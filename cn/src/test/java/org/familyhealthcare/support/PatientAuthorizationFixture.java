package org.familyhealthcare.support;

import org.familyhealthcare.entity.Patient;
import org.familyhealthcare.mapper.PatientMapper;
import org.familyhealthcare.service.NotificationAudienceService;
import org.familyhealthcare.util.DataScopeHelper;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;
import static org.mockito.Mockito.*;

/** Isolated synthetic patients and real grant SQL; never opens an application database. */
public final class PatientAuthorizationFixture implements AutoCloseable {
    public final JdbcTemplate jdbc;
    public final PatientMapper patients = mock(PatientMapper.class);
    public final DataScopeHelper scope = new DataScopeHelper();
    public final NotificationAudienceService audience = new NotificationAudienceService();

    public PatientAuthorizationFixture() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:boundary" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE patient(id BIGINT PRIMARY KEY,user_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status INT DEFAULT 1,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_role(id BIGINT PRIMARY KEY,role_code VARCHAR(30),status INT DEFAULT 1,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        jdbc.execute("CREATE TABLE care_member(patient_id BIGINT,user_id BIGINT,access_level VARCHAR(20),visible_modules VARCHAR(200))");
        jdbc.execute("CREATE TABLE doctor_patient_assignment(patient_id BIGINT,doctor_user_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE care_access_grant(patient_id BIGINT,grantee_user_id BIGINT,access_level VARCHAR(20),visible_modules VARCHAR(200),status VARCHAR(20),expires_at TIMESTAMP)");
        jdbc.update("INSERT INTO sys_user(id) VALUES(7),(8),(9),(11),(12)");
        addPatient(1L, 7L); addPatient(2L, 9L);
        when(patients.selectById(any())).thenAnswer(call -> {
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM patient WHERE id=?", (Object) call.getArgument(0));
            if (rows.isEmpty()) return null;
            Map<String,Object> row = rows.get(0);
            Patient p = new Patient(); p.setId(((Number)row.get("id")).longValue());
            p.setUserId(((Number)row.get("user_id")).longValue()); p.setDeleted(((Number)row.get("deleted")).intValue()); p.setName("Synthetic patient"); return p;
        });
        ReflectionTestUtils.setField(scope, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(scope, "patientMapper", patients);
        ReflectionTestUtils.setField(audience, "jdbc", jdbc);
        actor(11L);
    }
    public void actor(Long id) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/unrelated/read-only-route");
        request.setAttribute("userId", id); request.setAttribute("roleCodes", Collections.singletonList("family"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    public void addPatient(Long id, Long owner) { jdbc.update("INSERT INTO patient(id,user_id) VALUES(?,?)", id, owner); }
    public void member(Long patient, Long user) { jdbc.update("INSERT INTO care_member VALUES(?,?,'WRITE',NULL)", patient, user); }
    public void grant(Long patient, Long user, String level, String module, String status, boolean expired) {
        jdbc.update("INSERT INTO care_access_grant VALUES(?,?,?,?,?,?)", patient, user, level, module, status,
                expired ? java.sql.Timestamp.valueOf("2000-01-01 00:00:00") : null);
    }
    public void doctor(Long patient, Long user) {
        jdbc.update("INSERT INTO sys_role VALUES(201,'doctor',1,0)"); jdbc.update("INSERT INTO sys_user_role VALUES(?,201)", user);
        jdbc.update("INSERT INTO doctor_patient_assignment VALUES(?,?,'ACTIVE')", patient, user);
    }
    @Override public void close() { RequestContextHolder.resetRequestAttributes(); jdbc.execute("DROP ALL OBJECTS"); }
}
