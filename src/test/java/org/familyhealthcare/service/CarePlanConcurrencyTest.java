package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.familyhealthcare.service.CarePlanLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real overlapping H2 transactions and distinct connections, not native MySQL acceptance. */
class CarePlanConcurrencyTest {
    private CarePlanLifecycleTest h;
    private ExecutorService pool;
    private CarePlanActionService actionService;
    private CarePlanNotificationQueue actionQueue;
    private final ThreadLocal<Boolean>recording=ThreadLocal.withInitial(()->false);
    private final Set<Connection> connections=Collections.newSetFromMap(new ConcurrentHashMap<Connection,Boolean>());
    @BeforeEach void setup()throws Exception{h=new CarePlanLifecycleTest();h.setup();h.connectionObserver=connection->{if(recording.get())connections.add(connection);};pool=Executors.newFixedThreadPool(2);actionQueue=h::enqueue;}
    @AfterEach void close()throws Exception{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));h.close();}

    @Test void concurrentPublishSameCommandReturnsOneOriginalResult()throws Exception{
        Map<String,Object>d=h.create(body(2));long plan=id(d),rev=num(d.get("draftRevisionId"));String k=key();
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>first=run(()->h.publish(DOCTOR,plan,rev,map(),k,0));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Map<String,Object>>replay=run(()->{started.countDown();return h.publish(DOCTOR,plan,rev,map(),k,0);});
        assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(replay.isDone());release.countDown();
        assertEquals(first.get(10,TimeUnit.SECONDS),replay.get(10,TimeUnit.SECONDS));assertEquals(2,connections.size());
        assertEquals(2,h.count("care_plan_action"));assertEquals(2,h.count("care_plan_event"));assertEquals(1,h.count("care_plan_notification"));assertEquals(2,h.count("care_plan_command"));
    }
    @Test void concurrentPublishDistinctCommandsOnlyOneVersionWins()throws Exception{
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>first=run(()->h.publish(DOCTOR,plan,rev,map(),key(),0));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>second=run(()->{started.countDown();try{h.publish(DOCTOR,plan,rev,map(),key(),0);return 200;}catch(CarePlanException e){return e.getStatus();}});
        assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(second.isDone());release.countDown();assertEquals("ACTIVE",first.get(10,TimeUnit.SECONDS).get("lifecycle"));assertEquals(409,second.get(10,TimeUnit.SECONDS));
        assertEquals(2,connections.size());assertEquals(1,h.count("care_plan_action"));assertEquals(1,h.count("care_plan_notification"));assertEquals(2,h.count("care_plan_command"));
    }
    @Test void publicationWinsAgainstLateDraftSaveWithVersionConflict()throws Exception{
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>publication=run(()->h.publish(DOCTOR,plan,rev,map(),key(),0));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>save=run(()->{started.countDown();try{h.tx.execute(s->{h.observeConnection();return h.service.saveDraft(DOCTOR,plan,rev,body(1),key(),0);});return 200;}catch(CarePlanException e){return e.getStatus();}});
        assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(save.isDone());release.countDown();
        assertEquals("ACTIVE",publication.get(10,TimeUnit.SECONDS).get("lifecycle"));assertEquals(409,save.get(10,TimeUnit.SECONDS));
        assertEquals(2,connections.size());assertEquals(2,h.count("care_plan_command"));assertEquals(2,h.count("care_plan_event"));assertEquals(1,h.count("care_plan_notification"));
        assertEquals("PUBLISHED",h.f.jdbc().queryForObject("SELECT status FROM care_plan_revision WHERE id=?",String.class,rev));
        h.f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED'");
        error(403,()->h.tx.execute(s->h.service.saveDraft(DOCTOR,plan,rev,body(1),key(),0)));
        assertEquals(2,h.count("care_plan_command"));
    }
    @Test void cancelWinsAgainstLateReceipt()throws Exception{
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,rev,map(),key(),0);
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>cancel=run(()->h.transition(DOCTOR,plan,"CANCEL","Synthetic reason",key(),1));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>receipt=run(()->{started.countDown();return receiptAttempt(actionId(plan,rev),1);});
        assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(receipt.isDone());release.countDown();assertEquals("CANCELLED",cancel.get(10,TimeUnit.SECONDS).get("lifecycle"));assertEquals(409,receipt.get(10,TimeUnit.SECONDS));
        assertEquals(2,connections.size());assertEquals("CANCELLED",h.f.jdbc().queryForObject("SELECT status FROM care_plan_action",String.class));assertEquals(0,h.f.jdbc().queryForObject("SELECT COUNT(*) FROM care_plan_event WHERE event_type='RECEIPT_SUBMITTED'",Integer.class));
    }
    @Test void revisionWinsAgainstLateOldRevisionReceipt()throws Exception{
        Map<String,Object>d=h.create(body(1));long plan=id(d),old=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,old,map(),key(),0);
        Map<String,Object>r=h.tx.execute(s->h.service.createRevision(DOCTOR,plan,key(),1));long next=num(r.get("draftRevisionId"));Map<String,Object>confirmation=h.confirmation(plan);
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>publish=run(()->h.publish(DOCTOR,plan,next,confirmation,key(),2));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>receipt=run(()->{started.countDown();return receiptAttempt(actionId(plan,old),2);});
        assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(receipt.isDone());release.countDown();assertEquals("ACTIVE",publish.get(10,TimeUnit.SECONDS).get("lifecycle"));assertEquals(409,receipt.get(10,TimeUnit.SECONDS));
        assertEquals(2,connections.size());assertEquals("SUPERSEDED",h.f.jdbc().queryForObject("SELECT status FROM care_plan_action WHERE revision_id=?",String.class,old));assertEquals("OPEN",h.f.jdbc().queryForObject("SELECT status FROM care_plan_action WHERE revision_id=?",String.class,next));
    }
    @Test void cancelledQueueFailureReleasesLockAndSecondCommandCanWin()throws Exception{
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,rev,map(),key(),0);
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);h.failQueue=true;
        Future<Integer>first=run(()->{try{h.transition(DOCTOR,plan,"CANCEL","First",key(),1);return 200;}catch(IllegalStateException e){return 500;}});assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Map<String,Object>>second=run(()->{started.countDown();return h.transition(DOCTOR,plan,"CANCEL","Second",key(),1);});
        assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(second.isDone());h.failQueue=false;release.countDown();
        // The first queue callback captures failure before waiting, so the rollback remains deterministic.
        assertEquals(500,first.get(10,TimeUnit.SECONDS));assertEquals("CANCELLED",second.get(10,TimeUnit.SECONDS).get("lifecycle"));
        assertEquals("Second",h.f.jdbc().queryForObject("SELECT cancel_reason FROM doctor_care_plan WHERE id=?",String.class,plan));assertEquals(2,h.count("care_plan_notification"));assertEquals(3,h.count("care_plan_command"));assertEquals(2,connections.size());
    }
    @Test void replayAfterWaitingForPlanLockUsesCurrentAuthorization()throws Exception{
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));String k=key();h.publish(DOCTOR,plan,rev,map(),k,0);
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        Future<Void>holder=run(()->h.tx.execute(s->{h.observeConnection();h.f.jdbc().queryForObject("SELECT id FROM doctor_care_plan WHERE id=? FOR UPDATE",Long.class,plan);locked.countDown();await(release);return null;}));assertTrue(locked.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>replay=run(()->{started.countDown();try{h.publish(DOCTOR,plan,rev,map(),k,0);return 200;}catch(CarePlanException e){return e.getStatus();}});
        assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(replay.isDone());h.f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED'");release.countDown();holder.get(10,TimeUnit.SECONDS);
        assertEquals(403,replay.get(10,TimeUnit.SECONDS));assertEquals(1,h.count("care_plan_notification"));assertEquals(2,connections.size());
    }
    @Test void simultaneousReceiptsSameCommandReturnOneOriginalResult() throws Exception {
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,rev,map(),key(),0);long a=actionId(plan,rev);String k=key();
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>first=run(()->action("submit",OWNER,a,receipt(),k,1));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Map<String,Object>>second=run(()->{started.countDown();return action("submit",OWNER,a,receipt(),k,1);});assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(second.isDone());release.countDown();
        Map<String,Object>result=first.get(10,TimeUnit.SECONDS);assertEquals(result,second.get(10,TimeUnit.SECONDS));assertEquals("SUBMITTED",result.get("actionStatus"));assertEquals(2L,result.get("version"));
        assertEquals(2,connections.size());assertEquals(3,h.count("care_plan_event"));assertEquals(3,h.count("care_plan_command"));assertEquals(2,h.count("care_plan_notification"));assertEquals(1L,h.f.jdbc().queryForObject("SELECT lock_version FROM care_plan_action WHERE id=?",Long.class,a));
    }
    @Test void simultaneousReceiptsDistinctCommandsOnlyOneAggregateVersionWins() throws Exception {
        Map<String,Object>d=h.create(body(2));long plan=id(d),rev=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,rev,map(),key(),0);List<Long>ids=h.f.jdbc().queryForList("SELECT id FROM care_plan_action WHERE plan_id=? ORDER BY ordinal",Long.class,plan);
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>first=run(()->action("submit",OWNER,ids.get(0),receipt(),key(),1));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>second=run(()->{started.countDown();return receiptAttempt(ids.get(1),1);});assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();release.countDown();
        assertEquals("SUBMITTED",first.get(10,TimeUnit.SECONDS).get("actionStatus"));assertEquals(409,second.get(10,TimeUnit.SECONDS));assertEquals(Arrays.asList("SUBMITTED","OPEN"),h.f.jdbc().queryForList("SELECT status FROM care_plan_action WHERE plan_id=? ORDER BY ordinal",String.class,plan));
        assertEquals(2,connections.size());assertEquals(3,h.count("care_plan_event"));assertEquals(3,h.count("care_plan_command"));assertEquals(2,h.count("care_plan_notification"));
    }
    @Test void receiptWinsAgainstConcurrentCancellationAtSameObservedVersion() throws Exception {
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,rev,map(),key(),0);long a=actionId(plan,rev);
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>first=run(()->action("submit",OWNER,a,receipt(),key(),1));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>cancel=run(()->{started.countDown();try{h.transition(DOCTOR,plan,"CANCEL","Synthetic",key(),1);return 200;}catch(CarePlanException e){return e.getStatus();}});assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();release.countDown();
        assertEquals("SUBMITTED",first.get(10,TimeUnit.SECONDS).get("actionStatus"));assertEquals(409,cancel.get(10,TimeUnit.SECONDS));assertEquals("ACTIVE",h.f.jdbc().queryForObject("SELECT lifecycle FROM doctor_care_plan WHERE id=?",String.class,plan));assertEquals("SUBMITTED",h.f.jdbc().queryForObject("SELECT status FROM care_plan_action WHERE id=?",String.class,a));assertEquals(2,connections.size());assertEquals(2,h.count("care_plan_notification"));
    }
    @Test void receiptWinsAgainstConcurrentRevisionAtSameObservedVersion() throws Exception {
        Map<String,Object>d=h.create(body(1));long plan=id(d),old=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,old,map(),key(),0);Map<String,Object>r=h.tx.execute(s->h.service.createRevision(DOCTOR,plan,key(),1));long next=num(r.get("draftRevisionId"));Map<String,Object>confirmation=h.confirmation(plan);long a=actionId(plan,old);
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>first=run(()->action("submit",OWNER,a,receipt(),key(),2));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>publish=run(()->{started.countDown();try{h.publish(DOCTOR,plan,next,confirmation,key(),2);return 200;}catch(CarePlanException e){return e.getStatus();}});assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();release.countDown();
        assertEquals("SUBMITTED",first.get(10,TimeUnit.SECONDS).get("actionStatus"));assertEquals(409,publish.get(10,TimeUnit.SECONDS));assertEquals(old,h.f.jdbc().queryForObject("SELECT current_revision_id FROM doctor_care_plan WHERE id=?",Long.class,plan));assertEquals("DRAFT",h.f.jdbc().queryForObject("SELECT status FROM care_plan_revision WHERE id=?",String.class,next));assertEquals(2,connections.size());assertEquals(2,h.count("care_plan_notification"));
    }
    @Test void simultaneousConfirmationsDistinctCommandsOnlyOneSucceeds() throws Exception {
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,rev,map(),key(),0);long a=actionId(plan,rev);action("submit",OWNER,a,receipt(),key(),1);
        connections.clear();CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);
        Future<Map<String,Object>>first=run(()->action("review",DOCTOR,a,map("decision","CONFIRM"),key(),2));assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>second=run(()->{started.countDown();try{action("review",DOCTOR,a,map("decision","RETURN","note","Synthetic"),key(),2);return 200;}catch(CarePlanException e){return e.getStatus();}});assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();release.countDown();
        assertEquals("CONFIRMED",first.get(10,TimeUnit.SECONDS).get("actionStatus"));assertEquals(409,second.get(10,TimeUnit.SECONDS));assertEquals("CONFIRMED",h.f.jdbc().queryForObject("SELECT status FROM care_plan_action WHERE id=?",String.class,a));assertEquals(2,connections.size());assertEquals(4,h.count("care_plan_command"));assertEquals(4,h.count("care_plan_event"));assertEquals(3,h.count("care_plan_notification"));
    }
    @Test void receiptReplayAfterBlockedPlanLockChecksFreshProxyWriteAuthority() throws Exception {
        h.grantFamily();Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,rev,map(),key(),0);long a=actionId(plan,rev);Map<String,Object>b=receipt();b.put("entryMode","ASSISTED");String k=key();action("submit",FAMILY,a,b,k,1);
        connections.clear();CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);Future<Void>holder=run(()->h.tx.execute(s->{h.observeConnection();h.f.jdbc().queryForObject("SELECT id FROM doctor_care_plan WHERE id=? FOR UPDATE",Long.class,plan);locked.countDown();await(release);return null;}));assertTrue(locked.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Integer>replay=run(()->{started.countDown();try{action("submit",FAMILY,a,b,k,1);return 200;}catch(CarePlanException e){return e.getStatus();}});assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();assertFalse(replay.isDone());h.f.jdbc().update("UPDATE care_access_grant SET access_level='READ' WHERE grantee_user_id=8");release.countDown();holder.get(10,TimeUnit.SECONDS);
        assertEquals(403,replay.get(10,TimeUnit.SECONDS));assertEquals(2,h.count("care_plan_notification"));assertEquals(3,h.count("care_plan_command"));assertEquals(2,connections.size());
    }
    @Test void failedReceiptQueueRollsBackAndWaitingReceiptCanWin() throws Exception {
        Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));h.publish(DOCTOR,plan,rev,map(),key(),0);long a=actionId(plan,rev);
        CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);blockFirstEnqueue(queued,release);h.failQueue=true;
        Future<Integer>first=run(()->{try{action("submit",OWNER,a,receipt(),key(),1);return 200;}catch(IllegalStateException e){return 500;}});assertTrue(queued.await(10,TimeUnit.SECONDS));
        CountDownLatch started=new CountDownLatch(1);Future<Map<String,Object>>second=run(()->{started.countDown();return action("submit",OWNER,a,receipt(),key(),1);});assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlockedPlanLock();h.failQueue=false;release.countDown();
        assertEquals(500,first.get(10,TimeUnit.SECONDS));assertEquals("SUBMITTED",second.get(10,TimeUnit.SECONDS).get("actionStatus"));assertEquals(2L,h.f.jdbc().queryForObject("SELECT lock_version FROM doctor_care_plan WHERE id=?",Long.class,plan));assertEquals(1L,h.f.jdbc().queryForObject("SELECT lock_version FROM care_plan_action WHERE id=?",Long.class,a));assertEquals(3,h.count("care_plan_command"));assertEquals(3,h.count("care_plan_event"));assertEquals(2,h.count("care_plan_notification"));assertEquals(2,connections.size());
    }
    private <T>Future<T>run(Callable<T>work){return pool.submit(()->{recording.set(true);try{return work.call();}finally{recording.remove();}});}
    private void blockFirstEnqueue(CountDownLatch queued,CountDownLatch release)throws Exception{
        AtomicBoolean first=new AtomicBoolean(true);
        CarePlanNotificationQueue capture=event->{boolean blocked=first.compareAndSet(true,false);boolean fail=h.failQueue;
            if(blocked){queued.countDown();await(release);}h.enqueue(event);if(blocked&&fail)throw new IllegalStateException("Synthetic queue failure");};
        ReflectionTestUtils.setField(h.service,"notifications",capture);actionQueue=capture;if(actionService!=null)ReflectionTestUtils.setField(actionService,"notifications",capture);
    }
    /** Calls the real service in its own observed transaction; no SQL state-machine substitute. */
    private int receiptAttempt(long action,long version){try{action("submit",OWNER,action,receipt(),key(),version);return 200;}catch(CarePlanException e){return e.getStatus();}}
    private long actionId(long plan,long revision){return h.f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE plan_id=? AND revision_id=?",Long.class,plan,revision);}
    private CarePlanActionService actions(){if(actionService==null)actionService=new CarePlanActionService(h.f.jdbc(),h.auth,h.properties,new CarePlanCommandStore(h.f.jdbc(),h.properties),new CarePlanEventStore(h.f.jdbc(),h.auth,h.properties),actionQueue);return actionService;}
    private Map<String,Object>action(String operation,long actor,long action,Map<String,Object>body,String command,long version){return h.tx.execute(s->{h.observeConnection();return CarePlanReceiptTest.invokeOn(actions(),operation,actor,action,body,command,version);});}
    private static Map<String,Object>receipt(){return map("note","Synthetic receipt","occurredAt","2026-10-03T04:00:00Z","entryMode","SELF","evidence",Collections.emptyList());}
    private void awaitBlockedPlanLock() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){
            int blocked=h.f.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.sessions WHERE state='BLOCKED' AND blocker_id>0 AND UPPER(statement) LIKE '%DOCTOR_CARE_PLAN%FOR UPDATE%'",Integer.class);
            if(blocked>0)return;
            try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
        fail("The contender never became a database-observed blocked plan-lock waiter");
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic concurrency barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
