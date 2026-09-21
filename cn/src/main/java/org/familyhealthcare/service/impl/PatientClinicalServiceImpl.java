package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.PatientClinical;
import org.familyhealthcare.mapper.PatientClinicalMapper;
import org.familyhealthcare.service.PatientClinicalService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Patientclinical detailsinformationServiceimplement
 */
@Service
public class PatientClinicalServiceImpl extends ServiceImpl<PatientClinicalMapper, PatientClinical> implements PatientClinicalService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Override
    public PatientClinical getByPatientId(Long patientId) {
        if (patientId == null) {
            return null;
        }
        // validatePatientownership
        dataScopeHelper.requirePatient(patientId);
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<PatientClinical> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.eq("patient_id", patientId).last("limit 1");
        return baseMapper.selectOne(qw);
    }

    @Override
    public boolean saveOrUpdateClinical(PatientClinical record) {
        Long userId = dataScopeHelper.requireUserId();
        if (record.getPatientId() == null) {
            throw new IllegalStateException("Select a patient");
        }
        // validatePatientownership
        dataScopeHelper.requirePatient(record.getPatientId());
        record.setUserId(userId);

        // querythis PatientYesNoalready has clinicalrecord (UNIQUE KEY on patient_id)
        QueryWrapper<PatientClinical> qw = new QueryWrapper<>();
        qw.eq("patient_id", record.getPatientId()).last("limit 1");
        PatientClinical existing = baseMapper.selectOne(qw);

        if (existing != null) {
            record.setId(existing.getId());
            return updateById(record);
        } else {
            return save(record);
        }
    }

    @Override
    public boolean deleteOwned(Long id) {
        PatientClinical existing = getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }
}
