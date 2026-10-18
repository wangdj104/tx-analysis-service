package org.familyhealthcare.service.visitquestion;

import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Map;

/** 事件只追加；回执完成前，将真实的专业答复事件编号绑定到当前状态。 */
@Repository
public class VisitQuestionEventStore {
    private final VisitQuestionRepository repo;
    public VisitQuestionEventStore(VisitQuestionRepository repo){this.repo=repo;}
    public long append(long actor,String actorKind,Map<String,Object> state,String previous,String operation,String origin,
                       String reason,Map<String,Object> envelope,Map<String,Object> baseline,Instant now){
        String body=VisitQuestionContracts.eventBody(((Number)state.get("lock_version")).longValue(),envelope,baseline);
        return repo.insert(true,"INSERT INTO visit_question_event(question_id,patient_id,version,event_type,from_state,to_state,actor_id,actor_kind,answer_origin,reason_code,body_json,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                state.get("question_id"),state.get("patient_id"),state.get("lock_version"),operation,previous,state.get("state"),actor,actorKind,origin,reason,body,now);
    }
}
