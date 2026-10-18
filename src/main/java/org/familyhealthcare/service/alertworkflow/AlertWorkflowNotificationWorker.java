package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.workflow.*;
import org.familyhealthcare.service.workflow.NotificationClaim.SessionRestoreException;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryAttempt;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome;
import org.familyhealthcare.service.workflow.NotificationAttemptLifecycle.Attempt;
import org.familyhealthcare.service.workflow.NotificationAttemptLifecycle.Change;
import org.familyhealthcare.service.workflow.NotificationAttemptLifecycle.Outcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;
import java.sql.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import static org.familyhealthcare.service.workflow.WorkflowData.*;

/** Durable, bounded attempts; external delivery never changes acceptance, evidence or closure. */
@Service
@ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
public class AlertWorkflowNotificationWorker {
    private static final Set<String> EVENTS=new HashSet<>(Arrays.asList("CREATED","ASSIGN","ESCALATE","REJECT_ASSIGNMENT","SUBMIT","FEEDBACK","RETURN","CLOSE","CANCEL","REOPEN","SOURCE_CHANGED","DUE"));
    private static final Set<String> RESULTS=new HashSet<>(Arrays.asList("QUEUED","CLAIMED_UNATTEMPTED","ATTEMPT_STARTED","DELIVERED","FAILED","UNKNOWN","NO_CHANNEL","SUPPRESSED","MANUAL_RETRY","FAILED_RETRYABLE","FAILED_MANUAL_RETRY_REQUIRED"));
    private static final Set<String> RETRYABLE=new HashSet<>(Arrays.asList("FAILED","UNKNOWN","SUPPRESSED","NO_CHANNEL"));
    private static final long LEASE_SECONDS=300;
    private static final int DUE_BATCH_SIZE=100;
    // DATETIME(6) is UTC wall time; TIMESTAMPDIFF avoids session-dependent UNIX_TIMESTAMP conversion.
    private static final String CURRENT_DUE_KEY_SQL="CONCAT('due:',a.id,':',a.cycle_no,':',CAST((TIMESTAMPDIFF(MICROSECOND,'1970-01-01 00:00:00',a.due_at)-MICROSECOND(a.due_at))/1000000 AS SIGNED),'.',LPAD(MICROSECOND(a.due_at),6,'0'))";
    private final JdbcTemplate jdbc;
    private final AlertWorkflowAuthorizationService auth;
    private final AlertWorkflowProperties properties;
    private final AlertWorkflowRepository repo;
    private final AlertWorkflowNotificationQueue queue;
    private final AlertWorkflowNotificationTransport transport;
    public AlertWorkflowNotificationWorker(JdbcTemplate jdbc,AlertWorkflowAuthorizationService auth,AlertWorkflowProperties properties,AlertWorkflowRepository repo,AlertWorkflowNotificationQueue queue,AlertWorkflowNotificationTransport transport){this.jdbc=Objects.requireNonNull(jdbc);this.auth=Objects.requireNonNull(auth);this.properties=Objects.requireNonNull(properties);this.repo=Objects.requireNonNull(repo);this.queue=Objects.requireNonNull(queue);this.transport=Objects.requireNonNull(transport);}

    public void tick(Instant now) {
        if(!properties.isEnabled())return;
        Objects.requireNonNull(now,"now");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Notification delivery must run outside a caller transaction.");
        inTransaction(()->{recover(now);return null;});enqueueDue(now);
        for(int i=0;i<100&&properties.isEnabled();i++){
            Job claimed=claim(properties.now());if(claimed==null)break;
            Job attempted=inTransaction(()->prepare(claimed));if(attempted==null)continue;
            // Fresh reads after the durable attempt transaction, immediately before external transport.
            Context context=context(attempted.eventId);String blocked=blocked(attempted,context);
            if(blocked!=null){complete(attempted,blocked,properties.now(),false);continue;}
            List<Job> active=query("SELECT * FROM alert_notification WHERE id=? AND status='CLAIMED' AND claim_token=? AND last_result='ATTEMPT_STARTED'",this::job,attempted.id,attempted.token);
            if(active.isEmpty())continue;
            if(!active.get(0).claimedAt.plusSeconds(LEASE_SECONDS).isAfter(properties.now())){complete(attempted,"UNKNOWN",properties.now(),false);continue;}
            DeliveryAttempt result;
            try{result=transport.attempt(attempted.channelId,attempted.recipient,"ALERT_WORKFLOW_"+context.type,"Measurement follow-up update","/alert-workflows/"+context.alertId+"?patientId="+context.patientId);}
            catch(RuntimeException uncertain){result=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);}
            if(result==null)result=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            complete(attempted,result.getOutcome().name(),properties.now(),result.isRetryable());
        }
    }

    /** Current clinical/source authority and only the authenticated recipient's delivery rows. */
    public Map<String,Object> deliveryStatus(long actor,long alertId,String cursor,int limit){
        properties.requireEnabled();if(limit<1||limit>100)throw AlertWorkflowException.invalid("limit must be between 1 and 100.");
        Map<String,Object> alert=repo.readAlert(actor,alertId);long patient=id(alert.get("patientId"));repo.readSource(actor,patient,(String)alert.get("sourceType"),id(alert.get("sourceId")));
        long after=cursor(cursor,actor,alertId);boolean admin=isAdmin(actor);
        List<Map<String,Object>> rows=query("SELECT n.* FROM alert_notification n JOIN alert_event e ON e.id=n.event_id WHERE e.alert_id=? AND n.recipient_user_id=? AND n.id>? ORDER BY n.id LIMIT ?",(rs,i)->projection(job(rs,i),rs,admin),alertId,actor,after,limit+1);
        String next=null;if(rows.size()>limit){rows.remove(rows.size()-1);next=encodeCursor(actor,alertId,id(rows.get(rows.size()-1).get("id")));}
        repo.readAlert(actor,alertId);repo.readSource(actor,patient,(String)alert.get("sourceType"),id(alert.get("sourceId")));
        return map("alertId",alertId,"items",rows,"nextCursor",next,"canRetryNotifications",isAdmin(actor),"deliveryMeaning","PROVIDER_ACCEPTANCE_ONLY");
    }

    /** Token-conditioned reset: delayed identical requests cannot reset a later failed retry cycle. */
    public Map<String,Object> retry(long actor,long notificationId,String expectedToken,boolean duplicateRiskAcknowledged){
        properties.requireEnabled();if(notificationId<=0)throw AlertWorkflowException.invalid("Invalid notification ID.");
        if(expectedToken==null||!expectedToken.matches("[0-9a-f]{64}"))throw AlertWorkflowException.invalid("retryToken is required.");
        return inTransaction(()->{
            auth.requireAssignmentAdmin(actor);
            List<Job> current=query("SELECT * FROM alert_notification WHERE id=?",this::job,notificationId);
            if(current.isEmpty())throw AlertWorkflowException.denied();requireRetryTarget(current.get(0));
            List<Job> rows=query("SELECT * FROM alert_notification WHERE id=? FOR UPDATE",this::job,notificationId);auth.requireAssignmentAdmin(actor);if(rows.isEmpty())throw AlertWorkflowException.denied();Job row=rows.get(0);
            requireRetryTarget(row);
            if(!MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.US_ASCII),retryToken(row).getBytes(StandardCharsets.US_ASCII)))throw new AlertWorkflowException(409,"RETRY_CONFLICT","Delivery changed. Refresh its status before retrying.");
            if(!RETRYABLE.contains(row.status))throw new AlertWorkflowException(409,"RETRY_UNAVAILABLE","This delivery cannot be retried.");
            if("UNKNOWN".equals(row.status)&&!duplicateRiskAcknowledged)throw AlertWorkflowException.invalid("Acknowledge that an uncertain delivery may be duplicated.");

            Instant now=properties.now();
            update("INSERT INTO operation_audit_log(user_id,request_method,request_path,status_code,duration_ms,action_type,target_type,target_id,detail_json,created_at) VALUES(?,'POST','/alert-workflows/notifications/retry',200,0,'RETRY','ALERT_NOTIFICATION',?,?,?)",actor,Long.toString(row.id),json(map("notificationId",row.id,"previousStatus",row.status,"previousAttemptCount",row.attempts,"duplicateRiskAcknowledged",duplicateRiskAcknowledged)),java.time.LocalDateTime.now());
            // Fresh cycle UUID also distinguishes unattempted manual cycles; prepare anchors its time at actual first send.
            update("UPDATE alert_notification SET status='QUEUED',attempt_count=0,next_attempt_at=?,claimed_at=NULL,claim_token=NULL,request_id=?,last_result='MANUAL_RETRY',delivered_at=NULL,updated_at=? WHERE id=?",now,UUID.randomUUID()+":"+now.toEpochMilli(),now,row.id);
            auth.requireAssignmentAdmin(actor);if(blocked(row,context(row.eventId))!=null)throw AlertWorkflowException.denied();
            return query("SELECT * FROM alert_notification WHERE id=?",(rs,i)->projection(job(rs,i),rs,true),row.id).get(0);
        });
    }

    private void requireRetryTarget(Job row){if(row.channelId==null||blocked(row,context(row.eventId))!=null)throw AlertWorkflowException.denied();}

    /** One immutable system event for each current deadline identity, even with no authorized recipients. */
    private void enqueueDue(Instant now){
        List<Long> alerts=query("SELECT a.id FROM alert_record a WHERE a.workflow_version=1 AND a.due_at<=? AND a.workflow_state NOT IN ('CLOSED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM alert_event e WHERE e.alert_id=a.id AND e.cycle_no=a.cycle_no AND e.event_type='DUE' AND CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload_json,'$.dueKey')) AS BINARY)="+CURRENT_DUE_KEY_SQL+") ORDER BY a.id LIMIT ?",(rs,i)->rs.getLong(1),now,DUE_BATCH_SIZE);
        for(long alertId:alerts)inTransaction(()->{
            Map<String,Object> alert=repo.lockAlert(alertId);
            if(alert==null||alert.get("dueAt")==null||Arrays.asList("CLOSED","CANCELLED").contains(alert.get("workflowState"))||Instant.parse((String)alert.get("dueAt")).isAfter(now))return null;
            String key=dueKey(alertId,((Number)alert.get("cycleNo")).intValue(),Instant.parse((String)alert.get("dueAt")));
            List<String> existing=query("SELECT payload_json FROM alert_event WHERE alert_id=? AND cycle_no=? AND event_type='DUE'",(rs,i)->rs.getString(1),alertId,alert.get("cycleNo"));
            for(String payload:existing)if(key.equals(object(payload).get("dueKey")))return null;
            long event=repo.appendDueEvent(alertId,now,key);queue.enqueue(event);return null;
        });
    }
    private Job claim(Instant now) {
        try {return inTransaction(()->{
            NotificationClaim lock=NotificationClaim.acquire(jdbc);
            try {List<Job> rows=query("SELECT * FROM alert_notification WHERE status IN ('QUEUED','FAILED') AND (status='QUEUED' OR last_result='FAILED_RETRYABLE') AND attempt_count<3 AND next_attempt_at IS NOT NULL AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 1 "+lock.getClause(),this::job,now);
            if(rows.isEmpty())return null;Job row=rows.get(0);String blocked=blocked(row,context(row.eventId));
            if(blocked!=null){update("UPDATE alert_notification SET status=?,next_attempt_at=NULL,last_result=?,updated_at=? WHERE id=?",blocked,blocked,now,row.id);return row;}
            String token=UUID.randomUUID().toString();update("UPDATE alert_notification SET status='CLAIMED',claimed_at=?,claim_token=?,last_result='CLAIMED_UNATTEMPTED',updated_at=? WHERE id=?",now,token,now,row.id);row.token=token;row.status="CLAIMED";return row;
            }finally{lock.restore();}
        });}catch(CannotAcquireLockException busy){return null;}
        catch(TransactionSystemException cleanupFailure) {
            // Aborting a dirty session can make Spring's subsequent rollback fail; retain the actual cause.
            if(cleanupFailure.getApplicationException() instanceof SessionRestoreException) {
                SessionRestoreException original=(SessionRestoreException)cleanupFailure.getApplicationException();
                if(cleanupFailure.getCause()!=null)original.addSuppressed(cleanupFailure.getCause());
                throw original;
            }
            throw cleanupFailure;
        }
    }
    private Job prepare(Job row) {
        if(!"CLAIMED".equals(row.status)||row.token==null)return null;
        List<Job> rows=query("SELECT * FROM alert_notification WHERE id=? AND status='CLAIMED' AND claim_token=? FOR UPDATE",this::job,row.id,row.token);
        if(rows.isEmpty())return null;Job current=rows.get(0);String blocked=blocked(current,context(current.eventId));
        Instant now=properties.now(); // Capture this job's attempt start after its current-context checks.
        // A paused claimant must not consume an attempt after the persisted lease expires.
        if(current.claimedAt==null||!current.claimedAt.plusSeconds(LEASE_SECONDS).isAfter(now)){
            String result="ATTEMPT_STARTED".equals(current.lastResult)?"UNKNOWN":"QUEUED";
            update("UPDATE alert_notification SET status=?,next_attempt_at=?,claim_token=NULL,claimed_at=NULL,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=?",result,"QUEUED".equals(result)?now:null,result,now,current.id,current.token);return null;
        }
        if(blocked!=null){update("UPDATE alert_notification SET status=?,next_attempt_at=NULL,claim_token=NULL,last_result=?,updated_at=? WHERE id=?",blocked,blocked,now,current.id);return null;}
        String request=current.attempts==0?(current.requestId==null?UUID.randomUUID().toString():current.requestId.split(":")[0])+":"+now.toEpochMilli():current.requestId;
        Attempt started=NotificationAttemptLifecycle.markStarted(attempt(current),request,now,this::applyAttemptChange);
        if(started==null)return null;
        current.attempts=started.getAttemptCount();current.requestId=started.getRequestId();current.lastResult=started.getLastResult();return current;
    }
    private void complete(Job row,String result,Instant now,boolean retryable) {
        inTransaction(()->{NotificationAttemptLifecycle.complete(attempt(row),new Outcome(result,retryable),now,this::applyAttemptChange);return null;});
    }
    private void recover(Instant now) {
        List<Attempt> expired=query("SELECT id,status,claim_token,claimed_at,request_id,attempt_count,last_result FROM alert_notification WHERE status='CLAIMED' AND claimed_at<=? FOR UPDATE",this::attempt,now.minusSeconds(LEASE_SECONDS));
        for(Attempt current:expired)NotificationAttemptLifecycle.recoverClaim(current,now,this::applyAttemptChange);
    }

    /** Fixed-table persistence and field masks; recovery compares the selected expired claim snapshot. */
    private boolean applyAttemptChange(Attempt expected,Change change) {
        mutationTransaction(jdbc);
        switch(change.getKind()) {
            case START:
                return update("UPDATE alert_notification SET attempt_count=?,request_id=?,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=? AND attempt_count=?",
                        change.getAttemptCount(),change.getRequestId(),change.getLastResult(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken(),expected.getAttemptCount())==1;
            case RECOVER:
                return update("UPDATE alert_notification SET status=?,next_attempt_at=?,claim_token=NULL,claimed_at=NULL,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND (claim_token=? OR (claim_token IS NULL AND ? IS NULL)) AND claimed_at=? AND (last_result=? OR (last_result IS NULL AND ? IS NULL))",
                        change.getStatus(),change.getNextAttemptAt(),change.getLastResult(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken(),expected.getClaimToken(),expected.getClaimedAt(),expected.getLastResult(),expected.getLastResult())==1;
            case COMPLETE:
                return update("UPDATE alert_notification SET status=?,next_attempt_at=?,claim_token=NULL,last_result=?,delivered_at=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=?",
                        change.getStatus(),change.getNextAttemptAt(),change.getLastResult(),change.getDeliveredAt(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken())==1;
            default: throw new IllegalStateException("Unsupported notification transition.");
        }
    }
    private Attempt attempt(Job row) {return new Attempt(row.id,row.status,row.token,row.claimedAt,row.requestId,row.attempts,row.lastResult);}
    private Attempt attempt(ResultSet rs,int index)throws SQLException {
        String claimed=time(rs,"claimed_at");
        return new Attempt(rs.getLong("id"),rs.getString("status"),rs.getString("claim_token"),claimed==null?null:Instant.parse(claimed),rs.getString("request_id"),rs.getInt("attempt_count"),rs.getString("last_result"));
    }

    private String blocked(Job row,Context context){
        if(!properties.isEnabled()||context==null||context.patientId!=row.patientId||!EVENTS.contains(context.type)||context.cycle!=context.currentCycle||context.sourceRevision!=context.currentSourceRevision)return "SUPPRESSED";
        if("DUE".equals(context.type)){
            if(context.actor!=null||!"SYSTEM".equals(context.actorRole)||!"SCHEDULER".equals(context.actorRelation)||context.due==null||context.due.isAfter(properties.now())||Arrays.asList("CLOSED","CANCELLED").contains(context.state)||!dueKey(context.alertId,context.cycle,context.due).equals(context.dueKey)||!row.dispatchKey.startsWith(context.dueKey+":"))return "SUPPRESSED";
        }else if(context.actor==null||!authorized(context.actor,context,false))return "SUPPRESSED";
        if(!recipients(context).contains(row.recipient))return "SUPPRESSED";
        if(row.channelId==null)return "NO_CHANNEL";
        List<Map<String,Object>> channels=query("SELECT user_id,enabled,channel_type FROM notification_channel WHERE id=?",(rs,i)->map("owner",rs.getLong(1),"enabled",rs.getInt(2),"type",rs.getString(3)),row.channelId);
        if(channels.isEmpty())return "NO_CHANNEL";Map<String,Object> channel=channels.get(0);if(id(channel.get("owner"))!=row.recipient)return "SUPPRESSED";
        return ((Number)channel.get("enabled")).intValue()==1&&Arrays.asList("WEBHOOK","WECHAT_WEBHOOK","DINGTALK_WEBHOOK").contains(channel.get("type"))?null:"NO_CHANNEL";
    }
    private Set<Long> recipients(Context c){
        Set<Long> recipients=new TreeSet<>();if(c==null)return recipients;
        List<Long> owners=query("SELECT user_id FROM patient WHERE id=?",(rs,i)->rs.getLong(1),c.patientId);if(owners.isEmpty())return recipients;
        recipients.add(owners.get(0));if(c.assignee!=null)recipients.add(c.assignee);
        if("CLINICAL".equals(c.mode))for(long doctor:query("SELECT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND status='ACTIVE'",(rs,i)->rs.getLong(1),c.patientId))try{auth.requireClinical(doctor,c.patientId);recipients.add(doctor);}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;}
        recipients.removeIf(actor->!authorized(actor,c,Objects.equals(c.assignee,actor)));return recipients;
    }
    private boolean authorized(long actor,Context c,boolean assignee){
        try{auth.requireRead(actor,c.patientId);if(assignee){auth.requireRecord(actor,c.patientId);if("CLINICAL".equals(c.mode)){try{auth.requireClinical(actor,c.patientId);}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;auth.requireNursing(actor,c.patientId);}}}return auth.canReadEvidence(actor,c.patientId,c.sourceType,c.sourceId);}
        catch(AlertWorkflowException denied){if(denied.getStatus()!=403&&denied.getStatus()!=503)throw denied;return false;}
    }
    private Context context(long eventId){
        List<Context> rows=query("SELECT e.id,e.patient_id,e.alert_id,e.event_type,e.cycle_no,e.actor_id,e.actor_role,e.actor_relation,e.payload_json,a.cycle_no AS current_cycle,a.responsible_user_id,a.workflow_mode,a.workflow_state,a.source_type,a.source_id,a.source_revision,a.due_at,s.current_revision FROM alert_event e JOIN alert_record a ON a.id=e.alert_id AND a.patient_id=e.patient_id AND a.workflow_version=1 JOIN alert_source_identity s ON s.source_type=a.source_type AND s.source_id=a.source_id AND s.patient_id=a.patient_id WHERE e.id=?",(rs,i)->{
            Context c=new Context();c.eventId=rs.getLong("id");c.patientId=rs.getLong("patient_id");c.alertId=rs.getLong("alert_id");c.type=rs.getString("event_type");c.cycle=rs.getInt("cycle_no");c.currentCycle=rs.getInt("current_cycle");c.actor=nullableId(rs,"actor_id");c.actorRole=rs.getString("actor_role");c.actorRelation=rs.getString("actor_relation");c.assignee=nullableId(rs,"responsible_user_id");c.mode=rs.getString("workflow_mode");c.state=rs.getString("workflow_state");c.sourceType=rs.getString("source_type");c.sourceId=rs.getLong("source_id");c.sourceRevision=rs.getLong("source_revision");c.currentSourceRevision=rs.getLong("current_revision");String due=time(rs,"due_at");c.due=due==null?null:Instant.parse(due);c.dueKey=(String)object(rs.getString("payload_json")).get("dueKey");return c;
        },eventId);return rows.isEmpty()?null:rows.get(0);
    }
    static String dueKey(long alert,int cycle,Instant due){return "due:"+alert+":"+cycle+":"+due.getEpochSecond()+"."+String.format(Locale.ROOT,"%06d",due.getNano()/1000);}
    static String safeResult(String result){return RESULTS.contains(result)?result:null;}
    private Job job(ResultSet rs,int i)throws SQLException{Job j=new Job();j.id=rs.getLong("id");j.eventId=rs.getLong("event_id");j.patientId=rs.getLong("patient_id");j.recipient=rs.getLong("recipient_user_id");j.channelId=nullableId(rs,"channel_id");j.dispatchKey=rs.getString("dispatch_key");j.status=rs.getString("status");j.attempts=rs.getInt("attempt_count");j.token=rs.getString("claim_token");j.requestId=rs.getString("request_id");j.lastResult=rs.getString("last_result");j.updatedAt=Instant.parse(time(rs,"updated_at"));String claimed=time(rs,"claimed_at");j.claimedAt=claimed==null?null:Instant.parse(claimed);return j;}
    private Map<String,Object> projection(Job j,ResultSet rs,boolean admin)throws SQLException{return map("id",j.id,"status",j.status,"attemptCount",j.attempts,"nextAttemptAt",time(rs,"next_attempt_at"),"lastResult",safeResult(j.lastResult),"deliveredAt",time(rs,"delivered_at"),"createdAt",time(rs,"created_at"),"updatedAt",j.updatedAt.toString(),"retryToken",retryToken(j),"canRetry",admin&&RETRYABLE.contains(j.status)&&j.channelId!=null&&blocked(j,context(j.eventId))==null,"requiresDuplicateRiskAcknowledgement","UNKNOWN".equals(j.status));}
    private boolean isAdmin(long actor){try{auth.requireAssignmentAdmin(actor);return true;}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;return false;}}
    private String retryToken(Job j){try{String content=j.id+"|"+j.status+"|"+j.attempts+"|"+j.requestId+"|"+j.updatedAt;byte[] digest=MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte value:digest)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private long cursor(String value,long actor,long alert){if(value==null)return 0;if(value.length()>512)throw AlertWorkflowException.invalid("Invalid delivery cursor.");try{String[] parts=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8).split(":");if(parts.length!=5||!"DELIVERIES".equals(parts[0])||!"1".equals(parts[1])||!Long.toString(actor).equals(parts[2])||!Long.toString(alert).equals(parts[3])||!parts[4].matches("[1-9][0-9]*"))throw new IllegalArgumentException();return Long.parseLong(parts[4]);}catch(RuntimeException invalid){throw AlertWorkflowException.invalid("Invalid delivery cursor.");}}
    private String encodeCursor(long actor,long alert,long after){return Base64.getUrlEncoder().withoutPadding().encodeToString(("DELIVERIES:1:"+actor+":"+alert+":"+after).getBytes(StandardCharsets.UTF_8));}
    private <T>T inTransaction(Supplier<T> work){TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx.execute(s->{mutationTransaction(jdbc);return work.get();});}
    private <T>List<T> query(String sql,RowMapper<T> mapper,Object...args){return jdbc.query(sql,ps->bind(ps,args),mapper);}
    private int update(String sql,Object...args){return jdbc.update(sql,ps->bind(ps,args));}
    private void bind(PreparedStatement ps,Object[] args)throws SQLException{for(int i=0;i<args.length;i++)if(args[i] instanceof Instant)time(ps,i+1,(Instant)args[i]);else ps.setObject(i+1,args[i]);}
    private static final class Context{long eventId,patientId,alertId,sourceId,sourceRevision,currentSourceRevision;int cycle,currentCycle;Long actor,assignee;String type,mode,state,sourceType,actorRole,actorRelation,dueKey;Instant due;}
    private static final class Job{long id,eventId,patientId,recipient;Long channelId;int attempts;String dispatchKey,status,token,requestId,lastResult;Instant updatedAt,claimedAt;}
}
