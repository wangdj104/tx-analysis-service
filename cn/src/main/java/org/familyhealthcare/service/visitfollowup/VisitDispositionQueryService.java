package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpRepository.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;

/** 就诊读取独立于预约/处方。先发现最小身份，再以固定批量前后核验当前事实。 */
@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class VisitDispositionQueryService {
    private final VisitFollowUpRepository repo;
    private final VisitFollowUpAuthorizationService auth;
    private final VisitFollowUpProperties properties;
    private final VisitFollowUpSourceBridge bridge;
    private final VisitActionProjection actions;
    public VisitDispositionQueryService(VisitFollowUpRepository repo,VisitFollowUpAuthorizationService auth,VisitFollowUpProperties properties,VisitFollowUpSourceBridge bridge,VisitActionProjection actions){this.repo=Objects.requireNonNull(repo);this.auth=Objects.requireNonNull(auth);this.properties=Objects.requireNonNull(properties);this.bridge=Objects.requireNonNull(bridge);this.actions=Objects.requireNonNull(actions);}
    public Map<String,Object> visits(long actor,long patient,long after,int limit) {
        page(after,limit);VisitFollowUpSchema.Availability storage=storage();List<Map<String,Object>> discovery=discover(actor,patient,null,after,limit+1,false);
        SortedSet<Long> subjects=subjects(actor,discovery);VisitFollowUpAuthorizationService.Snapshot first=auth.snapshot(patient,subjects);requireRead(first,actor,null);
        Set<Long> selected=ids(discovery);Map<Long,VisitReadSource> before=repo.visitReadSources(patient,selected,false);
        requireSources(before,selected,first,actor);Map<Long,VisitReadSource> end=repo.visitReadSources(patient,selected,false);VisitFollowUpAuthorizationService.Snapshot last=auth.snapshot(patient,subjects);requireSources(end,selected,last,actor);
        if(!first.signature().equals(last.signature())||!signature(before).equals(signature(end)))throw changed();
        boolean more=discovery.size()>limit;List<Map<String,Object>> items=new ArrayList<>();for(Map<String,Object> row:discovery.subList(0,Math.min(limit,discovery.size())))items.add(project(actor,end.get(id(row.get("source_id"))),last,storage,false));
        return bounded(map("items",items,"hasMore",more,"nextCursor",more?discovery.get(limit-1).get("source_id"):null,"schemaStatus",storage.name()));
    }
    public Map<String,Object> source(long actor,long patient,long source) {
        id(source);VisitFollowUpSchema.Availability storage=storage();List<Map<String,Object>> discovery=discover(actor,patient,source,0,11,true);if(discovery.isEmpty())throw VisitFollowUpAuthorizationService.denied();
        SortedSet<Long> subjects=subjects(actor,discovery);VisitFollowUpAuthorizationService.Snapshot first=auth.snapshot(patient,subjects);requireRead(first,actor,discovery.get(0).get("status"));
        Set<Long> selected=Collections.singleton(source);Map<Long,VisitReadSource> before=repo.visitReadSources(patient,selected,true);requireSources(before,selected,first,actor);VisitReadSource initial=before.get(source);
        VisitActionProjection.ReadSession session=actions.open();VisitActionProjection.Frame frame=actions.read(actor,patient,initial.state,first,session);
        Map<String,Object> origin=origin(actor,patient,initial,first);
        Map<Long,VisitReadSource> end=repo.visitReadSources(patient,selected,true);VisitReadSource current=end.get(source);if(current==null)throw changed();
        VisitActionProjection.Frame finalFrame=actions.read(actor,patient,current.state,first,session);Map<String,Object> finalOrigin=origin(actor,patient,current,first);
        // 最后一次权限快照必须在全部来源/证据读取后；纯到期不改数据库行，另比有效权限谓词。
        VisitFollowUpAuthorizationService.Snapshot last=auth.snapshot(patient,subjects);requireSources(end,selected,last,actor);
        if(!first.signature().equals(last.signature())||!readPermissions(first,actor).equals(readPermissions(last,actor))||!signature(before).equals(signature(end))||!frame.signature().equals(finalFrame.signature())||!canonical(origin).equals(canonical(finalOrigin)))throw changed();
        Map<String,Object> result=project(actor,current,last,storage,true);result.put("actions",actions.project(patient,finalFrame,last,current(current)));result.put("originAppointment",finalOrigin);return bounded(result);
    }
    public Map<String,Object> events(long actor,long patient,long source,long after,int limit) {
        page(after,limit);storage();List<Map<String,Object>> discovery=discover(actor,patient,source,0,1,false);if(discovery.size()!=1)throw VisitFollowUpAuthorizationService.denied();
        SortedSet<Long> subjects=subjects(actor,discovery);VisitFollowUpAuthorizationService.Snapshot first=auth.snapshot(patient,subjects);requireRead(first,actor,discovery.get(0).get("status"));Set<Long> selected=Collections.singleton(source);
        Map<Long,VisitReadSource> before=repo.visitReadSources(patient,selected,false);requireSources(before,selected,first,actor);Map<String,Object> state=before.get(source).state;
        String bytes="OCTET_LENGTH(COALESCE(actor_name,''))+OCTET_LENGTH(COALESCE(actor_relation,''))+OCTET_LENGTH(COALESCE(reason,''))";
        List<Map<String,Object>> rows=state==null?Collections.emptyList():repo.rows("SELECT id,event_type,actor_id,actor_relation,occurred_at_utc,recorded_at,"+bytes+" AS text_bytes,CASE WHEN "+bytes+"<=65536 THEN actor_name ELSE NULL END AS actor_name,CASE WHEN "+bytes+"<=65536 THEN reason ELSE NULL END AS reason FROM visit_followup_event WHERE state_id=? AND patient_id=? AND id>? ORDER BY id LIMIT ?",limit+1,state.get("id"),patient,after,limit+1);
        for(Map<String,Object> row:rows)if(number(row.remove("text_bytes"))>NATIVE_TEXT_BYTES)throw VisitFollowUpContracts.limit();
        Map<Long,VisitReadSource> end=repo.visitReadSources(patient,selected,false);VisitFollowUpAuthorizationService.Snapshot last=auth.snapshot(patient,subjects);requireSources(end,selected,last,actor);
        if(!first.signature().equals(last.signature())||!signature(before).equals(signature(end)))throw changed();boolean more=rows.size()>limit;
        return bounded(map("items",normalize(rows.subList(0,Math.min(limit,rows.size()))),"hasMore",more,"nextCursor",more?rows.get(limit-1).get("id"):null));
    }
    /** 只选当前已发布FOLLOW_UP的确切行动；不调用无界recorders()/完整计划详情。 */
    public Map<String,Object> actionCandidates(long actor,long patient,long after,int limit) {
        page(after,limit);VisitFollowUpSchema.Availability storage=storage();
        List<Map<String,Object>> discovery=repo.rows("SELECT a.id AS source_id,a.assigned_user_id FROM care_plan_action a JOIN doctor_care_plan p ON p.id=a.plan_id AND p.patient_id=a.patient_id AND p.current_revision_id=a.revision_id JOIN care_plan_revision r ON r.id=a.revision_id AND r.plan_id=p.id WHERE a.patient_id=? AND a.id>? AND p.workflow_version=1 AND p.lifecycle='ACTIVE' AND r.status='PUBLISHED' AND r.plan_type='FOLLOW_UP' AND a.status IN ('OPEN','NEEDS_HELP') ORDER BY a.id LIMIT ?",limit+1,patient,after,limit+1);
        SortedSet<Long> subjects=new TreeSet<>();subjects.add(actor);Set<Long> selected=new LinkedHashSet<>();for(Map<String,Object> row:discovery){subjects.add(id(row.get("assigned_user_id")));selected.add(id(row.get("source_id")));}
        VisitFollowUpAuthorizationService.Snapshot first=auth.snapshot(patient,subjects);if(!first.canPlanClinical(actor))throw VisitFollowUpAuthorizationService.denied();
        List<Map<String,Object>> before=candidates(patient,selected),end=candidates(patient,selected);VisitFollowUpAuthorizationService.Snapshot last=auth.snapshot(patient,subjects);if(!last.canPlanClinical(actor))throw VisitFollowUpAuthorizationService.denied();
        if(!first.signature().equals(last.signature())||!canonical(before).equals(canonical(end))||end.size()!=selected.size())throw changed();boolean more=discovery.size()>limit;List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:end.subList(0,Math.min(limit,end.size()))){long assignee=id(row.get("assigned_user_id"));if(!subjects.contains(assignee))throw changed();if(!last.canPlanRecord(assignee))continue;result.add(map("planId",row.get("plan_id"),"revisionId",row.get("revision_id"),"actionId",row.get("id"),"instruction",row.get("instruction"),"assignedUserId",assignee,"assignedName",last.displayName(assignee),"dueAt",normalize(row.get("due_at")),"actionVersion",row.get("lock_version")));}
        return bounded(map("items",result,"hasMore",more,"nextCursor",more?discovery.get(limit-1).get("source_id"):null,"schemaStatus",storage.name()));
    }
    private List<Map<String,Object>> candidates(long patient,Collection<Long> selected) {
        if(selected.isEmpty())return Collections.emptyList();List<Object> args=new ArrayList<>();args.add(patient);args.addAll(selected);
        List<Map<String,Object>> rows=repo.rows("SELECT a.id,a.plan_id,a.revision_id,a.patient_id,a.assigned_user_id,a.status,a.lock_version,a.due_at,p.lock_version AS plan_version,p.lifecycle,p.current_revision_id,r.status AS revision_status,r.plan_type,OCTET_LENGTH(COALESCE(a.instruction,'')) AS text_bytes,CASE WHEN OCTET_LENGTH(COALESCE(a.instruction,''))<=65536 THEN a.instruction ELSE NULL END AS instruction FROM care_plan_action a JOIN doctor_care_plan p ON p.id=a.plan_id AND p.patient_id=a.patient_id AND p.current_revision_id=a.revision_id JOIN care_plan_revision r ON r.id=a.revision_id AND r.plan_id=p.id WHERE a.patient_id=? AND a.id IN ("+in(selected)+") AND p.workflow_version=1 AND p.lifecycle='ACTIVE' AND r.status='PUBLISHED' AND r.plan_type='FOLLOW_UP' AND a.status IN ('OPEN','NEEDS_HELP') ORDER BY a.id",26,args.toArray());
        for(Map<String,Object> row:rows)if(number(row.remove("text_bytes"))>NATIVE_TEXT_BYTES)throw VisitFollowUpContracts.limit();return rows;
    }
    private List<Map<String,Object>> discover(long actor,long patient,Long source,long after,int maximum,boolean links) {
        String clinical="EXISTS(SELECT 1 FROM doctor_patient_assignment d JOIN sys_user u ON u.id=d.doctor_user_id AND u.status=1 AND u.deleted=0 JOIN sys_user_role m ON m.user_id=u.id JOIN sys_role r ON r.id=m.role_id AND r.status=1 AND r.deleted=0 AND LOWER(TRIM(r.role_code))='doctor' WHERE d.patient_id=v.patient_id AND d.doctor_user_id=? AND d.status='ACTIVE')";
        List<Object> args=new ArrayList<>();args.add(patient);args.add(source==null?after:source);args.add(actor);
        String nativeRows="SELECT v.id,v.patient_id,v.doctor_user_id,v.appointment_id,v.status FROM visit_record v WHERE v.patient_id=? AND v.id"+(source==null?">":"=")+"? AND (v.status='PUBLISHED' OR "+clinical+") ORDER BY v.id LIMIT "+maximum;
        String sql="SELECT v.id AS source_id,v.status,v.doctor_user_id,p.user_id AS owner_id,ca.doctor_user_id AS origin_doctor_id"+(links?",a.assigned_user_id":"")+" FROM ("+nativeRows+") v JOIN patient p ON p.id=v.patient_id LEFT JOIN visit_followup_state s ON s.visit_record_id=v.id AND s.patient_id=v.patient_id AND s.source_kind='VISIT_RECORD' LEFT JOIN visit_followup_state o ON o.id=s.origin_appointment_state_id AND o.patient_id=v.patient_id LEFT JOIN care_appointment ca ON ca.id=COALESCE(o.care_appointment_id,v.appointment_id) AND ca.patient_id=v.patient_id"+(links?" LEFT JOIN visit_followup_action_link l ON l.state_id=s.id AND l.disposition_revision=s.disposition_revision LEFT JOIN care_plan_action a ON a.id=l.action_id":"")+" ORDER BY v.id"+(links?",l.id":"")+" LIMIT "+maximum;
        List<Map<String,Object>> rows=repo.rows(sql,maximum,args.toArray());if(links&&rows.size()>10)throw VisitFollowUpAuthorizationService.limit();return rows;
    }
    private SortedSet<Long> subjects(long actor,List<Map<String,Object>> discovery){SortedSet<Long> result=new TreeSet<>();result.add(actor);for(Map<String,Object> row:discovery)for(String field:Arrays.asList("doctor_user_id","owner_id","origin_doctor_id","assigned_user_id"))if(row.get(field)!=null)result.add(id(row.get(field)));if(result.size()>100)throw VisitFollowUpAuthorizationService.limit();return result;}
    private Set<Long> ids(List<Map<String,Object>> rows){Set<Long> result=new LinkedHashSet<>();for(Map<String,Object> row:rows)result.add(id(row.get("source_id")));return result;}
    private void requireSources(Map<Long,VisitReadSource> rows,Set<Long> expected,VisitFollowUpAuthorizationService.Snapshot authority,long actor){requireRead(authority,actor,null);if(!rows.keySet().equals(expected))throw changed();for(VisitReadSource row:rows.values()){requireRead(authority,actor,row.source.getValues().get("status"));if(row.source.getPatientId()!=authority.getPatientId()||!authority.getSubjects().contains(row.source.getDoctorId()))throw changed();}}
    private static void requireRead(VisitFollowUpAuthorizationService.Snapshot authority,long actor,Object status){if(!authority.canVisitRead(actor)||status!=null&&!"PUBLISHED".equals(status)&&!authority.canClinical(actor))throw VisitFollowUpAuthorizationService.denied();}
    private Map<String,Object> origin(long actor,long patient,VisitReadSource source,VisitFollowUpAuthorizationService.Snapshot authority){if(source.origin==null)return null;String kind=String.valueOf(source.origin.get("kind"));if(!Arrays.asList("CARE_ITEM_APPOINTMENT","CARE_APPOINTMENT").contains(kind))throw changed();SourceRef ref=new SourceRef(SourceKind.valueOf(kind),id(source.origin.get("id")));if(!authority.canAppointmentRead(actor)&&!(ref.getKind()==SourceKind.CARE_APPOINTMENT&&authority.isActiveDoctor(actor)))return map("available",false);NativeSource nativeSource=repo.appointmentSources(Collections.singleton(ref)).get(ref);if(nativeSource==null||nativeSource.getPatientId()!=patient||!nativeSource.isOrdinary())return map("available",false);if(!authority.canAppointmentRead(actor)&&!Objects.equals(nativeSource.getDoctorId(),actor))return map("available",false);if(nativeSource.getDoctorId()!=null&&!authority.getSubjects().contains(nativeSource.getDoctorId()))throw changed();return map("available",true,"sourceKind",kind,"sourceId",ref.getId(),"sourceFingerprint",nativeSource.getFingerprint(),"startWall",normalize(nativeSource.nativeTarget().get("native_start_wall")),"nativeStatus",nativeSource.getValues().get("status"));}
    private Map<String,Object> project(long actor,VisitReadSource row,VisitFollowUpAuthorizationService.Snapshot authority,VisitFollowUpSchema.Availability storage,boolean detail) {
        Map<String,Object> state=row.state,n=row.source.getValues();boolean managed=state!=null,current=current(row);String disposition=managed?String.valueOf(state.get("disposition")):"UNASSESSED";
        if(managed&&state.get("decision_event_id")!=null&&(row.decision==null||number(row.decision.get("id"))!=number(state.get("decision_event_id"))||!Objects.equals(row.decision.get("fingerprint"),state.get("source_fingerprint"))))throw changed();
        Map<String,Object> result=map("patientId",row.source.getPatientId(),"sourceKind","VISIT_RECORD","sourceId",row.source.getReference().getId(),"sourceFingerprint",row.source.getFingerprint(),"managed",managed,"version",managed?state.get("lock_version"):0L,"nativeStatus",n.get("status"),"visitedWall",normalize(n.get("visited_at")),"publishedWall",normalize(n.get("published_at")),"followUpWall",normalize(n.get("follow_up_at")),"doctorUserId",row.source.getDoctorId(),"doctorName",authority.displayName(row.source.getDoctorId()),"actualTimeKind",managed?state.get("actual_time_kind"):"UNKNOWN","actualAtUtc",managed?normalize(state.get("actual_at_utc")):null,"actualOffset",managed?state.get("actual_offset"):null,"disposition",disposition,"currentDisposition",current?disposition:"NEEDS_RECHECK","dispositionRevision",managed?state.get("disposition_revision"):0L,"sourceChanged",!current,"schemaStatus",storage.name());
        List<String> commands=new ArrayList<>();if(storage.isWriteAvailable(properties.isEnabled())&&authority.canClinical(actor)) {
            if("DRAFT".equals(n.get("status"))&&managed&&current&&"UNASSESSED".equals(disposition))commands.add("PUBLISH_VISIT");
            if("PUBLISHED".equals(n.get("status"))&&nativeCompatible(row))commands.add("UNASSESSED".equals(disposition)?"SET_DISPOSITION":"REVISE_DISPOSITION");
        }
        result.put("allowedCommands",commands);result.put("planBindingAvailable",storage.isWriteAvailable(properties.isEnabled())&&authority.canPlanClinical(actor));
        if(detail){result.put("diagnosisSummary",row.body.get("diagnosis_summary"));result.put("treatmentSummary",row.body.get("treatment_summary"));result.put("followUpAdvice",row.body.get("follow_up_advice"));result.put("decision",row.decision==null?null:map("eventId",row.decision.get("id"),"actorId",row.decision.get("actor"),"actorName",row.decision.get("actor_name"),"actorRelation",row.decision.get("relation"),"recordedAt",normalize(row.decision.get("recorded_at")),"reason",row.decision.get("reason"),"current",current));}
        return result;
    }
    private static boolean current(VisitReadSource row){return row.state==null||row.source.getFingerprint().equals(row.state.get("source_fingerprint"));}
    private static boolean nativeCompatible(VisitReadSource row){if(row.state==null)return true;for(Map.Entry<String,Object> entry:row.source.nativeTarget().entrySet())if(!Objects.equals(normalize(entry.getValue()),normalize(row.state.get(entry.getKey()))))return false;return true;}
    private static List<Boolean> readPermissions(VisitFollowUpAuthorizationService.Snapshot authority,long actor){return Arrays.asList(authority.canVisitRead(actor),authority.canClinical(actor),authority.canPlanRead(actor),authority.canPlanEvidenceModule(actor,"MEDICAL"),authority.canPlanEvidenceModule(actor,"MEASUREMENTS"),authority.canAppointmentRead(actor),authority.isActiveDoctor(actor));}
    private static String signature(Map<Long,VisitReadSource> rows){Map<Long,String> result=new TreeMap<>();rows.forEach((id,row)->result.put(id,row.signature()));return canonical(result);}
    private VisitFollowUpSchema.Availability storage(){VisitFollowUpSchema.Availability result=bridge.availability();if(!result.isReadAvailable())throw new WorkflowException(503,"VISIT_FOLLOWUP_SCHEMA_NOT_READY","Visit follow-up storage is unavailable.");return result;}
    private static String in(Collection<?> ids){return String.join(",",Collections.nCopies(ids.size(),"?"));}
    private static void page(long after,int size){if(after<0||after>MAX_ID||size<1||size>MAX_PAGE)throw invalid("Invalid visit page cursor or size.");}
    private static Map<String,Object> bounded(Map<String,Object> value){requireBytes(WorkflowData.json(value),RESPONSE_BYTES);return value;}
    private static WorkflowException changed(){return new WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","The visit, linked action, evidence or current access changed. Refresh to compare.");}
}
