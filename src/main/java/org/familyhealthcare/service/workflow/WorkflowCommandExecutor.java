package org.familyhealthcare.service.workflow;

import org.springframework.dao.DuplicateKeyException;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.Types;
import org.familyhealthcare.service.visitquestion.VisitQuestionContracts;
import org.familyhealthcare.service.visitquestion.VisitQuestionTransportReadiness;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Small durable-command boundary. No business transitions, authorization or arbitrary SQL identifiers. */
public final class WorkflowCommandExecutor {
    public enum Domain {
        CARE_PLAN("care_plan_command", "plan_id", "planId"),
        ALERT("alert_command", "alert_id", "alertId"),
        MEASUREMENT("measurement_command", "source_id", "sourceId"),
        MEDICATION_EXECUTION("medication_execution_command", "execution_id", "executionId"),
        STOCK("stock_command", "stock_id", "stockId"),
        FAMILY_ASSISTANCE("family_assistance_command", "assistance_id", "assistanceId"),
        EMERGENCY_ASSISTANCE("emergency_assistance_command", "emergency_event_id", "emergencyEventId"),
        VISIT_QUESTION("visit_question_command", "question_id", "questionId"),
        MEDICAL_RECORD("medical_record_command", "record_id", "recordId"),
        COMMON_CARE_GOAL("common_care_goal_command", "goal_id", "goalId"),
        VISIT_FOLLOWUP("visit_followup_command", "state_id", "workflowId");
        private final String table, aggregateColumn, resultKey;
        Domain(String table, String aggregateColumn, String resultKey) {
            this.table = table; this.aggregateColumn = aggregateColumn; this.resultKey = resultKey;
        }
    }
    private WorkflowCommandExecutor() { }

    /** 父15固定域必须携带正确入场能力；不能借通用执行器绕开 A/V 锁序。 */
    public static Map<String,Object> executeVisitFollowUp(long actor, org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.Command command,
            org.familyhealthcare.service.visitfollowup.VisitFollowUpAdmission.Context admission, Supplier<Map<String,Object>> change) {
        return org.familyhealthcare.service.visitfollowup.VisitFollowUpCommandStore.executeAppointment(actor, command, admission, change);
    }
    public static Map<String,Object> executeVisitFollowUp(long actor, org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.Command command,
            org.familyhealthcare.service.visitfollowup.VisitFollowUpAdmission.VisitBindingContext admission, Supplier<Map<String,Object>> change) {
        return org.familyhealthcare.service.visitfollowup.VisitFollowUpCommandStore.executeVisitBinding(actor, command, admission, change);
    }

    /** Callers check authority before entry and again after lock waits/replay, in this same transaction. */
    public static Map<String,Object> execute(JdbcTemplate jdbc, Domain domain, long actorId, String key, String payload,
                                             Long aggregateId, long expectedVersion, Instant now, Supplier<Map<String,Object>> change) {
        if (domain == Domain.VISIT_FOLLOWUP)
            throw WorkflowException.invalid("Visit follow-up commands require their scoped admission context.");
        if (domain == Domain.COMMON_CARE_GOAL)
            return org.familyhealthcare.service.commongoal.CommonCareGoalCommandStore.execute(jdbc, actorId, key, payload, aggregateId, expectedVersion, now, change, false);
        WorkflowData.mutationTransaction(jdbc);
        if (domain == null || actorId <= 0 || payload == null || now == null || change == null)
            throw WorkflowException.invalid("Invalid command.");
        if (expectedVersion < 0) throw WorkflowException.invalid("expectedVersion must be non-negative.");
        if (aggregateId != null && aggregateId <= 0) throw WorkflowException.invalid(domain.resultKey + " must be positive.");
        String normalized = domain == Domain.MEDICAL_RECORD ? MedicalRecordCorrectionContracts.commandKey(key) : validateKey(key);
        // B 创建回执绑定真实 ID 后，原始 null 目标仍属于不可更换的请求信封。
        // 保留已有 A 三域哈希语义；四个固定 B 域将完整技术信封一起取哈希。
        boolean household = domain == Domain.MEDICATION_EXECUTION || domain == Domain.STOCK
                || domain == Domain.FAMILY_ASSISTANCE || domain == Domain.EMERGENCY_ASSISTANCE;
        boolean question = domain == Domain.VISIT_QUESTION;
        boolean medical = domain == Domain.MEDICAL_RECORD;
        MedicalRecordCorrectionContracts.Command medicalCommand = medical ? MedicalRecordCorrectionContracts.command(payload) : null;
        if (medical) {
            MedicalRecordCorrectionContracts.id(actorId);
            if (!Objects.equals(aggregateId, medicalCommand.getRecordId()) || expectedVersion != medicalCommand.getExpectedContentVersion()
                    || !normalized.equals(medicalCommand.getCommandKey())) throw MedicalRecordCorrectionContracts.invalid();
        }
        String questionEnvelope = question ? VisitQuestionContracts.envelope(payload, aggregateId, expectedVersion) : null;
        if (question) VisitQuestionContracts.id(actorId, "actorId");
        String hash = medical ? medicalCommand.payloadHash() : question ? WorkflowData.hash(questionEnvelope) : WorkflowData.hash(household
                ? WorkflowData.json(WorkflowData.map("target", aggregateId, "expectedVersion", expectedVersion, "payload", payload)) : payload);
        try {
            jdbc.update(connection -> {
                String sql = "INSERT INTO " + domain.table + "(actor_id,command_key," + domain.aggregateColumn + ",expected_version,payload_hash,created_at) VALUES(?,?,?,?,?,?)";
                if (question) VisitQuestionTransportReadiness.requireStatement(connection, sql, actorId, normalized, aggregateId, expectedVersion, hash, now);
                PreparedStatement statement = connection.prepareStatement(sql);
                if (question || medical) statement.setQueryTimeout(5);
                statement.setLong(1, actorId); statement.setString(2, normalized);
                if (aggregateId == null) statement.setNull(3, Types.BIGINT); else statement.setLong(3, aggregateId);
                statement.setLong(4, expectedVersion); statement.setString(5, hash); WorkflowData.time(statement, 6, now);
                return statement;
            });
        } catch (DuplicateKeyException duplicate) {
            // Locking read sees the winning committed result after the unique-key wait on MySQL5.7.
            String readSql = "SELECT " + domain.aggregateColumn + ",expected_version,payload_hash,"
                    + ((question || medical) ? "CASE WHEN OCTET_LENGTH(result_json)<=512 THEN result_json ELSE NULL END AS result_json" : "result_json")
                    + " FROM " + domain.table + " WHERE actor_id=? AND command_key=? FOR UPDATE";
            org.springframework.jdbc.core.RowMapper<Map<String,Object>> mapper = (result, index) -> WorkflowData.map("aggregateId", WorkflowData.nullableId(result, domain.aggregateColumn), "version", result.getLong("expected_version"), "hash", result.getString("payload_hash"), "result", result.getString("result_json"));
            List<Map<String,Object>> rows = (question || medical) ? jdbc.query(connection -> {
                if (question) VisitQuestionTransportReadiness.requireStatement(connection, readSql, actorId, normalized);
                PreparedStatement statement = connection.prepareStatement(readSql);
                statement.setQueryTimeout(5); statement.setLong(1, actorId); statement.setString(2, normalized);
                return statement;
            }, mapper) : jdbc.query(readSql, mapper, actorId, normalized);
            if (rows.isEmpty()) throw new IllegalStateException("Command reservation was not found", duplicate);
            Map<String,Object> row = rows.get(0);
            if (!hash.equals(row.get("hash")) || expectedVersion != WorkflowData.id(row.get("version"))
                    || (aggregateId != null && !aggregateId.equals(row.get("aggregateId"))))
                throw medical ? MedicalRecordCorrectionContracts.commandConflict() : new WorkflowException(409, "COMMAND_CONFLICT", "This command key was already used for a different request.");
            if (row.get("aggregateId") == null || row.get("result") == null)
                throw new IllegalStateException("Incomplete committed workflow command.");
            if (medical) {
                Map<String,Object> receipt = MedicalRecordCorrectionContracts.storedReceipt((String) row.get("result"));
                if (!receipt.get("recordId").equals(row.get("aggregateId"))
                        || MedicalRecordCorrectionContracts.id(receipt.get("contentVersion")) != expectedVersion + 1
                        || !medicalCommand.getOperation().name().equals(receipt.get("operation")))
                    throw MedicalRecordCorrectionContracts.integrity();
                return receipt;
            }
            if (question) {
                Map<String,Object> receipt = VisitQuestionContracts.receipt(VisitQuestionContracts.parse((String) row.get("result")));
                if (!receipt.get("questionId").equals(row.get("aggregateId"))
                        || VisitQuestionContracts.id(receipt.get("version"), "version") != expectedVersion + 1)
                    throw new IllegalStateException("Question replay receipt is inconsistent.");
                return receipt;
            }
            return WorkflowData.cachedResult(WorkflowData.object((String) row.get("result")));
        }
        AtomicBoolean completed = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<Map<String,Object>> medicalReceipt = new java.util.concurrent.atomic.AtomicReference<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                if (!completed.get()) throw new IllegalStateException("An incomplete workflow command cannot commit.");
                if (medical) MedicalRecordCorrectionRepository.requireCompletedTransition(jdbc, actorId, normalized, medicalCommand, medicalReceipt.get());
            }
        });
        Map<String,Object> result = change.get();
        if (result == null) throw new IllegalStateException("Command did not produce a result.");
        Object resultId = result.get(domain.resultKey);
        if (domain == Domain.CARE_PLAN && !result.containsKey(domain.resultKey)) resultId = result.get("id");
        long resolvedId = WorkflowData.requireId(resultId, domain.resultKey);
        if (aggregateId != null && resolvedId != aggregateId) throw new IllegalStateException("Command result has a different aggregate.");
        Map<String,Object> safeResult = medical ? MedicalRecordCorrectionContracts.receipt(result) : question ? VisitQuestionContracts.receipt(result) : WorkflowData.cachedResult(result);
        if (question && VisitQuestionContracts.id(safeResult.get("version"), "version") != expectedVersion + 1)
            throw new IllegalStateException("Question receipt has a different version.");
        if (medical && (MedicalRecordCorrectionContracts.id(safeResult.get("contentVersion")) != expectedVersion + 1
                || !medicalCommand.getOperation().name().equals(safeResult.get("operation")))) throw MedicalRecordCorrectionContracts.integrity();
        String completeSql = "UPDATE " + domain.table + " SET " + domain.aggregateColumn + "=?,result_json=? WHERE actor_id=? AND command_key=? AND result_json IS NULL";
        String receiptJson = WorkflowData.json(safeResult);
        int count = (question || medical) ? jdbc.update(connection -> {
            if (question) VisitQuestionTransportReadiness.requireStatement(connection, completeSql, resolvedId, receiptJson, actorId, normalized);
            PreparedStatement statement = connection.prepareStatement(completeSql);
            statement.setQueryTimeout(5); statement.setLong(1, resolvedId); statement.setString(2, receiptJson);
            statement.setLong(3, actorId); statement.setString(4, normalized);
            return statement;
        }) : jdbc.update(completeSql, resolvedId, receiptJson, actorId, normalized);
        if (count != 1) throw new IllegalStateException("Command reservation could not be completed.");
        if (medical) medicalReceipt.set(safeResult);
        completed.set(true);
        return safeResult;
    }
    /** 共同目标的固定入场标记；其它域保留原公开入口及完整旧实现。 */
    public static Map<String,Object> execute(JdbcTemplate jdbc, Domain domain, long actorId, String key, String payload,
                                             Long aggregateId, long expectedVersion, Instant now, Supplier<Map<String,Object>> change, boolean replayOnly) {
        if (domain != Domain.COMMON_CARE_GOAL) throw WorkflowException.invalid("Replay-only admission belongs to common care goals.");
        return org.familyhealthcare.service.commongoal.CommonCareGoalCommandStore.execute(jdbc, actorId, key, payload, aggregateId, expectedVersion, now, change, replayOnly);
    }
    public static String validateKey(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw WorkflowException.invalid("commandKey must be a UUID.");
        return UUID.fromString(value).toString();
    }
}
