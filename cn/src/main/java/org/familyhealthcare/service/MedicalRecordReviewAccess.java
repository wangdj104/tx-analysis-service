package org.familyhealthcare.service;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** 当前授权单独开启新事务，不能使用详情的旧内容快照。 */
@Service
public class MedicalRecordReviewAccess {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private org.familyhealthcare.service.careplan.CarePlanAuthorizationService carePlanAuthorization;

    public MedicalRecordReviewAccess(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public void requireCurrent(MedicalRecord snapshot, boolean write, boolean doctorWorkspace) {
        requireAuthority(snapshot, write, doctorWorkspace);
    }

    /** 写入口已持有 authority 父锁；不得再开启独立事务等待自己持有的锁。 */
    public void requireLockedCurrent(MedicalRecord snapshot, long actor, boolean write, boolean doctorWorkspace) {
        org.familyhealthcare.service.workflow.WorkflowData.mutationTransaction(jdbc);
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) denied();
        requireAuthority(snapshot, write, doctorWorkspace);
    }

    private void requireAuthority(MedicalRecord snapshot, boolean write, boolean doctorWorkspace) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,patient_id,user_id FROM medical_record WHERE id=?",snapshot.getId());
        if(rows.size()!=1 || !Objects.equals(number(rows.get(0).get("id")),snapshot.getId())
                || !Objects.equals(number(rows.get(0).get("patient_id")),snapshot.getPatientId())
                || !Objects.equals(number(rows.get(0).get("user_id")),snapshot.getUserId())) denied();
        Long actor=CurrentUserUtil.getCurrentUserId();
        if(actor==null || count("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",actor)==0
                || count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor)==0) denied();
        boolean admin=role(actor,"admin"), doctor=role(actor,"doctor");
        Long patient=snapshot.getPatientId();
        if(patient==null) {if(doctorWorkspace || (!admin && !Objects.equals(actor,snapshot.getUserId()))) denied();return;}
        List<Long> owners=jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",Long.class,patient);
        if(owners.isEmpty()) denied();
        boolean assigned=doctor && count("SELECT COUNT(*) FROM doctor_patient_assignment WHERE doctor_user_id=? AND patient_id=? AND status='ACTIVE'",actor,patient)>0;
        if(doctorWorkspace) {if(!admin && !assigned) denied();return;}
        if(admin || assigned || Objects.equals(owners.get(0),actor)) return;
        // Explicit grants replace legacy family membership, including revoked or expired grants.
        List<Map<String,Object>> grants=jdbc.queryForList("SELECT access_level,visible_modules,status,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired FROM care_access_grant WHERE grantee_user_id=? AND patient_id=?",actor,patient);
        if(!grants.isEmpty()) {
            for(Map<String,Object> grant:grants) if("ACTIVE".equals(grant.get("status")) && truth(grant.get("unexpired")) && permits(grant,write)) return;
            denied();
        }
        for(Map<String,Object> member:jdbc.queryForList("SELECT access_level,visible_modules FROM care_member WHERE user_id=? AND patient_id=?",actor,patient)) if(permits(member,write)) return;
        denied();
    }
    /** 聚焦来源严格沿用照护证据权限；管理员或任务指派不能替代该权限。 */
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public void requireCurrentEvidence(MedicalRecord snapshot, long actor, long patient) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,patient_id,user_id FROM medical_record WHERE id=?", snapshot.getId());
        if (rows.size() != 1 || !Objects.equals(number(rows.get(0).get("id")), snapshot.getId())
                || !Objects.equals(number(rows.get(0).get("patient_id")), snapshot.getPatientId())
                || !Objects.equals(number(rows.get(0).get("user_id")), snapshot.getUserId())
                || !Long.valueOf(patient).equals(snapshot.getPatientId())
                || !Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())
                || !carePlanAuthorization.canReadEvidence(actor, patient, "MEDICAL_RECORD", snapshot.getId())) denied();
    }

    private boolean permits(Map<String,Object> row,boolean write) {
        if(write && !Arrays.asList("WRITE","PROXY").contains(String.valueOf(row.get("access_level")))) return false;
        String modules=row.get("visible_modules")==null?"":String.valueOf(row.get("visible_modules")).trim();
        if(modules.isEmpty()) return true;
        for(String module:modules.split(",")) if("MEDICAL".equalsIgnoreCase(module.trim())) return true;
        return false;
    }
    private boolean role(long actor,String role) {return count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor,role)>0;}
    private int count(String sql,Object...args) {return jdbc.queryForObject(sql,Integer.class,args);}
    private static Long number(Object value) {return value==null?null:((Number)value).longValue();}
    private static boolean truth(Object value) {return Boolean.TRUE.equals(value) || value instanceof Number && ((Number)value).intValue()!=0;}
    private static void denied() {throw new IllegalStateException("Access denied to source record.");}
}
