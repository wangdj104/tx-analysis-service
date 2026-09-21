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

    public List<HealthAnalysisAutomation> list() {
        List<HealthAnalysisAutomation> rows = mapper.selectList(new QueryWrapper<HealthAnalysisAutomation>()
                .eq("user_id", scope.requireUserId()).orderByDesc("updated_at"));
        for (HealthAnalysisAutomation row : rows) {
            if (row.getLastAnalysisRecordId() == null) continue;
            AiAnalysisRecord record = analysisRecords.selectById(row.getLastAnalysisRecordId());
            if (record != null && Objects.equals(record.getUserId(), row.getUserId())) row.setLastAnalysisContent(record.getAnalysisContent());
        }
        return rows;
    }

    public HealthAnalysisAutomation save(HealthAnalysisAutomation row) {
        Long userId = scope.requireUserId();
        scope.requirePatient(row.getPatientId());
        if (row.getId() != null) {
            HealthAnalysisAutomation old = requireOwned(row.getId(), userId);
            row.setUserId(old.getUserId());
            row.setLastRunAt(old.getLastRunAt()); row.setLastRunStatus(old.getLastRunStatus());
            row.setLastError(old.getLastError()); row.setLastAnalysisRecordId(old.getLastAnalysisRecordId());
        } else row.setUserId(userId);
        normalize(row);
        validateChannels(row, userId);
        row.setNextRunAt(Integer.valueOf(1).equals(row.getEnabled()) ? calculateNextRun(row, LocalDateTime.now()) : null);
        if (row.getId() == null) mapper.insert(row); else mapper.updateById(row);
        return mapper.selectById(row.getId());
    }

    public void delete(Long id) {
        mapper.deleteById(requireOwned(id, scope.requireUserId()).getId());
    }

    public AiAnalysisRecord runNow(Long id) {
        HealthAnalysisAutomation row = requireOwned(id, scope.requireUserId());
        return execute(row, false);
    }

    @Scheduled(cron = "20 * * * * ?")
    public void runDueTasks() {
        List<HealthAnalysisAutomation> due = mapper.selectList(new QueryWrapper<HealthAnalysisAutomation>()
                .eq("enabled", 1).le("next_run_at", LocalDateTime.now()).orderByAsc("next_run_at").last("limit 20"));
        for (HealthAnalysisAutomation row : due) {
            try {
                row.setNextRunAt(calculateNextRun(row, LocalDateTime.now()));
                mapper.updateById(row);
                CurrentUserUtil.runAsUser(row.getUserId(), () -> execute(row, true));
            } catch (Exception e) {
                markFailure(row, e);
            }
        }
    }

    private AiAnalysisRecord execute(HealthAnalysisAutomation row, boolean scheduled) {
        try {
            List<String> items = split(row.getAnalysisItems());
            String content = aiAnalysis.analyzeHealthSnapshot(row.getPatientId(), row.getAnalysisRangeDays(), items);
            AiAnalysisRecord record = new AiAnalysisRecord();
            record.setUserId(row.getUserId()); record.setPatientId(row.getPatientId());
            record.setTimeType("automation"); record.setTimeValue(LocalDate.now().toString());
            record.setPeriodLabel("most recent " + row.getAnalysisRangeDays() + "days"); record.setAnalysisContent(content);
            record.setReviewStatus("REVIEW_REQUIRED");
            record.setRemark("Automated Analysis: " + row.getTaskName()); analysisRecords.insert(record);
            Patient patient = scope.requirePatient(row.getPatientId());
            String title = "Health analysis draft ready · " + (patient == null ? row.getTaskName() : patient.getName());
            String message = "An automated analysis draft is ready for review. No clinical recommendation has been sent; open the Clinical Workbench to approve or reject it.";
            boolean notified = delivery.notifyUser(row.getUserId(), parseIds(row.getNotificationChannelIds()), title, message);
            row.setLastRunAt(LocalDateTime.now()); row.setLastAnalysisRecordId(record.getId());
            row.setLastRunStatus(notified ? "DRAFT_READY" : "DRAFT_READY_NOTIFY_FAILED");
            row.setLastError(notified ? null : "The draft was saved, but no enabled notification channel accepted the message.");
            if (!scheduled && Integer.valueOf(1).equals(row.getEnabled())) row.setNextRunAt(calculateNextRun(row, LocalDateTime.now()));
            mapper.updateById(row);
            return record;
        } catch (Exception e) {
            markFailure(row, e);
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public AiAnalysisRecord reviewAnalysis(Long id, boolean approved, boolean notify) {
        AiAnalysisRecord record = analysisRecords.selectById(id);
        Long userId = scope.requireUserId();
        if (record == null || !Objects.equals(record.getUserId(), userId)) {
            throw new IllegalArgumentException("Analysis draft does not exist.");
        }
        if (!"REVIEW_REQUIRED".equals(record.getReviewStatus())) {
            throw new IllegalArgumentException("This analysis draft has already been reviewed.");
        }
        record.setReviewStatus(approved ? "APPROVED" : "REJECTED");
        record.setReviewedBy(userId);
        record.setReviewedAt(LocalDateTime.now());
        analysisRecords.updateById(record);
        if (approved && notify) {
            Patient patient = scope.requirePatient(record.getPatientId());
            String title = "Reviewed health analysis · " + (patient == null ? "Family member" : patient.getName());
            String content = record.getAnalysisContent() == null ? "The reviewed analysis is available in Clarity Health."
                    : record.getAnalysisContent().substring(0, Math.min(record.getAnalysisContent().length(), 3500));
            delivery.notifyUser(userId, title, content);
        }
        return record;
    }

    private void markFailure(HealthAnalysisAutomation row, Exception e) {
        row.setLastRunAt(LocalDateTime.now()); row.setLastRunStatus("FAILED");
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        row.setLastError(message.substring(0, Math.min(message.length(), 500)));
        mapper.updateById(row);
    }

    private void normalize(HealthAnalysisAutomation row) {
        row.setTaskName(row.getTaskName() == null || row.getTaskName().trim().isEmpty() ? "setperiodHealth Analytics" : row.getTaskName().trim());
        row.setEnabled(row.getEnabled() == null ? 1 : row.getEnabled());
        row.setFrequencyType(row.getFrequencyType() == null ? "WEEKLY" : row.getFrequencyType().toUpperCase());
        if (!FREQUENCIES.contains(row.getFrequencyType())) throw new IllegalArgumentException("runWeekNonevalid");
        try { LocalTime.parse(row.getRunTime(), DateTimeFormatter.ofPattern("HH:mm")); }
        catch (Exception e) { throw new IllegalArgumentException("SelectRun Time"); }
        row.setIntervalDays(between(row.getIntervalDays(), 1, 30, 1));
        row.setDayOfWeek(between(row.getDayOfWeek(), 1, 7, 1));
        row.setDayOfMonth(between(row.getDayOfMonth(), 1, 28, 1));
        row.setAnalysisRangeDays(between(row.getAnalysisRangeDays(), 1, 365, 30));
        List<String> selected = split(row.getAnalysisItems()).stream().filter(ITEMS::contains).distinct().collect(Collectors.toList());
        if (selected.isEmpty()) throw new IllegalArgumentException("Please to fewselectoneitemExaminationcontent");
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
        if (count != ids.size()) throw new IllegalArgumentException("Notificationchanneldoes not exist or does not belong tocurrentuser");
        row.setNotificationChannelIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    private HealthAnalysisAutomation requireOwned(Long id, Long userId) {
        HealthAnalysisAutomation row = mapper.selectById(id);
        if (row == null || !Objects.equals(row.getUserId(), userId)) throw new IllegalArgumentException("Automated Analysistaskdoes not exist");
        return row;
    }
    private int between(Integer value,int min,int max,int fallback){int v=value==null?fallback:value;if(v<min||v>max)throw new IllegalArgumentException("Weekreferencecountexceedrange");return v;}
    private List<String> split(String value){if(value==null||value.trim().isEmpty())return new ArrayList<>();return Arrays.stream(value.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(String::toUpperCase).collect(Collectors.toList());}
    private List<Long> parseIds(String value){List<Long> ids=new ArrayList<>();if(value==null||value.trim().isEmpty())return ids;try{for(String s:value.split(","))if(!s.trim().isEmpty())ids.add(Long.valueOf(s.trim()));}catch(NumberFormatException e){throw new IllegalArgumentException("Notificationchannelformatincorrect");}return ids.stream().distinct().collect(Collectors.toList());}
}
