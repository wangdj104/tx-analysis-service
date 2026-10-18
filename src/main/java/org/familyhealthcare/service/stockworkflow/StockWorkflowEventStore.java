package org.familyhealthcare.service.stockworkflow;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
@Service public class StockWorkflowEventStore  {
    private final StockWorkflowRepository sql;
    private final StockWorkflowProperties properties;
    private final StockWorkflowNotificationQueue queue;
    public StockWorkflowEventStore(JdbcTemplate jdbc,StockWorkflowProperties properties,StockWorkflowNotificationQueue queue)  {
        this.sql=new StockWorkflowRepository(jdbc,null,null,null);
        this.properties=properties;
        this.queue=queue;
    }
    public long append(long actor,Map<String,Object> stock,Map<String,Object> work,long version,String type,String reason,Map<String,Object> evidence,Long intake,Long action,Long executionEvent,Long original,Long compensation,Long count,Long prior,Long movement,String settlement)  {
        mutationTransaction(sql.jdbc);
        Map<String,Object> recorded=new LinkedHashMap<>(evidence);
        if(!"CHANGE_RULE".equals(type)) {
            recorded.put("ruleEventId",work.get("current_rule_event_id"));
            recorded.put("ruleVersion",work.get("current_rule_version"));
            recorded.put("ruleHash",work.get("current_rule_hash"));
        }
        String data=json(recorded);
        long event=sql.insert("INSERT INTO stock_event(patient_id,stock_id,aggregate_version,cycle,type,actor_kind,actor_id,actor_relation,occurred_at,reason,evidence_json,evidence_hash,work_id,intake_id,action_id,execution_event_id,original_movement_id,compensation_movement_id,count_id,prior_stock_event_id,movement_id,settlement_status,settled_action_id) VALUES(?,?,?,?,?,'HUMAN',?,'WRITER',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",stock.get("patient_id"),stock.get("id"),version,work.get("cycle"),type,actor,properties.now(),reason,data,hash(data),work.get("id"),intake,action,executionEvent,original,compensation,count,prior,movement,settlement,settlement==null||"UNRECONCILED".equals(settlement)?null:action);
        queue.enqueue(event);
        return event;
    }
}
