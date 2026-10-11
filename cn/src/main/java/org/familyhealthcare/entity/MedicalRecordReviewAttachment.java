package org.familyhealthcare.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 审核预览只暴露元数据，序列化时不输出文件字节或存储路径。 */
@JsonIgnoreProperties({"fileContent", "filePath"})
public final class MedicalRecordReviewAttachment extends MedicalRecordAttachment {
    public MedicalRecordReviewAttachment(MedicalRecordAttachment source) {
        setId(source.getId()); setRecordId(source.getRecordId());
        setFileName(source.getFileName()); setFileType(source.getFileType());
        setFileSize(source.getFileSize()); setCreatedAt(source.getCreatedAt());
    }
}
