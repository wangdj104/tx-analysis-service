package org.familyhealthcare.service.careplan;

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
    private final JdbcTemplate jdbc;private final CarePlanAuthorizationService auth;private final CarePlanProperties properties;
    private static final Set<String> QUEUES=new HashSet<>(Arrays.asList("TODAY","REVIEW","HELP","OVERDUE","HISTORY"));
    public CarePlanQueryService(JdbcTemplate jdbc,CarePlanAuthorizationService auth,CarePlanProperties properties){this.jdbc=jdbc;this.auth=auth;this.properties=properties;}

    public Map<String,Object> list(long actorId,Long patientId,String queue,String cursor,int limit){
        properties.requireEnabled();limit(limit);if(!QUEUES.contains(queue))throw CarePlanException.invalid("Unsupported care-plan queue.");
        auth.requireActiveActor(actorId);if(patientId!=null)auth.requireRead(actorId,patientId);
        String scope=patientId==null?"ALL":patientId.toString();String[] after=cursor(cursor,"PLAN",actorId,scope,queue);
        List<Map<String,Object>>items=new ArrayList<>();String[] last=after;boolean exhausted=false;
        // Broad relationship candidates are only a prefilter. Central authorization decides every row.
        while(items.size()<=limit&&!exhausted){
            String sql="SELECT p.id,p.patient_id,r.created_at AS sort_at FROM doctor_care_plan p JOIN patient pa ON pa.id=p.patient_id JOIN care_plan_revision r ON r.plan_id=p.id AND r.revision_no=1 WHERE p.workflow_version=1";
            List<Object>args=new ArrayList<>();
            if(patientId!=null){sql+=" AND p.patient_id=?";args.add(patientId);}
            else {sql+=" AND (pa.user_id=? OR EXISTS(SELECT 1 FROM doctor_patient_assignment da WHERE da.patient_id=p.patient_id AND da.doctor_user_id=?) OR EXISTS(SELECT 1 FROM care_access_grant g WHERE g.patient_id=p.patient_id AND g.grantee_user_id=?))";Collections.addAll(args,actorId,actorId,actorId);}
            String active="p.lifecycle='ACTIVE' AND a.revision_id=p.current_revision_id";
            if("TODAY".equals(queue)){sql+=" AND (p.lifecycle='DRAFT' OR EXISTS(SELECT 1 FROM care_plan_action a WHERE a.plan_id=p.id AND "+active+" AND a.status IN ('OPEN','NEEDS_HELP') AND a.due_at<?))";args.add(properties.now().atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));}
            else if("HISTORY".equals(queue))sql+=" AND p.current_revision_id IS NOT NULL AND p.lifecycle IN ('ACTIVE','COMPLETED','CANCELLED')";
            else {String filter="REVIEW".equals(queue)?"a.status='SUBMITTED'":"HELP".equals(queue)?"a.status='NEEDS_HELP'":"a.status IN ('OPEN','NEEDS_HELP') AND a.due_at<?";
                sql+=" AND EXISTS(SELECT 1 FROM care_plan_action a WHERE a.plan_id=p.id AND "+active+" AND "+filter+")";if("OVERDUE".equals(queue))args.add(properties.now());}
            if(last!=null){sql+=" AND (r.created_at>? OR (r.created_at=? AND p.id>?))";Instant time=CarePlanContracts.parseOffsetInstant(last[0]);Collections.addAll(args,time,time,Long.parseLong(last[1]));}
            sql+=" ORDER BY r.created_at,p.id LIMIT ?";args.add(limit+1);
            List<Map<String,Object>>rows=query(sql,(rs,i)->map("id",rs.getLong("id"),"patientId",rs.getLong("patient_id"),"sortAt",time(rs,"sort_at")),args);
            if(rows.isEmpty())break;exhausted=rows.size()<limit+1;
            for(Map<String,Object>row:rows){last=new String[]{(String)row.get("sortAt"),row.get("id").toString()};
                try {Map<String,Object>view=detail(actorId,id(row.get("id")));view.put("_cursor",last);items.add(view);}
                catch(CarePlanException ex){if(ex.getStatus()!=403)throw ex;}
                if(items.size()>limit)break;
            }
        }
        String next=null;if(items.size()>limit){items.remove(items.size()-1);String[] key=(String[])items.get(items.size()-1).get("_cursor");next=encode("PLAN",actorId,scope,queue,key[0],key[1]);}
        for(Map<String,Object>item:items)item.remove("_cursor");return map("items",items,"nextCursor",next);
    }
    public Map<String,Object> detail(long actorId,long planId){
        Map<String,Object>plan=plan(actorId,planId);boolean clinical=clinical(actorId,id(plan.get("patientId")));
        Long selected=(Long)plan.get("currentRevisionId");
        if(selected==null){if(!clinical)throw CarePlanException.denied();selected=(Long)plan.get("draftRevisionId");}
        if(selected==null)throw CarePlanException.denied();return snapshot(actorId,plan,selected,clinical);
    }
    public Map<String,Object> revision(long actorId,long planId,long revisionId){Map<String,Object>plan=plan(actorId,planId);return snapshot(actorId,plan,revisionId,clinical(actorId,id(plan.get("patientId"))));}
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
    private Map<String,Object>snapshot(long actor,Map<String,Object>aggregate,long revisionId,boolean clinical){
        long plan=id(aggregate.get("id")),patient=id(aggregate.get("patientId"));
        List<Map<String,Object>>rows=jdbc.query("SELECT id,revision_no,status,title,instructions,plan_type,draft_json FROM care_plan_revision WHERE id=? AND plan_id=?",(rs,i)->map("revisionId",rs.getLong("id"),"revisionNo",rs.getInt("revision_no"),"revisionStatus",rs.getString("status"),"title",rs.getString("title"),"instructions",rs.getString("instructions"),"planType",rs.getString("plan_type"),"draftJson",rs.getString("draft_json")),revisionId,plan);
        if(rows.isEmpty())throw CarePlanException.denied();Map<String,Object>revision=rows.get(0);boolean draft="DRAFT".equals(revision.get("revisionStatus"));
        if(draft&&!clinical)throw CarePlanException.denied();if(!draft&&!"PUBLISHED".equals(revision.get("revisionStatus")))throw CarePlanException.denied();
        Map<String,Object>body=object((String)revision.remove("draftJson"));Map<String,Object>view=new LinkedHashMap<>(aggregate);view.putAll(revision);
        view.put("actions",draft?draftActions(actor,plan,revisionId,patient,body):publishedActions(actor,plan,revisionId,patient,body));
        List<String>allowed=new ArrayList<>();String lifecycle=(String)view.get("lifecycle");
        if(clinical&&Arrays.asList("DRAFT","ACTIVE").contains(lifecycle)){if(draft)allowed.add("SAVE_DRAFT");if("ACTIVE".equals(lifecycle)&&view.get("draftRevisionId")==null)allowed.add("CREATE_REVISION");}
        view.put("allowedActions",allowed);
        if(!clinical)view.put("draftRevisionId",null);
        else if(view.get("currentRevisionId")!=null)view.put("revisionImpact",revisionImpact(plan,(Long)view.get("currentRevisionId")));
        return view;
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
        for(Map<String,Object>row:rows){List<Map<String,Object>>refs=jdbc.query("SELECT source_type,source_id FROM care_plan_evidence WHERE event_id=? ORDER BY id",(rs,i)->map("sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id")),row.get("id"));row.put("evidence",evidenceViews(actor,patient,refs));}return rows;
    }
    /** Replays keep their original version/body, but evidence permissions are never cached. */
    @SuppressWarnings("unchecked")Map<String,Object>refreshEvidence(long actor,Map<String,Object>original){
        Map<String,Object>view=object(json(original));Map<String,Object>current=plan(actor,id(view.get("id")));long patient=id(current.get("patientId"));auth.requireClinical(actor,patient);
        if(patient!=id(view.get("patientId")))throw CarePlanException.denied();
        for(Map<String,Object>action:(List<Map<String,Object>>)view.get("actions")){
            action.put("evidence",evidenceViews(actor,patient,evidence(action)));
            for(Map<String,Object>event:(List<Map<String,Object>>)action.get("events"))event.put("evidence",evidenceViews(actor,patient,evidence(event)));
        }
        return view;
    }
    private List<Map<String,Object>>evidenceViews(long actor,long patient,List<Map<String,Object>>refs){
        List<Map<String,Object>>result=new ArrayList<>();for(Map<String,Object>ref:refs){String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));boolean readable=auth.canReadEvidence(actor,patient,type,source);Map<String,Object>view=map("sourceType",type,"sourceId",source,"restricted",!readable);
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
