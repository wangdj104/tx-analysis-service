package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.MedicalRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface MedicalRecordMapper extends BaseMapper<MedicalRecord> {
    // 不执行随后补写；原始事实和父记录共享同一次 INSERT。
    @Insert("INSERT INTO medical_record(patient_name,patient_id,user_id,record_date,record_type,hospital_name,dept_name,doctor_name,source_type,source_external_id,verification_status,confidence_score,content_version,remark,import_facts_json) VALUES(#{row.patientName},#{row.patientId},#{row.userId},#{row.recordDate},#{row.recordType},#{row.hospitalName,jdbcType=VARCHAR},#{row.deptName,jdbcType=VARCHAR},#{row.doctorName,jdbcType=VARCHAR},#{row.sourceType},#{row.sourceExternalId},#{row.verificationStatus},#{row.confidenceScore,jdbcType=DECIMAL},#{row.contentVersion},#{row.remark,jdbcType=VARCHAR},#{factsJson,jdbcType=LONGVARCHAR})")
    @Options(useGeneratedKeys=true,keyProperty="row.id",keyColumn="id")
    int insertImported(@Param("row") MedicalRecord row,@Param("factsJson") String factsJson);

    @Select("SELECT * FROM medical_record WHERE id=#{id} FOR UPDATE")
    MedicalRecord selectForUpdate(@Param("id") Long id);

    @Update("UPDATE medical_record SET content_version=content_version+1,verification_status='REVIEW_REQUIRED',verified_by=NULL,verified_at=NULL,verification_note=NULL WHERE id=#{id} AND content_version=#{expectedVersion}")
    int invalidateReview(@Param("id") Long id,@Param("expectedVersion") Long expectedVersion);

    @Update("UPDATE medical_record SET verification_status=#{status},verified_by=#{actor},verified_at=#{at},verification_note=#{note,jdbcType=LONGVARCHAR} WHERE id=#{id} AND content_version=#{expectedVersion}")
    int reviewVersion(@Param("id") Long id,@Param("expectedVersion") Long expectedVersion,@Param("status") String status,@Param("actor") Long actor,@Param("at") LocalDateTime at,@Param("note") String note);
}
