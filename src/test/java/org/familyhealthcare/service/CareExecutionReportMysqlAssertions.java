package org.familyhealthcare.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.familyhealthcare.controller.CareExecutionReportController;
import org.familyhealthcare.controller.CarePlanExceptionAdvice;
import org.familyhealthcare.service.careplan.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.familyhealthcare.service.careplan.CareExecutionReportContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Native report assertions, called ONLY inside the existing guarded three-schema MySQL acceptance.
 * No @Test entry point, schema creation, alternate launcher, or fallback database is provided here.
 */
final class CareExecutionReportMysqlAssertions {
    private static final long OWNER=7001, FAMILY=7002, DOCTOR=7003;
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static long nextPatient=7950;
    private final CarePlanMysqlIntegrationTest.NativeServices h;
    private final Observer observer;
    private CareExecutionReportMysqlAssertions(CarePlanMysqlIntegrationTest.NativeServices h){this.h=h;this.observer=h.reportObserver;}

    /** Minimal overload keeps the existing guarded source and already-proxied services together.
     * Rebuilding services from only a DataSource would create a second acceptance context.
     */
    static void verify(DataSource source,CarePlanMysqlIntegrationTest.NativeServices h)throws Exception {
        assertSame(source,h.jdbc.getDataSource(),"Reuse exactly the guarded NativeServices datasource");
        CareExecutionReportMysqlAssertions acceptance=new CareExecutionReportMysqlAssertions(h);
        acceptance.consistentSnapshotAcrossConcurrentRevision();
        acceptance.freshPermissionAfterSnapshotRevocation();
        acceptance.reportQueriesStayBounded();
        acceptance.realRendererUsesConfiguredFont();
        System.out.println("Native report acceptance: five committed lifecycle races; independent RR/RC and first-read UTC; before/after-buffer revocation/expiry; bounded profiles; real EN/zh-CN PDFs verified");
    }

    /** Every writer executes actual proxied commands in one independent, committed native RC transaction. */
    void consistentSnapshotAcrossConcurrentRevision()throws Exception {
        for(String mutation:Arrays.asList("RECEIPT","RETURN","REVISION","CANCEL","CLOSE")) {
            Fixture f=fixture(1,false);
            if(mutation.equals("RETURN")||mutation.equals("CLOSE")||mutation.equals("REVISION")) submit(f);
            if(mutation.equals("CLOSE")||mutation.equals("REVISION")) h.actions.review(DOCTOR,f.action(),map("decision","CONFIRM","note","Synthetic native reviewed"),key(),version(f));
            CareExecutionReport before=preview(OWNER,f);
            observer.begin(Boundary.SNAPSHOT);
            CareExecutionReport frozen=race(()->preview(OWNER,f),()->h.transaction(()->{
                if(mutation.equals("RECEIPT"))submit(f);
                else if(mutation.equals("RETURN"))h.actions.review(DOCTOR,f.action(),map("decision","RETURN","note","Synthetic native supplement"),key(),version(f));
                else if(mutation.equals("REVISION"))publishRevision(f);
                else h.plans.transitionPlan(DOCTOR,f.plan,mutation,mutation.equals("CANCEL")?"Synthetic native cancellation":null,key(),version(f));
                return null;
            }),()->{
                if(mutation.equals("RECEIPT"))assertEquals("SUBMITTED",actionState(f));
                else if(mutation.equals("RETURN"))assertEquals("OPEN",actionState(f));
                else if(mutation.equals("REVISION"))assertNotEquals(f.revision,h.jdbc.queryForObject("SELECT current_revision_id FROM doctor_care_plan WHERE id=?",Long.class,f.plan));
                else assertEquals(mutation.equals("CLOSE")?"COMPLETED":"CANCELLED",h.jdbc.queryForObject("SELECT lifecycle FROM doctor_care_plan WHERE id=?",String.class,f.plan));
            });
            observer.assertBodyAndFreshReads();
            observer.assertDistinctCommittedWriter();
            assertEquals(logicalBody(before),logicalBody(frozen),"Concurrent "+mutation+" must not mix any count, detail, historical revision, or lifecycle flag");
            assertEquals(observer.firstSnapshotTime.get(),frozen.getMetadata().getCurrentAsOf(),"currentAsOf is the UTC value returned by the first real patient table read");
            observer.end();
            CareExecutionReport fresh=preview(OWNER,f);
            assertNotEquals(logicalBody(frozen),logicalBody(fresh),"A subsequent report must see the committed "+mutation);
            assertEquals(fresh.getCurrentActions().size(),fresh.getCurrentSummary().getTotal());
            assertEquals(fresh.getCurrentSummary().getTotal(),fresh.getCurrentSummary().getOpen()+fresh.getCurrentSummary().getNeedsHelp()+fresh.getCurrentSummary().getSubmitted()+fresh.getCurrentSummary().getConfirmed());
            if(mutation.equals("RECEIPT")){assertEquals(1,fresh.getCurrentSummary().getSubmitted());assertEquals(0,fresh.getCurrentSummary().getOverdue());assertNotNull(fresh.getCurrentActions().get(0).getReviewWaitingSince());}
            if(mutation.equals("RETURN")){assertEquals(1,fresh.getCurrentSummary().getNeedsSupplement());assertNotNull(fresh.getCurrentActions().get(0).getLatestReturn());}
            if(mutation.equals("REVISION")){
                assertEquals(2,fresh.getCurrentActions().get(0).getRevisionNo());
                assertEquals("OPEN",fresh.getCurrentActions().get(0).getStatus());
                assertNull(fresh.getCurrentActions().get(0).getLatestReceipt());assertNull(fresh.getCurrentActions().get(0).getLatestReview());
                assertTrue(fresh.getPeriodEvents().stream().anyMatch(e->e.getRevisionId()==f.revision&&"RECEIPT_CONFIRMED".equals(e.getEventType())&&"CONFIRMED".equals(e.getActionStatusAfterEvent())&&!e.isRevisionIsCurrentAtGeneration()));
                assertEquals("Synthetic native replacement instructions",fresh.getCurrentActions().get(0).getInstructions());
                assertTrue(fresh.getPeriodEvents().stream().anyMatch(e->e.getRevisionId()==f.revision&&!e.isRevisionIsCurrentAtGeneration()&&"Synthetic native report instructions".equals(e.getInstructions())));
            }
            if(mutation.equals("CANCEL")||mutation.equals("CLOSE")){assertEquals(0,fresh.getCurrentSummary().getTotal());assertFalse(fresh.getPeriodEvents().isEmpty());}
        }
        // A caller's RC transaction must not become the projector's transaction, even without the service executor.
        Fixture f=fixture(1,false);Request directRequest=request(f,null,"en");observer.begin(Boundary.NONE);observer.directThread=Thread.currentThread();
        h.transaction(()->{
            String caller=h.jdbc.queryForObject("SELECT CONNECTION_ID()",String.class);
            CareExecutionReportProjector.Projection projection=h.reportProjector.project(OWNER,directRequest,h.reportAccess.inspect(OWNER,directRequest),CareExecutionReportBudget.start(REQUEST_TIMEOUT));
            assertEquals(1,projection.getReport().getCurrentSummary().getTotal());
            assertFalse(observer.bodyIds().contains(caller),"REQUIRES_NEW must suspend the caller's RC connection");
            assertEquals("READ-COMMITTED",h.jdbc.queryForObject("SELECT @@SESSION.transaction_isolation",String.class));
            return null;
        });
        observer.assertBodyAndFreshReads();observer.end();
    }

    /** A real buffered renderer is used: bytes exist internally at AFTER_RENDER but never reach HTTP. */
    void freshPermissionAfterSnapshotRevocation()throws Exception {
        for(Boundary boundary:Arrays.asList(Boundary.SNAPSHOT,Boundary.AFTER_RENDER)) {
            for(String loss:Arrays.asList("BASE_REVOKED","BASE_EXPIRED","EVIDENCE_REVOKED","EVIDENCE_EXPIRED","QUESTIONS_REVOKED","QUESTIONS_EXPIRED")) {
                Fixture f=fixture(1,true);submit(f);grant(f,"FAMILY","CARE_PLAN");
                if(loss.startsWith("EVIDENCE"))grant(f,"GUARDIAN","MEASUREMENTS");
                if(loss.startsWith("QUESTIONS"))grant(f,"GUARDIAN","");
                CareExecutionReport allowed=preview(FAMILY,f);
                if(loss.startsWith("EVIDENCE"))assertFalse(allowed.getCurrentActions().get(0).getEvidence().get(0).isRestricted());
                if(loss.startsWith("QUESTIONS")){assertEquals("AVAILABLE",allowed.getQuestionsAvailability());assertEquals(1,allowed.getQuestions().size());}
                String format=loss.equals("BASE_EXPIRED")?"actions_csv":loss.equals("EVIDENCE_REVOKED")?"events_csv":loss.endsWith("EXPIRED")?"pdf":"html";
                observer.begin(boundary);
                MockHttpServletResponse denied=race(()->httpExport(f,FAMILY,format),()->h.transaction(()->{
                    String role=loss.startsWith("BASE")?"FAMILY":"GUARDIAN";
                    if(loss.endsWith("EXPIRED"))h.jdbc.update("UPDATE care_access_grant SET expires_at=UTC_TIMESTAMP()+INTERVAL 1 SECOND WHERE patient_id=? AND grantee_user_id=? AND grantee_role=?",f.patient,FAMILY,role);
                    else h.jdbc.update("UPDATE care_access_grant SET status='REVOKED' WHERE patient_id=? AND grantee_user_id=? AND grantee_role=?",f.patient,FAMILY,role);
                    return null;
                }),()->verifyLossCommitted(f,loss));
                assertEquals(loss.startsWith("BASE")?403:409,denied.getStatus());
                JsonNode error=JSON.readTree(denied.getContentAsByteArray());
                assertEquals(loss.startsWith("BASE")?"ACCESS_DENIED":"REPORT_ACCESS_CHANGED",error.path("data").path("errorCode").asText());
                assertEquals(1,error.path("data").size());assertNull(denied.getHeader("Content-Disposition"));
                assertTrue(denied.getContentType().contains("application/json"));assertTrue(denied.getContentAsByteArray().length<500);
                assertFalse(denied.getContentAsString().contains("Synthetic native"));assertFalse(denied.getContentAsString().contains("<html"));
                assertEquals(boundary==Boundary.AFTER_RENDER?1:0,observer.renderCount.get(),"Pre-render refusal must not render; post-render refusal must discard genuinely prepared bytes");
                if(boundary==Boundary.AFTER_RENDER)assertTrue(observer.renderedBytes.get()>100);
                observer.assertBodyAndFreshReads();observer.assertDistinctCommittedWriter();observer.end();
                if(!loss.startsWith("BASE")){
                    CareExecutionReport restricted=preview(FAMILY,f);
                    if(loss.startsWith("EVIDENCE"))assertTrue(restricted.getCurrentActions().get(0).getEvidence().get(0).isRestricted());
                    else {assertEquals("NOT_AUTHORIZED",restricted.getQuestionsAvailability());assertTrue(restricted.getQuestions().isEmpty());}
                }
            }
        }
    }

    void reportQueriesStayBounded()throws Exception {
        for(int count:new int[]{1,50}) {
            Fixture f=fixture(count,true);grant(f,"FAMILY","CARE_PLAN");
            for(String format:new String[]{null,"actions_csv","events_csv"}) {
                observer.begin(Boundary.NONE);
                if(format==null){CareExecutionReport r=preview(FAMILY,f);assertEquals(count,r.getCurrentSummary().getTotal());assertEquals("NOT_AUTHORIZED",r.getQuestionsAvailability());assertTrue(r.getQuestions().isEmpty());}
                else assertTrue(h.reports.export(FAMILY,request(f,format,"en")).getContent().length>0);
                observer.assertBodyAndFreshReads();List<Query> body=observer.bodyQueries();
                List<String> sql=body.stream().map(q->q.sql).collect(Collectors.toList());
                assertTrue(sql.stream().noneMatch(s->s.contains("care_item")||s.contains("medication")||s.contains("medical_history")||s.contains("select *")),"Narrow reporting never loads questions, medication, history or full entities");
                for(String s:sql)if(s.contains(" from health_measurement ")||s.contains(" from medical_record "))assertTrue(s.matches("select count\\(\\*\\) from (health_measurement|medical_record) where id=\\? and patient_id=\\?"),"Only existing narrow evidence-ownership COUNT probes are permitted under CARE_PLAN-only");
                assertEquals("events_csv".equals(format)?0:count,sql.stream().filter(s->s.startsWith("select count(*) from health_measurement where id=? and patient_id=?")).count(),"Only one existing narrow ownership probe per included action reference; no optional clinical body or aggregate count");
                if(!"events_csv".equals(format)){
                    assertEquals(1,sql.stream().filter(s->s.startsWith("select a.id from doctor_care_plan")&&s.contains("limit 1001")).count());
                    assertEquals(1,sql.stream().filter(s->s.startsWith("select max(e.id)")&&s.contains("group by e.action_id,e.event_type")).count(),"Latest summaries use one grouped ID query, not a history-per-action loop");
                    assertEquals(1,sql.stream().filter(s->s.startsWith("select a.id,a.plan_id")).count(),"Small source texts fit one bounded action-body batch regardless of 1 or 50 actions");
                } else assertTrue(sql.stream().noneMatch(s->s.startsWith("select a.id,a.plan_id")||s.startsWith("select max(e.id)")||s.contains("limit 1001")));
                if(!"actions_csv".equals(format))assertEquals(1,sql.stream().filter(s->s.contains("e.recorded_at>=? and e.recorded_at<?")&&s.contains("limit 5001")).count());
                else assertTrue(sql.stream().noneMatch(s->s.contains("e.recorded_at>=?")),"Actions CSV must not load the period event set");
                assertTrue(sql.stream().anyMatch(s->s.contains("octet_length(")),"Native byte preflight must execute before clinical body allocation");
                int preflight=index(sql,"octet_length(");int details=index(sql,"select a.id,a.plan_id");if(details>=0)assertTrue(preflight<details);
                observer.end();
            }
            // Native optimizer evidence: scoped keys are available; no tiny-fixture forced-index assertion.
            String eventPlan=JSON.writeValueAsString(h.jdbc.queryForList("EXPLAIN SELECT id FROM care_plan_event WHERE patient_id=? AND recorded_at>=? AND recorded_at<? ORDER BY recorded_at,id LIMIT 5001",f.patient,Timestamp.from(h.now.get().minusSeconds(86400)),Timestamp.from(h.now.get().plusSeconds(86400))));
            assertTrue(eventPlan.contains("idx_care_plan_event_patient_time"),"Patient/time index must be available to the native period query");
            assertEquals("3",h.jdbc.queryForObject("SELECT OCTET_LENGTH(?)",String.class,"中"),"MySQL utf8mb4 storage budget counts UTF-8 bytes");
        }
        Fixture f=fixture(1,false);
        observer.begin(Boundary.NONE);CareExecutionReport authorized=preview(OWNER,f);
        assertEquals("AVAILABLE",authorized.getQuestionsAvailability());assertEquals(1,authorized.getQuestions().size());
        assertTrue(observer.bodyQueries().stream().anyMatch(q->q.sql.contains("from care_item")&&q.sql.contains("limit 201")),"Authorized questions use the bounded 201-ID probe");observer.end();
        // Full question authority must not mask a regression in CSV-specific section omission.
        for(String format:Arrays.asList("actions_csv","events_csv")) {
            observer.begin(Boundary.NONE);
            try {
                assertTrue(h.reports.export(OWNER,request(f,format,"en")).getContent().length>0);
                observer.assertBodyAndFreshReads();
                assertTrue(observer.queries.stream().noneMatch(q->q.sql.contains("care_item")),"Even an authorized owner CSV must never query question bodies, IDs or counts");
                List<String> sql=observer.bodyQueries().stream().map(q->q.sql).collect(Collectors.toList());
                if("actions_csv".equals(format)) {
                    assertEquals(1,sql.stream().filter(q->q.startsWith("select a.id,a.plan_id")).count());
                    assertTrue(sql.stream().noneMatch(q->q.contains("e.recorded_at>=?")),"Owner actions CSV must not load period events");
                } else {
                    assertEquals(1,sql.stream().filter(q->q.contains("e.recorded_at>=? and e.recorded_at<?")&&q.contains("limit 5001")).count());
                    assertTrue(sql.stream().noneMatch(q->q.startsWith("select a.id,a.plan_id")||q.startsWith("select max(e.id)")||q.contains("limit 1001")),"Owner events CSV must not load current actions or latest summaries");
                }
            } finally {observer.end();}
        }
    }

    void realRendererUsesConfiguredFont()throws Exception {
        String font=System.getenv("REPORT_PDF_FONT_PATH");assertNotNull(font,"Native report PDF acceptance requires the CI-prepared complete official font");assertFalse(font.trim().isEmpty());
        Path path=Paths.get(font).toAbsolutePath().normalize();
        assertEquals(Paths.get(Objects.requireNonNull(System.getenv("RUNNER_TEMP"),"Native report font requires the declared CI temporary directory")).resolve("care-report-font/wqy-microhei.ttf").toAbsolutePath().normalize(),path);
        System.out.println("Native report actualFontSHA256="+configuredFontSha256(path));
        Fixture f=fixture(1,false);
        for(String language:Arrays.asList("en","zh-CN")) {
            CareExecutionReportRenderer.Export pdf=h.reports.export(OWNER,request(f,"pdf",language));
            assertEquals("application/pdf",pdf.getContentType());assertTrue(pdf.getContent().length>1000);
            try(PDDocument doc=PDDocument.load(pdf.getContent())){
                assertTrue(doc.getNumberOfPages()>0);String text=new PDFTextStripper().getText(doc);
                assertTrue(text.contains("Synthetic native report"));assertTrue(text.contains("原文"));
                org.apache.pdfbox.rendering.PDFRenderer renderer=new org.apache.pdfbox.rendering.PDFRenderer(doc);
                for(int page=0;page<doc.getNumberOfPages();page++){
                    java.awt.image.BufferedImage image=renderer.renderImageWithDPI(page,72);
                    long marked=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)!=0xffffff)marked++;
                    assertTrue(marked>100,"Every native PDF page must really rasterize with visible content");
                }
            }
        }
    }

    /** Evidence integrity only: actual font parsing/glyph coverage remains the real renderer's job. */
    static String configuredFontSha256(Path path)throws Exception {
        final long max=30L*1024*1024;
        for(Path entry=path;entry!=null;entry=entry.getParent())assertFalse(Files.isSymbolicLink(entry),"Native report font and ancestors must not be symlinks");
        assertTrue(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS));assertTrue(Files.size(path)>0&&Files.size(path)<=max,"Native report font must be a bounded regular file");
        Path metadata=path.resolveSibling("font-preparation.json");assertFalse(Files.isSymbolicLink(metadata));assertTrue(Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS));assertTrue(Files.size(metadata)>0&&Files.size(metadata)<=4096);
        JsonNode preparation=JSON.readTree(Files.readAllBytes(metadata));assertTrue(preparation.path("faceIndex").isIntegralNumber());assertEquals(0,preparation.path("faceIndex").intValue());
        String expected=preparation.path("ttfSha256").asText();assertTrue(expected.matches("[a-f0-9]{64}"));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
        try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){bytes+=count;assertTrue(bytes<=max,"Native report font grew beyond the bounded limit");digest.update(buffer,0,count);}}
        StringBuilder actual=new StringBuilder();for(byte value:digest.digest())actual.append(String.format(Locale.ROOT,"%02x",value&255));
        assertEquals(expected,actual.toString(),"The actual native PDF renderer font must match the complete official face-0 preparation evidence");return actual.toString();
    }

    private Fixture fixture(int actions,boolean evidence) {
        long patient=++nextPatient;
        h.now.set(databaseNow(h.jdbc).minusSeconds(60));
        h.jdbc.update("INSERT INTO patient(id,name,user_id,remark) VALUES(?,? ,?,'Disposable native report acceptance')",patient,"Synthetic native report patient "+patient,OWNER);
        h.jdbc.update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,status) VALUES(?,?,'ACTIVE')",DOCTOR,patient);
        h.jdbc.update("INSERT INTO care_item(patient_id,user_id,kind,title,status,data_json) VALUES(?,?,'QUESTION','Synthetic native question','OPEN','{\"description\":\"Synthetic native question body\"}')",patient,OWNER);
        Long source=null;
        if(evidence){h.jdbc.update("INSERT INTO health_measurement(patient_id,metric_type,value_primary,unit,measured_at,recorded_by) VALUES(?,'WEIGHT',61.25,'kg',UTC_TIMESTAMP(),?)",patient,OWNER);source=h.jdbc.queryForObject("SELECT id FROM health_measurement WHERE patient_id=?",Long.class,patient);}
        Fixture f=new Fixture(patient,source,actions);Map<String,Object> draft=h.plans.createDraft(DOCTOR,body(f,false),key());
        f.plan=number(draft.get("id"));f.revision=number(draft.get("draftRevisionId"));h.plans.publish(DOCTOR,f.plan,f.revision,map(),key(),0);
        f.actions=h.jdbc.queryForList("SELECT id FROM care_plan_action WHERE plan_id=? ORDER BY ordinal",Long.class,f.plan);return f;
    }
    private Map<String,Object> body(Fixture f,boolean revision){List<Map<String,Object>> actions=new ArrayList<>();for(int i=1;i<=f.count;i++)actions.add(map("ordinal",i,"instruction","Synthetic native report 原文 action "+i,"dueAt",h.now.get().plusSeconds(86400).toString(),"assignedUserId",OWNER,"evidence",f.source==null?Collections.emptyList():Collections.singletonList(map("sourceType","MEASUREMENT","sourceId",f.source))));return map("patientId",f.patient,"title","Synthetic native report plan","instructions",revision?"Synthetic native replacement instructions":"Synthetic native report instructions","planType","FOLLOW_UP","actions",actions);}
    @SuppressWarnings("unchecked") private void publishRevision(Fixture f){Map<String,Object>d=h.plans.createRevision(DOCTOR,f.plan,key(),version(f));long revision=number(d.get("draftRevisionId"));h.plans.saveDraft(DOCTOR,f.plan,revision,body(f,true),key(),version(f));Map<String,Object> impact=(Map<String,Object>)h.queries.detail(DOCTOR,f.plan).get("revisionImpact");h.plans.publish(DOCTOR,f.plan,revision,map("currentRevisionId",f.revision,"supersededActionDigest",impact.get("digest")),key(),version(f));}
    private void submit(Fixture f){h.actions.submit(OWNER,f.action(),map("note","Synthetic native report receipt","occurredAt",h.now.get().minusSeconds(1).toString(),"entryMode","SELF","evidence",f.source==null?Collections.emptyList():Collections.singletonList(map("sourceType","MEASUREMENT","sourceId",f.source))),key(),version(f));}
    private void verifyLossCommitted(Fixture f,String loss) {
        String role=loss.startsWith("BASE")?"FAMILY":"GUARDIAN";
        if(loss.endsWith("EXPIRED")) {
            assertEquals(1,h.count("SELECT COUNT(*) FROM care_access_grant WHERE patient_id=? AND grantee_user_id=? AND grantee_role=? AND status='ACTIVE' AND expires_at IS NOT NULL",f.patient,FAMILY,role),"An independent connection must first observe the committed future expiry");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(h.count("SELECT COUNT(*) FROM care_access_grant WHERE patient_id=? AND grantee_user_id=? AND grantee_role=? AND expires_at<=UTC_TIMESTAMP()",f.patient,FAMILY,role)==0) {
                assertTrue(System.nanoTime()<deadline,"The native database clock must reach the committed grant expiry before resuming the report");
                try{TimeUnit.MILLISECONDS.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }
        } else assertEquals(1,h.count("SELECT COUNT(*) FROM care_access_grant WHERE patient_id=? AND grantee_user_id=? AND grantee_role=? AND status='REVOKED'",f.patient,FAMILY,role));
    }
    private void grant(Fixture f,String role,String modules){h.jdbc.update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,status,granted_by) VALUES(?,?,?,'READ',?,'ACTIVE',?)",f.patient,FAMILY,role,modules,OWNER);}
    private long version(Fixture f){return h.jdbc.queryForObject("SELECT lock_version FROM doctor_care_plan WHERE id=?",Long.class,f.plan);}
    private String actionState(Fixture f){return h.jdbc.queryForObject("SELECT status FROM care_plan_action WHERE id=?",String.class,f.action());}
    private CareExecutionReport preview(long actor,Fixture f){return h.reports.preview(actor,request(f,null,"en")).getReport();}
    private Request request(Fixture f,String format,String language){return parse(requestJson(f,format,language),format!=null,databaseNow(h.jdbc));}
    private String requestJson(Fixture f,String format,String language){return "{\"patientId\":"+f.patient+",\"timeZone\":\"UTC\",\"language\":\""+language+"\""+(format==null?"":",\"format\":\""+format+"\"")+"}";}
    private MockHttpServletResponse httpExport(Fixture f,long actor,String format){try{MockMvc mvc=MockMvcBuilders.standaloneSetup(new CareExecutionReportController(h.properties,h.context.getBeanProvider(CareExecutionReportService.class))).setControllerAdvice(new CarePlanExceptionAdvice()).build();return mvc.perform(post("/care-plans/reports/export").requestAttr("userId",actor).contentType("application/json").content(requestJson(f,format,"en"))).andReturn().getResponse();}catch(Exception failure){throw new AssertionError("Native buffered HTTP assertion failed",failure);}}
    private <T>T race(Supplier<T> reader,Runnable writer,Runnable verifyCommit)throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();Future<T> response=executor.submit(reader::get);
        try {
            assertTrue(observer.reached.await(10,TimeUnit.SECONDS),"The report must reach the actual post-execution/read or post-buffer checkpoint");
            writer.run();verifyCommit.run(); // independent autocommit visibility BEFORE the snapshot is released
            observer.committed.set(true);observer.release.countDown();return response.get(20,TimeUnit.SECONDS);
        } finally {observer.release.countDown();response.cancel(true);executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));observer.capture=false;}
    }
    private static JsonNode logicalBody(CareExecutionReport report){com.fasterxml.jackson.databind.node.ObjectNode body=JSON.valueToTree(report);body.remove("metadata");return body;}
    static Instant databaseNow(JdbcTemplate jdbc){return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,n)->rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant());}
    private static int index(List<String> values,String needle){for(int i=0;i<values.size();i++)if(values.get(i).contains(needle))return i;return -1;}
    private static long number(Object value){return ((Number)value).longValue();}
    private static String key(){return UUID.randomUUID().toString();}
    private static Map<String,Object> map(Object...values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static final class Fixture{final long patient;final Long source;final int count;long plan,revision;List<Long> actions;Fixture(long patient,Long source,int count){this.patient=patient;this.source=source;this.count=count;}long action(){return actions.get(0);}}

    enum Boundary {NONE,SNAPSHOT,AFTER_RENDER}
    static final class Query {
        final String sql,id,isolation;final boolean readOnly,autoCommit;final int timeout;
        Query(String sql,String id,String isolation,boolean readOnly,boolean autoCommit,int timeout){this.sql=sql;this.id=id;this.isolation=isolation;this.readOnly=readOnly;this.autoCommit=autoCommit;this.timeout=timeout;}
    }
    /** Transparent execution-level extension to the existing datasource proxy. Never records bind values. */
    static final class Observer {
        final List<Query> queries=new CopyOnWriteArrayList<>();final Set<String> writers=ConcurrentHashMap.newKeySet();
        final AtomicReference<Instant> firstSnapshotTime=new AtomicReference<>();final AtomicInteger renderCount=new AtomicInteger(),renderedBytes=new AtomicInteger();
        final AtomicBoolean first=new AtomicBoolean(),committed=new AtomicBoolean();
        volatile CountDownLatch reached=new CountDownLatch(0),release=new CountDownLatch(0);volatile Boundary boundary=Boundary.NONE;volatile boolean capture;volatile Thread directThread;
        void begin(Boundary boundary){queries.clear();writers.clear();firstSnapshotTime.set(null);renderCount.set(0);renderedBytes.set(0);first.set(true);committed.set(false);reached=new CountDownLatch(1);release=new CountDownLatch(1);this.boundary=boundary;capture=true;}
        void end(){capture=false;directThread=null;boundary=Boundary.NONE;release.countDown();}
        private boolean reader(){return Thread.currentThread().getName().startsWith("care-report-")||Thread.currentThread()==directThread;}
        Object statement(Statement statement,Connection connection,String id,String preparedSql){
            Class<?> type=statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(proxy,method,args)->{
                String sql=preparedSql!=null?preparedSql:args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:null;
                boolean execute=method.getName().startsWith("execute")&&sql!=null;
                Object result;try{result=method.invoke(statement,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                // Crucially, the delegate executed before observing or pausing a real table read.
                if(execute&&capture){
                    String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
                    if(reader()){
                        String isolation;boolean readOnly;
                        try(Statement state=connection.createStatement();ResultSet rs=state.executeQuery("SELECT @@SESSION.transaction_isolation,@@SESSION.transaction_read_only")){assertTrue(rs.next());isolation=rs.getString(1);readOnly=rs.getBoolean(2);}
                        queries.add(new Query(normalized,id,isolation,readOnly,connection.getAutoCommit(),statement.getQueryTimeout()));
                        if(normalized.startsWith("select id,name,utc_timestamp(6) as snapshot_at from patient")){
                            assertEquals("REPEATABLE-READ",isolation);assertTrue(readOnly);assertTrue(connection.isReadOnly());assertFalse(connection.getAutoCommit());assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                            assertTrue(first.compareAndSet(true,false),"One body snapshot first table read per request");
                            if(boundary==Boundary.SNAPSHOT)pause();
                            if(result instanceof ResultSet){ResultSet original=(ResultSet)result;result=Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->{Object v;try{v=m.invoke(original,a);}catch(InvocationTargetException failure){throw failure.getCause();}if(m.getName().equals("getTimestamp")&&a!=null&&"snapshot_at".equals(a[0])&&v!=null)firstSnapshotTime.set(((Timestamp)v).toInstant());return v;});}
                        }
                    } else if(!connection.getAutoCommit()&&(normalized.startsWith("insert ")||normalized.startsWith("update ")||normalized.startsWith("delete ")))writers.add(id);
                }
                return result;
            });
        }
        CareExecutionReportRenderer renderer(String font){return new CareExecutionReportRenderer(new CareExecutionReportHtmlRenderer(),new CareExecutionReportCsvRenderer(),font){@Override public Export render(CareExecutionReport report,Format format,CareExecutionReportBudget budget){Export output=super.render(report,format,budget);if(capture){renderCount.incrementAndGet();renderedBytes.set(output.getContent().length);if(boundary==Boundary.AFTER_RENDER)pause();}return output;}};}
        private void pause(){reached.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS),"Native independent writer must finish without being blocked by report reading");assertTrue(committed.get(),"Reader must resume only after an independently visible commit");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
        List<Query> bodyQueries(){return queries.stream().filter(q->"REPEATABLE-READ".equals(q.isolation)).collect(Collectors.toList());}
        Set<String> bodyIds(){return bodyQueries().stream().map(q->q.id).collect(Collectors.toSet());}
        void assertBodyAndFreshReads(){
            assertFalse(bodyQueries().isEmpty());assertEquals(1,bodyIds().size(),"All report body reads use one physical native RR connection");
            assertTrue(bodyQueries().get(0).sql.startsWith("select id,name,utc_timestamp(6) as snapshot_at from patient"),"Database UTC currentAsOf must be selected in the very first real table read, not a later snapshot query");
            Set<String> fresh=new HashSet<>();for(Query q:queries){
                // Direct caller diagnostic SELECTs are intentionally not report statements.
                if(q.sql.equals("select connection_id()")||q.sql.equals("select @@session.transaction_isolation"))continue;
                assertTrue(q.readOnly,"Actual MySQL session must acknowledge read-only at report query execution");assertFalse(q.autoCommit);assertTrue(q.timeout>0&&q.timeout<=30,"Every report statement has a finite remaining query timeout");
                if("READ-COMMITTED".equals(q.isolation))fresh.add(q.id);else assertEquals("REPEATABLE-READ",q.isolation);
            }
            assertFalse(fresh.isEmpty(),"Actual fresh RC authorization reads must be observed");assertTrue(Collections.disjoint(fresh,bodyIds()),"Fresh permission checks cannot reuse the old RR snapshot connection");
            if(directThread==null){
                assertTrue(fresh.size()>=(boundary==Boundary.SNAPSHOT?2:3),"Initial and completed pre/post-render permission phases use distinct native RC connections");
                assertEquals("READ-COMMITTED",queries.get(0).isolation);
                assertEquals("READ-COMMITTED",queries.get(queries.size()-1).isolation,"Final authorization executes after the body snapshot ends");
            }
        }
        void assertDistinctCommittedWriter(){assertTrue(committed.get());assertEquals(1,writers.size(),"All mutation commands in the race share one committed writer transaction");assertTrue(Collections.disjoint(writers,bodyIds()),"Concurrent writer and report reader are distinct physical MySQL connections");}
    }
}
