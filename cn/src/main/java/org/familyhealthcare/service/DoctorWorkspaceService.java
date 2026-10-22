package org.familyhealthcare.service;

import org.familyhealthcare.util.CurrentUserUtil;
import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.familyhealthcare.service.careplan.CarePlanQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.familyhealthcare.dto.MedicalRecordReviewSummary;
import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection;
import org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State;
import org.familyhealthcare.service.medicalreview.MedicalReviewQualificationReader;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedCaseInsensitiveMap;
import java.sql.Connection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DoctorWorkspaceService {
    @Autowired private org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection medicalValidity;
    @Autowired private org.familyhealthcare.service.workflow.HouseholdAuthorityLocks householdAuthorityLocks;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceTransactionManager medicalTransactions;
    @Autowired private MedicalRecordReviewAccess medicalAccess;
    @Autowired private MedicalReviewQualificationReader medicalQualification;
    @Autowired private MedicalRecordService medicalRecordService;
    @Autowired(required=false) private CarePlanProperties carePlanProperties;
    @Autowired(required=false) private CarePlanQueryService carePlanQuery;

    public Map<String, Object> summary() {
        requireDoctor();
        long actor = currentUserId();
        requireMedicalActor(actor);requireMedicalUnowned();
        String scope = CurrentUserUtil.isAdmin() ? "" : " AND a.doctor_user_id=" + actor;
        MedicalQueue medical = null;boolean released = false;
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> patientRows = patients();
            medical = prepareMedicalQueue("REVIEW_REQUIRED", scope, actor);
            List<Map<String, Object>> reviewRows = assembleReviews(medical, "REVIEW_REQUIRED", scope);
            long critical = patientRows.stream().filter(row -> number(row.get("criticalAlerts")) > 0).count();
            result.put("assignedPatients", patientRows.size());
            result.put("pendingReviews", reviewRows.size());
            result.put("criticalPatients", critical);
            result.put("activePlans", countScoped("doctor_care_plan", "status='ACTIVE'"));
            if (collaborationEnabled()) result.putAll(carePlanQuery.clinicalCounts(currentUserId()));
            result.put("patients", patientRows);
            result.put("reviewQueue", reviewRows.size() > 6 ? reviewRows.subList(0, 6) : reviewRows);
            requireMedicalRelease(medical, actor);
            released = true;return result;
        } finally {
            if (!released) {clearMedicalQueue(medical);result.clear();}
        }
    }

    public List<Map<String, Object>> patients() {
        requireDoctor();
        String scope = CurrentUserUtil.isAdmin() ? "" : " AND a.doctor_user_id=" + currentUserId();
        String sql = "SELECT p.id,p.name,p.gender,p.birth_date AS birthDate,p.phone,a.care_team_role AS careTeamRole," +
                "(SELECT COUNT(*) FROM alert_record ar WHERE (ar.alert_type IS NULL OR ar.alert_type<>'MEASUREMENT_FOLLOW_UP') AND ar.patient_id=p.id AND ar.status IN ('PENDING','CONFIRMED') AND ar.alert_level='CRITICAL') AS criticalAlerts," +
                "(SELECT MAX(CONCAT(b.record_date,' ',COALESCE(b.record_time,''))) FROM bp_self_monitor_record b WHERE b.patient_id=p.id) AS lastVitalAt " +
                "FROM patient p JOIN doctor_patient_assignment a ON a.patient_id=p.id AND a.status='ACTIVE' " +
                "WHERE p.deleted=0 AND p.status=1" + scope + " ORDER BY criticalAlerts DESC,p.name";
        return jdbc.queryForList(sql);
    }

    public List<Map<String, Object>> reviews(String status) {
        requireDoctor();
        long actor = currentUserId();
        requireMedicalActor(actor);requireMedicalUnowned();
        String wanted = status == null || status.trim().isEmpty() ? "REVIEW_REQUIRED" : status.trim().toUpperCase();
        String scope = CurrentUserUtil.isAdmin() ? "" : " AND a.doctor_user_id=" + actor;
        MedicalQueue medical = null;boolean released = false;
        try {
            medical = prepareMedicalQueue(wanted, scope, actor);
            List<Map<String, Object>> rows = assembleReviews(medical, wanted, scope);
            requireMedicalRelease(medical, actor);
            released = true;return rows;
        } finally {
            if (!released) clearMedicalQueue(medical);
        }
    }

    private List<Map<String, Object>> assembleReviews(MedicalQueue medical, String wanted, String scope) {
        List<Map<String, Object>> rows = new ArrayList<>(medical.rows);
        rows.addAll(jdbc.queryForList("SELECT 'AI_ANALYSIS' AS sourceType,r.id AS sourceId,r.patient_id AS patientId,p.name AS patientName," +
                "r.review_status AS status,COALESCE(r.period_label,'AI 分析') AS title,r.created_at AS occurredAt,NULL AS confidence " +
                "FROM ai_analysis_record r JOIN patient p ON p.id=r.patient_id JOIN doctor_patient_assignment a ON a.patient_id=r.patient_id AND a.status='ACTIVE' " +
                "WHERE r.review_status=?" + scope + " ORDER BY r.created_at DESC", wanted));
        rows.sort((left, right) -> String.valueOf(right.get("occurredAt")).compareTo(String.valueOf(left.get("occurredAt"))));
        return rows;
    }

    /** Complete original join frame, privately staged until its RR owner has committed. */
    private MedicalQueue prepareMedicalQueue(String wanted, String scopeSql, long actor) {
        requireMedicalActor(actor);requireMedicalUnowned();
        MedicalQueue staged = new MedicalQueue();
        RuntimeException[] diagnosed = new RuntimeException[1];boolean[] committed = {false};
        MedicalQueue result;
        try {
            result = medicalTemplate(Connection.TRANSACTION_REPEATABLE_READ).execute(transaction -> {
                MedicalResource resource = new MedicalResource(Connection.TRANSACTION_REPEATABLE_READ, actor);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {resource.verify();}
                    @Override public void afterCompletion(int status) {
                        committed[0] = status == STATUS_COMMITTED;
                        if (!committed[0]) clearMedicalQueue(staged);
                    }
                });
                try {
                    MedicalRecordValidityProjection.Scope scope = medicalValidity.open();resource.verify();
                    staged.candidates.addAll(medicalQuery(resource,
                            "SELECT 'MEDICAL_RECORD' AS sourceType,m.id AS sourceId,m.patient_id AS patientId,p.name AS patientName," +
                            "m.verification_status AS status,m.verification_status AS verificationStatus,m.content_version AS contentVersion,m.record_type AS title,m.record_date AS occurredAt,m.confidence_score AS confidence," +
                            "CASE WHEN m.verification_status=? THEN 1 ELSE 0 END AS legacyMatch,m.user_id AS queueOwnerId,a.id AS queueAssignmentId,a.doctor_user_id AS queueDoctorId " +
                            "FROM medical_record m JOIN patient p ON p.id=m.patient_id JOIN doctor_patient_assignment a ON a.patient_id=m.patient_id AND a.status='ACTIVE' " +
                            "WHERE " + scope.activeSql("m.") + scopeSql + " ORDER BY m.updated_at DESC", Collections.singletonList(wanted), rows -> {
                        List<MedicalCandidate> candidates = new ArrayList<>();
                        while (rows.next()) {
                            Map<String, Object> row = new LinkedCaseInsensitiveMap<>(10);
                            // Preserve JdbcTemplate's original public column names, values and JDBC conversions.
                            for (int column = 1; column <= 10; column++)
                                row.putIfAbsent(JdbcUtils.lookupColumnName(rows.getMetaData(), column), JdbcUtils.getResultSetValue(rows, column));
                            MedicalHeader header = new MedicalHeader(medicalNumber(rows, "sourceId"), medicalNumber(rows, "patientId"),
                                    medicalNumber(rows, "queueOwnerId"), medicalNumber(rows, "contentVersion"), rows.getString("verificationStatus"));
                            MedicalAssignment assignment = new MedicalAssignment(medicalNumber(rows, "queueAssignmentId"), header.patient, medicalNumber(rows, "queueDoctorId"));
                            Long match = medicalNumber(rows, "legacyMatch");
                            if (!Long.valueOf(0).equals(match) && !Long.valueOf(1).equals(match)) throw medicalChanged();
                            candidates.add(new MedicalCandidate(row, header, assignment, match == 1));
                        }
                        return candidates;
                    }));
                    for (MedicalCandidate candidate : staged.candidates) {
                        MedicalHeader prior = staged.headers.putIfAbsent(candidate.header.id, candidate.header);
                        if (prior != null && !prior.same(candidate.header)) throw medicalChanged();
                        MedicalAssignment old = staged.assignments.putIfAbsent(candidate.assignment.id, candidate.assignment);
                        if (old != null && !old.same(candidate.assignment)) throw medicalChanged();
                    }
                    for (MedicalHeader header : staged.headers.values()) staged.sources.add(header.record());
                    requireWorkspace(staged, actor, resource); // W0, before general source admission.
                    try {medicalAccess.requireReadAdmission(staged.sources, actor);}
                    catch (IllegalStateException failure) {throw new MedicalDenied();}
                    finally {resource.verify();}
                    List<Long> ids = new ArrayList<>(staged.headers.keySet());
                    for (int offset = 0; offset < Math.max(1, ids.size()); offset += 100) {
                        resource.verify();List<Long> part = ids.subList(offset, Math.min(offset + 100, ids.size()));
                        Map<Long, State> batch;
                        try {batch = scope.qualifiedSnapshot(part);}
                        finally {resource.verify();}
                        if (batch == null || !batch.keySet().equals(new LinkedHashSet<>(part))) throw medicalChanged();
                        for (Long id : part) {
                            requireMedicalState(staged.headers.get(id), batch.get(id), MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT);
                            staged.snapshotStates.put(id, batch.get(id));
                        }
                    }
                    requireWorkspace(staged, actor, resource); // W1, before the complete first current frame.
                    Map<Long, State> current = currentMedicalFrame(staged, actor, resource);
                    for (MedicalCandidate candidate : staged.candidates) {
                        State state = current.get(candidate.header.id);
                        boolean selected = !state.reviewParticipating ? candidate.legacyMatch : state.doctor != null &&
                                ("REVIEW_REQUIRED".equals(wanted) && "PENDING".equals(state.doctor.state)
                                || ("VERIFIED".equals(wanted) || "APPROVED".equals(wanted)) && "APPROVED".equals(state.doctor.state)
                                || "REJECTED".equals(wanted) && "REJECTED".equals(state.doctor.state));
                        if (!selected) continue;
                        MedicalRecordReviewSummary summary = MedicalRecordReviewSummary.fromObservedState(state);
                        if (summary == null) throw medicalChanged();
                        candidate.row.put("reviewChannel", state.reviewParticipating ? "DOCTOR" : "LEGACY");
                        candidate.row.put("reviewSummary", summary);
                        candidate.row.put("managed", state.managed);candidate.row.put("validity", state.validity);candidate.row.put("sourceStatus", state.status());
                        staged.rows.add(candidate.row);
                    }
                    resource.verify();return staged;
                } catch (WorkflowException | MedicalDenied failure) {
                    resource.verify();diagnosed[0] = failure;throw failure;
                } catch (RuntimeException failure) {
                    resource.verify();WorkflowException safe = medicalUnavailable();diagnosed[0] = safe;throw safe;
                } catch (Error failure) {resource.verify();throw failure;}
            });
        } catch (RuntimeException failure) {
            clearMedicalQueue(staged);requireMedicalUnowned();requireMedicalActor(actor);
            if (failure == diagnosed[0]) throw failure;
            throw medicalUnavailable();
        } catch (Error failure) {
            clearMedicalQueue(staged);requireMedicalUnowned();requireMedicalActor(actor);throw failure;
        }
        try {
            requireMedicalUnowned();requireMedicalActor(actor);
            if (result != staged || !committed[0]) throw medicalUnavailable();return result;
        } catch (RuntimeException | Error failure) {clearMedicalQueue(staged);throw failure;}
    }

    /** Both public responses finish all ordinary assembly before these last current observations. */
    private void requireMedicalRelease(MedicalQueue medical, long actor) {
        requireMedicalUnowned();requireMedicalActor(actor);
        requireWorkspace(medical, actor, null); // W2.
        currentMedicalFrame(medical, actor, null); // F2 compares the original RR frame, never refreshes output.
        requireWorkspace(medical, actor, null); // W3 prevents a general-access fallback during F2.
        requireMedicalActor(actor);requireMedicalUnowned();
    }

    private Map<Long, State> currentMedicalFrame(MedicalQueue medical, long actor, MedicalResource outer) {
        requireMedicalActor(actor);
        try {
            Map<Long, State> current = medicalAccess.currentReadFrame(medical.sources, medical.snapshotStates, actor);
            if (current == null || !current.keySet().equals(medical.headers.keySet())) throw medicalChanged();
            for (MedicalHeader header : medical.headers.values()) {
                State state = current.get(header.id);
                requireMedicalState(header, state, MedicalReviewQualificationReader.Semantics.CURRENT_CHECK);
                if (!state.signature.equals(medical.snapshotStates.get(header.id).signature)) throw medicalChanged();
            }
            return current;
        } catch (WorkflowException failure) {throw failure;}
        catch (IllegalStateException failure) {throw new MedicalDenied();}
        catch (RuntimeException failure) {throw medicalUnavailable();}
        finally {
            if (outer != null) outer.verify();else requireMedicalUnowned();
            requireMedicalActor(actor);
        }
    }

    /** Exact contributing assignment identities, separate from the general MEDICAL access predicate. */
    private void requireWorkspace(MedicalQueue medical, long actor, MedicalResource outer) {
        requireMedicalActor(actor);
        if (outer != null) outer.verify();else requireMedicalUnowned();
        RuntimeException[] diagnosed = new RuntimeException[1];boolean[] committed = {false};
        Boolean result;
        try {
            result = medicalTemplate(Connection.TRANSACTION_READ_COMMITTED).execute(transaction -> {
                MedicalResource resource = new MedicalResource(Connection.TRANSACTION_READ_COMMITTED, actor);
                if (outer != null && (resource.holder == outer.holder || resource.connection == outer.connection)) throw medicalUnavailable();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {resource.verify();}
                    @Override public void afterCompletion(int status) {committed[0] = status == STATUS_COMMITTED;}
                });
                try {
                    boolean[] first = workspaceActor(actor, resource);
                    requireWorkspaceRoles(first, medical.assignments.values(), actor);
                    List<Long> ids = new ArrayList<>(medical.assignments.keySet());
                    for (int offset = 0; offset < ids.size(); offset += 100) {
                        List<Long> part = ids.subList(offset, Math.min(offset + 100, ids.size()));
                        medicalQuery(resource, "SELECT id,patient_id,doctor_user_id FROM doctor_patient_assignment WHERE id IN (" + medicalMarks(part.size()) +
                                ") AND status='ACTIVE' ORDER BY id LIMIT " + (part.size() + 1), part, rows -> {
                            Set<Long> seen = new LinkedHashSet<>();
                            while (rows.next()) {
                                Long id = medicalNumber(rows, "id");MedicalAssignment expected = medical.assignments.get(id);
                                if (expected == null || !part.contains(id) || !seen.add(id)
                                        || !Objects.equals(expected.patient, medicalNumber(rows, "patient_id"))
                                        || !Objects.equals(expected.doctor, medicalNumber(rows, "doctor_user_id"))) throw new MedicalDenied();
                            }
                            if (!seen.equals(new LinkedHashSet<>(part))) throw new MedicalDenied();return null;
                        });
                    }
                    requireWorkspaceRoles(workspaceActor(actor, resource), medical.assignments.values(), actor);
                    resource.verify();return Boolean.TRUE;
                } catch (WorkflowException | MedicalDenied failure) {
                    resource.verify();diagnosed[0] = failure;throw failure;
                } catch (RuntimeException failure) {
                    resource.verify();WorkflowException safe = medicalUnavailable();diagnosed[0] = safe;throw safe;
                } catch (Error failure) {resource.verify();throw failure;}
            });
            if (!Boolean.TRUE.equals(result) || !committed[0]) throw medicalUnavailable();
        } catch (RuntimeException failure) {
            if (failure == diagnosed[0]) throw failure;
            throw medicalUnavailable();
        } finally {
            if (outer != null) outer.verify();else requireMedicalUnowned();
            requireMedicalActor(actor);
        }
    }

    private boolean[] workspaceActor(long actor, MedicalResource resource) {
        return medicalQuery(resource, "SELECT u.id,u.status,u.deleted," +
                "EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.status=1 AND COALESCE(r.deleted,0)=0 AND r.role_code='admin') AS admin_role," +
                "EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.status=1 AND COALESCE(r.deleted,0)=0 AND r.role_code='doctor') AS doctor_role " +
                "FROM sys_user u WHERE u.id=? LIMIT 2", Collections.singletonList(actor), rows -> {
            if (!rows.next() || !Long.valueOf(actor).equals(medicalNumber(rows, "id")) || !Long.valueOf(1).equals(medicalNumber(rows, "status"))) throw new MedicalDenied();
            Long deleted = medicalNumber(rows, "deleted");
            boolean[] roles = {medicalTruth(rows.getObject("admin_role")), medicalTruth(rows.getObject("doctor_role"))};
            if (deleted != null && deleted != 0 || !roles[0] && !roles[1] || rows.next()) throw new MedicalDenied();return roles;
        });
    }

    private static void requireWorkspaceRoles(boolean[] roles, java.util.Collection<MedicalAssignment> assignments, long actor) {
        if (!roles[0]) for (MedicalAssignment assignment : assignments)
            if (!roles[1] || !Long.valueOf(actor).equals(assignment.doctor)) throw new MedicalDenied();
    }
    private TransactionTemplate medicalTemplate(int isolation) {
        medicalGraph();TransactionTemplate template = new TransactionTemplate(medicalTransactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);template.setIsolationLevel(isolation);return template;
    }
    private void medicalGraph() {
        try {
            if (jdbc == null || jdbc.getDataSource() == null || medicalTransactions == null || medicalTransactions.getDataSource() != jdbc.getDataSource()
                    || medicalValidity == null || medicalAccess == null || medicalQualification == null) throw medicalUnavailable();
            medicalValidity.requireDataSource(jdbc.getDataSource());medicalAccess.requireDataSource(jdbc.getDataSource());medicalQualification.requireDataSource(jdbc.getDataSource());
        } catch (RuntimeException failure) {throw medicalUnavailable();}
    }
    private void requireMedicalUnowned() {
        medicalGraph();
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw medicalUnavailable();
    }
    private static void requireMedicalActor(long actor) {
        if (!safeMedicalId(actor) || !Long.valueOf(actor).equals(CurrentUserUtil.getCurrentUserId())) throw new MedicalDenied();
    }
    private final class MedicalResource {
        final Thread thread = Thread.currentThread();final int isolation;final long actor;
        final ConnectionHolder holder;final SimpleConnectionHandle handle;final Connection connection;
        MedicalResource(int isolation, long actor) {
            this.isolation = isolation;this.actor = actor;requireMedicalActor(actor);medicalGraph();
            Object value = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                    || value == null || value.getClass() != ConnectionHolder.class) throw medicalUnavailable();
            holder = (ConnectionHolder)value;
            if (!holder.isSynchronizedWithTransaction() || holder.isRollbackOnly() || holder.getConnectionHandle() == null
                    || holder.getConnectionHandle().getClass() != SimpleConnectionHandle.class) throw medicalUnavailable();
            handle = (SimpleConnectionHandle)holder.getConnectionHandle();connection = handle.getConnection();verify();
        }
        void verify() {
            requireMedicalActor(actor);medicalGraph();
            if (Thread.currentThread() != thread || !TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly() || TransactionSynchronizationManager.getResource(jdbc.getDataSource()) != holder
                    || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly() || holder.getConnectionHandle() != handle || handle.getConnection() != connection) throw medicalUnavailable();
            try {
                if (connection == null || holder.getConnection() != connection || connection.isClosed() || connection.getAutoCommit()
                        || connection.getTransactionIsolation() != isolation) throw medicalUnavailable();
            } catch (java.sql.SQLException | IllegalStateException failure) {throw medicalUnavailable();}
        }
    }
    private <T> T medicalQuery(MedicalResource resource, String sql, List<?> args, org.springframework.jdbc.core.ResultSetExtractor<T> extractor) {
        resource.verify();
        try {
            T result = jdbc.query(sql, statement -> {
                resource.verify();statement.setQueryTimeout(resource.holder.hasTimeout() ? Math.min(5, resource.holder.getTimeToLiveInSeconds()) : 5);
                for (int index = 0; index < args.size(); index++) statement.setObject(index + 1, args.get(index));
            }, extractor);
            resource.verify();return result;
        } catch (WorkflowException | MedicalDenied failure) {resource.verify();throw failure;}
        catch (RuntimeException failure) {resource.verify();throw medicalUnavailable();}
        catch (Error failure) {resource.verify();throw failure;}
    }
    private static void requireMedicalState(MedicalHeader header, State state, MedicalReviewQualificationReader.Semantics semantics) {
        if (header == null || state == null || !state.reviewObserved || state.signature == null || state.checkedAt == null
                || state.qualificationSemantics != semantics || state.id != header.id || !Objects.equals(state.patientId, header.patient)
                || !Objects.equals(state.ownerId, header.owner) || !Objects.equals(state.version, header.version)
                || !Objects.equals(state.review, header.review) || !state.active()) throw medicalChanged();
    }
    private static Long medicalNumber(java.sql.ResultSet rows, String column) throws java.sql.SQLException {
        java.math.BigDecimal value = rows.getBigDecimal(column);if (value == null) return null;
        try {return value.longValueExact();} catch (ArithmeticException invalid) {throw medicalChanged();}
    }
    private static boolean safeMedicalId(Long value) {return value != null && value > 0 && value <= 9007199254740991L;}
    private static boolean medicalTruth(Object value) {return Boolean.TRUE.equals(value) || value instanceof Number && ((Number)value).intValue() != 0;}
    private static String medicalMarks(int size) {if (size < 1 || size > 100) throw medicalChanged();return String.join(",", Collections.nCopies(size, "?"));}
    private static WorkflowException medicalUnavailable() {return new WorkflowException(503, "CURRENT_RESULT_UNAVAILABLE", "The current medical record result could not be verified.");}
    private static WorkflowException medicalChanged() {return new WorkflowException(409, "CURRENT_RESULT_UNAVAILABLE", "The current medical record result could not be verified.");}
    private static final class MedicalDenied extends IllegalStateException {MedicalDenied() {super("Access denied to source record.");}}
    private static void clearMedicalQueue(MedicalQueue queue) {
        if (queue == null) return;
        for (MedicalCandidate candidate : queue.candidates) {
            candidate.row.remove("reviewSummary");candidate.row.remove("reviewChannel");candidate.row.remove("managed");candidate.row.remove("validity");candidate.row.remove("sourceStatus");
        }
        queue.rows.clear();
    }
    private static final class MedicalHeader {
        final long id;final Long patient, owner, version;final String review;
        MedicalHeader(Long id, Long patient, Long owner, Long version, String review) {
            if (!safeMedicalId(id) || !safeMedicalId(patient) || owner != null && !safeMedicalId(owner) || !safeMedicalId(version)) throw medicalChanged();
            this.id = id;this.patient = patient;this.owner = owner;this.version = version;this.review = review;
        }
        boolean same(MedicalHeader other) {return id == other.id && Objects.equals(patient, other.patient) && Objects.equals(owner, other.owner) && Objects.equals(version, other.version) && Objects.equals(review, other.review);}
        MedicalRecord record() {
            MedicalRecord record = new MedicalRecord();record.setId(id);record.setPatientId(patient);record.setUserId(owner);record.setContentVersion(version);record.setVerificationStatus(review);return record;
        }
    }
    private static final class MedicalAssignment {
        final long id;final Long patient, doctor;
        MedicalAssignment(Long id, Long patient, Long doctor) {
            if (!safeMedicalId(id) || !safeMedicalId(patient) || !safeMedicalId(doctor)) throw medicalChanged();
            this.id = id;this.patient = patient;this.doctor = doctor;
        }
        boolean same(MedicalAssignment other) {return id == other.id && Objects.equals(patient, other.patient) && Objects.equals(doctor, other.doctor);}
    }
    private static final class MedicalCandidate {
        final Map<String, Object> row;final MedicalHeader header;final MedicalAssignment assignment;final boolean legacyMatch;
        MedicalCandidate(Map<String, Object> row, MedicalHeader header, MedicalAssignment assignment, boolean legacyMatch) {
            this.row = row;this.header = header;this.assignment = assignment;this.legacyMatch = legacyMatch;
        }
    }
    private static final class MedicalQueue {
        final List<MedicalCandidate> candidates = new ArrayList<>();final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<Long, MedicalHeader> headers = new LinkedHashMap<>();final Map<Long, MedicalAssignment> assignments = new LinkedHashMap<>();
        final List<MedicalRecord> sources = new ArrayList<>();final Map<Long, State> snapshotStates = new LinkedHashMap<>();
    }

    public List<Map<String, Object>> notes(Long patientId) {
        requireAssigned(patientId);
        return jdbc.queryForList("SELECT n.id,n.patient_id AS patientId,n.note_type AS noteType,n.note_text AS noteText,n.visibility," +
                "n.created_at AS createdAt,u.real_name AS doctorName FROM doctor_clinical_note n LEFT JOIN sys_user u ON u.id=n.doctor_user_id " +
                "WHERE n.patient_id=? ORDER BY n.created_at DESC", patientId);
    }

    public List<Map<String, Object>> plans(Long patientId) {
        requireAssigned(patientId);
        String marker=collaborationEnabled()?",workflow_version AS workflowVersion":"";
        String scope=collaborationEnabled()?" AND workflow_version=0":"";
        return jdbc.queryForList("SELECT id,patient_id AS patientId,title,plan_type AS planType,instructions,target_date AS targetDate,status,created_at AS createdAt" + marker +
                " FROM doctor_care_plan WHERE patient_id=? AND (status IS NULL OR status<>'COLLABORATION')"+scope+" ORDER BY created_at DESC", patientId);
    }

    @Transactional
    public void saveNote(Map<String, Object> body) {
        Long patientId = requiredLong(body, "patientId");
        requireAssigned(patientId);
        String text = requiredText(body, "noteText", 4000);
        String type = text(body, "noteType", "FOLLOW_UP");
        String visibility = text(body, "visibility", "CARE_TEAM");
        if (!java.util.Arrays.asList("CARE_TEAM", "PATIENT").contains(visibility)) throw new IllegalArgumentException("无效的医嘱可见范围。");
        jdbc.update("INSERT INTO doctor_clinical_note(doctor_user_id,patient_id,note_type,note_text,visibility) VALUES(?,?,?,?,?)",
                currentUserId(), patientId, type, text, visibility);
    }

    @Transactional
    public void savePlan(Map<String, Object> body) {
        Long patientId = requiredLong(body, "patientId");
        requireAssigned(patientId);
        String status=text(body,"status","ACTIVE");
        if ("COLLABORATION".equalsIgnoreCase(status)) throw new IllegalArgumentException("此状态仅供协作照护计划使用。");
        jdbc.update("INSERT INTO doctor_care_plan(doctor_user_id,patient_id,title,plan_type,instructions,target_date,status) VALUES(?,?,?,?,?,?,?)",
                currentUserId(), patientId, requiredText(body, "title", 160), text(body, "planType", "FOLLOW_UP"),
                requiredText(body, "instructions", 4000), date(body.get("targetDate")), status);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void review(Map<String, Object> body) {
        String sourceType = requiredText(body, "sourceType", 30).toUpperCase();
        Long sourceId = requiredLong(body, "sourceId");
        String decision = requiredText(body, "decision", 30).toUpperCase();
        if (!java.util.Arrays.asList("APPROVED", "REJECTED").contains(decision)) throw new IllegalArgumentException("无效的审核结论。");
        Long patientId;
        if ("MEDICAL_RECORD".equals(sourceType)) {
            patientId = medicalRecordService.reviewRecordForDoctor(sourceId, "APPROVED".equals(decision),
                    body.get("reviewNote") == null ? null : String.valueOf(body.get("reviewNote")), MedicalRecordReviewContract.expectedVersion(body));
        } else if ("AI_ANALYSIS".equals(sourceType)) {
            patientId = reviewPatient("ai_analysis_record", sourceId);
            requireAssigned(patientId);
            jdbc.update("UPDATE ai_analysis_record SET review_status=?,reviewed_by=?,reviewed_at=NOW() WHERE id=?", decision, currentUserId(), sourceId);
        } else {
            throw new IllegalArgumentException("暂不支持该审核来源。");
        }
        jdbc.update("INSERT INTO doctor_review_log(doctor_user_id,patient_id,source_type,source_id,decision,review_note) VALUES(?,?,?,?,?,?)",
                currentUserId(), patientId, sourceType, sourceId, decision, text(body, "reviewNote", null));
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void assign(Map<String, Object> body) {
        if (!CurrentUserUtil.isAdmin()) throw new IllegalStateException("Only administrators can assign doctors.");
        Long doctorId = requiredLong(body, "doctorUserId");
        Long patientId = requiredLong(body, "patientId");
        if (householdAuthorityLocks != null) {
            householdAuthorityLocks.lockPatientAndSubjects(patientId, java.util.Arrays.asList(currentUserId(), doctorId));
            org.familyhealthcare.service.workflow.HouseholdGovernance.requireCurrentAdmin(jdbc, currentUserId());
        }
        requireAssigned(patientId);
        Integer doctors = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.id=? AND u.status=1 AND COALESCE(u.deleted,0)=0 AND r.role_code='doctor' AND r.status=1 AND COALESCE(r.deleted,0)=0", Integer.class, doctorId);
        if(doctors==null||doctors==0)throw new IllegalArgumentException("Select an active doctor for the consultation.");
        jdbc.update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by,status,care_team_role) VALUES(?,?,?,'ACTIVE',?) " +
                        "ON DUPLICATE KEY UPDATE assigned_by=VALUES(assigned_by),status='ACTIVE',care_team_role=VALUES(care_team_role),assigned_at=NOW()",
                doctorId, patientId, currentUserId(), text(body, "careTeamRole", "ATTENDING"));
    }

    private boolean collaborationEnabled() { return carePlanProperties != null && carePlanProperties.isEnabled(); }

    private void requireDoctor() {
        if (!CurrentUserUtil.isAdmin() && !CurrentUserUtil.hasRole("doctor")) throw new IllegalStateException("需要医生权限。");
    }

    private void requireAssigned(Long patientId) {
        requireDoctor();
        if(patientId==null || jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE id=? AND COALESCE(deleted,0)=0 AND status=1",Integer.class,patientId)==0)throw new IllegalArgumentException("Patient not found.");
        if (CurrentUserUtil.isAdmin()) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM doctor_patient_assignment WHERE doctor_user_id=? AND patient_id=? AND status='ACTIVE'",
                Integer.class, currentUserId(), patientId);
        if (count == null || count == 0) throw new IllegalStateException("该患者尚未分配给当前医生。");
    }

    private int countScoped(String table, String condition) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + condition;
        List<Object> args = new ArrayList<>();
        if (!CurrentUserUtil.isAdmin()) { sql += " AND doctor_user_id=?"; args.add(currentUserId()); }
        Integer count = jdbc.queryForObject(sql, args.toArray(), Integer.class);
        return count == null ? 0 : count;
    }

    private Long reviewPatient(String table,Long id) {
        List<Long> ids=jdbc.queryForList("SELECT patient_id FROM " + table + " WHERE id=?",Long.class,id);
        if(ids.isEmpty()||ids.get(0)==null)throw new IllegalArgumentException("The review record does not exist or has no patient.");
        return ids.get(0);
    }

    private Long currentUserId() {
        Long id = CurrentUserUtil.getCurrentUserId();
        if (id == null) throw new IllegalStateException("You are not signed in. Please sign in first.");
        return id;
    }

    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }
    private static Long requiredLong(Map<String, Object> body, String key) {
        Object value = body.get(key); if (value == null) throw new IllegalArgumentException(key + "不能为空。");
        return Long.valueOf(String.valueOf(value));
    }
    private static String requiredText(Map<String, Object> body, String key, int max) {
        String value = text(body, key, null); if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + "不能为空。");
        if (value.length() > max) throw new IllegalArgumentException(key + "长度超出限制。"); return value.trim();
    }
    private static String text(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key); return value == null || String.valueOf(value).trim().isEmpty() ? fallback : String.valueOf(value).trim();
    }
    private static LocalDate date(Object value) { return value == null || String.valueOf(value).trim().isEmpty() ? null : LocalDate.parse(String.valueOf(value)); }
}
