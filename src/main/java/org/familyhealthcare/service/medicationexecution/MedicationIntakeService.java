package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.familyhealthcare.service.stockworkflow.StockWorkflowService;
import org.familyhealthcare.service.stockworkflow.StockWorkflowRepository.PreparedConsumption;
import org.familyhealthcare.service.stockworkflow.StockWorkflowContracts.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionContracts.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionRepository.conflict;
import static org.familyhealthcare.service.medicationexecution.MedicationBoundaryService.at;
/** Human facts and follow-up overlays. Actions are appended before stock settlement, native projection last. */
@Service
public class MedicationIntakeService {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionRepository repo;
    private final MedicationExecutionEventStore events;
    private final StockWorkflowService stock;
    public MedicationIntakeService(JdbcTemplate jdbc,MedicationExecutionProperties properties,MedicationExecutionAuthorizationService auth,MedicationExecutionRepository repo,MedicationExecutionEventStore events,StockWorkflowService stock) {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.events=events;
        this.stock=stock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,long executionId,IntakeCommand command) {
        return execute(actor,executionId,null,command);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> recordPersonalStockFact(long actor,PersonalStockFact fact) {
        return execute(actor,null,fact.medicationId,fact.command);
    }
    private Map<String,Object> execute(long actor,Long execution,Long personalMedication,IntakeCommand c) {
        mutationTransaction(jdbc);
        auth.requireWrite(actor,c.meta.patientId);
        repo.inspectStorageMetadata().requireStructurallyReady();
        Map<String,Object> discovered=repo.one("SELECT patient_id,medication_id FROM medication_intake WHERE id=?",c.intakeId);
        if(discovered==null||id(discovered.get("patient_id"))!=c.meta.patientId)throw MedicationExecutionAuthorizationService.denied();
        long medication=id(discovered.get("medication_id")),patient=c.meta.patientId;
        if(personalMedication!=null&&personalMedication!=medication)throw MedicationExecutionAuthorizationService.denied();
        if(repo.one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw MedicationExecutionAuthorizationService.denied();
        Map<String,Object> recheck=repo.one("SELECT patient_id,medication_id FROM medication_intake WHERE id=?",c.intakeId);
        if(!discovered.equals(recheck))throw conflict("INTAKE_CHANGED");
        Map<String,Object> existing=execution==null?null:repo.execution(execution);
        if(execution!=null&&(existing==null||id(existing.get("patient_id"))!=patient||id(existing.get("medication_id"))!=medication))throw MedicationExecutionAuthorizationService.denied();
        Map<String,Object> replay=repo.one("SELECT id FROM medication_execution_command WHERE actor_id=? AND command_key=?",actor,c.meta.commandKey);
        Map<String,Object> nativeStock=repo.one("SELECT id FROM medication_stock WHERE patient_id=? AND medication_id=?",patient,medication);
        PreparedConsumption prepared=replay==null&&c.actualAt!=null&&nativeStock!=null?stock.prepareIntakeConsumption(actor,patient,id(nativeStock.get("id")),c.intakeId,c.originalActionId,c.countId):null;
        repo.lockIntake(actor,patient,medication,c.meta.commandKey,c.intakeId,c.originalActionId,prepared);
        current(actor,execution,patient,medication,c);
        String payload=personalMedication==null?c.payload(execution):json(map("personalMedicationId",personalMedication,"command",object(c.payload(null))));
        Map<String,Object> result=WorkflowCommandExecutor.execute(jdbc,WorkflowCommandExecutor.Domain.MEDICATION_EXECUTION,actor,c.meta.commandKey,payload,execution,c.meta.expectedVersion,properties.now(),()-> {
            current(actor,execution,patient,medication,c);
            long target;
            if(execution==null) {
                if(c.meta.expectedVersion!=0)throw conflict("VERSION_CONFLICT");
                if(repo.one("SELECT id FROM medication_execution WHERE patient_id=? AND medication_id=?",patient,medication)!=null)throw conflict("EXECUTION_EXISTS");
                target=repo.insert("INSERT INTO medication_execution(patient_id,medication_id,provenance,created_by,created_at,updated_at) VALUES(?,?,'PERSONAL_UNVERIFIED',?,?,?)",patient,medication,actor,properties.now(),properties.now());
                repo.update("UPDATE medication SET workflow_version=1,execution_id=? WHERE id=? AND patient_id=?",target,medication,patient);
            }
            else {
                target=execution;
                if(id(repo.execution(target).get("lock_version"))!=c.meta.expectedVersion)throw conflict("VERSION_CONFLICT");
            }
            Map<String,Object> intake=repo.one("SELECT * FROM medication_intake WHERE id=?",c.intakeId);
            if(intake.get("execution_id")!=null&&id(intake.get("execution_id"))!=target)throw MedicationExecutionAuthorizationService.denied();
            if(id(intake.get("workflow_version"))==0) {
                // Preserve every old native byte, including local timestamps and ON UPDATE updated_at.
                repo.update("UPDATE medication_intake SET workflow_version=1,execution_id=?,provenance='PERSONAL_UNVERIFIED',follow_up_state=?,follow_up_kind='FOLLOW_UP_ONLY',follow_up_cycle=1,updated_at=updated_at WHERE id=? AND workflow_version=0",target,Arrays.asList("TAKEN","SKIPPED").contains(intake.get("status"))?"FACT_RECORDED":"OPEN",c.intakeId);
                intake=repo.one("SELECT * FROM medication_intake WHERE id=?",c.intakeId);
            }
            long version=c.meta.expectedVersion+1;
            int cycle=((Number)intake.get("follow_up_cycle")).intValue();
            Long reconciliation=number(intake.get("reconciliation_id")),revision=reconciliation==null?null:number(repo.reconciliation(reconciliation).get("scheduled_revision_id"));
            Long action=null;
            long event;
            ConsumptionResult consumed=new ConsumptionResult("NOT_APPLICABLE",null,null,null,null,null);
            if(c.actualAt!=null) {
                boolean correction="CORRECT_INTAKE".equals(c.operation);
                if("CANCELLED".equals(intake.get("status")))throw conflict("BOUNDARY_NOT_APPLICABLE");
                Map<String,Object> latest=repo.one("SELECT id FROM care_intake_action WHERE intake_id=? AND patient_id=? AND status IN ('TAKEN','SKIPPED') ORDER BY id DESC LIMIT 1",c.intakeId,patient);
                if(correction) {
                    if(latest==null||id(latest.get("id"))!=c.originalActionId)throw conflict("ACTION_CHANGED");
                }
                else if(latest!=null||Arrays.asList("TAKEN","SKIPPED").contains(intake.get("status")))throw conflict("GENUINE_FACT_CORRECTION_REQUIRED");
                BigDecimal quantity=c.quantity;
                String unit=c.unit;
                if("CONFIRMED_PLAN".equals(c.amountBasis)) {
                    if(!"VERIFIED_ORDER".equals(intake.get("provenance"))||intake.get("planned_quantity")==null||intake.get("planned_unit")==null)throw conflict("FROZEN_PLAN_REQUIRED");
                    quantity=new BigDecimal(intake.get("planned_quantity").toString());
                    unit=(String)intake.get("planned_unit");
                }
                Map<String,Object> provenance=map("attribution",c.attribution,"amountBasis",c.amountBasis,"historicalUnit",c.historicalUnit,"countChoice",c.countChoice,"countId",c.countId,"countVersion",c.countVersion);
                MedicationExecutionRepository.InsertedAction inserted=repo.insertAction("INSERT INTO care_intake_action(patient_id,intake_id,actor_id,status,recorded_for,quantity,workflow_version,execution_id,original_action_id,action_kind,actual_at_utc,recorded_at_utc,unit,amount_basis,correction_reason,provenance_json) VALUES(?,?,?,?,?,?,1,?,?,?,?,?,?,?,?,?)",patient,c.intakeId,actor,c.status,c.attribution,quantity,target,c.originalActionId,correction?"CORRECTION":"FACT",c.actualAt,properties.now(),unit,c.amountBasis,c.reason,json(provenance));
                action=inserted.id();
                if(prepared!=null)stock.registerPreparedAction(prepared,inserted);
                event=events.appendFact(actor,patient,target,version,cycle,c.operation,c.attribution,reconciliation,revision,c.reason,map("actualAt",c.actualAt.toString(),"amountBasis",c.amountBasis,"originalActionId",c.originalActionId),c.intakeId,action);
                repo.update("UPDATE care_intake_action SET execution_event_id=? WHERE id=? AND execution_event_id IS NULL",event,action);
                RecordedIntakeAction recorded=new RecordedIntakeAction(action,c.intakeId,event,patient,medication,actor,c.actualAt,quantity,unit,c.attribution);
                if(prepared!=null) {
                    stock.registerPreparedEvent(prepared,action,event);
                    consumed=correction?stock.correctConsumptionLocked(actor,id(nativeStock.get("id")),new IntakeCorrection(recorded,c.originalActionId,action,c.meta.commandKey,c.reason,c.countChoice,c.countId,c.countVersion,c.historicalUnit)):stock.recordConsumptionLocked(actor,id(nativeStock.get("id")),recorded);
                }
                else consumed=new ConsumptionResult("UNRECONCILED",null,null,null,null,"NO_STOCK_RECORD");
                // Stock sees the old native projection until correction settles; any failure rolls everything back.
                repo.update("UPDATE medication_intake SET status=?,actual_at_utc=?,latest_action_id=?,follow_up_state='FACT_RECORDED',follow_up_due_at=NULL,snooze_until_utc=NULL,updated_at=? WHERE id=?",c.status,c.actualAt,action,properties.now(),c.intakeId);
            }
            else {
                String follow=(String)intake.get("follow_up_state"),kind=(String)intake.get("follow_up_kind");
                switch(c.operation) {
                    case "SNOOZE":if(!"OPEN".equals(follow)||!"DOSE_REMINDER".equals(kind)||!Arrays.asList("PENDING","SNOOZED","MISSED").contains(intake.get("status")))throw conflict("DOSE_REMINDER_REQUIRED");
                    repo.update("UPDATE medication_intake SET status='SNOOZED',snooze_until_utc=?,follow_up_due_at=?,updated_at=? WHERE id=?",properties.now().plusSeconds(900),properties.now().plusSeconds(900),properties.now(),c.intakeId);
                    break;
                    case "END_INTAKE_UNRESOLVED":Instant due=at(intake.get("follow_up_due_at"));
                    if(!"OPEN".equals(follow)||due==null||due.plusSeconds(3600).isAfter(properties.now())||!Arrays.asList("PENDING","SNOOZED","MISSED").contains(intake.get("status")))throw conflict("OVERDUE_UNCONFIRMED_REQUIRED");
                    repo.update("UPDATE medication_intake SET follow_up_state='ENDED_UNRESOLVED',follow_up_due_at=NULL,updated_at=? WHERE id=?",properties.now(),c.intakeId);
                    break;
                    case "REOPEN_INTAKE_FOLLOW_UP":if(!"ENDED_UNRESOLVED".equals(follow))throw conflict("ENDED_UNRESOLVED_REQUIRED");
                    cycle++;
                    repo.update("UPDATE medication_intake SET follow_up_state='OPEN',follow_up_kind='FOLLOW_UP_ONLY',follow_up_cycle=?,follow_up_due_at=?,snooze_until_utc=NULL,updated_at=? WHERE id=?",cycle,properties.now(),properties.now(),c.intakeId);
                    break;
                    case "RECORD_CONTACT":if("NOT_APPLICABLE".equals(follow))throw conflict("BOUNDARY_NOT_APPLICABLE");
                    break;
                    default:throw WorkflowException.invalid("Unsupported intake operation.");
                }
                event=events.appendFact(actor,patient,target,version,cycle,c.operation,auth.relation(actor,patient),reconciliation,revision,c.reason,map("reasonCode",c.reasonCode,"previousFollowUpState",follow,"previousFollowUpKind",kind),c.intakeId,null);
            }
            repo.update("UPDATE medication_execution SET lock_version=?,updated_at=? WHERE id=?",version,properties.now(),target);
            Map<String,Object> updated=repo.one("SELECT status,follow_up_state FROM medication_intake WHERE id=?",c.intakeId);
            return map("executionId",target,"version",version,"eventId",event,"state",updated.get("status"),"cycle",cycle,"intakeId",c.intakeId,"actionId",action,"followUpState",updated.get("follow_up_state"),"stockOutcome",consumed.outcome,"resolutionId",consumed.resolutionId,"stockEventId",consumed.stockEventId,"originalMovementId",consumed.originalMovementId,"compensationMovementId",consumed.compensationMovementId);
        });
        requireMinimalReceipt(result);
        current(actor,id(result.get("executionId")),patient,medication,c);
        return result;
    }
    private void current(long actor,Long execution,long patient,long medication,IntakeCommand c) {
        auth.requireWrite(actor,patient);
        if(c.actualAt!=null&&c.actualAt.isAfter(properties.now()))throw WorkflowException.invalid("The actual time cannot be in the future.");
        if("SELF".equals(c.attribution)&&actor!=auth.owner(patient))throw MedicationExecutionAuthorizationService.denied();
        Map<String,Object> i=repo.one("SELECT * FROM medication_intake WHERE id=?",c.intakeId);
        if(i==null||id(i.get("patient_id"))!=patient||id(i.get("medication_id"))!=medication||execution!=null&&i.get("execution_id")!=null&&id(i.get("execution_id"))!=execution)throw MedicationExecutionAuthorizationService.denied();
        if(execution!=null) {
            Map<String,Object> e=repo.execution(execution);
            if(e==null||id(e.get("patient_id"))!=patient||id(e.get("medication_id"))!=medication)throw MedicationExecutionAuthorizationService.denied();
        }
        if(i.get("reconciliation_id")!=null) {
            Map<String,Object> p=repo.reconciliation(id(i.get("reconciliation_id"))),v=repo.revision(id(p.get("scheduled_revision_id")));
            repo.requireRevisionLinks(v,patient);
            if("PRESCRIPTION".equals(v.get("source_kind")))auth.requireSource(actor,patient,id(v.get("prescription_id")),id(v.get("prescription_item_id")),((Number)v.get("source_version")).intValue());
        }
    }
    private static Long number(Object value) {
        return value==null?null:id(value);
    }
}
