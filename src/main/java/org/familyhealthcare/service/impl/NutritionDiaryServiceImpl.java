package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.NutritionDiary;
import org.familyhealthcare.mapper.NutritionDiaryMapper;
import org.familyhealthcare.service.NutritionDiaryService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Nutrition Diary Service implement
 */
@Service
public class NutritionDiaryServiceImpl extends ServiceImpl<NutritionDiaryMapper, NutritionDiary> implements NutritionDiaryService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Override
    public List<NutritionDiary> listByPatient(Long patientId) {
        if (patientId == null) return Collections.emptyList();
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<NutritionDiary> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId).orderByDesc("record_date");
        return baseMapper.selectList(qw);
    }

    @Override
    public boolean saveOwned(NutritionDiary record) {
        if (record.getPatientId() == null) throw new IllegalStateException("Select a patient");
        dataScopeHelper.requirePatient(record.getPatientId());
        Long userId = dataScopeHelper.requireUserId();
        record.setUserId(userId);
        return baseMapper.insert(record) > 0;
    }

    @Override
    public boolean saveOrUpdateRecord(NutritionDiary record) {
        return record.getId() == null ? saveOwned(record) : updateOwned(record);
    }


    @Override
    public boolean updateOwned(NutritionDiary record) {
        NutritionDiary existing = getById(record.getId());
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        if (record.getPatientId() == null) {
            record.setPatientId(existing.getPatientId());
        } else {
            dataScopeHelper.requirePatient(record.getPatientId());
        }
        record.setUserId(existing.getUserId());
        return updateById(record);
    }


    @Override
    public NutritionDiary getOwnedById(Long id) {
        if (id == null) return null;
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<NutritionDiary> qw = new QueryWrapper<>();
        qw.eq("id", id);
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        return baseMapper.selectOne(qw);
    }
    @Override
    public boolean deleteOwned(Long id) {
        NutritionDiary existing = getById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }
}
