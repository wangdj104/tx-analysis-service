package org.familyhealthcare.service.visitquestion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** 只验证有限输入与字节预算；身份、临床权限及状态转换必须由当前事务另行核验。 */
public final class VisitQuestionContracts {
    public static final long MAX_ID = 9007199254740991L;
    public static final int ORDINARY_BYTES = 64 * 1024;
    public static final int RAW_BYTES = 128 * 1024;
    public static final int BASELINE_BYTES = 896 * 1024;
    public static final int EVENT_BYTES = 1024 * 1024;
    public static final int DETAIL_BYTES = 1024 * 1024;
    public static final int RESPONSE_BYTES = 1280 * 1024;
    public static final int EVENT_PAGE_BYTES = 4 * 1024 * 1024;
    public static final int RECEIPT_BYTES = 512;
    public static final Set<String> STATES = keys("OPEN", "UNRESOLVED", "ANSWERED", "FOLLOW_UP", "CLOSED");
    public static final Set<String> OPERATIONS = keys("CREATE", "ADOPT", "EDIT", "RECORD_ANSWER", "PUBLISH_ANSWER",
            "MARK_UNRESOLVED", "SET_FOLLOW_UP", "CLOSE", "REOPEN");
    public static final Set<String> CONTEXTS = keys("LEGACY_APPOINTMENT", "CARE_APPOINTMENT", "VISIT_RECORD");
    public static final Set<String> UNRESOLVED_REASONS = keys("NOT_ASKED", "NOT_ANSWERED", "MORE_INFORMATION_NEEDED", "VISIT_CANCELLED", "OTHER");
    public static final Set<String> CLOSE_REASONS = keys("ANSWER_SUFFICIENT", "FOLLOW_UP_FINISHED", "NO_LONGER_NEEDED", "DUPLICATE", "WITHDRAWN", "OTHER");
    public static final Set<String> REOPEN_REASONS = keys("NEW_INFORMATION", "ANSWER_UNCLEAR", "FOLLOW_UP_NOT_DONE", "CONTEXT_CHANGED", "OTHER");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private VisitQuestionContracts() { }

    public static long id(Object value, String field) {
        long result = WorkflowData.requireId(value, field);
        if (result > MAX_ID) throw invalid(field + " exceeds the safe integer range.");
        return result;
    }

    public static long version(Object value) {
        if (value instanceof Number && !(value instanceof Float) && !(value instanceof Double)
                && "0".equals(value.toString())) return 0;
        return id(value, "expectedVersion");
    }

    public static long nextVersion(long value) {
        if (value < 0 || value >= MAX_ID) throw invalid("Question version cannot advance.");
        return value + 1;
    }

    /** 同一命令的空创建目标、患者、版本、操作和规范化正文全部进入哈希。 */
    public static String envelope(String payload, Long target, long expectedVersion) {
        requireBytes(payload, ORDINARY_BYTES, "command");
        Map<String,Object> input = parse(payload);
        exact(input, keys("schemaVersion", "patientId", "questionId", "expectedVersion", "operation", "body"));
        if (version(input.get("schemaVersion")) != 1) throw invalid("Unsupported question envelope version.");
        long patient = id(input.get("patientId"), "patientId");
        Long question = input.get("questionId") == null ? null : id(input.get("questionId"), "questionId");
        long version = version(input.get("expectedVersion"));
        if (!Objects.equals(target, question) || expectedVersion != version) throw invalid("Question envelope target/version mismatch.");
        String operation = choice(input.get("operation"), OPERATIONS, "operation");
        if ((question == null) != "CREATE".equals(operation)
                || (("CREATE".equals(operation) || "ADOPT".equals(operation)) && version != 0))
            throw invalid("Question operation has an invalid target/version.");
        nextVersion(version);
        Map<String,Object> body = body(operation, object(input.get("body")));
        String normalized = WorkflowData.json(WorkflowData.map("schemaVersion", 1, "patientId", patient,
                "questionId", question, "expectedVersion", version, "operation", operation, "body", body));
        requireBytes(normalized, ORDINARY_BYTES, "command");
        return normalized;
    }

    public static Map<String,Object> body(String operation, Map<String,Object> input) {
        choice(operation, OPERATIONS, "operation");
        Set<String> allowed;
        switch (operation) {
            case "CREATE": case "ADOPT": case "EDIT":
                allowed = keys("title", "description", "legacyAppointmentId", "context"); break;
            case "RECORD_ANSWER":
                allowed = keys("answer", "keepFollowUp", "reportedClinician", "reportedDate"); break;
            case "PUBLISH_ANSWER": allowed = keys("answer", "keepFollowUp"); break;
            case "MARK_UNRESOLVED": allowed = keys("reason", "reasonNote"); break;
            case "SET_FOLLOW_UP": allowed = keys("followUp", "responsibleUserId", "dueDate", "reasonNote"); break;
            case "CLOSE": allowed = keys("reason", "reasonNote", "resultText", "resultReference"); break;
            case "REOPEN": allowed = keys("reason", "reasonNote", "state"); break;
            default: throw invalid("Unsupported operation.");
        }
        fields(input, allowed);
        Map<String,Object> output = new TreeMap<>();
        for (Map.Entry<String,Object> entry : input.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            switch (key) {
                case "title": output.put(key, text(value, key, 200, true)); break;
                case "description": case "answer": case "resultText":
                    output.put(key, text(value, key, 4000, "answer".equals(key))); break;
                case "followUp": output.put(key, text(value, key, 2000, true)); break;
                case "reasonNote": output.put(key, text(value, key, 1000, false)); break;
                case "reportedClinician": output.put(key, text(value, key, 200, false)); break;
                case "legacyAppointmentId": case "responsibleUserId":
                    output.put(key, value == null ? null : id(value, key)); break;
                case "context": output.put(key, value == null ? null : context(object(value))); break;
                case "resultReference":
                    Map<String,Object> reference = object(value);
                    exact(reference, keys("type", "id"));
                    output.put(key, WorkflowData.map("type", choice(reference.get("type"),
                            keys("QUESTION_EVENT", "LEGACY_APPOINTMENT", "CARE_APPOINTMENT", "VISIT_RECORD"), "resultReference.type"),
                            "id", id(reference.get("id"), "resultReference.id"))); break;
                case "keepFollowUp":
                    if (!(value instanceof Boolean)) throw invalid("keepFollowUp must be boolean.");
                    output.put(key, value); break;
                case "dueDate": case "reportedDate": output.put(key, date(value)); break;
                case "state": output.put(key, choice(value, keys("OPEN", "UNRESOLVED"), key)); break;
                case "reason": output.put(key, choice(value, "CLOSE".equals(operation) ? CLOSE_REASONS
                        : "REOPEN".equals(operation) ? REOPEN_REASONS : UNRESOLVED_REASONS, key)); break;
                default: throw invalid("Unsupported request field.");
            }
        }
        if ("CREATE".equals(operation)) required(output, "title");
        if ("EDIT".equals(operation) && output.isEmpty()) throw invalid("A changed question field is required.");
        if (operation.endsWith("ANSWER")) required(output, "answer");
        if ("SET_FOLLOW_UP".equals(operation)) { required(output, "followUp"); required(output, "dueDate"); }
        if (Arrays.asList("MARK_UNRESOLVED", "CLOSE", "REOPEN").contains(operation)) {
            required(output, "reason");
            if ("OTHER".equals(output.get("reason"))) nonempty(output.get("reasonNote"), "reasonNote");
        }
        if ("REOPEN".equals(operation)) required(output, "state");
        if ("FOLLOW_UP_FINISHED".equals(output.get("reason")) && !output.containsKey("resultReference"))
            nonempty(output.get("resultText"), "resultText");
        requireBytes(WorkflowData.json(output), ORDINARY_BYTES, "command body");
        return Collections.unmodifiableMap(output);
    }

    private static Map<String,Object> context(Map<String,Object> input) {
        exact(input, keys("type", "id", "fingerprint"));
        Object fingerprint = input.get("fingerprint");
        if (!(fingerprint instanceof String) || !((String) fingerprint).matches("[0-9a-f]{64}"))
            throw invalid("Context fingerprint is required.");
        return WorkflowData.map("type", choice(input.get("type"), CONTEXTS, "context.type"),
                "id", id(input.get("id"), "context.id"), "fingerprint", fingerprint);
    }

    /** 回执只含定位与版本，不缓存任何问题、答案或人员名称。 */
    public static Map<String,Object> receipt(Map<String,Object> input) {
        exact(input, keys("questionId", "version", "eventId", "state"));
        Map<String,Object> result = WorkflowData.map("questionId", id(input.get("questionId"), "questionId"),
                "version", id(input.get("version"), "version"), "eventId", id(input.get("eventId"), "eventId"),
                "state", choice(input.get("state"), STATES, "state"));
        requireBytes(WorkflowData.json(result), RECEIPT_BYTES, "receipt");
        return Collections.unmodifiableMap(result);
    }

    /** 正文是结构化对象，仅序列化一次；原文基线仅允许出现在首个事件。 */
    public static String eventBody(long version, Map<String,Object> ordinary, Map<String,Object> baseline) {
        id(version, "version");
        Objects.requireNonNull(ordinary, "ordinary");
        Long target = ordinary.get("questionId") == null ? null : id(ordinary.get("questionId"), "questionId");
        String ordinaryJson = envelope(WorkflowData.json(ordinary), target, version - 1);
        if (version == 1 && target != null && baseline == null) throw invalid("The first adopted event requires its original baseline.");
        requireExtractedBytes(ordinaryJson, ORDINARY_BYTES, "ordinary event");
        Map<String,Object> body = WorkflowData.map("schemaVersion", 1, "ordinary", parse(ordinaryJson));
        if (baseline != null) {
            if (version != 1) throw invalid("A baseline is permitted only in the first event.");
            Map<String,Object> preserved = baseline(baseline);
            if ((target != null && !target.equals(preserved.get("id")))
                    || id(ordinary.get("patientId"), "patientId") != id(preserved.get("patientId"), "patientId"))
                throw invalid("Baseline belongs to a different question or patient.");
            requireExtractedBytes(WorkflowData.json(preserved), BASELINE_BYTES, "baseline");
            body.put("baseline", preserved);
        }
        String result = WorkflowData.json(body);
        requireBytes(result, baseline == null ? ORDINARY_BYTES : EVENT_BYTES, "event");
        return result;
    }

    /** 原生普通字段逐一保留，包括原始 JSON 字符串；不推断旧作者或重写旧日期。 */
    public static Map<String,Object> baseline(Map<String,Object> input) {
        exact(input, keys("id", "patientId", "userId", "kind", "title", "status", "assignedUserId", "actorId",
                "actorName", "eventAt", "notifyAt", "notifiedAt", "escalatedAt", "dataJson", "createdAt", "updatedAt"));
        Map<String,Object> result = new TreeMap<>();
        long bytes = 0;
        for (Map.Entry<String,Object> field : input.entrySet()) {
            Object value = field.getValue();
            if (Arrays.asList("id", "patientId", "userId").contains(field.getKey())) value = id(value, field.getKey());
            else if (Arrays.asList("assignedUserId", "actorId").contains(field.getKey()))
                value = value == null ? null : id(value, field.getKey());
            else if (value != null && !(value instanceof String)) throw invalid("Native baseline fields must be exact scalar values.");
            if (value != null) bytes += utf8(value.toString());
            result.put(field.getKey(), value);
        }
        if (!"QUESTION".equals(result.get("kind")) || !(result.get("title") instanceof String)
                || !(result.get("status") instanceof String)) throw invalid("A native question baseline is required.");
        if (bytes > RAW_BYTES) throw limit("Original question text exceeds the supported byte limit.");
        requireExtractedBytes(WorkflowData.json(result), BASELINE_BYTES, "baseline");
        return Collections.unmodifiableMap(result);
    }

    public static String sourceObservedHash(Map<String,Object> baseline) {
        Map<String,Object> protectedFields = new TreeMap<>(baseline(baseline));
        protectedFields.remove("updatedAt");
        return WorkflowData.hash(WorkflowData.json(protectedFields));
    }

    // MySQL JSON_EXTRACT 在结构分隔符后加空格。提前计入，避免临界正文在数据库规范化后超额。
    private static void requireExtractedBytes(String json, long maximum, String field) {
        long bytes = utf8(json);
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') quoted = !quoted;
            else if (!quoted && (c == ',' || c == ':')) bytes++;
        }
        if (bytes > maximum) throw limit(field + " exceeds the supported byte limit.");
    }

    public static void requireRawScalars(String... values) {
        long total = 0;
        for (String value : values) if (value != null) total += utf8(value);
        if (total > RAW_BYTES) throw limit("Original/current question text exceeds the supported byte limit.");
    }

    public static long utf8(String value) {
        if (value == null) throw invalid("Text must not be null.");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            return encoded.remaining();
        } catch (CharacterCodingException invalid) { throw invalid("Text contains invalid Unicode."); }
    }

    public static void requireBytes(String value, long maximum, String field) {
        if (utf8(value) > maximum) throw limit(field + " exceeds the supported byte limit.");
    }

    public static Map<String,Object> parse(String json) {
        try {
            Object value = JSON.readValue(json, Object.class);
            return object(value);
        } catch (java.io.IOException failure) { throw invalid("Invalid question JSON."); }
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if (!(value instanceof Map)) throw invalid("An object is required.");
        return (Map<String,Object>) value;
    }
    private static String text(Object value, String field, int maximum, boolean nonempty) {
        if (!(value instanceof String)) throw invalid(field + " must be text.");
        String text = (String) value;
        utf8(text);
        if (text.codePointCount(0, text.length()) > maximum) throw invalid(field + " is too long.");
        if (nonempty) nonempty(text, field);
        return text;
    }
    private static void nonempty(Object value, String field) {
        if (!(value instanceof String) || ((String) value).codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
            throw invalid(field + " is required.");
    }
    private static String date(Object value) {
        if (!(value instanceof String) || !((String) value).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("A calendar date is required.");
        try { LocalDate date = LocalDate.parse((String) value); if (date.getYear() < 1000) throw invalid("Date is out of range."); return date.toString(); }
        catch (java.time.DateTimeException failure) { throw invalid("Invalid calendar date."); }
    }
    private static String choice(Object value, Set<String> allowed, String field) {
        if (!(value instanceof String) || !allowed.contains(value)) throw invalid(field + " is not supported.");
        return (String) value;
    }
    private static void required(Map<String,Object> input, String key) { if (!input.containsKey(key)) throw invalid(key + " is required."); }
    private static void fields(Map<String,Object> input, Set<String> allowed) {
        if (input == null || !allowed.containsAll(input.keySet())) throw invalid("Unsupported question fields.");
    }
    private static void exact(Map<String,Object> input, Set<String> allowed) {
        fields(input, allowed);
        if (!input.keySet().equals(allowed)) throw invalid("Incomplete question fields.");
    }
    private static Set<String> keys(String... values) { return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values))); }
    private static WorkflowException invalid(String message) { return WorkflowException.invalid(message); }
    private static WorkflowException limit(String message) { return new WorkflowException(413, "QUESTION_LIMIT", message); }
}
