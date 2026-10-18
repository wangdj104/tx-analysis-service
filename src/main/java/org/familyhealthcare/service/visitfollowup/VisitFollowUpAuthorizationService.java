package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 只批量读取当前资格。预约沿用家庭兼容门，护理计划始终单独显式授权。 */
@Service
public final class VisitFollowUpAuthorizationService {
    private final JdbcTemplate jdbc;
    private final VisitFollowUpProperties properties;
    private final CarePlanProperties carePlans;
    public VisitFollowUpAuthorizationService(JdbcTemplate jdbc, VisitFollowUpProperties properties, CarePlanProperties carePlans) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.properties=Objects.requireNonNull(properties);
        this.carePlans=Objects.requireNonNull(carePlans);
    }
    public Snapshot snapshot(long patient, Collection<Long> subjects) {
        return snapshot(patient, subjects, 500);
    }
    Snapshot snapshot(long patient, Collection<Long> subjects, int maximumRows) {
        positive(patient);
        if(subjects==null||subjects.size()>100||maximumRows<1||maximumRows>500)throw limit();
        SortedSet<Long> ids=new TreeSet<>();
        for(Long user:subjects) {
            if(user==null)throw denied();
            positive(user);
            ids.add(user);
        }
        if(ids.isEmpty())throw denied();
        String in=String.join(",",Collections.nCopies(ids.size(),"?"));
        List<Object> args=new ArrayList<>();
        args.add(patient);
        args.addAll(ids);
        Map<String,List<Map<String,Object>>> groups=new LinkedHashMap<>();
        groups.put("patient",query("SELECT id,user_id,status,deleted FROM patient WHERE id=?",maximumRows,patient));
        groups.put("sys_user",query("SELECT u.id,u.status,u.deleted,COALESCE(u.real_name,u.username) AS display_name FROM sys_user u WHERE u.id IN ("+in+") ORDER BY u.id LIMIT "+(maximumRows+1),maximumRows,ids.toArray()));
        groups.put("sys_user_role",query("SELECT m.id,m.user_id,m.role_id,r.role_code,r.status AS role_status,r.deleted AS role_deleted FROM sys_user_role m LEFT JOIN sys_role r ON r.id=m.role_id WHERE m.user_id IN ("+in+") ORDER BY m.user_id,m.role_id,m.id LIMIT "+(maximumRows+1),maximumRows,ids.toArray()));
        groups.put("care_access_grant",query("SELECT id,patient_id,grantee_user_id,grantee_role,status,access_level,visible_modules,expires_at FROM care_access_grant WHERE patient_id=? AND grantee_user_id IN ("+in+") ORDER BY id LIMIT "+(maximumRows+1),maximumRows,args.toArray()));
        groups.put("doctor_patient_assignment",query("SELECT id,patient_id,doctor_user_id,status FROM doctor_patient_assignment WHERE patient_id=? AND doctor_user_id IN ("+in+") ORDER BY id LIMIT "+(maximumRows+1),maximumRows,args.toArray()));
        groups.put("care_member",query("SELECT id,patient_id,user_id,access_level,visible_modules FROM care_member WHERE patient_id=? AND user_id IN ("+in+") ORDER BY id LIMIT "+(maximumRows+1),maximumRows,args.toArray()));
        groups.put("care_nurse_assignment",query("SELECT id,patient_id,nurse_user_id,status,expires_at,revoked_at FROM care_nurse_assignment WHERE patient_id=? AND nurse_user_id IN ("+in+") ORDER BY id LIMIT "+(maximumRows+1),maximumRows,args.toArray()));
        return new Snapshot(patient,ids,groups,properties.now(),carePlans.isEnabled());
    }
    /** 同一批事实在等待后和提交前重读；过期时间用此刻真实时钟重新判断。 */
    public void requireCommand(Snapshot s,long actor,VisitFollowUpContracts.Command command) {
        if(s.patient!=command.getPatientId())throw denied();
        String operation=command.getOperation().name();
        if(command.getSourceKind()==VisitFollowUpContracts.SourceKind.VISIT_RECORD) {
            if(!s.canClinical(actor))throw denied();
            if(command.isVisitBinding()&&"ACTIONS_REQUIRED".equals(command.getBody().get("disposition"))&&!s.canPlanClinical(actor))throw denied();
        } else if(!s.canAppointmentWrite(actor))throw denied();
        Object owner=command.getBody().get("responsibleUserId");
        if(owner!=null&&!s.canAppointmentWrite(VisitFollowUpContracts.id(owner,"responsibleUserId")))throw denied();
    }
    public static final class Snapshot {
        private final long patient,owner;
        private final Instant now;
        private final boolean plansEnabled;
        private final SortedSet<Long> subjects;
        private final Map<String,List<Map<String,Object>>> rows;
        private final Map<Long,Person> people=new LinkedHashMap<>();
        private Snapshot(long patient,SortedSet<Long> subjects,Map<String,List<Map<String,Object>>> groups,Instant now,boolean plansEnabled) {
            this.patient=patient;
            this.now=now;
            this.plansEnabled=plansEnabled;
            this.subjects=Collections.unmodifiableSortedSet(new TreeSet<>(subjects));
            List<Map<String,Object>> patients=groups.get("patient");
            if(patients.size()!=1||!VisitFollowUpAuthorizationService.active(patients.get(0))||number(patients.get(0).get("id"))!=patient||number(patients.get(0).get("user_id"))<1)throw denied();
            owner=number(patients.get(0).get("user_id"));
            Map<String,List<Map<String,Object>>> copy=new LinkedHashMap<>();
            groups.forEach((table,values)-> {
                List<Map<String,Object>> r=new ArrayList<>();
                values.forEach(value->r.add(Collections.unmodifiableMap(new LinkedHashMap<>(value))));
                copy.put(table,Collections.unmodifiableList(r));
            });
            rows=Collections.unmodifiableMap(copy);
            for(Long subject:subjects)people.put(subject,new Person());
            for(Map<String,Object> r:rows.get("sys_user")) {
                Person p=person(number(r.get("id")));
                p.account=VisitFollowUpAuthorizationService.active(r);
                p.name=String.valueOf(r.get("display_name"));
            }
            for(Map<String,Object> r:rows.get("sys_user_role")) {
                Person p=person(number(r.get("user_id")));
                String role=text(r.get("role_code"));
                if(role!=null)role=role.replaceFirst(" +$", "").toLowerCase(Locale.ROOT);
                if("nurse".equals(role))p.nursingProvenance=true;
                if(number(r.get("role_status"))==1&&zero(r.get("role_deleted"))) {
                    p.anyRole=true;
                    p.roles.add(role);
                }
            }
            for(Map<String,Object> r:rows.get("doctor_patient_assignment"))if("ACTIVE".equals(r.get("status")))person(number(r.get("doctor_user_id"))).assigned=true;
            for(Map<String,Object> r:rows.get("care_access_grant")) {
                Person p=person(number(r.get("grantee_user_id")));
                p.grants.add(r);
                if("NURSE".equals(r.get("grantee_role")))p.nursingProvenance=true;
            }
            for(Map<String,Object> r:rows.get("care_member"))person(number(r.get("user_id"))).members.add(r);
            for(Map<String,Object> r:rows.get("care_nurse_assignment")) {
                Person p=person(number(r.get("nurse_user_id")));
                p.nursingProvenance=true;
                if("ACTIVE".equals(r.get("status"))&&r.get("revoked_at")==null&&unexpired(r.get("expires_at")))p.nurseAssigned=true;
            }
        }
        public long getPatientId() {
            return patient;
        }
        public long getOwnerId() {
            return owner;
        }
        public SortedSet<Long> getSubjects() {
            return subjects;
        }
        Map<String,List<Map<String,Object>>> rows() {
            return rows;
        }
        public String signature() {
            return WorkflowData.hash(VisitFollowUpRepository.canonical(rows));
        }
        public boolean canAppointmentRead(long user) {
            return ordinary(user,"APPOINTMENTS",false);
        }
        public boolean canAppointmentWrite(long user) {
            return ordinary(user,"APPOINTMENTS",true);
        }
        public boolean canVisitRead(long user) {
            return ordinary(user,"VISITS",false);
        }
        public boolean canVisitWrite(long user) {
            return ordinary(user,"VISITS",true);
        }
        public boolean canClinical(long user) {
            return active(user)&&assignedDoctor(user)&&canVisitWrite(user);
        }
        public boolean canPlanClinical(long user) {
            return plansEnabled&&canClinical(user);
        }
        public boolean canPlanRead(long user) {
            return plan(user,false);
        }
        public boolean canPlanRecord(long user) {
            return plan(user,true);
        }
        /** 证据仍须调用方另核同患者；此门只复用CARE_PLAN的当前模块授权，不回退旧成员。 */
        public boolean canPlanEvidenceModule(long user,String module) {
            if(!Arrays.asList("MEDICAL","MEASUREMENTS").contains(module)||!canPlanRead(user))return false;
            if(user==owner||assignedDoctor(user))return true;
            Person p=person(user);
            for(Map<String,Object> grant:p.grants) {
                String role=text(grant.get("grantee_role"));
                boolean eligibleRole=p.nursingProvenance?Arrays.asList("NURSE","FAMILY","GUARDIAN").contains(role):Arrays.asList("FAMILY","GUARDIAN").contains(role);
                if(eligibleRole&&"ACTIVE".equals(grant.get("status"))&&unexpired(grant.get("expires_at"))&&permitted(grant,module,false,false))return true;
            }
            return false;
        }
        public boolean isActiveDoctor(long user) {
            return active(user)&&person(user).roles.contains("doctor");
        }
        public String displayName(long user) { return person(user).name; }
        public String actorName(long user) {
            if(!active(user))throw denied();
            return person(user).name;
        }
        public String actorRelation(long user) {
            if(!active(user))throw denied();
            return assignedDoctor(user)?"DOCTOR":user==owner?"PATIENT":person(user).roles.contains("nurse")?"NURSE":"FAMILY";
        }
        private boolean active(long user) {
            Person p=people.get(user);
            return p!=null&&p.account&&p.anyRole;
        }
        private boolean assignedDoctor(long user) {
            Person p=person(user);
            return p.roles.contains("doctor")&&p.assigned;
        }
        private boolean ordinary(long user,String module,boolean write) {
            if(!active(user))return false;
            Person p=person(user);
            if(user==owner||assignedDoctor(user))return true;
            if(!p.grants.isEmpty()) {
                if(Collections.disjoint(p.roles,Arrays.asList("family","doctor","nurse")))return false;
                for(Map<String,Object> g:p.grants)if("ACTIVE".equals(g.get("status"))&&unexpired(g.get("expires_at"))&&permitted(g,module,write,false))return true;
                return false;
            }
            if(!p.roles.contains("family"))return false;
            for(Map<String,Object> member:p.members)if(permitted(member,module,write,false))return true;
            return false;
        }
        private boolean plan(long user,boolean write) {
            if(!plansEnabled||!active(user))return false;
            Person p=person(user);
            if(user==owner||assignedDoctor(user))return true;
            if(p.nursingProvenance&&(!p.roles.contains("nurse")||!p.nurseAssigned))return false;
            if(!p.nursingProvenance&&!p.roles.contains("family"))return false;
            for(Map<String,Object> grant:p.grants) {
                String role=text(grant.get("grantee_role"));
                boolean grantRole=p.nursingProvenance?Arrays.asList("NURSE","FAMILY","GUARDIAN").contains(role):Arrays.asList("FAMILY","GUARDIAN").contains(role);
                if(grantRole&&"ACTIVE".equals(grant.get("status"))&&unexpired(grant.get("expires_at"))&&permitted(grant,"CARE_PLAN",write,true))return true;
            }
            return false;
        }
        private boolean unexpired(Object expiry) {
            return expiry==null||instant(expiry).isAfter(now);
        }
        private Person person(long id) {
            Person p=people.get(id);
            if(p==null)throw changed();
            return p;
        }
    }
    private static final class Person {
        boolean account,anyRole,assigned,nursingProvenance,nurseAssigned;
        String name;
        Set<String> roles=new HashSet<>();
        List<Map<String,Object>> grants=new ArrayList<>(),members=new ArrayList<>();
    }
    private static boolean permitted(Map<String,Object> row,String module,boolean write,boolean optIn) {
        String level=text(row.get("access_level"));
        if(!Arrays.asList("READ","WRITE","PROXY").contains(level)||(write&&!Arrays.asList("WRITE","PROXY").contains(level)))return false;
        String modules=text(row.get("visible_modules"));
        if(modules==null||modules.trim().isEmpty())return !optIn;
        for(String configured:modules.split(","))if(module.equalsIgnoreCase(configured.trim()))return true;
        return false;
    }
    private List<Map<String,Object>> query(String sql,int maximum,Object...args) {
        return jdbc.query(sql,statement-> {
            statement.setQueryTimeout(5);
            for(int i=0; i<args.length; i++)statement.setObject(i+1,args[i]);
        },(rs,index)-> {
            if(index>=maximum)throw limit();
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int column=1; column<=md.getColumnCount(); column++) {
                Object value=rs.getObject(column);
                if(value instanceof Timestamp)value=rs.getTimestamp(column,WorkflowData.utc()).toInstant();
                row.put(md.getColumnLabel(column).toLowerCase(Locale.ROOT),value);
            }
            return row;
        });
    }
    private static boolean active(Map<String,Object> row) {
        return number(row.get("status"))==1&&zero(row.get("deleted"));
    }
    private static boolean zero(Object n) {
        return n==null||number(n)==0;
    }
    private static long number(Object n) {
        return n instanceof Number?((Number)n).longValue():Boolean.TRUE.equals(n)?1:0;
    }
    private static String text(Object value) {
        return value==null?null:String.valueOf(value);
    }
    private static Instant instant(Object value) {
        if(value instanceof Instant)return (Instant)value;
        if(value instanceof Timestamp)return ((Timestamp)value).toInstant();
        throw changed();
    }
    private static void positive(long id) {
        if(id<=0||id>9007199254740991L)throw denied();
    }
    static WorkflowException denied() {
        return new WorkflowException(403,"ACCESS_DENIED","Current source access is required.");
    }
    static WorkflowException changed() {
        return new WorkflowException(409,"ADMISSION_CONTEXT_CHANGED","The source or current authority changed. Refresh and review this request.");
    }
    static WorkflowException limit() {
        return new WorkflowException(409,"ADMISSION_LIMIT","This request exceeds the bounded verification limit.");
    }
}
