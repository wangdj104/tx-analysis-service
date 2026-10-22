package org.familyhealthcare.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordAttachment;
import org.familyhealthcare.entity.MedicalRecordItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.familyhealthcare.util.ExportLocalization.text;

/** 独立的详情白名单；原始导入事实不能通过通用实体进入列表、修改或模型请求。 */
@Getter
public final class MedicalRecordImportDetail {
    private final Long id;
    private final String patientName;
    private final Long patientId;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate recordDate;
    private final String recordType;
    private final String hospitalName;
    private final String deptName;
    private final String doctorName;
    private final String aiRawResult;
    private final String sourceType;
    private final String sourceExternalId;
    private final String verificationStatus;
    private final Long contentVersion;
    // 这里只转发当前授权读取已算好的状态，不补历史、理由或新的关联内容。
    private final Boolean managed;
    private final String validity;
    private final String sourceStatus;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JSONField(deserialize = false)
    private final MedicalRecordReviewSummary reviewSummary;
    private final BigDecimal confidenceScore;
    private final Long verifiedBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private final LocalDateTime verifiedAt;
    private final String verificationNote;
    private final String remark;
    private final Long userId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private final LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private final LocalDateTime updatedAt;
    private final List<MedicalRecordItem> items;
    private final List<MedicalRecordAttachment> attachments;
    private final Map<String,Object> originalImport;

    public MedicalRecordImportDetail(MedicalRecord record, Map<String,Object> originalImport) {
        this.id = record.getId();
        this.patientName = record.getPatientName();
        this.patientId = record.getPatientId();
        this.recordDate = record.getRecordDate();
        this.recordType = record.getRecordType();
        this.hospitalName = record.getHospitalName();
        this.deptName = record.getDeptName();
        this.doctorName = record.getDoctorName();
        this.aiRawResult = record.getAiRawResult();
        this.sourceType = record.getSourceType();
        this.sourceExternalId = record.getSourceExternalId();
        this.verificationStatus = record.getVerificationStatus();
        this.contentVersion = record.getContentVersion();
        this.managed = record.getManaged();
        this.validity = record.getValidity();
        this.sourceStatus = record.getSourceStatus();
        this.reviewSummary = record.getReviewSummary();
        this.confidenceScore = record.getConfidenceScore();
        this.verifiedBy = record.getVerifiedBy();
        this.verifiedAt = record.getVerifiedAt();
        this.verificationNote = record.getVerificationNote();
        this.remark = record.getRemark();
        this.userId = record.getUserId();
        this.createdAt = record.getCreatedAt();
        this.updatedAt = record.getUpdatedAt();
        this.items = record.getItems();
        this.attachments = record.getAttachments();
        this.originalImport = originalImport;
    }

    /** 仅新详情分支使用固定错误，不能携带数据库原因或来源值。 */
    public static final class ReadFailure extends RuntimeException {
        private final int status;
        private final String errorCode;
        private ReadFailure(int status, String code, String en, String cn) {
            super(text(en, cn), null, false, false); this.status = status; this.errorCode = code;
        }
        public int getStatus() { return status; }
        public String getErrorCode() { return errorCode; }
        public static ReadFailure selector() { return new ReadFailure(400, "INVALID_IMPORT_FACTS_SELECTOR", "The original-facts query is invalid.", "原始事实查询参数无效。"); }
        public static ReadFailure authentication() { return new ReadFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to view source records.", "请登录后查看来源记录。"); }
        public static ReadFailure denied() { return new ReadFailure(403, "ACCESS_DENIED", "Access is unavailable. Refresh your session and patient selection.", "暂时无法访问，请刷新会话并重新选择患者。"); }
    }
}
