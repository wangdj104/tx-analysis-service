package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.util.*;

/** Current database authority only: request roles, authorship and assignment alone never grant module access. */
@Service
public class AlertWorkflowAuthorizationService {
    private final JdbcTemplate jdbc;
    private final AlertWorkflowProperties properties;
    private final CarePlanProperties carePlan;
    @Autowired public AlertWorkflowAuthorizationService(JdbcTemplate jdbc,AlertWorkflowProperties properties,CarePlanProperties carePlan){this.jdbc=jdbc;this.properties=properties;this.carePlan=carePlan;}
    public void requireAccount(long actor){activeActor(actor);}
    public void requireActiveActor(long actor){properties.requireEnabled();activeActor(actor);}
    public void requireRead(long actor,long patient){requireModule(actor,patient,"MEASUREMENTS",false);}
    public void requireRecord(long actor,long patient){requireModule(actor,patient,"MEASUREMENTS",true);}
    public void requireOwner(long actor,long patient){if(context(actor,patient)!=actor)throw AlertWorkflowException.denied();}
    public void requireClinical(long actor,long patient){context(actor,patient);if(!doctor(actor,patient))throw AlertWorkflowException.denied();}
    public void requireNursing(long actor,long patient){context(actor,patient);if(!role(actor,"nurse")||!assignedNurse(actor,patient)||!grant(actor,patient,"MEASUREMENTS",true,true))throw AlertWorkflowException.denied();}

    /** Governance is independent of CARE_PLAN; assignment is never itself a clinical-content grant. */
    public void requireAssignmentAdmin(long actor){requireActiveActor(actor);if(!role(actor,"admin"))throw AlertWorkflowException.denied();}
    public void requireAssignmentRead(long actor,long patient){long owner=context(actor,patient);if(owner!=actor&&!doctor(actor,patient))throw AlertWorkflowException.denied();}
    public void requireActivePatient(long patient){properties.requireEnabled();owner(patient);}
    public void requireActiveNurse(long nurse){requireActiveActor(nurse);if(!role(nurse,"nurse"))throw AlertWorkflowException.denied();}
    public void requireNurseGrant(long actor,long patient,long nurse){requireOwner(actor,patient);requireActiveNurse(nurse);if(!assignedNurse(nurse,patient))throw AlertWorkflowException.denied();}

    /** Exact fixed source types, independently module gated; never an arbitrary table/URL lookup. */
    public boolean canReadEvidence(long actor,long patient,String type,long id){
        try{
            requireRead(actor,patient);
            if(id<=0)return false;
            if("HEALTH_MEASUREMENT".equals(type)||"MEASUREMENT".equals(type))
                return count("SELECT COUNT(*) FROM health_measurement WHERE id=? AND patient_id=?",id,patient)>0;
            if("BP_SELF_MONITOR".equals(type))
                return count("SELECT COUNT(*) FROM bp_self_monitor_record WHERE id=? AND patient_id=?",id,patient)>0;
            if("MEDICAL_RECORD".equals(type)){
                requireModule(actor,patient,"MEDICAL",false);
                return count("SELECT COUNT(*) FROM medical_record WHERE id=? AND patient_id=?",id,patient)>0;
            }
            if("CARE_PLAN_ACTION".equals(type)){
                if(!carePlan.isEnabled())return false;
                requireModule(actor,patient,"CARE_PLAN",false);
                if(count("SELECT COUNT(*) FROM care_plan_action a JOIN doctor_care_plan p ON p.id=a.plan_id AND p.patient_id=a.patient_id WHERE a.id=? AND a.patient_id=? AND p.workflow_version=1 AND a.revision_id=p.current_revision_id AND p.lifecycle IN ('ACTIVE','COMPLETED') AND a.status NOT IN ('SUPERSEDED','CANCELLED')",id,patient)==0)return false;
                // A plan receipt cannot smuggle the titles/values of independently restricted sources.
                List<Map<String,Object>> refs=jdbc.query("SELECT v.source_type,v.source_id FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id JOIN care_plan_action a ON a.id=e.action_id AND a.plan_id=e.plan_id WHERE a.id=? AND a.patient_id=? ORDER BY v.id LIMIT 51",(rs,i)->WorkflowData.map("type",rs.getString(1),"id",rs.getLong(2)),id,patient);
                if(refs.size()>50)throw new AlertWorkflowException(409,"REFERENCE_CHECK_LIMIT","Too many evidence references; review them manually.");
                for(Map<String,Object> ref:refs){String source=(String)ref.get("type");if(!Arrays.asList("MEASUREMENT","MEDICAL_RECORD").contains(source)||!canReadEvidence(actor,patient,source,((Number)ref.get("id")).longValue()))return false;}
                return true;
            }
            return false;
        }catch(AlertWorkflowException denied){if(denied.getStatus()==403||denied.getStatus()==503)return false;throw denied;}
    }
    /** 调用方已发现并预算真实锚点；这里只核行动本身的当前权限，嵌套来源由同一预算逐项核对。 */
    boolean canReadPlanAnchor(long actor,long patient,long action,boolean resolving) {
        try{requireRead(actor,patient);if(!carePlan.isEnabled())return false;requireModule(actor,patient,"CARE_PLAN",false);
            String active=resolving?"":" AND a.revision_id=p.current_revision_id AND p.lifecycle IN ('ACTIVE','COMPLETED') AND a.status NOT IN ('SUPERSEDED','CANCELLED')";
            return count("SELECT COUNT(*) FROM care_plan_action a JOIN doctor_care_plan p ON p.id=a.plan_id AND p.patient_id=a.patient_id WHERE a.id=? AND a.patient_id=? AND p.workflow_version=1"+active,action,patient)==1;
        }catch(AlertWorkflowException denied){if(denied.getStatus()==403||denied.getStatus()==503)return false;throw denied;}
    }
    /** A stopped plan is still a reviewable dependency under its independent module authority. */
    public boolean canResolveEvidence(long actor,long patient,String type,long sourceId) {
        if(!"CARE_PLAN_ACTION".equals(type))return canReadEvidence(actor,patient,type,sourceId);
        try {
            requireRead(actor,patient);if(!carePlan.isEnabled())return false;requireModule(actor,patient,"CARE_PLAN",false);
            if(count("SELECT COUNT(*) FROM care_plan_action a JOIN doctor_care_plan p ON p.id=a.plan_id AND p.patient_id=a.patient_id WHERE a.id=? AND a.patient_id=? AND p.workflow_version=1",sourceId,patient)!=1)return false;
            List<Map<String,Object>> refs=jdbc.query("SELECT v.source_type,v.source_id FROM care_plan_evidence v JOIN care_plan_event e ON e.id=v.event_id WHERE e.action_id=? ORDER BY v.id LIMIT 51",(rs,i)->WorkflowData.map("type",rs.getString(1),"id",rs.getLong(2)),sourceId);
            if(refs.size()>50)throw new AlertWorkflowException(409,"REFERENCE_CHECK_LIMIT","Too many evidence references; review them manually.");
            for(Map<String,Object> ref:refs)if(!canReadEvidence(actor,patient,(String)ref.get("type"),((Number)ref.get("id")).longValue()))return false;
            return true;
        }catch(AlertWorkflowException denied){if(denied.getStatus()==403||denied.getStatus()==503)return false;throw denied;}
    }
    /** Owner selects personal work; owner/current doctor routes clinical work. Every candidate is revalidated. */
    public List<Map<String,Object>> eligibleAssignees(long actor,long patient,String mode){
        if(!Arrays.asList("PERSONAL","CLINICAL").contains(mode))throw AlertWorkflowException.invalid("workflowMode is not supported.");
        if("PERSONAL".equals(mode))requireOwner(actor,patient);
        else{long owner=context(actor,patient);if(owner!=actor&&!doctor(actor,patient))throw AlertWorkflowException.denied();}
        List<Map<String,Object>> candidates=jdbc.query("SELECT u.id,u.real_name,u.username FROM sys_user u WHERE u.id=(SELECT user_id FROM patient WHERE id=?) OR EXISTS(SELECT 1 FROM doctor_patient_assignment a WHERE a.doctor_user_id=u.id AND a.patient_id=?) OR EXISTS(SELECT 1 FROM care_access_grant g WHERE g.grantee_user_id=u.id AND g.patient_id=?) OR EXISTS(SELECT 1 FROM care_nurse_assignment n WHERE n.nurse_user_id=u.id AND n.patient_id=?) ORDER BY u.id",(rs,i)->WorkflowData.map("userId",rs.getLong(1),"displayName",serverName(rs.getLong(1),rs.getString(2),rs.getString(3))),patient,patient,patient,patient);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:candidates){long id=((Number)row.get("userId")).longValue();try{requireRecord(id,patient);if("CLINICAL".equals(mode)&&!doctor(id,patient))requireNursing(id,patient);row.put("role",actorRole(id,patient));out.add(row);}catch(AlertWorkflowException denied){if(denied.getStatus()!=403)throw denied;}}
        return out;
    }
    /** Snapshot contains actual server identity, never client-declared role or relation. */
    public Map<String,Object> actorSnapshot(long actor,long patient){
        requireRead(actor,patient);String role=actorRole(actor,patient);
        String name=jdbc.queryForObject("SELECT real_name,username FROM sys_user WHERE id=?",(rs,i)->serverName(actor,rs.getString(1),rs.getString(2)),actor);
        return WorkflowData.map("actorId",actor,"actorName",name,"actorRole",role,"actorRelation",owner(patient)==actor?"OWNER":role);
    }
    /** Same bounded server identity for events and assignees, including legacy blank profile names. */
    private String serverName(long actor,String realName,String username){
        String name=trimProfileName(realName);if(name.isEmpty())name=trimProfileName(username);if(name.isEmpty())name="User "+actor;
        int size=name.codePointCount(0,name.length());return trimProfileName(size>100?name.substring(0,name.offsetByCodePoints(0,100)):name);
    }
    private String trimProfileName(String value){
        if(value==null)return "";int start=0,end=value.length();
        while(start<end){int c=value.codePointAt(start);if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c))break;start+=Character.charCount(c);}
        while(end>start){int c=value.codePointBefore(end);if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c))break;end-=Character.charCount(c);}
        return value.substring(start,end);
    }
    private void requireModule(long actor,long patient,String module,boolean write){
        long owner=context(actor,patient);
        if(owner==actor||doctor(actor,patient))return;
        boolean nurse=nurseIdentity(actor,patient);
        if(nurse){if(!role(actor,"nurse")||!assignedNurse(actor,patient)||!grant(actor,patient,module,write,true))throw AlertWorkflowException.denied();}
        else if(!role(actor,"family")||!grant(actor,patient,module,write,false))throw AlertWorkflowException.denied();
    }
    private long context(long actor,long patient){properties.requireEnabled();activeActor(actor);return owner(patient);}
    private void activeActor(long actor){if(actor<=0||count("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",actor)==0||count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor)==0)throw AlertWorkflowException.denied();}
    private long owner(long patient){List<Long> rows=jdbc.query("SELECT user_id FROM patient WHERE id=? AND status=1 AND COALESCE(deleted,0)=0",(rs,i)->rs.getLong(1),patient);if(patient<=0||rows.isEmpty()||rows.get(0)<=0)throw AlertWorkflowException.denied();return rows.get(0);}
    private boolean role(long actor,String role){return count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code=? AND r.status=1 AND COALESCE(r.deleted,0)=0",actor,role)>0;}
    private boolean doctor(long actor,long patient){return role(actor,"doctor")&&count("SELECT COUNT(*) FROM doctor_patient_assignment WHERE doctor_user_id=? AND patient_id=? AND status='ACTIVE'",actor,patient)>0;}
    private boolean nurseIdentity(long actor,long patient){return count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code='nurse'",actor)>0||count("SELECT COUNT(*) FROM care_nurse_assignment WHERE nurse_user_id=? AND patient_id=?",actor,patient)>0||count("SELECT COUNT(*) FROM care_access_grant WHERE grantee_user_id=? AND patient_id=? AND grantee_role='NURSE'",actor,patient)>0;}
    private boolean assignedNurse(long actor,long patient){return jdbc.query("SELECT expires_at FROM care_nurse_assignment WHERE nurse_user_id=? AND patient_id=? AND status='ACTIVE' AND revoked_at IS NULL",(rs,i)->unexpired(rs.getTimestamp(1,WorkflowData.utc())),actor,patient).contains(Boolean.TRUE);}
    // Existing grants store legacy database-local DATETIME; preserve their CURRENT_TIMESTAMP comparison.
    private boolean grant(long actor,long patient,String module,boolean write,boolean nurse){
        return jdbc.query("SELECT grantee_role,access_level,visible_modules,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired FROM care_access_grant WHERE grantee_user_id=? AND patient_id=? AND status='ACTIVE'",(rs,i)->{
            String relation=rs.getString(1),level=rs.getString(2),modules=rs.getString(3);
            return (nurse?Arrays.asList("NURSE","FAMILY","GUARDIAN"):Arrays.asList("FAMILY","GUARDIAN")).contains(relation)&&Arrays.asList("READ","WRITE","PROXY").contains(level)&&(!write||Arrays.asList("WRITE","PROXY").contains(level))&&includesModule(modules,module)&&rs.getBoolean("unexpired");
        },actor,patient).contains(Boolean.TRUE);
    }
    private boolean includesModule(String modules,String module){if(modules==null)return false;for(String value:modules.split(","))if(module.equalsIgnoreCase(value.trim()))return true;return false;}
    private boolean unexpired(Timestamp expiry){return expiry==null||expiry.toInstant().isAfter(properties.now());}
    private String actorRole(long actor,long patient){if(doctor(actor,patient))return "DOCTOR";if(owner(patient)==actor)return "PATIENT";return nurseIdentity(actor,patient)?"NURSE":"FAMILY";}
    private int count(String sql,Object...args){return jdbc.queryForObject(sql,Integer.class,args);}
}
