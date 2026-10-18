package org.familyhealthcare.service.stockworkflow;
import org.familyhealthcare.service.workflow.*;
import org.familyhealthcare.service.medicationexecution.MedicationExecutionQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.math.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
@Service public class StockWorkflowQueryService  {
    private final JdbcTemplate jdbc;
    private final StockWorkflowProperties properties;
    private final StockWorkflowAuthorizationService auth;
    private final StockWorkflowRepository repo;
    private final HouseholdWorkflowSchema schema;
    public StockWorkflowQueryService(JdbcTemplate jdbc,StockWorkflowProperties properties,StockWorkflowAuthorizationService auth,StockWorkflowRepository repo,HouseholdWorkflowSchema schema)  {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.schema=schema;
    }
    /** 旧库存页仅在本域当前授权下返回受跟踪事实；来源查询已由调用方的严格结构门保护。 */
    public List<Map<String,Object>> legacyProjection(long actor,long patient,List<Map<String,Object>> rows) {
        boolean read=auth.canRead(actor,patient),returnedTracked=false;List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            boolean tracked=id(row.get("workflow_version"))!=0||row.get("current_work_id")!=null||id(row.get("medication_workflow_version"))!=0||row.get("medication_execution_id")!=null;
            if(tracked&&!read)continue;
            Map<String,Object> safe=new LinkedHashMap<>();
            // JDBC 列名大小写不能改变旧 API 字段；保留显式显示别名，原生列统一小写。
            for(Map.Entry<String,Object> field:row.entrySet()){String key=field.getKey().toLowerCase(Locale.ROOT);for(String alias:Arrays.asList("drugName","isActive","dailyUse","estimatedDays","low"))if(alias.equalsIgnoreCase(key)){key=alias;break;}safe.put(key,field.getValue());}
            safe.remove("medication_workflow_version");safe.remove("medication_execution_id");
            if(tracked){safe.put("nextAction","STOCK_WORKFLOW_REQUIRED");safe.put("usageKnown",row.get("dailyUse")!=null);returnedTracked=true;}
            out.add(safe);
        }if(returnedTracked)auth.requireRead(actor,patient);return out;
    }
    /** 旧组合页返回前再查当前本域授权，不沿用先前投影时的资格。 */
    public void finishLegacyRead(long actor,long patient){auth.requireRead(actor,patient);}
    public Map<String,Object> capabilities(long actor,long patient)  {
        auth.requireRead(actor,patient);
        HouseholdWorkflowSchema.StorageReadiness ready=schema.inspectStorageMetadata();
        return map("actorId",actor,"enabled",properties.isEnabled(),"schemaStatus",ready.getStatus().name(),"blockedReasons",ready.getReasons(),"read",ready.getStatus()==HouseholdWorkflowSchema.StorageStatus.STRUCTURALLY_READY,"create",properties.isEnabled()&&ready.getStatus()==HouseholdWorkflowSchema.StorageStatus.STRUCTURALLY_READY&&auth.canWrite(actor,patient),"source",false,"retry",admin(actor));
    }
    public Map<String,Object> list(long actor,long patient,String cursor,int limit)  {
        auth.requireRead(actor,patient);
        long after=cursor(cursor,actor,patient,"LIST",0,limit);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> s:repo.rows("SELECT s.id FROM medication_stock s WHERE s.patient_id=? AND s.id>? ORDER BY s.id LIMIT ?",patient,after,limit+1))out.add(detail(actor,id(s.get("id")),false));
        new org.familyhealthcare.service.archivestock.ArchiveStockNotificationProjection(jdbc).append(actor,patient,out,"stockId");
        auth.requireRead(actor,patient);
        return page(out,limit,actor,patient,"LIST",0,"stockId");
    }
    public Map<String,Object> detail(long actor,long stock)  {return detail(actor,stock,true);}
    private Map<String,Object> detail(long actor,long stock,boolean includePolicy)  {
        Map<String,Object> s=repo.stock(stock);
        if(s==null)throw StockWorkflowAuthorizationService.denied();
        long patient=id(s.get("patient_id"));
        auth.requireRead(actor,patient);
        HouseholdWorkflowSchema.StorageReadiness ready=schema.inspectStorageMetadata();
        ready.requireStructurallyReady();
        Map<String,Object> w=s.get("current_work_id")==null?null:repo.work(id(s.get("current_work_id")));
        if(w!=null&&(id(w.get("stock_id"))!=stock||id(w.get("patient_id"))!=patient))throw StockWorkflowRepository.conflict("WORK_IDENTITY_MISMATCH");
        boolean responsibleCurrent=w==null||w.get("assigned_user_id")!=null&&auth.canWrite(id(w.get("assigned_user_id")),patient);
        boolean currentWrite=auth.canWrite(actor,patient),write=properties.isEnabled()&&currentWrite;
        List<String> commands=new ArrayList<>();
        if(write)  {
            if(w==null)commands.add("ADOPT_STOCK");
            else if(!responsibleCurrent)  {
                commands.add("CHANGE_RULE");
                if("OPEN".equals(w.get("state")))commands.add("CANCEL_MISTAKE");
            }
            else if("OPEN".equals(w.get("state")))commands.addAll(Arrays.asList("RECORD_REPLENISHMENT","RECORD_COUNT","RESOLVE_INTAKE","RECONCILE","CHANGE_RULE","CANCEL_MISTAKE"));
            else commands.add("CHANGE_RULE");
        }
        if(!properties.isEnabled()&&currentWrite&&w!=null&&"OPEN".equals(w.get("state")))commands.add("CANCEL_MISTAKE");
        List<Map<String,Object>> unresolved=unresolvedRows(patient,id(s.get("medication_id")));
        long total=repo.unresolved(patient,id(s.get("medication_id")));
        Map<String,Object> count=w==null?null:repo.one("SELECT id,stock_version,counted_at,quantity,unit FROM stock_count WHERE stock_id=? AND work_id=? ORDER BY id DESC LIMIT 1",stock,w.get("id"));
        Map<String,Object> med=repo.one("SELECT drug_name FROM medication WHERE id=? AND patient_id=?",s.get("medication_id"),patient);
        Map<String,Object> result=map("stockId",stock,"patientId",patient,"medicationId",s.get("medication_id"),"drugName",med==null?null:med.get("drug_name"),"version",s.get("lock_version"),"state",w==null?"UNTRACKED":w.get("state"),"workId",w==null?null:w.get("id"),"cycle",w==null?null:w.get("cycle"),"previousWorkId",w==null?null:w.get("previous_work_id"),"quantity",nullableString(s.get("quantity")),"unit",s.get("unit"),"warningQuantity",nullableString(s.get("warning_quantity")),"warningDays",s.get("warning_days"),"dailyUseQuantity",w==null||w.get("daily_use_quantity")==null?null:w.get("daily_use_quantity").toString(),"assignedUserId",w==null?null:w.get("assigned_user_id"),"responsibleCurrent",responsibleCurrent,"low",w==null||s.get("quantity")==null||s.get("warning_quantity")==null||s.get("warning_days")==null?null:StockWorkflowService.low(s,w),"usageKnown",w!=null&&w.get("daily_use_quantity")!=null,"unresolvedCount",total,"unresolved",unresolved,"unresolvedWindowLimited",total>unresolved.size(),"currentCount",count,"allowedCommands",commands,"enabled",properties.isEnabled(),"schemaStatus",ready.getStatus().name(),"blockedReasons",ready.getReasons(),"read",true,"create",write&&w==null,"source",false,"retry",admin(actor));
        if(includePolicy)new org.familyhealthcare.service.archivestock.ArchiveStockNotificationProjection(jdbc).append(actor,patient,Collections.singletonList(result),"stockId");
        auth.requireRead(actor,patient);
        return result;
    }
    private static String nullableString(Object value){return value==null?null:value.toString();}
    private List<Map<String,Object>> unresolvedRows(long patient,long medication)  {
        return repo.rows("SELECT i.id AS intake_id,i.status,i.latest_action_id AS action_id,a.actual_at_utc,a.quantity,a.unit,r.id AS resolution_id,r.state FROM medication_intake i LEFT JOIN care_intake_action a ON a.id=i.latest_action_id AND a.intake_id=i.id AND a.patient_id=i.patient_id LEFT JOIN stock_intake_resolution r ON r.intake_id=i.id AND r.current_action_id=a.id LEFT JOIN stock_event e ON e.id=r.stock_event_id AND e.settled_action_id=a.id AND e.intake_id=i.id AND e.patient_id=i.patient_id AND e.stock_id=r.stock_id AND e.execution_event_id=a.execution_event_id AND e.settlement_status IN ('APPLIED','COUNT_INCLUDED','NO_DELTA') WHERE i.patient_id=? AND i.medication_id=? AND (i.status='TAKEN' OR a.action_kind='CORRECTION') AND (a.id IS NULL OR e.id IS NULL) ORDER BY i.id LIMIT 1000",patient,medication);
    }
    public Map<String,Object> events(long actor,long stock,String cursor,int limit)  {
        Map<String,Object> d=detail(actor,stock);
        long patient=id(d.get("patientId")),after=cursor(cursor,actor,patient,"EVENTS",stock,limit);
        List<Map<String,Object>> rows=repo.rows("SELECT id,type,aggregate_version,cycle,occurred_at,actor_id,reason,settlement_status FROM stock_event WHERE stock_id=? AND patient_id=? AND id>? ORDER BY id LIMIT ?",stock,patient,after,limit+1);
        auth.requireRead(actor,patient);
        return page(rows,limit,actor,patient,"EVENTS",stock,"id");
    }
    public Map<String,Object> commandReceipt(long actor,long patient,String key)  {
        auth.requireRead(actor,patient);
        key=WorkflowCommandExecutor.validateKey(key);
        Map<String,Object> c=repo.one("SELECT stock_id,result_json FROM stock_command WHERE actor_id=? AND command_key=?",actor,key);
        if(c==null)return null;
        Map<String,Object> s=repo.stock(id(c.get("stock_id")));
        if(s==null||id(s.get("patient_id"))!=patient)throw StockWorkflowAuthorizationService.denied();
        Map<String,Object> result=object((String)c.get("result_json"));
        StockWorkflowContracts.requireMinimalReceipt(result);
        auth.requireRead(actor,patient);
        return result;
    }
    private boolean admin(long actor)  {
        try  {
            auth.requireNotificationAdmin(actor);
            return true;
        }
        catch(WorkflowException e)  {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    public static long cursor(String c,long actor,long patient,String lane,long target,int limit)  {
        return MedicationExecutionQueryService.cursor(c,actor,patient,"STOCK_"+lane,target,limit);
    }
    public static Map<String,Object> page(List<Map<String,Object>> rows,int limit,long actor,long patient,String lane,long target,String idKey)  {
        return MedicationExecutionQueryService.page(rows,limit,actor,patient,"STOCK_"+lane,target,idKey);
    }
}
