package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class DialysisScheduleService {
    @Autowired private DialysisScheduleMapper schedules;
    @Autowired private DialysisRecordMapper records;
    @Autowired private PatientClinicalMapper clinicalRecords;
    @Autowired private org.familyhealthcare.util.DataScopeHelper scope;

    @Transactional
    public List<DialysisSchedule> list(Long patientId) {
        scope.requirePatient(patientId);
        List<DialysisSchedule> rows = schedules.selectList(new QueryWrapper<DialysisSchedule>().eq("patient_id", patientId).orderByDesc("schedule_date"));
        Map<LocalDate, DialysisRecord> byDate = new HashMap<>();
        for (DialysisRecord r : records.selectList(new QueryWrapper<DialysisRecord>().eq("patient_id", patientId).orderByAsc("id"))) {
            if (!"INCOMPLETE".equals(r.getRecordType())) byDate.put(r.getRecordDate(), r);
        }
        for (DialysisSchedule row : rows) {
            if ("CANCELLED".equals(row.getStatus())) continue;
            DialysisRecord record = byDate.get(row.getScheduleDate());
            if (record != null && ("PLANNED".equals(row.getStatus()) || row.getCompletedRecordId() != null)) {
                if (!Objects.equals(row.getCompletedRecordId(), record.getId()) || !"COMPLETED".equals(row.getStatus())) {
                    row.setStatus("COMPLETED"); row.setCompletedRecordId(record.getId()); schedules.updateById(row);
                }
            } else if (record == null && row.getCompletedRecordId() != null) {
                schedules.update(null, new UpdateWrapper<DialysisSchedule>().eq("id", row.getId()).set("status", "PLANNED").set("completed_record_id", null));
                row.setStatus("PLANNED"); row.setCompletedRecordId(null);
            }
        }
        return rows;
    }

    public List<DialysisSchedule> previewPlan(Long patientId, String weekdays, String time,
                                              LocalDate from, LocalDate to) {
        scope.requirePatient(patientId);
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Choose a valid schedule date range.");
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 92) throw new IllegalArgumentException("Preview no more than 93 days at a time.");
        Set<Integer> days = parseWeekdays(weekdays);
        String normalizedTime = normalizeTime(time);
        List<DialysisSchedule> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!days.contains(date.getDayOfWeek().getValue())) continue;
            DialysisSchedule row = new DialysisSchedule();
            row.setPatientId(patientId);
            row.setScheduleDate(date);
            row.setScheduleTime(normalizedTime);
            row.setStatus("PLANNED");
            row.setRemark("Generated from confirmed dialysis plan");
            result.add(row);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> generatePlan(Long patientId, String weekdays, String time,
                                            LocalDate from, LocalDate to, boolean confirmed) {
        List<DialysisSchedule> preview = previewPlan(patientId, weekdays, time, from, to);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("preview", preview);
        response.put("created", 0);
        response.put("skipped", 0);
        response.put("requiresConfirmation", !confirmed);
        if (!confirmed) return response;

        Long userId = scope.requireUserId();
        int created = 0;
        int skipped = 0;
        for (DialysisSchedule row : preview) {
            row.setUserId(userId);
            try {
                schedules.insert(row);
                created++;
            } catch (DuplicateKeyException ignored) {
                skipped++;
            }
        }
        PatientClinical clinical = clinicalRecords.selectOne(new QueryWrapper<PatientClinical>().eq("patient_id", patientId).last("limit 1"));
        if (clinical == null) {
            clinical = new PatientClinical();
            clinical.setPatientId(patientId);
            clinical.setUserId(userId);
            clinical.setDialysisWeekdays(canonicalWeekdays(weekdays));
            clinical.setDialysisTime(normalizeTime(time));
            clinicalRecords.insert(clinical);
        } else {
            clinical.setDialysisWeekdays(canonicalWeekdays(weekdays));
            clinical.setDialysisTime(normalizeTime(time));
            clinicalRecords.updateById(clinical);
        }
        response.put("created", created);
        response.put("skipped", skipped);
        response.put("requiresConfirmation", false);
        return response;
    }

    private Set<Integer> parseWeekdays(String weekdays) {
        Set<Integer> days = new TreeSet<>();
        if (weekdays != null) {
            for (String value : weekdays.split(",")) {
                try {
                    int day = Integer.parseInt(value.trim());
                    if (day >= 1 && day <= 7) days.add(day);
                } catch (NumberFormatException ignored) { }
            }
        }
        if (days.isEmpty()) throw new IllegalArgumentException("Select at least one confirmed dialysis weekday.");
        return days;
    }

    private String canonicalWeekdays(String weekdays) {
        return parseWeekdays(weekdays).stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private String normalizeTime(String time) {
        if (time == null || time.trim().isEmpty()) return null;
        try { return LocalTime.parse(time.trim()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Dialysis time must use HH:mm."); }
    }

    @Transactional
    public DialysisSchedule save(DialysisSchedule row) {
        scope.requirePatient(row.getPatientId());
        if (row.getScheduleDate() == null) throw new IllegalArgumentException("SelectscheduleDate");
        if (row.getScheduleTime() != null && !row.getScheduleTime().isEmpty()) {
            try { row.setScheduleTime(LocalTime.parse(row.getScheduleTime()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))); }
            catch (RuntimeException e) { throw new IllegalArgumentException("scheduleTimeformatincorrect"); }
        }
        if (row.getStatus() == null) row.setStatus("PLANNED");
        if (!Arrays.asList("PLANNED", "COMPLETED", "CANCELLED").contains(row.getStatus())) throw new IllegalArgumentException("scheduleInvalid status.");
        if (row.getId() != null) {
            DialysisSchedule old = schedules.selectById(row.getId());
            if (old == null || !Objects.equals(old.getPatientId(), row.getPatientId())) throw new IllegalArgumentException("scheduledoes not exist or does not belong tocurrentFamily Member");
            scope.requirePatient(old.getPatientId()); row.setUserId(old.getUserId());
        } else row.setUserId(scope.requireUserId());
        QueryWrapper<DialysisSchedule> duplicate = new QueryWrapper<DialysisSchedule>().eq("patient_id", row.getPatientId()).eq("schedule_date", row.getScheduleDate());
        if (row.getId() != null) duplicate.ne("id", row.getId());
        if (schedules.selectCount(duplicate) > 0) throw new IllegalArgumentException("this Datealready has schedule, Please Editoriginalschedule");
        row.setCompletedRecordId(null);
        if (row.getId() == null) schedules.insert(row);
        else {
            schedules.updateById(row);
            schedules.update(null, new UpdateWrapper<DialysisSchedule>().eq("id", row.getId()).set("completed_record_id", null));
        }
        list(row.getPatientId());
        return schedules.selectById(row.getId());
    }
}
