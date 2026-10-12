package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** JDBC dialect/transaction boundary tests; native InnoDB acceptance lives in the MySQL integration suite. */
class CarePlanNotificationLockTest {
    @Test void mysql57BoundsTheClaimAndRestoresTheOriginalSessionTimeoutBeforeCommit() throws Exception {
        try (ClaimSession session = new ClaimSession(5, 7)) {
            assertNull(assertDoesNotThrow(session::claim));
            assertTrue(session.claimSql.endsWith("FOR UPDATE"));
            assertEquals(Collections.singletonList(1), session.timeoutsAtClaim);
            assertEquals(Arrays.asList("timeout:1", "claim", "timeout:47", "commit"), session.events);
            assertEquals(47, session.timeout);
            assertTrue(session.connection.getAutoCommit());
        }
    }

    @Test void mysql57LockTimeoutRestoresTheSessionAndRollsBackTheWholeClaim() throws Exception {
        try (ClaimSession session = new ClaimSession(5, 7)) {
            session.failure = new SQLException("Synthetic InnoDB row lock timeout", "HY000", 1205);
            assertNull(assertDoesNotThrow(session::claim), "A busy claim is deferred without failing the worker");
            assertEquals(Arrays.asList("timeout:1", "claim", "timeout:47", "rollback"), session.events);
            assertEquals(47, session.timeout);
            assertEquals(0, session.jdbc.queryForObject("SELECT COUNT(*) FROM claim_marker", Integer.class));
        }
    }

    @Test void mysql57NonLockFailureStillRestoresAndRollsBackRatherThanBeingSwallowed() throws Exception {
        try (ClaimSession session = new ClaimSession(5, 7)) {
            session.failure = new SQLException("Synthetic invalid column", "42S22", 1054);
            assertThrows(DataAccessException.class, session::claim);
            assertEquals(Arrays.asList("timeout:1", "claim", "timeout:47", "rollback"), session.events);
            assertEquals(47, session.timeout);
            assertEquals(0, session.jdbc.queryForObject("SELECT COUNT(*) FROM claim_marker", Integer.class));
        }
    }

    @Test void restorationFailureAbortsThePhysicalConnectionInsteadOfReturningBusy() throws Exception {
        try (ClaimSession session = new ClaimSession(5, 7)) {
            session.restoreFailure = new SQLException("Synthetic restore interruption", "HY000", 1205);
            IllegalStateException error = assertThrows(IllegalStateException.class, session::claim);
            assertSame(session.restoreFailure, error.getCause());
            assertTrue(session.connection.isClosed(), "The dirty physical connection must not return to a pool");
            assertEquals(Arrays.asList("timeout:1", "claim", "restore-failed", "abort", "rollback"), session.events);
            assertFalse(session.events.contains("commit"));
        }
    }

    @Test void restorationFailureRetainsAbortFailureAsSuppressedAndNeverCommits() throws Exception {
        try (ClaimSession session = new ClaimSession(5, 7)) {
            session.restoreFailure = new SQLException("Synthetic restore interruption", "HY000", 1205);
            session.abortFailure = new SQLException("Synthetic abort failure", "08006", 0);
            IllegalStateException error = assertThrows(IllegalStateException.class, session::claim);
            assertSame(session.restoreFailure, error.getCause());
            assertArrayEquals(new Throwable[]{session.abortFailure}, session.restoreFailure.getSuppressed());
            assertEquals(Arrays.asList("timeout:1", "claim", "restore-failed", "abort", "rollback"), session.events);
            assertFalse(session.events.contains("commit"));
        }
    }

    @Test void mysql8KeepsSkipLockedWithoutChangingSessionSettings() throws Exception {
        try (ClaimSession session = new ClaimSession(8, 0)) {
            assertNull(assertDoesNotThrow(session::claim));
            assertTrue(session.claimSql.endsWith("FOR UPDATE SKIP LOCKED"));
            assertEquals(Arrays.asList("claim", "commit"), session.events);
            assertEquals(47, session.timeout);
        }
    }

    @Test void unsupportedOrUnknownMysqlVersionFailsClosedBeforeClaiming() throws Exception {
        for (int[] version : new int[][]{{5, 6}, {0, 0}, {8, -1}}) {
            try (ClaimSession session = new ClaimSession(version[0], version[1])) {
                assertThrows(IllegalStateException.class, session::claim);
                assertNull(session.claimSql);
                assertEquals(Collections.singletonList("rollback"), session.events);
                assertEquals(47, session.timeout);
            }
        }
    }

    @Test void unavailableVersionMetadataFailsClosedBeforeClaiming() throws Exception {
        try (ClaimSession session = new ClaimSession(5, 7)) {
            session.metadataFailure = new SQLException("Synthetic unavailable version metadata", "HY000", 0);
            assertThrows(DataAccessException.class, session::claim);
            assertNull(session.claimSql);
            assertEquals(Collections.singletonList("rollback"), session.events);
            assertEquals(47, session.timeout);
        }
    }

    private static final class ClaimSession implements AutoCloseable {
        final Connection connection;
        final JdbcTemplate jdbc;
        final CarePlanNotificationWorker worker;
        final List<String> events = new ArrayList<>();
        final List<Integer> timeoutsAtClaim = new ArrayList<>();
        final int major, minor;
        int timeout = 47;
        String claimSql;
        SQLException failure, metadataFailure, restoreFailure, abortFailure;

        ClaimSession(int major, int minor) throws Exception {
            this.major = major;
            this.minor = minor;
            connection = DriverManager.getConnection("jdbc:h2:mem:claim_" + UUID.randomUUID() + ";MODE=MySQL");
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE care_plan_notification(id BIGINT,status VARCHAR(30),last_result VARCHAR(40),attempt_count INT,next_attempt_at TIMESTAMP)");
                statement.execute("CREATE TABLE claim_marker(id INT)");
            }
            Connection boundary = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if ("getMetaData".equals(method.getName())) return metadata();
                if ("abort".equals(method.getName())) {
                    events.add("abort");
                    if (abortFailure != null) throw abortFailure;
                    // Simulate a physical abort: subsequent transaction cleanup cannot use this connection.
                    ((java.util.concurrent.Executor) args[0]).execute(() -> {
                        try { connection.close(); } catch (SQLException failure) { throw new IllegalStateException(failure); }
                    });
                    return null;
                }
                if ("createStatement".equals(method.getName())) return statement((Statement) invoke(connection, method, args));
                if ("prepareStatement".equals(method.getName()) && ((String) args[0]).contains("FROM care_plan_notification")) {
                    claimSql = (String) args[0];
                    if (major == 5 && claimSql.contains("SKIP LOCKED"))
                        throw new SQLSyntaxErrorException("MySQL 5.7 has no SKIP LOCKED syntax", "42000", 1064);
                    Object[] compatible = args.clone();
                    compatible[0] = claimSql.replace(" SKIP LOCKED", "");
                    PreparedStatement delegate = (PreparedStatement) invoke(connection, method, compatible);
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (prepared, operation, values) -> {
                        if ("executeQuery".equals(operation.getName())) {
                            events.add("claim");
                            timeoutsAtClaim.add(timeout);
                            assertFalse(connection.getAutoCommit(), "Claim and timeout must use the transaction connection");
                            if (failure != null) {
                                try (Statement marker = connection.createStatement()) { marker.executeUpdate("INSERT INTO claim_marker VALUES(1)"); }
                                throw failure;
                            }
                        }
                        return invoke(delegate, operation, values);
                    });
                }
                if ("commit".equals(method.getName()) || "rollback".equals(method.getName())) events.add(method.getName());
                return invoke(connection, method, args);
            });
            jdbc = new JdbcTemplate(new SingleConnectionDataSource(boundary, true));
            CarePlanProperties properties = new CarePlanProperties(true, Clock.systemUTC());
            worker = new CarePlanNotificationWorker(jdbc, new CarePlanAuthorizationService(jdbc, properties), properties,
                    (channel, event, title, path) -> { throw new AssertionError("Claim must not send a notification"); });
        }

        Object claim() { return ReflectionTestUtils.invokeMethod(worker, "claim", Instant.now()); }

        private DatabaseMetaData metadata() throws SQLException {
            DatabaseMetaData delegate = connection.getMetaData();
            return (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                if ("getDatabaseProductName".equals(method.getName())) return "MySQL";
                if ("getDatabaseMajorVersion".equals(method.getName()) || "getDatabaseMinorVersion".equals(method.getName())) {
                    if (metadataFailure != null) throw metadataFailure;
                    return "getDatabaseMajorVersion".equals(method.getName()) ? major : minor;
                }
                return invoke(delegate, method, args);
            });
        }

        private Statement statement(Statement delegate) {
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String sql = (String) args[0];
                    if ("SELECT @@SESSION.innodb_lock_wait_timeout".equals(sql)) return delegate.executeQuery("SELECT " + timeout);
                    String prefix = "SET SESSION innodb_lock_wait_timeout = ";
                    if (sql.startsWith(prefix)) {
                        int requested = Integer.parseInt(sql.substring(prefix.length()));
                        if (requested == 47 && restoreFailure != null) {
                            events.add("restore-failed");
                            throw restoreFailure;
                        }
                        timeout = requested;
                        events.add("timeout:" + timeout);
                        return false;
                    }
                }
                return invoke(delegate, method, args);
            });
        }

        @Override public void close() throws SQLException { connection.close(); }
        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
