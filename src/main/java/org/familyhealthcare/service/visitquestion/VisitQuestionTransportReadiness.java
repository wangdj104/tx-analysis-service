package org.familyhealthcare.service.visitquestion;

import org.familyhealthcare.service.workflow.WorkflowException;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** 仅读取当前借用连接；不改参数、不重连、不推测连接池另一条连接的能力。 */
public final class VisitQuestionTransportReadiness {
    public static final long MIN_PACKET = 4L * 1024 * 1024;
    private static final String SERVER_SQL = "SELECT VERSION(),DATABASE(),@@GLOBAL.max_allowed_packet,@@SESSION.max_allowed_packet,"
            + "@@character_set_client,@@character_set_connection,@@character_set_results";
    private VisitQuestionTransportReadiness() { }

    /** NULL results 仅允许读取这些经元数据独立核实的正文列。 */
    public enum ReadColumn {
        TITLE("visit_question_state", "current_title"), DESCRIPTION("visit_question_state", "current_description"),
        RECORDED_ANSWER("visit_question_state", "current_recorded_answer"), FOLLOW_UP("visit_question_state", "current_follow_up"),
        EVENT_BODY("visit_question_event", "body_json"), RECEIPT("visit_question_command", "result_json"),
        NATIVE_TITLE("care_item", "title"), NATIVE_DETAILS("care_item", "data_json");
        private final String table, column;
        ReadColumn(String table, String column) { this.table = table; this.column = column; }
    }

    public static final class Limits {
        private final Connection borrowed;
        private final long global, session, driver;
        private Limits(Connection borrowed, long global, long session, long driver) {
            this.borrowed = borrowed; this.global = global; this.session = session; this.driver = driver;
        }
        public long getMinimumPacket() { return Math.min(global, Math.min(session, driver)); }
        public void requireStatement(Connection executing, String sql, Object... parameters) {
            if (executing != borrowed) throw stop();
            if (wireBound(sql, parameters) > getMinimumPacket()) throw new WorkflowException(413, "QUESTION_WIRE_LIMIT", "Question statement exceeds the verified transport limit.");
        }
    }

    // 结构存在性检查不依赖正文列；实际就绪与命令入口每次核对这六列，不采信调用方旧证明。
    private static final Set<ReadColumn> QUESTION_READ_COLUMNS = Collections.unmodifiableSet(EnumSet.range(ReadColumn.TITLE, ReadColumn.RECEIPT));

    public static Limits inspect(Connection borrowed) { return inspect(borrowed, QUESTION_READ_COLUMNS); }

    public static Limits inspect(Connection borrowed, Set<ReadColumn> readColumns) {
        if (borrowed == null || readColumns == null) throw stop();
        try {
            Class<?> vendor = Class.forName("com.mysql.cj.jdbc.JdbcConnection");
            if (!borrowed.isWrapperFor(vendor)) throw stop();
            Object physical = borrowed.unwrap(vendor);
            if (!vendor.isInstance(physical) || borrowed.unwrap(vendor) != physical
                    || ((Connection) physical).unwrap(vendor) != physical) throw stop();
            DatabaseMetaData metadata = borrowed.getMetaData();
            if (metadata == null || !"MySQL Connector/J".equals(metadata.getDriverName())
                    || metadata.getDriverVersion() == null
                    || !metadata.getDriverVersion().matches("mysql-connector-java-8\\.0\\.29(?: \\(Revision: [^)]+\\))?")) throw stop();
            Connection metadataConnection = metadata.getConnection();
            if (metadataConnection == null || metadataConnection.unwrap(vendor) != physical) throw stop();

            // Connector/J 在主源码中是 runtime 依赖，只使用固定公共接口，无私有字段或可写反射。
            Object properties = vendor.getMethod("getPropertySet").invoke(physical);
            Class<?> propertySet = Class.forName("com.mysql.cj.conf.PropertySet");
            Class<?> propertyKey = Class.forName("com.mysql.cj.conf.PropertyKey");
            Class<?> runtimeProperty = Class.forName("com.mysql.cj.conf.RuntimeProperty");
            if (!propertySet.isInstance(properties)) throw stop();
            Method getValue = runtimeProperty.getMethod("getValue");
            Object packetProperty = propertySet.getMethod("getIntegerProperty", propertyKey)
                    .invoke(properties, propertyKey.getField("maxAllowedPacket").get(null));
            Object encodingProperty = propertySet.getMethod("getStringProperty", propertyKey)
                    .invoke(properties, propertyKey.getField("characterEncoding").get(null));
            Object packet = getValue.invoke(packetProperty), encoding = getValue.invoke(encodingProperty);
            if (!(packet instanceof Integer) || ((Integer) packet).longValue() < MIN_PACKET
                    || !(encoding instanceof String) || !StandardCharsets.UTF_8.equals(Charset.forName((String) encoding))) throw stop();
            try (PreparedStatement statement = borrowed.prepareStatement(SERVER_SQL)) {
                statement.setQueryTimeout(5);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || !supportedVersion(rows.getString(1)) || rows.getString(2) == null) throw stop();
                    long global = rows.getLong(3); if (rows.wasNull() || global < MIN_PACKET) throw stop();
                    long session = rows.getLong(4); if (rows.wasNull() || session < MIN_PACKET) throw stop();
                    if (!"utf8mb4".equals(rows.getString(5)) || !"utf8mb4".equals(rows.getString(6))) throw stop();
                    String results = rows.getString(7);
                    if (results == null) verifyNoConversionColumns(borrowed, rows.getString(2), readColumns, new Limits(borrowed, global, session, ((Integer) packet).longValue()));
                    else if (!"utf8mb4".equals(results)) throw stop();
                    if (rows.next() || borrowed.unwrap(vendor) != physical) throw stop();
                    return new Limits(borrowed, global, session, ((Integer) packet).longValue());
                }
            }
        } catch (WorkflowException failure) { throw failure; }
        catch (ReflectiveOperationException | SQLException | RuntimeException failure) { throw stop(); }
    }

    private static void verifyNoConversionColumns(Connection borrowed, String schema, Set<ReadColumn> columns, Limits limits) throws SQLException {
        if (columns.isEmpty() || columns.contains(null)) throw stop();
        // 固定枚举至多八列，单次有界查询；逐行核实库、表、列和字符集，缺失或重复都停止。
        Set<ReadColumn> expected = EnumSet.copyOf(columns);
        StringJoiner predicates = new StringJoiner(" OR ");
        List<Object> parameters = new ArrayList<>();
        for (ReadColumn column : expected) {
            predicates.add("(BINARY table_name=? AND BINARY column_name=?)");
            parameters.add(column.table); parameters.add(column.column);
        }
        String sql = "SELECT table_schema,table_name,column_name,character_set_name FROM information_schema.columns WHERE BINARY table_schema=BINARY DATABASE() AND ("
                + predicates + ") LIMIT " + (expected.size() + 1);
        limits.requireStatement(borrowed, sql, parameters.toArray());
        try (PreparedStatement statement = borrowed.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int i = 0; i < parameters.size(); i++) statement.setString(i + 1, (String) parameters.get(i));
            Set<ReadColumn> observed = EnumSet.noneOf(ReadColumn.class);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (!schema.equals(row.getString(1)) || !"utf8mb4".equals(row.getString(4))) throw stop();
                    ReadColumn match = null;
                    for (ReadColumn column : expected) {
                        if (column.table.equals(row.getString(2)) && column.column.equals(row.getString(3))) { match = column; break; }
                    }
                    if (match == null || !observed.add(match)) throw stop();
                }
            }
            if (!observed.equals(expected)) throw stop();
        }
    }

    /** 所有参数都计入；固定 SQL、类型标签和协议开销另作保守预留，不接受流或任意对象。 */
    public static long wireBound(String sql, Object... parameters) {
        if (sql == null || parameters == null) throw stop();
        long allowance = 1024L + 2L * VisitQuestionContracts.utf8(sql) + 32L * parameters.length;
        if (allowance > 64 * 1024) throw stop();
        long payload = 0;
        for (Object value : parameters) {
            long bytes;
            if (value == null) bytes = 4;
            else if (value instanceof String) bytes = VisitQuestionContracts.utf8((String) value);
            else if (value instanceof byte[]) bytes = ((byte[]) value).length;
            else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal || value instanceof Boolean)
                bytes = VisitQuestionContracts.utf8(value.toString());
            else if (value instanceof Timestamp || value instanceof java.sql.Date || value instanceof java.time.Instant) bytes = 64;
            else throw stop();
            if (bytes > Long.MAX_VALUE / 2 - payload) throw stop();
            payload += bytes;
        }
        if (payload > (Long.MAX_VALUE - allowance) / 2) throw stop();
        return 2 * payload + allowance;
    }

    public static void requireStatement(Connection connection, String sql, Object... parameters) {
        inspect(connection).requireStatement(connection, sql, parameters);
    }
    private static boolean supportedVersion(String version) {
        return version != null && ("5.7.18".equals(version) || version.startsWith("5.7.18-"))
                && !version.toLowerCase(Locale.ROOT).contains("mariadb");
    }
    private static WorkflowException stop() {
        return new WorkflowException(503, "QUESTION_TRANSPORT_NOT_READY", "The executing question connection could not be verified; no change was made.");
    }
}
