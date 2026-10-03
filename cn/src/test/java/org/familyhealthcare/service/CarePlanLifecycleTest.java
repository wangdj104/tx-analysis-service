package org.familyhealthcare.service;

import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic real JDBC lifecycle and transactional-outbox contract tests. */
class CarePlanLifecycleTest {
    CarePlanTestFixture f;
    TransactionTemplate tx;
    CarePlanProperties properties;
    CarePlanAuthorizationService auth;
    CarePlanQueryService query;
    CarePlanService service;
    volatile boolean failQueue;
    java.util.function.Consumer<java.sql.Connection> connectionObserver=connection->{};

    @BeforeEach void setup() throws Exception {
        f=new CarePlanTestFixture();tx=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc().getDataSource()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        properties=new CarePlanProperties(true,new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return f.now();}});
        auth=new CarePlanAuthorizationService(f.jdbc(),properties);query=new CarePlanQueryService(f.jdbc(),auth,properties);
        service=new CarePlanService(f.jdbc(),auth,properties,query,new CarePlanCommandStore(f.jdbc(),properties),new CarePlanEventStore(f.jdbc(),auth,properties));
        installQueue();
    }
    @AfterEach void close() throws Exception {f.close();}

    @Test void publishCreatesOneFrozenRevisionAndActionSet() {
        Map<String,Object>d=create(body(2));long plan=id(d),revision=num(d.get("draftRevisionId"));
        String frozen=f.jdbc().queryForObject("SELECT draft_json FROM care_plan_revision WHERE id=?",String.class,revision);
        Map<String,Object>result=publish(DOCTOR,plan,revision,map(),key(),0);
        assertEquals("ACTIVE",result.get("lifecycle"));assertEquals(1L,result.get("version"));assertEquals(plan,result.get("planId"));
        assertNull(result.get("actionId"));assertNull(result.get("actionStatus"));assertNotNull(result.get("eventId"));
        assertEquals(2,count("care_plan_action"));assertEquals(1,count("care_plan_notification"));
        assertEquals(Arrays.asList("OPEN","OPEN"),f.jdbc().queryForList("SELECT status FROM care_plan_action ORDER BY ordinal",String.class));
        assertEquals(frozen,f.jdbc().queryForObject("SELECT draft_json FROM care_plan_revision WHERE id=?",String.class,revision));
        assertEquals("PUBLISHED",f.jdbc().queryForObject("SELECT status FROM care_plan_revision WHERE id=?",String.class,revision));
        assertEquals(DOCTOR,f.jdbc().queryForObject("SELECT published_by FROM care_plan_revision WHERE id=?",Long.class,revision));
        assertEquals("COLLABORATION",f.jdbc().queryForObject("SELECT status FROM doctor_care_plan WHERE id=?",String.class,plan));
        assertEquals("",f.jdbc().queryForObject("SELECT instructions FROM doctor_care_plan WHERE id=?",String.class,plan));
        assertEquals(revision,query.detail(OWNER,plan).get("currentRevisionId"));assertNull(query.detail(OWNER,plan).get("draftRevisionId"));
        error(403,()->tx.execute(s->service.saveDraft(DOCTOR,plan,revision,body(2),key(),1)));
        assertEquals(frozen,f.jdbc().queryForObject("SELECT draft_json FROM care_plan_revision WHERE id=?",String.class,revision));
        assertEquals("PLAN_PUBLISHED",f.jdbc().queryForObject("SELECT event_type FROM care_plan_event WHERE id=?",String.class,result.get("eventId")));
        assertEquals(result.get("eventId"),f.jdbc().queryForObject("SELECT event_id FROM care_plan_notification",Long.class));
    }
    @Test void revisionSupersedesPendingButRetainsConfirmedHistory() {
        Map<String,Object>d=create(body(4));long plan=id(d),old=num(d.get("draftRevisionId"));publish(DOCTOR,plan,old,map(),key(),0);
        f.jdbc().update("UPDATE care_plan_action SET status=CASE ordinal WHEN 1 THEN 'CONFIRMED' WHEN 2 THEN 'OPEN' WHEN 3 THEN 'NEEDS_HELP' ELSE 'SUBMITTED' END,lock_version=3 WHERE revision_id=?",old);
        Map<String,Object>r=tx.execute(s->service.createRevision(DOCTOR,plan,key(),1));long next=num(r.get("draftRevisionId"));
        tx.execute(s->service.saveDraft(DOCTOR,plan,next,body(1),key(),2));Map<String,Object>confirmation=confirmation(plan);
        Map<String,Object>result=publish(DOCTOR,plan,next,confirmation,key(),3);
        assertEquals("ACTIVE",result.get("lifecycle"));assertEquals(4L,result.get("version"));
        assertEquals(Arrays.asList("CONFIRMED","SUPERSEDED","SUPERSEDED","SUPERSEDED"),f.jdbc().queryForList("SELECT status FROM care_plan_action WHERE revision_id=? ORDER BY ordinal",String.class,old));
        assertEquals(Arrays.asList(3L,4L,4L,4L),f.jdbc().queryForList("SELECT lock_version FROM care_plan_action WHERE revision_id=? ORDER BY ordinal",Long.class,old));
        assertEquals("OPEN",f.jdbc().queryForObject("SELECT status FROM care_plan_action WHERE revision_id=?",String.class,next));
        assertEquals("PUBLISHED",f.jdbc().queryForObject("SELECT status FROM care_plan_revision WHERE id=?",String.class,old));
        assertEquals(old,query.revision(OWNER,plan,old).get("revisionId"));assertEquals(next,query.detail(OWNER,plan).get("revisionId"));
        assertEquals("REVISION_PUBLISHED",f.jdbc().queryForObject("SELECT event_type FROM care_plan_event WHERE id=?",String.class,result.get("eventId")));
        assertEquals(2,count("care_plan_notification"));
    }
    @Test void revisionConfirmationRechecksCurrentIdsStatusAndVersion() {
        Map<String,Object>d=create(body(1));long plan=id(d),old=num(d.get("draftRevisionId"));publish(DOCTOR,plan,old,map(),key(),0);
        Map<String,Object>r=tx.execute(s->service.createRevision(DOCTOR,plan,key(),1));long next=num(r.get("draftRevisionId"));Map<String,Object>stale=confirmation(plan);
        error(409,()->publish(DOCTOR,plan,next,map(),key(),2));
        error(409,()->publish(DOCTOR,plan,next,map("currentRevisionId",old+1,"supersededActionDigest",stale.get("supersededActionDigest")),key(),2));
        f.jdbc().update("UPDATE care_plan_action SET lock_version=lock_version+1 WHERE revision_id=?",old);
        final Map<String,Object>staleVersion=stale;error(409,()->publish(DOCTOR,plan,next,staleVersion,key(),2));Map<String,Object>changed=confirmation(plan);
        final Map<String,Object>staleStatus=changed;f.jdbc().update("UPDATE care_plan_action SET status='SUBMITTED' WHERE revision_id=?",old);
        error(409,()->publish(DOCTOR,plan,next,staleStatus,key(),2));
        final Map<String,Object>staleIds=confirmation(plan);
        f.jdbc().update("INSERT INTO care_plan_action(plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,created_at,updated_at) VALUES(?,?,1,2,'Synthetic late action',?,7,?,?)",plan,old,Timestamp.from(f.now()),Timestamp.from(f.now()),Timestamp.from(f.now()));
        error(409,()->publish(DOCTOR,plan,next,staleIds,key(),2));assertEquals(1,count("care_plan_notification"));
        publish(DOCTOR,plan,next,confirmation(plan),key(),2);assertEquals(2,count("care_plan_notification"));
    }
    @Test void closeRequiresAllCurrentActionsConfirmed() {
        Map<String,Object>d=create(body(2));long plan=id(d),rev=num(d.get("draftRevisionId"));publish(DOCTOR,plan,rev,map(),key(),0);
        error(409,()->transition(DOCTOR,plan,"CLOSE",null,key(),1));f.jdbc().update("UPDATE care_plan_action SET status='CONFIRMED' WHERE ordinal=1");
        error(409,()->transition(DOCTOR,plan,"CLOSE",null,key(),1));
        for(long actor:new long[]{OWNER,FAMILY,NURSE,ADMIN})error(403,()->transition(actor,plan,"CLOSE",null,key(),1));
        f.jdbc().update("UPDATE care_plan_action SET status='CONFIRMED'");Map<String,Object>result=transition(DOCTOR,plan,"CLOSE",null,key(),1);
        assertEquals("COMPLETED",result.get("lifecycle"));assertEquals(2L,result.get("version"));assertNotNull(f.jdbc().queryForObject("SELECT closed_at FROM doctor_care_plan WHERE id=?",Timestamp.class,plan));
        assertEquals("COLLABORATION",f.jdbc().queryForObject("SELECT status FROM doctor_care_plan WHERE id=?",String.class,plan));
        error(409,()->transition(DOCTOR,plan,"CANCEL","Synthetic reason",key(),2));error(409,()->tx.execute(s->service.createRevision(DOCTOR,plan,key(),2)));
    }
    @Test void cancelRequiresReasonAndPreservesConfirmedHistory() {
        Map<String,Object>d=create(body(4));long plan=id(d),rev=num(d.get("draftRevisionId"));publish(DOCTOR,plan,rev,map(),key(),0);
        error(400,()->transition(DOCTOR,plan,"CANCEL","  ",key(),1));error(400,()->transition(DOCTOR,plan,"CANCEL",String.join("",Collections.nCopies(1001,"x")),key(),1));
        error(400,()->transition(DOCTOR,plan,"REOPEN",null,key(),1));error(400,()->transition(DOCTOR,plan,"CLOSE","unexpected",key(),1));
        for(long actor:new long[]{OWNER,FAMILY,NURSE,ADMIN})error(403,()->transition(actor,plan,"CANCEL","Synthetic reason",key(),1));
        f.jdbc().update("UPDATE care_plan_action SET status=CASE ordinal WHEN 1 THEN 'CONFIRMED' WHEN 2 THEN 'OPEN' WHEN 3 THEN 'NEEDS_HELP' ELSE 'SUBMITTED' END");
        Map<String,Object>result=transition(DOCTOR,plan,"CANCEL","  Synthetic reason  ",key(),1);assertEquals("CANCELLED",result.get("lifecycle"));
        assertEquals(Arrays.asList("CONFIRMED","CANCELLED","CANCELLED","CANCELLED"),f.jdbc().queryForList("SELECT status FROM care_plan_action ORDER BY ordinal",String.class));
        assertEquals("Synthetic reason",f.jdbc().queryForObject("SELECT cancel_reason FROM doctor_care_plan WHERE id=?",String.class,plan));
        assertNotNull(f.jdbc().queryForObject("SELECT cancelled_at FROM doctor_care_plan WHERE id=?",Timestamp.class,plan));assertEquals(2,count("care_plan_notification"));
        error(409,()->transition(DOCTOR,plan,"CLOSE",null,key(),2));
    }
    @Test void sameKeySameBodyReturnsSameResult() {
        Map<String,Object>d=create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));String k=key();
        Map<String,Object>first=publish(DOCTOR,plan,rev,map(),k,0);
        Map<String,Object>replay=publish(DOCTOR,plan,rev,map("supersededActionDigest",null,"currentRevisionId",null),k.toUpperCase(Locale.ROOT),0);
        assertEquals(first,replay);assertEquals("ACTIVE",first.get("lifecycle"));assertEquals(1,count("care_plan_action"));assertEquals(2,count("care_plan_event"));assertEquals(1,count("care_plan_notification"));assertEquals(2,count("care_plan_command"));
        String cancelKey=key();Map<String,Object>cancel=transition(DOCTOR,plan,"CANCEL","Reason",cancelKey,1);
        assertEquals(cancel,transition(DOCTOR,plan,"CANCEL"," Reason ",cancelKey,1));assertEquals(first,publish(DOCTOR,plan,rev,map(),k,0));
        assertEquals(3,count("care_plan_event"));assertEquals(2,count("care_plan_notification"));
    }
    @Test void sameKeyDifferentBodyReturns409() {
        Map<String,Object>d=create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));String k=key();publish(DOCTOR,plan,rev,map(),k,0);
        error(409,()->publish(DOCTOR,plan,rev,map(),k,1));error(409,()->publish(DOCTOR,plan,rev+1,map(),k,0));
        error(409,()->publish(DOCTOR,plan,rev,map("currentRevisionId",rev,"supersededActionDigest","different"),k,0));
        error(409,()->transition(DOCTOR,plan,"CANCEL","Reason",k,0));
        Map<String,Object>other=create(body(1));error(409,()->publish(DOCTOR,id(other),num(other.get("draftRevisionId")),map(),k,0));
        String cancel=key();transition(DOCTOR,plan,"CANCEL","One",cancel,1);error(409,()->transition(DOCTOR,plan,"CANCEL","Two",cancel,1));
    }
    @Test void replayAfterRevocationReturns403() {
        Map<String,Object>d=create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));String k=key();publish(DOCTOR,plan,rev,map(),k,0);
        f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9");error(403,()->publish(DOCTOR,plan,rev,map(),k,0));
        assertEquals(1,count("care_plan_notification"));assertEquals(2,count("care_plan_command"));
    }
    @Test void publishRechecksAssigneeAndEvidenceAndActionBounds() {
        grantFamily();Map<String,Object>b=body(1);actions(b).get(0).put("assignedUserId",FAMILY);Map<String,Object>d=create(b);
        f.jdbc().update("UPDATE care_access_grant SET status='REVOKED'");error(403,()->publish(DOCTOR,id(d),num(d.get("draftRevisionId")),map(),key(),0));
        f.jdbc().update("INSERT INTO medical_record(id,patient_id,patient_name,record_type) VALUES(100,1,'Synthetic source','OTHER')");b=body(1);actions(b).get(0).put("evidence",Collections.singletonList(map("sourceType","MEDICAL_RECORD","sourceId",100L)));Map<String,Object>e=create(b);
        f.jdbc().update("UPDATE medical_record SET patient_id=2 WHERE id=100");error(403,()->publish(DOCTOR,id(e),num(e.get("draftRevisionId")),map(),key(),0));
        Map<String,Object>empty=create(body(1));f.jdbc().update("UPDATE care_plan_revision SET draft_json=? WHERE id=?","{\"patientId\":1,\"title\":\"Synthetic\",\"instructions\":\"Synthetic\",\"planType\":\"FOLLOW_UP\",\"actions\":[]}",empty.get("draftRevisionId"));
        error(400,()->publish(DOCTOR,id(empty),num(empty.get("draftRevisionId")),map(),key(),0));assertEquals(0,count("care_plan_action"));
        Map<String,Object>max=create(body(50));publish(DOCTOR,id(max),num(max.get("draftRevisionId")),map(),key(),0);assertEquals(50,count("care_plan_action"));
    }
    @Test void queueFailureRollsBackPublicationAndAllowsExactRetry() {
        Map<String,Object>d=create(body(2));long plan=id(d),rev=num(d.get("draftRevisionId"));String k=key();failQueue=true;
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->publish(DOCTOR,plan,rev,map(),k,0));assertEquals("Synthetic queue failure",failure.getMessage());
        assertEquals(0,count("care_plan_action"));assertEquals(0,count("care_plan_notification"));assertEquals(1,count("care_plan_event"));assertEquals(1,count("care_plan_command"));
        assertEquals("DRAFT",f.jdbc().queryForObject("SELECT lifecycle FROM doctor_care_plan WHERE id=?",String.class,plan));assertEquals("DRAFT",f.jdbc().queryForObject("SELECT status FROM care_plan_revision WHERE id=?",String.class,rev));
        failQueue=false;publish(DOCTOR,plan,rev,map(),k,0);assertEquals(2,count("care_plan_action"));assertEquals(1,count("care_plan_notification"));
    }
    @Test void revisionQueueFailureRestoresOldPendingSetAndImmutableHistory() {
        Map<String,Object>d=create(body(2));long plan=id(d),old=num(d.get("draftRevisionId"));publish(DOCTOR,plan,old,map(),key(),0);
        f.jdbc().update("UPDATE care_plan_action SET status='CONFIRMED' WHERE ordinal=1");Map<String,Object>r=tx.execute(s->service.createRevision(DOCTOR,plan,key(),1));long next=num(r.get("draftRevisionId"));Map<String,Object>confirmation=confirmation(plan);String k=key();
        String frozen=f.jdbc().queryForObject("SELECT draft_json FROM care_plan_revision WHERE id=?",String.class,old);failQueue=true;
        assertThrows(IllegalStateException.class,()->publish(DOCTOR,plan,next,confirmation,k,2));
        assertEquals(Arrays.asList("CONFIRMED","OPEN"),f.jdbc().queryForList("SELECT status FROM care_plan_action WHERE revision_id=? ORDER BY ordinal",String.class,old));
        assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM care_plan_action WHERE revision_id=?",Integer.class,next));
        assertEquals("DRAFT",f.jdbc().queryForObject("SELECT status FROM care_plan_revision WHERE id=?",String.class,next));assertEquals(old,query.detail(DOCTOR,plan).get("currentRevisionId"));
        assertEquals(frozen,f.jdbc().queryForObject("SELECT draft_json FROM care_plan_revision WHERE id=?",String.class,old));assertEquals(1,count("care_plan_notification"));assertEquals(3,count("care_plan_command"));
        failQueue=false;publish(DOCTOR,plan,next,confirmation,k,2);assertEquals(2,count("care_plan_notification"));assertEquals("SUPERSEDED",f.jdbc().queryForObject("SELECT status FROM care_plan_action WHERE revision_id=? AND ordinal=2",String.class,old));
    }
    @Test void queueFailureRollsBackCancelAndClose() {
        Map<String,Object>d=create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));publish(DOCTOR,plan,rev,map(),key(),0);failQueue=true;
        assertThrows(IllegalStateException.class,()->transition(DOCTOR,plan,"CANCEL","Reason",key(),1));assertEquals("OPEN",f.jdbc().queryForObject("SELECT status FROM care_plan_action",String.class));
        f.jdbc().update("UPDATE care_plan_action SET status='CONFIRMED'");assertThrows(IllegalStateException.class,()->transition(DOCTOR,plan,"CLOSE",null,key(),1));
        assertEquals("ACTIVE",f.jdbc().queryForObject("SELECT lifecycle FROM doctor_care_plan WHERE id=?",String.class,plan));assertEquals(1,count("care_plan_notification"));assertEquals(2,count("care_plan_event"));assertEquals(2,count("care_plan_command"));
    }
    @Test void lifecycleAuditEventsRequireCurrentClinicalAuthority() {
        Map<String,Object>d=create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));publish(DOCTOR,plan,rev,map(),key(),0);
        CarePlanEventStore store=new CarePlanEventStore(f.jdbc(),auth,properties);
        for(String type:new String[]{"PLAN_PUBLISHED","REVISION_PUBLISHED","PLAN_CANCELLED","PLAN_CLOSED"})error(403,()->tx.execute(s->store.append(PATIENT,plan,rev,null,OWNER,type,map())));
        assertEquals(2,count("care_plan_event"));
    }
    @Test void lifecycleWritesRequireEffectiveReadCommittedBeforeAuthorization() throws Exception {
        for(String method:new String[]{"publish","transitionPlan"}) {
            Method m=lifecycleMethod(method);Transactional annotation=m.getAnnotation(Transactional.class);assertNotNull(annotation);assertEquals(Isolation.READ_COMMITTED,annotation.isolation());
        }
        TransactionTemplate rr=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc().getDataSource()));rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        for(String method:new String[]{"publish","transitionPlan"})assertThrows(IllegalStateException.class,()->rr.execute(s->method.equals("publish")?invoke("publish",DOCTOR,999L,999L,map(),key(),0L):invoke("transitionPlan",DOCTOR,999L,"CANCEL","Reason",key(),0L)));
        assertEquals(0,count("care_plan_command"));assertEquals(1,count("doctor_care_plan"));
    }
    @Test void outerFailureAfterSuccessfulPublicationRollsBackCompletedCommandAndOutbox() {
        Map<String,Object>d=create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));String k=key();
        assertThrows(IllegalStateException.class,()->tx.execute(s->{
            Map<String,Object>result=service.publish(DOCTOR,plan,rev,map(),k,0);assertEquals("ACTIVE",result.get("lifecycle"));
            assertEquals(1,count("care_plan_notification"));assertEquals(2,count("care_plan_command"));
            assertNotNull(f.jdbc().queryForObject("SELECT result_json FROM care_plan_command WHERE command_key=?",String.class,k));
            throw new IllegalStateException("Synthetic failure after successful command");
        }));
        assertEquals(0,count("care_plan_action"));assertEquals(0,count("care_plan_notification"));assertEquals(1,count("care_plan_command"));assertEquals(1,count("care_plan_event"));
        assertEquals("DRAFT",f.jdbc().queryForObject("SELECT lifecycle FROM doctor_care_plan WHERE id=?",String.class,plan));
        publish(DOCTOR,plan,rev,map(),k,0);assertEquals(1,count("care_plan_notification"));
    }
    @Test void caughtAuthorizationFailureAfterEnqueueCannotCommitLifecycle() {
        Map<String,Object>d=create(body(1));long plan=id(d),rev=num(d.get("draftRevisionId"));String k=key();
        CarePlanNotificationQueue revokeAfterEnqueue=event->{
            enqueue(event);
            // A genuinely independent connection commits revocation while the business transaction is open.
            try(java.sql.Connection independent=f.jdbc().getDataSource().getConnection();java.sql.PreparedStatement statement=independent.prepareStatement("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9 AND patient_id=1")){
                assertTrue(independent.getAutoCommit());assertEquals(1,statement.executeUpdate());
            }catch(java.sql.SQLException e){throw new IllegalStateException(e);}
        };
        service=new CarePlanService(f.jdbc(),auth,properties,query,new CarePlanCommandStore(f.jdbc(),properties),new CarePlanEventStore(f.jdbc(),auth,properties),revokeAfterEnqueue);
        assertThrows(RuntimeException.class,()->tx.execute(s->{
            CarePlanException denied=assertThrows(CarePlanException.class,()->service.publish(DOCTOR,plan,rev,map(),k,0));assertEquals(403,denied.getStatus());
            return null; // Catching the operation error must not turn an unauthorized mutation into a commit.
        }));
        assertEquals("REVOKED",f.jdbc().queryForObject("SELECT status FROM doctor_patient_assignment WHERE doctor_user_id=9 AND patient_id=1",String.class));
        assertEquals("DRAFT",f.jdbc().queryForObject("SELECT lifecycle FROM doctor_care_plan WHERE id=?",String.class,plan));assertEquals(0,count("care_plan_action"));assertEquals(0,count("care_plan_notification"));assertEquals(1,count("care_plan_event"));assertEquals(1,count("care_plan_command"));
    }
    @Test void lifecycleProxyUsesReadCommittedWithoutChangingDatasourceDefault()throws Exception {
        IsolationProbeDataSource dataSource=new IsolationProbeDataSource(f.jdbc().getDataSource());
        try(AnnotationConfigApplicationContext context=transactionContext(dataSource)){
            CarePlanService proxied=context.getBean(CarePlanService.class);assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(proxied));
            Map<String,Object>d=proxied.createDraft(DOCTOR,body(1),key());proxied.publish(DOCTOR,id(d),num(d.get("draftRevisionId")),map(),key(),0);proxied.transitionPlan(DOCTOR,id(d),"CANCEL","Reason",key(),1);
            Map<String,Object>second=proxied.createDraft(DOCTOR,body(1),key());proxied.publish(DOCTOR,id(second),num(second.get("draftRevisionId")),map(),key(),0);
            f.jdbc().update("UPDATE care_plan_action SET status='CONFIRMED' WHERE plan_id=?",id(second));proxied.transitionPlan(DOCTOR,id(second),"CLOSE",null,key(),1);
            assertFalse(dataSource.mutationIsolation.isEmpty());for(int level:dataSource.mutationIsolation)assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,level);
            try(java.sql.Connection connection=dataSource.getConnection()){assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());}
        }
    }
    @Test void inheritedDefaultRepeatableReadRejectsLifecycleBeforeAnyQuery() {
        IsolationProbeDataSource dataSource=new IsolationProbeDataSource(f.jdbc().getDataSource());
        try(AnnotationConfigApplicationContext context=transactionContext(dataSource)){
            CarePlanService proxied=context.getBean(CarePlanService.class);
            for(int isolation:new int[]{TransactionDefinition.ISOLATION_DEFAULT,TransactionDefinition.ISOLATION_REPEATABLE_READ}){
                TransactionTemplate outer=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));outer.setIsolationLevel(isolation);
                for(String operation:new String[]{"publish","transition"}){
                    dataSource.statements.clear();
                    IllegalStateException failure=assertThrows(IllegalStateException.class,()->outer.execute(s->{
                        if(isolation==TransactionDefinition.ISOLATION_DEFAULT)assertNull(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                        return operation.equals("publish")?proxied.publish(DOCTOR,999,999,map(),key(),0):proxied.transitionPlan(DOCTOR,999,"CANCEL","Reason",key(),0);
                    }));
                    assertTrue(failure.getMessage().contains("READ_COMMITTED"));assertTrue(dataSource.statements.isEmpty());
                }
            }
            assertEquals(0,count("care_plan_command"));assertEquals(0,count("care_plan_event"));assertEquals(1,count("doctor_care_plan"));
        }
    }
    @org.springframework.context.annotation.Configuration @org.springframework.transaction.annotation.EnableTransactionManagement static class TransactionConfiguration {}
    private AnnotationConfigApplicationContext transactionContext(javax.sql.DataSource dataSource){
        AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("enabled",Collections.singletonMap("care-plan.enabled",true)));
        org.springframework.jdbc.core.JdbcTemplate observed=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        context.registerBean(javax.sql.DataSource.class,()->dataSource);context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,()->observed);
        context.registerBean("transactionManager",org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(dataSource));context.registerBean(CarePlanProperties.class,()->properties);
        context.registerBean(CarePlanNotificationQueue.class,()->event->{observed.update("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,status,created_at,updated_at) SELECT id,patient_id,7,?,'QUEUED',?,? FROM care_plan_event WHERE id=?","synthetic-"+event,Timestamp.from(f.now()),Timestamp.from(f.now()),event);});
        context.register(TransactionConfiguration.class,CarePlanAuthorizationService.class,CarePlanQueryService.class,CarePlanCommandStore.class,CarePlanEventStore.class,CarePlanService.class);context.refresh();return context;
    }
    private static final class IsolationProbeDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final List<Integer>mutationIsolation=new java.util.concurrent.CopyOnWriteArrayList<>();final List<String>statements=new java.util.concurrent.CopyOnWriteArrayList<>();
        IsolationProbeDataSource(javax.sql.DataSource target){super(target);}
        @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
            java.sql.Connection connection=super.getConnection();connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            return(java.sql.Connection)Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class[]{java.sql.Connection.class},(p,m,a)->{
                if(m.getName().equals("prepareStatement")&&a!=null&&a[0] instanceof String){String sql=(String)a[0];statements.add(sql);if(sql.matches("(?s)^(INSERT|UPDATE|DELETE).*"))mutationIsolation.add(connection.getTransactionIsolation());}
                try{return m.invoke(connection,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
        }
    }
    @Test void sixArgumentConstructorCannotSilentlyDropNotifications() {
        service=new CarePlanService(f.jdbc(),auth,properties,query,new CarePlanCommandStore(f.jdbc(),properties),new CarePlanEventStore(f.jdbc(),auth,properties));
        Map<String,Object>d=create(body(1));assertThrows(IllegalStateException.class,()->publish(DOCTOR,id(d),num(d.get("draftRevisionId")),map(),key(),0));
        assertEquals(0,count("care_plan_action"));assertEquals(1,count("care_plan_event"));
    }
    @Test void enabledSpringStartupRequiresQueueImplementation() {
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("enabled",Collections.singletonMap("care-plan.enabled",true)));
            context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,()->f.jdbc());context.registerBean(CarePlanProperties.class,()->properties);
            context.register(CarePlanAuthorizationService.class,CarePlanQueryService.class,CarePlanCommandStore.class,CarePlanEventStore.class,CarePlanService.class);
            RuntimeException error=assertThrows(RuntimeException.class,context::refresh);assertTrue(error.toString().contains("CarePlanNotificationQueue"));
        }
    }

    void installQueue() {service=new CarePlanService(f.jdbc(),auth,properties,query,new CarePlanCommandStore(f.jdbc(),properties),new CarePlanEventStore(f.jdbc(),auth,properties),this::enqueue);}
    void enqueue(long event) {
        f.jdbc().update("INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,status,created_at,updated_at) SELECT id,patient_id,7,?,'QUEUED',?,? FROM care_plan_event WHERE id=?","synthetic-"+event,Timestamp.from(f.now()),Timestamp.from(f.now()),event);
        if(failQueue)throw new IllegalStateException("Synthetic queue failure");
    }
    Method lifecycleMethod(String name){try{return name.equals("publish")?CarePlanService.class.getMethod(name,long.class,long.class,long.class,Map.class,String.class,long.class):CarePlanService.class.getMethod(name,long.class,long.class,String.class,String.class,String.class,long.class);}catch(NoSuchMethodException e){throw new AssertionError("Missing lifecycle operation: "+name,e);}}
    @SuppressWarnings("unchecked") Map<String,Object>invoke(String name,Object...args){try{return(Map<String,Object>)lifecycleMethod(name).invoke(service,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof RuntimeException)throw(RuntimeException)cause;throw new AssertionError(cause);}catch(IllegalAccessException e){throw new AssertionError(e);}}
    Map<String,Object>publish(long actor,long plan,long revision,Map<String,Object>confirmation,String key,long version){return tx.execute(s->{observeConnection();return service.publish(actor,plan,revision,confirmation,key,version);});}
    Map<String,Object>transition(long actor,long plan,String action,String reason,String key,long version){return tx.execute(s->{observeConnection();return service.transitionPlan(actor,plan,action,reason,key,version);});}
    void observeConnection(){java.sql.Connection connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(f.jdbc().getDataSource());try{connectionObserver.accept(connection);}finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,f.jdbc().getDataSource());}}
    Map<String,Object>create(Map<String,Object>b){return tx.execute(s->service.createDraft(DOCTOR,b,key()));}
    @SuppressWarnings("unchecked") Map<String,Object>confirmation(long plan){Map<String,Object>impact=(Map<String,Object>)query.detail(DOCTOR,plan).get("revisionImpact");return map("currentRevisionId",impact.get("currentRevisionId"),"supersededActionDigest",impact.get("digest"));}
    void grantFamily(){f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,granted_by) VALUES(1,8,'FAMILY','WRITE','CARE_PLAN','ACTIVE',7)");}
    int count(String table){return f.jdbc().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    static Map<String,Object>body(int count){List<Map<String,Object>>actions=new ArrayList<>();for(int i=1;i<=count;i++)actions.add(map("ordinal",i,"instruction","Synthetic action "+i,"dueAt","2026-10-03T06:00:00Z","assignedUserId",OWNER));return map("patientId",PATIENT,"title","Synthetic plan","instructions","Synthetic instruction","planType","FOLLOW_UP","actions",actions);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>>actions(Map<String,Object>b){return(List<Map<String,Object>>)b.get("actions");}
    static String key(){return UUID.randomUUID().toString();}static long id(Map<String,Object>b){return num(b.get("id"));}static long num(Object b){return((Number)b).longValue();}
    static Map<String,Object>map(Object...kv){Map<String,Object>b=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)b.put((String)kv[i],kv[i+1]);return b;}
    static void error(int status,Runnable action){assertEquals(status,assertThrows(CarePlanException.class,action::run).getStatus());}
}
