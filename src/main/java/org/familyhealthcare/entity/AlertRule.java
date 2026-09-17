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
 * alert ruleentity
 */
@Data
@TableName("alert_rule")
@ApiModel("alert rule")
public class AlertRule {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("owner userID")
    private Long userId;

    @ApiModelProperty("relatedindicatorCode, for example  K/CA/HB")
    private String indicatorCode;

    @ApiModelProperty("indicatorName")
    private String indicatorName;

    @ApiModelProperty("thresholdtype: ABOVE-highin, BELOW-below, OUT_OF_RANGE-exceedrange")
    private String thresholdType;

    @ApiModelProperty("thresholdvalue, for example 5.5 or 2.1-2.6")
    private String thresholdValue;

    @ApiModelProperty("alertlevel: INFO-Notice, WARNING-warning, CRITICAL-Severe")
    private String alertLevel;

    @ApiModelProperty("YesNoEnabled: 0-Disabled, 1-Enabled")
    private Integer enabled;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
