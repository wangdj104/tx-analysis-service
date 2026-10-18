package org.familyhealthcare.service.medicationexecution;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.medicationexecution.MedicationExecutionAuthorizationService.denied;
/** Bounded, typed projections. No raw source/entity or other recipient's channel information. */
@Service
public class MedicationExecutionQueryService {
    private final JdbcTemplate jdbc;
    private final MedicationExecutionProperties properties;
    private final MedicationExecutionAuthorizationService auth;
    private final MedicationExecutionRepository repo;
    private final HouseholdWorkflowSchema schema;
    public MedicationExecutionQueryService(JdbcTemplate jdbc,MedicationExecutionProperties properties,MedicationExecutionAuthorizationService auth,MedicationExecutionRepository repo,HouseholdWorkflowSchema schema) {
        this.jdbc=jdbc;
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.schema=schema;
    }
    public Map<String,Object> capabilities(long actor,long patient) {
        auth.requireRead(actor,patient);
        HouseholdWorkflowSchema.StorageReadiness storage=schema.inspectStorageMetadata();
        boolean ready=storage.getStatus()==HouseholdWorkflowSchema.StorageStatus.STRUCTURALLY_READY,write=auth.canWrite(actor,patient),enabled=properties.isEnabled();
        List<String> blockers=new ArrayList<>();
        if(!ready)blockers.add("SCHEMA_NOT_READY");
        if(!enabled)blockers.add("FEATURE_DISABLED");
        if(!write)blockers.add("READ_ONLY");
        Map<String,Object> doctorPage=ready?clinicians(actor,patient,null,30):map("items",Collections.emptyList(),"nextCursor",null);
        List<?> doctors=(List<?>)doctorPage.get("items");
        if(doctors.isEmpty()&&doctorPage.get("nextCursor")==null)blockers.add("NO_CURRENT_CLINICIAN");
        Map<String,Object> out=map("enabled",enabled,"schemaStatus",storage.getStatus().name(),"blockedReasons",blockers,"read",ready,"create",ready&&enabled&&write,"recordFacts",ready&&write,"source",ready,"retry",notificationAdmin(actor)&&enabled&&ready,"allowedCommands",ready&&enabled&&write?Arrays.asList("CREATE_RECONCILIATION","NEW_RECONCILIATION","REVISE_RECONCILIATION"):Collections.emptyList(),"eligibleDoctors",doctors);
        Map<String,Object> medicinePage=ready?medications(actor,patient,null,30):map("items",Collections.emptyList(),"nextCursor",null);
        out.put("medications",medicinePage.get("items"));
        out.put("medicationCursor",medicinePage.get("nextCursor"));
        out.put("doctorCursor",doctorPage.get("nextCursor"));
        auth.requireRead(actor,patient);
        return out;
    }
    public Map<String,Object> medications(long actor,long patient,String cursor,int limit) {
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        long after=cursor(cursor,actor,patient,"MEDICATIONS",0,limit);
        List<Map<String,Object>> rows=jdbc.query("SELECT id,drug_name,execution_id FROM medication WHERE patient_id=? AND id>? ORDER BY id LIMIT ?",(rs,i)->map("id",rs.getLong(1),"name",rs.getString(2),"executionId",nullableId(rs,"execution_id")),patient,after,limit+1);
        auth.requireRead(actor,patient);
        return page(rows,limit,actor,patient,"MEDICATIONS",0,"id");
    }
    public Map<String,Object> medication(long actor,long patient,long medication) {
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        List<Map<String,Object>> rows=jdbc.query("SELECT id,drug_name,execution_id FROM medication WHERE patient_id=? AND id=?",(rs,i)->map("id",rs.getLong(1),"name",rs.getString(2),"executionId",nullableId(rs,"execution_id")),patient,medication);
        if(rows.size()!=1)throw denied();
        auth.requireRead(actor,patient);
        return rows.get(0);
    }
    public Map<String,Object> clinicians(long actor,long patient,String cursor,int limit) {
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        return auth.eligibleDoctors(actor,patient,cursor,limit);
    }
    public Map<String,Object> detail(long actor,long execution) {
        schema.inspectStorageMetadata().requireStructurallyReady();
        Map<String,Object> e=repo.execution(execution);
        if(e==null)throw denied();
        long patient=id(e.get("patient_id"));
        auth.requireRead(actor,patient);
        Map<String,Object> med=repo.one("SELECT id,patient_id,drug_name,execution_id,workflow_version FROM medication WHERE id=?",e.get("medication_id"));
        if(med==null||id(med.get("patient_id"))!=patient||med.get("execution_id")==null||id(med.get("execution_id"))!=execution||((Number)med.get("workflow_version")).intValue()!=1)throw denied();
        Map<String,Object> out=map("executionId",execution,"patientId",patient,"medicationId",e.get("medication_id"),"medicationName",med.get("drug_name"),"version",e.get("lock_version"),"reviewRequired",((Number)e.get("review_required")).intValue()==1,"applied",e.get("current_reconciliation_id")!=null,"enabled",properties.isEnabled(),"schemaStatus","STRUCTURALLY_READY","read",true,"create",properties.isEnabled()&&auth.canWrite(actor,patient),"source",true,"retry",properties.isEnabled()&&notificationAdmin(actor));
        Object chosen=e.get("pending_reconciliation_id")!=null?e.get("pending_reconciliation_id"):e.get("current_reconciliation_id");
        Map<String,Object> p=chosen==null?null:repo.reconciliation(id(chosen));
        List<String> allowed=new ArrayList<>(),blocked=new ArrayList<>();
        if(!properties.isEnabled())blocked.add("FEATURE_DISABLED");
        if(auth.canWrite(actor,patient)&&properties.isEnabled())allowed.add("NEW_RECONCILIATION");
        if(p!=null) {
            if(id(p.get("execution_id"))!=execution||id(p.get("patient_id"))!=patient)throw denied();
            Map<String,Object> v=repo.revision(id(p.get("current_revision_id")));
            repo.requireRevisionLinks(v,patient);
            requireSource(actor,patient,v);
            Long selected=p.get("selected_verifier_id")==null?null:id(p.get("selected_verifier_id"));
            String state=(String)p.get("state");
            boolean editable=Arrays.asList("DRAFT","READY","RETURNED","SOURCE_CHANGED","TIME_UNRESOLVED","REPLACEMENT_CHANGED").contains(state),verify=auth.canVerify(actor,patient,selected);
            if(selected==null)blocked.add("NO_SELECTED_CLINICIAN");
            else if(!auth.canVerify(selected,patient,selected))blocked.add("CLINICIAN_UNAVAILABLE");
            if("PRESCRIPTION".equals(v.get("source_kind"))&&!"ACTIVE".equals(repo.one("SELECT status FROM electronic_prescription WHERE id=?",v.get("prescription_id")).get("status")))blocked.add("SOURCE_CHANGED");
            if(auth.canWrite(actor,patient)){if("READY".equals(state)&&properties.isEnabled()&&!blocked.contains("CLINICIAN_UNAVAILABLE")&&!blocked.contains("SOURCE_CHANGED"))allowed.add("SCHEDULE_REPLACEMENT");if("SCHEDULED".equals(state))allowed.add("CANCEL_SCHEDULE");}
            if(editable&&auth.canWrite(actor,patient)) {
                allowed.add("CANCEL_RECONCILIATION");
                if(properties.isEnabled())allowed.add("REVISE_RECONCILIATION");
            }
            if(properties.isEnabled()&&verify&&editable) {
                allowed.add("RETURN_RECONCILIATION");
                if("DRAFT".equals(state)&&!blocked.contains("SOURCE_CHANGED"))allowed.add("VERIFY_RECONCILIATION");
            }
            out.put("reconciliation",map("id",p.get("id"),"state",state,"cycle",p.get("proposal_no"),"selectedVerifierId",selected,"verifiedBy",p.get("verified_by"),"verifiedAt",jdbc.queryForObject("SELECT verified_at FROM medication_reconciliation WHERE id=?",(rs,n)->time(rs,"verified_at"),p.get("id")),"manualTranscription",true));
            Map<String,Object> source=map("kind",v.get("source_kind"),"prescriptionId",v.get("prescription_id"),"itemId",v.get("prescription_item_id"),"version",v.get("source_version"),"hash",v.get("source_hash"));
            if("MANUAL".equals(v.get("source_kind")))source.put("provenance",object((String)v.get("source_json")).get("provenance"));
            out.put("revision",map("id",v.get("id"),"revisionNo",v.get("revision_no"),"revisionHash",v.get("revision_hash"),"transcribedBy",v.get("transcribed_by"),"source",source,"mapping",object((String)v.get("mapping_json"))));
        }
        else {
            out.put("reconciliation",null);
            out.put("revision",null);
        }
        out.put("allowedCommands",allowed);
        out.put("blockedReasons",blocked);
        out.put("nextAction",p==null?"MANUAL_TRANSCRIPTION":"READY".equals(p.get("state"))?"VERIFIED_NOT_APPLIED":"DRAFT".equals(p.get("state"))?"SELECTED_CLINICIAN_REVIEW":"REVIEW_CURRENT_REVISION");
        auth.requireRead(actor,patient);
        return out;
    }
    public Map<String,Object> list(long actor,long patient,String cursor,int limit) {
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        long after=cursor(cursor,actor,patient,"LIST",0,limit);
        List<Map<String,Object>> rows=jdbc.query("SELECT e.id,e.medication_id,e.lock_version,e.review_required,m.drug_name,r.state FROM medication_execution e JOIN medication m ON m.id=e.medication_id AND m.patient_id=e.patient_id LEFT JOIN medication_reconciliation r ON r.id=COALESCE(e.pending_reconciliation_id,e.current_reconciliation_id) AND r.execution_id=e.id AND r.patient_id=e.patient_id WHERE e.patient_id=? AND e.id>? ORDER BY e.id LIMIT ?",(rs,i)->map("executionId",rs.getLong(1),"medicationId",rs.getLong(2),"version",rs.getLong(3),"reviewRequired",rs.getBoolean(4),"medicationName",rs.getString(5),"state",rs.getString(6)),patient,after,limit+1);
        auth.requireRead(actor,patient);
        return page(rows,limit,actor,patient,"LIST",0,"executionId");
    }
    public Map<String,Object> events(long actor,long execution,String cursor,int limit) {
        Map<String,Object> detail=detail(actor,execution);
        long patient=id(detail.get("patientId")),after=cursor(cursor,actor,patient,"EVENTS",execution,limit);
        List<Map<String,Object>> rows=jdbc.query("SELECT id,aggregate_version,cycle,type,actor_id,actor_relation,occurred_at,reconciliation_id,revision_id FROM medication_execution_event WHERE execution_id=? AND patient_id=? AND id>? ORDER BY id LIMIT ?",(rs,i)->map("id",rs.getLong(1),"version",rs.getLong(2),"cycle",rs.getInt(3),"type",rs.getString(4),"actorId",nullableId(rs,"actor_id"),"actorRelation",rs.getString(6),"occurredAt",time(rs,"occurred_at"),"reconciliationId",nullableId(rs,"reconciliation_id"),"revisionId",nullableId(rs,"revision_id")),execution,patient,after,limit+1);
        auth.requireRead(actor,patient);
        return page(rows,limit,actor,patient,"EVENTS",execution,"id");
    }
    public Map<String,Object> sources(long actor,long patient,String cursor,int limit) {
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        long after=cursor(cursor,actor,patient,"SOURCES",0,limit);
        List<Map<String,Object>> rows=jdbc.query("SELECT i.id,p.id AS prescription_id,p.version_no,p.status,i.drug_name,i.dosage,i.frequency,i.administration_route FROM electronic_prescription_item i JOIN electronic_prescription p ON p.id=i.prescription_id WHERE p.patient_id=? AND i.id>? ORDER BY i.id LIMIT ?",(rs,i)->map("itemId",rs.getLong(1),"prescriptionId",rs.getLong(2),"version",rs.getInt(3),"status",rs.getString(4),"drugName",rs.getString(5),"dosage",rs.getString(6),"frequency",rs.getString(7),"route",rs.getString(8)),patient,after,limit+1);
        auth.requireRead(actor,patient);
        return page(rows,limit,actor,patient,"SOURCES",0,"itemId");
    }
    public Map<String,Object> intakes(long actor,long execution,String cursor,int limit) {
        Map<String,Object> detail=detail(actor,execution);return intakePage(actor,id(detail.get("patientId")),id(detail.get("medicationId")),execution,cursor,limit);
    }
    public Map<String,Object> personalIntakes(long actor,long patient,long medication,String cursor,int limit){auth.requireRead(actor,patient);schema.inspectStorageMetadata().requireStructurallyReady();Map<String,Object> m=repo.one("SELECT id,execution_id FROM medication WHERE id=? AND patient_id=?",medication,patient);if(m==null)throw denied();Map<String,Object> result=intakePage(actor,patient,medication,0,cursor,limit);result.put("executionId",m.get("execution_id"));return result;}
    private Map<String,Object> intakePage(long actor,long patient,long medication,long execution,String cursor,int limit){
        long after=cursor(cursor,actor,patient,execution==0?"PERSONAL_INTAKES":"INTAKES",execution==0?medication:execution,limit);boolean write=auth.canWrite(actor,patient);
        Map<String,Object> stock=repo.one("SELECT id,current_work_id FROM medication_stock WHERE patient_id=? AND medication_id=?",patient,medication),count=stock==null||stock.get("current_work_id")==null?null:repo.one("SELECT id,stock_version,counted_at FROM stock_count WHERE stock_id=? AND work_id=? ORDER BY id DESC LIMIT 1",stock.get("id"),stock.get("current_work_id"));
        Object countTime=count==null?null:jdbc.queryForObject("SELECT counted_at FROM stock_count WHERE id=?",(rs,n)->time(rs,"counted_at"),count.get("id"));
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> i:repo.rows("SELECT * FROM medication_intake WHERE patient_id=? AND medication_id=? AND (execution_id=? OR execution_id IS NULL) AND id>? ORDER BY id LIMIT ?",patient,medication,execution,after,limit+1)){
            Map<String,Object> latest=repo.one("SELECT id,status,quantity,unit,actual_at_utc,recorded_for,amount_basis,actor_id FROM care_intake_action WHERE intake_id=? AND patient_id=? AND status IN ('TAKEN','SKIPPED') ORDER BY id DESC LIMIT 1",i.get("id"),patient);
            Map<String,Object> resolution=repo.one("SELECT state,current_action_id FROM stock_intake_resolution WHERE intake_id=? AND patient_id=?",i.get("id"),patient);
            String follow=i.get("follow_up_state")==null?(Arrays.asList("TAKEN","SKIPPED").contains(i.get("status"))?"FACT_RECORDED":"OPEN"):(String)i.get("follow_up_state");List<String> allowed=new ArrayList<>();
            if(write&&!"CANCELLED".equals(i.get("status"))){if(latest!=null)allowed.add("CORRECT_INTAKE");else if(!Arrays.asList("TAKEN","SKIPPED").contains(i.get("status")))allowed.addAll(Arrays.asList("RECORD_TAKEN","RECORD_SKIPPED"));if(!"NOT_APPLICABLE".equals(follow))allowed.add("RECORD_CONTACT");if("ENDED_UNRESOLVED".equals(follow))allowed.add("REOPEN_INTAKE_FOLLOW_UP");if("OPEN".equals(follow)){if("DOSE_REMINDER".equals(i.get("follow_up_kind")))allowed.add("SNOOZE");java.time.Instant due=MedicationBoundaryService.at(i.get("follow_up_due_at"));if(due!=null&&!due.plusSeconds(3600).isAfter(properties.now()))allowed.add("END_INTAKE_UNRESOLVED");}}
            String outcome=latest==null?"NOT_APPLICABLE":resolution!=null&&Objects.equals(latest.get("id"),resolution.get("current_action_id"))?(String)resolution.get("state"):"UNRECONCILED";
            Map<String,Object> row=map("intakeId",i.get("id"),"status",i.get("status"),"provenance",i.get("provenance"),"scheduledAtUtc",instant(i.get("scheduled_at_utc")),"actualAt",instant(i.get("actual_at_utc")),"plannedQuantity",i.get("planned_quantity")==null?null:i.get("planned_quantity").toString(),"plannedUnit",i.get("planned_unit"),"followUpState",follow,"followUpKind",i.get("follow_up_kind"),"cycle",i.get("follow_up_cycle"),"latestActionId",latest==null?null:latest.get("id"),"canSelf",actor==auth.owner(patient),"stockOutcome",outcome,"allowedCommands",allowed,"currentCount",count==null?null:map("id",count.get("id"),"version",count.get("stock_version"),"countedAt",countTime));
            Map<String,Object> fact=null;
            if(latest!=null)fact=map("actionId",latest.get("id"),"status",latest.get("status"),"actualAt",instant(latest.get("actual_at_utc")),"quantity",latest.get("quantity")==null?null:latest.get("quantity").toString(),"unit",latest.get("unit"),"amountBasis",latest.get("amount_basis"),"attribution",latest.get("recorded_for"),"actorId",latest.get("actor_id"));
            else if(Arrays.asList("TAKEN","SKIPPED").contains(i.get("status")))fact=map("actionId",null,"status",i.get("status"),"actualAt",instant(i.get("actual_at_utc")),"quantity",null,"unit",null,"amountBasis",null,"attribution",null,"actorId",null);
            row.put("latestFact",fact);
            if(execution==0)allowed.removeIf(operation->!Arrays.asList("RECORD_TAKEN","RECORD_SKIPPED","CORRECT_INTAKE").contains(operation));
            result.add(row);
        }auth.requireRead(actor,patient);return page(result,limit,actor,patient,execution==0?"PERSONAL_INTAKES":"INTAKES",execution==0?medication:execution,"intakeId");
    }
    private static String instant(Object value){java.time.Instant at=MedicationBoundaryService.at(value);return at==null?null:at.toString();}
    public Map<String,Object> commandReceipt(long actor,long patient,String key) {
        auth.requireRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        WorkflowCommandExecutor.validateKey(key);
        Map<String,Object> row=repo.one("SELECT c.result_json,c.execution_id FROM medication_execution_command c JOIN medication_execution e ON e.id=c.execution_id WHERE c.actor_id=? AND c.command_key=? AND e.patient_id=?",actor,key,patient);
        if(row==null)return null;
        detail(actor,id(row.get("execution_id")));
        Map<String,Object> receipt=object((String)row.get("result_json"));
        MedicationExecutionContracts.requireMinimalReceipt(receipt);
        auth.requireRead(actor,patient);
        return receipt;
    }
    private void requireSource(long actor,long patient,Map<String,Object> v) {
        if("PRESCRIPTION".equals(v.get("source_kind")))auth.requireSource(actor,patient,id(v.get("prescription_id")),id(v.get("prescription_item_id")),((Number)v.get("source_version")).intValue());
    }
    private boolean notificationAdmin(long actor) {
        try {
            auth.requireNotificationAdmin(actor);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    public static long cursor(String value,long actor,long patient,String lane,long target,int limit) {
        if(limit<1||limit>100)throw WorkflowException.invalid("limit must be between 1 and 100.");
        if(value==null)return 0;
        try {
            if(value.length()>256)throw new IllegalArgumentException();
            String[] p=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8).split(":");
            if(p.length!=6||!"ME1".equals(p[0])||!Long.toString(actor).equals(p[1])||!Long.toString(patient).equals(p[2])||!lane.equals(p[3])||!Long.toString(target).equals(p[4])||!p[5].matches("[1-9][0-9]*"))throw new IllegalArgumentException();
            long id=Long.parseLong(p[5]);
            if(id>9007199254740991L)throw new IllegalArgumentException();
            return id;
        }
        catch(RuntimeException e) {
            throw WorkflowException.invalid("Invalid continuation cursor.");
        }
    }
    public static String nextCursor(long actor,long patient,String lane,long target,long after) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("ME1:"+actor+":"+patient+":"+lane+":"+target+":"+after).getBytes(StandardCharsets.UTF_8));
    }
    public static Map<String,Object> page(List<Map<String,Object>> rows,int limit,long actor,long patient,String lane,long target,String idKey) {
        boolean more=rows.size()>limit;
        if(more)rows=new ArrayList<>(rows.subList(0,limit));
        String next=more?nextCursor(actor,patient,lane,target,id(rows.get(rows.size()-1).get(idKey))):null;
        return map("items",rows,"nextCursor",next);
    }
}
