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
    @Autowired private org.familyhealthcare.service.familyassistance.FamilyAssistanceSourceBridge familyAssistance;
    @Autowired private org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge visitFollowUp;
    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard medicationGuard;
    @Autowired private CareItemMapper items;
    @Autowired private FamilyCareService care;
    @Autowired private CareMembershipService membership;
    @Autowired private NotificationDeliveryService delivery;
    @Autowired private NotificationAudienceService audience;
    @Autowired private MedicationReminderMapper reminders;
    @Autowired private MedicationStockService stock;
    @Autowired private org.familyhealthcare.service.stockworkflow.StockWorkflowTrackingGuard stockGuard;
    @Autowired private PatientMapper patients;
    @Autowired private JdbcTemplate jdbc;

    private boolean notifyCurrentRecipient(Long patientId, Long target, String eventType, String title, String content) {
        // 旧分配不是永久外发授权：发送前重查当前患者和模块权限，但仍只通知原指定人，不扩大收件范围。
        // 校验与外部投递并不原子；被抑制或投递失败都返回 false，不能记成已成功通知。
        if (target == null || !audience.recipients(patientId, eventType).contains(target)) return false;
        return delivery.notifyUser(target, eventType, title, content);
    }

    private boolean notifyLegacyStock(Long patientId,long stockId) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stock transport cannot join a business transaction.");
        org.familyhealthcare.service.stockworkflow.StockWorkflowTrackingGuard.Candidate candidate=stockGuard.candidate(patientId,stockId);
        if(candidate==null)return false;
        final boolean[] checkFailed={false};
        NotificationDeliveryService.GuardedBatchResult result=delivery.notifyUserOutcomesGuarded(candidate.getRecipient(),Collections.emptyList(),"MEDICATION_RESTOCK","Medication restock reminder",candidate.getContent(),
            new NotificationDeliveryService.BeforeAttemptGate(){
                private NotificationDeliveryService.GateDecision check(Long user,Long channel){
                    try{return stockGuard.recheck(candidate,user,channel)?NotificationDeliveryService.GateDecision.ALLOW:NotificationDeliveryService.GateDecision.STOP;}
                    catch(RuntimeException unavailable){checkFailed[0]=true;throw unavailable;}
                }
                public NotificationDeliveryService.GateDecision beforeRecipient(Long user){return check(user,null);}
                public NotificationDeliveryService.GateDecision beforeChannel(Long user,Long channel){return check(user,channel);}
            },(channel,outcome)->{});
        if(result!=null&&result.getAttemptedOutcomes().containsValue(org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome.DELIVERED))stockGuard.markLegacyNotified(candidate,LocalDateTime.now());
        return checkFailed[0];
    }

    @Scheduled(cron="15 * * * * ?") public void tick(){
        LocalDateTime now=LocalDateTime.now();
        for(CareItem v:items.selectList(new QueryWrapper<CareItem>().in("status","SCHEDULED","ACTIVE","OPEN"))){try{
            if("ORDER".equals(v.getKind())){care.activateIfDue(v,now.toLocalDate());care.endOrderIfDue(v.getId(),now.toLocalDate());continue;}
            if(!Arrays.asList("APPOINTMENT","HANDOVER").contains(v.getKind())||!"OPEN".equals(v.getStatus()))continue;
            // 已纳管预约只走一次revision尝试；未纳管来源继续原提醒与升级分支。
            if("APPOINTMENT".equals(v.getKind())&&visitFollowUp.remindAppointment(org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.SourceKind.CARE_ITEM_APPOINTMENT,v.getId(),v.getPatientId(),org.familyhealthcare.service.workflow.WorkflowData.map("native_status",v.getStatus(),"native_start_wall",v.getEventAt(),"native_end_wall",null,"native_notify_wall",v.getNotifyAt(),"native_doctor_id",null,"native_schedule_id",null)))continue;
            if(!familyAssistance.admitLegacyNotification(v.getPatientId(),v.getId()))continue;
            Patient p=patients.selectById(v.getPatientId());if(p==null)continue;
            Long target=v.getAssignedUserId()==null?p.getUserId():v.getAssignedUserId();
            if(v.getNotifyAt()!=null&&!v.getNotifyAt().isAfter(now)&&v.getNotifiedAt()==null&&familyAssistance.admitLegacyNotification(v.getPatientId(),v.getId())&&notifyCurrentRecipient(v.getPatientId(),target,"FAMILY_CARE_REMINDER","Family care reminder",p.getName()+": "+v.getTitle()+" · "+v.getEventAt()+"\n"+v.getDetails().getOrDefault("note",v.getDetails().getOrDefault("preparation","")))){items.update(null,new UpdateWrapper<CareItem>().eq("id",v.getId()).eq("status","OPEN").eq(v.getEventAt()!=null,"event_at",v.getEventAt()).isNull(v.getEventAt()==null,"event_at").eq(v.getNotifyAt()!=null,"notify_at",v.getNotifyAt()).isNull(v.getNotifyAt()==null,"notify_at").eq(familyAssistance.storageAvailable(),"family_workflow_version",0).isNull(familyAssistance.storageAvailable(),"family_assistance_id").isNull("notified_at").set("notified_at",now));}
            Long coordinator=care.coordinator(v.getPatientId());
            if(coordinator!=null&&membership.canAssign(v.getPatientId(),coordinator)&&v.getEventAt()!=null&&v.getEventAt().plusMinutes(care.delayMinutes(v.getPatientId())).isBefore(now)&&v.getEscalatedAt()==null&&familyAssistance.admitLegacyNotification(v.getPatientId(),v.getId())&&notifyCurrentRecipient(v.getPatientId(),coordinator,"CARE_FOLLOW_UP","Follow-up required",p.getName()+": "+v.getTitle())){items.update(null,new UpdateWrapper<CareItem>().eq("id",v.getId()).eq("status","OPEN").eq(v.getEventAt()!=null,"event_at",v.getEventAt()).isNull(v.getEventAt()==null,"event_at").eq(v.getNotifyAt()!=null,"notify_at",v.getNotifyAt()).isNull(v.getNotifyAt()==null,"notify_at").eq(familyAssistance.storageAvailable(),"family_workflow_version",0).isNull(familyAssistance.storageAvailable(),"family_assistance_id").isNull("escalated_at").set("escalated_at",now));}
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Failed to process care task, id={}",v.getId(),e);}}
        int stockFailures=0;
        org.familyhealthcare.service.archivestock.ArchiveStockNotificationSchema.Status stockPolicyStatus=null;
        try{stockPolicyStatus=stockGuard.notificationPolicyStatus();}catch(RuntimeException unavailable){/* No patient facts in module-health reporting. */}
        boolean stockPolicyReady=stockPolicyStatus==org.familyhealthcare.service.archivestock.ArchiveStockNotificationSchema.Status.READY;
        for(Patient p:patients.selectList(new QueryWrapper<Patient>())){
            // A policy/storage failure is local to stock and never skips the independent missed-intake branch.
            boolean stockCheckFailed=false;
            try{
                if(stockPolicyReady)for(Map<String,Object>s:stock.snapshot(p.getId()))if(Boolean.TRUE.equals(s.get("low"))&&!Boolean.FALSE.equals(s.get("isActive"))&&!"0".equals(String.valueOf(s.get("isActive")))){
                    Object last=s.get("notified_at");if(last!=null&&last.toString().startsWith(now.toLocalDate().toString()))continue;
                    stockCheckFailed|=notifyLegacyStock(p.getId(),((Number)s.get("id")).longValue());
                }
            }catch(RuntimeException unverifiable){stockCheckFailed=true;}
            if(stockCheckFailed)stockFailures++;
            try{
            Long coordinator=care.coordinator(p.getId());if(coordinator==null||!membership.canAssign(p.getId(),coordinator))continue;
            for(Map<String,Object>r:jdbc.queryForList("SELECT i.id,i.patient_id,i.medication_id,i.user_id,i.reminder_id,i.status,i.scheduled_at,i.snooze_until,m.drug_name FROM medication_intake i LEFT JOIN medication m ON m.id=i.medication_id WHERE i.patient_id=? "+(medicationGuard.storageAvailable()?"AND i.workflow_version=0 AND i.execution_id IS NULL ":"")+"AND i.status IN ('PENDING','MISSED','SNOOZED') AND COALESCE(i.snooze_until,i.scheduled_at)<? AND i.scheduled_at>=? AND NOT EXISTS(SELECT 1 FROM care_item c WHERE c.kind='ESCALATION' AND JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.intakeId'))=CAST(i.id AS CHAR))",p.getId(),now.minusMinutes(care.delayMinutes(p.getId())),now.toLocalDate().atStartOfDay())){
                MedicationIntake candidate=legacyCandidate(r);
                if(audience.recipients(p.getId(),"MEDICATION_MISSED").contains(coordinator)&&medicationGuard.admitLegacyIntakeNotification(candidate,candidate.getStatus())&&notifyCurrentRecipient(p.getId(),coordinator,"MEDICATION_MISSED","Medication intake not confirmed",p.getName()+": "+r.get("drug_name")+" · "+r.get("scheduled_at"))){CareItem mark=new CareItem();mark.setPatientId(p.getId());mark.setUserId(p.getUserId());mark.setKind("ESCALATION");mark.setTitle("已通知照护协调人跟进用药");mark.setStatus("DONE");mark.setEventAt(now);mark.setDetails(Collections.singletonMap("intakeId",r.get("id")));medicationGuard.mutateLegacyIntake(candidate,candidate.getStatus(),()->items.insert(mark));}
            }
            }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Failed to check patient reminders, patientId={}",p.getId(),e);}
        }
        if(!stockPolicyReady||stockFailures>0)org.slf4j.LoggerFactory.getLogger(getClass()).warn("Stock reminder checks unavailable: policy schema={}, failed patient scopes={}; other reminder checks continued",stockPolicyStatus==null?"UNVERIFIED":stockPolicyStatus.name(),stockFailures);
    }
    private static MedicationIntake legacyCandidate(Map<String,Object> row) {
        MedicationIntake intake=new MedicationIntake();intake.setId(((Number)row.get("id")).longValue());intake.setPatientId(((Number)row.get("patient_id")).longValue());intake.setMedicationId(((Number)row.get("medication_id")).longValue());intake.setUserId(((Number)row.get("user_id")).longValue());intake.setReminderId(row.get("reminder_id")==null?null:((Number)row.get("reminder_id")).longValue());intake.setStatus((String)row.get("status"));intake.setScheduledAt(localTime(row.get("scheduled_at")));intake.setSnoozeUntil(localTime(row.get("snooze_until")));return intake;
    }
    private static LocalDateTime localTime(Object value){return value==null?null:value instanceof java.sql.Timestamp?((java.sql.Timestamp)value).toLocalDateTime():(LocalDateTime)value;}

}
