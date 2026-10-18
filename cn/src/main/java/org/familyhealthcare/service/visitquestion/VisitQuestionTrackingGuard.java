package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.service.familyassistance.*;
import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.visitquestion.VisitQuestionRepository.*;

/** 患者父锁→完整有序身份锁→原生/B→Q；等待后重新核验当前权限。 */
@Service
public class VisitQuestionTrackingGuard {
    private final VisitQuestionRepository repo;
    private final HouseholdAuthorityLocks locks;
    private final FamilyAssistanceRepository assistance;
    private final FamilyAssistanceSourceBridge bridge;
    private final VisitQuestionAuthorizationService auth;
    public VisitQuestionTrackingGuard(VisitQuestionRepository repo,HouseholdAuthorityLocks locks,FamilyAssistanceRepository assistance,
                                     FamilyAssistanceSourceBridge bridge,VisitQuestionAuthorizationService auth) {
        this.repo=repo;this.locks=locks;this.assistance=assistance;this.bridge=bridge;this.auth=auth;
    }
    public static final class Admission {
        public final Map<String,Object> source,state;
        public final Long question;
        public final boolean bPresent,bOwned;
        final HouseholdAuthorityLocks.LockedAuthority authority;
        Admission(Long question,Map<String,Object> source,Map<String,Object> state,boolean bPresent,HouseholdAuthorityLocks.LockedAuthority authority){
            this.question=question;this.source=source;this.state=state;this.bPresent=bPresent;this.authority=authority;
            this.bOwned=source!=null&&source.get("family_assistance_id")!=null;
        }
    }
    public Admission command(long actor,long patient,Long requested,String key,Map<String,Object> envelope) {
        repo.parent(patient);
        Map<String,Object> replay=repo.replay(actor,key);
        Long question=requested;
        if(replay!=null){
            if(!hash(json(envelope)).equals(replay.get("payload_hash")))throw conflict("COMMAND_CONFLICT");
            if(replay.get("question_id")==null||replay.get("result_json")==null)throw conflict("QUESTION_RECEIPT_INCOMPLETE");
            Map<String,Object> receipt=VisitQuestionContracts.receipt(VisitQuestionContracts.parse(text(replay,"result_json")));
            question=VisitQuestionContracts.id(replay.get("question_id"),"questionId");
            if(!question.equals(receipt.get("questionId"))||requested!=null&&!requested.equals(question))throw conflict("COMMAND_CONFLICT");
        }
        Map<String,Object> discovered=question==null?null:repo.discover(question);
        if(discovered!=null&&id(discovered.get("patient_id"))!=patient)throw denied();
        SortedSet<Long> users=new TreeSet<>();users.add(actor);users.add(auth.owner(patient));
        add(users,discovered,"created_by");add(users,discovered,"responsible_user_id");
        @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)envelope.get("body");
        add(users,body,"responsibleUserId");
        if(users.size()>50)throw conflict("ADMISSION_LIMIT");
        HouseholdAuthorityLocks.LockedAuthority authority=question==null?locks.lockPatientAndSubjects(patient,users):assistance.admitQuestion(actor,patient,question,users);
        auth.write(actor,patient);
        if(!Objects.equals(discovered,question==null?null:repo.discover(question)))throw conflict("ADMISSION_CONTEXT_CHANGED");
        Map<String,Object> source=question==null?null:repo.source(question,patient,true,true);
        lockContexts(patient,discovered,body,source);
        auth.write(actor,patient);
        Map<String,Object> state=question==null?null:repo.state(question,patient);
        if(source!=null){ownership(source,state);if(state==null)VisitQuestionContracts.eventBody(1,envelope,repo.baseline(source));}
        locks.requireLive(authority,patient,actor);
        return new Admission(question,source,state,true,authority);
    }
    /** 手工兼容不等待 Q 命令锁；存在任何接管标记时都不能降级绕过。 */
    public Admission manual(long actor,long patient,Long question,Long assignee) {
        repo.parent(patient);
        VisitQuestionSchema.Status storage=repo.storage();
        if(storage==VisitQuestionSchema.Status.INCOMPATIBLE)throw new WorkflowException(503,"QUESTION_SCHEMA_NOT_READY","Question ownership cannot be verified. Retry after storage is available.");
        boolean bPresent=bridge.storageAvailable();
        Map<String,Object> discovered=question==null||storage==VisitQuestionSchema.Status.ABSENT?null:repo.plainOne("SELECT question_id,patient_id,created_by,responsible_user_id,lock_version FROM visit_question_state WHERE question_id=?",question);
        if(discovered!=null)throw conflict("QUESTION_REFRESH_REQUIRED");
        SortedSet<Long> users=new TreeSet<>();users.add(actor);if(assignee!=null)users.add(assignee);
        Map<String,Object> patientRow=repo.plainOne("SELECT user_id FROM patient WHERE id=?",patient);users.add(id(patientRow.get("user_id")));
        HouseholdAuthorityLocks.LockedAuthority authority=locks.lockPatientAndSubjects(patient,users);
        Map<String,Object> source=question==null?null:repo.source(question,patient,false,bPresent);
        if(source!=null){
            Map<String,Object> details=repo.details(source);
            if(details.containsKey("questionWorkflowVersion")||source.get("family_assistance_id")!=null||id(source.get("family_workflow_version"))!=0)throw conflict("QUESTION_REFRESH_REQUIRED");
            if(storage!=VisitQuestionSchema.Status.ABSENT&&repo.plainOne("SELECT question_id FROM visit_question_state WHERE question_id=?",question)!=null)throw conflict("QUESTION_REFRESH_REQUIRED");
        }
        auth.manual(actor,patient);auth.assignee(assignee,patient);locks.requireLive(authority,patient,actor);
        return new Admission(question,source,null,bPresent,authority);
    }
    void recheck(long actor,long patient,Admission admission,String operation,Map<String,Object> body){
        locks.requireLive(admission.authority,patient,actor);
        if("PUBLISH_ANSWER".equals(operation))auth.publish(actor,patient);else auth.write(actor,patient);
        if("CLOSE".equals(operation)||"REOPEN".equals(operation))auth.administer(actor,patient,admission.state);
        if("SET_FOLLOW_UP".equals(operation)){
            Long current=admission.state==null?auth.qualifiedOwner(patient):optionalId(admission.state.get("responsible_user_id"));
            Long next=body.containsKey("responsibleUserId")?optionalId(body.get("responsibleUserId")):current;
            boolean reason=VisitQuestionService.nonblank(body.get("reasonNote"));
            // 原因文字明确表示改派意图；即使重放时目标已相同，也必须重验当前所有者。
            if(!Objects.equals(current,next)||reason){
                auth.ownerWrite(actor,patient);
                if(!reason)throw WorkflowException.invalid("Record a reason for changing the responsible person.");
            }
            auth.assignee(next,patient);
        }
    }
    private static Long optionalId(Object value){return value==null?null:VisitQuestionContracts.id(value,"responsibleUserId");}
    private void ownership(Map<String,Object> source,Map<String,Object> state){
        Map<String,Object> details=repo.details(source);boolean marked=details.containsKey("questionWorkflowVersion");
        if(marked&&(!(details.get("questionWorkflowVersion") instanceof Number)||id(details.get("questionWorkflowVersion"))!=1||!"1".equals(details.get("questionWorkflowVersion").toString())))throw conflict("QUESTION_OWNERSHIP_INVALID");
        boolean b=source.get("family_assistance_id")!=null;
        if((marked&&state==null)||(state!=null&&!marked&&!b))throw conflict("QUESTION_OWNERSHIP_INVALID");
        if(state!=null&&!Objects.equals(source.get("patient_id"),state.get("patient_id")))throw denied();
    }
    @SuppressWarnings("unchecked") private void lockContexts(long patient,Map<String,Object> discovered,Map<String,Object> body,Map<String,Object> source){
        SortedMap<String,SortedSet<Long>> selected=new TreeMap<>();
        if(discovered!=null){contextId(selected,(String)discovered.get("context_type"),discovered.get("context_id"));contextId(selected,"LEGACY_APPOINTMENT",discovered.get("legacy_appointment_id"));}
        if(source!=null)contextId(selected,"LEGACY_APPOINTMENT",repo.details(source).get("appointmentId"));
        contextId(selected,"LEGACY_APPOINTMENT",body.get("legacyAppointmentId"));
        for(String key:Arrays.asList("context","resultReference"))if(body.get(key) instanceof Map){Map<String,Object> value=(Map<String,Object>)body.get(key);if(!"QUESTION_EVENT".equals(value.get("type")))contextId(selected,(String)value.get("type"),value.get("id"));}
        if(body.get("resultReference") instanceof Map){
            Map<String,Object> reference=(Map<String,Object>)body.get("resultReference");
            if("QUESTION_EVENT".equals(reference.get("type"))){
                Map<String,Object> event=repo.one("SELECT question_id,patient_id FROM visit_question_event WHERE id=? AND patient_id=?",reference.get("id"),patient);
                if(event==null)throw conflict("QUESTION_RESULT_REQUIRED");
                selected.computeIfAbsent("care_item",k->new TreeSet<>()).add(VisitQuestionContracts.id(event.get("question_id"),"questionId"));
            }
        }
        for(Map.Entry<String,SortedSet<Long>> entry:selected.entrySet())for(Long id:entry.getValue())
            repo.one("SELECT id FROM "+entry.getKey()+" WHERE id=? AND patient_id=? FOR UPDATE",id,patient);
    }
    private static void contextId(Map<String,SortedSet<Long>> selected,String type,Object value){
        if(value==null)return;
        String table="LEGACY_APPOINTMENT".equals(type)?"care_item":"CARE_APPOINTMENT".equals(type)?"care_appointment":"VISIT_RECORD".equals(type)?"visit_record":null;
        if(table==null)throw conflict("QUESTION_CONTEXT_CHANGED");selected.computeIfAbsent(table,k->new TreeSet<>()).add(VisitQuestionContracts.id(value,"contextId"));
    }
    private static void add(Set<Long> users,Map<String,Object> row,String field){if(row!=null&&row.get(field)!=null)users.add(VisitQuestionContracts.id(row.get(field),field));}
}
