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
 * healthlaboratory test dictionaryentity
 */
@Data
@TableName("health_indicator")
@ApiModel("healthlaboratory test dictionary")
public class HealthIndicator {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("indicatorCode, for example  FERRITIN")
    private String itemCode;

    @ApiModelProperty("standardName")
    private String itemName;

    @ApiModelProperty("alias, comma-separated: ferritin,serumferritin")
    private String aliases;

    @ApiModelProperty("Unit")
    private String unit;

    @ApiModelProperty("MaleReference Range")
    private String refRangeMale;

    @ApiModelProperty("FemaleReference Range")
    private String refRangeFemale;

    @ApiModelProperty("indicatorcategory: BLOOD/KIDNEY/LIVER/BONE/IRONetc.")
    private String category;

    @ApiModelProperty("Order")
    private Integer sortOrder;

    @ApiModelProperty("YesNoEnabled: 0-Disabled, 1-Enabled")
    private Integer isActive;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
