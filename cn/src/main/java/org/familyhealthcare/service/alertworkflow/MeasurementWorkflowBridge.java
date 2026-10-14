package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.workflow.WorkflowCommandExecutor;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.alertworkflow.AlertWorkflowContracts.*;

/** Native stores keep their own validation and persistence; this boundary owns the atomic workflow command. */
@Service
@ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
public class MeasurementWorkflowBridge {
    private final JdbcTemplate jdbc;
    private final AlertWorkflowProperties properties;
    private final AlertWorkflowAuthorizationService auth;
    private final AlertWorkflowRepository repo;
    private final AlertWorkflowService workflow;
    private final AlertWorkflowEvidenceService evidence;
    public MeasurementWorkflowBridge(JdbcTemplate jdbc, AlertWorkflowProperties properties,
            AlertWorkflowAuthorizationService auth, AlertWorkflowRepository repo,
            AlertWorkflowService workflow, AlertWorkflowEvidenceService evidence) {
        this.jdbc=jdbc;this.properties=properties;this.auth=auth;this.repo=repo;this.workflow=workflow;this.evidence=evidence;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> read(long actor,long patient,String type,long sourceId) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);repo.readSource(actor,patient,type,sourceId);
        Map<String,Object> source=repo.lockSource(type,sourceId);repo.readSource(actor,patient,type,sourceId);
        Map<String,Object> result=new LinkedHashMap<>(source);List<Map<String,Object>> assessments=new ArrayList<>();
        for(String metric:metrics(source))assessments.add(workflow.assess(source,metric));result.put("assessments",assessments);
        auth.requireRead(actor,patient);return result;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> history(long actor,long patient,String type,long sourceId,long after,int limit) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);requireVersion(after);if(limit<1||limit>100)throw AlertWorkflowException.invalid("limit must be between 1 and 100.");
        repo.readSource(actor,patient,type,sourceId);Map<String,Object> source=repo.lockSource(type,sourceId);repo.readSource(actor,patient,type,sourceId);
        List<Map<String,Object>> items=source.get("sourceIdentityId")==null?new ArrayList<>():repo.sourceRevisions(id(source.get("sourceIdentityId")),after,limit+1);Long next=null;
        if(items.size()>limit){items.remove(items.size()-1);next=id(items.get(items.size()-1).get("sourceRevision"));}
        auth.requireRead(actor,patient);return map("items",items,"nextAfterRevision",next);
    }

    /** Internal callback is supplied by a native service, never by an HTTP client. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> save(long actor,String type,long patient,Map<String,Object> body,Supplier<Long> nativeInsert) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);choice(type,"sourceType",SOURCE_TYPES);
        if(!body.containsKey("commandKey"))throw AlertWorkflowException.invalid("Refresh the client before saving: commandKey is required.");
        String key=commandKey(body.get("commandKey"));OffsetDateTime declared=requireOccurredTime(body.get("occurredAt"),properties.now());Instant occurred=declared.toInstant();
        if(body.containsKey("expectedVersion")&&requireVersion(body.get("expectedVersion"))!=0)throw AlertWorkflowException.conflict();
        auth.requireRecord(actor,patient);
        Map<String,Object> result=repo.executeCommand(WorkflowCommandExecutor.Domain.MEASUREMENT,actor,key,
                json(map("operation","CREATE","sourceType",type,"patientId",patient,"body",body)),null,0,()->{
            auth.requireRecord(actor,patient);long sourceId=nativeInsert.get();
            Map<String,Object> source=repo.lockSource(type,sourceId);repo.readSource(actor,patient,type,sourceId);
            if(source.get("sourceIdentityId")!=null)throw AlertWorkflowException.conflict();
            requireWallTime(source,declared.toLocalDateTime());
            long identity=repo.createSourceIdentity(type,sourceId,patient);
            repo.appendSourceRevision(identity,1,actor,occurred,evidence.snapshot(source));
            source=repo.readSource(actor,patient,type,sourceId);
            List<Map<String,Object>> followUps=new ArrayList<>();
            for(String metric:metrics(source)) {
                Map<String,Object> assessment=workflow.assess(source,metric);
                if("ABNORMAL".equals(assessment.get("status")))followUps.add(minimal(workflow.createFromSource(actor,source,metric,assessment,"Abnormal measurement recorded")));
            }
            return map("sourceType",type,"sourceId",sourceId,"sourceRevision",1L,"followUps",followUps);
        });
        try {auth.requireRecord(actor,patient);repo.readSource(actor,patient,type,id(result.get("sourceId")));}
        catch(RuntimeException denied){preventCommit();throw denied;}
        return result;
    }
    /** All dependency locks are acquired by prepare BEFORE the callback may touch the native row. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> correct(long actor,String type,long sourceId,Map<String,Object> body,
            boolean invalidate,java.util.function.Consumer<Map<String,Object>> nativeUpdate) {
        properties.requireEnabled();WorkflowData.mutationTransaction(jdbc);choice(type,"sourceType",SOURCE_TYPES);
        long patient=AlertWorkflowContracts.requireId(body.get("patientId"),"patientId"),expected=requireVersion(body.get("expectedVersion"));
        String key=commandKey(body.get("commandKey")),reason=requireText(body.get("reason"),"reason",1000);
        OffsetDateTime declared=invalidate?null:requireOccurredTime(body.get("occurredAt"),properties.now());
        Instant occurred=invalidate?properties.now():declared.toInstant();
        AlertWorkflowService.SourceChange change=workflow.prepareSourceChange(actor,patient,type,sourceId);
        Map<String,Object> result=repo.executeCommand(WorkflowCommandExecutor.Domain.MEASUREMENT,actor,key,
                json(map("operation",invalidate?"INVALIDATE":"CORRECT","sourceType",type,"sourceId",sourceId,"body",body)),sourceId,expected,()->{
            auth.requireRecord(actor,patient);Map<String,Object> before=change.getSource();
            if(id(before.get("sourceVersion"))!=expected)throw AlertWorkflowException.conflict();
            if(!invalidate)nativeUpdate.accept(before);
            Map<String,Object> current=repo.readSource(actor,patient,type,sourceId);
            if(!invalidate)requireWallTime(current,declared.toLocalDateTime());
            long revision=id(before.get("sourceRevision"))+1,identity=id(before.get("sourceIdentityId"));
            repo.appendSourceRevision(identity,revision,actor,occurred,evidence.snapshot(current),reason,invalidate?"INVALIDATED":"ACTIVE");
            repo.advanceSourceRevision(identity,expected,revision,invalidate?"INVALIDATED":"ACTIVE");
            Map<String,Object> changed=workflow.sourceChanged(actor,change,reason);
            List<Map<String,Object>> followUps=new ArrayList<>();
            for(Object item:(List<?>)changed.get("followUps"))followUps.add(minimal((Map<String,Object>)item));
            if(!invalidate)for(String metric:metrics(current)) {
                Map<String,Object> assessment=workflow.assess(current,metric);
                if("ABNORMAL".equals(assessment.get("status"))) {
                    Map<String,Object> followUp=minimal(workflow.createFromSource(actor,repo.readSource(actor,patient,type,sourceId),metric,assessment,reason));
                    if(followUps.stream().noneMatch(item->Objects.equals(item.get("alertId"),followUp.get("alertId"))))followUps.add(followUp);
                }
            }
            return map("sourceType",type,"sourceId",sourceId,"sourceRevision",revision,"followUps",followUps);
        });
        if(!change.isCompleted())workflow.completeSourceChangeReplay(actor,change);
        try{auth.requireRecord(actor,patient);repo.readSource(actor,patient,type,sourceId);}
        catch(RuntimeException denied){preventCommit();throw denied;}
        return result;
    }

    static Map<String,Object> minimal(Map<String,Object> followUp) {return map("alertId",followUp.get("alertId"),"state",followUp.get("state"),"version",followUp.get("version"));}
    private List<String> metrics(Map<String,Object> source) {
        if("HEALTH_MEASUREMENT".equals(source.get("sourceType")))return Collections.singletonList((String)source.get("metricType"));
        List<String> metrics=new ArrayList<>();if(source.get("systolicBp")!=null||source.get("diastolicBp")!=null)metrics.add("BP");if(source.get("bloodGlucose")!=null)metrics.add("GLUCOSE");return metrics;
    }
    /** Offset is explicit input; the original native wall-time strings are never assigned an invented zone. */
    private void requireWallTime(Map<String,Object> source,LocalDateTime declared) {
        LocalDateTime nativeTime;
        try {
            if("HEALTH_MEASUREMENT".equals(source.get("sourceType")))nativeTime=LocalDateTime.parse((String)source.get("measuredAt"));
            else nativeTime=LocalDateTime.of(LocalDate.parse((String)source.get("recordDate")),LocalTime.parse((String)source.get("recordTime")));
        }catch(DateTimeException|NullPointerException invalid){throw AlertWorkflowException.invalid("Enter the original measurement date/time and an explicit offset.");}
        if(!declared.equals(nativeTime))throw AlertWorkflowException.invalid("occurredAt must match the native measurement wall time with an explicit offset.");
    }
    private void preventCommit(){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("A failed measurement authority check cannot commit.");}});}
}
