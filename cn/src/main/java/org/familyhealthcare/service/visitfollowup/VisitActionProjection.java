package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.medicalrecord.*;
import org.familyhealthcare.service.workflow.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpRepository.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;

/** 只读取已提交的护理行动事实；不调用计划命令，也不缓存“临床完成”。 */
@Service
public class VisitActionProjection {
    private final VisitFollowUpRepository repo;
    private final MedicalRecordValidityProjection medical;
    private final MedicalRecordReferenceVersions references;
    public VisitActionProjection(VisitFollowUpRepository repo,MedicalRecordValidityProjection medical,MedicalRecordReferenceVersions references) {
        this.repo=Objects.requireNonNull(repo);this.medical=Objects.requireNonNull(medical);this.references=Objects.requireNonNull(references);
    }
    /** 同一详情前后只复用结构检查，两个pass仍重读所有业务行。 */
    final class ReadSession {
        private MedicalRecordValidityProjection.Scope scope;
        MedicalRecordValidityProjection.Scope medical(){if(scope==null)scope=medical.open();return scope;}
    }
    ReadSession open(){return new ReadSession();}
    static final class Frame {
        final List<Map<String,Object>> links,plans,actions,events,evidence;
        final Map<Long,String> evidenceStatus;
        final Map<String,Object> evidenceSignatures;
        final boolean available;
        Frame(List<Map<String,Object>> links,List<Map<String,Object>> plans,List<Map<String,Object>> actions,List<Map<String,Object>> events,List<Map<String,Object>> evidence,Map<Long,String> status,Map<String,Object> signatures,boolean available){this.links=links;this.plans=plans;this.actions=actions;this.events=events;this.evidence=evidence;this.evidenceStatus=status;this.evidenceSignatures=signatures;this.available=available;}
        String signature(){return canonical(map("links",links,"plans",plans,"actions",actions,"events",events,"evidence",evidence,"sources",evidenceSignatures,"statuses",evidenceStatus,"available",available));}
    }
    Frame read(long actor,long patient,Map<String,Object> state,VisitFollowUpAuthorizationService.Snapshot authority,ReadSession session) {
        List<Map<String,Object>> links=state==null||number(state.get("disposition_revision"))==0?Collections.emptyList():repo.rows("SELECT id,state_id,patient_id,disposition_revision,plan_id,revision_id,action_id,binding_event_id,bound_after_action_event_id,kind FROM visit_followup_action_link WHERE state_id=? AND disposition_revision=? ORDER BY id LIMIT 11",11,state.get("id"),state.get("disposition_revision"));
        if(links.size()>10)throw VisitFollowUpAuthorizationService.limit();
        for(Map<String,Object> link:links)if(number(link.get("patient_id"))!=patient||number(link.get("state_id"))!=number(state.get("id")))throw changed();
        if(links.isEmpty()||!authority.canPlanRead(actor))return new Frame(links,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyMap(),Collections.emptyMap(),links.isEmpty()||authority.canPlanRead(actor));
        SortedSet<Long> plans=new TreeSet<>(),actions=new TreeSet<>();for(Map<String,Object> link:links){plans.add(id(link.get("plan_id")));actions.add(id(link.get("action_id")));}
        List<Object> planArgs=new ArrayList<>(plans);planArgs.add(patient);
        List<Map<String,Object>> planRows=repo.rows("SELECT p.id,p.patient_id,p.workflow_version,p.lifecycle,p.current_revision_id,p.lock_version,r.id AS revision_id,r.plan_id AS revision_plan_id,r.status AS revision_status,r.plan_type FROM doctor_care_plan p LEFT JOIN care_plan_revision r ON r.id=p.current_revision_id WHERE p.id IN ("+in(plans)+") AND p.patient_id=? ORDER BY p.id",10,planArgs.toArray());
        List<Object> actionArgs=new ArrayList<>(actions);actionArgs.addAll(actions);actionArgs.add(patient);
        List<Map<String,Object>> actionRows=repo.rows("SELECT a.id,a.plan_id,a.revision_id,a.patient_id,a.ordinal,a.assigned_user_id,a.status,a.lock_version,a.due_at,a.first_submitted_at,a.latest_submitted_at,a.review_waiting_since,COALESCE(w.event_watermark,0) AS event_watermark,OCTET_LENGTH(COALESCE(a.instruction,'')) AS text_bytes,CASE WHEN OCTET_LENGTH(COALESCE(a.instruction,''))<=65536 THEN a.instruction ELSE NULL END AS instruction FROM care_plan_action a LEFT JOIN (SELECT action_id,MAX(id) AS event_watermark FROM care_plan_event WHERE action_id IN ("+in(actions)+") GROUP BY action_id) w ON w.action_id=a.id WHERE a.id IN ("+in(actions)+") AND a.patient_id=? ORDER BY a.id",10,actionArgs.toArray());
        for(Map<String,Object> action:actionRows){textSize(action);if(!authority.getSubjects().contains(id(action.get("assigned_user_id"))))throw changed();}
        // MySQL5.7可用的grouped MAX + 确切索引连接；不扫描/返回全部历史事件。
        String bytes="OCTET_LENGTH(COALESCE(e.actor_name,''))+OCTET_LENGTH(COALESCE(e.actor_role,''))+OCTET_LENGTH(COALESCE(e.actor_relation,''))+OCTET_LENGTH(COALESCE(e.entry_mode,''))+OCTET_LENGTH(COALESCE(e.note,''))";
        List<Object> eventArgs=new ArrayList<>(actions);eventArgs.add(patient);
        List<Map<String,Object>> eventRows=repo.rows("SELECT e.id,e.patient_id,e.plan_id,e.revision_id,e.action_id,e.actor_id,e.actor_role,e.actor_relation,e.entry_mode,e.event_type,e.occurred_at,e.recorded_at,"+bytes+" AS text_bytes,CASE WHEN "+bytes+"<=65536 THEN e.actor_name ELSE NULL END AS actor_name,CASE WHEN "+bytes+"<=65536 THEN e.note ELSE NULL END AS note FROM care_plan_event e JOIN (SELECT action_id,event_type,MAX(id) AS id FROM care_plan_event WHERE action_id IN ("+in(actions)+") AND event_type IN ('RECEIPT_SUBMITTED','RECEIPT_CONFIRMED','RECEIPT_RETURNED') GROUP BY action_id,event_type) latest ON latest.id=e.id WHERE e.patient_id=? ORDER BY e.action_id,e.id",30,eventArgs.toArray());
        for(Map<String,Object> event:eventRows)textSize(event);
        List<String> terms=new ArrayList<>();for(long action:actions)terms.add("(SELECT v.id,v.event_id,v.source_type,v.source_id,e.action_id,e.plan_id,e.revision_id,e.patient_id FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id WHERE e.action_id=? AND e.event_type='RECEIPT_SUBMITTED' ORDER BY v.id LIMIT 51)");
        List<Map<String,Object>> evidence=repo.rows(String.join(" UNION ALL ",terms)+" ORDER BY action_id,id",510,actions.toArray());
        Map<Long,Integer> counts=new HashMap<>();for(Map<String,Object> row:evidence)counts.merge(id(row.get("action_id")),1,Integer::sum);
        Map<Long,String> statuses=new LinkedHashMap<>();Map<String,Object> signatures=new TreeMap<>();List<MedicalRecordReferenceVersions.Anchor> medicalAnchors=new ArrayList<>();List<Map<String,Object>> measurements=new ArrayList<>();
        for(Map<String,Object> row:evidence) {
            long anchor=id(row.get("id")),action=id(row.get("action_id"));Map<String,Object> linked=by(links,"action_id",action);
            if(counts.get(action)>50||linked==null||number(row.get("patient_id"))!=patient||number(row.get("plan_id"))!=number(linked.get("plan_id"))||number(row.get("revision_id"))!=number(linked.get("revision_id"))) {statuses.put(anchor,"SOURCE_UNAVAILABLE");continue;}
            String type=String.valueOf(row.get("source_type"));
            if("MEDICAL_RECORD".equals(type)&&authority.canPlanEvidenceModule(actor,"MEDICAL"))medicalAnchors.add(new MedicalRecordReferenceVersions.Anchor(anchor,patient,id(row.get("source_id")),null));
            else if("MEASUREMENT".equals(type)&&authority.canPlanEvidenceModule(actor,"MEASUREMENTS"))measurements.add(row);
            else statuses.put(anchor,"SOURCE_UNAVAILABLE");
        }
        // 累计所有历史提交的证据，与现CARE_PLAN复核合同一致；51st哨兵不能被截掉称有效。
        for(int offset=0;offset<medicalAnchors.size();offset+=100) {
            List<MedicalRecordReferenceVersions.Anchor> part=medicalAnchors.subList(offset,Math.min(offset+100,medicalAnchors.size()));Set<Long> sources=new LinkedHashSet<>();for(MedicalRecordReferenceVersions.Anchor anchor:part)sources.add(anchor.record);
            MedicalRecordValidityProjection.Scope scope=session.medical();Map<Long,MedicalRecordValidityProjection.State> states=scope.batchSnapshot(sources);
            for(long source:sources)signatures.put("medical:"+source,states.get(source)==null?null:states.get(source).signature);
            Map<Long,MedicalRecordReferenceVersions.ReferenceSnapshot> projected=references.projectSnapshot(MedicalRecordReferenceVersions.Kind.CARE_PLAN_EVIDENCE,part,scope,states);
            for(MedicalRecordReferenceVersions.Anchor anchor:part){MedicalRecordReferenceVersions.ReferenceSnapshot projection=projected.get(anchor.id);statuses.put(anchor.id,projection==null?"SOURCE_UNAVAILABLE":projection.status);signatures.put("reference:"+anchor.id,projection==null?null:projection.signature);}
        }
        for(int offset=0;offset<measurements.size();offset+=100) {
            List<Map<String,Object>> part=measurements.subList(offset,Math.min(offset+100,measurements.size()));Set<Long> sources=new TreeSet<>();for(Map<String,Object> row:part)sources.add(id(row.get("source_id")));
            List<Map<String,Object>> rows=repo.rows("SELECT id,patient_id,recorded_by,metric_type,metric_name,value_primary,value_secondary,unit,measured_at,source_type,status FROM health_measurement WHERE id IN ("+in(sources)+") ORDER BY id",100,sources.toArray());
            for(long source:sources)signatures.put("measurement:"+source,by(rows,"id",source));
            for(Map<String,Object> anchor:part){Map<String,Object> value=by(rows,"id",id(anchor.get("source_id")));statuses.put(id(anchor.get("id")),value!=null&&number(value.get("patient_id"))==patient?"CURRENT":"SOURCE_UNAVAILABLE");}
        }
        return new Frame(links,planRows,actionRows,eventRows,evidence,statuses,signatures,true);
    }
    Map<String,Object> project(long patient,Frame frame,VisitFollowUpAuthorizationService.Snapshot authority,boolean currentVisit) {
        if(!frame.available)return map("available",false,"allConfirmed",false,"items",Collections.emptyList(),"status","CURRENT_RESULT_UNAVAILABLE");
        List<Map<String,Object>> items=new ArrayList<>();boolean all=!frame.links.isEmpty();
        for(Map<String,Object> link:frame.links) {
            long action=id(link.get("action_id"));Map<String,Object> plan=by(frame.plans,"id",id(link.get("plan_id"))),row=by(frame.actions,"id",action),submitted=event(frame.events,action,"RECEIPT_SUBMITTED"),confirmed=event(frame.events,action,"RECEIPT_CONFIRMED"),returned=event(frame.events,action,"RECEIPT_RETURNED");
            boolean fresh=true;int evidenceCount=0;for(Map<String,Object> evidence:frame.evidence)if(number(evidence.get("action_id"))==action){evidenceCount++;fresh&=MedicalRecordReferenceVersions.fresh(frame.evidenceStatus.get(id(evidence.get("id"))));}if(evidenceCount>50)fresh=false;
            String status=currentResult(patient,link,plan,row,submitted,confirmed,returned,fresh,currentVisit);all&="CONFIRMED".equals(status);
            Map<String,Object> value=map("kind",link.get("kind"),"planId",link.get("plan_id"),"revisionId",link.get("revision_id"),"actionId",action,"boundAfterActionEventId",link.get("bound_after_action_event_id"),"status",status,"evidenceCount",evidenceCount,"evidenceCurrent",fresh);
            if(row!=null&&number(row.get("patient_id"))==patient&&number(row.get("plan_id"))==number(link.get("plan_id"))&&number(row.get("revision_id"))==number(link.get("revision_id"))) {
                long assigned=id(row.get("assigned_user_id"));value.putAll(map("instruction",row.get("instruction"),"assignedUserId",assigned,"assignedName",authority.displayName(assigned),"assignedCurrent",authority.canPlanRecord(assigned),"dueAt",normalize(row.get("due_at")),"actionStatus",row.get("status"),"actionVersion",row.get("lock_version")));
            }
            value.put("submission",eventView(patient,link,submitted));value.put("confirmation",eventView(patient,link,confirmed));value.put("returned",eventView(patient,link,returned));items.add(value);
        }
        return map("available",true,"allConfirmed",all,"items",items,"status",all?"ALL_CONFIRMED":"NOT_ALL_CONFIRMED");
    }
    static String currentResult(long patient,Map<String,Object> link,Map<String,Object> plan,Map<String,Object> action,Map<String,Object> submitted,Map<String,Object> confirmed,Map<String,Object> returned,boolean evidenceCurrent,boolean currentVisit) {
        if(!currentVisit||plan==null||action==null||number(plan.get("patient_id"))!=patient||number(plan.get("workflow_version"))!=1||!Arrays.asList("ACTIVE","COMPLETED").contains(plan.get("lifecycle"))||number(plan.get("current_revision_id"))!=number(link.get("revision_id"))||number(plan.get("revision_id"))!=number(link.get("revision_id"))||number(plan.get("revision_plan_id"))!=number(link.get("plan_id"))||!"PUBLISHED".equals(plan.get("revision_status"))||!"FOLLOW_UP".equals(plan.get("plan_type"))||number(action.get("patient_id"))!=patient||number(action.get("plan_id"))!=number(link.get("plan_id"))||number(action.get("revision_id"))!=number(link.get("revision_id")))return "NEEDS_RECHECK";
        if(!evidenceCurrent)return "CURRENT_RESULT_UNAVAILABLE";
        boolean realSubmit=matches(patient,link,submitted,"RECEIPT_SUBMITTED")&&number(submitted.get("id"))>number(link.get("bound_after_action_event_id"))&&submitted.get("occurred_at")!=null;
        boolean realConfirm=realSubmit&&matches(patient,link,confirmed,"RECEIPT_CONFIRMED")&&number(confirmed.get("id"))>number(submitted.get("id"));
        boolean realReturn=realSubmit&&matches(patient,link,returned,"RECEIPT_RETURNED")&&number(returned.get("id"))>number(submitted.get("id"));
        String status=String.valueOf(action.get("status"));
        if("CONFIRMED".equals(status))return realConfirm&&(!realReturn||number(returned.get("id"))<number(confirmed.get("id")))?"CONFIRMED":"NEEDS_RECHECK";
        if("SUBMITTED".equals(status))return realSubmit&&(!realReturn||number(returned.get("id"))<number(submitted.get("id")))?"SUBMITTED":"NEEDS_RECHECK";
        if(realReturn)return "RETURNED";
        return Arrays.asList("OPEN","NEEDS_HELP").contains(status)?status:"NEEDS_RECHECK";
    }
    private static boolean matches(long patient,Map<String,Object> link,Map<String,Object> event,String type){return event!=null&&number(event.get("patient_id"))==patient&&number(event.get("plan_id"))==number(link.get("plan_id"))&&number(event.get("revision_id"))==number(link.get("revision_id"))&&number(event.get("action_id"))==number(link.get("action_id"))&&type.equals(event.get("event_type"));}
    private static Map<String,Object> eventView(long patient,Map<String,Object> link,Map<String,Object> event){if(event==null||!matches(patient,link,event,String.valueOf(event.get("event_type"))))return null;return map("id",event.get("id"),"actorId",event.get("actor_id"),"actorName",event.get("actor_name"),"actorRole",event.get("actor_role"),"actorRelation",event.get("actor_relation"),"entryMode",event.get("entry_mode"),"eventType",event.get("event_type"),"occurredAt",normalize(event.get("occurred_at")),"recordedAt",normalize(event.get("recorded_at")),"note",event.get("note"));}
    private static Map<String,Object> event(List<Map<String,Object>> rows,long action,String type){for(Map<String,Object> row:rows)if(number(row.get("action_id"))==action&&type.equals(row.get("event_type")))return row;return null;}
    private static Map<String,Object> by(List<Map<String,Object>> rows,String field,long id){for(Map<String,Object> row:rows)if(number(row.get(field))==id)return row;return null;}
    private static String in(Collection<?> ids){return String.join(",",Collections.nCopies(ids.size(),"?"));}
    private static void textSize(Map<String,Object> row){if(number(row.remove("text_bytes"))>NATIVE_TEXT_BYTES)throw VisitFollowUpContracts.limit();}
    private static WorkflowException changed(){return new WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","The linked action or its evidence changed. Refresh to review current facts.");}
}
