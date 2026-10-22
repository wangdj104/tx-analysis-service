package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.*;
import org.familyhealthcare.util.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@RestController @RequestMapping("/care")
public class CareController {
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @Autowired private MedicalRecordReviewAccess medicalAccess;
    @Autowired private org.springframework.jdbc.datasource.DataSourceTransactionManager choiceTransactions;
    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard medicationTracking;
    @Autowired private FamilyCareService care;
    @Autowired private CareMembershipService membership;
    @Autowired private DataScopeHelper scope;
    @Autowired private PatientMapper patients;
    @Autowired private MedicationMapper medications;
    @Autowired private MedicalRecordMapper reports;
    @Autowired private MedicationIntakeMapper intakes;
    @Autowired private MedicationReminderService reminders;
    @Autowired private MedicationStockService stock;
    @Autowired private BpSelfMonitorRecordService vitals;
    @Autowired private JdbcTemplate jdbc;

    @ModelAttribute public void questionPrivacy(javax.servlet.http.HttpServletRequest request,javax.servlet.http.HttpServletResponse response){String path=request.getRequestURI();if(path.endsWith("/context")||path.contains("/care/items")){response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");}}
    @GetMapping("/home") public Result<List<Map<String,Object>>> home(){
        List<Map<String,Object>>out=new ArrayList<>();List<org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead> readChecks=new ArrayList<>();
        Long userId=scope.requireUserId();
        List<Long> ids=CurrentUserUtil.isAdmin()?patients.selectList(new QueryWrapper<Patient>().eq("status",1)).stream().map(Patient::getId).collect(java.util.stream.Collectors.toList()):scope.accessiblePatientIds(userId);
        for(Long pid:ids){Patient p=patients.selectById(pid);if(p==null||Integer.valueOf(0).equals(p.getStatus()))continue;Map<String,Object>r=new LinkedHashMap<>();r.put("patient",p);r.put("profile",care.publicProfile(pid));List<CareItem> summary=new ArrayList<>();for(CareItem item:care.list(pid,null)){if(Arrays.asList("APPOINTMENT","HANDOVER").contains(item.getKind())){item.setDetails(Collections.emptyMap());summary.add(item);}}r.put("items",summary);org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead read=medicationTracking.legacyRead(pid);readChecks.add(read);r.put("intakes",todayIntakes(pid,read));r.put("members",membership.members(pid));out.add(r);}for(org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead read:readChecks)read.finish(userId);return Result.ok(out);
    }
    public Result<Map<String,Object>> context(Long patientId){return contextPage(patientId,null,25);}
    @GetMapping("/context") public Result<Map<String,Object>> contextPage(@RequestParam Long patientId,@RequestParam(required=false) String questionCursor,@RequestParam(defaultValue="25") Integer questionLimit){
        scope.requirePatient(patientId);org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead legacy=medicationTracking.legacyRead(patientId);Map<String,Object>out=new LinkedHashMap<>();out.put("patient",patients.selectById(patientId));List<CareItem> ordinaryItems=care.list(patientId,null);Map<String,Object> questionPage=care.questionPage(patientId,questionCursor,questionLimit);ordinaryItems.addAll((List<CareItem>)questionPage.get("items"));out.put("items",ordinaryItems);out.put("questionContinuation",questionPage.get("nextCursor"));out.put("questionPreviews",false);out.put("members",membership.members(patientId));
        out.put("medications",legacy.medications(scope.requireUserId(),medications.selectList(new QueryWrapper<Medication>().eq("patient_id",patientId).orderByAsc("drug_name"))));
        try(ChoiceFrame sourceFrame=sourceChoices(patientId)){out.put("reports",sourceFrame.choices);
        out.put("stocks",stock.list(patientId));out.put("intakes",todayIntakes(patientId,legacy));
        out.put("actions",legacy.untrackedActions(jdbc.queryForList("SELECT * FROM care_intake_action WHERE patient_id=? ORDER BY id DESC LIMIT 200",patientId)));
        legacy.finish(scope.requireUserId());care.finishQuestionRead(patientId,questionPage);sourceFrame.finish();return Result.ok(out);}
    }
    private void choiceGraph(){medicalValidity.requireDataSource(jdbc.getDataSource());medicalAccess.requireDataSource(jdbc.getDataSource());if(choiceTransactions==null||choiceTransactions.getDataSource()!=jdbc.getDataSource())throw choiceUnavailable();}
    private ChoiceFrame sourceChoices(Long patient) {
        choiceGraph();long actor=scope.requireUserId();ChoiceResource outer=null;
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())outer=new ChoiceResource(actor);
        else if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()||org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw choiceUnavailable();
        final ChoiceResource original=outer;org.springframework.transaction.support.TransactionTemplate snapshot=new org.springframework.transaction.support.TransactionTemplate(choiceTransactions);
        snapshot.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);snapshot.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setReadOnly(true);
        ChoiceFrame frame;
        try{frame=snapshot.execute(status->{
            ChoiceResource owner=new ChoiceResource(actor);if(original!=null&&(original.holder==owner.holder||original.connection==owner.connection))throw choiceUnavailable();
            try{org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope medical=medicalValidity.open();
            List<MedicalRecord> choices=reports.selectList(new QueryWrapper<MedicalRecord>().select("id","record_date","hospital_name","record_type","verification_status","content_version","patient_id","user_id").eq("patient_id",patient).apply(medical.activeSql("medical_record.")).orderByDesc("record_date"));owner.verify();
            List<MedicalRecord> headers=new ArrayList<>();Set<Long> ids=new LinkedHashSet<>();
            for(MedicalRecord choice:choices){if(choice==null||choice.getId()==null||!ids.add(choice.getId())||!Objects.equals(patient,choice.getPatientId()))throw choiceChanged();MedicalRecord header=new MedicalRecord();header.setId(choice.getId());header.setPatientId(choice.getPatientId());header.setUserId(choice.getUserId());header.setContentVersion(choice.getContentVersion());header.setVerificationStatus(choice.getVerificationStatus());headers.add(header);choice.setPatientId(null);choice.setUserId(null);}
            medicalAccess.requireReadAdmission(headers,actor);owner.verify();Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State> states=new LinkedHashMap<>();List<Long> ordered=new ArrayList<>(ids);
            if(ordered.isEmpty()){Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State> empty=medical.qualifiedSnapshot(Collections.emptyList());owner.verify();if(empty==null||!empty.isEmpty())throw choiceChanged();}
            for(int start=0;start<ordered.size();start+=100){List<Long> part=ordered.subList(start,Math.min(start+100,ordered.size()));Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State> observed;
                try{observed=medical.qualifiedSnapshot(part);}catch(org.familyhealthcare.service.workflow.WorkflowException failure){owner.verify();throw failure;}owner.verify();
                if(observed==null||observed.size()!=part.size()||!observed.keySet().equals(new LinkedHashSet<>(part)))throw choiceChanged();
                for(int index=start;index<Math.min(start+100,ordered.size());index++){MedicalRecord header=headers.get(index);org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State state=observed.get(header.getId());
                    if(state==null||!state.reviewObserved||state.signature==null||state.checkedAt==null||state.qualificationSemantics!=org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT||state.id!=header.getId()||!Objects.equals(header.getPatientId(),state.patientId)||!Objects.equals(header.getUserId(),state.ownerId)||!Objects.equals(header.getContentVersion(),state.version)||!Objects.equals(header.getVerificationStatus(),state.review))throw choiceChanged();states.put(header.getId(),state);
                }
            }owner.verify();return new ChoiceFrame(actor,choices,headers,Collections.unmodifiableMap(states));}finally{owner.verify();}
        });}catch(org.familyhealthcare.service.workflow.WorkflowException|IllegalStateException failure){throw failure;}catch(RuntimeException failure){throw choiceUnavailable();}finally{if(original!=null)original.verify();else if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()||org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()||org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw choiceUnavailable();}
        if(frame==null)throw choiceUnavailable();try{frame.project();return frame;}catch(RuntimeException failure){frame.close();throw failure;}
    }
    private final class ChoiceFrame implements AutoCloseable {
        final long actor;final List<MedicalRecord> choices,headers;final Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State> snapshot;boolean released;
        ChoiceFrame(long actor,List<MedicalRecord> choices,List<MedicalRecord> headers,Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State> snapshot){this.actor=actor;this.choices=choices;this.headers=Collections.unmodifiableList(headers);this.snapshot=snapshot;}
        Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State> current(){choiceGraph();return medicalAccess.currentReadFrame(headers,snapshot,actor);}
        void project(){Map<Long,org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State> current=current();for(int n=0;n<choices.size();n++){MedicalRecord header=headers.get(n),choice=choices.get(n);org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State state=current.get(header.getId());org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.projectReviewSummary(header,state);choice.setReviewSummary(header.getReviewSummary());choice.setManaged(state.managed);choice.setValidity(state.validity);choice.setSourceStatus(state.status());}}
        void finish(){current();released=true;}
        @Override public void close(){if(!released)for(MedicalRecord choice:choices){choice.setReviewSummary(null);choice.setManaged(null);choice.setValidity(null);choice.setSourceStatus(null);}}
    }
    private final class ChoiceResource {
        final Thread thread=Thread.currentThread();final long actor;final org.springframework.jdbc.datasource.ConnectionHolder holder;final org.springframework.jdbc.datasource.SimpleConnectionHandle handle;final java.sql.Connection connection;
        ChoiceResource(long actor){this.actor=actor;choiceGraph();Object resource=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource());if(resource==null||resource.getClass()!=org.springframework.jdbc.datasource.ConnectionHolder.class)throw choiceUnavailable();holder=(org.springframework.jdbc.datasource.ConnectionHolder)resource;if(holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=org.springframework.jdbc.datasource.SimpleConnectionHandle.class)throw choiceUnavailable();handle=(org.springframework.jdbc.datasource.SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();verify();}
        void verify(){choiceGraph();if(thread!=Thread.currentThread()||actor!=scope.requireUserId()||!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()||!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()||!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()||org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource())!=holder||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw choiceUnavailable();try{if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()||connection.getTransactionIsolation()!=java.sql.Connection.TRANSACTION_REPEATABLE_READ)throw choiceUnavailable();}catch(java.sql.SQLException|IllegalStateException failure){throw choiceUnavailable();}}
    }
    private static org.familyhealthcare.service.workflow.WorkflowException choiceUnavailable(){return new org.familyhealthcare.service.workflow.WorkflowException(503,"CURRENT_RESULT_UNAVAILABLE","Current source choices could not be verified.");}
    private static org.familyhealthcare.service.workflow.WorkflowException choiceChanged(){return new org.familyhealthcare.service.workflow.WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","Current source choices could not be verified.");}

    private List<MedicationIntake> todayIntakes(Long pid){return todayIntakes(pid,medicationTracking.legacyRead(pid));}
    private List<MedicationIntake> todayIntakes(Long pid,org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.LegacyRead legacy){
        LocalDate day=LocalDate.now();reminders.ensureDailyTasks(pid,day);
        List<MedicationIntake> rows=legacy.intakes(scope.requireUserId(),intakes.selectList(new QueryWrapper<MedicationIntake>().eq("patient_id",pid).and(q->q.ge("scheduled_at",day.atStartOfDay()).lt("scheduled_at",day.plusDays(1).atStartOfDay()).or().ge("snooze_until",day.atStartOfDay()).lt("snooze_until",day.plusDays(1).atStartOfDay())).orderByAsc("scheduled_at")));
        Map<Long,String> names=new HashMap<>();List<Long> ids=new ArrayList<>();for(MedicationIntake row:rows)ids.add(row.getMedicationId());
        if(!ids.isEmpty())for(Medication m:medications.selectBatchIds(ids))names.put(m.getId(),m.getDrugName());
        for(MedicationIntake row:rows)row.setDrugName(names.getOrDefault(row.getMedicationId(),org.familyhealthcare.util.ExportLocalization.text("Deleted medication","已删除药品")));legacy.finish(scope.requireUserId());return rows;
    }
    @PostMapping("/items") public Result<CareItem> save(@RequestBody CareItem v){return Result.ok(care.save(v));}
    @PutMapping("/items/{id}/status") public Result<CareItem> action(@PathVariable Long id,@RequestBody Map<String,String>body){return Result.ok(care.action(id,body.get("status"),body.get("note"),body));}
    @DeleteMapping("/items/{id}") public Result<String> delete(@PathVariable Long id,@RequestParam Map<String,String> command){boolean withdrawn=care.delete(id,command);return Result.okMessage(withdrawn?"Withdrawn; history retained and reopening remains available":"Deleted");}
    @PostMapping("/invitations") public Result<String> invite(@RequestParam Long patientId){return Result.ok(membership.invite(patientId));}
    @PostMapping("/join") public Result<Long> join(@RequestBody Map<String,String>b){return Result.ok(membership.join(b.get("code"),b.get("relation")));}
    @DeleteMapping("/members/{userId}") public Result<String> removeMember(@PathVariable Long userId,@RequestParam Long patientId){membership.remove(patientId,userId);return Result.okMessage("Removed successfully.");}
    @PostMapping("/stock") public Result<String> configureStock(@RequestBody Map<String,Object>b){stock.configure(number(b,"patientId"),number(b,"medicationId"),decimal(b,"quantity"),String.valueOf(b.get("unit")),Integer.parseInt(String.valueOf(b.getOrDefault("warningDays",7))),new BigDecimal(String.valueOf(b.getOrDefault("warningQuantity",0))));return Result.okMessage("Inventory settings saved.");}
    @PostMapping("/stock/purchase") public Result<String> purchase(@RequestBody Map<String,Object>b){stock.purchase(number(b,"patientId"),number(b,"medicationId"),decimal(b,"quantity"),(String)b.get("reason"));return Result.okMessage("Restock recorded.");}
    @GetMapping("/stock/history") public Result<List<Map<String,Object>>> stockHistory(@RequestParam Long patientId,@RequestParam Long medicationId){return Result.ok(stock.history(patientId,medicationId));}
    @PostMapping("/quick-vitals") public Result<String> quickVitals(@RequestBody MeasurementInputBody body){
        // 先核整数原输入，避免反序列化把小数血压截成整数。
        MeasurementInputNumbers.requireSelfInteger(MeasurementInputBody.originalValue(body,"systolicBp"),"systolicBp");
        MeasurementInputNumbers.requireSelfInteger(MeasurementInputBody.originalValue(body,"diastolicBp"),"diastolicBp");
        Object glucose=MeasurementInputBody.originalValue(body,"bloodGlucose");if(glucose!=null&&!glucose.toString().trim().isEmpty())MeasurementInputNumbers.selfGlucose(new BigDecimal(glucose.toString()));
        BpSelfMonitorRecord r=com.alibaba.fastjson2.JSON.parseObject(com.alibaba.fastjson2.JSON.toJSONString(body),BpSelfMonitorRecord.class);
        if(r.getPatientId()==null)throw new IllegalArgumentException("SelectFamily Member");
        boolean bp=r.getSystolicBp()!=null||r.getDiastolicBp()!=null;
        if(bp&&(r.getSystolicBp()==null||r.getDiastolicBp()==null||r.getSystolicBp()<=0||r.getDiastolicBp()<=0))throw new IllegalArgumentException("Enter both systolic and diastolic blood pressure.");
        if(!bp&&r.getBloodGlucose()==null)throw new IllegalArgumentException("Enter blood pressure or blood glucose.");
        if(r.getBloodGlucose()!=null&&r.getBloodGlucose().signum()<=0)throw new IllegalArgumentException("Blood glucose must be greater than zero.");
        r.setId(null);if(r.getRecordDate()==null)r.setRecordDate(LocalDate.now());if(r.getRecordTime()==null)r.setRecordTime(LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));r.setMeasureType(bp?(r.getBloodGlucose()!=null?"BOTH":"BP"):"BG");if(r.getBgUnit()==null)r.setBgUnit("mmol/L");vitals.saveOwned(r);return Result.okMessage("Recorded successfully.");
    }
    @ExceptionHandler(org.familyhealthcare.service.visitquestion.VisitQuestionFailure.class)
    public org.springframework.http.ResponseEntity<Result<Map<String,Object>>> questionFailure(org.familyhealthcare.service.visitquestion.VisitQuestionFailure failure){
        String message=failure.getMessage();

        Result<Map<String,Object>> response=Result.error(failure.getStatus(),message);response.setData(Collections.singletonMap("errorCode",failure.getErrorCode()));
        return org.springframework.http.ResponseEntity.status(failure.getStatus()).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(response);
    }
    private Long number(Map<String,Object>b,String key){return Long.valueOf(String.valueOf(b.get(key)));}
    private BigDecimal decimal(Map<String,Object>b,String key){return new BigDecimal(String.valueOf(b.get(key)));}
}
