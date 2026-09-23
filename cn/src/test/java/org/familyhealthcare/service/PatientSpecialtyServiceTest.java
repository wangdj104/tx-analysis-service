package org.familyhealthcare.service;

import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.util.CurrentUserUtil;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatientSpecialtyServiceTest {
    private JdbcTemplate jdbc;
    private PatientSpecialtyService service;
    private DataScopeHelper scope;

    @BeforeEach void setup() {
        JdbcDataSource data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:specialty" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE patient(id BIGINT PRIMARY KEY,user_id BIGINT,deleted INT DEFAULT 0,status INT DEFAULT 1)");
        jdbc.execute("CREATE TABLE sys_role(id BIGINT PRIMARY KEY,role_code VARCHAR(50),role_name VARCHAR(50),status INT,deleted INT)");
        jdbc.execute("CREATE TABLE patient_specialty_role(patient_id BIGINT,role_id BIGINT,assigned_by BIGINT,PRIMARY KEY(patient_id,role_id))");
        jdbc.execute("CREATE TABLE sys_menu(id BIGINT PRIMARY KEY,menu_path VARCHAR(100),status INT)");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)");
        jdbc.update("INSERT INTO patient VALUES(1,7,0,1)");
        jdbc.update("INSERT INTO sys_role VALUES(101,'specialty_dialysis','Dialysis',1,0),(102,'doctor','Doctor',1,0)");
        jdbc.update("INSERT INTO sys_menu VALUES(1,'/dialysis',1),(13,'/dry-weight',1)");
        jdbc.update("INSERT INTO sys_role_menu VALUES(101,1),(101,13)");
        scope = mock(DataScopeHelper.class);
        when(scope.requireUserId()).thenAnswer(invocation -> CurrentUserUtil.getCurrentUserId());
        service = new PatientSpecialtyService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "scope", scope);
        request(7L);
    }

    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void patientRolesControlMenusAndCanBeCleared() {
        service.replaceRoles(1L, Collections.singletonList(101L));
        assertEquals(Collections.singletonList(101L), service.roleIds(1L));
        Map<String,Object> scopeResult = service.menuScope(1L);
        assertEquals(Arrays.asList("/dialysis", "/dry-weight"), scopeResult.get("restrictedPaths"));
        assertEquals(Arrays.asList("/dialysis", "/dry-weight"), scopeResult.get("allowedPaths"));
        service.replaceRoles(1L, Collections.emptyList());
        assertTrue(service.roleIds(1L).isEmpty());
        assertTrue(((java.util.List<?>) service.menuScope(1L).get("allowedPaths")).isEmpty());
    }

    @Test void onlyOwnerOrAdminCanAssignAndOnlySpecialtyRolesAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> service.replaceRoles(1L, Collections.singletonList(102L)));
        request(8L);
        assertThrows(IllegalStateException.class, () -> service.replaceRoles(1L, Collections.singletonList(101L)));
        assertTrue(service.roleIds(1L).isEmpty());
    }

    @Test void anotherPatientsMenuScopeRequiresPatientAccess() {
        doThrow(new IllegalStateException("Access denied")).when(scope).requirePatient(2L);
        assertThrows(IllegalStateException.class, () -> service.menuScope(2L));
    }

    private void request(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", userId);
        request.setAttribute("roleCodes", Collections.singletonList("patient"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
