package org.familyhealthcare.service.emergencyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.emergencyassistance.EmergencyAssistanceContracts.*;
import static org.familyhealthcare.service.emergencyassistance.EmergencyAssistanceRepository.*;
import static org.familyhealthcare.service.emergencyassistance.EmergencyAssistanceAuthorizationService.denied;
@Service public class EmergencyAssistanceService {
    private final JdbcTemplate jdbc;
    private final EmergencyAssistanceProperties properties;
    private final EmergencyAssistanceAuthorizationService auth;
    private final EmergencyAssistanceRepository repo;
    private final EmergencyAssistanceEventStore events;
    private final EmergencyInformationSnapshotService snapshot;
    public EmergencyAssistanceService(JdbcTemplate j,EmergencyAssistanceProperties p,EmergencyAssistanceAuthorizationService a,
    EmergencyAssistanceRepository r,EmergencyAssistanceEventStore e,EmergencyInformationSnapshotService s){
        jdbc=j;
        properties=p;
        auth=a;
        repo=r;
        events=e;
        snapshot=s;
    }
    /** 首个新命令才冻结信息与邀请；合法重放只披露最小回执，仍须当前权限。 */
    @Transactional(isolation=Isolation.READ_COMMITTED,timeout=5) public Map<String,Object> command(long actor,
    Long emergencyEventId,EmergencyCommand c){
        mutationTransaction(jdbc);
        if((emergencyEventId==null)!=c.operation.equals("CREATE_REQUEST"))throw WorkflowException.invalid("Create a new request for each incident.");
        auth.requireWrite(actor,c.patientId);
        HouseholdAuthorityLocks.LockedAuthority token=repo.admit(actor,c.patientId,emergencyEventId,c.successorUserId,
        c.commandKey,null,null,emergencyEventId==null);
        auth.requireWrite(actor,c.patientId);
        Map<String,Object> result=WorkflowCommandExecutor.execute(jdbc,WorkflowCommandExecutor.Domain.EMERGENCY_ASSISTANCE,
        actor,c.commandKey,c.payload(emergencyEventId),emergencyEventId,c.expectedVersion,properties.now(),
        ()->{
            Map<String,Object>a;
            SortedSet<Long> invitations=new TreeSet<>();
            Map<Long,List<Long>> plan;
            Long event=null;
            if(emergencyEventId==null){
                if(!properties.isEnabled())throw new WorkflowException(503,"FEATURE_DISABLED","Emergency requests are disabled. Contact local emergency services if needed.");
                if(c.expectedVersion!=0)throw conflict("VERSION_CONFLICT");
                for(long u:token.getUserIds())if(u!=actor&&auth.canRead(u,
                c.patientId))invitations.add(u);
                plan=events.queue.plan(c.patientId,invitations);
                String data=json(snapshot.project(c.patientId));
                auth.requireWrite(actor,
                c.patientId);
                long id=repo.insert("INSERT INTO emergency_event(patient_id,triggered_by,latitude,longitude,location_text,snapshot_json,status,notified_user_ids,workflow_version,lock_version,workflow_state,response_status,requester_user_id,requested_at_utc,snapshot_hash) VALUES(?,?,?,?,?,?,'TRIGGERED',NULL,1,1,'REQUESTED','UNANSWERED',?,?,?)",
                c.patientId,actor,c.latitude,c.longitude,c.locationText,data,actor,properties.now(),hash(data));
                a=repo.emergency(id);
            }
            else{
                a=repo.emergency(emergencyEventId);
                repo.requireIdentity(a,c.patientId);
                if(id(a.get("lock_version"))!=c.expectedVersion)throw conflict("VERSION_CONFLICT");
                if(c.operation.equals("HANDOFF")){
                    auth.requireWrite(c.successorUserId,c.patientId);
                    invitations.add(c.successorUserId);
                }
                SortedSet<Long> recipients=new TreeSet<>();
                List<Map<String,Object>> frozen=repo.rows("SELECT recipient_user_id FROM emergency_assistance_recipient WHERE emergency_event_id=? ORDER BY recipient_user_id LIMIT 41",
                emergencyEventId);
                if(frozen.size()>40||!invitations.isEmpty()&&frozen.size()==40&&!repo.invited(emergencyEventId,
                c.successorUserId))throw conflict("AUDIENCE_LIMIT");
                for(Map<String,Object> r:frozen){
                    long u=id(r.get("recipient_user_id"));
                    if(auth.canRead(u,c.patientId))recipients.add(u);
                }
                recipients.addAll(invitations);
                if(recipients.size()>40)throw conflict("AUDIENCE_LIMIT");
                plan=events.queue.plan(c.patientId,
                recipients);
                if(c.operation.equals("HANDOFF")){
                    Map<String,Object> future=new LinkedHashMap<>(a);
                    future.put("lock_version",id(a.get("lock_version"))+1);
                    event=events.append(actor,
                    future,c.operation,c.reason,map("invitedUserIds",new ArrayList<>(invitations)));
                    for(long u:invitations)repo.inviteLocked(id(a.get("id")),
                    u,event);
                }
                apply(actor,a,c);
            }
            if(event==null)event=events.append(actor,a,c.operation,c.reason,map("state",a.get("workflow_state"),
            "responseStatus",a.get("response_status"),"actualAt",c.actualAt==null?null:c.actualAt.toString(),
            "note",
            c.note,"outcomeCode",c.outcomeCode,"invitedUserIds",new ArrayList<>(invitations)));
            for(long u:invitations)repo.inviteLocked(id(a.get("id")),
            u,event);
            events.queue.enqueue(event,plan);
            return map("emergencyEventId",a.get("id"),"version",a.get("lock_version"),
            "eventId",event,"state",a.get("workflow_state"),"cycle",1);
        }
        );
        requireMinimalReceipt(result);
        repo.requireIdentity(repo.emergency(id(result.get("emergencyEventId"))),c.patientId);
        auth.requireWrite(actor,c.patientId);
        return result;
    }
    /** 人工接手与投递分开；交接保留历史首次回应时间，不把状态倒退。 */
    private void apply(long actor,Map<String,Object>a,EmergencyCommand c){
        if(terminal(a))throw conflict("REQUEST_ENDED");
        long patient=id(a.get("patient_id")),requester=id(a.get("requester_user_id"));
        Long lead=number(a.get("current_lead_id")),pending=number(a.get("pending_successor_id"));
        boolean owner=actor==auth.owner(patient),origin=actor==requester,accepted=Objects.equals(lead,
        actor);
        Map<String,Object> change=new LinkedHashMap<>();
        switch(c.operation){
            case "ACCEPT":if(origin||!repo.invited(id(a.get("id")),actor))throw denied();
            if(lead!=null&&auth.canWrite(lead,patient)&&!Objects.equals(pending,actor))throw conflict("LEAD_ALREADY_ACCEPTED");
            change.put("current_lead_id",actor);
            change.put("pending_successor_id",null);
            if(a.get("accepted_at_utc")==null)change.put("accepted_at_utc",properties.now());
            change.put("response_status","ANSWERED");
            if("REQUESTED".equals(a.get("workflow_state")))change.put("workflow_state","ACCEPTED");
            break;
            case "PROGRESS":if(!accepted)throw denied();
            if(c.actualAt.isAfter(properties.now())||c.actualAt.isBefore(java.time.Instant.parse(iso(a.get("requested_at_utc"))))||a.get("last_progress_at_utc")!=null&&c.actualAt.isBefore(java.time.Instant.parse(iso(a.get("last_progress_at_utc")))))throw WorkflowException.invalid("Progress must record an actual UTC instant after this request and previous progress.");
            change.put("last_progress_at_utc",c.actualAt);
            change.put("workflow_state","IN_PROGRESS");
            break;
            case "HANDOFF":if(!origin&&!owner&&!accepted||c.successorUserId==requester||Objects.equals(lead,
            c.successorUserId))throw denied();
            auth.requireWrite(c.successorUserId,patient);
            change.put("pending_successor_id",c.successorUserId);
            break;
            case "RESOLVE":if(!origin&&!owner&&!accepted)throw denied();
            change.put("workflow_state","RESOLVED");
            change.put("outcome_code",c.outcomeCode);
            change.put("outcome_reason",c.reason);
            change.put("resolved_at_utc",properties.now());
            break;
            case "CANCEL_MISTAKE":if(!origin&&!owner)throw denied();
            change.put("workflow_state","CANCELLED_MISTAKE");
            change.put("outcome_code","MISTAKE");
            change.put("outcome_reason",c.reason);
            change.put("resolved_at_utc",properties.now());
            break;
            default:throw WorkflowException.invalid("Unsupported emergency action.");
        }
        long v=id(a.get("lock_version"));
        if(v>=9007199254740991L)throw conflict("VERSION_OVERFLOW");
        change.put("lock_version",v+1);
        List<Object>args=new ArrayList<>(change.values());
        args.add(a.get("id"));
        List<String>fields=new ArrayList<>();
        for(String f:change.keySet())fields.add(f+"=?");
        repo.update("UPDATE emergency_event SET "+String.join(",",fields)+" WHERE id=?",args.toArray());
        long id=id(a.get("id"));
        a.clear();
        a.putAll(repo.emergency(id));
    }
    public static boolean terminal(Map<String,Object>a){
        return Arrays.asList("RESOLVED","CANCELLED_MISTAKE").contains(a.get("workflow_state"));
    }
    private static Long number(Object v){
        return v==null?null:id(v);
    }
}
