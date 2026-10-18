package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionRepository.conflict;
/** Human transcription and exact current-clinician verification only. READY does not create an ORDER or reminders. */
@Service
public class MedicationExecutionService {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionRepository repo;
    private final MedicationExecutionEventStore events;
    public MedicationExecutionService(JdbcTemplate jdbc,MedicationExecutionProperties properties,MedicationExecutionAuthorizationService auth,MedicationExecutionRepository repo,MedicationExecutionEventStore events) {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.events=events;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,Long executionId,ReconciliationCommand command) {
        mutationTransaction(jdbc);
        long patient=command.meta.patientId;
        auth.requireWrite(actor,patient);
        boolean create="CREATE_RECONCILIATION".equals(command.operation);
        if(create?(executionId!=null||command.meta.expectedVersion!=0):executionId==null)throw WorkflowException.invalid("Use a null target and version zero only for initial creation.");
        Map<String,Object> discovered=executionId==null?null:repo.execution(executionId);
        if(executionId!=null&&(discovered==null||id(discovered.get("patient_id"))!=patient))throw MedicationExecutionAuthorizationService.denied();
        long medication=create?command.medicationId:id(discovered.get("medication_id"));
        if(command.medicationId!=null&&command.medicationId!=medication)throw MedicationExecutionAuthorizationService.denied();
        repo.lockCommand(actor,patient,medication,command);
        requireCurrentCommandAuthority(actor,executionId,command);
        Map<String,Object> result=WorkflowCommandExecutor.execute(jdbc,WorkflowCommandExecutor.Domain.MEDICATION_EXECUTION,actor,command.meta.commandKey,command.payload(executionId),executionId,command.meta.expectedVersion,properties.now(),()-> {
            if(!properties.isEnabled()&&!"CANCEL_RECONCILIATION".equals(command.operation))throw new WorkflowException(503,"FEATURE_DISABLED","Medication verification is disabled.");
            requireCurrentCommandAuthority(actor,executionId,command);
            Map<String,Object> execution;
            if(create) {
                Map<String,Object> winner=repo.one("SELECT id FROM medication_execution WHERE patient_id=? AND medication_id=?",patient,medication);
                if(winner!=null)throw new WorkflowException(409,"EXECUTION_EXISTS","This medicine is already tracked. Open its current detail and explicitly start a new reconciliation.");
                long id=repo.insert("INSERT INTO medication_execution(patient_id,medication_id,provenance,created_by,created_at,updated_at) VALUES(?,?,'PROFESSIONAL',?,?,?)",patient,medication,actor,properties.now(),properties.now());
                repo.update("UPDATE medication SET workflow_version=1,execution_id=? WHERE id=? AND patient_id=? AND workflow_version=0 AND execution_id IS NULL",id,medication,patient);
                execution=repo.execution(id);
            }
            else execution=repo.execution(executionId);
            if(id(execution.get("lock_version"))!=command.meta.expectedVersion)throw conflict("VERSION_CONFLICT");
            long target=id(execution.get("id"));
            Map<String,Object> proposal;
            if(command.source!=null) {
                if(command.selectedVerifierId!=null)auth.requireClinical(command.selectedVerifierId,patient);
                Map<String,Object> source=command.source.prescriptionId==null?command.source.values():repo.sourceSnapshot(patient,command.source.prescriptionId,command.source.itemId,command.source.version,true);
                if("REVISE_RECONCILIATION".equals(command.operation)) {
                    proposal=proposal(command,target,patient);
                    if(!Arrays.asList("DRAFT","READY","RETURNED","SOURCE_CHANGED","REPLACEMENT_CHANGED","TIME_UNRESOLVED").contains(proposal.get("state")))throw conflict("STATE_CONFLICT");
                }
                else {
                    Long previous=number(execution.get("pending_reconciliation_id"));
                    if(previous!=null) {
                        Map<String,Object> old=repo.reconciliation(previous);
                        if(Arrays.asList("SCHEDULED","ACTIVE").contains(old.get("state")))throw conflict("PENDING_APPLICATION");
                        repo.update("UPDATE medication_reconciliation SET state='CANCELLED',updated_at=? WHERE id=?",properties.now(),previous);
                    }
                    Map<String,Object> lastProposal=repo.one("SELECT proposal_no FROM medication_reconciliation WHERE execution_id=? ORDER BY proposal_no DESC LIMIT 1",target);
                    int cycle=lastProposal==null?1:((Number)lastProposal.get("proposal_no")).intValue()+1;
                    long id=repo.insert("INSERT INTO medication_reconciliation(execution_id,patient_id,proposal_no,state,selected_verifier_id,created_by,created_at,updated_at) VALUES(?,?,?,'DRAFT',?,?,?,?)",target,patient,cycle,command.selectedVerifierId,actor,properties.now(),properties.now());
                    proposal=repo.reconciliation(id);
                }
                long proposalId=id(proposal.get("id"));
                Map<String,Object> lastRevision=repo.one("SELECT revision_no FROM medication_reconciliation_revision WHERE reconciliation_id=? ORDER BY revision_no DESC LIMIT 1",proposalId);
                int no=lastRevision==null?1:((Number)lastRevision.get("revision_no")).intValue()+1;
                String sourceJson=json(source),mappingJson=json(command.mapping.values()),sourceHash=hash(sourceJson),mappingHash=hash(mappingJson),revisionHash=hash(json(map("sourceHash",sourceHash,"mappingHash",mappingHash,"revisionNo",no,"transcribedBy",actor)));
                StructuredMapping m=command.mapping;
                long revision=repo.insert("INSERT INTO medication_reconciliation_revision(reconciliation_id,execution_id,patient_id,revision_no,source_kind,prescription_id,prescription_item_id,source_version,source_json,source_hash,mapping_json,mapping_hash,action,schedule_zone,effective_local,effective_offset_seconds,effective_at,end_local,end_offset_seconds,end_at,revision_hash,transcribed_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",proposalId,target,patient,no,command.source.kind,command.source.prescriptionId,command.source.itemId,command.source.version,sourceJson,sourceHash,mappingJson,mappingHash,m.action,m.scheduleZone,m.effectiveLocal,m.effectiveOffsetSeconds,m.effectiveAt,m.endLocal,m.endOffsetSeconds,m.endAt,revisionHash,actor,properties.now());
                if(command.source.prescriptionId!=null) {
                    repo.update("UPDATE electronic_prescription SET workflow_version=1 WHERE id=? AND patient_id=?",command.source.prescriptionId,patient);
                    repo.update("UPDATE electronic_prescription_item SET workflow_version=1 WHERE id=? AND prescription_id=?",command.source.itemId,command.source.prescriptionId);
                }
                repo.update("UPDATE medication_reconciliation SET state='DRAFT',current_revision_id=?,selected_verifier_id=?,verified_revision_id=NULL,verified_by=NULL,verified_at=NULL,updated_at=? WHERE id=?",revision,command.selectedVerifierId,properties.now(),proposalId);
                repo.update("UPDATE medication_execution SET pending_reconciliation_id=? WHERE id=?",proposalId,target);
                proposal=repo.reconciliation(proposalId);
            }
            else {
                proposal=proposal(command,target,patient);
                Map<String,Object> revision=repo.revision(command.revisionId);
                repo.requireRevisionLinks(revision,patient);
                if(!Objects.equals(number(proposal.get("current_revision_id")),command.revisionId)||!Objects.equals(command.revisionHash,revision.get("revision_hash")))throw conflict("REVISION_CHANGED");
                if("VERIFY_RECONCILIATION".equals(command.operation)) {
                    if(!"DRAFT".equals(proposal.get("state")))throw conflict("STATE_CONFLICT");
                    if("PRESCRIPTION".equals(revision.get("source_kind")))repo.sourceSnapshot(patient,id(revision.get("prescription_id")),id(revision.get("prescription_item_id")),((Number)revision.get("source_version")).intValue(),true);
                    repo.update("UPDATE medication_reconciliation SET state='READY',verified_revision_id=?,verified_by=?,verified_at=?,updated_at=? WHERE id=?",command.revisionId,actor,properties.now(),properties.now(),command.reconciliationId);
                }
                else {
                    if(!Arrays.asList("DRAFT","READY","RETURNED","SOURCE_CHANGED","REPLACEMENT_CHANGED","TIME_UNRESOLVED").contains(proposal.get("state")))throw conflict("STATE_CONFLICT");
                    String state="RETURN_RECONCILIATION".equals(command.operation)?"RETURNED":"CANCELLED";
                    repo.update("UPDATE medication_reconciliation SET state=?,updated_at=? WHERE id=?",state,properties.now(),command.reconciliationId);
                    if("CANCELLED".equals(state))repo.update("UPDATE medication_execution SET pending_reconciliation_id=NULL WHERE id=? AND pending_reconciliation_id=?",target,command.reconciliationId);
                }
                proposal=repo.reconciliation(command.reconciliationId);
            }
            long version=command.meta.expectedVersion+1;
            repo.update("UPDATE medication_execution SET lock_version=?,updated_at=? WHERE id=?",version,properties.now(),target);
            long proposalId=id(proposal.get("id")),revisionId=id(proposal.get("current_revision_id"));
            int cycle=((Number)proposal.get("proposal_no")).intValue();
            long event=events.append(actor,patient,target,version,cycle,command.operation,auth.relation(actor,patient),proposalId,revisionId,command.reason,map("revisionHash",repo.revision(revisionId).get("revision_hash")));
            // Exact declared receipt, constructed here before durable persistence. Never pass a detail/source map to the executor.
            return map("executionId",target,"version",version,"eventId",event,"state",proposal.get("state"),"cycle",(long)cycle,"reconciliationId",proposalId,"revisionId",revisionId);
        });
        requireMinimalReceipt(result);
        Map<String,Object> bound=repo.execution(id(result.get("executionId")));
        if(bound==null||id(bound.get("patient_id"))!=patient||id(bound.get("medication_id"))!=medication)throw conflict("RECEIPT_INVALID");
        requireCurrentCommandAuthority(actor,((Number)result.get("executionId")).longValue(),command);
        return result;
    }
    private Map<String,Object> proposal(ReconciliationCommand command,long execution,long patient) {
        Map<String,Object> row=repo.reconciliation(command.reconciliationId);
        if(row==null||id(row.get("execution_id"))!=execution||id(row.get("patient_id"))!=patient)throw MedicationExecutionAuthorizationService.denied();
        return row;
    }
    private void requireCurrentCommandAuthority(long actor,Long execution,ReconciliationCommand command) {
        long patient=command.meta.patientId;
        auth.requireWrite(actor,patient);
        if(command.source!=null&&command.source.prescriptionId!=null)auth.requireSource(actor,patient,command.source.prescriptionId,command.source.itemId,command.source.version);
        if(command.reconciliationId!=null) {
            Map<String,Object> p=proposal(command,execution,patient);
            Map<String,Object> v=repo.revision(id(p.get("current_revision_id")));
            repo.requireRevisionLinks(v,patient);
            if("PRESCRIPTION".equals(v.get("source_kind")))auth.requireSource(actor,patient,id(v.get("prescription_id")),id(v.get("prescription_item_id")),((Number)v.get("source_version")).intValue());
            if(Arrays.asList("VERIFY_RECONCILIATION","RETURN_RECONCILIATION").contains(command.operation))auth.requireSelectedDoctor(actor,patient,number(p.get("selected_verifier_id")));
        }
    }
    private static Long number(Object value) {
        return value==null?null:id(value);
    }
}
