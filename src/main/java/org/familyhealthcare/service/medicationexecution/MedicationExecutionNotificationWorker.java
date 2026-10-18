package org.familyhealthcare.service.medicationexecution;
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
@Service
public class MedicationExecutionNotificationWorker {
    private static final Set<String> RETRYABLE=new HashSet<>(Arrays.asList("UNKNOWN","FAILED","NO_CHANNEL","SUPPRESSED"));
    private Instant queuedAfterAt;
    private long queuedAfterId,recoveryAfterId;
    private final JdbcTemplate jdbc;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionRepository repo;
    private final MedicationExecutionQueryService queries;
    private final NotificationDeliveryService delivery;
    public MedicationExecutionNotificationWorker(JdbcTemplate jdbc,MedicationExecutionAuthorizationService auth,MedicationExecutionProperties properties,MedicationExecutionRepository repo,MedicationExecutionQueryService queries,NotificationDeliveryService delivery) {
        this.jdbc=jdbc;
        this.auth=auth;
        this.properties=properties;
        this.repo=repo;
        this.queries=queries;
        this.delivery=delivery;
    }
    public synchronized void tick(Instant now) {
        Objects.requireNonNull(now);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Transport must run outside a business transaction.");
        if(!properties.isEnabled())return;
        List<Map<String,Object>> expiredRows=repo.rows("SELECT id FROM medication_execution_notification WHERE status='CLAIMED' AND claimed_at<=? AND id>? ORDER BY id LIMIT 100",now.minusSeconds(300),recoveryAfterId);
        for(Map<String,Object> candidate:expiredRows) {
            recoveryAfterId=id(candidate.get("id"));
            try {
                tx(()-> {
                    Map<String,Object> row=lockTarget(id(candidate.get("id")));
                    if(row!=null)NotificationAttemptLifecycle.recoverClaim(attempt(row),now,this::apply);
                    return null;
                });
            }
            catch(WorkflowException deferred) {
                if(deferred.getStatus()!=403&&deferred.getStatus()!=409)throw deferred;
            }
        }
        if(expiredRows.size()<100)recoveryAfterId=0;
        // 单次固定窗口，游标跨 tick 推进；无法入场的首行保留原真相，不能垄断后续合法通知。
        String after=queuedAfterAt==null?"":" AND (next_attempt_at>? OR (next_attempt_at=? AND id>?))";
        List<Object> args=new ArrayList<>();
        args.add(properties.now());
        if(queuedAfterAt!=null)args.addAll(Arrays.asList(queuedAfterAt,queuedAfterAt,queuedAfterId));
        List<Map<String,Object>> candidates=repo.rows("SELECT id,next_attempt_at FROM medication_execution_notification WHERE status IN ('QUEUED','FAILED') AND (status='QUEUED' OR last_result='FAILED_RETRYABLE') AND attempt_count<3 AND next_attempt_at IS NOT NULL AND next_attempt_at<=?"+after+" ORDER BY next_attempt_at,id LIMIT 100",args.toArray());
        for(Map<String,Object> candidate:candidates) {
            queuedAfterAt=instant(candidate.get("next_attempt_at"));
            queuedAfterId=id(candidate.get("id"));
            if(!properties.isEnabled())break;
            Map<String,Object> claimed,started;
            try {
                claimed=tx(()->claim(id(candidate.get("id"))));
                if(claimed==null)continue;
                started=tx(()->prepare(claimed));
            }
            catch(WorkflowException deferred) {
                if(deferred.getStatus()!=403&&deferred.getStatus()!=409)throw deferred;
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
                result=delivery.deliverMedicationExecutionAttempt(id(live.get("channel_id")),id(live.get("recipient_user_id")),(String)live.get("template_key"),"Medication review update","/medication?patientId="+live.get("patient_id")+"&executionId="+live.get("execution_id"));
            }
            catch(RuntimeException uncertain) {
                result=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            }
            if(result==null)result=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            complete(started,result.getOutcome().name(),result.isRetryable());
        }
        if(candidates.size()<100){queuedAfterAt=null;queuedAfterId=0;}
    }
    public Map<String,Object> deliveryStatus(long actor,long execution,String cursor,int limit) {
        Map<String,Object> detail=queries.detail(actor,execution);
        long patient=id(detail.get("patientId")),after=MedicationExecutionQueryService.cursor(cursor,actor,patient,"DELIVERY",execution,limit);
        boolean admin=admin(actor);
        List<Map<String,Object>> rows=notificationRows("SELECT * FROM medication_execution_notification WHERE execution_id=? AND patient_id=? AND recipient_user_id=? AND id>? ORDER BY id LIMIT ?",execution,patient,actor,after,limit+1);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:rows)out.add(projection(row,admin));
        auth.requireRead(actor,patient);
        return MedicationExecutionQueryService.page(out,limit,actor,patient,"DELIVERY",execution,"id");
    }
    public Map<String,Object> retry(long actor,long notification,String retryToken,boolean duplicateRiskAcknowledged) {
        auth.requireNotificationAdmin(actor);
        Map<String,Object> discovered=notification(notification);
        if(discovered==null||!Objects.equals(number(discovered.get("recipient_user_id")),actor))throw MedicationExecutionAuthorizationService.denied();
        queries.detail(actor,id(discovered.get("execution_id")));
        return tx(()-> {
            Map<String,Object> row=lockTarget(notification);
            auth.requireNotificationAdmin(actor);
            if(row==null||!Objects.equals(number(row.get("recipient_user_id")),actor)||blocked(row)!=null)throw MedicationExecutionAuthorizationService.denied();
            auth.requireRead(actor,id(row.get("patient_id")));
            if(!Objects.equals(token(row),retryToken)||!RETRYABLE.contains(row.get("status")))throw MedicationExecutionRepository.conflict("RETRY_CHANGED");
            if("UNKNOWN".equals(row.get("status"))&&!duplicateRiskAcknowledged)throw WorkflowException.invalid("Acknowledge that retrying an uncertain delivery may send a duplicate.");
            repo.update("UPDATE medication_execution_notification SET status='QUEUED',attempt_count=0,next_attempt_at=?,claim_token=NULL,claimed_at=NULL,request_id=?,last_result='MANUAL_RETRY',delivered_at=NULL,updated_at=? WHERE id=?",properties.now(),UUID.randomUUID()+":"+properties.now().toEpochMilli(),properties.now(),notification);
            return projection(notification(notification),true);
        });
    }
    private Map<String,Object> claim(long id) {
        Map<String,Object> row=lockTarget(id);
        if(row==null||!Arrays.asList("QUEUED","FAILED").contains(row.get("status"))||((Number)row.get("attempt_count")).intValue()>=3||row.get("next_attempt_at")==null||instant(row.get("next_attempt_at")).isAfter(properties.now())||"FAILED".equals(row.get("status"))&&!"FAILED_RETRYABLE".equals(row.get("last_result")))return null;
        String blocked=blocked(row);
        if(blocked!=null) {
            suppress(row,blocked);
            return null;
        }
        repo.update("UPDATE medication_execution_notification SET status='CLAIMED',claim_token=?,claimed_at=?,last_result='CLAIMED_UNATTEMPTED',updated_at=? WHERE id=?",UUID.randomUUID().toString(),properties.now(),properties.now(),id);
        return notification(id);
    }
    private Map<String,Object> prepare(Map<String,Object> claimed) {
        Map<String,Object> current=lockTarget(id(claimed.get("id")));
        if(current==null||!"CLAIMED".equals(current.get("status"))||!Objects.equals(current.get("claim_token"),claimed.get("claim_token")))return null;
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
        Map<String,Object> event=repo.one("SELECT * FROM medication_execution_event WHERE id=?",row.get("event_id")),execution=repo.execution(id(row.get("execution_id")));
        if(event==null||execution==null)throw MedicationExecutionRepository.conflict("NOTIFICATION_INVALID");
        if(event.get("actor_id")==null) {
            repo.lockSystemNotification(id(row.get("patient_id")),id(execution.get("medication_id")),id(event.get("id")),notification,number(row.get("recipient_user_id")));
            return notification(notification);
        }
        if(!"HUMAN".equals(event.get("actor_kind")))throw MedicationExecutionRepository.conflict("NOTIFICATION_INVALID");
        long patient=id(row.get("patient_id")),actor=id(event.get("actor_id"));
        HouseholdAuthorityLocks.LockedAuthority token=null;
        try {
            token=repo.lockNotification(actor,patient,id(execution.get("medication_id")),id(event.get("id")),notification,number(row.get("recipient_user_id")));
        }
        catch(WorkflowException denied) {
            if(denied.getStatus()!=403)throw denied;
        }
        repo.one("SELECT id FROM medication_execution_event WHERE id=? FOR UPDATE",event.get("id"));
        repo.one("SELECT id FROM medication_execution_notification WHERE id=? FOR UPDATE",notification);
        row=notification(notification);
        return row;
    }
    private String blocked(Map<String,Object> row) {
        if(!properties.isEnabled()||row.get("recipient_user_id")==null)return "SUPPRESSED";
        Map<String,Object> event=repo.one("SELECT * FROM medication_execution_event WHERE id=?",row.get("event_id")),e=repo.execution(id(row.get("execution_id")));
        if(event==null||e==null||id(event.get("patient_id"))!=id(row.get("patient_id"))||id(event.get("execution_id"))!=id(e.get("id"))||id(event.get("aggregate_version"))!=id(e.get("lock_version"))||!Objects.equals(event.get("revision_id"),row.get("revision_id"))||id(event.get("cycle"))!=id(row.get("cycle"))||!hash((String)event.get("evidence_json")).equals(event.get("evidence_hash"))||!Objects.equals("MEDICATION_EXECUTION_"+event.get("type"),row.get("template_key")))return "SUPPRESSED";
        boolean intakeEvent=event.get("intake_id")!=null;
        Map<String,Object> p=event.get("reconciliation_id")==null?null:repo.reconciliation(id(event.get("reconciliation_id")));
        // 随访周期属于具体 intake，不等于第几份处方提议；个人事实没有提议或临床修订。
        if(intakeEvent) {
            Map<String,Object> intake=repo.one("SELECT * FROM medication_intake WHERE id=?",event.get("intake_id"));
            if(intake==null||!Objects.equals(intake.get("execution_id"),e.get("id"))||!Objects.equals(intake.get("patient_id"),row.get("patient_id"))||!Objects.equals(intake.get("medication_id"),e.get("medication_id"))||id(intake.get("follow_up_cycle"))!=id(event.get("cycle"))||!Objects.equals(intake.get("reconciliation_id"),event.get("reconciliation_id")))return "SUPPRESSED";
            if("DUE".equals(event.get("type"))&&(!"OPEN".equals(intake.get("follow_up_state"))||!Objects.equals(MedicationBoundaryService.at(intake.get("follow_up_due_at")),MedicationBoundaryService.at(event.get("due_at")))))return "SUPPRESSED";
            if((p!=null&&!Objects.equals(p.get("scheduled_revision_id"),event.get("revision_id")))||(p==null&&event.get("revision_id")!=null))return "SUPPRESSED";
            if(event.get("action_id")!=null) {
                Map<String,Object> action=repo.one("SELECT patient_id,intake_id,execution_id,execution_event_id,actor_id FROM care_intake_action WHERE id=?",event.get("action_id"));
                if(action==null||!Objects.equals(intake.get("latest_action_id"),event.get("action_id"))||!Objects.equals(action.get("patient_id"),row.get("patient_id"))||!Objects.equals(action.get("intake_id"),intake.get("id"))||!Objects.equals(action.get("execution_id"),e.get("id"))||!Objects.equals(action.get("execution_event_id"),event.get("id"))||!Objects.equals(action.get("actor_id"),event.get("actor_id")))return "SUPPRESSED";
            }
        }
        else if(p==null||!Objects.equals(p.get("current_revision_id"),row.get("revision_id"))||id(p.get("proposal_no"))!=id(row.get("cycle")))return "SUPPRESSED";
        long patient=id(row.get("patient_id")),recipient=id(row.get("recipient_user_id"));
        try {
            if("SYSTEM".equals(event.get("actor_kind")))repo.requireSystemNotificationEvent(event,patient);
            else {
                if(!"HUMAN".equals(event.get("actor_kind"))||event.get("actor_id")==null)return "SUPPRESSED";
                auth.requireWrite(id(event.get("actor_id")),patient);
            }
            auth.requireRead(recipient,patient);
            if(event.get("revision_id")!=null) {
                Map<String,Object> v=repo.revision(id(event.get("revision_id")));
                repo.requireRevisionLinks(v,patient);
                if("PRESCRIPTION".equals(v.get("source_kind")))auth.requireSource(recipient,patient,id(v.get("prescription_id")),id(v.get("prescription_item_id")),((Number)v.get("source_version")).intValue());
            }
            Long selected=p==null?null:number(p.get("selected_verifier_id"));
            if(recipient!=auth.owner(patient)) {
                if(selected==null||recipient!=selected)return "SUPPRESSED";
                auth.requireSelectedDoctor(recipient,patient,selected);
            }
        }
        catch(WorkflowException denied) {
            if(denied.getStatus()!=403&&denied.getStatus()!=409)throw denied;
            return "SUPPRESSED";
        }
        if(row.get("channel_id")==null)return "NO_CHANNEL";
        Map<String,Object> channel=repo.one("SELECT user_id,enabled,channel_type FROM notification_channel WHERE id=?",row.get("channel_id"));
        if(channel==null)return "NO_CHANNEL";
        if(id(channel.get("user_id"))!=recipient)return "SUPPRESSED";
        return ((Number)channel.get("enabled")).intValue()==1&&Arrays.asList("WEBHOOK","WECHAT_WEBHOOK","DINGTALK_WEBHOOK").contains(channel.get("channel_type"))?null:"NO_CHANNEL";
    }
    private Map<String,Object> projection(Map<String,Object> row,boolean admin) {
        return map("id",row.get("id"),"status",row.get("status"),"attemptCount",row.get("attempt_count"),"lastResult",row.get("last_result"),"nextAttemptAt",iso(row.get("next_attempt_at")),"deliveredAt",iso(row.get("delivered_at")),"retryToken",token(row),"canRetry",admin&&RETRYABLE.contains(row.get("status"))&&blocked(row)==null,"requiresDuplicateRiskAcknowledgement","UNKNOWN".equals(row.get("status")));
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
        return hash(json(map("id",row.get("id"),"status",row.get("status"),"attempts",row.get("attempt_count"),"request",row.get("request_id"),"updated",iso(row.get("updated_at")))));
    }
    private Map<String,Object> notification(long id) {
        List<Map<String,Object>> rows=notificationRows("SELECT * FROM medication_execution_notification WHERE id=?",id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private List<Map<String,Object>> notificationRows(String sql,Object...args) {
        return jdbc.query(sql,ps->MedicationExecutionRepository.bind(ps,args),(rs,n)-> {
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int i=1; i<=md.getColumnCount(); i++) {
                String name=md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                Object value=rs.getObject(i);
                if(value instanceof Timestamp)value=rs.getTimestamp(i,utc()).toInstant();
                row.put(name,value);
            }
            return row;
        });
    }
    private Attempt attempt(Map<String,Object> row) {
        return new Attempt(id(row.get("id")),(String)row.get("status"),(String)row.get("claim_token"),instant(row.get("claimed_at")),(String)row.get("request_id"),((Number)row.get("attempt_count")).intValue(),(String)row.get("last_result"));
    }
    private void complete(Map<String,Object> row,String status,boolean retryable) {
        tx(()-> {
            NotificationAttemptLifecycle.complete(attempt(row),new Outcome(status,retryable),properties.now(),this::apply);
            return null;
        });
    }
    private boolean apply(Attempt expected,Change change) {
        mutationTransaction(jdbc);
        switch(change.getKind()) {
            case START:return repo.update("UPDATE medication_execution_notification SET attempt_count=?,request_id=?,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=? AND attempt_count=?",change.getAttemptCount(),change.getRequestId(),change.getLastResult(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken(),expected.getAttemptCount())==1;
            case RECOVER:return repo.update("UPDATE medication_execution_notification SET status=?,next_attempt_at=?,claim_token=NULL,claimed_at=NULL,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND (claim_token=? OR (claim_token IS NULL AND ? IS NULL)) AND claimed_at=? AND (last_result=? OR (last_result IS NULL AND ? IS NULL))",change.getStatus(),change.getNextAttemptAt(),change.getLastResult(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken(),expected.getClaimToken(),expected.getClaimedAt(),expected.getLastResult(),expected.getLastResult())==1;
            case COMPLETE:return repo.update("UPDATE medication_execution_notification SET status=?,next_attempt_at=?,claim_token=NULL,last_result=?,delivered_at=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=?",change.getStatus(),change.getNextAttemptAt(),change.getLastResult(),change.getDeliveredAt(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken())==1;
            default:throw new IllegalStateException("Unsupported attempt change");
        }
    }
    private void suppress(Map<String,Object> row,String status) {
        repo.update("UPDATE medication_execution_notification SET status=?,claim_token=NULL,next_attempt_at=NULL,last_result=?,updated_at=? WHERE id=?",status,status,properties.now(),row.get("id"));
    }
    private boolean liveAttempt(Map<String,Object> expected,Map<String,Object> live) {
        return live!=null&&"CLAIMED".equals(live.get("status"))&&"ATTEMPT_STARTED".equals(live.get("last_result"))&&Objects.equals(expected.get("claim_token"),live.get("claim_token"));
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
        return tx.execute(status->work.get());
    }
}
