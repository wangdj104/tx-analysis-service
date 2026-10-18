package org.familyhealthcare.task;

import org.familyhealthcare.service.NotificationAudienceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reminder and recurrence automation for appointments, vaccines, and assessments. */
@Component
public class CareJourneyTask {
    @Autowired private org.familyhealthcare.service.familyassistance.FamilyAssistanceSourceBridge familyAssistance;
    @Autowired private org.familyhealthcare.service.workflow.HouseholdAuthorityLocks householdAuthorityLocks;
    @Autowired private org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService familyAssistanceAuthorization;
    @Autowired private org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge visitFollowUp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationAudienceService audience;

    @Scheduled(cron="30 * * * * ?")
    public void run() {
        remindAppointments();
        generateFollowUps();
        remindVaccinations();
        deliverMentalAssessments();
    }

    // 游标只保证本 JVM 的有界轮询公平；重启从头扫描，不代表权限或投递成功。
    private final java.util.concurrent.atomic.AtomicBoolean appointmentReminderRunning=new java.util.concurrent.atomic.AtomicBoolean();
    private LocalDateTime appointmentReminderCursorAt,appointmentReminderLastNow;
    private Long appointmentReminderCursorId;

    private void remindAppointments() {
        remindAppointments(LocalDateTime.now());
    }
    private void remindAppointments(LocalDateTime now) {
        if(!appointmentReminderRunning.compareAndSet(false,true))return;
        try {
            LocalDateTime horizon=now.plusHours(24);
            if(appointmentReminderLastNow!=null&&(now.isBefore(appointmentReminderLastNow)||!now.isBefore(appointmentReminderLastNow.plusHours(24)))||appointmentReminderCursorAt!=null&&(appointmentReminderCursorAt.isBefore(now)||appointmentReminderCursorAt.isAfter(horizon)))clearAppointmentReminderCursor();
            appointmentReminderLastNow=now;
            boolean modern=familyAssistance.storageAvailable();
            List<Map<String,Object>> rows=reminderCandidates(now,horizon,modern);
            // 空尾最多回绕一次；没有循环补页，每 tick 返回的候选总数仍不超过 100。
            if(rows.isEmpty()&&appointmentReminderCursorAt!=null){clearAppointmentReminderCursor();rows=reminderCandidates(now,horizon,modern);}
            for(Map<String,Object> row:rows) {
                Long patientId=id(row,"patient_id"),doctorId=id(row,"doctor_user_id");LocalDateTime start=time(row.get("start_at"));
                // 不可发送或 provider 抛错也要让下轮前进，恢复资格后会在下一轮回绕重读。
                appointmentReminderCursorAt=start;appointmentReminderCursorId=id(row,"id");
                // 候选只用于精确墙钟比对；桥接内部提交CLAIM之后才进入provider，已纳管始终阻断旧出口。
                if(visitFollowUp.remindAppointment(org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.SourceKind.CARE_APPOINTMENT,id(row,"id"),patientId,org.familyhealthcare.service.workflow.WorkflowData.map("native_status",row.get("status"),"native_start_wall",start,"native_end_wall",row.get("end_at"),"native_notify_wall",null,"native_doctor_id",doctorId,"native_schedule_id",row.get("schedule_id"))))continue;
                boolean attempted=notifyLegacyAppointment(row,"APPOINTMENT_REMINDER","Appointment reminder","Upcoming appointment at "+start+". Open the shared schedule for details.");
                if(attempted)familyAssistance.markLegacyAppointmentNotified(patientId,id(row,"id"),doctorId,start,now);
            }
            if(rows.size()<100)clearAppointmentReminderCursor();
        } finally {
            appointmentReminderRunning.set(false);
        }
    }
    private void clearAppointmentReminderCursor(){appointmentReminderCursorAt=null;appointmentReminderCursorId=null;}
    private List<Map<String,Object>> reminderCandidates(LocalDateTime now,LocalDateTime horizon,boolean modern) {
        String sql="SELECT id,patient_id,doctor_user_id,schedule_id,start_at,end_at,status FROM care_appointment WHERE status='BOOKED' AND notified_at IS NULL AND start_at BETWEEN ? AND ?"+(modern?" AND family_workflow_version=0 AND family_assistance_id IS NULL":"")+(appointmentReminderCursorAt==null?"":" AND (start_at>? OR (start_at=? AND id>?))")+" ORDER BY start_at,id LIMIT 100";
        return jdbc.query(sql,ps->{
            ps.setQueryTimeout(5);ps.setObject(1,now);ps.setObject(2,horizon);
            if(appointmentReminderCursorAt!=null){ps.setObject(3,appointmentReminderCursorAt);ps.setObject(4,appointmentReminderCursorAt);ps.setLong(5,appointmentReminderCursorId);}
        },(rs,n)->{
            Map<String,Object> row=new java.util.LinkedHashMap<>();
            row.put("id",rs.getLong("id"));row.put("patient_id",rs.getLong("patient_id"));
            row.put("doctor_user_id",rs.getObject("doctor_user_id")==null?null:rs.getLong("doctor_user_id"));
            // 原生 DATETIME 是无时区墙钟；排序游标不能经 Timestamp/默认区转换。
            row.put("start_at",LocalDateTime.parse(rs.getString("start_at").replace(' ','T')));row.put("end_at",rs.getString("end_at")==null?null:LocalDateTime.parse(rs.getString("end_at").replace(' ','T')));row.put("schedule_id",rs.getObject("schedule_id")==null?null:rs.getLong("schedule_id"));row.put("status",rs.getString("status"));return row;
        });
    }
    private boolean notifyLegacyAppointment(Map<String,Object> row,String event,String title,String content) {
        long patient=id(row,"patient_id"),appointment=id(row,"id");Long doctor=id(row,"doctor_user_id");LocalDateTime start=time(row.get("start_at"));
        boolean attempted=false;
        for(long recipient:familyAssistance.legacyAppointmentRecipients(patient,doctor)) {
            if(!familyAssistance.admitLegacyAppointmentNotification(patient,appointment,null,recipient,doctor,start,(String)row.get("status")))continue;
            audience.notify(Collections.singleton(recipient),patient,event,title,content);
            attempted=true;
        }
        return attempted;
    }
    private List<Map<String,Object>> appointmentCandidates(String sql,Object...args) {
        return jdbc.query(sql,ps->{ps.setQueryTimeout(5);for(int n=0;n<args.length;n++)ps.setObject(n+1,args[n]);},new org.springframework.jdbc.core.ColumnMapRowMapper());
    }

    private void generateFollowUps() {
        LocalDateTime now=LocalDateTime.now(),horizon=now.plusHours(24);
        List<Map<String,Object>> rows=appointmentCandidates("SELECT a.* FROM care_appointment a JOIN patient p ON p.id=a.patient_id JOIN sys_user u ON u.id=a.doctor_user_id WHERE COALESCE(p.deleted,0)=0 AND u.status=1 AND COALESCE(u.deleted,0)=0 AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.role_code='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0) AND a.status IN ('BOOKED','COMPLETED') AND a.recurrence_days>0 AND a.next_follow_up_at<=?"+visitFollowUp.excludeTrackedModernRecurrence()+" ORDER BY a.next_follow_up_at,a.id LIMIT 100",horizon);
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        for(Map<String,Object> candidate:rows){
            Map<String,Object> result=transaction.execute(status->{
                // Use the same doctor lock as manual booking so concurrent schedulers cannot occupy a slot twice.
                householdAuthorityLocks.lockPatientAndSubjects(id(candidate,"patient_id"),Collections.singleton(id(candidate,"doctor_user_id")));
                List<Map<String,Object>> locked=jdbc.queryForList("SELECT * FROM care_appointment WHERE id=? FOR UPDATE",candidate.get("id"));
                if(locked.isEmpty())return null;
                Map<String,Object> row=locked.get(0);
                if(visitFollowUp.isTracked(org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.SourceKind.CARE_APPOINTMENT,id(candidate,"id")))return null;
                if(!java.util.Objects.equals(row.get("patient_id"),candidate.get("patient_id"))||!java.util.Objects.equals(row.get("doctor_user_id"),candidate.get("doctor_user_id")))return null;
                try{familyAssistanceAuthorization.requireDoctor(id(row,"doctor_user_id"));}catch(org.familyhealthcare.service.workflow.WorkflowException revoked){return null;}
                if(!java.util.Arrays.asList("BOOKED","COMPLETED").contains(row.get("status"))||row.get("next_follow_up_at")==null)return null;
                long days=((Number)row.get("recurrence_days")).longValue();
                if(days<=0)return null;
                LocalDateTime next=time(row.get("next_follow_up_at"));
                // A restarted scheduler must never create appointments in the past.
                while(next.isBefore(now))next=next.plusDays(days);
                if(next.isAfter(horizon)){jdbc.update("UPDATE care_appointment SET next_follow_up_at=? WHERE id=?",next,row.get("id"));return null;}
                long minutes=Math.max(1,Duration.between(time(row.get("start_at")),time(row.get("end_at"))).toMinutes());
                Integer same=jdbc.queryForObject("SELECT COUNT(*) FROM care_appointment WHERE patient_id=? AND doctor_user_id=? AND start_at=? AND status='BOOKED'",Integer.class,row.get("patient_id"),row.get("doctor_user_id"),next);
                if(same!=null&&same>0){jdbc.update("UPDATE care_appointment SET next_follow_up_at=NULL WHERE id=?",row.get("id"));return null;}
                Integer conflicts=jdbc.queryForObject("SELECT COUNT(*) FROM care_appointment WHERE doctor_user_id=? AND status='BOOKED' AND start_at<? AND end_at>?",Integer.class,row.get("doctor_user_id"),next.plusMinutes(minutes),next);
                Integer available=jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 AND r.role_code='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0",Integer.class,row.get("doctor_user_id"));
                row.put("follow_up_at",next);
                if(conflicts!=null&&conflicts>0||available==null||available==0){
                    jdbc.update("UPDATE care_appointment SET next_follow_up_at=? WHERE id=?",next.plusDays(days),row.get("id"));
                    row.put("needs_rebooking",true);return row;
                }
                jdbc.update("INSERT INTO care_appointment(patient_id,doctor_user_id,appointment_type,consultation_mode,start_at,end_at,status,reason,recurrence_days,next_follow_up_at,created_by) VALUES(?,?,?,?,?,?,'BOOKED',?,?,?,?)",row.get("patient_id"),row.get("doctor_user_id"),row.get("appointment_type"),row.get("consultation_mode"),next,next.plusMinutes(minutes),"Automatically generated periodic follow-up",days,next.plusDays(days),row.get("created_by"));
                // Only the new appointment owns the next recurrence; historical parents must not branch.
                jdbc.update("UPDATE care_appointment SET next_follow_up_at=NULL WHERE id=?",row.get("id"));return row;
            });
            if(result==null)continue;
            Long patientId=id(result,"patient_id"),doctorId=id(result,"doctor_user_id");
            boolean conflict=Boolean.TRUE.equals(result.get("needs_rebooking"));
            String event=conflict?"APPOINTMENT_RESCHEDULE_REQUIRED":"APPOINTMENT_UPDATED",title=conflict?"Follow-up needs rescheduling":"Appointment updated";
            String content=conflict?"The periodic follow-up at "+result.get("follow_up_at")+" could not be booked because the clinician or time slot is unavailable. Please choose another appointment time.":"Appointment scheduled for "+result.get("follow_up_at")+". The shared care calendar has been updated.";
            notifyLegacyAppointment(result,event,title,content);
        }
    }

    private void remindVaccinations() {
        LocalDateTime now=LocalDateTime.now();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM vaccination_plan WHERE status='PLANNED' AND remind_at<=? AND notified_at IS NULL",now)){
            Long patientId=id(row,"patient_id");
            audience.notifyCareTeam(patientId,"VACCINATION_REMINDER","Vaccination reminder",row.get("vaccine_name")+" dose "+row.get("dose_no")+" is planned for "+row.get("planned_date")+". This is a manually maintained plan; confirm with the vaccination provider.");
            jdbc.update("UPDATE vaccination_plan SET notified_at=NOW() WHERE id=? AND notified_at IS NULL",row.get("id"));
        }
    }

    private void deliverMentalAssessments() {
        LocalDateTime now=LocalDateTime.now();
        for(Map<String,Object> row:jdbc.queryForList("SELECT s.*,p.user_id FROM mental_assessment_schedule s JOIN patient p ON p.id=s.patient_id WHERE s.enabled=1 AND s.next_due_at<=?",now)){
            Long patientId=id(row,"patient_id"),patientUser=id(row,"user_id");
            audience.notify(Collections.singleton(patientUser),patientId,"MENTAL_ASSESSMENT_DUE","Self-assessment is due",row.get("scale_code")+" is ready. Results follow your selected privacy setting.");
            int days=Math.max(1,((Number)row.get("interval_days")).intValue());
            LocalDateTime previous=time(row.get("next_due_at"));
            LocalDateTime next=previous.plusDays((Duration.between(previous,now).toDays()/days+1)*days);
            jdbc.update("UPDATE mental_assessment_schedule SET last_notified_at=NOW(),next_due_at=? WHERE id=?",next,row.get("id"));
        }
    }

    private static Long id(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static LocalDateTime time(Object value){return value instanceof Timestamp?((Timestamp)value).toLocalDateTime():LocalDateTime.parse(value.toString().replace(' ','T'));}
}
