package org.familyhealthcare.mapper;
import org.familyhealthcare.entity.CareItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface CareItemMapper extends BaseMapper<CareItem> {
    /** 与旧完整来源同一读取附带最小事实，避免逐行另查或把已报告到诊误呈为待进行。 */
    @Select("<script>SELECT i.*,(vf.id IS NOT NULL) AS visit_follow_up_managed,vf.attendance AS visit_follow_up_attendance,vf.attendance_origin AS visit_follow_up_attendance_origin FROM care_item i LEFT JOIN visit_followup_state vf ON vf.source_kind='CARE_ITEM_APPOINTMENT' AND vf.care_item_id=i.id AND vf.patient_id=i.patient_id WHERE 1=1 <if test='patient != null'>AND i.patient_id=#{patient}</if><if test='source != null'>AND i.id=#{source}</if><if test='source == null'><choose><when test='kind != null'>AND i.kind=#{kind}</when><otherwise>AND i.kind&lt;&gt;'QUESTION'</otherwise></choose></if> ORDER BY i.event_at DESC,i.id DESC</script>")
    List<CareItem> selectFollowUpItems(@Param("patient") Long patient,@Param("kind") String kind,@Param("source") Long source);
}
