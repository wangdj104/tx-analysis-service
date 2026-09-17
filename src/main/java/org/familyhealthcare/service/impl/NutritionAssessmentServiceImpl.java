package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.NutritionAssessment;
import org.familyhealthcare.mapper.NutritionAssessmentMapper;
import org.familyhealthcare.service.NutritionAssessmentService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calculates and stores nutrition assessments.
 */
@Service
public class NutritionAssessmentServiceImpl extends ServiceImpl<NutritionAssessmentMapper, NutritionAssessment> implements NutritionAssessmentService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Override
    public List<NutritionAssessment> listByPatient(Long patientId) {
        if (patientId == null) return java.util.Collections.emptyList();
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<NutritionAssessment> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId).orderByDesc("assessment_date");
        return baseMapper.selectList(qw);
    }

    @Override
    public boolean saveOrUpdateAssessment(NutritionAssessment record) {
        Long userId = dataScopeHelper.requireUserId();
        if (record.getPatientId() == null) throw new IllegalStateException("Select a patient");
        dataScopeHelper.requirePatient(record.getPatientId());
        record.setUserId(userId);

        // AutomaticcalculateBMI
        if (record.getBodyWeight() != null && record.getHeight() != null && record.getHeight().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal heightM = record.getHeight().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            record.setBmi(record.getBodyWeight().divide(heightM.multiply(heightM), 2, RoundingMode.HALF_UP));
        }

        // AutomaticinferenceNutritionStatus
        calculateNutritionStatus(record);

        return record.getId() != null ? updateById(record) : save(record);
    }

    @Override
    public NutritionAssessment calculateNutritionStatus(NutritionAssessment record) {
        int riskPoints = 0;

        // BMIassessment
        if (record.getBmi() != null) {
            if (record.getBmi().compareTo(new BigDecimal("18.5")) < 0) riskPoints += 2;
            else if (record.getBmi().compareTo(new BigDecimal("20")) < 0) riskPoints += 1;
        }

        // albuminassessment
        if (record.getAlbumin() != null) {
            if (record.getAlbumin().compareTo(new BigDecimal("35")) < 0) riskPoints += 2;
            else if (record.getAlbumin().compareTo(new BigDecimal("40")) < 0) riskPoints += 1;
        }

        // before albuminassessment
        if (record.getPreAlbumin() != null) {
            if (record.getPreAlbumin().compareTo(new BigDecimal("200")) < 0) riskPoints += 2;
            else if (record.getPreAlbumin().compareTo(new BigDecimal("300")) < 0) riskPoints += 1;
        }

        // SGAscore
        if (record.getSgaScore() != null) {
            if (record.getSgaScore() <= 2) riskPoints += 3;
            else if (record.getSgaScore() <= 4) riskPoints += 1;
        }

        // inferenceNutritionStatus
        if (riskPoints >= 4) {
            record.setNutritionStatus("DEFICIENT");
        } else if (riskPoints >= 2) {
            record.setNutritionStatus("AT_RISK");
        } else {
            record.setNutritionStatus("GOOD");
        }

        // inferenceSGAgrade
        if (record.getSgaScore() != null) {
            if (record.getSgaScore() >= 6) record.setSgaGrade("A");
            else if (record.getSgaScore() >= 3) record.setSgaGrade("B");
            else record.setSgaGrade("C");
        }

        // Generate concise, non-diagnostic guidance.
        StringBuilder advice = new StringBuilder();
        if ("DEFICIENT".equals(record.getNutritionStatus())) {
            advice.append("Nutritional status is deficient. Recommendations: ");
            if (record.getAlbumin() != null && record.getAlbumin().compareTo(new BigDecimal("35")) < 0) {
                advice.append("increase protein intake toward the prescribed target (often about 1.2 g/kg/day); ");
            }
            if (record.getBmi() != null && record.getBmi().compareTo(new BigDecimal("18.5")) < 0) {
                advice.append("increase calorie intake toward the prescribed target (often 30–35 kcal/kg/day); ");
            }
            advice.append("discuss oral nutrition supplements with a clinician and monitor weight closely.");
        } else if ("AT_RISK".equals(record.getNutritionStatus())) {
            advice.append("Nutritional status is at risk. Follow the prescribed protein and calorie targets, and monitor albumin and prealbumin regularly.");
        } else {
            advice.append("Nutritional status is good. Maintain a balanced diet, follow the prescribed protein target, and review nutrition indicators regularly.");
        }
        record.setSupplementAdvice(advice.toString());

        return record;
    }

    @Override
    public boolean deleteOwned(Long id) {
        NutritionAssessment existing = getById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }
}
