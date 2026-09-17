package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class FamilyCareService {
    @Autowired private CareItemMapper items;
    @Autowired private DataScopeHelper scope;
    @Autowired private CareMembershipService membership;
    @Autowired private MedicationMapper medications;
    @Autowired private MedicationReminderMapper reminders;
    @Autowired private MedicalRecordMapper reports;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public List<CareItem> list(Long pid,String kind){scope.requirePatient(pid);QueryWrapper<CareItem>q=new QueryWrapper<CareItem>().eq("patient_id",pid);if(kind!=null)q.eq("kind",kind);return items.selectList(q.orderByDesc("event_at").orderByDesc("id"));}
    public CareItem get(Long id){CareItem v=items.selectById(id);if(v==null)throw new IllegalArgumentException("The record does not exist.");scope.requirePatient(v.getPatientId());return v;}
    public CareItem profile(Long pid){return items.selectOne(new QueryWrapper<CareItem>().eq("patient_id",pid).eq("kind","PROFILE").last("limit 1"));}
    public Long coordinator(Long pid){CareItem p=profile(pid);Object id=p==null?null:p.getDetails().get("escalationUserId");return id==null||id.toString().isEmpty()?null:Long.valueOf(id.toString());}
    public int delayMinutes(Long pid){CareItem p=profile(pid);Object delay=p==null?null:p.getDetails().get("escalationMinutes");return delay==null?60:Math.max(15,Integer.parseInt(delay.toString()));}

    @Transactional public CareItem save(CareItem v){
        Patient patient=scope.requirePatient(v.getPatientId());if(patient==null)throw new IllegalArgumentException("SelectFamily Member");
        if(!Arrays.asList("APPOINTMENT","ORDER","SYMPTOM","QUESTION","HANDOVER","PROFILE").contains(v.getKind()))throw new IllegalArgumentException("recordtypeNonevalid");
        if(v.getTitle()==null||v.getTitle().trim().isEmpty()||v.getTitle().length()>200)throw new IllegalArgumentException("Please fillwrite200charactertowithin title");
        if(!membership.canAssign(v.getPatientId(),v.getAssignedUserId()))throw new IllegalArgumentException("responsible forpersonmustYesthis Family Member carecompletemember");
        CareItem old=v.getId()==null?null:get(v.getId());
        if(old!=null&&(!Objects.equals(old.getPatientId(),v.getPatientId())||!Objects.equals(old.getKind(),v.getKind())))throw new IllegalArgumentException("cannotchangerecordbelongFamily Member or type");
        if(old!=null&&"ORDER".equals(old.getKind()))throw new IllegalArgumentException("prescriptionchangePlease Addversionthis , keeporiginalsolution");
        if("PROFILE".equals(v.getKind())){CareItem existing=profile(v.getPatientId());v.setId(existing==null?null:existing.getId());old=existing;}
        v.setUserId(patient.getUserId());v.setActorId(scope.requireUserId());v.setActorName(CurrentUserUtil.getCurrentUsername());
        v.setStatus(old==null?("ORDER".equals(v.getKind())?"SCHEDULED":"OPEN"):old.getStatus());
        v.setCreatedAt(old==null?null:old.getCreatedAt());v.setUpdatedAt(LocalDateTime.now());
        v.setNotifiedAt(old==null?null:old.getNotifiedAt());v.setEscalatedAt(old==null?null:old.getEscalatedAt());
        Map<String,Object>d=v.getDetails();
        for(String key:Arrays.asList("reportId","appointmentId","escalationUserId"))if(d.get(key)!=null&&d.get(key).toString().trim().isEmpty())d.put(key,null);
        v.setDetails(d);
        if("APPOINTMENT".equals(v.getKind())){
            if(v.getEventAt()==null)throw new IllegalArgumentException("SelectappointmentTime");
            if(v.getNotifyAt()==null)v.setNotifyAt(v.getEventAt().minusDays(1));
            if(v.getNotifyAt().isAfter(v.getEventAt()))throw new IllegalArgumentException("ReminderTimecannotlater thanappointmentTime");
            if(d.get("nextAt")!=null&&!d.get("nextAt").toString().isEmpty()&&!LocalDateTime.parse(d.get("nextAt").toString().replace(' ','T')).isAfter(v.getEventAt()))throw new IllegalArgumentException("down timesfollow-up examinationshouldlater thanthis timesappointment");
            if(old!=null&&!Objects.equals(v.getEventAt(),old.getEventAt())){v.setNotifiedAt(null);v.setEscalatedAt(null);}
            if(d.get("reportId")!=null){MedicalRecord r=reports.selectById(Long.valueOf(d.get("reportId").toString()));if(r==null||!Objects.equals(r.getPatientId(),v.getPatientId()))throw new IllegalArgumentException("Reportdoes not belong tocurrentFamily Member");}
        }
        if("SYMPTOM".equals(v.getKind())){int severity=Integer.parseInt(String.valueOf(d.getOrDefault("severity",0)));if(severity<0||severity>10)throw new IllegalArgumentException("symptomlevelshouldin 0to 10between");if(v.getEventAt()==null)throw new IllegalArgumentException("SelectsymptomoccurTime");}
        if("QUESTION".equals(v.getKind())&&d.get("appointmentId")!=null){CareItem a=get(Long.valueOf(d.get("appointmentId").toString()));if(!"APPOINTMENT".equals(a.getKind())||!Objects.equals(a.getPatientId(),v.getPatientId()))throw new IllegalArgumentException("SelectcurrentFamily Member appointment");}
        if("PROFILE".equals(v.getKind())&&d.get("escalationUserId")!=null&&!membership.canAssign(v.getPatientId(),Long.valueOf(d.get("escalationUserId").toString())))throw new IllegalArgumentException("Selecthas valid follow-upfamily caregiver");
        if("ORDER".equals(v.getKind()))validateOrder(v);
        if(v.getId()==null)items.insert(v);else items.updateById(v);
        if("ORDER".equals(v.getKind()))activateIfDue(v,LocalDate.now());
        activity(v,"Create or Edit: "+v.getTitle());return v;
    }
    private void validateOrder(CareItem v){Map<String,Object>d=v.getDetails();
        if(!Boolean.TRUE.equals(d.get("confirmed")))throw new IllegalArgumentException("Please Confirmalready based onClinicianprescriptionverifysolution");
        Medication m=medications.selectById(Long.valueOf(String.valueOf(d.get("medicationId"))));if(m==null||!Objects.equals(m.getPatientId(),v.getPatientId()))throw new IllegalArgumentException("SelectcurrentFamily Member Medication");
        LocalDate start=LocalDate.parse(String.valueOf(d.get("startDate")));if(d.get("endDate")!=null&&!d.get("endDate").toString().isEmpty()&&LocalDate.parse(d.get("endDate").toString()).isBefore(start))throw new IllegalArgumentException("End DatecannotmorninginStart Date");
        String action=String.valueOf(d.getOrDefault("action","CHANGE"));if(!Arrays.asList("CHANGE","STOP").contains(action))throw new IllegalArgumentException("prescriptionActionsNonevalid");
        if(!"STOP".equals(action)){
            String unit=String.valueOf(d.getOrDefault("unit","tablet"));
            List<String> units=jdbc.queryForList("SELECT unit FROM medication_stock WHERE patient_id=? AND medication_id=?",String.class,v.getPatientId(),m.getId());
            if(unit.trim().isEmpty()||(!units.isEmpty()&&!unit.equals(units.get(0))))throw new IllegalArgumentException("prescriptiondosageUnitmust and MedicationInventoryUnitonecause, Please first verifyInventory");
            List<?> doses=(List<?>)d.get("doses");if(doses==null||doses.isEmpty())throw new IllegalArgumentException("Please fillwriteeach Daymedication intakeTime and dosage");
            Set<String>times=new HashSet<>();for(Object obj:doses){Map<?,?>dose=(Map<?,?>)obj;String time=LocalTime.parse(dose.get("time").toString()).toString();if(!times.add(time)||new BigDecimal(dose.get("quantity").toString()).signum()<=0)throw new IllegalArgumentException("Timecannotduplicate, dosagemustgreater than0");}
            if(!String.valueOf(d.getOrDefault("repeatDays","")).matches("[1-7](,[1-7])*"))throw new IllegalArgumentException("Selectduplicatestarperiod");
        }
        d.remove("reminderIds");v.setDetails(d);v.setEventAt(start.atStartOfDay());v.setNotifyAt(null);
    }
    @Transactional public void activateIfDue(CareItem v,LocalDate today){
        if(!"SCHEDULED".equals(v.getStatus())||v.getEventAt().toLocalDate().isAfter(today))return;
        CareItem locked=items.selectOne(new QueryWrapper<CareItem>().eq("id",v.getId()).last("FOR UPDATE"));
        if(locked==null||!"SCHEDULED".equals(locked.getStatus()))return;
        Map<String,Object>d=v.getDetails();Long mid=Long.valueOf(d.get("medicationId").toString());
        if(d.get("endDate")!=null&&!d.get("endDate").toString().isEmpty()&&LocalDate.parse(d.get("endDate").toString()).isBefore(today)){v.setStatus("STOPPED");items.updateById(v);return;}
        for(CareItem old:items.selectList(new QueryWrapper<CareItem>().eq("patient_id",v.getPatientId()).eq("kind","ORDER").eq("status","ACTIVE"))){if(String.valueOf(mid).equals(String.valueOf(old.getDetails().get("medicationId")))){old.setStatus("STOPPED");items.updateById(old);}}
        reminders.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MedicationReminder>().eq("patient_id",v.getPatientId()).eq("medication_id",mid).set("enabled",0));
        Medication med=medications.selectById(mid);boolean stop="STOP".equals(d.get("action"));med.setIsActive(stop?0:1);medications.updateById(med);
        List<Long>ids=new ArrayList<>();if(!stop)for(Object obj:(List<?>)d.get("doses")){Map<?,?>dose=(Map<?,?>)obj;MedicationReminder r=new MedicationReminder();r.setPatientId(v.getPatientId());r.setUserId(v.getUserId());r.setMedicationId(mid);r.setEnabled(1);r.setRemindTime(LocalTime.parse(dose.get("time").toString()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));r.setRepeatDays(d.get("repeatDays").toString());r.setDosage(dose.get("quantity")+" "+d.getOrDefault("unit","tablet"));r.setRemark("prescriptionversionthis  #"+v.getId());reminders.insert(r);ids.add(r.getId());}
        d.put("reminderIds",ids);v.setDetails(d);v.setStatus(stop?"STOPPED":"ACTIVE");items.updateById(v);
    }
    @Transactional public CareItem action(Long id,String status,String note){CareItem v=items.selectOne(new QueryWrapper<CareItem>().eq("id",id).last("FOR UPDATE"));
        if(v==null)throw new IllegalArgumentException("The record does not exist.");scope.requirePatient(v.getPatientId());
        String previousStatus=v.getStatus();
        if("ORDER".equals(v.getKind())){if(!"CANCELLED".equals(status)||!"SCHEDULED".equals(v.getStatus()))throw new IllegalArgumentException("take effectprescriptionPlease throughAddchange or stop medicationprescriptionprocess");}
        else if(!Arrays.asList("OPEN","DONE","CANCELLED","ANSWERED","RESOLVED").contains(status))throw new IllegalArgumentException("Invalid status.");
        v.setStatus(status);v.setActorId(scope.requireUserId());v.setActorName(CurrentUserUtil.getCurrentUsername());
        Map<String,Object>d=v.getDetails();if(note!=null)d.put("answer",note);
        if("APPOINTMENT".equals(v.getKind())&&"DONE".equals(status)&&!"DONE".equals(previousStatus)&&d.get("nextAppointmentId")==null&&d.get("nextAt")!=null&&!d.get("nextAt").toString().isEmpty()){
            CareItem next=new CareItem();next.setPatientId(v.getPatientId());next.setKind("APPOINTMENT");next.setTitle(v.getTitle());next.setAssignedUserId(v.getAssignedUserId());next.setEventAt(LocalDateTime.parse(d.get("nextAt").toString().replace(' ','T')));
            Map<String,Object>details=new LinkedHashMap<>(d);details.remove("reportId");details.remove("nextAt");details.remove("nextAppointmentId");next.setDetails(details);save(next);d.put("nextAppointmentId",next.getId());
        }
        v.setDetails(d);if("OPEN".equals(status)){v.setNotifiedAt(null);v.setEscalatedAt(null);}items.updateById(v);activity(v,"Statuschangefor  "+status+(note==null?"":": "+note));return v;
    }
    @Transactional public void delete(Long id){CareItem v=get(id);if("ORDER".equals(v.getKind()))throw new IllegalArgumentException("prescriptionhistorycannotDelete, Please Cancelnot take effectversionthis  or Addstop medicationprescription");items.deleteById(id);activity(v,"Delete: "+v.getTitle());}
    public void activity(CareItem source,String title){CareItem a=new CareItem();a.setPatientId(source.getPatientId());a.setUserId(source.getUserId());a.setKind("ACTIVITY");a.setTitle(title.length()>200?title.substring(0,200):title);a.setStatus("DONE");a.setActorId(CurrentUserUtil.getCurrentUserId());a.setActorName(CurrentUserUtil.getCurrentUsername());a.setEventAt(LocalDateTime.now());Map<String,Object>d=new HashMap<>();d.put("sourceId",source.getId());a.setDetails(d);items.insert(a);}
}
