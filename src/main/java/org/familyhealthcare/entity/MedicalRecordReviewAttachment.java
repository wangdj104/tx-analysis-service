package org.familyhealthcare.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Review preview exposes metadata only, with no bytes or storage path in serialization. */
@JsonIgnoreProperties({"fileContent", "filePath"})
public final class MedicalRecordReviewAttachment extends MedicalRecordAttachment {
    public MedicalRecordReviewAttachment(MedicalRecordAttachment source) {
        setId(source.getId()); setRecordId(source.getRecordId());
        setFileName(source.getFileName()); setFileType(source.getFileType());
        setFileSize(source.getFileSize()); setCreatedAt(source.getCreatedAt());
    }
}
