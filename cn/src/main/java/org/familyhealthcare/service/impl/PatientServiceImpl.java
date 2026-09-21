package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.PatientMapper;
import org.familyhealthcare.service.PatientService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PatientServiceImpl extends ServiceImpl<PatientMapper, Patient> implements PatientService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Override
    public List<Map<String, Object>> getPatientNames() {
        Long userId = CurrentUserUtil.getCurrentUserId();
        QueryWrapper<Patient> qw = new QueryWrapper<>();
        qw.select("id", "name");
        if (!CurrentUserUtil.isAdmin()) {
            java.util.List<Long> ids=dataScopeHelper.accessiblePatientIds(userId);
            qw.and(q -> {q.eq("user_id",userId);if(!ids.isEmpty())q.or().in("id",ids);});
        }
        qw.eq("status", 1);
        qw.eq("deleted", 0);
        qw.orderByDesc("create_time");
        return toPatientNameMaps(baseMapper.selectMaps(qw));
    }

    private List<Map<String, Object>> toPatientNameMaps(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row.get("id"));
            map.put("patientName", row.get("name"));
            return map;
        }).collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<Patient> list() {
        Long userId = CurrentUserUtil.getCurrentUserId();
        QueryWrapper<Patient> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            java.util.List<Long> ids=dataScopeHelper.accessiblePatientIds(userId);
            qw.and(q -> {q.eq("user_id",userId);if(!ids.isEmpty())q.or().in("id",ids);});
        }
        return super.list(qw);
    }

    @Override
    public boolean save(Patient patient) {
        Long userId = CurrentUserUtil.getCurrentUserId();
        patient.setUserId(userId);
        return super.save(patient);
    }

    @Override
    public Patient getOwnedById(Long id) {
        Patient patient = super.getById(id);
        if (patient != null) {
            dataScopeHelper.requirePatient(patient.getId());
        }
        return patient;
    }

    @Override
    public boolean updateOwned(Patient patient) {
        Patient existing = super.getById(patient.getId());
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatient(existing.getId());
        patient.setUserId(existing.getUserId());
        return super.updateById(patient);
    }

    @Override
    public boolean deleteOwned(Long id) {
        Patient existing = super.getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.assertOwnedByCurrentUser(existing.getUserId());
        return super.removeById(id);
    }
}
