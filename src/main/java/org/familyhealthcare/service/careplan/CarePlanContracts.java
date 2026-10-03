package org.familyhealthcare.service.careplan;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure request validation shared by the care-plan services; never infers authorization. */
public final class CarePlanContracts {
    private static final Set<String> PLAN_TYPES = keys("FOLLOW_UP", "MEDICATION", "DIALYSIS", "NUTRITION");
    private static final Set<String> ENTRY_MODES = keys("SELF", "ASSISTED");
    private static final Set<String> EVIDENCE_TYPES = keys("MEASUREMENT", "MEDICAL_RECORD");

    // Conservative documented MySQL 8.0 DATETIME(6) UTC storage bounds.
    private static final Instant MIN_STORAGE_INSTANT = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_STORAGE_INSTANT = Instant.parse("9999-12-31T23:59:59.499999Z");

    private CarePlanContracts() { }

    /** Validates without mutating caller input. Services persist trimmed text and UTC instants. */
    public static void validateDraft(Map<String, Object> body) {
        fields(body, keys("patientId", "title", "instructions", "planType", "actions", "legacySourceId"));
        requireId(body.get("patientId"), "patientId");
        requireText(body.get("title"), "title", 160);
        requireText(body.get("instructions"), "instructions", 4000);
        choice(body.get("planType"), "planType", PLAN_TYPES);
        if (body.get("legacySourceId") != null) requireId(body.get("legacySourceId"), "legacySourceId");
        Object raw = body.get("actions");
        if (!(raw instanceof List)) throw invalid("actions must be a list");
        List<?> actions = (List<?>) raw;
        if (actions.isEmpty() || actions.size() > 50) throw invalid("actions must contain 1 to 50 items");
        for (int i = 0; i < actions.size(); i++) {
            Map<String, Object> action = object(actions.get(i), "action");
            fields(action, keys("ordinal", "instruction", "dueAt", "assignedUserId", "evidence"));
            if (requireId(action.get("ordinal"), "ordinal") != i + 1L) throw invalid("ordinal must be consecutive from 1");
            requireText(action.get("instruction"), "instruction", 2000);
            parseOffsetInstant(string(action.get("dueAt"), "dueAt"));
            requireId(action.get("assignedUserId"), "assignedUserId");
            validateEvidence(action.get("evidence"));
        }
    }

    /** Actual occurrence time and evidence ownership require service context and are checked there. */
    public static void validateReceipt(Map<String, Object> body) {
        fields(body, keys("note", "occurredAt", "entryMode", "evidence"));
        requireText(body.get("note"), "note", 2000);
        parseOffsetInstant(string(body.get("occurredAt"), "occurredAt"));
        choice(body.get("entryMode"), "entryMode", ENTRY_MODES);
        validateEvidence(body.get("evidence"));
    }

    private static void validateEvidence(Object raw) {
        if (raw == null) return;
        if (!(raw instanceof List)) throw invalid("evidence must be a list");
        List<?> evidence = (List<?>) raw;
        if (evidence.size() > 5) throw invalid("evidence may contain at most 5 references");
        Set<String> identities = new HashSet<>();
        for (Object item : evidence) {
            Map<String, Object> ref = object(item, "evidence reference");
            fields(ref, keys("sourceType", "sourceId"));
            choice(ref.get("sourceType"), "sourceType", EVIDENCE_TYPES);
            long id = requireId(ref.get("sourceId"), "sourceId");
            if (!identities.add(ref.get("sourceType") + ":" + id)) throw invalid("duplicate evidence references are not allowed");
        }
    }

    /** Requires an ISO-8601 offset and exact microsecond precision within the UTC storage range. */
    public static Instant parseOffsetInstant(String value) {
        if (value == null) throw invalid("time must include an ISO-8601 offset");
        try {
            Instant instant = OffsetDateTime.parse(trim(value), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            if (instant.getNano() % 1000 != 0) throw invalid("time must be exactly representable at UTC microsecond precision");
            if (instant.isBefore(MIN_STORAGE_INSTANT) || instant.isAfter(MAX_STORAGE_INSTANT))
                throw invalid("time is outside the supported UTC storage range");
            return instant;
        } catch (DateTimeParseException e) {
            throw invalid("time must include an ISO-8601 offset");
        }
    }

    /** Positive exact IDs, with no string coercion, truncation or non-finite numbers. */
    public static long requireId(Object value, String field) {
        long id;
        try {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                id = ((Number) value).longValue();
            } else if (value instanceof BigInteger) {
                id = ((BigInteger) value).longValueExact();
            } else if (value instanceof BigDecimal) {
                id = ((BigDecimal) value).longValueExact();
            } else {
                throw invalid(field + " must be an integer ID");
            }
        } catch (ArithmeticException e) {
            throw invalid(field + " must be an integer ID");
        }
        if (id <= 0) throw invalid(field + " must be a positive integer ID");
        return id;
    }

    /** Counts Unicode code points after stripping Unicode whitespace at both ends. */
    public static String requireText(Object value, String field, int maximum) {
        String text = trim(string(value, field));
        int count = text.codePointCount(0, text.length());
        if (count == 0 || count > maximum) throw invalid(field + " must contain 1 to " + maximum + " characters");
        return text;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String)) throw invalid(field + " must be text");
        return (String) value;
    }

    private static void choice(Object value, String field, Set<String> allowed) {
        if (!(value instanceof String) || !allowed.contains(value)) throw invalid(field + " is not supported");
    }

    private static void fields(Map<String, Object> body, Set<String> allowed) {
        if (body == null) throw invalid("request body is required");
        for (Object field : body.keySet()) if (!allowed.contains(field)) throw invalid("unsupported request field: " + field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String field) {
        if (!(raw instanceof Map)) throw invalid(field + " must be an object");
        return (Map<String, Object>) raw;
    }

    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static String trim(String value) {
        int from = 0, to = value.length();
        while (from < to && whitespace(value.codePointAt(from))) from += Character.charCount(value.codePointAt(from));
        while (from < to && whitespace(value.codePointBefore(to))) to -= Character.charCount(value.codePointBefore(to));
        return value.substring(from, to);
    }
    private static boolean whitespace(int codePoint) { return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint); }
}
