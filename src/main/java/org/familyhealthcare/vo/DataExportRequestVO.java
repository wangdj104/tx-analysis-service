package org.familyhealthcare.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * Data ExportrequestVO
 */
@Data
@ApiModel("Data Exportrequest")
public class DataExportRequestVO {

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("datatype: dialysis-Dialysis Records, bp_analysis-Blood Pressureanalysis, nutrition-Nutrition Diary, complication-complication, medication-medicationrecord, bp_self_monitor-Blood GlucoseBlood Pressurerecord")
    private String dataType;

    @ApiModelProperty("Time dimension: month/week/year")
    private String timeType;

    @ApiModelProperty("Timevalue")
    private String timeValue;

    @ApiModelProperty("Exportformat: csv")
    private String format;

    @ApiModelProperty("De-identify phone numbers and identity-card-like values in free-text fields")
    private Boolean masked;
}
