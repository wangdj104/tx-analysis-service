package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
/** Current database authority. Selection, historical channels and administrator role are never clinical grants. */
@Service
public class MedicationExecutionAuthorizationService {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    public MedicationExecutionAuthorizationService(JdbcTemplate jdbc,MedicationExecutionProperties properties) {
        this.jdbc=jdbc;
        this.properties=properties;
    }
    public void requireAccount(long actor) {
        if(count("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",actor)!=1||count("SELECT COUNT(*) FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor)==0)throw denied();
    }
    public long owner(long patient) {
        List<Long> ids=jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",Long.class,patient);
        if(ids.size()!=1)throw denied();
        return ids.get(0);
    }
    public void requireRead(long actor,long patient) {
        requireModule(actor,patient,false);
    }
    public void requireWrite(long actor,long patient) {
        requireModule(actor,patient,true);
    }
    public void requireClinical(long actor,long patient) {
        requireWrite(actor,patient);
        if(!role(actor,"doctor"))throw denied();
    }
    public void requireSelectedDoctor(long actor,long patient,Long selected) {
        if(selected==null||actor!=selected)throw denied();
        requireClinical(actor,patient);
        requireRead(actor,patient);
    }
    public void requireSource(long actor,long patient,long prescription,long item,int version) {
        requireRead(actor,patient);
        if(count("SELECT COUNT(*) FROM electronic_prescription p JOIN electronic_prescription_item i ON i.prescription_id=p.id WHERE p.id=? AND p.patient_id=? AND p.version_no=? AND i.id=?",prescription,patient,version,item)!=1)throw denied();
    }
    public void requireNotificationAdmin(long actor) {
        requireAccount(actor);
        if(!role(actor,"admin"))throw denied();
    }
    public boolean canWrite(long actor,long patient) {
        try {
            requireWrite(actor,patient);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    public boolean canRead(long actor,long patient) {
        try {
            requireRead(actor,patient);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    public boolean canVerify(long actor,long patient,Long selected) {
        try {
            requireSelectedDoctor(actor,patient,selected);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    /** Each relationship leg is keyset-bounded before current qualification checks. A filtered page may be empty with a continuation. */
    public Map<String,Object> eligibleDoctors(long actor,long patient,String cursor,int limit) {
        requireRead(actor,patient);
        long after=MedicationExecutionQueryService.cursor(cursor,actor,patient,"CLINICIANS",0,limit);
        SortedSet<Long> candidates=new TreeSet<>();
        candidates.addAll(jdbc.queryForList("SELECT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id>? ORDER BY doctor_user_id LIMIT ?",Long.class,patient,after,limit+1));
        candidates.addAll(jdbc.queryForList("SELECT DISTINCT grantee_user_id FROM care_access_grant WHERE patient_id=? AND grantee_user_id>? ORDER BY grantee_user_id LIMIT ?",Long.class,patient,after,limit+1));
        long owner=owner(patient);
        if(owner>after)candidates.add(owner);
        boolean more=candidates.size()>limit;
        int checked=0;
        long last=after;
        List<Map<String,Object>> out=new ArrayList<>();
        for(long candidate:candidates) {
            if(checked++==limit)break;
            last=candidate;
            try {
                requireClinical(candidate,patient);
                out.addAll(jdbc.query("SELECT id,real_name,username FROM sys_user WHERE id=?",(rs,n)->map("userId",rs.getLong(1),"displayName",display(rs.getString(2),rs.getString(3))),candidate));
            }
            catch(WorkflowException denied) {
                if(denied.getStatus()!=403)throw denied;
            }
        }
        requireRead(actor,patient);
        return map("items",out,"nextCursor",more?MedicationExecutionQueryService.nextCursor(actor,patient,"CLINICIANS",0,last):null);
    }
    public String relation(long actor,long patient) {
        requireRead(actor,patient);
        return actor==owner(patient)?"OWNER":role(actor,"doctor")?"DOCTOR":role(actor,"nurse")?"NURSE":"FAMILY";
    }
    private void requireModule(long actor,long patient,boolean write) {
        requireAccount(actor);
        if(owner(patient)==actor)return;
        if(role(actor,"doctor")&&count("SELECT COUNT(*) FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id=? AND status='ACTIVE'",patient,actor)>0)return;
        List<Boolean> grants=jdbc.query("SELECT status,access_level,visible_modules,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired FROM care_access_grant WHERE patient_id=? AND grantee_user_id=?",(rs,i)->"ACTIVE".equals(rs.getString(1))&&permitted(rs.getString(2),rs.getString(3),write)&&rs.getBoolean(4),patient,actor);
        if(!grants.isEmpty()) {
            if(grants.contains(Boolean.TRUE)&&(role(actor,"family")||role(actor,"doctor")||role(actor,"nurse")))return;
            throw denied();
        }
        if(role(actor,"family")&&jdbc.query("SELECT access_level,visible_modules FROM care_member WHERE patient_id=? AND user_id=?",(rs,i)->permitted(rs.getString(1),rs.getString(2),write),patient,actor).contains(Boolean.TRUE))return;
        throw denied();
    }
    private boolean permitted(String level,String modules,boolean write) {
        if(!Arrays.asList("READ","WRITE","PROXY").contains(level)||write&&!Arrays.asList("WRITE","PROXY").contains(level))return false;
        if(modules==null||modules.trim().isEmpty())return true;
        for(String module:modules.split(","))if("MEDICATION".equalsIgnoreCase(module.trim()))return true;
        return false;
    }
    private boolean role(long actor,String role) {
        return count("SELECT COUNT(*) FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor,role)>0;
    }
    private int count(String sql,Object...args) {
        return jdbc.queryForObject(sql,Integer.class,args);
    }
    private static String display(String name,String username) {
        String s=name==null||name.trim().isEmpty()?username:name;
        if(s==null||s.trim().isEmpty())return "Clinician";
        return s.trim().substring(0,Math.min(100,s.trim().length()));
    }
    public static WorkflowException denied() {
        return new WorkflowException(403,"ACCESS_DENIED","Current medication access is required.");
    }
}
