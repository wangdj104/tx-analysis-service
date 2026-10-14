-- Separate, manually reviewed alert workflow installation for existing databases.
-- DDL commits implicitly. Requires CREATE/ALTER/INDEX/REFERENCES/TRIGGER plus read permissions.
-- No production execution authorized. No data repair, feature enablement, seeds, trigger replacement or startup migration.
-- Single-use strict preflight precedes every product DDL; partial installation requires operator inspection.
-- Read-only fail-closed preflight for the separate alert workflow installation.
-- Select the intended application schema; stop all writers, take/restore-test a full backup.
-- Stop on any error; never use mysql --force. Existing managed objects are NOT overwritten.
-- This single-use path rejects partial/prior installation and unknown names for operator review.
-- It does not inspect or print patient prose, enable features, grant access or send notifications.
SELECT DATABASE() AS selected_schema,VERSION() AS server_version,@@sql_mode AS sql_mode;
-- QUOTE() preserves the legacy note comment under the supported default escaping mode.
SET @aw57_ok=(FIND_IN_SET('NO_BACKSLASH_ESCAPES',@@sql_mode)=0);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS supported_escaping','SELECT * FROM information_schema.AW57_STOP_ESCAPING_MODE');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SHOW VARIABLES WHERE Variable_name IN ('log_bin','log_bin_trust_function_creators');
SET @aw57_ok=(@@GLOBAL.log_bin=0 OR @@GLOBAL.log_bin_trust_function_creators=1 OR EXISTS(SELECT 1 FROM information_schema.user_privileges WHERE privilege_type='SUPER' AND grantee=CONCAT(CHAR(39),SUBSTRING_INDEX(CURRENT_USER(),'@',1),CHAR(39),'@',CHAR(39),SUBSTRING_INDEX(CURRENT_USER(),'@',-1),CHAR(39))));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS trigger_privilege_review','SELECT * FROM information_schema.AW57_STOP_TRIGGER_PRIVILEGE_REVIEW');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(DATABASE() IS NOT NULL AND VERSION() NOT LIKE '%MariaDB%' AND (VERSION() LIKE '8.0.%' OR (VERSION() LIKE '5.7.%' AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=18)));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS supported_mysql','SELECT * FROM information_schema.AW57_STOP_SUPPORTED_MYSQL');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('patient','health_measurement','bp_self_monitor_record','alert_record','alert_event','doctor_care_plan','care_plan_action','care_plan_event','care_plan_evidence','care_access_grant','care_nurse_assignment') AND engine='InnoDB' AND table_type='BASE TABLE')=11);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS prerequisite_tables','SELECT * FROM information_schema.AW57_STOP_PREREQUISITE_TABLES');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('alert_source_identity','alert_source_revision','alert_source_link','alert_evidence','alert_command','measurement_command','alert_notification')));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS uninstalled_tables','SELECT * FROM information_schema.AW57_STOP_UNINSTALLED_TABLES');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='alert_record' AND column_name IN ('workflow_version','workflow_state','workflow_mode','cycle_no','source_revision','metric_key','responsible_user_id','due_at','accepted_by','accepted_at','lock_version','current_handling_event_id','current_feedback_event_id','source_review_required','reassignment_required','classification_json','closed_by','closed_at','closure_reason')) OR (table_name='alert_event' AND column_name IN ('event_type','cycle_no','actor_role','actor_relation','entry_mode','occurred_at','recorded_at','payload_json')))));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS uninstalled_columns','SELECT * FROM information_schema.AW57_STOP_UNINSTALLED_COLUMNS');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND (trigger_name LIKE 'aw57\_%' OR event_object_table IN ('alert_record','alert_event','alert_source_identity','alert_source_revision','alert_source_link','alert_evidence','alert_command','measurement_command','alert_notification'))) AND NOT EXISTS(SELECT 1 FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name LIKE 'aw57\_%'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS unknown_trigger_routine','SELECT * FROM information_schema.AW57_STOP_UNKNOWN_TRIGGER_ROUTINE');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name IN ('uk_aw_source_identity','idx_aw_source_patient','fk_aw_source_patient','uk_aw_source_revision','fk_aw_revision_identity','uk_aw_source_metric','idx_aw_link_alert','fk_aw_link_revision','fk_aw_link_alert','uk_aw_evidence_ref','fk_aw_evidence_event','uk_aw_command_actor_key','fk_aw_command_alert','uk_aw_measurement_actor_key','uk_aw_notification_dispatch','idx_aw_notification_due','fk_aw_notification_event','fk_aw_notification_patient','idx_aw_patient_queue','idx_aw_event_cycle')) AND NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name IN ('uk_aw_source_identity','idx_aw_source_patient','fk_aw_source_patient','uk_aw_source_revision','fk_aw_revision_identity','uk_aw_source_metric','idx_aw_link_alert','fk_aw_link_revision','fk_aw_link_alert','uk_aw_evidence_ref','fk_aw_evidence_event','uk_aw_command_actor_key','fk_aw_command_alert','uk_aw_measurement_actor_key','uk_aw_notification_dispatch','idx_aw_notification_due','fk_aw_notification_event','fk_aw_notification_patient','idx_aw_patient_queue','idx_aw_event_cycle')));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS reserved_name_collision','SELECT * FROM information_schema.AW57_STOP_RESERVED_NAME_COLLISION');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='patient' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS patient_id','SELECT * FROM information_schema.AW57_STOP_PATIENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_record' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_record_id','SELECT * FROM information_schema.AW57_STOP_ALERT_RECORD_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_record' AND column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_record_patient_id','SELECT * FROM information_schema.AW57_STOP_ALERT_RECORD_PATIENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='alert_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_event_alert_id','SELECT * FROM information_schema.AW57_STOP_ALERT_EVENT_ALERT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_event_patient_id','SELECT * FROM information_schema.AW57_STOP_ALERT_EVENT_PATIENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='health_measurement' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS health_measurement_id','SELECT * FROM information_schema.AW57_STOP_HEALTH_MEASUREMENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bp_self_monitor_record' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS bp_self_monitor_record_id','SELECT * FROM information_schema.AW57_STOP_BP_SELF_MONITOR_RECORD_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
-- Existing FK parents must have exact compatible signed identifiers and usable primary indexes.
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_event_id','SELECT * FROM information_schema.AW57_STOP_ALERT_EVENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='PRIMARY' AND seq_in_index=1 AND column_name='id' AND sub_part IS NULL AND non_unique=0 AND index_type='BTREE') AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='PRIMARY')=1);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS patient_primary_id','SELECT * FROM information_schema.AW57_STOP_PATIENT_PRIMARY_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_record' AND index_name='PRIMARY' AND seq_in_index=1 AND column_name='id' AND sub_part IS NULL AND non_unique=0 AND index_type='BTREE') AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_record' AND index_name='PRIMARY')=1);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_record_primary_id','SELECT * FROM information_schema.AW57_STOP_ALERT_RECORD_PRIMARY_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_event' AND index_name='PRIMARY' AND seq_in_index=1 AND column_name='id' AND sub_part IS NULL AND non_unique=0 AND index_type='BTREE') AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_event' AND index_name='PRIMARY')=1);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_event_primary_id','SELECT * FROM information_schema.AW57_STOP_ALERT_EVENT_PRIMARY_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
-- The stable legacy-column discriminator is reserved; conflicting legacy rows require operator review.
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM alert_record WHERE alert_type='MEASUREMENT_FOLLOW_UP'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS reserved_marker_rows','SELECT * FROM information_schema.AW57_STOP_RESERVED_MARKER_ROWS');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='note' AND data_type='varchar' AND is_nullable='YES' AND column_default IS NULL AND extra=''));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS note_compatible','SELECT * FROM information_schema.AW57_STOP_NOTE_COMPATIBLE');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SELECT COUNT(*) AS preserved_legacy_orphan_events FROM alert_event e LEFT JOIN alert_record a ON a.id=e.alert_id WHERE a.id IS NULL;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM alert_record a LEFT JOIN patient p ON p.id=a.patient_id WHERE p.id IS NULL));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_patient','SELECT * FROM information_schema.AW57_STOP_ALERT_PATIENT');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;

-- Any existing revision table is a prior/partial installation, including one without action_versions_json.
-- Never substitute unknown historical qualification metadata with an empty object.

-- Baseline alert rows remain workflow_version=0; no historical row is reinterpreted.
ALTER TABLE `alert_record`
  ADD COLUMN `workflow_version` INT NOT NULL DEFAULT 0,
  ADD COLUMN `workflow_state` VARCHAR(30) DEFAULT NULL,
  ADD COLUMN `workflow_mode` VARCHAR(20) DEFAULT NULL,
  ADD COLUMN `cycle_no` INT NOT NULL DEFAULT 0,
  ADD COLUMN `source_revision` BIGINT DEFAULT NULL,
  ADD COLUMN `metric_key` VARCHAR(40) DEFAULT NULL,
  ADD COLUMN `responsible_user_id` BIGINT DEFAULT NULL,
  ADD COLUMN `due_at` DATETIME(6) DEFAULT NULL,
  ADD COLUMN `accepted_by` BIGINT DEFAULT NULL,
  ADD COLUMN `accepted_at` DATETIME(6) DEFAULT NULL,
  ADD COLUMN `lock_version` BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN `current_handling_event_id` BIGINT DEFAULT NULL,
  ADD COLUMN `current_feedback_event_id` BIGINT DEFAULT NULL,
  ADD COLUMN `source_review_required` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `reassignment_required` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `classification_json` LONGTEXT DEFAULT NULL,
  ADD COLUMN `closed_by` BIGINT DEFAULT NULL,
  ADD COLUMN `closed_at` DATETIME(6) DEFAULT NULL,
  ADD COLUMN `closure_reason` VARCHAR(1000) DEFAULT NULL,
  ADD KEY `idx_aw_patient_queue` (`patient_id`,`workflow_version`,`workflow_state`,`due_at`,`id`);
ALTER TABLE `alert_event`
  ADD COLUMN `event_type` VARCHAR(40) DEFAULT NULL,
  ADD COLUMN `cycle_no` INT NOT NULL DEFAULT 0,
  ADD COLUMN `actor_role` VARCHAR(50) DEFAULT NULL,
  ADD COLUMN `actor_relation` VARCHAR(100) DEFAULT NULL,
  ADD COLUMN `entry_mode` VARCHAR(20) DEFAULT NULL,
  ADD COLUMN `occurred_at` DATETIME(6) DEFAULT NULL,
  ADD COLUMN `recorded_at` DATETIME(6) DEFAULT NULL,
  ADD COLUMN `payload_json` LONGTEXT DEFAULT NULL,
  ADD KEY `idx_aw_event_cycle` (`alert_id`,`cycle_no`,`id`);
SET @aw57_note_ddl=(SELECT IF(character_maximum_length<2000,CONCAT('ALTER TABLE `alert_event` MODIFY COLUMN `note` VARCHAR(2000) CHARACTER SET ',character_set_name,' COLLATE ',collation_name,' NULL DEFAULT NULL COMMENT ',QUOTE(column_comment)),'SELECT 1 AS note_already_large') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='note');
PREPARE aw57_stmt FROM @aw57_note_ddl; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
-- Alert workflow storage is opt-in at the application boundary. No patient/account/grant seeds.
CREATE TABLE IF NOT EXISTS `alert_source_identity` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_type` VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `current_revision` BIGINT NOT NULL DEFAULT 1,
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `lock_version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aw_source_identity` (`source_type`,`source_id`),
  KEY `idx_aw_source_patient` (`patient_id`,`id`),
  CONSTRAINT `fk_aw_source_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Identity retains distinct journey and self-monitor stores';

CREATE TABLE IF NOT EXISTS `alert_source_revision` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_identity_id` BIGINT NOT NULL,
  `revision_no` BIGINT NOT NULL,
  `recorded_by` BIGINT NOT NULL,
  `occurred_at` DATETIME(6) NOT NULL,
  `recorded_at` DATETIME(6) NOT NULL,
  `snapshot_json` LONGTEXT NOT NULL,
  `action_versions_json` LONGTEXT DEFAULT NULL COMMENT 'Server-only action version watermarks; NULL means unknown, never empty',
  `correction_reason` VARCHAR(1000) DEFAULT NULL,
  `source_status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aw_source_revision` (`source_identity_id`,`revision_no`),
  CONSTRAINT `fk_aw_revision_identity` FOREIGN KEY (`source_identity_id`) REFERENCES `alert_source_identity` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable source value/unit/context snapshots, no broad patient prose';

CREATE TABLE IF NOT EXISTS `alert_source_link` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_identity_id` BIGINT NOT NULL,
  `source_type` VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_id` BIGINT NOT NULL,
  `source_revision` BIGINT NOT NULL,
  `metric_key` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `alert_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aw_source_metric` (`source_type`,`source_id`,`source_revision`,`metric_key`),
  KEY `idx_aw_link_alert` (`alert_id`,`id`),
  CONSTRAINT `fk_aw_link_revision` FOREIGN KEY (`source_identity_id`,`source_revision`) REFERENCES `alert_source_revision` (`source_identity_id`,`revision_no`),
  CONSTRAINT `fk_aw_link_alert` FOREIGN KEY (`alert_id`) REFERENCES `alert_record` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='One follow-up per original identity, revision and metric';

CREATE TABLE IF NOT EXISTS `alert_evidence` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT NOT NULL,
  `source_type` VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_id` BIGINT NOT NULL,
  `source_revision` BIGINT DEFAULT NULL,
  `dependency_version` BIGINT DEFAULT NULL,
  `dependency_state` VARCHAR(30) NOT NULL DEFAULT 'VALID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aw_evidence_ref` (`event_id`,`source_type`,`source_id`),
  CONSTRAINT `fk_aw_evidence_event` FOREIGN KEY (`event_id`) REFERENCES `alert_event` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='References and dependency versions only, independent source authority required';

CREATE TABLE IF NOT EXISTS `alert_command` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `command_key` VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `alert_id` BIGINT DEFAULT NULL,
  `expected_version` BIGINT NOT NULL,
  `payload_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result_json` LONGTEXT DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aw_command_actor_key` (`actor_id`,`command_key`),
  CONSTRAINT `fk_aw_command_alert` FOREIGN KEY (`alert_id`) REFERENCES `alert_record` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Shared ALERT command contract, current authorization on every replay';

CREATE TABLE IF NOT EXISTS `measurement_command` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `command_key` VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_id` BIGINT DEFAULT NULL,
  `expected_version` BIGINT NOT NULL,
  `payload_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result_json` LONGTEXT DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aw_measurement_actor_key` (`actor_id`,`command_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Shared MEASUREMENT contract; sourceId is the native ID, source type is in canonical payload/result';

CREATE TABLE IF NOT EXISTS `alert_notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `recipient_user_id` BIGINT NOT NULL,
  `channel_id` BIGINT DEFAULT NULL,
  `dispatch_key` VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `next_attempt_at` DATETIME(6) DEFAULT NULL,
  `claimed_at` DATETIME(6) DEFAULT NULL,
  `claim_token` VARCHAR(64) DEFAULT NULL,
  `request_id` VARCHAR(64) DEFAULT NULL,
  `last_result` VARCHAR(1000) DEFAULT NULL,
  `delivered_at` DATETIME(6) DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aw_notification_dispatch` (`dispatch_key`),
  KEY `idx_aw_notification_due` (`status`,`next_attempt_at`,`id`),
  CONSTRAINT `fk_aw_notification_event` FOREIGN KEY (`event_id`) REFERENCES `alert_event` (`id`),
  CONSTRAINT `fk_aw_notification_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Dedicated alert outbox, never a replacement for care-plan foreign keys';

-- MySQL 5.7 ignores CHECK: these owned guards run on 5.7 and 8.0.
DELIMITER $$
CREATE TRIGGER `aw57_alert_bi` BEFORE INSERT ON `alert_record` FOR EACH ROW
BEGIN
  IF NEW.workflow_version NOT IN (0,1) OR NEW.lock_version<0 OR NEW.cycle_no<0 OR NEW.source_review_required NOT IN (0,1) OR NEW.reassignment_required NOT IN (0,1) OR (NEW.workflow_version=1 AND (NEW.workflow_state IS NULL OR NEW.workflow_state NOT IN ('OPEN','ACCEPTED','IN_PROGRESS','WAITING_FEEDBACK','READY_FOR_REVIEW','CLOSED','CANCELLED') OR NEW.workflow_mode IS NULL OR NEW.workflow_mode NOT IN ('PERSONAL','CLINICAL') OR NEW.cycle_no<1 OR NEW.source_type IS NULL OR NEW.source_type NOT IN ('HEALTH_MEASUREMENT','BP_SELF_MONITOR') OR NEW.source_id IS NULL OR NEW.source_id<1 OR NEW.source_revision IS NULL OR NEW.source_revision<1 OR NEW.metric_key IS NULL OR NEW.metric_key NOT IN ('BP','GLUCOSE','SPO2','WEIGHT','HEART_RATE','TEMPERATURE','CUSTOM') OR NEW.classification_json IS NULL OR JSON_VALID(NEW.classification_json)=0)) OR (NEW.current_handling_event_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM alert_event e WHERE e.id=NEW.current_handling_event_id AND e.alert_id=NEW.id AND e.cycle_no=NEW.cycle_no)) OR (NEW.current_feedback_event_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM alert_event e WHERE e.id=NEW.current_feedback_event_id AND e.alert_id=NEW.id AND e.cycle_no=NEW.cycle_no)) OR (NEW.workflow_version=1 AND (NEW.alert_type IS NULL OR NEW.alert_type<>'MEASUREMENT_FOLLOW_UP')) OR (NEW.workflow_version<>1 AND NEW.alert_type='MEASUREMENT_FOLLOW_UP') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid alert workflow metadata';
  END IF;
END$$
CREATE TRIGGER `aw57_alert_bu` BEFORE UPDATE ON `alert_record` FOR EACH ROW
BEGIN
  IF NEW.workflow_version NOT IN (0,1) OR NEW.lock_version<0 OR NEW.cycle_no<0 OR NEW.source_review_required NOT IN (0,1) OR NEW.reassignment_required NOT IN (0,1) OR (NEW.workflow_version=1 AND (NEW.workflow_state IS NULL OR NEW.workflow_state NOT IN ('OPEN','ACCEPTED','IN_PROGRESS','WAITING_FEEDBACK','READY_FOR_REVIEW','CLOSED','CANCELLED') OR NEW.workflow_mode IS NULL OR NEW.workflow_mode NOT IN ('PERSONAL','CLINICAL') OR NEW.cycle_no<1 OR NEW.source_type IS NULL OR NEW.source_type NOT IN ('HEALTH_MEASUREMENT','BP_SELF_MONITOR') OR NEW.source_id IS NULL OR NEW.source_id<1 OR NEW.source_revision IS NULL OR NEW.source_revision<1 OR NEW.metric_key IS NULL OR NEW.metric_key NOT IN ('BP','GLUCOSE','SPO2','WEIGHT','HEART_RATE','TEMPERATURE','CUSTOM') OR NEW.classification_json IS NULL OR JSON_VALID(NEW.classification_json)=0)) OR (NEW.current_handling_event_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM alert_event e WHERE e.id=NEW.current_handling_event_id AND e.alert_id=NEW.id AND e.cycle_no=NEW.cycle_no)) OR (NEW.current_feedback_event_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM alert_event e WHERE e.id=NEW.current_feedback_event_id AND e.alert_id=NEW.id AND e.cycle_no=NEW.cycle_no)) OR (NEW.workflow_version=1 AND (NEW.alert_type IS NULL OR NEW.alert_type<>'MEASUREMENT_FOLLOW_UP')) OR (NEW.workflow_version<>1 AND NEW.alert_type='MEASUREMENT_FOLLOW_UP') OR ((OLD.workflow_version=1 OR OLD.alert_type='MEASUREMENT_FOLLOW_UP' OR EXISTS(SELECT 1 FROM alert_event e WHERE e.alert_id=OLD.id AND e.event_type IS NOT NULL)) AND (NEW.id IS NULL OR NEW.id<>OLD.id OR NEW.workflow_version<>1 OR NEW.alert_type IS NULL OR NEW.alert_type<>'MEASUREMENT_FOLLOW_UP' OR NEW.patient_id<>OLD.patient_id OR NEW.user_id<>OLD.user_id OR NEW.source_type<>OLD.source_type OR NEW.source_id<>OLD.source_id OR NEW.metric_key<>OLD.metric_key)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Workflow identity and marker are immutable';
  END IF;
END$$
CREATE TRIGGER `aw57_alert_bd` BEFORE DELETE ON `alert_record` FOR EACH ROW
BEGIN
  IF OLD.workflow_version=1 OR OLD.alert_type='MEASUREMENT_FOLLOW_UP' OR EXISTS(SELECT 1 FROM alert_event e WHERE e.alert_id=OLD.id AND e.event_type IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Workflow alerts retain their history';
  END IF;
END$$
CREATE TRIGGER `aw57_event_bi` BEFORE INSERT ON `alert_event` FOR EACH ROW
BEGIN
  IF (NEW.event_type IS NULL AND EXISTS(SELECT 1 FROM alert_record a WHERE a.id=NEW.alert_id AND (a.workflow_version=1 OR a.alert_type='MEASUREMENT_FOLLOW_UP'))) OR NEW.cycle_no<0 OR (NEW.event_type IS NOT NULL AND (NOT EXISTS(SELECT 1 FROM alert_record a WHERE a.id=NEW.alert_id AND a.patient_id=NEW.patient_id AND a.workflow_version=1 AND a.alert_type='MEASUREMENT_FOLLOW_UP') OR NEW.cycle_no<1 OR (NEW.event_type<>'DUE' AND (NEW.actor_id IS NULL OR NEW.actor_id<1 OR NEW.actor_role IS NULL OR NEW.actor_role='SYSTEM' OR NEW.actor_relation IS NULL)) OR (NEW.event_type='DUE' AND (NEW.actor_id IS NOT NULL OR NEW.actor_name IS NOT NULL OR COALESCE(NEW.actor_role,'')<>'SYSTEM' OR COALESCE(NEW.actor_relation,'')<>'SCHEDULER' OR NEW.entry_mode IS NOT NULL OR NEW.note IS NOT NULL OR NEW.occurred_at IS NULL OR NEW.recorded_at IS NULL OR NEW.occurred_at<>NEW.recorded_at OR NEW.from_status IS NULL OR NEW.from_status<>NEW.to_status OR NOT EXISTS(SELECT 1 FROM alert_record d WHERE d.id=NEW.alert_id AND d.patient_id=NEW.patient_id AND d.cycle_no=NEW.cycle_no AND d.workflow_state=NEW.to_status AND d.workflow_state NOT IN ('CLOSED','CANCELLED') AND d.due_at IS NOT NULL AND d.due_at<=NEW.recorded_at AND JSON_TYPE(NEW.payload_json)='OBJECT' AND JSON_LENGTH(NEW.payload_json)=2 AND CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(NEW.payload_json,'$.dueKey')),'') AS BINARY)=CONCAT('due:',d.id,':',d.cycle_no,':',CAST((TIMESTAMPDIFF(MICROSECOND,'1970-01-01 00:00:00',d.due_at)-MICROSECOND(d.due_at))/1000000 AS SIGNED),'.',LPAD(MICROSECOND(d.due_at),6,'0')) AND CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(NEW.payload_json,'$.dueAt')),'') AS BINARY)=CASE WHEN MICROSECOND(d.due_at)=0 THEN DATE_FORMAT(d.due_at,'%Y-%m-%dT%H:%i:%sZ') WHEN MOD(MICROSECOND(d.due_at),1000)=0 THEN CONCAT(DATE_FORMAT(d.due_at,'%Y-%m-%dT%H:%i:%s.'),LEFT(LPAD(MICROSECOND(d.due_at),6,'0'),3),'Z') ELSE DATE_FORMAT(d.due_at,'%Y-%m-%dT%H:%i:%s.%fZ') END))) OR NEW.recorded_at IS NULL OR NEW.payload_json IS NULL OR JSON_VALID(NEW.payload_json)=0 OR (NEW.entry_mode IS NOT NULL AND NEW.entry_mode NOT IN ('SELF','ASSISTED')) OR (NEW.occurred_at IS NOT NULL AND NEW.occurred_at>NEW.recorded_at))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid alert workflow event';
  END IF;
END$$
CREATE TRIGGER `aw57_event_bu` BEFORE UPDATE ON `alert_event` FOR EACH ROW
BEGIN
  IF OLD.event_type IS NOT NULL OR NEW.event_type IS NOT NULL OR EXISTS(SELECT 1 FROM alert_record a WHERE a.id=OLD.alert_id AND (a.workflow_version=1 OR a.alert_type='MEASUREMENT_FOLLOW_UP')) OR EXISTS(SELECT 1 FROM alert_record a WHERE a.id=NEW.alert_id AND (a.workflow_version=1 OR a.alert_type='MEASUREMENT_FOLLOW_UP')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Workflow events are append-only';
  END IF;
END$$
CREATE TRIGGER `aw57_event_bd` BEFORE DELETE ON `alert_event` FOR EACH ROW
BEGIN
  IF OLD.event_type IS NOT NULL OR EXISTS(SELECT 1 FROM alert_record a WHERE a.id=OLD.alert_id AND (a.workflow_version=1 OR a.alert_type='MEASUREMENT_FOLLOW_UP')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Workflow events are append-only';
  END IF;
END$$
CREATE TRIGGER `aw57_source_bi` BEFORE INSERT ON `alert_source_identity` FOR EACH ROW
BEGIN
  IF NEW.source_type NOT IN ('HEALTH_MEASUREMENT','BP_SELF_MONITOR') OR NEW.source_id<1 OR NEW.current_revision<1 OR NEW.lock_version<0 OR NEW.status NOT IN ('ACTIVE','INVALIDATED') OR (NEW.source_type='HEALTH_MEASUREMENT' AND NOT EXISTS(SELECT 1 FROM health_measurement m WHERE m.id=NEW.source_id AND m.patient_id=NEW.patient_id)) OR (NEW.source_type='BP_SELF_MONITOR' AND NOT EXISTS(SELECT 1 FROM bp_self_monitor_record m WHERE m.id=NEW.source_id AND m.patient_id=NEW.patient_id)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid source identity or patient ownership';
  END IF;
END$$
CREATE TRIGGER `aw57_source_bu` BEFORE UPDATE ON `alert_source_identity` FOR EACH ROW
BEGIN
  IF NEW.source_type NOT IN ('HEALTH_MEASUREMENT','BP_SELF_MONITOR') OR NEW.source_id<1 OR NEW.current_revision<1 OR NEW.lock_version<0 OR NEW.status NOT IN ('ACTIVE','INVALIDATED') OR (NEW.source_type='HEALTH_MEASUREMENT' AND NOT EXISTS(SELECT 1 FROM health_measurement m WHERE m.id=NEW.source_id AND m.patient_id=NEW.patient_id)) OR (NEW.source_type='BP_SELF_MONITOR' AND NOT EXISTS(SELECT 1 FROM bp_self_monitor_record m WHERE m.id=NEW.source_id AND m.patient_id=NEW.patient_id)) OR NEW.source_type<>OLD.source_type OR NEW.source_id<>OLD.source_id OR NEW.patient_id<>OLD.patient_id OR NEW.current_revision<OLD.current_revision OR NOT EXISTS(SELECT 1 FROM alert_source_revision r WHERE r.source_identity_id=NEW.id AND r.revision_no=NEW.current_revision AND r.source_status=NEW.status) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid source revision or immutable identity';
  END IF;
END$$
CREATE TRIGGER `aw57_revision_bi` BEFORE INSERT ON `alert_source_revision` FOR EACH ROW
BEGIN
  IF NEW.action_versions_json IS NULL OR JSON_VALID(NEW.action_versions_json)=0 OR JSON_TYPE(CASE WHEN JSON_VALID(NEW.action_versions_json)=1 THEN NEW.action_versions_json ELSE 'null' END)<>'OBJECT' OR (NEW.revision_no=1 AND NEW.action_versions_json<>'{}') OR NEW.revision_no<1 OR NEW.recorded_by<1 OR JSON_VALID(NEW.snapshot_json)=0 OR NEW.occurred_at>NEW.recorded_at OR NEW.source_status NOT IN ('ACTIVE','INVALIDATED') OR (NEW.revision_no=1 AND NEW.source_status<>'ACTIVE') OR (NEW.revision_no>1 AND (NEW.correction_reason IS NULL OR CHAR_LENGTH(TRIM(NEW.correction_reason))=0)) OR CHAR_LENGTH(NEW.correction_reason)>1000 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid source snapshot';
  END IF;
END$$
CREATE TRIGGER `aw57_revision_bu` BEFORE UPDATE ON `alert_source_revision` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source snapshots are immutable';
END$$
CREATE TRIGGER `aw57_revision_bd` BEFORE DELETE ON `alert_source_revision` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source snapshots are immutable';
END$$
CREATE TRIGGER `aw57_link_bi` BEFORE INSERT ON `alert_source_link` FOR EACH ROW
BEGIN
  IF NEW.source_revision<1 OR NEW.metric_key NOT IN ('BP','GLUCOSE','SPO2','WEIGHT','HEART_RATE','TEMPERATURE','CUSTOM') OR NOT EXISTS(SELECT 1 FROM alert_source_identity s JOIN alert_record a ON a.patient_id=s.patient_id WHERE s.id=NEW.source_identity_id AND s.source_type=NEW.source_type AND s.source_id=NEW.source_id AND a.id=NEW.alert_id AND a.workflow_version=1 AND a.alert_type='MEASUREMENT_FOLLOW_UP' AND a.source_type=NEW.source_type AND a.source_id=NEW.source_id AND a.metric_key=NEW.metric_key) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid source metric link';
  END IF;
END$$
CREATE TRIGGER `aw57_link_bu` BEFORE UPDATE ON `alert_source_link` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source links are append-only';
END$$
CREATE TRIGGER `aw57_link_bd` BEFORE DELETE ON `alert_source_link` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source links are append-only';
END$$
CREATE TRIGGER `aw57_evidence_bi` BEFORE INSERT ON `alert_evidence` FOR EACH ROW
BEGIN
  IF NEW.source_type NOT IN ('HEALTH_MEASUREMENT','BP_SELF_MONITOR','MEDICAL_RECORD','CARE_PLAN_ACTION') OR NEW.source_id<1 OR NEW.dependency_state NOT IN ('VALID','REFERENCE_CHANGED','WITHDRAWN') OR (NEW.source_revision IS NOT NULL AND NEW.source_revision<1) OR (NEW.dependency_version IS NOT NULL AND NEW.dependency_version<0) OR (NEW.source_type IN ('HEALTH_MEASUREMENT','BP_SELF_MONITOR') AND NEW.source_revision IS NULL) OR (NEW.source_type='CARE_PLAN_ACTION' AND NEW.dependency_version IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid alert evidence reference';
  END IF;
END$$
CREATE TRIGGER `aw57_evidence_bu` BEFORE UPDATE ON `alert_evidence` FOR EACH ROW
BEGIN
  IF NEW.source_type NOT IN ('HEALTH_MEASUREMENT','BP_SELF_MONITOR','MEDICAL_RECORD','CARE_PLAN_ACTION') OR NEW.source_id<1 OR NEW.dependency_state NOT IN ('VALID','REFERENCE_CHANGED','WITHDRAWN') OR (NEW.source_revision IS NOT NULL AND NEW.source_revision<1) OR (NEW.dependency_version IS NOT NULL AND NEW.dependency_version<0) OR (NEW.source_type IN ('HEALTH_MEASUREMENT','BP_SELF_MONITOR') AND NEW.source_revision IS NULL) OR (NEW.source_type='CARE_PLAN_ACTION' AND NEW.dependency_version IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid alert evidence reference';
  END IF;
END$$
CREATE TRIGGER `aw57_command_bi` BEFORE INSERT ON `alert_command` FOR EACH ROW
BEGIN
  IF NEW.actor_id<1 OR NEW.expected_version<0 OR NEW.command_key NOT REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' OR NEW.payload_hash NOT REGEXP '^[0-9a-f]{64}$' OR (NEW.alert_id IS NOT NULL AND NEW.alert_id<1) OR (NEW.result_json IS NOT NULL AND (JSON_VALID(NEW.result_json)=0 OR NEW.alert_id IS NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid durable workflow command';
  END IF;
END$$
CREATE TRIGGER `aw57_command_bu` BEFORE UPDATE ON `alert_command` FOR EACH ROW
BEGIN
  IF NEW.actor_id<1 OR NEW.expected_version<0 OR NEW.command_key NOT REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' OR NEW.payload_hash NOT REGEXP '^[0-9a-f]{64}$' OR (NEW.alert_id IS NOT NULL AND NEW.alert_id<1) OR (NEW.result_json IS NOT NULL AND (JSON_VALID(NEW.result_json)=0 OR NEW.alert_id IS NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid durable workflow command';
  END IF;
END$$
CREATE TRIGGER `aw57_measurement_bi` BEFORE INSERT ON `measurement_command` FOR EACH ROW
BEGIN
  IF NEW.actor_id<1 OR NEW.expected_version<0 OR NEW.command_key NOT REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' OR NEW.payload_hash NOT REGEXP '^[0-9a-f]{64}$' OR (NEW.source_id IS NOT NULL AND NEW.source_id<1) OR (NEW.result_json IS NOT NULL AND (JSON_VALID(NEW.result_json)=0 OR NEW.source_id IS NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid durable workflow command';
  END IF;
END$$
CREATE TRIGGER `aw57_measurement_bu` BEFORE UPDATE ON `measurement_command` FOR EACH ROW
BEGIN
  IF NEW.actor_id<1 OR NEW.expected_version<0 OR NEW.command_key NOT REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' OR NEW.payload_hash NOT REGEXP '^[0-9a-f]{64}$' OR (NEW.source_id IS NOT NULL AND NEW.source_id<1) OR (NEW.result_json IS NOT NULL AND (JSON_VALID(NEW.result_json)=0 OR NEW.source_id IS NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid durable workflow command';
  END IF;
END$$
CREATE TRIGGER `aw57_notification_bi` BEFORE INSERT ON `alert_notification` FOR EACH ROW
BEGIN
  IF NEW.attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(NEW.dispatch_key)=0 OR NEW.status NOT IN ('QUEUED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED') OR NOT EXISTS(SELECT 1 FROM alert_event e WHERE e.id=NEW.event_id AND e.patient_id=NEW.patient_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid alert delivery job';
  END IF;
END$$
CREATE TRIGGER `aw57_notification_bu` BEFORE UPDATE ON `alert_notification` FOR EACH ROW
BEGIN
  IF NEW.attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(NEW.dispatch_key)=0 OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED') OR NOT EXISTS(SELECT 1 FROM alert_event e WHERE e.id=NEW.event_id AND e.patient_id=NEW.patient_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid alert delivery job';
  END IF;
END$$
DELIMITER ;
