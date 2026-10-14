package org.familyhealthcare.service.alertworkflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Patient-scoped team governance. An assignment never creates a patient access grant. */
@Service
@ConditionalOnProperty(name = "alert-workflow.enabled", havingValue = "true")
public class AlertWorkflowNurseGovernance {
    private final JdbcTemplate jdbc;
    private final AlertWorkflowAuthorizationService auth;
    private final AlertWorkflowProperties properties;

    public AlertWorkflowNurseGovernance(JdbcTemplate jdbc, AlertWorkflowAuthorizationService auth, AlertWorkflowProperties properties) {
        this.jdbc = jdbc; this.auth = auth; this.properties=properties;
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> assign(long adminId, Map<String,Object> body) {
        auth.requireAssignmentAdmin(adminId);
        if (body == null || !new HashSet<>(Arrays.asList("patientId", "nurseUserId", "expiresAt")).containsAll(body.keySet()))
            throw AlertWorkflowException.invalid("Invalid nurse assignment fields.");
        long patientId = AlertWorkflowContracts.requireId(body.get("patientId"), "patientId");
        long nurseId = AlertWorkflowContracts.requireId(body.get("nurseUserId"), "nurseUserId");
        auth.requireActivePatient(patientId); auth.requireActiveNurse(nurseId);
        Instant now = properties.now(), expiry = null;
        if (body.get("expiresAt") != null) {
            if (!(body.get("expiresAt") instanceof String)) throw AlertWorkflowException.invalid("Expiry must include an ISO-8601 offset.");
            expiry = AlertWorkflowContracts.parseOffsetInstant((String)body.get("expiresAt"));
            if (!expiry.isAfter(now)) throw AlertWorkflowException.invalid("Assignment expiry must be in the future.");
        }
        final Instant expires = expiry;
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,assigned_by,assigned_at,expires_at,status) VALUES(?,?,?,?,?,'ACTIVE') ON DUPLICATE KEY UPDATE assigned_by=VALUES(assigned_by),assigned_at=VALUES(assigned_at),expires_at=VALUES(expires_at),revoked_at=NULL,status='ACTIVE'");
            ps.setLong(1, patientId); ps.setLong(2, nurseId); ps.setLong(3, adminId);
            setInstant(ps, 4, now); setInstant(ps, 5, expires); return ps;
        });
        auth.requireAssignmentAdmin(adminId);auth.requireActivePatient(patientId);auth.requireActiveNurse(nurseId);
        return rows("a.patient_id=? AND a.nurse_user_id=?", patientId, nurseId).get(0);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void revoke(long adminId, long assignmentId) {
        auth.requireAssignmentAdmin(adminId);
        if (assignmentId <= 0) throw AlertWorkflowException.denied();
        List<Long> patients = jdbc.queryForList("SELECT patient_id FROM care_nurse_assignment WHERE id=? FOR UPDATE", Long.class, assignmentId);
        if (patients.isEmpty()) throw AlertWorkflowException.denied();
        auth.requireAssignmentAdmin(adminId);auth.requireActivePatient(patients.get(0));
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("UPDATE care_nurse_assignment SET status='REVOKED',revoked_at=? WHERE id=? AND status='ACTIVE'");
            setInstant(ps, 1, properties.now()); ps.setLong(2, assignmentId); return ps;
        });
        auth.requireAssignmentAdmin(adminId);auth.requireActivePatient(patients.get(0));
    }

    public List<Map<String,Object>> list(long actorId, long patientId) {
        auth.requireAssignmentRead(actorId, patientId);
        List<Map<String,Object>> result=rows("a.patient_id=?", patientId);auth.requireAssignmentRead(actorId,patientId);return result;
    }

    /** Administrators select from current nurse accounts; this exposes no patient or clinical content. */
    public List<Map<String,Object>> candidates(long adminId) {
        auth.requireAssignmentAdmin(adminId);
        List<Map<String,Object>> result=jdbc.query("SELECT DISTINCT u.id,COALESCE(u.real_name,u.username) AS display_name FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.status=1 AND COALESCE(u.deleted,0)=0 AND r.status=1 AND COALESCE(r.deleted,0)=0 AND r.role_code='nurse' ORDER BY u.id",(rs,i)->org.familyhealthcare.service.workflow.WorkflowData.map("userId",rs.getLong(1),"displayName",rs.getString(2),"role","NURSE"));
        auth.requireAssignmentAdmin(adminId);return result;
    }

    private List<Map<String,Object>> rows(String where, Object... args) {
        return jdbc.query("SELECT a.id,a.patient_id,a.nurse_user_id,COALESCE(u.real_name,u.username) AS nurse_name,a.assigned_by,a.assigned_at,a.expires_at,a.revoked_at,a.status FROM care_nurse_assignment a LEFT JOIN sys_user u ON u.id=a.nurse_user_id WHERE " + where + " ORDER BY a.id", (rs, i) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id")); row.put("patientId", rs.getLong("patient_id"));
            row.put("nurseUserId", rs.getLong("nurse_user_id")); row.put("nurseName", rs.getString("nurse_name"));
            row.put("assignedBy", rs.getLong("assigned_by")); row.put("assignedAt", readInstant(rs, "assigned_at"));
            row.put("expiresAt", readInstant(rs, "expires_at")); row.put("revokedAt", readInstant(rs, "revoked_at"));
            row.put("status", rs.getString("status")); return row;
        }, args);
    }
    private static String readInstant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name, utcCalendar()); return value == null ? null : value.toInstant().toString();
    }
    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) ps.setNull(index, Types.TIMESTAMP); else ps.setTimestamp(index, Timestamp.from(value), utcCalendar());
    }
    private static Calendar utcCalendar() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
