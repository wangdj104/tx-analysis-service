package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.ComplicationRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Complication TrackingrecordMapper
 */
@Mapper
public interface ComplicationRecordMapper extends BaseMapper<ComplicationRecord> {

    @Select("SELECT complication_type AS type, COUNT(*) AS count FROM complication_record WHERE patient_id = #{patientId} GROUP BY complication_type")
    List<Map<String, Object>> countByType(Long patientId);

    @Select("SELECT severity AS severity, COUNT(*) AS count FROM complication_record WHERE patient_id = #{patientId} GROUP BY severity")
    List<Map<String, Object>> countBySeverity(Long patientId);
}
