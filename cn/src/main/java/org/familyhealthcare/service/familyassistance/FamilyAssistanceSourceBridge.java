package org.familyhealthcare.service.familyassistance;
import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.familyhealthcare.service.familyassistance.FamilyAssistanceRepository.*;
/** 原生预约保持自己的状态；最小指纹变更仅使协调中的接手和处理证据失效。 */
@Service public class FamilyAssistanceSourceBridge {
    private final JdbcTemplate jdbc;
    private final org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge visitFollowUp;
    private final FamilyAssistanceRepository repo;
    private final FamilyAssistanceEventStore events;
    private final FamilyAssistanceProperties properties;
    private final HouseholdWorkflowSchema schema;
    public FamilyAssistanceSourceBridge(JdbcTemplate jdbc,FamilyAssistanceRepository repo,FamilyAssistanceEventStore events,FamilyAssistanceProperties properties,HouseholdWorkflowSchema schema,org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge visitFollowUp) {
        this.visitFollowUp=java.util.Objects.requireNonNull(visitFollowUp);
        this.jdbc=jdbc;
        this.repo=repo;
        this.events=events;
        this.properties=properties;
        this.schema=schema;
    }
    public void appointmentChangedLocked(long patientId,long appointmentId,String sourceKind) {
        mutationTransaction(jdbc);
        if(!storageAvailable())return;
        Map<String,Object> s=repo.source(sourceKind,appointmentId);
        if(s==null||id(s.get("patient_id"))!=patientId)throw FamilyAssistanceAuthorizationService.denied();
        if(s.get("family_assistance_id")==null)return;
        Map<String,Object> a=repo.one("SELECT * FROM family_assistance WHERE id=? FOR UPDATE",s.get("family_assistance_id"));
        repo.requireIdentity(a,patientId,sourceKind,appointmentId);
        if(!"ACCOMPANIMENT".equals(a.get("request_kind")))return;
        String fp=(String)repo.minimal(sourceKind,s).get("fingerprint");
        if(Objects.equals(fp,a.get("source_fingerprint")))return;
        long version=id(a.get("lock_version"));
        if(version>=9007199254740991L)throw conflict("VERSION_OVERFLOW");
        if(FamilyAssistanceService.terminal(a)) {
            repo.update("UPDATE family_assistance SET source_fingerprint=?,source_changed=1,lock_version=lock_version+1,updated_at=? WHERE id=?",fp,properties.now(),a.get("id"));
        }
        else repo.update("UPDATE family_assistance SET state='REQUESTED',source_fingerprint=?,source_changed=1,accepted_by=NULL,accepted_at=NULL,accepted_fingerprint=NULL,handled_by=NULL,handled_at=NULL,confirmed_at=NULL,lock_version=lock_version+1,updated_at=? WHERE id=?",fp,properties.now(),a.get("id"));
        Long actor=org.familyhealthcare.util.CurrentUserUtil.getCurrentUserId();
        if(actor==null)throw FamilyAssistanceAuthorizationService.denied();
        repo.requireBoundaryWrite(actor,patientId,"ACCOMPANIMENT");
        events.append(actor,repo.assistance(id(a.get("id"))),"SOURCE_CHANGED",null,map("previousFingerprint",a.get("source_fingerprint"),"fingerprint",fp),null,null);
    }
    /** 调用方已持患者父锁；只发现身份，不把发起人或处理者当成来源授权。 */
    public Collection<Long> nativeAppointmentSubjects(long patient,Long appointment) {
        SortedSet<Long> users=new TreeSet<>();
        if(appointment==null||!storageAvailable())return users;
        Map<String,Object> s=repo.source("CARE_APPOINTMENT",appointment);
        if(s==null||id(s.get("patient_id"))!=patient)throw FamilyAssistanceAuthorizationService.denied();
        if(s.get("family_assistance_id")!=null) {
            Map<String,Object> a=repo.assistance(id(s.get("family_assistance_id")));
            repo.requireIdentity(a,patient,"CARE_APPOINTMENT",appointment);
            for(String key:Arrays.asList("originator_id","assigned_user_id","accepted_by","handled_by"))if(a.get(key)!=null)users.add(id(a.get(key)));
        }
        return users;
    }
    public void validateNativeAppointmentLocked(HouseholdAuthorityLocks.LockedAuthority token,long actor,long patient,long appointment) {
        if(!storageAvailable())return;
        Map<String,Object> s=repo.source("CARE_APPOINTMENT",appointment);
        if(s==null||id(s.get("patient_id"))!=patient)throw FamilyAssistanceAuthorizationService.denied();
        Map<String,Collection<Long>> selected=new LinkedHashMap<>();
        selected.put("care_appointment",Collections.singleton(appointment));
        if(s.get("family_assistance_id")!=null) {
            Map<String,Object> a=repo.one("SELECT * FROM family_assistance WHERE id=? FOR UPDATE",s.get("family_assistance_id"));
            repo.requireIdentity(a,patient,"CARE_APPOINTMENT",appointment);
            selected.put("family_assistance",Collections.singleton(id(a.get("id"))));
        }
        repo.requireBoundarySelected(token,patient,actor,selected);
        repo.requireBoundaryWrite(actor,patient,"ACCOMPANIMENT");
    }
    /** 未跟踪的旧入口仍可用；父锁把收养和旧写串行化，不能只信预先读取的 marker。 */
    public void requireLegacyItemWrite(long actor,long patient,long item) {
        mutationTransaction(jdbc);
        visitFollowUp.requireLegacyWrite(patient,org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.SourceKind.CARE_ITEM_APPOINTMENT,item);
        if(!storageAvailable())return;
        repo.lockBoundarySubjects(patient,Collections.singleton(actor));
        Map<String,Object> row=repo.one("SELECT * FROM care_item WHERE id=? FOR UPDATE",item);
        if(row==null||id(row.get("patient_id"))!=patient)throw FamilyAssistanceAuthorizationService.denied();
        if(row.get("family_assistance_id")!=null||id(row.get("family_workflow_version"))!=0)throw conflict("TYPED_ASSISTANCE_REQUIRED");
    }
    public void requireLegacyAppointmentWrite(long patient,long appointment) {
        visitFollowUp.requireLegacyWrite(patient,org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.SourceKind.CARE_APPOINTMENT,appointment);
    }
    /** 旧读取只附带最小预约旁表事实，不改变原有完整来源授权。 */
    public boolean appointmentReadStorageAvailable() { return visitFollowUp.storageAvailable(); }
    public boolean storageAvailable() {
        HouseholdWorkflowSchema.StorageReadiness r=schema.inspectStorageMetadata();
        if(r.getStatus()==HouseholdWorkflowSchema.StorageStatus.ABSENT&&!properties.isEnabled())return false;
        r.requireStructurallyReady();
        return true;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public boolean admitLegacyNotification(long patient,long item) {
        if(visitFollowUp.isTracked(org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.SourceKind.CARE_ITEM_APPOINTMENT,item))return false;
        if(!storageAvailable())return true;
        Map<String,Object> s=repo.source("CARE_ITEM",item);
        if(s==null||id(s.get("patient_id"))!=patient)return false;
        Map<String,Object> p=repo.one("SELECT user_id FROM patient WHERE id=?",patient);
        if(p==null)return false;
        repo.lockBoundarySubjects(patient,Collections.singleton(id(p.get("user_id"))));
        if(visitFollowUp.isTracked(org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.SourceKind.CARE_ITEM_APPOINTMENT,item))return false;
        s=repo.one("SELECT family_workflow_version,family_assistance_id FROM care_item WHERE id=? AND patient_id=? FOR UPDATE",item,patient);
        return s!=null&&id(s.get("family_workflow_version"))==0&&s.get("family_assistance_id")==null;
    }
    public boolean trackedItem(long item) {
        if(!storageAvailable())return false;
        Map<String,Object> s=repo.source("CARE_ITEM",item);
        return s!=null&&(s.get("family_assistance_id")!=null||id(s.get("family_workflow_version"))!=0);
    }
    /** 候选身份只限当前患者关系，不读正文；候选不是权限，真正发送前逐人短 admission。 */
    public List<Long> legacyAppointmentRecipients(long patient,Long doctor) {
        SortedSet<Long> recipients = new TreeSet<>();
        if(doctor!=null)recipients.add(doctor);
        for(String sql : Arrays.asList( "SELECT user_id AS recipient FROM patient WHERE id=? LIMIT 1", "SELECT user_id AS recipient FROM care_member WHERE patient_id=? ORDER BY user_id LIMIT 51", "SELECT grantee_user_id AS recipient FROM care_access_grant WHERE patient_id=? ORDER BY grantee_user_id LIMIT 51", "SELECT doctor_user_id AS recipient FROM doctor_patient_assignment WHERE patient_id=? ORDER BY doctor_user_id LIMIT 51")) {
            List<Map<String,Object>> rows = repo.rows(sql,patient);
            if(rows.size()>50)throw conflict("RECIPIENT_LIMIT");
            for(Map<String,Object> row:rows)if(row.get("recipient")!=null)recipients.add(id(row.get("recipient")));
            if(recipients.size()>50)throw conflict("RECIPIENT_LIMIT");
        }
        return new ArrayList<>(recipients);
    }
    /** 旧原生通知只借用短有序锁读取门，不写 B 业务行，也没有 SYSTEM 或人类账号替身。 */
    public boolean admitLegacyAppointmentNotification(long patient,long appointment,Long actor,long recipient,Long doctor,java.time.LocalDateTime start,String status) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return false;
        boolean modern = storageAvailable();
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return Boolean.TRUE.equals(tx.execute(transaction -> {
            if (repo.one("SELECT id FROM patient WHERE id=? FOR UPDATE",patient)==null) return false;
            SortedSet<Long> users = new TreeSet<>();
            users.add(recipient);
            if(actor!=null)users.add(actor);
            if(doctor!=null)users.add(doctor);
            repo.lockBoundarySubjects(patient,users);
            if(visitFollowUp.isTracked(org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.SourceKind.CARE_APPOINTMENT,appointment))return false;
            Map<String,Object> source = nativeAppointmentNoticeSource(patient,appointment,modern,true);
            if (source==null || !Objects.equals(source.get("doctor_user_id"),doctor) || !Objects.equals(source.get("start_at"),start) || !Objects.equals(source.get("status"),status)) return false;
            if (modern && (id(source.get("family_workflow_version"))!=0 || source.get("family_assistance_id")!=null)) return false;
            try {
                if(actor!=null)repo.requireBoundaryWrite(actor,patient,"ACCOMPANIMENT");
                if(doctor==null)return false;
                repo.requireBoundaryDoctor(doctor);
                repo.requireBoundaryRead(recipient,patient,"ACCOMPANIMENT");
            }
            catch (org.familyhealthcare.service.workflow.WorkflowException denied) {
                if (denied.getStatus()!=403) throw denied;
                return false;
            }
            return true;
        }
        ));
    }
    private Map<String,Object> nativeAppointmentNoticeSource(long patient,long appointment,boolean modern,boolean lock) {
        List<Map<String,Object>> rows = jdbc.query("SELECT id,patient_id,doctor_user_id,start_at,status"+(modern?",family_workflow_version,family_assistance_id":"")+" FROM care_appointment WHERE id=? AND patient_id=?"+(lock?" FOR UPDATE":""),ps -> {
            ps.setQueryTimeout(5);
            ps.setLong(1,appointment);
            ps.setLong(2,patient);
        }
        ,(rs,n) -> {
            Map<String,Object> row = map("id",rs.getLong("id"),"patient_id",rs.getLong("patient_id"),"doctor_user_id",nullableId(rs,"doctor_user_id"),"start_at",java.time.LocalDateTime.parse(rs.getString("start_at").replace(' ','T')),"status",rs.getString("status"));
            if(modern) {
                row.put("family_workflow_version",rs.getInt("family_workflow_version"));
                row.put("family_assistance_id",nullableId(rs,"family_assistance_id"));
            }
            return row;
        }
        );
        return rows.isEmpty()?null:rows.get(0);
    }
    /** 已在途的真实结果由旧 delivery log 保留；这里只阻止最终标记覆盖后来的收养或原生变更。 */
    public int markLegacyAppointmentNotified(long patient,long appointment,Long doctor,java.time.LocalDateTime start,java.time.LocalDateTime at) {
        boolean modern = storageAvailable();
        return repo.update("UPDATE care_appointment SET notified_at=? WHERE id=? AND patient_id=? AND doctor_user_id=? AND start_at=? AND status='BOOKED' AND notified_at IS NULL"+(modern?" AND family_workflow_version=0 AND family_assistance_id IS NULL":"")+visitFollowUp.excludeTrackedModernMarker(),at,appointment,patient,doctor,start);
    }
}
