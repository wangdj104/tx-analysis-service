package org.familyhealthcare.service;

import org.familyhealthcare.vo.DataExportRequestVO;

/**
 * Data ExportService
 */
public interface DataExportService {

    /**
     * Exportdatafor CSVformat
     * @param request Exportrequest
     * @return CSVcontentbytescountgroup
     */
    byte[] exportCsv(DataExportRequestVO request) throws Exception;
}
