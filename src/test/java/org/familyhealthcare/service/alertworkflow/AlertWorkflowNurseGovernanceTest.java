package org.familyhealthcare.service.alertworkflow;

import org.junit.jupiter.api.*;
import org.familyhealthcare.util.CurrentUserUtil;
import java.util.*;
import java.util.concurrent.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertWorkflowNurseGovernanceTest {
    MeasurementWorkflowBridgeTest nativeApi; AlertWorkflowHarness h;
    @BeforeEach void setup() throws Exception {
        nativeApi=new MeasurementWorkflowBridgeTest();nativeApi.setup();h=nativeApi.h;
        h.db.jdbc.execute("ALTER TABLE sys_user_role ADD id BIGINT AUTO_INCREMENT");
        org.springframework.test.util.ReflectionTestUtils.setField(h.auth,"carePlan",new org.familyhealthcare.service.careplan.CarePlanProperties(false,java.time.Clock.systemUTC()));
        h.db.jdbc.execute("ALTER TABLE care_access_grant ADD granted_by BIGINT");
        h.db.jdbc.execute("ALTER TABLE care_access_grant ADD created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        h.db.jdbc.execute("ALTER TABLE care_access_grant ADD CONSTRAINT grant_unique UNIQUE(patient_id,grantee_user_id,grantee_role)");
        h.db.jdbc.update("DELETE FROM care_access_grant WHERE grantee_user_id=4");
    }
    /** H2 has no MySQL shared-lock syntax; this fixture uses a stronger row lock, not native lock evidence. */
    private org.familyhealthcare.service.workflow.HouseholdAuthorityLocks assignmentLocks() {
        org.springframework.jdbc.core.JdbcTemplate fixture=new org.springframework.jdbc.core.JdbcTemplate(h.db.jdbc.getDataSource()) {
            @Override public List<Map<String,Object>> queryForList(String sql,Object...args) {
                if("SELECT id,user_id FROM patient WHERE id=? LOCK IN SHARE MODE".equals(sql))
                    sql="SELECT id,user_id FROM patient WHERE id=? FOR UPDATE";
                return super.queryForList(sql,args);
            }
        };
        return new org.familyhealthcare.service.workflow.HouseholdAuthorityLocks(fixture);
    }
    @Test void patientCanExplicitlyGrantOnlyMeasurementsToAnAssignedNurseWhileCarePlansAreOff() {
        h.denied(403,()->h.auth.requireRecord(4,10));
        Map<String,Object>b=map("patientId",10,"granteeUserId",4,"granteeRole","NURSE","accessLevel","WRITE","visibleModules","MEASUREMENTS");
        assertDoesNotThrow(()->CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->nativeApi.journey.saveGrant(b))));
        assertEquals("MEASUREMENTS",h.db.jdbc.queryForObject("SELECT visible_modules FROM care_access_grant WHERE grantee_user_id=4",String.class));
        h.auth.requireRecord(4,10);assertFalse(h.auth.canReadEvidence(4,10,"MEDICAL_RECORD",77));
    }
    @Test void independentAssignmentGovernanceNeverCreatesAGrantAndOnlyOwnerOrDoctorCanReadIt() throws Exception {
        h.db.jdbc.execute("DROP TABLE care_nurse_assignment");h.db.jdbc.execute(AlertWorkflowTestDatabase.h2(AlertWorkflowTestDatabase.tableSql(AlertWorkflowTestDatabase.read("src/main/resources/sql/init.sql"),"care_nurse_assignment")));
        AlertWorkflowNurseGovernance governance=new AlertWorkflowNurseGovernance(h.db.jdbc,h.auth,h.p,assignmentLocks());
        h.denied(403,()->h.db.tx.execute(tx->governance.assign(1,map("patientId",10,"nurseUserId",4))));
        Map<String,Object>assignment=h.db.tx.execute(tx->governance.assign(5,map("patientId",10,"nurseUserId",4,"expiresAt",AlertWorkflowTestDatabase.NOW.plusSeconds(60).atOffset(java.time.ZoneOffset.ofHours(8)).toString())));
        assertEquals(AlertWorkflowTestDatabase.NOW.plusSeconds(60).toString(),assignment.get("expiresAt"));
        assertEquals(1,governance.list(1,10).size());assertEquals(1,governance.list(3,10).size());h.denied(403,()->governance.list(5,10));h.denied(403,()->governance.list(4,10));h.denied(403,()->h.auth.requireRecord(4,10));
        assertEquals(1,governance.candidates(5).size());h.denied(403,()->governance.candidates(1));
        h.db.tx.execute(tx->{governance.revoke(5,id(assignment.get("id")));return null;});h.denied(403,()->h.auth.requireNurseGrant(1,10,4));
        assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM care_access_grant WHERE grantee_user_id=4",Integer.class));
    }
    @Test void revokedAssignmentExpiredAssignmentAndReadOnlyGrantCannotRecord() {
        h.db.grant(4,"NURSE","READ","MEASUREMENTS",null);h.denied(403,()->h.auth.requireRecord(4,10));h.auth.requireRead(4,10);
        h.db.jdbc.update("UPDATE care_access_grant SET access_level='WRITE' WHERE grantee_user_id=4");h.auth.requireRecord(4,10);
        h.db.jdbc.update(connection->{java.sql.PreparedStatement ps=connection.prepareStatement("UPDATE care_nurse_assignment SET expires_at=? WHERE nurse_user_id=4");org.familyhealthcare.service.workflow.WorkflowData.time(ps,1,AlertWorkflowTestDatabase.NOW);return ps;});h.denied(403,()->h.auth.requireRead(4,10));
        h.db.jdbc.update("UPDATE care_nurse_assignment SET expires_at=NULL,status='REVOKED' WHERE nurse_user_id=4");h.denied(403,()->h.auth.requireRecord(4,10));
    }

    @Test void existingCarePlanNurseGrantWithMeasurementsStillWorksWhenAlertWorkflowIsOff() {
        org.familyhealthcare.service.careplan.CarePlanProperties cp=new org.familyhealthcare.service.careplan.CarePlanProperties(true,java.time.Clock.fixed(AlertWorkflowTestDatabase.NOW,java.time.ZoneOffset.UTC));
        org.springframework.test.util.ReflectionTestUtils.setField(nativeApi.journey,"carePlanAuthorization",new org.familyhealthcare.service.careplan.CarePlanAuthorizationService(h.db.jdbc,cp));
        org.springframework.test.util.ReflectionTestUtils.setField(h.p,"enabled",false);
        Map<String,Object>b=map("patientId",10,"granteeUserId",4,"granteeRole","NURSE","accessLevel","WRITE","visibleModules","CARE_PLAN,MEASUREMENTS");
        assertDoesNotThrow(()->CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->nativeApi.journey.saveGrant(b))));
    }

    @Test void assignmentRevocationRechecksAdminAuthorityAfterWaitingForTheAssignmentLock() throws Exception {
        CountDownLatch held=new CountDownLatch(1),checked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        AlertWorkflowAuthorizationService observed=org.mockito.Mockito.spy(h.auth);org.mockito.Mockito.doAnswer(call->{Object result=call.callRealMethod();checked.countDown();return result;}).when(observed).requireAssignmentAdmin(5);
        AlertWorkflowNurseGovernance governance=new AlertWorkflowNurseGovernance(h.db.jdbc,observed,h.p,assignmentLocks());
        try {
            Future<?> lock=pool.submit(()->h.db.tx.execute(tx->{h.db.jdbc.queryForList("SELECT id FROM care_nurse_assignment WHERE id=1 FOR UPDATE");held.countDown();AlertWorkflowPlanDependencyTest.await(release);return null;}));assertTrue(held.await(3,TimeUnit.SECONDS));
            Future<?> revoke=pool.submit(()->h.denied(403,()->h.db.tx.execute(tx->{governance.revoke(5,1);return null;})));assertTrue(checked.await(3,TimeUnit.SECONDS));
            h.db.jdbc.update("UPDATE sys_user SET status=0 WHERE id=5");release.countDown();lock.get(5,TimeUnit.SECONDS);revoke.get(5,TimeUnit.SECONDS);
            assertEquals("ACTIVE",h.db.jdbc.queryForObject("SELECT status FROM care_nurse_assignment WHERE id=1",String.class));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void measurementOnlyGrantRechecksNurseAssignmentAfterWaitingForTheGrantRow() throws Exception {
        h.db.grant(4,"NURSE","READ","MEASUREMENTS",null);CountDownLatch held=new CountDownLatch(1),checked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        AlertWorkflowAuthorizationService observed=org.mockito.Mockito.spy(h.auth);org.mockito.Mockito.doAnswer(call->{Object result=call.callRealMethod();checked.countDown();return result;}).when(observed).requireNurseGrant(1,10,4);org.springframework.test.util.ReflectionTestUtils.setField(nativeApi.journey,"workflowAuthorization",observed);
        try {
            Future<?> lock=pool.submit(()->h.db.tx.execute(tx->{h.db.jdbc.queryForList("SELECT id FROM care_access_grant WHERE grantee_user_id=4 FOR UPDATE");held.countDown();AlertWorkflowPlanDependencyTest.await(release);return null;}));assertTrue(held.await(3,TimeUnit.SECONDS));
            Future<?> grant=pool.submit(()->h.denied(403,()->CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->nativeApi.journey.saveGrant(map("patientId",10,"granteeUserId",4,"granteeRole","NURSE","accessLevel","WRITE","visibleModules","MEASUREMENTS"))))));assertTrue(checked.await(3,TimeUnit.SECONDS));
            h.db.jdbc.update("UPDATE care_nurse_assignment SET status='REVOKED' WHERE id=1");release.countDown();lock.get(5,TimeUnit.SECONDS);grant.get(5,TimeUnit.SECONDS);
            assertEquals("READ",h.db.jdbc.queryForObject("SELECT access_level FROM care_access_grant WHERE grantee_user_id=4",String.class));
        }finally{release.countDown();pool.shutdownNow();}
    }

    @Test void measurementOnlyGrantRetainsLocalExpiryAndRejectsOffsetOrExpiredInput() {
        java.time.LocalDateTime future=java.time.LocalDateTime.now().plusDays(1).withNano(0);
        Map<String,Object> body=map("patientId",10,"granteeUserId",4,"granteeRole","NURSE","accessLevel","WRITE","visibleModules","MEASUREMENTS","expiresAt",future.toString());
        CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->nativeApi.journey.saveGrant(body)));
        assertEquals(future,h.db.jdbc.queryForObject("SELECT expires_at FROM care_access_grant WHERE grantee_user_id=4",java.sql.Timestamp.class).toLocalDateTime());h.auth.requireRecord(4,10);
        for(String invalid:Arrays.asList(future+"Z",future+"+08:00",java.time.LocalDateTime.now().minusDays(1).toString())) {
            body.put("expiresAt",invalid);assertThrows(IllegalArgumentException.class,()->CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->nativeApi.journey.saveGrant(body))));
        }
        assertEquals(future,h.db.jdbc.queryForObject("SELECT expires_at FROM care_access_grant WHERE grantee_user_id=4",java.sql.Timestamp.class).toLocalDateTime());
    }

}
