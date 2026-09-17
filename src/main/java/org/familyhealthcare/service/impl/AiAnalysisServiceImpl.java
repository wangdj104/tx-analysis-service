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
        prompt.append("youYesfamilyHealth Managementassistant. Please based ontodown realrecord, usestandardthroughfamilycanviewunderstand intextanalysis, not asdiagnosis, not selfrowchangeplaceside. \n")
                .append("Patient: ").append(patient == null ? "Unknown" : patient.getName()).append("; Examinationrange: most recent ").append(days).append("days (from ").append(from).append("start) . \n")
                .append("onlyanalysisalready raiseprovide data; missingdatatimeclearinstructions, not needfabricated. \n\n");
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
        prompt.append("\nPlease by todown structureoutput, total characterscountcontrolin 800charactertowithin: \n")
                .append("1. totalbodycondition\n2. needneedattention change (by UrgentlevelOrder) \n3. Todaycanrun familymanagementitem\n4. recommendationremeasure or follow-up visit itemitem and Time\n")
                .append("ifappearclearshowdangerousmessagenumber, Please singleindependentwrite“needneedas soon as possibleprocess”. end noteclear: this analysisonlyused forfamilyrecordreference, cannotreplaceCliniciandiagnosis. \n");
        return callDeepSeek(prompt.toString());
    }

    @Override
    public boolean saveAnalysis(AiAnalysisRecord record) {
        Long userId = CurrentUserUtil.getCurrentUserId();
        if (record.getPatientId() == null) {
            throw new IllegalStateException("Select a patient");
        }
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
                vo.setDwAdjustNeeded(json.getString("YesNoadjustment needed"));
                vo.setDwAdjustAmount(parseDecimal(json.getString("recommendationadjustment amount")));
                vo.setDwTargetWeight(parseDecimal(json.getString("recommendationtargetDry Weight")));
                vo.setDwAdjustReason(json.getString("adjustment rationale"));
                // multipledimensionassessment
                vo.setWeightControlEval(json.getString("Weightcontrol assessment"));
                vo.setDehydrationEval(json.getString("fluid removal assessment"));
                vo.setBpControlEval(json.getString("Blood Pressurecontrol assessment"));
                vo.setMainRisk(json.getString("primaryRiskNotice"));
                vo.setDietAdvice(json.getString("dietrecommendation"));
                vo.setFluidAdvice(json.getString("fluid intake controlrecommendation"));
                vo.setExerciseAdvice(json.getString("exerciserecommendation"));
                vo.setMedicationAdvice(json.getString("medicationrecommendation"));
                vo.setFollowUpAdvice(json.getString("follow-upfollow-up examinationrecommendation"));
                // enhancefield
                vo.setComplicationRiskAssessment(json.getString("complicationRiskassessment"));
                vo.setMedicationAdviceDetails(json.getString("medicationDetailedrecommendation"));
                vo.setVitalSignTrendSummary(json.getString("Blood Pressuretrend summary"));
                return;
            } catch (Exception ignored) {
            }
        }

        // exitreturnto positivethenmatchtextrow
        Pattern needPattern = Pattern.compile("YesNoadjustment needed[: :]\\s*(.+?)(?:\\n|$)");
        Pattern amountPattern = Pattern.compile("recommendationadjustment amount[: :]\\s*([+\\-]?\\d+\\.?\\d*)\\s*kg");
        Pattern targetPattern = Pattern.compile("recommendationtargetDry Weight[: :]\\s*(\\d+\\.?\\d*)\\s*kg");
        Pattern reasonPattern = Pattern.compile("adjustment rationale[: :]\\s*(.+?)(?:\\n|$)");

        Matcher m1 = needPattern.matcher(text);
        if (m1.find()) vo.setDwAdjustNeeded(m1.group(1).trim());

        Matcher m2 = amountPattern.matcher(text);
        if (m2.find()) vo.setDwAdjustAmount(new BigDecimal(m2.group(1)));

        Matcher m3 = targetPattern.matcher(text);
        if (m3.find()) vo.setDwTargetWeight(new BigDecimal(m3.group(1)));

        Matcher m4 = reasonPattern.matcher(text);
        if (m4.find()) vo.setDwAdjustReason(m4.group(1).trim());
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

        sb.append("\nPlease by todown structureuseintextanswer (positivetexttotal characterscountrecommendation 500 charactertowithin, itemsmanageclear) : \n");
        sb.append("1. Weightcontrolconditionanalysis\n");
        sb.append("2. fluid removaleffect assessment\n");
        sb.append("3. Blood Pressurecontrolcondition\n");
        sb.append("4. storein  questionandRiskNotice\n");
        sb.append("5. specific diet/fluid intake/treatmentrecommendation\n");
        sb.append("\n");
        sb.append("======== strongsystemoutput (mustplacein alltextmost after , title and formatcannot change, cannot omit)  ========\n");
        sb.append("【Dry Weightadjustment conclusion】\n");
        sb.append("YesNoadjustment needed: Yes / No\n");
        sb.append("recommendationadjustment amount: +X.XX kg  or  -X.XX kg  or  0 kg (maintainnot change) \n");
        sb.append("recommendationtargetDry Weight: XX.XX kg (in 「YesNoadjustment needed」for 「No」timefillcurrentreferenceDry Weight) \n");
        sb.append("adjustment rationale: onesentencemessageinstructionsbasis (combineAverage post-dialysis weight, interdialytic weight gain, Ultrafiltration target rateetc.) \n");
        sb.append("\nDry Weightadjustdeterminesetreference: \n");
        sb.append("- Average post-dialysis weightdurationhighinreferenceDry Weight, andInsufficient ultrafiltrationexcessive → throughoftenneedup adjustDry Weight; \n");
        sb.append("- Excessive ultrafiltration, Post-dialysis WeightclearshowbelowDry Weight → throughoftenneeddown adjustDry Weight; \n");
        sb.append("- datavariationlarge or recordnot enoughtime, can recommendationmaintainandaddstrongmonitoring, butmustin 「YesNoadjustment needed」inclearwrite「No」 or 「Yes」. \n");
        sb.append("- 「recommendationadjustment amount」mustprovidespecificnumbers (preciseto  0.1 kg) , prohibitonlywrite「fitwhen adjust」「pendingset」. \n");
        sb.append("\n======== structureddata (mustplacein most end, use ```json replacecodeblockwrap) ========\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"YesNoadjustment needed\": \"Yes/No\",\n");
        sb.append("  \"recommendationadjustment amount\": \"+0.5 kg  or  -0.3 kg  or  0 kg\",\n");
        sb.append("  \"recommendationtargetDry Weight\": \"60.50 kg\",\n");
        sb.append("  \"adjustment rationale\": \"onesentencemessageinstructionsbasis\",\n");
        sb.append("  \"Weightcontrol assessment\": \"excellent/Good/Fair/difference\",\n");
        sb.append("  \"fluid removal assessment\": \"excellent/Good/Fair/difference\",\n");
        sb.append("  \"Blood Pressurecontrol assessment\": \"excellent/Good/Fair/difference\",\n");
        sb.append("  \"primaryRiskNotice\": \"summary1-2most heavyneed Riskpoint\",\n");
        sb.append("  \"dietrecommendation\": \"specific dietadjustrecommendation (for example lowsalt, lowpotassium, lowphosphorusetc.) \",\n");
        sb.append("  \"fluid intake controlrecommendation\": \"specific Fluid Intakecontrolrecommendation\",\n");
        sb.append("  \"exerciserecommendation\": \"fitcombine exercisetype and stronglevelrecommendation\",\n");
        sb.append("  \"medicationrecommendation\": \"YesNoadjustment neededmedication (for example antihypertensive, erythropoietinetc.) ,  or write\"follow the prescription\"\",\n");
        sb.append("  \"follow-upfollow-up examinationrecommendation\": \"recommendationfollow-up examination indicator and Time\",\n");
        sb.append("  \"complicationRiskassessment\": \"based oncomplicationhistory and Dialysisdata overallRiskassessment\",\n");
        sb.append("  \"medicationDetailedrecommendation\": \"based oncurrentmedication regimen adjustrecommendation\",\n");
        sb.append("  \"Blood Pressuretrend summary\": \"Blood Pressurechangetrend simpleneedsummary\"\n");
        sb.append("}\n");
        sb.append("```\n");

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
        if (timeValue == null || timeValue.isEmpty()) {
            return "Allhistorydata";
        }
        switch (timeType != null ? timeType.toLowerCase() : "") {
            case "year":
                return timeValue + "Year";
            case "week":
                return timeValue + "in week";
            case "month":
            default:
                return timeValue.replace("-", "Year") + "Month";
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

    private String callDeepSeek(String prompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                    "youYeskidneyinternal medicineDialysis Managementassistant. answermustincludeandonlyincludeonetimestextendpanelblock「【Dry Weightadjustment conclusion】」, "
                            + "andstrictgridincludefourrow: YesNoadjustment needed, recommendationadjustment amount, recommendationtargetDry Weight, adjustment rationale. "
                            + "recommendationadjustment amountmustfor bringsymbol specifickgcount (for example  +0.5 kg, -0.3 kg, 0 kg) . "
                            + "most after mustoutputone ```json replacecodeblock, includetodown field: "
                            + "YesNoadjustment needed, recommendationadjustment amount, recommendationtargetDry Weight, adjustment rationale, "
                            + "Weightcontrol assessment, fluid removal assessment, Blood Pressurecontrol assessment, primaryRiskNotice, "
                            + "dietrecommendation, fluid intake controlrecommendation, exerciserecommendation, medicationrecommendation, follow-upfollow-up examinationrecommendation, "
                            + "complicationRiskassessment, medicationDetailedrecommendation, Blood Pressuretrend summary. ");

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
                return ensureDryWeightConclusion(content);
            }
            return "AI analysisfailed, not Backhas validresult";
        } catch (Exception e) {
            return "AI analysiscallfailed: " + e.getMessage();
        }
    }

    /**
     * ifmodelnot outputfixedsetpanelblock, trackaddNotice
     */
    private String ensureDryWeightConclusion(String content) {
        if (content == null) {
            return "";
        }
        if (content.contains("【Dry Weightadjustment conclusion】")) {
            return content;
        }
        return content + "\n\n【Dry Weightadjustment conclusion】\n"
                + "YesNoadjustment needed: needcombineclinicalreassess\n"
                + "recommendationadjustment amount: 0 kg (maintainnot change) \n"
                + "recommendationtargetDry Weight: Please referenceDry Weight Managementpagelatestsetset\n"
                + "adjustment rationale: AI not Backstructuredconclusion, Please againanalysis or personworkassessment. \n"
                + "\n```json\n"
                + "{\n"
                + "  \"YesNoadjustment needed\": \"No\",\n"
                + "  \"recommendationadjustment amount\": \"0 kg\",\n"
                + "  \"recommendationtargetDry Weight\": \"Please referenceDry Weight Managementpagelatestsetset\",\n"
                + "  \"adjustment rationale\": \"AI not Backstructuredconclusion, Please againanalysis or personworkassessment\"\n"
                + "}\n"
                + "```\n";
    }
}
