package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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
 * Dry WeightMonthly recordentity
 */
@Data
@TableName("dry_weight_monthly")
@ApiModel("Dry WeightMonthly record")
public class DryWeightMonthly {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("Month, format yyyy-MM")
    @TableField("`year_month`")
    private String yearMonth;

    @ApiModelProperty("Dry Weightreference value(kg)")
    private BigDecimal dryWeight;

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
