package org.familyhealthcare.service;

import org.familyhealthcare.entity.HealthAnalysisAutomation;
import org.familyhealthcare.util.CurrentUserUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class HealthAnalysisAutomationServiceTest {
    private final HealthAnalysisAutomationService service = new HealthAnalysisAutomationService();

    @Test void calculatesDailyWeeklyAndMonthlyNextRuns() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 10, 0);
        HealthAnalysisAutomation row = base();
        row.setFrequencyType("DAILY"); row.setIntervalDays(2); row.setRunTime("08:00");
        assertEquals(LocalDateTime.of(2026, 9, 18, 8, 0), service.calculateNextRun(row, now));
        row.setFrequencyType("WEEKLY"); row.setDayOfWeek(4);
        assertEquals(LocalDateTime.of(2026, 9, 17, 8, 0), service.calculateNextRun(row, now));
        row.setFrequencyType("MONTHLY"); row.setDayOfMonth(15);
        assertEquals(LocalDateTime.of(2026, 10, 15, 8, 0), service.calculateNextRun(row, now));
    }

    @Test void backgroundUserContextIsClearedAfterExecution() {
        assertNull(CurrentUserUtil.getCurrentUserId());
        assertEquals(9L, CurrentUserUtil.runAsUser(9L, CurrentUserUtil::getCurrentUserId));
        assertNull(CurrentUserUtil.getCurrentUserId());
    }

    private HealthAnalysisAutomation base() {
        HealthAnalysisAutomation row = new HealthAnalysisAutomation();
        row.setRunTime("08:00"); row.setIntervalDays(1); row.setDayOfWeek(1); row.setDayOfMonth(1);
        return row;
    }
}
