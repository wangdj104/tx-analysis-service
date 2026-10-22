package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionAuthorization;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionRepository;
import org.familyhealthcare.service.medicalrecord.MedicalRecordCorrectionSchema;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.integrity;

/** Internal completed-receipt recovery only. Empty does not admit a NEW command. */
@Service
public class MedicalReviewReceiptAdmission {
    private final DataSource dataSource;
    private final MedicalReviewSchema schema;
    private final MedicalRecordCorrectionSchema correctionSchema;
    private final MedicalRecordCorrectionRepository corrections;
    private final MedicalRecordCorrectionAuthorization authorization;
    private final boolean correctionEnabled;
    private final MedicalReviewReceiptRepository receipts=new MedicalReviewReceiptRepository();
    private final MedicalReviewSourceRepository sources;

    public MedicalReviewReceiptAdmission(JdbcTemplate jdbc,MedicalReviewSchema schema,MedicalRecordCorrectionSchema correctionSchema,
                                         MedicalRecordCorrectionRepository corrections,MedicalRecordCorrectionAuthorization authorization,
                                         @Value("${medical-record-correction.enabled:false}") boolean correctionEnabled) {
        if (jdbc==null || jdbc.getDataSource()==null || schema==null || correctionSchema==null || corrections==null || authorization==null)
            throw MedicalReviewSchema.notReady();
        this.dataSource=jdbc.getDataSource();this.schema=schema;this.correctionSchema=correctionSchema;
        this.corrections=corrections;this.authorization=authorization;this.correctionEnabled=correctionEnabled;
        this.sources=new MedicalReviewSourceRepository(jdbc,schema);
    }
    /** No-query graph assertion: another datasource must fail before acquisition. */
    void requireDataSource(DataSource expected) {
        if (expected==null || dataSource!=expected) throw MedicalReviewSchema.notReady();
    }
    @Transactional(propagation=Propagation.MANDATORY,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Optional<MedicalReviewReceipt> recoverCompleted(Command command) {
        if (command==null) throw new WorkflowException(400,"MEDICAL_REVIEW_INVALID_REQUEST","The medical review request is invalid.");
        final long actor=command.getActorId();requireActor(actor);
        try (BoundConnection bound=new BoundConnection()) {
            requireRepository();
            MedicalRecord source=authorization.lockExistingForRead(command.getRecordId(),actor,dataSource);
            bound.verify();
            MedicalRecordCorrectionSchema.Status parent9=correctionSchema.inspectStorage(bound.connection);
            if (parent9==MedicalRecordCorrectionSchema.Status.INCOMPATIBLE
                    || (parent9==MedicalRecordCorrectionSchema.Status.ABSENT && correctionEnabled)) throw MedicalRecordCorrectionSchema.notReady();
            boolean parent9Ready=parent9==MedicalRecordCorrectionSchema.Status.READY;
            if (schema.inspectStorage(bound.connection)!=MedicalReviewSchema.Status.READY) throw MedicalReviewSchema.notReady();
            receipts.lockCurrentKeys(bound.connection,source.getId(),parent9Ready);
            read(bound,source,actor);
            Optional<MedicalReviewReceipt> found;
            try { found=receipts.findCompleted(bound.connection,command); }
            catch (WorkflowException invalidStoredReceipt) { read(bound,source,actor);throw invalidStoredReceipt; }
            read(bound,source,actor);
            if (!found.isPresent()) { read(bound,source,actor);return Optional.empty(); }
            MedicalReviewReceipt receipt=found.get();receipt.requireCommand(command);
            Lifecycle lifecycle=current(bound,source,actor,parent9Ready,receipt);
            receipts.requireEventMatch(bound.connection,command,receipt);
            read(bound,source,actor);
            // Register outside any closure over the full Command (which contains private text).
            guardReceipt(bound,source,actor,parent9Ready,lifecycle,receipt);
            return Optional.of(receipt);
        } catch (SQLException | DataAccessException failure) { throw MedicalReviewSchema.notReady(); }
    }
    private Lifecycle current(BoundConnection bound,MedicalRecord source,long actor,boolean parent9Ready,MedicalReviewReceipt receipt) throws SQLException {
        if (parent9Ready) {
            corrections.requireStable(source,corrections.state(source.getId(),true));
            read(bound,source,actor);
        }
        Lifecycle lifecycle=sources.readLockedLifecycle(source);
        read(bound,source,actor);
        receipts.requireCurrentChannel(bound.connection,source,lifecycle,receipt);
        return lifecycle;
    }
    private void guardReceipt(BoundConnection bound,MedicalRecord source,long actor,boolean parent9Ready,Lifecycle lifecycle,MedicalReviewReceipt receipt) {
        final long record=source.getId();final Long patient=source.getPatientId(),owner=source.getUserId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                try {
                    bound.verify();requireActor(actor);requireRepository();
                    // These rows are already owned. Never acquire a new authority lock set
                    // from a binding that changed inside the outer transaction.
                    MedicalRecord currentSource=corrections.binding(record,false);
                    if (currentSource==null) throw MedicalRecordCorrectionAuthorization.denied();
                    if (MedicalReviewContentFingerprint.id(currentSource.getId())!=record) throw integrity();
                    MedicalReviewContentFingerprint.id(currentSource.getContentVersion());
                    MedicalReviewContentFingerprint.nullableId(currentSource.getPatientId());
                    MedicalReviewContentFingerprint.nullableId(currentSource.getUserId());
                    if (!Objects.equals(patient,currentSource.getPatientId()) || !Objects.equals(owner,currentSource.getUserId()))
                        throw MedicalRecordCorrectionAuthorization.denied();
                    read(bound,currentSource,actor);
                    Lifecycle actual=current(bound,currentSource,actor,parent9Ready,receipt);
                    if (!Objects.equals(lifecycle.getCurrentGeneration(),actual.getCurrentGeneration())
                            || lifecycle.getLatestDeletionEventId()!=actual.getLatestDeletionEventId()) throw integrity();
                    receipts.requireUnchangedCompleted(bound.connection,receipt);
                    read(bound,currentSource,actor);
                } catch (SQLException | DataAccessException failure) { throw MedicalReviewSchema.notReady(); }
            }
        });
    }
    private void read(BoundConnection bound,MedicalRecord source,long actor) throws SQLException {
        bound.verify();requireActor(actor);authorization.requireLockedRead(source,actor,dataSource);bound.verify();requireActor(actor);
    }
    private void requireRepository() {
        try { corrections.requireDataSource(dataSource); }
        catch (IllegalStateException failure) { throw MedicalReviewSchema.notReady(); }
    }
    private static void requireActor(long actor) {
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw MedicalRecordCorrectionAuthorization.denied();
    }
    private final class BoundConnection implements AutoCloseable {
        final ConnectionHolder holder;final Connection connection;private final SimpleConnectionHandle handle;private Connection borrowed;
        BoundConnection() throws SQLException {
            mode();Object resource=TransactionSynchronizationManager.getResource(dataSource);
            // A generic holder may lazily acquire via an arbitrary ConnectionHandle.
            // Support the eager standard form used by DataSourceTransactionManager only.
            if (resource==null || resource.getClass()!=ConnectionHolder.class) throw MedicalReviewSchema.notReady();
            holder=(ConnectionHolder)resource;
            if (!holder.isSynchronizedWithTransaction() || holder.getConnectionHandle()==null
                    || holder.getConnectionHandle().getClass()!=SimpleConnectionHandle.class) throw MedicalReviewSchema.notReady();
            handle=(SimpleConnectionHandle)holder.getConnectionHandle();
            connection=handle.getConnection();
            verify();
            Connection acquired=null;
            try {
                acquired=DataSourceUtils.getConnection(dataSource);
                if (acquired!=connection || !DataSourceUtils.isConnectionTransactional(acquired,dataSource)) throw MedicalReviewSchema.notReady();
                verify();borrowed=acquired;
            } finally { if (borrowed==null && acquired!=null) DataSourceUtils.releaseConnection(acquired,dataSource); }
        }
        void verify() throws SQLException {
            mode();
            if (TransactionSynchronizationManager.getResource(dataSource)!=holder || !holder.isSynchronizedWithTransaction()) throw MedicalReviewSchema.notReady();
            if (holder.getConnectionHandle()!=handle || handle.getConnection()!=connection) throw MedicalReviewSchema.notReady();
            try { if (holder.getConnection()!=connection) throw MedicalReviewSchema.notReady(); }
            catch (IllegalStateException | IllegalArgumentException failure) { throw MedicalReviewSchema.notReady(); }
            if (connection==null || connection.getAutoCommit() || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
                throw MedicalReviewSchema.notReady();
        }
        @Override public void close() { if (borrowed!=null) { DataSourceUtils.releaseConnection(borrowed,dataSource);borrowed=null; } }
    }
    private static void mode() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw MedicalReviewSchema.notReady();
    }
}
