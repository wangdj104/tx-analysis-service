package org.familyhealthcare.service.workflow;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.*;

/** Bounded MySQL5.7 row-lock wait, with explicit pooled-session restoration before transaction completion. */
public final class NotificationClaim {
    private final JdbcTemplate jdbc;
    private final String clause, restoreSql;
    private NotificationClaim(JdbcTemplate jdbc, String clause, String restoreSql) {
        this.jdbc = jdbc; this.clause = clause; this.restoreSql = restoreSql;
    }
    public String getClause() { return clause; }
    public static NotificationClaim acquire(JdbcTemplate jdbc) {
        WorkflowData.mutationTransaction(jdbc);
        return jdbc.execute((ConnectionCallback<NotificationClaim>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            String database = metadata.getDatabaseProductName();
            if ("MySQL".equals(database)) {
                int major = metadata.getDatabaseMajorVersion(), minor = metadata.getDatabaseMinorVersion();
                if (major >= 8 && minor >= 0) return new NotificationClaim(jdbc, "FOR UPDATE SKIP LOCKED", null);
                if (major == 5 && minor == 7) {
                    int previous;
                    try (Statement statement = connection.createStatement(); ResultSet value = statement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                        value.next(); previous = value.getInt(1);
                    }
                    try (Statement statement = connection.createStatement()) { statement.execute("SET SESSION innodb_lock_wait_timeout = 1"); }
                    return new NotificationClaim(jdbc, "FOR UPDATE", "SET SESSION innodb_lock_wait_timeout = " + previous);
                }
                throw new IllegalStateException("Unsupported notification MySQL version.");
            }
            // H2 is test-only; NOWAIT alone still waits, so also bound the actual test session.
            if ("H2".equals(database)) {
                int previous;
                try (Statement statement = connection.createStatement(); ResultSet value = statement.executeQuery("CALL LOCK_TIMEOUT()")) {
                    value.next(); previous = value.getInt(1);
                }
                try (Statement statement = connection.createStatement()) { statement.execute("SET LOCK_TIMEOUT 1"); }
                return new NotificationClaim(jdbc, "FOR UPDATE NOWAIT", "SET LOCK_TIMEOUT " + previous);
            }
            throw new IllegalStateException("Unsupported notification database.");
        });
    }
    public void restore() {
        if (restoreSql != null) jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) { statement.execute(restoreSql); }
            catch (SQLException restoreFailure) {
                // Pools do not reset arbitrary session variables. Abort the physical connection, not its handle.
                try { connection.abort(Runnable::run); }
                catch (SQLException | RuntimeException abortFailure) { restoreFailure.addSuppressed(abortFailure); }
                throw new SessionRestoreException(restoreFailure);
            }
            return null;
        });
    }
    /** Distinguish failed session cleanup from an ordinary busy claim. */
    public static final class SessionRestoreException extends IllegalStateException {
        public SessionRestoreException(SQLException cause) { super("Cannot restore notification claim session timeout.", cause); }
    }
}
