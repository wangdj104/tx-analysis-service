package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * medicationrecordentity
 */
@Data
@TableName("medication_log")
@ApiModel("medicationrecord")
public class MedicationLog {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("PatientName")
    private String patientName;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("MedicationID")
    private Long medicationId;

    @ApiModelProperty("relatedDialysis RecordsID")
    private Long dialysisRecordId;

    @ApiModelProperty("this timesDose")
    private String dosage;

    @ApiModelProperty("administrationroute: ORAL-oral, IV-intravenous, SC-subcutaneous , IM-intramuscular")
    private String adminRoute;

    @ApiModelProperty("administrationTime")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime administrationTime;

    @ApiModelProperty("prescribeClinician")
    private String prescribedBy;

    @ApiModelProperty("effect assessment: GOOD-Good, MODERATE-Fair, POOR-poor")
    private String effectEvaluation;

    @ApiModelProperty("adverse reaction")
    private String sideEffect;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("userID")
    private Long userId;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // non-datadatabasefield
    @TableField(exist = false)
    @ApiModelProperty("related Medicationinformation")
    private Medication medication;
}