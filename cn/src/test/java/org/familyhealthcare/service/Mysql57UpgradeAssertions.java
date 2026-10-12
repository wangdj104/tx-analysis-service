package org.familyhealthcare.service;

import org.springframework.core.io.ClassPathResource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only called after CarePlanMysqlIntegrationTest verifies its disposable synthetic server/schema. */
final class Mysql57UpgradeAssertions {
    static final String SCRIPT = "sql/mysql57_schema_upgrade_20261012.sql";
    private static final String[] TABLES = {"care_plan_revision", "care_plan_action", "care_plan_event",
            "care_plan_evidence", "care_nurse_assignment", "care_plan_notification", "care_plan_command"};
    private Mysql57UpgradeAssertions() { }

    static void apply(Connection c) throws Exception { applyScript(c, SCRIPT); }

    private static void applyScript(Connection c, String path) throws Exception {
        for (String sql : statements(readScript(path))) execute(c, sql);
    }

    private static String readScript(String path) throws Exception {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192]; int read;
            while ((read = input.read(bytes)) != -1) output.write(bytes, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** MySQL DELIMITER is a client directive, never sent to JDBC as SQL. */
    static List<String> statements(String source) {
        List<String> result = new ArrayList<>();
        String delimiter = ";";
        StringBuilder statement = new StringBuilder();
        char quote = 0;
        boolean lineComment = false, blockComment = false, hasSql = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (quote == 0 && !lineComment && !blockComment && (i == 0 || source.charAt(i - 1) == '\n')) {
                int end = source.indexOf('\n', i);
                if (end < 0) end = source.length();
                String line = source.substring(i, end).trim();
                if (line.matches("(?i)DELIMITER\\s+\\S+")) {
                    if (hasSql) throw new IllegalArgumentException("DELIMITER encountered before SQL terminator");
                    delimiter = line.split("\\s+", 2)[1];
                    statement.setLength(0); i = end; continue;
                }
            }
            if (lineComment) {
                statement.append(ch);
                if (ch == '\n') lineComment = false;
            } else if (blockComment) {
                statement.append(ch);
                if (ch == '*' && next == '/') { statement.append(next); i++; blockComment = false; }
            } else if (quote != 0) {
                statement.append(ch);
                if (ch == '\\' && next != 0) { statement.append(next); i++; }
                else if (ch == quote) {
                    if (next == quote) { statement.append(next); i++; }
                    else quote = 0;
                }
            } else if (source.startsWith(delimiter, i)) {
                if (hasSql) result.add(statement.toString().trim());
                statement.setLength(0); hasSql = false; i += delimiter.length() - 1;
            } else if (ch == '#' || (ch == '-' && next == '-' &&
                    (i + 2 == source.length() || Character.isWhitespace(source.charAt(i + 2))))) {
                lineComment = true; statement.append(ch);
            } else if (ch == '/' && next == '*') {
                blockComment = true; statement.append(ch).append(next); i++;
            } else {
                statement.append(ch);
                if (!Character.isWhitespace(ch)) hasSql = true;
                if (ch == '\'' || ch == '"' || ch == '`') quote = ch;
            }
        }
        if (quote != 0 || blockComment) throw new IllegalArgumentException("Unterminated SQL literal/comment");
        if (hasSql) result.add(statement.toString().trim());
        return result;
    }

    /** This deliberately downgrades only synthetic test structures and applies the migration itself. */
    static void verifyLegacyUpgrade(Connection c) throws Exception {
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_revision'"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM patient WHERE id BETWEEN 6750 AND 6759"));
        assertEquals("0", scalar(c, "SELECT COUNT(*) FROM medication_stock WHERE id BETWEEN 6750 AND 6759"));
        execute(c, "ALTER TABLE patient DROP INDEX uk_user_patient_name, ADD UNIQUE KEY uk_name(name)");
        execute(c, "ALTER TABLE medication_stock DROP INDEX uk_medication_stock, ADD UNIQUE KEY uk_medication_stock(medication_id), ALTER COLUMN unit SET DEFAULT '片'");
        execute(c, "ALTER TABLE alert_record DROP INDEX idx_alert_dedupe");
        execute(c, "ALTER TABLE medication_intake DROP INDEX idx_intake_patient_schedule");
        execute(c, "ALTER TABLE dialysis_schedule DROP INDEX idx_schedule_patient_date");
        execute(c, "ALTER TABLE health_event DROP INDEX idx_event_patient_date, ADD KEY idx_event_patient_date(patient_id,event_date)");
        execute(c, "ALTER TABLE medical_record_attachment MODIFY COLUMN file_path VARCHAR(500) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'Synthetic retained 路径'");
        execute(c, "CREATE TABLE cp57_synthetic_extra(id BIGINT PRIMARY KEY, note VARCHAR(100)) ENGINE=InnoDB");
        execute(c, "INSERT INTO cp57_synthetic_extra VALUES(6750,'Synthetic unrelated extra table')");
        execute(c, "INSERT INTO patient(id,user_id,name) VALUES(6750,6750,'Synthetic migration retained patient')");
        execute(c, "INSERT INTO medication(id,patient_id,user_id,drug_name) VALUES(6750,6750,6750,'Synthetic migration medication')");
        execute(c, "INSERT INTO medication_stock(id,patient_id,medication_id,quantity) VALUES(6750,6750,6750,12.500)");
        execute(c, "INSERT INTO medical_record(id,patient_name,patient_id,user_id,record_date,record_type,remark) VALUES(6750,'Synthetic migration patient',6750,6750,'2026-10-10','OTHER','Synthetic original medical text')");
        execute(c, "INSERT INTO medical_record_attachment(id,record_id,file_path,file_content) VALUES(6750,6750,'synthetic/retained.txt','c3ludGhldGlj')");
        applyScript(c, "sql/mysql57_preflight_20261012.sql");
        Map<String,List<String>> original = data(c);
        List<String> attachmentEncoding = rows(c, "SELECT character_set_name,collation_name,column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record_attachment' AND column_name='file_path'");

        // An unrelated same-name routine must not be overwritten by migration management DDL.
        execute(c, "CREATE PROCEDURE cp57_upgrade_preflight() SELECT 'Synthetic unrelated routine'");
        List<String> unrelated = rows(c,"SELECT routine_definition FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name='cp57_upgrade_preflight'");
        assertThrows(SQLException.class,()->apply(c));
        assertEquals(unrelated,rows(c,"SELECT routine_definition FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name='cp57_upgrade_preflight'"));
        execute(c,"DROP PROCEDURE cp57_upgrade_preflight");
        // Missing prerequisite fails before procedure creation or table changes.
        execute(c, "RENAME TABLE care_access_grant TO cp57_synthetic_grant");
        assertRejectedWithoutTableMutation(c, 1146);
        execute(c, "RENAME TABLE cp57_synthetic_grant TO care_access_grant");
        // Unknown column or index definitions are not silently treated as already upgraded.
        execute(c, "ALTER TABLE doctor_care_plan ADD COLUMN workflow_version VARCHAR(20) NULL");
        assertRejectedWithoutTableMutation(c, 1644);
        execute(c, "ALTER TABLE doctor_care_plan DROP COLUMN workflow_version");
        execute(c, "ALTER TABLE alert_record ADD KEY idx_alert_dedupe(status)");
        assertRejectedWithoutTableMutation(c, 1644);
        execute(c, "ALTER TABLE alert_record DROP INDEX idx_alert_dedupe");
        // Missing/ambiguous seed identities must not grant permission to unknown content.
        execute(c,"UPDATE sys_role SET role_code='cp57_synthetic_admin' WHERE role_code='admin'");
        assertRejectedWithoutTableMutation(c,1644);
        execute(c,"UPDATE sys_role SET role_code='admin' WHERE role_code='cp57_synthetic_admin'");
        execute(c,"UPDATE sys_menu SET menu_code='cp57_synthetic_parent' WHERE id=8");
        assertRejectedWithoutTableMutation(c,1644);
        execute(c,"UPDATE sys_menu SET menu_code='patient-profile' WHERE id=8");
        assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sys_menu WHERE menu_code='care-plan-nursing'"));
        execute(c,"INSERT INTO sys_menu(id,parent_id,menu_name,menu_code,menu_path,permission,menu_type,status) VALUES(6759,0,'Synthetic unknown menu','care-plan-nursing','/unknown','synthetic:unknown',1,1)");
        assertRejectedWithoutTableMutation(c,1644);
        execute(c,"DELETE FROM sys_menu WHERE id=6759");
        // NULL ownership and duplicates must stop without deleting or reassigning a patient.
        execute(c, "INSERT INTO patient(id,user_id,name) VALUES(6751,NULL,'Synthetic missing owner')");
        assertRejectedWithoutTableMutation(c, 1644);
        execute(c, "DELETE FROM patient WHERE id=6751");
        execute(c, "ALTER TABLE patient DROP INDEX uk_name");
        execute(c, "INSERT INTO patient(id,user_id,name) VALUES(6751,6750,'Synthetic migration retained patient')");
        assertRejectedWithoutTableMutation(c, 1644);
        execute(c, "DELETE FROM patient WHERE id=6751");
        execute(c, "ALTER TABLE patient ADD UNIQUE KEY uk_name(name)");
        execute(c, "ALTER TABLE medication_stock DROP INDEX uk_medication_stock");
        execute(c, "INSERT INTO medication_stock(id,patient_id,medication_id,quantity) VALUES(6751,6750,6750,1)");
        assertRejectedWithoutTableMutation(c, 1644);
        execute(c, "DELETE FROM medication_stock WHERE id=6751");
        execute(c, "ALTER TABLE medication_stock ADD UNIQUE KEY uk_medication_stock(medication_id)");
        execute(c, "UPDATE medication SET patient_id=6751 WHERE id=6750");
        assertRejectedWithoutTableMutation(c, 1644);
        execute(c, "UPDATE medication SET patient_id=6750 WHERE id=6750");

        apply(c);
        applyScript(c, "sql/mysql57_postcheck_20261012.sql");
        assertSchema(c);
        assertEquals(original, data(c), "All original data and extra tables survive");
        assertEquals(attachmentEncoding, rows(c, "SELECT character_set_name,collation_name,column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record_attachment' AND column_name='file_path'"));
        assertEquals("片", scalar(c, "SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medication_stock' AND column_name='unit'"));
        List<String> metadata = metadata(c);
        apply(c);
        assertEquals(metadata, metadata(c), "Repeat migration preserves column/index/FK/trigger definitions");
        assertEquals(original, data(c));
        for (String table : TABLES) assertEquals("0", scalar(c, "SELECT COUNT(*) FROM " + table));

        // Scoped names accept another owner and reject duplicates of the same owner.
        execute(c, "INSERT INTO patient(id,user_id,name) VALUES(6751,6751,'Synthetic migration retained patient')");
        error(c, 1062, "INSERT INTO patient(id,user_id,name) VALUES(6752,6750,'Synthetic migration retained patient')");
        // Native composite unique behavior, without invoking any medication business service.
        execute(c, "INSERT INTO medication_stock(id,patient_id,medication_id,quantity) VALUES(6751,6751,6750,1)");
        error(c, 1062, "INSERT INTO medication_stock(id,patient_id,medication_id,quantity) VALUES(6752,6750,6750,1)");
        execute(c, "DELETE FROM medication_stock WHERE id=6751");
        execute(c, "DELETE FROM patient WHERE id=6751");
        execute(c, "INSERT INTO medical_record_attachment(id,record_id,file_path,file_content) VALUES(6751,6750,NULL,'c3ludGhldGlj')");
        assertEquals("c3ludGhldGlj", scalar(c, "SELECT file_content FROM medical_record_attachment WHERE id=6751 AND file_path IS NULL"));
    }

    /** Run after native CHECK tests on 8.0, because these triggers intentionally also run there. */
    static void verify(Connection c) throws Exception {
        Map<String,List<String>> before = allData(c);
        applyScript(c, "sql/mysql57_preflight_20261012.sql");
        apply(c); assertSchema(c);
        applyScript(c, "sql/mysql57_postcheck_20261012.sql");
        assertEquals(before, allData(c));
        List<String> definitions = metadata(c);
        apply(c);
        assertEquals(definitions, metadata(c));
        assertEquals(before, allData(c));
        String[] invalid = {
            "UPDATE care_plan_revision SET revision_no=0 WHERE id=(SELECT x.id FROM (SELECT MIN(id) id FROM care_plan_revision) x)",
            "UPDATE care_plan_action SET ordinal=51 WHERE id=(SELECT x.id FROM (SELECT MIN(id) id FROM care_plan_action) x)",
            "UPDATE care_plan_action SET lock_version=-1 WHERE id=(SELECT x.id FROM (SELECT MIN(id) id FROM care_plan_action) x)",
            "UPDATE care_plan_notification SET attempt_count=4 WHERE id=(SELECT x.id FROM (SELECT MIN(id) id FROM care_plan_notification) x)",
            "UPDATE care_plan_notification SET dispatch_key='' WHERE id=(SELECT x.id FROM (SELECT MIN(id) id FROM care_plan_notification) x)",
            "UPDATE care_plan_command SET expected_version=-1 WHERE id=(SELECT x.id FROM (SELECT MIN(id) id FROM care_plan_command) x)"};
        for (String table : new String[]{"care_plan_revision","care_plan_action","care_plan_notification","care_plan_command"})
            assertNotEquals("0", scalar(c,"SELECT COUNT(*) FROM "+table), "Native fixtures must exercise each trigger table");
        for (String statement : invalid) error(c,1644,statement);
        String[] invalidInserts = {
            "INSERT INTO care_plan_revision(plan_id,revision_no,title,instructions,plan_type,draft_json,created_by,created_at,updated_at) SELECT plan_id,0,title,instructions,plan_type,draft_json,created_by,created_at,updated_at FROM care_plan_revision LIMIT 1",
            "INSERT INTO care_plan_action(plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,lock_version,created_at,updated_at) SELECT plan_id,revision_id,patient_id,0,instruction,due_at,assigned_user_id,0,created_at,updated_at FROM care_plan_action LIMIT 1",
            "INSERT INTO care_plan_action(plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,lock_version,created_at,updated_at) SELECT plan_id,revision_id,patient_id,50,instruction,due_at,assigned_user_id,-1,created_at,updated_at FROM care_plan_action LIMIT 1",
            "INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,attempt_count,created_at,updated_at) SELECT event_id,patient_id,recipient_user_id,'cp57-synthetic-invalid-attempt',4,created_at,updated_at FROM care_plan_notification LIMIT 1",
            "INSERT INTO care_plan_notification(event_id,patient_id,recipient_user_id,dispatch_key,attempt_count,created_at,updated_at) SELECT event_id,patient_id,recipient_user_id,'',0,created_at,updated_at FROM care_plan_notification LIMIT 1",
            "INSERT INTO care_plan_command(actor_id,command_key,plan_id,expected_version,payload_hash,created_at) SELECT actor_id,'cp57-synthetic-invalid-version',plan_id,-1,payload_hash,created_at FROM care_plan_command LIMIT 1"};
        for (String statement : invalidInserts) error(c,1644,statement);

        assertEquals(before,allData(c));
        // Validate ALL owned trigger definitions before dropping ANY of them.
        String owned = scalar(c,"SELECT action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_command_bu'");
        execute(c,"DROP TRIGGER cp57_command_bu");
        execute(c,"CREATE TRIGGER cp57_command_bu BEFORE UPDATE ON care_plan_command FOR EACH ROW SET NEW.expected_version=NEW.expected_version");
        List<String> triggers = triggerDefinitions(c);
        assertRejectedWithoutTableMutation(c,1644);
        assertEquals(triggers,triggerDefinitions(c),"Unknown owned trigger is preserved, including earlier known triggers");
        execute(c,"DROP TRIGGER cp57_command_bu");
        execute(c,"CREATE TRIGGER cp57_command_bu BEFORE UPDATE ON care_plan_command FOR EACH ROW "+owned);
        apply(c);
    }

    private static void assertSchema(Connection c) throws SQLException {
        assertEquals("7",scalar(c,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('"+String.join("','",TABLES)+"')"));
        assertEquals("9",scalar(c,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name IN ('workflow_version','lifecycle','current_revision_id','draft_revision_id','legacy_source_id','lock_version','closed_at','cancelled_at','cancel_reason')"));
        assertEquals("3",scalar(c,"SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name='doctor_care_plan' AND constraint_name IN ('fk_doctor_plan_current_revision','fk_doctor_plan_draft_revision','fk_doctor_plan_legacy_source')"));
        assertEquals(8,triggerDefinitions(c).size());
        key(c,"patient","uk_user_patient_name","user_id,name",0);
        assertEquals("0",scalar(c,"SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='uk_name'"));
        key(c,"medication_stock","uk_medication_stock","patient_id,medication_id",0);
        key(c,"alert_record","idx_alert_dedupe","patient_id,dedupe_key,status",1);
        key(c,"medication_intake","idx_intake_patient_schedule","patient_id,scheduled_at,status",1);
        key(c,"dialysis_schedule","idx_schedule_patient_date","patient_id,schedule_date,status",1);
        key(c,"health_event","idx_event_patient_date","patient_id,event_date,event_time",1);
        assertEquals("YES",scalar(c,"SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record_attachment' AND column_name='file_path'"));
        assertEquals("0",scalar(c,"SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name='cp57_upgrade_preflight'"));
    }
    private static void key(Connection c,String table,String name,String columns,int nonUnique) throws SQLException {
        assertEquals(columns+":"+nonUnique+":0",scalar(c,"SELECT CONCAT(GROUP_CONCAT(column_name ORDER BY seq_in_index),':',MIN(non_unique),':',SUM(sub_part IS NOT NULL)) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='"+table+"' AND index_name='"+name+"'"));
    }
    private static void assertRejectedWithoutTableMutation(Connection c,int code) throws Exception {
        Map<String,List<String>> before = allData(c); List<String> definitions=metadata(c);
        SQLException failure=assertThrows(SQLException.class,()->apply(c));
        assertEquals(code,failure.getErrorCode(),failure.getMessage());
        if (code==1644) assertStoredRoutineFingerprint(c);
        assertEquals(before,allData(c),"Rejected preflight must not alter table rows");
        assertEquals(definitions,metadata(c),"Rejected preflight must not alter table/index/FK/trigger structure");
    }
    private static void assertStoredRoutineFingerprint(Connection c) throws Exception {
        String routine=statements(readScript(SCRIPT)).stream()
                .filter(x->x.startsWith("CREATE PROCEDURE cp57_upgrade_preflight()")).findFirst().orElseThrow(AssertionError::new);
        String body=routine.substring(routine.indexOf("BEGIN"));
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder expected=new StringBuilder();for(byte b:digest)expected.append(String.format("%02x",b&255));
        assertEquals(expected.toString(),scalar(c,"SELECT SHA2(routine_definition,256) FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name='cp57_upgrade_preflight' AND routine_type='PROCEDURE'"),
                "Native MySQL metadata must retain the owned routine fingerprint after a rejected preflight");
    }
    private static Map<String,List<String>> data(Connection c) throws SQLException {
        Map<String,List<String>> result=allData(c);
        // Only nurse/menu seed additions are deliberately authorized by the migration.
        result.remove("sys_role"); result.remove("sys_menu"); result.remove("sys_role_menu");
        for (String table:TABLES) if (result.containsKey(table) && result.get(table).isEmpty()) result.remove(table);
        // Legacy columns are compared explicitly; newly added columns do not change old logical data.
        result.put("doctor_care_plan",rows(c,"SELECT id,doctor_user_id,patient_id,title,plan_type,instructions,target_date,status,created_at,updated_at FROM doctor_care_plan"));
        return result;
    }
    private static Map<String,List<String>> allData(Connection c) throws SQLException {
        Map<String,List<String>> result=new TreeMap<>();
        try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")) {
            while(rs.next()) {String name=rs.getString(1);result.put(name,rows(c,"SELECT * FROM `"+name.replace("`","``")+"`"));}
        }
        return result;
    }
    private static List<String> metadata(Connection c) throws SQLException {
        List<String> result=new ArrayList<>();
        result.addAll(rows(c,"SELECT table_name,column_name,column_type,is_nullable,column_default,extra,character_set_name,collation_name,column_comment FROM information_schema.columns WHERE table_schema=DATABASE()"));
        result.addAll(rows(c,"SELECT table_name,index_name,non_unique,seq_in_index,column_name,sub_part FROM information_schema.statistics WHERE table_schema=DATABASE()"));
        result.addAll(rows(c,"SELECT table_name,constraint_name,column_name,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE()"));
        result.addAll(triggerDefinitions(c));Collections.sort(result);return result;
    }
    private static List<String> triggerDefinitions(Connection c) throws SQLException {
        return rows(c,"SELECT trigger_name,event_object_table,event_manipulation,action_timing,action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'cp57\\_%'");
    }
    private static void error(Connection c,int code,String sql) {
        SQLException failure=assertThrows(SQLException.class,()->execute(c,sql));assertEquals(code,failure.getErrorCode(),failure.getMessage());
    }
    private static List<String> rows(Connection c,String sql) throws SQLException {
        List<String> result=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet rs=s.executeQuery(sql)) {
            while(rs.next()) {StringBuilder row=new StringBuilder();for(int i=1;i<=rs.getMetaData().getColumnCount();i++){String v=rs.getString(i);row.append(v==null?"N;":"S"+v.length()+":"+v+";");}result.add(row.toString());}
        }
        Collections.sort(result);return result;
    }
    private static String scalar(Connection c,String sql) throws SQLException {
        try(Statement s=c.createStatement();ResultSet rs=s.executeQuery(sql)){assertTrue(rs.next());return rs.getString(1);}
    }
    private static void execute(Connection c,String sql) throws SQLException {try(Statement s=c.createStatement()){s.execute(sql);}}
}
