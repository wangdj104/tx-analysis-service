package org.familyhealthcare.service.alertworkflow;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;

/** 只核查当前读取需要的固定结构，不运行迁移、写守卫或全库扫描。 */
@Service
public class MeasurementReadReadiness {
    private final JdbcTemplate jdbc;
    public MeasurementReadReadiness(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public void requireTrend(String sourceType) {
        jdbc.execute((ConnectionCallback<Void>)connection->{
            DatabaseMetaData metadata=connection.getMetaData();
            boolean self="BP_SELF_MONITOR".equals(sourceType);
            String nativeTable=self?"bp_self_monitor_record":"health_measurement";
            Map<String,Set<Integer>> nativeColumns=columns("id patient_id",integers());
            if(self){
                add(nativeColumns,"record_date",types(Types.DATE));add(nativeColumns,"record_time measure_type bg_unit measure_period verification_status",strings());
                add(nativeColumns,"systolic_bp diastolic_bp",integers());add(nativeColumns,"blood_glucose",decimals());
            }else{
                add(nativeColumns,"metric_type metric_name unit source_type",strings());add(nativeColumns,"value_primary value_secondary",decimals());add(nativeColumns,"measured_at",types(Types.TIMESTAMP));
            }
            check(connection,metadata,nativeTable,nativeColumns,self?Arrays.asList("patient_id","record_date","record_time"):Arrays.asList("patient_id","metric_type","measured_at"),false,true);
            Map<String,Set<Integer>> identity=columns("source_id patient_id current_revision lock_version",integers());add(identity,"source_type status",strings());
            check(connection,metadata,"alert_source_identity",identity,Arrays.asList("source_type","source_id"),true,false);
            Map<String,Set<Integer>> target=columns("patient_id systolic_min systolic_max diastolic_min diastolic_max",integers());
            add(target,"fasting_glucose_min fasting_glucose_max postmeal_glucose_min postmeal_glucose_max target_weight weight_gain_limit",decimals());
            check(connection,metadata,"patient_health_target",target,Collections.singletonList("patient_id"),true,false);
            return null;
        });
    }
    public void requireItems(){requireAlert(true);}
    public void requireSelectedItem(){requireAlert(false);}
    private void requireAlert(boolean picker){
        jdbc.execute((ConnectionCallback<Void>)connection->{
            Map<String,Set<Integer>> required=columns("id patient_id workflow_version source_id cycle_no",integers());
            add(required,"source_type metric_key workflow_state workflow_mode",strings());if(picker)add(required,"due_at",types(Types.TIMESTAMP));
            check(connection,connection.getMetaData(),"alert_record",required,picker?Arrays.asList("patient_id","workflow_version","workflow_state","due_at","id"):Collections.singletonList("id"),false,true);
            return null;
        });
    }
    public void requireItemEvents(){
        jdbc.execute((ConnectionCallback<Void>)connection->{
            Map<String,Set<Integer>> required=columns("id alert_id patient_id cycle_no",integers());
            add(required,"event_type from_status to_status",strings());add(required,"occurred_at recorded_at",types(Types.TIMESTAMP));
            check(connection,connection.getMetaData(),"alert_event",required,Arrays.asList("alert_id","cycle_no","id"),false,true);return null;
        });
    }
    public void requireItemSources(Set<String> sources){
        jdbc.execute((ConnectionCallback<Void>)connection->{
            DatabaseMetaData metadata=connection.getMetaData();
            // 只查本页真实使用的两类白名单来源，不能借用趋势就绪检查要求目标或测量值字段。
            for(String source:sources){
                if(!AlertWorkflowContracts.SOURCE_TYPES.contains(source))throw unavailable();boolean self="BP_SELF_MONITOR".equals(source);
                Map<String,Set<Integer>> required=columns("id patient_id",integers());
                check(connection,metadata,self?"bp_self_monitor_record":"health_measurement",required,Collections.singletonList("id"),false,true);
            }
            Map<String,Set<Integer>> identity=columns("source_id patient_id current_revision lock_version",integers());add(identity,"source_type status",strings());
            check(connection,metadata,"alert_source_identity",identity,Arrays.asList("source_type","source_id"),true,false);return null;
        });
    }
    private void check(Connection connection,DatabaseMetaData metadata,String name,Map<String,Set<Integer>> required,List<String> order,boolean unique,boolean primary)throws SQLException {
        String table=metadata.storesUpperCaseIdentifiers()?name.toUpperCase(Locale.ROOT):name;
        String escape=metadata.getSearchStringEscape();
        if(escape==null||escape.isEmpty())throw unavailable();
        String pattern=table.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");
        Set<String> found=new HashSet<>();
        try(ResultSet rows=metadata.getColumns(connection.getCatalog(),null,pattern,"%")){
            while(rows.next()){
                if(!table.equals(rows.getString("TABLE_NAME")))continue;
                String column=rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                if(required.containsKey(column)){
                    if(!required.get(column).contains(rows.getInt("DATA_TYPE")))throw unavailable();
                    found.add(column);
                }
            }
        }
        if(!found.containsAll(required.keySet()))throw unavailable();
        Map<String,SortedMap<Integer,String>> indexes=new HashMap<>();Set<String> nonUnique=new HashSet<>();
        try(ResultSet rows=metadata.getIndexInfo(connection.getCatalog(),null,table,false,false)){
            while(rows.next()){
                if(!table.equals(rows.getString("TABLE_NAME")))continue;
                String index=rows.getString("INDEX_NAME"),column=rows.getString("COLUMN_NAME");
                if(index==null||column==null)continue;
                indexes.computeIfAbsent(index,key->new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"),column.toLowerCase(Locale.ROOT));
                if(rows.getBoolean("NON_UNIQUE"))nonUnique.add(index);
            }
        }
        boolean ordered=false;
        for(Map.Entry<String,SortedMap<Integer,String>> index:indexes.entrySet()){
            List<String> keys=new ArrayList<>(index.getValue().values());
            if(keys.size()>=order.size()&&keys.subList(0,order.size()).equals(order)&&(!unique||keys.size()==order.size()&&!nonUnique.contains(index.getKey())))ordered=true;
        }
        if(!ordered)throw unavailable();
        if(primary){
            SortedMap<Integer,String> keys=new TreeMap<>();
            try(ResultSet rows=metadata.getPrimaryKeys(connection.getCatalog(),null,table)){
                while(rows.next())if(table.equals(rows.getString("TABLE_NAME")))keys.put(rows.getInt("KEY_SEQ"),rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            if(!new ArrayList<>(keys.values()).equals(Collections.singletonList("id")))throw unavailable();
        }
    }
    static AlertWorkflowException unavailable(){return new AlertWorkflowException(503,"READ_UNAVAILABLE","Measurement reading is currently unavailable. Try again later.");}
    private static Map<String,Set<Integer>> columns(String names,Set<Integer> types){Map<String,Set<Integer>> result=new HashMap<>();add(result,names,types);return result;}
    private static void add(Map<String,Set<Integer>> columns,String names,Set<Integer> types){for(String name:names.split(" "))columns.put(name,types);}
    private static Set<Integer> types(Integer...values){return new HashSet<>(Arrays.asList(values));}
    private static Set<Integer> integers(){return types(Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT);}
    private static Set<Integer> decimals(){return types(Types.DECIMAL,Types.NUMERIC);}
    private static Set<Integer> strings(){return types(Types.VARCHAR,Types.CHAR,Types.NVARCHAR,Types.NCHAR);}
}
