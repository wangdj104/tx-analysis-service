package org.familyhealthcare.service.careplan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.careplan.CarePlanData.*;

/** Private draft mutations. Published bodies remain immutable; publishing is a separate operation. */
@Service
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
public class CarePlanService {
    private final JdbcTemplate jdbc;private final CarePlanAuthorizationService auth;private final CarePlanProperties properties;
    private final CarePlanQueryService query;private final CarePlanCommandStore commands;private final CarePlanEventStore events;
    public CarePlanService(JdbcTemplate jdbc,CarePlanAuthorizationService auth,CarePlanProperties properties,CarePlanQueryService query,CarePlanCommandStore commands,CarePlanEventStore events){this.jdbc=jdbc;this.auth=auth;this.properties=properties;this.query=query;this.commands=commands;this.events=events;}

    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object>createDraft(long actorId,Map<String,Object>body,String commandKey){
        properties.requireEnabled();mutationTransaction(jdbc);CarePlanCommandStore.validateKey(commandKey);Map<String,Object>normalized=normalize(body);long patient=id(normalized.get("patientId"));auth.requireClinical(actorId,patient);
        String payload=json(map("operation","CREATE_DRAFT","body",normalized));
        Map<String,Object>result=commands.executeCreate(actorId,commandKey,payload,()->{
            auth.requireClinical(actorId,patient);validateReferences(actorId,patient,normalized);validateLegacy(patient,normalized.get("legacySourceId"));
            Instant now=properties.now();GeneratedKeyHolder key=new GeneratedKeyHolder();
            // Legacy APIs never receive a clinical draft body, including after disabling the feature.
            jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("INSERT INTO doctor_care_plan(doctor_user_id,patient_id,title,instructions,plan_type,status,workflow_version,lifecycle,legacy_source_id,lock_version,created_at,updated_at) VALUES(?,?,'协作照护计划','','FOLLOW_UP','COLLABORATION',1,'DRAFT',?,0,?,?)",new String[]{"id"});
                ps.setLong(1,actorId);ps.setLong(2,patient);if(normalized.get("legacySourceId")==null)ps.setNull(3,Types.BIGINT);else ps.setLong(3,id(normalized.get("legacySourceId")));time(ps,4,now);time(ps,5,now);return ps;},key);
            long plan=key.getKey().longValue();long revision=insertRevision(plan,1,actorId,normalized,now);
            jdbc.update("UPDATE doctor_care_plan SET draft_revision_id=? WHERE id=?",revision,plan);
            events.append(patient,plan,revision,null,actorId,"DRAFT_CREATED",map("revisionNo",1));return query.detail(actorId,plan);
        });
        return query.refreshEvidence(actorId,result);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object>saveDraft(long actorId,long planId,long revisionId,Map<String,Object>body,String commandKey,long expectedVersion){
        properties.requireEnabled();mutationTransaction(jdbc);version(expectedVersion);CarePlanCommandStore.validateKey(commandKey);
        Map<String,Object>plan=lock(actorId,planId);Map<String,Object>normalized=normalize(body);long patient=id(plan.get("patientId"));
        if(id(normalized.get("patientId"))!=patient)throw CarePlanException.denied();
        // Legacy provenance is set only at creation; a save cannot silently switch source records.
        Object legacy=plan.get("legacySourceId");if(normalized.get("legacySourceId")!=null&&!Objects.equals(legacy,normalized.get("legacySourceId")))throw CarePlanException.invalid("legacySourceId 不可更改。");
        normalized.put("legacySourceId",legacy);
        String payload=json(map("operation","SAVE_DRAFT","planId",planId,"revisionId",revisionId,"expectedVersion",expectedVersion,"body",normalized));
        Map<String,Object>result=commands.execute(actorId,commandKey,payload,planId,expectedVersion,()->{
            auth.requireClinical(actorId,patient);requireDraft(plan,revisionId);requireVersion(plan,expectedVersion);validateReferences(actorId,patient,normalized);
            jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("UPDATE care_plan_revision SET title=?,instructions=?,plan_type=?,draft_json=?,updated_at=? WHERE id=? AND plan_id=? AND status='DRAFT'");
                ps.setString(1,(String)normalized.get("title"));ps.setString(2,(String)normalized.get("instructions"));ps.setString(3,(String)normalized.get("planType"));ps.setString(4,json(normalized));time(ps,5,properties.now());ps.setLong(6,revisionId);ps.setLong(7,planId);return ps;});
            advance(planId,expectedVersion);events.append(patient,planId,revisionId,null,actorId,"DRAFT_SAVED",map("version",expectedVersion+1));return query.revision(actorId,planId,revisionId);
        });
        return query.refreshEvidence(actorId,result);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object>createRevision(long actorId,long planId,String commandKey,long expectedVersion){
        properties.requireEnabled();mutationTransaction(jdbc);version(expectedVersion);CarePlanCommandStore.validateKey(commandKey);Map<String,Object>plan=lock(actorId,planId);long patient=id(plan.get("patientId"));
        String payload=json(map("operation","CREATE_REVISION","planId",planId,"expectedVersion",expectedVersion));
        Map<String,Object>result=commands.execute(actorId,commandKey,payload,planId,expectedVersion,()->{
            auth.requireClinical(actorId,patient);requireVersion(plan,expectedVersion);
            if(!"ACTIVE".equals(plan.get("lifecycle"))||plan.get("currentRevisionId")==null||plan.get("draftRevisionId")!=null)throw conflict();
            Map<String,Object>source=jdbc.queryForObject("SELECT draft_json FROM care_plan_revision WHERE id=? AND plan_id=? AND status='PUBLISHED'",(rs,i)->object(rs.getString("draft_json")),plan.get("currentRevisionId"),planId);
            Map<String,Object>normalized=normalize(source);validateReferences(actorId,patient,normalized);
            int number=jdbc.queryForObject("SELECT MAX(revision_no)+1 FROM care_plan_revision WHERE plan_id=?",Integer.class,planId);
            long revision=insertRevision(planId,number,actorId,normalized,properties.now());
            jdbc.update("UPDATE doctor_care_plan SET draft_revision_id=? WHERE id=?",revision,planId);advance(planId,expectedVersion);
            events.append(patient,planId,revision,null,actorId,"REVISION_CREATED",map("revisionNo",number));return query.detail(actorId,planId);
        });
        return query.refreshEvidence(actorId,result);
    }
    private Map<String,Object>lock(long actor,long plan){
        mutationTransaction(jdbc);List<Map<String,Object>>rows=jdbc.query("SELECT patient_id,lifecycle,current_revision_id,draft_revision_id,legacy_source_id,lock_version FROM doctor_care_plan WHERE id=? AND workflow_version=1 FOR UPDATE",(rs,i)->map("patientId",rs.getLong("patient_id"),"lifecycle",rs.getString("lifecycle"),"currentRevisionId",nullableId(rs,"current_revision_id"),"draftRevisionId",nullableId(rs,"draft_revision_id"),"legacySourceId",nullableId(rs,"legacy_source_id"),"version",rs.getLong("lock_version")),plan);
        if(rows.isEmpty())throw CarePlanException.denied();Map<String,Object>row=rows.get(0);auth.requireClinical(actor,id(row.get("patientId")));return row;
    }
    private void requireDraft(Map<String,Object>plan,long revision){
        if(!Objects.equals(plan.get("draftRevisionId"),revision))throw CarePlanException.denied();
        if(!Arrays.asList("DRAFT","ACTIVE").contains(plan.get("lifecycle")))throw conflict();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_revision WHERE id=? AND status='DRAFT'",Integer.class,revision)!=1)throw conflict();
    }
    private static void requireVersion(Map<String,Object>plan,long expected){if(id(plan.get("version"))!=expected)throw conflict();}
    private void advance(long plan,long expected){int changed=jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("UPDATE doctor_care_plan SET lock_version=lock_version+1,updated_at=? WHERE id=? AND lock_version=?");time(ps,1,properties.now());ps.setLong(2,plan);ps.setLong(3,expected);return ps;});if(changed!=1)throw conflict();}
    private long insertRevision(long plan,int number,long actor,Map<String,Object>body,Instant now){
        GeneratedKeyHolder key=new GeneratedKeyHolder();jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("INSERT INTO care_plan_revision(plan_id,revision_no,title,instructions,plan_type,draft_json,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            ps.setLong(1,plan);ps.setInt(2,number);ps.setString(3,(String)body.get("title"));ps.setString(4,(String)body.get("instructions"));ps.setString(5,(String)body.get("planType"));ps.setString(6,json(body));ps.setLong(7,actor);time(ps,8,now);time(ps,9,now);return ps;},key);return key.getKey().longValue();
    }
    private void validateLegacy(long patient,Object legacy){if(legacy!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM doctor_care_plan WHERE id=? AND patient_id=? AND workflow_version=0",Integer.class,legacy,patient)!=1)throw CarePlanException.denied();}
    private void validateReferences(long actor,long patient,Map<String,Object>body){for(Map<String,Object>action:actions(body)){auth.requireRecord(id(action.get("assignedUserId")),patient);for(Map<String,Object>ref:evidence(action))if(!auth.canReadEvidence(actor,patient,(String)ref.get("sourceType"),id(ref.get("sourceId"))))throw CarePlanException.denied();}}
    private static Map<String,Object>normalize(Map<String,Object>body){
        try {CarePlanContracts.validateDraft(body);Map<String,Object>normalized=map("patientId",CarePlanContracts.requireId(body.get("patientId"),"patientId"),"title",CarePlanContracts.requireText(body.get("title"),"title",160),"instructions",CarePlanContracts.requireText(body.get("instructions"),"instructions",4000),"planType",body.get("planType"));
            normalized.put("legacySourceId",body.get("legacySourceId")==null?null:CarePlanContracts.requireId(body.get("legacySourceId"),"legacySourceId"));List<Map<String,Object>>actions=new ArrayList<>();
            for(Map<String,Object>input:CarePlanData.actions(body)){List<Map<String,Object>>refs=new ArrayList<>();for(Map<String,Object>ref:evidence(input))refs.add(map("sourceType",ref.get("sourceType"),"sourceId",CarePlanContracts.requireId(ref.get("sourceId"),"sourceId")));
                actions.add(map("ordinal",((Number)input.get("ordinal")).intValue(),"instruction",CarePlanContracts.requireText(input.get("instruction"),"instruction",2000),"dueAt",CarePlanContracts.parseOffsetInstant((String)input.get("dueAt")).toString(),"assignedUserId",CarePlanContracts.requireId(input.get("assignedUserId"),"assignedUserId"),"evidence",refs));}
            normalized.put("actions",actions);return normalized;
        }catch(IllegalArgumentException ex){throw CarePlanException.invalid(ex.getMessage());}
    }
}
