package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.impl.AlertServiceImpl;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClinicalSafetyWorkflowTest {

    @Test void repeatedThresholdScanRefreshesTheActiveAlertInsteadOfCreatingADuplicate() {
        AlertServiceImpl service = new AlertServiceImpl();
        AlertRecordMapper records = mock(AlertRecordMapper.class);
        AlertRuleMapper rules = mock(AlertRuleMapper.class);
        MedicalRecordItemMapper items = mock(MedicalRecordItemMapper.class);
        HealthIndicatorService indicators = mock(HealthIndicatorService.class);
        NotificationDeliveryService notifications = mock(NotificationDeliveryService.class);
        ReflectionTestUtils.setField(service, "baseMapper", records);
        ReflectionTestUtils.setField(service, "alertRuleMapper", rules);
        ReflectionTestUtils.setField(service, "medicalRecordItemMapper", items);
        ReflectionTestUtils.setField(service, "healthIndicatorService", indicators);
        ReflectionTestUtils.setField(service, "notificationDeliveryService", notifications);

        AlertRule rule = new AlertRule();
        rule.setId(4L); rule.setIndicatorCode("K"); rule.setIndicatorName("Potassium");
        rule.setThresholdType("ABOVE"); rule.setThresholdValue("5.5"); rule.setAlertLevel("CRITICAL");
        MedicalRecordItem latest = new MedicalRecordItem();
        latest.setId(9L); latest.setResultValue("6.2"); latest.setUnit("mmol/L");
        AlertRecord active = new AlertRecord(); active.setId(12L); active.setStatus("PENDING"); active.setOccurrenceCount(2);
        when(rules.selectList(any())).thenReturn(Collections.singletonList(rule));
        when(items.selectLatestByPatientIdAndItemCode(2L, "K")).thenReturn(latest);
        when(records.selectOne(any())).thenReturn(active);

        service.checkThresholdsSystem(2L, 7L);

        verify(records).updateById(active);
        verify(records, never()).insert(any());
        verifyNoInteractions(notifications);
        assertNotNull(active.getLastTriggeredAt());
        assertEquals(3, active.getOccurrenceCount());
    }

    @Test void fhirObservationIsPreviewedWithoutWritingAndRequiresConfirmation() {
        ClinicalImportService service = new ClinicalImportService();
        DataScopeHelper scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service, "scope", scope);
        Map<String, Object> systolic = component("8480-6", 128);
        Map<String, Object> diastolic = component("8462-4", 78);
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("resourceType", "Observation"); observation.put("id", "bp-1");
        observation.put("code", coding("85354-9", "Blood pressure panel"));
        observation.put("effectiveDateTime", "2026-09-20T08:30:00+08:00");
        observation.put("component", Arrays.asList(systolic, diastolic));
        Map<String, Object> entry = Collections.singletonMap("resource", observation);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("resourceType", "Bundle"); bundle.put("entry", Collections.singletonList(entry));

        Map<String, Object> result = service.preview(2L, Collections.singletonMap("bundle", bundle));

        assertEquals(1, result.get("itemCount"));
        assertEquals(Boolean.TRUE, result.get("requiresConfirmation"));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
        assertEquals("VITAL", item.get("kind")); assertEquals(128, item.get("systolic")); assertEquals(78, item.get("diastolic"));
        verify(scope).requirePatient(2L);
    }

    @Test void reviewedAiDraftOnlySendsClinicalTextAfterExplicitApprovalAndNotifyChoice() {
        HealthAnalysisAutomationService service = new HealthAnalysisAutomationService();
        AiAnalysisRecordMapper records = mock(AiAnalysisRecordMapper.class);
        NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
        DataScopeHelper scope = mock(DataScopeHelper.class);
        ReflectionTestUtils.setField(service, "analysisRecords", records);
        ReflectionTestUtils.setField(service, "delivery", delivery);
        ReflectionTestUtils.setField(service, "scope", scope);
        when(scope.requireUserId()).thenReturn(7L);
        Patient patient = new Patient(); patient.setName("Test Patient"); when(scope.requirePatient(2L)).thenReturn(patient);
        AiAnalysisRecord draft = new AiAnalysisRecord();
        draft.setId(3L); draft.setUserId(7L); draft.setPatientId(2L); draft.setReviewStatus("REVIEW_REQUIRED"); draft.setAnalysisContent("Clinical draft text");
        when(records.selectById(3L)).thenReturn(draft);

        service.reviewAnalysis(3L, true, true);

        assertEquals("APPROVED", draft.getReviewStatus()); assertEquals(7L, draft.getReviewedBy()); assertNotNull(draft.getReviewedAt());
        verify(records).updateById(draft);
        verify(delivery).notifyUser(7L, "HEALTH_ANALYSIS_REVIEWED", "Reviewed health analysis · Test Patient", "Clinical draft text");
    }

    private static Map<String, Object> component(String code, int value) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("code", coding(code, null));
        Map<String, Object> quantity = new LinkedHashMap<>(); quantity.put("value", value); quantity.put("unit", "mmHg");
        component.put("valueQuantity", quantity); return component;
    }

    private static Map<String, Object> coding(String code, String display) {
        Map<String, Object> coding = new LinkedHashMap<>(); coding.put("code", code); if (display != null) coding.put("display", display);
        return Collections.singletonMap("coding", Collections.singletonList(coding));
    }
}
