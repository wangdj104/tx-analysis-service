package org.familyhealthcare.task;

import org.familyhealthcare.service.MedicationReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Medication ReminderssettimeExaminationtask
 */
@Component
public class ReminderCheckTask {

    private static final Logger log = LoggerFactory.getLogger(ReminderCheckTask.class);

    @Autowired
    private MedicationReminderService medicationReminderService;

    /**
     * Check for due reminders every minute
     */
    @Scheduled(cron = "0 * * * * ?")
    public void checkReminders() {
        try {
            medicationReminderService.checkAndTriggerReminders();
        } catch (Exception e) {
            log.error("Medication reminder check failed: {}", e.getMessage());
        }
    }
}
