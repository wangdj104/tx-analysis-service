package org.familyhealthcare.service.workflow;

import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real transaction boundaries with synthetic JDBC metadata; not native MySQL acceptance. */
class NotificationSafetyPrimitivesTest {
    private static final Instant FIRST = Instant.parse("2026-10-13T12:00:00.123Z");
    private static final String REQUEST = "12345678-1234-1234-1234-123456789abc:" + FIRST.toEpochMilli();

    @Test void cycleIdentityRestoresTheFirstAttemptMillisecondExactly() {
        assertEquals(FIRST, firstAttempt(REQUEST));
    }

    @Test void retryableFailureHasOnlyTwoRetriesAnchoredToTheFirstAttempt() {
        assertEquals(FIRST.plusSeconds(60), nextAttempt(REQUEST, "FAILED", true, 1));
        assertEquals(FIRST.plusSeconds(300), nextAttempt(REQUEST, "FAILED", true, 2));
        assertNull(nextAttempt(REQUEST, "FAILED", true, 3));
        assertNull(nextAttempt(REQUEST, "FAILED", true, 4));
    }

    @Test void unknownAndNonRetryableOutcomesNeverRetryAutomatically() {
        for (String outcome : Arrays.asList("UNKNOWN", "DELIVERED", "SUPPRESSED", "NO_CHANNEL"))
            assertNull(nextAttempt(REQUEST, outcome, true, 1));
        assertNull(nextAttempt(REQUEST, "FAILED", false, 1));
    }

    @Test void corruptCycleIdentityFailsClosedBeforeSchedulingARetry() {
        for (String request : Arrays.asList(null, "", "not-a-uuid:1", "1-1-1-1-1:1", "12345678-1234-1234-1234-123456789ABC:1", "12345678-1234-1234-1234-123456789abc:01", REQUEST + ":extra")) {
            assertThrows(IllegalStateException.class, () -> firstAttempt(request));
            assertThrows(IllegalStateException.class, () -> nextAttempt(request, "FAILED", true, 1));
        }
    }

    @Test void mysql57ClaimBoundsItsWaitAndExplicitlyRestoresTheSessionBeforeCommit() throws Exception {
        try (ClaimSession session = new ClaimSession()) {
            session.transaction.execute(status -> {
                NotificationClaim claim = acquire(session.jdbc);
                assertEquals("FOR UPDATE", claim.getClause());
                assertEquals(1, session.timeout);
                restore(claim);
                assertEquals(47, session.timeout);
                return null;
            });
            assertEquals(Arrays.asList("timeout:1", "timeout:47", "commit"), session.events);
        }
    }

    @Test void mysql57FailedRestoreAbortsThePhysicalConnectionAndPreservesTheCause() throws Exception {
        try (ClaimSession session = new ClaimSession()) {
            session.restoreFailure = new SQLException("Synthetic restore failure", "HY000", 1205);
            TransactionSystemException transactionFailure = assertThrows(TransactionSystemException.class, () -> session.transaction.execute(status -> {
                restore(acquire(session.jdbc)); return null;
            }));
            // Physical abort makes Spring's rollback fail; the original restore failure remains attached.
            Throwable error = transactionFailure.getApplicationException();
            assertTrue(error instanceof IllegalStateException);
            assertSame(session.restoreFailure, error.getCause());
            assertTrue(session.connection.isClosed());
            assertEquals(Arrays.asList("timeout:1", "restore-failed", "abort", "rollback"), session.events);
        }
    }

    @Test void mysql57RestoreFailureRetainsAnAbortFailureAsSuppressed() throws Exception {
        try (ClaimSession session = new ClaimSession()) {
            session.restoreFailure = new SQLException("Synthetic restore failure", "HY000", 1205);
            session.abortFailure = new SQLException("Synthetic abort failure", "08006");
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> session.transaction.execute(status -> {
                restore(acquire(session.jdbc)); return null;
            }));
            assertSame(session.restoreFailure, error.getCause());
            assertArrayEquals(new Throwable[]{session.abortFailure}, session.restoreFailure.getSuppressed());
            assertFalse(session.events.contains("commit"));
        }
    }

    @Test void h2ClaimRestoresItsRealTestSessionTimeout() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:timeout_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.execute(status -> {
            jdbc.execute("SET LOCK_TIMEOUT 417");
            NotificationClaim claim = acquire(jdbc);
            assertEquals("FOR UPDATE NOWAIT", claim.getClause());
            assertEquals(1, jdbc.queryForObject("CALL LOCK_TIMEOUT()", Integer.class));
            restore(claim);
            assertEquals(417, jdbc.queryForObject("CALL LOCK_TIMEOUT()", Integer.class));
            return null;
        });
    }

    @Test void carePlanRecoveryMakesAnAttemptedExpiredClaimUnknownAndOnlyRequeuesUnattemptedWork() {
        Instant now = FIRST.plusSeconds(1000);
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:recovery_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE care_plan_notification(id BIGINT,status VARCHAR(30),last_result VARCHAR(40),next_attempt_at TIMESTAMP,claim_token VARCHAR(36),claimed_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO care_plan_notification VALUES(1,'CLAIMED','ATTEMPT_STARTED',NULL,'old',?,NULL)", ps -> ps.setTimestamp(1, Timestamp.from(now.minusSeconds(300)), Calendar.getInstance(TimeZone.getTimeZone("UTC"))));
        jdbc.update("INSERT INTO care_plan_notification VALUES(2,'CLAIMED','CLAIMED_UNATTEMPTED',NULL,'old',?,NULL)", ps -> ps.setTimestamp(1, Timestamp.from(now.minusSeconds(301)), Calendar.getInstance(TimeZone.getTimeZone("UTC"))));
        jdbc.update("INSERT INTO care_plan_notification VALUES(3,'CLAIMED','ATTEMPT_STARTED',NULL,'recent',?,NULL)", ps -> ps.setTimestamp(1, Timestamp.from(now.minusSeconds(299)), Calendar.getInstance(TimeZone.getTimeZone("UTC"))));
        CarePlanProperties properties = new CarePlanProperties(true, Clock.systemUTC());
        CarePlanNotificationWorker worker = new CarePlanNotificationWorker(jdbc, new CarePlanAuthorizationService(jdbc, properties), properties,
                (channel, event, title, path) -> { throw new AssertionError("Recovery must not send"); });
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.execute(status -> { ReflectionTestUtils.invokeMethod(worker, "recover", now); return null; });
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM care_plan_notification WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT next_attempt_at FROM care_plan_notification WHERE id=1", Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT claim_token FROM care_plan_notification WHERE id=1", String.class));
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM care_plan_notification WHERE id=2", String.class));
        assertEquals(now, jdbc.queryForObject("SELECT next_attempt_at FROM care_plan_notification WHERE id=2", (rs, index) -> rs.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant()));
        assertEquals("CLAIMED", jdbc.queryForObject("SELECT status FROM care_plan_notification WHERE id=3", String.class));
    }

    private Instant firstAttempt(String request) { return NotificationAttemptPolicy.firstAttempt(request); }
    private Instant nextAttempt(String request, String outcome, boolean retryable, int attempts) {
        return NotificationAttemptPolicy.nextAttempt(request, outcome, retryable, attempts);
    }
    private NotificationClaim acquire(JdbcTemplate jdbc) { return NotificationClaim.acquire(jdbc); }
    private void restore(NotificationClaim claim) { claim.restore(); }

    private static final class ClaimSession implements AutoCloseable {
        final Connection connection;
        final JdbcTemplate jdbc;
        final TransactionTemplate transaction;
        final List<String> events = new ArrayList<>();
        int timeout = 47;
        SQLException restoreFailure, abortFailure;

        ClaimSession() throws Exception {
            connection = DriverManager.getConnection("jdbc:h2:mem:primitive_claim_" + UUID.randomUUID());
            Connection boundary = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if ("getMetaData".equals(method.getName())) {
                    DatabaseMetaData metadata = connection.getMetaData();
                    return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (ignored, operation, values) -> {
                        if ("getDatabaseProductName".equals(operation.getName())) return "MySQL";
                        if ("getDatabaseMajorVersion".equals(operation.getName())) return 5;
                        if ("getDatabaseMinorVersion".equals(operation.getName())) return 7;
                        return invoke(metadata, operation, values);
                    });
                }
                if ("createStatement".equals(method.getName())) {
                    Statement delegate = connection.createStatement();
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (ignored, operation, values) -> {
                        if (values != null && values.length > 0 && values[0] instanceof String) {
                            String sql = (String) values[0];
                            if ("SELECT @@SESSION.innodb_lock_wait_timeout".equals(sql)) return delegate.executeQuery("SELECT " + timeout);
                            String prefix = "SET SESSION innodb_lock_wait_timeout = ";
                            if (sql.startsWith(prefix)) {
                                assertFalse(connection.getAutoCommit(), "Use the current transaction connection");
                                int next = Integer.parseInt(sql.substring(prefix.length()));
                                if (next == 47 && restoreFailure != null) { events.add("restore-failed"); throw restoreFailure; }
                                timeout = next; events.add("timeout:" + next); return false;
                            }
                        }
                        return invoke(delegate, operation, values);
                    });
                }
                if ("abort".equals(method.getName())) {
                    events.add("abort");
                    if (abortFailure != null) throw abortFailure;
                    connection.close(); return null;
                }
                if ("commit".equals(method.getName()) || "rollback".equals(method.getName())) events.add(method.getName());
                return invoke(connection, method, args);
            });
            jdbc = new JdbcTemplate(new SingleConnectionDataSource(boundary, true));
            transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        }
        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        @Override public void close() throws SQLException { connection.close(); }
    }
}
