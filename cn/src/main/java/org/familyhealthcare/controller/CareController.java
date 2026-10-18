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
        org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope medical=medicalValidity.open();
        List<MedicalRecord> choices=reports.selectList(new QueryWrapper<MedicalRecord>().select("id","record_date","hospital_name","record_type","verification_status","content_version").eq("patient_id",patientId).apply(medical.activeSql("medical_record.")).orderByDesc("record_date"));medical.project(choices);out.put("reports",choices);
        out.put("stocks",stock.list(patientId));out.put("intakes",todayIntakes(patientId,legacy));
        out.put("actions",legacy.untrackedActions(jdbc.queryForList("SELECT * FROM care_intake_action WHERE patient_id=? ORDER BY id DESC LIMIT 200",patientId)));
        legacy.finish(scope.requireUserId());care.finishQuestionRead(patientId,questionPage);return Result.ok(out);
    }
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
    @DeleteMapping("/items/{id}") public Result<String> delete(@PathVariable Long id,@RequestParam Map<String,String> command){boolean withdrawn=care.delete(id,command);return Result.okMessage(withdrawn?"已撤回，历史记录仍保留，可重新开启":"Deleted");}
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
        switch(failure.getErrorCode()){
            case "ACCESS_DENIED":message="当前账号没有访问或修改此问题的权限，请刷新后重试";break;
            case "QUESTION_REFRESH_REQUIRED":message="此问题已进入跟踪流程，请刷新并使用带版本的操作，历史记录不会被覆盖";break;
            case "QUESTION_DISABLED":message="问题跟踪暂未启用，未跟踪的普通记录仍可使用";break;
            case "QUESTION_SCHEMA_NOT_READY":case "QUESTION_TRANSPORT_NOT_READY":message="暂时无法验证问题存储，请稍后重新检查，不能确认操作已完成";break;
            case "QUESTION_LIMIT":case "QUESTION_WIRE_LIMIT":message="这条问题的内容超过安全处理上限，原始文字未被截断，请缩小本次新增内容后重试";break;
            case "QUESTION_CONTEXT_CHANGED":message="关联的就诊记录已变化或当前不可访问，请刷新后重新选择或记录未解决原因";break;
            case "QUESTION_REOPEN_REQUIRED":case "QUESTION_CLOSED":message="请先填写原因重新开启问题，再修改或补充答案";break;
            case "QUESTION_NO_CHANGE":message="内容与当前记录一致，没有创建新的作者记录";break;
            case "QUESTION_RESULT_REQUIRED":case "QUESTION_ANSWER_REQUIRED":message="请填写实际结果或选择当前可访问的同一患者记录，不能仅凭原因完成关闭";break;
            case "QUESTION_TRANSITION_INVALID":case "QUESTION_FOLLOW_UP_REQUIRED":message="当前问题状态不支持此操作，请刷新后选择可用操作";break;
            case "INVALID_REQUEST":message="问题操作参数不完整或不支持，请检查文字、原因、日期和版本信息，普通记录不能填写专业认证字段";break;
            default:message="问题或当前权限已变化，请刷新并核对操作结果后重试";
        }
        Result<Map<String,Object>> response=Result.error(failure.getStatus(),message);response.setData(Collections.singletonMap("errorCode",failure.getErrorCode()));
        return org.springframework.http.ResponseEntity.status(failure.getStatus()).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").body(response);
    }
    private Long number(Map<String,Object>b,String key){return Long.valueOf(String.valueOf(b.get(key)));}
    private BigDecimal decimal(Map<String,Object>b,String key){return new BigDecimal(String.valueOf(b.get(key)));}
}
