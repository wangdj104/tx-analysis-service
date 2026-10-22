package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.*;
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
    @org.springframework.beans.factory.annotation.Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @org.springframework.beans.factory.annotation.Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions medicalReferences;
    private final Object graphKey=new Object();
    private final JdbcTemplate jdbc;private final AlertWorkflowRepository repo;private final AlertWorkflowAuthorizationService auth;private final AlertWorkflowProperties properties;
    public AlertWorkflowEvidenceService(JdbcTemplate jdbc,AlertWorkflowRepository repo,AlertWorkflowAuthorizationService auth,AlertWorkflowProperties properties) {this.jdbc=jdbc;this.repo=repo;this.auth=auth;this.properties=properties;}

    public List<Map<String,Object>> dependencies(long alert) {return dependencies(alert,50);}
    private List<Map<String,Object>> dependencies(long alert,int remaining) {
        List<Map<String,Object>> rows=jdbc.query("SELECT v.id,v.event_id,v.source_type,v.source_id,v.source_revision,v.dependency_version,v.dependency_state FROM alert_evidence v JOIN alert_event e ON e.id=v.event_id WHERE e.alert_id=? AND v.dependency_state<>'WITHDRAWN' ORDER BY v.id LIMIT ?",
                (rs,i)->map("id",rs.getLong("id"),"eventId",rs.getLong("event_id"),"sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id"),"sourceRevision",nullableId(rs,"source_revision"),"dependencyVersion",nullableId(rs,"dependency_version"),"dependencyState",rs.getString("dependency_state")),alert,remaining+1);
        if(rows.size()>remaining)throw limit();return rows;
    }
    // 列表只在完整聚合边界分页；单个图超限仍报错，不把截断依赖视为有效。
    static final class ReadBudget {final Graph graph=new Graph();int groups;}
    List<Map<String,Object>> nextReferences(long alert,ReadBudget budget) {
        try {List<Map<String,Object>> refs=dependencies(alert,50-budget.graph.rows);discover(budget.graph,refs);budget.groups++;return refs;}
        catch(AlertWorkflowException exceeded){if(!"REFERENCE_CHECK_LIMIT".equals(exceeded.getErrorCode())||budget.groups==0)throw exceeded;return null;}
    }
    /** 一次调用共用实际锚点行和唯一来源预算；发现、排序、加锁、重验，不在锁尾补锁。 */
    public boolean hasLockedGraph(){return org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(graphKey);}
    public Locks lock(Map<String,Object> alert,List<Map<String,Object>> requested){return lock(alert,requested,null);}
    public Locks lock(Map<String,Object> alert,List<Map<String,Object>> requested,Long actor) {
        WorkflowData.mutationTransaction(jdbc);if(requested.size()>5)throw limit();
        List<Map<String,Object>> refs=dependencies(id(alert.get("id")));
        List<Map<String,Object>> all=new ArrayList<>(refs);for(Map<String,Object> requestedRef:requested){Map<String,Object> fresh=new LinkedHashMap<>(requestedRef);fresh.put("newAnchor",true);all.add(fresh);}all.add(map("sourceType",alert.get("sourceType"),"sourceId",alert.get("sourceId")));
        Graph graph=discover(all);bindGraph(graph);if(actor!=null)requireReadable(actor,id(alert.get("patientId")),requested);lockReferences(all);
        return new Locks(id(alert.get("id")),json(refs),graph.signature(),all);
    }
    public void verify(Locks locks) {
        if(!locks.references.equals(json(dependencies(locks.alert)))||!locks.graph.equals(discover(locks.all).signature()))throw AlertWorkflowException.conflict();
    }
    void lockReferences(List<Map<String,Object>> references) {
        WorkflowData.mutationTransaction(jdbc);
        Graph graph=(Graph)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(graphKey);
        if(graph==null){graph=discover(references);bindGraph(graph);}
        else if(!graph.signature().equals(discover(references).signature()))throw AlertWorkflowException.conflict();
        lockGraph(graph,references);
    }
    private void lockGraph(Graph graph,List<Map<String,Object>> references) {
        for(long plan:graph.plans)jdbc.queryForList("SELECT id FROM doctor_care_plan WHERE id=? FOR UPDATE",Long.class,plan);
        if(!graph.signature().equals(discover(references).signature()))throw AlertWorkflowException.conflict();
        for(Map<String,Object> source:graph.sources.values()) {
            String type=(String)source.get("sourceType");long sourceId=id(source.get("sourceId"));
            if(SOURCE_TYPES.contains(type))repo.lockSource(type,sourceId);
            else if("MEDICAL_RECORD".equals(type))jdbc.queryForList("SELECT id FROM medical_record WHERE id=? FOR UPDATE",Long.class,sourceId);
        }
    }
    private void bindGraph(Graph graph) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(graphKey))throw AlertWorkflowException.conflict();
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(graphKey,graph);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void suspend(){org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(graphKey);}
            @Override public void resume(){org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(graphKey,graph);}
            @Override public void afterCompletion(int status){org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(graphKey);}
        });
    }
    private Graph discover(List<Map<String,Object>> references) {Graph graph=new Graph();discover(graph,references);return graph;}
    private void discover(Graph graph,List<Map<String,Object>> references) {
        for(Map<String,Object> ref:references)if(ref.containsKey("id")||Boolean.TRUE.equals(ref.get("newAnchor")))graph.rows++;
        if(graph.rows>50)throw limit();
        for(Map<String,Object> ref:references) {
            String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));
            if("CARE_PLAN_ACTION".equals(type)) {
                if(graph.nested.containsKey(source))continue;
                List<Map<String,Object>> parentRows=jdbc.query("SELECT a.plan_id,a.patient_id,p.patient_id AS plan_patient FROM care_plan_action a LEFT JOIN doctor_care_plan p ON p.id=a.plan_id WHERE a.id=?",(r,n)->map("plan",nullableId(r,"plan_id"),"patient",nullableId(r,"patient_id"),"planPatient",nullableId(r,"plan_patient")),source);
                if(parentRows.size()!=1||!Objects.equals(parentRows.get(0).get("patient"),parentRows.get(0).get("planPatient")))throw AlertWorkflowException.conflict();
                Map<String,Object> parent=parentRows.get(0);List<Long> parents=Collections.singletonList(id(parent.get("plan")));graph.planLinks.put(source,parents);graph.plans.addAll(parents);
                List<Map<String,Object>> rows=nested(source,50-graph.rows);
                for(Map<String,Object> row:rows)if(!Objects.equals(row.get("patientId"),parent.get("patient"))||!Objects.equals(row.get("planId"),parent.get("plan")))throw AlertWorkflowException.conflict();
                graph.rows+=rows.size();graph.nested.put(source,rows);
                for(Map<String,Object> row:rows)putSource(graph.sources,row);
            }else putSource(graph.sources,ref);
            if(graph.sources.size()+graph.nested.size()>50)throw limit();
        }
    }
    private void putSource(Map<String,Map<String,Object>> sources,Map<String,Object> ref) {String type=(String)ref.get("sourceType");if("MEASUREMENT".equals(type))type="HEALTH_MEASUREMENT";if(!SOURCE_TYPES.contains(type)&&!"MEDICAL_RECORD".equals(type))throw AlertWorkflowException.conflict();sources.put(type+":"+String.format(Locale.ROOT,"%020d",id(ref.get("sourceId"))),map("sourceType",type,"sourceId",ref.get("sourceId")));}
    private List<Map<String,Object>> nested(long action,int remaining) {
        List<Map<String,Object>> rows=jdbc.query("SELECT v.id,v.event_id,v.source_type,v.source_id,e.patient_id,e.plan_id FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id WHERE e.action_id=? ORDER BY v.id LIMIT ?",(rs,i)->map("id",rs.getLong("id"),"eventId",rs.getLong("event_id"),"sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id"),"patientId",rs.getLong("patient_id"),"planId",rs.getLong("plan_id")),action,remaining+1);
        if(rows.size()>remaining)throw limit();return rows;
    }
    private List<Map<String,Object>> nested(long action) {
        Graph current=(Graph)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(graphKey);
        if(current!=null){List<Map<String,Object>> rows=current.nested.get(action);if(rows==null)throw AlertWorkflowException.conflict();return rows;}
        return nested(action,50);
    }
    String dependencyLinks(List<Map<String,Object>> refs){return discover(refs).signature();}
    List<Map<String,Object>> receiptActions(String type,long source) {
        String nativeType="HEALTH_MEASUREMENT".equals(type)?"MEASUREMENT":type;
        List<Map<String,Object>> rows=jdbc.query("SELECT DISTINCT a.id FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id JOIN care_plan_action a ON a.id=e.action_id WHERE v.source_type=? AND v.source_id=? ORDER BY a.id LIMIT 51",(rs,i)->map("sourceType","CARE_PLAN_ACTION","sourceId",rs.getLong(1)),nativeType,source);
        if(rows.size()>50)throw limit();return rows;
    }
    boolean referencesSource(Map<String,Object> ref,String type,long source) {
        if(type.equals(ref.get("sourceType"))&&id(ref.get("sourceId"))==source)return true;
        if("CARE_PLAN_ACTION".equals(ref.get("sourceType")))for(Map<String,Object> nested:nested(id(ref.get("sourceId")))) {
            String nestedType="MEASUREMENT".equals(nested.get("sourceType"))?"HEALTH_MEASUREMENT":(String)nested.get("sourceType");
            if(type.equals(nestedType)&&id(nested.get("sourceId"))==source)return true;
        }return false;
    }
    private static final class Graph {
        int rows;final SortedSet<Long> plans=new TreeSet<>();final SortedMap<String,Map<String,Object>> sources=new TreeMap<>();
        final Map<Long,List<Long>> planLinks=new TreeMap<>();final Map<Long,List<Map<String,Object>>> nested=new TreeMap<>();
        String signature(){return json(map("plans",planLinks,"sources",sources,"nested",nested,"rows",rows));}
    }
    public static final class Locks {private final long alert;private final String references,graph;private final List<Map<String,Object>> all;private Locks(long alert,String references,String graph,List<Map<String,Object>> all){this.alert=alert;this.references=references;this.graph=graph;this.all=all;}}
    private static AlertWorkflowException limit(){return new AlertWorkflowException(409,"REFERENCE_CHECK_LIMIT","证据引用过多，请人工核对。");}

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
        return readable(actor,patient,ref,resolving,(Graph)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(graphKey));
    }
    private boolean readable(long actor,long patient,Map<String,Object> ref,boolean resolving,Graph graph) {
        String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));
        if("CARE_PLAN_ACTION".equals(type)&&graph!=null) {
            if(!auth.canReadPlanAnchor(actor,patient,source,resolving))return false;
            for(Map<String,Object> row:graph.nested.getOrDefault(source,Collections.emptyList()))if(id(row.get("patientId"))!=patient||!auth.canReadEvidence(actor,patient,(String)row.get("sourceType"),id(row.get("sourceId"))))return false;
            return true;
        }
        if(auth.canReadEvidence(actor,patient,type,source))return true;
        if(!resolving||!"CARE_PLAN_ACTION".equals(type))return false;
        return auth.canResolveEvidence(actor,patient,type,source);
    }

    public void attach(long actor,long patient,long event,List<Map<String,Object>> references) {
        requireReadable(actor,patient,references);
        Set<Long> medical=new LinkedHashSet<>();List<Map<String,Object>> actions=new ArrayList<>();
        for(Map<String,Object> ref:references) {
            if("MEDICAL_RECORD".equals(ref.get("sourceType")))medical.add(id(ref.get("sourceId")));
            else if("CARE_PLAN_ACTION".equals(ref.get("sourceType")))actions.add(ref);
        }
        Checks actionChecks=actions.isEmpty()?null:checks(actor,patient,actions);
        if(actionChecks!=null&&!actionChecks.careAnchors.isEmpty())medicalReferences.requireCurrent(Kind.CARE_PLAN_EVIDENCE,actionChecks.careAnchors,medicalValidity.open());
        try(AlertEvidenceFrame frame=medical.isEmpty()?null:medicalReferences.prepareAlertEvidence(event,patient,actor,medical,medicalValidity.open())) {
            for(Map<String,Object> ref:references) {
                String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));Long revision=null,version=null;
                if(SOURCE_TYPES.contains(type)) {
                    Map<String,Object> row=repo.readSource(actor,patient,type,source);if(!"ACTIVE".equals(row.get("sourceStatus")))throw changed();
                    ensureTracked(actor,row);revision=id(row.get("sourceRevision"));
                }else if("CARE_PLAN_ACTION".equals(type)) {
                    // All attachment paths retain the action's correction obligation, using this request's fixed medical set.
                    if(!freshPlanConfirmation(actor,patient,source,actionChecks))throw changed();
                    version=jdbc.queryForObject("SELECT lock_version FROM care_plan_action WHERE id=?",Long.class,source);
                }
                if("MEDICAL_RECORD".equals(type))frame.insert(source);
                else repo.addEvidence(event,type,source,revision,version,"VALID");
            }
            if(frame!=null)frame.complete();
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
    /** 只在当前调用内合批状态；权限仍逐次核对，不能把只读提示当成执行许可。 */
    static final class Checks {
        final List<Map<String,Object>> references;final Graph graph;Map<Long,String> medical,care;
        List<Anchor> medicalAnchors=Collections.emptyList(),careAnchors=Collections.emptyList();
        Checks(List<Map<String,Object>> references,Graph graph){this.references=references;this.graph=graph;}
    }
    Checks checks(long actor,long patient,List<Map<String,Object>> references){return checksForGroups(actor,patient,Collections.singletonList(references)).get(0);}
    List<Checks> checksForGroups(long actor,long patient,List<List<Map<String,Object>>> groups) {
        List<Checks> result=new ArrayList<>();List<Anchor> direct=new ArrayList<>(),nestedMedical=new ArrayList<>();
        List<Map<String,Object>> all=new ArrayList<>();for(List<Map<String,Object>> refs:groups)all.addAll(refs);
        Graph locked=(Graph)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(graphKey);Graph graph=locked==null?discover(all):locked;
        for(List<Map<String,Object>> refs:groups){Checks check=new Checks(refs,graph);result.add(check);
            for(Map<String,Object> ref:refs)if(readable(actor,patient,ref,false,check.graph)){
                if("MEDICAL_RECORD".equals(ref.get("sourceType")))direct.add(new Anchor(id(ref.get("id")),patient,id(ref.get("sourceId")),null));
                if("CARE_PLAN_ACTION".equals(ref.get("sourceType")))for(Map<String,Object> row:check.graph.nested.get(id(ref.get("sourceId"))))if("MEDICAL_RECORD".equals(row.get("sourceType")))nestedMedical.add(new Anchor(id(row.get("id")),patient,id(row.get("sourceId")),null));
            }
        }
        Map<Kind,List<Anchor>> selected=new LinkedHashMap<>();if(!direct.isEmpty())selected.put(Kind.ALERT_EVIDENCE,direct);if(!nestedMedical.isEmpty())selected.put(Kind.CARE_PLAN_EVIDENCE,nestedMedical);
        Map<Long,String> medical=new LinkedHashMap<>(),care=new LinkedHashMap<>();
        if(!selected.isEmpty()) {
            ReferenceFrame frame=medicalReferences.readSnapshots(selected,medicalValidity.open());
            for(Map.Entry<Long,ReferenceSnapshot> row:frame.snapshots.getOrDefault(Kind.ALERT_EVIDENCE,Collections.emptyMap()).entrySet())medical.put(row.getKey(),row.getValue().status);
            for(Map.Entry<Long,ReferenceSnapshot> row:frame.snapshots.getOrDefault(Kind.CARE_PLAN_EVIDENCE,Collections.emptyMap()).entrySet())care.put(row.getKey(),row.getValue().status);
        }
        // The common reader validates every duplicate expectation before work is deduplicated for strict proofs.
        List<Anchor> medicalAnchors=distinctAnchors(direct),careAnchors=distinctAnchors(nestedMedical);
        for(Checks check:result){check.medical=medical;check.care=care;check.medicalAnchors=medicalAnchors;check.careAnchors=careAnchors;}return result;
    }
    private List<Anchor> distinctAnchors(List<Anchor> anchors){Map<Long,Anchor> unique=new LinkedHashMap<>();for(Anchor anchor:anchors)unique.put(anchor.id,anchor);return Collections.unmodifiableList(new ArrayList<>(unique.values()));}
    public boolean valid(long actor,long patient,Map<String,Object> ref){return valid(actor,patient,ref,checks(actor,patient,Collections.singletonList(ref)));}
    boolean valid(long actor,long patient,Map<String,Object> ref,Checks checks) {
        if(!"VALID".equals(ref.get("dependencyState"))||!readable(actor,patient,ref,false,checks.graph))return false;
        String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));
        if(SOURCE_TYPES.contains(type)) {Map<String,Object> row=repo.readSource(actor,patient,type,source);return "ACTIVE".equals(row.get("sourceStatus"))&&Objects.equals(row.get("sourceRevision"),ref.get("sourceRevision"));}
        if("CARE_PLAN_ACTION".equals(type)) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_action WHERE id=? AND status='CONFIRMED' AND lock_version=?",Integer.class,source,ref.get("dependencyVersion"))!=1)return false;
            return freshPlanConfirmation(actor,patient,source,checks);
        }
        if("MEDICAL_RECORD".equals(type))return org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.fresh(checks.medical.get(id(ref.get("id"))));
        return true;
    }
    public void requireComplete(long actor,Map<String,Object> alert) {
        if(!hasLockedGraph())throw AlertWorkflowException.conflict();
        Checks checks=checks(actor,id(alert.get("patientId")),dependencies(id(alert.get("id"))));
        requireComplete(actor,alert,checks);
        if(!checks.medicalAnchors.isEmpty())medicalReferences.requireCurrent(Kind.ALERT_EVIDENCE,checks.medicalAnchors,medicalValidity.open());
        if(!checks.careAnchors.isEmpty())medicalReferences.requireCurrent(Kind.CARE_PLAN_EVIDENCE,checks.careAnchors,medicalValidity.open());
    }
    // 此内部重载供只读可用性提示；命令入口必须调用上方强制锁图的公开方法。
    void requireComplete(long actor,Map<String,Object> alert,Checks checks) {
        long patient=id(alert.get("patientId"));List<Map<String,Object>> references=checks.references;
        for(Map<String,Object> ref:references)if(!valid(actor,patient,ref,checks))throw changed();
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
        // 兼容已有内部调用；仍执行完整锚点检查，身份不得由调用方引用替代。
        Long patient=jdbc.queryForObject("SELECT patient_id FROM care_plan_action WHERE id=?",Long.class,id(reference.get("sourceId")));
        if(patient==null||!freshPlanConfirmation(null,patient,id(reference.get("sourceId"))))throw changed();
    }
    private boolean freshPlanConfirmation(Long actor,long patient,long action) {return freshPlanConfirmation(actor,patient,action,null);}
    private boolean freshPlanConfirmation(Long actor,long patient,long action,Checks checks) {
        List<org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.Anchor> medical=new ArrayList<>();
        for(Map<String,Object> ref:checks==null?nested(action):checks.graph.nested.get(action)) {
            if(id(ref.get("patientId"))!=patient)return false;
            String type=(String)ref.get("sourceType");long source=id(ref.get("sourceId"));
            if(actor!=null&&!auth.canReadEvidence(actor,patient,"MEASUREMENT".equals(type)?"HEALTH_MEASUREMENT":type,source))return false;
            if("MEDICAL_RECORD".equals(type))medical.add(new org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.Anchor(id(ref.get("id")),patient,source,null));
        }
        if(!medical.isEmpty()){Map<Long,String> statuses=checks==null?medicalReferences.statuses(org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.Kind.CARE_PLAN_EVIDENCE,medical,medicalValidity.open()):checks.care;for(org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.Anchor anchor:medical)if(!org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions.fresh(statuses.get(anchor.id)))return false;}
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
    List<Map<String,Object>> eventReferences(List<Long> events) {
        if(events.isEmpty())return Collections.emptyList();if(events.size()>100)throw limit();
        String marks=String.join(",",Collections.nCopies(events.size(),"?"));
        List<Map<String,Object>> rows=jdbc.query("SELECT id,event_id,source_type,source_id,source_revision,dependency_version,dependency_state FROM alert_evidence WHERE event_id IN ("+marks+") ORDER BY id LIMIT 51",(rs,i)->map("id",rs.getLong("id"),"eventId",rs.getLong("event_id"),"sourceType",rs.getString("source_type"),"sourceId",rs.getLong("source_id"),"sourceRevision",nullableId(rs,"source_revision"),"dependencyVersion",nullableId(rs,"dependency_version"),"dependencyState",rs.getString("dependency_state")),events.toArray());
        if(rows.size()>50)throw limit();return rows;
    }
    public List<Map<String,Object>> sanitized(long actor,long patient,long event) {
        List<Map<String,Object>> refs=eventReferences(Collections.singletonList(event));return sanitized(actor,patient,refs,checks(actor,patient,refs));
    }
    List<Map<String,Object>> sanitized(long actor,long patient,List<Map<String,Object>> refs,Checks checks) {
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> ref:refs) {
            if(!readable(actor,patient,ref,false,checks.graph))result.add(map("id",ref.get("id"),"restricted",true,"dependencyState","REFERENCE_CHANGED"));
            else {Map<String,Object> clean=new LinkedHashMap<>(ref);clean.put("restricted",false);if("MEDICAL_RECORD".equals(ref.get("sourceType")))clean.put("sourceStatus",checks.medical.get(id(ref.get("id"))));if(!"WITHDRAWN".equals(ref.get("dependencyState"))&&!valid(actor,patient,ref,checks))clean.put("dependencyState","REFERENCE_CHANGED");result.add(clean);}
        }return result;
    }
    static AlertWorkflowException changed(){return new AlertWorkflowException(409,"REFERENCE_CHANGED","Review the current evidence and dependencies before closing.");}
}
