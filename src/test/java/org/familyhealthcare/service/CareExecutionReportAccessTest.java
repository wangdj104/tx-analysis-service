package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.service.careplan.CareExecutionReportAccess.Access;
import org.familyhealthcare.service.careplan.CareExecutionReportAccess.EvidenceKey;
import org.familyhealthcare.service.careplan.CareExecutionReportAccess.Manifest;
import org.familyhealthcare.service.careplan.CareExecutionReportContracts.Request;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic database facts, including SQL-column and effective-transaction observations. */
class CareExecutionReportAccessTest {
    private CarePlanTestFixture f;
    private CarePlanProperties properties;
    private CarePlanAuthorizationService auth;
    private CareExecutionReportAccess access;
    private RecordingDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;

    @BeforeEach void setup() throws Exception {
        f = new CarePlanTestFixture();
        dataSource = new RecordingDataSource(f.jdbc().getDataSource());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        properties = new CarePlanProperties(true, new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return f.now(); }
        });
        auth = new CarePlanAuthorizationService(jdbc, properties);
        access = new CareExecutionReportAccess(jdbc, auth, transactions);
    }

    @AfterEach void close() throws Exception { if (f != null) f.close(); }

    @Test void ownerAndCurrentAssignedDoctorMayReadQuestions() {
        assertTrue(access.inspect(OWNER, request()).isQuestionsAllowed());
        assertTrue(access.inspect(DOCTOR, request()).isQuestionsAllowed());
        assertDoesNotThrow(() -> access.recheck(OWNER, request(), manifest(true)));
    }

    @Test void readGrantIsEnough() {
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN");
        assertFalse(access.inspect(FAMILY, request()).isQuestionsAllowed());
        assertDoesNotThrow(() -> access.recheck(FAMILY, request(), manifest(false)));
        assertDenied(() -> auth.requireRecord(FAMILY, PATIENT));
        nurse();
        grant(NURSE, "NURSE", "READ", "CARE_PLAN");
        assertFalse(access.inspect(NURSE, request()).isQuestionsAllowed());
        assertDenied(() -> auth.requireRecord(NURSE, PATIENT));
    }

    @Test void narrowNurseNeverLoadsFullPatient() {
        nurse(); grant(NURSE, "NURSE", "READ", "CARE_PLAN");
        seedPlan(20, PATIENT, "ACTIVE", true);
        dataSource.reads.clear();
        assertFalse(access.inspect(NURSE, request()).isQuestionsAllowed());
        access.inspect(NURSE, request(20L));
        access.recheck(NURSE, request(), manifest(false));
        assertNarrowReads();
        assertTrue(dataSource.reads.stream().anyMatch(r -> r.sql.contains("doctor_care_plan")));
        assertFalse(dataSource.reads.stream().anyMatch(r -> r.sql.contains("care_item")));
    }

    @Test void nurseNeedsCurrentRoleAssignmentAndGrant() {
        role(NURSE, "nurse"); grant(NURSE, "NURSE", "READ", "CARE_PLAN");
        assertDenied(() -> access.inspect(NURSE, request()));
        assignment();
        assertFalse(access.inspect(NURSE, request()).isQuestionsAllowed());
        f.jdbc().update("UPDATE care_nurse_assignment SET expires_at=?", java.sql.Timestamp.from(f.now()));
        assertDenied(() -> access.inspect(NURSE, request()));
        f.jdbc().update("UPDATE care_nurse_assignment SET expires_at=NULL");
        f.jdbc().update("UPDATE sys_role SET status=0 WHERE role_code='nurse'");
        assertDenied(() -> access.inspect(NURSE, request()));
    }

    @Test void nursingHistoryCannotBecomeFamilyQuestionAuthority() {
        nurse(); role(NURSE, "family");
        grant(NURSE, "FAMILY", "READ", "CARE_PLAN");
        grant(NURSE, "GUARDIAN", "READ", null);
        f.jdbc().update("INSERT INTO care_member(patient_id,user_id,access_level) VALUES(1,10,'READ')");
        assertFalse(access.inspect(NURSE, request()).isQuestionsAllowed());
        f.jdbc().update("DELETE FROM sys_user_role WHERE user_id=10 AND role_id IN (SELECT id FROM sys_role WHERE role_code='nurse')");
        f.jdbc().update("UPDATE care_nurse_assignment SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP");
        assertDenied(() -> access.inspect(NURSE, request()));
        role(NURSE, "doctor");
        f.jdbc().update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(10,1,11)");
        assertTrue(access.inspect(NURSE, request()).isQuestionsAllowed());
    }

    @Test void independentOwnershipOverridesNursingIdentity() {
        role(OWNER, "nurse");
        assertTrue(access.inspect(OWNER, request()).isQuestionsAllowed());
    }

    @Test void staleDoctorOrAdminCannotReadQuestions() {
        f.as(DOCTOR); // Keep the HTTP doctor role cached after database revocation.
        role(DOCTOR, "family"); grant(DOCTOR, "FAMILY", "READ", "CARE_PLAN");
        f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9");
        assertFalse(access.inspect(DOCTOR, request()).isQuestionsAllowed());
        f.jdbc().update("UPDATE doctor_patient_assignment SET status='ACTIVE' WHERE doctor_user_id=9");
        f.jdbc().update("UPDATE sys_role SET status=0 WHERE role_code='doctor'");
        assertFalse(access.inspect(DOCTOR, request()).isQuestionsAllowed());
        f.as(ADMIN);
        assertDenied(() -> access.inspect(ADMIN, request()));
        role(ADMIN, "family"); grant(ADMIN, "FAMILY", "READ", "CARE_PLAN");
        assertFalse(access.inspect(ADMIN, request()).isQuestionsAllowed());
        grant(ADMIN, "GUARDIAN", "READ", null);
        assertTrue(access.inspect(ADMIN, request()).isQuestionsAllowed());
    }

    @Test void readFamilyNeedsSeparateFullRecordAuthority() {
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN");
        f.jdbc().update("INSERT INTO care_member(patient_id,user_id,access_level) VALUES(1,8,'READ')");
        assertFalse(access.inspect(FAMILY, request()).isQuestionsAllowed());
        grant(FAMILY, "GUARDIAN", "READ", null);
        assertTrue(access.inspect(FAMILY, request()).isQuestionsAllowed());
        f.jdbc().update("UPDATE care_access_grant SET visible_modules='  ' WHERE grantee_role='GUARDIAN'");
        assertTrue(access.inspect(FAMILY, request()).isQuestionsAllowed());
        for (String modules : Arrays.asList("*", "CARE_PLAN,MEDICAL,MEASUREMENTS", "MEDICATION")) {
            f.jdbc().update("UPDATE care_access_grant SET visible_modules=? WHERE grantee_role='GUARDIAN'", modules);
            assertFalse(access.inspect(FAMILY, request()).isQuestionsAllowed());
        }
    }

    @Test void explicitRevocationOverridesLegacyMembershipAndOtherValidGrantsRemainEffective() {
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN"); grant(FAMILY, "GUARDIAN", "READ", null);
        f.jdbc().update("INSERT INTO care_member(patient_id,user_id,access_level) VALUES(1,8,'PROXY')");
        assertTrue(access.inspect(FAMILY, request()).isQuestionsAllowed());
        f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_role='GUARDIAN'");
        assertFalse(access.inspect(FAMILY, request()).isQuestionsAllowed());
        grant(FAMILY, "DOCTOR", "READ", null); // Full-record rules honor any valid explicit grant.
        assertTrue(access.inspect(FAMILY, request()).isQuestionsAllowed());
        f.jdbc().update("UPDATE care_access_grant SET expires_at=CURRENT_TIMESTAMP WHERE grantee_role='DOCTOR'");
        assertFalse(access.inspect(FAMILY, request()).isQuestionsAllowed());
        assertChanged(() -> access.recheck(FAMILY, request(), manifest(true)));
    }

    @Test void disabledActorPatientOrOnlyCurrentRoleDeniesWholeReport() {
        f.jdbc().update("UPDATE sys_user SET status=0 WHERE id=7");
        assertDenied(() -> access.inspect(OWNER, request()));
        f.jdbc().update("UPDATE sys_user SET status=1,deleted=1 WHERE id=7");
        assertDenied(() -> access.inspect(OWNER, request()));
        f.jdbc().update("UPDATE sys_user SET deleted=0 WHERE id=7");
        f.jdbc().update("UPDATE patient SET status=0 WHERE id=1");
        assertDenied(() -> access.inspect(OWNER, request()));
        f.jdbc().update("UPDATE patient SET status=1,deleted=1 WHERE id=1");
        assertDenied(() -> access.inspect(OWNER, request()));
        f.jdbc().update("UPDATE patient SET deleted=0 WHERE id=1");
        f.jdbc().update("UPDATE sys_role SET deleted=1 WHERE role_code='patient'");
        assertDenied(() -> access.inspect(OWNER, request()));
    }

    @Test void planMustBelongToPatientAndHavePublishedCollaborativeRevision() {
        seedPlan(20, PATIENT, "ACTIVE", true);
        seedPlan(21, OTHER_PATIENT, "ACTIVE", true);
        seedPlan(22, PATIENT, "DRAFT", false);
        for (long plan : new long[]{1, 21, 22, 999}) {
            assertDenied(() -> access.inspect(DOCTOR, request(plan)));
        }
        for (String lifecycle : Arrays.asList("ACTIVE", "COMPLETED", "CANCELLED")) {
            f.jdbc().update("UPDATE doctor_care_plan SET lifecycle=? WHERE id=20", lifecycle);
            assertDoesNotThrow(() -> access.inspect(OWNER, request(20L)));
        }
        f.jdbc().update("UPDATE care_plan_revision SET status='DRAFT' WHERE plan_id=20");
        assertDenied(() -> access.inspect(OWNER, request(20L)));
    }

    @Test void finalScopeValidationDoesNotLeakChangedPlanOwnership() {
        seedPlan(20, PATIENT, "ACTIVE", true);
        access.inspect(OWNER, request(20L));
        f.jdbc().update("UPDATE doctor_care_plan SET patient_id=2 WHERE id=20");
        assertDenied(() -> access.recheck(OWNER, request(20L), manifest(false)));
    }

    @Test void revocationChangesFinalDecision() {
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN,MEDICAL,MEASUREMENTS");
        seedEvidence();
        Manifest included = manifest(false, new EvidenceKey("MEASUREMENT", 1), new EvidenceKey("MEDICAL_RECORD", 1));
        assertDoesNotThrow(() -> access.recheck(FAMILY, request(), included));
        f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN,MEDICAL' WHERE grantee_user_id=8");
        assertChanged(() -> access.recheck(FAMILY, request(), included));
        f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");
        assertDenied(() -> access.recheck(FAMILY, request(), included));
    }

    @Test void emptyIncludedQuestionSectionStillRequiresFinalAuthority() {
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN"); grant(FAMILY, "GUARDIAN", "READ", null);
        assertTrue(access.inspect(FAMILY, request()).isQuestionsAllowed());
        Manifest emptyButIncluded = manifest(true);
        f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_role='GUARDIAN'");
        assertChanged(() -> access.recheck(FAMILY, request(), emptyButIncluded));
        assertDoesNotThrow(() -> access.recheck(FAMILY, request(), manifest(false)));
        assertFalse(dataSource.reads.stream().anyMatch(r -> r.sql.contains("care_item")));
    }

    @Test void reassignmentOrDeletionOfReadableEvidenceInvalidatesPreparedReport() {
        seedEvidence();
        Manifest included = manifest(false, new EvidenceKey("MEASUREMENT", 1), new EvidenceKey("MEDICAL_RECORD", 1));
        access.recheck(OWNER, request(), included);
        f.jdbc().update("UPDATE health_measurement SET patient_id=2 WHERE id=1");
        assertChanged(() -> access.recheck(OWNER, request(), included));
        f.jdbc().update("UPDATE health_measurement SET patient_id=1 WHERE id=1");
        f.jdbc().update("DELETE FROM medical_record WHERE id=1");
        assertChanged(() -> access.recheck(OWNER, request(), included));
        assertChanged(() -> access.recheck(OWNER, request(), manifest(false, new EvidenceKey("UNKNOWN", 1))));
        assertChanged(() -> access.recheck(OWNER, request(), manifest(false, new EvidenceKey("MEASUREMENT", 999))));
        assertNarrowReads();
    }

    @Test void newlyGrantedAuthorityDoesNotMutateEarlierAccessOrManifest() {
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN");
        Access earlier = access.inspect(FAMILY, request());
        Manifest included = manifest(false);
        grant(FAMILY, "GUARDIAN", "READ", null);
        access.recheck(FAMILY, request(), included);
        assertFalse(earlier.isQuestionsAllowed());
        assertFalse(included.isQuestionsIncluded());
        assertTrue(included.getReadableEvidence().isEmpty());
        assertTrue(access.inspect(FAMILY, request()).isQuestionsAllowed());
    }

    @Test void eachCallUsesFreshReadCommittedConnectionAndRestoresOuterSnapshot() {
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN"); grant(FAMILY, "GUARDIAN", "READ", null);
        TransactionTemplate outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status -> {
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM care_access_grant WHERE grantee_role=?", String.class, "GUARDIAN"));
            int outerConnection = dataSource.reads.get(dataSource.reads.size() - 1).connection;
            // The fixture datasource is unbound; this update commits on another connection.
            f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_role='GUARDIAN'");
            dataSource.reads.clear();
            assertFalse(access.inspect(FAMILY, request()).isQuestionsAllowed());
            Set<Integer> inspectConnections = observedFreshConnections(outerConnection);
            dataSource.reads.clear();
            assertChanged(() -> access.recheck(FAMILY, request(), manifest(true)));
            Set<Integer> recheckConnections = observedFreshConnections(outerConnection);
            assertTrue(Collections.disjoint(inspectConnections, recheckConnections));
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM care_access_grant WHERE grantee_role=?", String.class, "GUARDIAN"));
            Read restored = dataSource.reads.get(dataSource.reads.size() - 1);
            assertEquals(outerConnection, restored.connection);
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, restored.isolation);
            return null;
        });
    }

    @Test void disabledFeatureUsesReport404WithoutReadingPatientData() {
        CarePlanAuthorizationService disabled = new CarePlanAuthorizationService(jdbc, new CarePlanProperties());
        CareExecutionReportAccess disabledAccess = new CareExecutionReportAccess(jdbc, disabled, transactions);
        dataSource.reads.clear();
        CarePlanException denied = assertThrows(CarePlanException.class, () -> disabledAccess.inspect(OWNER, request()));
        assertEquals("FEATURE_DISABLED", denied.getErrorCode()); assertEquals(404, denied.getStatus());
        assertTrue(dataSource.reads.isEmpty());
        assertEquals(404, assertThrows(CarePlanException.class,
                () -> disabledAccess.recheck(OWNER, request(), manifest(false))).getStatus());
    }

    @Test void manifestDefensivelyCopiesAndDeduplicatesEvidenceKeys() {
        EvidenceKey key = new EvidenceKey("MEASUREMENT", 1);
        Set<EvidenceKey> input = new LinkedHashSet<>(Arrays.asList(key, new EvidenceKey("MEASUREMENT", 1), new EvidenceKey("MEDICAL_RECORD", 1)));
        Manifest manifest = new Manifest(true, input);
        input.clear();
        assertTrue(manifest.isQuestionsIncluded()); assertEquals(2, manifest.getReadableEvidence().size());
        assertEquals("MEASUREMENT", key.getSourceType()); assertEquals(1, key.getSourceId());
        assertEquals(key, new EvidenceKey("MEASUREMENT", 1));
        assertEquals(key.hashCode(), new EvidenceKey("MEASUREMENT", 1).hashCode());
        assertNotEquals(key, new EvidenceKey("MEASUREMENT", 2));
        assertThrows(UnsupportedOperationException.class, () -> manifest.getReadableEvidence().clear());
    }

    @Test void remainingSharedBudgetBoundsAllFreshAuthorizationStatements() {
        CareExecutionReportBudget budget=CareExecutionReportBudget.start(Duration.ofMillis(900));
        access.inspect(OWNER,request(),budget);access.recheck(OWNER,request(),manifest(true),budget);
        assertFalse(dataSource.reads.isEmpty());
        for(Read read:dataSource.reads){assertEquals(1,read.queryTimeout,read.sql);assertEquals(Connection.TRANSACTION_READ_COMMITTED,read.isolation);assertTrue(read.readOnly);}
        dataSource.reads.clear();
        assertEquals("REPORT_TIMEOUT",assertThrows(CareExecutionReportException.class,()->access.inspect(OWNER,request(),CareExecutionReportBudget.start(Duration.ZERO))).getErrorCode());
        assertEquals("REPORT_TIMEOUT",assertThrows(CareExecutionReportException.class,()->access.recheck(OWNER,request(),manifest(true),CareExecutionReportBudget.start(Duration.ZERO))).getErrorCode());
        assertTrue(dataSource.reads.isEmpty());
    }

    private Set<Integer> observedFreshConnections(int outerConnection) {
        assertFalse(dataSource.reads.isEmpty());
        Set<Integer> connections = new HashSet<>();
        for (Read read : dataSource.reads) {
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, read.isolation, read.sql);
            assertFalse(read.autoCommit, read.sql);
            assertNotEquals(outerConnection, read.connection, read.sql);
            connections.add(read.connection);
        }
        assertEquals(1, connections.size());
        return connections;
    }

    private void assertNarrowReads() {
        assertFalse(dataSource.reads.isEmpty());
        for (Read read : dataSource.reads) {
            String sql = read.sql.toLowerCase(Locale.ROOT);
            assertFalse(sql.matches("(?s).*select\\s+(?:\\w+\\.)?\\*.*"), sql);
            for (String forbidden : Arrays.asList("medical_history", "care_item", "data_json", "draft_json", "instructions", "ai_raw_result", "file_content", "metric_value", "value_primary", "password"))
                assertFalse(sql.contains(forbidden), sql);
        }
    }

    private void nurse() { role(NURSE, "nurse"); assignment(); }
    private void role(long actor, String role) {
        f.jdbc().update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?", actor, role);
    }
    private void assignment() {
        f.jdbc().update("INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,assigned_by,assigned_at) VALUES(1,10,11,CURRENT_TIMESTAMP)");
    }
    private void grant(long actor, String role, String level, String modules) {
        f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,granted_by) VALUES(1,?,?,?,?,7)", actor, role, level, modules);
    }
    private Request request() { return request(null); }
    private Request request(Long planId) {
        return CareExecutionReportContracts.parse("{\"patientId\":1,\"timeZone\":\"UTC\",\"language\":\"en\"" +
                (planId == null ? "" : ",\"planId\":" + planId) + "}", false, f.now());
    }
    private Manifest manifest(boolean questions, EvidenceKey... keys) { return new Manifest(questions, new LinkedHashSet<>(Arrays.asList(keys))); }
    private void seedPlan(long id, long patient, String lifecycle, boolean published) {
        f.jdbc().update("INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions,workflow_version,lifecycle) VALUES(?,9,?,'Synthetic title','Synthetic instruction',1,?)", id, patient, lifecycle);
        f.jdbc().update("INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at,published_at) VALUES(?,?,1,?,'Synthetic title','Synthetic instruction','OTHER','{}',9,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",
                id, id, published ? "PUBLISHED" : "DRAFT", published ? java.sql.Timestamp.from(f.now()) : null);
        f.jdbc().update("UPDATE doctor_care_plan SET " + (published ? "current_revision_id" : "draft_revision_id") + "=? WHERE id=?", id, id);
    }
    private void seedEvidence() {
        f.jdbc().update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(1,1,7,'CUSTOM',10,'synthetic',CURRENT_TIMESTAMP)");
        f.jdbc().update("INSERT INTO medical_record(id,patient_id,user_id,patient_name,record_type) VALUES(1,1,7,'Synthetic Patient','OTHER')");
    }
    private void assertDenied(org.junit.jupiter.api.function.Executable action) {
        CarePlanException error = assertThrows(CarePlanException.class, action);
        assertEquals(403, error.getStatus()); assertEquals("ACCESS_DENIED", error.getErrorCode());
    }
    private void assertChanged(org.junit.jupiter.api.function.Executable action) {
        CarePlanException error = assertThrows(CarePlanException.class, action);
        assertEquals(409, error.getStatus()); assertEquals("REPORT_ACCESS_CHANGED", error.getErrorCode());
    }

    static final class Read {
        final String sql;
        final int connection, isolation, queryTimeout;
        final boolean autoCommit, readOnly;
        Read(String sql, int connection, Connection delegate, int queryTimeout) throws SQLException {
            this.queryTimeout=queryTimeout;
            this.sql = sql; this.connection = connection;
            this.isolation = delegate.getTransactionIsolation(); this.autoCommit = delegate.getAutoCommit();
            this.readOnly = org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }
    }

    /** SQL text only: never captures bind values, result data, or credentials. */
    static final class RecordingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private int nextConnection;
        final List<Read> reads = new ArrayList<>();
        RecordingDataSource(DataSource delegate) { this.delegate = delegate; }
        public Connection getConnection() throws SQLException { return observe(delegate.getConnection()); }
        public Connection getConnection(String username, String password) throws SQLException { return observe(delegate.getConnection(username, password)); }
        private Connection observe(Connection connection) {
            final int id = ++nextConnection;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result=method.invoke(connection,args);
                    if (result instanceof java.sql.Statement) {
                        String prepared=args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:null;
                        Class<?> type=result instanceof java.sql.PreparedStatement?java.sql.PreparedStatement.class:java.sql.Statement.class;
                        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                            if (m.getName().startsWith("execute")) {
                                String sql=prepared!=null?prepared:a!=null&&a.length>0&&a[0] instanceof String?(String)a[0]:null;
                                if(sql!=null) reads.add(new Read(sql,id,connection,((java.sql.Statement)result).getQueryTimeout()));
                            }
                            try{return m.invoke(result,a);}catch(InvocationTargetException error){throw error.getCause();}
                        });
                    }
                    return result;
                } catch (InvocationTargetException error) { throw error.getCause(); }
            });
        }
    }
}
