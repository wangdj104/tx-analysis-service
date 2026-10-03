package org.familyhealthcare.service.careplan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;

import static org.familyhealthcare.service.careplan.CarePlanData.*;

/** Append-only feedback and clinical review, serialized by the care-plan aggregate lock. */
@Service
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
public class CarePlanActionService {
    private final JdbcTemplate jdbc;
    private final CarePlanAuthorizationService auth;
    private final CarePlanProperties properties;
    private final CarePlanCommandStore commands;
    private final CarePlanEventStore events;
    private final CarePlanNotificationQueue notifications;

    /** An enabled action service cannot silently drop the transactional notification outbox. */
    public CarePlanActionService(JdbcTemplate jdbc,CarePlanAuthorizationService auth,CarePlanProperties properties,
                                 CarePlanCommandStore commands,CarePlanEventStore events,CarePlanNotificationQueue notifications){
        this.jdbc=jdbc;this.auth=auth;this.properties=properties;this.commands=commands;this.events=events;
        this.notifications=Objects.requireNonNull(notifications,"notifications");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object>submit(long actorId,long actionId,Map<String,Object>body,String commandKey,long expectedVersion){
        return mutate("SUBMIT_RECEIPT",actorId,actionId,body,commandKey,expectedVersion);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object>help(long actorId,long actionId,Map<String,Object>body,String commandKey,long expectedVersion){
        return mutate("REQUEST_HELP",actorId,actionId,body,commandKey,expectedVersion);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object>followUp(long actorId,long actionId,Map<String,Object>body,String commandKey,long expectedVersion){
        return mutate("FOLLOW_UP",actorId,actionId,body,commandKey,expectedVersion);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object>review(long actorId,long actionId,Map<String,Object>body,String commandKey,long expectedVersion){
        return mutate("REVIEW_RECEIPT",actorId,actionId,body,commandKey,expectedVersion);
    }

    private Map<String,Object>mutate(String operation,long actor,long actionId,Map<String,Object>body,String commandKey,long expectedVersion){
        properties.requireEnabled();mutationTransaction(jdbc);version(expectedVersion);CarePlanCommandStore.validateKey(commandKey);
        // The only pre-lock lookup returns an immutable parent identifier, never clinical content.
        List<Long>parents=jdbc.queryForList("SELECT plan_id FROM care_plan_action WHERE id=?",Long.class,actionId);
        if(parents.isEmpty())throw CarePlanException.denied();long planId=parents.get(0);
        List<Map<String,Object>>plans=jdbc.query("SELECT patient_id,lifecycle,current_revision_id,lock_version FROM doctor_care_plan WHERE id=? AND workflow_version=1 FOR UPDATE",
                (rs,i)->map("patientId",rs.getLong("patient_id"),"lifecycle",rs.getString("lifecycle"),"currentRevisionId",nullableId(rs,"current_revision_id"),"version",rs.getLong("lock_version")),planId);
        if(plans.isEmpty())throw CarePlanException.denied();Map<String,Object>plan=plans.get(0);long patient=id(plan.get("patientId"));
        requireAuthority(operation,actor,patient);
        Map<String,Object>normalized=normalize(operation,body);
        validateReceiptAuthority(operation,actor,patient,normalized);
        // Lock order is always plan -> action -> command, including a matching command replay.
        List<Map<String,Object>>actions=jdbc.query("SELECT revision_id,patient_id,status,lock_version FROM care_plan_action WHERE id=? AND plan_id=? FOR UPDATE",
                (rs,i)->map("revisionId",rs.getLong("revision_id"),"patientId",rs.getLong("patient_id"),"status",rs.getString("status"),"version",rs.getLong("lock_version")),actionId,planId);
        if(actions.isEmpty()||id(actions.get(0).get("patientId"))!=patient)throw CarePlanException.denied();Map<String,Object>action=actions.get(0);
        String payload=json(map("operation",operation,"planId",planId,"actionId",actionId,"expectedVersion",expectedVersion,"body",normalized));
        Map<String,Object>result=commands.execute(actor,commandKey,payload,planId,expectedVersion,()->{
            requireAuthority(operation,actor,patient);validateReceiptAuthority(operation,actor,patient,normalized);
            if(id(plan.get("version"))!=expectedVersion)throw conflict();
            long revision=id(action.get("revisionId"));String before=(String)action.get("status");
            if(!"ACTIVE".equals(plan.get("lifecycle"))||!Objects.equals(plan.get("currentRevisionId"),revision))throw conflict();
            String after,eventType;
            if("SUBMIT_RECEIPT".equals(operation)){
                requireState(before,"OPEN","NEEDS_HELP");after="SUBMITTED";eventType="RECEIPT_SUBMITTED";
            }else if("REQUEST_HELP".equals(operation)){
                requireState(before,"OPEN");after="NEEDS_HELP";eventType="HELP_REQUESTED";
            }else if("FOLLOW_UP".equals(operation)){
                requireState(before,"OPEN","NEEDS_HELP","SUBMITTED");after=before;eventType="FOLLOW_UP_RECORDED";
            }else{
                requireState(before,"SUBMITTED");boolean confirm="CONFIRM".equals(normalized.get("decision"));
                after=confirm?"CONFIRMED":"OPEN";eventType=confirm?"RECEIPT_CONFIRMED":"RECEIPT_RETURNED";
            }
            Instant now=properties.now();long childVersion=id(action.get("version"));
            updateAction(actionId,childVersion,after,operation,now);
            int updated=jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("UPDATE doctor_care_plan SET lock_version=lock_version+1,updated_at=? WHERE id=? AND lock_version=?");time(ps,1,now);ps.setLong(2,planId);ps.setLong(3,expectedVersion);return ps;});
            if(updated!=1)throw conflict();
            Map<String,Object>eventBody=new LinkedHashMap<>(normalized);
            eventBody.put("version",expectedVersion+1);eventBody.put("actionVersion",childVersion+1);eventBody.put("actionStatus",after);
            long eventId=events.append(patient,planId,revision,actionId,actor,eventType,eventBody);
            notifications.enqueue(eventId);
            return map("planId",planId,"actionId",actionId,"eventId",eventId,"version",expectedVersion+1,"lifecycle","ACTIVE","actionStatus",after);
        });
        // A matching replay can wait on locks; use current authority and evidence, never cached grants.
        try{requireAuthority(operation,actor,patient);validateReceiptAuthority(operation,actor,patient,normalized);}
        catch(RuntimeException denied){
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("A failed care-plan action authorization cannot commit.");}
            });
            throw denied;
        }
        return result;
    }

    private void updateAction(long actionId,long childVersion,String status,String operation,Instant now){
        String sql="UPDATE care_plan_action SET status=?,lock_version=lock_version+1,updated_at=?";
        if("SUBMIT_RECEIPT".equals(operation))sql+=",first_submitted_at=COALESCE(first_submitted_at,?),latest_submitted_at=?,review_waiting_since=?";
        else if("REVIEW_RECEIPT".equals(operation))sql+=",review_waiting_since=NULL";
        final String statement=sql+" WHERE id=? AND lock_version=?";
        int updated=jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement(statement);int n=1;ps.setString(n++,status);time(ps,n++,now);
            if("SUBMIT_RECEIPT".equals(operation)){time(ps,n++,now);time(ps,n++,now);time(ps,n++,now);}ps.setLong(n++,actionId);ps.setLong(n,childVersion);return ps;});
        if(updated!=1)throw conflict();
    }
    private void requireAuthority(String operation,long actor,long patient){
        if("REVIEW_RECEIPT".equals(operation))auth.requireClinical(actor,patient);
        else if("FOLLOW_UP".equals(operation)){
            try{auth.requireClinical(actor,patient);}catch(CarePlanException denied){if(denied.getStatus()!=403)throw denied;auth.requireNursing(actor,patient);}
        }else auth.requireRecord(actor,patient);
    }
    private void validateReceiptAuthority(String operation,long actor,long patient,Map<String,Object>body){
        if(!"SUBMIT_RECEIPT".equals(operation))return;
        if("SELF".equals(body.get("entryMode"))&&jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE id=? AND user_id=?",Integer.class,patient,actor)!=1)throw CarePlanException.denied();
        for(Map<String,Object>ref:evidence(body))if(!auth.canReadEvidence(actor,patient,(String)ref.get("sourceType"),id(ref.get("sourceId"))))throw CarePlanException.denied();
    }
    private Map<String,Object>normalize(String operation,Map<String,Object>body){
        try{
            if("SUBMIT_RECEIPT".equals(operation)){
                CarePlanContracts.validateReceipt(body);Instant occurred=CarePlanContracts.parseOffsetInstant((String)body.get("occurredAt"));
                if(occurred.isAfter(properties.now()))throw CarePlanException.invalid("occurredAt cannot be in the future.");
                List<Map<String,Object>>refs=new ArrayList<>();for(Map<String,Object>ref:evidence(body))refs.add(map("sourceType",ref.get("sourceType"),"sourceId",CarePlanContracts.requireId(ref.get("sourceId"),"sourceId")));
                return map("note",CarePlanContracts.requireText(body.get("note"),"note",2000),"occurredAt",occurred.toString(),"entryMode",body.get("entryMode"),"evidence",refs);
            }
            if("REQUEST_HELP".equals(operation)){
                fields(body,"note");return map("note",CarePlanContracts.requireText(body.get("note"),"note",1000));
            }
            if("FOLLOW_UP".equals(operation)){
                fields(body,"kind","note");Object kind=body.get("kind");if(!Arrays.asList("CONTACTED","AWAITING_INFORMATION","DOCTOR_NOTIFIED").contains(kind))throw CarePlanException.invalid("Unsupported follow-up kind.");
                return map("kind",kind,"note",CarePlanContracts.requireText(body.get("note"),"note",1000));
            }
            fields(body,"decision","note");Object decision=body.get("decision");if(!Arrays.asList("CONFIRM","RETURN").contains(decision))throw CarePlanException.invalid("Unsupported review decision.");
            String note="RETURN".equals(decision)?CarePlanContracts.requireText(body.get("note"),"note",1000):body.get("note")==null?null:CarePlanContracts.requireText(body.get("note"),"note",2000);
            return map("decision",decision,"note",note);
        }catch(IllegalArgumentException ex){throw CarePlanException.invalid(ex.getMessage());}
    }
    private static void fields(Map<String,Object>body,String...allowed){
        if(body==null)throw CarePlanException.invalid("Request body is required.");List<String>fields=Arrays.asList(allowed);
        for(String name:body.keySet())if(!fields.contains(name))throw CarePlanException.invalid("Unsupported request field: "+name);
    }
    private static void requireState(String state,String...allowed){if(!Arrays.asList(allowed).contains(state))throw conflict();}
}
