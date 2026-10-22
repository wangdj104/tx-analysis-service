package org.familyhealthcare.service.medicalreview;

import org.familyhealthcare.entity.MedicalRecord;
import org.familyhealthcare.service.workflow.WorkflowException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContracts.*;
import static org.familyhealthcare.service.medicalreview.MedicalReviewContentFingerprint.integrity;

/** Fixed completed-receipt point reads on the caller's preverified actual connection. */
final class MedicalReviewReceiptRepository {
    private static final String COMPLETED = "SELECT actor_id,record_id,channel,command_key,source_generation,request_hash,event_id,operation,"
            + "content_version,decision_version,cycle,state,actor_kind,"
            + "DATE_FORMAT(applied_at,'%Y-%m-%dT%H:%i:%s.%f') AS applied_at_text,applied_at IS NULL AS applied_at_null "
            + "FROM medical_review_command WHERE actor_id=? AND record_id=? AND channel=? AND command_key=? FOR UPDATE";
    private static final String EVENT = "SELECT id,record_id,source_generation,channel,operation,before_content_version,"
            + "content_version,decision_version,cycle,state,actor_id,actor_kind,"
            + "DATE_FORMAT(occurred_at,'%Y-%m-%dT%H:%i:%s.%f') AS occurred_at_text,occurred_at IS NULL AS occurred_at_null "
            + "FROM medical_review_event WHERE id=?";

    void lockCurrentKeys(Connection connection, long recordId, boolean parent9Ready) throws SQLException {
        if (parent9Ready) query(connection,"SELECT record_id FROM medical_record_correction_state WHERE record_id=? FOR UPDATE",r->{
            if (r.next() && (positive(r,"record_id")!=recordId || r.next())) throw integrity();
            return null;
        },recordId);
        query(connection,"SELECT record_id,channel FROM medical_review_state WHERE record_id=? ORDER BY channel LIMIT 3 FOR UPDATE",r->{
            String previous=null; int count=0;
            while (r.next()) {
                if (++count>2 || positive(r,"record_id")!=recordId) throw integrity();
                String channel=r.getString("channel");
                if ((!"DOCTOR".equals(channel) && !"SOURCE".equals(channel))
                        || (previous!=null && (!"DOCTOR".equals(previous) || !"SOURCE".equals(channel)))) throw integrity();
                previous=channel;
            }
            return null;
        },recordId);
    }
    Optional<MedicalReviewReceipt> findCompleted(Connection connection, Command command) throws SQLException {
        return completed(connection,command.getActorId(),command.getRecordId(),command.getChannel(),command.getCommandKey());
    }
    private Optional<MedicalReviewReceipt> completed(Connection connection,long actor,long record,Channel channel,String key) throws SQLException {
        return query(connection,COMPLETED,r->{
            if (!r.next()) return Optional.empty();
            MedicalReviewReceipt receipt=new MedicalReviewReceipt(number(r,"actor_id"),number(r,"record_id"),r.getString("channel"),
                    r.getString("command_key"),r.getString("source_generation"),r.getString("request_hash"),number(r,"event_id"),
                    r.getString("operation"),number(r,"content_version"),number(r,"decision_version"),number(r,"cycle"),
                    r.getString("state"),r.getString("actor_kind"),formatted(r,"applied_at"));
            if (r.next()) throw integrity();
            return Optional.of(receipt);
        },actor,record,channel.name(),key);
    }
    void requireEventMatch(Connection connection, Command command, MedicalReviewReceipt receipt) throws SQLException {
        receipt.requireCommand(command);
        requireEvent(connection,receipt);
    }
    /** Before-commit verification captures no Command, request text or canonical envelope. */
    void requireUnchangedCompleted(Connection connection, MedicalReviewReceipt expected) throws SQLException {
        Optional<MedicalReviewReceipt> actual=completed(connection,expected.actorId,expected.recordId,expected.channel,expected.commandKey());
        if (!actual.isPresent() || !expected.equals(actual.get())) throw integrity();
        requireEvent(connection,expected);
    }
    private void requireEvent(Connection connection,MedicalReviewReceipt receipt) throws SQLException {
        query(connection,EVENT,r->{
            if (!r.next()) throw integrity();
            long id=positive(r,"id"),record=positive(r,"record_id"),actor=positive(r,"actor_id");
            long content=positive(r,"content_version"),decision=positive(r,"decision_version"),cycle=positive(r,"cycle");
            Long before=number(r,"before_content_version"); String generation=r.getString("source_generation");
            String channel=r.getString("channel"),operation=r.getString("operation"),state=r.getString("state");
            try { requireEventFacts(generation,Channel.valueOf(channel),Operation.valueOf(operation),State.valueOf(state),before,content,decision,cycle); }
            catch (IllegalArgumentException | NullPointerException | WorkflowException failure) { throw integrity(); }
            if (id!=receipt.eventId || record!=receipt.recordId || actor!=receipt.actorId || content!=receipt.contentVersion
                    || decision!=receipt.decisionVersion || cycle!=receipt.cycle || !receipt.generation.equals(generation)
                    || !receipt.channel.name().equals(channel) || !receipt.operation.name().equals(operation)
                    || !receipt.state.name().equals(state) || !receipt.actorKind.equals(r.getString("actor_kind"))
                    || !receipt.appliedAt.equals(formatted(r,"occurred_at")) || r.next()) throw integrity();
            return null;
        },receipt.eventId);
    }
    void requireCurrentChannel(Connection connection,MedicalRecord source,Lifecycle lifecycle,MedicalReviewReceipt receipt) throws SQLException {
        query(connection,"SELECT record_id,channel,source_generation,content_version,decision_version,cycle,last_event_id "
                + "FROM medical_review_state WHERE record_id=? AND channel=?",r->{
            if (!r.next()) throw integrity();
            long record=positive(r,"record_id"),content=positive(r,"content_version"),decision=positive(r,"decision_version"),cycle=positive(r,"cycle"),head=positive(r,"last_event_id");
            String generation=r.getString("source_generation");
            if (record!=receipt.recordId || !Long.valueOf(record).equals(source.getId()) || record!=lifecycle.getRecordId()
                    || !receipt.channel.name().equals(r.getString("channel")) || !receipt.generation.equals(generation)
                    || !receipt.generation.equals(lifecycle.getCurrentGeneration()) || !Long.valueOf(content).equals(source.getContentVersion())
                    || receipt.contentVersion>content || decision<receipt.decisionVersion || cycle<receipt.cycle
                    || (decision==receipt.decisionVersion && head!=receipt.eventId) || r.next()) throw integrity();
            return null;
        },source.getId(),receipt.channel.name());
    }
    private interface Rows<T> { T read(ResultSet result) throws SQLException; }
    private static <T> T query(Connection connection,String sql,Rows<T> rows,Object...keys) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int i=0;i<keys.length;i++) statement.setObject(i+1,keys[i]);
            try (ResultSet result=statement.executeQuery()) { return rows.read(result); }
        }
    }
    private static Long number(ResultSet result,String column) throws SQLException {
        long value=result.getLong(column);return result.wasNull()?null:value;
    }
    private static long positive(ResultSet result,String column) throws SQLException { return MedicalReviewContentFingerprint.id(number(result,column)); }
    private static String formatted(ResultSet result,String column) throws SQLException {
        String value=result.getString(column+"_text");Long nil=number(result,column+"_null");
        if (value==null || nil==null || nil!=0) throw integrity();
        MedicalReviewContentFingerprint.time(value);return value;
    }
}
