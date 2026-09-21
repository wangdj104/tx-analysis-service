package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * alertrecordentity
 */
@Data
@TableName("alert_record")
@ApiModel("alertrecord")
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("relatedruleID")
    private Long ruleId;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("owner userID")
    private Long userId;

    @ApiModelProperty("alerttype: INDICATOR-indicatorAbnormal, DIALYSIS-DialysisreferencecountAbnormal, BP-Blood PressureAbnormal")
    private String alertType;

    @ApiModelProperty("alertlevel: INFO-Notice, WARNING-warning, CRITICAL-Severe")
    private String alertLevel;

    @ApiModelProperty("alerttitle")
    private String alertTitle;

    @ApiModelProperty("triggervalue")
    private String triggeredValue;

    @ApiModelProperty("triggerTime")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime triggeredAt;

    private String sourceType;

    private Long sourceId;

    private String dedupeKey;

    private Integer occurrenceCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastTriggeredAt;

    @ApiModelProperty("Status: PENDING-pendingConfirm, CONFIRMED-already Confirm, RESOLVED-resolved")
    private String status;

    private Long acknowledgedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime acknowledgedAt;

    private Long resolvedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime resolvedAt;

    @ApiModelProperty("placesetrecord")
    private String handlingNote;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
