package org.familyhealthcare.service.careplan;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.HumanLinkObservation;
import org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.Anchor;
import org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.CareEvidenceReadFrame;
import org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.ReferenceSnapshot;
import org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.familyhealthcare.service.careplan.CarePlanData.*;

/** Current authorization and minimal care-plan projections; never calls the full care context. */
@Service
@ConditionalOnProperty(name="care-plan.enabled",havingValue="true")
@Transactional(readOnly=true)
public class CarePlanQueryService {
    @org.springframework.beans.factory.annotation.Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @org.springframework.beans.factory.annotation.Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions medicalReferences;

    @org.springframework.beans.factory.annotation.Autowired private MedicalRecordReviewAccess medicalAccess;
    @org.springframework.beans.factory.annotation.Autowired private DataSourceTransactionManager medicalTransactions;

    private final JdbcTemplate jdbc;private final CarePlanAuthorizationService auth;private final CarePlanProperties properties;
    private static final Set<String> QUEUES=new HashSet<>(Arrays.asList("TODAY","REVIEW","HELP","OVERDUE","HISTORY"));
    public CarePlanQueryService(JdbcTemplate jdbc,CarePlanAuthorizationService auth,CarePlanProperties properties){this.jdbc=jdbc;this.auth=auth;this.properties=properties;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(long actorId,Long patientId,String queue,String cursor,int limit){return list(actorId,patientId,queue,cursor,limit,null);}
    /** TODAY accepts the browser's next local midnight as an exclusive UTC cutoff. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(long actorId,Long patientId,String queue,String cursor,int limit,String dueBefore){
        EvidenceSelection selection=new EvidenceSelection();properties.requireEnabled();limit(limit);if(!QUEUES.contains(queue))throw CarePlanException.invalid("Unsupported care-plan queue.");

        if(dueBefore!=null&&!"TODAY".equals(queue))throw CarePlanException.invalid("dueBefore is only valid for TODAY.");
        Instant cutoff=null;
        if("TODAY".equals(queue)){
            if(dueBefore==null)cutoff=properties.now().atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            else{if(!dueBefore.endsWith("Z")||!dueBefore.equals(dueBefore.trim()))throw CarePlanException.invalid("dueBefore must be an explicit UTC Z instant.");cutoff=CarePlanContracts.parseOffsetInstant(dueBefore);}
        }
        String cursorQueue="TODAY".equals(queue)?queue+"@"+cutoff.toString():queue;
        auth.requireActiveActor(actorId);if(patientId!=null)auth.requireRead(actorId,patientId);
        String scope=patientId==null?"ALL":patientId.toString();String[] after=cursor(cursor,"PLAN",actorId,scope,cursorQueue);
        List<Map<String,Object>>items=new ArrayList<>();String[] last=after;boolean exhausted=false;
        // Broad relationship candidates are only a prefilter. Central authorization decides every row.
        while(items.size()<=limit&&!exhausted){
            String sql="SELECT p.id,p.patient_id,r.created_at AS sort_at FROM doctor_care_plan p JOIN patient pa ON pa.id=p.patient_id JOIN care_plan_revision r ON r.plan_id=p.id AND r.revision_no=1 WHERE p.workflow_version=1";
            List<Object>args=new ArrayList<>();
            if(patientId!=null){sql+=" AND p.patient_id=?";args.add(patientId);}
            else {sql+=" AND (pa.user_id=? OR EXISTS(SELECT 1 FROM doctor_patient_assignment da WHERE da.patient_id=p.patient_id AND da.doctor_user_id=?) OR EXISTS(SELECT 1 FROM care_access_grant g WHERE g.patient_id=p.patient_id AND g.grantee_user_id=?))";Collections.addAll(args,actorId,actorId,actorId);}
            String active="p.lifecycle='ACTIVE' AND a.revision_id=p.current_revision_id";
            if("TODAY".equals(queue)){sql+=" AND (p.lifecycle='DRAFT' OR EXISTS(SELECT 1 FROM care_plan_action a WHERE a.plan_id=p.id AND "+active+" AND a.status IN ('OPEN','NEEDS_HELP') AND a.due_at<?))";args.add(cutoff);}
            else if("HISTORY".equals(queue))sql+=" AND ((p.lifecycle='DRAFT' AND p.current_revision_id IS NULL) OR (p.current_revision_id IS NOT NULL AND p.lifecycle IN ('ACTIVE','COMPLETED','CANCELLED')))";
            else {String filter="REVIEW".equals(queue)?"a.status='SUBMITTED'":"HELP".equals(queue)?"a.status='NEEDS_HELP'":"a.status IN ('OPEN','NEEDS_HELP') AND a.due_at<?";
                sql+=" AND EXISTS(SELECT 1 FROM care_plan_action a WHERE a.plan_id=p.id AND "+active+" AND "+filter+")";if("OVERDUE".equals(queue))args.add(properties.now());}
            if(last!=null){sql+=" AND (r.created_at>? OR (r.created_at=? AND p.id>?))";Instant time=CarePlanContracts.parseOffsetInstant(last[0]);Collections.addAll(args,time,time,Long.parseLong(last[1]));}
            sql+=" ORDER BY r.created_at,p.id LIMIT ?";args.add(limit+1);
            List<Map<String,Object>>rows=query(sql,(rs,i)->map("id",rs.getLong("id"),"patientId",rs.getLong("patient_id"),"sortAt",time(rs,"sort_at")),args);
            if(rows.isEmpty())break;exhausted=rows.size()<limit+1;
            for(Map<String,Object>row:rows){last=new String[]{(String)row.get("sortAt"),row.get("id").toString()};
                try {Map<String,Object>view=detail(actorId,id(row.get("id")),false,selection);view.put("_cursor",last);items.add(view);}
                catch(CarePlanException ex){if(ex.getStatus()!=403)throw ex;}
                if(items.size()>limit)break;
            }
        }
        String next=null;if(items.size()>limit){items.remove(items.size()-1);String[] key=(String[])items.get(items.size()-1).get("_cursor");next=encode("PLAN",actorId,scope,cursorQueue,key[0],key[1]);}
        for(Map<String,Object>item:items)item.remove("_cursor");projectMedicalEvidence(actorId,items,selection);return map("items",items,"nextCursor",next);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(long actorId,long planId){return detail(actorId,planId,true,new EvidenceSelection());}
    private Map<String,Object> detail(long actorId,long planId,boolean project,EvidenceSelection selection){
        Map<String,Object>plan=plan(actorId,planId);boolean clinical=clinical(actorId,id(plan.get("patientId")));
        Long selected=(Long)plan.get("currentRevisionId");
        if(selected==null){if(!clinical)throw CarePlanException.denied();selected=(Long)plan.get("draftRevisionId");}
        if(selected==null)throw CarePlanException.denied();Map<String,Object> result=snapshot(actorId,plan,selected,clinical,selection);if(project)projectMedicalEvidence(actorId,Collections.singletonList(result),selection);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> revision(long actorId,long planId,long revisionId){EvidenceSelection selection=new EvidenceSelection();Map<String,Object>plan=plan(actorId,planId);Map<String,Object> result=snapshot(actorId,plan,revisionId,clinical(actorId,id(plan.get("patientId"))),selection);projectMedicalEvidence(actorId,Collections.singletonList(result),selection);return result;}
    public Map<String,Object> listRevisions(long actorId,long planId,String cursor,int limit){
        limit(limit);Map<String,Object>plan=plan(actorId,planId);boolean clinical=clinical(actorId,id(plan.get("patientId")));
        String[]after=cursor(cursor,"REVISION",actorId,Long.toString(planId),"HISTORY");
        String sql="SELECT id,revision_no,status,created_at,published_at FROM care_plan_revision WHERE plan_id=?";List<Object>args=new ArrayList<>();args.add(planId);
        if(!clinical)sql+=" AND status='PUBLISHED'";
        if(after!=null){sql+=" AND (created_at>? OR (created_at=? AND id>?))";Instant t=CarePlanContracts.parseOffsetInstant(after[0]);Collections.addAll(args,t,t,Long.parseLong(after[1]));}
        sql+=" ORDER BY created_at,id LIMIT ?";args.add(limit+1);
        List<Map<String,Object>>rows=query(sql,(rs,i)->map("id",rs.getLong("id"),"planId",planId,"revisionNo",rs.getInt("revision_no"),"status",rs.getString("status"),"createdAt",time(rs,"created_at"),"publishedAt",time(rs,"published_at")),args);
        String next=null;if(rows.size()>limit){rows.remove(rows.size()-1);Map<String,Object>last=rows.get(rows.size()-1);next=encode("REVISION",actorId,Long.toString(planId),"HISTORY",(String)last.get("createdAt"),last.get("id").toString());}
        return map("items",rows,"nextCursor",next);
    }
    public List<Map<String,Object>>assignees(long actorId,long patientId){properties.requireEnabled();return auth.recorders(actorId,patientId);}

    /** Compatibility statistics use current clinical authority, never the old admin/global shortcut. */
    public Map<String,Object>clinicalCounts(long actorId){
        properties.requireEnabled();auth.requireActiveActor(actorId);int drafts=0,active=0;
        List<Long>patients=jdbc.queryForList("SELECT DISTINCT patient_id FROM doctor_patient_assignment WHERE doctor_user_id=? ORDER BY patient_id",Long.class,actorId);
        for(long patient:patients){
            try{auth.requireClinical(actorId,patient);}catch(CarePlanException denied){if(denied.getStatus()!=403)throw denied;continue;}
            drafts+=jdbc.queryForObject("SELECT COUNT(*) FROM doctor_care_plan WHERE patient_id=? AND workflow_version=1 AND (lifecycle='DRAFT' OR draft_revision_id IS NOT NULL)",Integer.class,patient);
            active+=jdbc.queryForObject("SELECT COUNT(*) FROM doctor_care_plan WHERE patient_id=? AND workflow_version=1 AND lifecycle='ACTIVE'",Integer.class,patient);
        }
        return map("draftPlans",drafts,"activeCollaborativePlans",active);
    }

    /** Minimal aggregate metadata; authorization happens before any revision body is loaded. */
    Map<String,Object>plan(long actor,long planId){
        properties.requireEnabled();List<Map<String,Object>>rows=jdbc.query("SELECT id,patient_id,lifecycle,current_revision_id,draft_revision_id,lock_version FROM doctor_care_plan WHERE id=? AND workflow_version=1",(rs,i)->map("id",rs.getLong("id"),"patientId",rs.getLong("patient_id"),"workflowVersion",1,"lifecycle",rs.getString("lifecycle"),"currentRevisionId",nullableId(rs,"current_revision_id"),"draftRevisionId",nullableId(rs,"draft_revision_id"),"version",rs.getLong("lock_version")),planId);
        if(rows.isEmpty())throw CarePlanException.denied();Map<String,Object>row=rows.get(0);long patient=id(row.get("patientId"));auth.requireRead(actor,patient);
        if(row.get("currentRevisionId")==null)auth.requireClinical(actor,patient);return row;
    }
    private boolean clinical(long actor,long patient){try{auth.requireClinical(actor,patient);return true;}catch(CarePlanException ex){if(ex.getStatus()!=403)throw ex;return false;}}
    private Map<String,Object>snapshot(long actor,Map<String,Object>aggregate,long revisionId,boolean clinical,EvidenceSelection selection){
        long plan=id(aggregate.get("id")),patient=id(aggregate.get("patientId"));
        List<Map<String,Object>>rows=jdbc.query("SELECT id,revision_no,status,title,instructions,plan_type,draft_json FROM care_plan_revision WHERE id=? AND plan_id=?",(rs,i)->map("revisionId",rs.getLong("id"),"revisionNo",rs.getInt("revision_no"),"revisionStatus",rs.getString("status"),"title",rs.getString("title"),"instructions",rs.getString("instructions"),"planType",rs.getString("plan_type"),"draftJson",rs.getString("draft_json")),revisionId,plan);
        if(rows.isEmpty())throw CarePlanException.denied();Map<String,Object>revision=rows.get(0);boolean draft="DRAFT".equals(revision.get("revisionStatus"));
        if(draft&&!clinical)throw CarePlanException.denied();if(!draft&&!"PUBLISHED".equals(revision.get("revisionStatus")))throw CarePlanException.denied();
        Map<String,Object>body=object((String)revision.remove("draftJson"));Map<String,Object>view=new LinkedHashMap<>(aggregate);view.putAll(revision);
        view.put("actions",draft?draftActions(actor,plan,revisionId,patient,body):publishedActions(actor,plan,revisionId,patient,body));
        applyCapabilities(actor,patient,view,clinical);

        if(!clinical)view.put("draftRevisionId",null);
        else if(view.get("currentRevisionId")!=null)view.put("revisionImpact",revisionImpact(plan,(Long)view.get("currentRevisionId")));
        selection.clinicalViews.put(view,clinical);return view;
    }
    /** UI hints use the same current gates; command services independently enforce every action. */
    @SuppressWarnings("unchecked")private void applyCapabilities(long actor,long patient,Map<String,Object>view,boolean clinical){
        List<Map<String,Object>>actions=(List<Map<String,Object>>)view.get("actions");
        boolean current=Objects.equals(view.get("revisionId"),view.get("currentRevisionId"));
        boolean draft="DRAFT".equals(view.get("revisionStatus"))&&Objects.equals(view.get("revisionId"),view.get("draftRevisionId"));
        boolean active="ACTIVE".equals(view.get("lifecycle"));List<String>allowed=new ArrayList<>();
        if(clinical){
            if(draft&&Arrays.asList("DRAFT","ACTIVE").contains(view.get("lifecycle"))){allowed.add("SAVE_DRAFT");allowed.add("PUBLISH_PLAN");}
            else if(current&&active){
                if(view.get("draftRevisionId")==null)allowed.add("CREATE_REVISION");allowed.add("CANCEL_PLAN");
                boolean allConfirmed=!actions.isEmpty()&&actions.size()<=50;for(Map<String,Object>action:actions)allConfirmed&="CONFIRMED".equals(action.get("status"));
                if(allConfirmed)allowed.add("CLOSE_PLAN");
            }
        }
        view.put("allowedActions",allowed);
        boolean record=false,nursing=false;
        if(current&&active){
            try{auth.requireRecord(actor,patient);record=true;}catch(CarePlanException denied){if(denied.getStatus()!=403)throw denied;}
            if(!clinical)try{auth.requireNursing(actor,patient);nursing=true;}catch(CarePlanException denied){if(denied.getStatus()!=403)throw denied;}
        }
        boolean owner=record&&jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE id=? AND user_id=?",Integer.class,patient,actor)==1;
        for(Map<String,Object>action:actions){
            List<String>capabilities=new ArrayList<>();String status=(String)action.get("status");
            if(current&&active){
                if(record&&Arrays.asList("OPEN","NEEDS_HELP").contains(status))capabilities.add("SUBMIT_RECEIPT");
                if(record&&"OPEN".equals(status))capabilities.add("REQUEST_HELP");
                if((clinical||nursing)&&Arrays.asList("OPEN","NEEDS_HELP","SUBMITTED").contains(status))capabilities.add("FOLLOW_UP");
                if(clinical&&"SUBMITTED".equals(status))capabilities.add("REVIEW_RECEIPT");
            }
            action.put("allowedActions",capabilities);
            action.put("allowedEntryModes",capabilities.contains("SUBMIT_RECEIPT")?(owner?Arrays.asList("ASSISTED","SELF"):Collections.singletonList("ASSISTED")):Collections.emptyList());
        }
    }
    /** Used by publishing to recheck precisely the same deterministic current-action digest. */
    Map<String,Object>revisionImpact(long planId,long currentRevisionId){
        properties.requireEnabled();List<Map<String,Object>>rows=jdbc.query("SELECT id,status,lock_version FROM care_plan_action WHERE plan_id=? AND revision_id=? ORDER BY id",(rs,i)->map("id",rs.getLong("id"),"status",rs.getString("status"),"version",rs.getLong("lock_version")),planId,currentRevisionId);
        List<Long>ids=new ArrayList<>();for(Map<String,Object>row:rows)ids.add(id(row.get("id")));return map("currentRevisionId",currentRevisionId,"actionIds",ids,"digest",hash(json(rows)));
    }
    private List<Map<String,Object>>draftActions(long actor,long plan,long revision,long patient,Map<String,Object>body){
        List<Map<String,Object>>result=new ArrayList<>();for(Map<String,Object>input:actions(body)){
            Map<String,Object>row=map("id",null,"planId",plan,"revisionId",revision,"patientId",patient,"ordinal",((Number)input.get("ordinal")).intValue(),"assignedUserId",id(input.get("assignedUserId")),"version",0L,"instruction",input.get("instruction"),"status","DRAFT","dueAt",input.get("dueAt"),"overdue",false,"firstSubmittedAt",null,"latestSubmittedAt",null,"reviewWaitingSince",null,"events",Collections.emptyList(),"evidence",evidenceViews(actor,patient,evidence(input)));result.add(row);
        }return result;
    }
    private List<Map<String,Object>>publishedActions(long actor,long plan,long revision,long patient,Map<String,Object>body){
        List<Map<String,Object>>rows=jdbc.query("SELECT id,ordinal,assigned_user_id,lock_version,instruction,status,due_at,first_submitted_at,latest_submitted_at,review_waiting_since FROM care_plan_action WHERE plan_id=? AND revision_id=? AND patient_id=? ORDER BY ordinal",(rs,i)->{
            String due=time(rs,"due_at"),status=rs.getString("status");return map("id",rs.getLong("id"),"planId",plan,"revisionId",revision,"patientId",patient,"ordinal",rs.getInt("ordinal"),"assignedUserId",rs.getLong("assigned_user_id"),"version",rs.getLong("lock_version"),"instruction",rs.getString("instruction"),"status",status,"dueAt",due,"overdue",Arrays.asList("OPEN","NEEDS_HELP").contains(status)&&properties.now().isAfter(Instant.parse(due)),"firstSubmittedAt",time(rs,"first_submitted_at"),"latestSubmittedAt",time(rs,"latest_submitted_at"),"reviewWaitingSince",time(rs,"review_waiting_since"));},plan,revision,patient);
        for(Map<String,Object>row:rows){int ordinal=((Number)row.get("ordinal")).intValue();List<Map<String,Object>>refs=Collections.emptyList();for(Map<String,Object>input:actions(body))if(((Number)input.get("ordinal")).intValue()==ordinal){refs=evidence(input);break;}
            row.put("evidence",evidenceViews(actor,patient,refs));row.put("events",events(actor,patient,plan,revision,id(row.get("id"))));}
        return rows;
    }
    private List<Map<String,Object>>events(long actor,long patient,long plan,long revision,long action){
        String visibility=clinical(actor,patient)?"":" AND event_type NOT LIKE 'DRAFT_%' AND event_type<>'REVISION_CREATED'";
        List<Map<String,Object>>rows=jdbc.query("SELECT id,actor_id,actor_name,actor_role,entry_mode,event_type,note,occurred_at,recorded_at FROM care_plan_event WHERE patient_id=? AND plan_id=? AND revision_id=? AND action_id=?"+visibility+" ORDER BY id",(rs,i)->map("id",rs.getLong("id"),"actorId",rs.getLong("actor_id"),"actorName",rs.getString("actor_name"),"actorRole",rs.getString("actor_role"),"entryMode",rs.getString("entry_mode"),"eventType",rs.getString("event_type"),"note",rs.getString("note"),"occurredAt",time(rs,"occurred_at"),"recordedAt",time(rs,"recorded_at")),patient,plan,revision,action);
        for(Map<String,Object>row:rows){List<Map<String,Object>>refs=jdbc.query("SELECT id,source_type,source_id FROM care_plan_evidence WHERE event_id=? ORDER BY id",(rs,i)->map("anchorId",rs.getLong("id"),"sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id")),row.get("id"));row.put("evidence",evidenceViews(actor,patient,refs));}return rows;
    }
    /** Replays keep their original version/body, but evidence permissions are never cached. */
    @SuppressWarnings("unchecked")Map<String,Object>refreshEvidence(long actor,Map<String,Object>original){
        EvidenceSelection selection=new EvidenceSelection();Map<String,Object>view=object(json(original));Map<String,Object>current=plan(actor,id(view.get("id")));long patient=id(current.get("patientId"));auth.requireClinical(actor,patient);
        if(patient!=id(view.get("patientId")))throw CarePlanException.denied();
        for(Map<String,Object>action:(List<Map<String,Object>>)view.get("actions")){
            action.put("evidence",evidenceViews(actor,patient,evidence(action)));
            for(Map<String,Object>event:(List<Map<String,Object>>)action.get("events"))event.put("evidence",evidenceViews(actor,patient,evidence(event)));
        }
        refreshCapabilities(actor,patient,view,current);
        selection.clinicalViews.put(view,true);projectMedicalEvidence(actor,Collections.singletonList(view),selection);
        return view;
    }
    /** Retain original snapshot data/version, but intersect its controls with current authority/state. */
    @SuppressWarnings("unchecked")private void refreshCapabilities(long actor,long patient,Map<String,Object>view,Map<String,Object>current){
        long plan=id(current.get("id")),revision=id(view.get("revisionId"));
        List<String>statuses=jdbc.queryForList("SELECT status FROM care_plan_revision WHERE id=? AND plan_id=?",String.class,revision,plan);
        boolean sameKind=!statuses.isEmpty()&&Objects.equals(view.get("revisionStatus"),statuses.get(0));
        Map<String,Object>capabilities=new LinkedHashMap<>(current);capabilities.put("revisionId",revision);capabilities.put("revisionStatus",sameKind?statuses.get(0):null);
        List<Map<String,Object>>rows=Collections.emptyList();
        if(sameKind&&"PUBLISHED".equals(statuses.get(0)))rows=jdbc.query("SELECT id,status FROM care_plan_action WHERE plan_id=? AND revision_id=? AND patient_id=? ORDER BY ordinal",(rs,i)->map("id",rs.getLong("id"),"status",rs.getString("status")),plan,revision,patient);
        capabilities.put("actions",rows);
        if(sameKind)applyCapabilities(actor,patient,capabilities,true);else capabilities.put("allowedActions",Collections.emptyList());
        view.put("allowedActions",retainedControls(view.get("allowedActions"),capabilities.get("allowedActions")));
        for(Map<String,Object>action:(List<Map<String,Object>>)view.get("actions")){
            Map<String,Object>now=null;for(Map<String,Object>row:rows)if(Objects.equals(action.get("id"),row.get("id"))){now=row;break;}
            action.put("allowedActions",retainedControls(action.get("allowedActions"),now==null?null:now.get("allowedActions")));
            action.put("allowedEntryModes",retainedControls(action.get("allowedEntryModes"),now==null?null:now.get("allowedEntryModes")));
        }
    }
    private static List<String>retainedControls(Object original,Object current){
        List<String>result=new ArrayList<>();if(original instanceof List&&current instanceof List)for(Object value:(List<?>)original)if(((List<?>)current).contains(value))result.add((String)value);return result;
    }
    /** A fixed selected inventory; qualification is published only after the complete release checks. */
    @SuppressWarnings("unchecked") private void projectMedicalEvidence(long actor,List<Map<String,Object>> views,EvidenceSelection selected) {
        for(Map<String,Object> view:views) {
            long patient=id(view.get("patientId"));boolean clinical=Boolean.TRUE.equals(selected.clinicalViews.get(view));
            selected.patients.put(patient,Boolean.TRUE.equals(selected.patients.get(patient))||clinical);
            for(Map<String,Object> action:(List<Map<String,Object>>)view.get("actions")) {
                List<Map<String,Object>> grouped=new ArrayList<>(evidence(action));for(Map<String,Object> event:(List<Map<String,Object>>)action.get("events"))grouped.addAll(evidence(event));
                for(Map<String,Object> ref:grouped)if("MEDICAL_RECORD".equals(ref.get("sourceType"))&&!Boolean.TRUE.equals(ref.get("restricted")))selected.add(ref,patient);
            }
        }
        try {
            EvidenceResource owner=new EvidenceResource(actor,selected);boolean writable=!owner.readOnly;
            if(!writable&&owner.isolation!=Connection.TRANSACTION_REPEATABLE_READ)throw evidenceUnavailable();
            if(selected.refs.isEmpty()){if(writable)evidenceAuthority(actor,selected,false);else currentEvidenceFrame(actor,selected,owner,false);owner.verify();return;}
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)selected.clear();}
            });
            Map<Long,String> unbound=new LinkedHashMap<>();CareEvidenceReadFrame snapshot;
            if(writable) {
                evidenceAuthority(actor,selected,true);owner.verify();
                MedicalRecordValidityProjection.Scope scope=medicalValidity.open();
                snapshot=medicalReferences.readCareEvidenceFrame(new ArrayList<>(selected.anchors.values()),Collections.emptyList(),scope);
                validateEvidenceFrame(selected,snapshot,selected.boundSources,owner.isolation==Connection.TRANSACTION_REPEATABLE_READ?Semantics.CALLER_TRANSACTION_SNAPSHOT:Semantics.CURRENT_CHECK);
                List<Long> human=new ArrayList<>(selected.sources.keySet());human.removeAll(selected.boundSources);
                for(int offset=0;offset<human.size();offset+=100) {
                    List<Long> chunk=human.subList(offset,Math.min(offset+100,human.size()));Map<Long,HumanLinkObservation> observed=scope.humanLinkBatch(chunk);owner.verify();
                    if(observed==null||!observed.keySet().equals(new LinkedHashSet<>(chunk)))throw evidenceChanged();
                    for(long source:chunk) {
                        HumanLinkObservation link=observed.get(source);if(link==null)throw evidenceChanged();State nativeState=link.nativeState;
                        if(nativeState==null||nativeState.id!=source||nativeState.version==null||!Objects.equals(nativeState.patientId,selected.sources.get(source)))throw evidenceChanged();
                        String status=link.sourceStatus;
                        if(!link.metadataAvailable)status="SOURCE_UNAVAILABLE";
                        else if("CURRENT".equals(status)&&(nativeState.managed||link.sourceState!=null||link.doctorState!=null))status="UNKNOWN_NEEDS_RECHECK";
                        unbound.put(source,status);
                    }
                }
            } else {
                snapshot=medicalReferences.readCareEvidenceFrame(new ArrayList<>(selected.anchors.values()),selected.sources.keySet(),medicalValidity.open());
                validateEvidenceFrame(selected,snapshot,selected.sources.keySet(),Semantics.CALLER_TRANSACTION_SNAPSHOT);owner.verify();
                evidenceAdmission(actor,selected,snapshot,owner);
                CareEvidenceReadFrame first=currentEvidenceFrame(actor,selected,owner,true);sameEvidenceFrame(snapshot,first);
                CareEvidenceReadFrame second=currentEvidenceFrame(actor,selected,owner,true);sameEvidenceFrame(snapshot,second);sameEvidenceFrame(first,second);
            }
            Map<Map<String,Object>,String> staged=new IdentityHashMap<>();
            for(Map<String,Object> ref:selected.refs) {
                long source=id(ref.get("sourceId"));String status;
                if(ref.get("anchorId")!=null)status=snapshot.snapshots.get(id(ref.get("anchorId"))).status;
                else if(unbound.containsKey(source))status=unbound.get(source);
                else {State state=snapshot.sources.get(source);status=!state.eligible()?state.status():state.managed||state.reviewParticipating?"UNKNOWN_NEEDS_RECHECK":"UNTRACKED";}
                staged.put(ref,status==null?"SOURCE_UNAVAILABLE":status);
            }
            if(writable)evidenceAuthority(actor,selected,true);else {evidenceAdmission(actor,selected,snapshot,owner);currentEvidenceFrame(actor,selected,owner,false);}
            owner.verify();for(Map<String,Object> ref:selected.refs)ref.put("sourceStatus",staged.get(ref));
        } catch(WorkflowException failure) {selected.clear();throw new CarePlanException(failure.getStatus(),failure.getErrorCode(),"The current care-plan evidence could not be verified.");}
        catch(CarePlanException failure){selected.clear();throw failure;}
        catch(RuntimeException failure){selected.clear();throw evidenceUnavailable();}
        catch(Error failure){selected.clear();throw failure;}
    }
    private static final class EvidenceSelection {
        final Long requestActor=CurrentUserUtil.getCurrentUserId();
        final Map<Map<String,Object>,Boolean> clinicalViews=new IdentityHashMap<>();
        final List<Map<String,Object>> refs=new ArrayList<>();final SortedMap<Long,Long> sources=new TreeMap<>();
        final SortedMap<Long,Boolean> patients=new TreeMap<>();final SortedMap<Long,Anchor> anchors=new TreeMap<>();final Set<Long> boundSources=new TreeSet<>();
        void add(Map<String,Object> ref,long patient) {
            long source=id(ref.get("sourceId"));Long prior=sources.putIfAbsent(source,patient);if(prior!=null&&prior!=patient)throw evidenceChanged();refs.add(ref);
            if(ref.get("anchorId")!=null) {
                long anchorId=id(ref.get("anchorId"));Anchor anchor=new Anchor(anchorId,patient,source,null),old=anchors.putIfAbsent(anchorId,anchor);
                if(old!=null&&(old.patient!=patient||old.record!=source))throw evidenceChanged();boundSources.add(source);
            }
        }
        void clear(){for(Map<String,Object> ref:refs)ref.put("sourceStatus","SOURCE_UNAVAILABLE");}
    }
    private void validateEvidenceFrame(EvidenceSelection selected,CareEvidenceReadFrame frame,Collection<Long> sources,Semantics semantics) {
        if(frame==null||frame.qualificationSemantics!=semantics||frame.checkedAt==null||!frame.sources.keySet().equals(new LinkedHashSet<>(sources))||!frame.snapshots.keySet().equals(selected.anchors.keySet()))throw evidenceChanged();
        for(long source:sources) {
            State state=frame.sources.get(source);
            if(state==null||state.id!=source||!state.reviewObserved||state.signature==null||state.version==null||state.version<=0||!Objects.equals(state.patientId,selected.sources.get(source))||state.qualificationSemantics!=semantics||"SOURCE_UNAVAILABLE".equals(state.status()))throw evidenceChanged();
        }
        for(ReferenceSnapshot snapshot:frame.snapshots.values())if(snapshot==null||snapshot.signature==null||snapshot.qualificationSemantics!=semantics)throw evidenceChanged();
    }
    private static void sameEvidenceFrame(CareEvidenceReadFrame expected,CareEvidenceReadFrame current) {
        if(!expected.sources.keySet().equals(current.sources.keySet())||!expected.snapshots.keySet().equals(current.snapshots.keySet()))throw evidenceChanged();
        for(long source:expected.sources.keySet())if(!Objects.equals(expected.sources.get(source).signature,current.sources.get(source).signature))throw evidenceChanged();
        for(long anchor:expected.snapshots.keySet())if(!Objects.equals(expected.snapshots.get(anchor).signature,current.snapshots.get(anchor).signature))throw evidenceChanged();
    }
    private CareEvidenceReadFrame currentEvidenceFrame(long actor,EvidenceSelection selected,EvidenceResource outer,boolean collect) {
        outer.verify();if(medicalTransactions==null)throw evidenceUnavailable();TransactionTemplate current=new TransactionTemplate(medicalTransactions);current.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        current.setReadOnly(true);current.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            return current.execute(status->{
                EvidenceResource owner=new EvidenceResource(actor,selected);
                if(!owner.readOnly||owner.isolation!=Connection.TRANSACTION_READ_COMMITTED||owner.holder==outer.holder||owner.connection==outer.connection)throw evidenceUnavailable();
                evidenceAuthority(actor,selected,false);owner.verify();if(!collect)return null;
                CareEvidenceReadFrame frame=medicalReferences.readCareEvidenceFrame(new ArrayList<>(selected.anchors.values()),selected.sources.keySet(),medicalValidity.open());
                validateEvidenceFrame(selected,frame,selected.sources.keySet(),Semantics.CURRENT_CHECK);evidenceAuthority(actor,selected,false);owner.verify();return frame;
            });
        } finally {outer.verify();}
    }
    private void evidenceAdmission(long actor,EvidenceSelection selected,CareEvidenceReadFrame frame,EvidenceResource owner) {
        owner.verify();
        for(long source:selected.sources.keySet()) {
            State state=frame.sources.get(source);MedicalRecord nativeRecord=new MedicalRecord();nativeRecord.setId(state.id);nativeRecord.setPatientId(state.patientId);nativeRecord.setUserId(state.ownerId);nativeRecord.setContentVersion(state.version);nativeRecord.setVerificationStatus(state.review);
            // E2's focused singleton denial is the only IllegalStateException escaping this exact call.
            try{medicalAccess.requireEvidenceAdmission(nativeRecord,actor,selected.sources.get(source));}
            catch(IllegalStateException denied){throw CarePlanException.denied();}
            owner.verify();
        }
    }
    private void evidenceAuthority(long actor,EvidenceSelection selected,boolean sources) {
        requireEvidenceActor(actor,selected);if(selected.patients.isEmpty())auth.requireActiveActor(actor);
        for(Map.Entry<Long,Boolean> patient:selected.patients.entrySet()){auth.requireRead(actor,patient.getKey());if(patient.getValue())auth.requireClinical(actor,patient.getKey());}
        if(sources)for(Map.Entry<Long,Long> source:selected.sources.entrySet())if(!auth.canReadEvidence(actor,source.getValue(),"MEDICAL_RECORD",source.getKey()))throw CarePlanException.denied();
        requireEvidenceActor(actor,selected);
    }
    private void evidenceGraph(boolean medical) {
        if(jdbc.getDataSource()==null||medicalTransactions!=null&&medicalTransactions.getDataSource()!=jdbc.getDataSource())throw evidenceUnavailable();
        if(medical){if(medicalTransactions==null||medicalValidity==null||medicalReferences==null||medicalAccess==null)throw evidenceUnavailable();medicalValidity.requireDataSource(jdbc.getDataSource());medicalAccess.requireDataSource(jdbc.getDataSource());}
    }
    private static void requireEvidenceActor(long actor,EvidenceSelection selected){if(actor<=0||actor>9007199254740991L||!Objects.equals(selected.requestActor,CurrentUserUtil.getCurrentUserId())||(selected.requestActor!=null||!selected.refs.isEmpty())&&!Long.valueOf(actor).equals(selected.requestActor))throw CarePlanException.denied();}
    private final class EvidenceResource {
        final Thread thread=Thread.currentThread();final long actor;final EvidenceSelection selected;final ConnectionHolder holder;final SimpleConnectionHandle handle;final Connection connection;final int isolation;final boolean readOnly;
        EvidenceResource(long actor,EvidenceSelection selected) {
            this.actor=actor;this.selected=selected;requireEvidenceActor(actor,selected);evidenceGraph(!selected.refs.isEmpty());Object value=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()||value==null||value.getClass()!=ConnectionHolder.class)throw evidenceUnavailable();
            holder=(ConnectionHolder)value;
            if(!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()==null||holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class)throw evidenceUnavailable();
            handle=(SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();readOnly=TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            try{if(connection==null)throw evidenceUnavailable();isolation=connection.getTransactionIsolation();}catch(SQLException failure){throw evidenceUnavailable();}verify();
        }
        void verify() {
            requireEvidenceActor(actor,selected);evidenceGraph(!selected.refs.isEmpty());
            if(thread!=Thread.currentThread()||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()||readOnly!=TransactionSynchronizationManager.isCurrentTransactionReadOnly()||TransactionSynchronizationManager.getResource(jdbc.getDataSource())!=holder||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw evidenceUnavailable();
            try{if(holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()||connection.getTransactionIsolation()!=isolation||isolation!=Connection.TRANSACTION_READ_COMMITTED&&isolation!=Connection.TRANSACTION_REPEATABLE_READ)throw evidenceUnavailable();}catch(SQLException|IllegalStateException failure){throw evidenceUnavailable();}
        }
    }
    private static CarePlanException evidenceUnavailable(){return new CarePlanException(503,"CURRENT_RESULT_UNAVAILABLE","The current care-plan evidence could not be verified.");}
    private static CarePlanException evidenceChanged(){return new CarePlanException(409,"CURRENT_RESULT_UNAVAILABLE","The current care-plan evidence could not be verified.");}
    private List<Map<String,Object>>evidenceViews(long actor,long patient,List<Map<String,Object>>refs){
        List<Map<String,Object>>result=new ArrayList<>();for(Map<String,Object>ref:refs){String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));boolean readable=auth.canReadEvidence(actor,patient,type,source);Map<String,Object>view=map("sourceType",type,"sourceId",source,"restricted",!readable);if(readable&&ref.get("anchorId")!=null)view.put("anchorId",ref.get("anchorId"));if("MEDICAL_RECORD".equals(type))view.put("sourceStatus",readable?"UNKNOWN_NEEDS_RECHECK":"SOURCE_UNAVAILABLE");
            if(readable){String table="MEASUREMENT".equals(type)?"health_measurement":"medical_record",column="MEASUREMENT".equals(type)?"metric_type":"record_type";
                List<String>titles=jdbc.queryForList("SELECT "+column+" FROM "+table+" WHERE id=? AND patient_id=?",String.class,source,patient);if(titles.isEmpty())view.put("restricted",true);else view.put("title",titles.get(0));}
            result.add(view);
        }return result;
    }
    private <T>List<T>query(String sql,RowMapper<T>mapper,List<Object>args){return jdbc.query(connection->{PreparedStatement ps=connection.prepareStatement(sql);for(int i=0;i<args.size();i++){Object value=args.get(i);if(value instanceof Instant)time(ps,i+1,(Instant)value);else ps.setObject(i+1,value);}return ps;},mapper);}
    private static String encode(String kind,long actor,String scope,String queue,String time,String id){return Base64.getUrlEncoder().withoutPadding().encodeToString((kind+"|"+actor+"|"+scope+"|"+queue+"|"+time+"|"+id).getBytes(StandardCharsets.UTF_8));}
    private static String[]cursor(String cursor,String kind,long actor,String scope,String queue){
        if(cursor==null)return null;try{if(cursor.length()>512||!cursor.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();String[]parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=6||!kind.equals(parts[0])||!Long.toString(actor).equals(parts[1])||!scope.equals(parts[2])||!queue.equals(parts[3])||!parts[5].matches("[1-9][0-9]*")||Long.parseLong(parts[5])<=0)throw new IllegalArgumentException();CarePlanContracts.parseOffsetInstant(parts[4]);return new String[]{parts[4],parts[5]};}
        catch(IllegalArgumentException ex){throw CarePlanException.invalid("Invalid care-plan cursor.");}
    }
}
