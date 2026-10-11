package org.familyhealthcare.service;
import org.familyhealthcare.entity.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class WebhookBaselineBoundaryTest {
    @Test void httpAndLoopbackAreRejectedBeforeAnyDelivery(){
        for(String url:new String[]{"http://127.0.0.1/hook","https://127.0.0.1/hook","https://[::1]/hook"}){
            NotificationDeliveryService s=new NotificationDeliveryService();

            NotificationChannel c=new NotificationChannel();c.setChannelType("WEBHOOK");c.setWebhookUrl(url);
            assertThrows(IllegalArgumentException.class,()->s.send(c,"synthetic","synthetic"),url);
        }
    }
}
