package org.familyhealthcare.service;

import org.familyhealthcare.entity.DryWeightMonthly;
import com.baomidou.mybatisplus.extension.service.IService;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dry WeightMonthly recordService
 */
public interface DryWeightMonthlyService extends IService<DryWeightMonthly> {

    /**
     * getspecifiedMonth Dry Weightreference value
     * @param yearMonth Month, format yyyy-MM
     * @return Dry Weightvalue, ifthis Monthnohas recordthenBacknull
     */
    BigDecimal getDryWeightByMonth(String yearMonth, Long patientId);

    /**
     * queryhas Dry Weightrecord, by Monthlowerorder
     */
    List<DryWeightMonthly> listAllOrderByMonth(Long patientId);

    /**
     * Saveor updateDry Weightrecord (by Monthonlyone)
     */
    boolean saveOrUpdateByMonth(DryWeightMonthly record);

    boolean deleteOwned(Long id);
}
