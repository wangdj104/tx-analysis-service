package org.familyhealthcare.service;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareJourneyMeasurementValidationTest {
    CareDatabaseWorkflowTest database;
    CareJourneyService service;
    NotificationAudienceService audience;
    @BeforeEach void setup() throws Exception {
        database = new CareDatabaseWorkflowTest(); database.setup(); service = new CareJourneyService(); audience = mock(NotificationAudienceService.class);
        ReflectionTestUtils.setField(service,"jdbc",database.jdbc); ReflectionTestUtils.setField(service,"scope",database.scope); ReflectionTestUtils.setField(service,"audience",audience);
    }
    @AfterEach void cleanup() { database.cleanup(); }
    @Test void invalidGlucoseNeverPersistsOrNotifiesTheCareTeam() {
        for (Object value : new Object[]{-1, 0, null, "", "invalid", "NaN", "Infinity"}) {
            Map<String,Object> body = new HashMap<>(); body.put("patientId",1L); body.put("metricType","GLUCOSE"); body.put("unit","mmol/L"); body.put("valuePrimary",value);
            assertThrows(IllegalArgumentException.class, () -> service.saveMeasurement(body), String.valueOf(value));
        }
        assertEquals(0, database.jdbc.queryForObject("SELECT COUNT(*) FROM health_measurement",Integer.class));
        verifyNoInteractions(audience);
    }
}
