package org.familyhealthcare.service.commongoal;

import org.familyhealthcare.service.workflow.WorkflowData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.familyhealthcare.service.commongoal.CommonCareGoalContracts.*;

/** 只读本域三表；不接触数值目标、临床计划、病历或授权写入。 */
@Repository
public class CommonCareGoalRepository {
    static final String GOAL_COLUMNS = "id,patient_id,creator_id,title,goal_text,progress_criterion,responsibility,review_date,nominee_id,state,version,cycle,accepted_by,accepted_at,created_at,updated_at";
    private final JdbcTemplate jdbc;
    public CommonCareGoalRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    /** 仅用于选择锁路径，不能作为成功或授权证明；不会读取回执或正文。 */
    public boolean hasReservation(long actor, long patient, String key) {
        id(patient); // 先前已核验请求患者；仅探测本actor的全域唯一key是否已占用。
        return !query("SELECT 1 FROM common_care_goal_command WHERE actor_id=? AND command_key=?", (r,n)->r.getInt(1), id(actor), commandKey(key)).isEmpty();
    }

    /** 锁前仅发现账号，不读取正文；该集合必须在有序authority锁及目标锁之后重新核对。 */
    public Set<Long> discoverSubjects(long patient, long goal) {
        List<Long> rows = query("SELECT nominee_id FROM common_care_goal WHERE patient_id=? AND id=?", (r,n) -> WorkflowData.nullableId(r,"nominee_id"), id(patient), id(goal));
        Set<Long> result = new TreeSet<>(); for (Long user : rows) if (user != null) result.add(id(user)); return result;
    }
    public Goal find(long patient, long goal, boolean lock) {
        if (lock) WorkflowData.mutationTransaction(jdbc);
        List<Goal> rows = query("SELECT " + GOAL_COLUMNS + " FROM common_care_goal WHERE patient_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), CommonCareGoalRepository::goal, id(patient), id(goal));
        return rows.isEmpty() ? null : rows.get(0);
    }
    public Goal create(long patient, long actor, Content content, Instant now) {
        WorkflowData.mutationTransaction(jdbc); id(patient); id(actor); Objects.requireNonNull(content); Objects.requireNonNull(now);
        String sql = "INSERT INTO common_care_goal(patient_id,creator_id,title,goal_text,progress_criterion,responsibility,review_date,nominee_id,state,version,cycle,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'PROPOSED',1,1,?,?)";
        KeyHolder holder = new GeneratedKeyHolder();
        one(jdbc.update(c -> statement(c, sql, Statement.RETURN_GENERATED_KEYS, patient, actor, content.getTitle(), content.getGoalText(), content.getProgressCriterion(), content.getResponsibility(), content.getReviewDate(), content.getNomineeId(), now, now), holder));
        Number generated = holder.getKey(); if (generated == null) throw integrity();
        return new Goal(id(generated), patient, actor, content, State.PROPOSED, 1, 1, null, null, now, now);
    }
    /** 先追加事件，再推进状态；SQL守卫和提交前核对共同拒绝漏事件/漏回执。 */
    public void save(Goal before, Goal after) {
        WorkflowData.mutationTransaction(jdbc);
        if (before.getId() != after.getId() || before.getPatientId() != after.getPatientId() || before.getCreatorId() != after.getCreatorId()
                || !before.getCreatedAt().equals(after.getCreatedAt()) || next(before.getVersion()) != after.getVersion()) throw integrity();
        Content c = after.getContent();
        one(update("UPDATE common_care_goal SET title=?,goal_text=?,progress_criterion=?,responsibility=?,review_date=?,nominee_id=?,state=?,version=?,cycle=?,accepted_by=?,accepted_at=?,updated_at=? WHERE patient_id=? AND id=? AND version=?",
                c.getTitle(), c.getGoalText(), c.getProgressCriterion(), c.getResponsibility(), c.getReviewDate(), c.getNomineeId(), after.getState().name(), after.getVersion(), after.getCycle(), after.getAcceptedBy(), after.getAcceptedAt(), after.getUpdatedAt(), before.getPatientId(), before.getId(), before.getVersion()));
    }
    public void append(Command command, long actor, Goal before, Goal after, Instant now) {
        WorkflowData.mutationTransaction(jdbc);
        if (command.getOperation() == Operation.REVISE && before != null && before.getContent().equals(after.getContent()))
            throw new org.familyhealthcare.service.workflow.WorkflowException(409, "COMMON_GOAL_NO_CHANGE", "The proposed content has not changed.");
        String json = event(command, actor, before, after, now);
        one(update("INSERT INTO common_care_goal_event(patient_id,goal_id,sequence,cycle,operation,actor_id,command_key,event_json,recorded_at) VALUES(?,?,?,?,?,?,?,?,?)",
                command.getPatientId(), after.getId(), after.getVersion(), after.getCycle(), command.getOperation().name(), id(actor), command.getCommandKey(), json, now));
    }
    public Map<String,Object> latestEvent(long patient, long goal, long cycle, Operation operation) {
        if (operation != Operation.ADD_PROGRESS && operation != Operation.ADD_FEEDBACK) throw invalid();
        List<Map<String,Object>> rows = query("SELECT OCTET_LENGTH(event_json) AS event_bytes,CASE WHEN OCTET_LENGTH(event_json)<=61440 THEN event_json ELSE NULL END AS event_json FROM common_care_goal_event WHERE goal_id=? AND cycle=? AND operation=? AND patient_id=? ORDER BY sequence DESC LIMIT 1",
                (r,n) -> readEvent(r), id(goal), id(cycle), operation.name(), id(patient));
        return rows.isEmpty() ? null : rows.get(0);
    }
    /** 缺少回执只表示尚未确认；调用者不能据此换key重做。权限由外层当前READ核验。 */
    public Map<String,Object> receipt(long actor, long patient, String key) {
        List<Map<String,Object>> rows = query("SELECT goal_id,expected_version,operation,OCTET_LENGTH(result_json) AS receipt_bytes,CASE WHEN OCTET_LENGTH(result_json)<=512 THEN result_json ELSE NULL END AS result_json FROM common_care_goal_command WHERE actor_id=? AND command_key=? AND patient_id=?",
                (r,n) -> readReceipt(r), id(actor), commandKey(key), id(patient));
        return rows.isEmpty() ? null : rows.get(0);
    }
    static Map<String,Object> readReceipt(ResultSet r) throws SQLException {
        if (r.getLong("receipt_bytes") > RECEIPT_BYTES || r.getString("result_json") == null) throw integrity();
        Map<String,Object> result = storedReceipt(r.getString("result_json"));
        if (!Objects.equals(result.get("goalId"), WorkflowData.nullableId(r,"goal_id"))
                || !Long.valueOf(next(r.getLong("expected_version"))).equals(result.get("version")) || !result.get("operation").equals(r.getString("operation"))) throw integrity();
        return result;
    }
    static Map<String,Object> readEvent(ResultSet r) throws SQLException {
        if (r.getLong("event_bytes") > EVENT_BYTES || r.getString("event_json") == null) throw integrity();
        return storedEvent(r.getString("event_json"));
    }
    static Goal goal(ResultSet r, int row) throws SQLException {
        return new Goal(r.getLong("id"), r.getLong("patient_id"), r.getLong("creator_id"),
                new Content(r.getString("title"),r.getString("goal_text"),r.getString("progress_criterion"),r.getString("responsibility"),r.getString("review_date"),WorkflowData.nullableId(r,"nominee_id")),
                choice(r.getString("state"), State.class),r.getLong("version"),r.getLong("cycle"),WorkflowData.nullableId(r,"accepted_by"),
                time(r,"accepted_at"),time(r,"created_at"),time(r,"updated_at"));
    }
    private static Instant time(ResultSet r, String field) throws SQLException { String value = WorkflowData.time(r, field); return value == null ? null : Instant.parse(value); }
    static <T> List<T> query(JdbcTemplate jdbc, String sql, RowMapper<T> mapper, Object... args) { return jdbc.query(c -> statement(c, sql, Statement.NO_GENERATED_KEYS, args), mapper); }
    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) { return query(jdbc, sql, mapper, args); }
    static int update(JdbcTemplate jdbc, String sql, Object... args) { return jdbc.update(c -> statement(c, sql, Statement.NO_GENERATED_KEYS, args)); }
    private int update(String sql, Object... args) { return update(jdbc, sql, args); }
    private static PreparedStatement statement(Connection connection, String sql, int generatedKeys, Object... args) throws SQLException {
        PreparedStatement s = connection.prepareStatement(sql, generatedKeys); s.setQueryTimeout(5);
        for (int i=0; i<args.length; i++) if (args[i] instanceof Instant) WorkflowData.time(s, i+1, (Instant)args[i]); else s.setObject(i+1, args[i]);
        return s;
    }
    static void one(int count) { if (count != 1) throw integrity(); }

    /** NEW路径提交前只点读该目标/事件/回执；重放绝不拿当前状态否定旧成功。 */
    static void requireCompletedTransition(JdbcTemplate jdbc, long actor, Command command, Map<String,Object> receipt) {
        WorkflowData.mutationTransaction(jdbc); if (receipt == null) throw integrity();
        long goal = id(receipt.get("goalId")); CommonCareGoalRepository repository = new CommonCareGoalRepository(jdbc);
        Goal current = repository.find(command.getPatientId(), goal, false);
        List<Map<String,Object>> events = query(jdbc, "SELECT OCTET_LENGTH(event_json) AS event_bytes,CASE WHEN OCTET_LENGTH(event_json)<=61440 THEN event_json ELSE NULL END AS event_json FROM common_care_goal_event WHERE goal_id=? AND sequence=? AND patient_id=? AND actor_id=? AND command_key=? AND operation=?",
                (r,n) -> readEvent(r), goal, next(command.getExpectedVersion()), command.getPatientId(), actor, command.getCommandKey(), command.getOperation().name());
        List<String> hashes = query(jdbc, "SELECT payload_hash FROM common_care_goal_command WHERE actor_id=? AND command_key=? AND patient_id=? AND goal_id=?", (r,n)->r.getString(1), actor, command.getCommandKey(), command.getPatientId(), goal);
        if (current == null || events.size()!=1 || hashes.size()!=1 || !command.payloadHash().equals(hashes.get(0))
                || !current.asMap().equals(events.get(0).get("after")) || !CommonCareGoalContracts.receipt(current, command.getOperation()).equals(receipt)
                || !receipt.equals(repository.receipt(actor, command.getPatientId(), command.getCommandKey()))) throw integrity();
        Map<String,Object> persisted = events.get(0);
        Goal previous = persisted.get("before") == null ? null : CommonCareGoalContracts.goal(object(persisted.get("before")));
        String expectedEvent = event(command, actor, previous, current, Instant.parse((String)persisted.get("recordedAt")));
        if (!WorkflowData.json(persisted).equals(expectedEvent)
                || (command.getContent()!=null && !command.getContent().equals(current.getContent()))) throw integrity();
    }
}
