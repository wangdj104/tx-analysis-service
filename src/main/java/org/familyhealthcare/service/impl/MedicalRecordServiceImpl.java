package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.entity.MedicalRecordAttachment;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.MedicalRecordAttachmentMapper;
import org.familyhealthcare.mapper.MedicalRecordItemMapper;
import org.familyhealthcare.mapper.MedicalRecordMapper;
import org.familyhealthcare.service.HealthIndicatorService;
import org.familyhealthcare.service.MedicalRecordService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Medical Recordsserviceimplement
 */
@Service
public class MedicalRecordServiceImpl extends ServiceImpl<MedicalRecordMapper, MedicalRecord>
        implements MedicalRecordService {

    @Autowired
    private MedicalRecordItemMapper itemMapper;

    @Autowired
    private MedicalRecordAttachmentMapper attachmentMapper;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private HealthIndicatorService healthIndicatorService;

    private void syncPatient(MedicalRecord record) {
        if (record.getPatientId() != null) {
            Patient patient = dataScopeHelper.requirePatient(record.getPatientId());
            record.setPatientName(patient.getName());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveRecordWithItems(MedicalRecord record, List<MedicalRecordItem> items) {
        Long userId = dataScopeHelper.requireUserId();
        record.setUserId(userId);
        syncPatient(record);
        boolean saved = this.save(record);
        if (saved && items != null && !items.isEmpty()) {
            for (MedicalRecordItem item : items) {
                item.setRecordId(record.getId());
                sanitizeMedicalRecordItem(item);
                itemMapper.insert(item);
            }
        }
        // SaveAttachment
        if (saved) {
            saveAttachments(record.getId(), record.getAttachments());
        }
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateRecordWithItems(MedicalRecord record, List<MedicalRecordItem> items) {
        MedicalRecord existing = this.getById(record.getId());
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        syncPatient(record);
        boolean updated = this.updateById(record);
        if (updated) {
            // Deletelegacy Examinationitem
            itemMapper.delete(new QueryWrapper<MedicalRecordItem>().eq("record_id", record.getId()));
            // insertenternew Examinationitem
            if (items != null && !items.isEmpty()) {
                for (MedicalRecordItem item : items) {
                    item.setRecordId(record.getId());
                    sanitizeMedicalRecordItem(item);
                    itemMapper.insert(item);
                }
            }
            // updateAttachment: keepalready has  , Addnew , Deletebefore endnot transmit
            updateAttachments(record.getId(), record.getAttachments());
        }
        return updated;
    }

    @Override
    public MedicalRecord getRecordWithDetails(Long id) {
        MedicalRecord record = this.getById(id);
        if (record != null) {
            dataScopeHelper.requirePatientOrOwner(record.getPatientId(), record.getUserId());
            // queryExamination item details
            List<MedicalRecordItem> items = itemMapper.selectList(
                    new QueryWrapper<MedicalRecordItem>().eq("record_id", id));
            record.setItems(items);

            // queryAttachment (include file_content, providebefore enddisplayimage)
            List<MedicalRecordAttachment> attachments = attachmentMapper.selectList(
                    new QueryWrapper<MedicalRecordAttachment>().eq("record_id", id));
            record.setAttachments(attachments);
        }
        return record;
    }

    @Override
    public List<MedicalRecord> listRecords(Long patientId, String patientName, String recordType, String timeValue) {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<MedicalRecord> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.orderByDesc("record_date");

        if (patientId != null) {
            qw.eq("patient_id", patientId);
        } else if (patientName != null && !patientName.isEmpty()) {
            qw.eq("patient_name", patientName);
        }
        if (recordType != null && !recordType.isEmpty()) {
            qw.eq("record_type", recordType);
        }
        if (timeValue != null && !timeValue.isEmpty()) {
            qw.apply("DATE_FORMAT(record_date, '%Y-%m') = {0}", timeValue);
        }

        List<MedicalRecord> records = this.list(qw);
        // populateExamination item details
        for (MedicalRecord record : records) {
            List<MedicalRecordItem> items = itemMapper.selectList(
                    new QueryWrapper<MedicalRecordItem>().eq("record_id", record.getId()));
            record.setItems(items);
        }
        return records;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRecord(Long id) {
        MedicalRecord existing = this.getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        // firstDeleteAttachment
        attachmentMapper.delete(new QueryWrapper<MedicalRecordAttachment>().eq("record_id", id));
        // DeleteExaminationitem (outsidekeylevelconnect, butexplicitDeletemoresafeall)
        itemMapper.delete(new QueryWrapper<MedicalRecordItem>().eq("record_id", id));
        // Deletemainrecord
        return this.removeById(id);
    }

    @Override
    public List<Map<String, Object>> getItemTrend(Long patientId, String patientName, String itemName) {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<MedicalRecord> recordQw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(recordQw);
        }
        if (patientId != null) {
            recordQw.eq("patient_id", patientId);
        } else if (patientName != null && !patientName.isEmpty()) {
            recordQw.eq("patient_name", patientName);
        } else {
            return new ArrayList<>();
        }
        recordQw.orderByAsc("record_date");
        List<MedicalRecord> records = this.list(recordQw);

        List<Map<String, Object>> result = new ArrayList<>();
        for (MedicalRecord record : records) {
            QueryWrapper<MedicalRecordItem> itemQw = new QueryWrapper<>();
            itemQw.eq("record_id", record.getId());
            itemQw.like("item_name", itemName);
            List<MedicalRecordItem> items = itemMapper.selectList(itemQw);
            for (MedicalRecordItem item : items) {
                Map<String, Object> map = new HashMap<>();
                map.put("recordDate", record.getRecordDate());
                map.put("itemName", item.getItemName());
                map.put("resultValue", item.getResultValue());
                map.put("unit", item.getUnit());
                map.put("referenceRange", item.getReferenceRange());
                map.put("isAbnormal", item.getIsAbnormal());
                map.put("hospitalName", record.getHospitalName());
                result.add(map);
            }
        }
        return result;
    }

    @Override
    public List<String> getAllItemNames() {
        if (CurrentUserUtil.isAdmin()) {
            return itemMapper.selectDistinctItemNames();
        }
        List<Long> recordIds = this.list().stream().map(MedicalRecord::getId).collect(java.util.stream.Collectors.toList());
        if (recordIds.isEmpty()) {
            return new ArrayList<>();
        }
        return itemMapper.selectDistinctItemNamesByRecordIds(recordIds);
    }

    @Override
    public List<String> getAllItemNames(Long patientId) {
        if (patientId == null) {
            return getAllItemNames();
        }
        return itemMapper.selectDistinctItemNamesByPatientId(patientId);
    }

    @Override
    public List<MedicalRecord> list() {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<MedicalRecord> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.orderByDesc("record_date");
        return super.list(qw);
    }

    private void saveAttachments(Long recordId, List<MedicalRecordAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        for (MedicalRecordAttachment att : attachments) {
            if (att.getFileContent() == null || att.getFileContent().isEmpty()) {
                continue;
            }
            att.setRecordId(recordId);
            fillAttachmentDefaults(att, recordId);
            try {
                attachmentMapper.insert(att);
            } catch (Exception e) {
                // databasetableNone file_content columntimeonlySaveelementdata
                att.setFileContent(null);
                attachmentMapper.insert(att);
            }
        }
    }

    private void updateAttachments(Long recordId, List<MedicalRecordAttachment> newAttachments) {
        // collectbefore endkeep already has Attachment id
        List<Long> keepIds = new ArrayList<>();
        if (newAttachments != null) {
            for (MedicalRecordAttachment att : newAttachments) {
                if (att.getId() != null) {
                    keepIds.add(att.getId());
                }
            }
        }
        // Deletenot in keeplistin legacyAttachment
        QueryWrapper<MedicalRecordAttachment> deleteQw = new QueryWrapper<>();
        deleteQw.eq("record_id", recordId);
        if (!keepIds.isEmpty()) {
            deleteQw.notIn("id", keepIds);
        }
        attachmentMapper.delete(deleteQw);

        // SavenewAttachment (nohas  id andbringhas  fileContent  )
        if (newAttachments != null) {
            for (MedicalRecordAttachment att : newAttachments) {
                if (att.getId() == null && att.getFileContent() != null && !att.getFileContent().isEmpty()) {
                    att.setRecordId(recordId);
                    fillAttachmentDefaults(att, recordId);
                    try {
                        attachmentMapper.insert(att);
                    } catch (Exception e) {
                        att.setFileContent(null);
                        attachmentMapper.insert(att);
                    }
                }
            }
        }
    }

    private void fillAttachmentDefaults(MedicalRecordAttachment att, Long recordId) {
        if (att.getFileType() == null || att.getFileType().isEmpty()) {
            att.setFileType("IMAGE");
        }
        if (att.getFilePath() == null || att.getFilePath().isEmpty()) {
            String name = att.getFileName() != null && !att.getFileName().isEmpty()
                    ? att.getFileName() : "attachment";
            att.setFilePath("inline://" + recordId + "/" + name);
        }
    }

    private void sanitizeMedicalRecordItem(MedicalRecordItem item) {
        if (item == null) {
            return;
        }
        item.setItemName(truncateField(item.getItemName(), 200));
        item.setResultValue(truncateField(item.getResultValue(), 500));
        item.setUnit(truncateField(item.getUnit(), 50));
        item.setReferenceRange(truncateField(item.getReferenceRange(), 200));
        // Automaticbased onindicatorNameparseandpopulatestandard item_code
        if (item.getItemCode() == null || item.getItemCode().isEmpty()) {
            String resolvedCode = healthIndicatorService.resolveName(item.getItemName());
            if (resolvedCode != null) {
                item.setItemCode(resolvedCode);
            }
        }
    }

    private String truncateField(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen);
    }

    /** list/DetailsqueryAttachmentelementdata, not loadlargefield file_content */
    private List<MedicalRecordAttachment> listAttachmentsMeta(Long recordId) {
        return attachmentMapper.selectList(
                new QueryWrapper<MedicalRecordAttachment>()
                        .eq("record_id", recordId)
                        .select("id", "record_id", "file_path", "file_name", "file_type", "file_size", "created_at"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveRecordsBatch(List<MedicalRecord> records, List<List<MedicalRecordItem>> itemsList,
                                List<MedicalRecordAttachment> sharedAttachments) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (int i = 0; i < records.size(); i++) {
            MedicalRecord record = records.get(i);
            List<MedicalRecordItem> items = itemsList != null && i < itemsList.size()
                    ? itemsList.get(i) : Collections.emptyList();
            if (i == 0 && sharedAttachments != null && !sharedAttachments.isEmpty()) {
                record.setAttachments(sharedAttachments);
            }
            if (saveRecordWithItems(record, items)) {
                saved++;
            }
        }
        return saved;
    }
}