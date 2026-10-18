package org.familyhealthcare.service.visitfollowup;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 这里只校验严格的输入合同；当前资格、来源身份和转换还必须在持锁服务内核对。 */
public final class VisitFollowUpContracts {
    public static final long MAX_ID = 9007199254740991L;
    public static final int NATIVE_TEXT_BYTES = 64 * 1024, COMMAND_BYTES = 72 * 1024;
    public static final int RECEIPT_BYTES = 512, RESPONSE_BYTES = 256 * 1024;
    public static final int DEFAULT_PAGE = 25, MAX_PAGE = 25, MAX_LINKS = 10, QUERY_TIMEOUT_SECONDS = 5;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final Set<String> ENVELOPE_FIELDS = keys("commandKey", "patientId", "sourceKind", "sourceId",
            "operation", "expectedVersion", "expectedSourceFingerprint", "body");
    private VisitFollowUpContracts() { }

    public enum SourceKind {
        CARE_ITEM_APPOINTMENT("care_item", "care_item_id"), CARE_APPOINTMENT("care_appointment", "care_appointment_id"),
        VISIT_RECORD("visit_record", "visit_record_id");
        private final String table, column;
        SourceKind(String table, String column) { this.table = table; this.column = column; }
        public String tableName() { return table; }
        /** 返回旁表中可空的原生引用列，不是原生表的主键列。 */
        public String idColumn() { return column; }
        public boolean isAppointment() { return this != VISIT_RECORD; }
    }
    public enum Operation {
        TRACK_APPOINTMENT, RESCHEDULE, CANCEL, RECORD_ATTENDANCE, COMPLETE_SELECTED_APPOINTMENT, CORRECT_ATTENDANCE,
        SET_NEXT_DECISION, CREATE_NEXT, LINK_NEXT, REPLACE_NEXT, CREATE_VISIT, PUBLISH_VISIT, SET_DISPOSITION, REVISE_DISPOSITION;
        public boolean isVisitBinding() { return this == SET_DISPOSITION || this == REVISE_DISPOSITION; }
        public boolean isVisitOperation() { return isVisitBinding() || this == CREATE_VISIT || this == PUBLISH_VISIT; }
    }
    public enum TimeKind { KNOWN, UNKNOWN }
    public enum Attendance { UNVERIFIED, ATTENDED, NO_SHOW }
    public enum AttendanceOrigin { UNKNOWN, REPORTED, SELECTED_CLINICIAN_RECORDED }
    public enum NextDecision { NEEDS_DECISION, SCHEDULED, NOT_ARRANGED, CLOSED_UNRESOLVED }
    public enum Disposition { UNASSESSED, NO_FOLLOW_UP, ACTIONS_REQUIRED }
    public enum LinkKind { CHECK, REVISIT, REFERRAL }

    /** 两类预约的 ID 不能混用；所属患者还要从原生行核实。 */
    public static final class SourceRef {
        private final SourceKind kind;
        private final long id;
        public SourceRef(SourceKind kind, long id) { this.kind = Objects.requireNonNull(kind, "kind"); this.id = id(id, "sourceId"); }
        public SourceKind getKind() { return kind; }
        public long getId() { return id; }
        public Map<String,Object> asMap() { return immutable(WorkflowData.map("sourceKind", kind.name(), "sourceId", id)); }
        @Override public boolean equals(Object other) { return other instanceof SourceRef && kind == ((SourceRef)other).kind && id == ((SourceRef)other).id; }
        @Override public int hashCode() { return Objects.hash(kind, id); }
        @Override public String toString() { return kind.name() + ":" + id; }
    }
    public static SourceRef source(Map<String,Object> input) {
        exact(input, keys("sourceKind", "sourceId"));
        return new SourceRef(choice(input.get("sourceKind"), SourceKind.class), id(input.get("sourceId"), "sourceId"));
    }

    /** 未知时间不填时间戳；已知时间分别保存真实 UTC 时刻和输入的偏移。 */
    public static final class ActualTime {
        private final TimeKind timeKind;
        private final Instant occurredAt;
        private final String inputOffset, canonicalInput;
        private final LocalDateTime nativeWallTime;
        private ActualTime(TimeKind kind, Instant instant, String offset, LocalDateTime wall, String input) {
            timeKind = kind; occurredAt = instant; inputOffset = offset; nativeWallTime = wall; canonicalInput = input;
        }
        public TimeKind getTimeKind() { return timeKind; }
        public Instant getOccurredAt() { return occurredAt; }
        public String getInputOffset() { return inputOffset; }
        public LocalDateTime getNativeWallTime() { return nativeWallTime; }
        public LocalDateTime getUtcDateTime() { return occurredAt == null ? null : LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC); }
        public Map<String,Object> asMap() { return immutable(WorkflowData.map("timeKind", timeKind.name(), "occurredAt", canonicalInput)); }
    }
    public static ActualTime actual(Map<String,Object> input, Clock clock) {
        exact(input, keys("timeKind", "occurredAt"));
        TimeKind kind = choice(input.get("timeKind"), TimeKind.class);
        if (kind == TimeKind.UNKNOWN) {
            if (input.get("occurredAt") != null) throw invalid("Unknown actual time must not contain a timestamp.");
            return new ActualTime(kind, null, null, null, null);
        }
        String value = scalar(input.get("occurredAt"));
        OffsetDateTime parsed = offsetTime(value);
        Instant instant = parsed.toInstant();
        if (instant.isAfter(Objects.requireNonNull(clock, "clock").instant())) throw invalid("Actual time must not be in the future.");
        String offset = value.endsWith("Z") ? "Z" : value.substring(value.length() - 6);
        LocalDateTime wall = parsed.toLocalDateTime();
        return new ActualTime(kind, instant, offset, wall, wall.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + offset);
    }
    public static ActualTime actual(Map<String,Object> input) { return actual(input, Clock.systemUTC()); }

    public static final class Command {
        private final String commandKey, expectedSourceFingerprint;
        private final long patientId, expectedVersion;
        private final SourceKind sourceKind;
        private final SourceRef source;
        private final Operation operation;
        private final Map<String,Object> body;
        private Command(String key, long patient, SourceKind kind, Long nativeId, Operation op, long version,
                        String fingerprint, Map<String,Object> body) {
            commandKey = key; patientId = patient; sourceKind = kind;
            source = nativeId == null ? null : new SourceRef(kind, nativeId);
            operation = op; expectedVersion = version; expectedSourceFingerprint = fingerprint; this.body = immutable(body);
        }
        public String getCommandKey() { return commandKey; }
        public long getPatientId() { return patientId; }
        public SourceKind getSourceKind() { return sourceKind; }
        public Long getSourceId() { return source == null ? null : source.id; }
        public SourceRef getSource() { return source; }
        public Operation getOperation() { return operation; }
        public long getExpectedVersion() { return expectedVersion; }
        public String getExpectedSourceFingerprint() { return expectedSourceFingerprint; }
        public Map<String,Object> getBody() { return body; }
        public boolean isVisitBinding() { return operation.isVisitBinding(); }
        public String canonicalBody() { return WorkflowData.json(body); }
        public Map<String,Object> asMap() { return immutable(WorkflowData.map("commandKey", commandKey, "patientId", patientId,
                "sourceKind", sourceKind.name(), "sourceId", getSourceId(), "operation", operation.name(),
                "expectedVersion", expectedVersion, "expectedSourceFingerprint", expectedSourceFingerprint, "body", body)); }
    }
    public static Command parse(Map<String,Object> input) { return parse(input, Clock.systemUTC()); }
    public static Command parse(Map<String,Object> input, Clock clock) {
        exact(input, ENVELOPE_FIELDS);
        String key = commandKey(input.get("commandKey"));
        long patient = id(input.get("patientId"), "patientId"), version = version(input.get("expectedVersion"));
        nextVersion(version);
        SourceKind kind = choice(input.get("sourceKind"), SourceKind.class);
        Operation operation = choice(input.get("operation"), Operation.class);
        Long nativeId = input.get("sourceId") == null ? null : id(input.get("sourceId"), "sourceId");
        if ((operation == Operation.CREATE_VISIT) != (nativeId == null)
                || operation.isVisitOperation() != (kind == SourceKind.VISIT_RECORD)
                || (operation == Operation.COMPLETE_SELECTED_APPOINTMENT && kind != SourceKind.CARE_APPOINTMENT))
            throw invalid("The operation does not match its typed native source.");
        if ((operation == Operation.CREATE_VISIT || operation == Operation.TRACK_APPOINTMENT) && version != 0)
            throw invalid("A new source requires expectedVersion zero.");
        if (version == 0 && operation != Operation.CREATE_VISIT && operation != Operation.TRACK_APPOINTMENT
                && operation != Operation.SET_DISPOSITION) throw invalid("This operation requires a managed source version.");
        String fingerprint = input.get("expectedSourceFingerprint") == null ? null : fingerprint(input.get("expectedSourceFingerprint"));
        if ((operation == Operation.CREATE_VISIT) != (fingerprint == null)) throw invalid("The current source fingerprint is required.");
        Map<String,Object> normalized = body(operation, kind, nativeId, object(input.get("body")), Objects.requireNonNull(clock, "clock"));
        Command command = new Command(key, patient, kind, nativeId, operation, version, fingerprint, normalized);
        requireBytes(WorkflowData.json(command.asMap()), COMMAND_BYTES);
        return command;
    }
    /** HTTP 入口必须使用原始 JSON 解析；转成 Map 后已经无法发现被覆盖的重复字段。 */
    public static Command parseJson(String json) { return parseJson(json, Clock.systemUTC()); }
    public static Command parseJson(String json, Clock clock) { return parse(parseObject(json, COMMAND_BYTES), clock); }

    /** 完整身份和协议版本一起进入规范摘要；操作者只能来自当前会话，不能由请求指定。 */
    public static String envelope(long actor, Command command) {
        Objects.requireNonNull(command, "command");
        Map<String,Object> value = new TreeMap<>(command.asMap());
        value.put("schemaVersion", 1L); value.put("actorId", id(actor, "actorId"));
        String result = WorkflowData.json(value); requireBytes(result, COMMAND_BYTES); return result;
    }

    private static Map<String,Object> body(Operation op, SourceKind kind, Long nativeId, Map<String,Object> input, Clock clock) {
        Map<String,Object> output = new TreeMap<>();
        switch (op) {
            case TRACK_APPOINTMENT:
                exact(input, keys("responsibleUserId", "dueAt")); responsibility(input, output); break;
            case RESCHEDULE:
                exact(input, keys("schedule", "reason")); output.put("schedule", schedule(kind, object(input.get("schedule"))));
                output.put("reason", text(input.get("reason"), 500, true)); break;
            case CANCEL:
                fields(input, keys("reason", "successorDisposition")); output.put("reason", text(input.get("reason"), 500, true));
                output.put("successorDisposition", input.get("successorDisposition") == null ? null
                        : token(input.get("successorDisposition"), "KEEP_LINK", "UNLINK")); break;
            case RECORD_ATTENDANCE: case CORRECT_ATTENDANCE: case COMPLETE_SELECTED_APPOINTMENT:
                Set<String> allowed = keys("actual");
                if (op != Operation.COMPLETE_SELECTED_APPOINTMENT) { allowed.add("attendance"); allowed.add("reason"); }
                if (op == Operation.CORRECT_ATTENDANCE) allowed.add("eventId"); exact(input, allowed);
                ActualTime actual = actual(object(input.get("actual")), clock); output.put("actual", actual.asMap());
                if (op != Operation.COMPLETE_SELECTED_APPOINTMENT) {
                    Attendance attendance = choice(input.get("attendance"), Attendance.class);
                    if (attendance == Attendance.UNVERIFIED && (op != Operation.CORRECT_ATTENDANCE || actual.timeKind != TimeKind.UNKNOWN))
                        throw invalid("Only an explicit correction may withdraw an attendance fact, with unknown actual time.");
                    output.put("attendance", attendance.name()); output.put("reason", text(input.get("reason"), 500, true));
                }
                if (op == Operation.CORRECT_ATTENDANCE) output.put("eventId", id(input.get("eventId"), "eventId")); break;
            case SET_NEXT_DECISION:
                exact(input, keys("nextDecision", "responsibleUserId", "dueAt", "reason"));
                NextDecision decision = choice(input.get("nextDecision"), NextDecision.class);
                if (decision == NextDecision.SCHEDULED) throw invalid("A scheduled next appointment requires an exact successor command.");
                output.put("nextDecision", decision.name()); responsibility(input, output);
                output.put("reason", text(input.get("reason"), 500, true)); break;
            case CREATE_NEXT: case LINK_NEXT: case REPLACE_NEXT:
                Set<String> next = keys("responsibleUserId", "dueAt", "reason");
                if (op != Operation.LINK_NEXT) next.add("schedule");
                if (op != Operation.CREATE_NEXT) next.add("successor");
                if (op == Operation.REPLACE_NEXT) next.add("previousSuccessorDisposition"); fields(input, next);
                boolean hasSchedule = input.get("schedule") != null, hasSuccessor = input.get("successor") != null;
                if (hasSchedule == hasSuccessor) throw invalid("Select exactly one explicit successor or new schedule.");
                if ((op == Operation.CREATE_NEXT && !hasSchedule) || (op == Operation.LINK_NEXT && !hasSuccessor))
                    throw invalid("The next appointment command has the wrong target shape.");
                if (hasSchedule) output.put("schedule", schedule(kind, object(input.get("schedule"))));
                else {
                    Map<String,Object> target = binding(object(input.get("successor")), true);
                    if (!kind.name().equals(target.get("sourceKind")) || Objects.equals(nativeId, target.get("sourceId")))
                        throw invalid("The successor must be another appointment of the same native kind.");
                    output.put("successor", target);
                }
                responsibility(input, output); output.put("reason", text(input.get("reason"), 500, true));
                if (op == Operation.REPLACE_NEXT) output.put("previousSuccessorDisposition", token(input.get("previousSuccessorDisposition"), "KEEP_APPOINTMENT"));
                break;
            case CREATE_VISIT:
                fields(input, keys("actual", "originAppointment", "diagnosisSummary", "treatmentSummary", "followUpAdvice", "followUpAt"));
                ActualTime visitActual = actual(object(input.get("actual")), clock);
                if (visitActual.timeKind != TimeKind.KNOWN) throw invalid("Enter the real visit time and offset before saving a new summary.");
                output.put("actual", visitActual.asMap());
                output.put("originAppointment", input.get("originAppointment") == null ? null : binding(object(input.get("originAppointment")), false));
                long total = 0;
                for (String field : Arrays.asList("diagnosisSummary", "treatmentSummary", "followUpAdvice")) {
                    String value = input.get(field) == null ? null : text(input.get(field), NATIVE_TEXT_BYTES, false);
                    if (value != null) total += utf8(value); output.put(field, value);
                }
                if (total > NATIVE_TEXT_BYTES) throw limit();
                if (blank((String)output.get("diagnosisSummary")) && blank((String)output.get("treatmentSummary")))
                    throw invalid("Enter a diagnosis or treatment summary.");
                output.put("followUpAt", input.get("followUpAt") == null ? null : wallTime(input.get("followUpAt")).toString()); break;
            case PUBLISH_VISIT: exact(input, keys()); break;
            case SET_DISPOSITION: case REVISE_DISPOSITION:
                fields(input, keys("disposition", "reason", "links"));
                Disposition disposition = choice(input.get("disposition"), Disposition.class);
                if (disposition == Disposition.UNASSESSED) throw invalid("Select an explicit professional disposition.");
                output.put("disposition", disposition.name());
                boolean reasonRequired = disposition == Disposition.NO_FOLLOW_UP || op == Operation.REVISE_DISPOSITION;
                output.put("reason", input.get("reason") == null && !reasonRequired ? null : text(input.get("reason"), 1000, reasonRequired));
                List<Map<String,Object>> links = links(input.get("links"));
                if ((disposition == Disposition.ACTIONS_REQUIRED) != !links.isEmpty())
                    throw invalid("The disposition and exact action links do not agree.");
                output.put("links", links); break;
            default: throw invalid("Unsupported follow-up command.");
        }
        return immutable(output);
    }
    private static void responsibility(Map<String,Object> input, Map<String,Object> output) {
        output.put("responsibleUserId", id(input.get("responsibleUserId"), "responsibleUserId"));
        OffsetDateTime due = offsetTime(scalar(input.get("dueAt")));
        if (!due.getOffset().equals(ZoneOffset.UTC)) throw invalid("The administrative deadline must be explicit UTC.");
        output.put("dueAt", due.toInstant().toString());
    }
    private static Map<String,Object> schedule(SourceKind kind, Map<String,Object> input) {
        Map<String,Object> result = new TreeMap<>();
        if (kind == SourceKind.CARE_ITEM_APPOINTMENT) {
            exact(input, keys("eventAt", "notifyAt")); LocalDateTime start = wallTime(input.get("eventAt"));
            LocalDateTime notify = input.get("notifyAt") == null ? null : wallTime(input.get("notifyAt"));
            if (notify != null && notify.isAfter(start)) throw invalid("Reminder time must not be after the appointment.");
            result.put("eventAt", start.toString()); result.put("notifyAt", notify == null ? null : notify.toString());
        } else if (kind == SourceKind.CARE_APPOINTMENT) {
            fields(input, keys("doctorUserId", "scheduleId", "startAt", "endAt", "appointmentType", "consultationMode"));
            LocalDateTime start = wallTime(input.get("startAt")), end = wallTime(input.get("endAt"));
            if (!end.isAfter(start)) throw invalid("Appointment end must be after its start.");
            result.put("doctorUserId", id(input.get("doctorUserId"), "doctorUserId"));
            result.put("scheduleId", input.get("scheduleId") == null ? null : id(input.get("scheduleId"), "scheduleId"));
            result.put("startAt", start.toString()); result.put("endAt", end.toString());
            if (input.containsKey("appointmentType")) result.put("appointmentType", text(input.get("appointmentType"), 30, true));
            if (input.containsKey("consultationMode")) result.put("consultationMode", token(input.get("consultationMode"), "IN_PERSON", "TEXT", "VOICE", "VIDEO"));
        } else throw invalid("A native appointment schedule is required.");
        return immutable(result);
    }
    private static Map<String,Object> binding(Map<String,Object> input, boolean successor) {
        Set<String> fields = keys("sourceKind", "sourceId", "expectedVersion", "expectedSourceFingerprint");
        if (successor) fields.add("track"); exact(input, fields);
        SourceKind kind = choice(input.get("sourceKind"), SourceKind.class);
        if (!kind.isAppointment()) throw invalid("An exact native appointment reference is required.");
        long version = version(input.get("expectedVersion")); nextVersion(version);
        Map<String,Object> value = WorkflowData.map("sourceKind", kind.name(), "sourceId", id(input.get("sourceId"), "sourceId"),
                "expectedVersion", version, "expectedSourceFingerprint", fingerprint(input.get("expectedSourceFingerprint")));
        if (successor) {
            if (!Boolean.TRUE.equals(input.get("track"))) throw invalid("Explicitly confirm management of the selected successor.");
            value.put("track", true);
        }
        return immutable(value);
    }
    private static List<Map<String,Object>> links(Object value) {
        if (!(value instanceof List) || ((List<?>)value).size() > MAX_LINKS) throw invalid("At most ten exact action links are supported.");
        List<Map<String,Object>> result = new ArrayList<>(); Set<Long> actionIds = new HashSet<>();
        for (Object item : (List<?>)value) {
            Map<String,Object> link = object(item); exact(link, keys("planId", "revisionId", "actionId", "kind"));
            long action = id(link.get("actionId"), "actionId");
            if (!actionIds.add(action)) throw invalid("An action must not appear twice in one disposition.");
            result.add(immutable(WorkflowData.map("planId", id(link.get("planId"), "planId"),
                    "revisionId", id(link.get("revisionId"), "revisionId"), "actionId", action,
                    "kind", choice(link.get("kind"), LinkKind.class).name())));
        }
        result.sort(Comparator.<Map<String,Object>>comparingLong(link -> (Long)link.get("planId"))
                .thenComparingLong(link -> (Long)link.get("revisionId")).thenComparingLong(link -> (Long)link.get("actionId")));
        return Collections.unmodifiableList(result);
    }

    /** 回执只含精确身份和版本，不复制姓名、权限、排程或临床正文。 */
    public static Map<String,Object> receipt(Map<String,Object> input) {
        Set<String> required = keys("workflowId", "sourceKind", "sourceId", "version", "eventId", "operation");
        Set<String> allowed = new LinkedHashSet<>(required); allowed.add("successorKind"); allowed.add("successorId");
        fields(input, allowed); if (!input.keySet().containsAll(required)) throw invalid("A complete minimal receipt is required.");
        SourceKind kind = choice(input.get("sourceKind"), SourceKind.class); Operation op = choice(input.get("operation"), Operation.class);
        if (op.isVisitOperation() != (kind == SourceKind.VISIT_RECORD)
                || (op == Operation.COMPLETE_SELECTED_APPOINTMENT && kind != SourceKind.CARE_APPOINTMENT)) throw invalid("Receipt source and operation do not agree.");
        Map<String,Object> result = WorkflowData.map("workflowId", id(input.get("workflowId"), "workflowId"), "sourceKind", kind.name(),
                "sourceId", id(input.get("sourceId"), "sourceId"), "version", id(input.get("version"), "version"),
                "eventId", id(input.get("eventId"), "eventId"), "operation", op.name());
        boolean hasKind = input.containsKey("successorKind"), hasId = input.containsKey("successorId");
        boolean needsSuccessor = op == Operation.CREATE_NEXT || op == Operation.LINK_NEXT || op == Operation.REPLACE_NEXT;
        if (hasKind != hasId || hasKind != needsSuccessor) throw invalid("The receipt must identify the exact created or linked successor.");
        if (hasKind) {
            SourceKind successor = choice(input.get("successorKind"), SourceKind.class);
            long target = id(input.get("successorId"), "successorId");
            if (successor != kind || target == (Long)result.get("sourceId")) throw invalid("Receipt successor has an invalid native identity.");
            result.put("successorKind", successor.name()); result.put("successorId", target);
        }
        requireBytes(WorkflowData.json(result), RECEIPT_BYTES); return immutable(result);
    }
    public static Map<String,Object> storedReceipt(String json) {
        try { return receipt(parseObject(json, RECEIPT_BYTES)); }
        catch (RuntimeException failure) { throw new WorkflowException(409, "RECEIPT_INVALID", "Stored follow-up receipt is invalid."); }
    }
    public static long id(Object value) { return id(value, "id"); }
    public static long id(Object value, String field) {
        long result = integer(value); if (result <= 0 || result > MAX_ID) throw invalid(field + " must be a positive safe integer."); return result;
    }
    public static long version(Object value) {
        long result = integer(value); if (result < 0 || result > MAX_ID) throw invalid("Version must be a non-negative safe integer."); return result;
    }
    public static long nextVersion(long value) {
        if (value < 0 || value >= MAX_ID) throw invalid("The source version cannot advance."); return value + 1;
    }
    private static long integer(Object value) {
        try {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return ((Number)value).longValue();
            if (value instanceof BigInteger) return ((BigInteger)value).longValueExact();
        } catch (ArithmeticException ignored) { throw invalid("An exact integer is required."); }
        throw invalid("An exact integer is required.");
    }
    public static String fingerprint(Object value) {
        if (!(value instanceof String) || !((String)value).matches("[0-9a-f]{64}")) throw invalid("A current source fingerprint is required.");
        return (String)value;
    }
    public static String commandKey(Object value) {
        if (!(value instanceof String) || !((String)value).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid("A canonical UUID command key is required."); return (String)value;
    }
    private static OffsetDateTime offsetTime(String value) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?(?:Z|[+-][0-9]{2}:[0-9]{2})")) throw invalid("A real ISO-8601 timestamp with an explicit offset is required.");
            OffsetDateTime result = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            if (result.getNano() % 1000 != 0) throw invalid("Actual and deadline timestamps must be exactly representable in microseconds.");
            validYear(result.toLocalDateTime()); validYear(LocalDateTime.ofInstant(result.toInstant(), ZoneOffset.UTC)); return result;
        } catch (DateTimeException failure) { throw invalid("A valid timestamp with an explicit offset is required."); }
    }
    public static LocalDateTime wallTime(Object value) {
        try {
            String text = scalar(value);
            if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")) throw invalid("Native appointment time must be a wall-clock time without an offset or fractional seconds.");
            LocalDateTime result = LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME); validYear(result); return result;
        } catch (DateTimeException failure) { throw invalid("A valid native wall-clock time is required."); }
    }
    private static void validYear(LocalDateTime value) { if (value.getYear() < 1000 || value.getYear() > 9999) throw invalid("The timestamp is outside the supported database range."); }
    /** 文本只统一换行符；有意义的空白和 Unicode 原样保留。 */
    private static String text(Object input, int maximum, boolean required) {
        String value = scalar(input).replace("\r\n", "\n").replace('\r', '\n'); utf8(value);
        if (value.codePointCount(0, value.length()) > maximum || (required && blank(value))) throw invalid("The text is missing or exceeds its supported length.");
        return value;
    }
    private static boolean blank(String value) { return value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)); }
    private static String scalar(Object value) { if (!(value instanceof String)) throw invalid("A text value is required."); return (String)value; }
    private static String token(Object value, String... values) {
        if (!(value instanceof String) || !Arrays.asList(values).contains(value)) throw invalid("An unsupported machine token was supplied."); return (String)value;
    }
    private static <E extends Enum<E>> E choice(Object value, Class<E> type) {
        if (!(value instanceof String)) throw invalid("An exact machine token is required.");
        try { return Enum.valueOf(type, (String)value); } catch (IllegalArgumentException failure) { throw invalid("An unsupported machine token was supplied."); }
    }
    public static int utf8(String value) {
        if (value == null) throw invalid("Text must not be null.");
        try { return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining(); }
        catch (java.nio.charset.CharacterCodingException failure) { throw invalid("Text contains invalid Unicode."); }
    }
    public static void requireBytes(String value, int maximum) { if (utf8(value) > maximum) throw limit(); }
    public static Map<String,Object> parseObject(String json, int maximumBytes) {
        requireBytes(json, maximumBytes);
        try {
            int depth = 0;
            try (JsonParser parser = JSON.getFactory().createParser(json)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) { if (++depth > 12) throw invalid("The request nesting is too deep."); }
                    else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
                }
            }
            return object(JSON.readValue(json, Object.class));
        } catch (IOException failure) { throw invalid("Invalid follow-up JSON."); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if (!(value instanceof Map)) throw invalid("A structured object is required.");
        for (Object key : ((Map<?,?>)value).keySet()) if (!(key instanceof String)) throw invalid("Request field names must be text.");
        return (Map<String,Object>)value;
    }
    private static Set<String> keys(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }
    private static void fields(Map<String,Object> input, Set<String> allowed) {
        if (input == null || !allowed.containsAll(input.keySet())) throw invalid("Unknown request fields are not permitted.");
    }
    private static void exact(Map<String,Object> input, Set<String> expected) {
        if (input == null || !input.keySet().equals(expected)) throw invalid("The request fields do not match this operation.");
    }
    @SuppressWarnings("unchecked") private static <T> T freeze(T value) {
        if (value instanceof Map) {
            Map<String,Object> result = new TreeMap<>();
            for (Map.Entry<String,Object> entry : ((Map<String,Object>)value).entrySet()) result.put(entry.getKey(), freeze(entry.getValue()));
            return (T)Collections.unmodifiableMap(result);
        }
        if (value instanceof List) { List<Object> result = new ArrayList<>(); for (Object item : (List<?>)value) result.add(freeze(item)); return (T)Collections.unmodifiableList(result); }
        return value;
    }
    private static Map<String,Object> immutable(Map<String,Object> value) { return freeze(value); }
    public static WorkflowException invalid(String message) { return new WorkflowException(400, "VISIT_FOLLOWUP_INVALID_REQUEST", message); }
    public static WorkflowException limit() { return new WorkflowException(413, "VISIT_FOLLOWUP_LIMIT", "The follow-up request exceeds the supported size limit."); }
    public static WorkflowException conflict() { return new WorkflowException(409, "COMMAND_CONFLICT", "This command key was already used for a different request."); }
}
