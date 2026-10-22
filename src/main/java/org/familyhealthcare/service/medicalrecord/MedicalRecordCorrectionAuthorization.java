package org.familyhealthcare.service.medicalrecord;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.MedicalRecordReviewAccess;
import org.familyhealthcare.service.workflow.HouseholdAuthorityLocks;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.stereotype.Component;
import java.util.*;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.familyhealthcare.service.medicalreview.MedicalReviewSchema;
import org.familyhealthcare.service.medicalreview.MedicalReviewContracts;

/** 发现绑定不授予权限；等待后的实际绑定和当前数据库权限才是入场依据。 */
@Component
public class MedicalRecordCorrectionAuthorization {
    private final MedicalRecordCorrectionRepository repository;
    private final HouseholdAuthorityLocks locks;
    private final MedicalRecordReviewAccess access;
    public MedicalRecordCorrectionAuthorization(MedicalRecordCorrectionRepository repository, HouseholdAuthorityLocks locks, MedicalRecordReviewAccess access) {
        this.repository = repository; this.locks = locks; this.access = access;
    }
    public long actor() {
        Long actor = CurrentUserUtil.getCurrentUserId();
        if (actor == null) throw denied();
        return MedicalRecordCorrectionContracts.id(actor);
    }
    public MedicalRecord lock(long id, long actor, boolean workspace) {
        MedicalRecord value = lockExisting(id, actor, workspace);
        if (value == null) throw denied();
        return value;
    }
    public MedicalRecord lockExisting(long id, long actor, boolean workspace) {
        return lockExisting(id, actor, workspace, true, null);
    }
    private MedicalRecord lockExisting(long id, long actor, boolean workspace, boolean write, DataSource expected) {
        MedicalRecord discovered = repository.binding(id, false);
        if (discovered == null) return null;
        if (!write) storedBinding(discovered, id);
        List<Long> subjects = new ArrayList<>(); subjects.add(actor);
        if (discovered.getUserId() != null) subjects.add(discovered.getUserId());
        if (discovered.getPatientId() == null) locks.lockSubjectsWithoutPatient(subjects);
        else locks.lockPatientAndSubjects(discovered.getPatientId(), subjects);
        if (write) requireLocked(discovered, actor, workspace);
        else requireLockedRead(discovered, actor, expected);
        MedicalRecord locked = repository.binding(id, true);
        if (!write && locked != null) storedBinding(locked, id);
        sameBinding(discovered, locked);
        if (write) requireLocked(locked, actor, workspace);
        else requireLockedRead(locked, actor, expected);
        return locked;
    }
    public void requireLocked(MedicalRecord source, long actor, boolean workspace) {
        try { access.requireLockedCurrent(source, actor, true, workspace); }
        catch (IllegalStateException failure) { throw denied(); }
    }
    /** Current READ only, on the caller's already-bound mutation-mode connection. */
    public MedicalRecord lockExistingForRead(long id, long actor, DataSource expectedDataSource) {
        MedicalReviewContracts.id(id); MedicalReviewContracts.id(actor);
        requireActor(actor);
        try (ReadConnection guard = readConnection(expectedDataSource)) {
            MedicalRecord source = lockExisting(id, actor, false, false, expectedDataSource);
            if (source == null) throw denied();
            guard.verify(); requireActor(actor);
            return source;
        } catch (SQLException | DataAccessException failure) { throw MedicalReviewSchema.notReady(); }
    }
    /** Does not create a transaction or accept a caller-controlled authority downgrade. */
    public void requireLockedRead(MedicalRecord source, long actor, DataSource expectedDataSource) {
        if (source == null) throw new WorkflowException(400, "MEDICAL_REVIEW_INVALID_REQUEST", "The medical review request is invalid.");
        MedicalReviewContracts.id(source.getId()); MedicalReviewContracts.id(actor);
        requireActor(actor);
        try (ReadConnection guard = readConnection(expectedDataSource)) {
            storedBinding(source, source.getId());
            try { access.requireLockedCurrent(source, actor, false, false); }
            catch (IllegalStateException failure) { throw denied(); }
            guard.verify(); requireActor(actor);
        } catch (SQLException | DataAccessException failure) { throw MedicalReviewSchema.notReady(); }
    }
    private ReadConnection readConnection(DataSource expected) throws SQLException {
        // Keep explicit wiring/guard failures outside the access-denied catch above.
        try {
            repository.requireDataSource(expected); locks.requireDataSource(expected); access.requireDataSource(expected);
        } catch (IllegalStateException failure) { throw MedicalReviewSchema.notReady(); }
        return new ReadConnection(expected);
    }
    private static void requireActor(long actor) {
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw denied();
    }
    private static void storedBinding(MedicalRecord source, long requestedId) {
        try {
            MedicalReviewContracts.id(source.getId()); MedicalReviewContracts.id(source.getContentVersion());
            if (source.getPatientId() != null) MedicalReviewContracts.id(source.getPatientId());
            if (source.getUserId() != null) MedicalReviewContracts.id(source.getUserId());
            if (!Long.valueOf(requestedId).equals(source.getId())) throw integrity();
        } catch (WorkflowException failure) { throw integrity(); }
    }
    private static WorkflowException integrity() {
        return new WorkflowException(409, "MEDICAL_REVIEW_INTEGRITY", "The medical review lifecycle is inconsistent. Reload and request a review.");
    }
    private static final class ReadConnection implements AutoCloseable {
        private final DataSource dataSource;
        private final ConnectionHolder holder;
        private final SimpleConnectionHandle handle;
        private final Connection connection;
        private Connection borrowed;
        ReadConnection(DataSource dataSource) throws SQLException {
            this.dataSource = dataSource;
            mode();
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            // Generic holder getters may acquire lazily. The standard eager form
            // holds a real connection before this READ boundary begins.
            if (resource == null || resource.getClass() != ConnectionHolder.class) throw MedicalReviewSchema.notReady();
            holder = (ConnectionHolder) resource;
            if (!holder.isSynchronizedWithTransaction() || holder.getConnectionHandle() == null
                    || holder.getConnectionHandle().getClass() != SimpleConnectionHandle.class) throw MedicalReviewSchema.notReady();
            handle = (SimpleConnectionHandle) holder.getConnectionHandle();
            connection = handle.getConnection();
            verify(); // No DataSourceUtils acquisition before the actual bound-connection proof.
            Connection acquired = null;
            try {
                acquired = DataSourceUtils.getConnection(dataSource);
                if (acquired != connection || !DataSourceUtils.isConnectionTransactional(acquired,dataSource)) throw MedicalReviewSchema.notReady();
                verify(); borrowed = acquired;
            } finally { if (borrowed == null && acquired != null) DataSourceUtils.releaseConnection(acquired,dataSource); }
        }
        void verify() throws SQLException {
            mode();
            if (TransactionSynchronizationManager.getResource(dataSource) != holder || !holder.isSynchronizedWithTransaction()) throw MedicalReviewSchema.notReady();
            if (holder.getConnectionHandle() != handle || handle.getConnection() != connection) throw MedicalReviewSchema.notReady();
            try { if (holder.getConnection() != connection) throw MedicalReviewSchema.notReady(); }
            catch (IllegalStateException | IllegalArgumentException failure) { throw MedicalReviewSchema.notReady(); }
            if (connection == null || connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
                throw MedicalReviewSchema.notReady();
        }
        private static void mode() {
            if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw MedicalReviewSchema.notReady();
        }
        @Override public void close() { if (borrowed != null) DataSourceUtils.releaseConnection(borrowed,dataSource); }
    }
    /** 通过另一个 Spring bean 的 REQUIRES_NEW 代理核当前授权，不能拿旧内容快照当权限。 */
    public void requireFresh(MedicalRecord source, long actor, boolean write) {
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw denied();
        try { access.requireCurrent(source, write, false); }
        catch (IllegalStateException failure) { throw denied(); }
        if (!Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw denied();
    }
    public boolean canWrite(MedicalRecord source, long actor) {
        try { requireFresh(source, actor, true); return true; }
        catch (WorkflowException failure) { if (failure.getStatus() == 403) return false; throw failure; }
    }
    public static void sameBinding(MedicalRecord expected, MedicalRecord actual) {
        if (actual == null || !Objects.equals(expected.getId(), actual.getId())
                || !Objects.equals(expected.getPatientId(), actual.getPatientId()) || !Objects.equals(expected.getUserId(), actual.getUserId())) throw denied();
    }
    public static WorkflowException denied() { return new WorkflowException(403, "MEDICAL_RECORD_ACCESS_DENIED", "Access to this medical record is not available."); }
}
