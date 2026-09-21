package org.familyhealthcare.vo;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * AIanalysisresultVO (real-timeanalysisBack)
 */
@Data
public class AiAnalysisResultVO {

    @ApiModelProperty("AIanalysisoriginaltext")
    private String rawText;

    @ApiModelProperty("WeekdisplayName")
    private String periodLabel;

    // Dry Weightadjustment conclusion
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

    // indicatorsnapshot
    @ApiModelProperty("recordtotal")
    private Integer totalCount;

    @ApiModelProperty("Average pre-dialysis weight")
    private BigDecimal avgOnWeight;

    @ApiModelProperty("Average post-dialysis weight")
    private BigDecimal avgOffWeight;

    @ApiModelProperty("Average interdialytic weight gain")
    private BigDecimal avgWeightGain;

    @ApiModelProperty("Average ultrafiltration volume")
    private BigDecimal avgUfAmount;

    @ApiModelProperty("Ultrafiltration target rate(%)")
    private BigDecimal dehydrationMatchRate;

    // enhanceassessmentfield
    @ApiModelProperty("complicationRiskassessment")
    private String complicationRiskAssessment;

    @ApiModelProperty("Detailedmedicationrecommendation")
    private String medicationAdviceDetails;

    @ApiModelProperty("Blood Pressuretrend summary")
    private String vitalSignTrendSummary;

    @ApiModelProperty("YesNoincludeclinicalup down textdata")
    private Boolean clinicalContextIncluded;
}
