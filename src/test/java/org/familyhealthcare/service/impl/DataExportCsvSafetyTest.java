package org.familyhealthcare.service.impl;

import org.familyhealthcare.entity.NutritionDiary;
import org.familyhealthcare.service.NutritionDiaryService;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.vo.DataExportRequestVO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataExportCsvSafetyTest {
    @AfterEach void cleanup() { LocaleContextHolder.resetLocaleContext(); }
    static Stream<String> dangerous() { return Stream.of("=SUM(1,2)", "+CMD()", "-5.5", "@SUM(1,2)", " \u00a0\u2003=1+1", "\u200b=1+1", "\tordinary", "\r\n=1+1", "\u0001", "\uFEFFplain"); }
    @ParameterizedTest @MethodSource("dangerous")
    void dangerousTextIsApostrophePrefixedWithoutMutatingStoredNotes(String note) throws Exception {
        NutritionDiary row = row(note);
        String csv = export(row,Locale.ENGLISH);
        assertTrue(csv.contains("\"'"+note.replace("\"","\"\"")+"\""),"unsafe note: "+note);
        assertEquals(note,row.getRemark());
    }

    @Test void actualNegativeNumbersRemainNumericWhileNegativeTextIsProtected() throws Exception {
        NutritionDiary row = row("-5.5"); row.setBodyWeight(new BigDecimal("-5.5")); row.setFluidIntake(-25);
        String csv=export(row,Locale.ENGLISH);
        assertTrue(csv.contains("\"2026-09-10\",-5.5,")); assertTrue(csv.contains(",-25,")); assertTrue(csv.contains("\"'-5.5\""));
        assertEquals(new BigDecimal("-5.5"),row.getBodyWeight());
    }

    @Test void ordinaryQuotedMultilineTextAndNullCellsKeepCsvFormattingAndBothHeaders() throws Exception {
        NutritionDiary row = row("Original \"note\", unchanged\n第二行"); row.setSymptoms(null);
        String english=export(row,Locale.ENGLISH); String chinese=export(row,Locale.SIMPLIFIED_CHINESE);
        assertTrue(english.startsWith("\uFEFFDate,Weight (kg)")); assertTrue(chinese.startsWith("\uFEFF日期,体重（kg）"));
        assertTrue(english.contains("\"Original \"\"note\"\", unchanged\n第二行\"")); assertTrue(english.contains(",\"\",\"Original"));
        assertTrue(chinese.contains("\"Original \"\"note\"\", unchanged\n第二行\"")); assertEquals("Original \"note\", unchanged\n第二行",row.getRemark());
    }

    private NutritionDiary row(String note) { NutritionDiary row=new NutritionDiary();row.setRecordDate(LocalDate.of(2026,9,10));row.setRemark(note);return row; }
    private String export(NutritionDiary row,Locale locale) throws Exception {
        DataExportServiceImpl service=new DataExportServiceImpl(); NutritionDiaryService records=mock(NutritionDiaryService.class);
        ReflectionTestUtils.setField(service,"dataScopeHelper",mock(DataScopeHelper.class));ReflectionTestUtils.setField(service,"nutritionDiaryService",records);
        when(records.listByPatient(1L)).thenReturn(Collections.singletonList(row));
        DataExportRequestVO request=new DataExportRequestVO();request.setPatientId(1L);request.setDataType("nutrition");LocaleContextHolder.setLocale(locale);
        return new String(service.exportCsv(request),StandardCharsets.UTF_8);
    }
}
