package org.familyhealthcare.service.commongoal;

import org.familyhealthcare.service.familyassistance.FamilyAssistanceAuthorizationService;
import org.familyhealthcare.service.workflow.HouseholdAuthorityLocks;
import org.familyhealthcare.service.workflow.WorkflowCommandExecutor;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** 完整普通家庭权限，不合并单模块授权，也不把档案负责人称为患者本人。 */
@Service
public final class CommonCareGoalAuthorization {
    private static final String KIND = "COMMON_CARE_GOAL";
    private final FamilyAssistanceAuthorizationService family;
    private final HouseholdAuthorityLocks locks;
    public CommonCareGoalAuthorization(FamilyAssistanceAuthorizationService family, HouseholdAuthorityLocks locks) {
        this.family = Objects.requireNonNull(family); this.locks = Objects.requireNonNull(locks);
    }
    public void requireRead(long actor, long patient) { family.requireRead(id(actor), id(patient), KIND); }
    public void requireWrite(long actor, long patient) { family.requireWrite(id(actor), id(patient), KIND); }
    public boolean canWrite(long actor, long patient) { return family.canWrite(id(actor), id(patient), KIND); }
    public long recordOwner(long patient) { return family.owner(id(patient)); }
    public boolean isRecordOwner(long actor, long patient) { return id(actor) == recordOwner(patient); }

    public static final class Admission {
        private final HouseholdAuthorityLocks.LockedAuthority authority;
        private final long actor;
        private final String payloadHash;
        private final boolean replayOnly;
        private boolean goalChecked;
        private Long requiredNominee;
        private Admission(HouseholdAuthorityLocks.LockedAuthority authority, long actor, Command command, boolean replayOnly) {
            this.authority=authority; this.actor=actor; this.payloadHash=command.payloadHash(); this.replayOnly=replayOnly;
            this.goalChecked=replayOnly || command.getGoalId()==null;
        }
        public boolean isReplayOnly() { return replayOnly; }
        public HouseholdAuthorityLocks.LockedAuthority getAuthority() { return authority; }
    }
    /** 存在预检只选择锁路径。命中后只可核对旧回执，绝不能退回新写。 */
    public Admission admit(long actor, Command command, CommonCareGoalRepository repository) {
        requireWrite(actor, command.getPatientId());
        boolean replayOnly = repository.hasReservation(actor, command.getPatientId(), command.getCommandKey());
        Set<Long> subjects = new TreeSet<>(); subjects.add(id(actor));
        if (!replayOnly) {
            if (command.getGoalId()!=null) subjects.addAll(repository.discoverSubjects(command.getPatientId(), command.getGoalId()));
            if (command.getContent()!=null && command.getContent().getNomineeId()!=null) subjects.add(command.getContent().getNomineeId());
        }
        HouseholdAuthorityLocks.LockedAuthority token = locks.lockPatientAndSubjects(command.getPatientId(), subjects);
        requireLockedWrite(token, actor, command.getPatientId());
        // A committed winner may have appeared while authority locks were awaited.
        // Recover its exact receipt before inspecting a later goal/nomination state.
        // Occupied still means replay-only, never permission to start a new change.
        if (!replayOnly) replayOnly=repository.hasReservation(actor,command.getPatientId(),command.getCommandKey());
        return new Admission(token, actor, command, replayOnly);
    }
    /** authority之后、command之前取目标锁并重验发现的身份；重放不读取新正文或新关系。 */
    public Goal lockGoal(Admission admission, long actor, Command command, CommonCareGoalRepository repository) {
        validate(admission, actor, command);
        if (admission.replayOnly || command.getGoalId()==null) return null;
        Goal goal = repository.find(command.getPatientId(), command.getGoalId(), true);
        if (goal==null) throw new WorkflowException(404, "COMMON_GOAL_UNAVAILABLE", "This common care goal is not available.");
        requireLockedGoal(admission.authority, actor, goal); admission.goalChecked=true; return goal;
    }
    private void validate(Admission admission, long actor, Command command) {
        if (admission==null || admission.actor!=actor || !admission.payloadHash.equals(command.payloadHash())) throw changed();
        requireLockedWrite(admission.authority, actor, command.getPatientId());
    }
    public void requireLockedWrite(HouseholdAuthorityLocks.LockedAuthority token, long actor, long patient) {
        locks.requireLive(token, id(patient), id(actor)); requireWrite(actor, patient);
    }
    /** 已锁目标后发现换人只能冲突刷新，绝不倒序补锁另一名账户。 */
    public void requireLockedSubjects(HouseholdAuthorityLocks.LockedAuthority token, long actor, long patient, Collection<Long> subjects) {
        requireLockedWrite(token, actor, patient);
        if (subjects != null) for (Long subject : subjects) if (subject != null && !token.getUserIds().contains(id(subject))) throw changed();
        if (!token.getUserIds().contains(recordOwner(patient))) throw changed();
    }
    public void requireLockedGoal(HouseholdAuthorityLocks.LockedAuthority token, long actor, Goal goal) {
        requireLockedSubjects(token, actor, goal.getPatientId(), Arrays.asList(goal.getContent().getNomineeId(), goal.getAcceptedBy()));
    }
    /** Only a genuinely new callback records a required nominee; never acquire another lock. */
    public void requireNomineeBeforeCommit(Admission admission, Long nominee) {
        if (nominee==null) return;
        if (admission==null || admission.replayOnly || !admission.authority.getUserIds().contains(id(nominee))) throw changed();
        admission.requiredNominee=nominee;
    }
    /** 当前WRITE最先，精确回执其次；新命令的领域关系/版本/flag只放在newChange中。 */
    public Map<String,Object> executeLocked(JdbcTemplate jdbc, Admission admission, long actor,
                                             Command command, Instant now, Supplier<Map<String,Object>> newChange) {
        validate(admission, actor, command);
        if (!admission.goalChecked) throw changed();
        HouseholdAuthorityLocks.LockedAuthority token=admission.authority;
        Map<String,Object> result = WorkflowCommandExecutor.execute(jdbc, WorkflowCommandExecutor.Domain.COMMON_CARE_GOAL,
                actor, command.getCommandKey(), command.canonicalEnvelope(), command.getGoalId(), command.getExpectedVersion(), now, newChange, admission.replayOnly);
        requireLockedWrite(token, actor, command.getPatientId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                requireLockedWrite(token, actor, command.getPatientId());
                if (admission.requiredNominee!=null && !canWrite(admission.requiredNominee,command.getPatientId()))
                    throw new WorkflowException(409,"COMMON_GOAL_NOMINEE_UNAVAILABLE","The nominated account cannot currently take responsibility for this patient. Refresh the available participants.");
            }
        });
        return result;
    }
    /** 降为READ仍可核对自己的最小回执；完全撤权不能利用旧成功读取。 */
    public Map<String,Object> receipt(CommonCareGoalRepository repository, long actor, long patient, String key) {
        requireRead(actor, patient); Map<String,Object> result = repository.receipt(actor, patient, key); requireRead(actor, patient); return result;
    }
    private static WorkflowException changed() { return new WorkflowException(409, "COMMON_GOAL_AUTHORITY_CHANGED", "The responsible accounts changed. Refresh before making a new decision."); }
}
