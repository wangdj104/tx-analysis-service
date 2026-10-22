package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.familyhealthcare.service.workflow.WorkflowException;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ephemeral native-content values, never response DTOs or persisted review facts.
 * Native timestamps remain wall-clock literals; stored TEXT references describe
 * database text bytes, not decoded attachments or files behind their paths.
 */
final class MedicalReviewContentFingerprint {
    static final int MAX_CHILDREN = 100;
    static final int MAX_BYTES = 1048576;
    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS", java.util.Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private MedicalReviewContentFingerprint() { }

    static final class TextReference {
        final Long byteLength;
        final String sha256;
        TextReference(Long byteLength, String sha256) { this.byteLength = byteLength; this.sha256 = sha256; }
    }

    static final class NativeHeader {
        final Long id, patientId, userId, contentVersion, verifiedBy;
        final String patientName, recordDate, recordType, hospitalName, deptName, doctorName, remark;
        final String sourceType, sourceExternalId, confidenceScore, createdAt, verificationStatus, verifiedAt;
        final TextReference aiRawReference, importFactsReference, verificationNoteReference;
        NativeHeader(Long id, Long patientId, Long userId, String patientName, String recordDate,
                     String recordType, String hospitalName, String deptName, String doctorName, String remark,
                     String sourceType, String sourceExternalId, String confidenceScore, String createdAt,
                     Long contentVersion, TextReference aiRawReference, TextReference importFactsReference,
                     String verificationStatus, Long verifiedBy, String verifiedAt, TextReference verificationNoteReference) {
            this.id = id; this.patientId = patientId; this.userId = userId; this.patientName = patientName;
            this.recordDate = recordDate; this.recordType = recordType; this.hospitalName = hospitalName;
            this.deptName = deptName; this.doctorName = doctorName; this.remark = remark; this.sourceType = sourceType;
            this.sourceExternalId = sourceExternalId; this.confidenceScore = confidenceScore; this.createdAt = createdAt;
            this.contentVersion = contentVersion; this.aiRawReference = aiRawReference; this.importFactsReference = importFactsReference;
            this.verificationStatus = verificationStatus; this.verifiedBy = verifiedBy; this.verifiedAt = verifiedAt;
            this.verificationNoteReference = verificationNoteReference;
        }
    }

    static final class NativeItem {
        final Long id, recordId, isAbnormal;
        final String itemName, itemCode, resultValue, unit, referenceRange, aiConfidence, createdAt;
        NativeItem(Long id, Long recordId, String itemName, String itemCode, String resultValue, String unit,
                   String referenceRange, Long isAbnormal, String aiConfidence, String createdAt) {
            this.id = id; this.recordId = recordId; this.itemName = itemName; this.itemCode = itemCode;
            this.resultValue = resultValue; this.unit = unit; this.referenceRange = referenceRange;
            this.isAbnormal = isAbnormal; this.aiConfidence = aiConfidence; this.createdAt = createdAt;
        }
    }

    static final class NativeAttachment {
        final Long id, recordId, fileSize;
        final String filePath, fileName, fileType, createdAt;
        final TextReference fileContentReference;
        NativeAttachment(Long id, Long recordId, String filePath, String fileName, String fileType,
                         Long fileSize, String createdAt, TextReference fileContentReference) {
            this.id = id; this.recordId = recordId; this.filePath = filePath; this.fileName = fileName;
            this.fileType = fileType; this.fileSize = fileSize; this.createdAt = createdAt;
            this.fileContentReference = fileContentReference;
        }
    }

    /**
     * Content material only: this value proves neither READY storage nor current
     * authority or review qualification. A later caller must pair it with the
     * corresponding lifecycle/READY read in the same transaction and for the same
     * source binding, plus the required current-authority checks. An aggregate
     * alone is never an authorized or actionable preview.
     */
    static final class Content {
        private final NativeHeader header;
        private final String canonical, fingerprint;
        private Content(NativeHeader header, String canonical) {
            this.header = header; this.canonical = canonical; this.fingerprint = WorkflowData.hash(canonical);
        }
        long recordId() { return header.id; }
        Long patientId() { return header.patientId; }
        Long userId() { return header.userId; }
        long contentVersion() { return header.contentVersion; }
        String canonicalEnvelope() { return canonical; }
        String fingerprint() { return fingerprint; }
        String initialLegacyCanonicalEnvelope() {
            return WorkflowData.json(WorkflowData.map("contractVersion", 1L, "contentFingerprint", fingerprint,
                    "verificationStatus", header.verificationStatus, "verifiedBy", header.verifiedBy,
                    "verifiedAt", header.verifiedAt, "verificationNoteReference", reference(header.verificationNoteReference, 65535)));
        }
        String initialLegacyFingerprint() { return WorkflowData.hash(initialLegacyCanonicalEnvelope()); }
    }

    static Content assemble(NativeHeader header, List<NativeItem> items, List<NativeAttachment> attachments) {
        if (header == null || items == null || attachments == null) throw integrity();
        if (items.size() > MAX_CHILDREN || attachments.size() > MAX_CHILDREN) throw limit();
        id(header.id); nullableId(header.patientId); nullableId(header.userId); id(header.contentVersion);
        text(header.patientName, 50, true); date(header.recordDate); text(header.recordType, 30, false);
        text(header.hospitalName, 100, false); text(header.deptName, 100, false); text(header.doctorName, 50, false);
        text(header.remark, 500, false); text(header.sourceType, 20, false); text(header.sourceExternalId, 120, false);
        decimal(header.confidenceScore); time(header.createdAt); text(header.verificationStatus, 30, false);
        nullableId(header.verifiedBy); time(header.verifiedAt); reference(header.verificationNoteReference, 65535);
        Map<String,Object> record = WorkflowData.map("id", header.id, "patientId", header.patientId, "userId", header.userId,
                "patientName", header.patientName, "recordDate", header.recordDate, "recordType", header.recordType,
                "hospitalName", header.hospitalName, "deptName", header.deptName, "doctorName", header.doctorName,
                "remark", header.remark, "sourceType", header.sourceType, "sourceExternalId", header.sourceExternalId,
                "confidenceScore", header.confidenceScore, "createdAt", header.createdAt, "contentVersion", header.contentVersion,
                "aiRawReference", reference(header.aiRawReference, 65535), "importFactsReference", reference(header.importFactsReference, 65535));
        List<Map<String,Object>> itemValues = new ArrayList<>(items.size());
        long last = 0;
        for (NativeItem item : items) {
            if (item == null) throw integrity();
            long current = id(item.id); id(item.recordId);
            if (current <= last || !header.id.equals(item.recordId)) throw integrity();
            last = current;
            text(item.itemName, 200, false); text(item.itemCode, 50, false); text(item.resultValue, 500, false);
            text(item.unit, 50, false); text(item.referenceRange, 200, false); decimal(item.aiConfidence); time(item.createdAt);
            if (item.isAbnormal != null && (item.isAbnormal < -128 || item.isAbnormal > 127)) throw integrity();
            itemValues.add(WorkflowData.map("id", item.id, "recordId", item.recordId, "itemName", item.itemName,
                    "itemCode", item.itemCode, "resultValue", item.resultValue, "unit", item.unit,
                    "referenceRange", item.referenceRange, "isAbnormal", item.isAbnormal,
                    "aiConfidence", item.aiConfidence, "createdAt", item.createdAt));
        }
        List<Map<String,Object>> attachmentValues = new ArrayList<>(attachments.size());
        last = 0;
        for (NativeAttachment attachment : attachments) {
            if (attachment == null) throw integrity();
            long current = id(attachment.id); id(attachment.recordId);
            if (current <= last || !header.id.equals(attachment.recordId)) throw integrity();
            last = current;
            text(attachment.filePath, 500, false); text(attachment.fileName, 200, false);
            text(attachment.fileType, 20, false); time(attachment.createdAt);
            // The declared size is a signed native BIGINT, not an identifier or measured text length.
            attachmentValues.add(WorkflowData.map("id", attachment.id, "recordId", attachment.recordId,
                    "filePath", attachment.filePath, "fileName", attachment.fileName, "fileType", attachment.fileType,
                    "fileSize", attachment.fileSize, "createdAt", attachment.createdAt,
                    "fileContentReference", reference(attachment.fileContentReference, 16777215)));
        }
        String canonical = WorkflowData.json(WorkflowData.map("contractVersion", 1L, "record", record,
                "items", itemValues, "attachments", attachmentValues));
        if (utf8(canonical) > MAX_BYTES) throw limit(); // Check complete bytes before any aggregate hash.
        return new Content(header, canonical);
    }

    static long id(Long value) {
        if (value == null || value <= 0 || value > MedicalReviewContracts.MAX_ID) throw integrity();
        return value;
    }
    static void nullableId(Long value) { if (value != null) id(value); }
    private static void text(String value, int points, boolean required) {
        if (value == null) { if (required) throw integrity(); return; }
        if (value.length() > points * 2 || value.codePointCount(0, value.length()) > points) throw integrity();
        utf8(value);
    }
    private static int utf8(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining();
        } catch (CharacterCodingException failure) { throw integrity(); }
    }
    private static void decimal(String value) {
        if (value != null && !value.matches("-?[0-9]\\.[0-9]{4}")) throw integrity();
    }
    static void date(String value) {
        if (value == null) return;
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw integrity();
        try { if (LocalDate.parse(value).getYear() == 0) throw integrity(); }
        catch (java.time.DateTimeException failure) { throw integrity(); }
    }
    static void time(String value) {
        if (value == null) return;
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}")) throw integrity();
        try { if (LocalDateTime.parse(value, WALL_CLOCK).getYear() == 0) throw integrity(); }
        catch (java.time.DateTimeException failure) { throw integrity(); }
    }
    private static Map<String,Object> reference(TextReference value, long maximum) {
        if (value == null) return null;
        if (value.byteLength == null || value.byteLength < 0 || value.byteLength > maximum || value.sha256 == null
                || !value.sha256.matches("[0-9a-f]{64}") || (value.byteLength == 0 && !EMPTY_SHA.equals(value.sha256))) throw integrity();
        return WorkflowData.map("byteLength", value.byteLength, "sha256", value.sha256);
    }
    static WorkflowException integrity() {
        return new WorkflowException(409, "MEDICAL_REVIEW_INTEGRITY", "The medical review source is inconsistent. Reload and request a review.");
    }
    static WorkflowException limit() {
        return new WorkflowException(413, "MEDICAL_REVIEW_LIMIT", "The medical review source exceeds the supported size limit.");
    }
}
