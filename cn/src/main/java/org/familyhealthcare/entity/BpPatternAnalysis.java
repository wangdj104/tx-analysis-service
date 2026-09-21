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
 * Blood Pressure Pattern Analysisentity
 */
@Data
@TableName("bp_pattern_analysis")
@ApiModel("Blood Pressure Pattern Analysis")
public class BpPatternAnalysis {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("owner userID")
    private Long userId;

    @ApiModelProperty("Analysis date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate analysisDate;

    @ApiModelProperty("Time dimension")
    private String timeType;

    @ApiModelProperty("Timevalue")
    private String timeValue;

    @ApiModelProperty("Average systolic pressure(mmHg)")
    private BigDecimal avgSystolic;

    @ApiModelProperty("Average diastolic pressure(mmHg)")
    private BigDecimal avgDiastolic;

    @ApiModelProperty("maximumSystolic Pressure(mmHg)")
    private Integer maxSystolic;

    @ApiModelProperty("minimumSystolic Pressure(mmHg)")
    private Integer minSystolic;

    @ApiModelProperty("Systolic standard deviation")
    private BigDecimal stdDeviation;

    @ApiModelProperty("Orthostatic hypotension count")
    private Integer orthostaticCount;

    @ApiModelProperty("Low blood pressure count")
    private Integer lowBpCount;

    @ApiModelProperty("High blood pressure count")
    private Integer highBpCount;

    @ApiModelProperty("Average ultrafiltration volume(kg)")
    private BigDecimal avgUfAmount;

    @ApiModelProperty("Weight-gain and blood-pressure correlation")
    private BigDecimal correlationWeightGainBp;

    @ApiModelProperty("analysissummary")
    private String analysisSummary;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
