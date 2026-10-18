package org.familyhealthcare.service.medicalrecord;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;

import java.math.BigInteger;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** 有界的病历更正合同；通过校验不表示已获当前授权，也不代替锁内的版本和实际变化核验。 */
public final class MedicalRecordCorrectionContracts {
    public static final long MAX_ID = 9007199254740991L;
    public static final int COMMAND_BYTES = 64 * 1024;
    public static final int SNAPSHOT_BYTES = 256 * 1024;
    public static final int RECEIPT_BYTES = 512;
    public static final int MAX_ITEMS = 100;
    public static final int MAX_ATTACHMENTS = 100;
    public enum Operation { CORRECT, INVALIDATE }
    public enum Validity { ACTIVE, INVALIDATED }
    public enum ReferenceKind { CARE_PLAN_EVIDENCE, ALERT_EVIDENCE, LEGACY_ALERT }
    public enum HeaderField {
        RECORD_DATE("recordDate", 10), HOSPITAL_NAME("hospitalName", 100), DEPT_NAME("deptName", 100),
        DOCTOR_NAME("doctorName", 50), REMARK("remark", 500);
        private final String key; private final int maximum;
        HeaderField(String key, int maximum) { this.key = key; this.maximum = maximum; }
        public String key() { return key; }
    }
    public enum ItemField {
        ITEM_NAME("itemName", 200), ITEM_CODE("itemCode", 50), RESULT_VALUE("resultValue", 500),
        UNIT("unit", 50), REFERENCE_RANGE("referenceRange", 200);
        private final String key; private final int maximum;
        ItemField(String key, int maximum) { this.key = key; this.maximum = maximum; }
        public String key() { return key; }
    }
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_LONG_FOR_INTS);
    private MedicalRecordCorrectionContracts() { }

    public static final class HeaderPatch {
        private final Map<HeaderField,String> fields;
        private HeaderPatch(Map<HeaderField,String> fields) { this.fields = Collections.unmodifiableMap(new EnumMap<>(fields)); }
        public boolean contains(HeaderField field) { return fields.containsKey(field); }
        public String get(HeaderField field) { return fields.get(field); }
        public boolean isEmpty() { return fields.isEmpty(); }
        public Map<String,Object> asMap() {
            Map<String,Object> result = new TreeMap<>();
            fields.forEach((field, value) -> result.put(field.key, value));
            return Collections.unmodifiableMap(result);
        }
    }
    public static final class ItemPatch {
        private final long itemId; private final Map<ItemField,String> fields;
        private final boolean pairConfirmed, flagPresent; private final Integer reportedFlag;
        private ItemPatch(long itemId, Map<ItemField,String> fields, boolean pairConfirmed, boolean flagPresent, Integer flag) {
            this.itemId = itemId; this.fields = Collections.unmodifiableMap(new EnumMap<>(fields));
            this.pairConfirmed = pairConfirmed; this.flagPresent = flagPresent; this.reportedFlag = flag;
        }
        public long getItemId() { return itemId; }
        public boolean contains(ItemField field) { return fields.containsKey(field); }
        public String get(ItemField field) { return fields.get(field); }
        public boolean isNameCodePairConfirmed() { return pairConfirmed; }
        public boolean hasReportedFlag() { return flagPresent; }
        /** contextChanged 必须来自锁内逐字段精确比较，不能把字段出现当成实际变化。 */
        public Integer reportedFlagFor(boolean contextChanged, Integer previous) {
            return flagPresent ? reportedFlag : contextChanged ? null : previous;
        }
        public Map<String,Object> asMap() {
            Map<String,Object> values = new TreeMap<>(); fields.forEach((field, value) -> values.put(field.key, value));
            if (flagPresent) values.put("reportedFlag", immutable(WorkflowData.map("mode", reportedFlag == null ? "UNKNOWN" : "SOURCE_REPORTED", "value", reportedFlag)));
            Map<String,Object> result = WorkflowData.map("itemId", itemId, "fields", immutable(values));
            if (pairConfirmed) result.put("confirmNameCodePair", true);
            return immutable(result);
        }
    }
    public static final class Command {
        private final String commandKey, reason, canonical; private final long recordId, expectedContentVersion;
        private final Long patientId; private final Operation operation; private final HeaderPatch headerPatch;
        private final List<ItemPatch> itemPatches;
        private Command(String key, long record, Long patient, long version, Operation operation, String reason,
                        HeaderPatch header, List<ItemPatch> items, boolean headerPresent, boolean itemsPresent) {
            this.commandKey = key; this.recordId = record; this.patientId = patient; this.expectedContentVersion = version;
            this.operation = operation; this.reason = reason; this.headerPatch = header;
            this.itemPatches = Collections.unmodifiableList(new ArrayList<>(items));
            Map<String,Object> envelope = WorkflowData.map("commandKey", key, "recordId", record, "patientId", patient,
                    "expectedContentVersion", version, "operation", operation.name(), "reason", reason);
            if (headerPresent) envelope.put("headerPatch", header.asMap());
            if (itemsPresent) { List<Object> patches = new ArrayList<>(); items.forEach(item -> patches.add(item.asMap())); envelope.put("itemPatches", patches); }
            this.canonical = WorkflowData.json(envelope); requireBytes(canonical, COMMAND_BYTES);
        }
        public String getCommandKey() { return commandKey; }
        public long getRecordId() { return recordId; }
        public Long getPatientId() { return patientId; }
        public long getExpectedContentVersion() { return expectedContentVersion; }
        public Operation getOperation() { return operation; }
        public String getReason() { return reason; }
        public HeaderPatch getHeaderPatch() { return headerPatch; }
        public List<ItemPatch> getItemPatches() { return itemPatches; }
        public String canonicalEnvelope() { return canonical; }
        public String payloadHash() { return WorkflowData.hash(canonical); }
    }

    public static Command command(String input) {
        Map<String,Object> value = parseObject(input, COMMAND_BYTES);
        Set<String> required = keys("commandKey", "recordId", "patientId", "expectedContentVersion", "operation", "reason");
        fields(value, keys("commandKey", "recordId", "patientId", "expectedContentVersion", "operation", "reason", "headerPatch", "itemPatches"));
        if (!value.keySet().containsAll(required)) throw invalid();
        String key = commandKey(value.get("commandKey"));
        long record = id(value.get("recordId")), version = id(value.get("expectedContentVersion"));
        nextVersion(version);
        Long patient = nullableId(value.get("patientId"));
        Operation operation = choice(value.get("operation"), Operation.class);
        String reason = text(value.get("reason"), 1000, false);
        if (reason.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) throw invalid();
        boolean hasHeader = value.containsKey("headerPatch"), hasItems = value.containsKey("itemPatches");
        if (operation == Operation.INVALIDATE && (hasHeader || hasItems)) throw invalid();
        Map<HeaderField,String> headers = new EnumMap<>(HeaderField.class);
        if (hasHeader) {
            Map<String,Object> patch = object(value.get("headerPatch"));
            Set<String> allowed = new HashSet<>(); for (HeaderField field : HeaderField.values()) allowed.add(field.key);
            fields(patch, allowed);
            for (HeaderField field : HeaderField.values()) if (patch.containsKey(field.key))
                headers.put(field, field == HeaderField.RECORD_DATE ? date(patch.get(field.key)) : text(patch.get(field.key), field.maximum, true));
        }
        List<ItemPatch> items = new ArrayList<>();
        if (hasItems) {
            List<?> list = list(value.get("itemPatches"), MAX_ITEMS); Set<Long> ids = new HashSet<>();
            for (Object entry : list) {
                ItemPatch item = itemPatch(object(entry)); if (!ids.add(item.itemId)) throw invalid(); items.add(item);
            }
            items.sort(Comparator.comparingLong(ItemPatch::getItemId));
        }
        if (operation == Operation.CORRECT && headers.isEmpty() && items.isEmpty()) throw invalid();
        return new Command(key, record, patient, version, operation, reason, new HeaderPatch(headers), items, hasHeader, hasItems);
    }
    private static ItemPatch itemPatch(Map<String,Object> input) {
        fields(input, keys("itemId", "fields", "confirmNameCodePair"));
        if (!input.containsKey("itemId") || !input.containsKey("fields")) throw invalid();
        long item = id(input.get("itemId")); Map<String,Object> patch = object(input.get("fields"));
        Set<String> allowed = new HashSet<>(); for (ItemField field : ItemField.values()) allowed.add(field.key); allowed.add("reportedFlag");
        fields(patch, allowed); if (patch.isEmpty()) throw invalid();
        boolean confirmed = input.containsKey("confirmNameCodePair");
        if (confirmed && !Boolean.TRUE.equals(input.get("confirmNameCodePair"))) throw invalid();
        if ((patch.containsKey("itemName") || patch.containsKey("itemCode")) && !confirmed) throw invalid();
        Map<ItemField,String> values = new EnumMap<>(ItemField.class);
        for (ItemField field : ItemField.values()) if (patch.containsKey(field.key)) values.put(field, text(patch.get(field.key), field.maximum, true));
        Integer flag = null; boolean flagPresent = patch.containsKey("reportedFlag");
        if (flagPresent) {
            Map<String,Object> reported = object(patch.get("reportedFlag")); exact(reported, keys("mode", "value"));
            if ("UNKNOWN".equals(reported.get("mode"))) { if (reported.get("value") != null) throw invalid(); }
            else if ("SOURCE_REPORTED".equals(reported.get("mode"))) {
                long number = integer(reported.get("value")); if (number < -1 || number > 1) throw invalid(); flag = (int) number;
            } else throw invalid();
        }
        return new ItemPatch(item, values, confirmed, flagPresent, flag);
    }

    /** 最小回执白名单；拒绝多余字段而非悄悄缓存病历、理由或权限。 */
    public static Map<String,Object> receipt(Map<String,Object> input) {
        exact(input, keys("recordId", "contentVersion", "historySequence", "validity", "operation"));
        Map<String,Object> result = WorkflowData.map("recordId", id(input.get("recordId")), "contentVersion", id(input.get("contentVersion")),
                "historySequence", id(input.get("historySequence")), "validity", choice(input.get("validity"), Validity.class).name(),
                "operation", choice(input.get("operation"), Operation.class).name());
        requireBytes(WorkflowData.json(result), RECEIPT_BYTES); return immutable(result);
    }
    public static Map<String,Object> storedReceipt(String json) { return receipt(parseObject(json, RECEIPT_BYTES)); }

    /** 快照只记录当次实际采集值。capture actor/time 属于历史行，不冒充更早的作者或修改时间。 */
    public static final class Snapshot {
        private final Map<String,Object> value; private final String json, hash, contentDigest;
        private Snapshot(Map<String,Object> value) {
            requireScalarBudget(value); this.value = immutable(value); this.json = WorkflowData.json(value); requireBytes(json, SNAPSHOT_BYTES); this.hash = WorkflowData.hash(json);
            Map<String,Object> contentRecord = new TreeMap<>(object(value.get("record")));
            for (String field : Arrays.asList("contentVersion", "updatedAt", "verificationStatus", "verifiedBy", "verifiedAt", "verificationNote")) contentRecord.remove(field);
            // 独立核对可改变 review/updatedAt；不能因此把合法核对误判为未审计的内容改写。
            this.contentDigest = WorkflowData.hash(WorkflowData.json(WorkflowData.map("schemaVersion", 1L, "record", contentRecord,
                    "items", value.get("items"), "attachments", value.get("attachments"))));
        }
        public String json() { return json; }
        public String snapshotHash() { return hash; }
        public String contentDigest() { return contentDigest; }
        public Map<String,Object> asMap() { return value; }
    }
    public static Snapshot storedSnapshot(String json, String expectedHash) {
        requireBytes(json, SNAPSHOT_BYTES);
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}") || !WorkflowData.hash(json).equals(expectedHash)) throw integrity();
        Snapshot snapshot = snapshot(parseObject(json, SNAPSHOT_BYTES));
        if (!json.equals(snapshot.json)) throw integrity();
        return snapshot;
    }
    public static Snapshot snapshot(Map<String,Object> input) {
        exact(input, keys("schemaVersion", "record", "items", "attachments")); if (integer(input.get("schemaVersion")) != 1) throw invalid();
        Map<String,Object> record = snapshotRecord(object(input.get("record"))); long recordId = id(record.get("id"));
        List<Map<String,Object>> items = new ArrayList<>(), attachments = new ArrayList<>(); Set<Long> itemIds = new HashSet<>(), attachmentIds = new HashSet<>();
        for (Object value : list(input.get("items"), MAX_ITEMS)) {
            Map<String,Object> item = object(value); exact(item, keys("id", "recordId", "itemName", "itemCode", "resultValue", "unit", "referenceRange", "isAbnormal", "aiConfidence", "createdAt"));
            Map<String,Object> copy = new TreeMap<>(); long itemId = id(item.get("id")); if (!itemIds.add(itemId) || id(item.get("recordId")) != recordId) throw invalid();
            copy.put("id", itemId); copy.put("recordId", recordId);
            for (String field : Arrays.asList("itemName", "itemCode", "resultValue", "unit", "referenceRange", "aiConfidence", "createdAt")) copy.put(field, rawText(item.get(field)));
            Object flag = item.get("isAbnormal"); if (flag != null && (integer(flag) < -128 || integer(flag) > 127)) throw invalid();
            copy.put("isAbnormal", flag == null ? null : integer(flag)); items.add(immutable(copy));
        }
        for (Object value : list(input.get("attachments"), MAX_ATTACHMENTS)) {
            Map<String,Object> attachment = object(value); exact(attachment, keys("id", "recordId", "fileName", "fileType", "fileSize", "createdAt"));
            long attachmentId = id(attachment.get("id")); if (!attachmentIds.add(attachmentId) || id(attachment.get("recordId")) != recordId) throw invalid();
            Map<String,Object> copy = WorkflowData.map("id", attachmentId, "recordId", recordId);
            for (String field : Arrays.asList("fileName", "fileType", "createdAt")) copy.put(field, rawText(attachment.get(field)));
            Object size = attachment.get("fileSize"); if (size != null && (integer(size) < 0 || integer(size) > MAX_ID)) throw invalid();
            copy.put("fileSize", size == null ? null : integer(size)); attachments.add(immutable(copy));
        }
        items.sort(Comparator.comparingLong(item -> (Long)item.get("id"))); attachments.sort(Comparator.comparingLong(item -> (Long)item.get("id")));
        return new Snapshot(WorkflowData.map("schemaVersion", 1L, "record", record, "items", Collections.unmodifiableList(items), "attachments", Collections.unmodifiableList(attachments)));
    }
    private static Map<String,Object> snapshotRecord(Map<String,Object> record) {
        exact(record, keys("id", "patientId", "patientName", "userId", "recordDate", "recordType", "hospitalName", "deptName", "doctorName", "remark",
                "sourceType", "sourceExternalId", "confidenceScore", "createdAt", "updatedAt", "contentVersion", "verificationStatus", "verifiedBy", "verifiedAt", "verificationNote", "aiRawReference", "importFactsReference"));
        Map<String,Object> copy = new TreeMap<>();
        for (String field : record.keySet()) {
            Object value = record.get(field);
            switch (field) {
                case "id": case "contentVersion": value = id(value); break;
                case "patientId": case "userId": case "verifiedBy": value = nullableId(value); break;
                case "aiRawReference": case "importFactsReference": value = rawReference(value); break;
                case "recordDate": value = date(value); break;
                default: value = rawText(value);
            }
            copy.put(field, value);
        }
        if (copy.get("patientName") == null) throw invalid();
        return immutable(copy);
    }
    /** 大文本先在数据库读取 OCTET_LENGTH/SHA2；快照不接受 AI 原文、导入正文或附件字节。 */
    private static Object rawReference(Object value) {
        if (value == null) return null;
        Map<String,Object> reference = object(value); exact(reference, keys("byteLength", "sha256"));
        long length = integer(reference.get("byteLength")); Object hash = reference.get("sha256");
        if (length < 0 || length > MAX_ID || !(hash instanceof String) || !((String)hash).matches("[0-9a-f]{64}")) throw invalid();
        return immutable(WorkflowData.map("byteLength", length, "sha256", hash));
    }
    private static long requireScalarBudget(Object value) {
        long bytes = 0;
        if (value instanceof Map) {
            for (Map.Entry<?,?> field : ((Map<?,?>)value).entrySet()) {
                bytes += utf8((String)field.getKey()) + requireScalarBudget(field.getValue());
                if (bytes > SNAPSHOT_BYTES) throw limit();
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>)value) { bytes += requireScalarBudget(item); if (bytes > SNAPSHOT_BYTES) throw limit(); }
        } else if (value instanceof String) bytes = utf8((String)value);
        return bytes;
    }
    public static void requireReferenceItem(ReferenceKind kind, Long itemId) {
        if (kind == null || (kind == ReferenceKind.LEGACY_ALERT) != (itemId != null)) throw invalid();
        if (itemId != null) id(itemId); // item→record 的真实归属另由锁内 adapter 与 SQL 同时核验。
    }
    public static long id(Object value) { long number = integer(value); if (number < 1 || number > MAX_ID) throw invalid(); return number; }
    public static long nextVersion(long version) { if (version < 1 || version >= MAX_ID) throw invalid(); return version + 1; }
    private static Long nullableId(Object value) { return value == null ? null : id(value); }
    private static long integer(Object value) {
        try {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return ((Number)value).longValue();
            if (value instanceof BigInteger) return ((BigInteger)value).longValueExact();
        } catch (ArithmeticException failure) { throw invalid(); }
        throw invalid();
    }
    public static long utf8(String value) {
        if (value == null) throw invalid();
        try { return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining(); }
        catch (CharacterCodingException failure) { throw invalid(); }
    }
    public static void requireBytes(String value, int maximum) {
        if (value == null) throw invalid();
        // UTF-16 长度先限界，避免先为任意大输入分配编码缓冲。
        if (value.length() > maximum || utf8(value) > maximum) throw limit();
    }
    private static Map<String,Object> parseObject(String input, int maximum) {
        requireBytes(input, maximum);
        try (JsonParser parser = JSON.getFactory().createParser(input)) {
            int depth = 0, tokens = 0; JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (++tokens > 20000) throw limit();
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) { if (++depth > 8) throw limit(); }
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
                if (token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME) utf8(parser.getText());
            }
            return object(JSON.readValue(input, Object.class));
        } catch (java.io.IOException failure) { throw invalid(); }
    }
    public static String commandKey(Object value) {
        if (!(value instanceof String) || !((String)value).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
        return UUID.fromString((String)value).toString();
    }
    private static String text(Object value, int maximum, boolean nullable) {
        if (value == null && nullable) return null;
        if (!(value instanceof String)) throw invalid(); String text = (String)value;
        if (text.length() > maximum * 2 || text.codePointCount(0, text.length()) > maximum) throw invalid(); utf8(text); return text;
    }
    private static String rawText(Object value) { if (value == null) return null; if (!(value instanceof String)) throw invalid(); requireBytes((String)value, SNAPSHOT_BYTES); return (String)value; }
    private static String date(Object value) {
        if (value == null) return null;
        if (!(value instanceof String) || !((String)value).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try { LocalDate parsed = LocalDate.parse((String)value); if (parsed.getYear() < 1000) throw invalid(); return (String)value; }
        catch (java.time.DateTimeException failure) { throw invalid(); }
    }
    private static <T extends Enum<T>> T choice(Object value, Class<T> type) {
        if (!(value instanceof String)) throw invalid(); try { return Enum.valueOf(type, (String)value); } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { if (!(value instanceof Map)) throw invalid(); return (Map<String,Object>)value; }
    private static List<?> list(Object value, int maximum) { if (!(value instanceof List)) throw invalid(); if (((List<?>)value).size() > maximum) throw limit(); return (List<?>)value; }
    private static void fields(Map<String,Object> value, Set<String> keys) { if (value == null || !keys.containsAll(value.keySet())) throw invalid(); }
    private static void exact(Map<String,Object> value, Set<String> keys) { fields(value, keys); if (!value.keySet().equals(keys)) throw invalid(); }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static Map<String,Object> immutable(Map<String,Object> value) { return Collections.unmodifiableMap(new TreeMap<>(value)); }
    public static WorkflowException commandConflict() { return new WorkflowException(409, "COMMAND_CONFLICT", "This command key was already used for a different request."); }
    public static WorkflowException invalid() { return new WorkflowException(400, "MEDICAL_RECORD_INVALID_REQUEST", "The medical record correction request is invalid."); }
    public static WorkflowException integrity() { return new WorkflowException(409, "MEDICAL_RECORD_INTEGRITY", "The medical record history is inconsistent. Reload and request a review."); }
    public static WorkflowException limit() { return new WorkflowException(413, "MEDICAL_RECORD_LIMIT", "The medical record exceeds the supported size limit."); }
}
