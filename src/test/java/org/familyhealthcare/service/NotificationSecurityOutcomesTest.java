package org.familyhealthcare.service;
import org.familyhealthcare.entity.NotificationChannel;
import org.familyhealthcare.mapper.NotificationChannelMapper;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class NotificationSecurityOutcomesTest {
    HttpHeaders redirect(){HttpHeaders headers=new HttpHeaders();headers.set("Location","https://127.0.0.1/private");return headers;}
    NotificationChannel channel(long id,String url,String type){NotificationChannel c=new NotificationChannel();c.setId(id);c.setUserId(7L);c.setEnabled(1);c.setWebhookUrl(url);c.setChannelType(type);return c;}
    @Test void perChannelOutcomesKeepExplicitFailureSuccessAndAmbiguitySeparateWithoutRetries(){
        NotificationDeliveryService service=WebhookTestFixture.service();NotificationChannelMapper mapper=mock(NotificationChannelMapper.class);ReflectionTestUtils.setField(service,"mapper",mapper);
        List<NotificationChannel> channels=new ArrayList<>();for(long id=1;id<=8;id++)channels.add(channel(id,"https://synthetic.test/hook/"+id,"WEBHOOK"));
        channels.get(6).setWebhookUrl("http://127.0.0.1/hook");channels.get(7).setChannelType("WECHAT_WEBHOOK");
        when(mapper.selectList(any())).thenReturn(channels);
        MockRestServiceServer server=MockRestServiceServer.bindTo(WebhookTestFixture.client(service)).build();
        server.expect(requestTo(channels.get(0).getWebhookUrl())).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        server.expect(requestTo(channels.get(1).getWebhookUrl())).andRespond(withBadRequest());
        server.expect(requestTo(channels.get(2).getWebhookUrl())).andRespond(withException(new SocketTimeoutException("synthetic uncertain")));
        server.expect(requestTo(channels.get(3).getWebhookUrl())).andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
        server.expect(requestTo(channels.get(4).getWebhookUrl())).andRespond(withServerError());
        server.expect(requestTo(channels.get(5).getWebhookUrl())).andRespond(withStatus(HttpStatus.FOUND).headers(redirect()));
        server.expect(requestTo(channels.get(7).getWebhookUrl())).andRespond(withSuccess("{\"errcode\":310000}",MediaType.APPLICATION_JSON));
        Map<Long,DeliveryOutcome> result=service.notifyUserOutcomes(7L,Collections.emptyList(),"HEALTH_ANALYSIS_DRAFT","synthetic","synthetic");
        assertEquals(8,result.size());assertEquals(DeliveryOutcome.DELIVERED,result.get(1L));assertEquals(DeliveryOutcome.FAILED,result.get(2L));
        for(long id=3;id<=6;id++)assertEquals(DeliveryOutcome.UNKNOWN,result.get(id));
        assertEquals(DeliveryOutcome.FAILED,result.get(7L));assertEquals(DeliveryOutcome.FAILED,result.get(8L));server.verify();
    }
    @Test void sendRevalidatesAfterSaveValidationAndStopsDnsRebindingBeforeTransport()throws Exception{
        AtomicInteger lookups=new AtomicInteger();WebhookTransport transport=mock(WebhookTransport.class);
        InetAddress publicIp=InetAddress.getByAddress(new byte[]{93,(byte)184,(byte)216,34});
        NotificationDeliveryService service=new NotificationDeliveryService(new WebhookTargetPolicy(host->new InetAddress[]{lookups.incrementAndGet()==1?publicIp:InetAddress.getLoopbackAddress()}),transport);
        service.validateWebhookUrl("https://synthetic.test/hook");
        assertThrows(IllegalArgumentException.class,()->service.send(channel(1L,"https://synthetic.test/hook","WEBHOOK"),"synthetic","synthetic"));
        assertEquals(2,lookups.get());verifyNoInteractions(transport);
    }
    @Test void observerPersistsEachOutcomeAndJournalFailureAbortsBeforeTheNextChannel()throws Exception {
        NotificationDeliveryService service=WebhookTestFixture.service();NotificationChannelMapper mapper=mock(NotificationChannelMapper.class);ReflectionTestUtils.setField(service,"mapper",mapper);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(channel(1L,"https://synthetic.test/one","WEBHOOK"),channel(2L,"https://synthetic.test/two","WEBHOOK")));
        MockRestServiceServer server=MockRestServiceServer.bindTo(WebhookTestFixture.client(service)).build();
        server.expect(requestTo("https://synthetic.test/one")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        List<Long> recorded=new ArrayList<>();java.util.function.BiConsumer<Long,DeliveryOutcome> observer=(id,outcome)->{recorded.add(id);assertEquals(DeliveryOutcome.DELIVERED,outcome);throw new IllegalStateException("synthetic journal failure");};
        IllegalStateException error=assertThrows(IllegalStateException.class,()->service.notifyUserOutcomes(7L,Collections.emptyList(),"HEALTH_ANALYSIS_DRAFT","synthetic","synthetic",observer));
        assertEquals("synthetic journal failure",error.getMessage());assertEquals(Collections.singletonList(1L),recorded);server.verify();
    }
    @Test void providerAcknowledgementsMustBeStrictProtocolIntegersAndNeverRetryMalformedResponses(){
        for(String type:Arrays.asList("WECHAT_WEBHOOK","DINGTALK_WEBHOOK")){
            Map<String,DeliveryOutcome> acknowledgements=new LinkedHashMap<>();
            acknowledgements.put("{\"errcode\":0}",DeliveryOutcome.DELIVERED);acknowledgements.put("{\"errcode\":310000}",DeliveryOutcome.FAILED);
            for(String invalid:Arrays.asList("{\"errcode\":0.5}","{\"errcode\":4294967296}","{\"errcode\":-4294967296}","{\"errcode\":0.0}","{\"errcode\":0e0}","{\"errcode\":\"0\"}","{\"errcode\":true}","{\"errcode\":[0]}","{\"errcode\":{\"value\":0}}","{\"errcode\":null}","{}","[]","broken","{\"errcode\":0} {}","{\"errcode\":310000,\"errcode\":0}"))acknowledgements.put(invalid,DeliveryOutcome.UNKNOWN);
            for(Map.Entry<String,DeliveryOutcome> ack:acknowledgements.entrySet()){
                NotificationDeliveryService service=WebhookTestFixture.service();NotificationChannelMapper mapper=mock(NotificationChannelMapper.class);ReflectionTestUtils.setField(service,"mapper",mapper);ReflectionTestUtils.setField(service,"robots",mock(RobotChannelConfigService.class));
                when(mapper.selectList(any())).thenReturn(Collections.singletonList(channel(1L,"https://synthetic.test/ack",type)));
                MockRestServiceServer server=MockRestServiceServer.bindTo(WebhookTestFixture.client(service)).build();
                server.expect(requestTo("https://synthetic.test/ack")).andRespond(withSuccess(ack.getKey(),MediaType.APPLICATION_JSON));
                List<DeliveryOutcome> journal=new ArrayList<>();Map<Long,DeliveryOutcome> result=service.notifyUserOutcomes(7L,Collections.emptyList(),"HEALTH_ANALYSIS_DRAFT","synthetic","synthetic",(id,outcome)->journal.add(outcome));
                assertEquals(ack.getValue(),result.get(1L),type+" "+ack.getKey());assertEquals(Collections.singletonList(ack.getValue()),journal);server.verify();
                server.reset();when(mapper.selectById(1L)).thenReturn(channel(1L,"https://synthetic.test/ack",type));
                server.expect(requestTo("https://synthetic.test/ack")).andRespond(withSuccess(ack.getKey(),MediaType.APPLICATION_JSON));
                org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryAttempt adapter=new org.familyhealthcare.service.careplan.CarePlanNotificationTransport.ExistingChannels(service).attempt(1L,"CARE_PLAN_PLAN_PUBLISHED","synthetic","/care-plans/1");
                assertEquals(ack.getValue(),adapter.getOutcome(),"care-plan "+type+" "+ack.getKey());assertFalse(adapter.isRetryable());server.verify();
            }
        }
    }
}
