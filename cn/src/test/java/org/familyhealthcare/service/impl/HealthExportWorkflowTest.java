package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HealthExportWorkflowTest {
    @BeforeEach void englishRequest() { LocaleContextHolder.setLocale(Locale.ENGLISH); }
    @AfterEach void resetLocale() { LocaleContextHolder.resetLocaleContext(); }

    @Test void allCsvHeadersFollowTheRequestLanguage() throws Exception {
        DataExportServiceImpl service = new DataExportServiceImpl();
        ReflectionTestUtils.setField(service,"dataScopeHelper",mock(DataScopeHelper.class));
        ReflectionTestUtils.setField(service,"dialysisRecordService",mock(DialysisRecordService.class));
        ReflectionTestUtils.setField(service,"bpPatternAnalysisService",mock(BpPatternAnalysisService.class));
        ReflectionTestUtils.setField(service,"nutritionDiaryService",mock(NutritionDiaryService.class));
        ReflectionTestUtils.setField(service,"complicationRecordService",mock(ComplicationRecordService.class));
        ReflectionTestUtils.setField(service,"medicationService",mock(MedicationService.class));
        ReflectionTestUtils.setField(service,"intakeMapper",mock(MedicationIntakeMapper.class));
        ReflectionTestUtils.setField(service,"bpSelfMonitorRecordService",mock(BpSelfMonitorRecordService.class));
        DataExportRequestVO request = new DataExportRequestVO(); request.setPatientId(2L);
        String[] types = {"dialysis", "bp_analysis", "nutrition", "complication", "medication", "bp_self_monitor"};
        String[] headers = {"透前体重", "分析日期", "液体摄入", "并发症类型", "实际服药时间", "测量时段"};
        for (int i=0; i<types.length; i++) {
            request.setDataType(types[i]); LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
            String chinese = new String(service.exportCsv(request), StandardCharsets.UTF_8);
            assertTrue(chinese.contains(headers[i]), types[i]);
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            assertFalse(new String(service.exportCsv(request),StandardCharsets.UTF_8).contains(headers[i]),types[i]);
        }
    }

    @Test void csvTranslatesSystemLabelsWithoutChangingPatientNotesOrQuotes() throws Exception {
        DataExportServiceImpl service=new DataExportServiceImpl();
        BpSelfMonitorRecordService records=mock(BpSelfMonitorRecordService.class);
        ReflectionTestUtils.setField(service,"dataScopeHelper",mock(DataScopeHelper.class));
        ReflectionTestUtils.setField(service,"bpSelfMonitorRecordService",records);
        BpSelfMonitorRecord row=measurement("2026-09-10");row.setMeasureType("BOTH");row.setMeasurePeriod("Fasting");row.setRemark("Original \"note\", unchanged");
        when(records.listByPatient(2L)).thenReturn(Collections.singletonList(row));
        DataExportRequestVO request=new DataExportRequestVO();request.setPatientId(2L);request.setDataType("bp_self_monitor");
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        String csv=new String(service.exportCsv(request),StandardCharsets.UTF_8);
        assertTrue(csv.contains("血压和血糖"));assertTrue(csv.contains("空腹"));assertTrue(csv.contains("Original \"\"note\"\", unchanged"));
    }

    @Test void reportsUseRequestedLanguageEscapePatientContentAndRenderPdf() throws Exception {
        HealthReportServiceImpl service=new HealthReportServiceImpl();
        PatientMapper patients=mock(PatientMapper.class);Patient patient=new Patient();patient.setName("<script>alert(1)</script>");
        when(patients.selectById(2L)).thenReturn(patient);
        ReflectionTestUtils.setField(service,"dataScopeHelper",mock(DataScopeHelper.class));ReflectionTestUtils.setField(service,"patientMapper",patients);
        ReflectionTestUtils.setField(service,"patientClinicalService",mock(PatientClinicalService.class));
        BpPatternAnalysisService bp=mock(BpPatternAnalysisService.class);NutritionDiaryService nutrition=mock(NutritionDiaryService.class);
        BpSelfMonitorRecordService measurements=mock(BpSelfMonitorRecordService.class);
        ReflectionTestUtils.setField(service,"bpPatternAnalysisService",bp);ReflectionTestUtils.setField(service,"nutritionDiaryService",nutrition);
        ReflectionTestUtils.setField(service,"bpSelfMonitorRecordService",measurements);
        BpPatternAnalysis analysis=new BpPatternAnalysis();analysis.setAnalysisDate(LocalDate.of(2026,9,10));analysis.setAnalysisSummary("Patient's original note");
        NutritionDiary diary=new NutritionDiary();diary.setRecordDate(LocalDate.of(2026,9,10));diary.setAppetite("POOR");diary.setMealBreakfast(true);
        when(bp.listByPatient(2L)).thenReturn(Collections.singletonList(analysis));when(nutrition.listByPatient(2L)).thenReturn(Collections.singletonList(diary));
        BpSelfMonitorRecord row=measurement("2026-09-10");row.setBloodGlucose(new java.math.BigDecimal("90"));row.setBgUnit("mg/dL");row.setMeasurePeriod("Fasting");
        when(measurements.listByPatient(2L)).thenReturn(Collections.singletonList(row));
        HealthReportRequestVO request=new HealthReportRequestVO();request.setPatientId(2L);request.setReportType("summary_no_dialysis");
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        String chinese=new String(service.generateReport(request,"html"),StandardCharsets.UTF_8);
        assertTrue(chinese.contains("lang='zh-CN'"));assertTrue(chinese.contains("健康数据综合报告"));assertTrue(chinese.contains("营养日记"));assertTrue(chinese.contains("较差"));assertTrue(chinese.contains("早餐"));assertTrue(chinese.contains("空腹"));
        assertTrue(chinese.contains("&lt;script&gt;"));assertFalse(chinese.contains("<script>"));assertTrue(chinese.contains("Patient&#39;s original note"));
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        String english=new String(service.generateReport(request,"html"),StandardCharsets.UTF_8);
        assertTrue(english.contains("lang='en'"));assertTrue(english.contains("Comprehensive Health Report"));assertTrue(english.contains("Poor"));assertTrue(english.contains("Breakfast"));assertFalse(english.contains("健康数据综合报告"));
        byte[] pdf=service.generateReport(request,"pdf");assertTrue(new String(pdf,0,4,StandardCharsets.US_ASCII).equals("%PDF"));
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        byte[] chinesePdf=service.generateReport(request,"pdf");
        assertEquals("%PDF",new String(chinesePdf,0,4,StandardCharsets.US_ASCII));
    }

    @Test void glucoseBadgesRespectUnitsAndBothPeriodLanguages() {
        HealthReportServiceImpl service=new HealthReportServiceImpl();
        String mmol=ReflectionTestUtils.invokeMethod(service,"badgeForBgValue",new java.math.BigDecimal("5"),"Fasting","mmol/L");
        String mg=ReflectionTestUtils.invokeMethod(service,"badgeForBgValue",new java.math.BigDecimal("90"),"空腹","mg/dL");
        assertEquals(mmol,mg);assertTrue(mg.contains("Normal"));
        assertEquals("",ReflectionTestUtils.invokeMethod(service,"badgeForBgValue",new java.math.BigDecimal("5"),"空腹","unknown"));
        assertEquals(false,ReflectionTestUtils.invokeMethod(service,"validChartImage","data:image/png;' onerror='alert(1)"));
    }
    @Test void reportExcludesMeasurementsOutsideSelectedMonth() throws Exception {
        HealthReportServiceImpl service=new HealthReportServiceImpl();
        BpSelfMonitorRecordService measurements=mock(BpSelfMonitorRecordService.class);
        ReflectionTestUtils.setField(service,"dataScopeHelper",mock(DataScopeHelper.class));
        ReflectionTestUtils.setField(service,"patientMapper",mock(PatientMapper.class));
        ReflectionTestUtils.setField(service,"patientClinicalService",mock(PatientClinicalService.class));
        ReflectionTestUtils.setField(service,"bpSelfMonitorRecordService",measurements);
        BpSelfMonitorRecord included=measurement("2026-09-10"),excluded=measurement("2026-08-10");
        when(measurements.listByPatient(2L)).thenReturn(Arrays.asList(included,excluded));
        HealthReportRequestVO request=new HealthReportRequestVO();request.setPatientId(2L);request.setReportType("bp_monitor");request.setTimeType("month");request.setTimeValue("2026-09");
        String report=new String(service.generateReport(request,"html"),StandardCharsets.UTF_8);
        assertTrue(report.contains("2026-09-10"));assertFalse(report.contains("2026-08-10"));
    }

    @Test void csvFiltersMeasurementsAndExportsActualMedicationHistory() throws Exception {
        DataExportServiceImpl service=new DataExportServiceImpl();
        ReflectionTestUtils.setField(service,"dataScopeHelper",mock(DataScopeHelper.class));
        BpSelfMonitorRecordService measurements=mock(BpSelfMonitorRecordService.class);
        MedicationService medication=mock(MedicationService.class);MedicationIntakeMapper intakes=mock(MedicationIntakeMapper.class);
        ReflectionTestUtils.setField(service,"bpSelfMonitorRecordService",measurements);ReflectionTestUtils.setField(service,"medicationService",medication);ReflectionTestUtils.setField(service,"intakeMapper",intakes);
        when(measurements.listByPatient(2L)).thenReturn(Arrays.asList(measurement("2026-09-10"),measurement("2026-08-10")));
        DataExportRequestVO request=new DataExportRequestVO();request.setPatientId(2L);request.setTimeType("month");request.setTimeValue("2026-09");request.setDataType("bp_self_monitor");
        String csv=new String(service.exportCsv(request),StandardCharsets.UTF_8);assertTrue(csv.contains("2026-09-10"));assertFalse(csv.contains("2026-08-10"));
        Medication med=new Medication();med.setDrugName("Test medication, with comma");when(medication.getById(3L)).thenReturn(med);
        MedicationLog log=new MedicationLog();log.setMedication(med);log.setAdministrationTime(LocalDateTime.of(2026,9,10,8,0));log.setDosage("1 tablet");
        when(medication.listLogs(2L,null)).thenReturn(Collections.singletonList(log));
        MedicationIntake intake=new MedicationIntake();intake.setMedicationId(3L);intake.setScheduledAt(LocalDateTime.of(2026,9,10,9,0));intake.setActionAt(LocalDateTime.of(2026,9,10,9,5));intake.setStatus("TAKEN");
        when(intakes.selectList(any())).thenReturn(Collections.singletonList(intake));request.setDataType("medication");
        csv=new String(service.exportCsv(request),StandardCharsets.UTF_8);
        assertTrue(csv.contains("Actual intake time"));assertTrue(csv.contains("2026-09-10T09:05"));assertTrue(csv.contains("Manual record"));assertTrue(csv.contains("Reminder check-in"));assertTrue(csv.contains("\"Test medication, with comma\""));
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        csv=new String(service.exportCsv(request),StandardCharsets.UTF_8);
        assertTrue(csv.contains("实际服药时间"));assertTrue(csv.contains("提醒打卡"));assertTrue(csv.contains("已服药"));assertFalse(csv.contains("TAKEN"));
    }

    private BpSelfMonitorRecord measurement(String date) {BpSelfMonitorRecord r=new BpSelfMonitorRecord();r.setRecordDate(LocalDate.parse(date));r.setSystolicBp(120);r.setDiastolicBp(80);return r;}
}
