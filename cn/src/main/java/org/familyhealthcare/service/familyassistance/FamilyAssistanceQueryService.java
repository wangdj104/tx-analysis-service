package org.familyhealthcare.service.familyassistance;
import org.familyhealthcare.service.workflow.*;
import org.familyhealthcare.service.medicationexecution.MedicationExecutionQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.familyassistance.FamilyAssistanceRepository.*;
import static org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService.denied;
@Service public class FamilyAssistanceQueryService {
    private final org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge visitFollowUp;
    private final FamilyAssistanceProperties properties;
    private final FamilyAssistanceAuthorizationService auth;
    private final FamilyAssistanceRepository repo;
    private final HouseholdWorkflowSchema schema;
    public FamilyAssistanceQueryService(JdbcTemplate jdbc,FamilyAssistanceProperties properties,FamilyAssistanceAuthorizationService auth,FamilyAssistanceRepository repo,HouseholdWorkflowSchema schema,org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge visitFollowUp) {
        this.visitFollowUp=java.util.Objects.requireNonNull(visitFollowUp);
        this.properties=properties;
        this.auth=auth;
        this.repo=repo;
        this.schema=schema;
    }
    /** 旧家庭页仅消费本域权限；受限正文与复制的活动标题均不能借旧入口绕行。 */
    public List<org.familyhealthcare.entity.CareItem> legacyItems(long actor,long patient,List<org.familyhealthcare.entity.CareItem> rows) {
        HouseholdWorkflowSchema.StorageReadiness ready=schema.inspectStorageMetadata();
        boolean modern=ready.getStatus()!=HouseholdWorkflowSchema.StorageStatus.ABSENT||properties.isEnabled();
        if(modern)ready.requireStructurallyReady();
        Map<Long,Map<String,Object>> nativeRows=new HashMap<>();
        if(modern) {
            List<Long> ids=new ArrayList<>();for(org.familyhealthcare.entity.CareItem row:rows){if(row.getId()!=null)ids.add(row.getId());if("ACTIVITY".equals(row.getKind())&&row.getDetails().get("sourceId") instanceof Number)ids.add(((Number)row.getDetails().get("sourceId")).longValue());}
            for(int start=0;start<ids.size();start+=100){List<Long> batch=ids.subList(start,Math.min(start+100,ids.size()));List<Object> args=new ArrayList<>();args.add(patient);args.addAll(batch);for(Map<String,Object> row:repo.rows("SELECT id,kind,data_json,family_workflow_version,family_assistance_id,medication_workflow_version,medication_execution_id FROM care_item WHERE patient_id=? AND id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray()))nativeRows.put(id(row.get("id")),row);}
        }
        if(!modern){List<Long> ids=new ArrayList<>();for(org.familyhealthcare.entity.CareItem row:rows)if("ACTIVITY".equals(row.getKind())&&row.getDetails().get("sourceId") instanceof Number)ids.add(((Number)row.getDetails().get("sourceId")).longValue());for(int start=0;start<ids.size();start+=100){List<Long> batch=ids.subList(start,Math.min(start+100,ids.size()));List<Object> args=new ArrayList<>();args.add(patient);args.addAll(batch);for(Map<String,Object> row:repo.rows("SELECT id,kind,data_json,0 AS family_workflow_version,NULL AS family_assistance_id,0 AS medication_workflow_version,NULL AS medication_execution_id FROM care_item WHERE patient_id=? AND id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray()))nativeRows.put(id(row.get("id")),row);}}
        // 未跟踪旧记录继续由调用者既有 patient scope 授权；这里不把旧 scope 变成 B 域授权。
        List<org.familyhealthcare.entity.CareItem> out=new ArrayList<>();
        for(org.familyhealthcare.entity.CareItem row:rows) {
            if(!Objects.equals(row.getPatientId(),patient))continue;
            String kind="APPOINTMENT".equals(row.getKind())?"ACCOMPANIMENT":"HANDOVER";
            Map<String,Object> nativeRow=nativeRows.get(row.getId());
            if(modern&&(nativeRow==null||id(nativeRow.get("family_workflow_version"))!=0||nativeRow.get("family_assistance_id")!=null||id(nativeRow.get("medication_workflow_version"))!=0||nativeRow.get("medication_execution_id")!=null))continue;
            if(restricted(row.getDetails())||modern&&(!Objects.equals(row.getKind(),nativeRow.get("kind"))||restricted(object((String)nativeRow.get("data_json")))))continue;
            if("ACTIVITY".equals(row.getKind())) {
                Object sourceId=row.getDetails().get("sourceId");
                Map<String,Object> source=sourceId instanceof Number?nativeRows.get(((Number)sourceId).longValue()):null;
                // 活动只保留仍可核实的未跟踪来源身份，历史复制标题不能当当前来源权限。
                if(source==null||"ORDER".equals(source.get("kind"))||id(source.get("family_workflow_version"))!=0||source.get("family_assistance_id")!=null||id(source.get("medication_workflow_version"))!=0||source.get("medication_execution_id")!=null||restricted(object((String)source.get("data_json"))))continue;
                kind="APPOINTMENT".equals(source.get("kind"))?"ACCOMPANIMENT":"HANDOVER";
                org.familyhealthcare.entity.CareItem safe=new org.familyhealthcare.entity.CareItem();org.springframework.beans.BeanUtils.copyProperties(row,safe);safe.setTitle(org.familyhealthcare.util.ExportLocalization.text("Care record activity","照护记录动态"));safe.setDetails(map("sourceId",sourceId));row=safe;
            }
            out.add(row);
        }
        return out;
    }
    public static boolean restricted(Object value) {
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
            String key=String.valueOf(entry.getKey()).replace("_","").toLowerCase(Locale.ROOT);
            if(key.contains("psychological")||key.contains("minor")||key.contains("restricted")||key.contains("private")||restricted(entry.getValue()))return true;
        }
        else if(value instanceof Collection)for(Object row:(Collection<?>)value)if(restricted(row))return true;
        return false;
    }
    private void anyRead(long a,long p) {
        if(!auth.canRead(a,p,"HANDOVER")&&!auth.canRead(a,p,"ACCOMPANIMENT"))throw denied();
    }
    public Map<String,Object> capabilities(long actor,long patient) {
        anyRead(actor,patient);
        HouseholdWorkflowSchema.StorageReadiness ready=schema.inspectStorageMetadata();
        boolean ordinary=auth.canRead(actor,patient,"HANDOVER"),appointments=auth.canRead(actor,patient,"ACCOMPANIMENT");
        if(!ordinary&&!appointments)throw denied();
        boolean ordinaryWrite=ordinary&&auth.canWrite(actor,patient,"HANDOVER"),appointmentWrite=appointments&&auth.canWrite(actor,patient,"ACCOMPANIMENT"),retry=admin(actor);
        // metadata 等待不能保留过时的 read/create/retry；只重验将要返回为真的能力。
        if(ordinary)auth.requireRead(actor,patient,"HANDOVER");
        if(appointments)auth.requireRead(actor,patient,"ACCOMPANIMENT");
        if(ordinaryWrite)auth.requireWrite(actor,patient,"HANDOVER");
        if(appointmentWrite)auth.requireWrite(actor,patient,"ACCOMPANIMENT");
        if(retry)auth.requireNotificationAdmin(actor);
        return map("actorId",actor,"enabled",properties.isEnabled(),"schemaStatus",ready.getStatus().name(),"blockedReasons",ready.getReasons(),"read",ready.getStatus()==HouseholdWorkflowSchema.StorageStatus.STRUCTURALLY_READY,"create",properties.isEnabled()&&ready.getStatus()==HouseholdWorkflowSchema.StorageStatus.STRUCTURALLY_READY&&(ordinaryWrite||appointmentWrite),"ordinary",ordinary,"accompaniment",appointments,"source",true,"retry",retry);
    }
    public Map<String,Object> list(long actor,long patient,String cursor,int limit) {
        anyRead(actor,patient);
        schema.inspectStorageMetadata().requireStructurallyReady();
        long after=cursor(cursor,actor,patient,"LIST",0,limit);
        List<Map<String,Object>> rows=repo.rows("SELECT * FROM family_assistance WHERE patient_id=? AND id>? ORDER BY id LIMIT ?",patient,after,limit+1);
        boolean more=rows.size()>limit;
        if(more)rows.remove(rows.size()-1);
        List<Map<String,Object>> out=new ArrayList<>();
        boolean ordinary=auth.canRead(actor,patient,"HANDOVER"),appointments=auth.canRead(actor,patient,"ACCOMPANIMENT");
        for(Map<String,Object> a:rows)if("ACCOMPANIMENT".equals(a.get("request_kind"))?appointments:ordinary)out.add(map("assistanceId",a.get("id"),"patientId",patient,"sourceKind",a.get("source_kind"),"requestKind",a.get("request_kind"),"state",a.get("state"),"cycle",a.get("cycle"),"assignedUserId",a.get("assigned_user_id"),"originatorId",a.get("originator_id")));
        // 返回了哪个权限域，就在返回前逐域重验；普通权限丢失不能由仍有 APPOINTMENTS 掩盖。
        Set<String> returnedKinds=new LinkedHashSet<>();
        for(Map<String,Object> item:out)returnedKinds.add("ACCOMPANIMENT".equals(item.get("requestKind"))?"ACCOMPANIMENT":"HANDOVER");
        for(String kind:returnedKinds)auth.requireRead(actor,patient,kind);
        anyRead(actor,patient);
        return map("items",out,"nextCursor",more?next(actor,patient,"LIST",0,id(rows.get(rows.size()-1).get("id"))):null);
    }
    public Map<String,Object> detail(long actor,long aid) {
        Map<String,Object> a=repo.assistance(aid);
        if(a==null)throw denied();
        long patient=id(a.get("patient_id"));
        String kind=(String)a.get("request_kind");
        auth.requireRead(actor,patient,kind);
        HouseholdWorkflowSchema.StorageReadiness ready=schema.inspectStorageMetadata();
        ready.requireStructurallyReady();
        Map<String,Object> s=repo.source((String)a.get("source_kind"),sourceId(a));
        if(s==null||id(s.get("patient_id"))!=patient)throw denied();
        repo.requireIdentity(a,patient,(String)a.get("source_kind"),sourceId(a));
        Map<String,Object> source=repo.minimal((String)a.get("source_kind"),s);
        boolean changed="ACCOMPANIMENT".equals(kind)&&(FamilyAssistanceService.changed(a)||!Objects.equals(source.get("fingerprint"),a.get("source_fingerprint")));
        boolean handler=a.get("accepted_by")!=null&&auth.canWrite(id(a.get("accepted_by")),patient,kind);
        Long currentAssistance=s.get("family_assistance_id")==null?null:id(s.get("family_assistance_id"));
        boolean currentSource=Objects.equals(currentAssistance,aid)&&id(s.get("family_workflow_version"))==1;
        if(currentAssistance!=null&&!currentSource)repo.requireIdentity(repo.assistance(currentAssistance),patient,(String)a.get("source_kind"),sourceId(a));
        List<String> allowed=currentSource?visitFollowUp.filterAssistanceCommands((String)a.get("source_kind"),sourceId(a),allowed(actor,a,changed,handler)):new ArrayList<>();
        List<String> blocked=new ArrayList<>(ready.getReasons());
        if(!currentSource)blocked.add("SOURCE_SUPERSEDED");
        if(changed)blocked.add("APPOINTMENT_RECONFIRM_REQUIRED");
        if(a.get("assigned_user_id")!=null&&!auth.canWrite(id(a.get("assigned_user_id")),patient,kind))blocked.add("HANDLER_ACCESS_LOST");
        Map<String,Object> out=map("assistanceId",aid,"currentAssistanceId",currentAssistance,"currentSource",currentSource,"patientId",patient,"sourceKind",a.get("source_kind"),"careItemId",a.get("care_item_id"),"careAppointmentId",a.get("care_appointment_id"),"requestKind",kind,"requestNo",a.get("request_no"),"previousAssistanceId",a.get("previous_assistance_id"),"originatorId",a.get("originator_id"),"assignedUserId",a.get("assigned_user_id"),"acceptedBy",a.get("accepted_by"),"acceptedAt",iso(a.get("accepted_at")),"handledBy",a.get("handled_by"),"handledAt",iso(a.get("handled_at")),"confirmedAt",iso(a.get("confirmed_at")),"state",a.get("state"),"version",a.get("lock_version"),"cycle",a.get("cycle"),"source",source,"sourceChanged",changed,"handlerCurrent",handler,"deadlineAt",iso(a.get("deadline_at")),"overdue",!FamilyAssistanceService.terminal(a)&&a.get("deadline_at")!=null&&!java.time.Instant.parse(iso(a.get("deadline_at"))).isAfter(properties.now()),"closedAt",iso(a.get("closed_at")),"closeReason",a.get("close_reason"),"allowedCommands",allowed,"enabled",properties.isEnabled(),"schemaStatus",ready.getStatus().name(),"blockedReasons",blocked,"read",true,"create",false,"retry",admin(actor));
        auth.requireRead(actor,patient,kind);
        return out;
    }
    private List<String> allowed(long actor,Map<String,Object> a,boolean changed,boolean handler) {
        List<String> out=new ArrayList<>();
        long p=id(a.get("patient_id"));
        if(!auth.canWrite(actor,p,(String)a.get("request_kind")))return out;
        boolean origin=actor==id(a.get("originator_id")),owner=actor==auth.owner(p),accepted=Objects.equals(a.get("accepted_by"),actor);
        String state=(String)a.get("state");
        if(FamilyAssistanceService.terminal(a)) {
            if(origin||owner&&!state.equals("COMPLETED"))out.add("REOPEN");
            return out;
        }
        if(origin) {
            out.addAll(Arrays.asList("CANCEL","CLOSE_UNRESOLVED"));
            if(state.equals("AWAITING_CONFIRMATION")) {
                out.add("RETURN");
                if(!changed&&handler)out.add("CONFIRM");
            }
            if(state.equals("REQUESTED")&&"ACCOMPANIMENT".equals(a.get("request_kind"))&&changed)out.add("RECONFIRM_APPOINTMENT");
        }
        else if(owner)out.add("CLOSE_UNRESOLVED");
        if(origin||owner||accepted)out.add("HANDOFF");
        if(state.equals("REQUESTED")&&(origin||owner))out.add("ASSIGN");
        if(Objects.equals(a.get("assigned_user_id"),actor)) {
            out.add("REJECT");
            if(state.equals("REQUESTED")&&!changed)out.add("ACCEPT");
        }
        if(state.equals("ACCEPTED")&&accepted&&!changed)out.add("HANDLE");
        return out;
    }
    public Map<String,Object> events(long actor,long aid,String cursor,int limit) {
        Map<String,Object> d=detail(actor,aid);
        long patient=id(d.get("patientId")),after=cursor(cursor,actor,patient,"EVENTS",aid,limit);
        List<Map<String,Object>> rows=repo.rows("SELECT id,type,aggregate_version,cycle,actor_id,actor_kind,occurred_at,reason,due_at FROM family_assistance_event WHERE assistance_id=? AND patient_id=? AND id>? ORDER BY id LIMIT ?",aid,patient,after,limit+1);
        auth.requireRead(actor,patient,(String)d.get("requestKind"));
        return page(rows,limit,actor,patient,"EVENTS",aid,"id");
    }
    public Map<String,Object> commandReceipt(long actor,long patient,String key) {
        anyRead(actor,patient);
        key=WorkflowCommandExecutor.validateKey(key);
        Map<String,Object> c=repo.one("SELECT assistance_id,result_json FROM family_assistance_command WHERE actor_id=? AND command_key=?",actor,key);
        if(c==null)return null;
        Map<String,Object> a=repo.assistance(id(c.get("assistance_id")));
        if(a==null||id(a.get("patient_id"))!=patient)throw denied();
        auth.requireRead(actor,patient,(String)a.get("request_kind"));
        Map<String,Object> r=object((String)c.get("result_json"));
        FamilyAssistanceContracts.requireMinimalReceipt(r);
        return r;
    }
    private boolean admin(long a) {
        try {
            auth.requireNotificationAdmin(a);
            return true;
        }
        catch(WorkflowException e) {
            if(e.getStatus()!=403)throw e;
            return false;
        }
    }
    public static long cursor(String c,long a,long p,String lane,long target,int limit) {
        return MedicationExecutionQueryService.cursor(c,a,p,"FAMILY_"+lane,target,limit);
    }
    public static String next(long a,long p,String lane,long target,long last) {
        return MedicationExecutionQueryService.nextCursor(a,p,"FAMILY_"+lane,target,last);
    }
    public static Map<String,Object> page(List<Map<String,Object>> rows,int limit,long a,long p,String lane,long target,String key) {
        return MedicationExecutionQueryService.page(rows,limit,a,p,"FAMILY_"+lane,target,key);
    }
}
