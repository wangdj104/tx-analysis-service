package org.familyhealthcare.service.archivestock;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.familyhealthcare.service.archivestock.ArchiveStockNotificationContracts.*;
import static org.familyhealthcare.service.archivestock.ArchiveStockNotificationRepository.one;

/** Dedicated fixed domain. A replay returns history and never invokes the state transition. */
public final class ArchiveStockNotificationCommandStore {
    private ArchiveStockNotificationCommandStore(){ }
    public static Map<String,Object> execute(JdbcTemplate jdbc,long actor,Command command,Instant now,Runnable validateNew,Supplier<Map<String,Object>> change){
        WorkflowData.mutationTransaction(jdbc);id(actor);
        Map<String,Object> existing=find(jdbc,actor,command.stockId,command.commandKey,true,command);
        if(existing!=null)return existing;
        // Native INSERT guards require these new-command preconditions. Never validate them before a completed replay.
        Objects.requireNonNull(validateNew).run();
        try {one(jdbc.update(c->{java.sql.PreparedStatement p=c.prepareStatement("INSERT INTO archive_stock_notification_command(actor_id,command_key,stock_id,expected_version,operation,payload_hash,created_at) VALUES(?,?,?,?,?,?,?)");
            p.setLong(1,actor);p.setString(2,command.commandKey);p.setLong(3,command.stockId);p.setLong(4,command.expectedControlVersion);p.setString(5,command.operation.name());p.setString(6,command.payloadHash);WorkflowData.time(p,7,now);return p;}));}
        catch(DuplicateKeyException duplicate){Map<String,Object> result=find(jdbc,actor,command.stockId,command.commandKey,true,command);if(result==null)throw unknown();return result;}
        AtomicReference<Map<String,Object>> completed=new AtomicReference<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){
            Map<String,Object> result=completed.get();if(result==null)throw integrity();
            Map<String,Object> stored=find(jdbc,actor,command.stockId,command.commandKey,true,command);
            if(!result.equals(stored))throw integrity();
            ArchiveStockNotificationRepository.State current=new ArchiveStockNotificationRepository(jdbc).state(command.stockId,false);
            if(current==null||current.controlVersion<id(result.get("controlVersion")))throw integrity();
        }});
        Map<String,Object> result=storedReceipt(WorkflowData.json(change.get()));
        if(id(result.get("stockId"))!=command.stockId||id(result.get("controlVersion"))!=next(command.expectedControlVersion)||!command.operation.name().equals(result.get("operation"))||!timestamp(now).equals(result.get("recordedAt")))throw integrity();
        one(jdbc.update("UPDATE archive_stock_notification_command SET result_json=? WHERE actor_id=? AND command_key=? AND stock_id=? AND result_json IS NULL",WorkflowData.json(result),actor,command.commandKey,command.stockId));
        completed.set(result);return result;
    }
    /** Current source identity and read authority must already have been established. Never reserves. */
    public static Map<String,Object> lookup(JdbcTemplate jdbc,long actor,long stock,String key){
        WorkflowData.mutationTransaction(jdbc);Map<String,Object> result=find(jdbc,id(actor),id(stock),key(key),false,null);
        if(result==null)throw unknown();return result;
    }
    private static Map<String,Object> find(JdbcTemplate jdbc,long actor,long stock,String key,boolean lock,Command command){
        List<Map<String,Object>> rows=jdbc.query("SELECT stock_id,expected_version,operation,payload_hash,created_at,OCTET_LENGTH(result_json) AS receipt_bytes,CASE WHEN OCTET_LENGTH(result_json)<=512 THEN result_json ELSE NULL END AS result_json FROM archive_stock_notification_command WHERE actor_id=? AND command_key=?"+(lock?" FOR UPDATE":""),(r,n)->{
            if(r.getLong("stock_id")!=stock)throw conflict("KEY_CONFLICT");
            long expected=version(r.getLong("expected_version"));String operation=r.getString("operation");
            if(command!=null&&(expected!=command.expectedControlVersion||!command.operation.name().equals(operation)||!command.payloadHash.equals(r.getString("payload_hash"))))throw conflict("KEY_CONFLICT");
            String raw=r.getString("result_json");if(raw==null)throw integrity();
            Map<String,Object> result=storedReceipt(raw);
            if(id(result.get("stockId"))!=stock||id(result.get("controlVersion"))!=next(expected)||!Objects.equals(result.get("operation"),operation)
                    ||!Instant.parse((String)result.get("recordedAt")).equals(Instant.parse(WorkflowData.time(r,"created_at"))))throw integrity();return result;
        },actor,key);if(rows.size()>1)throw integrity();return rows.isEmpty()?null:rows.get(0);
    }
}
