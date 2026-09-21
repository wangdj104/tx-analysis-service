package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AIDialysisanalysisrecordentity
 */
@Data
@TableName("ai_analysis_record")
@ApiModel("AIDialysisanalysisrecord")
public class AiAnalysisRecord {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("Time dimension: year-Year, month-Month, week-week")
    private String timeType;

    @ApiModelProperty("Timevalue, for example  2024-05, 2024")
    private String timeValue;

    @ApiModelProperty("WeekdisplayName, for example  2024Year5Month")
    private String periodLabel;

    @ApiModelProperty("AIanalysispositivetext")
    private String analysisContent;

    @ApiModelProperty("YesNoadjustment needed: Yes/No")
    private String dwAdjustNeeded;

    @ApiModelProperty("recommendationadjustment amount(kg)")
    private BigDecimal dwAdjustAmount;

    @ApiModelProperty("recommendationtargetDry Weight(kg)")
    private BigDecimal dwTargetWeight;

    @ApiModelProperty("adjustment rationale")
    private String dwAdjustReason;

    // multipledimensionhealthassessmentconclusion
    @ApiModelProperty("Weightcontrol assessment: excellent/Good/Fair/difference")
    private String weightControlEval;

    @ApiModelProperty("fluid removal assessment: excellent/Good/Fair/difference")
    private String dehydrationEval;

    @ApiModelProperty("Blood Pressurecontrol assessment: excellent/Good/Fair/difference")
    private String bpControlEval;

    @ApiModelProperty("primaryRiskNotice")
    private String mainRisk;

    @ApiModelProperty("dietrecommendation")
    private String dietAdvice;

    @ApiModelProperty("fluid intake controlrecommendation")
    private String fluidAdvice;

    @ApiModelProperty("exerciserecommendation")
    private String exerciseAdvice;

    @ApiModelProperty("medicationrecommendation")
    private String medicationAdvice;

    @ApiModelProperty("follow-up/follow-up examinationrecommendation")
    private String followUpAdvice;

    @ApiModelProperty("recordtotal")
    private Integer totalCount;

    @ApiModelProperty("Average pre-dialysis weight(kg)")
    private BigDecimal avgOnWeight;

    @ApiModelProperty("Average post-dialysis weight(kg)")
    private BigDecimal avgOffWeight;

    @ApiModelProperty("Average interdialytic weight gain(kg)")
    private BigDecimal avgWeightGain;

    @ApiModelProperty("Average ultrafiltration volume(kg)")
    private BigDecimal avgUfAmount;

    @ApiModelProperty("Ultrafiltration target rate(%)")
    private BigDecimal dehydrationMatchRate;

    @ApiModelProperty("complicationRiskassessment")
    private String complicationRiskAssessment;

    @ApiModelProperty("Detailedmedicationrecommendation")
    private String medicationAdviceDetails;

    @ApiModelProperty("Blood Pressuretrend summary")
    private String vitalSignTrendSummary;

    @ApiModelProperty("APPROVED, REVIEW_REQUIRED or REJECTED")
    private String reviewStatus;

    private Long reviewedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedAt;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    @ApiModelProperty("userID")
    private Long userId;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("Notes")
    private String remark;
}
