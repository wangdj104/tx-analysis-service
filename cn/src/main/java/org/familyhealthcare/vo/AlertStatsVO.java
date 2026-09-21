package org.familyhealthcare.vo;

import org.familyhealthcare.entity.AlertRecord;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * alertstatisticsVO
 */
@Data
public class AlertStatsVO {

    private Long pendingCount;
    private Long confirmedCount;
    private Long resolvedCount;
    private Long totalCount;

    /** eachlevelcount */
    private List<Map<String, Object>> levelCounts;

    /** eachtypecount */
    private List<Map<String, Object>> typeCounts;

    /** recent 5itemsalert */
    private List<AlertRecord> recentAlerts;
}
