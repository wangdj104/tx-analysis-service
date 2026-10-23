package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.workflow.WorkflowException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;

/**
 * Bounded internal reader only. Callers provide current authority and the genuine
 * transaction; locked calls also require the established authority/native/parent9
 * lock order. No admission, transaction creation, mutation, or external file access.
 */
final class MedicalReviewSourceRepository {
    private static final String HEADER = "SELECT id,patient_id,user_id,patient_name,"
            + "DATE_FORMAT(record_date,'%Y-%m-%d') AS record_date_text,record_date IS NULL AS record_date_null,"
            + "record_type,hospital_name,dept_name,doctor_name,remark,source_type,source_external_id,"
            + "CAST(confidence_score AS CHAR) AS confidence_score_text,"
            + "DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') AS created_at_text,created_at IS NULL AS created_at_null,"
            + "content_version,OCTET_LENGTH(ai_raw_result) AS ai_bytes,SHA2(ai_raw_result,256) AS ai_hash,"
            + "OCTET_LENGTH(import_facts_json) AS facts_bytes,SHA2(import_facts_json,256) AS facts_hash,"
            + "verification_status,verified_by,"
            + "DATE_FORMAT(verified_at,'%Y-%m-%dT%H:%i:%s.%f') AS verified_at_text,verified_at IS NULL AS verified_at_null,"
            + "OCTET_LENGTH(verification_note) AS note_bytes,SHA2(verification_note,256) AS note_hash "
            + "FROM medical_record WHERE id=?";
    private static final String ITEMS = "SELECT id,record_id,item_name,item_code,result_value,unit,reference_range,is_abnormal,"
            + "CAST(ai_confidence AS CHAR) AS ai_confidence_text,"
            + "DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') AS created_at_text,created_at IS NULL AS created_at_null "
            + "FROM medical_record_item WHERE record_id=? ORDER BY id LIMIT 101";
    private static final String ATTACHMENTS = "SELECT id,record_id,file_path,file_name,file_type,file_size,"
            + "DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') AS created_at_text,created_at IS NULL AS created_at_null,"
            + "OCTET_LENGTH(file_content) AS content_bytes,SHA2(file_content,256) AS content_hash "
            + "FROM medical_record_attachment WHERE record_id=? ORDER BY id LIMIT 101";
    private static final String BINDING = "SELECT id,patient_id,user_id,content_version FROM medical_record WHERE id=?";
    private static final String STATES = "SELECT record_id,channel,source_generation,patient_id,owner_user_id,content_version,"
            + "decision_version,cycle,state,last_event_id,current_decision_event_id "
            + "FROM medical_review_state WHERE record_id=? ORDER BY channel LIMIT 3";
    private static final String EVENT_COLUMNS = "SELECT id,record_id,source_generation,channel,operation,before_content_version,"
            + "content_version,decision_version,cycle,state,actor_id,actor_kind,"
            + "DATE_FORMAT(occurred_at,'%Y-%m-%dT%H:%i:%s.%f') AS occurred_at_text,occurred_at IS NULL AS occurred_at_null ";
    private static final String HEAD = EVENT_COLUMNS + "FROM medical_review_event WHERE id=?";
    private static final String WATERMARK = EVENT_COLUMNS + "FROM medical_review_event "
            + "WHERE record_id=? AND channel='RECORD' AND operation='SOURCE_DELETED' ORDER BY id DESC LIMIT 1";
    private static final String CLOSED = EVENT_COLUMNS + "FROM medical_review_event "
            + "WHERE record_id=? AND source_generation=? AND channel='RECORD' AND operation='SOURCE_DELETED' ORDER BY id DESC LIMIT 1";
    private static final String LATER = "SELECT EXISTS(SELECT 1 FROM medical_review_event "
            + "WHERE record_id=? AND source_generation=? AND channel=? AND decision_version>?)";

    private final DataSource dataSource;
    private final MedicalReviewSchema schema;
    MedicalReviewSourceRepository(JdbcTemplate jdbc, MedicalReviewSchema schema) {
        if (jdbc == null || jdbc.getDataSource() == null || schema == null) throw MedicalReviewSchema.notReady();
        this.dataSource = jdbc.getDataSource(); this.schema = schema;
    }

    /**
     * Content material only; this method does not inspect READY or authorize access.
     * The later caller must combine readQualifiedSnapshotLifecycle on this same transaction
     * and source binding with the required current-authority and fresh-lifecycle
     * checks before exposing an authorized or actionable preview.
     */
    Content readSnapshot(MedicalRecord expectedBinding) {
        return withConnection(Mode.SNAPSHOT_RR, connection -> readContent(connection, expectedBinding, false));
    }

    /**
     * Receipt recovery and authority/native locking must precede this expensive read.
     * This content-only result does not establish READY or current authority: the
     * later caller must also use readLockedLifecycle in this same transaction and
     * for this source binding, together with the required locked-authority checks.
     */
    Content readLocked(MedicalRecord expectedBinding) {
        return withConnection(Mode.LOCKED_MUTATION_RC, connection -> readContent(connection, expectedBinding, true));
    }

    /**
     * Reuse READY from the qualification just read on the caller's verified RR
     * connection. The caller retains its guard across both reads and still
     * performs a fresh current-authority/qualification check before disclosure.
     * This evidence is local to that read; mutation and fresh readers inspect
     * storage independently.
     */
    Lifecycle readQualifiedSnapshotLifecycle(MedicalRecord source, Connection observedConnection,
            org.familyhealthcare.service.medicalrecord.MedicalRecordValidityProjection.State observed) {
        return withConnection(Mode.SNAPSHOT_RR, connection -> {
            if(connection!=observedConnection||source==null||observed==null||!observed.reviewObserved
                    ||observed.qualificationSemantics!=MedicalReviewQualificationReader.Semantics.CALLER_TRANSACTION_SNAPSHOT
                    ||observed.checkedAt==null||observed.signature==null||observed.signature.isEmpty()
                    ||!Long.valueOf(observed.id).equals(source.getId())
                    ||!Objects.equals(observed.patientId,source.getPatientId())||!Objects.equals(observed.ownerId,source.getUserId())
                    ||!Objects.equals(observed.version,source.getContentVersion())||!Objects.equals(observed.review,source.getVerificationStatus())
                    ||!observed.reviewSummaryMetadataAvailable())throw MedicalReviewSchema.notReady();
            NativeBinding current = binding(connection, source);
            current.requireExpected(source, true);
            return sample(connection, current, false).lifecycle();
        });
    }

    /**
     * Two bounded RC observations detect differences, not a serialized snapshot.
     * A later external authority proxy checks current access around this method.
     */
    Lifecycle readFreshLifecycle(MedicalRecord expectedBinding) {
        return withConnection(Mode.FRESH_READ_RC, connection -> {
            ready(connection);
            NativeBinding current = binding(connection, expectedBinding);
            current.requireExpected(expectedBinding, false);
            Sample first = sample(connection, current, false);
            NativeBinding next = binding(connection, expectedBinding, true);
            // Heads were point-validated once; immutable facts need not be refetched.
            List<CurrentState> states = readStates(connection, next, false);
            long watermark = readDeletionWatermark(connection, next.id);
            if (!current.same(next) || !first.states.equals(states) || first.watermark != watermark) throw integrity();
            return first.lifecycle();
        });
    }

    Lifecycle readLockedLifecycle(MedicalRecord source) {
        return withConnection(Mode.LOCKED_MUTATION_RC, connection -> {
            ready(connection);
            NativeBinding current = binding(connection, source);
            current.requireExpected(source, true);
            return sample(connection, current, true).lifecycle();
        });
    }

    /**
     * The native lifecycle owns the preceding schema inspection on this exact
     * RC connection and repeats it before commit. Independent command and
     * receipt callers still use readLockedLifecycle and inspect for themselves.
     */
    Lifecycle readInspectedLockedLifecycle(MedicalRecord source, Connection inspectedConnection,
            MedicalReviewSchema.Status inspectedStatus) {
        return withConnection(Mode.LOCKED_MUTATION_RC, connection -> {
            if (connection != inspectedConnection || inspectedStatus != MedicalReviewSchema.Status.READY)
                throw MedicalReviewSchema.notReady();
            NativeBinding current = binding(connection, source);
            current.requireExpected(source, true);
            return sample(connection, current, true).lifecycle();
        });
    }

    private Content readContent(Connection connection, MedicalRecord expected, boolean lock) throws SQLException {
        long recordId = expectedId(expected);
        NativeHeader header = query(connection, HEADER + (lock ? " FOR UPDATE" : ""), result -> {
            if (!result.next()) throw denied();
            NativeHeader value = new NativeHeader(number(result,"id"),number(result,"patient_id"),number(result,"user_id"),
                    result.getString("patient_name"),formatted(result,"record_date",false),result.getString("record_type"),
                    result.getString("hospital_name"),result.getString("dept_name"),result.getString("doctor_name"),
                    result.getString("remark"),result.getString("source_type"),result.getString("source_external_id"),
                    result.getString("confidence_score_text"),formatted(result,"created_at",true),number(result,"content_version"),
                    reference(result,"ai"),reference(result,"facts"),result.getString("verification_status"),
                    number(result,"verified_by"),formatted(result,"verified_at",true),reference(result,"note"));
            if (result.next()) throw integrity();
            return value;
        }, recordId);
        new NativeBinding(header.id,header.patientId,header.userId,header.contentVersion).requireExpected(expected,true);
        List<NativeItem> items = query(connection, ITEMS, result -> {
            List<NativeItem> values = new ArrayList<>();
            while (result.next()) {
                if (values.size() == MAX_CHILDREN) throw limit();
                values.add(new NativeItem(number(result,"id"),number(result,"record_id"),result.getString("item_name"),
                        result.getString("item_code"),result.getString("result_value"),result.getString("unit"),
                        result.getString("reference_range"),number(result,"is_abnormal"),
                        result.getString("ai_confidence_text"),formatted(result,"created_at",true)));
            }
            return values;
        },recordId);
        List<NativeAttachment> attachments = query(connection, ATTACHMENTS, result -> {
            List<NativeAttachment> values = new ArrayList<>();
            while (result.next()) {
                if (values.size() == MAX_CHILDREN) throw limit();
                values.add(new NativeAttachment(number(result,"id"),number(result,"record_id"),result.getString("file_path"),
                        result.getString("file_name"),result.getString("file_type"),number(result,"file_size"),
                        formatted(result,"created_at",true),reference(result,"content")));
            }
            return values;
        },recordId);
        return assemble(header,items,attachments);
    }

    private void ready(Connection connection) {
        if (schema.inspectStorage(connection) != MedicalReviewSchema.Status.READY) throw MedicalReviewSchema.notReady();
    }

    private NativeBinding binding(Connection connection, MedicalRecord expected) throws SQLException {
        return binding(connection, expected, false);
    }

    private NativeBinding binding(Connection connection, MedicalRecord expected, boolean recheck) throws SQLException {
        return query(connection,BINDING,result -> {
            if (!result.next()) throw recheck ? integrity() : denied();
            NativeBinding binding = new NativeBinding(number(result,"id"),number(result,"patient_id"),
                    number(result,"user_id"),number(result,"content_version"));
            if (result.next()) throw integrity();
            return binding;
        },expectedId(expected));
    }

    private Sample sample(Connection connection, NativeBinding source, boolean lock) throws SQLException {
        List<CurrentState> states = readCurrentHeads(connection,source,lock);
        long watermark = readDeletionWatermark(connection,source.id);
        String generation = states.isEmpty() ? null : states.get(0).generation;
        if (generation != null) {
            Event closed = deletion(connection,CLOSED,source.id,generation);
            if (closed != null) {
                if (!generation.equals(closed.generation)) throw integrity();
                throw integrity();
            }
        }
        return new Sample(source,states,watermark);
    }

    private List<CurrentState> readCurrentHeads(Connection connection, NativeBinding source, boolean lock) throws SQLException {
        // Acquire both state locks in DOCTOR,SOURCE order before any immutable event query.
        List<CurrentState> states = readStates(connection,source,lock);
        for (CurrentState state : states) {
            Event event = query(connection,HEAD,result -> {
                if (!result.next()) throw integrity();
                Event value = event(result);
                if (result.next()) throw integrity();
                return value;
            },state.lastEventId);
            if (event.id != state.lastEventId || event.recordId != source.id || !event.generation.equals(state.generation)
                    || event.channel != state.channel || event.contentVersion != state.contentVersion
                    || event.decisionVersion != state.decisionVersion || event.cycle != state.cycle || event.state != state.state)
                throw integrity();
            boolean decision = state.state == State.APPROVED || state.state == State.REJECTED;
            if (decision && (state.decisionEventId == null || state.decisionEventId != event.id
                    || event.operation != (state.state == State.APPROVED ? Operation.APPROVE : Operation.REJECT))) throw integrity();
            if (!decision && state.decisionEventId != null) throw integrity();
            long later = query(connection,LATER,result -> {
                if (!result.next()) throw integrity();
                Long value = number(result,1);
                if (value == null || (value != 0 && value != 1) || result.next()) throw integrity();
                return value;
            },source.id,state.generation,state.channel.name(),state.decisionVersion);
            if (later != 0) throw integrity();
        }
        return states;
    }

    private List<CurrentState> readStates(Connection connection, NativeBinding source, boolean lock) throws SQLException {
        return query(connection,STATES + (lock ? " FOR UPDATE" : ""),result -> {
            List<CurrentState> values = new ArrayList<>();
            while (result.next()) {
                if (values.size() == 2) throw integrity();
                CurrentState value = new CurrentState(number(result,"record_id"),result.getString("channel"),
                        result.getString("source_generation"),number(result,"patient_id"),number(result,"owner_user_id"),
                        number(result,"content_version"),number(result,"decision_version"),number(result,"cycle"),
                        result.getString("state"),number(result,"last_event_id"),number(result,"current_decision_event_id"));
                if (value.recordId != source.id || !Objects.equals(value.patientId,source.patientId)
                        || !Objects.equals(value.ownerId,source.ownerId) || value.contentVersion != source.version) throw integrity();
                if (!values.isEmpty()) {
                    CurrentState previous = values.get(0);
                    if (previous.channel != Channel.DOCTOR || value.channel != Channel.SOURCE
                            || !previous.generation.equals(value.generation)) throw integrity();
                }
                values.add(value);
            }
            return values;
        },source.id);
    }

    private long readDeletionWatermark(Connection connection, long recordId) throws SQLException {
        Event event = deletion(connection,WATERMARK,recordId,null);
        return event == null ? 0 : event.id;
    }

    private Event deletion(Connection connection, String sql, long recordId, String generation) throws SQLException {
        Object[] keys = generation == null ? new Object[]{recordId} : new Object[]{recordId,generation};
        return query(connection,sql,result -> {
            if (!result.next()) return null;
            Event event = event(result);
            if (result.next() || event.recordId != recordId || event.channel != Channel.RECORD
                    || event.operation != Operation.SOURCE_DELETED || (generation != null && !generation.equals(event.generation)))
                throw integrity();
            // No historical actor/binding comparison against a replacement native record.
            return event;
        },keys);
    }

    private static Event event(ResultSet result) throws SQLException {
        return new Event(number(result,"id"),number(result,"record_id"),result.getString("source_generation"),
                result.getString("channel"),result.getString("operation"),number(result,"before_content_version"),
                number(result,"content_version"),number(result,"decision_version"),number(result,"cycle"),
                result.getString("state"),number(result,"actor_id"),result.getString("actor_kind"),
                formatted(result,"occurred_at",true));
    }

    private enum Mode {
        SNAPSHOT_RR(Connection.TRANSACTION_REPEATABLE_READ,true),
        FRESH_READ_RC(Connection.TRANSACTION_READ_COMMITTED,true),
        LOCKED_MUTATION_RC(Connection.TRANSACTION_READ_COMMITTED,false);
        final int isolation; final boolean readOnly;
        Mode(int isolation, boolean readOnly) { this.isolation = isolation; this.readOnly = readOnly; }
    }
    private interface Work<T> { T read(Connection connection) throws SQLException; }
    private interface Rows<T> { T read(ResultSet result) throws SQLException; }

    private <T> T withConnection(Mode mode, Work<T> work) {
        Connection borrowed = null;
        try {
            requireMode(mode);
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            // An arbitrary holder/handle getter may acquire lazily. Accept only the
            // standard eager form used by DataSourceTransactionManager.
            if (resource == null || resource.getClass() != ConnectionHolder.class) throw MedicalReviewSchema.notReady();
            ConnectionHolder holder = (ConnectionHolder) resource;
            if (!holder.isSynchronizedWithTransaction() || holder.getConnectionHandle() == null
                    || holder.getConnectionHandle().getClass() != SimpleConnectionHandle.class) throw MedicalReviewSchema.notReady();
            SimpleConnectionHandle handle = (SimpleConnectionHandle) holder.getConnectionHandle();
            Connection held = handle.getConnection();
            verifyConnection(held,mode);
            requireMode(mode);
            if (TransactionSynchronizationManager.getResource(dataSource) != holder || holder.getConnectionHandle() != handle
                    || handle.getConnection() != held || holder.getConnection() != held) throw MedicalReviewSchema.notReady();
            borrowed = DataSourceUtils.getConnection(dataSource);
            if (TransactionSynchronizationManager.getResource(dataSource) != holder || borrowed != held
                    || holder.getConnectionHandle() != handle || handle.getConnection() != held
                    || holder.getConnection() != held || !holder.isSynchronizedWithTransaction()
                    || !DataSourceUtils.isConnectionTransactional(borrowed,dataSource)) throw MedicalReviewSchema.notReady();
            requireMode(mode);
            verifyConnection(borrowed,mode);
            return work.read(borrowed);
        } catch (WorkflowException failure) { throw failure; }
        catch (SQLException | RuntimeException failure) { throw MedicalReviewSchema.notReady(); }
        finally {
            if (borrowed != null) DataSourceUtils.releaseConnection(borrowed,dataSource);
        }
    }
    private static void requireMode(Mode mode) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly() != mode.readOnly) throw MedicalReviewSchema.notReady();
    }
    private static void verifyConnection(Connection connection, Mode mode) throws SQLException {
        if (connection == null || connection.getAutoCommit() || connection.getTransactionIsolation() != mode.isolation)
            throw MedicalReviewSchema.notReady();
    }
    private static <T> T query(Connection connection, String sql, Rows<T> rows, Object... keys) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int i=0;i<keys.length;i++) statement.setObject(i+1,keys[i]);
            try (ResultSet result = statement.executeQuery()) { return rows.read(result); }
        }
    }
    private static Long number(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column); return result.wasNull() ? null : value;
    }
    private static Long number(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column); return result.wasNull() ? null : value;
    }
    private static String formatted(ResultSet result, String column, boolean timestamp) throws SQLException {
        String value = result.getString(column+"_text");
        Long nil = number(result,column+"_null");
        if (nil == null || (nil != 0 && nil != 1) || (nil == 1) != (value == null)) throw integrity();
        if (timestamp) time(value); else date(value);
        return value;
    }
    private static TextReference reference(ResultSet result, String prefix) throws SQLException {
        Long bytes = number(result,prefix+"_bytes"); String hash = result.getString(prefix+"_hash");
        if (bytes == null && hash == null) return null;
        return new TextReference(bytes,hash);
    }
    private static long expectedId(MedicalRecord expected) {
        if (expected == null) throw denied();
        return MedicalReviewContentFingerprint.id(expected.getId());
    }
    private static WorkflowException denied() {
        return new WorkflowException(403,"MEDICAL_RECORD_ACCESS_DENIED","Access to this medical record is not available.");
    }

    private static final class NativeBinding {
        final long id,version; final Long patientId,ownerId;
        NativeBinding(Long id,Long patientId,Long ownerId,Long version) {
            this.id=MedicalReviewContentFingerprint.id(id);this.version=MedicalReviewContentFingerprint.id(version);
            nullableId(patientId);nullableId(ownerId);this.patientId=patientId;this.ownerId=ownerId;
        }
        void requireExpected(MedicalRecord expected, boolean versionRequired) {
            if (expected == null || !Long.valueOf(id).equals(expected.getId())
                    || !Objects.equals(patientId,expected.getPatientId()) || !Objects.equals(ownerId,expected.getUserId())
                    || (versionRequired && !Long.valueOf(version).equals(expected.getContentVersion()))) throw denied();
        }
        boolean same(NativeBinding other) {
            return id==other.id && version==other.version && Objects.equals(patientId,other.patientId) && Objects.equals(ownerId,other.ownerId);
        }
    }
    private static final class CurrentState {
        final long recordId,contentVersion,decisionVersion,cycle,lastEventId;
        final Long patientId,ownerId,decisionEventId;
        final String generation; final Channel channel; final State state;
        CurrentState(Long recordId,String channel,String generation,Long patientId,Long ownerId,Long contentVersion,
                     Long decisionVersion,Long cycle,String state,Long lastEventId,Long decisionEventId) {
            this.recordId=MedicalReviewContentFingerprint.id(recordId);
            this.contentVersion=MedicalReviewContentFingerprint.id(contentVersion);
            this.decisionVersion=MedicalReviewContentFingerprint.id(decisionVersion);
            this.cycle=MedicalReviewContentFingerprint.id(cycle);this.lastEventId=MedicalReviewContentFingerprint.id(lastEventId);
            nullableId(patientId);nullableId(ownerId);nullableId(decisionEventId);
            this.patientId=patientId;this.ownerId=ownerId;this.decisionEventId=decisionEventId;this.generation=generation;
            try {
                this.channel=Channel.valueOf(channel);this.state=State.valueOf(state);
                requireStateFacts(generation,this.channel,this.state,this.contentVersion,this.decisionVersion,this.cycle);
            } catch (RuntimeException failure) { throw integrity(); }
            if ((this.state==State.APPROVED || this.state==State.REJECTED) != (decisionEventId!=null)) throw integrity();
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof CurrentState)) return false;
            CurrentState s=(CurrentState)other;
            return recordId==s.recordId && contentVersion==s.contentVersion && decisionVersion==s.decisionVersion
                    && cycle==s.cycle && lastEventId==s.lastEventId && Objects.equals(patientId,s.patientId)
                    && Objects.equals(ownerId,s.ownerId) && Objects.equals(decisionEventId,s.decisionEventId)
                    && generation.equals(s.generation) && channel==s.channel && state==s.state;
        }
        @Override public int hashCode() {
            return Objects.hash(recordId,channel,generation,patientId,ownerId,contentVersion,decisionVersion,cycle,state,lastEventId,decisionEventId);
        }
    }
    private static final class Event {
        final long id,recordId,contentVersion,decisionVersion,cycle;
        final String generation; final Channel channel; final Operation operation; final State state;
        Event(Long id,Long recordId,String generation,String channel,String operation,Long before,Long contentVersion,
              Long decisionVersion,Long cycle,String state,Long actorId,String actorKind,String occurredAt) {
            this.id=MedicalReviewContentFingerprint.id(id);this.recordId=MedicalReviewContentFingerprint.id(recordId);
            this.contentVersion=MedicalReviewContentFingerprint.id(contentVersion);MedicalReviewContentFingerprint.id(actorId);
            if (decisionVersion==null || cycle==null || occurredAt==null) throw integrity();
            this.decisionVersion=decisionVersion;this.cycle=cycle;this.generation=generation;
            try {
                this.channel=Channel.valueOf(channel);this.operation=Operation.valueOf(operation);this.state=State.valueOf(state);
                requireEventFacts(generation,this.channel,this.operation,this.state,before,this.contentVersion,this.decisionVersion,this.cycle);
            } catch (RuntimeException failure) { throw integrity(); }
            if (!"OWNER".equals(actorKind) && !"ASSIGNED_DOCTOR".equals(actorKind)
                    && !"ADMIN".equals(actorKind) && !"DELEGATE".equals(actorKind)) throw integrity();
            if (this.channel==Channel.DOCTOR && (this.operation==Operation.APPROVE || this.operation==Operation.REJECT)
                    && !"ASSIGNED_DOCTOR".equals(actorKind) && !"ADMIN".equals(actorKind)) throw integrity();
            time(occurredAt);
            if (occurredAt.compareTo("1000-01-01T00:00:00.000000") < 0) throw integrity();
        }
    }
    private static final class Sample {
        final NativeBinding source; final List<CurrentState> states; final long watermark;
        Sample(NativeBinding source,List<CurrentState> states,long watermark) { this.source=source;this.states=states;this.watermark=watermark; }
        Lifecycle lifecycle() {
            String doctor=null,sourceGeneration=null;
            for (CurrentState state:states) { if (state.channel==Channel.DOCTOR) doctor=state.generation; else sourceGeneration=state.generation; }
            try { return MedicalReviewContracts.lifecycle(source.id,sourceGeneration,doctor,watermark); }
            catch (WorkflowException failure) { throw integrity(); }
        }
    }
}
