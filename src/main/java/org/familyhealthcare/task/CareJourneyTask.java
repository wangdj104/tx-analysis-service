package org.familyhealthcare.task;

import org.familyhealthcare.service.NotificationAudienceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        for(Map<String,Object> row:jdbc.queryForList("SELECT id,patient_id,start_at,consultation_mode FROM care_appointment WHERE status='BOOKED' AND notified_at IS NULL AND start_at BETWEEN ? AND ?",now,horizon)){
            Long patientId=id(row,"patient_id");
            audience.notifyCareTeam(patientId,"APPOINTMENT_REMINDER","Appointment reminder","Upcoming "+row.get("consultation_mode")+" appointment at "+row.get("start_at")+". Open the shared schedule for details.");
            jdbc.update("UPDATE care_appointment SET notified_at=NOW() WHERE id=? AND notified_at IS NULL",row.get("id"));
        }
    }

    private void generateFollowUps() {
        LocalDateTime now=LocalDateTime.now();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM care_appointment WHERE status IN ('BOOKED','COMPLETED') AND recurrence_days IS NOT NULL AND recurrence_days>0 AND next_follow_up_at IS NOT NULL AND next_follow_up_at<=?",now);
        for(Map<String,Object> row:rows){
            LocalDateTime previousStart=time(row.get("start_at")),previousEnd=time(row.get("end_at")),next=time(row.get("next_follow_up_at"));
            long minutes=Math.max(15,Duration.between(previousStart,previousEnd).toMinutes());
            Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM care_appointment WHERE patient_id=? AND doctor_user_id=? AND start_at=? AND status='BOOKED'",Integer.class,row.get("patient_id"),row.get("doctor_user_id"),next);
            if(exists==null||exists==0)jdbc.update("INSERT INTO care_appointment(patient_id,doctor_user_id,appointment_type,consultation_mode,start_at,end_at,status,reason,recurrence_days,next_follow_up_at,created_by) VALUES(?,?,?,?,?,?,'BOOKED',?,?,?,?)",row.get("patient_id"),row.get("doctor_user_id"),row.get("appointment_type"),row.get("consultation_mode"),next,next.plusMinutes(minutes),"Automatically generated periodic follow-up",row.get("recurrence_days"),next.plusDays(((Number)row.get("recurrence_days")).longValue()),row.get("created_by"));
            jdbc.update("UPDATE care_appointment SET next_follow_up_at=? WHERE id=?",next.plusDays(((Number)row.get("recurrence_days")).longValue()),row.get("id"));
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
            int days=((Number)row.get("interval_days")).intValue();
            jdbc.update("UPDATE mental_assessment_schedule SET last_notified_at=NOW(),next_due_at=? WHERE id=?",time(row.get("next_due_at")).plusDays(days),row.get("id"));
        }
    }

    private static Long id(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static LocalDateTime time(Object value){return value instanceof Timestamp?((Timestamp)value).toLocalDateTime():LocalDateTime.parse(value.toString().replace(' ','T'));}
}
