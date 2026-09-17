package org.familyhealthcare.service;

import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.PatientMapper;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CareMembershipService {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PatientMapper patients;

    public boolean isMember(Long patientId,Long userId) {
        if(patientId==null || userId==null)return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM care_member WHERE patient_id=? AND user_id=?",Long.class,patientId,userId)>0;
    }
    public List<Long> accessiblePatients(Long uid) {
        return jdbc.queryForList("SELECT p.id FROM patient p WHERE COALESCE(p.deleted,0)=0 AND (p.user_id=? OR EXISTS (SELECT 1 FROM care_member m WHERE m.patient_id=p.id AND m.user_id=?))",Long.class,uid,uid);
    }
    public List<Map<String,Object>> members(Long patientId) {
        return jdbc.queryForList("SELECT u.id AS userId,u.username,COALESCE(NULLIF(u.real_name,''),u.username) AS name,CASE WHEN p.user_id=u.id THEN 'recordCreateperson' ELSE m.relation_name END AS relationName FROM patient p JOIN sys_user u ON u.id=p.user_id OR EXISTS (SELECT 1 FROM care_member x WHERE x.patient_id=p.id AND x.user_id=u.id) LEFT JOIN care_member m ON m.patient_id=p.id AND m.user_id=u.id WHERE p.id=? AND COALESCE(u.deleted,0)=0",patientId);
    }
    public void requireOwner(Long patientId) {
        Patient p=patients.selectById(patientId);
        if(p==null || !CurrentUserUtil.isAdmin() && !Objects.equals(p.getUserId(),CurrentUserUtil.getCurrentUserId()))throw new IllegalStateException("Only the record owner can manage caregivers.");
    }
    public String invite(Long patientId) {
        requireOwner(patientId);String code=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO care_invitation(patient_id,user_id,invite_code,expires_at) VALUES(?,?,?,?)",patientId,CurrentUserUtil.getCurrentUserId(),code,java.time.LocalDateTime.now().plusDays(7));
        return code;
    }
    @Transactional public Long join(String code,String relation) {
        Long uid=CurrentUserUtil.getCurrentUserId();if(uid==null)throw new IllegalStateException("Please first Sign In");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM care_invitation WHERE invite_code=? AND expires_at>NOW() AND accepted_by IS NULL FOR UPDATE",code);
        if(rows.isEmpty())throw new IllegalArgumentException("The invitation code is invalid, expired, or already used.");
        Long pid=((Number)rows.get(0).get("patient_id")).longValue();
        if(patients.selectById(pid)==null)throw new IllegalArgumentException("The family member record does not exist.");
        jdbc.update("INSERT INTO care_member(patient_id,user_id,relation_name) VALUES(?,?,?) ON DUPLICATE KEY UPDATE relation_name=VALUES(relation_name)",pid,uid,relation==null?"family caregiver":relation);
        jdbc.update("UPDATE care_invitation SET accepted_by=? WHERE invite_code=?",uid,code);return pid;
    }
    public void remove(Long pid,Long uid) {requireOwner(pid);jdbc.update("DELETE FROM care_member WHERE patient_id=? AND user_id=?",pid,uid);}
    public boolean canAssign(Long pid,Long uid) {
        if(uid==null)return true;Patient p=patients.selectById(pid);return p!=null&&(Objects.equals(p.getUserId(),uid)||isMember(pid,uid));
    }
}
