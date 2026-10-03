package org.familyhealthcare.service.careplan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.function.Supplier;

/** Atomic actor/key reservation. Call only after fresh authorization, in the aggregate transaction. */
@Service
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
public class CarePlanCommandStore {
    private final JdbcTemplate jdbc; private final CarePlanProperties properties;
    public CarePlanCommandStore(JdbcTemplate jdbc,CarePlanProperties properties){this.jdbc=jdbc;this.properties=properties;}
    public Map<String,Object> execute(long actorId,String commandKey,String canonicalPayload,long planId,long expectedVersion,Supplier<Map<String,Object>>change){
        if(planId<=0)throw CarePlanException.invalid("planId must be positive.");
        return reserve(actorId,commandKey,canonicalPayload,planId,expectedVersion,change);
    }
    /** The nullable create reservation must be completed or rolled back before this transaction ends. */
    public Map<String,Object> executeCreate(long actorId,String commandKey,String canonicalPayload,Supplier<Map<String,Object>>change){return reserve(actorId,commandKey,canonicalPayload,null,0,change);}
    private Map<String,Object>reserve(long actor,String key,String payload,Long plan,long version,Supplier<Map<String,Object>>change){
        properties.requireEnabled();CarePlanData.mutationTransaction(jdbc);CarePlanData.version(version);
        if(actor<=0||payload==null||change==null)throw CarePlanException.invalid("Invalid command.");
        final String normalized=validateKey(key),hash=CarePlanData.hash(payload);
        try {jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,created_at) VALUES(?,?,?,?,?,?)");
            ps.setLong(1,actor);ps.setString(2,normalized);if(plan==null)ps.setNull(3,java.sql.Types.BIGINT);else ps.setLong(3,plan);ps.setLong(4,version);ps.setString(5,hash);CarePlanData.time(ps,6,properties.now());return ps;});}
        catch(DuplicateKeyException duplicate){
            // A locking read sees the winning committed command even under MySQL REPEATABLE READ.
            List<Map<String,Object>>rows=jdbc.query("SELECT plan_id,expected_version,payload_hash,result_json FROM care_plan_command WHERE actor_id=? AND command_key=? FOR UPDATE",(rs,i)->CarePlanData.map("planId",CarePlanData.nullableId(rs,"plan_id"),"version",rs.getLong("expected_version"),"hash",rs.getString("payload_hash"),"result",rs.getString("result_json")),actor,normalized);
            if(rows.isEmpty())throw new IllegalStateException("Command reservation was not found",duplicate);
            Map<String,Object>row=rows.get(0);
            if(!hash.equals(row.get("hash"))||version!=CarePlanData.id(row.get("version"))||(plan!=null&&!plan.equals(row.get("planId"))))throw new CarePlanException(409,"COMMAND_CONFLICT","This command key was already used for a different request.");
            if(row.get("planId")==null||row.get("result")==null)throw new IllegalStateException("Incomplete committed care-plan command.");
            return CarePlanData.object((String)row.get("result"));
        }
        AtomicBoolean completedReservation=new AtomicBoolean(false);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){
                if(!completedReservation.get())throw new IllegalStateException("An incomplete care-plan command cannot commit.");
            }
        });
        Map<String,Object>result=change.get();
        if(result==null)throw new IllegalStateException("Command did not produce a result.");
        Object id=result.containsKey("planId")?result.get("planId"):result.get("id");
        long resultPlan=CarePlanContracts.requireId(id,"planId");
        if(plan!=null&&resultPlan!=plan)throw new IllegalStateException("Command result has a different plan.");
        int completed=jdbc.update("UPDATE care_plan_command SET plan_id=?,result_json=? WHERE actor_id=? AND command_key=? AND result_json IS NULL",resultPlan,CarePlanData.json(CarePlanData.cachedResult(result)),actor,normalized);
        if(completed!=1)throw new IllegalStateException("Command reservation could not be completed.");
        completedReservation.set(true);
        return result;
    }
    public static String validateKey(String value){
        if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw CarePlanException.invalid("commandKey must be a UUID.");
        return UUID.fromString(value).toString();
    }
}
