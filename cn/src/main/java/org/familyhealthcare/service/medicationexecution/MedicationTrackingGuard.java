package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** Each native writer discovers a parent, locks it, then re-reads immutable identity and current authority. */
@Service
public class MedicationTrackingGuard {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionRepository repo;
    private final MedicationExecutionEventStore events;
    public MedicationTrackingGuard(JdbcTemplate jdbc,MedicationExecutionProperties properties,MedicationExecutionAuthorizationService auth,MedicationExecutionRepository repo,MedicationExecutionEventStore events) {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.events=events;
    }
    public boolean storageAvailable() {
        HouseholdWorkflowSchema.StorageReadiness s=repo.inspectStorageMetadata();
        if(s.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT&&!properties.isEnabled())return false;
        s.requireStructurallyReady();
        return true;
    }
    /** 一次旧页面读取仅做一次结构检查；药品身份分批主键读取，不缓存当前业务授权。 */
    public LegacyRead legacyRead(long patient) {
        return new LegacyRead(patient,storageAvailable());
    }
    public final class LegacyRead {
        private final long patient;
        private final boolean modern;
        private final Map<Long,Boolean> tracked=new HashMap<>();
        private final Map<Long,Map<String,Object>> medicineRows=new HashMap<>();
        private Map<Long,Map<String,Object>> currentIntakes=Collections.emptyMap();
        private boolean disclosedTracked;
        public void finish(long actor){if(disclosedTracked)auth.requireRead(actor,patient);}
        private LegacyRead(long patient,boolean modern) {this.patient=patient;this.modern=modern;}
        private void discover(Collection<Long> ids) {
            if(!modern)return;
            tracked.clear();medicineRows.clear();
            List<Long> missing=new ArrayList<>();
            for(Long id:new LinkedHashSet<>(ids))if(id!=null&&!tracked.containsKey(id))missing.add(id);
            for(int start=0;start<missing.size();start+=100) {
                List<Long> batch=missing.subList(start,Math.min(start+100,missing.size()));
                List<Object> args=new ArrayList<>();args.add(patient);args.addAll(batch);
                // 缺失或跨患者行不能借调用方旧实体降级为未跟踪数据。
                for(Long id:batch)tracked.put(id,true);
                for(Map<String,Object> row:repo.rows("SELECT m.id,m.drug_name,m.workflow_version,m.execution_id,CASE WHEN m.workflow_version<>0 OR m.execution_id IS NOT NULL OR EXISTS(SELECT 1 FROM medication_stock s WHERE s.patient_id=m.patient_id AND s.medication_id=m.id AND (s.workflow_version<>0 OR s.current_work_id IS NOT NULL)) OR EXISTS(SELECT 1 FROM medication_intake i WHERE i.patient_id=m.patient_id AND i.medication_id=m.id AND (i.workflow_version<>0 OR i.execution_id IS NOT NULL OR i.reconciliation_id IS NOT NULL)) THEN 1 ELSE 0 END AS tracked FROM medication m WHERE m.patient_id=? AND m.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray())){tracked.put(id(row.get("id")),id(row.get("tracked"))!=0);medicineRows.put(id(row.get("id")),row);}
            }
        }
        public List<org.familyhealthcare.entity.Medication> untrackedMedications(List<org.familyhealthcare.entity.Medication> rows) {
            List<Long> ids=new ArrayList<>();for(org.familyhealthcare.entity.Medication row:rows)ids.add(row.getId());discover(ids);
            List<org.familyhealthcare.entity.Medication> out=new ArrayList<>();for(org.familyhealthcare.entity.Medication row:rows)if(Objects.equals(row.getPatientId(),patient)&&(!modern||Boolean.FALSE.equals(tracked.get(row.getId()))))out.add(row);return out;
        }
        public List<org.familyhealthcare.entity.MedicationIntake> untrackedIntakes(List<org.familyhealthcare.entity.MedicationIntake> rows) {
            if(!modern){List<org.familyhealthcare.entity.MedicationIntake> out=new ArrayList<>();for(org.familyhealthcare.entity.MedicationIntake row:rows)if(Objects.equals(row.getPatientId(),patient))out.add(row);return out;}
            currentIntakes=intakeRows(rows);List<Long> ids=new ArrayList<>();for(Map<String,Object> row:currentIntakes.values())ids.add(id(row.get("medication_id")));discover(ids);
            List<org.familyhealthcare.entity.MedicationIntake> out=new ArrayList<>();for(org.familyhealthcare.entity.MedicationIntake row:rows){Map<String,Object> current=currentIntakes.get(row.getId());if(Objects.equals(row.getPatientId(),patient)&&current!=null&&Objects.equals(row.getMedicationId(),number(current.get("medication_id")))&&Boolean.FALSE.equals(tracked.get(row.getMedicationId())))out.add(row);}return out;
        }
        public List<org.familyhealthcare.entity.Medication> medications(long actor,List<org.familyhealthcare.entity.Medication> rows) {
            List<org.familyhealthcare.entity.Medication> old=untrackedMedications(rows);
            if(!modern||!auth.canRead(actor,patient))return old;
            List<org.familyhealthcare.entity.Medication> out=new ArrayList<>();
            for(org.familyhealthcare.entity.Medication row:rows)if(Objects.equals(row.getPatientId(),patient)&&medicineRows.containsKey(row.getId())) {
                Map<String,Object> current=medicineRows.get(row.getId());row.setWorkflowVersion(((Number)current.get("workflow_version")).intValue());row.setExecutionId(number(current.get("execution_id")));if(Boolean.TRUE.equals(tracked.get(row.getId())))disclosedTracked=true;out.add(row);
            }
            auth.requireRead(actor,patient);return out;
        }
        public List<org.familyhealthcare.entity.MedicationIntake> intakes(long actor,List<org.familyhealthcare.entity.MedicationIntake> rows) {
            List<org.familyhealthcare.entity.MedicationIntake> old=untrackedIntakes(rows);
            if(!modern||!auth.canRead(actor,patient))return old;
            Map<Long,Map<String,Object>> current=currentIntakes;List<org.familyhealthcare.entity.MedicationIntake> out=new ArrayList<>();
            for(org.familyhealthcare.entity.MedicationIntake row:rows) {
                Map<String,Object> i=current.get(row.getId());if(i==null||!Objects.equals(row.getPatientId(),patient)||!Objects.equals(row.getMedicationId(),number(i.get("medication_id"))))continue;
                row.setStatus((String)i.get("status"));row.setWorkflowVersion(((Number)i.get("workflow_version")).intValue());
                Map<String,Object> medicine=medicineRows.get(row.getMedicationId());
                row.setExecutionId(number(i.get("execution_id"))==null?medicine==null?null:number(medicine.get("execution_id")):number(i.get("execution_id")));
                row.setReconciliationId(number(i.get("reconciliation_id")));row.setProvenance((String)i.get("provenance"));row.setFollowUpState((String)i.get("follow_up_state"));row.setFollowUpKind((String)i.get("follow_up_kind"));row.setFollowUpCycle(((Number)i.get("follow_up_cycle")).intValue());
                row.setScheduledAtUtc(MedicationBoundaryService.at(i.get("scheduled_at_utc")));row.setActualAtUtc(MedicationBoundaryService.at(i.get("actual_at_utc")));
                if(Boolean.TRUE.equals(tracked.get(row.getMedicationId()))){row.setNextAction("MEDICATION_INTAKE_REQUIRED");disclosedTracked=true;}
                // 仅随访或真实结束没有待服剂量，不能从旧 dosage/snooze 投影出可执行提醒。
                if("FOLLOW_UP_ONLY".equals(row.getFollowUpKind())||"ENDED_UNRESOLVED".equals(row.getFollowUpState())||"NOT_APPLICABLE".equals(row.getFollowUpState())){row.setDosage(null);row.setSnoozeUntil(null);}
                out.add(row);
            }auth.requireRead(actor,patient);return out;
        }
        private Map<Long,Map<String,Object>> intakeRows(List<org.familyhealthcare.entity.MedicationIntake> rows) {
            List<Long> ids=new ArrayList<>();for(org.familyhealthcare.entity.MedicationIntake row:rows)if(row.getId()!=null)ids.add(row.getId());
            Map<Long,Map<String,Object>> out=new HashMap<>();
            for(int start=0;start<ids.size();start+=100){List<Long> batch=ids.subList(start,Math.min(start+100,ids.size()));List<Object> args=new ArrayList<>();args.add(patient);args.addAll(batch);for(Map<String,Object> row:repo.rows("SELECT * FROM medication_intake WHERE patient_id=? AND id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray()))out.put(id(row.get("id")),row);}return out;
        }
        public List<org.familyhealthcare.entity.MedicationLog> logs(long actor,List<org.familyhealthcare.entity.MedicationLog> rows) {
            if(!modern){List<org.familyhealthcare.entity.MedicationLog> out=new ArrayList<>();for(org.familyhealthcare.entity.MedicationLog row:rows)if(Objects.equals(row.getPatientId(),patient))out.add(row);return out;}
            List<Long> logIds=new ArrayList<>(),medicationIds=new ArrayList<>();for(org.familyhealthcare.entity.MedicationLog row:rows)if(row.getId()!=null)logIds.add(row.getId());
            Map<Long,Map<String,Object>> current=new HashMap<>();
            for(int start=0;start<logIds.size();start+=100){List<Long> batch=logIds.subList(start,Math.min(start+100,logIds.size()));List<Object> args=new ArrayList<>();args.add(patient);args.addAll(batch);for(Map<String,Object> row:repo.rows("SELECT id,medication_id FROM medication_log WHERE patient_id=? AND id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray())){current.put(id(row.get("id")),row);medicationIds.add(id(row.get("medication_id")));}}
            discover(medicationIds);boolean read=auth.canRead(actor,patient);List<org.familyhealthcare.entity.MedicationLog> out=new ArrayList<>();
            for(org.familyhealthcare.entity.MedicationLog row:rows){Map<String,Object> nativeRow=current.get(row.getId()),medicine=medicineRows.get(row.getMedicationId());if(!Objects.equals(row.getPatientId(),patient)||nativeRow==null||medicine==null||!Objects.equals(row.getMedicationId(),number(nativeRow.get("medication_id"))))continue;boolean owned=Boolean.TRUE.equals(tracked.get(row.getMedicationId()));if(owned&&!read)continue;org.familyhealthcare.entity.Medication safe=new org.familyhealthcare.entity.Medication();safe.setId(row.getMedicationId());safe.setPatientId(patient);safe.setDrugName((String)medicine.get("drug_name"));row.setMedication(safe);out.add(row);if(owned)disclosedTracked=true;}
            if(read)auth.requireRead(actor,patient);return out;
        }
        public List<Map<String,Object>> untrackedActions(List<Map<String,Object>> rows) {
            if(!modern)return rows;
            List<org.familyhealthcare.entity.MedicationIntake> inputs=new ArrayList<>();
            for(Map<String,Object> row:rows)if(row.get("intake_id")!=null){org.familyhealthcare.entity.MedicationIntake i=new org.familyhealthcare.entity.MedicationIntake();i.setId(id(row.get("intake_id")));inputs.add(i);}
            Map<Long,Map<String,Object>> current=intakeRows(inputs);List<Long> ids=new ArrayList<>();for(Map<String,Object> i:current.values())ids.add(id(i.get("medication_id")));discover(ids);
            List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> a:rows){Map<String,Object> i=a.get("intake_id")==null?null:current.get(id(a.get("intake_id")));if(i!=null&&id(a.get("workflow_version"))==0&&a.get("execution_id")==null&&Boolean.FALSE.equals(tracked.get(id(i.get("medication_id")))))out.add(a);}return out;
        }
        public List<org.familyhealthcare.entity.CareItem> untrackedOrders(List<org.familyhealthcare.entity.CareItem> rows) {
            if(!modern)return rows;List<Long> ids=new ArrayList<>();
            for(org.familyhealthcare.entity.CareItem row:rows)if("ORDER".equals(row.getKind())&&row.getDetails().get("medicationId")!=null)ids.add(Long.valueOf(row.getDetails().get("medicationId").toString()));discover(ids);
            List<org.familyhealthcare.entity.CareItem> out=new ArrayList<>();for(org.familyhealthcare.entity.CareItem row:rows)if(!"ORDER".equals(row.getKind())||row.getDetails().get("medicationId")!=null&&Boolean.FALSE.equals(tracked.get(Long.valueOf(row.getDetails().get("medicationId").toString()))))out.add(row);return out;
        }
    }
    public void requireLegacyOrderWriteAllowed(long actor,long patient,Long order,long medication) {
        if(!storageAvailable())return;
        parent(actor,patient,medication);
        if(order!=null) {
            Map<String,Object> current=repo.one("SELECT * FROM care_item WHERE id=? FOR UPDATE",order);
            if(current==null||id(current.get("patient_id"))!=patient||!"ORDER".equals(current.get("kind"))||id(object((String)current.get("data_json")).get("medicationId"))!=medication)throw MedicationExecutionAuthorizationService.denied();
            if(current.get("medication_execution_id")!=null)throw required("MEDICATION_EXECUTION_REQUIRED");
        }
        if(execution(patient,medication)!=null)throw required("MEDICATION_EXECUTION_REQUIRED");
    }
    public void requireLegacyIntakeWriteAllowed(long actor,long intake) {
        if(!storageAvailable())return;
        Map<String,Object> first=repo.one("SELECT patient_id,medication_id FROM medication_intake WHERE id=?",intake);
        if(first==null)throw MedicationExecutionAuthorizationService.denied();
        long patient=id(first.get("patient_id")),medication=id(first.get("medication_id"));
        parent(actor,patient,medication);
        Map<String,Object> current=repo.one("SELECT * FROM medication_intake WHERE id=? FOR UPDATE",intake);
        if(current==null||id(current.get("patient_id"))!=patient||id(current.get("medication_id"))!=medication)throw MedicationExecutionAuthorizationService.denied();
        if(current.get("execution_id")!=null||execution(patient,medication)!=null||repo.one("SELECT id FROM medication_stock WHERE patient_id=? AND medication_id=? AND workflow_version=1",patient,medication)!=null)throw required("MEDICATION_INTAKE_REQUIRED");
    }
    public void requireMedicationMutationAllowed(long actor,long patient,long medication) {
        if(!storageAvailable())return;
        parent(actor,patient,medication);
        if(execution(patient,medication)!=null)throw required("MEDICATION_EXECUTION_REQUIRED");
    }
    public void requireLegacyReminderWriteAllowed(long actor,long patient,long medication,Long reminder) {
        if(!storageAvailable())return;
        repo.lockInterval(actor,patient,medication,null,null,null);
        parent(actor,patient,medication);
        if(reminder!=null) {
            Map<String,Object> r=repo.one("SELECT * FROM medication_reminder WHERE id=? FOR UPDATE",reminder);
            if(r==null||id(r.get("patient_id"))!=patient||id(r.get("medication_id"))!=medication)throw MedicationExecutionAuthorizationService.denied();
            if(r.get("execution_id")!=null||id(r.get("workflow_version"))!=0)throw required("MEDICATION_EXECUTION_REQUIRED");
        }
    }
    public void reminderInputsChanged(long actor,long patient,long medication) {
        if(!storageAvailable())return;
        mutationTransaction(jdbc);
        auth.requireWrite(actor,patient);
        Map<String,Object> e=execution(patient,medication);
        if(e==null)return;
        long version=id(e.get("lock_version"))+1;
        Long proposal=e.get("pending_reconciliation_id")==null?null:id(e.get("pending_reconciliation_id")),revision=null;
        String type="REPLACEMENT_INPUT_CHANGED";
        int cycle=1;
        if(proposal!=null) {
            Map<String,Object> p=repo.reconciliation(proposal);
            revision=p.get("current_revision_id")==null?null:id(p.get("current_revision_id"));
            cycle=((Number)p.get("proposal_no")).intValue();
            if(Arrays.asList("READY","SCHEDULED").contains(p.get("state"))) {
                type="REPLACEMENT_CHANGED";
                repo.update("UPDATE medication_reconciliation SET state='REPLACEMENT_CHANGED',updated_at=? WHERE id=?",properties.now(),proposal);
            }
        }
        repo.update("UPDATE medication_execution SET lock_version=?,updated_at=? WHERE id=?",version,properties.now(),e.get("id"));
        events.append(actor,patient,id(e.get("id")),version,cycle,type,auth.relation(actor,patient),proposal,revision,null,map("reasonCode","PERSONAL_REMINDER_CHANGED"));
    }
    /** Returns current routing while the caller retains the same parent lock; false keeps unowned tracked orders inert. */
    public Map<String,Object> lockLegacyOrderTick(long order) {
        if(!storageAvailable())return null;
        mutationTransaction(jdbc);
        Map<String,Object> first=repo.one("SELECT patient_id,kind,data_json,medication_execution_id,medication_reconciliation_id FROM care_item WHERE id=? AND kind='ORDER'",order);
        if(first==null)return Collections.emptyMap();
        long patient=id(first.get("patient_id")),medication=Long.parseLong(String.valueOf(object((String)first.get("data_json")).get("medicationId")));
        if(repo.one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw MedicationExecutionAuthorizationService.denied();
        Map<String,Object> again=repo.one("SELECT patient_id,kind,data_json,medication_execution_id,medication_reconciliation_id FROM care_item WHERE id=?",order);
        if(!first.equals(again))throw MedicationExecutionAuthorizationService.denied();
        Map<String,Object> e=execution(patient,medication);
        MedicationExecutionRepository.IntervalAdmission admission=null;
        if(e!=null&&again.get("medication_execution_id")!=null) {
            if(!Objects.equals(e.get("id"),again.get("medication_execution_id"))||again.get("medication_reconciliation_id")==null)throw MedicationExecutionAuthorizationService.denied();
            admission=repo.lockGeneration(auth.owner(patient),patient,medication,id(again.get("medication_reconciliation_id")));
        }else parent(auth.owner(patient),patient,medication);
        Map<String,Object> current=repo.one("SELECT patient_id,kind,data_json,medication_execution_id,medication_reconciliation_id FROM care_item WHERE id=? FOR UPDATE",order);
        if(!first.equals(current)){if(admission!=null)admission.finish();throw MedicationExecutionAuthorizationService.denied();}
        return map("tracked",e!=null,"owned",current.get("medication_execution_id")!=null,"executionId",e==null?null:e.get("id"),"admission",admission);
    }
    /** Server-only route/context from lockLegacyOrderTick, never a request DTO or a grant. */
    public void applyTrackedOrderBoundary(Map<String,Object> route,java.time.Instant now,MedicationBoundaryService boundary,boolean ending) {
        Object value=route.get("admission");
        if(!(value instanceof MedicationExecutionRepository.IntervalAdmission))throw required("MEDICATION_EXECUTION_REQUIRED");
        MedicationExecutionRepository.IntervalAdmission admission=(MedicationExecutionRepository.IntervalAdmission)value;
        try{long execution=id(route.get("executionId"));if(ending)boundary.endDueAdmitted(execution,now,admission);else boundary.applyDueAdmitted(execution,now,admission);}
        finally{admission.finish();}
    }
    /** Linearization point only: an already admitted provider call cannot be recalled by later adoption. */
    public boolean admitLegacyIntakeNotification(org.familyhealthcare.entity.MedicationIntake expected,String status) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())return false;
        return legacyIntake(expected,status,true,()->{});
    }
    /** Exact legacy native mutation, with provider work always outside this short transaction. */
    public boolean mutateLegacyIntake(org.familyhealthcare.entity.MedicationIntake expected,String status,Runnable mutation) {
        return legacyIntake(expected,status,false,mutation);
    }
    private boolean legacyIntake(org.familyhealthcare.entity.MedicationIntake expected,String status,boolean notification,Runnable mutation) {
        boolean modern=storageAvailable();
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return Boolean.TRUE.equals(tx.execute(transaction->{
            if(expected.getPatientId()==null||expected.getMedicationId()==null||expected.getUserId()==null)return false;
            long patient=expected.getPatientId(),owner=auth.owner(patient);
            repo.lockBoundarySubjects(patient,Arrays.asList(owner,expected.getUserId()));
            try {auth.requireWrite(owner,patient);if(notification)auth.requireRead(expected.getUserId(),patient);}
            catch(WorkflowException denied){if(denied.getStatus()!=403)throw denied;return false;}
            Map<String,Object> medicine=repo.one("SELECT id,patient_id,is_active FROM medication WHERE id=? FOR UPDATE",expected.getMedicationId());
            if(medicine==null||id(medicine.get("patient_id"))!=patient)return false;
            Map<String,Object> reminder=expected.getReminderId()==null?null:repo.one("SELECT * FROM medication_reminder WHERE id=? FOR UPDATE",expected.getReminderId());
            Map<String,Object> intake=repo.one("SELECT * FROM medication_intake WHERE id=? FOR UPDATE",expected.getId());
            if(intake==null||!Objects.equals(intake.get("patient_id"),expected.getPatientId())||!Objects.equals(intake.get("medication_id"),expected.getMedicationId())||!Objects.equals(intake.get("user_id"),expected.getUserId())||!Objects.equals(intake.get("reminder_id"),expected.getReminderId())||!Objects.equals(intake.get("status"),status))return false;
            if(!Objects.equals(localTime(intake.get("scheduled_at")),expected.getScheduledAt())||!Objects.equals(localTime(intake.get("snooze_until")),expected.getSnoozeUntil()))return false;
            if(modern&&(id(intake.get("workflow_version"))!=0||intake.get("execution_id")!=null||intake.get("reconciliation_id")!=null))return false;
            if(notification&&(reminder==null||!Objects.equals(reminder.get("patient_id"),expected.getPatientId())||!Objects.equals(reminder.get("medication_id"),expected.getMedicationId())||!Objects.equals(reminder.get("user_id"),expected.getUserId())||id(reminder.get("enabled"))!=1||id(medicine.get("is_active"))!=1||modern&&(id(reminder.get("workflow_version"))!=0||reminder.get("execution_id")!=null)))return false;
            mutation.run();return true;
        }));
    }
    private static java.time.LocalDateTime localTime(Object value){return value==null?null:value instanceof java.sql.Timestamp?((java.sql.Timestamp)value).toLocalDateTime():(java.time.LocalDateTime)value;}
    public void decorateMedication(org.familyhealthcare.entity.Medication medication) {
        Map<String,Object> m=repo.one("SELECT workflow_version,execution_id FROM medication WHERE id=? AND patient_id=?",medication.getId(),medication.getPatientId());
        if(m!=null) {
            medication.setWorkflowVersion(((Number)m.get("workflow_version")).intValue());
            medication.setExecutionId(number(m.get("execution_id")));
        }
    }
    public void decorateIntake(org.familyhealthcare.entity.MedicationIntake intake) {
        Map<String,Object> i=repo.one("SELECT i.*,m.execution_id AS medicine_execution,s.workflow_version AS stock_workflow FROM medication_intake i JOIN medication m ON m.id=i.medication_id AND m.patient_id=i.patient_id LEFT JOIN medication_stock s ON s.patient_id=i.patient_id AND s.medication_id=i.medication_id WHERE i.id=?",intake.getId());
        if(i==null)return;
        intake.setWorkflowVersion(((Number)i.get("workflow_version")).intValue());
        intake.setExecutionId(number(i.get("execution_id"))==null?number(i.get("medicine_execution")):number(i.get("execution_id")));
        intake.setReconciliationId(number(i.get("reconciliation_id")));
        intake.setProvenance((String)i.get("provenance"));
        intake.setFollowUpState((String)i.get("follow_up_state"));
        intake.setFollowUpKind((String)i.get("follow_up_kind"));
        intake.setFollowUpCycle(((Number)i.get("follow_up_cycle")).intValue());
        intake.setScheduledAtUtc(MedicationBoundaryService.at(i.get("scheduled_at_utc")));
        intake.setActualAtUtc(MedicationBoundaryService.at(i.get("actual_at_utc")));
        if(intake.getExecutionId()!=null||i.get("stock_workflow")!=null&&id(i.get("stock_workflow"))==1)intake.setNextAction("MEDICATION_INTAKE_REQUIRED");
    }
    public void decorateReminder(org.familyhealthcare.entity.MedicationReminder reminder) {
        Map<String,Object> r=repo.one("SELECT workflow_version,execution_id,reconciliation_id,planned_quantity,planned_unit,schedule_zone FROM medication_reminder WHERE id=?",reminder.getId());
        if(r==null)return;
        reminder.setWorkflowVersion(((Number)r.get("workflow_version")).intValue());
        reminder.setExecutionId(number(r.get("execution_id")));
        reminder.setReconciliationId(number(r.get("reconciliation_id")));
        reminder.setPlannedQuantity((java.math.BigDecimal)r.get("planned_quantity"));
        reminder.setPlannedUnit((String)r.get("planned_unit"));
        reminder.setScheduleZone((String)r.get("schedule_zone"));
    }
    private static Long number(Object value) {
        return value==null?null:id(value);
    }
    private void parent(long actor,long patient,long medication) {
        mutationTransaction(jdbc);
        repo.lockBoundarySubjects(patient,Collections.singleton(actor));
        auth.requireWrite(actor,patient);
        Map<String,Object> m=repo.one("SELECT id,patient_id FROM medication WHERE id=? FOR UPDATE",medication);
        if(m==null||id(m.get("patient_id"))!=patient)throw MedicationExecutionAuthorizationService.denied();
    }
    private Map<String,Object> execution(long patient,long medication) {
        return repo.one("SELECT * FROM medication_execution WHERE patient_id=? AND medication_id=?",patient,medication);
    }
    private static WorkflowException required(String code) {
        return new WorkflowException(409,code,"Open the current medication execution to review this action.");
    }
}
