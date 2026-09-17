package org.familyhealthcare.service;

import org.familyhealthcare.entity.NutritionAssessment;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface NutritionAssessmentService extends IService<NutritionAssessment> {

    List<NutritionAssessment> listByPatient(Long patientId);

    boolean saveOrUpdateAssessment(NutritionAssessment record);

    NutritionAssessment calculateNutritionStatus(NutritionAssessment record);

    boolean deleteOwned(Long id);
}
