package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.HealthIndicator;
import org.familyhealthcare.mapper.HealthIndicatorMapper;
import org.familyhealthcare.service.HealthIndicatorService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * healthlaboratory test dictionaryServiceimplement
 */
@Service
public class HealthIndicatorServiceImpl extends ServiceImpl<HealthIndicatorMapper, HealthIndicator> implements HealthIndicatorService {

    @Autowired
    private HealthIndicatorMapper healthIndicatorMapper;

    @Override
    public List<HealthIndicator> listActive() {
        return healthIndicatorMapper.selectActiveIndicators();
    }

    @Override
    public List<HealthIndicator> listByCategory(String category) {
        return healthIndicatorMapper.selectByCategory(category);
    }

    @Override
    public HealthIndicator findByCode(String itemCode) {
        if (itemCode == null || itemCode.isEmpty()) {
            return null;
        }
        QueryWrapper<HealthIndicator> qw = new QueryWrapper<>();
        qw.eq("item_code", itemCode).eq("is_active", 1).last("limit 1");
        return baseMapper.selectOne(qw);
    }

    @Override
    public String resolveName(String inputName) {
        if (inputName == null || inputName.trim().isEmpty()) {
            return null;
        }
        String trimmed = inputName.trim();
        List<HealthIndicator> all = listActive();
        for (HealthIndicator indicator : all) {
            // 1. directlymatchitemCode
            if (indicator.getItemCode().equalsIgnoreCase(trimmed)) {
                return indicator.getItemCode();
            }
            // 2. directlymatchitemName
            if (indicator.getItemName().equalsIgnoreCase(trimmed)) {
                return indicator.getItemCode();
            }
            // 3. matchaliases (comma-separated)
            if (indicator.getAliases() != null && !indicator.getAliases().isEmpty()) {
                String[] aliasArr = indicator.getAliases().split(",");
                for (String alias : aliasArr) {
                    if (alias.trim().equalsIgnoreCase(trimmed)) {
                        return indicator.getItemCode();
                    }
                }
            }
        }
        // 4. includematch (widthrelaxedmode, asfor fallback)
        for (HealthIndicator indicator : all) {
            if (indicator.getItemName().contains(trimmed) || trimmed.contains(indicator.getItemName())) {
                return indicator.getItemCode();
            }
        }
        return null;
    }
}
