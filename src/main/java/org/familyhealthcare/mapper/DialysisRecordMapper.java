package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.DialysisRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Dialysis RecordsMapper
 */
@Mapper
public interface DialysisRecordMapper extends BaseMapper<DialysisRecord> {

    /**
     * by Monthstatisticsinterdialytic weight gain and ultrafiltration volume
     */
    @Select("SELECT DATE_FORMAT(record_date, '%Y-%m') as month, " +
            "AVG(weight_gain) as avg_weight_gain, " +
            "AVG(uf_amount) as avg_uf_amount " +
            "FROM dialysis_record " +
            "GROUP BY DATE_FORMAT(record_date, '%Y-%m') " +
            "ORDER BY month")
    List<Map<String, Object>> selectMonthlyStats();
}
