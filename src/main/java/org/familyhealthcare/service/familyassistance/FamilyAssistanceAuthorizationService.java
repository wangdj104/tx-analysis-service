package org.familyhealthcare.service.familyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
/** 普通交接需完整家庭权限；陪诊只使用 APPOINTMENTS，不扩读其它模块。 */
@Service public class FamilyAssistanceAuthorizationService {
    private final JdbcTemplate jdbc;
    public FamilyAssistanceAuthorizationService(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }
    public void requireAccount(long actor) {
        if(count("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",actor)!=1||count("SELECT COUNT(*) FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor)==0)throw denied();
    }
    public long owner(long patient) {
        List<Long> ids=jdbc.queryForList("SELECT user_id FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",Long.class,patient);
        if(ids.size()!=1)throw denied();
        return ids.get(0);
    }
    public void requireRead(long a,long p,String kind) {
        require(a,p,kind,false);
    }
    public void requireWrite(long a,long p,String kind) {
        require(a,p,kind,true);
    }
    public boolean canRead(long a,long p,String k) {
        try {
            requireRead(a,p,k);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    public boolean canWrite(long a,long p,String k) {
        try {
            requireWrite(a,p,k);
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
    private void require(long actor,long patient,String kind,boolean write) {
        requireAccount(actor);
        if(owner(patient)==actor)return;
        if(role(actor,"doctor")&&count("SELECT COUNT(*) FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id=? AND status='ACTIVE'",patient,actor)>0)return;
        List<Boolean> grants=jdbc.query("SELECT status,access_level,visible_modules,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired FROM care_access_grant WHERE patient_id=? AND grantee_user_id=?",(r,n)->"ACTIVE".equals(r.getString(1))&&permitted(r.getString(2),r.getString(3),kind,write)&&r.getBoolean(4),patient,actor);
        if(!grants.isEmpty()) {
            if(grants.contains(true)&&(role(actor,"family")||role(actor,"doctor")||role(actor,"nurse")))return;
            throw denied();
        }
        if(role(actor,"family")&&jdbc.query("SELECT access_level,visible_modules FROM care_member WHERE patient_id=? AND user_id=?",(r,n)->permitted(r.getString(1),r.getString(2),kind,write),patient,actor).contains(true))return;
        throw denied();
    }
    /**
     * Bounded read-only facts for common goals only. 81 covers at most 40 goal
     * creator/nominee pairs plus the owner; it is not the picker size (40).
     * Existing single-account paths above retain their exact behavior.
     */
    public Map<Long,CommonGoalPermission> commonGoalPermissions(long patient, Collection<Long> subjects) {
        Map<Long,CommonGoalPermission> result=new LinkedHashMap<>();
        ordinaryPermissions(patient,subjects,false).forEach((id,f)->result.put(id,new CommonGoalPermission(f.read,f.write)));
        return Collections.unmodifiableMap(result);
    }
    /** 普通交接只复用同一套有限事实；不接收任意模块，也不改变原逐人命令鉴权。 */
    public Map<Long,HandoverPermission> handoverPermissions(long patient, Collection<Long> subjects) {
        Map<Long,HandoverPermission> result=new LinkedHashMap<>();
        ordinaryPermissions(patient,subjects,true).forEach((id,f)->result.put(id,new HandoverPermission(f.read,f.write)));
        return Collections.unmodifiableMap(result);
    }
    private Map<Long,OrdinaryPermission> ordinaryPermissions(long patient, Collection<Long> subjects,boolean handover) {
        if (patient < 1 || patient > 9007199254740991L || subjects == null || subjects.size() > 81)
            throw handover?FamilyHandoverProjection.invalidRequest():WorkflowException.invalid("Invalid common goal participant set.");
        SortedSet<Long> ids = new TreeSet<>();
        for (Long subject : subjects) {
            if (subject == null || subject < 1 || subject > 9007199254740991L)
                throw handover?FamilyHandoverProjection.invalidRequest():WorkflowException.invalid("Invalid common goal participant set.");
            ids.add(subject);
        }
        long owner = owner(patient);
        Map<Long,OrdinaryPermission> result = new LinkedHashMap<>();
        for (long id : ids) result.put(id, new OrdinaryPermission());
        if (ids.isEmpty()) return Collections.unmodifiableMap(result);
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> roleArgs = new ArrayList<>(Arrays.asList("doctor", "family", "nurse"));
        roleArgs.addAll(ids);
        // Keep role_code comparison in SQL with the same parameters/collation as role().
        jdbc.query("SELECT u.id,COUNT(r.id) AS active_roles,MAX(CASE WHEN r.role_code=? THEN 1 ELSE 0 END) AS doctor_role,"
                + "MAX(CASE WHEN r.role_code=? THEN 1 ELSE 0 END) AS family_role,MAX(CASE WHEN r.role_code=? THEN 1 ELSE 0 END) AS nurse_role "
                + "FROM sys_user u LEFT JOIN sys_user_role m ON m.user_id=u.id "
                + "LEFT JOIN sys_role r ON r.id=m.role_id AND r.status=1 AND COALESCE(r.deleted,0)=0 "
                + "WHERE u.id IN (" + in + ") AND u.status=1 AND COALESCE(u.deleted,0)=0 GROUP BY u.id",
                r -> { OrdinaryPermission f=result.get(r.getLong("id")); f.active=r.getLong("active_roles")>0;
                    f.doctor=r.getInt("doctor_role")>0; f.family=r.getInt("family_role")>0; f.nurse=r.getInt("nurse_role")>0; }, roleArgs.toArray());
        List<Object> relationArgs = new ArrayList<>(); relationArgs.add(patient); relationArgs.addAll(ids);
        jdbc.query("SELECT DISTINCT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id IN ("
                + in + ") AND status='ACTIVE' LIMIT 82", r -> { result.get(r.getLong(1)).assigned=true; }, relationArgs.toArray());
        // More than 2048 facts is an unavailable projection, never truncated permission.
        int[] grantCount={0};
        jdbc.query("SELECT grantee_user_id,status,access_level,visible_modules,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired "
                + "FROM care_access_grant WHERE patient_id=? AND grantee_user_id IN (" + in + ") ORDER BY grantee_user_id,id LIMIT 2049",
                r -> { if (++grantCount[0]>2048) throw handover?FamilyHandoverProjection.eligibilityLimit():commonGoalFactLimit();
                    OrdinaryPermission f=result.get(r.getLong(1)); f.explicit=true;
                    if ("ACTIVE".equals(r.getString(2)) && r.getBoolean(5)) {
                        f.grantRead |= permitted(r.getString(3),r.getString(4),handover?"HANDOVER":"COMMON_CARE_GOAL",false);
                        f.grantWrite |= permitted(r.getString(3),r.getString(4),handover?"HANDOVER":"COMMON_CARE_GOAL",true);
                    } }, relationArgs.toArray());
        int[] memberCount={0};
        jdbc.query("SELECT user_id,access_level,visible_modules FROM care_member WHERE patient_id=? AND user_id IN ("
                + in + ") ORDER BY user_id,id LIMIT 2049",
                r -> { if (++memberCount[0]>2048) throw handover?FamilyHandoverProjection.eligibilityLimit():commonGoalFactLimit();
                    OrdinaryPermission f=result.get(r.getLong(1));
                    f.legacyRead |= permitted(r.getString(2),r.getString(3),handover?"HANDOVER":"COMMON_CARE_GOAL",false);
                    f.legacyWrite |= permitted(r.getString(2),r.getString(3),handover?"HANDOVER":"COMMON_CARE_GOAL",true); }, relationArgs.toArray());
        for (Map.Entry<Long,OrdinaryPermission> entry : result.entrySet()) {
            OrdinaryPermission f=entry.getValue(); boolean direct=entry.getKey()==owner || f.doctor && f.assigned;
            boolean eligibleRole=f.family || f.doctor || f.nurse;
            f.read=f.active && (direct || (f.explicit ? eligibleRole && f.grantRead : f.family && f.legacyRead));
            f.write=f.active && (direct || (f.explicit ? eligibleRole && f.grantWrite : f.family && f.legacyWrite));
        }
        return Collections.unmodifiableMap(result);
    }
    private static final class OrdinaryPermission {
        private boolean active,doctor,family,nurse,assigned,explicit,grantRead,grantWrite,legacyRead,legacyWrite,read,write;
    }
    public static final class CommonGoalPermission {
        private final boolean read,write;
        private CommonGoalPermission(boolean read,boolean write) { this.read=read;this.write=write; }
        public boolean canRead() { return read; }
        public boolean canWrite() { return write; }
    }
    public static final class HandoverPermission {
        private final boolean read,write;
        private HandoverPermission(boolean read,boolean write) { this.read=read;this.write=write; }
        public boolean canRead() { return read; }
        public boolean canWrite() { return write; }
    }
    private static WorkflowException commonGoalFactLimit() {
        return new WorkflowException(503,"COMMON_GOAL_ELIGIBILITY_LIMIT","Current participant permissions exceed the supported verification limit. No eligibility result is available.");
    }
    private boolean permitted(String level,String modules,String kind,boolean write) {
        if(!Arrays.asList("READ","WRITE","PROXY").contains(level)||write&&!Arrays.asList("WRITE","PROXY").contains(level))return false;
        if(modules==null||modules.trim().isEmpty())return true;
        if(!"ACCOMPANIMENT".equals(kind))return false;
        return Arrays.stream(modules.split(",")).anyMatch(s->"APPOINTMENTS".equalsIgnoreCase(s.trim()));
    }
    private boolean role(long a,String role) {
        return count("SELECT COUNT(*) FROM sys_user_role m JOIN sys_role r ON r.id=m.role_id WHERE m.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0",a,role)>0;
    }
    private int count(String sql,Object...a) {
        return jdbc.queryForObject(sql,Integer.class,a);
    }
    public static WorkflowException denied() {
        return new WorkflowException(403,"ACCESS_DENIED","Current source access is required.");
    }
}
