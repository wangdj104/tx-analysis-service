package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.Medication;
import org.familyhealthcare.entity.MedicationReminder;
import org.familyhealthcare.mapper.MedicationMapper;
import org.familyhealthcare.mapper.MedicationReminderMapper;
import org.familyhealthcare.mapper.MedicationIntakeMapper;
import org.familyhealthcare.entity.MedicationIntake;
import org.familyhealthcare.service.MedicationReminderService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Medication RemindersplanServiceimplement
 */
@Service
public class MedicationReminderServiceImpl extends ServiceImpl<MedicationReminderMapper, MedicationReminder> implements MedicationReminderService {

    private static final Logger log = LoggerFactory.getLogger(MedicationReminderServiceImpl.class);

    @Autowired
    private DataScopeHelper dataScopeHelper;

    @Autowired
    private MedicationMapper medicationMapper;

    @Autowired
    private MedicationIntakeMapper medicationIntakeMapper;

    @Override
    public List<MedicationReminder> listByPatient(Long patientId) {
        if (patientId == null) {
            return java.util.Collections.emptyList();
        }
        Long userId = dataScopeHelper.requireUserId();
        QueryWrapper<MedicationReminder> qw = new QueryWrapper<>();
        if (!CurrentUserUtil.isAdmin()) {
            dataScopeHelper.applyUserScope(qw);
        }
        qw.eq("patient_id", patientId).orderByAsc("remind_time");
        List<MedicationReminder> list = baseMapper.selectList(qw);
        // populateMedicationinformation
        for (MedicationReminder reminder : list) {
            if (reminder.getMedicationId() != null) {
                Medication med = medicationMapper.selectById(reminder.getMedicationId());
                reminder.setMedication(med);
            }
        }
        return list;
    }

    @Override
    public boolean saveOrUpdateReminder(MedicationReminder record) {
        Long userId = dataScopeHelper.requireUserId();
        if (record.getPatientId() == null) {
            throw new IllegalStateException("Select a patient");
        }
        dataScopeHelper.requirePatient(record.getPatientId());
        Medication medication = medicationMapper.selectById(record.getMedicationId());
        if (medication == null || !record.getPatientId().equals(medication.getPatientId()))
            throw new IllegalArgumentException("SelectcurrentFamily Member Medication");
        try { record.setRemindTime(LocalTime.parse(record.getRemindTime()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))); }
        catch (RuntimeException e) { throw new IllegalArgumentException("ReminderTimeformatshouldfor  HH:mm"); }
        if (record.getRepeatDays() != null && !record.getRepeatDays().trim().isEmpty()
                && java.util.Arrays.stream(record.getRepeatDays().split(",")).anyMatch(day -> !day.trim().matches("[1-7]")))
            throw new IllegalArgumentException("duplicateWeekshouldfor weekoneto weekDay");
        record.setUserId(userId);
        if (record.getEnabled() == null) {
            record.setEnabled(1);
        }
        if (record.getId() != null) {
            MedicationReminder existing = getById(record.getId());
            if (existing == null) return false;
            dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
            if (!record.getPatientId().equals(existing.getPatientId())) throw new IllegalArgumentException("cannotwill Remindermoveto OtherFamily Member");
            record.setUserId(existing.getUserId());
            record.setLastTriggerAt(existing.getLastTriggerAt());
            return updateById(record);
        } else {
            return save(record);
        }
    }

    @Override
    public boolean toggleEnabled(Long id) {
        MedicationReminder existing = getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        existing.setEnabled(existing.getEnabled() != null && existing.getEnabled() == 1 ? 0 : 1);
        return updateById(existing);
    }

    @Override
    public boolean deleteOwned(Long id) {
        MedicationReminder existing = getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        return removeById(id);
    }

    @Autowired private org.familyhealthcare.service.NotificationDeliveryService delivery;

    private boolean matches(MedicationReminder reminder, java.time.LocalDate date) {
        if (!Integer.valueOf(1).equals(reminder.getEnabled())) return false;
        if (reminder.getCreatedAt() != null && date.isBefore(reminder.getCreatedAt().toLocalDate())) return false;
        Medication med = reminder.getMedicationId() == null ? null : medicationMapper.selectById(reminder.getMedicationId());
        if (med == null || !Integer.valueOf(1).equals(med.getIsActive())) return false;
        String days = reminder.getRepeatDays();
        return days == null || days.trim().isEmpty() || java.util.Arrays.stream(days.split(","))
                .anyMatch(day -> day.trim().equals(String.valueOf(date.getDayOfWeek().getValue())));
    }

    @Override
    public synchronized void ensureDailyTasks(Long patientId, java.time.LocalDate date) {
        QueryWrapper<MedicationReminder> query = new QueryWrapper<>();
        if (patientId != null) query.eq("patient_id", patientId);
        List<MedicationReminder> reminders = baseMapper.selectList(query);
        java.util.Map<Long, MedicationReminder> valid = new java.util.HashMap<>();
        for (MedicationReminder reminder : reminders) {
            if (!matches(reminder, date)) continue;
            LocalDateTime scheduled;
            try { scheduled = date.atTime(LocalTime.parse(reminder.getRemindTime())); }
            catch (RuntimeException e) { log.warn("Invalid reminder time, planID={}", reminder.getId()); continue; }
            valid.put(reminder.getId(), reminder);
            QueryWrapper<MedicationIntake> q = new QueryWrapper<MedicationIntake>()
                    .eq("reminder_id", reminder.getId()).eq("scheduled_at", scheduled);
            MedicationIntake existing = medicationIntakeMapper.selectOne(q);
            if (existing == null) {
                MedicationIntake intake = new MedicationIntake();
                intake.setUserId(reminder.getUserId()); intake.setPatientId(reminder.getPatientId());
                intake.setReminderId(reminder.getId()); intake.setMedicationId(reminder.getMedicationId());
                intake.setScheduledAt(scheduled); intake.setStatus("PENDING"); intake.setDosage(reminder.getDosage());
                try { medicationIntakeMapper.insert(intake); }
                catch (org.springframework.dao.DuplicateKeyException ignored) { /* Another request generated it. */ }
            } else if (!"TAKEN".equals(existing.getStatus()) && !"SKIPPED".equals(existing.getStatus())) {
                String previousStatus = existing.getStatus();
                existing.setMedicationId(reminder.getMedicationId()); existing.setDosage(reminder.getDosage());
                if ("CANCELLED".equals(existing.getStatus())) { existing.setStatus("PENDING"); existing.setReason(""); }
                medicationIntakeMapper.update(null, new UpdateWrapper<MedicationIntake>()
                        .eq("id", existing.getId()).eq("status", previousStatus)
                        .set("medication_id", existing.getMedicationId()).set("dosage", existing.getDosage())
                        .set("status", existing.getStatus()).set("reason", existing.getReason()));
            }
        }
        QueryWrapper<MedicationIntake> q = new QueryWrapper<MedicationIntake>()
                .ge("scheduled_at", date.atStartOfDay()).lt("scheduled_at", date.plusDays(1).atStartOfDay())
                .in("status", "PENDING", "SNOOZED", "MISSED");
        if (patientId != null) q.eq("patient_id", patientId);
        for (MedicationIntake intake : medicationIntakeMapper.selectList(q)) {
            String previousStatus = intake.getStatus();
            MedicationReminder reminder = valid.get(intake.getReminderId());
            boolean unchanged = reminder != null && java.util.Objects.equals(intake.getPatientId(), reminder.getPatientId())
                    && java.util.Objects.equals(intake.getMedicationId(), reminder.getMedicationId())
                    && intake.getScheduledAt().toLocalTime().equals(LocalTime.parse(reminder.getRemindTime()));
            if (!unchanged) { intake.setStatus("CANCELLED"); intake.setReason("The reminder or medication was disabled, deleted, or changed"); }
            else intake.setDosage(reminder.getDosage());
            medicationIntakeMapper.update(null, new UpdateWrapper<MedicationIntake>()
                    .eq("id", intake.getId()).eq("status", previousStatus)
                    .set("status", intake.getStatus()).set("reason", intake.getReason()).set("dosage", intake.getDosage()));
        }
    }

    @Override
    public synchronized void checkAndTriggerReminders() {
        LocalDateTime now = LocalDateTime.now();
        ensureDailyTasks(null, now.toLocalDate());
        List<MedicationIntake> tasks = medicationIntakeMapper.selectList(new QueryWrapper<MedicationIntake>()
                .in("status", "PENDING", "SNOOZED", "MISSED").le("scheduled_at", now));
        for (MedicationIntake intake : tasks) {
            try {
                String previousStatus = intake.getStatus();
                MedicationReminder reminder = baseMapper.selectById(intake.getReminderId());
                if (reminder == null || !matches(reminder, intake.getScheduledAt().toLocalDate())) {
                    intake.setStatus("CANCELLED"); updateTaskStatus(intake, previousStatus); continue;
                }
                boolean snoozed = "SNOOZED".equals(intake.getStatus());
                LocalDateTime due = intake.getSnoozeUntil() != null ? intake.getSnoozeUntil() : intake.getScheduledAt();
                if (due.isAfter(now)) continue;
                boolean notify = snoozed || reminder.getLastTriggerAt() == null || reminder.getLastTriggerAt().isBefore(intake.getScheduledAt());
                if (notify) {
                    Medication med = medicationMapper.selectById(intake.getMedicationId());
                    if (!delivery.notifyUser(intake.getUserId(), "Medication Reminders", med.getDrugName() + " · "
                            + intake.getScheduledAt() + " · " + (intake.getDosage() == null ? "As prescribed" : intake.getDosage()))) {
                        if (!snoozed && due.isBefore(now.minusMinutes(60))) {
                            intake.setStatus("MISSED"); updateTaskStatus(intake, previousStatus);
                        }
                        continue;
                    }
                    reminder.setLastTriggerAt(now); baseMapper.updateById(reminder);
                }
                intake.setStatus(due.isBefore(now.minusMinutes(60)) ? "MISSED" : "PENDING");
                updateTaskStatus(intake, previousStatus);
            } catch (Exception e) { log.warn("Failed to process medication task, taskID={}", intake.getId(), e); }
        }
    }

    private void updateTaskStatus(MedicationIntake intake, String previousStatus) {
        medicationIntakeMapper.update(null, new UpdateWrapper<MedicationIntake>()
                .eq("id", intake.getId()).eq("status", previousStatus).set("status", intake.getStatus()));
    }
}
