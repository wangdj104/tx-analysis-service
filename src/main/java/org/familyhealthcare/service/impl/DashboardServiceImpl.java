package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.entity.DryWeightMonthly;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.entity.MedicationLog;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.MedicalRecordItemMapper;
import org.familyhealthcare.mapper.MedicalRecordMapper;
import org.familyhealthcare.mapper.MedicationLogMapper;
import org.familyhealthcare.service.DashboardService;
import org.familyhealthcare.service.DialysisRecordService;
import org.familyhealthcare.service.DryWeightMonthlyService;
import org.familyhealthcare.service.MedicalRecordService;
import org.familyhealthcare.service.MedicationService;
import org.familyhealthcare.service.PatientService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.DashboardSummaryVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private PatientService patientService;

    @Autowired
    private DialysisRecordService dialysisRecordService;

    @Autowired
    private MedicalRecordService medicalRecordService;

    @Autowired
    private MedicalRecordMapper medicalRecordMapper;

    @Autowired
    private MedicalRecordItemMapper medicalRecordItemMapper;

    @Autowired
    private MedicationLogMapper medicationLogMapper;

    @Autowired
    private DryWeightMonthlyService dryWeightMonthlyService;

    @Override
    public DashboardSummaryVO getSummary(Long patientId) {
        Long userId = dataScopeHelper.requireUserId();
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        DashboardSummaryVO vo = new DashboardSummaryVO();

        List<Patient> patients = patientService.list();
        if (patientId != null) {
            vo.setPatientCount(1L);
        } else {
            vo.setPatientCount((long) patients.stream().filter(p -> p.getStatus() != null && p.getStatus() == 1).count());
        }

        QueryWrapper<DialysisRecord> dialysisQw = new QueryWrapper<DialysisRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(dialysisQw);
        }
        if (patientId != null) {
            dialysisQw.eq("patient_id", patientId);
        }
        dialysisQw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
        vo.setDialysisCountMonth(dialysisRecordService.count(dialysisQw));

        QueryWrapper<MedicalRecord> medicalQw = new QueryWrapper<MedicalRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(medicalQw);
        }
        if (patientId != null) {
            medicalQw.eq("patient_id", patientId);
        }
        medicalQw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
        vo.setMedicalRecordCountMonth(medicalRecordService.count(medicalQw));

        QueryWrapper<MedicationLog> medLogQw = new QueryWrapper<MedicationLog>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(medLogQw);
        }
        if (patientId != null) {
            medLogQw.eq("patient_id", patientId);
        }
        medLogQw.apply("DATE_FORMAT(administration_time, '%Y-%m') = {0}", month);
        vo.setMedicationLogCountMonth(medicationLogMapper.selectCount(medLogQw));

        QueryWrapper<MedicalRecord> recordQw = new QueryWrapper<MedicalRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(recordQw);
        }
        if (patientId != null) {
            recordQw.eq("patient_id", patientId);
        }
        List<MedicalRecord> userRecords = medicalRecordService.list(recordQw);
        List<Long> recordIds = userRecords.stream().map(MedicalRecord::getId).collect(Collectors.toList());
        long abnormalCount = 0;
        List<Map<String, Object>> recentAbnormal = new ArrayList<>();
        if (!recordIds.isEmpty()) {
            List<MedicalRecordItem> abnormalItems = medicalRecordItemMapper.selectList(
                    new QueryWrapper<MedicalRecordItem>()
                            .in("record_id", recordIds)
                            .ne("is_abnormal", 0)
                            .orderByDesc("created_at")
                            .last("limit 20"));
            abnormalCount = medicalRecordItemMapper.selectCount(
                    new QueryWrapper<MedicalRecordItem>()
                            .in("record_id", recordIds)
                            .ne("is_abnormal", 0));
            Map<Long, MedicalRecord> recordMap = userRecords.stream()
                    .collect(Collectors.toMap(MedicalRecord::getId, r -> r, (a, b) -> a));
            for (MedicalRecordItem item : abnormalItems) {
                MedicalRecord rec = recordMap.get(item.getRecordId());
                if (rec == null) continue;
                Map<String, Object> row = new HashMap<>();
                row.put("recordDate", rec.getRecordDate());
                row.put("patientName", rec.getPatientName());
                row.put("itemName", item.getItemName());
                row.put("resultValue", item.getResultValue());
                row.put("unit", item.getUnit());
                row.put("isAbnormal", item.getIsAbnormal());
                recentAbnormal.add(row);
            }
        }
        vo.setAbnormalItemCount(abnormalCount);
        vo.setRecentAbnormalItems(recentAbnormal.size() > 8 ? recentAbnormal.subList(0, 8) : recentAbnormal);

        List<DryWeightMonthly> dryWeights = patientId != null
                ? dryWeightMonthlyService.listAllOrderByMonth(patientId)
                : java.util.Collections.emptyList();
        if (!dryWeights.isEmpty()) {
            DryWeightMonthly latest = dryWeights.get(0);
            vo.setCurrentDryWeight(latest.getDryWeight());
            vo.setCurrentDryWeightMonth(latest.getYearMonth());
            if (dryWeights.size() > 1) {
                DryWeightMonthly prev = dryWeights.get(1);
                vo.setPreviousDryWeight(prev.getDryWeight());
                if (latest.getDryWeight() != null && prev.getDryWeight() != null) {
                    vo.setDryWeightDelta(latest.getDryWeight().subtract(prev.getDryWeight()));
                }
            }
        }

        QueryWrapper<DialysisRecord> recentDialysisQw = new QueryWrapper<DialysisRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(recentDialysisQw);
        }
        if (patientId != null) {
            recentDialysisQw.eq("patient_id", patientId);
        }
        recentDialysisQw.orderByDesc("record_date").last("limit 5");
        List<DialysisRecord> recentDialysis = dialysisRecordService.list(recentDialysisQw);
        vo.setRecentDialysis(recentDialysis);

        QueryWrapper<MedicalRecord> recentMedicalQw = new QueryWrapper<MedicalRecord>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(recentMedicalQw);
        }
        if (patientId != null) {
            recentMedicalQw.eq("patient_id", patientId);
        }
        recentMedicalQw.orderByDesc("record_date").last("limit 5");
        vo.setRecentMedicalRecords(medicalRecordMapper.selectList(recentMedicalQw));

        vo.setMonthlyTrend(buildMonthlyTrend(userId, patientId));
        Map<String, Long> dehydrationStats = buildDehydrationStats(userId, month, patientId);
        vo.setDehydrationStats(dehydrationStats);
        vo.setDryWeightTrend(buildDryWeightTrend(dryWeights));
        fillHealthInsights(vo, recentDialysis, dehydrationStats, userId, month, patientId);

        return vo;
    }

    private void fillHealthInsights(DashboardSummaryVO vo, List<DialysisRecord> recentDialysis,
                                    Map<String, Long> dehydrationStats, Long userId, String month, Long patientId) {
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<>();
        dataScopeHelper.applyUserScope(qw);
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        qw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
        List<DialysisRecord> records = dialysisRecordService.list(qw);

        List<DialysisRecord> normalRecords = records.stream()
                .filter(r -> !"INCOMPLETE".equals(r.getRecordType()))
                .collect(Collectors.toList());

        long match = dehydrationStats.getOrDefault("match", 0L);
        long tooMuch = dehydrationStats.getOrDefault("tooMuch", 0L);
        long insufficient = dehydrationStats.getOrDefault("insufficient", 0L);
        long totalDehydration = match + tooMuch + insufficient;
        BigDecimal matchRate = totalDehydration > 0
                ? BigDecimal.valueOf(match * 100.0 / totalDehydration).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        long over5 = normalRecords.stream().filter(this::isOver5pct).count();
        long bpAbnormal = records.stream().filter(this::isBpAbnormal).count();
        vo.setDialysisMatchRateMonth(matchRate);
        vo.setAvgWeightGainMonth(avg(normalRecords.stream().map(DialysisRecord::getWeightGain).collect(Collectors.toList())));
        vo.setAvgUfAmountMonth(avg(normalRecords.stream().map(DialysisRecord::getUfAmount).collect(Collectors.toList())));
        vo.setOver5pctCountMonth(over5);
        vo.setBpAbnormalCountMonth(bpAbnormal);
        vo.setLatestDialysisStatus(recentDialysis == null || recentDialysis.isEmpty()
                ? "NoneDialysis Records"
                : dehydrationText(recentDialysis.get(0).getDehydrationStatus()));

        List<String> highlights = new ArrayList<>();
        if (totalDehydration > 0) {
            highlights.add("This MonthUltrafiltration on targetrate " + matchRate + "%");
        }
        if (over5 > 0) {
            highlights.add("This Month " + over5 + " timesweight gain exceedspastDry Weight 5%");
        }
        if (bpAbnormal > 0) {
            highlights.add("This Month " + bpAbnormal + " timesBlood Pressurenot in targetrangebetween");
        }
        if (vo.getAbnormalItemCount() != null && vo.getAbnormalItemCount() > 0) {
            highlights.add("tiredcount " + vo.getAbnormalItemCount() + " itemExaminationAbnormalneedattention");
        }
        if (highlights.isEmpty()) {
            highlights.add("recent periodcoreindicatorstable, keep it uprecordrhythm");
        }
        vo.setHealthHighlights(highlights);

        int riskScore = 0;
        if (matchRate.compareTo(new BigDecimal("60")) < 0 && totalDehydration > 0) {
            riskScore += 2;
        } else if (matchRate.compareTo(new BigDecimal("80")) < 0 && totalDehydration > 0) {
            riskScore += 1;
        }
        if (over5 >= 2) riskScore += 2;
        else if (over5 == 1) riskScore += 1;
        if (bpAbnormal >= 3) riskScore += 2;
        else if (bpAbnormal > 0) riskScore += 1;
        if (vo.getAbnormalItemCount() != null && vo.getAbnormalItemCount() >= 10) riskScore += 2;
        else if (vo.getAbnormalItemCount() != null && vo.getAbnormalItemCount() >= 5) riskScore += 1;

        if (riskScore >= 4) {
            vo.setHealthRiskLevel("HIGH");
            vo.setHealthRiskLabel("needneedheavypointattention");
        } else if (riskScore >= 2) {
            vo.setHealthRiskLevel("MEDIUM");
            vo.setHealthRiskLabel("storein variation");
        } else {
            vo.setHealthRiskLevel("LOW");
            vo.setHealthRiskLabel("wholebodystable");
        }
    }

    private BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(v -> v != null).collect(Collectors.toList());
        if (nonNull.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = nonNull.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(nonNull.size()), 2, RoundingMode.HALF_UP);
    }

    private boolean isOver5pct(DialysisRecord r) {
        if (r == null || r.getWeightGain() == null || r.getWeight5pct() == null) {
            return false;
        }
        return r.getWeightGain().compareTo(r.getWeight5pct()) > 0;
    }

    private boolean isBpAbnormal(DialysisRecord r) {
        if (r == null) {
            return false;
        }
        Integer sys = r.getSystolicBp();
        Integer dia = r.getDiastolicBp();
        return (sys != null && (sys > 140 || sys < 120))
                || (dia != null && (dia > 90 || dia < 70));
    }

    private String dehydrationText(String status) {
        if ("TOO_MUCH".equals(status)) return "Excessive ultrafiltration";
        if ("INSUFFICIENT".equals(status)) return "Insufficient ultrafiltration";
        if ("MATCH".equals(status)) return "Ultrafiltration on target";
        return "Statuspendingdetermine";
    }

    private List<Map<String, Object>> buildMonthlyTrend(Long userId, Long patientId) {
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate now = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        for (int i = 5; i >= 0; i--) {
            String month = now.minusMonths(i).format(fmt);
            Map<String, Object> row = new HashMap<>();
            row.put("month", month);

            QueryWrapper<DialysisRecord> dq = new QueryWrapper<>();
            dataScopeHelper.applyUserScope(dq);
            if (patientId != null) {
                dq.eq("patient_id", patientId);
            }
            dq.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
            row.put("dialysis", dialysisRecordService.count(dq));

            QueryWrapper<MedicalRecord> mq = new QueryWrapper<>();
            dataScopeHelper.applyUserScope(mq);
            if (patientId != null) {
                mq.eq("patient_id", patientId);
            }
            mq.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
            row.put("medical", medicalRecordService.count(mq));

            QueryWrapper<MedicationLog> lq = new QueryWrapper<>();
            dataScopeHelper.applyUserScope(lq);
            if (patientId != null) {
                lq.eq("patient_id", patientId);
            }
            lq.apply("DATE_FORMAT(administration_time, '%Y-%m') = {0}", month);
            row.put("medication", medicationLogMapper.selectCount(lq));

            trend.add(row);
        }
        return trend;
    }

    private Map<String, Long> buildDehydrationStats(Long userId, String month, Long patientId) {
        QueryWrapper<DialysisRecord> qw = new QueryWrapper<>();
        dataScopeHelper.applyUserScope(qw);
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        qw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", month);
        List<DialysisRecord> records = dialysisRecordService.list(qw);
        long tooMuch = 0;
        long insufficient = 0;
        long match = 0;
        for (DialysisRecord r : records) {
            String status = r.getDehydrationStatus();
            if ("TOO_MUCH".equals(status)) {
                tooMuch++;
            } else if ("INSUFFICIENT".equals(status)) {
                insufficient++;
            } else if ("MATCH".equals(status)) {
                match++;
            }
        }
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("tooMuch", tooMuch);
        stats.put("insufficient", insufficient);
        stats.put("match", match);
        return stats;
    }

    private List<Map<String, Object>> buildDryWeightTrend(List<DryWeightMonthly> dryWeights) {
        if (dryWeights == null || dryWeights.isEmpty()) {
            return Collections.emptyList();
        }
        List<DryWeightMonthly> sorted = new ArrayList<>(dryWeights);
        Collections.reverse(sorted);
        int from = Math.max(0, sorted.size() - 12);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = from; i < sorted.size(); i++) {
            DryWeightMonthly d = sorted.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("month", d.getYearMonth());
            row.put("weight", d.getDryWeight());
            trend.add(row);
        }
        return trend;
    }
}
