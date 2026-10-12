package org.familyhealthcare.service;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.impl.MedicalRecordServiceImpl;
import org.familyhealthcare.util.DataScopeHelper;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Actual MyBatis mappers and Spring transaction proxy; every row and byte is synthetic. */
class MedicalRecordIntegrityTest {
    @TestConfiguration @EnableTransactionManagement static class TxConfig { }
    Runnable atAttachmentRead, atItemsRead;
    CarePlanTestFixture f; GenericApplicationContext context; MedicalRecordService service; DoctorWorkspaceService doctor;
    @BeforeEach void setup() throws Exception {
        f=new CarePlanTestFixture();
        context=new GenericApplicationContext();
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class, f::jdbc);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, ()->new DataSourceTransactionManager(f.jdbc().getDataSource()));
        MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(f.jdbc().getDataSource());
        factory.setPlugins(new AttachmentReadObserver(this));
        SqlSessionFactory sql=factory.getObject();
        mapper(MedicalRecordMapper.class,sql);mapper(MedicalRecordItemMapper.class,sql);mapper(MedicalRecordAttachmentMapper.class,sql);mapper(PatientMapper.class,sql);
        context.registerBean(HealthIndicatorService.class,()->mock(HealthIndicatorService.class));
        context.registerBean(CareMembershipService.class,()->mock(CareMembershipService.class));
        AnnotatedBeanDefinitionReader reader=new AnnotatedBeanDefinitionReader(context);
        reader.register(TxConfig.class,DataScopeHelper.class,MedicalRecordServiceImpl.class,DoctorWorkspaceService.class);
        try {reader.register(Class.forName("org.familyhealthcare.service.MedicalRecordReviewAccess"));} catch(ClassNotFoundException baseline) { }
        context.refresh();service=context.getBean(MedicalRecordService.class);doctor=context.getBean(DoctorWorkspaceService.class);
        as(7,"GET","/medical-record/1/review-preview");
    }
    <T> void mapper(Class<T> type,SqlSessionFactory sql) throws Exception { MapperFactoryBean<T> factory=new MapperFactoryBean<>(type);factory.setSqlSessionFactory(sql);factory.afterPropertiesSet();T value=factory.getObject();context.registerBean(type,()->value); }
    void as(long actor,String method,String path) { f.as(actor);MockHttpServletRequest request=((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest() instanceof MockHttpServletRequest?(MockHttpServletRequest)((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest():null;request.setMethod(method);request.setRequestURI(path); }
    @AfterEach void close() throws Exception {if(context!=null)context.close();if(f!=null)f.close();}
    MedicalRecord record(String remark) {MedicalRecord r=new MedicalRecord();r.setPatientId(1L);r.setRecordType("BLOOD");r.setSourceType("OCR");r.setRemark(remark);return r;}
    MedicalRecordAttachment attachment(String bytes) {MedicalRecordAttachment a=new MedicalRecordAttachment();a.setFileName("synthetic.txt");a.setFileContent(bytes);return a;}
    MedicalRecord saved(String remark) {MedicalRecord r=record(remark);assertTrue(service.saveRecordWithItems(r,Collections.emptyList()));return r;}
    MedicalRecord patch(MedicalRecord r,String remark){MedicalRecord p=record(remark);p.setId(r.getId());return p;}
    Object invoke(String name,Class<?>[] types,Object...args) {try {Method method=MedicalRecordService.class.getMethod(name,types);return method.invoke(service,args);}catch(NoSuchMethodException absent){fail("Version-bound medical review API is required: "+name);return null;}catch(InvocationTargetException ex){if(ex.getCause() instanceof RuntimeException)throw (RuntimeException)ex.getCause();throw new AssertionError(ex.getCause());}catch(ReflectiveOperationException ex){throw new AssertionError(ex);}}
    MedicalRecord preview(long id,boolean workspace){return (MedicalRecord)invoke("getReviewPreview",new Class<?>[]{Long.class},id);}
    boolean review(long id,long version,String note){return (boolean)invoke("reviewRecord",new Class<?>[]{Long.class,boolean.class,String.class,Long.class},id,true,note,version);}
    long version(long id){return f.jdbc().queryForObject("SELECT content_version FROM medical_record WHERE id=?",Long.class,id);}
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}))
    static class AttachmentReadObserver implements org.apache.ibatis.plugin.Interceptor {
        private final MedicalRecordIntegrityTest owner;
        AttachmentReadObserver(MedicalRecordIntegrityTest owner){this.owner=owner;}
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation)throws Throwable {
            String id=((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId();
            if(id.equals("org.familyhealthcare.mapper.MedicalRecordItemMapper.selectList") && owner.atItemsRead!=null){Runnable action=owner.atItemsRead;owner.atItemsRead=null;action.run();}
            Object result=invocation.proceed();
            if(id.equals("org.familyhealthcare.mapper.MedicalRecordAttachmentMapper.selectList") && owner.atAttachmentRead!=null){Runnable action=owner.atAttachmentRead;owner.atAttachmentRead=null;action.run();}
            return result;
        }
        public Object plugin(Object target){return org.apache.ibatis.plugin.Plugin.wrap(target,this);}
        public void setProperties(Properties properties){ }
    }
    void committed(Runnable action){org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.execute(status->{action.run();return null;});}
    @Test void previewOwnsRepeatableReadSnapshotEvenInsideReadCommittedCaller() {
        MedicalRecord r=record("old body");MedicalRecordItem item=new MedicalRecordItem();item.setItemName("Synthetic analyte");item.setResultValue("old result");assertTrue(service.saveRecordWithItems(r,Collections.singletonList(item)));
        atItemsRead=()->committed(()->{f.jdbc().update("UPDATE medical_record SET remark='new body',content_version=content_version+1 WHERE id=?",r.getId());f.jdbc().update("UPDATE medical_record_item SET result_value='new result' WHERE record_id=?",r.getId());});
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        MedicalRecord view=tx.execute(status->preview(r.getId(),false));assertEquals("old body",view.getRemark());assertEquals(1L,view.getContentVersion());assertEquals("old result",view.getItems().get(0).getResultValue());
    }
    @Test void previewRemainsConsistentWhenBodyItemsAndVersionChangeBeforeReturn() {
        MedicalRecord r=record("original body");MedicalRecordItem item=new MedicalRecordItem();item.setItemName("Synthetic analyte");item.setResultValue("old value");assertTrue(service.saveRecordWithItems(r,Collections.singletonList(item)));
        atAttachmentRead=()->committed(()->{f.jdbc().update("UPDATE medical_record SET remark='new body',content_version=content_version+1 WHERE id=?",r.getId());f.jdbc().update("UPDATE medical_record_item SET result_value='new value' WHERE record_id=?",r.getId());});
        MedicalRecord view=preview(r.getId(),false);assertEquals("original body",view.getRemark());assertEquals("old value",view.getItems().get(0).getResultValue());assertEquals(1L,view.getContentVersion());assertEquals(2L,version(r.getId()));assertThrows(MedicalRecordReviewContract.Conflict.class,()->review(r.getId(),view.getContentVersion(),"old"));
    }
    @Test void returnTimeGateRejectsPatientBindingChangedToAnotherCurrentlyAllowedPatient() {
        MedicalRecord r=saved("raw");f.jdbc().update("UPDATE patient SET user_id=7 WHERE id=2");
        atAttachmentRead=()->committed(()->f.jdbc().update("UPDATE medical_record SET patient_id=2 WHERE id=?",r.getId()));
        assertThrows(IllegalStateException.class,()->preview(r.getId(),false));
    }
    @Test void returnTimeGateRejectsOwnerBindingChangeAndSourceDeletion() {
        for(boolean delete:new boolean[]{false,true}){MedicalRecord r=saved("raw");atAttachmentRead=()->committed(()->f.jdbc().update(delete?"DELETE FROM medical_record WHERE id=?":"UPDATE medical_record SET user_id=8 WHERE id=?",r.getId()));assertThrows(IllegalStateException.class,()->preview(r.getId(),false));}
    }
    @Test void returnTimeGateUsesCurrentGrantNotRepeatableReadGrantSnapshot() {
        MedicalRecord r=saved("raw");f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,granted_by) VALUES(1,8,'FAMILY','READ','MEDICAL',7)");as(8,"GET","/medical-record/1/review-preview");
        atAttachmentRead=()->committed(()->f.jdbc().update("UPDATE care_access_grant SET status='REVOKED' WHERE patient_id=1 AND grantee_user_id=8"));assertThrows(IllegalStateException.class,()->preview(r.getId(),false));
    }
    @Test void returnTimeGateRejectsRevokedDoctorAssignmentRoleAndDisabledActor() {
        for(String sql:Arrays.asList("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=9","DELETE FROM sys_user_role WHERE user_id=9","UPDATE sys_user SET status=0 WHERE id=9")) {
            committed(()->{f.jdbc().update("UPDATE sys_user SET status=1 WHERE id=9");f.jdbc().update("UPDATE doctor_patient_assignment SET status='ACTIVE' WHERE doctor_user_id=9");f.jdbc().update("INSERT INTO sys_user_role(user_id,role_id) SELECT 9,id FROM sys_role WHERE role_code='doctor' AND NOT EXISTS(SELECT 1 FROM sys_user_role WHERE user_id=9)");});
            as(7,"GET","/medical-record/1/review-preview");MedicalRecord r=saved("raw");as(9,"GET","/medical-record/1/review-preview");atAttachmentRead=()->committed(()->f.jdbc().update(sql));assertThrows(IllegalStateException.class,()->preview(r.getId(),false));
        }
    }
    @Test void returnTimeGateRejectsOwnerWhoseLastEnabledRoleWasRemoved() {
        MedicalRecord r=saved("raw");atAttachmentRead=()->committed(()->f.jdbc().update("DELETE FROM sys_user_role WHERE user_id=7"));assertThrows(IllegalStateException.class,()->preview(r.getId(),false));
    }
    @Test void readOnlyMedicalGrantCanPreviewButCannotReviewAndCarePlanOnlyCannotPreview() {
        MedicalRecord r=saved("raw");f.jdbc().update("INSERT INTO care_access_grant(patient_id,grantee_user_id,grantee_role,access_level,visible_modules,granted_by) VALUES(1,8,'FAMILY','READ','MEDICAL',7)");as(8,"GET","/medical-record/1/review-preview");assertNotNull(preview(r.getId(),false));assertThrows(IllegalStateException.class,()->review(r.getId(),version(r.getId()),"write"));f.jdbc().update("UPDATE care_access_grant SET visible_modules='CARE_PLAN' WHERE grantee_user_id=8");assertThrows(IllegalStateException.class,()->preview(r.getId(),false));
    }
    @Test void competingCommittedEditFencesWaitingDoctorReviewAndItsAuditLog() throws Exception {
        MedicalRecord r=saved("raw");long seen=version(r.getId());java.util.concurrent.CountDownLatch edited=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),reviewing=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService threads=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> edit=threads.submit(()->{as(7,"PUT","/medical-record/update");org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));tx.execute(status->{service.updateRecordWithItems(patch(r,"competing edit"),Collections.emptyList());edited.countDown();try{assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException ex){throw new AssertionError(ex);}return null;});RequestContextHolder.resetRequestAttributes();});
            assertTrue(edited.await(10,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> review=threads.submit(()->{as(9,"POST","/doctor-workspace/reviews");Map<String,Object>b=new HashMap<>();b.put("sourceType","MEDICAL_RECORD");b.put("sourceId",r.getId());b.put("decision","APPROVED");b.put("expectedContentVersion",seen);reviewing.countDown();assertThrows(MedicalRecordReviewContract.Conflict.class,()->doctor.review(b));RequestContextHolder.resetRequestAttributes();});
            assertTrue(reviewing.await(10,java.util.concurrent.TimeUnit.SECONDS));release.countDown();edit.get(10,java.util.concurrent.TimeUnit.SECONDS);review.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("competing edit",service.getById(r.getId()).getRemark());assertNull(service.getById(r.getId()).getVerifiedBy());assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM doctor_review_log",Integer.class));
        } finally {release.countDown();threads.shutdownNow();}
    }
    @Test void missingAttachmentColumnRollsBackInsteadOfSavingMetadataOnly() {
        f.jdbc().execute("ALTER TABLE medical_record_attachment DROP COLUMN file_content");MedicalRecord r=record("raw");MedicalRecordAttachment a=attachment("SYNTHETIC-BYTES");r.setAttachments(Collections.singletonList(a));assertThrows(RuntimeException.class,()->service.saveRecordWithItems(r,Collections.emptyList()));assertEquals("SYNTHETIC-BYTES",a.getFileContent());assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM medical_record",Integer.class));
    }
    @Test void databaseInsertConstraintFailureRollsBackBodyAndNewItems() {
        f.jdbc().execute("ALTER TABLE medical_record_attachment ADD CONSTRAINT synthetic_failure CHECK(file_content <> 'FAIL')");MedicalRecord r=record("raw");r.setAttachments(Collections.singletonList(attachment("FAIL")));MedicalRecordItem item=new MedicalRecordItem();item.setItemName("Synthetic analyte");assertThrows(RuntimeException.class,()->service.saveRecordWithItems(r,Collections.singletonList(item)));assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM medical_record",Integer.class));assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM medical_record_item",Integer.class));
    }
    @Test void controllerContractsReturnConflictAndNeverSerializeAttachmentBytesOrPath() throws Exception {
        MedicalRecord r=record("Raw controller text");r.setAttachments(Collections.singletonList(attachment("SYNTHETIC-BYTES")));assertTrue(service.saveRecordWithItems(r,Collections.emptyList()));
        org.familyhealthcare.controller.MedicalRecordController controller=new org.familyhealthcare.controller.MedicalRecordController();org.springframework.test.util.ReflectionTestUtils.setField(controller,"medicalRecordService",service);
        org.familyhealthcare.controller.DoctorWorkspaceController dc=new org.familyhealthcare.controller.DoctorWorkspaceController();org.springframework.test.util.ReflectionTestUtils.setField(dc,"service",doctor);
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller,dc).setControllerAdvice(new org.familyhealthcare.controller.MedicalRecordExceptionAdvice(),new org.familyhealthcare.common.GlobalExceptionHandler()).build();
        String view=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/medical-record/"+r.getId()+"/review-preview").requestAttr("userId",7L)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.contentVersion").value(1)).andReturn().getResponse().getContentAsString();
        assertFalse(view.contains("fileContent"));assertFalse(view.contains("filePath"));assertFalse(view.contains("SYNTHETIC-BYTES"));
        assertTrue(service.updateRecordWithItems(patch(r,"changed"),Collections.emptyList()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/medical-record/"+r.getId()+"/review").requestAttr("userId",7L).contentType("application/json").content("{\"approved\":true,\"expectedContentVersion\":1,\"note\":\"stale\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(409));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/doctor-workspace/reviews").requestAttr("userId",9L).contentType("application/json").content("{\"sourceType\":\"MEDICAL_RECORD\",\"sourceId\":"+r.getId()+",\"decision\":\"APPROVED\",\"expectedContentVersion\":1}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/medical-record/"+r.getId()+"/review").requestAttr("userId",7L).contentType("application/json").content("{\"approved\":true}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400));
        assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM doctor_review_log",Integer.class));assertNull(service.getById(r.getId()).getVerifiedBy());
    }
    @Test void expectedVersionParserRejectsMissingFractionNegativeOverflowAndUnsafeInteger() {
        for(Object value:Arrays.asList(null,0,-1,1.5,"1e2","9007199254740992","9223372036854775808"," 1 ")){Map<String,Object>b=new HashMap<>();b.put("expectedContentVersion",value);assertThrows(IllegalArgumentException.class,()->MedicalRecordReviewContract.expectedVersion(b));}
        Map<String,Object>b=new HashMap<>();b.put("expectedContentVersion",7);assertEquals(7L,MedicalRecordReviewContract.expectedVersion(b));
    }
    @Test void importedCreateCannotAcceptForgedReviewStatusIdentityNoteOrVersion() {
        MedicalRecord r=record(" Original raw text ");r.setVerificationStatus("VERIFIED");r.setVerifiedBy(9L);r.setVerifiedAt(java.time.LocalDateTime.now());r.setVerificationNote("forged");r.setContentVersion(800L);assertTrue(service.saveRecordWithItems(r,Collections.emptyList()));MedicalRecord fresh=service.getById(r.getId());assertEquals("REVIEW_REQUIRED",fresh.getVerificationStatus());assertNull(fresh.getVerifiedBy());assertNull(fresh.getVerifiedAt());assertNull(fresh.getVerificationNote());assertEquals(1L,fresh.getContentVersion());assertEquals(" Original raw text ",fresh.getRemark());
    }
    @Test void contentUpdateCannotAcceptClientSuppliedOwnerVersionOrReviewMetadata() {
        MedicalRecord r=saved("raw");MedicalRecord p=patch(r,"edited");p.setUserId(8L);p.setContentVersion(700L);p.setVerifiedBy(11L);p.setVerificationNote("forged");assertTrue(service.updateRecordWithItems(p,Collections.emptyList()));MedicalRecord fresh=service.getById(r.getId());assertEquals(7L,fresh.getUserId());assertEquals(2L,fresh.getContentVersion());assertNull(fresh.getVerificationNote());assertNull(fresh.getVerifiedBy());
    }
    @Test void nullReviewNoteIsAnExplicitSqlNullAndManualEditsAlsoClearReview() {
        MedicalRecord r=saved(" Raw patient text ");assertTrue(review(r.getId(),1L,"prior"));assertTrue(review(r.getId(),1L,null));assertNull(f.jdbc().queryForObject("SELECT verification_note FROM medical_record WHERE id=?",String.class,r.getId()));MedicalRecord p=patch(r," Raw edited text ");p.setSourceType("MANUAL");assertTrue(service.updateRecordWithItems(p,Collections.emptyList()));MedicalRecord fresh=service.getById(r.getId());assertNull(fresh.getVerifiedBy());assertNull(fresh.getVerifiedAt());assertEquals("REVIEW_REQUIRED",fresh.getVerificationStatus());assertEquals(" Raw edited text ",fresh.getRemark());
    }
    @Test void contentUpdateExplicitlyClearsPersistedReviewIdentityAndTime() {
        MedicalRecord r=saved("Raw patient note\n 中文保留 ");f.jdbc().update("UPDATE medical_record SET verification_status='VERIFIED',verified_by=9,verified_at=CURRENT_TIMESTAMP WHERE id=?",r.getId());
        assertTrue(service.updateRecordWithItems(patch(r,"New raw note"),Collections.emptyList()));
        MedicalRecord fresh=service.getById(r.getId());assertNull(fresh.getVerifiedBy(),"ORM default must not leave obsolete review identity");assertNull(fresh.getVerifiedAt());assertEquals("REVIEW_REQUIRED",fresh.getVerificationStatus());
    }
    @Test void oversizedAttachmentInsertionRollsBackNewRecordAndPreservesSubmittedBytes() {
        f.jdbc().execute("ALTER TABLE medical_record_attachment MODIFY file_content VARCHAR(5)");MedicalRecord r=record("Unchanged text");MedicalRecordAttachment a=attachment("TOO-LONG-SYNTHETIC-BYTES");r.setAttachments(Collections.singletonList(a));
        assertThrows(RuntimeException.class,()->service.saveRecordWithItems(r,Collections.emptyList()));assertEquals("TOO-LONG-SYNTHETIC-BYTES",a.getFileContent());assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM medical_record",Integer.class));
    }
    @Test void failedAttachmentReplacementRestoresOldBodyItemsAndBytes() {
        MedicalRecord r=record("Original raw note");r.setAttachments(Collections.singletonList(attachment("OLD")));assertTrue(service.saveRecordWithItems(r,Collections.emptyList()));f.jdbc().execute("ALTER TABLE medical_record_attachment MODIFY file_content VARCHAR(5)");
        MedicalRecord change=patch(r,"Replacement body");MedicalRecordAttachment a=attachment("TOO-LONG-SYNTHETIC-BYTES");change.setAttachments(Collections.singletonList(a));
        assertThrows(RuntimeException.class,()->service.updateRecordWithItems(change,Collections.emptyList()));assertEquals("Original raw note",service.getById(r.getId()).getRemark());assertEquals("OLD",f.jdbc().queryForObject("SELECT file_content FROM medical_record_attachment WHERE record_id=?",String.class,r.getId()));assertEquals("TOO-LONG-SYNTHETIC-BYTES",a.getFileContent());
    }
    @Test void reviewNoteIsSeparateAndStaleReviewCannotChangeAnyBodyOrIdentity() {
        String raw=" Patient words \n患者原文 ";MedicalRecord r=saved(raw);MedicalRecord snapshot=preview(r.getId(),false);long seen=version(r.getId());
        assertTrue(review(r.getId(),seen," Review note "));assertEquals(raw,service.getById(r.getId()).getRemark());assertEquals(" Review note ",f.jdbc().queryForObject("SELECT verification_note FROM medical_record WHERE id=?",String.class,r.getId()));
        assertTrue(service.updateRecordWithItems(patch(r,"Changed clinical text"),Collections.emptyList()));assertEquals(seen+1,version(r.getId()));assertThrows(RuntimeException.class,()->review(r.getId(),seen,"stale"));assertEquals("Changed clinical text",service.getById(r.getId()).getRemark());assertNull(service.getById(r.getId()).getVerifiedBy());assertNull(f.jdbc().queryForObject("SELECT verification_note FROM medical_record WHERE id=?",String.class,r.getId()));assertEquals(r.getId(),snapshot.getId());
    }
    @Test void previewIncludesSourceAndItemsAndOnlyAttachmentMetadata() {
        MedicalRecord r=record(" Raw text ");r.setAttachments(Collections.singletonList(attachment("SYNTHETIC-SECRET-BYTES")));MedicalRecordItem item=new MedicalRecordItem();item.setItemName("Synthetic potassium");item.setResultValue("4.3");assertTrue(service.saveRecordWithItems(r,Collections.singletonList(item)));
        MedicalRecord view=preview(r.getId(),false);assertEquals(" Raw text ",view.getRemark());assertEquals(1,view.getItems().size());assertEquals(1,view.getAttachments().size());assertNull(view.getAttachments().get(0).getFileContent());assertEquals("synthetic.txt",view.getAttachments().get(0).getFileName());
    }
    @Test void doctorStaleReviewCannotCreateAuditLog() {
        MedicalRecord r=saved("original");long seen=versionIfAvailable(r.getId());assertTrue(service.updateRecordWithItems(patch(r,"changed"),Collections.emptyList()));as(9,"POST","/doctor-workspace/reviews");Map<String,Object> b=new LinkedHashMap<>();b.put("sourceType","MEDICAL_RECORD");b.put("sourceId",r.getId());b.put("decision","APPROVED");b.put("expectedContentVersion",seen);b.put("reviewNote","stale");assertThrows(RuntimeException.class,()->doctor.review(b));assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM doctor_review_log",Integer.class));assertEquals("changed",service.getById(r.getId()).getRemark());
    }
    long versionIfAvailable(long id){try{return version(id);}catch(org.springframework.dao.DataAccessException baseline){return 1L;}}
    @Test void missingContentVersionFailsClosedOnBothReviewEntrypoints() {
        MedicalRecord r=saved("raw");assertThrows(RuntimeException.class,()->invoke("reviewRecord",new Class<?>[]{Long.class,boolean.class,String.class,Long.class},r.getId(),true,"missing",null));as(9,"POST","/doctor-workspace/reviews");Map<String,Object> b=new HashMap<>();b.put("sourceType","MEDICAL_RECORD");b.put("sourceId",r.getId());b.put("decision","APPROVED");assertThrows(RuntimeException.class,()->doctor.review(b));assertEquals(0,f.jdbc().queryForObject("SELECT COUNT(*) FROM doctor_review_log",Integer.class));
    }
}
