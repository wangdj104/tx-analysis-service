import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One-off, auditable SQL migration runner. Credentials are read only from environment variables. */
public final class RunSqlMigration {
    private static final List<String> BACKUP_TABLES = Arrays.asList("care_member", "patient_clinical", "operation_audit_log", "platform_branding", "sys_menu", "sys_role_menu");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: RunSqlMigration <migration.sql> <backup-directory>");
        String host = required("CARE_DB_HOST"), port = required("CARE_DB_PORT"), database = required("CARE_DB_NAME");
        String user = required("CARE_DB_USER"), password = System.getenv("CARE_DB_PASSWORD");
        if (password == null || password.isBlank()) { if (System.console() == null) throw new IllegalStateException("CARE_DB_PASSWORD is missing and no secure console is available."); char[] entered = System.console().readPassword("Database password: "); password = entered == null ? null : new String(entered); }
        if (password == null || password.isBlank()) throw new IllegalStateException("Database password is required.");
        Path migration = Path.of(args[0]).toAbsolutePath().normalize();
        Path backupDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(backupDirectory);
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai&allowMultiQueries=false";
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            Path backup = backupDirectory.resolve("txdata-care-platform-before-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".sql");
            backup(connection, database, backup);
            execute(connection, Files.readString(migration, StandardCharsets.UTF_8));
            verify(connection);
            System.out.println("Backup: " + backup);
            System.out.println("Migration applied and verified.");
        }
    }

    private static void backup(Connection connection, String database, Path target) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            out.write("-- Pre-migration backup for affected Chengxin Health tables\nSET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\n");
            for (String table : BACKUP_TABLES) {
                if (!exists(connection, database, table)) continue;
                try (Statement statement = connection.createStatement(); ResultSet create = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                    create.next(); out.write("\nDROP TABLE IF EXISTS `" + table + "`;\n" + create.getString(2) + ";\n");
                }
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM `" + table + "`")) {
                    ResultSetMetaData meta = rows.getMetaData(); int count = meta.getColumnCount();
                    while (rows.next()) {
                        out.write("INSERT INTO `" + table + "` VALUES(");
                        for (int i = 1; i <= count; i++) { if (i > 1) out.write(','); Object value = rows.getObject(i); out.write(value == null ? "NULL" : quote(value.toString())); }
                        out.write(");\n");
                    }
                }
            }
            out.write("SET FOREIGN_KEY_CHECKS=1;\n");
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        boolean autoCommit = connection.getAutoCommit(); connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String command : split(sql)) if (!command.isBlank()) statement.execute(command);
            connection.commit();
        } catch (Exception error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(autoCommit); }
    }

    private static List<String> split(String sql) {
        List<String> statements = new ArrayList<>(); StringBuilder current = new StringBuilder(); boolean single = false, backtick = false, lineComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i), next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) { if (c == '\n') { lineComment = false; current.append(c); } continue; }
            if (!single && !backtick && c == '-' && next == '-') { lineComment = true; i++; continue; }
            if (c == '\'' && !backtick && (i == 0 || sql.charAt(i - 1) != '\\')) single = !single;
            if (c == '`' && !single) backtick = !backtick;
            if (c == ';' && !single && !backtick) { statements.add(current.toString().trim()); current.setLength(0); } else current.append(c);
        }
        if (!current.toString().isBlank()) statements.add(current.toString().trim()); return statements;
    }

    private static void verify(Connection connection) throws Exception {
        for (String table : Arrays.asList("health_measurement", "care_access_grant", "care_appointment", "consultation", "treatment_plan", "emergency_event", "mental_assessment", "notification_delivery_log", "platform_branding")) {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) { result.next(); }
        }
        for (String check : Arrays.asList("SHOW COLUMNS FROM care_member LIKE 'access_level'", "SHOW COLUMNS FROM patient_clinical LIKE 'blood_type'", "SHOW COLUMNS FROM operation_audit_log LIKE 'action_type'")) {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(check)) { if (!result.next()) throw new IllegalStateException("Verification failed: " + check); }
        }
    }

    private static boolean exists(Connection connection, String database, String table) throws Exception {
        try (java.sql.PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?")) { statement.setString(1, database); statement.setString(2, table); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1) > 0; } }
    }
    private static String quote(String value) { return "'" + value.replace("\\", "\\\\").replace("'", "''").replace("\u0000", "") + "'"; }
    private static String required(String key) { String value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + key); return value; }
}
