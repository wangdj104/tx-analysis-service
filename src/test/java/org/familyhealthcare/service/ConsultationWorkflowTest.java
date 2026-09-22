package org.familyhealthcare.service;

import org.familyhealthcare.util.DataScopeHelper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsultationWorkflowTest {
    JdbcTemplate jdbc;
    ConsultationService service;
    CareJourneyService journey;
    DataScopeHelper scope;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:consultation" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE patient(id BIGINT PRIMARY KEY,name VARCHAR(50),user_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,username VARCHAR(50),real_name VARCHAR(50),status INT DEFAULT 1,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_role(id BIGINT PRIMARY KEY,role_code VARCHAR(30),status INT DEFAULT 1)");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        jdbc.execute("CREATE TABLE care_member(patient_id BIGINT,user_id BIGINT,relation_name VARCHAR(30))");
        jdbc.execute("CREATE TABLE consultation(id BIGINT AUTO_INCREMENT PRIMARY KEY,patient_id BIGINT,doctor_user_id BIGINT,mode VARCHAR(20),status VARCHAR(20),symptom VARCHAR(500),duration_text VARCHAR(100),medical_history TEXT,family_visibility VARCHAR(20),created_by BIGINT,started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,ended_at TIMESTAMP,transfer_advice VARCHAR(1000),archived_event_id BIGINT)");
        jdbc.execute("CREATE TABLE consultation_participant(id BIGINT AUTO_INCREMENT PRIMARY KEY,consultation_id BIGINT,user_id BIGINT,participant_role VARCHAR(20),UNIQUE(consultation_id,user_id))");
        jdbc.execute("CREATE TABLE consultation_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,consultation_id BIGINT,sender_user_id BIGINT,message_type VARCHAR(20),content TEXT,attachment_record_id BIGINT,sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE consultation_signal(id BIGINT AUTO_INCREMENT PRIMARY KEY,consultation_id BIGINT,sender_user_id BIGINT,target_user_id BIGINT,signal_type VARCHAR(20),payload_json TEXT)");
        jdbc.execute("CREATE TABLE health_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,patient_id BIGINT,event_date DATE,event_time VARCHAR(5),event_type VARCHAR(30),title VARCHAR(120),summary VARCHAR(500),source_type VARCHAR(30),source_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE medical_record(id BIGINT PRIMARY KEY,patient_id BIGINT,record_date DATE,record_type VARCHAR(30),hospital_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE medical_record_attachment(id BIGINT PRIMARY KEY,record_id BIGINT,file_name VARCHAR(200),file_type VARCHAR(20),file_content TEXT)");
        jdbc.update("INSERT INTO patient(id,name,user_id) VALUES(1,'Patient A',7),(2,'Patient B',8)");
        jdbc.update("INSERT INTO sys_user(id,username,real_name) VALUES(7,'patient','Patient'),(9,'doctor','Doctor'),(10,'otherDoctor','Other Doctor'),(11,'caregiver','Caregiver')");
        jdbc.update("INSERT INTO sys_role(id,role_code) VALUES(1,'doctor')");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(9,1),(10,1)");
        journey = mock(CareJourneyService.class);
        scope = mock(DataScopeHelper.class);
        service = new ConsultationService();
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "journey", journey);
        ReflectionTestUtils.setField(service, "scope", scope);
        login(7L, "patient");
    }

    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void selectedDoctorDiscoversExistingAndNewConsultationsWithoutFullRecordAccess() {
        Long id = start("VISIBLE");
        login(9L, "doctor");
        doThrow(new IllegalStateException("No full record grant")).when(journey).requireRead(1L, "CONSULTATION");
        doThrow(new IllegalStateException("No full record grant")).when(scope).requirePatientAccess(1L,"MEDICAL",false);
        assertEquals("Patient A", service.inbox("OPEN").get(0).get("patient_name"));
        assertEquals(Boolean.TRUE, service.detail(id).get("can_message"));
        service.message(id, map("content", "How long have the symptoms lasted?"));
        assertTrue(service.recordOptions(id).isEmpty());
        login(7L, "patient");
        assertEquals(1, ((List<?>)service.detail(id).get("messages")).size());
        verify(journey, never()).requireRead(1L, "CONSULTATION");
    }

    @Test void unrelatedDoctorCannotReadOrJoinPrivateConsultationEvenWithPatientModuleAccess() {
        Long id = start("PRIVATE");
        login(10L, "doctor");
        assertThrows(IllegalStateException.class, () -> service.detail(id));
        assertThrows(IllegalStateException.class, () -> service.message(id, map("content", "intrusion")));
        assertTrue(service.inbox(null).isEmpty());
        login(9L, "doctor");
        assertNotNull(service.detail(id));
    }

    @Test void readOnlyCaregiverCannotSendCloseOrReadCallSignalsUnlessInvited() {
        Long id = start("VISIBLE");
        login(11L, "family");
        assertEquals(Boolean.FALSE, service.detail(id).get("can_message"));
        assertThrows(IllegalStateException.class, () -> service.message(id, map("content", "hello")));
        assertThrows(IllegalStateException.class, () -> service.close(id, Collections.emptyMap()));
        assertThrows(IllegalStateException.class, () -> service.signals(id, 0L));
    }

    @Test void doctorSelectionMustBeValidAndMessagesMustHaveValidContent() {
        Map<String,Object> invalid = request("VISIBLE");
        invalid.put("doctorUserId", 11L);
        assertThrows(IllegalArgumentException.class, () -> service.start(invalid));
        Long id = start("VISIBLE");
        assertThrows(IllegalArgumentException.class, () -> service.message(id, map("content", "   ")));
        assertThrows(IllegalArgumentException.class, () -> service.message(id, map("content", "fake", "messageType", "SYSTEM")));
        assertThrows(IllegalArgumentException.class, () -> service.message(id, map("content", "image", "messageType", "IMAGE")));
    }

    @Test void archiveIsIdempotentAndPrivateSymptomsNeverReachSharedTimeline() {
        Long id = start("PRIVATE");
        login(9L, "doctor");
        service.close(id, map("transferAdvice", "Discuss with your usual clinician."));
        service.close(id, Collections.emptyMap());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM health_event", Integer.class));
        assertNull(jdbc.queryForObject("SELECT summary FROM health_event", String.class));
        assertEquals(7L, jdbc.queryForObject("SELECT user_id FROM health_event", Long.class));
        assertThrows(IllegalStateException.class, () -> service.message(id, map("content", "too late")));
        assertThrows(IllegalStateException.class, () -> service.signal(id, map("targetUserId", 7L, "signalType", "HANGUP")));
        assertTrue(service.signals(id, 0L).isEmpty());
    }

    @Test void onlyRecordsFromThisPatientCanBeSharedAndOnlySharedFilesCanBeDownloaded() {
        Long id = start("VISIBLE");
        jdbc.update("INSERT INTO medical_record(id,patient_id,record_type) VALUES(20,1,'IMAGE'),(21,2,'BLOOD'),(22,1,'BLOOD')");
        jdbc.update("INSERT INTO medical_record_attachment(id,record_id,file_name,file_type,file_content) VALUES(30,20,'scan.png','IMAGE','aGVsbG8='),(31,22,'private.pdf','PDF','aGVsbG8=')");
        assertThrows(IllegalArgumentException.class, () -> service.message(id, map("attachmentRecordId", 21L, "messageType", "FILE")));
        service.message(id, map("attachmentRecordId", 20L, "messageType", "IMAGE"));
        login(9L, "doctor");
        doThrow(new IllegalStateException("No full record grant")).when(scope).requirePatientAccess(1L,"MEDICAL",false);
        assertEquals("aGVsbG8=", service.attachment(id, 30L).get("file_content"));
        assertThrows(IllegalArgumentException.class, () -> service.attachment(id, 31L));
        assertThrows(IllegalStateException.class, () -> service.message(id, map("attachmentRecordId", 22L, "messageType", "FILE")));
    }

    @Test void revokedFamilyAccessStopsAnInvitedParticipantAndSignalTargetsAreValidated() {
        jdbc.update("INSERT INTO care_member(patient_id,user_id) VALUES(1,11)");
        assertEquals(11L,service.invitees(1L).get(0).get("user_id"));
        Map<String,Object> request = request("VISIBLE");
        request.put("participantUserIds", Collections.singletonList(11L));
        Long id = ((Number)service.start(request).get("id")).longValue();
        assertThrows(IllegalArgumentException.class, () -> service.signal(id, map("targetUserId", 10L, "signalType", "OFFER")));
        login(11L, "family");
        assertEquals(Boolean.TRUE, service.detail(id).get("can_message"));
        service.message(id, map("content", "I can help."));
        doThrow(new IllegalStateException("Access revoked")).when(journey).requireRead(1L, "CONSULTATION");
        assertThrows(IllegalStateException.class, () -> service.detail(id));
        assertTrue(service.inbox(null).isEmpty());
    }

    private Long start(String visibility) { return ((Number)service.start(request(visibility)).get("id")).longValue(); }
    private Map<String,Object> request(String visibility) { return map("patientId", 1L, "doctorUserId", 9L, "mode", "TEXT", "symptom", "Sensitive symptom", "familyVisibility", visibility); }
    private static Map<String,Object> map(Object... entries) { Map<String,Object> out = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) out.put((String)entries[i], entries[i+1]); return out; }
    private void login(Long id, String role) { MockHttpServletRequest request = new MockHttpServletRequest(); request.setAttribute("userId", id); request.setAttribute("roleCodes", Collections.singletonList(role)); RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); }
}
