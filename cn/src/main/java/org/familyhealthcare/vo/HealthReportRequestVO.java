package org.familyhealthcare.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Map;

/**
 * Health ReportExportrequestVO
 */
@Data
@ApiModel("Health ReportExportrequest")
public class HealthReportRequestVO {

    @ApiModelProperty("PatientID")
    private Long patientId;

    @ApiModelProperty("Time dimension: month/week/year")
    private String timeType;

    @ApiModelProperty("Timevalue")
    private String timeValue;

    @ApiModelProperty("Reporttype: summary-overallReport, bp-Blood PressureReport, nutrition-NutritionReport, bp_monitor-Blood GlucoseBlood PressureReport")
    private String reportType;

    @ApiModelProperty("Exportformat: pdf/html")
    private String format;

    @ApiModelProperty("before endEChartstrendcharttruncatechart, keyfor charttableidentifier, valuefor dataURL")
    private Map<String, String> trendChartImages;
}
