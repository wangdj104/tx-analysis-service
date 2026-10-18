package org.familyhealthcare.service.stockworkflow;

import org.familyhealthcare.service.MedicationStockService;
import org.familyhealthcare.service.archivestock.ArchiveStockNotificationGate;
import org.familyhealthcare.service.workflow.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import static org.familyhealthcare.service.workflow.WorkflowData.*;

/** Source/policy admissions are short transactions. The original candidate owns the exact message facts. */
@Service public class StockWorkflowTrackingGuard {
    private final JdbcTemplate jdbc;
    private final HouseholdAuthorityLocks locks;
    private final HouseholdWorkflowSchema schema;
    private final StockWorkflowProperties properties;
    private final StockWorkflowAuthorizationService auth;
    private final ArchiveStockNotificationGate policy;
    @Autowired private MedicationStockService stockFacts;
    public StockWorkflowTrackingGuard(JdbcTemplate jdbc,HouseholdAuthorityLocks locks,HouseholdWorkflowSchema schema,StockWorkflowProperties properties){
        this.jdbc=jdbc;this.locks=locks;this.schema=schema;this.properties=properties;
        auth=new StockWorkflowAuthorizationService(jdbc);policy=new ArchiveStockNotificationGate(jdbc);
    }
    public static final class Candidate {
        private final long patientId,stockId,recipient;
        private final Map<String,Object> facts;
        private final String factsHash;
        private final ArchiveStockNotificationGate.Token token;
        private Candidate(long patient,long stock,long recipient,Map<String,Object> facts,ArchiveStockNotificationGate.Token token){
            patientId=patient;stockId=stock;this.recipient=recipient;this.facts=Collections.unmodifiableMap(new LinkedHashMap<>(facts));factsHash=hash(json(facts));this.token=token;
        }
        public long getRecipient(){return recipient;}
        public String getContent(){return facts.get("patientName")+": "+facts.get("drugName")+" — "+facts.get("quantity")+" "+facts.get("unit")+" remaining";}
    }
    /** Cycle-local health observation only; each candidate and attempt still checks fresh policy metadata. */
    public org.familyhealthcare.service.archivestock.ArchiveStockNotificationSchema.Status notificationPolicyStatus(){return policy.status();}
    /** A boolean compatibility probe cannot authorize a send or its completion marker. */
    public boolean admitLegacyNotification(long patient,long stock){return candidate(patient,stock)!=null;}
    public Candidate candidate(long patient,long stock){outside();return tx(()->observe(patient,stock,null,null,null));}
    public boolean recheck(Candidate original,Long recipient,Long channel){
        outside();if(original==null||recipient==null||recipient!=original.recipient)return false;
        return tx(()->observe(original.patientId,original.stockId,original,recipient,channel))!=null;
    }
    public int markLegacyNotified(Candidate original,LocalDateTime at){
        outside();if(original==null||at==null)return 0;
        return tx(()->{
            Candidate current=observe(original.patientId,original.stockId,original,original.recipient,null);
            if(current==null)return 0;
            // Source stock is already locked; policy lock was appended last. No new native lock follows it.
            return jdbc.update("UPDATE medication_stock SET notified_at=? WHERE id=? AND patient_id=?",at,original.stockId,original.patientId);
        });
    }
    private Candidate observe(long patient,long stock,Candidate original,Long recipient,Long channel){
        boolean modern=storage();
        List<Long> owners=jdbc.queryForList("SELECT user_id FROM patient WHERE id=?",Long.class,patient);if(owners.size()!=1)return null;
        locks.lockPatientAndSubjects(patient,owners);
        long owner;
        try{owner=auth.owner(patient);auth.requireRead(owner,patient);}catch(WorkflowException denied){if(denied.getStatus()!=403)throw denied;return null;}
        if(recipient!=null&&recipient!=owner)return null;
        List<Long> medication=jdbc.queryForList("SELECT medication_id FROM medication_stock WHERE id=? AND patient_id=?",Long.class,stock,patient);
        if(medication.size()!=1)return null;
        List<Map<String,Object>> medicines=jdbc.queryForList("SELECT id,patient_id,drug_name,is_active FROM medication WHERE id=? AND patient_id=? FOR UPDATE",medication.get(0),patient);
        List<Map<String,Object>> stocks=jdbc.queryForList("SELECT * FROM medication_stock WHERE id=? AND patient_id=? FOR UPDATE",stock,patient);
        if(medicines.size()!=1||stocks.size()!=1)return null;Map<String,Object> s=stocks.get(0),m=medicines.get(0);
        if(id(s.get("medication_id"))!=medication.get(0))return null;
        if(modern&&(id(s.get("workflow_version"))!=0||s.get("current_work_id")!=null))return null;
        Object last=s.get("notified_at");String notified=last==null?null:last.toString();
        if(notified!=null&&notified.startsWith(LocalDateTime.now().toLocalDate().toString()))return null;
        BigDecimal quantity=decimal(s.get("quantity")),warning=decimal(s.get("warning_quantity")),daily=stockFacts.dailyUse(medication.get(0));
        if(quantity==null||warning==null||s.get("warning_days")==null)return null;
        long days=((Number)s.get("warning_days")).longValue();
        boolean low=quantity.compareTo(warning)<=0||daily!=null&&daily.signum()>0&&quantity.compareTo(daily.multiply(BigDecimal.valueOf(days)))<=0;
        Boolean active=ArchiveStockNotificationGate.knownActive(m.get("is_active"));if(!low||Boolean.FALSE.equals(active))return null;
        List<String> names=jdbc.queryForList("SELECT name FROM patient WHERE id=?",String.class,patient);if(names.size()!=1)return null;
        Map<String,Object> facts=map("patientId",patient,"patientName",names.get(0),"stockId",stock,"medicationId",medication.get(0),"ownerId",owner,
                "drugName",m.get("drug_name"),"active",active,"quantity",canonical(quantity),"unit",s.get("unit"),"warningQuantity",canonical(warning),"warningDays",days,
                "dailyUse",daily==null?null:canonical(daily),"low",low,"ownership","LEGACY","recipientRule","CURRENT_OWNER","notifiedAt",notified);
        if(original!=null&&!original.factsHash.equals(hash(json(facts))))return null;
        if(channel!=null){
            List<Map<String,Object>> channels=jdbc.queryForList("SELECT user_id,enabled,channel_type FROM notification_channel WHERE id=?",channel);
            if(channels.size()!=1)return null;Map<String,Object> c=channels.get(0);
            if(id(c.get("user_id"))!=owner||!Boolean.TRUE.equals(ArchiveStockNotificationGate.knownActive(c.get("enabled")))||!Arrays.asList("WEBHOOK","WECHAT_WEBHOOK","DINGTALK_WEBHOOK").contains(c.get("channel_type")))return null;
        }
        // No pre-existing native row locks may be acquired after this call, including in HH-ABSENT mode.
        ArchiveStockNotificationGate.Token token=policy.admit(patient,stock,null,original==null?null:original.token,true);
        if(token==null||token.marked()&&!Boolean.TRUE.equals(active))return null;
        return original==null?new Candidate(patient,stock,owner,facts,token):original;
    }
    private static BigDecimal decimal(Object value){return value==null?null:new BigDecimal(value.toString());}
    private static String canonical(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private boolean storage(){HouseholdWorkflowSchema.StorageReadiness state=schema.inspectStorageMetadata();if(state.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT&&!properties.isEnabled())return false;state.requireStructurallyReady();return true;}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stock notification admission and transport must run outside an ambient transaction.");}
    private <T>T tx(Supplier<T> work){TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx.execute(status->work.get());}
}
