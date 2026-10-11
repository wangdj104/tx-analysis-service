package org.familyhealthcare.service.impl;
import org.familyhealthcare.config.RestTemplateConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.client.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.HttpHost;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AiHttpClientLifecycleTest {
 @Test void boundedDedicatedClientIsClosedWithBeanContext() {
  AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();Map<String,Object> properties=new HashMap<>();properties.put("rest-template.read-timeout",900000);properties.put("rest-template.connection-timeout",200000);properties.put("deepseek.connect-timeout",999999);properties.put("deepseek.read-timeout",999999);
  context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",properties));context.register(RestTemplateConfig.class);context.refresh();
  CloseableHttpClient client=context.getBean(CloseableHttpClient.class);PoolingHttpClientConnectionManager pool=(PoolingHttpClientConnectionManager)ReflectionTestUtils.getField(client,"connManager");
  RestTemplate ai=context.getBean("aiAnalysisRestTemplate",RestTemplate.class);RequestConfig config=(RequestConfig)ReflectionTestUtils.getField(ai.getRequestFactory(),"requestConfig");assertEquals(10000,config.getConnectTimeout());assertEquals(10000,config.getConnectionRequestTimeout());assertEquals(120000,config.getSocketTimeout());
  assertTrue(context.getBean("restTemplate",RestTemplate.class).getRequestFactory() instanceof SimpleClientHttpRequestFactory);
  context.close();assertThrows(IllegalStateException.class,()->pool.requestConnection(new HttpRoute(new HttpHost("example.invalid",443,"https")),null));
 }
 @Test void nonSuccessRedirectResponseCannotReturnSuccessfulClinicalBody()throws Exception {
  RestTemplateConfig config=new RestTemplateConfig();
  try(CloseableHttpClient client=config.aiAnalysisHttpClient()) {
   RestTemplate template=config.aiAnalysisRestTemplate(client);
   org.springframework.test.web.client.MockRestServiceServer server=org.springframework.test.web.client.MockRestServiceServer.createServer(template);
   server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://example.invalid/synthetic")).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.FOUND).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{\"choices\":[{\"message\":{\"content\":\"Synthetic body\"}}]}"));
   assertThrows(org.springframework.web.client.RestClientResponseException.class,()->template.postForObject("https://example.invalid/synthetic",Collections.emptyMap(),String.class));server.verify();
  }
 }
 @Test void defaultsAreFiveAndThirtySecondsIndependentOfOcr() throws Exception {
  RestTemplateConfig config=new RestTemplateConfig();ReflectionTestUtils.setField(config,"connectionTimeout",60000);ReflectionTestUtils.setField(config,"readTimeout",900000);
  try(CloseableHttpClient client=config.aiAnalysisHttpClient()){RestTemplate ai=config.aiAnalysisRestTemplate(client);RequestConfig request=(RequestConfig)ReflectionTestUtils.getField(ai.getRequestFactory(),"requestConfig");assertEquals(5000,request.getConnectTimeout());assertEquals(30000,request.getSocketTimeout());}
 }
}
