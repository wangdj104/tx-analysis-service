package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic real JDBC/transaction tests; no mocked authorization or persistence. */
class CarePlanDraftTest {
    private CarePlanTestFixture f;
    private TransactionTemplate tx;
    private CarePlanQueryService query;
    private CarePlanService service;
    private CarePlanCommandStore commands;
    private CarePlanEventStore events;
    private CarePlanProperties properties;
    private CarePlanAuthorizationService auth;
    private Clock clock;
    @BeforeEach void setup() throws Exception {
        f = new CarePlanTestFixture();
        tx = new TransactionTemplate(new DataSourceTransactionManager(f.jdbc().getDataSource()));
        clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return f.now(); }
        };
    }
    @AfterEach void close() throws Exception { f.close(); }

    @Test void draftHiddenFromPatientFamilyAndNurse() {
        services(true); grant(FAMILY,"FAMILY","WRITE","CARE_PLAN"); nurse();
        Map<String,Object> draft=create(body(),key()); long id=id(draft);
        assertEquals("DRAFT",draft.get("lifecycle")); assertEquals(1,draft.get("workflowVersion"));
        assertEquals(0L,draft.get("version")); assertNotNull(draft.get("draftRevisionId"));
        for(long actor:new long[]{OWNER,FAMILY,NURSE}) {
            denied(()->detail(actor,id));
            assertTrue(items(list(actor,PATIENT,"TODAY",null,50)).isEmpty());
        }
        denied(()->detail(ADMIN,id));denied(()->list(ADMIN,PATIENT,"TODAY",null,50));
        assertEquals("Synthetic draft",detail(DOCTOR,id).get("title"));
        assertEquals(0,count("care_plan_action")); assertEquals(0,count("care_plan_notification"));
        assertEquals(1,count("care_plan_revision")); assertEquals(1,count("care_plan_command"));
    }
    @Test void legacyCopyCreatesDraftWithoutTasks() {
        services(true); Map<String,Object>b=body(); b.put("legacySourceId",1L);
        Map<String,Object>d=create(b,key()); assertNotEquals(1L,id(d));
        assertEquals(1L,f.jdbc().queryForObject("SELECT legacy_source_id FROM doctor_care_plan WHERE id=?",Long.class,id(d)));
        assertEquals(0,f.jdbc().queryForObject("SELECT workflow_version FROM doctor_care_plan WHERE id=1",Integer.class));
        assertEquals("ACTIVE",f.jdbc().queryForObject("SELECT status FROM doctor_care_plan WHERE id=1",String.class));
        assertEquals(0,count("care_plan_action")); assertEquals(0,count("care_plan_notification"));
        denied(()->detail(DOCTOR,1));
        f.jdbc().update("INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions) VALUES(100,9,2,'Other legacy','Private')");
        b.put("legacySourceId",100L); denied(()->create(b,key()));
        assertEquals(1,count("care_plan_revision"));
        assertDoesNotThrow(()->save(id(d),num(d.get("draftRevisionId")),body(),key(),0));
        assertEquals(1L,f.jdbc().queryForObject("SELECT legacy_source_id FROM doctor_care_plan WHERE id=?",Long.class,id(d)));
    }
    @Test void draftSaveRequiresCurrentVersion() {
        services(true); Map<String,Object>d=create(body(),key()); Map<String,Object>b=body(); b.put("title","Changed");
        long plan=id(d),rev=num(d.get("draftRevisionId"));
        Map<String,Object>saved=save(plan,rev,b,key(),0); assertEquals(1L,saved.get("version"));
        conflict(()->save(plan,rev,body(),key(),0)); assertEquals("Changed",detail(DOCTOR,plan).get("title"));
        assertEquals(2,count("care_plan_event")); assertEquals(2,count("care_plan_command"));
        denied(()->save(plan,999,body(),key(),1));
        Map<String,Object>wrong=body(); wrong.put("patientId",OTHER_PATIENT); denied(()->save(plan,rev,wrong,key(),1));
    }
    @Test void moduleOnlyReadDoesNotLoadFullCareContext() {
        services(true); Map<String,Object>d=create(body(),key()); publishFixture(d);
        grant(FAMILY,"FAMILY","READ","CARE_PLAN");
        f.jdbc().execute("DROP TABLE care_member"); f.jdbc().execute("DROP TABLE medication CASCADE");
        Map<String,Object>view=detail(FAMILY,id(d)); assertEquals("Synthetic draft",view.get("title"));
        assertNull(view.get("draftRevisionId")); assertFalse(view.containsKey("revisionImpact"));
        for(String field:Arrays.asList("patientName","family","medications","medicalRecords","patient")) assertFalse(view.containsKey(field));
        assertEquals(1,items(list(FAMILY,PATIENT,"TODAY",null,50)).size());
        f.jdbc().update("INSERT INTO patient(id,name,user_id) VALUES(3,'Other synthetic profile',7)");
        denied(()->list(FAMILY,3L,"TODAY",null,50));
    }
    @Test void createAndSaveCommandsDeduplicateAndConflict() {
        services(true); String createKey=key(); Map<String,Object>b=body(); Map<String,Object>d=create(b,createKey);
        Map<String,Object>reordered=new LinkedHashMap<>(); List<String>fields=new ArrayList<>(b.keySet()); Collections.reverse(fields);
        for(String field:fields)reordered.put(field,b.get(field));
        assertEquals(d,create(reordered,createKey)); assertEquals(1,count("care_plan_revision"));
        Map<String,Object>changed=body();changed.put("title","Different");conflict(()->create(changed,createKey));
        String saveKey=key(); long rev=num(d.get("draftRevisionId"));
        Map<String,Object>saved=save(id(d),rev,changed,saveKey,0);
        assertEquals(saved,save(id(d),rev,changed,saveKey,0));
        assertEquals(1L,detail(DOCTOR,id(d)).get("version")); assertEquals(2,count("care_plan_command"));
        conflict(()->save(id(d),rev,body(),saveKey,0));
        assertEquals(d,create(b,createKey)); assertEquals("Different",detail(DOCTOR,id(d)).get("title"));
    }
    @Test void commandReplayRechecksCurrentClinicalAuthority() {
        services(true); String k=key();Map<String,Object>d=create(body(),k);
        f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED'");
        denied(()->create(body(),k)); denied(()->detail(DOCTOR,id(d)));
        assertEquals(1,count("care_plan_command"));
    }
    @Test void writesValidateCurrentAssigneeAndEvidence() {
        services(true); grant(FAMILY,"FAMILY","READ","CARE_PLAN");
        Map<String,Object>b=body(); action(b).put("assignedUserId",FAMILY); denied(()->create(b,key()));
        action(b).put("assignedUserId",OWNER); f.jdbc().update("UPDATE sys_user SET status=0 WHERE id=7"); denied(()->create(b,key()));
        f.jdbc().update("UPDATE sys_user SET status=1 WHERE id=7");
        f.jdbc().update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(1,2,7,'PRIVATE_VALUE',1,'synthetic',CURRENT_TIMESTAMP)");
        action(b).put("evidence",Collections.singletonList(map("sourceType","MEASUREMENT","sourceId",1L))); denied(()->create(b,key()));
        assertEquals(0,count("care_plan_revision")); assertEquals(0,count("care_plan_command"));
        f.jdbc().update("UPDATE health_measurement SET patient_id=1 WHERE id=1");
        Map<String,Object>d=create(b,key()); assertEquals(1,((List<?>)actionView(d).get("evidence")).size());
        String json=f.jdbc().queryForObject("SELECT draft_json FROM care_plan_revision WHERE id=?",String.class,d.get("draftRevisionId"));
        assertTrue(json.contains("sourceType"));assertFalse(json.contains("PRIVATE_VALUE"));
    }
    @Test void revisionCopiesPublishedSnapshotWithoutMutatingIt() {
        services(true);Map<String,Object>d=create(body(),key());publishFixture(d);long old=num(d.get("draftRevisionId"));
        String k=key(); Map<String,Object>next=revise(id(d),k,0);long draft=num(next.get("draftRevisionId"));
        assertNotEquals(old,draft); assertEquals("ACTIVE",next.get("lifecycle")); assertEquals(1L,next.get("version"));
        assertEquals(next,revise(id(d),k,0)); conflict(()->revise(id(d),key(),1));
        assertEquals(old,detail(DOCTOR,id(d)).get("currentRevisionId"));
        assertEquals("DRAFT",actionView(revision(DOCTOR,id(d),draft)).get("status"));
        assertNull(actionView(revision(DOCTOR,id(d),draft)).get("id"));
        assertEquals("OPEN",actionView(detail(DOCTOR,id(d))).get("status"));
        assertEquals(1,count("care_plan_action"));
        Map<String,Object>b=body();b.put("title","Private revised body");save(id(d),draft,b,key(),1);
        assertEquals("Synthetic draft",detail(DOCTOR,id(d)).get("title"));
        assertEquals("Private revised body",revision(DOCTOR,id(d),draft).get("title"));
        assertEquals("Synthetic draft",revision(OWNER,id(d),old).get("title"));
        denied(()->revision(OWNER,id(d),draft));
    }
    @Test void historyFiltersDraftsAndReauthorizesEveryRead() {
        services(true);nurse();Map<String,Object>d=create(body(),key());long plan=id(d);
        assertEquals(1,items(revisions(DOCTOR,plan,null,50)).size());denied(()->revisions(OWNER,plan,null,50));
        publishFixture(d);revise(plan,key(),0);
        assertEquals(2,items(revisions(DOCTOR,plan,null,50)).size());
        assertEquals(1,items(revisions(OWNER,plan,null,50)).size());
        assertEquals(1,items(revisions(NURSE,plan,null,50)).size());
        f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=10");
        denied(()->revisions(NURSE,plan,null,50));denied(()->revision(NURSE,plan,num(d.get("draftRevisionId"))));
        CarePlanException unknown=denied(()->revision(OWNER,plan,999));
        CarePlanException privateDraft=denied(()->revision(OWNER,plan,num(detail(DOCTOR,plan).get("draftRevisionId"))));
        assertEquals(unknown.getMessage(),privateDraft.getMessage());
    }
    @Test void evidenceIsReadTimeRedactedForOriginalModuleRights() {
        services(true); f.jdbc().update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(1,1,7,'SECRET_MEASUREMENT',1,'synthetic',CURRENT_TIMESTAMP)");
        Map<String,Object>b=body();action(b).put("evidence",Collections.singletonList(map("sourceType","MEASUREMENT","sourceId",1L)));
        Map<String,Object>d=create(b,key());publishFixture(d);grant(FAMILY,"FAMILY","READ","CARE_PLAN");
        Map<?,?>ref=(Map<?,?>)((List<?>)actionView(detail(FAMILY,id(d))).get("evidence")).get(0);
        assertEquals(true,ref.get("restricted")); assertFalse(ref.containsKey("title"));assertFalse(ref.containsKey("detailLink"));
        grant(FAMILY,"FAMILY","READ","CARE_PLAN,MEASUREMENTS");
        ref=(Map<?,?>)((List<?>)actionView(detail(FAMILY,id(d))).get("evidence")).get(0);
        assertEquals(false,ref.get("restricted")); assertEquals("SECRET_MEASUREMENT",ref.get("title"));
        f.jdbc().update("UPDATE health_measurement SET patient_id=2 WHERE id=1");
        ref=(Map<?,?>)((List<?>)actionView(detail(FAMILY,id(d))).get("evidence")).get(0);assertEquals(true,ref.get("restricted"));
    }
    @Test void draftCommandReplayRechecksEvidenceOwnership() {
        services(true);f.jdbc().update("INSERT INTO health_measurement(id,patient_id,recorded_by,metric_type,value_primary,unit,measured_at) VALUES(1,1,7,'SECRET_MEASUREMENT',1,'synthetic',CURRENT_TIMESTAMP)");
        Map<String,Object>b=body();action(b).put("evidence",Collections.singletonList(map("sourceType","MEASUREMENT","sourceId",1L)));String k=key();
        Map<String,Object>d=create(b,k);assertEquals(false,((Map<?,?>)((List<?>)actionView(d).get("evidence")).get(0)).get("restricted"));
        f.jdbc().update("UPDATE health_measurement SET patient_id=2 WHERE id=1");
        Map<?,?>ref=(Map<?,?>)((List<?>)actionView(create(b,k)).get("evidence")).get(0);
        assertEquals(true,ref.get("restricted"));assertFalse(ref.containsKey("title"));assertFalse(ref.containsKey("detailLink"));
        assertFalse(f.jdbc().queryForObject("SELECT result_json FROM care_plan_command WHERE command_key=?",String.class,k).contains("SECRET_MEASUREMENT"));
    }
    @Test void draftEventsStayPrivateAfterTheirRevisionIsPublished() {
        services(true);Map<String,Object>d=create(body(),key());publishFixture(d);long plan=id(d),rev=num(d.get("draftRevisionId"));
        long actionId=f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,plan);
        tx.execute(s->{events.append(PATIENT,plan,rev,actionId,DOCTOR,"DRAFT_UPDATED",map("note","PRIVATE_DRAFT_NOTE"));return null;});
        assertEquals(1,((List<?>)actionView(detail(DOCTOR,plan)).get("events")).size());
        assertTrue(((List<?>)actionView(detail(OWNER,plan)).get("events")).isEmpty());
        denied(()->tx.execute(s->{events.append(PATIENT,plan,rev,actionId,OWNER,"DRAFT_UPDATED",map("note","Forged draft event"));return null;}));
    }
    @Test void paginationStableBoundedAndRejectsForgedCursor() {
        services(true);for(int i=0;i<3;i++)create(body(),key());
        Set<Long>seen=new HashSet<>();String cursor=null;
        do {Map<String,Object>page=list(DOCTOR,PATIENT,"TODAY",cursor,1);assertEquals(1,items(page).size());assertTrue(seen.add(id(items(page).get(0))));cursor=(String)page.get("nextCursor");}while(cursor!=null);
        assertEquals(3,seen.size());assertEquals(3,items(list(DOCTOR,null,"TODAY",null,50)).size());
        invalid(()->list(DOCTOR,PATIENT,"TODAY",null,101));invalid(()->list(DOCTOR,PATIENT,"TODAY",null,0));
        invalid(()->list(DOCTOR,PATIENT,"TODAY' OR 1=1",null,50));invalid(()->list(DOCTOR,PATIENT,"TODAY","forged",50));
        String c=(String)list(DOCTOR,PATIENT,"TODAY",null,1).get("nextCursor"); invalid(()->list(DOCTOR,PATIENT,"HISTORY",c,1));
        Map<String,Object>d=items(list(DOCTOR,PATIENT,"TODAY",null,1)).get(0);publishFixture(d);revise(id(d),key(),0);
        Map<String,Object>p=revisions(DOCTOR,id(d),null,1);assertNotNull(p.get("nextCursor"));assertEquals(1,items(revisions(DOCTOR,id(d),(String)p.get("nextCursor"),1)).size());
    }
    @Test void assigneesReturnOnlyCurrentRecordersAndMinimumFields() {
        services(true);nurse();grant(FAMILY,"FAMILY","READ","CARE_PLAN");
        List<Map<String,Object>>rows=assignees();Set<Long>ids=new HashSet<>();
        for(Map<String,Object>row:rows){assertEquals(new HashSet<>(Arrays.asList("userId","displayName","role")),row.keySet());ids.add(num(row.get("userId")));}
        assertEquals(new HashSet<>(Arrays.asList(OWNER,DOCTOR,NURSE)),ids);
        f.jdbc().update("DELETE FROM sys_user_role WHERE user_id=10");assertEquals(2,assignees().size());
        denied(()->query.assignees(FAMILY,PATIENT));
    }
    @Test void invalidCommandsAndDraftsNeverPersistAnything() {
        services(true);invalid(()->create(body(),"1-1-1-1-1"));invalid(()->create(body(),null));
        Map<String,Object>b=body();b.put("actorId",ADMIN);Map<String,Object>forged=b;invalid(()->create(forged,key()));
        b=body();action(b).put("dueAt","2026-10-03T05:00:00.0000001Z");Map<String,Object>bad=b;invalid(()->create(bad,key()));
        Map<String,Object>d=create(body(),key());invalid(()->save(id(d),num(d.get("draftRevisionId")),body(),key(),-1));
        assertEquals(1,count("care_plan_command"));assertEquals(1,count("care_plan_revision"));
    }
    @Test void failedEventRollsBackPlanRevisionAndCreateReservation() {
        services(true);f.jdbc().execute("ALTER TABLE care_plan_event ADD CONSTRAINT fail_event CHECK (event_type='NEVER')");
        assertThrows(RuntimeException.class,()->create(body(),key()));
        assertEquals(0,count("care_plan_revision"));assertEquals(0,count("care_plan_command"));
        assertEquals(1,count("doctor_care_plan"));
        f.jdbc().execute("ALTER TABLE care_plan_event DROP CONSTRAINT fail_event");assertEquals("DRAFT",create(body(),key()).get("lifecycle"));
    }
    @Test void concurrentCreateReservationProducesOnePlan() throws Exception {
        services(true);String k=key();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try {Callable<Map<String,Object>>work=()->{go.await();return create(body(),k);};Future<Map<String,Object>>a=pool.submit(work),b=pool.submit(work);go.countDown();assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();}
        assertEquals(1,count("care_plan_command"));assertEquals(1,count("care_plan_revision"));assertEquals(2,count("doctor_care_plan"));
    }
    @Test void newTimesUseUtcAndMicrosecondsWithNonUtcJvm() {
        services(true);TimeZone previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        try {Map<String,Object>b=body();action(b).put("dueAt","2026-11-01T01:30:00.123456-04:00");Map<String,Object>d=create(b,key());assertEquals("2026-11-01T05:30:00.123456Z",actionView(d).get("dueAt"));
            assertEquals(f.now().toString(),f.jdbc().query("SELECT created_at FROM care_plan_revision",(rs,i)->rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().toString()).get(0));}
        finally{TimeZone.setDefault(previous);}
    }
    @Test void disabledServicesAvoidNewTablesAndLegacyPostRemainsLegacy() {
        services(false);f.jdbc().execute("SET REFERENTIAL_INTEGRITY FALSE");for(String table:Arrays.asList("care_plan_command","care_plan_event","care_plan_action","care_plan_revision","care_nurse_assignment"))f.jdbc().execute("DROP TABLE "+table+" CASCADE");
        assertEquals(503,assertThrows(CarePlanException.class,()->create(body(),key())).getStatus());
        assertEquals(503,assertThrows(CarePlanException.class,()->detail(DOCTOR,1)).getStatus());
        assertEquals(503,assertThrows(CarePlanException.class,()->list(DOCTOR,PATIENT,"TODAY",null,50)).getStatus());
        DoctorWorkspaceService legacy=new DoctorWorkspaceService();ReflectionTestUtils.setField(legacy,"jdbc",f.jdbc());f.as(DOCTOR);
        assertEquals(1,legacy.plans(PATIENT).size());legacy.savePlan(map("patientId",PATIENT,"title","Old POST","instructions","Old body"));
        assertEquals(2,f.jdbc().queryForObject("SELECT COUNT(*) FROM doctor_care_plan WHERE workflow_version=0",Integer.class));
        try(org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.registerBean(JdbcTemplate.class,()->f.jdbc());
            context.register(CarePlanProperties.class,CarePlanAuthorizationService.class,CarePlanQueryService.class,CarePlanService.class,CarePlanEventStore.class,CarePlanCommandStore.class);
            assertDoesNotThrow(context::refresh);
            assertTrue(context.getBeansOfType(CarePlanQueryService.class).isEmpty());assertTrue(context.getBeansOfType(CarePlanService.class).isEmpty());
            assertTrue(context.getBeansOfType(CarePlanEventStore.class).isEmpty());assertTrue(context.getBeansOfType(CarePlanCommandStore.class).isEmpty());
            assertFalse(context.getBean(CarePlanProperties.class).isEnabled());
        }
    }

    @Test void legacyListAndPostReserveCollaborationMarkerEvenAfterDisabling() {
        services(true);Map<String,Object>d=create(body(),key());
        DoctorWorkspaceService legacy=new DoctorWorkspaceService();ReflectionTestUtils.setField(legacy,"jdbc",f.jdbc());f.as(DOCTOR);
        assertEquals(1,legacy.plans(PATIENT).size());assertFalse(legacy.plans(PATIENT).toString().contains("Synthetic draft"));
        assertEquals("COLLABORATION",f.jdbc().queryForObject("SELECT status FROM doctor_care_plan WHERE id=?",String.class,id(d)));
        assertFalse(f.jdbc().queryForObject("SELECT instructions FROM doctor_care_plan WHERE id=?",String.class,id(d)).contains("Synthetic instruction"));
        publishFixture(d);revise(id(d),key(),0);
        assertEquals("COLLABORATION",f.jdbc().queryForObject("SELECT status FROM doctor_care_plan WHERE id=?",String.class,id(d)));
        assertThrows(IllegalArgumentException.class,()->legacy.savePlan(map("patientId",PATIENT,"title","Bad old POST","instructions","Body","status","COLLABORATION")));
        // The default-disabled old bean uses only baseline columns and still hides reserved rows.
        for(String column:Arrays.asList("workflow_version","lifecycle","current_revision_id","draft_revision_id","legacy_source_id","lock_version","closed_at","cancelled_at","cancel_reason"))f.jdbc().execute("ALTER TABLE doctor_care_plan DROP COLUMN "+column);
        assertEquals(1,legacy.plans(PATIENT).size());legacy.savePlan(map("patientId",PATIENT,"title","Baseline POST","instructions","Baseline body"));
        assertEquals(2,legacy.plans(PATIENT).size());
    }
    @Test void legacyEnabledListAddsVersionZeroWithoutChangingScope() {
        services(true);create(body(),key());DoctorWorkspaceService legacy=new DoctorWorkspaceService();ReflectionTestUtils.setField(legacy,"jdbc",f.jdbc());assertDoesNotThrow(()->ReflectionTestUtils.setField(legacy,"carePlanProperties",properties));f.as(DOCTOR);
        assertEquals(1,legacy.plans(PATIENT).size());assertEquals(0,((Number)legacy.plans(PATIENT).get(0).get("workflowVersion")).intValue());
        f.as(ADMIN);assertEquals(1,legacy.plans(PATIENT).size());
        f.jdbc().execute("ALTER TABLE doctor_care_plan ALTER COLUMN status DROP NOT NULL");
        f.jdbc().update("UPDATE doctor_care_plan SET status=NULL WHERE id=1");assertEquals(1,legacy.plans(PATIENT).size());
    }

    @Test void incompleteReservationCannotCommitEvenIfCallerCatchesFailure() {
        services(true);String k=key();
        assertThrows(RuntimeException.class,()->tx.execute(s->{
            try {((CarePlanCommandStore)commands).executeCreate(DOCTOR,k,"synthetic",()->null);}catch(RuntimeException expected) { }
            return null;
        }));
        assertEquals(0,count("care_plan_command"));
    }
    @Test void enabledLegacySummarySeparatesNewCountsWithCurrentClinicalScope() {
        services(true);create(body(),key());DoctorWorkspaceService legacy=new DoctorWorkspaceService();ReflectionTestUtils.setField(legacy,"jdbc",f.jdbc());
        assertDoesNotThrow(()->ReflectionTestUtils.setField(legacy,"carePlanProperties",properties));
        assertDoesNotThrow(()->ReflectionTestUtils.setField(legacy,"carePlanQuery",query));f.as(DOCTOR);
        Map<String,Object>counts=legacy.summary();assertEquals(1,counts.get("activePlans"));assertEquals(1,counts.get("draftPlans"));assertEquals(0,counts.get("activeCollaborativePlans"));
        f.as(ADMIN);counts=legacy.summary();assertEquals(0,counts.get("draftPlans"));assertEquals(0,counts.get("activeCollaborativePlans"));
        f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED'");f.as(DOCTOR);counts=legacy.summary();assertEquals(0,counts.get("draftPlans"));
    }

    @Test void mutationsUseReadCommittedWhenDatasourceDefaultIsRepeatableRead() throws Exception {
        IsolationProbeDataSource dataSource=new IsolationProbeDataSource(f.jdbc().getDataSource());
        try(AnnotationConfigApplicationContext context=transactionContext(dataSource)){
            CarePlanService proxied=context.getBean(CarePlanService.class);
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(proxied));
            Map<String,Object>d=proxied.createDraft(DOCTOR,body(),key());
            proxied.saveDraft(DOCTOR,id(d),num(d.get("draftRevisionId")),body(),key(),0);
            publishFixture(d);proxied.createRevision(DOCTOR,id(d),key(),1);
            assertFalse(dataSource.mutationIsolation.isEmpty());
            for(int level:dataSource.mutationIsolation)assertEquals(Connection.TRANSACTION_READ_COMMITTED,level);
            try(Connection connection=dataSource.getConnection()){assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());}
        }
    }
    @Test void incompatibleOuterTransactionIsRejectedBeforeMutation() {
        IsolationProbeDataSource dataSource=new IsolationProbeDataSource(f.jdbc().getDataSource());
        try(AnnotationConfigApplicationContext context=transactionContext(dataSource)){
            CarePlanService proxied=context.getBean(CarePlanService.class);
            for(int isolation:new int[]{TransactionDefinition.ISOLATION_DEFAULT,TransactionDefinition.ISOLATION_REPEATABLE_READ}){
                TransactionTemplate outer=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));outer.setIsolationLevel(isolation);
                IllegalStateException denied=assertThrows(IllegalStateException.class,()->outer.execute(status->{
                    if(isolation==TransactionDefinition.ISOLATION_DEFAULT)assertNull(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                    Connection connection=DataSourceUtils.getConnection(dataSource);
                    try{assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());}
                    catch(SQLException ex){throw new AssertionError(ex);}finally{DataSourceUtils.releaseConnection(connection,dataSource);}
                    return proxied.createDraft(DOCTOR,body(),key());
                }));
                assertTrue(denied.getMessage().contains("READ_COMMITTED"));
            }
            assertTrue(dataSource.mutationIsolation.isEmpty());assertEquals(0,count("care_plan_revision"));assertEquals(0,count("care_plan_command"));assertEquals(1,count("doctor_care_plan"));
        }
    }
    @Configuration @EnableTransactionManagement static class TransactionConfiguration { }
    private AnnotationConfigApplicationContext transactionContext(DataSource dataSource){
        AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("care-plan-test",Collections.singletonMap("care-plan.enabled",true)));
        context.registerBean(DataSource.class,()->dataSource);context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(dataSource));
        context.registerBean("transactionManager",PlatformTransactionManager.class,()->new DataSourceTransactionManager(dataSource));
        context.registerBean(CarePlanProperties.class,()->new CarePlanProperties(true,clock));
        context.register(TransactionConfiguration.class,CarePlanAuthorizationService.class,CarePlanQueryService.class,CarePlanCommandStore.class,CarePlanEventStore.class,CarePlanService.class);
        context.refresh();return context;
    }
    /** Real connections with a configured default RR; probe records effective isolation at each actual mutation. */
    private static final class IsolationProbeDataSource extends DelegatingDataSource {
        final List<Integer>mutationIsolation=new CopyOnWriteArrayList<>();
        IsolationProbeDataSource(DataSource target){super(target);}
        @Override public Connection getConnection()throws SQLException{
            Connection connection=super.getConnection();connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                if("prepareStatement".equals(method.getName())&&args!=null&&args.length>0&&args[0] instanceof String&&((String)args[0]).matches("(?s)^(INSERT|UPDATE|DELETE).*"))mutationIsolation.add(connection.getTransactionIsolation());
                try{return method.invoke(connection,args);}catch(InvocationTargetException ex){throw ex.getCause();}
            });
        }
    }

    private void services(boolean enabled) {
        properties=new CarePlanProperties(enabled,clock);auth=new CarePlanAuthorizationService(f.jdbc(),properties);
        query=new CarePlanQueryService(f.jdbc(),auth,properties);commands=new CarePlanCommandStore(f.jdbc(),properties);
        events=new CarePlanEventStore(f.jdbc(),auth,properties);service=new CarePlanService(f.jdbc(),auth,properties,query,commands,events);
    }
    private Map<String,Object>create(Map<String,Object>b,String k){return tx.execute(s->service.createDraft(DOCTOR,b,k));}
    private Map<String,Object>save(long p,long r,Map<String,Object>b,String k,long v){return tx.execute(s->service.saveDraft(DOCTOR,p,r,b,k,v));}
    private Map<String,Object>revise(long p,String k,long v){return tx.execute(s->service.createRevision(DOCTOR,p,k,v));}
    private Map<String,Object>detail(long a,long p){return query.detail(a,p);}
    private Map<String,Object>revision(long a,long p,long r){return query.revision(a,p,r);}
    private Map<String,Object>revisions(long a,long p,String c,int l){return query.listRevisions(a,p,c,l);}
    private Map<String,Object>list(long a,Long p,String q,String c,int l){return query.list(a,p,q,c,l);}
    private List<Map<String,Object>>assignees(){return query.assignees(DOCTOR,PATIENT);}
    private void publishFixture(Map<String,Object>d){long p=id(d),r=num(d.get("draftRevisionId"));tx.execute(s->{f.jdbc().update("UPDATE care_plan_revision SET status='PUBLISHED',published_by=9,published_at=? WHERE id=?",Timestamp.from(f.now()),r);f.jdbc().update("UPDATE doctor_care_plan SET lifecycle='ACTIVE',current_revision_id=?,draft_revision_id=NULL WHERE id=?",r,p);
        f.jdbc().update("INSERT INTO care_plan_action(plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,created_at,updated_at) VALUES(?,?,1,1,'Synthetic action',?,7,?,?)",p,r,Timestamp.from(f.now().plusSeconds(3600)),Timestamp.from(f.now()),Timestamp.from(f.now()));return null;});}
    private void grant(long actor,String role,String level,String modules){f.jdbc().update("DELETE FROM care_access_grant WHERE grantee_user_id=? AND patient_id=1",actor);f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,granted_by) VALUES(1,?,?,?,?,'ACTIVE',7)",actor,role,level,modules);}
    private void nurse(){f.jdbc().update("INSERT INTO sys_user_role(user_id,role_id) SELECT 10,id FROM sys_role WHERE role_code='nurse'");f.jdbc().update("INSERT INTO care_nurse_assignment(patient_id,nurse_user_id,assigned_by,assigned_at) VALUES(1,10,11,?)",Timestamp.from(f.now()));grant(NURSE,"NURSE","WRITE","CARE_PLAN");}
    private int count(String table){return f.jdbc().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private static String key(){return UUID.randomUUID().toString();}
    private Map<String,Object>body(){return map("patientId",PATIENT,"title","Synthetic draft","instructions","Synthetic instruction","planType","FOLLOW_UP","actions",Collections.singletonList(map("ordinal",1,"instruction","Synthetic action","dueAt","2026-10-03T06:00:00Z","assignedUserId",OWNER)));}
    private static Map<String,Object>map(Object...kv){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    @SuppressWarnings("unchecked")private static Map<String,Object>action(Map<String,Object>b){return(Map<String,Object>)((List<?>)b.get("actions")).get(0);}
    @SuppressWarnings("unchecked")private static Map<String,Object>actionView(Map<String,Object>b){return(Map<String,Object>)((List<?>)b.get("actions")).get(0);}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>>items(Map<String,Object>p){return(List<Map<String,Object>>)p.get("items");}
    private static long id(Map<String,Object>m){return num(m.get("id"));}private static long num(Object n){return((Number)n).longValue();}
    private CarePlanException denied(Runnable work){CarePlanException e=assertThrows(CarePlanException.class,work::run);assertEquals(403,e.getStatus());assertEquals("ACCESS_DENIED",e.getErrorCode());return e;}
    private void conflict(Runnable work){assertEquals(409,assertThrows(CarePlanException.class,work::run).getStatus());}
    private void invalid(Runnable work){assertEquals(400,assertThrows(CarePlanException.class,work::run).getStatus());}
}
