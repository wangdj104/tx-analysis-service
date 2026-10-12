package org.familyhealthcare.service;

import org.familyhealthcare.entity.*;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Called by the guarded disposable MySQL gate, before its exact final restore snapshot. */
public final class MedicalRecordMysqlAssertions {
    private static final long OWNER=882001L,DOCTOR=882002L,PATIENT=882001L;
    private MedicalRecordMysqlAssertions() { }
    public static void verify(DataSource dataSource) throws Exception {
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).matches("care_plan_test_[a-z0-9_]+"),"Only the already guarded disposable database is allowed");
        assertTrue(NativeMysqlTestVersion.matches(NativeMysqlTestVersion.selected(),jdbc.queryForObject("SELECT VERSION()",String.class)),"Explicitly selected native MySQL version is required");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id IN (?,?)",Integer.class,OWNER,DOCTOR));
        jdbc.update("INSERT INTO sys_user(id,username,password,real_name) VALUES(?,?,'not-a-real-credential','Synthetic medical owner'),(?,?,'not-a-real-credential','Synthetic medical doctor')",OWNER,"medical-risk-synthetic-owner",DOCTOR,"medical-risk-synthetic-doctor");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code='patient'",OWNER);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code='doctor'",DOCTOR);
        jdbc.update("INSERT INTO patient(id,name,user_id) VALUES(?,'Synthetic medical integrity patient',?)",PATIENT,OWNER);
        jdbc.update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by,status) VALUES(?,?,?,'ACTIVE')",DOCTOR,PATIENT,OWNER);
        boolean constraint=false;
        RequestContextHolder.resetRequestAttributes();
        NativeAttachmentObserver observer=new NativeAttachmentObserver();
        try(GenericApplicationContext context=MedicalRecordPersistenceHarness.create(dataSource,observer)) {
            MedicalRecordService service=context.getBean(MedicalRecordService.class);DoctorWorkspaceService workspace=context.getBean(DoctorWorkspaceService.class);
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
            String raw=" Synthetic patient words \n患者原文，不改写 ";MedicalRecord record=newRecord(raw);record.setAttachments(Collections.singletonList(attachment("medical-risk-original.txt","OLD-BYTES")));
            MedicalRecordItem first=new MedicalRecordItem();first.setItemName("Synthetic analyte");first.setResultValue("4.3");
            assertTrue(CurrentUserUtil.runAsUser(OWNER,()->service.saveRecordWithItems(record,Collections.singletonList(first))));
            long id=record.getId();MedicalRecord preview=CurrentUserUtil.runAsUser(OWNER,()->service.getReviewPreview(id));assertEquals(1L,preview.getContentVersion());assertEquals(raw,preview.getRemark());assertNull(preview.getAttachments().get(0).getFileContent());
            assertTrue(CurrentUserUtil.runAsUser(OWNER,()->service.reviewRecord(id,true," Separate review ",1L)));
            assertEquals(raw,jdbc.queryForObject("SELECT remark FROM medical_record WHERE id=?",String.class,id));assertEquals(" Separate review ",jdbc.queryForObject("SELECT verification_note FROM medical_record WHERE id=?",String.class,id));
            assertTrue(CurrentUserUtil.runAsUser(OWNER,()->service.reviewRecord(id,true,null,1L)));assertNull(jdbc.queryForObject("SELECT verification_note FROM medical_record WHERE id=?",String.class,id));
            MedicalRecord edit=newRecord(" Edited raw text ");edit.setId(id);edit.setUserId(DOCTOR);edit.setContentVersion(999L);
            // getRecordWithDetails requires the same authenticated owner as the actual edit.
            edit.setAttachments(CurrentUserUtil.runAsUser(OWNER,()->service.getRecordWithDetails(id)).getAttachments());
            assertTrue(CurrentUserUtil.runAsUser(OWNER,()->service.updateRecordWithItems(edit,Collections.emptyList())));
            assertEquals(2L,jdbc.queryForObject("SELECT content_version FROM medical_record WHERE id=?",Long.class,id));assertEquals(OWNER,jdbc.queryForObject("SELECT user_id FROM medical_record WHERE id=?",Long.class,id));
            assertNull(jdbc.queryForObject("SELECT verified_by FROM medical_record WHERE id=?",Long.class,id));assertNull(jdbc.queryForObject("SELECT verified_at FROM medical_record WHERE id=?",java.sql.Timestamp.class,id));assertNull(jdbc.queryForObject("SELECT verification_note FROM medical_record WHERE id=?",String.class,id));
            Map<String,Object> stale=reviewBody(id,1L);assertThrows(MedicalRecordReviewContract.Conflict.class,()->CurrentUserUtil.runAsUser(DOCTOR,()->{workspace.review(stale);return null;}));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM doctor_review_log WHERE source_type='MEDICAL_RECORD' AND source_id=?",Integer.class,id));
            TransactionTemplate current=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));current.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            observer.action=()->current.execute(status->{jdbc.update("UPDATE medical_record SET remark='Native new snapshot body',content_version=content_version+1 WHERE id=?",id);return null;});
            MedicalRecord oldSnapshot=CurrentUserUtil.runAsUser(OWNER,()->service.getReviewPreview(id));assertEquals(" Edited raw text ",oldSnapshot.getRemark());assertEquals(2L,oldSnapshot.getContentVersion());assertEquals(3L,service.getById(id).getContentVersion());assertThrows(MedicalRecordReviewContract.Conflict.class,()->CurrentUserUtil.runAsUser(OWNER,()->service.reviewRecord(id,true,"stale preview",oldSnapshot.getContentVersion())));
            // Revocation commits after content load; the return-time gate must read current authority.
            observer.action=()->current.execute(status->{jdbc.update("UPDATE doctor_patient_assignment SET status='REVOKED' WHERE doctor_user_id=? AND patient_id=?",DOCTOR,PATIENT);return null;});
            assertThrows(IllegalStateException.class,()->CurrentUserUtil.runAsUser(DOCTOR,()->service.getReviewPreview(id)));jdbc.update("UPDATE doctor_patient_assignment SET status='ACTIVE' WHERE doctor_user_id=? AND patient_id=?",DOCTOR,PATIENT);
            verifyRace(context,service,workspace,jdbc,id);
            // A native database insertion error must unwind the complete proxied transaction.
            jdbc.execute("ALTER TABLE medical_record_attachment ADD CONSTRAINT ck_medical_risk_synthetic_length CHECK(file_name <> 'medical-risk-too-long.txt' OR CHAR_LENGTH(file_content)<=8)");constraint=true;
            MedicalRecord fresh=newRecord(" New rollback body ");MedicalRecordAttachment bad=attachment("medical-risk-too-long.txt","TOO-LONG-SYNTHETIC-BYTES");fresh.setAttachments(Collections.singletonList(bad));assertThrows(RuntimeException.class,()->CurrentUserUtil.runAsUser(OWNER,()->service.saveRecordWithItems(fresh,Collections.emptyList())));assertEquals("TOO-LONG-SYNTHETIC-BYTES",bad.getFileContent());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM medical_record WHERE id=?",Integer.class,fresh.getId()));
            MedicalRecord old=service.getById(id);long oldVersion=old.getContentVersion();String oldBytes=jdbc.queryForObject("SELECT file_content FROM medical_record_attachment WHERE record_id=?",String.class,id);MedicalRecord failed=newRecord(" Failed replacement body ");failed.setId(id);failed.setAttachments(Collections.singletonList(attachment("medical-risk-too-long.txt","TOO-LONG-SYNTHETIC-BYTES")));assertThrows(RuntimeException.class,()->CurrentUserUtil.runAsUser(OWNER,()->service.updateRecordWithItems(failed,Collections.emptyList())));assertEquals(old.getRemark(),service.getById(id).getRemark());assertEquals(oldVersion,service.getById(id).getContentVersion());assertEquals(oldBytes,jdbc.queryForObject("SELECT file_content FROM medical_record_attachment WHERE record_id=?",String.class,id));
        } finally {
            if(constraint) jdbc.execute("ALTER TABLE medical_record_attachment DROP CHECK ck_medical_risk_synthetic_length");
            jdbc.update("DELETE FROM doctor_review_log WHERE patient_id=?",PATIENT);jdbc.update("DELETE FROM medical_record WHERE patient_id=?",PATIENT);jdbc.update("DELETE FROM doctor_patient_assignment WHERE patient_id=?",PATIENT);jdbc.update("DELETE FROM patient WHERE id=?",PATIENT);jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (?,?)",OWNER,DOCTOR);jdbc.update("DELETE FROM sys_user WHERE id IN (?,?)",OWNER,DOCTOR);RequestContextHolder.resetRequestAttributes();
        }
    }
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}))
    static final class NativeAttachmentObserver implements org.apache.ibatis.plugin.Interceptor {
        Runnable action;
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation)throws Throwable {Object result=invocation.proceed();if(((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId().equals("org.familyhealthcare.mapper.MedicalRecordAttachmentMapper.selectList") && action!=null){Runnable run=action;action=null;run.run();}return result;}
        public Object plugin(Object target){return org.apache.ibatis.plugin.Plugin.wrap(target,this);}
        public void setProperties(Properties properties){ }
    }
    private static void verifyRace(GenericApplicationContext context,MedicalRecordService service,DoctorWorkspaceService workspace,JdbcTemplate jdbc,long id) throws Exception {
        long seen=service.getById(id).getContentVersion();CountDownLatch edited=new CountDownLatch(1),release=new CountDownLatch(1),reviewing=new CountDownLatch(1);ExecutorService threads=Executors.newFixedThreadPool(2);
        try {
            Future<?> edit=threads.submit(()->CurrentUserUtil.runAsUser(OWNER,()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{MedicalRecord change=newRecord(" Native competing edit ");change.setId(id);change.setAttachments(service.getRecordWithDetails(id).getAttachments());assertTrue(service.updateRecordWithItems(change,Collections.emptyList()));edited.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new AssertionError(ex);}return null;})));
            assertTrue(edited.await(15,TimeUnit.SECONDS));Future<?> review=threads.submit(()->CurrentUserUtil.runAsUser(DOCTOR,()->{reviewing.countDown();assertThrows(MedicalRecordReviewContract.Conflict.class,()->workspace.review(reviewBody(id,seen)));return null;}));assertTrue(reviewing.await(15,TimeUnit.SECONDS));release.countDown();edit.get(15,TimeUnit.SECONDS);review.get(15,TimeUnit.SECONDS);
            assertEquals(seen+1,service.getById(id).getContentVersion());assertEquals(" Native competing edit ",service.getById(id).getRemark());assertNull(service.getById(id).getVerifiedBy());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM doctor_review_log WHERE source_type='MEDICAL_RECORD' AND source_id=?",Integer.class,id));
        } finally {release.countDown();threads.shutdownNow();}
    }
    private static MedicalRecord newRecord(String raw){MedicalRecord r=new MedicalRecord();r.setPatientId(PATIENT);r.setRecordType("BLOOD");r.setSourceType("OCR");r.setRemark(raw);return r;}
    private static MedicalRecordAttachment attachment(String name,String bytes){MedicalRecordAttachment a=new MedicalRecordAttachment();a.setFileName(name);a.setFileContent(bytes);return a;}
    private static Map<String,Object> reviewBody(long id,long version){Map<String,Object>b=new HashMap<>();b.put("sourceType","MEDICAL_RECORD");b.put("sourceId",id);b.put("decision","APPROVED");b.put("reviewNote","stale synthetic review");b.put("expectedContentVersion",version);return b;}
}
