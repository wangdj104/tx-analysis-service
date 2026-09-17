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
 * Blood GlucoseBlood Pressureself-monitoringrecordentity
 */
@Data
@TableName("bp_self_monitor_record")
@ApiModel("Blood GlucoseBlood Pressureself-monitoringrecord")
public class BpSelfMonitorRecord {

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

    @ApiModelProperty("recordTime(HH:mm)")
    private String recordTime;

    @ApiModelProperty("measurementtype: BP-Blood Pressure, BG-Blood Glucose")
    private String measureType;

    @ApiModelProperty("Systolic Pressure(mmHg)")
    private Integer systolicBp;

    @ApiModelProperty("Diastolic Pressure(mmHg)")
    private Integer diastolicBp;

    @ApiModelProperty("Blood Glucosevalue")
    private BigDecimal bloodGlucose;

    @ApiModelProperty("Blood GlucoseUnit: mmol/L, mg/dL")
    private String bgUnit;

    @ApiModelProperty("measurementperiod: Fasting/After Meal2h/Random")
    private String measurePeriod;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}

