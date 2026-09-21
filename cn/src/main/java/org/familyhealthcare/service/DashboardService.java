package org.familyhealthcare.service;

import org.familyhealthcare.vo.DashboardSummaryVO;

public interface DashboardService {

    DashboardSummaryVO getSummary(Long patientId);
}
