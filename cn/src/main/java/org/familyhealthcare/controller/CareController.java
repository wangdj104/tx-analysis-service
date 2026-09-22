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

    @GetMapping("/home") public Result<List<Map<String,Object>>> home(){
        List<Map<String,Object>>out=new ArrayList<>();
        Long userId=scope.requireUserId();
        List<Long> ids=CurrentUserUtil.isAdmin()?patients.selectList(new QueryWrapper<Patient>().eq("status",1)).stream().map(Patient::getId).collect(java.util.stream.Collectors.toList()):scope.accessiblePatientIds(userId);
        for(Long pid:ids){Patient p=patients.selectById(pid);if(p==null||Integer.valueOf(0).equals(p.getStatus()))continue;Map<String,Object>r=new LinkedHashMap<>();r.put("patient",p);r.put("profile",care.profile(pid));List<CareItem> summary=new ArrayList<>();for(CareItem item:care.list(pid,null)){if(Arrays.asList("APPOINTMENT","HANDOVER").contains(item.getKind())){item.setDetails(Collections.emptyMap());summary.add(item);}}r.put("items",summary);r.put("intakes",todayIntakes(pid));r.put("members",membership.members(pid));out.add(r);}return Result.ok(out);
    }
    @GetMapping("/context") public Result<Map<String,Object>> context(@RequestParam Long patientId){
        scope.requirePatient(patientId);Map<String,Object>out=new LinkedHashMap<>();out.put("patient",patients.selectById(patientId));out.put("items",care.list(patientId,null));out.put("members",membership.members(patientId));
        out.put("medications",medications.selectList(new QueryWrapper<Medication>().eq("patient_id",patientId).orderByAsc("drug_name")));
        out.put("reports",reports.selectList(new QueryWrapper<MedicalRecord>().select("id","record_date","hospital_name","record_type").eq("patient_id",patientId).orderByDesc("record_date")));
        out.put("stocks",stock.list(patientId));out.put("intakes",todayIntakes(patientId));
        out.put("actions",jdbc.queryForList("SELECT * FROM care_intake_action WHERE patient_id=? ORDER BY id DESC LIMIT 200",patientId));
        return Result.ok(out);
    }
    private List<MedicationIntake> todayIntakes(Long pid){LocalDate day=LocalDate.now();reminders.ensureDailyTasks(pid,day);List<MedicationIntake>rows=intakes.selectList(new QueryWrapper<MedicationIntake>().eq("patient_id",pid).and(q->q.ge("scheduled_at",day.atStartOfDay()).lt("scheduled_at",day.plusDays(1).atStartOfDay()).or().ge("snooze_until",day.atStartOfDay()).lt("snooze_until",day.plusDays(1).atStartOfDay())).orderByAsc("scheduled_at"));for(MedicationIntake r:rows){Medication m=medications.selectById(r.getMedicationId());r.setDrugName(m==null?"DeletedMedication":m.getDrugName());}return rows;}
    @PostMapping("/items") public Result<CareItem> save(@RequestBody CareItem v){return Result.ok(care.save(v));}
    @PutMapping("/items/{id}/status") public Result<CareItem> action(@PathVariable Long id,@RequestBody Map<String,String>body){return Result.ok(care.action(id,body.get("status"),body.get("note")));}
    @DeleteMapping("/items/{id}") public Result<String> delete(@PathVariable Long id){care.delete(id);return Result.ok("Deleted");}
    @PostMapping("/invitations") public Result<String> invite(@RequestParam Long patientId){return Result.ok(membership.invite(patientId));}
    @PostMapping("/join") public Result<Long> join(@RequestBody Map<String,String>b){return Result.ok(membership.join(b.get("code"),b.get("relation")));}
    @DeleteMapping("/members/{userId}") public Result<String> removeMember(@PathVariable Long userId,@RequestParam Long patientId){membership.remove(patientId,userId);return Result.ok("Removed successfully.");}
    @PostMapping("/stock") public Result<String> configureStock(@RequestBody Map<String,Object>b){stock.configure(number(b,"patientId"),number(b,"medicationId"),decimal(b,"quantity"),String.valueOf(b.get("unit")),Integer.parseInt(String.valueOf(b.getOrDefault("warningDays",7))),new BigDecimal(String.valueOf(b.getOrDefault("warningQuantity",0))));return Result.ok("Inventory settings saved.");}
    @PostMapping("/stock/purchase") public Result<String> purchase(@RequestBody Map<String,Object>b){stock.purchase(number(b,"patientId"),number(b,"medicationId"),decimal(b,"quantity"),(String)b.get("reason"));return Result.ok("Restock recorded.");}
    @GetMapping("/stock/history") public Result<List<Map<String,Object>>> stockHistory(@RequestParam Long patientId,@RequestParam Long medicationId){return Result.ok(stock.history(patientId,medicationId));}
    @PostMapping("/quick-vitals") public Result<String> quickVitals(@RequestBody BpSelfMonitorRecord r){
        if(r.getPatientId()==null)throw new IllegalArgumentException("SelectFamily Member");
        boolean bp=r.getSystolicBp()!=null||r.getDiastolicBp()!=null;
        if(bp&&(r.getSystolicBp()==null||r.getDiastolicBp()==null||r.getSystolicBp()<=0||r.getDiastolicBp()<=0))throw new IllegalArgumentException("Enter both systolic and diastolic blood pressure.");
        if(!bp&&r.getBloodGlucose()==null)throw new IllegalArgumentException("Enter blood pressure or blood glucose.");
        if(r.getBloodGlucose()!=null&&r.getBloodGlucose().signum()<=0)throw new IllegalArgumentException("Blood glucose must be greater than zero.");
        r.setId(null);if(r.getRecordDate()==null)r.setRecordDate(LocalDate.now());if(r.getRecordTime()==null)r.setRecordTime(LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));r.setMeasureType(bp?(r.getBloodGlucose()!=null?"BOTH":"BP"):"BG");if(r.getBgUnit()==null)r.setBgUnit("mmol/L");vitals.saveOwned(r);return Result.ok("Recorded successfully.");
    }
    private Long number(Map<String,Object>b,String key){return Long.valueOf(String.valueOf(b.get(key)));}
    private BigDecimal decimal(Map<String,Object>b,String key){return new BigDecimal(String.valueOf(b.get(key)));}
}
