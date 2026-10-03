package org.familyhealthcare.service.careplan;

import com.mysql.cj.BindValue;
import com.mysql.cj.MysqlType;
import com.mysql.cj.conf.DefaultPropertySet;
import com.mysql.cj.protocol.ServerCapabilities;
import com.mysql.cj.protocol.ServerSession;
import com.mysql.cj.protocol.a.SqlTimestampValueEncoder;
import org.familyhealthcare.service.CarePlanTestFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;

import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual command/JDBC rollback and Connector/J encoding at production helper boundaries. */
class CarePlanFoundationTest {
    private CarePlanTestFixture fixture;
    private TransactionTemplate transaction;
    private CarePlanService service;
    private ProjectionRevocation query;
    private boolean ownerDoctor;

    @BeforeEach void setup() throws Exception {
        fixture = new CarePlanTestFixture();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.jdbc().getDataSource()));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        CarePlanProperties properties = new CarePlanProperties(true, Clock.fixed(fixture.now(), ZoneOffset.UTC));
        CarePlanAuthorizationService authorization = new CarePlanAuthorizationService(fixture.jdbc(), properties);
        query = new ProjectionRevocation(authorization, properties);
        service = new CarePlanService(fixture.jdbc(), authorization, properties, query,
                new CarePlanCommandStore(fixture.jdbc(), properties),
                new CarePlanEventStore(fixture.jdbc(), authorization, properties), eventId -> {
                    fixture.jdbc().update("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,status,created_at,updated_at) SELECT id,patient_id,7,?,'QUEUED',?,? FROM care_plan_event WHERE id=?",
                            "synthetic-foundation-" + eventId, Timestamp.from(fixture.now()), Timestamp.from(fixture.now()), eventId);
                });
    }
    @AfterEach void close() throws Exception { fixture.close(); }

    @Test void caughtFinalCreateProjectionRevocationRollsBackCompletedCommand() {
        assertProjectionRollback("DRAFT_CREATED", () -> service.createDraft(DOCTOR, body(), query.commandKey));
    }

    @Test void caughtFinalSaveProjectionRevocationPreservesDraftAndCachedResult() {
        Map<String,Object> draft = transaction.execute(status -> service.createDraft(DOCTOR, body(), key()));
        Map<String,Object> changed = body(); changed.put("title", "Synthetic changed draft");
        assertProjectionRollback("DRAFT_SAVED", () -> service.saveDraft(DOCTOR, number(draft.get("id")),
                number(draft.get("draftRevisionId")), changed, query.commandKey, 0));
    }

    @Test void caughtFinalRevisionProjectionRevocationPreservesPublishedSnapshot() {
        Map<String,Object> draft = transaction.execute(status -> service.createDraft(DOCTOR, body(), key()));
        long plan = number(draft.get("id")), revision = number(draft.get("draftRevisionId"));
        transaction.execute(status -> service.publish(DOCTOR, plan, revision, Collections.emptyMap(), key(), 0));
        assertProjectionRollback("REVISION_CREATED", () -> service.createRevision(DOCTOR, plan, query.commandKey, 1));
    }

    @Test void caughtFinalRevisionClinicalRevocationCannotCommitWhileProjectionRemainsReadable() {
        ownerDoctor = true;
        fixture.jdbc().update("UPDATE patient SET user_id=9 WHERE id=1");
        Map<String,Object> draft = transaction.execute(status -> service.createDraft(DOCTOR, body(), key()));
        long plan = number(draft.get("id")), revision = number(draft.get("draftRevisionId"));
        transaction.execute(status -> service.publish(DOCTOR, plan, revision, Collections.emptyMap(), key(), 0));
        query.revokeAfterProjection = true;
        assertProjectionRollback("REVISION_CREATED", () -> service.createRevision(DOCTOR, plan, query.commandKey, 1));
        assertTrue(query.projectedSuccessfully, "The actual PUBLISHED projection must succeed before operation authority is revoked");
        assertEquals("PUBLISHED", query.detail(DOCTOR, plan).get("revisionStatus"), "Owner read access must remain after rollback and revocation");
    }

    private void assertProjectionRollback(String eventType, Runnable mutation) {
        Map<String,List<Map<String,Object>>> before = businessSnapshot();
        query.commandKey = key(); query.eventType = eventType; query.revokeAtProjection = true;
        IllegalStateException commitFailure = assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            CarePlanException denied = assertThrows(CarePlanException.class, mutation::run);
            assertEquals(403, denied.getStatus()); assertEquals("ACCESS_DENIED", denied.getErrorCode());
            return null; // The caller deliberately catches the final projection exception.
        }), "A caught final projection failure must still prevent commit");
        assertNotNull(commitFailure.getMessage());
        assertTrue(query.revocationCommitted, "The independent revocation must have completed after command caching");
        assertEquals("REVOKED", fixture.jdbc().queryForObject(
                "SELECT status FROM doctor_patient_assignment WHERE doctor_user_id=9 AND patient_id=1", String.class));
        assertEquals(before, businessSnapshot(), "All plan/revision/action/event/outbox/command fields must roll back");
    }

    private Map<String,List<Map<String,Object>>> businessSnapshot() {
        Map<String,List<Map<String,Object>>> snapshot = new LinkedHashMap<>();
        for (String table : Arrays.asList("doctor_care_plan", "care_plan_revision", "care_plan_action",
                "care_plan_event", "care_plan_evidence", "care_plan_notification", "care_plan_command"))
            snapshot.put(table, fixture.jdbc().queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return snapshot;
    }

    /** A deterministic boundary hook; the actual final projection and authorization still execute. */
    private final class ProjectionRevocation extends CarePlanQueryService {
        boolean revokeAtProjection, revokeAfterProjection, revocationCommitted, projectedSuccessfully;
        private final CarePlanAuthorizationService authorization;
        String commandKey, eventType;
        ProjectionRevocation(CarePlanAuthorizationService authorization, CarePlanProperties properties) {
            super(fixture.jdbc(), authorization, properties); this.authorization = authorization;
        }
        @Override Map<String,Object> refreshEvidence(long actor, Map<String,Object> original) {
            Map<String,Object> projected = null;
            if (revokeAtProjection) {
                if (revokeAfterProjection) {
                    projected = super.refreshEvidence(actor, original);
                    assertEquals("PUBLISHED", projected.get("revisionStatus")); projectedSuccessfully = true;
                }
                assertEquals(1, fixture.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM care_plan_command WHERE command_key=? AND plan_id IS NOT NULL AND result_json IS NOT NULL",
                        Integer.class, commandKey), "The real command callback and cached result must be complete before revocation");
                assertEquals(1, fixture.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM care_plan_event WHERE id=(SELECT MAX(id) FROM care_plan_event) AND event_type=?",
                        Integer.class, eventType), "The requested business event must already exist inside the outer transaction");
                Connection outer = DataSourceUtils.getConnection(fixture.jdbc().getDataSource());
                try (Connection independent = fixture.jdbc().getDataSource().getConnection()) {
                    assertNotSame(outer, independent); assertTrue(independent.getAutoCommit());
                    independent.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    independent.setAutoCommit(false);
                    try (PreparedStatement statement = independent.prepareStatement(
                            "UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9 AND patient_id=1")) {
                        assertEquals(1, statement.executeUpdate());
                    }
                    independent.commit(); revocationCommitted = true;
                    if (revokeAfterProjection) assertDoesNotThrow(() -> authorization.requireRead(actor, PATIENT));
                } catch (SQLException failure) { throw new IllegalStateException(failure); }
                finally { DataSourceUtils.releaseConnection(outer, fixture.jdbc().getDataSource()); }
            }
            return revokeAfterProjection ? projected : super.refreshEvidence(actor, original);
        }
    }

    @Test void productionUtcBindingEncodesAcceptedDatesThroughActualConnectorJ() throws Exception {
        String[][] cases = {
                {"1000-01-01T00:00:00Z", "'1000-01-01 00:00:00'"},
                {"1000-01-01T05:45:00+05:45", "'1000-01-01 00:00:00'"},
                {"1500-01-01T00:00:00.123456Z", "'1500-01-01 00:00:00.123456'"},
                {"2026-11-01T01:30:00.123456-04:00", "'2026-11-01 05:30:00.123456'"},
                {"9999-12-31T23:59:59.499999Z", "'9999-12-31 23:59:59.499999'"},
                {"9999-12-31T16:59:59.499999-07:00", "'9999-12-31 23:59:59.499999'"}
        };
        List<Executable> assertions = new ArrayList<>();
        for (String[] value : cases) assertions.add(() -> assertEquals(value[1], encodeProductionBinding(value[0]), value[0]));
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : Arrays.asList("Pacific/Honolulu", "Asia/Kathmandu")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone)); assertAll(zone, assertions);
            }
        } finally { TimeZone.setDefault(previous); }
    }

    private static String encodeProductionBinding(String input) throws SQLException {
        Object[] bound = new Object[2];
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    if ("setTimestamp".equals(method.getName())) { bound[0] = args[1]; bound[1] = args[2]; }
                    return defaultValue(method.getReturnType());
                });
        CarePlanData.time(statement, 1, CarePlanContracts.parseOffsetInstant(input));
        assertNotNull(bound[0]); assertNotNull(bound[1]);
        BindValue value = (BindValue) Proxy.newProxyInstance(BindValue.class.getClassLoader(), new Class<?>[]{BindValue.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getValue": return bound[0];
                        case "getMysqlType": return MysqlType.TIMESTAMP;
                        case "getCalendar": return bound[1];
                        case "keepOrigNanos": return true;
                        default: return defaultValue(method.getReturnType());
                    }
                });
        ServerCapabilities capabilities = (ServerCapabilities) Proxy.newProxyInstance(ServerCapabilities.class.getClassLoader(),
                new Class<?>[]{ServerCapabilities.class}, (proxy, method, args) ->
                        "serverSupportsFracSecs".equals(method.getName()) ? true : defaultValue(method.getReturnType()));
        ServerSession session = (ServerSession) Proxy.newProxyInstance(ServerSession.class.getClassLoader(),
                new Class<?>[]{ServerSession.class}, (proxy, method, args) -> {
                    if ("getCapabilities".equals(method.getName())) return capabilities;
                    if ("getDefaultTimeZone".equals(method.getName()) || "getSessionTimeZone".equals(method.getName()))
                        return TimeZone.getTimeZone("UTC");
                    return defaultValue(method.getReturnType());
                });
        SqlTimestampValueEncoder encoder = new SqlTimestampValueEncoder();
        encoder.init(new DefaultPropertySet(), session, null);
        return encoder.getString(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    private Map<String,Object> body() {
        return CarePlanData.map("patientId", PATIENT, "title", "Synthetic draft", "instructions", "Synthetic instructions",
                "planType", "FOLLOW_UP", "actions", Collections.singletonList(CarePlanData.map("ordinal", 1,
                        "instruction", "Synthetic action", "dueAt", "2026-10-03T06:00:00Z", "assignedUserId", ownerDoctor ? DOCTOR : OWNER)));
    }
    private static long number(Object value) { return ((Number)value).longValue(); }
    private static String key() { return UUID.randomUUID().toString(); }
}
