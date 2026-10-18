package org.familyhealthcare.service.visitquestion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.visitquestion.VisitQuestionReadSupport.*;

/** 预约与已发布就诊仅提供有限定位信息，不复制诊断、处方或问诊正文。 */
@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class VisitQuestionContextService {
    private final VisitQuestionQueryService questions;
    private final VisitQuestionRepository repo;
    private final VisitQuestionAuthorizationService auth;
    public VisitQuestionContextService(VisitQuestionQueryService questions,VisitQuestionRepository repo,VisitQuestionAuthorizationService auth){this.questions=questions;this.repo=repo;this.auth=auth;}
    private static String fields(String type){
        if(type==null)throw invalid("不支持此问题关联类型。");
        switch(type){
            case "LEGACY_APPOINTMENT":return "id,patient_id,status,event_at,updated_at";
            case "CARE_APPOINTMENT":return "id,patient_id,status,doctor_user_id,start_at,end_at,updated_at";
            case "VISIT_RECORD":return "id,patient_id,status,doctor_user_id,appointment_id,visited_at,published_at,updated_at";
            default:throw invalid("不支持此问题关联类型。");
        }
    }
    private static String table(String type){fields(type);return "LEGACY_APPOINTMENT".equals(type)?"care_item":"CARE_APPOINTMENT".equals(type)?"care_appointment":"visit_record";}
    private static String date(String type){return "LEGACY_APPOINTMENT".equals(type)?"event_at":"CARE_APPOINTMENT".equals(type)?"start_at":"visited_at";}
    private static String predicate(String type){return ("LEGACY_APPOINTMENT".equals(type)?" AND kind='APPOINTMENT' AND "+ordinarySource("n"):"")+("VISIT_RECORD".equals(type)?" AND status='PUBLISHED'":" AND status<>'CANCELLED'");}
    public Map<String,Object> options(long actor,long patient,String type,String cursor,Integer requested){
        int limit=VisitQuestionQueryService.pageSize(requested);String table=table(type);questions.ready(actor,patient);
        LocalDate from=LocalDate.now().minusDays(90),to=LocalDate.now().plusDays(181);String lane="CONTEXT:"+type+":"+from+":"+to;long after=cursor(cursor,actor,patient,lane);
        String where=" FROM "+table+" n WHERE patient_id=? AND id>? AND "+date(type)+">=? AND "+date(type)+"<?"+predicate(type);
        List<Map<String,Object>> rows=repo.readRows(true,"LEGACY_APPOINTMENT".equals(type),"SELECT "+fields(type)+where+" ORDER BY id LIMIT ?",patient,after,from.toString(),to.toString(),limit);
        List<Map<String,Object>> items=new ArrayList<>();for(Map<String,Object> row:rows)items.add(context(type,row));
        long last=rows.isEmpty()?after:id(rows.get(rows.size()-1).get("id"));
        Map<String,Object> continuation=VisitQuestionQueryService.single(repo.readRows(true,"LEGACY_APPOINTMENT".equals(type),"SELECT EXISTS(SELECT 1"+where+" LIMIT 1) AS more",patient,last,from.toString(),to.toString()));
        boolean more=continuation!=null&&flag(continuation,"more");Map<String,Object> out=map("items",items,"hasMore",more,"nextCursor",more?next(actor,patient,lane,last):null,"fromDate",from.toString(),"toDate",to.minusDays(1).toString());encoded(out,VisitQuestionContracts.RESPONSE_BYTES);auth.read(actor,patient);return out;
    }
    public Map<String,Object> exact(long actor,long patient,String type,long context){
        VisitQuestionContracts.id(context,"contextId");String table=table(type);questions.ready(actor,patient);
        Map<String,Object> row=VisitQuestionQueryService.single(repo.readRows(true,"LEGACY_APPOINTMENT".equals(type),"SELECT "+fields(type)+" FROM "+table+" n WHERE id=? AND patient_id=?"+predicate(type),context,patient));
        if(row==null)throw VisitQuestionRepository.conflict("QUESTION_CONTEXT_CHANGED");Map<String,Object> out=context(type,row);auth.read(actor,patient);return out;
    }
    private static Map<String,Object> context(String type,Map<String,Object> row){return map("type",type,"id",row.get("id"),"fingerprint",hash(json(map("type",type,"source",row))),"status",row.get("status"),"date",row.get(date(type)),"doctorUserId",row.get("doctor_user_id"));}
}
