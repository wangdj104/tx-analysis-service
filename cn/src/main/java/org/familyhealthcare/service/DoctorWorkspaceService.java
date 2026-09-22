package org.familyhealthcare.service;

import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DoctorWorkspaceService {
    @Autowired private JdbcTemplate jdbc;

    public Map<String, Object> summary() {
        requireDoctor();
        List<Map<String, Object>> patientRows = patients();
        List<Map<String, Object>> reviewRows = reviews("REVIEW_REQUIRED");
        long critical = patientRows.stream().filter(row -> number(row.get("criticalAlerts")) > 0).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignedPatients", patientRows.size());
        result.put("pendingReviews", reviewRows.size());
        result.put("criticalPatients", critical);
        result.put("activePlans", countScoped("doctor_care_plan", "status='ACTIVE'"));
        result.put("patients", patientRows);
        result.put("reviewQueue", reviewRows.size() > 6 ? reviewRows.subList(0, 6) : reviewRows);
        return result;
    }

    public List<Map<String, Object>> patients() {
        requireDoctor();
        String scope = CurrentUserUtil.isAdmin() ? "" : " AND a.doctor_user_id=" + currentUserId();
        String sql = "SELECT p.id,p.name,p.gender,p.birth_date AS birthDate,p.phone,a.care_team_role AS careTeamRole," +
                "(SELECT COUNT(*) FROM alert_record ar WHERE ar.patient_id=p.id AND ar.status IN ('PENDING','CONFIRMED') AND ar.alert_level='CRITICAL') AS criticalAlerts," +
                "(SELECT MAX(CONCAT(b.record_date,' ',COALESCE(b.record_time,''))) FROM bp_self_monitor_record b WHERE b.patient_id=p.id) AS lastVitalAt " +
                "FROM patient p JOIN doctor_patient_assignment a ON a.patient_id=p.id AND a.status='ACTIVE' " +
                "WHERE p.deleted=0 AND p.status=1" + scope + " ORDER BY criticalAlerts DESC,p.name";
        return jdbc.queryForList(sql);
    }

    public List<Map<String, Object>> reviews(String status) {
        requireDoctor();
        String wanted = status == null || status.trim().isEmpty() ? "REVIEW_REQUIRED" : status.trim().toUpperCase();
        String scope = CurrentUserUtil.isAdmin() ? "" : " AND a.doctor_user_id=" + currentUserId();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT 'MEDICAL_RECORD' AS sourceType,m.id AS sourceId,m.patient_id AS patientId,p.name AS patientName," +
                "m.verification_status AS status,m.record_type AS title,m.record_date AS occurredAt,m.confidence_score AS confidence " +
                "FROM medical_record m JOIN patient p ON p.id=m.patient_id JOIN doctor_patient_assignment a ON a.patient_id=m.patient_id AND a.status='ACTIVE' " +
                "WHERE m.verification_status=?" + scope + " ORDER BY m.updated_at DESC", wanted));
        rows.addAll(jdbc.queryForList("SELECT 'AI_ANALYSIS' AS sourceType,r.id AS sourceId,r.patient_id AS patientId,p.name AS patientName," +
                "r.review_status AS status,COALESCE(r.period_label,'AI 分析') AS title,r.created_at AS occurredAt,NULL AS confidence " +
                "FROM ai_analysis_record r JOIN patient p ON p.id=r.patient_id JOIN doctor_patient_assignment a ON a.patient_id=r.patient_id AND a.status='ACTIVE' " +
                "WHERE r.review_status=?" + scope + " ORDER BY r.created_at DESC", wanted));
        rows.sort((left, right) -> String.valueOf(right.get("occurredAt")).compareTo(String.valueOf(left.get("occurredAt"))));
        return rows;
    }

    public List<Map<String, Object>> notes(Long patientId) {
        requireAssigned(patientId);
        return jdbc.queryForList("SELECT n.id,n.patient_id AS patientId,n.note_type AS noteType,n.note_text AS noteText,n.visibility," +
                "n.created_at AS createdAt,u.real_name AS doctorName FROM doctor_clinical_note n LEFT JOIN sys_user u ON u.id=n.doctor_user_id " +
                "WHERE n.patient_id=? ORDER BY n.created_at DESC", patientId);
    }

    public List<Map<String, Object>> plans(Long patientId) {
        requireAssigned(patientId);
        return jdbc.queryForList("SELECT id,patient_id AS patientId,title,plan_type AS planType,instructions,target_date AS targetDate,status,created_at AS createdAt " +
                "FROM doctor_care_plan WHERE patient_id=? ORDER BY created_at DESC", patientId);
    }

    @Transactional
    public void saveNote(Map<String, Object> body) {
        Long patientId = requiredLong(body, "patientId");
        requireAssigned(patientId);
        String text = requiredText(body, "noteText", 4000);
        String type = text(body, "noteType", "FOLLOW_UP");
        String visibility = text(body, "visibility", "CARE_TEAM");
        if (!java.util.Arrays.asList("CARE_TEAM", "PATIENT").contains(visibility)) throw new IllegalArgumentException("无效的医嘱可见范围。");
        jdbc.update("INSERT INTO doctor_clinical_note(doctor_user_id,patient_id,note_type,note_text,visibility) VALUES(?,?,?,?,?)",
                currentUserId(), patientId, type, text, visibility);
    }

    @Transactional
    public void savePlan(Map<String, Object> body) {
        Long patientId = requiredLong(body, "patientId");
        requireAssigned(patientId);
        jdbc.update("INSERT INTO doctor_care_plan(doctor_user_id,patient_id,title,plan_type,instructions,target_date,status) VALUES(?,?,?,?,?,?,?)",
                currentUserId(), patientId, requiredText(body, "title", 160), text(body, "planType", "FOLLOW_UP"),
                requiredText(body, "instructions", 4000), date(body.get("targetDate")), text(body, "status", "ACTIVE"));
    }

    @Transactional
    public void review(Map<String, Object> body) {
        String sourceType = requiredText(body, "sourceType", 30).toUpperCase();
        Long sourceId = requiredLong(body, "sourceId");
        String decision = requiredText(body, "decision", 30).toUpperCase();
        if (!java.util.Arrays.asList("APPROVED", "REJECTED").contains(decision)) throw new IllegalArgumentException("无效的审核结论。");
        Long patientId;
        if ("MEDICAL_RECORD".equals(sourceType)) {
            patientId = reviewPatient("medical_record", sourceId);
            requireAssigned(patientId);
            String verified = "APPROVED".equals(decision) ? "VERIFIED" : "REJECTED";
            jdbc.update("UPDATE medical_record SET verification_status=?,verified_by=?,verified_at=NOW() WHERE id=?", verified, currentUserId(), sourceId);
        } else if ("AI_ANALYSIS".equals(sourceType)) {
            patientId = reviewPatient("ai_analysis_record", sourceId);
            requireAssigned(patientId);
            jdbc.update("UPDATE ai_analysis_record SET review_status=?,reviewed_by=?,reviewed_at=NOW() WHERE id=?", decision, currentUserId(), sourceId);
        } else {
            throw new IllegalArgumentException("暂不支持该审核来源。");
        }
        jdbc.update("INSERT INTO doctor_review_log(doctor_user_id,patient_id,source_type,source_id,decision,review_note) VALUES(?,?,?,?,?,?)",
                currentUserId(), patientId, sourceType, sourceId, decision, text(body, "reviewNote", null));
    }

    @Transactional
    public void assign(Map<String, Object> body) {
        if (!CurrentUserUtil.isAdmin()) throw new IllegalStateException("Only administrators can assign doctors.");
        Long doctorId = requiredLong(body, "doctorUserId");
        Long patientId = requiredLong(body, "patientId");
        requireAssigned(patientId);
        Integer doctors = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 AND r.role_code='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0", Integer.class, doctorId);
        if(doctors==null||doctors==0)throw new IllegalArgumentException("Select an active doctor for the consultation.");
        jdbc.update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by,status,care_team_role) VALUES(?,?,?,'ACTIVE',?) " +
                        "ON DUPLICATE KEY UPDATE assigned_by=VALUES(assigned_by),status='ACTIVE',care_team_role=VALUES(care_team_role),assigned_at=NOW()",
                doctorId, patientId, currentUserId(), text(body, "careTeamRole", "ATTENDING"));
    }

    private void requireDoctor() {
        if (!CurrentUserUtil.isAdmin() && !CurrentUserUtil.hasRole("doctor")) throw new IllegalStateException("需要医生权限。");
    }

    private void requireAssigned(Long patientId) {
        requireDoctor();
        if(patientId==null || jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE id=? AND COALESCE(deleted,0)=0 AND status=1",Integer.class,patientId)==0)throw new IllegalArgumentException("Patient not found.");
        if (CurrentUserUtil.isAdmin()) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM doctor_patient_assignment WHERE doctor_user_id=? AND patient_id=? AND status='ACTIVE'",
                Integer.class, currentUserId(), patientId);
        if (count == null || count == 0) throw new IllegalStateException("该患者尚未分配给当前医生。");
    }

    private int countScoped(String table, String condition) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + condition;
        List<Object> args = new ArrayList<>();
        if (!CurrentUserUtil.isAdmin()) { sql += " AND doctor_user_id=?"; args.add(currentUserId()); }
        Integer count = jdbc.queryForObject(sql, args.toArray(), Integer.class);
        return count == null ? 0 : count;
    }

    private Long reviewPatient(String table,Long id) {
        List<Long> ids=jdbc.queryForList("SELECT patient_id FROM " + table + " WHERE id=?",Long.class,id);
        if(ids.isEmpty()||ids.get(0)==null)throw new IllegalArgumentException("The review record does not exist or has no patient.");
        return ids.get(0);
    }

    private Long currentUserId() {
        Long id = CurrentUserUtil.getCurrentUserId();
        if (id == null) throw new IllegalStateException("You are not signed in. Please sign in first.");
        return id;
    }

    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }
    private static Long requiredLong(Map<String, Object> body, String key) {
        Object value = body.get(key); if (value == null) throw new IllegalArgumentException(key + "不能为空。");
        return Long.valueOf(String.valueOf(value));
    }
    private static String requiredText(Map<String, Object> body, String key, int max) {
        String value = text(body, key, null); if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + "不能为空。");
        if (value.length() > max) throw new IllegalArgumentException(key + "长度超出限制。"); return value.trim();
    }
    private static String text(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key); return value == null || String.valueOf(value).trim().isEmpty() ? fallback : String.valueOf(value).trim();
    }
    private static LocalDate date(Object value) { return value == null || String.valueOf(value).trim().isEmpty() ? null : LocalDate.parse(String.valueOf(value)); }
}
