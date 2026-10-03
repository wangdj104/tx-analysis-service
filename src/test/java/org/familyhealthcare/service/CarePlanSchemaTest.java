package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.familyhealthcare.service.careplan.CarePlanContracts;

@SuppressWarnings("unchecked")
class CarePlanSchemaTest {
    @Test void legacyPlansRemainPrivateAfterRepeatMigration() throws Exception {
        try (CarePlanTestFixture fixture = new CarePlanTestFixture()) {
            JdbcTemplate jdbc = fixture.jdbc();
            int roles = jdbc.queryForObject("SELECT COUNT(*) FROM sys_role", Integer.class);
            int menus = jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu", Integer.class);
            fixture.migrate();
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_action", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT workflow_version FROM doctor_care_plan WHERE id=1", Integer.class));
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM doctor_care_plan WHERE id=1", String.class));
            assertNull(jdbc.queryForObject("SELECT lifecycle FROM doctor_care_plan WHERE id=1", String.class));
            assertNull(jdbc.queryForObject("SELECT current_revision_id FROM doctor_care_plan WHERE id=1", Long.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_revision", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_notification", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM care_nurse_assignment", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM care_access_grant", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE r.role_code='nurse'", Integer.class));
            assertEquals(roles, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role", Integer.class));
            assertEquals(menus, jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu", Integer.class));
            jdbc.update("INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions) VALUES(2,9,1,'New legacy API row','Still internal')");
            assertEquals(0, jdbc.queryForObject("SELECT workflow_version FROM doctor_care_plan WHERE id=2", Integer.class));
        }
    }

    @Test void schemaRejectsDuplicateRevisionAndActionOrdinal() throws Exception {
        try (CarePlanTestFixture fixture = new CarePlanTestFixture()) {
            JdbcTemplate jdbc = fixture.jdbc();
            jdbc.update("INSERT INTO care_plan_revision(id,plan_id,revision_no,title,instructions,plan_type,draft_json,created_by,created_at,updated_at) VALUES(1,1,1,'Revision','Instruction','FOLLOW_UP','[]',9,?,?)", java.sql.Timestamp.from(fixture.now()), java.sql.Timestamp.from(fixture.now()));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO care_plan_revision(plan_id,revision_no,title,instructions,plan_type,draft_json,created_by,created_at,updated_at) SELECT plan_id,revision_no,title,instructions,plan_type,draft_json,created_by,created_at,updated_at FROM care_plan_revision WHERE id=1"));
            jdbc.update("INSERT INTO care_plan_action(id,plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,created_at,updated_at) VALUES(1,1,1,1,1,'Synthetic action',?,7,?,?)", java.sql.Timestamp.from(fixture.now()), java.sql.Timestamp.from(fixture.now()), java.sql.Timestamp.from(fixture.now()));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO care_plan_action(plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,created_at,updated_at) SELECT plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,created_at,updated_at FROM care_plan_action WHERE id=1"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM doctor_care_plan WHERE id=1"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE doctor_care_plan SET current_revision_id=999 WHERE id=1"));
        }
    }

    @Test void notificationAndCommandKeysDeduplicateAndHistorySurvivesActorDeletion() throws Exception {
        try (CarePlanTestFixture fixture = new CarePlanTestFixture()) {
            JdbcTemplate jdbc = fixture.jdbc();
            java.sql.Timestamp now = java.sql.Timestamp.from(fixture.now());
            jdbc.update("INSERT INTO care_plan_event(id,patient_id,plan_id,actor_id,actor_name,actor_role,event_type,recorded_at,payload_json) VALUES(1,1,1,10,'Synthetic departed nurse','nurse','FOLLOW_UP',?,'{}')", now);
            jdbc.update("DELETE FROM sys_user WHERE id=10");
            assertEquals("Synthetic departed nurse", jdbc.queryForObject("SELECT actor_name FROM care_plan_event WHERE id=1", String.class));
            jdbc.update("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,channel_id,dispatch_key,status,created_at,updated_at) VALUES(1,1,7,NULL,'1:7:NO_CHANNEL','NO_CHANNEL',?,?)", now, now);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,created_at,updated_at) VALUES(1,1,7,'1:7:NO_CHANNEL',?,?)", now, now));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,created_at,updated_at) VALUES(1,1,7,NULL,?,?)", now, now));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,created_at,updated_at) VALUES(1,1,7,'',?,?)", now, now));
            jdbc.update("INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at) VALUES(9,'00000000-0000-0000-0000-000000000001',1,0,'digest','{}',?)", now);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at) SELECT actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at FROM care_plan_command"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at) VALUES(9,'00000000-0000-0000-0000-000000000002',1,-1,'digest','{}',?)", now));
        }
    }

    @Test void createCommandCanReserveItsKeyBeforeThePlanExists() throws Exception {
        try (CarePlanTestFixture fixture = new CarePlanTestFixture()) {
            JdbcTemplate jdbc = fixture.jdbc();
            java.sql.Timestamp now = java.sql.Timestamp.from(fixture.now());
            assertDoesNotThrow(() -> jdbc.update("INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at) VALUES(9,'00000000-0000-0000-0000-000000000001',NULL,0,'digest',NULL,?)", now));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at) VALUES(9,'00000000-0000-0000-0000-000000000001',NULL,0,'digest',NULL,?)", now));
            jdbc.update("UPDATE care_plan_command SET plan_id=1,result_json='{}' WHERE actor_id=9");
            assertEquals(1L, jdbc.queryForObject("SELECT plan_id FROM care_plan_command WHERE actor_id=9", Long.class));
        }
    }

    @Test void nurseMenusMatchApprovedRoutesWithoutInventingAnAdminPage() throws Exception {
        try (CarePlanTestFixture fixture = new CarePlanTestFixture()) {
            assertEquals("/nurse-workspace", fixture.jdbc().queryForObject("SELECT menu_path FROM sys_menu WHERE menu_code='care-plan-nursing'", String.class));
            assertEquals(2, fixture.jdbc().queryForObject("SELECT menu_type FROM sys_menu WHERE menu_code='care-plan-nurse-assignments'", Integer.class));
            assertEquals(8L, fixture.jdbc().queryForObject("SELECT parent_id FROM sys_menu WHERE menu_code='care-plan-nurse-assignments'", Long.class));
        }
    }

    @Test void validatesExactLimitsAndOffsetTime() {
        validate("validateDraft", draft(1));
        validate("validateDraft", draft(50));
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft(0)));
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft(51)));
        Map<String,Object> draft = draft(1);
        action(draft).put("instruction", repeat("😀", 2000));
        validate("validateDraft", draft);
        action(draft).put("instruction", repeat("😀", 2001));
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        Map<String,Object> receipt = receipt(5);
        receipt.put("note", "  " + repeat("😀", 2000) + "  ");
        validate("validateReceipt", receipt);
        receipt.put("note", repeat("😀", 2001));
        assertThrows(IllegalArgumentException.class, () -> validate("validateReceipt", receipt));
        assertThrows(IllegalArgumentException.class, () -> validate("validateReceipt", receipt(6)));
        assertThrows(IllegalArgumentException.class, () -> parse("2026-11-01T01:30:00"));
        assertThrows(IllegalArgumentException.class, () -> parse("2026-11-01"));
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), parse("2026-11-01T01:30:00-04:00"));
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), parse("2026-11-01T01:30:00-05:00"));
        assertNotEquals(parse("2026-11-01T01:30:00-04:00"), parse("2026-11-01T01:30:00-05:00"));
    }

    @Test void validatesDraftTextBoundariesAndRequiredReceiptFields() {
        Map<String,Object> draft = draft(1);
        draft.put("title", repeat("😀", 160));
        draft.put("instructions", repeat("😀", 4000));
        validate("validateDraft", draft);
        draft.put("title", repeat("😀", 161));
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        draft.put("title", "x");
        draft.put("instructions", repeat("😀", 4001));
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        draft.put("instructions", "x");
        action(draft).put("instruction", "x");
        validate("validateDraft", draft);
        for (String key : Arrays.asList("title", "instructions")) {
            Object old = draft.put(key, " ");
            assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
            draft.put(key, old);
        }
        Map<String,Object> receipt = receipt(0);
        receipt.put("note", "x");
        validate("validateReceipt", receipt);
        for (String key : Arrays.asList("note", "occurredAt", "entryMode")) {
            Object old = receipt.remove(key);
            assertThrows(IllegalArgumentException.class, () -> validate("validateReceipt", receipt));
            receipt.put(key, old);
        }
    }

    @Test void rejectsMalformedContractsAndPreservesCallerInput() {
        Map<String,Object> draft = draft(1);
        draft.put("title", "  Title  ");
        validate("validateDraft", draft);
        assertEquals("  Title  ", draft.get("title"));
        for (Object invalid : Arrays.asList(1.5d, Double.NaN, Double.POSITIVE_INFINITY, "1", 0L, -1L)) {
            draft.put("patientId", invalid);
            assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        }
        draft.put("patientId", 1L);
        draft.put("planType", "UNRECOGNIZED");
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        draft.put("planType", "FOLLOW_UP");
        draft.put("actorId", 9L);
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        draft.remove("actorId");
        action(draft).put("ordinal", 2);
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        action(draft).put("ordinal", 1);
        action(draft).put("dueAt", "2026-10-03T09:00:00");
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        Map<String,Object> receipt = receipt(0);
        receipt.put("entryMode", "INFERRED");
        assertThrows(IllegalArgumentException.class, () -> validate("validateReceipt", receipt));
        receipt.put("entryMode", "ASSISTED");
        receipt.put("note", "\u2003\u00a0\t");
        assertThrows(IllegalArgumentException.class, () -> validate("validateReceipt", receipt));
        Map<String,Object> invalidEvidenceReceipt = receipt(1);
        ((Map<String,Object>) ((List<?>)invalidEvidenceReceipt.get("evidence")).get(0)).put("sourceType", "DIALYSIS");
        assertThrows(IllegalArgumentException.class, () -> validate("validateReceipt", invalidEvidenceReceipt));
    }

    @Test void optionalActionEvidenceAcceptsExactLimitsAndIsNotMutated() {
        Map<String,Object> body = draft(1);
        validate("validateDraft", body);
        action(body).put("evidence", null);
        assertDoesNotThrow(() -> validate("validateDraft", body));
        action(body).put("evidence", Collections.emptyList());
        assertDoesNotThrow(() -> validate("validateDraft", body));
        List<Map<String,Object>> references = (List<Map<String,Object>>)receipt(5).get("evidence");
        action(body).put("evidence", references);
        assertDoesNotThrow(() -> validate("validateDraft", body));
        assertSame(references, action(body).get("evidence"));
        assertEquals(5, references.size());
        action(body).put("evidence", receipt(6).get("evidence"));
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", body));
    }

    @Test void duplicateReceiptEvidenceIsRejectedByTypedNumericIdentity() {
        Map<String,Object> body = receipt(1);
        List<Map<String,Object>> references = (List<Map<String,Object>>)body.get("evidence");
        references.add(evidence("MEASUREMENT", new BigDecimal("1.000")));
        assertThrows(IllegalArgumentException.class, () -> validate("validateReceipt", body));
    }

    @Test void duplicateActionEvidenceIsRejectedByTypedNumericIdentity() {
        Map<String,Object> body = draft(1);
        List<Map<String,Object>> references = new ArrayList<>();
        references.add(evidence("MEASUREMENT", 1L));
        references.add(evidence("MEASUREMENT", BigInteger.ONE));
        action(body).put("evidence", references);
        assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", body));
    }

    @Test void equalIdsInDistinctEvidenceSourcesRemainDistinct() {
        List<Map<String,Object>> references = Arrays.asList(evidence("MEASUREMENT", 1L), evidence("MEDICAL_RECORD", 1L));
        Map<String,Object> receipt = receipt(0);
        receipt.put("evidence", references);
        validate("validateReceipt", receipt);
        Map<String,Object> draft = draft(1);
        action(draft).put("evidence", references);
        assertDoesNotThrow(() -> validate("validateDraft", draft));
    }

    @Test void exactLargeNumericIdsAreAcceptedWithoutTruncation() {
        for (Object id : Arrays.asList(Byte.valueOf((byte)1), Short.valueOf((short)1), Integer.valueOf(1),
                Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE), new BigDecimal("9223372036854775807.000"))) {
            assertEquals(id instanceof Byte || id instanceof Short || id instanceof Integer ? 1L : Long.MAX_VALUE,
                    CarePlanContracts.requireId(id, "sourceId"));
            Map<String,Object> draft = draft(1);
            draft.put("patientId", id); draft.put("legacySourceId", id); action(draft).put("assignedUserId", id);
            validate("validateDraft", draft);
            Map<String,Object> receipt = receipt(1);
            ((List<Map<String,Object>>)receipt.get("evidence")).get(0).put("sourceId", id);
            validate("validateReceipt", receipt);
        }
    }

    @ParameterizedTest @MethodSource("nonExactIds")
    void rejectsOverflowAndFractionalNumericIds(Object id) {
        assertThrows(IllegalArgumentException.class, () -> CarePlanContracts.requireId(id, "sourceId"));
    }

    static Stream<Arguments> nonExactIds() {
        return Stream.of(new BigInteger("9223372036854775808"), new BigInteger("-9223372036854775809"),
                new BigDecimal("9223372036854775808"), new BigDecimal("-9223372036854775809"),
                new BigDecimal("1.5"), new BigDecimal("0.000001"), Float.NaN, Float.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY).map(Arguments::of);
    }

    @ParameterizedTest @MethodSource("malformedNestedInputs")
    void rejectsWrongContainersNonObjectsAndUnknownNestedFields(String target, Object value) {
        Map<String,Object> draft = draft(1), receipt = receipt(0);
        if ("actions".equals(target)) {
            draft.put("actions", value);
            assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        } else if ("action".equals(target)) {
            draft.put("actions", Collections.singletonList(value));
            assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        } else {
            receipt.put("evidence", value);
            assertThrows(IllegalArgumentException.class, () -> validate("validateReceipt", receipt));
            action(draft).put("evidence", value);
            assertThrows(IllegalArgumentException.class, () -> validate("validateDraft", draft));
        }
    }

    static Stream<Arguments> malformedNestedInputs() {
        Map<String,Object> unknownAction = new HashMap<>(action(draft(1)));
        unknownAction.put("actorId", 9L);
        Map<String,Object> unknownReference = evidence("MEASUREMENT", 1L);
        unknownReference.put("title", "Unapproved clinical content");
        Map<String,Object> wrongSource = evidence("DIALYSIS", 1L);
        return Stream.of(Arguments.of("actions", null), Arguments.of("actions", "wrong"),
                Arguments.of("actions", Collections.emptyMap()), Arguments.of("action", null),
                Arguments.of("action", 1L), Arguments.of("action", "wrong"), Arguments.of("action", unknownAction),
                Arguments.of("evidence", "wrong"), Arguments.of("evidence", Collections.emptyMap()),
                Arguments.of("evidence", 1L), Arguments.of("evidence", Collections.singletonList(null)),
                Arguments.of("evidence", Collections.singletonList(1L)),
                Arguments.of("evidence", Collections.singletonList("wrong")),
                Arguments.of("evidence", Collections.singletonList(unknownReference)),
                Arguments.of("evidence", Collections.singletonList(wrongSource)));
    }

    @Test void offsetTimesRequireUtcMysqlRangeAndMicrosecondPrecision() {
        assertEquals(Instant.parse("1000-01-01T00:00:00Z"), parse("1000-01-01T00:00:00Z"));
        assertEquals(Instant.parse("9999-12-31T23:59:59.499999Z"), parse("9999-12-31T23:59:59.499999Z"));
        assertEquals(Instant.parse("9999-12-31T23:59:59.499998Z"), parse("9999-12-31T23:59:59.499998Z"));
        assertEquals(parse("1000-01-01T00:00:00Z"), parse("0999-12-31T23:00:00-01:00"));
        assertEquals(parse("1000-01-01T00:00:00Z"), parse("1000-01-01T01:00:00+01:00"));
        assertEquals(parse("9999-12-31T23:59:59.499999Z"), parse("9999-12-31T22:59:59.499999-01:00"));
        assertEquals(parse("9999-12-31T23:59:59.499999Z"), parse("+10000-01-01T00:59:59.499999+01:00"));
        assertEquals(Instant.parse("2026-10-03T05:00:00.123456Z"), parse("2026-10-03T07:00:00.123456+02:00"));
        assertEquals(parse("2026-10-03T05:00:00.123456Z"), parse("2026-10-03T07:00:00.123456+02:00"));
        assertEquals(parse("2026-10-03T05:00:00.123456Z"), parse("2026-10-03T05:00:00.123456000Z"));
        assertThrows(IllegalArgumentException.class, () -> parse("0999-12-31T23:59:59Z"));
        assertThrows(IllegalArgumentException.class, () -> parse("0999-12-31T23:59:59.999999Z"));
        assertThrows(IllegalArgumentException.class, () -> parse("1000-01-01T00:59:59.999999+01:00"));
        assertThrows(IllegalArgumentException.class, () -> parse("9999-12-31T22:59:59.500000-01:00"));
        assertThrows(IllegalArgumentException.class, () -> parse("+10000-01-01T00:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> parse("1000-01-01T00:00:00+01:00"));
        assertThrows(IllegalArgumentException.class, () -> parse("9999-12-31T23:59:59-01:00"));
        assertThrows(IllegalArgumentException.class, () -> parse("2026-10-03T05:00:00.1234567Z"));
        assertThrows(IllegalArgumentException.class, () -> parse("2026-10-03T05:00:00.000000001Z"));
        assertThrows(IllegalArgumentException.class, () -> parse("9999-12-31T23:59:59.500000Z"));
    }

    private static Map<String,Object> evidence(String sourceType, Object sourceId) {
        Map<String,Object> ref = new HashMap<>(); ref.put("sourceType", sourceType); ref.put("sourceId", sourceId); return ref;
    }

    static Map<String,Object> draft(int count) {
        Map<String,Object> body = new HashMap<>();
        body.put("patientId", 1L); body.put("title", "Synthetic title"); body.put("instructions", "Synthetic instruction"); body.put("planType", "FOLLOW_UP");
        List<Map<String,Object>> actions = new ArrayList<>();
        for (int i=1; i<=count; i++) {
            Map<String,Object> action = new HashMap<>();
            action.put("ordinal", i); action.put("instruction", "Synthetic action"); action.put("dueAt", "2026-10-03T09:00:00+02:00"); action.put("assignedUserId", 7L); actions.add(action);
        }
        body.put("actions", actions); return body;
    }
    static Map<String,Object> receipt(int count) {
        Map<String,Object> body = new HashMap<>();
        body.put("note", "Synthetic receipt"); body.put("occurredAt", "2026-10-03T04:00:00Z"); body.put("entryMode", "ASSISTED");
        List<Map<String,Object>> evidence = new ArrayList<>();
        for (int i=1; i<=count; i++) {
            Map<String,Object> ref = new HashMap<>(); ref.put("sourceType", "MEASUREMENT"); ref.put("sourceId", (long)i); evidence.add(ref);
        }
        body.put("evidence", evidence); return body;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> action(Map<String,Object> body) { return (Map<String,Object>)((List<?>)body.get("actions")).get(0); }
    static String repeat(String text, int count) { return String.join("", Collections.nCopies(count, text)); }
    static void validate(String method, Map<String,Object> body) {
        if ("validateDraft".equals(method)) CarePlanContracts.validateDraft(body);
        else CarePlanContracts.validateReceipt(body);
    }
    static Instant parse(String value) { return CarePlanContracts.parseOffsetInstant(value); }
}
