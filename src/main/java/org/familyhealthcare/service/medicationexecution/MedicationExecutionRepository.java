package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.familyhealthcare.service.stockworkflow.StockWorkflowRepository.PreparedConsumption;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionAuthorizationService.denied;
/** Fixed domain SQL, complete related-row discovery, and explicit typed source links. */
@Repository
public class MedicationExecutionRepository {
    final JdbcTemplate jdbc;
    final HouseholdAuthorityLocks locks;
    final HouseholdWorkflowSchema schema;
    final MedicationExecutionAuthorizationService auth;
    public MedicationExecutionRepository(JdbcTemplate jdbc,HouseholdAuthorityLocks locks,HouseholdWorkflowSchema schema,MedicationExecutionAuthorizationService auth) {
        this.jdbc=jdbc;
        this.locks=locks;
        this.schema=schema;
        this.auth=auth;
    }
    /** 通过仓储方法进入实际目标对象，避免从 Spring 代理实例读取依赖字段。 */
    public HouseholdWorkflowSchema.StorageReadiness inspectStorageMetadata() {
        return schema.inspectStorageMetadata();
    }
    public Map<String,Object> execution(long id) {
        return one("SELECT * FROM medication_execution WHERE id=?",id);
    }
    public Map<String,Object> reconciliation(long id) {
        return one("SELECT * FROM medication_reconciliation WHERE id=?",id);
    }
    public Map<String,Object> revision(long id) {
        return one("SELECT * FROM medication_reconciliation_revision WHERE id=?",id);
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
                String column=md.getColumnLabel(n).toLowerCase(Locale.ROOT);
                if(value instanceof Timestamp&&(column.endsWith("_utc")||Arrays.asList("effective_at","end_at","ended_at","follow_up_due_at","due_at","next_attempt_at").contains(column)))value=rs.getTimestamp(n,utc()).toInstant();
                row.put(md.getColumnLabel(n).toLowerCase(Locale.ROOT),value);
            }
            return row;
        });
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
                String column=md.getColumnLabel(n).toLowerCase(Locale.ROOT);
                if(value instanceof Timestamp&&(column.endsWith("_utc")||Arrays.asList("effective_at","end_at","ended_at","follow_up_due_at","due_at","next_attempt_at").contains(column)))value=rs.getTimestamp(n,utc()).toInstant();
                row.put(md.getColumnLabel(n).toLowerCase(Locale.ROOT),value);
            }
            return row;
        });
    }
    /** One current/explicit evidence admission. Old proposals/revisions are never aggregate closure input. */
    public HouseholdAuthorityLocks.LockedAuthority lockCommand(long actor,long patient,long medication,MedicationExecutionContracts.ReconciliationCommand command) {
        return admit(actor,patient,Collections.singleton(medication),command.source,command.selectedVerifierId,
        command.reconciliationId,command.revisionId,command.meta.commandKey,null,null,null,Collections.emptyList());
    }
    public HouseholdAuthorityLocks.LockedAuthority lockNotification(long actor,long patient,long medication,long event,long notification,Long recipient) {
        Map<String,Object> e=one("SELECT intake_id,action_id FROM medication_execution_event WHERE id=?",event);
        if(e==null)throw conflict("NOTIFICATION_INVALID");
        return admit(actor,patient,Collections.singleton(medication),null,null,null,null,null,event,notification,recipient,Collections.emptyList(),false,number(e.get("intake_id")),number(e.get("action_id")),null);
    }
    // 仅服务器 worker 能进入；不能将接收人或 owner 冒充原事件的人类操作者。
    void lockSystemNotification(long patient,long medication,long event,long notification,Long recipient) {
        Map<String,Object> e=one("SELECT * FROM medication_execution_event WHERE id=?",event);
        requireSystemNotificationEvent(e,patient);
        admit(null,patient,Collections.singleton(medication),null,null,null,null,null,event,notification,recipient,Collections.emptyList(),false,number(e.get("intake_id")),null,null);
    }
    private static Long number(Object value){return value==null?null:id(value);}
    void requireSystemNotificationEvent(Map<String,Object> e,long patient){
        if(e==null||id(e.get("patient_id"))!=patient||!"SYSTEM".equals(e.get("actor_kind"))||e.get("actor_id")!=null||!Arrays.asList("BOUNDARY_APPLIED","INTERVAL_ENDED","TIME_UNRESOLVED","DUE").contains(e.get("type"))||!hash((String)e.get("evidence_json")).equals(e.get("evidence_hash")))throw conflict("NOTIFICATION_INVALID");
        if("DUE".equals(e.get("type"))) {
            Instant due=MedicationBoundaryService.at(e.get("due_at"));
            String key=due==null?null:"MEDICATION_EXECUTION:"+e.get("execution_id")+":"+e.get("intake_id")+":"+e.get("cycle")+":"+java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(java.time.ZoneOffset.UTC).format(due);
            if(e.get("intake_id")==null||!"DUE".equals(e.get("actor_relation"))||!Objects.equals(key,e.get("due_key"))||due==null)throw conflict("NOTIFICATION_INVALID");
        }
        else if(!"SYSTEM".equals(e.get("actor_relation"))||e.get("intake_id")!=null||e.get("action_id")!=null||e.get("due_at")!=null||e.get("due_key")!=null||e.get("reconciliation_id")==null||e.get("revision_id")==null)throw conflict("NOTIFICATION_INVALID");
    }
    public HouseholdAuthorityLocks.LockedAuthority lockPublication(long actor,long patient,Collection<Long> medications,Collection<Long> activeHeaders) {
        return admit(actor,patient,medications,null,null,null,null,null,null,null,null,activeHeaders);
    }
    public HouseholdAuthorityLocks.LockedAuthority lockInterval(long actor,long patient,long medication,String commandKey,Long reconciliation,Long revision) {
        return admit(actor,patient,Collections.singleton(medication),null,null,reconciliation,revision,commandKey,null,null,null,Collections.emptyList(),true);
    }
    private HouseholdAuthorityLocks.LockedAuthority admit(long actor,long patient,Collection<Long> medications,
    MedicationExecutionContracts.SourceReference extra,Long verifier,Long explicitProposal,Long explicitRevision,
    String commandKey,Long explicitEvent,Long notification,Long recipient,Collection<Long> activeHeaders) {
        return admit(actor,patient,medications,extra,verifier,explicitProposal,explicitRevision,commandKey,explicitEvent,notification,recipient,activeHeaders,false);
    }
    /** One narrow generator admission; all existing relationships share its original deadline. */
    IntervalAdmission lockGeneration(long actor,long patient,long medication,long proposal) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        HouseholdAuthorityLocks.LockedAuthority token=admit(actor,patient,Collections.singleton(medication),null,null,proposal,null,null,null,null,null,Collections.emptyList(),true);
        Map<String,Object> selected=reconciliation(proposal);
        if(selected==null||id(selected.get("patient_id"))!=patient)throw denied();
        IntervalAdmission admission=new IntervalAdmission(token,actor,patient,id(selected.get("execution_id")),proposal,deadline);
        admission.check(admission.execution);
        return admission;
    }
    final class IntervalAdmission {
        private final HouseholdAuthorityLocks.LockedAuthority token;
        private final long actor,patient,execution,proposal,deadline;
        private final Connection connection;
        private final Thread thread=Thread.currentThread();
        private boolean live=true;
        private IntervalAdmission(HouseholdAuthorityLocks.LockedAuthority token,long actor,long patient,long execution,long proposal,long deadline) {
            this.token=token;this.actor=actor;this.patient=patient;this.execution=execution;this.proposal=proposal;this.deadline=deadline;
            connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,jdbc.getDataSource());
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCompletion(int status){finish();}
                @Override public void suspend(){finish();}
            });
        }
        void check(long expectedExecution) {
            if(!live||thread!=Thread.currentThread()||execution!=expectedExecution)throw conflict("ADMISSION_CONTEXT_CHANGED");
            locks.requireLive(token,patient,actor);
            Connection current=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());
            try{if(connection!=current)throw conflict("ADMISSION_CONTEXT_CHANGED");}finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(current,jdbc.getDataSource());}
            if(System.nanoTime()>deadline)throw admissionLimit();
            Map<String,Object> p=reconciliation(proposal),e=execution(execution);
            if(p==null||e==null||id(p.get("execution_id"))!=execution||id(p.get("patient_id"))!=patient||id(e.get("patient_id"))!=patient)throw denied();
            auth.requireWrite(actor,patient);
        }
        void finish(){live=false;}
    }
    public HouseholdAuthorityLocks.LockedAuthority lockIntake(long actor,long patient,long medication,String key,long intake,Long original,PreparedConsumption stock) {
        return admit(actor,patient,Collections.singleton(medication),null,null,null,null,key,null,null,null,Collections.emptyList(),false,intake,original,stock);
    }
    private HouseholdAuthorityLocks.LockedAuthority admit(long actor,long patient,Collection<Long> medications,
    MedicationExecutionContracts.SourceReference extra,Long verifier,Long explicitProposal,Long explicitRevision,
    String commandKey,Long explicitEvent,Long notification,Long recipient,Collection<Long> activeHeaders,boolean interval) {
        return admit(actor,patient,medications,extra,verifier,explicitProposal,explicitRevision,commandKey,explicitEvent,notification,recipient,activeHeaders,interval,null,null,null);
    }
    private HouseholdAuthorityLocks.LockedAuthority admit(Long actor,long patient,Collection<Long> medications,
    MedicationExecutionContracts.SourceReference extra,Long verifier,Long explicitProposal,Long explicitRevision,
    String commandKey,Long explicitEvent,Long notification,Long recipient,Collection<Long> activeHeaders,boolean interval,Long intake,Long original,PreparedConsumption stock) {
        mutationTransaction(jdbc);
        schema.inspectStorageMetadata().requireStructurallyReady();
        Admission graph=new Admission();
        graph.stock=stock;
        if(one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null)throw denied();
        graph.check();
        if(actor!=null)graph.users.add(actor);else if(explicitEvent==null||notification==null||commandKey!=null||stock!=null)throw conflict("NOTIFICATION_INVALID");
        if(stock!=null){for(Map.Entry<String,Collection<Long>> entry:stock.selectedRows().entrySet())for(long row:entry.getValue())graph.add(entry.getKey(),row);graph.users.addAll(stock.subjects());}
        graph.add("medication_intake",intake);graph.add("care_intake_action",original);
        if(verifier!=null)graph.users.add(verifier);
        if(recipient!=null)graph.users.add(recipient);
        for(long medication:medications)graph.add("medication",medication);
        for(long header:activeHeaders)graph.add("electronic_prescription",header);
        if(extra!=null&&extra.prescriptionId!=null) {
            graph.add("electronic_prescription",extra.prescriptionId);
            graph.add("electronic_prescription_item",extra.itemId);
        }
        graph.add("medication_reconciliation",explicitProposal);
        graph.add("medication_reconciliation_revision",explicitRevision);
        graph.add("medication_execution_event",explicitEvent);
        graph.add("medication_execution_notification",notification);
        Map<String,Object> replay=null;
        if(commandKey!=null) {
            Map<String,Object> c=one("SELECT id,execution_id,result_json FROM medication_execution_command WHERE actor_id=? AND command_key=?",actor,commandKey);
            if(c!=null) {
                replay=object((String)c.get("result_json"));
                MedicationExecutionContracts.requireMinimalReceipt(replay);
                if(c.get("execution_id")==null||id(c.get("execution_id"))!=id(replay.get("executionId")))throw conflict("RECEIPT_INVALID");
                graph.add("medication_execution_command",c.get("id"));
                graph.add("medication_reconciliation",replay.get("reconciliationId"));
                graph.add("medication_reconciliation_revision",replay.get("revisionId"));
                graph.add("medication_execution_event",replay.get("eventId"));
                graph.add("medication_intake",replay.get("intakeId"));graph.add("care_intake_action",replay.get("actionId"));
            }
        }
        for(long medication:graph.ids("medication")) {
            Map<String,Object> med=one("SELECT id,patient_id FROM medication WHERE id=?",medication);
            if(med==null||id(med.get("patient_id"))!=patient)throw denied();
            Map<String,Object> e=one("SELECT id,current_reconciliation_id,pending_reconciliation_id FROM medication_execution WHERE patient_id=? AND medication_id=?",patient,medication);
            if(e!=null) {
                graph.add("medication_execution",e.get("id"));
                graph.add("medication_reconciliation",e.get("current_reconciliation_id"));
                graph.add("medication_reconciliation",e.get("pending_reconciliation_id"));
            }
        }
        if(interval)for(long execution:graph.ids("medication_execution")){
            Map<String,Object> e=execution(execution);if(e.get("pending_reconciliation_id")==null)continue;Map<String,Object> pending=reconciliation(id(e.get("pending_reconciliation_id")));if(!Arrays.asList("READY","SCHEDULED").contains(pending.get("state")))continue;Map<String,Object> v=revision(id(pending.get("current_revision_id")));
            for(Map<String,Object> i:rows("SELECT id FROM medication_intake WHERE execution_id=? AND reconciliation_id IS NOT NULL AND scheduled_at_utc>=? AND status IN ('PENDING','SNOOZED','MISSED') ORDER BY scheduled_at_utc,id LIMIT 51",execution,v.get("effective_at")))graph.add("medication_intake",i.get("id"));
        }
        for(long selectedIntake:new ArrayList<>(graph.ids("medication_intake"))){Map<String,Object> row=one("SELECT * FROM medication_intake WHERE id=?",selectedIntake);if(row==null||id(row.get("patient_id"))!=patient||!medications.contains(id(row.get("medication_id"))))throw denied();graph.add("medication_execution",row.get("execution_id"));graph.add("medication_reconciliation",row.get("reconciliation_id"));graph.add("medication_reminder",row.get("reminder_id"));graph.add("care_intake_action",row.get("latest_action_id"));}
        for(long action:new ArrayList<>(graph.ids("care_intake_action"))){Map<String,Object> row=one("SELECT * FROM care_intake_action WHERE id=?",action);if(row==null||id(row.get("patient_id"))!=patient||!graph.ids("medication_intake").contains(id(row.get("intake_id"))))throw denied();graph.add("medication_execution_event",row.get("execution_event_id"));graph.user(row.get("actor_id"));}
        if(interval)for(long medication:medications) {
            for(Map<String,Object> row:rows("SELECT id FROM medication_reminder WHERE patient_id=? AND medication_id=? AND (workflow_version=0 OR enabled=1) ORDER BY id LIMIT 51",patient,medication))graph.add("medication_reminder",row.get("id"));
            for(long execution:graph.ids("medication_execution"))for(Map<String,Object> row:rows("SELECT id FROM care_item WHERE medication_execution_id=? AND status IN ('SCHEDULED','ACTIVE') ORDER BY id LIMIT 51",execution))graph.add("care_item",row.get("id"));
        }
        if(interval&&explicitProposal!=null)for(Map<String,Object> row:rows("SELECT id FROM care_item WHERE patient_id=? AND medication_reconciliation_id=? ORDER BY id LIMIT 51",patient,explicitProposal))graph.add("care_item",row.get("id"));
        if(interval&&explicitProposal!=null)for(Map<String,Object> row:rows("SELECT id FROM medication_reminder WHERE patient_id=? AND reconciliation_id=? ORDER BY id LIMIT 25",patient,explicitProposal))graph.add("medication_reminder",row.get("id"));
        for(long reminder:new ArrayList<>(graph.ids("medication_reminder"))) {
            Map<String,Object> r=one("SELECT patient_id,medication_id,execution_id,reconciliation_id,order_id FROM medication_reminder WHERE id=?",reminder);
            if(r==null||id(r.get("patient_id"))!=patient||!medications.contains(id(r.get("medication_id"))))throw denied();
            graph.add("medication_execution",r.get("execution_id"));graph.add("medication_reconciliation",r.get("reconciliation_id"));graph.add("care_item",r.get("order_id"));
        }
        if(replay!=null&&!graph.ids("medication_execution").contains(id(replay.get("executionId"))))throw conflict("RECEIPT_INVALID");
        for(long event:graph.ids("medication_execution_event")) {
            Map<String,Object> row=one("SELECT patient_id,execution_id,actor_id,reconciliation_id,revision_id,intake_id,action_id FROM medication_execution_event WHERE id=?",event);
            if(row==null||id(row.get("patient_id"))!=patient||!graph.ids("medication_execution").contains(id(row.get("execution_id"))))throw denied();
            graph.add("medication_reconciliation",row.get("reconciliation_id"));
            graph.add("medication_reconciliation_revision",row.get("revision_id"));
            graph.user(row.get("actor_id"));
            graph.add("medication_intake",row.get("intake_id"));graph.add("care_intake_action",row.get("action_id"));
        }
        // Explicit old command/event revisions add only their own typed proposal, not its revision history.
        for(long revision:new ArrayList<>(graph.ids("medication_reconciliation_revision"))) {
            Map<String,Object> row=one("SELECT patient_id,execution_id,reconciliation_id FROM medication_reconciliation_revision WHERE id=?",revision);
            if(row==null||id(row.get("patient_id"))!=patient||!graph.ids("medication_execution").contains(id(row.get("execution_id"))))throw denied();
            graph.add("medication_reconciliation",row.get("reconciliation_id"));
        }
        for(long proposal:graph.ids("medication_reconciliation")) {
            Map<String,Object> row=one("SELECT patient_id,execution_id,current_revision_id,verified_revision_id,scheduled_revision_id,selected_verifier_id,verified_by,scheduled_by FROM medication_reconciliation WHERE id=?",proposal);
            if(row==null||id(row.get("patient_id"))!=patient||!graph.ids("medication_execution").contains(id(row.get("execution_id"))))throw denied();
            for(String field:Arrays.asList("current_revision_id","verified_revision_id","scheduled_revision_id"))graph.add("medication_reconciliation_revision",row.get(field));
            for(String field:Arrays.asList("selected_verifier_id","verified_by","scheduled_by"))graph.user(row.get(field));
        }
        for(long revision:graph.ids("medication_reconciliation_revision")) {
            Map<String,Object> row=one("SELECT patient_id,execution_id,reconciliation_id,prescription_id,prescription_item_id,transcribed_by FROM medication_reconciliation_revision WHERE id=?",revision);
            if(row==null||id(row.get("patient_id"))!=patient||!graph.ids("medication_execution").contains(id(row.get("execution_id")))||!graph.ids("medication_reconciliation").contains(id(row.get("reconciliation_id"))))throw denied();
            graph.add("electronic_prescription",row.get("prescription_id"));
            graph.add("electronic_prescription_item",row.get("prescription_item_id"));
            graph.user(row.get("transcribed_by"));
        }
        for(long header:graph.ids("electronic_prescription")) {
            Map<String,Object> row=one("SELECT patient_id,doctor_user_id FROM electronic_prescription WHERE id=?",header);
            if(row==null||id(row.get("patient_id"))!=patient)throw denied();
            graph.user(row.get("doctor_user_id"));
        }
        graph.check();
        Map<String,Object> serverEvent=actor==null?one("SELECT * FROM medication_execution_event WHERE id=?",explicitEvent):null;
        if(actor==null)requireSystemNotificationEvent(serverEvent,patient);
        HouseholdAuthorityLocks.MedicationNotificationAuthority serverToken=actor==null?locks.lockMedicationNotification(patient,id(serverEvent.get("execution_id")),explicitEvent,notification,graph.users,graph.deadline):null;
        HouseholdAuthorityLocks.LockedAuthority token=actor==null?null:locks.lockPatientAndSubjects(patient,graph.users);
        graph.check();
        // Fixed resource order. The indirect items and command rows count toward the same total ceiling,
        // but are explicitly bound below because the shared schema helper does not own these table shapes.
        for(Map.Entry<String,SortedSet<Long>> entry:graph.rows.entrySet())for(long row:entry.getValue()) {
            graph.check();
            if(one("SELECT id FROM "+entry.getKey()+" WHERE id=? FOR UPDATE",row)==null)throw denied();
        }
        for(long item:graph.ids("electronic_prescription_item")) {
            Map<String,Object> row=one("SELECT prescription_id FROM electronic_prescription_item WHERE id=?",item);
            if(row==null||!graph.ids("electronic_prescription").contains(id(row.get("prescription_id"))))throw denied();
        }
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();
        for(Map.Entry<String,SortedSet<Long>> entry:graph.rows.entrySet())if(!entry.getValue().isEmpty()&&!Arrays.asList("electronic_prescription_item","medication_execution_command","stock_command").contains(entry.getKey()))selected.put(entry.getKey(),entry.getValue());
        if(actor==null) {
            requireSystemNotificationEvent(one("SELECT * FROM medication_execution_event WHERE id=?",explicitEvent),patient);
            schema.requireMedicationNotificationRows(serverToken,patient,id(serverEvent.get("execution_id")),explicitEvent,notification,selected);
        }
        else if(!selected.isEmpty())schema.requireSelectedRows(token,patient,actor,selected);
        // Exactly once; no renewed batches.
        graph.check();
        for(long revision:graph.ids("medication_reconciliation_revision")) {
            requireRevisionLinks(revision(revision),patient);
            graph.check();
        }
        if(replay!=null) {
            Map<String,Object> event=one("SELECT execution_id,reconciliation_id,revision_id,aggregate_version,intake_id,action_id FROM medication_execution_event WHERE id=?",replay.get("eventId"));
            if(event==null||id(event.get("execution_id"))!=id(replay.get("executionId"))||id(event.get("aggregate_version"))!=id(replay.get("version")))throw conflict("RECEIPT_INVALID");
            if(replay.containsKey("intakeId")){if(!Objects.equals(event.get("intake_id"),replay.get("intakeId"))||!Objects.equals(event.get("action_id"),replay.get("actionId")))throw conflict("RECEIPT_INVALID");}
            else if(!Objects.equals(event.get("reconciliation_id"),replay.get("reconciliationId"))||!Objects.equals(event.get("revision_id"),replay.get("revisionId")))throw conflict("RECEIPT_INVALID");
        }
        if(actor!=null)auth.requireWrite(actor,patient);
        graph.check();
        if(stock!=null)stock.bind(token);
        return token;
    }
    private static final class Admission {
        private final Map<String,SortedSet<Long>> rows=new LinkedHashMap<>();
        private final SortedSet<Long> users=new TreeSet<>();
        private final long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        private int total;
        private PreparedConsumption stock;
        Admission() {
            for(String table:Arrays.asList("electronic_prescription","electronic_prescription_item","medication","medication_execution","medication_reconciliation","medication_reconciliation_revision","care_item","medication_reminder","medication_intake","care_intake_action","medication_stock","stock_work","stock_count","stock_intake_resolution","medication_execution_event","stock_event","medication_execution_command","stock_command","medication_execution_notification","stock_notification"))rows.put(table,new TreeSet<>());
        }
        SortedSet<Long> ids(String table) {
            return rows.get(table);
        }
        void add(String table,Object value) {
            check();
            if(value!=null&&ids(table).add(id(value))&&++total>50)throw admissionLimit();
        }
        void user(Object value) {
            if(value!=null)users.add(id(value));
            if(users.size()>50)throw admissionLimit();
            check();
        }
        void check() {
            if(stock!=null)stock.check();
            if(System.nanoTime()>deadline)throw new WorkflowException(503,"ADMISSION_TIMEOUT","Current related records could not be checked in time. Refresh and retry; no changes were applied.");
        }
    }
    public static WorkflowException admissionLimit() {
        return new WorkflowException(409,"ADMISSION_LIMIT","Too many current related records for one safe review. Resolve or narrow pending reviews and retry; no changes were applied.");
    }
    public void requireRevisionLinks(Map<String,Object> v,long patient) {
        if(v==null||id(v.get("patient_id"))!=patient)throw denied();
        Map<String,Object> r=reconciliation(id(v.get("reconciliation_id"))),e=execution(id(v.get("execution_id")));
        if(r==null||e==null||id(r.get("patient_id"))!=patient||id(e.get("patient_id"))!=patient||id(r.get("execution_id"))!=id(e.get("id")))throw denied();
        if(!hash((String)v.get("source_json")).equals(v.get("source_hash"))||!hash((String)v.get("mapping_json")).equals(v.get("mapping_hash")))throw conflict("EVIDENCE_CHANGED");
        String revisionHash=hash(json(map("sourceHash",v.get("source_hash"),"mappingHash",v.get("mapping_hash"),"revisionNo",((Number)v.get("revision_no")).intValue(),"transcribedBy",id(v.get("transcribed_by")))));
        if(!revisionHash.equals(v.get("revision_hash")))throw conflict("EVIDENCE_CHANGED");
        try {
            MedicationExecutionContracts.parseMapping(object((String)v.get("mapping_json")));
            if("MANUAL".equals(v.get("source_kind")))MedicationExecutionContracts.parseSource(object((String)v.get("source_json")));
        }
        catch(WorkflowException invalid) {
            throw conflict("EVIDENCE_CHANGED");
        }
        if("PRESCRIPTION".equals(v.get("source_kind"))) {
            Map<String,Object> source=sourceSnapshot(patient,id(v.get("prescription_id")),id(v.get("prescription_item_id")),((Number)v.get("source_version")).intValue(),false);
            if(!hash(json(source)).equals(v.get("source_hash")))throw conflict("SOURCE_CHANGED");
        }
    }
    public Map<String,Object> sourceSnapshot(long patient,long prescription,long item,int version,boolean active) {
        Map<String,Object> p=one("SELECT id,patient_id,doctor_user_id,visit_id,version_no,status,instructions,published_at FROM electronic_prescription WHERE id=?",prescription),i=one("SELECT id,prescription_id,drug_name,dosage,frequency,administration_route,duration_days,remark FROM electronic_prescription_item WHERE id=?",item);
        if(p==null||i==null||id(p.get("patient_id"))!=patient||id(i.get("prescription_id"))!=prescription||((Number)p.get("version_no")).intValue()!=version)throw denied();
        if(active&&!"ACTIVE".equals(p.get("status")))throw conflict("SOURCE_CHANGED");
        p.remove("status");
        if(p.get("published_at")!=null)p.put("published_at",p.get("published_at").toString());
        return map("kind","PRESCRIPTION","header",p,"item",i);
    }
    /** Unforgeable server INSERT receipt. No public constructor and no client-deserialized identity. */
    public static final class InsertedAction {
        private final long id;private final java.sql.Connection connection;private final Thread thread=Thread.currentThread();private boolean live=true;
        private InsertedAction(long id,JdbcTemplate jdbc){this.id=id;connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,jdbc.getDataSource());org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCompletion(int status){live=false;}@Override public void suspend(){live=false;}});}
        public long id(){return id;}
        public void requireCurrent(JdbcTemplate jdbc){mutationTransaction(jdbc);java.sql.Connection current=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource());try{if(!live||thread!=Thread.currentThread()||connection!=current)throw conflict("CONSUMPTION_CONTEXT_CHANGED");}finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(current,jdbc.getDataSource());}}
    }
    public InsertedAction insertAction(String sql,Object...args){mutationTransaction(jdbc);if(!sql.startsWith("INSERT INTO care_intake_action("))throw conflict("CONSUMPTION_CONTEXT_CHANGED");return new InsertedAction(insert(sql,args),jdbc);}
    public long insert(String sql,Object...args) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(c-> {
            PreparedStatement ps=c.prepareStatement(sql,new String[]{"id"});
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
    public static WorkflowException conflict(String code) {
        return new WorkflowException(409,code,"The current reconciliation changed. Refresh and review it.");
    }
}
