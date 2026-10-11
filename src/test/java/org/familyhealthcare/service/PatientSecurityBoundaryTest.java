package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.impl.AlertServiceImpl;
import org.familyhealthcare.support.PatientAuthorizationFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PatientSecurityBoundaryTest {
    PatientAuthorizationFixture f;
    @BeforeEach void setup() { f = new PatientAuthorizationFixture(); }
    @AfterEach void cleanup() { f.close(); }

    @Test void forgedPatientIdCannotAuthorizeUpdatingAnotherPatientsAlertRule() {
        f.grant(1L,11L,"WRITE",null,"ACTIVE",false);
        AlertRuleMapper rules = mock(AlertRuleMapper.class); AlertServiceImpl service = alert(rules);
        when(rules.selectById(30L)).thenReturn(rule(30L,2L,9L));
        assertThrows(IllegalStateException.class, () -> service.saveRule(rule(30L,1L,11L)));
        verify(rules,never()).updateById(any()); verify(rules,never()).update(any(),any()); verify(rules,never()).insert(any());
    }

    @Test void authorizedEditorStillCannotMigrateAnAlertRuleBetweenPatients() {
        f.grant(1L,11L,"WRITE",null,"ACTIVE",false); f.grant(2L,11L,"WRITE",null,"ACTIVE",false);
        AlertRuleMapper rules = mock(AlertRuleMapper.class); AlertServiceImpl service = alert(rules);
        when(rules.selectById(30L)).thenReturn(rule(30L,2L,9L));
        assertThrows(IllegalArgumentException.class, () -> service.saveRule(rule(30L,1L,11L)));
        verify(rules,never()).updateById(any()); verify(rules,never()).update(any(),any()); verify(rules,never()).insert(any());
    }

    @Test void samePatientEditPreservesOriginalAuthorAndConditionsWriteOnStoredPatient() {
        f.grant(1L,11L,"WRITE",null,"ACTIVE",false);
        AlertRuleMapper rules = mock(AlertRuleMapper.class); AlertServiceImpl service = alert(rules);
        when(rules.selectById(30L)).thenReturn(rule(30L,1L,7L)); when(rules.update(any(),any())).thenReturn(1);
        AlertRule form = rule(30L,1L,11L); form.setThresholdValue("5.5");
        assertTrue(service.saveRule(form)); assertEquals(7L,form.getUserId());
        org.mockito.ArgumentCaptor<Wrapper<AlertRule>> condition = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(rules).update(eq(form),condition.capture()); verify(rules,never()).updateById(any());
        String sql = condition.getValue().getSqlSegment();
        assertTrue(sql.contains("id =")); assertTrue(sql.contains("patient_id ="));
        Map<String,Object> values = ((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AlertRule>)condition.getValue()).getParamNameValuePairs();
        assertTrue(values.containsValue(30L)); assertTrue(values.containsValue(1L));
    }

    @Test void missingAlertRuleIdDoesNotTurnAnUpdateIntoACreate() {
        f.grant(1L,11L,"WRITE",null,"ACTIVE",false);
        AlertRuleMapper rules = mock(AlertRuleMapper.class);
        assertFalse(alert(rules).saveRule(rule(404L,1L,11L)));
        verify(rules,never()).updateById(any()); verify(rules,never()).update(any(),any()); verify(rules,never()).insert(any());
    }

    @ParameterizedTest @CsvSource({"READ,,ACTIVE,false", "WRITE,,REVOKED,false", "WRITE,,ACTIVE,true", "WRITE,MEDICATION,ACTIVE,false"})
    void alertCreationRequiresCurrentFullWriteEvenFromReadOnlyRequest(String level,String module,String status,boolean expired) {
        f.member(1L,11L); f.grant(1L,11L,level,module,status,expired);
        AlertRuleMapper rules = mock(AlertRuleMapper.class);
        assertThrows(IllegalStateException.class, () -> alert(rules).saveRule(rule(null,1L,null)));
        verifyNoInteractions(rules);
    }

    @Test void fullWriteStillCreatesAlertRulesForCurrentActor() {
        f.grant(1L,11L,"WRITE",null,"ACTIVE",false); AlertRuleMapper rules = mock(AlertRuleMapper.class);
        when(rules.insert(any())).thenReturn(1); AlertRule form = rule(null,1L,null);
        assertTrue(alert(rules).saveRule(form)); assertEquals(11L,form.getUserId()); verify(rules).insert(form);
    }

    @Test void automationListFiltersRevokedExpiredScopedAndDeletedPatientsIndependently() {
        f.grant(1L,11L,"READ",null,"ACTIVE",false);
        f.member(2L,11L); f.grant(2L,11L,"WRITE",null,"REVOKED",false);
        f.addPatient(3L,7L); f.grant(3L,11L,"WRITE",null,"ACTIVE",true);
        f.addPatient(4L,7L); f.grant(4L,11L,"WRITE","MEDICATION","ACTIVE",false);
        f.addPatient(5L,7L); f.grant(5L,11L,"WRITE",null,"ACTIVE",false); f.jdbc.update("UPDATE patient SET deleted=1 WHERE id=5");
        HealthAnalysisAutomationMapper tasks = mock(HealthAnalysisAutomationMapper.class); AiAnalysisRecordMapper records = mock(AiAnalysisRecordMapper.class);
        List<HealthAnalysisAutomation> rows = new ArrayList<>(); for(long id=1;id<=6;id++)rows.add(task(id,id,id+100));
        when(tasks.selectList(any())).thenReturn(rows); AiAnalysisRecord allowed = draft(101L,1L,11L); when(records.selectById(101L)).thenReturn(allowed);
        assertEquals(Collections.singletonList(rows.get(0)), automation(tasks,records,mock(NotificationDeliveryService.class)).list());
        assertEquals(allowed.getAnalysisContent(),rows.get(0).getLastAnalysisContent());
        verify(records).selectById(101L); verifyNoMoreInteractions(records);
    }

    @ParameterizedTest @CsvSource({"1,12", "2,11"})
    void automationListNeverExposesDraftForDifferentPatientOrAuthor(long recordPatient,long recordAuthor) {
        f.grant(1L,11L,"READ",null,"ACTIVE",false);
        HealthAnalysisAutomation row = task(1L,1L,101L); row.setLastAnalysisContent("Previously loaded secret");
        HealthAnalysisAutomationMapper tasks = mock(HealthAnalysisAutomationMapper.class); AiAnalysisRecordMapper records = mock(AiAnalysisRecordMapper.class);
        when(tasks.selectList(any())).thenReturn(Collections.singletonList(row)); when(records.selectById(101L)).thenReturn(draft(101L,recordPatient,recordAuthor));
        List<HealthAnalysisAutomation> result = automation(tasks,records,mock(NotificationDeliveryService.class)).list();
        assertEquals(1,result.size()); assertNull(result.get(0).getLastAnalysisContent());
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void revokedDraftReviewIsRejectedBeforeAnyMutationOrTransport(boolean approved,boolean notify) {
        f.member(1L,11L); f.grant(1L,11L,"WRITE",null,"REVOKED",false);
        assertReviewDenied(approved,notify);
    }

    @ParameterizedTest @CsvSource({"READ,,ACTIVE,false", "WRITE,,ACTIVE,true", "WRITE,MEDICATION,ACTIVE,false"})
    void draftReviewRequiresFullWriteRatherThanReadOrModuleGrant(String level,String module,String status,boolean expired) {
        f.grant(1L,11L,level,module,status,expired); assertReviewDenied(false,false);
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void fullWriteDraftReviewPreservesApprovalAndNotificationChoices(boolean approved,boolean notify) {
        f.grant(1L,11L,"WRITE",null,"ACTIVE",false);
        AiAnalysisRecordMapper records = mock(AiAnalysisRecordMapper.class); NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
        AiAnalysisRecord draft = draft(101L,1L,11L); when(records.selectById(101L)).thenReturn(draft);
        assertSame(draft, automation(mock(HealthAnalysisAutomationMapper.class),records,delivery).reviewAnalysis(101L,approved,notify));
        assertEquals(approved?"APPROVED":"REJECTED",draft.getReviewStatus()); assertEquals(11L,draft.getReviewedBy()); assertNotNull(draft.getReviewedAt()); verify(records).updateById(draft);
        if(approved&&notify)verify(delivery).notifyUser(eq(11L),eq("HEALTH_ANALYSIS_REVIEWED"),anyString(),eq("Original clinical draft 原文")); else verifyNoInteractions(delivery);
    }

    @Test void draftReviewKeepsSameAuthorRestrictionEvenForPatientOwner() {
        f.actor(7L); AiAnalysisRecordMapper records = mock(AiAnalysisRecordMapper.class); NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
        when(records.selectById(101L)).thenReturn(draft(101L,1L,11L));
        assertThrows(IllegalArgumentException.class, () -> automation(mock(HealthAnalysisAutomationMapper.class),records,delivery).reviewAnalysis(101L,true,true));
        verify(records,never()).updateById(any()); verifyNoInteractions(delivery);
    }

    @Test void patientlessDraftCannotBypassFullRecordAuthorization() {
        AiAnalysisRecordMapper records=mock(AiAnalysisRecordMapper.class);NotificationDeliveryService delivery=mock(NotificationDeliveryService.class);
        AiAnalysisRecord draft=draft(101L,null,11L);when(records.selectById(101L)).thenReturn(draft);
        assertThrows(IllegalStateException.class,()->automation(mock(HealthAnalysisAutomationMapper.class),records,delivery).reviewAnalysis(101L,false,false));
        assertEquals("REVIEW_REQUIRED",draft.getReviewStatus());verify(records,never()).updateById(any());verifyNoInteractions(delivery);
    }

    @Test void patientlessTaskIsNotReturnedAsAnAuthorizedAggregate() {
        HealthAnalysisAutomationMapper tasks=mock(HealthAnalysisAutomationMapper.class);AiAnalysisRecordMapper records=mock(AiAnalysisRecordMapper.class);
        when(tasks.selectList(any())).thenReturn(Collections.singletonList(task(1L,null,101L)));
        assertTrue(automation(tasks,records,mock(NotificationDeliveryService.class)).list().isEmpty());verifyNoInteractions(records);
    }

    private void assertReviewDenied(boolean approved,boolean notify) {
        AiAnalysisRecordMapper records = mock(AiAnalysisRecordMapper.class); NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
        AiAnalysisRecord draft = draft(101L,1L,11L); when(records.selectById(101L)).thenReturn(draft);
        assertThrows(IllegalStateException.class, () -> automation(mock(HealthAnalysisAutomationMapper.class),records,delivery).reviewAnalysis(101L,approved,notify));
        assertEquals("REVIEW_REQUIRED",draft.getReviewStatus()); assertNull(draft.getReviewedAt()); assertNull(draft.getReviewedBy());
        verify(records,never()).updateById(any()); verifyNoInteractions(delivery);
    }
    private AlertServiceImpl alert(AlertRuleMapper rules) {
        AlertServiceImpl service = new AlertServiceImpl(); ReflectionTestUtils.setField(service,"alertRuleMapper",rules);
        ReflectionTestUtils.setField(service,"dataScopeHelper",f.scope); ReflectionTestUtils.setField(service,"healthIndicatorService",mock(HealthIndicatorService.class)); return service;
    }
    private HealthAnalysisAutomationService automation(HealthAnalysisAutomationMapper tasks,AiAnalysisRecordMapper records,NotificationDeliveryService delivery) {
        HealthAnalysisAutomationService service = new HealthAnalysisAutomationService(); ReflectionTestUtils.setField(service,"mapper",tasks); ReflectionTestUtils.setField(service,"analysisRecords",records);
        ReflectionTestUtils.setField(service,"scope",f.scope); ReflectionTestUtils.setField(service,"delivery",delivery); ReflectionTestUtils.setField(service,"runStore",mock(HealthAnalysisRunStore.class)); return service;
    }
    private AlertRule rule(Long id,Long patient,Long author) { AlertRule r = new AlertRule(); r.setId(id); r.setPatientId(patient); r.setUserId(author); return r; }
    private HealthAnalysisAutomation task(Long id,Long patient,Long last) { HealthAnalysisAutomation r = new HealthAnalysisAutomation(); r.setId(id); r.setUserId(11L); r.setPatientId(patient); r.setLastAnalysisRecordId(last); return r; }
    private AiAnalysisRecord draft(Long id,Long patient,Long author) { AiAnalysisRecord r = new AiAnalysisRecord(); r.setId(id); r.setPatientId(patient); r.setUserId(author); r.setReviewStatus("REVIEW_REQUIRED"); r.setAnalysisContent("Original clinical draft 原文"); return r; }
}
