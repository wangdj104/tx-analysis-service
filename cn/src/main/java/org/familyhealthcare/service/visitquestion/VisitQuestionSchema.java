package org.familyhealthcare.service.visitquestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.HouseholdWorkflowSchema;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 只读、不缓存。结构完整不等于历史完整；关闭开关也不能把未知结构当作未管理记录。 */
@Component
public final class VisitQuestionSchema {
    public enum Status { ABSENT, STRUCTURALLY_READY, INCOMPATIBLE }
    public enum Availability { DISABLED, ABSENT, READY, INCOMPATIBLE }
    private static final JsonNode MANIFEST = manifest();

    /** 调用方传入将执行本次业务的实际借用连接，不在这里再向池申请连接。 */
    public Status inspectStorage(Connection connection) {
        if (connection == null) return Status.INCOMPATIBLE;
        try {
            long present = scalar(connection, MANIFEST.get("presenceSql").asText());
            if (present == 0) return Status.ABSENT;
            return scalar(connection, MANIFEST.get("metadataSql").asText()) == 1
                    ? Status.STRUCTURALLY_READY : Status.INCOMPATIBLE;
        } catch (SQLException | RuntimeException failure) { return Status.INCOMPATIBLE; }
    }

    public Availability availability(Connection connection, VisitQuestionProperties properties) {
        Status storage = inspectStorage(connection);
        if (storage == Status.INCOMPATIBLE) return Availability.INCOMPATIBLE;
        if (!properties.isEnabled()) return Availability.DISABLED;
        if (storage == Status.ABSENT) return Availability.ABSENT;
        try { requireReady(connection); return Availability.READY; }
        catch (RuntimeException failure) { return Availability.INCOMPATIBLE; }
    }

    /** 沿用 B 的既有只读结构验证，不复制其一千余条签名，也不改变旧守卫。 */
    public VisitQuestionTransportReadiness.Limits requireReady(Connection connection) {
        VisitQuestionTransportReadiness.Limits transport = VisitQuestionTransportReadiness.inspect(connection);
        if (inspectStorage(connection) != Status.STRUCTURALLY_READY) throw notReady();
        JdbcTemplate sameConnection = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        sameConnection.setQueryTimeout(5);
        // Preserve the original JDBC failure without probing a connection that the pool may have evicted.
        sameConnection.setExceptionTranslator(new SQLExceptionSubclassTranslator());
        if (new HouseholdWorkflowSchema(sameConnection).inspectStorageMetadata().getStatus()
                != HouseholdWorkflowSchema.StorageStatus.STRUCTURALLY_READY) throw notReady();
        return transport;
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        // 这些固定元数据查询无正文参数；长度已随独立结构签名限制，不执行 DDL 或会话 SET。
        if (VisitQuestionTransportReadiness.wireBound(sql) > VisitQuestionTransportReadiness.MIN_PACKET) throw notReady();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw notReady();
                long value = result.getLong(1);
                if (result.wasNull() || value < 0 || result.next()) throw notReady();
                return value;
            }
        }
    }

    private static JsonNode manifest() {
        try (InputStream input = VisitQuestionSchema.class.getResourceAsStream("/visit-question-schema.json")) {
            if (input == null) throw new IllegalStateException("Question storage signature is missing.");
            return new ObjectMapper().readTree(input);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Question storage signature is unreadable.", failure); }
    }
    private static WorkflowException notReady() {
        return new WorkflowException(503, "QUESTION_SCHEMA_NOT_READY", "Question tracking storage is not ready.");
    }
}
