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
 * Nutrition Diaryentity (PatientDayoftenrecord)
 */
@Data
@TableName("nutrition_diary")
@ApiModel("Nutrition Diary")
public class NutritionDiary {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("owner userID")
    private Long userId;

    @ApiModelProperty("recordDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate recordDate;

    @ApiModelProperty("Weight(kg)")
    private BigDecimal bodyWeight;

    @ApiModelProperty("appetite: GOOD-Good, NORMAL-Fair, POOR-difference")
    private String appetite;

    @ApiModelProperty("YesNotakeBreakfast")
    private Boolean mealBreakfast;

    @ApiModelProperty("YesNotakeLunch")
    private Boolean mealLunch;

    @ApiModelProperty("YesNotakeDinner")
    private Boolean mealDinner;

    @ApiModelProperty("YesNotakeSnack")
    private Boolean mealSnack;

    @ApiModelProperty("each DayFluid Intake(ml)")
    private Integer fluidIntake;

    @ApiModelProperty("symptomlabel(comma-separated: fatigue,edema,nausea,itching etc.)")
    private String symptoms;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
