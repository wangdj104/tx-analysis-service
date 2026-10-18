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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Medicationserviceimplement
 */
@Service
public class MedicationServiceImpl extends ServiceImpl<MedicationMapper, Medication>
        implements MedicationService {

    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard tracking;

    @Autowired
    private MedicationLogMapper logMapper;

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Override @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean updateById(Medication medication){Medication old=getById(medication.getId());if(old==null)return false;tracking.requireMedicationMutationAllowed(dataScopeHelper.requireUserId(),old.getPatientId(),old.getId());if(!java.util.Objects.equals(old.getPatientId(),medication.getPatientId()))throw new IllegalStateException("Medication identity changed");return super.updateById(medication);}
    @Override @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean removeById(java.io.Serializable id){Medication old=getById(id);if(old==null)return false;tracking.requireMedicationMutationAllowed(dataScopeHelper.requireUserId(),old.getPatientId(),old.getId());return super.removeById(id);}

    private void syncPatient(MedicationLog log) {
        Patient patient = dataScopeHelper.requirePatient(log.getPatientId());
        Medication medication = log.getMedicationId() == null ? null : getById(log.getMedicationId());
        if (medication == null || !java.util.Objects.equals(log.getPatientId(), medication.getPatientId()))
            throw new IllegalArgumentException(org.familyhealthcare.util.ExportLocalization.text(
                    "Select a medication belonging to this patient.", "请选择属于当前患者的药品。"));
        if (log.getAdministrationTime() == null)
            throw new IllegalArgumentException(org.familyhealthcare.util.ExportLocalization.text(
                    "Enter the actual intake time.", "请填写实际服药时间。"));
        log.setPatientName(patient.getName());
    }

    @Override
    public boolean saveLog(MedicationLog log) {
        Long userId = dataScopeHelper.requireUserId();
        log.setId(null);
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
        log.setPatientId(existing.getPatientId());
        log.setUserId(existing.getUserId());
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
        List<Medication> rows=super.list(qw);
        if(tracking.storageAvailable())for(Medication medication:rows)tracking.decorateMedication(medication);
        return rows;
    }

    @Override
    public List<Medication> list(Long patientId) {
        QueryWrapper<Medication> qw = new QueryWrapper<>();
        dataScopeHelper.applyUserScope(qw);
        if (patientId != null) {
            qw.eq("patient_id", patientId);
        }
        qw.orderByAsc("drug_name");
        List<Medication> rows=super.list(qw);
        if(tracking.storageAvailable())for(Medication medication:rows)tracking.decorateMedication(medication);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveMedicationsBatch(List<Medication> medications) {
        if (medications == null || medications.isEmpty()) {
            return false;
        }
        Long userId = dataScopeHelper.requireUserId();
        for (Medication medication : medications) dataScopeHelper.requirePatient(medication.getPatientId());
        int saved = 0;
        for (Medication medication : medications) {
            medication.setId(null);
            medication.setUserId(userId);
            medication.setIsActive(1);
            if (super.save(medication)) {
                saved++;
            } else {
                throw new IllegalStateException("zh".equals(org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage())
                        ? "批量保存失败，本次导入已回滚。" : "The batch could not be saved and was rolled back.");
            }
        }
        return saved > 0;
    }
}
