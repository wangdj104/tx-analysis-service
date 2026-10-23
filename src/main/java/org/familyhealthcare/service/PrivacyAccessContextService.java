package org.familyhealthcare.service;

import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A non-disclosing revision of current account grants; clinical records never enter this digest. */
@Service
public class PrivacyAccessContextService {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataScopeHelper scope;

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String revision() {
        Long actor = scope.requireUserId();
        List<Object> facts = new ArrayList<>();
        facts.add(jdbc.queryForList("SELECT id,status,deleted FROM sys_user WHERE id=?", actor));
        facts.add(jdbc.queryForList("SELECT r.id AS role_id,r.role_code,r.status AS role_status,r.deleted AS role_deleted,rm.menu_id,m.permission,m.menu_path,m.status AS menu_status FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id LEFT JOIN sys_role_menu rm ON rm.role_id=r.id LEFT JOIN sys_menu m ON m.id=rm.menu_id WHERE ur.user_id=? ORDER BY r.id,rm.menu_id", actor));
        facts.add(jdbc.queryForList("SELECT id,patient_id,access_level,visible_modules FROM care_member WHERE user_id=? ORDER BY id", actor));
        facts.add(jdbc.queryForList("SELECT id,patient_id,grantee_role,access_level,visible_modules,status,(expires_at IS NULL OR expires_at>NOW()) AS unexpired FROM care_access_grant WHERE grantee_user_id=? ORDER BY id", actor));
        facts.add(jdbc.queryForList("SELECT id,patient_id,status,care_team_role FROM doctor_patient_assignment WHERE doctor_user_id=? ORDER BY id", actor));
        facts.add(jdbc.queryForList("SELECT p.id,p.user_id,p.status,p.deleted FROM patient p WHERE p.user_id=? OR p.id IN (SELECT patient_id FROM care_member WHERE user_id=? UNION SELECT patient_id FROM care_access_grant WHERE grantee_user_id=? UNION SELECT patient_id FROM doctor_patient_assignment WHERE doctor_user_id=?) ORDER BY p.id", actor,actor,actor,actor));
        facts.add(jdbc.queryForList("SELECT ps.patient_id,ps.role_id,r.status AS specialty_status,r.deleted AS specialty_deleted FROM patient_specialty_role ps JOIN sys_role r ON r.id=ps.role_id WHERE ps.patient_id IN (SELECT id FROM patient WHERE user_id=? UNION SELECT patient_id FROM care_member WHERE user_id=? UNION SELECT patient_id FROM care_access_grant WHERE grantee_user_id=? UNION SELECT patient_id FROM doctor_patient_assignment WHERE doctor_user_id=?) ORDER BY ps.patient_id,ps.role_id", actor,actor,actor,actor));
        Integer nurses = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_nurse_assignment'", Integer.class);
        if (nurses != null && nurses > 0) facts.add(jdbc.queryForList("SELECT n.id,n.patient_id,n.status,n.revoked_at,(n.expires_at IS NULL OR n.expires_at>UTC_TIMESTAMP()) AS unexpired,p.user_id,p.status AS patient_status,p.deleted FROM care_nurse_assignment n JOIN patient p ON p.id=n.patient_id WHERE n.nurse_user_id=? ORDER BY n.id", actor));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(com.alibaba.fastjson2.JSON.toJSONString(facts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
