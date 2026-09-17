package org.familyhealthcare.task;

import org.familyhealthcare.entity.AlertRule;
import org.familyhealthcare.mapper.AlertRuleMapper;
import org.familyhealthcare.service.AlertService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * alertthresholdAutomaticExaminationtask
 */
@Component
public class AlertCheckTask {

    private static final Logger log = LoggerFactory.getLogger(AlertCheckTask.class);

    @Autowired
    private AlertService alertService;
    @Autowired
    private AlertRuleMapper alertRuleMapper;

    /**
     * each 10minutesAutomaticExaminationonetimeshas EnabledrulecorrespondingPatient alertthreshold
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void checkAlertThresholds() {
        try {
            // queryhas Enabled rule, by  patientId group, geteach groupinNo. oneitemsrule  userId
            QueryWrapper<AlertRule> qw = new QueryWrapper<>();
            qw.eq("enabled", 1);
            List<AlertRule> rules = alertRuleMapper.selectList(qw);

            // patientId -> userId mapping (sameonePatient ruletotalsharesameone userId)
            Map<Long, Long> patientUserMap = rules.stream()
                    .filter(r -> r.getPatientId() != null && r.getUserId() != null)
                    .collect(Collectors.toMap(
                            AlertRule::getPatientId,
                            AlertRule::getUserId,
                            (existing, replacement) -> existing
                    ));

            if (patientUserMap.isEmpty()) return;

            log.info("startAutomaticalertExamination, involveand {} Patient", patientUserMap.size());
            for (Map.Entry<Long, Long> entry : patientUserMap.entrySet()) {
                try {
                    alertService.checkThresholdsSystem(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    log.warn("Patient {} alertExaminationfailed: {}", entry.getKey(), e.getMessage());
                }
            }
            log.info("AutomaticalertExaminationcomplete");
        } catch (Exception e) {
            log.error("AutomaticalertExaminationtaskrunfailed: {}", e.getMessage(), e);
        }
    }
}
