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

import static org.familyhealthcare.util.ExportLocalization.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

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
    public NutritionAssessment getOwnedById(Long id) {
        dataScopeHelper.requireUserId();
        NutritionAssessment record = getById(id);
        if (record != null) {
            dataScopeHelper.requirePatientOrOwner(record.getPatientId(), record.getUserId());
        }
        return record;
    }

    @Override
    public boolean saveOrUpdateAssessment(NutritionAssessment record) {
        Long userId = dataScopeHelper.requireUserId();
        if (record.getPatientId() == null) throw new IllegalStateException("Select a patient");
        if (record.getId() != null) {
            NutritionAssessment existing = getById(record.getId());
            if (existing == null) return false;
            dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
            if (!Objects.equals(existing.getPatientId(), record.getPatientId())) {
                throw new IllegalArgumentException("An assessment cannot be moved to another patient.");
            }
            record.setUserId(existing.getUserId());
        } else {
            record.setUserId(userId);
        }
        dataScopeHelper.requirePatient(record.getPatientId());

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
            advice.append(text("Nutritional status is deficient. Recommendations: ", "营养状况不足。建议："));
            if (record.getAlbumin() != null && record.getAlbumin().compareTo(new BigDecimal("35")) < 0) {
                advice.append(text("increase protein intake toward the prescribed target (often about 1.2 g/kg/day); ", "按医嘱目标增加蛋白质摄入（通常约为 1.2 g/kg/天）；"));
            }
            if (record.getBmi() != null && record.getBmi().compareTo(new BigDecimal("18.5")) < 0) {
                advice.append(text("increase calorie intake toward the prescribed target (often 30–35 kcal/kg/day); ", "按医嘱目标增加热量摄入（通常为 30–35 kcal/kg/天）；"));
            }
            advice.append(text("discuss oral nutrition supplements with a clinician and monitor weight closely.", "与医生讨论口服营养补充剂，并密切监测体重。"));
        } else if ("AT_RISK".equals(record.getNutritionStatus())) {
            advice.append(text("Nutritional status is at risk. Follow the prescribed protein and calorie targets, and monitor albumin and prealbumin regularly.", "存在营养风险。请遵循医嘱规定的蛋白质和热量目标，并定期监测白蛋白和前白蛋白。"));
        } else {
            advice.append(text("Nutritional status is good. Maintain a balanced diet, follow the prescribed protein target, and review nutrition indicators regularly.", "营养状况良好。请保持均衡饮食，遵循医嘱规定的蛋白质目标，并定期复查营养指标。"));
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
