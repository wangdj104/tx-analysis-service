package org.familyhealthcare.service.medicalrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 同一次请求/事务复用返回值；不跨请求缓存结构，不扫描临床数据，不执行迁移。 */
@Component
public final class MedicalRecordCorrectionSchema {
    public enum Status { ABSENT, READY, INCOMPATIBLE }
    private static final JsonNode MANIFEST = manifest();

    /** 必须传入本次业务实际借用的连接；检查失败不能被当作无历史或未纳管。 */
    public Status inspectStorage(Connection connection) {
        if (connection == null) return Status.INCOMPATIBLE;
        try {
            long presence = scalar(connection, MANIFEST.get("presenceSql").asText());
            if (presence == 0) return Status.ABSENT;
            return scalar(connection, MANIFEST.get("metadataSql").asText()) == 1 ? Status.READY : Status.INCOMPATIBLE;
        } catch (SQLException | RuntimeException failure) { return Status.INCOMPATIBLE; }
    }

    public void requireNewCommands(Status status, boolean enabled) {
        if (status != Status.READY) throw notReady();
        if (!enabled) throw new WorkflowException(409, "MEDICAL_RECORD_CORRECTION_DISABLED", "Medical record corrections are currently disabled.");
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
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
        try (InputStream input = MedicalRecordCorrectionSchema.class.getResourceAsStream("/medical-record-correction-schema.json")) {
            if (input == null) throw new IllegalStateException("Medical record storage signature is missing.");
            JsonNode value = new ObjectMapper().readTree(input);
            if (value.path("contractVersion").asInt() != 1 || !value.path("presenceSql").isTextual() || !value.path("metadataSql").isTextual())
                throw new IllegalStateException("Medical record storage signature is invalid.");
            return value;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Medical record storage signature is unreadable.", failure); }
    }
    public static WorkflowException notReady() {
        return new WorkflowException(503, "MEDICAL_RECORD_SCHEMA_NOT_READY", "Medical record correction storage is not ready.");
    }
}
