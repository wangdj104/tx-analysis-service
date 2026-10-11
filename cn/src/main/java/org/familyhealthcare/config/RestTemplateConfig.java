package org.familyhealthcare.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @createTime 2025-04-06
 */
@Configuration
public class RestTemplateConfig {
    @Value("${rest-template.read-timeout}") // from configurationfileinreadreadovertimeTime
    private int readTimeout;
    @Value("${rest-template.connection-timeout}")// from configurationfileinreadconnectionovertimeTime
    private int connectionTimeout;
    @Bean
    @org.springframework.context.annotation.Primary
    public RestTemplate restTemplate() {
        // use SimpleClientHttpRequestFactory settingsovertimeTime
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(readTimeout);      // readovertimeTime
        factory.setConnectTimeout(connectionTimeout); // connectionovertimeTime

        RestTemplate restTemplate = new RestTemplate(factory);
        List<HttpMessageConverter<?>> messageConverters = new ArrayList<>();

        // AddDefault  JSON convertchangedevice
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        // support application/octet-stream type
        jsonConverter.setSupportedMediaTypes(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));
        messageConverters.add(jsonConverter);

        // AddOtherDefault convertchangedevice
        messageConverters.addAll(restTemplate.getMessageConverters());

        restTemplate.setMessageConverters(messageConverters);
        return restTemplate;
    }
    @Bean(destroyMethod = "close")
    public org.apache.http.impl.client.CloseableHttpClient aiAnalysisHttpClient() {
        return org.apache.http.impl.client.HttpClients.custom()
                .disableAutomaticRetries().disableRedirectHandling().build();
    }

    /** AI 超时小于运行认领恢复窗口，不改变 OCR 等共享客户端的配置。 */
    @Value("${deepseek.connect-timeout:5000}") private int aiConnectTimeout;
    @Value("${deepseek.read-timeout:30000}") private int aiReadTimeout;
    @Bean("aiAnalysisRestTemplate")
    public RestTemplate aiAnalysisRestTemplate(org.apache.http.impl.client.CloseableHttpClient client) {
        org.springframework.http.client.HttpComponentsClientHttpRequestFactory factory = new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(client);
        factory.setConnectionRequestTimeout(aiConnectTimeout > 0 ? Math.min(aiConnectTimeout, 10000) : 5000);
        factory.setConnectTimeout(aiConnectTimeout > 0 ? Math.min(aiConnectTimeout, 10000) : 5000);
        factory.setReadTimeout(aiReadTimeout > 0 ? Math.min(aiReadTimeout, 120000) : 30000);
        RestTemplate template = new RestTemplate(factory);
        template.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                int status = response.getRawStatusCode();
                return status < 200 || status >= 300;
            }
        });
        return template;
    }

}
