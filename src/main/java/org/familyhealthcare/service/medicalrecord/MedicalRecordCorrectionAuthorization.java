package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.workflow.HouseholdAuthorityLocks;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.stereotype.Component;
import java.util.*;

/** 发现绑定不授予权限；等待后的实际绑定和当前数据库权限才是入场依据。 */
@Component
public class MedicalRecordCorrectionAuthorization {
    private final MedicalRecordCorrectionRepository repository;
    private final HouseholdAuthorityLocks locks;
    private final MedicalRecordReviewAccess access;
    public MedicalRecordCorrectionAuthorization(MedicalRecordCorrectionRepository repository, HouseholdAuthorityLocks locks, MedicalRecordReviewAccess access) {
        this.repository = repository; this.locks = locks; this.access = access;
    }
    public long actor() {
        Long actor = CurrentUserUtil.getCurrentUserId();
        if (actor == null) throw denied();
        return MedicalRecordCorrectionContracts.id(actor);
    }
    public MedicalRecord lock(long id, long actor, boolean workspace) {
        MedicalRecord value = lockExisting(id, actor, workspace);
        if (value == null) throw denied();
        return value;
    }
    public MedicalRecord lockExisting(long id, long actor, boolean workspace) {
        MedicalRecord discovered = repository.binding(id, false);
        if (discovered == null) return null;
        List<Long> subjects = new ArrayList<>(); subjects.add(actor);
        if (discovered.getUserId() != null) subjects.add(discovered.getUserId());
        if (discovered.getPatientId() == null) locks.lockSubjectsWithoutPatient(subjects);
        else locks.lockPatientAndSubjects(discovered.getPatientId(), subjects);
        requireLocked(discovered, actor, workspace);
        MedicalRecord locked = repository.binding(id, true);
        sameBinding(discovered, locked);
        requireLocked(locked, actor, workspace);
        return locked;
    }
    public void requireLocked(MedicalRecord source, long actor, boolean workspace) {
        try { access.requireLockedCurrent(source, actor, true, workspace); }
        catch (IllegalStateException failure) { throw denied(); }
    }
    /** 通过另一个 Spring bean 的 REQUIRES_NEW 代理核当前授权，不能拿旧内容快照当权限。 */
    public void requireFresh(MedicalRecord source, long actor, boolean write) {
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw denied();
        try { access.requireCurrent(source, write, false); }
        catch (IllegalStateException failure) { throw denied(); }
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw denied();
    }
    public boolean canWrite(MedicalRecord source, long actor) {
        try { requireFresh(source, actor, true); return true; }
        catch (WorkflowException failure) { if (failure.getStatus() == 403) return false; throw failure; }
    }
    public static void sameBinding(MedicalRecord expected, MedicalRecord actual) {
        if (actual == null || !Objects.equals(expected.getId(), actual.getId())
                || !Objects.equals(expected.getPatientId(), actual.getPatientId()) || !Objects.equals(expected.getUserId(), actual.getUserId())) throw denied();
    }
    public static WorkflowException denied() { return new WorkflowException(403, "MEDICAL_RECORD_ACCESS_DENIED", "Access to this medical record is not available."); }
}
