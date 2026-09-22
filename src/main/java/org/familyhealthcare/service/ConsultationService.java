package org.familyhealthcare.service;

import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/** A consultation invitation grants access to that conversation, not the patient's full record. */
@Service
public class ConsultationService {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataScopeHelper scope;
    @Autowired private CareJourneyService journey;

    private static final String SELECT_SESSION = "SELECT c.*,p.user_id AS patient_owner_id,p.name AS patient_name,"
            + "COALESCE(NULLIF(u.real_name,''),u.username) AS doctor_name,"
            + "COALESCE((SELECT MAX(m.sent_at) FROM consultation_message m WHERE m.consultation_id=c.id),c.started_at) AS last_message_at "
            + "FROM consultation c JOIN patient p ON p.id=c.patient_id LEFT JOIN sys_user u ON u.id=c.doctor_user_id "
            + "WHERE COALESCE(p.deleted,0)=0";

    public List<Map<String,Object>> inbox(String status) {
        Long uid = userId();
        List<Object> args = new ArrayList<>(Arrays.asList(uid, uid, uid, uid));
        String sql = SELECT_SESSION + " AND (c.doctor_user_id=? OR p.user_id=? OR c.created_by=? OR EXISTS "
                + "(SELECT 1 FROM consultation_participant cp WHERE cp.consultation_id=c.id AND cp.user_id=?))";
        if (status != null && !status.trim().isEmpty()) {
            String value = status.trim().toUpperCase(Locale.ROOT);
            if (!Arrays.asList("OPEN", "CLOSED").contains(value)) throw new IllegalArgumentException("Invalid consultation status.");
            sql += " AND c.status=?";
            args.add(value);
        }
        return visibleRows(jdbc.queryForList(sql + " ORDER BY CASE WHEN c.status='OPEN' THEN 0 ELSE 1 END,last_message_at DESC,c.id DESC LIMIT 200", args.toArray()));
    }

    public List<Map<String,Object>> list(Long patientId) {
        List<Map<String,Object>> rows = visibleRows(jdbc.queryForList(SELECT_SESSION + " AND c.patient_id=? ORDER BY c.started_at DESC,c.id DESC", patientId));
        if (rows.isEmpty()) journey.requireRead(patientId, "CONSULTATION");
        return rows;
    }

    private List<Map<String,Object>> visibleRows(List<Map<String,Object>> rows) {
        List<Map<String,Object>> visible = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            try { requireAccess(row, false); }
            catch (IllegalStateException denied) { continue; }
            addSessionMetadata(row);
            visible.add(row);
        }
        return visible;
    }

    public List<Map<String,Object>> invitees(Long patientId) {
        journey.requireWrite(patientId, "CONSULTATION");
        return jdbc.queryForList("SELECT u.id AS user_id,COALESCE(NULLIF(u.real_name,''),u.username) AS real_name,m.relation_name FROM care_member m JOIN sys_user u ON u.id=m.user_id WHERE m.patient_id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 ORDER BY real_name", patientId);
    }

    @Transactional
    public Map<String,Object> start(Map<String,Object> body) {
        Long patientId = requiredId(body, "patientId");
        journey.requireWrite(patientId, "CONSULTATION");
        Long doctor = requiredId(body, "doctorUserId");
        Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 AND r.role_code='doctor' AND r.status=1", Integer.class, doctor);
        if (valid == null || valid == 0) throw new IllegalArgumentException("Select an active doctor for the consultation.");
        String mode = value(body, "mode", "TEXT").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("TEXT", "VOICE", "VIDEO").contains(mode)) throw new IllegalArgumentException("Unsupported consultation mode.");
        String visibility = value(body, "familyVisibility", "VISIBLE").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("VISIBLE", "PRIVATE").contains(visibility)) throw new IllegalArgumentException("Invalid family visibility.");
        String symptom = checked(body, "symptom", 500, true);
        String duration = checked(body, "durationText", 100, false);
        String history = checked(body, "medicalHistory", 10000, false);
        Long owner = jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?", Long.class, patientId);
        long id = insert("INSERT INTO consultation(patient_id,doctor_user_id,mode,status,symptom,duration_text,medical_history,family_visibility,created_by) VALUES(?,?,?,'OPEN',?,?,?,?,?)", patientId, doctor, mode, symptom, duration, history, visibility, userId());
        Set<Long> participants = new LinkedHashSet<>(Arrays.asList(userId(), owner, doctor));
        if ("VISIBLE".equals(visibility)) {
            Set<Long> family = new HashSet<>(jdbc.queryForList("SELECT user_id FROM care_member WHERE patient_id=?", Long.class, patientId));
            Object invitees = body.get("participantUserIds");
            if (invitees instanceof Collection) for (Object raw : (Collection<?>) invitees) {
                Long invited = Long.valueOf(raw.toString());
                if (family.contains(invited)) participants.add(invited);
            }
        }
        participants.remove(null);
        for (Long uid : participants) jdbc.update("INSERT IGNORE INTO consultation_participant(consultation_id,user_id,participant_role) VALUES(?,?,?)", id, uid, Objects.equals(uid, doctor) ? "DOCTOR" : Objects.equals(uid, owner) ? "PATIENT" : "CARE_TEAM");
        return detail(id);
    }

    public Map<String,Object> detail(Long id) {
        Map<String,Object> session = session(id, false);
        requireAccess(session, false);
        addSessionMetadata(session);
        List<Map<String,Object>> messages = jdbc.queryForList("SELECT m.*,COALESCE(NULLIF(u.real_name,''),u.username) AS sender_name FROM consultation_message m LEFT JOIN sys_user u ON u.id=m.sender_user_id WHERE m.consultation_id=? ORDER BY m.sent_at,m.id", id);
        for (Map<String,Object> message : messages) addAttachmentMetadata(message, number(session.get("patient_id")));
        session.put("messages", messages);
        return session;
    }

    @Transactional
    public Map<String,Object> message(Long id, Map<String,Object> body) {
        Map<String,Object> session = session(id, true);
        requireAccess(session, true);
        requireOpen(session);
        String type = value(body, "messageType", "TEXT").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("TEXT", "IMAGE", "FILE", "VOICE").contains(type)) throw new IllegalArgumentException("Invalid message type.");
        Long recordId = optionalId(body, "attachmentRecordId");
        String content = checked(body, "content", 10000, recordId == null);
        Long patientId = number(session.get("patient_id"));
        if (recordId != null) {
            scope.requirePatientAccess(patientId,"MEDICAL",false);
            journey.requireRead(patientId, "MEDICAL");
            if (one("SELECT id FROM medical_record WHERE id=? AND patient_id=?", recordId, patientId) == null)
                throw new IllegalArgumentException("The attachment does not belong to this patient.");
        }
        if (("IMAGE".equals(type) || "FILE".equals(type)) && recordId == null)
            throw new IllegalArgumentException("Select a patient medical record to attach.");
        long messageId = insert("INSERT INTO consultation_message(consultation_id,sender_user_id,message_type,content,attachment_record_id) VALUES(?,?,?,?,?)", id, userId(), type, content, recordId);
        Map<String,Object> result = one("SELECT m.*,COALESCE(NULLIF(u.real_name,''),u.username) AS sender_name FROM consultation_message m LEFT JOIN sys_user u ON u.id=m.sender_user_id WHERE m.id=?", messageId);
        addAttachmentMetadata(result, patientId);
        return result;
    }

    public List<Map<String,Object>> recordOptions(Long id) {
        Map<String,Object> session = session(id, false);
        requireAccess(session, false);
        Long patientId = number(session.get("patient_id"));
        try { scope.requirePatientAccess(patientId,"MEDICAL",false); journey.requireRead(patientId, "MEDICAL"); } catch (IllegalStateException denied) { return Collections.emptyList(); }
        return jdbc.queryForList("SELECT id,record_date,record_type,hospital_name FROM medical_record WHERE patient_id=? ORDER BY record_date DESC,id DESC LIMIT 100", patientId);
    }

    public Map<String,Object> attachment(Long id, Long attachmentId) {
        Map<String,Object> session = session(id, false);
        requireAccess(session, false);
        Map<String,Object> result = one("SELECT a.id,a.file_name,a.file_type,a.file_content FROM medical_record_attachment a JOIN medical_record r ON r.id=a.record_id WHERE a.id=? AND r.patient_id=? AND EXISTS (SELECT 1 FROM consultation_message m WHERE m.consultation_id=? AND m.attachment_record_id=r.id)", attachmentId, session.get("patient_id"), id);
        if (result == null || result.get("file_content") == null || result.get("file_content").toString().trim().isEmpty())
            throw new IllegalArgumentException("The shared attachment is not available. Ask the sender to upload it again.");
        return result;
    }

    public List<Map<String,Object>> signals(Long id, Long afterId) {
        Map<String,Object> session = session(id, false);
        requireAccess(session, true);
        if (!"OPEN".equals(session.get("status"))) return Collections.emptyList();
        return jdbc.queryForList("SELECT * FROM consultation_signal WHERE consultation_id=? AND target_user_id=? AND id>? ORDER BY id LIMIT 200", id, userId(), afterId == null ? 0 : Math.max(0, afterId));
    }

    @Transactional
    public Map<String,Object> signal(Long id, Map<String,Object> body) {
        Map<String,Object> session = session(id, true);
        requireAccess(session, true);
        requireOpen(session);
        Long target = requiredId(body, "targetUserId");
        if (Objects.equals(target, userId()) || !participant(id, target)) throw new IllegalArgumentException("Signal target is not another consultation participant.");
        String type = value(body, "signalType", "").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("OFFER", "ANSWER", "ICE", "HANGUP").contains(type)) throw new IllegalArgumentException("Invalid signal type.");
        String payload = com.alibaba.fastjson2.JSON.toJSONString(body.get("payload"));
        if (payload.length() > 100000) throw new IllegalArgumentException("The call signal is too large.");
        long signalId = insert("INSERT INTO consultation_signal(consultation_id,sender_user_id,target_user_id,signal_type,payload_json) VALUES(?,?,?,?,?)", id, userId(), target, type, payload);
        return one("SELECT * FROM consultation_signal WHERE id=?", signalId);
    }

    @Transactional
    public Map<String,Object> close(Long id, Map<String,Object> body) {
        Map<String,Object> session = session(id, true);
        requireAccess(session, true);
        if (!canClose(session)) throw new IllegalStateException("Only the patient, initiating caregiver, or selected doctor can close this consultation.");
        if (!"OPEN".equals(session.get("status"))) return detail(id);
        String advice = checked(body, "transferAdvice", 1000, false);
        if (advice != null && !CurrentUserUtil.hasRole("doctor") && !CurrentUserUtil.isAdmin())
            throw new IllegalStateException("Only a doctor can provide referral advice.");
        jdbc.update("UPDATE consultation SET status='CLOSED',ended_at=NOW(),transfer_advice=? WHERE id=?", advice, id);
        // The shared timeline must not expose private symptom text. The full transcript stays behind consultation access checks.
        String summary = "PRIVATE".equals(session.get("family_visibility")) ? null : String.valueOf(session.get("symptom"));
        long eventId = insert("INSERT INTO health_event(user_id,patient_id,event_date,event_time,event_type,title,summary,source_type,source_id,status) VALUES(?,?,CURRENT_DATE,?,'CONSULTATION','Remote consultation',?,'CONSULTATION',?,'RECORDED')", session.get("patient_owner_id"), session.get("patient_id"), java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), summary, id);
        jdbc.update("UPDATE consultation SET archived_event_id=? WHERE id=?", eventId, id);
        jdbc.update("DELETE FROM consultation_signal WHERE consultation_id=?", id);
        return detail(id);
    }

    private Map<String,Object> session(Long id, boolean lock) {
        // Serialize sends and closing to prevent messages being appended after an archive is finalized.
        if (lock && one("SELECT id FROM consultation WHERE id=? FOR UPDATE", id) == null) throw new IllegalArgumentException("Consultation not found.");
        Map<String,Object> row = one(SELECT_SESSION + " AND c.id=?", id);
        if (row == null) throw new IllegalArgumentException("Consultation not found.");
        return row;
    }

    private void requireAccess(Map<String,Object> session, boolean participating) {
        Long uid = userId();
        if (CurrentUserUtil.isAdmin() || Objects.equals(uid, number(session.get("patient_owner_id")))) return;
        if (Objects.equals(uid, number(session.get("doctor_user_id"))) && CurrentUserUtil.hasRole("doctor")) return;
        journey.requireRead(number(session.get("patient_id")), "CONSULTATION");
        if ("PRIVATE".equals(session.get("family_visibility")) && !Objects.equals(uid, number(session.get("created_by"))))
            throw new IllegalStateException("This consultation is private and is not visible to family caregivers.");
        if (participating && !participant(number(session.get("id")), uid))
            throw new IllegalStateException("Only invited consultation participants can send messages or join calls.");
    }

    private void addSessionMetadata(Map<String,Object> session) {
        boolean canParticipate;
        try { requireAccess(session, true); canParticipate = true; } catch (IllegalStateException denied) { canParticipate = false; }
        session.put("can_message", canParticipate && "OPEN".equals(session.get("status")));
        session.put("can_close", canParticipate && canClose(session) && "OPEN".equals(session.get("status")));
        session.put("participants", jdbc.queryForList("SELECT cp.*,COALESCE(NULLIF(u.real_name,''),u.username) AS real_name FROM consultation_participant cp LEFT JOIN sys_user u ON u.id=cp.user_id WHERE cp.consultation_id=?", session.get("id")));
    }

    private boolean canClose(Map<String,Object> session) {
        Long uid = userId();
        return CurrentUserUtil.isAdmin() || Objects.equals(uid, number(session.get("patient_owner_id"))) || Objects.equals(uid, number(session.get("created_by"))) || Objects.equals(uid, number(session.get("doctor_user_id"))) && CurrentUserUtil.hasRole("doctor");
    }

    private void addAttachmentMetadata(Map<String,Object> message, Long patientId) {
        if (message.get("attachment_record_id") == null) return;
        Map<String,Object> record = one("SELECT id,record_date,record_type,hospital_name FROM medical_record WHERE id=? AND patient_id=?", message.get("attachment_record_id"), patientId);
        if (record != null) record.put("attachments", jdbc.queryForList("SELECT id,file_name,file_type FROM medical_record_attachment WHERE record_id=?", record.get("id")));
        message.put("attachment_record", record);
    }

    private boolean participant(Long id, Long uid) { return jdbc.queryForObject("SELECT COUNT(*) FROM consultation_participant WHERE consultation_id=? AND user_id=?", Integer.class, id, uid) > 0; }
    private void requireOpen(Map<String,Object> session) { if (!"OPEN".equals(session.get("status"))) throw new IllegalStateException("Consultation is closed."); }
    private Long userId() { Long uid = CurrentUserUtil.getCurrentUserId(); if (uid == null) throw new IllegalStateException("Authentication is required."); return uid; }
    private Map<String,Object> one(String sql, Object... args) { List<Map<String,Object>> rows = jdbc.queryForList(sql, args); return rows.isEmpty() ? null : rows.get(0); }
    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> { PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS); for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]); return statement; }, key);
        Number generated = key.getKeys() != null && key.getKeys().get("id") instanceof Number ? (Number)key.getKeys().get("id") : key.getKey();
        if (generated == null) throw new IllegalStateException("The record was not created.");
        return generated.longValue();
    }
    private static Long number(Object raw) { return raw == null ? null : ((Number)raw).longValue(); }
    private static Long optionalId(Map<String,Object> body, String field) { String raw = value(body, field, null); return raw == null ? null : Long.valueOf(raw); }
    private static Long requiredId(Map<String,Object> body, String field) { Long id = optionalId(body, field); if (id == null || id < 1) throw new IllegalArgumentException(field + " is required."); return id; }
    private static String value(Map<String,Object> body, String field, String fallback) { Object raw = body.get(field); return raw == null || raw.toString().trim().isEmpty() ? fallback : raw.toString().trim(); }
    private static String checked(Map<String,Object> body, String field, int max, boolean required) { String text = value(body, field, null); if (required && text == null) throw new IllegalArgumentException(field + " is required."); if (text != null && text.length() > max) throw new IllegalArgumentException(field + " exceeds " + max + " characters."); return text; }
}
