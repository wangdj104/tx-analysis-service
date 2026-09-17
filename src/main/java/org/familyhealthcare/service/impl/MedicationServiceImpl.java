package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.Medication;
import org.familyhealthcare.entity.MedicationLog;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.MedicationLogMapper;
import org.familyhealthcare.mapper.MedicationMapper;
import org.familyhealthcare.service.MedicationService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Medicationserviceimplement
 */
@Service
public class MedicationServiceImpl extends ServiceImpl<MedicationMapper, Medication>
        implements MedicationService {

    @Autowired
    private MedicationLogMapper logMapper;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    private void syncPatient(MedicationLog log) {
        if (log.getPatientId() != null) {
            Patient patient = dataScopeHelper.requirePatient(log.getPatientId());
            log.setPatientName(patient.getName());
        }
    }

    @Override
    public boolean saveLog(MedicationLog log) {
        Long userId = dataScopeHelper.requireUserId();
        log.setUserId(userId);
        syncPatient(log);
        return logMapper.insert(log) > 0;
    }

    @Override
    public boolean updateLog(MedicationLog log) {
        MedicationLog existing = logMapper.selectById(log.getId());
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        syncPatient(log);
        return logMapper.updateById(log) > 0;
    }

    @Override
    public List<MedicationLog> listLogs(Long patientId, String patientName) {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<MedicationLog> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        } else if (patientName != null && !patientName.isEmpty()) {
            qw.eq("patient_name", patientName);
        }
        qw.orderByDesc("administration_time");

        List<MedicationLog> logs = logMapper.selectList(qw);
        fillMedicationInfo(logs);
        return logs;
    }

    @Override
    public List<MedicationLog> listAllLogs() {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<MedicationLog> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.orderByDesc("administration_time");
        List<MedicationLog> logs = logMapper.selectList(qw);
        fillMedicationInfo(logs);
        return logs;
    }

    private void fillMedicationInfo(List<MedicationLog> logs) {
        for (MedicationLog log : logs) {
            if (log.getMedicationId() != null) {
                Medication medication = this.getById(log.getMedicationId());
                log.setMedication(medication);
            }
        }
    }

    @Override
    public boolean deleteLog(Long id) {
        MedicationLog existing = logMapper.selectById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return logMapper.deleteById(id) > 0;
    }

    @Override
    public List<Medication> listActiveMedications(Long patientId) {
        QueryWrapper<Medication> qw = new QueryWrapper<>();
        dataScopeHelper.applyUserScope(qw);
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        qw.eq("is_active", 1).orderByAsc("drug_name");
        return super.list(qw);
    }

    @Override
    public List<Medication> list(Long patientId) {
        QueryWrapper<Medication> qw = new QueryWrapper<>();
        dataScopeHelper.applyUserScope(qw);
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        qw.orderByAsc("drug_name");
        return super.list(qw);
    }

    @Override
    public boolean saveMedicationsBatch(List<Medication> medications) {
        if (medications == null || medications.isEmpty()) {
            return false;
        }
        Long userId = dataScopeHelper.requireUserId();
        int saved = 0;
        for (Medication medication : medications) {
            medication.setUserId(userId);
            medication.setIsActive(1);
            if (super.save(medication)) {
                saved++;
            }
        }
        return saved > 0;
    }
}
