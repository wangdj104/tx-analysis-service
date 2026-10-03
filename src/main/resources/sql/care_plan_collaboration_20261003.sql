-- Additive MySQL 8.0 migration. Back up the entire database and attachments first.
-- Apply after init.sql, doctor_workspace_20260921.sql and care_platform_upgrade_20260921.sql.
-- Existing ACTIVE plans stay workflow_version=0 with NULL collaboration lifecycle.
-- All new DATETIME(6) values are UTC: applications must explicitly bind/read UTC,
-- never rely on server/session timezone or CURRENT_TIMESTAMP for this subsystem.
-- No patient grants, nurse user roles, assignments, actions or notifications are seeded.

SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='workflow_version'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN workflow_version TINYINT NOT NULL DEFAULT 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='lifecycle'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN lifecycle VARCHAR(20) DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='current_revision_id'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN current_revision_id BIGINT DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='draft_revision_id'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN draft_revision_id BIGINT DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='legacy_source_id'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN legacy_source_id BIGINT DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='lock_version'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='closed_at'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN closed_at DATETIME(6) DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='cancelled_at'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN cancelled_at DATETIME(6) DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='cancel_reason'),'SELECT 1','ALTER TABLE doctor_care_plan ADD COLUMN cancel_reason VARCHAR(1000) DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `care_plan_revision` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `revision_no` INT NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  `title` VARCHAR(160) NOT NULL,
  `instructions` TEXT NOT NULL,
  `plan_type` VARCHAR(40) NOT NULL,
  `draft_json` LONGTEXT NOT NULL COMMENT 'Action inputs; frozen with clinical snapshot when published',
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  `published_by` BIGINT DEFAULT NULL,
  `published_at` DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_care_plan_revision_no` (`plan_id`,`revision_no`),
  CONSTRAINT `fk_care_plan_revision_plan` FOREIGN KEY (`plan_id`) REFERENCES `doctor_care_plan` (`id`),
  CONSTRAINT `ck_care_plan_revision_no` CHECK (`revision_no` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Draft and immutable published clinical snapshots';

CREATE TABLE IF NOT EXISTS `care_plan_action` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `revision_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `ordinal` INT NOT NULL,
  `instruction` TEXT NOT NULL,
  `due_at` DATETIME(6) NOT NULL,
  `assigned_user_id` BIGINT NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  `lock_version` BIGINT NOT NULL DEFAULT 0,
  `first_submitted_at` DATETIME(6) DEFAULT NULL,
  `latest_submitted_at` DATETIME(6) DEFAULT NULL,
  `review_waiting_since` DATETIME(6) DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_care_plan_action_ordinal` (`revision_id`,`ordinal`),
  KEY `idx_care_plan_action_patient_queue` (`patient_id`,`status`,`due_at`),
  KEY `idx_care_plan_action_plan` (`plan_id`,`status`),
  CONSTRAINT `fk_care_plan_action_plan` FOREIGN KEY (`plan_id`) REFERENCES `doctor_care_plan` (`id`),
  CONSTRAINT `fk_care_plan_action_revision` FOREIGN KEY (`revision_id`) REFERENCES `care_plan_revision` (`id`),
  CONSTRAINT `fk_care_plan_action_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`),
  CONSTRAINT `ck_care_plan_action_ordinal` CHECK (`ordinal` BETWEEN 1 AND 50),
  CONSTRAINT `ck_care_plan_action_version` CHECK (`lock_version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Published one-time action snapshots';

CREATE TABLE IF NOT EXISTS `care_plan_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `plan_id` BIGINT NOT NULL,
  `revision_id` BIGINT DEFAULT NULL,
  `action_id` BIGINT DEFAULT NULL,
  `actor_id` BIGINT NOT NULL,
  `actor_name` VARCHAR(100) NOT NULL,
  `actor_role` VARCHAR(50) NOT NULL,
  `actor_relation` VARCHAR(100) DEFAULT NULL,
  `entry_mode` VARCHAR(20) DEFAULT NULL,
  `event_type` VARCHAR(40) NOT NULL,
  `note` TEXT,
  `occurred_at` DATETIME(6) DEFAULT NULL,
  `recorded_at` DATETIME(6) NOT NULL,
  `payload_json` LONGTEXT NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_care_plan_event_plan` (`plan_id`,`id`),
  KEY `idx_care_plan_event_action` (`action_id`,`id`),
  KEY `idx_care_plan_event_patient_time` (`patient_id`,`recorded_at`,`id`),
  CONSTRAINT `fk_care_plan_event_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`),
  CONSTRAINT `fk_care_plan_event_plan` FOREIGN KEY (`plan_id`) REFERENCES `doctor_care_plan` (`id`),
  CONSTRAINT `fk_care_plan_event_revision` FOREIGN KEY (`revision_id`) REFERENCES `care_plan_revision` (`id`),
  CONSTRAINT `fk_care_plan_event_action` FOREIGN KEY (`action_id`) REFERENCES `care_plan_action` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Append-only audit; actor identity is a durable snapshot without a user FK';

CREATE TABLE IF NOT EXISTS `care_plan_evidence` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT NOT NULL,
  `source_type` VARCHAR(30) NOT NULL COMMENT 'MEASUREMENT=health_measurement; MEDICAL_RECORD=medical_record',
  `source_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_care_plan_evidence_ref` (`event_id`,`source_type`,`source_id`),
  CONSTRAINT `fk_care_plan_evidence_event` FOREIGN KEY (`event_id`) REFERENCES `care_plan_event` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Original record IDs only; no copied clinical titles or content';

CREATE TABLE IF NOT EXISTS `care_nurse_assignment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `nurse_user_id` BIGINT NOT NULL,
  `assigned_by` BIGINT NOT NULL,
  `assigned_at` DATETIME(6) NOT NULL,
  `expires_at` DATETIME(6) DEFAULT NULL,
  `revoked_at` DATETIME(6) DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_care_nurse_patient_user` (`patient_id`,`nurse_user_id`),
  KEY `idx_care_nurse_user_status` (`nurse_user_id`,`status`,`expires_at`),
  CONSTRAINT `fk_care_nurse_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Team relationship only; never implies a patient module grant';

CREATE TABLE IF NOT EXISTS `care_plan_notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `recipient_user_id` BIGINT NOT NULL,
  `channel_id` BIGINT DEFAULT NULL COMMENT 'NULL is permitted for NO_CHANNEL',
  `dispatch_key` VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Application-generated event/recipient/channel identity, including NO_CHANNEL',
  `status` VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `next_attempt_at` DATETIME(6) DEFAULT NULL,
  `claimed_at` DATETIME(6) DEFAULT NULL,
  `claim_token` VARCHAR(64) DEFAULT NULL,
  `request_id` VARCHAR(64) DEFAULT NULL,
  `last_result` VARCHAR(1000) DEFAULT NULL COMMENT 'Sanitized transport result; never webhook credentials or clinical content',
  `delivered_at` DATETIME(6) DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_care_plan_notification_dispatch` (`dispatch_key`),
  KEY `idx_care_plan_notification_due` (`status`,`next_attempt_at`,`id`),
  CONSTRAINT `fk_care_plan_notification_event` FOREIGN KEY (`event_id`) REFERENCES `care_plan_event` (`id`),
  CONSTRAINT `fk_care_plan_notification_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`),
  CONSTRAINT `ck_care_plan_notification_attempts` CHECK (`attempt_count` BETWEEN 0 AND 3),
  CONSTRAINT `ck_care_plan_notification_dispatch` CHECK (CHAR_LENGTH(`dispatch_key`) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable delivery jobs; UNKNOWN is not automatically retried';

CREATE TABLE IF NOT EXISTS `care_plan_command` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `command_key` VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `plan_id` BIGINT DEFAULT NULL COMMENT 'NULL only while a create command reserves its key in the same transaction',
  `expected_version` BIGINT NOT NULL,
  `payload_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result_json` LONGTEXT DEFAULT NULL COMMENT 'Filled with plan_id before the reservation transaction commits',
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_care_plan_command_actor_key` (`actor_id`,`command_key`),
  CONSTRAINT `fk_care_plan_command_plan` FOREIGN KEY (`plan_id`) REFERENCES `doctor_care_plan` (`id`),
  CONSTRAINT `ck_care_plan_command_version` CHECK (`expected_version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Command deduplication; current authorization must be checked before replay';

-- Add circular references only after the revision table exists. Never cascade-delete history.
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='doctor_care_plan' AND constraint_name='fk_doctor_plan_current_revision'),'SELECT 1','ALTER TABLE doctor_care_plan ADD CONSTRAINT fk_doctor_plan_current_revision FOREIGN KEY (current_revision_id) REFERENCES care_plan_revision (id)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='doctor_care_plan' AND constraint_name='fk_doctor_plan_draft_revision'),'SELECT 1','ALTER TABLE doctor_care_plan ADD CONSTRAINT fk_doctor_plan_draft_revision FOREIGN KEY (draft_revision_id) REFERENCES care_plan_revision (id)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='doctor_care_plan' AND constraint_name='fk_doctor_plan_legacy_source'),'SELECT 1','ALTER TABLE doctor_care_plan ADD CONSTRAINT fk_doctor_plan_legacy_source FOREIGN KEY (legacy_source_id) REFERENCES doctor_care_plan (id)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `sys_role` (`role_code`,`role_name`,`description`,`status`)
SELECT 'nurse','Nurse','Assigned care-plan follow-up; patient module grant also required',1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code='nurse');
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
SELECT 0,'Nursing Queue','care-plan-nursing','/nurse-workspace','FirstAidKit','care-plan:nursing:view',1,2,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_code='care-plan-nursing');
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
SELECT 8,'Manage Nurse Assignments','care-plan-nurse-assignments',NULL,'User','care-plan:nurse-assignment:manage',2,90,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_code='care-plan-nurse-assignments');
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code='care-plan-nursing' WHERE r.role_code='nurse'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code='care-plan-nurse-assignments' WHERE r.role_code='admin'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
