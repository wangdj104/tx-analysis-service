package org.familyhealthcare.service;

import org.familyhealthcare.entity.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NotificationDeliveryServiceTest {
    @Test void dingTalkUsesSignedTextAndRejectsBusinessErrors() {
        NotificationDeliveryService service=new NotificationDeliveryService();
        MockRestServiceServer server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(service,"client")).build();
        NotificationChannel c=new NotificationChannel();c.setChannelType("DINGTALK_WEBHOOK");c.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=test");c.setRobotSecret("SECtest");c.setRobotKeyword("familyhealth");
        server.expect(request->{assertTrue(request.getURI().getRawQuery().contains("timestamp="));assertTrue(request.getURI().getRawQuery().contains("sign="));}).andExpect(content().json("{\"msgtype\":\"text\",\"text\":{\"content\":\"familyhealth\\nattentionitem\\nbringExaminationsingle\"}}")).andRespond(withSuccess("{\"errcode\":0}",MediaType.APPLICATION_JSON));
        service.send(c,"attentionitem","bringExaminationsingle");server.verify();server.reset();server.expect(request->{}).andRespond(withSuccess("{\"errcode\":310000}",MediaType.APPLICATION_JSON));assertThrows(IllegalStateException.class,()->service.send(c,"attentionitem","bringExaminationsingle"));
    }
    @Test void sendsRealWechatPayloadAndChecksBusinessResponse() {
        NotificationDeliveryService service = new NotificationDeliveryService();
        RestTemplate client = (RestTemplate) ReflectionTestUtils.getField(service,"client");
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        NotificationChannel channel = new NotificationChannel(); channel.setChannelType("WECHAT_WEBHOOK"); channel.setWebhookUrl("https://example.test/robot");
        server.expect(requestTo(channel.getWebhookUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"msgtype\":\"text\",\"text\":{\"content\":\"test\\nhello\"}}"))
                .andRespond(withSuccess("{\"errcode\":0}",MediaType.APPLICATION_JSON));
        service.send(channel,"test","hello"); server.verify();
        server.reset(); server.expect(requestTo(channel.getWebhookUrl())).andRespond(withSuccess("{\"errcode\":40014}",MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class,()->service.send(channel,"test","hello")); server.verify();
    }

    @Test void genericWebhookFailureIsNotReportedAsSuccess() {
        NotificationDeliveryService service = new NotificationDeliveryService();
        MockRestServiceServer server = MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(service,"client")).build();
        NotificationChannel channel = new NotificationChannel();channel.setChannelType("WEBHOOK");channel.setWebhookUrl("https://example.test/hook");
        server.expect(requestTo(channel.getWebhookUrl())).andExpect(content().json("{\"title\":\"t\",\"content\":\"c\"}")).andRespond(withServerError());
        assertThrows(RuntimeException.class,()->service.send(channel,"t","c"));server.verify();
    }
}
