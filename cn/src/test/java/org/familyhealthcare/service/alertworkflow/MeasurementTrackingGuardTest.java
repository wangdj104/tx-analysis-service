package org.familyhealthcare.service.alertworkflow;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MeasurementTrackingGuardTest {
    AlertWorkflowTestDatabase db;MeasurementTrackingGuard guard;
    @BeforeEach void setup() throws Exception {db=new AlertWorkflowTestDatabase();db.fixtures();db.sourceTables();guard=new MeasurementTrackingGuard(db.jdbc);db.jdbc.update("INSERT INTO bp_self_monitor_record(id,patient_id,user_id,record_date,record_time,measure_type,systolic_bp,diastolic_bp) VALUES(1,10,1,'2026-10-13','11:59','BP',120,80)");}
    @Test void genuinelyPreUpgradeSchemaPreservesUntrackedLegacyMutation() {
        assertDoesNotThrow(()->db.tx.execute(tx->{guard.requireUntrackedMutation("BP_SELF_MONITOR",1);db.jdbc.update("UPDATE bp_self_monitor_record SET systolic_bp=130 WHERE id=1");return null;}));
        assertEquals(130,db.jdbc.queryForObject("SELECT systolic_bp FROM bp_self_monitor_record WHERE id=1",Integer.class));
    }
    @Test void partiallyInstalledWorkflowStorageFailsClosedRatherThanIgnoringSqlErrors() {
        db.jdbc.execute("CREATE TABLE alert_source_identity(id BIGINT,source_type VARCHAR(40),source_id BIGINT)");
        AlertWorkflowException e=assertThrows(AlertWorkflowException.class,()->db.tx.execute(tx->{guard.requireUntrackedMutation("BP_SELF_MONITOR",1);return null;}));assertEquals("WORKFLOW_SCHEMA_UNAVAILABLE",e.getErrorCode());
    }
    @Test void markerWithoutWorkflowTablesIsNotMistakenForAGenuinePreUpgradeDatabase() {
        db.jdbc.execute("CREATE TABLE alert_record(id BIGINT,alert_type VARCHAR(40))");db.jdbc.update("INSERT INTO alert_record VALUES(1,'MEASUREMENT_FOLLOW_UP')");
        AlertWorkflowException e=assertThrows(AlertWorkflowException.class,()->db.tx.execute(tx->{guard.requireUntrackedMutation("BP_SELF_MONITOR",1);return null;}));assertEquals("WORKFLOW_SCHEMA_UNAVAILABLE",e.getErrorCode());
    }
    @Test void unexpectedDatabaseFailureIsNeverConvertedIntoUntrackedPermission() throws Exception {
        db.workflowTables();db.jdbc.execute("ALTER TABLE alert_source_identity RENAME COLUMN source_type TO broken_source_type");
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.tx.execute(tx->{guard.requireUntrackedMutation("BP_SELF_MONITOR",1);return null;}));
    }
    @Test void sourceTablesAloneCannotMasqueradeAsACompleteWorkflowInstallation() {
        db.jdbc.execute("CREATE TABLE alert_source_identity(id BIGINT,source_type VARCHAR(40),source_id BIGINT)");
        db.jdbc.execute("CREATE TABLE alert_source_revision(id BIGINT)");db.jdbc.execute("CREATE TABLE measurement_command(id BIGINT)");
        AlertWorkflowException failure=assertThrows(AlertWorkflowException.class,()->db.tx.execute(tx->{guard.requireUntrackedMutation("BP_SELF_MONITOR",1);return null;}));assertEquals("WORKFLOW_SCHEMA_UNAVAILABLE",failure.getErrorCode());
    }

}
