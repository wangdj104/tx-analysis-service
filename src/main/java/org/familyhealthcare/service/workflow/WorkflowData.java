package org.familyhealthcare.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Canonical storage mechanics only. Domain services remain responsible for current authority. */
public final class WorkflowData {
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(DeserializationFeature.USE_LONG_FOR_INTS, true);
    private WorkflowData() { }

    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) result.put((String) pairs[i], pairs[i+1]);
        return result;
    }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode workflow data", failure); }
    }
    public static Map<String,Object> object(String value) {
        try {
            Map<String,Object> result = JSON.readValue(value, new TypeReference<Map<String,Object>>() { });
            restoreIntegers(result);
            return result;
        } catch (Exception failure) { throw new IllegalStateException("Invalid stored workflow data", failure); }
    }
    @SuppressWarnings("unchecked") private static void restoreIntegers(Object value) {
        if (value instanceof Map) {
            Map<String,Object> map = (Map<String,Object>) value;
            for (String key : map.keySet()) {
                Object item = map.get(key);
                if (Arrays.asList("workflowVersion", "ordinal", "revisionNo").contains(key) && item instanceof Number)
                    map.put(key, ((Number) item).intValue());
                else restoreIntegers(item);
            }
        } else if (value instanceof List) for (Object item : (List<?>) value) restoreIntegers(item);
    }
    /** Never retain evidence titles/locations in a command replay cache. */
    public static Map<String,Object> cachedResult(Map<String,Object> result) {
        Map<String,Object> copy = object(json(result));
        stripEvidence(copy);
        return copy;
    }
    @SuppressWarnings("unchecked") private static void stripEvidence(Object value) {
        if (value instanceof Map) {
            Map<String,Object> map = (Map<String,Object>) value;
            for (Map.Entry<String,Object> entry : map.entrySet()) {
                if ("evidence".equals(entry.getKey()) && entry.getValue() instanceof List) {
                    for (Object item : (List<?>) entry.getValue()) {
                        Map<String,Object> reference = (Map<String,Object>) item;
                        reference.remove("title");
                        reference.remove("detailLink");
                        reference.put("restricted", true);
                    }
                } else stripEvidence(entry.getValue());
            }
        } else if (value instanceof List) for (Object item : (List<?>) value) stripEvidence(item);
    }
    public static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format(Locale.ROOT, "%02x", item & 255));
            return result.toString();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    public static Calendar utc() {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setGregorianChange(new java.util.Date(Long.MIN_VALUE));
        return calendar;
    }
    public static void time(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setNull(index, Types.TIMESTAMP);
        else statement.setTimestamp(index, Timestamp.from(value), utc());
    }
    public static String time(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column, utc());
        return value == null ? null : value.toInstant().toString();
    }
    public static Long nullableId(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
    public static long id(Object value) { return ((Number) value).longValue(); }
    /** Positive exact IDs; do not coerce strings or truncate fractional/overflowing numbers. */
    public static long requireId(Object value, String field) {
        long id;
        try {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                id = ((Number) value).longValue();
            else if (value instanceof BigInteger) id = ((BigInteger) value).longValueExact();
            else if (value instanceof BigDecimal) id = ((BigDecimal) value).longValueExact();
            else throw WorkflowException.invalid(field + " must be an integer ID");
        } catch (ArithmeticException invalid) { throw WorkflowException.invalid(field + " must be an integer ID"); }
        if (id <= 0) throw WorkflowException.invalid(field + " must be a positive integer ID");
        return id;
    }
    public static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("A workflow write requires an active synchronized transaction.");
    }
    /** Verify the actual connection, not merely the caller's transaction annotation. */
    public static void mutationTransaction(JdbcTemplate jdbc) {
        transaction();
        javax.sql.DataSource source = Objects.requireNonNull(jdbc.getDataSource(), "datasource");
        Connection connection = DataSourceUtils.getConnection(source);
        try {
            if (!DataSourceUtils.isConnectionTransactional(connection, source) || connection.getAutoCommit()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
                throw new IllegalStateException("Workflow mutations require an effective READ_COMMITTED transaction on their datasource.");
        } catch (SQLException failure) { throw new IllegalStateException("Cannot verify the workflow transaction isolation.", failure); }
        finally { DataSourceUtils.releaseConnection(connection, source); }
    }
}
