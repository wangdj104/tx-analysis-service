package org.familyhealthcare.service.careplan;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Explicit current database authority, independent of request roles, menus and admin shortcuts. */
@Service
public class CarePlanAuthorizationService {
    private final JdbcTemplate jdbc;
    private final CarePlanProperties properties;

    public CarePlanAuthorizationService(JdbcTemplate jdbc) { this(jdbc, new CarePlanProperties()); }
    @Autowired public CarePlanAuthorizationService(JdbcTemplate jdbc, CarePlanProperties properties) { this.jdbc = jdbc; this.properties = properties; }

    public void requireActiveActor(long actorId) { requireEnabled(); requireActor(actorId); }

    public void requireRead(long actorId, long patientId) { require(actorId, patientId, false, false, false); }
    public void requireRecord(long actorId, long patientId) { require(actorId, patientId, true, false, false); }
    public void requireClinical(long actorId, long patientId) { require(actorId, patientId, false, true, false); }
    public void requireNursing(long actorId, long patientId) { require(actorId, patientId, true, false, true); }

    private void require(long actor, long patient, boolean write, boolean clinical, boolean nursing) {
        long owner = requireContext(actor, patient);
        if (clinical) {
            if (!isAssignedDoctor(actor, patient)) throw CarePlanException.denied();
            return;
        }
        if (nursing) {
            if (!hasRole(actor, "nurse") || !hasNurseAssignment(actor, patient)
                    || !hasGrant(actor, patient, "CARE_PLAN", true, true)) throw CarePlanException.denied();
            return;
        }
        if (owner == actor || isAssignedDoctor(actor, patient)) return;
        // A second family identity never bypasses a nurse's role/assignment/grant requirements.
        if (isNurseIdentity(actor, patient)) {
            if (!hasRole(actor, "nurse") || !hasNurseAssignment(actor, patient)
                    || !hasGrant(actor, patient, "CARE_PLAN", write, true)) throw CarePlanException.denied();
            return;
        }
        if (!hasRole(actor, "family") || !hasGrant(actor, patient, "CARE_PLAN", write, false))
            throw CarePlanException.denied();
    }

    public boolean canReadEvidence(long actorId, long patientId, String sourceType, long sourceId) {
        try { requireRead(actorId, patientId); }
        catch (CarePlanException denied) { return false; }
        if (sourceId <= 0) return false;
        String table, module;
        if ("MEASUREMENT".equals(sourceType)) { table = "health_measurement"; module = "MEASUREMENTS"; }
        else if ("MEDICAL_RECORD".equals(sourceType)) { table = "medical_record"; module = "MEDICAL"; }
        else return false;
        if (count("SELECT COUNT(*) FROM " + table + " WHERE id=? AND patient_id=?", sourceId, patientId) == 0) return false;
        if (owner(patientId) == actorId || isAssignedDoctor(actorId, patientId)) return true;
        boolean nurse = isNurseIdentity(actorId, patientId);
        return hasGrant(actorId, patientId, module, false, nurse);
    }

    /** Assignment governance is separate from permission to see clinical content. */
    public void requireAssignmentAdmin(long actorId) {
        requireEnabled(); requireActor(actorId);
        if (!hasRole(actorId, "admin")) throw CarePlanException.denied();
    }
    public void requireAssignmentRead(long actorId, long patientId) {
        long owner = requireContext(actorId, patientId);
        if (owner != actorId && !hasRole(actorId, "admin") && !isAssignedDoctor(actorId, patientId))
            throw CarePlanException.denied();
    }
    public void requireActivePatient(long patientId) { requireEnabled(); owner(patientId); }
    public void requireActiveNurse(long nurseId) {
        requireEnabled(); requireActor(nurseId);
        if (!hasRole(nurseId, "nurse")) throw CarePlanException.denied();
    }
    /** Used only by the existing grant entry point; admin governance cannot stand in for owner consent. */
    public void requireCarePlanGrant(long actorId, long patientId) {
        if (requireContext(actorId, patientId) != actorId) throw CarePlanException.denied();
    }
    public void requireNurseGrant(long actorId, long patientId, long nurseId) {
        long owner = requireContext(actorId, patientId);
        if (owner != actorId) throw CarePlanException.denied();
        requireActiveNurse(nurseId);
        if (!hasNurseAssignment(nurseId, patientId)) throw CarePlanException.denied();
    }

    /** Minimum assignee projection; every candidate goes through the same current recording gate. */
    public List<Map<String,Object>> recorders(long actorId, long patientId) {
        requireClinical(actorId, patientId);
        List<Map<String,Object>> candidates=jdbc.query("SELECT DISTINCT u.id,COALESCE(u.real_name,u.username) AS display_name FROM sys_user u WHERE u.id=(SELECT user_id FROM patient WHERE id=?) OR EXISTS(SELECT 1 FROM doctor_patient_assignment a WHERE a.doctor_user_id=u.id AND a.patient_id=?) OR EXISTS(SELECT 1 FROM care_access_grant g WHERE g.grantee_user_id=u.id AND g.patient_id=?) ORDER BY u.id",(rs,i)->CarePlanData.map("userId",rs.getLong("id"),"displayName",rs.getString("display_name")),patientId,patientId,patientId);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> candidate:candidates) {
            long id=CarePlanData.id(candidate.get("userId"));
            try {requireRecord(id,patientId);candidate.put("role",role(id,patientId));result.add(candidate);}
            catch(CarePlanException ex){if(ex.getStatus()!=403)throw ex;}
        }
        return result;
    }
    /** Durable audit identity comes from the current database, never client-supplied fields. */
    public Map<String,Object> actorSnapshot(long actorId,long patientId) {
        requireRead(actorId,patientId);
        String role=role(actorId,patientId);
        String name=jdbc.queryForObject("SELECT COALESCE(real_name,username) FROM sys_user WHERE id=?",String.class,actorId);
        return CarePlanData.map("actorName",name,"actorRole",role.toUpperCase(Locale.ROOT),"actorRelation",role.toUpperCase(Locale.ROOT));
    }
    private String role(long actor,long patient) {
        if(isAssignedDoctor(actor,patient))return "doctor";
        if(owner(patient)==actor)return "patient";
        return isNurseIdentity(actor,patient)?"nurse":"family";
    }

    private long requireContext(long actor, long patient) {
        requireEnabled(); requireActor(actor); return owner(patient);
    }
    private void requireEnabled() {
        properties.requireEnabled();
    }
    private void requireActor(long actor) {
        if (actor <= 0 || count("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0", actor) == 0
                || count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.status=1 AND COALESCE(r.deleted,0)=0", actor) == 0)
            throw CarePlanException.denied();
    }
    private long owner(long patient) {
        List<Long> owners = jdbc.query("SELECT user_id FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",
                (rs, i) -> { long value = rs.getLong(1); return rs.wasNull() ? 0L : value; }, patient);
        if (patient <= 0 || owners.isEmpty()) throw CarePlanException.denied();
        return owners.get(0);
    }
    private boolean hasRole(long actor, String role) {
        return count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0", actor, role) > 0;
    }
    private boolean isAssignedDoctor(long actor, long patient) {
        return hasRole(actor, "doctor") && count("SELECT COUNT(*) FROM doctor_patient_assignment WHERE doctor_user_id=? AND patient_id=? AND status='ACTIVE'", actor, patient) > 0;
    }
    private boolean isNurseIdentity(long actor, long patient) {
        return count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code='nurse'", actor) > 0
                || count("SELECT COUNT(*) FROM care_access_grant WHERE grantee_user_id=? AND patient_id=? AND grantee_role='NURSE'", actor, patient) > 0
                // Retain patient-scoped nursing provenance after role removal / assignment expiry or revoke.
                // History only selects the stricter gate; it never supplies current role, assignment or grant rights.
                || count("SELECT COUNT(*) FROM care_nurse_assignment WHERE nurse_user_id=? AND patient_id=?", actor, patient) > 0;
    }
    private boolean hasNurseAssignment(long actor, long patient) {
        List<Boolean> valid = jdbc.query("SELECT expires_at FROM care_nurse_assignment WHERE nurse_user_id=? AND patient_id=? AND status='ACTIVE' AND revoked_at IS NULL",
                (rs, i) -> unexpired(rs.getTimestamp(1, utcCalendar())), actor, patient);
        return valid.contains(Boolean.TRUE);
    }
    private boolean hasGrant(long actor, long patient, String module, boolean write, boolean nurse) {
        List<Boolean> valid = jdbc.query("SELECT grantee_role,access_level,visible_modules,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired FROM care_access_grant WHERE grantee_user_id=? AND patient_id=? AND status='ACTIVE'",
                (rs, i) -> {
                    String role = rs.getString("grantee_role"), level = rs.getString("access_level");
                    return (nurse ? Arrays.asList("NURSE", "FAMILY", "GUARDIAN").contains(role) : Arrays.asList("FAMILY", "GUARDIAN").contains(role))
                            && Arrays.asList("READ", "WRITE", "PROXY").contains(level)
                            && (!write || Arrays.asList("WRITE", "PROXY").contains(level))
                            && includesModule(rs.getString("visible_modules"), module)
                            && rs.getBoolean("unexpired");
                }, actor, patient);
        return valid.contains(Boolean.TRUE);
    }
    private boolean includesModule(String configured, String module) {
        // CARE_PLAN is always opt-in; old blank/all grants must not become clinical-sharing grants.
        if (configured == null || configured.trim().isEmpty()) return !"CARE_PLAN".equals(module);
        for (String value : configured.split(",")) if (module.equalsIgnoreCase(value.trim())) return true;
        return false;
    }
    private boolean unexpired(Timestamp expiry) { return expiry == null || expiry.toInstant().isAfter(properties.now()); }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static Calendar utcCalendar() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
