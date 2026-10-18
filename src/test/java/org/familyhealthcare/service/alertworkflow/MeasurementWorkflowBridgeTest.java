package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.service.CareJourneyService;
import org.familyhealthcare.service.NotificationAudienceService;
import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native journey entry point, real relational workflow and durable retries; fictional records only. */
class MeasurementWorkflowBridgeTest {
    AlertWorkflowHarness h; CareJourneyService journey; org.familyhealthcare.service.impl.BpSelfMonitorRecordServiceImpl self; org.springframework.test.web.servlet.MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        h=new AlertWorkflowHarness(); journey=new CareJourneyService();
        h.db.jdbc.execute("CREATE TABLE measurement_annotation(id BIGINT,measurement_id BIGINT,doctor_user_id BIGINT,patient_id BIGINT,created_at TIMESTAMP)");
        ReflectionTestUtils.setField(journey,"jdbc",h.db.jdbc);
        ReflectionTestUtils.setField(journey,"alertWorkflowProperties",h.p);
        ReflectionTestUtils.setField(journey,"workflowAuthorization",h.auth);
        ReflectionTestUtils.setField(journey,"measurementWorkflow",new MeasurementWorkflowBridge(h.db.jdbc,h.p,h.auth,h.repo,h.service,new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p)));
        ReflectionTestUtils.setField(journey,"measurementAssessment",new MeasurementAssessmentService(h.db.jdbc));
        ReflectionTestUtils.setField(journey,"scope",mock(DataScopeHelper.class));
        ReflectionTestUtils.setField(journey,"audience",mock(NotificationAudienceService.class));
        for(String name:Arrays.asList("fasting_glucose_min","fasting_glucose_max","postmeal_glucose_min","postmeal_glucose_max"))h.db.jdbc.execute("ALTER TABLE patient_health_target ADD "+name+" DECIMAL(8,3)");
        com.baomidou.mybatisplus.core.MybatisConfiguration config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setEnvironment(new org.apache.ibatis.mapping.Environment("test",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),h.db.jdbc.getDataSource()));
        config.addMapper(org.familyhealthcare.mapper.BpSelfMonitorRecordMapper.class);
        org.apache.ibatis.session.SqlSessionFactory factory=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);
        self=new org.familyhealthcare.service.impl.BpSelfMonitorRecordServiceImpl();
        ReflectionTestUtils.setField(self,"alertWorkflowProperties",h.p);
        ReflectionTestUtils.setField(self,"measurementWorkflow",new MeasurementWorkflowBridge(h.db.jdbc,h.p,h.auth,h.repo,h.service,new AlertWorkflowEvidenceService(h.db.jdbc,h.repo,h.auth,h.p)));
        ReflectionTestUtils.setField(self,"tracking",new MeasurementTrackingGuard(h.db.jdbc));
        ReflectionTestUtils.setField(self,"workflowAuth",h.auth);
        ReflectionTestUtils.setField(self,"baseMapper",new org.mybatis.spring.SqlSessionTemplate(factory).getMapper(org.familyhealthcare.mapper.BpSelfMonitorRecordMapper.class));
        DataScopeHelper scope=mock(DataScopeHelper.class);when(scope.requireUserId()).thenReturn(1L);ReflectionTestUtils.setField(self,"dataScopeHelper",scope);
        org.familyhealthcare.controller.BpSelfMonitorRecordController controller=new org.familyhealthcare.controller.BpSelfMonitorRecordController();ReflectionTestUtils.setField(controller,"bpSelfMonitorRecordService",self);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new org.familyhealthcare.controller.MeasurementWorkflowExceptionAdvice()).addFilters(new org.familyhealthcare.config.SensitiveResponseFilter()).build();
    }
    Map<String,Object> body(String value) {return map("patientId",10,"metricType","GLUCOSE","valuePrimary",value,"unit","mmol/L","measuredAt","2026-10-13T11:59:00","occurredAt","2026-10-13T11:59:00Z","commandKey",UUID.randomUUID().toString());}
    Map<String,Object> save(Map<String,Object> b) {return CurrentUserUtil.runAsUser(1L,()->h.db.tx.execute(tx->journey.saveMeasurement(b)));}
    @Test void nativeJourneySaveAndRetryCreateExactlyOneSourceRevisionAndFollowUp() {
        Map<String,Object> b=body("18"),first=save(b),again=save(b);
        assertEquals(first,again,"An uncertain retry must return the original durable result");
        assertEquals(1,h.count("alert_source_identity"));assertEquals(1,h.count("alert_source_revision"));assertEquals(1,h.count("alert_record"));assertEquals(1,h.count("measurement_command"));
        assertEquals("HEALTH_MEASUREMENT",first.get("sourceType"));assertTrue(id(first.get("sourceId"))>0);
    }
    @Test void normalNativeEntryIsTrackedWithoutCreatingAnAlert() {
        Map<String,Object> result=save(body("5"));assertEquals(1,h.count("alert_source_identity"));assertEquals(0,h.count("alert_record"));assertEquals(Collections.emptyList(),result.get("followUps"));
    }
    @Test void missingWorkflowCommandCannotSilentlyWriteEnabledSource() {
        Map<String,Object>b=body("18");b.remove("commandKey");long before=h.count("health_measurement");
        assertThrows(AlertWorkflowException.class,()->save(b));assertEquals(before,h.count("health_measurement"));
    }
    Map<String,Object> selfBody() {return map("patientId",10,"measureType","BP_BG","systolicBp",190,"diastolicBp",125,"bloodGlucose","18","bgUnit","mmol/L","measurePeriod","Fasting","recordDate","2026-10-13","recordTime","11:59","occurredAt","2026-10-13T11:59:00Z","commandKey",UUID.randomUUID().toString());}
    Map<String,Object> selfPost(Map<String,Object> body) {
        return h.db.tx.execute(tx->{try {
            String response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/bp-self-monitor/save").requestAttr("userId",1L).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json(body))).andReturn().getResponse().getContentAsString();
            return object(response);
        }catch(Exception e){throw new IllegalStateException(e);}});
    }
    @Test void combinedSelfMonitorNativeSaveHasTwoMetricLinksAndDurableRetry() {
        Map<String,Object>body=selfBody();Map<String,Object>a=selfPost(body),b=selfPost(body);
        assertEquals(a.get("data"),b.get("data"));assertEquals(1,h.count("bp_self_monitor_record"));assertEquals(1,h.count("alert_source_revision"));assertEquals(2,h.count("alert_record"));assertEquals(2,h.count("alert_source_link"));
    }
    @Test void separateSameValuedReadingsAreIndependentRetests() {save(body("18"));save(body("18"));assertEquals(2,h.count("alert_source_identity"));assertEquals(2,h.count("alert_record"));}

    @Test void enabledSavesNeverCallTheLegacyImmediateNotificationSender() {
        save(body("18"));verifyNoInteractions((NotificationAudienceService)ReflectionTestUtils.getField(journey,"audience"));assertTrue(h.count("alert_notification")>0);
    }
    @Test void gateToggleRetryEnvelopeIsRejectedWithoutAnotherRawRow() {
        Map<String,Object>b=body("18");save(b);long count=h.count("health_measurement");ReflectionTestUtils.setField(h.p,"enabled",false);
        h.denied(503,()->save(b));assertEquals(count,h.count("health_measurement"));
        b.remove("commandKey");b.remove("occurredAt");save(b);assertEquals(count+1,h.count("health_measurement"));
    }
    @Test void selfMonitorGateToggleRetryCannotCreateAnotherRawRow() {
        Map<String,Object>b=selfBody();selfPost(b);ReflectionTestUtils.setField(h.p,"enabled",false);long count=h.count("bp_self_monitor_record");
        h.denied(503,()->h.db.tx.execute(tx->self.saveRequest(b)));assertEquals(count,h.count("bp_self_monitor_record"));
        b.remove("commandKey");b.remove("occurredAt");h.db.tx.execute(tx->self.saveRequest(b));assertEquals(count+1,h.count("bp_self_monitor_record"));
    }
    @Test void explicitMeasurementOffsetMustMatchNativeWallTimeAndRollBackCompletely() {
        Map<String,Object>b=body("18");b.put("occurredAt","2026-10-13T10:59:00+01:00");long count=h.count("health_measurement");h.denied(400,()->save(b));
        assertEquals(count,h.count("health_measurement"));assertEquals(0,h.count("measurement_command"));assertEquals(0,h.count("alert_source_revision"));
    }
    @Test void sourceWriteAndCommandRollBackWhenTransactionalOutboxEnqueueFails() {
        h.db.jdbc.execute("DROP TABLE alert_notification");long count=h.count("health_measurement");assertThrows(org.springframework.dao.DataAccessException.class,()->save(body("18")));
        assertEquals(count,h.count("health_measurement"));assertEquals(0,h.count("measurement_command"));assertEquals(0,h.count("alert_source_revision"));assertEquals(0,h.count("alert_event"));
    }
    @Test void unknownContextIsNotClassifiedAsNormalOrGivenAnAutomaticAlert() {
        Map<String,Object>b=selfBody();b.put("measureType","BG");b.remove("systolicBp");b.remove("diastolicBp");b.remove("measurePeriod");
        Map<String,Object>result=(Map<String,Object>)selfPost(b).get("data");assertEquals(Collections.emptyList(),result.get("followUps"));assertEquals(1,h.count("alert_source_identity"));
        Map<String,Object>source=h.repo.readSource(1,10,"BP_SELF_MONITOR",id(result.get("sourceId")));assertEquals("UNCLASSIFIABLE",h.service.assess(source,"GLUCOSE").get("status"));
    }

    @Test void enabledNativeReadersDoNotReuseRevokedAccountOrAdministratorAuthority() {
        save(body("18"));h.db.jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");
        h.denied(403,()->CurrentUserUtil.runAsUser(1L,()->journey.measurements(10L,null,null,null)));
        h.denied(403,()->CurrentUserUtil.runAsUser(1L,()->self.listByPatient(10L)));
    }

    @Test void enabledNativeSaveResultIsNeverPubliclyCacheable() {
        h.db.tx.execute(tx->{try {org.springframework.mock.web.MockHttpServletResponse r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/bp-self-monitor/save").requestAttr("userId",1L).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json(selfBody()))).andReturn().getResponse();assertEquals("no-store, private",r.getHeader("Cache-Control"));return null;}catch(Exception e){throw new IllegalStateException(e);}});
    }

    @Test void historicalImportStaysUntrackedUntilExplicitAdoptionAndRejectsForgedWorkflowMetadata() {
        org.familyhealthcare.service.ClinicalImportService importer=new org.familyhealthcare.service.ClinicalImportService();ReflectionTestUtils.setField(importer,"scope",mock(DataScopeHelper.class));
        // 明确的 H2 夹具兼容：仅本例扩列；元数据是固定的五查询合成响应，非 MySQL 证明。
        h.db.jdbc.execute("ALTER TABLE bp_self_monitor_record ALTER COLUMN record_time VARCHAR(15)");
        h.db.jdbc.execute("ALTER TABLE bp_self_monitor_record ADD import_facts_json VARCHAR(4096)");
        org.springframework.jdbc.core.JdbcTemplate metadata=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        List<List<Map<String,Object>>> syntheticSchema=Arrays.asList(
Arrays.asList(
map("schema_name","explicitly_synthetic_fixture","server_version","5.7.18","client_charset","utf8mb4","connection_charset","utf8mb4","results_charset","utf8mb4")
),
Arrays.asList(
map("table_name","bp_self_monitor_record","engine","InnoDB","table_collation","utf8mb4_general_ci"),
map("table_name","medical_record","engine","InnoDB","table_collation","utf8mb4_general_ci")
),
Arrays.asList(
map("table_name","bp_self_monitor_record","column_name","import_facts_json","data_type","text","column_type","text","is_nullable","YES","column_default",null,"character_maximum_length",65535,"character_set_name","utf8mb4"),
map("table_name","medical_record","column_name","import_facts_json","data_type","text","column_type","text","is_nullable","YES","column_default",null,"character_maximum_length",65535,"character_set_name","utf8mb4"),
map("table_name","bp_self_monitor_record","column_name","record_time","data_type","varchar","column_type","varchar(15)","is_nullable","YES","column_default",null,"character_maximum_length",15,"character_set_name","utf8mb4")
),
Arrays.asList(
map("table_name","bp_self_monitor_record","index_name","PRIMARY","non_unique",0,"seq_in_index",1,"column_name","id","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","medical_record","index_name","PRIMARY","non_unique",0,"seq_in_index",1,"column_name","id","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","bp_self_monitor_record","index_name","uk_bp_external","non_unique",0,"seq_in_index",1,"column_name","patient_id","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","bp_self_monitor_record","index_name","uk_bp_external","non_unique",0,"seq_in_index",2,"column_name","source_type","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","bp_self_monitor_record","index_name","uk_bp_external","non_unique",0,"seq_in_index",3,"column_name","source_external_id","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","medical_record","index_name","uk_record_external","non_unique",0,"seq_in_index",1,"column_name","patient_id","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","medical_record","index_name","uk_record_external","non_unique",0,"seq_in_index",2,"column_name","source_type","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","medical_record","index_name","uk_record_external","non_unique",0,"seq_in_index",3,"column_name","source_external_id","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","bp_self_monitor_record","index_name","idx_bp_patient_date","non_unique",1,"seq_in_index",1,"column_name","patient_id","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","bp_self_monitor_record","index_name","idx_bp_patient_date","non_unique",1,"seq_in_index",2,"column_name","record_date","sub_part",null,"index_type","BTREE","collation","A"),
map("table_name","bp_self_monitor_record","index_name","idx_bp_patient_date","non_unique",1,"seq_in_index",3,"column_name","record_time","sub_part",null,"index_type","BTREE","collation","A")
),
Arrays.asList(
map("trigger_name","if57_bp_insert_facts","table_name","bp_self_monitor_record","event_manipulation","INSERT","action_timing","BEFORE","action_orientation","ROW","action_condition",null,"action_statement","BEGIN\n  IF NEW.import_facts_json IS NOT NULL THEN\n    IF OCTET_LENGTH(NEW.import_facts_json) > 4096 OR JSON_VALID(NEW.import_facts_json) <> 1 THEN\n      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_INVALID_IMPORT_FACTS';\n    END IF;\n    IF JSON_TYPE(NEW.import_facts_json) <> 'OBJECT'\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.version')) <=> 'INTEGER')\n      OR NOT (JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.version')) <=> '1')\n      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.format')) <=> BINARY NEW.source_type)\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.timestamps')) <=> 'OBJECT')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.basis')) <=> 'OBJECT')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.readings')) <=> 'ARRAY')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.selectedTimestamp')) <=> 'STRING')\n      OR NOT (JSON_CONTAINS_PATH(NEW.import_facts_json, 'all', '$.version', '$.format', '$.kind', '$.timestamps', '$.selectedTimestamp', '$.basis', '$.readings') <=> 1)\n      OR NEW.source_external_id IS NULL OR CHAR_LENGTH(TRIM(NEW.source_external_id)) = 0\n      OR NOT (BINARY NEW.source_type <=> BINARY 'FHIR') AND NOT (BINARY NEW.source_type <=> BINARY 'DEVICE')\n      OR NOT (JSON_LENGTH(NEW.import_facts_json) <=> IF(BINARY NEW.source_type = BINARY 'DEVICE', 8, 7))\n      OR (BINARY NEW.source_type = BINARY 'DEVICE' AND NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.deviceUnit')) <=> 'OBJECT'))\n      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.kind')) <=> BINARY NEW.measure_type)\n      OR NOT (BINARY NEW.measure_type <=> BINARY 'BP') AND NOT (BINARY NEW.measure_type <=> BINARY 'BG') AND NOT (BINARY NEW.measure_type <=> BINARY 'BP_BG')\n      OR NOT (JSON_LENGTH(JSON_EXTRACT(NEW.import_facts_json, '$.readings')) <=> IF(BINARY NEW.measure_type = BINARY 'BP', 2, IF(BINARY NEW.measure_type = BINARY 'BG', 1, 3))) THEN\n      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_INVALID_IMPORT_FACTS';\n    END IF;\n  END IF;\nEND","character_set_client","utf8mb4","collation_connection","utf8mb4_general_ci","database_collation","utf8mb4_general_ci"),
map("trigger_name","if57_bp_update_facts","table_name","bp_self_monitor_record","event_manipulation","UPDATE","action_timing","BEFORE","action_orientation","ROW","action_condition",null,"action_statement","BEGIN\n  IF NOT ((BINARY NEW.import_facts_json <=> BINARY OLD.import_facts_json)\n      AND (OCTET_LENGTH(NEW.import_facts_json) <=> OCTET_LENGTH(OLD.import_facts_json))) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_ORIGINAL_BINDING_IMMUTABLE';\n  END IF;\n  IF OLD.import_facts_json IS NOT NULL AND (\n      NOT (NEW.id <=> OLD.id) OR NOT (NEW.patient_id <=> OLD.patient_id) OR NOT (NEW.user_id <=> OLD.user_id)\n      OR NOT ((BINARY NEW.source_type <=> BINARY OLD.source_type) AND (OCTET_LENGTH(NEW.source_type) <=> OCTET_LENGTH(OLD.source_type)))\n      OR NOT ((BINARY NEW.source_external_id <=> BINARY OLD.source_external_id) AND (OCTET_LENGTH(NEW.source_external_id) <=> OCTET_LENGTH(OLD.source_external_id)))) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_ORIGINAL_BINDING_IMMUTABLE';\n  END IF;\nEND","character_set_client","utf8mb4","collation_connection","utf8mb4_general_ci","database_collation","utf8mb4_general_ci"),
map("trigger_name","if57_medical_insert_facts","table_name","medical_record","event_manipulation","INSERT","action_timing","BEFORE","action_orientation","ROW","action_condition",null,"action_statement","BEGIN\n  IF NEW.import_facts_json IS NOT NULL THEN\n    IF OCTET_LENGTH(NEW.import_facts_json) > 4096 OR JSON_VALID(NEW.import_facts_json) <> 1 THEN\n      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_INVALID_IMPORT_FACTS';\n    END IF;\n    IF JSON_TYPE(NEW.import_facts_json) <> 'OBJECT'\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.version')) <=> 'INTEGER')\n      OR NOT (JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.version')) <=> '1')\n      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.format')) <=> BINARY NEW.source_type)\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.timestamps')) <=> 'OBJECT')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.basis')) <=> 'OBJECT')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.readings')) <=> 'ARRAY')\n      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, '$.selectedTimestamp')) <=> 'STRING')\n      OR NOT (JSON_CONTAINS_PATH(NEW.import_facts_json, 'all', '$.version', '$.format', '$.kind', '$.timestamps', '$.selectedTimestamp', '$.basis', '$.readings') <=> 1)\n      OR NEW.source_external_id IS NULL OR CHAR_LENGTH(TRIM(NEW.source_external_id)) = 0\n      OR NOT (BINARY NEW.source_type <=> BINARY 'FHIR')\n      OR NOT (JSON_LENGTH(NEW.import_facts_json) <=> 7)\n      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, '$.kind')) <=> BINARY 'LAB')\n      OR NOT (JSON_LENGTH(JSON_EXTRACT(NEW.import_facts_json, '$.readings')) <=> 1) THEN\n      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_INVALID_IMPORT_FACTS';\n    END IF;\n  END IF;\nEND","character_set_client","utf8mb4","collation_connection","utf8mb4_general_ci","database_collation","utf8mb4_general_ci"),
map("trigger_name","if57_medical_update_facts","table_name","medical_record","event_manipulation","UPDATE","action_timing","BEFORE","action_orientation","ROW","action_condition",null,"action_statement","BEGIN\n  IF NOT ((BINARY NEW.import_facts_json <=> BINARY OLD.import_facts_json)\n      AND (OCTET_LENGTH(NEW.import_facts_json) <=> OCTET_LENGTH(OLD.import_facts_json))) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_ORIGINAL_BINDING_IMMUTABLE';\n  END IF;\n  IF OLD.import_facts_json IS NOT NULL AND (\n      NOT (NEW.id <=> OLD.id) OR NOT (NEW.patient_id <=> OLD.patient_id) OR NOT (NEW.user_id <=> OLD.user_id)\n      OR NOT ((BINARY NEW.source_type <=> BINARY OLD.source_type) AND (OCTET_LENGTH(NEW.source_type) <=> OCTET_LENGTH(OLD.source_type)))\n      OR NOT ((BINARY NEW.source_external_id <=> BINARY OLD.source_external_id) AND (OCTET_LENGTH(NEW.source_external_id) <=> OCTET_LENGTH(OLD.source_external_id)))) THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IF57_ORIGINAL_BINDING_IMMUTABLE';\n  END IF;\nEND","character_set_client","utf8mb4","collation_connection","utf8mb4_general_ci","database_collation","utf8mb4_general_ci")
)
);
        java.util.concurrent.atomic.AtomicInteger metadataStep=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation->syntheticSchema.get(metadataStep.getAndIncrement())).when(metadata).query(any(org.springframework.jdbc.core.PreparedStatementCreator.class),any(org.springframework.jdbc.core.RowMapper.class));
        org.familyhealthcare.service.ClinicalImportFactsRepository factsRepository=new org.familyhealthcare.service.ClinicalImportFactsRepository();
        ReflectionTestUtils.setField(factsRepository,"jdbc",metadata);
        ReflectionTestUtils.setField(importer,"factsRepository",factsRepository);
        Map<String,Object> reading=map("systolic",190,"diastolic",125,"observedAt","2026-10-13T11:59:00Z","sourceExternalId","fictional-device-77");
        for(Map<String,Object> forged:Arrays.asList(map("workflowVersion",1),map("alert_type","MEASUREMENT_FOLLOW_UP"),map("sourceRevision",3))) {
            Map<String,Object> bad=new LinkedHashMap<>(reading);bad.putAll(forged);assertThrows(IllegalArgumentException.class,()->importer.preview(10L,map("readings",Arrays.asList(bad))));
        }
        Map<String,Object> preview=importer.preview(10L,map("readings",Arrays.asList(reading)));assertEquals("UNTRACKED_HISTORICAL_COPY",preview.get("workflowCoverage"));
        org.familyhealthcare.service.ClinicalImportRowWriter writer=new org.familyhealthcare.service.ClinicalImportRowWriter();ReflectionTestUtils.setField(writer,"vitalService",self);ReflectionTestUtils.setField(writer,"vitalMapper",ReflectionTestUtils.getField(self,"baseMapper"));
        Object parsed=ReflectionTestUtils.invokeMethod(importer,"parseRows",map("readings",Arrays.asList(reading)));
        org.familyhealthcare.service.ClinicalImportFacts.PreparedRow item=(org.familyhealthcare.service.ClinicalImportFacts.PreparedRow)((List<?>)ReflectionTestUtils.getField(parsed,"rows")).get(0);
        assertEquals(Boolean.TRUE,h.db.tx.execute(tx->writer.write(10L,"Fictional",item)));assertEquals(0,h.count("alert_source_identity"));assertEquals(0,h.count("alert_record"));assertEquals(0,h.count("alert_notification"));
        long id=h.db.jdbc.queryForObject("SELECT id FROM bp_self_monitor_record WHERE source_external_id='fictional-device-77'",Long.class);
        h.db.tx.execute(tx->h.service.adopt(1L,"BP_SELF_MONITOR",id,"BP",map("patientId",10,"manualFollowUp",false,"reason","Explicitly review fictional imported reading"),UUID.randomUUID().toString()));
        assertEquals(1,h.count("alert_source_identity"));assertEquals(1,h.count("alert_record"));
    }
    @Test void enabledNativeListReturnsTheCurrentAuthorityFailureRatherThanAHiddenLoadError() throws Exception {
        h.db.jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");
        assertEquals(403,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/bp-self-monitor/list").param("patientId","10")).andReturn().getResponse().getStatus());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"HEALTH_MEASUREMENT,PERSONAL","HEALTH_MEASUREMENT,CLINICAL","BP_SELF_MONITOR,PERSONAL","BP_SELF_MONITOR,CLINICAL"})
    void bothNativeSourcesCompleteDistinctPersonalAndClinicalJourneys(String type,String mode) {
        Map<String,Object> saved="HEALTH_MEASUREMENT".equals(type)?save(body("18")):(Map<String,Object>)selfPost(selfBody()).get("data");
        long source=id(saved.get("sourceId")),alert=id(((Map<?,?>)((List<?>)saved.get("followUps")).get(0)).get("alertId"));
        if("CLINICAL".equals(mode)) {
            h.command(1,alert,"ESCALATE",map("reason","Request fictional clinical review","assigneeId",3));
            h.command(3,alert,"ASSIGN",map("reason","Nursing follow-up","assigneeId",4));h.accept(alert,4);h.submit(alert,4);h.feedback(alert,4,"ASSISTED");
            h.denied(403,()->h.command(1,alert,"CLOSE",map("reason","Owner cannot clinically close")));h.denied(403,()->h.command(4,alert,"CLOSE",map("reason","Nurse cannot clinically close")));
            h.command(3,alert,"CLOSE",map("reason","Current assigned doctor reviewed the fictional handling"));
        } else {h.assign(alert,1);h.accept(alert,1);h.submit(alert,1);h.feedback(alert,1,"SELF");h.command(1,alert,"CLOSE",map("reason","Personal handling recorded without clinical approval"));}
        assertEquals("CLOSED",h.row(alert).get("workflowState"));assertEquals(mode,h.row(alert).get("workflowMode"));assertTrue(h.events(1,alert).toString().contains("CLOSE"));
        System.out.println("FICTIONAL_WALKTHROUGH sourceType="+type+" sourceId="+source+" alertId="+alert+" mode="+mode+" state=CLOSED version="+h.version(alert));
    }
    @Test void bothNativeCommandDomainsRejectKeyPayloadClashesAndRevokedSaveReplays() {
        Map<String,Object> journeyBody=body("18"),selfBody=selfBody();save(journeyBody);selfPost(selfBody);
        Map<String,Object> changedJourney=new LinkedHashMap<>(journeyBody);changedJourney.put("valuePrimary","19");h.denied(409,()->save(changedJourney));
        Map<String,Object> changedSelf=new LinkedHashMap<>(selfBody);changedSelf.put("systolicBp",191);h.denied(409,()->h.db.tx.execute(tx->self.saveRequest(changedSelf)));
        assertEquals(2,h.count("measurement_command"));h.db.jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");
        h.denied(403,()->save(journeyBody));h.denied(403,()->h.db.tx.execute(tx->self.saveRequest(selfBody)));assertEquals(2,h.count("alert_source_revision"));
    }

}
