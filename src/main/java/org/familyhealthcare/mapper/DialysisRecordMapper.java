package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.DialysisRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * Dialysis RecordsMapper
 */
@Mapper
public interface DialysisRecordMapper extends BaseMapper<DialysisRecord> {

    /** The caller holds the row lock and has merged all source inputs. Null derivations are deliberate. */
    @Update("UPDATE dialysis_record SET patient_id=#{patientId}, record_date=#{recordDate}, " +
            "last_off_weight=#{lastOffWeight}, on_weight=#{onWeight}, off_weight=#{offWeight}, interval_days=#{intervalDays}, " +
            "session_minutes=#{sessionMinutes}, ktv=#{ktv}, urr=#{urr}, access_issue=#{accessIssue}, " +
            "systolic_bp=#{systolicBp}, diastolic_bp=#{diastolicBp}, record_type=#{recordType}, remark=#{remark}, " +
            "weight_gain=#{weightGain}, uf_amount=#{ufAmount}, daily_weight_gain=#{dailyWeightGain}, " +
            "dehydration_status=#{dehydrationStatus}, weight_3pct=#{weight3pct}, weight_5pct=#{weight5pct} WHERE id=#{id}")
    int updateEffectiveRecord(DialysisRecord record);

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
