package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.AlertRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AlertRecordMapper extends BaseMapper<AlertRecord> {

    @Select("SELECT alert_level AS level, COUNT(*) AS count FROM alert_record WHERE patient_id = #{patientId} GROUP BY alert_level")
    List<Map<String, Object>> countByLevel(Long patientId);

    @Select("SELECT alert_type AS type, COUNT(*) AS count FROM alert_record WHERE patient_id = #{patientId} GROUP BY alert_type")
    List<Map<String, Object>> countByType(Long patientId);
}
