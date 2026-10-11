package org.familyhealthcare.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.*;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic client only: no provider requests or credentials. */
class AiAnalysisFailureRegressionTest {
    AiAnalysisServiceImpl service(RestTemplate client) {
        AiAnalysisServiceImpl s = new AiAnalysisServiceImpl();
        ReflectionTestUtils.setField(s,"restTemplate",client);
        ReflectionTestUtils.setField(s,"apiKey","synthetic");
        ReflectionTestUtils.setField(s,"apiUrl","https://example.invalid/v1");
        return s;
    }
    @Test void transportAndHttpFailuresNeverBecomeClinicalText() {
        for (RuntimeException failure : new RuntimeException[]{new ResourceAccessException("synthetic timeout"),new HttpServerErrorException(HttpStatus.BAD_GATEWAY),new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT),new HttpClientErrorException(HttpStatus.BAD_REQUEST)}) {
            RestTemplate client=mock(RestTemplate.class);
            when(client.postForObject(anyString(),any(),eq(String.class))).thenThrow(failure);
            org.familyhealthcare.service.AiAnalysisException typed=assertThrows(org.familyhealthcare.service.AiAnalysisException.class,()->ReflectionTestUtils.invokeMethod(service(client),"callDeepSeek","synthetic",false));
            assertEquals(!(failure instanceof HttpClientErrorException) || ((HttpClientErrorException)failure).getRawStatusCode()==408,typed.isOutcomeUncertain());
        }
    }
    @Test void invalidJsonMissingChoicesAndEmptyBodyNeverBecomeClinicalText() {
        for(String response:new String[]{"not JSON","{}","{\"choices\":[]}","{\"choices\":[{\"message\":{\"content\":\"  \"}}]}","null"}) {
            RestTemplate client=mock(RestTemplate.class);
            when(client.postForObject(anyString(),any(),eq(String.class))).thenReturn(response);
            assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service(client),"callDeepSeek","synthetic",false));
        }
    }
    @Test void nonStringClinicalBodiesAreInvalidInsteadOfCoercedText() {
        for(String value:new String[]{"42","true","[]","{}"}) {
            RestTemplate client=mock(RestTemplate.class);
            when(client.postForObject(anyString(),any(),eq(String.class))).thenReturn("{\"choices\":[{\"message\":{\"content\":"+value+"}}]}");
            org.familyhealthcare.service.AiAnalysisException failure=assertThrows(org.familyhealthcare.service.AiAnalysisException.class,()->ReflectionTestUtils.invokeMethod(service(client),"callDeepSeek","synthetic",false));
            assertEquals(org.familyhealthcare.service.AiAnalysisException.Reason.INVALID_RESPONSE,failure.getReason());assertFalse(failure.isOutcomeUncertain());
        }
    }
    @Test void validBodyRemainsVerbatimForStructuredAndGeneralGeneration() {
        RestTemplate client=mock(RestTemplate.class);
        String answer="  original clinical text 原文\n";
        when(client.postForObject(anyString(),any(),eq(String.class))).thenReturn("{\"choices\":[{\"message\":{\"content\":\"  original clinical text 原文\\n\"}}]}");
        assertEquals(answer,ReflectionTestUtils.invokeMethod(service(client),"callDeepSeek","synthetic",false));
        assertEquals(answer,ReflectionTestUtils.invokeMethod(service(client),"callDeepSeek","synthetic",true));
    }
}
