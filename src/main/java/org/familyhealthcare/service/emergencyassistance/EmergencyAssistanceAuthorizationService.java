package org.familyhealthcare.service.emergencyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
/** Current EMERGENCY authority only; invitation and admin status never grant access. */
@Service public class EmergencyAssistanceAuthorizationService {
    private final JdbcTemplate jdbc;
    public EmergencyAssistanceAuthorizationService(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }
    public void requireAccount(long actor) {
        if(count("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",actor)!=1||count("SELECT COUNT(*) FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=? AND r.status=1 AND COALESCE(r.deleted,0)=0",
        actor)==0)throw denied();
    }
    public long owner(long patient) {
        List<Long> ids=jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",
        Long.class,patient);
        if(ids.size()!=1)throw denied();
        return ids.get(0);
    }
    public void requireRead(long a,long p) {
        require(a,p,false);
    }
    public void requireWrite(long a,long p) {
        require(a,p,true);
    }
    public boolean canRead(long a,long p) {
        try {
            requireRead(a,p);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    public boolean canWrite(long a,long p) {
        try {
            requireWrite(a,p);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    public void requireNotificationAdmin(long a) {
        requireAccount(a);
        if(!role(a,"admin"))throw denied();
    }
    public void requireDoctor(long a) {
        requireAccount(a);
        if(!role(a,"doctor"))throw denied();
    }
    private void require(long actor,long patient,boolean write) {
        requireAccount(actor);
        if(owner(patient)==actor)return;
        if(role(actor,"doctor")&&count("SELECT COUNT(*) FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id=? AND status='ACTIVE'",
        patient,actor)>0)return;
        List<Boolean> grants=jdbc.query("SELECT status,access_level,visible_modules,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired FROM care_access_grant WHERE patient_id=? AND grantee_user_id=?",
        (r,n)->"ACTIVE".equals(r.getString(1))&&permitted(r.getString(2),r.getString(3),write)&&r.getBoolean(4),
        patient,actor);
        if(!grants.isEmpty()) {
            if(grants.contains(true)&&(role(actor,"family")||role(actor,"doctor")||role(actor,"nurse")))return;
            throw denied();
        }
        if(role(actor,"family")&&jdbc.query("SELECT access_level,visible_modules FROM care_member WHERE patient_id=? AND user_id=?",
        (r,n)->permitted(r.getString(1),r.getString(2),write),patient,actor).contains(true))return;
        throw denied();
    }
    private boolean permitted(String level,String modules,boolean write) {
        if(!Arrays.asList("READ","WRITE","PROXY").contains(level)||write&&!Arrays.asList("WRITE","PROXY").contains(level))return false;
        if(modules==null||modules.trim().isEmpty())return true;
        return Arrays.stream(modules.split(",")).anyMatch(s->"EMERGENCY".equalsIgnoreCase(s.trim()));
    }
    private boolean role(long a,String role) {
        return count("SELECT COUNT(*) FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0",
        a,role)>0;
    }
    private int count(String sql,Object...a) {
        return jdbc.queryForObject(sql,Integer.class,a);
    }
    public static WorkflowException denied() {
        return new WorkflowException(403,"ACCESS_DENIED","Current source access is required.");
    }
}
