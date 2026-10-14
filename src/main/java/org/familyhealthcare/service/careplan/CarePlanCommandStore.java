package org.familyhealthcare.service.careplan;

import org.familyhealthcare.service.workflow.WorkflowCommandExecutor;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 同一人重复提交同一命令号才复用结果；权限是否仍有效由调用方在等待后重查。 */
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
        String normalized=validateKey(key);
        // Preserve fresh-result compatibility; only persisted/replayed results omit protected evidence.
        AtomicReference<Map<String,Object>>fresh=new AtomicReference<>();
        try {
            Map<String,Object>safe=WorkflowCommandExecutor.execute(jdbc,WorkflowCommandExecutor.Domain.CARE_PLAN,actor,normalized,payload,plan,version,properties.now(),()->{
                Map<String,Object>result=change.get();
                if(result==null)throw new IllegalStateException("Command did not produce a result.");
                CarePlanContracts.requireId(result.containsKey("planId")?result.get("planId"):result.get("id"),"planId");
                fresh.set(result);return result;
            });
            return fresh.get()==null?safe:fresh.get();
        }catch(WorkflowException error){
            if("COMMAND_CONFLICT".equals(error.getErrorCode()))throw new CarePlanException(error.getStatus(),error.getErrorCode(),"This command key was already used for a different request.");
            throw new CarePlanException(error.getStatus(),error.getErrorCode(),error.getMessage());
        }
    }
    public static String validateKey(String value){
        try{return WorkflowCommandExecutor.validateKey(value);}
        catch(WorkflowException invalid){throw CarePlanException.invalid("commandKey must be a UUID.");}
    }
}
