package org.familyhealthcare.vo;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * DialysisstatisticsanalysisVO
 */
@Data
public class DialysisStatsVO {

    @ApiModelProperty("recordtotal")
    private Long totalCount;

    @ApiModelProperty("dataincompleterecordcount")
    private Long incompleteCount;

    @ApiModelProperty("Average pre-dialysis weight")
    private BigDecimal avgOnWeight;

    @ApiModelProperty("Average post-dialysis weight")
    private BigDecimal avgOffWeight;

    @ApiModelProperty("Average interdialytic weight gain")
    private BigDecimal avgWeightGain;

    @ApiModelProperty("Average ultrafiltration volume")
    private BigDecimal avgUfAmount;

    @ApiModelProperty("Average systolic pressure")
    private BigDecimal avgSystolicBp;

    @ApiModelProperty("Average diastolic pressure")
    private BigDecimal avgDiastolicBp;

    @ApiModelProperty("Excessive ultrafiltration count")
    private Long tooMuchCount;

    @ApiModelProperty("Insufficient ultrafiltration count")
    private Long insufficientCount;

    @ApiModelProperty("Ultrafiltration on target count")
    private Long matchCount;

    @ApiModelProperty("averageAverage daily weight gain")
    private BigDecimal avgDailyWeightGain;

    @ApiModelProperty("Average interval in days")
    private BigDecimal avgIntervalDays;

    @ApiModelProperty("maximuminterdialytic weight gain")
    private BigDecimal maxWeightGain;

    @ApiModelProperty("minimuminterdialytic weight gain")
    private BigDecimal minWeightGain;

    @ApiModelProperty("weight gain exceedsDry Weight3% count (>3%, contain3%-5%idealrange, onlylegacy compatibilityAPI) ")
    private Long over3pctCount;

    @ApiModelProperty("weight gain exceedsDry Weight5% count (>5%, needheavypointattention) ")
    private Long over5pctCount;

    @ApiModelProperty("weight gainin Dry Weight3%-5%idealrangebetween count")
    private Long idealGainCount;

    @ApiModelProperty("weight gainbelowDry Weight3% count")
    private Long under3pctCount;

    @ApiModelProperty("Ultrafiltration target rate(%)")
    private BigDecimal dehydrationMatchRate;

    @ApiModelProperty("Blood PressureAbnormal readings (systolic or diastolicanyoneout of range) ")
    private Long bpAbnormalCount;

    @ApiModelProperty("systolicAbnormal readings (non-120-140) ")
    private Long bpSysAbnormalCount;

    @ApiModelProperty("diastolicAbnormal readings (non-70-90) ")
    private Long bpDiaAbnormalCount;

    @ApiModelProperty("Monthly statisticsdata")
    private List<Map<String, Object>> monthlyStats;

    @ApiModelProperty("Datelist (used forcharttable) ")
    private List<String> dateList;

    @ApiModelProperty("Pre-dialysis Weightlist")
    private List<BigDecimal> onWeightList;

    @ApiModelProperty("Post-dialysis Weightlist")
    private List<BigDecimal> offWeightList;

    @ApiModelProperty("Dry Weightreferenceline")
    private List<BigDecimal> dryWeightList;

    @ApiModelProperty("interdialytic weight gainlist")
    private List<BigDecimal> weightGainList;

    @ApiModelProperty("ultrafiltration volumelist")
    private List<BigDecimal> ufAmountList;

    @ApiModelProperty("systoliclist")
    private List<Integer> systolicBpList;

    @ApiModelProperty("diastoliclist")
    private List<Integer> diastolicBpList;

    @ApiModelProperty("3%thresholdline")
    private List<BigDecimal> weight3pctList;

    @ApiModelProperty("5%thresholdline")
    private List<BigDecimal> weight5pctList;

    @ApiModelProperty("Average daily weight gainlist")
    private List<BigDecimal> dailyWeightGainList;
}
