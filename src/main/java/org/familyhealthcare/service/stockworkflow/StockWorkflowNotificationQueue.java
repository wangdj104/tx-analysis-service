package org.familyhealthcare.service.stockworkflow;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** Only current patient owner and explicitly assigned current writer receive generic updates. */
@Service public class StockWorkflowNotificationQueue  {
    private final JdbcTemplate jdbc;
    private final StockWorkflowAuthorizationService auth;
    private final StockWorkflowProperties properties;
    private final org.familyhealthcare.service.archivestock.ArchiveStockNotificationGate policy;
    public StockWorkflowNotificationQueue(JdbcTemplate jdbc,StockWorkflowAuthorizationService auth,StockWorkflowProperties properties)  {
        this.jdbc=jdbc;
        this.auth=auth;
        this.properties=properties;
        this.policy=new org.familyhealthcare.service.archivestock.ArchiveStockNotificationGate(jdbc);
    }
    public void enqueue(long event)  {
        mutationTransaction(jdbc);
        StockWorkflowRepository sql=new StockWorkflowRepository(jdbc,null,null,null);
        Map<String,Object> e=sql.one("SELECT e.*,w.assigned_user_id,p.user_id AS owner_id FROM stock_event e JOIN stock_work w ON w.id=e.work_id JOIN patient p ON p.id=e.patient_id WHERE e.id=?",event);
        if(e==null)throw WorkflowException.invalid("Invalid stock event.");
        SortedSet<Long> recipients=new TreeSet<>();
        recipients.add(id(e.get("owner_id")));
        if(e.get("assigned_user_id")!=null&&auth.canWrite(id(e.get("assigned_user_id")),id(e.get("patient_id"))))recipients.add(id(e.get("assigned_user_id")));
        // Existing command graph is already locked. New policy lock is appended only here.
        // A real metadata/row SQL failure propagates through this same command transaction.
        boolean allowed=policy.admit(id(e.get("patient_id")),id(e.get("stock_id")),id(e.get("aggregate_version")),null,true)!=null;
        int added=0;
        for(long recipient:recipients)  {
            if(!auth.canRead(recipient,id(e.get("patient_id"))))continue;
            if(!allowed){insert(sql,e,recipient,null,"SUPPRESSED");added++;continue;}
            List<Long> channels=jdbc.queryForList("SELECT id FROM notification_channel WHERE user_id=? AND enabled=1 AND channel_type IN ('WEBHOOK','WECHAT_WEBHOOK','DINGTALK_WEBHOOK') ORDER BY id",Long.class,recipient);
            if(channels.isEmpty())  {
                insert(sql,e,recipient,null,"NO_CHANNEL");
                added++;
            }
            else for(long channel:channels)  {
                insert(sql,e,recipient,channel,properties.isEnabled()?"QUEUED":"SUPPRESSED");
                added++;
            }
        }
        if(added==0)insert(sql,e,null,null,"NO_CHANNEL");
    }
    private void insert(StockWorkflowRepository sql,Map<String,Object> e,Long recipient,Long channel,String status)  {
        sql.insert("INSERT INTO stock_notification(event_id,patient_id,stock_id,cycle,recipient_kind,recipient_user_id,channel_id,template_key,dispatch_key,status,attempt_count,next_attempt_at,last_result,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)",e.get("id"),e.get("patient_id"),e.get("stock_id"),e.get("cycle"),recipient==null?"NONE":"USER",recipient,channel,"STOCK_WORKFLOW_"+e.get("type"),"event:"+e.get("id")+":"+recipient+":"+channel,status,"QUEUED".equals(status)?properties.now():null,recipient==null?"NO_RECIPIENT":status,properties.now(),properties.now());
    }
}
