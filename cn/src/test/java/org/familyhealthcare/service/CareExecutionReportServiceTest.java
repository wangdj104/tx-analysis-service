package org.familyhealthcare.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.service.careplan.CareExecutionReportContracts.*;
import org.familyhealthcare.service.careplan.CareExecutionReportAccess.*;
import org.familyhealthcare.service.careplan.CareExecutionReportRenderer.Export;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import java.io.*;
import java.lang.reflect.Constructor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareExecutionReportServiceTest {
    CareExecutionReportAccess access=mock(CareExecutionReportAccess.class);
    CareExecutionReportProjector projector=mock(CareExecutionReportProjector.class);
    CareExecutionReportRenderer renderer=mock(CareExecutionReportRenderer.class);
    CareExecutionReport report=CareExecutionReportTestData.exampleReport("en");
    Manifest manifest=new Manifest(true,Collections.singleton(new EvidenceKey("MEDICAL_RECORD",100)));
    ObjectMapper json=Jackson2ObjectMapperBuilder.json().build();
    CareExecutionReportService service;
    @BeforeEach void setup(){when(access.inspect(anyLong(),any(),any())).thenReturn(new Access(true));when(projector.project(anyLong(),any(),any(),any())).thenReturn(new CareExecutionReportProjector.Projection(report,manifest));}
    @AfterEach void close(){if(service!=null)service.close();}
    Request request(String format){return CareExecutionReportContracts.parse("{\"patientId\":1,\"timeZone\":\"UTC\",\"language\":\"en\""+(format==null?"":",\"format\":\""+format+"\"")+"}",format!=null,Instant.parse("2026-10-04T12:00:00Z"));}
    void start(Duration timeout)throws Exception {Constructor<CareExecutionReportService> c=CareExecutionReportService.class.getDeclaredConstructor(CareExecutionReportAccess.class,CareExecutionReportProjector.class,CareExecutionReportRenderer.class,ObjectMapper.class,Duration.class);c.setAccessible(true);service=c.newInstance(access,projector,renderer,json,timeout);}
    @Test void freshAuthAfterRenderDiscardsBytes()throws Exception {
        List<String> steps=new ArrayList<>();AtomicReference<CareExecutionReportBudget> shared=new AtomicReference<>();
        when(access.inspect(anyLong(),any(),any())).thenAnswer(a->{steps.add("inspect");shared.set(a.getArgument(2));return new Access(true);});
        when(projector.project(anyLong(),any(),any(),any())).thenAnswer(a->{steps.add("snapshot");assertSame(shared.get(),a.getArgument(3));return new CareExecutionReportProjector.Projection(report,manifest);});
        AtomicInteger checks=new AtomicInteger();doAnswer(a->{steps.add("recheck");assertSame(shared.get(),a.getArgument(3));if(checks.incrementAndGet()==2)throw CareExecutionReportException.denied();return null;}).when(access).recheck(anyLong(),any(),any(),any());
        when(renderer.render(any(),any(),any())).thenAnswer(a->{steps.add("render");assertSame(shared.get(),a.getArgument(2));return new Export("Synthetic private note".getBytes("UTF-8"),"application/pdf","safe.pdf");});
        start(Duration.ofSeconds(30));CareExecutionReportException e=assertThrows(CareExecutionReportException.class,()->service.export(7,request("pdf")));
        assertEquals("ACCESS_DENIED",e.getErrorCode());assertEquals(Arrays.asList("inspect","snapshot","recheck","render","recheck"),steps);
    }
    @Test void finalAuthorizationRunsAfterResultSerializationIncludingEmptyQuestionManifest()throws Exception {
        report=new CareExecutionReport(report.getPatient(),report.getScope(),report.getMetadata(),report.getCurrentSummary(),report.getCurrentActions(),report.getCurrentAttention(),report.getActivitySummary(),report.getPeriodEvents(),"AVAILABLE",Collections.emptyList());
        when(projector.project(anyLong(),any(),any(),any())).thenReturn(new CareExecutionReportProjector.Projection(report,manifest));
        AtomicBoolean serialized=new AtomicBoolean();AtomicInteger checks=new AtomicInteger();
        SimpleModule module=new SimpleModule();module.addSerializer(CareExecutionReport.class,new StdSerializer<CareExecutionReport>(CareExecutionReport.class){public void serialize(CareExecutionReport value,JsonGenerator gen,SerializerProvider serializers)throws IOException{assertEquals(1,checks.get());gen.writeStartObject();gen.writeNumberField("reportSchemaVersion",1);gen.writeEndObject();serialized.set(true);}});json.registerModule(module);
        doAnswer(a->{if(checks.incrementAndGet()==2){assertTrue(serialized.get());throw new CareExecutionReportException(CareExecutionReportException.Code.REPORT_ACCESS_CHANGED);}return null;}).when(access).recheck(anyLong(),any(),eq(manifest),any());
        start(Duration.ofSeconds(30));assertEquals("REPORT_ACCESS_CHANGED",assertThrows(CareExecutionReportException.class,()->service.preview(7,request(null))).getErrorCode());verifyNoInteractions(renderer);
    }
    @Test void preparedPreviewIsTheBoundedApplicationResultEnvelope()throws Exception {
        start(Duration.ofSeconds(30));CareExecutionReportService.PreparedPreview p=service.preview(7,request(null));JsonNode tree=json.readTree(p.getJson());
        assertEquals(200,tree.get("code").intValue());assertEquals(1,tree.get("data").get("reportSchemaVersion").intValue());assertTrue(tree.get("data").get("metadata").get("generatedAt").asText().endsWith("Z"));assertSame(report,p.getReport());
        verify(access,times(2)).recheck(eq(7L),any(),eq(manifest),any());
    }
    @Test void boundedTimeoutAndNoPartialResponse()throws Exception {
        CountDownLatch started=new CountDownLatch(1),stopped=new CountDownLatch(1);
        when(renderer.render(any(),any(),any())).thenAnswer(a->{started.countDown();try{new CountDownLatch(1).await();}finally{stopped.countDown();}return null;});
        start(Duration.ofMillis(180));long before=System.nanoTime();assertEquals("REPORT_TIMEOUT",assertThrows(CareExecutionReportException.class,()->service.export(7,request("pdf"))).getErrorCode());
        assertTrue(started.await(1,TimeUnit.SECONDS));assertTrue(stopped.await(1,TimeUnit.SECONDS));assertTrue(System.nanoTime()-before<TimeUnit.SECONDS.toNanos(2));verify(access,times(1)).recheck(anyLong(),any(),any(),any());
    }
    @Test void exactlyTwoWorkersTwoQueuedAndCanceledQueueEntriesAreRemoved()throws Exception {
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        when(renderer.render(any(),any(),any())).thenAnswer(a->{calls.incrementAndGet();entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){/* Simulate a library that does not promptly terminate on interruption. */}}return new Export(new byte[]{1},"application/pdf","safe.pdf");});
        start(Duration.ofMillis(800));ExecutorService callers=Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> pending=new ArrayList<>();for(int i=0;i<2;i++)pending.add(callers.submit(()->outcome()));assertTrue(entered.await(1,TimeUnit.SECONDS));
            for(int i=0;i<2;i++)pending.add(callers.submit(()->outcome()));
            ThreadPoolExecutor pool=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(service,"executor");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(pool.getQueue().size()!=2&&System.nanoTime()<until)Thread.yield();
            assertEquals(2,pool.getQueue().size());assertEquals(2,pool.getMaximumPoolSize());assertEquals(2,pool.getCorePoolSize());
            assertEquals("REPORT_RENDER_UNAVAILABLE",outcome());assertEquals(2,calls.get());
            for(Future<String> f:pending)assertEquals("REPORT_TIMEOUT",f.get(2,TimeUnit.SECONDS));assertEquals(0,pool.getQueue().size());
            assertEquals(2,pool.getActiveCount()); // Cancellation does not falsely claim forced interruption inside the renderer.
        }finally{release.countDown();callers.shutdownNow();}
    }
    private String outcome(){try{service.export(7,request("pdf"));return "SUCCESS";}catch(CareExecutionReportException e){return e.getErrorCode();}}
    @Test void queueTimeIsChargedAndBudgetNeverRestarts()throws Exception {
        AtomicReference<CareExecutionReportBudget> shared=new AtomicReference<>();
        when(access.inspect(anyLong(),any(),any())).thenAnswer(a->{CareExecutionReportBudget b=a.getArgument(2);shared.set(b);Thread.sleep(100);return new Access(true);});
        when(renderer.render(any(),any(),any())).thenAnswer(a->{assertSame(shared.get(),a.getArgument(2));Thread.sleep(180);return new Export(new byte[]{1},"application/pdf","safe.pdf");});
        start(Duration.ofMillis(200));assertEquals("REPORT_TIMEOUT",outcome());
    }
    @Test void unexpectedFailuresAndSerializerCausesNeverEscape()throws Exception {
        when(projector.project(anyLong(),any(),any(),any())).thenThrow(new IllegalStateException("Synthetic private note Authorization bearer-secret"));start(Duration.ofSeconds(30));
        CareExecutionReportException e=assertThrows(CareExecutionReportException.class,()->service.preview(7,request(null)));assertEquals("REPORT_RENDER_UNAVAILABLE",e.getErrorCode());assertNull(e.getCause());assertFalse(e.getMessage().contains("Synthetic"));
    }
    @Test void serializationOutputLimitRemains422AndSkipsFinalDeliveryCheck()throws Exception {
        SimpleModule module=new SimpleModule();module.addSerializer(CareExecutionReport.class,new StdSerializer<CareExecutionReport>(CareExecutionReport.class){public void serialize(CareExecutionReport value,JsonGenerator gen,SerializerProvider serializers)throws IOException{gen.writeStartArray();char[] chunk=new char[8192];Arrays.fill(chunk,'x');for(int i=0;i<5000;i++)gen.writeString(chunk,0,chunk.length);gen.writeEndArray();}});json.registerModule(module);
        start(Duration.ofSeconds(30));CareExecutionReportException e=assertThrows(CareExecutionReportException.class,()->service.preview(7,request(null)));assertEquals("OUTPUT_BYTES",e.getLimitKind());assertEquals(422,e.getStatus());verify(access,times(1)).recheck(anyLong(),any(),any(),any());
    }
    @Test void rendererUsesFrozenNamesAndDoesNotDoubleChargeCsv()throws Exception {
        CareExecutionReportRenderer real=new CareExecutionReportRenderer(new CareExecutionReportHtmlRenderer(),new CareExecutionReportCsvRenderer(),"");
        for(Format f:new Format[]{Format.HTML,Format.ACTIONS_CSV,Format.EVENTS_CSV}){CareExecutionReportBudget b=CareExecutionReportBudget.start(Duration.ofSeconds(30));Export file=real.render(report,f,b);String kind=f==Format.HTML?"html.html":f==Format.ACTIONS_CSV?"actions.csv":"events.csv";assertEquals("care-execution-report-en-20261004T120000Z-"+kind,file.getFileName());assertEquals((long)file.getContent().length,org.springframework.test.util.ReflectionTestUtils.getField(b,"outputBytes"));}
    }
    @Test void pdfDispatchUsesConfiguredFontAndChargesFinalBytesOnceForBothLanguages()throws Exception {
        String font=new java.io.File(getClass().getClassLoader().getResource("fonts/wqy-care-report-subset.ttf").toURI()).getPath();
        CareExecutionReportRenderer real=new CareExecutionReportRenderer(new CareExecutionReportHtmlRenderer(),new CareExecutionReportCsvRenderer(),font);
        for(String language:Arrays.asList("en","zh-CN")){
            CareExecutionReportBudget budget=CareExecutionReportBudget.start(Duration.ofSeconds(30));Export file=real.render(CareExecutionReportTestData.exampleReport(language),Format.PDF,budget);
            assertEquals("application/pdf",file.getContentType());assertEquals("%PDF",new String(file.getContent(),0,4,java.nio.charset.StandardCharsets.US_ASCII));assertEquals("care-execution-report-"+language+"-20261004T120000Z-pdf.pdf",file.getFileName());
            assertEquals((long)file.getContent().length,org.springframework.test.util.ReflectionTestUtils.getField(budget,"outputBytes"));
        }
    }
    @Test void expiredCsvBudgetAndInterruptedWorkFailClosed(){for(Format f:new Format[]{Format.ACTIONS_CSV,Format.EVENTS_CSV})assertEquals("REPORT_TIMEOUT",assertThrows(CareExecutionReportException.class,()->new CareExecutionReportCsvRenderer().render(report,f,CareExecutionReportBudget.start(Duration.ZERO))).getErrorCode());Thread.currentThread().interrupt();try{assertThrows(CareExecutionReportException.class,()->CareExecutionReportBudget.start(Duration.ofSeconds(30)).checkTime());}finally{Thread.interrupted();}}
}
