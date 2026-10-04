package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.service.careplan.CareExecutionReport.*;
import org.familyhealthcare.service.careplan.CareExecutionReportAccess.*;
import org.familyhealthcare.service.careplan.CareExecutionReportContracts.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** All rows are synthetic. H2 verifies projection semantics, not native MySQL MVCC. */
class CareExecutionReportProjectionTest {
    private CarePlanTestFixture f;
    private JdbcTemplate jdbc;
    private CareExecutionReportProjector projector;
    private CarePlanAuthorizationService auth;
    private CareExecutionReportAccessTest.RecordingDataSource observed;
    private DataSourceTransactionManager transactions;
    @BeforeEach void setup() throws Exception {
        f = new CarePlanTestFixture();
        CareExecutionReportTestData.seedReportScenario(f.jdbc(),f.now());
        observed=new CareExecutionReportAccessTest.RecordingDataSource(f.jdbc().getDataSource());
        jdbc=new JdbcTemplate(observed); transactions=new DataSourceTransactionManager(observed);
        auth=new CarePlanAuthorizationService(jdbc,new CarePlanProperties(true,Clock.fixed(f.now(),ZoneOffset.UTC)));
        projector=new CareExecutionReportProjector(jdbc,auth,transactions);
    }
    @AfterEach void close() throws Exception {f.close();}
    @Test void currentDenominatorAndReviewWait() {
        CareExecutionReport r=project(null,null,true).getReport();
        assertEquals(6,r.getCurrentSummary().getTotal());
        assertEquals(2,r.getCurrentSummary().getOpen()); assertEquals(1,r.getCurrentSummary().getNeedsHelp());
        assertEquals(2,r.getCurrentSummary().getSubmitted()); assertEquals(1,r.getCurrentSummary().getConfirmed());
        CurrentAction submitted=action(r,104); assertFalse(submitted.isOverdue());
        assertEquals(f.now().minusSeconds(60),submitted.getReviewWaitingSince());
        assertEquals(1006,submitted.getLatestReceipt().getEventId());
        assertTrue(action(r,102).isNeedsSupplement()); assertEquals(1,r.getCurrentSummary().getNeedsSupplement());
        assertTrue(r.getCurrentAttention().stream().anyMatch(a->a.getActionId()==103));
        assertEquals("CONTACTED",action(r,103).getLatestFollowUp().getFollowUpKind());
        assertFalse(r.getCurrentActions().stream().anyMatch(a->a.getRevisionId()==100||a.getPlanId()!=100));
        assertEquals("原文：新版指示",submitted.getInstructions());
        assertThrows(UnsupportedOperationException.class,()->r.getCurrentActions().clear());
        assertThrows(UnsupportedOperationException.class,()->r.getPeriodEvents().clear());
    }
    @Test void periodUsesRecordedTimeAndOriginalRevision() {
        CareExecutionReport r=project(null,null,true).getReport();
        PeriodEvent old=r.getPeriodEvents().stream().filter(e->e.getEventId()==1001).findFirst().get();
        assertEquals(100,old.getRevisionId()); assertEquals("Synthetic original instructions",old.getInstructions());
        assertEquals("SUBMITTED",old.getActionStatusAfterEvent()); // Mutable old action is now CONFIRMED.
        assertEquals(f.now().minusSeconds(86400*40),old.getOccurredAt()); assertFalse(old.isRevisionIsCurrentAtGeneration());
        assertEquals(9,r.getActivitySummary().getEventCount()); assertEquals(3,r.getActivitySummary().getDistinctActionCount());
        assertEquals(4,r.getActivitySummary().getEventTypeCounts().get("RECEIPT_SUBMITTED"));
        assertFalse(r.getPeriodEvents().stream().anyMatch(e->e.getRevisionId()==400||e.getEventId()==1007));
        assertTrue(r.getPeriodEvents().stream().anyMatch(e->e.getActionId()==null&&e.getEventType().equals("PLAN_CLOSED")));
        assertEquals(0,project(200L,null,true).getReport().getCurrentSummary().getTotal());
        assertEquals(1,project(200L,null,true).getReport().getPeriodEvents().size());
    }
    @Test void questionsAndEvidenceAreSeparateScopes() {
        f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,granted_by) VALUES(1,8,'FAMILY','READ','CARE_PLAN,MEASUREMENTS',7)");
        CareExecutionReportProjector.Projection p=projectAs(FAMILY,null,null,false);
        assertEquals("NOT_AUTHORIZED",p.getReport().getQuestionsAvailability()); assertFalse(p.getManifest().isQuestionsIncluded());
        List<Evidence> refs=action(p.getReport(),101).getEvidence(); assertFalse(refs.get(0).isRestricted());
        assertEquals("/care-journey?tab=measurements&patientId=1&measurementId=501",refs.get(0).getDetailPath());
        assertTrue(refs.get(1).isRestricted()); assertNull(refs.get(1).getSourceId()); assertNull(refs.get(1).getTitle());
        assertEquals(Collections.singleton(new EvidenceKey("MEASUREMENT",501)),p.getManifest().getReadableEvidence());
        assertFalse(observed.reads.stream().anyMatch(r->r.sql.contains("care_item")));
        p=project(null,null,true); assertTrue(p.getManifest().isQuestionsIncluded());
        Question q=p.getReport().getQuestions().get(0); assertEquals("原文：待讨论",q.getDescription());
        assertEquals("LEGACY_UNZONED",q.getTimeBasis()); assertEquals("2026-08-01 09:00:00",q.getEventAtLocal()); assertNull(q.getUpdatedAtLocal());
        observed.reads.clear(); assertEquals("NOT_INCLUDED_IN_PLAN_SCOPE",project(100L,null,true).getReport().getQuestionsAvailability());
        assertFalse(observed.reads.stream().anyMatch(r->r.sql.contains("care_item")));
        f.jdbc().update("DELETE FROM care_item"); p=project(null,null,true);
        assertEquals("AVAILABLE",p.getReport().getQuestionsAvailability()); assertTrue(p.getReport().getQuestions().isEmpty()); assertTrue(p.getManifest().isQuestionsIncluded());
        for(CareExecutionReportAccessTest.Read read:observed.reads) for(String forbidden:Arrays.asList("value_primary","file_content","ai_raw_result","medical_history","password")) assertFalse(read.sql.contains(forbidden),read.sql);
    }
    @Test void csvLoadsOnlyItsProfile() {
        CareExecutionReportProjector.Projection p=project(null,"actions_csv",true);
        assertEquals(6,p.getReport().getCurrentActions().size()); assertNull(p.getReport().getActivitySummary());
        assertTrue(p.getReport().getPeriodEvents().isEmpty()); assertFalse(p.getManifest().isQuestionsIncluded());
        assertFalse(observed.reads.stream().anyMatch(r->r.sql.contains("recorded_at>=")||r.sql.contains("care_item")));
        observed.reads.clear(); p=project(null,"events_csv",true);
        assertNull(p.getReport().getCurrentSummary()); assertTrue(p.getReport().getCurrentActions().isEmpty());
        assertEquals(9,p.getReport().getPeriodEvents().size());
        assertFalse(observed.reads.stream().anyMatch(r->r.sql.contains("review_waiting_since")||r.sql.contains("care_item")||r.sql.contains("draft_json")));
    }
    @Test void incompleteRelationsFailClosed() {
        f.jdbc().update("UPDATE care_plan_action SET status='UNKNOWN' WHERE id=101"); inconsistent();
        f.jdbc().update("UPDATE care_plan_action SET status='OPEN' WHERE id=101");
        f.jdbc().update("UPDATE care_plan_action SET review_waiting_since=NULL WHERE id=104"); inconsistent();
        f.jdbc().update("UPDATE care_plan_action SET review_waiting_since=? WHERE id=104",utc(f.now()));
        f.jdbc().update("UPDATE doctor_care_plan SET current_revision_id=NULL WHERE id=100"); inconsistent();
    }
    @Test void latestStateUsesAppendOrderRatherThanRecordedTime() {
        CareExecutionReportTestData.seedEvent(f.jdbc(),1100,100,101,102L,"RECEIPT_SUBMITTED",f.now().minusSeconds(10000),f.now().minusSeconds(20000),"{}");
        f.jdbc().update("UPDATE care_plan_action SET status='SUBMITTED',review_waiting_since=? WHERE id=102",utc(f.now().minusSeconds(10000)));
        CurrentAction a=action(project(null,null,true).getReport(),102); assertEquals(1100,a.getLatestReceipt().getEventId()); assertFalse(a.isNeedsSupplement());
    }
    @Test void independentReadOnlyRepeatableReadSnapshotAndFirstTableTimestamp() {
        TransactionTemplate outer=new TransactionTemplate(transactions);outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.execute(s->{ jdbc.queryForObject("SELECT COUNT(*) FROM patient",Integer.class); int outerId=observed.reads.get(0).connection; observed.reads.clear();
            CareExecutionReport r=project(null,null,true).getReport(); assertNotNull(r.getMetadata().getCurrentAsOf());
            assertTrue(observed.reads.get(0).sql.contains("patient")); assertTrue(observed.reads.get(0).sql.contains("snapshot_at"));
            Set<Integer> ids=new HashSet<>();for(CareExecutionReportAccessTest.Read read:observed.reads){assertEquals(Connection.TRANSACTION_REPEATABLE_READ,read.isolation);assertFalse(read.autoCommit);assertTrue(read.readOnly);assertNotEquals(outerId,read.connection);ids.add(read.connection);}
            assertEquals(1,ids.size()); return null;});
    }
    @Test void questionLimitBeforeLoadingText() {
        for(int i=0;i<200;i++) f.jdbc().update("INSERT INTO care_item(patient_id,user_id,kind,title,status,data_json) VALUES(1,7,'QUESTION','Synthetic','OPEN','{}')");
        assertLimit("QUESTIONS",()->project(null,null,true));
        assertFalse(observed.reads.stream().anyMatch(r->r.sql.matches("(?s).*SELECT[^;]*\\bdata_json\\b(?!\\)).*")));
        assertEquals(6,project(null,"actions_csv",true).getReport().getCurrentActions().size());
    }
    @Test void oversizedLegacyCellIsRejectedBeforeFetchingBody() {
        char[] chars=new char[8*1024*1024+1];Arrays.fill(chars,'x');
        f.jdbc().update("UPDATE care_item SET data_json=? WHERE id=601","{\"description\":\""+new String(chars)+"\"}");
        assertLimit("SOURCE_TEXT_BYTES",()->project(null,null,true));
        assertTrue(observed.reads.stream().anyMatch(r->r.sql.contains("LENGTH(")&&r.sql.contains("q.data_json")));
        assertFalse(observed.reads.stream().anyMatch(r->r.sql.startsWith("SELECT q.id,q.title")));
        assertEquals(9,project(null,"events_csv",true).getReport().getPeriodEvents().size());
    }
    @Test void individuallyAllowedLargeJsonCellsAreFetchedInBoundedBatches() {
        char[] chars=new char[5*1024*1024];Arrays.fill(chars,'x');String body="{\"unused\":\""+new String(chars)+"\",\"description\":\"Synthetic visible description\"}";
        f.jdbc().update("UPDATE care_item SET data_json=? WHERE id=601",body);
        f.jdbc().update("INSERT INTO care_item(id,patient_id,user_id,kind,title,status,data_json) VALUES(602,1,7,'QUESTION','Synthetic second question','OPEN',?)",body);
        CareExecutionReport r=project(null,null,true).getReport();assertEquals(2,r.getQuestions().size());
        assertEquals(2,observed.reads.stream().filter(x->x.sql.startsWith("SELECT q.id,q.title")).count());
    }
    @Test void includedTextBudgetAccumulatesAcrossBodyBatches() {
        char[] chars=new char[5*1024*1024];Arrays.fill(chars,'x');String body="{\"description\":\""+new String(chars)+"\"}";
        f.jdbc().update("UPDATE care_item SET data_json=? WHERE id=601",body);
        f.jdbc().update("INSERT INTO care_item(id,patient_id,user_id,kind,title,status,data_json) VALUES(602,1,7,'QUESTION','Synthetic second question','OPEN',?)",body);
        assertLimit("SOURCE_TEXT_BYTES",()->project(null,null,true));
        assertEquals(2,observed.reads.stream().filter(x->x.sql.startsWith("SELECT q.id,q.title")).count());
    }
    @Test void currentLimitDoesNotBlockIndependentEventCsv() {
        List<Object[]> actionArgs=new ArrayList<>();
        for(long plan=2000;plan<2020;plan++) {
            f.jdbc().update("INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions,workflow_version,lifecycle) VALUES(?,9,1,'Synthetic limit plan','Synthetic',1,'ACTIVE')",plan);
            f.jdbc().update("INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at,published_at) VALUES(?,?,1,'PUBLISHED','Synthetic','Synthetic','OTHER','{}',9,?,?,?)",plan,plan,utc(f.now()),utc(f.now()),utc(f.now()));
            f.jdbc().update("UPDATE doctor_care_plan SET current_revision_id=? WHERE id=?",plan,plan);
            for(int ordinal=1;ordinal<=50;ordinal++)actionArgs.add(new Object[]{plan,plan,ordinal,utc(f.now()),utc(f.now()),utc(f.now())});
        }
        f.jdbc().batchUpdate("INSERT INTO care_plan_action(plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,status,created_at,updated_at) VALUES(?,?,1,?,'Synthetic',?,7,'OPEN',?,?)",actionArgs);
        assertLimit("CURRENT_ACTIONS",()->project(null,null,true));
        assertFalse(observed.reads.stream().anyMatch(r->r.sql.startsWith("SELECT a.id,a.plan_id")));
        assertEquals(9,project(null,"events_csv",true).getReport().getPeriodEvents().size());
    }
    @Test void eventLimitDoesNotBlockIndependentActionCsv() {
        List<Object[]> args=new ArrayList<>();for(int i=0;i<4992;i++)args.add(new Object[]{utc(f.now())});
        f.jdbc().batchUpdate("INSERT INTO care_plan_event(patient_id,plan_id,revision_id,action_id,actor_id,actor_name,actor_role,event_type,recorded_at,payload_json) VALUES(1,100,101,105,7,'Synthetic','PATIENT','RECEIPT_SUBMITTED',?,'{}')",args);
        assertLimit("PERIOD_EVENTS",()->project(null,"events_csv",true));
        assertFalse(observed.reads.stream().anyMatch(r->r.sql.startsWith("SELECT e.id,e.patient_id")));
        assertEquals(6,project(null,"actions_csv",true).getReport().getCurrentActions().size());
    }
    @Test void boundariesUnknownQuestionStatusesAndAppendIdentityRemainExplicit() {
        CareExecutionReportTestData.seedEvent(f.jdbc(),1100,100,101,105L,"RECEIPT_SUBMITTED",Instant.parse("2026-09-04T00:00:00Z"),f.now().minusSeconds(10),"{}");
        CareExecutionReportTestData.seedEvent(f.jdbc(),1101,100,101,105L,"RECEIPT_SUBMITTED",Instant.parse("2026-10-04T00:00:00Z"),f.now().minusSeconds(10),"{}");
        CareExecutionReportTestData.seedEvent(f.jdbc(),1102,100,101,105L,"RECEIPT_SUBMITTED",Instant.parse("2026-09-03T23:59:59.999999Z"),f.now(),"{}");
        f.jdbc().update("UPDATE care_item SET status='LEGACY_UNKNOWN' WHERE id=601");
        CareExecutionReport r=project(null,null,true).getReport();
        assertTrue(r.getPeriodEvents().stream().anyMatch(e->e.getEventId()==1100));
        assertFalse(r.getPeriodEvents().stream().anyMatch(e->e.getEventId()==1101||e.getEventId()==1102));
        assertEquals("LEGACY_UNKNOWN",r.getQuestions().get(0).getStatus());
        EventSummary latest=action(r,105).getLatestReceipt();assertEquals(1102,latest.getEventId());
        assertEquals("Synthetic historical helper",latest.getActorName());assertEquals("FAMILY",latest.getActorRole());assertEquals("SPOUSE",latest.getActorRelation());assertEquals("ASSISTED",latest.getEntryMode());
    }
    @Test void corruptedPublishedRelationsNeverSilentlyDisappear() {
        f.jdbc().update("UPDATE care_plan_action SET patient_id=2 WHERE id=101");inconsistent();
        f.jdbc().update("UPDATE care_plan_action SET patient_id=1 WHERE id=101");
        f.jdbc().update("UPDATE care_plan_event SET revision_id=NULL WHERE id=1001");inconsistent();
        f.jdbc().update("UPDATE care_plan_event SET revision_id=100 WHERE id=1001");
        f.jdbc().update("UPDATE care_plan_event SET payload_json='{\"actionStatus\":\"CONFIRMED\"}' WHERE id=1001");inconsistent();
    }
    @Test void unavailableAssigneeDoesNotRewriteHistoricalActor() {
        f.jdbc().update("UPDATE care_plan_action SET assigned_user_id=8 WHERE id=101");
        CurrentAction a=action(project(null,null,true).getReport(),101);assertFalse(a.isAssigneeAvailable());
        assertEquals(8,a.getAssignedUserId());
    }
    @Test void sqlObserverSeesPlainAndPreparedExecutionsWithoutValues() {
        jdbc.queryForObject("SELECT COUNT(*) FROM patient",Integer.class);
        jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE id=?",Integer.class,1);
        assertEquals(2,observed.reads.size()); assertEquals("SELECT COUNT(*) FROM patient",observed.reads.get(0).sql);
        assertTrue(observed.reads.get(1).sql.endsWith("id=?"));
    }
    @Test void snapshotJdbcTimeoutUsesRemainingRequestBudget() {
        projector.project(OWNER,request(null,null),new Access(true),CareExecutionReportBudget.start(Duration.ofSeconds(5)));
        assertFalse(observed.reads.isEmpty());for(CareExecutionReportAccessTest.Read read:observed.reads)assertTrue(read.queryTimeout>0&&read.queryTimeout<=5,"query timeout "+read.queryTimeout);
    }
    @Test void mismatchedTransactionManagerFailsBeforePatientRead() {
        CareExecutionReportProjector bad=new CareExecutionReportProjector(jdbc,auth,new DataSourceTransactionManager(f.jdbc().getDataSource()));
        assertThrows(IllegalStateException.class,()->bad.project(OWNER,request(null,null),new Access(true),CareExecutionReportBudget.start(Duration.ofSeconds(30))));
        assertTrue(observed.reads.isEmpty());
    }
    @Test void queryTimeoutIsAStableReportFailure() {
        JdbcTemplate timedOut=new JdbcTemplate(observed) {
            @Override public <T> List<T> query(org.springframework.jdbc.core.PreparedStatementCreator creator,org.springframework.jdbc.core.RowMapper<T> mapper) {throw new org.springframework.dao.QueryTimeoutException("Synthetic timeout");}
        };
        CareExecutionReportProjector p=new CareExecutionReportProjector(timedOut,auth,transactions);
        assertEquals("REPORT_TIMEOUT",assertThrows(CareExecutionReportException.class,()->p.project(OWNER,request(null,null),new Access(true),CareExecutionReportBudget.start(Duration.ofSeconds(30)))).getErrorCode());
    }
    @Test void existingPatientTimeAndActionIndexesRemainEligible() {
        String eventPlan=f.jdbc().queryForObject("EXPLAIN SELECT id FROM care_plan_event WHERE patient_id=1 AND recorded_at>=TIMESTAMP '2026-09-04 00:00:00' AND recorded_at<TIMESTAMP '2026-10-04 00:00:00' ORDER BY recorded_at,id LIMIT 5001",String.class);
        String actionPlan=f.jdbc().queryForObject("EXPLAIN SELECT MAX(id) FROM care_plan_event WHERE action_id IN (101,102) GROUP BY action_id,event_type",String.class);
        String revisionPlan=f.jdbc().queryForObject("EXPLAIN SELECT id FROM care_plan_action WHERE revision_id=101 ORDER BY ordinal",String.class);
        assertTrue(eventPlan.contains("idx_care_plan_event_patient_time"),eventPlan);
        assertTrue(actionPlan.contains("idx_care_plan_event_action")||actionPlan.contains("fk_care_plan_event_action_INDEX"),actionPlan);
        assertTrue(revisionPlan.contains("uk_care_plan_action_ordinal")||revisionPlan.contains("fk_care_plan_action_revision_INDEX"),revisionPlan);
    }
    private CurrentAction action(CareExecutionReport r,long id){return r.getCurrentActions().stream().filter(a->a.getActionId()==id).findFirst().get();}
    private CareExecutionReportProjector.Projection project(Long plan,String format,boolean questions){return projectAs(OWNER,plan,format,questions);}
    private CareExecutionReportProjector.Projection projectAs(long actor,Long plan,String format,boolean questions){return projector.project(actor,request(plan,format),new Access(questions),CareExecutionReportBudget.start(Duration.ofSeconds(30)));}
    private Request request(Long plan,String format){return CareExecutionReportContracts.parse("{\"patientId\":1,\"timeZone\":\"UTC\",\"language\":\"en\""+(plan==null?"":",\"planId\":"+plan)+(format==null?"":",\"format\":\""+format+"\"")+"}",format!=null,f.now());}
    private void inconsistent(){assertEquals("REPORT_DATA_INCONSISTENT",assertThrows(CareExecutionReportException.class,()->project(null,null,true)).getErrorCode());}
    private void assertLimit(String kind,Runnable run){assertEquals(kind,assertThrows(CareExecutionReportException.class,run::run).getLimitKind());}
    private static java.time.LocalDateTime utc(Instant instant) { return java.time.LocalDateTime.ofInstant(instant,java.time.ZoneOffset.UTC); }

}
