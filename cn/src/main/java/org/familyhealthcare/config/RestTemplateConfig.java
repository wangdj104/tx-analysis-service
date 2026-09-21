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
}
