package org.familyhealthcare.service.alertworkflow;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertWorkflowAuthorizationTest {
    private AlertWorkflowTestDatabase db;private AlertWorkflowAuthorizationService auth;
    @BeforeEach void setup() throws Exception {
        db=new AlertWorkflowTestDatabase();db.fixtures();db.sourceTables();
        AlertWorkflowProperties properties=new AlertWorkflowProperties(true,Clock.fixed(AlertWorkflowTestDatabase.NOW,ZoneOffset.UTC));
        auth=new AlertWorkflowAuthorizationService(db.jdbc,properties,new CarePlanProperties(true,Clock.fixed(AlertWorkflowTestDatabase.NOW,ZoneOffset.UTC)));
        db.jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(101,10,1,'GLUCOSE',8,'mmol/L',?),(202,20,6,'GLUCOSE',8,'mmol/L',?)",AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW);
        db.jdbc.update("INSERT INTO bp_self_monitor_record(id,user_id,patient_id,record_date,record_time,measure_type,blood_glucose,bg_unit,measure_period) VALUES(102,1,10,'2026-10-13','12:00','BG',8,'mmol/L','Fasting')");
        db.jdbc.update("INSERT INTO medical_record(id,patient_id) VALUES(103,10)");
    }
    @Test void ownerCanReadRecordAndManageButCannotPerformClinicalClosure() {
        allow("requireRead",1);allow("requireRecord",1);allow("requireOwner",1);deny("requireClinical",1);deny("requireNursing",1);
    }
    @Test void familyReadDoesNotBecomeWriteOrOwnership() {allow("requireRead",2);deny("requireRecord",2);deny("requireOwner",2);deny("requireClinical",2);}
    @ParameterizedTest @ValueSource(strings={"WRITE","PROXY"}) void explicitFamilyRecordingGrantPermitsAssistance(String level) {
        db.jdbc.update("UPDATE care_access_grant SET access_level=? WHERE grantee_user_id=2",level);allow("requireRecord",2);
    }
    @ParameterizedTest @ValueSource(strings={"","ALL","MEDICAL","CARE_PLAN"}) void implicitOrOtherModuleGrantNeverGivesMeasurements(String modules) {
        db.jdbc.update("UPDATE care_access_grant SET visible_modules=? WHERE grantee_user_id=2",modules);deny("requireRead",2);
    }
    @Test void nullModulesNeverBecomeImplicitMeasurementsGrant() {db.jdbc.update("UPDATE care_access_grant SET visible_modules=NULL WHERE grantee_user_id=2");deny("requireRead",2);}
    @Test void currentDoctorAssignmentGivesClinicalAuthorityWithoutGrant() {allow("requireRead",3);allow("requireRecord",3);allow("requireClinical",3);db.jdbc.update("DELETE FROM doctor_patient_assignment");deny("requireRead",3);deny("requireClinical",3);}
    @Test void nurseMeasurementOnlyAssignmentAndGrantDoNotImplyCarePlanOrMedical() {
        allow("requireRead",4);allow("requireNursing",4);deny("requireClinical",4);
        assertTrue(evidence(4,"BP_SELF_MONITOR",102));assertFalse(evidence(4,"MEDICAL_RECORD",103));
        plan();assertFalse(evidence(4,"CARE_PLAN_ACTION",104));
    }
    @Test void nurseReadGrantAllowsReadButDoesNotAllowRecording() {
        db.jdbc.update("UPDATE care_access_grant SET access_level='READ' WHERE grantee_user_id=4");allow("requireRead",4);deny("requireRecord",4);deny("requireNursing",4);
    }
    @Test void nurseCannotFallbackToFamilyAfterAssignmentRevocationOrRoleRemoval() {
        db.jdbc.update("INSERT INTO sys_user_role VALUES(4,2)");db.grant(4,"FAMILY","WRITE","MEASUREMENTS",null);
        db.jdbc.update("UPDATE care_nurse_assignment SET status='REVOKED',revoked_at=?",AlertWorkflowTestDatabase.NOW);deny("requireRead",4);
        db.jdbc.update("DELETE FROM sys_user_role WHERE user_id=4 AND role_id=4");deny("requireRecord",4);
    }
    @Test void expiredNursingAssignmentAtTheExactDeadlineDenies() {db.jdbc.update("UPDATE care_nurse_assignment SET expires_at=?",AlertWorkflowTestDatabase.NOW);deny("requireRead",4);}
    @Test void utcNursingExpiryAndLegacyLocalGrantExpiryKeepTheirDistinctInterpretation() {
        db.jdbc.update(connection->{java.sql.PreparedStatement ps=connection.prepareStatement("UPDATE care_nurse_assignment SET expires_at=?");org.familyhealthcare.service.workflow.WorkflowData.time(ps,1,AlertWorkflowTestDatabase.NOW.plusSeconds(60));return ps;});allow("requireNursing",4);
        db.jdbc.update(connection->{java.sql.PreparedStatement ps=connection.prepareStatement("UPDATE care_nurse_assignment SET expires_at=?");org.familyhealthcare.service.workflow.WorkflowData.time(ps,1,AlertWorkflowTestDatabase.NOW);return ps;});deny("requireRead",4);
        db.jdbc.update("UPDATE care_access_grant SET expires_at=DATEADD('DAY',1,CURRENT_TIMESTAMP) WHERE grantee_user_id=2");allow("requireRead",2);
        db.jdbc.update("UPDATE care_access_grant SET expires_at=CURRENT_TIMESTAMP WHERE grantee_user_id=2");deny("requireRead",2);
    }
    @Test void expiredOrRevokedGrantDeniesCurrentAccess() {
        db.jdbc.update("UPDATE care_access_grant SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE grantee_user_id=2");deny("requireRead",2);
        db.jdbc.update("UPDATE care_access_grant SET expires_at=NULL,status='REVOKED' WHERE grantee_user_id=2");deny("requireRead",2);
    }
    @ParameterizedTest @ValueSource(strings={"status=0","deleted=1"}) void disabledOrDeletedAccountsNeverKeepAuthority(String change) {db.jdbc.update("UPDATE sys_user SET "+change+" WHERE id=3");deny("requireRead",3);deny("requireClinical",3);}
    @ParameterizedTest @ValueSource(strings={"status=0","deleted=1"}) void disabledOrDeletedRoleNeverKeepsAuthority(String change) {db.jdbc.update("UPDATE sys_role SET "+change+" WHERE role_code='doctor'");deny("requireRead",3);}
    @Test void deletedPatientDeniesOwnerAndAssignedDoctor() {db.jdbc.update("UPDATE patient SET deleted=1 WHERE id=10");deny("requireRead",1);deny("requireClinical",3);}
    @Test void administratorRoleAloneGrantsNoClinicalContent() {deny("requireRead",5);deny("requireRecord",5);deny("requireClinical",5);assertFalse(evidence(5,"HEALTH_MEASUREMENT",101));}
    @Test void evidenceHasIndependentModulesAndExactPatientOwnership() {
        assertTrue(evidence(2,"HEALTH_MEASUREMENT",101));assertFalse(evidence(2,"HEALTH_MEASUREMENT",202));assertFalse(evidence(2,"MEDICAL_RECORD",103));
        db.jdbc.update("UPDATE care_access_grant SET visible_modules='MEASUREMENTS,MEDICAL' WHERE grantee_user_id=2");assertTrue(evidence(2,"MEDICAL_RECORD",103));
        assertFalse(evidence(2,"UNTRUSTED_TABLE",101));assertFalse(evidence(2,"HEALTH_MEASUREMENT",999));
    }
    @Test void currentCarePlanActionRequiresPlanRelationshipAndItsOwnSourcePermissions() {
        plan();db.jdbc.update("UPDATE care_access_grant SET visible_modules='MEASUREMENTS,CARE_PLAN' WHERE grantee_user_id=2");assertTrue(evidence(2,"CARE_PLAN_ACTION",104));
        db.jdbc.update("INSERT INTO care_plan_event(id,plan_id,action_id,event_type,patient_id) VALUES(1000,105,104,'RECEIPT_SUBMITTED',10)");db.jdbc.update("INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(1000,'MEDICAL_RECORD',103)");
        assertFalse(evidence(2,"CARE_PLAN_ACTION",104));db.jdbc.update("UPDATE care_access_grant SET visible_modules='MEASUREMENTS,CARE_PLAN,MEDICAL' WHERE grantee_user_id=2");assertTrue(evidence(2,"CARE_PLAN_ACTION",104));
        db.jdbc.update("UPDATE care_plan_action SET revision_id=999 WHERE id=104");assertFalse(evidence(2,"CARE_PLAN_ACTION",104));
    }
    @Test void clinicalAssigneesAreOnlyCurrentDoctorsOrAssignedMeasurementRecordNurses() {
        List<Map<String,Object>> rows=assignees(1,"CLINICAL");assertEquals(Arrays.asList(3L,4L),ids(rows));
        db.jdbc.update("UPDATE care_nurse_assignment SET status='REVOKED'");assertEquals(Collections.singletonList(3L),ids(assignees(3,"CLINICAL")));
        deny("requireOwner",2);assertThrows(RuntimeException.class,()->assignees(2,"PERSONAL"));
    }
    @Test void actorSnapshotIsServerDerivedAndReflectsCurrentIdentity() {
        Map<?,?> row=(Map<?,?>)auth.actorSnapshot(4L,10L);
        assertEquals("NURSE",row.get("actorRole"));assertEquals("NURSE",row.get("actorRelation"));assertEquals("Fictional 4",row.get("actorName"));
        db.jdbc.update("UPDATE sys_user SET status=0 WHERE id=4");assertThrows(RuntimeException.class,()->auth.actorSnapshot(4L,10L));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("blankActorNames")
    void serverNameFallbackForOwnerDoctorAndNursePersistsEventsAndAssigneeDisplay(long actor,String realName) throws Exception {
        db.jdbc.update("UPDATE sys_user SET real_name=? WHERE id=?",realName,actor);db.workflowTables();
        AlertWorkflowProperties props=new AlertWorkflowProperties(true,Clock.fixed(AlertWorkflowTestDatabase.NOW,ZoneOffset.UTC));AlertWorkflowRepository repo=new AlertWorkflowRepository(db.jdbc,props,auth);
        db.tx.execute(status->{
            long identity=repo.createSourceIdentity("HEALTH_MEASUREMENT",101,10);repo.appendSourceRevision(identity,1,1,AlertWorkflowTestDatabase.NOW,Collections.singletonMap("valuePrimary",8));
            Map<String,Object> input=new LinkedHashMap<>();input.put("patientId",10L);input.put("ownerUserId",1L);input.put("sourceType","HEALTH_MEASUREMENT");input.put("sourceId",101L);input.put("sourceRevision",1L);input.put("metric","GLUCOSE");input.put("workflowMode","PERSONAL");input.put("classification",Collections.singletonMap("status","ABNORMAL"));long alert=repo.createAlert(input);
            Map<String,Object> snapshot=auth.actorSnapshot(actor,10);assertEquals("fictional"+actor,snapshot.get("actorName"));
            long event=repo.appendEvent(alert,10,1,"CREATED",snapshot,null,"OPEN",null,null,null,Collections.emptyMap());
            assertEquals("fictional"+actor,db.jdbc.queryForObject("SELECT actor_name FROM alert_event WHERE id=?",String.class,event));
            Map<String,Object> assignee=auth.eligibleAssignees(1,10,"PERSONAL").stream().filter(row->((Number)row.get("userId")).longValue()==actor).findFirst().orElseThrow(AssertionError::new);assertEquals("fictional"+actor,assignee.get("displayName"));return null;
        });
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> blankActorNames(){
        List<org.junit.jupiter.params.provider.Arguments> rows=new ArrayList<>();for(long actor:new long[]{1,3,4})for(String name:Arrays.asList(null,""," ","\t\n","\u00a0"))rows.add(org.junit.jupiter.params.provider.Arguments.of(actor,name));return rows.stream();
    }
    @Test void serverNameHasStableIdentityFallbackWhenBothProfileNamesAreBlank() {
        db.jdbc.update("UPDATE sys_user SET real_name=?,username=? WHERE id=1","\u00a0","\t ");
        assertEquals("User 1",auth.actorSnapshot(1,10).get("actorName"));
        assertEquals("User 1",auth.eligibleAssignees(1,10,"PERSONAL").get(0).get("displayName"));
    }
    @Test void serverNameTrimsAndBoundsUnicodeWithoutSplittingSurrogatePairs() {
        db.jdbc.execute("ALTER TABLE sys_user ALTER COLUMN real_name VARCHAR(400)");
        StringBuilder name=new StringBuilder("\u00a0 "),expected=new StringBuilder();
        for(int i=0;i<101;i++){name.append("\ud83d\ude00");if(i<100)expected.append("\ud83d\ude00");}name.append("\t\u00a0");
        db.jdbc.update("UPDATE sys_user SET real_name=? WHERE id=1",name.toString());
        assertEquals(expected.toString(),auth.actorSnapshot(1,10).get("actorName"));
        assertEquals(expected.toString(),auth.eligibleAssignees(1,10,"PERSONAL").get(0).get("displayName"));
        assertEquals(expected.toString(),AlertWorkflowContracts.requireText(auth.actorSnapshot(1,10).get("actorName"),"actorName",100));
    }
    @Test void featureOffDeniesBeforeAnyWorkflowDatabaseReadOrWrite() {
        AlertWorkflowProperties off=new AlertWorkflowProperties(false,Clock.fixed(AlertWorkflowTestDatabase.NOW,ZoneOffset.UTC));
        AlertWorkflowAuthorizationService gate=new AlertWorkflowAuthorizationService(new JdbcTemplate(),off,new CarePlanProperties());
        AlertWorkflowException error=assertThrows(AlertWorkflowException.class,()->gate.requireRead(1L,10L));
        assertEquals(503,error.getStatus());
    }
    private void plan(){db.jdbc.update("INSERT INTO doctor_care_plan VALUES(105,10,100,'ACTIVE',1,'ACTIVE')");db.jdbc.update("INSERT INTO care_plan_action VALUES(104,105,10,100,'CONFIRMED',2)");}
    private void allow(String method,long actor){assertDoesNotThrow(()->require(method,actor));}
    private void deny(String method,long actor){AlertWorkflowException e=assertThrows(AlertWorkflowException.class,()->require(method,actor));assertEquals(403,e.getStatus());}
    private void require(String method,long actor){
        switch(method){case "requireRead":auth.requireRead(actor,10);break;case "requireRecord":auth.requireRecord(actor,10);break;case "requireOwner":auth.requireOwner(actor,10);break;case "requireClinical":auth.requireClinical(actor,10);break;case "requireNursing":auth.requireNursing(actor,10);break;default:fail("Unknown fixture gate");}
    }
    private boolean evidence(long actor,String type,long id){return(Boolean)auth.canReadEvidence(actor,10L,type,id);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> assignees(long actor,String mode){return(List<Map<String,Object>>)auth.eligibleAssignees(actor,10L,mode);}
    private List<Long> ids(List<Map<String,Object>> rows){List<Long> ids=new ArrayList<>();for(Map<String,Object> row:rows)ids.add(((Number)row.get("userId")).longValue());return ids;}
}
