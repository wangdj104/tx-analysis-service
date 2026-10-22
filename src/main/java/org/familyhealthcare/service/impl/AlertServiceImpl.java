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
import org.familyhealthcare.service.NotificationAudienceService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.AlertStatsVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
    @Autowired
    private NotificationAudienceService notificationAudienceService;

    /** This existing-column discriminator also works before the workflow schema upgrade and after disable. */
    private static final String LEGACY_ONLY="(alert_type IS NULL OR alert_type<>'MEASUREMENT_FOLLOW_UP')";
    @Override public AlertRecord getById(java.io.Serializable id) {return baseMapper.selectOne(new QueryWrapper<AlertRecord>().apply(LEGACY_ONLY).eq("id",id));}
    @Override public boolean save(AlertRecord row) {
        if("MEASUREMENT_FOLLOW_UP".equalsIgnoreCase(String.valueOf(row.getAlertType()).trim()))throw new IllegalArgumentException("The follow-up marker is server-owned.");
        return super.save(row);
    }
    @Override public boolean updateById(AlertRecord row) {
        if("MEASUREMENT_FOLLOW_UP".equalsIgnoreCase(String.valueOf(row.getAlertType()).trim()))throw new IllegalArgumentException("Use the follow-up command API.");
        return baseMapper.update(row,new UpdateWrapper<AlertRecord>().eq("id",row.getId()).apply(LEGACY_ONLY))>0;
    }
    @Override public boolean removeById(java.io.Serializable id) {return baseMapper.delete(new QueryWrapper<AlertRecord>().apply(LEGACY_ONLY).eq("id",id))>0;}

    @Autowired
    private org.familyhealthcare.service.medicalrecord.MedicalRecordThresholdWriter thresholdWriter;
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordAlertProjection medicalAlerts;

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
        AlertRule existing = rule.getId() == null ? null : alertRuleMapper.selectById(rule.getId());
        if (rule.getId() != null && existing == null) return false;
        if (rule.getPatientId() == null) throw new IllegalStateException("Select a patient");
        if (existing != null) {
            // 只能用数据库里的患者归属核对写权限，不能让请求换个患者来证明可以修改旧规则。
            if (existing.getPatientId() == null) throw new IllegalStateException("Select a patient");
            dataScopeHelper.requirePatientAccess(existing.getPatientId(), null, true);
            if (!java.util.Objects.equals(existing.getPatientId(), rule.getPatientId())) {
                throw new IllegalArgumentException("An alert rule cannot be moved to another patient.");
            }
            rule.setUserId(existing.getUserId());
        } else {
            dataScopeHelper.requirePatientAccess(rule.getPatientId(), null, true);
            rule.setUserId(userId);
        }
        // AutomaticpopulateindicatorName
        if (rule.getIndicatorCode() != null) {
            HealthIndicator indicator = healthIndicatorService.findByCode(rule.getIndicatorCode());
            if (indicator != null) rule.setIndicatorName(indicator.getItemName());
        }
        if (rule.getEnabled() == null) rule.setEnabled(1);
        // 把原患者也放进更新条件，避免检查后记录归属变化时仍覆盖到别人的规则；原创建人保持不变。
        return existing != null ? alertRuleMapper.update(rule, new UpdateWrapper<AlertRule>()
                .eq("id", existing.getId()).eq("patient_id", existing.getPatientId())) > 0
                : alertRuleMapper.insert(rule) > 0;
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
        QueryWrapper<AlertRecord> qw = new QueryWrapper<AlertRecord>().apply(LEGACY_ONLY);
        if (!CurrentUserUtil.isAdmin()) dataScopeHelper.applyUserScope(qw);
        qw.eq("patient_id", patientId);
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        qw.orderByDesc("triggered_at");
        List<AlertRecord> rows=baseMapper.selectList(qw);medicalAlerts.project(rows);return rows;
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
        String activeSources=medicalValidity.open().activeSql("r.");

        // getthis Patienthas Enabled rule
        QueryWrapper<AlertRule> ruleQw = new QueryWrapper<>();
        ruleQw.eq("patient_id", patientId).eq("enabled", 1);
        List<AlertRule> rules = alertRuleMapper.selectList(ruleQw);

        for (AlertRule rule : rules) {
            String indicatorCode = rule.getIndicatorCode();
            if (indicatorCode == null) continue;

            // through JOIN medical_record tableby PatientID + indicatorCodequerylatestExaminationitem
            MedicalRecordItem latestItem = medicalRecordItemMapper.selectLatestByPatientIdAndItemCode(patientId, indicatorCode, activeSources);

            // if item_code not match, trythroughindicatorNamematch (supportstandardname and alias)
            if (latestItem == null) {
                HealthIndicator indicator = healthIndicatorService.findByCode(indicatorCode);
                if (indicator != null) {
                    // 1. firsttrystandardName
                    latestItem = medicalRecordItemMapper.selectLatestByPatientIdAndItemName(patientId, indicator.getItemName(), activeSources);
                    // 2. againtry aliases in alias (comma-separated)
                    if (latestItem == null && indicator.getAliases() != null && !indicator.getAliases().isEmpty()) {
                        String[] aliases = indicator.getAliases().split(",");
                        for (String alias : aliases) {
                            String trimmedAlias = alias.trim();
                            if (!trimmedAlias.isEmpty()) {
                                latestItem = medicalRecordItemMapper.selectLatestByPatientIdAndItemName(patientId, trimmedAlias, activeSources);
                                if (latestItem != null) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (latestItem == null || latestItem.getResultValue() == null) continue;

            org.familyhealthcare.service.medicalrecord.MedicalRecordThresholdWriter.Candidate candidate =
                    thresholdWriter.evaluate(patientId,userId,latestItem.getRecordId(),latestItem.getId(),rule.getId());
            thresholdWriter.dispatch(candidate);
        }
    }

    // ---- statistics ----

    @Override
    public AlertStatsVO getStats(Long patientId) {
        if (patientId == null) return new AlertStatsVO();
        dataScopeHelper.requirePatient(patientId);

        AlertStatsVO stats = new AlertStatsVO();
        QueryWrapper<AlertRecord> totalQw = new QueryWrapper<AlertRecord>().apply(LEGACY_ONLY);
        totalQw.eq("patient_id", patientId);
        stats.setTotalCount(baseMapper.selectCount(totalQw));

        QueryWrapper<AlertRecord> pendingQw = new QueryWrapper<AlertRecord>().apply(LEGACY_ONLY);
        pendingQw.eq("patient_id", patientId).eq("status", "PENDING");
        stats.setPendingCount(baseMapper.selectCount(pendingQw));

        QueryWrapper<AlertRecord> confirmedQw = new QueryWrapper<AlertRecord>().apply(LEGACY_ONLY);
        confirmedQw.eq("patient_id", patientId).eq("status", "CONFIRMED");
        stats.setConfirmedCount(baseMapper.selectCount(confirmedQw));

        QueryWrapper<AlertRecord> resolvedQw = new QueryWrapper<AlertRecord>().apply(LEGACY_ONLY);
        resolvedQw.eq("patient_id", patientId).eq("status", "RESOLVED");
        stats.setResolvedCount(baseMapper.selectCount(resolvedQw));

        stats.setLevelCounts(baseMapper.countByLevel(patientId));
        stats.setTypeCounts(baseMapper.countByType(patientId));

        QueryWrapper<AlertRecord> recentQw = new QueryWrapper<AlertRecord>().apply(LEGACY_ONLY);
        recentQw.eq("patient_id", patientId).orderByDesc("triggered_at").last("limit 5");
        List<AlertRecord> recent=baseMapper.selectList(recentQw);medicalAlerts.project(recent);stats.setRecentAlerts(recent);

        return stats;
    }
}

