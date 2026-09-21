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
 * Patientclinical detailsinformationentity
 */
@Data
@TableName("patient_clinical")
@ApiModel("Patientclinicalinformation")
public class PatientClinical {

    @TableId(type = IdType.AUTO)
    @ApiModelProperty("primary keyID")
    private Long id;

    @ApiModelProperty("relatedPatientID")
    private Long patientId;

    @ApiModelProperty("owner userID")
    private Long userId;

    @ApiModelProperty("Dialysistype: HD-bloodDialysis, PD-peritonealDialysis, CRRT-continuouspropertykidneyorganreplacetreatment")
    private String dialysisType;

    @ApiModelProperty("startDialysis Date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dialysisStartDate;

    @ApiModelProperty("vascular access: AVF-arteriovenous fistula, AVG-personworkbloodmanagewithinfistula, CATH-central venous catheter")
    private String vascularAccess;

    @ApiModelProperty("originalonset/primary diagnosis")
    private String primaryDiagnosis;

    @ApiModelProperty("Medicationallergy history")
    private String allergyDrugs;

    @ApiModelProperty("currenttargetDry Weight(kg)")
    private BigDecimal targetDryWeight;

    @ApiModelProperty("Dayfluidintakeup limit(ml)")
    private Integer fluidLimitMl;

    @ApiModelProperty("Confirmed dialysis weekdays using ISO 1-7, comma separated")
    private String dialysisWeekdays;

    @ApiModelProperty("Confirmed dialysis time in HH:mm")
    private String dialysisTime;

    @ApiModelProperty("Notes")
    private String remark;

    @ApiModelProperty("Created At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ApiModelProperty("Updated At")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
