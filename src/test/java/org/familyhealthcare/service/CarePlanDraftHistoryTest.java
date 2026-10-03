package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.CarePlanQueryService;
import org.junit.jupiter.api.*;

import java.sql.Timestamp;
import java.util.*;

import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.familyhealthcare.service.CarePlanLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real committed commands and JDBC reads for uncertain-create history reconciliation. */
class CarePlanDraftHistoryTest {
    private CarePlanLifecycleTest h;

    @BeforeEach void setup() throws Exception { h=new CarePlanLifecycleTest();h.setup(); }
    @AfterEach void close() throws Exception { if(h!=null)h.close(); }

    @Test void committedCreateCanBeReconciledAfterLosingResponseAndRecreatingQuery() {
        Map<String,Object>draft=create(PATIENT,"Committed private draft");
        CarePlanQueryService reloaded=new CarePlanQueryService(h.f.jdbc(),h.auth,h.properties);
        List<Map<String,Object>>history=items(reloaded.list(DOCTOR,PATIENT,"HISTORY",null,50));
        assertEquals(1,history.size());
        Map<String,Object>found=history.get(0);
        assertEquals(id(draft),id(found));assertEquals("Committed private draft",found.get("title"));
        assertEquals("DRAFT",found.get("lifecycle"));assertEquals("DRAFT",found.get("revisionStatus"));
        assertNull(found.get("currentRevisionId"));assertEquals(draft.get("draftRevisionId"),found.get("draftRevisionId"));
        assertEquals(Arrays.asList("SAVE_DRAFT","PUBLISH_PLAN"),found.get("allowedActions"));
        assertEquals("DRAFT",actions(found).get(0).get("status"));
        assertEquals(1,h.count("care_plan_revision"));assertEquals(1,h.count("care_plan_command"));
        assertEquals(0,h.count("care_plan_action"));assertEquals(0,h.count("care_plan_notification"));
    }

    @Test void currentlyAssignedDoctorCanReconcileAnotherDoctorsPrivateDraft() {
        Map<String,Object>draft=create(PATIENT,"Another doctor's draft");
        addRole(OWNER,"doctor");
        h.f.jdbc().update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(7,1,11)");
        assertEquals(Collections.singletonList(id(draft)),ids(history(OWNER,PATIENT,null,1)));
        assertEquals(Collections.singletonList(id(draft)),ids(history(OWNER,null,null,1)));
    }

    @Test void privateAndPublishedPlansPageStablyInPatientAndAllPatientHistory() {
        h.f.jdbc().update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(9,2,11)");
        Map<String,Object>a=create(PATIENT,"First private"),b=create(OTHER_PATIENT,"Other private");
        Map<String,Object>c=create(PATIENT,"Published"),d=create(OTHER_PATIENT,"Other published");
        publish(c);publish(d);Map<String,Object>e=create(PATIENT,"Last private");
        // All first revisions share a timestamp, so plan ID must be the stable tiebreaker.
        h.f.jdbc().update("INSERT INTO patient(id,name,user_id) VALUES(3,'Unassigned synthetic patient',8)");
        addRole(FAMILY,"doctor");
        h.f.jdbc().update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(8,3,11)");
        Map<String,Object>foreign=body(1);foreign.put("patientId",3L);actions(foreign).get(0).put("assignedUserId",FAMILY);
        h.tx.execute(s->h.service.createDraft(FAMILY,foreign,key()));
        assertEquals(Arrays.asList(id(a),id(c),id(e)),allIds(DOCTOR,PATIENT));
        assertEquals(Arrays.asList(id(b),id(d)),allIds(DOCTOR,OTHER_PATIENT));
        assertEquals(Arrays.asList(id(a),id(b),id(c),id(d),id(e)),allIds(DOCTOR,null));
        assertEquals(5,items(history(DOCTOR,null,null,50)).size());
    }

    @Test void authorizedNonclinicalReadersSkipPrivateDraftsAcrossEveryHistoryPage() {
        nonclinicalReaders();
        create(PATIENT,"Private before");create(PATIENT,"Private before two");
        Map<String,Object>a=create(PATIENT,"Published one");publish(a);
        create(PATIENT,"Private middle");create(PATIENT,"Private middle two");
        Map<String,Object>b=create(PATIENT,"Published two");publish(b);create(PATIENT,"Private after");
        for(long actor:new long[]{OWNER,FAMILY,NURSE,ADMIN}) {
            assertDoesNotThrow(()->h.auth.requireRead(actor,PATIENT));
            assertEquals(Arrays.asList(id(a),id(b)),allIds(actor,PATIENT),"patient history for actor "+actor);
            assertEquals(Arrays.asList(id(a),id(b)),allIds(actor,null),"all history for actor "+actor);
            for(Map<String,Object>view:items(history(actor,PATIENT,null,50))) {
                assertEquals("PUBLISHED",view.get("revisionStatus"));assertNull(view.get("draftRevisionId"));
                assertFalse(view.toString().contains("Private"));
            }
        }
    }

    @Test void adminWithCarePlanGrantAndNoClinicalOrFamilyRoleCannotReadPrivateHistory() {
        grant(ADMIN,"FAMILY");create(PATIENT,"Admin must not see");
        error(403,()->history(ADMIN,PATIENT,null,50));
        assertTrue(items(history(ADMIN,null,null,50)).isEmpty());
    }

    @Test void revokedDoctorWithReadGrantCannotReuseAuthorIdentityOrCursorForDrafts() {
        Map<String,Object>privateOne=create(PATIENT,"Original author's private draft");
        Map<String,Object>publishedOne=create(PATIENT,"Published one");publish(publishedOne);
        create(PATIENT,"Another private draft");
        Map<String,Object>publishedTwo=create(PATIENT,"Published two");publish(publishedTwo);
        String patientCursor=(String)history(DOCTOR,PATIENT,null,1).get("nextCursor");
        String allCursor=(String)history(DOCTOR,null,null,1).get("nextCursor");
        assertNotNull(patientCursor);assertNotNull(allCursor);
        addRole(DOCTOR,"family");grant(DOCTOR,"FAMILY");
        h.f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9");
        assertDoesNotThrow(()->h.auth.requireRead(DOCTOR,PATIENT));
        error(403,()->h.query.detail(DOCTOR,id(privateOne)));
        assertEquals(Arrays.asList(id(publishedOne),id(publishedTwo)),allIds(DOCTOR,PATIENT));
        assertEquals(Arrays.asList(id(publishedOne),id(publishedTwo)),allIds(DOCTOR,null));
        assertEquals(Collections.singletonList(id(publishedOne)),ids(history(DOCTOR,PATIENT,patientCursor,1)));
        assertEquals(Collections.singletonList(id(publishedOne)),ids(history(DOCTOR,null,allCursor,1)));
    }

    @Test void currentRevocationAndExpiryRemoveHistoryAccessForDoctorFamilyAndNurse() {
        nonclinicalReaders();create(PATIENT,"Private");Map<String,Object>published=create(PATIENT,"Published");publish(published);
        String doctorCursor=(String)history(DOCTOR,PATIENT,null,1).get("nextCursor");assertNotNull(doctorCursor);
        h.f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9");
        error(403,()->history(DOCTOR,PATIENT,doctorCursor,1));assertTrue(items(history(DOCTOR,null,null,50)).isEmpty());
        for(long actor:new long[]{FAMILY,NURSE}) {
            assertEquals(Collections.singletonList(id(published)),allIds(actor,PATIENT));
            h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=?",actor);
            error(403,()->history(actor,PATIENT,null,50));assertTrue(items(history(actor,null,null,50)).isEmpty());
            h.f.jdbc().update("UPDATE care_access_grant SET status='ACTIVE',expires_at=? WHERE grantee_user_id=?",Timestamp.from(java.time.Instant.EPOCH),actor);
            error(403,()->history(actor,PATIENT,null,50));assertTrue(items(history(actor,null,null,50)).isEmpty());
        }
        h.f.jdbc().update("UPDATE care_access_grant SET expires_at=NULL WHERE grantee_user_id=10");
        h.f.jdbc().update("UPDATE care_nurse_assignment SET expires_at=? WHERE nurse_user_id=10",Timestamp.from(java.time.Instant.EPOCH));
        error(403,()->history(NURSE,PATIENT,null,50));assertTrue(items(history(NURSE,null,null,50)).isEmpty());
    }

    @Test void historyCursorCannotCrossActorPatientScopeOrQueue() {
        create(PATIENT,"First");create(PATIENT,"Second");
        h.f.jdbc().update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(9,2,11)");
        String patient=(String)history(DOCTOR,PATIENT,null,1).get("nextCursor");
        String all=(String)history(DOCTOR,null,null,1).get("nextCursor");
        assertNotNull(patient);assertNotNull(all);
        error(400,()->history(OWNER,PATIENT,patient,1));
        error(400,()->history(DOCTOR,OTHER_PATIENT,patient,1));
        error(400,()->history(DOCTOR,null,patient,1));
        error(400,()->history(DOCTOR,PATIENT,all,1));
        error(400,()->h.query.list(DOCTOR,PATIENT,"TODAY",patient,1));
        error(400,()->history(DOCTOR,PATIENT,"forged",1));
        assertEquals(1,items(history(DOCTOR,PATIENT,patient,1)).size());
    }

    @Test void historyKeepsPublishedSnapshotWhenActivePlanHasPrivateRevision() {
        nonclinicalReaders();Map<String,Object>draft=create(PATIENT,"Published title");publish(draft);
        Map<String,Object>next=h.tx.execute(s->h.service.createRevision(DOCTOR,id(draft),key(),1));
        Map<String,Object>changed=body(1);changed.put("title","Private revised title");
        h.tx.execute(s->h.service.saveDraft(DOCTOR,id(draft),num(next.get("draftRevisionId")),changed,key(),2));
        for(long actor:new long[]{DOCTOR,OWNER,FAMILY,NURSE,ADMIN}) {
            Map<String,Object>view=items(history(actor,PATIENT,null,1)).get(0);
            assertEquals("Published title",view.get("title"));assertEquals("PUBLISHED",view.get("revisionStatus"));
            assertFalse(view.toString().contains("Private revised title"));
            if(actor==DOCTOR)assertEquals(next.get("draftRevisionId"),view.get("draftRevisionId"));
            else {assertNull(view.get("draftRevisionId"));assertFalse(view.containsKey("revisionImpact"));}
        }
    }

    private Map<String,Object>create(long patient,String title) {
        Map<String,Object>b=body(1);b.put("patientId",patient);b.put("title",title);
        actions(b).get(0).put("assignedUserId",patient==PATIENT?OWNER:FAMILY);
        return h.create(b);
    }
    private void publish(Map<String,Object>draft) { h.publish(DOCTOR,id(draft),num(draft.get("draftRevisionId")),map(),key(),0); }
    private Map<String,Object>history(long actor,Long patient,String cursor,int limit) { return h.query.list(actor,patient,"HISTORY",cursor,limit); }
    private void addRole(long actor,String role) { h.f.jdbc().update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?",actor,role); }
    private void grant(long actor,String role) { h.f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,granted_by) VALUES(1,?,?,'READ','CARE_PLAN','ACTIVE',7)",actor,role); }
    private void nonclinicalReaders() {
        grant(FAMILY,"FAMILY");addRole(NURSE,"nurse");grant(NURSE,"NURSE");
        h.f.jdbc().update("INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,assigned_by,assigned_at) VALUES(1,10,11,?)",Timestamp.from(h.f.now()));
        // An administrator with independently granted family read access still has no clinical authority.
        addRole(ADMIN,"family");grant(ADMIN,"FAMILY");
    }
    private List<Long>allIds(long actor,Long patient) {
        List<Long>result=new ArrayList<>();Set<String>cursors=new HashSet<>();String cursor=null;
        do {
            Map<String,Object>page=history(actor,patient,cursor,1);List<Map<String,Object>>rows=items(page);
            assertTrue(rows.size()<=1);for(Map<String,Object>row:rows)assertTrue(!result.contains(id(row)),"duplicate history plan");
            result.addAll(ids(page));cursor=(String)page.get("nextCursor");
            if(cursor!=null){assertEquals(1,rows.size());assertTrue(cursors.add(cursor),"repeated history cursor");}
        } while(cursor!=null);
        return result;
    }
    private List<Long>ids(Map<String,Object>page) { List<Long>result=new ArrayList<>();for(Map<String,Object>row:items(page))result.add(id(row));return result; }
    @SuppressWarnings("unchecked")private static List<Map<String,Object>>items(Map<String,Object>page) { return(List<Map<String,Object>>)page.get("items"); }
}
