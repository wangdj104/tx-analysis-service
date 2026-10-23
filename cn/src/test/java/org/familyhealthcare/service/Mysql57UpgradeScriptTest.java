package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Static initialization contracts; no assertion here claims native MySQL execution. */
class Mysql57UpgradeScriptTest {
    private String script() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/sql/init.sql")), StandardCharsets.UTF_8);
    }

    @Test void initializationInstallsBothOperationsForEveryIgnoredCollaborationCheck() throws Exception {
        String sql = script();
        for (String table : new String[]{"revision", "action", "notification", "command"}) {
            for (String operation : new String[]{"INSERT", "UPDATE"}) {
                String suffix = operation.equals("INSERT") ? "bi" : "bu";
                assertTrue(sql.contains("CREATE TRIGGER cp57_" + table + "_" + suffix + " BEFORE " + operation
                        + " ON care_plan_" + table), table + " " + operation);
            }
        }
        assertTrue(sql.contains("NEW.revision_no <= 0"));
        assertTrue(sql.contains("NEW.ordinal NOT BETWEEN 1 AND 50"));
        assertTrue(sql.contains("NEW.lock_version < 0"));
        assertTrue(sql.contains("NEW.attempt_count NOT BETWEEN 0 AND 3"));
        assertTrue(sql.contains("CHAR_LENGTH(NEW.dispatch_key) = 0"));
        assertTrue(sql.contains("NEW.expected_version < 0"));
        assertEquals(8, count(sql, "(?m)^CREATE TRIGGER cp57_"));
        assertFalse(Pattern.compile("(?is)SET @[^;]*CREATE TRIGGER").matcher(sql).find(), "5.7 cannot PREPARE CREATE TRIGGER");
    }

    @Test void initializationContainsFinalScopedIndexesAndNullableAttachments() throws Exception {
        String sql = script();
        for (String index : new String[]{
                "UNIQUE KEY `uk_user_patient_name` (`user_id`,`name`)",
                "UNIQUE KEY `uk_medication_stock` (`patient_id`,`medication_id`)",
                "KEY `idx_alert_dedupe` (`patient_id`,`dedupe_key`,`status`)",
                "KEY `idx_intake_patient_schedule` (`patient_id`,`scheduled_at`,`status`)",
                "KEY `idx_schedule_patient_date` (`patient_id`,`schedule_date`,`status`)",
                "KEY `idx_event_patient_date` (`patient_id`,`event_date`,`event_time`)"}) {
            assertTrue(sql.replaceAll("\\s+", "").contains(index.replaceAll("\\s+", "")),index);
        }
        String attachment=table(sql,"medical_record_attachment");
        assertTrue(attachment.contains("`file_path` VARCHAR(500)"));
        assertFalse(Pattern.compile("`file_path`[^\\n]*NOT NULL").matcher(attachment).find());
        assertTrue(attachment.contains("FOREIGN KEY (`record_id`) REFERENCES `medical_record`(`id`) ON DELETE CASCADE"));
        assertFalse(sql.contains("DROP INDEX uk_name"));
    }

    @Test void initializationDefinesEveryCollaborationTableAndItsReferences() throws Exception {
        String sql=script();
        for(String name:new String[]{"care_plan_revision","care_plan_action","care_plan_event","care_plan_evidence","care_nurse_assignment","care_plan_notification","care_plan_command"})
            assertTrue(table(sql,name).contains("ENGINE=InnoDB"),name);
        for(String foreignKey:new String[]{"fk_doctor_plan_current_revision","fk_doctor_plan_draft_revision","fk_doctor_plan_legacy_source"})
            assertTrue(sql.contains(foreignKey),foreignKey);
        assertTrue(sql.contains("'nurse'"));assertTrue(sql.contains("'care-plan-nursing'"));assertTrue(sql.contains("'care-plan-nurse-assignments'"));
        assertFalse(Pattern.compile("(?im)^\\s*(DROP TABLE|DELETE FROM|TRUNCATE|SOURCE)\\b").matcher(sql).find());
        assertFalse(sql.contains("SKIP LOCKED"));assertFalse(sql.contains("utf8mb4_0900"));
    }

    @Test void delimiterParserPreservesTriggerBodyAndQuotedDelimitersExactly() {
        String body="BEGIN\n  IF NEW.ordinal < 1 THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic $$ ; message';\n  END IF;\nEND";
        List<String> parts=Mysql57UpgradeAssertions.statements("SELECT 'before;';\nDELIMITER $$\nCREATE TRIGGER synthetic BEFORE INSERT ON synthetic_table FOR EACH ROW "+body+"$$\nDELIMITER ;\nSELECT 'after';");
        assertEquals(3,parts.size());assertTrue(parts.get(1).endsWith(body));assertFalse(parts.get(1).contains("DELIMITER"));
    }

    @Test void parserKeepsEveryInitializationTriggerInOneStatement() throws Exception {
        String sql=script();int triggerCount=0;
        for(String statement:Mysql57UpgradeAssertions.statements(sql)) {
            int declarations=count(statement,"(?m)^CREATE TRIGGER ");
            assertTrue(declarations<=1,"Each trigger must be one independent SQL statement");
            if(declarations==1){triggerCount++;assertTrue(statement.trim().endsWith("END"));assertTrue(statement.contains("SIGNAL SQLSTATE '45000'"));}
            assertFalse(Pattern.compile("(?m)^DELIMITER\\s").matcher(statement).find());
        }
        assertTrue(triggerCount>8);assertEquals(count(sql,"(?m)^CREATE TRIGGER "),triggerCount);
    }

    @Test void delimiterParserIgnoresCommentsAndDirectivesInsideQuotedStrings() {
        String source="-- synthetic ; $$\nSELECT 'first;'; /* ; */\nDELIMITER $$\nCREATE PROCEDURE synthetic() BEGIN\n SELECT 'quoted\nDELIMITER ;\nvalue', 'it''s $$'; -- $$\nEND$$\nDELIMITER ;\n# trailing comment;\n";
        List<String> parts=Mysql57UpgradeAssertions.statements(source);
        assertEquals(2,parts.size());assertTrue(parts.get(1).contains("'quoted\nDELIMITER ;\nvalue'"));assertTrue(parts.get(1).endsWith("END"));
    }

    private String table(String sql,String name) {
        java.util.regex.Matcher matcher=Pattern.compile("(?s)CREATE TABLE(?: IF NOT EXISTS)? `"+name+"` \\(.*?\\) ENGINE=InnoDB[^;]*;").matcher(sql);
        assertTrue(matcher.find(),name);return matcher.group();
    }
    private int count(String text,String regex) {
        java.util.regex.Matcher matcher=Pattern.compile(regex).matcher(text);int count=0;while(matcher.find())count++;return count;
    }
}
