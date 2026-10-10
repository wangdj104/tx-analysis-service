package org.familyhealthcare.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SystemMessageLocalizationTest {
    @AfterEach void resetLocale() { LocaleContextHolder.resetLocaleContext(); }

    @Test void knownSystemMessagesUseReadableEnglishAndChinese() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/localization/system-messages.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split("\t", -1);
                LocaleContextHolder.setLocale(Locale.ENGLISH);
                assertEquals(cells[1], MessageLocalizer.localize(cells[0]), cells[0]);
                LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
                assertEquals(cells[2], MessageLocalizer.localize(cells[0]), cells[0]);
            }
        }
    }

    @Test void generatedValidationTemplatesPreserveParameters() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("PHQ9 需要填写 9 题得分。", MessageLocalizer.localize("PHQ9 requires 9 item scores."));
        assertEquals("PHQ9 各题得分必须在 0 到 3 之间。", MessageLocalizer.localize("PHQ9 item scores must be between 0 and 3."));
        assertEquals("输入内容过长，最多允许 500 个字符。", MessageLocalizer.localize("The entered text is too long (maximum 500 characters)."));
        assertEquals("血压指标的单位必须为 mmHg。", MessageLocalizer.localize("Measurement unit must be mmHg for BP."));
        assertEquals("消息内容不能为空。", MessageLocalizer.localize("content is required."));
        assertEquals("消息内容不能超过 2000 个字符。", MessageLocalizer.localize("content exceeds 2000 characters."));
        assertEquals("保存失败：问诊记录不存在。", MessageLocalizer.localize("Failed to save: Consultation not found."));
        assertEquals("不支持的文件格式：patient-report.xyz", MessageLocalizer.localize("Unsupported file format: patient-report.xyz"));
    }

    @Test void explicitAcknowledgementsAreLocalized() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("保存成功", Result.okMessage("Saved successfully").getData());
        assertEquals("已保存 12 条记录", Result.okMessage("Saved 12 records").getData());
        assertEquals("您无权访问该患者或报告。", Result.error(403, "You do not have access to this patient or report.").getMsg());
    }

    @Test void ordinaryDataNeverUsesTheSystemMessageDictionary() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        for (String value : Arrays.asList("Saved successfully", "Current Passwordincorrect", "Consultation not found.", "As prescribed", "患者原文 English note")) {
            assertEquals(value, Result.ok(value).getData());
        }
        Map<String,Object> source = new LinkedHashMap<>();
        source.put("actorName", "Doctor"); source.put("note", "Saved successfully");
        source.put("drugName", "As prescribed"); source.put("status", "OPEN");
        assertSame(source, Result.ok(source).getData());
        assertEquals("Saved successfully", source.get("note"));
        assertEquals("患者原文 English note", MessageLocalizer.localize("患者原文 English note"));
    }
}
