package org.familyhealthcare.service;

import org.familyhealthcare.vo.HealthReportRequestVO;

/**
 * Health ReportExportService
 */
public interface HealthReportService {

    /**
     * generateHealth Report
     * @param request requestreferencecount
     * @param format format: html/pdf
     * @return Reportcontentbytescountgroup
     */
    byte[] generateReport(HealthReportRequestVO request, String format) throws Exception;
}
