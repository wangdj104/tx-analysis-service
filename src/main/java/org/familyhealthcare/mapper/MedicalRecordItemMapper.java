package org.familyhealthcare.mapper;

import org.familyhealthcare.entity.MedicalRecordItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Examination item detailsMapper
 */
@Mapper
public interface MedicalRecordItemMapper extends BaseMapper<MedicalRecordItem> {

    @Select("<script>SELECT DISTINCT i.item_name FROM medical_record_item i JOIN medical_record ON medical_record.id=i.record_id ${ew.customSqlSegment}</script>")
    List<String> selectActiveItemNames(@Param("ew") com.baomidou.mybatisplus.core.conditions.Wrapper<org.familyhealthcare.entity.MedicalRecord> query);

    /** 原文与来源版本在同一条查询中捕获，随后批量状态投影只做一致性比对。 */
    @org.apache.ibatis.annotations.Results({
        @org.apache.ibatis.annotations.Result(column="record_id",property="recordId",javaType=Long.class),
        @org.apache.ibatis.annotations.Result(column="content_version",property="contentVersion",javaType=Long.class),
        @org.apache.ibatis.annotations.Result(column="verification_status",property="verificationStatus",javaType=String.class),
        @org.apache.ibatis.annotations.Result(column="record_date",property="recordDate",javaType=String.class),
        @org.apache.ibatis.annotations.Result(column="hospital_name",property="hospitalName",javaType=String.class),
        @org.apache.ibatis.annotations.Result(column="item_name",property="itemName",javaType=String.class),
        @org.apache.ibatis.annotations.Result(column="result_value",property="resultValue",javaType=String.class),
        @org.apache.ibatis.annotations.Result(column="unit",property="unit",javaType=String.class),
        @org.apache.ibatis.annotations.Result(column="reference_range",property="referenceRange",javaType=String.class),
        @org.apache.ibatis.annotations.Result(column="is_abnormal",property="isAbnormal",javaType=Integer.class)
    })
    @Select("<script>SELECT r.id AS record_id,r.content_version,r.verification_status,r.record_date,r.hospital_name,i.item_name,i.result_value,i.unit,i.reference_range,i.is_abnormal FROM medical_record r JOIN medical_record_item i ON i.record_id=r.id ${ew.customSqlSegment}</script>")
    List<java.util.Map<String,Object>> selectTrendRows(@Param("ew") com.baomidou.mybatisplus.core.conditions.Wrapper<org.familyhealthcare.entity.MedicalRecord> query);

    @Select("SELECT DISTINCT item_name FROM medical_record_item WHERE item_name IS NOT NULL AND item_name != '' ORDER BY item_name")
    List<String> selectDistinctItemNames();

    @Select("<script>SELECT DISTINCT item_name FROM medical_record_item WHERE item_name IS NOT NULL AND item_name != '' "
            + "AND record_id IN <foreach collection='recordIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY item_name</script>")
    List<String> selectDistinctItemNamesByRecordIds(@Param("recordIds") List<Long> recordIds);

    @Select("SELECT DISTINCT i.item_name FROM medical_record_item i "
            + "INNER JOIN medical_record r ON i.record_id = r.id "
            + "WHERE i.item_name IS NOT NULL AND i.item_name != '' AND r.patient_id = #{patientId} "
            + "ORDER BY i.item_name")
    List<String> selectDistinctItemNamesByPatientId(@Param("patientId") Long patientId);

    /**
     * based onPatientID and indicatorCodequerylatest Examinationitem (through JOIN medical_record filterPatient)
     */
    // activeSql仅接收本次结构检查生成的服务器常量条件，不接收请求参数。
    @Select("SELECT i.* FROM medical_record_item i "
            + "INNER JOIN medical_record r ON i.record_id = r.id "
            + "WHERE r.patient_id = #{patientId} AND i.item_code = #{itemCode} AND ${activeSql} "
            + "ORDER BY i.created_at DESC LIMIT 1")
    MedicalRecordItem selectLatestByPatientIdAndItemCode(@Param("patientId") Long patientId, @Param("itemCode") String itemCode, @Param("activeSql") String activeSql);

    /**
     * based onPatientID and indicatorNamequerylatest Examinationitem (through JOIN medical_record filterPatient)
     */
    // activeSql仅接收本次结构检查生成的服务器常量条件，不接收请求参数。
    @Select("SELECT i.* FROM medical_record_item i "
            + "INNER JOIN medical_record r ON i.record_id = r.id "
            + "WHERE r.patient_id = #{patientId} AND i.item_name = #{itemName} AND ${activeSql} "
            + "ORDER BY i.created_at DESC LIMIT 1")
    MedicalRecordItem selectLatestByPatientIdAndItemName(@Param("patientId") Long patientId, @Param("itemName") String itemName, @Param("activeSql") String activeSql);
}
