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
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional(rollbackFor = Exception.class)
    public boolean saveOrUpdateByMonth(DryWeightMonthly record) {
        Long userId = dataScopeHelper.requireUserId();
        if (record.getPatientId() == null) {
            throw new IllegalStateException("Select a patient");
        }
        dataScopeHelper.requirePatient(record.getPatientId());
        if (record.getDryWeight() == null || record.getDryWeight().signum() <= 0) throw validation("Dry weight must be greater than zero.", "干体重必须大于零。");
        try { record.setYearMonth(java.time.YearMonth.parse(record.getYearMonth()).toString()); }
        catch (RuntimeException e) { throw validation("Select a valid month.", "请选择有效月份。"); }
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
            record.setUserId(existing.getUserId());
            return updateById(record);
        } else {
            // Addrecord
            record.setId(null);
            return save(record);
        }
    }

    private IllegalArgumentException validation(String english, String chinese) {
        return new IllegalArgumentException("zh".equals(org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage()) ? chinese : english);
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
