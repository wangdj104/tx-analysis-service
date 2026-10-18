package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.PreparedStatement;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** Transactional generic outbox only. No clinical text, provider calls, or invented recipients. */
@Service
public class MedicationExecutionNotificationQueue {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionProperties properties;
    public MedicationExecutionNotificationQueue(JdbcTemplate jdbc,MedicationExecutionAuthorizationService auth,MedicationExecutionProperties properties) {
        this.jdbc=jdbc;
        this.auth=auth;
        this.properties=properties;
    }
    public void enqueue(long eventId) {
        mutationTransaction(jdbc);
        List<Map<String,Object>> rows=jdbc.query("SELECT e.*,r.selected_verifier_id,p.user_id AS owner_id FROM medication_execution_event e JOIN patient p ON p.id=e.patient_id LEFT JOIN medication_reconciliation r ON r.id=e.reconciliation_id AND r.execution_id=e.execution_id AND r.patient_id=e.patient_id WHERE e.id=?",(rs,n)->map("patient",rs.getLong("patient_id"),"execution",rs.getLong("execution_id"),"cycle",rs.getInt("cycle"),"revision",nullableId(rs,"revision_id"),"type",rs.getString("type"),"owner",rs.getLong("owner_id"),"doctor",nullableId(rs,"selected_verifier_id")),eventId);
        if(rows.size()!=1)throw WorkflowException.invalid("Invalid notification event.");
        Map<String,Object> row=rows.get(0);
        Set<Long> recipients=new TreeSet<>();
        recipients.add(id(row.get("owner")));
        if(row.get("doctor")!=null)recipients.add(id(row.get("doctor")));
        int added=0;
        for(long recipient:recipients) {
            if(!auth.canRead(recipient,id(row.get("patient"))))continue;
            if(Objects.equals(row.get("doctor"),recipient)&&!auth.canVerify(recipient,id(row.get("patient")),recipient))continue;
            List<Long> channels=jdbc.queryForList("SELECT id FROM notification_channel WHERE user_id=? AND enabled=1 AND channel_type IN ('WEBHOOK','WECHAT_WEBHOOK','DINGTALK_WEBHOOK') ORDER BY id",Long.class,recipient);
            if(channels.isEmpty()) {
                insert(eventId,row,recipient,null,"NO_CHANNEL","NO_CHANNEL");
                added++;
            }
            else for(long channel:channels) {
                insert(eventId,row,recipient,channel,properties.isEnabled()?"QUEUED":"SUPPRESSED",properties.isEnabled()?"QUEUED":"SUPPRESSED");
                added++;
            }
        }
        if(added==0)insert(eventId,row,null,null,"NO_CHANNEL","NO_RECIPIENT");
    }
    private void insert(long event,Map<String,Object> row,Long recipient,Long channel,String status,String result) {
        String dispatch="event:"+event+":"+(recipient==null?"NONE":recipient)+":"+(channel==null?"NONE":channel);
        jdbc.update(c-> {
            PreparedStatement p=c.prepareStatement("INSERT INTO medication_execution_notification(event_id,patient_id,execution_id,cycle,recipient_kind,recipient_user_id,channel_id,template_key,dispatch_key,status,attempt_count,next_attempt_at,last_result,created_at,updated_at,revision_id) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?)");
            int i=1;
            p.setLong(i++,event);
            p.setLong(i++,id(row.get("patient")));
            p.setLong(i++,id(row.get("execution")));
            p.setInt(i++,((Number)row.get("cycle")).intValue());
            p.setString(i++,recipient==null?"NONE":"USER");
            p.setObject(i++,recipient);
            p.setObject(i++,channel);
            p.setString(i++,"MEDICATION_EXECUTION_"+row.get("type"));
            p.setString(i++,dispatch);
            p.setString(i++,status);
            time(p,i++,"QUEUED".equals(status)?properties.now():null);
            p.setString(i++,result);
            time(p,i++,properties.now());
            time(p,i++,properties.now());
            p.setObject(i,row.get("revision"));
            return p;
        });
    }
}
