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

    boolean saveImportedRecordWithItem(MedicalRecord record, MedicalRecordItem item, ClinicalImportFacts facts);

    /**
     * updaterecordanditsExaminationitem
     */
    boolean updateRecordWithItems(MedicalRecord record, List<MedicalRecordItem> items);

    /**
     * based onIDqueryrecord (containExamination item details)
     */
    MedicalRecord getRecordWithDetails(Long id);
    MedicalRecord getRecordWithDetails(Long id, Long patientId);

    /**
     * queryRecord List
     */
    List<MedicalRecord> listRecords(Long patientId, String patientName, String recordType, String timeValue);
    List<MedicalRecord> listRecords(Long patientId, String patientName, String recordType, String timeValue, boolean includeInvalidated);

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

    MedicalRecord getReviewPreview(Long id);

    org.familyhealthcare.dto.MedicalRecordImportDetail getImportDetails(Long id);
    org.familyhealthcare.dto.MedicalRecordImportDetail getImportDetails(Long id, Long patientId);
    org.familyhealthcare.dto.MedicalRecordImportDetail getImportReviewPreview(Long id);


    boolean reviewRecord(Long id, boolean approved, String note, Long expectedContentVersion);

    Long reviewRecordForDoctor(Long id, boolean approved, String note, Long expectedContentVersion);
}
