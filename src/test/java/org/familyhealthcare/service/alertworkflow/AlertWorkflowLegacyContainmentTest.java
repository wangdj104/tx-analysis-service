package org.familyhealthcare.service.alertworkflow;

import org.familyhealthcare.entity.AlertRecord;
import org.familyhealthcare.mapper.AlertRecordMapper;
import org.familyhealthcare.service.impl.AlertServiceImpl;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.familyhealthcare.service.workflow.WorkflowData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertWorkflowLegacyContainmentTest {
    AlertWorkflowHarness h; AlertServiceImpl legacy;
    @BeforeEach void setup() throws Exception {
        h=new AlertWorkflowHarness();legacy=new AlertServiceImpl();
        com.baomidou.mybatisplus.core.MybatisConfiguration config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setEnvironment(new org.apache.ibatis.mapping.Environment("test",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),h.db.jdbc.getDataSource()));config.addMapper(AlertRecordMapper.class);
        org.apache.ibatis.session.SqlSessionFactory sessions=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);
        AlertRecordMapper mapper=new org.mybatis.spring.SqlSessionTemplate(sessions).getMapper(AlertRecordMapper.class);
        ReflectionTestUtils.setField(legacy,"medicalAlerts",new org.familyhealthcare.service.medicalrecord.MedicalRecordAlertProjection(
                h.db.jdbc,sessions,h.medicalValidity,
                new org.familyhealthcare.service.medicalrecord.MedicalRecordReferenceVersions(h.db.jdbc,h.medicalValidity,h.db.manager),
                new org.familyhealthcare.service.MedicalRecordReviewAccess(h.db.jdbc)));
        ReflectionTestUtils.setField(legacy,"baseMapper",mapper);DataScopeHelper scope=mock(DataScopeHelper.class);when(scope.requireUserId()).thenReturn(5L);ReflectionTestUtils.setField(legacy,"dataScopeHelper",scope);
    }
    @Test void legacyListAndDirectReadOmitWorkflowRowsButRetainNullTypeRows() {
        long alert=h.adopt();h.db.jdbc.update("INSERT INTO alert_record(patient_id,user_id,alert_type,alert_title,status) VALUES(10,1,NULL,'Old row','PENDING')");
        assertEquals(1,legacy.listRecords(10L,null).size());assertNull(legacy.getById(alert));
    }
    @Test void legacyGenericUpdateCannotClearTheServerOwnedMarker() {
        long alert=h.adopt();AlertRecord fake=new AlertRecord();fake.setId(alert);fake.setAlertType("BP");fake.setStatus("RESOLVED");
        assertFalse(legacy.updateById(fake));assertEquals("OPEN",h.row(alert).get("workflowState"));assertEquals("MEASUREMENT_FOLLOW_UP",h.db.jdbc.queryForObject("SELECT alert_type FROM alert_record WHERE id=?",String.class,alert));
    }
    @Test void legacyGenericCreateCannotForgeWorkflowMarker() {
        AlertRecord fake=new AlertRecord();fake.setPatientId(10L);fake.setUserId(1L);fake.setAlertTitle("Forged");fake.setAlertType("MEASUREMENT_FOLLOW_UP");
        assertThrows(IllegalArgumentException.class,()->legacy.save(fake));assertEquals(0,h.count("alert_record"));
    }
    @Test void familyArchiveExcludesWorkflowAlertsAndStatesThatHistoryIsOmitted() throws Exception {
        h.adopt();h.db.jdbc.update("INSERT INTO alert_record(patient_id,user_id,alert_type,alert_title,status) VALUES(10,1,NULL,'Old row','PENDING')");
        org.familyhealthcare.service.FamilyBackupService backup=backup();
        com.alibaba.fastjson2.JSONObject archive=backup.readArchive(backup.exportArchive());
        com.alibaba.fastjson2.JSONArray alerts=archive.getJSONObject("tables").getJSONArray("alert_record");assertEquals(1,alerts.size());
        for(String key:alerts.getJSONObject(0).keySet())assertFalse(key.toLowerCase(Locale.ROOT).startsWith("workflow_"));
        assertTrue(archive.getJSONArray("warnings").toJSONString().contains("measurement follow-up history"));
    }
    @Test void restoreRejectsMarkerForgeryAndPartialWorkflowHistoryBeforeInsertingAnything() throws Exception {
        org.familyhealthcare.service.FamilyBackupService backup=backup();
        for(Map<String,Object> row:Arrays.asList(map("id",1,"alert_type","MEASUREMENT_FOLLOW_UP"),map("id",1,"alert_type","measurement_follow_up"),map("id",1,"workflow_state","CLOSED"),map("id",1,"workflow_version",1))) {
            byte[]archive=zip(map("format","family-health-backup","version",1,"tables",map("patient",Collections.emptyList(),"alert_record",Arrays.asList(row))));
            assertThrows(IllegalArgumentException.class,()->backup.readArchive(archive));
        }
        byte[]partial=zip(map("format","family-health-backup","version",1,"tables",map("patient",Collections.emptyList(),"alert_event",Arrays.asList(map("alert_id",1,"event_type","CLOSE")))));
        assertThrows(IllegalArgumentException.class,()->backup.readArchive(partial));assertEquals(0,h.count("alert_record"));
    }
    org.familyhealthcare.service.FamilyBackupService backup() {
        org.familyhealthcare.service.FamilyBackupService backup=new org.familyhealthcare.service.FamilyBackupService();ReflectionTestUtils.setField(backup,"jdbc",h.db.jdbc);ReflectionTestUtils.setField(backup,"householdSchema",absentHouseholdSchema());ReflectionTestUtils.setField(backup,"medicalValidity",h.medicalValidity);
        DataScopeHelper scope=mock(DataScopeHelper.class);when(scope.requireUserId()).thenReturn(1L);when(scope.accessiblePatientIds(1L)).thenReturn(Arrays.asList(10L));ReflectionTestUtils.setField(backup,"scope",scope);return backup;
    }
    static org.familyhealthcare.service.workflow.HouseholdWorkflowSchema absentHouseholdSchema(){
        // 同连接只回答两条精确的缺失模式查询；意外 SQL 直接使夹具失败。
        try {
            Set<String> presence=new HashSet<>();
            for(String resource:Arrays.asList("household-workflow-schema.json","visit-followup-schema.json")) {
                try(java.io.InputStream input=AlertWorkflowLegacyContainmentTest.class.getClassLoader().getResourceAsStream(resource)) {
                    presence.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).get("presenceSql").asText());
                }
            }
            javax.sql.DataSource source=mock(javax.sql.DataSource.class);
            java.sql.Connection connection=mock(java.sql.Connection.class);
            java.sql.Statement statement=mock(java.sql.Statement.class);
            when(source.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenAnswer(call->{
                if(!presence.contains(call.getArgument(0)))throw new AssertionError("Unexpected schema SQL: "+call.getArgument(0));
                java.sql.ResultSet result=mock(java.sql.ResultSet.class);
                java.sql.ResultSetMetaData metadata=mock(java.sql.ResultSetMetaData.class);
                when(metadata.getColumnCount()).thenReturn(1);when(result.getMetaData()).thenReturn(metadata);
                when(result.next()).thenReturn(true,false);when(result.getLong(1)).thenReturn(0L);return result;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call->{throw new AssertionError("Unexpected prepared schema SQL: "+call.getArgument(0));});
            return new org.familyhealthcare.service.workflow.HouseholdWorkflowSchema(new org.springframework.jdbc.core.JdbcTemplate(source));
        } catch(java.io.IOException|java.sql.SQLException failure){throw new AssertionError(failure);}
    }
    static org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard absentMedicationTracking(org.springframework.jdbc.core.JdbcTemplate jdbc){org.familyhealthcare.service.medicationexecution.MedicationExecutionProperties p=new org.familyhealthcare.service.medicationexecution.MedicationExecutionProperties();org.familyhealthcare.service.medicationexecution.MedicationExecutionAuthorizationService a=new org.familyhealthcare.service.medicationexecution.MedicationExecutionAuthorizationService(jdbc,p);return new org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard(jdbc,p,a,new org.familyhealthcare.service.medicationexecution.MedicationExecutionRepository(jdbc,null,absentHouseholdSchema(),a),null);}
    static byte[] zip(Map<String,Object>root)throws Exception{java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)){zip.putNextEntry(new java.util.zip.ZipEntry("family-health.json"));zip.write(json(root).getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}return bytes.toByteArray();}

    @Test void concreteFamilyClinicalAndMonitoringProjectionsExcludeWorkflowContentAfterDisableAndRevocation() throws Exception {
        h.adopt();h.db.jdbc.update("INSERT INTO alert_record(patient_id,user_id,alert_type,alert_title,status,alert_level) VALUES(10,1,NULL,'Old row','PENDING','CRITICAL')");
        org.springframework.test.util.ReflectionTestUtils.setField(h.p,"enabled",false);h.db.jdbc.update("UPDATE care_access_grant SET status='REVOKED'");
        org.familyhealthcare.entity.Patient patient=new org.familyhealthcare.entity.Patient();patient.setId(10L);patient.setName("Fictional");
        AlertRecordMapper mapper=(AlertRecordMapper)ReflectionTestUtils.getField(legacy,"baseMapper");
        org.familyhealthcare.controller.FamilyHealthController family=new org.familyhealthcare.controller.FamilyHealthController();mockFields(family);ReflectionTestUtils.setField(family,"alertMapper",mapper);when(((DataScopeHelper)ReflectionTestUtils.getField(family,"scope")).requirePatient(10L)).thenReturn(patient);
        assertEquals(1,family.alerts(10L).getData().size());assertEquals(1,((List<?>)family.visitSummary(10L).getData().get("unresolvedAlerts")).size());
        org.familyhealthcare.service.ClinicalWorkbenchService clinical=new org.familyhealthcare.service.ClinicalWorkbenchService();mockFields(clinical);ReflectionTestUtils.setField(clinical,"alerts",mapper);when(((DataScopeHelper)ReflectionTestUtils.getField(clinical,"scope")).requirePatient(10L)).thenReturn(patient);
        when(((DataScopeHelper)ReflectionTestUtils.getField(clinical,"scope")).requireUserId()).thenReturn(1L);
        org.familyhealthcare.util.CurrentUserUtil.runAsUser(1L,()->{
            assertEquals(1,((List<?>)clinical.attention(10L).get("items")).size());assertEquals(1,((List<?>)clinical.emergencyCard(10L).get("unresolvedAlerts")).size());
            return null;
        });
        org.familyhealthcare.service.impl.MonitoringServiceImpl monitor=new org.familyhealthcare.service.impl.MonitoringServiceImpl();mockFields(monitor);ReflectionTestUtils.setField(monitor,"alertMapper",mapper);when(((DataScopeHelper)ReflectionTestUtils.getField(monitor,"dataScopeHelper")).requirePatient(10L)).thenReturn(patient);
        assertEquals(1,monitor.getSnapshot(10L,7).getActiveAlerts().size());
    }
    @Test void doctorAndOperationsAggregatesCountOnlyLegacyAlerts() throws Exception {
        h.adopt();h.db.jdbc.update("UPDATE alert_record SET alert_level='CRITICAL'");h.db.jdbc.update("INSERT INTO alert_record(patient_id,user_id,alert_type,alert_title,status,alert_level) VALUES(10,1,NULL,'Old row','PENDING','CRITICAL')");
        for(String column:Arrays.asList("name VARCHAR(50)","gender VARCHAR(20)","birth_date DATE","phone VARCHAR(30)"))h.db.jdbc.execute("ALTER TABLE patient ADD "+column);h.db.jdbc.execute("ALTER TABLE doctor_patient_assignment ADD care_team_role VARCHAR(30)");
        org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest();request.setAttribute("userId",5L);request.setAttribute("roleCodes",Arrays.asList("admin"));org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            org.familyhealthcare.service.DoctorWorkspaceService doctor=new org.familyhealthcare.service.DoctorWorkspaceService();ReflectionTestUtils.setField(doctor,"jdbc",h.db.jdbc);assertEquals(1,((Number)doctor.patients().get(0).get("criticalAlerts")).intValue());
            h.db.jdbc.update("UPDATE alert_record SET triggered_at=CURRENT_TIMESTAMP");
            h.db.jdbc.execute("CREATE TABLE care_item(kind VARCHAR(30),status VARCHAR(30),event_at TIMESTAMP,created_at TIMESTAMP)");h.db.jdbc.execute("CREATE TABLE medication_intake(status VARCHAR(30),scheduled_at TIMESTAMP)");h.db.jdbc.execute("CREATE TABLE care_appointment(status VARCHAR(30),start_at TIMESTAMP)");
            org.familyhealthcare.service.CareJourneyService journey=new org.familyhealthcare.service.CareJourneyService();ReflectionTestUtils.setField(journey,"jdbc",h.db.jdbc);Map<?,?>stats=(Map<?,?>)journey.operations(java.time.LocalDate.now().minusDays(1),java.time.LocalDate.now().plusDays(1)).get("alerts");assertEquals(1,((Number)stats.get("total")).intValue());
        }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
    }
    @Test void preUpgradeLegacyMarkerPredicatesDoNotRequireNewColumnsAndLegacyDeletesStillWork() throws Exception {
        h.db.jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");h.db.jdbc.execute("DROP TABLE alert_record CASCADE");h.db.jdbc.execute(AlertWorkflowTestDatabase.h2(AlertWorkflowTestDatabase.tableSql(AlertWorkflowTestDatabase.read("src/test/resources/alertworkflow/legacy-alert-schema.sql"),"alert_record")));
        // The fixture has the complete old entity projection and no workflow columns.
        h.db.jdbc.update("INSERT INTO alert_record(id,patient_id,user_id,alert_type,alert_title,status) VALUES(1,10,1,NULL,'Old row','PENDING'),(2,10,1,'MEASUREMENT_FOLLOW_UP','Reserved','PENDING')");
        assertEquals(1,legacy.listRecords(10L,null).size());assertNull(legacy.getById(2L));assertTrue(legacy.deleteRecord(1L));assertFalse(legacy.deleteRecord(2L));
    }
    static void mockFields(Object object)throws Exception{for(java.lang.reflect.Field field:object.getClass().getDeclaredFields())if(field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))ReflectionTestUtils.setField(object,field.getName(),field.getType()==org.familyhealthcare.service.medicationexecution.MedicationTrackingGuard.class?absentMedicationTracking(new org.springframework.jdbc.core.JdbcTemplate()):mock(field.getType()));}

    @Test void mysqlCaseInsensitiveReservedMarkerCannotBeForgedThroughLegacySave() {
        AlertRecord row=new AlertRecord();row.setPatientId(10L);row.setUserId(1L);row.setAlertType("measurement_follow_up");
        assertThrows(IllegalArgumentException.class,()->legacy.save(row));assertEquals(0,h.count("alert_record"));
    }

    @Test void archiveCannotSmuggleSourceIdentityMetadataInsideARawSelfMonitorCopy() throws Exception {
        byte[] partial=zip(map("format","family-health-backup","version",1,"tables",map("patient",Collections.emptyList(),"bp_self_monitor_record",Arrays.asList(map("id",1,"patient_id",10,"source_revision",2,"source_status","INVALIDATED")))));
        assertThrows(IllegalArgumentException.class,()->backup().readArchive(partial));assertEquals(0,h.count("alert_source_identity"));
    }

}
