package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** Append-only human evidence and outbox are in the same native transaction. */
@Service
public class MedicationExecutionEventStore {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionNotificationQueue queue;
    public MedicationExecutionEventStore(JdbcTemplate jdbc,MedicationExecutionProperties properties,MedicationExecutionNotificationQueue queue) {
        this.jdbc=jdbc;
        this.properties=properties;
        this.queue=queue;
    }
    public long append(long actor,long patient,long execution,long version,int cycle,String type,String relation,Long reconciliation,Long revision,String reason,Map<String,Object> evidence) {
        mutationTransaction(jdbc);
        String json=json(evidence);
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(c-> {
            PreparedStatement p=c.prepareStatement("INSERT INTO medication_execution_event(patient_id,execution_id,aggregate_version,cycle,type,actor_kind,actor_id,actor_relation,occurred_at,reason,evidence_json,evidence_hash,reconciliation_id,revision_id) VALUES(?,?,?,?,?,'HUMAN',?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            MedicationExecutionRepository.bind(p,new Object[] {
                patient,execution,version,cycle,type,actor,relation,properties.now(),reason,json,hash(json),reconciliation,revision
            });
            return p;
        }
        ,key);
        long event=Objects.requireNonNull(key.getKey()).longValue();
        queue.enqueue(event);
        return event;
    }
    public long appendFact(long actor,long patient,long execution,long version,int cycle,String type,String relation,Long reconciliation,Long revision,String reason,Map<String,Object> evidence,long intake,Long action) {
        return appendLinked(actor,patient,execution,version,cycle,type,relation,reconciliation,revision,reason,evidence,intake,action,null,properties.now());
    }
    public long appendSystem(long patient,long execution,long version,int cycle,String type,Long reconciliation,Long revision,String reason,Map<String,Object> evidence,Long intake,Instant due,Instant now) {
        return appendLinked(null,patient,execution,version,cycle,type,due==null?"SYSTEM":"DUE",reconciliation,revision,reason,evidence,intake,null,due,now);
    }
    private long appendLinked(Long actor,long patient,long execution,long version,int cycle,String type,String relation,Long reconciliation,Long revision,String reason,Map<String,Object> evidence,Long intake,Long action,Instant due,Instant now) {
        mutationTransaction(jdbc);String value=json(evidence);
        String dueKey=due==null?null:"MEDICATION_EXECUTION:"+execution+":"+intake+":"+cycle+":"+java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(java.time.ZoneOffset.UTC).format(due);
        GeneratedKeyHolder key=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO medication_execution_event(patient_id,execution_id,aggregate_version,cycle,type,actor_kind,actor_id,actor_relation,occurred_at,reason,evidence_json,evidence_hash,reconciliation_id,revision_id,intake_id,action_id,due_at,due_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);MedicationExecutionRepository.bind(p,new Object[]{patient,execution,version,cycle,type,actor==null?"SYSTEM":"HUMAN",actor,relation,now,reason,value,hash(value),reconciliation,revision,intake,action,due,dueKey});return p;},key);
        long event=Objects.requireNonNull(key.getKey()).longValue();queue.enqueue(event);return event;
    }

}
