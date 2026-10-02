package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.familyhealthcare.common.Result;
import org.familyhealthcare.controller.NutritionAssessmentController;
import org.familyhealthcare.entity.NutritionAssessment;
import org.familyhealthcare.mapper.NutritionAssessmentMapper;
import org.familyhealthcare.mapper.PatientMapper;
import org.familyhealthcare.service.impl.NutritionAssessmentServiceImpl;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic patients only: real authorization, controller, service, and mapper SQL. */
class NutritionAssessmentAuthorizationTest {
    private CareDatabaseWorkflowTest database;
    private JdbcTemplate jdbc;
    private NutritionAssessmentMapper assessments;
    private NutritionAssessmentServiceImpl service;
    private NutritionAssessmentController controller;

    @BeforeEach
    void setup() throws Exception {
        database = new CareDatabaseWorkflowTest();
        database.setup();
        jdbc = database.jdbc;
        jdbc.update("INSERT INTO patient(id,name,user_id) VALUES(2,'Other synthetic patient',8)");
        jdbc.update("INSERT INTO nutrition_assessment(id,patient_id,user_id,assessment_date,remark) "
                + "VALUES(10,1,7,'2026-09-22','Original assessment')");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(NutritionAssessmentMapper.class);
        configuration.addMapper(PatientMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(database.ds);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        assessments = session.getMapper(NutritionAssessmentMapper.class);
        DataScopeHelper scope = new DataScopeHelper();
        ReflectionTestUtils.setField(scope, "patientMapper", session.getMapper(PatientMapper.class));
        ReflectionTestUtils.setField(scope, "jdbcTemplate", jdbc);

        service = new NutritionAssessmentServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", assessments);
        ReflectionTestUtils.setField(service, "dataScopeHelper", scope);
        controller = new NutritionAssessmentController();
        ReflectionTestUtils.setField(controller, "nutritionAssessmentService", service);
    }

    @AfterEach
    void cleanup() {
        database.cleanup();
    }

    @Test
    void detailDoesNotExposeAnotherPatientsAssessmentById() {
        request(8L, "family", "GET", "/api/nutrition/detail/10");

        Result<NutritionAssessment> response = controller.detail(10L);

        assertEquals(403, response.getCode());
        assertNull(response.getData());
    }

    @Test
    void anOwnedDestinationPatientCannotAuthorizeOverwritingAnotherPatientsAssessment() {
        request(8L, "family", "POST", "/api/nutrition/save");

        assertEquals(403, controller.save(edit(2L)).getCode());

        assertOriginalAssessment();
    }

    @Test
    void unassignedDoctorCannotReadAnotherPatientsAssessment() {
        request(11L, "doctor", "GET", "/api/nutrition/detail/10");

        assertEquals(403, controller.detail(10L).getCode());
    }

    @Test
    void assignedDoctorCanReadAndEditWithoutTakingOwnership() {
        jdbc.update("INSERT INTO doctor_patient_assignment(patient_id,doctor_user_id,status) VALUES(1,11,'ACTIVE')");
        request(11L, "doctor", "GET", "/api/nutrition/detail/10");
        assertEquals(1L, controller.detail(10L).getData().getPatientId());
        request(11L, "doctor", "POST", "/api/nutrition/save");

        assertTrue(service.saveOrUpdateAssessment(edit(1L)));

        NutritionAssessment saved = assessments.selectById(10L);
        assertEquals(1L, saved.getPatientId());
        assertEquals(7L, saved.getUserId());
        assertEquals("Edited assessment", saved.getRemark());
    }

    @Test
    void editingCannotMoveAnAssessmentBetweenTwoAccessiblePatients() {
        jdbc.update("UPDATE patient SET user_id=7 WHERE id=2");
        request(7L, "family", "POST", "/api/nutrition/save");

        assertThrows(IllegalArgumentException.class, () -> service.saveOrUpdateAssessment(edit(2L)));

        assertOriginalAssessment();
    }

    @Test
    void patientMismatchIsReportedAsABadRequestWithoutSaving() {
        jdbc.update("UPDATE patient SET user_id=7 WHERE id=2");
        request(7L, "family", "POST", "/api/nutrition/save");

        assertEquals(400, controller.save(edit(2L)).getCode());

        assertOriginalAssessment();
    }

    @Test
    void readOnlyGrantAllowsDetailButCannotBeBypassedUsingAnOwnedDestination() {
        grant(8L, "READ", null, "ACTIVE");
        request(8L, "family", "GET", "/api/nutrition/detail/10");
        assertEquals(200, controller.detail(10L).getCode());
        request(8L, "family", "POST", "/api/nutrition/save");

        assertEquals(403, controller.save(edit(2L)).getCode());

        assertOriginalAssessment();
    }

    @Test
    void writeGrantRetainsTheStoredOwnerDuringAnAuthorizedEdit() {
        grant(8L, "WRITE", null, "ACTIVE");
        request(8L, "family", "POST", "/api/nutrition/save");

        assertEquals(200, controller.save(edit(1L)).getCode());

        assertEquals(7L, assessments.selectById(10L).getUserId());
    }

    @Test
    void readOnlyGrantCannotEditTheSamePatient() {
        grant(8L, "READ", null, "ACTIVE");
        request(8L, "family", "POST", "/api/nutrition/save");

        assertEquals(403, controller.save(edit(1L)).getCode());

        assertOriginalAssessment();
    }

    @Test
    void restrictedOrRevokedGrantsDoNotExposeAssessmentDetails() {
        grant(8L, "WRITE", "MEDICATION", "ACTIVE");
        request(8L, "family", "GET", "/api/nutrition/detail/10");
        assertEquals(403, controller.detail(10L).getCode());

        jdbc.update("UPDATE care_access_grant SET visible_modules=NULL,status='REVOKED'");

        assertEquals(403, controller.detail(10L).getCode());
    }

    @Test
    void recordCreatorDoesNotBypassRevokedPatientAccess() {
        jdbc.update("UPDATE nutrition_assessment SET user_id=8 WHERE id=10");
        request(8L, "family", "GET", "/api/nutrition/detail/10");

        assertEquals(403, controller.detail(10L).getCode());
    }

    @Test
    void patientlessLegacyDetailRemainsLimitedToItsOwner() {
        jdbc.update("UPDATE nutrition_assessment SET patient_id=NULL WHERE id=10");
        request(7L, "family", "GET", "/api/nutrition/detail/10");
        assertEquals(200, controller.detail(10L).getCode());
        request(8L, "family", "GET", "/api/nutrition/detail/10");

        assertEquals(403, controller.detail(10L).getCode());
    }

    @Test
    void missingAssessmentUpdateDoesNotInsertANewRecord() {
        request(7L, "family", "POST", "/api/nutrition/save");
        NutritionAssessment missing = edit(1L);
        missing.setId(999L);

        assertFalse(service.saveOrUpdateAssessment(missing));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nutrition_assessment", Integer.class));
        assertOriginalAssessment();
    }

    @Test
    void authorizedCreationUsesCurrentOwnerAndStillCalculatesNutrition() {
        request(7L, "family", "POST", "/api/nutrition/save");
        NutritionAssessment created = edit(1L);
        created.setId(null);
        created.setBodyWeight(new BigDecimal("72"));
        created.setHeight(new BigDecimal("180"));

        assertTrue(service.saveOrUpdateAssessment(created));

        NutritionAssessment saved = assessments.selectById(created.getId());
        assertEquals(7L, saved.getUserId());
        assertEquals(1L, saved.getPatientId());
        assertEquals(new BigDecimal("22.22"), saved.getBmi());
        assertEquals("GOOD", saved.getNutritionStatus());
    }

    @Test
    void unauthorizedCreationDoesNotPersistARecord() {
        request(8L, "family", "POST", "/api/nutrition/save");
        NutritionAssessment created = edit(1L);
        created.setId(null);

        assertEquals(403, controller.save(created).getCode());

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nutrition_assessment", Integer.class));
    }

    @Test
    void ownerCanStillReadAndMissingDetailIsEmpty() {
        request(7L, "family", "GET", "/api/nutrition/detail/10");

        assertEquals("Original assessment", controller.detail(10L).getData().getRemark());
        assertNull(controller.detail(999L).getData());
    }

    private NutritionAssessment edit(Long patientId) {
        NutritionAssessment record = new NutritionAssessment();
        record.setId(10L);
        record.setPatientId(patientId);
        record.setUserId(999L);
        record.setAssessmentDate(LocalDate.of(2026, 9, 22));
        record.setRemark("Edited assessment");
        return record;
    }

    private void assertOriginalAssessment() {
        NutritionAssessment saved = assessments.selectById(10L);
        assertEquals(1L, saved.getPatientId());
        assertEquals(7L, saved.getUserId());
        assertEquals("Original assessment", saved.getRemark());
    }

    private void grant(Long userId, String access, String modules, String status) {
        jdbc.update("INSERT INTO care_access_grant(patient_id,grantee_user_id,access_level,visible_modules,status,granted_by,grantee_role) "
                + "VALUES(1,?,?,?,?,7,'FAMILY')", userId, access, modules, status);
    }

    private void request(Long userId, String role, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute("userId", userId);
        request.setAttribute("roleCodes", Collections.singletonList(role));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
