package org.familyhealthcare.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.impl.*;
import org.familyhealthcare.util.DataScopeHelper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic database only: production services, Spring transaction proxies and MyBatis mappers. */
class ClinicalImportDialysisPersistenceTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ClinicalImportService imports;
    private DialysisRecordService dialysis;
    private DialysisRecordMapper dialysisMapper;

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(H2Database.class);
        jdbc = context.getBean(JdbcTemplate.class);
        String sql;
        try (java.io.InputStream input = new ClassPathResource("sql/init.sql").getInputStream()) {
            sql = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        StringBuilder selected = new StringBuilder();
        for (String table : Arrays.asList("medical_record", "medical_record_item", "medical_record_attachment",
                "bp_self_monitor_record", "dialysis_record", "clinical_import_batch")) {
            Matcher ddl = Pattern.compile("CREATE TABLE IF NOT EXISTS `" + table + "` \\(.*?;", Pattern.DOTALL).matcher(sql);
            assertTrue(ddl.find(), table);
            selected.append(ddl.group().replaceAll("KEY `([^`]+)`", "KEY `" + table + "_$1`")).append('\n');
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(selected.toString().getBytes(StandardCharsets.UTF_8)));
        populator.execute(context.getBean(DataSource.class));
        imports = context.getBean(ClinicalImportService.class);
        dialysis = context.getBean(DialysisRecordService.class);
        dialysisMapper = context.getBean(DialysisRecordMapper.class);
        assertTrue(AopUtils.isAopProxy(imports));
        assertTrue(AopUtils.isAopProxy(context.getBean(MedicalRecordService.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(MedicalRecordReviewAccess.class)));
        assertTrue(AopUtils.isAopProxy(dialysis));
    }

    @AfterEach void cleanup() { context.close(); }

    @Test void nestedMapperFailureRollsBackOnlyTheFailedImportRow() {
        Map<String,Object> payload = fhir(lab("first", "K"), lab("failed", repeat("private-source-value", 50)), lab("last", "Na"));
        Map<String,Object> result = assertDoesNotThrow(() -> imports.commit(1L, payload));
        assertEquals(2, result.get("created")); assertEquals(1, result.get("errors"));
        assertEquals(2, count("medical_record")); assertEquals(2, count("medical_record_item"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM medical_record WHERE source_external_id='failed'", Integer.class));
        String summary = jdbc.queryForObject("SELECT summary FROM clinical_import_batch", String.class);
        assertTrue(summary.length() <= 500); assertFalse(summary.contains("private-source-value"));
        assertFalse(summary.toLowerCase(Locale.ROOT).contains("insert into"));
        Map<String,Object> retry = imports.commit(1L, fhir(lab("first", "K"), lab("last", "Na")));
        assertEquals(0, retry.get("created")); assertEquals(2, retry.get("skipped")); assertEquals(2, count("medical_record"));
    }

    @Test void aRowWhoseCommitFailsIsNotCountedAsCreated() {
        context.getBean(FaultingTransactionManager.class).failCommit = true;
        Map<String,Object> result = assertDoesNotThrow(() -> imports.commit(1L, fhir(lab("first", "K"), lab("fail-commit", "K"), lab("last", "Na"))));
        assertEquals(2, result.get("created")); assertEquals(1, result.get("errors"));
        assertEquals(2, count("medical_record")); assertEquals(2, count("medical_record_item"));
        assertEquals(2, jdbc.queryForObject("SELECT item_count FROM clinical_import_batch", Integer.class));
    }

    @Test void anEnclosingCallerRollbackCannotUndoTheImportsReportedAsCommitted() {
        new TransactionTemplate(context.getBean(FaultingTransactionManager.class)).execute(status -> {
            Map<String,Object> result = imports.commit(1L, device("outer", 125));
            assertEquals(1, result.get("created")); status.setRollbackOnly(); return null;
        });
        assertEquals(1, count("bp_self_monitor_record")); assertEquals(1, count("clinical_import_batch"));
    }

    @Test void externalIdentityIncludesPatientAndExactSourceType() {
        Map<String,Object> observation = lab("same", "2339-0");
        observation.put("valueQuantity", map("value", "5.5", "unit", "mmol/L"));
        assertEquals(1, imports.commit(1L, fhir(observation)).get("created"));
        assertEquals(1, imports.commit(1L, device("same", 125)).get("created"));
        assertEquals(1, imports.commit(2L, device("same", 125)).get("created"));
        assertEquals(1, imports.commit(1L, device("same", 125)).get("skipped"));
        assertEquals(3, count("bp_self_monitor_record"));
    }

    @Test void concurrentDuplicateImportsAreClassifiedAfterTheUniqueConstraintWins() throws Exception {
        installImportRaceBarrier(context);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Callable<Map<String,Object>> run = () -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return imports.commit(1L, device("race", 125)); };
            Future<Map<String,Object>> first = pool.submit(run), second = pool.submit(run);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            Map<String,Object> a = first.get(10, TimeUnit.SECONDS), b = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, ((Number)a.get("created")).intValue() + ((Number)b.get("created")).intValue());
            assertEquals(1, ((Number)a.get("skipped")).intValue() + ((Number)b.get("skipped")).intValue());
            assertEquals(0, ((Number)a.get("errors")).intValue() + ((Number)b.get("errors")).intValue());
            assertEquals(1, count("bp_self_monitor_record"));
        } finally { pool.shutdownNow(); }
    }

    @Test void missingFhirIdsStillUseNewGeneratedObservationUuids() {
        Map<String,Object> observation = lab(null, "K");
        assertEquals(1, imports.commit(1L, fhir(observation)).get("created"));
        assertEquals(1, imports.commit(1L, fhir(observation)).get("created"));
        List<String> ids = jdbc.queryForList("SELECT source_external_id FROM medical_record", String.class);
        assertNotEquals(ids.get(0), ids.get(1));
        for (String id : ids) UUID.fromString(id.substring("observation-".length()));
    }

    @Test void oneWeightPatchRecalculatesFromStoredInputsAndPreservesOwnerAndInterval() {
        DialysisRecord original = saveDialysis(false, "NORMAL", "2.00");
        DialysisRecord patch = patch(original); patch.setOnWeight(new BigDecimal("72")); patch.setUserId(99L);
        patch.setWeightGain(new BigDecimal("999")); patch.setUfAmount(new BigDecimal("999"));
        assertTrue(dialysis.updateRecord(patch));
        DialysisRecord row = dialysisMapper.selectById(original.getId());
        decimal("4.00", row.getWeightGain()); decimal("4.00", row.getUfAmount()); decimal("1.00", row.getDailyWeightGain());
        assertEquals(4, row.getIntervalDays()); assertEquals(7L, row.getUserId());
        decimal("68", row.getOffWeight()); assertEquals("Original patient prose 原文", row.getRemark());
    }

    @Test void metadataAndSameValuedInputsPreserveExplicitImportedUfAfterReload() {
        for (String type : Arrays.asList("NORMAL", "INCOMPLETE")) {
            DialysisRecord original = saveDialysis(true, type, "9.50");
            assertFalse(dialysisMapper.selectById(original.getId()).isTextImport());
            DialysisRecord patch = patch(original); patch.setRemark("Only metadata 原文");
            assertTrue(dialysis.updateRecord(patch));
            decimal("9.50", dialysisMapper.selectById(original.getId()).getUfAmount());
            patch = patch(original); patch.setOnWeight(new BigDecimal("70.000")); patch.setOffWeight(new BigDecimal("68.000")); patch.setRecordType(type);
            assertTrue(dialysis.updateRecord(patch));
            DialysisRecord reloaded = dialysisMapper.selectById(original.getId());
            decimal("9.50", reloaded.getUfAmount()); assertEquals("Only metadata 原文", reloaded.getRemark());
            if ("INCOMPLETE".equals(type)) assertNull(reloaded.getWeightGain());
        }
    }

    @Test void omittedStoredNullIntervalRemainsNullAndDoesNotReuseStaleDailyGain() {
        DialysisRecord original = saveDialysis(true, "NORMAL", "9.50");
        jdbc.update("UPDATE dialysis_record SET interval_days=NULL WHERE id=?", original.getId());
        DialysisRecord patch = patch(original); patch.setRemark("Metadata keeps missing interval 原文");
        assertTrue(dialysis.updateRecord(patch));
        DialysisRecord row = dialysisMapper.selectById(original.getId());
        assertNull(row.getIntervalDays()); assertNull(row.getDailyWeightGain()); decimal("9.50", row.getUfAmount());
    }

    @Test void incompleteTransitionExplicitlyClearsPersistedDerivedColumns() {
        DialysisRecord original = saveDialysis(false, "NORMAL", "2.00");
        DialysisRecord patch = patch(original); patch.setRecordType("INCOMPLETE");
        assertTrue(dialysis.updateRecord(patch));
        assertDerivedSqlNull(original.getId(), true);
        patch = patch(original); patch.setRecordType("NORMAL"); assertTrue(dialysis.updateRecord(patch));
        decimal("2", dialysisMapper.selectById(original.getId()).getWeightGain());
    }

    @Test void changedPatientOrDateUsesMergedInputsAndClearsUnavailableDryWeightThresholds() {
        DialysisRecord original = saveDialysis(false, "NORMAL", "2.00");
        DialysisRecord patch = patch(original); patch.setPatientId(2L); patch.setRecordDate(LocalDate.of(2026, 11, 1));
        assertTrue(dialysis.updateRecord(patch));
        DialysisRecord row = dialysisMapper.selectById(original.getId());
        assertEquals(2L, row.getPatientId()); assertEquals(7L, row.getUserId()); assertEquals(4, row.getIntervalDays());
        decimal("2.00", row.getWeightGain()); decimal("2.00", row.getUfAmount());
        assertNull(row.getWeight3pct()); assertNull(row.getWeight5pct());
    }

    @Test void concurrentSparsePatchesReadTheLockedCurrentRow() throws Exception {
        DialysisRecord original = saveDialysis(false, "NORMAL", "2.00");
        CountDownLatch written = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> { if (first.getAndSet(false)) { written.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); } return Collections.emptyList(); })
                .when(context.getBean(DialysisScheduleService.class)).list(1L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> { DialysisRecord p = patch(original); p.setOnWeight(new BigDecimal("72")); return dialysis.updateRecord(p); });
            assertTrue(written.await(5, TimeUnit.SECONDS));
            Future<Boolean> b = pool.submit(() -> { secondStarted.countDown(); DialysisRecord p = patch(original); p.setOffWeight(new BigDecimal("69")); return dialysis.updateRecord(p); });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> b.get(150, TimeUnit.MILLISECONDS));
            release.countDown(); assertTrue(a.get(5, TimeUnit.SECONDS)); assertTrue(b.get(5, TimeUnit.SECONDS));
            DialysisRecord row = dialysisMapper.selectById(original.getId());
            decimal("72", row.getOnWeight()); decimal("69", row.getOffWeight()); decimal("3", row.getUfAmount()); decimal("4", row.getWeightGain());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    /** A test-only barrier forces both real row transactions past their duplicate preflight. */
    static void installImportRaceBarrier(AnnotationConfigApplicationContext context) {
        BpSelfMonitorRecordService target = context.getBean(BpSelfMonitorRecordService.class);
        org.springframework.aop.framework.ProxyFactory proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setInterfaces(BpSelfMonitorRecordService.class);
        CyclicBarrier writes = new CyclicBarrier(2);
        proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            if ("saveOwned".equals(invocation.getMethod().getName())) writes.await(5, TimeUnit.SECONDS);
            return invocation.proceed();
        });
        org.springframework.test.util.ReflectionTestUtils.setField(
                (Object) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(ClinicalImportRowWriter.class)),
                "vitalService", proxy.getProxy());
    }

    static void restoreVitalService(AnnotationConfigApplicationContext context) {
        org.springframework.test.util.ReflectionTestUtils.setField(
                (Object) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(ClinicalImportRowWriter.class)),
                "vitalService", context.getBean(BpSelfMonitorRecordService.class));
    }

    private DialysisRecord saveDialysis(boolean imported, String type, String uf) {
        DialysisRecord row = new DialysisRecord(); row.setPatientId(1L);
        row.setRecordDate(LocalDate.of(2026,10,1).plusDays(count("dialysis_record")));
        row.setLastOffWeight(new BigDecimal("68")); row.setOnWeight(new BigDecimal("70")); row.setOffWeight(new BigDecimal("68"));
        row.setIntervalDays(4); row.setRecordType(type); row.setRemark("Original patient prose 原文");
        row.setTextImport(imported); row.setUfAmount(new BigDecimal(uf)); assertTrue(dialysis.saveRecord(row)); return row;
    }
    private DialysisRecord patch(DialysisRecord original) { DialysisRecord patch = new DialysisRecord(); patch.setId(original.getId()); return patch; }
    private void assertDerivedSqlNull(Long id, boolean uf) {
        Map<String,Object> row = jdbc.queryForMap("SELECT weight_gain,uf_amount,daily_weight_gain,dehydration_status,weight_3pct,weight_5pct FROM dialysis_record WHERE id=?", id);
        for (Map.Entry<String,Object> field : row.entrySet()) if (uf || !field.getKey().equals("uf_amount")) assertNull(field.getValue(), field.getKey());
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static void decimal(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
    private static String repeat(String value, int count) { StringBuilder result = new StringBuilder(); for (int i=0;i<count;i++) result.append(value); return result.toString(); }
    private static Map<String,Object> map(Object... values) { Map<String,Object> result = new LinkedHashMap<>(); for (int i=0;i<values.length;i+=2) result.put((String)values[i], values[i+1]); return result; }
    private static Map<String,Object> lab(String id, String code) { return map("resourceType", "Observation", "id", id, "code", map("coding", Collections.singletonList(map("code", code, "display", "Synthetic lab"))), "effectiveDateTime", "2026-10-01T08:00:00Z", "valueString", "Synthetic clinical prose 原文"); }
    @SafeVarargs private static Map<String,Object> fhir(Map<String,Object>... resources) { List<Object> entries = new ArrayList<>(); for (Map<String,Object> resource : resources) entries.add(map("resource", resource)); return map("bundle", map("resourceType", "Bundle", "entry", entries)); }
    private static Map<String,Object> device(String id, int systolic) { return map("readings", Collections.singletonList(map("sourceExternalId", id, "observedAt", "2026-10-01T08:00:00Z", "systolic", systolic, "diastolic", 80))); }

    @TestConfiguration
    @Import(Config.class)
    static class H2Database {
        @Bean DataSource dataSource() { JdbcDataSource ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:importdialysis" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000"); return ds; }
    }

    @TestConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan("org.familyhealthcare.mapper")
    @Import({ClinicalImportService.class, ClinicalImportRowWriter.class, MedicalRecordReviewAccess.class, BpSelfMonitorRecordServiceImpl.class, MedicalRecordServiceImpl.class, DialysisRecordServiceImpl.class})
    static class Config {
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean FaultingTransactionManager transactionManager(DataSource ds) { return new FaultingTransactionManager(ds); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception { MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); MybatisConfiguration configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true); factory.setConfiguration(configuration); return factory.getObject(); }
        @Bean DataScopeHelper scope() { DataScopeHelper scope = mock(DataScopeHelper.class); when(scope.requireUserId()).thenReturn(7L); when(scope.requirePatient(anyLong())).thenAnswer(invocation -> { Patient p = new Patient(); p.setId(invocation.getArgument(0)); p.setName("Synthetic Patient"); p.setUserId(7L); return p; }); return scope; }
        @Bean CareMembershipService careMembershipService() { return mock(CareMembershipService.class); }
        @Bean HealthIndicatorService healthIndicatorService() { return mock(HealthIndicatorService.class); }
        @Bean DryWeightMonthlyService dryWeightMonthlyService() { DryWeightMonthlyService service = mock(DryWeightMonthlyService.class); when(service.getDryWeightByMonth("2026-10",1L)).thenReturn(new BigDecimal("68")); return service; }
        @Bean DialysisScheduleService scheduleService() { return mock(DialysisScheduleService.class); }
    }

    /** Exercise a real transaction commit failure, with Spring rollback-on-commit-failure enabled. */
    static class FaultingTransactionManager extends DataSourceTransactionManager {
        volatile boolean failCommit;
        FaultingTransactionManager(DataSource ds) { super(ds); setRollbackOnCommitFailure(true); }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                try {
                    Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(getDataSource());
                    try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM medical_record WHERE source_external_id='fail-commit'")) {
                        rows.next(); if (rows.getInt(1)>0) throw new TransactionSystemException("Synthetic row commit failure");
                    }
                } catch (java.sql.SQLException e) { throw new TransactionSystemException("Synthetic commit inspection failed", e); }
            }
            super.doCommit(status);
        }
    }
}
