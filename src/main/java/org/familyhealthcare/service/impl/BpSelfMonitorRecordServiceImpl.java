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
        record.setPatientId(existing.getPatientId());
        if ("BOTH".equals(record.getMeasureType())) record.setMeasureType("BP_BG");
        record.setUserId(existing.getUserId());
        return updateById(record);
    }

    @Override
    public boolean deleteOwned(Long id) {
        BpSelfMonitorRecord existing = getById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }
}
