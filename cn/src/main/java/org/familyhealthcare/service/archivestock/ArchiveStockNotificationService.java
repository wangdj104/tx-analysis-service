package org.familyhealthcare.service.archivestock;

import org.familyhealthcare.service.MedicationStockService;
import org.familyhealthcare.service.stockworkflow.*;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.familyhealthcare.service.archivestock.ArchiveStockNotificationContracts.*;

/** Per-item current STOCK WRITE review. Independent lock order: patient/authority, medication, stock, policy, receipt. */
@Service
public class ArchiveStockNotificationService {
    private final JdbcTemplate jdbc;
    private final HouseholdAuthorityLocks locks;
    private final StockWorkflowAuthorizationService auth;
    private final ArchiveStockNotificationSchema schema;
    private final ArchiveStockNotificationRepository policies;
    private final HouseholdWorkflowSchema household;
    private final StockWorkflowProperties properties;
    private final MedicationStockService stocks;
    public ArchiveStockNotificationService(JdbcTemplate jdbc,HouseholdAuthorityLocks locks,StockWorkflowAuthorizationService auth,
            ArchiveStockNotificationSchema schema,ArchiveStockNotificationRepository policies,HouseholdWorkflowSchema household,
            StockWorkflowProperties properties,MedicationStockService stocks){
        this.jdbc=jdbc;this.locks=locks;this.auth=auth;this.schema=schema;this.policies=policies;this.household=household;this.properties=properties;this.stocks=stocks;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> current(long actor,long patient,long stock){
        Source source=lockSource(actor,patient,stock,false);Map<String,Object> facts=facts(source);
        ArchiveStockNotificationRepository.State state=policies.state(stock,true);
        Map<String,Object> out=state==null?WorkflowData.map("stockId",stock,"marked",false):state.asMap();
        out.put("schemaStatus","READY");out.put("patientId",patient);out.put("currentFacts",facts);
        out.put("currentFactsHash",Boolean.TRUE.equals(facts.get("reviewReady"))?WorkflowData.hash(WorkflowData.json(facts)):null);out.put("canWrite",auth.canWrite(actor,patient));
        return out;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(long actor,String raw){
        Command command=ArchiveStockNotificationContracts.command(raw);
        Source source=lockSource(actor,command.patientId,command.stockId,true);
        ArchiveStockNotificationRepository.State state=policies.state(command.stockId,true);
        if(state==null)throw conflict("NOT_RESTORED");
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        return ArchiveStockNotificationCommandStore.execute(jdbc,actor,command,now,()->{
            if(command.expectedControlVersion!=state.controlVersion)throw conflict("VERSION_CHANGED");
            if((command.operation==Operation.ENABLE)==state.enabled)throw conflict("NO_CHANGE");
            next(state.controlVersion);
            if(command.operation==Operation.ENABLE){
                // Replay is already resolved. These are nonlocking observations of the source rows
                // already locked above and the existing ORDER-derived rule; never add a native lock here.
                Map<String,Object> current=facts(source);
                if(!Boolean.TRUE.equals(current.get("reviewReady")))throw conflict("REVIEW_UNAVAILABLE");
                if(!command.currentFactsHash.equals(WorkflowData.hash(WorkflowData.json(current))))throw conflict("FACTS_CHANGED");
            }
        },()->{
            policies.transition(state,command,source.stockVersion,now);
            return ArchiveStockNotificationContracts.receipt(command.stockId,command.operation,next(state.controlVersion),now);
        });
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> receipt(long actor,long patient,long stock,String commandKey){
        lockSource(actor,patient,stock,false);
        if(policies.state(stock,true)==null)throw conflict("NOT_RESTORED");
        return ArchiveStockNotificationCommandStore.lookup(jdbc,actor,stock,commandKey);
    }
    private Source lockSource(long actor,long patient,long stock,boolean write){
        WorkflowData.mutationTransaction(jdbc);id(actor);id(patient);id(stock);
        auth.requireRead(actor,patient);schema.requireReady(jdbc);
        HouseholdWorkflowSchema.StorageReadiness hh=household.inspectStorageMetadata();
        boolean modern=!(hh.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT&&!properties.isEnabled());
        if(modern)hh.requireStructurallyReady();
        locks.lockPatientAndSubjects(patient,Collections.singleton(actor));
        // No unlocked discovery hint is authority; identity is checked again after source locks.
        List<Long> medications=jdbc.queryForList("SELECT medication_id FROM medication_stock WHERE id=? AND patient_id=?",Long.class,stock,patient);
        if(medications.size()!=1)throw StockWorkflowAuthorizationService.denied();long medication=id(medications.get(0));
        List<Map<String,Object>> ms=jdbc.queryForList("SELECT id,patient_id,drug_name,is_active FROM medication WHERE id=? AND patient_id=? FOR UPDATE",medication,patient);
        List<Map<String,Object>> ss=jdbc.queryForList("SELECT * FROM medication_stock WHERE id=? AND patient_id=? FOR UPDATE",stock,patient);
        if(ms.size()!=1||ss.size()!=1||id(ss.get(0).get("medication_id"))!=medication)throw StockWorkflowAuthorizationService.denied();
        if(write)auth.requireWrite(actor,patient);else auth.requireRead(actor,patient);
        Map<String,Object> s=ss.get(0);boolean managed=modern&&(number(s.get("workflow_version"))!=0||s.get("current_work_id")!=null);
        if(managed&&(number(s.get("workflow_version"))!=1||s.get("current_work_id")==null))throw integrity();
        long stockVersion=managed?version(s.get("lock_version")):0;
        return new Source(patient,stock,medication,modern,managed,stockVersion,s,ms.get(0));
    }
    /** Equality of displayed current values, not proof that no native A-to-B-to-A edit occurred. */
    private Map<String,Object> facts(Source source){
        Map<String,Object> s=source.stock,m=source.medication;
        BigDecimal daily=stocks.dailyUse(source.medicationId),quantity=nullableDecimal(s.get("quantity")),warning=nullableDecimal(s.get("warning_quantity"));
        Long days=s.get("warning_days")==null?null:number(s.get("warning_days"));
        Boolean active=active(m.get("is_active"));
        List<String> blocked=new ArrayList<>();
        if(active==null)blocked.add("MEDICATION_ACTIVITY_UNKNOWN");
        if(quantity==null)blocked.add("QUANTITY_UNKNOWN");
        if(warning==null)blocked.add("WARNING_QUANTITY_UNKNOWN");
        if(days==null)blocked.add("WARNING_DAYS_UNKNOWN");
        if(!(s.get("unit") instanceof String)||((String)s.get("unit")).trim().isEmpty())blocked.add("UNIT_UNKNOWN");
        Boolean low=quantity==null||warning==null||days==null?null:quantity.compareTo(warning)<=0||daily!=null&&daily.signum()>0&&quantity.compareTo(daily.multiply(BigDecimal.valueOf(days)))<=0;
        Map<String,Object> rule=null;
        if(source.managed){
            Map<String,Object> work=new StockWorkflowRepository(jdbc,null,null,null).work(id(s.get("current_work_id")));
            if(work==null||id(work.get("stock_id"))!=source.stockId||id(work.get("patient_id"))!=source.patientId)throw integrity();
            rule=WorkflowData.map("workId",id(work.get("id")),"state",work.get("state"),"assignedUserId",work.get("assigned_user_id"),
                    "ruleEventId",work.get("current_rule_event_id"),"ruleVersion",work.get("current_rule_version"),"ruleHash",work.get("current_rule_hash"));
        }
        List<String> names=jdbc.queryForList("SELECT name FROM patient WHERE id=?",String.class,source.patientId);
        if(names.size()!=1)throw integrity();
        return WorkflowData.map("patientId",source.patientId,"patientName",names.get(0),"stockId",source.stockId,"medicationId",source.medicationId,
                "drugName",m.get("drug_name"),"active",active,"quantity",quantity==null?null:canonical(quantity),"unit",s.get("unit"),
                "warningQuantity",warning==null?null:canonical(warning),"warningDays",days,"dailyUse",daily==null?null:canonical(daily),"low",low,
                "ownership",source.managed?"MANAGED":"LEGACY","recipientRule",source.managed?"CURRENT_OWNER_AND_CURRENT_ASSIGNED_WRITER":"CURRENT_OWNER",
                "ownerId",id(auth.owner(source.patientId)),"currentRule",rule,"reviewReady",blocked.isEmpty(),"reviewBlockedReasons",blocked);
    }
    private static long number(Object value){if(!(value instanceof Number))throw integrity();return ((Number)value).longValue();}
    private static Boolean active(Object value){
        if(value==null)return null;if(value instanceof Boolean)return (Boolean)value;
        if(!(value instanceof Number))return null;
        BigDecimal number=decimal(value);if(number.compareTo(BigDecimal.ZERO)==0)return false;if(number.compareTo(BigDecimal.ONE)==0)return true;return null;
    }
    private static BigDecimal nullableDecimal(Object value){return value==null?null:decimal(value);}
    private static BigDecimal decimal(Object value){try{return new BigDecimal(value.toString());}catch(RuntimeException e){throw integrity();}}
    private static String canonical(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static final class Source {
        final long patientId,stockId,medicationId,stockVersion;final boolean modern,managed;final Map<String,Object> stock,medication;
        Source(long patient,long stock,long medication,boolean modern,boolean managed,long version,Map<String,Object> s,Map<String,Object> m){patientId=patient;stockId=stock;medicationId=medication;this.modern=modern;this.managed=managed;stockVersion=version;this.stock=s;this.medication=m;}
    }
}
