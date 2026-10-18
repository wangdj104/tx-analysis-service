package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** Narrow native publication seam. Always suppress the old clinical notification once a source is tracked. */
@Service
public class MedicationSourceBridge {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionRepository repo;
    private final MedicationExecutionEventStore events;
    private final HouseholdWorkflowSchema schema;
    private final Object publicationKey=new Object();
    public MedicationSourceBridge(JdbcTemplate jdbc,MedicationExecutionProperties properties,MedicationExecutionAuthorizationService auth,MedicationExecutionRepository repo,MedicationExecutionEventStore events,HouseholdWorkflowSchema schema) {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.events=events;
        this.schema=schema;
    }
    /** Complete affected-current discovery and one admission before the native source transaction writes. */
    public boolean preparePublicationLocked(long actor,long patient) {
        mutationTransaction(jdbc);
        final long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        HouseholdWorkflowSchema.StorageReadiness storage=schema.inspectStorageMetadata();
        if(storage.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT&&!properties.isEnabled())return false;
        storage.requireStructurallyReady();
        if(repo.one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw MedicationExecutionAuthorizationService.denied();
        boolean tracked=tracked(patient);
        List<Long> headers=identities(repo.rows("SELECT id FROM electronic_prescription WHERE patient_id=? AND status='ACTIVE' ORDER BY id LIMIT 51",patient),"id");
        // The patient/state index excludes cancelled and superseded proposal history. Each joined version is a typed PK.
        List<Long> medications=tracked?identities(repo.rows("SELECT DISTINCT e.medication_id FROM medication_reconciliation r JOIN medication_execution e ON e.id=r.execution_id AND e.patient_id=r.patient_id WHERE r.patient_id=? AND r.state IN ('DRAFT','READY','RETURNED','SOURCE_CHANGED','REPLACEMENT_CHANGED','TIME_UNRESOLVED','SCHEDULED','ACTIVE') AND (r.id=e.current_reconciliation_id OR r.id=e.pending_reconciliation_id) AND EXISTS(SELECT 1 FROM medication_reconciliation_revision v WHERE v.id IN (r.current_revision_id,r.verified_revision_id,r.scheduled_revision_id) AND v.reconciliation_id=r.id AND v.execution_id=e.id AND v.patient_id=e.patient_id AND v.source_kind='PRESCRIPTION') ORDER BY e.medication_id LIMIT 51",patient),"medication_id"):Collections.emptyList();
        if(headers.size()>50||medications.size()>50)throw MedicationExecutionRepository.admissionLimit();
        repo.lockPublication(actor,patient,medications,headers);
        auth.requireClinical(actor,patient);
        if(tracked) {
            if(TransactionSynchronizationManager.hasResource(publicationKey))throw MedicationExecutionRepository.conflict("PUBLICATION_CONTEXT_CHANGED");
            Publication admission=new Publication(actor,patient,medications,deadline);
            admission.check();
            TransactionSynchronizationManager.bindResource(publicationKey,admission);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(publicationKey);
                }
            });
        }
        return tracked;
    }
    private static List<Long> identities(List<Map<String,Object>> rows,String field) {
        List<Long> ids=new ArrayList<>();
        for(Map<String,Object> row:rows)ids.add(id(row.get(field)));
        return ids;
    }
    private final class Publication {
        final long actor,patient,deadline;
        final List<Long> medications;
        final java.sql.Connection connection;
        final Thread thread=Thread.currentThread();
        boolean consumed;
        Publication(long actor,long patient,List<Long> medications,long deadline) {
            this.actor=actor;
            this.patient=patient;
            this.medications=Collections.unmodifiableList(new ArrayList<>(medications));
            this.deadline=deadline;
            this.connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,jdbc.getDataSource());
        }
        void check() {
            mutationTransaction(jdbc);
            java.sql.Connection current=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());
            try {
                if(thread!=Thread.currentThread()||current!=connection)throw MedicationExecutionRepository.conflict("PUBLICATION_CONTEXT_CHANGED");
            }
            finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(current,jdbc.getDataSource());
            }
            if(System.nanoTime()>deadline)throw new WorkflowException(503,"ADMISSION_TIMEOUT","Current related records could not be checked in time. Refresh and retry; no changes were applied.");
        }
    }
    public void requireCurrentReadIfTracked(long actor,long patient) {
        if(tracked(patient))auth.requireRead(actor,patient);
    }
    public boolean tracked(long patient) {
        HouseholdWorkflowSchema.StorageReadiness storage=schema.inspectStorageMetadata();
        if(storage.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT&&!properties.isEnabled())return false;
        storage.requireStructurallyReady();
        return repo.one("SELECT id FROM electronic_prescription WHERE patient_id=? AND workflow_version=1 LIMIT 1",patient)!=null||repo.one("SELECT id FROM medication_reconciliation_revision WHERE patient_id=? AND source_kind='PRESCRIPTION' LIMIT 1",patient)!=null;
    }
    public void sourcePublishedLocked(long patient,long prescription) {
        mutationTransaction(jdbc);
        Publication admission=(Publication)TransactionSynchronizationManager.getResource(publicationKey);
        Long actor=CurrentUserUtil.getCurrentUserId();
        if(admission==null||admission.consumed||actor==null||admission.actor!=actor||admission.patient!=patient)throw MedicationExecutionRepository.conflict("PUBLICATION_CONTEXT_CHANGED");
        admission.check();
        admission.consumed=true;
        auth.requireClinical(actor,patient);
        Map<String,Object> published=repo.one("SELECT patient_id,doctor_user_id,status FROM electronic_prescription WHERE id=?",prescription);
        if(published==null||id(published.get("patient_id"))!=patient||id(published.get("doctor_user_id"))!=actor||!"ACTIVE".equals(published.get("status")))throw MedicationExecutionAuthorizationService.denied();
        // The complete set and all pre-existing resources are still locked by this same transaction.
        repo.update("UPDATE electronic_prescription SET workflow_version=1 WHERE id=?",prescription);
        repo.update("UPDATE electronic_prescription_item SET workflow_version=1 WHERE prescription_id=?",prescription);
        for(long medication:admission.medications) {
            admission.check();
            Map<String,Object> e=repo.one("SELECT * FROM medication_execution WHERE patient_id=? AND medication_id=?",patient,medication);
            long execution=id(e.get("id"));
            List<Map<String,Object>> affected=new ArrayList<>();
            Set<Long> seen=new HashSet<>();
            for(String pointer:Arrays.asList("pending_reconciliation_id","current_reconciliation_id")) {
                if(e.get(pointer)==null)continue;
                Map<String,Object> p=repo.reconciliation(id(e.get(pointer))),v=repo.revision(id(p.get("current_revision_id")));
                boolean changed=false;
                for(String revisionPointer:Arrays.asList("current_revision_id","verified_revision_id","scheduled_revision_id"))if(p.get(revisionPointer)!=null) {
                    Map<String,Object> relied=repo.revision(id(p.get(revisionPointer)));
                    if("PRESCRIPTION".equals(relied.get("source_kind"))&&id(relied.get("prescription_id"))!=prescription)changed=true;
                }
                if(!changed)continue;
                if(seen.add(id(p.get("id"))))affected.add(p);
                if("pending_reconciliation_id".equals(pointer))repo.update("UPDATE medication_reconciliation SET state='SOURCE_CHANGED',updated_at=? WHERE id=? AND state IN ('DRAFT','READY','RETURNED','SOURCE_CHANGED','REPLACEMENT_CHANGED','TIME_UNRESOLVED','SCHEDULED')",properties.now(),p.get("id"));
                else repo.update("UPDATE medication_execution SET review_required=1 WHERE id=?",execution);
            }
            if(!affected.isEmpty()) {
                long version=id(e.get("lock_version"))+1;
                repo.update("UPDATE medication_execution SET lock_version=?,updated_at=? WHERE id=?",version,properties.now(),execution);
                for(Map<String,Object> eventProposal:affected)events.append(actor,patient,execution,version,((Number)eventProposal.get("proposal_no")).intValue(),"SOURCE_CHANGED",auth.relation(actor,patient),id(eventProposal.get("id")),id(eventProposal.get("current_revision_id")),null,map("publishedPrescriptionId",prescription));
            }
        }
        admission.check();
        auth.requireClinical(actor,patient);
    }
}
