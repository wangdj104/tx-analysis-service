package org.familyhealthcare.service.visitfollowup;

import org.familyhealthcare.service.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import java.sql.Connection;
import java.util.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpContracts.*;
import static org.familyhealthcare.service.visitfollowup.VisitFollowUpRepository.*;

/** 旧入口/B的窄边界：历史可读不授予新写能力，不能在B锁后再取得预约A锁。 */
@Service
public class VisitFollowUpSourceBridge {
    private final JdbcTemplate jdbc;
    private final VisitFollowUpRepository repo;
    private final VisitFollowUpProperties properties;
    private final VisitFollowUpAuthorizationService authorization;
    private final org.familyhealthcare.service.NotificationDeliveryService delivery;
    private final org.springframework.transaction.support.TransactionTemplate reminderTransaction;
    private final Object reminderOwner=new Object();
    public VisitFollowUpSourceBridge(JdbcTemplate jdbc,VisitFollowUpRepository repo,VisitFollowUpProperties properties,VisitFollowUpAuthorizationService authorization,org.familyhealthcare.service.NotificationDeliveryService delivery,org.springframework.transaction.PlatformTransactionManager transactions) {
        this.jdbc=Objects.requireNonNull(jdbc);this.repo=Objects.requireNonNull(repo);this.properties=Objects.requireNonNull(properties);this.authorization=Objects.requireNonNull(authorization);this.delivery=Objects.requireNonNull(delivery);
        reminderTransaction=new org.springframework.transaction.support.TransactionTemplate(Objects.requireNonNull(transactions));
        reminderTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        reminderTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reminderTransaction.setTimeout(5);
    }
    public boolean storageAvailable() { return availability().isReadAvailable(); }
    /** 一次完整结构快照；FULL_A保留历史，所有新操作明确要求FULL_B。 */
    public VisitFollowUpSchema.Availability availability() { return availability(inspectStorage().getProfile()); }
    private HouseholdWorkflowSchema.Compatibility inspectStorage() {
        Connection connection=DataSourceUtils.getConnection(Objects.requireNonNull(jdbc.getDataSource()));
        try{return HouseholdWorkflowSchema.inspectCompatibility(connection);}finally{DataSourceUtils.releaseConnection(connection,jdbc.getDataSource());}
    }
    private VisitFollowUpSchema.Availability availability(HouseholdWorkflowSchema.Profile profile) {
        if(profile==HouseholdWorkflowSchema.Profile.FULLY_INSTALLED)return VisitFollowUpSchema.Availability.REMINDER_EXTENSION_REQUIRED;
        if(profile==HouseholdWorkflowSchema.Profile.FULLY_INSTALLED_REMINDER)return VisitFollowUpSchema.Availability.FULLY_READY;
        if(!properties.isEnabled()&&Arrays.asList(HouseholdWorkflowSchema.Profile.ABSENT,HouseholdWorkflowSchema.Profile.EXACT_OLD).contains(profile))return VisitFollowUpSchema.Availability.ABSENT;
        throw new WorkflowException(503,"VISIT_FOLLOWUP_SCHEMA_NOT_READY","Appointment follow-up storage could not be verified.");
    }
    public boolean isTracked(SourceKind kind,long source) {
        if(!kind.isAppointment())throw invalid("An appointment source is required.");
        return storageAvailable()&&repo.state(new SourceRef(kind,source))!=null;
    }
    /** 必须先于旧原生/B锁；患者锁也保护尚未存在的纳管状态，关功能不能恢复旧写。 */
    public void requireLegacyWrite(long patient,SourceKind kind,long source) {
        WorkflowData.mutationTransaction(jdbc);
        repo.lock("patient",patient,false);
        if(!storageAvailable())return;
        Map<String,Object> state=repo.state(new SourceRef(kind,source));
        if(state!=null)throw new WorkflowException(409,"VISIT_FOLLOWUP_REQUIRED",kind==SourceKind.VISIT_RECORD?"Use the current visit follow-up command and compare its version.":"Use the current appointment follow-up command and compare its version.");
    }
    /** 新摘要必须明确填写真实时间；开启后不再让旧创建入口补now。 */
    public void requireLegacyVisitCreation() {
        if(properties.isEnabled())throw new WorkflowException(409,"VISIT_FOLLOWUP_REQUIRED","Use the current visit follow-up command and enter the actual visit time.");
    }
    public boolean terminalManaged(String nativeKind,long source) {
        SourceKind kind="CARE_ITEM".equals(nativeKind)?SourceKind.CARE_ITEM_APPOINTMENT:"CARE_APPOINTMENT".equals(nativeKind)?SourceKind.CARE_APPOINTMENT:null;
        if(kind==null||!storageAvailable())return false;
        Map<String,Object> state=repo.state(new SourceRef(kind,source));
        if(state==null)return false;
        NativeSource current=repo.source(new SourceRef(kind,source));
        if(current.getPatientId()!=number(state.get("patient_id")))throw integrity();
        return Arrays.asList("NO_SHOW","CANCELLED").contains(current.getValues().get("status"));
    }
    public void requireAssistanceAction(String nativeKind,long source,String operation) {
        if(terminalManaged(nativeKind,source)&&!Arrays.asList("CANCEL","CLOSE_UNRESOLVED","REJECT","RETURN").contains(operation))
            throw new WorkflowException(409,"APPOINTMENT_SOURCE_ENDED","This appointment no longer permits a new accompaniment action.");
    }
    public List<String> filterAssistanceCommands(String nativeKind,long source,List<String> commands) {
        List<String> result=new ArrayList<>(commands);
        if(terminalManaged(nativeKind,source))result.retainAll(Arrays.asList("CANCEL","CLOSE_UNRESOLVED","REJECT","RETURN"));
        return result;
    }
    /** 只返回固定SQL片段；旧未纳管来源继续使用原键集与候选条件。 */
    public String excludeTrackedModernRecurrence() {
        return storageAvailable()?" AND NOT EXISTS(SELECT 1 FROM visit_followup_state vf WHERE vf.source_kind='CARE_APPOINTMENT' AND vf.care_appointment_id=a.id)":"";
    }
    public String excludeTrackedModernMarker() {
        return storageAvailable()?" AND NOT EXISTS(SELECT 1 FROM visit_followup_state vf WHERE vf.source_kind='CARE_APPOINTMENT' AND vf.care_appointment_id=care_appointment.id)":"";
    }
    /** 任务候选只用于比对；可发送能力必须来自本轮CLAIM已确认的独立提交。 */
    public boolean remindAppointment(SourceKind kind,long source,long patient,Map<String,Object> expectedNative) {
        if(!kind.isAppointment())throw invalid("An appointment source is required.");
        SourceRef ref=new SourceRef(kind,source);id(patient);
        VisitFollowUpSchema.Availability available=availability();
        if(!available.isReadAvailable())return false;
        Map<String,Object> state=repo.state(ref);if(state==null)return false;
        // 已纳管即终止旧提醒/升级入口；缺扩展或关功能仍不能回退旧协议。
        if(!available.isWriteAvailable(properties.isEnabled()))return true;
        noTransaction();
        NativeSource selected=repo.source(ref);
        if(selected.getPatientId()!=patient||expectedNative==null||!canonical(selected.nativeTarget()).equals(canonical(expectedNative)))return true;
        ReminderAttempt attempt=claimReminder(ref,patient,selected.getFingerprint());
        if(attempt==null)return true;
        for(long recipient:attempt.recipients) {
            noTransaction();final boolean[] observerFailed={false};
            org.familyhealthcare.service.NotificationDeliveryService.GuardedBatchResult result=delivery.notifyUserOutcomesGuarded(recipient,Collections.emptyList(),REMINDER_EVENT,REMINDER_TITLE,
                    "Upcoming appointment at "+attempt.nativeTarget.get("native_start_wall")+". Open the shared schedule for details.",
                    new org.familyhealthcare.service.NotificationDeliveryService.BeforeAttemptGate(){
                        public org.familyhealthcare.service.NotificationDeliveryService.GateDecision beforeRecipient(Long user){return currentReminderGate(attempt,user);}
                        public org.familyhealthcare.service.NotificationDeliveryService.GateDecision beforeChannel(Long user,Long channel){id(channel);return currentReminderGate(attempt,user);}
                    },(channel,outcome)->{
                        String status=outcome.name();String reason="DELIVERED".equals(status)?null:"FAILED".equals(status)?"PROVIDER_FAILED":"PROVIDER_UNKNOWN";
                        try {LoggedReminderOutcome proof=recordReminderOutcome(attempt,recipient,channel,status,reason);
                            if("DELIVERED".equals(status))bindDeliveredReminder(attempt,proof);
                        }catch(RuntimeException uncertain){observerFailed[0]=true;throw uncertain;}
                    });
            // observer持久化失败已被guarded转成STOP；绝不能在外层继续下一收件人。
            if(result.isStopRemaining()&&observerFailed[0])break;
            try {
                if(result.isNoEnabledChannels())recordReminderOutcome(attempt,recipient,null,"NO_CHANNEL","NO_ENABLED_CHANNELS");
                else if(result.isRecipientSkipped())recordReminderOutcome(attempt,recipient,null,"SKIPPED","RECIPIENT_UNAVAILABLE");
                for(Map.Entry<Long,String> skipped:result.getSkippedChannels().entrySet())recordReminderOutcome(attempt,recipient,skipped.getKey(),"SKIPPED",skipped.getValue());
            }catch(RuntimeException journalUncertain){break;}
            if(result.isStopRemaining())break;
        }
        return true;
    }
    private ReminderAttempt claimReminder(SourceRef ref,long patient,String fingerprint) {
        noTransaction();String attemptId=UUID.randomUUID().toString();java.time.Instant attemptedAt=properties.now();
        ReminderDiscovery committed=reminderTransaction.execute(transaction->{
            ReminderScope scope=admitReminder(ref,patient,Collections.emptySet());ReminderDiscovery d=scope.discovery;
            if(!fingerprint.equals(d.source.getFingerprint())||!eligibleSource(d,true)||d.recipients.isEmpty())return null;
            Long consumed=nullable(d.state.get("reminder_attempt_source_revision"));long revision=number(d.state.get("source_revision"));
            if(consumed!=null&&consumed>=revision)return null;
            int changed=repo.update("UPDATE visit_followup_state SET reminder_attempt_source_revision=?,reminder_attempt_id=?,reminder_attempted_at_utc=?,reminder_delivery_log_id=NULL WHERE id=? AND patient_id=? AND lock_version=? AND source_revision=? AND source_fingerprint=? AND attendance='UNVERIFIED' AND (reminder_attempt_source_revision IS NULL OR reminder_attempt_source_revision<source_revision) AND reminder_delivery_log_id <=> ?",revision,attemptId,attemptedAt,d.state.get("id"),patient,d.state.get("lock_version"),revision,fingerprint,d.state.get("reminder_delivery_log_id"));
            if(changed!=1)throw integrity();
            scope.expectedState.put("reminder_attempt_source_revision",revision);scope.expectedState.put("reminder_attempt_id",attemptId);scope.expectedState.put("reminder_attempted_at_utc",attemptedAt);scope.expectedState.put("reminder_delivery_log_id",null);
            scope.verify();scope.beforeCommit();return d;
        });
        // execute只有确认commit后才返回；未知提交抛出时不产生attempt，不调用provider。
        return committed==null?null:new ReminderAttempt(committed,attemptId,attemptedAt);
    }
    private org.familyhealthcare.service.NotificationDeliveryService.GateDecision currentReminderGate(ReminderAttempt attempt,Long recipient) {
        noTransaction();attempt.requireLive();
        boolean allowed=Boolean.TRUE.equals(reminderTransaction.execute(transaction->{
            ReminderScope scope=admitReminder(attempt.source,attempt.patient,Collections.emptySet());
            if(!currentAttempt(scope.discovery,attempt)||recipient==null||!attempt.recipients.contains(recipient)||!scope.discovery.recipients.contains(recipient))return false;
            scope.beforeCommit();return true;
        }));
        noTransaction();return allowed?org.familyhealthcare.service.NotificationDeliveryService.GateDecision.ALLOW:org.familyhealthcare.service.NotificationDeliveryService.GateDecision.STOP;
    }
    private LoggedReminderOutcome recordReminderOutcome(ReminderAttempt attempt,long recipient,Long channel,String status,String reason) {
        noTransaction();attempt.requireLive();if(!attempt.recipients.contains(recipient))throw integrity();
        String key=recipient+":"+channel;String outcome=status+":"+reason;
        JournalEntry previous=attempt.journal.get(key);
        if(previous!=null){if(!outcome.equals(previous.outcome)||previous.proof==null)throw integrity();return previous.proof;}
        JournalEntry entry=new JournalEntry(outcome);attempt.journal.put(key,entry);
        String detail=WorkflowData.json(WorkflowData.map("attemptId",attempt.id,"stateId",attempt.state,"sourceKind",attempt.source.getKind().name(),"sourceId",attempt.source.getId(),"sourceRevision",attempt.revision,"channelId",channel,"reasonCode",reason));
        Long log=reminderTransaction.execute(transaction->repo.insertReminderDeliveryLog(recipient,attempt.patient,status,detail));
        if(log==null)throw integrity();
        // 在途结果即使已改期/撤权也照实记录原attempt；journal事务绝不持log锁调用binder。
        entry.proof=new LoggedReminderOutcome(attempt,id(log),recipient,channel,status,detail);return entry.proof;
    }
    private enum ReminderBindingResult { BOUND, ALREADY_BOUND, STALE }
    private ReminderBindingResult bindDeliveredReminder(ReminderAttempt attempt,LoggedReminderOutcome proof) {
        noTransaction();attempt.requireLive();
        if(proof==null||proof.attempt!=attempt||!"DELIVERED".equals(proof.status)||proof.channel==null||attempt.journal.get(proof.recipient+":"+proof.channel)==null||attempt.journal.get(proof.recipient+":"+proof.channel).proof!=proof)throw integrity();
        return reminderTransaction.execute(transaction->{
            ReminderScope scope=admitReminder(attempt.source,attempt.patient,Collections.singleton(proof.id));ReminderDiscovery d=scope.discovery;
            requireProof(d.logs.get(proof.id),attempt,proof.id);
            if(number(d.logs.get(proof.id).get("recipient_user_id"))!=proof.recipient||!proof.detail.equals(d.logs.get(proof.id).get("detail")))throw integrity();
            if(!currentAttempt(d,attempt)||!d.recipients.contains(proof.recipient))return ReminderBindingResult.STALE;
            Long bound=nullable(d.state.get("reminder_delivery_log_id"));
            if(bound!=null){requireProof(d.logs.get(bound),attempt,bound);if(d.marker.get("notified_wall")==null)throw integrity();scope.beforeCommit();return ReminderBindingResult.ALREADY_BOUND;}
            if(d.marker.get("notified_wall")!=null)return ReminderBindingResult.STALE;
            int changed=repo.update("UPDATE visit_followup_state SET reminder_delivery_log_id=? WHERE id=? AND patient_id=? AND source_revision=? AND source_fingerprint=? AND reminder_attempt_source_revision=? AND reminder_attempt_id=? AND reminder_attempted_at_utc=? AND reminder_delivery_log_id IS NULL AND attendance='UNVERIFIED'",proof.id,attempt.state,attempt.patient,attempt.revision,attempt.fingerprint,attempt.revision,attempt.id,attempt.attemptedAt);
            if(changed!=1)throw integrity();
            java.time.LocalDateTime now=wallNow().withNano(0);Map<String,Object> n=attempt.nativeTarget;
            if(attempt.source.getKind()==SourceKind.CARE_ITEM_APPOINTMENT)
                changed=repo.update("UPDATE care_item SET notified_at=? WHERE id=? AND patient_id=? AND kind='APPOINTMENT' AND status='OPEN' AND event_at <=> ? AND notify_at <=> ? AND notified_at IS NULL AND family_workflow_version=0 AND family_assistance_id IS NULL",now,attempt.source.getId(),attempt.patient,n.get("native_start_wall"),n.get("native_notify_wall"));
            else changed=repo.update("UPDATE care_appointment SET notified_at=? WHERE id=? AND patient_id=? AND status='BOOKED' AND start_at=? AND end_at=? AND doctor_user_id=? AND schedule_id <=> ? AND notified_at IS NULL AND family_workflow_version=0 AND family_assistance_id IS NULL",now,attempt.source.getId(),attempt.patient,n.get("native_start_wall"),n.get("native_end_wall"),n.get("native_doctor_id"),n.get("native_schedule_id"));
            if(changed!=1)throw integrity();scope.expectedState.put("reminder_delivery_log_id",proof.id);scope.expectedMarker.put("notified_wall",now);scope.verify();scope.beforeCommit();return ReminderBindingResult.BOUND;
        });
    }
    private void requireProof(Map<String,Object> row,ReminderAttempt attempt,long log) {
        if(row==null||number(row.get("id"))!=log||number(row.get("patient_id"))!=attempt.patient||!REMINDER_EVENT.equals(row.get("event_type"))||!"DELIVERED".equals(row.get("delivery_status")))throw integrity();
        id(row.get("recipient_user_id"));com.fasterxml.jackson.databind.JsonNode json=reminderDetail("DELIVERED",String.valueOf(row.get("detail")));
        if(!attempt.id.equals(json.get("attemptId").asText())||attempt.state!=json.get("stateId").longValue()||!attempt.source.getKind().name().equals(json.get("sourceKind").asText())||attempt.source.getId()!=json.get("sourceId").longValue()||attempt.revision!=json.get("sourceRevision").longValue())throw integrity();
    }
    private boolean currentAttempt(ReminderDiscovery d,ReminderAttempt attempt) {
        if(!eligibleSource(d,false)||number(d.state.get("id"))!=attempt.state||number(d.state.get("source_revision"))!=attempt.revision||!attempt.fingerprint.equals(d.source.getFingerprint())||number(d.state.get("reminder_attempt_source_revision"))!=attempt.revision||!attempt.id.equals(d.state.get("reminder_attempt_id"))||!Objects.equals(attempt.attemptedAt,d.state.get("reminder_attempted_at_utc")))return false;
        Long bound=nullable(d.state.get("reminder_delivery_log_id"));
        if(bound!=null)requireProof(d.logs.get(bound),attempt,bound);
        return d.marker.get("notified_wall")==null||bound!=null;
    }
    private boolean eligibleSource(ReminderDiscovery d,boolean claiming) {
        if(number(d.state.get("lock_version"))<1||!"UNVERIFIED".equals(d.state.get("attendance"))||!d.source.getFingerprint().equals(d.state.get("source_fingerprint"))||number(d.source.getValues().get("family_workflow_version"))!=0||d.source.getValues().get("family_assistance_id")!=null||claiming&&d.marker.get("notified_wall")!=null)return false;
        if(!canonical(d.source.nativeTarget()).equals(canonical(nativeFields(d.state))))return false;
        java.time.LocalDateTime now=wallNow(),start=(java.time.LocalDateTime)d.source.nativeTarget().get("native_start_wall");if(start==null)return false;
        if(d.source.getReference().getKind()==SourceKind.CARE_ITEM_APPOINTMENT){java.time.LocalDateTime notify=(java.time.LocalDateTime)d.source.nativeTarget().get("native_notify_wall");return "OPEN".equals(d.source.getValues().get("status"))&&notify!=null&&!notify.isAfter(now);}
        java.time.LocalDateTime end=(java.time.LocalDateTime)d.source.nativeTarget().get("native_end_wall");
        return end!=null&&end.isAfter(start)&&"BOOKED".equals(d.source.getValues().get("status"))&&d.source.getDoctorId()!=null&&d.authority.isActiveDoctor(d.source.getDoctorId())&&!start.isBefore(now)&&!start.isAfter(now.plusHours(24));
    }
    private static Map<String,Object> nativeFields(Map<String,Object> state) {Map<String,Object> result=new LinkedHashMap<>();for(String key:Arrays.asList("native_status","native_start_wall","native_end_wall","native_notify_wall","native_doctor_id","native_schedule_id"))result.put(key,state.get(key));return result;}
    private java.time.LocalDateTime wallNow(){return java.time.LocalDateTime.ofInstant(properties.now(),java.time.ZoneId.systemDefault());}
    private static void noTransaction(){if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()||org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())throw integrity();}
    private ReminderScope admitReminder(SourceRef source,long patient,Collection<Long> extraLogs) {
        WorkflowData.mutationTransaction(jdbc);HouseholdWorkflowSchema.Compatibility storage=inspectStorage();availability(storage.getProfile()).requireWritable(properties.isEnabled());
        ReminderDiscovery before=discoverReminder(source,patient,extraLogs);
        repo.lock("patient",patient,false);
        // 首锁后仅重核已发现集合；变化即回滚，不能补锁新主体或日志。
        ReminderDiscovery underPatient=discoverReminder(source,patient,extraLogs);if(!before.signature().equals(underPatient.signature()))throw VisitFollowUpAuthorizationService.changed();
        for(String table:Arrays.asList("sys_user","sys_role","sys_user_role","care_access_grant","doctor_patient_assignment","care_member","care_nurse_assignment"))for(long row:before.ids.getOrDefault(table,new TreeSet<>()))repo.lock(table,row,false);
        repo.lock(source.getKind().tableName(),source.getId(),false);
        for(long row:before.ids.getOrDefault("family_assistance",new TreeSet<>()))repo.lock("family_assistance",row,false);
        for(long row:before.ids.getOrDefault("notification_delivery_log",new TreeSet<>()))repo.lock("notification_delivery_log",row,true);
        repo.lock("visit_followup_state",id(before.state.get("id")),false);
        ReminderDiscovery after=discoverReminder(source,patient,extraLogs);if(!before.signature().equals(after.signature()))throw VisitFollowUpAuthorizationService.changed();return new ReminderScope(after,storage);
    }
    private ReminderDiscovery discoverReminder(SourceRef ref,long patient,Collection<Long> extraLogs) {
        ReminderDiscovery d=new ReminderDiscovery();d.source=repo.source(ref);d.state=repo.state(ref);d.marker=repo.reminderNativeMarker(ref);
        if(d.source.getPatientId()!=patient||d.state==null||number(d.state.get("patient_id"))!=patient)throw integrity();
        repo.requireOrdinaryLegacySource(ref);d.add(ref.getKind().tableName(),ref.getId());d.add("visit_followup_state",id(d.state.get("id")));
        Map<String,Object> p=repo.one("SELECT id,user_id FROM patient WHERE id=?",patient);if(p==null)throw integrity();long owner=id(p.get("user_id"));d.subjects.add(owner);
        for(String field:Arrays.asList("user_id","assigned_user_id","actor_id","doctor_user_id"))if(d.source.getValues().get(field)!=null)d.subjects.add(id(d.source.getValues().get(field)));
        for(String field:Arrays.asList("created_by","responsible_user_id"))if(d.state.get(field)!=null)d.subjects.add(id(d.state.get(field)));
        SortedSet<Long> candidates=new TreeSet<>();
        if(ref.getKind()==SourceKind.CARE_ITEM_APPOINTMENT)candidates.add(d.source.getValues().get("assigned_user_id")==null?owner:id(d.source.getValues().get("assigned_user_id")));
        else {
            candidates.add(owner);if(d.source.getDoctorId()!=null)candidates.add(d.source.getDoctorId());
            for(String sql:Arrays.asList("SELECT user_id AS recipient FROM care_member WHERE patient_id=? ORDER BY user_id LIMIT 51","SELECT grantee_user_id AS recipient FROM care_access_grant WHERE patient_id=? ORDER BY grantee_user_id LIMIT 51","SELECT doctor_user_id AS recipient FROM doctor_patient_assignment WHERE patient_id=? ORDER BY doctor_user_id LIMIT 51"))for(Map<String,Object> row:repo.rows(sql,50,patient)){candidates.add(id(row.get("recipient")));if(candidates.size()>50)throw VisitFollowUpAuthorizationService.limit();}
        }
        d.subjects.addAll(candidates);d.authority=authorization.snapshot(patient,d.subjects,50);
        for(Map.Entry<String,List<Map<String,Object>>> group:d.authority.rows().entrySet())for(Map<String,Object> row:group.getValue()){d.add(group.getKey(),id(row.get("id")));if("sys_user_role".equals(group.getKey())&&row.get("role_code")!=null)d.add("sys_role",id(row.get("role_id")));}
        for(long candidate:candidates)if(d.authority.canAppointmentRead(candidate))d.recipients.add(candidate);
        Long assistance=nullable(d.source.getValues().get("family_assistance_id"));
        if(assistance!=null){Map<String,Object> row=repo.one("SELECT id,patient_id,source_kind,care_item_id,care_appointment_id FROM family_assistance WHERE id=?",assistance);if(row==null||number(row.get("patient_id"))!=patient)throw integrity();d.assistance=row;d.add("family_assistance",assistance);}
        SortedSet<Long> logs=new TreeSet<>(extraLogs);Long pointer=nullable(d.state.get("reminder_delivery_log_id"));if(pointer!=null)logs.add(pointer);
        for(long log:logs){d.add("notification_delivery_log",id(log));Map<String,Object> row=repo.reminderDeliveryLog(log);if(row==null)throw integrity();d.logs.put(log,row);}
        return d;
    }
    private final class ReminderScope {
        final ReminderDiscovery discovery;final HouseholdWorkflowSchema.Compatibility storage;final Map<String,Object> expectedState,expectedMarker;final Connection connection;final Thread thread=Thread.currentThread();boolean live=true;
        ReminderScope(ReminderDiscovery discovery,HouseholdWorkflowSchema.Compatibility storage){this.discovery=discovery;this.storage=storage;expectedState=new LinkedHashMap<>(discovery.state);expectedMarker=new LinkedHashMap<>(discovery.marker);connection=DataSourceUtils.getConnection(jdbc.getDataSource());DataSourceUtils.releaseConnection(connection,jdbc.getDataSource());org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){live=false;}});}
        void requireLive(){if(!live||thread!=Thread.currentThread())throw integrity();WorkflowData.mutationTransaction(jdbc);Connection current=DataSourceUtils.getConnection(jdbc.getDataSource());try{if(current!=connection)throw integrity();}finally{DataSourceUtils.releaseConnection(current,jdbc.getDataSource());}}
        void verify(){requireLive();ReminderDiscovery current=discoverReminder(discovery.source.getReference(),discovery.source.getPatientId(),discovery.logs.keySet());if(!discovery.signature(expectedState,expectedMarker).equals(current.signature()))throw VisitFollowUpAuthorizationService.changed();}
        /** 本事务复用已核结构证明；每个新gate/claim/bind仍重新检查，提交前当前权限和来源复核不省略。 */
        void beforeCommit(){org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void beforeCommit(boolean readOnly){requireLive();storage.requireCurrent(jdbc);availability(storage.getProfile()).requireWritable(properties.isEnabled());verify();}});}
    }
    private static final class ReminderDiscovery {
        NativeSource source;Map<String,Object> state,marker,assistance;VisitFollowUpAuthorizationService.Snapshot authority;
        final SortedSet<Long> subjects=new TreeSet<>(),recipients=new TreeSet<>();final Map<String,SortedSet<Long>> ids=new TreeMap<>();final Map<Long,Map<String,Object>> logs=new TreeMap<>();
        void add(String table,long row){ids.computeIfAbsent(table,key->new TreeSet<>()).add(row);int count=0;for(Collection<Long> values:ids.values())count+=values.size();if(count>50||subjects.size()>50)throw VisitFollowUpAuthorizationService.limit();}
        String signature(){return signature(state,marker);}
        String signature(Map<String,Object> state,Map<String,Object> marker){return canonical(WorkflowData.map("source",source.getFingerprint(),"nativeB",WorkflowData.map("version",source.getValues().get("family_workflow_version"),"pointer",source.getValues().get("family_assistance_id")),"state",state,"marker",marker,"authority",authority.signature(),"recipients",recipients,"subjects",subjects,"ids",ids,"logs",logs,"assistance",assistance));}
    }
    private final class ReminderAttempt {
        final Object owner=reminderOwner;final Thread thread=Thread.currentThread();final SourceRef source;final long patient,state,revision;final String fingerprint,id;final java.time.Instant attemptedAt;final Map<String,Object> nativeTarget;final SortedSet<Long> recipients;final Map<String,JournalEntry> journal=new HashMap<>();
        private ReminderAttempt(ReminderDiscovery d,String attemptId,java.time.Instant at){source=d.source.getReference();patient=d.source.getPatientId();state=VisitFollowUpContracts.id(d.state.get("id"));revision=number(d.state.get("source_revision"));fingerprint=d.source.getFingerprint();id=attemptId;attemptedAt=at;nativeTarget=Collections.unmodifiableMap(new LinkedHashMap<>(d.source.nativeTarget()));recipients=Collections.unmodifiableSortedSet(new TreeSet<>(d.recipients));}
        void requireLive(){if(owner!=reminderOwner||thread!=Thread.currentThread())throw integrity();}
    }
    private static final class JournalEntry {final String outcome;LoggedReminderOutcome proof;JournalEntry(String outcome){this.outcome=outcome;}}
    private static final class LoggedReminderOutcome {
        final ReminderAttempt attempt;final long id,recipient;final Long channel;final String status,detail;
        private LoggedReminderOutcome(ReminderAttempt attempt,long id,long recipient,Long channel,String status,String detail){this.attempt=attempt;this.id=id;this.recipient=recipient;this.channel=channel;this.status=status;this.detail=detail;}
    }
}
