package org.familyhealthcare.service;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NotificationAudienceScopeTest {
    @Test void notificationsRespectModuleRestrictionsRevocationExpiryAndDisabledUsers() {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:audience"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc=new JdbcTemplate(ds);
        // Hold a connection so this isolated in-memory database stays alive.
        ds.setURL(ds.getURL()+";DB_CLOSE_DELAY=-1");
        jdbc.execute("CREATE TABLE patient(id BIGINT,user_id BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT,status INT,deleted INT)");
        jdbc.execute("CREATE TABLE care_member(patient_id BIGINT,user_id BIGINT,visible_modules VARCHAR(100))");
        jdbc.execute("CREATE TABLE doctor_patient_assignment(patient_id BIGINT,doctor_user_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE care_access_grant(patient_id BIGINT,grantee_user_id BIGINT,visible_modules VARCHAR(100),status VARCHAR(20),expires_at TIMESTAMP)");
        jdbc.update("INSERT INTO patient VALUES(1,7,0)");
        jdbc.update("INSERT INTO sys_user VALUES(7,1,0),(8,1,0),(9,1,0),(10,1,0),(11,1,0),(12,0,0)");
        jdbc.update("INSERT INTO doctor_patient_assignment VALUES(1,9,'ACTIVE')");
        jdbc.update("INSERT INTO care_member VALUES(1,8,NULL),(1,10,NULL),(1,11,NULL),(1,12,NULL)");
        jdbc.update("INSERT INTO care_access_grant VALUES(1,8,'MEDICATION','ACTIVE',NULL),(1,10,NULL,'REVOKED',NULL),(1,11,NULL,'ACTIVE',TIMESTAMP '2000-01-01 00:00:00')");
        NotificationAudienceService service=new NotificationAudienceService();ReflectionTestUtils.setField(service,"jdbc",jdbc);
        assertEquals(new HashSet<>(Arrays.asList(7L,8L,9L)),service.recipients(1L,"PRESCRIPTION_UPDATED"));
        assertEquals(new HashSet<>(Arrays.asList(7L,9L)),service.recipients(1L,"MEASUREMENT_ALERT"));
        assertEquals(new HashSet<>(Arrays.asList(7L,9L)),service.recipients(1L));
        jdbc.update("INSERT INTO care_access_grant VALUES(1,8,'EMERGENCY','ACTIVE',NULL)");
        assertTrue(service.recipients(1L).contains(8L));
    }
}
