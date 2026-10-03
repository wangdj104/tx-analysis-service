package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.familyhealthcare.service.CarePlanLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic, real JDBC receipt state, identity, evidence, current access and outbox checks. */
class CarePlanReceiptTest {
    private CarePlanLifecycleTest h;
    private CarePlanActionService service;
    private CarePlanNotificationQueue queue;
    @BeforeEach void setup() throws Exception {h=new CarePlanLifecycleTest();h.setup();queue=h::enqueue;}
    @AfterEach void close() throws Exception {h.close();}

    @Test void proxyCannotForgeSelfOrActor() {
        h.grantFamily();grantNurse();long action=publishedAction();
        error(403,()->call("submit",FAMILY,action,receipt("SELF"),key(),1));
        error(403,()->call("submit",NURSE,action,receipt("SELF"),key(),1));
        error(403,()->call("submit",DOCTOR,action,receipt("SELF"),key(),1));
        for(String field:new String[]{"actorId","actorName","actorRole","patientId","status"}) {
            Map<String,Object>b=receipt("ASSISTED");b.put(field,field.equals("actorId")?OWNER:"forged");
            error(400,()->call("submit",FAMILY,action,b,key(),1));
        }
        Map<String,Object> submitted=call("submit",FAMILY,action,receipt("ASSISTED"),key(),1);
        assertEquals("SUBMITTED",submitted.get("actionStatus"));assertEquals(2L,submitted.get("version"));
        Map<String,Object>event=events(action,FAMILY).get(0);assertEquals("ASSISTED",event.get("entryMode"));
        assertEquals(FAMILY,event.get("actorId"));assertEquals("FAMILY",event.get("actorRole"));assertEquals("Synthetic User 8",event.get("actorName"));
        call("submit",OWNER,publishedAction(),receipt("SELF"),key(),1);
        assertEquals("SELF",h.f.jdbc().queryForObject("SELECT entry_mode FROM care_plan_event WHERE event_type='RECEIPT_SUBMITTED' ORDER BY id DESC LIMIT 1",String.class));
    }
    @Test void nurseCannotConfirmOrClose() {
        grantNurse();h.grantFamily();long action=publishedAction();call("submit",NURSE,action,receipt("ASSISTED"),key(),1);
        assertEquals("NURSE",events(action,OWNER).get(0).get("actorRole"));assertEquals("ASSISTED",events(action,OWNER).get(0).get("entryMode"));
        for(long actor:new long[]{NURSE,FAMILY,OWNER,ADMIN}) error(403,()->call("review",actor,action,map("decision","CONFIRM"),key(),2));
        long plan=plan(action);error(403,()->h.transition(NURSE,plan,"CLOSE",null,key(),1));
        call("followUp",NURSE,action,map("kind","DOCTOR_NOTIFIED","note","Synthetic contact"),key(),2);
        assertEquals("SUBMITTED",view(action,OWNER).get("status"));assertEquals(2L,view(action,OWNER).get("version"));
        assertNull(events(action,OWNER).get(1).get("entryMode"));
        call("review",DOCTOR,action,map("decision","CONFIRM","note","Reviewed feedback"),key(),3);
        assertEquals("CONFIRMED",view(action,OWNER).get("status"));assertNull(view(action,OWNER).get("reviewWaitingSince"));
        assertEquals("COMPLETED",h.transition(DOCTOR,plan,"CLOSE",null,key(),4).get("lifecycle"));
    }
    @Test void returnRetainsFirstSubmissionAndCreatesNewReceipt() {
        long action=publishedAction();Instant first=h.f.now();call("submit",OWNER,action,receipt("SELF"),key(),1);
        Map<String,Object>before=view(action,OWNER);Object originalFirstSubmittedAt=before.get("firstSubmittedAt");
        assertEquals(first.toString(),originalFirstSubmittedAt);assertFalse((Boolean)before.get("overdue"));
        h.f.advance(Duration.ofHours(2));
        error(400,()->call("review",DOCTOR,action,map("decision","RETURN","note"," "),key(),2));
        error(400,()->call("review",DOCTOR,action,map("decision","RETURN","note",repeat("x",1001)),key(),2));
        call("review",DOCTOR,action,map("decision","RETURN","note","  Need more detail  "),key(),2);
        Map<String,Object>returned=view(action,OWNER);assertEquals("OPEN",returned.get("status"));assertTrue((Boolean)returned.get("overdue"));
        assertEquals(originalFirstSubmittedAt,returned.get("firstSubmittedAt"));assertEquals(first.toString(),returned.get("latestSubmittedAt"));assertNull(returned.get("reviewWaitingSince"));
        h.f.advance(Duration.ofMinutes(5));Map<String,Object>b=receipt("SELF");b.put("note","Second synthetic receipt");
        call("submit",OWNER,action,b,key(),3);Map<String,Object>after=view(action,OWNER);
        assertEquals(originalFirstSubmittedAt,after.get("firstSubmittedAt"));assertEquals(h.f.now().toString(),after.get("latestSubmittedAt"));assertEquals(h.f.now().toString(),after.get("reviewWaitingSince"));
        assertFalse((Boolean)after.get("overdue"));assertEquals(Arrays.asList("RECEIPT_SUBMITTED","RECEIPT_RETURNED","RECEIPT_SUBMITTED"),eventTypes(action));
        assertEquals("Synthetic completion",events(action,OWNER).get(0).get("note"));assertEquals("Need more detail",events(action,OWNER).get(1).get("note"));
        assertEquals(4,h.count("care_plan_notification"));
    }
    @Test void evidenceIsScopedAtWriteAndRead() {
        h.grantFamily();records();long action=publishedAction();Map<String,Object>b=receipt("ASSISTED");b.put("evidence",Arrays.asList(ref("MEASUREMENT",101),ref("MEDICAL_RECORD",101)));
        error(403,()->call("submit",FAMILY,action,b,key(),1));
        h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN,MEASUREMENTS,MEDICAL' WHERE grantee_user_id=8");
        Map<String,Object>cross=receipt("ASSISTED");cross.put("evidence",Collections.singletonList(ref("MEASUREMENT",102)));error(403,()->call("submit",FAMILY,action,cross,key(),1));
        Map<String,Object>missing=receipt("ASSISTED");missing.put("evidence",Collections.singletonList(ref("MEASUREMENT",999)));error(403,()->call("submit",FAMILY,action,missing,key(),1));
        call("submit",FAMILY,action,b,key(),1);assertEquals(2,h.count("care_plan_evidence"));
        List<Map<String,Object>>visible=eventEvidence(action,FAMILY);assertEquals(false,visible.get(0).get("restricted"));assertEquals("WEIGHT",visible.get(0).get("title"));
        String payload=h.f.jdbc().queryForObject("SELECT payload_json FROM care_plan_event WHERE action_id=?",String.class,action);assertFalse(payload.contains("WEIGHT"));assertFalse(payload.contains("Synthetic secret"));
        h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN' WHERE grantee_user_id=8");
        for(Map<String,Object>e:eventEvidence(action,FAMILY)){assertEquals(true,e.get("restricted"));assertFalse(e.containsKey("title"));assertFalse(e.containsKey("detailLink"));}
        h.f.jdbc().update("UPDATE health_measurement SET patient_id=2 WHERE id=101");assertEquals(true,eventEvidence(action,OWNER).get(0).get("restricted"));
    }
    @Test void receiptBoundsAndOccurrenceTimeValidated() {
        long action=publishedAction();
        List<Map<String,Object>>invalid=new ArrayList<>();
        for(Object value:new Object[]{" ",repeat("😀",2001),42,null}){Map<String,Object>b=receipt("SELF");b.put("note",value);invalid.add(b);}
        for(Object value:new Object[]{"2026-10-03T05:00:00.000001Z","2026-10-03T04:00:00","2026-10-03T04:00:00.000000001Z","0999-12-31T23:59:59Z",42,null}){Map<String,Object>b=receipt("SELF");b.put("occurredAt",value);invalid.add(b);}
        for(Object value:new Object[]{"FORGED",42,null}){Map<String,Object>b=receipt("SELF");b.put("entryMode",value);invalid.add(b);}
        invalid.add(null);
        for(Map<String,Object>b:invalid)error(400,()->call("submit",OWNER,action,b,key(),1));
        error(400,()->call("submit",OWNER,action,receipt("SELF"),key(),-1));error(400,()->call("submit",OWNER,action,receipt("SELF"),"bad-key",1));
        Map<String,Object>b=receipt("SELF");b.put("note","　"+repeat("😀",2000)+"　");b.put("occurredAt","2026-10-03T06:00:00+02:00");
        call("submit",OWNER,action,b,key(),1);Map<String,Object>e=events(action,OWNER).get(0);
        assertEquals(2000,((String)e.get("note")).codePointCount(0,((String)e.get("note")).length()));assertEquals("2026-10-03T04:00:00Z",e.get("occurredAt"));assertEquals(h.f.now().toString(),e.get("recordedAt"));
        assertEquals(1,eventTypes(action).size());
    }
    @Test void evidenceBoundsDuplicatesAndTypesValidated() {
        records();long action=publishedAction();List<Map<String,Object>>refs=new ArrayList<>();for(long n=201;n<=206;n++){h.f.jdbc().update("INSERT INTO medical_record(id,patient_id,patient_name,record_type) VALUES(?,1,'Synthetic','OTHER')",n);refs.add(ref("MEDICAL_RECORD",n));}
        Map<String,Object>b=receipt("SELF");b.put("evidence",refs);error(400,()->call("submit",OWNER,action,b,key(),1));
        for(Object value:Arrays.asList(Arrays.asList(ref("MEASUREMENT",101),ref("MEASUREMENT",101)),Collections.singletonList(ref("UNKNOWN",101)),Collections.singletonList(map("sourceType","MEASUREMENT","sourceId",1.5)),Collections.singletonList(map("sourceType","MEASUREMENT","sourceId",101,"title","forged")),"bad")){
            Map<String,Object>invalid=receipt("SELF");invalid.put("evidence",value);error(400,()->call("submit",OWNER,action,invalid,key(),1));
        }
        b.put("evidence",new ArrayList<>(refs.subList(0,5)));call("submit",OWNER,action,b,key(),1);assertEquals(5,h.count("care_plan_evidence"));
    }
    @Test void helpAndFollowUpNeverClearDifficultyOrCompleteAnAction() {
        grantNurse();long action=publishedAction();
        error(400,()->call("help",OWNER,action,map("note"," "),key(),1));error(400,()->call("help",OWNER,action,map("note",repeat("x",1001)),key(),1));
        error(400,()->call("help",OWNER,action,map("note","Synthetic","patientId",OTHER_PATIENT),key(),1));
        call("help",OWNER,action,map("note","　"+repeat("😀",1000)+"　"),key(),1);assertEquals("NEEDS_HELP",view(action,OWNER).get("status"));
        error(409,()->call("help",OWNER,action,map("note","More"),key(),2));
        for(long actor:new long[]{OWNER,FAMILY,ADMIN})error(403,()->call("followUp",actor,action,map("kind","CONTACTED","note","Synthetic"),key(),2));
        for(Map<String,Object>invalid:Arrays.asList(map("kind","UNKNOWN","note","Synthetic"),map("kind","CONTACTED","note"," "),map("kind","CONTACTED","note",repeat("x",1001)),map("kind","CONTACTED","note","Synthetic","entryMode","SELF")))error(400,()->call("followUp",NURSE,action,invalid,key(),2));
        long version=2;for(String kind:Arrays.asList("CONTACTED","AWAITING_INFORMATION","DOCTOR_NOTIFIED"))call("followUp",NURSE,action,map("kind",kind,"note",repeat("😀",1000)),key(),version++);
        call("followUp",DOCTOR,action,map("kind","CONTACTED","note","Doctor management note"),key(),version++);
        Map<String,Object>after=view(action,OWNER);assertEquals("NEEDS_HELP",after.get("status"));assertNull(after.get("firstSubmittedAt"));
        assertEquals(5,eventTypes(action).size());assertEquals("HELP_REQUESTED",eventTypes(action).get(0));for(Map<String,Object>e:events(action,OWNER))assertNull(e.get("entryMode"));
        call("submit",OWNER,action,receipt("SELF"),key(),version);assertEquals("SUBMITTED",view(action,OWNER).get("status"));
    }
    @Test void terminalAndWrongVersionActionsFailWithoutEvents() {
        long action=publishedAction();error(409,()->call("submit",OWNER,action,receipt("SELF"),key(),2));error(409,()->call("review",DOCTOR,action,map("decision","CONFIRM"),key(),1));
        call("submit",OWNER,action,receipt("SELF"),key(),1);
        error(409,()->call("submit",OWNER,action,receipt("SELF"),key(),2));error(409,()->call("help",OWNER,action,map("note","Synthetic"),key(),2));
        for(Map<String,Object>b:Arrays.asList(map("decision","UNKNOWN"),map("decision","RETURN"),map("decision","CONFIRM","note"," "),map("decision","CONFIRM","actorId",DOCTOR)))error(400,()->call("review",DOCTOR,action,b,key(),2));
        call("review",DOCTOR,action,map("decision","CONFIRM","note",repeat("😀",2000)),key(),2);
        for(String operation:new String[]{"submit","help","review","followUp"}) {
            Map<String,Object>b=operation.equals("submit")?receipt("SELF"):operation.equals("help")?map("note","Synthetic"):operation.equals("review")?map("decision","RETURN","note","Synthetic"):map("kind","CONTACTED","note","Synthetic");
            error(409,()->call(operation,operation.equals("submit")||operation.equals("help")?OWNER:DOCTOR,action,b,key(),3));
        }
        assertEquals(Arrays.asList("RECEIPT_SUBMITTED","RECEIPT_CONFIRMED"),eventTypes(action));assertEquals(3,h.count("care_plan_notification"));
    }
    @Test void matchingReplayKeepsOriginalResultAndRequiresCurrentOperationAuthority() {
        h.grantFamily();long action=publishedAction();String k=key();Map<String,Object>b=receipt("ASSISTED");Map<String,Object>first=call("submit",FAMILY,action,b,k,1);
        Map<String,Object>equivalent=receipt("ASSISTED");equivalent.put("note","  Synthetic completion  ");equivalent.put("occurredAt","2026-10-03T06:00:00+02:00");
        assertEquals(first,call("submit",FAMILY,action,equivalent,k.toUpperCase(Locale.ROOT),1));
        call("review",DOCTOR,action,map("decision","CONFIRM"),key(),2);assertEquals(first,call("submit",FAMILY,action,b,k,1));
        Map<String,Object>changed=receipt("ASSISTED");changed.put("note","Different");error(409,()->call("submit",FAMILY,action,changed,k,1));error(409,()->call("submit",FAMILY,action,b,k,2));
        error(409,()->call("help",FAMILY,action,map("note","Synthetic completion"),k,1));
        h.f.jdbc().update("UPDATE care_access_grant SET access_level='READ' WHERE grantee_user_id=8");error(403,()->call("submit",FAMILY,action,b,k,1));
        assertEquals("CONFIRMED",view(action,FAMILY).get("status"));h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");error(403,()->view(action,FAMILY));
        assertEquals(2,eventTypes(action).size());assertEquals(4,h.count("care_plan_command"));assertEquals(3,h.count("care_plan_notification"));
    }
    @Test void replayRechecksEvidencePermissionAndAccountStatus() {
        h.grantFamily();records();h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN,MEASUREMENTS' WHERE grantee_user_id=8");
        long action=publishedAction();String k=key();Map<String,Object>b=receipt("ASSISTED");b.put("evidence",Collections.singletonList(ref("MEASUREMENT",101)));call("submit",FAMILY,action,b,k,1);
        h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN' WHERE grantee_user_id=8");error(403,()->call("submit",FAMILY,action,b,k,1));
        h.f.jdbc().update("UPDATE sys_user SET status=0 WHERE id=8");error(403,()->call("submit",FAMILY,action,b,k,1));assertEquals(1,eventTypes(action).size());
    }
    @Test void eachNurseAccessRequirementAppliesToWritesAndHistoricalReads() {
        grantNurse();long action=publishedAction();String k=key();Map<String,Object>b=receipt("ASSISTED");call("submit",NURSE,action,b,k,1);
        h.f.jdbc().update("UPDATE care_nurse_assignment SET status='REVOKED'");error(403,()->call("submit",NURSE,action,b,k,1));error(403,()->view(action,NURSE));
        h.f.jdbc().update("UPDATE care_nurse_assignment SET status='ACTIVE',expires_at=?",Timestamp.from(h.f.now()));error(403,()->view(action,NURSE));
        h.f.jdbc().update("UPDATE care_nurse_assignment SET expires_at=NULL");h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='MEDICAL' WHERE grantee_user_id=10");error(403,()->view(action,NURSE));
        h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN' WHERE grantee_user_id=10");h.f.jdbc().update("DELETE FROM sys_user_role WHERE user_id=10");error(403,()->call("submit",NURSE,action,b,k,1));error(403,()->view(action,NURSE));
    }
    @Test void failedQueueAndCaughtFailuresCannotCommitActionEventOrCommand() {
        long action=publishedAction();String k=key();h.failQueue=true;
        assertThrows(IllegalStateException.class,()->call("submit",OWNER,action,receipt("SELF"),k,1));assertPristine(action);
        assertThrows(RuntimeException.class,()->h.tx.execute(s->{assertThrows(IllegalStateException.class,()->invoke("submit",OWNER,action,receipt("SELF"),k,1));return null;}));assertPristine(action);
        h.failQueue=false;call("submit",OWNER,action,receipt("SELF"),k,1);assertEquals(2,h.count("care_plan_notification"));
    }
    @Test void caughtAuthorizationFailureAfterEnqueueCannotCommitReceipt() {
        h.grantFamily();long action=publishedAction();queue=event->{h.enqueue(event);independent("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");};
        assertThrows(RuntimeException.class,()->h.tx.execute(s->{error(403,()->invoke("submit",FAMILY,action,receipt("ASSISTED"),key(),1));return null;}));
        assertEquals("REVOKED",h.f.jdbc().queryForObject("SELECT status FROM care_access_grant WHERE grantee_user_id=8",String.class));assertPristine(action);
    }
    @Test void caughtEvidenceRevocationAfterEnqueueCannotCommitReceipt() {
        h.grantFamily();records();h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN,MEASUREMENTS' WHERE grantee_user_id=8");long action=publishedAction();
        queue=event->{h.enqueue(event);independent("UPDATE care_access_grant SET visible_modules='CARE_PLAN' WHERE grantee_user_id=8");};Map<String,Object>b=receipt("ASSISTED");b.put("evidence",Collections.singletonList(ref("MEASUREMENT",101)));
        assertThrows(RuntimeException.class,()->h.tx.execute(s->{error(403,()->invoke("submit",FAMILY,action,b,key(),1));return null;}));assertPristine(action);assertEquals(0,h.count("care_plan_evidence"));
    }
    @Test void completedReceiptRollsBackWithOuterFailure() {
        long action=publishedAction();String k=key();assertThrows(IllegalStateException.class,()->h.tx.execute(s->{invoke("submit",OWNER,action,receipt("SELF"),k,1);throw new IllegalStateException("Synthetic outer rollback");}));assertPristine(action);
        call("submit",OWNER,action,receipt("SELF"),k,1);assertEquals("SUBMITTED",view(action,OWNER).get("status"));
    }
    @Test void confirmedHistoryRemainsImmutableAcrossRevisionAndCancellation() {
        long action=publishedAction();long plan=plan(action),old=revision(action);call("submit",OWNER,action,receipt("SELF"),key(),1);call("review",DOCTOR,action,map("decision","CONFIRM"),key(),2);
        Object first=view(action,OWNER).get("firstSubmittedAt");Map<String,Object>r=h.tx.execute(s->h.service.createRevision(DOCTOR,plan,key(),3));long next=num(r.get("draftRevisionId"));h.publish(DOCTOR,plan,next,h.confirmation(plan),key(),4);
        Map<String,Object>historic=actions(h.query.revision(OWNER,plan,old)).get(0);assertEquals("CONFIRMED",historic.get("status"));assertEquals(first,historic.get("firstSubmittedAt"));assertEquals(2,((List<?>)historic.get("events")).size());assertTrue(((List<?>)historic.get("allowedActions")).isEmpty());assertEquals(Collections.emptyList(),historic.get("allowedEntryModes"));
        long current=h.f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE revision_id=?",Long.class,next);assertNull(view(current,OWNER).get("firstSubmittedAt"));
        h.transition(DOCTOR,plan,"CANCEL","Synthetic",key(),5);error(409,()->call("submit",OWNER,current,receipt("SELF"),key(),2));error(409,()->call("submit",OWNER,action,receipt("SELF"),key(),3));
        assertEquals("CONFIRMED",actions(h.query.revision(OWNER,plan,old)).get(0).get("status"));assertEquals(Arrays.asList("RECEIPT_SUBMITTED","RECEIPT_CONFIRMED"),eventTypes(action));
    }
    @Test void allowedActionsProjectCurrentCapabilitiesWithoutGrantingAuthority() {
        h.grantFamily();grantNurse();Map<String,Object>d=h.create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));
        assertEquals(Arrays.asList("SAVE_DRAFT","PUBLISH_PLAN"),d.get("allowedActions"));assertEquals(Collections.emptyList(),actions(d).get(0).get("allowedActions"));
        h.publish(DOCTOR,plan,rev,map(),key(),0);long action=h.f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,plan);
        assertEquals(Arrays.asList("CREATE_REVISION","CANCEL_PLAN"),h.query.detail(DOCTOR,plan).get("allowedActions"));
        assertEquals(Arrays.asList("SUBMIT_RECEIPT","REQUEST_HELP"),view(action,OWNER).get("allowedActions"));assertEquals(Arrays.asList("SUBMIT_RECEIPT","REQUEST_HELP","FOLLOW_UP"),view(action,NURSE).get("allowedActions"));
        h.f.jdbc().update("UPDATE care_access_grant SET access_level='READ' WHERE grantee_user_id=8");assertEquals(Collections.emptyList(),view(action,FAMILY).get("allowedActions"));error(403,()->call("submit",FAMILY,action,receipt("ASSISTED"),key(),1));
        call("submit",OWNER,action,receipt("SELF"),key(),1);assertEquals(Collections.emptyList(),view(action,OWNER).get("allowedActions"));assertEquals(Arrays.asList("FOLLOW_UP","REVIEW_RECEIPT"),view(action,DOCTOR).get("allowedActions"));assertEquals(Collections.singletonList("FOLLOW_UP"),view(action,NURSE).get("allowedActions"));
        call("review",DOCTOR,action,map("decision","CONFIRM"),key(),2);assertEquals(Collections.emptyList(),view(action,DOCTOR).get("allowedActions"));assertEquals(Arrays.asList("CREATE_REVISION","CANCEL_PLAN","CLOSE_PLAN"),h.query.detail(DOCTOR,plan).get("allowedActions"));
        h.transition(DOCTOR,plan,"CLOSE",null,key(),3);assertEquals(Collections.emptyList(),h.query.detail(DOCTOR,plan).get("allowedActions"));
    }
    @Test void draftCommandReplayKeepsOriginalSnapshotWithoutStaleControls() {
        String k=key();Map<String,Object>d=h.tx.execute(s->h.service.createDraft(DOCTOR,body(1),k));long plan=id(d),rev=num(d.get("draftRevisionId"));
        h.publish(DOCTOR,plan,rev,map(),key(),0);Map<String,Object>replay=h.tx.execute(s->h.service.createDraft(DOCTOR,body(1),k));
        assertEquals(d.get("version"),replay.get("version"));assertEquals("DRAFT",replay.get("revisionStatus"));
        assertEquals(Collections.emptyList(),replay.get("allowedActions"));assertEquals(Collections.emptyList(),actions(replay).get(0).get("allowedActions"));assertEquals(Collections.emptyList(),actions(replay).get(0).get("allowedEntryModes"));
        assertEquals(Arrays.asList("CREATE_REVISION","CANCEL_PLAN"),h.query.detail(DOCTOR,plan).get("allowedActions"));
        Map<String,Object>next=h.tx.execute(tx->h.service.createRevision(DOCTOR,plan,key(),1));long nextDraft=num(next.get("draftRevisionId"));assertNotEquals(rev,nextDraft);
        Map<String,Object>different=body(1);different.put("title","Different synthetic draft");different.put("instructions","Different instructions");actions(different).get(0).put("instruction","Different synthetic action");
        h.tx.execute(tx->h.service.saveDraft(DOCTOR,plan,nextDraft,different,key(),2));
        Map<String,Object>oldReplay=h.tx.execute(tx->h.service.createDraft(DOCTOR,body(1),k));assertEquals(rev,oldReplay.get("draftRevisionId"));
        for(String field:Arrays.asList("revisionId","revisionStatus","version","title","instructions"))assertEquals(d.get(field),oldReplay.get(field),field);
        assertEquals(actions(d).get(0).get("instruction"),actions(oldReplay).get(0).get("instruction"));
        assertEquals(Collections.emptyList(),oldReplay.get("allowedActions"));assertEquals(Collections.emptyList(),actions(oldReplay).get(0).get("allowedEntryModes"));
        Map<String,Object>currentDraft=h.query.revision(DOCTOR,plan,nextDraft);assertEquals("Different synthetic draft",currentDraft.get("title"));
        assertEquals(Arrays.asList("SAVE_DRAFT","PUBLISH_PLAN"),currentDraft.get("allowedActions"));
    }
    @Test void allowedEntryModesUseActualOwnerAndCurrentSubmitAuthority() {
        h.grantFamily();grantNurse();Map<String,Object>d=h.create(body(1));long plan=id(d),revision=num(d.get("draftRevisionId"));
        assertEquals(Collections.emptyList(),actions(d).get(0).get("allowedEntryModes"));
        h.publish(DOCTOR,plan,revision,map(),key(),0);long action=h.f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,plan);
        assertEquals(Arrays.asList("ASSISTED","SELF"),view(action,OWNER).get("allowedEntryModes"));
        for(long actor:new long[]{FAMILY,NURSE,DOCTOR})assertEquals(Collections.singletonList("ASSISTED"),view(action,actor).get("allowedEntryModes"));
        h.f.jdbc().update("UPDATE care_access_grant SET access_level='READ' WHERE grantee_user_id=8");assertEquals(Collections.emptyList(),view(action,FAMILY).get("allowedEntryModes"));
        h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");error(403,()->view(action,FAMILY));
        // The clinical role label is deliberately unchanged: actual account ownership governs SELF.
        h.f.jdbc().update("UPDATE patient SET user_id=9 WHERE id=1");
        assertEquals(Arrays.asList("ASSISTED","SELF"),view(action,DOCTOR).get("allowedEntryModes"));
        call("submit",DOCTOR,action,receipt("SELF"),key(),1);assertEquals(Collections.emptyList(),view(action,DOCTOR).get("allowedEntryModes"));
        assertEquals("DOCTOR",events(action,DOCTOR).get(0).get("actorRole"));assertEquals("SELF",events(action,DOCTOR).get(0).get("entryMode"));
        h.transition(DOCTOR,plan,"CANCEL","Synthetic",key(),2);assertEquals(Collections.emptyList(),view(action,DOCTOR).get("allowedEntryModes"));
    }
    @Test void eventStoreIndependentlyChecksReceiptAuthorityIdentityAndBounds() {
        h.grantFamily();grantNurse();long action=publishedAction(),plan=plan(action),revision=revision(action);CarePlanEventStore store=new CarePlanEventStore(h.f.jdbc(),h.auth,h.properties);
        error(403,()->h.tx.execute(s->store.append(PATIENT,plan,revision,action,FAMILY,"RECEIPT_CONFIRMED",map("note","Forged"))));
        error(403,()->h.tx.execute(s->store.append(PATIENT,plan,revision,action,NURSE,"RECEIPT_RETURNED",map("note","Forged"))));
        error(403,()->h.tx.execute(s->store.append(PATIENT,plan,revision,action,FAMILY,"RECEIPT_SUBMITTED",receipt("SELF"))));
        h.f.jdbc().update("UPDATE care_access_grant SET access_level='READ' WHERE grantee_user_id=8");error(403,()->h.tx.execute(s->store.append(PATIENT,plan,revision,action,FAMILY,"HELP_REQUESTED",map("note","Forged"))));
        error(400,()->h.tx.execute(s->store.append(PATIENT,plan,revision,action,OWNER,"HELP_REQUESTED",map("note",repeat("x",1001)))));
        error(400,()->h.tx.execute(s->store.append(PATIENT,plan,revision,action,DOCTOR,"RECEIPT_RETURNED",map("note"," "))));
        Map<String,Object>future=receipt("SELF");future.put("occurredAt",h.f.now().plusSeconds(1).toString());error(400,()->h.tx.execute(s->store.append(PATIENT,plan,revision,action,OWNER,"RECEIPT_SUBMITTED",future)));
        assertTrue(events(action,OWNER).isEmpty());
    }
    @Test void actionMutationsRequireEffectiveReadCommittedBeforeAnySql() throws Exception {
        Class<?>type=actionType();for(String op:Arrays.asList("submit","help","followUp","review"))assertEquals(org.springframework.transaction.annotation.Isolation.READ_COMMITTED,type.getMethod(op,long.class,long.class,Map.class,String.class,long.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class).isolation());
        ProbeDataSource source=new ProbeDataSource(h.f.jdbc().getDataSource());JdbcTemplate observed=new JdbcTemplate(source);
        Object observedService=newActionService(observed,new CarePlanAuthorizationService(observed,h.properties),queue);
        TransactionTemplate outer=new TransactionTemplate(new DataSourceTransactionManager(source));
        for(int isolation:new int[]{TransactionDefinition.ISOLATION_DEFAULT,TransactionDefinition.ISOLATION_REPEATABLE_READ})for(String op:Arrays.asList("submit","help","followUp","review")){
            outer.setIsolationLevel(isolation);source.sql.clear();IllegalStateException failure=assertThrows(IllegalStateException.class,()->outer.execute(s->invokeOn(observedService,op,OWNER,999,receipt("SELF"),key(),1)));
            assertTrue(failure.getMessage().contains("READ_COMMITTED"));assertTrue(source.sql.isEmpty());
        }
        assertEquals(0,h.count("care_plan_command"));
    }
    @org.springframework.context.annotation.Configuration @org.springframework.transaction.annotation.EnableTransactionManagement static class TransactionConfiguration {}
    @Test void actionSpringProxyUsesReadCommittedAndRequiresTransactionalQueue() throws Exception {
        ProbeDataSource source=new ProbeDataSource(h.f.jdbc().getDataSource());JdbcTemplate observed=new JdbcTemplate(source);Class<?>type=actionType();
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("enabled",Collections.singletonMap("care-plan.enabled",true)));
            context.registerBean(JdbcTemplate.class,()->observed);context.registerBean(CarePlanProperties.class,()->h.properties);context.registerBean(CarePlanNotificationQueue.class,()->event->{observed.update(connection->{PreparedStatement ps=connection.prepareStatement("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,status,created_at,updated_at) SELECT id,patient_id,7,?,'QUEUED',?,? FROM care_plan_event WHERE id=?");ps.setString(1,"synthetic-"+event);Calendar utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));ps.setTimestamp(2,Timestamp.from(h.f.now()),utc);ps.setTimestamp(3,Timestamp.from(h.f.now()),utc);ps.setLong(4,event);return ps;});});
            context.registerBean("transactionManager",org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));context.register(TransactionConfiguration.class,CarePlanAuthorizationService.class,CarePlanCommandStore.class,CarePlanEventStore.class,type);context.refresh();
            Object proxied=context.getBean(type);assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(proxied));long action=publishedAction();
            invokeOn(proxied,"submit",OWNER,action,receipt("SELF"),key(),1);invokeOn(proxied,"review",DOCTOR,action,map("decision","CONFIRM"),key(),2);
            assertFalse(source.mutationIsolation.isEmpty());for(int level:source.mutationIsolation)assertEquals(Connection.TRANSACTION_READ_COMMITTED,level);
            try(Connection connection=source.getConnection()){assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());}
        }
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("enabled",Collections.singletonMap("care-plan.enabled",true)));context.registerBean(JdbcTemplate.class,()->h.f.jdbc());context.registerBean(CarePlanProperties.class,()->h.properties);
            context.register(CarePlanAuthorizationService.class,CarePlanCommandStore.class,CarePlanEventStore.class,type);assertTrue(assertThrows(RuntimeException.class,context::refresh).toString().contains("CarePlanNotificationQueue"));
        }
    }
    @Test void inaccessibleActionIdsAndBodyPatientIdsNeverRevealOrChoosePatient() {
        long action=publishedAction();for(long actor:new long[]{FAMILY,NURSE,ADMIN,999})error(403,()->call("submit",actor,action,receipt("ASSISTED"),key(),1));error(403,()->call("submit",OWNER,999,receipt("SELF"),key(),1));
        Map<String,Object>b=receipt("SELF");b.put("patientId",OTHER_PATIENT);error(400,()->call("submit",OWNER,action,b,key(),1));assertPristine(action);
    }

    private CarePlanActionService target(){if(service==null)service=newActionService(h.f.jdbc(),h.auth,queue);return service;}
    static Class<?>actionType(){return CarePlanActionService.class;}
    private CarePlanActionService newActionService(JdbcTemplate jdbc,CarePlanAuthorizationService auth,CarePlanNotificationQueue notifications){return new CarePlanActionService(jdbc,auth,h.properties,new CarePlanCommandStore(jdbc,h.properties),new CarePlanEventStore(jdbc,auth,h.properties),notifications);}
    static Map<String,Object> invokeOn(Object target,String op,long actor,long action,Map<String,Object>b,String key,long version){
        CarePlanActionService service=(CarePlanActionService)target;
        switch(op){case "submit":return service.submit(actor,action,b,key,version);case "help":return service.help(actor,action,b,key,version);case "followUp":return service.followUp(actor,action,b,key,version);case "review":return service.review(actor,action,b,key,version);default:throw new AssertionError("Unknown test operation "+op);}
    }
    private Map<String,Object>invoke(String op,long actor,long action,Map<String,Object>b,String key,long version){return invokeOn(target(),op,actor,action,b,key,version);}
    private Map<String,Object>call(String op,long actor,long action,Map<String,Object>b,String key,long version){return h.tx.execute(s->invoke(op,actor,action,b,key,version));}
    private long publishedAction(){Map<String,Object>d=h.create(body(1));h.publish(DOCTOR,id(d),num(d.get("draftRevisionId")),map(),key(),0);return h.f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,id(d));}
    private long plan(long action){return h.f.jdbc().queryForObject("SELECT plan_id FROM care_plan_action WHERE id=?",Long.class,action);}
    private long revision(long action){return h.f.jdbc().queryForObject("SELECT revision_id FROM care_plan_action WHERE id=?",Long.class,action);}
    private Map<String,Object>view(long action,long actor){for(Map<String,Object>a:actions(h.query.revision(actor,plan(action),revision(action))))if(num(a.get("id"))==action)return a;throw new AssertionError("Missing action");}
    @SuppressWarnings("unchecked")private List<Map<String,Object>>events(long action,long actor){return(List<Map<String,Object>>)view(action,actor).get("events");}
    @SuppressWarnings("unchecked")private List<Map<String,Object>>eventEvidence(long action,long actor){return(List<Map<String,Object>>)events(action,actor).get(0).get("evidence");}
    private List<String>eventTypes(long action){return h.f.jdbc().queryForList("SELECT event_type FROM care_plan_event WHERE action_id=? ORDER BY id",String.class,action);}
    private Map<String,Object>receipt(String mode){return map("note","Synthetic completion","occurredAt","2026-10-03T04:00:00Z","entryMode",mode,"evidence",Collections.emptyList());}
    private static Map<String,Object>ref(String type,long source){return map("sourceType",type,"sourceId",source);}
    private static String repeat(String s,int n){return String.join("",Collections.nCopies(n,s));}
    private void grantNurse(){h.f.jdbc().update("INSERT INTO sys_user_role(user_id,role_id) SELECT 10,id FROM sys_role WHERE role_code='nurse'");h.f.jdbc().update("INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,assigned_by,status,assigned_at) VALUES(1,10,11,'ACTIVE',?)",Timestamp.from(h.f.now()));h.f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,granted_by) VALUES(1,10,'NURSE','PROXY','CARE_PLAN','ACTIVE',7)");}
    private void records(){h.f.jdbc().update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at,remark) VALUES(101,1,7,'WEIGHT',60,'kg',?,'Synthetic secret'),(102,2,8,'WEIGHT',50,'kg',?,'Other Synthetic secret')",Timestamp.from(h.f.now()),Timestamp.from(h.f.now()));h.f.jdbc().update("INSERT INTO medical_record(id,patient_id,patient_name,record_type,remark) VALUES(101,1,'Synthetic','BLOOD','Synthetic secret')");}
    private void assertPristine(long action){Map<String,Object>a=view(action,OWNER);assertEquals("OPEN",a.get("status"));assertEquals(0L,a.get("version"));assertNull(a.get("firstSubmittedAt"));assertNull(a.get("latestSubmittedAt"));assertTrue(events(action,OWNER).isEmpty());assertEquals(1,h.count("care_plan_notification"));assertEquals(2,h.count("care_plan_command"));assertEquals(2,h.count("care_plan_event"));}
    private void independent(String sql){try(Connection connection=h.f.jdbc().getDataSource().getConnection();Statement statement=connection.createStatement()){assertTrue(connection.getAutoCommit());assertEquals(1,statement.executeUpdate(sql));}catch(SQLException e){throw new IllegalStateException(e);}}
    private static final class ProbeDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final List<String>sql=new CopyOnWriteArrayList<>();final List<Integer>mutationIsolation=new CopyOnWriteArrayList<>();
        ProbeDataSource(javax.sql.DataSource source){super(source);}
        @Override public Connection getConnection()throws SQLException{Connection c=super.getConnection();c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{if(m.getName().equals("prepareStatement")&&a!=null&&a[0] instanceof String){sql.add((String)a[0]);if(((String)a[0]).matches("(?s)^(INSERT|UPDATE|DELETE).*"))mutationIsolation.add(c.getTransactionIsolation());}try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}});}
    }
}
