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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Complication Trackingrecordentity
 */
@Data
@TableName("complication_record")
@ApiModel("Complication Trackingrecord")
public class ComplicationRecord {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("owner userID")
    private Long userId;

    @ApiModelProperty("PatientName")
    private String patientName;

    @ApiModelProperty("complicationtype: INFECTION-infection, HYPOTENSION-DialysisinlowBlood Pressure, ANEMIA-anemia, BONE_DISEASE-bone disease, CARDIOVASCULAR-cardiovascular event, VASCULAR_ACCESS_ISSUE-vascular accessquestion, OTHER-Other")
    private String complicationType;

    @ApiModelProperty("occurDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate occurrenceDate;

    @ApiModelProperty("Severelevel: MILD-Mild, MODERATE-Moderate, SEVERE-severe")
    private String severity;

    @ApiModelProperty("Description")
    private String description;

    @ApiModelProperty("treatment measures")
    private String treatmentMeasures;

    @ApiModelProperty("result/outcome")
    private String outcome;

    @ApiModelProperty("relatedDialysis RecordsID")
    private Long relatedDialysisId;

    @ApiModelProperty("Notes")
    private String remark;

    // ===== relatedDialysis Records temporarytimefield (not enterdatabase)  =====

    @TableField(exist = false)
    @ApiModelProperty("relatedDialysis Date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dialysisRecordDate;

    @TableField(exist = false)
    @ApiModelProperty("relatedDialysis-Pre-dialysis Weight(kg)")
    private BigDecimal dialysisOnWeight;

    @TableField(exist = false)
    @ApiModelProperty("relatedDialysis-Post-dialysis Weight(kg)")
    private BigDecimal dialysisOffWeight;

    @TableField(exist = false)
    @ApiModelProperty("relatedDialysis-interdialytic weight gain(kg)")
    private BigDecimal dialysisWeightGain;

    @TableField(exist = false)
    @ApiModelProperty("relatedDialysis-ultrafiltration volume(kg)")
    private BigDecimal dialysisUfAmount;

    @TableField(exist = false)
    @ApiModelProperty("relatedDialysis-systolic")
    private Integer dialysisSystolicBp;

    @TableField(exist = false)
    @ApiModelProperty("relatedDialysis-diastolic")
    private Integer dialysisDiastolicBp;

    @TableField(exist = false)
    @ApiModelProperty("relatedDialysis-fluid removalStatus")
    private String dialysisDehydrationStatus;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
