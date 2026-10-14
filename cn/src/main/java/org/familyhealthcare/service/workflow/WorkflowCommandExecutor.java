package org.familyhealthcare.service.workflow;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Small durable-command boundary. No business transitions, authorization or arbitrary SQL identifiers. */
public final class WorkflowCommandExecutor {
    public enum Domain {
        CARE_PLAN("care_plan_command", "plan_id", "planId"),
        ALERT("alert_command", "alert_id", "alertId"),
        MEASUREMENT("measurement_command", "source_id", "sourceId");
        private final String table, aggregateColumn, resultKey;
        Domain(String table, String aggregateColumn, String resultKey) {
            this.table = table; this.aggregateColumn = aggregateColumn; this.resultKey = resultKey;
        }
    }
    private WorkflowCommandExecutor() { }

    /** Callers check authority before entry and again after lock waits/replay, in this same transaction. */
    public static Map<String,Object> execute(JdbcTemplate jdbc, Domain domain, long actorId, String key, String payload,
                                             Long aggregateId, long expectedVersion, Instant now, Supplier<Map<String,Object>> change) {
        WorkflowData.mutationTransaction(jdbc);
        if (domain == null || actorId <= 0 || payload == null || now == null || change == null)
            throw WorkflowException.invalid("Invalid command.");
        if (expectedVersion < 0) throw WorkflowException.invalid("expectedVersion must be non-negative.");
        if (aggregateId != null && aggregateId <= 0) throw WorkflowException.invalid(domain.resultKey + " must be positive.");
        String normalized = validateKey(key), hash = WorkflowData.hash(payload);
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO " + domain.table + "(actor_id,command_key," + domain.aggregateColumn + ",expected_version,payload_hash,created_at) VALUES(?,?,?,?,?,?)");
                statement.setLong(1, actorId); statement.setString(2, normalized);
                if (aggregateId == null) statement.setNull(3, Types.BIGINT); else statement.setLong(3, aggregateId);
                statement.setLong(4, expectedVersion); statement.setString(5, hash); WorkflowData.time(statement, 6, now);
                return statement;
            });
        } catch (DuplicateKeyException duplicate) {
            // Locking read sees the winning committed result after the unique-key wait on MySQL5.7.
            List<Map<String,Object>> rows = jdbc.query("SELECT " + domain.aggregateColumn + ",expected_version,payload_hash,result_json FROM " + domain.table + " WHERE actor_id=? AND command_key=? FOR UPDATE",
                    (result, index) -> WorkflowData.map("aggregateId", WorkflowData.nullableId(result, domain.aggregateColumn), "version", result.getLong("expected_version"), "hash", result.getString("payload_hash"), "result", result.getString("result_json")), actorId, normalized);
            if (rows.isEmpty()) throw new IllegalStateException("Command reservation was not found", duplicate);
            Map<String,Object> row = rows.get(0);
            if (!hash.equals(row.get("hash")) || expectedVersion != WorkflowData.id(row.get("version"))
                    || (aggregateId != null && !aggregateId.equals(row.get("aggregateId"))))
                throw new WorkflowException(409, "COMMAND_CONFLICT", "This command key was already used for a different request.");
            if (row.get("aggregateId") == null || row.get("result") == null)
                throw new IllegalStateException("Incomplete committed workflow command.");
            return WorkflowData.cachedResult(WorkflowData.object((String) row.get("result")));
        }
        AtomicBoolean completed = new AtomicBoolean(false);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                if (!completed.get()) throw new IllegalStateException("An incomplete workflow command cannot commit.");
            }
        });
        Map<String,Object> result = change.get();
        if (result == null) throw new IllegalStateException("Command did not produce a result.");
        Object resultId = result.get(domain.resultKey);
        if (domain == Domain.CARE_PLAN && !result.containsKey(domain.resultKey)) resultId = result.get("id");
        long resolvedId = WorkflowData.requireId(resultId, domain.resultKey);
        if (aggregateId != null && resolvedId != aggregateId) throw new IllegalStateException("Command result has a different aggregate.");
        Map<String,Object> safeResult = WorkflowData.cachedResult(result);
        int count = jdbc.update("UPDATE " + domain.table + " SET " + domain.aggregateColumn + "=?,result_json=? WHERE actor_id=? AND command_key=? AND result_json IS NULL",
                resolvedId, WorkflowData.json(safeResult), actorId, normalized);
        if (count != 1) throw new IllegalStateException("Command reservation could not be completed.");
        completed.set(true);
        return safeResult;
    }
    public static String validateKey(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw WorkflowException.invalid("commandKey must be a UUID.");
        return UUID.fromString(value).toString();
    }
}
