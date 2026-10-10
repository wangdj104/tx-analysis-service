package org.familyhealthcare.service;

import org.familyhealthcare.controller.NotificationChannelController;
import org.familyhealthcare.entity.NotificationChannel;
import org.familyhealthcare.mapper.NotificationChannelMapper;
import org.familyhealthcare.util.CurrentUserUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class NotificationChannelLanguageTest {
    @AfterEach void resetLocale(){LocaleContextHolder.resetLocaleContext();}
    @Test void testStatusStaysCanonicalInStorageAndUsesTheCurrentDisplayLanguage() {
        NotificationChannelController controller = new NotificationChannelController();
        NotificationChannelMapper mapper = mock(NotificationChannelMapper.class);
        ReflectionTestUtils.setField(controller, "mapper", mapper);
        ReflectionTestUtils.setField(controller, "robots", mock(RobotChannelConfigService.class));
        ReflectionTestUtils.setField(controller, "delivery", mock(NotificationDeliveryService.class));
        NotificationChannel channel = new NotificationChannel(); channel.setId(1L); channel.setUserId(7L);
        when(mapper.selectById(1L)).thenReturn(channel);
        AtomicReference<String> stored = new AtomicReference<>();
        when(mapper.updateById(any())).thenAnswer(invocation -> { stored.set(((NotificationChannel) invocation.getArgument(0)).getLastTestResult()); return 1; });
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            NotificationChannel row = new NotificationChannel(); row.setId(1L); row.setUserId(7L); row.setLastTestResult(stored.get());
            return Collections.singletonList(row);
        });
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("测试消息已发送，请在接收端查看。", CurrentUserUtil.runAsUser(7L, () -> controller.test(1L)).getData());
        assertEquals("Test message sent. Check the receiving channel.", stored.get());
        assertEquals("测试消息已发送，请在接收端查看。", CurrentUserUtil.runAsUser(7L, controller::list).getData().get(0).getLastTestResult());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("测试消息已发送，请在接收端查看。", CurrentUserUtil.runAsUser(7L, controller::list).getData().get(0).getLastTestResult());
        assertEquals("Test message sent. Check the receiving channel.", stored.get());
    }
}
