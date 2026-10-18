package org.familyhealthcare.service.familyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** 仅当前发起人、具名处理者和必要患者所有者接收通用更新。 */
@Service public class FamilyAssistanceNotificationQueue {
    private final JdbcTemplate jdbc;
    private final FamilyAssistanceAuthorizationService auth;
    private final FamilyAssistanceProperties properties;
    public FamilyAssistanceNotificationQueue(JdbcTemplate jdbc,FamilyAssistanceAuthorizationService auth,FamilyAssistanceProperties properties) {
        this.jdbc=jdbc;
        this.auth=auth;
        this.properties=properties;
    }
    public void enqueue(long event) {
        mutationTransaction(jdbc);
        FamilyAssistanceRepository sql=new FamilyAssistanceRepository(jdbc,null,null,null);
        Map<String,Object> e=sql.one("SELECT e.*,a.originator_id,a.assigned_user_id,a.request_kind,p.user_id AS owner_id FROM family_assistance_event e JOIN family_assistance a ON a.id=e.assistance_id JOIN patient p ON p.id=e.patient_id WHERE e.id=?",event);
        SortedSet<Long> recipients=new TreeSet<>();
        for(String key:Arrays.asList("originator_id","assigned_user_id","owner_id"))if(e.get(key)!=null)recipients.add(id(e.get(key)));
        int added=0;
        for(long r:recipients) {
            if(!auth.canRead(r,id(e.get("patient_id")),(String)e.get("request_kind")))continue;
            List<Long> channels=jdbc.queryForList("SELECT id FROM notification_channel WHERE user_id=? AND enabled=1 AND channel_type IN ('WEBHOOK','WECHAT_WEBHOOK','DINGTALK_WEBHOOK') ORDER BY id LIMIT 51",Long.class,r);
            if(channels.size()>50)throw FamilyAssistanceRepository.conflict("CHANNEL_LIMIT");
            if(channels.isEmpty()) {
                insert(sql,e,r,null,"NO_CHANNEL");
                added++;
            }
            else for(long c:channels) {
                insert(sql,e,r,c,properties.isEnabled()?"QUEUED":"SUPPRESSED");
                added++;
            }
        }
        if(added==0)insert(sql,e,null,null,"NO_CHANNEL");
    }
    private void insert(FamilyAssistanceRepository sql,Map<String,Object> e,Long r,Long c,String status) {
        sql.insert("INSERT INTO family_assistance_notification(event_id,patient_id,assistance_id,cycle,recipient_kind,recipient_user_id,channel_id,template_key,dispatch_key,status,attempt_count,next_attempt_at,last_result,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)",e.get("id"),e.get("patient_id"),e.get("assistance_id"),e.get("cycle"),r==null?"NONE":"USER",r,c,"FAMILY_ASSISTANCE_"+e.get("type"),"event:"+e.get("id")+":"+r+":"+c,status,"QUEUED".equals(status)?properties.now():null,r==null?"NO_RECIPIENT":status,properties.now(),properties.now());
    }
}
