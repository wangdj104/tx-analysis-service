package org.familyhealthcare.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.familyhealthcare.service.careplan.CarePlanAuthorizationService;
import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.service.careplan.CareNurseAssignmentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.familyhealthcare.interceptor.PermissionInterceptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.time.*;
import java.util.*;

import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real synthetic H2 authorization tests; no request-role or menu mock can grant clinical access. */
class CarePlanAuthorizationTest {
    private CarePlanTestFixture f;
    private CarePlanAuthorizationService auth;
    private CarePlanProperties properties;

    @BeforeEach void setup() throws Exception {
        f = new CarePlanTestFixture();
        properties = new CarePlanProperties(true, new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return f.now(); }
        });
        auth = new CarePlanAuthorizationService(f.jdbc(), properties);
    }
    @AfterEach void close() throws Exception { if (f != null) f.close(); }

    @Test void nurseNeedsRoleAssignmentAndGrant() {
        grant(NURSE, "NURSE", "WRITE", "CARE_PLAN");
        deny("requireRead", NURSE, PATIENT);
        role(NURSE, "nurse");
        deny("requireRead", NURSE, PATIENT);
        assignment(null);
        allow("requireRead", NURSE, PATIENT);
        allow("requireRecord", NURSE, PATIENT);
        allow("requireNursing", NURSE, PATIENT);
        assertThrows(CarePlanException.class, () -> auth.requireClinical(NURSE, PATIENT));
        f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=?", NURSE);
        deny("requireRead", NURSE, PATIENT);
    }

    @Test void adminAloneCannotReadClinicalPlan() {
        deny("requireRead", ADMIN, PATIENT);
        deny("requireRecord", ADMIN, PATIENT);
        deny("requireClinical", ADMIN, PATIENT);
        role(ADMIN, "doctor");
        deny("requireClinical", ADMIN, PATIENT);
        f.jdbc().update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(11,1,11)");
        allow("requireClinical", ADMIN, PATIENT);
    }

    @Test void familyReadGrantIsReadOnlyAndWriteProxyCannotActClinically() {
        for (String level : Arrays.asList("READ", "WRITE", "PROXY")) {
            grant(FAMILY, "FAMILY", level, "CARE_PLAN");
            allow("requireRead", FAMILY, PATIENT);
            if ("READ".equals(level)) deny("requireRecord", FAMILY, PATIENT);
            else allow("requireRecord", FAMILY, PATIENT);
            deny("requireClinical", FAMILY, PATIENT);
            deny("requireNursing", FAMILY, PATIENT);
        }
    }

    @Test void revocationDoesNotFallBackToMembership() {
        f.jdbc().update("INSERT INTO care_member(patient_id,user_id,access_level) VALUES(1,8,'PROXY')");
        grant(FAMILY, "FAMILY", "WRITE", "CARE_PLAN");
        allow("requireRead", FAMILY, PATIENT);
        f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");
        deny("requireRead", FAMILY, PATIENT);
        deny("requireRecord", FAMILY, PATIENT);
        grant(FAMILY, "FAMILY", "WRITE", "MEDICATION");
        deny("requireRead", FAMILY, PATIENT);
    }

    @Test void ownerCanRecordButCannotActAsDoctor() {
        allow("requireRead", OWNER, PATIENT);
        allow("requireRecord", OWNER, PATIENT);
        deny("requireClinical", OWNER, PATIENT);
        deny("requireNursing", OWNER, PATIENT);
        role(OWNER, "nurse");
        allow("requireRead", OWNER, PATIENT);
    }

    @Test void nurseFamilyIdentityDoesNotBypassAssignmentAndDoctorAssignmentRemainsClinical() {
        role(NURSE, "nurse"); role(NURSE, "family");
        grant(NURSE, "FAMILY", "PROXY", "CARE_PLAN");
        f.jdbc().update("INSERT INTO care_member(patient_id,user_id,access_level) VALUES(1,10,'PROXY')");
        deny("requireRead", NURSE, PATIENT);
        role(NURSE, "doctor");
        deny("requireClinical", NURSE, PATIENT);
        f.jdbc().update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(10,1,11)");
        allow("requireClinical", NURSE, PATIENT);
        allow("requireRead", NURSE, PATIENT);
    }

    @Test void assignmentExpiryAndRevocationStopHistoricalAccessImmediately() {
        role(NURSE, "nurse"); grant(NURSE, "NURSE", "WRITE", "CARE_PLAN");
        assignment(f.now().plusSeconds(1));
        allow("requireRead", NURSE, PATIENT);
        f.advance(Duration.ofSeconds(1));
        deny("requireRead", NURSE, PATIENT);
        f.jdbc().update("UPDATE care_nurse_assignment SET expires_at=NULL,status='REVOKED',revoked_at=?", utc(f.now()));
        deny("requireRead", NURSE, PATIENT);
    }

    @Test void grantExpiryAtExactBoundaryAndRoleDisableStopAccess() {
        role(NURSE, "nurse"); grant(NURSE, "NURSE", "READ", "CARE_PLAN"); assignment(null);
        allow("requireRead", NURSE, PATIENT);
        deny("requireRecord", NURSE, PATIENT);
        deny("requireNursing", NURSE, PATIENT);
        f.jdbc().update("UPDATE care_access_grant SET expires_at=CURRENT_TIMESTAMP");
        deny("requireRead", NURSE, PATIENT);
        f.jdbc().update("UPDATE care_access_grant SET expires_at=NULL");
        f.jdbc().update("UPDATE sys_role SET status=0 WHERE role_code='nurse'");
        deny("requireRead", NURSE, PATIENT);
    }

    @Test void disabledDeletedAccountsPatientsAndStaleRequestRolesCannotAuthorize() {
        allow("requireClinical", DOCTOR, PATIENT);
        f.jdbc().update("UPDATE sys_role SET status=0 WHERE role_code='doctor'");
        deny("requireClinical", DOCTOR, PATIENT);
        f.jdbc().update("UPDATE sys_role SET status=1 WHERE role_code='doctor'");
        f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED'");
        deny("requireClinical", DOCTOR, PATIENT);
        f.jdbc().update("UPDATE sys_user SET status=0 WHERE id=7");
        deny("requireRead", OWNER, PATIENT);
        f.jdbc().update("UPDATE sys_user SET status=1,deleted=1 WHERE id=7");
        deny("requireRead", OWNER, PATIENT);
        f.jdbc().update("UPDATE sys_user SET deleted=0 WHERE id=7");
        f.jdbc().update("UPDATE patient SET status=0 WHERE id=1");
        deny("requireRead", OWNER, PATIENT);
        f.jdbc().update("UPDATE patient SET status=1,deleted=1 WHERE id=1");
        deny("requireRead", OWNER, PATIENT);
        deny("requireRead", OWNER, 999L);
    }

    @Test void crossPatientEvidenceAndDisabledActorDenied() {
        seedEvidence();
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN");
        assertFalse(evidence(FAMILY, PATIENT, "MEASUREMENT", 1));
        grant(FAMILY, "FAMILY", "READ", "CARE_PLAN,MEASUREMENTS,MEDICAL");
        assertTrue(evidence(FAMILY, PATIENT, "MEASUREMENT", 1));
        assertTrue(evidence(FAMILY, PATIENT, "MEDICAL_RECORD", 1));
        assertFalse(evidence(FAMILY, PATIENT, "MEASUREMENT", 2));
        assertFalse(evidence(FAMILY, PATIENT, "MEDICAL_RECORD", 2));
        assertFalse(evidence(FAMILY, PATIENT, "MEASUREMENT", 999));
        assertFalse(evidence(FAMILY, PATIENT, "UNKNOWN", 1));
        assertFalse(evidence(ADMIN, PATIENT, "MEDICAL_RECORD", 1));
        assertTrue(evidence(DOCTOR, PATIENT, "MEDICAL_RECORD", 1));
        f.jdbc().update("UPDATE sys_user SET status=0 WHERE id=8");
        assertFalse(evidence(FAMILY, PATIENT, "MEDICAL_RECORD", 1));
    }

    @Test void nurseCarePlanPermissionDoesNotImplyEvidenceModules() {
        seedEvidence(); role(NURSE, "nurse"); assignment(null);
        grant(NURSE, "NURSE", "WRITE", "CARE_PLAN");
        assertFalse(evidence(NURSE, PATIENT, "MEASUREMENT", 1));
        grant(NURSE, "NURSE", "WRITE", "CARE_PLAN,MEASUREMENTS");
        assertTrue(evidence(NURSE, PATIENT, "MEASUREMENT", 1));
        assertFalse(evidence(NURSE, PATIENT, "MEDICAL_RECORD", 1));
    }

    @Test void disabledFeatureDeniesWithoutQueryingNewTables() {
        ReflectionTestUtils.setField(properties, "enabled", false);
        f.jdbc().execute("DROP TABLE care_nurse_assignment");
        CarePlanException unavailable=assertThrows(CarePlanException.class,()->auth.requireRead(OWNER,PATIENT));
        assertEquals(503,unavailable.getStatus()); assertEquals("FEATURE_DISABLED",unavailable.getErrorCode());
        assertFalse(evidence(OWNER, PATIENT, "MEASUREMENT", 1));
        // The service bean can also register with disabled defaults and no migrated schema.
        CarePlanAuthorizationService disabled = new CarePlanAuthorizationService(f.jdbc());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> call(disabled, "requireRead", OWNER, PATIENT));
        assertEquals("CarePlanException", ex.getClass().getSimpleName());
    }

    @Test void legacyFullMembershipAndBlankGrantDoNotOptInToCarePlan() {
        f.jdbc().update("INSERT INTO care_member(patient_id,user_id,access_level,visible_modules) VALUES(1,8,'PROXY',NULL)");
        deny("requireRead", FAMILY, PATIENT);
        f.jdbc().update("UPDATE care_member SET visible_modules='CARE_PLAN'");
        deny("requireRead", FAMILY, PATIENT);
        grant(FAMILY, "FAMILY", "WRITE", null);
        deny("requireRead", FAMILY, PATIENT);
        grant(FAMILY, "FAMILY", "WRITE", "*");
        deny("requireRead", FAMILY, PATIENT);
        grant(FAMILY, "FAMILY", "WRITE", "CARE_PLAN");
        allow("requireRead", FAMILY, PATIENT);
    }

    @Test void administratorAssignmentDoesNotGrantPatientModuleAccess() {
        Object assignments = assignmentService();
        role(NURSE, "nurse");
        Map<String,Object> row = assign(assignments, ADMIN, body("patientId", PATIENT, "nurseUserId", NURSE));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(PATIENT, row.get("patientId"));
        assertEquals(NURSE, row.get("nurseUserId"));
        assertEquals(ADMIN, row.get("assignedBy"));
        assertEquals(f.now().toString(), row.get("assignedAt"));
        assertEquals(0, f.jdbc().queryForObject("SELECT COUNT(*) FROM care_access_grant", Integer.class));
        deny("requireRead", NURSE, PATIENT);
        assertThrows(RuntimeException.class, () -> assign(assignments, OWNER, body("patientId", PATIENT, "nurseUserId", NURSE)));
    }

    @Test void assignmentRevokeAndReactivateAreScopedAndPreserveIdentity() {
        Object assignments = assignmentService(); role(NURSE, "nurse");
        Map<String,Object> row = assign(assignments, ADMIN, body("patientId", PATIENT, "nurseUserId", NURSE,"expiresAt","2026-10-03T07:00:00+01:00"));
        assertEquals("2026-10-03T06:00:00Z", row.get("expiresAt"));
        long id = ((Number)row.get("id")).longValue();
        grant(NURSE, "NURSE", "WRITE", "CARE_PLAN"); allow("requireRead",NURSE,PATIENT);
        invoke(assignments,"revoke",new Class<?>[]{long.class,long.class},ADMIN,id);
        deny("requireRead",NURSE,PATIENT);
        Map<String,Object> again=assign(assignments,ADMIN,body("patientId",PATIENT,"nurseUserId",NURSE));
        assertEquals(id,again.get("id")); assertNull(again.get("revokedAt"));
        allow("requireRead",NURSE,PATIENT);
        assertThrows(RuntimeException.class, () -> invoke(assignments,"revoke",new Class<?>[]{long.class,long.class},OWNER,id));
        assertThrows(RuntimeException.class, () -> invoke(assignments,"revoke",new Class<?>[]{long.class,long.class},ADMIN,999L));
    }

    @Test void assignmentListContainsOnlySelectedPatientAndGovernanceFields() {
        Object assignments=assignmentService(); role(NURSE,"nurse");
        assign(assignments,ADMIN,body("patientId",PATIENT,"nurseUserId",NURSE));
        assign(assignments,ADMIN,body("patientId",OTHER_PATIENT,"nurseUserId",NURSE));
        for(long actor: new long[]{ADMIN,OWNER,DOCTOR}) {
            List<?> rows=(List<?>)call(assignments,"list",actor,PATIENT);
            assertEquals(1,rows.size());
            assertEquals(new HashSet<>(Arrays.asList("id","patientId","nurseUserId","nurseName","assignedBy","assignedAt","expiresAt","revokedAt","status")), ((Map<?,?>)rows.get(0)).keySet());
        }
        assertThrows(RuntimeException.class, () -> call(assignments,"list",FAMILY,PATIENT));
        assertThrows(RuntimeException.class, () -> call(assignments,"list",DOCTOR,OTHER_PATIENT));
        assertThrows(RuntimeException.class, () -> call(assignments,"list",NURSE,PATIENT));
    }

    @Test void assignmentValidatesExactIdsOffsetExpiryAndCannotAcceptActorId() {
        Object assignments=assignmentService(); role(NURSE,"nurse");
        for(Map<String,Object> invalid: Arrays.asList(
                body("patientId",1.5,"nurseUserId",NURSE),body("patientId",PATIENT,"nurseUserId",NURSE,"actorId",ADMIN),
                body("patientId",PATIENT,"nurseUserId",NURSE,"expiresAt",f.now().toString()),
                body("patientId",PATIENT,"nurseUserId",NURSE,"expiresAt","2026-10-03T06:00:00"),
                body("patientId",PATIENT,"nurseUserId",NURSE,"expiresAt","2026-10-03T06:00:00.1234567Z"))) {
            assertThrows(RuntimeException.class, () -> assign(assignments,ADMIN,invalid));
        }
        f.jdbc().update("UPDATE sys_role SET status=0 WHERE role_code='nurse'");
        assertThrows(RuntimeException.class, () -> assign(assignments,ADMIN,body("patientId",PATIENT,"nurseUserId",NURSE)));
        assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM care_nurse_assignment",Integer.class));
    }

    @Test void nurseGrantRequiresActualOwnerActiveNurseAndActiveAssignment() {
        CareJourneyService journey=journey(); f.as(OWNER);
        Map<String,Object> request=body("patientId",PATIENT,"granteeUserId",NURSE,"granteeRole","NURSE","accessLevel","WRITE","visibleModules","CARE_PLAN");
        assertThrows(RuntimeException.class,()->journey.saveGrant(request));
        role(NURSE,"nurse"); assertThrows(RuntimeException.class,()->journey.saveGrant(request));
        assignment(null);
        assertDoesNotThrow(()->journey.saveGrant(request));
        allow("requireRecord",NURSE,PATIENT);
        f.as(ADMIN);
        assertThrows(RuntimeException.class,()->journey.saveGrant(request));
        f.as(OWNER);
        f.jdbc().update("UPDATE care_nurse_assignment SET status='REVOKED'");
        assertThrows(RuntimeException.class,()->journey.saveGrant(request));
    }

    @Test void legacyGrantBehaviorIsUnchangedWhenCarePlanDisabled() {
        ReflectionTestUtils.setField(properties,"enabled",false);
        f.jdbc().execute("DROP TABLE care_nurse_assignment");
        CareJourneyService journey=journey(); f.as(OWNER);
        assertDoesNotThrow(()->journey.saveGrant(body("patientId",PATIENT,"granteeUserId",FAMILY,"granteeRole","FAMILY","accessLevel","READ","visibleModules","MEDICATION")));
        assertThrows(RuntimeException.class,()->journey.saveGrant(body("patientId",PATIENT,"granteeUserId",NURSE,"granteeRole","NURSE","accessLevel","READ","visibleModules","CARE_PLAN")));
    }

    @Test void permissionInterceptorRestrictsAssignmentWritesAndAllowsScopedReadEntry() throws Exception {
        PermissionInterceptor interceptor=new PermissionInterceptor();
        ReflectionTestUtils.setField(interceptor,"carePlanProperties",properties);
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/care-nurse-assignments");
        request.setAttribute("userId",FAMILY); request.setAttribute("roleCodes",Collections.singletonList("family"));
        MockHttpServletResponse response=new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request,response,new Object())); assertEquals(403,response.getStatus());
        request.setMethod("GET"); assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
        request.setMethod("POST"); request.setAttribute("roleCodes",Collections.singletonList("admin"));
        assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
    }

    @Test void legacyGrantExpiryUsesDatabaseClockAndNeverUtcReinterpretation() {
        grant(FAMILY,"FAMILY","READ","CARE_PLAN");
        f.jdbc().update("UPDATE care_access_grant SET expires_at=DATEADD('DAY',1,CURRENT_TIMESTAMP)");
        f.advance(Duration.ofDays(30));
        allow("requireRead",FAMILY,PATIENT);
        f.jdbc().update("UPDATE care_access_grant SET expires_at=CURRENT_TIMESTAMP");
        deny("requireRead",FAMILY,PATIENT);
    }

    @Test void nurseGrantExpiryPreservesExistingLocalInputAndRejectsOffsetExtension() {
        role(NURSE,"nurse"); assignment(null); f.as(OWNER);
        CareJourneyService journey=journey();
        String expiry=LocalDateTime.now().plusDays(1).withNano(0).toString();
        Map<String,Object> request=body("patientId",PATIENT,"granteeUserId",NURSE,"granteeRole","NURSE","accessLevel","READ","visibleModules","CARE_PLAN","expiresAt",expiry);
        assertDoesNotThrow(()->journey.saveGrant(request));
        assertEquals(LocalDateTime.parse(expiry),f.jdbc().queryForObject("SELECT expires_at FROM care_access_grant WHERE grantee_user_id=10",LocalDateTime.class));
        request.put("expiresAt",Instant.now().plusSeconds(86400).toString());
        assertThrows(IllegalArgumentException.class,()->journey.saveGrant(request));
    }

    @Test void assignmentApiReturnsReal403And400WithStableCodesAndNoIdentityLeak() throws Exception {
        Object service=assignmentService(); role(NURSE,"nurse");
        Object controller=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.controller.CareNurseAssignmentController")
                .getConstructor(service.getClass()).newInstance(service));
        Object advice=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.controller.CarePlanExceptionAdvice").getConstructor().newInstance());
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice,new org.familyhealthcare.common.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/care-nurse-assignments")
                .requestAttr("userId",OWNER).contentType("application/json").content("{\"patientId\":1,\"nurseUserId\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(403))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.errorCode").value("ACCESS_DENIED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/care-nurse-assignments")
                .requestAttr("userId",ADMIN).contentType("application/json").content("{\"patientId\":1,\"nurseUserId\":10,\"actorId\":11}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/care-nurse-assignments").param("patientId","1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/care-nurse-assignments").requestAttr("userId",OWNER).param("patientId","bad"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/care-nurse-assignments")
                .requestAttr("userId",ADMIN).contentType("application/json").content("{\"patientId\":1,\"nurseUserId\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.patientId").value(1));
    }

    @Test void disabledSpringRegistrationHasAuthorizationInterfaceButNoNewEndpointsOrTableQueries() {
        f.jdbc().execute("DROP TABLE care_nurse_assignment");
        org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        try {
            context.registerBean(JdbcTemplate.class,()->f.jdbc());
            Class<?> assignmentType=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.service.careplan.CareNurseAssignmentService"));
            Class<?> controllerType=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.controller.CareNurseAssignmentController"));
            context.register(CarePlanProperties.class,auth.getClass(),assignmentType,controllerType); context.refresh();
            Object registered=context.getBean(auth.getClass());
            assertTrue(context.getBeansOfType(assignmentType).isEmpty());
            assertTrue(context.getBeansOfType(controllerType).isEmpty());
            assertEquals("CarePlanException",assertThrows(RuntimeException.class,()->call(registered,"requireRead",OWNER,PATIENT)).getClass().getSimpleName());
        } finally { context.close(); }
    }

    @Test void revokeApiRejectsClientActorEvenForAuthorizedAdministrator() throws Exception {
        Object service=assignmentService(); role(NURSE,"nurse");
        Map<String,Object> row=assign(service,ADMIN,body("patientId",PATIENT,"nurseUserId",NURSE));
        Object controller=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.controller.CareNurseAssignmentController")
                .getConstructor(service.getClass()).newInstance(service));
        Object advice=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.controller.CarePlanExceptionAdvice").getConstructor().newInstance());
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/care-nurse-assignments/"+row.get("id")+"/revoke")
                .requestAttr("userId",ADMIN).contentType("application/json").content("{\"actorId\":11}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertEquals("ACTIVE",f.jdbc().queryForObject("SELECT status FROM care_nurse_assignment WHERE id=?",String.class,row.get("id")));
    }

    @Test void assignmentUtcMicrosecondTimesSurviveNonUtcJvmAndExpiryBoundary() {
        TimeZone previous=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            Object service=assignmentService(); role(NURSE,"nurse");
            Map<String,Object> row=assign(service,ADMIN,body("patientId",PATIENT,"nurseUserId",NURSE,"expiresAt","2026-10-03T14:00:00.000001+09:00"));
            assertEquals("2026-10-03T05:00:00.000001Z",row.get("expiresAt"));
            grant(NURSE,"NURSE","WRITE","CARE_PLAN"); allow("requireRead",NURSE,PATIENT);
            f.advance(Duration.ofNanos(1000)); deny("requireRead",NURSE,PATIENT);
        } finally { TimeZone.setDefault(previous); }
    }

    @Test void carePlanAdviceDoesNotChangeLegacyControllerHttpErrorSemantics() throws Exception {
        org.familyhealthcare.controller.CareJourneyController controller=new org.familyhealthcare.controller.CareJourneyController();
        ReflectionTestUtils.setField(controller,"service",journey());
        Object advice=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.controller.CarePlanExceptionAdvice").getConstructor().newInstance());
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice,new org.familyhealthcare.common.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/care-journey/access-grants")
                .requestAttr("userId",OWNER).contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400));
    }

    @Test void anyExplicitCarePlanGrantRequiresActualOwnerRegardlessOfRoleLabel() {
        CareJourneyService journey=journey(); role(NURSE,"nurse"); assignment(null);
        Map<String,Object> request=body("patientId",PATIENT,"granteeUserId",NURSE,"granteeRole","FAMILY","accessLevel","WRITE","visibleModules","CARE_PLAN");
        f.as(ADMIN);
        assertThrows(IllegalStateException.class,()->journey.saveGrant(request));
        assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM care_access_grant",Integer.class));
        request.put("granteeUserId",FAMILY); request.put("granteeRole","GUARDIAN");
        assertThrows(IllegalStateException.class,()->journey.saveGrant(request));
        request.put("visibleModules","MEDICATION");
        assertDoesNotThrow(()->journey.saveGrant(request));
        f.as(OWNER); request.put("visibleModules","CARE_PLAN");
        assertDoesNotThrow(()->journey.saveGrant(request)); allow("requireRead",FAMILY,PATIENT);
    }

    @Test void newGrantMessagesHaveMirroredChineseTranslationsAndStable403Code() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        try {
            assertEquals("护理授权字段无效。",org.familyhealthcare.common.Result.error(400,"Invalid nurse grant fields.").getMsg());
            assertEquals("护理授权必须明确包含 CARE_PLAN。",org.familyhealthcare.common.Result.error(400,"A nurse grant must explicitly include CARE_PLAN.").getMsg());
            org.familyhealthcare.common.Result<?> denied=org.familyhealthcare.common.Result.error(403,"Access denied to this care-plan grant.");
            assertEquals(403,denied.getCode()); assertEquals("无权管理该照护计划授权。",denied.getMsg());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

    @Test void ownerIssuedCaregiverLabelStillRequiresNurseRoleAndAssignment() {
        role(NURSE,"nurse"); role(NURSE,"family");
        f.as(OWNER); journey().saveGrant(body("patientId",PATIENT,"granteeUserId",NURSE,"granteeRole","FAMILY","accessLevel","WRITE","visibleModules","CARE_PLAN"));
        deny("requireRead",NURSE,PATIENT);
        assignment(null); allow("requireRead",NURSE,PATIENT); allow("requireRecord",NURSE,PATIENT);
        deny("requireClinical",NURSE,PATIENT);
        f.jdbc().update("UPDATE care_nurse_assignment SET status='REVOKED'");
        deny("requireRead",NURSE,PATIENT);
    }

    @Test void explicitCarePlanGrantRejectsForgedActorAndFractionalIdsForEveryLabel() {
        f.as(OWNER); CareJourneyService journey=journey();
        Map<String,Object> request=body("patientId",PATIENT,"granteeUserId",FAMILY,"granteeRole","FAMILY","accessLevel","READ","visibleModules","CARE_PLAN","actorId",ADMIN);
        Map<String,Object> fractional=new LinkedHashMap<>(request); fractional.remove("actorId"); fractional.put("patientId",1.5);
        assertAll(
                ()->assertThrows(IllegalArgumentException.class,()->journey.saveGrant(request)),
                ()->assertThrows(IllegalArgumentException.class,()->journey.saveGrant(fractional)),
                ()->assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM care_access_grant",Integer.class)));
    }

    @ParameterizedTest
    @CsvSource({"FAMILY,ACTIVE", "FAMILY,REVOKED", "FAMILY,EXPIRED", "GUARDIAN,ACTIVE", "GUARDIAN,REVOKED", "GUARDIAN,EXPIRED"})
    void nurseRoleAssociationRemovalNeverFallsBackToCaregiverGrant(String label,String assignmentState) {
        role(NURSE,"nurse"); role(NURSE,"family"); assignment(f.now().plusSeconds(10));
        f.as(OWNER); journey().saveGrant(body("patientId",PATIENT,"granteeUserId",NURSE,"granteeRole",label,"accessLevel","WRITE","visibleModules","CARE_PLAN"));
        allow("requireRead",NURSE,PATIENT); allow("requireRecord",NURSE,PATIENT);
        f.jdbc().update("DELETE FROM sys_user_role WHERE user_id=? AND role_id IN (SELECT id FROM sys_role WHERE role_code='nurse')",NURSE);
        if("REVOKED".equals(assignmentState))f.jdbc().update("UPDATE care_nurse_assignment SET status='REVOKED',revoked_at=?",utc(f.now()));
        if("EXPIRED".equals(assignmentState))f.advance(Duration.ofSeconds(10));
        f.as(NURSE);
        assertAll(
                ()->deny("requireRead",NURSE,PATIENT),
                ()->deny("requireRecord",NURSE,PATIENT),
                ()->deny("requireNursing",NURSE,PATIENT),
                ()->deny("requireClinical",NURSE,PATIENT));
        // Nursing history is patient-scoped and cannot replace or restrict a different patient's family consent.
        f.as(FAMILY); journey().saveGrant(body("patientId",OTHER_PATIENT,"granteeUserId",NURSE,"granteeRole",label,"accessLevel","WRITE","visibleModules","CARE_PLAN"));
        allow("requireRead",NURSE,OTHER_PATIENT); allow("requireRecord",NURSE,OTHER_PATIENT);
        // Provenance selects the restrictive nurse gate; actual owner / valid assigned doctor remain exceptions.
        allow("requireRead",OWNER,PATIENT);
        role(NURSE,"doctor");
        f.jdbc().update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(10,1,11)");
        allow("requireClinical",NURSE,PATIENT); allow("requireRead",NURSE,PATIENT);
    }

    private CareJourneyService journey() {
        CareJourneyService service=new CareJourneyService(); ReflectionTestUtils.setField(service,"jdbc",f.jdbc());
        ReflectionTestUtils.setField(service,"carePlanAuthorization",auth);
        return service;
    }
    private Object assignmentService() {
        CareNurseAssignmentService service=new CareNurseAssignmentService(f.jdbc(),auth);
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(f.now(),ZoneOffset.UTC)); return service;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> assign(Object service,long actor,Map<String,Object> request) {
        return (Map<String,Object>)invoke(service,"assign",new Class<?>[]{long.class,Map.class},actor,request);
    }
    private static Map<String,Object> body(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]); return result;
    }

    private Object call(Object target, String method, long actor, long patient) {
        return invoke(target, method, new Class<?>[]{long.class,long.class}, actor, patient);
    }
    private Object invoke(Object target, String method, Class<?>[] types, Object... args) {
        try { return target.getClass().getMethod(method, types).invoke(target, args); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause(); throw new AssertionError(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void allow(String method, long actor, long patient) { assertDoesNotThrow(() -> call(auth, method, actor, patient)); }
    private void deny(String method, long actor, long patient) {
        CarePlanException ex = assertThrows(CarePlanException.class, () -> call(auth, method, actor, patient));
        assertEquals(403,ex.getStatus()); assertEquals("ACCESS_DENIED",ex.getErrorCode());
    }
    private boolean evidence(long actor,long patient,String type,long id) {
        return (boolean)invoke(auth,"canReadEvidence",new Class<?>[]{long.class,long.class,String.class,long.class},actor,patient,type,id);
    }
    private void role(long actor,String role) { f.jdbc().update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?", actor,role); }
    private void grant(long actor,String role,String level,String modules) {
        f.jdbc().update("DELETE FROM care_access_grant WHERE patient_id=1 AND grantee_user_id=?",actor);
        f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,granted_by) VALUES(1,?,?,?,?,7)",actor,role,level,modules);
    }
    private void assignment(Instant expires) { f.jdbc().update("INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,assigned_by,assigned_at,expires_at) VALUES(1,10,11,?,?)",utc(f.now()),expires==null?null:utc(expires)); }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private void seedEvidence() {
        f.jdbc().update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(1,1,7,'WEIGHT',60,'kg',?),(2,2,8,'WEIGHT',65,'kg',?)",utc(f.now()),utc(f.now()));
        f.jdbc().update("INSERT INTO medical_record(id,patient_name,patient_id,user_id) VALUES(1,'Synthetic Patient',1,7),(2,'Other Synthetic Patient',2,8)");
    }
}
