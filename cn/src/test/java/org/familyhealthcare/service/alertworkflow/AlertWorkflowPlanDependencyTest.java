package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-module integration uses actual services and independent JDBC transactions, never an external database. */
class AlertWorkflowPlanDependencyTest {
    AlertWorkflowHarness h; CarePlanProperties cp; CarePlanAuthorizationService auth; CarePlanActionService actions; CarePlanService plans;
    @BeforeEach void setup() throws Exception {
        h=new AlertWorkflowHarness();
        for(String table:Arrays.asList("care_plan_evidence","care_plan_event","care_plan_action","doctor_care_plan"))h.db.jdbc.execute("DROP TABLE "+table);
        String sql=AlertWorkflowTestDatabase.read("src/main/resources/sql/init.sql");
        String plan=AlertWorkflowTestDatabase.tableSql(sql,"doctor_care_plan").replaceAll("(?m)^  CONSTRAINT fk_doctor_plan_(current_revision|draft_revision).*\\n","");
        h.db.jdbc.execute(AlertWorkflowTestDatabase.h2(plan));
        for(String table:Arrays.asList("care_plan_revision","care_plan_action","care_plan_event","care_plan_evidence","care_plan_command","care_plan_notification"))h.db.jdbc.execute(AlertWorkflowTestDatabase.h2(AlertWorkflowTestDatabase.tableSql(sql,table)));
        cp=new CarePlanProperties(true,Clock.fixed(AlertWorkflowTestDatabase.NOW,ZoneOffset.UTC));auth=new CarePlanAuthorizationService(h.db.jdbc,cp);
        CarePlanEventStore events=new CarePlanEventStore(h.db.jdbc,auth,cp);CarePlanCommandStore commands=new CarePlanCommandStore(h.db.jdbc,cp);
        CarePlanNotificationQueue queue=new CarePlanNotificationWorker(h.db.jdbc,auth,cp,(channel,event,title,path)->{throw new AssertionError("No live notification in service integration tests");});
        actions=new CarePlanActionService(h.db.jdbc,auth,cp,commands,events,queue);
        plans=new CarePlanService(h.db.jdbc,auth,cp,new CarePlanQueryService(h.db.jdbc,auth,cp),commands,events,queue);
        h.db.jdbc.update("INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions,workflow_version,lifecycle,current_revision_id,lock_version) VALUES(90,3,10,'Fictional plan','Record a repeat reading',1,'ACTIVE',91,0)");
        h.db.jdbc.update("INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at) VALUES(91,90,1,'PUBLISHED','Fictional plan','Repeat reading','FOLLOW_UP','{}',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        Map<String,Object>draft=map("patientId",10,"title","Fictional plan","instructions","Repeat reading","planType","FOLLOW_UP","actions",Arrays.asList(map("ordinal",1,"instruction","Record a repeat reading","dueAt",AlertWorkflowTestDatabase.NOW.plusSeconds(3600).toString(),"assignedUserId",1,"evidence",Collections.emptyList())));
        h.db.jdbc.update("UPDATE care_plan_revision SET draft_json=? WHERE id=91",json(draft));
        h.db.jdbc.update("INSERT INTO care_plan_action(id,plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,status,created_at,updated_at) VALUES(92,90,91,10,1,'Record a repeat reading',CURRENT_TIMESTAMP,1,'OPEN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
    Map<String,Object> receipt(){return map("note","Fictional repeat reading","occurredAt",h.time,"entryMode","SELF","evidence",Arrays.asList(map("sourceType","MEASUREMENT","sourceId",202)));}
    Map<String,Object> submit(){return h.db.tx.execute(tx->actions.submit(1,92,receipt(),UUID.randomUUID().toString(),planVersion()));}
    long planVersion(){return h.db.jdbc.queryForObject("SELECT lock_version FROM doctor_care_plan WHERE id=90",Long.class);}
    @Test void receiptCannotAttachANewMeasurementDependencyWhileItsCorrectionOwnsTheSourceLock() throws Exception {
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),authorized=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        CarePlanAuthorizationService observed=new CarePlanAuthorizationService(h.db.jdbc,cp){@Override public boolean canReadEvidence(long actor,long patient,String type,long source){boolean valid=super.canReadEvidence(actor,patient,type,source);authorized.countDown();return valid;}};
        CarePlanActionService receiptService=new CarePlanActionService(h.db.jdbc,observed,cp,new CarePlanCommandStore(h.db.jdbc,cp),new CarePlanEventStore(h.db.jdbc,observed,cp),event->{});
        try {
            Future<?> correction=pool.submit(()->h.db.tx.execute(tx->{AlertWorkflowService.SourceChange change=h.service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",202);held.countDown();await(release);h.applyRevision(change,202,false);return null;}));assertTrue(held.await(3,TimeUnit.SECONDS));
            Future<?> receipt=pool.submit(()->h.db.tx.execute(tx->receiptService.submit(1,92,receipt(),UUID.randomUUID().toString(),0)));
            assertTrue(authorized.await(3,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->receipt.get(250,TimeUnit.MILLISECONDS),"Receipt must wait on the correction's native-source lock even when its parent was absent at discovery");
            release.countDown();correction.get(5,TimeUnit.SECONDS);receipt.get(5,TimeUnit.SECONDS);
            assertEquals(1,h.count("care_plan_evidence"));assertEquals(2,h.count("alert_source_revision"));assertEquals("SUBMITTED",h.db.jdbc.queryForObject("SELECT status FROM care_plan_action WHERE id=92",String.class));
        }finally{release.countDown();pool.shutdownNow();}
    }
    static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    void confirm(){h.db.tx.execute(tx->actions.review(3,92,map("decision","CONFIRM"),UUID.randomUUID().toString(),planVersion()));}
    long linkedClinicalAlert() {
        long alert=h.adopt();h.command(1,alert,"ESCALATE",map("reason","Clinical review","assigneeId",3));h.accept(alert,3);
        h.command(3,alert,"SUBMIT",map("note","Review the plan receipt","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));h.feedback(alert,1,"SELF");return alert;
    }
    @Test void invalidatedNestedMeasurementCannotBeMadeCompletionValidByLaterDoctorConfirmation() {
        submit();long alert=linkedClinicalAlert();
        MeasurementWorkflowBridge bridge=new MeasurementWorkflowBridge(h.db.jdbc,h.p,h.auth,h.repo,h.service,new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p));
        h.db.tx.execute(tx->bridge.correct(1,"HEALTH_MEASUREMENT",202,map("patientId",10,"commandKey",UUID.randomUUID().toString(),"expectedVersion",0,"reason","Wrong source device"),true,source->{fail("Invalidation must not mutate native row");}));
        confirm();long reference=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_type='CARE_PLAN_ACTION' AND source_id=92",Long.class);
        h.denied(409,()->h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",map("reason","Later confirmation cannot rehabilitate invalid evidence","evidenceId",reference,"resolution","REPLACE","replacement",map("sourceType","CARE_PLAN_ACTION","sourceId",92))));
        assertEquals("INVALIDATED",h.repo.readSource(3,10,"HEALTH_MEASUREMENT",202).get("sourceStatus"));assertDoesNotThrow(()->h.detail(3,alert));assertTrue(h.detail(3,alert).toString().contains("REFERENCE_CHANGED"));
    }
    @Test void actualFreshDoctorConfirmationCanResolveCorrectedActiveSourceForTheSameAction() {
        submit();long alert=linkedClinicalAlert();h.revise(202,false);confirm();long reference=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_type='CARE_PLAN_ACTION' AND source_id=92",Long.class);
        h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",map("reason","Fresh doctor review","evidenceId",reference,"resolution","REPLACE","replacement",map("sourceType","CARE_PLAN_ACTION","sourceId",92)));
        h.command(3,alert,"REVIEW_SOURCE_CHANGE",map("reason","Current source reviewed","sourceRevision",1));h.accept(alert,3);h.submit(alert,3);h.feedback(alert,1,"SELF");h.command(3,alert,"CLOSE",map("reason","Clinical review complete"));assertEquals("CLOSED",h.row(alert).get("workflowState"));
    }

    @Test void receiptWinningTheSourceLockMakesAnUndiscoveredParentCorrectionConflict() throws Exception {
        long alert=linkedClinicalAlert();CountDownLatch attached=new CountDownLatch(1),attempted=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        AlertWorkflowEvidenceService observed=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p){@Override void lockReferences(List<Map<String,Object>>refs){assertFalse(refs.stream().anyMatch(r->"CARE_PLAN_ACTION".equals(r.get("sourceType"))),"The uncommitted receipt parent must be absent from initial discovery");attempted.countDown();super.lockReferences(refs);}};
        AlertWorkflowService correctionService=new AlertWorkflowService(h.db.jdbc,h.repo,h.auth,h.p,new AlertWorkflowEventStore(h.repo,h.auth),h.queue,observed,new MeasurementAssessmentService(h.db.jdbc));
        try {
            Future<?> receipt=pool.submit(()->h.db.tx.execute(tx->{actions.submit(1,92,receipt(),UUID.randomUUID().toString(),0);attached.countDown();await(release);return null;}));assertTrue(attached.await(3,TimeUnit.SECONDS));
            Future<?> correction=pool.submit(()->h.denied(409,()->h.db.tx.execute(tx->{AlertWorkflowService.SourceChange change=correctionService.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",202);fail("Changed link set must conflict before native mutation");return change;})));
            assertTrue(attempted.await(3,TimeUnit.SECONDS));release.countDown();receipt.get(5,TimeUnit.SECONDS);correction.get(5,TimeUnit.SECONDS);
            assertEquals("READY_FOR_REVIEW",h.row(alert).get("workflowState"));assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_source_identity WHERE source_id=202",Integer.class));
            h.revise(202,false);assertEquals("OPEN",h.row(alert).get("workflowState"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void actualPlanCancellationHoldingTheParentPreventsConcurrentStaleClosure() throws Exception {
        submit();confirm();long alert=linkedClinicalAlert();CountDownLatch changed=new CountDownLatch(1),closing=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);long version=h.version(alert);
        AlertWorkflowEvidenceService observed=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p){@Override void lockReferences(List<Map<String,Object>>refs){closing.countDown();super.lockReferences(refs);}};
        AlertWorkflowService closeService=new AlertWorkflowService(h.db.jdbc,h.repo,h.auth,h.p,new AlertWorkflowEventStore(h.repo,h.auth),h.queue,observed,new MeasurementAssessmentService(h.db.jdbc));
        try {
            Future<?> cancellation=pool.submit(()->h.db.tx.execute(tx->{plans.transitionPlan(3,90,"CANCEL","Reasoned termination",UUID.randomUUID().toString(),planVersion());changed.countDown();await(release);return null;}));assertTrue(changed.await(3,TimeUnit.SECONDS));
            Future<?> close=pool.submit(()->h.denied(409,()->h.db.tx.execute(tx->closeService.command(3,alert,"CLOSE",map("reason","Stale completion"),UUID.randomUUID().toString(),version))));assertTrue(closing.await(3,TimeUnit.SECONDS));release.countDown();cancellation.get(5,TimeUnit.SECONDS);close.get(5,TimeUnit.SECONDS);
            assertEquals("READY_FOR_REVIEW",h.row(alert).get("workflowState"));assertTrue(h.detail(3,alert).toString().contains("REFERENCE_CHANGED"));assertEquals(1,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_event WHERE event_type='PLAN_CANCELLED'",Integer.class));
            long ref=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=92",Long.class);h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",map("reason","End dependency because the plan was cancelled","evidenceId",ref,"resolution","WITHDRAW"));h.command(3,alert,"RETURN",map("reason","Record current handling after termination","targetState","IN_PROGRESS"));h.submit(alert,3);h.feedback(alert,1,"SELF");h.command(3,alert,"CLOSE",map("reason","Current clinical handling reviewed"));assertEquals("CLOSED",h.row(alert).get("workflowState"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void actualPlanSupersessionAndReceiptReturnRemainBlockingUntilCurrentDoctorConfirmation() {
        submit();long alert=linkedClinicalAlert();h.db.tx.execute(tx->actions.review(3,92,map("decision","RETURN","note","Supply corrected context"),UUID.randomUUID().toString(),planVersion()));
        h.denied(409,()->h.command(3,alert,"CLOSE",map("reason","Returned receipt is insufficient")));
        Map<String,Object> revision=h.db.tx.execute(tx->plans.createRevision(3,90,UUID.randomUUID().toString(),planVersion()));long revisionId=id(revision.get("draftRevisionId"));Map<?,?>impact=(Map<?,?>)revision.get("revisionImpact");
        h.db.tx.execute(tx->plans.publish(3,90,revisionId,map("currentRevisionId",91,"supersededActionDigest",impact.get("digest")),UUID.randomUUID().toString(),planVersion()));
        assertEquals("SUPERSEDED",h.db.jdbc.queryForObject("SELECT status FROM care_plan_action WHERE id=92",String.class));h.denied(409,()->h.command(3,alert,"CLOSE",map("reason","Superseded receipt is insufficient")));
        long replacement=h.db.jdbc.queryForObject("SELECT id FROM care_plan_action WHERE revision_id=?",Long.class,revisionId),ref=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=92",Long.class);
        h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",map("reason","Use new plan action","evidenceId",ref,"resolution","REPLACE","replacement",map("sourceType","CARE_PLAN_ACTION","sourceId",replacement)));
        h.denied(409,()->h.command(3,alert,"CLOSE",map("reason","Unconfirmed replacement cannot close")));
        h.db.tx.execute(tx->actions.submit(1,replacement,receipt(),UUID.randomUUID().toString(),planVersion()));h.db.tx.execute(tx->actions.review(3,replacement,map("decision","CONFIRM"),UUID.randomUUID().toString(),planVersion()));
        long currentRef=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=? AND dependency_state<>'WITHDRAWN'",Long.class,replacement);
        h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",map("reason","Revalidate doctor-confirmed version","evidenceId",currentRef,"resolution","REPLACE","replacement",map("sourceType","CARE_PLAN_ACTION","sourceId",replacement)));
        h.command(3,alert,"RETURN",map("reason","Record fresh handling for replacement","targetState","IN_PROGRESS"));h.submit(alert,3);h.feedback(alert,1,"SELF");h.command(3,alert,"CLOSE",map("reason","Replacement confirmed and handling current"));assertEquals("CLOSED",h.row(alert).get("workflowState"));
    }

    @Test void correctionBeforeFirstAlertAttachmentRejectsEarlierDoctorConfirmationEverywhere() {
        submit();confirm();h.revise(202,false);
        long alert=h.adopt();h.command(1,alert,"ESCALATE",map("reason","Clinical review","assigneeId",3));h.accept(alert,3);
        h.denied(409,()->h.command(3,alert,"SUBMIT",map("note","Old confirmation is stale","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92)))));
        assertEquals("ACCEPTED",h.row(alert).get("workflowState"));assertEquals(0,h.count("alert_evidence"));
    }
    @Test void correctionBeforeFirstAttachmentAllowsGenuinelyLaterDoctorConfirmation() {
        submit();h.revise(202,false);confirm();long alert=linkedClinicalAlert();
        h.command(3,alert,"CLOSE",map("reason","Actual later doctor review qualifies current source"));assertEquals("CLOSED",h.row(alert).get("workflowState"));
    }
    @Test void firstReceiptWinningSourceWithoutAnyAlertMakesCorrectionConflictBeforeMutation() throws Exception {
        CountDownLatch attached=new CountDownLatch(1),attempted=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        AlertWorkflowEvidenceService observed=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p){@Override void lockReferences(List<Map<String,Object>>refs){assertFalse(refs.stream().anyMatch(r->"CARE_PLAN_ACTION".equals(r.get("sourceType"))));attempted.countDown();super.lockReferences(refs);}};
        AlertWorkflowService service=new AlertWorkflowService(h.db.jdbc,h.repo,h.auth,h.p,new AlertWorkflowEventStore(h.repo,h.auth),h.queue,observed,new MeasurementAssessmentService(h.db.jdbc));
        try {
            Future<?> receipt=pool.submit(()->h.db.tx.execute(tx->{submit();attached.countDown();await(release);return null;}));assertTrue(attached.await(3,TimeUnit.SECONDS));
            Future<?> correction=pool.submit(()->h.denied(409,()->h.db.tx.execute(tx->{AlertWorkflowService.SourceChange change=service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",202);h.applyRevision(change,202,false);return null;})));
            assertTrue(attempted.await(3,TimeUnit.SECONDS));release.countDown();receipt.get(5,TimeUnit.SECONDS);correction.get(5,TimeUnit.SECONDS);
            assertEquals(0,h.count("alert_source_revision"));assertEquals(0,h.count("alert_record"));
        }finally{release.countDown();pool.shutdownNow();}
    }

    @Test void correctionWatermarkIsPrivateDurableAndUnknownMetadataNeverMeansUnaffected() {
        submit();List<Map<String,Object>> originalEvents=h.db.jdbc.queryForList("SELECT payload_json FROM care_plan_event ORDER BY id");h.revise(202,false);
        String privateMetadata=h.db.jdbc.queryForObject("SELECT action_versions_json FROM alert_source_revision WHERE revision_no=2",String.class);assertEquals(1L,id(object(privateMetadata).get("92")));
        MeasurementWorkflowBridge bridge=new MeasurementWorkflowBridge(h.db.jdbc,h.p,h.auth,h.repo,h.service,new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p));
        assertFalse(h.auth.canReadEvidence(2,10,"CARE_PLAN_ACTION",92));
        Map<String,Object> history=h.db.tx.execute(tx->bridge.history(2,10,"HEALTH_MEASUREMENT",202,0,100));
        assertFalse(json(history).contains("action_versions"));assertFalse(json(history).contains("actionVersions"));assertFalse(json(history).contains("\"92\""));assertEquals(originalEvents,h.db.jdbc.queryForList("SELECT payload_json FROM care_plan_event ORDER BY id"));
        // Corrupt/old unknown metadata is a separate fail-closed condition, not a trusted empty set.
        confirm();AlertWorkflowEvidenceService evidence=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p);
        assertDoesNotThrow(()->evidence.requireFreshPlanConfirmation(map("sourceType","CARE_PLAN_ACTION","sourceId",92)));
        h.db.jdbc.update("UPDATE alert_source_revision SET action_versions_json=NULL WHERE revision_no=1");
        h.denied(409,()->evidence.requireFreshPlanConfirmation(map("sourceType","CARE_PLAN_ACTION","sourceId",92)));
        for(String unknown:Arrays.asList("null","[]","{\"92\":\"bad\"}","broken")){h.db.jdbc.update("UPDATE alert_source_revision SET action_versions_json=? WHERE revision_no=1",unknown);h.denied(409,()->evidence.requireFreshPlanConfirmation(map("sourceType","CARE_PLAN_ACTION","sourceId",92)));}
    }
    @Test void correctionOnAnExistingAlertStillBlocksPreviouslyConfirmedActionOnAnotherAlert() {
        submit();confirm();long first=linkedClinicalAlert();h.revise(202,false);assertEquals("OPEN",h.row(first).get("workflowState"));
        h.db.jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) SELECT 404,patient_id,recorded_by,metric_type,18,unit,measured_at FROM health_measurement WHERE id=101");
        long second=h.adopt(404,false);h.command(1,second,"ESCALATE",map("reason","Independent clinical follow-up","assigneeId",3));h.accept(second,3);
        h.denied(409,()->h.command(3,second,"SUBMIT",map("note","An independent alert cannot reset source qualification","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92)))));
        h.revise(202,true);h.revise(202,false);h.denied(409,()->h.command(3,second,"SUBMIT",map("note","Reactivation also retains prior obligation","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92)))));
    }
    @Test void genuinelyLaterConfirmationStillNeedsCurrentClinicalAuthorityAtAttachment() {
        submit();h.revise(202,false);confirm();long alert=h.adopt();h.command(1,alert,"ESCALATE",map("reason","Review","assigneeId",3));h.accept(alert,3);
        h.db.jdbc.update("UPDATE doctor_patient_assignment SET status='ENDED' WHERE doctor_user_id=3");long count=h.count("alert_event");
        h.denied(403,()->h.command(3,alert,"SUBMIT",map("note","Revoked doctor","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92)))));assertEquals(count,h.count("alert_event"));assertEquals(0,h.count("alert_evidence"));
    }
    @Test void discoveredReceiptParentSerializesConfirmationBeforeCorrectionEvenWithoutAlerts() throws Exception {
        submit();CountDownLatch held=new CountDownLatch(1),attempted=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        AlertWorkflowEvidenceService observed=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p){@Override void lockReferences(List<Map<String,Object>>refs){assertTrue(refs.stream().anyMatch(r->"CARE_PLAN_ACTION".equals(r.get("sourceType"))&&id(r.get("sourceId"))==92));attempted.countDown();super.lockReferences(refs);}};
        AlertWorkflowService service=new AlertWorkflowService(h.db.jdbc,h.repo,h.auth,h.p,new AlertWorkflowEventStore(h.repo,h.auth),h.queue,observed,new MeasurementAssessmentService(h.db.jdbc));
        try {
            Future<?> confirmation=pool.submit(()->h.db.tx.execute(tx->{h.db.jdbc.queryForList("SELECT id FROM doctor_care_plan WHERE id=90 FOR UPDATE");held.countDown();await(release);confirm();return null;}));assertTrue(held.await(3,TimeUnit.SECONDS));
            Future<?> correction=pool.submit(()->h.db.tx.execute(tx->{AlertWorkflowService.SourceChange change=service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",202);h.applyRevision(change,202,false);return null;}));assertTrue(attempted.await(3,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->correction.get(250,TimeUnit.MILLISECONDS));release.countDown();confirmation.get(5,TimeUnit.SECONDS);correction.get(5,TimeUnit.SECONDS);
            assertEquals(2L,id(object(h.db.jdbc.queryForObject("SELECT action_versions_json FROM alert_source_revision WHERE revision_no=2",String.class)).get("92")));assertEquals(0,h.count("alert_record"));
        }finally{release.countDown();pool.shutdownNow();}
    }

}
