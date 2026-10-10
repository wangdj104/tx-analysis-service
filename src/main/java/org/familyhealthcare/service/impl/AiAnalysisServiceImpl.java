package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.*;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.AiAnalysisResultVO;
import org.familyhealthcare.vo.DialysisStatsVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import static org.familyhealthcare.util.ExportLocalization.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.time.LocalDate;

/**
 * AI analysisserviceimplement
 */
@Service
public class AiAnalysisServiceImpl implements AiAnalysisService {

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.url}")
    private String apiUrl;

    @Value("${deepseek.api.model:deepseek-chat}")
    private String apiModel;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private DialysisRecordService dialysisRecordService;

    @Autowired
    private DryWeightMonthlyService dryWeightMonthlyService;

    @Autowired
    private AiAnalysisRecordMapper aiAnalysisRecordMapper;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private PatientClinicalService patientClinicalService;

    @Autowired
    private ComplicationRecordService complicationRecordService;

    @Autowired
    private MedicationService medicationService;

    @Autowired private BpSelfMonitorRecordMapper bpSelfMonitorRecordMapper;
    @Autowired private MedicationIntakeMapper medicationIntakeMapper;
    @Autowired private NutritionDiaryMapper nutritionDiaryMapper;
    @Autowired private ComplicationRecordMapper complicationRecordMapper;

    @Override
    public AiAnalysisResultVO analyzeDialysisData(String timeType, String timeValue, Long patientId) {
        if (patientId == null) {
            throw new IllegalStateException("Select a patient");
        }
        DialysisStatsVO stats = dialysisRecordService.getStatistics(timeType, timeValue, patientId);
        DialysisStatsVO chartData = dialysisRecordService.getChartData(timeType, timeValue, patientId);
        List<DryWeightMonthly> dryWeightList = dryWeightMonthlyService.listAllOrderByMonth(patientId);

        String prompt = buildPrompt(stats, chartData, dryWeightList, timeType, timeValue, patientId);
        String rawResult = callDeepSeek(prompt);

        AiAnalysisResultVO vo = new AiAnalysisResultVO();
        vo.setRawText(rawResult);
        vo.setPeriodLabel(formatPeriod(timeType, timeValue));

        // populateindicatorsnapshot
        vo.setTotalCount(stats.getTotalCount() != null ? stats.getTotalCount().intValue() : 0);
        vo.setAvgOnWeight(stats.getAvgOnWeight());
        vo.setAvgOffWeight(stats.getAvgOffWeight());
        vo.setAvgWeightGain(stats.getAvgWeightGain());
        vo.setAvgUfAmount(stats.getAvgUfAmount());
        vo.setDehydrationMatchRate(stats.getDehydrationMatchRate());

        // parseDry Weightadjustment conclusion
        parseConclusion(rawResult, vo);

        return vo;
    }

    @Override
    public String analyzeHealthSnapshot(Long patientId, int rangeDays, Collection<String> analysisItems) {
        Patient patient = dataScopeHelper.requirePatient(patientId);
        int days = Math.max(1, Math.min(rangeDays, 365));
        Set<String> items = analysisItems == null ? Collections.emptySet() : analysisItems.stream()
                .filter(Objects::nonNull).map(String::trim).map(String::toUpperCase).collect(Collectors.toCollection(LinkedHashSet::new));
        if (items.isEmpty()) items.addAll(Arrays.asList("DIALYSIS", "VITALS", "MEDICATION", "NUTRITION", "COMPLICATION"));
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        StringBuilder prompt = new StringBuilder();
        prompt.append(text("Analyze the supplied family-health records. Do not diagnose or independently change prescriptions.\n",
                        "请分析以下家庭健康记录，不作诊断，也不自行更改处方。\n"))
                .append(text("Patient: ", "患者：")).append(patient == null ? text("Unknown", "未知") : patient.getName())
                .append(text("; Review period: last ", "；分析期间：最近 ")).append(days).append(text(" days, from ", " 天，开始于 "))
                .append(from).append(".\n")
                .append(text("Analyze only recorded data. State what is missing and do not invent information.\n\n", "仅分析已有数据，明确说明缺失信息，不得编造。\n\n"));
        if (items.contains("DIALYSIS")) {
            List<DialysisRecord> rows = dialysisRecordService.list(new QueryWrapper<DialysisRecord>().eq("patient_id", patientId).ge("record_date", from).orderByDesc("record_date").last("limit 60"));
            prompt.append("【Dialysis and Weight】\n").append(JSON.toJSONString(rows)).append("\n");
        }
        if (items.contains("VITALS")) {
            List<BpSelfMonitorRecord> rows = bpSelfMonitorRecordMapper.selectList(new QueryWrapper<BpSelfMonitorRecord>().eq("patient_id", patientId).ge("record_date", from).orderByDesc("record_date").orderByDesc("record_time").last("limit 100"));
            prompt.append("【Blood Pressure & Glucose】\n").append(JSON.toJSONString(rows)).append("\n");
        }
        if (items.contains("MEDICATION")) {
            List<Medication> meds = medicationService.listActiveMedications(patientId);
            List<MedicationIntake> intakes = medicationIntakeMapper.selectList(new QueryWrapper<MedicationIntake>().eq("patient_id", patientId).ge("scheduled_at", from.atStartOfDay()).orderByDesc("scheduled_at").last("limit 100"));
            prompt.append("【currentmedication】\n").append(JSON.toJSONString(meds)).append("\n【medication intakerun】\n").append(JSON.toJSONString(intakes)).append("\n");
        }
        if (items.contains("NUTRITION")) {
            List<NutritionDiary> rows = nutritionDiaryMapper.selectList(new QueryWrapper<NutritionDiary>().eq("patient_id", patientId).ge("record_date", from).orderByDesc("record_date").last("limit 60"));
            prompt.append("【Nutrition and fluid intake】\n").append(JSON.toJSONString(rows)).append("\n");
        }
        if (items.contains("COMPLICATION")) {
            List<ComplicationRecord> rows = complicationRecordMapper.selectList(new QueryWrapper<ComplicationRecord>().eq("patient_id", patientId).ge("occurrence_date", from).orderByDesc("occurrence_date").last("limit 60"));
            prompt.append("【complication and discomfort】\n").append(JSON.toJSONString(rows)).append("\n");
        }
        prompt.append(text("\nUse these sections in no more than 800 words: 1. Overall condition; 2. Changes needing attention, ordered by urgency; 3. Family-care tasks; 4. Measurements or follow-up to discuss. Identify urgent warning signs clearly. End by explaining that this summary supports record review and does not replace professional diagnosis.\n",
                "\n请按以下结构作答，总字数不超过 800 字：1. 总体情况；2. 需要关注的变化，按紧急程度排序；3. 家庭照护事项；4. 需要讨论的复测或复诊安排。明确指出紧急警示信号，并在末尾说明本分析仅供记录复核，不能代替专业诊断。\n"));
        return callDeepSeek(prompt.toString(), false);
    }

    @Override
    public boolean saveAnalysis(AiAnalysisRecord record) {
        Long userId = CurrentUserUtil.getCurrentUserId();
        if (record.getPatientId() == null) {
            throw new IllegalStateException("Select a patient");
        }
        dataScopeHelper.requirePatientAccess(record.getPatientId(), null, true);
        record.setUserId(userId);
        return aiAnalysisRecordMapper.insert(record) > 0;
    }

    @Override
    public List<AiAnalysisRecord> listHistory(String timeType, String timeValue, Long patientId) {
        if (patientId == null) {
            return java.util.Collections.emptyList();
        }
        Long userId = CurrentUserUtil.getCurrentUserId();
        QueryWrapper<AiAnalysisRecord> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.eq("patient_id", patientId);
        qw.orderByDesc("created_at");
        if (timeType != null && !timeType.isEmpty()) {
            qw.eq("time_type", timeType);
        }
        if (timeValue != null && !timeValue.isEmpty()) {
            qw.eq("time_value", timeValue);
        }
        return aiAnalysisRecordMapper.selectList(qw);
    }

    @Override
    public boolean deleteAnalysis(Long id) {
        AiAnalysisRecord existing = aiAnalysisRecordMapper.selectById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return aiAnalysisRecordMapper.deleteById(id) > 0;
    }

    /**
     * parseAIBacktextin Dry Weightadjustment conclusion
     */
    private void parseConclusion(String text, AiAnalysisResultVO vo) {
        if (text == null) return;

        // trymatchJSONblock
        Pattern jsonPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
        Matcher jsonMatcher = jsonPattern.matcher(text);
        if (jsonMatcher.find()) {
            try {
                JSONObject json = JSON.parseObject(jsonMatcher.group(1));
                vo.setDwAdjustNeeded(normalizeAdjustment(analysisValue(json, "YesNoadjustment needed", "是否需要调整")));
                vo.setDwAdjustAmount(parseDecimal(analysisValue(json, "recommendationadjustment amount", "建议调整量")));
                vo.setDwTargetWeight(parseDecimal(analysisValue(json, "recommendationtargetDry Weight", "建议目标干体重")));
                vo.setDwAdjustReason(analysisValue(json, "adjustment rationale", "调整理由"));
                // multipledimensionassessment
                vo.setWeightControlEval(analysisValue(json, "Weightcontrol assessment", "体重控制评估"));
                vo.setDehydrationEval(analysisValue(json, "fluid removal assessment", "液体清除评估"));
                vo.setBpControlEval(analysisValue(json, "Blood Pressurecontrol assessment", "血压控制评估"));
                vo.setMainRisk(analysisValue(json, "primaryRiskNotice", "主要风险提示"));
                vo.setDietAdvice(analysisValue(json, "dietrecommendation", "饮食建议"));
                vo.setFluidAdvice(analysisValue(json, "fluid intake controlrecommendation", "液体摄入控制建议"));
                vo.setExerciseAdvice(analysisValue(json, "exerciserecommendation", "运动建议"));
                vo.setMedicationAdvice(analysisValue(json, "medicationrecommendation", "用药建议"));
                vo.setFollowUpAdvice(analysisValue(json, "follow-upfollow-up examinationrecommendation", "复诊复查建议"));
                // enhancefield
                vo.setComplicationRiskAssessment(analysisValue(json, "complicationRiskassessment", "并发症风险评估"));
                vo.setMedicationAdviceDetails(analysisValue(json, "medicationDetailedrecommendation", "详细用药建议"));
                vo.setVitalSignTrendSummary(analysisValue(json, "Blood Pressuretrend summary", "血压趋势摘要"));
                return;
            } catch (Exception ignored) {
            }
        }

        // exitreturnto positivethenmatchtextrow
        Pattern needPattern = Pattern.compile("(?:YesNoadjustment needed|Adjustment needed|是否需要调整)[:：]\\s*(.+?)(?:\\n|$)");
        Pattern amountPattern = Pattern.compile("(?:recommendationadjustment amount|Suggested adjustment|建议调整量)[:：]\\s*([+\\-]?\\d+\\.?\\d*)\\s*kg");
        Pattern targetPattern = Pattern.compile("(?:recommendationtargetDry Weight|Suggested target dry weight|建议目标干体重)[:：]\\s*(\\d+\\.?\\d*)\\s*kg");
        Pattern reasonPattern = Pattern.compile("(?:adjustment rationale|Rationale|调整理由)[:：]\\s*(.+?)(?:\\n|$)");

        Matcher m1 = needPattern.matcher(text);
        if (m1.find()) vo.setDwAdjustNeeded(normalizeAdjustment(m1.group(1).trim()));

        Matcher m2 = amountPattern.matcher(text);
        if (m2.find()) vo.setDwAdjustAmount(new BigDecimal(m2.group(1)));

        Matcher m3 = targetPattern.matcher(text);
        if (m3.find()) vo.setDwTargetWeight(new BigDecimal(m3.group(1)));

        Matcher m4 = reasonPattern.matcher(text);
        if (m4.find()) vo.setDwAdjustReason(m4.group(1).trim());
    }

    private String analysisValue(JSONObject json, String englishKey, String chineseKey) {
        String value = json.getString(englishKey);
        return value != null ? value : json.getString(chineseKey);
    }

    private String normalizeAdjustment(String value) {
        if (value == null) return null;
        if ("Yes".equalsIgnoreCase(value.trim()) || "是".equals(value.trim())) return "Yes";
        if ("No".equalsIgnoreCase(value.trim()) || "否".equals(value.trim())) return "No";
        return value;
    }

    private BigDecimal parseDecimal(String val) {
        if (val == null || val.trim().isEmpty()) return null;
        try {
            String clean = val.replace("kg", "").replace("+", "").trim();
            return new BigDecimal(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildPrompt(DialysisStatsVO stats, DialysisStatsVO chartData,
                               List<DryWeightMonthly> dryWeightList,
                               String timeType, String timeValue, Long patientId) {
        StringBuilder sb = new StringBuilder();
        String period = formatPeriod(timeType, timeValue);

        // getenhancedata
        PatientClinical clinical = patientClinicalService.getByPatientId(patientId);
        List<ComplicationRecord> recentComplications = complicationRecordService.listByPatient(patientId);
        List<Medication> activeMedications = medicationService.listActiveMedications(patientId);

        sb.append("youYesoneprofessional kidneyinternal medicineDialysistreatmentClinician, Please based ontodown Patient Dialysisdataprovideprofessional analysis and recommendation. \n\n");
        sb.append("【analysisWeek】").append(period).append("\n\n");

        sb.append("【statisticsdata】\n");
        sb.append("- recordtotal: ").append(stats.getTotalCount()).append(" times\n");
        sb.append("- Average pre-dialysis weight: ").append(fmt(stats.getAvgOnWeight())).append(" kg\n");
        sb.append("- Average post-dialysis weight: ").append(fmt(stats.getAvgOffWeight())).append(" kg\n");
        sb.append("- Average interdialytic weight gain: ").append(fmt(stats.getAvgWeightGain())).append(" kg\n");
        sb.append("- Average ultrafiltration volume: ").append(fmt(stats.getAvgUfAmount())).append(" kg\n");
        sb.append("- averageAverage daily weight gain: ").append(fmt(stats.getAvgDailyWeightGain())).append(" kg/days\n");
        sb.append("- Ultrafiltration target rate: ").append(fmt(stats.getDehydrationMatchRate())).append("%\n");
        sb.append("- Excessive ultrafiltration: ").append(nvl(stats.getTooMuchCount())).append(" times\n");
        sb.append("- Insufficient ultrafiltration: ").append(nvl(stats.getInsufficientCount())).append(" times\n");
        sb.append("- Ultrafiltration on target: ").append(nvl(stats.getMatchCount())).append(" times\n");
        sb.append("- Weight gain within target(3%-5%Dry Weight): ").append(nvl(stats.getIdealGainCount())).append(" times\n");
        sb.append("- weight gainbelow3%Dry Weight: ").append(nvl(stats.getUnder3pctCount())).append(" times\n");
        sb.append("- weight gain exceedsDry Weight5%: ").append(nvl(stats.getOver5pctCount())).append(" times\n");
        sb.append("- Average systolic pressure: ").append(fmt(stats.getAvgSystolicBp())).append(" mmHg\n");
        sb.append("- Average diastolic pressure: ").append(fmt(stats.getAvgDiastolicBp())).append(" mmHg\n");
        sb.append("- Blood PressureAbnormal: ").append(nvl(stats.getBpAbnormalCount())).append(" times\n");
        sb.append("- systolicAbnormal: ").append(nvl(stats.getBpSysAbnormalCount())).append(" times\n");
        sb.append("- diastolicAbnormal: ").append(nvl(stats.getBpDiaAbnormalCount())).append(" times\n");

        appendDryWeightSection(sb, dryWeightList, timeType, timeValue);
        appendOffVsDrySection(sb, chartData);

        // enhancedata: Patientclinicalinformation
        if (clinical != null) {
            sb.append("\n【Patientclinicalinformation】\n");
            sb.append("- Dialysistype: ").append(clinical.getDialysisType() != null ? clinical.getDialysisType() : "not entry").append("\n");
            sb.append("- startDialysis Date: ").append(clinical.getDialysisStartDate() != null ? clinical.getDialysisStartDate().toString() : "not entry").append("\n");
            sb.append("- vascular access: ").append(clinical.getVascularAccess() != null ? clinical.getVascularAccess() : "not entry").append("\n");
            sb.append("- primary diagnosis: ").append(clinical.getPrimaryDiagnosis() != null ? clinical.getPrimaryDiagnosis() : "not entry").append("\n");
            sb.append("- Medicationallergy: ").append(clinical.getAllergyDrugs() != null ? clinical.getAllergyDrugs() : "None").append("\n");
            if (clinical.getTargetDryWeight() != null) sb.append("- targetDry Weight: ").append(fmt(clinical.getTargetDryWeight())).append(" kg\n");
            if (clinical.getFluidLimitMl() != null) sb.append("- Dayfluidup limit: ").append(clinical.getFluidLimitMl()).append(" ml\n");
        }

        // enhancedata: complicationhistory
        if (recentComplications != null && !recentComplications.isEmpty()) {
            sb.append("\n【complicationhistory】\n");
            int limit = Math.min(recentComplications.size(), 5);
            for (int i = 0; i < limit; i++) {
                ComplicationRecord c = recentComplications.get(i);
                sb.append("- ").append(c.getOccurrenceDate() != null ? c.getOccurrenceDate().toString() : "")
                        .append(" ").append(c.getComplicationType() != null ? c.getComplicationType() : "")
                        .append("(").append(c.getSeverity() != null ? c.getSeverity() : "").append(")")
                        .append(": ").append(c.getDescription() != null ? c.getDescription() : "").append("\n");
            }
        }

        // enhancedata: currentmedication regimen
        if (activeMedications != null && !activeMedications.isEmpty()) {
            sb.append("\n【currentmedication regimen】\n");
            for (Medication med : activeMedications) {
                sb.append("- ").append(med.getDrugName() != null ? med.getDrugName() : "");
                if (med.getGenericName() != null) sb.append("(").append(med.getGenericName()).append(")");
                if (med.getDefaultDosage() != null) sb.append(" ").append(med.getDefaultDosage());
                if (med.getDosageForm() != null) sb.append(" [").append(med.getDosageForm()).append("]");
                sb.append("\n");
            }
        }

        if (stats.getMonthlyStats() != null && !stats.getMonthlyStats().isEmpty()) {
            sb.append("\n【Monthly Dialysisdata】\n");
            for (Map<String, Object> m : stats.getMonthlyStats()) {
                sb.append("- ").append(m.get("month"))
                        .append(": averageweight gain ").append(fmt(m.get("avg_weight_gain")))
                        .append(" kg, averageultrafiltration ").append(fmt(m.get("avg_uf_amount"))).append(" kg\n");
            }
        }

        BigDecimal refDry = resolveReferenceDryWeight(dryWeightList, timeType, timeValue);
        sb.append("\n【systemDry Weightmeasurecalculatereference (provideyouvalidateaccurateconclusion, do notoriginalstylecarecopy) 】\n");
        sb.append(buildDryWeightCalcHint(stats, refDry));

        sb.append(analysisOutputContract());

        return sb.toString();
    }

    private void appendDryWeightSection(StringBuilder sb, List<DryWeightMonthly> dryWeightList,
                                        String timeType, String timeValue) {
        List<DryWeightMonthly> inPeriod = filterDryWeightsInPeriod(dryWeightList, timeType, timeValue);
        sb.append("\n【WeekwithinDry WeightMonthly setset】\n");
        if (inPeriod.isEmpty()) {
            sb.append("-  (this Weeknot entryDry Weight, Please combineAverage post-dialysis weight and weight gainconditioninference) \n");
            return;
        }
        for (DryWeightMonthly d : inPeriod) {
            sb.append("- ").append(d.getYearMonth())
                    .append(": ").append(fmt(d.getDryWeight())).append(" kg\n");
        }
    }

    private void appendOffVsDrySection(StringBuilder sb, DialysisStatsVO chartData) {
        if (chartData.getOffWeightList() == null || chartData.getDryWeightList() == null) {
            return;
        }
        List<BigDecimal> diffs = new ArrayList<>();
        int size = Math.min(chartData.getOffWeightList().size(), chartData.getDryWeightList().size());
        for (int i = 0; i < size; i++) {
            BigDecimal off = chartData.getOffWeightList().get(i);
            BigDecimal dry = chartData.getDryWeightList().get(i);
            if (off != null && dry != null) {
                diffs.add(off.subtract(dry).setScale(2, RoundingMode.HALF_UP));
            }
        }
        if (diffs.isEmpty()) {
            return;
        }
        BigDecimal sum = diffs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgDiff = sum.divide(new BigDecimal(diffs.size()), 2, RoundingMode.HALF_UP);
        sb.append("\n【Post-dialysis Weight and Dry Weightdifferencevalue (post-dialysis-Dry Weight, positivevalue=post-dialysisslightlyheavy) 】\n");
        sb.append("- Weekaveragedifferencevalue: ").append(avgDiff).append(" kg\n");
        sb.append("- singletimesdifferencevaluerange: ")
                .append(diffs.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO))
                .append(" ~ ")
                .append(diffs.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO))
                .append(" kg\n");
    }

    private String buildDryWeightCalcHint(DialysisStatsVO stats, BigDecimal refDry) {
        if (refDry == null) {
            return "- missingDry WeightMonthly record, Please based onAverage post-dialysis weight and fluid removal/weight gaintrendprovideadjustrecommendation. \n";
        }
        BigDecimal avgOff = stats.getAvgOffWeight();
        if (avgOff == null) {
            return "- referenceDry Weight " + fmt(refDry) + " kg, missinghas validPost-dialysis Weightaveragevalue. \n";
        }
        BigDecimal gap = avgOff.subtract(refDry).setScale(2, RoundingMode.HALF_UP);
        long insufficient = nvl(stats.getInsufficientCount());
        long tooMuch = nvl(stats.getTooMuchCount());
        long total = nvl(stats.getTotalCount());

        StringBuilder hint = new StringBuilder();
        hint.append("- currentreferenceDry Weight: ").append(fmt(refDry)).append(" kg\n");
        hint.append("- WeekAverage post-dialysis weight: ").append(fmt(avgOff)).append(" kg\n");
        hint.append("- averagepost-dialysis and Dry Weightdifferencevalue: ").append(gap).append(" kg\n");

        BigDecimal suggestedDelta = BigDecimal.ZERO;
        if (total > 0) {
            if (insufficient > tooMuch && gap.compareTo(new BigDecimal("0.3")) > 0) {
                suggestedDelta = gap.min(new BigDecimal("1.0")).setScale(1, RoundingMode.HALF_UP);
                hint.append("- estimated trend: up adjustDry Weightabout +").append(suggestedDelta).append(" kg\n");
            } else if (tooMuch > insufficient && gap.compareTo(new BigDecimal("-0.3")) < 0) {
                suggestedDelta = gap.max(new BigDecimal("-1.0")).setScale(1, RoundingMode.HALF_UP);
                hint.append("- estimated trend: down adjustDry Weightabout ").append(suggestedDelta).append(" kg\n");
            } else {
                hint.append("- estimated trend: temporarilymaintaincurrentDry Weight (0 kg) \n");
            }
        }
        BigDecimal target = refDry.add(suggestedDelta).setScale(2, RoundingMode.HALF_UP);
        hint.append("- measurecalculatetargetDry Weightabout: ").append(fmt(target)).append(" kg\n");
        return hint.toString();
    }

    private BigDecimal resolveReferenceDryWeight(List<DryWeightMonthly> dryWeightList,
                                                 String timeType, String timeValue) {
        List<DryWeightMonthly> inPeriod = filterDryWeightsInPeriod(dryWeightList, timeType, timeValue);
        if (!inPeriod.isEmpty()) {
            return inPeriod.get(0).getDryWeight();
        }
        if (!dryWeightList.isEmpty()) {
            return dryWeightList.get(0).getDryWeight();
        }
        return null;
    }

    private List<DryWeightMonthly> filterDryWeightsInPeriod(List<DryWeightMonthly> all,
                                                            String timeType, String timeValue) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        if (timeValue == null || timeValue.isEmpty()) {
            return all.stream()
                    .sorted(Comparator.comparing(DryWeightMonthly::getYearMonth).reversed())
                    .collect(Collectors.toList());
        }
        String type = timeType != null ? timeType.toLowerCase() : "";
        return all.stream()
                .filter(d -> matchesPeriod(d.getYearMonth(), type, timeValue))
                .sorted(Comparator.comparing(DryWeightMonthly::getYearMonth).reversed())
                .collect(Collectors.toList());
    }

    private boolean matchesPeriod(String yearMonth, String timeType, String timeValue) {
        if (yearMonth == null) {
            return false;
        }
        switch (timeType) {
            case "year":
                return yearMonth.startsWith(timeValue);
            case "week":
                return true;
            case "month":
            default:
                return yearMonth.equals(timeValue);
        }
    }

    private String formatPeriod(String timeType, String timeValue) {
        if (timeValue == null || timeValue.isEmpty()) return text("All recorded data", "全部历史记录");
        switch (timeType == null ? "" : timeType.toLowerCase(Locale.ROOT)) {
            case "year": return text("Year ", "年度：") + timeValue;
            case "week": return text("Week ", "周：") + timeValue;
            default: return text("Month ", "月份：") + timeValue;
        }
    }

    private long nvl(Long val) {
        return val != null ? val : 0L;
    }

    private String fmt(Object val) {
        if (val == null) {
            return "0.00";
        }
        if (val instanceof BigDecimal) {
            return ((BigDecimal) val).setScale(2, RoundingMode.HALF_UP).toString();
        }
        return val.toString();
    }

    private String analysisOutputContract() {
        StringBuilder contract = new StringBuilder(text(
                "\nWrite a concise analysis of weight, fluid removal, blood pressure, risks, diet and follow-up. End with the heading 【Dry weight adjustment conclusion】 and four labeled lines: Adjustment needed, Suggested adjustment, Suggested target dry weight, Rationale. Treat any treatment adjustment as a suggestion requiring clinician review.\n",
                "\n请简洁分析体重、液体清除、血压、风险、饮食与复诊安排。末尾使用标题【干体重调整结论】，包含四行：是否需要调整、建议调整量、建议目标干体重、调整理由。所有治疗调整建议均须医生复核。\n"));
        contract.append("Use a final ```json block with these exact compatibility keys; localize narrative values, keep Yes/No as protocol values, and use null when a value cannot be determined. Never invent missing measurements or a treatment recommendation.\n");
        contract.append("{\"YesNoadjustment needed\":null,\"recommendationadjustment amount\":null,\"recommendationtargetDry Weight\":null,\"adjustment rationale\":null,\"Weightcontrol assessment\":null,\"fluid removal assessment\":null,\"Blood Pressurecontrol assessment\":null,\"primaryRiskNotice\":null,\"dietrecommendation\":null,\"fluid intake controlrecommendation\":null,\"exerciserecommendation\":null,\"medicationrecommendation\":null,\"follow-upfollow-up examinationrecommendation\":null,\"complicationRiskassessment\":null,\"medicationDetailedrecommendation\":null,\"Blood Pressuretrend summary\":null}\n");
        return contract.toString();
    }

    private String analysisSystemPrompt(boolean structured) {
        String instruction = "You are a family-health record analysis assistant. Use only the supplied records, identify uncertainty, and do not treat a summary as a diagnosis or an authorized prescription change. Preserve original names, quoted clinical text, units and required medical acronyms. "
                + text("Write all narrative headings, explanations and JSON text values in English. ", "所有面向读者的标题、说明及 JSON 文本值必须使用简体中文。 ");
        return instruction + (structured ? analysisOutputContract() : text(
                "Follow the requested general-health sections. Do not add a dialysis-only conclusion or a JSON block unless requested.",
                "按请求的家庭健康分析结构作答；不要额外添加仅适用于透析的结论或 JSON 数据块。"));
    }

    private String callDeepSeek(String prompt) {
        return callDeepSeek(prompt, true);
    }

    private String callDeepSeek(String prompt, boolean structured) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", analysisSystemPrompt(structured));

            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(systemMsg);
            messages.add(userMsg);

            Map<String, Object> body = new HashMap<>();
            body.put("model", apiModel);
            body.put("messages", messages);
            body.put("temperature", 0.4);
            body.put("max_tokens", 2048);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            String url = apiUrl.replace("/responses", "/chat/completions");
            if (!url.endsWith("/chat/completions")) {
                url = url + "/chat/completions";
            }

            String response = restTemplate.postForObject(url, entity, String.class);
            JSONObject json = JSON.parseObject(response);
            if (json.containsKey("choices") && !json.getJSONArray("choices").isEmpty()) {
                String content = json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
                return structured ? ensureDryWeightConclusion(content) : content;
            }
            return text("AI analysis did not return a valid result. Please try again.", "AI 分析未返回有效结果，请重试。");
        } catch (Exception e) {
            return text("AI analysis could not be completed. Check the service configuration and try again.", "AI 分析调用失败，请检查服务配置后重试。");
        }
    }

    /** Never turn missing structured output into a clinical recommendation. */
    private String ensureDryWeightConclusion(String content) {
        if (content == null) return "";
        if (content.contains("【Dry weight adjustment conclusion】") || content.contains("【干体重调整结论】")
                || content.contains("【Dry Weightadjustment conclusion】")) return content;
        return content + text(
                "\n\n【Dry weight adjustment conclusion】\nA structured conclusion is unavailable; clinician review is required. No adjustment amount or target weight has been established.\n",
                "\n\n【干体重调整结论】\nAI 未返回结构化结论，需要人工复核。尚未确定调整量或目标干体重。\n");
    }
}
