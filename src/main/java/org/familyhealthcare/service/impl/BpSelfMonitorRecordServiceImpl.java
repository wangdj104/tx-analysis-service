package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.mapper.BpSelfMonitorRecordMapper;
import org.familyhealthcare.service.BpSelfMonitorRecordService;
import org.familyhealthcare.service.ClinicalImportFacts;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import org.familyhealthcare.service.alertworkflow.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.alibaba.fastjson2.JSON;
import static org.familyhealthcare.service.alertworkflow.AlertWorkflowContracts.*;

/**
 * Blood GlucoseBlood Pressureself-monitoringrecordServiceimplement
 */
@Service
public class BpSelfMonitorRecordServiceImpl extends ServiceImpl<BpSelfMonitorRecordMapper, BpSelfMonitorRecord> implements BpSelfMonitorRecordService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired private AlertWorkflowProperties alertWorkflowProperties;
    @Autowired(required=false) private MeasurementWorkflowBridge measurementWorkflow;
    @Autowired private MeasurementTrackingGuard tracking;
    @Autowired private AlertWorkflowAuthorizationService workflowAuth;

    @Override
    public List<BpSelfMonitorRecord> listByPatient(Long patientId) {
        if (patientId == null) return Collections.emptyList();
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<BpSelfMonitorRecord> qw = new QueryWrapper<>();
        if(alertWorkflowProperties.isEnabled())workflowAuth.requireRead(userId,patientId);
        else if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId).orderByDesc("record_date").orderByDesc("record_time");
        List<BpSelfMonitorRecord> rows=normalizeTypes(baseMapper.selectList(qw));
        if(alertWorkflowProperties.isEnabled())workflowAuth.requireRead(userId,patientId);
        return rows;
    }

    @Override
    public List<BpSelfMonitorRecord> listByPatientAndType(Long patientId, String measureType) {
        if (patientId == null) return Collections.emptyList();
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<BpSelfMonitorRecord> qw = new QueryWrapper<>();
        if(alertWorkflowProperties.isEnabled())workflowAuth.requireRead(userId,patientId);
        else if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId);
        if ("BP".equals(measureType) || "BG".equals(measureType)) qw.in("measure_type", measureType, "BP_BG", "BOTH");
        else if ("BP_BG".equals(measureType) || "BOTH".equals(measureType)) qw.in("measure_type", "BP_BG", "BOTH");
        else if (measureType != null && !measureType.isEmpty()) qw.eq("measure_type", measureType);
        qw.orderByDesc("record_date").orderByDesc("record_time");
        List<BpSelfMonitorRecord> rows=normalizeTypes(baseMapper.selectList(qw));
        if(alertWorkflowProperties.isEnabled())workflowAuth.requireRead(userId,patientId);
        return rows;
    }

    private List<BpSelfMonitorRecord> normalizeTypes(List<BpSelfMonitorRecord> records) {
        for (BpSelfMonitorRecord record : records) if ("BOTH".equals(record.getMeasureType())) record.setMeasureType("BP_BG");
        return records;
    }

    private static final Set<String> INPUT_FIELDS=new HashSet<>(Arrays.asList("patientId","recordDate","recordTime","measureType","systolicBp","diastolicBp","bloodGlucose","bgUnit","measurePeriod","sourceType","sourceExternalId","verificationStatus","remark","commandKey","expectedVersion","occurredAt"));
    private void disabledEnvelope(Map<String,Object> body) {
        if(body.containsKey("commandKey")||body.containsKey("expectedVersion")||body.containsKey("occurredAt")||body.containsKey("reason"))
            throw new AlertWorkflowException(503,"FEATURE_DISABLED","Follow-up is disabled. Refresh and reconcile the original save before retrying.");
    }
    private BpSelfMonitorRecord input(Map<String,Object> body) {
        for(String key:Arrays.asList("systolicBp","diastolicBp"))if(body.get(key)!=null) {
            try{new java.math.BigDecimal(body.get(key).toString()).intValueExact();}catch(RuntimeException invalid){throw AlertWorkflowException.invalid(key+" must be an exact integer.");}
        }
        BpSelfMonitorRecord row=JSON.parseObject(JSON.toJSONString(body),BpSelfMonitorRecord.class);
        if("BOTH".equals(row.getMeasureType()))row.setMeasureType("BP_BG");return row;
    }
    // effective 副本只负责原 patch；精度仍核对同一请求中服务器保留的原值。
    private void validateExactNumbers(Map<String,Object> body,BpSelfMonitorRecord row,BpSelfMonitorRecord original) {
        if(body!=null)for(String key:Arrays.asList("systolicBp","diastolicBp"))if(body.containsKey(key))org.familyhealthcare.util.MeasurementInputNumbers.requireSelfInteger(org.familyhealthcare.util.MeasurementInputBody.originalValue(body,key),key);
        java.math.BigDecimal glucose=org.familyhealthcare.util.MeasurementInputBody.decimalValue(body,"bloodGlucose",row.getBloodGlucose());
        if(original==null||org.familyhealthcare.util.MeasurementInputNumbers.changed(glucose,original.getBloodGlucose()))org.familyhealthcare.util.MeasurementInputNumbers.selfGlucose(glucose);
        else row.setBloodGlucose(original.getBloodGlucose());
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> saveRequest(Map<String,Object> body) {
        ClinicalImportFacts.rejectOrdinaryClientFacts(body);
        if(!alertWorkflowProperties.isEnabled()){disabledEnvelope(body);BpSelfMonitorRecord row=input(body);if(!saveOwned(row,body))throw new IllegalStateException("Failed to save");return JSON.parseObject(JSON.toJSONString(row));}
        fields(body,INPUT_FIELDS);long patient=requireId(body.get("patientId"),"patientId"),actor=dataScopeHelper.requireUserId();
        BpSelfMonitorRecord row=input(body);validateMeasurements(row);row.setId(null);row.setUserId(actor);
        return measurementWorkflow.save(actor,"BP_SELF_MONITOR",patient,body,()->{validateExactNumbers(body,row,null);if(baseMapper.insert(row)!=1)throw new IllegalStateException("Failed to save");return row.getId();});
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> updateRequest(Map<String,Object> body) {
        ClinicalImportFacts.rejectOrdinaryClientFacts(body);
        if(!alertWorkflowProperties.isEnabled()){disabledEnvelope(body);return Collections.singletonMap("updated",updateOwned(input(body),body));}
        Set<String> allowed=new HashSet<>(INPUT_FIELDS);allowed.add("id");allowed.add("reason");fields(body,allowed);
        long source=requireId(body.get("id"),"id"),actor=dataScopeHelper.requireUserId();
        return measurementWorkflow.correct(actor,"BP_SELF_MONITOR",source,body,false,before->{
            BpSelfMonitorRecord existing=baseMapper.selectById(source);
            requireUnchangedMetadata(body,"sourceType",existing.getSourceType());
            requireUnchangedMetadata(body,"sourceExternalId",existing.getSourceExternalId());
            requireUnchangedMetadata(body,"remark",existing.getRemark());
            Map<String,Object> effective=JSON.parseObject(JSON.toJSONString(existing));
            for(String key:INPUT_FIELDS)if(body.containsKey(key))effective.put(key,body.get(key));
            BpSelfMonitorRecord row=input(effective);row.setPatientId(existing.getPatientId());row.setUserId(existing.getUserId());validateMeasurements(row);
            validateExactNumbers(body,row,existing);
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BpSelfMonitorRecord> update=new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BpSelfMonitorRecord>().eq("id",source).eq("patient_id",existing.getPatientId());
            // Explicit nulls clear a corrected-away metric; omitted keys retain the old native patch behavior.
            for(String key:Arrays.asList("systolicBp","diastolicBp","bloodGlucose","bgUnit","measurePeriod","recordTime"))if(body.containsKey(key)&&body.get(key)==null)update.set(key.replaceAll("([A-Z])","_$1").toLowerCase(Locale.ROOT),null);
            try { if(baseMapper.update(row,update)!=1)throw AlertWorkflowException.conflict(); }
            catch(RuntimeException error){throw ClinicalImportFacts.translateBinding(error);}
        });
    }
    /** Clinical imports are deliberately independent historical rows; they require later explicit adoption. */
    @Override @Transactional(rollbackFor=Exception.class)
    public boolean saveImportedOwned(BpSelfMonitorRecord row, ClinicalImportFacts facts) {
        if(!"REVIEW_REQUIRED".equals(row.getVerificationStatus())||row.getSourceExternalId()==null)throw new IllegalArgumentException("An import must retain its external provenance.");
        if(row.getPatientId()==null)throw new IllegalStateException("Select a patient");
        dataScopeHelper.requirePatient(row.getPatientId());
        Long actor=dataScopeHelper.requireUserId();
        validateMeasurements(row);validateExactNumbers(null,row,null);
        row.setId(null);row.setUserId(actor);
        if("BOTH".equals(row.getMeasureType()))row.setMeasureType("BP_BG");
        facts.requireBpProjection(row);
        return baseMapper.insertImported(row,facts.toCanonicalJson())==1;
    }

    @Override
    public boolean saveOwned(BpSelfMonitorRecord record) { return saveOwned(record,null); }
    private boolean saveOwned(BpSelfMonitorRecord record,Map<String,Object> body) {
        if(alertWorkflowProperties.isEnabled())throw AlertWorkflowException.invalid("Refresh the client before saving: commandKey is required.");
        return insertOwned(record,body);
    }
    private boolean insertOwned(BpSelfMonitorRecord record) { return insertOwned(record,null); }
    private boolean insertOwned(BpSelfMonitorRecord record,Map<String,Object> body) {
        if (record.getPatientId() == null) throw new IllegalStateException("Select a patient");
        dataScopeHelper.requirePatient(record.getPatientId());
        Long userId = dataScopeHelper.requireUserId();
        validateMeasurements(record);
        validateExactNumbers(body,record,null);
        record.setId(null);
        if ("BOTH".equals(record.getMeasureType())) record.setMeasureType("BP_BG");
        record.setUserId(userId);
        return baseMapper.insert(record) > 0;
    }

    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean updateById(BpSelfMonitorRecord row) {
        tracking.requireUntrackedMutation("BP_SELF_MONITOR",row.getId());
        try {return super.updateById(row);}
        catch(RuntimeException error){throw ClinicalImportFacts.translateBinding(error);}
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean removeById(java.io.Serializable sourceId) {
        long id=Long.parseLong(sourceId.toString());tracking.requireUntrackedMutation("BP_SELF_MONITOR",id);
        return super.removeById(sourceId);
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean updateOwned(BpSelfMonitorRecord record) { return updateOwned(record,null); }
    private boolean updateOwned(BpSelfMonitorRecord record,Map<String,Object> body) {
        if(alertWorkflowProperties.isEnabled())throw AlertWorkflowException.invalid("Use the versioned measurement correction request.");
        tracking.requireUntrackedMutation("BP_SELF_MONITOR",record.getId());
        BpSelfMonitorRecord existing = getById(record.getId());
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        // Validate the effective MyBatis patch without writing untouched measurements back.
        BpSelfMonitorRecord effective = new BpSelfMonitorRecord();
        effective.setRecordDate(record.getRecordDate() == null ? existing.getRecordDate() : record.getRecordDate());
        effective.setMeasureType(record.getMeasureType() == null ? existing.getMeasureType() : record.getMeasureType());
        effective.setSystolicBp(record.getSystolicBp() == null ? existing.getSystolicBp() : record.getSystolicBp());
        effective.setDiastolicBp(record.getDiastolicBp() == null ? existing.getDiastolicBp() : record.getDiastolicBp());
        effective.setBloodGlucose(record.getBloodGlucose() == null ? existing.getBloodGlucose() : record.getBloodGlucose());
        effective.setBgUnit(record.getBgUnit() == null ? existing.getBgUnit() : record.getBgUnit());
        validateMeasurements(effective);
        validateExactNumbers(body,effective,existing);
        // 只保留实际提交的同值血糖；省略或 null 仍按原 MyBatis patch 处理。
        if(record.getBloodGlucose()!=null)record.setBloodGlucose(effective.getBloodGlucose());
        record.setPatientId(existing.getPatientId());
        if ("BOTH".equals(record.getMeasureType())) record.setMeasureType("BP_BG");
        record.setUserId(existing.getUserId());
        return updateById(record);
    }

    private void validateMeasurements(BpSelfMonitorRecord record) {
        if (record.getRecordDate() == null) throw new IllegalArgumentException("Record date is required.");
        String type = record.getMeasureType();
        boolean bp = "BP".equals(type) || "BP_BG".equals(type) || "BOTH".equals(type);
        boolean bg = "BG".equals(type) || "BP_BG".equals(type) || "BOTH".equals(type);
        if (!bp && !bg) throw new IllegalArgumentException("Select a valid measurement type.");
        if (bp && (record.getSystolicBp() == null || record.getDiastolicBp() == null)) {
            throw new IllegalArgumentException("Systolic and diastolic blood pressure are required.");
        }
        if ((record.getSystolicBp() != null && (record.getSystolicBp() < 40 || record.getSystolicBp() > 300))
                || (record.getDiastolicBp() != null && (record.getDiastolicBp() < 20 || record.getDiastolicBp() > 200))) {
            throw new IllegalArgumentException("Blood pressure is outside the accepted input range.");
        }
        if (bg && record.getBloodGlucose() == null) throw new IllegalArgumentException("Blood glucose is required.");
        if (record.getBloodGlucose() != null) {
            if (record.getBloodGlucose().signum() <= 0) throw new IllegalArgumentException("Blood glucose must be greater than zero.");
            String unit = record.getBgUnit();
            // Missing units in historical records mean mmol/L, as in existing displays/exports.
            if (unit != null && !unit.trim().isEmpty()
                    && !"mmol/L".equalsIgnoreCase(unit.trim()) && !"mg/dL".equalsIgnoreCase(unit.trim())) {
                throw new IllegalArgumentException("Blood glucose unit must be mmol/L or mg/dL.");
            }
        }
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean deleteOwned(Long id) {
        tracking.requireUntrackedMutation("BP_SELF_MONITOR",id);
        BpSelfMonitorRecord existing = getById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }
}
