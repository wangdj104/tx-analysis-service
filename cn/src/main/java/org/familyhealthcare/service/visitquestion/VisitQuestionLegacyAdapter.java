package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.entity.CareItem;
import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.visitquestion.VisitQuestionRepository.*;

/** 旧入口仅接收和返回普通文字；已跟踪的问题必须使用明确的带版本命令。 */
@Service
public class VisitQuestionLegacyAdapter {
    private final VisitQuestionRepository repo;
    private final VisitQuestionAuthorizationService auth;
    private final VisitQuestionTrackingGuard guard;
    private final VisitQuestionService service;
    public VisitQuestionLegacyAdapter(VisitQuestionRepository repo,VisitQuestionAuthorizationService auth,VisitQuestionTrackingGuard guard,VisitQuestionService service){this.repo=repo;this.auth=auth;this.guard=guard;this.service=service;}
    public boolean isQuestion(CareItem input){
        if("QUESTION".equals(input.getKind()))return true;
        return input.getId()!=null&&isQuestion(input.getId());
    }
    public boolean isQuestion(long id){Map<String,Object> row=repo.plainOne("SELECT kind FROM care_item WHERE id=?",id);return row!=null&&"QUESTION".equals(row.get("kind"));}
    @SuppressWarnings("unchecked") public CareItem save(long actor,CareItem input){
        long patient=VisitQuestionContracts.id(input.getPatientId(),"patientId");
        if(!"QUESTION".equals(input.getKind()))throw conflict("QUESTION_IDENTITY_INVALID");
        Map<String,Object> proposed=input.getDetails();
        Set<String> permitted=new HashSet<>(Arrays.asList("description","answer","followUp","appointmentId","commandKey","questionCommand"));
        if(!permitted.containsAll(proposed.keySet()))throw WorkflowException.invalid("Unsupported question fields. Professional attribution cannot be supplied through an ordinary note.");
        if(proposed.containsKey("questionCommand")){
            if(!(proposed.get("questionCommand") instanceof Map)||!(proposed.get("commandKey") instanceof String))throw WorkflowException.invalid("An explicit question command and key are required.");
            Map<String,Object> command=(Map<String,Object>)proposed.get("questionCommand");
            if(!Arrays.asList("CREATE","ADOPT","EDIT","RECORD_ANSWER").contains(command.get("operation"))||VisitQuestionContracts.id(command.get("patientId"),"patientId")!=patient)throw WorkflowException.invalid("Use the dedicated question action for this operation.");
            Map<String,Object> receipt=service.command(actor,input.getId(),(String)proposed.get("commandKey"),json(command));
            return managed(actor,patient,id(receipt.get("questionId")));
        }
        if(proposed.containsKey("commandKey"))throw WorkflowException.invalid("The versioned question command is missing.");
        VisitQuestionTrackingGuard.Admission admission=guard.manual(actor,patient,input.getId(),input.getAssignedUserId());
        Map<String,Object> old=admission.source==null?Collections.emptyMap():repo.details(admission.source);
        textField(input.getTitle(),200,true);
        for(String field:Arrays.asList("description","answer","followUp"))if(proposed.containsKey(field)&&!Objects.equals(old.get(field),proposed.get(field)))textField(proposed.get(field),"followUp".equals(field)?2000:4000,false);
        Object appointment=proposed.containsKey("appointmentId")?proposed.get("appointmentId"):old.get("appointmentId");
        if(appointment!=null&&!"".equals(appointment)){
            long id=VisitQuestionContracts.id(appointment,"appointmentId");
            Map<String,Object> row=repo.plainOne("SELECT id,kind,patient_id,CASE WHEN OCTET_LENGTH(data_json)<=131072 THEN data_json ELSE NULL END AS data_json,OCTET_LENGTH(data_json) AS bytes FROM care_item WHERE id=? AND patient_id=? FOR UPDATE",id,patient);
            if(row==null||!"APPOINTMENT".equals(row.get("kind")))throw conflict("QUESTION_CONTEXT_CHANGED");if(row.get("bytes")!=null)bound(row.get("bytes"),VisitQuestionContracts.RAW_BYTES);repo.details(row);appointment=id;
        }else appointment=null;
        Map<String,Object> current=map("current_title",input.getTitle(),"current_description",proposed.containsKey("description")?proposed.get("description"):old.get("description"),
                "current_recorded_answer",proposed.containsKey("answer")?proposed.get("answer"):old.get("answer"),"current_follow_up",proposed.containsKey("followUp")?proposed.get("followUp"):old.get("followUp"),"legacy_appointment_id",appointment);
        VisitQuestionContracts.requireRawScalars(text(current,"current_title"),text(current,"current_description"),text(current,"current_recorded_answer"),text(current,"current_follow_up"));
        long question;
        if(admission.source==null){
            Map<String,Object> ordinary=new LinkedHashMap<>();copyOrdinary(current,ordinary);
            Map<String,Object> owner=repo.plainOne("SELECT user_id FROM patient WHERE id=?",patient);
            question=repo.createNative(patient,id(owner.get("user_id")),actor,input.getTitle(),json(ordinary),Instant.now(),false);
        }else{question=input.getId();repo.mirror(admission.source,current,false,admission.bPresent);}
        auth.manual(actor,patient);
        return ordinary(repo.source(question,patient,false,admission.bPresent));
    }
    public CareItem action(long actor,long question,String status,String note,Map<String,String> input){
        long patient=patient(question);
        if(input.get("commandKey")!=null||input.get("expectedVersion")!=null){
            String operation;Map<String,Object> body=new LinkedHashMap<>();
            if("ANSWERED".equals(status)){operation="RECORD_ANSWER";body.put("answer",note);}
            else if("OPEN".equals(status)){operation="REOPEN";body.put("reason",input.get("reason"));body.put("state","OPEN");if(note!=null)body.put("reasonNote",note);}
            else if(Arrays.asList("DONE","RESOLVED","CANCELLED").contains(status)){
                operation="CLOSE";body.put("reason","CANCELLED".equals(status)?"WITHDRAWN":input.get("reason"));
                if(note!=null)body.put("reasonNote",note);if(input.get("resultText")!=null)body.put("resultText",input.get("resultText"));
            }else throw WorkflowException.invalid("Unsupported question status.");
            run(actor,patient,question,input,operation,body);return managed(actor,patient,question);
        }
        VisitQuestionTrackingGuard.Admission admission=guard.manual(actor,patient,question,null);
        if(!Arrays.asList("OPEN","ANSWERED","DONE","RESOLVED","CANCELLED").contains(status))throw WorkflowException.invalid("Unsupported question status.");
        if("ANSWERED".equals(status)&&!VisitQuestionService.nonblank(note))throw WorkflowException.invalid("A recorded answer is required.");
        if("OPEN".equals(status)&&!VisitQuestionService.nonblank(note))throw WorkflowException.invalid("Record a reason for reopening this earlier question.");
        if(note!=null)textField(note,4000,false);
        Map<String,Object> details=repo.details(admission.source);if(note!=null)details.put("answer",note);
        String json=json(details);VisitQuestionContracts.requireRawScalars(text(admission.source,"title"),json);
        if(repo.update(false,"UPDATE care_item SET status=?,data_json=? WHERE id=? AND patient_id=?"+(admission.bPresent?" AND family_workflow_version=0 AND family_assistance_id IS NULL":""),status,json,question,patient)!=1)throw conflict("QUESTION_SOURCE_CHANGED");
        auth.manual(actor,patient);return ordinary(repo.source(question,patient,false,admission.bPresent));
    }
    public boolean delete(long actor,long question,Map<String,String> input){
        long patient=patient(question);
        if(input.get("commandKey")!=null||input.get("expectedVersion")!=null){
            Map<String,Object> body=map("reason","WITHDRAWN");if(input.get("reasonNote")!=null)body.put("reasonNote",input.get("reasonNote"));
            run(actor,patient,question,input,"CLOSE",body);return true;
        }
        VisitQuestionTrackingGuard.Admission admission=guard.manual(actor,patient,question,null);auth.manual(actor,patient);
        if(repo.update(false,"DELETE FROM care_item WHERE id=? AND patient_id=?"+(admission.bPresent?" AND family_workflow_version=0 AND family_assistance_id IS NULL":""),question,patient)!=1)throw conflict("QUESTION_SOURCE_CHANGED");return false;
    }
    private void run(long actor,long patient,long question,Map<String,String> input,String operation,Map<String,Object> body){
        long version;
        try{String value=input.get("expectedVersion");if(value==null||!value.matches("0|[1-9][0-9]*"))throw new NumberFormatException();version=Long.parseLong(value);}
        catch(NumberFormatException failure){throw WorkflowException.invalid("expectedVersion is required.");}
        service.command(actor,question,input.get("commandKey"),json(map("schemaVersion",1,"patientId",patient,"questionId",question,"expectedVersion",version,"operation",operation,"body",body)));
    }
    private long patient(long question){Map<String,Object> row=repo.plainOne("SELECT patient_id,kind FROM care_item WHERE id=?",question);if(row==null||!"QUESTION".equals(row.get("kind")))throw denied();return id(row.get("patient_id"));}
    private CareItem managed(long actor,long patient,long question){
        auth.read(actor,patient);Map<String,Object> state=repo.state(question,patient);if(state==null)throw conflict("QUESTION_STATE_CHANGED");
        Map<String,Object> metadata=map("questionVersion",state.get("lock_version"),"answerOrigin",state.get("ordinary_answer_origin"),"projectionBasis","CURRENT_ORDINARY_NOTE","preview",false);
        if("USER_RECORDED".equals(state.get("ordinary_answer_origin"))){metadata.put("answerRecorderId",state.get("ordinary_answer_recorder_id"));metadata.put("answerRecordedAt",state.get("ordinary_answer_recorded_at"));}
        CareItem out=new VisitQuestionProjection.OrdinaryNote(metadata);out.setId(question);out.setPatientId(patient);out.setKind("QUESTION");out.setTitle(text(state,"current_title"));out.setStatus(text(state,"state"));
        Map<String,Object> details=new LinkedHashMap<>();copyOrdinary(state,details);out.setDetails(details);auth.read(actor,patient);return out;
    }
    static CareItem ordinary(Map<String,Object> source){
        CareItem out=new VisitQuestionProjection.OrdinaryNote(map("answerOrigin","LEGACY_UNVERIFIED","projectionBasis","LEGACY_UNVERIFIED","preview",false));out.setId(id(source.get("id")));out.setPatientId(id(source.get("patient_id")));out.setKind("QUESTION");out.setTitle(text(source,"title"));out.setStatus(text(source,"status"));
        Map<String,Object> raw=source.get("data_json")==null?Collections.emptyMap():VisitQuestionContracts.parse(text(source,"data_json"));Map<String,Object> details=new LinkedHashMap<>();
        for(String field:Arrays.asList("description","answer","followUp"))if(raw.get(field) instanceof String)details.put(field,raw.get(field));
        Object appointment=raw.get("appointmentId");if(appointment instanceof Number){try{details.put("appointmentId",VisitQuestionContracts.id(appointment,"appointmentId"));}catch(WorkflowException invalid){/* 旧非法关联不升级为可信定位。 */}}
        out.setDetails(details);return out;
    }
    private static void copyOrdinary(Map<String,Object> state,Map<String,Object> target){for(String[] pair:new String[][]{{"description","current_description"},{"answer","current_recorded_answer"},{"followUp","current_follow_up"},{"appointmentId","legacy_appointment_id"}})if(state.get(pair[1])!=null)target.put(pair[0],state.get(pair[1]));}
    private static void textField(Object value,int max,boolean required){if(!(value instanceof String)||VisitQuestionContracts.utf8((String)value)>VisitQuestionContracts.RAW_BYTES||((String)value).codePointCount(0,((String)value).length())>max||required&&!VisitQuestionService.nonblank(value))throw WorkflowException.invalid("Enter question text within the supported field limit.");}
}
