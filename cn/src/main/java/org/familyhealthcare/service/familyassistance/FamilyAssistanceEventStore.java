package org.familyhealthcare.service.familyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
@Service public class FamilyAssistanceEventStore {
    private final FamilyAssistanceRepository repo;
    private final FamilyAssistanceProperties properties;
    private final FamilyAssistanceNotificationQueue queue;
    public FamilyAssistanceEventStore(JdbcTemplate jdbc,FamilyAssistanceProperties properties,FamilyAssistanceNotificationQueue queue) {
        repo=new FamilyAssistanceRepository(jdbc,null,null,null);
        this.properties=properties;
        this.queue=queue;
    }
    public long append(Long actor,Map<String,Object> a,String type,String reason,Map<String,Object> evidence,Instant due,String dueKey) {
        mutationTransaction(repo.jdbc);
        String data=json(evidence);
        long id=repo.insert("INSERT INTO family_assistance_event(patient_id,assistance_id,aggregate_version,cycle,type,actor_kind,actor_id,actor_relation,occurred_at,reason,evidence_json,evidence_hash,due_at,due_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",a.get("patient_id"),a.get("id"),a.get("lock_version"),a.get("cycle"),type,actor==null?"SYSTEM":"HUMAN",actor,actor==null?("DUE".equals(type)?"DUE":"SYSTEM"):"WRITER",properties.now(),reason,data,hash(data),due,dueKey);
        queue.enqueue(id);
        return id;
    }
}
