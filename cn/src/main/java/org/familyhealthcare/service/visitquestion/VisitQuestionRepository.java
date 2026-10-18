package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.service.workflow.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;

/** 固定 SQL、先检查字节预算，并在实际执行连接上逐次验证传输条件。 */
@Repository
public class VisitQuestionRepository {
    // 原生未知字段不能因普通文字编辑而丢失小数精度；不改共享命令规范化解析器。
    private static final ObjectMapper NATIVE_JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String NATIVE_FIELDS = "id,patient_id,user_id,kind,title,status,assigned_user_id,actor_id,actor_name,event_at,notify_at,notified_at,escalated_at,data_json,created_at,updated_at";
    private static final String STATE_FIELDS = "question_id,patient_id,state,lock_version,current_title,current_description,current_recorded_answer,current_follow_up,ordinary_answer_origin,ordinary_answer_recorder_id,ordinary_answer_recorded_at,legacy_appointment_id,source_observed_hash,source_observed_at,observed_b_assistance_id,context_type,context_id,context_fingerprint,responsible_user_id,follow_up_due_date,current_answer_event_id,close_reason,created_by,created_at,updated_at";
    private static final String NATIVE_LENGTH = nativeLength();
    private static final String CURRENT_LENGTH = "OCTET_LENGTH(current_title)+COALESCE(OCTET_LENGTH(current_description),0)+COALESCE(OCTET_LENGTH(current_recorded_answer),0)+COALESCE(OCTET_LENGTH(current_follow_up),0)";
    final JdbcTemplate jdbc;
    private final VisitQuestionSchema schema;
    public VisitQuestionRepository(JdbcTemplate jdbc, VisitQuestionSchema schema) { this.jdbc = jdbc; this.schema = schema; }
    public VisitQuestionSchema.Availability availability(VisitQuestionProperties properties) {
        return jdbc.execute((ConnectionCallback<VisitQuestionSchema.Availability>) connection -> schema.availability(connection,properties));
    }
    /** 仅供有限查询使用，调用者固定 SQL 和最大行数；同一执行连接验证混合正文列。 */
    public List<Map<String,Object>> readRows(boolean modern,boolean nativeColumns,String sql,Object...args) {
        return jdbc.query(connection->{
            if(modern)VisitQuestionTransportReadiness.inspect(connection,nativeColumns?EnumSet.allOf(VisitQuestionTransportReadiness.ReadColumn.class):EnumSet.range(VisitQuestionTransportReadiness.ReadColumn.TITLE,VisitQuestionTransportReadiness.ReadColumn.RECEIPT)).requireStatement(connection,sql,args);
            return statement(connection,false,false,sql,false,args);
        },(rs,n)->readRow(rs));
    }
    public void ready() { jdbc.execute((ConnectionCallback<Void>) connection -> { schema.requireReady(connection); return null; }); }
    public VisitQuestionSchema.Status storage() { return jdbc.execute((ConnectionCallback<VisitQuestionSchema.Status>) schema::inspectStorage); }
    public void parent(long patient) {
        mutationTransaction(jdbc);
        if (plainOne("SELECT id,user_id FROM patient WHERE id=? FOR UPDATE", patient) == null) throw denied();
    }
    public Map<String,Object> discover(long question) {
        return one("SELECT question_id,patient_id,created_by,responsible_user_id,lock_version,context_type,context_id,legacy_appointment_id FROM visit_question_state WHERE question_id=?", question);
    }
    public Map<String,Object> replay(long actor, String key) {
        return one("SELECT question_id,expected_version,payload_hash,CASE WHEN OCTET_LENGTH(result_json)<=512 THEN result_json ELSE NULL END AS result_json FROM visit_question_command WHERE actor_id=? AND command_key=?", actor, key);
    }
    public Map<String,Object> state(long question, long patient) {
        Map<String,Object> size = one("SELECT patient_id," + CURRENT_LENGTH + " AS bytes FROM visit_question_state WHERE question_id=? FOR UPDATE", question);
        if (size == null) return null;
        if (id(size.get("patient_id")) != patient) throw denied();
        bound(size.get("bytes"), VisitQuestionContracts.RAW_BYTES);
        Map<String,Object> row = one("SELECT " + STATE_FIELDS + " FROM visit_question_state WHERE question_id=? AND patient_id=?", question, patient);
        VisitQuestionContracts.requireRawScalars(text(row,"current_title"),text(row,"current_description"),text(row,"current_recorded_answer"),text(row,"current_follow_up"));
        return row;
    }
    public Map<String,Object> source(long question, long patient, boolean modern, boolean bPresent) {
        Map<String,Object> size = queryOne(modern, false, "SELECT patient_id,kind," + NATIVE_LENGTH + " AS bytes FROM care_item WHERE id=? FOR UPDATE", question);
        if (size == null || id(size.get("patient_id")) != patient || !"QUESTION".equals(size.get("kind"))) throw denied();
        bound(size.get("bytes"), VisitQuestionContracts.RAW_BYTES);
        return queryOne(modern, true, "SELECT " + NATIVE_FIELDS + (bPresent ? ",family_workflow_version,family_assistance_id" : ",0 AS family_workflow_version,NULL AS family_assistance_id") + " FROM care_item WHERE id=? AND patient_id=?", question, patient);
    }
    public Map<String,Object> baseline(Map<String,Object> source) {
        Map<String,Object> baseline = new LinkedHashMap<>();
        String[] javaNames = {"id","patientId","userId","kind","title","status","assignedUserId","actorId","actorName","eventAt","notifyAt","notifiedAt","escalatedAt","dataJson","createdAt","updatedAt"};
        String[] sqlNames = NATIVE_FIELDS.split(",");
        for (int i=0;i<javaNames.length;i++) baseline.put(javaNames[i],source.get(sqlNames[i]));
        return VisitQuestionContracts.baseline(baseline);
    }
    public Map<String,Object> details(Map<String,Object> source) {
        String value = text(source,"data_json");
        Map<String,Object> details=new LinkedHashMap<>();
        if(value!=null){
            VisitQuestionContracts.requireBytes(value,VisitQuestionContracts.RAW_BYTES,"native details");
            try {
                Map<String,Object> parsed=NATIVE_JSON.readValue(value,new TypeReference<Map<String,Object>>(){});
                if(parsed==null)throw WorkflowException.invalid("Original question details must be an object.");
                details.putAll(parsed);
            } catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw WorkflowException.invalid("Original question details are invalid.");}
        }
        if (org.familyhealthcare.service.familyassistance.FamilyAssistanceQueryService.restricted(details)) throw denied();
        return details;
    }
    public long createNative(long patient,long owner,long actor,String title,String details,Instant now,boolean modern) {
        return insert(modern,"INSERT INTO care_item(patient_id,user_id,kind,title,status,actor_id,data_json,created_at,updated_at) VALUES(?,?,'QUESTION',?,'OPEN',?,?,?,?)",patient,owner,title,actor,details,now,now);
    }
    public void mirror(Map<String,Object> source, Map<String,Object> state, boolean modern, boolean bPresent) {
        if (source.get("family_assistance_id") != null || id(source.get("family_workflow_version")) != 0) throw conflict("QUESTION_NATIVE_FROZEN");
        Map<String,Object> details = details(source);
        String unchanged=json(details);
        for (String[] field : new String[][]{{"description","current_description"},{"answer","current_recorded_answer"},{"followUp","current_follow_up"},{"appointmentId","legacy_appointment_id"}}) {
            Object value=state.get(field[1]); if(value==null)details.remove(field[0]);else details.put(field[0],value);
        }
        if(modern)details.put("questionWorkflowVersion",1);
        String encoded=json(details);
        if(Objects.equals(source.get("title"),state.get("current_title"))&&unchanged.equals(encoded))return;
        VisitQuestionContracts.requireRawScalars(text(state,"current_title"),encoded);
        String sql="UPDATE care_item SET title=?,data_json=? WHERE id=? AND patient_id=?"+(bPresent?" AND family_workflow_version=0 AND family_assistance_id IS NULL":"");
        if(update(modern,sql,state.get("current_title"),encoded,source.get("id"),source.get("patient_id"))!=1)throw conflict("QUESTION_SOURCE_CHANGED");
    }
    public void saveState(Map<String,Object> state,boolean first,long expected) {
        List<Object> values=new ArrayList<>();for(String field:STATE_FIELDS.split(","))values.add(state.get(field));
        if(first) {
            if(update(true,"INSERT INTO visit_question_state("+STATE_FIELDS+") VALUES("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.toArray())!=1)throw conflict("QUESTION_STATE_CHANGED");
        } else {
            String[] fields=STATE_FIELDS.split(",");List<String> sets=new ArrayList<>();List<Object> changed=new ArrayList<>();
            for(String field:fields)if(!Arrays.asList("question_id","patient_id","created_by","created_at").contains(field)){sets.add(field+"=?");changed.add(state.get(field));}
            changed.add(state.get("question_id"));changed.add(state.get("patient_id"));changed.add(expected);
            if(update(true,"UPDATE visit_question_state SET "+String.join(",",sets)+" WHERE question_id=? AND patient_id=? AND lock_version=?",changed.toArray())!=1)throw conflict("VERSION_CONFLICT");
        }
    }
    public Map<String,Object> one(String sql,Object...args){return queryOne(true,false,sql,args);}
    public Map<String,Object> nativeOne(String sql,Object...args){return queryOne(true,true,sql,args);}
    public Map<String,Object> plainOne(String sql,Object...args){return queryOne(false,false,sql,args);}
    private Map<String,Object> queryOne(boolean modern,boolean nativeBody,String sql,Object...args) {
        List<Map<String,Object>> rows=jdbc.query(connection->statement(connection,modern,nativeBody,sql,false,args),(rs,n)->readRow(rs));
        if(rows.size()>1)throw conflict("QUESTION_IDENTITY_INVALID");return rows.isEmpty()?null:rows.get(0);
    }
    public int update(boolean modern,String sql,Object...args){return jdbc.update(connection->statement(connection,modern,false,sql,false,args));}
    public long insert(boolean modern,String sql,Object...args) {
        GeneratedKeyHolder keys=new GeneratedKeyHolder();jdbc.update(connection->statement(connection,modern,false,sql,true,args),keys);
        if(keys.getKey()==null)throw conflict("QUESTION_IDENTITY_INVALID");return VisitQuestionContracts.id(keys.getKey(),"generatedId");
    }
    private PreparedStatement statement(Connection connection,boolean modern,boolean nativeBody,String sql,boolean generated,Object[] args)throws SQLException {
        if(modern) {
            if(nativeBody)VisitQuestionTransportReadiness.inspect(connection,EnumSet.of(VisitQuestionTransportReadiness.ReadColumn.NATIVE_TITLE,VisitQuestionTransportReadiness.ReadColumn.NATIVE_DETAILS)).requireStatement(connection,sql,args);
            else VisitQuestionTransportReadiness.requireStatement(connection,sql,args);
        }
        PreparedStatement ps=generated?connection.prepareStatement(sql,new String[]{"id"}):connection.prepareStatement(sql);ps.setQueryTimeout(5);
        for(int i=0;i<args.length;i++)if(args[i] instanceof Instant)WorkflowData.time(ps,i+1,(Instant)args[i]);else ps.setObject(i+1,args[i]);return ps;
    }
    private static Map<String,Object> readRow(ResultSet rs)throws SQLException {
        Map<String,Object> row=new LinkedHashMap<>();ResultSetMetaData md=rs.getMetaData();
        for(int i=1;i<=md.getColumnCount();i++){
            String name=md.getColumnLabel(i).toLowerCase(Locale.ROOT);Object value=rs.getObject(i);
            // 原生 DATETIME 保留数据库墙钟文字，不虚构临床事件的 UTC 作者时间。
            if(value instanceof Timestamp||value instanceof java.sql.Date||value instanceof Clob)value=rs.getString(i);
            row.put(name,value);
        }return row;
    }
    private static String nativeLength(){List<String> sums=new ArrayList<>();for(String field:NATIVE_FIELDS.split(","))sums.add("COALESCE(OCTET_LENGTH(CAST("+field+" AS CHAR)),0)");return String.join("+",sums);}
    static String text(Map<String,Object> row,String field){Object value=row.get(field);if(value!=null&&!(value instanceof String))throw conflict("QUESTION_IDENTITY_INVALID");return (String)value;}
    static void bound(Object value,long maximum){if(!(value instanceof Number)||((Number)value).longValue()<0||((Number)value).longValue()>maximum)throw new WorkflowException(413,"QUESTION_LIMIT","This original question is too large to start tracking safely. Its stored text has not been changed.");}
    static WorkflowException conflict(String code){return new WorkflowException(409,code,"The question changed. Refresh and review it before retrying.");}
    static WorkflowException denied(){return org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.denied();}
}
