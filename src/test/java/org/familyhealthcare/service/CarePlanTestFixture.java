package org.familyhealthcare.service;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.StreamUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Synthetic, in-process database and authenticated request context for care-plan tests. */
public final class CarePlanTestFixture implements AutoCloseable {
    public static final long OWNER = 7L, FAMILY = 8L, DOCTOR = 9L, NURSE = 10L, ADMIN = 11L;
    public static final long PATIENT = 1L, OTHER_PATIENT = 2L;
    private final JdbcDataSource dataSource = new JdbcDataSource();
    private final JdbcTemplate jdbc;
    private Instant now = Instant.parse("2026-10-03T05:00:00Z");

    public CarePlanTestFixture() throws IOException {
        dataSource.setURL("jdbc:h2:mem:care_plan_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        runH2(read("sql/init.sql"));
        String platform = read("sql/care_platform_upgrade_20260921.sql");
        runH2(platform.substring(0, platform.indexOf("SET @ddl")));
        for (long id : new long[]{OWNER, FAMILY, DOCTOR, NURSE, ADMIN}) {
            jdbc.update("INSERT INTO sys_user(id,username,password,real_name) VALUES(?,?,?,?)",
                    id, "synthetic-" + id, "not-a-real-password", "Synthetic User " + id);
        }
        jdbc.update("INSERT INTO patient(id,name,user_id) VALUES(1,'Synthetic Patient',7),(2,'Other Synthetic Patient',8)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?", OWNER, "patient");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?", FAMILY, "family");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?", DOCTOR, "doctor");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?", ADMIN, "admin");
        jdbc.update("INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by) VALUES(9,1,11)");
        // The legacy row must predate the additive migration; it must never become published.
        jdbc.update("INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions) VALUES(1,9,1,'Legacy internal plan','Synthetic legacy instruction')");
        migrate();
        as(OWNER);
    }

    public JdbcTemplate jdbc() { return jdbc; }
    public Instant now() { return now; }
    public void advance(Duration duration) { now = now.plus(duration); }

    public void as(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", userId);
        request.setAttribute("username", "synthetic-" + userId);
        List<String> roles = jdbc.queryForList("SELECT r.role_code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status=1 AND r.deleted=0", String.class, userId);
        request.setAttribute("roleCodes", roles);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    public void migrate() throws IOException {
        String path = "sql/care_plan_collaboration_20261003.sql";
        if (!new ClassPathResource(path).exists()) {
            throw new AssertionError("Care-plan collaboration migration is missing");
        }
        String sql = read(path);
        // Extract the real ALTER from MySQL's conditional PREPARE blocks, retaining its order.
        Pattern blocks = Pattern.compile("SET @ddl=.*?'(ALTER TABLE (?:''|[^'])*?)'\\)\\);\\s*PREPARE stmt FROM @ddl;\\s*EXECUTE stmt;\\s*DEALLOCATE PREPARE stmt;", Pattern.DOTALL);
        Matcher matcher = blocks.matcher(sql);
        StringBuffer translated = new StringBuffer();
        while (matcher.find()) {
            String ddl = matcher.group(1).replace("''", "'");
            if (ddl.contains(" ADD COLUMN ")) {
                ddl = ddl.replace(" ADD COLUMN ", " ADD COLUMN IF NOT EXISTS ");
            } else if (ddl.contains(" ADD CONSTRAINT ")) {
                String constraint = ddl.substring(ddl.indexOf(" ADD CONSTRAINT ") + 16).split("\\s+")[0].replace("`", "");
                int existing = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_name=?", Integer.class, constraint);
                if (existing > 0) ddl = "SELECT 1";
            } else {
                throw new AssertionError("Unrecognized migration compatibility block: " + ddl);
            }
            matcher.appendReplacement(translated, Matcher.quoteReplacement(ddl + ";"));
        }
        matcher.appendTail(translated);
        if (translated.indexOf("SET @ddl") >= 0) throw new AssertionError("Untranslated MySQL migration block");
        // H2 VARCHAR is case-sensitive by default; it has no MySQL column charset syntax.
        runH2(translated.toString().replace(" CHARACTER SET ascii COLLATE ascii_bin", ""));
    }

    private String read(String path) throws IOException {
        try (java.io.InputStream input = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
    }

    private void runH2(String sql) {
        // MySQL index names are table-local; H2 requires schema-wide index names.
        Matcher indexes = Pattern.compile("(?m)^(\\s*(?:UNIQUE )?KEY\\s+)`([^`]+)`").matcher(sql);
        StringBuffer renamed = new StringBuffer();
        int index = 0;
        while (indexes.find()) indexes.appendReplacement(renamed,
                Matcher.quoteReplacement(indexes.group(1) + "`" + indexes.group(2) + "_h2_" + index++ + "`"));
        indexes.appendTail(renamed);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new org.springframework.core.io.ByteArrayResource(renamed.toString().getBytes(StandardCharsets.UTF_8)));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }

    @Override public void close() throws java.sql.SQLException {
        RequestContextHolder.resetRequestAttributes();
        // JdbcTemplate checks warnings after execution; SHUTDOWN intentionally closes its connection.
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
