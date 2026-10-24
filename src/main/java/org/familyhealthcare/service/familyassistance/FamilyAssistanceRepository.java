package org.familyhealthcare.service.familyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.denied;
@Repository public class FamilyAssistanceRepository {
    private final JdbcTemplate jdbc;
    private final HouseholdAuthorityLocks locks;
    private final HouseholdWorkflowSchema schema;
    private final FamilyAssistanceAuthorizationService auth;
    public FamilyAssistanceRepository(JdbcTemplate jdbc,HouseholdAuthorityLocks locks,HouseholdWorkflowSchema schema,FamilyAssistanceAuthorizationService auth) {
        this.jdbc=jdbc;
        this.locks=locks;
        this.schema=schema;
        this.auth=auth;
    }
    public HouseholdWorkflowSchema.StorageReadiness inspectStorageMetadata() { return schema.inspectStorageMetadata(); }
    // Boundary callers must invoke methods: Spring may inject an exception-translation proxy.
    public HouseholdAuthorityLocks.LockedAuthority lockBoundarySubjects(long patient,Collection<Long> subjects) { return locks.lockPatientAndSubjects(patient,subjects); }
    public void requireBoundarySelected(HouseholdAuthorityLocks.LockedAuthority token,long patient,long actor,Map<String,Collection<Long>> selected) { schema.requireSelectedRows(token,patient,actor,selected); }
    public void requireBoundaryWrite(long actor,long patient,String kind) { auth.requireWrite(actor,patient,kind); }
    public void requireBoundaryRead(long actor,long patient,String kind) { auth.requireRead(actor,patient,kind); }
    public void requireBoundaryDoctor(long doctor) { auth.requireDoctor(doctor); }
    public Map<String,Object> assistance(long id) {
        return one("SELECT * FROM family_assistance WHERE id=?",id);
    }
    public Map<String,Object> source(String kind,long source) {
        if("CARE_ITEM".equals(kind))return nativeSource("SELECT id,patient_id,kind,status,event_at,family_workflow_version,family_assistance_id FROM care_item WHERE id=?",source);
        if("CARE_APPOINTMENT".equals(kind))return nativeSource("SELECT id,patient_id,start_at,end_at,status,doctor_user_id,family_workflow_version,family_assistance_id FROM care_appointment WHERE id=?",source);
        throw WorkflowException.invalid("Invalid typed source.");
    }
    public String requestKind(String kind,Map<String,Object> source) {
        if("CARE_APPOINTMENT".equals(kind)||"APPOINTMENT".equals(source.get("kind")))return "ACCOMPANIMENT";
        String k=(String)source.get("kind");
        if(!Arrays.asList("SYMPTOM","QUESTION","HANDOVER").contains(k))throw denied();
        return k;
    }
    public static long sourceId(Map<String,Object> a) {
        return id(a.get("CARE_ITEM".equals(a.get("source_kind"))?"care_item_id":"care_appointment_id"));
    }
    public Map<String,Object> minimal(String kind,Map<String,Object> s) {
        Map<String,Object> value=map("kind",kind,"id",s.get("id"),"time",iso(s.get("CARE_ITEM".equals(kind)?"event_at":"start_at")),"place",null,"status",s.get("status"));
        value.put("fingerprint",hash(json(map("minimal",value,"end",iso(s.get("end_at")),"doctor",s.get("doctor_user_id")))));
        return value;
    }
    /** 先父锁再身份和来源；发现和锁后重读都限定同一真实来源，不扫描完整历史。 */
    public HouseholdAuthorityLocks.LockedAuthority admit(long actor,long patient,String kind,long sourceId,Long target,Long extraUser,String key,Long event,Long notification) {
        mutationTransaction(jdbc);
        schema.inspectStorageMetadata().requireStructurallyReady();
        if(one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw denied();
        Map<String,Object> s=source(kind,sourceId);
        if(s==null||id(s.get("patient_id"))!=patient)throw denied();
        SortedSet<Long> users=new TreeSet<>();
        users.add(actor);
        if(extraUser!=null)users.add(extraUser);
        if(s.get("doctor_user_id")!=null)users.add(id(s.get("doctor_user_id")));
        SortedSet<Long> targets=new TreeSet<>();
        if(target!=null)targets.add(target);
        if(s.get("family_assistance_id")!=null)targets.add(id(s.get("family_assistance_id")));
        Map<String,Object> replay=key==null?null:one("SELECT * FROM family_assistance_command WHERE actor_id=? AND command_key=?",actor,key);
        if(replay!=null) {
            FamilyAssistanceContracts.requireMinimalReceipt(object((String)replay.get("result_json")));
            targets.add(id(replay.get("assistance_id")));
            event=id(object((String)replay.get("result_json")).get("eventId"));
        }
        for(long aid:new ArrayList<>(targets)) {
            Map<String,Object> a=assistance(aid);
            if(a==null||id(a.get("patient_id"))!=patient||!kind.equals(a.get("source_kind"))||sourceId(a)!=sourceId)throw denied();
            for(String u:Arrays.asList("originator_id","assigned_user_id","accepted_by","handled_by"))if(a.get(u)!=null)users.add(id(a.get(u)));
            if(a.get("previous_assistance_id")!=null)targets.add(id(a.get("previous_assistance_id")));
        }
        if(users.size()+targets.size()>50)throw conflict("ADMISSION_LIMIT");
        HouseholdAuthorityLocks.LockedAuthority token=locks.lockPatientAndSubjects(patient,users);
        String table="CARE_ITEM".equals(kind)?"care_item":"care_appointment";
        one("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",sourceId);
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();
        selected.put(table,Collections.singleton(sourceId));
        for(long aid:targets)one("SELECT id FROM family_assistance WHERE id=? FOR UPDATE",aid);
        if(!targets.isEmpty())selected.put("family_assistance",targets);
        if(event!=null) {
            one("SELECT id FROM family_assistance_event WHERE id=? FOR UPDATE",event);
            selected.put("family_assistance_event",Collections.singleton(event));
        }
        if(notification!=null) {
            one("SELECT id FROM family_assistance_notification WHERE id=? FOR UPDATE",notification);
            selected.put("family_assistance_notification",Collections.singleton(notification));
        }
        schema.requireSelectedRows(token,patient,actor,selected);
        Map<String,Object> fresh=source(kind,sourceId);
        if(fresh==null||id(fresh.get("patient_id"))!=patient||!Objects.equals(s.get("family_assistance_id"),fresh.get("family_assistance_id"))||!Objects.equals(s.get("doctor_user_id"),fresh.get("doctor_user_id")))throw conflict("SOURCE_CHANGED");
        return token;
    }
    /** Q callers supply their complete discovered subject union before native/B locks. B never reads Q state. */
    public HouseholdAuthorityLocks.LockedAuthority admitQuestion(long actor,long patient,long sourceId,Collection<Long> questionSubjects) {
        mutationTransaction(jdbc);
        Map<String,Object> patientRow=one("SELECT id,user_id FROM patient WHERE id=? FOR UPDATE",patient);
        if(patientRow==null)throw denied();
        Map<String,Object> discovered=source("CARE_ITEM",sourceId);
        if(discovered==null||id(discovered.get("patient_id"))!=patient||!"QUESTION".equals(discovered.get("kind")))throw denied();
        SortedSet<Long> users=new TreeSet<>();users.add(actor);users.add(id(patientRow.get("user_id")));
        if(questionSubjects!=null)for(Long user:questionSubjects){if(user==null||user<=0||user>9007199254740991L)throw denied();users.add(user);}
        Map<Long,Map<String,Object>> history=new TreeMap<>();
        Long next=discovered.get("family_assistance_id")==null?null:id(discovered.get("family_assistance_id"));
        if((next==null&&id(discovered.get("family_workflow_version"))!=0)||(next!=null&&id(discovered.get("family_workflow_version"))!=1))throw conflict("SOURCE_CHANGED");
        while(next!=null){
            if(history.containsKey(next)||history.size()+users.size()>=50)throw conflict("ADMISSION_LIMIT");
            Map<String,Object> row=questionAdmissionIdentity(next);
            if(row==null||id(row.get("patient_id"))!=patient||!"CARE_ITEM".equals(row.get("source_kind"))||id(row.get("care_item_id"))!=sourceId||row.get("care_appointment_id")!=null)throw denied();
            if(!"QUESTION".equals(row.get("request_kind")))throw denied();
            history.put(next,row);
            for(String field:Arrays.asList("originator_id","assigned_user_id","accepted_by","handled_by"))if(row.get(field)!=null)users.add(id(row.get(field)));
            next=row.get("previous_assistance_id")==null?null:id(row.get("previous_assistance_id"));
        }
        if(users.size()+history.size()>50)throw conflict("ADMISSION_LIMIT");
        HouseholdAuthorityLocks.LockedAuthority token=locks.lockPatientAndSubjects(patient,users);
        one("SELECT id FROM care_item WHERE id=? FOR UPDATE",sourceId);
        for(Long assistance:history.keySet())one("SELECT id FROM family_assistance WHERE id=? FOR UPDATE",assistance);
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();selected.put("care_item",Collections.singleton(sourceId));
        if(!history.isEmpty())selected.put("family_assistance",history.keySet());
        // The unchanged selected-row admission performs its own current structure/account checks.
        schema.requireSelectedRows(token,patient,actor,selected);
        if(!discovered.equals(source("CARE_ITEM",sourceId)))throw conflict("SOURCE_CHANGED");
        for(Map.Entry<Long,Map<String,Object>> entry:history.entrySet())
            if(!entry.getValue().equals(questionAdmissionIdentity(entry.getKey())))throw conflict("SOURCE_CHANGED");
        return token;
    }
    private Map<String,Object> questionAdmissionIdentity(long assistance) {
        // No historical evidence/body allocation during identity discovery. The unchanged selected-row audit verifies its hash.
        return one("SELECT id,patient_id,source_kind,care_item_id,care_appointment_id,request_kind,originator_id,assigned_user_id,accepted_by,handled_by,previous_assistance_id,state,lock_version FROM family_assistance WHERE id=?",assistance);
    }
    public void admitDue(long patient,long assistance,int cycle,Instant deadline,Instant now) {
        mutationTransaction(jdbc);
        schema.inspectStorageMetadata().requireStructurallyReady();
        if(one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw conflict("DUE_CHANGED");
        Map<String,Object> a=assistance(assistance);
        if(a==null||id(a.get("patient_id"))!=patient)throw conflict("DUE_CHANGED");
        SortedSet<Long> users=new TreeSet<>();
        for(String key:Arrays.asList("originator_id","assigned_user_id","accepted_by","handled_by"))if(a.get(key)!=null)users.add(id(a.get(key)));
        Map<String,Object> s=source((String)a.get("source_kind"),sourceId(a));
        if(s!=null&&s.get("doctor_user_id")!=null)users.add(id(s.get("doctor_user_id")));
        HouseholdAuthorityLocks.FamilyDueAuthority token=locks.lockFamilyDue(patient,assistance,cycle,deadline,users);
        String table="CARE_ITEM".equals(a.get("source_kind"))?"care_item":"care_appointment";
        one("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",sourceId(a));
        one("SELECT id FROM family_assistance WHERE id=? FOR UPDATE",assistance);
        schema.requireFamilyDueRows(token,patient,assistance,cycle,deadline,now);
    }
    public void admitDueNotification(long patient,long assistance,long event,long notification) {
        mutationTransaction(jdbc);
        schema.inspectStorageMetadata().requireStructurallyReady();
        if(one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw conflict("DUE_CHANGED");
        Map<String,Object> a=assistance(assistance),e=one("SELECT * FROM family_assistance_event WHERE id=?",event),n=one("SELECT * FROM family_assistance_notification WHERE id=?",notification);
        if(a==null||e==null||n==null||id(a.get("patient_id"))!=patient||id(e.get("assistance_id"))!=assistance||id(n.get("assistance_id"))!=assistance||id(n.get("event_id"))!=event||e.get("due_at")==null)throw conflict("NOTIFICATION_INVALID");
        SortedSet<Long> users=new TreeSet<>();
        for(String key:Arrays.asList("originator_id","assigned_user_id","accepted_by","handled_by"))if(a.get(key)!=null)users.add(id(a.get(key)));
        if(n.get("recipient_user_id")!=null)users.add(id(n.get("recipient_user_id")));
        Map<String,Object> source=source((String)a.get("source_kind"),sourceId(a));
        if(source==null)throw conflict("NOTIFICATION_INVALID");
        if(source.get("doctor_user_id")!=null)users.add(id(source.get("doctor_user_id")));
        Instant deadline=Instant.parse(iso(e.get("due_at")));
        int cycle=((Number)e.get("cycle")).intValue();
        HouseholdAuthorityLocks.FamilyDueAuthority token=locks.lockFamilyDueNotification(patient,assistance,cycle,deadline,event,notification,users);
        String table="CARE_ITEM".equals(a.get("source_kind"))?"care_item":"care_appointment";
        one("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",sourceId(a));
        one("SELECT id FROM family_assistance WHERE id=? FOR UPDATE",assistance);
        schema.requireFamilyDueNotificationRows(token,patient,assistance,cycle,deadline,event,notification);
    }
    public void requireIdentity(Map<String,Object> a,long patient,String kind,long source) {
        if(a==null||id(a.get("patient_id"))!=patient||!kind.equals(a.get("source_kind"))||sourceId(a)!=source||!hash((String)a.get("origin_evidence_json")).equals(a.get("origin_evidence_hash")))throw denied();
    }
    public static WorkflowException conflict(String code) {
        return new WorkflowException(409,code,"The assistance request changed. Refresh and review the current request.");
    }
    public static String iso(Object value) {
        return value==null?null:value instanceof Instant?value.toString():value instanceof Timestamp?((Timestamp)value).toInstant().toString():value.toString();
    }
    /** 原生 DATETIME 没有记录时区。直接保留 SQL 墙钟，不借 JVM 默认区或 UTC 伪造瞬间。 */
    private Map<String,Object> nativeSource(String sql,long source) {
        List<Map<String,Object>> rows = jdbc.query(sql,ps -> {
            ps.setQueryTimeout(5);
            ps.setLong(1,source);
        }
        ,(rs,n) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            ResultSetMetaData md = rs.getMetaData();
            for (int column=1; column<=md.getColumnCount(); column++) {
                String name = md.getColumnLabel(column).toLowerCase(Locale.ROOT);
                Object value;
                if (Arrays.asList("event_at","start_at","end_at").contains(name)) {
                    String wall = rs.getString(column);
                    value = wall == null ? null : LocalDateTime.parse(wall.replace(' ','T'));
                }
                else value = rs.getObject(column);
                row.put(name,value);
            }
            return row;
        }
        );
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> one(String sql,Object...args) {
        List<Map<String,Object>> rows=jdbc.query(sql,ps-> {
            ps.setQueryTimeout(5);
            bind(ps,args);
        }
        ,(rs,i)-> {
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int n=1; n<=md.getColumnCount(); n++) {
                Object value=rs.getObject(n);
                if(value instanceof Clob)value=rs.getString(n);
                if(value instanceof Timestamp)value=rs.getTimestamp(n,utc()).toInstant();
                row.put(md.getColumnLabel(n).toLowerCase(Locale.ROOT),value);
            }
            return row;
        }
        );
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<Map<String,Object>> rows(String sql,Object...args) {
        return jdbc.query(sql,ps-> {
            ps.setQueryTimeout(5);
            bind(ps,args);
        }
        ,(rs,i)-> {
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int n=1; n<=md.getColumnCount(); n++) {
                Object value=rs.getObject(n);
                if(value instanceof Clob)value=rs.getString(n);
                if(value instanceof Timestamp)value=rs.getTimestamp(n,utc()).toInstant();
                row.put(md.getColumnLabel(n).toLowerCase(Locale.ROOT),value);
            }
            return row;
        }
        );
    }
    public long insert(String sql,Object...args) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(c-> {
            PreparedStatement ps=c.prepareStatement(sql,new String[] {
                "id"
            }
            );
            bind(ps,args);
            return ps;
        }
        ,key);
        if(key.getKey()==null)throw new IllegalStateException("Missing generated identity");
        return key.getKey().longValue();
    }
    public int update(String sql,Object...args) {
        return jdbc.update(sql,ps->bind(ps,args));
    }
    public static void bind(PreparedStatement ps,Object[] args)throws SQLException {
        for(int n=0; n<args.length; n++)if(args[n] instanceof Instant)time(ps,n+1,(Instant)args[n]);
        else if(args[n] instanceof LocalDateTime)ps.setTimestamp(n+1,Timestamp.from(((LocalDateTime)args[n]).toInstant(ZoneOffset.UTC)),utc());
        else ps.setObject(n+1,args[n]);
    }
}
