package org.familyhealthcare.service.archivestock;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.familyhealthcare.service.archivestock.ArchiveStockNotificationContracts.*;

/** Sidecar locks are terminal: callers acquire all existing source/authority/HH locks first. */
@Repository
public class ArchiveStockNotificationRepository {
    private final JdbcTemplate jdbc;
    public ArchiveStockNotificationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public static final class State {
        public final long stockId, controlVersion, enabledAfterStockVersion;
        public final boolean enabled;
        public final String restoredAt,updatedAt;
        private State(long stock,int enabled,long version,long watermark,String restored,String updated){
            this.stockId=id(stock);this.controlVersion=version(version);this.enabledAfterStockVersion=version(watermark);
            if(enabled!=0&&enabled!=1||restored==null||updated==null||version==0&&(enabled!=0||watermark!=0))throw integrity();
            this.enabled=enabled==1;this.restoredAt=restored;this.updatedAt=updated;
        }
        public Map<String,Object> asMap(){return WorkflowData.map("stockId",stockId,"marked",true,"enabled",enabled,"controlVersion",controlVersion,
                "enabledAfterStockVersion",enabledAfterStockVersion,"restoredAt",restoredAt,"updatedAt",updatedAt);}
    }
    public State state(long stock,boolean lock){
        if(lock)WorkflowData.mutationTransaction(jdbc);
        List<State> rows=jdbc.query("SELECT stock_id,enabled,control_version,enabled_after_stock_version,restored_at,updated_at FROM archive_stock_notification_state WHERE stock_id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new State(r.getLong(1),r.getInt(2),r.getLong(3),r.getLong(4),WorkflowData.time(r,"restored_at"),WorkflowData.time(r,"updated_at")),id(stock));
        if(rows.size()>1)throw integrity();return rows.isEmpty()?null:rows.get(0);
    }
    /** One patient-scoped state batch for an already authorized list; no source facts are derived. */
    public Map<Long,State> states(long patient){
        List<State> rows=jdbc.query("SELECT p.stock_id,p.enabled,p.control_version,p.enabled_after_stock_version,p.restored_at,p.updated_at FROM archive_stock_notification_state p JOIN medication_stock s ON s.id=p.stock_id JOIN medication m ON m.id=s.medication_id AND m.patient_id=s.patient_id WHERE s.patient_id=?",
                (r,n)->new State(r.getLong(1),r.getInt(2),r.getLong(3),r.getLong(4),WorkflowData.time(r,"restored_at"),WorkflowData.time(r,"updated_at")),id(patient));
        Map<Long,State> result=new LinkedHashMap<>();for(State row:rows)if(result.put(row.stockId,row)!=null)throw integrity();return result;
    }
    public void requireRestoreTransaction(JdbcTemplate source){
        if(source.getDataSource()!=jdbc.getDataSource())throw new IllegalStateException("Restore policy must use the restore DataSource.");
        WorkflowData.mutationTransaction(jdbc);
    }
    /** Called only for a newly inserted restored identity, inside that same verified transaction. */
    public void createRestoredOff(long stock){
        WorkflowData.mutationTransaction(jdbc);Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        one(jdbc.update(c->{java.sql.PreparedStatement p=c.prepareStatement("INSERT INTO archive_stock_notification_state(stock_id,enabled,control_version,enabled_after_stock_version,restored_at,updated_at) VALUES(?,0,0,0,?,?)");
            p.setLong(1,id(stock));WorkflowData.time(p,2,now);WorkflowData.time(p,3,now);return p;}));
    }
    public void transition(State before,Command command,long stockVersion,Instant now){
        WorkflowData.mutationTransaction(jdbc);long next=next(before.controlVersion);
        if(before.stockId!=command.stockId||before.controlVersion!=command.expectedControlVersion)throw conflict("VERSION_CHANGED");
        boolean enabled=command.operation==Operation.ENABLE;if(before.enabled==enabled)throw conflict("NO_CHANGE");
        long watermark=enabled?version(stockVersion):before.enabledAfterStockVersion;
        one(jdbc.update(c->{java.sql.PreparedStatement p=c.prepareStatement("UPDATE archive_stock_notification_state SET enabled=?,control_version=?,enabled_after_stock_version=?,updated_at=? WHERE stock_id=? AND control_version=? AND enabled=?");
            p.setInt(1,enabled?1:0);p.setLong(2,next);p.setLong(3,watermark);WorkflowData.time(p,4,now);p.setLong(5,before.stockId);p.setLong(6,before.controlVersion);p.setInt(7,before.enabled?1:0);return p;}));
    }
    public static void one(int count){if(count!=1)throw integrity();}
}
