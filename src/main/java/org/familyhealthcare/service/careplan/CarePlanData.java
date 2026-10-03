package org.familyhealthcare.service.careplan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Package-local JSON and UTC persistence mechanics, never an authority source. */
final class CarePlanData {
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true)
            .configure(DeserializationFeature.USE_LONG_FOR_INTS,true);
    private CarePlanData() { }
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }
    static String json(Object value) {
        try {return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode care-plan data",e);}
    }
    static Map<String,Object> object(String value) {
        try {Map<String,Object> result=JSON.readValue(value,new TypeReference<Map<String,Object>>(){});restoreIntegers(result);return result;}
        catch(Exception e){throw new IllegalStateException("Invalid stored care-plan data",e);}
    }
    @SuppressWarnings("unchecked") private static void restoreIntegers(Object value) {
        if(value instanceof Map){Map<String,Object> m=(Map<String,Object>)value;for(String k:m.keySet()){
            Object v=m.get(k);if(Arrays.asList("workflowVersion","ordinal","revisionNo").contains(k)&&v instanceof Number)m.put(k,((Number)v).intValue());else restoreIntegers(v);}}
        else if(value instanceof List)for(Object item:(List<?>)value)restoreIntegers(item);
    }
    /** Cache original command results without copying clinical evidence metadata. */
    static Map<String,Object>cachedResult(Map<String,Object>result){Map<String,Object>copy=object(json(result));stripEvidence(copy);return copy;}
    @SuppressWarnings("unchecked")private static void stripEvidence(Object value){
        if(value instanceof Map){Map<String,Object>m=(Map<String,Object>)value;for(Map.Entry<String,Object>entry:m.entrySet()){
            if("evidence".equals(entry.getKey())&&entry.getValue() instanceof List){for(Object item:(List<?>)entry.getValue()){
                Map<String,Object>ref=(Map<String,Object>)item;ref.remove("title");ref.remove("detailLink");ref.put("restricted",true);}}
            else stripEvidence(entry.getValue());}}
        else if(value instanceof List)for(Object item:(List<?>)value)stripEvidence(item);
    }
    static String hash(String value) {
        try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    static Calendar utc() {return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    static void time(PreparedStatement ps,int index,Instant value)throws SQLException {if(value==null)ps.setNull(index,Types.TIMESTAMP);else ps.setTimestamp(index,Timestamp.from(value),utc());}
    static String time(ResultSet rs,String column)throws SQLException {Timestamp value=rs.getTimestamp(column,utc());return value==null?null:value.toInstant().toString();}
    static Long nullableId(ResultSet rs,String column)throws SQLException {long value=rs.getLong(column);return rs.wasNull()?null:value;}
    static long id(Object value){return ((Number)value).longValue();}
    static long version(long version){if(version<0)throw CarePlanException.invalid("expectedVersion must be non-negative.");return version;}
    static CarePlanException conflict(){return new CarePlanException(409,"VERSION_CONFLICT","The care plan changed. Refresh before trying again.");}
    static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("A care-plan write requires an active transaction.");}
    /** Mutation transactions must not inherit a stale REPEATABLE READ snapshot. */
    static void mutationTransaction(JdbcTemplate jdbc){
        transaction();javax.sql.DataSource dataSource=jdbc.getDataSource();Connection connection=DataSourceUtils.getConnection(dataSource);
        try{
            if(!DataSourceUtils.isConnectionTransactional(connection,dataSource)||connection.getAutoCommit()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
                throw new IllegalStateException("Care-plan mutations require an effective READ_COMMITTED transaction on their datasource.");
        }catch(SQLException ex){throw new IllegalStateException("Cannot verify the care-plan transaction isolation.",ex);}
        finally{DataSourceUtils.releaseConnection(connection,dataSource);}
    }
    static void limit(int limit){if(limit<1||limit>100)throw CarePlanException.invalid("limit must be between 1 and 100.");}
    @SuppressWarnings("unchecked")static List<Map<String,Object>> actions(Map<String,Object>body){return(List<Map<String,Object>>)body.get("actions");}
    @SuppressWarnings("unchecked")static List<Map<String,Object>> evidence(Map<String,Object>action){Object refs=action.get("evidence");return refs==null?Collections.emptyList():(List<Map<String,Object>>)refs;}
}
