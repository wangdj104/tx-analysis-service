package org.familyhealthcare.service;

import org.familyhealthcare.support.PatientAuthorizationFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationAudienceCurrentIdentityTest {
    PatientAuthorizationFixture f;
    @BeforeEach void setup() { f = new PatientAuthorizationFixture(); }
    @AfterEach void cleanup() { f.close(); }

    @Test void missingOrDeletedPatientNeverProducesAnAudienceFromStaleRelationships() {
        f.member(1L,11L); f.doctor(1L,8L);
        f.member(404L,11L); f.jdbc.update("INSERT INTO doctor_patient_assignment VALUES(404,8,'ACTIVE')");
        assertTrue(f.audience.recipients(null,"FAMILY_CARE_REMINDER").isEmpty());
        assertTrue(f.audience.recipients(404L,"MEDICATION_MISSED").isEmpty()); assertTrue(f.audience.assignedDoctors(404L).isEmpty());
        f.jdbc.update("UPDATE patient SET deleted=1 WHERE id=1");
        assertTrue(f.audience.recipients(1L,"FAMILY_CARE_REMINDER").isEmpty()); assertTrue(f.audience.assignedDoctors(1L).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"UPDATE sys_role SET status=0 WHERE id=201", "UPDATE sys_role SET deleted=1 WHERE id=201", "DELETE FROM sys_user_role WHERE user_id=8", "UPDATE sys_user SET status=0 WHERE id=8", "UPDATE sys_user SET deleted=1 WHERE id=8", "UPDATE doctor_patient_assignment SET status='INACTIVE' WHERE doctor_user_id=8"})
    void staleOrRemovedDoctorAuthorityCannotReceivePatientEvents(String mutation) {
        f.doctor(1L,8L); assertEquals(Collections.singletonList(8L),f.audience.assignedDoctors(1L));
        f.jdbc.update(mutation);
        assertEquals(Collections.singleton(7L),f.audience.recipients(1L,"FAMILY_CARE_REMINDER")); assertTrue(f.audience.assignedDoctors(1L).isEmpty());
    }

    @Test void independentOwnerAndCurrentDoctorAuthoritySurviveRevokedFamilyGrantWithoutDuplicates() {
        f.doctor(1L,8L); f.jdbc.update("INSERT INTO sys_user_role VALUES(8,201)"); f.jdbc.update("INSERT INTO doctor_patient_assignment VALUES(1,8,'ACTIVE')");
        f.member(1L,7L); f.member(1L,8L); f.grant(1L,7L,"READ",null,"REVOKED",false); f.grant(1L,8L,"READ",null,"REVOKED",false);
        assertEquals(new HashSet<>(Arrays.asList(7L,8L)),f.audience.recipients(1L,"FAMILY_CARE_REMINDER")); assertEquals(Collections.singletonList(8L),f.audience.assignedDoctors(1L));
    }

    @Test void genericCareRequiresFullRecordButMedicationEventsHonorReadOnlyMedicationGrant() {
        f.member(1L,11L); f.grant(1L,11L,"READ","MEDICATION","ACTIVE",false);
        assertFalse(f.audience.recipients(1L,"FAMILY_CARE_REMINDER").contains(11L)); assertFalse(f.audience.recipients(1L,"CARE_FOLLOW_UP").contains(11L));
        assertTrue(f.audience.recipients(1L,"MEDICATION_MISSED").contains(11L));
        f.jdbc.update("UPDATE care_access_grant SET status='REVOKED'"); assertFalse(f.audience.recipients(1L,"MEDICATION_MISSED").contains(11L));
    }
}
