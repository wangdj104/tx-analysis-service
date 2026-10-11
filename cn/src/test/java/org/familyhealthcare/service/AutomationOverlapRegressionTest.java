package org.familyhealthcare.service;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AutomationOverlapRegressionTest {
 @Test void overlappingManualRunsMustNotGenerateTwoDrafts() throws Exception {
  try(CarePlanTestFixture fixture=new CarePlanTestFixture()){
  HealthAnalysisAutomationService service=new HealthAnalysisAutomationService();
  HealthAnalysisRunStore store=new HealthAnalysisRunStore(fixture.jdbc(),new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.jdbc().getDataSource()));
  fixture.jdbc().update("INSERT INTO health_analysis_automation(id,user_id,patient_id,task_name,enabled,analysis_items,analysis_range_days) VALUES(1,7,8,'Synthetic overlap',1,'VITALS',30)");
  fixture.jdbc().update("UPDATE health_analysis_automation SET next_run_at=CURRENT_TIMESTAMP WHERE id=1");
  ReflectionTestUtils.setField(service,"runStore",store);
  HealthAnalysisAutomationMapper mapper=mock(HealthAnalysisAutomationMapper.class); AiAnalysisRecordMapper records=mock(AiAnalysisRecordMapper.class);
  DataScopeHelper scope=mock(DataScopeHelper.class); AiAnalysisService ai=mock(AiAnalysisService.class);
  ReflectionTestUtils.setField(service,"mapper",mapper); ReflectionTestUtils.setField(service,"analysisRecords",records);
  ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"aiAnalysis",ai);
  ReflectionTestUtils.setField(service,"delivery",mock(NotificationDeliveryService.class));
  HealthAnalysisAutomation task=new HealthAnalysisAutomation();task.setId(1L);task.setUserId(7L);task.setPatientId(8L);task.setEnabled(0);task.setAnalysisRangeDays(30);task.setAnalysisItems("VITALS");
  when(scope.requireUserId()).thenReturn(7L);when(mapper.selectById(1L)).thenReturn(task);
  CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
  when(ai.analyzeHealthSnapshot(anyLong(),anyInt(),anyCollection())).thenAnswer(a->{calls.incrementAndGet();started.countDown();release.await(5,TimeUnit.SECONDS);return "synthetic body";});
  ExecutorService pool=Executors.newFixedThreadPool(2);
  try {Future<?> one=pool.submit(()->service.runNow(1L)); assertTrue(started.await(5,TimeUnit.SECONDS));service.runDueTasks();Future<?> two=pool.submit(()->service.runNow(1L));Thread.sleep(150);release.countDown();one.get(5,TimeUnit.SECONDS);try{two.get(5,TimeUnit.SECONDS);}catch(ExecutionException expected){}
   assertEquals(1,calls.get(),"one durable task claim must exclude an overlapping manual run");
  } finally {release.countDown();pool.shutdownNow();}
  }
 }
}
