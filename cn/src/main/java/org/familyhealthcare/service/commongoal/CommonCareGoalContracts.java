package org.familyhealthcare.service.commongoal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;

import java.math.BigInteger;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** 共同目标只保存明确填报的文字；字段校验不是患者同意、当前授权或医学判断。 */
public final class CommonCareGoalContracts {
    public static final long MAX_ID = 9007199254740991L;
    public static final int COMMAND_BYTES = 32 * 1024, EVENT_BYTES = 60 * 1024, RECEIPT_BYTES = 512;
    public static final int DEFAULT_PAGE = 20, MAX_PAGE = 40;
    public enum State { PROPOSED, ACTIVE, COMPLETED, CANCELLED }
    public enum Operation { CREATE, REVISE, ACCEPT, DECLINE, STEP_BACK, ADD_PROGRESS, ADD_FEEDBACK, COMPLETE, CANCEL, REOPEN }
    public enum FeedbackSource { ACCOUNT_STATEMENT, REPORTED_PATIENT, REPORTED_PARTICIPANT, NOT_OBTAINED }
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_LONG_FOR_INTS);
    private CommonCareGoalContracts() { }

    /** REVISE 是完整替换这组允许字段，不接受补丁里的隐藏状态或默认提名。 */
    public static final class Content {
        private final String title, goalText, progressCriterion, responsibility, reviewDate;
        private final Long nomineeId;
        public Content(String title, String goalText, String progressCriterion, String responsibility, String reviewDate, Long nomineeId) {
            this.title = text(title, 120); this.goalText = text(goalText, 1000);
            this.progressCriterion = text(progressCriterion, 500); this.responsibility = text(responsibility, 500);
            this.reviewDate = date(reviewDate); this.nomineeId = nullableId(nomineeId);
        }
        public String getTitle() { return title; }
        public String getGoalText() { return goalText; }
        public String getProgressCriterion() { return progressCriterion; }
        public String getResponsibility() { return responsibility; }
        public String getReviewDate() { return reviewDate; }
        public Long getNomineeId() { return nomineeId; }
        public Content nominate(Long user) { return new Content(title, goalText, progressCriterion, responsibility, reviewDate, user); }
        public Map<String,Object> asMap() { return immutable(WorkflowData.map("title", title, "goalText", goalText,
                "progressCriterion", progressCriterion, "responsibility", responsibility, "reviewDate", reviewDate, "nomineeId", nomineeId)); }
        @Override public boolean equals(Object other) { return other instanceof Content && asMap().equals(((Content) other).asMap()); }
        @Override public int hashCode() { return asMap().hashCode(); }
    }
    public static final class Command {
        private final String commandKey, reason, note, reportedDate, result, canonical;
        private final long patientId, expectedVersion;
        private final Long goalId;
        private final Operation operation;
        private final Content content;
        private final FeedbackSource feedbackSource;
        private Command(String key, long patient, Long goal, long version, Operation operation, Content content,
                        String reason, String note, String reportedDate, FeedbackSource feedbackSource, String result) {
            this.commandKey = key; this.patientId = patient; this.goalId = goal; this.expectedVersion = version;
            this.operation = operation; this.content = content; this.reason = reason; this.note = note;
            this.reportedDate = reportedDate; this.feedbackSource = feedbackSource; this.result = result;
            Map<String,Object> envelope = WorkflowData.map("commandKey", key, "patientId", patient, "goalId", goal,
                    "expectedVersion", version, "operation", operation.name());
            if (content != null) envelope.put("content", content.asMap());
            if (reason != null) envelope.put("reason", reason);
            if (note != null) { envelope.put("note", note); envelope.put("reportedDate", reportedDate); }
            if (feedbackSource != null) envelope.put("feedbackSource", feedbackSource.name());
            if (result != null) envelope.put("result", result);
            canonical = WorkflowData.json(envelope); requireBytes(canonical, COMMAND_BYTES);
        }
        public String getCommandKey() { return commandKey; }
        public long getPatientId() { return patientId; }
        public Long getGoalId() { return goalId; }
        public long getExpectedVersion() { return expectedVersion; }
        public Operation getOperation() { return operation; }
        public Content getContent() { return content; }
        public String getReason() { return reason; }
        public String getNote() { return note; }
        public String getReportedDate() { return reportedDate; }
        public FeedbackSource getFeedbackSource() { return feedbackSource; }
        public String getResult() { return result; }
        public String canonicalEnvelope() { return canonical; }
        public String payloadHash() { return WorkflowData.hash(canonical); }
    }
    public static Command command(String json) {
        Map<String,Object> value = parse(json, COMMAND_BYTES);
        Set<String> allowed = keys("commandKey", "patientId", "goalId", "expectedVersion", "operation");
        if (!value.keySet().containsAll(allowed)) throw invalid();
        String key = commandKey(value.get("commandKey")); long patient = id(value.get("patientId"));
        Long goal = nullableId(value.get("goalId")); long version = version(value.get("expectedVersion"));
        Operation operation = choice(value.get("operation"), Operation.class);
        if ((operation == Operation.CREATE) != (goal == null) || (operation == Operation.CREATE) != (version == 0)) throw invalid();
        Content content = null; String reason = null, note = null, reportedDate = null, result = null; FeedbackSource source = null;
        switch (operation) {
            case CREATE: case REVISE: allowed.add("content"); content = content(object(value.get("content"))); break;
            default: break;
        }
        if (Arrays.asList(Operation.REVISE, Operation.DECLINE, Operation.STEP_BACK, Operation.CANCEL, Operation.REOPEN).contains(operation)) {
            allowed.add("reason"); reason = text(value.get("reason"), 1000);
        }
        if (operation == Operation.ADD_PROGRESS || operation == Operation.ADD_FEEDBACK) {
            allowed.add("note"); allowed.add("reportedDate"); note = text(value.get("note"), 1000);
            reportedDate = date(value.get("reportedDate"));
        }
        if (operation == Operation.ADD_FEEDBACK) { allowed.add("feedbackSource"); source = choice(value.get("feedbackSource"), FeedbackSource.class); }
        if (operation == Operation.COMPLETE) { allowed.add("result"); result = text(value.get("result"), 1000); }
        if (!allowed.containsAll(value.keySet())) throw invalid();
        return new Command(key, patient, goal, version, operation, content, reason, note, reportedDate, source, result);
    }
    public static Content content(Map<String,Object> value) {
        exact(value, keys("title", "goalText", "progressCriterion", "responsibility", "reviewDate", "nomineeId"));
        return new Content(text(value.get("title"), 120), text(value.get("goalText"), 1000), text(value.get("progressCriterion"), 500),
                text(value.get("responsibility"), 500), date(value.get("reviewDate")), nullableId(value.get("nomineeId")));
    }

    /** 存储投影保留终态接手事实；后来的撤权只影响开放目标的当前资格展示。 */
    public static final class Goal {
        private final long id, patientId, creatorId, version, cycle;
        private final Content content;
        private final State state;
        private final Long acceptedBy;
        private final Instant acceptedAt, createdAt, updatedAt;
        public Goal(long id, long patient, long creator, Content content, State state, long version, long cycle,
                    Long acceptedBy, Instant acceptedAt, Instant createdAt, Instant updatedAt) {
            this.id = id(id); this.patientId = id(patient); this.creatorId = id(creator);
            this.content = Objects.requireNonNull(content); this.state = Objects.requireNonNull(state);
            this.version = id(version); this.cycle = id(cycle); this.acceptedBy = nullableId(acceptedBy);
            this.acceptedAt = acceptedAt; this.createdAt = Objects.requireNonNull(createdAt); this.updatedAt = Objects.requireNonNull(updatedAt);
            if (cycle > version || (acceptedBy == null) != (acceptedAt == null)
                    || (acceptedBy != null && !acceptedBy.equals(content.nomineeId))
                    || (state == State.PROPOSED && acceptedBy != null)
                    || ((state == State.ACTIVE || state == State.COMPLETED) && acceptedBy == null)) throw integrity();
        }
        public long getId() { return id; }
        public long getPatientId() { return patientId; }
        public long getCreatorId() { return creatorId; }
        public Content getContent() { return content; }
        public State getState() { return state; }
        public long getVersion() { return version; }
        public long getCycle() { return cycle; }
        public Long getAcceptedBy() { return acceptedBy; }
        public Instant getAcceptedAt() { return acceptedAt; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public boolean isOpen() { return state == State.PROPOSED || state == State.ACTIVE; }
        public Map<String,Object> asMap() { return immutable(WorkflowData.map("goalId", id, "patientId", patientId, "creatorId", creatorId,
                "content", content.asMap(), "state", state.name(), "version", version, "cycle", cycle, "acceptedBy", acceptedBy,
                "acceptedAt", instant(acceptedAt), "createdAt", instant(createdAt), "updatedAt", instant(updatedAt))); }
    }
    public static Goal goal(Map<String,Object> value) {
        exact(value, keys("goalId", "patientId", "creatorId", "content", "state", "version", "cycle", "acceptedBy", "acceptedAt", "createdAt", "updatedAt"));
        return new Goal(id(value.get("goalId")), id(value.get("patientId")), id(value.get("creatorId")), content(object(value.get("content"))),
                choice(value.get("state"), State.class), id(value.get("version")), id(value.get("cycle")), nullableId(value.get("acceptedBy")),
                timestamp(value.get("acceptedAt")), timestamp(value.get("createdAt")), timestamp(value.get("updatedAt")));
    }
    public static Map<String,Object> receipt(Map<String,Object> value) {
        exact(value, keys("goalId", "version", "cycle", "state", "operation"));
        Map<String,Object> receipt = immutable(WorkflowData.map("goalId", id(value.get("goalId")), "version", id(value.get("version")),
                "cycle", id(value.get("cycle")), "state", choice(value.get("state"), State.class).name(), "operation", choice(value.get("operation"), Operation.class).name()));
        if ((Long)receipt.get("cycle") > (Long)receipt.get("version")) throw integrity();
        requireBytes(WorkflowData.json(receipt), RECEIPT_BYTES); return receipt;
    }
    public static Map<String,Object> storedReceipt(String value) { return receipt(parse(value, RECEIPT_BYTES)); }
    public static Map<String,Object> receipt(Goal goal, Operation operation) {
        return receipt(WorkflowData.map("goalId", goal.id, "version", goal.version, "cycle", goal.cycle, "state", goal.state.name(), "operation", operation.name()));
    }
    /** 一份事件 JSON 包含全部允许的前后快照和文字，60KiB 是整份事件上限。 */
    public static String event(Command command, long actor, Goal before, Goal after, Instant now) {
        if (after.patientId != command.patientId || after.version != command.expectedVersion + 1
                || (before == null) != (command.operation == Operation.CREATE)
                || (before != null && (before.id != after.id || before.patientId != after.patientId || before.version != command.expectedVersion))
                || (command.goalId != null && command.goalId != after.id)) throw integrity();
        Map<String,Object> value = WorkflowData.map("patientId", command.patientId, "goalId", after.id, "sequence", after.version,
                "cycle", after.cycle, "operation", command.operation.name(), "actorId", id(actor), "recordedAt", instant(Objects.requireNonNull(now)),
                "before", before == null ? null : before.asMap(), "after", after.asMap(), "reason", command.reason, "note", command.note,
                "reportedDate", command.reportedDate, "feedbackSource", command.feedbackSource == null ? null : command.feedbackSource.name(), "result", command.result);
        String json = WorkflowData.json(value); requireBytes(json, EVENT_BYTES); return json;
    }
    public static Map<String,Object> storedEvent(String json) {
        Map<String,Object> value = parse(json, EVENT_BYTES);
        exact(value, keys("patientId", "goalId", "sequence", "cycle", "operation", "actorId", "recordedAt", "before", "after", "reason", "note", "reportedDate", "feedbackSource", "result"));
        Goal after = goal(object(value.get("after"))), before = value.get("before") == null ? null : goal(object(value.get("before")));
        Operation operation = choice(value.get("operation"), Operation.class);
        Map<String,Object> command = WorkflowData.map("commandKey", "00000000-0000-0000-0000-000000000000", "patientId", id(value.get("patientId")),
                "goalId", before == null ? null : before.id, "expectedVersion", before == null ? 0L : before.version, "operation", operation.name());
        if (operation == Operation.CREATE || operation == Operation.REVISE) command.put("content", after.content.asMap());
        for (String field : Arrays.asList("reason", "note", "reportedDate", "feedbackSource", "result")) if (value.get(field) != null) command.put(field, value.get(field));
        String canonical = event(command(WorkflowData.json(command)), id(value.get("actorId")), before, after, timestamp(value.get("recordedAt")));
        if (!WorkflowData.json(value).equals(canonical)) throw integrity();
        return immutable(value);
    }
    public static String commandKey(Object value) {
        if (!(value instanceof String) || !((String)value).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
        return UUID.fromString((String)value).toString();
    }
    public static long id(Object value) { long n = integer(value); if (n < 1 || n > MAX_ID) throw invalid(); return n; }
    public static Long nullableId(Object value) { return value == null ? null : id(value); }
    public static long version(Object value) { long n = integer(value); if (n < 0 || n >= MAX_ID) throw invalid(); return n; }
    public static long next(long value) { if (value < 0 || value >= MAX_ID) throw invalid(); return value + 1; }
    private static long integer(Object value) {
        try { if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return ((Number)value).longValue();
            if (value instanceof BigInteger) return ((BigInteger)value).longValueExact(); }
        catch (ArithmeticException failure) { throw invalid(); }
        throw invalid();
    }
    public static String text(Object value, int max) {
        if (!(value instanceof String)) throw invalid(); String s = (String)value;
        if (s.length() > max * 2 || s.codePointCount(0, s.length()) > max || s.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) throw invalid();
        requireBytes(s, max * 4); return s;
    }
    public static String date(Object value) {
        if (value == null) return null;
        if (!(value instanceof String) || !((String)value).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try { LocalDate date = LocalDate.parse((String)value); if (date.getYear() < 1) throw invalid(); return (String)value; }
        catch (java.time.DateTimeException failure) { throw invalid(); }
    }
    private static String instant(Instant value) { return value == null ? null : new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value); }
    private static Instant timestamp(Object value) {
        if (value == null) return null;
        if (!(value instanceof String)) throw integrity();
        try { return Instant.parse((String)value); } catch (java.time.DateTimeException failure) { throw integrity(); }
    }
    public static void requireBytes(String value, int max) {
        if (value == null || value.length() > max) throw limit();
        try { if (StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() > max) throw limit(); }
        catch (java.nio.charset.CharacterCodingException failure) { throw invalid(); }
    }
    public static Map<String,Object> parse(String input, int maximum) {
        requireBytes(input, maximum);
        try (JsonParser parser = JSON.getFactory().createParser(input)) {
            int depth = 0, tokens = 0; JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (++tokens > 1000) throw limit();
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) if (++depth > 6) throw limit();
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
                if (token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME) requireBytes(parser.getText(), maximum);
            }
            return object(JSON.readValue(input, Object.class));
        } catch (java.io.IOException failure) { throw invalid(); }
    }
    public static <T extends Enum<T>> T choice(Object value, Class<T> type) {
        if (!(value instanceof String)) throw invalid();
        try { return Enum.valueOf(type, (String)value); } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value) { if (!(value instanceof Map)) throw invalid(); return (Map<String,Object>)value; }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void exact(Map<String,Object> value, Set<String> keys) { if (value == null || !value.keySet().equals(keys)) throw invalid(); }
    private static Map<String,Object> immutable(Map<String,Object> value) { return Collections.unmodifiableMap(new TreeMap<>(value)); }
    public static WorkflowException invalid() { return new WorkflowException(400, "COMMON_GOAL_INVALID_REQUEST", "The common care goal request is invalid."); }
    public static WorkflowException integrity() { return new WorkflowException(409, "COMMON_GOAL_INTEGRITY", "The common care goal history is inconsistent. Refresh and request a review."); }
    public static WorkflowException limit() { return new WorkflowException(413, "COMMON_GOAL_LIMIT", "The common care goal exceeds the supported size limit."); }
    public static WorkflowException conflict() { return new WorkflowException(409, "COMMAND_CONFLICT", "This command key was already used for a different request."); }
}
