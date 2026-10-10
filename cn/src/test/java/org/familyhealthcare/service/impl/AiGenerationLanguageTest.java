package org.familyhealthcare.service.impl;

import com.alibaba.fastjson2.JSON;
import org.familyhealthcare.config.OcrVisionProperties;
import org.familyhealthcare.vo.AiAnalysisResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiGenerationLanguageTest {
    @AfterEach void clearLocale() { LocaleContextHolder.resetLocaleContext(); }

    @Test void analysisRequestUsesSelectedLanguageWithoutRewritingClinicalInput() {
        for (Locale locale : Arrays.asList(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            LocaleContextHolder.setLocale(locale);
            AiAnalysisServiceImpl service = new AiAnalysisServiceImpl();
            RestTemplate client = mock(RestTemplate.class);
            ReflectionTestUtils.setField(service, "restTemplate", client);
            ReflectionTestUtils.setField(service, "apiKey", "synthetic");
            ReflectionTestUtils.setField(service, "apiUrl", "https://example.invalid/v1");
            String clinical = "Patient Doctor: Normal; 原始症状及药名 remain verbatim";
            when(client.postForObject(anyString(), any(), eq(String.class))).thenAnswer(call -> {
                HttpEntity<?> request = call.getArgument(1);
                List<?> messages = (List<?>) ((Map<?, ?>) request.getBody()).get("messages");
                String system = String.valueOf(((Map<?, ?>) messages.get(0)).get("content"));
                assertTrue(system.contains(locale.getLanguage().equals("zh") ? "简体中文" : "English"));
                assertEquals(clinical, ((Map<?, ?>) messages.get(1)).get("content"));
                return response("Original clinical answer 原文");
            });
            assertEquals("Original clinical answer 原文", ReflectionTestUtils.invokeMethod(service, "callDeepSeek", clinical, false));
            verify(client).postForObject(anyString(), any(), eq(String.class));
        }
    }

    @Test void structuredAnalysisAcceptsBothExistingJsonContractsAndKeepsSourceText() {
        AiAnalysisServiceImpl service = new AiAnalysisServiceImpl();
        for (String[] keys : new String[][] {
                {"YesNoadjustment needed", "recommendationadjustment amount", "recommendationtargetDry Weight", "adjustment rationale"},
                {"是否需要调整", "建议调整量", "建议目标干体重", "调整理由"}}) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(keys[0], "是"); payload.put(keys[1], "+0.5 kg"); payload.put(keys[2], "60.5 kg");
            payload.put(keys[3], "Normal is the original note; 原文不变");
            AiAnalysisResultVO result = new AiAnalysisResultVO();
            ReflectionTestUtils.invokeMethod(service, "parseConclusion", "```json\n" + JSON.toJSONString(payload) + "\n```", result);
            assertEquals("Yes", result.getDwAdjustNeeded());
            assertEquals(new BigDecimal("0.5"), result.getDwAdjustAmount());
            assertEquals(new BigDecimal("60.5"), result.getDwTargetWeight());
            assertEquals(payload.get(keys[3]), result.getDwAdjustReason());
        }
    }

    @Test void missingStructuredConclusionIsLocalizedAndDoesNotInventAnAdjustment() {
        AiAnalysisServiceImpl service = new AiAnalysisServiceImpl();
        for (Locale locale : Arrays.asList(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            LocaleContextHolder.setLocale(locale);
            String result = ReflectionTestUtils.invokeMethod(service, "ensureDryWeightConclusion", "Original clinical note 原文");
            assertTrue(result.startsWith("Original clinical note 原文"));
            assertTrue(result.contains(locale.getLanguage().equals("zh") ? "人工复核" : "clinician review"));
            assertFalse(result.contains("0 kg"));
            AiAnalysisResultVO parsed = new AiAnalysisResultVO();
            ReflectionTestUtils.invokeMethod(service, "parseConclusion", result, parsed);
            assertNull(parsed.getDwAdjustAmount()); assertNull(parsed.getDwTargetWeight());
            assertNotEquals("No", parsed.getDwAdjustNeeded());
        }
    }

    @Test void ocrLocalizesSystemWarningsAndPreservesExtractedNamesAndClinicalText() {
        for (Locale locale : Arrays.asList(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            LocaleContextHolder.setLocale(locale);
            AiOcrServiceImpl service = new AiOcrServiceImpl();
            RestTemplate client = mock(RestTemplate.class);
            OcrVisionProperties properties = new OcrVisionProperties(); properties.setApiKey("synthetic");
            ReflectionTestUtils.setField(service, "visionProps", properties);
            ReflectionTestUtils.setField(service, "restTemplate", client);
            when(client.postForObject(anyString(), any(), eq(String.class))).thenAnswer(call -> {
                HttpEntity<?> request = call.getArgument(1);
                List<?> messages = (List<?>) ((Map<?, ?>) request.getBody()).get("messages");
                String system = String.valueOf(((Map<?, ?>) messages.get(0)).get("content"));
                assertTrue(system.contains("Preserve original"));
                assertTrue(system.contains(locale.getLanguage().equals("zh") ? "简体中文" : "English"));
                return response("{\"drugs\":[{\"drugName\":\"Normal\",\"manufacturer\":\"Doctor\",\"remark\":\"Patient wrote: 血压 normal\"},{\"drugName\":\"阿司匹林\"}]}");
            });
            Map<String,Object> output = service.recognizeMedication("synthetic-image");
            List<?> drugs = (List<?>) output.get("drugs");
            assertEquals("Normal", ((Map<?,?>) drugs.get(0)).get("drugName"));
            assertEquals("Doctor", ((Map<?,?>) drugs.get(0)).get("manufacturer"));
            assertEquals("Patient wrote: 血压 normal", ((Map<?,?>) drugs.get(0)).get("remark"));
            assertTrue(String.valueOf(output.get("warning")).contains(locale.getLanguage().equals("zh") ? "2 种药品" : "2 medications"));
        }
    }

    @Test void missingOcrConfigurationHasLocalizedActionableError() {
        AiOcrServiceImpl service = new AiOcrServiceImpl();
        ReflectionTestUtils.setField(service, "visionProps", new OcrVisionProperties());
        ReflectionTestUtils.setField(service, "bailianApiKey", "");
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertTrue(String.valueOf(service.recognizeMedication("synthetic").get("error")).startsWith("尚未配置"));
        LocaleContextHolder.setLocale(Locale.US);
        assertTrue(String.valueOf(service.recognizeMedicalReport("synthetic", null).get("error")).startsWith("OCR is not configured"));
    }

    private static String response(String value) {
        return JSON.toJSONString(Collections.singletonMap("choices", Collections.singletonList(Collections.singletonMap("message", Collections.singletonMap("content", value)))));
    }
}
