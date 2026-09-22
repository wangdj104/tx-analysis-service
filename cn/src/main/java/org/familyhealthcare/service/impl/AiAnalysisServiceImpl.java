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
        prompt.append("你是家庭健康管理助手。请根据以下真实记录，使用家属容易理解的规范中文进行分析；不要作出诊断，也不要自行更改处方。\n")
                .append("患者：").append(patient == null ? "未知" : patient.getName()).append("；检查范围：最近 ").append(days).append(" 天（自 ").append(from).append(" 起）。\n")
                .append("只分析已提供的数据；数据缺失时请明确说明，不得编造。\n\n");
        if (items.contains("DIALYSIS")) {
            List<DialysisRecord> rows = dialysisRecordService.list(new QueryWrapper<DialysisRecord>().eq("patient_id", patientId).ge("record_date", from).orderByDesc("record_date").last("limit 60"));
            prompt.append("【透析与体重】\n").append(JSON.toJSONString(rows)).append("\n");
        }
        if (items.contains("VITALS")) {
            List<BpSelfMonitorRecord> rows = bpSelfMonitorRecordMapper.selectList(new QueryWrapper<BpSelfMonitorRecord>().eq("patient_id", patientId).ge("record_date", from).orderByDesc("record_date").orderByDesc("record_time").last("limit 100"));
            prompt.append("【血压与血糖】\n").append(JSON.toJSONString(rows)).append("\n");
        }
        if (items.contains("MEDICATION")) {
            List<Medication> meds = medicationService.listActiveMedications(patientId);
            List<MedicationIntake> intakes = medicationIntakeMapper.selectList(new QueryWrapper<MedicationIntake>().eq("patient_id", patientId).ge("scheduled_at", from.atStartOfDay()).orderByDesc("scheduled_at").last("limit 100"));
            prompt.append("【当前用药】\n").append(JSON.toJSONString(meds)).append("\n【用药执行情况】\n").append(JSON.toJSONString(intakes)).append("\n");
        }
        if (items.contains("NUTRITION")) {
            List<NutritionDiary> rows = nutritionDiaryMapper.selectList(new QueryWrapper<NutritionDiary>().eq("patient_id", patientId).ge("record_date", from).orderByDesc("record_date").last("limit 60"));
            prompt.append("【营养与液体摄入】\n").append(JSON.toJSONString(rows)).append("\n");
        }
        if (items.contains("COMPLICATION")) {
            List<ComplicationRecord> rows = complicationRecordMapper.selectList(new QueryWrapper<ComplicationRecord>().eq("patient_id", patientId).ge("occurrence_date", from).orderByDesc("occurrence_date").last("limit 60"));
            prompt.append("【并发症与不适】\n").append(JSON.toJSONString(rows)).append("\n");
        }
        prompt.append("\n请按以下结构输出，总字数控制在 800 字以内：\n")
                .append("1. 总体情况\n2. 需要关注的变化（按紧急程度排序）\n3. 今日可执行的家庭管理事项\n4. 建议复测或复诊的项目与时间\n")
                .append("如出现明确危险数值，请单独标注“需要尽快处理”。末尾注明：本分析仅供家庭记录参考，不能替代临床诊断。\n");
        return callDeepSeek(prompt.toString());
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
                vo.setDwAdjustNeeded(json.getString("是否需要调整"));
                vo.setDwAdjustAmount(parseDecimal(json.getString("建议调整量")));
                vo.setDwTargetWeight(parseDecimal(json.getString("建议目标干体重")));
                vo.setDwAdjustReason(json.getString("调整理由"));
                // multipledimensionassessment
                vo.setWeightControlEval(json.getString("体重控制评估"));
                vo.setDehydrationEval(json.getString("液体清除评估"));
                vo.setBpControlEval(json.getString("血压控制评估"));
                vo.setMainRisk(json.getString("主要风险提示"));
                vo.setDietAdvice(json.getString("饮食建议"));
                vo.setFluidAdvice(json.getString("液体摄入控制建议"));
                vo.setExerciseAdvice(json.getString("运动建议"));
                vo.setMedicationAdvice(json.getString("用药建议"));
                vo.setFollowUpAdvice(json.getString("复诊复查建议"));
                // enhancefield
                vo.setComplicationRiskAssessment(json.getString("并发症风险评估"));
                vo.setMedicationAdviceDetails(json.getString("详细用药建议"));
                vo.setVitalSignTrendSummary(json.getString("血压趋势摘要"));
                return;
            } catch (Exception ignored) {
            }
        }

        // exitreturnto positivethenmatchtextrow
        Pattern needPattern = Pattern.compile("是否需要调整[:：]\\s*(.+?)(?:\\n|$)");
        Pattern amountPattern = Pattern.compile("建议调整量[:：]\\s*([+\\-]?\\d+\\.?\\d*)\\s*kg");
        Pattern targetPattern = Pattern.compile("建议目标干体重[:：]\\s*(\\d+\\.?\\d*)\\s*kg");
        Pattern reasonPattern = Pattern.compile("调整理由[:：]\\s*(.+?)(?:\\n|$)");

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

        sb.append("你是一名专业的肾内科透析治疗临床助手，请根据以下患者透析数据提供专业分析和建议。\n\n");
        sb.append("【分析周期】").append(period).append("\n\n");

        sb.append("【统计数据】\n");
        sb.append("- 记录总数：").append(stats.getTotalCount()).append(" 次\n");
        sb.append("- 平均透前体重：").append(fmt(stats.getAvgOnWeight())).append(" kg\n");
        sb.append("- 平均透后体重：").append(fmt(stats.getAvgOffWeight())).append(" kg\n");
        sb.append("- 平均透析间期体重增长：").append(fmt(stats.getAvgWeightGain())).append(" kg\n");
        sb.append("- 平均超滤量：").append(fmt(stats.getAvgUfAmount())).append(" kg\n");
        sb.append("- 日均体重增长：").append(fmt(stats.getAvgDailyWeightGain())).append(" kg/天\n");
        sb.append("- 超滤达标率：").append(fmt(stats.getDehydrationMatchRate())).append("%\n");
        sb.append("- 超滤过量：").append(nvl(stats.getTooMuchCount())).append(" 次\n");
        sb.append("- 超滤不足：").append(nvl(stats.getInsufficientCount())).append(" 次\n");
        sb.append("- 超滤达标：").append(nvl(stats.getMatchCount())).append(" 次\n");
        sb.append("- 体重增长达标（干体重的 3%～5%）：").append(nvl(stats.getIdealGainCount())).append(" 次\n");
        sb.append("- 体重增长低于干体重的 3%：").append(nvl(stats.getUnder3pctCount())).append(" 次\n");
        sb.append("- 体重增长超过干体重的 5%：").append(nvl(stats.getOver5pctCount())).append(" 次\n");
        sb.append("- 平均收缩压：").append(fmt(stats.getAvgSystolicBp())).append(" mmHg\n");
        sb.append("- 平均舒张压：").append(fmt(stats.getAvgDiastolicBp())).append(" mmHg\n");
        sb.append("- 血压异常：").append(nvl(stats.getBpAbnormalCount())).append(" 次\n");
        sb.append("- 收缩压异常：").append(nvl(stats.getBpSysAbnormalCount())).append(" 次\n");
        sb.append("- 舒张压异常：").append(nvl(stats.getBpDiaAbnormalCount())).append(" 次\n");

        appendDryWeightSection(sb, dryWeightList, timeType, timeValue);
        appendOffVsDrySection(sb, chartData);

        // enhancedata: Patientclinicalinformation
        if (clinical != null) {
            sb.append("\n【患者临床信息】\n");
            sb.append("- 透析类型：").append(clinical.getDialysisType() != null ? clinical.getDialysisType() : "未录入").append("\n");
            sb.append("- 开始透析日期：").append(clinical.getDialysisStartDate() != null ? clinical.getDialysisStartDate().toString() : "未录入").append("\n");
            sb.append("- 血管通路：").append(clinical.getVascularAccess() != null ? clinical.getVascularAccess() : "未录入").append("\n");
            sb.append("- 原发诊断：").append(clinical.getPrimaryDiagnosis() != null ? clinical.getPrimaryDiagnosis() : "未录入").append("\n");
            sb.append("- 药物过敏：").append(clinical.getAllergyDrugs() != null ? clinical.getAllergyDrugs() : "无").append("\n");
            if (clinical.getTargetDryWeight() != null) sb.append("- 目标干体重：").append(fmt(clinical.getTargetDryWeight())).append(" kg\n");
            if (clinical.getFluidLimitMl() != null) sb.append("- 每日液体上限：").append(clinical.getFluidLimitMl()).append(" ml\n");
        }

        // enhancedata: complicationhistory
        if (recentComplications != null && !recentComplications.isEmpty()) {
            sb.append("\n【并发症历史】\n");
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
            sb.append("\n【当前用药方案】\n");
            for (Medication med : activeMedications) {
                sb.append("- ").append(med.getDrugName() != null ? med.getDrugName() : "");
                if (med.getGenericName() != null) sb.append("(").append(med.getGenericName()).append(")");
                if (med.getDefaultDosage() != null) sb.append(" ").append(med.getDefaultDosage());
                if (med.getDosageForm() != null) sb.append(" [").append(med.getDosageForm()).append("]");
                sb.append("\n");
            }
        }

        if (stats.getMonthlyStats() != null && !stats.getMonthlyStats().isEmpty()) {
            sb.append("\n【月度透析数据】\n");
            for (Map<String, Object> m : stats.getMonthlyStats()) {
                sb.append("- ").append(m.get("month"))
                        .append("：平均体重增长 ").append(fmt(m.get("avg_weight_gain")))
                        .append(" kg，平均超滤量 ").append(fmt(m.get("avg_uf_amount"))).append(" kg\n");
            }
        }

        BigDecimal refDry = resolveReferenceDryWeight(dryWeightList, timeType, timeValue);
        sb.append("\n【系统干体重测算参考（用于校验结论，请勿原样照抄）】\n");
        sb.append(buildDryWeightCalcHint(stats, refDry));

        sb.append("\n请按以下结构使用中文回答（正文建议控制在 500 字以内，条理清晰）：\n");
        sb.append("1. 体重控制情况分析\n");
        sb.append("2. 液体清除效果评估\n");
        sb.append("3. 血压控制情况\n");
        sb.append("4. 存在的问题与风险提示\n");
        sb.append("5. 具体饮食、液体摄入和治疗建议\n");
        sb.append("\n");
        sb.append("======== 强制输出（必须位于全文末尾，标题和格式不可变、不可省略）========\n");
        sb.append("【干体重调整结论】\n");
        sb.append("是否需要调整：是 / 否\n");
        sb.append("建议调整量：+X.XX kg、-X.XX kg 或 0 kg（维持不变）\n");
        sb.append("建议目标干体重：XX.XX kg（不需要调整时填写当前参考干体重）\n");
        sb.append("调整理由：用一句话说明依据（结合平均透后体重、透析间期体重增长、超滤达标率等）\n");
        sb.append("\n干体重调整判断参考：\n");
        sb.append("- 平均透后体重持续高于参考干体重，且超滤不足较多，通常需要上调干体重；\n");
        sb.append("- 超滤过量且透后体重明显低于干体重，通常需要下调干体重；\n");
        sb.append("- 数据波动较大或记录不足时，可建议维持并加强监测，但必须明确填写“是”或“否”；\n");
        sb.append("- 建议调整量必须给出精确到 0.1 kg 的数值，不得仅填写“适时调整”或“待定”。\n");
        sb.append("\n======== 结构化数据（必须位于最末尾，并使用 ```json 代码块包裹）========\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"是否需要调整\": \"是/否\",\n");
        sb.append("  \"建议调整量\": \"+0.5 kg、-0.3 kg 或 0 kg\",\n");
        sb.append("  \"建议目标干体重\": \"60.50 kg\",\n");
        sb.append("  \"调整理由\": \"一句话说明依据\",\n");
        sb.append("  \"体重控制评估\": \"优秀/良好/一般/较差\",\n");
        sb.append("  \"液体清除评估\": \"优秀/良好/一般/较差\",\n");
        sb.append("  \"血压控制评估\": \"优秀/良好/一般/较差\",\n");
        sb.append("  \"主要风险提示\": \"概括一至两个最重要的风险点\",\n");
        sb.append("  \"饮食建议\": \"具体饮食调整建议\",\n");
        sb.append("  \"液体摄入控制建议\": \"具体液体摄入控制建议\",\n");
        sb.append("  \"运动建议\": \"适合的运动类型和强度建议\",\n");
        sb.append("  \"用药建议\": \"是否需要调整用药，或填写遵医嘱\",\n");
        sb.append("  \"复诊复查建议\": \"建议复查指标和时间\",\n");
        sb.append("  \"并发症风险评估\": \"结合并发症历史和透析数据的综合评估\",\n");
        sb.append("  \"详细用药建议\": \"基于当前用药方案的调整建议\",\n");
        sb.append("  \"血压趋势摘要\": \"血压变化趋势简要总结\"\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    private void appendDryWeightSection(StringBuilder sb, List<DryWeightMonthly> dryWeightList,
                                        String timeType, String timeValue) {
        List<DryWeightMonthly> inPeriod = filterDryWeightsInPeriod(dryWeightList, timeType, timeValue);
        sb.append("\n【周期内月度干体重设置】\n");
        if (inPeriod.isEmpty()) {
            sb.append("- 本周期未录入干体重，请结合平均透后体重和体重增长情况推断。\n");
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
        sb.append("\n【透后体重与干体重差值（透后体重减干体重，正值表示透后偏重）】\n");
        sb.append("- 周期平均差值：").append(avgDiff).append(" kg\n");
        sb.append("- 单次差值范围：")
                .append(diffs.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO))
                .append(" ~ ")
                .append(diffs.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO))
                .append(" kg\n");
    }

    private String buildDryWeightCalcHint(DialysisStatsVO stats, BigDecimal refDry) {
        if (refDry == null) {
            return "- 缺少月度干体重记录，请根据平均透后体重及液体清除、体重增长趋势给出调整建议。\n";
        }
        BigDecimal avgOff = stats.getAvgOffWeight();
        if (avgOff == null) {
            return "- 参考干体重为 " + fmt(refDry) + " kg，缺少有效的平均透后体重。\n";
        }
        BigDecimal gap = avgOff.subtract(refDry).setScale(2, RoundingMode.HALF_UP);
        long insufficient = nvl(stats.getInsufficientCount());
        long tooMuch = nvl(stats.getTooMuchCount());
        long total = nvl(stats.getTotalCount());

        StringBuilder hint = new StringBuilder();
        hint.append("- 当前参考干体重：").append(fmt(refDry)).append(" kg\n");
        hint.append("- 周期平均透后体重：").append(fmt(avgOff)).append(" kg\n");
        hint.append("- 平均透后体重与干体重差值：").append(gap).append(" kg\n");

        BigDecimal suggestedDelta = BigDecimal.ZERO;
        if (total > 0) {
            if (insufficient > tooMuch && gap.compareTo(new BigDecimal("0.3")) > 0) {
                suggestedDelta = gap.min(new BigDecimal("1.0")).setScale(1, RoundingMode.HALF_UP);
                hint.append("- 测算趋势：建议上调干体重约 +").append(suggestedDelta).append(" kg\n");
            } else if (tooMuch > insufficient && gap.compareTo(new BigDecimal("-0.3")) < 0) {
                suggestedDelta = gap.max(new BigDecimal("-1.0")).setScale(1, RoundingMode.HALF_UP);
                hint.append("- 测算趋势：建议下调干体重约 ").append(suggestedDelta).append(" kg\n");
            } else {
                hint.append("- 测算趋势：暂时维持当前干体重（0 kg）\n");
            }
        }
        BigDecimal target = refDry.add(suggestedDelta).setScale(2, RoundingMode.HALF_UP);
        hint.append("- 测算目标干体重约：").append(fmt(target)).append(" kg\n");
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
            return "全部历史数据";
        }
        switch (timeType != null ? timeType.toLowerCase() : "") {
            case "year":
                return timeValue + " 年";
            case "week":
                return timeValue + " 周";
            case "month":
            default:
                return timeValue.replace("-", " 年 ") + " 月";
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
                    "你是肾内科透析管理助手。回答必须使用中文，并且仅包含一次固定面板“【干体重调整结论】”，"
                            + "面板必须严格包含四行：是否需要调整、建议调整量、建议目标干体重、调整理由。"
                            + "建议调整量必须是带符号的具体千克数，例如 +0.5 kg、-0.3 kg 或 0 kg。"
                            + "最后必须输出一个 ```json 代码块，包含这些字段："
                            + "是否需要调整、建议调整量、建议目标干体重、调整理由、体重控制评估、液体清除评估、血压控制评估、主要风险提示、"
                            + "饮食建议、液体摄入控制建议、运动建议、用药建议、复诊复查建议、并发症风险评估、详细用药建议、血压趋势摘要。");

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
            return "AI 分析失败，未返回有效结果";
        } catch (Exception e) {
            return "AI 分析调用失败：" + e.getMessage();
        }
    }

    /**
     * ifmodelnot outputfixedsetpanelblock, trackaddNotice
     */
    private String ensureDryWeightConclusion(String content) {
        if (content == null) {
            return "";
        }
        if (content.contains("【干体重调整结论】")) {
            return content;
        }
        return content + "\n\n【干体重调整结论】\n"
                + "是否需要调整：需结合临床复核\n"
                + "建议调整量：0 kg（维持不变）\n"
                + "建议目标干体重：请参考干体重管理页面的最新设置\n"
                + "调整理由：AI 未返回结构化结论，请重新分析或人工评估。\n"
                + "\n```json\n"
                + "{\n"
                + "  \"是否需要调整\": \"否\",\n"
                + "  \"建议调整量\": \"0 kg\",\n"
                + "  \"建议目标干体重\": \"请参考干体重管理页面的最新设置\",\n"
                + "  \"调整理由\": \"AI 未返回结构化结论，请重新分析或人工评估\"\n"
                + "}\n"
                + "```\n";
    }
}
