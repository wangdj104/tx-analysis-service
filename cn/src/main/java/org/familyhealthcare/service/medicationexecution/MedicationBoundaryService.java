package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionRepository.conflict;
/** Applies only the exact human-verified half-open interval. Personal reminders and medication.is_active are untouched. */
@Service
public class MedicationBoundaryService {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionRepository repo;
    private final MedicationExecutionEventStore events;
    public MedicationBoundaryService(JdbcTemplate jdbc,MedicationExecutionProperties properties,MedicationExecutionAuthorizationService auth,MedicationExecutionRepository repo,MedicationExecutionEventStore events) {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.events=events;
    }
    /** GET is read-only. Concurrent edits are detected by the later lock-bound exact comparison. */
    public Map<String,Object> preview(long actor,long executionId) {
        repo.schema.inspectStorageMetadata().requireStructurallyReady();
        Map<String,Object> e=required(executionId);
        long patient=id(e.get("patient_id"));
        auth.requireRead(actor,patient);
        if(e.get("pending_reconciliation_id")==null)throw conflict("NO_PENDING_RECONCILIATION");
        Map<String,Object> p=repo.reconciliation(id(e.get("pending_reconciliation_id")));
        Map<String,Object> v=repo.revision(id(p.get("current_revision_id")));
        repo.requireRevisionLinks(v,patient);
        source(actor,patient,v,false);
        Map<String,Object> result=preview(e,p,v,id(e.get("lock_version")));
        auth.requireRead(actor,patient);
        return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,long executionId,BoundaryCommand c) {
        mutationTransaction(jdbc);
        Map<String,Object> e=required(executionId);
        long patient=id(e.get("patient_id"));
        if(patient!=c.meta.patientId)throw MedicationExecutionAuthorizationService.denied();
        auth.requireWrite(actor,patient);
        repo.lockInterval(actor,patient,id(e.get("medication_id")),c.meta.commandKey,c.reconciliationId,c.preview==null?null:c.preview.revisionId);
        current(actor,executionId,c.reconciliationId);
        Map<String,Object> result=WorkflowCommandExecutor.execute(jdbc,WorkflowCommandExecutor.Domain.MEDICATION_EXECUTION,actor,c.meta.commandKey,c.payload(executionId),executionId,c.meta.expectedVersion,properties.now(),()-> {
            Map<String,Object> locked=current(actor,executionId,c.reconciliationId);
            if(id(locked.get("lock_version"))!=c.meta.expectedVersion)throw conflict("VERSION_CONFLICT");
            Map<String,Object> p=repo.reconciliation(c.reconciliationId),v=repo.revision(id(p.get("current_revision_id")));
            long version=c.meta.expectedVersion+1;
            if("SCHEDULE_REPLACEMENT".equals(c.operation)) {
                if(!properties.isEnabled())throw new WorkflowException(503,"FEATURE_DISABLED","New medication intervals are disabled.");
                if(!"READY".equals(p.get("state"))||!Objects.equals(locked.get("pending_reconciliation_id"),p.get("id")))throw conflict("STATE_CONFLICT");
                verified(actor,patient,p,v);
                if(!at(v.get("effective_at")).isAfter(properties.now()))throw conflict("FUTURE_VERIFIED_TIME_REQUIRED");
                Map<String,Object> expected=preview(locked,p,v,c.meta.expectedVersion);
                if(!json(expected).equals(json(c.preview.values())))throw conflict("REPLACEMENT_CHANGED");
                assertSafeFuture(executionId,at(v.get("effective_at")));
                repo.update("UPDATE medication_reconciliation SET state='SCHEDULED',scheduled_revision_id=?,scheduled_by=?,scheduled_at=?,replacement_json=?,replacement_hash=?,updated_at=? WHERE id=?",v.get("id"),actor,properties.now(),json(expected),hash(json(expected)),properties.now(),p.get("id"));
            }
            else {
                if(!"SCHEDULED".equals(p.get("state")))throw conflict("STATE_CONFLICT");
                repo.update("UPDATE medication_reconciliation SET state='READY',scheduled_revision_id=NULL,scheduled_by=NULL,scheduled_at=NULL,replacement_json=NULL,replacement_hash=NULL,updated_at=? WHERE id=?",properties.now(),p.get("id"));
            }
            repo.update("UPDATE medication_execution SET lock_version=?,updated_at=? WHERE id=?",version,properties.now(),executionId);
            long event=events.append(actor,patient,executionId,version,((Number)p.get("proposal_no")).intValue(),c.operation,auth.relation(actor,patient),id(p.get("id")),id(v.get("id")),c.reason,map("revisionHash",v.get("revision_hash")));
            return map("executionId",executionId,"version",version,"eventId",event,"state","SCHEDULE_REPLACEMENT".equals(c.operation)?"SCHEDULED":"READY","cycle",p.get("proposal_no"),"reconciliationId",p.get("id"),"revisionId",v.get("id"));
        });
        requireMinimalReceipt(result);
        current(actor,executionId,c.reconciliationId);
        return result;
    }
    /** The caller has a READ_COMMITTED transaction. Admission re-locks current parent identity before domain rows. */
    public void applyDueLocked(long executionId,Instant now) {
        applyDue(executionId,now,null);
    }
    void applyDueAdmitted(long executionId,Instant now,MedicationExecutionRepository.IntervalAdmission admission) {
        admission.check(executionId);
        applyDue(executionId,now,admission);
    }
    private void applyDue(long executionId,Instant now,MedicationExecutionRepository.IntervalAdmission admission) {
        mutationTransaction(jdbc);
        Map<String,Object> e=required(executionId);
        long patient=id(e.get("patient_id")),owner=auth.owner(patient);
        if(admission==null)repo.lockInterval(owner,patient,id(e.get("medication_id")),null,null,null);
        else admission.check(executionId);
        e=required(executionId);
        if(e.get("pending_reconciliation_id")==null)return;
        Map<String,Object> p=repo.reconciliation(id(e.get("pending_reconciliation_id")));
        if(!"SCHEDULED".equals(p.get("state")))return;
        Map<String,Object> v=repo.revision(id(p.get("scheduled_revision_id")));
        Instant effective=at(v.get("effective_at"));
        if(effective.isAfter(now))return;
        String blocked=null;
        Map<String,Object> saved=object((String)p.get("replacement_json"));
        if(!properties.isEnabled())blocked="FEATURE_DISABLED_BOUNDARY_MISSED";
        else try {
            verified(id(p.get("scheduled_by")),patient,p,v);
            if(!hash(json(saved)).equals(p.get("replacement_hash"))||!json(preview(e,p,v,id(saved.get("executionVersion")))).equals(json(saved)))blocked="REPLACEMENT_CHANGED";
            assertSafeFuture(executionId,effective);
        }
        catch(WorkflowException ex) {
            blocked=ex.getErrorCode();
        }
        if(blocked!=null) {
            repo.update("UPDATE medication_reconciliation SET state='TIME_UNRESOLVED',updated_at=? WHERE id=?",now,p.get("id"));
            long version=advance(executionId,now);
            events.appendSystem(patient,executionId,version,((Number)p.get("proposal_no")).intValue(),"TIME_UNRESOLVED",id(p.get("id")),id(v.get("id")),blocked,map("effectiveAt",effective.toString()),null,null,now);
            return;
        }
        // Lock affected future rows by ascending ID, within the same finite admission ceiling.
        List<Map<String,Object>> future=repo.rows("SELECT id FROM medication_intake WHERE execution_id=? AND reconciliation_id IS NOT NULL AND scheduled_at_utc>=? AND status IN ('PENDING','SNOOZED','MISSED') ORDER BY id LIMIT 51",executionId,effective);
        if(future.size()>50)throw MedicationExecutionRepository.admissionLimit();
        for(Object order:(List<?>)saved.get("sortedOrderIds"))repo.update("UPDATE care_item SET status='STOPPED',updated_at=? WHERE id=? AND medication_execution_id=?",now,order,executionId);
        for(Object reminder:(List<?>)saved.get("sortedReminderIds"))repo.update("UPDATE medication_reminder SET enabled=0,updated_at=? WHERE id=? AND execution_id=?",now,reminder,executionId);
        for(Map<String,Object> i:future)repo.update("UPDATE medication_intake SET status='CANCELLED',reason='BOUNDARY_REPLACED',follow_up_state='NOT_APPLICABLE',follow_up_due_at=NULL,snooze_until_utc=NULL,updated_at=? WHERE id=?",now,i.get("id"));
        if(e.get("current_reconciliation_id")!=null)repo.update("UPDATE medication_reconciliation SET state='SUPERSEDED',ended_at=?,updated_at=? WHERE id=? AND state='ACTIVE'",effective,now,e.get("current_reconciliation_id"));
        StructuredMapping mapping=parseMapping(object((String)v.get("mapping_json")));
        long reconciliation=id(p.get("id"));
        long order=repo.insert("INSERT INTO care_item(patient_id,user_id,kind,title,status,actor_id,event_at,data_json,medication_workflow_version,medication_execution_id,medication_reconciliation_id) VALUES(?,?,'ORDER',?,?,?, ?,?,1,?,?)",patient,owner,"Verified medication interval",mapping.action.equals("STOP")?"STOPPED":"ACTIVE",p.get("scheduled_by"),mapping.effectiveLocal,json(map("medicationId",e.get("medication_id"),"action",mapping.action,"revisionId",v.get("id"))),executionId,reconciliation);
        if("CHANGE".equals(mapping.action))for(String time:mapping.times)repo.insert("INSERT INTO medication_reminder(user_id,patient_id,medication_id,remind_time,repeat_days,dosage,enabled,workflow_version,execution_id,reconciliation_id,order_id,planned_quantity,planned_unit,schedule_zone,effective_at_utc,end_at_utc) VALUES(?,?,?,?,?,?,1,1,?,?,?,?,?,?,?,?)",owner,patient,e.get("medication_id"),time,mapping.weekdays.stream().map(Object::toString).collect(Collectors.joining(",")),mapping.quantity+" "+mapping.unit,executionId,reconciliation,order,mapping.quantity,mapping.unit,mapping.scheduleZone,mapping.effectiveAt,mapping.endAt);
        repo.update("UPDATE medication_reconciliation SET state='ACTIVE',applied_at=?,updated_at=? WHERE id=?",now,now,reconciliation);
        repo.update("UPDATE medication_execution SET current_reconciliation_id=?,pending_reconciliation_id=NULL,schedule_zone=?,generated_through=NULL,review_required=0 WHERE id=?",reconciliation,mapping.scheduleZone,executionId);
        long version=advance(executionId,now);
        events.appendSystem(patient,executionId,version,((Number)p.get("proposal_no")).intValue(),"BOUNDARY_APPLIED",reconciliation,id(v.get("id")),null,map("effectiveAt",effective.toString(),"orderId",order),null,null,now);
        if("STOP".equals(mapping.action))end(e,p,v,effective,now);
    }
    public void endDueLocked(long executionId,Instant now) {
        endDue(executionId,now,null);
    }
    void endDueAdmitted(long executionId,Instant now,MedicationExecutionRepository.IntervalAdmission admission) {
        admission.check(executionId);
        endDue(executionId,now,admission);
    }
    private void endDue(long executionId,Instant now,MedicationExecutionRepository.IntervalAdmission admission) {
        mutationTransaction(jdbc);
        Map<String,Object> e=required(executionId);
        long patient=id(e.get("patient_id"));
        if(admission==null)repo.lockInterval(auth.owner(patient),patient,id(e.get("medication_id")),null,null,null);
        else admission.check(executionId);
        e=required(executionId);
        if(e.get("current_reconciliation_id")==null)return;
        Map<String,Object> p=repo.reconciliation(id(e.get("current_reconciliation_id")));
        if(!"ACTIVE".equals(p.get("state")))return;
        Map<String,Object> v=repo.revision(id(p.get("scheduled_revision_id")));
        Instant end=at(v.get("end_at"));
        if(end!=null&&!end.isAfter(now))end(e,p,v,end,now);
    }
    private void end(Map<String,Object> e,Map<String,Object> p,Map<String,Object> v,Instant ending,Instant now) {
        long execution=id(e.get("id"));
        repo.update("UPDATE care_item SET status='STOPPED',updated_at=? WHERE medication_reconciliation_id=? AND medication_execution_id=? AND status='ACTIVE'",now,p.get("id"),execution);
        repo.update("UPDATE medication_reminder SET enabled=0,updated_at=? WHERE reconciliation_id=? AND execution_id=?",now,p.get("id"),execution);
        repo.update("UPDATE medication_reconciliation SET state='ENDED',ended_at=?,updated_at=? WHERE id=? AND state='ACTIVE'",ending,now,p.get("id"));
        long version=advance(execution,now);
        events.appendSystem(id(e.get("patient_id")),execution,version,((Number)p.get("proposal_no")).intValue(),"INTERVAL_ENDED",id(p.get("id")),id(v.get("id")),null,map("endAt",ending.toString()),null,null,now);
    }
    private Map<String,Object> preview(Map<String,Object> e,Map<String,Object> p,Map<String,Object> v,long version) {
        List<Map<String,Object>> orders=repo.rows("SELECT id,status,medication_reconciliation_id,data_json FROM care_item WHERE medication_execution_id=? AND status IN ('SCHEDULED','ACTIVE') ORDER BY id LIMIT 51",e.get("id"));
        List<Map<String,Object>> reminders=repo.rows("SELECT id,workflow_version,execution_id,reconciliation_id,order_id,remind_time,repeat_days,dosage,enabled,planned_quantity,planned_unit,schedule_zone FROM medication_reminder WHERE patient_id=? AND medication_id=? AND (workflow_version=0 OR enabled=1) ORDER BY id LIMIT 51",e.get("patient_id"),e.get("medication_id"));
        if(orders.size()+reminders.size()>40)throw MedicationExecutionRepository.admissionLimit();
        List<Long> orderIds=new ArrayList<>(),reminderIds=new ArrayList<>();
        for(Map<String,Object> row:orders)orderIds.add(id(row.get("id")));
        for(Map<String,Object> row:reminders)if(Objects.equals(row.get("execution_id"),e.get("id"))&&((Number)row.get("enabled")).intValue()==1)reminderIds.add(id(row.get("id")));
        Map<String,Object> result=map("executionVersion",version,"reconciliationId",p.get("id"),"revisionId",v.get("id"),"revisionHash",v.get("revision_hash"),"effectiveAt",at(v.get("effective_at")).toString(),"sortedOrderIds",orderIds,"sortedReminderIds",reminderIds);
        result.put("fingerprint",hash(json(map("binding",result,"orders",orders,"reminders",reminders))));
        return result;
    }
    private void assertSafeFuture(long execution,Instant boundary) {
        if(repo.one("SELECT id FROM medication_intake WHERE execution_id=? AND reconciliation_id IS NOT NULL AND scheduled_at_utc>=? AND status IN ('TAKEN','SKIPPED') LIMIT 1",execution,boundary)!=null)throw conflict("FUTURE_FACT_BLOCKS_REPLACEMENT");
    }
    private void verified(long actor,long patient,Map<String,Object> p,Map<String,Object> v) {
        auth.requireWrite(actor,patient);
        auth.requireSelectedDoctor(id(p.get("verified_by")),patient,id(p.get("selected_verifier_id")));
        if(!Objects.equals(p.get("verified_revision_id"),v.get("id"))||!Objects.equals(p.get("current_revision_id"),v.get("id")))throw conflict("REVISION_CHANGED");
        repo.requireRevisionLinks(v,patient);
        source(actor,patient,v,true);
    }
    private void source(long actor,long patient,Map<String,Object> v,boolean active) {
        if("PRESCRIPTION".equals(v.get("source_kind"))) {
            auth.requireSource(actor,patient,id(v.get("prescription_id")),id(v.get("prescription_item_id")),((Number)v.get("source_version")).intValue());
            repo.sourceSnapshot(patient,id(v.get("prescription_id")),id(v.get("prescription_item_id")),((Number)v.get("source_version")).intValue(),active);
        }
    }
    private Map<String,Object> current(long actor,long execution,long reconciliation) {
        Map<String,Object> e=required(execution);
        long patient=id(e.get("patient_id"));
        auth.requireWrite(actor,patient);
        Map<String,Object> p=repo.reconciliation(reconciliation);
        if(p==null||id(p.get("execution_id"))!=execution||id(p.get("patient_id"))!=patient)throw MedicationExecutionAuthorizationService.denied();
        source(actor,patient,repo.revision(id(p.get("current_revision_id"))),false);
        return e;
    }
    private Map<String,Object> required(long execution) {
        Map<String,Object> e=repo.execution(execution);
        if(e==null)throw MedicationExecutionAuthorizationService.denied();
        return e;
    }
    private long advance(long execution,Instant now) {
        repo.update("UPDATE medication_execution SET lock_version=lock_version+1,updated_at=? WHERE id=?",now,execution);
        return id(repo.execution(execution).get("lock_version"));
    }
    static Instant at(Object x) {
        return x==null?null:x instanceof Timestamp?((Timestamp)x).toInstant():x instanceof LocalDateTime?((LocalDateTime)x).toInstant(ZoneOffset.UTC):Instant.parse(x.toString());
    }
}
