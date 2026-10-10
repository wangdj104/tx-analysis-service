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


    @Test void scheduledGenerationUsesEachOwnersLanguageAndRestoresSchedulerContext() {
        org.familyhealthcare.mapper.HealthAnalysisAutomationMapper mapper = org.mockito.Mockito.mock(org.familyhealthcare.mapper.HealthAnalysisAutomationMapper.class);
        org.familyhealthcare.mapper.AiAnalysisRecordMapper records = org.mockito.Mockito.mock(org.familyhealthcare.mapper.AiAnalysisRecordMapper.class);
        AiAnalysisService analysis = org.mockito.Mockito.mock(AiAnalysisService.class);
        org.familyhealthcare.util.DataScopeHelper scope = org.mockito.Mockito.mock(org.familyhealthcare.util.DataScopeHelper.class);
        NotificationDeliveryService delivery = org.mockito.Mockito.mock(NotificationDeliveryService.class);
        UserLanguagePreferenceService languages = org.mockito.Mockito.mock(UserLanguagePreferenceService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mapper", mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analysisRecords", records);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aiAnalysis", analysis);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scope", scope);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "delivery", delivery);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "languagePreference", languages);
        HealthAnalysisAutomation chinese = base(), english = base();
        chinese.setId(1L); chinese.setUserId(9L); chinese.setPatientId(20L);
        english.setId(2L); english.setUserId(10L); english.setPatientId(21L);
        for (HealthAnalysisAutomation row : new HealthAnalysisAutomation[]{chinese, english}) {
            row.setFrequencyType("DAILY"); row.setAnalysisRangeDays(30); row.setAnalysisItems("VITALS"); row.setTaskName("Original name 原文");
        }
        org.mockito.Mockito.when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Arrays.asList(chinese, english));
        org.mockito.Mockito.when(languages.get(9L)).thenReturn("zh-CN");
        org.mockito.Mockito.when(languages.get(10L)).thenReturn("en-US");
        java.util.List<String> observed = new java.util.ArrayList<>();
        org.mockito.Mockito.when(analysis.analyzeHealthSnapshot(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(call -> {
            observed.add(org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage());
            return "Original clinical text Normal 原文";
        });
        java.util.List<org.familyhealthcare.entity.AiAnalysisRecord> saved = new java.util.ArrayList<>();
        org.mockito.Mockito.when(records.insert(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> { saved.add(call.getArgument(0)); return 1; });
        org.springframework.context.i18n.LocaleContext before = new org.springframework.context.i18n.SimpleLocaleContext(java.util.Locale.JAPAN);
        org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(before);
        try {
            service.runDueTasks();
            assertEquals(java.util.Arrays.asList("zh", "en"), observed);
            assertSame(before, org.springframework.context.i18n.LocaleContextHolder.getLocaleContext());
            assertEquals("最近 30 天", saved.get(0).getPeriodLabel());
            assertEquals("Last 30 days", saved.get(1).getPeriodLabel());
            assertEquals("Original clinical text Normal 原文", saved.get(0).getAnalysisContent());
            assertTrue(saved.get(0).getRemark().endsWith("Original name 原文"));
            org.mockito.Mockito.when(analysis.analyzeHealthSnapshot(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyCollection())).thenThrow(new IllegalStateException("Synthetic failure"));
            service.runDueTasks();
            assertSame(before, org.springframework.context.i18n.LocaleContextHolder.getLocaleContext());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

    private HealthAnalysisAutomation base() {
        HealthAnalysisAutomation row = new HealthAnalysisAutomation();
        row.setRunTime("08:00"); row.setIntervalDays(1); row.setDayOfWeek(1); row.setDayOfMonth(1);
        return row;
    }
}
