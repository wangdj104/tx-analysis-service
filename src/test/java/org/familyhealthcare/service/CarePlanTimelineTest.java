package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.familyhealthcare.service.CarePlanLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Current authority and read-only projection, no persisted clinical copies or real notifications. */
class CarePlanTimelineTest {
    CarePlanLifecycleTest h;
    Object projector;
    HealthTimelineService timeline;
    HealthEventMapper original;
    long plan,revision,event;
    @BeforeEach void setup() throws Exception {
        h=new CarePlanLifecycleTest();h.setup();
        Class<?> type=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.service.careplan.CarePlanTimelineProjector"));
        projector=type.getConstructor(org.springframework.jdbc.core.JdbcTemplate.class,CarePlanAuthorizationService.class,CarePlanProperties.class,CarePlanQueryService.class).newInstance(h.f.jdbc(),h.auth,h.properties,h.query);
        Map<String,Object>d=h.create(body(1));plan=id(d);revision=num(d.get("draftRevisionId"));event=num(h.publish(DOCTOR,plan,revision,map(),key(),0).get("eventId"));
        timeline=new HealthTimelineService();original=mock(HealthEventMapper.class);ReflectionTestUtils.setField(timeline,"events",original);
        ReflectionTestUtils.setField(timeline,"measurements",mock(BpSelfMonitorRecordMapper.class));ReflectionTestUtils.setField(timeline,"dialysis",mock(DialysisRecordMapper.class));
        ReflectionTestUtils.setField(timeline,"intakes",mock(MedicationIntakeMapper.class));ReflectionTestUtils.setField(timeline,"logs",mock(MedicationLogMapper.class));ReflectionTestUtils.setField(timeline,"medications",mock(MedicationMapper.class));
        ReflectionTestUtils.setField(timeline,"carePlans",projector);
    }
    @AfterEach void close() throws Exception {if(h!=null)h.close();}
    @Test void revokedTimelineDoesNotRevealSnapshot() {
        h.grantFamily();h.f.as(FAMILY);assertTrue(timeline.list(PATIENT,null,null,100).stream().anyMatch(e->"CARE_PLAN_EVENT".equals(e.getSourceType())));
        HealthEvent manual=manual();when(original.selectList(any())).thenReturn(Collections.singletonList(manual));
        h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");
        List<HealthEvent> events=timeline.list(PATIENT,null,null,100);
        assertTrue(events.stream().noneMatch(e -> "CARE_PLAN_EVENT".equals(e.getSourceType())));assertTrue(events.contains(manual));
    }
    @Test void sameEventIdInDifferentSourceDoesNotCollide() throws Exception {
        h.f.as(OWNER);HealthEvent manual=manual();manual.setId(event);manual.setSourceId(event);when(original.selectList(any())).thenReturn(Collections.singletonList(manual));
        List<HealthEvent> events=timeline.list(PATIENT,null,null,100);assertEquals(2,events.size());
        HealthEvent care=events.stream().filter(e->"CARE_PLAN_EVENT".equals(e.getSourceType())).findFirst().get();assertEquals(event,care.getSourceId());assertEquals(plan,carePlanId(care));assertNull(carePlanId(manual));
        com.baomidou.mybatisplus.annotation.TableField field=HealthEvent.class.getDeclaredField("carePlanId").getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);assertNotNull(field);assertFalse(field.exist());
        verify(original,never()).insert(any());assertEquals(0,h.f.jdbc().queryForObject("SELECT COUNT(*) FROM health_event",Integer.class));
    }
    @Test void timelineNeverIncludesDraftPrivateNotesOrRestrictedEvidenceBody() {
        h.grantFamily();h.f.jdbc().update("INSERT INTO medical_record(id,user_id,patient_id,patient_name,record_type,remark) VALUES(101,7,1,'Synthetic Patient','Synthetic restricted diagnosis','Synthetic secret')");
        long receipt=h.tx.execute(s->new CarePlanEventStore(h.f.jdbc(),h.auth,h.properties).append(PATIENT,plan,revision,action(),OWNER,"RECEIPT_SUBMITTED",map("note","Synthetic private note","occurredAt",h.f.now().toString(),"entryMode","SELF","evidence",Collections.singletonList(map("sourceType","MEDICAL_RECORD","sourceId",101)))));
        h.tx.execute(s->new CarePlanEventStore(h.f.jdbc(),h.auth,h.properties).append(PATIENT,plan,revision,null,OWNER,"SYNTHETIC_PRIVATE",map("note","Synthetic unknown secret")));
        h.f.as(FAMILY);List<HealthEvent> projected=list(FAMILY,null,null,100);
        assertEquals(2,projected.size());assertTrue(projected.stream().anyMatch(e->e.getSourceId()==receipt));
        for(HealthEvent e:projected){assertFalse(e.getEventType().startsWith("DRAFT_"));String text=e.getTitle()+e.getSummary();assertFalse(text.contains("Synthetic private note"));assertFalse(text.contains("Synthetic secret"));assertFalse(text.contains("diagnosis"));assertFalse(text.contains("unknown secret"));}
        assertTrue(feed(FAMILY).stream().noneMatch(e->"SYNTHETIC_PRIVATE".equals(e.get("eventType"))));
        Map<String,Object>draft=h.tx.execute(s->h.service.createRevision(DOCTOR,plan,key(),1));assertNotNull(draft.get("draftRevisionId"));assertEquals(2,list(FAMILY,null,null,100).size());
    }
    @Test void eventFeedReprojectsEvidenceAndChecksCurrentAccess() {
        h.grantFamily();h.f.jdbc().update("INSERT INTO health_measurement(id,recorded_by,patient_id,metric_type,value_primary,unit,measured_at) VALUES(101,7,1,'WEIGHT',70,'kg',?)",Timestamp.from(h.f.now()));
        h.tx.execute(s->new CarePlanEventStore(h.f.jdbc(),h.auth,h.properties).append(PATIENT,plan,revision,action(),OWNER,"RECEIPT_SUBMITTED",map("note","Synthetic receipt","occurredAt",h.f.now().toString(),"entryMode","SELF","evidence",Collections.singletonList(map("sourceType","MEASUREMENT","sourceId",101)))));
        List<Map<String,Object>> feed=feed(FAMILY);Map<String,Object>receipt=feed.stream().filter(e->"RECEIPT_SUBMITTED".equals(e.get("eventType"))).findFirst().get();
        Map<?,?> ref=(Map<?,?>)((List<?>)receipt.get("evidence")).get(0);assertEquals(true,ref.get("restricted"));assertFalse(ref.containsKey("title"));
        h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN,MEASUREMENTS' WHERE grantee_user_id=8");ref=(Map<?,?>)((List<?>)feed(FAMILY).stream().filter(e->"RECEIPT_SUBMITTED".equals(e.get("eventType"))).findFirst().get().get("evidence")).get(0);assertEquals(false,ref.get("restricted"));assertEquals("WEIGHT",ref.get("title"));
        h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");error(403,()->feed(FAMILY));
        h.f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9");error(403,()->feed(DOCTOR));
    }
    @Test void eventFeedPagesUseStableIdsAndCurrentVisibilityBoundCursors() {
        h.grantFamily();
        for(int i=0;i<2;i++)h.tx.execute(s->new CarePlanEventStore(h.f.jdbc(),h.auth,h.properties).append(PATIENT,plan,revision,action(),OWNER,"RECEIPT_SUBMITTED",map("note","Synthetic receipt","occurredAt",h.f.now().toString(),"entryMode","SELF")));
        Map<String,Object>first=page(FAMILY,plan,null,1);List<?>items=(List<?>)first.get("items");assertEquals(1,items.size());String cursor=(String)first.get("nextCursor");assertNotNull(cursor);
        Map<String,Object>second=page(FAMILY,plan,cursor,1);long a=num(((Map<?,?>)items.get(0)).get("id")),b=num(((Map<?,?>)((List<?>)second.get("items")).get(0)).get("id"));assertTrue(a>b);
        error(400,()->page(OWNER,plan,cursor,1));error(400,()->page(FAMILY,plan,"not-a-cursor",1));error(400,()->page(FAMILY,plan,null,101));
        String clinical=(String)page(DOCTOR,plan,null,1).get("nextCursor");assertNotNull(clinical);
        h.f.jdbc().update("INSERT INTO sys_user_role(user_id,role_id) SELECT 9,id FROM sys_role WHERE role_code='family'");
        h.f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,granted_by) VALUES(1,9,'FAMILY','READ','CARE_PLAN','ACTIVE',7)");
        h.f.jdbc().update("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9");error(400,()->page(DOCTOR,plan,clinical,1));
        h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");error(403,()->page(FAMILY,plan,cursor,1));
    }
    @Test void dateBoundsUseUtcAndOffSkipsAllNewTables() {
        assertEquals(1,list(OWNER,LocalDate.of(2026,10,3),LocalDate.of(2026,10,3),50).size());assertTrue(list(OWNER,LocalDate.of(2026,10,4),null,50).isEmpty());
        error(400,()->list(OWNER,LocalDate.of(2026,10,4),LocalDate.of(2026,10,3),50));error(400,()->list(OWNER,null,null,0));
        ReflectionTestUtils.setField(h.properties,"enabled",false);h.f.jdbc().execute("DROP TABLE care_plan_notification,care_plan_evidence,care_plan_command,care_plan_event,care_plan_action,care_plan_revision,care_nurse_assignment CASCADE");
        assertTrue(list(OWNER,null,null,100).isEmpty());assertTrue(timeline.list(PATIENT,null,null,100).isEmpty());
    }
    @Test void eventsAreNeverReadFromGeneralHealthEventCopies() {
        HealthEvent forged=manual();forged.setSourceType("CARE_PLAN_EVENT");forged.setSummary("Synthetic forbidden copied snapshot");when(original.selectList(any())).thenReturn(Collections.singletonList(forged));
        h.grantFamily();h.f.as(FAMILY);h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");assertTrue(timeline.list(PATIENT,null,null,100).isEmpty());
    }
    private HealthEvent manual(){HealthEvent e=new HealthEvent();e.setId(12L);e.setPatientId(PATIENT);e.setSourceType("MANUAL");e.setTitle("Synthetic general note");e.setEventDate(LocalDate.of(2026,10,3));e.setEventTime("04:00");return e;}
    private long action(){return h.f.jdbc().queryForObject("SELECT id FROM care_plan_action WHERE plan_id=?",Long.class,plan);}
    private Object invoke(String name,Class<?>[]types,Object...args){try{return projector.getClass().getMethod(name,types).invoke(projector,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException)throw(RuntimeException)e.getCause();throw new AssertionError(e.getCause());}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked")private List<HealthEvent> list(long actor,LocalDate from,LocalDate to,int limit){return(List<HealthEvent>)invoke("list",new Class<?>[]{long.class,long.class,LocalDate.class,LocalDate.class,int.class},actor,PATIENT,from,to,limit);}
    @SuppressWarnings("unchecked")private List<Map<String,Object>>feed(long actor){return(List<Map<String,Object>>)((Map<?,?>)invoke("events",new Class<?>[]{long.class,long.class,String.class,int.class},actor,plan,null,50)).get("items");}
    @SuppressWarnings("unchecked")private Map<String,Object>page(long actor,long id,String cursor,int limit){return(Map<String,Object>)invoke("events",new Class<?>[]{long.class,long.class,String.class,int.class},actor,id,cursor,limit);}
    private Long carePlanId(HealthEvent e)throws Exception{return(Long)HealthEvent.class.getMethod("getCarePlanId").invoke(e);}
}
