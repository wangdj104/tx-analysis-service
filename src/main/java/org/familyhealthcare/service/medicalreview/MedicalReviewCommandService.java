package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionRepository;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.sql.DataSource;
import java.util.Map;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;

/** Separate proxy-owned write-mode transactions; completed recovery never admits a new command. */
@Service
@ConditionalOnProperty(name="medical-review.api-enabled", havingValue="true", matchIfMissing=false)
public class MedicalReviewCommandService {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final DataSourceTransactionManager transactions;
    private final MedicalRecordCorrectionRepository corrections;
    private final MedicalRecordReviewAccess access;
    private final MedicalReviewReceiptAdmission admission;
    private final MedicalReviewCommandKernel kernel;

    public MedicalReviewCommandService(JdbcTemplate jdbc, DataSourceTransactionManager transactions,
            MedicalReviewSchema schema, MedicalRecordCorrectionSchema correctionSchema,
            MedicalRecordCorrectionRepository corrections, MedicalRecordReviewAccess access,
            MedicalReviewReceiptAdmission admission,
            @Value("${medical-review.enabled:false}") boolean enabled,
            @Value("${medical-record-correction.enabled:false}") boolean correctionEnabled) {
        if (jdbc==null || jdbc.getDataSource()==null || transactions==null || corrections==null || access==null || admission==null)
            throw MedicalReviewApiContracts.unavailable();
        this.jdbc=jdbc; this.dataSource=jdbc.getDataSource(); this.transactions=transactions;
        this.corrections=corrections; this.access=access; this.admission=admission;
        graph();
        kernel=new MedicalReviewCommandKernel(jdbc,schema,correctionSchema,corrections,access,admission,enabled,correctionEnabled);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED, readOnly=false, rollbackFor=Exception.class)
    public Map<String,Object> execute(long recordId, Channel channel, byte[] body) {
        long actor=actor();
        Command command=command(body,recordId,actor,channel);
        graph();
        return MedicalReviewApiContracts.commandResult(kernel.execute(command));
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED, readOnly=false, rollbackFor=Exception.class)
    public Map<String,Object> reconcile(long recordId, Channel channel, byte[] body) {
        long actor=actor();
        Command command=command(body,recordId,actor,channel);
        graph();
        return MedicalReviewApiContracts.reconciliationResult(admission.recoverCompleted(command).orElse(null));
    }

    private static long actor() {
        Long actor=CurrentUserUtil.getCurrentUserId();
        if (actor==null || actor<=0 || actor>MAX_ID) throw MedicalReviewApiContracts.error(401,"AUTHENTICATION_REQUIRED");
        return actor;
    }

    private void graph() {
        if (jdbc.getDataSource()!=dataSource || transactions.getDataSource()!=dataSource
                || !AopUtils.isAopProxy(admission) || AopUtils.getTargetClass(admission)!=MedicalReviewReceiptAdmission.class
                || !AopUtils.isAopProxy(access) || AopUtils.getTargetClass(access)!=MedicalRecordReviewAccess.class)
            throw MedicalReviewApiContracts.unavailable();
        admission.requireDataSource(dataSource);
        try { corrections.requireDataSource(dataSource); access.requireDataSource(dataSource); }
        catch (IllegalStateException failure) { throw MedicalReviewApiContracts.unavailable(); }
    }
}
