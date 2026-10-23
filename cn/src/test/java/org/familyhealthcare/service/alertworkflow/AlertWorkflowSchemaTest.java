package org.familyhealthcare.service.alertworkflow;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertWorkflowSchemaTest {
    @Test void rootAndChineseSourceDeduplicationAndCommandRelationshipsAreIdentical() throws Exception {
        String root=AlertWorkflowTestDatabase.read("src/main/resources/sql/init.sql");
        String cn=AlertWorkflowTestDatabase.read(java.nio.file.Files.exists(java.nio.file.Paths.get("cn/pom.xml"))?"cn/src/main/resources/sql/init.sql":"../src/main/resources/sql/init.sql");
        for(String table:Arrays.asList("alert_record","alert_event","alert_source_identity","alert_source_revision","alert_source_link","alert_evidence","alert_command","measurement_command","alert_notification"))
            assertEquals(AlertWorkflowTestDatabase.h2(AlertWorkflowTestDatabase.tableSql(root,table)),AlertWorkflowTestDatabase.h2(AlertWorkflowTestDatabase.tableSql(cn,table)),table);
    }
    @Test void sameValuedNewReadingRetainsItsOwnIdentityAndRevisionMetricLinkIsUnique() throws Exception {
        AlertWorkflowTestDatabase db=database();
        db.jdbc.update("INSERT INTO alert_source_identity(id,source_type,source_id,patient_id,current_revision,status,lock_version,created_at,updated_at) VALUES(1,'HEALTH_MEASUREMENT',101,10,1,'ACTIVE',0,?,?),(2,'HEALTH_MEASUREMENT',102,10,1,'ACTIVE',0,?,?)",AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW);
        db.jdbc.update("INSERT INTO alert_source_revision(source_identity_id,revision_no,recorded_by,occurred_at,recorded_at,snapshot_json) VALUES(1,1,1,?,?,'{\"valuePrimary\":8}'),(2,1,1,?,?,'{\"valuePrimary\":8}')",AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW);
        db.jdbc.update("INSERT INTO alert_source_link(source_identity_id,source_type,source_id,source_revision,metric_key,alert_id) VALUES(1,'HEALTH_MEASUREMENT',101,1,'GLUCOSE',1)");
        assertThrows(DataIntegrityViolationException.class,()->db.jdbc.update("INSERT INTO alert_source_link(source_identity_id,source_type,source_id,source_revision,metric_key,alert_id) VALUES(1,'HEALTH_MEASUREMENT',101,1,'GLUCOSE',2)"));
        db.jdbc.update("INSERT INTO alert_source_link(source_identity_id,source_type,source_id,source_revision,metric_key,alert_id) VALUES(2,'HEALTH_MEASUREMENT',102,1,'GLUCOSE',2)");
        assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_source_link",Integer.class));
    }
    @Test void evidenceAndOutboxReferenceAlertEventsWithoutWeakeningCarePlanForeignKeys() throws Exception {
        AlertWorkflowTestDatabase db=database();
        db.jdbc.update("UPDATE alert_record SET workflow_version=1,alert_type='MEASUREMENT_FOLLOW_UP',workflow_state='OPEN',workflow_mode='PERSONAL',cycle_no=1,source_type='HEALTH_MEASUREMENT',source_id=101,source_revision=1,metric_key='GLUCOSE',classification_json='{}' WHERE id=1");
        db.jdbc.update("INSERT INTO alert_event(id,alert_id,patient_id,to_status,event_type,cycle_no,actor_id,actor_role,actor_relation,recorded_at,payload_json) VALUES(7,1,10,'OPEN','CREATED',1,1,'PATIENT','OWNER',?,'{}')",AlertWorkflowTestDatabase.NOW);
        db.jdbc.update("INSERT INTO alert_evidence(event_id,source_type,source_id,source_revision,dependency_state) VALUES(7,'HEALTH_MEASUREMENT',101,1,'VALID')");
        assertThrows(DataIntegrityViolationException.class,()->db.jdbc.update("INSERT INTO alert_evidence(event_id,source_type,source_id,source_revision,dependency_state) VALUES(99,'HEALTH_MEASUREMENT',101,1,'VALID')"));
        db.jdbc.update("INSERT INTO alert_notification(event_id,patient_id,recipient_user_id,dispatch_key,status,created_at,updated_at) VALUES(7,10,1,'7:1:NONE','NO_CHANNEL',?,?)",AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW);
        assertThrows(DataIntegrityViolationException.class,()->db.jdbc.update("INSERT INTO alert_notification(event_id,patient_id,recipient_user_id,dispatch_key,status,created_at,updated_at) VALUES(7,10,1,'7:1:NONE','NO_CHANNEL',?,?)",AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW));
        assertThrows(DataIntegrityViolationException.class,()->db.jdbc.update("INSERT INTO alert_command(actor_id,command_key,alert_id,expected_version,payload_hash,created_at) VALUES(1,'12345678-1234-1234-1234-123456789abc',99,0,REPEAT('a',64),?)",AlertWorkflowTestDatabase.NOW));
        assertEquals("alert_event",db.jdbc.queryForObject("SELECT PKTABLE_NAME FROM INFORMATION_SCHEMA.CROSS_REFERENCES WHERE FKTABLE_NAME='ALERT_NOTIFICATION' AND FKCOLUMN_NAME='EVENT_ID'",String.class).toLowerCase());
        // The relational proof above is alert-owned; separately compare the untouched care-plan DDL to the baseline in review.
    }
    @Test void repositoryRequiresEffectiveTransactionAndPreservesCamelCaseSnapshots() throws Exception {
        AlertWorkflowTestDatabase db=database();db.sourceTables();
        db.jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(101,10,1,'GLUCOSE',8,'mmol/L',?)",AlertWorkflowTestDatabase.NOW);
        AlertWorkflowProperties props=new AlertWorkflowProperties(true,java.time.Clock.fixed(AlertWorkflowTestDatabase.NOW,java.time.ZoneOffset.UTC));
        AlertWorkflowAuthorizationService auth=new AlertWorkflowAuthorizationService(db.jdbc,props,new org.familyhealthcare.service.careplan.CarePlanProperties());
        AlertWorkflowRepository repo=new AlertWorkflowRepository(db.jdbc,props,auth);
        assertThrows(IllegalStateException.class,()->repo.lockAlert(1L));
        Map<?,?> source=db.tx.execute(status->(Map<?,?>)repo.lockSource("HEALTH_MEASUREMENT",101L));
        assertEquals(10L,source.get("patientId"));assertEquals("GLUCOSE",source.get("metricType"));assertFalse(source.containsKey("patient_id"));
        db.tx.execute(status->{
            long identity=((Number)repo.createSourceIdentity("HEALTH_MEASUREMENT",101L,10L)).longValue();
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("valuePrimary",new java.math.BigDecimal("8.0"));snapshot.put("unit","mmol/L");
            repo.appendSourceRevision(identity,1L,1L,AlertWorkflowTestDatabase.NOW,snapshot);
            Map<?,?> locked=(Map<?,?>)repo.lockSource("HEALTH_MEASUREMENT",101L);
            assertEquals(identity,locked.get("sourceIdentityId"));assertEquals(1L,locked.get("sourceRevision"));return null;
        });
        AlertWorkflowException denied=assertThrows(AlertWorkflowException.class,()->repo.readAlert(5L,1L));
        AlertWorkflowException missing=assertThrows(AlertWorkflowException.class,()->repo.readAlert(5L,999L));
        assertEquals(denied.getErrorCode(),missing.getErrorCode());
    }
    @Test void storageWritesRollbackTogetherWithSharedAlertAndMeasurementCommands() throws Exception {
        AlertWorkflowTestDatabase db=database();db.sourceTables();
        AlertWorkflowProperties props=new AlertWorkflowProperties(true,java.time.Clock.fixed(AlertWorkflowTestDatabase.NOW,java.time.ZoneOffset.UTC));
        AlertWorkflowAuthorizationService auth=new AlertWorkflowAuthorizationService(db.jdbc,props,new org.familyhealthcare.service.careplan.CarePlanProperties());
        AlertWorkflowRepository repo=new AlertWorkflowRepository(db.jdbc,props,auth);
        for(org.familyhealthcare.service.workflow.WorkflowCommandExecutor.Domain domain:Arrays.asList(org.familyhealthcare.service.workflow.WorkflowCommandExecutor.Domain.ALERT,org.familyhealthcare.service.workflow.WorkflowCommandExecutor.Domain.MEASUREMENT)){
            assertThrows(IllegalArgumentException.class,()->db.tx.execute(status->repo.executeCommand(domain,1L,"12345678-1234-1234-1234-123456789abc","synthetic",1L,0L,(java.util.function.Supplier<Map<String,Object>>)()->{db.jdbc.update("INSERT INTO alert_event(alert_id,patient_id,to_status,note) VALUES(1,10,'PENDING','synthetic')");throw new IllegalArgumentException("synthetic rollback");})));
        }
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_event",Integer.class));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_command",Integer.class));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM measurement_command",Integer.class));
    }
    @Test void strictContractsRejectUnknownKeysFractionalVersionsFutureTimesAndMoreThanFiveRefs() throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();body.put("commandKey","12345678-1234-1234-1234-123456789abc");body.put("expectedVersion",new java.math.BigDecimal("0.5"));
        assertThrows(AlertWorkflowException.class,()->AlertWorkflowContracts.validateCommand(body,Collections.emptySet()));body.put("expectedVersion",0L);body.put("role","admin");assertThrows(AlertWorkflowException.class,()->AlertWorkflowContracts.validateCommand(body,Collections.emptySet()));
        assertThrows(AlertWorkflowException.class,()->AlertWorkflowContracts.requireOccurredAt("2026-10-13T12:00:00.123457Z",AlertWorkflowTestDatabase.NOW));
        List<Map<String,Object>> refs=new ArrayList<>();for(long id=1;id<=6;id++){Map<String,Object> ref=new HashMap<>();ref.put("sourceType","HEALTH_MEASUREMENT");ref.put("sourceId",id);refs.add(ref);}assertThrows(AlertWorkflowException.class,()->AlertWorkflowContracts.validateEvidence(refs));
        Map<String,Object> snapshot=new HashMap<>();snapshot.put("patientName","not allowed");assertThrows(AlertWorkflowException.class,()->AlertWorkflowContracts.validateSourceSnapshot(snapshot));
    }
    @Test void aggregateEventsEvidenceAndRevisionMetadataUseFixedAtomicStorage() throws Exception {
        AlertWorkflowTestDatabase db=database();db.sourceTables();
        db.jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(101,10,1,'GLUCOSE',12,'mmol/L',?)",AlertWorkflowTestDatabase.NOW);
        AlertWorkflowProperties props=new AlertWorkflowProperties(true,java.time.Clock.fixed(AlertWorkflowTestDatabase.NOW,java.time.ZoneOffset.UTC));
        AlertWorkflowAuthorizationService auth=new AlertWorkflowAuthorizationService(db.jdbc,props,new org.familyhealthcare.service.careplan.CarePlanProperties());
        AlertWorkflowRepository repo=new AlertWorkflowRepository(db.jdbc,props,auth);
        db.tx.execute(status->{
            long identity=((Number)repo.createSourceIdentity("HEALTH_MEASUREMENT",101L,10L)).longValue();
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("valuePrimary",new java.math.BigDecimal("12"));snapshot.put("unit","mmol/L");
            repo.appendSourceRevision(identity,1L,1L,AlertWorkflowTestDatabase.NOW,snapshot);
            Map<String,Object> aggregate=new LinkedHashMap<>();aggregate.put("patientId",10L);aggregate.put("ownerUserId",1L);aggregate.put("sourceType","HEALTH_MEASUREMENT");aggregate.put("sourceId",101L);aggregate.put("sourceRevision",1L);aggregate.put("metric","GLUCOSE");aggregate.put("workflowMode","PERSONAL");aggregate.put("classification",Collections.singletonMap("status","ABNORMAL"));aggregate.put("responsibleUserId",1L);
            long alert=((Number)repo.createAlert(aggregate)).longValue();
            assertEquals("MEASUREMENT_FOLLOW_UP",db.jdbc.queryForObject("SELECT alert_type FROM alert_record WHERE id=?",String.class,alert));
            repo.linkSource(identity,"HEALTH_MEASUREMENT",101L,1L,"GLUCOSE",alert);
            Map<String,Object> actor=auth.actorSnapshot(1L,10L);
            long event=((Number)repo.appendEvent(alert,10L,1,"HANDLING_SUBMITTED",actor,"ACCEPTED","WAITING_FEEDBACK","synthetic handling","SELF",AlertWorkflowTestDatabase.NOW,Collections.singletonMap("outcome","contact reported"))).longValue();
            repo.addEvidence(event,"HEALTH_MEASUREMENT",101L,1L,null,"VALID");
            assertEquals(1,((List<?>)repo.evidence(event)).size());
            assertEquals(1,((List<?>)repo.events(alert,0L,10)).size());
            @SuppressWarnings("unchecked") Map<String,Object> locked=(Map<String,Object>)repo.lockAlert(alert);
            locked.put("workflowState","ACCEPTED");locked.put("acceptedBy",1L);locked.put("acceptedAt",AlertWorkflowTestDatabase.NOW.toString());
            repo.saveAlert(locked,0L);
            assertEquals(1L,db.jdbc.queryForObject("SELECT lock_version FROM alert_record WHERE id=?",Long.class,alert));
            assertEquals(AlertWorkflowTestDatabase.NOW.toString(),repo.readAlert(1,alert).get("acceptedAt"));
            assertEquals(10L,repo.readSource(2,10,"HEALTH_MEASUREMENT",101).get("patientId"));
            assertFalse(((String)repo.readSource(2,10,"HEALTH_MEASUREMENT",101).get("measuredAt")).endsWith("Z"));
            assertThrows(AlertWorkflowException.class,()->repo.readSource(2,20,"HEALTH_MEASUREMENT",101));
            AlertWorkflowException stale=assertThrows(AlertWorkflowException.class,()->repo.saveAlert(locked,0L));assertEquals(409,stale.getStatus());
            snapshot.put("valuePrimary",new java.math.BigDecimal("8"));repo.appendSourceRevision(identity,2L,1L,AlertWorkflowTestDatabase.NOW,snapshot,"Corrected fixture reading","ACTIVE");
            repo.advanceSourceRevision(identity,0L,2L,"ACTIVE");
            Map<?,?> original=(Map<?,?>)repo.sourceRevision(identity,1L);assertEquals(new java.math.BigDecimal("12"),new java.math.BigDecimal(((Map<?,?>)original.get("snapshot")).get("valuePrimary").toString()));
            return null;
        });
    }
    @Test void initializedSchemaKeepsLegacyRowsOutsideWorkflowByDefault() throws Exception {
        AlertWorkflowTestDatabase db=new AlertWorkflowTestDatabase();db.fixtures();db.workflowTables();
        db.jdbc.update("INSERT INTO alert_record(id,patient_id,user_id,status,handling_note) VALUES(44,10,1,'RESOLVED','synthetic old note')");
        db.jdbc.update("INSERT INTO alert_event(id,alert_id,patient_id,to_status,note) VALUES(45,44,10,'RESOLVED','synthetic history')");
        assertEquals("RESOLVED",db.jdbc.queryForObject("SELECT status FROM alert_record WHERE id=44",String.class));
        assertEquals("synthetic old note",db.jdbc.queryForObject("SELECT handling_note FROM alert_record WHERE id=44",String.class));
        assertEquals("synthetic history",db.jdbc.queryForObject("SELECT note FROM alert_event WHERE id=45",String.class));
        assertEquals(0,db.jdbc.queryForObject("SELECT workflow_version FROM alert_record WHERE id=44",Integer.class));
        assertNull(db.jdbc.queryForObject("SELECT event_type FROM alert_event WHERE id=45",String.class));
        for(String table:Arrays.asList("alert_source_identity","alert_source_revision","alert_source_link","alert_evidence","alert_command","measurement_command","alert_notification"))
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),table);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SOURCE","METRIC","TYPE","LEGACY"})
    void repositoryRejectsSourceLinksWhoseAggregateOnlySharesThePatient(String mismatch) throws Exception {
        AlertWorkflowTestDatabase db=database();db.sourceTables();
        db.jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(101,10,1,'GLUCOSE',12,'mmol/L',?)",AlertWorkflowTestDatabase.NOW);
        AlertWorkflowProperties props=new AlertWorkflowProperties(true,java.time.Clock.fixed(AlertWorkflowTestDatabase.NOW,java.time.ZoneOffset.UTC));
        AlertWorkflowAuthorizationService auth=new AlertWorkflowAuthorizationService(db.jdbc,props,new org.familyhealthcare.service.careplan.CarePlanProperties());AlertWorkflowRepository repo=new AlertWorkflowRepository(db.jdbc,props,auth);
        db.tx.execute(status->{
            long identity=repo.createSourceIdentity("HEALTH_MEASUREMENT",101,10);repo.appendSourceRevision(identity,1,1,AlertWorkflowTestDatabase.NOW,Collections.singletonMap("valuePrimary",12));
            Map<String,Object> input=new LinkedHashMap<>();input.put("patientId",10L);input.put("ownerUserId",1L);input.put("sourceType","HEALTH_MEASUREMENT");input.put("sourceId",101L);input.put("sourceRevision",1L);input.put("metric","GLUCOSE");input.put("workflowMode","PERSONAL");input.put("classification",Collections.singletonMap("status","ABNORMAL"));long alert=repo.createAlert(input);
            if("SOURCE".equals(mismatch))db.jdbc.update("UPDATE alert_record SET source_id=202 WHERE id=?",alert);
            if("METRIC".equals(mismatch))db.jdbc.update("UPDATE alert_record SET metric_key='BP' WHERE id=?",alert);
            if("TYPE".equals(mismatch))db.jdbc.update("UPDATE alert_record SET source_type='BP_SELF_MONITOR' WHERE id=?",alert);
            long target="LEGACY".equals(mismatch)?1:alert;
            assertThrows(AlertWorkflowException.class,()->repo.linkSource(identity,"HEALTH_MEASUREMENT",101,1,"GLUCOSE",target));
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_source_link",Integer.class));return null;
        });
    }
    private AlertWorkflowTestDatabase database() throws Exception {
        AlertWorkflowTestDatabase db=new AlertWorkflowTestDatabase();db.fixtures();db.workflowTables();
        db.jdbc.update("INSERT INTO alert_record(id,patient_id,user_id) VALUES(1,10,1),(2,10,1)");return db;
    }
    @Test void revisionRepositoryRequiresRealReasonAndIdentityStatusConsistency() throws Exception {
        AlertWorkflowHarness h=new AlertWorkflowHarness();h.adopt();Map<String,Object> source=h.repo.readSource(1,10,"HEALTH_MEASUREMENT",101);long identity=((Number)source.get("sourceIdentityId")).longValue();
        assertThrows(AlertWorkflowException.class,()->h.db.tx.execute(tx->h.repo.appendSourceRevision(identity,2,1,AlertWorkflowTestDatabase.NOW,Collections.singletonMap("valuePrimary",5))));
        assertThrows(AlertWorkflowException.class,()->h.db.tx.execute(tx->{h.repo.appendSourceRevision(identity,2,1,AlertWorkflowTestDatabase.NOW,Collections.singletonMap("valuePrimary",5),"Invalid reading","INVALIDATED");h.repo.advanceSourceRevision(identity,0,2,"ACTIVE");return null;}));assertEquals(1,h.count("alert_source_revision"));
    }

}
