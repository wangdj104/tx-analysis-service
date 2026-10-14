package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.alertworkflow.AlertWorkflowContracts.*;

/** Independent evidence authority and version checks. References remain dependencies until explicit resolution. */
@Service
@ConditionalOnProperty(name="alert-workflow.enabled",havingValue="true")
public class AlertWorkflowEvidenceService {
    private final JdbcTemplate jdbc;private final AlertWorkflowRepository repo;private final AlertWorkflowAuthorizationService auth;private final AlertWorkflowProperties properties;
    public AlertWorkflowEvidenceService(JdbcTemplate jdbc,AlertWorkflowRepository repo,AlertWorkflowAuthorizationService auth,AlertWorkflowProperties properties) {this.jdbc=jdbc;this.repo=repo;this.auth=auth;this.properties=properties;}

    public List<Map<String,Object>> dependencies(long alert) {
        return jdbc.query("SELECT v.id,v.event_id,v.source_type,v.source_id,v.source_revision,v.dependency_version,v.dependency_state FROM alert_evidence v JOIN alert_event e ON e.id=v.event_id WHERE e.alert_id=? AND v.dependency_state<>'WITHDRAWN' ORDER BY v.id",
                (rs,i)->map("id",rs.getLong("id"),"eventId",rs.getLong("event_id"),"sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id"),"sourceRevision",nullableId(rs,"source_revision"),"dependencyVersion",nullableId(rs,"dependency_version"),"dependencyState",rs.getString("dependency_state")),alert);
    }
    /** Discover first, then lock parents sorted, native sources sorted, aggregate later. No late parent acquisition. */
    public Locks lock(Map<String,Object> alert,List<Map<String,Object>> requested) {
        WorkflowData.mutationTransaction(jdbc);List<Map<String,Object>> refs=dependencies(id(alert.get("id")));String before=json(refs);
        List<Map<String,Object>> all=new ArrayList<>(refs);all.addAll(requested);all.add(map("sourceType",alert.get("sourceType"),"sourceId",alert.get("sourceId")));
        String planLinks=planLinks(all);lockReferences(all);
        return new Locks(id(alert.get("id")),before,planLinks,all);
    }
    public void verify(Locks locks) {
        if(!locks.references.equals(json(dependencies(locks.alert)))||!locks.planLinks.equals(planLinks(locks.all)))throw AlertWorkflowException.conflict();
    }
    /** Used by the source bridge before native source mutation; accepts only server-discovered references. */
    void lockReferences(List<Map<String,Object>> references) {
        WorkflowData.mutationTransaction(jdbc);SortedSet<Long> plans=new TreeSet<>();SortedMap<String,Map<String,Object>> sources=new TreeMap<>();
        for(Map<String,Object> ref:references) {
            String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));
            if("CARE_PLAN_ACTION".equals(type)) {
                plans.addAll(jdbc.queryForList("SELECT plan_id FROM care_plan_action WHERE id=?",Long.class,source));
                for(Map<String,Object> nested:nested(source))putSource(sources,nested);
            }else putSource(sources,ref);
        }
        for(long plan:plans)jdbc.queryForList("SELECT id FROM doctor_care_plan WHERE id=? FOR UPDATE",Long.class,plan);
        for(Map<String,Object> source:sources.values()) {
            String type=(String)source.get("sourceType");long sourceId=id(source.get("sourceId"));
            if(SOURCE_TYPES.contains(type))repo.lockSource(type,sourceId);
            else if("MEDICAL_RECORD".equals(type))jdbc.queryForList("SELECT id FROM medical_record WHERE id=? FOR UPDATE",Long.class,sourceId);
        }
    }
    private void putSource(Map<String,Map<String,Object>> sources,Map<String,Object> ref) {String type=(String)ref.get("sourceType");if("MEASUREMENT".equals(type))type="HEALTH_MEASUREMENT";sources.put(type+":"+String.format(Locale.ROOT,"%020d",id(ref.get("sourceId"))),map("sourceType",type,"sourceId",ref.get("sourceId")));}
    private List<Map<String,Object>> nested(long action) {return jdbc.query("SELECT DISTINCT v.source_type,v.source_id FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id WHERE e.action_id=? ORDER BY v.source_type,v.source_id",(rs,i)->map("sourceType",rs.getString(1),"sourceId",rs.getLong(2)),action);}
    String dependencyLinks(List<Map<String,Object>> refs){return planLinks(refs);}
    private String planLinks(List<Map<String,Object>> refs) {List<Object> rows=new ArrayList<>();for(Map<String,Object> ref:refs)if("CARE_PLAN_ACTION".equals(ref.get("sourceType"))){long action=id(ref.get("sourceId"));rows.add(map("action",action,"parents",jdbc.queryForList("SELECT plan_id FROM care_plan_action WHERE id=?",Long.class,action),"nested",nested(action)));}return json(rows);}
    /** Every receipt relationship participates, even before any alert references its action. */
    List<Map<String,Object>> receiptActions(String type,long source) {
        String nativeType="HEALTH_MEASUREMENT".equals(type)?"MEASUREMENT":type;
        return jdbc.query("SELECT DISTINCT a.id FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id JOIN care_plan_action a ON a.id=e.action_id WHERE v.source_type=? AND v.source_id=? ORDER BY a.id",(rs,i)->map("sourceType","CARE_PLAN_ACTION","sourceId",rs.getLong(1)),nativeType,source);
    }
    boolean referencesSource(Map<String,Object> ref,String type,long source) {
        if(type.equals(ref.get("sourceType"))&&id(ref.get("sourceId"))==source)return true;
        if("CARE_PLAN_ACTION".equals(ref.get("sourceType")))for(Map<String,Object> nested:nested(id(ref.get("sourceId")))) {
            String nestedType="MEASUREMENT".equals(nested.get("sourceType"))?"HEALTH_MEASUREMENT":(String)nested.get("sourceType");
            if(type.equals(nestedType)&&id(nested.get("sourceId"))==source)return true;
        }return false;
    }
    public static final class Locks {private final long alert;private final String references,planLinks;private final List<Map<String,Object>> all;private Locks(long alert,String references,String planLinks,List<Map<String,Object>> all){this.alert=alert;this.references=references;this.planLinks=planLinks;this.all=all;}}

    public boolean canResolve(long actor,long patient,String mode,Map<String,Object> reference) {
        try {
            if("CLINICAL".equals(mode)||"CARE_PLAN_ACTION".equals(reference.get("sourceType")))auth.requireClinical(actor,patient);else auth.requireOwner(actor,patient);
            return readable(actor,patient,reference,true);
        }catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;return false;}
    }
    public void requireReadable(long actor,long patient,List<Map<String,Object>> references) {
        for(Map<String,Object> ref:references)if(!readable(actor,patient,ref,false))throw AlertWorkflowException.denied();
    }
    /** Terminal/superseded plan content is not exposed; an authorized doctor may still explicitly terminate its dependency. */
    boolean readable(long actor,long patient,Map<String,Object> ref,boolean resolving) {
        String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));
        if(auth.canReadEvidence(actor,patient,type,source))return true;
        if(!resolving||!"CARE_PLAN_ACTION".equals(type))return false;
        return auth.canResolveEvidence(actor,patient,type,source);
    }

    public void attach(long actor,long patient,long event,List<Map<String,Object>> references) {
        requireReadable(actor,patient,references);
        for(Map<String,Object> ref:references) {
            String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));Long revision=null,version=null;
            if(SOURCE_TYPES.contains(type)) {
                Map<String,Object> row=repo.readSource(actor,patient,type,source);if(!"ACTIVE".equals(row.get("sourceStatus")))throw changed();
                ensureTracked(actor,row);revision=id(row.get("sourceRevision"));
            }else if("CARE_PLAN_ACTION".equals(type)) {
                // All attachment paths (including replacement chains and fresh SUBMIT) retain the action's correction obligation.
                requireFreshPlanConfirmation(ref);version=jdbc.queryForObject("SELECT lock_version FROM care_plan_action WHERE id=?",Long.class,source);
            }
            repo.addEvidence(event,type,source,revision,version,"VALID");
        }
    }
    /** Source lock must already be held. New UTC occurrence is the workflow snapshot capture, never a rewritten legacy measurement time. */
    public void ensureTracked(long actor,Map<String,Object> source) {
        WorkflowData.mutationTransaction(jdbc);
        if(source.get("sourceIdentityId")!=null)return;
        long identity=repo.createSourceIdentity((String)source.get("sourceType"),id(source.get("id")),id(source.get("patientId")));
        repo.appendSourceRevision(identity,1,actor,properties.now(),snapshot(source));source.put("sourceIdentityId",identity);source.put("sourceRevision",1L);source.put("sourceVersion",0L);
    }
    public Map<String,Object> snapshot(Map<String,Object> source) {
        Map<String,Object> result=new LinkedHashMap<>();for(String key:Arrays.asList("metricType","valuePrimary","valueSecondary","unit","measuredAt","sourceKind","systolicBp","diastolicBp","bloodGlucose","bgUnit","measurePeriod","recordDate","recordTime","measureType","verificationStatus"))if(source.containsKey(key))result.put(key,source.get(key));return result;
    }
    public boolean valid(long actor,long patient,Map<String,Object> ref) {
        if(!"VALID".equals(ref.get("dependencyState"))||!readable(actor,patient,ref,false))return false;
        String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));
        if(SOURCE_TYPES.contains(type)) {Map<String,Object> row=repo.readSource(actor,patient,type,source);return "ACTIVE".equals(row.get("sourceStatus"))&&Objects.equals(row.get("sourceRevision"),ref.get("sourceRevision"));}
        if("CARE_PLAN_ACTION".equals(type)) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_action WHERE id=? AND status='CONFIRMED' AND lock_version=?",Integer.class,source,ref.get("dependencyVersion"))!=1)return false;
            return freshPlanConfirmation(source);
        }
        return true;
    }
    public void requireComplete(long actor,Map<String,Object> alert) {
        long patient=id(alert.get("patientId"));List<Map<String,Object>> references=dependencies(id(alert.get("id")));
        for(Map<String,Object> ref:references)if(!valid(actor,patient,ref))throw changed();
        Long handling=(Long)alert.get("currentHandlingEventId");if(handling==null)throw changed();
        List<Map<String,Object>> rows=jdbc.query("SELECT payload_json FROM alert_event WHERE id=? AND alert_id=? AND cycle_no=? AND event_type='SUBMIT'",(rs,i)->object(rs.getString(1)),handling,alert.get("id"),alert.get("cycleNo"));
        if(rows.isEmpty())throw changed();boolean contact=rows.get(0).get("contact") instanceof Map;
        boolean validReference=false;for(Map<String,Object> ref:references)if(Objects.equals(ref.get("eventId"),handling))validReference=true;
        if(!contact&&!validReference)throw changed();
    }
    public Map<String,Object> reference(long alert,long evidenceId) {
        List<Map<String,Object>> rows=jdbc.query("SELECT v.id,v.event_id,v.source_type,v.source_id,v.source_revision,v.dependency_version,v.dependency_state FROM alert_evidence v JOIN alert_event e ON e.id=v.event_id WHERE e.alert_id=? AND v.id=?",(rs,i)->map("id",rs.getLong("id"),"eventId",rs.getLong("event_id"),"sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id"),"sourceRevision",nullableId(rs,"source_revision"),"dependencyVersion",nullableId(rs,"dependency_version"),"dependencyState",rs.getString("dependency_state")),alert,evidenceId);
        if(rows.isEmpty())throw AlertWorkflowException.denied();return rows.get(0);
    }
    /** A correction qualifies the plan action/source history, never a replaceable alert_evidence row. */
    public void requireFreshPlanConfirmation(Map<String,Object> reference) {
        if(!freshPlanConfirmation(id(reference.get("sourceId"))))throw changed();
    }
    private boolean freshPlanConfirmation(long action) {
        // History stays readable, but a doctor confirmation cannot turn an invalidated native
        // measurement back into usable evidence. Attachment/completion hold parent/source locks;
        // authorized history projections conservatively show the current validity.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id JOIN alert_source_identity s ON s.source_type='HEALTH_MEASUREMENT' AND s.source_id=v.source_id WHERE e.action_id=? AND v.source_type='MEASUREMENT' AND s.status='INVALIDATED'",Integer.class,action)>0)return false;
        long watermark=-1;
        List<String> changes=jdbc.query("SELECT DISTINCT r.id,r.action_versions_json FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id JOIN alert_source_identity s ON s.source_type='HEALTH_MEASUREMENT' AND s.source_id=v.source_id LEFT JOIN alert_source_revision r ON r.source_identity_id=s.id WHERE e.action_id=? AND v.source_type='MEASUREMENT'",(rs,i)->rs.getString("action_versions_json"),action);
        for(String raw:changes) {
            // NULL/unknown historical metadata is not the trusted empty object written by this server.
            // Fail closed and expose only the existing REFERENCE_CHANGED blocker, never relation IDs.
            if(raw==null)return false;
            try {
                Object parsed=com.alibaba.fastjson2.JSON.parse(raw);if(!(parsed instanceof Map))return false;
                Map<?,?> versions=(Map<?,?>)parsed;
                for(Map.Entry<?,?> entry:versions.entrySet()) {
                    if(!(entry.getKey() instanceof String)||!((String)entry.getKey()).matches("[1-9][0-9]*"))return false;
                    long linked=Long.parseLong((String)entry.getKey()),version=requireVersion(entry.getValue());
                    if(linked==action)watermark=Math.max(watermark,version);
                }
            }catch(RuntimeException invalid){return false;}
        }
        if(watermark<0)return true; // Unaffected actions retain the existing current-status/version contract.
        List<Long> current=jdbc.queryForList("SELECT lock_version FROM care_plan_action WHERE id=? AND status='CONFIRMED'",Long.class,action);
        if(current.isEmpty()||current.get(0)<=watermark)return false;
        List<Map<String,Object>> confirmations=jdbc.query("SELECT payload_json FROM care_plan_event WHERE action_id=? AND event_type='RECEIPT_CONFIRMED' AND actor_role='DOCTOR' ORDER BY id DESC LIMIT 1",(rs,i)->object(rs.getString(1)),action);
        return !confirmations.isEmpty()&&Objects.equals(confirmations.get(0).get("actionVersion"),current.get(0));
    }
    public void withdraw(long evidenceId) {WorkflowData.mutationTransaction(jdbc);if(jdbc.update("UPDATE alert_evidence SET dependency_state='WITHDRAWN' WHERE id=? AND dependency_state<>'WITHDRAWN'",evidenceId)!=1)throw AlertWorkflowException.conflict();}
    public List<Map<String,Object>> sanitized(long actor,long patient,long event) {
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> ref:repo.evidence(event)) {
            if(!readable(actor,patient,ref,false))result.add(map("id",ref.get("id"),"restricted",true,"dependencyState","REFERENCE_CHANGED"));
            else {Map<String,Object> clean=new LinkedHashMap<>(ref);clean.put("restricted",false);if(!"WITHDRAWN".equals(ref.get("dependencyState"))&&!valid(actor,patient,ref))clean.put("dependencyState","REFERENCE_CHANGED");result.add(clean);}
        }return result;
    }
    static AlertWorkflowException changed(){return new AlertWorkflowException(409,"REFERENCE_CHANGED","Review the current evidence and dependencies before closing.");}
}
