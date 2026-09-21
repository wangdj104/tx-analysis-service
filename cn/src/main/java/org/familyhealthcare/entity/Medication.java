package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Medicationinformationentity
 */
@Data
@TableName("medication")
@ApiModel("Medicationinformation")
public class Medication {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("owner userID")
    private Long userId;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("Medication Name")
    private String drugName;

    @ApiModelProperty("Generic Name")
    private String genericName;

    @ApiModelProperty("Specification")
    private String specification;

    @ApiModelProperty("Unit")
    private String unit;

    @ApiModelProperty("dosage form: TABLET-tablet, CAPSULE-capsule, INJECTION-injection, SOLUTION-oral solution, POWDER-powder")
    private String dosageForm;

    @ApiModelProperty("manufacturer")
    private String manufacturer;

    @ApiModelProperty("approval number")
    private String approvalNumber;

    @ApiModelProperty("Medicationcategory")
    private String category;

    @ApiModelProperty("DefaultDose instructions")
    private String defaultDosage;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("YesNoEnabled: 0-Disabled, 1-Enabled")
    private Integer isActive;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}