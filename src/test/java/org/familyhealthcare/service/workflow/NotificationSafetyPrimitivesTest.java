package org.familyhealthcare.service.workflow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.jdbc.JdbcPropertySetImpl;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.service.familyassistance.FamilyAssistanceProperties;
import org.familyhealthcare.service.familyassistance.FamilyAssistanceRepository;
import org.familyhealthcare.service.familyassistance.FamilyAssistanceService;
import org.familyhealthcare.service.medicationexecution.MedicationExecutionProperties;
import org.familyhealthcare.service.medicationexecution.MedicationExecutionRepository;
import org.familyhealthcare.service.medicationexecution.MedicationTrackedReminderAdapter;
import org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard;
import org.familyhealthcare.service.visitfollowup.VisitFollowUpSourceBridge;
import org.familyhealthcare.service.visitquestion.VisitQuestionSchema;
import org.familyhealthcare.service.workflow.HouseholdWorkflowSchema;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
        jdbc.execute("CREATE TABLE care_plan_notification(id BIGINT,status VARCHAR(30),last_result VARCHAR(40),next_attempt_at TIMESTAMP,claim_token VARCHAR(36),claimed_at TIMESTAMP,updated_at TIMESTAMP,request_id VARCHAR(100),attempt_count INT DEFAULT 0)");
        jdbc.update("INSERT INTO care_plan_notification(id,status,last_result,next_attempt_at,claim_token,claimed_at,updated_at) VALUES(1,'CLAIMED','ATTEMPT_STARTED',NULL,'old',?,NULL)", ps -> ps.setTimestamp(1, Timestamp.from(now.minusSeconds(300)), Calendar.getInstance(TimeZone.getTimeZone("UTC"))));
        jdbc.update("INSERT INTO care_plan_notification(id,status,last_result,next_attempt_at,claim_token,claimed_at,updated_at) VALUES(2,'CLAIMED','CLAIMED_UNATTEMPTED',NULL,'old',?,NULL)", ps -> ps.setTimestamp(1, Timestamp.from(now.minusSeconds(301)), Calendar.getInstance(TimeZone.getTimeZone("UTC"))));
        jdbc.update("INSERT INTO care_plan_notification(id,status,last_result,next_attempt_at,claim_token,claimed_at,updated_at) VALUES(3,'CLAIMED','ATTEMPT_STARTED',NULL,'recent',?,NULL)", ps -> ps.setTimestamp(1, Timestamp.from(now.minusSeconds(299)), Calendar.getInstance(TimeZone.getTimeZone("UTC"))));
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
        assertNull(jdbc.queryForObject("SELECT claimed_at FROM care_plan_notification WHERE id=1", Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT claimed_at FROM care_plan_notification WHERE id=2", Timestamp.class));
        assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM care_plan_notification WHERE id=2", Integer.class));
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

    @Nested
    class SchemaDiagnostics {
        @Test
        void retainsJdbcFailureCodesWithoutLoggingSensitiveMessages(){
            SQLException sql=new SQLException("jdbc:mysql://private-host/db?password=secret; patient=value","HYT00",3024);
            RuntimeException wrapped=new IllegalStateException("sensitive SQL and credentials",sql);
            String diagnostic=HouseholdWorkflowSchema.diagnosticCause(wrapped);
            assertEquals("java.lang.IllegalStateException; jdbcType=java.sql.SQLException; sqlState=HYT00; vendorCode=3024",diagnostic);
            assertFalse(diagnostic.contains("private-host"));
            assertFalse(diagnostic.contains("secret"));
            assertFalse(diagnostic.contains("patient"));
            assertFalse(diagnostic.contains("credentials"));
        }

        @Test
        void rejectsNonCodeSqlStateContent(){
            String diagnostic=HouseholdWorkflowSchema.diagnosticCause(new SQLException("private data","bad\nsecret",0));
            assertTrue(diagnostic.contains("sqlState=UNAVAILABLE"));
            assertFalse(diagnostic.contains("secret"));
            assertEquals("CONTRACT_MISMATCH",HouseholdWorkflowSchema.diagnosticCause(null));
        }

        @Test
        void throttlesOnlyDiagnosticsForOneMinute(){
            HouseholdWorkflowSchema.DiagnosticLimiter limiter=new HouseholdWorkflowSchema.DiagnosticLimiter();
            long start=-20L,minute=TimeUnit.MINUTES.toNanos(1);
            assertTrue(limiter.allow(start));
            assertFalse(limiter.allow(start));
            assertFalse(limiter.allow(start+minute-1));
            assertTrue(limiter.allow(start+minute));
            assertFalse(limiter.allow(start+minute));
        }

        @Test
        void repeatedUnreadableInspectionsStillFailClosed(){
            for(int i=0;i<3;i++){
                HouseholdWorkflowSchema.Compatibility result=HouseholdWorkflowSchema.inspectCompatibility(null);
                assertEquals(HouseholdWorkflowSchema.Profile.INCOMPATIBLE,result.getProfile());
                assertEquals(Collections.singletonList("METADATA_UNREADABLE"),result.getReasons());
            }
        }
    }

    @Nested
    class SchemaConnectionFailures {
        private Logger logger;
        private Level previousLevel;
        private ListAppender<ILoggingEvent> diagnostic;

        @BeforeEach void captureDiagnostics() throws Exception {
            logger = (Logger) LoggerFactory.getLogger(HouseholdWorkflowSchema.class);
            previousLevel = logger.getLevel();
            logger.setLevel(Level.WARN);
            diagnostic = new ListAppender<>();
            diagnostic.start();
            logger.addAppender(diagnostic);
            Field limiterField = HouseholdWorkflowSchema.class.getDeclaredField("DIAGNOSTICS");
            limiterField.setAccessible(true);
            Object limiter = limiterField.get(null);
            Field lastField = limiter.getClass().getDeclaredField("last");
            lastField.setAccessible(true);
            ((AtomicReference<?>) lastField.get(limiter)).set(null);
        }

        @AfterEach void restoreDiagnostics() {
            logger.detachAppender(diagnostic);
            logger.setLevel(previousLevel);
            diagnostic.stop();
        }

        @ParameterizedTest
        @ValueSource(strings = {"timeout", "connection", "syntax"})
        void originalJdbcFailureIsReportedWithoutRediscoveringDatabaseMetadata(String kind) throws Exception {
            SQLException failure = failure(kind);
            Connection connection = mock(Connection.class);
            failMetadataQuery(connection, failure, 2, !"syntax".equals(kind));

            HouseholdWorkflowSchema.Compatibility result = HouseholdWorkflowSchema.inspectCompatibility(connection);

            assertEquals(HouseholdWorkflowSchema.Profile.INCOMPATIBLE, result.getProfile());
            assertEquals(Collections.singletonList("METADATA_UNREADABLE"), result.getReasons());
            verify(connection, never()).getMetaData();
            verify(connection, never()).close();
            String message = diagnostic.list.get(0).getFormattedMessage();
            assertTrue(message.contains("queryGroup=SNAPSHOT_TABLES"), message);
            assertTrue(message.contains("jdbcType=" + failure.getClass().getName()), message);
            assertTrue(message.contains("vendorCode=" + failure.getErrorCode()), message);
            assertTrue(message.contains("cause=org.springframework."), message);
            assertFalse(message.contains("private-secret"));
            assertFalse(message.contains("SELECT private"));
        }

        @ParameterizedTest
        @CsvSource({"0,PRESENCE_HOUSEHOLD", "1,PRESENCE_FOLLOWUP", "3,SNAPSHOT_COLUMNS"})
        void diagnosticIdentifiesOnlyTheFixedFailedQueryGroup(int failedQuery, String expectedGroup) throws Exception {
            Connection connection = mock(Connection.class);
            failMetadataQuery(connection, failure("timeout"), failedQuery, true);
            HouseholdWorkflowSchema.Compatibility result = HouseholdWorkflowSchema.inspectCompatibility(connection);
            assertEquals(HouseholdWorkflowSchema.Profile.INCOMPATIBLE, result.getProfile());
            assertTrue(diagnostic.list.get(0).getFormattedMessage().contains("queryGroup=" + expectedGroup));
            verify(connection, never()).getMetaData();
            verify(connection, never()).close();
        }

        @Test void borrowedConnectionStaysOpenThroughoutInspectionAndIsReleasedByItsOwner() throws Exception {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenAnswer(call -> {
                Statement statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query -> {
                    verify(connection, never()).close();
                    return scalar(0);
                });
                return statement;
            });

            HouseholdWorkflowSchema.StorageReadiness result = new HouseholdWorkflowSchema(new JdbcTemplate(dataSource)).inspectStorageMetadata();

            assertEquals(HouseholdWorkflowSchema.StorageStatus.ABSENT, result.getStatus());
            verify(dataSource, times(1)).getConnection();
            verify(connection, times(1)).close();
            verify(connection, never()).getMetaData();
        }

        @Test void questionReadinessPreservesItsFailureWithoutAdditionalMetadataAccess() throws Exception {
            JdbcConnection connection = mock(JdbcConnection.class);
            when(connection.isWrapperFor(JdbcConnection.class)).thenReturn(true);
            when(connection.unwrap(JdbcConnection.class)).thenReturn(connection);
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getDriverName()).thenReturn("MySQL Connector/J");
            when(metadata.getDriverVersion()).thenReturn("mysql-connector-java-8.0.29");
            when(metadata.getConnection()).thenReturn(connection);
            JdbcPropertySetImpl properties = new JdbcPropertySetImpl();
            Properties values = new Properties();
            values.setProperty("maxAllowedPacket", "4194304");
            values.setProperty("characterEncoding", "UTF-8");
            properties.initializeProperties(values);
            when(connection.getPropertySet()).thenReturn(properties);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                PreparedStatement statement = mock(PreparedStatement.class);
                ResultSet rows = scalar(1);
                if (((String) call.getArgument(0)).startsWith("SELECT VERSION()")) {
                    when(rows.getString(1)).thenReturn("5.7.18-txsql-log");
                    when(rows.getString(2)).thenReturn("synthetic");
                    when(rows.getLong(3)).thenReturn(4194304L);
                    when(rows.getLong(4)).thenReturn(4194304L);
                    when(rows.getString(5)).thenReturn("utf8mb4");
                    when(rows.getString(6)).thenReturn("utf8mb4");
                    when(rows.getString(7)).thenReturn("utf8mb4");
                }
                when(statement.executeQuery()).thenReturn(rows);
                return statement;
            });
            failMetadataQuery(connection, failure("timeout"), 2, true);

            WorkflowException failure = assertThrows(WorkflowException.class, () -> new VisitQuestionSchema().requireReady(connection));

            assertEquals("QUESTION_SCHEMA_NOT_READY", failure.getErrorCode());
            // Transport validation deliberately reads driver metadata once; SQL translation must not read it again.
            verify(connection, times(1)).getMetaData();
            verify(connection, never()).close();
            assertTrue(diagnostic.list.get(0).getFormattedMessage().contains("jdbcType=java.sql.SQLTimeoutException"));
        }

        private void failMetadataQuery(Connection connection, SQLException failure, int failedQuery, boolean evict) throws SQLException {
            AtomicBoolean closed = new AtomicBoolean();
            AtomicInteger queryNumber = new AtomicInteger();
            when(connection.isClosed()).thenAnswer(call -> closed.get());
            when(connection.createStatement()).thenAnswer(call -> {
                Statement statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query -> {
                    verify(connection, never()).close();
                    int number = queryNumber.getAndIncrement();
                    if (number == failedQuery) {
                        closed.set(evict);
                        // Hikari replaces its delegate after a fatal error; later metadata reads cannot recover it.
                        if (evict) when(connection.getMetaData()).thenThrow(new SQLException("Connection is closed"));
                        throw failure;
                    }
                    return number < 2 ? scalar(1) : emptyRows();
                });
                return statement;
            });
        }

        private SQLException failure(String kind) {
            String message = "SELECT private FROM private-secret";
            if ("timeout".equals(kind)) return new SQLTimeoutException(message, null, 0);
            if ("connection".equals(kind)) return new SQLNonTransientConnectionException(message, "08006", 77);
            return new SQLSyntaxErrorException(message, "42000", 1064);
        }

        private ResultSet scalar(long value) throws SQLException {
            ResultSet rows = mock(ResultSet.class);
            ResultSetMetaData metadata = mock(ResultSetMetaData.class);
            when(metadata.getColumnCount()).thenReturn(1);
            when(rows.getMetaData()).thenReturn(metadata);
            when(rows.next()).thenReturn(true, false);
            when(rows.getLong(1)).thenReturn(value);
            return rows;
        }

        private ResultSet emptyRows() throws SQLException {
            ResultSet rows = mock(ResultSet.class);
            when(rows.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
            return rows;
        }
    }

    @Nested
    class MetadataTimeoutRecovery {
        private final Object boundResource = new Object();

        @AfterEach void clearTestTransactionContext() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations())
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.unbindResourceIfPossible(boundResource);
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty(), "Test transaction resources must be released");
        }

        @Test void retriesTheWholeReadOnlyAfterTheFailedBorrowIsReleased() throws Exception {
            AtomicInteger firstReads = new AtomicInteger(), secondReads = new AtomicInteger();
            Connection first = failing(new SQLTimeoutException("Synthetic timeout"), firstReads);
            Connection second = absent(secondReads);
            HikariDataSource pool = pool(first, second);

            HouseholdWorkflowSchema.StorageReadiness result = new HouseholdWorkflowSchema(new JdbcTemplate(pool)).inspectStorageMetadata();

            assertEquals(HouseholdWorkflowSchema.StorageStatus.ABSENT, result.getStatus());
            assertEquals(3, firstReads.get());
            assertEquals(2, secondReads.get(), "Both presence checks must run again on the fresh connection");
            verify(pool, times(2)).getConnection();
            verify(first, times(1)).close();
            verify(second, times(1)).close();
            verify(first, never()).getMetaData();
            verify(second, never()).getMetaData();
        }

        @Test void secondAttemptMustCompleteItsOwnSnapshotAndCannotReusePartialSuccess() throws Exception {
            AtomicInteger firstReads = new AtomicInteger(), secondReads = new AtomicInteger();
            Connection first = failing(new SQLTimeoutException("Synthetic timeout"), firstReads);
            Connection second = incompleteSchema(secondReads);
            HikariDataSource pool = pool(first, second);

            HouseholdWorkflowSchema.StorageReadiness result = new HouseholdWorkflowSchema(new JdbcTemplate(pool)).inspectStorageMetadata();

            assertEquals(HouseholdWorkflowSchema.StorageStatus.INCOMPATIBLE, result.getStatus());
            assertEquals(11, secondReads.get(), "Two presence checks and all nine snapshot groups must run again");
            assertFalse(result.getReasons().isEmpty());
            assertNotEquals(Collections.singletonList("METADATA_UNREADABLE"), result.getReasons());
            verify(pool, times(2)).getConnection();
        }

        @Test void secondTimeoutStopsWithoutAThirdBorrow() throws Exception {
            Connection first = failing(new SQLTimeoutException("First timeout"), new AtomicInteger());
            Connection second = failing(new SQLTimeoutException("Second timeout"), new AtomicInteger());
            HikariDataSource pool = pool(first, second);

            HouseholdWorkflowSchema.StorageReadiness result = new HouseholdWorkflowSchema(new JdbcTemplate(pool)).inspectStorageMetadata();

            assertEquals(HouseholdWorkflowSchema.StorageStatus.INCOMPATIBLE, result.getStatus());
            assertEquals(Collections.singletonList("METADATA_UNREADABLE"), result.getReasons());
            verify(pool, times(2)).getConnection();
            verify(second, times(1)).close();
        }

        @ParameterizedTest
        @ValueSource(strings = {"active", "synchronization", "bound", "autocommit"})
        void transactionOrCallerContextPreventsRetry(String context) throws Exception {
            Connection first = failing(new SQLTimeoutException("Synthetic timeout"), new AtomicInteger());
            HikariDataSource pool = pool(first, absent(new AtomicInteger()));
            if ("active".equals(context)) TransactionSynchronizationManager.setActualTransactionActive(true);
            if ("synchronization".equals(context)) TransactionSynchronizationManager.initSynchronization();
            if ("bound".equals(context)) TransactionSynchronizationManager.bindResource(boundResource, new Object());
            if ("autocommit".equals(context)) when(first.getAutoCommit()).thenReturn(false);

            HouseholdWorkflowSchema.StorageReadiness result = new HouseholdWorkflowSchema(new JdbcTemplate(pool)).inspectStorageMetadata();

            assertEquals(HouseholdWorkflowSchema.StorageStatus.INCOMPATIBLE, result.getStatus());
            verify(pool, times(1)).getConnection();
            if (!"autocommit".equals(context)) verify(first, never()).getAutoCommit();
        }

        @Test void callerPinnedSingleConnectionIsNeverReborrowedOrClosed() throws Exception {
            AtomicInteger reads = new AtomicInteger();
            Connection pinned = failing(new SQLTimeoutException("Synthetic timeout"), reads);

            HouseholdWorkflowSchema.StorageReadiness result = new HouseholdWorkflowSchema(
                    new JdbcTemplate(new SingleConnectionDataSource(pinned, true))).inspectStorageMetadata();

            assertEquals(HouseholdWorkflowSchema.StorageStatus.INCOMPATIBLE, result.getStatus());
            assertEquals(3, reads.get());
            verify(pinned, never()).close();
            verify(pinned, never()).getAutoCommit();
        }

        @Test void compatibilityProofInspectionKeepsTheOriginalConnectionOutcome() throws Exception {
            Connection first = failing(new SQLTimeoutException("Synthetic timeout"), new AtomicInteger());
            HikariDataSource pool = pool(first, absent(new AtomicInteger()));

            HouseholdWorkflowSchema.Compatibility result = new HouseholdWorkflowSchema(new JdbcTemplate(pool)).inspectCompatibility();

            assertEquals(HouseholdWorkflowSchema.Profile.INCOMPATIBLE, result.getProfile());
            verify(pool, times(1)).getConnection();
            verify(first, never()).getAutoCommit();
        }

        @ParameterizedTest
        @ValueSource(strings = {"connection", "syntax", "unknown", "mismatch"})
        void otherFailuresAndSchemaMismatchesDoNotRetry(String kind) throws Exception {
            SQLException failure = "connection".equals(kind) ? new SQLNonTransientConnectionException("Synthetic connection failure", "08006")
                    : "syntax".equals(kind) ? new SQLSyntaxErrorException("Synthetic syntax failure", "42000", 1064)
                    : new SQLException("Synthetic unknown failure");
            Connection first = "mismatch".equals(kind) ? incompleteSchema(new AtomicInteger()) : failing(failure, new AtomicInteger());
            HikariDataSource pool = pool(first, absent(new AtomicInteger()));

            HouseholdWorkflowSchema.StorageReadiness result = new HouseholdWorkflowSchema(new JdbcTemplate(pool)).inspectStorageMetadata();

            assertEquals(HouseholdWorkflowSchema.StorageStatus.INCOMPATIBLE, result.getStatus());
            verify(pool, times(1)).getConnection();
        }

        private HikariDataSource pool(Connection first, Connection second) throws SQLException {
            HikariDataSource pool = mock(HikariDataSource.class);
            AtomicInteger borrowed = new AtomicInteger();
            when(pool.getConnection()).thenAnswer(call -> {
                int count = borrowed.getAndIncrement();
                if (count == 0) return first;
                assertEquals(1, count, "Only one additional borrow is permitted");
                verify(first, times(1)).close();
                return second;
            });
            return pool;
        }

        private Connection failing(SQLException failure, AtomicInteger reads) throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.createStatement()).thenAnswer(call -> {
                Statement statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query -> {
                    int count = reads.incrementAndGet();
                    if (count <= 2) return scalar(1);
                    throw failure;
                });
                return statement;
            });
            return connection;
        }

        private Connection absent(AtomicInteger reads) throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.createStatement()).thenAnswer(call -> {
                Statement statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query -> {
                    reads.incrementAndGet();
                    return scalar(0);
                });
                return statement;
            });
            return connection;
        }

        private Connection incompleteSchema(AtomicInteger reads) throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.createStatement()).thenAnswer(call -> {
                Statement statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query -> {
                    int number = reads.incrementAndGet();
                    if (number <= 2) return scalar(1);
                    if (((String) query.getArgument(0)).startsWith("SELECT DATABASE()")) return schemaRow();
                    ResultSet empty = mock(ResultSet.class);
                    when(empty.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
                    return empty;
                });
                return statement;
            });
            return connection;
        }

        private ResultSet scalar(long value) throws SQLException {
            ResultSet rows = mock(ResultSet.class);
            ResultSetMetaData metadata = mock(ResultSetMetaData.class);
            when(metadata.getColumnCount()).thenReturn(1);
            when(rows.getMetaData()).thenReturn(metadata);
            when(rows.next()).thenReturn(true, false);
            when(rows.getLong(1)).thenReturn(value);
            return rows;
        }

        private ResultSet schemaRow() throws SQLException {
            String[] keys = {"schema_name", "server_version", "raw_server_version", "session_sql_mode", "foreign_key_checks", "unique_checks", "default_collation_name"};
            Object[] values = {"synthetic", "5.7.18", "5.7.18", "STRICT_TRANS_TABLES", 1, 1, "utf8mb4_unicode_ci"};
            ResultSet rows = mock(ResultSet.class);
            ResultSetMetaData metadata = mock(ResultSetMetaData.class);
            when(rows.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(keys.length);
            for (int i = 0; i < keys.length; i++) {
                when(metadata.getColumnLabel(i + 1)).thenReturn(keys[i]);
                when(rows.getObject(i + 1)).thenReturn(values[i]);
            }
            when(rows.next()).thenReturn(true, false);
            return rows;
        }
    }

    @Nested
    class MedicationRepositoryProxies {
        private Connection connection;
        private Statement statement;
        private MedicationTrackedReminderAdapter adapter;
        private MedicationTrackingGuard tracking;

        @BeforeEach void proxyRepository() throws Exception {
            DataSource source = mock(DataSource.class);
            connection = mock(Connection.class);
            statement = mock(Statement.class);
            when(source.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenAnswer(call -> {
                ResultSet result = mock(ResultSet.class);
                ResultSetMetaData metadata = mock(ResultSetMetaData.class);
                when(metadata.getColumnCount()).thenReturn(1);
                when(result.getMetaData()).thenReturn(metadata);
                when(result.next()).thenReturn(true, false);
                when(result.getLong(1)).thenReturn(0L);
                return result;
            });
            JdbcTemplate jdbc = new JdbcTemplate(source);
            MedicationExecutionRepository target = new MedicationExecutionRepository(
                    jdbc, null, new HouseholdWorkflowSchema(jdbc), null);
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvice(new PersistenceExceptionTranslationInterceptor(failure -> null));
            MedicationExecutionRepository repository = (MedicationExecutionRepository) factory.getProxy();
            assertTrue(AopUtils.isCglibProxy(repository));
            MedicationExecutionProperties properties = new MedicationExecutionProperties();
            adapter = new MedicationTrackedReminderAdapter(jdbc, properties, null, repository, null, null);
            tracking = new MedicationTrackingGuard(jdbc, properties, null, repository, null);
        }

        @Test void disabledLegacyTaskReadsReachTheTargetSchemaThroughTheProxy() throws Exception {
            assertDoesNotThrow(() -> adapter.ensureDailyTasks(7L, LocalDate.of(2026, 10, 24)));
            assertNotNull(tracking.legacyRead(7L));
            // Each read checks household and follow-up presence, without entering business writes.
            verify(statement, times(4)).executeQuery(anyString());
            verify(connection, never()).prepareStatement(anyString());
        }

        @Test void unreadableMetadataReturnsSchemaErrorInsteadOfNullPointerException() throws Exception {
            when(connection.createStatement()).thenThrow(new SQLException("Synthetic metadata unavailable"));
            WorkflowException failure = assertThrows(WorkflowException.class,
                    () -> adapter.ensureDailyTasks(7L, LocalDate.of(2026, 10, 24)));
            assertEquals(503, failure.getStatus());
            assertEquals("SCHEMA_NOT_READY", failure.getErrorCode());
            assertThrows(WorkflowException.class, () -> tracking.legacyRead(7L));
        }
    }

    @Nested
    class FamilyAssistanceRepositoryProxies {
        private Connection connection;
        private Statement statement;
        private FamilyAssistanceService service;

        @BeforeEach void proxyRepository() throws Exception {
            DataSource source = mock(DataSource.class);
            connection = mock(Connection.class);
            statement = mock(Statement.class);
            when(source.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenAnswer(call -> {
                ResultSet result = mock(ResultSet.class);
                ResultSetMetaData metadata = mock(ResultSetMetaData.class);
                when(metadata.getColumnCount()).thenReturn(1);
                when(result.getMetaData()).thenReturn(metadata);
                when(result.next()).thenReturn(true, false);
                when(result.getLong(1)).thenReturn(0L);
                return result;
            });
            JdbcTemplate jdbc = new JdbcTemplate(source);
            FamilyAssistanceRepository target = new FamilyAssistanceRepository(
                    jdbc, null, new HouseholdWorkflowSchema(jdbc), null);
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvice(new PersistenceExceptionTranslationInterceptor(failure -> null));
            FamilyAssistanceRepository repository = (FamilyAssistanceRepository) factory.getProxy();
            assertTrue(AopUtils.isCglibProxy(repository));
            service = new FamilyAssistanceService(jdbc, new FamilyAssistanceProperties(), null,
                    repository, null, mock(VisitFollowUpSourceBridge.class));
        }

        @Test void disabledDueTickChecksTheTargetSchemaWithoutWritingEvents() throws Exception {
            assertDoesNotThrow(() -> service.markDue(Instant.parse("2026-10-24T00:00:00Z")));
            verify(statement, times(2)).executeQuery(anyString());
            verify(connection, never()).prepareStatement(anyString());
        }

        @Test void unreadableMetadataRemainsClosedBehindTheRepositoryProxy() throws Exception {
            when(connection.createStatement()).thenThrow(new SQLException("Synthetic metadata unavailable"));
            WorkflowException failure = assertThrows(WorkflowException.class,
                    () -> service.markDue(Instant.parse("2026-10-24T00:00:00Z")));
            assertEquals(503, failure.getStatus());
            assertEquals("SCHEMA_NOT_READY", failure.getErrorCode());
            verify(connection, never()).prepareStatement(anyString());
        }
    }
}
