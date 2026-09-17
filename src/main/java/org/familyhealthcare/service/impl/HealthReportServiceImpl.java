package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.PatientMapper;
import org.familyhealthcare.service.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.DialysisStatsVO;
import org.familyhealthcare.vo.HealthReportRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;

/**
 * Health ReportExportServiceimplement
 * generateHTML/PDFformatReport
 */
@Service
public class HealthReportServiceImpl implements HealthReportService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private PatientMapper patientMapper;

    @Autowired
    private PatientClinicalService patientClinicalService;

    @Autowired
    private DialysisRecordService dialysisRecordService;

    @Autowired
    private BpPatternAnalysisService bpPatternAnalysisService;

    @Autowired
    private NutritionDiaryService nutritionDiaryService;

    @Autowired
    private BpSelfMonitorRecordService bpSelfMonitorRecordService;

    @Override
    public byte[] generateReport(HealthReportRequestVO request, String format) throws Exception {
        Long patientId = request.getPatientId();
        dataScopeHelper.requirePatient(patientId);

        String reportType = request.getReportType() != null ? request.getReportType() : "summary";

        // based onReporttypeitemsitemloaddata, avoidYou do not have permission to timeloadnot needneed data
        Patient patient = patientMapper.selectById(patientId);
        PatientClinical clinical = patientClinicalService.getByPatientId(patientId);

        DialysisStatsVO stats = null;
        DialysisStatsVO chartData = null;
        java.util.List<BpPatternAnalysis> bpList = Collections.emptyList();
        java.util.List<NutritionDiary> nutritionList = Collections.emptyList();
        java.util.List<BpSelfMonitorRecord> bpSelfMonitorList = Collections.emptyList();

        switch (reportType) {
            case "summary":
                // overallReport: loadhas data
                stats = dialysisRecordService.getStatistics(
                        request.getTimeType(), request.getTimeValue(), patientId);
                chartData = dialysisRecordService.getChartData(
                        request.getTimeType(), request.getTimeValue(), patientId);
                bpList = bpPatternAnalysisService.listByPatient(patientId);
                nutritionList = nutritionDiaryService.listByPatient(patientId);
                bpSelfMonitorList = bpSelfMonitorRecordService.listByPatient(patientId);
                break;
            case "summary_no_dialysis":
                // overallReport (NoneDialysisPermission) : not loadDialysisstatistics/trenddata
                bpList = bpPatternAnalysisService.listByPatient(patientId);
                nutritionList = nutritionDiaryService.listByPatient(patientId);
                bpSelfMonitorList = bpSelfMonitorRecordService.listByPatient(patientId);
                break;
            case "bp":
                bpList = bpPatternAnalysisService.listByPatient(patientId);
                break;
            case "nutrition":
                nutritionList = nutritionDiaryService.listByPatient(patientId);
                break;
            case "bp_monitor":
                bpSelfMonitorList = bpSelfMonitorRecordService.listByPatient(patientId);
                break;
            default:
                stats = dialysisRecordService.getStatistics(
                        request.getTimeType(), request.getTimeValue(), patientId);
                chartData = dialysisRecordService.getChartData(
                        request.getTimeType(), request.getTimeValue(), patientId);
                bpList = bpPatternAnalysisService.listByPatient(patientId);
                nutritionList = nutritionDiaryService.listByPatient(patientId);
                bpSelfMonitorList = bpSelfMonitorRecordService.listByPatient(patientId);
                break;
        }

        org.familyhealthcare.util.HealthDateRange range = org.familyhealthcare.util.HealthDateRange.of(request.getTimeType(), request.getTimeValue());
        bpList = bpList.stream().filter(r -> range.contains(r.getAnalysisDate())).collect(java.util.stream.Collectors.toList());
        nutritionList = nutritionList.stream().filter(r -> range.contains(r.getRecordDate())).collect(java.util.stream.Collectors.toList());
        bpSelfMonitorList = bpSelfMonitorList.stream().filter(r -> range.contains(r.getRecordDate())).collect(java.util.stream.Collectors.toList());
        // generateHTMLReport
        String html = buildHtmlReport(patient, clinical, stats, chartData, bpList, nutritionList, bpSelfMonitorList, request);

        if ("pdf".equalsIgnoreCase(format)) {
            return renderPdf(html);
        }
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] renderPdf(String html) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            registerChineseFonts(builder);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        }
    }

    private void registerChineseFonts(PdfRendererBuilder builder) {
        String[] fontPaths = {
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/simsun.ttc",
                "C:/Windows/Fonts/simhei.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
        };
        for (String path : fontPaths) {
            File font = new File(path);
            if (font.exists() && font.isFile()) {
                builder.useFont(font, "Microsoft YaHei");
                builder.useFont(font, "SimSun");
                builder.useFont(font, "Arial Unicode MS");
            }
        }
    }
    private String buildHtmlReport(Patient patient, PatientClinical clinical,
                                    DialysisStatsVO stats,
                                    DialysisStatsVO chartData,
                                    java.util.List<BpPatternAnalysis> bpList,
                                    java.util.List<NutritionDiary> nutritionList,
                                    java.util.List<BpSelfMonitorRecord> bpSelfMonitorList,
                                    HealthReportRequestVO request) {
        String patientName = patient != null ? patient.getName() : "Unknown";
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String reportType = request.getReportType() != null ? request.getReportType() : "summary";
        boolean isSummaryReport = "summary".equals(reportType) || "summary_no_dialysis".equals(reportType);
        boolean includeDialysis = "summary".equals(reportType);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'>");
        sb.append("<title>Health Report - ").append(patientName).append("</title>");
        sb.append("<style>");
        sb.append("body{font-family:'Microsoft YaHei',Arial,sans-serif;max-width:800px;margin:40px auto;color:#333;background:#f8fafc;}");
        sb.append("h1{text-align:center;color:#1e293b;border-bottom:2px solid #4f6af6;padding-bottom:10px;}");
        sb.append("h2{color:#4f6af6;margin-top:24px;border-left:4px solid #4f6af6;padding-left:8px;display:flex;align-items:center;gap:8px;}");
        sb.append("table{width:100%;border-collapse:collapse;margin:12px 0;background:#fff;border-radius:8px;overflow:hidden;}");
        sb.append("th,td{border:1px solid #e2e8f0;padding:8px 12px;text-align:left;}");
        sb.append("th{background:#f8fafc;color:#64748b;font-weight:600;}");
        sb.append(".tag-good{background:#e8f5e9;color:#2e7d32;padding:2px 8px;border-radius:4px;font-size:12px;}");
        sb.append(".tag-risk{background:#fff3e0;color:#e65100;padding:2px 8px;border-radius:4px;font-size:12px;}");
        sb.append(".tag-bad{background:#ffebee;color:#c62828;padding:2px 8px;border-radius:4px;font-size:12px;}");
        sb.append(".footer{text-align:center;color:#94a3b8;font-size:12px;margin-top:40px;border-top:1px solid #e2e8f0;padding-top:12px;}");
        sb.append(".section-icon{width:20px;height:20px;vertical-align:middle;}");
        sb.append(".progress-bar{background:#e2e8f0;border-radius:4px;height:8px;width:120px;display:inline-block;}");
        sb.append(".progress-fill{height:8px;border-radius:4px;display:block;}");
        sb.append(".badge{display:inline-flex;align-items:center;gap:4px;padding:2px 10px;border-radius:12px;font-size:12px;font-weight:600;}");
        sb.append(".badge-good{background:#e8f5e9;color:#2e7d32;}");
        sb.append(".badge-risk{background:#fff3e0;color:#e65100;}");
        sb.append(".badge-bad{background:#ffebee;color:#c62828;}");
        sb.append(".badge-normal{background:#e0e7ff;color:#4f46e5;}");
        sb.append(".report-card{background:#fff;border-radius:12px;padding:20px;margin:16px 0;box-shadow:0 1px 3px rgb(15 23 42 / 8%);}");
        sb.append(".trend-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:12px;}");
        sb.append(".trend-card{border:1px solid #e2e8f0;border-radius:10px;padding:12px;background:#fbfdff;}");
        sb.append(".trend-card h3{margin:0 0 8px;color:#334155;font-size:15px;}");
        sb.append(".trend-note{color:#64748b;font-size:12px;margin:6px 0 0;}");
        sb.append(".echart-image-card{border:1px solid #e2e8f0;border-radius:12px;padding:12px;background:#fff;break-inside:avoid;page-break-inside:avoid;}");
        sb.append(".echart-image-card--full{grid-column:1/-1;}");
        sb.append(".echart-image-card h3{margin:0 0 10px;color:#334155;font-size:15px;}");
        sb.append(".echart-image{display:block;width:100%;height:auto;border-radius:8px;}");
        sb.append(".risk-row{display:grid;grid-template-columns:140px 1fr 64px;align-items:center;gap:10px;margin:8px 0;font-size:13px;}");
        sb.append(".risk-track{height:10px;background:#e2e8f0;border-radius:999px;overflow:hidden;}");
        sb.append(".risk-fill{height:10px;border-radius:999px;display:block;}");
        sb.append(".summary-pills{display:flex;flex-wrap:wrap;gap:8px;margin:10px 0;}");
        sb.append(".summary-pill{background:#eef2ff;color:#4f46e5;border-radius:999px;padding:4px 10px;font-size:12px;font-weight:600;}");
        sb.append("@media print{body{background:#fff;margin:20px auto}.report-card{box-shadow:none;border:1px solid #e2e8f0}.trend-grid{grid-template-columns:1fr;}}");
        sb.append("</style></head><body>");

        // Reporttitle
        String titleLabel;
        switch (reportType) {
            case "bp": titleLabel = "Blood Pressure Pattern AnalysisReport"; break;
            case "nutrition": titleLabel = "Nutrition DiaryReport"; break;
            case "bp_monitor": titleLabel = "Blood GlucoseBlood PressuremonitoringReport"; break;
            default: titleLabel = "healthdataoverallReport"; break;
        }
        sb.append("<h1>").append(titleLabel).append("</h1>");
        sb.append("<p style='text-align:center;color:#64748b;'>Patient: ").append(patientName);
        sb.append(" | ReportDate: ").append(date).append("</p>");

        // Patientclinicalinformation (onlyoverallReportdisplay)
        if (isSummaryReport && clinical != null) {
            sb.append("<div class='report-card'>");
            sb.append("<h2>").append(svgIcon("clinical")).append(" Patientclinicalinformation</h2>");
            sb.append("<table><tr><th>Dialysistype</th><td>").append(nvl(clinical.getDialysisType())).append("</td>");
            sb.append("<th>startDialysis Date</th><td>").append(nvl(clinical.getDialysisStartDate())).append("</td></tr>");
            sb.append("<tr><th>vascular access</th><td>").append(nvl(clinical.getVascularAccess())).append("</td>");
            sb.append("<th>primary diagnosis</th><td>").append(nvl(clinical.getPrimaryDiagnosis())).append("</td></tr>");
            sb.append("</table></div>");
        }

        // Dialysisstatisticsdata (onlyoverallReportdisplay)
        if (includeDialysis && stats != null) {
            sb.append("<div class='report-card'>");
            sb.append("<h2>").append(svgIcon("dialysis")).append(" Dialysisstatisticsoverview</h2>");
            sb.append("<table>");
            sb.append("<tr><th>recordtotal</th><td>").append(nvl(stats.getTotalCount())).append(" times</td>");
            sb.append("<th>Average interdialytic weight gain</th><td>").append(fmt(stats.getAvgWeightGain())).append(" kg</td></tr>");
            sb.append("<tr><th>Ultrafiltration target rate</th><td>").append(fmt(stats.getDehydrationMatchRate()));
            sb.append(progressBar(stats.getDehydrationMatchRate(), "good"));
            sb.append("</td>");
            sb.append("<th>Average ultrafiltration volume</th><td>").append(fmt(stats.getAvgUfAmount())).append(" kg</td></tr>");
            sb.append("<tr><th>Average systolic pressure</th><td>").append(fmt(stats.getAvgSystolicBp())).append(" mmHg</td>");
            sb.append("<th>Average diastolic pressure</th><td>").append(fmt(stats.getAvgDiastolicBp())).append(" mmHg</td></tr>");
            sb.append("<tr><th>Blood PressureAbnormal readings</th><td>").append(nvl(stats.getBpAbnormalCount()));
            sb.append(badgeForBpAbnormal(stats.getBpAbnormalCount()));
            sb.append("</td>");
            sb.append("<th>weight gain exceeds5% count</th><td>").append(nvl(stats.getOver5pctCount())).append(" times");
            sb.append(badgeForOver5(stats.getOver5pctCount()));
            sb.append("</td></tr>");
            sb.append("</table></div>");
            sb.append(buildDialysisDetailCards(stats));
            sb.append(buildDialysisTrendSection(stats, chartData, request));
        }

        // Blood Pressure Pattern Analysis (overallReport or Blood PressureReportdisplay)
        if ((isSummaryReport || "bp".equals(reportType)) && !bpList.isEmpty()) {
            sb.append("<div class='report-card'>");
            sb.append("<h2>").append(svgIcon("bp")).append(" Blood Pressure Pattern Analysis</h2>");
            sb.append("<table><tr><th>Date</th><th>Week</th><th>Average blood pressure</th><th>standard deviation</th><th>Abnormal readings</th><th>Status</th></tr>");
            for (BpPatternAnalysis item : bpList) {
                sb.append("<tr><td>").append(nvl(item.getAnalysisDate())).append("</td>");
                sb.append("<td>").append(nvl(item.getTimeValue())).append("</td>");
                sb.append("<td>").append(nvl(item.getAvgSystolic())).append("/").append(nvl(item.getAvgDiastolic())).append("</td>");
                sb.append("<td>").append(nvl(item.getStdDeviation())).append("</td>");
                int abnormal = (item.getLowBpCount() != null ? item.getLowBpCount() : 0) +
                        (item.getHighBpCount() != null ? item.getHighBpCount() : 0) +
                        (item.getOrthostaticCount() != null ? item.getOrthostaticCount() : 0);
                sb.append("<td>").append(abnormal).append(" times").append(badgeForAbnormal(abnormal)).append("</td>");
                sb.append("<td>").append(nvl(item.getAnalysisSummary())).append("</td></tr>");
            }
            sb.append("</table></div>");
        }

        // Nutrition Diary (overallReport or NutritionReportdisplay)
        if ((isSummaryReport || "nutrition".equals(reportType)) && !nutritionList.isEmpty()) {
            sb.append("<div class='report-card'>");
            sb.append("<h2>").append(svgIcon("nutrition")).append(" Nutrition Diary</h2>");
            sb.append("<table><tr><th>Date</th><th>Weight(kg)</th><th>appetite</th><th>threemeal</th><th>Fluid Intake(ml)</th><th>symptom</th><th>Notes</th></tr>");
            for (NutritionDiary nd : nutritionList) {
                sb.append("<tr><td>").append(nvl(nd.getRecordDate())).append("</td>");
                sb.append("<td>").append(nvl(nd.getBodyWeight())).append("</td>");
                sb.append("<td>").append(appetiteLabel(nd.getAppetite())).append(" ").append(badgeForAppetite(nd.getAppetite())).append("</td>");
                sb.append("<td>").append(mealsText(nd)).append("</td>");
                sb.append("<td>").append(nvl(nd.getFluidIntake())).append("</td>");
                sb.append("<td>").append(nvl(nd.getSymptoms())).append("</td>");
                sb.append("<td>").append(nvl(nd.getRemark())).append("</td></tr>");
            }
            sb.append("</table></div>");
        }

        // Blood GlucoseBlood Pressuremonitoring (overallReport or Blood GlucoseBlood PressureReportdisplay)
        if ((isSummaryReport || "bp_monitor".equals(reportType)) && !bpSelfMonitorList.isEmpty()) {
            sb.append("<div class='report-card'>");
            sb.append("<h2>").append(svgIcon("monitor")).append(" Blood GlucoseBlood Pressuremonitoring</h2>");
            sb.append("<table><tr><th>Date</th><th>Time</th><th>type</th><th>Blood Pressure</th><th>Blood Glucose</th><th>period</th></tr>");
            for (BpSelfMonitorRecord r : bpSelfMonitorList) {
                sb.append("<tr><td>").append(nvl(r.getRecordDate())).append("</td>");
                sb.append("<td>").append(nvl(r.getRecordTime())).append("</td>");
                sb.append("<td>").append("BP".equals(r.getMeasureType()) ? "Blood Pressure" : "BG".equals(r.getMeasureType()) ? "Blood Glucose" : "Blood Pressure+Blood Glucose").append("</td>");
                if (r.getSystolicBp() != null && r.getDiastolicBp() != null) {
                    sb.append("<td>").append(r.getSystolicBp()).append("/").append(r.getDiastolicBp());
                    sb.append(badgeForBpValue(r.getSystolicBp())).append("</td>");
                } else {
                    sb.append("<td>-</td>");
                }
                if (r.getBloodGlucose() != null) {
                    sb.append("<td>").append(r.getBloodGlucose()).append(" ").append(nvl(r.getBgUnit()));
                    sb.append(badgeForBgValue(r.getBloodGlucose(), r.getMeasurePeriod())).append("</td>");
                } else {
                    sb.append("<td>-</td>");
                }
                sb.append("<td>").append(nvl(r.getMeasurePeriod())).append("</td></tr>");
            }
            sb.append("</table></div>");
        }

        sb.append("<div class='footer'>this ReportbysystemAutomaticgenerate, onlyprovidereference, most enddiagnosisPlease follow the prescription. </div>");
        sb.append("</body></html>");
        return sb.toString();
    }


    private String buildDialysisTrendSection(DialysisStatsVO stats, DialysisStatsVO chartData, HealthReportRequestVO request) {
        if (chartData == null || chartData.getDateList() == null || chartData.getDateList().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='report-card'>");
        sb.append("<h2>").append(svgIcon("trend")).append(" DialysisTrend Analysis</h2>");
        sb.append("<div class='summary-pills'>");
        sb.append("<span class='summary-pill'>Weekrecord ").append(chartData.getDateList().size()).append(" times</span>");
        if (stats != null) {
            sb.append("<span class='summary-pill'>averageAverage daily weight gain ").append(fmt(stats.getAvgDailyWeightGain())).append(" kg/days</span>");
            sb.append("<span class='summary-pill'>weight gainpeakvalue ").append(fmt(stats.getMaxWeightGain())).append(" kg</span>");
            sb.append("<span class='summary-pill'>Average interval ").append(fmt(stats.getAvgIntervalDays())).append(" days</span>");
        }
        sb.append("</div>");
        if (hasTrendChartImages(request)) {
            sb.append(renderTrendChartImages(request.getTrendChartImages(), stats));
            sb.append("<p class='trend-note'>instructions: trendchartcomeselfDialysis Management-Trend Analysissameoneset ECharts charttabletruncatechart; emptypointtableshowthis timesrecordWeight or Blood Pressuredataincomplete. </p>");
            sb.append("</div>");
            return sb.toString();
        }
        sb.append("<div class='trend-grid'>");
        sb.append(renderWeightOverviewChart(chartData));
        sb.append(renderLineChart("Pre-dialysis Weighttrend", chartData.getDateList(),
                chartData.getOnWeightList(), "Pre-dialysis Weight", "#6366f1",
                chartData.getDryWeightList(), "Dry Weightreference", "#E6A23C",
                null, null, null,
                null, null, null));
        sb.append(renderLineChart("Post-dialysis Weighttrend", chartData.getDateList(),
                chartData.getOffWeightList(), "Post-dialysis Weight", "#10b981",
                chartData.getDryWeightList(), "Dry Weightreference", "#E6A23C",
                null, null, null,
                null, null, null));
        sb.append(renderLineChart("interdialytic weight gain / ultrafiltration volume", chartData.getDateList(),
                chartData.getWeightGainList(), "interdialytic weight gain", "#3b82f6",
                chartData.getUfAmountList(), "ultrafiltration volume", "#10b981",
                chartData.getWeight3pctList(), "3%threshold", "#E6A23C",
                chartData.getWeight5pctList(), "5%threshold", "#F56C6C"));
        sb.append(renderLineChart("Average daily weight gain", chartData.getDateList(),
                chartData.getDailyWeightGainList(), "Average daily weight gain", "#8b5cf6",
                null, null, null,
                null, null, null,
                null, null, null));
        sb.append(renderBpTrendChart(chartData));
        sb.append(renderDehydrationDistribution(stats));
        sb.append("</div>");
        sb.append(renderMonthlyStatsChart(stats));
        sb.append("<p class='trend-note'>instructions: trendchartcomeselfDialysis Management-Trend Analysissameonecharttabledata; emptypointtableshowthis timesrecordWeight or Blood Pressuredataincomplete. </p>");
        sb.append("</div>");
        return sb.toString();
    }

    private String buildDialysisDetailCards(DialysisStatsVO stats) {
        if (stats == null) return "";
        StringBuilder sb = new StringBuilder();

        // Weight and fluid removal
        sb.append("<div class='report-card'>");
        sb.append("<h2>").append(svgIcon("dialysis")).append(" Weight and fluid removal</h2>");
        sb.append("<table>");
        sb.append("<tr><th>Average interdialytic weight gain</th><td>").append(fmt(stats.getAvgWeightGain())).append(" kg</td>");
        sb.append("<th>Average ultrafiltration volume</th><td>").append(fmt(stats.getAvgUfAmount())).append(" kg</td></tr>");
        sb.append("<tr><th>Average daily weight gain</th><td>").append(fmt(stats.getAvgDailyWeightGain())).append(" kg/days</td>");
        sb.append("<th>weight gainpeakvalue</th><td>").append(fmt(stats.getMaxWeightGain())).append(" kg</td></tr>");
        sb.append("<tr><th>Average interval</th><td>").append(fmt(stats.getAvgIntervalDays())).append(" days</td>");
        sb.append("<th></th><td></td></tr>");
        sb.append("</table></div>");

        // Blood Pressuremonitoring
        sb.append("<div class='report-card'>");
        sb.append("<h2>").append(svgIcon("bp")).append(" Blood Pressuremonitoring</h2>");
        sb.append("<table>");
        sb.append("<tr><th>Average systolic pressure</th><td>").append(fmt(stats.getAvgSystolicBp())).append(" mmHg <span style='color:#94a3b8;font-size:12px;'>target120-140</span></td>");
        sb.append("<th>Average diastolic pressure</th><td>").append(fmt(stats.getAvgDiastolicBp())).append(" mmHg <span style='color:#94a3b8;font-size:12px;'>target70-90</span></td></tr>");
        sb.append("<tr><th>systolicAbnormal</th><td>").append(nvl(stats.getBpSysAbnormalCount())).append(" times").append(badgeForBpAbnormal(stats.getBpSysAbnormalCount())).append("</td>");
        sb.append("<th>diastolicAbnormal</th><td>").append(nvl(stats.getBpDiaAbnormalCount())).append(" times").append(badgeForBpAbnormal(stats.getBpDiaAbnormalCount())).append("</td></tr>");
        sb.append("<tr><th>Blood PressureAbnormaltotal</th><td colspan='3'>").append(nvl(stats.getBpAbnormalCount())).append(" times").append(badgeForBpAbnormal(stats.getBpAbnormalCount())).append("</td></tr>");
        sb.append("</table></div>");

        // Risk alerts
        sb.append("<div class='report-card'>");
        sb.append("<h2>").append(svgIcon("trend")).append(" Risk alerts</h2>");
        sb.append("<div style='display:grid;grid-template-columns:1fr 1fr;gap:12px;'>");
        sb.append("<div><table>");
        sb.append("<tr><th>Weight gain above target(&gt;5%)</th><td>").append(nvl(stats.getOver5pctCount())).append(" times</td></tr>");
        sb.append("<tr><th>Weight gain within target(3%-5%)</th><td>").append(nvl(stats.getIdealGainCount())).append(" times</td></tr>");
        sb.append("<tr><th>Weight gain below target(&lt;3%)</th><td>").append(nvl(stats.getUnder3pctCount())).append(" times</td></tr>");
        sb.append("</table></div>");
        sb.append("<div><table>");
        sb.append("<tr><th>Ultrafiltration on target</th><td>").append(nvl(stats.getMatchCount())).append(" times</td></tr>");
        sb.append("<tr><th>Excessive ultrafiltration</th><td>").append(nvl(stats.getTooMuchCount())).append(" times</td></tr>");
        sb.append("<tr><th>Insufficient ultrafiltration</th><td>").append(nvl(stats.getInsufficientCount())).append(" times</td></tr>");
        sb.append("</table></div>");
        sb.append("</div></div>");

        return sb.toString();
    }

    private boolean hasTrendChartImages(HealthReportRequestVO request) {
        return request != null && request.getTrendChartImages() != null && !request.getTrendChartImages().isEmpty();
    }

    private String renderTrendChartImages(Map<String, String> images, DialysisStatsVO stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trend-grid'>");
        appendTrendImage(sb, images, "weightOverview", "Weight and weight gainthresholdoveralltrend", true);
        appendTrendImage(sb, images, "onWeight", "Pre-dialysis Weighttrend", false);
        appendTrendImage(sb, images, "offWeight", "Post-dialysis Weighttrend", false);
        appendTrendImage(sb, images, "uf", "interdialytic weight gain / ultrafiltration volume", false);
        appendTrendImage(sb, images, "dailyGain", "Average daily weight gain", false);
        appendTrendImage(sb, images, "bp", "Blood Pressuretrend", false);
        appendTrendImage(sb, images, "dehydration", "Ultrafiltration status distribution", false);
        sb.append("</div>");
        String monthly = images.get("monthly");
        if (monthly != null && monthly.startsWith("data:image/")) {
            sb.append("<div class='report-card' style='margin-top:14px;'>");
            sb.append("<h2>").append(svgIcon("trend")).append(" Monthly Average interdialytic weight gain / ultrafiltration volume</h2>");
            sb.append("<div class='trend-grid'>");
            appendTrendImage(sb, images, "monthly", "Monthly Average interdialytic weight gain / ultrafiltration volume", true);
            sb.append("</div>");
            sb.append(renderMonthlyStatsTable(stats));
            sb.append("</div>");
        }
        return sb.toString();
    }

    private void appendTrendImage(StringBuilder sb, Map<String, String> images, String key, String title, boolean full) {
        String src = images.get(key);
        if (src == null || !src.startsWith("data:image/")) return;
        sb.append("<div class='echart-image-card");
        if (full) sb.append(" echart-image-card--full");
        sb.append("'><h3>").append(title).append("</h3>");
        sb.append("<img class='echart-image' src='").append(src).append("' alt='").append(title).append("'/></div>");
    }
    private static class ReportSeries {
        final java.util.List<? extends Number> values;
        final String name;
        final String color;
        final String type;
        final int axis;
        final boolean dashed;

        ReportSeries(java.util.List<? extends Number> values, String name, String color, String type, int axis, boolean dashed) {
            this.values = values;
            this.name = name;
            this.color = color;
            this.type = type;
            this.axis = axis;
            this.dashed = dashed;
        }
    }

    private String renderWeightOverviewChart(DialysisStatsVO chartData) {
        java.util.List<ReportSeries> series = new java.util.ArrayList<>();
        series.add(new ReportSeries(chartData.getOnWeightList(), "Pre-dialysis Weight", "#6366f1", "line", 0, false));
        series.add(new ReportSeries(chartData.getOffWeightList(), "Post-dialysis Weight", "#10b981", "line", 0, false));
        series.add(new ReportSeries(chartData.getDryWeightList(), "Dry Weight", "#64748b", "line", 0, true));
        series.add(new ReportSeries(chartData.getWeightGainList(), "weight gain", "#38bdf8", "bar", 1, false));
        series.add(new ReportSeries(chartData.getWeight3pctList(), "3%threshold", "#f59e0b", "line", 1, true));
        series.add(new ReportSeries(chartData.getWeight5pctList(), "5%threshold", "#ef4444", "line", 1, true));
        return renderComboChart("Weight and weight gainthresholdoveralltrend", chartData.getDateList(), "Weight kg", "weight gain kg", series);
    }

    private String renderBpTrendChart(DialysisStatsVO chartData) {
        java.util.List<Number> idealSys = constantSeries(chartData.getDateList(), 130);
        java.util.List<Number> idealDia = constantSeries(chartData.getDateList(), 80);
        java.util.List<ReportSeries> series = new java.util.ArrayList<>();
        series.add(new ReportSeries(chartData.getSystolicBpList(), "systolic", "#ef4444", "line", 0, false));
        series.add(new ReportSeries(chartData.getDiastolicBpList(), "diastolic", "#3b82f6", "line", 0, false));
        series.add(new ReportSeries(idealSys, "idealsystolic", "#67C23A", "line", 0, true));
        series.add(new ReportSeries(idealDia, "idealdiastolic", "#67C23A", "line", 0, true));
        return renderComboChart("Blood Pressuretrend", chartData.getDateList(), "mmHg", null, series, 40.0, null);
    }

    private String renderMonthlyStatsChart(DialysisStatsVO stats) {
        if (stats == null || stats.getMonthlyStats() == null || stats.getMonthlyStats().isEmpty()) {
            return "";
        }
        java.util.List<String> months = new java.util.ArrayList<>();
        java.util.List<Number> avgGain = new java.util.ArrayList<>();
        java.util.List<Number> avgUf = new java.util.ArrayList<>();
        for (Map<String, Object> row : stats.getMonthlyStats()) {
            months.add(row.get("month") != null ? row.get("month").toString() : "-");
            avgGain.add(numberOf(row.get("avg_weight_gain")));
            avgUf.add(numberOf(row.get("avg_uf_amount")));
        }
        java.util.List<ReportSeries> series = new java.util.ArrayList<>();
        series.add(new ReportSeries(avgGain, "Average interdialytic weight gain", "#409EFF", "bar", 0, false));
        series.add(new ReportSeries(avgUf, "Average ultrafiltration volume", "#67C23A", "bar", 0, false));

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='report-card' style='margin-top:14px;'>");
        sb.append("<h2>").append(svgIcon("trend")).append(" Monthly Average interdialytic weight gain / ultrafiltration volume</h2>");
        sb.append(renderComboChart("Monthly Average interdialytic weight gain / ultrafiltration volume", months, "kg", null, series));
        sb.append(renderMonthlyStatsTable(stats));
        sb.append("</div>");
        return sb.toString();
    }

    private java.util.List<Number> constantSeries(java.util.List<String> labels, Number value) {
        java.util.List<Number> values = new java.util.ArrayList<>();
        int count = labels != null ? labels.size() : 0;
        for (int i = 0; i < count; i++) values.add(value);
        return values;
    }

    private Number numberOf(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return (Number) value;
        try { return new BigDecimal(value.toString()); } catch (Exception e) { return null; }
    }

    private String renderComboChart(String title, java.util.List<String> labels, String y1Name, String y2Name,
                                    java.util.List<ReportSeries> series) {
        return renderComboChart(title, labels, y1Name, y2Name, series, null, null);
    }

    private String renderComboChart(String title, java.util.List<String> labels, String y1Name, String y2Name,
                                    java.util.List<ReportSeries> series, Double fixedMin1, Double fixedMin2) {
        int count = labels != null ? labels.size() : 0;
        if (count == 0 || series == null || series.isEmpty()) {
            return "<div class='trend-card'><h3>" + title + "</h3><p class='trend-note'>No data available</p></div>";
        }

        double[] r1 = rangeForAxis(series, 0, fixedMin1);
        double[] r2 = rangeForAxis(series, 1, fixedMin2);
        boolean hasAxis2 = hasAxis(series, 1) && r2 != null;
        if (r1 == null && !hasAxis2) {
            return "<div class='trend-card'><h3>" + title + "</h3><p class='trend-note'>No data available</p></div>";
        }
        if (r1 == null) r1 = r2;
        if (r2 == null) r2 = r1;

        int width = 680;
        int height = 300;
        int left = 50;
        int right = hasAxis2 ? 52 : 18;
        int top = 28;
        int bottom = 58;
        int plotW = width - left - right;
        int plotH = height - top - bottom;

        StringBuilder svg = new StringBuilder();
        svg.append("<div class='trend-card'><h3>").append(title).append("</h3>");
        svg.append("<svg viewBox='0 0 ").append(width).append(" ").append(height).append("' width='100%' height='300' role='img'>");
        svg.append("<rect x='0' y='0' width='").append(width).append("' height='").append(height).append("' rx='10' fill='#ffffff'/>");
        if (y1Name != null) svg.append("<text x='").append(left).append("' y='14' fill='#94a3b8' font-size='11'>").append(y1Name).append("</text>");
        if (hasAxis2 && y2Name != null) svg.append("<text text-anchor='end' x='").append(width - right).append("' y='14' fill='#94a3b8' font-size='11'>").append(y2Name).append("</text>");
        for (int i = 0; i <= 4; i++) {
            double y = top + plotH * i / 4.0;
            double val1 = r1[1] - (r1[1] - r1[0]) * i / 4.0;
            svg.append("<line x1='").append(left).append("' y1='").append(round(y)).append("' x2='").append(width - right).append("' y2='").append(round(y)).append("' stroke='#e2e8f0' stroke-width='1'/>");
            svg.append("<text x='8' y='").append(round(y + 4)).append("' fill='#94a3b8' font-size='11'>").append(formatChartValue(val1)).append("</text>");
            if (hasAxis2) {
                double val2 = r2[1] - (r2[1] - r2[0]) * i / 4.0;
                svg.append("<text text-anchor='end' x='").append(width - 8).append("' y='").append(round(y + 4)).append("' fill='#94a3b8' font-size='11'>").append(formatChartValue(val2)).append("</text>");
            }
        }
        svg.append("<line x1='").append(left).append("' y1='").append(top + plotH).append("' x2='").append(width - right).append("' y2='").append(top + plotH).append("' stroke='#cbd5e1' stroke-width='1'/>");

        int barSeriesCount = 0;
        for (ReportSeries s : series) if ("bar".equals(s.type)) barSeriesCount++;
        int barSeriesIndex = 0;
        for (ReportSeries s : series) {
            double[] range = s.axis == 1 ? r2 : r1;
            if ("bar".equals(s.type)) {
                appendBarSeries(svg, labels, s, range[0], range[1], left, top, plotW, plotH, barSeriesCount, barSeriesIndex++);
            }
        }
        for (ReportSeries s : series) {
            if (!"bar".equals(s.type)) {
                double[] range = s.axis == 1 ? r2 : r1;
                appendComboLineSeries(svg, labels, s, range[0], range[1], left, top, plotW, plotH);
            }
        }

        appendLegendRows(svg, series, left, height - 28, width - left - right);
        svg.append("<text x='").append(left).append("' y='").append(height - 42).append("' fill='#94a3b8' font-size='11'>").append(shortDate(labels.get(0))).append("</text>");
        svg.append("<text text-anchor='end' x='").append(width - right).append("' y='").append(height - 42).append("' fill='#94a3b8' font-size='11'>").append(shortDate(labels.get(count - 1))).append("</text>");
        svg.append("</svg></div>");
        return svg.toString();
    }

    private double[] rangeForAxis(java.util.List<ReportSeries> series, int axis, Double fixedMin) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (ReportSeries s : series) {
            if (s.axis != axis || s.values == null) continue;
            for (Number n : s.values) {
                if (n == null) continue;
                double v = n.doubleValue();
                if (Double.isNaN(v)) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (min == Double.POSITIVE_INFINITY || max == Double.NEGATIVE_INFINITY) return null;
        if (fixedMin != null) min = fixedMin;
        if (Math.abs(max - min) < 0.0001) {
            max += 1;
            min -= 1;
        }
        double padding = (max - min) * 0.08;
        if (fixedMin == null) min -= padding;
        max += padding;
        return new double[]{min, max};
    }

    private boolean hasAxis(java.util.List<ReportSeries> series, int axis) {
        for (ReportSeries s : series) if (s.axis == axis) return true;
        return false;
    }

    private void appendComboLineSeries(StringBuilder svg, java.util.List<String> labels, ReportSeries s,
                                       double min, double max, int left, int top, int plotW, int plotH) {
        if (s.values == null || labels == null || labels.isEmpty()) return;
        int count = labels.size();
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < Math.min(count, s.values.size()); i++) {
            Number n = s.values.get(i);
            if (n == null) continue;
            double x = left + (count == 1 ? plotW / 2.0 : plotW * i / (double) (count - 1));
            double y = top + (max - n.doubleValue()) / (max - min) * plotH;
            points.append(round(x)).append(',').append(round(y)).append(' ');
            if (!s.dashed) svg.append("<circle cx='").append(round(x)).append("' cy='").append(round(y)).append("' r='2.8' fill='").append(s.color).append("'/>");
        }
        if (points.length() > 0) {
            svg.append("<polyline fill='none' stroke='").append(s.color).append("' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'");
            if (s.dashed) svg.append(" stroke-dasharray='7 5'");
            svg.append(" points='").append(points).append("'/>");
        }
    }

    private void appendBarSeries(StringBuilder svg, java.util.List<String> labels, ReportSeries s,
                                 double min, double max, int left, int top, int plotW, int plotH,
                                 int barSeriesCount, int barSeriesIndex) {
        if (s.values == null || labels == null || labels.isEmpty()) return;
        int count = labels.size();
        double step = count == 1 ? plotW : plotW / (double) count;
        double groupW = Math.min(step * 0.62, 42);
        double barW = groupW / Math.max(barSeriesCount, 1);
        double baseline = top + (max - Math.max(0, min)) / (max - min) * plotH;
        baseline = Math.max(top, Math.min(top + plotH, baseline));
        for (int i = 0; i < Math.min(count, s.values.size()); i++) {
            Number n = s.values.get(i);
            if (n == null) continue;
            double center = left + (count == 1 ? plotW / 2.0 : plotW * i / (double) (count - 1));
            double x = center - groupW / 2.0 + barW * barSeriesIndex + 1;
            double y = top + (max - n.doubleValue()) / (max - min) * plotH;
            double h = Math.abs(baseline - y);
            svg.append("<rect x='").append(round(x)).append("' y='").append(round(Math.min(y, baseline))).append("' width='").append(round(Math.max(barW - 2, 3))).append("' height='").append(round(h)).append("' rx='4' fill='").append(s.color).append("'/>");
        }
    }

    private void appendLegendRows(StringBuilder svg, java.util.List<ReportSeries> series, int x, int y, int maxWidth) {
        int currentX = x;
        int currentY = y;
        for (ReportSeries s : series) {
            if (s.name == null || s.color == null) continue;
            int itemW = Math.max(72, s.name.length() * 14 + 24);
            if (currentX + itemW > x + maxWidth) {
                currentX = x;
                currentY += 16;
            }
            if ("bar".equals(s.type)) {
                svg.append("<rect x='").append(currentX).append("' y='").append(currentY - 9).append("' width='10' height='10' rx='2' fill='").append(s.color).append("'/>");
            } else {
                svg.append("<line x1='").append(currentX).append("' y1='").append(currentY - 4).append("' x2='").append(currentX + 12).append("' y2='").append(currentY - 4).append("' stroke='").append(s.color).append("' stroke-width='2'");
                if (s.dashed) svg.append(" stroke-dasharray='4 3'");
                svg.append("/>");
            }
            svg.append("<text x='").append(currentX + 16).append("' y='").append(currentY).append("' fill='#64748b' font-size='12'>").append(s.name).append("</text>");
            currentX += itemW;
        }
    }
    private String renderLineChart(String title, java.util.List<String> labels,
                                   java.util.List<? extends Number> series1, String name1, String color1,
                                   java.util.List<? extends Number> series2, String name2, String color2,
                                   java.util.List<? extends Number> series3, String name3, String color3,
                                   java.util.List<? extends Number> series4, String name4, String color4) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        java.util.List<java.util.List<? extends Number>> all = new java.util.ArrayList<>();
        if (series1 != null) all.add(series1);
        if (series2 != null) all.add(series2);
        if (series3 != null) all.add(series3);
        if (series4 != null) all.add(series4);
        for (java.util.List<? extends Number> series : all) {
            for (Number n : series) {
                if (n == null) continue;
                double v = n.doubleValue();
                if (Double.isNaN(v)) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (min == Double.POSITIVE_INFINITY || max == Double.NEGATIVE_INFINITY) {
            return "<div class='trend-card'><h3>" + title + "</h3><p class='trend-note'>No data available</p></div>";
        }
        if (Math.abs(max - min) < 0.0001) {
            max += 1;
            min -= 1;
        }
        double padding = (max - min) * 0.08;
        min -= padding;
        max += padding;

        int width = 680;
        int height = 260;
        int left = 46;
        int right = 18;
        int top = 24;
        int bottom = 40;
        int plotW = width - left - right;
        int plotH = height - top - bottom;
        int count = labels != null ? labels.size() : 0;

        StringBuilder svg = new StringBuilder();
        svg.append("<div class='trend-card'><h3>").append(title).append("</h3>");
        svg.append("<svg viewBox='0 0 ").append(width).append(" ").append(height).append("' width='100%' height='260' role='img'>");
        svg.append("<rect x='0' y='0' width='").append(width).append("' height='").append(height).append("' rx='10' fill='#ffffff'/>");
        for (int i = 0; i <= 4; i++) {
            double y = top + plotH * i / 4.0;
            double val = max - (max - min) * i / 4.0;
            svg.append("<line x1='").append(left).append("' y1='").append(round(y)).append("' x2='").append(width - right).append("' y2='").append(round(y)).append("' stroke='#e2e8f0' stroke-width='1'/>");
            svg.append("<text x='8' y='").append(round(y + 4)).append("' fill='#94a3b8' font-size='11'>").append(formatChartValue(val)).append("</text>");
        }
        appendSeries(svg, labels, series1, color1, min, max, left, top, plotW, plotH);
        appendSeries(svg, labels, series2, color2, min, max, left, top, plotW, plotH);
        appendSeries(svg, labels, series3, color3, min, max, left, top, plotW, plotH);
        appendSeries(svg, labels, series4, color4, min, max, left, top, plotW, plotH);
        appendLegend(svg, 52, height - 14, name1, color1, name2, color2, name3, color3, name4, color4);
        if (count > 0) {
            svg.append("<text x='").append(left).append("' y='").append(height - 24).append("' fill='#94a3b8' font-size='11'>").append(shortDate(labels.get(0))).append("</text>");
            svg.append("<text text-anchor='end' x='").append(width - right).append("' y='").append(height - 24).append("' fill='#94a3b8' font-size='11'>").append(shortDate(labels.get(count - 1))).append("</text>");
        }
        svg.append("</svg></div>");
        return svg.toString();
    }

    private void appendSeries(StringBuilder svg, java.util.List<String> labels, java.util.List<? extends Number> series,
                              String color, double min, double max, int left, int top, int plotW, int plotH) {
        if (series == null || color == null || labels == null || labels.isEmpty()) return;
        int count = labels.size();
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < Math.min(count, series.size()); i++) {
            Number n = series.get(i);
            if (n == null) continue;
            double x = left + (count == 1 ? plotW / 2.0 : plotW * i / (double) (count - 1));
            double y = top + (max - n.doubleValue()) / (max - min) * plotH;
            points.append(round(x)).append(',').append(round(y)).append(' ');
            svg.append("<circle cx='").append(round(x)).append("' cy='").append(round(y)).append("' r='2.8' fill='").append(color).append("'/>");
        }
        if (points.length() > 0) {
            svg.append("<polyline fill='none' stroke='").append(color).append("' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round' points='").append(points).append("'/>");
        }
    }

    private void appendLegend(StringBuilder svg, int x, int y,
                              String name1, String color1, String name2, String color2,
                              String name3, String color3, String name4, String color4) {
        int currentX = x;
        currentX = appendLegendItem(svg, currentX, y, name1, color1);
        currentX = appendLegendItem(svg, currentX, y, name2, color2);
        currentX = appendLegendItem(svg, currentX, y, name3, color3);
        appendLegendItem(svg, currentX, y, name4, color4);
    }

    private int appendLegendItem(StringBuilder svg, int x, int y, String name, String color) {
        if (name == null || color == null) return x;
        svg.append("<circle cx='").append(x).append("' cy='").append(y - 4).append("' r='4' fill='").append(color).append("'/>");
        svg.append("<text x='").append(x + 8).append("' y='").append(y).append("' fill='#64748b' font-size='12'>").append(name).append("</text>");
        return x + 82;
    }

    private String renderMonthlyStatsTable(DialysisStatsVO stats) {
        if (stats == null || stats.getMonthlyStats() == null || stats.getMonthlyStats().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='report-card' style='margin-top:14px;'>");
        sb.append("<h2>").append(svgIcon("trend")).append(" Monthly Average interdialytic weight gain / ultrafiltration volume</h2>");
        sb.append("<table>");
        sb.append("<tr><th>Month</th><th>Average interdialytic weight gain(kg)</th><th>Average ultrafiltration volume(kg)</th></tr>");
        for (Map<String, Object> row : stats.getMonthlyStats()) {
            String month = row.get("month") != null ? row.get("month").toString() : "-";
            String avgGain = row.get("avg_weight_gain") != null ? row.get("avg_weight_gain").toString() : "-";
            String avgUf = row.get("avg_uf_amount") != null ? row.get("avg_uf_amount").toString() : "-";
            sb.append("<tr><td>").append(month).append("</td>");
            sb.append("<td>").append(avgGain).append("</td>");
            sb.append("<td>").append(avgUf).append("</td></tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    private String renderDehydrationDistribution(DialysisStatsVO stats) {
        long tooMuch = stats != null && stats.getTooMuchCount() != null ? stats.getTooMuchCount() : 0;
        long insufficient = stats != null && stats.getInsufficientCount() != null ? stats.getInsufficientCount() : 0;
        long match = stats != null && stats.getMatchCount() != null ? stats.getMatchCount() : 0;
        long total = Math.max(tooMuch + insufficient + match, 1);

        String[] labels = {"Excessive ultrafiltration", "Insufficient ultrafiltration", "Ultrafiltration on target"};
        long[] values = {tooMuch, insufficient, match};
        String[] colors = {"#ef4444", "#f59e0b", "#10b981"};

        int width = 680;
        int height = 300;
        double cx = 340;
        double cy = 132;
        double radius = 78;
        double innerRadius = 44;
        double start = -90;

        StringBuilder svg = new StringBuilder();
        svg.append("<div class='trend-card'><h3>Ultrafiltration status distribution</h3>");
        svg.append("<svg viewBox='0 0 ").append(width).append(" ").append(height).append("' width='100%' height='300' role='img'>");
        svg.append("<rect x='0' y='0' width='").append(width).append("' height='").append(height).append("' rx='10' fill='#ffffff'/>");

        for (int i = 0; i < values.length; i++) {
            if (values[i] <= 0) continue;
            double sweep = values[i] * 360.0 / total;
            svg.append(donutSlice(cx, cy, radius, innerRadius, start, start + sweep, colors[i]));
            double mid = start + sweep / 2.0;
            double labelX = cx + Math.cos(Math.toRadians(mid)) * (radius + 28);
            double labelY = cy + Math.sin(Math.toRadians(mid)) * (radius + 28);
            long pct = Math.round(values[i] * 100.0 / total);
            svg.append("<text text-anchor='middle' x='").append(round(labelX)).append("' y='").append(round(labelY)).append("' fill='").append(colors[i]).append("' font-size='12' font-weight='600'>")
                    .append(values[i]).append("times ").append(pct).append("%</text>");
            start += sweep;
        }
        svg.append("<text text-anchor='middle' x='").append(cx).append("' y='").append(cy - 4).append("' fill='#334155' font-size='20' font-weight='700'>").append(total).append("</text>");
        svg.append("<text text-anchor='middle' x='").append(cx).append("' y='").append(cy + 18).append("' fill='#94a3b8' font-size='12'>Total</text>");

        int legendX = 190;
        int legendY = 260;
        for (int i = 0; i < labels.length; i++) {
            int x = legendX + i * 110;
            svg.append("<circle cx='").append(x).append("' cy='").append(legendY - 4).append("' r='5' fill='").append(colors[i]).append("'/>");
            svg.append("<text x='").append(x + 10).append("' y='").append(legendY).append("' fill='#64748b' font-size='12'>").append(labels[i]).append("</text>");
        }
        svg.append("</svg>");
        svg.append("<p class='trend-note'>Ultrafiltration on targettoultrafiltration volume and interdialytic weight gaindifferencevaluenot exceed 0.3kg count. </p></div>");
        return svg.toString();
    }

    private String donutSlice(double cx, double cy, double r, double ir, double startDeg, double endDeg, String color) {
        double largeArc = endDeg - startDeg > 180 ? 1 : 0;
        double sx = cx + Math.cos(Math.toRadians(startDeg)) * r;
        double sy = cy + Math.sin(Math.toRadians(startDeg)) * r;
        double ex = cx + Math.cos(Math.toRadians(endDeg)) * r;
        double ey = cy + Math.sin(Math.toRadians(endDeg)) * r;
        double isx = cx + Math.cos(Math.toRadians(endDeg)) * ir;
        double isy = cy + Math.sin(Math.toRadians(endDeg)) * ir;
        double iex = cx + Math.cos(Math.toRadians(startDeg)) * ir;
        double iey = cy + Math.sin(Math.toRadians(startDeg)) * ir;
        return "<path d='M " + round(sx) + " " + round(sy)
                + " A " + r + " " + r + " 0 " + (long) largeArc + " 1 " + round(ex) + " " + round(ey)
                + " L " + round(isx) + " " + round(isy)
                + " A " + ir + " " + ir + " 0 " + (long) largeArc + " 0 " + round(iex) + " " + round(iey)
                + " Z' fill='" + color + "' stroke='#fff' stroke-width='3'/>";
    }

    private String shortDate(String label) {
        if (label == null) return "";
        return label.length() >= 10 ? label.substring(5, 10) : label;
    }

    private String formatChartValue(double val) {
        if (Math.abs(val) >= 100) return String.valueOf(Math.round(val));
        return String.format(java.util.Locale.US, "%.1f", val);
    }

    private String round(double val) {
        return String.format(java.util.Locale.US, "%.1f", val);
    }
    /** SVG Icon: for ReportsectionAddIcon */
    private String svgIcon(String type) {
        switch (type) {
            case "dialysis":
                return "<svg class='section-icon' viewBox='0 0 24 24' fill='none' stroke='#4f6af6' stroke-width='2'><path d='M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z'/><path d='M12 8v4l3 3'/></svg>";
            case "bp":
                return "<svg class='section-icon' viewBox='0 0 24 24' fill='none' stroke='#4f6af6' stroke-width='2'><path d='M3 12h4l3-9 4 18 3-9h4'/></svg>";
            case "nutrition":
                return "<svg class='section-icon' viewBox='0 0 24 24' fill='none' stroke='#4f6af6' stroke-width='2'><circle cx='12' cy='12' r='10'/><path d='M8 14s1.5 2 4 2 4-2 4-2'/><line x1='9' y1='9' x2='9.01' y2='9'/><line x1='15' y1='9' x2='15.01' y2='9'/></svg>";
            case "clinical":
                return "<svg class='section-icon' viewBox='0 0 24 24' fill='none' stroke='#4f6af6' stroke-width='2'><path d='M22 12h-4l-3 9L9 3l-3 9H2'/></svg>";
            case "monitor":
                return "<svg class='section-icon' viewBox='0 0 24 24' fill='none' stroke='#4f6af6' stroke-width='2'><circle cx='12' cy='12' r='10'/><path d='M12 6v6l4 2'/></svg>";
            case "trend":
                return "<svg class='section-icon' viewBox='0 0 24 24' fill='none' stroke='#4f6af6' stroke-width='2'><path d='M3 17l6-6 4 4 7-9'/><path d='M14 6h6v6'/></svg>";
            default:
                return "";
        }
    }

    /** enterlevelitems: used foron targetrateetc.indicator can viewtransform */
    private String progressBar(Object value, String theme) {
        if (value == null) return "";
        double pct;
        try { pct = Double.parseDouble(value.toString()); } catch (NumberFormatException e) { return ""; }
        if (pct < 0) pct = 0; if (pct > 100) pct = 100;
        String color = "good".equals(theme) ? (pct >= 80 ? "#2e7d32" : pct >= 60 ? "#e65100" : "#c62828") :
                "risk".equals(theme) ? "#e65100" : "#4f46e5";
        return " <span class='progress-bar'><span class='progress-fill' style='width:" + pct + "%;background:" + color + "'></span></span>";
    }

    /** Statusbadge: Blood PressureAbnormal readings */
    private String badgeForBpAbnormal(Object count) {
        if (count == null) return "";
        int c;
        try { c = Integer.parseInt(count.toString()); } catch (NumberFormatException e) { return ""; }
        if (c <= 2) return " <span class='badge badge-good'>Normal</span>";
        if (c <= 5) return " <span class='badge badge-risk'>attention</span>";
        return " <span class='badge badge-bad'>alert</span>";
    }

    /** Statusbadge: weight gain exceeds5% count */
    private String badgeForOver5(Object count) {
        if (count == null) return "";
        int c;
        try { c = Integer.parseInt(count.toString()); } catch (NumberFormatException e) { return ""; }
        if (c <= 1) return " <span class='badge badge-good'>Good</span>";
        if (c <= 3) return " <span class='badge badge-risk'>attention</span>";
        return " <span class='badge badge-bad'>out of range</span>";
    }

    /** Statusbadge: Abnormal readingsthroughuse */
    private String badgeForAbnormal(int count) {
        if (count <= 2) return "";
        if (count <= 5) return " <span class='badge badge-risk'>attention</span>";
        return " <span class='badge badge-bad'>alert</span>";
    }

    /** appetiteintextlabel */
    private String appetiteLabel(String appetite) {
        if (appetite == null) return "-";
        switch (appetite) {
            case "GOOD": return "Good";
            case "NORMAL": return "Fair";
            case "POOR": return "difference";
            default: return appetite;
        }
    }

    /** appetitebadge */
    private String badgeForAppetite(String appetite) {
        if (appetite == null) return "";
        switch (appetite) {
            case "GOOD": return " <span class='badge badge-good'>Good</span>";
            case "NORMAL": return " <span class='badge badge-normal'>Fair</span>";
            case "POOR": return " <span class='badge badge-bad'>difference</span>";
            default: return "";
        }
    }

    /** threemealtext */
    private String mealsText(NutritionDiary nd) {
        StringBuilder sb = new StringBuilder();
        if (Boolean.TRUE.equals(nd.getMealBreakfast())) sb.append("morning ");
        if (Boolean.TRUE.equals(nd.getMealLunch())) sb.append("noon ");
        if (Boolean.TRUE.equals(nd.getMealDinner())) sb.append("evening ");
        if (Boolean.TRUE.equals(nd.getMealSnack())) sb.append("add");
        return sb.length() > 0 ? sb.toString().trim() : "-";
    }

    /** Statusbadge: Blood Pressurevalue (based onSystolic Pressuredetermine)  */
    private String badgeForBpValue(Integer systolicBp) {
        if (systolicBp == null) return "";
        if (systolicBp < 90) return " <span class='badge badge-bad'>lowBlood Pressure</span>";
        if (systolicBp <= 120) return " <span class='badge badge-good'>Normal</span>";
        if (systolicBp <= 139) return " <span class='badge badge-risk'>high</span>";
        return " <span class='badge badge-bad'>highBlood Pressure</span>";
    }

    /** Statusbadge: Blood Glucosevalue (based onmeasurementperioddetermine)  */
    private String badgeForBgValue(BigDecimal bloodGlucose, String measurePeriod) {
        if (bloodGlucose == null) return "";
        double val = bloodGlucose.doubleValue();
        // based onmeasurementperioddetermineNormalrange
        if (measurePeriod != null && measurePeriod.contains("Fasting")) {
            if (val < 3.9) return " <span class='badge badge-bad'>low</span>";
            if (val <= 6.1) return " <span class='badge badge-good'>Normal</span>";
            if (val <= 7.0) return " <span class='badge badge-risk'>high</span>";
            return " <span class='badge badge-bad'>pasthigh</span>";
        } else if (measurePeriod != null && measurePeriod.contains("After Meal")) {
            if (val < 3.9) return " <span class='badge badge-bad'>low</span>";
            if (val <= 7.8) return " <span class='badge badge-good'>Normal</span>";
            if (val <= 11.1) return " <span class='badge badge-risk'>high</span>";
            return " <span class='badge badge-bad'>pasthigh</span>";
        } else {
            // Randommeasurement: throughusedetermine
            if (val < 3.9) return " <span class='badge badge-bad'>low</span>";
            if (val <= 11.1) return " <span class='badge badge-good'>Normal</span>";
            return " <span class='badge badge-bad'>pasthigh</span>";
        }
    }

    private String nvl(Object v) { return v != null ? v.toString() : "-"; }
    private String fmt(Object v) { return v != null ? v.toString() : "-"; }
}
