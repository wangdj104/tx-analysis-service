package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.service.familyassistance.FamilyAssistanceSourceBridge;
import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.visitquestion.VisitQuestionReadSupport.*;

/** 当前数据库授权、有限主键分页；原文基线只能通过单个事件详情读取。 */
@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class VisitQuestionQueryService {
    final VisitQuestionRepository repo;
    final VisitQuestionAuthorizationService auth;
    final VisitQuestionProperties properties;
    private final FamilyAssistanceSourceBridge bridge;
    public VisitQuestionQueryService(VisitQuestionRepository repo,VisitQuestionAuthorizationService auth,VisitQuestionProperties properties,FamilyAssistanceSourceBridge bridge){this.repo=repo;this.auth=auth;this.properties=properties;this.bridge=bridge;}
    public static int pageSize(Integer value){int limit=value==null?25:value;if(limit<1||limit>50)throw invalid("问题页大小必须在 1 至 50 之间。");return limit;}
    void access(long actor,long patient){VisitQuestionContracts.id(actor,"actor");VisitQuestionContracts.id(patient,"patientId");auth.read(actor,patient);}
    boolean bStorage(){return bridge.storageAvailable();}
    VisitQuestionSchema.Availability availability(){return repo.availability(properties);}
    void ready(long actor,long patient){access(actor,patient);VisitQuestionSchema.Availability availability=availability();if(availability!=VisitQuestionSchema.Availability.READY)throw unavailable(availability);repo.ready();auth.read(actor,patient);}
    private static WorkflowException unavailable(VisitQuestionSchema.Availability availability){return new WorkflowException(503,"QUESTION_"+(availability==null?"INCOMPATIBLE":availability.name()),"问题跟踪暂不可用；早期普通记录与跟踪历史分别保留。");}
    private boolean allowed(Runnable check){try{check.run();return true;}catch(WorkflowException failure){if(failure.getStatus()!=403)throw failure;return false;}}
    public Map<String,Object> capabilities(long actor,long patient){
        access(actor,patient);VisitQuestionSchema.Availability availability=availability();
        boolean write=allowed(()->auth.write(actor,patient)),publish=write&&allowed(()->auth.publish(actor,patient));
        Map<String,Object> out=map("patientId",patient,"actorId",actor,"enabled",properties.isEnabled(),"availability",availability==null?"INCOMPATIBLE":availability.name(),"read",availability==VisitQuestionSchema.Availability.READY,"write",availability==VisitQuestionSchema.Availability.READY&&write,"publish",availability==VisitQuestionSchema.Availability.READY&&publish,"legacyNotes",true);
        if(write)auth.write(actor,patient);if(publish)auth.publish(actor,patient);auth.read(actor,patient);return out;
    }
    public Map<String,Object> list(long actor,long patient,boolean legacy,String state,String contextType,Long contextId,String cursor,Integer requested){
        int limit=pageSize(requested);access(actor,patient);
        if(state!=null&&!VisitQuestionContracts.STATES.contains(state)&&!"ACTIVE".equals(state))throw invalid("不支持此问题状态筛选。");
        if((contextType==null)!=(contextId==null)||contextType!=null&&!VisitQuestionContracts.CONTEXTS.contains(contextType))throw invalid("请选择明确类型及编号的就诊关联。");
        if(contextId!=null)VisitQuestionContracts.id(contextId,"contextId");
        if(legacy&&(state!=null||contextType!=null))throw invalid("关联及状态筛选仅适用于已跟踪问题。");
        String lane=(legacy?"LEGACY":"MANAGED")+":"+state+":"+contextType+":"+contextId;long after=cursor(cursor,actor,patient,lane);
        VisitQuestionSchema.Availability availability=availability();boolean modern=availability==VisitQuestionSchema.Availability.READY;
        if(!legacy&&!modern)throw unavailable(availability);
        if(availability==VisitQuestionSchema.Availability.INCOMPATIBLE)throw unavailable(availability);
        VisitQuestionSchema.Status storage=repo.storage();if(storage==VisitQuestionSchema.Status.INCOMPATIBLE)throw unavailable(VisitQuestionSchema.Availability.INCOMPATIBLE);
        boolean b=bridge.storageAvailable();List<Object> args=new ArrayList<>();args.add(patient);args.add(after);
        String sql;
        if(legacy){
            sql="SELECT n.id AS question_id,n.patient_id,LEFT(n.title,240) AS title,n.status AS legacy_status,LEFT("+field("n","description")+",240) AS description,LEFT("+field("n","answer")+",240) AS answer,LEFT("+field("n","followUp")+",240) AS follow_up,"+flags(false,b)+" FROM care_item n WHERE n.patient_id=? AND n.kind='QUESTION' AND n.id>?";
            if(storage==VisitQuestionSchema.Status.STRUCTURALLY_READY)sql+=" AND NOT EXISTS(SELECT 1 FROM visit_question_state v WHERE v.question_id=n.id)";
            // 关闭时仍可读旧记录，但接管标记绝不能伪装成可编辑的普通记录。
            sql+=" AND "+marker("n")+" IS NULL ORDER BY n.id LIMIT ?";
        }else{
            repo.ready();sql="SELECT s.question_id,s.patient_id,s.state,s.lock_version,LEFT(s.current_title,240) AS title,LEFT(s.current_description,240) AS description,LEFT(s.current_recorded_answer,240) AS answer,LEFT(s.current_follow_up,240) AS follow_up,s.ordinary_answer_origin,s.ordinary_answer_recorder_id,s.ordinary_answer_recorded_at,s.context_type,s.context_id,s.responsible_user_id,s.follow_up_due_date,s.current_answer_event_id,"+flags(true,b)+" FROM visit_question_state s LEFT JOIN care_item n ON n.id=s.question_id AND n.patient_id=s.patient_id AND n.kind='QUESTION' WHERE s.patient_id=? AND s.question_id>?";
            if("ACTIVE".equals(state))sql+=" AND s.state<>'CLOSED'";else if(state!=null){sql+=" AND s.state=?";args.add(state);}
            if(contextType!=null){sql+=" AND s.context_type=? AND s.context_id=?";args.add(contextType);args.add(contextId);}sql+=" ORDER BY s.question_id LIMIT ?";
        }
        args.add(limit+1);List<Map<String,Object>> rows=repo.readRows(modern,true,sql,args.toArray());boolean more=rows.size()>limit;
        List<Map<String,Object>> out=new ArrayList<>();long last=after;
        for(Map<String,Object> row:rows.subList(0,Math.min(limit,rows.size()))){last=id(row.get("question_id"));if(!flag(row,"ordinary_allowed"))continue;identity(row);out.add(card(row,legacy));}
        Map<String,Object> result=map("items",out,"hasMore",more,"nextCursor",more?next(actor,patient,lane,last):null,"lane",legacy?"LEGACY":"MANAGED","availability",availability.name(),"previews",true);
        encoded(result,VisitQuestionContracts.RESPONSE_BYTES);auth.read(actor,patient);return result;
    }
    private static Map<String,Object> card(Map<String,Object> row,boolean legacy){
        Map<String,Object> out=map("questionId",row.get("question_id"),"patientId",row.get("patient_id"),"title",row.get("title"),"description",row.get("description"),"recordedAnswer",row.get("answer"),"followUp",row.get("follow_up"),"managed",!legacy,"legacyMark",row.get("legacy_status"),"answerOrigin",legacy?"LEGACY_UNVERIFIED":row.get("ordinary_answer_origin"));
        if(!legacy){out.putAll(map("state",row.get("state"),"version",row.get("lock_version"),"answerRecorderId",row.get("ordinary_answer_recorder_id"),"answerRecordedAt",row.get("ordinary_answer_recorded_at"),"contextType",row.get("context_type"),"contextId",row.get("context_id"),"responsibleUserId",row.get("responsible_user_id"),"followUpDueDate",row.get("follow_up_due_date"),"professionalAnswerAvailable",row.get("current_answer_event_id")!=null));}return out;
    }
    public Map<String,Object> legacyDetail(long actor,long patient,long question){
        VisitQuestionContracts.id(question,"questionId");access(actor,patient);VisitQuestionSchema.Availability availability=availability();
        if(availability==VisitQuestionSchema.Availability.INCOMPATIBLE)throw unavailable(availability);
        boolean modern=availability==VisitQuestionSchema.Availability.READY,b=bridge.storageAvailable();VisitQuestionSchema.Status storage=repo.storage();
        if(storage==VisitQuestionSchema.Status.INCOMPATIBLE)throw unavailable(VisitQuestionSchema.Availability.INCOMPATIBLE);
        String scalar="COALESCE(OCTET_LENGTH(n.title),0)";for(String field:new String[]{"description","answer","followUp"})scalar+="+COALESCE(OCTET_LENGTH("+field("n",field)+"),0)";
        String where=" FROM care_item n WHERE n.id=? AND n.patient_id=? AND n.kind='QUESTION'"+(storage==VisitQuestionSchema.Status.STRUCTURALLY_READY?" AND NOT EXISTS(SELECT 1 FROM visit_question_state s WHERE s.question_id=n.id)":"");
        Map<String,Object> size=single(repo.readRows(modern,true,"SELECT "+scalar+" AS bytes,"+flags(false,b)+where,question,patient));identity(size);bytes(size.get("bytes"),VisitQuestionContracts.RAW_BYTES);
        Map<String,Object> row=single(repo.readRows(modern,true,"SELECT n.id AS question_id,n.patient_id,n.title,n.status AS legacy_status,"+field("n","description")+" AS description,"+field("n","answer")+" AS answer,"+field("n","followUp")+" AS follow_up,CASE WHEN JSON_TYPE(JSON_EXTRACT("+jsonObject("n")+",'$.appointmentId'))='INTEGER' THEN JSON_EXTRACT("+jsonObject("n")+",'$.appointmentId') ELSE NULL END AS legacy_appointment_id,"+(b?"n.family_assistance_id":"NULL")+" AS assistance_id,"+flags(false,b)+where+" AND "+scalar+"<=?",question,patient,VisitQuestionContracts.RAW_BYTES));
        identity(row);Map<String,Object> out=card(row,true);if(row.get("legacy_appointment_id")!=null)out.put("legacyAppointmentId",VisitQuestionContracts.id(Long.valueOf(row.get("legacy_appointment_id").toString()),"appointmentId"));out.put("familyAssistanceId",row.get("assistance_id"));out.put("legacyEditable",row.get("assistance_id")==null);out.put("availability",availability.name());out.put("previews",false);encoded(out,VisitQuestionContracts.DETAIL_BYTES);auth.read(actor,patient);return out;
    }
    /** 精确来源验证只读身份、权限排除标志和长度，不分配旧 JSON 正文。 */
    Map<String,Object> managedMetadata(long actor,long patient,long question){
        VisitQuestionContracts.id(question,"questionId");ready(actor,patient);boolean b=bridge.storageAvailable();
        String sql="SELECT s.question_id,s.patient_id,s.state,s.lock_version,s.created_by,s.responsible_user_id,s.current_answer_event_id,"+CURRENT_BYTES+" AS scalar_bytes,n.status AS legacy_status,"+(b?"n.family_assistance_id":"NULL")+" AS assistance_id,"+flags(true,b)+" FROM visit_question_state s LEFT JOIN care_item n ON n.id=s.question_id AND n.patient_id=s.patient_id AND n.kind='QUESTION' WHERE s.question_id=? AND s.patient_id=?";
        Map<String,Object> row=single(repo.readRows(true,true,sql,question,patient));identity(row);bytes(row.get("scalar_bytes"),VisitQuestionContracts.RAW_BYTES);auth.read(actor,patient);return row;
    }
    public Map<String,Object> detail(long actor,long patient,long question){
        Map<String,Object> metadata=managedMetadata(actor,patient,question);
        Map<String,Object> row=single(repo.readRows(true,false,"SELECT s.question_id,s.patient_id,s.state,s.lock_version,s.current_title AS title,s.current_description AS description,s.current_recorded_answer AS answer,s.current_follow_up AS follow_up,s.ordinary_answer_origin,s.ordinary_answer_recorder_id,s.ordinary_answer_recorded_at,s.legacy_appointment_id,s.context_type,s.context_id,s.context_fingerprint,s.responsible_user_id,s.follow_up_due_date,s.current_answer_event_id,s.close_reason,s.created_by,s.created_at,s.updated_at FROM visit_question_state s WHERE s.question_id=? AND s.patient_id=? AND s.lock_version=? AND "+CURRENT_BYTES+"<=?",question,patient,metadata.get("lock_version"),VisitQuestionContracts.RAW_BYTES));
        if(row==null)throw VisitQuestionRepository.conflict("QUESTION_STATE_CHANGED");Map<String,Object> out=card(row,false);
        out.putAll(map("legacyAppointmentId",row.get("legacy_appointment_id"),"contextFingerprint",row.get("context_fingerprint"),"closeReason",row.get("close_reason"),"createdBy",row.get("created_by"),"createdAt",row.get("created_at"),"updatedAt",row.get("updated_at"),"legacyMark",metadata.get("legacy_status"),"familyAssistanceId",metadata.get("assistance_id"),"familyAssistanceSeparate",metadata.get("assistance_id")!=null));
        if(row.get("current_answer_event_id")!=null){Map<String,Object> event=eventOrdinary(patient,question,id(row.get("current_answer_event_id")));if(!"PUBLISH_ANSWER".equals(event.get("type"))||!"CLINICIAN_AUTHORED".equals(event.get("answerOrigin")))throw VisitQuestionRepository.conflict("QUESTION_EVENT_INVALID");out.put("professionalAnswer",event);}
        out.put("allowedCommands",commands(actor,patient,metadata));encoded(out,VisitQuestionContracts.DETAIL_BYTES);managedMetadata(actor,patient,question);auth.read(actor,patient);return out;
    }
    private List<String> commands(long actor,long patient,Map<String,Object> state){
        List<String> result=new ArrayList<>();if(!allowed(()->auth.write(actor,patient)))return result;
        String current=(String)state.get("state");
        if(Arrays.asList("OPEN","UNRESOLVED").contains(current))result.add("EDIT");
        if(Arrays.asList("OPEN","UNRESOLVED","FOLLOW_UP").contains(current)){result.add("RECORD_ANSWER");if(allowed(()->auth.publish(actor,patient)))result.add("PUBLISH_ANSWER");}
        if(Arrays.asList("OPEN","ANSWERED","FOLLOW_UP").contains(current))result.add("MARK_UNRESOLVED");
        if(Arrays.asList("OPEN","UNRESOLVED","ANSWERED").contains(current))result.add("SET_FOLLOW_UP");
        if(allowed(()->auth.administer(actor,patient,state))){if(!"CLOSED".equals(current))result.add("CLOSE");if(!"OPEN".equals(current))result.add("REOPEN");}
        auth.write(actor,patient);return result;
    }
    public Map<String,Object> events(long actor,long patient,long question,String cursor,Integer requested){
        int limit=pageSize(requested);VisitQuestionContracts.id(question,"questionId");String lane="EVENTS:"+question;long after=cursor(cursor,actor,patient,lane);managedMetadata(actor,patient,question);
        List<Map<String,Object>> metadata=repo.readRows(true,false,"SELECT id,version,OCTET_LENGTH(body_json) AS body_bytes,COALESCE(OCTET_LENGTH("+ORDINARY_EVENT+"),0) AS ordinary_bytes,CASE WHEN JSON_EXTRACT(body_json,'$.baseline') IS NOT NULL THEN 1 ELSE 0 END AS baseline_available FROM visit_question_event WHERE patient_id=? AND question_id=? AND id>? ORDER BY id LIMIT ?",patient,question,after,limit+1);
        boolean more=metadata.size()>limit;List<Long> ids=new ArrayList<>();long budget=0;Map<Long,Map<String,Object>> sizes=new HashMap<>();
        for(Map<String,Object> row:metadata.subList(0,Math.min(limit,metadata.size()))){bytes(row.get("ordinary_bytes"),VisitQuestionContracts.ORDINARY_BYTES);bytes(row.get("body_bytes"),VisitQuestionContracts.EVENT_BYTES);budget+=((Number)row.get("ordinary_bytes")).longValue()+2048;long event=id(row.get("id"));ids.add(event);sizes.put(event,row);}
        if(budget>VisitQuestionContracts.EVENT_PAGE_BYTES)throw limit();List<Map<String,Object>> items=new ArrayList<>();
        if(!ids.isEmpty()){
            List<Object> args=new ArrayList<>();args.add(patient);args.add(question);args.addAll(ids);args.add(VisitQuestionContracts.ORDINARY_BYTES);
            for(Map<String,Object> row:repo.readRows(true,false,eventSelect()+" WHERE patient_id=? AND question_id=? AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND OCTET_LENGTH("+ORDINARY_EVENT+")<=? ORDER BY id",args.toArray())){Map<String,Object> item=event(row);Map<String,Object> size=sizes.get(id(row.get("id")));item.put("baselineAvailable",flag(size,"baseline_available"));item.put("encodedBodyBytes",size.get("body_bytes"));items.add(item);}
            if(items.size()!=ids.size())throw VisitQuestionRepository.conflict("QUESTION_EVENT_INVALID");
        }
        Map<String,Object> out=map("items",items,"hasMore",more,"nextCursor",more?next(actor,patient,lane,ids.get(ids.size()-1)):null);encoded(out,VisitQuestionContracts.EVENT_PAGE_BYTES);managedMetadata(actor,patient,question);auth.read(actor,patient);return out;
    }
    private static String eventSelect(){return "SELECT id,version,event_type,from_state,to_state,actor_id,actor_kind,answer_origin,reason_code,recorded_at,"+ORDINARY_EVENT+" AS ordinary_json FROM visit_question_event";}
    private static Map<String,Object> event(Map<String,Object> row){String body=(String)row.get("ordinary_json");VisitQuestionContracts.requireBytes(body,VisitQuestionContracts.ORDINARY_BYTES,"ordinary event");return map("eventId",row.get("id"),"version",row.get("version"),"type",row.get("event_type"),"fromState",row.get("from_state"),"toState",row.get("to_state"),"actorId",row.get("actor_id"),"actorKind",row.get("actor_kind"),"answerOrigin",row.get("answer_origin"),"reason",row.get("reason_code"),"recordedAt",row.get("recorded_at"),"ordinary",VisitQuestionContracts.parse(body));}
    private Map<String,Object> eventOrdinary(long patient,long question,long event){
        Map<String,Object> size=single(repo.readRows(true,false,"SELECT OCTET_LENGTH("+ORDINARY_EVENT+") AS bytes FROM visit_question_event WHERE id=? AND question_id=? AND patient_id=?",event,question,patient));if(size==null)throw VisitQuestionRepository.denied();bytes(size.get("bytes"),VisitQuestionContracts.ORDINARY_BYTES);
        Map<String,Object> row=single(repo.readRows(true,false,eventSelect()+" WHERE id=? AND question_id=? AND patient_id=? AND OCTET_LENGTH("+ORDINARY_EVENT+")<=?",event,question,patient,VisitQuestionContracts.ORDINARY_BYTES));if(row==null)throw VisitQuestionRepository.conflict("QUESTION_EVENT_INVALID");return event(row);
    }
    public Map<String,Object> eventDetail(long actor,long patient,long question,long event){
        VisitQuestionContracts.id(event,"eventId");managedMetadata(actor,patient,question);
        Map<String,Object> size=single(repo.readRows(true,false,"SELECT OCTET_LENGTH(body_json) AS bytes FROM visit_question_event WHERE id=? AND question_id=? AND patient_id=?",event,question,patient));if(size==null)throw VisitQuestionRepository.denied();bytes(size.get("bytes"),VisitQuestionContracts.EVENT_BYTES);
        Map<String,Object> row=single(repo.readRows(true,false,"SELECT id,version,event_type,actor_id,actor_kind,answer_origin,recorded_at,body_json FROM visit_question_event WHERE id=? AND question_id=? AND patient_id=? AND OCTET_LENGTH(body_json)<=?",event,question,patient,VisitQuestionContracts.EVENT_BYTES));if(row==null)throw VisitQuestionRepository.conflict("QUESTION_EVENT_INVALID");
        String body=(String)row.remove("body_json");VisitQuestionContracts.requireBytes(body,VisitQuestionContracts.EVENT_BYTES,"event");Map<String,Object> out=map("eventId",event,"questionId",question,"patientId",patient,"metadata",row,"body",VisitQuestionContracts.parse(body));encoded(out,VisitQuestionContracts.RESPONSE_BYTES);managedMetadata(actor,patient,question);auth.read(actor,patient);return out;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> receipt(long actor,long patient,String key){
        ready(actor,patient);auth.write(actor,patient);key=WorkflowCommandExecutor.validateKey(key);
        Map<String,Object> row=repo.replay(actor,key);if(row==null){auth.write(actor,patient);return null;}if(row.get("result_json")==null)throw VisitQuestionRepository.conflict("QUESTION_RECEIPT_INCOMPLETE");
        Map<String,Object> receipt=VisitQuestionContracts.receipt(VisitQuestionContracts.parse((String)row.get("result_json")));long question=id(receipt.get("questionId"));if(id(row.get("question_id"))!=question)throw VisitQuestionRepository.conflict("QUESTION_RECEIPT_INVALID");
        Map<String,Object> state=managedMetadata(actor,patient,question),event=eventOrdinary(patient,question,id(receipt.get("eventId")));
        if(id(event.get("version"))!=id(receipt.get("version"))||!Objects.equals(event.get("toState"),receipt.get("state")))throw VisitQuestionRepository.conflict("QUESTION_RECEIPT_INVALID");
        String operation=(String)event.get("type");Map<String,Object> body=(Map<String,Object>)((Map<String,Object>)event.get("ordinary")).get("body");
        if("PUBLISH_ANSWER".equals(operation))auth.publish(actor,patient);
        if("CLOSE".equals(operation)||"REOPEN".equals(operation))auth.administer(actor,patient,state);
        if("SET_FOLLOW_UP".equals(operation)){
            Long current=optionalResponsibleId(state.get("responsible_user_id"));
            Long proposed=body.containsKey("responsibleUserId")?optionalResponsibleId(body.get("responsibleUserId")):current;
            // JSON 整数和 JDBC BIGINT 先统一身份类型，保持命令与回执的同一改派规则。
            if(VisitQuestionService.nonblank(body.get("reasonNote"))||!Objects.equals(proposed,current))auth.ownerWrite(actor,patient);
            auth.assignee(proposed,patient);
        }
        auth.write(actor,patient);auth.read(actor,patient);return receipt;
    }
    private static Long optionalResponsibleId(Object value){return value==null?null:VisitQuestionContracts.id(value,"responsibleUserId");}
    static Map<String,Object> single(List<Map<String,Object>> rows){if(rows.size()>1)throw VisitQuestionRepository.conflict("QUESTION_IDENTITY_INVALID");return rows.isEmpty()?null:rows.get(0);}
}
