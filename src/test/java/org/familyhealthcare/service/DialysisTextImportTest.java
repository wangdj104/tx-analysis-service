package org.familyhealthcare.service;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Locale;

class DialysisTextImportTest {
    @BeforeEach void useEnglishRequest(){LocaleContextHolder.setLocale(Locale.ENGLISH);}
    @AfterEach void clearRequestLocale(){LocaleContextHolder.resetLocaleContext();}

    @Test void chineseScreenshotTemplateWorksOnSharedBackend(){
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        DialysisTextImportService.Preview p=new DialysisTextImportService().parse("日期: 2026-09-21\n上次透后体重: 59.75\n透前体重: 61.77\n透后体重: 59.65\n超滤量:\n间隔天数:\n血压: 149/72\n备注:");
        assertTrue(p.getErrors().isEmpty(),p.getErrors().toString());
        assertTrue(p.getWarnings().isEmpty());
        assertEquals(1,p.getRows().size());
        DialysisRecord row=p.getRows().get(0);
        assertEquals(LocalDate.of(2026,9,21),row.getRecordDate());
        assertEquals(new BigDecimal("61.77"),row.getOnWeight());
        assertEquals(new BigDecimal("59.65"),row.getOffWeight());
        assertEquals(149,row.getSystolicBp());assertEquals(72,row.getDiastolicBp());
        assertEquals("NORMAL",row.getRecordType());
    }
    @Test void fieldLanguageIsIndependentOfResponseLanguage(){
        DialysisTextImportService service=new DialysisTextImportService();
        DialysisTextImportService.Preview english=service.parse("日期: 2026-09-21\n状态: 缺失\n缺失原因: 记录未保留");
        assertTrue(english.getErrors().isEmpty());
        assertTrue(english.getWarnings().get(0).contains("marked as incomplete"));
        assertEquals("记录未保留",english.getRows().get(0).getRemark());
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        DialysisTextImportService.Preview chinese=service.parse("Date: 2026-09-21\nPre-dialysis weight: invalid\nInterval days: 2.5");
        assertEquals(2,chinese.getErrors().size());
        assertTrue(chinese.getErrors().stream().allMatch(e->e.contains("数值格式无效")));
        assertTrue(chinese.getWarnings().get(0).contains("缺少上次透后体重"));
        assertFalse(chinese.getWarnings().get(0).contains("weight"));
    }
    @Test void acceptsFullWidthInputUnitsAliasesAndFlexibleDates(){
        DialysisTextImportService.Preview p=new DialysisTextImportService().parse("\uFEFF日期：２０２６年９月２１日；本次透前体重：６１.７７ 公斤；本次透后体重：５９.６５ KG\n血压：１４９／７２ 毫米汞柱\n间隔天数：３ 天\nNotes: 保留原文；无需翻译");
        assertTrue(p.getErrors().isEmpty(),p.getErrors().toString());
        assertEquals(new BigDecimal("61.77"),p.getRows().get(0).getOnWeight());
        assertEquals(3,p.getRows().get(0).getIntervalDays());
        assertEquals("保留原文；无需翻译",p.getRows().get(0).getRemark());
        assertTrue(new DialysisTextImportService().parse("date: 2026/9/21\npre-dialysis weight: 62").getErrors().isEmpty());
    }
    @Test void invalidDateDoesNotModifyPreviousRecordAndErrorsAreLocalized(){
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        DialysisTextImportService.Preview p=new DialysisTextImportService().parse("Date: 2026-09-21\nPre-dialysis weight: 61\n日期: 2026-02-30\n血压: 120/80");
        assertEquals(1,p.getRows().size());assertNull(p.getRows().get(0).getSystolicBp());
        assertTrue(p.getErrors().get(0).contains("请输入有效日期"));
        assertTrue(p.getErrors().get(1).contains("请先填写有效日期"));
        assertFalse(p.getErrors().toString().contains("Text '"));
    }
    @Test void detectsDuplicateFieldsAcrossLanguages(){
        DialysisTextImportService.Preview p=new DialysisTextImportService().parse("Date: 2026-09-21\nPre-dialysis weight: 61\n透前体重: 65");
        assertEquals(1,p.getErrors().size());
        assertTrue(p.getErrors().get(0).contains("Duplicate field"));
        assertEquals(new BigDecimal("61"),p.getRows().get(0).getOnWeight());
    }
    @Test void doesNotInventIntervalWithoutHistoricalDate(){
        DialysisTextImportService service=new DialysisTextImportService();DataScopeHelper scope=mock(DataScopeHelper.class);DialysisRecordMapper records=mock(DialysisRecordMapper.class);
        ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"records",records);
        when(scope.requirePatient(1L)).thenReturn(new Patient());when(records.selectList(any())).thenReturn(Collections.emptyList());
        DialysisTextImportService.Preview p=service.preview(1L,"日期: 2026-09-21\n透前体重: 61.77\n透后体重: 59.65");
        assertEquals(new BigDecimal("2.12"),p.getRows().get(0).getUfAmount());
        assertNull(p.getRows().get(0).getIntervalDays());
        assertFalse(p.getInferredFields().get("2026-09-21").contains("intervalDays"));
    }
    @Test void refusesNegativeInferredFluidRemoval(){
        DialysisTextImportService service=new DialysisTextImportService();DataScopeHelper scope=mock(DataScopeHelper.class);DialysisRecordMapper records=mock(DialysisRecordMapper.class);
        ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"records",records);
        when(scope.requirePatient(1L)).thenReturn(new Patient());when(records.selectList(any())).thenReturn(Collections.emptyList());
        DialysisTextImportService.Preview p=service.preview(1L,"Date: 2026-09-21\nPre-dialysis weight: 61\nPost-dialysis weight: 62");
        assertFalse(p.getErrors().isEmpty());assertNull(p.getRows().get(0).getUfAmount());
    }
    @Test void skippedPastedHistoryCannotSupplyMeasurementsToNextSession(){
        DialysisTextImportService service=new DialysisTextImportService();DataScopeHelper scope=mock(DataScopeHelper.class);DialysisRecordMapper records=mock(DialysisRecordMapper.class);
        ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"records",records);
        when(scope.requirePatient(1L)).thenReturn(new Patient());
        DialysisRecord previous=new DialysisRecord();previous.setRecordDate(LocalDate.of(2026,9,10));previous.setOffWeight(new BigDecimal("60"));
        when(records.selectList(any())).thenReturn(Collections.singletonList(previous));when(records.selectCount(any())).thenReturn(1L,0L);
        DialysisTextImportService.Preview p=service.preview(1L,"Date: 2026-09-14\nPost-dialysis weight: 999\nDate: 2026-09-21\nPre-dialysis weight: 61.77\nPost-dialysis weight: 59.65");
        assertEquals(Collections.singletonList("2026-09-14"),p.getExistingDates());
        assertEquals(new BigDecimal("60"),p.getRows().get(1).getLastOffWeight());
    }
    @Test void missingDayAndPartialValuesStayMissing(){
        DialysisTextImportService.Preview p=new DialysisTextImportService().parse("Date: 2026-09-14\nPre-dialysis weight: 65.2\nPost-dialysis weight: missing\nBlood pressure: 120/80\nDate: 2026-09-16\nStatus: Missing\nMissing reason: Not retained\nDate: 2026-09-20; Pre-dialysis weight: 66kg; Fluid removed: 2.1");
        assertTrue(p.getErrors().isEmpty());assertEquals(3,p.getRows().size());assertNull(p.getRows().get(0).getOffWeight());assertNull(p.getRows().get(0).getIntervalDays());assertNull(p.getRows().get(1).getOnWeight());assertEquals("INCOMPLETE",p.getRows().get(1).getRecordType());assertEquals(new BigDecimal("2.1"),p.getRows().get(2).getUfAmount());
    }
    @Test void refusesAmbiguousDatesAndUnrecognizedFields(){
        DialysisTextImportService service=new DialysisTextImportService();
        assertFalse(service.parse("Date: 2026-02-30\nPre-dialysis weight: 65").getErrors().isEmpty());
        assertFalse(service.parse("Date: 2026-09-14\nDate: 2026-09-14").getErrors().isEmpty());
        assertFalse(service.parse("Date: 2026-09-14\nWeight: 65").getErrors().isEmpty());
        assertFalse(service.parse("Date: 2026-09-14\nBlood pressure: 120").getErrors().isEmpty());
    }
    @Test void previewCalculatesDerivableFieldsAndDoesNotReportMissing(){
        DialysisTextImportService service=new DialysisTextImportService();DataScopeHelper scope=mock(DataScopeHelper.class);DialysisRecordMapper records=mock(DialysisRecordMapper.class);
        ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"records",records);
        when(scope.requirePatient(1L)).thenReturn(new Patient());
        DialysisRecord previous=new DialysisRecord();previous.setRecordDate(LocalDate.of(2026,9,10));previous.setOffWeight(new BigDecimal("60.58"));
        when(records.selectList(any())).thenReturn(Collections.singletonList(previous));when(records.selectCount(any())).thenReturn(0L);

        DialysisTextImportService.Preview p=service.preview(1L,"Date: 2026-09-14\nPrevious post-dialysis weight: 60.58\nPre-dialysis weight: 62.47\nPost-dialysis weight: 60.16\nFluid removed: \nInterval days: \nBlood pressure: 130/69\nNotes: ");

        DialysisRecord row=p.getRows().get(0);assertEquals(new BigDecimal("2.31"),row.getUfAmount());assertEquals(4,row.getIntervalDays());assertEquals("NORMAL",row.getRecordType());assertTrue(p.getWarnings().isEmpty());assertTrue(p.getInferredFields().get("2026-09-14").containsAll(java.util.Arrays.asList("ufAmount","intervalDays")));
    }
    @Test void confirmsOnlyNewDatesAndDoesNotOverwrite(){
        DialysisTextImportService service=new DialysisTextImportService();DataScopeHelper scope=mock(DataScopeHelper.class);DialysisRecordMapper records=mock(DialysisRecordMapper.class);PatientMapper patients=mock(PatientMapper.class);DialysisRecordService writer=mock(DialysisRecordService.class);
        ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"records",records);ReflectionTestUtils.setField(service,"patients",patients);ReflectionTestUtils.setField(service,"service",writer);
        when(scope.requirePatient(1L)).thenReturn(new Patient());when(records.selectCount(any())).thenReturn(1L,0L);when(writer.saveRecord(any())).thenReturn(true);
        assertEquals(1,service.commit(1L,"Date: 2026-09-14\nDate: 2026-09-16\nPre-dialysis weight: 65").get("added"));
        verify(writer).saveRecord(argThat(r->r.isTextImport()&&r.getRecordDate().toString().equals("2026-09-16")&&r.getOffWeight()==null));verify(records,never()).updateById(any());
    }
}
