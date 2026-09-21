package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.MedicalRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Medical RecordsMapper
 */
@Mapper
public interface MedicalRecordMapper extends BaseMapper<MedicalRecord> {
}