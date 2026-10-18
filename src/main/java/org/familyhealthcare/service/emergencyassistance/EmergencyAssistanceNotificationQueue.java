package org.familyhealthcare.service.emergencyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** Frozen invitations independent of transport, with a 100-row total transaction budget. */
@Service public class EmergencyAssistanceNotificationQueue {
    private final JdbcTemplate jdbc;
    private final EmergencyAssistanceAuthorizationService auth;
    private final EmergencyAssistanceProperties properties;
    public EmergencyAssistanceNotificationQueue(JdbcTemplate j,EmergencyAssistanceAuthorizationService a,
    EmergencyAssistanceProperties p){
        jdbc=j;
        auth=a;
        properties=p;
    }
    public Map<Long,List<Long>> plan(long patient,Collection<Long> recipients){
        Map<Long,List<Long>> out=new LinkedHashMap<>();
        int total=0;
        for(long user:recipients){
            auth.requireRead(user,patient);
            List<Long> channels=jdbc.queryForList("SELECT id FROM notification_channel WHERE user_id=? AND enabled=1 AND channel_type IN ('WEBHOOK','WECHAT_WEBHOOK','DINGTALK_WEBHOOK') ORDER BY id LIMIT 51",
            Long.class,user);
            if(channels.size()>50)throw EmergencyAssistanceRepository.conflict("CHANNEL_LIMIT");
            total+=Math.max(1,channels.size());
            if(total>100)throw EmergencyAssistanceRepository.conflict("CHANNEL_LIMIT");
            out.put(user,channels);
        }
        return out;
    }
    public void enqueue(long event,Map<Long,List<Long>> audience){
        mutationTransaction(jdbc);
        EmergencyAssistanceRepository sql=new EmergencyAssistanceRepository(jdbc,null,null,null);
        Map<String,Object> e=sql.one("SELECT * FROM emergency_assistance_event WHERE id=?",event);
        if(audience.isEmpty())insert(sql,e,null,null,"NO_CHANNEL");
        for(Map.Entry<Long,List<Long>> entry:audience.entrySet()){
            if(!sql.invited(id(e.get("emergency_event_id")),entry.getKey()))throw EmergencyAssistanceRepository.conflict("INVITATION_INVALID");
            if(entry.getValue().isEmpty())insert(sql,e,entry.getKey(),null,"NO_CHANNEL");
            else for(long c:entry.getValue())insert(sql,e,entry.getKey(),c,properties.isEnabled()?"QUEUED":"SUPPRESSED");
        }
    }
    private void insert(EmergencyAssistanceRepository sql,Map<String,Object>e,Long r,Long c,String status){
        sql.insert("INSERT INTO emergency_assistance_notification(event_id,patient_id,emergency_event_id,cycle,recipient_kind,recipient_user_id,channel_id,template_key,dispatch_key,status,attempt_count,next_attempt_at,last_result,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
        e.get("id"),e.get("patient_id"),e.get("emergency_event_id"),1,r==null?"NONE":"USER",r,c,"EMERGENCY_ASSISTANCE_"+e.get("type"),
        "event:"+e.get("id")+":"+r+":"+c,status,"QUEUED".equals(status)?properties.now():null,r==null?"NO_RECIPIENT":status,
        properties.now(),properties.now());
    }
}
