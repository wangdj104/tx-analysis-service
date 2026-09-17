package org.familyhealthcare.service;

import org.familyhealthcare.entity.MedicationReminder;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * Medication RemindersplanService
 */
public interface MedicationReminderService extends IService<MedicationReminder> {

    /**
     * queryPatient Reminderlist
     */
    List<MedicationReminder> listByPatient(Long patientId);

    /**
     * Saveor updateReminder
     */
    boolean saveOrUpdateReminder(MedicationReminder record);

    /**
     * switchEnabled/DisabledStatus
     */
    boolean toggleEnabled(Long id);

    /**
     * DeleteReminder (containownershipvalidate)
     */
    boolean deleteOwned(Long id);

    /**
     * Examinationandtriggerto periodReminder
     */
    void checkAndTriggerReminders();
    void ensureDailyTasks(Long patientId, java.time.LocalDate date);
}
