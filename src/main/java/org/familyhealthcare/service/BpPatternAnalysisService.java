package org.familyhealthcare.service;

import org.familyhealthcare.entity.BpPatternAnalysis;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface BpPatternAnalysisService extends IService<BpPatternAnalysis> {

    BpPatternAnalysis analyze(Long patientId, String timeType, String timeValue);

    List<BpPatternAnalysis> listByPatient(Long patientId);

    boolean deleteOwned(Long id);
}
