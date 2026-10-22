package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 病历专用的有界存储操作；不公开 JDBC 字段，不把存储状态当作权限。 */
@org.springframework.stereotype.Repository
public class MedicalRecordCorrectionRepository {
    private final JdbcTemplate jdbc;
    /** Identity only: never acquire or bind another datasource. */
    public void requireDataSource(javax.sql.DataSource expected) {
        if (expected == null || jdbc.getDataSource() != expected)
            throw new IllegalStateException("The configured datasource does not match.");
    }

    private final MedicalRecordCorrectionSchema schema;
    public MedicalRecordCorrectionRepository(JdbcTemplate jdbc, MedicalRecordCorrectionSchema schema) { this.jdbc = jdbc; this.schema = schema; }

    public MedicalRecordCorrectionSchema.Status status() {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<MedicalRecordCorrectionSchema.Status>) schema::inspectStorage);
    }
    public void requireNewCommands(MedicalRecordCorrectionSchema.Status status, boolean enabled) { schema.requireNewCommands(status, enabled); }
    public void requireMutation() { WorkflowData.mutationTransaction(jdbc); }
    public org.familyhealthcare.entity.MedicalRecord binding(long id, boolean lock) {
        MedicalRecordCorrectionContracts.id(id);
        List<org.familyhealthcare.entity.MedicalRecord> rows = query("SELECT id,patient_id,user_id,content_version FROM medical_record WHERE id=?" + (lock ? " FOR UPDATE" : ""), (r,n) -> {
            org.familyhealthcare.entity.MedicalRecord value = new org.familyhealthcare.entity.MedicalRecord();
            value.setId(r.getLong("id")); value.setPatientId(WorkflowData.nullableId(r,"patient_id"));
            value.setUserId(WorkflowData.nullableId(r,"user_id")); value.setContentVersion(WorkflowData.nullableId(r,"content_version")); return value;
        }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public static final class State {
        public final Long patientId, ownerId;
        public final long baselineVersion, version, sequence;
        public final String validity, digest;
        private State(java.sql.ResultSet r) throws java.sql.SQLException {
            patientId=WorkflowData.nullableId(r,"patient_id"); ownerId=WorkflowData.nullableId(r,"owner_user_id");
            baselineVersion=r.getLong("baseline_content_version"); version=r.getLong("current_content_version"); sequence=r.getLong("current_history_sequence");
            validity=r.getString("validity"); digest=r.getString("content_digest");
            if (baselineVersion<1 || version<baselineVersion || version>MedicalRecordCorrectionContracts.MAX_ID || sequence<0 || sequence>MedicalRecordCorrectionContracts.MAX_ID
                    || !("ACTIVE".equals(validity)||"INVALIDATED".equals(validity)) || digest==null || !digest.matches("[0-9a-f]{64}")) throw MedicalRecordCorrectionContracts.integrity();
        }
    }
    public State state(long id, boolean lock) {
        List<State> rows=query("SELECT patient_id,owner_user_id,baseline_content_version,current_content_version,current_history_sequence,validity,content_digest FROM medical_record_correction_state WHERE record_id=?"+(lock?" FOR UPDATE":""),(r,n)->new State(r),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void requireStable(org.familyhealthcare.entity.MedicalRecord source, State state) {
        try { MedicalRecordCorrectionContracts.id(source.getContentVersion()); }
        catch (org.familyhealthcare.service.workflow.WorkflowException invalidVersion) { throw MedicalRecordCorrectionContracts.integrity(); }
        if (state!=null && (!Objects.equals(source.getPatientId(),state.patientId) || !Objects.equals(source.getUserId(),state.ownerId)
                || !Long.valueOf(state.version).equals(source.getContentVersion()))) throw MedicalRecordCorrectionContracts.integrity();
        if (state == null) return;
        // 只点读当前事件和真实基线；摘要分页不能因坏指针伪装成空历史，也不预载快照。
        List<Map<String,Object>> heads=query("SELECT h.after_version,h.validity,h.content_digest,b.after_version AS baseline_version,b.before_version AS baseline_before,b.operation AS baseline_operation,CASE WHEN EXISTS(SELECT 1 FROM medical_record_correction_history p WHERE p.record_id=h.record_id AND p.history_sequence>?) THEN 1 ELSE 0 END AS pending FROM medical_record_correction_history h JOIN medical_record_correction_history b ON b.record_id=h.record_id AND b.history_sequence=0 WHERE h.record_id=? AND h.history_sequence=?",(r,n)->WorkflowData.map("version",r.getLong("after_version"),"validity",r.getString("validity"),"digest",r.getString("content_digest"),"baseline",r.getLong("baseline_version"),"before",r.getLong("baseline_before"),"operation",r.getString("baseline_operation"),"pending",r.getInt("pending")),state.sequence,source.getId(),state.sequence);
        if(heads.size()!=1)throw MedicalRecordCorrectionContracts.integrity();
        Map<String,Object> head=heads.get(0);
        if(!Long.valueOf(state.version).equals(head.get("version"))||!state.validity.equals(head.get("validity"))||!state.digest.equals(head.get("digest"))||!Long.valueOf(state.baselineVersion).equals(head.get("baseline"))||!head.get("baseline").equals(head.get("before"))||!"BASELINE".equals(head.get("operation"))||!Integer.valueOf(0).equals(head.get("pending")))throw MedicalRecordCorrectionContracts.integrity();
    }
    public MedicalRecordCorrectionContracts.Snapshot snapshot(long id) {
        // 大型原文只在 SQL 求长度/摘要；核对说明先限界，绝不先把任意 CLOB 载入 JVM。
        String sql="SELECT id,patient_id,patient_name,user_id,record_date,record_type,hospital_name,dept_name,doctor_name,remark,source_type,source_external_id,confidence_score,created_at,updated_at,content_version,verification_status,verified_by,verified_at,"
                +"OCTET_LENGTH(verification_note) AS note_bytes,CASE WHEN OCTET_LENGTH(verification_note)<=262144 THEN verification_note ELSE NULL END AS verification_note,"
                +"OCTET_LENGTH(ai_raw_result) AS ai_bytes,SHA2(ai_raw_result,256) AS ai_hash,OCTET_LENGTH(import_facts_json) AS facts_bytes,SHA2(import_facts_json,256) AS facts_hash FROM medical_record WHERE id=?";
        List<Map<String,Object>> records=query(sql,(r,n)->{
            if(r.getLong("note_bytes")>MedicalRecordCorrectionContracts.SNAPSHOT_BYTES)throw MedicalRecordCorrectionContracts.limit();
            Map<String,Object> row=WorkflowData.map("id",r.getLong("id"),"patientId",WorkflowData.nullableId(r,"patient_id"),"userId",WorkflowData.nullableId(r,"user_id"),"contentVersion",r.getLong("content_version"),"verifiedBy",WorkflowData.nullableId(r,"verified_by"));
            String[][] fields={{"patientName","patient_name"},{"recordDate","record_date"},{"recordType","record_type"},{"hospitalName","hospital_name"},{"deptName","dept_name"},{"doctorName","doctor_name"},{"remark","remark"},{"sourceType","source_type"},{"sourceExternalId","source_external_id"},{"confidenceScore","confidence_score"},{"verificationStatus","verification_status"},{"verificationNote","verification_note"}};
            for(String[] field:fields)row.put(field[0],r.getString(field[1]));
            row.put("createdAt",WorkflowData.time(r,"created_at"));row.put("updatedAt",WorkflowData.time(r,"updated_at"));row.put("verifiedAt",WorkflowData.time(r,"verified_at"));
            row.put("aiRawReference",reference(r,"ai_bytes","ai_hash"));row.put("importFactsReference",reference(r,"facts_bytes","facts_hash"));return row;
        },id);
        if(records.size()!=1)throw MedicalRecordCorrectionAuthorization.denied();
        List<Map<String,Object>> items=query("SELECT id,record_id,item_name,item_code,result_value,unit,reference_range,is_abnormal,ai_confidence,created_at FROM medical_record_item WHERE record_id=? ORDER BY id LIMIT 101",(r,n)->WorkflowData.map("id",r.getLong("id"),"recordId",r.getLong("record_id"),"itemName",r.getString("item_name"),"itemCode",r.getString("item_code"),"resultValue",r.getString("result_value"),"unit",r.getString("unit"),"referenceRange",r.getString("reference_range"),"isAbnormal",WorkflowData.nullableId(r,"is_abnormal"),"aiConfidence",r.getString("ai_confidence"),"createdAt",WorkflowData.time(r,"created_at")),id);
        List<Map<String,Object>> attachments=query("SELECT id,record_id,file_name,file_type,file_size,created_at FROM medical_record_attachment WHERE record_id=? ORDER BY id LIMIT 101",(r,n)->WorkflowData.map("id",r.getLong("id"),"recordId",r.getLong("record_id"),"fileName",r.getString("file_name"),"fileType",r.getString("file_type"),"fileSize",WorkflowData.nullableId(r,"file_size"),"createdAt",WorkflowData.time(r,"created_at")),id);
        return MedicalRecordCorrectionContracts.snapshot(WorkflowData.map("schemaVersion",1L,"record",records.get(0),"items",items,"attachments",attachments));
    }
    private static Object reference(java.sql.ResultSet r,String size,String hash) throws java.sql.SQLException {
        Long bytes=WorkflowData.nullableId(r,size);return bytes==null?null:WorkflowData.map("byteLength",bytes,"sha256",r.getString(hash));
    }
    public Map<String,Object> execute(long actor,MedicalRecordCorrectionContracts.Command command,java.time.Instant now,java.util.function.Supplier<Map<String,Object>> change) {
        return org.familyhealthcare.service.workflow.WorkflowCommandExecutor.execute(jdbc,org.familyhealthcare.service.workflow.WorkflowCommandExecutor.Domain.MEDICAL_RECORD,actor,command.getCommandKey(),command.canonicalEnvelope(),command.getRecordId(),command.getExpectedContentVersion(),now,change);
    }
    public void append(long id,long sequence,String operation,long before,long after,long actor,String reason,java.time.Instant now,String validity,MedicalRecordCorrectionContracts.Snapshot snapshot) {
        one(update("INSERT INTO medical_record_correction_history(record_id,history_sequence,operation,before_version,after_version,actor_id,reason,captured_at,validity,snapshot_json,snapshot_hash,content_digest) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,sequence,operation,before,after,actor,reason,now,validity,snapshot.json(),snapshot.snapshotHash(),snapshot.contentDigest()));
    }
    public void baseline(org.familyhealthcare.entity.MedicalRecord source,long actor,java.time.Instant now,MedicalRecordCorrectionContracts.Snapshot snapshot) {
        long v=MedicalRecordCorrectionContracts.id(source.getContentVersion());
        append(source.getId(),0,"BASELINE",v,v,actor,null,now,"ACTIVE",snapshot);
        one(update("INSERT INTO medical_record_correction_state(record_id,patient_id,owner_user_id,baseline_content_version,current_content_version,current_history_sequence,validity,content_digest,created_at,updated_at) VALUES(?,?,?,?,?,0,'ACTIVE',?,?,?)",source.getId(),source.getPatientId(),source.getUserId(),v,v,snapshot.contentDigest(),now,now));
    }
    @SuppressWarnings("unchecked")
    public void persist(long id,long expected,MedicalRecordCorrectionContracts.Snapshot target,List<MedicalRecordCorrectionContracts.ItemPatch> patches) {
        Map<String,Object> record=(Map<String,Object>)target.asMap().get("record");
        one(update("UPDATE medical_record SET record_date=?,hospital_name=?,dept_name=?,doctor_name=?,remark=?,content_version=?,verification_status='REVIEW_REQUIRED',verified_by=NULL,verified_at=NULL,verification_note=NULL,updated_at=? WHERE id=? AND content_version=?",record.get("recordDate"),record.get("hospitalName"),record.get("deptName"),record.get("doctorName"),record.get("remark"),record.get("contentVersion"),java.time.Instant.parse((String)record.get("updatedAt")),id,expected));
        Map<Long,Map<String,Object>> items=new java.util.HashMap<>();for(Map<String,Object> item:(List<Map<String,Object>>)target.asMap().get("items"))items.put((Long)item.get("id"),item);
        for(MedicalRecordCorrectionContracts.ItemPatch patch:patches){Map<String,Object> item=items.get(patch.getItemId());
            one(update("UPDATE medical_record_item SET item_name=?,item_code=?,result_value=?,unit=?,reference_range=?,is_abnormal=? WHERE id=? AND record_id=?",item.get("itemName"),item.get("itemCode"),item.get("resultValue"),item.get("unit"),item.get("referenceRange"),item.get("isAbnormal"),patch.getItemId(),id));}
    }
    public void advance(long id,long before,long next,long sequence,String validity,String digest,java.time.Instant now) {
        one(update("UPDATE medical_record_correction_state SET current_content_version=?,current_history_sequence=?,validity=?,content_digest=?,updated_at=? WHERE record_id=? AND current_content_version=?",next,sequence,validity,digest,now,id,before));
    }
    public List<Map<String,Object>> summaries(long id,long before,int limit) {
        return query("SELECT history_sequence,operation,before_version,after_version,actor_id,captured_at,validity FROM medical_record_correction_history WHERE record_id=? AND history_sequence<? ORDER BY history_sequence DESC LIMIT ?",(r,n)->summary(r),id,before,limit);
    }
    public Map<String,Object> revision(long id,long sequence) {
        List<Map<String,Object>> rows=query("SELECT history_sequence,operation,before_version,after_version,actor_id,captured_at,validity,reason,content_digest,snapshot_hash,OCTET_LENGTH(snapshot_json) AS snapshot_bytes,CASE WHEN OCTET_LENGTH(snapshot_json)<=262144 THEN snapshot_json ELSE NULL END AS snapshot_json FROM medical_record_correction_history WHERE record_id=? AND history_sequence=?",(r,n)->{
            if(r.getLong("snapshot_bytes")>MedicalRecordCorrectionContracts.SNAPSHOT_BYTES)throw MedicalRecordCorrectionContracts.limit();
            MedicalRecordCorrectionContracts.Snapshot snapshot=MedicalRecordCorrectionContracts.storedSnapshot(r.getString("snapshot_json"),r.getString("snapshot_hash"));
            Map<?,?> record=(Map<?,?>)snapshot.asMap().get("record");
            if(!Long.valueOf(id).equals(record.get("id"))||!Long.valueOf(r.getLong("after_version")).equals(record.get("contentVersion"))||!snapshot.contentDigest().equals(r.getString("content_digest")))throw MedicalRecordCorrectionContracts.integrity();
            Map<String,Object> result=summary(r);result.put("reason",r.getString("reason"));result.put("snapshot",snapshot);return result;
        },id,sequence);
        if(rows.size()!=1)throw new org.familyhealthcare.service.workflow.WorkflowException(404,"MEDICAL_RECORD_REVISION_UNAVAILABLE","无法读取此已采集版本。");return rows.get(0);
    }
    private static Map<String,Object> summary(java.sql.ResultSet r) throws java.sql.SQLException {
        return WorkflowData.map("historySequence",r.getLong("history_sequence"),"operation",r.getString("operation"),"beforeVersion",r.getLong("before_version"),"afterVersion",r.getLong("after_version"),"capturedBy",r.getLong("actor_id"),"capturedAt",WorkflowData.time(r,"captured_at"),"validity",r.getString("validity"));
    }
    private <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object...args) {
        return jdbc.query(c->{PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(5);bind(s,args);return s;},mapper);
    }
    private int update(String sql,Object...args) { return jdbc.update(c->{PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(5);bind(s,args);return s;}); }
    private static void bind(PreparedStatement statement,Object[] args) throws java.sql.SQLException {
        for(int i=0;i<args.length;i++)if(args[i] instanceof java.time.Instant)WorkflowData.time(statement,i+1,(java.time.Instant)args[i]);else statement.setObject(i+1,args[i]);
    }
    private static void one(int count) { if(count!=1)throw MedicalRecordCorrectionContracts.integrity(); }


    /**
     * 只在 NEW 命令的真实 beforeCommit 中调用，不能在重放时拿最新版本否定旧的已完成回执。
     * 业务层仍须在锁内核验完整目标内容摘要和当前授权/期限；这里守住提交时的四表转换闭合。
     */
    public static void requireCompletedTransition(JdbcTemplate jdbc, long actorId, String key,
                                                   MedicalRecordCorrectionContracts.Command command, Map<String,Object> receipt) {
        WorkflowData.mutationTransaction(jdbc);
        if (receipt == null) throw incomplete();
        String sql = "SELECT m.content_version AS native_version,m.patient_id AS native_patient,m.user_id AS native_owner,"
                + "s.patient_id AS state_patient,s.owner_user_id AS state_owner,s.baseline_content_version,s.current_content_version,"
                + "s.current_history_sequence,s.validity AS state_validity,s.content_digest AS state_digest,"
                + "h.before_version,h.after_version,h.actor_id AS history_actor,h.operation,h.validity AS history_validity,h.content_digest AS history_digest,"
                + "b.before_version AS baseline_before,b.after_version AS baseline_after,b.operation AS baseline_operation,"
                + "c.expected_version,c.payload_hash,CASE WHEN OCTET_LENGTH(c.result_json)<=512 THEN c.result_json ELSE NULL END AS result_json,"
                + "CASE WHEN EXISTS(SELECT 1 FROM medical_record_correction_history p WHERE p.record_id=m.id AND p.history_sequence>s.current_history_sequence) THEN 1 ELSE 0 END AS pending "
                + "FROM medical_record m JOIN medical_record_correction_state s ON s.record_id=m.id "
                + "JOIN medical_record_correction_history h ON h.record_id=m.id AND h.history_sequence=s.current_history_sequence "
                + "JOIN medical_record_correction_history b ON b.record_id=m.id AND b.history_sequence=0 "
                + "JOIN medical_record_command c ON c.record_id=m.id AND c.actor_id=? AND c.command_key=? WHERE m.id=?";
        List<Map<String,Object>> rows = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(5); statement.setLong(1, actorId); statement.setString(2, key); statement.setLong(3, command.getRecordId());
            return statement;
        }, (result, index) -> WorkflowData.map("nativeVersion", result.getLong("native_version"),
                "nativePatient", WorkflowData.nullableId(result, "native_patient"), "nativeOwner", WorkflowData.nullableId(result, "native_owner"),
                "statePatient", WorkflowData.nullableId(result, "state_patient"), "stateOwner", WorkflowData.nullableId(result, "state_owner"),
                "baselineVersion", result.getLong("baseline_content_version"), "stateVersion", result.getLong("current_content_version"),
                "sequence", result.getLong("current_history_sequence"), "stateValidity", result.getString("state_validity"), "stateDigest", result.getString("state_digest"),
                "before", result.getLong("before_version"), "after", result.getLong("after_version"), "actor", result.getLong("history_actor"),
                "operation", result.getString("operation"), "historyValidity", result.getString("history_validity"), "historyDigest", result.getString("history_digest"),
                "baselineBefore", result.getLong("baseline_before"), "baselineAfter", result.getLong("baseline_after"), "baselineOperation", result.getString("baseline_operation"),
                "expected", result.getLong("expected_version"), "hash", result.getString("payload_hash"), "result", result.getString("result_json"), "pending", result.getInt("pending")));
        if (rows.size() != 1) throw incomplete(); Map<String,Object> row = rows.get(0);
        long version = command.getExpectedContentVersion(), next = MedicalRecordCorrectionContracts.nextVersion(version);
        // 技术字符串在 JVM 精确比较，避免数据库大小写/补空格排序规则把不同值视为相等。
        if (!Objects.equals(command.getPatientId(), row.get("nativePatient")) || !Objects.equals(row.get("nativePatient"), row.get("statePatient"))
                || !Objects.equals(row.get("nativeOwner"), row.get("stateOwner"))
                || !Long.valueOf(next).equals(row.get("nativeVersion")) || !Long.valueOf(next).equals(row.get("stateVersion"))
                || !Long.valueOf(next).equals(row.get("after")) || !Long.valueOf(version).equals(row.get("before"))
                || !Long.valueOf(version).equals(row.get("expected")) || !Long.valueOf(actorId).equals(row.get("actor"))
                || !command.getOperation().name().equals(row.get("operation")) || !command.payloadHash().equals(row.get("hash"))
                || !receipt.get("historySequence").equals(row.get("sequence")) || !receipt.get("validity").equals(row.get("stateValidity"))
                || !receipt.get("validity").equals(row.get("historyValidity")) || !Objects.equals(row.get("stateDigest"), row.get("historyDigest"))
                || !(row.get("stateDigest") instanceof String) || !((String)row.get("stateDigest")).matches("[0-9a-f]{64}")
                || !"BASELINE".equals(row.get("baselineOperation")) || !Objects.equals(row.get("baselineVersion"), row.get("baselineBefore"))
                || !Objects.equals(row.get("baselineVersion"), row.get("baselineAfter")) || (Long)row.get("baselineVersion") < 1
                || (Long)row.get("baselineVersion") > version || !Integer.valueOf(0).equals(row.get("pending"))
                || !WorkflowData.json(receipt).equals(row.get("result"))) throw incomplete();
    }
    private static IllegalStateException incomplete() { return new IllegalStateException("An incomplete medical record transition cannot commit."); }
}
