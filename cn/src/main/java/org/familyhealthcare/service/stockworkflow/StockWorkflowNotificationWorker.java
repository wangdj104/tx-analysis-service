package org.familyhealthcare.service.stockworkflow;
import org.familyhealthcare.service.NotificationDeliveryService;
import org.familyhealthcare.service.archivestock.ArchiveStockNotificationGate;
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
@Service public class StockWorkflowNotificationWorker  {
    private static final Set<String> RETRYABLE=new HashSet<>(Arrays.asList("UNKNOWN","FAILED","NO_CHANNEL","SUPPRESSED"));
    private final JdbcTemplate jdbc;
    private final StockWorkflowAuthorizationService auth;
    private final StockWorkflowProperties properties;
    private final StockWorkflowRepository repo;
    private final StockWorkflowQueryService queries;
    private final NotificationDeliveryService delivery;
    private final ArchiveStockNotificationGate policy;
    private static final class Claim {
        final Map<String,Object> row;final ArchiveStockNotificationGate.Token policyToken;
        Claim(Map<String,Object> row,ArchiveStockNotificationGate.Token token){this.row=row;this.policyToken=Objects.requireNonNull(token);}
    }
    private static final class Admission {
        final String blocked;final ArchiveStockNotificationGate.Token token;
        Admission(String blocked,ArchiveStockNotificationGate.Token token){this.blocked=blocked;this.token=token;}
    }
    private static Admission denied(String reason){return new Admission(reason,null);}
    public StockWorkflowNotificationWorker(JdbcTemplate jdbc,StockWorkflowAuthorizationService auth,StockWorkflowProperties properties,StockWorkflowRepository repo,StockWorkflowQueryService queries,NotificationDeliveryService delivery)  {
        this.jdbc=jdbc;
        this.auth=auth;
        this.properties=properties;
        this.repo=repo;
        this.queries=queries;
        this.delivery=delivery;
        this.policy=new ArchiveStockNotificationGate(jdbc);
    }
    public void tick(Instant now)  {
        Objects.requireNonNull(now);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Transport must run outside a business transaction.");
        if(!properties.isEnabled())return;
        for(long id:jdbc.query("SELECT id FROM stock_notification WHERE status='CLAIMED' AND claimed_at<=? ORDER BY id LIMIT 100",ps->time(ps,1,now.minusSeconds(300)),(rs,n)->rs.getLong(1)))tx(()->  {
            Map<String,Object> row=lockTarget(id);
            if(row!=null)NotificationAttemptLifecycle.recoverClaim(attempt(row),now,this::apply);
            return null;
        }
        );
        for(int i=0; i<100&&properties.isEnabled(); i++)  {
            List<Long> candidates=jdbc.query("SELECT id FROM stock_notification WHERE status IN ('QUEUED','FAILED') AND (status='QUEUED' OR last_result='FAILED_RETRYABLE') AND attempt_count<3 AND next_attempt_at IS NOT NULL AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 1",ps->time(ps,1,properties.now()),(rs,n)->rs.getLong(1));
            if(candidates.isEmpty())break;
            Claim claimed=tx(()->claim(candidates.get(0)));
            if(claimed==null)continue;
            Claim started=tx(()->prepare(claimed));
            if(started==null)continue;
            // This transaction reacquires the entire old graph, then the policy lock last.
            // It compares the original claim token; transport begins only after all locks release.
            Map<String,Object> live=tx(()->finalAdmission(started));
            if(live==null)continue;
            DeliveryAttempt result;
            try  {
                result=delivery.deliverStockWorkflowAttempt(id(live.get("channel_id")),id(live.get("recipient_user_id")),(String)live.get("template_key"),"Stock review update","/care?patientId="+live.get("patient_id")+"&stockId="+live.get("stock_id"));
            }
            catch(RuntimeException uncertain)  {
                result=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            }
            if(result==null)result=new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            complete(started.row,result.getOutcome().name(),result.isRetryable());
        }
    }
    public Map<String,Object> deliveryStatus(long actor,long stock,String cursor,int limit)  {
        Map<String,Object> detail=queries.detail(actor,stock);
        long patient=id(detail.get("patientId")),after=StockWorkflowQueryService.cursor(cursor,actor,patient,"DELIVERY",stock,limit);
        boolean admin=admin(actor);
        List<Map<String,Object>> rows=notificationRows("SELECT * FROM stock_notification WHERE stock_id=? AND patient_id=? AND recipient_user_id=? AND id>? ORDER BY id LIMIT ?",stock,patient,actor,after,limit+1);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:rows)out.add(projection(row,admin));
        auth.requireRead(actor,patient);
        return StockWorkflowQueryService.page(out,limit,actor,patient,"DELIVERY",stock,"id");
    }
    public Map<String,Object> retry(long actor,long notification,String retryToken,boolean duplicateRiskAcknowledged)  {
        auth.requireNotificationAdmin(actor);
        Map<String,Object> discovered=notification(notification);
        if(discovered==null||!Objects.equals(number(discovered.get("recipient_user_id")),actor))throw StockWorkflowAuthorizationService.denied();
        queries.detail(actor,id(discovered.get("stock_id")));
        return tx(()->  {
            Map<String,Object> row=lockTarget(notification);
            auth.requireNotificationAdmin(actor);
            if(row==null||!Objects.equals(number(row.get("recipient_user_id")),actor)||admission(row,null,true).blocked!=null)throw StockWorkflowAuthorizationService.denied();
            auth.requireRead(actor,id(row.get("patient_id")));
            if(!Objects.equals(token(row),retryToken)||!RETRYABLE.contains(row.get("status")))throw StockWorkflowRepository.conflict("RETRY_CHANGED");
            if("UNKNOWN".equals(row.get("status"))&&!duplicateRiskAcknowledged)throw WorkflowException.invalid("Acknowledge that retrying an uncertain delivery may send a duplicate.");
            repo.update("UPDATE stock_notification SET status='QUEUED',attempt_count=0,next_attempt_at=?,claim_token=NULL,claimed_at=NULL,request_id=?,last_result='MANUAL_RETRY',delivered_at=NULL,updated_at=? WHERE id=?",properties.now(),UUID.randomUUID()+":"+properties.now().toEpochMilli(),properties.now(),notification);
            return projection(notification(notification),true);
        }
        );
    }
    private Claim claim(long id)  {
        Map<String,Object> row=lockTarget(id);
        if(row==null||!Arrays.asList("QUEUED","FAILED").contains(row.get("status"))||((Number)row.get("attempt_count")).intValue()>=3||row.get("next_attempt_at")==null||instant(row.get("next_attempt_at")).isAfter(properties.now())||"FAILED".equals(row.get("status"))&&!"FAILED_RETRYABLE".equals(row.get("last_result")))return null;
        Admission check=admission(row,null,true);
        if(check.blocked!=null)  {
            suppress(row,check.blocked);
            return null;
        }
        repo.update("UPDATE stock_notification SET status='CLAIMED',claim_token=?,claimed_at=?,last_result='CLAIMED_UNATTEMPTED',updated_at=? WHERE id=?",UUID.randomUUID().toString(),properties.now(),properties.now(),id);
        return new Claim(notification(id),check.token);
    }
    private Claim prepare(Claim claimed)  {
        Map<String,Object> current=lockTarget(id(claimed.row.get("id")));
        if(current==null||!"CLAIMED".equals(current.get("status"))||!Objects.equals(current.get("claim_token"),claimed.row.get("claim_token")))return null;
        if(expired(current,properties.now()))  {
            NotificationAttemptLifecycle.recoverClaim(attempt(current),properties.now(),this::apply);
            return null;
        }
        Admission check=admission(current,claimed.policyToken,true);
        if(check.blocked!=null)  {
            suppress(current,check.blocked);
            return null;
        }
        String request=(String)current.get("request_id");
        if(((Number)current.get("attempt_count")).intValue()==0)request=(request==null?UUID.randomUUID().toString():request.split(":")[0])+":"+properties.now().toEpochMilli();
        if(NotificationAttemptLifecycle.markStarted(attempt(current),request,properties.now(),this::apply)==null)return null;
        return new Claim(notification(id(current.get("id"))),claimed.policyToken);
    }
    private Map<String,Object> finalAdmission(Claim started){
        Map<String,Object> current=lockTarget(id(started.row.get("id")));
        if(!liveAttempt(started.row,current))return null;
        if(expired(current,properties.now())){NotificationAttemptLifecycle.recoverClaim(attempt(current),properties.now(),this::apply);return null;}
        Admission check=admission(current,started.policyToken,true);
        if(check.blocked!=null){suppress(current,check.blocked);return null;}
        return current;
    }
    private Map<String,Object> lockTarget(long notification)  {
        Map<String,Object> row=notification(notification);
        if(row==null)return null;
        Map<String,Object> event=repo.one("SELECT * FROM stock_event WHERE id=?",row.get("event_id")),stock=repo.stock(id(row.get("stock_id")));
        if(event==null||stock==null||event.get("actor_id")==null)throw StockWorkflowRepository.conflict("NOTIFICATION_INVALID");
        long patient=id(row.get("patient_id")),actor=id(event.get("actor_id"));
        boolean sourceDenied=false;
        try  {
            repo.lockNotification(actor,patient,id(stock.get("id")),id(event.get("id")),notification,number(row.get("recipient_user_id")));
        }
        catch(WorkflowException denied)  {
            if(denied.getStatus()!=403)throw denied;
            sourceDenied=true;
        }
        repo.one("SELECT id FROM stock_event WHERE id=? FOR UPDATE",event.get("id"));
        repo.one("SELECT id FROM stock_notification WHERE id=? FOR UPDATE",notification);
        Map<String,Object> current=notification(notification);
        if(current!=null&&sourceDenied)current.put("_sourceDenied",true);
        return current;
    }
    private Admission admission(Map<String,Object> row,ArchiveStockNotificationGate.Token original,boolean lock)  {
        if(Boolean.TRUE.equals(row.get("_sourceDenied"))||!properties.isEnabled()||row.get("recipient_user_id")==null)return denied("SUPPRESSED");
        Map<String,Object> e=repo.one("SELECT * FROM stock_event WHERE id=?",row.get("event_id")),s=repo.stock(id(row.get("stock_id")));
        if(e==null||s==null||id(e.get("patient_id"))!=id(row.get("patient_id"))||id(e.get("stock_id"))!=id(s.get("id"))||id(e.get("aggregate_version"))!=id(s.get("lock_version"))||id(e.get("cycle"))!=id(row.get("cycle"))||!hash((String)e.get("evidence_json")).equals(e.get("evidence_hash"))||!Objects.equals("STOCK_WORKFLOW_"+e.get("type"),row.get("template_key"))||!Objects.equals(s.get("current_work_id"),e.get("work_id")))return denied("SUPPRESSED");
        Map<String,Object> w=repo.work(id(e.get("work_id")));
        long patient=id(row.get("patient_id")),recipient=id(row.get("recipient_user_id"));
        try  {
            auth.requireWrite(id(e.get("actor_id")),patient);
            auth.requireRead(recipient,patient);
            if(recipient!=auth.owner(patient))  {
                if(!Objects.equals(number(w.get("assigned_user_id")),recipient))return denied("SUPPRESSED");
                auth.requireWrite(recipient,patient);
            }
        }
        catch(WorkflowException denied)  {
            if(denied.getStatus()!=403&&denied.getStatus()!=409)throw denied;
            return denied("SUPPRESSED");
        }
        ArchiveStockNotificationGate.Token token=policy.admit(patient,id(s.get("id")),id(e.get("aggregate_version")),original,lock);
        if(token==null)return denied("SUPPRESSED");
        if(row.get("channel_id")==null)return denied("NO_CHANNEL");
        Map<String,Object> channel=repo.one("SELECT user_id,enabled,channel_type FROM notification_channel WHERE id=?",row.get("channel_id"));
        if(channel==null)return denied("NO_CHANNEL");
        if(id(channel.get("user_id"))!=recipient)return denied("SUPPRESSED");
        return ((Number)channel.get("enabled")).intValue()==1&&Arrays.asList("WEBHOOK","WECHAT_WEBHOOK","DINGTALK_WEBHOOK").contains(channel.get("channel_type"))?new Admission(null,token):denied("NO_CHANNEL");
    }
    private Map<String,Object> projection(Map<String,Object> row,boolean admin)  {
        return map("id",row.get("id"),"status",row.get("status"),"attemptCount",row.get("attempt_count"),"lastResult",row.get("last_result"),"nextAttemptAt",iso(row.get("next_attempt_at")),"deliveredAt",iso(row.get("delivered_at")),"retryToken",token(row),"canRetry",admin&&RETRYABLE.contains(row.get("status"))&&retryAvailable(row),"requiresDuplicateRiskAcknowledgement","UNKNOWN".equals(row.get("status")));
    }
    /** Read-only hints fail closed; transaction paths never normalize failed SQL into successful bookkeeping. */
    private boolean retryAvailable(Map<String,Object> row){try{return admission(row,null,false).blocked==null;}catch(RuntimeException unverifiable){if(TransactionSynchronizationManager.isActualTransactionActive())throw unverifiable;return false;}}
    private boolean admin(long actor)  {
        try  {
            auth.requireNotificationAdmin(actor);
            return true;
        }
        catch(WorkflowException e)  {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    private String token(Map<String,Object> row)  {
        return hash(json(map("id",row.get("id"),"status",row.get("status"),"attempts",row.get("attempt_count"),"request",row.get("request_id"),"updated",iso(row.get("updated_at")))));
    }
    private Map<String,Object> notification(long id)  {
        List<Map<String,Object>> rows=notificationRows("SELECT * FROM stock_notification WHERE id=?",id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private List<Map<String,Object>> notificationRows(String sql,Object...args)  {
        return jdbc.query(sql,ps->StockWorkflowRepository.bind(ps,args),(rs,n)->  {
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int i=1; i<=md.getColumnCount(); i++)  {
                String name=md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                Object value=rs.getObject(i);
                if(value instanceof Timestamp)value=rs.getTimestamp(i,utc()).toInstant();
                row.put(name,value);
            }
            return row;
        }
        );
    }
    private Attempt attempt(Map<String,Object> row)  {
        return new Attempt(id(row.get("id")),(String)row.get("status"),(String)row.get("claim_token"),instant(row.get("claimed_at")),(String)row.get("request_id"),((Number)row.get("attempt_count")).intValue(),(String)row.get("last_result"));
    }
    private void complete(Map<String,Object> row,String status,boolean retryable)  {
        tx(()->  {
            NotificationAttemptLifecycle.complete(attempt(row),new Outcome(status,retryable),properties.now(),this::apply);
            return null;
        }
        );
    }
    private boolean apply(Attempt expected,Change change)  {
        mutationTransaction(jdbc);
        switch(change.getKind())  {
            case START:return repo.update("UPDATE stock_notification SET attempt_count=?,request_id=?,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=? AND attempt_count=?",change.getAttemptCount(),change.getRequestId(),change.getLastResult(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken(),expected.getAttemptCount())==1;
            case RECOVER:return repo.update("UPDATE stock_notification SET status=?,next_attempt_at=?,claim_token=NULL,claimed_at=NULL,last_result=?,updated_at=? WHERE id=? AND status='CLAIMED' AND (claim_token=? OR (claim_token IS NULL AND ? IS NULL)) AND claimed_at=? AND (last_result=? OR (last_result IS NULL AND ? IS NULL))",change.getStatus(),change.getNextAttemptAt(),change.getLastResult(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken(),expected.getClaimToken(),expected.getClaimedAt(),expected.getLastResult(),expected.getLastResult())==1;
            case COMPLETE:return repo.update("UPDATE stock_notification SET status=?,next_attempt_at=?,claim_token=NULL,last_result=?,delivered_at=?,updated_at=? WHERE id=? AND status='CLAIMED' AND claim_token=?",change.getStatus(),change.getNextAttemptAt(),change.getLastResult(),change.getDeliveredAt(),change.getUpdatedAt(),expected.getId(),expected.getClaimToken())==1;
            default:throw new IllegalStateException("Unsupported attempt change");
        }
    }
    private void suppress(Map<String,Object> row,String status)  {
        repo.update("UPDATE stock_notification SET status=?,claim_token=NULL,next_attempt_at=NULL,last_result=?,updated_at=? WHERE id=?",status,status,properties.now(),row.get("id"));
    }
    private boolean liveAttempt(Map<String,Object> expected,Map<String,Object> live)  {
        return live!=null&&"CLAIMED".equals(live.get("status"))&&"ATTEMPT_STARTED".equals(live.get("last_result"))&&Objects.equals(expected.get("claim_token"),live.get("claim_token"));
    }
    private boolean expired(Map<String,Object> row,Instant now)  {
        Instant claimed=instant(row.get("claimed_at"));
        return claimed==null||!claimed.plusSeconds(300).isAfter(now);
    }
    private static Long number(Object value)  {
        return value==null?null:id(value);
    }
    private static Instant instant(Object value)  {
        return value==null?null:value instanceof Instant?(Instant)value:((Timestamp)value).toInstant();
    }
    private static String iso(Object value)  {
        return value==null?null:instant(value).toString();
    }
    private <T>T tx(Supplier<T> work)  {
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status->work.get());
    }
}
