-- Idempotent production migration for role-specific workspaces and doctor workflows.
CREATE TABLE IF NOT EXISTS `doctor_patient_assignment` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT, `doctor_user_id` BIGINT NOT NULL, `patient_id` BIGINT NOT NULL,
  `assigned_by` BIGINT DEFAULT NULL, `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `care_team_role` VARCHAR(40) NOT NULL DEFAULT 'ATTENDING', `assigned_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_doctor_patient` (`doctor_user_id`,`patient_id`),
  KEY `idx_doctor_assignment_patient` (`patient_id`,`status`), KEY `idx_doctor_assignment_doctor` (`doctor_user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doctor-to-patient care-team assignments';
CREATE TABLE IF NOT EXISTS `doctor_clinical_note` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT, `doctor_user_id` BIGINT NOT NULL, `patient_id` BIGINT NOT NULL,
  `note_type` VARCHAR(40) NOT NULL DEFAULT 'FOLLOW_UP', `note_text` TEXT NOT NULL,
  `visibility` VARCHAR(20) NOT NULL DEFAULT 'CARE_TEAM', `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_doctor_note_patient` (`patient_id`,`created_at`), KEY `idx_doctor_note_doctor` (`doctor_user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doctor clinical notes with visibility controls';
CREATE TABLE IF NOT EXISTS `doctor_care_plan` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT, `doctor_user_id` BIGINT NOT NULL, `patient_id` BIGINT NOT NULL,
  `title` VARCHAR(160) NOT NULL, `plan_type` VARCHAR(40) NOT NULL DEFAULT 'FOLLOW_UP', `instructions` TEXT NOT NULL,
  `target_date` DATE DEFAULT NULL, `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_doctor_plan_patient` (`patient_id`,`status`,`target_date`), KEY `idx_doctor_plan_doctor` (`doctor_user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Clinician-authored patient care plans';
CREATE TABLE IF NOT EXISTS `doctor_review_log` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT, `doctor_user_id` BIGINT NOT NULL, `patient_id` BIGINT NOT NULL,
  `source_type` VARCHAR(30) NOT NULL, `source_id` BIGINT NOT NULL, `decision` VARCHAR(20) NOT NULL,
  `review_note` VARCHAR(1000) DEFAULT NULL, `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_doctor_review_patient` (`patient_id`,`created_at`), KEY `idx_doctor_review_source` (`source_type`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable clinician review audit log';

-- 兼容 MySQL 5.7：仅在旧库缺少字段时补齐人工复核能力。
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='verification_status'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN verification_status VARCHAR(30) DEFAULT ''VERIFIED'''));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='confidence_score'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN confidence_score DECIMAL(5,4) DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='verified_by'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN verified_by BIGINT DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='verified_at'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN verified_at DATETIME DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medical_record' AND index_name='idx_record_review'),'SELECT 1','ALTER TABLE medical_record ADD KEY idx_record_review (patient_id,verification_status)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_analysis_record' AND column_name='review_status'),'SELECT 1','ALTER TABLE ai_analysis_record ADD COLUMN review_status VARCHAR(30) DEFAULT ''APPROVED'''));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_analysis_record' AND column_name='reviewed_by'),'SELECT 1','ALTER TABLE ai_analysis_record ADD COLUMN reviewed_by BIGINT DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=(SELECT IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_analysis_record' AND column_name='reviewed_at'),'SELECT 1','ALTER TABLE ai_analysis_record ADD COLUMN reviewed_at DATETIME DEFAULT NULL'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
INSERT INTO `sys_role` (`role_code`,`role_name`,`description`,`status`) VALUES
('doctor','医生','负责临床复核和已分配患者管理',1),
('patient','患者','个人健康记录与自我管理',1),
('family','家属照护者','经授权的家庭照护与协作',1)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),description=VALUES(description),status=VALUES(status);
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
SELECT 0,'医生工作台','doctor-workspace','/doctor-workspace','FirstAidKit','doctor-workspace:view',1,1,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_code`='doctor-workspace');
UPDATE `sys_menu` SET `menu_name`='医生工作台',`menu_path`='/doctor-workspace',`menu_icon`='FirstAidKit',`permission`='doctor-workspace:view',`status`=1 WHERE `menu_code`='doctor-workspace';
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) SELECT r.id,m.id FROM sys_role r,sys_menu m WHERE r.role_code='doctor' AND m.id IN (28,38,30,8,2,16,18,19,26,27,20,21,35,29,22,24,25,36) ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code='doctor-workspace' WHERE r.role_code='doctor' ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code='doctor-workspace' WHERE r.role_code='admin' ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) SELECT r.id,m.id FROM sys_role r,sys_menu m WHERE r.role_code='patient' AND m.id IN (28,30,31,36,1,11,12,13,14,15,2,16,17,18,19,3,32,33,34,26,27,20,21,23,35,29,22,24,25) ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) SELECT r.id,m.id FROM sys_role r,sys_menu m WHERE r.role_code='family' AND m.id IN (28,30,31,36,1,11,12,15,2,16,17,18,19,3,32,33,34,26,27,20,21,23,29,24,25) ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
