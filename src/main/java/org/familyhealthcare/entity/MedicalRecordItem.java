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
 * Examination item detailsentity
 */
@Data
@TableName("medical_record_item")
@ApiModel("Examination item details")
public class MedicalRecordItem {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("relatedrecordID")
    private Long recordId;

    @ApiModelProperty("ExaminationitemName")
    private String itemName;

    @ApiModelProperty("Examinationitemreplacecode")
    private String itemCode;

    @ApiModelProperty("testresultvalue")
    private String resultValue;

    @ApiModelProperty("Unit")
    private String unit;

    @ApiModelProperty("Reference Range")
    private String referenceRange;

    @ApiModelProperty("YesNoAbnormal: 0-Normal, 1-high, -1-low")
    private Integer isAbnormal;

    @ApiModelProperty("AIrecognitionsetmessagelevel")
    private BigDecimal aiConfidence;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}