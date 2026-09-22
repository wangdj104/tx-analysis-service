package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserLanguagePreferenceServiceTest {
    @Test void independentChineseBackendDefaultsUnconfiguredUsersToChinese() {
        UserLanguagePreferenceService service = new UserLanguagePreferenceService();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(8L), eq("language"))).thenReturn(Collections.emptyList());
        assertEquals("zh-CN", service.get(null));
        assertEquals("zh-CN", service.get(8L));
    }

    @Test void explicitEnglishPreferenceOverridesChineseBackendDefault() {
        UserLanguagePreferenceService service = new UserLanguagePreferenceService();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(8L), eq("language"))).thenReturn(Collections.singletonList("en-US"));
        assertEquals("en-US", service.get(8L));
    }

    @Test void languageCaptureNormalizesTheCurrentUiLocaleBeforeSaving() {
        UserLanguagePreferenceService service = new UserLanguagePreferenceService();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        service.capture(8L, "zh-CN,zh;q=0.9");
        verify(jdbc).update(anyString(), eq(8L), eq("language"), eq("zh-CN"));
        service.capture(8L, "en-US,en;q=0.9");
        verify(jdbc).update(anyString(), eq(8L), eq("language"), eq("en-US"));
    }
}
