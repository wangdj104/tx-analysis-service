-- Chengxin Health full care-platform upgrade.
-- Idempotent table creation; safe to run after taking a database backup.

CREATE TABLE IF NOT EXISTS `health_measurement` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `recorded_by` BIGINT NOT NULL,
  `metric_type` VARCHAR(40) NOT NULL COMMENT 'BP/GLUCOSE/SPO2/WEIGHT/HEART_RATE/TEMPERATURE/CUSTOM',
  `metric_name` VARCHAR(100) DEFAULT NULL,
  `value_primary` DECIMAL(12,3) NOT NULL,
  `value_secondary` DECIMAL(12,3) DEFAULT NULL,
  `unit` VARCHAR(30) NOT NULL,
  `measured_at` DATETIME NOT NULL,
  `source_type` VARCHAR(30) DEFAULT 'MANUAL',
  `status` VARCHAR(20) DEFAULT 'NORMAL',
  `remark` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_measurement_patient_time` (`patient_id`,`measured_at`),
  KEY `idx_measurement_type_time` (`patient_id`,`metric_type`,`measured_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Unified patient-entered health measurements';

CREATE TABLE IF NOT EXISTS `measurement_annotation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `measurement_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `doctor_user_id` BIGINT NOT NULL,
  `annotation` VARCHAR(1000) NOT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_annotation_measurement` (`measurement_id`,`created_at`),
  KEY `idx_annotation_patient` (`patient_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Clinician annotations on measurement trend points';

CREATE TABLE IF NOT EXISTS `care_access_grant` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `grantee_user_id` BIGINT NOT NULL,
  `grantee_role` VARCHAR(30) NOT NULL COMMENT 'DOCTOR/FAMILY/GUARDIAN',
  `access_level` VARCHAR(20) NOT NULL DEFAULT 'READ' COMMENT 'READ/WRITE/PROXY',
  `visible_modules` VARCHAR(1000) DEFAULT NULL COMMENT 'Comma-separated module codes; empty means all',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `granted_by` BIGINT NOT NULL,
  `expires_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_access_patient_user_role` (`patient_id`,`grantee_user_id`,`grantee_role`),
  KEY `idx_access_grantee` (`grantee_user_id`,`status`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Patient-controlled clinician and caregiver access grants';

CREATE TABLE IF NOT EXISTS `doctor_schedule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `doctor_user_id` BIGINT NOT NULL,
  `work_date` DATE NOT NULL,
  `start_time` TIME NOT NULL,
  `end_time` TIME NOT NULL,
  `slot_minutes` INT NOT NULL DEFAULT 30,
  `location` VARCHAR(160) DEFAULT NULL,
  `consultation_modes` VARCHAR(100) DEFAULT 'IN_PERSON,TEXT,VOICE,VIDEO',
  `status` VARCHAR(20) DEFAULT 'AVAILABLE',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_doctor_schedule` (`doctor_user_id`,`work_date`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Clinician availability and appointment slot source';

CREATE TABLE IF NOT EXISTS `care_appointment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `doctor_user_id` BIGINT NOT NULL,
  `schedule_id` BIGINT DEFAULT NULL,
  `appointment_type` VARCHAR(30) DEFAULT 'FOLLOW_UP',
  `consultation_mode` VARCHAR(20) DEFAULT 'IN_PERSON',
  `start_at` DATETIME NOT NULL,
  `end_at` DATETIME NOT NULL,
  `status` VARCHAR(20) DEFAULT 'BOOKED',
  `reason` VARCHAR(500) DEFAULT NULL,
  `recurrence_days` INT DEFAULT NULL,
  `next_follow_up_at` DATETIME DEFAULT NULL,
  `created_by` BIGINT NOT NULL,
  `cancel_reason` VARCHAR(500) DEFAULT NULL,
  `notified_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_appointment_patient` (`patient_id`,`start_at`,`status`),
  KEY `idx_appointment_doctor` (`doctor_user_id`,`start_at`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Conflict-checked appointments and recurring follow-up plans';

CREATE TABLE IF NOT EXISTS `visit_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `appointment_id` BIGINT DEFAULT NULL,
  `patient_id` BIGINT NOT NULL,
  `doctor_user_id` BIGINT NOT NULL,
  `visited_at` DATETIME NOT NULL,
  `diagnosis_summary` TEXT,
  `treatment_summary` TEXT,
  `follow_up_advice` TEXT,
  `follow_up_at` DATETIME DEFAULT NULL,
  `status` VARCHAR(20) DEFAULT 'DRAFT',
  `published_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_visit_patient_time` (`patient_id`,`visited_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Structured outpatient and follow-up visit summaries';

CREATE TABLE IF NOT EXISTS `electronic_prescription` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `visit_id` BIGINT DEFAULT NULL,
  `patient_id` BIGINT NOT NULL,
  `doctor_user_id` BIGINT NOT NULL,
  `version_no` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(20) DEFAULT 'ACTIVE',
  `instructions` VARCHAR(1000) DEFAULT NULL,
  `published_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_prescription_patient` (`patient_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Archived clinician orders; no pharmacy integration';

CREATE TABLE IF NOT EXISTS `electronic_prescription_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `prescription_id` BIGINT NOT NULL,
  `drug_name` VARCHAR(160) NOT NULL,
  `dosage` VARCHAR(100) NOT NULL,
  `frequency` VARCHAR(100) NOT NULL,
  `administration_route` VARCHAR(50) DEFAULT NULL,
  `duration_days` INT DEFAULT NULL,
  `remark` VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_prescription_item` (`prescription_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Electronic prescription line items';

CREATE TABLE IF NOT EXISTS `consultation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `doctor_user_id` BIGINT DEFAULT NULL,
  `mode` VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/VOICE/VIDEO',
  `status` VARCHAR(20) DEFAULT 'OPEN',
  `symptom` VARCHAR(500) NOT NULL,
  `duration_text` VARCHAR(100) DEFAULT NULL,
  `medical_history` TEXT,
  `transfer_advice` VARCHAR(1000) DEFAULT NULL,
  `family_visibility` VARCHAR(20) DEFAULT 'VISIBLE' COMMENT 'VISIBLE/PRIVATE',
  `started_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `ended_at` DATETIME DEFAULT NULL,
  `archived_event_id` BIGINT DEFAULT NULL,
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_consultation_patient` (`patient_id`,`started_at`),
  KEY `idx_consultation_doctor` (`doctor_user_id`,`status`,`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Three-party text, voice, or video consultation session';

CREATE TABLE IF NOT EXISTS `consultation_participant` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `consultation_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `participant_role` VARCHAR(20) NOT NULL,
  `joined_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consult_participant` (`consultation_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Patient, clinician, and family participants';

CREATE TABLE IF NOT EXISTS `consultation_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `consultation_id` BIGINT NOT NULL,
  `sender_user_id` BIGINT NOT NULL,
  `message_type` VARCHAR(20) DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/FILE/VOICE/SYSTEM',
  `content` TEXT,
  `attachment_record_id` BIGINT DEFAULT NULL,
  `sent_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_consult_message` (`consultation_id`,`sent_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persisted consultation transcript and attachments';

CREATE TABLE IF NOT EXISTS `consultation_signal` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `consultation_id` BIGINT NOT NULL,
  `sender_user_id` BIGINT NOT NULL,
  `target_user_id` BIGINT NOT NULL,
  `signal_type` VARCHAR(20) NOT NULL COMMENT 'OFFER/ANSWER/ICE/HANGUP',
  `payload_json` LONGTEXT,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_consult_signal_target` (`consultation_id`,`target_user_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Short-lived WebRTC signaling for multi-party consultations';

CREATE TABLE IF NOT EXISTS `treatment_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `doctor_user_id` BIGINT NOT NULL,
  `plan_type` VARCHAR(30) NOT NULL COMMENT 'INPATIENT/SURGERY/REHAB/DISCHARGE',
  `title` VARCHAR(200) NOT NULL,
  `plan_json` LONGTEXT NOT NULL,
  `start_at` DATETIME DEFAULT NULL,
  `end_at` DATETIME DEFAULT NULL,
  `status` VARCHAR(20) DEFAULT 'ACTIVE',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_treatment_plan_patient` (`patient_id`,`status`,`start_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Inpatient, surgery, discharge, and rehabilitation plans';

CREATE TABLE IF NOT EXISTS `rehab_checkin` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT DEFAULT NULL,
  `patient_id` BIGINT NOT NULL,
  `recorded_by` BIGINT NOT NULL,
  `record_type` VARCHAR(30) NOT NULL COMMENT 'EXERCISE/WOUND/DRAIN/SYMPTOM',
  `completion_percent` INT DEFAULT NULL,
  `video_url` VARCHAR(500) DEFAULT NULL,
  `data_json` LONGTEXT NOT NULL,
  `abnormal` TINYINT DEFAULT 0,
  `recorded_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rehab_patient` (`patient_id`,`recorded_at`),
  KEY `idx_rehab_abnormal` (`patient_id`,`abnormal`,`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Rehabilitation completion, wound, drain, and symptom observations';

CREATE TABLE IF NOT EXISTS `emergency_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `triggered_by` BIGINT NOT NULL,
  `latitude` DECIMAL(10,7) DEFAULT NULL,
  `longitude` DECIMAL(10,7) DEFAULT NULL,
  `location_text` VARCHAR(500) DEFAULT NULL,
  `snapshot_json` LONGTEXT NOT NULL,
  `status` VARCHAR(20) DEFAULT 'TRIGGERED',
  `notified_user_ids` VARCHAR(1000) DEFAULT NULL,
  `triggered_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `resolved_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_emergency_patient` (`patient_id`,`triggered_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Emergency calls with immutable clinical snapshot and location';

CREATE TABLE IF NOT EXISTS `growth_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `recorded_by` BIGINT NOT NULL,
  `record_date` DATE NOT NULL,
  `height_cm` DECIMAL(6,2) DEFAULT NULL,
  `weight_kg` DECIMAL(6,2) DEFAULT NULL,
  `head_circumference_cm` DECIMAL(6,2) DEFAULT NULL,
  `height_percentile` DECIMAL(5,2) DEFAULT NULL,
  `weight_percentile` DECIMAL(5,2) DEFAULT NULL,
  `reference_standard` VARCHAR(100) DEFAULT 'WHO',
  `remark` VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_growth_patient` (`patient_id`,`record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Child growth observations and standard percentiles';

CREATE TABLE IF NOT EXISTS `vaccination_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `vaccine_name` VARCHAR(160) NOT NULL,
  `dose_no` VARCHAR(30) DEFAULT NULL,
  `planned_date` DATE NOT NULL,
  `completed_date` DATE DEFAULT NULL,
  `status` VARCHAR(20) DEFAULT 'PLANNED',
  `remind_at` DATETIME DEFAULT NULL,
  `notified_at` DATETIME DEFAULT NULL,
  `recorded_by` BIGINT NOT NULL,
  `remark` VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_vaccine_due` (`patient_id`,`status`,`planned_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Manually maintained vaccination schedule';

CREATE TABLE IF NOT EXISTS `maternity_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `recorded_by` BIGINT NOT NULL,
  `record_type` VARCHAR(30) NOT NULL COMMENT 'PRENATAL/DELIVERY/POSTPARTUM',
  `record_date` DATE NOT NULL,
  `gestational_week` DECIMAL(4,1) DEFAULT NULL,
  `title` VARCHAR(200) NOT NULL,
  `data_json` LONGTEXT NOT NULL,
  `remark` VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_maternity_patient` (`patient_id`,`record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prenatal, delivery, and postpartum timeline';

CREATE TABLE IF NOT EXISTS `mental_assessment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `scale_code` VARCHAR(30) NOT NULL,
  `answers_json` LONGTEXT NOT NULL,
  `score` DECIMAL(8,2) NOT NULL,
  `severity` VARCHAR(20) NOT NULL,
  `family_visibility` VARCHAR(20) DEFAULT 'PRIVATE',
  `submitted_by` BIGINT NOT NULL,
  `submitted_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `doctor_notified_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mental_patient` (`patient_id`,`submitted_at`),
  KEY `idx_mental_severity` (`severity`,`doctor_notified_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scheduled mental-health self assessments with privacy control';

CREATE TABLE IF NOT EXISTS `mental_assessment_schedule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `scale_code` VARCHAR(30) NOT NULL,
  `interval_days` INT NOT NULL DEFAULT 14,
  `next_due_at` DATETIME NOT NULL,
  `family_visibility` VARCHAR(20) DEFAULT 'PRIVATE',
  `enabled` TINYINT DEFAULT 1,
  `last_notified_at` DATETIME DEFAULT NULL,
  `created_by` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mental_schedule_due` (`enabled`,`next_due_at`),
  KEY `idx_mental_schedule_patient` (`patient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Recurring mental-health assessment delivery schedule';

CREATE TABLE IF NOT EXISTS `patient_group` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `doctor_user_id` BIGINT NOT NULL,
  `group_name` VARCHAR(120) NOT NULL,
  `description` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_patient_group_doctor_name` (`doctor_user_id`,`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Clinician-owned patient groups';

CREATE TABLE IF NOT EXISTS `patient_group_member` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `group_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_patient_group_member` (`group_id`,`patient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Patient membership in clinician groups';

CREATE TABLE IF NOT EXISTS `notification_delivery_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `recipient_user_id` BIGINT NOT NULL,
  `patient_id` BIGINT DEFAULT NULL,
  `event_type` VARCHAR(40) NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `delivery_status` VARCHAR(20) NOT NULL,
  `detail` VARCHAR(1000) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_delivery_recipient` (`recipient_user_id`,`created_at`),
  KEY `idx_delivery_event` (`event_type`,`delivery_status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Auditable notification routing outcomes';

SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_member' AND column_name='access_level'),'SELECT 1','ALTER TABLE care_member ADD COLUMN access_level VARCHAR(20) NOT NULL DEFAULT ''WRITE'' AFTER relation_name'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Compatibility upgrades for installations created before clinical import and monitoring were added.
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bp_self_monitor_record' AND column_name='source_type'),'SELECT 1','ALTER TABLE bp_self_monitor_record ADD COLUMN source_type VARCHAR(20) DEFAULT ''MANUAL'' AFTER measure_period'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bp_self_monitor_record' AND column_name='source_external_id'),'SELECT 1','ALTER TABLE bp_self_monitor_record ADD COLUMN source_external_id VARCHAR(120) DEFAULT NULL AFTER source_type'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bp_self_monitor_record' AND column_name='verification_status'),'SELECT 1','ALTER TABLE bp_self_monitor_record ADD COLUMN verification_status VARCHAR(30) DEFAULT ''VERIFIED'' AFTER source_external_id'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='source_type'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN source_type VARCHAR(20) DEFAULT ''MANUAL'' AFTER ai_raw_result'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='source_external_id'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN source_external_id VARCHAR(120) DEFAULT NULL AFTER source_type'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='verification_status'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN verification_status VARCHAR(30) DEFAULT ''VERIFIED'' AFTER source_external_id'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='confidence_score'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN confidence_score DECIMAL(5,4) DEFAULT NULL AFTER verification_status'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='verified_by'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN verified_by BIGINT DEFAULT NULL AFTER confidence_score'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='verified_at'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN verified_at DATETIME DEFAULT NULL AFTER verified_by'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dialysis_record' AND column_name='session_minutes'),'SELECT 1','ALTER TABLE dialysis_record ADD COLUMN session_minutes INT DEFAULT NULL AFTER uf_amount'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dialysis_record' AND column_name='ktv'),'SELECT 1','ALTER TABLE dialysis_record ADD COLUMN ktv DECIMAL(5,2) DEFAULT NULL AFTER session_minutes'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dialysis_record' AND column_name='urr'),'SELECT 1','ALTER TABLE dialysis_record ADD COLUMN urr DECIMAL(5,2) DEFAULT NULL AFTER ktv'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dialysis_record' AND column_name='access_issue'),'SELECT 1','ALTER TABLE dialysis_record ADD COLUMN access_issue VARCHAR(255) DEFAULT NULL AFTER urr'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `sys_menu` (`id`,`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
VALUES (40,0,'Care Journey','care-journey','/care-journey','FirstAidKit','care:journey:view',1,2,1)
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),menu_path=VALUES(menu_path),menu_icon=VALUES(menu_icon),permission=VALUES(permission),sort_order=VALUES(sort_order),status=VALUES(status);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,40 FROM sys_role r WHERE r.role_code IN ('admin','doctor','patient','family','user')
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='patient_clinical' AND column_name='blood_type'),'SELECT 1','ALTER TABLE patient_clinical ADD COLUMN blood_type VARCHAR(10) DEFAULT NULL AFTER allergy_drugs'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_member' AND column_name='visible_modules'),'SELECT 1','ALTER TABLE care_member ADD COLUMN visible_modules VARCHAR(1000) DEFAULT NULL AFTER access_level'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operation_audit_log' AND column_name='action_type'),'SELECT 1','ALTER TABLE operation_audit_log ADD COLUMN action_type VARCHAR(30) DEFAULT NULL, ADD COLUMN target_type VARCHAR(80) DEFAULT NULL, ADD COLUMN target_id VARCHAR(80) DEFAULT NULL, ADD COLUMN detail_json TEXT DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `platform_branding` (
  `id` TINYINT NOT NULL,
  `platform_name` VARCHAR(80) NOT NULL,
  `organization_name` VARCHAR(120) NOT NULL DEFAULT '',
  `logo_data` LONGTEXT NOT NULL,
  `page_background` CHAR(7) NOT NULL DEFAULT '#f5f7fb',
  `ownership_text` VARCHAR(240) NOT NULL,
  `updated_by` BIGINT DEFAULT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Singleton platform branding configuration';

INSERT INTO `platform_branding` (`id`,`platform_name`,`organization_name`,`logo_data`,`page_background`,`ownership_text`)
VALUES (1,'Chengxin Health','Chengxin Health','/logo.svg','#f5f7fb','© 2026 Chengxin Health. All rights reserved.')
ON DUPLICATE KEY UPDATE id=VALUES(id);

INSERT INTO `sys_menu` (`id`,`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
VALUES (41,5,'Platform Branding','platform-branding','/system/branding','Brush','branding:manage',1,5,1)
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),menu_name=VALUES(menu_name),menu_path=VALUES(menu_path),menu_icon=VALUES(menu_icon),permission=VALUES(permission),sort_order=VALUES(sort_order),status=VALUES(status);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,41 FROM sys_role r WHERE r.role_code='admin'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
