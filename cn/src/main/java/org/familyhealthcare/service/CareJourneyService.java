package org.familyhealthcare.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Application service for the longitudinal care journey. It intentionally keeps related
 * cross-role workflows together so authorization, notification, and audit boundaries are
 * consistent across measurements, appointments, consultations, rehabilitation, and specialty care.
 */
@Service
public class CareJourneyService {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataScopeHelper scope;
    @Autowired private NotificationAudienceService audience;

    private static final Set<String> METRICS = new HashSet<>(Arrays.asList("BP","GLUCOSE","SPO2","WEIGHT","HEART_RATE","TEMPERATURE","CUSTOM"));
    private static final Set<String> ACCESS_LEVELS = new HashSet<>(Arrays.asList("READ","WRITE","PROXY"));
    private static final Set<String> CONSULT_MODES = new HashSet<>(Arrays.asList("TEXT","VOICE","VIDEO"));

    public List<Map<String,Object>> measurements(Long patientId, String metricType, LocalDateTime from, LocalDateTime to) {
        requireRead(patientId, "MEASUREMENTS");
        StringBuilder sql = new StringBuilder("SELECT m.*,u.real_name AS recorder_name FROM health_measurement m LEFT JOIN sys_user u ON u.id=m.recorded_by WHERE m.patient_id=?");
        List<Object> args = new ArrayList<>(); args.add(patientId);
        if (metricType != null && !metricType.trim().isEmpty()) { sql.append(" AND m.metric_type=?"); args.add(metricType.trim().toUpperCase()); }
        if (from != null) { sql.append(" AND m.measured_at>=?"); args.add(from); }
        if (to != null) { sql.append(" AND m.measured_at<=?"); args.add(to); }
        sql.append(" ORDER BY m.measured_at DESC,m.id DESC LIMIT 1000");
        List<Map<String,Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String,Object> row : rows) row.put("annotations", jdbc.queryForList("SELECT a.*,u.real_name AS doctor_name FROM measurement_annotation a LEFT JOIN sys_user u ON u.id=a.doctor_user_id WHERE a.measurement_id=? ORDER BY a.created_at", row.get("id")));
        return rows;
    }

    @Transactional
    public Map<String,Object> saveMeasurement(Map<String,Object> body) {
        Long patientId = requiredLong(body,"patientId"); requireWrite(patientId,"MEASUREMENTS");
        String type = required(body,"metricType").toUpperCase();
        if (!METRICS.contains(type)) throw new IllegalArgumentException("Unsupported metric type.");
        BigDecimal primary = decimal(body,"valuePrimary",true), secondary = decimal(body,"valueSecondary",false);
        validateMeasurement(type, primary, secondary);
        String unit = measurementUnit(type,required(body,"unit"));
        LocalDateTime at = dateTime(body.get("measuredAt"), LocalDateTime.now());
        // Preserve the entered value; convert glucose only for comparison with the existing mmol/L thresholds.
        // NIDDK: https://www.niddk.nih.gov/-/media/Files/Strategic-Plans/Diabetes-in-America-3rd-Edition/DIA_Conversions.pdf
        BigDecimal assessedPrimary = "GLUCOSE".equals(type) && "mg/dL".equals(unit)
                ? primary.divide(new BigDecimal("18"),8,java.math.RoundingMode.HALF_UP) : primary;
        String status = measurementStatus(patientId,type,assessedPrimary,secondary);
        long id = insert("INSERT INTO health_measurement(patient_id,recorded_by,metric_type,metric_name,value_primary,value_secondary,unit,measured_at,source_type,status,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                patientId,userId(),type,text(body,"metricName",null),primary,secondary,unit,at,text(body,"sourceType","MANUAL"),status,text(body,"remark",null));
        if (!"NORMAL".equals(status)) audience.notifyCareTeam(patientId,"MEASUREMENT_ALERT","Abnormal health measurement",type+" recorded at "+at+" crossed the configured safety range. Please review and confirm the next action.");
        return one("SELECT * FROM health_measurement WHERE id=?",id);
    }

    public Map<String,Object> annotateMeasurement(Long id, String annotation) {
        if (!CurrentUserUtil.isAdmin() && !CurrentUserUtil.hasRole("doctor")) throw new IllegalStateException("Clinician access is required.");
        Map<String,Object> m = one("SELECT * FROM health_measurement WHERE id=?",id);
        if (m == null) throw new IllegalArgumentException("Measurement not found.");
        Long patientId = number(m.get("patient_id")); requireWrite(patientId,"MEASUREMENTS");
        if (annotation == null || annotation.trim().isEmpty()) throw new IllegalArgumentException("Annotation is required.");
        long annotationId=insert("INSERT INTO measurement_annotation(measurement_id,patient_id,doctor_user_id,annotation) VALUES(?,?,?,?)",id,patientId,userId(),limit(annotation,1000));
        return one("SELECT * FROM measurement_annotation WHERE id=?",annotationId);
    }

    public List<Map<String,Object>> grants(Long patientId) {
        requireOwner(patientId);
        return jdbc.queryForList("SELECT g.*,u.username,u.real_name FROM care_access_grant g JOIN sys_user u ON u.id=g.grantee_user_id WHERE g.patient_id=? ORDER BY g.created_at DESC",patientId);
    }

    @Transactional
    public Map<String,Object> saveGrant(Map<String,Object> body) {
        Long patientId=requiredLong(body,"patientId"), grantee=requiredLong(body,"granteeUserId"); requireOwner(patientId);
        String role=required(body,"granteeRole").toUpperCase(), level=required(body,"accessLevel").toUpperCase();
        if(!Arrays.asList("DOCTOR","FAMILY","GUARDIAN").contains(role) || !ACCESS_LEVELS.contains(level)) throw new IllegalArgumentException("Invalid access grant.");
        if(Objects.equals(grantee,userId())) throw new IllegalArgumentException("The record owner already has full access.");
        Integer enabled=jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",Integer.class,grantee);
        if(enabled==null||enabled==0)throw new IllegalArgumentException("The selected user does not exist or is disabled.");
        if("DOCTOR".equals(role))requireActiveDoctor(grantee);
        LocalDateTime expires=dateTime(body.get("expiresAt"),null);
        if(expires!=null&&!expires.isAfter(LocalDateTime.now()))throw new IllegalArgumentException("Access expiry must be in the future.");
        jdbc.update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,granted_by,expires_at) VALUES(?,?,?,?,?,'ACTIVE',?,?) ON DUPLICATE KEY UPDATE access_level=VALUES(access_level),visible_modules=VALUES(visible_modules),status='ACTIVE',granted_by=VALUES(granted_by),expires_at=VALUES(expires_at)",patientId,grantee,role,level,text(body,"visibleModules",null),userId(),expires);
        return one("SELECT * FROM care_access_grant WHERE patient_id=? AND grantee_user_id=? AND grantee_role=?",patientId,grantee,role);
    }

    public void revokeGrant(Long id) {
        Map<String,Object> grant=one("SELECT * FROM care_access_grant WHERE id=?",id); if(grant==null)return;
        requireOwner(number(grant.get("patient_id"))); jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE id=?",id);
    }

    public List<Map<String,Object>> doctorSchedules(Long doctorId, LocalDate from, LocalDate to) {
        Long id=doctorId==null?userId():doctorId;
        return jdbc.queryForList("SELECT * FROM doctor_schedule WHERE doctor_user_id=? AND work_date BETWEEN ? AND ? ORDER BY work_date,start_time",id,from==null?LocalDate.now():from,to==null?LocalDate.now().plusDays(30):to);
    }

    public List<Map<String,Object>> clinicians(){return jdbc.queryForList("SELECT DISTINCT u.id,u.username,u.real_name FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE r.role_code='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0 AND u.status=1 AND COALESCE(u.deleted,0)=0 ORDER BY COALESCE(u.real_name,u.username)");}

    public Map<String,Object> saveDoctorSchedule(Map<String,Object> body) {
        requireDoctor(); Long doctorId=body.get("doctorUserId")==null?userId():requiredLong(body,"doctorUserId");
        if(!CurrentUserUtil.isAdmin()&&!Objects.equals(doctorId,userId()))throw new IllegalStateException("You can only manage your own schedule.");
        requireActiveDoctor(doctorId);
        LocalDate day=LocalDate.parse(required(body,"workDate")); LocalTime start=LocalTime.parse(required(body,"startTime")); LocalTime end=LocalTime.parse(required(body,"endTime"));
        if(!end.isAfter(start))throw new IllegalArgumentException("End time must be after start time.");
        Integer slot = integer(body,"slotMinutes",30);
        if (slot<1 || slot>java.time.Duration.between(start,end).toMinutes()) throw new IllegalArgumentException("The slot length must fit inside the clinician availability.");
        long id=insert("INSERT INTO doctor_schedule(doctor_user_id,work_date,start_time,end_time,slot_minutes,location,consultation_modes,status) VALUES(?,?,?,?,?,?,?,?)",doctorId,day,start,end,integer(body,"slotMinutes",30),text(body,"location",null),text(body,"consultationModes","IN_PERSON,TEXT,VOICE,VIDEO"),text(body,"status","AVAILABLE"));
        return one("SELECT * FROM doctor_schedule WHERE id=?",id);
    }

    public List<Map<String,Object>> appointments(Long patientId) {
        requireRead(patientId,"APPOINTMENTS");
        return jdbc.queryForList("SELECT a.*,u.real_name AS doctor_name FROM care_appointment a LEFT JOIN sys_user u ON u.id=a.doctor_user_id WHERE a.patient_id=? ORDER BY a.start_at DESC",patientId);
    }

    /** Booking a clinician shares this appointment, not the patient's complete medical record. */
    public List<Map<String,Object>> appointmentInbox() {
        requireDoctor();
        String sql="SELECT a.*,p.name AS patient_name,u.real_name AS doctor_name FROM care_appointment a JOIN patient p ON p.id=a.patient_id LEFT JOIN sys_user u ON u.id=a.doctor_user_id WHERE COALESCE(p.deleted,0)=0";
        if(CurrentUserUtil.isAdmin())return jdbc.queryForList(sql+" ORDER BY CASE WHEN a.status='BOOKED' THEN 0 ELSE 1 END,a.start_at DESC LIMIT 200");
        return jdbc.queryForList(sql+" AND a.doctor_user_id=? ORDER BY CASE WHEN a.status='BOOKED' THEN 0 ELSE 1 END,a.start_at DESC LIMIT 200",userId());
    }

    @Transactional
    public Map<String,Object> saveAppointment(Map<String,Object> body) {
        Long patientId=requiredLong(body,"patientId"), doctorId=requiredLong(body,"doctorUserId"); requireWrite(patientId,"APPOINTMENTS");
        requireActiveDoctor(doctorId);
        one("SELECT id FROM sys_user WHERE id=? FOR UPDATE",doctorId);
        LocalDateTime start=dateTime(body.get("startAt"),null), end=dateTime(body.get("endAt"),null);
        if(start==null||end==null||!end.isAfter(start))throw new IllegalArgumentException("A valid appointment time is required.");
        int recurrence = integer(body,"recurrenceDays",0);
        if (recurrence<0 || recurrence>3650) throw new IllegalArgumentException("Follow-up interval must be between 0 and 3650 days.");
        body = new LinkedHashMap<>(body);
        LocalDateTime next = dateTime(body.get("nextFollowUpAt"),null);
        body.put("nextFollowUpAt",recurrence==0?null:next==null||!next.isAfter(start)?start.plusDays(recurrence):next);
        Long scheduleId=optionalLong(body,"scheduleId");
        if(scheduleId!=null){Map<String,Object>s=one("SELECT * FROM doctor_schedule WHERE id=? AND doctor_user_id=? AND status='AVAILABLE'",scheduleId,doctorId);if(s==null)throw new IllegalArgumentException("The selected clinician availability is no longer available.");LocalDate workDate=LocalDate.parse(s.get("work_date").toString());LocalDateTime availableFrom=LocalDateTime.of(workDate,LocalTime.parse(s.get("start_time").toString())),availableTo=LocalDateTime.of(workDate,LocalTime.parse(s.get("end_time").toString()));if(start.isBefore(availableFrom)||end.isAfter(availableTo))throw new IllegalArgumentException("The appointment must fit inside the selected clinician availability.");}
        Long appointmentId=body.get("id")==null?null:requiredLong(body,"id");
        Map<String,Object> current=appointmentId==null?null:one("SELECT * FROM care_appointment WHERE id=? FOR UPDATE",appointmentId);
        if(appointmentId!=null&&(current==null||!Objects.equals(number(current.get("patient_id")),patientId)))throw new IllegalArgumentException("Appointment not found.");
        if(current!=null&&!"BOOKED".equals(current.get("status")))throw new IllegalArgumentException("Only a booked appointment can be rescheduled.");
        Integer conflicts=jdbc.queryForObject("SELECT COUNT(*) FROM care_appointment WHERE doctor_user_id=? AND status='BOOKED' AND start_at<? AND end_at>? AND (? IS NULL OR id<>?)",Integer.class,doctorId,end,start,appointmentId,appointmentId);
        if(conflicts!=null&&conflicts>0)throw new IllegalStateException("The clinician time slot is already occupied.");
        if(appointmentId==null){appointmentId=insert("INSERT INTO care_appointment(patient_id,doctor_user_id,schedule_id,appointment_type,consultation_mode,start_at,end_at,status,reason,recurrence_days,next_follow_up_at,created_by) VALUES(?,?,?,?,?,?,?,'BOOKED',?,?,?,?)",patientId,doctorId,scheduleId,text(body,"appointmentType","FOLLOW_UP"),text(body,"consultationMode","IN_PERSON"),start,end,text(body,"reason",null),integer(body,"recurrenceDays",null),dateTime(body.get("nextFollowUpAt"),null),userId());}
        else {jdbc.update("UPDATE care_appointment SET doctor_user_id=?,schedule_id=?,appointment_type=?,consultation_mode=?,start_at=?,end_at=?,reason=?,recurrence_days=?,next_follow_up_at=?,notified_at=NULL WHERE id=?",doctorId,scheduleId,text(body,"appointmentType","FOLLOW_UP"),text(body,"consultationMode","IN_PERSON"),start,end,text(body,"reason",null),integer(body,"recurrenceDays",null),dateTime(body.get("nextFollowUpAt"),null),appointmentId);}
        notifyAppointment(patientId,doctorId,"APPOINTMENT_UPDATED","Appointment updated","Appointment scheduled for "+start+". The shared care calendar has been updated.");
        jdbc.update("UPDATE care_appointment SET notified_at=NULL WHERE id=?",appointmentId);
        return one("SELECT * FROM care_appointment WHERE id=?",appointmentId);
    }

    @Transactional public void cancelAppointment(Long id, String reason) {
        Map<String,Object> appointment = one("SELECT * FROM care_appointment WHERE id=? FOR UPDATE",id);
        if (appointment == null) throw new IllegalArgumentException("Appointment not found.");
        Long patientId = number(appointment.get("patient_id")),doctorId=number(appointment.get("doctor_user_id"));
        if(!CurrentUserUtil.hasRole("doctor")||!Objects.equals(doctorId,userId()))requireWrite(patientId,"APPOINTMENTS");
        if ("CANCELLED".equals(appointment.get("status"))) return;
        if (!"BOOKED".equals(appointment.get("status")))throw new IllegalArgumentException("Only a booked appointment can be cancelled.");
        jdbc.update("UPDATE care_appointment SET status='CANCELLED',cancel_reason=?,next_follow_up_at=NULL WHERE id=?",limit(reason,500),id);
        notifyAppointment(patientId,doctorId,"APPOINTMENT_CANCELLED","Appointment cancelled",reason==null?"The shared appointment was cancelled.":reason);
    }

    @Transactional public Map<String,Object> completeAppointment(Long id) {
        requireDoctor();Map<String,Object> appointment=one("SELECT * FROM care_appointment WHERE id=? FOR UPDATE",id);
        if(appointment==null)throw new IllegalArgumentException("Appointment not found.");
        if(!CurrentUserUtil.isAdmin()&&!Objects.equals(number(appointment.get("doctor_user_id")),userId()))throw new IllegalStateException("Only the selected clinician can complete this appointment.");
        if("COMPLETED".equals(appointment.get("status")))return appointment;
        if(!"BOOKED".equals(appointment.get("status")))throw new IllegalArgumentException("Only a booked appointment can be completed.");
        jdbc.update("UPDATE care_appointment SET status='COMPLETED' WHERE id=?",id);
        return one("SELECT * FROM care_appointment WHERE id=?",id);
    }

    private void notifyAppointment(Long patientId,Long doctorId,String eventType,String title,String content) {
        Set<Long> notified=audience.notifyCareTeam(patientId,eventType,title,content);
        if(doctorId!=null&&(notified==null||!notified.contains(doctorId)))audience.notify(Collections.singleton(doctorId),patientId,eventType,title,content);
    }

    public List<Map<String,Object>> visits(Long patientId) {
        requireRead(patientId, "VISITS");
        String publishedOnly = CurrentUserUtil.isAdmin() || CurrentUserUtil.hasRole("doctor") ? "" : " AND v.status='PUBLISHED'";
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT v.*,u.real_name AS doctor_name FROM visit_record v LEFT JOIN sys_user u ON u.id=v.doctor_user_id WHERE v.patient_id=?" + publishedOnly + " ORDER BY v.visited_at DESC", patientId);
        for (Map<String,Object> row : rows) row.put("prescriptions", prescriptions(number(row.get("id")), patientId));
        return rows;
    }

    public List<Map<String,Object>> prescriptions(Long patientId) {
        requireRead(patientId, "MEDICATION");
        return prescriptions(null, patientId);
    }

    @Transactional public Map<String,Object> saveVisit(Map<String,Object> body) {
        requireDoctor(); Long patientId = requiredLong(body, "patientId"); requireWrite(patientId, "VISITS");
        Long appointmentId = optionalLong(body, "appointmentId");
        if (appointmentId != null && one("SELECT id FROM care_appointment WHERE id=? AND patient_id=?", appointmentId, patientId) == null) throw new IllegalArgumentException("Appointment not found for this patient.");
        String diagnosis = text(body, "diagnosisSummary", null), treatment = text(body, "treatmentSummary", null);
        if (diagnosis == null && treatment == null) throw new IllegalArgumentException("Enter a diagnosis or treatment summary.");
        long id = insert("INSERT INTO visit_record(appointment_id,patient_id,doctor_user_id,visited_at,diagnosis_summary,treatment_summary,follow_up_advice,follow_up_at,status) VALUES(?,?,?,?,?,?,?,?,'DRAFT')", appointmentId, patientId, userId(), dateTime(body.get("visitedAt"), LocalDateTime.now()), diagnosis, treatment, text(body,"followUpAdvice",null), dateTime(body.get("followUpAt"),null));
        return one("SELECT * FROM visit_record WHERE id=?",id);
    }

    @Transactional public Map<String,Object> publishVisit(Long id) {
        requireDoctor(); Map<String,Object> visit = one("SELECT * FROM visit_record WHERE id=? FOR UPDATE", id);
        if (visit == null) throw new IllegalArgumentException("Visit record not found.");
        Long patientId = number(visit.get("patient_id")); requireWrite(patientId, "VISITS");
        if ("PUBLISHED".equals(visit.get("status"))) return visit;
        jdbc.update("UPDATE visit_record SET status='PUBLISHED',published_at=NOW() WHERE id=?", id);
        Long owner = jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?", Long.class, patientId);
        jdbc.update("INSERT INTO health_event(user_id,patient_id,event_date,event_time,event_type,title,summary,source_type,source_id,status) VALUES(?,?,CURRENT_DATE,?,'VISIT','Visit summary',?,'VISIT_RECORD',?,'RECORDED')", owner, patientId, LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), limit(text(visit,"diagnosis_summary",text(visit,"treatment_summary",null)),500), id);
        audience.notifyCareTeam(patientId, "VISIT_SUMMARY", "Visit summary published", "A clinician published the visit summary and follow-up instructions.");
        return one("SELECT * FROM visit_record WHERE id=?", id);
    }

    @SuppressWarnings("unchecked") @Transactional public Map<String,Object> savePrescription(Map<String,Object> body) {
        requireDoctor(); Long patientId = requiredLong(body, "patientId"); requireWrite(patientId, "MEDICATION");
        Long visitId = optionalLong(body, "visitId");
        if (visitId != null && one("SELECT id FROM visit_record WHERE id=? AND patient_id=?", visitId, patientId) == null) throw new IllegalArgumentException("Visit record not found for this patient.");
        Object rawItems = body.get("items");
        if (!(rawItems instanceof Collection) || ((Collection<?>)rawItems).isEmpty()) throw new IllegalArgumentException("Add at least one prescription item.");
        List<Map<String,Object>> items = new ArrayList<>();
        for (Object raw : (Collection<?>)rawItems) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Invalid prescription item.");
            Map<String,Object> item = (Map<String,Object>)raw;
            checkedLength(required(item,"drugName"),160); checkedLength(required(item,"dosage"),100); checkedLength(required(item,"frequency"),100);
            Integer days = integer(item,"durationDays",null);
            if (days != null && (days < 1 || days > 3650)) throw new IllegalArgumentException("Prescription duration must be between 1 and 3650 days.");
            checkedLength(text(item,"administrationRoute",null),50); checkedLength(text(item,"remark",null),500); items.add(item);
        }
        String instructions = text(body,"instructions",null); checkedLength(instructions,1000);
        one("SELECT id FROM patient WHERE id=? FOR UPDATE",patientId);
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM electronic_prescription WHERE patient_id=?",Integer.class,patientId);
        jdbc.update("UPDATE electronic_prescription SET status='SUPERSEDED' WHERE patient_id=? AND status='ACTIVE'",patientId);
        long id = insert("INSERT INTO electronic_prescription(visit_id,patient_id,doctor_user_id,version_no,status,instructions,published_at) VALUES(?,?,?,?,'ACTIVE',?,NOW())",visitId,patientId,userId(),version,instructions);
        for (Map<String,Object> item : items) jdbc.update("INSERT INTO electronic_prescription_item(prescription_id,drug_name,dosage,frequency,administration_route,duration_days,remark) VALUES(?,?,?,?,?,?,?)",id,required(item,"drugName"),required(item,"dosage"),required(item,"frequency"),text(item,"administrationRoute",null),integer(item,"durationDays",null),text(item,"remark",null));
        audience.notifyCareTeam(patientId,"PRESCRIPTION_UPDATED","Medication instructions updated","A clinician published prescription version "+version+". Review the new dose and frequency before the next intake.");
        Map<String,Object> result = one("SELECT * FROM electronic_prescription WHERE id=?",id);
        result.put("items",jdbc.queryForList("SELECT * FROM electronic_prescription_item WHERE prescription_id=?",id)); return result;
    }
    private void checkedLength(String value,int maximum) { if (value != null && value.length() > maximum) throw new IllegalArgumentException("The entered text is too long (maximum " + maximum + " characters)."); }


    public List<Map<String,Object>> treatmentPlans(Long patientId){requireRead(patientId,"TREATMENT");return jdbc.queryForList("SELECT t.*,u.real_name AS doctor_name FROM treatment_plan t LEFT JOIN sys_user u ON u.id=t.doctor_user_id WHERE t.patient_id=? ORDER BY t.created_at DESC",patientId);}
    public Map<String,Object> saveTreatmentPlan(Map<String,Object>body){requireDoctor();Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"TREATMENT");long id=insert("INSERT INTO treatment_plan(patient_id,doctor_user_id,plan_type,title,plan_json,start_at,end_at,status) VALUES(?,?,?,?,?,?,?,?)",patientId,userId(),required(body,"planType"),required(body,"title"),json(body.get("plan")),dateTime(body.get("startAt"),null),dateTime(body.get("endAt"),null),text(body,"status","ACTIVE"));audience.notifyCareTeam(patientId,"TREATMENT_PLAN","Treatment plan updated","A clinician added or changed the treatment and rehabilitation plan.");return one("SELECT * FROM treatment_plan WHERE id=?",id);}

    public List<Map<String,Object>> rehabCheckins(Long patientId){requireRead(patientId,"REHAB");return jdbc.queryForList("SELECT * FROM rehab_checkin WHERE patient_id=? ORDER BY recorded_at DESC",patientId);}
    public Map<String,Object> saveRehabCheckin(Map<String,Object> body) {
        Long patientId = requiredLong(body,"patientId"); requireWrite(patientId,"REHAB");
        String type = required(body,"recordType").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("EXERCISE","WOUND","DRAIN","SYMPTOM").contains(type)) throw new IllegalArgumentException("Invalid rehabilitation record type.");
        Long planId = optionalLong(body,"planId");
        if (planId != null && one("SELECT id FROM treatment_plan WHERE id=? AND patient_id=?",planId,patientId)==null) throw new IllegalArgumentException("Treatment plan not found for this patient.");
        Integer completion = integer(body,"completionPercent",null);
        if (completion!=null&&(completion<0||completion>100)) throw new IllegalArgumentException("Completion must be between 0 and 100.");
        boolean abnormal = Boolean.TRUE.equals(body.get("abnormal"))||"true".equalsIgnoreCase(String.valueOf(body.get("abnormal")));
        long id = insert("INSERT INTO rehab_checkin(plan_id,patient_id,recorded_by,record_type,completion_percent,video_url,data_json,abnormal,recorded_at) VALUES(?,?,?,?,?,?,?,?,?)",planId,patientId,userId(),type,completion,text(body,"videoUrl",null),json(body.get("data")),abnormal?1:0,dateTime(body.get("recordedAt"),LocalDateTime.now()));
        if (abnormal) audience.notify(audience.assignedDoctors(patientId),patientId,"REHAB_ALERT","Post-treatment symptom requires review",type+" was marked abnormal. Review the structured observation and contact the patient if needed.");
        return one("SELECT * FROM rehab_checkin WHERE id=?",id);
    }

    public Map<String,Object> emergencyCard(Long patientId){requireRead(patientId,"EMERGENCY");Map<String,Object>card=new LinkedHashMap<>();card.put("patient",one("SELECT id,name,gender,birth_date,emergency_contact,emergency_phone,medical_history FROM patient WHERE id=?",patientId));card.put("clinical",one("SELECT allergy_drugs,blood_type,primary_diagnosis,dialysis_type,vascular_access,target_dry_weight,remark FROM patient_clinical WHERE patient_id=?",patientId));card.put("medications",jdbc.queryForList("SELECT drug_name,default_dosage,remark FROM medication WHERE patient_id=? AND is_active=1 ORDER BY drug_name",patientId));card.put("generatedAt",LocalDateTime.now());card.put("offlineReadable",true);card.put("disclaimer","Emergency information is patient-maintained and must be clinically verified.");return card;}

    @Transactional public Map<String,Object> triggerEmergency(Map<String,Object>body){
        Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"EMERGENCY");
        Map<String,Object>snapshot=emergencyCard(patientId);
        Set<Long>recipients=audience.recipients(patientId);recipients.remove(userId());
        String location=text(body,"locationText","Not provided");
        BigDecimal latitude=decimal(body,"latitude",false),longitude=decimal(body,"longitude",false);
        long id=insert("INSERT INTO emergency_event(patient_id,triggered_by,latitude,longitude,location_text,snapshot_json,status,notified_user_ids) VALUES(?,?,?,?,?,?,'TRIGGERED',?)",patientId,userId(),latitude,longitude,text(body,"locationText",null),JSON.toJSONString(snapshot),join(recipients));
        Map<?,?> patient=(Map<?,?>)snapshot.get("patient");
        String patientName=patient==null?"Patient":String.valueOf(patient.get("name"));
        String content="Emergency event #"+id+" for "+patientName+". Location: "+location+(latitude==null||longitude==null?"":" ("+latitude+", "+longitude+")")+". Open Care journey > Emergency to review the medical snapshot.";
        int delivered=audience.notifyWithDeliveryCount(recipients,patientId,"EMERGENCY","Emergency call from bound patient",content);
        Map<String,Object>result=one("SELECT * FROM emergency_event WHERE id=?",id);
        result.put("snapshot",snapshot);result.put("recipientCount",recipients.size());result.put("deliveryCount",delivered);
        return result;
    }

    public List<Map<String,Object>> emergencies(Long patientId){
        requireRead(patientId,"EMERGENCY");
        return jdbc.queryForList("SELECT id,patient_id,triggered_by,latitude,longitude,location_text,status,triggered_at,notified_user_ids FROM emergency_event WHERE patient_id=? ORDER BY triggered_at DESC,id DESC LIMIT 100",patientId);
    }

    public Map<String,Object> emergency(Long id){
        Map<String,Object>event=one("SELECT * FROM emergency_event WHERE id=?",id);
        if(event==null)throw new IllegalArgumentException("Emergency event not found.");
        requireRead(number(event.get("patient_id")),"EMERGENCY");
        event.put("snapshot",JSON.parseObject(String.valueOf(event.get("snapshot_json"))));
        event.remove("snapshot_json");
        return event;
    }

    public List<Map<String,Object>> specialty(String type,Long patientId){requireRead(patientId,"SPECIALTY");String table=specialtyTable(type);return jdbc.queryForList("SELECT * FROM "+table+" WHERE patient_id=? ORDER BY "+specialtyDateColumn(type)+" DESC,id DESC",patientId);}

    public Map<String,Object> saveSpecialty(String type,Map<String,Object>body){Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"SPECIALTY");switch(type.toLowerCase()){
        case "growth":{BigDecimal h=decimal(body,"heightCm",false),w=decimal(body,"weightKg",false),head=decimal(body,"headCircumferenceCm",false);long id=insert("INSERT INTO growth_record(patient_id,recorded_by,record_date,height_cm,weight_kg,head_circumference_cm,height_percentile,weight_percentile,reference_standard,remark) VALUES(?,?,?,?,?,?,?,?,?,?)",patientId,userId(),LocalDate.parse(required(body,"recordDate")),h,w,head,decimal(body,"heightPercentile",false),decimal(body,"weightPercentile",false),text(body,"referenceStandard","WHO"),text(body,"remark",null));return one("SELECT * FROM growth_record WHERE id=?",id);}
        case "vaccination":{long id=insert("INSERT INTO vaccination_plan(patient_id,vaccine_name,dose_no,planned_date,completed_date,status,remind_at,recorded_by,remark) VALUES(?,?,?,?,?,?,?,?,?)",patientId,required(body,"vaccineName"),text(body,"doseNo",null),LocalDate.parse(required(body,"plannedDate")),localDate(body.get("completedDate")),text(body,"status","PLANNED"),dateTime(body.get("remindAt"),null),userId(),text(body,"remark",null));return one("SELECT * FROM vaccination_plan WHERE id=?",id);}
        case "maternity":{long id=insert("INSERT INTO maternity_record(patient_id,recorded_by,record_type,record_date,gestational_week,title,data_json,remark) VALUES(?,?,?,?,?,?,?,?)",patientId,userId(),required(body,"recordType"),LocalDate.parse(required(body,"recordDate")),decimal(body,"gestationalWeek",false),required(body,"title"),json(body.get("data")),text(body,"remark",null));return one("SELECT * FROM maternity_record WHERE id=?",id);}
        default:throw new IllegalArgumentException("Unsupported specialty record type.");}}

    public List<Map<String,Object>> mentalAssessments(Long patientId){requireRead(patientId,"MENTAL");Long owner=jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?",Long.class,patientId);String sql="SELECT * FROM mental_assessment WHERE patient_id=?";if(!Objects.equals(owner,userId())&&!CurrentUserUtil.isAdmin()&&!CurrentUserUtil.hasRole("doctor"))sql+=" AND family_visibility='VISIBLE'";return jdbc.queryForList(sql+" ORDER BY submitted_at DESC",patientId);}

    @Transactional public Map<String,Object> saveMentalAssessment(Map<String,Object> body) {
        Long patientId = requiredLong(body, "patientId"); requireWrite(patientId, "MENTAL");
        Map<String,Object> result = MentalAssessmentScoring.evaluate(required(body, "scaleCode"), body.get("answers"));
        String visibility = validatedVisibility(body);
        long id = insert("INSERT INTO mental_assessment(patient_id,scale_code,answers_json,score,severity,family_visibility,submitted_by) VALUES(?,?,?,?,?,?,?)",
                patientId, result.get("scale"), JSON.toJSONString(result.get("answers")), result.get("score"), result.get("severity"), visibility, userId());
        if (Boolean.TRUE.equals(result.get("requiresReview"))) {
            List<Long> doctors = audience.assignedDoctors(patientId);
            if (!doctors.isEmpty()) {
                audience.notify(doctors, patientId, "MENTAL_ASSESSMENT", "Mental-health assessment requires review", result.get("scale") + " result: " + result.get("severity") + ". Review privately in the clinician workspace.");
                jdbc.update("UPDATE mental_assessment SET doctor_notified_at=NOW() WHERE id=?", id);
            }
        }
        return one("SELECT * FROM mental_assessment WHERE id=?", id);
    }

    public List<Map<String,Object>> mentalSchedules(Long patientId) {
        requireRead(patientId, "MENTAL");
        Long owner = jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?", Long.class, patientId);
        boolean privileged = Objects.equals(owner, userId()) || CurrentUserUtil.isAdmin() || CurrentUserUtil.hasRole("doctor");
        return jdbc.queryForList("SELECT * FROM mental_assessment_schedule WHERE patient_id=?" + (privileged ? "" : " AND family_visibility='VISIBLE'") + " ORDER BY next_due_at", patientId);
    }
    @Transactional public Map<String,Object> saveMentalSchedule(Map<String,Object> body) {
        Long patientId = requiredLong(body, "patientId"); requireWrite(patientId, "MENTAL");
        one("SELECT id FROM patient WHERE id=? FOR UPDATE",patientId);
        String scale = MentalAssessmentScoring.scale(required(body, "scaleCode"));
        int interval = integer(body, "intervalDays", 14);
        if (interval < 1 || interval > 3650) throw new IllegalArgumentException("Assessment interval must be between 1 and 3650 days.");
        String visibility = validatedVisibility(body);
        Map<String,Object>active=one("SELECT id FROM mental_assessment_schedule WHERE patient_id=? AND scale_code=? AND enabled=1 ORDER BY id LIMIT 1 FOR UPDATE",patientId,scale);
        long id;
        if(active==null)id=insert("INSERT INTO mental_assessment_schedule(patient_id,scale_code,interval_days,next_due_at,family_visibility,enabled,created_by) VALUES(?,?,?,?,?,1,?)", patientId, scale, interval, dateTime(body.get("nextDueAt"), LocalDateTime.now()), visibility, userId());
        else{id=number(active.get("id"));jdbc.update("UPDATE mental_assessment_schedule SET interval_days=?,next_due_at=?,family_visibility=? WHERE id=?",interval,dateTime(body.get("nextDueAt"), LocalDateTime.now()),visibility,id);jdbc.update("UPDATE mental_assessment_schedule SET enabled=0 WHERE patient_id=? AND scale_code=? AND enabled=1 AND id<>?",patientId,scale,id);}
        return one("SELECT * FROM mental_assessment_schedule WHERE id=?", id);
    }

    @Transactional public void disableMentalSchedule(Long id){
        Map<String,Object>row=one("SELECT patient_id FROM mental_assessment_schedule WHERE id=? FOR UPDATE",id);
        if(row==null)throw new IllegalArgumentException("Assessment schedule not found.");
        requireWrite(number(row.get("patient_id")),"MENTAL");
        jdbc.update("UPDATE mental_assessment_schedule SET enabled=0 WHERE id=?",id);
    }

    private String validatedVisibility(Map<String,Object> body) {
        String visibility = text(body, "familyVisibility", "PRIVATE").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("PRIVATE", "VISIBLE").contains(visibility)) throw new IllegalArgumentException("Invalid family visibility.");
        return visibility;
    }

    public List<Map<String,Object>> patientGroups(){requireDoctor();Long doctor=CurrentUserUtil.isAdmin()?null:userId();String sql="SELECT g.*,(SELECT COUNT(*) FROM patient_group_member m WHERE m.group_id=g.id) AS patient_count FROM patient_group g";return doctor==null?jdbc.queryForList(sql+" ORDER BY g.group_name"):jdbc.queryForList(sql+" WHERE g.doctor_user_id=? ORDER BY g.group_name",doctor);}
    public Map<String,Object> savePatientGroup(Map<String,Object>body){requireDoctor();Long doctor=body.get("doctorUserId")==null?userId():requiredLong(body,"doctorUserId");if(!CurrentUserUtil.isAdmin()&&!Objects.equals(doctor,userId()))throw new IllegalStateException("You can only manage your own groups.");long id=insert("INSERT INTO patient_group(doctor_user_id,group_name,description) VALUES(?,?,?)",doctor,required(body,"groupName"),text(body,"description",null));return one("SELECT * FROM patient_group WHERE id=?",id);}
    public void addGroupMember(Long groupId,Long patientId){requireDoctor();Map<String,Object>g=one("SELECT * FROM patient_group WHERE id=?",groupId);if(g==null||!CurrentUserUtil.isAdmin()&&!Objects.equals(number(g.get("doctor_user_id")),userId()))throw new IllegalStateException("Group access denied.");requireRead(patientId,"GROUPS");jdbc.update("INSERT IGNORE INTO patient_group_member(group_id,patient_id) VALUES(?,?)",groupId,patientId);}

    public Map<String,Object> operations(LocalDate from, LocalDate to) {
        requireDoctor();
        LocalDate start = from == null ? LocalDate.now().minusDays(30) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start)) throw new IllegalArgumentException("The end date must not precede the start date.");
        String patientFilter = "";
        if (!CurrentUserUtil.isAdmin()) {
            List<Long> ids = scope.accessiblePatientIds(userId());
            patientFilter = ids.isEmpty() ? " AND 1=0" : " AND patient_id IN (" + join(ids) + ")";
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("period", Arrays.asList(start, end));
        out.put("followUp", one("SELECT COUNT(*) total,SUM(CASE WHEN status IN ('DONE','COMPLETED','ANSWERED') THEN 1 ELSE 0 END) completed,ROUND(100.0*SUM(CASE WHEN status IN ('DONE','COMPLETED','ANSWERED') THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),1) completion_rate FROM care_item WHERE kind IN ('APPOINTMENT','HANDOVER','QUESTION') AND CAST(COALESCE(event_at,created_at) AS DATE) BETWEEN ? AND ?" + patientFilter, start, end));
        out.put("medication", one("SELECT COUNT(*) total,SUM(CASE WHEN status='TAKEN' THEN 1 ELSE 0 END) taken,ROUND(100.0*SUM(CASE WHEN status='TAKEN' THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),1) adherence_rate FROM medication_intake WHERE CAST(scheduled_at AS DATE) BETWEEN ? AND ?" + patientFilter, start, end));
        out.put("measurements", one("SELECT COUNT(*) total,SUM(CASE WHEN status='NORMAL' THEN 1 ELSE 0 END) on_target,ROUND(100.0*SUM(CASE WHEN status='NORMAL' THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),1) target_rate FROM health_measurement WHERE CAST(measured_at AS DATE) BETWEEN ? AND ?" + patientFilter, start, end));
        out.put("appointments", one("SELECT COUNT(*) total,SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END) completed,SUM(CASE WHEN status='CANCELLED' THEN 1 ELSE 0 END) cancelled FROM care_appointment WHERE CAST(start_at AS DATE) BETWEEN ? AND ?" + patientFilter, start, end));
        out.put("alerts", one("SELECT COUNT(*) total,SUM(CASE WHEN status='RESOLVED' THEN 1 ELSE 0 END) resolved FROM alert_record WHERE CAST(triggered_at AS DATE) BETWEEN ? AND ?" + patientFilter, start, end));
        return out;
    }

    public void requireRead(Long patientId,String module){scope.requirePatientAccess(patientId,module,false);requireModule(patientId,module,"READ");}
    public void requireWrite(Long patientId,String module){scope.requirePatientAccess(patientId,module,true);requireModule(patientId,module,"WRITE");}
    private void requireModule(Long patientId,String module,String needed) {
        if(CurrentUserUtil.isAdmin())return;
        Long uid=userId();
        Long owner=jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?",Long.class,patientId);
        if(Objects.equals(owner,uid))return;
        if(CurrentUserUtil.hasRole("doctor")) {
            Integer assigned=jdbc.queryForObject("SELECT COUNT(*) FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id=? AND status='ACTIVE'",Integer.class,patientId,uid);
            if(assigned!=null&&assigned>0)return;
        }
        List<Map<String,Object>> grants=jdbc.queryForList("SELECT access_level,visible_modules FROM care_access_grant WHERE patient_id=? AND grantee_user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>NOW())",patientId,uid);
        if(grants.isEmpty()) {
            grants=jdbc.queryForList("SELECT access_level,visible_modules FROM care_member WHERE patient_id=? AND user_id=?",patientId,uid);
            if(grants.isEmpty())throw new IllegalStateException("Access to this module has not been authorized by the patient.");
        }
        boolean visible=false;
        for(Map<String,Object> grant:grants) {
            String raw=text(grant,"visible_modules",null);
            Set<String> modules=new HashSet<>();
            if(raw!=null)for(String part:raw.split(","))modules.add(part.trim().toUpperCase(Locale.ROOT));
            if(raw!=null&&!modules.contains(module))continue;
            visible=true;
            if(!"WRITE".equals(needed)||Arrays.asList("WRITE","PROXY").contains(String.valueOf(grant.get("access_level"))))return;
        }
        throw new IllegalStateException(visible?"This grant is read-only.":"This module is outside the authorized visibility scope.");
    }
    private void requireOwner(Long patientId){Map<String,Object>p=one("SELECT user_id FROM patient WHERE id=? AND COALESCE(deleted,0)=0",patientId);if(p==null)throw new IllegalArgumentException("Patient not found.");if(!CurrentUserUtil.isAdmin()&&!Objects.equals(number(p.get("user_id")),userId()))throw new IllegalStateException("Only the patient or guardian owner can manage access.");}
    private void requireDoctor(){if(!CurrentUserUtil.isAdmin()&&!CurrentUserUtil.hasRole("doctor"))throw new IllegalStateException("Clinician access is required.");}
    private void requireActiveDoctor(Long id) {
        Integer valid=jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 AND r.role_code='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0",Integer.class,id);
        if(valid==null||valid==0)throw new IllegalArgumentException("Select an active doctor for the consultation.");
    }
    private Long userId(){Long id=CurrentUserUtil.getCurrentUserId();if(id==null)throw new IllegalStateException("Authentication is required.");return id;}

    private String measurementStatus(Long patientId,String type,BigDecimal a,BigDecimal b){Map<String,Object>target=one("SELECT * FROM patient_health_target WHERE patient_id=?",patientId);if("BP".equals(type)){BigDecimal min=decimal(target,"systolic_min",new BigDecimal("90")),max=decimal(target,"systolic_max",new BigDecimal("140")),dmin=decimal(target,"diastolic_min",new BigDecimal("60")),dmax=decimal(target,"diastolic_max",new BigDecimal("90"));return a.compareTo(min)<0||a.compareTo(max)>0||b==null||b.compareTo(dmin)<0||b.compareTo(dmax)>0?"ABNORMAL":"NORMAL";}if("GLUCOSE".equals(type))return a.compareTo(new BigDecimal("3.9"))<0||a.compareTo(new BigDecimal("11.1"))>0?"ABNORMAL":"NORMAL";if("SPO2".equals(type))return a.compareTo(new BigDecimal("92"))<0?"ABNORMAL":"NORMAL";if("HEART_RATE".equals(type))return a.compareTo(new BigDecimal("50"))<0||a.compareTo(new BigDecimal("120"))>0?"ABNORMAL":"NORMAL";if("TEMPERATURE".equals(type))return a.compareTo(new BigDecimal("35"))<0||a.compareTo(new BigDecimal("38"))>0?"ABNORMAL":"NORMAL";if("WEIGHT".equals(type)&&target!=null&&target.get("target_weight")!=null){BigDecimal t=new BigDecimal(target.get("target_weight").toString()),lim=decimal(target,"weight_gain_limit",new BigDecimal("3"));return a.subtract(t).abs().compareTo(lim)>0?"ABNORMAL":"NORMAL";}return "NORMAL";}
    private String measurementUnit(String type,String unit) {
        if("CUSTOM".equals(type))return limit(unit,30);
        if("GLUCOSE".equals(type)) {
            if("mmol/L".equalsIgnoreCase(unit))return "mmol/L";
            if("mg/dL".equalsIgnoreCase(unit))return "mg/dL";
            throw new IllegalArgumentException("Blood glucose unit must be mmol/L or mg/dL.");
        }
        String expected;
        switch(type){case "BP":expected="mmHg";break;case "SPO2":expected="%";break;case "WEIGHT":expected="kg";break;case "HEART_RATE":expected="bpm";break;case "TEMPERATURE":expected="°C";break;default:throw new IllegalArgumentException("Unsupported metric type.");}
        if("TEMPERATURE".equals(type)&&("C".equalsIgnoreCase(unit)||"℃".equals(unit)))return expected;
        if(!expected.equalsIgnoreCase(unit))throw new IllegalArgumentException("Measurement unit must be "+expected+" for "+type+".");
        return expected;
    }
    private void validateMeasurement(String type,BigDecimal a,BigDecimal b){if(a==null)throw new IllegalArgumentException("Measurement value is required.");if("BP".equals(type)&&(a.compareTo(new BigDecimal("40"))<0||a.compareTo(new BigDecimal("300"))>0||b==null||b.compareTo(new BigDecimal("20"))<0||b.compareTo(new BigDecimal("200"))>0))throw new IllegalArgumentException("Blood pressure is outside the accepted input range.");if("SPO2".equals(type)&&(a.compareTo(BigDecimal.ZERO)<0||a.compareTo(new BigDecimal("100"))>0))throw new IllegalArgumentException("SpO2 must be between 0 and 100.");}
    private List<Map<String,Object>> prescriptions(Long visitId,Long patientId){String sql="SELECT p.* FROM electronic_prescription p WHERE p.patient_id=?";List<Object>a=new ArrayList<>();a.add(patientId);if(visitId!=null){sql+=" AND p.visit_id=?";a.add(visitId);}sql+=" ORDER BY p.version_no DESC";List<Map<String,Object>>rows=jdbc.queryForList(sql,a.toArray());for(Map<String,Object>r:rows)r.put("items",jdbc.queryForList("SELECT * FROM electronic_prescription_item WHERE prescription_id=?",r.get("id")));return rows;}
    private String specialtyTable(String type){switch(type.toLowerCase()){case"growth":return"growth_record";case"vaccination":return"vaccination_plan";case"maternity":return"maternity_record";default:throw new IllegalArgumentException("Unsupported specialty record type.");}}
    private String specialtyDateColumn(String type){return"vaccination".equalsIgnoreCase(type)?"planned_date":"record_date";}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>>r=jdbc.queryForList(sql,args);return r.isEmpty()?null:r.get(0);}
    private long insert(String sql,Object...args){KeyHolder key=new GeneratedKeyHolder();PreparedStatementCreator creator=con->{PreparedStatement ps=con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;};jdbc.update(creator,key);Number generated=key.getKeys()!=null&&key.getKeys().get("id") instanceof Number?(Number)key.getKeys().get("id"):key.getKey();if(generated==null)throw new IllegalStateException("The record was not created.");return generated.longValue();}
    private static String required(Map<String,Object>b,String key){Object v=b.get(key);if(v==null||v.toString().trim().isEmpty())throw new IllegalArgumentException(key+" is required.");return v.toString().trim();}
    private static String text(Map<String,Object>b,String key,String fallback){Object v=b==null?null:b.get(key);return v==null||v.toString().trim().isEmpty()?fallback:v.toString().trim();}
    private static String limit(String value,int max){if(value==null)return null;return value.length()>max?value.substring(0,max):value;}
    private static Long requiredLong(Map<String,Object>b,String key){return Long.valueOf(required(b,key));}
    private static Long optionalLong(Map<String,Object>b,String key){Object v=b.get(key);return v==null||v.toString().trim().isEmpty()?null:Long.valueOf(v.toString());}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static Integer integer(Map<String,Object>b,String key,Integer fallback){Object v=b.get(key);return v==null||v.toString().trim().isEmpty()?fallback:Integer.valueOf(v.toString());}
    private static BigDecimal decimal(Map<String,Object>b,String key,boolean required){Object v=b==null?null:b.get(key);if(v==null||v.toString().trim().isEmpty()){if(required)throw new IllegalArgumentException(key+" is required.");return null;}return new BigDecimal(v.toString());}
    private static BigDecimal decimal(Map<String,Object>b,String key,BigDecimal fallback){BigDecimal v=decimal(b,key,false);return v==null?fallback:v;}
    private static LocalDateTime dateTime(Object value,LocalDateTime fallback){if(value==null||value.toString().trim().isEmpty())return fallback;String s=value.toString().trim().replace(' ','T');try{return LocalDateTime.parse(s.length()==16?s+":00":s);}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Enter a valid date and time.");}}
    private static LocalDate localDate(Object value){if(value==null||value.toString().trim().isEmpty())return null;try{return LocalDate.parse(value.toString());}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Enter a valid date.");}}
    private static String json(Object value){return value==null?"{}":value instanceof String?(String)value:JSON.toJSONString(value);}
    private static String join(Collection<Long>values){StringBuilder b=new StringBuilder();for(Long v:values){if(b.length()>0)b.append(',');b.append(v);}return b.toString();}
}
