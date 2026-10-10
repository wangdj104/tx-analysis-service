package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class GeneratedLanguageWorkflowTest {
    @AfterEach void resetLocale() { LocaleContextHolder.resetLocaleContext(); }

    @Test void newBloodPressureSummaryUsesRequestLanguageWithoutChangingExistingSummaries() {
        BpPatternAnalysisServiceImpl service = new BpPatternAnalysisServiceImpl();
        DialysisRecordService dialysis = mock(DialysisRecordService.class);
        ReflectionTestUtils.setField(service, "dialysisRecordService", dialysis);
        ReflectionTestUtils.setField(service, "dataScopeHelper", mock(DataScopeHelper.class));
        BpPatternAnalysisMapper mapper = mock(BpPatternAnalysisMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        DialysisRecord high = new DialysisRecord(); high.setSystolicBp(160); high.setDiastolicBp(100);
        DialysisRecord low = new DialysisRecord(); low.setSystolicBp(80); low.setDiastolicBp(60);
        when(dialysis.listByFilter("month", "2026-09", 1L)).thenReturn(Arrays.asList(high, low));
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        String chinese = service.analyze(1L, "month", "2026-09").getAnalysisSummary();
        assertTrue(chinese.contains("平均收缩压 120.0 mmHg"), chinese);
        assertTrue(chinese.contains("变化范围 80-160 mmHg"), chinese);
        assertFalse(chinese.contains("Average systolic"));
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        String english = service.analyze(1L, "month", "2026-09").getAnalysisSummary();
        assertTrue(english.contains("Average systolic pressure 120.0 mmHg"), english);
        assertTrue(english.contains("Range 80-160 mmHg"), english);
        assertFalse(english.contains("variationrange"));
        assertFalse(english.contains("Pressurevariability"));
    }

    @Test void complicationContextLocalizesOnlyGeneratedLabels() {
        ComplicationRecordServiceImpl service = new ComplicationRecordServiceImpl();
        ComplicationRecordMapper mapper = mock(ComplicationRecordMapper.class);
        DialysisRecordMapper dialysis = mock(DialysisRecordMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "dialysisRecordMapper", dialysis);
        ReflectionTestUtils.setField(service, "dataScopeHelper", mock(DataScopeHelper.class));
        ComplicationRecord record = new ComplicationRecord(); record.setRelatedDialysisId(5L);
        record.setDescription("Original clinical text 原始临床记录");
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(record));
        DialysisRecord source = new DialysisRecord(); source.setId(5L); source.setWeightGain(new BigDecimal("2.5")); source.setSystolicBp(120); source.setDiastolicBp(80);
        when(dialysis.selectBatchIds(any())).thenReturn(Collections.singletonList(source));
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        Map<String,Object> chinese = service.getStats(1L).getRecentList().get(0);
        assertEquals("增重 2.5 kg | 血压 120/80", chinese.get("dialysisSummary"));
        assertEquals(record.getDescription(), chinese.get("description"));
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("Weight gain 2.5 kg | Blood pressure 120/80", service.getStats(1L).getRecentList().get(0).get("dialysisSummary"));
    }

    @Test void importPreviewLocalizesWarningsAndPreservesSourceLabels() {
        ClinicalImportService service = new ClinicalImportService();
        ReflectionTestUtils.setField(service, "scope", mock(DataScopeHelper.class));
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        Map<String,Object> invalid = new HashMap<>(); invalid.put("bundle", Collections.singletonMap("resourceType", "Patient"));
        Map<String,Object> result = service.preview(1L, invalid);
        assertEquals(Collections.singletonList("FHIR 数据不是 Bundle 资源。"), result.get("warnings"));
        Map<String,Object> observation = new HashMap<>(); observation.put("resourceType", "Observation"); observation.put("id", "source-id-1");
        observation.put("effectiveDateTime", "2026-09-21"); observation.put("code", Collections.singletonMap("text", "Saved successfully"));
        observation.put("valueString", "Patient said As prescribed 原文");
        Map<String,Object> bundle = new HashMap<>(); bundle.put("resourceType", "Bundle"); bundle.put("entry", Collections.singletonList(Collections.singletonMap("resource", observation)));
        result = service.preview(1L, Collections.singletonMap("bundle", bundle));
        Map<?,?> item = (Map<?,?>)((List<?>)result.get("items")).get(0);
        assertEquals("Saved successfully", item.get("display")); assertEquals("Patient said As prescribed 原文", item.get("value"));
        assertEquals("source-id-1", item.get("externalId"));
    }
    @Test void dashboardGeneratedLabelsUseTheRequestLanguage() {
        DashboardServiceImpl service = new DashboardServiceImpl();
        DialysisRecordService dialysis = mock(DialysisRecordService.class);
        ReflectionTestUtils.setField(service, "dialysisRecordService", dialysis);
        ReflectionTestUtils.setField(service, "dataScopeHelper", mock(DataScopeHelper.class));
        when(dialysis.list(any())).thenReturn(Collections.emptyList());
        org.familyhealthcare.vo.DashboardSummaryVO vo = new org.familyhealthcare.vo.DashboardSummaryVO();
        vo.setAbnormalItemCount(0L);
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        ReflectionTestUtils.invokeMethod(service, "fillHealthInsights", vo, Collections.emptyList(), Collections.emptyMap(), 1L, "2026-09", 1L);
        assertEquals("暂无透析记录", vo.getLatestDialysisStatus());
        assertEquals("整体平稳", vo.getHealthRiskLabel());
        assertEquals("超滤达标", ReflectionTestUtils.invokeMethod(service, "dehydrationText", "MATCH"));
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        ReflectionTestUtils.invokeMethod(service, "fillHealthInsights", vo, Collections.emptyList(), Collections.emptyMap(), 1L, "2026-09", 1L);
        assertEquals("No dialysis records", vo.getLatestDialysisStatus());
        assertEquals("Stable overall", vo.getHealthRiskLabel());
        assertEquals("Ultrafiltration on target", ReflectionTestUtils.invokeMethod(service, "dehydrationText", "MATCH"));
    }

    @Test void medicalRecordFallbackLabelsDoNotRewriteActualFacilityNames() {
        ClinicalWorkbenchService service = new ClinicalWorkbenchService();
        MedicalRecord record = new MedicalRecord(); record.setHospitalName("Date missing");
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("日期缺失 · Date missing", ReflectionTestUtils.invokeMethod(service, "recordLabel", record));
        record.setHospitalName(null);
        assertEquals("日期缺失 · 医疗机构缺失", ReflectionTestUtils.invokeMethod(service, "recordLabel", record));
    }

    @Test void monitoringFallbacksDoNotTranslateMedicationNamesOrDosages() {
        MonitoringServiceImpl service = new MonitoringServiceImpl();
        MedicationMapper medications = mock(MedicationMapper.class);
        ReflectionTestUtils.setField(service, "medicationMapper", medications);
        Medication medication = new Medication(); medication.setId(3L); medication.setDrugName("by timemedication intake");
        when(medications.selectBatchIds(any())).thenReturn(Collections.singletonList(medication));
        MedicationIntake intake = new MedicationIntake(); intake.setId(8L); intake.setMedicationId(3L); intake.setDosage("As prescribed");
        intake.setScheduledAt(java.time.LocalDateTime.of(2026,9,24,8,0));
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        java.util.List<org.familyhealthcare.vo.MonitoringSnapshotVO.TaskItem> tasks = ReflectionTestUtils.invokeMethod(service, "buildTasks", Collections.singletonList(intake), Collections.emptyList());
        assertEquals("by timemedication intake", tasks.get(0).getTitle()); assertEquals("As prescribed", tasks.get(0).getDosage());
        when(medications.selectBatchIds(any())).thenReturn(Collections.emptyList());
        tasks = ReflectionTestUtils.invokeMethod(service, "buildTasks", Collections.singletonList(intake), Collections.emptyList());
        assertEquals("按时服药", tasks.get(0).getTitle());
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026,9,24,8,0);
        assertEquals("刚刚更新", ReflectionTestUtils.invokeMethod(service, "freshness", now, now));
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("Updated just now", ReflectionTestUtils.invokeMethod(service, "freshness", now, now));
    }

}
