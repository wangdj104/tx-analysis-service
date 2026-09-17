package org.familyhealthcare.service;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordAttachment;
import org.familyhealthcare.entity.MedicalRecordItem;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

import java.util.Map;

/**
 * Medical Recordsservice interface
 */
public interface MedicalRecordService extends IService<MedicalRecord> {

    /**
     * SaverecordanditsExaminationitem
     */
    boolean saveRecordWithItems(MedicalRecord record, List<MedicalRecordItem> items);

    /**
     * updaterecordanditsExaminationitem
     */
    boolean updateRecordWithItems(MedicalRecord record, List<MedicalRecordItem> items);

    /**
     * based onIDqueryrecord (containExamination item details)
     */
    MedicalRecord getRecordWithDetails(Long id);

    /**
     * queryRecord List
     */
    List<MedicalRecord> listRecords(Long patientId, String patientName, String recordType, String timeValue);

    /**
     * Deleterecord (levelconnectDeleteExaminationitem and Attachment)
     */
    boolean deleteRecord(Long id);

    /**
     * queryspecifiedPatientsomeitemExaminationindicator historytrend
     */
    List<Map<String, Object>> getItemTrend(Long patientId, String patientName, String itemName);

    /**
     * queryhas not same ExaminationitemName
     */
    List<String> getAllItemNames();

    /**
     * queryspecifiedPatient ExaminationitemName
     */
    List<String> getAllItemNames(Long patientId);

    /**
     * batchSavemultiplecopyMedical Records (for example  OCR by typesplitafter  multipleitemsrecord)
     */
    int saveRecordsBatch(List<MedicalRecord> records, List<List<MedicalRecordItem>> itemsList,
                         List<MedicalRecordAttachment> sharedAttachments);
}