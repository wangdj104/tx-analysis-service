package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class CareDatabaseWorkflowTest {
    JdbcTemplate jdbc;JdbcDataSource ds;DataScopeHelper scope;CareMembershipService members;
    @BeforeEach void setup()throws Exception{
        ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:care"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        initializeDatabase();
        jdbc.update("INSERT INTO patient(id,name,user_id) VALUES(1,'Test Patient',7)");jdbc.update("INSERT INTO medication(id,patient_id,user_id,drug_name,is_active) VALUES(3,1,7,'Test Medication',1)");
        scope=mock(DataScopeHelper.class);when(scope.requireUserId()).thenReturn(7L);
        members=mock(CareMembershipService.class);when(members.accessiblePatients(7L)).thenReturn(Collections.singletonList(1L));
        MockHttpServletRequest request=new MockHttpServletRequest();request.setAttribute("userId",7L);request.setAttribute("username","Test Caregiver");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}

    private void initializeDatabase()throws java.io.IOException{
        String sql;
        try(java.io.InputStream input=new ClassPathResource("sql/init.sql").getInputStream()){
            sql=org.springframework.util.StreamUtils.copyToString(input,java.nio.charset.StandardCharsets.UTF_8);
        }
        // MySQL index names are table-local; H2 requires schema-wide uniqueness.
        // Rename only indexes for H2, preserving all columns, constraints and seed data.
        java.util.regex.Matcher indexes=java.util.regex.Pattern.compile("(?m)^(\\s*(?:UNIQUE )?KEY\\s+)`([^`]+)`").matcher(sql);
        StringBuffer compatibleSql=new StringBuffer();
        int index=0;
        while(indexes.find()){
            indexes.appendReplacement(compatibleSql,java.util.regex.Matcher.quoteReplacement(indexes.group(1)+"`"+indexes.group(2)+"_h2_"+(index++)+"`"));
        }
        indexes.appendTail(compatibleSql);
        ResourceDatabasePopulator populator=new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(compatibleSql.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(ds);
    }

    @Test void schemaIsRepeatableAndStockConsumptionIsIdempotent()throws Exception{
        int roles=jdbc.queryForObject("SELECT COUNT(*) FROM sys_role",Integer.class);
        int menus=jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu",Integer.class);
        initializeDatabase();
        assertEquals(roles,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role",Integer.class));
        assertEquals(menus,jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class));
        MedicationStockService stock=new MedicationStockService();MedicationMapper meds=mock(MedicationMapper.class);CareItemMapper items=mock(CareItemMapper.class);
        Medication med=new Medication();med.setId(3L);med.setPatientId(1L);when(meds.selectById(3L)).thenReturn(med);
        ReflectionTestUtils.setField(stock,"jdbc",jdbc);ReflectionTestUtils.setField(stock,"scope",scope);ReflectionTestUtils.setField(stock,"medications",meds);ReflectionTestUtils.setField(stock,"items",items);
        stock.configure(1L,3L,new BigDecimal("20"),"tablet",7,BigDecimal.ZERO);
        MedicationIntake intake=new MedicationIntake();intake.setId(50L);intake.setPatientId(1L);intake.setMedicationId(3L);intake.setStatus("TAKEN");
        stock.consume(intake,new BigDecimal("1.5"),"FAMILY");stock.consume(intake,new BigDecimal("1.5"),"FAMILY");
        assertEquals(0,new BigDecimal("18.5").compareTo(jdbc.queryForObject("SELECT quantity FROM medication_stock WHERE medication_id=3",BigDecimal.class)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM medication_stock_movement WHERE source_key='INTAKE:50'",Integer.class));
        stock.purchase(1L,3L,new BigDecimal("10"),"Restock");assertEquals(0,new BigDecimal("28.5").compareTo(jdbc.queryForObject("SELECT quantity FROM medication_stock WHERE medication_id=3",BigDecimal.class)));
        assertThrows(IllegalArgumentException.class,()->stock.purchase(1L,3L,new BigDecimal("-1"),"errorinput"));
    }

    @Test void backupRestoresIndependentPatientAndAttachmentLinks()throws Exception{
        jdbc.update("INSERT INTO medical_record(id,patient_name,patient_id,user_id,record_date) VALUES(9,'Test Patient',1,7,'2026-09-15')");
        jdbc.update("INSERT INTO medical_record_attachment(id,record_id,file_name,file_path,file_content) VALUES(10,9,'report.pdf','inline://9/report.pdf','cGRmYnl0ZXM=')");
        FamilyBackupService service=backup();byte[] archive=service.exportArchive();Map<String,Object>preview=service.preview(archive);
        assertEquals(1,((Map<?,?>)preview.get("counts")).get("medical_record_attachment"));
        Map<String,Object>result=service.restore(archive);Long restored=((Number)((Collection<?>)result.get("patientIds")).iterator().next()).longValue();assertNotEquals(1L,restored);
        Long record=jdbc.queryForObject("SELECT id FROM medical_record WHERE patient_id=?",Long.class,restored);
        assertNotEquals(9L,record);assertEquals("cGRmYnl0ZXM=",jdbc.queryForObject("SELECT file_content FROM medical_record_attachment WHERE record_id=?",String.class,record));
        assertEquals("Test Patient",jdbc.queryForObject("SELECT name FROM patient WHERE id=1",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM patient",Integer.class));
    }

    @Test void restoreFailureRollsBackAllInsertedRows()throws Exception{
        FamilyBackupService service=backup();com.alibaba.fastjson2.JSONObject root=service.readArchive(service.exportArchive());
        root.getJSONObject("tables").getJSONArray("medication").getJSONObject(0).put("unknown_column","bad schema");
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)){zip.putNextEntry(new java.util.zip.ZipEntry("family-health.json"));zip.write(root.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertThrows(RuntimeException.class,()->transaction.execute(status->{try{return service.restore(bytes.toByteArray());}catch(Exception e){throw new RuntimeException(e);}}));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM patient",Integer.class));
    }

    @Test void invitationIsSingleUseAndAddsSharedPatient(){
        CareMembershipService service=new CareMembershipService();PatientMapper patients=mock(PatientMapper.class);Patient p=new Patient();p.setId(1L);p.setUserId(7L);when(patients.selectById(1L)).thenReturn(p);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"patients",patients);
        String code=service.invite(1L);MockHttpServletRequest r=new MockHttpServletRequest();r.setAttribute("userId",8L);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
        assertEquals(1L,service.join(code,"Femalechild"));assertTrue(service.isMember(1L,8L));assertEquals(Collections.singletonList(1L),service.accessiblePatients(8L));
        assertThrows(IllegalArgumentException.class,()->service.join(code,"Femalechild"));
    }
    FamilyBackupService backup(){FamilyBackupService service=new FamilyBackupService();ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"scope",scope);ReflectionTestUtils.setField(service,"membership",members);return service;}
}
