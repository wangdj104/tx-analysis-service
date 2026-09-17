package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.AlertRecord;
import org.familyhealthcare.entity.BpSelfMonitorRecord;
import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.entity.DialysisSchedule;
import org.familyhealthcare.entity.DryWeightMonthly;
import org.familyhealthcare.entity.HealthEvent;
import org.familyhealthcare.entity.Medication;
import org.familyhealthcare.entity.MedicationIntake;
import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.entity.PatientHealthTarget;
import org.familyhealthcare.mapper.AlertRecordMapper;
import org.familyhealthcare.mapper.BpSelfMonitorRecordMapper;
import org.familyhealthcare.mapper.DialysisRecordMapper;
import org.familyhealthcare.mapper.DialysisScheduleMapper;
import org.familyhealthcare.mapper.DryWeightMonthlyMapper;
import org.familyhealthcare.mapper.HealthEventMapper;
import org.familyhealthcare.mapper.MedicationIntakeMapper;
import org.familyhealthcare.mapper.MedicationMapper;
import org.familyhealthcare.mapper.PatientHealthTargetMapper;
import org.familyhealthcare.service.MonitoringService;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.MonitoringSnapshotVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MonitoringServiceImpl implements MonitoringService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("H:mm");

    @Autowired private DataScopeHelper dataScopeHelper;
    @Autowired private BpSelfMonitorRecordMapper monitorMapper;
    @Autowired private AlertRecordMapper alertMapper;
    @Autowired private MedicationIntakeMapper intakeMapper;
    @Autowired private MedicationMapper medicationMapper;
    @Autowired private DialysisScheduleMapper scheduleMapper;
    @Autowired private DialysisRecordMapper dialysisMapper;
    @Autowired private DryWeightMonthlyMapper dryWeightMapper;
    @Autowired private HealthEventMapper eventMapper;
    @Autowired private org.familyhealthcare.service.DialysisScheduleService scheduleService;
    @Autowired private PatientHealthTargetMapper targetMapper;
    @Autowired private org.familyhealthcare.service.MedicationReminderService reminderService;
    @Autowired private org.familyhealthcare.service.HealthTimelineService timelineService;

    @Override
    public MonitoringSnapshotVO getSnapshot(Long patientId, Integer days) {
        if (patientId == null) {
            throw new IllegalArgumentException("Select a patient");
        }
        Patient patient = dataScopeHelper.requirePatient(patientId);
        int rangeDays = days == null ? 7 : Math.max(1, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(rangeDays - 1L);
        LocalDateTime now = LocalDateTime.now();

        PatientHealthTarget target = targetMapper.selectOne(
                new QueryWrapper<PatientHealthTarget>().eq("patient_id", patientId).last("limit 1"));

        List<BpSelfMonitorRecord> monitorRows = monitorMapper.selectList(
                new QueryWrapper<BpSelfMonitorRecord>()
                        .eq("patient_id", patientId)
                        .ge("record_date", from)
                        .orderByAsc("record_date")
                        .orderByAsc("record_time"));

        List<AlertRecord> activeAlerts = alertMapper.selectList(
                new QueryWrapper<AlertRecord>()
                        .eq("patient_id", patientId)
                        .and(q -> q.isNull("status").or().ne("status", "RESOLVED"))
                        .orderByDesc("triggered_at")
                        .last("limit 100"));

        reminderService.ensureDailyTasks(patientId, today);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        List<MedicationIntake> intakes = intakeMapper.selectList(
                new QueryWrapper<MedicationIntake>()
                        .eq("patient_id", patientId)
                        .and(q -> q.ge("scheduled_at", dayStart).lt("scheduled_at", dayEnd)
                                .or().ge("snooze_until", dayStart).lt("snooze_until", dayEnd))
                        .orderByAsc("scheduled_at"));

        List<DialysisSchedule> schedules = scheduleService.list(patientId).stream()
                .filter(r -> today.equals(r.getScheduleDate())).collect(java.util.stream.Collectors.toList());

        List<DialysisRecord> dialysisRows = dialysisMapper.selectList(
                new QueryWrapper<DialysisRecord>()
                        .eq("patient_id", patientId)
                        .orderByDesc("record_date")
                        .orderByDesc("id")
                        .last("limit 1"));
        DialysisRecord latestDialysis = dialysisRows.isEmpty() ? null : dialysisRows.get(0);

        List<DryWeightMonthly> dryWeights = dryWeightMapper.selectList(
                new QueryWrapper<DryWeightMonthly>()
                        .eq("patient_id", patientId)
                        .orderByDesc("`year_month`")
                        .last("limit 1"));
        DryWeightMonthly latestDryWeight = dryWeights.isEmpty() ? null : dryWeights.get(0);

        List<HealthEvent> events = timelineService.list(patientId, null, null, 30);

        BpSelfMonitorRecord latestBp = null;
        BpSelfMonitorRecord latestGlucose = null;
        for (BpSelfMonitorRecord row : monitorRows) {
            if (row.getSystolicBp() != null || row.getDiastolicBp() != null) latestBp = row;
            if (row.getBloodGlucose() != null) latestGlucose = row;
        }

        MonitoringSnapshotVO result = new MonitoringSnapshotVO();
        result.setGeneratedAt(now);
        result.setPatient(toPatientSummary(patient));
        result.setVitalTrend(buildTrend(monitorRows, target));
        result.setActiveAlerts(buildAlerts(activeAlerts));
        result.setTodayTasks(buildTasks(intakes, schedules));
        result.setRecentEvents(buildEvents(events));
        result.setLatestVitals(buildLatestVitals(latestBp, latestGlucose, latestDialysis, latestDryWeight));

        List<MonitoringSnapshotVO.Signal> signals = new ArrayList<>();
        signals.add(buildBloodPressureSignal(latestBp, target, now));
        signals.add(buildGlucoseSignal(latestGlucose, target, now));
        signals.add(buildDialysisSignal(latestDialysis, now));
        signals.add(buildMedicationSignal(intakes, now));
        result.setSignals(signals);

        MonitoringSnapshotVO.Metrics metrics = buildMetrics(activeAlerts, result.getTodayTasks(), signals);
        result.setMetrics(metrics);
        resolveOverallStatus(result, latestBp, latestGlucose, target);
        result.setLastDataAt(resolveLastDataAt(latestBp, latestGlucose, latestDialysis, activeAlerts, events));
        result.setCareSuggestions(buildSuggestions(result, latestBp, latestGlucose, intakes));
        return result;
    }

    private MonitoringSnapshotVO.PatientSummary toPatientSummary(Patient patient) {
        MonitoringSnapshotVO.PatientSummary summary = new MonitoringSnapshotVO.PatientSummary();
        summary.setId(patient.getId());
        summary.setName(patient.getName());
        summary.setGender(patient.getGender());
        if (patient.getBirthDate() != null && !patient.getBirthDate().isAfter(LocalDate.now())) {
            summary.setAge(Period.between(patient.getBirthDate(), LocalDate.now()).getYears());
        }
        summary.setEmergencyContact(patient.getEmergencyContact());
        summary.setEmergencyPhone(patient.getEmergencyPhone());
        return summary;
    }

    private List<MonitoringSnapshotVO.VitalPoint> buildTrend(
            List<BpSelfMonitorRecord> rows, PatientHealthTarget target) {
        List<MonitoringSnapshotVO.VitalPoint> points = new ArrayList<>();
        for (BpSelfMonitorRecord row : rows) {
            MonitoringSnapshotVO.VitalPoint point = new MonitoringSnapshotVO.VitalPoint();
            point.setDate(row.getRecordDate());
            point.setTime(row.getRecordTime());
            point.setSystolic(row.getSystolicBp());
            point.setDiastolic(row.getDiastolicBp());
            point.setGlucose(row.getBloodGlucose());
            point.setGlucoseUnit(row.getBgUnit());
            point.setMeasurePeriod(row.getMeasurePeriod());
            point.setAbnormal(isBpAbnormal(row, target) || isGlucoseAbnormal(row, target));
            points.add(point);
        }
        return points;
    }

    private List<MonitoringSnapshotVO.AlertItem> buildAlerts(List<AlertRecord> rows) {
        List<MonitoringSnapshotVO.AlertItem> items = new ArrayList<>();
        for (AlertRecord row : rows) {
            MonitoringSnapshotVO.AlertItem item = new MonitoringSnapshotVO.AlertItem();
            item.setId(row.getId());
            item.setType(row.getAlertType());
            item.setLevel(row.getAlertLevel());
            item.setTitle(row.getAlertTitle());
            item.setValue(row.getTriggeredValue());
            item.setTriggeredAt(row.getTriggeredAt());
            item.setStatus(row.getStatus());
            item.setHandlingNote(row.getHandlingNote());
            items.add(item);
        }
        return items;
    }

    private List<MonitoringSnapshotVO.TaskItem> buildTasks(
            List<MedicationIntake> intakes, List<DialysisSchedule> schedules) {
        Map<Long, Medication> medicationMap = loadMedications(intakes);
        List<MonitoringSnapshotVO.TaskItem> tasks = new ArrayList<>();
        for (MedicationIntake intake : intakes) {
            Medication medication = medicationMap.get(intake.getMedicationId());
            MonitoringSnapshotVO.TaskItem task = new MonitoringSnapshotVO.TaskItem();
            task.setId(intake.getId());
            task.setTaskType("MEDICATION");
            task.setTitle(medication != null ? medication.getDrugName() : "by timemedication intake");
            task.setScheduledAt(intake.getSnoozeUntil() != null ? intake.getSnoozeUntil() : intake.getScheduledAt());
            task.setStatus(intake.getStatus());
            task.setDosage(intake.getDosage());
            task.setDescription(intake.getReason());
            tasks.add(task);
        }
        for (DialysisSchedule schedule : schedules) {
            MonitoringSnapshotVO.TaskItem task = new MonitoringSnapshotVO.TaskItem();
            task.setId(schedule.getId());
            task.setTaskType("DIALYSIS");
            task.setTitle("Dialysisschedule");
            task.setScheduledAt(schedule.getScheduleDate().atTime(parseTime(schedule.getScheduleTime())));
            task.setStatus(schedule.getStatus());
            task.setDescription(schedule.getRemark());
            tasks.add(task);
        }
        Collections.sort(tasks, Comparator.comparing(
                MonitoringSnapshotVO.TaskItem::getScheduledAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return tasks;
    }

    private Map<Long, Medication> loadMedications(List<MedicationIntake> intakes) {
        Set<Long> ids = new LinkedHashSet<>();
        for (MedicationIntake intake : intakes) {
            if (intake.getMedicationId() != null) ids.add(intake.getMedicationId());
        }
        if (ids.isEmpty()) return Collections.emptyMap();
        List<Medication> medications = medicationMapper.selectBatchIds(ids);
        Map<Long, Medication> map = new HashMap<>();
        for (Medication medication : medications) map.put(medication.getId(), medication);
        return map;
    }

    private List<MonitoringSnapshotVO.EventItem> buildEvents(List<HealthEvent> rows) {
        List<MonitoringSnapshotVO.EventItem> items = new ArrayList<>();
        for (HealthEvent row : rows) {
            MonitoringSnapshotVO.EventItem item = new MonitoringSnapshotVO.EventItem();
            item.setId(row.getId());
            item.setDate(row.getEventDate());
            item.setTime(row.getEventTime());
            item.setType(row.getEventType());
            item.setTitle(row.getTitle());
            item.setSummary(row.getSummary() != null ? row.getSummary() : row.getRemark());
            item.setSourceType(row.getSourceType());
            item.setStatus(row.getStatus());
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> buildLatestVitals(
            BpSelfMonitorRecord bp, BpSelfMonitorRecord glucose,
            DialysisRecord dialysis, DryWeightMonthly dryWeight) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("systolic", bp == null ? null : bp.getSystolicBp());
        values.put("diastolic", bp == null ? null : bp.getDiastolicBp());
        values.put("bloodPressureAt", recordDateTime(bp));
        values.put("glucose", glucose == null ? null : glucose.getBloodGlucose());
        values.put("glucoseUnit", glucose == null ? null : defaultText(glucose.getBgUnit(), "mmol/L"));
        values.put("glucosePeriod", glucose == null ? null : glucose.getMeasurePeriod());
        values.put("glucoseAt", recordDateTime(glucose));
        values.put("onWeight", dialysis == null ? null : dialysis.getOnWeight());
        values.put("offWeight", dialysis == null ? null : dialysis.getOffWeight());
        values.put("weightGain", dialysis == null ? null : dialysis.getWeightGain());
        values.put("dehydrationStatus", dialysis == null ? null : dialysis.getDehydrationStatus());
        values.put("dialysisDate", dialysis == null ? null : dialysis.getRecordDate());
        values.put("dryWeight", dryWeight == null ? null : dryWeight.getDryWeight());
        return values;
    }

    private MonitoringSnapshotVO.Signal buildBloodPressureSignal(
            BpSelfMonitorRecord latest, PatientHealthTarget target, LocalDateTime now) {
        LocalDateTime at = recordDateTime(latest);
        if (latest == null || latest.getSystolicBp() == null) {
            return signal("bloodPressure", "Blood Pressuremonitoring", "NO_DATA", "No data", "—", "mmHg", null, "etc.pendingfirsttimesmeasurement");
        }
        String status = bpSeverity(latest, target);
        long hours = hoursSince(at, now);
        if ("NORMAL".equals(status) && hours > 24) status = "DELAYED";
        String label = statusLabel(status);
        String value = latest.getSystolicBp() + "/" + (latest.getDiastolicBp() == null ? "—" : latest.getDiastolicBp());
        return signal("bloodPressure", "Blood Pressuremonitoring", status, label, value, "mmHg", at, freshness(at, now));
    }

    private MonitoringSnapshotVO.Signal buildGlucoseSignal(
            BpSelfMonitorRecord latest, PatientHealthTarget target, LocalDateTime now) {
        LocalDateTime at = recordDateTime(latest);
        if (latest == null || latest.getBloodGlucose() == null) {
            return signal("glucose", "Blood Glucosemonitoring", "NO_DATA", "No data", "—", "mmol/L", null, "etc.pendingfirsttimesmeasurement");
        }
        String status = glucoseSeverity(latest, target);
        long hours = hoursSince(at, now);
        if ("NORMAL".equals(status) && hours > 24) status = "DELAYED";
        return signal("glucose", "Blood Glucosemonitoring", status, statusLabel(status),
                latest.getBloodGlucose().stripTrailingZeros().toPlainString(),
                defaultText(latest.getBgUnit(), "mmol/L"), at, freshness(at, now));
    }

    private MonitoringSnapshotVO.Signal buildDialysisSignal(DialysisRecord latest, LocalDateTime now) {
        if (latest == null || latest.getRecordDate() == null) {
            return signal("dialysis", "Dialysismonitoring", "NO_DATA", "No data", "—", "", null, "etc.pendingfirsttimesrecord");
        }
        LocalDateTime at = latest.getRecordDate().atTime(23, 59);
        String status = "MATCH".equals(latest.getDehydrationStatus()) ? "NORMAL" : "WARNING";
        if (hoursSince(at, now) > 96 && "NORMAL".equals(status)) status = "DELAYED";
        String value = latest.getWeightGain() == null ? "—" : latest.getWeightGain().stripTrailingZeros().toPlainString();
        return signal("dialysis", "Dialysismonitoring", status, statusLabel(status), value, "kg weight gain", at, freshness(at, now));
    }

    private MonitoringSnapshotVO.Signal buildMedicationSignal(List<MedicationIntake> intakes, LocalDateTime now) {
        intakes = intakes.stream().filter(i -> !"CANCELLED".equals(i.getStatus())).collect(java.util.stream.Collectors.toList());
        if (intakes.isEmpty()) {
            return signal("medication", "Today's medications", "NO_DATA", "No plan", "0/0", "", null, "No medication tasks are scheduled for today");
        }
        int completed = 0;
        int overdue = 0;
        LocalDateTime latest = null;
        for (MedicationIntake item : intakes) {
            if ("TAKEN".equals(item.getStatus())) completed++;
            LocalDateTime due = item.getSnoozeUntil() != null ? item.getSnoozeUntil() : item.getScheduledAt();
            if (("PENDING".equals(item.getStatus()) || "MISSED".equals(item.getStatus()) || "SNOOZED".equals(item.getStatus()))
                    && due != null && due.isBefore(now.minusMinutes(60))) overdue++;
            LocalDateTime candidate = item.getActionAt() != null ? item.getActionAt() : item.getScheduledAt();
            latest = max(latest, candidate);
        }
        String status = overdue > 0 ? "WARNING" : (completed == intakes.size() ? "NORMAL" : "PENDING");
        String label = overdue > 0 ? "storein overdueperiod" : (completed == intakes.size() ? "Allcomplete" : "In Progress");
        return signal("medication", "Today's medications", status, label,
                completed + "/" + intakes.size(), "times", latest, overdue > 0 ? overdue + " itemPending" : "by today Dayplanstatistics");
    }

    private MonitoringSnapshotVO.Signal signal(String key, String label, String status,
                                                String statusText, String value, String unit,
                                                LocalDateTime at, String freshness) {
        MonitoringSnapshotVO.Signal signal = new MonitoringSnapshotVO.Signal();
        signal.setKey(key);
        signal.setLabel(label);
        signal.setStatus(status);
        signal.setStatusLabel(statusText);
        signal.setValue(value);
        signal.setUnit(unit);
        signal.setUpdatedAt(at);
        signal.setFreshnessText(freshness);
        return signal;
    }

    private MonitoringSnapshotVO.Metrics buildMetrics(
            List<AlertRecord> alerts, List<MonitoringSnapshotVO.TaskItem> tasks,
            List<MonitoringSnapshotVO.Signal> signals) {
        MonitoringSnapshotVO.Metrics metrics = new MonitoringSnapshotVO.Metrics();
        metrics.setActiveAlertCount(alerts.size());
        int critical = 0;
        for (AlertRecord alert : alerts) if ("CRITICAL".equals(alert.getAlertLevel())) critical++;
        metrics.setCriticalAlertCount(critical);
        tasks = tasks.stream().filter(t -> !"CANCELLED".equals(t.getStatus())).collect(java.util.stream.Collectors.toList());
        metrics.setTodayTaskCount(tasks.size());
        int completed = 0;
        for (MonitoringSnapshotVO.TaskItem task : tasks) {
            if ("TAKEN".equals(task.getStatus()) || "COMPLETED".equals(task.getStatus())) completed++;
        }
        metrics.setCompletedTaskCount(completed);
        metrics.setAdherenceRate(tasks.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(completed * 100.0 / tasks.size()).setScale(0, RoundingMode.HALF_UP));
        int available = 0;
        for (MonitoringSnapshotVO.Signal signal : signals) {
            if (!"NO_DATA".equals(signal.getStatus())) available++;
        }
        metrics.setDataCompleteness(signals.isEmpty() ? 0 :
                (int) Math.round(available * 100.0 / signals.size()));
        return metrics;
    }

    private void resolveOverallStatus(MonitoringSnapshotVO result,
                                      BpSelfMonitorRecord bp, BpSelfMonitorRecord glucose,
                                      PatientHealthTarget target) {
        boolean hasData = false;
        boolean warning = false;
        boolean critical = result.getMetrics().getCriticalAlertCount() > 0;
        for (MonitoringSnapshotVO.Signal signal : result.getSignals()) {
            if (!"NO_DATA".equals(signal.getStatus())) hasData = true;
            if ("WARNING".equals(signal.getStatus()) || "DELAYED".equals(signal.getStatus())) warning = true;
            if ("CRITICAL".equals(signal.getStatus())) critical = true;
        }
        if ("CRITICAL".equals(bpSeverity(bp, target)) || "CRITICAL".equals(glucoseSeverity(glucose, target))) critical = true;
        if (critical) {
            result.setOverallStatus("CRITICAL");
            result.setStatusLabel("needneedimmediatelyattention");
        } else if (warning || result.getMetrics().getActiveAlertCount() > 0) {
            result.setOverallStatus("WARNING");
            result.setStatusLabel("storein Pendingitem");
        } else if (!hasData) {
            result.setOverallStatus("NO_DATA");
            result.setStatusLabel("etc.pendinghealthdata");
        } else {
            result.setOverallStatus("STABLE");
            result.setStatusLabel("currentStatusstable");
        }
    }

    private List<String> buildSuggestions(MonitoringSnapshotVO result,
                                          BpSelfMonitorRecord bp, BpSelfMonitorRecord glucose,
                                          List<MedicationIntake> intakes) {
        List<String> suggestions = new ArrayList<>();
        if ("CRITICAL".equals(result.getOverallStatus())) {
            suggestions.add("storein SevereAbnormal, Please immediatelyremeasure; for example companionfollowclearshowdiscomfort, Please as prescribedcontactClinician or andtimethenmedical. ");
        }
        if (bp == null) suggestions.add("No recent blood pressure record is available. Consider taking one resting measurement.");
        else if ("WARNING".equals(bpSeverity(bp, null))) suggestions.add("most recent Blood Pressureexceedcommonrange, recommendationrestafter remeasureanddurationrecord. ");
        if (glucose == null) suggestions.add("No recent blood glucose record is available. Add one if it is part of the care plan.");
        int pending = 0;
        for (MedicationIntake item : intakes) {
            if ("PENDING".equals(item.getStatus()) || "MISSED".equals(item.getStatus()) || "SNOOZED".equals(item.getStatus())) pending++;
        }
        if (pending > 0) suggestions.add("Todaystillhas  " + pending + " itemmedicationtaskPending, Please verifyactualTakecondition. ");
        if (result.getMetrics().getDataCompleteness() < 50) suggestions.add("currentmonitoringdatacovernot enough, continuousrecordafter trenddeterminewillmorecan rely on. ");
        if (suggestions.isEmpty()) suggestions.add("currentnohas Urgentitem, continueby planrecord, medication and follow-up examination. ");
        return suggestions;
    }

    private boolean isBpAbnormal(BpSelfMonitorRecord row, PatientHealthTarget target) {
        return !"NORMAL".equals(bpSeverity(row, target)) && !"NO_DATA".equals(bpSeverity(row, target));
    }

    private String bpSeverity(BpSelfMonitorRecord row, PatientHealthTarget target) {
        if (row == null || row.getSystolicBp() == null) return "NO_DATA";
        int s = row.getSystolicBp();
        int d = row.getDiastolicBp() == null ? 0 : row.getDiastolicBp();
        if (s >= 180 || (d > 0 && d >= 120) || s < 80 || (d > 0 && d < 50)) return "CRITICAL";
        int sMin = target != null && target.getSystolicMin() != null ? target.getSystolicMin() : 90;
        int sMax = target != null && target.getSystolicMax() != null ? target.getSystolicMax() : 140;
        int dMin = target != null && target.getDiastolicMin() != null ? target.getDiastolicMin() : 60;
        int dMax = target != null && target.getDiastolicMax() != null ? target.getDiastolicMax() : 90;
        return s < sMin || s > sMax || (d > 0 && (d < dMin || d > dMax)) ? "WARNING" : "NORMAL";
    }

    private boolean isGlucoseAbnormal(BpSelfMonitorRecord row, PatientHealthTarget target) {
        String severity = glucoseSeverity(row, target);
        return !"NORMAL".equals(severity) && !"NO_DATA".equals(severity);
    }

    private String glucoseSeverity(BpSelfMonitorRecord row, PatientHealthTarget target) {
        if (row == null || row.getBloodGlucose() == null) return "NO_DATA";
        BigDecimal value = row.getBloodGlucose();
        if (value.compareTo(new BigDecimal("3.0")) < 0 || value.compareTo(new BigDecimal("16.7")) > 0) return "CRITICAL";
        boolean postMeal = row.getMeasurePeriod() != null && row.getMeasurePeriod().contains("After Meal");
        BigDecimal min = target == null ? null : (postMeal ? target.getPostmealGlucoseMin() : target.getFastingGlucoseMin());
        BigDecimal max = target == null ? null : (postMeal ? target.getPostmealGlucoseMax() : target.getFastingGlucoseMax());
        if (min == null) min = new BigDecimal("3.9");
        if (max == null) max = postMeal ? new BigDecimal("7.8") : new BigDecimal("6.1");
        return value.compareTo(min) < 0 || value.compareTo(max) > 0 ? "WARNING" : "NORMAL";
    }

    private String statusLabel(String status) {
        if ("CRITICAL".equals(status)) return "SevereAbnormal";
        if ("WARNING".equals(status)) return "needneedattention";
        if ("DELAYED".equals(status)) return "datadelay";
        if ("PENDING".equals(status)) return "In Progress";
        if ("NO_DATA".equals(status)) return "No data";
        return "Normal";
    }

    private LocalDateTime resolveLastDataAt(BpSelfMonitorRecord bp, BpSelfMonitorRecord glucose,
                                            DialysisRecord dialysis, List<AlertRecord> alerts,
                                            List<HealthEvent> events) {
        LocalDateTime latest = max(recordDateTime(bp), recordDateTime(glucose));
        if (dialysis != null && dialysis.getRecordDate() != null) latest = max(latest, dialysis.getRecordDate().atTime(23, 59));
        if (!alerts.isEmpty()) latest = max(latest, alerts.get(0).getTriggeredAt());
        if (!events.isEmpty()) latest = max(latest, eventDateTime(events.get(0)));
        return latest;
    }

    private LocalDateTime recordDateTime(BpSelfMonitorRecord row) {
        if (row == null || row.getRecordDate() == null) return null;
        return row.getRecordDate().atTime(parseTime(row.getRecordTime()));
    }

    private LocalDateTime eventDateTime(HealthEvent event) {
        if (event == null || event.getEventDate() == null) return null;
        return event.getEventDate().atTime(parseTime(event.getEventTime()));
    }

    private LocalTime parseTime(String text) {
        if (text == null || text.trim().isEmpty()) return LocalTime.NOON;
        try {
            return LocalTime.parse(text.trim(), HM);
        } catch (DateTimeParseException ignored) {
            try { return LocalTime.parse(text.trim()); } catch (DateTimeParseException e) { return LocalTime.NOON; }
        }
    }

    private long hoursSince(LocalDateTime at, LocalDateTime now) {
        if (at == null) return Long.MAX_VALUE;
        return Math.max(0, Duration.between(at, now).toHours());
    }

    private String freshness(LocalDateTime at, LocalDateTime now) {
        if (at == null) return "No updates";
        long minutes = Math.max(0, Duration.between(at, now).toMinutes());
        if (minutes < 1) return "just nowupdate";
        if (minutes < 60) return minutes + " minutesbefore ";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hoursbefore ";
        return (hours / 24) + " daysbefore ";
    }

    private LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
