package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.controller.*;
import org.familyhealthcare.config.SensitiveResponseFilter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class MeasurementWorkflowSourceApiTest {
    MeasurementWorkflowBridgeTest nativeApi;AlertWorkflowHarness h;MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        nativeApi=new MeasurementWorkflowBridgeTest();nativeApi.setup();h=nativeApi.h;
        DefaultListableBeanFactory beans=new DefaultListableBeanFactory();beans.registerSingleton("bridge",new MeasurementWorkflowBridge(h.db.jdbc,h.p,h.auth,h.repo,h.service,new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p)));
        mvc=MockMvcBuilders.standaloneSetup(new MeasurementWorkflowSourceController(h.p,beans.getBeanProvider(MeasurementWorkflowBridge.class))).setControllerAdvice(new AlertWorkflowExceptionAdvice()).addFilters(new SensitiveResponseFilter()).build();
    }
    org.springframework.mock.web.MockHttpServletResponse perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return h.db.tx.execute(tx->{try{return mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();}catch(Exception e){throw new IllegalStateException(e);}});
    }
    @Test void noAlertSourceReadDistinguishesNormalAndUnclassifiableWithoutLeakingAnotherPatient() throws Exception {
        long normal=id(nativeApi.save(nativeApi.body("5")).get("sourceId"));Map<String,Object>b=nativeApi.selfBody();b.put("measureType","BG");b.remove("systolicBp");b.remove("diastolicBp");b.remove("measurePeriod");long unknown=id(((Map<?,?>)nativeApi.selfPost(b).get("data")).get("sourceId"));
        for(String[] item:new String[][]{{"HEALTH_MEASUREMENT",Long.toString(normal),"NORMAL"},{"BP_SELF_MONITOR",Long.toString(unknown),"UNCLASSIFIABLE"}}) {
            org.springframework.mock.web.MockHttpServletResponse r=perform(get("/alert-workflows/sources/"+item[0]+"/"+item[1]).requestAttr("userId",1L).param("patientId","10"));assertEquals(200,r.getStatus());assertEquals("no-store, private",r.getHeader("Cache-Control"));
            Map<?,?>data=(Map<?,?>)object(r.getContentAsString()).get("data");assertEquals(0L,data.get("sourceVersion"));assertEquals(item[2],((Map<?,?>)((List<?>)data.get("assessments")).get(0)).get("status"));
        }
        assertEquals(403,perform(get("/alert-workflows/sources/HEALTH_MEASUREMENT/"+normal).requestAttr("userId",6L).param("patientId","10")).getStatus());
        assertEquals(403,perform(get("/alert-workflows/sources/HEALTH_MEASUREMENT/"+normal).requestAttr("userId",5L).param("patientId","10")).getStatus());
        long adopted=id(h.db.tx.execute(tx->h.service.adopt(1,"BP_SELF_MONITOR",unknown,"GLUCOSE",map("patientId",10,"manualFollowUp",true,"reason","Confirm missing context"),UUID.randomUUID().toString())).get("alertId"));assertEquals("UNCLASSIFIABLE",((Map<?,?>)h.row(adopted).get("classification")).get("status"));
    }
    @Test void invalidationIsNonDestructiveAuditedAndIdempotentAndPreservesTerminalHistory() throws Exception {
        Map<String,Object>created=nativeApi.save(nativeApi.body("18"));long source=id(created.get("sourceId")),alert=id(((Map<?,?>)((List<?>)created.get("followUps")).get(0)).get("alertId"));h.command(1,alert,"CANCEL",map("reason","No longer planned"));
        String body=json(map("patientId",10,"commandKey",UUID.randomUUID().toString(),"expectedVersion",0,"reason","Wrong-device reading"));
        String path="/alert-workflows/sources/HEALTH_MEASUREMENT/"+source+"/invalidate";
        Map<String,Object>first=object(perform(post(path).requestAttr("userId",1L).content(body)).getContentAsString());
        Map<String,Object>again=object(perform(post(path).requestAttr("userId",1L).content(body)).getContentAsString());assertEquals(first.get("data"),again.get("data"));
        assertEquals("INVALIDATED",h.repo.readSource(1,10,"HEALTH_MEASUREMENT",source).get("sourceStatus"));assertEquals(2,h.count("alert_source_revision"));assertEquals(1,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM health_measurement WHERE id=?",Integer.class,source));assertEquals("OPEN",h.row(alert).get("workflowState"));assertEquals(1,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE alert_id=? AND event_type='CANCEL'",Integer.class,alert));
    }
    @Test void sourceReadRejectsUnknownRepeatedQueriesAndRevokedAccess() {
        long source=id(nativeApi.save(nativeApi.body("5")).get("sourceId"));String path="/alert-workflows/sources/HEALTH_MEASUREMENT/"+source;
        assertEquals(400,perform(get(path).requestAttr("userId",1L).param("patientId","10").param("extra","x")).getStatus());assertEquals(400,perform(get(path).requestAttr("userId",1L).param("patientId","10","20")).getStatus());
        h.db.jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=2");assertEquals(403,perform(get(path).requestAttr("userId",2L).param("patientId","10")).getStatus());
        org.springframework.test.util.ReflectionTestUtils.setField(h.p,"enabled",false);assertEquals(404,perform(get(path).requestAttr("userId",1L).param("patientId","10")).getStatus());
    }
    @Test void zeroAlertInvalidationAndReactivationKeepReasonAndStatusInAuthorizedPagedHistory() throws Exception {
        long source=id(nativeApi.save(nativeApi.body("5")).get("sourceId"));String path="/alert-workflows/sources/HEALTH_MEASUREMENT/"+source;
        assertEquals(200,perform(post(path+"/invalidate").requestAttr("userId",1L).content(json(map("patientId",10,"commandKey",UUID.randomUUID().toString(),"expectedVersion",0,"reason","Wrong-device association")))).getStatus());
        Map<String,Object>b=nativeApi.body("5");b.put("expectedVersion",1);b.put("reason","Device association corrected");org.familyhealthcare.util.CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->nativeApi.journey.updateMeasurement(source,b)));
        assertEquals(0,h.count("alert_record"));assertEquals(0,h.count("alert_event"));
        org.springframework.mock.web.MockHttpServletResponse response=perform(get(path+"/revisions").requestAttr("userId",2L).param("patientId","10").param("limit","2"));assertEquals("no-store, private",response.getHeader("Cache-Control"));Map<?,?>page=(Map<?,?>)object(response.getContentAsString()).get("data");List<?>items=(List<?>)page.get("items");
        assertEquals(2,items.size());assertNull(((Map<?,?>)items.get(0)).get("correctionReason"));assertEquals("ACTIVE",((Map<?,?>)items.get(0)).get("sourceStatus"));assertEquals("INVALIDATED",((Map<?,?>)items.get(1)).get("sourceStatus"));assertEquals("Wrong-device association",((Map<?,?>)items.get(1)).get("correctionReason"));assertEquals(2L,page.get("nextAfterRevision"));
        Map<?,?>last=(Map<?,?>)object(perform(get(path+"/revisions").requestAttr("userId",2L).param("patientId","10").param("afterRevision","2")).getContentAsString()).get("data");assertEquals("Device association corrected",((Map<?,?>)((List<?>)last.get("items")).get(0)).get("correctionReason"));assertEquals("ACTIVE",((Map<?,?>)((List<?>)last.get("items")).get(0)).get("sourceStatus"));
        h.db.jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=2");assertEquals(403,perform(get(path+"/revisions").requestAttr("userId",2L).param("patientId","10").param("afterRevision","2")).getStatus());assertEquals(400,perform(get(path+"/revisions").requestAttr("userId",1L).param("patientId","10").param("limit","101")).getStatus());
    }

}
