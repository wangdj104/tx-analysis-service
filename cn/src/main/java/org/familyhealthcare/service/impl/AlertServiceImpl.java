package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.AlertRecord;
import org.familyhealthcare.entity.AlertRule;
import org.familyhealthcare.entity.AlertEvent;
import org.familyhealthcare.entity.HealthIndicator;
import org.familyhealthcare.entity.MedicalRecordItem;
import org.familyhealthcare.mapper.AlertRecordMapper;
import org.familyhealthcare.mapper.AlertRuleMapper;
import org.familyhealthcare.mapper.AlertEventMapper;
import org.familyhealthcare.mapper.MedicalRecordItemMapper;
import org.familyhealthcare.service.AlertService;
import org.familyhealthcare.service.HealthIndicatorService;
import org.familyhealthcare.service.NotificationDeliveryService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.AlertStatsVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * alertserviceimplement
 */
@Service
public class AlertServiceImpl extends ServiceImpl<AlertRecordMapper, AlertRecord> implements AlertService {

    @Autowired
    private AlertRuleMapper alertRuleMapper;
    @Autowired
    private DataScopeHelper dataScopeHelper;
    @Autowired
    private MedicalRecordItemMapper medicalRecordItemMapper;
    @Autowired
    private HealthIndicatorService healthIndicatorService;
    @Autowired
    private AlertEventMapper alertEventMapper;
    @Autowired
    private NotificationDeliveryService notificationDeliveryService;

    // ---- rulemanagement ----

    @Override
    public List<AlertRule> listRules(Long patientId) {
        if (patientId == null) return java.util.Collections.emptyList();
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<AlertRule> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId).orderByDesc("created_at");
        return alertRuleMapper.selectList(qw);
    }

    @Override
    public boolean saveRule(AlertRule rule) {
        Long userId = dataScopeHelper.requireUserId();
        if (rule.getPatientId() == null) throw new IllegalStateException("Select a patient");
        dataScopeHelper.requirePatient(rule.getPatientId());
        rule.setUserId(userId);
        // AutomaticpopulateindicatorName
        if (rule.getIndicatorCode() != null) {
            HealthIndicator indicator = healthIndicatorService.findByCode(rule.getIndicatorCode());
            if (indicator != null) rule.setIndicatorName(indicator.getItemName());
        }
        if (rule.getEnabled() == null) rule.setEnabled(1);
        return rule.getId() != null ? alertRuleMapper.updateById(rule) > 0 : alertRuleMapper.insert(rule) > 0;
    }

    @Override
    public boolean toggleRuleEnabled(Long id) {
        AlertRule existing = alertRuleMapper.selectById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        existing.setEnabled(existing.getEnabled() != null && existing.getEnabled() == 1 ? 0 : 1);
        return alertRuleMapper.updateById(existing) > 0;
    }

    @Override
    public boolean deleteRule(Long id) {
        AlertRule existing = alertRuleMapper.selectById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return alertRuleMapper.deleteById(id) > 0;
    }

    // ---- recordmanagement ----

    @Override
    public List<AlertRecord> listRecords(Long patientId, String status) {
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<AlertRecord> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId);
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        qw.orderByDesc("triggered_at");
        return baseMapper.selectList(qw);
    }

    @Override
    public boolean acknowledge(Long id) {
        return updateStatus(id, "CONFIRMED", null);
    }

    @Override
    public boolean resolve(Long id, String handlingNote) {
        return updateStatus(id, "RESOLVED", handlingNote);
    }

    @Override
    public boolean updateStatus(Long id, String status, String handlingNote) {
        AlertRecord existing = getById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        if (!java.util.Arrays.asList("PENDING", "CONFIRMED", "OBSERVING", "CONSULTED", "RECHECKED", "RESOLVED").contains(status)) {
            throw new IllegalArgumentException("Invalid alert status.");
        }
        if ("RESOLVED".equals(status) && (handlingNote == null || handlingNote.trim().isEmpty())) {
            throw new IllegalArgumentException("Document the action taken before resolving an alert.");
        }
        String previous = existing.getStatus();
        if ("RESOLVED".equals(previous) && !"RESOLVED".equals(status)) {
            throw new IllegalArgumentException("A resolved alert cannot be reopened. Run a new assessment instead.");
        }
        existing.setStatus(status);
        existing.setHandlingNote(handlingNote);
        LocalDateTime now = LocalDateTime.now();
        Long actorId = dataScopeHelper.requireUserId();
        if ("CONFIRMED".equals(status) && existing.getAcknowledgedAt() == null) {
            existing.setAcknowledgedBy(actorId);
            existing.setAcknowledgedAt(now);
        }
        if ("RESOLVED".equals(status)) {
            existing.setResolvedBy(actorId);
            existing.setResolvedAt(now);
        }
        boolean changed = updateById(existing);
        if (changed && !java.util.Objects.equals(previous, status)) {
            AlertEvent event = new AlertEvent();
            event.setAlertId(existing.getId());
            event.setPatientId(existing.getPatientId());
            event.setActorId(actorId);
            String actorName = CurrentUserUtil.getCurrentUsername();
            event.setActorName(actorName == null ? "SYSTEM" : actorName);
            event.setFromStatus(previous);
            event.setToStatus(status);
            event.setNote(handlingNote);
            alertEventMapper.insert(event);
        }
        return changed;
    }

    @Override
    public List<AlertEvent> listEvents(Long id) {
        AlertRecord existing = getById(id);
        if (existing == null) return java.util.Collections.emptyList();
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return alertEventMapper.selectList(new QueryWrapper<AlertEvent>()
                .eq("alert_id", id).orderByAsc("created_at"));
    }

    @Override
    public boolean deleteRecord(Long id) {
        AlertRecord existing = getById(id);
        if (existing == null) return false;
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }

    // ---- alertExamination ----

    @Override
    public void checkThresholds(Long patientId) {
        if (patientId == null) return;
        dataScopeHelper.requirePatient(patientId);
        Long userId = dataScopeHelper.requireUserId();
        checkThresholdsSystem(patientId, userId);
    }

    @Override
    public void checkThresholdsSystem(Long patientId, Long userId) {
        if (patientId == null || userId == null) return;

        // getthis Patienthas Enabled rule
        QueryWrapper<AlertRule> ruleQw = new QueryWrapper<>();
        ruleQw.eq("patient_id", patientId).eq("enabled", 1);
        List<AlertRule> rules = alertRuleMapper.selectList(ruleQw);

        for (AlertRule rule : rules) {
            String indicatorCode = rule.getIndicatorCode();
            if (indicatorCode == null) continue;

            // through JOIN medical_record tableby PatientID + indicatorCodequerylatestExaminationitem
            MedicalRecordItem latestItem = medicalRecordItemMapper.selectLatestByPatientIdAndItemCode(patientId, indicatorCode);

            // if item_code not match, trythroughindicatorNamematch (supportstandardname and alias)
            if (latestItem == null) {
                HealthIndicator indicator = healthIndicatorService.findByCode(indicatorCode);
                if (indicator != null) {
                    // 1. firsttrystandardName
                    latestItem = medicalRecordItemMapper.selectLatestByPatientIdAndItemName(patientId, indicator.getItemName());
                    // 2. againtry aliases in alias (comma-separated)
                    if (latestItem == null && indicator.getAliases() != null && !indicator.getAliases().isEmpty()) {
                        String[] aliases = indicator.getAliases().split(",");
                        for (String alias : aliases) {
                            String trimmedAlias = alias.trim();
                            if (!trimmedAlias.isEmpty()) {
                                latestItem = medicalRecordItemMapper.selectLatestByPatientIdAndItemName(patientId, trimmedAlias);
                                if (latestItem != null) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (latestItem == null || latestItem.getResultValue() == null) continue;

            // extractcountvalue (supportfrom containUnit stringinextract, for example  "200.5μmol/L")
            BigDecimal numericValue = extractNumericValue(latestItem.getResultValue());
            if (numericValue == null) continue;

            // Examinationthreshold
            boolean breached = checkThreshold(numericValue, rule.getThresholdType(), rule.getThresholdValue());
            if (!breached) continue;

            String dedupeKey = "RULE:" + rule.getId() + ":ITEM:" + latestItem.getId();
            QueryWrapper<AlertRecord> activeQw = new QueryWrapper<>();
            activeQw.eq("patient_id", patientId).eq("dedupe_key", dedupeKey)
                    .in("status", "PENDING", "CONFIRMED", "OBSERVING", "CONSULTED", "RECHECKED")
                    .orderByDesc("id").last("limit 1");
            AlertRecord active = baseMapper.selectOne(activeQw);
            if (active != null) {
                active.setLastTriggeredAt(LocalDateTime.now());
                active.setTriggeredValue(latestItem.getResultValue() + (latestItem.getUnit() != null ? " " + latestItem.getUnit() : ""));
                active.setOccurrenceCount((active.getOccurrenceCount() == null ? 1 : active.getOccurrenceCount()) + 1);
                baseMapper.updateById(active);
                continue;
            }

            // Create one active alert for one source observation. Repeated scans only refresh lastTriggeredAt.
            AlertRecord record = new AlertRecord();
            record.setRuleId(rule.getId());
            record.setPatientId(patientId);
            record.setUserId(userId);
            record.setAlertType("INDICATOR");
            record.setAlertLevel(rule.getAlertLevel());
            record.setAlertTitle(rule.getIndicatorName() + " Health Alerts");
            record.setTriggeredValue(latestItem.getResultValue() + (latestItem.getUnit() != null ? " " + latestItem.getUnit() : ""));
            record.setTriggeredAt(LocalDateTime.now());
            record.setLastTriggeredAt(record.getTriggeredAt());
            record.setSourceType("MEDICAL_RECORD_ITEM");
            record.setSourceId(latestItem.getId());
            record.setDedupeKey(dedupeKey);
            record.setOccurrenceCount(1);
            record.setStatus("PENDING");
            baseMapper.insert(record);
            if ("WARNING".equals(rule.getAlertLevel()) || "CRITICAL".equals(rule.getAlertLevel())) {
                notificationDeliveryService.notifyUser(userId, record.getAlertTitle(),
                        "A new result crossed the configured threshold. Review it in the Attention Center; clinical action still requires confirmation.");
            }
        }
    }

    /**
     * from testresultvalueinextractcountvalue, supportpurenumbers or containUnit string (for example  "200.5μmol/L", ">200")
     */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("(-?\\d+\\.?\\d*)");

    private BigDecimal extractNumericValue(String resultValue) {
        if (resultValue == null) return null;
        String trimmed = resultValue.trim();
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            // tryfrom stringinextractnumberspart
            Matcher matcher = NUMERIC_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                try {
                    return new BigDecimal(matcher.group(1));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            return null;
        }
    }

    private boolean checkThreshold(BigDecimal value, String thresholdType, String thresholdValue) {
        try {
            switch (thresholdType) {
                case "ABOVE":
                    BigDecimal aboveThreshold = new BigDecimal(thresholdValue.trim());
                    return value.compareTo(aboveThreshold) > 0;
                case "BELOW":
                    BigDecimal belowThreshold = new BigDecimal(thresholdValue.trim());
                    return value.compareTo(belowThreshold) < 0;
                case "OUT_OF_RANGE":
                    // thresholdValue format: "min-max"
                    String[] range = thresholdValue.trim().split("-");
                    if (range.length == 2) {
                        BigDecimal min = new BigDecimal(range[0]);
                        BigDecimal max = new BigDecimal(range[1]);
                        return value.compareTo(min) < 0 || value.compareTo(max) > 0;
                    }
                    return false;
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ---- statistics ----

    @Override
    public AlertStatsVO getStats(Long patientId) {
        if (patientId == null) return new AlertStatsVO();
        dataScopeHelper.requirePatient(patientId);

        AlertStatsVO stats = new AlertStatsVO();
        QueryWrapper<AlertRecord> totalQw = new QueryWrapper<>();
        totalQw.eq("patient_id", patientId);
        stats.setTotalCount(baseMapper.selectCount(totalQw));

        QueryWrapper<AlertRecord> pendingQw = new QueryWrapper<>();
        pendingQw.eq("patient_id", patientId).eq("status", "PENDING");
        stats.setPendingCount(baseMapper.selectCount(pendingQw));

        QueryWrapper<AlertRecord> confirmedQw = new QueryWrapper<>();
        confirmedQw.eq("patient_id", patientId).eq("status", "CONFIRMED");
        stats.setConfirmedCount(baseMapper.selectCount(confirmedQw));

        QueryWrapper<AlertRecord> resolvedQw = new QueryWrapper<>();
        resolvedQw.eq("patient_id", patientId).eq("status", "RESOLVED");
        stats.setResolvedCount(baseMapper.selectCount(resolvedQw));

        stats.setLevelCounts(baseMapper.countByLevel(patientId));
        stats.setTypeCounts(baseMapper.countByType(patientId));

        QueryWrapper<AlertRecord> recentQw = new QueryWrapper<>();
        recentQw.eq("patient_id", patientId).orderByDesc("triggered_at").last("limit 5");
        stats.setRecentAlerts(baseMapper.selectList(recentQw));

        return stats;
    }
}
