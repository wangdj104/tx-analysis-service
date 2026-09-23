package org.familyhealthcare.service;

import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PatientSpecialtyService {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataScopeHelper scope;
    @Autowired private PatientService patients;

    public List<Map<String,Object>> availableRoles() {
        return jdbc.queryForList("SELECT id,role_code AS roleCode,role_name AS roleName FROM sys_role WHERE LEFT(role_code,10)='specialty_' AND status=1 AND COALESCE(deleted,0)=0 ORDER BY role_name,id");
    }

    public List<Long> roleIds(Long patientId) {
        scope.requirePatient(patientId);
        return jdbc.queryForList("SELECT psr.role_id FROM patient_specialty_role psr JOIN sys_role r ON r.id=psr.role_id WHERE psr.patient_id=? AND LEFT(r.role_code,10)='specialty_' AND r.status=1 AND COALESCE(r.deleted,0)=0 ORDER BY psr.role_id", Long.class, patientId);
    }

    public Map<String,Object> menuScope(Long patientId) {
        if (patientId != null) scope.requirePatient(patientId);
        List<String> restricted = jdbc.queryForList("SELECT DISTINCT m.menu_path FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id WHERE LEFT(r.role_code,10)='specialty_' AND m.status=1 AND m.menu_path IS NOT NULL AND m.menu_path<>'' ORDER BY m.menu_path", String.class);
        List<String> allowed = patientId == null ? Collections.emptyList() : jdbc.queryForList("SELECT DISTINCT m.menu_path FROM patient_specialty_role psr JOIN sys_role r ON r.id=psr.role_id JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id WHERE psr.patient_id=? AND LEFT(r.role_code,10)='specialty_' AND r.status=1 AND COALESCE(r.deleted,0)=0 AND m.status=1 AND m.menu_path IS NOT NULL AND m.menu_path<>'' ORDER BY m.menu_path", String.class, patientId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("restrictedPaths", restricted);
        result.put("allowedPaths", allowed);
        return result;
    }

    public boolean hasRole(Long patientId, String roleCode) {
        if (patientId == null) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM patient_specialty_role psr JOIN sys_role r ON r.id=psr.role_id WHERE psr.patient_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0", Integer.class, patientId, roleCode);
        return count != null && count > 0;
    }

    @Transactional
    public void replaceRoles(Long patientId, List<Long> roleIds) {
        requireOwner(patientId);
        Set<Long> selected = new LinkedHashSet<>(roleIds == null ? Collections.emptyList() : roleIds);
        if (selected.contains(null)) throw new IllegalArgumentException("Select valid specialty roles.");
        if (!selected.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(selected.size(), "?"));
            List<Long> valid = jdbc.queryForList("SELECT id FROM sys_role WHERE id IN (" + placeholders + ") AND LEFT(role_code,10)='specialty_' AND status=1 AND COALESCE(deleted,0)=0", Long.class, selected.toArray());
            if (valid.size() != selected.size()) throw new IllegalArgumentException("Select valid specialty roles.");
        }
        jdbc.update("DELETE FROM patient_specialty_role WHERE patient_id=?", patientId);
        Long actor = scope.requireUserId();
        for (Long roleId : selected) jdbc.update("INSERT INTO patient_specialty_role(patient_id,role_id,assigned_by) VALUES(?,?,?)", patientId, roleId, actor);
    }

    @Transactional
    public boolean savePatient(Patient patient) {
        if (!patients.save(patient)) return false;
        if (patient.getSpecialtyRoleIds() != null) replaceRoles(patient.getId(), patient.getSpecialtyRoleIds());
        return true;
    }

    @Transactional
    public boolean updatePatient(Patient patient) {
        if (patient.getSpecialtyRoleIds() != null) requireOwner(patient.getId());
        if (!patients.updateOwned(patient)) return false;
        if (patient.getSpecialtyRoleIds() != null) replaceRoles(patient.getId(), patient.getSpecialtyRoleIds());
        return true;
    }

    private void requireOwner(Long patientId) {
        if (patientId == null) throw new IllegalArgumentException("Select a patient.");
        List<Long> owners = jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND COALESCE(deleted,0)=0", Long.class, patientId);
        if (owners.isEmpty()) throw new IllegalArgumentException("The patient does not exist.");
        if (!CurrentUserUtil.isAdmin() && !Objects.equals(owners.get(0), scope.requireUserId()))
            throw new IllegalStateException("Only the patient owner or an administrator can assign specialty roles.");
    }
}
