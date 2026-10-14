package org.familyhealthcare.controller;

import org.familyhealthcare.config.SensitiveResponseFilter;
import org.junit.jupiter.api.Test;
import org.familyhealthcare.service.alertworkflow.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.familyhealthcare.service.workflow.WorkflowData.map;
import org.springframework.mock.web.*;
import javax.servlet.http.HttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class AlertWorkflowControllerTest {
    @Test void allWorkflowRoutesIncludingRejectedMethodsAndErrorsAreNoStore() throws Exception {
        for(String path:new String[]{"/api/alert-workflows","/api/alert-workflows/12/close","/api/alert-workflows/capabilities","/api/alert-workflows/12/events"}) {
            MockHttpServletRequest request=new MockHttpServletRequest("PATCH",path);MockHttpServletResponse response=new MockHttpServletResponse();
            new SensitiveResponseFilter().doFilter(request,response,(req,res)->((HttpServletResponse)res).setStatus(405));
            assertEquals("no-store, private",response.getHeader("Cache-Control"),path);assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
        }
    }
    @Test void exactEnvelopeUsesServerActorAndFixedActionMapping() throws Exception {
        Api api=new Api();when(api.service.command(eq(7L),eq(12L),eq("REJECT_ASSIGNMENT"),anyMap(),anyString(),eq(2L))).thenReturn(map("alertId",12,"version",3));
        api.mvc.perform(post("/alert-workflows/12/reject-assignment").requestAttr("userId",7L).contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"00000000-0000-0000-0000-000000000001\",\"expectedVersion\":2,\"body\":{\"reason\":\"Busy\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3));verify(api.service).command(eq(7L),eq(12L),eq("REJECT_ASSIGNMENT"),eq(map("reason","Busy")),anyString(),eq(2L));
    }
    @Test void rejectsUnknownKeysDuplicateQueryInvalidVersionAndArbitraryStatusPatches() throws Exception {
        Api api=new Api();String base="{\"commandKey\":\"00000000-0000-0000-0000-000000000001\",\"expectedVersion\":%s,\"body\":{}}";
        for(String version:new String[]{"-1","1.1","1.0","1e2","\"1\"","null"})api.mvc.perform(post("/alert-workflows/12/close").requestAttr("userId",7L).contentType(MediaType.APPLICATION_JSON).content(String.format(base,version))).andExpect(status().isBadRequest());
        api.mvc.perform(post("/alert-workflows/12/close").requestAttr("userId",7L).contentType(MediaType.APPLICATION_JSON).content(String.format(base,"2").replace("\"body\":{}","\"body\":{},\"actorId\":3"))).andExpect(status().isBadRequest());
        api.mvc.perform(get("/alert-workflows").requestAttr("userId",7L).param("patientId","10","20")).andExpect(status().isBadRequest());
        api.mvc.perform(get("/alert-workflows/12").requestAttr("userId",7L).param("extra","1")).andExpect(status().isBadRequest());
        api.mvc.perform(post("/alert-workflows/12/status").requestAttr("userId",7L).contentType(MediaType.APPLICATION_JSON).content(String.format(base,"2"))).andExpect(status().isBadRequest());
        api.mvc.perform(patch("/alert-workflows/12").requestAttr("userId",7L).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"CLOSED\"}")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(api.service);
    }
    @Test void inaccessibleAndMissingIdsHaveIdenticalSafeEnvelope() throws Exception {
        Api api=new Api();when(api.query.detail(anyLong(),anyLong())).thenThrow(AlertWorkflowException.denied());
        String a=api.mvc.perform(get("/alert-workflows/12").requestAttr("userId",7L)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        String b=api.mvc.perform(get("/alert-workflows/999").requestAttr("userId",7L)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.node.ObjectNode ja=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(a),jb=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(b);assertTrue(ja.remove("timestamp").isNumber());assertTrue(jb.remove("timestamp").isNumber());assertEquals(ja,jb);
    }
    @Test void missingAuthenticationCannotReachServices() throws Exception {Api api=new Api();api.mvc.perform(get("/alert-workflows/12")).andExpect(status().isForbidden());verifyNoInteractions(api.query);}
    @Test void disabledCapabilitiesDoNotInstantiateWorkflowBeansOrBorrowCarePlanEnablement() throws Exception {
        AlertWorkflowProperties properties=new AlertWorkflowProperties(false,Clock.systemUTC());DefaultListableBeanFactory beans=new DefaultListableBeanFactory();AlertWorkflowAuthorizationService auth=mock(AlertWorkflowAuthorizationService.class);
        AlertWorkflowController controller=new AlertWorkflowController(properties,beans.getBeanProvider(AlertWorkflowService.class),beans.getBeanProvider(AlertWorkflowQueryService.class),auth);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new AlertWorkflowExceptionAdvice()).addFilters(new SensitiveResponseFilter()).build();
        mvc.perform(get("/alert-workflows/capabilities").requestAttr("userId",7L)).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false)).andExpect(header().string("Cache-Control","no-store, private"));
        mvc.perform(get("/alert-workflows/12").requestAttr("userId",7L)).andExpect(status().isNotFound()).andExpect(jsonPath("$.data.errorCode").value("FEATURE_DISABLED"));verify(auth).requireAccount(7);
    }
    private static final class Api {
        final AlertWorkflowService service=mock(AlertWorkflowService.class);final AlertWorkflowQueryService query=mock(AlertWorkflowQueryService.class);final MockMvc mvc;
        Api() throws Exception {
            AlertWorkflowProperties p=new AlertWorkflowProperties(true,Clock.systemUTC());AlertWorkflowAuthorizationService auth=mock(AlertWorkflowAuthorizationService.class);DefaultListableBeanFactory beans=new DefaultListableBeanFactory();beans.registerSingleton("service",service);beans.registerSingleton("query",query);
            AlertWorkflowController controller=new AlertWorkflowController(p,beans.getBeanProvider(AlertWorkflowService.class),beans.getBeanProvider(AlertWorkflowQueryService.class),auth);
            mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new AlertWorkflowExceptionAdvice()).addFilters(new SensitiveResponseFilter()).build();
        }
    }
    @Test void workflowAdviceIsScopedToItsOwnController() {assertTrue(AlertWorkflowController.class.isAnnotationPresent(AlertWorkflowExceptionAdvice.Api.class));assertFalse(CarePlanController.class.isAnnotationPresent(AlertWorkflowExceptionAdvice.Api.class));}
}
