package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real relational lifecycle tests; no outbound delivery or real patient data. */
class AlertWorkflowLifecycleTest {
    AlertWorkflowHarness h;
    @BeforeEach void setup() throws Exception {h=new AlertWorkflowHarness();}
    @Test void personalChainRequiresFreshAcceptanceHandlingFeedbackAndReasonedOwnerClosure() {
        long id=h.adopt();assertEquals("OPEN",h.row(id).get("workflowState"));
        h.assign(id,1);h.accept(id,1);h.command(1,id,"FOLLOW_UP",map("note","Called","occurredAt",h.time));
        h.submit(id,1);assertEquals("WAITING_FEEDBACK",h.row(id).get("workflowState"));h.feedback(id,1,"SELF");
        Map<String,Object> close=h.command(1,id,"CLOSE",map("reason","Handling recorded"));assertEquals("CLOSED",close.get("state"));
        assertEquals("RESOLVED",h.row(id).get("legacyStatus"));assertTrue(h.count("alert_notification")>0);
        h.command(1,id,"REOPEN",map("reason","Check again"));assertEquals(2,((Number)h.row(id).get("cycleNo")).intValue());
        assertNull(h.row(id).get("acceptedBy"));assertNull(h.row(id).get("currentHandlingEventId"));assertNull(h.row(id).get("currentFeedbackEventId"));
        h.denied(409,()->h.command(1,id,"CLOSE",map("reason","Old evidence")));
    }
    @Test void clinicalChainHasOnlyCurrentAssignedDoctorClosureAndNoDowngrade() {
        long id=h.adopt();h.command(1,id,"ESCALATE",map("reason","Clinical review requested","assigneeId",3));
        h.accept(id,3);h.submit(id,3);h.feedback(id,1,"SELF");
        h.denied(403,()->h.command(1,id,"CLOSE",map("reason","Owner cannot review")));
        h.denied(403,()->h.command(4,id,"CLOSE",map("reason","Nurse cannot review")));
        h.command(3,id,"RETURN",map("reason","Please clarify","targetState","WAITING_FEEDBACK"));h.feedback(id,1,"ASSISTED");
        h.command(3,id,"CLOSE",map("reason","Reviewed"));h.command(3,id,"REOPEN",map("reason","New concern"));
        assertEquals("CLINICAL",h.row(id).get("workflowMode"));
        h.denied(400,()->h.command(1,id,"ASSIGN",map("reason","Downgrade","assigneeId",1,"mode","PERSONAL")));
    }
    @Test void escalationClearsPersonalQualificationsAndWithoutSelectedClinicianLeavesRoutingBlocker() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.submit(id,1);h.feedback(id,1,"SELF");
        h.command(1,id,"ESCALATE",map("reason","Please review"));Map<String,Object> row=h.row(id);
        assertEquals("OPEN",row.get("workflowState"));assertEquals("CLINICAL",row.get("workflowMode"));assertNull(row.get("acceptedBy"));assertNull(row.get("currentFeedbackEventId"));assertNull(row.get("responsibleUserId"));
        assertTrue(h.detail(1,id).toString().contains("AWAITING_CLINICIAN"));h.denied(403,()->h.accept(id,1));
    }
    @Test void rejectionAndReassignmentClearAcceptanceButKeepHistory() {
        long id=h.adopt();h.assign(id,4);h.command(4,id,"REJECT_ASSIGNMENT",map("reason","Unavailable"));assertEquals(true,h.row(id).get("reassignmentRequired"));
        h.denied(409,()->h.accept(id,4));h.assign(id,1);h.accept(id,1);h.assign(id,4);assertNull(h.row(id).get("acceptedBy"));
    }
    @Test void difficultyReturnsToHandlingAndInformationRequestCanRequireNewHandling() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.submit(id,1);
        h.command(1,id,"FEEDBACK",map("note","Still difficult","occurredAt",h.time,"entryMode","SELF","outcome","DIFFICULTY","reason","Need help"));
        assertEquals("IN_PROGRESS",h.row(id).get("workflowState"));h.submit(id,1);h.feedback(id,1,"SELF");
        h.command(1,id,"RETURN",map("reason","Need outcome","targetState","IN_PROGRESS"));assertNull(h.row(id).get("currentHandlingEventId"));assertNull(h.row(id).get("currentFeedbackEventId"));
    }
    @Test void cancellationIsNotCompletionAndReopenClearsCycle() {long id=h.adopt();h.command(1,id,"CANCEL",map("reason","Mistaken entry"));assertEquals("CANCELLED",h.row(id).get("legacyStatus"));h.command(1,id,"REOPEN",map("reason","Restore"));assertEquals(2,((Number)h.row(id).get("cycleNo")).intValue());}
    @Test void familyAndNurseCannotClaimSelfAndDoctorCannotSilentlySupplyFeedback() {
        h.db.jdbc.update("UPDATE care_access_grant SET access_level='WRITE' WHERE grantee_user_id=2");long id=h.adopt();h.assign(id,4);h.accept(id,4);h.submit(id,4);
        h.denied(403,()->h.feedback(id,4,"SELF"));h.denied(403,()->h.feedback(id,2,"SELF"));h.denied(403,()->h.feedback(id,3,"SELF"));h.feedback(id,4,"ASSISTED");
        assertTrue(h.events(1,id).toString().contains("NURSE"));
    }
    @Test void acceptanceMustConfirmActualAssigneeAndDeadlineAndCannotEditIt() {
        long id=h.adopt();h.command(1,id,"ASSIGN",map("reason","Set target","assigneeId",4,"dueAt","2026-10-14T12:00:00Z"));
        h.denied(409,()->h.accept(id,4));h.denied(403,()->h.command(4,id,"ASSIGN",map("reason","Change deadline","assigneeId",4,"dueAt",null)));
        h.command(4,id,"ACCEPT",map("assigneeId",4,"dueAt","2026-10-14T14:00:00+02:00"));
    }
    @Test void clinicalDeadlineCanOnlyBeSetByDoctor() {long id=h.adopt();h.denied(403,()->h.command(1,id,"ESCALATE",map("reason","Review","assigneeId",3,"dueAt",h.time)));h.command(1,id,"ESCALATE",map("reason","Review","assigneeId",3));h.denied(403,()->h.command(1,id,"ASSIGN",map("reason","Target","assigneeId",3,"dueAt",h.time)));}
    @Test void boundariesRejectBlankLongFutureForgedAndUncheckableBodiesWithoutWriting() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);long events=h.count("alert_event");
        h.denied(400,()->h.command(1,id,"FOLLOW_UP",map("note"," ","occurredAt",h.time)));
        h.denied(400,()->h.command(1,id,"FOLLOW_UP",map("note",String.join("",Collections.nCopies(2001,"x")),"occurredAt",h.time)));
        h.denied(400,()->h.command(1,id,"FOLLOW_UP",map("note","Future","occurredAt","2099-01-01T00:00:00Z")));
        h.denied(400,()->h.command(1,id,"SUBMIT",map("note","No outcome","occurredAt",h.time)));
        h.denied(400,()->h.command(1,id,"FOLLOW_UP",map("note","Forged","occurredAt",h.time,"actorId",3)));
        h.denied(400,()->h.command(1,id,"CANCEL",map("reason",String.join("",Collections.nCopies(1001,"x")))));assertEquals(events,h.count("alert_event"));
    }
    @Test void sourceAdoptionIsExplicitDeduplicatedAndNormalNeedsManualLabel() {
        long id=h.adopt();assertEquals(id,h.adopt());assertEquals(1,h.count("alert_record"));
        h.db.jdbc.update("UPDATE health_measurement SET value_primary=5 WHERE id=202");
        h.denied(400,()->h.adopt(202,false));long normal=h.adopt(202,true);assertEquals(true,((Map<?,?>)h.row(normal).get("classification")).get("manualFollowUp"));
    }
    @Test void inaccessibleAndForeignSourcesAreIndistinguishableAndEvidenceIsModuleGated() {
        h.denied(403,()->h.adopt(303,false));h.denied(403,()->h.adopt(999,false));long id=h.adopt();h.assign(id,4);h.accept(id,4);
        h.denied(403,()->h.command(4,id,"SUBMIT",map("note","Report","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","MEDICAL_RECORD","sourceId",77)))));
        h.denied(403,()->h.command(4,id,"SUBMIT",map("note","Foreign","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","HEALTH_MEASUREMENT","sourceId",303)))));
    }
    @Test void identityRevocationBlocksReplayAndCurrentAssigneeActions() {
        h.db.jdbc.update("UPDATE care_access_grant SET access_level='WRITE' WHERE grantee_user_id=2");long id=h.adopt();h.assign(id,2);long version=h.version(id);String key=UUID.randomUUID().toString();Map<String,Object> b=map("assigneeId",2,"dueAt",null);
        Map<String,Object> first=h.command(2,id,"ACCEPT",b,key,version);assertEquals(first,h.command(2,id,"ACCEPT",b,key,version));
        h.db.jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=2");h.denied(403,()->h.command(2,id,"ACCEPT",b,key,version));
        assertTrue(h.detail(1,id).toString().contains("REASSIGNMENT_REQUIRED"));
    }
    @Test void sameKeyChangedContentConflictsAndStaleVersionDoesNotWrite() {
        long id=h.adopt();String key=UUID.randomUUID().toString();h.command(1,id,"ASSIGN",map("reason","Self","assigneeId",1),key,0);
        h.denied(409,()->h.command(1,id,"ASSIGN",map("reason","Changed","assigneeId",1),key,0));h.denied(409,()->h.command(1,id,"ASSIGN",map("reason","Stale","assigneeId",1),UUID.randomUUID().toString(),0));assertEquals(2,h.count("alert_event"));
    }
    @Test void evidenceBoundsAndRevokedAccessCannotBecomeCompletion() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);
        List<Map<String,Object>> tooMany=new ArrayList<>();for(int n=1;n<=6;n++)tooMany.add(map("sourceType","HEALTH_MEASUREMENT","sourceId",n));
        h.denied(400,()->h.command(1,id,"SUBMIT",map("note","Too many","occurredAt",h.time,"evidence",tooMany)));
        h.command(1,id,"SUBMIT",map("note","Retest","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","HEALTH_MEASUREMENT","sourceId",202))));h.feedback(id,1,"SELF");
        h.db.tx.execute(s->{h.db.jdbc.update("UPDATE alert_source_identity SET status='INVALIDATED' WHERE source_id=202");return null;});
        h.denied(409,()->h.command(1,id,"CLOSE",map("reason","Invalid source")));
    }
    @Test void personalOwnerCanWithdrawNonclinicalReferenceWithAccessAndMustKeepCheckableHandling() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);
        h.command(1,id,"SUBMIT",map("note","Retest","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","HEALTH_MEASUREMENT","sourceId",202))));h.feedback(id,1,"SELF");
        long ref=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence",Long.class);
        h.command(1,id,"RESOLVE_REFERENCE_CHANGE",map("reason","Wrong reference","evidenceId",ref,"resolution","WITHDRAW"));
        h.denied(409,()->h.command(1,id,"CLOSE",map("reason","No outcome remains")));
    }
    @Test void planDependencyNeedsDoctorResolutionAndConfirmedReplacementAndCannotBeOmittedByResubmit() {
        h.db.jdbc.update("INSERT INTO doctor_care_plan VALUES(90,10,91,'ACTIVE',1,'ACTIVE')");
        h.db.jdbc.update("INSERT INTO care_plan_action VALUES(92,90,10,91,'SUBMITTED',0),(93,90,10,91,'SUBMITTED',0)");
        long id=h.adopt();h.assign(id,1);h.accept(id,1);
        h.command(1,id,"SUBMIT",map("note","Plan receipt","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));h.feedback(id,1,"SELF");
        h.denied(409,()->h.command(1,id,"CLOSE",map("reason","Not confirmed")));
        long ref=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence",Long.class);
        h.denied(403,()->h.command(1,id,"RESOLVE_REFERENCE_CHANGE",map("reason","Ignore plan","evidenceId",ref,"resolution","WITHDRAW")));
        h.command(3,id,"RESOLVE_REFERENCE_CHANGE",map("reason","Replace action","evidenceId",ref,"resolution","REPLACE","replacement",map("sourceType","CARE_PLAN_ACTION","sourceId",93)));
        h.denied(409,()->h.command(1,id,"CLOSE",map("reason","Replacement not confirmed")));
        h.command(1,id,"RETURN",map("reason","Fresh outcome","targetState","IN_PROGRESS"));h.submit(id,1);h.feedback(id,1,"SELF");h.denied(409,()->h.command(1,id,"CLOSE",map("reason","Cannot omit dependency")));
    }
    @Test void eventsHideEntireRestrictedReferenceAndAssociatedProse() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.command(1,id,"SUBMIT",map("note","PRIVATE MEDICAL WORDS","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","MEDICAL_RECORD","sourceId",77))));
        String visible=h.events(2,id).toString();assertFalse(visible.contains("PRIVATE MEDICAL WORDS"));assertFalse(visible.contains("sourceId=77"));assertTrue(visible.contains("restricted=true"));
    }
    @Test void listAndEventsCursorsCannotCrossActorPatientOrQueue() {
        long id=h.adopt();h.adopt(202,true);
        Map<String,Object> list=h.query.list(1L,10L,"ALL",null,1);String cursor=(String)list.get("nextCursor");assertNotNull(cursor);
        h.denied(400,()->h.query.list(2L,10L,"ALL",cursor,1));h.denied(400,()->h.query.list(1L,10L,"ASSIGNED_TO_ME",cursor,1));
        h.denied(400,()->h.query.list(1L,10L,"ALL",null,101));h.denied(400,()->h.query.events(1L,id,"bad",1));
    }
    @Test void correctionReopensClosedCycleAndNeedsExplicitReviewFreshHandlingAndFeedback() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.submit(id,1);h.feedback(id,1,"SELF");h.command(1,id,"CLOSE",map("reason","Prior conclusion"));
        h.revise(101,false);Map<String,Object> row=h.row(id);assertEquals("OPEN",row.get("workflowState"));assertEquals(2,id(row.get("sourceRevision")));assertEquals(true,row.get("sourceReviewRequired"));assertEquals(2,((Number)row.get("cycleNo")).intValue());assertNull(row.get("currentHandlingEventId"));assertNull(row.get("currentFeedbackEventId"));
        h.denied(409,()->h.accept(id,1));h.command(1,id,"REVIEW_SOURCE_CHANGE",map("reason","Reviewed correction","sourceRevision",2));h.accept(id,1);h.submit(id,1);h.feedback(id,1,"SELF");h.command(1,id,"CLOSE",map("reason","New handling recorded"));assertTrue(h.events(1,id).toString().contains("Prior conclusion"));
    }
    @Test void correctingEvidenceAlsoReopensItsReferencingFollowupAndRequiresResolution() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.command(1,id,"SUBMIT",map("note","Retest","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","HEALTH_MEASUREMENT","sourceId",202))));h.feedback(id,1,"SELF");h.command(1,id,"CLOSE",map("reason","Recorded"));
        h.revise(202,true);assertEquals("OPEN",h.row(id).get("workflowState"));assertEquals(true,h.row(id).get("sourceReviewRequired"));
        h.denied(409,()->h.command(1,id,"REVIEW_SOURCE_CHANGE",map("reason","Cannot ignore changed evidence","sourceRevision",1)));
        long ref=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence",Long.class);h.command(1,id,"RESOLVE_REFERENCE_CHANGE",map("reason","Invalidated retest","evidenceId",ref,"resolution","WITHDRAW"));h.command(1,id,"REVIEW_SOURCE_CHANGE",map("reason","Reviewed","sourceRevision",1));h.accept(id,1);h.submit(id,1);h.feedback(id,1,"SELF");h.command(1,id,"CLOSE",map("reason","New handling recorded"));
    }
    @Test void sourceCorrectionPreservesClinicalModeAndOnlyDoctorMayReview() {long id=h.adopt();h.command(1,id,"ESCALATE",map("reason","Review","assigneeId",3));h.revise(101,false);assertEquals("CLINICAL",h.row(id).get("workflowMode"));h.denied(403,()->h.command(1,id,"REVIEW_SOURCE_CHANGE",map("reason","Owner","sourceRevision",2)));h.command(3,id,"REVIEW_SOURCE_CHANGE",map("reason","Doctor reviewed","sourceRevision",2));}
    @Test void correctedNormalSourceStillNeedsExplicitReviewAndNeverAutoCloses() {long id=h.adopt();h.revise(101,false);assertEquals("NORMAL",((Map<?,?>)h.row(id).get("classification")).get("status"));assertEquals("OPEN",h.row(id).get("workflowState"));assertEquals(true,h.row(id).get("sourceReviewRequired"));}
    @ParameterizedTest @ValueSource(strings={"OPEN","ACCEPTED","IN_PROGRESS","WAITING_FEEDBACK","READY_FOR_REVIEW"})
    void everyNonterminalStateCanBeReassignedCancelledOrExplicitlyEscalated(String state) {
        for(String operation:Arrays.asList("ASSIGN","CANCEL","ESCALATE")) {
            long id=h.adopt();h.toState(id,state);
            Map<String,Object> b=map("reason","New routing");if("ASSIGN".equals(operation))b.put("assigneeId",4);
            h.command(1,id,operation,b);assertEquals("CANCEL".equals(operation)?"CANCELLED":"OPEN",h.row(id).get("workflowState"));
            if(!"CANCEL".equals(operation))assertNull(h.row(id).get("acceptedBy"));
            h.db.jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) SELECT id+1000,patient_id,recorded_by,metric_type,value_primary,unit,measured_at FROM health_measurement WHERE id=?",h.nextSource);h.nextSource+=1000;
        }
    }
    @Test void clinicalAssigneeMustBeDoctorOrCurrentlyAssignedGrantedNurse() {long id=h.adopt();h.denied(403,()->h.command(1,id,"ESCALATE",map("reason","Family is not clinician","assigneeId",2)));h.db.jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=4");h.denied(403,()->h.command(1,id,"ESCALATE",map("reason","Nurse lacks grant","assigneeId",4)));}
    @Test void allTerminalMutationsExceptReopenAreRejected() {
        long id=h.adopt();h.command(1,id,"CANCEL",map("reason","Mistake"));
        h.denied(409,()->h.command(1,id,"ASSIGN",map("reason","Terminal","assigneeId",1)));h.denied(409,()->h.command(1,id,"ESCALATE",map("reason","Terminal")));h.denied(409,()->h.command(1,id,"CANCEL",map("reason","Again")));
    }
    @Test void clinicalResolutionCanTerminateCancelledPlanButOnlyWithIndependentModuleAuthority() {
        h.db.jdbc.update("INSERT INTO doctor_care_plan VALUES(90,10,91,'ACTIVE',1,'ACTIVE')");h.db.jdbc.update("INSERT INTO care_plan_action VALUES(92,90,10,91,'CONFIRMED',0)");long id=h.adopt();h.assign(id,1);h.accept(id,1);
        h.command(1,id,"SUBMIT",map("note","Plan","occurredAt",h.time,"contact",map("relationship","Patient","outcome","Discussed"),"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));h.feedback(id,1,"SELF");
        h.db.jdbc.update("UPDATE doctor_care_plan SET lifecycle='CANCELLED'");h.db.jdbc.update("UPDATE care_plan_action SET status='CANCELLED',lock_version=1");h.denied(409,()->h.command(1,id,"CLOSE",map("reason","Cancelled plan")));
        long ref=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence",Long.class);h.command(3,id,"RESOLVE_REFERENCE_CHANGE",map("reason","Dependency explicitly terminated","evidenceId",ref,"resolution","WITHDRAW"));h.command(1,id,"CLOSE",map("reason","Personal handling recorded"));
    }
    @Test void successfulReferenceResolutionAndClosureReplayRecheckCurrentEvidenceAuthority() {
        h.db.jdbc.update("UPDATE care_access_grant SET access_level='WRITE',visible_modules='MEASUREMENTS,MEDICAL' WHERE grantee_user_id=2");long id=h.adopt();h.assign(id,2);h.accept(id,2);
        Map<String,Object> body=map("note","Medical receipt","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","MEDICAL_RECORD","sourceId",77)));String key=UUID.randomUUID().toString();long version=h.version(id);h.command(2,id,"SUBMIT",body,key,version);
        h.db.jdbc.update("UPDATE care_access_grant SET visible_modules='MEASUREMENTS' WHERE grantee_user_id=2");h.denied(403,()->h.command(2,id,"SUBMIT",body,key,version));
    }
    @Test void notificationQueueDeduplicatesAndNeverBroadcastsToUnrelatedFamilyOrRevokedAssignee() {
        long id=h.adopt();h.assign(id,4);h.db.jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=4");h.command(1,id,"CANCEL",map("reason","Mistake"));
        long event=h.db.jdbc.queryForObject("SELECT MAX(id) FROM alert_event",Long.class);long count=h.count("alert_notification");h.db.tx.execute(tx->{h.queue.enqueue(event);return null;});assertEquals(count,h.count("alert_notification"));
        assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_notification WHERE event_id=? AND recipient_user_id IN (2,4,5,6)",Integer.class,event));assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_notification WHERE status='DELIVERED'",Integer.class));
    }
    @Test void selfMonitorAdoptionRetainsSourceSpecificThresholdsAndUnclassifiableRequiresManual() {
        for(String column:Arrays.asList("fasting_glucose_min","fasting_glucose_max","postmeal_glucose_min","postmeal_glucose_max"))h.db.jdbc.execute("ALTER TABLE patient_health_target ADD "+column+" DECIMAL(5,2)");
        h.db.jdbc.update("INSERT INTO bp_self_monitor_record(id,user_id,patient_id,record_date,record_time,measure_type,blood_glucose,bg_unit,measure_period) VALUES(401,1,10,'2026-10-13','12:00','BG',8,'mmol/L','Fasting'),(402,1,10,'2026-10-13','12:00','BG',8,'mmol/L','Unknown')");
        Map<String,Object> a=h.db.tx.execute(tx->h.service.adopt(1,"BP_SELF_MONITOR",401,"GLUCOSE",map("patientId",10,"manualFollowUp",false,"reason","Adopt"),UUID.randomUUID().toString()));assertEquals("SELF_MONITOR_GLUCOSE_FASTING",((Map<?,?>)h.row(id(a.get("alertId"))).get("classification")).get("policyKey"));
        h.denied(400,()->h.db.tx.execute(tx->h.service.adopt(1,"BP_SELF_MONITOR",402,"GLUCOSE",map("patientId",10,"manualFollowUp",false,"reason","Unknown"),UUID.randomUUID().toString())));
    }
    @Test void restrictedDependencyCannotLeakThroughClosureReasonOrUnlinkedReviewNarrative() {
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.command(1,id,"SUBMIT",map("note","PRIVATE REPORT DETAIL","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","MEDICAL_RECORD","sourceId",77))));h.feedback(id,1,"SELF");h.command(1,id,"CLOSE",map("reason","PRIVATE CLINICAL REASON"));
        assertFalse(h.detail(2,id).toString().contains("PRIVATE"));assertFalse(h.events(2,id).toString().contains("PRIVATE"));assertTrue(h.detail(1,id).toString().contains("PRIVATE CLINICAL REASON"));
    }
    @Test void incompleteSourceChangeCannotCommitEvenWhenCallerSwallowsItsFailure() {
        long id=h.adopt();assertThrows(IllegalStateException.class,()->h.db.tx.execute(status->{h.service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",101);h.db.jdbc.update("UPDATE health_measurement SET value_primary=6 WHERE id=101");return null;}));
        assertEquals(0,new java.math.BigDecimal("18").compareTo(h.db.jdbc.queryForObject("SELECT value_primary FROM health_measurement WHERE id=101",java.math.BigDecimal.class)));assertEquals(1,id(h.row(id).get("sourceRevision")));
    }
    @Test void replacingNonclinicalReferenceWithPlanDependencyStillRequiresDoctor() {
        h.db.jdbc.update("INSERT INTO doctor_care_plan VALUES(90,10,91,'ACTIVE',1,'ACTIVE')");h.db.jdbc.update("INSERT INTO care_plan_action VALUES(92,90,10,91,'CONFIRMED',0)");
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.command(1,id,"SUBMIT",map("note","Retest","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","HEALTH_MEASUREMENT","sourceId",202))));
        long ref=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence",Long.class);h.denied(403,()->h.command(1,id,"RESOLVE_REFERENCE_CHANGE",map("reason","Plan instead","evidenceId",ref,"resolution","REPLACE","replacement",map("sourceType","CARE_PLAN_ACTION","sourceId",92))));
    }
    @Test void correctionOfMeasurementInsidePlanReceiptReopensDependentAlert() {
        h.db.jdbc.update("INSERT INTO doctor_care_plan VALUES(90,10,91,'ACTIVE',1,'ACTIVE')");h.db.jdbc.update("INSERT INTO care_plan_action VALUES(92,90,10,91,'CONFIRMED',0)");h.db.jdbc.update("INSERT INTO care_plan_event(id,plan_id,action_id,event_type,patient_id) VALUES(94,90,92,'RECEIPT_SUBMITTED',10)");h.db.jdbc.update("INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(94,'MEASUREMENT',202)");
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.command(1,id,"SUBMIT",map("note","Plan receipt","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));h.feedback(id,1,"SELF");h.command(1,id,"CLOSE",map("reason","Recorded"));
        h.revise(202,false);assertEquals("OPEN",h.row(id).get("workflowState"));assertEquals(true,h.row(id).get("sourceReviewRequired"));assertEquals("REFERENCE_CHANGED",h.db.jdbc.queryForObject("SELECT dependency_state FROM alert_evidence",String.class));
    }
    @Test void changedPlanReceiptAcceptsGenuinelyFreshDoctorConfirmationOnSameActionOnly() {
        h.db.jdbc.update("INSERT INTO doctor_care_plan VALUES(90,10,91,'ACTIVE',1,'ACTIVE')");h.db.jdbc.update("INSERT INTO care_plan_action VALUES(92,90,10,91,'SUBMITTED',0)");h.db.jdbc.update("INSERT INTO care_plan_event(id,plan_id,action_id,event_type,patient_id) VALUES(94,90,92,'RECEIPT_SUBMITTED',10)");h.db.jdbc.update("INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(94,'MEASUREMENT',202)");
        h.db.tx.execute(tx->{Map<String,Object> source=h.repo.lockSource("HEALTH_MEASUREMENT",202);new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p).ensureTracked(1,source);return null;});
        long id=h.adopt();h.assign(id,1);h.accept(id,1);h.command(1,id,"SUBMIT",map("note","Plan receipt","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));h.revise(202,false);
        long ref=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence",Long.class);Map<String,Object> resolve=map("reason","Newly reviewed receipt","evidenceId",ref,"resolution","REPLACE","replacement",map("sourceType","CARE_PLAN_ACTION","sourceId",92));
        h.denied(409,()->h.command(3,id,"RESOLVE_REFERENCE_CHANGE",resolve));
        h.db.jdbc.execute("ALTER TABLE care_plan_event ADD payload_json LONGTEXT");h.db.jdbc.execute("ALTER TABLE care_plan_event ADD actor_role VARCHAR(50)");
        h.db.jdbc.update("UPDATE care_plan_action SET status='CONFIRMED',lock_version=1 WHERE id=92");h.db.jdbc.update("INSERT INTO care_plan_event(id,plan_id,action_id,event_type,payload_json,actor_role,patient_id) VALUES(95,90,92,'RECEIPT_CONFIRMED',?,'DOCTOR',10)",json(map("actionVersion",1)));
        h.command(3,id,"RESOLVE_REFERENCE_CHANGE",resolve);assertEquals(1,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_evidence WHERE dependency_state='VALID' AND dependency_version=1",Integer.class));
    }
    @Test void referenceCapabilitiesDistinguishPersonalOwnerFromRequiredClinicalReviewer() {
        h.db.jdbc.update("INSERT INTO doctor_care_plan VALUES(90,10,91,'ACTIVE',1,'ACTIVE')");h.db.jdbc.update("INSERT INTO care_plan_action VALUES(92,90,10,91,'SUBMITTED',0)");long id=h.adopt();h.assign(id,1);h.accept(id,1);h.command(1,id,"SUBMIT",map("note","Plan","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));
        assertFalse(((List<?>)h.detail(1,id).get("allowedActions")).contains("RESOLVE_REFERENCE_CHANGE"));assertTrue(((List<?>)h.detail(3,id).get("allowedActions")).contains("RESOLVE_REFERENCE_CHANGE"));
        Map<?,?> ref=(Map<?,?>)((List<?>)h.detail(1,id).get("evidence")).get(0);assertEquals(false,ref.get("canResolve"));
    }
    @Test void correctingAwayOriginalMetricPreservesIdentityAsUnclassifiablePendingReview() {
        long id=h.adopt();assertDoesNotThrow(()->h.db.tx.execute(tx->{AlertWorkflowService.SourceChange change=h.service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",101);Map<String,Object> old=change.getSource();h.db.jdbc.update("UPDATE health_measurement SET metric_type='CUSTOM' WHERE id=101");h.repo.appendSourceRevision(id(old.get("sourceIdentityId")),2,1,AlertWorkflowTestDatabase.NOW,map("metricType","CUSTOM","valuePrimary",18,"unit","unknown"),"Corrected metric context","ACTIVE");h.repo.advanceSourceRevision(id(old.get("sourceIdentityId")),0,2,"ACTIVE");return h.service.sourceChanged(1,change,"Corrected metric context");}));
        assertEquals("GLUCOSE",h.row(id).get("metric"));assertEquals("UNCLASSIFIABLE",((Map<?,?>)h.row(id).get("classification")).get("status"));assertEquals(true,h.row(id).get("sourceReviewRequired"));
    }
    @Test void assignmentReplayRechecksTheChosenAssigneeCurrentAuthority() {
        long id=h.adopt();String key=UUID.randomUUID().toString();Map<String,Object> body=map("reason","Nursing help","assigneeId",4);h.command(1,id,"ASSIGN",body,key,0);
        h.db.jdbc.update("UPDATE care_nurse_assignment SET status='REVOKED'");h.denied(403,()->h.command(1,id,"ASSIGN",body,key,0));assertEquals(2,h.count("alert_event"));
    }
    @Test void closureReplayRechecksIndependentCarePlanModule() {
        h.db.jdbc.update("INSERT INTO doctor_care_plan VALUES(90,10,91,'ACTIVE',1,'ACTIVE')");h.db.jdbc.update("INSERT INTO care_plan_action VALUES(92,90,10,91,'CONFIRMED',0)");long id=h.adopt();h.assign(id,1);h.accept(id,1);h.command(1,id,"SUBMIT",map("note","Plan","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));h.feedback(id,1,"SELF");String key=UUID.randomUUID().toString();long v=h.version(id);Map<String,Object> body=map("reason","Recorded");h.command(1,id,"CLOSE",body,key,v);
        AlertWorkflowAuthorizationService restricted=new AlertWorkflowAuthorizationService(h.db.jdbc,h.p,new CarePlanProperties(false,Clock.fixed(AlertWorkflowTestDatabase.NOW,ZoneOffset.UTC)));AlertWorkflowEvidenceService refs=new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,restricted,h.p);
        AlertWorkflowService service=new AlertWorkflowService(h.db.jdbc,h.repo,restricted,h.p,new AlertWorkflowEventStore(h.repo,restricted),h.queue,refs,new MeasurementAssessmentService(h.db.jdbc));
        h.denied(403,()->h.db.tx.execute(tx->service.command(1,id,"CLOSE",body,key,v)));
    }
    @Test void sourceChangedPlanConfirmationCannotReturnThroughReplacementChain() {
        long alert=changedPlanWithConfirmedAlternative();long first=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=92",Long.class);
        h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",replacement(first,93));long second=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=93",Long.class);
        long version=h.version(alert),events=h.count("alert_event"),commands=h.count("alert_command");
        h.denied(409,()->h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",replacement(second,92)));
        assertEquals(version,h.version(alert));assertEquals(events,h.count("alert_event"));assertEquals(commands,h.count("alert_command"));
        assertEquals(0,h.db.jdbc.queryForObject("SELECT COUNT(*) FROM alert_evidence WHERE source_id=92 AND dependency_state='VALID'",Integer.class));
    }
    @Test void sourceChangedPlanConfirmationCannotReturnThroughNewSubmission() {
        long alert=changedPlanWithConfirmedAlternative();long first=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=92",Long.class);
        h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",replacement(first,93));h.command(1,alert,"REVIEW_SOURCE_CHANGE",map("reason","Replacement reviewed","sourceRevision",1));h.accept(alert,1);
        long version=h.version(alert),events=h.count("alert_event"),commands=h.count("alert_command");
        h.denied(409,()->h.command(1,alert,"SUBMIT",map("note","Old confirmation again","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92)))));
        assertEquals(version,h.version(alert));assertEquals(events,h.count("alert_event"));assertEquals(commands,h.count("alert_command"));assertNull(h.row(alert).get("currentHandlingEventId"));
    }
    @Test void successfulAdoptionReplaysOriginalResultAfterInvalidationWithoutWrites() {
        String key=UUID.randomUUID().toString();Map<String,Object> body=map("patientId",10,"manualFollowUp",false,"reason","Original adoption");
        Map<String,Object> original=h.db.tx.execute(tx->h.service.adopt(1,"HEALTH_MEASUREMENT",101,"GLUCOSE",body,key));long alert=id(original.get("alertId"));h.revise(101,true);
        Map<String,Object> current=h.row(alert);Map<String,Long> counts=workflowCounts();
        Map<String,Object> replay=assertDoesNotThrow(()->h.db.tx.execute(tx->h.service.adopt(1,"HEALTH_MEASUREMENT",101,"GLUCOSE",body,key)));
        assertEquals(original,replay);assertEquals(current,h.row(alert));assertEquals(counts,workflowCounts());
        h.denied(409,()->h.db.tx.execute(tx->h.service.adopt(1,"HEALTH_MEASUREMENT",101,"GLUCOSE",body,UUID.randomUUID().toString())));assertEquals(counts,workflowCounts());
        h.db.jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");h.denied(403,()->h.db.tx.execute(tx->h.service.adopt(1,"HEALTH_MEASUREMENT",101,"GLUCOSE",body,key)));assertEquals(counts,workflowCounts());
    }
    @ParameterizedTest @ValueSource(strings={"REPLACE","SUBMIT"})
    void sourceChangedPlanCanReturnThroughEitherPathWithNewDoctorConfirmation(String path) {
        long alert=changedPlanWithConfirmedAlternative("SUBMITTED");long first=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=92",Long.class);
        h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",replacement(first,93));recordNewConfirmation(1,1);
        if("REPLACE".equals(path)){long second=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=93",Long.class);h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",replacement(second,92));}
        h.command(1,alert,"REVIEW_SOURCE_CHANGE",map("reason","Current receipt reviewed","sourceRevision",1));h.accept(alert,1);
        if("SUBMIT".equals(path))h.command(1,alert,"SUBMIT",map("note","Freshly confirmed receipt","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));else h.submit(alert,1);
        h.feedback(alert,1,"SELF");h.command(1,alert,"CLOSE",map("reason","Current evidence reviewed"));assertEquals("CLOSED",h.row(alert).get("workflowState"));
    }
    @Test void aHigherActionVersionWithoutMatchingNewDoctorConfirmationCannotClearTheObligation() {
        long alert=changedPlanWithConfirmedAlternative();long first=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=92",Long.class);h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",replacement(first,93));recordNewConfirmation(1,0);
        long second=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=93",Long.class);h.denied(409,()->h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",replacement(second,92)));
    }
    @Test void explicitTerminationStillAllowsClosureWithoutReusingTheChangedAction() {
        long alert=changedPlanWithConfirmedAlternative();long reference=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=92",Long.class);
        h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",map("reason","Explicitly end this dependency","evidenceId",reference,"resolution","WITHDRAW"));h.command(1,alert,"REVIEW_SOURCE_CHANGE",map("reason","Termination reviewed","sourceRevision",1));h.accept(alert,1);h.submit(alert,1);h.feedback(alert,1,"SELF");h.command(1,alert,"CLOSE",map("reason","Personal handling recorded"));assertEquals("CLOSED",h.row(alert).get("workflowState"));
    }
    @Test void completionRejectsAnAlreadyPersistedReattachmentWithOldConfirmation() {
        long alert=changedPlanWithConfirmedAlternative();long first=h.db.jdbc.queryForObject("SELECT id FROM alert_evidence WHERE source_id=92",Long.class);h.command(3,alert,"RESOLVE_REFERENCE_CHANGE",replacement(first,93));h.command(1,alert,"REVIEW_SOURCE_CHANGE",map("reason","Review replacement","sourceRevision",1));h.accept(alert,1);h.submit(alert,1);
        // Model a persisted pre-fix bypass through the real repository; current completion must fail closed too.
        long handling=id(h.row(alert).get("currentHandlingEventId"));h.db.tx.execute(tx->{h.repo.addEvidence(handling,"CARE_PLAN_ACTION",92,null,0L,"VALID");return null;});h.feedback(alert,1,"SELF");
        h.denied(409,()->h.command(1,alert,"CLOSE",map("reason","Old evidence cannot complete")));assertTrue(h.detail(1,alert).toString().contains("REFERENCE_CHANGED"));
    }
    private void recordNewConfirmation(long currentVersion,long confirmedVersion) {
        // Synthetic representation of the care-plan transaction's action update and actual doctor event.
        h.db.jdbc.execute("ALTER TABLE care_plan_event ADD payload_json LONGTEXT");h.db.jdbc.execute("ALTER TABLE care_plan_event ADD actor_role VARCHAR(50)");
        h.db.jdbc.update("UPDATE care_plan_action SET status='CONFIRMED',lock_version=? WHERE id=92",currentVersion);
        h.db.jdbc.update("INSERT INTO care_plan_event(id,plan_id,action_id,event_type,payload_json,actor_role,patient_id) VALUES(95,90,92,'RECEIPT_CONFIRMED',?,'DOCTOR',10)",json(map("actionVersion",confirmedVersion)));
    }
    private long changedPlanWithConfirmedAlternative(){return changedPlanWithConfirmedAlternative("CONFIRMED");}
    private long changedPlanWithConfirmedAlternative(String initialStatus) {
        h.db.jdbc.update("INSERT INTO doctor_care_plan VALUES(90,10,91,'ACTIVE',1,'ACTIVE')");h.db.jdbc.update("INSERT INTO care_plan_action VALUES(92,90,10,91,?,0),(93,90,10,91,'CONFIRMED',0)",initialStatus);
        h.db.jdbc.update("INSERT INTO care_plan_event(id,plan_id,action_id,event_type,patient_id) VALUES(94,90,92,'RECEIPT_SUBMITTED',10)");h.db.jdbc.update("INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(94,'MEASUREMENT',202)");
        long alert=h.adopt();h.assign(alert,1);h.accept(alert,1);h.command(1,alert,"SUBMIT",map("note","Plan A receipt","occurredAt",h.time,"evidence",Arrays.asList(map("sourceType","CARE_PLAN_ACTION","sourceId",92))));h.revise(202,false);return alert;
    }
    private Map<String,Object> replacement(long evidence,long action){return map("reason","Change reference","evidenceId",evidence,"resolution","REPLACE","replacement",map("sourceType","CARE_PLAN_ACTION","sourceId",action));}
    private Map<String,Long> workflowCounts(){Map<String,Long> counts=new LinkedHashMap<>();for(String table:Arrays.asList("alert_record","alert_event","alert_evidence","alert_command","alert_notification","alert_source_identity","alert_source_revision","alert_source_link"))counts.put(table,h.count(table));return counts;}
    @Test void featureGateDoesNotBorrowCarePlanAndAdminDoesNotGetWorkflowAccess() {long id=h.adopt();h.denied(403,()->h.detail(5,id));h.denied(403,()->h.detail(1,999));h.denied(403,()->h.detail(6,id));}
}

/** Synthetic relational harness. Real services, transactions, sources and evidence are exercised. */
class AlertWorkflowHarness {
    final AlertWorkflowTestDatabase db; final AlertWorkflowProperties p;final AlertWorkflowAuthorizationService auth;final AlertWorkflowRepository repo;
    final org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    final AlertWorkflowService service;final AlertWorkflowQueryService query;final AlertWorkflowNotificationQueue queue;long nextSource=101;final String time=AlertWorkflowTestDatabase.NOW.minusSeconds(60).toString();
    AlertWorkflowHarness() throws Exception {
        db=new AlertWorkflowTestDatabase(true);db.fixtures();db.sourceTables();db.workflowTables();
        db.jdbc.execute("CREATE TABLE notification_channel(id BIGINT PRIMARY KEY,user_id BIGINT,enabled INT,channel_type VARCHAR(40))");
        db.jdbc.update("INSERT INTO notification_channel VALUES(1,1,1,'WEBHOOK'),(2,3,1,'WEBHOOK')");
        db.jdbc.update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(101,10,1,'GLUCOSE',18,'mmol/L',?),(202,10,1,'GLUCOSE',8,'mmol/L',?),(303,20,6,'GLUCOSE',18,'mmol/L',?)",AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW,AlertWorkflowTestDatabase.NOW);
        db.jdbc.update("INSERT INTO medical_record(id,patient_id) VALUES(77,10)");
        p=new AlertWorkflowProperties(true,Clock.fixed(AlertWorkflowTestDatabase.NOW,ZoneOffset.UTC));auth=new AlertWorkflowAuthorizationService(db.jdbc,p,new CarePlanProperties(true,Clock.fixed(AlertWorkflowTestDatabase.NOW,ZoneOffset.UTC)));repo=new AlertWorkflowRepository(db.jdbc,p,auth);
        queue=new AlertWorkflowNotificationQueue(db.jdbc,auth,p);AlertWorkflowEventStore events=new AlertWorkflowEventStore(repo,auth);AlertWorkflowEvidenceService evidence=new AlertWorkflowEvidenceService(db.jdbc,repo,auth,p);
        org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader reader=new org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader(db.jdbc,db.manager,new org.familyhealthcare.service.medicalreview.MedicalReviewSchema(),new org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema(),false);
        medicalValidity=new org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection(db.jdbc,new org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema(),false,reader);
        org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions references=new org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions(db.jdbc,medicalValidity,db.manager);
        org.springframework.test.util.ReflectionTestUtils.setField(references,"alertAuthorization",auth);
        org.springframework.test.util.ReflectionTestUtils.setField(evidence,"medicalValidity",medicalValidity);
        org.springframework.test.util.ReflectionTestUtils.setField(evidence,"medicalReferences",references);
        service=new AlertWorkflowService(db.jdbc,repo,auth,p,events,queue,evidence,new MeasurementAssessmentService(db.jdbc));query=new AlertWorkflowQueryService(db.jdbc,repo,auth,p,evidence);
    }

    Map<String,Object> command(long actor,long id,String op,Map<String,Object>b){return command(actor,id,op,b,UUID.randomUUID().toString(),version(id));}
    Map<String,Object> command(long actor,long id,String op,Map<String,Object>b,String key,long version){return org.familyhealthcare.util.CurrentUserUtil.runAsUser(actor,()->db.tx.execute(s->service.command(actor,id,op,b,key,version)));}
    long adopt(){return adopt(nextSource,false);}long adopt(long source,boolean manual){return id(db.tx.execute(s->service.adopt(1L,"HEALTH_MEASUREMENT",source,"GLUCOSE",map("patientId",10,"manualFollowUp",manual,"reason","Follow up this reading"),UUID.randomUUID().toString())).get("alertId"));}
    Map<String,Object> row(long id){return repo.readAlert(1,id);}long version(long id){return id(row(id).get("lockVersion"));}
    void assign(long id,long actor){command(1,id,"ASSIGN",map("reason","Route work","assigneeId",actor));}void accept(long id,long actor){command(actor,id,"ACCEPT",map("assigneeId",actor,"dueAt",null));}
    void submit(long id,long actor){command(actor,id,"SUBMIT",map("note","Contact made","occurredAt",time,"contact",map("relationship","Patient","outcome","Instructions discussed")));}
    void feedback(long id,long actor,String entry){command(actor,id,"FEEDBACK",map("note","Handling completed","occurredAt",time,"entryMode",entry,"outcome","HANDLED"));}
    Map<String,Object> detail(long actor,long id){return query.detail(actor,id);}Map<String,Object> events(long actor,long id){return query.events(actor,id,null,100);}
    void toState(long id,String state) {if("OPEN".equals(state))return;assign(id,1);accept(id,1);if("ACCEPTED".equals(state))return;command(1,id,"FOLLOW_UP",map("note","Working","occurredAt",time));if("IN_PROGRESS".equals(state))return;submit(id,1);if("WAITING_FEEDBACK".equals(state))return;feedback(id,1,"SELF");}
    void revise(long sourceId,boolean invalidated) {db.tx.execute(tx->{AlertWorkflowService.SourceChange change=service.prepareSourceChange(1,10,"HEALTH_MEASUREMENT",sourceId);applyRevision(change,sourceId,invalidated);return null;});}
    void applyRevision(AlertWorkflowService.SourceChange change,long sourceId,boolean invalidated) {
        Map<String,Object> source=change.getSource();assertNotNull(source.get("sourceIdentityId"),"Prepare must capture the legacy source before mutation");long identity=id(source.get("sourceIdentityId")),revision=id(source.get("sourceRevision"))+1;
        db.jdbc.update("UPDATE health_measurement SET value_primary=5 WHERE id=?",sourceId);
        repo.appendSourceRevision(identity,revision,1,AlertWorkflowTestDatabase.NOW,map("metricType","GLUCOSE","valuePrimary",5,"unit","mmol/L"),"Corrected source",invalidated?"INVALIDATED":"ACTIVE");repo.advanceSourceRevision(identity,id(source.get("sourceVersion")),revision,invalidated?"INVALIDATED":"ACTIVE");service.sourceChanged(1,change,"Corrected source");
    }
    long count(String table){return db.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}void denied(int status,Runnable work){assertEquals(status,assertThrows(AlertWorkflowException.class,work::run).getStatus());}
}
