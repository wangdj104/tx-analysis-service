package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.BpPatternAnalysis;
import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.mapper.BpPatternAnalysisMapper;
import org.familyhealthcare.service.BpPatternAnalysisService;
import org.familyhealthcare.service.DialysisRecordService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Blood Pressure Pattern AnalysisServiceimplement
 */
@Service
public class BpPatternAnalysisServiceImpl extends ServiceImpl<BpPatternAnalysisMapper, BpPatternAnalysis> implements BpPatternAnalysisService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private DialysisRecordService dialysisRecordService;

    @Override
    public BpPatternAnalysis analyze(Long patientId, String timeType, String timeValue) {
        if (patientId == null) throw new IllegalStateException("Select a patient");
        dataScopeHelper.requirePatient(patientId);
        Long userId = dataScopeHelper.requireUserId();

        List<DialysisRecord> records = dialysisRecordService.listByFilter(timeType, timeValue, patientId);
        if (records == null || records.isEmpty()) throw new IllegalStateException("this WeekNoneDialysisdata");

        // collecthas validBPdata
        List<Integer> systolicList = new ArrayList<>();
        List<Integer> diastolicList = new ArrayList<>();
        List<BigDecimal> weightGainList = new ArrayList<>();
        List<BigDecimal> ufAmountList = new ArrayList<>();
        int orthostaticCount = 0;
        int lowBpCount = 0;
        int highBpCount = 0;

        DialysisRecord prevRecord = null;
        for (DialysisRecord r : records) {
            if (r.getSystolicBp() != null) {
                systolicList.add(r.getSystolicBp());
                if (r.getSystolicBp() < 90) lowBpCount++;
                if (r.getSystolicBp() > 140) highBpCount++;
                // orthostatic hypotensionBlood Pressuretest: Systolic Pressurelowerimage>20mmHg
                if (prevRecord != null && prevRecord.getSystolicBp() != null &&
                        (prevRecord.getSystolicBp() - r.getSystolicBp()) > 20) {
                    orthostaticCount++;
                }
            }
            if (r.getDiastolicBp() != null) diastolicList.add(r.getDiastolicBp());
            if (r.getWeightGain() != null) weightGainList.add(r.getWeightGain());
            if (r.getUfAmount() != null) ufAmountList.add(r.getUfAmount());
            prevRecord = r;
        }

        BpPatternAnalysis analysis = new BpPatternAnalysis();
        analysis.setPatientId(patientId);
        analysis.setUserId(userId);
        analysis.setAnalysisDate(LocalDate.now());
        analysis.setTimeType(timeType);
        analysis.setTimeValue(timeValue);

        // calculateaverageBP
        if (!systolicList.isEmpty()) {
            double avgSys = systolicList.stream().mapToInt(i -> i).average().orElse(0);
            analysis.setAvgSystolic(BigDecimal.valueOf(avgSys).setScale(1, RoundingMode.HALF_UP));
            analysis.setMaxSystolic(systolicList.stream().mapToInt(i -> i).max().orElse(0));
            analysis.setMinSystolic(systolicList.stream().mapToInt(i -> i).min().orElse(0));
            // standard deviation
            double variance = systolicList.stream()
                    .mapToDouble(i -> Math.pow(i - avgSys, 2)).average().orElse(0);
            analysis.setStdDeviation(BigDecimal.valueOf(Math.sqrt(variance)).setScale(2, RoundingMode.HALF_UP));
        }
        if (!diastolicList.isEmpty()) {
            double avgDia = diastolicList.stream().mapToInt(i -> i).average().orElse(0);
            analysis.setAvgDiastolic(BigDecimal.valueOf(avgDia).setScale(1, RoundingMode.HALF_UP));
        }
        analysis.setOrthostaticCount(orthostaticCount);
        analysis.setLowBpCount(lowBpCount);
        analysis.setHighBpCount(highBpCount);

        // Average ultrafiltration volume
        if (!ufAmountList.isEmpty()) {
            double avgUf = ufAmountList.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
            analysis.setAvgUfAmount(BigDecimal.valueOf(avgUf).setScale(2, RoundingMode.HALF_UP));
        }

        // Pearsonrelatedcoefficient: Weightgrowth andSystolic Pressure
        if (!weightGainList.isEmpty() && !systolicList.isEmpty() &&
                weightGainList.size() == systolicList.size()) {
            double correlation = calculatePearsonCorrelation(weightGainList, systolicList);
            analysis.setCorrelationWeightGainBp(BigDecimal.valueOf(correlation).setScale(3, RoundingMode.HALF_UP));
        }

        // generateanalysissummary
        StringBuilder summary = new StringBuilder();
        if (analysis.getAvgSystolic() != null) {
            summary.append("Average systolic pressure ").append(analysis.getAvgSystolic()).append(" mmHg, ");
            summary.append("variationrange ").append(analysis.getMinSystolic()).append("-").append(analysis.getMaxSystolic()).append(" mmHg. ");
        }
        if (analysis.getStdDeviation() != null && analysis.getStdDeviation().compareTo(BigDecimal.valueOf(15)) > 0) {
            summary.append("Blood Pressurevariabilityrelativelylarge(standard deviation").append(analysis.getStdDeviation()).append("), needattention. ");
        }
        if (orthostaticCount > 0) summary.append("Dialysisinorthostatic hypotensionBlood Pressure ").append(orthostaticCount).append(" times. ");
        if (lowBpCount > 0) summary.append("lowBlood Pressure(<90) ").append(lowBpCount).append(" times. ");
        if (highBpCount > 0) summary.append("highBlood Pressure(>140) ").append(highBpCount).append(" times. ");
        analysis.setAnalysisSummary(summary.toString());

        baseMapper.insert(analysis);
        return analysis;
    }

    private double calculatePearsonCorrelation(List<BigDecimal> x, List<Integer> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 2) return 0;
        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) { sumX += x.get(i).doubleValue(); sumY += y.get(i); }
        double meanX = sumX / n, meanY = sumY / n;
        double covXY = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i).doubleValue() - meanX;
            double dy = y.get(i) - meanY;
            covXY += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX == 0 || varY == 0) return 0;
        return covXY / Math.sqrt(varX * varY);
    }

    @Override
    public List<BpPatternAnalysis> listByPatient(Long patientId) {
        if (patientId == null) return java.util.Collections.emptyList();
        Long userId = dataScopeHelper.requireUserId();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpPatternAnalysis> qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId).orderByDesc("analysis_date");
        return baseMapper.selectList(qw);
    }

    @Override
    public boolean deleteOwned(Long id) {
        BpPatternAnalysis existing = getById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }
}
