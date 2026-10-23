package org.familyhealthcare.service.alertworkflow;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.http.MediaType;

class AlertWorkflowSourceCorrectionTest {
    MeasurementWorkflowBridgeTest nativeApi; AlertWorkflowHarness h; MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        nativeApi=new MeasurementWorkflowBridgeTest();nativeApi.setup();h=nativeApi.h;
        org.familyhealthcare.controller.CareJourneyController c=new org.familyhealthcare.controller.CareJourneyController();ReflectionTestUtils.setField(c,"service",nativeApi.journey);
        mvc=MockMvcBuilders.standaloneSetup(c).setControllerAdvice(new org.familyhealthcare.controller.MeasurementWorkflowExceptionAdvice()).build();
    }
    Map<String,Object> correction(long version){Map<String,Object>b=nativeApi.body("5");b.put("expectedVersion",version);b.put("reason","Corrected transcription");return b;}
    Map<String,Object> correct(long source,Map<String,Object> body) {return h.db.tx.execute(tx->{try {
        org.springframework.mock.web.MockHttpServletResponse r=mvc.perform(put("/care-journey/measurements/"+source).requestAttr("userId",1L).contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn().getResponse();
        assertEquals(200,r.getStatus(),r.getContentAsString());return object(r.getContentAsString());
    }catch(Exception e){throw new IllegalStateException(e);}});}
    @Test void correctingClosedNativeJourneyReopensExactlyOnceWithFreshReviewQualifications() {
        Map<String,Object>created=nativeApi.save(nativeApi.body("18"));long source=id(created.get("sourceId"));long alert=id(((Map<?,?>)((List<?>)created.get("followUps")).get(0)).get("alertId"));
        h.assign(alert,1);h.accept(alert,1);h.submit(alert,1);h.feedback(alert,1,"SELF");h.command(1,alert,"CLOSE",map("reason","Personal handling recorded"));long oldEvents=h.count("alert_event");
        Map<String,Object>b=correction(0),first=correct(source,b),replay=correct(source,b);
        assertEquals(first.get("data"),replay.get("data"));assertEquals(2,h.count("alert_source_revision"));assertEquals(2,id(h.row(alert).get("cycleNo")));assertEquals("OPEN",h.row(alert).get("workflowState"));assertEquals(true,h.row(alert).get("sourceReviewRequired"));
        assertNull(h.row(alert).get("acceptedBy"));assertNull(h.row(alert).get("currentHandlingEventId"));assertNull(h.row(alert).get("currentFeedbackEventId"));assertEquals(oldEvents+1,h.count("alert_event"));
        h.denied(409,()->h.command(1,alert,"CLOSE",map("reason","Cannot reuse the old handling")));
    }
    @Test void correctingTrackedNormalToAbnormalCreatesItsFirstFollowUp() {
        long source=id(nativeApi.save(nativeApi.body("5")).get("sourceId"));Map<String,Object>b=correction(0);b.put("valuePrimary","18");correct(source,b);
        assertEquals(1,h.count("alert_record"));assertEquals(2,h.count("alert_source_revision"));assertEquals(1,h.count("alert_source_link"));
    }

    @Test void selfMonitorCombinedCorrectionRetainsBothIdentitiesIncludingRemovedMetric() {
        Map<String,Object>created=(Map<String,Object>)nativeApi.selfPost(nativeApi.selfBody()).get("data");long source=id(created.get("sourceId"));
        Map<String,Object>b=nativeApi.selfBody();b.put("id",source);b.put("expectedVersion",0);b.put("reason","Corrected imported transcription");b.put("measureType","BG");b.put("systolicBp",null);b.put("diastolicBp",null);b.put("bloodGlucose","5");
        Map<String,Object>a=h.db.tx.execute(tx->nativeApi.self.updateRequest(b));Map<String,Object>replay=h.db.tx.execute(tx->nativeApi.self.updateRequest(b));
        assertEquals(a,replay);assertEquals(2,h.count("alert_record"));assertEquals(2,h.count("alert_source_revision"));assertEquals(4,h.count("alert_source_link"));
        List<Map<String,Object>>rows=h.db.jdbc.queryForList("SELECT workflow_state,source_review_required,classification_json FROM alert_record ORDER BY id");
        for(Map<String,Object>row:rows){assertEquals("OPEN",row.get("WORKFLOW_STATE"));assertEquals(1,((Number)row.get("SOURCE_REVIEW_REQUIRED")).intValue());}
        assertTrue(rows.get(0).get("CLASSIFICATION_JSON").toString().contains("UNCLASSIFIABLE"));
    }
    @Test void revokedAuthorCannotReplayAMeasurementCorrection() {
        long source=id(nativeApi.save(nativeApi.body("18")).get("sourceId"));Map<String,Object>b=correction(0);correct(source,b);
        long revisions=h.count("alert_source_revision"),commands=h.count("measurement_command");h.db.jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");
        h.denied(403,()->org.familyhealthcare.util.CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->nativeApi.journey.updateMeasurement(source,b))));
        assertEquals(revisions,h.count("alert_source_revision"));assertEquals(commands,h.count("measurement_command"));
    }
    @Test void staleSourceVersionCannotOverwriteANewerCorrection() {
        long source=id(nativeApi.save(nativeApi.body("18")).get("sourceId"));correct(source,correction(0));Map<String,Object>b=correction(0);
        h.denied(409,()->org.familyhealthcare.util.CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->nativeApi.journey.updateMeasurement(source,b))));assertEquals(2,h.count("alert_source_revision"));
    }
    @Test void disabledTrackedNormalSourceCannotBeEditedReassignedOrDeleted() {
        Map<String,Object>b=nativeApi.selfBody();b.put("systolicBp",120);b.put("diastolicBp",80);b.put("bloodGlucose","5");Map<String,Object>created=(Map<String,Object>)nativeApi.selfPost(b).get("data");long source=id(created.get("sourceId"));
        assertEquals(0,h.count("alert_record"));ReflectionTestUtils.setField(h.p,"enabled",false);
        org.familyhealthcare.entity.BpSelfMonitorRecord patch=new org.familyhealthcare.entity.BpSelfMonitorRecord();patch.setId(source);patch.setPatientId(20L);patch.setSystolicBp(190);
        h.denied(409,()->h.db.tx.execute(tx->nativeApi.self.updateOwned(patch)));h.denied(409,()->h.db.tx.execute(tx->nativeApi.self.deleteOwned(source)));
        assertEquals(10,h.db.jdbc.queryForObject("SELECT patient_id FROM bp_self_monitor_record WHERE id=?",Integer.class,source));assertEquals(120,h.db.jdbc.queryForObject("SELECT systolic_bp FROM bp_self_monitor_record WHERE id=?",Integer.class,source));
    }

    @Test void inheritedSourceUpdateAndDeleteCannotBypassTrackedCorrectionsWhileDisabled() {
        long source=id(((Map<?,?>)nativeApi.selfPost(nativeApi.selfBody()).get("data")).get("sourceId"));ReflectionTestUtils.setField(h.p,"enabled",false);
        org.familyhealthcare.entity.BpSelfMonitorRecord patch=new org.familyhealthcare.entity.BpSelfMonitorRecord();patch.setId(source);patch.setSystolicBp(90);
        h.denied(409,()->h.db.tx.execute(tx->nativeApi.self.updateById(patch)));h.denied(409,()->h.db.tx.execute(tx->nativeApi.self.removeById(source)));
    }
    @Test void disabledLegacyUpdateKeepsItsExistingResponseAndPatchRules() throws Exception {
        ReflectionTestUtils.setField(h.p,"enabled",false);Map<String,Object>b=nativeApi.selfBody();b.remove("commandKey");b.remove("occurredAt");long source=id(((Map<?,?>)nativeApi.selfPost(b).get("data")).get("id"));
        h.db.tx.execute(tx->{try {String response=nativeApi.mvc.perform(put("/bp-self-monitor/update").locale(java.util.Locale.ENGLISH).requestAttr("userId",1L).contentType(MediaType.APPLICATION_JSON).content(json(map("id",source,"systolicBp",160)))).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertEquals("Updated successfully",object(response).get("data"));return null;}catch(Exception e){throw new IllegalStateException(e);}});
        assertEquals(125,h.db.jdbc.queryForObject("SELECT diastolic_bp FROM bp_self_monitor_record WHERE id=?",Integer.class,source));
    }

    @Test void normalSourceCorrectionKeepsTheRealReasonWithoutInventingAnAlert() {
        long source=id(nativeApi.save(nativeApi.body("5")).get("sourceId"));correct(source,correction(0));Map<String,Object>current=h.repo.readSource(1,10,"HEALTH_MEASUREMENT",source);
        assertEquals(0,h.count("alert_record"));assertEquals("Corrected transcription",h.repo.sourceRevision(id(current.get("sourceIdentityId")),2).get("correctionReason"));
    }

    @Test void correctionPreservesAnUnknownLegacySourceKindWithoutInventingText() {
        h.db.jdbc.update("UPDATE health_measurement SET source_type=NULL WHERE id=101");correct(101,correction(0));
        assertNull(h.db.jdbc.queryForObject("SELECT source_type FROM health_measurement WHERE id=101",String.class));
    }

}
