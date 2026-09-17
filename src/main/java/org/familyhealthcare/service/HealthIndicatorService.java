package org.familyhealthcare.service;

import org.familyhealthcare.entity.HealthIndicator;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * healthlaboratory test dictionaryService
 */
public interface HealthIndicatorService extends IService<HealthIndicator> {

    /**
     * gethas active indicators
     */
    List<HealthIndicator> listActive();

    /**
     * by categorygetactive indicators
     */
    List<HealthIndicator> listByCategory(String category);

    /**
     * based onitemCodecheckfindindicator
     */
    HealthIndicator findByCode(String itemCode);

    /**
     * based oninputName (can canYesalias) parsefor standarditemCode
     * @param inputName inputName, for example "ferritin", "Ca"
     * @return match itemCode, for example "FERRITIN", "CA"; NonematchBacknull
     */
    String resolveName(String inputName);
}
