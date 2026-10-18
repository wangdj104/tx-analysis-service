package org.familyhealthcare.service.emergencyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.emergencyassistance.EmergencyAssistanceAuthorizationService.denied;
@Repository public class EmergencyAssistanceRepository {
    final JdbcTemplate jdbc;
    final HouseholdAuthorityLocks locks;
    final HouseholdWorkflowSchema schema;
    final EmergencyAssistanceAuthorizationService auth;
    public EmergencyAssistanceRepository(JdbcTemplate jdbc,HouseholdAuthorityLocks locks,HouseholdWorkflowSchema schema,
    EmergencyAssistanceAuthorizationService auth){
        this.jdbc=jdbc;
        this.locks=locks;
        this.schema=schema;
        this.auth=auth;
    }
    public Map<String,Object> emergency(long id){
        return one("SELECT * FROM emergency_event WHERE id=?",id);
    }
    /** Indexed patient relations only. Entire command fails before writing when capacity is exceeded. */
    public SortedSet<Long> candidates(long patient){
        SortedSet<Long> out=new TreeSet<>();
        out.add(auth.owner(patient));
        for(String sql:Arrays.asList("SELECT DISTINCT user_id FROM care_member WHERE patient_id=? ORDER BY user_id LIMIT 41",
        "SELECT DISTINCT grantee_user_id FROM care_access_grant WHERE patient_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) ORDER BY grantee_user_id LIMIT 41",
        "SELECT DISTINCT doctor_user_id FROM doctor_patient_assignment WHERE patient_id=? AND status='ACTIVE' ORDER BY doctor_user_id LIMIT 41")){
            out.addAll(jdbc.queryForList(sql,Long.class,patient));
            if(out.size()>40)throw conflict("AUDIENCE_LIMIT");
        }
        return out;
    }
    /** patient父锁后先重读回执身份，再一次锁全相关账号；不从事件子锁返回账号锁。 */
    public HouseholdAuthorityLocks.LockedAuthority admit(long actor,long patient,Long target,Long extra,
    String key,Long event,Long notification,boolean discover){
        mutationTransaction(jdbc);
        schema.inspectStorageMetadata().requireStructurallyReady();
        if(one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw denied();
        Map<String,Object> replay=key==null?null:one("SELECT * FROM emergency_assistance_command WHERE actor_id=? AND command_key=?",
        actor,key);
        if(replay!=null){
            Map<String,Object>receipt=object((String)replay.get("result_json"));
            EmergencyAssistanceContracts.requireMinimalReceipt(receipt);
            target=id(receipt.get("emergencyEventId"));
            event=id(receipt.get("eventId"));
        }
        SortedSet<Long> users=discover&&replay==null?candidates(patient):new TreeSet<>();
        users.add(actor);
        if(extra!=null)users.add(extra);
        Map<String,Object>a=target==null?null:emergency(target);
        if(target!=null){
            if(a==null||id(a.get("patient_id"))!=patient)throw denied();
            List<Map<String,Object>> frozen=rows("SELECT recipient_user_id FROM emergency_assistance_recipient WHERE emergency_event_id=? ORDER BY recipient_user_id LIMIT 41",
            target);
            if(frozen.size()>40)throw conflict("AUDIENCE_LIMIT");
            for(Map<String,Object> r:frozen){
                users.add(id(r.get("recipient_user_id")));
            }
            if(users.size()>43)throw conflict("AUDIENCE_LIMIT");
            for(String k:Arrays.asList("requester_user_id","current_lead_id","pending_successor_id"))if(a.get(k)!=null)users.add(id(a.get(k)));
        }
        HouseholdAuthorityLocks.LockedAuthority token=locks.lockPatientAndSubjects(patient,users);
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();
        if(target!=null){
            one("SELECT id FROM emergency_event WHERE id=? FOR UPDATE",target);
            selected.put("emergency_event",Collections.singleton(target));
        }
        if(event!=null){
            one("SELECT id FROM emergency_assistance_event WHERE id=? FOR UPDATE",event);
            selected.put("emergency_assistance_event",Collections.singleton(event));
        }
        if(notification!=null){
            one("SELECT id FROM emergency_assistance_notification WHERE id=? FOR UPDATE",notification);
            selected.put("emergency_assistance_notification",Collections.singleton(notification));
        }
        if(!selected.isEmpty())schema.requireSelectedRows(token,patient,actor,selected);
        else locks.requireLive(token,patient,actor);
        return token;
    }
    /** 历史邀请须匹配真实人类事件；邀请本身不代替此刻EMERGENCY权限。 */
    public boolean invited(long event,long user){
        Map<String,Object> r=one("SELECT r.*,e.actor_id,e.type,e.actor_kind,e.occurred_at,e.evidence_json,e.evidence_hash,e.emergency_event_id AS invitation_target,e.patient_id FROM emergency_assistance_recipient r JOIN emergency_assistance_event e ON e.id=r.invitation_event_id WHERE r.emergency_event_id=? AND r.recipient_user_id=?",
        event,user);
        if(r==null)return false;
        Map<String,Object>a=emergency(event);
        Object frozen=object((String)r.get("evidence_json")).get("invitedUserIds");
        if(!"HUMAN".equals(r.get("actor_kind"))||!hash((String)r.get("evidence_json")).equals(r.get("evidence_hash"))||!Objects.equals(r.get("invited_at"),
        r.get("occurred_at"))||!(frozen instanceof List)||((List<?>)frozen).size()>40||!((List<?>)frozen).stream().anyMatch(u->id(u)==user)||a==null||id(r.get("invitation_target"))!=event||id(r.get("patient_id"))!=id(a.get("patient_id"))||id(r.get("invited_by"))!=id(r.get("actor_id"))||!Arrays.asList("CREATE_REQUEST",
        "HANDOFF").contains(r.get("type")))throw conflict("INVITATION_INVALID");
        return true;
    }
    public void inviteLocked(long emergencyEventId,long userId,long invitationEventId){
        mutationTransaction(jdbc);
        Map<String,Object>a=emergency(emergencyEventId),e=one("SELECT * FROM emergency_assistance_event WHERE id=?",
        invitationEventId);
        if(a==null||e==null||id(e.get("emergency_event_id"))!=emergencyEventId||id(e.get("patient_id"))!=id(a.get("patient_id"))||!"HUMAN".equals(e.get("actor_kind"))||userId==id(a.get("requester_user_id")))throw conflict("INVITATION_INVALID");
        Object frozen=object((String)e.get("evidence_json")).get("invitedUserIds");
        if(!(frozen instanceof java.util.List)||!((java.util.List<?>)frozen).stream().anyMatch(u->id(u)==userId))throw conflict("INVITATION_INVALID");
        auth.requireRead(userId,id(a.get("patient_id")));
        if(!invited(emergencyEventId,userId))insert("INSERT INTO emergency_assistance_recipient(emergency_event_id,recipient_user_id,invitation_event_id,invited_by,invited_at) VALUES(?,?,?,?,?)",
        emergencyEventId,userId,invitationEventId,e.get("actor_id"),e.get("occurred_at"));
    }
    public void requireIdentity(Map<String,Object>a,long patient){
        if(a==null||id(a.get("patient_id"))!=patient||id(a.get("workflow_version"))!=1||!hash((String)a.get("snapshot_json")).equals(a.get("snapshot_hash")))throw denied();
    }
    public static WorkflowException conflict(String code){
        return new WorkflowException(409,code,"The emergency request was not established or changed. Refresh and review; contact local emergency services if needed.");
    }
    public static String iso(Object v){
        return v==null?null:v instanceof Instant?v.toString():v instanceof Timestamp?((Timestamp)v).toInstant().toString():v.toString();
    }
    public Map<String,Object> one(String sql,Object...args) {
        List<Map<String,Object>> rows=jdbc.query(sql,ps-> {
            org.springframework.jdbc.datasource.DataSourceUtils.applyTimeout(ps,jdbc.getDataSource(),
            5);
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
            org.springframework.jdbc.datasource.DataSourceUtils.applyTimeout(ps,jdbc.getDataSource(),
            5);
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
        else if(args[n] instanceof LocalDateTime)ps.setTimestamp(n+1,Timestamp.from(((LocalDateTime)args[n]).toInstant(ZoneOffset.UTC)),
        utc());
        else ps.setObject(n+1,args[n]);
    }
}
