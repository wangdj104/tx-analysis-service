package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class MedicationStockService {
    @Autowired private org.familyhealthcare.service.stockworkflow.StockWorkflowQueryService stockQueries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataScopeHelper scope;
    @Autowired private MedicationMapper medications;
    @Autowired private CareItemMapper items;
    @Autowired private org.familyhealthcare.service.workflow.HouseholdAuthorityLocks stockLocks;
    @Autowired private org.familyhealthcare.service.workflow.HouseholdWorkflowSchema stockSchema;
    @Autowired private org.familyhealthcare.service.stockworkflow.StockWorkflowProperties stockProperties;

    public List<Map<String,Object>> list(Long pid) {
        scope.requirePatient(pid);
        boolean modern=workflowStorage();List<Map<String,Object>> rows=snapshot(pid,modern);
        if(modern)rows=stockQueries.legacyProjection(scope.requireUserId(),pid,rows);
        new org.familyhealthcare.service.archivestock.ArchiveStockNotificationProjection(jdbc).append(scope.requireUserId(),pid,rows,"id");return rows;
    }
    public void finishLegacyRead(Long pid,List<Map<String,Object>> rows){for(Map<String,Object> row:rows)if(row.get("nextAction")!=null){stockQueries.finishLegacyRead(scope.requireUserId(),pid);break;}}
    public List<Map<String,Object>> snapshot(Long pid) {
        return snapshot(pid,workflowStorage());
    }
    private List<Map<String,Object>> snapshot(Long pid,boolean workflowStorage) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT s.*,m.drug_name AS drugName,m.is_active AS isActive"+(workflowStorage?",m.workflow_version AS medication_workflow_version,m.execution_id AS medication_execution_id":"")+" FROM medication_stock s JOIN medication m ON m.id=s.medication_id AND m.patient_id=s.patient_id WHERE s.patient_id=? ORDER BY m.drug_name",pid);
        for(Map<String,Object> r:rows){
            BigDecimal daily=dailyUse(((Number)r.get("medication_id")).longValue(),workflowStorage);
            BigDecimal quantity=r.get("quantity")==null?null:new BigDecimal(r.get("quantity").toString());
            BigDecimal warning=r.get("warning_quantity")==null?null:new BigDecimal(r.get("warning_quantity").toString());
            BigDecimal days=quantity!=null&&daily!=null&&daily.signum()>0?quantity.divide(daily,0,RoundingMode.DOWN):null;
            r.put("dailyUse",daily);r.put("estimatedDays",days);
            r.put("low",quantity==null||warning==null||r.get("warning_days")==null?null:quantity.compareTo(warning)<=0 || daily!=null&&daily.signum()>0&&quantity.compareTo(daily.multiply(BigDecimal.valueOf(((Number)r.get("warning_days")).longValue())))<=0);
        }return rows;
    }
    public BigDecimal dailyUse(Long medicationId) { return dailyUse(medicationId,workflowStorage()); }
    private BigDecimal dailyUse(Long medicationId,boolean workflowStorage) {
        if(workflowStorage){
        List<Map<String,Object>> tracked=jdbc.queryForList("SELECT s.workflow_version,s.current_work_id,w.daily_use_quantity FROM medication_stock s LEFT JOIN stock_work w ON w.id=s.current_work_id WHERE s.medication_id=?",medicationId);
        if(!tracked.isEmpty()&&((Number)tracked.get(0).get("workflow_version")).intValue()==1){
            Map<String,Object> current=new org.familyhealthcare.service.stockworkflow.StockWorkflowRepository(jdbc,null,null,null).work(((Number)tracked.get(0).get("current_work_id")).longValue());
            return (BigDecimal)current.get("daily_use_quantity");
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM medication_execution WHERE medication_id=?",Integer.class,medicationId)>0)return null;
        }
        List<CareItem> plans=items.selectList(new QueryWrapper<CareItem>().eq("kind","ORDER").eq("status","ACTIVE"));
        for(CareItem plan:plans){Map<String,Object>d=plan.getDetails();if(!String.valueOf(medicationId).equals(String.valueOf(d.get("medicationId"))))continue;
            BigDecimal total=BigDecimal.ZERO;for(Object dose:(List<?>)d.getOrDefault("doses",Collections.emptyList()))total=total.add(new BigDecimal(((Map<?,?>)dose).get("quantity").toString()));
            String days=String.valueOf(d.getOrDefault("repeatDays","1,2,3,4,5,6,7"));
            return total.multiply(BigDecimal.valueOf(new HashSet<>(Arrays.asList(days.split(","))).size())).divide(BigDecimal.valueOf(7),3,RoundingMode.HALF_UP);
        }return BigDecimal.ZERO;
    }
    public BigDecimal doseFor(MedicationIntake intake) {
        if(workflowStorage()&&ownedIntake(intake))return null;
        // 扣库存以本次服药任务保存的剂量和单位为准，不能拿后来修改的处方覆盖；单位对不上就不猜数量。
        List<String> stockUnits=jdbc.queryForList("SELECT unit FROM medication_stock WHERE patient_id=? AND medication_id=?",String.class,intake.getPatientId(),intake.getMedicationId());
        if(stockUnits.isEmpty()||intake.getDosage()==null)return null;
        java.util.regex.Matcher dose=java.util.regex.Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*([^\\d]+)\\s*$").matcher(intake.getDosage());
        if(!dose.matches()||!stockUnit(dose.group(2)).equals(stockUnit(stockUnits.get(0))))return null;
        BigDecimal quantity=new BigDecimal(dose.group(1));return quantity.signum()>0?quantity:null;
    }
    private String stockUnit(String unit){String normalized=unit==null?"":unit.trim().toLowerCase(Locale.ROOT);if(Arrays.asList("tablet","tablets","tab","tabs","片").contains(normalized))return "tablet";if(Arrays.asList("capsule","capsules","粒").contains(normalized))return "capsule";return normalized;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void configure(Long pid,Long mid,BigDecimal quantity,String unit,int warningDays,BigDecimal warningQuantity) {
        scope.requirePatient(pid);requireLegacyWrite(pid,mid);Medication m=medications.selectById(mid);
        if(m==null||!Objects.equals(pid,m.getPatientId()))throw new IllegalArgumentException("SelectcurrentFamily Member Medication");
        if(quantity==null||quantity.signum()<0||warningDays<0||warningQuantity==null||warningQuantity.signum()<0)throw new IllegalArgumentException("Inventory and Reminderthresholdcannotfor negativecount");
        if(unit==null||unit.trim().isEmpty()||unit.length()>20)throw new IllegalArgumentException("Please fillwriteInventoryUnit");
        for(CareItem p:items.selectList(new QueryWrapper<CareItem>().eq("patient_id",pid).eq("kind","ORDER").eq("status","ACTIVE")))if(String.valueOf(mid).equals(String.valueOf(p.getDetails().get("medicationId")))&&!unit.equals(p.getDetails().get("unit")))throw new IllegalArgumentException("InventoryUnitshould and positivein run prescriptiononecause");
        jdbc.update("INSERT INTO medication_stock(patient_id,medication_id,quantity,unit,warning_days,warning_quantity) VALUES(?,?,0,?,?,?) ON DUPLICATE KEY UPDATE unit=VALUES(unit),warning_days=VALUES(warning_days),warning_quantity=VALUES(warning_quantity)",pid,mid,unit,warningDays,warningQuantity);
        BigDecimal old=jdbc.queryForObject("SELECT quantity FROM medication_stock WHERE medication_id=? FOR UPDATE",BigDecimal.class,mid);
        move(pid,mid,quantity.subtract(old),"correctremainingInventory",null);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void purchase(Long pid,Long mid,BigDecimal quantity,String reason) {
        scope.requirePatient(pid);requireLegacyWrite(pid,mid);if(quantity==null||quantity.signum()<=0)throw new IllegalArgumentException("Restockquantitymustgreater than0");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM medication_stock WHERE patient_id=? AND medication_id=?",Long.class,pid,mid)==0)throw new IllegalArgumentException("Please first settingsInventoryUnit and initialquantity");
        move(pid,mid,quantity,reason==null?"Restockenterdatabase":reason,null);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void consume(MedicationIntake intake,BigDecimal quantity,String recordedFor) {
        requireLegacyWrite(intake.getPatientId(),intake.getMedicationId());
        if(workflowStorage()&&ownedIntake(intake))throw workflowRequired();
        if(quantity==null)quantity=doseFor(intake);
        if(quantity!=null&&quantity.signum()<=0)throw new IllegalArgumentException("actualdosagemustgreater than0");
        jdbc.update("INSERT INTO care_intake_action(patient_id,intake_id,actor_id,actor_name,status,recorded_for,quantity) VALUES(?,?,?,?,?,?,?)",intake.getPatientId(),intake.getId(),scope.requireUserId(),CurrentUserUtil.getCurrentUsername(),intake.getStatus(),recordedFor==null?"SELF":recordedFor,quantity);
        if("TAKEN".equals(intake.getStatus())&&quantity!=null)move(intake.getPatientId(),intake.getMedicationId(),quantity.negate(),"medication intakecheck-indeduct","INTAKE:"+intake.getId());
    }
    private void move(Long pid,Long mid,BigDecimal delta,String reason,String source) {
        List<Map<String,Object>> locked=jdbc.queryForList("SELECT id FROM medication_stock WHERE medication_id=? AND patient_id=? FOR UPDATE",mid,pid);
        if(locked.isEmpty())return;
        if(source!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM medication_stock_movement WHERE source_key=?",Long.class,source)>0)return;
        jdbc.update("INSERT INTO medication_stock_movement(patient_id,medication_id,quantity,reason,source_key,actor_id,actor_name) VALUES(?,?,?,?,?,?,?)",pid,mid,delta,reason,source,CurrentUserUtil.getCurrentUserId(),CurrentUserUtil.getCurrentUsername());
        jdbc.update("UPDATE medication_stock SET quantity=quantity+?,notified_at=CASE WHEN ?>0 THEN NULL ELSE notified_at END WHERE medication_id=?",delta,delta,mid);
    }
    public List<Map<String,Object>> history(Long pid,Long mid){scope.requirePatient(pid);if(workflowStorage()&&tracked(pid,mid))throw workflowRequired();return jdbc.queryForList("SELECT * FROM medication_stock_movement WHERE patient_id=? AND medication_id=? ORDER BY id DESC LIMIT 200",pid,mid);}
    private boolean ownedIntake(MedicationIntake intake){return intake.getId()!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM medication_intake WHERE id=? AND patient_id=? AND medication_id=? AND (workflow_version=1 OR execution_id IS NOT NULL)",Integer.class,intake.getId(),intake.getPatientId(),intake.getMedicationId())>0;}
    private boolean tracked(Long pid,Long mid){return jdbc.queryForObject("SELECT COUNT(*) FROM medication_stock WHERE patient_id=? AND medication_id=? AND (workflow_version=1 OR current_work_id IS NOT NULL)",Integer.class,pid,mid)>0;}
    private void requireLegacyWrite(Long pid,Long mid){org.familyhealthcare.service.workflow.WorkflowData.mutationTransaction(jdbc);if(!workflowStorage())return;stockLocks.lockPatientAndSubjects(pid,Collections.singleton(scope.requireUserId()));jdbc.queryForList("SELECT id FROM medication WHERE id=? AND patient_id=? FOR UPDATE",mid,pid);jdbc.queryForList("SELECT id FROM medication_stock WHERE patient_id=? AND medication_id=? FOR UPDATE",pid,mid);if(tracked(pid,mid))throw workflowRequired();}
    private static org.familyhealthcare.service.workflow.WorkflowException workflowRequired(){return new org.familyhealthcare.service.workflow.WorkflowException(409,"STOCK_WORKFLOW_REQUIRED","Open the stock workflow for this tracked record.");}
    private boolean workflowStorage(){org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.StorageReadiness state=stockSchema.inspectStorageMetadata();if(state.getStatus()==org.familyhealthcare.service.workflow.HouseholdWorkflowSchema.StorageStatus.ABSENT&&!stockProperties.isEnabled())return false;state.requireStructurallyReady();return true;}
}
