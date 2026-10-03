package org.familyhealthcare.service;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.familyhealthcare.service.careplan.CarePlanAuthorizationService;
import org.familyhealthcare.service.careplan.CarePlanContracts;
import org.familyhealthcare.service.careplan.CarePlanCommandStore;
import org.familyhealthcare.service.careplan.CarePlanEventStore;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.familyhealthcare.service.careplan.CarePlanQueryService;
import org.familyhealthcare.service.careplan.CarePlanService;
import org.familyhealthcare.service.careplan.CarePlanActionService;
import org.familyhealthcare.service.careplan.CarePlanNotificationWorker;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native MySQL 8.0 schema/restore, actual lifecycle/action races and actual outbox acceptance.
 * Minimal transaction-proxy contexts use real services and current database authority.
 * No Spring Boot/HTTP/UI API or real external transport is loaded by this class.
 * Run through scripts/verify-care-plan-mysql.sh in its disposable service job.
 * An unconfigured ordinary local suite explicitly skips this class; the required
 * flag turns absent/partial configuration into a failure, never an H2 fallback.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CarePlanMysqlIntegrationTest {
    private static final String[] CONFIG_KEYS = {
            "CARE_PLAN_TEST_ONLY", "CARE_PLAN_MYSQL_HOST", "CARE_PLAN_MYSQL_PORT",
            "CARE_PLAN_MYSQL_DATABASE", "CARE_PLAN_MYSQL_UPGRADE_DATABASE", "CARE_PLAN_MYSQL_RESTORE_DATABASE",
            "CARE_PLAN_MYSQL_USER", "CARE_PLAN_MYSQL_PASSWORD", "CARE_PLAN_MYSQL_CONTAINER_ID"};
    private static final List<String> CARE_TABLES = Arrays.asList("care_plan_revision", "care_plan_action",
            "care_plan_event", "care_plan_evidence", "care_nurse_assignment", "care_plan_notification", "care_plan_command");
    private static final String MIGRATION = "sql/care_plan_collaboration_20261003.sql";
    private static final String[] LEGACY_CHAIN = {"sql/init.sql", "sql/doctor_workspace_20260921.sql",
            "sql/care_platform_upgrade_20260921.sql", "sql/functional_review_repair_20260922.sql",
            "sql/notification_language_preference_20260922.sql", "sql/role_permission_cleanup_20260922.sql",
            "sql/patient_specialty_roles_20260923.sql"};
    private String host, port, fresh, upgrade, restore, user, password, container;
    private final Set<String> concurrentConnectionIds = ConcurrentHashMap.newKeySet();

    @BeforeAll void requireDisposableNativeMysql() throws Exception {
        Map<String, String> env = System.getenv();
        boolean configured = Arrays.stream(CONFIG_KEYS).anyMatch(env::containsKey);
        boolean required = Boolean.getBoolean("carePlanMysqlRequired");
        if (!configured && !required) {
            Assumptions.assumeTrue(false, "Native MySQL 8.0 acceptance SKIPPED: no explicit synthetic configuration; H2 is not native acceptance");
        }
        assertTrue(configured, "Required native MySQL configuration is absent; run the guarded disposable service script");
        for (String key : CONFIG_KEYS) assertNotNull(env.get(key), "Missing required synthetic test configuration: " + key);
        assertEquals("true", env.get("CARE_PLAN_TEST_ONLY"), "CARE_PLAN_TEST_ONLY=true is mandatory");
        assertEquals("true", env.get("CI"), "Native acceptance requires the disposable CI service");
        assertEquals("true", env.get("GITHUB_ACTIONS"), "Native acceptance requires the disposable GitHub service");
        host = env.get("CARE_PLAN_MYSQL_HOST"); port = env.get("CARE_PLAN_MYSQL_PORT");
        assertTrue(Arrays.asList("127.0.0.1", "localhost", "::1", "mysql").contains(host), "Only test loopback/service hosts are allowed");
        assertTrue(port.matches("[1-9][0-9]{0,4}") && Integer.parseInt(port) <= 65535, "Invalid explicit test port");
        fresh = database(env.get("CARE_PLAN_MYSQL_DATABASE"));
        upgrade = database(env.get("CARE_PLAN_MYSQL_UPGRADE_DATABASE"));
        restore = database(env.get("CARE_PLAN_MYSQL_RESTORE_DATABASE"));
        assertEquals(3, new HashSet<>(Arrays.asList(fresh, upgrade, restore)).size(), "Synthetic database names must be distinct");
        user = env.get("CARE_PLAN_MYSQL_USER"); password = env.get("CARE_PLAN_MYSQL_PASSWORD");
        assertTrue(user.matches("care_plan_test_[a-z0-9_]+") && user.length() <= 32, "Only an ephemeral synthetic non-root user is allowed");
        assertTrue(password.matches("[a-zA-Z0-9]{24,128}"), "A generated synthetic test password is required");
        container = env.get("CARE_PLAN_MYSQL_CONTAINER_ID");
        assertTrue(container.matches("[a-f0-9]{12,64}"), "The exact disposable service container ID is required");
        String image = command(Arrays.asList("docker", "inspect", "--format", "{{.Config.Image}}", container), null, null).trim();
        assertTrue(image.equals("mysql:8.0") || image.startsWith("mysql:8.0@sha256:"), "Only the official MySQL 8.0 service is allowed");
        String serviceEnvironment = command(Arrays.asList("docker", "inspect", "--format",
                "{{range .Config.Env}}{{println .}}{{end}}", container), null, null);
        List<String> rootHostLines = new ArrayList<>();
        for (String line : serviceEnvironment.split("\\r?\\n")) if (line.startsWith("MYSQL_ROOT_HOST=")) rootHostLines.add(line);
        assertEquals(Collections.singletonList("MYSQL_ROOT_HOST=localhost"), rootHostLines,
                "Explicit local-only root configuration is required; the official image defaults absence to a wildcard");
        assertTrue(Arrays.asList(serviceEnvironment.split("\\r?\\n")).contains("MYSQL_ALLOW_EMPTY_PASSWORD=yes"),
                "Only the declared disposable empty-password service is permitted");
        assertEquals("0", socketQuery("SELECT COUNT(*) FROM mysql.user WHERE User='root' AND Host<>'localhost'"),
                "The actual account table must contain no non-local root account");
        assertEquals("0", socketQuery("SELECT @@GLOBAL.partial_revokes"), "Escaped grant names require partial_revokes=OFF");
        String serviceIdentity = socketQuery("SELECT @@server_uuid");
        assertExactNativeGrantPatterns();
        for (String db : Arrays.asList(fresh, upgrade, restore)) {
            try (Connection connection = connect(db)) {
                assertTrue(scalar(connection, "SELECT VERSION()").startsWith("8.0."), "A real MySQL 8.0 server is required");
                assertEquals(serviceIdentity, scalar(connection, "SELECT @@server_uuid"), "JDBC must reach the exact disposable service");
                assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"),
                        "The wrapper must create a new empty schema; never adopt an existing schema");
            }
        }
        try (Connection connection = connect(fresh)) { assertNoGlobalGrant(connection); }
    }

    @Test @Order(1) void freshAndExistingDatabaseApplyMigrationTwice() throws Exception {
        for (String db : Arrays.asList(fresh, upgrade)) {
            try (Connection connection = connect(db)) {
                // Executes original scripts, including MySQL information_schema,
                // session @ddl and PREPARE/EXECUTE. No H2 translation or rewritten DDL.
                for (String script : LEGACY_CHAIN) {
                    apply(connection, script);
                    if ("sql/init.sql".equals(script)) assertInitializationPrimaryKeys(connection);
                }
                List<String> legacyFields = columns(connection, "doctor_care_plan");
                if (db.equals(upgrade)) {
                    BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
                    String syntheticHash = bcrypt.encode(UUID.randomUUID().toString());
                    for (long id = 7001; id <= 7005; id++) {
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO sys_user(id,username,password,real_name) VALUES(?,?,?,?)")) {
                            insert.setLong(1, id); insert.setString(2, "care-plan-mysql-synthetic-" + id);
                            insert.setString(3, syntheticHash); insert.setString(4, "Synthetic MySQL User " + id); insert.executeUpdate();
                        }
                    }
                    apply(connection, "sql/care-plan-mysql-legacy.sql");
                }
                List<String> legacyBefore = rows(connection, "SELECT " + identifiers(legacyFields) + " FROM doctor_care_plan");
                List<String> grantsBefore = rows(connection, "SELECT * FROM care_access_grant");
                List<String> bindingsBefore = rows(connection, "SELECT * FROM sys_user_role");
                apply(connection, MIGRATION);
                Map<String, List<String>> first = comparisonSnapshot(snapshot(connection), false);
                // INSERT ... ON DUPLICATE KEY UPDATE may advance InnoDB allocators
                // without changing any logical row; this is not a migration defect.
                apply(connection, MIGRATION);
                assertEquals(first, comparisonSnapshot(snapshot(connection), false), "Repeat migration must preserve every row, key and schema relationship");
                assertEquals(legacyBefore, rows(connection, "SELECT " + identifiers(legacyFields) + " FROM doctor_care_plan"),
                        "Every populated legacy field must survive both migration applications unchanged");
                assertEquals(grantsBefore, rows(connection, "SELECT * FROM care_access_grant"));
                assertEquals(bindingsBefore, rows(connection, "SELECT * FROM sys_user_role"));
                for (String table : CARE_TABLES) assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM `" + table + "`"));
                assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM doctor_care_plan WHERE workflow_version<>0 OR lifecycle IS NOT NULL OR current_revision_id IS NOT NULL OR draft_revision_id IS NOT NULL"));
                assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM sys_role WHERE role_code='nurse'"));
                assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE r.role_code='nurse'"));
                assertNativeSchema(connection);
            }
        }
        try (Connection connection = connect(upgrade)) { apply(connection, "sql/care-plan-mysql-relationships.sql"); }
    }

    @Test @Order(2) void nativeUniqueChecksAndForeignKeysRejectInvalidRows() throws Exception {
        try (Connection c = connect(upgrade)) {
            expectMysqlError(c, 1062, "INSERT INTO care_plan_revision(plan_id,revision_no,title,instructions,plan_type,draft_json,created_by,created_at,updated_at) SELECT plan_id,revision_no,title,instructions,plan_type,draft_json,created_by,created_at,updated_at FROM care_plan_revision WHERE id=7201");
            expectMysqlError(c, 1062, "INSERT INTO care_plan_action(plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,created_at,updated_at) SELECT plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,created_at,updated_at FROM care_plan_action WHERE id=7301");
            expectMysqlError(c, 1062, "INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at) SELECT actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at FROM care_plan_command WHERE id=7901");
            expectMysqlError(c, 1062, "INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,created_at,updated_at) SELECT event_id,patient_id,recipient_user_id,dispatch_key,created_at,updated_at FROM care_plan_notification WHERE id=7801");
            expectMysqlError(c, 1062, "INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(7402,'MEASUREMENT',7601)");
            expectMysqlError(c, 1062, "INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,assigned_by,assigned_at) VALUES(7001,7004,7005,'2026-10-03 05:00:00.123456')");
            expectMysqlError(c, 1048, "UPDATE care_plan_notification SET dispatch_key=NULL WHERE id=7801");
            for (String sql : Arrays.asList("UPDATE care_plan_revision SET revision_no=0 WHERE id=7201",
                    "UPDATE care_plan_action SET ordinal=0 WHERE id=7301", "UPDATE care_plan_action SET ordinal=51 WHERE id=7301",
                    "UPDATE care_plan_action SET lock_version=-1 WHERE id=7301", "UPDATE care_plan_command SET expected_version=-1 WHERE id=7901",
                    "UPDATE care_plan_notification SET attempt_count=4 WHERE id=7801", "UPDATE care_plan_notification SET dispatch_key='' WHERE id=7801")) {
                expectMysqlError(c, 3819, sql);
            }
            // Every additive FK is tested on real rows, not merely counted.
            for (String target : Arrays.asList("care_plan_revision:plan_id:7201", "care_plan_action:plan_id:7301",
                    "care_plan_action:revision_id:7301", "care_plan_action:patient_id:7301", "care_plan_event:patient_id:7402",
                    "care_plan_event:plan_id:7402", "care_plan_event:revision_id:7402", "care_plan_event:action_id:7402",
                    "care_plan_evidence:event_id:7501", "care_nurse_assignment:patient_id:7701",
                    "care_plan_notification:event_id:7801", "care_plan_notification:patient_id:7801",
                    "care_plan_command:plan_id:7901", "doctor_care_plan:current_revision_id:7200",
                    "doctor_care_plan:draft_revision_id:7200", "doctor_care_plan:legacy_source_id:7200")) {
                String[] parts = target.split(":");
                expectMysqlError(c, 1452, "UPDATE `" + parts[0] + "` SET `" + parts[1] + "`=999999 WHERE id=" + parts[2]);
            }
            expectMysqlError(c, 1451, "DELETE FROM doctor_care_plan WHERE id=7200");
            expectMysqlError(c, 1451, "DELETE FROM care_plan_event WHERE id=7402");
            execute(c, "DELETE FROM sys_user WHERE id=7004");
            assertEquals("Synthetic Nurse Snapshot", scalar(c, "SELECT actor_name FROM care_plan_event WHERE id=7403"));
            // ASCII binary keys distinguish case while exact same keys deduplicate.
            execute(c, "INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,created_at,updated_at) VALUES(7401,7001,7001,'7401:7001:no_channel','2026-10-03 05:01:00.234567','2026-10-03 05:01:00.234567')");
            assertEquals("2", scalar(c, "SELECT COUNT(*) FROM care_plan_notification WHERE event_id=7401 AND recipient_user_id=7001"));
            execute(c, "INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(7402,'MEDICAL_RECORD',7601)");
            assertEquals("2", scalar(c, "SELECT COUNT(*) FROM care_plan_evidence WHERE event_id=7402 AND source_id=7601"));
            assertRelationships(c);
        }
    }

    @Test @Order(3) void productionUtcMicrosecondsRoundTripAcrossJvmAndSessionZones() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        int combinations = 0;
        // Invoke the package-private production helpers: a separate correct test calendar
        // would hide a regression in the application's Connector/J binding/reading path.
        Class<?> data = Class.forName("org.familyhealthcare.service.careplan.CarePlanData");
        java.lang.reflect.Method write = data.getDeclaredMethod("time", PreparedStatement.class, int.class, Instant.class);
        java.lang.reflect.Method read = data.getDeclaredMethod("time", ResultSet.class, String.class);
        write.setAccessible(true); read.setAccessible(true);
        String[][] cases = {{"1000-01-01T00:00:00Z", "1000-01-01 00:00:00.000000"},
                {"1000-01-01T05:45:00+05:45", "1000-01-01 00:00:00.000000"},
                {"1500-01-01T00:00:00.123456Z", "1500-01-01 00:00:00.123456"},
                {"2026-11-01T01:30:00.123456-04:00", "2026-11-01 05:30:00.123456"},
                {"2026-11-01T01:30:00.123456-05:00", "2026-11-01 06:30:00.123456"},
                {"9999-12-31T23:59:59.499999Z", "9999-12-31 23:59:59.499999"},
                {"9999-12-31T16:59:59.499999-07:00", "9999-12-31 23:59:59.499999"}};
        try {
            for (String jvmZone : Arrays.asList("Pacific/Honolulu", "Asia/Kathmandu")) {
                TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
                for (String sessionZone : Arrays.asList("-07:00", "+05:45")) {
                    for (boolean serverPrepared : new boolean[]{false, true}) {
                        try (Connection c = connect(upgrade, serverPrepared)) {
                            execute(c, "SET SESSION time_zone='" + sessionZone + "'");
                            for (String[] value : cases) {
                                Instant expected = CarePlanContracts.parseOffsetInstant(value[0]);
                                String context = value[0] + " jvm=" + jvmZone + " session=" + sessionZone + " serverPrepared=" + serverPrepared;
                                try (PreparedStatement ps = c.prepareStatement("UPDATE care_plan_action SET due_at=? WHERE id=7301")) {
                                    assertEquals(serverPrepared, ps.getClass().getName().contains("ServerPreparedStatement"), context);
                                    write.invoke(null, ps, 1, expected); assertEquals(1, ps.executeUpdate(), context);
                                }
                                assertEquals(value[1], scalar(c, "SELECT DATE_FORMAT(due_at,'%Y-%m-%d %H:%i:%s.%f') FROM care_plan_action WHERE id=7301"), context);
                                try (PreparedStatement ps = c.prepareStatement("SELECT due_at FROM care_plan_action WHERE id=7301")) {
                                    assertEquals(serverPrepared, ps.getClass().getName().contains("ServerPreparedStatement"), context);
                                    try (ResultSet rs = ps.executeQuery()) {
                                        assertTrue(rs.next(), context); assertEquals(expected.toString(), read.invoke(null, rs, "due_at"), context);
                                    }
                                }
                                combinations++;
                            }
                        }
                    }
                }
            }
        } finally { TimeZone.setDefault(previous); }
        assertEquals(56, combinations, "Every production-helper boundary/offset/zone/prepared-mode combination must execute");
        System.out.println("Native production-helper UTC round-trip combinations executed: " + combinations);
    }

    @Test @Order(4) void mysqlConcurrentCommandsAreIdempotent() throws Exception {
        // SQL-level locking/dedup harness only. Later Task 12 must separately
        // exercise concurrent lifecycle commands through the real service/API.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstHasLock = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), secondAttempts = new CountDownLatch(1);
        try {
            Future<Long> first = pool.submit(() -> sqlPublishWithPlanLock(firstHasLock, releaseFirst, null));
            assertTrue(firstHasLock.await(10, TimeUnit.SECONDS), "First connection must acquire the row lock");
            Future<Long> second = pool.submit(() -> sqlPublishWithPlanLock(null, null, secondAttempts));
            assertTrue(secondAttempts.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS), "Second command must block while the first holds FOR UPDATE");
            releaseFirst.countDown();
            assertEquals(Long.valueOf(8001), first.get(15, TimeUnit.SECONDS));
            assertEquals(Long.valueOf(8001), second.get(15, TimeUnit.SECONDS));
            assertEquals(2, concurrentConnectionIds.size(), "FOR UPDATE contention must use two distinct physical MySQL connections");
            concurrentConnectionIds.clear();
            try (Connection c = connect(upgrade)) {
                int publishedRevisionCount = Integer.parseInt(scalar(c, "SELECT COUNT(*) FROM care_plan_revision WHERE plan_id=8000 AND status='PUBLISHED'"));
                assertEquals(1, publishedRevisionCount);
                assertEquals("1", scalar(c, "SELECT lock_version FROM doctor_care_plan WHERE id=8000"));
                assertEquals("1", scalar(c, "SELECT COUNT(*) FROM care_plan_command WHERE plan_id=8000"));
                assertEquals("1", scalar(c, "SELECT COUNT(*) FROM care_plan_event WHERE plan_id=8000"));
            }
            // Independent simultaneous INSERTs exercise MySQL's unique-key wait,
            // with separate physical connections and no shared Java monitor.
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Boolean> a = pool.submit(() -> insertSameCommand(barrier));
            Future<Boolean> b = pool.submit(() -> insertSameCommand(barrier));
            List<Boolean> outcomes = Arrays.asList(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(outcomes, Boolean.TRUE));
            assertEquals(1, Collections.frequency(outcomes, Boolean.FALSE));
            assertEquals(2, concurrentConnectionIds.size(), "Concurrent unique INSERTs must use two distinct physical MySQL connections");
            try (Connection c = connect(upgrade)) {
                assertEquals("1", scalar(c, "SELECT COUNT(*) FROM care_plan_command WHERE command_key='00000000-0000-0000-0000-000000008002'"));
            }
        } finally { releaseFirst.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test @Order(5) void actualServiceDuplicateCreateReplaysAfterOlderAuthorizationRead() throws Exception {
        // This supplements the SQL primitive harness with the actual service,
        // command/query/event stores and real current database authorization.
        // The loser performs its ordinary authorization SELECTs before the
        // winner commits. Under an old RR snapshot the later replay refresh
        // cannot see the winning aggregate; the actual proxy must select RC.
        try (Connection connection = connect(upgrade)) {
            execute(connection, "INSERT INTO sys_user_role(user_id,role_id) SELECT 7001,id FROM sys_role WHERE role_code='patient' ON DUPLICATE KEY UPDATE role_id=VALUES(role_id)");
            execute(connection, "INSERT INTO sys_user_role(user_id,role_id) SELECT 7003,id FROM sys_role WHERE role_code='doctor' ON DUPLICATE KEY UPDATE role_id=VALUES(role_id)");
            execute(connection, "INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by,status) VALUES(7003,7001,7005,'ACTIVE') ON DUPLICATE KEY UPDATE status='ACTIVE'");
        }
        Map<String, Long> before = replayRowCounts();
        String key = "00000000-0000-0000-0000-000000008101";
        Map<String, Object> body = replayDraftBody();
        ReplayCheckpoint checkpoint = new ReplayCheckpoint();
        DataSource dataSource = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = connect(upgrade);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return connection;
            }
            @Override public Connection getConnection(String ignoredUser, String ignoredPassword) throws SQLException {
                throw new SQLFeatureNotSupportedException("Only the explicit disposable test datasource is permitted");
            }
        };
        try (Connection baseline = dataSource.getConnection()) {
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, baseline.getTransactionIsolation(), "The native datasource default must be RR");
            assertEquals("REPEATABLE-READ", scalar(baseline, "SELECT @@SESSION.transaction_isolation"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CarePlanProperties properties = new CarePlanProperties(true,
                Clock.fixed(Instant.parse("2026-10-03T06:10:00.123456Z"), ZoneOffset.UTC));
        CarePlanAuthorizationService auth = new CarePlanAuthorizationService(jdbc, properties) {
            @Override public void requireClinical(long actor, long patient) {
                super.requireClinical(actor, patient); // Real auth completes BEFORE any checkpoint.
                checkpoint.afterCurrentClinicalRead(jdbc);
            }
        };
        CarePlanQueryService queries = new CarePlanQueryService(jdbc, auth, properties);
        CarePlanCommandStore commands = new CarePlanCommandStore(jdbc, properties);
        CarePlanEventStore events = new CarePlanEventStore(jdbc, auth, properties);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("care-plan-mysql-replay-only",
                    Collections.<String, Object>singletonMap("care-plan.enabled", true)));
            context.registerBean("transactionManager", PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
            // The retained six-argument constructor runs draft-only work without
            // installing any notification transport, Spring Boot or web server.
            context.registerBean(CarePlanService.class, () -> new CarePlanService(jdbc, auth, properties, queries, commands, events));
            context.register(MysqlReplayTransactionConfiguration.class);
            context.refresh();
            CarePlanService proxied = context.getBean(CarePlanService.class);
            assertTrue(AopUtils.isAopProxy(proxied), "Use actual Spring transaction annotations, not a manually invoked service");
            Future<Map<String, Object>> loser = pool.submit(() -> checkpoint.create(proxied, "loser", body, key));
            if (!checkpoint.loserAuthorized.await(20, TimeUnit.SECONDS)) {
                if (loser.isDone()) loser.get(1, TimeUnit.SECONDS); // Surface the actual service/authorization failure.
                fail("The loser must finish actual auth reads before the winner starts");
            }
            assertFalse(loser.isDone(), "The loser is paused after its real pre-winner authorization snapshot");
            Future<Map<String, Object>> winner = pool.submit(() -> checkpoint.create(proxied, "winner", body, key));
            Map<String, Object> winnerResult = winner.get(20, TimeUnit.SECONDS); // Proxy return occurs after COMMIT.
            long planId = ((Number) winnerResult.get("id")).longValue();
            long revisionId = ((Number) winnerResult.get("draftRevisionId")).longValue();
            try (Connection observer = connect(upgrade)) {
                assertEquals("1", scalar(observer, "SELECT COUNT(*) FROM care_plan_command WHERE actor_id=7003 AND command_key='" + key + "' AND plan_id=" + planId + " AND result_json IS NOT NULL"), "The winner must be independently visible as committed while the loser remains paused");
            }
            assertFalse(loser.isDone());
            checkpoint.winnerCommitted.set(true);
            checkpoint.allowLoser.countDown();
            Map<String, Object> loserResult = loser.get(20, TimeUnit.SECONDS);
            assertEquals(winnerResult, loserResult, "Both actual service calls must return the same successful aggregate");
            assertEquals("DRAFT", loserResult.get("lifecycle"));
            assertEquals("DRAFT", loserResult.get("revisionStatus"));
            assertEquals("Synthetic native service replay", loserResult.get("title"));
            assertEquals(2, checkpoint.connectionIds.size());
            assertNotEquals(checkpoint.connectionIds.get("winner"), checkpoint.connectionIds.get("loser"), "Actual service calls must use distinct native connections");
            assertTrue(checkpoint.loserClinicalReadsAfterCommit.get() > 0, "Replay must recheck current clinical authority after the winner commit");
            assertReplayRowCounts(before, 1);
            try (Connection connection = connect(upgrade)) {
                assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM doctor_care_plan WHERE id=" + planId + " AND workflow_version=1 AND lifecycle='DRAFT'"));
                assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM care_plan_revision WHERE id=" + revisionId + " AND plan_id=" + planId + " AND revision_no=1"));
                assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM care_plan_event WHERE plan_id=" + planId + " AND revision_id=" + revisionId + " AND event_type='DRAFT_CREATED'"));
                assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM care_plan_command WHERE actor_id=7003 AND command_key='" + key + "'"));
                // A cached command result never retains authority after revocation.
                execute(connection, "UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=7003 AND patient_id=7001");
            }
            try {
                CarePlanException denied = assertThrows(CarePlanException.class, () -> proxied.createDraft(7003L, body, key));
                assertEquals(403, denied.getStatus());
                assertEquals("ACCESS_DENIED", denied.getErrorCode());
                assertReplayRowCounts(before, 1);
            } finally {
                try (Connection connection = connect(upgrade)) {
                    execute(connection, "UPDATE doctor_patient_assignment SET status='ACTIVE' WHERE doctor_user_id=7003 AND patient_id=7001");
                }
            }
            try (Connection baseline = dataSource.getConnection()) {
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, baseline.getTransactionIsolation(), "The proxy must not change the datasource's subsequent default");
            }
            System.out.println("Native actual-service duplicate-create replay verified after pre-winner auth reads; one aggregate/revision/event/command and current-authority denial retained");
        } finally {
            checkpoint.allowLoser.countDown();
            pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test @Order(6) void actualLifecyclePublicationAndAggregateVersionRaces() throws Exception {
        try (NativeServices h = nativeServices()) {
            Map<String,Object> draft=h.plans.createDraft(7003, nativeBody(2), key());
            long plan=number(draft.get("id")),revision=number(draft.get("draftRevisionId"));
            String publicationKey=key();
            List<Object> duplicate=race(()->h.plans.publish(7003,plan,revision,map(),publicationKey,0),
                    ()->h.plans.publish(7003,plan,revision,map(),publicationKey,0));
            assertEquals(duplicate.get(0),duplicate.get(1),"Actual concurrent publish replay returns the same committed result");
            assertEquals(1,h.count("SELECT COUNT(*) FROM care_plan_revision WHERE plan_id=? AND status='PUBLISHED'",plan));
            assertEquals(2,h.count("SELECT COUNT(*) FROM care_plan_action WHERE plan_id=?",plan));
            assertEquals(1,h.count("SELECT COUNT(*) FROM care_plan_event WHERE plan_id=? AND event_type='PLAN_PUBLISHED'",plan));
            assertEquals(1,h.count("SELECT COUNT(*) FROM care_plan_command WHERE actor_id=7003 AND command_key=?",publicationKey));
            List<Long> actions=h.jdbc.queryForList("SELECT id FROM care_plan_action WHERE plan_id=? ORDER BY ordinal",Long.class,plan);
            List<Object> versions=race(()->h.actions.submit(7001,actions.get(0),receipt("SELF"),key(),1),
                    ()->h.actions.help(7001,actions.get(1),map("note","Synthetic version race"),key(),1));
            assertOneConflict(versions);
            assertEquals(2L,h.jdbc.queryForObject("SELECT lock_version FROM doctor_care_plan WHERE id=?",Long.class,plan));
            assertEquals(1,h.count("SELECT COUNT(*) FROM care_plan_event WHERE plan_id=? AND event_type IN ('RECEIPT_SUBMITTED','HELP_REQUESTED')",plan));
            assertTrue(h.transactionConnections.entrySet().stream().filter(e->e.getKey().startsWith("pool-")).count()>=2,"Both actual competing service threads must execute native transaction SQL");
            Set<String> competingConnections=new HashSet<>();h.transactionConnections.forEach((thread,ids)->{if(thread.startsWith("pool-"))competingConnections.addAll(ids);});
            assertTrue(competingConnections.size()>=2,"Competing actual services must use distinct native transaction connections");
            assertEquals(Integer.valueOf(403),status(()->h.queries.detail(7002,plan)),"No grant means no published clinical content");
            System.out.println("Native actual lifecycle duplicate publication and same-aggregate action version race verified");
        }
    }

    @Test @Order(7) void actualReceiptVersusCancellationRevisionAndCurrentAuthority() throws Exception {
        try (NativeServices h=nativeServices()) {
            for(String transition:Arrays.asList("CANCEL","REVISION")) {
                Map<String,Object> draft=h.plans.createDraft(7003,nativeBody(1),key());long plan=number(draft.get("id"));
                h.plans.publish(7003,plan,number(draft.get("draftRevisionId")),map(),key(),0);
                long action=h.jdbc.queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,plan);
                long version=1;Map<String,Object> impact=null;long revised=0;
                if("REVISION".equals(transition)) {
                    Map<String,Object> next=h.plans.createRevision(7003,plan,key(),1);version=2;revised=number(next.get("draftRevisionId"));
                    @SuppressWarnings("unchecked") Map<String,Object> revisionImpact=(Map<String,Object>)next.get("revisionImpact");
                    impact=map("currentRevisionId",revisionImpact.get("currentRevisionId"),"supersededActionDigest",revisionImpact.get("digest"));
                }
                final long expected=version,newRevision=revised;final Map<String,Object> confirmation=impact;
                List<Object> results=race(()->h.actions.submit(7001,action,receipt("SELF"),key(),expected),
                        ()->"CANCEL".equals(transition)?h.plans.transitionPlan(7003,plan,"CANCEL","Synthetic native cancellation",key(),expected):h.plans.publish(7003,plan,newRevision,confirmation,key(),expected));
                assertOneConflict(results);
                assertEquals(expected+1,h.jdbc.queryForObject("SELECT lock_version FROM doctor_care_plan WHERE id=?",Long.class,plan));
                String actionStatus=h.jdbc.queryForObject("SELECT status FROM care_plan_action WHERE id=?",String.class,action);
                int receipts=h.count("SELECT COUNT(*) FROM care_plan_event WHERE action_id=? AND event_type='RECEIPT_SUBMITTED'",action);
                if(receipts==1)assertEquals("SUBMITTED",actionStatus);else assertEquals("CANCEL".equals(transition)?"CANCELLED":"SUPERSEDED",actionStatus);
            }
            h.grantFamily(true);
            Map<String,Object> draft=h.plans.createDraft(7003,nativeBody(1),key());long plan=number(draft.get("id"));
            h.plans.publish(7003,plan,number(draft.get("draftRevisionId")),map(),key(),0);
            long action=h.jdbc.queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,plan);String receiptKey=key();
            Map<String,Object> result=h.actions.submit(7002,action,receipt("ASSISTED"),receiptKey,1);
            h.grantFamily(false);
            assertEquals(Integer.valueOf(403),status(()->h.actions.submit(7002,action,receipt("ASSISTED"),receiptKey,1)),"Revocation must reject cached successful receipt replay");
            assertEquals(1,h.count("SELECT COUNT(*) FROM care_plan_event WHERE action_id=? AND event_type='RECEIPT_SUBMITTED'",action));
            assertEquals(2L,result.get("version"));
            assertEquals(Integer.valueOf(403),status(()->h.queries.detail(7002,plan)),"Current authority also guards old published snapshots");
            System.out.println("Native actual receipt/cancel/revision races and revoked successful receipt replay verified");
        }
    }

    @Test @Order(8) void actualNotificationWorkerSkipLockedConcurrencyRecoveryAndDedup() throws Exception {
        try(NativeServices h=nativeServices()) {
            h.jdbc.update("INSERT INTO notification_channel(id,user_id,channel_type,webhook_url,enabled) VALUES(8801,7001,'WEBHOOK','http://127.0.0.1:9/synthetic-disabled',1)");
            Map<String,Object> draft=h.plans.createDraft(7003,nativeBody(1),key());long plan=number(draft.get("id"));
            Map<String,Object> published=h.plans.publish(7003,plan,number(draft.get("draftRevisionId")),map(),key(),0);long event=number(published.get("eventId"));
            long job=h.jdbc.queryForObject("SELECT id FROM care_plan_notification WHERE event_id=? AND channel_id=8801",Long.class,event);
            try(Connection lock=connect(upgrade)) {
                lock.setAutoCommit(false);execute(lock,"SELECT id FROM care_plan_notification WHERE id="+job+" FOR UPDATE");
                long start=System.nanoTime();h.worker.tick(h.now.get());
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3000,"Production SKIP LOCKED must avoid waiting on another instance's claim");
                assertEquals(0,h.transport.calls.size());lock.rollback();
            }
            assertTrue(h.sql.stream().anyMatch(sql->sql.contains("FOR UPDATE SKIP LOCKED")),"Actual MySQL claim SQL must execute; H2 fallback is not native proof");
            CountDownLatch sending=new CountDownLatch(1),release=new CountDownLatch(1);
            h.transport.beforeSend=()->{sending.countDown();await(release);};
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                Future<?> first=pool.submit(()->h.worker.tick(h.now.get()));assertTrue(sending.await(20,TimeUnit.SECONDS));
                Future<?> second=pool.submit(()->new CarePlanNotificationWorker(h.jdbc,h.auth,h.properties,h.transport).tick(h.now.get()));
                second.get(20,TimeUnit.SECONDS);assertEquals(1,h.transport.calls.size(),"Two worker instances must not dispatch one claimed job twice");
                release.countDown();first.get(20,TimeUnit.SECONDS);
            } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));h.transport.beforeSend=()->{};}
            assertEquals("DELIVERED",h.state(job));
            h.transaction(()->{h.worker.enqueue(event);h.worker.enqueue(event);return null;});
            assertEquals(1,h.count("SELECT COUNT(*) FROM care_plan_notification WHERE event_id=? AND channel_id=8801",event));
            h.worker.tick(h.now.get());assertEquals(1,h.transport.calls.size(),"Committed delivery and enqueue replay are deduplicated");

            long unattempted=h.newJob();h.jdbc.update("UPDATE care_plan_notification SET status='CLAIMED',claimed_at='2026-10-03 05:00:00',claim_token='synthetic-unattempted',last_result='CLAIMED_UNATTEMPTED' WHERE id=?",unattempted);
            new CarePlanNotificationWorker(h.jdbc,h.auth,h.properties,h.transport).tick(h.now.get());assertEquals("DELIVERED",h.state(unattempted));
            long uncertain=h.newJob();int sends=h.transport.calls.size();
            h.jdbc.update("UPDATE care_plan_notification SET status='CLAIMED',attempt_count=1,claimed_at='2026-10-03 05:00:00',claim_token='synthetic-attempted',request_id='synthetic-request:1791007800123',last_result='ATTEMPT_STARTED' WHERE id=?",uncertain);
            new CarePlanNotificationWorker(h.jdbc,h.auth,h.properties,h.transport).tick(h.now.get());
            assertEquals("UNKNOWN",h.state(uncertain));assertEquals(sends,h.transport.calls.size(),"Restart after an attempted send must not resend");
            h.now.set(h.now.get().plusSeconds(3600));h.worker.tick(h.now.get());assertEquals(sends,h.transport.calls.size());
            assertEquals(Integer.valueOf(400),status(()->h.worker.retry(7005,uncertain,false)),"UNKNOWN manual retry needs explicit duplicate-risk acknowledgement");
            h.worker.retry(7005,uncertain,true);h.worker.tick(h.now.get());assertEquals("DELIVERED",h.state(uncertain));
            h.transport.outcome=new CarePlanNotificationTransport.DeliveryAttempt(CarePlanNotificationTransport.DeliveryOutcome.UNKNOWN,false);
            long unknownResult=h.newJob();h.worker.tick(h.now.get());int unknownSends=h.transport.calls.size();assertEquals("UNKNOWN",h.state(unknownResult));
            h.now.set(h.now.get().plusSeconds(3600));h.worker.tick(h.now.get());assertEquals(unknownSends,h.transport.calls.size(),"An actual UNKNOWN transport outcome must never auto resend");
            h.transport.outcome=new CarePlanNotificationTransport.DeliveryAttempt(CarePlanNotificationTransport.DeliveryOutcome.DELIVERED,false);

            h.grantFamily(true);h.jdbc.update("INSERT INTO notification_channel(id,user_id,channel_type,webhook_url,enabled) VALUES(8802,7002,'WEBHOOK','http://127.0.0.1:9/synthetic-disabled',1)");
            Map<String,Object> familyBody=nativeBody(1);nativeActions(familyBody).get(0).put("assignedUserId",7002L);
            Map<String,Object> familyDraft=h.plans.createDraft(7003,familyBody,key());long familyPlan=number(familyDraft.get("id"));
            Map<String,Object> familyPublished=h.plans.publish(7003,familyPlan,number(familyDraft.get("draftRevisionId")),map(),key(),0);
            long familyEvent=number(familyPublished.get("eventId")),familyJob=h.jdbc.queryForObject("SELECT id FROM care_plan_notification WHERE event_id=? AND recipient_user_id=7002",Long.class,familyEvent);
            h.grantFamily(false);h.worker.tick(h.now.get());assertEquals("SUPPRESSED",h.state(familyJob));
            assertFalse(h.transport.calls.stream().anyMatch(call->call.channel==8802),"A queued recipient whose authority was revoked must not be contacted");

            h.transport.outcome=new CarePlanNotificationTransport.DeliveryAttempt(CarePlanNotificationTransport.DeliveryOutcome.FAILED,false);
            long permanent=h.newJob();h.worker.tick(h.now.get());int permanentSends=h.transport.calls.size();
            assertEquals("FAILED_MANUAL_RETRY_REQUIRED",h.jdbc.queryForObject("SELECT last_result FROM care_plan_notification WHERE id=?",String.class,permanent));
            h.now.set(h.now.get().plusSeconds(3600));h.worker.tick(h.now.get());assertEquals(permanentSends,h.transport.calls.size());
            h.transport.outcome=new CarePlanNotificationTransport.DeliveryAttempt(CarePlanNotificationTransport.DeliveryOutcome.FAILED,true);
            long retryable=h.newJob();Instant firstTime=h.now.get();h.worker.tick(firstTime);
            assertEquals(1,h.attempts(retryable));h.now.set(firstTime.plusSeconds(59));h.worker.tick(h.now.get());assertEquals(1,h.attempts(retryable));
            h.now.set(firstTime.plusSeconds(60));h.worker.tick(h.now.get());assertEquals(2,h.attempts(retryable));
            h.now.set(firstTime.plusSeconds(299));h.worker.tick(h.now.get());assertEquals(2,h.attempts(retryable));
            h.now.set(firstTime.plusSeconds(300));h.worker.tick(h.now.get());assertEquals(3,h.attempts(retryable));
            h.now.set(firstTime.plusSeconds(7200));h.worker.tick(h.now.get());assertEquals(3,h.attempts(retryable));
            h.transport.outcome=new CarePlanNotificationTransport.DeliveryAttempt(CarePlanNotificationTransport.DeliveryOutcome.FAILED,false);
            long clockFirst=h.newJob(),clockSecond=h.newJob();Instant batchStart=h.now.get();AtomicBoolean slowFirst=new AtomicBoolean(true);
            h.transport.beforeSend=()->{if(slowFirst.compareAndSet(true,false))h.now.set(batchStart.plusSeconds(120));};
            h.worker.tick(batchStart);h.transport.beforeSend=()->{};
            assertTrue(h.jdbc.queryForObject("SELECT request_id FROM care_plan_notification WHERE id=?",String.class,clockFirst).endsWith(":"+batchStart.toEpochMilli()));
            assertTrue(h.jdbc.queryForObject("SELECT request_id FROM care_plan_notification WHERE id=?",String.class,clockSecond).endsWith(":"+batchStart.plusSeconds(120).toEpochMilli()),"A later job's first-send clock must be captured after the earlier transport finishes");
            for(TransportCall call:h.transport.calls){assertEquals("Care plan update",call.title);assertTrue(call.path.matches("/care-plans/[1-9][0-9]*"));}
            System.out.println("Native actual worker SKIP LOCKED/concurrent claim/current authority/restart recovery/dedup/UNKNOWN/explicit retry budget and generic provider body verified; all external sends synthetic");
        }
    }

    @Test @Order(9) void restorePreservesAllPlanRelationships() throws Exception {
        Map<String, List<String>> beforeRestore;
        try (Connection c = connect(upgrade)) { assertRelationships(c); beforeRestore = comparisonSnapshot(snapshot(c), true); }
        Path directory = Files.createTempDirectory("care-plan-mysql-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path dump = Files.createTempFile(directory, "care-plan-mysql-", ".sql", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            assertNotEquals(upgrade, restore, "Restore must target a different synthetic schema");
            try (Connection c = connect(restore)) {
                assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"), "Restore target must be empty");
            }
            command(Arrays.asList("docker", "exec", container, "mysqldump", "--user=root", "--protocol=socket",
                    "--single-transaction", "--quick", "--routines", "--events", "--triggers", "--hex-blob",
                    "--no-tablespaces", "--set-gtid-purged=OFF", "--default-character-set=utf8mb4", upgrade), null, dump);
            assertTrue(Files.size(dump) > 0, "The complete database dump must be nonempty");
            // No --databases/--all-databases: the destination is explicitly selected.
            command(Arrays.asList("docker", "exec", "-i", container, "mysql", "--user=root", "--protocol=socket",
                    "--default-character-set=utf8mb4", restore), dump, null);
            try (Connection c = connect(restore)) {
                Map<String, List<String>> afterRestore = comparisonSnapshot(snapshot(c), true);
                assertEquals(beforeRestore, afterRestore, "Every full-database sorted row and PK/FK relationship must survive native dump/restore");
                assertRelationships(c); assertNativeSchema(c);
            }
            System.out.println("Native MySQL full restore verified: all tables/rows and column/key/relationship definitions match; raw dump is temporary only");
        } finally { Files.deleteIfExists(dump); Files.deleteIfExists(directory); }
    }

    @TestConfiguration @EnableTransactionManagement
    public static class MysqlReplayTransactionConfiguration { }

    private NativeServices nativeServices() throws Exception { return new NativeServices(); }
    private final class NativeServices implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final List<String> sql=new CopyOnWriteArrayList<>();
        final Map<String,Set<String>> transactionConnections=new ConcurrentHashMap<>();
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-03T06:10:00.123456Z"));
        final SyntheticTransport transport=new SyntheticTransport();
        final JdbcTemplate jdbc;final CarePlanProperties properties;final CarePlanAuthorizationService auth;
        final CarePlanQueryService queries;final CarePlanService plans;final CarePlanActionService actions;final CarePlanNotificationWorker worker;
        NativeServices() throws Exception {
            DataSource source=new AbstractDataSource(){
                @Override public Connection getConnection()throws SQLException {
                    Connection c=connect(upgrade);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    String physicalId=scalar(c,"SELECT CONNECTION_ID()");
                    return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                        if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String){
                            sql.add((String)args[0]);
                            if(TransactionSynchronizationManager.isActualTransactionActive())transactionConnections.computeIfAbsent(Thread.currentThread().getName(),ignored->ConcurrentHashMap.newKeySet()).add(physicalId);
                        }
                        try{return method.invoke(c,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                    });
                }
                @Override public Connection getConnection(String ignoredUser,String ignoredPassword)throws SQLException {throw new SQLFeatureNotSupportedException("Explicit guarded native identity only");}
            };
            jdbc=new JdbcTemplate(source);
            for(Object[] binding:new Object[][]{{7002L,"family"},{7004L,"nurse"},{7005L,"admin"}})
                jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=? ON DUPLICATE KEY UPDATE role_id=VALUES(role_id)",binding);
            properties=new CarePlanProperties(true,new Clock(){public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return now.get();}});
            auth=new CarePlanAuthorizationService(jdbc,properties);queries=new CarePlanQueryService(jdbc,auth,properties);
            CarePlanCommandStore commands=new CarePlanCommandStore(jdbc,properties);CarePlanEventStore events=new CarePlanEventStore(jdbc,auth,properties);
            worker=new CarePlanNotificationWorker(jdbc,auth,properties,transport);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("native-services",Collections.<String,Object>singletonMap("care-plan.enabled",true)));
            context.registerBean("transactionManager",PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean(CarePlanService.class,()->new CarePlanService(jdbc,auth,properties,queries,commands,events,worker));
            context.registerBean(CarePlanActionService.class,()->new CarePlanActionService(jdbc,auth,properties,commands,events,worker));
            context.register(MysqlReplayTransactionConfiguration.class);context.refresh();
            plans=context.getBean(CarePlanService.class);actions=context.getBean(CarePlanActionService.class);
            assertTrue(AopUtils.isAopProxy(plans));assertTrue(AopUtils.isAopProxy(actions));
        }
        int count(String sql,Object...params){return jdbc.queryForObject(sql,Integer.class,params);}
        String state(long job){return jdbc.queryForObject("SELECT status FROM care_plan_notification WHERE id=?",String.class,job);}
        int attempts(long job){return jdbc.queryForObject("SELECT attempt_count FROM care_plan_notification WHERE id=?",Integer.class,job);}
        <T>T transaction(Supplier<T> body){org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);return tx.execute(s->body.get());}
        void grantFamily(boolean active){jdbc.update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,granted_by) VALUES(7001,7002,'FAMILY','WRITE','CARE_PLAN',?,7001) ON DUPLICATE KEY UPDATE status=VALUES(status),expires_at=NULL",active?"ACTIVE":"REVOKED");}
        long newJob(){Map<String,Object>d=plans.createDraft(7003,nativeBody(1),key());Map<String,Object>p=plans.publish(7003,number(d.get("id")),number(d.get("draftRevisionId")),map(),key(),0);return jdbc.queryForObject("SELECT id FROM care_plan_notification WHERE event_id=? AND channel_id=8801",Long.class,p.get("eventId"));}
        @Override public void close(){context.close();}
    }
    private static final class TransportCall {final long channel;final String event,title,path;TransportCall(long channel,String event,String title,String path){this.channel=channel;this.event=event;this.title=title;this.path=path;}}
    private static final class SyntheticTransport implements CarePlanNotificationTransport {
        final List<TransportCall> calls=new CopyOnWriteArrayList<>();volatile Runnable beforeSend=()->{};
        volatile DeliveryAttempt outcome=new DeliveryAttempt(DeliveryOutcome.DELIVERED,false);
        public DeliveryOutcome send(long channel,String event,String title,String path){return attempt(channel,event,title,path).getOutcome();}
        @Override public DeliveryAttempt attempt(long channel,String event,String title,String path){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Synthetic transport must still run outside native DB transactions");
            calls.add(new TransportCall(channel,event,title,path));beforeSend.run();return outcome;
        }
    }
    private static List<Object> race(Supplier<Map<String,Object>> first,Supplier<Map<String,Object>> second)throws Exception {
        CyclicBarrier start=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {List<Future<Object>> futures=new ArrayList<>();for(Supplier<Map<String,Object>> call:Arrays.asList(first,second))futures.add(pool.submit(()->{start.await(20,TimeUnit.SECONDS);try{return call.get();}catch(CarePlanException rejected){return rejected.getStatus();}}));
            return Arrays.asList(futures.get(0).get(30,TimeUnit.SECONDS),futures.get(1).get(30,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }
    private static void assertOneConflict(List<Object> results){assertEquals(1,results.stream().filter(Map.class::isInstance).count(),"Exactly one actual command commits");assertEquals(1,results.stream().filter(Integer.valueOf(409)::equals).count(),"The stale competing command must reject as conflict");}
    private static Integer status(Supplier<?> call){try{call.get();return 200;}catch(CarePlanException rejected){return rejected.getStatus();}}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private static String key(){return UUID.randomUUID().toString();}
    private static long number(Object value){return ((Number)value).longValue();}
    private static Map<String,Object> map(Object...values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static Map<String,Object> receipt(String mode){return map("note","Synthetic native actual receipt","occurredAt","2026-10-03T06:00:00.123456Z","entryMode",mode,"evidence",Collections.emptyList());}
    private static Map<String,Object> nativeBody(int count){List<Map<String,Object>> actions=new ArrayList<>();for(int i=1;i<=count;i++)actions.add(map("ordinal",i,"instruction","Synthetic native actual action "+i,"dueAt","2026-11-01T05:30:00.654321Z","assignedUserId",7001L,"evidence",Collections.emptyList()));return map("patientId",7001L,"title","Synthetic native lifecycle "+key(),"instructions","Synthetic actual service instructions","planType","FOLLOW_UP","actions",actions);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> nativeActions(Map<String,Object> body){return (List<Map<String,Object>>)body.get("actions");}

    private static final class ReplayCheckpoint {
        final ThreadLocal<String> role = new ThreadLocal<>();
        final CountDownLatch loserAuthorized = new CountDownLatch(1), allowLoser = new CountDownLatch(1);
        final AtomicBoolean loserFirstRead = new AtomicBoolean(true), winnerCommitted = new AtomicBoolean(false);
        final AtomicInteger loserClinicalReadsAfterCommit = new AtomicInteger();
        final Map<String, String> connectionIds = new ConcurrentHashMap<>();

        void afterCurrentClinicalRead(JdbcTemplate jdbc) {
            String current = role.get();
            if (current == null) return;
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED),
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), "The actual @Transactional service must declare RC");
            assertEquals("READ-COMMITTED", jdbc.queryForObject("SELECT @@SESSION.transaction_isolation", String.class), "Native effective isolation must be RC despite the RR datasource default");
            String id = jdbc.queryForObject("SELECT CONNECTION_ID()", String.class);
            String previous = connectionIds.putIfAbsent(current, id);
            if (previous != null) assertEquals(previous, id, "Each command must stay on its own transaction-bound physical connection");
            if ("loser".equals(current) && loserFirstRead.compareAndSet(true, false)) {
                assertFalse(winnerCommitted.get());
                loserAuthorized.countDown();
                try { assertTrue(allowLoser.await(30, TimeUnit.SECONDS), "Winner must commit before the loser resumes"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                assertTrue(winnerCommitted.get());
            } else if ("loser".equals(current) && winnerCommitted.get()) loserClinicalReadsAfterCommit.incrementAndGet();
        }
        Map<String, Object> create(CarePlanService service, String label, Map<String, Object> body, String key) {
            role.set(label);
            try { return service.createDraft(7003L, body, key); }
            finally { role.remove(); }
        }
    }

    private Map<String, Long> replayRowCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = connect(upgrade)) {
            for (String table : Arrays.asList("doctor_care_plan", "care_plan_revision", "care_plan_event", "care_plan_command", "care_plan_action", "care_plan_notification"))
                counts.put(table, Long.parseLong(scalar(connection, "SELECT COUNT(*) FROM `" + table + "`")));
        }
        return counts;
    }
    private void assertReplayRowCounts(Map<String, Long> before, long extraAggregates) throws SQLException {
        Map<String, Long> expected = new LinkedHashMap<>(before);
        for (String table : Arrays.asList("doctor_care_plan", "care_plan_revision", "care_plan_event", "care_plan_command")) expected.put(table, before.get(table) + extraAggregates);
        assertEquals(expected, replayRowCounts(), "Exactly one real service aggregate/revision/event/command and no actions/notifications may be created");
    }
    private Map<String, Object> replayDraftBody() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("ordinal", 1); action.put("instruction", "Synthetic native replay action");
        action.put("dueAt", "2026-11-01T05:30:00.654321Z"); action.put("assignedUserId", 7001L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", 7001L); body.put("title", "Synthetic native service replay");
        body.put("instructions", "Synthetic exact-key replay under current authorization"); body.put("planType", "FOLLOW_UP");
        body.put("actions", Collections.singletonList(action)); return body;
    }

    private Long sqlPublishWithPlanLock(CountDownLatch locked, CountDownLatch release, CountDownLatch attempting) throws Exception {
        try (Connection c = connect(upgrade)) {
            c.setAutoCommit(false);
            concurrentConnectionIds.add(scalar(c, "SELECT CONNECTION_ID()"));
            try {
                if (attempting != null) attempting.countDown();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM doctor_care_plan WHERE id=8000 FOR UPDATE")) { assertTrue(rs.next()); }
                if (locked != null) locked.countDown();
                if (release != null) assertTrue(release.await(10, TimeUnit.SECONDS));
                if ("0".equals(scalar(c, "SELECT COUNT(*) FROM care_plan_command WHERE actor_id=7003 AND command_key='00000000-0000-0000-0000-000000008001'"))) {
                    execute(c, "INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at,published_by,published_at) VALUES(8001,8000,1,'PUBLISHED','SQL harness','Synthetic','FOLLOW_UP','[]',7003,'2026-10-03 06:00:00.123456','2026-10-03 06:00:00.123456',7003,'2026-10-03 06:00:00.123456')");
                    execute(c, "UPDATE doctor_care_plan SET current_revision_id=8001,lifecycle='ACTIVE',lock_version=lock_version+1 WHERE id=8000");
                    execute(c, "INSERT INTO care_plan_event(id,patient_id,plan_id,revision_id,actor_id,actor_name,actor_role,event_type,recorded_at,payload_json) VALUES(8001,7001,8000,8001,7003,'Synthetic SQL Doctor','doctor','PUBLISHED','2026-10-03 06:00:00.123456','{}')");
                    execute(c, commandInsert("00000000-0000-0000-0000-000000008001"));
                }
                assertEquals("8001", scalar(c, "SELECT current_revision_id FROM doctor_care_plan WHERE id=8000"));
                c.commit(); return 8001L;
            } catch (Exception | AssertionError failure) { c.rollback(); throw failure; }
        }
    }

    private Boolean insertSameCommand(CyclicBarrier barrier) throws Exception {
        try (Connection c = connect(upgrade)) {
            c.setAutoCommit(false);
            concurrentConnectionIds.add(scalar(c, "SELECT CONNECTION_ID()"));
            try {
                barrier.await(10, TimeUnit.SECONDS);
                execute(c, commandInsert("00000000-0000-0000-0000-000000008002"));
                c.commit(); return true;
            } catch (SQLException failure) {
                c.rollback(); assertEquals(1062, failure.getErrorCode(), "Only native duplicate-key rejection is expected"); return false;
            } catch (Exception | AssertionError failure) { c.rollback(); throw failure; }
        }
    }

    private String commandInsert(String key) {
        return "INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at) VALUES(7003,'" + key + "',8000,0,'synthetic-sql-only','{\"revisionId\":8001}','2026-10-03 06:00:00.123456')";
    }

    private void assertInitializationPrimaryKeys(Connection c) throws SQLException {
        // Regression for original initialization DDL: preserve generated BIGINT
        // identities and require the actual native primary key, not just text.
        for (String table : Arrays.asList("patient_health_target", "medication_intake", "health_event", "dialysis_schedule")) {
            assertEquals("id", scalar(c, "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='" + table + "' AND INDEX_NAME='PRIMARY'"),
                    "Original initialization must create exactly PRIMARY KEY(id) for " + table);
            assertEquals("1", scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='id' AND data_type='bigint' AND extra='auto_increment' AND is_nullable='NO' AND column_key='PRI'"),
                    "Generated non-null BIGINT identity must remain unchanged for " + table);
        }
    }

    private void assertNativeSchema(Connection c) throws SQLException {
        for (String table : CARE_TABLES) {
            assertEquals("InnoDB", scalar(c, "SELECT ENGINE FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + table + "'"));
        }
        assertEquals("16", scalar(c, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY' AND (table_name LIKE 'care_plan_%' OR table_name IN ('care_nurse_assignment','doctor_care_plan'))"));
        assertEquals("6", scalar(c, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK' AND table_name LIKE 'care_plan_%'"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND data_type='datetime' AND (table_name LIKE 'care_plan_%' OR table_name='care_nurse_assignment') AND (datetime_precision<>6 OR column_default IS NOT NULL)"));
        for (String target : Arrays.asList("care_plan_notification:dispatch_key", "care_plan_command:command_key", "care_plan_command:payload_hash")) {
            String[] parts = target.split(":");
            assertEquals("ascii_bin", scalar(c, "SELECT COLLATION_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + parts[0] + "' AND column_name='" + parts[1] + "'"));
        }
    }

    private void assertRelationships(Connection c) throws SQLException {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM care_plan_action a JOIN care_plan_revision r ON r.id=a.revision_id JOIN doctor_care_plan p ON p.id=a.plan_id WHERE r.plan_id<>p.id OR a.patient_id<>p.patient_id"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM care_plan_event e JOIN doctor_care_plan p ON p.id=e.plan_id LEFT JOIN care_plan_revision r ON r.id=e.revision_id LEFT JOIN care_plan_action a ON a.id=e.action_id WHERE e.patient_id<>p.patient_id OR (r.id IS NOT NULL AND r.plan_id<>p.id) OR (a.id IS NOT NULL AND (a.plan_id<>p.id OR a.patient_id<>e.patient_id))"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM care_plan_notification n JOIN care_plan_event e ON e.id=n.event_id WHERE n.patient_id<>e.patient_id"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM care_plan_command WHERE plan_id IS NULL OR result_json IS NULL"));
        assertEquals("1", scalar(c, "SELECT COUNT(*) FROM doctor_care_plan WHERE id=7100 AND workflow_version=0 AND status='ACTIVE' AND lifecycle IS NULL"));
        for (String table : CARE_TABLES) assertTrue(Integer.parseInt(scalar(c, "SELECT COUNT(*) FROM `" + table + "`")) > 0, "Restore fixture must populate " + table);
    }

    private Map<String, List<String>> snapshot(Connection c) throws SQLException {
        Map<String, List<String>> result = new Snapshot();
        // Full database, not the old limited-table backup utility. Complete rows
        // include PK/FK IDs, payload JSON, Unicode, NULLs and microsecond strings.
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) { String table = rs.getString(1); result.put("rows:" + table, rows(c, "SELECT * FROM `" + table + "`")); }
        }
        result.put("table-storage", rows(c, "SELECT TABLE_NAME,ENGINE,TABLE_COLLATION FROM information_schema.tables WHERE table_schema=DATABASE() AND TABLE_TYPE='BASE TABLE'"));
        result.put("allocator-sequences", rows(c, "SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=DATABASE() AND TABLE_TYPE='BASE TABLE'"));
        result.put("columns", rows(c, "SELECT TABLE_NAME,ORDINAL_POSITION,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,CHARACTER_SET_NAME,COLLATION_NAME,EXTRA FROM information_schema.columns WHERE table_schema=DATABASE()"));
        result.put("keys", rows(c, "SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,ORDINAL_POSITION,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE()"));
        result.put("indexes", rows(c, "SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION,SUB_PART,INDEX_TYPE FROM information_schema.statistics WHERE table_schema=DATABASE()"));
        result.put("checks", rows(c, "SELECT CONSTRAINT_NAME,CHECK_CLAUSE FROM information_schema.check_constraints WHERE constraint_schema=DATABASE()"));
        result.put("foreign-key-rules", rows(c, "SELECT TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME,UPDATE_RULE,DELETE_RULE FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()"));
        return result;
    }

    static Map<String, List<String>> comparisonSnapshot(Map<String, List<String>> snapshot, boolean includeAllocators) {
        Map<String, List<String>> result = new Snapshot();
        result.putAll(snapshot);
        if (!includeAllocators) result.remove("allocator-sequences");
        return result;
    }

    /** Keep complete equality semantics while failure logs expose only digests. */
    private static final class Snapshot extends TreeMap<String, List<String>> {
        @Override public String toString() {
            Map<String, String> summaries = new TreeMap<>();
            try {
                for (Map.Entry<String, List<String>> entry : entrySet()) {
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    for (String row : entry.getValue()) {
                        byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
                        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                        digest.update((byte)':'); digest.update(bytes);
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
                    summaries.put(entry.getKey(), entry.getValue().size() + " rows, sha256=" + hex);
                }
            } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
            return summaries.toString();
        }
    }

    private List<String> rows(Connection c, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int count = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int index = 1; index <= count; index++) {
                    String value = rs.getString(index);
                    row.append(value == null ? "N;" : "S" + value.length() + ":" + value + ";");
                }
                result.add(row.toString());
            }
        }
        Collections.sort(result); return result;
    }

    private List<String> columns(Connection c, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, table); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(rs.getString(1)); }
        }
        return result;
    }

    private String identifiers(List<String> values) { List<String> quoted = new ArrayList<>(); for (String value : values) quoted.add("`" + value + "`"); return String.join(",", quoted); }
    private void expectMysqlError(Connection c, int code, String sql) { SQLException failure = assertThrows(SQLException.class, () -> execute(c, sql)); assertEquals(code, failure.getErrorCode(), "Unexpected native error: class=" + failure.getClass().getSimpleName() + ", SQLState=" + failure.getSQLState() + ", message=" + failure.getMessage()); }
    private void apply(Connection c, String path) { ScriptUtils.executeSqlScript(c, new ClassPathResource(path)); }
    // executeUpdate rejects SELECT client-side (01S03/code0), before MySQL can
    // enforce the read-denial probe. Generic execute preserves real vendor errors.
    static void execute(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    private String scalar(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { assertTrue(rs.next()); return rs.getString(1); } }
    private String database(String value) { assertTrue(value.matches("care_plan_test_[a-z0-9_]+") && value.length() <= 64, "Only explicit synthetic schema identifiers are allowed"); return value; }
    private Calendar utc() { GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC")); calendar.setGregorianChange(new java.util.Date(Long.MIN_VALUE)); return calendar; }
    private Connection connect(String database) throws SQLException { return connect(database, false); }
    private Connection connect(String database, boolean serverPrepared) throws SQLException {
        String address = "::1".equals(host) ? "[::1]" : host;
        String url = "jdbc:mysql://" + address + ":" + port + "/" + database(database)
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&preserveInstants=true&characterEncoding=UTF-8&connectTimeout=10000&socketTimeout=20000&useServerPrepStmts=" + serverPrepared;
        Properties credentials = new Properties(); credentials.setProperty("user", user); credentials.setProperty("password", password);
        Connection connection = DriverManager.getConnection(url, credentials);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION time_zone='+00:00'");
            // Fresh metadata avoids cached pre-insert AUTO_INCREMENT statistics.
            statement.execute("SET SESSION information_schema_stats_expiry=0");
        } catch (SQLException failure) { connection.close(); throw failure; }
        return connection;
    }

    private String socketQuery(String sql) throws Exception {
        return command(Arrays.asList("docker", "exec", container, "mysql", "--user=root", "--protocol=socket",
                "--batch", "--raw", "--skip-column-names", "--execute=" + sql), null, null).trim();
    }

    private void assertExactNativeGrantPatterns() throws Exception {
        // mysql.db is the authoritative database-grant pattern store. Backticks
        // alone do not make '_' literal when partial_revokes is OFF.
        String patterns = socketQuery("SELECT Db FROM mysql.db WHERE User='" + user + "' AND Host='%' ORDER BY Db");
        Set<String> expected = new TreeSet<>();
        for (String db : Arrays.asList(fresh, upgrade, restore)) expected.add(db.replace("_", "\\_"));
        List<String> actual = Arrays.asList(patterns.split("\\r?\\n"));
        assertEquals(3, actual.size(), "Exactly three database grants are required");
        assertEquals(expected, new TreeSet<>(actual), "All underscores must be escaped in the actual native grant patterns");
    }

    private void assertNoGlobalGrant(Connection connection) throws SQLException {
        List<String> grants = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SHOW GRANTS FOR CURRENT_USER")) {
            while (rs.next()) grants.add(rs.getString(1));
        }
        assertEquals(4, grants.size(), "Only USAGE plus the exact three database grants is permitted, with no extra roles or scopes");
        int globalUsage = 0;
        for (String grant : grants) {
            if (grant.contains(" ON *.* TO ")) { assertTrue(grant.startsWith("GRANT USAGE ON *.* TO "), "No global privileges are allowed"); globalUsage++; }
            assertFalse(grant.contains("WITH GRANT OPTION"), "The temporary user cannot regrant privileges");
        }
        assertEquals(1, globalUsage);
        expectMysqlError(connection, 1142, "SELECT COUNT(*) FROM mysql.user");
    }

    private String command(List<String> args, Path input, Path output) throws Exception {
        Path errors = Files.createTempFile("care-plan-mysql-error-", ".txt", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Path stdout = output == null ? Files.createTempFile("care-plan-mysql-output-", ".txt", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) : output;
        try {
            ProcessBuilder builder = new ProcessBuilder(args).redirectError(errors.toFile()).redirectOutput(stdout.toFile());
            // Never inherit credentials into docker's environment or process argv.
            builder.environment().remove("CARE_PLAN_MYSQL_PASSWORD");
            if (input != null) builder.redirectInput(input.toFile());
            Process process = builder.start();
            if (input == null) process.getOutputStream().close();
            boolean completed = process.waitFor(90, TimeUnit.SECONDS);
            if (!completed) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); fail("Disposable native MySQL utility timed out"); }
            assertEquals(0, process.exitValue(), "Disposable MySQL utility failed; stderr is temporary and no dump is published");
            return output == null ? new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8) : "";
        } finally { Files.deleteIfExists(errors); if (output == null) Files.deleteIfExists(stdout); }
    }
}
