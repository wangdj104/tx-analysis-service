package org.familyhealthcare.service.emergencyassistance;
import org.familyhealthcare.service.workflow.*;
import org.familyhealthcare.service.medicationexecution.MedicationExecutionQueryService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.emergencyassistance.EmergencyAssistanceRepository.*;
import static org.familyhealthcare.service.emergencyassistance.EmergencyAssistanceAuthorizationService.denied;
@Service public class EmergencyAssistanceQueryService {
    private final EmergencyAssistanceProperties properties;
    private final EmergencyAssistanceAuthorizationService auth;
    private final EmergencyAssistanceRepository repo;
    private final HouseholdWorkflowSchema schema;
    public EmergencyAssistanceQueryService(JdbcTemplate j,EmergencyAssistanceProperties p,EmergencyAssistanceAuthorizationService a,
    EmergencyAssistanceRepository r,HouseholdWorkflowSchema s){
        properties=p;
        auth=a;
        repo=r;
        schema=s;
    }
    public Map<String,Object> capabilities(long actor,long patient){
        auth.requireRead(actor,patient);
        HouseholdWorkflowSchema.StorageReadiness ready=schema.inspectStorageMetadata();
        boolean write=auth.canWrite(actor,patient),retry=admin(actor);
        auth.requireRead(actor,patient);
        if(write)auth.requireWrite(actor,patient);
        if(retry)auth.requireNotificationAdmin(actor);
        boolean read=ready.getStatus()==HouseholdWorkflowSchema.StorageStatus.STRUCTURALLY_READY;
        return map("actorId",actor,"patientId",patient,"enabled",properties.isEnabled(),"schemaStatus",
        ready.getStatus().name(),
        "blockedReasons",ready.getReasons(),"read",read,"create",read&&write&&properties.isEnabled(),
        "source",
        read,"write",write&&read,"retry",retry,"allowedCommands",read&&write&&properties.isEnabled()?Collections.singletonList("CREATE_REQUEST"):Collections.emptyList());
    }
    public Map<String,Object> list(long actor,long patient,String cursor,int limit){
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        long after=cursor(cursor,actor,patient,"LIST",0,limit);
        List<Map<String,Object>>rows=repo.rows("SELECT id,patient_id,workflow_state,response_status,requested_at_utc,lock_version FROM emergency_event WHERE patient_id=? AND workflow_version=1 AND id>? ORDER BY id LIMIT ?",
        patient,after,limit+1);
        List<Map<String,Object>>out=new ArrayList<>();
        for(Map<String,Object>r:rows)out.add(map("emergencyEventId",r.get("id"),"patientId",patient,"state",
        r.get("workflow_state"),
        "responseStatus",r.get("response_status"),"requestedAt",iso(r.get("requested_at_utc")),"version",
        r.get("lock_version")));
        auth.requireRead(actor,patient);
        return page(out,limit,actor,patient,"LIST",0,"emergencyEventId");
    }
    public Map<String,Object> detail(long actor,long id){
        Map<String,Object>a=repo.emergency(id);
        if(a==null)throw denied();
        long patient=id(a.get("patient_id"));
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        repo.requireIdentity(a,patient);
        boolean write=auth.canWrite(actor,patient),owner=actor==auth.owner(patient),requester=actor==id(a.get("requester_user_id")),
        lead=Objects.equals(number(a.get("current_lead_id")),actor),leadCurrent=a.get("current_lead_id")!=null&&auth.canWrite(id(a.get("current_lead_id")),
        patient);
        List<String>allowed=new ArrayList<>();
        if(write&&!EmergencyAssistanceService.terminal(a)){
            if(!requester&&repo.invited(id,actor)&&(!leadCurrent||Objects.equals(number(a.get("pending_successor_id")),
            actor)))allowed.add("ACCEPT");
            if(lead)allowed.add("PROGRESS");
            if(owner||requester||lead)allowed.addAll(Arrays.asList("HANDOFF","RESOLVE"));
            if(owner||requester)allowed.add("CANCEL_MISTAKE");
        }
        boolean retry=admin(actor);
        Map<String,Object>out=map("emergencyEventId",id,"patientId",patient,"state",a.get("workflow_state"),
        "responseStatus",
        a.get("response_status"),"version",a.get("lock_version"),"cycle",1,"requesterId",a.get("requester_user_id"),
        "currentLeadId",a.get("current_lead_id"),"leadCurrent",leadCurrent,"pendingSuccessorId",a.get("pending_successor_id"),
        "requestedAt",iso(a.get("requested_at_utc")),"acceptedAt",iso(a.get("accepted_at_utc")),"lastProgressAt",
        iso(a.get("last_progress_at_utc")),"resolvedAt",iso(a.get("resolved_at_utc")),"outcomeCode",a.get("outcome_code"),
        "outcomeReason",a.get("outcome_reason"),"locationText",a.get("location_text"),"latitude",a.get("latitude"),
        "longitude",a.get("longitude"),"snapshot",object((String)a.get("snapshot_json")),"enabled",properties.isEnabled(),
        "schemaStatus","STRUCTURALLY_READY","blockedReasons",leadCurrent||a.get("current_lead_id")==null?Collections.emptyList():Collections.singletonList("LEAD_ACCESS_LOST"),
        "allowedCommands",allowed,"read",true,"write",write,"create",false,"source",true,"retry",retry,
        "safetyNotice",
        "Records and contact attempts only. No response or medical safety is guaranteed. In an emergency contact local emergency services.");
        auth.requireRead(actor,patient);
        if(write)auth.requireWrite(actor,patient);
        if(retry)auth.requireNotificationAdmin(actor);
        return out;
    }
    public Map<String,Object> events(long actor,long target,String cursor,int limit){
        Map<String,Object>d=detail(actor,target);
        long p=id(d.get("patientId")),after=cursor(cursor,actor,p,"EVENTS",target,limit);
        List<Map<String,Object>>r=repo.rows("SELECT id,type,aggregate_version,cycle,actor_id,occurred_at,reason,evidence_json,evidence_hash FROM emergency_assistance_event WHERE emergency_event_id=? AND patient_id=? AND id>? ORDER BY id LIMIT ?",
        target,p,after,limit+1);
        for(Map<String,Object>row:r){
            String data=(String)row.remove("evidence_json");
            if(!hash(data).equals(row.remove("evidence_hash")))throw conflict("EVENT_INVALID");
            row.put("occurred_at",iso(row.get("occurred_at")));
            Map<String,Object>evidence=object(data);
            row.put("actualAt",evidence.get("actualAt"));
            row.put("note",evidence.get("note"));
            row.put("outcomeCode",evidence.get("outcomeCode"));
        }
        auth.requireRead(actor,p);
        return page(r,limit,actor,p,"EVENTS",target,"id");
    }
    public Map<String,Object> commandReceipt(long actor,long patient,String key){
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        key=WorkflowCommandExecutor.validateKey(key);
        Map<String,Object>c=repo.one("SELECT emergency_event_id,result_json FROM emergency_assistance_command WHERE actor_id=? AND command_key=?",
        actor,key);
        if(c==null){
            auth.requireRead(actor,patient);
            return null;
        }
        repo.requireIdentity(repo.emergency(id(c.get("emergency_event_id"))),patient);
        Map<String,Object>r=object((String)c.get("result_json"));
        EmergencyAssistanceContracts.requireMinimalReceipt(r);
        auth.requireRead(actor,patient);
        return r;
    }
    private boolean admin(long actor){
        try{
            auth.requireNotificationAdmin(actor);
            return true;
        }
        catch(WorkflowException e){
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    private static Long number(Object o){
        return o==null?null:id(o);
    }
    public static long cursor(String c,long a,long p,String lane,long target,int limit){
        return MedicationExecutionQueryService.cursor(c,a,p,"EMERGENCY_"+lane,target,limit);
    }
    public static String next(long a,long p,String lane,long target,long last){
        return MedicationExecutionQueryService.nextCursor(a,p,"EMERGENCY_"+lane,target,last);
    }
    public static Map<String,Object>page(List<Map<String,Object>>rows,int limit,long a,long p,String lane,
    long target,String key){
        return MedicationExecutionQueryService.page(rows,limit,a,p,"EMERGENCY_"+lane,target,key);
    }
}
