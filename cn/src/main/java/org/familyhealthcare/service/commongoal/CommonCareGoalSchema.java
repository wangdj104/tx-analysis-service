package org.familyhealthcare.service.commongoal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.sql.*;

/** 只检查本请求的有限命名结构元数据；不跨请求缓存、不扫描业务内容、不执行DDL。 */
@Component
public final class CommonCareGoalSchema {
    public enum Status { ABSENT, INCOMPATIBLE, READY }
    private static final JsonNode MANIFEST = manifest();
    public Status inspectStorage(Connection connection) {
        if (connection == null) return Status.INCOMPATIBLE;
        try {
            long present = scalar(connection, MANIFEST.get("presenceSql").asText());
            if (present == 0) return Status.ABSENT;
            return scalar(connection, MANIFEST.get("metadataSql").asText()) == 1 ? Status.READY : Status.INCOMPATIBLE;
        } catch (SQLException | RuntimeException failure) { return Status.INCOMPATIBLE; }
    }
    /** READY且关闭时仍可读已有目标和回执，不能把关闭/坏结构伪装成空数据。 */
    public void requireReadable(Status status) {
        if (status == Status.ABSENT) throw new WorkflowException(503, "COMMON_GOAL_SCHEMA_ABSENT", "Common care goal storage has not been installed.");
        if (status != Status.READY) throw new WorkflowException(503, "COMMON_GOAL_SCHEMA_INCOMPATIBLE", "Common care goal storage needs review before it can be used.");
    }
    public void requireNewCommands(Status status, boolean enabled) {
        requireReadable(status);
        if (!enabled) throw new WorkflowException(409, "COMMON_GOALS_DISABLED", "Common care goal changes are currently disabled.");
    }
    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing storage signature result");
                long value = result.getLong(1);
                if (result.wasNull() || value < 0 || result.next()) throw new SQLException("Invalid storage signature result");
                return value;
            }
        }
    }
    private static JsonNode manifest() {
        try (InputStream input = CommonCareGoalSchema.class.getResourceAsStream("/common-care-goal-schema.json")) {
            if (input == null) throw new IllegalStateException("Common care goal storage signature is missing.");
            JsonNode value = new ObjectMapper().readTree(input);
            if (value.path("contractVersion").asInt()!=1 || !value.path("presenceSql").isTextual() || !value.path("metadataSql").isTextual()) throw new IllegalStateException("Invalid common care goal storage signature.");
            return value;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Common care goal storage signature is unreadable.", failure); }
    }
}
