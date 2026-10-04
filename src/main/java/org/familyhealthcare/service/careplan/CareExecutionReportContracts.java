package org.familyhealthcare.service.careplan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** A dedicated strict parser; it does not change JSON handling for existing APIs. */
public final class CareExecutionReportContracts {
    public static final long MAX_CURRENT_ACTIONS = 1000;
    public static final long MAX_PERIOD_EVENTS = 5000;
    public static final long MAX_QUESTIONS = 200;
    public static final long MAX_SOURCE_TEXT_BYTES = 8L * 1024 * 1024;
    public static final long MAX_OUTPUT_BYTES = 32L * 1024 * 1024;
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    public static final Set<String> PUBLIC_EVENT_TYPES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "PLAN_PUBLISHED", "REVISION_PUBLISHED", "RECEIPT_SUBMITTED", "HELP_REQUESTED", "FOLLOW_UP_RECORDED",
            "RECEIPT_RETURNED", "RECEIPT_CONFIRMED", "PLAN_CANCELLED", "PLAN_CLOSED")));

    private static final Set<String> FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "patientId", "planId", "fromDate", "toDate", "timeZone", "language", "format")));
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();
    private static final Set<String> AMBIGUOUS_ZONES = ambiguousZones();
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private CareExecutionReportContracts() { }

    public enum Format {
        PREVIEW(null), HTML("html"), PDF("pdf"), ACTIONS_CSV("actions_csv"), EVENTS_CSV("events_csv");
        private final String wireName;
        Format(String wireName) { this.wireName = wireName; }
        public String getWireName() { return wireName; }
    }

    public static final class Request {
        private final long patientId;
        private final Long planId;
        private final LocalDate fromDate, toDate;
        private final ZoneId timeZone;
        private final String language;
        private final Instant rangeStartAt, rangeEndExclusiveAt;
        private final Format format;
        private Request(long patientId, Long planId, LocalDate fromDate, LocalDate toDate,
                        ZoneId timeZone, String language, Format format) {
            this.patientId = patientId; this.planId = planId;
            this.fromDate = fromDate; this.toDate = toDate; this.timeZone = timeZone;
            this.language = language; this.format = format;
            this.rangeStartAt = fromDate.atStartOfDay(timeZone).toInstant();
            this.rangeEndExclusiveAt = toDate.plusDays(1).atStartOfDay(timeZone).toInstant();
        }
        public long getPatientId() { return patientId; }
        public Long getPlanId() { return planId; }
        public LocalDate getFromDate() { return fromDate; }
        public LocalDate getToDate() { return toDate; }
        public ZoneId getTimeZone() { return timeZone; }
        public String getLanguage() { return language; }
        public Instant getRangeStartAt() { return rangeStartAt; }
        public Instant getRangeEndExclusiveAt() { return rangeEndExclusiveAt; }
        public Format getFormat() { return format; }
    }

    public static Request parse(String json, boolean exporting, Instant now) {
        if (json == null || now == null) throw CareExecutionReportException.invalid();
        try (JsonParser parser = JSON.getFactory().createParser(json)) {
            JsonNode body = JSON.readTree(parser);
            if (body == null || !body.isObject() || parser.nextToken() != null) throw CareExecutionReportException.invalid();
            Iterator<String> fields = body.fieldNames();
            while (fields.hasNext()) if (!FIELDS.contains(fields.next())) throw CareExecutionReportException.invalid();
            long patientId = id(body.get("patientId"));
            Long planId = body.has("planId") ? id(body.get("planId")) : null;
            String zoneName = text(body.get("timeZone"));
            // Runtime IANA aliases are valid too; slash presence is not an identity rule.
            if ((!"UTC".equals(zoneName) && !ZONES.contains(zoneName)) || AMBIGUOUS_ZONES.contains(zoneName)) {
                throw CareExecutionReportException.invalid();
            }
            ZoneId zone = ZoneId.of(zoneName);
            String language = text(body.get("language"));
            if (!"en".equals(language) && !"zh-CN".equals(language)) throw CareExecutionReportException.invalid();
            if (body.has("fromDate") != body.has("toDate")) throw CareExecutionReportException.invalid();
            LocalDate today = now.atZone(zone).toLocalDate();
            LocalDate from = body.has("fromDate") ? date(body.get("fromDate")) : today.minusDays(29);
            LocalDate to = body.has("toDate") ? date(body.get("toDate")) : today;
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            if (days < 1 || days > 366 || to.isAfter(today)) throw CareExecutionReportException.invalid();
            Format format = Format.PREVIEW;
            if (exporting) {
                String name = text(body.get("format"));
                format = null;
                for (Format candidate : Format.values()) if (name.equals(candidate.wireName)) format = candidate;
                if (format == null) throw CareExecutionReportException.invalid();
            } else if (body.has("format")) throw CareExecutionReportException.invalid();
            return new Request(patientId, planId, from, to, zone, language, format);
        } catch (IOException | DateTimeException e) {
            // Do not attach Jackson's input-containing message or cause to the public error.
            throw CareExecutionReportException.invalid();
        }
    }

    private static long id(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw CareExecutionReportException.invalid();
        }
        return value.longValue();
    }
    private static String text(JsonNode value) {
        if (value == null || !value.isTextual()) throw CareExecutionReportException.invalid();
        return value.textValue();
    }
    private static LocalDate date(JsonNode value) {
        String text = text(value);
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw CareExecutionReportException.invalid();
        return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
    }
    private static Set<String> ambiguousZones() {
        Set<String> zones = new HashSet<>(ZoneId.SHORT_IDS.keySet());
        zones.addAll(Arrays.asList("CET", "EET", "MET", "WET"));
        return Collections.unmodifiableSet(zones);
    }
}
