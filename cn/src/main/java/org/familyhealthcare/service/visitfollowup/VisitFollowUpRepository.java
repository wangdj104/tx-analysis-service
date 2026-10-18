package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.familyassistance.FamilyAssistanceQueryService;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;

/** 固定来源和有界 SQL。没有任意表名入口，也不把原生墙钟误写成 UTC。 */
@Repository
public class VisitFollowUpRepository {
    static final int ADMISSION_ROWS=50;
    static final List<String> STATE_FIELDS=Collections.unmodifiableList(Arrays.asList(
    "source_revision","source_fingerprint","native_status","native_start_wall","native_end_wall","native_notify_wall","native_doctor_id","native_schedule_id","last_native_event_id",
    "attendance","attendance_origin","actual_time_kind","actual_at_utc","actual_offset","current_fact_event_id","next_decision","responsible_user_id","decision_due_at",
    "successor_kind","successor_care_item_id","successor_care_appointment_id","successor_bound_version","successor_bound_fingerprint",
    "reminder_attempt_source_revision","reminder_attempt_id","reminder_attempted_at_utc","disposition","disposition_revision","decision_event_id","updated_at"));
    private static final Set<String> VISIT_FIELDS=new HashSet<>(Arrays.asList("source_revision","source_fingerprint","disposition","disposition_revision","decision_event_id"));
    private static final Set<String> WALL_FIELDS=new HashSet<>(Arrays.asList("event_at","start_at","end_at","notify_at","visited_at","published_at","follow_up_at","next_follow_up_at","native_start_wall","native_end_wall","native_notify_wall"));
    private static final DateTimeFormatter MICROS=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private final JdbcTemplate jdbc;
    private final Object ownershipIdentity=new Object();
    JdbcTemplate jdbcTemplate() { return jdbc; }
    Object ownershipIdentity() { return ownershipIdentity; }
    public VisitFollowUpRepository(JdbcTemplate jdbc) {
        this.jdbc=Objects.requireNonNull(jdbc);
    }
    public static final class NativeSource {
        private final SourceRef reference;
        private final long patient;
        private final Map<String,Object> values;
        private final String fingerprint;
        private final boolean ordinary;
        private final LocalDateTime notifiedWall;
        private NativeSource(SourceRef reference,Map<String,Object> row) { this(reference,row,true); }
        private NativeSource(SourceRef reference,Map<String,Object> row,boolean ordinary) {
            Object reminderWall=row.remove("reminder_native_notified_wall");
            this.notifiedWall=reminderWall==null?null:LocalDateTime.parse(String.valueOf(reminderWall).replace(' ','T'));
            this.ordinary=ordinary;
            this.reference=reference;
            this.patient=number(row.get("patient_id"));
            this.values=Collections.unmodifiableMap(new LinkedHashMap<>(row));
            Map<String,Object> factual=new LinkedHashMap<>(row);
            factual.remove("family_workflow_version");
            factual.remove("family_assistance_id");
            this.fingerprint=WorkflowData.hash(canonical(factual));
        }
        public LocalDateTime getNotifiedWall() { return notifiedWall; }
        public boolean isOrdinary() { return ordinary; }
        public SourceRef getReference() {
            return reference;
        }
        public long getPatientId() {
            return patient;
        }
        public String getFingerprint() {
            return fingerprint;
        }
        public Map<String,Object> getValues() {
            return values;
        }
        public Long getDoctorId() {
            return nullable(values.get("doctor_user_id"));
        }
        public Map<String,Object> nativeTarget() {
            return WorkflowData.map("native_status",values.get("status"),"native_start_wall",values.get(reference.getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"event_at":reference.getKind()==SourceKind.VISIT_RECORD?"visited_at":"start_at"),"native_end_wall",values.get("end_at"),"native_notify_wall",values.get("notify_at"),"native_doctor_id",values.get("doctor_user_id"),"native_schedule_id",values.get("schedule_id"));
        }
    }
    /** Predict only the six admitted native scheduling columns; clinical/configuration bytes remain unchanged. */
    NativeSource appointmentTarget(VisitFollowUpAdmission.Context context, SourceRef source, Map<String,Object> target) {
        context.requireAppointmentWrite(source);
        requireWriteScope(context.scope());
        if (!source.getKind().isAppointment()) throw integrity();
        NativeSource current = source(source);
        Map<String,Object> row = new LinkedHashMap<>(current.values);
        Map<String,String> columns = new LinkedHashMap<>();
        columns.put("native_status", "status");
        columns.put("native_start_wall", source.getKind() == SourceKind.CARE_ITEM_APPOINTMENT ? "event_at" : "start_at");
        if (source.getKind() == SourceKind.CARE_ITEM_APPOINTMENT) columns.put("native_notify_wall", "notify_at");
        else { columns.put("native_end_wall", "end_at"); columns.put("native_doctor_id", "doctor_user_id"); columns.put("native_schedule_id", "schedule_id"); }
        if (!current.nativeTarget().keySet().equals(target.keySet())) throw integrity();
        columns.forEach((field,column) -> row.put(column,target.get(field)));
        return new NativeSource(source,row);
    }
    /** Only the already persisted event/state target can authorize this fixed native update. */
    void writeAppointmentTarget(VisitFollowUpAdmission.Context context, SourceRef source, boolean clearReminder) {
        context.requireAppointmentWrite(source);
        requireWriteScope(context.scope());
        Map<String,Object> state = state(source);
        if (state == null || !context.scope().completed.containsKey(id(state.get("id")))) throw integrity();
        VisitFollowUpAdmission.PendingEvent event = context.scope().pending(id(state.get("id")),id(state.get("last_native_event_id")));
        if (event.version != number(state.get("lock_version"))) throw integrity();
        int changed;
        if (source.getKind() == SourceKind.CARE_ITEM_APPOINTMENT)
            changed = update("UPDATE care_item SET status=?,event_at=?,notify_at=?" + (clearReminder ? ",notified_at=?,escalated_at=?" : "") + " WHERE id=? AND patient_id=?",
                    nativeWriteArguments(state,source,clearReminder,true));
        else if (source.getKind() == SourceKind.CARE_APPOINTMENT)
            changed = update("UPDATE care_appointment SET status=?,start_at=?,end_at=?,doctor_user_id=?,schedule_id=?" + (clearReminder ? ",notified_at=?" : "") + " WHERE id=? AND patient_id=?",
                    nativeWriteArguments(state,source,clearReminder,false));
        else throw integrity();
        if (changed != 1) throw VisitFollowUpAuthorizationService.changed();
    }
    private Object[] nativeWriteArguments(Map<String,Object> state,SourceRef source,boolean clear,boolean legacy) {
        List<Object> args = new ArrayList<>(Arrays.asList(state.get("native_status"),state.get("native_start_wall")));
        if (legacy) args.add(state.get("native_notify_wall"));
        else args.addAll(Arrays.asList(state.get("native_end_wall"),state.get("native_doctor_id"),state.get("native_schedule_id")));
        if (clear) { args.add(null); if (legacy) args.add(null); }
        args.add(source.getId()); args.add(state.get("patient_id")); return args.toArray();
    }
    /** 发布只改变已入场的确切visit状态/发布时间，不改原摘要、预约关系或实际时间。 */
    NativeSource publishedVisitTarget(VisitFollowUpAdmission.Context context,LocalDateTime published) {
        SourceRef ref=context.scope().command.getSource();context.requireAppointmentWrite(ref);requireWriteScope(context.scope());
        if(context.scope().command.getOperation()!=Operation.PUBLISH_VISIT||ref.getKind()!=SourceKind.VISIT_RECORD
                ||published==null||published.getNano()!=0)throw integrity();
        NativeSource current=source(ref);if(!"DRAFT".equals(current.values.get("status")))throw VisitFollowUpAuthorizationService.changed();
        Map<String,Object> row=new LinkedHashMap<>(current.values);row.put("status","PUBLISHED");row.put("published_at",published);
        return new NativeSource(ref,row);
    }
    void writePublishedVisit(VisitFollowUpAdmission.Context context,LocalDateTime published) {
        SourceRef ref=context.scope().command.getSource();NativeSource expected=publishedVisitTarget(context,published);
        Map<String,Object> state=state(ref);if(state==null||!context.scope().completed.containsKey(id(state.get("id")))
                ||!expected.fingerprint.equals(state.get("source_fingerprint")))throw integrity();
        VisitFollowUpAdmission.PendingEvent event=context.scope().pending(id(state.get("id")),id(state.get("last_native_event_id")));
        if(event.version!=number(state.get("lock_version")))throw integrity();
        if(update("UPDATE visit_record SET status=?,published_at=? WHERE id=? AND patient_id=? AND status='DRAFT'",
                "PUBLISHED",published,ref.getId(),context.getPatientId())!=1)throw VisitFollowUpAuthorizationService.changed();
    }
    /** 内部摘要仅带字节摘要；临床正文不会进入命令回执或资格发现。 */
    public NativeSource source(SourceRef source) {
        String sql=sourceSql(source.getKind());
        Map<String,Object> row=one(sql,source.getId());
        if(row==null)throw VisitFollowUpAuthorizationService.denied();
        if(number(row.get("text_bytes"))>65536)throw new WorkflowException(413,"SOURCE_TOO_LARGE","This source exceeds the supported verification size.");
        if(source.getKind()==SourceKind.CARE_ITEM_APPOINTMENT&&(!"APPOINTMENT".equals(row.get("kind"))||number(row.get("medication_workflow_version"))!=0||row.get("medication_execution_id")!=null))throw VisitFollowUpAuthorizationService.denied();
        row.remove("text_bytes");
        return new NativeSource(source,row);
    }
    private String sourceSql(SourceKind kind) {
        String sql;
        if(kind==SourceKind.CARE_ITEM_APPOINTMENT)sql="SELECT id,patient_id,user_id,kind,status,assigned_user_id,actor_id,event_at,notify_at,family_workflow_version,family_assistance_id,medication_workflow_version,medication_execution_id,COALESCE(OCTET_LENGTH(title),0)+COALESCE(OCTET_LENGTH(data_json),0) AS text_bytes,CASE WHEN COALESCE(OCTET_LENGTH(title),0)+COALESCE(OCTET_LENGTH(data_json),0)<=65536 THEN SHA2(title,256) ELSE NULL END AS title_hash,CASE WHEN COALESCE(OCTET_LENGTH(title),0)+COALESCE(OCTET_LENGTH(data_json),0)<=65536 THEN SHA2(data_json,256) ELSE NULL END AS data_hash FROM care_item WHERE id=?";
        else if(kind==SourceKind.CARE_APPOINTMENT)sql="SELECT id,patient_id,doctor_user_id,schedule_id,start_at,end_at,status,appointment_type,consultation_mode,recurrence_days,next_follow_up_at,family_workflow_version,family_assistance_id,COALESCE(OCTET_LENGTH(reason),0)+COALESCE(OCTET_LENGTH(cancel_reason),0) AS text_bytes,CASE WHEN COALESCE(OCTET_LENGTH(reason),0)+COALESCE(OCTET_LENGTH(cancel_reason),0)<=65536 THEN SHA2(reason,256) ELSE NULL END AS reason_hash,CASE WHEN COALESCE(OCTET_LENGTH(reason),0)+COALESCE(OCTET_LENGTH(cancel_reason),0)<=65536 THEN SHA2(cancel_reason,256) ELSE NULL END AS cancel_reason_hash FROM care_appointment WHERE id=?";
        else sql="SELECT id,patient_id,doctor_user_id,appointment_id,status,visited_at,published_at,follow_up_at,COALESCE(OCTET_LENGTH(diagnosis_summary),0)+COALESCE(OCTET_LENGTH(treatment_summary),0)+COALESCE(OCTET_LENGTH(follow_up_advice),0) AS text_bytes,CASE WHEN COALESCE(OCTET_LENGTH(diagnosis_summary),0)+COALESCE(OCTET_LENGTH(treatment_summary),0)+COALESCE(OCTET_LENGTH(follow_up_advice),0)<=65536 THEN SHA2(diagnosis_summary,256) ELSE NULL END AS diagnosis_hash,CASE WHEN COALESCE(OCTET_LENGTH(diagnosis_summary),0)+COALESCE(OCTET_LENGTH(treatment_summary),0)+COALESCE(OCTET_LENGTH(follow_up_advice),0)<=65536 THEN SHA2(treatment_summary,256) ELSE NULL END AS treatment_hash,CASE WHEN COALESCE(OCTET_LENGTH(diagnosis_summary),0)+COALESCE(OCTET_LENGTH(treatment_summary),0)+COALESCE(OCTET_LENGTH(follow_up_advice),0)<=65536 THEN SHA2(follow_up_advice,256) ELSE NULL END AS advice_hash FROM visit_record WHERE id=?";
        return sql;
    }
    /** 摘要及其当前决定一次固定批量读；文本先检查字节上限，指纹复用原生同一列集合。 */
    static final class VisitReadSource {
        final NativeSource source;final Map<String,Object> state,body,decision,origin;
        VisitReadSource(NativeSource source,Map<String,Object> state,Map<String,Object> body,Map<String,Object> decision,Map<String,Object> origin){this.source=source;this.state=state;this.body=body;this.decision=decision;this.origin=origin;}
        String signature(){return canonical(WorkflowData.map("source",source.getFingerprint(),"state",state,"decision",decision,"origin",origin));}
    }
    Map<Long,VisitReadSource> visitReadSources(long patient,Collection<Long> input,boolean body) {
        SortedSet<Long> ids=new TreeSet<>(input);if(ids.size()>26)throw VisitFollowUpAuthorizationService.limit();if(ids.isEmpty())return Collections.emptyMap();
        List<String> fields=new ArrayList<>(Arrays.asList("id","patient_id","source_kind","care_item_id","care_appointment_id","visit_record_id","origin_appointment_state_id","lock_version","created_by","created_at"));fields.addAll(STATE_FIELDS);
        StringBuilder columns=new StringBuilder("n.*");for(String field:fields)columns.append(",").append(WALL_FIELDS.contains(field)?"CAST(s."+field+" AS CHAR)":"s."+field).append(" AS state_").append(field);
        String decisionBytes="OCTET_LENGTH(COALESCE(e.actor_name,''))+OCTET_LENGTH(COALESCE(e.actor_relation,''))+OCTET_LENGTH(COALESCE(e.reason,''))";
        columns.append(",e.id AS decision_id,e.state_id AS decision_state,e.patient_id AS decision_patient,e.state_version AS decision_version,e.event_type AS decision_type,e.actor_id AS decision_actor,e.actor_relation AS decision_relation,e.recorded_at AS decision_recorded_at,e.after_source_fingerprint AS decision_fingerprint,").append(decisionBytes).append(" AS decision_text_bytes,CASE WHEN ").append(decisionBytes).append("<=65536 THEN e.actor_name ELSE NULL END AS decision_actor_name,CASE WHEN ").append(decisionBytes).append("<=65536 THEN e.reason ELSE NULL END AS decision_reason");
        columns.append(",o.source_kind AS origin_kind,COALESCE(o.care_item_id,o.care_appointment_id) AS origin_id");
        if(body)for(String field:Arrays.asList("diagnosis_summary","treatment_summary","follow_up_advice"))columns.append(",CASE WHEN n.text_bytes<=65536 THEN b.").append(field).append(" ELSE NULL END AS body_").append(field);
        String nativeSql=sourceSql(SourceKind.VISIT_RECORD).replace(" WHERE id=?"," WHERE patient_id=? AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")");
        List<Object> args=new ArrayList<>();args.add(patient);args.addAll(ids);
        String sql="SELECT "+columns+" FROM ("+nativeSql+") n JOIN visit_record b ON b.id=n.id LEFT JOIN visit_followup_state s ON s.visit_record_id=n.id AND s.patient_id=n.patient_id AND s.source_kind='VISIT_RECORD' LEFT JOIN visit_followup_event e ON e.id=s.decision_event_id AND e.state_id=s.id AND e.patient_id=n.patient_id LEFT JOIN visit_followup_state o ON o.id=s.origin_appointment_state_id AND o.patient_id=n.patient_id ORDER BY n.id";
        Map<Long,VisitReadSource> result=new LinkedHashMap<>();
        for(Map<String,Object> row:rows(sql,26,args.toArray())) {
            if(number(row.remove("text_bytes"))>NATIVE_TEXT_BYTES||number(row.remove("decision_text_bytes"))>NATIVE_TEXT_BYTES)throw VisitFollowUpContracts.limit();
            Map<String,Object> state=new LinkedHashMap<>(),text=new LinkedHashMap<>(),decision=new LinkedHashMap<>(),origin=new LinkedHashMap<>();
            for(String key:new ArrayList<>(row.keySet())) {
                if(key.startsWith("state_")){String field=key.substring(6);Object value=row.remove(key);if(value!=null&&WALL_FIELDS.contains(field))value=LocalDateTime.parse(String.valueOf(value).replace(' ','T'));state.put(field,value);}
                else if(key.startsWith("body_"))text.put(key.substring(5),row.remove(key));
                else if(key.startsWith("decision_"))decision.put(key.substring(9),row.remove(key));
                else if(key.startsWith("origin_"))origin.put(key.substring(7),row.remove(key));
            }
            if(origin.get("id")==null&&row.get("appointment_id")!=null)origin=WorkflowData.map("kind","CARE_APPOINTMENT","id",row.get("appointment_id"));
            long source=id(row.get("id"));result.put(source,new VisitReadSource(new NativeSource(new SourceRef(SourceKind.VISIT_RECORD,source),row),state.get("id")==null?null:state,text,decision.get("id")==null?null:decision,origin.get("id")==null?null:origin));
        }
        return result;
    }
    /** Two fixed native batches per read pass. Restricted legacy content is inspected, never projected. */
    Map<SourceRef,NativeSource> appointmentSources(Collection<SourceRef> references) {
        if(references.size()>50)throw VisitFollowUpAuthorizationService.limit();
        Map<SourceRef,NativeSource> result=new LinkedHashMap<>();
        for(SourceKind kind:Arrays.asList(SourceKind.CARE_ITEM_APPOINTMENT,SourceKind.CARE_APPOINTMENT)) {
            List<Long> ids=new ArrayList<>();for(SourceRef reference:references)if(reference.getKind()==kind)ids.add(reference.getId());
            if(ids.isEmpty())continue;
            String sql=sourceSql(kind).replace(" WHERE id=?"," WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id LIMIT 51");
            // 提醒标记只作只读投影，不进入原生排程/临床来源指纹。
            sql=sql.replace(" FROM "+kind.tableName(),",CAST(notified_at AS CHAR) AS reminder_native_notified_wall FROM "+kind.tableName());
            if(kind==SourceKind.CARE_ITEM_APPOINTMENT)sql=sql.replace(" FROM care_item",",CASE WHEN COALESCE(OCTET_LENGTH(title),0)+COALESCE(OCTET_LENGTH(data_json),0)<=65536 THEN data_json ELSE NULL END AS restricted_payload FROM care_item");
            for(Map<String,Object> row:rows(sql,50,ids.toArray())) {
                if(number(row.get("text_bytes"))>65536)throw new WorkflowException(413,"SOURCE_TOO_LARGE","This source exceeds the supported verification size.");
                row.remove("text_bytes");Object payload=row.remove("restricted_payload");boolean ordinary=true;
                if(kind==SourceKind.CARE_ITEM_APPOINTMENT) {
                    ordinary="APPOINTMENT".equals(row.get("kind"))&&number(row.get("medication_workflow_version"))==0&&row.get("medication_execution_id")==null;
                    try { if(payload!=null&&FamilyAssistanceQueryService.restricted(WorkflowData.object(String.valueOf(payload))))ordinary=false; }
                    catch(RuntimeException invalid){ordinary=false;}
                }
                SourceRef reference=new SourceRef(kind,id(row.get("id")));result.put(reference,new NativeSource(reference,row,ordinary));
            }
        }
        return result;
    }
    Map<SourceRef,Map<String,Object>> appointmentStates(long patient,Collection<SourceRef> references) {
        if(references.size()>50)throw VisitFollowUpAuthorizationService.limit();
        List<String> terms=new ArrayList<>();List<Object> args=new ArrayList<>();args.add(patient);
        for(SourceKind kind:Arrays.asList(SourceKind.CARE_ITEM_APPOINTMENT,SourceKind.CARE_APPOINTMENT)) {
            List<Long> ids=new ArrayList<>();for(SourceRef reference:references)if(reference.getKind()==kind)ids.add(reference.getId());
            if(ids.isEmpty())continue;terms.add("(source_kind='"+kind.name()+"' AND "+kind.idColumn()+" IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+"))");args.addAll(ids);
        }
        Map<SourceRef,Map<String,Object>> result=new LinkedHashMap<>();if(terms.isEmpty())return result;
        for(Map<String,Object> row:rows("SELECT * FROM visit_followup_state WHERE patient_id=? AND ("+String.join(" OR ",terms)+") ORDER BY id LIMIT 51",50,args.toArray()))result.put(reference(row),row);
        return result;
    }
    Map<Long,Map<String,Object>> appointmentFacts(Collection<Long> ids) {
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();if(ids.isEmpty())return result;if(ids.size()>50)throw VisitFollowUpAuthorizationService.limit();
        for(Map<String,Object> row:rows("SELECT id,state_id,patient_id,state_version,event_type,actor_id,actor_name,actor_relation,occurred_at_utc,recorded_at FROM visit_followup_event WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id LIMIT 51",50,ids.toArray()))result.put(id(row.get("id")),row);
        return result;
    }
    void requireOrdinaryLegacySource(SourceRef source) {
        if(source.getKind()!=SourceKind.CARE_ITEM_APPOINTMENT)return;
        Map<String,Object> row=one("SELECT OCTET_LENGTH(data_json) AS bytes,CASE WHEN COALESCE(OCTET_LENGTH(data_json),0)<=65536 THEN data_json ELSE NULL END AS data_json FROM care_item WHERE id=?",source.getId());
        if(row==null||number(row.get("bytes"))>65536)throw VisitFollowUpAuthorizationService.denied();
        Object raw=row.get("data_json");
        try {
            if(raw!=null&&FamilyAssistanceQueryService.restricted(WorkflowData.object(String.valueOf(raw))))throw VisitFollowUpAuthorizationService.denied();
        }catch(IllegalStateException invalid) {
            throw VisitFollowUpAuthorizationService.denied();
        }
    }
    Map<String,Object> state(SourceRef source) {
        return one("SELECT * FROM visit_followup_state WHERE source_kind=? AND "+source.getKind().idColumn()+"=?",source.getKind().name(),source.getId());
    }
    Map<String,Object> state(long state) {
        return one("SELECT * FROM visit_followup_state WHERE id=?",state);
    }
    List<Map<String,Object>> predecessor(SourceRef source) {
        String column=source.getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"successor_care_item_id":"successor_care_appointment_id";
        if(source.getKind()==SourceKind.VISIT_RECORD)return Collections.emptyList();
        return rows("SELECT * FROM visit_followup_state WHERE successor_kind=? AND "+column+"=? LIMIT 2",1,source.getKind().name(),source.getId());
    }
    List<Map<String,Object>> links(long state,long revision) {
        return rows("SELECT * FROM visit_followup_action_link WHERE state_id=? AND disposition_revision=? ORDER BY id LIMIT 11",10,state,revision);
    }
    Map<String,Object> command(long actor,String key,boolean lock) {
        return one("SELECT actor_id,command_key,patient_id,state_id,expected_version,operation,payload_hash,OCTET_LENGTH(result_json) AS receipt_bytes,CASE WHEN OCTET_LENGTH(result_json)<=512 THEN result_json ELSE NULL END AS result_json FROM visit_followup_command WHERE actor_id=? AND command_key=?"+(lock?" FOR UPDATE":""),actor,key);
    }
    Map<String,Object> event(long id) {
        return one("SELECT id,state_id,patient_id,state_version,event_type,actor_id,command_key,after_source_fingerprint,native_status,native_start_wall,native_end_wall,native_notify_wall,native_doctor_id,native_schedule_id FROM visit_followup_event WHERE id=?",id);
    }
    private Map<String,Object> eventAfter(long eventId) {
        Map<String,Object> row=one("SELECT OCTET_LENGTH(payload_json) AS payload_bytes,CASE WHEN OCTET_LENGTH(payload_json)<=16384 THEN JSON_EXTRACT(payload_json,'$.after') ELSE NULL END AS after_json FROM visit_followup_event WHERE id=?",eventId);
        if(row==null||number(row.get("payload_bytes"))>16384||row.get("after_json")==null)throw integrity();
        try {
            return VisitFollowUpContracts.parseObject(String.valueOf(row.get("after_json")),16384);
        }
        catch(RuntimeException invalid) {
            throw integrity();
        }
    }
    /** 回放按当时事件中的后继返回；当前关系已更换也不能重写真实旧回执。 */
    void requireReceiptSuccessor(VisitFollowUpAdmission.Scope scope,Map<String,Object> receipt,boolean current) {
        if(receipt.get("successorId")==null)return;
        SourceRef successor=new SourceRef(SourceKind.valueOf(String.valueOf(receipt.get("successorKind"))),id(receipt.get("successorId")));
        Map<String,Object> after=eventAfter(id(receipt.get("eventId")));
        if(!scope.sources.contains(successor)||!successor.getKind().name().equals(after.get("successor_kind"))
        ||successor.getId()!=number(after.get(successor.getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"successor_care_item_id":"successor_care_appointment_id")))throw integrity();
        if(current) {
            Map<String,Object> primary=state(id(receipt.get("workflowId"))),target=state(successor);
            NativeSource nativeTarget=source(successor);
            if(primary==null||target==null||nativeTarget.patient!=scope.command.getPatientId()
            ||!successor.getKind().name().equals(primary.get("successor_kind"))
            ||successor.getId()!=number(primary.get(successor.getKind()==SourceKind.CARE_ITEM_APPOINTMENT?"successor_care_item_id":"successor_care_appointment_id"))
            ||number(primary.get("successor_bound_version"))!=number(target.get("lock_version"))
            ||!nativeTarget.fingerprint.equals(primary.get("successor_bound_fingerprint"))
            ||!nativeTarget.fingerprint.equals(target.get("source_fingerprint")))throw integrity();
        }
    }
    Map<String,Object> plan(long id) {
        return one("SELECT id,patient_id,workflow_version,current_revision_id,lifecycle FROM doctor_care_plan WHERE id=?",id);
    }
    Map<String,Object> revision(long id) {
        return one("SELECT id,plan_id,status,plan_type FROM care_plan_revision WHERE id=?",id);
    }
    Map<String,Object> action(long id) {
        return one("SELECT id,plan_id,revision_id,patient_id,assigned_user_id,status,lock_version,due_at FROM care_plan_action WHERE id=?",id);
    }
    long actionWatermark(long action) {
        Map<String,Object> row=one("SELECT MAX(id) AS event_id FROM care_plan_event WHERE action_id=?",action);
        return number(row.get("event_id"));
    }
    /** 只能由本事务的固定 INSERT 产生，不能拿一个现成 ID 冒充新建来源。 */
    public static final class CreatedSource {
        private final VisitFollowUpAdmission.Scope scope;
        private final NativeSource source;
        private final Map<String,Object> initial;
        private boolean consumed;
        private long state;
        private CreatedSource(VisitFollowUpAdmission.Scope scope,NativeSource source,Map<String,Object> initial) {
            this.scope=scope;
            this.source=source;
            this.initial=Collections.unmodifiableMap(new LinkedHashMap<>(initial));
        }
        public SourceRef getReference() {
            scope.requireOpen();
            return source.reference;
        }
        boolean permits(VisitFollowUpAdmission.Scope current,SourceRef reference) {
            return scope==current&&consumed&&state>0&&source.reference.equals(reference);
        }
    }
    /** 仅落命令明确要求的新原生行；排期冲突和临床流程仍由后续业务服务核实。 */
    public CreatedSource insertCreatedSource(VisitFollowUpAdmission.Context context) {
        VisitFollowUpAdmission.Scope scope=Objects.requireNonNull(context).scope();
        requireWriteScope(scope);
        if(scope.binding||!scope.requiresCreation()||scope.creationAttempted)throw integrity();
        scope.requireCurrent();
        scope.creationAttempted=true;
        Map<String,Object> body=scope.command.getBody(),initial=new LinkedHashMap<>();
        VisitFollowUpAuthorizationService.Snapshot authority=scope.currentAuthority();
        SourceKind kind=scope.command.getSourceKind();
        long nativeId;
        if(kind==SourceKind.VISIT_RECORD) {
            ActualTime actual=actual(commandObject(body.get("actual")),Clock.fixed(scope.now(),ZoneOffset.UTC));
            if(actual.getTimeKind()!=TimeKind.KNOWN)throw integrity();
            Long appointment=null;
            Map<String,Object> origin=commandObject(body.get("originAppointment"));
            if(origin!=null) {
                SourceRef reference=new SourceRef(SourceKind.valueOf(String.valueOf(origin.get("sourceKind"))),id(origin.get("sourceId")));
                Map<String,Object> prior=state(reference);
                if(!scope.sources.contains(reference)||prior==null||!scope.contains("visit_followup_state",number(prior.get("id"))))throw integrity();
                NativeSource nativeOrigin=source(reference);
                if(nativeOrigin.patient!=scope.command.getPatientId()||number(prior.get("lock_version"))!=number(origin.get("expectedVersion"))
                ||!nativeOrigin.fingerprint.equals(origin.get("expectedSourceFingerprint")))throw VisitFollowUpAuthorizationService.changed();
                initial.put("origin_appointment_state_id",id(prior.get("id")));
                if(reference.getKind()==SourceKind.CARE_APPOINTMENT)appointment=reference.getId();
            }
            // 旧 DATETIME(0) 只存原输入墙钟的秒值；真实 UTC 微秒和 offset 由事件/状态另存。
            LocalDateTime nativeWall=actual.getNativeWallTime().withNano(0);
            nativeId=insertGenerated("INSERT INTO visit_record(appointment_id,patient_id,doctor_user_id,visited_at,diagnosis_summary,treatment_summary,follow_up_advice,follow_up_at,status) VALUES(?,?,?,?,?,?,?,?,?)",
                appointment,scope.command.getPatientId(),scope.actor,nativeWall,body.get("diagnosisSummary"),body.get("treatmentSummary"),body.get("followUpAdvice"),optionalWall(body.get("followUpAt")),"DRAFT");
        }else {
            Map<String,Object> schedule=commandObject(body.get("schedule"));
            long responsible=id(body.get("responsibleUserId"));
            if(!scope.subjects.contains(responsible)||!authority.canAppointmentWrite(responsible))throw VisitFollowUpAuthorizationService.denied();
            initial.put("responsible_user_id",responsible);
            initial.put("decision_due_at",Instant.parse(String.valueOf(body.get("dueAt"))));
            if(kind==SourceKind.CARE_ITEM_APPOINTMENT) {
                long owner=authority.getOwnerId();
                if(!scope.subjects.contains(owner))throw integrity();
                nativeId=insertGenerated("INSERT INTO care_item(patient_id,user_id,kind,title,status,assigned_user_id,actor_id,actor_name,event_at,notify_at,data_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    scope.command.getPatientId(),owner,"APPOINTMENT","Follow-up appointment","OPEN",responsible,scope.actor,authority.actorName(scope.actor),wallTime(schedule.get("eventAt")),optionalWall(schedule.get("notifyAt")),"{}");
            }else if(kind==SourceKind.CARE_APPOINTMENT) {
                long doctor=id(schedule.get("doctorUserId"));
                if(!scope.subjects.contains(doctor)||!authority.isActiveDoctor(doctor))throw VisitFollowUpAuthorizationService.denied();
                nativeId=insertGenerated("INSERT INTO care_appointment(patient_id,doctor_user_id,schedule_id,appointment_type,consultation_mode,start_at,end_at,status,reason,recurrence_days,next_follow_up_at,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    scope.command.getPatientId(),doctor,schedule.get("scheduleId"),schedule.getOrDefault("appointmentType","FOLLOW_UP"),schedule.getOrDefault("consultationMode","IN_PERSON"),wallTime(schedule.get("startAt")),wallTime(schedule.get("endAt")),"BOOKED",body.get("reason"),0,null,scope.actor);
            }else throw integrity();
        }
        SourceRef reference=new SourceRef(kind,nativeId);
        if(scope.sources.contains(reference)||scope.contains(kind.tableName(),nativeId))throw integrity();
        NativeSource persisted=source(reference);
        if(persisted.patient!=scope.command.getPatientId())throw integrity();
        CreatedSource proof=new CreatedSource(scope,persisted,initial);
        scope.creation=proof;
        // 新插入行单独记账，不冒充预先选定的既存行，也不追加任何旧行锁。
        scope.sources.add(reference);
        return proof;
    }
    /** 凭证只能消费一次；失败后提交门仍会发现未闭合的新来源并整体回滚。 */
    public long insertCreatedState(VisitFollowUpAdmission.Context context,CreatedSource proof) {
        VisitFollowUpAdmission.Scope scope=Objects.requireNonNull(context).scope();
        requireWriteScope(scope);
        if(proof==null||proof.scope!=scope||scope.creation!=proof||proof.consumed)throw integrity();
        proof.consumed=true;
        proof.state=insertSkeleton(scope,proof.source.reference,proof.initial);
        return proof.state;
    }
    /** Context 绑定的是创建它的仓库及事务连接，不能拿到另一个写入实例上使用。 */
    private void requireWriteScope(VisitFollowUpAdmission.Scope scope) {
        scope.requireOpen();
        if(scope.repositoryOwner!=ownershipIdentity)throw integrity();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> commandObject(Object value) {
        return value==null?null:(Map<String,Object>)value;
    }
    private static LocalDateTime optionalWall(Object value) {
        return value==null?null:wallTime(value);
    }
    private long insertGenerated(String sql,Object...values) {
        GeneratedKeyHolder keys=new GeneratedKeyHolder();
        int changed=jdbc.update(connection-> {
            PreparedStatement ps=connection.prepareStatement(sql,new String[]{"id"});
            ps.setQueryTimeout(5);
            bind(ps,values);
            return ps;
        },keys);
        if(changed!=1||keys.getKey()==null)throw integrity();
        return id(keys.getKey());
    }
    private void requireCreationCompleted(VisitFollowUpAdmission.Scope scope,Map<String,Object> receipt) {
        if(!scope.requiresCreation()) {
            if(scope.creationAttempted||scope.creation!=null)throw integrity();
            return;
        }
        CreatedSource proof=scope.creation;
        if(!scope.creationAttempted||proof==null||proof.scope!=scope||!proof.consumed||proof.state<1||receipt==null
        ||!scope.completed.containsKey(proof.state)||!proof.source.reference.equals(scope.touchedStates.get(proof.state)))throw integrity();
        NativeSource persisted=source(proof.source.reference);
        if(!proof.source.fingerprint.equals(persisted.fingerprint))throw integrity();
        if(scope.command.getOperation()==Operation.CREATE_VISIT) {
            if(number(receipt.get("workflowId"))!=proof.state||number(receipt.get("sourceId"))!=proof.source.reference.getId())throw integrity();
            ActualTime actual=actual(commandObject(scope.command.getBody().get("actual")),Clock.fixed(scope.now(),ZoneOffset.UTC));
            Map<String,Object> state=state(proof.state);
            // 真实就诊时刻不能被旧原生字段的秒精度覆盖，也不能退回 UNKNOWN。
            if(!"KNOWN".equals(state.get("actual_time_kind"))||!Objects.equals(normalize(actual.getOccurredAt()),normalize(state.get("actual_at_utc")))
            ||!actual.getInputOffset().equals(state.get("actual_offset")))throw integrity();
        }else if(!proof.source.reference.getKind().name().equals(receipt.get("successorKind"))
        ||proof.source.reference.getId()!=number(receipt.get("successorId")))throw integrity();
    }
    /** A 与 V 持有不同能力；V 没有任何原生、陪诊或提醒写方法。 */
    public long insertState(VisitFollowUpAdmission.Context context,SourceRef source,Map<String,Object> initial) {
        context.requireAppointmentWrite(source);
        if(!context.scope().contains(source.getKind().tableName(),source.getId()))throw integrity();
        return insertSkeleton(context.scope(),source,initial);
    }
    public long insertVisitState(VisitFollowUpAdmission.VisitBindingContext context,Map<String,Object> initial) {
        context.requireBindingWrite();
        return insertSkeleton(context.scope(),context.scope().command.getSource(),initial);
    }
    private long insertSkeleton(VisitFollowUpAdmission.Scope scope,SourceRef source,Map<String,Object> initial) {
        requireWriteScope(scope);
        NativeSource nativeSource=source(source);
        if(nativeSource.patient!=scope.command.getPatientId())throw integrity();
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("patient_id",nativeSource.patient);
        values.put("source_kind",source.getKind().name());
        values.put(source.getKind().idColumn(),source.getId());
        values.put("lock_version",0L);
        values.put("source_revision",0L);
        values.put("source_fingerprint",nativeSource.fingerprint);
        values.putAll(nativeSource.nativeTarget());
        values.put("attendance","UNVERIFIED");
        values.put("attendance_origin","UNKNOWN");
        values.put("actual_time_kind","UNKNOWN");
        values.put("next_decision","NEEDS_DECISION");
        values.put("disposition","UNASSESSED");
        values.put("disposition_revision",0L);
        Instant recordedAt=scope.now();
        values.put("created_by",scope.actor);
        values.put("created_at",recordedAt);
        values.put("updated_at",recordedAt);
        if(initial!=null)for(Map.Entry<String,Object> e:initial.entrySet()) {
            if(!Arrays.asList("responsible_user_id","decision_due_at","origin_appointment_state_id").contains(e.getKey()))throw integrity();
            values.put(e.getKey(),e.getValue());
        }
        for(String user:Arrays.asList("created_by","responsible_user_id"))if(values.get(user)!=null&&!scope.subjects.contains(number(values.get(user))))throw integrity();
        if(values.get("origin_appointment_state_id")!=null&&!scope.contains("visit_followup_state",number(values.get("origin_appointment_state_id"))))throw integrity();
        long id=insert("visit_followup_state",values);
        scope.registerState(id,source);
        return id;
    }
    public long appendAppointmentEvent(VisitFollowUpAdmission.Context context,long state,Map<String,Object> patch,String reason,Map<String,Object> details) {
        context.requireAppointmentState(state);
        return append(context.scope(),state,patch,reason,details,false);
    }
    public long appendBindingEvent(VisitFollowUpAdmission.VisitBindingContext context,long state,Map<String,Object> patch,String reason,Map<String,Object> details) {
        context.requireBindingState(state);
        return append(context.scope(),state,patch,reason,details,true);
    }
    private long append(VisitFollowUpAdmission.Scope scope,long stateId,Map<String,Object> patch,String reason,Map<String,Object> details,boolean binding) {
        requireWriteScope(scope);
        Map<String,Object> current=state(stateId);
        if(current==null||number(current.get("patient_id"))!=scope.command.getPatientId())throw integrity();
        Map<String,Object> after=new LinkedHashMap<>();
        for(String field:STATE_FIELDS)after.put(field,current.get(field));
        for(Map.Entry<String,Object> e:patch.entrySet()) {
            if(!STATE_FIELDS.contains(e.getKey())||(binding?!VISIT_FIELDS.contains(e.getKey()):VISIT_FIELDS.contains(e.getKey())&&!Arrays.asList("source_revision","source_fingerprint").contains(e.getKey())))throw integrity();
            after.put(e.getKey(),e.getValue());
        }
        if(reason!=null&&reason.codePointCount(0,reason.length())>(binding?1000:500))throw integrity();
        after.put("updated_at",scope.now());
        long next=nextVersion(number(current.get("lock_version")));
        Map<String,Object> payload=WorkflowData.map("after",normalize(after));
        if(details!=null)payload.put("details",details);
        String encoded=WorkflowData.json(payload);
        if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw integrity();
        Map<String,Object> e=WorkflowData.map("state_id",stateId,"patient_id",scope.command.getPatientId(),"state_version",next,"event_type",scope.command.getOperation().name(),"actor_id",scope.actor,"actor_name",scope.currentAuthority().actorName(scope.actor),"actor_relation",scope.currentAuthority().actorRelation(scope.actor),"command_key",scope.command.getCommandKey(),"occurred_at_utc",after.get("actual_at_utc"),"recorded_at",after.get("updated_at"),"reason",reason,"before_source_fingerprint",current.get("source_fingerprint"),"after_source_fingerprint",after.get("source_fingerprint"),"payload_json",encoded);
        for(String field:Arrays.asList("native_status","native_start_wall","native_end_wall","native_notify_wall","native_doctor_id","native_schedule_id"))e.put(field,after.get(field));
        long event=insert("visit_followup_event",e);
        for(String pointer:Arrays.asList("last_native_event_id","current_fact_event_id","decision_event_id"))if("SELF".equals(after.get(pointer)))after.put(pointer,event);
        Set<String> changes=new LinkedHashSet<>(patch.keySet());
        changes.add("updated_at");
        scope.pendingEvent(stateId,event,next,after,changes);
        return event;
    }
    /** 事件先追加，再一次 CAS；失败不能留下任何半条事实。 */
    public void advanceAppointmentState(VisitFollowUpAdmission.Context context,long state,long event) {
        context.requireAppointmentState(state);
        advance(context.scope(),state,event);
    }
    public void advanceBindingState(VisitFollowUpAdmission.VisitBindingContext context,long state,long event) {
        context.requireBindingState(state);
        advance(context.scope(),state,event);
    }
    private void advance(VisitFollowUpAdmission.Scope scope,long state,long event) {
        requireWriteScope(scope);
        VisitFollowUpAdmission.PendingEvent pending=scope.pending(state,event);
        Map<String,Object> values=new LinkedHashMap<>();
        for(String key:pending.changes)values.put(key,pending.after.get(key));
        values.put("lock_version",pending.version);
        List<Object> args=new ArrayList<>(values.values());
        args.add(state);
        args.add(scope.command.getPatientId());
        args.add(pending.version-1);
        String set=String.join(",",values.keySet().stream().map(k->k+"=?").toArray(String[]::new));
        if(update("UPDATE visit_followup_state SET "+set+" WHERE id=? AND patient_id=? AND lock_version=?",args.toArray())!=1)throw VisitFollowUpAuthorizationService.changed();
        scope.completedState(state,event,pending.version);
    }
    public void insertActionLink(VisitFollowUpAdmission.VisitBindingContext context,long state,long dispositionRevision,long plan,long revision,long action,long event,String kind) {
        context.requireBindingState(state);
        VisitFollowUpAdmission.Scope scope=context.scope();
        requireWriteScope(scope);
        if(!Arrays.asList("CHECK","REVISIT","REFERRAL").contains(kind)||!scope.contains("doctor_care_plan",plan)||!scope.contains("care_plan_revision",revision)||!scope.contains("care_plan_action",action))throw integrity();
        Map<String,Object> a=action(action),r=revision(revision),s=state(state);
        if(a==null||r==null||s==null||number(a.get("plan_id"))!=plan||number(a.get("revision_id"))!=revision||number(a.get("patient_id"))!=scope.command.getPatientId()||number(r.get("plan_id"))!=plan||number(s.get("disposition_revision"))!=dispositionRevision)throw integrity();
        context.requireRequestedLink(plan,revision,action,kind);
        Map<String,Object> values=WorkflowData.map("state_id",state,"patient_id",scope.command.getPatientId(),"disposition_revision",dispositionRevision,"plan_id",plan,"revision_id",revision,"action_id",action,"binding_event_id",event,"bound_after_action_event_id",actionWatermark(action),"kind",kind);
        insert("visit_followup_action_link",values);
    }
    void requireCompleted(VisitFollowUpAdmission.Scope scope,Map<String,Object> receipt) {
        scope.requireLive();
        requireCreationCompleted(scope,receipt);
        for(Map.Entry<Long,SourceRef> touched:scope.touchedStates.entrySet()) {
            Map<String,Object> state=state(touched.getKey());
            VisitFollowUpAdmission.CompletedState done=scope.completed.get(touched.getKey());
            if(state==null||done==null||number(state.get("lock_version"))<1||number(state.get("lock_version"))!=done.version)throw integrity();
            Map<String,Object> event=event(done.event);
            if(event==null||number(event.get("state_id"))!=touched.getKey()||number(event.get("state_version"))!=done.version||number(event.get("actor_id"))!=scope.actor||!scope.command.getCommandKey().equals(event.get("command_key")))throw integrity();
            NativeSource nativeSource=source(touched.getValue());
            if(nativeSource.patient!=scope.command.getPatientId()||!nativeSource.fingerprint.equals(state.get("source_fingerprint")))throw integrity();
            for(Map.Entry<String,Object> target:nativeSource.nativeTarget().entrySet())if(!Objects.equals(normalize(target.getValue()),normalize(state.get(target.getKey()))))throw integrity();
            if(!Objects.equals(state.get("source_fingerprint"),event.get("after_source_fingerprint")))throw integrity();
        }
        if(receipt==null)throw integrity();
        long receiptState=number(receipt.get("workflowId"));
        VisitFollowUpAdmission.CompletedState result=scope.completed.get(receiptState);
        SourceRef receiptSource=scope.touchedStates.get(receiptState);
        // 回执必须指向这次真正完成的状态和事件，不能只凭状态属于当前事务就关闭命令。
        if(result==null||receiptSource==null||result.event!=number(receipt.get("eventId"))||result.version!=number(receipt.get("version"))
        ||!receiptSource.getKind().name().equals(receipt.get("sourceKind"))||receiptSource.getId()!=number(receipt.get("sourceId")))throw integrity();
        requireReceiptSuccessor(scope,receipt,true);
        CompletedLinks.require(this,scope,receipt);

    }
    private static final class CompletedLinks {
        static void require(VisitFollowUpRepository repo,VisitFollowUpAdmission.Scope scope,Map<String,Object> receipt) {
            if(!scope.binding)return;
            long stateId=number(receipt.get("workflowId"));
            Map<String,Object> state=repo.state(stateId);
            List<Map<String,Object>> actual=repo.links(stateId,number(state.get("disposition_revision")));
            List<Map<String,Object>> requested=VisitFollowUpAdmission.requestedLinks(scope.command);
            if(actual.size()!=requested.size())throw integrity();
            Set<String> expected=new HashSet<>();
            for(Map<String,Object> link:requested)expected.add(link.get("planId")+":"+link.get("revisionId")+":"+link.get("actionId")+":"+link.get("kind"));
            for(Map<String,Object> link:actual) {
                String key=link.get("plan_id")+":"+link.get("revision_id")+":"+link.get("action_id")+":"+link.get("kind");
                if(!expected.remove(key)||number(link.get("binding_event_id"))!=number(receipt.get("eventId"))||number(link.get("patient_id"))!=scope.command.getPatientId()||number(link.get("bound_after_action_event_id"))!=repo.actionWatermark(number(link.get("action_id"))))throw integrity();
            }
            if(!expected.isEmpty())throw integrity();
        }
    }
    static SourceRef reference(Map<String,Object> state) {
        return new SourceRef(SourceKind.valueOf(String.valueOf(state.get("source_kind"))),number(state.get(SourceKind.valueOf(String.valueOf(state.get("source_kind"))).idColumn())));
    }
    static final String REMINDER_EVENT="VISIT_FOLLOWUP_REMINDER",REMINDER_TITLE="Appointment reminder";
    private static final com.fasterxml.jackson.databind.ObjectMapper REMINDER_JSON=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 真实observer的独立短事务：固定INSERT、真实生成主键、同连接精确回读；不读取state。 */
    long insertReminderDeliveryLog(long recipient,long patient,String status,String detail) {
        WorkflowData.mutationTransaction(jdbc);id(recipient);id(patient);reminderDetail(status,detail);
        GeneratedKeyHolder keys=new GeneratedKeyHolder();
        int affected=jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("INSERT INTO notification_delivery_log(recipient_user_id,patient_id,event_type,title,delivery_status,detail) VALUES(?,?,?,?,?,?)",new String[]{"id"});ps.setQueryTimeout(5);bind(ps,recipient,patient,REMINDER_EVENT,REMINDER_TITLE,status,detail);return ps;},keys);
        if(affected!=1||keys.getKeyList().size()!=1||keys.getKeyList().get(0).size()!=1)throw integrity();
        Object generated=keys.getKeyList().get(0).values().iterator().next();long log;
        try {log=id(generated);}catch(RuntimeException invalid){throw integrity();}
        Map<String,Object> row=reminderDeliveryLog(log);
        if(row==null||number(row.get("id"))!=log||number(row.get("recipient_user_id"))!=recipient||number(row.get("patient_id"))!=patient||!REMINDER_EVENT.equals(row.get("event_type"))||!REMINDER_TITLE.equals(row.get("title"))||!status.equals(row.get("delivery_status"))||!detail.equals(row.get("detail"))||row.get("created_at")==null)throw integrity();
        return log;
    }
    static com.fasterxml.jackson.databind.JsonNode reminderDetail(String status,String detail) {
        if(!Arrays.asList("DELIVERED","FAILED","UNKNOWN","NO_CHANNEL","SKIPPED").contains(status)||detail==null||detail.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1000)throw integrity();
        try {
            com.fasterxml.jackson.databind.JsonNode json=REMINDER_JSON.readTree(detail);
            Set<String> expected=new HashSet<>(Arrays.asList("attemptId","stateId","sourceKind","sourceId","sourceRevision","channelId","reasonCode")),actual=new HashSet<>();json.fieldNames().forEachRemaining(actual::add);
            if(!json.isObject()||!expected.equals(actual)||!json.get("attemptId").isTextual()||!json.get("attemptId").asText().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||!json.get("sourceKind").isTextual()||!Arrays.asList("CARE_ITEM_APPOINTMENT","CARE_APPOINTMENT").contains(json.get("sourceKind").asText()))throw integrity();
            for(String key:Arrays.asList("stateId","sourceId","sourceRevision"))if(!json.get(key).isIntegralNumber()||!json.get(key).canConvertToLong()||json.get(key).longValue()<1||json.get(key).longValue()>MAX_ID)throw integrity();
            com.fasterxml.jackson.databind.JsonNode channel=json.get("channelId"),reason=json.get("reasonCode");
            boolean attempted=Arrays.asList("DELIVERED","FAILED","UNKNOWN").contains(status);
            if(!channel.isNull()&&(!channel.isIntegralNumber()||!channel.canConvertToLong()||channel.longValue()<1||channel.longValue()>MAX_ID)||attempted&&channel.isNull()||"NO_CHANNEL".equals(status)&&!channel.isNull())throw integrity();
            String required="DELIVERED".equals(status)?null:"FAILED".equals(status)?"PROVIDER_FAILED":"UNKNOWN".equals(status)?"PROVIDER_UNKNOWN":"NO_CHANNEL".equals(status)?"NO_ENABLED_CHANNELS":null;
            if("SKIPPED".equals(status)){if(!reason.isTextual()||!Arrays.asList("CHANNEL_UNAVAILABLE","CURRENT_GATE_DENIED","CURRENT_GATE_UNAVAILABLE","RECIPIENT_UNAVAILABLE").contains(reason.asText()))throw integrity();}
            else if(required==null?!reason.isNull():!reason.isTextual()||!required.equals(reason.asText()))throw integrity();
            return json;
        }catch(java.io.IOException|NullPointerException invalid){throw integrity();}
    }
    /** marker是原生无offset墙钟；不能经UTC事实转换，也不作为实际到诊时间。 */
    Map<String,Object> reminderNativeMarker(SourceRef source) {
        if(!source.getKind().isAppointment())throw integrity();
        Map<String,Object> row=one("SELECT CAST(notified_at AS CHAR) AS notified_wall"+(source.getKind()==SourceKind.CARE_ITEM_APPOINTMENT?",CAST(escalated_at AS CHAR) AS escalated_wall":"")+" FROM "+source.getKind().tableName()+" WHERE id=?",source.getId());
        if(row==null)throw integrity();
        row.replaceAll((key,value)->value==null?null:LocalDateTime.parse(String.valueOf(value).replace(' ','T')));return row;
    }

    /** 精确日志PK，绝不按历史JSON搜索；缺失证明不能补猜其它成功。 */
    Map<String,Object> reminderDeliveryLog(long log) { return one("SELECT id,recipient_user_id,patient_id,event_type,title,delivery_status,detail,created_at FROM notification_delivery_log WHERE id=?",id(log)); }
    void lock(String table,long id,boolean shared) {
        if("notification_delivery_log".equals(table)&&!shared)throw integrity();
        if(!Arrays.asList("patient","sys_user","sys_role","sys_user_role","care_access_grant","doctor_patient_assignment","care_member","care_nurse_assignment","care_item","care_appointment","visit_record","family_assistance","family_assistance_event","visit_followup_state","visit_followup_event","visit_followup_action_link","doctor_care_plan","care_plan_revision","care_plan_action","notification_delivery_log").contains(table))throw integrity();
        if(one("SELECT id FROM "+table+" WHERE id=?"+(shared?" LOCK IN SHARE MODE":" FOR UPDATE"),id)==null)throw VisitFollowUpAuthorizationService.changed();
    }
    Map<String,Object> one(String sql,Object...args) {
        List<Map<String,Object>> rows=rows(sql,1,args);
        return rows.isEmpty()?null:rows.get(0);
    }
    List<Map<String,Object>> rows(String sql,int maximum,Object...args) {
        return jdbc.query(sql,ps-> {
            ps.setQueryTimeout(5);
            bind(ps,args);
        },(rs,index)-> {
            if(index>=maximum)throw VisitFollowUpAuthorizationService.limit();
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData md=rs.getMetaData();
            for(int n=1; n<=md.getColumnCount(); n++) {
                String column=md.getColumnLabel(n).toLowerCase(Locale.ROOT);
                Object value=rs.getObject(n);
                if(value instanceof Clob)value=rs.getString(n);
                if(value instanceof Timestamp) {
                    String raw=rs.getString(n);
                    value=WALL_FIELDS.contains(column)?LocalDateTime.parse(raw.replace(' ','T')):rs.getTimestamp(n,WorkflowData.utc()).toInstant();
                }
                row.put(column,value);
            }
            return row;
        });
    }
    int update(String sql,Object...args) {
        return jdbc.update(sql,ps-> {
            ps.setQueryTimeout(5);
            bind(ps,args);
        });
    }
    private long insert(String table,Map<String,Object> values) {
        if(!Arrays.asList("visit_followup_state","visit_followup_event","visit_followup_action_link").contains(table))throw integrity();
        GeneratedKeyHolder keys=new GeneratedKeyHolder();
        jdbc.update(connection-> {
            PreparedStatement ps=connection.prepareStatement("INSERT INTO "+table+"("+String.join(",",values.keySet())+") VALUES("+String.join(",",Collections.nCopies(values.size(),"?"))+")",new String[] {
                "id"
            });
            ps.setQueryTimeout(5);
            bind(ps,values.values().toArray());
            return ps;
        },keys);
        if(keys.getKey()==null)throw integrity();
        return id(keys.getKey());
    }
    static void bind(PreparedStatement ps,Object...args)throws SQLException {
        for(int n=0; n<args.length; n++) {
            Object value=args[n];
            if(value instanceof Instant)WorkflowData.time(ps,n+1,(Instant)value);
            else if(value instanceof LocalDateTime)ps.setString(n+1,((LocalDateTime)value).format(MICROS).replace('T',' '));
            else ps.setObject(n+1,value);
        }
    }
    static Object normalize(Object value) {
        if(value instanceof Instant)return MICROS.format(((Instant)value).atOffset(ZoneOffset.UTC))+"Z";
        if(value instanceof LocalDateTime)return MICROS.format((LocalDateTime)value);
        if(value instanceof Map) {
            Map<String,Object> result=new TreeMap<>();
            ((Map<?,?>)value).forEach((k,v)->result.put(String.valueOf(k),normalize(v)));
            return result;
        }
        if(value instanceof Collection) {
            List<Object> result=new ArrayList<>();
            for(Object v:(Collection<?>)value)result.add(normalize(v));
            return result;
        }
        return value;
    }
    static String canonical(Object value) {
        return WorkflowData.json(normalize(value));
    }
    static long number(Object value) {
        return value instanceof Number?((Number)value).longValue():Boolean.TRUE.equals(value)?1:0;
    }
    static Long nullable(Object value) {
        return value==null?null:number(value);
    }
    static WorkflowException integrity() {
        return new WorkflowException(409,"VISIT_FOLLOWUP_INTEGRITY","The follow-up transaction is incomplete or inconsistent.");
    }
}
