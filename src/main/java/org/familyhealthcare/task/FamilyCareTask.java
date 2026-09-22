package org.familyhealthcare.task;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

@Component
public class FamilyCareTask {
    @Autowired private CareItemMapper items;
    @Autowired private FamilyCareService care;
    @Autowired private CareMembershipService membership;
    @Autowired private NotificationDeliveryService delivery;
    @Autowired private MedicationReminderMapper reminders;
    @Autowired private MedicationStockService stock;
    @Autowired private PatientMapper patients;
    @Autowired private JdbcTemplate jdbc;

    @Scheduled(cron="15 * * * * ?") public void tick(){
        LocalDateTime now=LocalDateTime.now();
        for(CareItem v:items.selectList(new QueryWrapper<CareItem>().in("status","SCHEDULED","ACTIVE","OPEN"))){try{
            if("ORDER".equals(v.getKind())){care.activateIfDue(v,now.toLocalDate());Map<String,Object>d=v.getDetails();Object end=d.get("endDate");if("ACTIVE".equals(v.getStatus())&&end!=null&&!end.toString().isEmpty()&&LocalDate.parse(end.toString()).isBefore(now.toLocalDate())){
                List<?>ids=(List<?>)d.getOrDefault("reminderIds",Collections.emptyList());if(!ids.isEmpty())reminders.update(null,new UpdateWrapper<MedicationReminder>().in("id",ids).set("enabled",0));v.setStatus("STOPPED");items.updateById(v);
            }continue;}
            if(!Arrays.asList("APPOINTMENT","HANDOVER").contains(v.getKind())||!"OPEN".equals(v.getStatus()))continue;
            Patient p=patients.selectById(v.getPatientId());if(p==null)continue;
            Long target=v.getAssignedUserId()==null?p.getUserId():v.getAssignedUserId();
            if(v.getNotifyAt()!=null&&!v.getNotifyAt().isAfter(now)&&v.getNotifiedAt()==null&&delivery.notifyUser(target,"FAMILY_CARE_REMINDER","Family care reminder",p.getName()+": "+v.getTitle()+" · "+v.getEventAt()+"\n"+v.getDetails().getOrDefault("note",v.getDetails().getOrDefault("preparation","")))){items.update(null,new UpdateWrapper<CareItem>().eq("id",v.getId()).eq("status","OPEN").isNull("notified_at").set("notified_at",now));}
            Long coordinator=care.coordinator(v.getPatientId());
            if(coordinator!=null&&membership.canAssign(v.getPatientId(),coordinator)&&v.getEventAt()!=null&&v.getEventAt().plusMinutes(care.delayMinutes(v.getPatientId())).isBefore(now)&&v.getEscalatedAt()==null&&delivery.notifyUser(coordinator,"CARE_FOLLOW_UP","Follow-up required",p.getName()+": "+v.getTitle())){items.update(null,new UpdateWrapper<CareItem>().eq("id",v.getId()).eq("status","OPEN").isNull("escalated_at").set("escalated_at",now));}
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Failed to process care task, id={}",v.getId(),e);}}
        for(Patient p:patients.selectList(new QueryWrapper<Patient>()))try{
            for(Map<String,Object>s:stock.snapshot(p.getId()))if(Boolean.TRUE.equals(s.get("low"))&&!Boolean.FALSE.equals(s.get("isActive"))&&!"0".equals(String.valueOf(s.get("isActive")))){Object last=s.get("notified_at");if(last!=null&&last.toString().startsWith(now.toLocalDate().toString()))continue;
                if(delivery.notifyUser(p.getUserId(),"MEDICATION_RESTOCK","Medication restock reminder",p.getName()+": "+s.get("drugName")+" — "+s.get("quantity")+" "+s.get("unit")+" remaining"))jdbc.update("UPDATE medication_stock SET notified_at=? WHERE id=?",now,s.get("id"));
            }
            Long coordinator=care.coordinator(p.getId());if(coordinator==null||!membership.canAssign(p.getId(),coordinator))continue;
            for(Map<String,Object>r:jdbc.queryForList("SELECT i.id,i.scheduled_at,m.drug_name FROM medication_intake i LEFT JOIN medication m ON m.id=i.medication_id WHERE i.patient_id=? AND i.status IN ('PENDING','MISSED','SNOOZED') AND COALESCE(i.snooze_until,i.scheduled_at)<? AND i.scheduled_at>=? AND NOT EXISTS(SELECT 1 FROM care_item c WHERE c.kind='ESCALATION' AND JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.intakeId'))=CAST(i.id AS CHAR))",p.getId(),now.minusMinutes(care.delayMinutes(p.getId())),now.toLocalDate().atStartOfDay())){
                if(delivery.notifyUser(coordinator,"MEDICATION_MISSED","Medication intake not confirmed",p.getName()+": "+r.get("drug_name")+" · "+r.get("scheduled_at"))){CareItem mark=new CareItem();mark.setPatientId(p.getId());mark.setUserId(p.getUserId());mark.setKind("ESCALATION");mark.setTitle("Care coordinator notified about medication intake");mark.setStatus("DONE");mark.setEventAt(now);mark.setDetails(Collections.singletonMap("intakeId",r.get("id")));items.insert(mark);}
            }
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Failed to check patient reminders, patientId={}",p.getId(),e);}
    }
}
