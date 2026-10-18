package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.dto.MedicalRecordImportDetail;
import org.familyhealthcare.dto.MedicalRecordImportDetail.ReadFailure;
import org.familyhealthcare.service.ClinicalImportFactsRepository;
import org.springframework.dao.DataAccessException;
import org.familyhealthcare.entity.MedicalRecordAttachment;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.MedicalRecordAttachmentMapper;
import org.familyhealthcare.mapper.MedicalRecordItemMapper;
import org.familyhealthcare.mapper.MedicalRecordMapper;
import org.familyhealthcare.service.HealthIndicatorService;
import org.familyhealthcare.service.MedicalRecordService;
import org.familyhealthcare.service.ClinicalImportFacts;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.MedicalRecordReviewContract;
import org.familyhealthcare.entity.MedicalRecordReviewAttachment;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Medical Recordsserviceimplement
 */
@Service
public class MedicalRecordServiceImpl extends ServiceImpl<MedicalRecordMapper, MedicalRecord>
        implements MedicalRecordService {

    @Autowired(required=false)
    private org.familyhealthcare.service.careplan.CarePlanAuthorizationService carePlanAuthorization;

    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection validity;

    @Autowired
    private MedicalRecordReviewAccess reviewAccess;

    @Autowired
    private org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService corrections;

    @Autowired
    private ClinicalImportFactsRepository factsRepository;

    @Autowired
    private MedicalRecordItemMapper itemMapper;

    @Autowired
    private MedicalRecordAttachmentMapper attachmentMapper;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private HealthIndicatorService healthIndicatorService;


    /** 关闭 IService 通用写路径；有目标的旧 update/delete 仍执行相同受控兼容矩阵。 */
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public boolean save(MedicalRecord record) { return saveRecordWithItems(record,record==null?null:record.getItems()); }
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public boolean removeById(java.io.Serializable id) { return deleteRecord(org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(id)); }
    @Override public boolean update(MedicalRecord entity, com.baomidou.mybatisplus.core.conditions.Wrapper<MedicalRecord> wrapper) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean remove(com.baomidou.mybatisplus.core.conditions.Wrapper<MedicalRecord> wrapper) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean removeByMap(Map<String,Object> values) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean removeByIds(java.util.Collection<?> ids) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean updateBatchById(java.util.Collection<MedicalRecord> records,int size) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean saveOrUpdate(MedicalRecord record) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean saveOrUpdateBatch(java.util.Collection<MedicalRecord> records,int size) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean saveBatch(java.util.Collection<MedicalRecord> records,int size) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }

    @Override public boolean removeById(MedicalRecord record) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean removeById(java.io.Serializable id,boolean fill) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean removeByIds(java.util.Collection<?> ids,boolean fill) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean removeBatchByIds(java.util.Collection<?> ids,int size) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public boolean removeBatchByIds(java.util.Collection<?> ids,int size,boolean fill) { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper<MedicalRecord> update() { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }
    @Override public com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<MedicalRecord> lambdaUpdate() { throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionService.versionedRequired(); }

    private void syncPatient(MedicalRecord record) {
        if (record.getPatientId() != null) {
            Patient patient = dataScopeHelper.requirePatient(record.getPatientId());
            record.setPatientName(patient.getName());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean saveRecordWithItems(MedicalRecord record, List<MedicalRecordItem> items) {
        corrections.requireCreateStorage();
        return saveNewRecord(record, items);
    }

    // 同一批创建只检查一次结构；每条记录仍逐一校验新身份，不能复用授权结论。
    private boolean saveNewRecord(MedicalRecord record, List<MedicalRecordItem> items) {
        org.familyhealthcare.service.medicalrecord.MedicalRecordLegacyInput.requireNewIdentity(record, items);
        if (record.getVerificationStatus() != null) throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.invalid();
        prepareNewRecord(record, items);
        boolean saved = super.save(record);
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

    private void prepareNewRecord(MedicalRecord record, List<MedicalRecordItem> items) {
        Long userId = dataScopeHelper.requireUserId();
        record.setUserId(userId);
        syncPatient(record);
        applySourceDefaults(record, items);
        record.setVerificationStatus("MANUAL".equals(record.getSourceType()) ? "VERIFIED" : "REVIEW_REQUIRED");
        record.setContentVersion(1L);
        record.setVerifiedBy(null);
        record.setVerifiedAt(null);
        record.setVerificationNote(null);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public boolean saveImportedRecordWithItem(MedicalRecord record, MedicalRecordItem item, ClinicalImportFacts facts) {
        if(item==null||record.getAttachments()!=null&&!record.getAttachments().isEmpty())throw new IllegalArgumentException("An imported observation must contain one item and no attachments.");
        org.familyhealthcare.service.medicalrecord.MedicalRecordLegacyInput.requireNewIdentity(record,Collections.singletonList(item));
        corrections.requireCreateStorage();
        facts.requireLabProjection(record,item);
        prepareNewRecord(record,Collections.singletonList(item));
        record.setId(null);
        // 先验证事实对应的原始字段，再复用原有卫生处理；缺失标准编码仍按旧逻辑解析。
        sanitizeMedicalRecordItem(item);
        if(baseMapper.insertImported(record,facts.toCanonicalJson())!=1)throw new IllegalStateException("The clinical import row could not be saved.");
        item.setId(null);item.setRecordId(record.getId());
        if(itemMapper.insert(item)!=1)throw new IllegalStateException("The clinical import item could not be saved.");
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean updateById(MedicalRecord record) {
        if (record == null || record.getId() == null) throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.invalid();
        MedicalRecord source = corrections.guardLegacy(record.getId());
        if (source == null) return false;
        try {boolean updated = super.updateById(record); corrections.recheck(source,false); return updated;}
        catch(RuntimeException error){throw ClinicalImportFacts.translateBinding(error);}
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean updateRecordWithItems(MedicalRecord record, List<MedicalRecordItem> items) {
        if (record == null || record.getId() == null) throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.invalid();
        MedicalRecord admission = corrections.guardLegacy(record.getId());
        MedicalRecord existing = baseMapper.selectForUpdate(record.getId());
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        record.setUserId(existing.getUserId());
        record.setContentVersion(existing.getContentVersion());
        syncPatient(record);
        if (record.getSourceType() == null) record.setSourceType(existing.getSourceType());
        if (record.getSourceExternalId() == null) record.setSourceExternalId(existing.getSourceExternalId());
        applySourceDefaults(record, items);
        record.setVerificationStatus(existing.getVerificationStatus());
        record.setVerifiedBy(existing.getVerifiedBy());
        record.setVerifiedAt(existing.getVerifiedAt());
        record.setVerificationNote(existing.getVerificationNote());
        boolean updated;
        try { updated = super.updateById(record); }
        catch(RuntimeException failure) { throw ClinicalImportFacts.translateBinding(failure); }
        if (updated && baseMapper.invalidateReview(record.getId(), existing.getContentVersion()) != 1)
            throw new MedicalRecordReviewContract.Conflict();
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
        corrections.recheck(admission,false);
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
        if(record!=null)validity.open().project(Collections.singletonList(record));
        return record;
    }

    @Override
    public MedicalRecord getRecordWithDetails(Long id, Long patientId) {
        if (patientId == null) return getRecordWithDetails(id);
        if (patientId <= 0 || id == null || id <= 0) throw new IllegalArgumentException("Invalid source selector.");
        requireRecordEvidence(patientId,id);
        MedicalRecord record = baseMapper.selectOne(new QueryWrapper<MedicalRecord>().eq("id",id).eq("patient_id",patientId));
        if (record == null) throw new IllegalStateException("Access denied to source record.");
        record.setItems(itemMapper.selectList(new QueryWrapper<MedicalRecordItem>().eq("record_id",id)));
        record.setAttachments(attachmentMapper.selectList(new QueryWrapper<MedicalRecordAttachment>().eq("record_id",id)));
        requireRecordEvidence(patientId,id);
        if(record!=null)validity.open().project(Collections.singletonList(record));
        return record;
    }
    private void requireRecordEvidence(long patientId,long id) {
        Long actor = CurrentUserUtil.getCurrentUserId();
        if (actor == null || carePlanAuthorization == null || !carePlanAuthorization.canReadEvidence(actor,patientId,"MEDICAL_RECORD",id))
            throw new IllegalStateException("Access denied to source record.");
    }

    // 三个公开入口分别由 Spring 代理开启内容快照；私有组装方法不承担事务开启。
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MedicalRecordImportDetail getImportDetails(Long id) {
        return readImportDetails(id, null, false, false);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MedicalRecordImportDetail getImportDetails(Long id, Long patientId) {
        if (patientId == null || patientId <= 0) throw ReadFailure.selector();
        return readImportDetails(id, patientId, true, false);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MedicalRecordImportDetail getImportReviewPreview(Long id) {
        return readImportDetails(id, null, false, true);
    }

    private MedicalRecordImportDetail readImportDetails(Long id, Long patient, boolean focused, boolean review) {
        if (id == null || id <= 0) throw ReadFailure.selector();
        Long actor = CurrentUserUtil.getCurrentUserId();
        if (actor == null || actor <= 0) throw ReadFailure.authentication();
        try {
            // 首次原生父记录读取确立快照；随后子项、附件和原始事实全部属于同一版内容。
            MedicalRecord record = baseMapper.selectById(id);
            if (record == null || focused && !java.util.Objects.equals(patient, record.getPatientId())) throw ReadFailure.denied();
            requireImportAccess(record, actor, patient, focused);
            record.setItems(itemMapper.selectList(new QueryWrapper<MedicalRecordItem>().eq("record_id", id)));
            record.setAttachments(review ? listAttachmentsMeta(id).stream().map(MedicalRecordReviewAttachment::new)
                    .collect(java.util.stream.Collectors.toList())
                    : attachmentMapper.selectList(new QueryWrapper<MedicalRecordAttachment>().eq("record_id", id)));
            Map<String,Object> original = factsRepository.readMedicalOriginal(record.getPatientId(), id);
            validity.open().project(Collections.singletonList(record));
            MedicalRecordImportDetail detail = new MedicalRecordImportDetail(record, original);
            // 独立 READ_COMMITTED 代理读取当前权限，不能复用刚才的内容快照或角色缓存。
            requireImportAccess(record, actor, patient, focused);
            return detail;
        } catch (ReadFailure failure) { throw failure;
        } catch (RuntimeException failure) { throw ClinicalImportFacts.Failure.unavailable(); }
    }

    private void requireImportAccess(MedicalRecord record, long actor, Long patient, boolean focused) {
        try {
            if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw ReadFailure.denied();
            if (focused) reviewAccess.requireCurrentEvidence(record, actor, patient);
            else reviewAccess.requireCurrent(record, false, false);
        } catch (DataAccessException failure) { throw ClinicalImportFacts.Failure.unavailable();
        } catch (IllegalStateException denied) { throw ReadFailure.denied(); }
    }

    @Override
    public List<MedicalRecord> listRecords(Long patientId, String patientName, String recordType, String timeValue) {
        return listRecords(patientId,patientName,recordType,timeValue,false);
    }
    @Override
    public List<MedicalRecord> listRecords(Long patientId,String patientName,String recordType,String timeValue,boolean includeInvalidated) {
        org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope view=validity.open();
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
            try {
                if (timeValue.length() == 10) {
                    qw.eq("record_date", java.time.LocalDate.parse(timeValue));
                } else {
                    java.time.YearMonth month = java.time.YearMonth.parse(timeValue);
                    qw.ge("record_date", month.atDay(1)).lt("record_date", month.plusMonths(1).atDay(1));
                }
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("zh".equals(org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage())
                        ? "请选择有效日期或月份。" : "Select a valid date or month.");
            }
        }

        if(!includeInvalidated)qw.apply(view.activeSql("medical_record."));
        List<MedicalRecord> records = super.list(qw);loadItems(records,null);view.project(records);
        return records;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean deleteRecord(Long id) {
        MedicalRecord existing = corrections.guardLegacy(org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(id));
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        // firstDeleteAttachment
        attachmentMapper.delete(new QueryWrapper<MedicalRecordAttachment>().eq("record_id", id));
        // DeleteExaminationitem (outsidekeylevelconnect, butexplicitDeletemoresafeall)
        itemMapper.delete(new QueryWrapper<MedicalRecordItem>().eq("record_id", id));
        // Deletemainrecord
        corrections.recheck(existing,false);
        return super.removeById(id);
    }

    @Override
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public List<Map<String,Object>> getItemTrend(Long patientId,String patientName,String itemName) {
        dataScopeHelper.requireUserId();QueryWrapper<MedicalRecord> query=new QueryWrapper<>();
        if(!CurrentUserUtil.isAdmin())dataScopeHelper.applyUserScope(query);
        if(patientId!=null)query.eq("patient_id",patientId);else if(patientName!=null&&!patientName.isEmpty())query.eq("patient_name",patientName);else return new ArrayList<>();
        org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope view=validity.open();
        query.apply(view.activeSql("r.")).like("i.item_name",itemName).orderByAsc("r.record_date","i.id");
        List<Map<String,Object>> result=itemMapper.selectTrendRows(query);view.projectRows(result,"recordId");
        java.util.Set<String> units=new java.util.HashSet<>();boolean unknownUnit=false;
        for(Map<String,Object> row:result){String unit=(String)row.get("unit");if(unit==null||unit.trim().isEmpty())unknownUnit=true;else units.add(unit);row.put("isAbnormal",org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.sourceFlag((Integer)row.get("isAbnormal")));}
        for(Map<String,Object> row:result)if(Boolean.TRUE.equals(row.get("managed"))) {
            BigDecimal number=org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.plotNumber((String)row.get("resultValue"));
            String reason=!"CURRENT".equals(row.get("sourceStatus"))?(String)row.get("sourceStatus"):unknownUnit?"UNIT_UNKNOWN":units.size()!=1?"UNIT_MIXED":number==null?"VALUE_NOT_PLOTTABLE":null;
            row.put("plotEligible",reason==null);row.put("numericValue",reason==null?number:null);row.put("plotReason",reason);
        }
        return result;
    }

    @Override public List<String> getAllItemNames(){return getAllItemNames(null);}
    @Override public List<String> getAllItemNames(Long patientId) {
        dataScopeHelper.requireUserId();if(patientId!=null)dataScopeHelper.requirePatientAccess(patientId,"MEDICAL",false);
        QueryWrapper<MedicalRecord> query=new QueryWrapper<>();if(!CurrentUserUtil.isAdmin())dataScopeHelper.applyUserScope(query);if(patientId!=null)query.eq("patient_id",patientId);
        query.apply(validity.open().activeSql("medical_record.")).isNotNull("i.item_name").ne("i.item_name","").orderByAsc("i.item_name");return itemMapper.selectActiveItemNames(query);
    }
    @Override public List<MedicalRecord> list() {
        dataScopeHelper.requireUserId();QueryWrapper<MedicalRecord> query=new QueryWrapper<>();if(!CurrentUserUtil.isAdmin())dataScopeHelper.applyUserScope(query);
        org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope view=validity.open();query.apply(view.activeSql("medical_record.")).orderByDesc("record_date");List<MedicalRecord> records=super.list(query);view.project(records);return records;
    }
    private void loadItems(List<MedicalRecord> records,String itemName) {
        for(int start=0;start<records.size();start+=100){List<MedicalRecord> part=records.subList(start,Math.min(start+100,records.size()));List<Long> ids=new ArrayList<>();Map<Long,MedicalRecord> parents=new HashMap<>();for(MedicalRecord row:part){ids.add(row.getId());parents.put(row.getId(),row);row.setItems(new ArrayList<>());}
            QueryWrapper<MedicalRecordItem> query=new QueryWrapper<MedicalRecordItem>().in("record_id",ids).orderByAsc("id");if(itemName!=null)query.like("item_name",itemName);
            for(MedicalRecordItem item:itemMapper.selectList(query)){MedicalRecord parent=parents.get(item.getRecordId());if(parent!=null)parent.getItems().add(item);}
        }
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
            attachmentMapper.insert(att);
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
                    attachmentMapper.insert(att);
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

    private void applySourceDefaults(MedicalRecord record, List<MedicalRecordItem> items) {
        boolean hasAiConfidence = items != null && items.stream().anyMatch(item -> item != null && item.getAiConfidence() != null);
        if (record.getSourceType() == null || record.getSourceType().trim().isEmpty()) {
            record.setSourceType(hasAiConfidence || record.getAiRawResult() != null ? "OCR" : "MANUAL");
        } else {
            record.setSourceType(record.getSourceType().trim().toUpperCase());
        }
        if (!java.util.Arrays.asList("MANUAL", "OCR", "FHIR", "DEVICE").contains(record.getSourceType())) {
            throw new IllegalArgumentException("Unsupported medical-record source type.");
        }
        if (record.getConfidenceScore() == null && items != null) {
            List<BigDecimal> scores = items.stream().filter(java.util.Objects::nonNull)
                    .map(MedicalRecordItem::getAiConfidence).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
            if (!scores.isEmpty()) {
                BigDecimal total = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                record.setConfidenceScore(total.divide(BigDecimal.valueOf(scores.size()), 4, RoundingMode.HALF_UP));
            }
        }
        if (record.getVerificationStatus() == null || record.getVerificationStatus().trim().isEmpty()) {
            record.setVerificationStatus("MANUAL".equals(record.getSourceType()) ? "VERIFIED" : "REVIEW_REQUIRED");
        }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MedicalRecord getReviewPreview(Long id) {
        MedicalRecord record = baseMapper.selectById(id);
        if (record == null) throw new IllegalArgumentException("The record does not exist.");
        reviewAccess.requireCurrent(record, false, false);
        record.setItems(itemMapper.selectList(new QueryWrapper<MedicalRecordItem>().eq("record_id", id)));
        record.setAttachments(listAttachmentsMeta(id).stream().map(MedicalRecordReviewAttachment::new)
                .collect(java.util.stream.Collectors.toList()));
        // Re-read the actual current source binding and authority outside this content snapshot.
        reviewAccess.requireCurrent(record, false, false);
        if(record!=null)validity.open().project(Collections.singletonList(record));
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean reviewRecord(Long id, boolean approved, String note, Long expectedContentVersion) {
        return reviewLocked(id, approved, note, expectedContentVersion, false) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long reviewRecordForDoctor(Long id, boolean approved, String note, Long expectedContentVersion) {
        MedicalRecord reviewed = reviewLocked(id, approved, note, expectedContentVersion, true);
        if (reviewed == null) throw new IllegalArgumentException("The record does not exist.");
        return reviewed.getPatientId();
    }

    private MedicalRecord reviewLocked(Long id, boolean approved, String note, Long version, boolean workspace) {
        MedicalRecordReviewContract.requireVersion(version);
        MedicalRecord record = corrections.guardReview(org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(id),workspace,approved);
        if (record == null) return null;
        if (baseMapper.reviewVersion(id, version, approved ? "VERIFIED" : "REJECTED",
                dataScopeHelper.requireUserId(), LocalDateTime.now(), note) != 1)
            throw new MedicalRecordReviewContract.Conflict();
        corrections.recheck(record,workspace);
        if(record!=null)validity.open().project(Collections.singletonList(record));
        return record;
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
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int saveRecordsBatch(List<MedicalRecord> records, List<List<MedicalRecordItem>> itemsList,
                                List<MedicalRecordAttachment> sharedAttachments) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        corrections.requireCreateStorage();
        int saved = 0;
        for (int i = 0; i < records.size(); i++) {
            MedicalRecord record = records.get(i);
            List<MedicalRecordItem> items = itemsList != null && i < itemsList.size()
                    ? itemsList.get(i) : Collections.emptyList();
            if (i == 0 && sharedAttachments != null && !sharedAttachments.isEmpty()) {
                record.setAttachments(sharedAttachments);
            }
            if (saveNewRecord(record, items)) {
                saved++;
            }
        }
        return saved;
    }
}
