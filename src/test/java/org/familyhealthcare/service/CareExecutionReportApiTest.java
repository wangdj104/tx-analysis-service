package org.familyhealthcare.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.familyhealthcare.entity.OperationAuditLog;
import org.familyhealthcare.mapper.OperationAuditLogMapper;
import org.familyhealthcare.service.careplan.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic data through the existing application's real HTTP interceptor and JDBC service chain. */
class CareExecutionReportApiTest extends CarePlanWebTestSupport {
    private String fontPathForTest;
    @Override void registerReportBeans(org.springframework.context.annotation.AnnotatedBeanDefinitionReader reader){super.registerReportBeans(reader);reader.register(ConverterProbe.class);}
    @org.springframework.web.bind.annotation.ControllerAdvice
    static class ConverterProbe implements org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice<Object> {
        final List<Class<?>> converters=new ArrayList<>(),bodies=new ArrayList<>();
        public boolean supports(org.springframework.core.MethodParameter type,Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converter){return type.getContainingClass()==org.familyhealthcare.controller.CareExecutionReportController.class;}
        public Object beforeBodyWrite(Object body,org.springframework.core.MethodParameter type,org.springframework.http.MediaType contentType,Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converter,org.springframework.http.server.ServerHttpRequest request,org.springframework.http.server.ServerHttpResponse response){converters.add(converter);bodies.add(body.getClass());return body;}
    }
    private java.util.function.BiFunction<CareExecutionReport,CareExecutionReportContracts.Format,CareExecutionReportRenderer.Export> renderHook;
    @Override CareExecutionReportRenderer reportRenderer(){
        return new CareExecutionReportRenderer(new CareExecutionReportHtmlRenderer(),new CareExecutionReportCsvRenderer(),"") {
            @Override public Export render(CareExecutionReport r,CareExecutionReportContracts.Format f,CareExecutionReportBudget b){
                if(renderHook!=null)return renderHook.apply(r,f);
                if(fontPathForTest!=null)return new CareExecutionReportRenderer(new CareExecutionReportHtmlRenderer(),new CareExecutionReportCsvRenderer(),fontPathForTest).render(r,f,b);
                return super.render(r,f,b);
            }
        };
    }
    private static final String ROOT="/api/care-plans/reports/";
    private String body(String format) {return "{\"patientId\":1,\"timeZone\":\"UTC\",\"language\":\"en\""+(format==null?"":",\"format\":\""+format+"\"")+"}";}
    private ResultActions report(String format,long actor)throws Exception{return perform(post(ROOT+(format==null?"preview":"export")).contentType("application/json").content(body(format)),actor);}
    private void readGrant(){h.grantFamily();h.f.jdbc().update("UPDATE care_access_grant SET access_level='READ' WHERE grantee_user_id=8");}
    private void seed(){CareExecutionReportTestData.seedReportScenario(h.f.jdbc(),h.f.now());}

    @Test void reportHttpAndErrorEnvelope() throws Exception {
        seed();readGrant();
        report(null,FAMILY).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reportSchemaVersion").value(1)).andExpect(jsonPath("$.data.questionsAvailability").value("NOT_AUTHORIZED"))
                .andExpect(header().string("Cache-Control","no-store, private")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        report("actions_csv",FAMILY).andExpect(status().isOk()).andExpect(content().contentType("text/csv;charset=UTF-8"));
        h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");
        MockHttpServletResponse denied=report("actions_csv",FAMILY).andExpect(status().isForbidden()).andExpect(jsonPath("$.data.errorCode").value("ACCESS_DENIED")).andReturn().getResponse();
        assertTrue(denied.getContentType().contains("application/json"));assertNull(denied.getHeader("Content-Disposition"));
        assertFalse(denied.getContentAsString().contains("Synthetic"));
        assertEquals(403,json.readTree(denied.getContentAsByteArray()).get("code").intValue()); // browser error-Blob bytes are JSON Result
        assertEquals(Collections.singleton("errorCode"),keys(json.readTree(denied.getContentAsByteArray()).get("data")));
    }
    @Test void exactNestedWireKeysAndFlatPeriodEvents() throws Exception {
        seed();JsonNode envelope=json.readTree(report(null,OWNER).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());exact(envelope,"code msg data timestamp");JsonNode r=envelope.get("data");
        exact(r,"reportSchemaVersion patient scope metadata currentSummary currentActions currentAttention activitySummary periodEvents questionsAvailability questions completeness");
        exact(r.get("patient"),"id displayName");exact(r.get("scope"),"planId label");
        exact(r.get("metadata"),"fromDate toDate timeZone language rangeStartAt rangeEndExclusiveAt currentAsOf generatedAt");
        exact(r.get("currentSummary"),"total open needsHelp submitted confirmed overdue needsSupplement");
        exact(r.get("activitySummary"),"eventCount distinctActionCount eventTypeCounts");
        assertEquals(CareExecutionReportContracts.PUBLIC_EVENT_TYPES,keys(r.get("activitySummary").get("eventTypeCounts")));
        String actionKeys="planId revisionId actionId assignedUserId revisionNo planTitle instructions instruction status dueAt reviewWaitingSince overdue needsSupplement assigneeAvailable latestReceipt latestReturn latestReview latestHelp latestFollowUp evidence";
        for(JsonNode a:r.get("currentActions"))exact(a,actionKeys);
        for(JsonNode a:r.get("currentAttention"))exact(a,actionKeys);
        String eventKeys="eventId actorId eventType actorName actorRole actorRelation entryMode note followUpKind recordedAt occurredAt evidence";
        for(JsonNode a:r.get("currentActions"))for(String k:Arrays.asList("latestReceipt","latestReturn","latestReview","latestHelp","latestFollowUp"))if(!a.get(k).isNull())exact(a.get(k),eventKeys);
        for(JsonNode e:r.get("periodEvents"))exact(e,eventKeys+" planId revisionId actionId revisionNo planTitle instructions actionInstruction actionStatusAfterEvent planLifecycleAtGeneration revisionIsCurrentAtGeneration");
        exact(r.get("questions").get(0),"id title status description answer followUp actorName actorId createdAtLocal updatedAtLocal eventAtLocal timeBasis");
        boolean restricted=false,readable=false;
        for(JsonNode a:r.get("currentActions")){for(JsonNode e:a.get("evidence")){if(e.get("restricted").booleanValue()){exact(e,"restricted");restricted=true;}else{exact(e,"restricted sourceType sourceId title detailPath");readable=true;}}}
        // The current receipt also has an unavailable reference, which must have exactly one wire key.
        for(JsonNode a:r.get("currentActions"))if(!a.get("latestReceipt").isNull())for(JsonNode e:a.get("latestReceipt").get("evidence"))if(e.get("restricted").booleanValue()){exact(e,"restricted");restricted=true;}
        assertTrue(restricted);assertTrue(readable);assertTrue(r.get("metadata").get("generatedAt").asText().endsWith("Z"));
    }
    @Test void strictBoundedBodyAndDeepMalformedContainerHaveFixedSafe400() throws Exception {
        String nested="[";for(int i=0;i<1900;i++)nested+="[";nested+="0";for(int i=0;i<1901;i++)nested+="]";
        assertTrue(nested.getBytes(StandardCharsets.UTF_8).length<=4096);
        for(String bad:new String[]{"[]","null","{} {}",body(null).replace("\"patientId\":1","\"patientId\":1,\"patientId\":1"),body(null).replace("\"patientId\":1","\"actorId\":7,\"patientId\":1"),nested,String.join("",Collections.nCopies(4097," "))}) {
            MockHttpServletResponse res=perform(post(ROOT+"preview").contentType("application/json").content(bad),OWNER).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("INVALID_REQUEST")).andReturn().getResponse();
            assertFalse(res.getContentAsString().contains("actorId"));assertTrue(res.getContentAsByteArray().length<500);
        }
        perform(post(ROOT+"preview").contentType("application/json").content(new byte[]{(byte)0xc3,0x28}),OWNER).andExpect(status().isBadRequest());
        perform(post(ROOT+"preview").contentType("application/json").queryParam("patientId","1").content(body(null)),OWNER).andExpect(status().isBadRequest());
        perform(post(ROOT+"preview").contentType("text/plain").content(body(null)),OWNER).andExpect(status().isBadRequest());
    }
    @Test void featureOffReadsNoClinicalTablesAndConstructsNoExecutor() throws Exception {
        context.close();buildContext(false);
        assertTrue(context.getBeansOfType(CareExecutionReportAccess.class).isEmpty());assertTrue(context.getBeansOfType(CareExecutionReportProjector.class).isEmpty());
        assertFalse(Arrays.stream(context.getBeanDefinitionNames()).anyMatch(n->n.equals("careExecutionReportService")));
        h.f.jdbc().execute("DROP TABLE care_plan_notification,care_plan_evidence,care_plan_command,care_plan_event,care_plan_action,care_plan_revision,care_nurse_assignment,care_item,patient CASCADE");
        for(String endpoint:new String[]{"preview","export"})perform(post(ROOT+endpoint).contentType("application/json").content("["),OWNER)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data.errorCode").value("FEATURE_DISABLED"))
                .andExpect(header().string("Cache-Control","no-store, private"));
    }
    @Test void unauthenticatedResponseIsPrivate() throws Exception {
        mvc.perform(post(ROOT+"preview").contentType("application/json").content(body(null))).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store, private"));
    }
    @Test void readPostDoesNotMutateCareStateAndAuditContainsTechnicalMetadataOnly() throws Exception {
        seed();readGrant();Map<String,List<Map<String,Object>>> before=new LinkedHashMap<>();
        for(String table:new String[]{"doctor_care_plan","care_plan_revision","care_plan_action","care_plan_event","care_plan_command","care_plan_notification","care_item"})before.put(table,h.f.jdbc().queryForList("SELECT * FROM "+table));
        report(null,FAMILY).andExpect(status().isOk());report("html",FAMILY).andExpect(status().isOk());
        for(Map.Entry<String,List<Map<String,Object>>> e:before.entrySet())assertEquals(e.getValue(),h.f.jdbc().queryForList("SELECT * FROM "+e.getKey()));
        ArgumentCaptor<OperationAuditLog> logs=ArgumentCaptor.forClass(OperationAuditLog.class);verify(context.getBean(OperationAuditLogMapper.class),times(2)).insert(logs.capture());
        assertEquals("VIEW",logs.getAllValues().get(0).getActionType());assertEquals("EXPORT",logs.getAllValues().get(1).getActionType());
        for(OperationAuditLog log:logs.getAllValues()){assertEquals("{}",log.getDetailJson());assertFalse(log.getDetailJson().contains("synthetic-"));assertFalse(log.toString().contains("原文"));}
        verifyNoInteractions(transport);
    }
    @Test void smallerAndEmptyCsvRemainAvailableWhenFullPreviewExceedsLimits() throws Exception {
        seed();for(int i=0;i<200;i++)h.f.jdbc().update("INSERT INTO care_item(patient_id,user_id,kind,title,status,data_json) VALUES(1,7,'QUESTION','Synthetic excess','OPEN','{}')");
        report(null,OWNER).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.limitKind").value("QUESTIONS")).andExpect(jsonPath("$.data.limit").value(200));
        report("actions_csv",OWNER).andExpect(status().isOk());
        h.f.jdbc().execute("DROP TABLE care_item"); // no optional-question count or body reads for CSV or narrow grant
        report("events_csv",OWNER).andExpect(status().isOk());readGrant();report(null,FAMILY).andExpect(status().isOk());
        h.f.jdbc().update("UPDATE doctor_care_plan SET lifecycle='COMPLETED' WHERE lifecycle='ACTIVE'");
        String empty=report("actions_csv",OWNER).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(empty.startsWith("\uFEFF"));assertEquals(1,empty.split("\r\n").length);
    }
    @Test void exportHeadersAndSafeNames() throws Exception {
        for(String format:new String[]{"html","actions_csv","events_csv"}) {
            MockHttpServletResponse res=report(format,OWNER).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"))
                    .andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse();
            assertTrue(res.getHeader("Content-Disposition").matches("attachment; filename=\"care-execution-report-en-[0-9]{8}T[0-9]{6}Z-"+(format.equals("html")?"html.html":format.equals("actions_csv")?"actions.csv":"events.csv")+"\""));
            assertEquals(format.equals("html")?"text/html;charset=UTF-8":"text/csv;charset=UTF-8",res.getContentType());
        }
    }
    @Test void finalFreshAuthorizationDiscardsBufferedFileForBaseAndEvidenceRevocation()throws Exception {
        seed();readGrant();h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN,MEASUREMENTS' WHERE grantee_user_id=8");
        renderHook=(r,f)->{h.f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN' WHERE grantee_user_id=8");return new CareExecutionReportRenderer.Export("Synthetic private note PDF-fragment".getBytes(StandardCharsets.UTF_8),"application/pdf","safe.pdf");};
        MockHttpServletResponse changed=report("pdf",FAMILY).andExpect(status().isConflict()).andExpect(jsonPath("$.data.errorCode").value("REPORT_ACCESS_CHANGED")).andReturn().getResponse();
        assertNull(changed.getHeader("Content-Disposition"));assertTrue(changed.getContentType().contains("application/json"));assertFalse(changed.getContentAsString().contains("Synthetic private note"));
        renderHook=(r,f)->{h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");return new CareExecutionReportRenderer.Export("Synthetic private note CSV-fragment".getBytes(StandardCharsets.UTF_8),"text/csv","safe.csv");};
        MockHttpServletResponse denied=report("actions_csv",FAMILY).andExpect(status().isForbidden()).andReturn().getResponse();
        assertNull(denied.getHeader("Content-Disposition"));assertFalse(denied.getContentAsString().contains("Synthetic private note"));assertTrue(denied.getContentType().contains("application/json"));
    }
    @Test void periodLimitHasNumericMetadataAndDoesNotBlockActionsCsv()throws Exception {
        seed();List<Object[]> args=new ArrayList<>();for(int i=0;i<4992;i++)args.add(new Object[]{java.time.LocalDateTime.ofInstant(h.f.now(),java.time.ZoneOffset.UTC)});
        h.f.jdbc().batchUpdate("INSERT INTO care_plan_event(patient_id,plan_id,revision_id,action_id,actor_id,actor_name,actor_role,event_type,recorded_at,payload_json) VALUES(1,100,101,105,7,'Synthetic','PATIENT','RECEIPT_SUBMITTED',?,'{}')",args);
        for(String format:new String[]{null,"events_csv"})report(format,OWNER).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.errorCode").value("REPORT_LIMIT_EXCEEDED")).andExpect(jsonPath("$.data.limitKind").value("PERIOD_EVENTS")).andExpect(jsonPath("$.data.limit").value(5000));
        report("actions_csv",OWNER).andExpect(status().isOk());
    }
    @Test void unexpectedRenderFailureHasSafeBlobJsonAndNoSensitiveApplicationLogs()throws Exception {
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.familyhealthcare");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender=new ch.qos.logback.core.read.ListAppender<>();appender.start();logger.addAppender(appender);
        String token="synthetic-report-private-token";when(jwt.validateToken(token)).thenReturn(true);doReturn(OWNER).when(jwt).getUserIdFromToken(token);
        try {
            renderHook=(r,f)->{throw new IllegalStateException("Synthetic private note "+token+" auth-config");};
            MockHttpServletResponse res=mvc.perform(post(ROOT+"export").contentType("application/json").content(body("html")).header("Authorization","Bearer "+token))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.errorCode").value("REPORT_RENDER_UNAVAILABLE")).andReturn().getResponse();
            assertTrue(res.getContentType().contains("application/json"));assertNull(res.getHeader("Content-Disposition"));assertFalse(res.getContentAsString().contains("Synthetic"));
            for(ch.qos.logback.classic.spi.ILoggingEvent event:appender.list){assertFalse(event.getFormattedMessage().contains(token));assertFalse(event.getFormattedMessage().contains("Synthetic private note"));assertFalse(event.getFormattedMessage().contains("auth-config"));assertNull(event.getThrowableProxy());}
            ArgumentCaptor<OperationAuditLog> logs=ArgumentCaptor.forClass(OperationAuditLog.class);verify(context.getBean(OperationAuditLogMapper.class)).insert(logs.capture());assertFalse(logs.getValue().toString().contains(token));assertEquals("{}",logs.getValue().getDetailJson());
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void equivalentMappedTrailingSlashRouteRetainsPrivateHeadersAndReadAudit()throws Exception {
        perform(post(ROOT+"preview/").contentType("application/json").content(body(null)),OWNER)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"));
        ArgumentCaptor<OperationAuditLog> logs=ArgumentCaptor.forClass(OperationAuditLog.class);verify(context.getBean(OperationAuditLogMapper.class)).insert(logs.capture());assertEquals("VIEW",logs.getValue().getActionType());assertEquals("{}",logs.getValue().getDetailJson());
    }
    @Test void emptyIncludedQuestionSectionStillRequiresFinalAuthorityAndSinglePlanNeverReadsQuestions()throws Exception {
        seed();readGrant();h.f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,granted_by) VALUES(1,8,'GUARDIAN','READ',NULL,7)");h.f.jdbc().update("DELETE FROM care_item");
        renderHook=(r,f)->{assertEquals("AVAILABLE",r.getQuestionsAvailability());assertTrue(r.getQuestions().isEmpty());h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8 AND grantee_role='GUARDIAN'");return new CareExecutionReportRenderer.Export(new byte[]{1},"application/pdf","safe.pdf");};
        report("pdf",FAMILY).andExpect(status().isConflict()).andExpect(jsonPath("$.data.errorCode").value("REPORT_ACCESS_CHANGED"));
        renderHook=null;h.f.jdbc().execute("DROP TABLE care_item");
        perform(post(ROOT+"preview").contentType("application/json").content(body(null).replace("\"patientId\":1","\"patientId\":1,\"planId\":100")),OWNER)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.questionsAvailability").value("NOT_INCLUDED_IN_PLAN_SCOPE"));
    }
    @Test void missingLengthStillReadsAtMost4097BytesAndNeverInvokesService()throws Exception {
        java.util.concurrent.atomic.AtomicInteger read=new java.util.concurrent.atomic.AtomicInteger();
        javax.servlet.ServletInputStream endless=new javax.servlet.ServletInputStream(){
            public boolean isFinished(){return false;}public boolean isReady(){return true;}public void setReadListener(javax.servlet.ReadListener listener){}
            public int read(){read.incrementAndGet();return ' ';}
        };
        org.springframework.mock.web.MockHttpServletRequest input=new org.springframework.mock.web.MockHttpServletRequest(){
            @Override public long getContentLengthLong(){return -1;}
            @Override public javax.servlet.ServletInputStream getInputStream(){return endless;}
        };input.setContentType("application/json");
        org.springframework.beans.factory.ObjectProvider<CareExecutionReportService> provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        CareExecutionReportService service=mock(CareExecutionReportService.class);when(provider.getIfAvailable()).thenReturn(service);
        org.familyhealthcare.controller.CareExecutionReportController controller=new org.familyhealthcare.controller.CareExecutionReportController(h.properties,provider);
        CareExecutionReportException error=assertThrows(CareExecutionReportException.class,()->org.familyhealthcare.util.CurrentUserUtil.runAsUser(OWNER,()->controller.preview(input)));
        assertEquals("INVALID_REQUEST",error.getErrorCode());assertEquals(4097,read.get());verifyNoInteractions(service);
    }
    @Test void revocationDuringBlockedRenderingNeverCommitsFileHeadersOrBytes()throws Exception {
        seed();readGrant();java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        renderHook=(r,f)->{entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test rendering wait expired");}catch(InterruptedException e){Thread.currentThread().interrupt();throw CareExecutionReportException.timeout();}return new CareExecutionReportRenderer.Export("Synthetic private note prepared bytes".getBytes(StandardCharsets.UTF_8),"text/csv","safe.csv");};
        java.util.concurrent.ExecutorService caller=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            java.util.concurrent.Future<MockHttpServletResponse> response=caller.submit(()->report("actions_csv",FAMILY).andReturn().getResponse());
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertFalse(response.isDone());
            h.f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE grantee_user_id=8");release.countDown();
            MockHttpServletResponse denied=response.get(3,java.util.concurrent.TimeUnit.SECONDS);assertEquals(403,denied.getStatus());assertTrue(denied.getContentType().contains("application/json"));assertNull(denied.getHeader("Content-Disposition"));assertFalse(denied.getContentAsString().contains("Synthetic private note"));
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test void frameworkDebugAndTraceNeverLogBodyAndRangeCannotDeliverPartialReports()throws Exception {
        fontPathForTest=new java.io.File(getClass().getClassLoader().getResource("fonts/wqy-care-report-subset.ttf").toURI()).getPath();
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor");
        ch.qos.logback.classic.Level previous=logger.getLevel();ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender=new ch.qos.logback.core.read.ListAppender<>();appender.start();logger.addAppender(appender);
        ConverterProbe probe=context.getBean(ConverterProbe.class);
        try{
            for(ch.qos.logback.classic.Level level:new ch.qos.logback.classic.Level[]{ch.qos.logback.classic.Level.DEBUG,ch.qos.logback.classic.Level.TRACE}) {
                logger.setLevel(level);
                for(String format:new String[]{null,"html","pdf","actions_csv","events_csv"}){
                    appender.list.clear();probe.converters.clear();probe.bodies.clear();
                    MockHttpServletResponse res=perform(post(ROOT+(format==null?"preview":"export")).contentType("application/json").content(body(format)).header("Range","bytes=0-4"),OWNER).andExpect(status().isOk()).andReturn().getResponse();
                    List<String> writes=new ArrayList<>();for(ch.qos.logback.classic.spi.ILoggingEvent event:appender.list)if(event.getFormattedMessage().startsWith("Writing ["))writes.add(event.getFormattedMessage());
                    assertEquals(Collections.singletonList("Writing [InputStream resource [Care execution report]]"),writes,"Framework logs must contain only a fixed resource description");
                    assertEquals(Collections.singletonList(org.springframework.http.converter.ResourceHttpMessageConverter.class),probe.converters);
                    assertEquals(Collections.singletonList(org.springframework.core.io.InputStreamResource.class),probe.bodies,"Exact InputStreamResource disables Spring's automatic range handling");
                    assertEquals(Integer.toString(res.getContentAsByteArray().length),res.getHeader("Content-Length"));assertTrue(res.getContentAsByteArray().length>5);assertNull(res.getHeader("Content-Range"));assertNull(res.getHeader("Accept-Ranges"));
                    assertEquals(format==null?"application/json":format.equals("pdf")?"application/pdf":format.equals("html")?"text/html;charset=UTF-8":"text/csv;charset=UTF-8",res.getContentType());
                    if(format==null)assertEquals(1,json.readTree(res.getContentAsByteArray()).get("data").get("reportSchemaVersion").intValue());
                    else if(format.equals("pdf"))assertEquals("%PDF",new String(res.getContentAsByteArray(),0,4,StandardCharsets.US_ASCII));
                    else if(format.equals("html"))assertTrue(res.getContentAsString(StandardCharsets.UTF_8).startsWith("<html"));
                    else assertTrue(res.getContentAsString(StandardCharsets.UTF_8).startsWith("\uFEFF"));
                }
            }
            for(String range:new String[]{"bytes=999999999-","invalid"})perform(post(ROOT+"export").contentType("application/json").content(body("actions_csv")).header("Range",range),OWNER).andExpect(status().isOk()).andExpect(header().doesNotExist("Content-Range"));
        }finally{logger.setLevel(previous);logger.detachAppender(appender);appender.stop();}
    }
    @Test void mappedReportAuditNeverRetainsMatrixParameters()throws Exception {
        String marker="synthetic-private-matrix-marker";
        OperationAuditLogMapper audit=context.getBean(OperationAuditLogMapper.class);
        for(String endpoint:new String[]{"preview","export"}) {
            String canonical=ROOT+endpoint;
            for(String path:new String[]{canonical,canonical+"/",
                    "/api/care-plans;"+marker+"/reports/"+endpoint,
                    "/api/care-plans/reports;"+marker+"/"+endpoint,
                    canonical+";"+marker,
                    "/api;"+marker+"/care-plans;"+marker+"/reports;"+marker+"/"+endpoint+";"+marker+"/"}) {
                clearInvocations(audit);
                perform(post(path).contentType("application/json").content(body(endpoint.equals("preview")?null:"html")),OWNER)
                        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"));
                ArgumentCaptor<OperationAuditLog> inserted=ArgumentCaptor.forClass(OperationAuditLog.class);verify(audit).insert(inserted.capture());OperationAuditLog log=inserted.getValue();
                JsonNode fields=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).valueToTree(log);
                fields.fields().forEachRemaining(field->assertFalse(field.getValue().toString().contains(marker),"Caller matrix content retained in audit field "+field.getKey()));
                assertEquals(canonical,log.getRequestPath());assertEquals("care-plans",log.getTargetType());assertNull(log.getTargetId());
                assertEquals("POST",log.getRequestMethod());assertEquals(endpoint.equals("preview")?"VIEW":"EXPORT",log.getActionType());
                assertEquals(OWNER,log.getUserId());assertEquals(200,log.getStatusCode());assertEquals("{}",log.getDetailJson());
            }
        }
    }
    @Test void legacyMatrixAuditBehaviorRemainsUnchanged()throws Exception {
        String path="/api/care-plans;synthetic-legacy-marker/capabilities";
        perform(get(path),OWNER).andExpect(status().isOk());
        ArgumentCaptor<OperationAuditLog> inserted=ArgumentCaptor.forClass(OperationAuditLog.class);verify(context.getBean(OperationAuditLogMapper.class)).insert(inserted.capture());OperationAuditLog log=inserted.getValue();
        assertEquals(path,log.getRequestPath());assertEquals("care-plans;synthetic-legacy-marker",log.getTargetType());assertNull(log.getTargetId());
        assertEquals("VIEW",log.getActionType());assertEquals("{\"parameterNames\":[]}",log.getDetailJson());
    }
    private static Set<String> keys(JsonNode node){Set<String>s=new HashSet<>();node.fieldNames().forEachRemaining(s::add);return s;}
    private static void exact(JsonNode node,String keys){assertEquals(new HashSet<>(Arrays.asList(keys.split(" "))),keys(node));}
}
