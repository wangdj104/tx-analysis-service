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
        String unit = required(body,"unit");
        LocalDateTime at = dateTime(body.get("measuredAt"), LocalDateTime.now());
        String status = measurementStatus(patientId,type,primary,secondary);
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
        LocalDateTime expires=dateTime(body.get("expiresAt"),null);
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

    public List<Map<String,Object>> clinicians(){return jdbc.queryForList("SELECT DISTINCT u.id,u.username,u.real_name FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE r.role_code='doctor' AND u.status=1 AND COALESCE(u.deleted,0)=0 ORDER BY COALESCE(u.real_name,u.username)");}

    public Map<String,Object> saveDoctorSchedule(Map<String,Object> body) {
        requireDoctor(); Long doctorId=body.get("doctorUserId")==null?userId():requiredLong(body,"doctorUserId");
        if(!CurrentUserUtil.isAdmin()&&!Objects.equals(doctorId,userId()))throw new IllegalStateException("You can only manage your own schedule.");
        LocalDate day=LocalDate.parse(required(body,"workDate")); LocalTime start=LocalTime.parse(required(body,"startTime")); LocalTime end=LocalTime.parse(required(body,"endTime"));
        if(!end.isAfter(start))throw new IllegalArgumentException("End time must be after start time.");
        long id=insert("INSERT INTO doctor_schedule(doctor_user_id,work_date,start_time,end_time,slot_minutes,location,consultation_modes,status) VALUES(?,?,?,?,?,?,?,?)",doctorId,day,start,end,integer(body,"slotMinutes",30),text(body,"location",null),text(body,"consultationModes","IN_PERSON,TEXT,VOICE,VIDEO"),text(body,"status","AVAILABLE"));
        return one("SELECT * FROM doctor_schedule WHERE id=?",id);
    }

    public List<Map<String,Object>> appointments(Long patientId) {
        requireRead(patientId,"APPOINTMENTS");
        return jdbc.queryForList("SELECT a.*,u.real_name AS doctor_name FROM care_appointment a LEFT JOIN sys_user u ON u.id=a.doctor_user_id WHERE a.patient_id=? ORDER BY a.start_at DESC",patientId);
    }

    @Transactional
    public Map<String,Object> saveAppointment(Map<String,Object> body) {
        Long patientId=requiredLong(body,"patientId"), doctorId=requiredLong(body,"doctorUserId"); requireWrite(patientId,"APPOINTMENTS");
        LocalDateTime start=dateTime(body.get("startAt"),null), end=dateTime(body.get("endAt"),null);
        if(start==null||end==null||!end.isAfter(start))throw new IllegalArgumentException("A valid appointment time is required.");
        Long scheduleId=optionalLong(body,"scheduleId");
        if(scheduleId!=null){Map<String,Object>s=one("SELECT * FROM doctor_schedule WHERE id=? AND doctor_user_id=? AND status='AVAILABLE'",scheduleId,doctorId);if(s==null)throw new IllegalArgumentException("The selected clinician availability is no longer available.");LocalDate workDate=LocalDate.parse(s.get("work_date").toString());LocalDateTime availableFrom=LocalDateTime.of(workDate,LocalTime.parse(s.get("start_time").toString())),availableTo=LocalDateTime.of(workDate,LocalTime.parse(s.get("end_time").toString()));if(start.isBefore(availableFrom)||end.isAfter(availableTo))throw new IllegalArgumentException("The appointment must fit inside the selected clinician availability.");}
        Long appointmentId=body.get("id")==null?null:requiredLong(body,"id");
        Integer conflicts=jdbc.queryForObject("SELECT COUNT(*) FROM care_appointment WHERE doctor_user_id=? AND status='BOOKED' AND start_at<? AND end_at>? AND (? IS NULL OR id<>?)",Integer.class,doctorId,end,start,appointmentId,appointmentId);
        if(conflicts!=null&&conflicts>0)throw new IllegalStateException("The clinician time slot is already occupied.");
        if(appointmentId==null){appointmentId=insert("INSERT INTO care_appointment(patient_id,doctor_user_id,schedule_id,appointment_type,consultation_mode,start_at,end_at,status,reason,recurrence_days,next_follow_up_at,created_by) VALUES(?,?,?,?,?,?,?,'BOOKED',?,?,?,?)",patientId,doctorId,scheduleId,text(body,"appointmentType","FOLLOW_UP"),text(body,"consultationMode","IN_PERSON"),start,end,text(body,"reason",null),integer(body,"recurrenceDays",null),dateTime(body.get("nextFollowUpAt"),null),userId());}
        else {Map<String,Object> current=one("SELECT * FROM care_appointment WHERE id=?",appointmentId);if(current==null||!Objects.equals(number(current.get("patient_id")),patientId))throw new IllegalArgumentException("Appointment not found.");jdbc.update("UPDATE care_appointment SET doctor_user_id=?,schedule_id=?,appointment_type=?,consultation_mode=?,start_at=?,end_at=?,reason=?,recurrence_days=?,next_follow_up_at=?,status='BOOKED',cancel_reason=NULL WHERE id=?",doctorId,scheduleId,text(body,"appointmentType","FOLLOW_UP"),text(body,"consultationMode","IN_PERSON"),start,end,text(body,"reason",null),integer(body,"recurrenceDays",null),dateTime(body.get("nextFollowUpAt"),null),appointmentId);}
        audience.notifyCareTeam(patientId,"APPOINTMENT_UPDATED","Appointment updated","Appointment scheduled for "+start+". The shared care calendar has been updated.");
        return one("SELECT * FROM care_appointment WHERE id=?",appointmentId);
    }

    public void cancelAppointment(Long id,String reason){Map<String,Object>a=one("SELECT * FROM care_appointment WHERE id=?",id);if(a==null)return;Long patientId=number(a.get("patient_id"));requireWrite(patientId,"APPOINTMENTS");jdbc.update("UPDATE care_appointment SET status='CANCELLED',cancel_reason=? WHERE id=?",limit(reason,500),id);audience.notifyCareTeam(patientId,"APPOINTMENT_CANCELLED","Appointment cancelled",reason==null?"The shared appointment was cancelled.":reason);}

    public List<Map<String,Object>> visits(Long patientId){requireRead(patientId,"VISITS");List<Map<String,Object>>rows=jdbc.queryForList("SELECT v.*,u.real_name AS doctor_name FROM visit_record v LEFT JOIN sys_user u ON u.id=v.doctor_user_id WHERE v.patient_id=? ORDER BY v.visited_at DESC",patientId);for(Map<String,Object>r:rows)r.put("prescriptions",prescriptions(number(r.get("id")),patientId));return rows;}

    @Transactional public Map<String,Object> saveVisit(Map<String,Object>body){requireDoctor();Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"VISITS");long id=insert("INSERT INTO visit_record(appointment_id,patient_id,doctor_user_id,visited_at,diagnosis_summary,treatment_summary,follow_up_advice,follow_up_at,status) VALUES(?,?,?,?,?,?,?,?,?)",optionalLong(body,"appointmentId"),patientId,userId(),dateTime(body.get("visitedAt"),LocalDateTime.now()),text(body,"diagnosisSummary",null),text(body,"treatmentSummary",null),text(body,"followUpAdvice",null),dateTime(body.get("followUpAt"),null),text(body,"status","DRAFT"));return one("SELECT * FROM visit_record WHERE id=?",id);}

    @Transactional public Map<String,Object> publishVisit(Long id){Map<String,Object>v=one("SELECT * FROM visit_record WHERE id=?",id);if(v==null)throw new IllegalArgumentException("Visit record not found.");Long patientId=number(v.get("patient_id"));requireDoctor();requireWrite(patientId,"VISITS");jdbc.update("UPDATE visit_record SET status='PUBLISHED',published_at=NOW() WHERE id=?",id);jdbc.update("INSERT INTO health_event(user_id,patient_id,event_date,event_time,event_type,title,summary,source_type,source_id,status) VALUES(?,?,CURDATE(),DATE_FORMAT(NOW(),'%H:%i'),'VISIT','Visit summary',?,'VISIT_RECORD',?,'RECORDED')",userId(),patientId,String.valueOf(v.get("diagnosis_summary")),id);audience.notifyCareTeam(patientId,"VISIT_SUMMARY","Visit summary published","A clinician published the visit summary and follow-up instructions.");return one("SELECT * FROM visit_record WHERE id=?",id);}

    @SuppressWarnings("unchecked") @Transactional public Map<String,Object> savePrescription(Map<String,Object>body){requireDoctor();Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"MEDICATION");Long visitId=optionalLong(body,"visitId");Integer version=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM electronic_prescription WHERE patient_id=?",Integer.class,patientId);jdbc.update("UPDATE electronic_prescription SET status='SUPERSEDED' WHERE patient_id=? AND status='ACTIVE'",patientId);long id=insert("INSERT INTO electronic_prescription(visit_id,patient_id,doctor_user_id,version_no,status,instructions,published_at) VALUES(?,?,?,?, 'ACTIVE',?,NOW())",visitId,patientId,userId(),version,text(body,"instructions",null));Object itemsObj=body.get("items");if(!(itemsObj instanceof Collection)||((Collection<?>)itemsObj).isEmpty())throw new IllegalArgumentException("Add at least one prescription item.");for(Object raw:(Collection<?>)itemsObj){Map<String,Object>item=(Map<String,Object>)raw;jdbc.update("INSERT INTO electronic_prescription_item(prescription_id,drug_name,dosage,frequency,administration_route,duration_days,remark) VALUES(?,?,?,?,?,?,?)",id,required(item,"drugName"),required(item,"dosage"),required(item,"frequency"),text(item,"administrationRoute",null),integer(item,"durationDays",null),text(item,"remark",null));}audience.notifyCareTeam(patientId,"PRESCRIPTION_UPDATED","Medication instructions updated","A clinician published prescription version "+version+". Review the new dose and frequency before the next intake.");Map<String,Object>result=one("SELECT * FROM electronic_prescription WHERE id=?",id);result.put("items",jdbc.queryForList("SELECT * FROM electronic_prescription_item WHERE prescription_id=?",id));return result;}

    public List<Map<String,Object>> consultations(Long patientId){requireRead(patientId,"CONSULTATION");Long owner=jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?",Long.class,patientId);boolean privileged=CurrentUserUtil.isAdmin()||CurrentUserUtil.hasRole("doctor")||Objects.equals(owner,userId());String sql="SELECT c.*,u.real_name AS doctor_name FROM consultation c LEFT JOIN sys_user u ON u.id=c.doctor_user_id WHERE c.patient_id=?"+(privileged?"":" AND c.family_visibility='VISIBLE'")+" ORDER BY c.started_at DESC";List<Map<String,Object>>rows=jdbc.queryForList(sql,patientId);for(Map<String,Object>r:rows)r.put("participants",jdbc.queryForList("SELECT cp.*,u.real_name FROM consultation_participant cp LEFT JOIN sys_user u ON u.id=cp.user_id WHERE cp.consultation_id=?",r.get("id")));return rows;}

    @SuppressWarnings("unchecked") @Transactional public Map<String,Object> startConsultation(Map<String,Object>body){Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"CONSULTATION");String mode=required(body,"mode").toUpperCase();if(!CONSULT_MODES.contains(mode))throw new IllegalArgumentException("Unsupported consultation mode.");Long doctor=optionalLong(body,"doctorUserId");long id=insert("INSERT INTO consultation(patient_id,doctor_user_id,mode,status,symptom,duration_text,medical_history,family_visibility,created_by) VALUES(?,?,?,'OPEN',?,?,?,?,?)",patientId,doctor,mode,required(body,"symptom"),text(body,"durationText",null),text(body,"medicalHistory",null),text(body,"familyVisibility","VISIBLE"),userId());Set<Long>participants=new LinkedHashSet<>();participants.add(userId());participants.addAll(jdbc.queryForList("SELECT user_id FROM patient WHERE id=?",Long.class,patientId));if(doctor!=null)participants.add(doctor);Object family=body.get("participantUserIds");if(family instanceof Collection)for(Object raw:(Collection<?>)family){Long uid=Long.valueOf(raw.toString());if(audience.recipients(patientId).contains(uid))participants.add(uid);}for(Long uid:participants)jdbc.update("INSERT IGNORE INTO consultation_participant(consultation_id,user_id,participant_role) VALUES(?,?,?)",id,uid,Objects.equals(uid,doctor)?"DOCTOR":Objects.equals(uid,userId())?"INITIATOR":"CARE_TEAM");return consultation(id);}

    public Map<String,Object> consultation(Long id){Map<String,Object>c=one("SELECT * FROM consultation WHERE id=?",id);if(c==null)throw new IllegalArgumentException("Consultation not found.");requireRead(number(c.get("patient_id")),"CONSULTATION");if("PRIVATE".equals(String.valueOf(c.get("family_visibility")))&&!CurrentUserUtil.isAdmin()&&!CurrentUserUtil.hasRole("doctor")&&!Objects.equals(number(c.get("created_by")),userId())){Integer participant=jdbc.queryForObject("SELECT COUNT(*) FROM consultation_participant WHERE consultation_id=? AND user_id=? AND participant_role IN ('INITIATOR','DOCTOR')",Integer.class,id,userId());if(participant==null||participant==0)throw new IllegalStateException("This consultation is private and is not visible to family caregivers.");}c.put("messages",jdbc.queryForList("SELECT m.*,u.real_name AS sender_name FROM consultation_message m LEFT JOIN sys_user u ON u.id=m.sender_user_id WHERE m.consultation_id=? ORDER BY m.sent_at,m.id",id));c.put("participants",jdbc.queryForList("SELECT cp.*,u.real_name FROM consultation_participant cp LEFT JOIN sys_user u ON u.id=cp.user_id WHERE cp.consultation_id=?",id));return c;}

    public Map<String,Object> addConsultationMessage(Long id,Map<String,Object>body){Map<String,Object>c=consultation(id);if(!"OPEN".equals(String.valueOf(c.get("status"))))throw new IllegalStateException("Consultation is closed.");String type=text(body,"messageType","TEXT").toUpperCase();if(!Arrays.asList("TEXT","IMAGE","FILE","VOICE","SYSTEM").contains(type))throw new IllegalArgumentException("Invalid message type.");long messageId=insert("INSERT INTO consultation_message(consultation_id,sender_user_id,message_type,content,attachment_record_id) VALUES(?,?,?,?,?)",id,userId(),type,required(body,"content"),optionalLong(body,"attachmentRecordId"));return one("SELECT * FROM consultation_message WHERE id=?",messageId);}

    public List<Map<String,Object>> consultationSignals(Long id,Long afterId){consultation(id);return jdbc.queryForList("SELECT * FROM consultation_signal WHERE consultation_id=? AND target_user_id=? AND id>? ORDER BY id",id,userId(),afterId==null?0:afterId);}
    public Map<String,Object> sendConsultationSignal(Long id,Map<String,Object>body){consultation(id);Long target=requiredLong(body,"targetUserId");Integer participant=jdbc.queryForObject("SELECT COUNT(*) FROM consultation_participant WHERE consultation_id=? AND user_id=?",Integer.class,id,target);if(participant==null||participant==0)throw new IllegalArgumentException("Signal target is not a consultation participant.");String type=required(body,"signalType").toUpperCase();if(!Arrays.asList("OFFER","ANSWER","ICE","HANGUP").contains(type))throw new IllegalArgumentException("Invalid signal type.");long signalId=insert("INSERT INTO consultation_signal(consultation_id,sender_user_id,target_user_id,signal_type,payload_json) VALUES(?,?,?,?,?)",id,userId(),target,type,json(body.get("payload")));return one("SELECT * FROM consultation_signal WHERE id=?",signalId);}

    @Transactional public Map<String,Object> closeConsultation(Long id,Map<String,Object>body){Map<String,Object>c=consultation(id);Long patientId=number(c.get("patient_id"));String advice=text(body,"transferAdvice",null);jdbc.update("UPDATE consultation SET status='CLOSED',ended_at=NOW(),transfer_advice=? WHERE id=?",advice,id);long eventId=insert("INSERT INTO health_event(user_id,patient_id,event_date,event_time,event_type,title,summary,source_type,source_id,status) VALUES(?,?,CURDATE(),DATE_FORMAT(NOW(),'%H:%i'),'CONSULTATION','Remote consultation',?,'CONSULTATION',?,'RECORDED')",userId(),patientId,String.valueOf(c.get("symptom")),id);jdbc.update("UPDATE consultation SET archived_event_id=? WHERE id=?",eventId,id);jdbc.update("DELETE FROM consultation_signal WHERE consultation_id=?",id);return consultation(id);}

    public List<Map<String,Object>> treatmentPlans(Long patientId){requireRead(patientId,"TREATMENT");return jdbc.queryForList("SELECT t.*,u.real_name AS doctor_name FROM treatment_plan t LEFT JOIN sys_user u ON u.id=t.doctor_user_id WHERE t.patient_id=? ORDER BY t.created_at DESC",patientId);}
    public Map<String,Object> saveTreatmentPlan(Map<String,Object>body){requireDoctor();Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"TREATMENT");long id=insert("INSERT INTO treatment_plan(patient_id,doctor_user_id,plan_type,title,plan_json,start_at,end_at,status) VALUES(?,?,?,?,?,?,?,?)",patientId,userId(),required(body,"planType"),required(body,"title"),json(body.get("plan")),dateTime(body.get("startAt"),null),dateTime(body.get("endAt"),null),text(body,"status","ACTIVE"));audience.notifyCareTeam(patientId,"TREATMENT_PLAN","Treatment plan updated","A clinician added or changed the treatment and rehabilitation plan.");return one("SELECT * FROM treatment_plan WHERE id=?",id);}

    public List<Map<String,Object>> rehabCheckins(Long patientId){requireRead(patientId,"REHAB");return jdbc.queryForList("SELECT * FROM rehab_checkin WHERE patient_id=? ORDER BY recorded_at DESC",patientId);}
    public Map<String,Object> saveRehabCheckin(Map<String,Object>body){Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"REHAB");String type=required(body,"recordType").toUpperCase();if(!Arrays.asList("EXERCISE","WOUND","DRAIN","SYMPTOM").contains(type))throw new IllegalArgumentException("Invalid rehabilitation record type.");boolean abnormal=Boolean.TRUE.equals(body.get("abnormal"))||"true".equalsIgnoreCase(String.valueOf(body.get("abnormal")));long id=insert("INSERT INTO rehab_checkin(plan_id,patient_id,recorded_by,record_type,completion_percent,video_url,data_json,abnormal,recorded_at) VALUES(?,?,?,?,?,?,?,?,?)",optionalLong(body,"planId"),patientId,userId(),type,integer(body,"completionPercent",null),text(body,"videoUrl",null),json(body.get("data")),abnormal?1:0,dateTime(body.get("recordedAt"),LocalDateTime.now()));if(abnormal)audience.notify(audience.assignedDoctors(patientId),patientId,"REHAB_ALERT","Post-treatment symptom requires review",type+" was marked abnormal. Review the structured observation and contact the patient if needed.");return one("SELECT * FROM rehab_checkin WHERE id=?",id);}

    public Map<String,Object> emergencyCard(Long patientId){requireRead(patientId,"EMERGENCY");Map<String,Object>card=new LinkedHashMap<>();card.put("patient",one("SELECT id,name,gender,birth_date,emergency_contact,emergency_phone,medical_history FROM patient WHERE id=?",patientId));card.put("clinical",one("SELECT allergy_drugs,blood_type,primary_diagnosis,dialysis_type,vascular_access,target_dry_weight,remark FROM patient_clinical WHERE patient_id=?",patientId));card.put("medications",jdbc.queryForList("SELECT drug_name,default_dosage,remark FROM medication WHERE patient_id=? AND is_active=1 ORDER BY drug_name",patientId));card.put("generatedAt",LocalDateTime.now());card.put("offlineReadable",true);card.put("disclaimer","Emergency information is patient-maintained and must be clinically verified.");return card;}

    @Transactional public Map<String,Object> triggerEmergency(Map<String,Object>body){Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"EMERGENCY");Map<String,Object>snapshot=emergencyCard(patientId);Set<Long>recipients=audience.recipients(patientId);recipients.remove(userId());long id=insert("INSERT INTO emergency_event(patient_id,triggered_by,latitude,longitude,location_text,snapshot_json,status,notified_user_ids) VALUES(?,?,?,?,?,?,'TRIGGERED',?)",patientId,userId(),decimal(body,"latitude",false),decimal(body,"longitude",false),text(body,"locationText",null),JSON.toJSONString(snapshot),join(recipients));audience.notify(recipients,patientId,"EMERGENCY","Emergency call from bound patient","Open the emergency event to view location, medical history, allergies, and current medication.");Map<String,Object>result=one("SELECT * FROM emergency_event WHERE id=?",id);result.put("snapshot",snapshot);return result;}

    public List<Map<String,Object>> specialty(String type,Long patientId){requireRead(patientId,"SPECIALTY");String table=specialtyTable(type);return jdbc.queryForList("SELECT * FROM "+table+" WHERE patient_id=? ORDER BY "+specialtyDateColumn(type)+" DESC,id DESC",patientId);}

    public Map<String,Object> saveSpecialty(String type,Map<String,Object>body){Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"SPECIALTY");switch(type.toLowerCase()){
        case "growth":{BigDecimal h=decimal(body,"heightCm",false),w=decimal(body,"weightKg",false),head=decimal(body,"headCircumferenceCm",false);long id=insert("INSERT INTO growth_record(patient_id,recorded_by,record_date,height_cm,weight_kg,head_circumference_cm,height_percentile,weight_percentile,reference_standard,remark) VALUES(?,?,?,?,?,?,?,?,?,?)",patientId,userId(),LocalDate.parse(required(body,"recordDate")),h,w,head,decimal(body,"heightPercentile",false),decimal(body,"weightPercentile",false),text(body,"referenceStandard","WHO"),text(body,"remark",null));return one("SELECT * FROM growth_record WHERE id=?",id);}
        case "vaccination":{long id=insert("INSERT INTO vaccination_plan(patient_id,vaccine_name,dose_no,planned_date,completed_date,status,remind_at,recorded_by,remark) VALUES(?,?,?,?,?,?,?,?,?)",patientId,required(body,"vaccineName"),text(body,"doseNo",null),LocalDate.parse(required(body,"plannedDate")),localDate(body.get("completedDate")),text(body,"status","PLANNED"),dateTime(body.get("remindAt"),null),userId(),text(body,"remark",null));return one("SELECT * FROM vaccination_plan WHERE id=?",id);}
        case "maternity":{long id=insert("INSERT INTO maternity_record(patient_id,recorded_by,record_type,record_date,gestational_week,title,data_json,remark) VALUES(?,?,?,?,?,?,?,?)",patientId,userId(),required(body,"recordType"),LocalDate.parse(required(body,"recordDate")),decimal(body,"gestationalWeek",false),required(body,"title"),json(body.get("data")),text(body,"remark",null));return one("SELECT * FROM maternity_record WHERE id=?",id);}
        default:throw new IllegalArgumentException("Unsupported specialty record type.");}}

    public List<Map<String,Object>> mentalAssessments(Long patientId){requireRead(patientId,"MENTAL");Long owner=jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?",Long.class,patientId);String sql="SELECT * FROM mental_assessment WHERE patient_id=?";if(!Objects.equals(owner,userId())&&!CurrentUserUtil.isAdmin()&&!CurrentUserUtil.hasRole("doctor"))sql+=" AND family_visibility='VISIBLE'";return jdbc.queryForList(sql+" ORDER BY submitted_at DESC",patientId);}

    @Transactional public Map<String,Object> saveMentalAssessment(Map<String,Object>body){Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"MENTAL");JSONArray answers=JSON.parseArray(json(body.get("answers")));BigDecimal score=BigDecimal.ZERO;for(Object value:answers){if(value instanceof Number)score=score.add(new BigDecimal(value.toString()));else if(value instanceof JSONObject){Object v=((JSONObject)value).get("score");if(v!=null)score=score.add(new BigDecimal(v.toString()));}}String severity=score.compareTo(new BigDecimal("15"))>=0?"SEVERE":score.compareTo(new BigDecimal("10"))>=0?"MODERATE":score.compareTo(new BigDecimal("5"))>=0?"MILD":"MINIMAL";long id=insert("INSERT INTO mental_assessment(patient_id,scale_code,answers_json,score,severity,family_visibility,submitted_by) VALUES(?,?,?,?,?,?,?)",patientId,required(body,"scaleCode"),answers.toJSONString(),score,severity,text(body,"familyVisibility","PRIVATE"),userId());if(Arrays.asList("MODERATE","SEVERE").contains(severity)){List<Long>doctors=audience.assignedDoctors(patientId);audience.notify(doctors,patientId,"MENTAL_ASSESSMENT","Mental-health assessment requires review",required(body,"scaleCode")+" result: "+severity+". Review privately in the clinician workspace.");jdbc.update("UPDATE mental_assessment SET doctor_notified_at=NOW() WHERE id=?",id);}return one("SELECT * FROM mental_assessment WHERE id=?",id);}

    public List<Map<String,Object>> mentalSchedules(Long patientId){requireRead(patientId,"MENTAL");return jdbc.queryForList("SELECT * FROM mental_assessment_schedule WHERE patient_id=? ORDER BY next_due_at",patientId);}
    public Map<String,Object> saveMentalSchedule(Map<String,Object>body){Long patientId=requiredLong(body,"patientId");requireWrite(patientId,"MENTAL");long id=insert("INSERT INTO mental_assessment_schedule(patient_id,scale_code,interval_days,next_due_at,family_visibility,enabled,created_by) VALUES(?,?,?,?,?,1,?)",patientId,required(body,"scaleCode"),integer(body,"intervalDays",14),dateTime(body.get("nextDueAt"),LocalDateTime.now()),text(body,"familyVisibility","PRIVATE"),userId());return one("SELECT * FROM mental_assessment_schedule WHERE id=?",id);}

    public List<Map<String,Object>> patientGroups(){requireDoctor();Long doctor=CurrentUserUtil.isAdmin()?null:userId();String sql="SELECT g.*,(SELECT COUNT(*) FROM patient_group_member m WHERE m.group_id=g.id) AS patient_count FROM patient_group g";return doctor==null?jdbc.queryForList(sql+" ORDER BY g.group_name"):jdbc.queryForList(sql+" WHERE g.doctor_user_id=? ORDER BY g.group_name",doctor);}
    public Map<String,Object> savePatientGroup(Map<String,Object>body){requireDoctor();Long doctor=body.get("doctorUserId")==null?userId():requiredLong(body,"doctorUserId");if(!CurrentUserUtil.isAdmin()&&!Objects.equals(doctor,userId()))throw new IllegalStateException("You can only manage your own groups.");long id=insert("INSERT INTO patient_group(doctor_user_id,group_name,description) VALUES(?,?,?)",doctor,required(body,"groupName"),text(body,"description",null));return one("SELECT * FROM patient_group WHERE id=?",id);}
    public void addGroupMember(Long groupId,Long patientId){requireDoctor();Map<String,Object>g=one("SELECT * FROM patient_group WHERE id=?",groupId);if(g==null||!CurrentUserUtil.isAdmin()&&!Objects.equals(number(g.get("doctor_user_id")),userId()))throw new IllegalStateException("Group access denied.");requireRead(patientId,"GROUPS");jdbc.update("INSERT IGNORE INTO patient_group_member(group_id,patient_id) VALUES(?,?)",groupId,patientId);}

    public Map<String,Object> operations(LocalDate from,LocalDate to){if(!CurrentUserUtil.isAdmin()&&!CurrentUserUtil.hasRole("doctor"))throw new IllegalStateException("Clinician access is required.");LocalDate start=from==null?LocalDate.now().minusDays(30):from,end=to==null?LocalDate.now():to;Map<String,Object>out=new LinkedHashMap<>();out.put("period",Arrays.asList(start,end));out.put("followUp",one("SELECT COUNT(*) total,SUM(status IN ('DONE','COMPLETED','ANSWERED')) completed,ROUND(100*SUM(status IN ('DONE','COMPLETED','ANSWERED'))/NULLIF(COUNT(*),0),1) completion_rate FROM care_item WHERE kind IN ('APPOINTMENT','HANDOVER','QUESTION') AND DATE(COALESCE(event_at,created_at)) BETWEEN ? AND ?",start,end));out.put("medication",one("SELECT COUNT(*) total,SUM(status='TAKEN') taken,ROUND(100*SUM(status='TAKEN')/NULLIF(COUNT(*),0),1) adherence_rate FROM medication_intake WHERE DATE(scheduled_at) BETWEEN ? AND ?",start,end));out.put("measurements",one("SELECT COUNT(*) total,SUM(status='NORMAL') on_target,ROUND(100*SUM(status='NORMAL')/NULLIF(COUNT(*),0),1) target_rate FROM health_measurement WHERE DATE(measured_at) BETWEEN ? AND ?",start,end));out.put("appointments",one("SELECT COUNT(*) total,SUM(status='COMPLETED') completed,SUM(status='CANCELLED') cancelled FROM care_appointment WHERE DATE(start_at) BETWEEN ? AND ?",start,end));out.put("alerts",one("SELECT COUNT(*) total,SUM(status='RESOLVED') resolved FROM alert_record WHERE DATE(triggered_at) BETWEEN ? AND ?",start,end));return out;}

    public void requireRead(Long patientId,String module){scope.requirePatient(patientId);requireModule(patientId,module,"READ");}
    public void requireWrite(Long patientId,String module){scope.requirePatient(patientId);requireModule(patientId,module,"WRITE");}
    private void requireModule(Long patientId,String module,String needed){if(CurrentUserUtil.isAdmin())return;Long uid=userId();Long owner=jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?",Long.class,patientId);if(Objects.equals(owner,uid))return;if(CurrentUserUtil.hasRole("doctor")){Integer assigned=jdbc.queryForObject("SELECT COUNT(*) FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id=? AND status='ACTIVE'",Integer.class,patientId,uid);if(assigned!=null&&assigned>0)return;}List<Map<String,Object>>grants=jdbc.queryForList("SELECT access_level,visible_modules FROM care_access_grant WHERE patient_id=? AND grantee_user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>NOW())",patientId,uid);if(grants.isEmpty()){Integer member=jdbc.queryForObject("SELECT COUNT(*) FROM care_member WHERE patient_id=? AND user_id=?",Integer.class,patientId,uid);if(member!=null&&member>0&&"READ".equals(needed))return;throw new IllegalStateException("Access to this module has not been authorized by the patient.");}Map<String,Object>g=grants.get(0);String modules=String.valueOf(g.get("visible_modules"));if(g.get("visible_modules")!=null&&!modules.trim().isEmpty()&&!Arrays.asList(modules.split(",")).contains(module))throw new IllegalStateException("This module is outside the authorized visibility scope.");String level=String.valueOf(g.get("access_level"));if("WRITE".equals(needed)&&"READ".equals(level))throw new IllegalStateException("This grant is read-only.");}
    private void requireOwner(Long patientId){Map<String,Object>p=one("SELECT user_id FROM patient WHERE id=? AND COALESCE(deleted,0)=0",patientId);if(p==null)throw new IllegalArgumentException("Patient not found.");if(!CurrentUserUtil.isAdmin()&&!Objects.equals(number(p.get("user_id")),userId()))throw new IllegalStateException("Only the patient or guardian owner can manage access.");}
    private void requireDoctor(){if(!CurrentUserUtil.isAdmin()&&!CurrentUserUtil.hasRole("doctor"))throw new IllegalStateException("Clinician access is required.");}
    private Long userId(){Long id=CurrentUserUtil.getCurrentUserId();if(id==null)throw new IllegalStateException("Authentication is required.");return id;}

    private String measurementStatus(Long patientId,String type,BigDecimal a,BigDecimal b){Map<String,Object>target=one("SELECT * FROM patient_health_target WHERE patient_id=?",patientId);if("BP".equals(type)){BigDecimal min=decimal(target,"systolic_min",new BigDecimal("90")),max=decimal(target,"systolic_max",new BigDecimal("140")),dmin=decimal(target,"diastolic_min",new BigDecimal("60")),dmax=decimal(target,"diastolic_max",new BigDecimal("90"));return a.compareTo(min)<0||a.compareTo(max)>0||b==null||b.compareTo(dmin)<0||b.compareTo(dmax)>0?"ABNORMAL":"NORMAL";}if("GLUCOSE".equals(type))return a.compareTo(new BigDecimal("3.9"))<0||a.compareTo(new BigDecimal("11.1"))>0?"ABNORMAL":"NORMAL";if("SPO2".equals(type))return a.compareTo(new BigDecimal("92"))<0?"ABNORMAL":"NORMAL";if("HEART_RATE".equals(type))return a.compareTo(new BigDecimal("50"))<0||a.compareTo(new BigDecimal("120"))>0?"ABNORMAL":"NORMAL";if("TEMPERATURE".equals(type))return a.compareTo(new BigDecimal("35"))<0||a.compareTo(new BigDecimal("38"))>0?"ABNORMAL":"NORMAL";if("WEIGHT".equals(type)&&target!=null&&target.get("target_weight")!=null){BigDecimal t=new BigDecimal(target.get("target_weight").toString()),lim=decimal(target,"weight_gain_limit",new BigDecimal("3"));return a.subtract(t).abs().compareTo(lim)>0?"ABNORMAL":"NORMAL";}return "NORMAL";}
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
    private static LocalDateTime dateTime(Object value,LocalDateTime fallback){if(value==null||value.toString().trim().isEmpty())return fallback;String s=value.toString().trim().replace(' ','T');return LocalDateTime.parse(s.length()==16?s+":00":s);}
    private static LocalDate localDate(Object value){return value==null||value.toString().trim().isEmpty()?null:LocalDate.parse(value.toString());}
    private static String json(Object value){return value==null?"{}":value instanceof String?(String)value:JSON.toJSONString(value);}
    private static String join(Collection<Long>values){StringBuilder b=new StringBuilder();for(Long v:values){if(b.length()>0)b.append(',');b.append(v);}return b.toString();}
}
