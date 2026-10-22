package org.familyhealthcare.service.medicalreview;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Metadata-only inspection on the caller's connection. No readiness cache or runtime migration. */
@Component
public final class MedicalReviewSchema {
    public enum Status { ABSENT, READY, INCOMPATIBLE }
    private static final SqlDefinitions SQL = definitions();

    /** The caller retains all connection and transaction ownership, including on failure. */
    public Status inspectStorage(Connection connection) {
        try {
            return inspectStorageStrict(connection);
        } catch (SQLException | RuntimeException failure) {
            return Status.INCOMPATIBLE;
        }
    }

    /** Same inspection, preserving JDBC failure for observers that must not neutralize infrastructure faults. */
    public Status inspectStorageStrict(Connection connection) throws SQLException {
        if (connection == null) return Status.INCOMPATIBLE;
        long presence = scalar(connection, SQL.presence);
        if (presence == 0) return Status.ABSENT;
        return scalar(connection, SQL.metadata) == 1 ? Status.READY : Status.INCOMPATIBLE;
    }

    public void requireNewCommands(Status status, boolean enabled) {
        if (status != Status.READY) throw notReady();
        if (!enabled) throw new WorkflowException(409, "MEDICAL_REVIEW_DISABLED", "Medical record reviews are currently disabled.");
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (result.getMetaData().getColumnCount() != 1 || !result.next()) throw notReady();
                Object raw = result.getObject(1);
                if (result.wasNull() || raw == null) throw notReady();
                long value = exactInteger(raw);
                if (value < 0 || result.next()) throw notReady();
                return value;
            }
        }
    }

    private static long exactInteger(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return ((Number) value).longValue();
        if (value instanceof BigInteger) return ((BigInteger) value).longValueExact();
        if (value instanceof BigDecimal) return ((BigDecimal) value).longValueExact();
        throw notReady();
    }

    private static SqlDefinitions definitions() {
        JsonFactory factory = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try (InputStream input = MedicalReviewSchema.class.getResourceAsStream("/medical-review-schema.json")) {
            if (input == null) throw invalidDefinitions();
            try (JsonParser parser = factory.createParser(input)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw invalidDefinitions();
                boolean version = false;
                String presence = null, metadata = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalidDefinitions();
                    String field = parser.getCurrentName();
                    JsonToken token = parser.nextToken();
                    if ("contractVersion".equals(field)) {
                        if (token != JsonToken.VALUE_NUMBER_INT || !BigInteger.ONE.equals(parser.getBigIntegerValue()))
                            throw invalidDefinitions();
                        version = true;
                    } else if ("presenceSql".equals(field) || "metadataSql".equals(field)) {
                        if (token != JsonToken.VALUE_STRING || parser.getText().trim().isEmpty()) throw invalidDefinitions();
                        if ("presenceSql".equals(field)) presence = parser.getText();
                        else metadata = parser.getText();
                    } else throw invalidDefinitions();
                }
                if (!version || presence == null || metadata == null || parser.nextToken() != null) throw invalidDefinitions();
                return new SqlDefinitions(presence, metadata);
            }
        } catch (IOException | RuntimeException failure) {
            throw invalidDefinitions();
        }
    }

    private static IllegalStateException invalidDefinitions() {
        return new IllegalStateException("Medical review storage signature is missing or invalid.");
    }

    private static final class SqlDefinitions {
        private final String presence, metadata;
        private SqlDefinitions(String presence, String metadata) { this.presence = presence; this.metadata = metadata; }
    }

    public static WorkflowException notReady() {
        return new WorkflowException(503, "MEDICAL_REVIEW_SCHEMA_NOT_READY", "Medical record review storage is not ready.");
    }
}
