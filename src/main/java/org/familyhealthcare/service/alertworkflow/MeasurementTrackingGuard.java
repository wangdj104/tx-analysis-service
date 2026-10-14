package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.ResultSet;
import java.util.*;

/** Available even when the feature is off. Only an actually absent pre-upgrade schema permits legacy fallback. */
@Service
public class MeasurementTrackingGuard {
    private final JdbcTemplate jdbc;
    public MeasurementTrackingGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void requireUntrackedMutation(String type,long sourceId) {
        WorkflowData.mutationTransaction(jdbc);
        String table="HEALTH_MEASUREMENT".equals(type)?"health_measurement":"BP_SELF_MONITOR".equals(type)?"bp_self_monitor_record":null;
        if(table==null)throw AlertWorkflowException.invalid("Unsupported source type.");
        jdbc.queryForList("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",Long.class,sourceId);
        if(tracked(type,sourceId))throw new AlertWorkflowException(409,"WORKFLOW_CORRECTION_REQUIRED","This reading has follow-up history. Enable follow-up and use the versioned correction or invalidation action.");
    }
    public boolean tracked(String type,long sourceId) {
        if(!available())return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM alert_source_identity WHERE source_type=? AND source_id=?",Integer.class,type,sourceId)>0;
    }
    private boolean available() {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
            Set<String> tables=new HashSet<>();
            try(ResultSet rs=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})){while(rs.next())tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));}
            Set<String> managed=new HashSet<>(Arrays.asList("alert_source_identity","alert_source_revision","alert_source_link","alert_evidence","alert_command","measurement_command","alert_notification"));
            boolean workflowColumns=false;
            for(String table:Arrays.asList("alert_record","ALERT_RECORD"))try(ResultSet rs=connection.getMetaData().getColumns(connection.getCatalog(),null,table,"%")){while(rs.next())if("workflow_version".equalsIgnoreCase(rs.getString("COLUMN_NAME")))workflowColumns=true;}
            if(tables.containsAll(managed)&&tables.contains("alert_event")&&workflowColumns)return true;
            boolean marker=tables.contains("alert_record")&&jdbc.queryForObject("SELECT COUNT(*) FROM alert_record WHERE alert_type='MEASUREMENT_FOLLOW_UP'",Integer.class)>0;
            if(!Collections.disjoint(tables,managed)||workflowColumns||marker)throw new AlertWorkflowException(503,"WORKFLOW_SCHEMA_UNAVAILABLE","Follow-up storage is incomplete. Reconcile the database upgrade before changing this reading.");
            return false;
        });
    }
}
