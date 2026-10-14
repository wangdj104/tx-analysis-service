package org.familyhealthcare.service.workflow;

import org.familyhealthcare.service.careplan.CarePlanAuthorizationService;
import org.familyhealthcare.service.careplan.CarePlanCommandStore;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic command storage only; no patient records or live database are used. */
class WorkflowCommandExecutorTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00.123456Z");
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private final String key = "12345678-1234-1234-1234-123456789abc";

    @BeforeEach void database() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:command_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        for (String[] domain : new String[][]{{"care_plan_command", "plan_id"}, {"alert_command", "alert_id"}, {"measurement_command", "source_id"}}) {
            jdbc.execute("CREATE TABLE " + domain[0] + "(actor_id BIGINT NOT NULL,command_key VARCHAR(36) NOT NULL," + domain[1] + " BIGINT,expected_version BIGINT NOT NULL,payload_hash VARCHAR(64) NOT NULL,result_json CLOB,created_at TIMESTAMP(6),UNIQUE(actor_id,command_key))");
        }
        jdbc.execute("CREATE TABLE change_marker(id BIGINT)");
    }

    @ParameterizedTest @ValueSource(strings = {"CARE_PLAN", "ALERT", "MEASUREMENT"})
    void equalCommandReplayReturnsThePersistedOriginalResult(String domain) {
        AtomicInteger changes = new AtomicInteger();
        Map<String,Object> original = transaction.execute(status -> execute(domain, 9L, 4, "same", () -> {
            changes.incrementAndGet();
            jdbc.update("INSERT INTO change_marker VALUES(9)");
            return result(domain, 9L, "original");
        }));
        Map<String,Object> replay = transaction.execute(status -> execute(domain, 9L, 4, "same", () -> {
            changes.incrementAndGet();
            return result(domain, 9L, "changed");
        }));
        assertEquals(original, replay);
        assertEquals("original", replay.get("value"));
        assertEquals(1, changes.get());
        assertEquals(1, count("change_marker"));
        String table = "CARE_PLAN".equals(domain) ? "care_plan_command" : "ALERT".equals(domain) ? "alert_command" : "measurement_command";
        assertEquals(NOW.toString(), jdbc.queryForObject("SELECT created_at FROM " + table, (rs, index) -> WorkflowData.time(rs, "created_at")));
    }

    @Test void sameKeyWithChangedPayloadConflictsWithoutRunningTheChange() {
        transaction.execute(status -> execute("ALERT", 9L, 4, "first", () -> result("ALERT", 9L, "first")));
        WorkflowException conflict = assertThrows(WorkflowException.class, () -> transaction.execute(status -> execute("ALERT", 9L, 4, "different", () -> {
            fail("A conflicting command must never run"); return null;
        })));
        assertEquals(409, conflict.getStatus());
        assertEquals("COMMAND_CONFLICT", conflict.getErrorCode());
        assertEquals(1, count("alert_command"));
    }

    @Test void changedAggregateOrVersionCannotReplayAnotherRequest() {
        transaction.execute(status -> execute("ALERT", 9L, 4, "same", () -> result("ALERT", 9L, "first")));
        for (long[] changed : new long[][]{{10, 4}, {9, 5}}) {
            WorkflowException error = assertThrows(WorkflowException.class, () -> transaction.execute(status -> execute("ALERT", changed[0], changed[1], "same", () -> result("ALERT", changed[0], "wrong"))));
            assertEquals("COMMAND_CONFLICT", error.getErrorCode());
        }
    }

    @Test void anIncompleteReservationCannotCommitEvenWhenCallbackFailureIsCaught() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            try { execute("MEASUREMENT", null, 0, "create", () -> {
                jdbc.update("INSERT INTO change_marker VALUES(9)");
                throw new IllegalArgumentException("Synthetic callback failure");
            }); } catch (IllegalArgumentException deliberatelyCaught) { /* beforeCommit must still reject */ }
            return null;
        }));
        assertTrue(error.getMessage().contains("incomplete"));
        assertEquals(0, count("measurement_command"));
        assertEquals(0, count("change_marker"));
    }

    @Test void aForeignResultAggregateIsRejectedAndRolledBack() {
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> execute("ALERT", 9L, 0, "same", () -> {
            jdbc.update("INSERT INTO change_marker VALUES(9)");
            return result("ALERT", 10L, "foreign");
        })));
        assertEquals(0, count("alert_command"));
        assertEquals(0, count("change_marker"));
    }

    @Test void aFailedCallbackLeavesNoCommandOrAggregateChange() {
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(status -> execute("CARE_PLAN", null, 0, "create", () -> {
            jdbc.update("INSERT INTO change_marker VALUES(9)");
            throw new IllegalArgumentException("Synthetic callback failure");
        })));
        assertEquals(0, count("care_plan_command"));
        assertEquals(0, count("change_marker"));
    }

    @Test void cachedResultsStripNestedSourceTitleAndLinkWithoutChangingTheCallbackResult() {
        Map<String,Object> evidence = map("sourceType", "MEASUREMENT", "sourceId", 91L, "title", "Synthetic source title", "detailLink", "/synthetic/91");
        Map<String,Object> fresh = result("ALERT", 9L, "first");
        fresh.put("nested", map("evidence", Collections.singletonList(evidence)));
        Map<String,Object> safe = transaction.execute(status -> execute("ALERT", 9L, 0, "same", () -> fresh));
        Map<String,Object> replay = transaction.execute(status -> execute("ALERT", 9L, 0, "same", () -> { fail("Must replay"); return null; }));
        assertEquals(safe, replay);
        assertFalse(safe.toString().contains("Synthetic source title"));
        assertFalse(safe.toString().contains("/synthetic/91"));
        assertTrue(safe.toString().contains("restricted=true"));
        assertEquals("Synthetic source title", evidence.get("title"));
        String stored = jdbc.queryForObject("SELECT result_json FROM alert_command", String.class);
        assertFalse(stored.contains("detailLink"));
        assertFalse(stored.contains("title"));
    }

    @Test void createCompletesItsReservationIncludingANormalMeasurementWithNoFollowUps() {
        Map<String,Object> fresh = result("MEASUREMENT", 77L, "normal");
        fresh.put("followUps", Collections.emptyList());
        Map<String,Object> returned = transaction.execute(status -> execute("MEASUREMENT", null, 0, "create", () -> fresh));
        assertEquals(77L, returned.get("sourceId"));
        assertEquals(Collections.emptyList(), returned.get("followUps"));
        assertEquals(77L, jdbc.queryForObject("SELECT source_id FROM measurement_command", Long.class));
        assertEquals(returned, transaction.execute(status -> execute("MEASUREMENT", null, 0, "create", () -> { fail("Must replay create"); return null; })));
    }

    @Test void onlyCarePlanAcceptsTheLegacyIdResultKey() {
        transaction.execute(status -> execute("CARE_PLAN", null, 0, "create", () -> map("id", 9L)));
        assertEquals(9L, jdbc.queryForObject("SELECT plan_id FROM care_plan_command", Long.class));
        assertThrows(RuntimeException.class, () -> transaction.execute(status -> execute("ALERT", null, 0, "create", () -> map("id", 9L))));
        assertEquals(0, count("alert_command"));
    }

    @Test void missingOrInvalidResultIdsCannotCompleteAReservation() {
        for (Object invalid : Arrays.asList(null, 0L, -1L, 1.5, "9")) {
            assertThrows(RuntimeException.class, () -> transaction.execute(status -> execute("ALERT", null, 0, "create", () -> map("alertId", invalid))));
            assertEquals(0, count("alert_command"));
        }
    }

    @Test void mutationsRequireAnEffectiveReadCommittedTransactionOnTheSameDatasource() {
        assertThrows(IllegalStateException.class, () -> execute("ALERT", 9L, 0, "same", () -> result("ALERT", 9L, "unsafe")));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> execute("ALERT", 9L, 0, "same", () -> result("ALERT", 9L, "unsafe"))));
        JdbcTemplate other = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:other_" + UUID.randomUUID(), "sa", ""));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> invokeExecutor(other, "ALERT", 9L, 0, "same", () -> result("ALERT", 9L, "unsafe"))));
        assertEquals(0, count("alert_command"));
    }

    @Test void aCurrentAuthorizationFailureOutsideTheExecutorRollsBackTheWholeMutation() {
        jdbc.execute("CREATE TABLE sys_user(id BIGINT,status INT,deleted INT)");
        jdbc.update("INSERT INTO sys_user VALUES(1,0,0)");
        CarePlanProperties properties = new CarePlanProperties(true, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        CarePlanAuthorizationService auth = new CarePlanAuthorizationService(jdbc, properties);
        CarePlanException denied = assertThrows(CarePlanException.class, () -> transaction.execute(status -> {
            Map<String,Object> result = execute("CARE_PLAN", 9L, 0, "same", () -> {
                jdbc.update("INSERT INTO change_marker VALUES(9)");
                return result("CARE_PLAN", 9L, "new");
            });
            auth.requireActiveActor(1L);
            return result;
        }));
        assertEquals(403, denied.getStatus());
        assertEquals(0, count("care_plan_command"));
        assertEquals(0, count("change_marker"));
    }

    @Test void carePlanAdapterRetainsFreshResultAndItsExistingExceptionContract() {
        CarePlanCommandStore store = new CarePlanCommandStore(jdbc, new CarePlanProperties(true, Clock.fixed(NOW, java.time.ZoneOffset.UTC)));
        Map<String,Object> fresh = map("id", 9L, "evidence", Collections.singletonList(map("title", "Synthetic title", "detailLink", "/synthetic/9")));
        assertSame(fresh, transaction.execute(status -> store.executeCreate(1L, key, "same", () -> fresh)));
        Map<String,Object> replay = transaction.execute(status -> store.executeCreate(1L, key, "same", () -> { fail("Must replay"); return null; }));
        assertFalse(replay.toString().contains("Synthetic title"));
        CarePlanException conflict = assertThrows(CarePlanException.class, () -> transaction.execute(status -> store.executeCreate(1L, key, "changed", () -> fresh)));
        assertEquals(409, conflict.getStatus());
        assertEquals("COMMAND_CONFLICT", conflict.getErrorCode());
        assertEquals("INVALID_REQUEST", assertThrows(CarePlanException.class, () -> CarePlanCommandStore.validateKey("invalid")).getErrorCode());
    }

    @Test void failedCurrentAuthorizationAfterReplayDoesNotExposeTheCachedResult() {
        transaction.execute(status -> execute("CARE_PLAN", 9L, 0, "same", () -> result("CARE_PLAN", 9L, "original")));
        jdbc.execute("CREATE TABLE sys_user(id BIGINT,status INT,deleted INT)");
        jdbc.update("INSERT INTO sys_user VALUES(1,0,0)");
        CarePlanAuthorizationService auth = new CarePlanAuthorizationService(jdbc, new CarePlanProperties(true, Clock.fixed(NOW, java.time.ZoneOffset.UTC)));
        CarePlanException denied = assertThrows(CarePlanException.class, () -> transaction.execute(status -> {
            Map<String,Object> replay = execute("CARE_PLAN", 9L, 0, "same", () -> { fail("Must replay"); return null; });
            auth.requireActiveActor(1L);
            return replay;
        }));
        assertEquals(403, denied.getStatus());
        assertEquals(1, count("care_plan_command"));
    }

    @Test void canonicalJsonHashAndLegacyIntegerTypesRemainStable() {
        assertEquals("{\"a\":1,\"b\":2}", WorkflowData.json(map("b", 2, "a", 1)));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", WorkflowData.hash("abc"));
        Map<String,Object> stored = WorkflowData.object(WorkflowData.json(map("workflowVersion", 1, "ordinal", 2, "revisionNo", 3, "planId", 9)));
        assertEquals(Integer.class, stored.get("workflowVersion").getClass());
        assertEquals(Integer.class, stored.get("ordinal").getClass());
        assertEquals(Integer.class, stored.get("revisionNo").getClass());
        assertEquals(Long.class, stored.get("planId").getClass());
        assertEquals("UTC", WorkflowData.utc().getTimeZone().getID());
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private Map<String,Object> execute(String domain, Long aggregate, long version, String payload, Supplier<Map<String,Object>> change) {
        return invokeExecutor(jdbc, domain, aggregate, version, payload, change);
    }
    private Map<String,Object> invokeExecutor(JdbcTemplate database, String domain, Long aggregate, long version, String payload, Supplier<Map<String,Object>> change) {
        return WorkflowCommandExecutor.execute(database, WorkflowCommandExecutor.Domain.valueOf(domain), 1L, key, payload, aggregate, version, NOW, change);
    }
    private Map<String,Object> result(String domain, long id, String value) { return map("CARE_PLAN".equals(domain) ? "planId" : "ALERT".equals(domain) ? "alertId" : "sourceId", id, "value", value); }
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> result = new LinkedHashMap<>(); for (int i=0; i<pairs.length; i+=2) result.put((String) pairs[i], pairs[i+1]); return result; }
}
