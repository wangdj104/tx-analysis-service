package org.familyhealthcare.service;

import com.alibaba.fastjson2.JSONObject;
import org.familyhealthcare.util.DataScopeHelper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Compatibility contract for the limited version-1 export, using synthetic records only. */
class CarePlanReleaseContractTest {
    private static final List<String> VERSION_ONE_TABLES = Arrays.asList(
        "patient", "patient_clinical", "patient_health_target", "medication", "dialysis_record",
        "medical_record", "medical_record_item", "medical_record_attachment", "dry_weight_monthly",
        "bp_self_monitor_record", "bp_pattern_analysis", "nutrition_diary", "nutrition_assessment",
        "complication_record", "ai_analysis_record", "health_analysis_automation", "alert_rule",
        "alert_record", "medication_reminder", "medication_log", "medication_intake", "dialysis_schedule",
        "health_event", "care_item", "medication_stock", "medication_stock_movement", "care_intake_action");
    private static final List<String> EXCLUDED_CARE_TABLES = Arrays.asList(
        "doctor_care_plan", "care_plan_revision", "care_plan_action", "care_plan_event",
        "care_plan_evidence", "care_nurse_assignment", "care_plan_notification", "care_plan_command");
    private FamilyBackupService backup;
    private Locale previousLocale;

    @BeforeEach void syntheticArchive() {
        previousLocale = LocaleContextHolder.getLocale();
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:release" + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        // Keep connections alive for this test without retaining a shared database.
        jdbc.execute("SET DB_CLOSE_DELAY -1");
        jdbc.execute("CREATE TABLE patient(id BIGINT PRIMARY KEY,name VARCHAR(100),user_id BIGINT)");
        jdbc.execute("CREATE TABLE medication(id BIGINT PRIMARY KEY,patient_id BIGINT,drug_name VARCHAR(100))");
        jdbc.update("INSERT INTO patient VALUES(1,'Synthetic release patient',7)");
        jdbc.update("INSERT INTO medication VALUES(2,1,'Synthetic medication')");
        for (String table : EXCLUDED_CARE_TABLES) {
            jdbc.execute("CREATE TABLE " + table + "(id BIGINT PRIMARY KEY,patient_id BIGINT,note VARCHAR(100))");
            jdbc.update("INSERT INTO " + table + " VALUES(10,1,'Synthetic collaboration history')");
        }
        DataScopeHelper scope = mock(DataScopeHelper.class);
        when(scope.requireUserId()).thenReturn(7L);
        when(scope.accessiblePatientIds(7L)).thenReturn(Collections.singletonList(1L));
        backup = new FamilyBackupService();
        ReflectionTestUtils.setField(backup, "jdbc", jdbc);
        ReflectionTestUtils.setField(backup, "scope", scope);
    }

    @AfterEach void restoreLocale() { LocaleContextHolder.setLocale(previousLocale); }

    @Test void archivePreservesVersionOneAndItsDeclaredTableList() throws Exception {
        JSONObject archive = backup.readArchive(backup.exportArchive());
        assertEquals("family-health-backup", archive.getString("format"));
        assertEquals(1, archive.getInteger("version"));
        assertEquals(VERSION_ONE_TABLES, archive.getJSONArray("includedTables").toJavaList(String.class));
        JSONObject tables = archive.getJSONObject("tables");
        assertEquals(new HashSet<>(Arrays.asList("patient", "medication")), tables.keySet());
        assertEquals("Synthetic release patient", tables.getJSONArray("patient").getJSONObject(0).getString("name"));
        assertEquals(1L, tables.getJSONArray("medication").getJSONObject(0).getLong("patient_id"));
        for (String table : EXCLUDED_CARE_TABLES) assertFalse(tables.containsKey(table), table);
    }

    @Test void englishWarningExplainsExcludedCollaborationAndRequiredFullBackup() throws Exception {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        String warnings = backup.readArchive(backup.exportArchive()).getJSONArray("warnings")
            .toJSONString().toLowerCase(Locale.ROOT);
        assertTrue(warnings.contains("care plan"));
        for (String boundary : Arrays.asList("revisions", "actions", "receipts", "events", "evidence",
                "nurse assignments", "notification outbox", "commands", "complete database", "attachment storage"))
            assertTrue(warnings.contains(boundary), boundary);
    }

    @Test void chineseWarningExplainsExcludedCollaborationAndRequiredFullBackup() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        String warnings = backup.readArchive(backup.exportArchive()).getJSONArray("warnings").toJSONString();
        assertTrue(warnings.contains("协作计划"));
        for (String boundary : Arrays.asList("版本", "行动项", "回执", "事件", "证据", "护理分配",
                "通知发件箱", "命令", "完整数据库", "附件存储"))
            assertTrue(warnings.contains(boundary), boundary);
    }
}
