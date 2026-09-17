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
    @Autowired private org.familyhealthcare.util.DataScopeHelper scope;

    @Transactional
    public List<DialysisSchedule> list(Long patientId) {
        ensureDefaultMonths(patientId, LocalDate.now());
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

    /**
     * firsttimesViewoneMonthtime, by weekone, weekfourgenerateDefaultschedule. This MonthonlygenerateTodayandtoafter ,
     * down MonthgeneratewholeMonth. onlyneedthis Monthalready has anyschedule (includeCancel schedule) , convenientviewfor useralready adjust,
     * after continuenot againAutomaticcover or supplementreturn.
     */
    private void ensureDefaultMonths(Long patientId, LocalDate today) {
        Long userId = null;
        for (int offset = 0; offset <= 1; offset++) {
            YearMonth month = YearMonth.from(today).plusMonths(offset);
            LocalDate monthStart = month.atDay(1);
            LocalDate monthEnd = month.atEndOfMonth();
            long existing = schedules.selectCount(new QueryWrapper<DialysisSchedule>()
                    .eq("patient_id", patientId)
                    .ge("schedule_date", monthStart)
                    .le("schedule_date", monthEnd));
            if (existing > 0) continue;

            if (userId == null) userId = scope.requireUserId();
            LocalDate firstDate = offset == 0 && today.isAfter(monthStart) ? today : monthStart;
            for (LocalDate date = firstDate; !date.isAfter(monthEnd); date = date.plusDays(1)) {
                if (date.getDayOfWeek() != DayOfWeek.MONDAY && date.getDayOfWeek() != DayOfWeek.THURSDAY) continue;
                DialysisSchedule row = new DialysisSchedule();
                row.setUserId(userId);
                row.setPatientId(patientId);
                row.setScheduleDate(date);
                row.setStatus("PLANNED");
                row.setRemark("weekone, weekfourDefaultschedule");
                try {
                    schedules.insert(row);
                } catch (DuplicateKeyException ignored) {
                    // twopagesametimeopentimebydatadatabaseonlyonekeyfallback, already has schedulekeep.
                }
            }
        }
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
