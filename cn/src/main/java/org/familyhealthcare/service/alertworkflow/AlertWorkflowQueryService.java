package org.familyhealthcare.service.alertworkflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;

/** Current-authority projections. No clinical titles, record URLs, or unrestricted cached event payloads. */
@Service
@ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
public class AlertWorkflowQueryService {
    private static final Set<String> QUEUES=new HashSet<>(Arrays.asList("ALL","TODAY","WAITING_ACCEPTANCE","ASSIGNED_TO_ME","WAITING_FEEDBACK","WAITING_REVIEW","BLOCKED"));
    private final JdbcTemplate jdbc;private final AlertWorkflowRepository repo;private final AlertWorkflowAuthorizationService auth;private final AlertWorkflowProperties properties;private final AlertWorkflowEvidenceService evidence;
    public AlertWorkflowQueryService(JdbcTemplate jdbc,AlertWorkflowRepository repo,AlertWorkflowAuthorizationService auth,AlertWorkflowProperties properties,AlertWorkflowEvidenceService evidence){this.jdbc=jdbc;this.repo=repo;this.auth=auth;this.properties=properties;this.evidence=evidence;}
    public Map<String,Object> detail(long actor,long alertId) {
        Map<String,Object> row=repo.readAlert(actor,alertId);long patient=id(row.get("patientId"));Map<String,Object> source=repo.readSource(actor,patient,(String)row.get("sourceType"),id(row.get("sourceId")));
        Map<String,Object> result=project(actor,row);result.put("source",source);if(restrictedHistory(actor,patient,alertId)){result.put("closureReason",null);result.put("restricted",true);}
        List<Map<String,Object>> refs=new ArrayList<>();for(Map<String,Object> ref:evidence.dependencies(alertId)) {
            if(!evidence.readable(actor,patient,ref,false))refs.add(map("id",ref.get("id"),"restricted",true,"dependencyState","REFERENCE_CHANGED","canResolve",evidence.canResolve(actor,patient,(String)row.get("workflowMode"),ref)));
            else {Map<String,Object> clean=new LinkedHashMap<>(ref);clean.put("restricted",false);clean.put("canResolve",evidence.canResolve(actor,patient,(String)row.get("workflowMode"),ref));if(!evidence.valid(actor,patient,ref))clean.put("dependencyState","REFERENCE_CHANGED");refs.add(clean);}
        }
        result.put("evidence",refs);result.put("deliveries",jdbc.query("SELECT n.id,n.status,n.attempt_count,n.last_result,n.delivered_at FROM alert_notification n JOIN alert_event e ON e.id=n.event_id WHERE e.alert_id=? AND n.recipient_user_id=? ORDER BY n.id",(rs,i)->map("id",rs.getLong(1),"status",rs.getString(2),"attemptCount",rs.getInt(3),"lastResult",AlertWorkflowNotificationWorker.safeResult(rs.getString(4)),"deliveredAt",time(rs,"delivered_at")),alertId,actor));
        auth.requireRead(actor,patient);repo.readSource(actor,patient,(String)row.get("sourceType"),id(row.get("sourceId")));return result;
    }
    public Map<String,Object> list(long actor,long patient,String queue,String cursor,int limit) {
        limit(limit);AlertWorkflowContracts.choice(queue,"queue",QUEUES);auth.requireRead(actor,patient);String scope="list:"+actor+":"+patient+":"+queue;long after=cursor(cursor,scope);
        List<Map<String,Object>> items=new ArrayList<>();long scanned=after;
        // Fixed cheap predicates precede source authorization/projection. Computed queues still have
        // a hard 100-candidate request budget; an empty page can legitimately carry continuation.
        String predicate="";List<Object> args=new ArrayList<>(Arrays.asList(patient,after));
        if("WAITING_ACCEPTANCE".equals(queue))predicate=" AND workflow_state='OPEN'";
        else if("WAITING_FEEDBACK".equals(queue))predicate=" AND workflow_state='WAITING_FEEDBACK'";
        else if("WAITING_REVIEW".equals(queue))predicate=" AND workflow_state='READY_FOR_REVIEW'";
        else if(Arrays.asList("TODAY","ASSIGNED_TO_ME","BLOCKED").contains(queue))predicate=" AND workflow_state NOT IN ('CLOSED','CANCELLED')";
        if("ASSIGNED_TO_ME".equals(queue)){predicate+=" AND responsible_user_id=?";args.add(actor);}
        List<Long> ids=jdbc.queryForList("SELECT id FROM alert_record WHERE patient_id=? AND workflow_version=1 AND id>?"+predicate+" ORDER BY id LIMIT 101",Long.class,args.toArray());
        int consumed=0;
        for(long alert:ids) {
            if(consumed==100||items.size()==limit)break;
            consumed++;scanned=alert;
            try {Map<String,Object> row=repo.readAlert(actor,alert);repo.readSource(actor,patient,(String)row.get("sourceType"),id(row.get("sourceId")));Map<String,Object> clean=project(actor,row);if(matches(actor,queue,clean))items.add(clean);}
            catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;}
        }
        String next=consumed<ids.size()?encode(scope,scanned):null;auth.requireRead(actor,patient);return map("items",items,"nextCursor",next,"patient",map("id",patient));
    }
    public Map<String,Object> events(long actor,long alertId,String cursor,int limit) {
        limit(limit);Map<String,Object> alert=repo.readAlert(actor,alertId);long patient=id(alert.get("patientId"));repo.readSource(actor,patient,(String)alert.get("sourceType"),id(alert.get("sourceId")));
        String scope="events:"+actor+":"+patient+":"+alertId;long after=cursor(cursor,scope);List<Map<String,Object>> items=repo.events(alertId,after,limit);
        boolean restrictedHistory=restrictedHistory(actor,patient,alertId);
        for(Map<String,Object> event:items) {
            List<Map<String,Object>> refs=evidence.sanitized(actor,patient,id(event.get("id")));boolean restricted=restrictedHistory;for(Map<String,Object> ref:refs)if(Boolean.TRUE.equals(ref.get("restricted")))restricted=true;
            Map<String,Object> payload=event.get("payload") instanceof Map?cast(event.get("payload")):Collections.emptyMap();
            if(payload.get("previousReference") instanceof Map&&!evidence.readable(actor,patient,cast(payload.get("previousReference")),false))restricted=true;
            Map<String,Object> safe=new LinkedHashMap<>(payload);safe.remove("evidence");safe.remove("replacement");safe.remove("previousReference");
            if(restricted){event.put("note",null);event.put("payload",Collections.emptyMap());event.put("restricted",true);}else{event.put("payload",safe);event.put("restricted",false);}event.put("evidence",refs);
        }
        long last=items.isEmpty()?after:id(items.get(items.size()-1).get("id"));boolean more=jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE alert_id=? AND id>?",Integer.class,alertId,last)>0;
        auth.requireRead(actor,patient);repo.readSource(actor,patient,(String)alert.get("sourceType"),id(alert.get("sourceId")));return map("items",items,"nextCursor",more?encode(scope,last):null,"alertId",alertId);
    }
    public List<Map<String,Object>> assignees(long actor,long patient,String mode){return auth.eligibleAssignees(actor,patient,mode);}
    public Map<String,Object> capabilities(long actor,long patient){auth.requireRead(actor,patient);boolean owner=can(()->auth.requireOwner(actor,patient)),doctor=can(()->auth.requireClinical(actor,patient));return map("enabled",true,"patientId",patient,"canAdopt",can(()->auth.requireRecord(actor,patient)),"canAssignPersonal",owner,"canRequestClinical",owner||doctor,"canSetClinicalDeadline",doctor,"canGrantMeasurementsToAssignedNurse",owner,"queues",new TreeSet<>(QUEUES));}
    private Map<String,Object> project(long actor,Map<String,Object> row) {
        long patient=id(row.get("patientId"));boolean owner=can(()->auth.requireOwner(actor,patient)),doctor=can(()->auth.requireClinical(actor,patient)),record=can(()->auth.requireRecord(actor,patient));
        boolean clinical="CLINICAL".equals(row.get("workflowMode")),terminal=Arrays.asList("CLOSED","CANCELLED").contains(row.get("workflowState"));
        boolean responsible=Objects.equals(row.get("responsibleUserId"),actor)&&record&&(!clinical||doctor||can(()->auth.requireNursing(actor,patient)));
        boolean reviewer=clinical?doctor:owner;String state=(String)row.get("workflowState");List<String> blockers=new ArrayList<>(),actions=new ArrayList<>();
        if(row.get("responsibleUserId")==null)blockers.add(clinical?"AWAITING_CLINICIAN":"UNASSIGNED");
        else {long assignee=id(row.get("responsibleUserId"));if(Boolean.TRUE.equals(row.get("reassignmentRequired"))||!can(()->auth.requireRecord(assignee,patient))||clinical&&!(can(()->auth.requireClinical(assignee,patient))||can(()->auth.requireNursing(assignee,patient))))blockers.add("REASSIGNMENT_REQUIRED");}
        if(Boolean.TRUE.equals(row.get("sourceReviewRequired")))blockers.add("SOURCE_CHANGED");
        if(row.get("dueAt")!=null&&!terminal&&Instant.parse((String)row.get("dueAt")).isBefore(properties.now()))blockers.add("OVERDUE");
        for(Map<String,Object> ref:evidence.dependencies(id(row.get("id"))))if(!evidence.valid(actor,patient,ref)){blockers.add("REFERENCE_CHANGED");break;}
        if(!terminal) {
            if(owner||clinical&&doctor)actions.add("ASSIGN");if(owner||doctor)actions.add("ESCALATE");
            if("OPEN".equals(state)&&responsible){actions.add("REJECT_ASSIGNMENT");if(!blockers.contains("SOURCE_CHANGED")&&!blockers.contains("REASSIGNMENT_REQUIRED"))actions.add("ACCEPT");}
            if(Arrays.asList("ACCEPTED","IN_PROGRESS").contains(state)&&responsible&&!blockers.contains("SOURCE_CHANGED")&&!blockers.contains("REASSIGNMENT_REQUIRED")){actions.add("FOLLOW_UP");actions.add("SUBMIT");}
            if("WAITING_FEEDBACK".equals(state)&&record&&!blockers.contains("SOURCE_CHANGED")&&!blockers.contains("REASSIGNMENT_REQUIRED"))actions.add("FEEDBACK");
            if(reviewer){actions.add("CANCEL");if("READY_FOR_REVIEW".equals(state)){actions.add("RETURN");if(!blockers.contains("SOURCE_CHANGED")&&!blockers.contains("REFERENCE_CHANGED")&&!blockers.contains("REASSIGNMENT_REQUIRED")&&row.get("acceptedBy")!=null&&row.get("currentFeedbackEventId")!=null&&can(()->evidence.requireComplete(actor,row)))actions.add("CLOSE");}if(Boolean.TRUE.equals(row.get("sourceReviewRequired")))actions.add("REVIEW_SOURCE_CHANGE");}
            for(Map<String,Object> ref:evidence.dependencies(id(row.get("id"))))if(evidence.canResolve(actor,patient,(String)row.get("workflowMode"),ref)){actions.add("RESOLVE_REFERENCE_CHANGE");break;}
        }else if(reviewer)actions.add("REOPEN");
        Map<String,Object> clean=new LinkedHashMap<>(row);clean.remove("legacyStatus");clean.put("alertId",row.get("id"));clean.put("version",row.get("lockVersion"));clean.put("state",state);clean.put("mode",row.get("workflowMode"));clean.put("patient",map("id",patient));clean.put("blockers",blockers);clean.put("allowedActions",actions);
        clean.put("permissions",map("canRecord",record,"canClosePersonal",owner,"canCloseClinical",doctor,"canSetDeadline",reviewer,"canDeclareSelf",owner));boolean restricted=restrictedHistory(actor,patient,id(row.get("id")));clean.put("restricted",restricted);if(restricted)clean.put("closureReason",null);clean.put("closureKind",clinical?"CLINICAL_REVIEW":"PERSONAL_HANDLING_RECORDED");return clean;
    }
    private boolean restrictedHistory(long actor,long patient,long alert) {
        List<Map<String,Object>> refs=jdbc.query("SELECT DISTINCT v.source_type,v.source_id FROM alert_evidence v JOIN alert_event e ON e.id=v.event_id WHERE e.alert_id=?",(rs,i)->map("sourceType",rs.getString(1),"sourceId",rs.getLong(2)),alert);
        for(Map<String,Object> ref:refs)if(!evidence.readable(actor,patient,ref,false))return true;return false;
    }
    private boolean matches(long actor,String queue,Map<String,Object> row){String state=(String)row.get("state");boolean terminal=Arrays.asList("CLOSED","CANCELLED").contains(state);if("ALL".equals(queue))return true;if("TODAY".equals(queue))return!terminal;if("WAITING_ACCEPTANCE".equals(queue))return"OPEN".equals(state);if("ASSIGNED_TO_ME".equals(queue))return!terminal&&Objects.equals(row.get("responsibleUserId"),actor);if("WAITING_FEEDBACK".equals(queue))return"WAITING_FEEDBACK".equals(state);if("WAITING_REVIEW".equals(queue))return"READY_FOR_REVIEW".equals(state);return!terminal&&!((List<?>)row.get("blockers")).isEmpty();}
    private static boolean can(Runnable work){try{work.run();return true;}catch(AlertWorkflowException denied){if(denied.getStatus()==403||denied.getStatus()==409)return false;throw denied;}}
    private static void limit(int limit){if(limit<1||limit>100)throw AlertWorkflowException.invalid("limit must be between 1 and 100.");}
    private static String encode(String scope,long after){return Base64.getUrlEncoder().withoutPadding().encodeToString((scope+":"+after).getBytes(StandardCharsets.UTF_8));}
    private static long cursor(String cursor,String scope){if(cursor==null)return 0;if(cursor.length()>512)throw AlertWorkflowException.invalid("Invalid cursor.");try{String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);String prefix=scope+":";if(!decoded.startsWith(prefix))throw new IllegalArgumentException();String n=decoded.substring(prefix.length());if(!n.matches("[1-9][0-9]*"))throw new IllegalArgumentException();return Long.parseLong(n);}catch(IllegalArgumentException bad){throw AlertWorkflowException.invalid("Invalid cursor.");}}
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object value){return(Map<String,Object>)value;}
}
