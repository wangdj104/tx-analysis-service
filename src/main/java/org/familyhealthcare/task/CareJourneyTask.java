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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationAudienceService audience;

    @Scheduled(cron="30 * * * * ?")
    public void run() {
        remindAppointments();
        generateFollowUps();
        remindVaccinations();
        deliverMentalAssessments();
    }

    private void remindAppointments() {
        LocalDateTime now=LocalDateTime.now(), horizon=now.plusHours(24);
        for(Map<String,Object> row:jdbc.queryForList("SELECT id,patient_id,doctor_user_id,start_at,consultation_mode FROM care_appointment WHERE status='BOOKED' AND notified_at IS NULL AND start_at BETWEEN ? AND ?",now,horizon)){
            Long patientId=id(row,"patient_id");
            String content="Upcoming "+row.get("consultation_mode")+" appointment at "+row.get("start_at")+". Open the shared schedule for details.";
            Set<Long> notified=audience.notifyCareTeam(patientId,"APPOINTMENT_REMINDER","Appointment reminder",content);
            Long doctorId=id(row,"doctor_user_id");
            if(notified==null||!notified.contains(doctorId))audience.notify(Collections.singleton(doctorId),patientId,"APPOINTMENT_REMINDER","Appointment reminder",content);
            jdbc.update("UPDATE care_appointment SET notified_at=NOW() WHERE id=? AND notified_at IS NULL",row.get("id"));
        }
    }

    private void generateFollowUps() {
        LocalDateTime now=LocalDateTime.now(),horizon=now.plusHours(24);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.* FROM care_appointment a JOIN patient p ON p.id=a.patient_id WHERE COALESCE(p.deleted,0)=0 AND a.status IN ('BOOKED','COMPLETED') AND a.recurrence_days>0 AND a.next_follow_up_at<=?",horizon);
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        for(Map<String,Object> candidate:rows){
            Map<String,Object> result=transaction.execute(status->{
                // Use the same doctor lock as manual booking so concurrent schedulers cannot occupy a slot twice.
                jdbc.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE",candidate.get("doctor_user_id"));
                List<Map<String,Object>> locked=jdbc.queryForList("SELECT * FROM care_appointment WHERE id=? FOR UPDATE",candidate.get("id"));
                if(locked.isEmpty())return null;
                Map<String,Object> row=locked.get(0);
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
            Set<Long> notified=audience.notifyCareTeam(patientId,event,title,content);
            if(notified==null||!notified.contains(doctorId))audience.notify(Collections.singleton(doctorId),patientId,event,title,content);
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
