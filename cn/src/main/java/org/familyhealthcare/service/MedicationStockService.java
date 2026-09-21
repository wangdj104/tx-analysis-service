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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataScopeHelper scope;
    @Autowired private MedicationMapper medications;
    @Autowired private CareItemMapper items;

    public List<Map<String,Object>> list(Long pid) {
        scope.requirePatient(pid);
        return snapshot(pid);
    }
    public List<Map<String,Object>> snapshot(Long pid) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT s.*,m.drug_name AS drugName,m.is_active AS isActive FROM medication_stock s JOIN medication m ON m.id=s.medication_id WHERE s.patient_id=? ORDER BY m.drug_name",pid);
        for(Map<String,Object> r:rows){
            BigDecimal daily=dailyUse(((Number)r.get("medication_id")).longValue());
            BigDecimal quantity=new BigDecimal(r.get("quantity").toString());
            BigDecimal days=daily.signum()>0?quantity.max(BigDecimal.ZERO).divide(daily,0,RoundingMode.DOWN):null;
            r.put("dailyUse",daily);r.put("estimatedDays",days);
            r.put("low",quantity.compareTo(new BigDecimal(r.get("warning_quantity").toString()))<=0 || days!=null&&days.intValue()<=((Number)r.get("warning_days")).intValue());
        }return rows;
    }
    public BigDecimal dailyUse(Long medicationId) {
        List<CareItem> plans=items.selectList(new QueryWrapper<CareItem>().eq("kind","ORDER").eq("status","ACTIVE"));
        for(CareItem plan:plans){Map<String,Object>d=plan.getDetails();if(!String.valueOf(medicationId).equals(String.valueOf(d.get("medicationId"))))continue;
            BigDecimal total=BigDecimal.ZERO;for(Object dose:(List<?>)d.getOrDefault("doses",Collections.emptyList()))total=total.add(new BigDecimal(((Map<?,?>)dose).get("quantity").toString()));
            String days=String.valueOf(d.getOrDefault("repeatDays","1,2,3,4,5,6,7"));
            return total.multiply(BigDecimal.valueOf(new HashSet<>(Arrays.asList(days.split(","))).size())).divide(BigDecimal.valueOf(7),3,RoundingMode.HALF_UP);
        }return BigDecimal.ZERO;
    }
    public BigDecimal doseFor(MedicationIntake intake) {
        for(CareItem p:items.selectList(new QueryWrapper<CareItem>().eq("patient_id",intake.getPatientId()).eq("kind","ORDER").orderByDesc("id"))){
            Map<String,Object>d=p.getDetails();List<?> ids=(List<?>)d.getOrDefault("reminderIds",Collections.emptyList());
            if(ids.stream().noneMatch(id->String.valueOf(id).equals(String.valueOf(intake.getReminderId()))))continue;
            for(Object obj:(List<?>)d.getOrDefault("doses",Collections.emptyList())){Map<?,?>dose=(Map<?,?>)obj;
                if(intake.getScheduledAt().toLocalTime().equals(LocalTime.parse(dose.get("time").toString())))return new BigDecimal(dose.get("quantity").toString());
            }
        }return null;
    }
    @Transactional public void configure(Long pid,Long mid,BigDecimal quantity,String unit,int warningDays,BigDecimal warningQuantity) {
        scope.requirePatient(pid);Medication m=medications.selectById(mid);
        if(m==null||!Objects.equals(pid,m.getPatientId()))throw new IllegalArgumentException("SelectcurrentFamily Member Medication");
        if(quantity==null||quantity.signum()<0||warningDays<0||warningQuantity==null||warningQuantity.signum()<0)throw new IllegalArgumentException("Inventory and Reminderthresholdcannotfor negativecount");
        if(unit==null||unit.trim().isEmpty()||unit.length()>20)throw new IllegalArgumentException("Please fillwriteInventoryUnit");
        for(CareItem p:items.selectList(new QueryWrapper<CareItem>().eq("patient_id",pid).eq("kind","ORDER").eq("status","ACTIVE")))if(String.valueOf(mid).equals(String.valueOf(p.getDetails().get("medicationId")))&&!unit.equals(p.getDetails().get("unit")))throw new IllegalArgumentException("InventoryUnitshould and positivein run prescriptiononecause");
        jdbc.update("INSERT INTO medication_stock(patient_id,medication_id,quantity,unit,warning_days,warning_quantity) VALUES(?,?,0,?,?,?) ON DUPLICATE KEY UPDATE unit=VALUES(unit),warning_days=VALUES(warning_days),warning_quantity=VALUES(warning_quantity)",pid,mid,unit,warningDays,warningQuantity);
        BigDecimal old=jdbc.queryForObject("SELECT quantity FROM medication_stock WHERE medication_id=? FOR UPDATE",BigDecimal.class,mid);
        move(pid,mid,quantity.subtract(old),"correctremainingInventory",null);
    }
    @Transactional public void purchase(Long pid,Long mid,BigDecimal quantity,String reason) {
        scope.requirePatient(pid);if(quantity==null||quantity.signum()<=0)throw new IllegalArgumentException("Restockquantitymustgreater than0");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM medication_stock WHERE patient_id=? AND medication_id=?",Long.class,pid,mid)==0)throw new IllegalArgumentException("Please first settingsInventoryUnit and initialquantity");
        move(pid,mid,quantity,reason==null?"Restockenterdatabase":reason,null);
    }
    @Transactional public void consume(MedicationIntake intake,BigDecimal quantity,String recordedFor) {
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
    public List<Map<String,Object>> history(Long pid,Long mid){scope.requirePatient(pid);return jdbc.queryForList("SELECT * FROM medication_stock_movement WHERE patient_id=? AND medication_id=? ORDER BY id DESC LIMIT 200",pid,mid);}
}
