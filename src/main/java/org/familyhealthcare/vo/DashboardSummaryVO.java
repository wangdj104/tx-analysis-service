package org.familyhealthcare.vo;

import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.entity.MedicalRecord;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.alibaba.fastjson2.annotation.JSONField;
import com.alibaba.fastjson2.JSONWriter;

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
    private String medicalQualificationStatus;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JSONField(serializeFeatures = JSONWriter.Feature.WriteNulls)
    private String medicalQualificationAsOf;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JSONField(serializeFeatures = JSONWriter.Feature.WriteNulls)
    private String medicalQualificationSemantics;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JSONField(serializeFeatures = JSONWriter.Feature.WriteNulls, serializeUsing = NullableMedicalValueWriter.class)
    private Long abnormalItemCount;
    private BigDecimal currentDryWeight;
    private String currentDryWeightMonth;
    private BigDecimal previousDryWeight;
    private BigDecimal dryWeightDelta;
    private List<DialysisRecord> recentDialysis;
    private List<MedicalRecord> recentMedicalRecords;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JSONField(serializeFeatures = JSONWriter.Feature.WriteNulls, serializeUsing = NullableMedicalValueWriter.class)
    private List<Map<String, Object>> recentAbnormalItems;

    /** recent 6Monthdatatrend: month, dialysis, medical, medication */
    private List<Map<String, Object>> monthlyTrend;

    /** This MonthDialysisUltrafiltration status distribution */
    private Map<String, Long> dehydrationStats;

    /** Dry Weighthistorytrend: month, weight */
    private List<Map<String, Object>> dryWeightTrend;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JSONField(serializeFeatures = JSONWriter.Feature.WriteNulls)
    private String healthRiskLevel;
    private String healthRiskLabel;
    private List<String> healthHighlights;
    private BigDecimal dialysisMatchRateMonth;
    private BigDecimal avgWeightGainMonth;
    private BigDecimal avgUfAmountMonth;
    private Long over5pctCountMonth;
    private Long bpAbnormalCountMonth;
    private String latestDialysisStatus;
    /** Fastjson 2.0.23's specialized Long/List writers ignore local WriteNulls; keep only these two fields explicit. */
    public static final class NullableMedicalValueWriter implements com.alibaba.fastjson2.writer.ObjectWriter<Object> {
        @Override public void write(JSONWriter writer,Object value,Object fieldName,java.lang.reflect.Type fieldType,long features) {
            writer.writeAny(value);
        }
    }

}
