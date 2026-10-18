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

    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard tracking;
    @Autowired private org.familyhealthcare.service.medicationexecution.MedicationTrackedReminderAdapter tracked;

    private static final Logger log = LoggerFactory.getLogger(MedicationReminderServiceImpl.class);

    @Autowired
    private org.familyhealthcare.service.UserLanguagePreferenceService languagePreference;

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
        boolean modern=tracking.storageAvailable();
        for (MedicationReminder reminder : list) {
            if(modern)tracking.decorateReminder(reminder);
            if (reminder.getMedicationId() != null) {
                Medication med = medicationMapper.selectById(reminder.getMedicationId());
                reminder.setMedication(med);
            }
        }
        return list;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean saveOrUpdateReminder(MedicationReminder record) {
        Long userId = dataScopeHelper.requireUserId();
        if (record.getPatientId() == null) {
            throw new IllegalStateException("Select a patient");
        }
        dataScopeHelper.requirePatient(record.getPatientId());
        tracking.requireLegacyReminderWriteAllowed(userId,record.getPatientId(),record.getMedicationId(),record.getId());
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
            boolean changed=updateById(record);if(changed)tracking.reminderInputsChanged(userId,record.getPatientId(),record.getMedicationId());return changed;
        } else {
            boolean changed=save(record);if(changed)tracking.reminderInputsChanged(userId,record.getPatientId(),record.getMedicationId());return changed;
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean toggleEnabled(Long id) {
        MedicationReminder existing = getById(id);
        if (existing == null) {
            return false;
        }
        tracking.requireLegacyReminderWriteAllowed(dataScopeHelper.requireUserId(),existing.getPatientId(),existing.getMedicationId(),id);
        existing=getById(id);dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        existing.setEnabled(existing.getEnabled() != null && existing.getEnabled() == 1 ? 0 : 1);
        boolean changed=updateById(existing);if(changed)tracking.reminderInputsChanged(dataScopeHelper.requireUserId(),existing.getPatientId(),existing.getMedicationId());return changed;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean deleteOwned(Long id) {
        MedicationReminder existing = getById(id);
        if (existing == null) {
            return false;
        }
        dataScopeHelper.requirePatientOrOwner(existing.getPatientId(), existing.getUserId());
        tracking.requireLegacyReminderWriteAllowed(dataScopeHelper.requireUserId(),existing.getPatientId(),existing.getMedicationId(),id);
        boolean changed=removeById(id);if(changed)tracking.reminderInputsChanged(dataScopeHelper.requireUserId(),existing.getPatientId(),existing.getMedicationId());return changed;
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
        if(patientId!=null)tracked.ensureDailyTasks(patientId,date);
        boolean modern=tracking.storageAvailable();
        QueryWrapper<MedicationReminder> query = new QueryWrapper<>();
        if(modern)query.eq("workflow_version",0).isNull("execution_id");
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
                updateLegacyIntake(existing, previousStatus, new UpdateWrapper<MedicationIntake>()
                        .set("medication_id", existing.getMedicationId()).set("dosage", existing.getDosage())
                        .set("status", existing.getStatus()).set("reason", existing.getReason()));
            }
        }
        QueryWrapper<MedicationIntake> q = new QueryWrapper<MedicationIntake>()
                .ge("scheduled_at", date.atStartOfDay()).lt("scheduled_at", date.plusDays(1).atStartOfDay())
                .in("status", "PENDING", "SNOOZED", "MISSED");
        if(modern)q.eq("workflow_version",0).isNull("execution_id");
        if (patientId != null) q.eq("patient_id", patientId);
        for (MedicationIntake intake : medicationIntakeMapper.selectList(q)) {
            String previousStatus = intake.getStatus();
            MedicationReminder reminder = valid.get(intake.getReminderId());
            boolean unchanged = reminder != null && java.util.Objects.equals(intake.getPatientId(), reminder.getPatientId())
                    && java.util.Objects.equals(intake.getMedicationId(), reminder.getMedicationId())
                    && intake.getScheduledAt().toLocalTime().equals(LocalTime.parse(reminder.getRemindTime()));
            if (!unchanged) { intake.setStatus("CANCELLED"); intake.setReason("The reminder or medication was disabled, deleted, or changed"); }
            else intake.setDosage(reminder.getDosage());
            updateLegacyIntake(intake, previousStatus, new UpdateWrapper<MedicationIntake>()
                    .set("status", intake.getStatus()).set("reason", intake.getReason()).set("dosage", intake.getDosage()));
        }
    }

    @Override
    public synchronized void checkAndTriggerReminders() {
        java.time.Instant utcNow=java.time.Instant.now();tracked.materializeDue(utcNow);tracked.processDueIntakes(utcNow);
        LocalDateTime now = LocalDateTime.now();
        ensureDailyTasks(null, now.toLocalDate());
        QueryWrapper<MedicationIntake> dueQuery=new QueryWrapper<MedicationIntake>().in("status", "PENDING", "SNOOZED", "MISSED").le("scheduled_at", now);
        if(tracking.storageAvailable())dueQuery.eq("workflow_version",0).isNull("execution_id");
        List<MedicationIntake> tasks = medicationIntakeMapper.selectList(dueQuery);
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
                    if (!tracking.admitLegacyIntakeNotification(intake,previousStatus)) continue;
                    if (!delivery.notifyUser(intake.getUserId(), "MEDICATION_REMINDER", "Medication Reminders", med.getDrugName() + " · "
                            + intake.getScheduledAt() + " · " + reminderDose(intake))) {
                        if (!snoozed && due.isBefore(now.minusMinutes(60))) {
                            intake.setStatus("MISSED"); updateTaskStatus(intake, previousStatus);
                        }
                        continue;
                    }
                    tracking.mutateLegacyIntake(intake,previousStatus,()->{UpdateWrapper<MedicationReminder> marker=new UpdateWrapper<MedicationReminder>().eq("id",reminder.getId()).eq("patient_id",intake.getPatientId()).eq("medication_id",intake.getMedicationId()).set("last_trigger_at",now);if(tracking.storageAvailable())marker.eq("workflow_version",0).isNull("execution_id");baseMapper.update(null,marker);});
                }
                intake.setStatus(due.isBefore(now.minusMinutes(60)) ? "MISSED" : "PENDING");
                updateTaskStatus(intake, previousStatus);
            } catch (Exception e) { log.warn("Failed to process medication task, taskID={}", intake.getId(), e); }
        }
    }

    private void updateTaskStatus(MedicationIntake intake, String previousStatus) {
        updateLegacyIntake(intake,previousStatus,new UpdateWrapper<MedicationIntake>().set("status",intake.getStatus()));
    }
    private void updateLegacyIntake(MedicationIntake intake,String previousStatus,UpdateWrapper<MedicationIntake> update) {
        update.eq("id",intake.getId()).eq("patient_id",intake.getPatientId()).eq("medication_id",intake.getMedicationId()).eq("user_id",intake.getUserId()).eq("status",previousStatus);
        if(tracking.storageAvailable())update.eq("workflow_version",0).isNull("execution_id").isNull("reconciliation_id");
        tracking.mutateLegacyIntake(intake,previousStatus,()->medicationIntakeMapper.update(null,update));
    }
    /** A stored dosage is clinical source text, even when it matches the old fallback exactly. */
    private String reminderDose(MedicationIntake intake) {
        if (intake.getDosage() != null) return intake.getDosage();
        String language = languagePreference == null ? "zh-CN" : languagePreference.get(intake.getUserId());
        return language != null && language.startsWith("zh") ? "遵医嘱" : "As prescribed";
    }

}
