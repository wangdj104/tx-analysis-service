package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Static contracts only: native execution is tested by Mysql57UpgradeAssertions. */
class Mysql57UpgradeScriptTest {
    private String script() throws Exception {
        Path path = Paths.get("src/main/resources/sql/mysql57_schema_upgrade_20261012.sql");
        assertTrue(Files.exists(path), "The guarded MySQL 5.7 upgrade must be available as one explicit script");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test void usesCompatibleTriggersForEveryIgnoredCheckOnInsertAndUpdate() throws Exception {
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
        assertEquals(8, count(sql, "(?m)^CREATE TRIGGER "));
        assertFalse(Pattern.compile("(?is)SET @[^;]*CREATE TRIGGER").matcher(sql).find(), "5.7 cannot PREPARE CREATE TRIGGER");
    }

    @Test void preflightRunsBeforeEveryApplicationMutationAndRejectsUnexpectedDefinitions() throws Exception {
        String sql = script();
        int preflight = sql.indexOf("CALL cp57_upgrade_preflight()");
        assertTrue(preflight > 0);
        assertTrue(preflight < sql.indexOf("ALTER TABLE doctor_care_plan ADD COLUMN"));
        assertTrue(preflight < sql.indexOf("CREATE TABLE IF NOT EXISTS `care_plan_revision`"));
        assertTrue(preflight < sql.indexOf("DROP TRIGGER IF EXISTS cp57_revision_bi"));
        assertTrue(sql.contains("information_schema.triggers"));
        assertTrue(sql.contains("ACTION_STATEMENT"));
        assertTrue(sql.contains("SIGNAL SQLSTATE '45000'"));
        assertTrue(sql.contains("Duplicate patient owner/name"));
        assertTrue(sql.contains("Duplicate stock patient/medication"));
        assertTrue(sql.contains("Patient ownership is NULL"));
        assertTrue(sql.contains("Invalid existing collaboration rows"));
        assertFalse(Pattern.compile("(?im)^\\s*(DROP TABLE|DELETE FROM|TRUNCATE|UPDATE medication_stock)").matcher(sql).find());
        assertFalse(sql.contains("CONVERT TO CHARACTER SET"));
    }

    @Test void suppliesOnlyTheTargetedLegacyRepairsWithoutOverwritingAttachmentEncoding() throws Exception {
        String sql = script();
        assertTrue(sql.contains("DROP INDEX uk_name"));
        assertTrue(sql.contains("ADD UNIQUE KEY uk_user_patient_name(user_id,name)"));
        assertTrue(sql.contains("ADD UNIQUE KEY uk_medication_stock(patient_id,medication_id)"));
        assertTrue(sql.contains("idx_alert_dedupe(patient_id,dedupe_key,status)"));
        assertTrue(sql.contains("idx_intake_patient_schedule(patient_id,scheduled_at,status)"));
        assertTrue(sql.contains("idx_schedule_patient_date(patient_id,schedule_date,status)"));
        assertTrue(sql.contains("idx_event_patient_date(patient_id,event_date,event_time)"));
        assertTrue(sql.contains("MODIFY COLUMN file_path VARCHAR(500) CHARACTER SET "));
        assertTrue(sql.contains("COLLATION_NAME"));
        assertTrue(sql.contains("COLUMN_COMMENT"));
        assertTrue(sql.contains("DELIMITER $$"));
    }

    @Test void delimiterParserPreservesRoutineBodyAndQuotedDelimitersExactly() {
        String body="BEGIN\n  IF NEW.ordinal < 1 THEN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic $$ ; message';\n  END IF;\nEND";
        java.util.List<String> parts=Mysql57UpgradeAssertions.statements("SELECT 'before;';\nDELIMITER $$\nCREATE TRIGGER synthetic BEFORE INSERT ON synthetic_table FOR EACH ROW "+body+"$$\nDELIMITER ;\nSELECT 'after';");
        assertEquals(3,parts.size());
        assertTrue(parts.get(1).endsWith(body), "Whitespace is part of the owned routine/trigger fingerprint");
        assertFalse(parts.get(1).contains("DELIMITER"));
    }

    @Test void parsedOwnedRoutineAndTriggersMatchTheirExactGuards() throws Exception {
        String sql=script();
        java.util.List<String> parts=Mysql57UpgradeAssertions.statements(sql);
        String routine=parts.stream().filter(x->x.startsWith("CREATE PROCEDURE cp57_upgrade_preflight()")).findFirst().orElseThrow(AssertionError::new);
        String body=routine.substring(routine.indexOf("BEGIN"));
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format("%02x",b&255));
        assertTrue(sql.contains("SHA2(ROUTINE_DEFINITION,256)='"+hex+"'"));
        int triggers=0;
        for(String statement:parts) {
            if(!statement.startsWith("CREATE TRIGGER cp57_"))continue;
            triggers++;
            String triggerBody=statement.substring(statement.indexOf("FOR EACH ROW ")+13);
            byte[] triggerHash=java.security.MessageDigest.getInstance("SHA-256").digest(triggerBody.getBytes(StandardCharsets.UTF_8));
            StringBuilder triggerHex=new StringBuilder();for(byte b:triggerHash)triggerHex.append(String.format("%02x",b&255));
            assertTrue(routine.contains("SHA2(ACTION_STATEMENT,256)<>'"+triggerHex+"'"),statement);
            assertTrue(routine.contains("ACTION_STATEMENT IS NULL"),"Unavailable trigger definitions must fail closed");
        }
        assertEquals(8,triggers);
    }

    @Test void routineOwnershipFingerprintSurvivesMysqlMetadataLiteralDecoding() throws Exception {
        // MySQL sql_lex.cc body_utf8_append_literal stores the decoded literal in INFORMATION_SCHEMA.
        // https://bugs.mysql.com/bug.php?id=116354 (also affects MySQL 5.7).
        String routine=Mysql57UpgradeAssertions.statements(script()).stream()
                .filter(x->x.startsWith("CREATE PROCEDURE cp57_upgrade_preflight()")).findFirst().orElseThrow(AssertionError::new);
        String body=routine.substring(routine.indexOf("BEGIN"));
        java.util.regex.Matcher literals=Pattern.compile("'(?:''|[^'])*'").matcher(body);
        StringBuffer metadataView=new StringBuffer();
        while(literals.find()) {
            String token=literals.group();
            String decoded="'"+token.substring(1,token.length()-1).replace("''","'")+"'";
            literals.appendReplacement(metadataView,java.util.regex.Matcher.quoteReplacement(decoded));
        }
        literals.appendTail(metadataView);
        assertEquals(body,metadataView.toString(),"Routine fingerprint must not depend on escaped literals discarded by MySQL metadata");
    }

    @Test void delimiterParserIgnoresCommentsAndDirectivesInsideQuotedStrings() {
        String source="-- synthetic ; $$\nSELECT 'first;'; /* ; */\nDELIMITER $$\nCREATE PROCEDURE synthetic() BEGIN\n SELECT 'quoted\nDELIMITER ;\nvalue', 'it''s $$'; -- $$\nEND$$\nDELIMITER ;\n# trailing comment;\n";
        java.util.List<String> parts=Mysql57UpgradeAssertions.statements(source);
        assertEquals(2,parts.size());
        assertTrue(parts.get(1).contains("'quoted\nDELIMITER ;\nvalue'"));
        assertTrue(parts.get(1).endsWith("END"));
    }

    @Test void legacyBlockerFixturesPreserveAutomaticAuditTimestamps() throws Exception {
        String helper=new String(Files.readAllBytes(Paths.get("src/test/java/org/familyhealthcare/service/Mysql57UpgradeAssertions.java")),StandardCharsets.UTF_8);
        assertTrue(helper.contains("UPDATE medication SET patient_id=6751,updated_at=updated_at WHERE id=6750"));
        assertTrue(helper.contains("UPDATE medication SET patient_id=6750,updated_at=updated_at WHERE id=6750"));
        assertTrue(helper.contains("UPDATE sys_role SET role_code='cp57_synthetic_admin',update_time=update_time WHERE role_code='admin'"));
        assertTrue(helper.contains("UPDATE sys_role SET role_code='admin',update_time=update_time WHERE role_code='cp57_synthetic_admin'"));
        assertTrue(helper.contains("'Synthetic migration medication','2026-09-20 12:34:56','2026-09-21 13:45:57'"),
                "Historical audit times make accidental ON UPDATE mutation deterministic");
        assertTrue(helper.contains("Synthetic blocker fixtures must restore all original rows before migration"));
        assertTrue(helper.contains("All original data and extra tables survive"));
    }

    private int count(String text, String regex) {
        java.util.regex.Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0; while (matcher.find()) count++; return count;
    }
}
