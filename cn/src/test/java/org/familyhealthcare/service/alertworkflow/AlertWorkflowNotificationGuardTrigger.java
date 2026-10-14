package org.familyhealthcare.service.alertworkflow;
import org.h2.api.Trigger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.*;
import java.util.*;
/** Executes the shipped SQL guard predicate on real synthetic worker writes; not native MySQL. */
public final class AlertWorkflowNotificationGuardTrigger implements Trigger {
    private final Map<String,Integer> columns=new HashMap<>();
    public void init(Connection connection,String schema,String name,String table,boolean before,int type)throws SQLException{
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT * FROM "+table+" WHERE 1=0")){ResultSetMetaData metadata=rows.getMetaData();for(int i=1;i<=metadata.getColumnCount();i++)columns.put(metadata.getColumnName(i).toLowerCase(Locale.ROOT),i-1);}
    }
    public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException{
        try{AlertWorkflowSqlPredicates sql=new AlertWorkflowSqlPredicates();JdbcTemplate jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            if(sql.rejects(jdbc,oldRow==null?"aw57_notification_bi":"aw57_notification_bu",row(oldRow),row(newRow)))throw new SQLException("Synthetic shipped notification guard rejected transition.","45000",1644);
        }catch(SQLException e){throw e;}catch(Exception e){throw new SQLException("Cannot execute synthetic product predicate.",e);}
    }
    private Map<String,Object> row(Object[] values){Map<String,Object> row=new HashMap<>();if(values!=null)for(Map.Entry<String,Integer> column:columns.entrySet())row.put(column.getKey(),values[column.getValue()]);return row;}
    public void close(){}public void remove(){}
}
