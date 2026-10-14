package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.util.CurrentUserUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Final cross-boundary regressions use actual native adapters and synthetic relational data. */
class AlertWorkflowFinalBoundaryTest {
    MeasurementWorkflowBridgeTest t;
    @BeforeEach void setup() throws Exception {t=new MeasurementWorkflowBridgeTest();t.setup();}
    Map<String,Object> create(String type) {
        Map<String,Object> b="JOURNEY".equals(type)?t.body("18"):t.selfBody();
        b.put("remark","Fictional original note");
        if("JOURNEY".equals(type))b.put("metricName","Fictional metric context");
        else {b.put("sourceType","DEVICE");b.put("sourceExternalId","fictional-device-row");}
        return b;
    }
    Map<String,Object> save(String type,Map<String,Object>b){return "JOURNEY".equals(type)?t.save(b):t.h.db.tx.execute(tx->t.self.saveRequest(b));}
    Map<String,Object> correction(String type,long source) {
        Map<String,Object>b="JOURNEY".equals(type)?t.body("17"):t.selfBody();
        b.put("expectedVersion",0);b.put("reason","Fictional reasoned correction");if(!"JOURNEY".equals(type))b.put("id",source);return b;
    }
    Map<String,Object> correct(String type,long source,Map<String,Object>b){return "JOURNEY".equals(type)?CurrentUserUtil.runAsUser(1L,()->t.h.db.tx.execute(tx->t.journey.updateMeasurement(source,b))):t.h.db.tx.execute(tx->t.self.updateRequest(b));}
    @ParameterizedTest @CsvSource({"JOURNEY","SELF"})
    void omittedMetadataSurvivesCorrectionAndReplayWithoutBroadHistoryCopy(String type) {
        long source=id(save(type,create(type)).get("sourceId"));Map<String,Object>b=correction(type,source);Map<String,Object>first=correct(type,source,b);assertEquals(first,correct(type,source,b));
        String table="JOURNEY".equals(type)?"health_measurement":"bp_self_monitor_record";
        assertEquals("Fictional original note",t.h.db.jdbc.queryForObject("SELECT remark FROM "+table+" WHERE id=?",String.class,source));
        if("JOURNEY".equals(type))assertEquals("Fictional metric context",t.h.db.jdbc.queryForObject("SELECT metric_name FROM health_measurement WHERE id=?",String.class,source));
        else {assertEquals("DEVICE",t.h.db.jdbc.queryForObject("SELECT source_type FROM bp_self_monitor_record WHERE id=?",String.class,source));assertEquals("fictional-device-row",t.h.db.jdbc.queryForObject("SELECT source_external_id FROM bp_self_monitor_record WHERE id=?",String.class,source));}
        assertEquals(2,t.h.count("alert_source_revision"));assertEquals(2,t.h.count("measurement_command"));
        assertEquals(0,t.h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_source_revision WHERE snapshot_json LIKE '%Fictional original note%'",Integer.class));
        t.h.db.jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");t.h.denied(403,()->correct(type,source,b));
    }
    @ParameterizedTest @CsvSource({"JOURNEY,metricName","JOURNEY,remark","SELF,sourceType","SELF,sourceExternalId","SELF,remark"})
    void unsupportedMetadataChangesAndExplicitNullClearAreRejectedAtomically(String type,String field) {
        long source=id(save(type,create(type)).get("sourceId"));long events=t.h.count("alert_event"),notifications=t.h.count("alert_notification");
        for(Object value:Arrays.asList("sourceType".equals(field)?"MANUAL":"fictional-replacement",null)) {
            Map<String,Object>b=correction(type,source);b.put(field,value);t.h.denied(400,()->correct(type,source,b));
            assertEquals(1,t.h.count("alert_source_revision"));assertEquals(1,t.h.count("measurement_command"));assertEquals(events,t.h.count("alert_event"));assertEquals(notifications,t.h.count("alert_notification"));
        }
        Map<String,Object>b=correction(type,source);b.put(field,create(type).get(field));assertDoesNotThrow(()->correct(type,source,b));
    }
    @ParameterizedTest @CsvSource({"JOURNEY","SELF"})
    void paddedOffsetIsConsistentOnCreateAndCorrectionAndKeepsNativeWallTime(String type) {
        Map<String,Object>b=create(type);b.put("occurredAt"," 2026-10-13T11:59:00+08:00 ");long source=id(save(type,b).get("sourceId"));
        Map<String,Object>change=correction(type,source);change.put("occurredAt"," 2026-10-13T11:59:00+08:00 ");correct(type,source,change);
        assertEquals(2,t.h.count("alert_source_revision"));assertTrue(t.h.repo.sourceRevisions(1,0,100).stream().allMatch(r->"2026-10-13T03:59:00Z".equals(r.get("occurredAt"))));
        String nativeTime="JOURNEY".equals(type)?(String)t.h.repo.readSource(1,10,"HEALTH_MEASUREMENT",source).get("measuredAt"):(String)t.h.repo.readSource(1,10,"BP_SELF_MONITOR",source).get("recordTime");assertTrue(nativeTime.contains("11:59"));
    }
    @ParameterizedTest @CsvSource({"JOURNEY","SELF"})
    void metadataAndQualificationRollBackAfterNativeUpdateWhenOutboxFails(String type) {
        long source=id(save(type,create(type)).get("sourceId"));String table="JOURNEY".equals(type)?"health_measurement":"bp_self_monitor_record";Map<String,Object>before=t.h.db.jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",source);
        t.h.db.jdbc.execute("DROP TABLE alert_notification");assertThrows(org.springframework.dao.DataAccessException.class,()->correct(type,source,correction(type,source)));
        assertEquals(before,t.h.db.jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",source));assertEquals(1,t.h.count("alert_source_revision"));assertEquals(1,t.h.count("measurement_command"));
    }
    @ParameterizedTest @CsvSource({"JOURNEY","SELF"})
    void explicitNullOnAlreadyNullMetadataIsAcceptedButForgedWorkflowIdentityIsNot(String type) {
        Map<String,Object>b="JOURNEY".equals(type)?t.body("18"):t.selfBody();long source=id(save(type,b).get("sourceId"));Map<String,Object>change=correction(type,source);change.put("remark",null);assertDoesNotThrow(()->correct(type,source,change));
        for(String key:Arrays.asList("sourceIdentityId","sourceRevision","action_versions_json","recordedBy")){Map<String,Object>forged=correction(type,source);forged.put("expectedVersion",1);forged.put(key,1);t.h.denied(400,()->correct(type,source,forged));}
        assertEquals(2,t.h.count("alert_source_revision"));
    }

}
