package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.PatientHealthTarget;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;

/** 双来源有界当前页；不读取修订正文、告警详情、来源锁或任何关联私人正文。 */
@Service
public class MeasurementTrendQueryService {
    private static final String IDENTITY_COLUMNS="i.source_id AS identity_source_id,i.patient_id AS identity_patient_id,i.current_revision,i.status AS identity_status,i.lock_version";
    private static final String SELF_SELECT="SELECT n.id,n.patient_id,n.record_date,n.record_time,n.measure_type,n.systolic_bp,n.diastolic_bp,n.blood_glucose,n.bg_unit,n.measure_period,n.verification_status,"+IDENTITY_COLUMNS+" FROM bp_self_monitor_record n LEFT JOIN alert_source_identity i ON i.source_type=? AND i.source_id=n.id WHERE n.patient_id=? AND n.record_date>=? AND n.record_date<? AND n.measure_type IN (?,?,?)";
    private static final String JOURNEY_SELECT="SELECT n.id,n.patient_id,n.metric_type,n.metric_name,n.value_primary,n.value_secondary,n.unit,n.measured_at,n.source_type,"+IDENTITY_COLUMNS+" FROM health_measurement n LEFT JOIN alert_source_identity i ON i.source_type=? AND i.source_id=n.id WHERE n.patient_id=? AND n.metric_type=? AND n.measured_at>=? AND n.measured_at<?";
    private static final String TARGET_SELECT="SELECT systolic_min,systolic_max,diastolic_min,diastolic_max,fasting_glucose_min,fasting_glucose_max,postmeal_glucose_min,postmeal_glucose_max,target_weight,weight_gain_limit FROM patient_health_target WHERE patient_id=? LIMIT 1";
    private final JdbcTemplate jdbc;
    private final AlertWorkflowAuthorizationService auth;
    private final AlertWorkflowProperties properties;
    private final MeasurementReadReadiness readiness;
    private final MeasurementAssessmentService assessment;
    public MeasurementTrendQueryService(JdbcTemplate jdbc,AlertWorkflowAuthorizationService auth,AlertWorkflowProperties properties,MeasurementReadReadiness readiness,MeasurementAssessmentService assessment){
        this.jdbc=jdbc;this.auth=auth;this.properties=properties;this.readiness=readiness;this.assessment=assessment;
    }

    public Map<String,Object> page(long actor,long patient,String source,String metric,String from,String to,String cursor,int limit){
        if(actor<=0||patient<=0||limit<1||limit>100)throw AlertWorkflowException.invalid("Invalid patient or page size.");
        if(!AlertWorkflowContracts.SOURCE_TYPES.contains(source)||!AlertWorkflowContracts.METRICS.contains(metric)||("BP_SELF_MONITOR".equals(source)&&!Arrays.asList("BP","GLUCOSE").contains(metric)))throw AlertWorkflowException.invalid("Unsupported measurement source or metric.");
        LocalDate start=date(from),end=date(to);
        long days=ChronoUnit.DAYS.between(start,end);
        if(days<0||days>=90||end.equals(LocalDate.of(9999,12,31)))throw AlertWorkflowException.invalid("Choose an inclusive date range of at most 90 days.");
        boolean self="BP_SELF_MONITOR".equals(source);
        MeasurementTrendCursor codec=new MeasurementTrendCursor(actor,patient,source,metric,from,to);
        Map<String,Object> position=codec.decode(cursor);validatePosition(position,self,start,end);
        try{
            // capability只是前置提示；读取前和返回前都复核当前账户、关系、授权及开关。
            auth.requireRead(actor,patient);readiness.requireTrend(source);
            List<Map<String,Object>> candidates=read(patient,source,metric,start,end.plusDays(1),position,limit,self);
            boolean hasNext=candidates.size()>limit;
            List<Map<String,Object>> rows=candidates.subList(0,Math.min(limit,candidates.size()));
            String next=hasNext?codec.encode(rows.get(rows.size()-1)):null;
            TargetSnapshot target=rows.isEmpty()?new TargetSnapshot(null,null):target(patient);
            List<Map<String,Object>> items=new ArrayList<>();Map<String,Integer> counts=new LinkedHashMap<>();
            for(Map<String,Object> row:rows){Map<String,Object> item=project(row,source,metric,self,target);items.add(item);for(Object flag:(List<?>)item.get("qualityFlags"))counts.put(flag.toString(),counts.getOrDefault(flag.toString(),0)+1);}
            auth.requireRead(actor,patient);
            return map("patientId",patient,"sourceType",source,"metric",metric,"from",from,"to",to,"generatedAt",properties.now().toString(),"items",items,"nextCursor",next,
                    "coverage",map("scope","CURRENT_PAGE","consistency","NON_SNAPSHOT","returned",items.size(),"hasMore",hasNext,"qualityCounts",counts,"unknownDatesIncluded",false,"refreshFromFirstPage",true,
                            "notice","This page is not a snapshot. Corrections can move records between pages; refresh from the first page. Records without a valid date may be absent; use the original-record entry to review them."));
        }catch(AlertWorkflowException failure){throw failure;}
        catch(DataAccessException|IllegalStateException failure){throw MeasurementReadReadiness.unavailable();}
    }
    private List<Map<String,Object>> read(long patient,String source,String metric,LocalDate from,LocalDate until,Map<String,Object> cursor,int limit,boolean self){
        StringBuilder sql=new StringBuilder(self?SELF_SELECT:JOURNEY_SELECT);List<Object> args=new ArrayList<>();
        args.add(source);args.add(patient);
        if(self){args.add(from.toString());args.add(until.toString());args.add("BP".equals(metric)?"BP":"BG");args.add("BP_BG");args.add("BOTH");}
        else{args.add(metric);args.add(from+" 00:00:00");args.add(until+" 00:00:00");}
        if(cursor!=null){
            String time=(String)cursor.get("time");long id=(Long)cursor.get("id");
            if(self){
                String date=(String)cursor.get("date");sql.append(" AND (n.record_date>? OR (n.record_date=? AND (");args.add(date);args.add(date);
                // MySQL原始升序中NULL在前；NULL与空串必须分别续页，不能补零点。
                if(time==null){sql.append("n.record_time IS NOT NULL OR (n.record_time IS NULL AND n.id>?)");args.add(id);}
                else{sql.append("n.record_time>? OR (n.record_time=? AND n.id>?)");args.add(time);args.add(time);args.add(id);}
                sql.append(")))");
            }else{
                sql.append(" AND (n.measured_at>? OR (n.measured_at=? AND n.id>?))");args.add(time);args.add(time);args.add(id);
            }
        }
        sql.append(self?" ORDER BY n.record_date,n.record_time,n.id LIMIT ?":" ORDER BY n.measured_at,n.id LIMIT ?");args.add(limit+1);
        return jdbc.query(sql.toString(),(rs,index)->nativeRow(rs,patient,source,metric,self),args.toArray());
    }
    private Map<String,Object> nativeRow(ResultSet rs,long patient,String source,String metric,boolean self)throws SQLException {
        long id=rs.getLong("id");
        if(id<=0||rs.getLong("patient_id")!=patient)throw MeasurementReadReadiness.unavailable();
        Long identity=nullableLong(rs,"identity_source_id"),revision=nullableLong(rs,"current_revision"),version=nullableLong(rs,"lock_version");
        String status=rs.getString("identity_status");
        // JOIN只按真实唯一来源身份匹配。错患者身份是整页错误，不能隐藏成未纳管。
        if(identity!=null&&(identity!=id||!Long.valueOf(patient).equals(nullableLong(rs,"identity_patient_id"))||revision==null||revision<1||version==null||version<0||!Arrays.asList("ACTIVE","INVALIDATED").contains(status)))throw MeasurementReadReadiness.unavailable();
        Map<String,Object> row=map("sourceId",id,"sourceRevision",revision,"sourceVersion",version,"sourceStatus",identity==null?"UNTRACKED":status);
        if(self){
            String type=rs.getString("measure_type");
            if(!Arrays.asList("BP".equals(metric)?"BP":"BG","BP_BG","BOTH").contains(type))throw MeasurementReadReadiness.unavailable();
            row.putAll(map("recordDate",rs.getString("record_date"),"sortTime",rs.getString("record_time"),"measureType",type,
                    "primary","BP".equals(metric)?rs.getBigDecimal("systolic_bp"):rs.getBigDecimal("blood_glucose"),"secondary","BP".equals(metric)?rs.getBigDecimal("diastolic_bp"):null,
                    "rawUnit","BP".equals(metric)?"mmHg":rs.getString("bg_unit"),"period",rs.getString("measure_period"),"verificationStatus",rs.getString("verification_status")));
        }else{
            if(!metric.equals(rs.getString("metric_type")))throw MeasurementReadReadiness.unavailable();
            row.putAll(map("metricName",rs.getString("metric_name"),"recordDate",null,"sortTime",rs.getString("measured_at"),"primary",rs.getBigDecimal("value_primary"),"secondary",rs.getBigDecimal("value_secondary"),"rawUnit",rs.getString("unit"),"sourceKind",rs.getString("source_type")));
        }
        return row;
    }
    private Map<String,Object> project(Map<String,Object> row,String source,String metric,boolean self,TargetSnapshot target){
        BigDecimal primary=(BigDecimal)row.get("primary"),secondary=(BigDecimal)row.get("secondary");String unit=(String)row.get("rawUnit");
        List<String> flags=new ArrayList<>();String sourceStatus=(String)row.get("sourceStatus");
        if(!"ACTIVE".equals(sourceStatus))flags.add(sourceStatus);
        if(self&&!"VERIFIED".equals(row.get("verificationStatus")))flags.add("REVIEW_REQUIRED");
        boolean bp="BP".equals(metric),custom="CUSTOM".equals(metric),known=knownUnit(metric,unit);
        // 缺失单位与没有标准比较政策是不同事实；用户明确填写的自定义单位不是坏数据。
        if(unit==null||unit.trim().isEmpty())flags.add("MISSING_UNIT");
        if(!custom&&!known)flags.add("UNKNOWN_UNIT");
        if(custom)flags.add("NO_STANDARD_COMPARISON_POLICY");
        if("WEIGHT".equals(metric)&&(target.journey==null||target.journey.get("target_weight")==null))flags.add("MISSING_CURRENT_TARGET");
        if(primary==null)flags.add("MISSING_VALUE");
        if(bp&&(primary==null||secondary==null))flags.add("INCOMPLETE_BP");
        String plotTime=plotTime(row,self);if(plotTime==null)flags.add("TIME_UNCONFIRMED");
        Map<String,Object> comparison;
        if(self){
            BpSelfMonitorRecord record=new BpSelfMonitorRecord();record.setMeasurePeriod((String)row.get("period"));record.setBgUnit(unit);
            if(bp){record.setSystolicBp(exactInteger(primary));record.setDiastolicBp(exactInteger(secondary));}else record.setBloodGlucose(primary);
            List<Map<String,Object>> results=assessment.assessSelfMonitor(record,target.self);
            comparison=results.isEmpty()?map("policyKey",bp?"SELF_MONITOR_BP":"SELF_MONITOR_GLUCOSE_UNKNOWN","status","UNCLASSIFIABLE","severity","UNCLASSIFIABLE","comparisonValue",null,"thresholds",Collections.emptyMap()):new LinkedHashMap<>(results.get(0));
            if(!bp&&!validPeriod((String)row.get("period")))flags.add("UNKNOWN_PERIOD");
        }else if(custom){
            // 自定义测量只展示当前获权原事实，不借用标准指标阈值或旧NORMAL默认值。
            comparison=map("policyKey","JOURNEY_CUSTOM","status","UNCLASSIFIABLE","severity","UNCLASSIFIABLE","comparisonValue",null,"thresholds",Collections.emptyMap());
        }else comparison=new LinkedHashMap<>(assessment.assessJourney(metric,primary,secondary,unit,target.journey));
        boolean comparable=!custom&&"ACTIVE".equals(sourceStatus)&&(!self||"VERIFIED".equals(row.get("verificationStatus")))&&known&&primary!=null&&(!bp||secondary!=null)&&plotTime!=null;
        boolean assessable=comparable&&!flags.contains("UNKNOWN_PERIOD")&&!"UNCLASSIFIABLE".equals(comparison.get("status"));
        // 原值和比较值分开；单位未知时绝不补mmol/L，质量闸门不能被NORMAL覆盖。
        if(!assessable){comparison.put("status","UNCLASSIFIABLE");comparison.put("severity","UNCLASSIFIABLE");}
        return map("sourceType",source,"sourceId",row.get("sourceId"),"sourceRevision",row.get("sourceRevision"),"sourceVersion",row.get("sourceVersion"),"sourceStatus",sourceStatus,
                "rawTime",self?map("recordDate",row.get("recordDate"),"recordTime",row.get("sortTime")):map("measuredAt",row.get("sortTime")),"timeBasis","RECORDED_LOCAL_TIME_ZONE_UNKNOWN","plotTime",plotTime,
                "rawValue",bp?map("systolic",primary,"diastolic",secondary):primary,"rawSecondaryValue",custom?secondary:null,"metricName",custom?row.get("metricName"):null,"rawUnit",unit,"unitBasis",self&&bp?"SCHEMA_DEFINED":"RECORDED","measurePeriod",row.get("period"),"verificationStatus",row.get("verificationStatus"),"sourceKind",row.get("sourceKind"),
                "comparisonValue",comparison.get("comparisonValue"),"comparisonUnit",known?comparisonUnit(metric):null,"qualityFlags",flags,"comparable",comparable,"assessable",assessable,
                "policyKey",comparison.get("policyKey"),"status",comparison.get("status"),"severity",comparison.get("severity"),"thresholds",comparison.get("thresholds"));
    }
    private TargetSnapshot target(long patient){
        List<TargetSnapshot> rows=jdbc.query(TARGET_SELECT,(rs,index)->{
            PatientHealthTarget self=new PatientHealthTarget();
            self.setSystolicMin(nullableInteger(rs,"systolic_min"));self.setSystolicMax(nullableInteger(rs,"systolic_max"));self.setDiastolicMin(nullableInteger(rs,"diastolic_min"));self.setDiastolicMax(nullableInteger(rs,"diastolic_max"));
            self.setFastingGlucoseMin(rs.getBigDecimal("fasting_glucose_min"));self.setFastingGlucoseMax(rs.getBigDecimal("fasting_glucose_max"));self.setPostmealGlucoseMin(rs.getBigDecimal("postmeal_glucose_min"));self.setPostmealGlucoseMax(rs.getBigDecimal("postmeal_glucose_max"));
            return new TargetSnapshot(self,map("systolic_min",self.getSystolicMin(),"systolic_max",self.getSystolicMax(),"diastolic_min",self.getDiastolicMin(),"diastolic_max",self.getDiastolicMax(),"target_weight",rs.getBigDecimal("target_weight"),"weight_gain_limit",rs.getBigDecimal("weight_gain_limit")));
        },patient);
        return rows.isEmpty()?new TargetSnapshot(null,null):rows.get(0);
    }
    private static String plotTime(Map<String,Object> row,boolean self){
        try{
            String raw=(String)row.get("sortTime");if(raw==null)return null;
            if(!self)return localTime(raw).toString();
            if(!raw.matches("[0-9]{2}:[0-9]{2}(:[0-9]{2}(\\.[0-9]{1,9})?)?"))return null;
            return LocalDate.parse((String)row.get("recordDate")).atTime(LocalTime.parse(raw)).toString();
        }catch(DateTimeException|NullPointerException invalid){return null;}
    }
    private static LocalDateTime localTime(String raw){return LocalDateTime.parse(raw.replace(' ','T'));}
    private static LocalDate date(String raw){
        try{if(raw==null||!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();LocalDate date=LocalDate.parse(raw);if(date.getYear()<1000)throw new IllegalArgumentException();return date;}
        catch(IllegalArgumentException|DateTimeException invalid){throw AlertWorkflowException.invalid("from and to must be valid ISO local dates.");}
    }
    private static void validatePosition(Map<String,Object> position,boolean self,LocalDate from,LocalDate to){
        if(position==null)return;
        try{
            String raw=(String)position.get("time");LocalDate day;
            // 原时刻可为无效时间或补充字符；长度由完整游标预算约束，不按UTF-16截断。
            if(self)day=date((String)position.get("date"));
            else{if(position.get("date")!=null||raw==null||raw.length()>32)throw new IllegalArgumentException();day=localTime(raw).toLocalDate();}
            if(day.isBefore(from)||day.isAfter(to))throw new IllegalArgumentException();
        }catch(RuntimeException failure){throw AlertWorkflowException.invalid("Invalid cursor position for this date range.");}
    }
    /** 单位描述只决定可比性及显示；所有临床判定仍由既有来源评估器处理。 */
    private static boolean knownUnit(String metric,String unit){
        if(unit==null)return false;String value=unit.trim();
        if("GLUCOSE".equals(metric))return "mmol/L".equalsIgnoreCase(value)||"mg/dL".equalsIgnoreCase(value);
        if("TEMPERATURE".equals(metric))return Arrays.asList("°C","℃","C","c").contains(value);
        String standard=comparisonUnit(metric);return standard!=null&&standard.equalsIgnoreCase(value);
    }
    private static String comparisonUnit(String metric){
        switch(metric){case "BP":return "mmHg";case "GLUCOSE":return "mmol/L";case "SPO2":return "%";case "WEIGHT":return "kg";case "HEART_RATE":return "bpm";case "TEMPERATURE":return "°C";default:return null;}
    }
    private static boolean validPeriod(String period){return period!=null&&Arrays.asList("Fasting","空腹","After Meal2h","餐后2小时","餐后2h","餐后2H","Random","随机").contains(period.trim());}
    private static Long nullableLong(ResultSet rs,String column)throws SQLException{long value=rs.getLong(column);return rs.wasNull()?null:value;}
    private static Integer nullableInteger(ResultSet rs,String column)throws SQLException{return exactInteger(rs.getBigDecimal(column));}
    /** 旧评估器只接收Integer；超出表示范围必须明确不可读，不能依赖驱动截断或落入旧全局异常。 */
    private static Integer exactInteger(BigDecimal value){
        try{return value==null?null:value.intValueExact();}
        catch(ArithmeticException unrepresentable){throw MeasurementReadReadiness.unavailable();}
    }
    private static final class TargetSnapshot {final PatientHealthTarget self;final Map<String,Object> journey;TargetSnapshot(PatientHealthTarget self,Map<String,Object> journey){this.self=self;this.journey=journey;}}
}
