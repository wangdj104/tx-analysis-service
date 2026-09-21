package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.HealthIndicator;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * healthlaboratory test dictionaryMapper
 */
@Mapper
public interface HealthIndicatorMapper extends BaseMapper<HealthIndicator> {

    @Select("SELECT * FROM health_indicator WHERE is_active = 1 ORDER BY sort_order")
    List<HealthIndicator> selectActiveIndicators();

    @Select("SELECT * FROM health_indicator WHERE is_active = 1 AND category = #{category} ORDER BY sort_order")
    List<HealthIndicator> selectByCategory(String category);
}
