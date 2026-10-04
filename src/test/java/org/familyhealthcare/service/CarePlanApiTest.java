package org.familyhealthcare.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.config.WebMvcConfig;
import org.familyhealthcare.controller.CarePlanExceptionAdvice;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.interceptor.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.familyhealthcare.service.CarePlanLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Existing collaboration API regressions use the shared actual web chain. */
class CarePlanApiTest extends CarePlanWebTestSupport {
    @Test void capabilitiesAreAuthenticatedDataFreeAndFeatureOffNeverReadsNewTables() throws Exception {
        mvc.perform(get("/api/care-plans/capabilities")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        perform(get("/api/care-plans/capabilities"),OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        ReflectionTestUtils.setField(h.properties,"enabled",false);
        h.f.jdbc().execute("DROP TABLE care_plan_notification,care_plan_evidence,care_plan_command,care_plan_event,care_plan_action,care_plan_revision,care_nurse_assignment CASCADE");
        Map<?,?> data=(Map<?,?>)read(perform(get("/api/care-plans/capabilities"),OWNER)).get("data");assertEquals(Collections.singletonMap("enabled",false),data);
        for(String path:new String[]{"","/2","/2/events","/2/revisions","/2/revisions/3","/assignees?patientId=1","/notifications"})
            perform(get("/api/care-plans"+path),OWNER).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        perform(post("/api/care-plans").contentType("application/json").content("{}"),DOCTOR).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        verifyNoInteractions(transport);
    }
    @Test void featureOffStartsWithoutConditionalServicesAndGatesMalformedRequestsBeforeBodyParsing() throws Exception {
        context.close();buildContext(false);
        assertTrue(context.getBeansOfType(CarePlanService.class).isEmpty());assertTrue(context.getBeansOfType(CarePlanNotificationWorker.class).isEmpty());
        h.f.jdbc().execute("DROP TABLE care_plan_notification,care_plan_evidence,care_plan_command,care_plan_event,care_plan_action,care_plan_revision,care_nurse_assignment CASCADE");
        perform(get("/api/care-plans/capabilities"),OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        for(long actor:new long[]{OWNER,ADMIN}) {
            perform(post("/api/care-plans").contentType("application/json").content("["),actor).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
            perform(get("/api/care-plans/bad").param("actorId","900"),actor).andExpect(status().isNotFound());
            perform(post("/api/care-nurse-assignments").contentType("application/json").content("["),actor).andExpect(status().isNotFound());
        }
        verifyNoInteractions(transport);
    }
    @Test void httpAndEnvelopeCodesAgree() throws Exception {
        Map<String,Object> d=create();long p=id(d),r=num(d.get("draftRevisionId"));
        perform(get("/api/care-plans/"+p),ADMIN).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403)).andExpect(jsonPath("$.data.errorCode").value("ACCESS_DENIED"));
        perform(command("/api/care-plans/"+p+"/revisions/"+r+"/publish",map(),0),DOCTOR).andExpect(status().isOk());
        perform(command("/api/care-plans/"+p+"/cancel",map("reason","Synthetic cancellation"),0),DOCTOR)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409)).andExpect(jsonPath("$.data.errorCode").value("VERSION_CONFLICT"));
    }
    @Test void forgedActorRejected() throws Exception {
        for(String field:new String[]{"actorId","actorName","actorRole","status","action"}) {Map<String,Object>b=body(1);b.put(field,OWNER);
            perform(command("/api/care-plans",b,0),DOCTOR).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));}
        assertEquals(0,h.f.jdbc().queryForObject("SELECT COUNT(*) FROM doctor_care_plan WHERE workflow_version=1",Integer.class));
    }
    @Test void serverActorAndAggregateVersionsSurviveTheFullApiWorkflow() throws Exception {
        Map<String,Object>d=create();long p=id(d),r=num(d.get("draftRevisionId"));
        perform(command("/api/care-plans/"+p+"/revisions/"+r+"/save",body(1),0),DOCTOR).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        perform(command("/api/care-plans/"+p+"/revisions/"+r+"/publish",map("currentRevisionId",null,"supersededActionDigest",null),1),DOCTOR).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        long a=h.f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,p);
        perform(command("/api/care-plans/actions/"+a+"/receipts",map("note","Synthetic completion","occurredAt",h.f.now().toString(),"entryMode","SELF","evidence",Collections.emptyList()),2),OWNER)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3)).andExpect(jsonPath("$.data.actionStatus").value("SUBMITTED"));
        assertEquals(OWNER,h.f.jdbc().queryForObject("SELECT actor_id FROM care_plan_event WHERE event_type='RECEIPT_SUBMITTED'",Long.class));
        perform(command("/api/care-plans/actions/"+a+"/follow-ups",map("kind","CONTACTED","note","Synthetic follow up"),3),DOCTOR).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(4));
        perform(command("/api/care-plans/actions/"+a+"/reviews",map("decision","CONFIRM"),4),DOCTOR).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(5));
        perform(command("/api/care-plans/"+p+"/close",map(),5),DOCTOR).andExpect(status().isOk()).andExpect(jsonPath("$.data.lifecycle").value("COMPLETED"));
        verifyNoInteractions(transport);
    }
    @Test void revisionsAssigneesEventsAndHelpUseReviewedFrontendPaths() throws Exception {
        Map<String,Object>d=create();long p=id(d),r=num(d.get("draftRevisionId"));
        perform(get("/api/care-plans/assignees").param("patientId","1"),DOCTOR).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].userId").exists());
        perform(get("/api/care-plans/"+p+"/revisions/"+r),DOCTOR).andExpect(status().isOk()).andExpect(jsonPath("$.data.revisionId").value(r));
        perform(command("/api/care-plans/"+p+"/revisions/"+r+"/publish",map(),0),DOCTOR).andExpect(status().isOk());
        long a=h.f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,p);
        perform(command("/api/care-plans/actions/"+a+"/help",map("note","Synthetic difficulty"),1),OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.actionStatus").value("NEEDS_HELP"));
        perform(command("/api/care-plans/"+p+"/revisions",map(),2),DOCTOR).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3));
        perform(get("/api/care-plans/"+p+"/revisions"),OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        String events=perform(get("/api/care-plans/"+p+"/events"),OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray()).andReturn().getResponse().getContentAsString();
        assertTrue(events.contains("PLAN_PUBLISHED"));assertTrue(events.contains("HELP_REQUESTED"));assertFalse(events.contains("DRAFT_CREATED"));assertFalse(events.contains("REVISION_CREATED"));
        h.grantFamily();perform(get("/api/care-plans/"+p+"/events"),FAMILY).andExpect(status().isOk());
        h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");
        for(String path:new String[]{"/events","/revisions","/revisions/"+r})perform(get("/api/care-plans/"+p+path),FAMILY).andExpect(status().isForbidden());
    }
    @Test void strictPathQueryAndCommandBodyValidation() throws Exception {
        for(String path:new String[]{"0","-1","1.5","bad","9223372036854775808"})perform(get("/api/care-plans/"+path),OWNER).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        for(String patient:new String[]{"0","-1","1.5"," 1","bad","9223372036854775808"})perform(get("/api/care-plans").param("patientId",patient),OWNER).andExpect(status().isBadRequest());
        for(String limit:new String[]{"0","101","1.5","bad"})perform(get("/api/care-plans").param("limit",limit),OWNER).andExpect(status().isBadRequest());
        perform(get("/api/care-plans").param("actorId","7"),OWNER).andExpect(status().isBadRequest());
        perform(get("/api/care-plans").param("limit","1","2"),OWNER).andExpect(status().isBadRequest());
        perform(get("/api/care-plans").param("queue","FORGED"),OWNER).andExpect(status().isBadRequest());
        for(Object version:new Object[]{-1,1.5,"0",null}){Map<String,Object>b=body(1);b.put("commandKey",key());b.put("expectedVersion",version);perform(post("/api/care-plans").contentType("application/json").content(json.writeValueAsString(b)),DOCTOR).andExpect(status().isBadRequest());}
        Map<String,Object>b=body(1);b.put("commandKey","bad");b.put("expectedVersion",0);perform(post("/api/care-plans").contentType("application/json").content(json.writeValueAsString(b)),DOCTOR).andExpect(status().isBadRequest());
        perform(post("/api/care-plans").contentType("application/json").content("[]"),DOCTOR).andExpect(status().isBadRequest());
    }
    @Test void todayCutoffIsExclusiveUtcAndCursorBindsIt() throws Exception {
        Map<String,Object>d=create();long p=id(d),r=num(d.get("draftRevisionId"));perform(command("/api/care-plans/"+p+"/revisions/"+r+"/publish",map(),0),DOCTOR).andExpect(status().isOk());
        perform(get("/api/care-plans").param("queue","TODAY").param("dueBefore","2026-10-03T06:00:00Z"),OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        perform(get("/api/care-plans").param("queue","TODAY").param("dueBefore","2026-10-03T06:00:00.000001Z"),OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        for(String cutoff:new String[]{"2026-10-03","2026-10-03T06:00:00","2026-10-03T08:00:00+02:00","2026-10-03T06:00:00.000000001Z"})perform(get("/api/care-plans").param("queue","TODAY").param("dueBefore",cutoff),OWNER).andExpect(status().isBadRequest());
        perform(get("/api/care-plans").param("queue","HISTORY").param("dueBefore","2026-10-04T00:00:00Z"),OWNER).andExpect(status().isBadRequest());
        Map<String,Object>second=create();perform(command("/api/care-plans/"+id(second)+"/revisions/"+second.get("draftRevisionId")+"/publish",map(),0),DOCTOR).andExpect(status().isOk());
        Map<?,?> page=(Map<?,?>)read(perform(get("/api/care-plans").param("limit","1").param("dueBefore","2026-10-04T00:00:00Z"),OWNER)).get("data");String cursor=(String)page.get("nextCursor");assertNotNull(cursor);
        perform(get("/api/care-plans").param("limit","1").param("dueBefore","2026-10-04T01:00:00Z").param("cursor",cursor),OWNER).andExpect(status().isBadRequest());
    }
    @Test void nurseAssignmentInterceptorUsesStableLocalizedErrorAndLegacyIsUntouched() throws Exception {
        perform(post("/api/care-nurse-assignments").contentType("application/json").content("{}"),OWNER).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403)).andExpect(jsonPath("$.data.errorCode").value("ACCESS_DENIED")).andExpect(jsonPath("$.msg").value(CarePlanException.denied().getMessage()));
        for(String value:new String[]{"0","-1","1.5","bad"})perform(get("/api/care-nurse-assignments").param("patientId",value),OWNER).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("INVALID_REQUEST"));
        perform(get("/api/care-nurse-assignments").param("patientId","1","2"),OWNER).andExpect(status().isBadRequest());
        perform(get("/api/care-nurse-assignments").param("patientId","1").param("actorId","7"),OWNER).andExpect(status().isBadRequest());
        perform(post("/api/care-nurse-assignments/0/revoke"),ADMIN).andExpect(status().isBadRequest());
        PermissionInterceptor interceptor=context.getBean(PermissionInterceptor.class);org.springframework.mock.web.MockHttpServletRequest req=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/user");req.setAttribute("userId",OWNER);req.setAttribute("roleCodes",Collections.singletonList("patient"));org.springframework.mock.web.MockHttpServletResponse res=new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req,res,new Object()));assertTrue(res.getContentAsString().contains("\"data\":null"));
    }
    @Test void deliveryAdministrationIsGenericAndRetryRequiresAnExactBoolean() throws Exception {
        h.f.jdbc().update("INSERT INTO notification_channel(id,user_id,channel_type,channel_name,webhook_url,enabled) VALUES(100,7,'WEBHOOK','Synthetic API channel','https://example.test/synthetic',1)");
        Map<String,Object>d=create();long p=id(d),r=num(d.get("draftRevisionId"));perform(command("/api/care-plans/"+p+"/revisions/"+r+"/publish",map(),0),DOCTOR).andExpect(status().isOk());
        String out=perform(get("/api/care-plans/notifications"),ADMIN).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for(String sensitive:new String[]{"patientId","planId","actorId","recipientUserId","channelId","payload","Synthetic plan"})assertFalse(out.contains(sensitive));
        perform(get("/api/care-plans/notifications"),OWNER).andExpect(status().isForbidden()).andExpect(jsonPath("$.data.errorCode").value("ACCESS_DENIED"));
        long n=h.f.jdbc().queryForObject("SELECT MIN(id) FROM care_plan_notification",Long.class);
        perform(post("/api/care-plans/notifications/"+n+"/retry").contentType("application/json").content("{\"duplicateRiskAcknowledged\":false}"),ADMIN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        for(String b:new String[]{"{}","{\"duplicateRiskAcknowledged\":\"true\"}","{\"duplicateRiskAcknowledged\":true,\"actorId\":11}"})perform(post("/api/care-plans/notifications/"+n+"/retry").contentType("application/json").content(b),ADMIN).andExpect(status().isBadRequest());
        h.f.jdbc().update("UPDATE care_plan_notification SET status='UNKNOWN' WHERE id=?",n);
        perform(post("/api/care-plans/notifications/"+n+"/retry").contentType("application/json").content("{\"duplicateRiskAcknowledged\":false}"),ADMIN).andExpect(status().isBadRequest());
        String retried=perform(post("/api/care-plans/notifications/"+n+"/retry").contentType("application/json").content("{\"duplicateRiskAcknowledged\":true}"),ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn().getResponse().getContentAsString();
        for(String sensitive:new String[]{"patientId","planId","actorId","recipientUserId","channelId","payload","Synthetic plan"})assertFalse(retried.contains(sensitive));
        assertEquals(1,h.f.jdbc().queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE action_type='RETRY'",Integer.class));
        verifyNoInteractions(transport);
    }
}
