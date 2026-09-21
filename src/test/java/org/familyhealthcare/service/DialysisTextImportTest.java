package org.familyhealthcare.service;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

class DialysisTextImportTest {
    @Test void acceptsChineseTemplateAndUnits(){
        DialysisTextImportService.Preview p=new DialysisTextImportService().parse("日期：2026-09-21\n上次透后体重：60.5 千克\n透前体重：62.3 公斤\n透后体重：60.2 kg\n超滤量：2.1 公斤\n间隔天数：2 天\n血压：128/76 毫米汞柱\n状态：正常");
        assertTrue(p.getErrors().isEmpty());
        assertEquals(1,p.getRows().size());
        DialysisRecord row=p.getRows().get(0);
        assertEquals(LocalDate.of(2026,9,21),row.getRecordDate());
        assertEquals(new BigDecimal("62.3"),row.getOnWeight());
        assertEquals(new BigDecimal("60.2"),row.getOffWeight());
        assertEquals(new BigDecimal("2.1"),row.getUfAmount());
        assertEquals(2,row.getIntervalDays());
        assertEquals(128,row.getSystolicBp());
        assertEquals(76,row.getDiastolicBp());
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
