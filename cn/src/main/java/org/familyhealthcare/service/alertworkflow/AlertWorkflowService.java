package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.PatientHealthTarget;
import org.familyhealthcare.service.workflow.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.id;
import static org.familyhealthcare.service.workflow.WorkflowData.json;
import static org.familyhealthcare.service.workflow.WorkflowData.map;
import static org.familyhealthcare.service.alertworkflow.AlertWorkflowContracts.*;

/** Authorized, versioned lifecycle; administrative personal closure never becomes a clinical conclusion. */
@Service
@ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
public class AlertWorkflowService {
    public static final Set<String> OPERATIONS=Collections.unmodifiableSet(set("ASSIGN","ACCEPT","REJECT_ASSIGNMENT","ESCALATE","FOLLOW_UP","SUBMIT","FEEDBACK","RETURN","REVIEW_SOURCE_CHANGE","RESOLVE_REFERENCE_CHANGE","CLOSE","CANCEL","REOPEN"));
    private final JdbcTemplate jdbc;private final AlertWorkflowRepository repo;private final AlertWorkflowAuthorizationService auth;private final AlertWorkflowProperties properties;
    private final AlertWorkflowEventStore events;private final AlertWorkflowNotificationQueue notifications;private final AlertWorkflowEvidenceService evidence;private final MeasurementAssessmentService assessments;
    public AlertWorkflowService(JdbcTemplate jdbc,AlertWorkflowRepository repo,AlertWorkflowAuthorizationService auth,AlertWorkflowProperties properties,AlertWorkflowEventStore events,AlertWorkflowNotificationQueue notifications,AlertWorkflowEvidenceService evidence,MeasurementAssessmentService assessments) {
        this.jdbc=jdbc;this.repo=repo;this.auth=auth;this.properties=properties;this.events=events;this.notifications=Objects.requireNonNull(notifications,"notifications");this.evidence=evidence;this.assessments=assessments;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,long alertId,String operation,Map<String,Object> body,String commandKey,long expectedVersion) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);requireId(alertId,"alertId");requireVersion(expectedVersion);commandKey(commandKey);choice(operation,"operation",OPERATIONS);
        Map<String,Object> normalized=normalize(operation,body);Map<String,Object> initial=repo.readAlert(actor,alertId);authorize(actor,initial,operation,normalized);
        List<Map<String,Object>> requested=requested(normalized);
        AlertWorkflowEvidenceService.Locks locks=evidence.lock(initial,requested,actor);Map<String,Object> alert=repo.lockAlert(alertId);
        if(alert==null||id(alert.get("workflowVersion"))!=1)throw AlertWorkflowException.denied();evidence.verify(locks);authorize(actor,alert,operation,normalized);requireSource(actor,alert);
        String payload=json(map("operation",operation,"alertId",alertId,"expectedVersion",expectedVersion,"body",normalized));
        Map<String,Object> result=repo.executeCommand(WorkflowCommandExecutor.Domain.ALERT,actor,commandKey,payload,alertId,expectedVersion,()->{
            authorize(actor,alert,operation,normalized);evidence.requireReadable(actor,id(alert.get("patientId")),requested);
            if(id(alert.get("lockVersion"))!=expectedVersion)throw AlertWorkflowException.conflict();
            return apply(actor,alert,operation,normalized,expectedVersion);
        });
        try {authorize(actor,alert,operation,normalized);requireSource(actor,alert);evidence.requireReadable(actor,id(alert.get("patientId")),requested);}
        catch(RuntimeException denied){preventCommit();throw denied;}
        return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> adopt(long actor,String sourceType,long sourceId,String metric,Map<String,Object> body,String commandKey) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);commandKey(commandKey);choice(sourceType,"sourceType",SOURCE_TYPES);requireId(sourceId,"sourceId");choice(metric,"metric",METRICS);
        fields(body,set("patientId","manualFollowUp","reason"));long patient=requireId(body.get("patientId"),"patientId");String reason=requireText(body.get("reason"),"reason",1000);
        if(!(body.get("manualFollowUp") instanceof Boolean))throw AlertWorkflowException.invalid("manualFollowUp must be explicit.");boolean manual=(Boolean)body.get("manualFollowUp");
        auth.requireRecord(actor,patient);repo.readSource(actor,patient,sourceType,sourceId);Map<String,Object> source=repo.lockSource(sourceType,sourceId);auth.requireRecord(actor,patient);repo.readSource(actor,patient,sourceType,sourceId);
        // Source status is a new-adoption precondition, not authority to retrieve a committed result.
        // Deduplication is source identity/revision/metric, never reading values or actor/key alone.
        List<Long> existing=jdbc.queryForList("SELECT alert_id FROM alert_source_link WHERE source_type=? AND source_id=? AND source_revision=? AND metric_key=?",Long.class,sourceType,sourceId,source.get("sourceRevision"),metric);
        Map<String,Object> current=existing.isEmpty()?null:repo.lockAlert(existing.get(0));
        String payload=json(map("operation","ADOPT","sourceType",sourceType,"sourceId",sourceId,"metric",metric,"body",body));
        Map<String,Object> result=repo.executeCommand(WorkflowCommandExecutor.Domain.ALERT,actor,commandKey,payload,null,0,()->{
            auth.requireRecord(actor,patient);repo.readSource(actor,patient,sourceType,sourceId);
            if(!"ACTIVE".equals(source.get("sourceStatus")))throw AlertWorkflowEvidenceService.changed();
            if(current!=null){auth.requireRead(actor,id(current.get("patientId")));return result(current,latestEvent(id(current.get("id"))));}
            Map<String,Object> classification=assess(source,metric);
            if(!"ABNORMAL".equals(classification.get("status"))&&!manual)throw AlertWorkflowException.invalid("Normal or unclassifiable readings require an explicitly labeled manual follow-up.");
            classification.put("manualFollowUp",manual);evidence.ensureTracked(actor,source);
            return createFromSource(actor,source,metric,classification,reason);
        });
        try{auth.requireRecord(actor,patient);repo.readSource(actor,patient,sourceType,sourceId);repo.readAlert(actor,id(result.get("alertId")));}catch(RuntimeException denied){preventCommit();throw denied;}return result;
    }
    /** Task4 bridge: source must be locked/tracked in the caller's same READ_COMMITTED transaction. */
    public Map<String,Object> createFromSource(long actor,Map<String,Object> source,String metric,Map<String,Object> classification,String reason) {
        WorkflowData.mutationTransaction(jdbc);long patient=id(source.get("patientId"));auth.requireRecord(actor,patient);requireText(reason,"reason",1000);choice(metric,"metric",METRICS);
        Map<String,Object> actual=repo.readSource(actor,patient,(String)source.get("sourceType"),id(source.get("id")));
        if(actual.get("sourceIdentityId")==null||!Objects.equals(actual.get("sourceRevision"),source.get("sourceRevision")))throw AlertWorkflowException.conflict();
        Map<String,Object> trusted=assess(actual,metric);boolean manual=Boolean.TRUE.equals(classification.get("manualFollowUp"));
        if(!"ABNORMAL".equals(trusted.get("status"))&&!manual)throw AlertWorkflowException.invalid("Only abnormal readings create automatic follow-ups.");trusted.put("manualFollowUp",manual);
        List<Long> existing=jdbc.queryForList("SELECT alert_id FROM alert_source_link WHERE source_type=? AND source_id=? AND source_revision=? AND metric_key=?",Long.class,actual.get("sourceType"),actual.get("id"),actual.get("sourceRevision"),metric);
        if(!existing.isEmpty()){Map<String,Object> row=repo.lockAlert(existing.get(0));return result(row,latestEvent(id(row.get("id"))));}
        long owner=jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?",Long.class,patient);
        long alertId=repo.createAlert(map("patientId",patient,"ownerUserId",owner,"sourceType",actual.get("sourceType"),"sourceId",actual.get("id"),"sourceRevision",actual.get("sourceRevision"),"metric",metric,"classification",trusted,"workflowMode","PERSONAL","responsibleUserId",null,"dueAt",null));
        repo.linkSource(id(actual.get("sourceIdentityId")),(String)actual.get("sourceType"),id(actual.get("id")),id(actual.get("sourceRevision")),metric,alertId);
        Map<String,Object> alert=repo.lockAlert(alertId);long event=events.append(actor,alert,"CREATED",null,reason,null,properties.now(),map("manualFollowUp",manual,"sourceRevision",actual.get("sourceRevision"),"version",0));notifications.enqueue(event);return result(alert,event);
    }
    /**
     * Task4 source-correction handoff. Invoke before the native update and source revision append.
     * The returned token is single-use and bound to this same synchronized datasource transaction.
     */
    public SourceChange prepareSourceChange(long actor,long patient,String type,long sourceId) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);choice(type,"sourceType",SOURCE_TYPES);requireId(sourceId,"sourceId");auth.requireRecord(actor,patient);repo.readSource(actor,patient,type,sourceId);
        List<Long> affected=affected(type,sourceId);List<Map<String,Object>> references=new ArrayList<>();Map<Long,String> signatures=new LinkedHashMap<>();List<Map<String,Object>> alerts=new ArrayList<>();
        for(long alertId:affected) {
            Map<String,Object> row=repo.readAlert(actor,alertId);if(id(row.get("patientId"))!=patient)throw AlertWorkflowException.denied();
            List<Map<String,Object>> deps=evidence.dependencies(alertId);signatures.put(alertId,json(deps));references.addAll(deps);references.add(map("sourceType",row.get("sourceType"),"sourceId",row.get("sourceId")));
        }
        List<Map<String,Object>> receiptActions=evidence.receiptActions(type,sourceId);references.addAll(receiptActions);
        references.add(map("sourceType",type,"sourceId",sourceId));String planLinks=evidence.dependencyLinks(references);evidence.lockReferences(references);
        Map<String,Object> source=repo.lockSource(type,sourceId);if(source==null)throw AlertWorkflowException.denied();evidence.ensureTracked(actor,source);
        for(long alertId:affected)alerts.add(repo.lockAlert(alertId));
        if(!json(receiptActions).equals(json(evidence.receiptActions(type,sourceId)))||!affected.equals(affected(type,sourceId))||!planLinks.equals(evidence.dependencyLinks(references)))throw AlertWorkflowException.conflict();
        for(Map.Entry<Long,String> signature:signatures.entrySet())if(!signature.getValue().equals(json(evidence.dependencies(signature.getKey()))))throw AlertWorkflowException.conflict();
        auth.requireRecord(actor,patient);repo.readSource(actor,patient,type,sourceId);for(Map<String,Object> row:alerts)auth.requireRead(actor,id(row.get("patientId")));
        SourceChange change=new SourceChange(actor,patient,type,sourceId,source,alerts,TransactionSynchronizationManager.getResource(jdbc.getDataSource()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){if(!change.used)throw new IllegalStateException("An unfinished source correction cannot commit.");}});
        return change;
    }
    /** Call after Task4's native save plus append/advance revision, before committing its MEASUREMENT command. */
    public Map<String,Object> sourceChanged(long actor,SourceChange change,String reason) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);reason=requireText(reason,"reason",1000);
        if(change==null||change.used||change.actor!=actor||change.transaction!=TransactionSynchronizationManager.getResource(jdbc.getDataSource()))throw new IllegalStateException("Source change must use its original locked transaction exactly once.");
        auth.requireRecord(actor,change.patient);Map<String,Object> source=repo.readSource(actor,change.patient,change.type,change.sourceId);
        if(change.source.get("sourceIdentityId")==null||!Objects.equals(source.get("sourceIdentityId"),change.source.get("sourceIdentityId"))||id(source.get("sourceRevision"))!=id(change.source.get("sourceRevision"))+1||id(source.get("sourceVersion"))!=id(change.source.get("sourceVersion"))+1)throw AlertWorkflowException.conflict();
        List<Long> alertIds=new ArrayList<>();for(Map<String,Object> row:change.alerts)alertIds.add(id(row.get("id")));if(!alertIds.equals(affected(change.type,change.sourceId)))throw AlertWorkflowException.conflict();
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> alert:change.alerts) {
            auth.requireRead(actor,id(alert.get("patientId")));long version=id(alert.get("lockVersion"));String before=(String)alert.get("workflowState");
            List<Map<String,Object>> changedDependencies=new ArrayList<>();
            for(Map<String,Object> ref:evidence.dependencies(id(alert.get("id"))))if(evidence.referencesSource(ref,change.type,change.sourceId)) {
                Map<String,Object> changed=new LinkedHashMap<>(ref);
                // Qualification is durable in private source-revision metadata, not alert-local payloads.
                changedDependencies.add(changed);jdbc.update("UPDATE alert_evidence SET dependency_state='REFERENCE_CHANGED' WHERE id=?",ref.get("id"));
            }
            newCycle(alert);alert.put("sourceReviewRequired",true);
            if(change.type.equals(alert.get("sourceType"))&&id(alert.get("sourceId"))==change.sourceId) {
                alert.put("sourceRevision",source.get("sourceRevision"));Map<String,Object> classification=assessRevision(source,alert);classification.put("manualFollowUp",Boolean.TRUE.equals(cast(alert.get("classification")).get("manualFollowUp")));alert.put("classification",classification);
                repo.linkSource(id(source.get("sourceIdentityId")),change.type,change.sourceId,id(source.get("sourceRevision")),(String)alert.get("metric"),id(alert.get("id")));
            }
            if(alert.get("responsibleUserId")!=null)try{long assignee=id(alert.get("responsibleUserId"));auth.requireRecord(assignee,change.patient);if("CLINICAL".equals(alert.get("workflowMode")))doctorOrNurse(assignee,change.patient);}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;alert.put("reassignmentRequired",true);}
            long event=events.append(actor,alert,"SOURCE_CHANGED",before,reason,null,properties.now(),map("sourceType",change.type,"sourceId",change.sourceId,"sourceRevision",source.get("sourceRevision"),"sourceStatus",source.get("sourceStatus"),"changedDependencies",changedDependencies,"version",version+1));
            repo.saveAlert(alert,version);alert.put("lockVersion",version+1);notifications.enqueue(event);result.add(result(alert,event));
        }
        auth.requireRecord(actor,change.patient);repo.readSource(actor,change.patient,change.type,change.sourceId);change.used=true;return map("sourceId",change.sourceId,"sourceRevision",source.get("sourceRevision"),"followUps",result);
    }
    /** Finish a caller's durable MEASUREMENT replay only when its prepared source and aggregates are unchanged. */
    public void completeSourceChangeReplay(long actor,SourceChange change) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);
        if(change==null||change.used||change.actor!=actor||change.transaction!=TransactionSynchronizationManager.getResource(jdbc.getDataSource()))throw new IllegalStateException("Source replay must use its original locked transaction exactly once.");
        auth.requireRecord(actor,change.patient);Map<String,Object> current=repo.readSource(actor,change.patient,change.type,change.sourceId);
        for(String key:Arrays.asList("sourceIdentityId","sourceRevision","sourceVersion","sourceStatus"))if(!Objects.equals(current.get(key),change.source.get(key)))throw AlertWorkflowException.conflict();
        if(!json(evidence.snapshot(current)).equals(json(evidence.snapshot(change.source))))throw AlertWorkflowException.conflict();
        for(Map<String,Object> alert:change.alerts)if(!json(alert).equals(json(repo.readAlert(actor,id(alert.get("id"))))))throw AlertWorkflowException.conflict();
        change.used=true;
    }
    private List<Long> affected(String type,long sourceId) {
        return jdbc.queryForList("SELECT DISTINCT a.id FROM alert_record a WHERE a.workflow_version=1 AND ((a.source_type=? AND a.source_id=?) OR EXISTS(SELECT 1 FROM alert_evidence v JOIN alert_event e ON e.id=v.event_id WHERE e.alert_id=a.id AND v.dependency_state<>'WITHDRAWN' AND ((v.source_type=? AND v.source_id=?) OR (v.source_type='CARE_PLAN_ACTION' AND EXISTS(SELECT 1 FROM care_plan_evidence cv JOIN care_plan_event ce ON ce.id=cv.event_id WHERE ce.action_id=v.source_id AND cv.source_type=? AND cv.source_id=?))))) ORDER BY a.id",Long.class,type,sourceId,type,sourceId,"HEALTH_MEASUREMENT".equals(type)?"MEASUREMENT":type,sourceId);
    }
    public static final class SourceChange {
        private final long actor,patient,sourceId;private final String type;private final Map<String,Object> source;private final List<Map<String,Object>> alerts;private final Object transaction;private boolean used;
        private SourceChange(long actor,long patient,String type,long sourceId,Map<String,Object> source,List<Map<String,Object>> alerts,Object transaction){this.actor=actor;this.patient=patient;this.type=type;this.sourceId=sourceId;this.source=source;this.alerts=alerts;this.transaction=transaction;}
        public boolean isCompleted(){return used;}
        public Map<String,Object> getSource(){return Collections.unmodifiableMap(new LinkedHashMap<>(source));}
        public List<Long> getAlertIds(){List<Long> ids=new ArrayList<>();for(Map<String,Object> row:alerts)ids.add(id(row.get("id")));return Collections.unmodifiableList(ids);}
    }

    private Map<String,Object> apply(long actor,Map<String,Object> alert,String op,Map<String,Object> body,long expectedVersion) {
        String before=(String)alert.get("workflowState");long patient=id(alert.get("patientId"));String note=(String)(body.containsKey("note")?body.get("note"):body.get("reason"));
        Instant occurred=body.containsKey("occurredAt")?parseOffsetInstant((String)body.get("occurredAt")):properties.now();String entry=(String)body.get("entryMode");
        Map<String,Object> payload=new LinkedHashMap<>(body);payload.put("version",expectedVersion+1);
        if(!"REOPEN".equals(op))nonterminal(before);
        switch(op) {
            case "ASSIGN":
                eligible(actor,patient,(String)alert.get("workflowMode"),id(body.get("assigneeId")));alert.put("responsibleUserId",body.get("assigneeId"));
                if(body.containsKey("dueAt"))alert.put("dueAt",body.get("dueAt"));resetAcceptance(alert);alert.put("reassignmentRequired",false);alert.put("workflowState","OPEN");break;
            case "ESCALATE":
                if(body.get("assigneeId")!=null)eligible(actor,patient,"CLINICAL",id(body.get("assigneeId")));
                alert.put("workflowMode","CLINICAL");alert.put("responsibleUserId",body.get("assigneeId"));
                // A personal response target is not silently adopted as a clinical target.
                alert.put("dueAt",body.get("dueAt"));newCycle(alert);alert.put("reassignmentRequired",false);break;
            case "ACCEPT":
                state(before,"OPEN");if(Boolean.TRUE.equals(alert.get("reassignmentRequired"))||Boolean.TRUE.equals(alert.get("sourceReviewRequired")))throw AlertWorkflowException.conflict();
                if(!Objects.equals(alert.get("responsibleUserId"),body.get("assigneeId"))||!Objects.equals(alert.get("dueAt"),body.get("dueAt")))throw AlertWorkflowException.conflict();
                alert.put("acceptedBy",actor);alert.put("acceptedAt",properties.now().toString());alert.put("workflowState","ACCEPTED");break;
            case "REJECT_ASSIGNMENT":state(before,"OPEN");resetAcceptance(alert);alert.put("reassignmentRequired",true);break;
            case "FOLLOW_UP":state(before,"ACCEPTED","IN_PROGRESS");accepted(alert);alert.put("workflowState","IN_PROGRESS");break;
            case "SUBMIT":state(before,"ACCEPTED","IN_PROGRESS");accepted(alert);alert.put("workflowState","WAITING_FEEDBACK");alert.put("currentFeedbackEventId",null);break;
            case "FEEDBACK":
                state(before,"WAITING_FEEDBACK");accepted(alert);if(alert.get("currentHandlingEventId")==null)throw AlertWorkflowException.conflict();
                if("DIFFICULTY".equals(body.get("outcome"))){alert.put("workflowState","IN_PROGRESS");alert.put("currentHandlingEventId",null);alert.put("currentFeedbackEventId",null);}else alert.put("workflowState","READY_FOR_REVIEW");
                payload.put("identityClaim","SELF".equals(entry)?"SELF_REPORTED":"ASSISTED");break;
            case "RETURN":
                state(before,"READY_FOR_REVIEW");alert.put("workflowState",body.get("targetState"));alert.put("currentFeedbackEventId",null);if("IN_PROGRESS".equals(body.get("targetState")))alert.put("currentHandlingEventId",null);break;
            case "CLOSE":
                state(before,"READY_FOR_REVIEW");accepted(alert);if(Boolean.TRUE.equals(alert.get("sourceReviewRequired"))||alert.get("currentFeedbackEventId")==null)throw AlertWorkflowException.conflict();
                requireCurrentFeedback(alert);evidence.requireComplete(actor,alert);alert.put("workflowState","CLOSED");alert.put("closedBy",actor);alert.put("closedAt",properties.now().toString());alert.put("closureReason",note);
                payload.put("closureKind","CLINICAL".equals(alert.get("workflowMode"))?"CLINICAL_REVIEW":"PERSONAL_HANDLING_RECORDED");break;
            case "CANCEL":alert.put("workflowState","CANCELLED");alert.put("closedBy",actor);alert.put("closedAt",properties.now().toString());alert.put("closureReason",note);break;
            case "REOPEN":state(before,"CLOSED","CANCELLED");newCycle(alert);break;
            case "REVIEW_SOURCE_CHANGE":reviewSource(actor,alert,body);break;
            case "RESOLVE_REFERENCE_CHANGE":resolve(actor,alert,body,payload);break;
            default:throw AlertWorkflowException.invalid("Unsupported operation.");
        }
        long event=events.append(actor,alert,op,before,note,entry,occurred,payload);
        if("SUBMIT".equals(op)) {evidence.attach(actor,patient,event,validateEvidence(body.get("evidence")));alert.put("currentHandlingEventId",event);}
        if("FEEDBACK".equals(op)&&"HANDLED".equals(body.get("outcome")))alert.put("currentFeedbackEventId",event);
        if("RESOLVE_REFERENCE_CHANGE".equals(op)&&body.get("replacement")!=null)evidence.attach(actor,patient,event,Collections.singletonList(cast(body.get("replacement"))));
        repo.saveAlert(alert,expectedVersion);alert.put("lockVersion",expectedVersion+1);
        if(set("ASSIGN","ESCALATE","REJECT_ASSIGNMENT","SUBMIT","FEEDBACK","RETURN","CLOSE","CANCEL","REOPEN").contains(op))notifications.enqueue(event);
        return result(alert,event);
    }
    private void reviewSource(long actor,Map<String,Object> alert,Map<String,Object> body) {
        if(!Boolean.TRUE.equals(alert.get("sourceReviewRequired")))throw AlertWorkflowException.conflict();
        Map<String,Object> source=repo.readSource(actor,id(alert.get("patientId")),(String)alert.get("sourceType"),id(alert.get("sourceId")));
        if(!Objects.equals(source.get("sourceRevision"),body.get("sourceRevision")))throw AlertWorkflowException.conflict();
        for(Map<String,Object> ref:evidence.dependencies(id(alert.get("id"))))if("REFERENCE_CHANGED".equals(ref.get("dependencyState")))throw AlertWorkflowEvidenceService.changed();
        alert.put("sourceRevision",source.get("sourceRevision"));alert.put("sourceReviewRequired",false);
    }
    private void resolve(long actor,Map<String,Object> alert,Map<String,Object> body,Map<String,Object> payload) {
        Map<String,Object> ref=evidence.reference(id(alert.get("id")),id(body.get("evidenceId")));long patient=id(alert.get("patientId"));
        if("CLINICAL".equals(alert.get("workflowMode"))||"CARE_PLAN_ACTION".equals(ref.get("sourceType"))||isPlanReplacement(body))auth.requireClinical(actor,patient);else auth.requireOwner(actor,patient);
        if(evidence.hasLockedGraph()&&!evidence.readable(actor,patient,ref,true))throw AlertWorkflowException.denied();
        Map<String,Object> replacement=body.get("replacement")==null?null:cast(body.get("replacement"));
        if("CARE_PLAN_ACTION".equals(ref.get("sourceType"))&&replacement!=null&&!"CARE_PLAN_ACTION".equals(replacement.get("sourceType")))throw AlertWorkflowException.invalid("A clinical plan dependency requires a replacement plan action or explicit termination.");
        payload.put("previousReference",map("sourceType",ref.get("sourceType"),"sourceId",ref.get("sourceId"),"sourceRevision",ref.get("sourceRevision"),"dependencyVersion",ref.get("dependencyVersion")));
        evidence.withdraw(id(ref.get("id")));
    }
    private void authorize(long actor,Map<String,Object> alert,String operation,Map<String,Object> body) {
        long patient=id(alert.get("patientId"));auth.requireRead(actor,patient);
        if("CLOSE".equals(operation)&&evidence.hasLockedGraph())for(Map<String,Object> ref:evidence.dependencies(id(alert.get("id"))))if(evidence.hasLockedGraph()&&!evidence.readable(actor,patient,ref,true))throw AlertWorkflowException.denied();
        if(set("ASSIGN","ESCALATE").contains(operation)) {
            if("ESCALATE".equals(operation)||"CLINICAL".equals(alert.get("workflowMode")))ownerOrDoctor(actor,patient);else auth.requireOwner(actor,patient);
            if(body.containsKey("dueAt")&&("ESCALATE".equals(operation)||"CLINICAL".equals(alert.get("workflowMode"))))auth.requireClinical(actor,patient);
            if(body.get("assigneeId")!=null)eligible(actor,patient,"ESCALATE".equals(operation)?"CLINICAL":(String)alert.get("workflowMode"),id(body.get("assigneeId")));
        }else if(set("ACCEPT","REJECT_ASSIGNMENT","FOLLOW_UP","SUBMIT").contains(operation)) {
            auth.requireRecord(actor,patient);if(!Objects.equals(alert.get("responsibleUserId"),actor))throw AlertWorkflowException.denied();
            if("CLINICAL".equals(alert.get("workflowMode")))doctorOrNurse(actor,patient);
        }else if("FEEDBACK".equals(operation)) {
            auth.requireRecord(actor,patient);if("SELF".equals(body.get("entryMode")))auth.requireOwner(actor,patient);
        }else if("RESOLVE_REFERENCE_CHANGE".equals(operation)) {
            Map<String,Object> ref=evidence.reference(id(alert.get("id")),id(body.get("evidenceId")));
            if("CLINICAL".equals(alert.get("workflowMode"))||"CARE_PLAN_ACTION".equals(ref.get("sourceType"))||isPlanReplacement(body))auth.requireClinical(actor,patient);else auth.requireOwner(actor,patient);
            if(evidence.hasLockedGraph()&&!evidence.readable(actor,patient,ref,true))throw AlertWorkflowException.denied();
        }else if("CLINICAL".equals(alert.get("workflowMode")))auth.requireClinical(actor,patient);else auth.requireOwner(actor,patient);
    }
    private boolean isPlanReplacement(Map<String,Object> body){return body.get("replacement") instanceof Map&&"CARE_PLAN_ACTION".equals(cast(body.get("replacement")).get("sourceType"));}
    private void ownerOrDoctor(long actor,long patient){try{auth.requireOwner(actor,patient);}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;auth.requireClinical(actor,patient);}}
    private void doctorOrNurse(long actor,long patient){try{auth.requireClinical(actor,patient);}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;auth.requireNursing(actor,patient);}}
    private void eligible(long actor,long patient,String mode,long assignee){for(Map<String,Object> row:auth.eligibleAssignees(actor,patient,mode))if(id(row.get("userId"))==assignee)return;throw AlertWorkflowException.denied();}
    private void requireSource(long actor,Map<String,Object> alert){repo.readSource(actor,id(alert.get("patientId")),(String)alert.get("sourceType"),id(alert.get("sourceId")));}
    private void accepted(Map<String,Object> alert){if(alert.get("acceptedBy")==null||!Objects.equals(alert.get("responsibleUserId"),alert.get("acceptedBy"))||Boolean.TRUE.equals(alert.get("reassignmentRequired"))||Boolean.TRUE.equals(alert.get("sourceReviewRequired")))throw AlertWorkflowException.conflict();long actor=id(alert.get("acceptedBy")),patient=id(alert.get("patientId"));auth.requireRecord(actor,patient);if("CLINICAL".equals(alert.get("workflowMode")))doctorOrNurse(actor,patient);}
    private void requireCurrentFeedback(Map<String,Object> alert){if(jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE id=? AND alert_id=? AND cycle_no=? AND event_type='FEEDBACK' AND entry_mode IN ('SELF','ASSISTED') AND to_status='READY_FOR_REVIEW'",Integer.class,alert.get("currentFeedbackEventId"),alert.get("id"),alert.get("cycleNo"))!=1)throw AlertWorkflowException.conflict();}
    private void resetAcceptance(Map<String,Object> row){row.put("acceptedBy",null);row.put("acceptedAt",null);}
    private void newCycle(Map<String,Object> row){row.put("cycleNo",Math.addExact(((Number)row.get("cycleNo")).intValue(),1));row.put("workflowState","OPEN");resetAcceptance(row);row.put("currentHandlingEventId",null);row.put("currentFeedbackEventId",null);row.put("closedBy",null);row.put("closedAt",null);row.put("closureReason",null);}
    private void nonterminal(String state){if(set("CLOSED","CANCELLED").contains(state))throw AlertWorkflowException.conflict();}
    private void state(String state,String...allowed){if(!set(allowed).contains(state))throw AlertWorkflowException.conflict();}
    private long latestEvent(long alert){return jdbc.queryForObject("SELECT MAX(id) FROM alert_event WHERE alert_id=?",Long.class,alert);}
    private Map<String,Object> result(Map<String,Object> row,long event){return map("alertId",row.get("id"),"version",row.get("lockVersion"),"state",row.get("workflowState"),"mode",row.get("workflowMode"),"cycleNo",row.get("cycleNo"),"eventId",event);}
    private void preventCommit(){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("Failed current workflow authorization cannot commit.");}});}
    private Map<String,Object> normalize(String operation,Map<String,Object> body) {
        Set<String> allowed;
        switch(operation){
            case "ASSIGN":allowed=set("assigneeId","dueAt","reason");break;
            case "ESCALATE":allowed=set("assigneeId","dueAt","reason");break;
            case "ACCEPT":allowed=set("assigneeId","dueAt");break;
            case "FOLLOW_UP":allowed=set("note","occurredAt");break;
            case "SUBMIT":allowed=set("note","occurredAt","evidence","contact");break;
            case "FEEDBACK":allowed=set("note","occurredAt","entryMode","outcome","reason");break;
            case "RETURN":allowed=set("reason","targetState");break;
            case "REVIEW_SOURCE_CHANGE":allowed=set("reason","sourceRevision");break;
            case "RESOLVE_REFERENCE_CHANGE":allowed=set("reason","evidenceId","resolution","replacement");break;
            default:allowed=set("reason");
        }
        fields(body,allowed);Map<String,Object> b=new LinkedHashMap<>(body);
        if(allowed.contains("reason")&&(!"FEEDBACK".equals(operation)||"DIFFICULTY".equals(body.get("outcome"))||body.containsKey("reason")))b.put("reason",requireText(body.get("reason"),"reason",1000));
        if(allowed.contains("note"))b.put("note",requireText(body.get("note"),"note",2000));
        if(allowed.contains("occurredAt"))b.put("occurredAt",requireOccurredAt(body.get("occurredAt"),properties.now()).toString());
        if("ASSIGN".equals(operation)||"ACCEPT".equals(operation)||body.containsKey("assigneeId"))b.put("assigneeId",requireId(body.get("assigneeId"),"assigneeId"));
        if("ACCEPT".equals(operation)&&!body.containsKey("dueAt"))throw AlertWorkflowException.invalid("Confirm the current deadline, including null when not set.");
        if(body.containsKey("dueAt")){Object due=body.get("dueAt");if(due!=null&&!(due instanceof String))throw AlertWorkflowException.invalid("dueAt must include an offset.");b.put("dueAt",due==null?null:parseOffsetInstant((String)due).toString());}
        if("FEEDBACK".equals(operation)){b.put("entryMode",choice(body.get("entryMode"),"entryMode",set("SELF","ASSISTED")));b.put("outcome",choice(body.get("outcome"),"outcome",set("HANDLED","DIFFICULTY")));}
        if("RETURN".equals(operation))b.put("targetState",choice(body.get("targetState"),"targetState",set("IN_PROGRESS","WAITING_FEEDBACK")));
        if("REVIEW_SOURCE_CHANGE".equals(operation))b.put("sourceRevision",requireId(body.get("sourceRevision"),"sourceRevision"));
        if("SUBMIT".equals(operation)) {
            List<Map<String,Object>> refs=validateEvidence(body.get("evidence"));b.put("evidence",refs);
            if(body.get("contact")!=null){Map<String,Object> contact=cast(body.get("contact"));fields(contact,set("relationship","outcome"));b.put("contact",map("relationship",requireText(contact.get("relationship"),"relationship",100),"outcome",requireText(contact.get("outcome"),"outcome",2000),"provenance","SELF_REPORTED"));}
            if(refs.isEmpty()&&b.get("contact")==null)throw AlertWorkflowException.invalid("Provide a checkable contact outcome or an existing-record reference.");
        }
        if("RESOLVE_REFERENCE_CHANGE".equals(operation)) {
            b.put("evidenceId",requireId(body.get("evidenceId"),"evidenceId"));String resolution=choice(body.get("resolution"),"resolution",set("WITHDRAW","REPLACE"));
            if("REPLACE".equals(resolution))b.put("replacement",validateEvidence(Collections.singletonList(body.get("replacement"))).get(0));else if(body.containsKey("replacement"))throw AlertWorkflowException.invalid("Withdrawal cannot include a replacement.");
        }
        return b;
    }
    private List<Map<String,Object>> requested(Map<String,Object> body){List<Map<String,Object>> refs=new ArrayList<>();if(body.get("evidence")!=null)refs.addAll(validateEvidence(body.get("evidence")));if(body.get("replacement")!=null)refs.add(cast(body.get("replacement")));return refs;}
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object value){if(!(value instanceof Map))throw AlertWorkflowException.invalid("Expected an object.");return(Map<String,Object>)value;}
    private static Set<String> set(String...values){return new HashSet<>(Arrays.asList(values));}
    private Map<String,Object> assessRevision(Map<String,Object> source,Map<String,Object> alert) {
        try{return assess(source,(String)alert.get("metric"));}
        catch(AlertWorkflowException invalid){
            if(invalid.getStatus()!=400)throw invalid;
            // A corrected source may no longer contain the original metric. Keep its follow-up identity and review obligation.
            return map("metric",alert.get("metric"),"policyKey",cast(alert.get("classification")).get("policyKey"),"status","UNCLASSIFIABLE","severity","UNCLASSIFIABLE","comparisonValue",null,"sourceUnit",source.containsKey("unit")?source.get("unit"):source.get("bgUnit"),"period",source.get("measurePeriod"),"thresholds",Collections.emptyMap(),"contextStatus","METRIC_REMOVED_OR_CHANGED");
        }
    }
    Map<String,Object> assess(Map<String,Object> source,String metric) {
        long patient=id(source.get("patientId"));
        if("HEALTH_MEASUREMENT".equals(source.get("sourceType"))){if(!metric.equals(source.get("metricType")))throw AlertWorkflowException.invalid("metric does not match the source.");return new LinkedHashMap<>(assessments.assessJourney(patient,metric,(BigDecimal)source.get("valuePrimary"),(BigDecimal)source.get("valueSecondary"),(String)source.get("unit")));}
        BpSelfMonitorRecord record=new BpSelfMonitorRecord();record.setSystolicBp((Integer)source.get("systolicBp"));record.setDiastolicBp((Integer)source.get("diastolicBp"));record.setBloodGlucose((BigDecimal)source.get("bloodGlucose"));record.setBgUnit((String)source.get("bgUnit"));record.setMeasurePeriod((String)source.get("measurePeriod"));
        List<PatientHealthTarget> targets=jdbc.query("SELECT systolic_min,systolic_max,diastolic_min,diastolic_max,fasting_glucose_min,fasting_glucose_max,postmeal_glucose_min,postmeal_glucose_max FROM patient_health_target WHERE patient_id=?",(rs,i)->{PatientHealthTarget t=new PatientHealthTarget();t.setSystolicMin((Integer)rs.getObject(1));t.setSystolicMax((Integer)rs.getObject(2));t.setDiastolicMin((Integer)rs.getObject(3));t.setDiastolicMax((Integer)rs.getObject(4));t.setFastingGlucoseMin(rs.getBigDecimal(5));t.setFastingGlucoseMax(rs.getBigDecimal(6));t.setPostmealGlucoseMin(rs.getBigDecimal(7));t.setPostmealGlucoseMax(rs.getBigDecimal(8));return t;},patient);
        for(Map<String,Object> row:assessments.assessSelfMonitor(record,targets.isEmpty()?null:targets.get(0)))if(metric.equals(row.get("metric")))return new LinkedHashMap<>(row);throw AlertWorkflowException.invalid("metric is not present in the source.");
    }
}
