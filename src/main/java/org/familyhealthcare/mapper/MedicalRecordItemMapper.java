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
    @Select("SELECT i.* FROM medical_record_item i "
            + "INNER JOIN medical_record r ON i.record_id = r.id "
            + "WHERE r.patient_id = #{patientId} AND i.item_code = #{itemCode} "
            + "ORDER BY i.created_at DESC LIMIT 1")
    MedicalRecordItem selectLatestByPatientIdAndItemCode(@Param("patientId") Long patientId, @Param("itemCode") String itemCode);

    /**
     * based onPatientID and indicatorNamequerylatest Examinationitem (through JOIN medical_record filterPatient)
     */
    @Select("SELECT i.* FROM medical_record_item i "
            + "INNER JOIN medical_record r ON i.record_id = r.id "
            + "WHERE r.patient_id = #{patientId} AND i.item_name = #{itemName} "
            + "ORDER BY i.created_at DESC LIMIT 1")
    MedicalRecordItem selectLatestByPatientIdAndItemName(@Param("patientId") Long patientId, @Param("itemName") String itemName);
}
