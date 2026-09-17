package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
 * Nutrition Assessmententity
 */
@Data
@TableName("nutrition_assessment")
@ApiModel("Nutrition Assessment")
public class NutritionAssessment {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("owner userID")
    private Long userId;

    @ApiModelProperty("assessmentDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate assessmentDate;

    @ApiModelProperty("SGAscore(7pointsystem)")
    private Integer sgaScore;

    @ApiModelProperty("SGAgrade: A-Good, B-mildModerateNutritionadverse, C-severeNutritionadverse")
    private String sgaGrade;

    @ApiModelProperty("BMIpointcount")
    private BigDecimal bmi;

    @ApiModelProperty("Weight(kg)")
    private BigDecimal bodyWeight;

    @ApiModelProperty("Height(cm)")
    private BigDecimal height;

    @ApiModelProperty("albumin(g/L)")
    private BigDecimal albumin;

    @ApiModelProperty("before albumin(mg/L)")
    private BigDecimal preAlbumin;

    @ApiModelProperty("each DayProteinintake(g)")
    private BigDecimal totalProteinIntake;

    @ApiModelProperty("each DayCaloriesintake(kcal)")
    private BigDecimal dailyCalorieIntake;

    @ApiModelProperty("each Daypotassiumintake(mg)")
    private BigDecimal dailyPotassiumIntake;

    @ApiModelProperty("each Dayphosphorusintake(mg)")
    private BigDecimal dailyPhosphorusIntake;

    @ApiModelProperty("each Dayfluidintake(ml)")
    private Integer fluidIntake;

    @ApiModelProperty("NutritionStatus: GOOD-Good, AT_RISK-has Risk, DEFICIENT-deficient")
    private String nutritionStatus;

    @ApiModelProperty("add detailsrecommendation")
    private String supplementAdvice;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
