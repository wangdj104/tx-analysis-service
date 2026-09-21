package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.DryWeightMonthly;
import org.familyhealthcare.mapper.DryWeightMonthlyMapper;
import org.familyhealthcare.service.DryWeightMonthlyService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dry WeightMonthly recordServiceimplement
 */
@Service
public class DryWeightMonthlyServiceImpl extends ServiceImpl<DryWeightMonthlyMapper, DryWeightMonthly> implements DryWeightMonthlyService {

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Override
    public BigDecimal getDryWeightByMonth(String yearMonth, Long patientId) {
        if (yearMonth == null || yearMonth.isEmpty()) {
            return null;
        }
        if (patientId == null) {
            return null;
        }
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<DryWeightMonthly> qw = new QueryWrapper<DryWeightMonthly>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.eq("patient_id", patientId);
        qw.eq("`year_month`", yearMonth)
                .last("limit 1");
        DryWeightMonthly record = baseMapper.selectOne(qw);
        return record != null ? record.getDryWeight() : null;
    }

    @Override
    public List<DryWeightMonthly> listAllOrderByMonth(Long patientId) {
        if (patientId == null) {
            return java.util.Collections.emptyList();
        }
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<DryWeightMonthly> qw = new QueryWrapper<DryWeightMonthly>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.eq("patient_id", patientId);
        qw.orderByDesc("`year_month`");
        return baseMapper.selectList(qw);
    }

    @Override
    public boolean saveOrUpdateByMonth(DryWeightMonthly record) {
        Long userId = dataScopeHelper.requireUserId();
        if (record.getPatientId() == null) {
            throw new IllegalStateException("Select a patient");
        }
        record.setUserId(userId);
        // firstquerythis MonthYesNoalready storein
        QueryWrapper<DryWeightMonthly> qw = new QueryWrapper<DryWeightMonthly>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.eq("patient_id", record.getPatientId());
        qw.eq("`year_month`", record.getYearMonth())
                .last("limit 1");
        DryWeightMonthly existing = baseMapper.selectOne(qw);

        if (existing != null) {
            // updatealready has record
            record.setId(existing.getId());
            return updateById(record);
        } else {
            // Addrecord
            return save(record);
        }
    }

    @Override
    public boolean deleteOwned(Long id) {
        DryWeightMonthly existing = getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }

    @Override
    public List<DryWeightMonthly> list() {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<DryWeightMonthly> qw = new QueryWrapper<DryWeightMonthly>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        // herenot by  patient_id filter, avoidimageresponseframeworkwithinsectioncall; externaluse listAllOrderByMonth(patientId)
        qw.orderByDesc("`year_month`");
        return baseMapper.selectList(qw);
    }
}
