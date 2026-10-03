package org.familyhealthcare.service;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native MySQL 8.0 schema/restore acceptance only. No Spring context, production
 * configuration, external transport, or future CarePlanService API is loaded.
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
                for (String script : LEGACY_CHAIN) apply(connection, script);
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

    @Test @Order(3) void utcMicrosecondsRoundTripAcrossJvmAndSessionZones() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        // Explicit proleptic UTC calendar supports the full documented range.
        // This characterizes JDBC persistence, not unfinished runtime service APIs.
        String[][] cases = {{"1000-01-01T00:00:00Z", "1000-01-01 00:00:00.000000"},
                {"2026-11-01T05:30:00.123456Z", "2026-11-01 05:30:00.123456"},
                {"2026-11-01T06:30:00.123456Z", "2026-11-01 06:30:00.123456"},
                {"9999-12-31T23:59:59.499999Z", "9999-12-31 23:59:59.499999"}};
        try {
            for (String jvmZone : Arrays.asList("Pacific/Honolulu", "Asia/Kathmandu")) {
                TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
                for (String sessionZone : Arrays.asList("-07:00", "+05:45")) {
                    try (Connection c = connect(upgrade)) {
                        execute(c, "SET SESSION time_zone='" + sessionZone + "'");
                        for (String[] value : cases) {
                            Instant expected = Instant.parse(value[0]);
                            try (PreparedStatement ps = c.prepareStatement("UPDATE care_plan_action SET due_at=? WHERE id=7301")) {
                                ps.setTimestamp(1, Timestamp.from(expected), utc()); ps.executeUpdate();
                            }
                            assertEquals(value[1], scalar(c, "SELECT DATE_FORMAT(due_at,'%Y-%m-%d %H:%i:%s.%f') FROM care_plan_action WHERE id=7301"));
                            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT due_at FROM care_plan_action WHERE id=7301")) {
                                assertTrue(rs.next()); assertEquals(expected, rs.getTimestamp(1, utc()).toInstant());
                            }
                        }
                    }
                }
            }
        } finally { TimeZone.setDefault(previous); }
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

    @Test @Order(5) void restorePreservesAllPlanRelationships() throws Exception {
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
    private void expectMysqlError(Connection c, int code, String sql) { SQLException failure = assertThrows(SQLException.class, () -> execute(c, sql)); assertEquals(code, failure.getErrorCode(), "Unexpected MySQL error for synthetic constraint assertion"); }
    private void apply(Connection c, String path) { ScriptUtils.executeSqlScript(c, new ClassPathResource(path)); }
    private void execute(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.executeUpdate(sql); } }
    private String scalar(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { assertTrue(rs.next()); return rs.getString(1); } }
    private String database(String value) { assertTrue(value.matches("care_plan_test_[a-z0-9_]+") && value.length() <= 64, "Only explicit synthetic schema identifiers are allowed"); return value; }
    private Calendar utc() { GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC")); calendar.setGregorianChange(new java.util.Date(Long.MIN_VALUE)); return calendar; }
    private Connection connect(String database) throws SQLException {
        String address = "::1".equals(host) ? "[::1]" : host;
        String url = "jdbc:mysql://" + address + ":" + port + "/" + database(database)
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&preserveInstants=true&characterEncoding=UTF-8&connectTimeout=10000&socketTimeout=20000";
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
