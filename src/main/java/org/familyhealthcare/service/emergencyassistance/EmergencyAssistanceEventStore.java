package org.familyhealthcare.service.emergencyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
@Service public class EmergencyAssistanceEventStore {
    private final EmergencyAssistanceRepository repo;
    private final EmergencyAssistanceProperties properties;
    final EmergencyAssistanceNotificationQueue queue;
    public EmergencyAssistanceEventStore(JdbcTemplate j,EmergencyAssistanceProperties p,EmergencyAssistanceNotificationQueue q){
        repo=new EmergencyAssistanceRepository(j,null,null,null);
        properties=p;
        queue=q;
    }
    public long append(long actor,Map<String,Object>a,String type,String reason,Map<String,Object>evidence){
        mutationTransaction(repo.jdbc);
        String data=json(evidence);
        return repo.insert("INSERT INTO emergency_assistance_event(patient_id,emergency_event_id,aggregate_version,cycle,type,actor_kind,actor_id,actor_relation,occurred_at,reason,evidence_json,evidence_hash) VALUES(?,?,?,1,?,'HUMAN',?,'WRITER',?,?,?,?)",
        a.get("patient_id"),a.get("id"),a.get("lock_version"),type,actor,properties.now(),reason,data,
        hash(data));
    }
}
