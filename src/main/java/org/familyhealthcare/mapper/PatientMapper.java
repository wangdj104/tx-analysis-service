package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.Patient;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PatientMapper extends BaseMapper<Patient> {

    @Select("SELECT name FROM patient WHERE status = 1 AND deleted = 0 ORDER BY create_time DESC")
    List<String> selectAllNames();
}
