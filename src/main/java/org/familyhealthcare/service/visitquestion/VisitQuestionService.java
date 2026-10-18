package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.visitquestion.VisitQuestionRepository.*;

/** 问题关闭只表示行政跟进结束，不代表诊疗完成，也不改变 B 协助状态。 */
@Service
public class VisitQuestionService {
    private final JdbcTemplate jdbc;
    private final VisitQuestionRepository repo;
    private final VisitQuestionProperties properties;
    private final VisitQuestionAuthorizationService auth;
    private final VisitQuestionTrackingGuard guard;
    private final VisitQuestionEventStore events;
    public VisitQuestionService(VisitQuestionRepository repo,VisitQuestionProperties properties,VisitQuestionAuthorizationService auth,
                                VisitQuestionTrackingGuard guard,VisitQuestionEventStore events,JdbcTemplate jdbc){this.jdbc=jdbc;this.repo=repo;this.properties=properties;this.auth=auth;this.guard=guard;this.events=events;}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,Long question,String commandKey,String payload){
        mutationTransaction(jdbc);
        VisitQuestionContracts.requireBytes(payload,VisitQuestionContracts.ORDINARY_BYTES,"command");
        Map<String,Object> raw=VisitQuestionContracts.parse(payload);
        long expected=VisitQuestionContracts.version(raw.get("expectedVersion"));
        String normalized=VisitQuestionContracts.envelope(payload,question,expected);
        Map<String,Object> envelope=VisitQuestionContracts.parse(normalized);
        long patient=VisitQuestionContracts.id(envelope.get("patientId"),"patientId");
        String operation=(String)envelope.get("operation");
        String key=WorkflowCommandExecutor.validateKey(commandKey);
        auth.write(actor,patient);
        if(!properties.isEnabled())throw new WorkflowException(503,"QUESTION_DISABLED","Question tracking is disabled. Existing manual notes remain available.");
        repo.ready();
        VisitQuestionTrackingGuard.Admission admission=guard.command(actor,patient,question,key,envelope);
        @SuppressWarnings("unchecked") Map<String,Object> commandBody=(Map<String,Object>)envelope.get("body");
        guard.recheck(actor,patient,admission,operation,commandBody);
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        Map<String,Object> receipt=WorkflowCommandExecutor.execute(jdbc,WorkflowCommandExecutor.Domain.VISIT_QUESTION,actor,key,normalized,question,expected,now,
                ()->apply(actor,patient,expected,operation,envelope,admission,now));
        guard.recheck(actor,patient,admission,operation,commandBody);
        Map<String,Object> actual=repo.discover(VisitQuestionContracts.id(receipt.get("questionId"),"questionId"));
        if(actual==null||id(actual.get("patient_id"))!=patient)throw denied();
        // 回执不缓存正文或角色；重放旧成功命令仍需当前权限及操作关系。
        guard.recheck(actor,patient,admission,operation,commandBody);
        return VisitQuestionContracts.receipt(receipt);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> apply(long actor,long patient,long expected,String operation,Map<String,Object> envelope,
                                                                   VisitQuestionTrackingGuard.Admission admission,Instant now){
        Map<String,Object> body=(Map<String,Object>)envelope.get("body");
        Map<String,Object> source=admission.source;
        boolean first=admission.state==null;
        if(first&&expected!=0||!first&&id(admission.state.get("lock_version"))!=expected)throw conflict("VERSION_CONFLICT");
        if(!first&&Arrays.asList("CREATE","ADOPT").contains(operation))throw conflict("QUESTION_ALREADY_TRACKED");
        Map<String,Object> baseline=source==null?null:repo.baseline(source);
        if(first&&source==null){
            if(!"CREATE".equals(operation))throw denied();
            Map<String,Object> ordinary=new LinkedHashMap<>();if(body.containsKey("description"))ordinary.put("description",body.get("description"));
            if(body.get("legacyAppointmentId")!=null)ordinary.put("appointmentId",body.get("legacyAppointmentId"));
            ordinary.put("questionWorkflowVersion",1);
            long id=repo.createNative(patient,auth.owner(patient),actor,(String)body.get("title"),json(ordinary),now,true);
            source=repo.source(id,patient,true,true);
        }
        Map<String,Object> state=first?initial(actor,patient,source,now):new LinkedHashMap<>(admission.state);
        String previous=first?null:(String)state.get("state");
        String from=(String)state.get("state");
        if("CLOSED".equals(from)&&!"REOPEN".equals(operation))throw conflict("QUESTION_CLOSED");
        boolean professional="PUBLISH_ANSWER".equals(operation);
        String origin=null;
        switch(operation){
            case "CREATE": case "ADOPT": case "EDIT":
                if(!Arrays.asList("OPEN","UNRESOLVED").contains(from))throw conflict("QUESTION_REOPEN_REQUIRED");
                boolean changed=false;
                for(String[] pair:new String[][]{{"title","current_title"},{"description","current_description"},{"legacyAppointmentId","legacy_appointment_id"}})
                    if(body.containsKey(pair[0])){changed|=!Objects.equals(state.get(pair[1]),body.get(pair[0]));state.put(pair[1],body.get(pair[0]));}
                if(body.containsKey("context")){applyContext(actor,patient,state,(Map<String,Object>)body.get("context"));changed=true;}
                if("EDIT".equals(operation)&&!first&&!changed)throw conflict("QUESTION_NO_CHANGE");
                break;
            case "RECORD_ANSWER": case "PUBLISH_ANSWER":
                requireState(from,"OPEN","UNRESOLVED","FOLLOW_UP");requireContext(actor,patient,state);
                if(Boolean.TRUE.equals(body.get("keepFollowUp"))&&!"FOLLOW_UP".equals(from))throw conflict("QUESTION_FOLLOW_UP_REQUIRED");
                if(professional){auth.publish(actor,patient);origin="CLINICIAN_AUTHORED";}
                else {
                    if(Objects.equals(state.get("current_recorded_answer"),body.get("answer")))throw conflict("QUESTION_NO_CHANGE");
                    state.put("current_recorded_answer",body.get("answer"));state.put("ordinary_answer_origin","USER_RECORDED");
                    state.put("ordinary_answer_recorder_id",actor);state.put("ordinary_answer_recorded_at",now);origin="USER_RECORDED";
                }
                state.put("state",Boolean.TRUE.equals(body.get("keepFollowUp"))?"FOLLOW_UP":"ANSWERED");break;
            case "MARK_UNRESOLVED":
                requireState(from,"OPEN","ANSWERED","FOLLOW_UP");state.put("state","UNRESOLVED");break;
            case "SET_FOLLOW_UP":
                requireState(from,"OPEN","UNRESOLVED","ANSWERED");requireContext(actor,patient,state);
                // 遗漏保留原负责人（包括明确未分配）；改派权限与原因在统一入场中检查。
                Object selected=body.containsKey("responsibleUserId")?body.get("responsibleUserId"):state.get("responsible_user_id");
                Long responsible=selected==null?null:VisitQuestionContracts.id(selected,"responsibleUserId");
                auth.assignee(responsible,patient);state.put("current_follow_up",body.get("followUp"));state.put("responsible_user_id",responsible);
                state.put("follow_up_due_date",body.get("dueDate"));state.put("state","FOLLOW_UP");break;
            case "CLOSE":
                if("ANSWER_SUFFICIENT".equals(body.get("reason"))){
                    requireContext(actor,patient,state);
                    if(!nonblank(state.get("current_recorded_answer"))&&state.get("current_answer_event_id")==null)throw conflict("QUESTION_ANSWER_REQUIRED");
                }
                if("FOLLOW_UP_FINISHED".equals(body.get("reason"))){
                    requireContext(actor,patient,state);
                    if(body.containsKey("resultReference"))requireResult(actor,patient,(Map<String,Object>)body.get("resultReference"));
                    else if(!nonblank(body.get("resultText")))throw WorkflowException.invalid("Record the actual follow-up result.");
                }
                state.put("state","CLOSED");state.put("close_reason",body.get("reason"));break;
            case "REOPEN":
                requireState(from,"CLOSED","ANSWERED","FOLLOW_UP","UNRESOLVED");state.put("state",body.get("state"));state.put("close_reason",null);break;
            default:throw WorkflowException.invalid("Unsupported question operation.");
        }
        if(state.get("legacy_appointment_id")!=null)legacyAppointment(actor,patient,id(state.get("legacy_appointment_id")));
        state.put("lock_version",VisitQuestionContracts.nextVersion(expected));state.put("updated_at",now);
        VisitQuestionContracts.requireRawScalars(text(state,"current_title"),text(state,"current_description"),text(state,"current_recorded_answer"),text(state,"current_follow_up"));
        // 写入前检查完整事件及首次原文基线的特殊字节预算。
        VisitQuestionContracts.eventBody(expected+1,envelope,first?baseline:null);
        guard.recheck(actor,patient,admission,operation,body);
        if(!admission.bOwned){
            repo.mirror(source,state,true,true);
            source=repo.source(id(source.get("id")),patient,true,true);
        }
        state.put("source_observed_hash",VisitQuestionContracts.sourceObservedHash(repo.baseline(source)));
        state.put("source_observed_at",now);state.put("observed_b_assistance_id",source.get("family_assistance_id"));
        repo.saveState(state,first,expected);
        if(origin==null&&first&&state.get("ordinary_answer_origin")!=null)origin="LEGACY_UNVERIFIED";
        long event=events.append(actor,auth.actorKind(actor,patient,professional),state,previous,operation,origin,(String)body.get("reason"),envelope,first?baseline:null,now);
        if(professional){
            if(repo.update(true,"UPDATE visit_question_state SET current_answer_event_id=? WHERE question_id=? AND patient_id=? AND lock_version=?",event,state.get("question_id"),patient,state.get("lock_version"))!=1)throw conflict("QUESTION_STATE_CHANGED");
        }
        return map("questionId",state.get("question_id"),"version",state.get("lock_version"),"eventId",event,"state",state.get("state"));
    }
    private Map<String,Object> initial(long actor,long patient,Map<String,Object> source,Instant now){
        Map<String,Object> d=repo.details(source);
        Map<String,Object> state=map("question_id",source.get("id"),"patient_id",patient,"state","OPEN","lock_version",1L,
                "current_title",source.get("title"),"current_description",ordinary(d,"description"),"current_recorded_answer",ordinary(d,"answer"),"current_follow_up",ordinary(d,"followUp"),
                "ordinary_answer_origin",d.get("answer")==null?null:"LEGACY_UNVERIFIED","ordinary_answer_recorder_id",null,"ordinary_answer_recorded_at",null,
                "legacy_appointment_id",d.get("appointmentId")==null?null:VisitQuestionContracts.id(d.get("appointmentId"),"appointmentId"),
                "source_observed_hash",VisitQuestionContracts.sourceObservedHash(repo.baseline(source)),"source_observed_at",now,"observed_b_assistance_id",source.get("family_assistance_id"),
                "context_type",null,"context_id",null,"context_fingerprint",null,"responsible_user_id",auth.qualifiedOwner(patient),"follow_up_due_date",null,"current_answer_event_id",null,"close_reason",null,
                "created_by",actor,"created_at",now,"updated_at",now);
        return state;
    }
    private static String ordinary(Map<String,Object> details,String field){Object value=details.get(field);if(value!=null&&!(value instanceof String))throw conflict("QUESTION_ORIGINAL_INVALID");return (String)value;}
    private void applyContext(long actor,long patient,Map<String,Object> state,Map<String,Object> context){
        if(context==null){state.put("context_type",null);state.put("context_id",null);state.put("context_fingerprint",null);return;}
        String type=(String)context.get("type");long id=VisitQuestionContracts.id(context.get("id"),"context.id");
        Map<String,Object> actual=context(actor,patient,type,id);
        if(!Objects.equals(context.get("fingerprint"),actual.get("fingerprint")))throw conflict("QUESTION_CONTEXT_CHANGED");
        state.put("context_type",type);state.put("context_id",id);state.put("context_fingerprint",actual.get("fingerprint"));
    }
    private void requireContext(long actor,long patient,Map<String,Object> state){
        if(state.get("context_type")==null)return;
        Map<String,Object> actual=context(actor,patient,(String)state.get("context_type"),id(state.get("context_id")));
        if(!Objects.equals(actual.get("fingerprint"),state.get("context_fingerprint")))throw conflict("QUESTION_CONTEXT_CHANGED");
    }
    /** 只取精确关联的元数据；选中的医生仅为说明，不提供专业或访问权限。 */
    public Map<String,Object> context(long actor,long patient,String type,long id){
        auth.read(actor,patient);Map<String,Object> row;
        switch(type){
            case "LEGACY_APPOINTMENT":row=legacyAppointment(actor,patient,id);break;
            case "CARE_APPOINTMENT":row=repo.one("SELECT id,patient_id,status,doctor_user_id,start_at,end_at,updated_at FROM care_appointment WHERE id=? AND patient_id=?",id,patient);break;
            case "VISIT_RECORD":row=repo.one("SELECT id,patient_id,status,doctor_user_id,appointment_id,visited_at,published_at,updated_at FROM visit_record WHERE id=? AND patient_id=?",id,patient);break;
            default:throw WorkflowException.invalid("Unsupported question context.");
        }
        if(row==null||id(row.get("patient_id"))!=patient||"CANCELLED".equals(row.get("status"))||"VISIT_RECORD".equals(type)&&!"PUBLISHED".equals(row.get("status")))throw conflict("QUESTION_CONTEXT_CHANGED");
        String fingerprint=hash(json(map("type",type,"source",row)));auth.read(actor,patient);
        return map("type",type,"id",id,"fingerprint",fingerprint,"status",row.get("status"));
    }
    private Map<String,Object> legacyAppointment(long actor,long patient,long id){
        auth.read(actor,patient);
        Map<String,Object> size=repo.one("SELECT id,patient_id,kind,OCTET_LENGTH(data_json) AS bytes FROM care_item WHERE id=? AND patient_id=?",id,patient);
        if(size==null||!"APPOINTMENT".equals(size.get("kind")))throw conflict("QUESTION_CONTEXT_CHANGED");
        if(size.get("bytes")!=null)bound(size.get("bytes"),VisitQuestionContracts.RAW_BYTES);
        Map<String,Object> row=repo.nativeOne("SELECT id,patient_id,status,event_at,updated_at,data_json FROM care_item WHERE id=? AND patient_id=?",id,patient);
        repo.details(row);row.remove("data_json");return row;
    }
    @SuppressWarnings("unchecked") private void requireResult(long actor,long patient,Map<String,Object> reference){
        String type=(String)reference.get("type");long id=VisitQuestionContracts.id(reference.get("id"),"resultReference.id");
        if(!"QUESTION_EVENT".equals(type)){
            Map<String,Object> record=context(actor,patient,type,id);
            if(!"VISIT_RECORD".equals(type)&&!Arrays.asList("DONE","COMPLETED").contains(record.get("status")))throw conflict("QUESTION_RESULT_REQUIRED");return;
        }
        Map<String,Object> event=repo.one("SELECT question_id,patient_id,event_type FROM visit_question_event WHERE id=? AND patient_id=?",id,patient);
        if(event==null||!Arrays.asList("RECORD_ANSWER","PUBLISH_ANSWER").contains(event.get("event_type")))throw conflict("QUESTION_RESULT_REQUIRED");
        repo.details(repo.source(id(event.get("question_id")),patient,true,true));auth.read(actor,patient);
    }
    static boolean nonblank(Object value){return value instanceof String&&!((String)value).codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c));}
    private static void requireState(String actual,String...allowed){if(!Arrays.asList(allowed).contains(actual))throw conflict("QUESTION_TRANSITION_INVALID");}
}
