package org.familyhealthcare.service.careplan;

import org.familyhealthcare.service.workflow.WorkflowData;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Package-local compatibility delegates, never an authority source. */
final class CarePlanData {
    private CarePlanData() { }
    static Map<String,Object> map(Object... pairs){return WorkflowData.map(pairs);}
    static String json(Object value){return WorkflowData.json(value);}
    static Map<String,Object> object(String value){return WorkflowData.object(value);}
    static Map<String,Object> cachedResult(Map<String,Object>result){return WorkflowData.cachedResult(result);}
    static String hash(String value){return WorkflowData.hash(value);}
    static Calendar utc(){return WorkflowData.utc();}
    static void time(PreparedStatement ps,int index,Instant value)throws SQLException{WorkflowData.time(ps,index,value);}
    static String time(ResultSet rs,String column)throws SQLException{return WorkflowData.time(rs,column);}
    static Long nullableId(ResultSet rs,String column)throws SQLException{return WorkflowData.nullableId(rs,column);}
    static long id(Object value){return WorkflowData.id(value);}
    static long version(long version){if(version<0)throw CarePlanException.invalid("expectedVersion 必须为非负数。");return version;}
    static CarePlanException conflict(){return new CarePlanException(409,"VERSION_CONFLICT","照护计划已更新，请刷新后重试。");}
    static void transaction(){WorkflowData.transaction();}
    /** 写入时要看到刚提交的授权和版本，不能沿用外层事务留下的旧快照。 */
    static void mutationTransaction(org.springframework.jdbc.core.JdbcTemplate jdbc){WorkflowData.mutationTransaction(jdbc);}
    static void limit(int limit){if(limit<1||limit>100)throw CarePlanException.invalid("limit 必须在 1 至 100 之间。");}
    @SuppressWarnings("unchecked")static List<Map<String,Object>> actions(Map<String,Object>body){return(List<Map<String,Object>>)body.get("actions");}
    @SuppressWarnings("unchecked")static List<Map<String,Object>> evidence(Map<String,Object>action){Object refs=action.get("evidence");return refs==null?Collections.emptyList():(List<Map<String,Object>>)refs;}
}
