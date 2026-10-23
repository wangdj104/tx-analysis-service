package org.familyhealthcare.service.alertworkflow;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic SQL predicate fixtures only. H2 does not execute native MySQL triggers. */
class AlertWorkflowGuardRegressionTest {
    private AlertWorkflowTestDatabase db;private AlertWorkflowSqlPredicates sql;
    @BeforeEach void setup() throws Exception {
        db=new AlertWorkflowTestDatabase();db.fixtures();db.workflowTables();sql=new AlertWorkflowSqlPredicates();
        db.jdbc.execute("CREATE ALIAS JSON_VALID FOR \"org.familyhealthcare.service.alertworkflow.AlertWorkflowSqlPredicates.jsonValid\"");
        db.jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_TYPE FOR \"org.familyhealthcare.service.alertworkflow.AlertWorkflowSqlPredicates.jsonType\"");
        db.jdbc.update("INSERT INTO alert_record(id,patient_id,user_id) VALUES(1,10,1),(2,10,1),(3,20,6),(44,10,1)");
        db.jdbc.update("UPDATE alert_record SET workflow_version=1,alert_type='MEASUREMENT_FOLLOW_UP',workflow_state='OPEN',workflow_mode='PERSONAL',cycle_no=1,source_type='HEALTH_MEASUREMENT',source_id=101,source_revision=2,metric_key='GLUCOSE',classification_json='{}' WHERE id IN (2,44)");
    }
    @Test void allOwnedGuardsHaveExactEditionParityInTheSingleInitialization() throws Exception {sql.parity();}
    @ParameterizedTest @ValueSource(longs={10,20}) void legacyEventCannotMoveIntoWorkflowParentEvenWithNullTypedMarker(long patient) {
        Map<String,Object> old=event(1,patient),next=event(2,patient);
        assertTrue(sql.rejects(db.jdbc,"aw57_event_bu",old,next));
    }
    @Test void legacyToLegacyEventUpdateRetainsItsPriorBehavior() {assertFalse(sql.rejects(db.jdbc,"aw57_event_bu",event(1,10),event(3,20)));}
    @Test void workflowAggregatePrimaryKeyCannotChangeWithTypedHistoryButNoIncidentalForeignKeyRows() {
        typedEvent(7,2,10);assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_source_link",Integer.class));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_command",Integer.class));
        Map<String,Object> old=aggregate(2,true),next=aggregate(88,true);assertTrue(sql.rejects(db.jdbc,"aw57_alert_bu",old,next));
    }
    @Test void legacyPrimaryKeyChangeIsAllowedUnlessItAlreadyOwnsTypedHistory() {
        assertFalse(sql.rejects(db.jdbc,"aw57_alert_bu",aggregate(1,false),aggregate(88,false)));
        typedEvent(7,1,10);assertTrue(sql.rejects(db.jdbc,"aw57_alert_bu",aggregate(1,false),aggregate(88,false)));
    }
    @Test void invariantFindsUntypedSameAndCrossPatientContaminationOfWorkflowHistory() {
        db.jdbc.update("INSERT INTO alert_event(alert_id,patient_id,to_status) VALUES(2,10,'PENDING'),(2,20,'PENDING'),(1,10,'PENDING')");
        assertEquals(2,sql.diagnostics(db.jdbc,"orphan_alert_events"));
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE","METRIC","TYPE","LEGACY"}) void sourceLinkMustMatchItsWorkflowAggregateRatherThanOnlyItsPatient(String mismatch) {
        identities();Map<String,Object> link=link();
        if("SOURCE".equals(mismatch)){link.put("source_identity_id",2);link.put("source_id",202);}
        if("METRIC".equals(mismatch))link.put("metric_key","BP");
        if("TYPE".equals(mismatch)){link.put("source_identity_id",3);link.put("source_type","BP_SELF_MONITOR");}
        if("LEGACY".equals(mismatch))link.put("alert_id",1);
        assertTrue(sql.rejects(db.jdbc,"aw57_link_bi",Collections.emptyMap(),link));
    }
    @Test void matchingHistoricalSourceLinkDoesNotNeedToEqualNewestAggregateRevision() {
        identities();assertFalse(sql.rejects(db.jdbc,"aw57_link_bi",Collections.emptyMap(),link()));
    }
    @Test void establishedSourceLinkCannotBeDeletedForReinsertionAtAnotherAlert() {
        identities();assertTrue(sql.rejects(db.jdbc,"aw57_link_bd",link(),Collections.emptyMap()));
    }
    @Test void invariantDetectsWrongSourceMetricAndLegacyLinkTargets() {
        identities();
        db.jdbc.update("INSERT INTO alert_source_link(source_identity_id,source_type,source_id,source_revision,metric_key,alert_id) VALUES(1,'HEALTH_MEASUREMENT',101,1,'BP',2),(2,'HEALTH_MEASUREMENT',202,1,'GLUCOSE',2),(3,'BP_SELF_MONITOR',101,1,'GLUCOSE',1)");
        assertEquals(3,sql.diagnostics(db.jdbc,"invalid_source_links"));
    }
    @ParameterizedTest @ValueSource(strings={"patient","alert_record","alert_event"})
    void initializationUsesSignedSingleColumnPrimaryKeysForExistingFkParents(String table) throws Exception {
        String ddl=AlertWorkflowTestDatabase.tableSql(AlertWorkflowTestDatabase.read("src/main/resources/sql/init.sql"),table);
        assertTrue(java.util.regex.Pattern.compile("(?i)\\x60id\\x60 BIGINT (?:NOT NULL )?AUTO_INCREMENT").matcher(ddl).find(),table);
        assertTrue(java.util.regex.Pattern.compile("(?:PRIMARY KEY \\(\\x60id\\x60\\)|\\x60id\\x60 BIGINT (?:NOT NULL )?AUTO_INCREMENT PRIMARY KEY)").matcher(ddl).find(),table);
        assertFalse(ddl.toUpperCase(java.util.Locale.ROOT).contains("BIGINT UNSIGNED"),table);
    }
    @Test void reservedMarkerIsRejectedWithoutReinterpretingLegacyRows() {
        Map<String,Object> row=aggregate(1,false);
        assertFalse(sql.rejects(db.jdbc,"aw57_alert_bi",Collections.emptyMap(),row));
        row.put("alert_type","MEASUREMENT_FOLLOW_UP");
        assertTrue(sql.rejects(db.jdbc,"aw57_alert_bi",Collections.emptyMap(),row));
        assertEquals(0,db.jdbc.queryForObject("SELECT workflow_version FROM alert_record WHERE id=1",Integer.class));
    }
    @Test void invariantDetectsReservedMarkerConflictsWithoutTreatingLegacyRowsAsWorkflows() {
        assertEquals(0,sql.diagnostics(db.jdbc,"reserved_marker_conflicts"));
        db.jdbc.update("UPDATE alert_record SET alert_type='MEASUREMENT_FOLLOW_UP' WHERE id=1");assertEquals(1,sql.diagnostics(db.jdbc,"reserved_marker_conflicts"));
        db.jdbc.update("UPDATE alert_record SET alert_type='LEGACY' WHERE id=2");assertEquals(2,sql.diagnostics(db.jdbc,"reserved_marker_conflicts"));
    }
    @Test void laterImmutableSourceRevisionRequiresItsBoundedRealCorrectionReason() {
        Map<String,Object> row=org.familyhealthcare.service.workflow.WorkflowData.map("revision_no",2,"recorded_by",1,"snapshot_json","{}","action_versions_json","{}","occurred_at","2026-10-13 11:00:00","recorded_at","2026-10-13 12:00:00","correction_reason",null,"source_status","ACTIVE");
        assertTrue(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),row));row.put("correction_reason","  ");assertTrue(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),row));
        row.put("correction_reason",String.join("",Collections.nCopies(1001,"a")));assertTrue(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),row));row.put("correction_reason","Corrected transcription");assertFalse(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),row));
    }
    @Test void sourceActionQualificationRequiresKnownObjectAndRemainsImmutableAndDiagnosed() {
        Map<String,Object> row=org.familyhealthcare.service.workflow.WorkflowData.map("revision_no",2,"recorded_by",1,"snapshot_json","{}","action_versions_json","{\"92\":2}","occurred_at","2026-10-13 11:00:00","recorded_at","2026-10-13 12:00:00","correction_reason","Corrected context","source_status","ACTIVE");
        assertFalse(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),row));
        for(String bad:Arrays.asList(null,"broken","[]","null")){row.put("action_versions_json",bad);assertTrue(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),row));}
        row.put("action_versions_json","{}");assertFalse(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),row));assertTrue(sql.rejects(db.jdbc,"aw57_revision_bu",row,row));assertTrue(sql.rejects(db.jdbc,"aw57_revision_bd",row,Collections.emptyMap()));
        identities();db.jdbc.update("UPDATE alert_source_revision SET action_versions_json='{}'");assertEquals(0,sql.diagnostics(db.jdbc,"unknown_source_action_qualification"));
        db.jdbc.update("UPDATE alert_source_revision SET action_versions_json=NULL WHERE source_identity_id=1");assertEquals(1,sql.diagnostics(db.jdbc,"unknown_source_action_qualification"));
    }
    @Test void sourceRevisionStatusEnumAndIdentityAdvanceMustAgreeWithImmutableProvenance() throws Exception {
        db.sourceTables();db.jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(101,10,1,'GLUCOSE',5,'mmol/L',CURRENT_TIMESTAMP)");identities();
        db.jdbc.update("INSERT INTO alert_source_revision(source_identity_id,revision_no,recorded_by,occurred_at,recorded_at,snapshot_json,correction_reason,source_status) VALUES(1,2,1,?,?,'{}','Wrong-device reading','INVALIDATED')",AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW);
        Map<String,Object>old=org.familyhealthcare.service.workflow.WorkflowData.map("id",1,"source_type","HEALTH_MEASUREMENT","source_id",101,"patient_id",10,"current_revision",1,"lock_version",0,"status","ACTIVE");Map<String,Object>next=new HashMap<>(old);next.put("current_revision",2);next.put("lock_version",1);
        assertTrue(sql.rejects(db.jdbc,"aw57_source_bu",old,next));next.put("status","INVALIDATED");assertFalse(sql.rejects(db.jdbc,"aw57_source_bu",old,next));
        db.jdbc.update("UPDATE alert_source_identity SET current_revision=2 WHERE id=1");assertEquals(1,sql.diagnostics(db.jdbc,"source_revision_status_mismatch"));db.jdbc.update("UPDATE alert_source_identity SET status='INVALIDATED' WHERE id=1");assertEquals(0,sql.diagnostics(db.jdbc,"source_revision_status_mismatch"));
        db.jdbc.update("UPDATE alert_source_revision SET correction_reason=NULL WHERE source_identity_id=1 AND revision_no=2");assertEquals(1,sql.diagnostics(db.jdbc,"invalid_source_revision_provenance"));
        Map<String,Object>revision=org.familyhealthcare.service.workflow.WorkflowData.map("revision_no",2,"recorded_by",1,"snapshot_json","{}","action_versions_json","{}","occurred_at","2026-10-13 11:00:00","recorded_at","2026-10-13 12:00:00","correction_reason","Wrong-device reading","source_status","UNKNOWN");assertTrue(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),revision));
        revision.put("source_status","INVALIDATED");revision.put("revision_no",1);assertTrue(sql.rejects(db.jdbc,"aw57_revision_bi",Collections.emptyMap(),revision));assertTrue(sql.rejects(db.jdbc,"aw57_revision_bu",revision,revision));assertTrue(sql.rejects(db.jdbc,"aw57_revision_bd",revision,Collections.emptyMap()));
    }
    @Test void onlyExactSystemDueShapeMayOmitHumanIdentity() {
        db.jdbc.update("UPDATE alert_record SET due_at=? WHERE id=2",ps->org.familyhealthcare.service.workflow.WorkflowData.time(ps,1,AlertWorkflowTestDatabase.NOW.minusSeconds(1)));
        Map<String,Object> due=org.familyhealthcare.service.workflow.WorkflowData.map("alert_id",2,"patient_id",10,"cycle_no",1,"event_type","DUE","actor_id",null,"actor_name",null,"actor_role","SYSTEM","actor_relation","SCHEDULER","entry_mode",null,"note",null,"from_status","OPEN","to_status","OPEN","occurred_at","2026-10-13 12:00:00","recorded_at","2026-10-13 12:00:00","payload_json",org.familyhealthcare.service.workflow.WorkflowData.json(org.familyhealthcare.service.workflow.WorkflowData.map("dueKey",AlertWorkflowNotificationWorker.dueKey(2,1,AlertWorkflowTestDatabase.NOW.minusSeconds(1)),"dueAt",AlertWorkflowTestDatabase.NOW.minusSeconds(1).toString())));
        assertFalse(sql.rejects(db.jdbc,"aw57_event_bi",Collections.emptyMap(),due));
        for(String payload:Arrays.asList("{}","[]","{\"dueKey\":\"due:999:4:wrong\",\"dueAt\":\"1900-01-01T00:00:00Z\"}",org.familyhealthcare.service.workflow.WorkflowData.json(org.familyhealthcare.service.workflow.WorkflowData.map("dueKey",AlertWorkflowNotificationWorker.dueKey(2,1,AlertWorkflowTestDatabase.NOW.minusSeconds(1)),"dueAt",AlertWorkflowTestDatabase.NOW.toString())))){Map<String,Object> invalid=new HashMap<>(due);invalid.put("payload_json",payload);assertTrue(sql.rejects(db.jdbc,"aw57_event_bi",Collections.emptyMap(),invalid),payload);}

        for(String field:Arrays.asList("actor_id","actor_name","actor_role","actor_relation","entry_mode","note","from_status","occurred_at","event_type")){Map<String,Object> invalid=new HashMap<>(due);invalid.put(field,"actor_id".equals(field)?1:"invalid");assertTrue(sql.rejects(db.jdbc,"aw57_event_bi",Collections.emptyMap(),invalid),field);}
        Map<String,Object> human=new HashMap<>(due);human.put("event_type","CREATED");human.put("actor_id",1);human.put("actor_name","Fictional");human.put("actor_role","PATIENT");human.put("actor_relation","OWNER");assertFalse(sql.rejects(db.jdbc,"aw57_event_bi",Collections.emptyMap(),human));human.put("actor_role","SYSTEM");assertTrue(sql.rejects(db.jdbc,"aw57_event_bi",Collections.emptyMap(),human));
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-13T11:59:59Z","2026-10-13T11:59:59.100Z","2026-10-13T11:59:59.001Z","2026-10-13T11:59:59.100100Z","2026-10-13T11:59:59.000001Z","2026-10-13T11:59:59.999999Z","1969-12-31T23:59:59.500Z"}) void duePayloadMatchesCanonicalUtcInstantPrecisionExactly(String value){
        java.time.Instant dueAt=java.time.Instant.parse(value);db.jdbc.update("UPDATE alert_record SET due_at=? WHERE id=2",ps->org.familyhealthcare.service.workflow.WorkflowData.time(ps,1,dueAt));
        Map<String,Object> payload=org.familyhealthcare.service.workflow.WorkflowData.map("dueKey",AlertWorkflowNotificationWorker.dueKey(2,1,dueAt),"dueAt",dueAt.toString());
        Map<String,Object> due=org.familyhealthcare.service.workflow.WorkflowData.map("alert_id",2,"patient_id",10,"cycle_no",1,"event_type","DUE","actor_id",null,"actor_name",null,"actor_role","SYSTEM","actor_relation","SCHEDULER","entry_mode",null,"note",null,"from_status","OPEN","to_status","OPEN","occurred_at","2026-10-13 12:00:00","recorded_at","2026-10-13 12:00:00","payload_json",org.familyhealthcare.service.workflow.WorkflowData.json(payload));
        assertFalse(sql.rejects(db.jdbc,"aw57_event_bi",Collections.emptyMap(),due),value);
        payload.put("dueAt",dueAt.plusNanos(1000).toString());due.put("payload_json",org.familyhealthcare.service.workflow.WorkflowData.json(payload));assertTrue(sql.rejects(db.jdbc,"aw57_event_bi",Collections.emptyMap(),due));payload.put("dueAt",dueAt.toString());payload.put("extra",true);due.put("payload_json",org.familyhealthcare.service.workflow.WorkflowData.json(payload));assertTrue(sql.rejects(db.jdbc,"aw57_event_bi",Collections.emptyMap(),due));
    }
    @Test void documentedMysql57PadSpaceModelDistinguishesNonbinaryAndBinaryDueEquality(){
        String operand="JSON_UNQUOTE(JSON_EXTRACT(e.payload_json,'$.dueKey'))";
        String padded=" FROM (SELECT '{\"dueKey\":\"canonical \"}' AS payload_json) e";
        assertFalse(db.jdbc.queryForObject("SELECT "+operand+"='canonical'"+padded,Boolean.class),"H2 ordinary equality does not reproduce MySQL PAD SPACE");
        assertTrue(db.jdbc.queryForObject("SELECT "+AlertWorkflowSqlPredicates.mysql57DuePadSpace(operand+"='canonical'")+padded,Boolean.class));
        assertFalse(db.jdbc.queryForObject("SELECT "+AlertWorkflowSqlPredicates.mysql57DuePadSpace("CAST("+operand+" AS BINARY)='canonical'")+padded,Boolean.class));
    }
    @ParameterizedTest @ValueSource(strings={"dueKey","dueAt"}) void duePayloadRejectsTrailingSpacesUnderDocumentedMysql57PadSpace(String field){
        java.time.Instant deadline=AlertWorkflowTestDatabase.NOW.minusSeconds(1);
        db.jdbc.update("UPDATE alert_record SET due_at=? WHERE id=2",ps->org.familyhealthcare.service.workflow.WorkflowData.time(ps,1,deadline));
        Map<String,Object> payload=org.familyhealthcare.service.workflow.WorkflowData.map("dueKey",AlertWorkflowNotificationWorker.dueKey(2,1,deadline),"dueAt",deadline.toString());
        Map<String,Object> due=org.familyhealthcare.service.workflow.WorkflowData.map("alert_id",2,"patient_id",10,"cycle_no",1,"event_type","DUE","actor_id",null,"actor_name",null,"actor_role","SYSTEM","actor_relation","SCHEDULER","entry_mode",null,"note",null,"from_status","OPEN","to_status","OPEN","occurred_at","2026-10-13 12:00:00","recorded_at","2026-10-13 12:00:00","payload_json",org.familyhealthcare.service.workflow.WorkflowData.json(payload));
        assertFalse(sql.rejectsMysql57DuePadSpace(db.jdbc,due),"Canonical payload stays valid");
        String canonical=(String)payload.get(field);
        for(String suffix:Arrays.asList(" ","  ")){payload.put(field,canonical+suffix);due.put("payload_json",org.familyhealthcare.service.workflow.WorkflowData.json(payload));assertTrue(sql.rejectsMysql57DuePadSpace(db.jdbc,due),field+" must reject trailing ASCII spaces under native PAD SPACE semantics");}
    }
    private void identities(){
        db.jdbc.update("INSERT INTO alert_source_identity(id,source_type,source_id,patient_id,created_at,updated_at) VALUES(1,'HEALTH_MEASUREMENT',101,10,?,?),(2,'HEALTH_MEASUREMENT',202,10,?,?),(3,'BP_SELF_MONITOR',101,10,?,?)",AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW);
        for(long id=1;id<=3;id++)db.jdbc.update("INSERT INTO alert_source_revision(source_identity_id,revision_no,recorded_by,occurred_at,recorded_at,snapshot_json) VALUES(?,1,1,?,?,'{}')",id,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW);
    }
    private void typedEvent(long id,long alert,long patient){db.jdbc.update("INSERT INTO alert_event(id,alert_id,patient_id,to_status,event_type,cycle_no,actor_id,actor_role,actor_relation,recorded_at,payload_json) VALUES(?,?,?,'OPEN','CREATED',1,1,'PATIENT','OWNER',?,'{}')",id,alert,patient,AlertWorkflowTestDatabase.NOW);}
    private Map<String,Object> event(long parent,long patient){Map<String,Object> row=new HashMap<>();row.put("event_type",null);row.put("alert_id",parent);row.put("patient_id",patient);return row;}
    private Map<String,Object> aggregate(long id,boolean workflow){Map<String,Object> row=new HashMap<>();row.put("id",id);row.put("patient_id",10);row.put("user_id",1);row.put("workflow_version",workflow?1:0);row.put("workflow_state",workflow?"OPEN":null);row.put("workflow_mode",workflow?"PERSONAL":null);row.put("cycle_no",workflow?1:0);row.put("lock_version",0);row.put("source_review_required",0);row.put("reassignment_required",0);row.put("source_type",workflow?"HEALTH_MEASUREMENT":null);row.put("source_id",workflow?101:null);row.put("source_revision",workflow?2:null);row.put("metric_key",workflow?"GLUCOSE":null);row.put("classification_json",workflow?"{}":null);row.put("alert_type",workflow?"MEASUREMENT_FOLLOW_UP":"LEGACY");row.put("current_handling_event_id",null);row.put("current_feedback_event_id",null);return row;}
    private Map<String,Object> link(){Map<String,Object> row=new HashMap<>();row.put("source_identity_id",1);row.put("source_type","HEALTH_MEASUREMENT");row.put("source_id",101);row.put("source_revision",1);row.put("metric_key","GLUCOSE");row.put("alert_id",2);return row;}
}
