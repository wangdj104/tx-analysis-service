package org.familyhealthcare.service;
import java.net.InetAddress;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.util.ReflectionTestUtils;
/** Payload-only tests retain real Spring serialization without touching system DNS or sockets. */
final class WebhookTestFixture {
    static final class MockTransport extends WebhookTransport {
        final RestTemplate client=new RestTemplate();
        @Override public ResponseEntity<String> post(WebhookTargetPolicy.Target target,Map<String,Object> body){
            HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
            return client.postForEntity(target.getUri(),new HttpEntity<>(body,headers),String.class);
        }
    }
    static NotificationDeliveryService service(){
        return new NotificationDeliveryService(new WebhookTargetPolicy(host->new InetAddress[]{InetAddress.getByAddress(new byte[]{93,(byte)184,(byte)216,34})}),new MockTransport());
    }
    static RestTemplate client(NotificationDeliveryService service){return ((MockTransport)ReflectionTestUtils.getField(service,"transport")).client;}
}
