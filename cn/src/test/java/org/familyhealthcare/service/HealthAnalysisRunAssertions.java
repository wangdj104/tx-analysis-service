package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Same production JDBC store assertions for H2 smoke tests and guarded native MySQL acceptance. */
public final class HealthAnalysisRunAssertions {
 private HealthAnalysisRunAssertions(){}
 private static final BiFunction<HealthAnalysisAutomation,LocalDateTime,LocalDateTime> NEXT=(r,t)->t.plusDays(1);
 private static long task(JdbcTemplate j){j.update("INSERT INTO health_analysis_automation(user_id,patient_id,task_name,enabled,analysis_items,next_run_at) VALUES(777001,777001,'Synthetic run safety',1,'VITALS',CURRENT_TIMESTAMP)");return j.queryForObject("SELECT MAX(id) FROM health_analysis_automation WHERE user_id=777001",Long.class);}
 private static HealthAnalysisRunStore.Claim claim(HealthAnalysisRunStore s,long id){return s.claim(id,777001L,false,false,null,null,NEXT);}
 private static AiAnalysisRecord draft(){AiAnalysisRecord r=new AiAnalysisRecord();r.setUserId(777001L);r.setPatientId(777001L);r.setTimeType("automation");r.setTimeValue("2026-10-11");r.setPeriodLabel("Synthetic");r.setAnalysisContent("  clinical 原文 preserved\n");r.setReviewStatus("REVIEW_REQUIRED");return r;}
 private static void age(JdbcTemplate j,long run){j.update("UPDATE health_analysis_run SET updated_at=? WHERE id=?",java.sql.Timestamp.valueOf(j.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class).toLocalDateTime().minusMinutes(16)),run);}
 public static void verify(JdbcTemplate j) throws Exception {
  HealthAnalysisRunStore one=new HealthAnalysisRunStore(j,new DataSourceTransactionManager(j.getDataSource())),two=new HealthAnalysisRunStore(j,new DataSourceTransactionManager(j.getDataSource()));
  long id=task(j);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{Callable<Boolean> a=()->{start.await();try{claim(one,id);return true;}catch(HealthAnalysisRunConflictException conflict){return false;}};Callable<Boolean> b=()->{start.await();try{claim(two,id);return true;}catch(HealthAnalysisRunConflictException conflict){return false;}};Future<Boolean> x=pool.submit(a),y=pool.submit(b);start.countDown();assertNotEquals(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS),"independent stores share a single durable claim");}finally{pool.shutdownNow();}
  HealthAnalysisRun claimed=one.current(id);assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM health_analysis_run WHERE automation_id=?",Integer.class,id));
  age(j,claimed.getId());HealthAnalysisRunStore.Claim fresh=claim(two,id);assertEquals(claimed.getId(),fresh.run.getId());assertNotEquals(claimed.getClaimToken(),fresh.run.getClaimToken());
  HealthAnalysisRunStore.Claim obsolete=new HealthAnalysisRunStore.Claim(claimed,fresh.settings,null);assertThrows(HealthAnalysisRunConflictException.class,()->one.advance(obsolete,"CLAIMED","GENERATING"));
  one.advance(fresh,"CLAIMED","GENERATING");assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  // Crash during AI work is uncertain, cannot be silently reclaimed by either executor.
  age(j,fresh.run.getId());two.recoverUncertain();assertEquals("UNKNOWN",two.current(id).getState());assertThrows(HealthAnalysisRunConflictException.class,()->claim(one,id));
  assertThrows(HealthAnalysisRunConflictException.class,()->one.claim(id,777001L,false,true,fresh.run.getId(),"NOTIFY_UNKNOWN",NEXT));
  HealthAnalysisRunStore.Claim regenerated=one.claim(id,777001L,false,true,fresh.run.getId(),"UNKNOWN",NEXT);assertNotEquals(fresh.run.getId(),regenerated.run.getId());
  assertThrows(HealthAnalysisRunConflictException.class,()->two.claim(id,777001L,false,true,fresh.run.getId(),"UNKNOWN",NEXT));
  one.advance(regenerated,"CLAIMED","GENERATING");
  // Failing link update rolls back the actual draft insert too.
  int before=j.queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class);
  JdbcTemplate linkFailure=new JdbcTemplate(j.getDataSource()){
   @Override public int update(String sql,Object... args){if(sql.startsWith("UPDATE health_analysis_run SET analysis_record_id="))throw new IllegalStateException("synthetic link failure");return super.update(sql,args);}
  };
  HealthAnalysisRunStore failedLinkStore=new HealthAnalysisRunStore(linkFailure,new DataSourceTransactionManager(j.getDataSource()));
  assertThrows(IllegalStateException.class,()->failedLinkStore.saveDraft(regenerated,draft()));
  assertEquals(before,j.queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class));assertNull(one.current(id).getAnalysisRecordId());assertEquals("GENERATING",one.current(id).getState());
  AiAnalysisRecord r=one.saveDraft(regenerated,draft());assertEquals(r.getId(),one.current(id).getAnalysisRecordId());assertEquals("  clinical 原文 preserved\n",j.queryForObject("SELECT analysis_content FROM ai_analysis_record WHERE id=?",String.class,r.getId()));
  // Only never-notifying saved drafts can resume after a crash, always with the original draft.
  age(j,regenerated.run.getId());HealthAnalysisRunStore.Claim resumed=claim(two,id);assertEquals("DRAFT_SAVED",resumed.run.getState());assertEquals(r.getId(),resumed.run.getAnalysisRecordId());assertThrows(HealthAnalysisRunConflictException.class,()->one.advance(regenerated,"DRAFT_SAVED","NOTIFYING"));
  two.advance(resumed,"DRAFT_SAVED","NOTIFYING");two.recordNotificationOutcome(resumed,4L,org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome.DELIVERED);two.recordNotificationOutcome(resumed,5L,org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome.FAILED);age(j,resumed.run.getId());one.recoverUncertain();assertEquals("NOTIFY_UNKNOWN",one.current(id).getState());assertTrue(one.current(id).getNotificationOutcomes().contains("DELIVERED"));assertTrue(one.current(id).getNotificationOutcomes().contains("FAILED"));
  assertThrows(HealthAnalysisRunConflictException.class,()->claim(two,id));HealthAnalysisRunStore.Claim acknowledged=one.claim(id,777001L,false,true,resumed.run.getId(),"NOTIFY_UNKNOWN",NEXT);assertEquals(r.getId(),acknowledged.existingDraftId);assertEquals(before+1,j.queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class));
  assertThrows(HealthAnalysisRunConflictException.class,()->two.claim(id,777001L,false,true,resumed.run.getId(),"NOTIFY_UNKNOWN",NEXT));
  // Editing during a manual generation does not get overwritten by old status/schedule writes.
  long edited=task(j);HealthAnalysisRunStore.Claim manual=claim(one,edited);LocalDateTime newTime=one.databaseNow().plusDays(9).withNano(0);j.update("UPDATE health_analysis_automation SET task_name='Edited during run',next_run_at=?,config_version=config_version+1 WHERE id=?",newTime,edited);
  one.advance(manual,"CLAIMED","GENERATING");one.saveDraft(manual,draft());one.advance(manual,"DRAFT_SAVED","NOTIFYING");one.finish(manual,"COMPLETE","DRAFT_READY",null,"{\"7\":\"DELIVERED\",\"8\":\"FAILED\"}",NEXT);
  assertEquals("Edited during run",j.queryForObject("SELECT task_name FROM health_analysis_automation WHERE id=?",String.class,edited));assertEquals(newTime,j.queryForObject("SELECT next_run_at FROM health_analysis_automation WHERE id=?",java.sql.Timestamp.class,edited).toLocalDateTime());
  assertTrue(one.current(edited).getNotificationOutcomes().contains("FAILED"));
  long failed=task(j);LocalDateTime oldTime=j.queryForObject("SELECT next_run_at FROM health_analysis_automation WHERE id=?",java.sql.Timestamp.class,failed).toLocalDateTime();HealthAnalysisRunStore.Claim failure=claim(one,failed);one.advance(failure,"CLAIMED","GENERATING");one.fail(failure,false);assertEquals(oldTime,j.queryForObject("SELECT next_run_at FROM health_analysis_automation WHERE id=?",java.sql.Timestamp.class,failed).toLocalDateTime());
  long deleted=task(j);HealthAnalysisRunStore.Claim late=claim(one,deleted);one.advance(late,"CLAIMED","GENERATING");j.update("DELETE FROM health_analysis_automation WHERE id=?",deleted);assertThrows(HealthAnalysisRunConflictException.class,()->one.saveDraft(late,draft()));
  // Twenty blocked records cannot consume LIMIT slots ahead of the next eligible due task.
  List<Long> blocked=new ArrayList<>();for(int i=0;i<20;i++){long t=task(j);HealthAnalysisRunStore.Claim c=claim(one,t);one.advance(c,"CLAIMED","GENERATING");one.fail(c,true);blocked.add(t);j.update("UPDATE health_analysis_automation SET next_run_at=? WHERE id=?",one.databaseNow().minusDays(2),t);}
  long eligible=task(j);j.update("UPDATE health_analysis_automation SET next_run_at=? WHERE id=?",one.databaseNow().minusDays(1),eligible);List<HealthAnalysisAutomation> due=one.candidates();assertTrue(due.stream().anyMatch(t->t.getId()==eligible));assertFalse(due.stream().anyMatch(t->blocked.contains(t.getId())));
  long scheduled=task(j);LocalDateTime scheduleBefore=j.queryForObject("SELECT next_run_at FROM health_analysis_automation WHERE id=?",java.sql.Timestamp.class,scheduled).toLocalDateTime();HealthAnalysisRunStore.Claim reserved=one.claim(scheduled,777001L,true,false,null,null,NEXT);assertEquals(scheduleBefore,reserved.run.getScheduledFor());assertTrue(j.queryForObject("SELECT next_run_at FROM health_analysis_automation WHERE id=?",java.sql.Timestamp.class,scheduled).toLocalDateTime().isAfter(scheduleBefore));assertThrows(HealthAnalysisRunConflictException.class,()->claim(two,scheduled));
  long manualSuccess=task(j);HealthAnalysisRunStore.Claim success=claim(one,manualSuccess);one.advance(success,"CLAIMED","GENERATING");one.saveDraft(success,draft());one.advance(success,"DRAFT_SAVED","NOTIFYING");one.finish(success,"COMPLETE","DRAFT_READY",null,"{}",NEXT);assertTrue(j.queryForObject("SELECT next_run_at FROM health_analysis_automation WHERE id=?",java.sql.Timestamp.class,manualSuccess).toLocalDateTime().isAfter(success.settings.getNextRunAt()));
  // Configuration writes reject stale versions and ignore caller-supplied runtime fields.
  HealthAnalysisAutomation config=j.queryForObject("SELECT * FROM health_analysis_automation WHERE id=?",BeanPropertyRowMapper.newInstance(HealthAnalysisAutomation.class),edited);config.setTaskName("CAS config");config.setLastRunStatus("Injected");one.updateConfiguration(config,config.getConfigVersion());assertThrows(HealthAnalysisRunConflictException.class,()->two.updateConfiguration(config,config.getConfigVersion()));assertEquals("DRAFT_READY",j.queryForObject("SELECT last_run_status FROM health_analysis_automation WHERE id=?",String.class,edited));
  HealthAnalysisRunReferenceAssertions.verify(j);
 }
}
