package org.familyhealthcare.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.sql.Connection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import java.util.*;

/** 只读模式检查。未知/部分安装不能降级到旧写入；不执行迁移，不输出患者内容。 */
@Component
public final class HouseholdWorkflowSchema {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(HouseholdWorkflowSchema.class);
    private static final DiagnosticLimiter DIAGNOSTICS=new DiagnosticLimiter();
    public enum Status { ABSENT, READY, INCOMPATIBLE }
    public static final class Readiness {
        private final Status status;
        private final List<String> reasons;
        private Readiness(Status status,List<String> reasons) {
            this.status=status;
            this.reasons=Collections.unmodifiableList(new ArrayList<>(reasons));
        }
        public Status getStatus(){return status;}
        public List<String> getReasons(){return reasons;}
        public void requireReady(){if(status!=Status.READY)throw new WorkflowException(503,"SCHEMA_NOT_READY","Household workflow storage is not ready.");}
    }
    /** Structural success is deliberately not a claim about existing business rows. */
    public enum StorageStatus { ABSENT, STRUCTURALLY_READY, INCOMPATIBLE }
    public static final class StorageReadiness {
        private final StorageStatus status;
        private final List<String> reasons;
        private StorageReadiness(StorageStatus status, List<String> reasons) {
            this.status=status;
            this.reasons=Collections.unmodifiableList(new ArrayList<>(reasons));
        }
        public StorageStatus getStatus() { return status; }
        public List<String> getReasons() { return reasons; }
        public void requireStructurallyReady() {
            if (status!=StorageStatus.STRUCTURALLY_READY)
                throw new WorkflowException(503,"SCHEMA_NOT_READY","Household workflow storage is not ready.");
        }
    }
    private static final JsonNode MANIFEST=manifest();
    private static final JsonNode FOLLOW_UP_MANIFEST=readManifest("/visit-followup-schema.json");
    private static final JsonNode REMINDER_MANIFEST=readManifest("/visit-followup-reminder-schema.json");
    private static final JsonNode FOLLOW_UP_HOUSEHOLD=followUpHousehold(FOLLOW_UP_MANIFEST);
    private static final JsonNode REMINDER_HOUSEHOLD=followUpHousehold(REMINDER_MANIFEST);
    /** Entire alternatives, never per-trigger old/new allowlists. */
    public enum Profile { ABSENT, EXACT_OLD, FULLY_INSTALLED, FULLY_INSTALLED_REMINDER, INCOMPATIBLE }
    public static final class Compatibility {
        private final Profile profile;
        private final List<String> reasons;
        private final Connection connection;
        private final boolean metadataTimedOut;
        private final Thread thread=Thread.currentThread();
        private volatile boolean live;
        private Compatibility(Profile profile,List<String> reasons,Connection connection) {
            this(profile,reasons,connection,false);
        }
        private Compatibility(Profile profile,List<String> reasons,Connection connection,boolean metadataTimedOut) {
            this.connection=connection;
            this.metadataTimedOut=metadataTimedOut;
            live=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()&&org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive();
            if(live)org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){live=false;}});
            this.profile=profile;
            this.reasons=Collections.unmodifiableList(new ArrayList<>(reasons));
        }
        /** 结构证明只在原连接/线程/事务内复用；不替代患者令牌或当前业务权限。 */
        public void requireCurrent(JdbcTemplate jdbc){
            if(!live||thread!=Thread.currentThread()||!Arrays.asList(Profile.EXACT_OLD,Profile.FULLY_INSTALLED,Profile.FULLY_INSTALLED_REMINDER).contains(profile))throw admissionDenied();
            WorkflowData.mutationTransaction(jdbc);Connection current=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(Objects.requireNonNull(jdbc.getDataSource()));
            try{if(current!=connection)throw admissionDenied();}finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(current,jdbc.getDataSource());}
        }
        public Profile getProfile(){return profile;}
        public List<String> getReasons(){return reasons;}
    }

    /** One borrowed business connection; one finite shared metadata snapshot; no row reads, cache or migration. */
    public static Compatibility inspectCompatibility(Connection connection) {
        if(connection==null)return incompatible(connection,Collections.singletonList("METADATA_UNREADABLE"),null,MetadataGroup.CONNECTION);
        JdbcTemplate same=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        same.setQueryTimeout(5);
        // A timeout may already have evicted this borrowed connection. Translation must not read it again.
        same.setExceptionTranslator(new SQLExceptionSubclassTranslator());
        InspectionTrace trace=new InspectionTrace();
        try {
            trace.group=MetadataGroup.PRESENCE_HOUSEHOLD;
            Long household=same.queryForObject(MetadataQueryPrefilter.indexed(MANIFEST.get("presenceSql").asText()),Long.class);
            trace.group=MetadataGroup.PRESENCE_FOLLOWUP;
            Long followUp=same.queryForObject(MetadataQueryPrefilter.indexed(FOLLOW_UP_MANIFEST.get("presenceSql").asText()),Long.class);
            if(household==null||followUp==null||household<0||followUp<0)throw new IllegalStateException("Unreadable metadata");
            if(household==0&&followUp==0)return new Compatibility(Profile.ABSENT,Collections.emptyList(),connection);
            Snapshot snapshot=new Snapshot(same,REMINDER_MANIFEST.get("snapshotQueries"),MANIFEST,trace);
            trace.group=MetadataGroup.CONTRACT_VALIDATION;
            List<String> reasons=new ArrayList<>();
            // OLD/A/B共用同一次11组元数据；每套完整合同独立判定，不能混合逐个守卫。
            snapshot.withContract(FOLLOW_UP_MANIFEST).validateSession(reasons);
            if(followUp==0) {
                snapshot.validate(reasons);
                snapshot.withContract(FOLLOW_UP_MANIFEST).validatePrerequisites(reasons);
                snapshot.requireNoLogGuards(reasons);
                return reasons.isEmpty()?new Compatibility(Profile.EXACT_OLD,reasons,connection):incompatible(connection,reasons,null,trace.group);
            }
            snapshot.withContract(FOLLOW_UP_HOUSEHOLD).validate(reasons);
            snapshot.withContract(FOLLOW_UP_MANIFEST).validate(reasons);
            if(reasons.isEmpty())return new Compatibility(Profile.FULLY_INSTALLED,reasons,connection);
            List<String> reminderReasons=new ArrayList<>();
            snapshot.withContract(REMINDER_MANIFEST).validateSession(reminderReasons);
            snapshot.withContract(REMINDER_HOUSEHOLD).validate(reminderReasons);
            snapshot.withContract(REMINDER_MANIFEST).validate(reminderReasons);
            return reminderReasons.isEmpty()?new Compatibility(Profile.FULLY_INSTALLED_REMINDER,reminderReasons,connection):incompatible(connection,reminderReasons,null,trace.group);
        } catch(RuntimeException failure) {
            return incompatible(connection,Collections.singletonList("METADATA_UNREADABLE"),failure,trace.group);
        }
    }
    private static Compatibility incompatible(Connection connection,List<String> reasons,Throwable failure,MetadataGroup group){
        logDiagnostic(reasons,failure,group);
        return new Compatibility(Profile.INCOMPATIBLE,reasons,connection,metadataTimedOut(failure));
    }
    private static boolean metadataTimedOut(Throwable failure){
        for(int depth=0;failure!=null&&depth<16;depth++,failure=failure.getCause())
            if(failure instanceof java.sql.SQLTimeoutException)return true;
        return false;
    }
    private static void logDiagnostic(List<String> reasons,Throwable failure,MetadataGroup group){
        if(DIAGNOSTICS.allow(System.nanoTime()))
            LOG.warn("Household workflow storage verification failed: reasons={}, queryGroup={}, cause={}. Diagnostics are limited to once per minute; readiness is checked on every request.",reasons,group,diagnosticCause(failure));
    }
    /** Static identifiers only: never include query text, connection properties or returned values. */
    private enum MetadataGroup {
        CONNECTION, PRESENCE_HOUSEHOLD, PRESENCE_FOLLOWUP, CONTRACT_VALIDATION,
        SNAPSHOT_TABLES, SNAPSHOT_COLUMNS, SNAPSHOT_STATISTICS, SNAPSHOT_KEY_COLUMN_USAGE,
        SNAPSHOT_REFERENTIAL_CONSTRAINTS, SNAPSHOT_TABLE_CONSTRAINTS, SNAPSHOT_TRIGGERS,
        SNAPSHOT_ROUTINES, SNAPSHOT_SCHEMA, SNAPSHOT_UNKNOWN;
        static MetadataGroup snapshot(String key){
            switch(key){
                case "tables":return SNAPSHOT_TABLES;
                case "columns":return SNAPSHOT_COLUMNS;
                case "statistics":return SNAPSHOT_STATISTICS;
                case "key_column_usage":return SNAPSHOT_KEY_COLUMN_USAGE;
                case "referential_constraints":return SNAPSHOT_REFERENTIAL_CONSTRAINTS;
                case "table_constraints":return SNAPSHOT_TABLE_CONSTRAINTS;
                case "triggers":return SNAPSHOT_TRIGGERS;
                case "routines":return SNAPSHOT_ROUTINES;
                case "schema":return SNAPSHOT_SCHEMA;
                default:return SNAPSHOT_UNKNOWN;
            }
        }
    }
    private static final class InspectionTrace { MetadataGroup group=MetadataGroup.CONNECTION; }
    /** Log codes and exception types only: JDBC messages can contain URLs, SQL or row values. */
    static String diagnosticCause(Throwable failure){
        if(failure==null)return "CONTRACT_MISMATCH";
        String result=failure.getClass().getName();
        Throwable current=failure;
        for(int depth=0;current!=null&&depth<16;depth++,current=current.getCause()){
            if(current instanceof java.sql.SQLException){
                java.sql.SQLException sql=(java.sql.SQLException)current;
                String state=sql.getSQLState();
                return result+"; jdbcType="+sql.getClass().getName()+"; sqlState="+
                        (state!=null&&state.matches("[A-Za-z0-9]{5}")?state:"UNAVAILABLE")+"; vendorCode="+sql.getErrorCode();
            }
        }
        return result;
    }
    /** Throttles diagnostic output only; no metadata, authority or readiness result is cached. */
    static final class DiagnosticLimiter {
        private final java.util.concurrent.atomic.AtomicReference<Long> last=new java.util.concurrent.atomic.AtomicReference<>();
        boolean allow(long now){
            Long previous=last.get();
            return (previous==null||now-previous>=java.util.concurrent.TimeUnit.MINUTES.toNanos(1))&&last.compareAndSet(previous,now);
        }
    }
    private final JdbcTemplate jdbc;
    public HouseholdWorkflowSchema(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }

    /** 不缓存。只比较有限模式签名；成功不说明全库历史有效，也不授予任何业务权限。 */
    /** 归档等消费者经真实注入实例检查一次结构；连接仍由当前事务的Jdbc管理。 */
    public Compatibility inspectCompatibility() {
        return jdbc.execute((ConnectionCallback<Compatibility>)HouseholdWorkflowSchema::inspectCompatibility);
    }

    public StorageReadiness inspectStorageMetadata() {
        try {
        MetadataAttempt first=inspectMetadataAttempt(mayRetryPoolRead());
        Compatibility compatibility=first==null?null:first.compatibility;
        // Retry the complete read only after JdbcTemplate released the failed connection.
        // A caller-pinned connection, transaction, or synchronization must keep its original outcome.
        if(first!=null&&first.retryAllowed&&compatibility!=null&&compatibility.metadataTimedOut&&mayRetryPoolRead()){
            MetadataAttempt second=inspectMetadataAttempt(false);
            compatibility=second==null?null:second.compatibility;
        }
        if(compatibility==null)return new StorageReadiness(StorageStatus.INCOMPATIBLE,Collections.singletonList("METADATA_UNREADABLE"));
        Profile profile=compatibility.getProfile();
        return new StorageReadiness(profile==Profile.ABSENT?StorageStatus.ABSENT:
                profile==Profile.EXACT_OLD||profile==Profile.FULLY_INSTALLED||profile==Profile.FULLY_INSTALLED_REMINDER?StorageStatus.STRUCTURALLY_READY:StorageStatus.INCOMPATIBLE,
                compatibility.getReasons());
        } catch(RuntimeException failure) {
            logDiagnostic(Collections.singletonList("METADATA_UNREADABLE"),failure,MetadataGroup.CONNECTION);
            return new StorageReadiness(StorageStatus.INCOMPATIBLE,Collections.singletonList("METADATA_UNREADABLE"));
        }
    }
    private MetadataAttempt inspectMetadataAttempt(boolean allowRetry){
        return jdbc.execute((ConnectionCallback<MetadataAttempt>)connection->{
            // Capture this before inspection: a timeout may replace the pool connection with a closed delegate.
            boolean retryAllowed=allowRetry&&connection.getAutoCommit();
            return new MetadataAttempt(inspectCompatibility(connection),retryAllowed);
        });
    }
    private boolean mayRetryPoolRead(){
        return jdbc.getDataSource() instanceof HikariDataSource
                &&!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                &&!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                &&org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap().isEmpty();
    }
    private static final class MetadataAttempt {
        final Compatibility compatibility;final boolean retryAllowed;
        MetadataAttempt(Compatibility compatibility,boolean retryAllowed){this.compatibility=compatibility;this.retryAllowed=retryAllowed;}
    }

    /** 显式全库诊断：与 preflight/postcheck 相同的完整谓词。未完成或失败绝不报告 READY。 */
    public Readiness auditDatabase() {
        StorageReadiness storage=inspectStorageMetadata();
        if (storage.getStatus()==StorageStatus.ABSENT) return new Readiness(Status.ABSENT,storage.getReasons());
        if (storage.getStatus()!=StorageStatus.STRUCTURALLY_READY) return new Readiness(Status.INCOMPATIBLE,storage.getReasons());
        List<String> reasons=new ArrayList<>();
        try {
            for (JsonNode assertion:MANIFEST.get("assertions")) {
                if ("rows".equals(assertion.get("phase").asText())
                        && !Integer.valueOf(1).equals(jdbc.queryForObject(assertion.get("sql").asText(),Integer.class)))
                    reasons.add(assertion.get("reason").asText());
            }
        } catch (RuntimeException failure) { reasons.add("ROW_AUDIT_INCOMPLETE"); }
        return new Readiness(reasons.isEmpty()?Status.READY:Status.INCOMPATIBLE,reasons);
    }

    /**
     * 仅验证调用方在父锁下完整发现并按业务顺序锁定的既存相关行。令牌不能代替当前模块/来源授权。
     * 最多 50 个明确主键；不存在、跨患者、未知类型、超预算均拒绝。新增行由守卫写入验证，不能伪装成既存行。
     * 本方法不返回全库或闭环证明。全历史闭环必须按固定水位分批完整核对并在父锁下重验，不能截断为此列表。
     */
    public void requireSelectedRows(HouseholdAuthorityLocks.LockedAuthority token, long patientId, long actorId,
                                    Map<String,? extends Collection<Long>> selected) {
        requireSelectedRows(token,patientId,actorId,selected,()->inspectStorageMetadata().requireStructurallyReady());
    }
    public void requireSelectedRowsWithStorage(HouseholdAuthorityLocks.LockedAuthority token,long patientId,long actorId,
                                              Map<String,? extends Collection<Long>> selected,Compatibility evaluated) {
        if(evaluated==null)throw admissionDenied();
        requireSelectedRows(token,patientId,actorId,selected,()->evaluated.requireCurrent(jdbc));
    }
    private void requireSelectedRows(HouseholdAuthorityLocks.LockedAuthority token,long patientId,long actorId,
                                     Map<String,? extends Collection<Long>> selected,Runnable storage) {
        if (token==null) throw admissionDenied();
        token.requireLive(jdbc,patientId,actorId);
        Map<String,SortedSet<Long>> identities=selectedIdentities(selected);
        storage.run();
        try { HouseholdGovernance.requireCurrentAccount(jdbc,actorId); }
        catch (IllegalStateException denied) { throw new WorkflowException(403,"ACCESS_DENIED","Current authority is required."); }
        validateSelectedRows(patientId,identities,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(MANIFEST.get("mutationAdmission").get("queryBudgetSeconds").asLong()));
        token.requireLive(jdbc,patientId,actorId);
    }
    private Map<String,SortedSet<Long>> selectedIdentities(Map<String,? extends Collection<Long>> selected) {
        JsonNode contract=MANIFEST.get("mutationAdmission");
        if (selected==null || selected.isEmpty()) throw admissionDenied();
        int total=0;
        Map<String,SortedSet<Long>> identities=new TreeMap<>();
        for (Map.Entry<String,? extends Collection<Long>> entry:selected.entrySet()) {
            if (entry.getKey()==null || !contract.get("selectedTables").has(entry.getKey())
                    || entry.getValue()==null || entry.getValue().isEmpty()) throw admissionDenied();
            SortedSet<Long> ids=new TreeSet<>();
            for (Long id:entry.getValue()) {
                if (id==null || id<=0 || id>9007199254740991L || !ids.add(id)) throw admissionDenied();
                if (++total>contract.get("maxSelectedRows").asInt()) throw admissionDenied();
            }
            identities.put(entry.getKey(),ids);
        }
        return identities;
    }
    private void validateSelectedRows(long patientId,Map<String,SortedSet<Long>> identities,long deadline) {
        JsonNode contract=MANIFEST.get("mutationAdmission");
        try {
            for (Map.Entry<String,SortedSet<Long>> entry:identities.entrySet()) {
                String table=entry.getKey();
                List<Object> parameters=new ArrayList<>(entry.getValue());
                String placeholders=String.join(",",Collections.nCopies(parameters.size(),"?"));
                // 输入是白名单表和有界主键；不先扫全表再 LIMIT，不加载历史 JSON 内容。
                String ownership="SELECT COUNT(*) FROM `"+table+"` n WHERE n.id IN ("+placeholders+") AND n.patient_id=?";
                parameters.add(patientId);
                if (selectedScalar(ownership,parameters,deadline)!=entry.getValue().size()) throw admissionDenied();
                parameters.remove(parameters.size()-1);
                String predicate=contract.get("selectedTables").get(table).get("invalidPredicate").asText();
                String sql="SELECT CASE WHEN EXISTS(SELECT 1 FROM `"+table+"` n WHERE n.id IN ("+placeholders+") AND ("+predicate+") LIMIT 1) THEN 0 ELSE 1 END";
                if (selectedScalar(sql,parameters,deadline)!=1) throw admissionDenied();
            }
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new WorkflowException(503,"ADMISSION_INDETERMINATE","Selected rows could not be verified; retry.");
        }
    }
    /** 唯一系统例外：已锁定的家庭请求、当前周期和精确真实截止。没有客户端入口或任意表选择。 */
    public void requireFamilyDueRows(HouseholdAuthorityLocks.FamilyDueAuthority token,long patient,long assistance,int cycle,java.time.Instant exactDeadline,java.time.Instant now) {
        if(token==null||now==null||exactDeadline==null||exactDeadline.isAfter(now))throw admissionDenied();
        token.requireCreationLive(jdbc,patient,assistance,cycle,exactDeadline);
        inspectStorageMetadata().requireStructurallyReady();
        List<Map<String,Object>> rows=jdbc.query("SELECT patient_id,source_kind,care_item_id,care_appointment_id,cycle,state,deadline_at FROM family_assistance WHERE id=? FOR UPDATE",(rs,n)->WorkflowData.map("patient",rs.getLong(1),"kind",rs.getString(2),"item",WorkflowData.nullableId(rs,"care_item_id"),"appointment",WorkflowData.nullableId(rs,"care_appointment_id"),"cycle",rs.getInt(5),"state",rs.getString(6),"deadline",rs.getTimestamp(7,WorkflowData.utc())==null?null:rs.getTimestamp(7,WorkflowData.utc()).toInstant()),assistance);
        if(rows.size()!=1)throw admissionDenied();Map<String,Object> row=rows.get(0);
        if(WorkflowData.id(row.get("patient"))!=patient||WorkflowData.id(row.get("cycle"))!=cycle||!exactDeadline.equals(row.get("deadline"))||!Arrays.asList("REQUESTED","ACCEPTED","AWAITING_CONFIRMATION").contains(row.get("state")))throw admissionDenied();
        String table="CARE_ITEM".equals(row.get("kind"))?"care_item":"CARE_APPOINTMENT".equals(row.get("kind"))?"care_appointment":null;if(table==null)throw admissionDenied();Object source=row.get(table.equals("care_item")?"item":"appointment");if(source==null)throw admissionDenied();
        if(!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND patient_id=? AND family_workflow_version=1 AND family_assistance_id=?",Integer.class,source,patient,assistance)))throw admissionDenied();
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();selected.put(table,Collections.singleton(WorkflowData.id(source)));selected.put("family_assistance",Collections.singleton(assistance));
        String dueKey="FAMILY_ASSISTANCE:"+assistance+":"+cycle+":"+java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(java.time.ZoneOffset.UTC).format(exactDeadline);
        List<Long> events=jdbc.query("SELECT id FROM family_assistance_event WHERE due_key=? AND assistance_id=? AND patient_id=? AND cycle=? AND due_at=? AND type='DUE' AND actor_kind='SYSTEM' AND actor_id IS NULL AND actor_relation='DUE' FOR UPDATE",ps->{ps.setString(1,dueKey);ps.setLong(2,assistance);ps.setLong(3,patient);ps.setInt(4,cycle);WorkflowData.time(ps,5,exactDeadline);},(rs,n)->rs.getLong(1));if(events.size()>1)throw admissionDenied();if(!events.isEmpty())selected.put("family_assistance_event",events);
        validateSelectedRows(patient,selectedIdentities(selected),token.validationDeadline());token.requireCreationLive(jdbc,patient,assistance,cycle,exactDeadline);
    }
    /** 旧截止投递仅清理/尝试记账；不可变事件绑定四行。当前源、周期和接收权限仍在发送前独立复核。 */
    public void requireFamilyDueNotificationRows(HouseholdAuthorityLocks.FamilyDueAuthority token,long patient,long assistance,int cycle,java.time.Instant deadline,long event,long notification) {
        if(token==null||deadline==null)throw admissionDenied();token.requireNotificationLive(jdbc,patient,assistance,cycle,deadline,event,notification);inspectStorageMetadata().requireStructurallyReady();
        List<Map<String,Object>> targets=jdbc.query("SELECT source_kind,care_item_id,care_appointment_id FROM family_assistance WHERE id=? AND patient_id=? FOR UPDATE",(rs,n)->WorkflowData.map("kind",rs.getString(1),"item",WorkflowData.nullableId(rs,"care_item_id"),"appointment",WorkflowData.nullableId(rs,"care_appointment_id")),assistance,patient);if(targets.size()!=1)throw admissionDenied();Map<String,Object> target=targets.get(0);String table="CARE_ITEM".equals(target.get("kind"))?"care_item":"CARE_APPOINTMENT".equals(target.get("kind"))?"care_appointment":null;if(table==null)throw admissionDenied();Object source=target.get(table.equals("care_item")?"item":"appointment");if(source==null)throw admissionDenied();
        String dueKey="FAMILY_ASSISTANCE:"+assistance+":"+cycle+":"+java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(java.time.ZoneOffset.UTC).format(deadline);
        List<Map<String,Object>> events=jdbc.query("SELECT evidence_json,evidence_hash FROM family_assistance_event WHERE id=? AND patient_id=? AND assistance_id=? AND cycle=? AND type='DUE' AND actor_kind='SYSTEM' AND actor_id IS NULL AND actor_relation='DUE' AND due_key=? AND due_at=? FOR UPDATE",ps->{ps.setLong(1,event);ps.setLong(2,patient);ps.setLong(3,assistance);ps.setInt(4,cycle);ps.setString(5,dueKey);WorkflowData.time(ps,6,deadline);},(rs,n)->WorkflowData.map("json",rs.getString(1),"hash",rs.getString(2)));if(events.size()!=1||!WorkflowData.hash((String)events.get(0).get("json")).equals(events.get(0).get("hash")))throw admissionDenied();
        List<Map<String,Object>> notices=jdbc.query("SELECT recipient_user_id,channel_id,dispatch_key,template_key FROM family_assistance_notification WHERE id=? AND event_id=? AND patient_id=? AND assistance_id=? AND cycle=? FOR UPDATE",(rs,n)->WorkflowData.map("recipient",WorkflowData.nullableId(rs,"recipient_user_id"),"channel",WorkflowData.nullableId(rs,"channel_id"),"dispatch",rs.getString(3),"template",rs.getString(4)),notification,event,patient,assistance,cycle);if(notices.size()!=1)throw admissionDenied();Map<String,Object> notice=notices.get(0);if(!Objects.equals("event:"+event+":"+notice.get("recipient")+":"+notice.get("channel"),notice.get("dispatch"))||!Objects.equals("FAMILY_ASSISTANCE_DUE",notice.get("template")))throw admissionDenied();
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();selected.put(table,Collections.singleton(WorkflowData.id(source)));selected.put("family_assistance",Collections.singleton(assistance));selected.put("family_assistance_event",Collections.singleton(event));selected.put("family_assistance_notification",Collections.singleton(notification));validateSelectedRows(patient,selectedIdentities(selected),token.validationDeadline());token.requireNotificationLive(jdbc,patient,assistance,cycle,deadline,event,notification);
    }
    /** 用药服务器通知仅校验同一 live 事务中的具体关系，不借接收人/owner 充当事件 actor。 */
    public void requireMedicationNotificationRows(HouseholdAuthorityLocks.MedicationNotificationAuthority token,long patient,long execution,long event,long notification,Map<String,? extends Collection<Long>> selected){
        if(token==null)throw admissionDenied();token.requireLive(jdbc,patient,execution,event,notification);
        Map<String,SortedSet<Long>> identities=selectedIdentities(selected);
        if(!identities.getOrDefault("medication_execution",Collections.emptySortedSet()).contains(execution)||!identities.getOrDefault("medication_execution_event",Collections.emptySortedSet()).contains(event)||!identities.getOrDefault("medication_execution_notification",Collections.emptySortedSet()).contains(notification))throw admissionDenied();
        inspectStorageMetadata().requireStructurallyReady();validateSelectedRows(patient,identities,token.validationDeadline());token.requireLive(jdbc,patient,execution,event,notification);
    }
    private int selectedScalar(String sql, List<Object> arguments, long deadline) {
        long remaining=deadline-System.nanoTime();
        if (remaining<=0) throw admissionDenied();
        int timeout=(int)Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(remaining)+1);
        Integer result=jdbc.query(connection->{
            java.sql.PreparedStatement statement=connection.prepareStatement(sql);
            statement.setQueryTimeout(timeout);
            for (int i=0;i<arguments.size();i++) statement.setObject(i+1,arguments.get(i));
            return statement;
        }, (org.springframework.jdbc.core.ResultSetExtractor<Integer>) rows->rows.next()?rows.getInt(1):null);
        if (result==null || System.nanoTime()>deadline) throw admissionDenied();
        return result;
    }
    private static WorkflowException admissionDenied() {
        return new WorkflowException(409,"SELECTED_ROWS_UNVERIFIED","Related rows are incomplete or changed; refresh and retry.");
    }
    /** Finite metadata comparison only; business row predicates stay exact shared SQL below the metadata gate. */
    private static final class Snapshot {
        private final Map<String,List<Map<String,Object>>> data;
        private final JsonNode contract;
        private final String schema,collation,version;
        Snapshot(JdbcTemplate jdbc,JsonNode queryContract,JsonNode contract,InspectionTrace trace){
            this.data=new HashMap<>();this.contract=contract;
            Iterator<Map.Entry<String,JsonNode>> queries=queryContract.fields();
            while(queries.hasNext()){
                Map.Entry<String,JsonNode> query=queries.next();List<Map<String,Object>> rows=new ArrayList<>();
                trace.group=MetadataGroup.snapshot(query.getKey());
                for(Map<String,Object> raw:jdbc.queryForList(MetadataQueryPrefilter.indexed(query.getValue().asText()))){
                    Map<String,Object> row=new HashMap<>();for(Map.Entry<String,Object> value:raw.entrySet())row.put(value.getKey().toLowerCase(Locale.ROOT),value.getValue());rows.add(row);
                }
                data.put(query.getKey(),rows);
            }
            if(data.get("schema").size()!=1)throw new IllegalStateException("No selected schema");
            Map<String,Object> info=data.get("schema").get(0);schema=str(info.get("schema_name"));collation=str(info.get("default_collation_name"));version=str(info.get("server_version"));
        }
        private Snapshot(Snapshot source,JsonNode contract) {
            this.data=source.data;this.contract=contract;this.schema=source.schema;this.collation=source.collation;this.version=source.version;
        }
        Snapshot withContract(JsonNode value){return new Snapshot(this,value);}
        void validatePrerequisites(List<String> reasons){
            Set<String> prerequisites=new HashSet<>();for(JsonNode table:contract.get("prerequisiteTables"))prerequisites.add(table.asText());
            for(JsonNode rule:contract.get("metadataRules")){
                String table=rule.has("table")?rule.get("table").asText():rule.get("identity").asText();
                if(prerequisites.contains(table)&&!matches(rule))reasons.add(rule.get("reason").asText());
            }
        }
        void requireNoLogGuards(List<String> reasons) {
            if(!rows("triggers",map("event_object_table","notification_delivery_log")).isEmpty())reasons.add("UNKNOWN:notification_delivery_log_guards");
        }
        void validateSession(List<String> reasons) {
            for(JsonNode rule:contract.get("metadataRules"))
                if("SESSION".equals(rule.get("kind").asText())&&!matches(rule))reasons.add(rule.get("reason").asText());
        }
        void validate(List<String> reasons) {
            for(JsonNode rule:contract.get("metadataRules"))
                if(!"SESSION".equals(rule.get("kind").asText())&&!matches(rule))reasons.add(rule.get("reason").asText());
        }
        boolean matches(JsonNode rule){
            String kind=rule.get("kind").asText(),identity=rule.get("identity").asText();JsonNode expected=expected(rule);
            String table=rule.has("table")?rule.get("table").asText():identity;
            if("TABLE".equals(kind))return one("tables",map("table_name",table,"table_type","BASE TABLE","engine",expected.get("engine").asText(),"table_collation",expected.get("collation").asText()),expected.path("rowFormat").isNull()?Collections.emptyMap():map("row_format",expected.get("rowFormat").asText()));
            if("COLUMN".equals(kind)){
                List<Map<String,Object>> found=rows("columns",map("table_name",table,"column_name",expected.get("column_name").asText()));
                return found.size()==1&&column(found.get(0),expected);
            }
            if("COLUMNS".equals(kind))return rows("columns",map("table_name",table)).size()==expected.get("columns").size();
            if("NATIVE_COLUMNS".equals(kind)){
                Set<String> added=new HashSet<>();contract.get("native").get(identity).get("columns").fieldNames().forEachRemaining(added::add);
                int original=0;for(Map<String,Object> row:rows("columns",map("table_name",identity)))if(!added.contains(str(row.get("column_name"))))original++;
                return original==expected.get("columns").size()-added.size();
            }
            if("INDEX".equals(kind))return index(table,expected,rule.path("global").asBoolean());
            if("FK".equals(kind))return foreignKey(table,expected);
            if("GUARD".equals(kind))return guard(expected);
            if("SERVER".equals(kind))return validServer();
            if("SESSION".equals(kind))return validSession();
            if("COUNTS".equals(kind))return exactCounts(table);
            if("UNKNOWN".equals(kind))return unknown(identity);
            throw new IllegalStateException("Unknown metadata rule");
        }
        private boolean validServer(){
            String raw=str(data.get("schema").get(0).get("raw_server_version"));
            return schema!=null&&"5.7.18".equals(version)&&raw!=null
                    &&(raw.equals("5.7.18")||raw.startsWith("5.7.18-"))&&!raw.toLowerCase(Locale.ROOT).contains("mariadb");
        }
        private boolean validSession(){
            Map<String,Object> info=data.get("schema").get(0);String mode=str(info.get("session_sql_mode"));
            Set<String> modes=mode==null?Collections.emptySet():new HashSet<>(Arrays.asList(mode.split(",",-1)));
            return validServer()&&(modes.contains("STRICT_TRANS_TABLES")||modes.contains("STRICT_ALL_TABLES"))
                    &&equal(info.get("foreign_key_checks"),1)&&equal(info.get("unique_checks"),1);
        }
        private boolean exactCounts(String table){
            JsonNode expected=contract.get("signatures").get(table);int indexRows=0,constraints=expected.get("fks").size(),foreignRows=0;
            for(JsonNode key:expected.get("keys")){indexRows+=key.get("columns").size();if(key.get("unique").asBoolean())constraints++;}
            for(JsonNode foreign:expected.get("fks"))foreignRows+=foreign.get("columns").size();
            int keys=foreignRows;for(JsonNode key:expected.get("keys"))if(key.get("unique").asBoolean())keys+=key.get("columns").size();
            return rows("statistics",map("table_name",table)).size()==indexRows
                    &&rows("table_constraints",map("table_name",table)).size()==constraints
                    &&rows("key_column_usage",map("table_name",table)).size()==keys
                    &&rows("referential_constraints",map("table_name",table)).size()==expected.get("fks").size();
        }
        private JsonNode expected(JsonNode rule){
            String kind=rule.get("kind").asText(),identity=rule.get("identity").asText();
            if("TABLE".equals(kind)||"COLUMNS".equals(kind)||"NATIVE_COLUMNS".equals(kind))return contract.get("signatures").get(identity);
            if("GUARD".equals(kind)){for(String group:Arrays.asList("guards","canonicalPrerequisiteGuards"))for(JsonNode guard:contract.get(group))if(identity.equals(guard.get("name").asText()))return guard;}
            if(rule.has("table")){
                JsonNode table=contract.get("signatures").get(rule.get("table").asText());String name=identity.substring(identity.indexOf('.')+1);
                String group="COLUMN".equals(kind)?"columns":"INDEX".equals(kind)?"keys":"fks";
                if("FK".equals(kind)&&name.matches("[0-9]+"))return table.get(group).get(Integer.parseInt(name));
                for(JsonNode object:table.get(group))if(name.equals(object.path("COLUMN".equals(kind)?"column_name":"name").asText()))return object;
                throw new IllegalStateException("Missing metadata identity");
            }
            return null;
        }
        private boolean column(Map<String,Object> actual,JsonNode expected){
            for(String field:Arrays.asList("column_name","ordinal_position","data_type","is_nullable","character_set_name","collation_name","character_maximum_length","datetime_precision"))if(!equal(actual.get(field),value(expected.get(field))))return false;
            String type=expected.get("data_type").asText(),actualType=str(actual.get("column_type"));
            if(Arrays.asList("int","bigint","tinyint").contains(type)){
                if(actualType==null||!actualType.toLowerCase(Locale.ROOT).matches(type+"(\\([0-9]+\\))?"))return false;
            }else if(!equal(actualType,value(expected.get("column_type"))))return false;
            if("decimal".equals(type))for(String field:Arrays.asList("numeric_precision","numeric_scale"))if(!equal(actual.get(field),value(expected.get(field))))return false;
            for(String field:Arrays.asList("column_default","extra")){
                String wanted=str(value(expected.get(field))),found=str(actual.get(field));
                if("CURRENT_TIMESTAMP".equals(wanted)||"on update CURRENT_TIMESTAMP".equals(wanted)){
                    if(found==null||!found.replace("()", "").equalsIgnoreCase(wanted))return false;
                }else if("column_default".equals(field)){
                    if(!columnDefaultMatches(type,expected.get("column_type").asText(),actualType,found,wanted))return false;
                }else if(!equal(found,wanted))return false;
            }
            return true;
        }
        private boolean index(String table, JsonNode expected, boolean global) {
            String name = expected.get("name").asText();
            List<Map<String,Object>> candidates = rows("statistics", global ? map("index_name",name) : map("table_name",table,"index_name",name));
            if (candidates.size()!=expected.get("columns").size()) return false;
            for (int i=0; i<expected.get("columns").size(); i++) {
                if (!contains(candidates,map("table_name",table,"seq_in_index",i+1,"column_name",expected.get("columns").get(i).asText(),"non_unique",expected.get("unique").asBoolean()?0:1,"sub_part",null,"collation","A","index_type","BTREE"))) return false;
            }
            if (!global) return true;
            Map<String,Object> reservedName = map("constraint_schema",schema,"constraint_name",name);
            boolean unique = expected.get("unique").asBoolean();
            List<Map<String,Object>> constraints = rows("table_constraints",reservedName);
            List<Map<String,Object>> keys = rows("key_column_usage",reservedName);
            if (constraints.size()!=(unique?1:0) || keys.size()!=(unique?expected.get("columns").size():0)
                    || !rows("referential_constraints",reservedName).isEmpty()) return false;
            if (unique) {
                if (!contains(constraints,map("table_name",table,"constraint_type","UNIQUE"))) return false;
                for (int i=0; i<expected.get("columns").size(); i++) {
                    if (!contains(keys,map("table_name",table,"column_name",expected.get("columns").get(i).asText(),"ordinal_position",i+1,"position_in_unique_constraint",null,"referenced_table_schema",null,"referenced_table_name",null,"referenced_column_name",null))) return false;
                }
            }
            return true;
        }
        private boolean foreignKey(String table, JsonNode expected) {
            String name = str(value(expected.get("name")));
            if (name!=null) {
                Map<String,Object> reservedName = map("constraint_schema",schema,"constraint_name",name);
                if (rows("key_column_usage",reservedName).size()!=expected.get("columns").size()
                        || rows("referential_constraints",reservedName).size()!=1
                        || rows("table_constraints",reservedName).size()!=1) return false;
            }
            Map<String,Object> filter = map("constraint_schema",schema,"table_name",table);
            if (name!=null) filter.put("constraint_name",name);
            // 自动生成的旧约束名可变；同一实际身份必须同时满足所有签名，不能借用另一外键动作。
            for (Map<String,Object> action : rows("referential_constraints",filter)) {
                Map<String,Object> identity = map("constraint_schema",schema,"table_name",table,"constraint_name",action.get("constraint_name"));
                List<Map<String,Object>> keys = rows("key_column_usage",identity);
                if (keys.size()!=expected.get("columns").size()) continue;
                if (!matchesFields(action,map("referenced_table_name",expected.get("target").asText(),"unique_constraint_schema",schema,"update_rule",expected.get("update").asText(),"delete_rule",expected.get("delete").asText()))) continue;
                if (!one("table_constraints",identity,map("constraint_type","FOREIGN KEY"))) continue;
                if(expected.has("targetIndex")&&!equal(action.get("unique_constraint_name"),expected.get("targetIndex").asText()))continue;
                boolean complete = true;
                for (int i=0; i<expected.get("columns").size(); i++) {
                    complete &= contains(keys,map("column_name",expected.get("columns").get(i).asText(),"ordinal_position",i+1,"position_in_unique_constraint",i+1,"referenced_table_schema",schema,"referenced_table_name",expected.get("target").asText(),"referenced_column_name",expected.get("targetColumns").get(i).asText()));
                }
                if (complete) return true;
            }
            return false;
        }
        private boolean guard(JsonNode expected){
            List<Map<String,Object>> found=rows("triggers",map("trigger_name",expected.get("name").asText(),"event_object_schema",schema,"event_object_table",expected.get("table").asText(),"action_timing",expected.get("timing").asText(),"event_manipulation",expected.get("event").asText(),"action_orientation","ROW"));
            if(found.size()!=1)return false;Map<String,Object> row=found.get(0);String body=str(row.get("action_statement"));
            if(expected.get("name").asText().startsWith("vf57_")&&(!equal(row.get("action_order"),1)||row.get("action_condition")!=null))return false;
            if(body==null||!WorkflowData.hash(body).equals(expected.get("sha256").asText()))return false;
            if(!expected.path("sqlMode").isNull()){
                String mode=str(row.get("sql_mode"));if(mode==null)return false;
                List<String> actual=Arrays.asList(mode.split(",",-1)),wanted=Arrays.asList(expected.get("sqlMode").asText().split(","));
                if(actual.size()!=wanted.size()||!new HashSet<>(actual).equals(new HashSet<>(wanted)))return false;
                if(!equal(row.get("character_set_client"),"utf8mb4")||!equal(row.get("collation_connection"),"utf8mb4_bin")||!equal(row.get("database_collation"),collation))return false;
            }
            return true;
        }
        private boolean unknown(String kind){
            if(contract==FOLLOW_UP_MANIFEST||contract==REMINDER_MANIFEST)return unknownFollowUp(kind);
            Set<String> allowed=new HashSet<>();
            if("guards".equals(kind)){
                Set<String> owned=new HashSet<>();contract.get("tables").fieldNames().forEachRemaining(owned::add);contract.get("originalNativeColumns").fieldNames().forEachRemaining(owned::add);
                for(JsonNode guard:contract.get("guards"))allowed.add(guard.get("name").asText());
                for(Map<String,Object> row:data.get("triggers")){String name=str(row.get("trigger_name"));if((name.toLowerCase(Locale.ROOT).startsWith("hh_")||owned.contains(str(row.get("event_object_table"))))&&!allowed.contains(name))return false;}
                return true;
            }
            if("routines".equals(kind)){for(Map<String,Object> row:data.get("routines"))if(str(row.get("routine_name")).toLowerCase(Locale.ROOT).startsWith("hh_"))return false;return true;}
            for(String group:Arrays.asList("tables","native"))for(JsonNode table:contract.get(group)){
                for(JsonNode key:table.get("keys"))if(!"PRIMARY".equals(key.get("name").asText()))allowed.add(key.get("name").asText());
                if("constraint".equals(kind))for(JsonNode fk:table.get("fks"))allowed.add(fk.get("name").asText());
            }
            String relation="index".equals(kind)?"statistics":"table_constraints",field="index".equals(kind)?"index_name":"constraint_name";
            for(Map<String,Object> row:data.get(relation)){String name=str(row.get(field));if(name.toLowerCase(Locale.ROOT).startsWith("hh_")&&!allowed.contains(name))return false;}
            return true;
        }
        private boolean unknownFollowUp(String kind){
            Set<String> owned=new HashSet<>();contract.get("tables").fieldNames().forEachRemaining(owned::add);
            Set<String> allowed=new HashSet<>();
            if("tables".equals(kind)){
                for(Map<String,Object> row:data.get("tables")){String table=str(row.get("table_name"));if(table.toLowerCase(Locale.ROOT).startsWith("visit_followup_")&&!owned.contains(table))return false;}
                return true;
            }
            if("guards".equals(kind)){
                for(JsonNode guard:contract.get("guards"))allowed.add(guard.get("name").asText());
                for(Map<String,Object> row:data.get("triggers")){String name=str(row.get("trigger_name")),table=str(row.get("event_object_table"));
                    if((name.toLowerCase(Locale.ROOT).startsWith("vf57_")||owned.contains(table)||"visit_record".equals(table)||"notification_delivery_log".equals(table))&&!allowed.contains(name))return false;}
                return true;
            }
            if("routines".equals(kind)){
                for(Map<String,Object> row:data.get("routines"))if(str(row.get("routine_name")).toLowerCase(Locale.ROOT).startsWith("vf57_"))return false;
                return true;
            }
            for(JsonNode table:contract.get("signatures")){
                for(JsonNode key:table.get("keys"))if(!"PRIMARY".equals(key.get("name").asText()))allowed.add(key.get("name").asText());
                if("constraint".equals(kind))for(JsonNode fk:table.get("fks"))allowed.add(fk.get("name").asText());
            }
            String relation="index".equals(kind)?"statistics":"table_constraints",field="index".equals(kind)?"index_name":"constraint_name";
            for(Map<String,Object> row:data.get(relation)){String name=str(row.get(field));if(name.toLowerCase(Locale.ROOT).startsWith("vf57_")&&(!allowed.contains(name)||!owned.contains(str(row.get("table_name")))))return false;}
            return true;
        }
        private boolean one(String relation,Map<String,Object> filter,Map<String,Object> required){List<Map<String,Object>> found=rows(relation,filter);return found.size()==1&&matchesFields(found.get(0),required);}
        private List<Map<String,Object>> rows(String relation,Map<String,Object> filter){List<Map<String,Object>> found=new ArrayList<>();for(Map<String,Object> row:data.get(relation))if(matchesFields(row,filter))found.add(row);return found;}
        private static boolean contains(List<Map<String,Object>> rows,Map<String,Object> expected){for(Map<String,Object> row:rows)if(matchesFields(row,expected))return true;return false;}
        private static boolean matchesFields(Map<String,Object> row,Map<String,Object> expected){for(Map.Entry<String,Object> field:expected.entrySet())if(!equal(row.get(field.getKey()),field.getValue()))return false;return true;}
        private static boolean equal(Object a,Object b){return Objects.equals(str(a),str(b));}
        private static String str(Object value){return value==null?null:String.valueOf(value);}
        private static Object value(JsonNode value){return value==null||value.isNull()?null:value.isNumber()?value.numberValue():value.asText();}
        private static Map<String,Object> map(Object...pairs){return WorkflowData.map(pairs);}
    }
    private static JsonNode manifest(){return readManifest("/household-workflow-schema.json");}
    private static JsonNode followUpHousehold(JsonNode followUp){
        JsonNode full=MANIFEST.deepCopy();
        for(JsonNode replacement:followUp.get("householdGuardOverrides")){
            com.fasterxml.jackson.databind.node.ArrayNode guards=(com.fasterxml.jackson.databind.node.ArrayNode)full.get("guards");boolean found=false;
            for(int i=0;i<guards.size();i++)if(guards.get(i).get("name").asText().equals(replacement.get("name").asText())){guards.set(i,replacement);found=true;break;}
            if(!found)throw new IllegalStateException("Unknown household guard replacement");
        }
        return full;
    }
    /** MySQL serializes a DECIMAL DEFAULT 0 as 0.000 for scale 3.
     * The caller has already checked column type, precision, scale and nullability. */
    static boolean columnDefaultMatches(String dataType,String expectedType,String actualType,String actual,String expected){
        if("decimal".equals(dataType)&&expectedType!=null&&expectedType.matches("decimal\\([1-9][0-9]*,[0-9]+\\)")&&Objects.equals(expectedType,actualType)
                &&actual!=null&&expected!=null&&actual.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")&&expected.matches("[+-]?[0-9]+(?:\\.[0-9]+)?"))
            return new java.math.BigDecimal(actual).compareTo(new java.math.BigDecimal(expected))==0;
        return Objects.equals(actual,expected);
    }

    private static JsonNode readManifest(String resource){
        try(InputStream input=HouseholdWorkflowSchema.class.getResourceAsStream(resource)){
            if(input==null)throw new IllegalStateException("Missing household schema contract");
            return new ObjectMapper().readTree(input);
        }catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
}
