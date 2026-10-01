package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.mapper.BpSelfMonitorRecordMapper;
import org.familyhealthcare.service.BpSelfMonitorRecordService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Blood GlucoseBlood Pressureself-monitoringrecordServiceimplement
 */
@Service
public class BpSelfMonitorRecordServiceImpl extends ServiceImpl<BpSelfMonitorRecordMapper, BpSelfMonitorRecord> implements BpSelfMonitorRecordService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Override
    public List<BpSelfMonitorRecord> listByPatient(Long patientId) {
        if (patientId == null) return Collections.emptyList();
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<BpSelfMonitorRecord> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId).orderByDesc("record_date").orderByDesc("record_time");
        return normalizeTypes(baseMapper.selectList(qw));
    }

    @Override
    public List<BpSelfMonitorRecord> listByPatientAndType(Long patientId, String measureType) {
        if (patientId == null) return Collections.emptyList();
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<BpSelfMonitorRecord> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId);
        if ("BP".equals(measureType) || "BG".equals(measureType)) qw.in("measure_type", measureType, "BP_BG", "BOTH");
        else if ("BP_BG".equals(measureType) || "BOTH".equals(measureType)) qw.in("measure_type", "BP_BG", "BOTH");
        else if (measureType != null && !measureType.isEmpty()) qw.eq("measure_type", measureType);
        qw.orderByDesc("record_date").orderByDesc("record_time");
        return normalizeTypes(baseMapper.selectList(qw));
    }

    private List<BpSelfMonitorRecord> normalizeTypes(List<BpSelfMonitorRecord> records) {
        for (BpSelfMonitorRecord record : records) if ("BOTH".equals(record.getMeasureType())) record.setMeasureType("BP_BG");
        return records;
    }

    @Override
    public boolean saveOwned(BpSelfMonitorRecord record) {
        if (record.getPatientId() == null) throw new IllegalStateException("Select a patient");
        dataScopeHelper.requirePatient(record.getPatientId());
        Long userId = dataScopeHelper.requireUserId();
        validateMeasurements(record);
        record.setId(null);
        if ("BOTH".equals(record.getMeasureType())) record.setMeasureType("BP_BG");
        record.setUserId(userId);
        return baseMapper.insert(record) > 0;
    }

    @Override
    public boolean updateOwned(BpSelfMonitorRecord record) {
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
    public boolean deleteOwned(Long id) {
        BpSelfMonitorRecord existing = getById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }
}
