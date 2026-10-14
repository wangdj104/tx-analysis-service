package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;

/** Durable enqueue only. Delivery is deliberately outside this service and the business transaction. */
@Service
@ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
public class AlertWorkflowNotificationQueue {
    private static final Set<String> EVENTS=new HashSet<>(Arrays.asList("CREATED","ASSIGN","ESCALATE","REJECT_ASSIGNMENT","SUBMIT","FEEDBACK","RETURN","CLOSE","CANCEL","REOPEN","SOURCE_CHANGED","DUE"));
    private final JdbcTemplate jdbc;
    private final AlertWorkflowAuthorizationService auth;
    private final AlertWorkflowProperties properties;
    public AlertWorkflowNotificationQueue(JdbcTemplate jdbc,AlertWorkflowAuthorizationService auth,AlertWorkflowProperties properties) {this.jdbc=jdbc;this.auth=auth;this.properties=properties;}
    public void enqueue(long eventId) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);
        List<Map<String,Object>> rows=jdbc.query("SELECT e.id,e.event_type,e.patient_id,a.id AS alert_id,a.responsible_user_id,a.workflow_mode,a.source_type,a.source_id,e.cycle_no,e.payload_json,a.cycle_no AS current_cycle,p.user_id FROM alert_event e JOIN alert_record a ON a.id=e.alert_id AND a.patient_id=e.patient_id JOIN patient p ON p.id=a.patient_id WHERE e.id=? AND a.workflow_version=1",
                (rs,i)->map("eventId",rs.getLong("id"),"eventType",rs.getString("event_type"),"patientId",rs.getLong("patient_id"),"alertId",rs.getLong("alert_id"),"owner",rs.getLong("user_id"),"assignee",nullableId(rs,"responsible_user_id"),"mode",rs.getString("workflow_mode"),"sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id"),"payload",object(rs.getString("payload_json")),"cycle",rs.getInt("cycle_no"),"currentCycle",rs.getInt("current_cycle")),eventId);
        if(rows.isEmpty()||!EVENTS.contains(rows.get(0).get("eventType")))throw AlertWorkflowException.invalid("Unsupported notification event.");
        Map<String,Object> row=rows.get(0);if(!Objects.equals(row.get("cycle"),row.get("currentCycle")))return;
        long patient=id(row.get("patientId"));Set<Long> recipients=new TreeSet<>();recipients.add(id(row.get("owner")));
        if(row.get("assignee")!=null)recipients.add(id(row.get("assignee")));
        // Only this patient's currently assigned clinicians, never the family membership list.
        if("CLINICAL".equals(row.get("mode")))for(Long doctor:jdbc.queryForList("SELECT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND status='ACTIVE'",Long.class,patient)) {
            try{auth.requireClinical(doctor,patient);recipients.add(doctor);}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;}
        }
        for(long recipient:recipients) {
            if(!authorized(recipient,patient,row))continue;
            List<Long> channels=jdbc.queryForList("SELECT id FROM notification_channel WHERE user_id=? AND enabled=1 AND channel_type IN ('WEBHOOK','WECHAT_WEBHOOK','DINGTALK_WEBHOOK') ORDER BY id",Long.class,recipient);
            if(channels.isEmpty())insert(eventId,patient,recipient,null,"NO_CHANNEL",prefix(row));
            else for(Long channel:channels)if(authorized(recipient,patient,row))insert(eventId,patient,recipient,channel,"QUEUED",prefix(row));
        }
    }
    private boolean authorized(long actor,long patient,Map<String,Object> row) {
        try {auth.requireRead(actor,patient);if(Objects.equals(row.get("assignee"),actor))auth.requireRecord(actor,patient);
            return auth.canReadEvidence(actor,patient,(String)row.get("sourceType"),id(row.get("sourceId")));
        }catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;return false;}
    }
    @SuppressWarnings("unchecked") private String prefix(Map<String,Object> row){
        if(!"DUE".equals(row.get("eventType")))return "event:"+row.get("eventId");
        Object key=((Map<String,Object>)row.get("payload")).get("dueKey");
        if(!(key instanceof String)||!((String)key).startsWith("due:"+row.get("alertId")+":"+row.get("cycle")+":"))throw AlertWorkflowException.invalid("Invalid due event identity.");return(String)key;
    }
    private void insert(long event,long patient,long recipient,Long channel,String status,String prefix) {
        Instant now=properties.now();String key=prefix+":"+recipient+":"+(channel==null?"NO_CHANNEL":channel);
        try {jdbc.update(c->{PreparedStatement ps=c.prepareStatement("INSERT INTO alert_notification(event_id,patient_id,recipient_user_id,channel_id,dispatch_key,status,attempt_count,next_attempt_at,last_result,created_at,updated_at) VALUES(?,?,?,?,?,?,0,?,?,?,?)");
            ps.setLong(1,event);ps.setLong(2,patient);ps.setLong(3,recipient);ps.setObject(4,channel);ps.setString(5,key);ps.setString(6,status);time(ps,7,"QUEUED".equals(status)?now:null);ps.setString(8,status);time(ps,9,now);time(ps,10,now);return ps;});
        }catch(DuplicateKeyException duplicate){/* Unique event/recipient/channel identity is the durable deduplication boundary. */}
    }
}
