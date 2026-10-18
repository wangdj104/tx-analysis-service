package org.familyhealthcare.service.measurementread;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;

/** 仅原表白名单；调用者负责读前和返回前授权，不读取工作流资料。 */
@Repository
public class OriginalMeasurementRepository {
    private static final String SELF_COLUMNS="id,patient_id,record_date,record_time,measure_type,systolic_bp,diastolic_bp,blood_glucose,bg_unit,measure_period,source_type,verification_status";
    private static final String JOURNEY_COLUMNS="id,patient_id,metric_type,metric_name,value_primary,value_secondary,unit,measured_at,source_type,status";
    private final JdbcTemplate jdbc;
    public OriginalMeasurementRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Map<String,Object>> page(long patient,boolean self,String filter,LocalDate from,LocalDate to,Map<String,Object> position,int limit){
        StringBuilder sql=new StringBuilder("SELECT ").append(self?SELF_COLUMNS:JOURNEY_COLUMNS).append(self?" FROM bp_self_monitor_record WHERE patient_id=?":" FROM health_measurement WHERE patient_id=?");
        List<Object>args=new ArrayList<>();args.add(patient);
        if(filter!=null){if(self){if("BP_BG".equals(filter)){sql.append(" AND measure_type IN (?,?)");args.add("BP_BG");args.add("BOTH");}else{sql.append(" AND measure_type IN (?,?,?)");args.add(filter);args.add("BP_BG");args.add("BOTH");}}else{sql.append(" AND metric_type=?");args.add(filter);}}
        if(from!=null){sql.append(self?" AND record_date>=? AND record_date<?":" AND measured_at>=? AND measured_at<?");args.add(self?from.toString():from+" 00:00:00");args.add(self?to.plusDays(1).toString():to.plusDays(1)+" 00:00:00");}
        if(position!=null)position(sql,args,self,position);
        sql.append(" ORDER BY ").append(self?OriginalMeasurementCursor.SELF_ORDER:OriginalMeasurementCursor.JOURNEY_ORDER).append(" LIMIT ?");args.add(limit+1);
        return jdbc.query(sql.toString(),(rs,i)->self?selfRow(rs,patient,false):journeyRow(rs,patient),args.toArray());
    }
    public Map<String,Object> detail(long patient,long record){
        List<Map<String,Object>>rows=jdbc.query("SELECT "+SELF_COLUMNS+",remark FROM bp_self_monitor_record WHERE patient_id=? AND id=? LIMIT 1",(rs,i)->selfRow(rs,patient,true),patient,record);
        if(rows.isEmpty())throw OriginalMeasurementReadAuthorization.denied();return rows.get(0);
    }
    /** 所选窗口各取一个真正最新值，不能从某一页前100条推算最新。 */
    public Map<String,Object> latestSelf(long patient,LocalDate from,LocalDate until,boolean bloodPressure){
        String present=bloodPressure?"(systolic_bp IS NOT NULL OR diastolic_bp IS NOT NULL)":"blood_glucose IS NOT NULL";
        List<Map<String,Object>>rows=jdbc.query("SELECT "+SELF_COLUMNS+" FROM bp_self_monitor_record WHERE patient_id=? AND record_date>=? AND record_date<? AND "+present+" ORDER BY "+OriginalMeasurementCursor.SELF_ORDER+" LIMIT 1",(rs,i)->selfRow(rs,patient,false),patient,from.toString(),until.toString());
        return rows.isEmpty()?null:rows.get(0);
    }
    /** 仅供原算法使用；原单位和原时刻仍由独立白名单map交给新界面。 */
    public static org.familyhealthcare.entity.BpSelfMonitorRecord legacyRecord(Map<String,Object> raw){
        if(raw==null)return null;
        try{
            org.familyhealthcare.entity.BpSelfMonitorRecord r=new org.familyhealthcare.entity.BpSelfMonitorRecord();r.setId((Long)raw.get("id"));r.setPatientId((Long)raw.get("patientId"));
            r.setRecordDate(raw.get("recordDate")==null?null:LocalDate.parse((String)raw.get("recordDate")));r.setRecordTime((String)raw.get("recordTime"));r.setMeasureType((String)raw.get("measureType"));
            r.setSystolicBp(integer(raw.get("systolicBp")));r.setDiastolicBp(integer(raw.get("diastolicBp")));r.setBloodGlucose((java.math.BigDecimal)raw.get("bloodGlucose"));r.setBgUnit((String)raw.get("bgUnit"));r.setMeasurePeriod((String)raw.get("measurePeriod"));r.setSourceType((String)raw.get("sourceType"));r.setVerificationStatus((String)raw.get("verificationStatus"));return r;
        }catch(RuntimeException failure){throw OriginalMeasurementReadAuthorization.unavailable();}
    }
    private static Integer integer(Object value){return value==null?null:((java.math.BigDecimal)value).intValueExact();}
    private static void position(StringBuilder sql,List<Object>args,boolean self,Map<String,Object>p){
        Object time=p.get("time"),id=p.get("id");
        if(!self){if(time==null){sql.append(" AND (measured_at IS NULL AND id<?)");args.add(id);}else{sql.append(" AND (measured_at<? OR measured_at IS NULL OR (measured_at=? AND id<?))");args.add(time);args.add(time);args.add(id);}return;}
        Object day=p.get("date");
        if(day==null)sql.append(" AND (record_date IS NULL AND (");
        else{sql.append(" AND (record_date<? OR record_date IS NULL OR (record_date=? AND (");args.add(day);args.add(day);}
        if(time==null){sql.append("record_time IS NULL AND id<?");args.add(id);}
        else{sql.append("record_time<? OR record_time IS NULL OR (record_time=? AND id<?)");args.add(time);args.add(time);args.add(id);}
        sql.append(day==null?"))":")))");
    }
    private static long id(ResultSet rs,long patient)throws SQLException{long id=rs.getLong("id");if(rs.wasNull()||id<=0||rs.getLong("patient_id")!=patient||rs.wasNull())throw OriginalMeasurementReadAuthorization.unavailable();return id;}
    private static Map<String,Object> selfRow(ResultSet rs,long patient,boolean detail)throws SQLException{
        Map<String,Object>row=map("id",id(rs,patient),"patientId",patient,"recordDate",rs.getString("record_date"),"recordTime",rs.getString("record_time"),"measureType",rs.getString("measure_type"),
                "systolicBp",rs.getBigDecimal("systolic_bp"),"diastolicBp",rs.getBigDecimal("diastolic_bp"),"bloodGlucose",rs.getBigDecimal("blood_glucose"),"bgUnit",rs.getString("bg_unit"),"measurePeriod",rs.getString("measure_period"),"sourceType",rs.getString("source_type"),"verificationStatus",rs.getString("verification_status"));
        if(detail)row.put("remark",rs.getString("remark"));return row;
    }
    private static Map<String,Object> journeyRow(ResultSet rs,long patient)throws SQLException{return map("id",id(rs,patient),"patient_id",patient,"metric_type",rs.getString("metric_type"),"metric_name",rs.getString("metric_name"),"value_primary",rs.getBigDecimal("value_primary"),"value_secondary",rs.getBigDecimal("value_secondary"),"unit",rs.getString("unit"),"measured_at",rs.getString("measured_at"),"source_type",rs.getString("source_type"),"status",rs.getString("status"));}
}
