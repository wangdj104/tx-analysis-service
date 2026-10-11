package org.familyhealthcare.service;

import org.familyhealthcare.entity.AiAnalysisRecord;
import org.familyhealthcare.entity.HealthAnalysisAutomation;
import org.familyhealthcare.entity.NotificationChannel;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.AiAnalysisRecordMapper;
import org.familyhealthcare.mapper.HealthAnalysisAutomationMapper;
import org.familyhealthcare.mapper.NotificationChannelMapper;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import static org.familyhealthcare.util.ExportLocalization.text;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class HealthAnalysisAutomationService {
    private static final Set<String> FREQUENCIES = new HashSet<>(Arrays.asList("DAILY", "WEEKLY", "MONTHLY"));
    private static final Set<String> ITEMS = new HashSet<>(Arrays.asList("DIALYSIS", "VITALS", "MEDICATION", "NUTRITION", "COMPLICATION"));
    @Autowired private HealthAnalysisAutomationMapper mapper;
    @Autowired private AiAnalysisRecordMapper analysisRecords;
    @Autowired private AiAnalysisService aiAnalysis;
    @Autowired private NotificationDeliveryService delivery;
    @Autowired private NotificationChannelMapper channels;
    @Autowired private DataScopeHelper scope;
    @Autowired private UserLanguagePreferenceService languagePreference;
    @Autowired private HealthAnalysisRunStore runStore;

    public List<HealthAnalysisAutomation> list() {
        runStore.recoverUncertain();
        List<HealthAnalysisAutomation> rows = mapper.selectList(new QueryWrapper<HealthAnalysisAutomation>()
                .eq("user_id", scope.requireUserId()).orderByDesc("updated_at"));
        List<HealthAnalysisAutomation> accessible = new ArrayList<>();
        for (HealthAnalysisAutomation row : rows) {
            row.setLastAnalysisContent(null);
            org.familyhealthcare.entity.HealthAnalysisRun run = runStore.current(row.getId());
            if (run != null && ("UNKNOWN".equals(run.getState()) || "NOTIFY_UNKNOWN".equals(run.getState()))) {
                row.setUncertainRunId(run.getId()); row.setUncertainRunState(run.getState());
            }
            // 任务属于自己不等于仍有患者权限；综合分析必须有当前完整档案读权限，单个无权任务不能挡住其余任务。
            if (row.getPatientId() == null) continue;
            try { scope.requirePatientAccess(row.getPatientId(), null, false); }
            catch (IllegalStateException denied) { continue; }
            if (row.getLastAnalysisRecordId() != null) {
                AiAnalysisRecord record = analysisRecords.selectById(row.getLastAnalysisRecordId());
                // 旧任务的结果引用也要同时核对作者和患者，避免串到其他患者的正文。
                if (record != null && Objects.equals(record.getUserId(), row.getUserId())
                        && Objects.equals(record.getPatientId(), row.getPatientId())) row.setLastAnalysisContent(record.getAnalysisContent());
            }
            accessible.add(row);
        }
        return accessible;
    }

    public HealthAnalysisAutomation save(HealthAnalysisAutomation row) {
        Long userId = scope.requireUserId();
        scope.requirePatient(row.getPatientId());
        Long expected = null;
        if (row.getId() != null) {
            HealthAnalysisAutomation old = requireOwned(row.getId(), userId);
            expected = row.getConfigVersion();
            if (expected == null || !Objects.equals(expected, old.getConfigVersion())) throw new HealthAnalysisRunConflictException();
            row.setUserId(old.getUserId());
        } else { row.setUserId(userId); row.setConfigVersion(0L); row.setActiveRunId(null); row.setLastRunAt(null); row.setLastRunStatus(null); row.setLastError(null); row.setLastAnalysisRecordId(null); }
        normalize(row);
        validateChannels(row, userId);
        row.setNextRunAt(Integer.valueOf(1).equals(row.getEnabled()) ? calculateNextRun(row, LocalDateTime.now()) : null);
        if (row.getId() == null) mapper.insert(row); else runStore.updateConfiguration(row, expected);
        return mapper.selectById(row.getId());
    }

    public void delete(Long id) {
        mapper.deleteById(requireOwned(id, scope.requireUserId()).getId());
    }

    public AiAnalysisRecord runNow(Long id) { return runNow(id, false, null, null); }

    public AiAnalysisRecord runNow(Long id, boolean acknowledge, Long observedRunId, String observedState) {
        Long userId = scope.requireUserId();
        HealthAnalysisAutomation row = requireOwned(id, userId);
        scope.requirePatientAccess(row.getPatientId(), null, true);
        runStore.recoverUncertain();
        HealthAnalysisRunStore.Claim claim = runStore.claim(id, userId, false, acknowledge, observedRunId, observedState, this::calculateNextRun);
        if (claim.existingDraftId != null) return requireDraft(claim);
        return execute(claim);
    }

    @Scheduled(cron = "20 * * * * ?")
    public void runDueTasks() {
        runStore.recoverUncertain();
        for (HealthAnalysisAutomation row : runStore.candidates()) {
            try {
                CurrentUserUtil.runAsUser(row.getUserId(), () -> {
                    HealthAnalysisRunStore.Claim claim = runStore.claim(row.getId(), row.getUserId(), true, false, null, null, this::calculateNextRun);
                    return execute(claim);
                });
            } catch (HealthAnalysisRunConflictException overlap) {
                // Another executor claimed, edited, acknowledged or deleted this task.
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Automated analysis did not finish, taskId={}", row.getId());
            }
        }
    }

    private AiAnalysisRecord requireDraft(HealthAnalysisRunStore.Claim claim) {
        Long id = claim.existingDraftId == null ? claim.run.getAnalysisRecordId() : claim.existingDraftId;
        AiAnalysisRecord record = analysisRecords.selectById(id);
        if (record == null || !Objects.equals(record.getUserId(), claim.settings.getUserId()) || !Objects.equals(record.getPatientId(), claim.settings.getPatientId()))
            throw new HealthAnalysisRunConflictException();
        scope.requirePatientAccess(record.getPatientId(), null, true);
        return record;
    }

    private AiAnalysisRecord execute(HealthAnalysisRunStore.Claim claim) {
        HealthAnalysisAutomation row = claim.settings;
        LocaleContext previousLocale = LocaleContextHolder.getLocaleContext();
        try {
            if (Integer.valueOf(1).equals(claim.run.getScheduled())) LocaleContextHolder.setLocale(Locale.forLanguageTag(languagePreference.get(row.getUserId())));
            scope.requirePatientAccess(row.getPatientId(), null, true);
            AiAnalysisRecord record;
            if ("DRAFT_SAVED".equals(claim.run.getState())) {
                record = requireDraft(claim);
            } else {
                runStore.advance(claim, "CLAIMED", "GENERATING");
                String content = aiAnalysis.analyzeHealthSnapshot(row.getPatientId(), row.getAnalysisRangeDays(), split(row.getAnalysisItems()));
                if (content == null || content.trim().isEmpty()) throw new AiAnalysisException(AiAnalysisException.Reason.EMPTY_RESULT);
                record = new AiAnalysisRecord();
                record.setUserId(row.getUserId()); record.setPatientId(row.getPatientId());
                record.setTimeType("automation"); record.setTimeValue(LocalDate.now().toString());
                record.setPeriodLabel(text("Last ", "最近 ") + row.getAnalysisRangeDays() + text(" days", " 天")); record.setAnalysisContent(content);
                record.setReviewStatus("REVIEW_REQUIRED");
                record.setRemark(text("Automated analysis: ", "自动健康分析：") + row.getTaskName());
                runStore.saveDraft(claim, record);
            }
            // Recheck authorization after generation and before notification, outside a transaction.
            Patient patient = scope.requirePatientAccess(row.getPatientId(), null, true);
            runStore.advance(claim, "DRAFT_SAVED", "NOTIFYING");
            String title = "Health analysis draft ready · " + (patient == null ? row.getTaskName() : patient.getName());
            String message = "An automated analysis draft is ready for review. No clinical recommendation has been sent; open the Clinical Workbench to approve or reject it.";
            Map<Long, org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome> outcomes = delivery.notifyUserOutcomes(row.getUserId(), parseIds(row.getNotificationChannelIds()), "HEALTH_ANALYSIS_DRAFT", title, message, (channel, outcome) -> runStore.recordNotificationOutcome(claim, channel, outcome));
            boolean uncertain = outcomes.containsValue(org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome.UNKNOWN);
            boolean sent = outcomes.containsValue(org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome.DELIVERED);
            boolean allSent = !outcomes.isEmpty() && outcomes.values().stream().allMatch(x -> x == org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome.DELIVERED);
            String status = uncertain ? "NOTIFY_UNKNOWN" : allSent ? "DRAFT_READY" : sent ? "DRAFT_READY_NOTIFY_PARTIAL" : "DRAFT_READY_NOTIFY_FAILED";
            String error = uncertain ? text("The draft was saved, but notification delivery is uncertain. Confirmation is required.", "草稿已保存，但通知结果不确定，需要确认。") : allSent ? null : text("The draft was saved, but some notification channels did not accept the message.", "草稿已保存，但部分通知渠道未成功接收消息。");
            runStore.finish(claim, uncertain ? "NOTIFY_UNKNOWN" : "COMPLETE", status, error, com.alibaba.fastjson2.JSON.toJSONString(outcomes), this::calculateNextRun);
            return record;
        } catch (RuntimeException failure) {
            try {
                org.familyhealthcare.entity.HealthAnalysisRun current = runStore.current(row.getId());
                if (current != null && Objects.equals(current.getId(), claim.run.getId()) && Objects.equals(current.getClaimToken(), claim.run.getClaimToken())) {
                    if ("CLAIMED".equals(current.getState())) runStore.failBeforeGeneration(claim);
                    else if ("GENERATING".equals(current.getState())) runStore.fail(claim, !(failure instanceof AiAnalysisException) || ((AiAnalysisException) failure).isOutcomeUncertain());
                    else if ("NOTIFYING".equals(current.getState())) runStore.finish(claim, "NOTIFY_UNKNOWN", "NOTIFY_UNKNOWN", text("Notification delivery is uncertain; confirmation is required.", "通知结果不确定，需要确认。"), null, this::calculateNextRun);
                }
            } catch (RuntimeException journalUnavailable) {
                // The durable stage is retained and recovered conservatively after the lease expires.
            }
            throw failure;
        } finally { LocaleContextHolder.setLocaleContext(previousLocale); }
    }

    public AiAnalysisRecord reviewAnalysis(Long id, boolean approved, boolean notify) {
        AiAnalysisRecord record = analysisRecords.selectById(id);
        Long userId = scope.requireUserId();
        if (record == null || !Objects.equals(record.getUserId(), userId)) {
            throw new IllegalArgumentException(text("Analysis draft does not exist.", "分析草稿不存在。"));
        }
        if (!"REVIEW_REQUIRED".equals(record.getReviewStatus())) {
            throw new IllegalArgumentException(text("This analysis draft has already been reviewed.", "该分析草稿已完成审核。"));
        }
        // 审核会写记录，即使拒绝草稿或不发通知，也必须先有当前完整档案写权限，不能先改字段再校验。
        if (record.getPatientId() == null) throw new IllegalStateException(text("Select a patient", "请选择患者"));
        Patient patient = scope.requirePatientAccess(record.getPatientId(), null, true);
        record.setReviewStatus(approved ? "APPROVED" : "REJECTED");
        record.setReviewedBy(userId);
        record.setReviewedAt(LocalDateTime.now());
        analysisRecords.updateById(record);
        if (approved && notify) {
            String title = "Reviewed health analysis · " + (patient == null ? "Family member" : patient.getName());
            String content = record.getAnalysisContent() == null ? "The reviewed analysis is available in Chengxin Health."
                    : record.getAnalysisContent().substring(0, Math.min(record.getAnalysisContent().length(), 3500));
            delivery.notifyUser(userId, "HEALTH_ANALYSIS_REVIEWED", title, content);
        }
        return record;
    }

    private void normalize(HealthAnalysisAutomation row) {
        row.setTaskName(row.getTaskName() == null || row.getTaskName().trim().isEmpty() ? text("Scheduled health analysis", "定期健康分析") : row.getTaskName().trim());
        row.setEnabled(row.getEnabled() == null ? 1 : row.getEnabled());
        row.setFrequencyType(row.getFrequencyType() == null ? "WEEKLY" : row.getFrequencyType().toUpperCase());
        if (!FREQUENCIES.contains(row.getFrequencyType())) throw new IllegalArgumentException(text("Invalid run frequency.", "运行周期无效。"));
        try { LocalTime.parse(row.getRunTime(), DateTimeFormatter.ofPattern("HH:mm")); }
        catch (Exception e) { throw new IllegalArgumentException(text("Select a valid run time.", "请选择有效的运行时间。")); }
        row.setIntervalDays(between(row.getIntervalDays(), 1, 30, 1));
        row.setDayOfWeek(between(row.getDayOfWeek(), 1, 7, 1));
        row.setDayOfMonth(between(row.getDayOfMonth(), 1, 28, 1));
        row.setAnalysisRangeDays(between(row.getAnalysisRangeDays(), 1, 365, 30));
        List<String> selected = split(row.getAnalysisItems()).stream().filter(ITEMS::contains).distinct().collect(Collectors.toList());
        if (selected.isEmpty()) throw new IllegalArgumentException(text("Select at least one analysis item.", "请至少选择一项分析内容。"));
        row.setAnalysisItems(String.join(",", selected));
    }

    LocalDateTime calculateNextRun(HealthAnalysisAutomation row, LocalDateTime now) {
        LocalTime time = LocalTime.parse(row.getRunTime(), DateTimeFormatter.ofPattern("HH:mm"));
        if ("DAILY".equals(row.getFrequencyType())) {
            LocalDateTime candidate = now.toLocalDate().atTime(time);
            return candidate.isAfter(now) ? candidate : now.toLocalDate().plusDays(row.getIntervalDays()).atTime(time);
        }
        if ("WEEKLY".equals(row.getFrequencyType())) {
            LocalDate date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(row.getDayOfWeek())));
            LocalDateTime candidate = date.atTime(time);
            return candidate.isAfter(now) ? candidate : date.plusWeeks(1).atTime(time);
        }
        YearMonth month = YearMonth.from(now);
        LocalDateTime candidate = month.atDay(Math.min(row.getDayOfMonth(), month.lengthOfMonth())).atTime(time);
        if (candidate.isAfter(now)) return candidate;
        YearMonth next = month.plusMonths(1);
        return next.atDay(Math.min(row.getDayOfMonth(), next.lengthOfMonth())).atTime(time);
    }

    private void validateChannels(HealthAnalysisAutomation row, Long userId) {
        List<Long> ids = parseIds(row.getNotificationChannelIds());
        if (ids.isEmpty()) return;
        long count = channels.selectCount(new QueryWrapper<NotificationChannel>().eq("user_id", userId).in("id", ids));
        if (count != ids.size()) throw new IllegalArgumentException(text("The notification channel does not exist or does not belong to you.", "通知渠道不存在或不属于当前用户。"));
        row.setNotificationChannelIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    private HealthAnalysisAutomation requireOwned(Long id, Long userId) {
        HealthAnalysisAutomation row = mapper.selectById(id);
        if (row == null || !Objects.equals(row.getUserId(), userId)) throw new IllegalArgumentException(text("The automated analysis task does not exist.", "自动分析任务不存在。"));
        return row;
    }
    private int between(Integer value,int min,int max,int fallback){int v=value==null?fallback:value;if(v<min||v>max)throw new IllegalArgumentException(text("The schedule value is outside the allowed range.", "周期参数超出允许范围。"));return v;}
    private List<String> split(String value){if(value==null||value.trim().isEmpty())return new ArrayList<>();return Arrays.stream(value.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(String::toUpperCase).collect(Collectors.toList());}
    private List<Long> parseIds(String value){List<Long> ids=new ArrayList<>();if(value==null||value.trim().isEmpty())return ids;try{for(String s:value.split(","))if(!s.trim().isEmpty())ids.add(Long.valueOf(s.trim()));}catch(NumberFormatException e){throw new IllegalArgumentException(text("Invalid notification channel format.", "通知渠道格式无效。"));}return ids.stream().distinct().collect(Collectors.toList());}
}
