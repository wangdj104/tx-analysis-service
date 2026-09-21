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
 * Dialysis Recordsentity
 */
@Data
@TableName("dialysis_record")
@ApiModel("Dialysis Records")
public class DialysisRecord {

    @TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean textImport;

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("Dialysis Date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate recordDate;

    @ApiModelProperty("up timesPost-dialysis Weight(kg)")
    private BigDecimal lastOffWeight;

    @ApiModelProperty("this timesPre-dialysis Weight(kg)")
    private BigDecimal onWeight;

    @ApiModelProperty("this timesPost-dialysis Weight(kg)")
    private BigDecimal offWeight;

    @ApiModelProperty("intervaldayscount")
    private Integer intervalDays;

    @ApiModelProperty("interdialytic weight gain(kg)")
    private BigDecimal weightGain;

    @ApiModelProperty("ultrafiltration volume/Fluid Removed(kg)")
    private BigDecimal ufAmount;

    @ApiModelProperty("Dialysis session duration in minutes")
    private Integer sessionMinutes;

    @ApiModelProperty("Single-pool Kt/V")
    private BigDecimal ktv;

    @ApiModelProperty("Urea reduction ratio percent")
    private BigDecimal urr;

    @ApiModelProperty("Vascular access issue observed during treatment")
    private String accessIssue;

    @ApiModelProperty("Blood Pressure-systolic")
    private Integer systolicBp;

    @ApiModelProperty("Blood Pressure-diastolic")
    private Integer diastolicBp;

    @ApiModelProperty("Average daily weight gain(kg)")
    private BigDecimal dailyWeightGain;

    @ApiModelProperty("fluid removalStatus: TOO_MUCH-Excessive ultrafiltration, INSUFFICIENT-Insufficient ultrafiltration, MATCH-match")
    private String dehydrationStatus;

    @ApiModelProperty("Dry Weightreference(kg) (non-datadatabasefield, from Monthly tablerelatedquery) ")
    @TableField(exist = false)
    private BigDecimal dryWeight;

    @ApiModelProperty("Dry Weight 3%")
    @TableField("weight_3pct")
    private BigDecimal weight3pct;

    @ApiModelProperty("Dry Weight 5%")
    @TableField("weight_5pct")
    private BigDecimal weight5pct;

    @ApiModelProperty("recordtype: NORMAL-Normal, INCOMPLETE-dataincomplete")
    private String recordType;

    @ApiModelProperty("Notes/missingreason")
    private String remark;

    @ApiModelProperty("userID")
    private Long userId;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
