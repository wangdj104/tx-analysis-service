package org.familyhealthcare.service.familyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.familyassistance.FamilyAssistanceContracts.*;
import static org.familyhealthcare.service.familyassistance.FamilyAssistanceRepository.*;
import static org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.denied;
@Service public class FamilyAssistanceService {
    private final JdbcTemplate jdbc;
    private final org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge visitFollowUp;
    private final FamilyAssistanceProperties properties;
    private final FamilyAssistanceAuthorizationService auth;
    private final FamilyAssistanceRepository repo;
    private final FamilyAssistanceEventStore events;
    public FamilyAssistanceService(JdbcTemplate jdbc,FamilyAssistanceProperties properties,FamilyAssistanceAuthorizationService auth,FamilyAssistanceRepository repo,FamilyAssistanceEventStore events,org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge visitFollowUp) {
        this.visitFollowUp=java.util.Objects.requireNonNull(visitFollowUp);
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.events=events;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> command(long actor,Long assistanceId,AssistanceCommand c) {
        mutationTransaction(jdbc);
        if((assistanceId==null)!=c.operation.equals("CREATE"))throw WorkflowException.invalid("CREATE requires a new request.");
        Map<String,Object> discovered=assistanceId==null?null:repo.assistance(assistanceId);
        if(assistanceId!=null&&discovered==null)throw denied();
        String kind=discovered==null?c.sourceKind:(String)discovered.get("source_kind");
        long source=discovered==null?(c.careItemId!=null?c.careItemId:c.careAppointmentId):sourceId(discovered);
        Map<String,Object> s=repo.source(kind,source);
        if(s==null||id(s.get("patient_id"))!=c.patientId)throw denied();
        String requestKind=repo.requestKind(kind,s);
        auth.requireWrite(actor,c.patientId,requestKind);
        repo.admit(actor,c.patientId,kind,source,assistanceId,c.assignedUserId,c.commandKey,null,null);
        auth.requireWrite(actor,c.patientId,requestKind);
        Map<String,Object> result=WorkflowCommandExecutor.execute(jdbc,WorkflowCommandExecutor.Domain.FAMILY_ASSISTANCE,actor,c.commandKey,c.payload(assistanceId),assistanceId,c.expectedVersion,properties.now(),()-> {
            if("ACCOMPANIMENT".equals(requestKind))visitFollowUp.requireAssistanceAction(kind,source,c.operation);
            Map<String,Object> a;
            if(assistanceId==null) {
                if(!properties.isEnabled())throw new WorkflowException(503,"FEATURE_DISABLED","Family assistance is disabled.");
                if(c.expectedVersion!=0)throw conflict("VERSION_CONFLICT");
                a=create(actor,c,kind,source,requestKind);
            }
            else {
                a=repo.assistance(assistanceId);
                repo.requireIdentity(a,c.patientId,kind,source);
                if(id(a.get("lock_version"))!=c.expectedVersion)throw conflict("VERSION_CONFLICT");
                apply(actor,a,c);
            }
            long event=events.append(actor,a,c.operation.equals("CREATE")?"CREATE_REQUEST":c.operation,c.reason,map("sourceKind",kind,"sourceId",source,"state",a.get("state"),"assignedUserId",a.get("assigned_user_id"),"fingerprint",a.get("source_fingerprint")),null,null);
            return map("assistanceId",a.get("id"),"version",a.get("lock_version"),"eventId",event,"state",a.get("state"),"cycle",a.get("cycle"));
        }
        );
        requireMinimalReceipt(result);
        Map<String,Object> a=repo.assistance(id(result.get("assistanceId")));
        repo.requireIdentity(a,c.patientId,kind,source);
        auth.requireWrite(actor,c.patientId,(String)a.get("request_kind"));
        return result;
    }
    private Map<String,Object> create(long actor,AssistanceCommand c,String kind,long source,String requestKind) {
        Map<String,Object> s=repo.source(kind,source);
        Map<String,Object> previous=s.get("family_assistance_id")==null?null:repo.assistance(id(s.get("family_assistance_id")));
        if(previous!=null&&!terminal(previous))throw conflict("SOURCE_ALREADY_TRACKED");
        if(c.assignedUserId!=null)auth.requireWrite(c.assignedUserId,c.patientId,requestKind);
        long request=previous==null?1:id(previous.get("request_no"))+1;
        if(request>Integer.MAX_VALUE)throw conflict("REQUEST_OVERFLOW");
        String origin=json(map("kind","AUTHENTICATED_NEW_REQUEST","actorId",actor,"sourceKind",kind,"sourceId",source,"legacyOrigin","UNKNOWN","previousAssistanceId",previous==null?null:previous.get("id")));
        String fp="ACCOMPANIMENT".equals(requestKind)?(String)repo.minimal(kind,s).get("fingerprint"):null;
        long a=repo.insert("INSERT INTO family_assistance(patient_id,source_kind,care_item_id,care_appointment_id,request_kind,request_no,previous_assistance_id,originator_id,origin_evidence_json,origin_evidence_hash,state,lock_version,cycle,assigned_user_id,deadline_at,source_fingerprint,reconfirmed_fingerprint,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'REQUESTED',1,1,?,?,?,?,?,?)",c.patientId,kind,"CARE_ITEM".equals(kind)?source:null,"CARE_APPOINTMENT".equals(kind)?source:null,requestKind,request,previous==null?null:previous.get("id"),actor,origin,hash(origin),c.assignedUserId,c.deadlineAt,fp,fp,properties.now(),properties.now());
        String table="CARE_ITEM".equals(kind)?"care_item":"care_appointment";
        repo.update("UPDATE "+table+" SET family_workflow_version=1,family_assistance_id=? WHERE id=?",a,source);
        return repo.assistance(a);
    }
    private void apply(long actor,Map<String,Object> a,AssistanceCommand c) {
        // 历史收据可重放，但新的变更只能作用于来源当前指向的请求，不能使旧请求与后继双活跃。
        Map<String,Object> currentSource=repo.source((String)a.get("source_kind"),sourceId(a));
        if(currentSource==null||!Objects.equals(currentSource.get("family_assistance_id"),a.get("id"))||id(currentSource.get("family_workflow_version"))!=1)throw conflict("SOURCE_SUPERSEDED");
        String state=(String)a.get("state"),kind=(String)a.get("request_kind");
        long origin=id(a.get("originator_id")),patient=id(a.get("patient_id"));
        Long accepted=number(a.get("accepted_by"));
        boolean requester=actor==origin,owner=actor==auth.owner(patient);
        if(terminal(a)&&!c.operation.equals("REOPEN"))throw conflict("REQUEST_ENDED");
        Map<String,Object> change=new LinkedHashMap<>();
        switch(c.operation) {
            case "ASSIGN":if(!requester&&!owner)throw denied();
            if(!state.equals("REQUESTED"))throw conflict("FRESH_HANDOFF_REQUIRED");
            auth.requireWrite(c.assignedUserId,patient,kind);
            clear(change);
            change.put("assigned_user_id",c.assignedUserId);
            break;
            case "ACCEPT":if(!state.equals("REQUESTED")||!Objects.equals(number(a.get("assigned_user_id")),actor))throw conflict("NAMED_ACCEPTANCE_REQUIRED");
            freshAppointment(a);
            change.put("state","ACCEPTED");
            change.put("accepted_by",actor);
            change.put("accepted_at",properties.now());
            change.put("accepted_fingerprint",a.get("source_fingerprint"));
            break;
            case "REJECT":if(!Objects.equals(number(a.get("assigned_user_id")),actor))throw denied();
            clear(change);
            change.put("assigned_user_id",null);
            break;
            case "HANDOFF":if(!requester&&!owner&&!Objects.equals(accepted,actor))throw denied();
            auth.requireWrite(c.assignedUserId,patient,kind);
            clear(change);
            change.put("assigned_user_id",c.assignedUserId);
            break;
            case "HANDLE":if(!state.equals("ACCEPTED")||!Objects.equals(accepted,actor))throw denied();
            freshAppointment(a);
            change.put("state","AWAITING_CONFIRMATION");
            change.put("handled_by",actor);
            change.put("handled_at",properties.now());
            break;
            case "CONFIRM":if(!requester)throw denied();
            if(!state.equals("AWAITING_CONFIRMATION"))throw conflict("HANDLING_REQUIRED");
            freshAppointment(a);
            if(accepted==null||!auth.canWrite(accepted,patient,kind))throw conflict("HANDLER_ACCESS_LOST");
            change.put("state","COMPLETED");
            change.put("confirmed_at",properties.now());
            change.put("closed_at",properties.now());
            break;
            case "RETURN":if(!requester||!state.equals("AWAITING_CONFIRMATION"))throw denied();
            change.put("handled_by",null);
            change.put("handled_at",null);
            if(accepted!=null&&auth.canWrite(accepted,patient,kind)&&!changed(a)&&!visitFollowUp.terminalManaged((String)a.get("source_kind"),sourceId(a)))change.put("state","ACCEPTED");
            else clear(change);
            break;
            case "CANCEL":if(!requester)throw denied();
            close(change,"CANCELLED",c.reason);
            break;
            case "CLOSE_UNRESOLVED":if(!requester) {
                if(!owner||!Arrays.asList("ACCESS_LOST","ORIGINATOR_UNAVAILABLE").contains(c.reason)||c.reason.equals("ACCESS_LOST")&&auth.canWrite(origin,patient,kind))throw denied();
            }
            close(change,"CLOSED_UNRESOLVED",c.reason);
            break;
            case "REOPEN":if(!terminal(a)||!requester&&(!owner||state.equals("COMPLETED")))throw denied();
            if(id(a.get("cycle"))>=Integer.MAX_VALUE)throw conflict("CYCLE_OVERFLOW");
            clear(change);
            change.put("cycle",id(a.get("cycle"))+1);
            change.put("closed_at",null);
            change.put("close_reason",null);
            change.put("confirmed_at",null);
            if(a.get("assigned_user_id")!=null&&!auth.canWrite(id(a.get("assigned_user_id")),patient,kind))change.put("assigned_user_id",null);
            break;
            case "RECONFIRM_APPOINTMENT":if(!requester||!kind.equals("ACCOMPANIMENT")||!state.equals("REQUESTED"))throw denied();
            String fp=(String)repo.minimal((String)a.get("source_kind"),repo.source((String)a.get("source_kind"),sourceId(a))).get("fingerprint");
            if(!fp.equals(c.fingerprint))throw conflict("SOURCE_CHANGED");
            change.put("source_fingerprint",fp);
            change.put("reconfirmed_fingerprint",fp);
            change.put("source_changed",0);
            clear(change);
            break;
            default:throw WorkflowException.invalid("Invalid assistance action.");
        }
        long v=id(a.get("lock_version"));
        if(v>=9007199254740991L)throw conflict("VERSION_OVERFLOW");
        change.put("lock_version",v+1);
        change.put("updated_at",properties.now());
        List<Object> args=new ArrayList<>(change.values());
        args.add(a.get("id"));
        List<String> fields=new ArrayList<>();
        for(String field:change.keySet())fields.add(field+"=?");
        repo.update("UPDATE family_assistance SET "+String.join(",",fields)+" WHERE id=?",args.toArray());
        long aid=id(a.get("id"));
        a.clear();
        a.putAll(repo.assistance(aid));
    }
    private void freshAppointment(Map<String,Object> a) {
        if(!"ACCOMPANIMENT".equals(a.get("request_kind")))return;
        String fp=(String)repo.minimal((String)a.get("source_kind"),repo.source((String)a.get("source_kind"),sourceId(a))).get("fingerprint");
        if(changed(a)||!Objects.equals(fp,a.get("source_fingerprint"))||!Objects.equals(fp,a.get("reconfirmed_fingerprint"))||!"REQUESTED".equals(a.get("state"))&&!Objects.equals(fp,a.get("accepted_fingerprint")))throw conflict("APPOINTMENT_RECONFIRM_REQUIRED");
    }
    public static boolean terminal(Map<String,Object> a) {
        return Arrays.asList("COMPLETED","CANCELLED","CLOSED_UNRESOLVED").contains(a.get("state"));
    }
    public static boolean changed(Map<String,Object> a) {
        return id(a.get("source_changed"))!=0;
    }
    private static void clear(Map<String,Object> c) {
        c.put("state","REQUESTED");
        for(String k:Arrays.asList("accepted_by","accepted_at","accepted_fingerprint","handled_by","handled_at","confirmed_at"))c.put(k,null);
    }
    private void close(Map<String,Object> c,String state,String reason) {
        c.put("state",state);
        c.put("closed_at",properties.now());
        c.put("close_reason",reason);
    }
    private static Long number(Object o) {
        return o==null?null:id(o);
    }
    /** exact due key 属于请求与周期，即使没有有效接收者也写一次，不推断成功。 */
    public void markDue(Instant now) {
        Objects.requireNonNull(now);
        HouseholdWorkflowSchema.StorageReadiness ready=repo.schema.inspectStorageMetadata();
        // A-only 且关闭时没有到期事实可读；已有 tracked 即使关闭也仍记录真实 DUE。
        if(ready.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT&&!properties.isEnabled())return;
        ready.requireStructurallyReady();
        for(Map<String,Object> candidate:repo.rows("SELECT a.id,a.patient_id FROM family_assistance a WHERE a.state IN ('REQUESTED','ACCEPTED','AWAITING_CONFIRMATION') AND a.deadline_at<=? AND NOT EXISTS(SELECT 1 FROM family_assistance_event e WHERE e.due_key=CONCAT('FAMILY_ASSISTANCE:',a.id,':',a.cycle,':',DATE_FORMAT(a.deadline_at,'%Y-%m-%dT%H:%i:%s.%fZ'))) ORDER BY a.deadline_at,a.id LIMIT 100",now)) {
            org.springframework.transaction.support.TransactionTemplate transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.execute(tx-> {
                markOneDue(id(candidate.get("patient_id")),id(candidate.get("id")),now);
                return null;
            }
            );
        }
    }
    private void markOneDue(long patient,long assistance,Instant now) {
        Map<String,Object> current=repo.assistance(assistance);
        if(current==null||terminal(current)||current.get("deadline_at")==null)return;
        Instant exactDeadline=Instant.parse(iso(current.get("deadline_at")));
        if(exactDeadline.isAfter(now))return;
        try {
            repo.admitDue(patient,assistance,((Number)current.get("cycle")).intValue(),exactDeadline,now);
        }
        catch(WorkflowException changed) {
            if(changed.getStatus()==409)return;
            throw changed;
        }
        Map<String,Object> a=repo.assistance(assistance);
        String key="FAMILY_ASSISTANCE:"+a.get("id")+":"+a.get("cycle")+":"+java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(java.time.ZoneOffset.UTC).format(exactDeadline);
        if(repo.one("SELECT id FROM family_assistance_event WHERE due_key=?",key)!=null)return;
        events.append(null,a,"DUE",null,map("state",a.get("state")),exactDeadline,key);
    }
}
