package org.familyhealthcare.service;

import com.alibaba.fastjson2.*;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiFunction;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production JDBC restore/run tests, shared with the already guarded native MySQL gate. */
public final class HealthAnalysisRunReferenceAssertions {
 private HealthAnalysisRunReferenceAssertions(){}
 private static final long OWNER=880007,RESTORER=880008;
 private static final BiFunction<HealthAnalysisAutomation,LocalDateTime,LocalDateTime> NEXT=(r,t)->t.plusDays(1);
 private static HealthAnalysisRunStore store(JdbcTemplate j){return new HealthAnalysisRunStore(j,new DataSourceTransactionManager(j.getDataSource()));}
 private static long patient(JdbcTemplate j){j.update("INSERT INTO patient(name,user_id) VALUES(?,?)","Ref "+UUID.randomUUID().toString().substring(0,8),OWNER);return j.queryForObject("SELECT MAX(id) FROM patient WHERE user_id=?",Long.class,OWNER);}
 private static long task(JdbcTemplate j,long user,long patient){j.update("INSERT INTO health_analysis_automation(user_id,patient_id,task_name,enabled,analysis_items,next_run_at,config_version) VALUES(?,?,'Synthetic runtime reference',1,'VITALS',CURRENT_TIMESTAMP,9)",user,patient);return j.queryForObject("SELECT MAX(id) FROM health_analysis_automation WHERE user_id=?",Long.class,user);}
 private static HealthAnalysisAutomation row(JdbcTemplate j,long id){return j.queryForObject("SELECT * FROM health_analysis_automation WHERE id=?",BeanPropertyRowMapper.newInstance(HealthAnalysisAutomation.class),id);}
 private static FamilyBackupService backup(JdbcTemplate j,DataScopeHelper scope){FamilyBackupService service=new FamilyBackupService();ReflectionTestUtils.setField(service,"jdbc",j);ReflectionTestUtils.setField(service,"scope",scope);return service;}
 private static byte[] zip(JSONObject archive)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(bytes)){z.putNextEntry(new ZipEntry("family-health.json"));z.write(archive.toJSONString().getBytes(StandardCharsets.UTF_8));z.closeEntry();}return bytes.toByteArray();}
 private static long restore(JdbcTemplate j,FamilyBackupService backup,byte[] bytes){Map<String,Object> result=new TransactionTemplate(new DataSourceTransactionManager(j.getDataSource())).execute(s->{try{return backup.restore(bytes);}catch(IOException error){throw new IllegalStateException(error);}});long p=((Number)((Collection<?>)result.get("patientIds")).iterator().next()).longValue();return j.queryForObject("SELECT id FROM health_analysis_automation WHERE patient_id=?",Long.class,p);}
 private static void detached(JdbcTemplate j,long id){HealthAnalysisAutomation task=row(j,id);assertEquals(0,task.getEnabled());assertEquals(0L,task.getConfigVersion());assertNull(task.getActiveRunId());assertNull(task.getNextRunAt());assertNull(task.getLastRunAt());assertNull(task.getLastRunStatus());assertNull(task.getLastError());assertNull(task.getLastAnalysisRecordId());assertNull(task.getNotificationChannelIds());assertNull(store(j).current(id));assertFalse(store(j).candidates().stream().anyMatch(r->r.getId()==id));}
 private static HealthAnalysisRunStore.Claim uncertain(JdbcTemplate j,long task,long user){HealthAnalysisRunStore s=store(j);HealthAnalysisRunStore.Claim c=s.claim(task,user,false,false,null,null,NEXT);s.advance(c,"CLAIMED","GENERATING");s.fail(c,true);return c;}
 private static void originalBlocked(HealthAnalysisRunStore s,long task,HealthAnalysisRunStore.Claim original){assertEquals("UNKNOWN",s.current(task).getState());assertEquals(original.run.getClaimToken(),s.current(task).getClaimToken());assertThrows(HealthAnalysisRunConflictException.class,()->s.claim(task,OWNER,false,false,null,null,NEXT));}
 public static void restoreCopiesDetachRuntime(JdbcTemplate j)throws Exception{
  long patient=patient(j),source=task(j,OWNER,patient);HealthAnalysisRunStore s=store(j);HealthAnalysisRunStore.Claim original=uncertain(j,source,OWNER);
  DataScopeHelper scope=mock(DataScopeHelper.class);when(scope.requireUserId()).thenReturn(OWNER);when(scope.accessiblePatientIds(OWNER)).thenReturn(Collections.singletonList(patient));FamilyBackupService backup=backup(j,scope);byte[] ordinary=backup.exportArchive();
  when(scope.requireUserId()).thenReturn(RESTORER);long restored=restore(j,backup,ordinary);detached(j,restored);originalBlocked(s,source,original);
  JSONObject crafted=backup.readArchive(ordinary);crafted.getJSONObject("tables").getJSONArray("patient").getJSONObject(0).put("name","Craft "+UUID.randomUUID().toString().substring(0,8));JSONObject task=crafted.getJSONObject("tables").getJSONArray("health_analysis_automation").getJSONObject(0);task.put("active_run_id",original.run.getId());task.put("config_version",999);task.put("last_run_status","NOTIFY_UNKNOWN");task.put("last_error","Foreign runtime");task.put("last_analysis_record_id",999999);task.put("notification_channel_ids","999999");long forged=restore(j,backup,zip(crafted));detached(j,forged);originalBlocked(s,source,original);
  assertThrows(HealthAnalysisRunConflictException.class,()->s.claim(forged,RESTORER,false,true,original.run.getId(),"UNKNOWN",NEXT));originalBlocked(s,source,original);
  // An independent, disabled copy can be configured and explicitly run as a new task.
  HealthAnalysisAutomation config=row(j,restored);config.setTaskName("Configured independent copy");s.updateConfiguration(config,0L);HealthAnalysisRunStore.Claim fresh=s.claim(restored,RESTORER,false,false,null,null,NEXT);assertNotEquals(original.run.getId(),fresh.run.getId());assertEquals(restored,fresh.run.getAutomationId());assertEquals(RESTORER,fresh.settings.getUserId());assertEquals(0,fresh.settings.getEnabled());originalBlocked(s,source,original);
 }
 public static void forgedPointerCannotAcknowledgeOtherOwner(JdbcTemplate j){
  long patient=patient(j),source=task(j,OWNER,patient),forged=task(j,RESTORER,patient);HealthAnalysisRunStore s=store(j);HealthAnalysisRunStore.Claim original=uncertain(j,source,OWNER);j.update("UPDATE health_analysis_automation SET active_run_id=? WHERE id=?",original.run.getId(),forged);
  HealthAnalysisAutomationService service=new HealthAnalysisAutomationService();DataScopeHelper scope=mock(DataScopeHelper.class);when(scope.requireUserId()).thenReturn(RESTORER);HealthAnalysisAutomationMapper tasks=mock(HealthAnalysisAutomationMapper.class);when(tasks.selectById(any())).thenAnswer(a->row(j,((Number)a.getArgument(0)).longValue()));AiAnalysisService ai=mock(AiAnalysisService.class);NotificationDeliveryService delivery=mock(NotificationDeliveryService.class);when(ai.analyzeHealthSnapshot(anyLong(),anyInt(),anyCollection())).thenReturn("Synthetic body");ReflectionTestUtils.setField(service,"mapper",tasks);ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"runStore",s);ReflectionTestUtils.setField(service,"aiAnalysis",ai);ReflectionTestUtils.setField(service,"delivery",delivery);
  long runs=j.queryForObject("SELECT COUNT(*) FROM health_analysis_run",Long.class),drafts=j.queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Long.class);
  assertThrows(HealthAnalysisRunConflictException.class,()->service.runNow(forged,true,original.run.getId(),"UNKNOWN"));originalBlocked(s,source,original);assertThrows(HealthAnalysisRunConflictException.class,()->service.runNow(forged));verifyNoInteractions(ai,delivery);assertEquals(runs,j.queryForObject("SELECT COUNT(*) FROM health_analysis_run",Long.class));assertEquals(drafts,j.queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Long.class));
 }
 public static void invalidPointersFailClosedAcrossStages(JdbcTemplate j){
  long patient=patient(j);HealthAnalysisRunStore s=store(j);
  for(String state:Arrays.asList("CLAIMED","GENERATING","DRAFT_SAVED","NOTIFYING","UNKNOWN","NOTIFY_UNKNOWN","COMPLETE","FAILED")){
   long source=task(j,OWNER,patient),forged=task(j,RESTORER,patient);HealthAnalysisRunStore.Claim original=s.claim(source,OWNER,false,false,null,null,NEXT);
   boolean hasDraft=Arrays.asList("DRAFT_SAVED","NOTIFYING","NOTIFY_UNKNOWN","COMPLETE").contains(state);
   if(hasDraft){s.advance(original,"CLAIMED","GENERATING");AiAnalysisRecord draft=new AiAnalysisRecord();draft.setUserId(OWNER);draft.setPatientId(patient);draft.setAnalysisContent("Synthetic retained draft "+state);draft.setReviewStatus("REVIEW_REQUIRED");s.saveDraft(original,draft);}
   j.update("UPDATE health_analysis_run SET state=?,updated_at=?,notification_outcomes=? WHERE id=?",state,s.databaseNow().minusMinutes(16),"{\"4\":\"DELIVERED\",\"5\":\"UNKNOWN\"}",original.run.getId());j.update("UPDATE health_analysis_automation SET active_run_id=? WHERE id=?",original.run.getId(),forged);
   Map<String,Object> before=j.queryForMap("SELECT * FROM health_analysis_run WHERE id=?",original.run.getId());
   long sameOwnerTask=task(j,OWNER,patient);j.update("UPDATE health_analysis_automation SET active_run_id=? WHERE id=?",original.run.getId(),sameOwnerTask);assertThrows(HealthAnalysisRunConflictException.class,()->s.current(sameOwnerTask),state);assertThrows(HealthAnalysisRunConflictException.class,()->s.claim(sameOwnerTask,OWNER,false,false,null,null,NEXT),state);assertThrows(HealthAnalysisRunConflictException.class,()->s.claim(sameOwnerTask,OWNER,false,true,original.run.getId(),state,NEXT),state);assertFalse(s.candidates().stream().anyMatch(r->r.getId()==sameOwnerTask),state);
   assertThrows(HealthAnalysisRunConflictException.class,()->s.current(forged),state);assertThrows(HealthAnalysisRunConflictException.class,()->s.claim(forged,RESTORER,false,false,null,null,NEXT),state);assertThrows(HealthAnalysisRunConflictException.class,()->s.claim(forged,RESTORER,false,true,original.run.getId(),state,NEXT),state);assertFalse(s.candidates().stream().anyMatch(r->r.getId()==forged),state);
   // Only the malformed pointer references this run while recovery runs. It must not recover a foreign journal.
   j.update("UPDATE health_analysis_automation SET active_run_id=NULL,enabled=0 WHERE id=?",source);s.recoverUncertain();j.update("UPDATE health_analysis_automation SET active_run_id=? WHERE id=?",original.run.getId(),source);
   assertEquals(before,j.queryForMap("SELECT * FROM health_analysis_run WHERE id=?",original.run.getId()),state);
   HealthAnalysisRun foreign=JSON.parseObject(JSON.toJSONString(original.run),HealthAnalysisRun.class);foreign.setAutomationId(forged);foreign.setState(state);HealthAnalysisRunStore.Claim malformed=new HealthAnalysisRunStore.Claim(foreign,row(j,forged),null);
   assertThrows(HealthAnalysisRunConflictException.class,()->s.advance(malformed,state,"FAILED"),state);
   HealthAnalysisAutomation wrongOwner=row(j,source);wrongOwner.setUserId(RESTORER);HealthAnalysisRunStore.Claim wrongOwnerClaim=new HealthAnalysisRunStore.Claim(original.run,wrongOwner,null);assertThrows(HealthAnalysisRunConflictException.class,()->s.advance(wrongOwnerClaim,state,"FAILED"),state);
   assertEquals(before,j.queryForMap("SELECT * FROM health_analysis_run WHERE id=?",original.run.getId()),state);
   if(hasDraft)assertEquals("Synthetic retained draft "+state,j.queryForObject("SELECT analysis_content FROM ai_analysis_record WHERE id=?",String.class,original.run.getAnalysisRecordId()),state);
  }
  long missing=task(j,RESTORER,patient);j.update("UPDATE health_analysis_automation SET active_run_id=9223372036854775807 WHERE id=?",missing);assertThrows(HealthAnalysisRunConflictException.class,()->s.current(missing));assertThrows(HealthAnalysisRunConflictException.class,()->s.claim(missing,RESTORER,false,false,null,null,NEXT));assertThrows(HealthAnalysisRunConflictException.class,()->s.claim(missing,RESTORER,false,true,9223372036854775807L,"UNKNOWN",NEXT));s.recoverUncertain();assertFalse(s.candidates().stream().anyMatch(r->r.getId()==missing));assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM health_analysis_run WHERE automation_id=?",Integer.class,missing));
  HealthAnalysisRun absent=new HealthAnalysisRun();absent.setId(9223372036854775807L);absent.setAutomationId(missing);absent.setClaimToken("synthetic-missing");HealthAnalysisRunStore.Claim missingClaim=new HealthAnalysisRunStore.Claim(absent,row(j,missing),null);assertThrows(HealthAnalysisRunConflictException.class,()->s.advance(missingClaim,"CLAIMED","GENERATING"));
  int drafts=j.queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class);assertThrows(HealthAnalysisRunConflictException.class,()->s.saveDraft(missingClaim,new AiAnalysisRecord()));assertEquals(drafts,j.queryForObject("SELECT COUNT(*) FROM ai_analysis_record",Integer.class));
 }
 public static void verify(JdbcTemplate j)throws Exception{restoreCopiesDetachRuntime(j);forgedPointerCannotAcknowledgeOtherOwner(j);invalidPointersFailClosedAcrossStages(j);}
}
