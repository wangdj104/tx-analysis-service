package org.familyhealthcare.service.careplan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.familyhealthcare.service.careplan.CareExecutionReportException.Code;

/** Fresh, narrow report authorization. No clinical body or full Patient entity is loaded here. */
@Service
public class CareExecutionReportAccess {
    private final JdbcTemplate jdbc;
    private final CarePlanAuthorizationService auth;
    private final TransactionTemplate freshRead;

    public CareExecutionReportAccess(JdbcTemplate jdbc, CarePlanAuthorizationService auth,
                                     PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.auth = Objects.requireNonNull(auth);
        this.freshRead = new TransactionTemplate(Objects.requireNonNull(transactions));
        freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        freshRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        freshRead.setReadOnly(true);
    }

    public Access inspect(long actorId, CareExecutionReportContracts.Request request) {
        Objects.requireNonNull(request);
        return freshRead.execute(status -> {
            requireFreshTransaction();
            requireScope(actorId, request);
            return new Access(questionsAllowed(actorId, request.getPatientId()));
        });
    }

    public void recheck(long actorId, CareExecutionReportContracts.Request request, Manifest manifest) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(manifest);
        freshRead.execute(status -> {
            requireFreshTransaction();
            // Base loss takes precedence over loss of an optional section or reference.
            requireScope(actorId, request);
            if (manifest.isQuestionsIncluded() && !questionsAllowed(actorId, request.getPatientId())) {
                throw new CareExecutionReportException(Code.REPORT_ACCESS_CHANGED);
            }
            for (EvidenceKey key : manifest.getReadableEvidence()) {
                if (!auth.canReadEvidence(actorId, request.getPatientId(), key.getSourceType(), key.getSourceId())) {
                    throw new CareExecutionReportException(Code.REPORT_ACCESS_CHANGED);
                }
            }
            return null;
        });
    }

    private void requireScope(long actorId, CareExecutionReportContracts.Request request) {
        try {
            auth.requireRead(actorId, request.getPatientId());
        } catch (CarePlanException error) {
            // Reporting has a 404 feature-disabled contract; leave the existing collaboration API unchanged.
            if ("FEATURE_DISABLED".equals(error.getErrorCode())) throw new CareExecutionReportException(Code.FEATURE_DISABLED);
            if ("ACCESS_DENIED".equals(error.getErrorCode())) throw CareExecutionReportException.denied();
            throw error;
        }
        if (request.getPlanId() != null && jdbc.queryForList(
                "SELECT p.id FROM doctor_care_plan p JOIN care_plan_revision r ON r.id=p.current_revision_id AND r.plan_id=p.id " +
                "WHERE p.id=? AND p.patient_id=? AND p.workflow_version=1 " +
                "AND p.lifecycle IN ('ACTIVE','COMPLETED','CANCELLED') AND r.status='PUBLISHED'",
                Long.class, request.getPlanId(), request.getPatientId()).isEmpty()) {
            throw CareExecutionReportException.denied();
        }
    }

    /** Permission only; the projector separately omits questions for a single plan or either CSV. */
    private boolean questionsAllowed(long actorId, long patientId) {
        if (count("SELECT COUNT(*) FROM patient WHERE id=? AND user_id=?", patientId, actorId) > 0) return true;
        try {
            auth.requireClinical(actorId, patientId);
            return true;
        } catch (CarePlanException error) {
            if (error.getStatus() != 403) throw error;
        }
        // Nursing provenance must never become a legacy full-record/family shortcut.
        // Ownership and a genuinely current assigned doctor were checked before this stricter gate.
        if (isNurseIdentity(actorId, patientId) || count(
                "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id " +
                "WHERE ur.user_id=? AND r.role_code='family' AND r.status=1 AND COALESCE(r.deleted,0)=0", actorId) == 0) {
            return false;
        }
        // Match DataScopeHelper's full-record READ rule, not a single module or the HTTP role cache.
        // Every explicit grant (including revoked/expired/narrow grants) displaces legacy membership;
        // any currently valid full-record grant can then supply authority independently of other grants.
        List<Boolean> grants = jdbc.query(
                "SELECT status,visible_modules,(expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) AS unexpired " +
                "FROM care_access_grant WHERE grantee_user_id=? AND patient_id=?",
                (rs, row) -> "ACTIVE".equals(rs.getString("status")) && rs.getBoolean("unexpired")
                        && fullRecord(rs.getString("visible_modules")), actorId, patientId);
        if (!grants.isEmpty()) return grants.contains(Boolean.TRUE);
        List<Boolean> members = jdbc.query("SELECT visible_modules FROM care_member WHERE user_id=? AND patient_id=?",
                (rs, row) -> fullRecord(rs.getString("visible_modules")), actorId, patientId);
        return members.contains(Boolean.TRUE);
    }

    private boolean isNurseIdentity(long actorId, long patientId) {
        return count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id " +
                "WHERE ur.user_id=? AND r.role_code='nurse'", actorId) > 0
                || count("SELECT COUNT(*) FROM care_access_grant WHERE grantee_user_id=? AND patient_id=? AND grantee_role='NURSE'", actorId, patientId) > 0
                || count("SELECT COUNT(*) FROM care_nurse_assignment WHERE nurse_user_id=? AND patient_id=?", actorId, patientId) > 0;
    }

    private static boolean fullRecord(String modules) { return modules == null || modules.trim().isEmpty(); }
    private int count(String sql, Object... arguments) { return jdbc.queryForObject(sql, Integer.class, arguments); }

    /** Fail closed if an incorrectly wired transaction manager did not isolate this datasource. */
    private void requireFreshTransaction() {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource());
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !DataSourceUtils.isConnectionTransactional(connection, dataSource)
                    || connection.getAutoCommit()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("Report authorization requires an effective read-only READ_COMMITTED transaction.");
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot verify report authorization transaction isolation.", error);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    public static final class Access {
        private final boolean questionsAllowed;
        public Access(boolean questionsAllowed) { this.questionsAllowed = questionsAllowed; }
        public boolean isQuestionsAllowed() { return questionsAllowed; }
    }

    /** What was actually included, even when the included question section contained zero rows. */
    public static final class Manifest {
        private final boolean questionsIncluded;
        private final Set<EvidenceKey> readableEvidence;
        public Manifest(boolean questionsIncluded, Set<EvidenceKey> readableEvidence) {
            this.questionsIncluded = questionsIncluded;
            LinkedHashSet<EvidenceKey> copy = new LinkedHashSet<>(Objects.requireNonNull(readableEvidence));
            for (EvidenceKey key : copy) Objects.requireNonNull(key);
            this.readableEvidence = Collections.unmodifiableSet(copy);
        }
        public boolean isQuestionsIncluded() { return questionsIncluded; }
        public Set<EvidenceKey> getReadableEvidence() { return readableEvidence; }
    }

    public static final class EvidenceKey {
        private final String sourceType;
        private final long sourceId;
        public EvidenceKey(String sourceType, long sourceId) {
            this.sourceType = Objects.requireNonNull(sourceType);
            this.sourceId = sourceId;
        }
        public String getSourceType() { return sourceType; }
        public long getSourceId() { return sourceId; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof EvidenceKey)) return false;
            EvidenceKey key = (EvidenceKey) other;
            return sourceId == key.sourceId && sourceType.equals(key.sourceType);
        }
        @Override public int hashCode() { return Objects.hash(sourceType, sourceId); }
    }
}
