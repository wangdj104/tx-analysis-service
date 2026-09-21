package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.time.*; import java.util.*;

/** familyhealthassistant: Personal Goals, today Daytask, Timeline and Dialysis Schedule.  */
@RestController @RequestMapping("/family-health")
public class FamilyHealthController {
 @Autowired private DataScopeHelper scope; @Autowired private PatientHealthTargetMapper targetMapper;
 @Autowired private MedicationIntakeMapper intakeMapper; @Autowired private HealthEventMapper eventMapper;
 @Autowired private DialysisScheduleMapper scheduleMapper;
 @Autowired private BpSelfMonitorRecordMapper monitorMapper; @Autowired private AlertRecordMapper alertMapper;
 @Autowired private org.familyhealthcare.service.MedicationReminderService reminders;
 @Autowired private org.familyhealthcare.service.HealthTimelineService timelineService;
 @Autowired private org.familyhealthcare.service.DialysisScheduleService scheduleService;
 @Autowired private org.familyhealthcare.service.AlertService alertService;
 @Autowired private org.familyhealthcare.service.MedicationStockService stockService;
 @Autowired private CareItemMapper careItems;
 @Autowired private PatientMapper patientMapper; @Autowired private MedicationMapper medicationMapper;

 @GetMapping("/target") public Result<PatientHealthTarget> target(@RequestParam Long patientId){ scope.requirePatient(patientId); return Result.ok(targetMapper.selectOne(new QueryWrapper<PatientHealthTarget>().eq("patient_id",patientId))); }
 @PostMapping("/target")
 public Result<PatientHealthTarget> saveTarget(@RequestBody PatientHealthTarget v) {
  scope.requirePatient(v.getPatientId());
  if (v.getSystolicMin()!=null && v.getSystolicMax()!=null && v.getSystolicMin()>v.getSystolicMax()
      || v.getDiastolicMin()!=null && v.getDiastolicMax()!=null && v.getDiastolicMin()>v.getDiastolicMax())
   return Result.error("A target minimum cannot exceed its maximum.");
  PatientHealthTarget old=targetMapper.selectOne(new QueryWrapper<PatientHealthTarget>().eq("patient_id",v.getPatientId()));
  // Identity comes from the selected patient, never the browser's previous form.
  v.setId(old==null?null:old.getId()); v.setUserId(old==null?scope.requireUserId():old.getUserId());
  if(old==null)targetMapper.insert(v);else targetMapper.updateById(v);
  return Result.ok(v);
 }

 @GetMapping("/intakes")
 public Result<List<MedicationIntake>> intakes(@RequestParam Long patientId,@RequestParam(required=false) String date) {
  scope.requirePatient(patientId); LocalDate d=date==null?LocalDate.now():LocalDate.parse(date);
  if(d.equals(LocalDate.now()))reminders.ensureDailyTasks(patientId,d);
  List<MedicationIntake> rows=intakeMapper.selectList(new QueryWrapper<MedicationIntake>().eq("patient_id",patientId)
      .and(q -> q.ge("scheduled_at",d.atStartOfDay()).lt("scheduled_at",d.plusDays(1).atStartOfDay())
          .or().ge("snooze_until",d.atStartOfDay()).lt("snooze_until",d.plusDays(1).atStartOfDay())).orderByAsc("scheduled_at"));
  for(MedicationIntake row:rows){Medication med=medicationMapper.selectById(row.getMedicationId());row.setDrugName(med==null?"DeletedMedication":med.getDrugName());}
  return Result.ok(rows);
 }

 @PutMapping("/intakes/{id}/action")
 @org.springframework.transaction.annotation.Transactional
 public Result<String> intakeAction(@PathVariable Long id,@RequestParam String status,@RequestParam(required=false) String reason,
     @RequestParam(required=false) java.math.BigDecimal quantity,@RequestParam(required=false) String recordedFor) {
  MedicationIntake v=intakeMapper.selectById(id); if(v==null)return Result.error("The record does not exist.");
  scope.requirePatient(v.getPatientId());
  reminders.ensureDailyTasks(v.getPatientId(), v.getScheduledAt().toLocalDate());
  v=intakeMapper.selectById(id);
  if("CANCELLED".equals(v.getStatus()))return Result.error("This task has been cancelled.");
  if(!Arrays.asList("TAKEN","SNOOZED","SKIPPED").contains(status))return Result.error("Invalid status.");
  if("TAKEN".equals(v.getStatus()) || "SKIPPED".equals(v.getStatus()))return Result.error("This task has already been processed. Refresh the page.");
  LocalDateTime now=LocalDateTime.now();
  int changed=intakeMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MedicationIntake>()
      .eq("id",id).eq("status",v.getStatus()).set("status",status).set("reason",reason).set("action_at",now)
      .set("snooze_until","SNOOZED".equals(status)?now.plusMinutes(15):null));
  if(changed>0){v.setStatus(status);stockService.consume(v,quantity,recordedFor);}
  return changed>0?Result.ok("Operation completed"):Result.error("The task status changed. Refresh and try again.");
 }

 @GetMapping("/timeline")
 public Result<List<HealthEvent>> timeline(@RequestParam Long patientId,@RequestParam(required=false) String from,@RequestParam(required=false) String to) {
  scope.requirePatient(patientId);
  return Result.ok(timelineService.list(patientId,from==null?null:LocalDate.parse(from),to==null?null:LocalDate.parse(to),200));
 }

 @PostMapping("/events")
 public Result<HealthEvent> event(@RequestBody HealthEvent v) {
  scope.requirePatient(v.getPatientId());
  if(v.getTitle()==null || v.getTitle().trim().isEmpty())return Result.error("Enter an event title.");
  if(v.getTitle().length()>120 || v.getSummary()!=null && v.getSummary().length()>500)return Result.error("The title may contain up to 120 characters and the description up to 500.");
  if(!Arrays.asList("SYMPTOM","VISIT","NOTE").contains(v.getEventType()))return Result.error("Invalid event type.");
  if(v.getEventDate()==null)return Result.error("Select an event date.");
  if(v.getEventTime()!=null && !v.getEventTime().isEmpty()) {
   try{v.setEventTime(LocalTime.parse(v.getEventTime()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));}catch(RuntimeException e){return Result.error("Invalid event time format.");}
  }
  v.setSourceType("MANUAL"); v.setSourceId(null);
  if(v.getId()==null){v.setUserId(scope.requireUserId());eventMapper.insert(v);}
  else {
   HealthEvent old=eventMapper.selectById(v.getId());
   if(old==null || !Objects.equals(old.getPatientId(),v.getPatientId()))return Result.error("The event does not exist or belongs to another patient.");
   scope.requirePatientOrOwner(old.getPatientId(), old.getUserId());
   if(old.getSourceType()!=null && !"MANUAL".equals(old.getSourceType()))return Result.error("Edit this item from its original record page.");
   v.setUserId(old.getUserId());eventMapper.updateById(v);
  }
  return Result.ok(v);
 }

 @DeleteMapping("/events/{id}")
 public Result<String> deleteEvent(@PathVariable Long id) {
  HealthEvent old=eventMapper.selectById(id);if(old==null)return Result.error("The event does not exist.");
  scope.requirePatientOrOwner(old.getPatientId(), old.getUserId());
  if(old.getSourceType()!=null && !"MANUAL".equals(old.getSourceType()))return Result.error("Delete this item from its original record page.");
  eventMapper.deleteById(id);return Result.ok("Deleted");
 }

 @GetMapping("/dialysis-schedules")
 public Result<List<DialysisSchedule>> schedules(@RequestParam Long patientId){scope.requirePatient(patientId);return Result.ok(scheduleService.list(patientId));}
 @PostMapping("/dialysis-schedules")
 public Result<DialysisSchedule> schedule(@RequestBody DialysisSchedule v){return Result.ok(scheduleService.save(v));}
 @PostMapping("/dialysis-schedules/generate")
 public Result<Map<String,Object>> generateSchedules(@RequestBody Map<String,Object> body){
  Long patientId=Long.valueOf(String.valueOf(body.get("patientId")));
  LocalDate from=LocalDate.parse(String.valueOf(body.get("from"))),to=LocalDate.parse(String.valueOf(body.get("to")));
  boolean confirmed=Boolean.parseBoolean(String.valueOf(body.getOrDefault("confirmed",false)));
  return Result.ok(scheduleService.generatePlan(patientId,String.valueOf(body.get("weekdays")),body.get("time")==null?null:String.valueOf(body.get("time")),from,to,confirmed));
 }

 @GetMapping("/insights") public Result<Map<String,Object>> insights(@RequestParam Long patientId){
  scope.requirePatient(patientId); PatientHealthTarget t=targetMapper.selectOne(new QueryWrapper<PatientHealthTarget>().eq("patient_id",patientId));
  List<BpSelfMonitorRecord> rows=monitorMapper.selectList(new QueryWrapper<BpSelfMonitorRecord>().eq("patient_id",patientId).orderByDesc("record_date").orderByDesc("record_time").last("limit 30"));
  int consecutive=0; List<Map<String,Object>> points=new ArrayList<>();
  for(BpSelfMonitorRecord r:rows){boolean abnormal=false;if(r.getSystolicBp()!=null){int min=t!=null&&t.getSystolicMin()!=null?t.getSystolicMin():90,max=t!=null&&t.getSystolicMax()!=null?t.getSystolicMax():140;int dmin=t!=null&&t.getDiastolicMin()!=null?t.getDiastolicMin():60,dmax=t!=null&&t.getDiastolicMax()!=null?t.getDiastolicMax():90;abnormal=r.getSystolicBp()<min||r.getSystolicBp()>max||r.getDiastolicBp()!=null&&(r.getDiastolicBp()<dmin||r.getDiastolicBp()>dmax);}if(abnormal&&consecutive==points.size())consecutive++;Map<String,Object> p=new HashMap<>();p.put("date",r.getRecordDate());p.put("time",r.getRecordTime());p.put("systolic",r.getSystolicBp());p.put("diastolic",r.getDiastolicBp());p.put("glucose",r.getBloodGlucose());p.put("abnormal",abnormal);points.add(p);}
  Map<String,Object> out=new HashMap<>();out.put("consecutiveAbnormal",consecutive);out.put("points",points);out.put("warning",consecutive>=3?"most recent continuous "+consecutive+" timesBlood PressureexceedPersonal Goals, Please remeasureandas prescribedprocess":null);return Result.ok(out);
 }

 @GetMapping("/alerts") public Result<List<AlertRecord>> alerts(@RequestParam Long patientId){scope.requirePatient(patientId);return Result.ok(alertMapper.selectList(new QueryWrapper<AlertRecord>().eq("patient_id",patientId).orderByDesc("triggered_at").last("limit 100")));}
 @PutMapping("/alerts/{id}/status") public Result<String> alertStatus(@PathVariable Long id,@RequestBody Map<String,String> body){return alertService.updateStatus(id,body.get("status"),body.get("handlingNote"))?Result.ok("Updated successfully"):Result.error("The alert does not exist.");}

 @GetMapping("/visit-summary") public Result<Map<String,Object>> visitSummary(@RequestParam Long patientId){Patient p=scope.requirePatient(patientId);Map<String,Object> out=new LinkedHashMap<>();out.put("generatedAt",LocalDateTime.now());out.put("patient",p);out.put("target",targetMapper.selectOne(new QueryWrapper<PatientHealthTarget>().eq("patient_id",patientId)));out.put("medications",medicationMapper.selectList(new QueryWrapper<Medication>().eq("patient_id",patientId).eq("is_active",1)));out.put("recentMeasurements",monitorMapper.selectList(new QueryWrapper<BpSelfMonitorRecord>().eq("patient_id",patientId).orderByDesc("record_date").last("limit 30")));out.put("unresolvedAlerts",alertMapper.selectList(new QueryWrapper<AlertRecord>().eq("patient_id",patientId).and(q->q.isNull("status").or().ne("status","RESOLVED")).orderByDesc("triggered_at").last("limit 30")));out.put("recentEvents",timelineService.list(patientId,null,null,30));out.put("questions",careItems.selectList(new QueryWrapper<CareItem>().eq("patient_id",patientId).eq("kind","QUESTION").orderByDesc("id")));out.put("careSymptoms",careItems.selectList(new QueryWrapper<CareItem>().eq("patient_id",patientId).eq("kind","SYMPTOM").orderByDesc("event_at").last("limit 30")));out.put("disclaimer","This summary is based on family-entered records and is intended only to support clinical conversations. It does not replace professional diagnosis or treatment.");return Result.ok(out);}
}
