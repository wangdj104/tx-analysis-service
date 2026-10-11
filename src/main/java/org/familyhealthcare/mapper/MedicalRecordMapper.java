package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.MedicalRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface MedicalRecordMapper extends BaseMapper<MedicalRecord> {
    @Select("SELECT * FROM medical_record WHERE id=#{id} FOR UPDATE")
    MedicalRecord selectForUpdate(@Param("id") Long id);

    @Update("UPDATE medical_record SET content_version=content_version+1,verification_status='REVIEW_REQUIRED',verified_by=NULL,verified_at=NULL,verification_note=NULL WHERE id=#{id} AND content_version=#{expectedVersion}")
    int invalidateReview(@Param("id") Long id,@Param("expectedVersion") Long expectedVersion);

    @Update("UPDATE medical_record SET verification_status=#{status},verified_by=#{actor},verified_at=#{at},verification_note=#{note,jdbcType=LONGVARCHAR} WHERE id=#{id} AND content_version=#{expectedVersion}")
    int reviewVersion(@Param("id") Long id,@Param("expectedVersion") Long expectedVersion,@Param("status") String status,@Param("actor") Long actor,@Param("at") LocalDateTime at,@Param("note") String note);
}
