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

import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State;
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
    private org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle nativeLifecycle;

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
        try (org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle.Scope scope=nativeLifecycle.openGenericUpdate()) {
            try {
                MedicalRecord source=corrections.guardLegacy(record.getId());
                if(source==null)return false;
                scope.preflight(source);scope.arm();
                boolean updated;
                try {updated=super.updateById(record);}catch(RuntimeException failure){throw ClinicalImportFacts.translateBinding(failure);}
                scope.checkpoint();corrections.recheck(source,false);scope.complete();return updated;
            } catch(Throwable failure){throw scope.failure(failure);}
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean updateRecordWithItems(MedicalRecord record, List<MedicalRecordItem> items) {
        if (record == null || record.getId() == null) throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.invalid();
        try (org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle.Scope scope=nativeLifecycle.openFullEdit()) {
            try {
        MedicalRecord admission = corrections.guardLegacy(record.getId());
        MedicalRecord existing = baseMapper.selectForUpdate(record.getId());
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        scope.preflight(admission);
        if(scope.participating())return updateParticipating(record,items,existing,admission,scope);
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
        scope.arm();
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
        scope.checkpoint();corrections.recheck(admission,false);
        if(updated)scope.retire();
        scope.complete();return updated;
            } catch(Throwable failure){throw scope.failure(failure);}
        }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MedicalRecord getRecordWithDetails(Long id) {
        try(ReadContent read=new ReadContent(readActorId(),false)) {
            MedicalRecord record=this.getById(id);
            read.select(record==null?Collections.emptyList():Collections.singletonList(record));
            if(record!=null)dataScopeHelper.requirePatientOrOwner(record.getPatientId(),record.getUserId());
            read.prepare(null);
            if(record!=null) {
                record.setItems(itemMapper.selectList(new QueryWrapper<MedicalRecordItem>().eq("record_id",id)));
                record.setAttachments(attachmentMapper.selectList(new QueryWrapper<MedicalRecordAttachment>().eq("record_id",id)));
            }
            read.project();read.finish();return record;
        }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MedicalRecord getRecordWithDetails(Long id, Long patientId) {
        if(patientId==null)return getRecordWithDetails(id);
        if(patientId<=0||id==null||id<=0)throw new IllegalArgumentException("Invalid source selector.");
        try(ReadContent read=new ReadContent(readActorId(),false)) {
            requireRecordEvidence(patientId,id);
            MedicalRecord record=baseMapper.selectOne(new QueryWrapper<MedicalRecord>().eq("id",id).eq("patient_id",patientId));
            if(record==null)throw new IllegalStateException("Access denied to source record.");
            read.select(Collections.singletonList(record));read.prepare(patientId);
            record.setItems(itemMapper.selectList(new QueryWrapper<MedicalRecordItem>().eq("record_id",id)));
            record.setAttachments(attachmentMapper.selectList(new QueryWrapper<MedicalRecordAttachment>().eq("record_id",id)));
            requireRecordEvidence(patientId,id);read.project();read.finish();return record;
        }
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
        if(id==null||id<=0)throw ReadFailure.selector();
        Long actor=CurrentUserUtil.getCurrentUserId();
        if(actor==null||actor<=0)throw ReadFailure.authentication();
        try(ReadContent read=new ReadContent(actor,true)) {
            MedicalRecord record=baseMapper.selectById(id);
            if(record!=null)read.select(Collections.singletonList(record));
            if(record==null||focused&&!java.util.Objects.equals(patient,record.getPatientId()))throw ReadFailure.denied();
            read.prepare(focused?patient:null);
            record.setItems(itemMapper.selectList(new QueryWrapper<MedicalRecordItem>().eq("record_id",id)));
            record.setAttachments(review?listAttachmentsMeta(id).stream().map(MedicalRecordReviewAttachment::new)
                    .collect(java.util.stream.Collectors.toList()):attachmentMapper.selectList(new QueryWrapper<MedicalRecordAttachment>().eq("record_id",id)));
            Map<String,Object> original=factsRepository.readMedicalOriginal(record.getPatientId(),id);
            read.project();MedicalRecordImportDetail detail=new MedicalRecordImportDetail(record,original);
            read.finish();return detail;
        }catch(ReadFailure failure){throw failure;
        }catch(RuntimeException failure){throw ClinicalImportFacts.Failure.unavailable();}
    }

    private void requireImportAccess(MedicalRecord record, long actor, Long patient, boolean focused) {
        try {
            if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw ReadFailure.denied();
            if (focused) reviewAccess.requireEvidenceAdmission(record, actor, patient);
            else reviewAccess.requireReadAdmission(Collections.singletonList(record), actor);
        } catch (DataAccessException failure) { throw ClinicalImportFacts.Failure.unavailable();
        } catch (IllegalStateException denied) { throw ReadFailure.denied(); }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public List<MedicalRecord> listRecords(Long patientId, String patientName, String recordType, String timeValue) {
        return listRecords(patientId,patientName,recordType,timeValue,false);
    }
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public List<MedicalRecord> listRecords(Long patientId,String patientName,String recordType,String timeValue,boolean includeInvalidated) {
        try(ReadContent read=new ReadContent(readActorId(),false)) {
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
        List<MedicalRecord> records=super.list(qw);read.select(records);read.prepare(null);
        loadItems(records,null);read.project();read.finish();return records;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean deleteRecord(Long id) {
        long recordId=org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(id);
        try (org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle.Scope scope=nativeLifecycle.openDelete()) {
            try {
                MedicalRecord existing=corrections.guardLegacy(recordId);if(existing==null)return false;
                dataScopeHelper.requirePatientOrOwner(existing.getPatientId(),existing.getUserId());
                scope.preflight(existing);scope.eraseReview();
                scope.deletedAttachments(attachmentMapper.delete(new QueryWrapper<MedicalRecordAttachment>().eq("record_id",recordId)));
                scope.deletedItems(itemMapper.delete(new QueryWrapper<MedicalRecordItem>().eq("record_id",recordId)));
                corrections.recheck(existing,false);scope.checkpoint();
                scope.deletedNative(baseMapper.deleteById(recordId));scope.complete();return true;
            }catch(Throwable failure){throw scope.failure(failure);}
        }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> getItemTrend(Long patientId,String patientName,String itemName) {
        try(ReadContent read=new ReadContent(readActorId(),false)) {
            QueryWrapper<MedicalRecord> query=new QueryWrapper<>();
            if(!CurrentUserUtil.isAdmin())dataScopeHelper.applyUserScope(query);
            List<Map<String,Object>> result;
            if(patientId==null&&(patientName==null||patientName.isEmpty()))result=new ArrayList<>();
            else {
                if(patientId!=null)query.eq("patient_id",patientId);else query.eq("patient_name",patientName);
                org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope view=validity.open();
                query.apply(view.activeSql("r.")).like("i.item_name",itemName).orderByAsc("r.record_date","i.id");
                result=itemMapper.selectTrendRows(query);
            }
            read.trend=result;read.select(trendSources(result,read));read.prepare(null);
            Map<Long,State> current=read.currentFrame();
            java.util.Set<String> units=new java.util.HashSet<>();boolean unknownUnit=false;
            for(Map<String,Object> row:result) {
                String unit=(String)row.get("unit");if(unit==null||unit.trim().isEmpty())unknownUnit=true;else units.add(unit);
                row.put("isAbnormal",org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.sourceFlag((Integer)row.get("isAbnormal")));
            }
            for(Map<String,Object> row:result) {
                State state=current.get((Long)row.get("recordId"));
                row.put("managed",state.managed);row.put("validity",state.validity);row.put("sourceStatus",state.status());
                if(state.managed||state.reviewParticipating) {
                    BigDecimal number=org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.plotNumber((String)row.get("resultValue"));
                    String reason=!state.active()||!state.eligible()?state.status():unknownUnit?"UNIT_UNKNOWN":units.size()!=1?"UNIT_MIXED":number==null?"VALUE_NOT_PLOTTABLE":null;
                    row.put("plotEligible",reason==null);row.put("numericValue",reason==null?number:null);row.put("plotReason",reason);
                }
            }
            read.finish();return result;
        }
    }

    @Override public List<String> getAllItemNames(){return getAllItemNames(null);}
    @Override public List<String> getAllItemNames(Long patientId) {
        dataScopeHelper.requireUserId();if(patientId!=null)dataScopeHelper.requirePatientAccess(patientId,"MEDICAL",false);
        QueryWrapper<MedicalRecord> query=new QueryWrapper<>();if(!CurrentUserUtil.isAdmin())dataScopeHelper.applyUserScope(query);if(patientId!=null)query.eq("patient_id",patientId);
        query.apply(validity.open().activeSql("medical_record.")).isNotNull("i.item_name").ne("i.item_name","").orderByAsc("i.item_name");return itemMapper.selectActiveItemNames(query);
    }
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public List<MedicalRecord> list() {
        try(ReadContent read=new ReadContent(readActorId(),false)) {
            QueryWrapper<MedicalRecord> query=new QueryWrapper<>();if(!CurrentUserUtil.isAdmin())dataScopeHelper.applyUserScope(query);
            org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.Scope view=validity.open();
            query.apply(view.activeSql("medical_record.")).orderByDesc("record_date");
            List<MedicalRecord> records=super.list(query);read.select(records);read.prepare(null);read.project();read.finish();return records;
        }
    }
    @Autowired private org.springframework.jdbc.core.JdbcTemplate readJdbc;
    @Autowired private org.springframework.jdbc.datasource.DataSourceTransactionManager readTransactions;
    @Autowired private org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader readQualification;
    @Autowired private org.apache.ibatis.session.SqlSessionFactory readSqlSessions;

    /** A single call owns all selected content, both complete current frames and failure cleanup. */
    private final class ReadContent implements AutoCloseable {
        final long actor;final boolean importing;final ContentResource resource;
        List<MedicalRecord> records=Collections.emptyList(),sources=Collections.emptyList();
        List<Map<String,Object>> trend=Collections.emptyList();
        Map<Long,State> snapshot;Long patient;boolean released;
        ReadContent(long actor,boolean importing) {
            this.actor=actor;this.importing=importing;checkActor();resource=new ContentResource();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)clear();}
            });
        }
        void select(List<MedicalRecord> selected) {
            if(selected==null)throw currentChanged();
            records=Collections.unmodifiableList(new ArrayList<>(selected));clear();
            java.util.LinkedHashMap<Long,MedicalRecord> unique=new java.util.LinkedHashMap<>();
            for(MedicalRecord record:records) {
                if(record==null||!readSafeId(record.getId())||!readSafeId(record.getContentVersion())
                        ||record.getPatientId()!=null&&!readSafeId(record.getPatientId())||record.getUserId()!=null&&!readSafeId(record.getUserId()))throw currentChanged();
                MedicalRecord previous=unique.putIfAbsent(record.getId(),record);
                if(previous!=null&&(!java.util.Objects.equals(previous.getPatientId(),record.getPatientId())
                        ||!java.util.Objects.equals(previous.getUserId(),record.getUserId())
                        ||!java.util.Objects.equals(previous.getContentVersion(),record.getContentVersion())
                        ||!java.util.Objects.equals(previous.getVerificationStatus(),record.getVerificationStatus())))throw currentChanged();
            }
            sources=Collections.unmodifiableList(new ArrayList<>(unique.values()));check();
        }
        void prepare(Long focusedPatient) {
            patient=focusedPatient;check();
            if(patient!=null&&sources.size()!=1)throw currentChanged();
            if(importing)requireImportAccess(sources.get(0),actor,patient,patient!=null);
            else if(patient==null)reviewAccess.requireReadAdmission(sources,actor);
            else reviewAccess.requireEvidenceAdmission(sources.get(0),actor,patient);
            check();Map<Long,State> expected=new java.util.LinkedHashMap<>();
            if(sources.isEmpty()) {
                Map<Long,State> empty=observeSnapshot(Collections.emptyList());check();
                if(empty==null||!empty.isEmpty())throw currentChanged();
            }else for(int offset=0;offset<sources.size();offset+=100) {
                check();List<MedicalRecord> part=sources.subList(offset,Math.min(offset+100,sources.size()));List<Long> ids=new ArrayList<>();
                for(MedicalRecord record:part)ids.add(record.getId());
                Map<Long,State> observed=observeSnapshot(ids);check();
                if(observed==null||observed.size()!=part.size()||!observed.keySet().equals(new java.util.LinkedHashSet<>(ids)))throw currentChanged();
                for(MedicalRecord record:part) {
                    State state=observed.get(record.getId());
                    if(state==null||!state.reviewObserved||state.signature==null||state.checkedAt==null
                            ||state.qualificationSemantics!=org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT
                            ||state.id!=record.getId()||!java.util.Objects.equals(state.patientId,record.getPatientId())
                            ||!java.util.Objects.equals(state.ownerId,record.getUserId())||!java.util.Objects.equals(state.version,record.getContentVersion())
                            ||!java.util.Objects.equals(state.review,record.getVerificationStatus()))throw currentChanged();
                    expected.put(record.getId(),state);
                }
            }
            snapshot=Collections.unmodifiableMap(expected);check();
        }
        Map<Long,State> observeSnapshot(java.util.Collection<Long> ids) {
            try {return validity.open().qualifiedSnapshot(ids);}
            catch(org.familyhealthcare.service.workflow.WorkflowException failure) {
                // Preserve genuine reader failures, but do not misdiagnose lost content resources as schema.
                check();throw failure;
            }
        }
        Map<Long,State> currentFrame() {
            check();Map<Long,State> current;
            try {
                current=patient==null?reviewAccess.currentReadFrame(sources,snapshot,actor)
                        :Collections.singletonMap(sources.get(0).getId(),reviewAccess.currentEvidenceFrame(sources.get(0),snapshot.get(sources.get(0).getId()),actor,patient));
            }catch(IllegalStateException denied){if(importing)throw ReadFailure.denied();throw denied;}
            check();return current;
        }
        void project() {
            Map<Long,State> current=currentFrame();
            for(MedicalRecord record:records) {
                State state=current.get(record.getId());
                record.setManaged(state.managed);record.setValidity(state.validity);record.setSourceStatus(state.status());
                org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.projectReviewSummary(record,state);
            }
        }
        void finish() {currentFrame();check();released=true;}
        void check() {checkActor();resource.verify();}
        void checkActor() {
            if(!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) {
                if(importing)throw ReadFailure.denied();throw new IllegalStateException("Access denied to source record.");
            }
        }
        void clear() {
            for(MedicalRecord record:records)if(record!=null)record.setReviewSummary(null);
            for(Map<String,Object> row:trend)if(row!=null){row.remove("plotEligible");row.remove("numericValue");row.remove("plotReason");}
        }
        @Override public void close(){if(!released)clear();}
    }

    private final class ContentResource {
        final Thread thread=Thread.currentThread();
        final org.springframework.jdbc.datasource.ConnectionHolder holder;
        final org.springframework.jdbc.datasource.SimpleConnectionHandle handle;final java.sql.Connection connection;
        ContentResource() {
            readGraph();Object value=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(readJdbc.getDataSource());
            if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    ||!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    ||value==null||value.getClass()!=org.springframework.jdbc.datasource.ConnectionHolder.class)throw currentUnavailable();
            holder=(org.springframework.jdbc.datasource.ConnectionHolder)value;
            if(!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()==null
                    ||holder.getConnectionHandle().getClass()!=org.springframework.jdbc.datasource.SimpleConnectionHandle.class)throw currentUnavailable();
            handle=(org.springframework.jdbc.datasource.SimpleConnectionHandle)holder.getConnectionHandle();connection=handle.getConnection();verify();
        }
        void verify() {
            readGraph();
            if(Thread.currentThread()!=thread||!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    ||!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    ||!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||org.springframework.transaction.support.TransactionSynchronizationManager.getResource(readJdbc.getDataSource())!=holder
                    ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||holder.getConnectionHandle()!=handle||handle.getConnection()!=connection)throw currentUnavailable();
            try {
                if(connection==null||holder.getConnection()!=connection||connection.isClosed()||connection.getAutoCommit()
                        ||connection.getTransactionIsolation()!=java.sql.Connection.TRANSACTION_REPEATABLE_READ)throw currentUnavailable();
            }catch(java.sql.SQLException|IllegalStateException failure){throw currentUnavailable();}
        }
    }
    private void readGraph() {
        try {
            if(readJdbc==null||readJdbc.getDataSource()==null||readTransactions==null||readTransactions.getDataSource()!=readJdbc.getDataSource()
                    ||readQualification==null||reviewAccess==null||readSqlSessions==null
                    ||readSqlSessions.getConfiguration().getEnvironment().getDataSource()!=readJdbc.getDataSource()
                    ||!(readSqlSessions.getConfiguration().getEnvironment().getTransactionFactory() instanceof org.mybatis.spring.transaction.SpringManagedTransactionFactory))throw currentUnavailable();
            reviewAccess.requireDataSource(readJdbc.getDataSource());readQualification.requireDataSource(readJdbc.getDataSource());
        }catch(RuntimeException failure){throw currentUnavailable();}
    }
    private static boolean readSafeId(Long value){return value!=null&&value>0&&value<=9007199254740991L;}
    private long readActorId() {
        Long actor=dataScopeHelper.requireUserId();
        if(!readSafeId(actor))throw new IllegalStateException("Access denied to source record.");return actor;
    }
    private static org.familyhealthcare.service.workflow.WorkflowException currentUnavailable(){return new org.familyhealthcare.service.workflow.WorkflowException(503,"CURRENT_RESULT_UNAVAILABLE","The current medical record result could not be verified.");}
    private static org.familyhealthcare.service.workflow.WorkflowException currentChanged(){return new org.familyhealthcare.service.workflow.WorkflowException(409,"CURRENT_RESULT_UNAVAILABLE","The current medical record result could not be verified.");}

    /** Native trend binding comes only from the same RR source headers, never the request selector. */
    private List<MedicalRecord> trendSources(List<Map<String,Object>> rows,ReadContent read) {
        java.util.LinkedHashSet<Long> unique=new java.util.LinkedHashSet<>();
        for(Map<String,Object> row:rows) {
            Object id=row.get("recordId");if(!(id instanceof Long)||!readSafeId((Long)id))throw currentChanged();unique.add((Long)id);
        }
        List<Long> ids=new ArrayList<>(unique);List<MedicalRecord> result=new ArrayList<>();Map<Long,MedicalRecord> headers=new java.util.LinkedHashMap<>();
        try {
            for(int offset=0;offset<ids.size();offset+=100) {
                read.check();List<Long> part=ids.subList(offset,Math.min(offset+100,ids.size()));
                List<MedicalRecord> loaded=baseMapper.selectList(new QueryWrapper<MedicalRecord>()
                        .select("id","patient_id","user_id","content_version","verification_status").in("id",part));
                for(MedicalRecord header:loaded)if(header!=null)header.setReviewSummary(null);
                if(loaded.size()!=part.size())throw currentChanged();
                for(MedicalRecord header:loaded) {
                    if(header==null||!part.contains(header.getId())||headers.putIfAbsent(header.getId(),header)!=null)throw currentChanged();
                    result.add(header);
                }
                read.check();
            }
        }catch(DataAccessException failure){throw currentUnavailable();}
        for(Map<String,Object> row:rows) {
            MedicalRecord header=headers.get((Long)row.get("recordId"));
            if(header==null||!java.util.Objects.equals(header.getContentVersion(),row.get("contentVersion"))
                    ||!java.util.Objects.equals(header.getVerificationStatus(),row.get("verificationStatus")))throw currentChanged();
        }
        return result;
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
        try(ReadContent read=new ReadContent(readActorId(),false)) {
            MedicalRecord record=baseMapper.selectById(id);
            if(record==null)throw new IllegalArgumentException("The record does not exist.");
            read.select(Collections.singletonList(record));read.prepare(null);
            record.setItems(itemMapper.selectList(new QueryWrapper<MedicalRecordItem>().eq("record_id",id)));
            record.setAttachments(listAttachmentsMeta(id).stream().map(MedicalRecordReviewAttachment::new)
                    .collect(java.util.stream.Collectors.toList()));
            read.project();read.finish();return record;
        }
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
        try (org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle.Scope scope=workspace?nativeLifecycle.openWorkspaceReview():nativeLifecycle.openLegacyReview()) {
            try {
                MedicalRecord record=corrections.guardReview(org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.id(id),workspace,approved);
                if(record==null)return null;
                if(!approved)MedicalRecordReviewContract.requireRejectionReason(note);
                scope.preflight(record);scope.arm();
                if(baseMapper.reviewVersion(id,version,approved?"VERIFIED":"REJECTED",dataScopeHelper.requireUserId(),LocalDateTime.now(),note)!=1)throw new MedicalRecordReviewContract.Conflict();
                scope.checkpoint();corrections.recheck(record,workspace);
                validity.open().project(Collections.singletonList(record));scope.complete();return record;
            }catch(Throwable failure){throw scope.failure(failure);}
        }
    }

    /** Participating-only exact intent/count branch. The old unparticipating replacement path is unchanged. */
    private boolean updateParticipating(MedicalRecord requested,List<MedicalRecordItem> requestedItems,MedicalRecord existing,MedicalRecord admission,
            org.familyhealthcare.service.medicalreview.MedicalReviewNativeLifecycle.Scope scope) {
        if(requested.getPatientId()!=null&&!java.util.Objects.equals(requested.getPatientId(),existing.getPatientId()))
            throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionAuthorization.denied();
        MedicalRecord record=new MedicalRecord();record.setId(existing.getId());record.setPatientId(existing.getPatientId());record.setUserId(existing.getUserId());
        record.setPatientName(requested.getPatientName()!=null?requested.getPatientName():existing.getPatientName());
        record.setRecordDate(requested.getRecordDate()!=null?requested.getRecordDate():existing.getRecordDate());
        record.setRecordType(requested.getRecordType()!=null?requested.getRecordType():existing.getRecordType());
        record.setHospitalName(requested.getHospitalName()!=null?requested.getHospitalName():existing.getHospitalName());
        record.setDeptName(requested.getDeptName()!=null?requested.getDeptName():existing.getDeptName());
        record.setDoctorName(requested.getDoctorName()!=null?requested.getDoctorName():existing.getDoctorName());
        record.setRemark(requested.getRemark()!=null?requested.getRemark():existing.getRemark());
        record.setAiRawResult(requested.getAiRawResult());
        record.setSourceType(requested.getSourceType()!=null?requested.getSourceType():existing.getSourceType());
        record.setSourceExternalId(requested.getSourceExternalId()!=null?requested.getSourceExternalId():existing.getSourceExternalId());
        record.setConfidenceScore(requested.getConfidenceScore());record.setCreatedAt(existing.getCreatedAt());
        record.setContentVersion(existing.getContentVersion());record.setVerificationStatus(existing.getVerificationStatus());record.setVerifiedBy(existing.getVerifiedBy());record.setVerifiedAt(existing.getVerifiedAt());record.setVerificationNote(existing.getVerificationNote());
        List<MedicalRecordItem> items=new ArrayList<>();
        if(requestedItems!=null){if(requestedItems.size()>100)throw reviewLimit();for(MedicalRecordItem input:requestedItems){
            if(input==null)throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.invalid();
            MedicalRecordItem item=new MedicalRecordItem();item.setId(input.getId());item.setRecordId(record.getId());item.setItemName(input.getItemName());item.setItemCode(input.getItemCode());item.setResultValue(input.getResultValue());item.setUnit(input.getUnit());item.setReferenceRange(input.getReferenceRange());item.setIsAbnormal(input.getIsAbnormal()==null?0:input.getIsAbnormal());item.setAiConfidence(input.getAiConfidence());sanitizeMedicalRecordItem(item);items.add(item);
        }}
        List<MedicalRecordAttachment> attachments=new ArrayList<>();List<Long> keepIds=new ArrayList<>();List<MedicalRecordAttachment> added=new ArrayList<>();
        if(requested.getAttachments()!=null)for(MedicalRecordAttachment input:requested.getAttachments()){
            if(input==null)throw org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionContracts.invalid();
            MedicalRecordAttachment attachment=new MedicalRecordAttachment();attachment.setId(input.getId());attachment.setRecordId(record.getId());
            if(input.getId()!=null){keepIds.add(input.getId());attachments.add(attachment);}
            else if(input.getFileContent()!=null&&!input.getFileContent().isEmpty()){
                attachment.setFilePath(input.getFilePath());attachment.setFileName(input.getFileName());attachment.setFileType(input.getFileType());attachment.setFileSize(input.getFileSize());attachment.setFileContent(input.getFileContent());fillAttachmentDefaults(attachment,record.getId());attachments.add(attachment);added.add(attachment);
            }
            if(attachments.size()>100)throw reviewLimit();
        }
        syncPatient(record);applySourceDefaults(record,items);
        if(record.getAiRawResult()==null)record.setAiRawResult(existing.getAiRawResult());
        for(MedicalRecordItem item:items)item.setAiConfidence(normalizedConfidence(item.getAiConfidence()));
        if(record.getConfidenceScore()==null)record.setConfidenceScore(existing.getConfidenceScore());record.setConfidenceScore(normalizedConfidence(record.getConfidenceScore()));
        scope.prepareEdit(record,items,attachments);scope.arm();
        try {scope.requireHeaderCount(baseMapper.updateById(record));}catch(RuntimeException failure){throw ClinicalImportFacts.translateBinding(failure);}
        if(baseMapper.invalidateReview(record.getId(),existing.getContentVersion())!=1)throw new MedicalRecordReviewContract.Conflict();scope.checkpoint();
        scope.deletedItems(itemMapper.delete(new QueryWrapper<MedicalRecordItem>().eq("record_id",record.getId())));
        for(int i=0;i<items.size();i++){MedicalRecordItem item=items.get(i);int count=itemMapper.insert(item);scope.insertedItem(i,item.getId(),count);}
        QueryWrapper<MedicalRecordAttachment> removed=new QueryWrapper<MedicalRecordAttachment>().eq("record_id",record.getId());if(!keepIds.isEmpty())removed.notIn("id",keepIds);
        scope.deletedAttachments(attachmentMapper.delete(removed));
        for(int i=0;i<added.size();i++){MedicalRecordAttachment attachment=added.get(i);int count=attachmentMapper.insert(attachment);scope.insertedAttachment(i,attachment.getId(),count);}
        corrections.recheck(admission,false);scope.retire();scope.complete();return true;
    }
    private static BigDecimal normalizedConfidence(BigDecimal value){return value==null?null:value.setScale(4,RoundingMode.HALF_UP);}
    private static org.familyhealthcare.service.workflow.WorkflowException reviewLimit(){return new org.familyhealthcare.service.workflow.WorkflowException(413,"MEDICAL_REVIEW_LIMIT","The medical review source exceeds the supported size limit.");}

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
