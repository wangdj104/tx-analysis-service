package org.familyhealthcare.util;

import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.PatientMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * dataPermission and Patientparse
 */
@Component
public class DataScopeHelper {

    @Autowired
    private PatientMapper patientMapper;
    @Autowired private org.familyhealthcare.service.CareMembershipService careMembership;
    @Autowired private JdbcTemplate jdbcTemplate;

    public Long requireUserId() {
        Long userId = CurrentUserUtil.getCurrentUserId();
        if (userId == null) {
            throw new IllegalStateException("You are not signed in. Please sign in first.");
        }
        return userId;
    }

    public void assertOwnedByCurrentUser(Long ownerUserId) {
        if (CurrentUserUtil.isAdmin()) {
            return; // super administratorhavehas AlldataPermission
        }
        if (ownerUserId == null || !requireUserId().equals(ownerUserId)) {
            throw new IllegalStateException("Access deniedthis data");
        }
    }

    /** non-super administratoruseronlycanActionsyourself data */
    public void requireAdmin() {
        if (!CurrentUserUtil.isAdmin()) {
            throw new IllegalStateException("onlysupermanagementmembercan runthisActions");
        }
    }

    /** for  QueryWrapper trackadd user_id filter (super administratornot limit)  */
    public <T> void applyUserScope(QueryWrapper<T> qw) {
        if (!CurrentUserUtil.isAdmin()) {
            Long uid=requireUserId();
            java.util.List<Long> ids=accessiblePatientIds(uid);
            qw.and(q -> { q.and(legacy -> legacy.eq("user_id",uid).isNull("patient_id")); if(!ids.isEmpty()) q.or().in("patient_id",ids); });
        }
    }

    /**
     * validatePatientownershipcurrentuser, Back Patient; patientId be emptytimeBack null
     */
    public Patient requirePatient(Long patientId) {
        if (patientId == null) {
            return null;
        }
        Patient patient = patientMapper.selectById(patientId);
        if (patient == null || patient.getDeleted() != null && patient.getDeleted() == 1) {
            throw new IllegalStateException("The patient does not exist.");
        }
        if(!CurrentUserUtil.isAdmin() && !java.util.Objects.equals(patient.getUserId(),requireUserId())
                && !accessiblePatientIds(requireUserId()).contains(patientId))
            throw new IllegalStateException("You do not have access to this family member.");
        return patient;
    }

    /** Patients visible through ownership, family membership, or an active doctor assignment. */
    public java.util.List<Long> accessiblePatientIds(Long userId) {
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>(careMembership.accessiblePatients(userId));
        ids.addAll(jdbcTemplate.queryForList(
                "SELECT patient_id FROM care_access_grant WHERE grantee_user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>NOW())",
                Long.class, userId));
        if (CurrentUserUtil.hasRole("doctor")) {
            ids.addAll(jdbcTemplate.queryForList(
                    "SELECT patient_id FROM doctor_patient_assignment WHERE doctor_user_id=? AND status='ACTIVE'",
                    Long.class, userId));
        }
        return new java.util.ArrayList<>(ids);
    }

    public void requirePatientOrOwner(Long patientId,Long ownerId) {
        if(patientId!=null)requirePatient(patientId);else assertOwnedByCurrentUser(ownerId);
    }

    public String resolvePatientName(Long patientId) {
        Patient patient = requirePatient(patientId);
        return patient != null ? patient.getName() : null;
    }
}
