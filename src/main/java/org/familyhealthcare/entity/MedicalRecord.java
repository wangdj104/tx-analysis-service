package org.familyhealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Medical Recordsmaintableentity
 */
@Data
@TableName("medical_record")
@ApiModel("Medical Records")
public class MedicalRecord {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("PatientName")
    private String patientName;

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("Examination Date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate recordDate;

    @ApiModelProperty("Examination Type: BLOOD-blood test, URINE-urinalysis, LIVER-liverfeature, KIDNEY-kidneyfeature, BONE-bone metabolism, IRON-iron metabolism, IMAGE-imagingReport, OTHER-Other")
    private String recordType;

    @ApiModelProperty("HospitalName")
    private String hospitalName;

    @ApiModelProperty("DepartmentName")
    private String deptName;

    @ApiModelProperty("ClinicianName")
    private String doctorName;

    @ApiModelProperty("AIoriginalrecognitionresult(JSON)")
    private String aiRawResult;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("userID")
    private Long userId;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // non-datadatabasefield
    @TableField(exist = false)
    @ApiModelProperty("Examination item detailslist")
    private List<MedicalRecordItem> items;

    @TableField(exist = false)
    @ApiModelProperty("Attachmentlist")
    private List<MedicalRecordAttachment> attachments;
}