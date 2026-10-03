package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.entity.NotificationChannel;
import org.familyhealthcare.mapper.NotificationChannelMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Synthetic real-JDBC outbox tests. No real destination or transport is contacted. */
class CarePlanNotificationTest {
    CarePlanTestFixture f; TransactionTemplate tx; CarePlanProperties properties;
    CarePlanAuthorizationService auth; CarePlanNotificationWorker worker; CarePlanNotificationTransport transport;
    List<List<Object>> sends=Collections.synchronizedList(new ArrayList<>());
    String outcome="DELIVERED"; boolean retryable=true; Runnable onSend=()->{};

    @BeforeEach void setup(TestInfo info) throws Exception {
        String method=info.getTestMethod().get().getName();
        if(method.equals("deliveryServiceClassifiesExplicitFailuresAndAmbiguousTransportWithoutChangingBooleanApi")||method.equals("carePlanLocalizationAlwaysUsesGenericTemplateAndControlledRelativeLocation")||method.equals("deliveryServiceClassifiesPermanentAndUncertainFailuresConservatively")||method.equals("actualDingTalkCarePlanBodyNeverIncludesConfiguredText"))return;
        f=new CarePlanTestFixture(); tx=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc().getDataSource()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        properties=new CarePlanProperties(true,new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return f.now();}});
        auth=new CarePlanAuthorizationService(f.jdbc(),properties);
        transport=mock(CarePlanNotificationTransport.class);
        when(transport.send(anyLong(),anyString(),anyString(),anyString())).thenAnswer(invocation->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Network delivery must not hold a database transaction");
            Object[] args=invocation.getArguments();sends.add(Arrays.asList(args[0],args[1],args[2],args[3],f.now()));onSend.run();
            return CarePlanNotificationTransport.DeliveryOutcome.valueOf(outcome);
        });
        when(transport.attempt(anyLong(),anyString(),anyString(),anyString())).thenAnswer(invocation->{
            Object[] args=invocation.getArguments();CarePlanNotificationTransport.DeliveryOutcome result=transport.send((Long)args[0],(String)args[1],(String)args[2],(String)args[3]);
            return new CarePlanNotificationTransport.DeliveryAttempt(result,retryable&&result==CarePlanNotificationTransport.DeliveryOutcome.FAILED);
        });
        worker=newWorker();
        // The real upgrade adds these columns after the fixture's legacy migration prefix.
        for(String ddl:new String[]{"action_type VARCHAR(30)","target_type VARCHAR(80)","target_id VARCHAR(80)","detail_json CLOB"})
            f.jdbc().execute("ALTER TABLE operation_audit_log ADD COLUMN IF NOT EXISTS "+ddl);
        seed();
    }
    @AfterEach void close() throws Exception {if(f!=null)f.close();}

    @Test void sameTransactionEnqueueIsDeduplicatedAndRollsBack() {
        tx.execute(s->{enqueue(100);enqueue(100);return null;});assertEquals(1,count());
        db("DELETE FROM care_plan_notification");
        assertThrows(IllegalStateException.class,()->tx.execute(s->{enqueue(100);throw new IllegalStateException("synthetic rollback");}));
        assertEquals(0,count());assertThrows(IllegalStateException.class,()->enqueue(100));assertEquals(0,count());
        TransactionTemplate rr=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc().getDataSource()));rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class,()->rr.execute(s->{enqueue(100);return null;}));assertEquals(0,count());
    }
    @Test void deliveryOutcomesRemainDistinct() {
        for(String value:new String[]{"DELIVERED","FAILED","UNKNOWN","NO_CHANNEL"}) {
            db("DELETE FROM care_plan_notification");outcome=value;queue();tick();
            assertEquals(value,job().get("status"));assertEquals(1,((Number)job().get("attempt_count")).intValue());
        }
    }
    @Test void retriesStopAtThreeAndRespectRevocation() {
        Instant first=f.now();outcome="FAILED";queue();tick();assertEquals(first.plusSeconds(60),time(job(),"next_attempt_at"));
        f.advance(Duration.ofSeconds(59));tick();assertEquals(1,sends.size());
        f.advance(Duration.ofSeconds(1));tick();assertEquals(first.plusSeconds(300),time(job(),"next_attempt_at"));
        f.advance(Duration.ofSeconds(239));tick();assertEquals(2,sends.size());
        f.advance(Duration.ofSeconds(1));tick();assertEquals(3,sends.size());assertNull(job().get("next_attempt_at"));
        f.advance(Duration.ofDays(1));tick();assertEquals(3,sends.size());assertEquals(3,((Number)job().get("attempt_count")).intValue());
        assertEquals(Arrays.asList(first,first.plusSeconds(60),first.plusSeconds(300)),Arrays.asList(sends.get(0).get(4),sends.get(1).get(4),sends.get(2).get(4)));
        db("DELETE FROM care_plan_notification");sends.clear();queue();tick();db("UPDATE sys_user SET status=0 WHERE id=7");
        f.advance(Duration.ofMinutes(1));tick();assertEquals(1,sends.size());assertEquals("SUPPRESSED",job().get("status"));
    }
    @Test void legacyFailedOutcomeDoesNotClaimExplicitRetryability() {
        queue();AtomicInteger count=new AtomicInteger();CarePlanNotificationTransport legacy=(channel,event,title,path)->{count.incrementAndGet();return CarePlanNotificationTransport.DeliveryOutcome.FAILED;};
        CarePlanNotificationWorker compatible=new CarePlanNotificationWorker(f.jdbc(),auth,properties,legacy);compatible.tick(f.now());
        assertEquals("FAILED",job().get("status"));assertNull(job().get("next_attempt_at"));f.advance(Duration.ofMinutes(10));compatible.tick(f.now());assertEquals(1,count.get());
    }
    @Test void permanentFailureNeverAutomaticallyRetries() {
        outcome="FAILED";retryable=false;queue();tick();assertEquals("FAILED",job().get("status"));assertNull(job().get("next_attempt_at"));
        f.advance(Duration.ofMinutes(1));tick();f.advance(Duration.ofMinutes(4));tick();f.advance(Duration.ofDays(1));tick();
        assertEquals(1,sends.size());assertEquals(1,((Number)job().get("attempt_count")).intValue());
    }
    @Test void unknownNeverAutomaticallyRetries() {
        outcome="UNKNOWN";queue();tick();f.advance(Duration.ofDays(30));tick();assertEquals(1,sends.size());assertEquals("UNKNOWN",job().get("status"));
        verify(transport,times(1)).send(100L,"CARE_PLAN_PLAN_PUBLISHED","Care plan update","/care-plans/100");
    }
    @Test void twoWorkersClaimOneJobWithoutHoldingNetworkLocks() throws Exception {
        queue();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);onSend=()->{entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}};
        ExecutorService executor=Executors.newFixedThreadPool(2);CarePlanNotificationWorker second=newWorker();
        Future<?> a=executor.submit(()->worker.tick(f.now()));
        try {assertTrue(entered.await(10,TimeUnit.SECONDS));Future<?> b=executor.submit(()->second.tick(f.now()));
            b.get(3,TimeUnit.SECONDS);assertEquals(1,sends.size());assertEquals("CLAIMED",job().get("status"));
        }finally{release.countDown();a.get(10,TimeUnit.SECONDS);executor.shutdownNow();}
        assertEquals("DELIVERED",job().get("status"));
    }
    @Test void lockedClaimReturnsPromptlyWithoutChangingTheSessionTimeout() throws Exception {
        queue();try(java.sql.Connection lock=f.jdbc().getDataSource().getConnection()) {
            lock.setAutoCommit(false);lock.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
            lock.createStatement().executeQuery("SELECT id FROM care_plan_notification FOR UPDATE");
            ExecutorService executor=Executors.newSingleThreadExecutor();Future<?> work=executor.submit(this::tick);
            try {assertDoesNotThrow(()->work.get(1,TimeUnit.SECONDS));assertEquals(0,sends.size());}
            finally {lock.rollback();work.get(10,TimeUnit.SECONDS);executor.shutdownNow();}
        }
        try(java.sql.Connection connection=f.jdbc().getDataSource().getConnection()) {
            connection.createStatement().execute("SET LOCK_TIMEOUT 1234");
            org.springframework.jdbc.datasource.SingleConnectionDataSource single=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            JdbcTemplate jdbc=new JdbcTemplate(single);
            CarePlanNotificationWorker local=new CarePlanNotificationWorker(jdbc,new CarePlanAuthorizationService(jdbc,properties),properties,transport);
            local.tick(f.now());assertEquals(1234,jdbc.queryForObject("CALL LOCK_TIMEOUT()",Integer.class));assertEquals(1,sends.size());
        }
    }
    @Test void crashAfterSendBecomesUnknown() throws Exception {
        queue();onSend=()->{throw new AssertionError("Synthetic process death after send");};assertThrows(AssertionError.class,this::tick);
        assertEquals("CLAIMED",job().get("status"));assertEquals(1,sends.size());
        f.advance(Duration.ofMinutes(10));worker=newWorker();onSend=()->{};tick();assertEquals("UNKNOWN",job().get("status"));
        f.advance(Duration.ofDays(1));tick();assertEquals(1,sends.size());
    }
    @Test void unattemptedCrashLeaseCanBeSafelyReclaimed() {
        queue();db("UPDATE care_plan_notification SET status='CLAIMED',claim_token='synthetic-lease',claimed_at=?,request_id=NULL,attempt_count=0",Timestamp.from(f.now().minusSeconds(600)));
        tick();assertEquals(1,sends.size());assertEquals("DELIVERED",job().get("status"));
    }
    @Test void unattemptedRecoveryPreservesExistingRetryBudget() {
        outcome="FAILED";queue();tick();String request=job().get("request_id").toString();
        db("UPDATE care_plan_notification SET status='CLAIMED',claim_token='new-unattempted-lease',claimed_at=?,last_result='CLAIMED_UNATTEMPTED'",Timestamp.from(f.now().minusSeconds(600)));
        f.advance(Duration.ofMinutes(1));tick();assertEquals(2,sends.size());assertEquals(2,((Number)job().get("attempt_count")).intValue());assertEquals(request,job().get("request_id"));
    }
    @Test void manualRetryRechecksRecipientAndChannelAndRejectsIncompatibleOuterTransactions() {
        outcome="UNKNOWN";queue();tick();long id=((Number)job().get("id")).longValue();
        db("UPDATE notification_channel SET user_id=8 WHERE id=100");error(403,()->retry(ADMIN,id,true));assertEquals("UNKNOWN",job().get("status"));
        db("UPDATE notification_channel SET user_id=7 WHERE id=100");db("UPDATE sys_user SET status=0 WHERE id=7");
        error(403,()->retry(ADMIN,id,true));assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM operation_audit_log",Integer.class));
        db("UPDATE sys_user SET status=1 WHERE id=7");
        TransactionTemplate rr=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc().getDataSource()));rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class,()->rr.execute(s->retry(ADMIN,id,true)));assertEquals("UNKNOWN",job().get("status"));
        assertThrows(IllegalStateException.class,()->tx.execute(s->{tick();return null;}));assertEquals(1,sends.size());
    }
    @Test void payloadContainsNoClinicalDetails() {
        queue();tick();assertEquals(1,sends.size());String body=sends.toString();
        for(String value:new String[]{"Synthetic Patient","Synthetic User","Private diagnosis","Confidential plan","Secret action","181/109"})assertFalse(body.contains(value),value);
        assertEquals("/care-plans/100",sends.get(0).get(3));assertEquals("Care plan update",sends.get(0).get(2));
    }
    @Test void currentOwnerAndChannelsAreRechecked() {
        queue();db("UPDATE notification_channel SET user_id=8 WHERE id=100");tick();assertEquals(0,sends.size());assertEquals("SUPPRESSED",job().get("status"));
        db("DELETE FROM care_plan_notification");db("UPDATE notification_channel SET user_id=7,enabled=1 WHERE id=100");queue();
        db("UPDATE notification_channel SET enabled=0 WHERE id=100");tick();assertEquals(0,sends.size());assertEquals("NO_CHANNEL",job().get("status"));
    }
    @Test void onlyRelevantCurrentlyAuthorizedRecipientsAreEnqueued() {
        db("INSERT INTO care_access_grant(patient_id,granted_by,grantee_user_id,grantee_role,access_level,visible_modules,status) VALUES(1,7,8,'FAMILY','WRITE','CARE_PLAN','ACTIVE')");
        queue();assertEquals(Collections.singletonList(7L),f.jdbc().queryForList("SELECT recipient_user_id FROM care_plan_notification ORDER BY id",Long.class));
        db("DELETE FROM care_plan_notification");db("UPDATE care_plan_action SET assigned_user_id=8 WHERE id=100");queue();
        assertEquals(Arrays.asList(7L,8L),f.jdbc().queryForList("SELECT recipient_user_id FROM care_plan_notification ORDER BY recipient_user_id",Long.class));
        assertEquals("NO_CHANNEL",f.jdbc().queryForObject("SELECT status FROM care_plan_notification WHERE recipient_user_id=8",String.class));
        db("DELETE FROM care_plan_notification");db("UPDATE care_access_grant SET status='REVOKED'");queue();assertEquals(1,count());
    }
    @Test void nurseNotificationsRequireAssignmentAndModuleGrant() {
        db("INSERT INTO sys_user_role(user_id,role_id) SELECT 10,id FROM sys_role WHERE role_code='nurse'");
        db("INSERT INTO notification_channel(id,user_id,channel_type,channel_name,webhook_url,enabled) VALUES(101,10,'WEBHOOK','Synthetic nurse channel','https://example.test/synthetic-nurse',1)");
        db("INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,status,assigned_by,assigned_at) VALUES(1,10,'ACTIVE',11,?)",Timestamp.from(f.now()));
        db("UPDATE care_plan_event SET event_type='HELP_REQUESTED',action_id=100 WHERE id=100");queue();assertFalse(recipients().contains(10L));
        db("INSERT INTO care_access_grant(patient_id,granted_by,grantee_user_id,grantee_role,access_level,visible_modules,status) VALUES(1,7,10,'NURSE','WRITE','CARE_PLAN','ACTIVE')");queue();assertTrue(recipients().contains(10L));
        db("UPDATE care_nurse_assignment SET status='REVOKED'");tick();assertEquals("SUPPRESSED",f.jdbc().queryForObject("SELECT status FROM care_plan_notification WHERE recipient_user_id=10",String.class));
    }
    @Test void dueIsOncePerActionInstantAndUsesPublicationWithoutImpersonation() {
        db("UPDATE care_plan_action SET due_at=? WHERE id=100",Timestamp.from(f.now()));tick();tick();
        assertEquals(1,count());assertTrue(job().get("dispatch_key").toString().startsWith("due:100:"));assertEquals(100L,((Number)job().get("event_id")).longValue());
        assertEquals(1,f.jdbc().queryForObject("SELECT COUNT(*) FROM care_plan_event WHERE plan_id=100",Integer.class));
        assertEquals("CARE_PLAN_ACTION_DUE",sends.get(0).get(1));
    }
    @Test void staleDueNeverSendsOrRequeues() {
        db("UPDATE care_plan_action SET due_at=? WHERE id=100",Timestamp.from(f.now()));outcome="FAILED";tick();assertEquals(1,sends.size());
        db("UPDATE care_plan_action SET status='SUBMITTED' WHERE id=100");f.advance(Duration.ofMinutes(1));tick();assertEquals(1,sends.size());assertEquals("SUPPRESSED",job().get("status"));
        db("DELETE FROM care_plan_notification");db("UPDATE doctor_care_plan SET lifecycle='CANCELLED' WHERE id=100");tick();assertEquals(0,count());
    }
    @Test void manualUnknownRetryRequiresAcknowledgementAndAuditsPriorCycle() {
        outcome="UNKNOWN";queue();tick();long id=((Number)job().get("id")).longValue();String request=job().get("request_id").toString();
        error(400,()->retry(ADMIN,id,false));error(403,()->retry(OWNER,id,true));assertEquals("UNKNOWN",job().get("status"));
        Map<String,Object> result=retry(ADMIN,id,true);assertEquals("QUEUED",result.get("status"));assertEquals(0,result.get("attemptCount"));
        String audit=f.jdbc().queryForObject("SELECT detail_json FROM operation_audit_log WHERE target_type='CARE_PLAN_NOTIFICATION'",String.class);
        assertTrue(audit.contains("UNKNOWN"));assertTrue(audit.contains("previousAttemptCount\":1"));assertTrue(audit.contains(request));assertTrue(audit.contains("duplicateRiskAcknowledged\":true"));
        assertFalse(audit.contains("patient"));outcome="DELIVERED";tick();assertEquals(2,sends.size());assertNotEquals(request,job().get("request_id"));
    }
    @Test void administratorStatusNeverExposesClinicalOrDestinationData() {
        queue();Map<String,Object> page=list(ADMIN,null,1);assertEquals(1,((List<?>)page.get("items")).size());
        String text=page.toString();for(String field:new String[]{"patientId","planId","eventId","recipientUserId","channelId","dispatchKey","requestId","webhook","note","title","payload"})assertFalse(text.contains(field),field);
        error(403,()->list(OWNER,null,10));error(400,()->list(ADMIN,"invalid",10));error(400,()->list(ADMIN,null,101));
    }
    @Test void disabledPathsNeverQueryNewTables() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);CarePlanProperties disabled=new CarePlanProperties(false,Clock.systemUTC());
        CarePlanNotificationWorker off=new CarePlanNotificationWorker(jdbc,mock(CarePlanAuthorizationService.class),disabled,transport);
        off.enqueue(100L);off.tick(f.now());
        error(503,()->off.listDeliveryStatus(ADMIN,null,10));
        error(503,()->off.retry(ADMIN,100L,true));verifyNoInteractions(jdbc);
    }
    @Test void deliveryServiceClassifiesExplicitFailuresAndAmbiguousTransportWithoutChangingBooleanApi() {
        NotificationDeliveryService service=new NotificationDeliveryService();NotificationChannelMapper mapper=mock(NotificationChannelMapper.class);ReflectionTestUtils.setField(service,"mapper",mapper);
        NotificationChannel c=new NotificationChannel();c.setId(100L);c.setUserId(7L);c.setEnabled(1);c.setChannelType("WEBHOOK");c.setWebhookUrl("https://example.test/synthetic");
        when(mapper.selectById(100L)).thenReturn(c);MockRestServiceServer server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(service,"client")).build();
        server.expect(requestTo(c.getWebhookUrl())).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));assertEquals("DELIVERED",classified(service));server.verify();server.reset();
        server.expect(requestTo(c.getWebhookUrl())).andRespond(withBadRequest());assertEquals("FAILED",classified(service));server.verify();server.reset();
        server.expect(requestTo(c.getWebhookUrl())).andRespond(withException(new java.net.SocketTimeoutException("secret URL and clinical data")));assertEquals("UNKNOWN",classified(service));server.verify();
        when(mapper.selectById(100L)).thenReturn(null);assertEquals("NO_CHANNEL",classified(service));
    }
    @Test void deliveryServiceClassifiesPermanentAndUncertainFailuresConservatively() {
        NotificationDeliveryService service=new NotificationDeliveryService();NotificationChannelMapper mapper=mock(NotificationChannelMapper.class);ReflectionTestUtils.setField(service,"mapper",mapper);
        NotificationChannel c=new NotificationChannel();c.setId(100L);c.setUserId(7L);c.setEnabled(1);c.setChannelType("WEBHOOK");c.setWebhookUrl("https://example.test/synthetic");when(mapper.selectById(100L)).thenReturn(c);
        MockRestServiceServer server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(service,"client")).build();
        server.expect(requestTo(c.getWebhookUrl())).andRespond(withServerError());assertEquals("UNKNOWN",classified(service));server.verify();server.reset();
        server.expect(requestTo(c.getWebhookUrl())).andRespond(withBadRequest());assertFalse(classifiedRetryable(service));server.verify();server.reset();
        c.setWebhookUrl("invalid configuration");assertFalse(classifiedRetryable(service));
        c.setWebhookUrl("https://example.test/synthetic");server.expect(requestTo(c.getWebhookUrl())).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));assertTrue(classifiedRetryable(service));server.verify();server.reset();
        org.springframework.http.HttpHeaders timing=new org.springframework.http.HttpHeaders();timing.add("Retry-After","3600");
        server.expect(requestTo(c.getWebhookUrl())).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).headers(timing));assertFalse(classifiedRetryable(service));server.verify();
    }
    @Test void actualDingTalkCarePlanBodyNeverIncludesConfiguredText() {
        NotificationDeliveryService service=new NotificationDeliveryService();NotificationChannelMapper mapper=mock(NotificationChannelMapper.class);
        RobotChannelConfigService robots=mock(RobotChannelConfigService.class);ReflectionTestUtils.setField(service,"mapper",mapper);ReflectionTestUtils.setField(service,"robots",robots);
        NotificationChannel channel=new NotificationChannel();channel.setId(100L);channel.setUserId(7L);channel.setEnabled(1);channel.setChannelType("DINGTALK_WEBHOOK");channel.setWebhookUrl("https://example.test/synthetic-robot");
        String configured="Synthetic Patient Private diagnosis Secret action 181/109";
        when(mapper.selectById(100L)).thenReturn(channel);doAnswer(call->{channel.setRobotKeyword(configured);return null;}).when(robots).load(channel);
        MockRestServiceServer server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(service,"client")).build();
        server.expect(requestTo(channel.getWebhookUrl())).andExpect(content().json("{\"msgtype\":\"text\",\"text\":{\"content\":\"Care plan update\\nOpen your care plan after signing in. /care-plans/100\"}}"))
            .andRespond(withSuccess("{\"errcode\":0}",MediaType.APPLICATION_JSON));
        CarePlanNotificationTransport.ExistingChannels adapter=new CarePlanNotificationTransport.ExistingChannels(service);
        assertEquals(CarePlanNotificationTransport.DeliveryOutcome.DELIVERED,adapter.attempt(100L,"CARE_PLAN_PLAN_PUBLISHED","Private diagnosis","/care-plans/100").getOutcome());
        server.verify();assertEquals(configured,channel.getRobotKeyword());server.reset();
        server.expect(requestTo(channel.getWebhookUrl())).andExpect(content().json("{\"msgtype\":\"text\",\"text\":{\"content\":\""+configured+"\\nLegacy title\\nLegacy content\"}}"))
            .andRespond(withSuccess("{\"errcode\":0}",MediaType.APPLICATION_JSON));
        service.send(channel,"Legacy title","Legacy content");server.verify();server.reset();
        server.expect(requestTo(channel.getWebhookUrl())).andExpect(content().json("{\"msgtype\":\"text\",\"text\":{\"content\":\"Care plan update\\nOpen your care plan after signing in. /care-plans/100\"}}"))
            .andRespond(withSuccess("{\"errcode\":310000}",MediaType.APPLICATION_JSON));
        CarePlanNotificationTransport.DeliveryAttempt rejected=adapter.attempt(100L,"CARE_PLAN_PLAN_PUBLISHED","Private diagnosis","/care-plans/100");
        server.verify();assertEquals(CarePlanNotificationTransport.DeliveryOutcome.FAILED,rejected.getOutcome());assertFalse(rejected.isRetryable());assertEquals(configured,channel.getRobotKeyword());
    }
    @Test void slowProviderBatchUsesEachJobsActualFirstAttemptClock() {
        for(long channel=101;channel<=102;channel++)db("INSERT INTO notification_channel(id,user_id,channel_type,channel_name,webhook_url,enabled) VALUES(?,7,'WEBHOOK','Synthetic batch channel','https://example.test/synthetic',1)",channel);
        NotificationDeliveryService service=new NotificationDeliveryService();NotificationChannelMapper mapper=mock(NotificationChannelMapper.class);ReflectionTestUtils.setField(service,"mapper",mapper);
        Map<Long,NotificationChannel> channels=new LinkedHashMap<>();Map<Long,List<Instant>> starts=new LinkedHashMap<>();Map<Long,List<Instant>> claims=new LinkedHashMap<>();
        for(long id=100;id<=102;id++){NotificationChannel channel=new NotificationChannel();channel.setId(id);channel.setUserId(7L);channel.setEnabled(1);channel.setChannelType("WEBHOOK");channel.setWebhookUrl("https://example.test/synthetic/"+id);channels.put(id,channel);starts.put(id,new ArrayList<>());claims.put(id,new ArrayList<>());}
        when(mapper.selectById(anyLong())).thenAnswer(call->channels.get((Long)call.getArgument(0)));
        MockRestServiceServer server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(service,"client")).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),request->{}).andRespond(request->{
            long channel=Long.parseLong(request.getURI().getPath().substring(request.getURI().getPath().lastIndexOf('/')+1));Instant started=f.now();starts.get(channel).add(started);
            Map<String,Object> row=f.jdbc().queryForMap("SELECT * FROM care_plan_notification WHERE channel_id=?",channel);claims.get(channel).add(time(row,"claimed_at"));
            f.advance(Duration.ofSeconds(7));return withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).createResponse(request);
        });
        worker=new CarePlanNotificationWorker(f.jdbc(),auth,properties,new CarePlanNotificationTransport.ExistingChannels(service));
        Instant batchStart=f.now();queue();tick();
        for(long id:channels.keySet()){Map<String,Object> row=f.jdbc().queryForMap("SELECT * FROM care_plan_notification WHERE channel_id=?",id);assertEquals(1,starts.get(id).size());Instant first=starts.get(id).get(0);assertEquals(first.plusSeconds(60),time(row,"next_attempt_at"),"Retry deadline must follow this job's actual first send");}
        for(long id:channels.keySet()){Map<String,Object> row=f.jdbc().queryForMap("SELECT * FROM care_plan_notification WHERE channel_id=?",id);assertEquals(starts.get(id),claims.get(id));assertEquals(starts.get(id).get(0).plusSeconds(7),time(row,"updated_at"));}
        f.advance(Duration.between(f.now(),batchStart.plusSeconds(60)));tick();
        for(long id:channels.keySet()){Map<String,Object> row=f.jdbc().queryForMap("SELECT * FROM care_plan_notification WHERE channel_id=?",id);assertEquals(2,starts.get(id).size());Instant first=starts.get(id).get(0);assertEquals(first.plusSeconds(60),starts.get(id).get(1));assertEquals(first.plusSeconds(300),time(row,"next_attempt_at"));}
        f.advance(Duration.between(f.now(),batchStart.plusSeconds(300)));tick();
        for(long id:channels.keySet()){Map<String,Object> row=f.jdbc().queryForMap("SELECT * FROM care_plan_notification WHERE channel_id=?",id);assertEquals(3,starts.get(id).size());assertEquals(starts.get(id),claims.get(id));assertEquals(starts.get(id).get(0).plusSeconds(300),starts.get(id).get(2));assertEquals(3,((Number)row.get("attempt_count")).intValue());assertNull(row.get("next_attempt_at"));}
        server.verify();
    }
    @Test void carePlanLocalizationAlwaysUsesGenericTemplateAndControlledRelativeLocation() {
        NotificationMessageLocalizer l=new NotificationMessageLocalizer();
        assertEquals("Care plan update",l.localize("CARE_PLAN_PLAN_PUBLISHED","Private diagnosis","/care-plans/100","en-US").getTitle());
        assertEquals("照护计划更新",l.localize("CARE_PLAN_PLAN_PUBLISHED","Private diagnosis","/care-plans/100","zh-CN").getTitle());
        assertEquals("Open your care plan after signing in. /care-plans/100",l.localize("CARE_PLAN_PLAN_PUBLISHED","secret","/care-plans/100","en-US").getContent());
        assertEquals("请登录后打开照护计划。 /care-plans/100",l.localize("CARE_PLAN_PLAN_PUBLISHED","secret","/care-plans/100","zh-CN").getContent());
        assertFalse(l.localize("CARE_PLAN_PLAN_PUBLISHED","Private diagnosis","https://bad.test/patient/181/109","en-US").getContent().contains("bad.test"));
    }

    void seed() {
        db("INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions,status,workflow_version,lifecycle) VALUES(100,9,1,'Confidential plan','Private diagnosis 181/109','COLLABORATION',1,'ACTIVE')");
        db("INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at,published_by,published_at) VALUES(100,100,1,'PUBLISHED','Confidential plan','Private diagnosis','FOLLOW_UP','{}',9,?,?,9,?)",Timestamp.from(f.now()),Timestamp.from(f.now()),Timestamp.from(f.now()));
        db("UPDATE doctor_care_plan SET current_revision_id=100 WHERE id=100");
        db("INSERT INTO care_plan_action(id,plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,created_at,updated_at) VALUES(100,100,100,1,1,'Secret action',?,7,?,?)",Timestamp.from(f.now().plus(Duration.ofDays(365))),Timestamp.from(f.now()),Timestamp.from(f.now()));
        db("INSERT INTO care_plan_event(id,patient_id,plan_id,revision_id,actor_id,actor_name,actor_role,event_type,note,recorded_at,payload_json) VALUES(100,1,100,100,9,'Synthetic User 9','DOCTOR','PLAN_PUBLISHED','Private diagnosis',?,'{}')",Timestamp.from(f.now()));
        db("INSERT INTO notification_channel(id,user_id,channel_type,channel_name,webhook_url,enabled) VALUES(100,7,'WEBHOOK','Synthetic channel','https://example.test/synthetic',1)");
    }
    CarePlanNotificationWorker newWorker(){return new CarePlanNotificationWorker(f.jdbc(),auth,properties,transport);}
    void enqueue(long id){worker.enqueue(id);}void queue(){tx.execute(s->{enqueue(100);return null;});}void tick(){worker.tick(f.now());}
    Map<String,Object> retry(long actor,long id,boolean risk){return worker.retry(actor,id,risk);}
    Map<String,Object> list(long actor,String cursor,int limit){return worker.listDeliveryStatus(actor,cursor,limit);}
    Map<String,Object>job(){return f.jdbc().queryForMap("SELECT * FROM care_plan_notification WHERE recipient_user_id=7 ORDER BY id LIMIT 1");}
    int count(){return f.jdbc().queryForObject("SELECT COUNT(*) FROM care_plan_notification",Integer.class);}List<Long>recipients(){return f.jdbc().queryForList("SELECT recipient_user_id FROM care_plan_notification ORDER BY recipient_user_id",Long.class);}
    Instant time(Map<String,Object> row,String column){return f.jdbc().queryForObject("SELECT "+column+" FROM care_plan_notification WHERE id=?",(rs,i)->{Timestamp value=rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC")));return value==null?null:value.toInstant();},row.get("id"));}
    void db(String sql,Object... args){f.jdbc().update(sql,ps->{for(int i=0;i<args.length;i++)if(args[i] instanceof Timestamp)ps.setTimestamp(i+1,(Timestamp)args[i],Calendar.getInstance(TimeZone.getTimeZone("UTC")));else ps.setObject(i+1,args[i]);});}
    static void error(int status,Runnable r){CarePlanException e=assertThrows(CarePlanException.class,r::run);assertEquals(status,e.getStatus());}
    boolean classifiedRetryable(NotificationDeliveryService service){return new CarePlanNotificationTransport.ExistingChannels(service).attempt(100L,"CARE_PLAN_PLAN_PUBLISHED","Care plan update","/care-plans/100").isRetryable();}
    String classified(NotificationDeliveryService service){return service.deliverCarePlan(100L,"CARE_PLAN_PLAN_PUBLISHED","Care plan update","/care-plans/100").name();}
}
