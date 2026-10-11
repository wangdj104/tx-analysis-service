package org.familyhealthcare.service;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.familyhealthcare.controller.HealthAnalysisAutomationController;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AutomationOutcomeRegressionTest {
 CarePlanTestFixture f;HealthAnalysisAutomationService service;HealthAnalysisRunStore store;AiAnalysisService ai;NotificationDeliveryService delivery;MockMvc mvc;
 @BeforeEach void setup()throws Exception{
  f=new CarePlanTestFixture();f.jdbc().update("INSERT INTO health_analysis_automation(id,user_id,patient_id,task_name,enabled,analysis_items,analysis_range_days) VALUES(1,7,1,'Synthetic outcome',0,'VITALS',30)");
  store=new HealthAnalysisRunStore(f.jdbc(),new DataSourceTransactionManager(f.jdbc().getDataSource()));service=new HealthAnalysisAutomationService();ai=mock(AiAnalysisService.class);delivery=mock(NotificationDeliveryService.class);DataScopeHelper scope=mock(DataScopeHelper.class);when(scope.requireUserId()).thenReturn(7L);Patient p=new Patient();p.setId(1L);p.setName("Synthetic Patient");when(scope.requirePatientAccess(eq(1L),isNull(),anyBoolean())).thenReturn(p);
  HealthAnalysisAutomationMapper tasks=mock(HealthAnalysisAutomationMapper.class);when(tasks.selectById(any())).thenAnswer(a->f.jdbc().queryForObject("SELECT * FROM health_analysis_automation WHERE id=?",BeanPropertyRowMapper.newInstance(HealthAnalysisAutomation.class),(Object)a.getArgument(0)));
  AiAnalysisRecordMapper records=mock(AiAnalysisRecordMapper.class);when(records.selectById(any())).thenAnswer(a->f.jdbc().queryForObject("SELECT * FROM ai_analysis_record WHERE id=?",BeanPropertyRowMapper.newInstance(AiAnalysisRecord.class),(Object)a.getArgument(0)));
  ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"mapper",tasks);ReflectionTestUtils.setField(service,"analysisRecords",records);ReflectionTestUtils.setField(service,"runStore",store);ReflectionTestUtils.setField(service,"aiAnalysis",ai);ReflectionTestUtils.setField(service,"delivery",delivery);
  HealthAnalysisAutomationController controller=new HealthAnalysisAutomationController();ReflectionTestUtils.setField(controller,"service",service);mvc=MockMvcBuilders.standaloneSetup(controller).build();
 }
 @AfterEach void close()throws Exception{f.close();}
 @Test void definitiveAndAmbiguousAiFailuresSaveNoDraftAndSendNoNotification() {
  for(AiAnalysisException failure:new AiAnalysisException[]{new AiAnalysisException(AiAnalysisException.Reason.INVALID_RESPONSE),new AiAnalysisException(AiAnalysisException.Reason.EMPTY_RESULT),new AiAnalysisException(AiAnalysisException.Reason.HTTP_FAILURE,false),new AiAnalysisException(AiAnalysisException.Reason.TRANSPORT_FAILURE,true)}){
   doThrow(failure).when(ai).analyzeHealthSnapshot(anyLong(),anyInt(),anyCollection());
   assertThrows(AiAnalysisException.class,()->service.runNow(1L));assertEquals(failure.isOutcomeUncertain()?"UNKNOWN":"FAILED",store.current(1L).getState());assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class));verifyNoInteractions(delivery);
  }
 }
 @Test void emptySuccessBodyIsRejectedBeforeDraftPersistence(){when(ai.analyzeHealthSnapshot(anyLong(),anyInt(),anyCollection())).thenReturn("  ");assertThrows(AiAnalysisException.class,()->service.runNow(1L));assertEquals("FAILED",store.current(1L).getState());assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class));verifyNoInteractions(delivery);}
 @Test void schedulerFiltersTwentyBlockedRunsBeforeLimitingAndExecutesNextEligibleTask(){
  for(long id=2;id<=21;id++){
   f.jdbc().update("INSERT INTO health_analysis_automation(id,user_id,patient_id,task_name,enabled,analysis_items,analysis_range_days,next_run_at) VALUES(?,7,1,'Synthetic blocked',1,'VITALS',30,?)",id,store.databaseNow().minusDays(2));
   HealthAnalysisRunStore.Claim blocked=store.claim(id,7L,false,false,null,null,(r,t)->t.plusDays(1));store.advance(blocked,"CLAIMED","GENERATING");store.fail(blocked,true);
  }
  f.jdbc().update("INSERT INTO health_analysis_automation(id,user_id,patient_id,task_name,enabled,analysis_items,analysis_range_days,next_run_at) VALUES(22,7,1,'Synthetic eligible',1,'VITALS',30,?)",store.databaseNow().minusDays(1));
  UserLanguagePreferenceService languages=mock(UserLanguagePreferenceService.class);when(languages.get(7L)).thenReturn("en-US");ReflectionTestUtils.setField(service,"languagePreference",languages);
  when(ai.analyzeHealthSnapshot(anyLong(),anyInt(),anyCollection())).thenReturn("Synthetic eligible draft");service.runDueTasks();assertEquals("COMPLETE",store.current(22L).getState());assertEquals(1,f.jdbc().queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class));verify(ai,times(1)).analyzeHealthSnapshot(anyLong(),anyInt(),anyCollection());
  for(long id=2;id<=21;id++)assertEquals("UNKNOWN",store.current(id).getState());
 }
 @Test void mixedNotificationResultsPersistAndAcknowledgementReturnsSameDraft()throws Exception{
  when(ai.analyzeHealthSnapshot(anyLong(),anyInt(),anyCollection())).thenAnswer(a->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return "Verbatim clinical body 原文";});Map<Long,DeliveryOutcome> map=new LinkedHashMap<>();map.put(4L,DeliveryOutcome.DELIVERED);map.put(5L,DeliveryOutcome.FAILED);map.put(6L,DeliveryOutcome.UNKNOWN);
  when(delivery.notifyUserOutcomes(anyLong(),anyCollection(),anyString(),anyString(),anyString(),any())).thenAnswer(a->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());((java.util.function.BiConsumer<Long,DeliveryOutcome>)a.getArgument(5)).accept(4L,DeliveryOutcome.DELIVERED);((java.util.function.BiConsumer<Long,DeliveryOutcome>)a.getArgument(5)).accept(5L,DeliveryOutcome.FAILED);((java.util.function.BiConsumer<Long,DeliveryOutcome>)a.getArgument(5)).accept(6L,DeliveryOutcome.UNKNOWN);return map;});
  AiAnalysisRecord draft=service.runNow(1L);HealthAnalysisRun uncertain=store.current(1L);assertEquals("NOTIFY_UNKNOWN",uncertain.getState());assertTrue(uncertain.getNotificationOutcomes().contains("DELIVERED"));assertTrue(uncertain.getNotificationOutcomes().contains("FAILED"));assertTrue(uncertain.getNotificationOutcomes().contains("UNKNOWN"));
  mvc.perform(post("/health-analysis/automations/1/run")).andExpect(status().isConflict());
  mvc.perform(post("/health-analysis/automations/1/run").param("acknowledgeUncertain","true").param("uncertainRunId",uncertain.getId().toString()).param("uncertainRunState","UNKNOWN")).andExpect(status().isConflict());
  AiAnalysisRecord existing=service.runNow(1L,true,uncertain.getId(),"NOTIFY_UNKNOWN");assertEquals(draft.getId(),existing.getId());assertEquals("COMPLETE",store.current(1L).getState());
  mvc.perform(post("/health-analysis/automations/1/run").param("acknowledgeUncertain","true").param("uncertainRunId",uncertain.getId().toString()).param("uncertainRunState","NOTIFY_UNKNOWN")).andExpect(status().isConflict());
  assertEquals(1,f.jdbc().queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class));verify(ai,times(1)).analyzeHealthSnapshot(anyLong(),anyInt(),anyCollection());verify(delivery,times(1)).notifyUserOutcomes(anyLong(),anyCollection(),anyString(),anyString(),anyString(),any());
 }
}
