package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BpSelfMonitorRecordMapper extends BaseMapper<BpSelfMonitorRecord> {
    // 历史导入一次写入原生读数与事实；显式 NULL 血糖单位覆盖旧列默认值。
    @Insert("INSERT INTO bp_self_monitor_record(patient_id,user_id,record_date,record_time,measure_type,systolic_bp,diastolic_bp,blood_glucose,bg_unit,measure_period,source_type,source_external_id,verification_status,remark,import_facts_json) VALUES(#{row.patientId},#{row.userId},#{row.recordDate},#{row.recordTime,jdbcType=VARCHAR},#{row.measureType},#{row.systolicBp,jdbcType=INTEGER},#{row.diastolicBp,jdbcType=INTEGER},#{row.bloodGlucose,jdbcType=DECIMAL},#{row.bgUnit,jdbcType=VARCHAR},#{row.measurePeriod,jdbcType=VARCHAR},#{row.sourceType},#{row.sourceExternalId},#{row.verificationStatus},#{row.remark,jdbcType=VARCHAR},#{factsJson,jdbcType=LONGVARCHAR})")
    @Options(useGeneratedKeys=true,keyProperty="row.id",keyColumn="id")
    int insertImported(@Param("row") BpSelfMonitorRecord row,@Param("factsJson") String factsJson);

}
