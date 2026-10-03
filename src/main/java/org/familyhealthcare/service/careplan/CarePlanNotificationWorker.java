package org.familyhealthcare.service.careplan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.familyhealthcare.service.careplan.CarePlanData.*;
import static org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome;
import static org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryAttempt;

/** Same-transaction durable outbox, short nonblocking claims and conservative uncertain-result recovery. */
@Service
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
public class CarePlanNotificationWorker implements CarePlanNotificationQueue {
    private static final Set<String> EVENTS=new HashSet<>(Arrays.asList("PLAN_PUBLISHED","REVISION_PUBLISHED","PLAN_CANCELLED","PLAN_CLOSED","RECEIPT_SUBMITTED","HELP_REQUESTED","FOLLOW_UP_RECORDED","RECEIPT_CONFIRMED","RECEIPT_RETURNED"));
    private static final Set<String> RESULTS=new HashSet<>(Arrays.asList("QUEUED","CLAIMED_UNATTEMPTED","ATTEMPT_STARTED","DELIVERED","FAILED","UNKNOWN","NO_CHANNEL","SUPPRESSED","MANUAL_RETRY","FAILED_RETRYABLE","FAILED_MANUAL_RETRY_REQUIRED"));
    private static final long LEASE_SECONDS=300;
    private final JdbcTemplate jdbc;
    private final CarePlanAuthorizationService auth;
    private final CarePlanProperties properties;
    private final CarePlanNotificationTransport transport;

    public CarePlanNotificationWorker(JdbcTemplate jdbc,CarePlanAuthorizationService auth,CarePlanProperties properties,CarePlanNotificationTransport transport) {
        this.jdbc=Objects.requireNonNull(jdbc);this.auth=Objects.requireNonNull(auth);this.properties=Objects.requireNonNull(properties);this.transport=Objects.requireNonNull(transport);
    }

    @Override public void enqueue(long eventId) {
        if(!properties.isEnabled())return;
        mutationTransaction(jdbc);
        Context context=context(eventId);
        if(context==null||!EVENTS.contains(context.type))throw CarePlanException.invalid("Unsupported care-plan notification event.");
        for(long recipient:recipients(context))enqueueRecipient(context,recipient,null,properties.now());
    }

    /** No caller transaction may survive into a network operation. */
    public void tick(Instant now) {
        if(!properties.isEnabled())return;
        Objects.requireNonNull(now,"now");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Notification delivery must run outside a caller transaction.");
        inTransaction(()->{recover(now);return null;});
        enqueueDue(now);
        for(int i=0;i<100;i++) {
            Job claimed=claim(properties.now());if(claimed==null)break;
            Job attempted=inTransaction(()->prepare(claimed));if(attempted==null)continue;
            // Fresh auto-commit authority reads immediately before transport, after the claim transaction ends.
            Context context=context(attempted.eventId);
            String blocked=blocked(attempted,context);
            if(blocked!=null){complete(attempted,blocked,properties.now(),false);continue;}
            DeliveryAttempt outcome;
            try {outcome=transport.attempt(attempted.channelId,eventKey(attempted,context),"Care plan update","/care-plans/"+context.planId);}
            catch(RuntimeException uncertain){outcome=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);}
            if(outcome==null)outcome=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            complete(attempted,outcome.getOutcome().name(),properties.now(),outcome.isRetryable());
        }
    }

    public Map<String,Object> listDeliveryStatus(long actorId,String cursor,int limit) {
        properties.requireEnabled();limit(limit);auth.requireAssignmentAdmin(actorId);
        long after=cursor(cursor,actorId);
        List<Map<String,Object>> rows=query("SELECT id,status,attempt_count,next_attempt_at,last_result,delivered_at,created_at,updated_at FROM care_plan_notification WHERE id>? ORDER BY id LIMIT ?",this::projection,after,limit+1);
        String next=null;if(rows.size()>limit){rows.remove(rows.size()-1);next=encodeCursor(actorId,id(rows.get(rows.size()-1).get("id")));}
        auth.requireAssignmentAdmin(actorId);
        return map("items",rows,"nextCursor",next);
    }

    /** Explicit new retry cycle, never an automatic budget reset. Audit and queue change are atomic. */
    public Map<String,Object> retry(long actorId,long notificationId,boolean duplicateRiskAcknowledged) {
        properties.requireEnabled();if(notificationId<=0)throw CarePlanException.invalid("Invalid notification ID.");
        return inTransaction(()->{
            auth.requireAssignmentAdmin(actorId);
            List<Job> rows=query("SELECT * FROM care_plan_notification WHERE id=? FOR UPDATE",this::job,notificationId);
            if(rows.isEmpty())throw CarePlanException.denied();Job row=rows.get(0);
            if(!Arrays.asList("FAILED","UNKNOWN","SUPPRESSED","NO_CHANNEL").contains(row.status))throw new CarePlanException(409,"RETRY_UNAVAILABLE","This delivery cannot be retried.");
            if("UNKNOWN".equals(row.status)&&!duplicateRiskAcknowledged)throw CarePlanException.invalid("Acknowledge that an uncertain delivery may be duplicated.");
            Context context=context(row.eventId);
            if(row.channelId==null||blocked(row,context)!=null)throw CarePlanException.denied();
            Map<String,Object> audit=map("notificationId",row.id,"previousStatus",row.status,"previousAttemptCount",row.attempts,"previousRequestId",row.requestId,"duplicateRiskAcknowledged",duplicateRiskAcknowledged);
            update("INSERT INTO operation_audit_log(user_id,request_method,request_path,status_code,duration_ms,action_type,target_type,target_id,detail_json,created_at) VALUES(?,'POST','/care-plans/notifications/retry',200,0,'RETRY','CARE_PLAN_NOTIFICATION',?,?,?)",actorId,Long.toString(row.id),json(audit),java.time.LocalDateTime.now());
            Instant now=properties.now();update("UPDATE care_plan_notification SET status='QUEUED',attempt_count=0,next_attempt_at=?,claimed_at=NULL,claim_token=NULL,request_id=NULL,last_result='MANUAL_RETRY',delivered_at=NULL,updated_at=? WHERE id=?",now,now,row.id);
            auth.requireAssignmentAdmin(actorId);
            if(blocked(row,context(row.eventId))!=null)throw CarePlanException.denied();
            return query("SELECT id,status,attempt_count,next_attempt_at,last_result,delivered_at,created_at,updated_at FROM care_plan_notification WHERE id=?",this::projection,row.id).get(0);
        });
    }

    private void enqueueDue(Instant now) {
        List<Long> actions=query("SELECT a.id FROM care_plan_action a JOIN doctor_care_plan p ON p.id=a.plan_id AND p.patient_id=a.patient_id WHERE p.workflow_version=1 AND p.lifecycle='ACTIVE' AND p.current_revision_id=a.revision_id AND a.status IN ('OPEN','NEEDS_HELP') AND a.due_at<=? AND NOT EXISTS(SELECT 1 FROM care_plan_notification n WHERE n.dispatch_key LIKE CONCAT('due:',a.id,':%')) ORDER BY a.id",(rs,i)->rs.getLong(1),now);
        for(long actionId:actions)inTransaction(()->{
            Action action=action(actionId);if(action==null)return null;
            List<Long> publications=query("SELECT id FROM care_plan_event WHERE plan_id=? AND patient_id=? AND revision_id=? AND action_id IS NULL AND event_type IN ('PLAN_PUBLISHED','REVISION_PUBLISHED') ORDER BY id LIMIT 1",(rs,i)->rs.getLong(1),action.planId,action.patientId,action.revisionId);
            if(publications.isEmpty())return null;Context context=context(publications.get(0));
            String due=dueKey(action);
            if(!validDue(context,action,due,now))return null;
            context.dueAction=action;
            for(long recipient:recipients(context))enqueueRecipient(context,recipient,due,now);
            return null;
        });
    }

    private void enqueueRecipient(Context context,long recipient,String due,Instant now) {
        if(!recipients(context).contains(recipient))return;
        List<Long> channels=query("SELECT id FROM notification_channel WHERE user_id=? AND enabled=1 AND channel_type IN ('WEBHOOK','WECHAT_WEBHOOK','DINGTALK_WEBHOOK') ORDER BY id",(rs,i)->rs.getLong(1),recipient);
        if(channels.isEmpty())insert(context,recipient,null,due,"NO_CHANNEL",now);
        else for(long channel:channels) {
            if(!recipients(context).contains(recipient))continue;
            if(channelState(channel,recipient)==null)insert(context,recipient,channel,due,"QUEUED",now);
        }
    }
    private void insert(Context context,long recipient,Long channel,String due,String status,Instant now) {
        String key=(due==null?"event:"+context.eventId:due)+":"+recipient+":"+(channel==null?"NO_CHANNEL":channel);
        try {update("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,channel_id,dispatch_key,status,attempt_count,next_attempt_at,last_result,created_at,updated_at) VALUES(?,?,?,?,?,?,0,?,?,?,?)",context.eventId,context.patientId,recipient,channel,key,status,"QUEUED".equals(status)?now:null,status,now,now);}
        catch(DuplicateKeyException duplicate){/* The unique dispatch key is the cross-instance deduplication boundary. */}
    }

    private Job claim(Instant now) {
        try {return inTransaction(()->{
            ClaimLock lock=claimLock();
            try {List<Job> rows=query("SELECT * FROM care_plan_notification WHERE status IN ('QUEUED','FAILED') AND (status='QUEUED' OR last_result='FAILED_RETRYABLE') AND attempt_count<3 AND next_attempt_at IS NOT NULL AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 1 "+lock.clause,this::job,now);
            if(rows.isEmpty())return null;Job row=rows.get(0);String blocked=blocked(row,context(row.eventId));
            if(blocked!=null){update("UPDATE care_plan_notification SET status=?,next_attempt_at=NULL,last_result=?,updated_at=? WHERE id=?",blocked,blocked,now,row.id);return row;}
            String token=UUID.randomUUID().toString();update("UPDATE care_plan_notification SET status='CLAIMED',claimed_at=?,claim_token=?,last_result='CLAIMED_UNATTEMPTED',updated_at=? WHERE id=?",now,token,now,row.id);row.token=token;row.status="CLAIMED";return row;
            }finally{lock.restore();}
        });}catch(CannotAcquireLockException busy){return null;}
    }
    private Job prepare(Job row) {
        if(!"CLAIMED".equals(row.status)||row.token==null)return null;
        List<Job> rows=query("SELECT * FROM care_plan_notification WHERE id=? AND status='CLAIMED' AND claim_token=? FOR UPDATE",this::job,row.id,row.token);
        if(rows.isEmpty())return null;Job current=rows.get(0);String blocked=blocked(current,context(current.eventId));
        Instant now=properties.now(); // Capture this job's attempt start after its current-context checks.
        if(blocked!=null){update("UPDATE care_plan_notification SET status=?,next_attempt_at=NULL,claim_token=NULL,last_result=?,updated_at=? WHERE id=?",blocked,blocked,now,current.id);return null;}
        String request=current.requestId==null?UUID.randomUUID()+":"+now.toEpochMilli():current.requestId;
        firstAttempt(request); // Corrupt cycle metadata fails closed before any network call.
        update("UPDATE care_plan_notification SET attempt_count=attempt_count+1,request_id=?,last_result='ATTEMPT_STARTED',updated_at=? WHERE id=?",request,now,current.id);
        current.attempts++;current.requestId=request;return current;
    }
    private void complete(Job row,String result,Instant now,boolean retryable) {
        inTransaction(()->{
            Instant next="FAILED".equals(result)&&retryable&&row.attempts<3?firstAttempt(row.requestId).plusSeconds(row.attempts==1?60:300):null;
            String code="FAILED".equals(result)?retryable?"FAILED_RETRYABLE":"FAILED_MANUAL_RETRY_REQUIRED":result;
            update("UPDATE care_plan_notification SET status=?,next_attempt_at=?,claim_token=NULL,last_result=?,delivered_at=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=?",result,next,code,"DELIVERED".equals(result)?now:null,now,row.id,row.token);return null;
        });
    }
    private void recover(Instant now) {
        update("UPDATE care_plan_notification SET status=CASE WHEN last_result='ATTEMPT_STARTED' THEN 'UNKNOWN' ELSE 'QUEUED' END,next_attempt_at=CASE WHEN last_result='ATTEMPT_STARTED' THEN NULL ELSE ? END,last_result=CASE WHEN last_result='ATTEMPT_STARTED' THEN 'UNKNOWN' ELSE 'QUEUED' END,claim_token=NULL,claimed_at=NULL,updated_at=? WHERE status='CLAIMED' AND claimed_at<=?",now,now,now.minusSeconds(LEASE_SECONDS));
    }

    private String blocked(Job row,Context context) {
        if(context==null||context.patientId!=row.patientId||!EVENTS.contains(context.type))return "SUPPRESSED";
        if(row.dispatchKey.startsWith("due:")) {
            Action action=dueAction(row.dispatchKey);
            if(!validDue(context,action,duePrefix(row.dispatchKey),properties.now()))return "SUPPRESSED";
            context.dueAction=action;
        }
        if(!recipients(context).contains(row.recipient))return "SUPPRESSED";
        if(row.channelId==null)return "NO_CHANNEL";
        return channelState(row.channelId,row.recipient);
    }
    private String channelState(long channel,long recipient) {
        List<Map<String,Object>> rows=query("SELECT user_id,enabled,channel_type FROM notification_channel WHERE id=?",(rs,i)->map("owner",rs.getLong(1),"enabled",rs.getInt(2),"type",rs.getString(3)),channel);
        if(rows.isEmpty())return "NO_CHANNEL";Map<String,Object> value=rows.get(0);
        if(id(value.get("owner"))!=recipient)return "SUPPRESSED";
        return ((Number)value.get("enabled")).intValue()==1&&Arrays.asList("WEBHOOK","WECHAT_WEBHOOK","DINGTALK_WEBHOOK").contains(value.get("type"))?null:"NO_CHANNEL";
    }
    private Set<Long> recipients(Context context) {
        Set<Long> candidates=new TreeSet<>();
        if(context==null)return candidates;
        Long owner=jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?",Long.class,context.patientId);if(owner!=null)candidates.add(owner);
        Action target=context.dueAction!=null?context.dueAction:context.actionId==null?null:action(context.actionId);
        if(target!=null) {
            if(target.patientId!=context.patientId||target.planId!=context.planId||target.revisionId!=context.revisionId||target.revisionId!=context.currentRevision)return Collections.emptySet();
            candidates.add(target.assignee);
        } else if(context.actionId!=null)return Collections.emptySet();
        else if(context.revisionId==context.currentRevision)candidates.addAll(query("SELECT assigned_user_id FROM care_plan_action WHERE plan_id=? AND patient_id=? AND revision_id=?",(rs,i)->rs.getLong(1),context.planId,context.patientId,context.revisionId));
        boolean clinical=context.dueAction==null&&Arrays.asList("RECEIPT_SUBMITTED","HELP_REQUESTED","FOLLOW_UP_RECORDED").contains(context.type);
        Set<Long> doctors=new HashSet<>(),nurses=new HashSet<>();
        if(clinical){doctors.addAll(query("SELECT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND status='ACTIVE'",(rs,i)->rs.getLong(1),context.patientId));candidates.addAll(doctors);}
        if(context.dueAction==null&&"HELP_REQUESTED".equals(context.type)){nurses.addAll(query("SELECT nurse_user_id FROM care_nurse_assignment WHERE patient_id=? AND status='ACTIVE' AND revoked_at IS NULL",(rs,i)->rs.getLong(1),context.patientId));candidates.addAll(nurses);}
        Set<Long> authorized=new TreeSet<>();
        for(long candidate:candidates)try {
            if(doctors.contains(candidate))auth.requireClinical(candidate,context.patientId);
            else if(nurses.contains(candidate))auth.requireNursing(candidate,context.patientId);
            else if(owner!=null&&owner==candidate)auth.requireRead(candidate,context.patientId);
            else auth.requireRecord(candidate,context.patientId);
            authorized.add(candidate);
        }catch(CarePlanException denied){if(denied.getStatus()!=403)throw denied;}
        return authorized;
    }

    private Context context(long eventId) {
        List<Context> rows=query("SELECT e.id,e.patient_id,e.plan_id,e.revision_id,e.action_id,e.event_type,p.current_revision_id,p.lifecycle FROM care_plan_event e JOIN doctor_care_plan p ON p.id=e.plan_id AND p.patient_id=e.patient_id AND p.workflow_version=1 JOIN care_plan_revision r ON r.id=e.revision_id AND r.plan_id=p.id AND r.status='PUBLISHED' WHERE e.id=?",(rs,i)->{
            Context c=new Context();c.eventId=rs.getLong("id");c.patientId=rs.getLong("patient_id");c.planId=rs.getLong("plan_id");c.revisionId=rs.getLong("revision_id");c.actionId=nullableId(rs,"action_id");c.type=rs.getString("event_type");c.currentRevision=rs.getLong("current_revision_id");c.lifecycle=rs.getString("lifecycle");return c;
        },eventId);return rows.isEmpty()?null:rows.get(0);
    }
    private Action action(long actionId) {
        List<Action> rows=query("SELECT id,plan_id,patient_id,revision_id,assigned_user_id,due_at,status FROM care_plan_action WHERE id=?",(rs,i)->{
            Action a=new Action();a.id=rs.getLong("id");a.planId=rs.getLong("plan_id");a.patientId=rs.getLong("patient_id");a.revisionId=rs.getLong("revision_id");a.assignee=rs.getLong("assigned_user_id");a.due=Instant.parse(time(rs,"due_at"));a.status=rs.getString("status");return a;
        },actionId);return rows.isEmpty()?null:rows.get(0);
    }
    private boolean validDue(Context context,Action action,String prefix,Instant now) {
        return context!=null&&action!=null&&context.actionId==null&&Arrays.asList("PLAN_PUBLISHED","REVISION_PUBLISHED").contains(context.type)&&context.planId==action.planId&&context.patientId==action.patientId&&context.revisionId==action.revisionId&&context.currentRevision==action.revisionId&&"ACTIVE".equals(context.lifecycle)&&Arrays.asList("OPEN","NEEDS_HELP").contains(action.status)&&!action.due.isAfter(now)&&dueKey(action).equals(prefix);
    }
    private Action dueAction(String key){try{String[] parts=key.split(":");if(parts.length!=5)return null;long id=Long.parseLong(parts[1]);return id>0?action(id):null;}catch(NumberFormatException invalid){return null;}}
    private String duePrefix(String key){String[] parts=key.split(":");return parts.length==5?parts[0]+":"+parts[1]+":"+parts[2]:"";}
    private String dueKey(Action action){return "due:"+action.id+":"+action.due.getEpochSecond()+"."+String.format(Locale.ROOT,"%06d",action.due.getNano()/1000);}
    private String eventKey(Job job,Context context){return job.dispatchKey.startsWith("due:")?"CARE_PLAN_ACTION_DUE":"CARE_PLAN_"+context.type;}
    private Instant firstAttempt(String request){try{String[] parts=request.split(":");if(parts.length!=2||!UUID.fromString(parts[0]).toString().equals(parts[0])||!Long.toString(Long.parseLong(parts[1])).equals(parts[1]))throw new IllegalArgumentException();return Instant.ofEpochMilli(Long.parseLong(parts[1]));}catch(RuntimeException invalid){throw new IllegalStateException("Invalid notification cycle identity.");}}
    private Job job(ResultSet rs,int i)throws SQLException {
        Job j=new Job();j.id=rs.getLong("id");j.eventId=rs.getLong("event_id");j.patientId=rs.getLong("patient_id");j.recipient=rs.getLong("recipient_user_id");j.channelId=nullableId(rs,"channel_id");j.dispatchKey=rs.getString("dispatch_key");j.status=rs.getString("status");j.attempts=rs.getInt("attempt_count");j.token=rs.getString("claim_token");j.requestId=rs.getString("request_id");return j;
    }
    private Map<String,Object> projection(ResultSet rs,int i)throws SQLException {
        String result=rs.getString("last_result");return map("id",rs.getLong("id"),"status",rs.getString("status"),"attemptCount",rs.getInt("attempt_count"),"nextAttemptAt",time(rs,"next_attempt_at"),"lastResult",RESULTS.contains(result)?result:null,"deliveredAt",time(rs,"delivered_at"),"createdAt",time(rs,"created_at"),"updatedAt",time(rs,"updated_at"));
    }
    private <T>T inTransaction(Supplier<T> work){TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return transaction.execute(s->{mutationTransaction(jdbc);return work.get();});}
    /** H2 is a test-scoped dependency. Its NOWAIT syntax alone still waits, so bound only that test session. */
    private ClaimLock claimLock(){return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<ClaimLock>)connection->{
        String database=connection.getMetaData().getDatabaseProductName();
        if("MySQL".equals(database))return new ClaimLock("FOR UPDATE SKIP LOCKED",null);
        if("H2".equals(database)) {
            int previous;try(Statement statement=connection.createStatement();ResultSet value=statement.executeQuery("CALL LOCK_TIMEOUT()")){value.next();previous=value.getInt(1);}
            try(Statement statement=connection.createStatement()){statement.execute("SET LOCK_TIMEOUT 1");}
            return new ClaimLock("FOR UPDATE NOWAIT",previous);
        }
        throw new IllegalStateException("Unsupported care-plan notification database.");
    });}
    private final class ClaimLock {
        final String clause;final Integer previous;
        ClaimLock(String clause,Integer previous){this.clause=clause;this.previous=previous;}
        void restore(){if(previous!=null)jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{try(Statement statement=connection.createStatement()){statement.execute("SET LOCK_TIMEOUT "+previous);}return null;});}
    }
    private <T>List<T>query(String sql,RowMapper<T> mapper,Object... args){return jdbc.query(sql,ps->bind(ps,args),mapper);}
    private int update(String sql,Object... args){return jdbc.update(sql,ps->bind(ps,args));}
    private void bind(PreparedStatement ps,Object[] args)throws SQLException{for(int i=0;i<args.length;i++)if(args[i] instanceof Instant)time(ps,i+1,(Instant)args[i]);else ps.setObject(i+1,args[i]);}
    private long cursor(String value,long actor){if(value==null)return 0;try{String decoded=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);String[] p=decoded.split(":");if(p.length!=4||!"NOTIFICATIONS".equals(p[0])||!"1".equals(p[1])||!Long.toString(actor).equals(p[2]))throw new IllegalArgumentException();long id=Long.parseLong(p[3]);if(id<=0||!Long.toString(id).equals(p[3]))throw new IllegalArgumentException();return id;}catch(RuntimeException invalid){throw CarePlanException.invalid("Invalid notification cursor.");}}
    private String encodeCursor(long actor,long id){return Base64.getUrlEncoder().withoutPadding().encodeToString(("NOTIFICATIONS:1:"+actor+":"+id).getBytes(StandardCharsets.UTF_8));}
    private static final class Context {long eventId,patientId,planId,revisionId,currentRevision;Long actionId;String type,lifecycle;Action dueAction;}
    private static final class Action {long id,planId,patientId,revisionId,assignee;Instant due;String status;}
    private static final class Job {long id,eventId,patientId,recipient;Long channelId;int attempts;String dispatchKey,status,token,requestId;}
}
