package org.familyhealthcare.service;

import org.familyhealthcare.entity.DialysisRecord;
import org.familyhealthcare.mapper.DialysisRecordMapper;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Called only from the existing guarded disposable MySQL 5.7.18/8.0 acceptance test. */
final class ClinicalImportDialysisMysqlAssertions {
    private static final long PATIENT = 991901L, OTHER_PATIENT = 991902L;
    private ClinicalImportDialysisMysqlAssertions() { }

    static void verify(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(NativeMysqlTestVersion.matches(NativeMysqlTestVersion.selected(),connection.getMetaData().getDatabaseProductVersion()));
        }
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, () -> dataSource);
        context.register(ClinicalImportDialysisPersistenceTest.Config.class);
        context.refresh();
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        ClinicalImportService imports = context.getBean(ClinicalImportService.class);
        DialysisRecordService dialysis = context.getBean(DialysisRecordService.class);
        DialysisRecordMapper mapper = context.getBean(DialysisRecordMapper.class);
        assertTrue(AopUtils.isAopProxy(imports)); assertTrue(AopUtils.isAopProxy(context.getBean(ClinicalImportRowWriter.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(MedicalRecordService.class))); assertTrue(AopUtils.isAopProxy(dialysis));
        assertTrue(AopUtils.isAopProxy(context.getBean(MedicalRecordReviewAccess.class)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM medical_record WHERE patient_id IN (?,?)", Integer.class, PATIENT, OTHER_PATIENT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM bp_self_monitor_record WHERE patient_id IN (?,?)", Integer.class, PATIENT, OTHER_PATIENT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dialysis_record WHERE patient_id IN (?,?)", Integer.class, PATIENT, OTHER_PATIENT));
        try {
            Map<String,Object> result = imports.commit(PATIENT, fhir(lab("native-first", "K"), lab("native-failed", repeat("Synthetic-invalid-code",50)), lab("native-last", "Na")));
            assertEquals(2,result.get("created")); assertEquals(1,result.get("errors"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM medical_record WHERE patient_id=?",Integer.class,PATIENT));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM medical_record_item i JOIN medical_record r ON r.id=i.record_id WHERE r.patient_id=?",Integer.class,PATIENT));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM medical_record WHERE patient_id=? AND source_external_id='native-failed'",Integer.class,PATIENT));
            String summary=jdbc.queryForObject("SELECT summary FROM clinical_import_batch WHERE patient_id=? ORDER BY id DESC LIMIT 1",String.class,PATIENT);
            assertTrue(summary.length()<=500); assertFalse(summary.contains("Synthetic-invalid-code"));
            assertFalse(summary.toLowerCase(Locale.ROOT).contains("insert into"));
            assertEquals(2,imports.commit(PATIENT,fhir(lab("native-first","K"),lab("native-last","Na"))).get("skipped"));

            new TransactionTemplate(context.getBean(ClinicalImportDialysisPersistenceTest.FaultingTransactionManager.class)).execute(status -> {
                assertEquals(1,imports.commit(PATIENT,device("native-enclosed")).get("created"));status.setRollbackOnly();return null;
            });
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM bp_self_monitor_record WHERE patient_id=? AND source_external_id='native-enclosed'",Integer.class,PATIENT));

            Map<String,Object> glucose=lab("native-shared","2339-0");glucose.put("valueQuantity",map("value",5.5,"unit","mmol/L"));
            assertEquals(1,imports.commit(PATIENT,fhir(glucose)).get("created"));
            assertEquals(1,imports.commit(PATIENT,device("native-shared")).get("created"));
            assertEquals(1,imports.commit(OTHER_PATIENT,device("native-shared")).get("created"));
            assertEquals(1,imports.commit(PATIENT,device("native-shared")).get("skipped"));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM bp_self_monitor_record WHERE patient_id IN (?,?) AND source_external_id='native-shared'",Integer.class,PATIENT,OTHER_PATIENT));
            assertConcurrentImport(context,imports,jdbc);

            when(context.getBean(DryWeightMonthlyService.class).getDryWeightByMonth("2026-10",PATIENT)).thenReturn(new BigDecimal("68"));
            for (String type:Arrays.asList("NORMAL","INCOMPLETE")) {
                DialysisRecord row=record(type,true); assertTrue(dialysis.saveRecord(row));
                assertFalse(mapper.selectById(row.getId()).isTextImport());
                DialysisRecord patch=patch(row);patch.setRemark("Native metadata-only patient prose 原文");patch.setUserId(999L);
                assertTrue(dialysis.updateRecord(patch));
                DialysisRecord persisted=mapper.selectById(row.getId());decimal("9.50",persisted.getUfAmount());
                assertEquals(7L,persisted.getUserId());assertEquals(4,persisted.getIntervalDays());
                patch=patch(row);patch.setOnWeight(new BigDecimal("70.000"));patch.setOffWeight(new BigDecimal("68.000"));patch.setRecordType(type);
                assertTrue(dialysis.updateRecord(patch));decimal("9.50",mapper.selectById(row.getId()).getUfAmount());
                assertEquals("Native metadata-only patient prose 原文",mapper.selectById(row.getId()).getRemark());
                jdbc.update("UPDATE dialysis_record SET interval_days=NULL WHERE id=?", row.getId());
                patch = patch(row); patch.setRemark("Native missing interval 原文");
                assertTrue(dialysis.updateRecord(patch));
                persisted = mapper.selectById(row.getId());
                assertNull(persisted.getIntervalDays()); assertNull(persisted.getDailyWeightGain()); decimal("9.50", persisted.getUfAmount());
            }
            DialysisRecord row=record("NORMAL",false);row.setRecordDate(LocalDate.of(2026,10,3));assertTrue(dialysis.saveRecord(row));
            DialysisRecord patch=patch(row);patch.setOnWeight(new BigDecimal("72"));assertTrue(dialysis.updateRecord(patch));
            DialysisRecord persisted=mapper.selectById(row.getId());decimal("4",persisted.getWeightGain());decimal("4",persisted.getUfAmount());decimal("1",persisted.getDailyWeightGain());
            assertEquals(4,persisted.getIntervalDays());
            patch=patch(row);patch.setRecordType("INCOMPLETE");assertTrue(dialysis.updateRecord(patch));
            Map<String,Object> nulled=jdbc.queryForMap("SELECT weight_gain,uf_amount,daily_weight_gain,dehydration_status,weight_3pct,weight_5pct FROM dialysis_record WHERE id=?",row.getId());
            for(Map.Entry<String,Object> field:nulled.entrySet())assertNull(field.getValue(),field.getKey());
            patch=patch(row);patch.setRecordType("NORMAL");patch.setPatientId(OTHER_PATIENT);patch.setRecordDate(LocalDate.of(2026,11,1));
            assertTrue(dialysis.updateRecord(patch));persisted=mapper.selectById(row.getId());
            decimal("4",persisted.getWeightGain());assertEquals(OTHER_PATIENT,persisted.getPatientId());assertEquals(7L,persisted.getUserId());
            assertEquals(4,persisted.getIntervalDays());assertNull(persisted.getWeight3pct());assertNull(persisted.getWeight5pct());
            assertConcurrentDialysis(context,dialysis,mapper);
        } finally {
            jdbc.update("DELETE FROM clinical_import_batch WHERE patient_id IN (?,?)",PATIENT,OTHER_PATIENT);
            jdbc.update("DELETE FROM dialysis_record WHERE patient_id IN (?,?)",PATIENT,OTHER_PATIENT);
            jdbc.update("DELETE FROM bp_self_monitor_record WHERE patient_id IN (?,?)",PATIENT,OTHER_PATIENT);
            jdbc.update("DELETE FROM medical_record WHERE patient_id IN (?,?)",PATIENT,OTHER_PATIENT);
            context.close();
        }
    }

    private static void assertConcurrentImport(AnnotationConfigApplicationContext context,ClinicalImportService imports,JdbcTemplate jdbc)throws Exception {
        ClinicalImportDialysisPersistenceTest.installImportRaceBarrier(context);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Map<String,Object>> first=pool.submit(()->imports.commit(PATIENT,device("native-race")));
            Future<Map<String,Object>> second=pool.submit(()->imports.commit(PATIENT,device("native-race")));
            Map<String,Object>a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);
            assertEquals(1,((Number)a.get("created")).intValue()+((Number)b.get("created")).intValue());
            assertEquals(1,((Number)a.get("skipped")).intValue()+((Number)b.get("skipped")).intValue());
            assertEquals(0,((Number)a.get("errors")).intValue()+((Number)b.get("errors")).intValue());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM bp_self_monitor_record WHERE patient_id=? AND source_external_id='native-race'",Integer.class,PATIENT));
        }finally{pool.shutdownNow();ClinicalImportDialysisPersistenceTest.restoreVitalService(context);}
    }

    private static void assertConcurrentDialysis(AnnotationConfigApplicationContext context,DialysisRecordService dialysis,DialysisRecordMapper mapper)throws Exception {
        DialysisRecord row=record("NORMAL",false);row.setRecordDate(LocalDate.of(2026,10,4));assertTrue(dialysis.saveRecord(row));
        CountDownLatch written=new CountDownLatch(1),release=new CountDownLatch(1),secondStarted=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation->{if(first.getAndSet(false)){written.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}return Collections.emptyList();})
                .when(context.getBean(DialysisScheduleService.class)).list(PATIENT);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<Boolean>a=pool.submit(()->{DialysisRecord patch=patch(row);patch.setOnWeight(new BigDecimal("72"));return dialysis.updateRecord(patch);});
            assertTrue(written.await(5,TimeUnit.SECONDS));
            Future<Boolean>b=pool.submit(()->{secondStarted.countDown();DialysisRecord patch=patch(row);patch.setOffWeight(new BigDecimal("69"));return dialysis.updateRecord(patch);});
            assertTrue(secondStarted.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->b.get(150,TimeUnit.MILLISECONDS));
            release.countDown();assertTrue(a.get(5,TimeUnit.SECONDS));assertTrue(b.get(5,TimeUnit.SECONDS));
            DialysisRecord persisted=mapper.selectById(row.getId());decimal("72",persisted.getOnWeight());decimal("69",persisted.getOffWeight());decimal("3",persisted.getUfAmount());decimal("4",persisted.getWeightGain());
        }finally{release.countDown();pool.shutdownNow();}
    }

    private static DialysisRecord record(String type,boolean imported){DialysisRecord row=new DialysisRecord();row.setPatientId(PATIENT);row.setRecordDate(LocalDate.of(2026,10,"INCOMPLETE".equals(type)?2:1));row.setLastOffWeight(new BigDecimal("68"));row.setOnWeight(new BigDecimal("70"));row.setOffWeight(new BigDecimal("68"));row.setIntervalDays(4);row.setRecordType(type);row.setTextImport(imported);row.setUfAmount(new BigDecimal("9.50"));row.setRemark("Native synthetic patient prose 原文");return row;}
    private static DialysisRecord patch(DialysisRecord row){DialysisRecord patch=new DialysisRecord();patch.setId(row.getId());return patch;}
    private static void decimal(String expected,BigDecimal actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    private static String repeat(String value,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(value);return result.toString();}
    private static Map<String,Object>map(Object...values){Map<String,Object>result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static Map<String,Object>lab(String id,String code){return map("resourceType","Observation","id",id,"code",map("coding",Collections.singletonList(map("code",code,"display","Native synthetic lab"))),"effectiveDateTime","2026-10-01T08:00:00Z","valueString","Native synthetic clinical prose 原文");}
    @SafeVarargs private static Map<String,Object>fhir(Map<String,Object>...resources){List<Object>entries=new ArrayList<>();for(Map<String,Object>resource:resources)entries.add(map("resource",resource));return map("bundle",map("resourceType","Bundle","entry",entries));}
    private static Map<String,Object>device(String id){return map("readings",Collections.singletonList(map("sourceExternalId",id,"observedAt","2026-10-01T08:00:00Z","systolic",125,"diastolic",80)));}
}
