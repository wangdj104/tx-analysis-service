package org.familyhealthcare.service;

import com.alibaba.fastjson2.JSON;
import org.familyhealthcare.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiFunction;
import static org.familyhealthcare.util.ExportLocalization.text;

/** Short database transactions only. No AI or notification transport may run inside this store. */
@Service
public class HealthAnalysisRunStore {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 private final BeanPropertyRowMapper<HealthAnalysisAutomation> tasks=BeanPropertyRowMapper.newInstance(HealthAnalysisAutomation.class);
 private final BeanPropertyRowMapper<HealthAnalysisRun> runs=BeanPropertyRowMapper.newInstance(HealthAnalysisRun.class);
 private static final Set<String> TERMINAL=new HashSet<>(Arrays.asList("COMPLETE","FAILED"));
 public HealthAnalysisRunStore(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public LocalDateTime databaseNow(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toLocalDateTime();}
 public HealthAnalysisRun current(Long taskId){return tx.execute(s->{
  List<HealthAnalysisAutomation> found=jdbc.query("SELECT * FROM health_analysis_automation WHERE id=? FOR UPDATE",tasks,taskId);
  if(found.isEmpty())return null;
  return activeRun(found.get(0));
 });}
 public static final class Claim {
  public final HealthAnalysisRun run;public final HealthAnalysisAutomation settings;public final Long existingDraftId;
  Claim(HealthAnalysisRun run,HealthAnalysisAutomation settings,Long existingDraftId){this.run=run;this.settings=settings;this.existingDraftId=existingDraftId;}
 }
 private HealthAnalysisAutomation lock(Long id){List<HealthAnalysisAutomation> found=jdbc.query("SELECT * FROM health_analysis_automation WHERE id=? FOR UPDATE",tasks,id);if(found.isEmpty())throw new HealthAnalysisRunConflictException();return found.get(0);}
 private HealthAnalysisRun lockRun(Long id,Long automationId){List<HealthAnalysisRun> found=jdbc.query("SELECT * FROM health_analysis_run WHERE id=? AND automation_id=? FOR UPDATE",runs,id,automationId);if(found.isEmpty())throw new HealthAnalysisRunConflictException();return found.get(0);}
 /** A non-null runtime pointer is valid only for this exact task; never treat corruption as empty history. */
 private HealthAnalysisRun activeRun(HealthAnalysisAutomation task){
  if(task.getActiveRunId()==null)return null;
  HealthAnalysisRun run=lockRun(task.getActiveRunId(),task.getId());
  if(!Objects.equals(run.getAutomationId(),task.getId()))throw new HealthAnalysisRunConflictException();
  return run;
 }
 private boolean stale(HealthAnalysisRun run,LocalDateTime now){return run.getUpdatedAt().isBefore(now.minusMinutes(15));}
 private boolean unknown(String state){return "UNKNOWN".equals(state)||"NOTIFY_UNKNOWN".equals(state);}
 private void status(HealthAnalysisAutomation task,String state,String error,Long draft){jdbc.update("UPDATE health_analysis_automation SET last_run_status=?,last_error=?,last_analysis_record_id=?,last_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",state,error,draft,task.getId());}
 private void mark(HealthAnalysisRun run,String state,String error){jdbc.update("UPDATE health_analysis_run SET state=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",state,error,run.getId());run.setState(state);run.setLastError(error);}
 private void recoverAmbiguous(HealthAnalysisAutomation task,HealthAnalysisRun run,LocalDateTime now){
  if(stale(run,now)&&("GENERATING".equals(run.getState())||"NOTIFYING".equals(run.getState()))){String state="GENERATING".equals(run.getState())?"UNKNOWN":"NOTIFY_UNKNOWN";mark(run,state,text("External operation was interrupted; confirmation is required.","外部操作中断，需要人工确认。"));status(task,state,run.getLastError(),run.getAnalysisRecordId());}
 }
 public Claim claim(Long taskId,Long userId,boolean scheduled,boolean acknowledge,Long observedRunId,String observedState,BiFunction<HealthAnalysisAutomation,LocalDateTime,LocalDateTime> next){
  return tx.execute(s->{
   HealthAnalysisAutomation task=lock(taskId);if(!Objects.equals(task.getUserId(),userId))throw new HealthAnalysisRunConflictException();
   LocalDateTime now=databaseNow();HealthAnalysisRun active=activeRun(task);
   if(active!=null)recoverAmbiguous(task,active,now);
   if(acknowledge){
    if(active==null||!unknown(active.getState())||observedRunId==null||!Objects.equals(active.getId(),observedRunId)||!Objects.equals(active.getState(),observedState))throw new HealthAnalysisRunConflictException();
    if(active.getAnalysisRecordId()!=null){HealthAnalysisAutomation snapshot=JSON.parseObject(active.getSettingsJson(),HealthAnalysisAutomation.class);mark(active,"COMPLETE",null);status(task,"DRAFT_READY_NOTIFY_UNCERTAIN",null,active.getAnalysisRecordId());adjustManualSchedule(active,snapshot,next);return new Claim(active,snapshot,active.getAnalysisRecordId());}
    mark(active,"FAILED",text("Uncertain generation acknowledged by owner.","所有者已确认不确定的生成结果。"));
   } else if(active!=null&&!TERMINAL.contains(active.getState())){
    if(unknown(active.getState())||!stale(active,now)||!("CLAIMED".equals(active.getState())||"DRAFT_SAVED".equals(active.getState())))throw new HealthAnalysisRunConflictException();
    // A new token fences the old executor. Keep the exact settings/schedule reservation and saved draft.
    String token=UUID.randomUUID().toString();jdbc.update("UPDATE health_analysis_run SET claim_token=?,claimed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",token,active.getId());active.setClaimToken(token);
    status(task,active.getState(),null,active.getAnalysisRecordId());return new Claim(active,JSON.parseObject(active.getSettingsJson(),HealthAnalysisAutomation.class),null);
   }
   if(scheduled&&(!Integer.valueOf(1).equals(task.getEnabled())||task.getNextRunAt()==null||task.getNextRunAt().isAfter(now)))throw new HealthAnalysisRunConflictException();
   HealthAnalysisRun run=new HealthAnalysisRun();run.setAutomationId(taskId);run.setClaimToken(UUID.randomUUID().toString());run.setState("CLAIMED");run.setScheduled(scheduled?1:0);run.setScheduledFor(task.getNextRunAt());run.setSettingsJson(JSON.toJSONString(task));
   KeyHolder key=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO health_analysis_run(automation_id,claim_token,state,scheduled,scheduled_for,settings_json) VALUES(?,?,?,?,?,?)",new String[]{"id"});p.setLong(1,taskId);p.setString(2,run.getClaimToken());p.setString(3,"CLAIMED");p.setInt(4,scheduled?1:0);p.setTimestamp(5,task.getNextRunAt()==null?null:Timestamp.valueOf(task.getNextRunAt()));p.setString(6,run.getSettingsJson());return p;},key);run.setId(key.getKey().longValue());
   jdbc.update("UPDATE health_analysis_automation SET active_run_id=?,last_run_status='CLAIMED',last_error=NULL,last_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",run.getId(),taskId);
   if(scheduled)jdbc.update("UPDATE health_analysis_automation SET next_run_at=? WHERE id=?",next.apply(task,now),taskId);
   return new Claim(run,task,null);
  });
 }
 private HealthAnalysisRun fence(Claim claim,String expected){HealthAnalysisAutomation task=lock(claim.run.getAutomationId());
  if(claim.settings==null||!Objects.equals(task.getId(),claim.settings.getId())||!Objects.equals(task.getUserId(),claim.settings.getUserId())||!Objects.equals(task.getActiveRunId(),claim.run.getId()))throw new HealthAnalysisRunConflictException();
  HealthAnalysisRun run=activeRun(task);
  if(run==null||!Objects.equals(run.getClaimToken(),claim.run.getClaimToken())||!Objects.equals(run.getState(),expected))throw new HealthAnalysisRunConflictException();return run;
 }
 public void advance(Claim claim,String expected,String state){tx.executeWithoutResult(s->{HealthAnalysisRun run=fence(claim,expected);mark(run,state,null);status(lock(claim.run.getAutomationId()),state,null,run.getAnalysisRecordId());});claim.run.setState(state);}
 public AiAnalysisRecord saveDraft(Claim claim,AiAnalysisRecord record){return tx.execute(s->{HealthAnalysisRun run=fence(claim,"GENERATING");KeyHolder key=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO ai_analysis_record(user_id,patient_id,time_type,time_value,period_label,analysis_content,review_status,remark) VALUES(?,?,?,?,?,?,?,?)",new String[]{"id"});p.setObject(1,record.getUserId());p.setObject(2,record.getPatientId());p.setString(3,record.getTimeType());p.setString(4,record.getTimeValue());p.setString(5,record.getPeriodLabel());p.setString(6,record.getAnalysisContent());p.setString(7,record.getReviewStatus());p.setString(8,record.getRemark());return p;},key);record.setId(key.getKey().longValue());
  jdbc.update("UPDATE health_analysis_run SET analysis_record_id=?,state='DRAFT_SAVED',updated_at=CURRENT_TIMESTAMP WHERE id=?",record.getId(),run.getId());status(lock(claim.run.getAutomationId()),"DRAFT_SAVED",null,record.getId());claim.run.setAnalysisRecordId(record.getId());claim.run.setState("DRAFT_SAVED");return record;});}
 public void recordNotificationOutcome(Claim claim,Long channelId,org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome outcome){tx.executeWithoutResult(s->{HealthAnalysisRun run=fence(claim,"NOTIFYING");com.alibaba.fastjson2.JSONObject results=run.getNotificationOutcomes()==null?new com.alibaba.fastjson2.JSONObject():JSON.parseObject(run.getNotificationOutcomes());results.put(String.valueOf(channelId),outcome.name());jdbc.update("UPDATE health_analysis_run SET notification_outcomes=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",results.toJSONString(),run.getId());});}
 public void finish(Claim claim,String state,String displayStatus,String error,String outcomes,BiFunction<HealthAnalysisAutomation,LocalDateTime,LocalDateTime> next){tx.executeWithoutResult(s->{HealthAnalysisRun run=fence(claim,"NOTIFYING");jdbc.update("UPDATE health_analysis_run SET state=?,last_error=?,notification_outcomes=COALESCE(?,notification_outcomes),updated_at=CURRENT_TIMESTAMP WHERE id=?",state,error,outcomes,run.getId());HealthAnalysisAutomation task=lock(run.getAutomationId());status(task,displayStatus,error,run.getAnalysisRecordId());
   if("COMPLETE".equals(state))adjustManualSchedule(run,claim.settings,next);
  });claim.run.setState(state);}
 private void adjustManualSchedule(HealthAnalysisRun run,HealthAnalysisAutomation snapshot,BiFunction<HealthAnalysisAutomation,LocalDateTime,LocalDateTime> next){
  if(Integer.valueOf(0).equals(run.getScheduled())&&Integer.valueOf(1).equals(snapshot.getEnabled()))jdbc.update("UPDATE health_analysis_automation SET next_run_at=? WHERE id=? AND config_version=? AND enabled=1 AND (next_run_at=? OR (next_run_at IS NULL AND ? IS NULL))",next.apply(snapshot,databaseNow()),run.getAutomationId(),snapshot.getConfigVersion(),snapshot.getNextRunAt(),snapshot.getNextRunAt());
 }
 public void fail(Claim claim,boolean uncertain){tx.executeWithoutResult(s->{HealthAnalysisRun run=fence(claim,"GENERATING");String state=uncertain?"UNKNOWN":"FAILED";String error=uncertain?text("AI result is uncertain; confirmation is required.","AI 结果不确定，需要人工确认。"):text("AI analysis failed; no draft was saved.","AI 分析失败，未保存草稿。");mark(run,state,error);status(lock(run.getAutomationId()),state,error,null);});claim.run.setState(uncertain?"UNKNOWN":"FAILED");}
 public void failBeforeGeneration(Claim claim){tx.executeWithoutResult(s->{HealthAnalysisRun run=fence(claim,"CLAIMED");mark(run,"FAILED",text("Generation could not be started.","无法启动生成。"));status(lock(run.getAutomationId()),"FAILED",run.getLastError(),null);});}
 public void recoverUncertain(){List<Long> ids=jdbc.queryForList("SELECT a.id FROM health_analysis_automation a JOIN health_analysis_run r ON r.id=a.active_run_id AND r.automation_id=a.id WHERE r.state IN ('GENERATING','NOTIFYING') AND r.updated_at<?",Long.class,databaseNow().minusMinutes(15));for(Long id:ids)try{tx.executeWithoutResult(s->{HealthAnalysisAutomation task=lock(id);HealthAnalysisRun run=activeRun(task);if(run!=null)recoverAmbiguous(task,run,databaseNow());});}catch(HealthAnalysisRunConflictException deleted){/* Deleted tasks remain fenced. */}}
 public List<HealthAnalysisAutomation> candidates(){LocalDateTime now=databaseNow();return jdbc.query("SELECT a.* FROM health_analysis_automation a LEFT JOIN health_analysis_run r ON r.id=a.active_run_id AND r.automation_id=a.id WHERE (a.active_run_id IS NULL OR r.id IS NOT NULL) AND ((r.state IN ('CLAIMED','DRAFT_SAVED') AND r.updated_at<?) OR (a.enabled=1 AND a.next_run_at<=? AND (a.active_run_id IS NULL OR r.state IN ('COMPLETE','FAILED')))) ORDER BY a.next_run_at,a.id LIMIT 20",tasks,now.minusMinutes(15),now);}
 public void updateConfiguration(HealthAnalysisAutomation row,Long expected){int changed=jdbc.update("UPDATE health_analysis_automation SET patient_id=?,task_name=?,enabled=?,frequency_type=?,interval_days=?,day_of_week=?,day_of_month=?,run_time=?,analysis_range_days=?,analysis_items=?,notification_channel_ids=?,next_run_at=?,config_version=config_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND config_version=?",row.getPatientId(),row.getTaskName(),row.getEnabled(),row.getFrequencyType(),row.getIntervalDays(),row.getDayOfWeek(),row.getDayOfMonth(),row.getRunTime(),row.getAnalysisRangeDays(),row.getAnalysisItems(),row.getNotificationChannelIds(),row.getNextRunAt(),row.getId(),row.getUserId(),expected);if(changed!=1)throw new HealthAnalysisRunConflictException();}
}
