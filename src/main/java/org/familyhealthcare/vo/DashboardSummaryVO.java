package org.familyhealthcare.vo;

import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.entity.MedicalRecord;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * firstpagedevicetablepanelsummarize
 */
@Data
public class DashboardSummaryVO {

    private Long patientCount;
    private Long dialysisCountMonth;
    private Long medicalRecordCountMonth;
    private Long medicationLogCountMonth;
    private Long abnormalItemCount;
    private BigDecimal currentDryWeight;
    private String currentDryWeightMonth;
    private BigDecimal previousDryWeight;
    private BigDecimal dryWeightDelta;
    private List<DialysisRecord> recentDialysis;
    private List<MedicalRecord> recentMedicalRecords;
    private List<Map<String, Object>> recentAbnormalItems;

    /** recent 6Monthdatatrend: month, dialysis, medical, medication */
    private List<Map<String, Object>> monthlyTrend;

    /** This MonthDialysisUltrafiltration status distribution */
    private Map<String, Long> dehydrationStats;

    /** Dry Weighthistorytrend: month, weight */
    private List<Map<String, Object>> dryWeightTrend;

    private String healthRiskLevel;
    private String healthRiskLabel;
    private List<String> healthHighlights;
    private BigDecimal dialysisMatchRateMonth;
    private BigDecimal avgWeightGainMonth;
    private BigDecimal avgUfAmountMonth;
    private Long over5pctCountMonth;
    private Long bpAbnormalCountMonth;
    private String latestDialysisStatus;
}
