package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.PatientHealthTarget;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.*;
import java.util.*;

/** Source-specific comparisons and provenance. Never mutates a source or prescribes treatment. */
@Service
public class MeasurementAssessmentService {
    private final JdbcTemplate jdbc;
    public MeasurementAssessmentService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,Object> assessJourney(long patientId,String metric,BigDecimal primary,BigDecimal secondary,String unit){
        String policy="JOURNEY_"+metric;
        if(primary==null||!knownUnit(metric,unit))return unknown(metric,policy,null,unit,null);
        return assessJourney(metric,primary,secondary,unit,target(patientId));
    }
    /** 同一趋势页共享一次目标快照，保留原来源的比较政策。 */
    public Map<String,Object> assessJourney(String metric,BigDecimal primary,BigDecimal secondary,String unit,Map<String,Object> target){
        String policy="JOURNEY_"+metric;
        if(primary==null||!knownUnit(metric,unit))return unknown(metric,policy,null,unit,null);
        Map<String,Object> thresholds=journeyThresholds(metric,target);
        if(thresholds.isEmpty()||("BP".equals(metric)&&secondary==null))return unknown(metric,policy,primary,unit,null);
        BigDecimal comparison="GLUCOSE".equals(metric)?glucoseValue(primary,unit):primary;
        String status=journeyStatus(metric,comparison,secondary,target);
        Object value="BP".equals(metric)?WorkflowData.map("systolic",comparison,"diastolic",secondary):comparison;
        return assessment(metric,policy,status,"NORMAL".equals(status)?"NORMAL":"WARNING",value,unit,null,thresholds);
    }
    /** One row per present metric in combined records. Required unknown context is explicit. */
    public List<Map<String,Object>> assessSelfMonitor(BpSelfMonitorRecord record,PatientHealthTarget target){
        List<Map<String,Object>> result=new ArrayList<>();if(record==null)return result;
        if(record.getSystolicBp()!=null||record.getDiastolicBp()!=null){
            Object value=WorkflowData.map("systolic",record.getSystolicBp(),"diastolic",record.getDiastolicBp());
            if(record.getSystolicBp()==null||record.getDiastolicBp()==null)result.add(unknown("BP","SELF_MONITOR_BP",value,"mmHg",null));
            else{String severity=legacyBpSeverity(record,target);result.add(assessment("BP","SELF_MONITOR_BP",status(severity),severity,value,"mmHg",null,bpThresholds(target)));}
        }
        if(record.getBloodGlucose()!=null){
            String period=period(record.getMeasurePeriod()),policy="SELF_MONITOR_GLUCOSE_"+(period==null?"UNKNOWN":("Random".equals(period)?"RANDOM_LEGACY":"After Meal2h".equals(period)?"POSTMEAL":"FASTING"));
            if(period==null||!knownUnit("GLUCOSE",record.getBgUnit()))result.add(unknown("GLUCOSE",policy,knownUnit("GLUCOSE",record.getBgUnit())?glucoseValue(record.getBloodGlucose(),record.getBgUnit()):null,record.getBgUnit(),period==null?record.getMeasurePeriod():period));
            else{
                BigDecimal value=glucoseValue(record.getBloodGlucose(),record.getBgUnit());
                Map<String,Object> thresholds=glucoseThresholds(target,"After Meal2h".equals(period));String severity=glucoseSeverity(value,thresholds);
                result.add(assessment("GLUCOSE",policy,status(severity),severity,value,record.getBgUnit(),period,thresholds));
            }
        }
        return result;
    }
    /** Explicit legacy adapter: caller already converted generic glucose and historically allows target-less weight/custom. */
    public String legacyJourneyStatus(long patientId,String metric,BigDecimal primary,BigDecimal secondary){return journeyStatus(metric,primary,secondary,target(patientId));}
    /** Legacy monitoring accepts systolic-only readings; strict follow-up assessment does not. */
    public String legacyBpSeverity(BpSelfMonitorRecord row,PatientHealthTarget target){
        if(row==null||row.getSystolicBp()==null)return "NO_DATA";
        int s=row.getSystolicBp(),d=row.getDiastolicBp()==null?0:row.getDiastolicBp();
        if(s>=180||(d>0&&d>=120)||s<80||(d>0&&d<50))return "CRITICAL";
        Map<String,Object> thresholds=bpThresholds(target);
        return outside(BigDecimal.valueOf(s),thresholds,"systolicMin","systolicMax")||(d>0&&outside(BigDecimal.valueOf(d),thresholds,"diastolicMin","diastolicMax"))?"WARNING":"NORMAL";
    }
    /** Legacy blank period/unit behavior remains explicit and is not used for new follow-up classification. */
    public String legacyGlucoseSeverity(BpSelfMonitorRecord row,PatientHealthTarget target){
        if(row==null||row.getBloodGlucose()==null)return "NO_DATA";
        String unit=row.getBgUnit()==null?"":row.getBgUnit().trim();
        if(!unit.isEmpty()&&!knownUnit("GLUCOSE",unit))return "NO_DATA";
        BigDecimal value=glucoseValue(row.getBloodGlucose(),unit);
        boolean postMeal=row.getMeasurePeriod()!=null&&row.getMeasurePeriod().contains("After Meal");
        return glucoseSeverity(value,glucoseThresholds(target,postMeal));
    }
    private String journeyStatus(String metric,BigDecimal a,BigDecimal b,Map<String,Object> target){
        Map<String,Object> thresholds=journeyThresholds(metric,target);
        if("BP".equals(metric))return outside(a,thresholds,"systolicMin","systolicMax")||b==null||outside(b,thresholds,"diastolicMin","diastolicMax")?"ABNORMAL":"NORMAL";
        if("WEIGHT".equals(metric)&&!thresholds.isEmpty())return a.subtract((BigDecimal)thresholds.get("target")).abs().compareTo((BigDecimal)thresholds.get("limit"))>0?"ABNORMAL":"NORMAL";
        return thresholds.isEmpty()?"NORMAL":outside(a,thresholds,"min","max")?"ABNORMAL":"NORMAL";
    }
    private Map<String,Object> journeyThresholds(String metric,Map<String,Object> target){
        if("BP".equals(metric))return WorkflowData.map("systolicMin",decimal(target,"systolic_min","90"),"systolicMax",decimal(target,"systolic_max","140"),"diastolicMin",decimal(target,"diastolic_min","60"),"diastolicMax",decimal(target,"diastolic_max","90"));
        if("GLUCOSE".equals(metric))return range("3.9","11.1");
        if("SPO2".equals(metric))return range("92",null);
        if("HEART_RATE".equals(metric))return range("50","120");
        if("TEMPERATURE".equals(metric))return range("35","38");
        if("WEIGHT".equals(metric)&&target!=null&&target.get("target_weight")!=null)return WorkflowData.map("target",decimal(target,"target_weight",null),"limit",decimal(target,"weight_gain_limit","3"));
        return Collections.emptyMap();
    }
    private Map<String,Object> bpThresholds(PatientHealthTarget target){return WorkflowData.map("systolicMin",number(target==null?null:target.getSystolicMin(),90),"systolicMax",number(target==null?null:target.getSystolicMax(),140),"diastolicMin",number(target==null?null:target.getDiastolicMin(),60),"diastolicMax",number(target==null?null:target.getDiastolicMax(),90),"criticalSystolicMin",new BigDecimal("80"),"criticalSystolicMax",new BigDecimal("180"),"criticalDiastolicMin",new BigDecimal("50"),"criticalDiastolicMax",new BigDecimal("120"));}
    private Map<String,Object> glucoseThresholds(PatientHealthTarget target,boolean postMeal){
        BigDecimal min=target==null?null:postMeal?target.getPostmealGlucoseMin():target.getFastingGlucoseMin(),max=target==null?null:postMeal?target.getPostmealGlucoseMax():target.getFastingGlucoseMax();
        return WorkflowData.map("min",min==null?new BigDecimal("3.9"):min,"max",max==null?new BigDecimal(postMeal?"7.8":"6.1"):max,"criticalMin",new BigDecimal("3.0"),"criticalMax",new BigDecimal("16.7"));
    }
    private String glucoseSeverity(BigDecimal value,Map<String,Object> thresholds){if(value.compareTo((BigDecimal)thresholds.get("criticalMin"))<0||value.compareTo((BigDecimal)thresholds.get("criticalMax"))>0)return "CRITICAL";return outside(value,thresholds,"min","max")?"WARNING":"NORMAL";}
    private boolean outside(BigDecimal value,Map<String,Object> thresholds,String minKey,String maxKey){BigDecimal min=(BigDecimal)thresholds.get(minKey),max=(BigDecimal)thresholds.get(maxKey);return (min!=null&&value.compareTo(min)<0)||(max!=null&&value.compareTo(max)>0);}
    private Map<String,Object> target(long patient){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM patient_health_target WHERE patient_id=?",patient);if(rows.size()>1)throw new IllegalStateException("Multiple current patient targets");if(rows.isEmpty())return null;Map<String,Object> result=new HashMap<>();for(Map.Entry<String,Object> e:rows.get(0).entrySet())result.put(e.getKey().toLowerCase(Locale.ROOT),e.getValue());return result;}
    private BigDecimal decimal(Map<String,Object> target,String field,String fallback){Object value=target==null?null:target.get(field);return value==null?(fallback==null?null:new BigDecimal(fallback)):new BigDecimal(value.toString());}
    private BigDecimal number(Integer value,int fallback){return BigDecimal.valueOf(value==null?fallback:value);}
    private BigDecimal glucoseValue(BigDecimal value,String unit){return unit!=null&&"mg/dL".equalsIgnoreCase(unit.trim())?value.divide(new BigDecimal("18"),8,RoundingMode.HALF_UP):value;}
    private boolean knownUnit(String metric,String unit){if(unit==null||unit.trim().isEmpty())return false;String value=unit.trim();if("GLUCOSE".equals(metric))return "mmol/L".equalsIgnoreCase(value)||"mg/dL".equalsIgnoreCase(value);if("BP".equals(metric))return "mmHg".equalsIgnoreCase(value);if("SPO2".equals(metric))return "%".equals(value);if("WEIGHT".equals(metric))return "kg".equalsIgnoreCase(value);if("HEART_RATE".equals(metric))return "bpm".equalsIgnoreCase(value);return "TEMPERATURE".equals(metric)&&Arrays.asList("°C","℃","C","c").contains(value);}
    private String period(String raw){if(raw==null)return null;String value=raw.trim();if(Arrays.asList("Fasting","空腹").contains(value))return "Fasting";if(Arrays.asList("After Meal2h","餐后2小时","餐后2h","餐后2H").contains(value))return "After Meal2h";if(Arrays.asList("Random","随机").contains(value))return "Random";return null;}
    private Map<String,Object> range(String min,String max){return WorkflowData.map("min",min==null?null:new BigDecimal(min),"max",max==null?null:new BigDecimal(max));}
    private String status(String severity){return "NORMAL".equals(severity)?"NORMAL":"ABNORMAL";}
    private Map<String,Object> unknown(String metric,String policy,Object comparison,String unit,String period){return assessment(metric,policy,"UNCLASSIFIABLE","UNCLASSIFIABLE",comparison,unit,period,Collections.emptyMap());}
    private Map<String,Object> assessment(String metric,String policy,String status,String severity,Object comparison,String unit,String period,Map<String,Object> thresholds){return WorkflowData.map("metric",metric,"policyKey",policy,"status",status,"severity",severity,"comparisonValue",comparison,"sourceUnit",unit,"period",period,"thresholds",thresholds);}
}
