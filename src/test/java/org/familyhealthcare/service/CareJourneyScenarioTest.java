package org.familyhealthcare.service;

import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.task.CareJourneyTask;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes the care-journey workflows against the actual initialization and upgrade schema. */
class CareJourneyScenarioTest {
    CareDatabaseWorkflowTest database;
    JdbcTemplate jdbc;
    DataScopeHelper scope;
    CareJourneyService service;
    NotificationAudienceService audience;

    @BeforeEach void setup() throws Exception {
        database=new CareDatabaseWorkflowTest(); database.setup(); jdbc=database.jdbc; scope=database.scope;
        jdbc.update("INSERT INTO sys_user(id,username,password,real_name) VALUES(7,'scenario-patient','unused','Patient'),(9,'scenario-doctor','unused','Doctor'),(11,'scenario-family','unused','Family')");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT 9,id FROM sys_role WHERE role_code='doctor'");
        jdbc.update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by,status) VALUES(9,1,7,'ACTIVE')");
        service=new CareJourneyService(); audience=mock(NotificationAudienceService.class);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"audience",audience);
        when(audience.assignedDoctors(1L)).thenReturn(Collections.singletonList(9L));
        when(audience.recipients(1L)).thenAnswer(invocation->new LinkedHashSet<>(Arrays.asList(7L,9L,11L)));
        login(7,"patient");
    }
    @AfterEach void cleanup(){database.cleanup();}

    @Test void measurementsSaveListAndDoctorAnnotatesTheSameRecord() {
        Long id=id(service.saveMeasurement(map("patientId",1L,"metricType","BP","valuePrimary",120,"valueSecondary",80,"unit","mmHg")));
        assertEquals(1,service.measurements(1L,null,null,null).size());
        login(9,"doctor");service.annotateMeasurement(id,"Measurement reviewed");
        assertEquals(1,((List<?>)service.measurements(1L,"BP",null,null).get(0).get("annotations")).size());
        assertThrows(IllegalArgumentException.class,()->service.saveMeasurement(map("patientId",1L,"metricType","SPO2","valuePrimary",120,"unit","%")));
    }

    @Test void glucoseAssessmentConvertsMgDlButPreservesTheEnteredValueAndUnit() {
        Map<String,Object> normal=service.saveMeasurement(map("patientId",1L,"metricType","GLUCOSE","valuePrimary",108,"unit","mg/dL"));
        assertEquals("NORMAL",normal.get("status"));assertEquals("mg/dL",normal.get("unit"));
        assertEquals(108,((Number)normal.get("value_primary")).intValue());
        assertEquals("NORMAL",service.saveMeasurement(map("patientId",1L,"metricType","GLUCOSE","valuePrimary",6,"unit","mmol/L")).get("status"));
        assertEquals("ABNORMAL",service.saveMeasurement(map("patientId",1L,"metricType","GLUCOSE","valuePrimary",54,"unit","mg/dL")).get("status"));
        assertEquals("ABNORMAL",service.saveMeasurement(map("patientId",1L,"metricType","GLUCOSE","valuePrimary",252,"unit","mg/dL")).get("status"));
    }

    @Test void unsupportedClinicalUnitsAreRejectedBeforePersistingOrSendingAlerts() {
        assertThrows(IllegalArgumentException.class,()->service.saveMeasurement(map("patientId",1L,"metricType","BP","valuePrimary",120,"valueSecondary",80,"unit","kPa")));
        assertThrows(IllegalArgumentException.class,()->service.saveMeasurement(map("patientId",1L,"metricType","GLUCOSE","valuePrimary",6,"unit","mg/L")));
        assertThrows(IllegalArgumentException.class,()->service.saveMeasurement(map("patientId",1L,"metricType","TEMPERATURE","valuePrimary",98.6,"unit","F")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM health_measurement",Integer.class));
        verifyNoInteractions(audience);
    }

    @Test void appointmentsReserveRescheduleResetRemindersAndCancelOnlyOnce() {
        LocalDate day=LocalDate.now().plusDays(2);
        login(9,"doctor");Long schedule=id(service.saveDoctorSchedule(map("workDate",day.toString(),"startTime","09:00:00","endTime","12:00:00","slotMinutes",30)));
        assertEquals(1,service.doctorSchedules(9L,day,day).size());
        login(7,"patient");
        Map<String,Object> request=map("patientId",1L,"doctorUserId",9L,"scheduleId",schedule,"startAt",day+"T09:00:00","endAt",day+"T09:30:00","recurrenceDays",7,"nextFollowUpAt",day+"T09:00:00");
        Map<String,Object> saved=service.saveAppointment(request);Long id=id(saved);
        assertEquals(day.plusDays(7),((java.sql.Timestamp)saved.get("next_follow_up_at")).toLocalDateTime().toLocalDate());
        assertThrows(IllegalStateException.class,()->service.saveAppointment(request));
        jdbc.update("UPDATE care_appointment SET notified_at=CURRENT_TIMESTAMP WHERE id=?",id);
        request.put("id",id);request.put("startAt",day+"T10:00:00");request.put("endAt",day+"T10:30:00");
        assertNull(service.saveAppointment(request).get("notified_at"));
        assertEquals(1,service.appointments(1L).size());
        service.cancelAppointment(id,"Changed plans");service.cancelAppointment(id,"Repeated click");
        assertEquals("CANCELLED",service.appointments(1L).get(0).get("status"));
        assertNull(service.appointments(1L).get(0).get("next_follow_up_at"));
        verify(audience,times(1)).notifyCareTeam(eq(1L),eq("APPOINTMENT_CANCELLED"),anyString(),anyString());
        assertThrows(IllegalArgumentException.class,()->service.saveAppointment(request));
        assertEquals("CANCELLED",service.appointments(1L).get(0).get("status"));
    }

    @Test void selectedAppointmentDoctorGetsScopedInboxAndCanCompleteWithoutFullPatientAccess() {
        jdbc.update("INSERT INTO sys_user(id,username,password,real_name) VALUES(13,'appointment-doctor','unused','Appointment Doctor')");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT 13,id FROM sys_role WHERE role_code='doctor'");
        LocalDate day=LocalDate.now().plusDays(3);
        Long appointment=id(service.saveAppointment(map("patientId",1L,"doctorUserId",13L,"startAt",day+"T09:00:00","endAt",day+"T09:30:00")));
        verify(audience).notify(eq(Collections.singleton(13L)),eq(1L),eq("APPOINTMENT_UPDATED"),anyString(),anyString());
        login(13,"doctor");
        List<Map<String,Object>> inbox=ReflectionTestUtils.invokeMethod(service,"appointmentInbox");
        assertEquals(1,inbox.size());assertEquals(appointment,id(inbox.get(0)));assertNotNull(inbox.get(0).get("patient_name"));
        assertThrows(IllegalStateException.class,()->service.requireRead(1L,"MEDICAL"));
        assertThrows(IllegalStateException.class,()->service.appointments(1L));
        login(9,"doctor");assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service,"completeAppointment",appointment));
        login(13,"doctor");ReflectionTestUtils.invokeMethod(service,"completeAppointment",appointment);
        ReflectionTestUtils.invokeMethod(service,"completeAppointment",appointment);
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM care_appointment WHERE id=?",String.class,appointment));
        assertThrows(IllegalArgumentException.class,()->service.cancelAppointment(appointment,"Already completed"));
        login(7,"patient");
        Map<String,Object> reschedule=map("id",appointment,"patientId",1L,"doctorUserId",13L,"startAt",day.plusDays(1)+"T09:00:00","endAt",day.plusDays(1)+"T09:30:00");
        assertThrows(IllegalArgumentException.class,()->service.saveAppointment(reschedule));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM care_appointment WHERE id=?",String.class,appointment));
    }

    @Test void periodicFollowUpsHaveOneRecurrenceOwnerAndDoNotDoubleBookClinicians() {
        LocalDateTime next=LocalDateTime.now().plusHours(2).withSecond(0).withNano(0);
        Long original=id(service.saveAppointment(map("patientId",1L,"doctorUserId",9L,"startAt",next.minusDays(7).toString(),"endAt",next.minusDays(7).plusMinutes(30).toString(),"recurrenceDays",7,"nextFollowUpAt",next.toString())));
        CareJourneyTask task=new CareJourneyTask();ReflectionTestUtils.setField(task,"jdbc",jdbc);ReflectionTestUtils.setField(task,"audience",audience);
        ReflectionTestUtils.invokeMethod(task,"generateFollowUps");ReflectionTestUtils.invokeMethod(task,"generateFollowUps");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM care_appointment",Integer.class));
        assertNull(jdbc.queryForObject("SELECT next_follow_up_at FROM care_appointment WHERE id=?",java.sql.Timestamp.class,original));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM care_appointment WHERE next_follow_up_at IS NOT NULL",Integer.class));
        jdbc.update("INSERT INTO patient(id,name,user_id) VALUES(2,'Other patient',7)");
        jdbc.update("INSERT INTO care_appointment(patient_id,doctor_user_id,start_at,end_at,status,created_by) VALUES(2,9,?,?,'BOOKED',7)",next.plusDays(7),next.plusDays(7).plusMinutes(30));
        // Move the recurrence due marker into the next 24 hours while an overlapping slot already exists.
        Long recurring=jdbc.queryForObject("SELECT id FROM care_appointment WHERE next_follow_up_at IS NOT NULL",Long.class);
        jdbc.update("UPDATE care_appointment SET next_follow_up_at=? WHERE id=?",next.plusHours(2),recurring);
        jdbc.update("UPDATE care_appointment SET start_at=?,end_at=? WHERE patient_id=2",next.plusHours(2),next.plusHours(2).plusMinutes(30));
        ReflectionTestUtils.invokeMethod(task,"generateFollowUps");
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM care_appointment",Integer.class));
        verify(audience).notifyCareTeam(eq(1L),eq("APPOINTMENT_RESCHEDULE_REQUIRED"),anyString(),anyString());
    }

    @Test void draftsStayPrivateAndPublishingCreatesExactlyOneTimelineEntry() {
        login(9,"doctor");Long visit=id(service.saveVisit(map("patientId",1L,"diagnosisSummary","Clinician summary")));
        assertEquals(1,service.visits(1L).size());
        login(7,"patient");assertTrue(service.visits(1L).isEmpty());
        login(9,"doctor");service.publishVisit(visit);service.publishVisit(visit);
        login(7,"patient");assertEquals(1,service.visits(1L).size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM health_event WHERE source_type='VISIT_RECORD'",Integer.class));
        verify(audience,times(1)).notifyCareTeam(eq(1L),eq("VISIT_SUMMARY"),anyString(),anyString());
    }

    @Test void standalonePrescriptionsAreVisibleAndInvalidVersionsDoNotSupersedeCurrentInstructions() {
        login(9,"doctor");Map<String,Object> request=map("patientId",1L,"instructions","Use as directed","items",Collections.singletonList(map("drugName","Example","dosage","1 tablet","frequency","daily","durationDays",7)));
        Long first=id(service.savePrescription(request));
        Map<String,Object> invalid=map("patientId",1L,"items",Collections.singletonList(map("drugName","Invalid")));
        assertThrows(IllegalArgumentException.class,()->service.savePrescription(invalid));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM electronic_prescription WHERE id=?",String.class,first));
        service.savePrescription(request);
        login(7,"patient");List<Map<String,Object>> prescriptions=service.prescriptions(1L);
        assertEquals(2,prescriptions.size());assertEquals("ACTIVE",prescriptions.get(0).get("status"));assertEquals("SUPERSEDED",prescriptions.get(1).get("status"));
        assertEquals(1,((List<?>)prescriptions.get(0).get("items")).size());
    }

    @Test void treatmentAndRehabValidatePlanOwnershipAndCompletion() {
        login(9,"doctor");Long plan=id(service.saveTreatmentPlan(map("patientId",1L,"planType","REHAB","title","Mobility plan","plan",map("instructions","Follow the clinician's instructions"))));
        login(7,"patient");assertEquals(1,service.treatmentPlans(1L).size());
        service.saveRehabCheckin(map("patientId",1L,"planId",plan,"recordType","EXERCISE","completionPercent",50,"data",map("observation","Completed")));
        service.saveRehabCheckin(map("patientId",1L,"recordType","SYMPTOM","abnormal",true,"data",map("observation","Clinician review requested")));
        assertEquals(2,service.rehabCheckins(1L).size());
        assertThrows(IllegalArgumentException.class,()->service.saveRehabCheckin(map("patientId",1L,"planId",999,"recordType","EXERCISE")));
        assertThrows(IllegalArgumentException.class,()->service.saveRehabCheckin(map("patientId",1L,"recordType","EXERCISE","completionPercent",150)));
        verify(audience).notify(eq(Collections.singletonList(9L)),eq(1L),eq("REHAB_ALERT"),anyString(),anyString());
    }

    @Test void emergencySnapshotReadsCurrentSchemaAndOnlyRoutesToBoundRecipients() {
        Map<String,Object> card=service.emergencyCard(1L);assertNotNull(card.get("patient"));
        when(audience.notifyWithDeliveryCount(anyCollection(),eq(1L),eq("EMERGENCY"),anyString(),anyString())).thenReturn(1);
        Map<String,Object> emergency=service.triggerEmergency(map("patientId",1L,"locationText","Home","latitude",31.2,"longitude",121.5));
        assertEquals("TRIGGERED",emergency.get("status"));assertNotNull(emergency.get("snapshot"));
        assertEquals(1,emergency.get("deliveryCount"));
        assertEquals(2,emergency.get("recipientCount"));
        verify(audience).notifyWithDeliveryCount(eq(new LinkedHashSet<>(Arrays.asList(9L,11L))),eq(1L),eq("EMERGENCY"),anyString(),contains("Home"));
        assertEquals(1,service.emergencies(1L).size());
        assertEquals("Home",service.emergency(id(emergency)).get("location_text"));
        login(11,"family");
        assertThrows(IllegalStateException.class,()->service.emergency(id(emergency)));
    }

    @Test void emergencyWithNoWorkingChannelReportsFailureWithoutClaimingDelivery() {
        when(audience.recipients(1L)).thenReturn(new LinkedHashSet<>(Collections.singletonList(7L)));
        Map<String,Object> emergency=service.triggerEmergency(map("patientId",1L,"locationText","Home"));
        assertEquals(0,emergency.get("recipientCount"));
        assertEquals(0,emergency.get("deliveryCount"));
    }

    @Test void growthVaccinationAndMaternityRecordsRoundTrip() {
        String day=LocalDate.now().toString();
        service.saveSpecialty("growth",map("patientId",1L,"recordDate",day,"heightCm",80,"weightKg",10));
        service.saveSpecialty("vaccination",map("patientId",1L,"vaccineName","Recorded vaccine","plannedDate",day));
        service.saveSpecialty("maternity",map("patientId",1L,"recordType","PRENATAL","recordDate",day,"title","Prenatal record","data",map("details","Follow-up scheduled")));
        for(String type:Arrays.asList("growth","vaccination","maternity"))assertEquals(1,service.specialty(type,1L).size());
    }

    @Test void mentalScalesValidateCompleteResponsesAndHonorOppositeWhoScoring() {
        Map<String,Object> who=service.saveMentalAssessment(map("patientId",1L,"scaleCode","WHO5","answers",Arrays.asList(5,5,5,5,5)));
        assertEquals("MINIMAL",who.get("severity"));assertEquals(25,((Number)who.get("score")).intValue());
        service.saveMentalAssessment(map("patientId",1L,"scaleCode","WHO5","answers",Arrays.asList(1,1,1,1,1)));
        service.saveMentalAssessment(map("patientId",1L,"scaleCode","PHQ9","answers",Arrays.asList(0,0,0,0,0,0,0,0,1)));
        assertThrows(IllegalArgumentException.class,()->service.saveMentalAssessment(map("patientId",1L,"scaleCode","PHQ9","answers",Collections.singletonList(0))));
        assertThrows(IllegalArgumentException.class,()->service.saveMentalAssessment(map("patientId",1L,"scaleCode","GAD7","answers",Arrays.asList(4,0,0,0,0,0,0))));
        assertEquals(3,service.mentalAssessments(1L).size());
        verify(audience,times(2)).notify(eq(Collections.singletonList(9L)),eq(1L),eq("MENTAL_ASSESSMENT"),anyString(),anyString());
        service.saveMentalSchedule(map("patientId",1L,"scaleCode","WHO5","intervalDays",7));
        service.saveMentalSchedule(map("patientId",1L,"scaleCode","WHO5","intervalDays",14));
        assertEquals(1,service.mentalSchedules(1L).size());
        Long scheduleId=id(service.mentalSchedules(1L).get(0));
        assertEquals(14,((Number)service.mentalSchedules(1L).get(0).get("interval_days")).intValue());
        service.disableMentalSchedule(scheduleId);
        assertEquals(0,((Number)service.mentalSchedules(1L).get(0).get("enabled")).intValue());
        assertThrows(IllegalArgumentException.class,()->service.saveMentalSchedule(map("patientId",1L,"scaleCode","WHO5","intervalDays",0)));
        jdbc.update("INSERT INTO care_member(patient_id,user_id,relation_name) VALUES(1,11,'Family')");
        login(11,"family");assertTrue(service.mentalAssessments(1L).isEmpty());assertTrue(service.mentalSchedules(1L).isEmpty());
    }

    @Test void overdueMentalSchedulesNotifyOnceAndResumeAtTheNextFutureCycle() {
        service.saveMentalSchedule(map("patientId",1L,"scaleCode","WHO5","intervalDays",7));
        jdbc.update("UPDATE mental_assessment_schedule SET next_due_at=?",LocalDateTime.now().minusYears(3));
        CareJourneyTask task=new CareJourneyTask();ReflectionTestUtils.setField(task,"jdbc",jdbc);ReflectionTestUtils.setField(task,"audience",audience);
        ReflectionTestUtils.invokeMethod(task,"deliverMentalAssessments");ReflectionTestUtils.invokeMethod(task,"deliverMentalAssessments");
        verify(audience,times(1)).notify(eq(Collections.singleton(7L)),eq(1L),eq("MENTAL_ASSESSMENT_DUE"),anyString(),anyString());
        LocalDateTime next=jdbc.queryForObject("SELECT next_due_at FROM mental_assessment_schedule",java.sql.Timestamp.class).toLocalDateTime();
        assertTrue(next.isAfter(LocalDateTime.now()));assertTrue(next.isBefore(LocalDateTime.now().plusDays(7)));
    }

    @Test void multipleGrantsAreCombinedAndRevocationImmediatelyRemovesWriteAccess() {
        Long read=id(service.saveGrant(map("patientId",1L,"granteeUserId",11L,"granteeRole","FAMILY","accessLevel","READ","visibleModules"," measurements ")));
        Long write=id(service.saveGrant(map("patientId",1L,"granteeUserId",11L,"granteeRole","GUARDIAN","accessLevel","WRITE","visibleModules","MEASUREMENTS")));
        assertEquals(2,service.grants(1L).size());
        login(11,"family");service.requireWrite(1L,"MEASUREMENTS");assertThrows(IllegalStateException.class,()->service.requireRead(1L,"MENTAL"));
        login(7,"patient");service.revokeGrant(write);
        login(11,"family");assertThrows(IllegalStateException.class,()->service.requireWrite(1L,"MEASUREMENTS"));service.requireRead(1L,"MEASUREMENTS");
        assertNotNull(read);
    }

    @Test void clinicianGroupsAndReviewsUseConsistentVerifiedStatus() {
        DoctorWorkspaceService doctors=new DoctorWorkspaceService();ReflectionTestUtils.setField(doctors,"jdbc",jdbc);
        login(9,"doctor");Long group=id(service.savePatientGroup(map("groupName","Routine follow-up")));service.addGroupMember(group,1L);service.addGroupMember(group,1L);
        assertEquals(1,((Number)service.patientGroups().get(0).get("patient_count")).intValue());
        jdbc.update("INSERT INTO medical_record(id,patient_name,patient_id,user_id,record_date,verification_status) VALUES(900,'Patient',1,7,CURRENT_DATE,'REVIEW_REQUIRED')");
        assertEquals(1,doctors.reviews("REVIEW_REQUIRED").size());
        doctors.review(map("sourceType","MEDICAL_RECORD","sourceId",900,"decision","APPROVED"));
        assertEquals("VERIFIED",jdbc.queryForObject("SELECT verification_status FROM medical_record WHERE id=900",String.class));
        doctors.saveNote(map("patientId",1L,"noteText","Reviewed"));doctors.savePlan(map("patientId",1L,"title","Plan","instructions","Follow up"));
        assertEquals(1,doctors.notes(1L).size());assertEquals(1,doctors.plans(1L).size());assertEquals(1,((Number)doctors.summary().get("assignedPatients")).intValue());
        assertThrows(IllegalArgumentException.class,()->doctors.review(map("sourceType","MEDICAL_RECORD","sourceId",999,"decision","APPROVED")));
    }

    private void login(long id,String role){MockHttpServletRequest request=new MockHttpServletRequest();request.setAttribute("userId",id);request.setAttribute("roleCodes",Collections.singletonList(role));RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    private static Long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}
    private static Map<String,Object> map(Object... entries){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;}
}
