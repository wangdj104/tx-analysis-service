package org.familyhealthcare.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * complicationstatisticsVO
 */
@Data
public class ComplicationStatsVO {

    private Long totalCount;

    /**
     * eachtypecount: [{type: "INFECTION", count: 3}, ...]
     */
    private List<Map<String, Object>> typeCounts;

    /**
     * eachSeverelevelcount: [{severity: "MILD", count: 5}, ...]
     */
    private List<Map<String, Object>> severityCounts;

    /**
     * recent periodcomplicationlist (most recent 5items)
     */
    private List<Map<String, Object>> recentList;
}
