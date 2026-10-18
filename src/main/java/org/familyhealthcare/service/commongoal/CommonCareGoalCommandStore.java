package org.familyhealthcare.service.commongoal;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** WorkflowCommandExecutor的固定共同目标分支，不接受表名或外部SQL，不负责授予领域权限。 */
public final class CommonCareGoalCommandStore {
    private CommonCareGoalCommandStore() { }
    public static Map<String,Object> execute(JdbcTemplate jdbc, long actor, String key, String payload, Long goal,
                                             long expected, Instant now, Supplier<Map<String,Object>> change, boolean replayOnly) {
        WorkflowData.mutationTransaction(jdbc); id(actor); Objects.requireNonNull(now); Objects.requireNonNull(change);
        Command command = command(payload);
        if (!command.getCommandKey().equals(commandKey(key)) || !Objects.equals(command.getGoalId(), goal) || command.getExpectedVersion()!=expected) throw invalid();
        if (replayOnly) return replay(jdbc, actor, command);
        try {
            CommonCareGoalRepository.one(CommonCareGoalRepository.update(jdbc,
                    "INSERT INTO common_care_goal_command(actor_id,command_key,patient_id,goal_id,expected_version,operation,payload_hash,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    actor, command.getCommandKey(), command.getPatientId(), goal, expected, command.getOperation().name(), command.payloadHash(), now));
        } catch (DuplicateKeyException duplicate) {
            return replay(jdbc, actor, command);
        }
        AtomicReference<Map<String,Object>> completed = new AtomicReference<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { CommonCareGoalRepository.requireCompletedTransition(jdbc, actor, command, completed.get()); }
        });
        Map<String,Object> result = receipt(change.get());
        if (!Long.valueOf(next(expected)).equals(result.get("version")) || !command.getOperation().name().equals(result.get("operation"))
                || (goal != null && !goal.equals(result.get("goalId")))) throw integrity();
        CommonCareGoalRepository.one(CommonCareGoalRepository.update(jdbc,
                "UPDATE common_care_goal_command SET goal_id=?,result_json=? WHERE actor_id=? AND command_key=? AND patient_id=? AND result_json IS NULL",
                result.get("goalId"), WorkflowData.json(result), actor, command.getCommandKey(), command.getPatientId()));
        completed.set(result); return result;
    }
    /** replay-only不能先保留新命令；丢失/不完整只报未知或完整性错误，不调用任何change。 */
    private static Map<String,Object> replay(JdbcTemplate jdbc, long actor, Command command) {
        List<Map<String,Object>> rows = CommonCareGoalRepository.query(jdbc,
                "SELECT patient_id,goal_id,expected_version,operation,payload_hash,OCTET_LENGTH(result_json) AS receipt_bytes,CASE WHEN OCTET_LENGTH(result_json)<=512 THEN result_json ELSE NULL END AS result_json FROM common_care_goal_command WHERE actor_id=? AND command_key=? FOR UPDATE",
                (r,n) -> {
                    if (r.getLong("patient_id") != command.getPatientId() || r.getLong("expected_version") != command.getExpectedVersion()
                            || !command.getOperation().name().equals(r.getString("operation")) || !command.payloadHash().equals(r.getString("payload_hash"))
                            || (command.getGoalId() != null && !command.getGoalId().equals(WorkflowData.nullableId(r,"goal_id")))) throw conflict();
                    return CommonCareGoalRepository.readReceipt(r);
                }, actor, command.getCommandKey());
        if (rows.size()!=1) throw new org.familyhealthcare.service.workflow.WorkflowException(409, "COMMON_GOAL_RECEIPT_UNKNOWN", "The original operation is not yet confirmed. Recheck the same command.");
        return rows.get(0);
    }

}
