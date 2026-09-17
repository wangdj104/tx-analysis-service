package org.familyhealthcare.service;

import org.familyhealthcare.entity.AlertRecord;
import org.familyhealthcare.entity.AlertRule;
import org.familyhealthcare.vo.AlertStatsVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * alertservice (sametimemanagementrule and record)
 */
public interface AlertService extends IService<AlertRecord> {

    // ---- rulemanagement ----
    List<AlertRule> listRules(Long patientId);
    boolean saveRule(AlertRule rule);
    boolean toggleRuleEnabled(Long id);
    boolean deleteRule(Long id);

    // ---- recordmanagement ----
    List<AlertRecord> listRecords(Long patientId, String status);
    boolean acknowledge(Long id);
    boolean resolve(Long id, String handlingNote);
    boolean deleteRecord(Long id);

    // ---- alertExamination ----
    void checkThresholds(Long patientId);

    /**
     * systemlevelalertExamination (Noneuserup down text, providesettimetaskcall)
     */
    void checkThresholdsSystem(Long patientId, Long userId);

    // ---- statistics ----
    AlertStatsVO getStats(Long patientId);
}
