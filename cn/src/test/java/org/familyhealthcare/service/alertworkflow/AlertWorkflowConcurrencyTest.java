package org.familyhealthcare.service.alertworkflow;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertWorkflowConcurrencyTest {
    AlertWorkflowHarness h;
    @BeforeEach void setup() throws Exception {h=new AlertWorkflowHarness();}
    @Test void twoAcceptsHaveOneVersionWinner() throws Exception {long id=h.adopt();h.assign(id,1);race(id,"ACCEPT",map("assigneeId",1,"dueAt",null));assertEquals("ACCEPTED",h.row(id).get("workflowState"));}
    @Test void twoClosuresHaveOneVersionWinner() throws Exception {long id=h.adopt();h.assign(id,1);h.accept(id,1);h.submit(id,1);h.feedback(id,1,"SELF");race(id,"CLOSE",map("reason","Reviewed"));assertEquals("CLOSED",h.row(id).get("workflowState"));}
    @Test void reassignmentHoldingSourceLockWinsAgainstAlreadyAuthorizedStaleClosure() throws Exception {
        long alert=h.adopt();h.assign(alert,1);h.accept(alert,1);h.submit(alert,1);h.feedback(alert,1,"SELF");
        Map<String,Object> before=h.row(alert);long expected=h.version(alert);
        assertEquals("READY_FOR_REVIEW",before.get("workflowState"));assertEquals(1L,id(before.get("acceptedBy")));
        assertNotNull(before.get("acceptedAt"));assertNotNull(before.get("currentHandlingEventId"));assertNotNull(before.get("currentFeedbackEventId"));
        long commands=h.count("alert_command"),events=h.count("alert_event"),outbox=h.count("alert_notification");
        String assignKey=UUID.randomUUID().toString(),closeKey=UUID.randomUUID().toString();
        CountDownLatch held=new CountDownLatch(1),attempted=new CountDownLatch(1),returned=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        AlertWorkflowEvidenceService observed=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p){
            @Override void lockReferences(List<Map<String,Object>> refs){attempted.countDown();super.lockReferences(refs);returned.countDown();}
        };
        AlertWorkflowService staleService=new AlertWorkflowService(h.db.jdbc,h.repo,h.auth,h.p,new AlertWorkflowEventStore(h.repo,h.auth),h.queue,observed,new MeasurementAssessmentService(h.db.jdbc));
        try {
            Future<Map<String,Object>> assign=pool.submit(()->h.db.tx.execute(tx->{
                h.repo.lockSource("HEALTH_MEASUREMENT",101);held.countDown();await(release);
                return h.service.command(1,alert,"ASSIGN",map("reason","A current nurse takes over","assigneeId",4),assignKey,expected);
            }));
            assertTrue(held.await(3,TimeUnit.SECONDS));
            Future<Integer> close=pool.submit(()->{try {
                h.db.tx.execute(tx->staleService.command(1,alert,"CLOSE",map("reason","Old accepted handling"),closeKey,expected));return 200;
            }catch(AlertWorkflowException failure){return failure.getStatus();}});
            assertTrue(attempted.await(3,TimeUnit.SECONDS),"The competing CLOSE must pass initial authorization and reach its source-lock boundary before ASSIGN commits");
            assertFalse(returned.await(150,TimeUnit.MILLISECONDS),"The held source lock must keep that already-started command from returning");assertFalse(close.isDone());
            release.countDown();Map<String,Object> winner=assign.get(5,TimeUnit.SECONDS);assertEquals(409,close.get(5,TimeUnit.SECONDS));assertEquals(0,returned.getCount());
            assertEquals(expected+1,id(winner.get("version")));assertEquals("OPEN",winner.get("state"));
            Map<String,Object> after=h.row(alert);assertEquals(expected+1,h.version(alert));assertEquals("OPEN",after.get("workflowState"));assertEquals(4L,id(after.get("responsibleUserId")));
            assertNull(after.get("acceptedBy"));assertNull(after.get("acceptedAt"));assertNull(after.get("closedBy"));assertNull(after.get("closedAt"));
            assertEquals(before.get("cycleNo"),after.get("cycleNo"));assertEquals(before.get("dueAt"),after.get("dueAt"));
            assertEquals(before.get("currentHandlingEventId"),after.get("currentHandlingEventId"),"Reassignment retains handling history for the new assignee's review");
            assertEquals(before.get("currentFeedbackEventId"),after.get("currentFeedbackEventId"),"Reassignment retains feedback history but resets acceptance");
            assertEquals(commands+1,h.count("alert_command"));assertEquals(events+1,h.count("alert_event"));assertEquals(outbox+2,h.count("alert_notification"));
            assertEquals(1,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_command WHERE actor_id=1 AND command_key=? AND alert_id=? AND result_json IS NOT NULL",Integer.class,assignKey,alert));
            assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_command WHERE actor_id=1 AND command_key=?",Integer.class,closeKey));
            long event=id(winner.get("eventId"));assertEquals("ASSIGN",h.db.jdbc.queryForObject("SELECT event_type FROM alert_event WHERE id=? AND alert_id=?",String.class,event,alert));
            assertEquals(Arrays.asList(1L,4L),h.db.jdbc.queryForList("SELECT recipient_user_id FROM alert_notification WHERE event_id=? ORDER BY recipient_user_id",Long.class,event));
            assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_command c LEFT JOIN alert_record a ON a.id=c.alert_id WHERE c.result_json IS NULL OR a.id IS NULL",Integer.class));
            assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_event e LEFT JOIN alert_record a ON a.id=e.alert_id WHERE a.id IS NULL OR e.patient_id<>a.patient_id",Integer.class));
            assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_notification n LEFT JOIN alert_event e ON e.id=n.event_id WHERE e.id IS NULL OR n.patient_id<>e.patient_id",Integer.class));
            h.denied(409,()->h.command(1,alert,"CLOSE",map("reason","Retained old history is not fresh acceptance")));
            assertEquals(after,h.row(alert));assertEquals(commands+1,h.count("alert_command"));assertEquals(events+1,h.count("alert_event"));assertEquals(outbox+2,h.count("alert_notification"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void outerRollbackLeavesNoAggregateSourceEventCommandOrOutboxOrphans() {
        assertThrows(IllegalStateException.class,()->h.db.tx.execute(s->{h.adopt();throw new IllegalStateException("forced rollback");}));
        for(String table:Arrays.asList("alert_record","alert_event","alert_source_identity","alert_source_revision","alert_source_link","alert_command","alert_notification"))assertEquals(0,h.count(table),table);
    }
    @Test void wrongEffectiveIsolationCannotWrite() {
        h.db.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class,()->h.adopt());assertEquals(0,h.count("alert_record"));
    }
    @Test void newCommandRechecksRevocationAfterInitialAuthorizationAtHeldSourceLock() throws Exception {revocationAtSourceLock(false);}
    @Test void successfulReplayRechecksRevocationAfterInitialAuthorizationAtHeldSourceLock() throws Exception {revocationAtSourceLock(true);}
    private void revocationAtSourceLock(boolean replay) throws Exception {
        h.db.jdbc.update("UPDATE care_access_grant SET access_level='WRITE' WHERE grantee_user_id=2");long alert=h.adopt();h.assign(alert,2);
        long expected=h.version(alert);String key=UUID.randomUUID().toString();Map<String,Object> body=map("assigneeId",2,"dueAt",null);
        if(replay){Map<String,Object> first=h.command(2,alert,"ACCEPT",body,key,expected);assertEquals(first,h.command(2,alert,"ACCEPT",body,key,expected));}
        Map<String,Object> original=h.row(alert);long commands=h.count("alert_command"),events=h.count("alert_event"),outbox=h.count("alert_notification");
        CountDownLatch held=new CountDownLatch(1),attempted=new CountDownLatch(1),returned=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        AlertWorkflowEvidenceService observed=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p){@Override void lockReferences(List<Map<String,Object>> refs){attempted.countDown();super.lockReferences(refs);returned.countDown();}};
        AlertWorkflowService service=new AlertWorkflowService(h.db.jdbc,h.repo,h.auth,h.p,new AlertWorkflowEventStore(h.repo,h.auth),h.queue,observed,new MeasurementAssessmentService(h.db.jdbc));
        try {
            Future<?> blocker=pool.submit(()->h.db.tx.execute(tx->{h.repo.lockSource("HEALTH_MEASUREMENT",101);held.countDown();await(release);return null;}));assertTrue(held.await(3,TimeUnit.SECONDS));
            Future<?> attempt=pool.submit(()->h.denied(403,()->h.db.tx.execute(tx->service.command(2,alert,"ACCEPT",body,key,expected))));
            assertTrue(attempted.await(3,TimeUnit.SECONDS),"Command must pass initial authorization and reach the source-lock attempt before revocation");assertEquals(1,returned.getCount(),"Held source lock must not have returned");
            h.db.jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=2");release.countDown();blocker.get(5,TimeUnit.SECONDS);attempt.get(5,TimeUnit.SECONDS);
            assertEquals(0,returned.getCount());assertEquals(original,h.row(alert));assertEquals(commands,h.count("alert_command"));assertEquals(events,h.count("alert_event"));assertEquals(outbox,h.count("alert_notification"));
            assertEquals(replay?1:0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_command WHERE actor_id=2 AND command_key=?",Integer.class,key));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void sourceCorrectionOwningLocksPreventsStaleClosureAndKeepsNewCycleOpen() throws Exception {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.submit(id,1);h.feedback(id,1,"SELF");long version=h.version(id);
        CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> correction=pool.submit(()->h.db.tx.execute(tx->{AlertWorkflowService.SourceChange change=h.service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",101);prepared.countDown();await(release);h.applyRevision(change,101,false);return null;}));assertTrue(prepared.await(3,TimeUnit.SECONDS));
            Future<?> close=pool.submit(()->h.denied(409,()->h.command(1,id,"CLOSE",map("reason","Old input"),UUID.randomUUID().toString(),version)));
            release.countDown();correction.get(5,TimeUnit.SECONDS);close.get(5,TimeUnit.SECONDS);assertEquals("OPEN",h.row(id).get("workflowState"));assertEquals(true,h.row(id).get("sourceReviewRequired"));assertNull(h.row(id).get("closedBy"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void outboxFailureRollsBackTheEntireAdoption() {
        h.db.jdbc.execute("DROP TABLE notification_channel");assertThrows(org.springframework.dao.DataAccessException.class,()->h.adopt());
        for(String table:Arrays.asList("alert_record","alert_event","alert_source_identity","alert_source_revision","alert_source_link","alert_command","alert_notification"))assertEquals(0,h.count(table),table);
    }
    @Test void sourceChangeTokenCannotMoveAcrossTransactionsOrBeReused() {
        long id=h.adopt();final AlertWorkflowService.SourceChange[] token=new AlertWorkflowService.SourceChange[1];
        h.db.tx.execute(tx->{token[0]=h.service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",101);h.applyRevision(token[0],101,false);assertThrows(IllegalStateException.class,()->h.service.sourceChanged(1,token[0],"Again"));return null;});
        assertThrows(IllegalStateException.class,()->h.db.tx.execute(tx->h.service.sourceChanged(1,token[0],"Other transaction")));assertEquals(2,id(h.row(id).get("sourceRevision")));
    }
    @Test void newEvidenceLinkedDuringSourceLockWaitConflictsInsteadOfAcquiringLateDependencies() throws Exception {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.submit(id,1);h.feedback(id,1,"SELF");long version=h.version(id);
        CountDownLatch held=new CountDownLatch(1),attempted=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        AlertWorkflowEvidenceService observed=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p){@Override void lockReferences(List<Map<String,Object>> refs){attempted.countDown();super.lockReferences(refs);}};
        AlertWorkflowService service=new AlertWorkflowService(h.db.jdbc,h.repo,h.auth,h.p,new AlertWorkflowEventStore(h.repo,h.auth),h.queue,observed,new MeasurementAssessmentService(h.db.jdbc));
        try {
            Future<?> writer=pool.submit(()->h.db.tx.execute(tx->{h.repo.lockSource("HEALTH_MEASUREMENT",101);held.countDown();await(release);Map<String,Object> source=h.repo.lockSource("HEALTH_MEASUREMENT",202);observed.ensureTracked(1,source);Map<String,Object> alert=h.repo.lockAlert(id);h.repo.addEvidence(id(alert.get("currentHandlingEventId")),"HEALTH_MEASUREMENT",202,id(source.get("sourceRevision")),null,"VALID");return null;}));assertTrue(held.await(3,TimeUnit.SECONDS));
            Future<?> close=pool.submit(()->h.denied(409,()->h.db.tx.execute(tx->service.command(1,id,"CLOSE",map("reason","Old link set"),UUID.randomUUID().toString(),version))));assertTrue(attempted.await(3,TimeUnit.SECONDS));release.countDown();writer.get(5,TimeUnit.SECONDS);close.get(5,TimeUnit.SECONDS);assertEquals("READY_FOR_REVIEW",h.row(id).get("workflowState"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void preparedSourceCorrectionCanFinishVerifiedNoChangeReplayButNotHideNativeMutation() throws Exception {
        h.adopt();long events=h.count("alert_event");
        h.db.tx.execute(tx->{AlertWorkflowService.SourceChange change=h.service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",101);assertFalse(change.isCompleted());h.service.completeSourceChangeReplay(1,change);assertTrue(change.isCompleted());return null;});assertEquals(events,h.count("alert_event"));
        h.denied(409,()->h.db.tx.execute(tx->{AlertWorkflowService.SourceChange change=h.service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",101);h.db.jdbc.update("UPDATE health_measurement SET value_primary=5 WHERE id=101");h.service.completeSourceChangeReplay(1,change);return null;}));assertEquals(events,h.count("alert_event"));
    }

    private void race(long id,String op,Map<String,Object> body) throws Exception {
        long v=h.version(id);CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {Callable<Integer> call=()->{await(start);try{h.command(1,id,op,body,UUID.randomUUID().toString(),v);return 200;}catch(AlertWorkflowException e){return e.getStatus();}};Future<Integer>a=pool.submit(call),b=pool.submit(call);start.countDown();List<Integer> statuses=Arrays.asList(a.get(8,TimeUnit.SECONDS),b.get(8,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(Arrays.asList(200,409),statuses);assertEquals(v+1,h.version(id));}finally{pool.shutdownNow();}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
