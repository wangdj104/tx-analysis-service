package org.familyhealthcare.service.emergencyassistance;
import org.familyhealthcare.service.NotificationDeliveryService;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.*;
import org.familyhealthcare.service.workflow.*;
import org.familyhealthcare.service.workflow.NotificationAttemptLifecycle.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** Exact live lease gates, current source/channel ownership, and truthful delivery outcomes. Never clinical completion. */
@Service public class EmergencyAssistanceNotificationWorker {
    // 候选筛选不是授权。等锁后仍用原人类账号合同，单条失权不能阻塞后面的当前合资格人类请求。
    private static final String SENDER_CURRENT = "(e.actor_kind='HUMAN' AND e.actor_id IS NOT NULL AND u.status=1 AND COALESCE(u.deleted,0)=0 AND EXISTS (SELECT 1 FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=u.id AND r.status=1 AND COALESCE(r.deleted,0)=0))";
    private static final String SENDER_JOIN = " JOIN emergency_assistance_event e ON e.id=n.event_id JOIN sys_user u ON u.id=e.actor_id ";
    private static final Set<String> RETRYABLE=new HashSet<>(Arrays.asList("UNKNOWN","FAILED","NO_CHANNEL",
    "SUPPRESSED"));
    private final JdbcTemplate jdbc;
    private final EmergencyAssistanceAuthorizationService auth;
    private final EmergencyAssistanceProperties properties;
    private final EmergencyAssistanceRepository repo;
    private final EmergencyAssistanceQueryService queries;
    private final NotificationDeliveryService delivery;
    public EmergencyAssistanceNotificationWorker(JdbcTemplate jdbc,EmergencyAssistanceAuthorizationService auth,
    EmergencyAssistanceProperties properties,EmergencyAssistanceRepository repo,EmergencyAssistanceQueryService queries,
    NotificationDeliveryService delivery) {
        this.jdbc=jdbc;
        this.auth=auth;
        this.properties=properties;
        this.repo=repo;
        this.queries=queries;
        this.delivery=delivery;
    }
    public void tick(Instant now) {
        Objects.requireNonNull(now);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Transport must run outside a business transaction.");
        if(!properties.isEnabled())return;
        List<Long> expiredClaims = candidateIds("n.status='CLAIMED' AND n.claimed_at<=?", now.minusSeconds(300),
        "n.id");
        for (long id : expiredClaims) {
            try {
                tx(() -> {
                    Map<String,Object> row = lockTarget(id);
                    if (row != null) NotificationAttemptLifecycle.recoverClaim(attempt(row),now,this::apply);
                    return null;
                }
                );
            }
            catch (WorkflowException denied) {
                if (denied.getStatus() != 403) throw denied;
                // 保留真实 pending，当前权限恢复后再按原 lease 恢复，不伪造投递结果。
            }
        }
        List<Long> candidates = candidateIds("n.status IN ('QUEUED','FAILED') AND (n.status='QUEUED' OR n.last_result='FAILED_RETRYABLE') AND n.attempt_count<3 AND n.next_attempt_at IS NOT NULL AND n.next_attempt_at<=?",
        properties.now(), "n.next_attempt_at,n.id");
        for (long candidate : candidates) {
            if (!properties.isEnabled()) break;
            Map<String,Object> claimed;
            try {
                claimed = tx(() -> claim(candidate));
            }
            catch (WorkflowException denied) {
                if (denied.getStatus() != 403) throw denied;
                continue;
            }
            if(claimed==null)continue;
            Map<String,Object> started;
            try {
                started = tx(() -> prepare(claimed));
            }
            catch (WorkflowException denied) {
                if (denied.getStatus() != 403) throw denied;
                continue;
            }
            if(started==null)continue;
            Map<String,Object> live=notification(id(started.get("id")));
            if(!liveAttempt(started,live))continue;
            if(expired(live,properties.now())) {
                complete(started,"UNKNOWN",false);
                continue;
            }
            String block=blocked(live);
            if(block!=null) {
                complete(started,block,false);
                continue;
            }
            // Last admission uses fresh rows after ATTEMPT_STARTED committed; no transaction/locks are held across transport.
            live=notification(id(started.get("id")));
            if(!liveAttempt(started,live))continue;
            if(expired(live,properties.now())) {
                complete(started,"UNKNOWN",false);
                continue;
            }
            DeliveryAttempt result;
            try {
                result=delivery.deliverEmergencyAssistanceAttempt(id(live.get("channel_id")),id(live.get("recipient_user_id")),
                (String)live.get("template_key"),"Emergency request update","/care-journey?tab=emergency&patientId="+live.get("patient_id")+"&emergencyEventId="+live.get("emergency_event_id"));
            }
            catch(RuntimeException uncertain) {
                result=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            }
            if(result==null)result=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            complete(started,result.getOutcome().name(),result.isRetryable());
        }
    }
    public Map<String,Object> deliveryStatus(long actor,long target,String cursor,int limit) {
        Map<String,Object> detail=queries.detail(actor,target);
        long patient=id(detail.get("patientId")),after=EmergencyAssistanceQueryService.cursor(cursor,
        actor,patient,"DELIVERY",target,limit);
        boolean admin=admin(actor);
        List<Map<String,Object>> rows=notificationRows("SELECT n.*,CASE WHEN "+SENDER_CURRENT+" THEN 1 ELSE 0 END AS sender_current FROM emergency_assistance_notification n"+SENDER_JOIN+" WHERE n.emergency_event_id=? AND n.patient_id=? AND n.recipient_user_id=? AND n.id>? ORDER BY n.id LIMIT ?",
        target,patient,actor,after,limit+1);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:rows)out.add(projection(row,admin));
        auth.requireRead(actor,patient);
        return EmergencyAssistanceQueryService.page(out,limit,actor,patient,"DELIVERY",target,"id");
    }
    public Map<String,Object> retry(long actor,long notification,String retryToken,boolean duplicateRiskAcknowledged) {
        auth.requireNotificationAdmin(actor);
        Map<String,Object> discovered=notification(notification);
        if(discovered==null||!Objects.equals(number(discovered.get("recipient_user_id")),actor))throw EmergencyAssistanceAuthorizationService.denied();
        queries.detail(actor,id(discovered.get("emergency_event_id")));
        return tx(()-> {
            Map<String,Object> row=lockTarget(notification);
            auth.requireNotificationAdmin(actor);
            if(row==null||!Objects.equals(number(row.get("recipient_user_id")),actor)||blocked(row)!=null)throw EmergencyAssistanceAuthorizationService.denied();
            auth.requireRead(actor,id(row.get("patient_id")));
            if(!Objects.equals(token(row),retryToken)||!RETRYABLE.contains(row.get("status")))throw EmergencyAssistanceRepository.conflict("RETRY_CHANGED");
            if("UNKNOWN".equals(row.get("status"))&&!duplicateRiskAcknowledged)throw WorkflowException.invalid("Acknowledge that retrying an uncertain delivery may send a duplicate.");
            repo.update("UPDATE emergency_assistance_notification SET status='QUEUED',attempt_count=0,next_attempt_at=?,claim_token=NULL,claimed_at=NULL,request_id=?,last_result='MANUAL_RETRY',delivered_at=NULL,updated_at=? WHERE id=?",
            properties.now(),UUID.randomUUID()+":"+properties.now().toEpochMilli(),properties.now(),notification);
            return projection(notification(notification),true);
        }
        );
    }
    private Map<String,Object> claim(long id) {
        Map<String,Object> row=lockTarget(id);
        if(row==null||!Arrays.asList("QUEUED","FAILED").contains(row.get("status"))||((Number)row.get("attempt_count")).intValue()>=3||row.get("next_attempt_at")==null||instant(row.get("next_attempt_at")).isAfter(properties.now())||"FAILED".equals(row.get("status"))&&!"FAILED_RETRYABLE".equals(row.get("last_result")))return null;
        String blocked=blocked(row);
        if(blocked!=null) {
            suppress(row,blocked);
            return null;
        }
        repo.update("UPDATE emergency_assistance_notification SET status='CLAIMED',claim_token=?,claimed_at=?,last_result='CLAIMED_UNATTEMPTED',updated_at=? WHERE id=?",
        UUID.randomUUID().toString(),properties.now(),properties.now(),id);
        return notification(id);
    }
    private Map<String,Object> prepare(Map<String,Object> claimed) {
        Map<String,Object> current=lockTarget(id(claimed.get("id")));
        if(current==null||!"CLAIMED".equals(current.get("status"))||!Objects.equals(current.get("claim_token"),
        claimed.get("claim_token")))return null;
        if(expired(current,properties.now())) {
            NotificationAttemptLifecycle.recoverClaim(attempt(current),properties.now(),this::apply);
            return null;
        }
        String blocked=blocked(current);
        if(blocked!=null) {
            suppress(current,blocked);
            return null;
        }
        String request=(String)current.get("request_id");
        if(((Number)current.get("attempt_count")).intValue()==0)request=(request==null?UUID.randomUUID().toString():request.split(":")[0])+":"+properties.now().toEpochMilli();
        if(NotificationAttemptLifecycle.markStarted(attempt(current),request,properties.now(),this::apply)==null)return null;
        return notification(id(current.get("id")));
    }
    private Map<String,Object> lockTarget(long notification) {
        Map<String,Object> row=notification(notification);
        if(row==null)return null;
        Map<String,Object> a=repo.emergency(id(row.get("emergency_event_id"))),e=repo.one("SELECT * FROM emergency_assistance_event WHERE id=?",
        row.get("event_id"));
        if(a==null||e==null)throw EmergencyAssistanceRepository.conflict("NOTIFICATION_INVALID");
        long patient=id(a.get("patient_id"));
        if(e.get("actor_id")==null||!"HUMAN".equals(e.get("actor_kind")))throw EmergencyAssistanceAuthorizationService.denied();
        long actor=id(e.get("actor_id"));
        repo.admit(actor,patient,id(a.get("id")),number(row.get("recipient_user_id")),null,id(e.get("id")),
        notification,false);
        return notification(notification);
    }
    private String blocked(Map<String,Object> row) {
        if(!properties.isEnabled()||row.get("recipient_user_id")==null)return "SUPPRESSED";
        Map<String,Object> e=repo.one("SELECT * FROM emergency_assistance_event WHERE id=?",row.get("event_id")),
        a=repo.emergency(id(row.get("emergency_event_id")));
        if(e==null||a==null||!"HUMAN".equals(e.get("actor_kind"))||e.get("actor_id")==null||id(e.get("patient_id"))!=id(row.get("patient_id"))||id(e.get("emergency_event_id"))!=id(a.get("id"))||id(e.get("aggregate_version"))!=id(a.get("lock_version"))||id(e.get("cycle"))!=1||id(row.get("cycle"))!=1||!hash((String)e.get("evidence_json")).equals(e.get("evidence_hash"))||!Objects.equals("EMERGENCY_ASSISTANCE_"+e.get("type"),
        row.get("template_key")))return "SUPPRESSED";
        long patient=id(a.get("patient_id")),recipient=id(row.get("recipient_user_id"));
        try {
            repo.requireIdentity(a,patient);
            auth.requireWrite(id(e.get("actor_id")),patient);
            auth.requireRead(recipient,patient);
            if(!repo.invited(id(a.get("id")),recipient))return "SUPPRESSED";
        }
        catch(WorkflowException denied){
            if(denied.getStatus()!=403&&denied.getStatus()!=409)throw denied;
            return "SUPPRESSED";
        }
        if(row.get("channel_id")==null)return "NO_CHANNEL";
        Map<String,Object> channel=repo.one("SELECT user_id,enabled,channel_type FROM notification_channel WHERE id=?",
        row.get("channel_id"));
        if(channel==null)return "NO_CHANNEL";
        if(id(channel.get("user_id"))!=recipient)return "SUPPRESSED";
        return ((Number)channel.get("enabled")).intValue()==1&&Arrays.asList("WEBHOOK","WECHAT_WEBHOOK",
        "DINGTALK_WEBHOOK").contains(channel.get("channel_type"))?null:"NO_CHANNEL";
    }
    private Map<String,Object> projection(Map<String,Object> row,boolean admin) {
        return map("blockedReason",pendingAccess(row)?"WAITING_FOR_CURRENT_ACCESS":null,"id",row.get("id"),
        "status",row.get("status"),"attemptCount",row.get("attempt_count"),"lastResult",row.get("last_result"),
        "nextAttemptAt",iso(row.get("next_attempt_at")),"deliveredAt",iso(row.get("delivered_at")),"retryToken",
        token(row),"canRetry",admin&&RETRYABLE.contains(row.get("status"))&&blocked(row)==null,"requiresDuplicateRiskAcknowledgement",
        "UNKNOWN".equals(row.get("status")));
    }
    private boolean pendingAccess(Map<String,Object> row) {
        return Arrays.asList("QUEUED","FAILED","CLAIMED").contains(row.get("status")) && row.get("sender_current") instanceof Number && ((Number)row.get("sender_current")).intValue() == 0;
    }
    private List<Long> candidateIds(String predicate, Instant at, String order) {
        return jdbc.query("SELECT n.id FROM emergency_assistance_notification n"+SENDER_JOIN+ " WHERE "+predicate+" AND "+SENDER_CURRENT+" ORDER BY "+order+" LIMIT 100",
        ps -> {
            org.springframework.jdbc.datasource.DataSourceUtils.applyTimeout(ps,jdbc.getDataSource(),
            5);
            time(ps,1,at);
        }
        , (rs,n) -> rs.getLong(1));
    }
    private boolean admin(long actor) {
        try {
            auth.requireNotificationAdmin(actor);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    private String token(Map<String,Object> row) {
        return hash(json(map("id",row.get("id"),"status",row.get("status"),"attempts",row.get("attempt_count"),
        "request",row.get("request_id"),"updated",iso(row.get("updated_at")))));
    }
    private Map<String,Object> notification(long id) {
        List<Map<String,Object>> rows=notificationRows("SELECT * FROM emergency_assistance_notification WHERE id=?",
        id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private List<Map<String,Object>> notificationRows(String sql,Object...args) {
        return jdbc.query(sql,ps-> {
            org.springframework.jdbc.datasource.DataSourceUtils.applyTimeout(ps,jdbc.getDataSource(),
            5);
            EmergencyAssistanceRepository.bind(ps,args);
        }
        ,(rs,n)-> {
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int i=1; i<=md.getColumnCount(); i++) {
                String name=md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                Object value=rs.getObject(i);
                if(value instanceof Timestamp)value=rs.getTimestamp(i,utc()).toInstant();
                row.put(name,value);
            }
            return row;
        }
        );
    }
    private Attempt attempt(Map<String,Object> row) {
        return new Attempt(id(row.get("id")),(String)row.get("status"),(String)row.get("claim_token"),
        instant(row.get("claimed_at")),(String)row.get("request_id"),((Number)row.get("attempt_count")).intValue(),
        (String)row.get("last_result"));
    }
    private void complete(Map<String,Object> row,String status,boolean retryable) {
        tx(()-> {
            NotificationAttemptLifecycle.complete(attempt(row),new Outcome(status,retryable),properties.now(),
            this::apply);
            return null;
        }
        );
    }
    private boolean apply(Attempt expected,Change change) {
        mutationTransaction(jdbc);
        switch(change.getKind()) {
            case START:return repo.update("UPDATE emergency_assistance_notification SET attempt_count=?,request_id=?,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=? AND attempt_count=?",
            change.getAttemptCount(),change.getRequestId(),change.getLastResult(),change.getUpdatedAt(),
            expected.getId(),
            expected.getClaimToken(),expected.getAttemptCount())==1;
            case RECOVER:return repo.update("UPDATE emergency_assistance_notification SET status=?,next_attempt_at=?,claim_token=NULL,claimed_at=NULL,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND (claim_token=? OR (claim_token IS NULL AND ? IS NULL)) AND claimed_at=? AND (last_result=? OR (last_result IS NULL AND ? IS NULL))",
            change.getStatus(),change.getNextAttemptAt(),change.getLastResult(),change.getUpdatedAt(),
            expected.getId(),
            expected.getClaimToken(),expected.getClaimToken(),expected.getClaimedAt(),expected.getLastResult(),
            expected.getLastResult())==1;
            case COMPLETE:return repo.update("UPDATE emergency_assistance_notification SET status=?,next_attempt_at=?,claim_token=NULL,last_result=?,delivered_at=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=?",
            change.getStatus(),change.getNextAttemptAt(),change.getLastResult(),change.getDeliveredAt(),
            change.getUpdatedAt(),
            expected.getId(),expected.getClaimToken())==1;
            default:throw new IllegalStateException("Unsupported attempt change");
        }
    }
    private void suppress(Map<String,Object> row,String status) {
        repo.update("UPDATE emergency_assistance_notification SET status=?,claim_token=NULL,next_attempt_at=NULL,last_result=?,updated_at=? WHERE id=?",
        status,status,properties.now(),row.get("id"));
    }
    private boolean liveAttempt(Map<String,Object> expected,Map<String,Object> live) {
        return live!=null&&"CLAIMED".equals(live.get("status"))&&"ATTEMPT_STARTED".equals(live.get("last_result"))&&Objects.equals(expected.get("claim_token"),
        live.get("claim_token"));
    }
    private boolean expired(Map<String,Object> row,Instant now) {
        Instant claimed=instant(row.get("claimed_at"));
        return claimed==null||!claimed.plusSeconds(300).isAfter(now);
    }
    private static Long number(Object value) {
        return value==null?null:id(value);
    }
    private static Instant instant(Object value) {
        return value==null?null:value instanceof Instant?(Instant)value:((Timestamp)value).toInstant();
    }
    private static String iso(Object value) {
        return value==null?null:instant(value).toString();
    }
    private <T>T tx(Supplier<T> work) {
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
        return tx.execute(status->work.get());
    }
}
