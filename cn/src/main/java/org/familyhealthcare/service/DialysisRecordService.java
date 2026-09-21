package org.familyhealthcare.service;

import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.vo.DialysisStatsVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * Dialysis RecordsService
 */
public interface DialysisRecordService extends IService<DialysisRecord> {

    /**
     * SaveDialysis Records (Automaticcalculatederived field)
     */
    boolean saveRecord(DialysisRecord record);

    /**
     * updateDialysis Records (Automaticcalculatederived field)
     */
    boolean updateRecord(DialysisRecord record);

    /**
     * getstatisticsanalysisdata
     * @param timeType Time dimension: year/month/week
     * @param timeValue Timevalue, for example  2026 / 2026-04 / 2026-04-28
     */
    DialysisStatsVO getStatistics(String timeType, String timeValue, Long patientId);

    /**
     * getcharttabledata
     * @param timeType Time dimension: year/month/week
     * @param timeValue Timevalue, for example  2026 / 2026-04 / 2026-04-28
     */
    DialysisStatsVO getChartData(String timeType, String timeValue, Long patientId);

    /**
     * by itemsitemquery (containuserseparateaway)
     */
    List<DialysisRecord> listByFilter(String timeType, String timeValue, Long patientId);

    DialysisRecord getOwnedById(Long id);

    boolean deleteOwned(Long id);
}
