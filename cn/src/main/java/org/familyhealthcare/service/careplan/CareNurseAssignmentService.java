package org.familyhealthcare.service.careplan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Patient-scoped team governance. An assignment never creates a patient access grant. */
@Service
@ConditionalOnProperty(name = "care-plan.enabled", havingValue = "true")
public class CareNurseAssignmentService {
    private final JdbcTemplate jdbc;
    private final CarePlanAuthorizationService auth;
    private Clock clock = Clock.systemUTC();

    public CareNurseAssignmentService(JdbcTemplate jdbc, CarePlanAuthorizationService auth) {
        this.jdbc = jdbc; this.auth = auth;
    }

    @Transactional
    public Map<String,Object> assign(long adminId, Map<String,Object> body) {
        auth.requireAssignmentAdmin(adminId);
        if (body == null || !new HashSet<>(Arrays.asList("patientId", "nurseUserId", "expiresAt")).containsAll(body.keySet()))
            throw CarePlanException.invalid("护理分配字段无效。");
        long patientId = CarePlanContracts.requireId(body.get("patientId"), "patientId");
        long nurseId = CarePlanContracts.requireId(body.get("nurseUserId"), "nurseUserId");
        auth.requireActivePatient(patientId); auth.requireActiveNurse(nurseId);
        Instant now = clock.instant(), expiry = null;
        if (body.get("expiresAt") != null) {
            if (!(body.get("expiresAt") instanceof String)) throw CarePlanException.invalid("到期时间必须包含 ISO-8601 时区偏移。");
            expiry = CarePlanContracts.parseOffsetInstant((String)body.get("expiresAt"));
            if (!expiry.isAfter(now)) throw CarePlanException.invalid("护理分配到期时间必须晚于当前时间。");
        }
        final Instant expires = expiry;
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,assigned_by,assigned_at,expires_at,status) VALUES(?,?,?,?,?,'ACTIVE') ON DUPLICATE KEY UPDATE assigned_by=VALUES(assigned_by),assigned_at=VALUES(assigned_at),expires_at=VALUES(expires_at),revoked_at=NULL,status='ACTIVE'");
            ps.setLong(1, patientId); ps.setLong(2, nurseId); ps.setLong(3, adminId);
            setInstant(ps, 4, now); setInstant(ps, 5, expires); return ps;
        });
        return rows("a.patient_id=? AND a.nurse_user_id=?", patientId, nurseId).get(0);
    }

    @Transactional
    public void revoke(long adminId, long assignmentId) {
        auth.requireAssignmentAdmin(adminId);
        if (assignmentId <= 0) throw CarePlanException.denied();
        List<Long> patients = jdbc.queryForList("SELECT patient_id FROM care_nurse_assignment WHERE id=? FOR UPDATE", Long.class, assignmentId);
        if (patients.isEmpty()) throw CarePlanException.denied();
        auth.requireActivePatient(patients.get(0));
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("UPDATE care_nurse_assignment SET status='REVOKED',revoked_at=? WHERE id=? AND status='ACTIVE'");
            setInstant(ps, 1, clock.instant()); ps.setLong(2, assignmentId); return ps;
        });
    }

    public List<Map<String,Object>> list(long actorId, long patientId) {
        auth.requireAssignmentRead(actorId, patientId);
        return rows("a.patient_id=?", patientId);
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
