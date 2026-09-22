package org.familyhealthcare.util;

import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.PatientMapper;
import org.familyhealthcare.service.CareMembershipService;
import org.familyhealthcare.service.CareJourneyService;
import org.familyhealthcare.service.AiAnalysisService;
import org.familyhealthcare.service.HealthAnalysisAutomationService;
import org.familyhealthcare.service.impl.AiAnalysisServiceImpl;
import org.familyhealthcare.entity.AiAnalysisRecord;
import org.familyhealthcare.entity.HealthAnalysisAutomation;
import org.familyhealthcare.mapper.AiAnalysisRecordMapper;
import org.familyhealthcare.mapper.HealthAnalysisAutomationMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataScopeAuthorizationTest {
    JdbcTemplate jdbc; DataScopeHelper scope; CareMembershipService membership;
    @BeforeEach void setup() {
        JdbcDataSource data=new JdbcDataSource();data.setURL("jdbc:h2:mem:scope"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE patient(id BIGINT,user_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE care_access_grant(patient_id BIGINT,grantee_user_id BIGINT,access_level VARCHAR(20),visible_modules VARCHAR(200),status VARCHAR(20),expires_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE doctor_patient_assignment(patient_id BIGINT,doctor_user_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE care_member(patient_id BIGINT,user_id BIGINT,access_level VARCHAR(20) DEFAULT 'WRITE',visible_modules VARCHAR(1000))");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT,status INT DEFAULT 1,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_role(id BIGINT,role_code VARCHAR(30),status INT DEFAULT 1,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        jdbc.update("INSERT INTO patient VALUES(1,7,0)");
        Patient patient=new Patient();patient.setId(1L);patient.setUserId(7L);patient.setDeleted(0);
        PatientMapper patients=mock(PatientMapper.class);when(patients.selectById(1L)).thenReturn(patient);
        membership=mock(CareMembershipService.class);when(membership.accessiblePatients(11L)).thenReturn(Collections.emptyList());
        scope=new DataScopeHelper();ReflectionTestUtils.setField(scope,"jdbcTemplate",jdbc);ReflectionTestUtils.setField(scope,"patientMapper",patients);ReflectionTestUtils.setField(scope,"careMembership",membership);
        request("GET","/api/patient/list");
    }
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}

    @Test void consultationOnlyGrantDoesNotExposeMedicationOrAggregatedClinicalRecord() {
        grant("READ","CONSULTATION");
        request("GET","/api/medication/list");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));assertTrue(scope.accessiblePatientIds(11L).isEmpty());
        request("GET","/api/clinical-workbench/overview");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        assertNotNull(scope.requirePatientAccess(1L,"CONSULTATION",false));
        assertThrows(IllegalStateException.class,()->scope.requirePatientAccess(1L,"CONSULTATION",true));
        request("GET","/api/patient/names");assertEquals(Collections.singletonList(1L),scope.accessiblePatientIds(11L));
        request("GET","/api/patient/list");assertTrue(scope.accessiblePatientIds(11L).isEmpty());
    }

    @Test void medicationReadGrantCannotWriteAndWriteGrantDoesNotAuthorizeOtherModules() {
        grant("READ"," medication ");request("GET","/api/medication/list");assertNotNull(scope.requirePatient(1L));
        request("POST","/api/medication/save");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        jdbc.update("UPDATE care_access_grant SET access_level='WRITE'");assertNotNull(scope.requirePatient(1L));
        request("POST","/api/medical-record/save");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
    }

    @Test void revokedOrExpiredGrantsDoNotFallBackToFamilyMembership() {
        jdbc.update("INSERT INTO care_member(patient_id,user_id) VALUES(1,11)");
        grant("WRITE",null);request("PUT","/api/medication/update");assertNotNull(scope.requirePatient(1L));
        jdbc.update("UPDATE care_access_grant SET status='REVOKED'");
        request("GET","/api/medication/list");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        request("DELETE","/api/medication/delete/1");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        jdbc.update("UPDATE care_access_grant SET status='ACTIVE',expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        request("GET","/api/medication/list");assertTrue(scope.accessiblePatientIds(11L).isEmpty());
    }

    @Test void familyMemberReadOnlyPermissionIsRespectedAndReadOnlyPostPreviewRemainsAvailable() {
        jdbc.update("INSERT INTO care_member(patient_id,user_id,access_level) VALUES(1,11,'READ')");
        request("GET","/api/medication/list");assertNotNull(scope.requirePatient(1L));
        request("POST","/api/medication/save");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        request("POST","/api/dialysis/text-import/preview");assertNotNull(scope.requirePatient(1L));
        request("POST","/api/dialysis/text-import/confirm");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
    }

    @Test void legacyWriteAndProxyFamilyMembersRetainOnlyTheirAllowedModules() {
        jdbc.update("INSERT INTO care_member(patient_id,user_id) VALUES(1,11)");
        request("POST","/api/medication/save");assertNotNull(scope.requirePatient(1L));
        jdbc.update("UPDATE care_member SET access_level='PROXY',visible_modules='MEDICATION'");
        assertNotNull(scope.requirePatient(1L));
        request("POST","/api/medical-record/save");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        request("GET","/api/clinical-workbench/overview");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
    }

    @Test void fullReadExportDoesNotImplyFullWriteAndAssignedDoctorKeepsAccess() {
        grant("READ",null);request("POST","/api/data-export/csv");assertNotNull(scope.requirePatient(1L));
        request("PUT","/api/patient/update");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        jdbc.update("INSERT INTO doctor_patient_assignment VALUES(1,11,'ACTIVE')");
        MockHttpServletRequest doctor=request("POST","/api/medical-record/save");doctor.setAttribute("roleCodes",Collections.singletonList("doctor"));assertNotNull(scope.requirePatient(1L));
    }

    @Test void fullReadHealthReportGenerationDoesNotRequireWriteButScopedGrantsCannotReadAnAggregate() {
        grant("READ",null);request("POST","/api/health-report/generate");assertNotNull(scope.requirePatient(1L));
        jdbc.update("UPDATE care_access_grant SET visible_modules='DIALYSIS'");
        assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        request("POST","/api/dialysis/text-import/confirm");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
    }

    @Test void dialysisPatternEndpointsHonorTheSameModuleWithoutExposingAggregatedAiData() {
        grant("READ","DIALYSIS");request("GET","/api/bp-pattern/list");assertNotNull(scope.requirePatient(1L));
        request("POST","/api/bp-pattern/analyze");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
        jdbc.update("UPDATE care_access_grant SET access_level='WRITE'");assertNotNull(scope.requirePatient(1L));
        request("GET","/api/ai/analyze");assertThrows(IllegalStateException.class,()->scope.requirePatient(1L));
    }

    @Test void journeyUsesExplicitModuleAndCombinesDifferentGrantsWithoutWideningWriteAccess() {
        grant("READ","MEDICATION");grant("WRITE","MEASUREMENTS");
        CareJourneyService journey=new CareJourneyService();ReflectionTestUtils.setField(journey,"jdbc",jdbc);ReflectionTestUtils.setField(journey,"scope",scope);
        request("GET","/api/unrelated-route");
        journey.requireRead(1L,"MEDICATION");journey.requireWrite(1L,"MEASUREMENTS");
        assertThrows(IllegalStateException.class,()->journey.requireWrite(1L,"MEDICATION"));
        assertThrows(IllegalStateException.class,()->journey.requireRead(1L,"MENTAL"));
        jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE visible_modules='MEASUREMENTS'");
        assertThrows(IllegalStateException.class,()->journey.requireWrite(1L,"MEASUREMENTS"));journey.requireRead(1L,"MEDICATION");
    }

    @Test void backgroundAuthorizationRechecksOwnershipGrantsAndActiveDoctorRoleWithoutHttpContext() {
        RequestContextHolder.resetRequestAttributes();
        assertNotNull(CurrentUserUtil.runAsUser(7L,()->scope.requirePatientAccess(1L,null,true)));
        grant("READ",null);
        assertNotNull(CurrentUserUtil.runAsUser(11L,()->scope.requirePatient(1L)));
        assertThrows(IllegalStateException.class,()->CurrentUserUtil.runAsUser(11L,()->scope.requirePatientAccess(1L,null,true)));
        jdbc.update("INSERT INTO sys_user(id) VALUES(11)");jdbc.update("INSERT INTO sys_role(id,role_code) VALUES(2,'doctor')");jdbc.update("INSERT INTO sys_user_role VALUES(11,2)");
        jdbc.update("INSERT INTO doctor_patient_assignment VALUES(1,11,'ACTIVE')");
        assertNotNull(CurrentUserUtil.runAsUser(11L,()->scope.requirePatientAccess(1L,null,true)));
        jdbc.update("UPDATE sys_role SET status=0 WHERE id=2");
        assertThrows(IllegalStateException.class,()->CurrentUserUtil.runAsUser(11L,()->scope.requirePatientAccess(1L,null,true)));
        assertNull(CurrentUserUtil.getCurrentUserId());
    }

    @Test void savingAiResultsRequiresCurrentFullWriteAccessInsteadOfTrustingPatientId() {
        grant("READ",null);request("POST","/api/ai/save");
        AiAnalysisServiceImpl analysis=new AiAnalysisServiceImpl();AiAnalysisRecordMapper records=mock(AiAnalysisRecordMapper.class);
        ReflectionTestUtils.setField(analysis,"dataScopeHelper",scope);ReflectionTestUtils.setField(analysis,"aiAnalysisRecordMapper",records);
        AiAnalysisRecord record=new AiAnalysisRecord();record.setPatientId(1L);record.setAnalysisContent("Draft");
        assertThrows(IllegalStateException.class,()->analysis.saveAnalysis(record));verifyNoInteractions(records);
    }

    @Test void backgroundAutomationCannotGenerateOrSaveDraftsAfterWriteAccessIsDowngraded() {
        grant("READ",null);RequestContextHolder.resetRequestAttributes();
        HealthAnalysisAutomationService automations=new HealthAnalysisAutomationService();
        HealthAnalysisAutomationMapper tasks=mock(HealthAnalysisAutomationMapper.class);AiAnalysisRecordMapper records=mock(AiAnalysisRecordMapper.class);AiAnalysisService analysis=mock(AiAnalysisService.class);
        HealthAnalysisAutomation task=new HealthAnalysisAutomation();task.setId(5L);task.setUserId(11L);task.setPatientId(1L);task.setEnabled(0);task.setAnalysisItems("DIALYSIS");task.setAnalysisRangeDays(7);
        when(tasks.selectById(5L)).thenReturn(task);when(analysis.analyzeHealthSnapshot(1L,7,Collections.singletonList("DIALYSIS"))).thenReturn("Draft");
        ReflectionTestUtils.setField(automations,"mapper",tasks);ReflectionTestUtils.setField(automations,"analysisRecords",records);ReflectionTestUtils.setField(automations,"aiAnalysis",analysis);ReflectionTestUtils.setField(automations,"scope",scope);
        assertThrows(IllegalStateException.class,()->CurrentUserUtil.runAsUser(11L,()->automations.runNow(5L)));
        verifyNoInteractions(analysis,records);assertEquals("FAILED",task.getLastRunStatus());
    }

    private void grant(String level,String module){jdbc.update("INSERT INTO care_access_grant(patient_id,grantee_user_id,access_level,visible_modules,status) VALUES(1,11,?,?,'ACTIVE')",level,module);}
    private MockHttpServletRequest request(String method,String uri){MockHttpServletRequest request=new MockHttpServletRequest(method,uri);request.setAttribute("userId",11L);request.setAttribute("roleCodes",Collections.singletonList("family"));RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));return request;}
}
