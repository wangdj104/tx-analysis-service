package org.familyhealthcare.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic, clock-independent tests of the public report boundary. */
class CareExecutionReportContractsTest {
    @org.junit.jupiter.api.Test void interruptedBudgetFailsClosed() {
        Thread.currentThread().interrupt();
        try { org.junit.jupiter.api.Assertions.assertThrows(CareExecutionReportException.class,
            () -> CareExecutionReportBudget.start(java.time.Duration.ofSeconds(30)).checkTime()); }
        finally { Thread.interrupted(); }
    }

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final long SOURCE_LIMIT = 8L * 1024 * 1024;
    private static final long OUTPUT_LIMIT = 32L * 1024 * 1024;

    // A UTC/24-hour default or use of the host date would break these literal expectations.
    @Test void defaultThirtyLocalDates() {
        CareExecutionReportContracts.Request request = parse("UTC", null, null);
        assertEquals(LocalDate.of(2026, 9, 5), request.getFromDate());
        assertEquals(LocalDate.of(2026, 10, 4), request.getToDate());
        assertEquals(Instant.parse("2026-09-05T00:00:00Z"), request.getRangeStartAt());
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), request.getRangeEndExclusiveAt());
        assertEquals(CareExecutionReportContracts.Format.PREVIEW, request.getFormat());
        CareExecutionReportContracts.Request shanghai = CareExecutionReportContracts.parse(
                body("Asia/Shanghai", null, null), false, Instant.parse("2026-10-04T20:00:00Z"));
        assertEquals(LocalDate.of(2026, 9, 6), shanghai.getFromDate());
        assertEquals(LocalDate.of(2026, 10, 5), shanghai.getToDate());
        assertEquals(Instant.parse("2026-09-05T16:00:00Z"), shanghai.getRangeStartAt());
        assertEquals(Instant.parse("2026-10-05T16:00:00Z"), shanghai.getRangeEndExclusiveAt());
    }

    // Adding 24 hours, instead of normalizing each local day, breaks both DST boundaries.
    @Test void dstHalfOpenRange() {
        CareExecutionReportContracts.Request spring = parse("America/New_York", "2026-03-08", "2026-03-08");
        CareExecutionReportContracts.Request fall = parse("America/New_York", "2025-11-02", "2025-11-02");
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), spring.getRangeStartAt());
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), spring.getRangeEndExclusiveAt());
        assertEquals(23, Duration.between(spring.getRangeStartAt(), spring.getRangeEndExclusiveAt()).toHours());
        assertEquals(25, Duration.between(fall.getRangeStartAt(), fall.getRangeEndExclusiveAt()).toHours());
    }

    @Test void skippedApiaDateUsesZoneRules() {
        CareExecutionReportContracts.Request skipped = parse("Pacific/Apia", "2011-12-30", "2011-12-30");
        assertEquals(Instant.parse("2011-12-30T10:00:00Z"), skipped.getRangeStartAt());
        assertEquals(skipped.getRangeStartAt(), skipped.getRangeEndExclusiveAt());
        CareExecutionReportContracts.Request adjacent = parse("Pacific/Apia", "2011-12-29", "2011-12-31");
        assertEquals(Instant.parse("2011-12-29T10:00:00Z"), adjacent.getRangeStartAt());
        assertEquals(Instant.parse("2011-12-31T10:00:00Z"), adjacent.getRangeEndExclusiveAt());
    }

    // Permissive Jackson coercion, duplicate acceptance, or ignoring fields would expose these mistakes.
    @Test void rejectDuplicateUnknownAndPartialFields() {
        invalid("{\"patientId\":1,\"patientId\":2,\"timeZone\":\"UTC\",\"language\":\"en\"}");
        invalid("{\"patientId\":1,\"timeZone\":\"UTC\",\"language\":\"en\",\"actorId\":7}");
        invalid(body("UTC", "2026-10-01", null));
        invalid(body("UTC", null, "2026-10-01"));
        invalid(body("UTC", null, null).replace("}", ",\"format\":\"html\"}"));
        invalid(body("UTC", null, null) + " {}");
        invalid("[]"); invalid("null"); invalid(""); invalid(null);
        invalid("{\"patientId\":1,\"language\":\"en\"}");
        invalid("{\"patientId\":1,\"timeZone\":\"UTC\"}");
        invalid(body("UTC", null, null).replace("\"en\"", "\"EN\""));
        invalid(body("UTC", null, null).replace("}", ",\"fromDate\":null,\"toDate\":null}"));
    }

    @Test void strictDatesAndInclusiveMaximum() {
        assertEquals(LocalDate.of(2025, 10, 4), parse("UTC", "2025-10-04", "2026-10-04").getFromDate());
        invalid(body("UTC", "2025-10-03", "2026-10-04"));
        invalid(body("UTC", "2026-10-04", "2026-10-03"));
        invalid(body("UTC", "2026-10-04", "2026-10-05"));
        for (String date : Arrays.asList("2026-02-29", "2026-2-01", "2026-02-30", "+2026-02-01", "2026-02-01 ")) {
            invalid(body("UTC", date, "2026-10-04"));
        }
        assertEquals(Instant.parse("2024-02-29T00:00:00Z"), parse("UTC", "2024-02-29", "2024-02-29").getRangeStartAt());
    }

    @Test void rejectAmbiguousOrOffsetTimeZones() {
        for (String zone : Arrays.asList("CST", "EST", "PST", "CET", "EET", "WET", "+08:00", "UTC+08:00", "Not/AZone", " UTC")) {
            invalid(body(zone, null, null));
        }
        assertEquals(ZoneId.of("Asia/Shanghai"), parse("Asia/Shanghai", "2026-10-01", "2026-10-01").getTimeZone());
    }

    @Test void acceptsUnambiguousRuntimeIanaAliasesWithoutSlashes() {
        assertEquals(ZoneId.of("Japan"), parse("Japan", "2026-10-01", "2026-10-01").getTimeZone());
        assertEquals(Instant.parse("2026-09-30T15:00:00Z"), parse("Japan", "2026-10-01", "2026-10-01").getRangeStartAt());
        assertEquals(ZoneId.of("EST5EDT"), parse("EST5EDT", "2026-03-08", "2026-03-08").getTimeZone());
        assertEquals(23, Duration.between(parse("EST5EDT", "2026-03-08", "2026-03-08").getRangeStartAt(),
                parse("EST5EDT", "2026-03-08", "2026-03-08").getRangeEndExclusiveAt()).toHours());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), parse("GMT", "2026-10-01", "2026-10-01").getRangeStartAt());
    }

    @Test void idsRequirePositiveExactLongIntegers() {
        for (String value : Arrays.asList("0", "-1", "1.0", "1.5", "1e0", "9223372036854775808", "\"1\"", "true", "null")) {
            invalid(body("UTC", null, null).replace("\"patientId\":1", "\"patientId\":" + value));
            invalid(body("UTC", null, null).replace("}", ",\"planId\":" + value + "}"));
        }
        CareExecutionReportContracts.Request request = CareExecutionReportContracts.parse(
                body("UTC", null, null).replace("\"patientId\":1", "\"patientId\":9223372036854775807")
                        .replace("}", ",\"planId\":2}"), false, NOW);
        assertEquals(Long.MAX_VALUE, request.getPatientId());
        assertEquals(Long.valueOf(2), request.getPlanId());
        assertNull(parse("UTC", null, null).getPlanId());
    }

    @Test void exportRequiresOnePublicFormat() {
        for (String name : Arrays.asList("html", "pdf", "actions_csv", "events_csv")) {
            CareExecutionReportContracts.Request request = CareExecutionReportContracts.parse(
                    body("UTC", null, null).replace("}", ",\"format\":\"" + name + "\"}"), true, NOW);
            assertEquals(CareExecutionReportContracts.Format.valueOf(name.toUpperCase(Locale.ROOT)), request.getFormat());
        }
        invalidExport(body("UTC", null, null));
        for (String name : Arrays.asList("preview", "HTML", "csv", "", " html")) {
            invalidExport(body("UTC", null, null).replace("}", ",\"format\":\"" + name + "\"}"));
        }
    }

    // Retaining caller collections or exposing them directly breaks this frozen projection.
    @Test void defensiveDtoAndExactBudgets() {
        CareExecutionReport report = CareExecutionReportTestData.exampleReport("en");
        assertEquals(6L, report.getCurrentSummary().getTotal());
        assertEquals(2L, report.getCurrentSummary().getOpen());
        assertEquals(1L, report.getCurrentSummary().getNeedsHelp());
        assertEquals(2L, report.getCurrentSummary().getSubmitted());
        assertEquals(1L, report.getCurrentSummary().getConfirmed());
        assertThrows(UnsupportedOperationException.class, () -> report.getCurrentActions().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getCurrentAttention().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getPeriodEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getQuestions().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getCurrentActions().get(0).getEvidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getCurrentActions().get(3).getLatestReceipt().getEvidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getPeriodEvents().get(0).getEvidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getActivitySummary().getEventTypeCounts().clear());
        CareExecutionReportBudget budget = CareExecutionReportBudget.start(Duration.ofSeconds(30));
        budget.requireRows("CURRENT_ACTIONS", 1000, CareExecutionReportContracts.MAX_CURRENT_ACTIONS);
        assertLimit("CURRENT_ACTIONS", 1000, () -> budget.requireRows("CURRENT_ACTIONS", 1001, CareExecutionReportContracts.MAX_CURRENT_ACTIONS));
        budget.requireRows("PERIOD_EVENTS", 5000, CareExecutionReportContracts.MAX_PERIOD_EVENTS);
        assertLimit("PERIOD_EVENTS", 5000, () -> budget.requireRows("PERIOD_EVENTS", 5001, CareExecutionReportContracts.MAX_PERIOD_EVENTS));
        budget.requireRows("QUESTIONS", 200, CareExecutionReportContracts.MAX_QUESTIONS);
        assertLimit("QUESTIONS", 200, () -> budget.requireRows("QUESTIONS", 201, CareExecutionReportContracts.MAX_QUESTIONS));
        assertLimit("CURRENT_ACTIONS", 1000, () -> budget.requireRows("CURRENT_ACTIONS", Long.MAX_VALUE, CareExecutionReportContracts.MAX_CURRENT_ACTIONS));
    }

    @Test void constructorCopiesCallerListsAndCountMap() {
        List<CareExecutionReport.CurrentAction> actions = new ArrayList<>(CareExecutionReportTestData.exampleReport("en").getCurrentActions());
        List<CareExecutionReport.Evidence> evidence = new ArrayList<>();
        evidence.add(new CareExecutionReport.Evidence(true, "MEDICAL_RECORD", 99L, "hidden", "/hidden"));
        CareExecutionReport.EventSummary event = new CareExecutionReport.EventSummary(7, 8, "RECEIPT_SUBMITTED", "Synthetic", "FAMILY", "SPOUSE", "ASSISTED", "原文", null, NOW, null, evidence);
        Map<String, Long> counts = new LinkedHashMap<>(); counts.put("RECEIPT_SUBMITTED", 1L);
        CareExecutionReport.ActivitySummary summary = new CareExecutionReport.ActivitySummary(1, 1, counts);
        CareExecutionReport source = CareExecutionReportTestData.exampleReport("en");
        CareExecutionReport report = new CareExecutionReport(source.getPatient(), source.getScope(), source.getMetadata(), source.getCurrentSummary(), actions,
                source.getCurrentAttention(), summary, source.getPeriodEvents(), "AVAILABLE", source.getQuestions());
        actions.clear(); evidence.clear(); counts.put("RECEIPT_SUBMITTED", 9L);
        assertEquals(6, report.getCurrentActions().size());
        assertEquals(1, event.getEvidence().size());
        assertEquals(1L, report.getActivitySummary().getEventTypeCounts().get("RECEIPT_SUBMITTED"));
        assertThrows(IllegalArgumentException.class, () -> new CareExecutionReport.ActivitySummary(1, 1, Collections.singletonMap("free clinical text", 1L)));
    }

    @Test void wireContractKeepsUtcAndOmitsRestrictedEvidenceDetails() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(CareExecutionReportTestData.exampleReport("zh-CN")));
        assertEquals(1, json.path("reportSchemaVersion").asInt());
        assertEquals("COMPLETE", json.path("completeness").asText());
        assertEquals(2, json.path("patient").size());
        assertEquals("UTC", json.path("metadata").path("timeZone").asText());
        assertEquals("2026-10-04T12:00:00Z", json.path("metadata").path("generatedAt").asText());
        assertEquals("2026-09-05", json.path("metadata").path("fromDate").asText());
        assertEquals("2026-09-06T09:00:00Z", json.path("periodEvents").get(0).path("recordedAt").asText());
        assertEquals("ASSISTED", json.path("currentActions").get(3).path("latestReceipt").path("entryMode").asText());
        assertEquals("原文：按既有计划记录，不自动翻译", json.path("currentActions").get(0).path("instruction").asText());
        JsonNode restricted = json.path("currentActions").get(0).path("evidence").get(0);
        assertEquals(1, restricted.size()); assertTrue(restricted.path("restricted").asBoolean());
        JsonNode readable = json.path("currentActions").get(1).path("evidence").get(0);
        assertFalse(readable.path("restricted").asBoolean());
        assertEquals(100, readable.path("sourceId").asLong());
        assertEquals("LEGACY_UNZONED", json.path("questions").get(0).path("timeBasis").asText());
        assertEquals("2026-09-01 08:30:00", json.path("questions").get(0).path("createdAtLocal").asText());
    }

    // UTF-16 length or an off-by-one comparison would admit too much text.
    @Test void sourceTextUsesExactUtf8Bytes() {
        CareExecutionReportBudget ascii = CareExecutionReportBudget.start(Duration.ofSeconds(30));
        ascii.addSourceText(repeat('a', (int) SOURCE_LIMIT));
        assertLimit("SOURCE_TEXT_BYTES", SOURCE_LIMIT, () -> ascii.addSourceText("a"));
        CareExecutionReportBudget chinese = CareExecutionReportBudget.start(Duration.ofSeconds(30));
        chinese.addSourceText(repeat('中', (int) (SOURCE_LIMIT / 3)));
        chinese.addSourceText("ab"); chinese.addSourceText(null);
        assertLimit("SOURCE_TEXT_BYTES", SOURCE_LIMIT, () -> chinese.addSourceText("中"));
    }

    @Test void sourceTextSupplementaryTwoByteMixedAndIsolatedSurrogates() {
        String[] chunks={"é", "\uD83D\uDE00", "aé中\uD83D\uDE00", "\uD800", "\uDC00", "\uD800x\uDC00"};
        int[] bytes={2,4,10,1,1,3}; // Independent UTF-8 expectations, including Java replacement bytes.
        for(int i=0;i<chunks.length;i++) {
            CareExecutionReportBudget budget=CareExecutionReportBudget.start(Duration.ofSeconds(30));
            budget.addSourceText(repeat('a',(int)SOURCE_LIMIT-bytes[i])); budget.addSourceText(chunks[i]);
            assertLimit("SOURCE_TEXT_BYTES",SOURCE_LIMIT,()->budget.addSourceText("a"));
        }
    }

    @Test void supplementarySourceChecksDeadlineDuringScan() throws Exception {
        java.lang.reflect.Constructor<CareExecutionReportBudget> constructor=CareExecutionReportBudget.class.getDeclaredConstructor(Duration.class,java.util.function.LongSupplier.class);
        constructor.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        CareExecutionReportBudget budget=constructor.newInstance(Duration.ofNanos(10),(java.util.function.LongSupplier)()->calls.incrementAndGet()<4?0:10);
        StringBuilder text=new StringBuilder();for(int i=0;i<10000;i++)text.append("\uD83D\uDE00");
        timeout(()->budget.addSourceText(text.toString())); assertTrue(calls.get()>=4);
    }

    // Budget checks must precede a delegate write so an oversized chunk cannot partly escape.
    @Test void outputChecksExactByteLimitBeforeWriting() throws Exception {
        CountingOutputStream delegate = new CountingOutputStream();
        CareExecutionReportBudget budget = CareExecutionReportBudget.start(Duration.ofSeconds(30));
        OutputStream output = budget.output(delegate);
        byte[] chunk = new byte[1024 * 1024];
        for (int i = 0; i < 32; i++) output.write(chunk);
        assertEquals(OUTPUT_LIMIT, delegate.count);
        assertLimit("OUTPUT_BYTES", OUTPUT_LIMIT, () -> { try { output.write(1); } catch (java.io.IOException e) { throw new AssertionError(e); } });
        assertEquals(OUTPUT_LIMIT, delegate.count);
        assertLimit("OUTPUT_BYTES", OUTPUT_LIMIT, () -> { try { budget.output(delegate).write(chunk); } catch (java.io.IOException e) { throw new AssertionError(e); } });
        CareExecutionReportBudget small = CareExecutionReportBudget.start(Duration.ofSeconds(30));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        small.output(bytes).write(new byte[] {1, 2, 3, 4}, 1, 2);
        assertArrayEquals(new byte[] {2, 3}, bytes.toByteArray());
    }

    @Test void remainingQueryTimeoutRoundsUpWithoutExtendingTheMonotonicDeadline() throws Exception {
        java.lang.reflect.Constructor<CareExecutionReportBudget> constructor=CareExecutionReportBudget.class.getDeclaredConstructor(Duration.class,java.util.function.LongSupplier.class);
        constructor.setAccessible(true);java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong();
        CareExecutionReportBudget budget=constructor.newInstance(Duration.ofSeconds(3),(java.util.function.LongSupplier)now::get);
        assertEquals(3,budget.remainingQuerySeconds());now.set(1_500_000_001L);assertEquals(2,budget.remainingQuerySeconds());
        now.set(2_000_000_000L);assertEquals(1,budget.remainingQuerySeconds());now.set(3_000_000_000L);timeout(()->budget.remainingQuerySeconds());
        assertEquals(Integer.MAX_VALUE,CareExecutionReportBudget.start(Duration.ofSeconds(Long.MAX_VALUE)).remainingQuerySeconds());
    }

    @Test void expiredBudgetStopsTimeTextRowsAndOutput() throws Exception {
        CareExecutionReportBudget expired = CareExecutionReportBudget.start(Duration.ZERO);
        timeout(expired::checkTime);
        timeout(() -> expired.requireRows("CURRENT_ACTIONS", 0, 1000));
        timeout(() -> expired.addSourceText("synthetic"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        timeout(() -> { try { expired.output(bytes).write(1); } catch (java.io.IOException e) { throw new AssertionError(e); } });
        assertEquals(0, bytes.size());
        CareExecutionReportBudget.start(Duration.ofSeconds(Long.MAX_VALUE)).checkTime();
        assertThrows(IllegalArgumentException.class, () -> CareExecutionReportBudget.start(Duration.ofNanos(-1)));
    }

    private static CareExecutionReportContracts.Request parse(String zone, String from, String to) {
        return CareExecutionReportContracts.parse(body(zone, from, to), false, NOW);
    }
    private static String body(String zone, String from, String to) {
        return "{\"patientId\":1,\"timeZone\":\"" + zone + "\",\"language\":\"en\""
                + (from == null ? "" : ",\"fromDate\":\"" + from + "\"")
                + (to == null ? "" : ",\"toDate\":\"" + to + "\"") + "}";
    }
    private static void invalid(String body) { invalid(body, false); }
    private static void invalidExport(String body) { invalid(body, true); }
    private static void invalid(String body, boolean exporting) {
        CareExecutionReportException error = assertThrows(CareExecutionReportException.class, () -> CareExecutionReportContracts.parse(body, exporting, NOW));
        assertEquals(400, error.getStatus()); assertEquals("INVALID_REQUEST", error.getErrorCode());
        assertNull(error.getLimitKind()); assertNull(error.getLimit());
        assertFalse(error.getMessage().contains("Not/AZone"));
    }
    private static void assertLimit(String kind, long limit, Runnable action) {
        CareExecutionReportException error = assertThrows(CareExecutionReportException.class, action::run);
        assertEquals(422, error.getStatus()); assertEquals("REPORT_LIMIT_EXCEEDED", error.getErrorCode());
        assertEquals(kind, error.getLimitKind()); assertEquals(Long.valueOf(limit), error.getLimit());
    }
    private static void timeout(Runnable action) {
        CareExecutionReportException error = assertThrows(CareExecutionReportException.class, action::run);
        assertEquals(503, error.getStatus()); assertEquals("REPORT_TIMEOUT", error.getErrorCode());
    }
    private static String repeat(char value, int length) { char[] chars = new char[length]; Arrays.fill(chars, value); return new String(chars); }
    private static class CountingOutputStream extends OutputStream {
        long count;
        @Override public void write(int value) { count++; }
        @Override public void write(byte[] bytes, int offset, int length) { count += length; }
    }
}
