package org.familyhealthcare.service.medicalreview;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Storage-free review contracts. Validation is not authority, a current-source read,
 * a transition, or a receipt lookup. No native content fingerprint is assembled here.
 */
public final class MedicalReviewContracts {
    public static final long MAX_ID = 9007199254740991L;
    public static final int COMMAND_BYTES = 16 * 1024;
    public static final int TEXT_CODE_POINTS = 1000;
    public static final int TEXT_BYTES = 4000;

    public enum Channel { SOURCE, DOCTOR, RECORD }
    public enum Operation { REQUEST, APPROVE, REJECT, RESUBMIT, CONTENT_CHANGED, SOURCE_DELETED }
    public enum State { PENDING, APPROVED, REJECTED, SUPERSEDED, DELETED }
    public enum Replay { NOT_SAME_KEY, IDENTICAL, CONFLICT }

    private static final JsonFactory JSON = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> COMMAND_FIELDS = new HashSet<>(Arrays.asList(
            "operation", "commandKey", "expectedContentVersion", "expectedDecisionVersion",
            "contentFingerprint", "initialLegacyFingerprint", "lifecycleFingerprint", "acknowledged", "text"));

    private MedicalReviewContracts() { }

    /** Immutable, fully validated input. The envelope/hash are ephemeral and must not be logged or retained as facts. */
    public static final class Command {
        private final long recordId, actorId, expectedContentVersion, expectedDecisionVersion;
        private final Channel channel;
        private final Operation operation;
        private final String commandKey, contentFingerprint, initialLegacyFingerprint, lifecycleFingerprint, text, canonical;

        private Command(long recordId, long actorId, Channel channel, Operation operation, String commandKey,
                        long contentVersion, long decisionVersion, String contentFingerprint,
                        String initialLegacyFingerprint, String lifecycleFingerprint, String text) {
            this.recordId = recordId; this.actorId = actorId; this.channel = channel; this.operation = operation;
            this.commandKey = commandKey; this.expectedContentVersion = contentVersion; this.expectedDecisionVersion = decisionVersion;
            this.contentFingerprint = contentFingerprint; this.initialLegacyFingerprint = initialLegacyFingerprint;
            this.lifecycleFingerprint = lifecycleFingerprint; this.text = text;
            // WorkflowData sorts keys, keeps JSON scalar types and explicit nulls, and hashes exact UTF-8.
            this.canonical = WorkflowData.json(WorkflowData.map("contractVersion", 1L, "actorId", actorId,
                    "recordId", recordId, "channel", channel.name(), "operation", operation.name(), "commandKey", commandKey,
                    "expectedContentVersion", contentVersion, "expectedDecisionVersion", decisionVersion,
                    "contentFingerprint", contentFingerprint, "initialLegacyFingerprint", initialLegacyFingerprint,
                    "lifecycleFingerprint", lifecycleFingerprint, "acknowledged", true, "text", text));
        }
        public long getRecordId() { return recordId; }
        public long getActorId() { return actorId; }
        public Channel getChannel() { return channel; }
        public Operation getOperation() { return operation; }
        public String getCommandKey() { return commandKey; }
        public long getExpectedContentVersion() { return expectedContentVersion; }
        public long getExpectedDecisionVersion() { return expectedDecisionVersion; }
        public String getContentFingerprint() { return contentFingerprint; }
        public String getInitialLegacyFingerprint() { return initialLegacyFingerprint; }
        public String getLifecycleFingerprint() { return lifecycleFingerprint; }
        public String getText() { return text; }
        public String canonicalEnvelope() { return canonical; }
        public String payloadHash() { return WorkflowData.hash(canonical); }
    }

    /** recordId/actorId/channel come from the server, never from request JSON. */
    public static Command command(String input, long recordId, long actorId, Channel channel) {
        id(recordId); id(actorId); requireReviewChannel(channel);
        Map<String,Object> fields = parseCommand(input);
        Operation operation = operation(fields.get("operation"));
        long contentVersion = id(fields.get("expectedContentVersion"));
        long decisionVersion = nonnegative(fields.get("expectedDecisionVersion"));
        requireCommandOperation(channel, operation, decisionVersion);
        String key = uuid(fields.get("commandKey"));
        String content = fingerprint(fields.get("contentFingerprint"));
        String lifecycle = fingerprint(fields.get("lifecycleFingerprint"));
        Object initial = fields.get("initialLegacyFingerprint");
        String legacy = null;
        if (channel == Channel.SOURCE && decisionVersion == 0) legacy = fingerprint(initial);
        else if (initial != null) throw invalid();
        if (!Boolean.TRUE.equals(fields.get("acknowledged"))) throw invalid();
        String text = text(fields.get("text"), operation);
        // Do not require a spare version here: an exact completed receipt may be recovered at MAX_ID.
        return new Command(recordId, actorId, channel, operation, key, contentVersion, decisionVersion,
                content, legacy, lifecycle, text);
    }

    /** Use at byte-oriented boundaries so malformed UTF-8 cannot become replacement characters before validation. */
    public static Command command(byte[] input, long recordId, long actorId, Channel channel) {
        if (input == null) throw invalid();
        if (input.length > COMMAND_BYTES) throw limit();
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
            return command(decoded, recordId, actorId, channel);
        } catch (CharacterCodingException failure) { throw invalid(); }
    }

    private static Map<String,Object> parseCommand(String input) {
        if (input == null) throw invalid();
        if (input.length() > COMMAND_BYTES || utf8(input) > COMMAND_BYTES) throw limit();
        Map<String,Object> fields = new HashMap<>();
        try (JsonParser parser = JSON.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.FIELD_NAME) throw invalid();
                String key = parser.getCurrentName();
                if (!COMMAND_FIELDS.contains(key) || fields.containsKey(key)) throw invalid();
                token = parser.nextToken();
                Object value;
                if (token == JsonToken.VALUE_STRING) { value = parser.getText(); utf8((String)value); }
                else if (token == JsonToken.VALUE_NUMBER_INT) value = parser.getBigIntegerValue();
                else if (token == JsonToken.VALUE_TRUE) value = Boolean.TRUE;
                else if (token == JsonToken.VALUE_FALSE) value = Boolean.FALSE;
                else if (token == JsonToken.VALUE_NULL) value = null;
                else throw invalid(); // Nested objects/arrays and fractional/coerced numbers are never commands.
                fields.put(key, value);
            }
            if (parser.nextToken() != null || !fields.keySet().equals(COMMAND_FIELDS)) throw invalid();
            return fields;
        } catch (IOException failure) { throw invalid(); } // Never echo a parser's request fragment.
    }

    private static void requireCommandOperation(Channel channel, Operation operation, long decisionVersion) {
        if (decisionVersion == 0) {
            if (channel == Channel.SOURCE && (operation == Operation.APPROVE || operation == Operation.REJECT)) return;
            if (channel == Channel.DOCTOR && operation == Operation.REQUEST) return;
        } else if (operation == Operation.APPROVE || operation == Operation.REJECT || operation == Operation.RESUBMIT) return;
        throw invalid();
    }

    private static String text(Object value, Operation operation) {
        if (operation == Operation.REQUEST) { if (value != null) throw invalid(); return null; }
        if (value == null && operation == Operation.APPROVE) return null;
        if (!(value instanceof String)) throw invalid();
        String text = (String)value;
        if (text.length() > TEXT_CODE_POINTS * 2 || text.codePointCount(0, text.length()) > TEXT_CODE_POINTS
                || utf8(text) > TEXT_BYTES) throw invalid();
        if (operation != Operation.APPROVE && text.codePoints().allMatch(MedicalReviewContracts::blankCodePoint)) throw invalid();
        return text; // Preserve original valid scalar text, including leading/trailing whitespace.
    }

    // Fixed JDK17 whitespace/space-character union plus NEL/BOM; no JDK8 U+180E drift.
    private static boolean blankCodePoint(int c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x85 || c == 0xA0
                || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029
                || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    /** Server-observed identity only. Generation creation and bounded watermark reads belong to the locked service. */
    public static final class Lifecycle {
        private final long recordId, latestDeletionEventId;
        private final String currentGeneration, canonical;
        private Lifecycle(long recordId, String generation, long deletionEventId) {
            this.recordId = recordId; this.currentGeneration = generation; this.latestDeletionEventId = deletionEventId;
            this.canonical = WorkflowData.json(WorkflowData.map("recordId", recordId,
                    "currentGeneration", generation, "latestDeletionEventId", deletionEventId));
        }
        public long getRecordId() { return recordId; }
        public String getCurrentGeneration() { return currentGeneration; }
        public long getLatestDeletionEventId() { return latestDeletionEventId; }
        public String canonicalEnvelope() { return canonical; }
        public String fingerprint() { return WorkflowData.hash(canonical); }
    }

    /** Null means that channel has no current state, not that retained historical generations are absent. */
    public static Lifecycle lifecycle(long recordId, String sourceGeneration, String doctorGeneration, long latestDeletionEventId) {
        id(recordId); nonnegative(latestDeletionEventId);
        if (sourceGeneration != null) uuid(sourceGeneration);
        if (doctorGeneration != null) uuid(doctorGeneration);
        if (sourceGeneration != null && doctorGeneration != null && !sourceGeneration.equals(doctorGeneration)) throw integrity();
        return new Lifecycle(recordId, sourceGeneration == null ? doctorGeneration : sourceGeneration, latestDeletionEventId);
    }

    /** For a new application only, after current read authority and completed-receipt lookup in the real service. */
    public static void requireLifecycle(Command command, Lifecycle current) {
        if (command == null || current == null) throw invalid();
        if (command.recordId != current.recordId || !command.lifecycleFingerprint.equals(current.fingerprint()))
            throw new WorkflowException(409, "MEDICAL_REVIEW_LIFECYCLE_CONFLICT", "The medical record lifecycle changed. Reload before submitting a new command.");
    }

    /** Pure composite-key/hash classification; does not read a receipt, admit an action, or decide current authority. */
    public static Replay classifyReplay(Command command, long storedActorId, long storedRecordId, Channel storedChannel,
                                        String storedCommandKey, String storedRequestHash) {
        if (command == null) throw invalid();
        id(storedActorId); id(storedRecordId); requireReviewChannel(storedChannel);
        uuid(storedCommandKey); fingerprint(storedRequestHash);
        if (command.actorId != storedActorId || command.recordId != storedRecordId || command.channel != storedChannel
                || !command.commandKey.equals(storedCommandKey)) return Replay.NOT_SAME_KEY;
        return command.payloadHash().equals(storedRequestHash) ? Replay.IDENTICAL : Replay.CONFLICT;
    }

    /** Shape checks only; previous-state transitions and event links must be checked separately under locks. */
    public static void requireStateFacts(String sourceGeneration, Channel channel, State state,
                                         long contentVersion, long decisionVersion, long cycle) {
        uuid(sourceGeneration); requireReviewChannel(channel);
        if (state == null || state == State.DELETED) throw invalid();
        id(contentVersion); id(decisionVersion); id(cycle);
    }

    /** RECORD/SOURCE_DELETED is the only zero decision-version/cycle fact and is never a review state/command. */
    public static void requireEventFacts(String sourceGeneration, Channel channel, Operation operation, State state,
                                         Long beforeContentVersion, long contentVersion, long decisionVersion, long cycle) {
        uuid(sourceGeneration); id(contentVersion);
        if (operation == null || state == null) throw invalid();
        if (beforeContentVersion != null) id(beforeContentVersion);
        if (operation == Operation.SOURCE_DELETED) {
            if (channel != Channel.RECORD || state != State.DELETED || decisionVersion != 0 || cycle != 0
                    || beforeContentVersion == null || beforeContentVersion.longValue() != contentVersion) throw invalid();
            return;
        }
        requireStateFacts(sourceGeneration, channel, state, contentVersion, decisionVersion, cycle);
        State expected = operation == Operation.APPROVE ? State.APPROVED : operation == Operation.REJECT ? State.REJECTED
                : operation == Operation.CONTENT_CHANGED ? State.SUPERSEDED : State.PENDING;
        if (state != expected) throw invalid();
        if (beforeContentVersion == null) {
            if (decisionVersion != 1 || cycle != 1) throw invalid();
            if (channel == Channel.SOURCE && (operation == Operation.APPROVE || operation == Operation.REJECT)) return;
            if (channel == Channel.DOCTOR && operation == Operation.REQUEST) return;
            throw invalid();
        }
        if (operation == Operation.REQUEST || decisionVersion == 1) throw invalid();
        if (operation == Operation.CONTENT_CHANGED) {
            if (nextVersion(beforeContentVersion) != contentVersion) throw invalid();
        } else if (operation == Operation.RESUBMIT) {
            if (cycle < 2 || beforeContentVersion.longValue() != contentVersion) throw invalid();
        } else if (beforeContentVersion.longValue() != contentVersion) throw invalid();
    }

    public static long id(Object value) { long number = nonnegative(value); if (number == 0) throw invalid(); return number; }
    public static long nextVersion(long version) { id(version); if (version == MAX_ID) throw invalid(); return version + 1; }
    private static long nonnegative(Object value) {
        long number;
        try {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                number = ((Number)value).longValue();
            else if (value instanceof BigInteger) number = ((BigInteger)value).longValueExact();
            else throw invalid();
        } catch (ArithmeticException failure) { throw invalid(); }
        if (number < 0 || number > MAX_ID) throw invalid();
        return number;
    }
    private static void requireReviewChannel(Channel channel) {
        if (channel != Channel.SOURCE && channel != Channel.DOCTOR) throw invalid();
    }
    private static Operation operation(Object value) {
        if (!(value instanceof String)) throw invalid();
        try { return Operation.valueOf((String)value); }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static String uuid(Object value) {
        if (!(value instanceof String) || !((String)value).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        return (String)value;
    }
    private static String fingerprint(Object value) {
        if (!(value instanceof String) || !((String)value).matches("[0-9a-f]{64}")) throw invalid();
        return (String)value;
    }
    private static int utf8(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining();
        } catch (CharacterCodingException failure) { throw invalid(); }
    }
    private static WorkflowException invalid() {
        return new WorkflowException(400, "MEDICAL_REVIEW_INVALID_REQUEST", "The medical review request is invalid.");
    }
    private static WorkflowException limit() {
        return new WorkflowException(413, "MEDICAL_REVIEW_LIMIT", "The medical review request exceeds the supported size limit.");
    }
    private static WorkflowException integrity() {
        return new WorkflowException(409, "MEDICAL_REVIEW_INTEGRITY", "The medical review lifecycle is inconsistent. Reload and request a review.");
    }
}
