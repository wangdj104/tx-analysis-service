package org.familyhealthcare.controller;
import org.familyhealthcare.service.alertworkflow.*;
import org.familyhealthcare.config.SensitiveResponseFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.time.Clock;
import static org.mockito.Mockito.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AlertWorkflowNotificationControllerTest {
    @Test void safeCurrentStatusAndTokenConditionalRetryUseOnlyServerActor() throws Exception {Api a=new Api(true);when(a.worker.deliveryStatus(7,12,null,50)).thenReturn(map("items",java.util.Collections.emptyList(),"deliveryMeaning","PROVIDER_ACCEPTANCE_ONLY"));when(a.worker.retry(7,21,a.token,true)).thenReturn(map("id",21,"status","QUEUED"));
        a.mvc.perform(get("/alert-workflows/12/delivery-status").requestAttr("userId",7L)).andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryMeaning").value("PROVIDER_ACCEPTANCE_ONLY")).andExpect(header().string("Cache-Control","no-store, private"));
        a.mvc.perform(post("/alert-workflows/notifications/21/retry").requestAttr("userId",7L).contentType(MediaType.APPLICATION_JSON).content(a.body())).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));verify(a.worker).retry(7,21,a.token,true);
    }
    @Test void rejectsUnknownFieldsBadFlagsQueriesAndMissingAuthentication() throws Exception {Api a=new Api(true);for(String body:new String[]{"{}","{\"retryToken\":\"x\",\"duplicateRiskAcknowledged\":\"true\"}",a.body().replace("}",",\"actorId\":3}"),a.body().replace("\"duplicateRiskAcknowledged\":true","\"duplicateRiskAcknowledged\":null")})a.mvc.perform(post("/alert-workflows/notifications/21/retry").requestAttr("userId",7L).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        a.mvc.perform(get("/alert-workflows/12/delivery-status").requestAttr("userId",7L).param("extra","1")).andExpect(status().isBadRequest());a.mvc.perform(get("/alert-workflows/12/delivery-status").requestAttr("userId",7L).param("limit","1","2")).andExpect(status().isBadRequest());a.mvc.perform(get("/alert-workflows/12/delivery-status")).andExpect(status().isForbidden());verifyNoInteractions(a.worker);
    }
    @Test void disabledAndConflictResponsesRetainSensitiveHeadersAndRealHttpCodes() throws Exception {Api off=new Api(false);off.mvc.perform(get("/alert-workflows/12/delivery-status").requestAttr("userId",7L)).andExpect(status().isNotFound()).andExpect(jsonPath("$.data.errorCode").value("FEATURE_DISABLED")).andExpect(header().string("X-Content-Type-Options","nosniff"));verifyNoInteractions(off.worker);Api a=new Api(true);when(a.worker.retry(anyLong(),anyLong(),anyString(),anyBoolean())).thenThrow(new AlertWorkflowException(409,"RETRY_CONFLICT","Delivery changed. Refresh its status before retrying."));a.mvc.perform(post("/alert-workflows/notifications/21/retry").requestAttr("userId",7L).contentType(MediaType.APPLICATION_JSON).content(a.body())).andExpect(status().isConflict()).andExpect(jsonPath("$.data.errorCode").value("RETRY_CONFLICT"));}
    static class Api {final AlertWorkflowNotificationWorker worker=mock(AlertWorkflowNotificationWorker.class);final MockMvc mvc;final String token=String.join("",java.util.Collections.nCopies(64,"a"));Api(boolean enabled){DefaultListableBeanFactory b=new DefaultListableBeanFactory();b.registerSingleton("worker",worker);AlertWorkflowNotificationController c=new AlertWorkflowNotificationController(new AlertWorkflowProperties(enabled,Clock.systemUTC()),b.getBeanProvider(AlertWorkflowNotificationWorker.class),mock(AlertWorkflowAuthorizationService.class));mvc=MockMvcBuilders.standaloneSetup(c).setControllerAdvice(new AlertWorkflowExceptionAdvice()).addFilters(new SensitiveResponseFilter()).build();}String body(){return "{\"retryToken\":\""+token+"\",\"duplicateRiskAcknowledged\":true}";}}
}
