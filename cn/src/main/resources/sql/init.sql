-- 完整系统初始化：MySQL 5.7 / 8.0，使用前先选择一个新建空数据库。
-- 本文件包含全部表、索引、外键及基础角色、菜单、字典和品牌配置；只需执行本文件。
-- 不创建默认账号、患者、患者授权或演示业务数据。演示数据另见 demo/sql/demo-data.sql。
-- 仅用于空库初始化；CREATE TABLE IF NOT EXISTS 不会升级已有表，禁止作为旧库升级脚本使用。
-- 仅用于新库初始化；不会替换已有工作流触发器。部分失败须先人工检查，再决定如何重试。
-- 建表期间临时关闭并恢复外键检查，以定义计划与版本的循环引用；种子写入时外键检查已恢复。
-- DATETIME(6) 协作字段由应用显式写入/读取 UTC；MySQL 8.0.16+ 强制 CHECK，5.7 由应用校验；告警工作流另有数据库触发器。
-- DDL 隐式提交；发生错误请停止并检查已创建结构，不要使用 mysql --force。

SET NAMES utf8mb4;

SET @init_foreign_key_checks = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

-- 一、系统结构（86 条 CREATE TABLE 语句；不是实际数据库清点结果）

CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'userID',
  `username` VARCHAR(50) NOT NULL COMMENT 'Username (Sign InAccount) ',
  `password` VARCHAR(100) NOT NULL COMMENT 'Password (BCryptaddsecret) ',
  `real_name` VARCHAR(50) COMMENT 'realName',
  `phone` VARCHAR(20) COMMENT 'Phone Number',
  `email` VARCHAR(100) COMMENT 'email',
  `status` INT DEFAULT 1 COMMENT 'Status: 0-Disabled, 1-Enabled',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  `deleted` INT DEFAULT 0 COMMENT 'logicDelete: 0-not Delete, 1-Deleted',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='systemusertable';

CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RoleID',
  `role_code` VARCHAR(50) NOT NULL COMMENT 'RoleCode (onlyoneidentifier) ',
  `role_name` VARCHAR(50) NOT NULL COMMENT 'RoleName',
  `description` VARCHAR(200) COMMENT 'RoleDescription',
  `status` INT DEFAULT 1 COMMENT 'Status: 0-Disabled, 1-Enabled',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  `deleted` INT DEFAULT 0 COMMENT 'logicDelete: 0-not Delete, 1-Deleted',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='systemRoletable';

CREATE TABLE IF NOT EXISTS `sys_menu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'MenuID',
  `parent_id` BIGINT DEFAULT 0 COMMENT 'parentMenuID, 0for toplevelMenu',
  `menu_name` VARCHAR(50) NOT NULL COMMENT 'MenuName',
  `menu_code` VARCHAR(100) DEFAULT NULL COMMENT 'businessstableCode',
  `menu_path` VARCHAR(100) COMMENT 'MenuPath (before endroute) ',
  `menu_icon` VARCHAR(50) COMMENT 'MenuIcon',
  `permission` VARCHAR(100) COMMENT 'Permissionidentifier (for example : user:add) ',
  `menu_type` INT DEFAULT 1 COMMENT 'Menutype: 1-Menu, 2-by button',
  `sort_order` INT DEFAULT 0 COMMENT 'displayOrder',
  `status` INT DEFAULT 1 COMMENT 'Status: 0-Disabled, 1-Enabled',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='systemMenu/Permissiontable';

CREATE TABLE IF NOT EXISTS `patient` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'PatientID',
  `user_id` BIGINT DEFAULT NULL COMMENT 'owner userID',
  `name` VARCHAR(50) NOT NULL COMMENT 'Name',
  `gender` VARCHAR(10) COMMENT 'Sex: MALE-Male, FEMALE-Female',
  `birth_date` DATE COMMENT 'Date of Birth',
  `id_card` VARCHAR(18) COMMENT 'ID Number',
  `phone` VARCHAR(20) COMMENT 'Phone Number',
  `address` VARCHAR(200) COMMENT 'address',
  `emergency_contact` VARCHAR(50) COMMENT 'Urgentcontact',
  `emergency_phone` VARCHAR(20) COMMENT 'Urgentcontactphone',
  `medical_history` TEXT COMMENT 'medical history',
  `remark` VARCHAR(500) COMMENT 'Notes',
  `status` INT DEFAULT 1 COMMENT 'Status: 0-Disabled, 1-Normal',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  `deleted` INT DEFAULT 0 COMMENT 'logicDelete: 0-not Delete, 1-Deleted',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_patient_name` (`user_id`, `name`),
  KEY `idx_status` (`status`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Patientinformationtable';

CREATE TABLE IF NOT EXISTS `dialysis_record` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'primary keyID',
  `record_date` DATE NOT NULL COMMENT 'Dialysis Date',
  `user_id` BIGINT DEFAULT NULL COMMENT 'owner userID',
  `patient_id` BIGINT DEFAULT NULL COMMENT 'PatientID',
  `last_off_weight` DECIMAL(5,2) COMMENT 'up timesPost-dialysis Weight(kg)',
  `on_weight` DECIMAL(5,2) COMMENT 'this timesPre-dialysis Weight(kg)',
  `off_weight` DECIMAL(5,2) COMMENT 'this timesPost-dialysis Weight(kg)',
  `interval_days` INT DEFAULT 1 COMMENT 'distanceup timesDialysisintervaldayscount',
  `weight_gain` DECIMAL(5,2) COMMENT 'interdialytic weight gain(kg)',
  `uf_amount` DECIMAL(5,2) COMMENT 'ultrafiltration volume/Fluid Removed(kg)',
  `session_minutes` INT DEFAULT NULL COMMENT 'dialysis session duration in minutes',
  `ktv` DECIMAL(5,2) DEFAULT NULL COMMENT 'single-pool Kt/V',
  `urr` DECIMAL(5,2) DEFAULT NULL COMMENT 'urea reduction ratio percent',
  `access_issue` VARCHAR(255) DEFAULT NULL COMMENT 'vascular access issue observed during treatment',
  `systolic_bp` INT COMMENT 'Blood Pressure-Systolic Pressure/systolic',
  `diastolic_bp` INT COMMENT 'Blood Pressure-Diastolic Pressure/diastolic',
  `daily_weight_gain` DECIMAL(5,2) COMMENT 'Dayaverage weight gain(kg)',
  `dehydration_status` VARCHAR(20) COMMENT 'fluid removalStatus: TOO_MUCH-excessive fluid removal, INSUFFICIENT-insufficient fluid removal, MATCH-match',
  `weight_3pct` DECIMAL(5,2) COMMENT 'Dry Weight 3%(idealweight gainup limit)',
  `weight_5pct` DECIMAL(5,2) COMMENT 'Dry Weight 5%(weight gainwarningline)',
  `record_type` VARCHAR(20) DEFAULT 'NORMAL' COMMENT 'recordtype: NORMAL-Normal, INCOMPLETE-dataincomplete',
  `remark` VARCHAR(255) COMMENT 'Notes/missingreason',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  KEY `idx_record_date` (`record_date`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_patient_id` (`patient_id`),
  UNIQUE KEY `uk_dialysis_patient_date` (`patient_id`, `record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Dialysis Recordstable';

CREATE TABLE IF NOT EXISTS `dry_weight_monthly` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'primary keyID',
  `year_month` VARCHAR(7) NOT NULL COMMENT 'Monthcopy, format yyyy-MM',
  `dry_weight` DECIMAL(5,2) NOT NULL COMMENT 'Dry Weightreference value(kg)',
  `user_id` BIGINT DEFAULT NULL COMMENT 'owner userID',
  `patient_id` BIGINT DEFAULT NULL COMMENT 'PatientID',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  UNIQUE KEY `uk_user_patient_month` (`user_id`, `patient_id`, `year_month`),
  KEY `idx_year_month` (`year_month`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_patient_id` (`patient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Dry WeightMonthlevelrecordtable';

CREATE TABLE IF NOT EXISTS `medical_record` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `patient_name` VARCHAR(50) NOT NULL COMMENT 'PatientName',
  `patient_id` BIGINT DEFAULT NULL COMMENT 'PatientID',
  `user_id` BIGINT DEFAULT NULL COMMENT 'owner userID',
  `record_date` DATE COMMENT 'Examination Date',
  `record_type` VARCHAR(30) COMMENT 'Examination Type: BLOOD-blood test, URINE-urinalysis, LIVER-liverfeature, KIDNEY-kidneyfeature, BONE-bone metabolism, IRON-iron metabolism, IMAGE-imagingReport, OTHER-Other',
  `hospital_name` VARCHAR(100) COMMENT 'HospitalName',
  `dept_name` VARCHAR(100) COMMENT 'DepartmentName',
  `doctor_name` VARCHAR(50) COMMENT 'ClinicianName',
  `ai_raw_result` TEXT COMMENT 'AIoriginalrecognitionresult(JSON)',
  `source_type` VARCHAR(20) DEFAULT 'MANUAL' COMMENT 'MANUAL/OCR/FHIR/DEVICE',
  `source_external_id` VARCHAR(120) DEFAULT NULL COMMENT 'source system record identifier',
  `verification_status` VARCHAR(30) DEFAULT 'VERIFIED' COMMENT 'VERIFIED/REVIEW_REQUIRED/REJECTED',
  `confidence_score` DECIMAL(5,4) DEFAULT NULL COMMENT 'overall import or recognition confidence',
  `verified_by` BIGINT DEFAULT NULL COMMENT 'reviewing user',
  `verified_at` DATETIME DEFAULT NULL COMMENT 'review time',
  `content_version` BIGINT NOT NULL DEFAULT 1 COMMENT 'medical-record content snapshot version',
  `verification_note` TEXT DEFAULT NULL COMMENT 'review note separate from patient remark',
  `remark` VARCHAR(500) COMMENT 'Notes',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  KEY `idx_record_patient` (`patient_name`),
  KEY `idx_record_date` (`record_date`),
  KEY `idx_record_type` (`record_type`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_patient_id` (`patient_id`),
  KEY `idx_record_review` (`patient_id`,`verification_status`),
  UNIQUE KEY `uk_record_external` (`patient_id`,`source_type`,`source_external_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Medical Recordsmaintable';

CREATE TABLE IF NOT EXISTS `medical_record_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `record_id` BIGINT NOT NULL COMMENT 'relatedrecordID',
  `item_name` VARCHAR(200) COMMENT 'ExaminationitemName',
  `item_code` VARCHAR(50) COMMENT 'Examinationitemreplacecode',
  `result_value` VARCHAR(500) COMMENT 'testresultvalue',
  `unit` VARCHAR(50) COMMENT 'Unit',
  `reference_range` VARCHAR(200) COMMENT 'Reference Range',
  `is_abnormal` TINYINT DEFAULT 0 COMMENT 'YesNoAbnormal: 0-Normal, 1-high, -1-low',
  `ai_confidence` DECIMAL(5,4) COMMENT 'AIrecognitionsetmessagelevel',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  FOREIGN KEY (`record_id`) REFERENCES `medical_record`(`id`) ON DELETE CASCADE,
  KEY `idx_item_record` (`record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Examination item detailstable';

CREATE TABLE IF NOT EXISTS `medical_record_attachment` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `record_id` BIGINT NOT NULL COMMENT 'relatedrecordID',
  `file_path` VARCHAR(500) COMMENT 'filePath',
  `file_name` VARCHAR(200) COMMENT 'originalfilename',
  `file_type` VARCHAR(20) COMMENT 'filetype: IMAGE, PDF',
  `file_size` BIGINT COMMENT 'filelargesmall(bytes)',
  `file_content` MEDIUMTEXT COMMENT 'fileBase64content',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  FOREIGN KEY (`record_id`) REFERENCES `medical_record`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='imagingReportAttachmenttable';

CREATE TABLE IF NOT EXISTS `medication` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `user_id` BIGINT DEFAULT NULL COMMENT 'owner userID',
  `patient_id` BIGINT DEFAULT NULL COMMENT 'PatientID',
  `drug_name` VARCHAR(100) NOT NULL COMMENT 'Medication Name',
  `generic_name` VARCHAR(100) COMMENT 'Generic Name',
  `specification` VARCHAR(100) COMMENT 'Specification',
  `unit` VARCHAR(20) COMMENT 'Unit (tablet/dose/bottle) ',
  `dosage_form` VARCHAR(30) COMMENT 'dosage form: TABLET-tablet, CAPSULE-capsule, INJECTION-injection, SOLUTION-oral solution, POWDER-powder',
  `manufacturer` VARCHAR(200) COMMENT 'manufacturer',
  `approval_number` VARCHAR(50) COMMENT 'approval number',
  `category` VARCHAR(30) COMMENT 'Medicationcategory: ANTIHYPERTENSIVE-antihypertensive, PHOSPHATE_BINDER-phosphate binder, IRON_SUPPLEMENT-iron supplement, VITAMIN-vitamin, ESA-erythropoietin, CALCIUM-calcium supplement, VD-active vitamin DD, DIURETIC-diuretic, ANTIBIOTIC-antibiotic, OTHER-Other',
  `default_dosage` VARCHAR(100) COMMENT 'DefaultDose instructions',
  `remark` VARCHAR(500) COMMENT 'Notes',
  `is_active` TINYINT DEFAULT 1 COMMENT 'YesNoEnabled: 0-Disabled, 1-Enabled',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  KEY `idx_user_id` (`user_id`),
  KEY `idx_patient_id` (`patient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Medicationinformationtable';

CREATE TABLE IF NOT EXISTS `medication_log` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `patient_name` VARCHAR(50) NOT NULL COMMENT 'PatientName',
  `patient_id` BIGINT DEFAULT NULL COMMENT 'PatientID',
  `user_id` BIGINT DEFAULT NULL COMMENT 'owner userID',
  `medication_id` BIGINT NOT NULL COMMENT 'MedicationID',
  `dialysis_record_id` BIGINT COMMENT 'relatedDialysis RecordsID (can empty) ',
  `dosage` VARCHAR(50) COMMENT 'this timesDose',
  `admin_route` VARCHAR(30) COMMENT 'administrationroute: ORAL-oral, IV-intravenous, SC-subcutaneous , IM-intramuscular',
  `administration_time` DATETIME COMMENT 'administrationTime',
  `prescribed_by` VARCHAR(50) COMMENT 'prescribeClinician',
  `effect_evaluation` VARCHAR(20) COMMENT 'effect assessment: GOOD-Good, MODERATE-Fair, POOR-poor',
  `side_effect` VARCHAR(200) COMMENT 'adverse reaction',
  `remark` VARCHAR(500) COMMENT 'Notes',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  FOREIGN KEY (`medication_id`) REFERENCES `medication`(`id`),
  KEY `idx_med_patient` (`patient_name`),
  KEY `idx_med_medication` (`medication_id`),
  KEY `idx_med_dialysis` (`dialysis_record_id`),
  KEY `idx_med_time` (`administration_time`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_patient_id` (`patient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='medicationrecordtable';

CREATE TABLE IF NOT EXISTS `ai_analysis_record` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `user_id` BIGINT DEFAULT NULL COMMENT 'owner userID',
  `patient_id` BIGINT DEFAULT NULL COMMENT 'PatientID',
  `time_type` VARCHAR(20) COMMENT 'Time dimension: year-Year, month-Month, week-week',
  `time_value` VARCHAR(20) COMMENT 'Timevalue, for example  2024-05, 2024',
  `period_label` VARCHAR(50) COMMENT 'weekperioddisplayName, for example  2024Year5Month',
  `analysis_content` TEXT COMMENT 'AIanalysispositivetext',
  `dw_adjust_needed` VARCHAR(10) COMMENT 'YesNoadjustment needed: Yes/No',
  `dw_adjust_amount` DECIMAL(5,2) COMMENT 'recommendationadjustment amount(kg)',
  `dw_target_weight` DECIMAL(5,2) COMMENT 'recommendationtargetDry Weight(kg)',
  `dw_adjust_reason` TEXT COMMENT 'adjustment rationale',
  `weight_control_eval` VARCHAR(20) COMMENT 'Weightcontrol assessment: excellent/Good/Fair/difference',
  `dehydration_eval` VARCHAR(20) COMMENT 'fluid removal assessment: excellent/Good/Fair/difference',
  `bp_control_eval` VARCHAR(20) COMMENT 'Blood Pressurecontrol assessment: excellent/Good/Fair/difference',
  `main_risk` TEXT COMMENT 'primaryRiskNotice',
  `diet_advice` TEXT COMMENT 'dietrecommendation',
  `fluid_advice` TEXT COMMENT 'fluid intake controlrecommendation',
  `exercise_advice` TEXT COMMENT 'exerciserecommendation',
  `medication_advice` TEXT COMMENT 'medicationrecommendation',
  `follow_up_advice` TEXT COMMENT 'follow-up/follow-up examinationrecommendation',
  `total_count` INT COMMENT 'recordtotal',
  `avg_on_weight` DECIMAL(5,2) COMMENT 'averagePre-dialysis Weight(kg)',
  `avg_off_weight` DECIMAL(5,2) COMMENT 'averagePost-dialysis Weight(kg)',
  `avg_weight_gain` DECIMAL(5,2) COMMENT 'averageinterdialytic weight gain(kg)',
  `avg_uf_amount` DECIMAL(5,2) COMMENT 'Average ultrafiltration volume(kg)',
  `dehydration_match_rate` DECIMAL(5,1) COMMENT 'fluid removal target rate(%)',
  `complication_risk_assessment` TEXT COMMENT 'complicationRiskassessment',
  `medication_advice_details` TEXT COMMENT 'Detailedmedicationrecommendation',
  `vital_sign_trend_summary` TEXT COMMENT 'Blood Pressuretrend summary',
  `review_status` VARCHAR(30) DEFAULT 'APPROVED' COMMENT 'APPROVED/REVIEW_REQUIRED/REJECTED',
  `reviewed_by` BIGINT DEFAULT NULL COMMENT 'reviewing user',
  `reviewed_at` DATETIME DEFAULT NULL COMMENT 'review time',
  `remark` VARCHAR(255) COMMENT 'Notes',
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  KEY `idx_user_id` (`user_id`),
  KEY `idx_ai_patient` (`patient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AIDialysisanalysisrecord';

CREATE TABLE IF NOT EXISTS `patient_clinical` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `patient_id` BIGINT NOT NULL COMMENT 'related patient.id',
  `user_id` BIGINT NOT NULL COMMENT 'belongAccount',
  `dialysis_type` VARCHAR(20) DEFAULT NULL COMMENT 'HDperitoneal dialysis/PDperitoneal dialysis/CRRTetc.',
  `dialysis_start_date` DATE DEFAULT NULL COMMENT 'startDialysis Date',
  `vascular_access` VARCHAR(64) DEFAULT NULL COMMENT 'vascular access',
  `primary_diagnosis` VARCHAR(255) DEFAULT NULL COMMENT 'originalonset/primary diagnosis',
  `allergy_drugs` TEXT COMMENT 'Medicationallergy history(JSON or text)',
  `blood_type` VARCHAR(10) DEFAULT NULL COMMENT 'Patient-reported blood group',
  `target_dry_weight` DECIMAL(6,2) DEFAULT NULL COMMENT 'currenttargetDry Weight kg',
  `fluid_limit_ml` INT DEFAULT NULL COMMENT 'Dayfluidintakeup limit ml',
  `dialysis_weekdays` VARCHAR(32) DEFAULT NULL COMMENT 'confirmed weekday plan, ISO 1-7 comma separated',
  `dialysis_time` VARCHAR(5) DEFAULT NULL COMMENT 'confirmed dialysis time HH:mm',
  `remark` VARCHAR(512) DEFAULT NULL COMMENT 'Notes',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  UNIQUE KEY `uk_patient` (`patient_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Patientclinical detailsinformation';

CREATE TABLE IF NOT EXISTS `health_indicator` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `item_code` VARCHAR(64) NOT NULL COMMENT 'indicatorCode, for example  FERRITIN',
  `item_name` VARCHAR(128) NOT NULL COMMENT 'standardName',
  `aliases` VARCHAR(512) DEFAULT NULL COMMENT 'alias, comma-separated: ferritin,serumferritin',
  `unit` VARCHAR(32) DEFAULT NULL COMMENT 'Unit',
  `ref_range_male` VARCHAR(64) DEFAULT NULL COMMENT 'MaleReference Range',
  `ref_range_female` VARCHAR(64) DEFAULT NULL COMMENT 'FemaleReference Range',
  `category` VARCHAR(32) DEFAULT NULL COMMENT 'BLOOD/KIDNEY/LIVERetc.',
  `sort_order` INT DEFAULT 0 COMMENT 'Order',
  `is_active` TINYINT DEFAULT 1 COMMENT 'YesNoEnabled',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  UNIQUE KEY `uk_code` (`item_code`),
  KEY `idx_name` (`item_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='healthlaboratory test dictionary';

CREATE TABLE IF NOT EXISTS `medication_reminder` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `user_id` BIGINT NOT NULL COMMENT 'userID',
  `patient_id` BIGINT NOT NULL COMMENT 'PatientID',
  `medication_id` BIGINT NOT NULL COMMENT 'related medication.id',
  `remind_time` VARCHAR(8) NOT NULL COMMENT 'ReminderTime HH:mm',
  `repeat_days` VARCHAR(32) DEFAULT '1,2,3,4,5,6,7' COMMENT 'weekseveral, 1=weekone',
  `dosage` VARCHAR(64) DEFAULT NULL COMMENT 'Dose',
  `enabled` TINYINT DEFAULT 1 COMMENT 'YesNoEnabled',
  `last_trigger_at` DATETIME DEFAULT NULL COMMENT 'up timestriggerTime',
  `remark` VARCHAR(256) DEFAULT NULL COMMENT 'Notes',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created At',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  KEY `idx_user_patient` (`user_id`, `patient_id`),
  KEY `idx_med` (`medication_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Medication Remindersplan';

CREATE TABLE IF NOT EXISTS `user_preference` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `user_id` BIGINT NOT NULL COMMENT 'userID',
  `pref_key` VARCHAR(128) NOT NULL COMMENT 'for example  table:medical-record-list',
  `pref_value` TEXT NOT NULL COMMENT 'JSON',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  UNIQUE KEY `uk_user_key` (`user_id`, `pref_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='userinterface preferences';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` BIGINT NOT NULL COMMENT 'userID',
  `role_id` BIGINT NOT NULL COMMENT 'RoleID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='userRolerelated table';

CREATE TABLE IF NOT EXISTS `sys_role_menu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `role_id` BIGINT NOT NULL COMMENT 'RoleID',
  `menu_id` BIGINT NOT NULL COMMENT 'MenuID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RoleMenurelated table';

CREATE TABLE IF NOT EXISTS `patient_specialty_role` (
  `patient_id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  `assigned_by` BIGINT NOT NULL,
  `assigned_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`patient_id`,`role_id`),
  KEY `idx_specialty_role` (`role_id`),
  CONSTRAINT `fk_patient_specialty_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`),
  CONSTRAINT `fk_patient_specialty_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Patient-specific specialty roles';

CREATE TABLE IF NOT EXISTS `complication_record` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `patient_id` BIGINT DEFAULT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `patient_name` VARCHAR(50) DEFAULT NULL,
  `complication_type` VARCHAR(30) DEFAULT NULL,
  `occurrence_date` DATE DEFAULT NULL,
  `severity` VARCHAR(20) DEFAULT NULL,
  `description` TEXT,
  `treatment_measures` TEXT,
  `outcome` VARCHAR(100) DEFAULT NULL,
  `related_dialysis_id` BIGINT DEFAULT NULL,
  `remark` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_complication_user` (`user_id`),
  KEY `idx_complication_patient` (`patient_id`),
  KEY `idx_complication_type` (`complication_type`),
  KEY `idx_complication_date` (`occurrence_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Complication Trackingrecord';

CREATE TABLE IF NOT EXISTS `bp_self_monitor_record` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `patient_id` BIGINT DEFAULT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `record_date` DATE NOT NULL,
  `record_time` VARCHAR(10) DEFAULT NULL,
  `measure_type` VARCHAR(10) NOT NULL,
  `systolic_bp` INT DEFAULT NULL,
  `diastolic_bp` INT DEFAULT NULL,
  `blood_glucose` DECIMAL(5,2) DEFAULT NULL,
  `bg_unit` VARCHAR(10) DEFAULT 'mmol/L',
  `measure_period` VARCHAR(20) DEFAULT NULL,
  `source_type` VARCHAR(20) DEFAULT 'MANUAL',
  `source_external_id` VARCHAR(120) DEFAULT NULL,
  `verification_status` VARCHAR(30) DEFAULT 'VERIFIED',
  `remark` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_bp_patient_date` (`patient_id`,`record_date`,`record_time`),
  KEY `idx_bp_user` (`user_id`),
  KEY `idx_bp_measure_type` (`measure_type`),
  UNIQUE KEY `uk_bp_external` (`patient_id`,`source_type`,`source_external_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Blood Pressure & Glucoseself-monitoringrecord';

CREATE TABLE IF NOT EXISTS `clinical_import_batch` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `source_type` VARCHAR(20) NOT NULL,
  `status` VARCHAR(30) NOT NULL,
  `item_count` INT NOT NULL DEFAULT 0,
  `error_count` INT NOT NULL DEFAULT 0,
  `summary` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_import_patient_time` (`patient_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='clinical import audit batch';

CREATE TABLE IF NOT EXISTS `nutrition_assessment` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `patient_id` BIGINT DEFAULT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `assessment_date` DATE DEFAULT NULL,
  `sga_score` INT DEFAULT NULL,
  `sga_grade` VARCHAR(5) DEFAULT NULL,
  `bmi` DECIMAL(5,2) DEFAULT NULL,
  `body_weight` DECIMAL(5,2) DEFAULT NULL,
  `height` DECIMAL(5,2) DEFAULT NULL,
  `albumin` DECIMAL(5,2) DEFAULT NULL,
  `pre_albumin` DECIMAL(5,2) DEFAULT NULL,
  `total_protein_intake` DECIMAL(5,2) DEFAULT NULL,
  `daily_calorie_intake` DECIMAL(5,2) DEFAULT NULL,
  `daily_potassium_intake` DECIMAL(5,2) DEFAULT NULL,
  `daily_phosphorus_intake` DECIMAL(5,2) DEFAULT NULL,
  `fluid_intake` INT DEFAULT NULL,
  `nutrition_status` VARCHAR(20) DEFAULT NULL,
  `supplement_advice` TEXT,
  `remark` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_nutrition_assessment_patient` (`patient_id`),
  KEY `idx_nutrition_assessment_user` (`user_id`),
  KEY `idx_nutrition_assessment_date` (`assessment_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Nutrition Assessment';

CREATE TABLE IF NOT EXISTS `nutrition_diary` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `patient_id` BIGINT DEFAULT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `record_date` DATE NOT NULL,
  `body_weight` DECIMAL(5,2) DEFAULT NULL,
  `appetite` VARCHAR(10) DEFAULT NULL,
  `meal_breakfast` TINYINT(1) DEFAULT 0,
  `meal_lunch` TINYINT(1) DEFAULT 0,
  `meal_dinner` TINYINT(1) DEFAULT 0,
  `meal_snack` TINYINT(1) DEFAULT 0,
  `fluid_intake` INT DEFAULT NULL,
  `symptoms` VARCHAR(200) DEFAULT NULL,
  `remark` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_nutrition_diary_patient` (`patient_id`),
  KEY `idx_nutrition_diary_user` (`user_id`),
  KEY `idx_nutrition_diary_date` (`record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Nutrition Diary';

CREATE TABLE IF NOT EXISTS `bp_pattern_analysis` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `patient_id` BIGINT DEFAULT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `analysis_date` DATE DEFAULT NULL,
  `time_type` VARCHAR(20) DEFAULT NULL,
  `time_value` VARCHAR(20) DEFAULT NULL,
  `avg_systolic` DECIMAL(5,1) DEFAULT NULL,
  `avg_diastolic` DECIMAL(5,1) DEFAULT NULL,
  `max_systolic` INT DEFAULT NULL,
  `min_systolic` INT DEFAULT NULL,
  `std_deviation` DECIMAL(5,2) DEFAULT NULL,
  `orthostatic_count` INT DEFAULT 0,
  `low_bp_count` INT DEFAULT 0,
  `high_bp_count` INT DEFAULT 0,
  `avg_uf_amount` DECIMAL(5,2) DEFAULT NULL,
  `correlation_weight_gain_bp` DECIMAL(5,3) DEFAULT NULL,
  `analysis_summary` TEXT,
  `remark` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_bp_pattern_patient` (`patient_id`),
  KEY `idx_bp_pattern_date` (`analysis_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Blood Pressure Pattern Analysis';

CREATE TABLE IF NOT EXISTS `alert_rule` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `patient_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `indicator_code` VARCHAR(64) DEFAULT NULL,
  `indicator_name` VARCHAR(128) DEFAULT NULL,
  `threshold_type` VARCHAR(20) DEFAULT NULL,
  `threshold_value` VARCHAR(100) DEFAULT NULL,
  `alert_level` VARCHAR(20) DEFAULT NULL,
  `enabled` TINYINT DEFAULT 1,
  `remark` VARCHAR(256) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_alert_rule_user_patient` (`user_id`,`patient_id`),
  KEY `idx_alert_rule_indicator` (`indicator_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='alert rule';

CREATE TABLE IF NOT EXISTS `alert_record` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `rule_id` BIGINT DEFAULT NULL,
  `patient_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `alert_type` VARCHAR(30) DEFAULT NULL,
  `alert_level` VARCHAR(20) DEFAULT NULL,
  `alert_title` VARCHAR(200) DEFAULT NULL,
  `triggered_value` VARCHAR(100) DEFAULT NULL,
  `triggered_at` DATETIME DEFAULT NULL,
  `source_type` VARCHAR(30) DEFAULT NULL,
  `source_id` BIGINT DEFAULT NULL,
  `dedupe_key` VARCHAR(160) DEFAULT NULL,
  `occurrence_count` INT NOT NULL DEFAULT 1,
  `last_triggered_at` DATETIME DEFAULT NULL,
  `status` VARCHAR(20) DEFAULT 'PENDING',
  `acknowledged_by` BIGINT DEFAULT NULL,
  `acknowledged_at` DATETIME DEFAULT NULL,
  `resolved_by` BIGINT DEFAULT NULL,
  `resolved_at` DATETIME DEFAULT NULL,
  `handling_note` VARCHAR(500) DEFAULT NULL,
  `remark` VARCHAR(256) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `workflow_version` INT NOT NULL DEFAULT 0,
  `workflow_state` VARCHAR(30) DEFAULT NULL,
  `workflow_mode` VARCHAR(20) DEFAULT NULL,
  `cycle_no` INT NOT NULL DEFAULT 0,
  `source_revision` BIGINT DEFAULT NULL,
  `metric_key` VARCHAR(40) DEFAULT NULL,
  `responsible_user_id` BIGINT DEFAULT NULL,
  `due_at` DATETIME(6) DEFAULT NULL,
  `accepted_by` BIGINT DEFAULT NULL,
  `accepted_at` DATETIME(6) DEFAULT NULL,
  `lock_version` BIGINT NOT NULL DEFAULT 0,
  `current_handling_event_id` BIGINT DEFAULT NULL,
  `current_feedback_event_id` BIGINT DEFAULT NULL,
  `source_review_required` TINYINT NOT NULL DEFAULT 0,
  `reassignment_required` TINYINT NOT NULL DEFAULT 0,
  `classification_json` LONGTEXT DEFAULT NULL,
  `closed_by` BIGINT DEFAULT NULL,
  `closed_at` DATETIME(6) DEFAULT NULL,
  `closure_reason` VARCHAR(1000) DEFAULT NULL,
  KEY `idx_alert_user_patient` (`user_id`,`patient_id`),
  KEY `idx_alert_patient_status_time` (`patient_id`,`status`,`triggered_at`),
  KEY `idx_alert_triggered_at` (`triggered_at`),
  KEY `idx_alert_dedupe` (`patient_id`,`dedupe_key`,`status`),
  KEY `idx_aw_patient_queue` (`patient_id`,`workflow_version`,`workflow_state`,`due_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='alertrecord';

CREATE TABLE IF NOT EXISTS `alert_event` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `alert_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `actor_id` BIGINT DEFAULT NULL,
  `actor_name` VARCHAR(100) DEFAULT NULL,
  `from_status` VARCHAR(20) DEFAULT NULL,
  `to_status` VARCHAR(20) NOT NULL,
  `note` VARCHAR(2000) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `event_type` VARCHAR(40) DEFAULT NULL,
  `cycle_no` INT NOT NULL DEFAULT 0,
  `actor_role` VARCHAR(50) DEFAULT NULL,
  `actor_relation` VARCHAR(100) DEFAULT NULL,
  `entry_mode` VARCHAR(20) DEFAULT NULL,
  `occurred_at` DATETIME(6) DEFAULT NULL,
  `recorded_at` DATETIME(6) DEFAULT NULL,
  `payload_json` LONGTEXT DEFAULT NULL,
  KEY `idx_alert_event_alert` (`alert_id`,`created_at`),
  KEY `idx_alert_event_patient` (`patient_id`,`created_at`),
  KEY `idx_aw_event_cycle` (`alert_id`,`cycle_no`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='alert status audit trail';

CREATE TABLE IF NOT EXISTS `medication_safety_rule` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `ingredient_a` VARCHAR(100) NOT NULL,
  `ingredient_b` VARCHAR(100) NOT NULL,
  `severity` VARCHAR(20) NOT NULL,
  `message` VARCHAR(500) NOT NULL,
  `renal_note` VARCHAR(500) DEFAULT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  UNIQUE KEY `uk_medication_safety_pair` (`ingredient_a`,`ingredient_b`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='curated medication safety screening rules';

CREATE TABLE IF NOT EXISTS `patient_health_target` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `systolic_min` INT DEFAULT 90,
  `systolic_max` INT DEFAULT 140,
  `diastolic_min` INT DEFAULT 60,
  `diastolic_max` INT DEFAULT 90,
  `fasting_glucose_min` DECIMAL(5,2) DEFAULT 3.9,
  `fasting_glucose_max` DECIMAL(5,2) DEFAULT 6.1,
  `postmeal_glucose_min` DECIMAL(5,2) DEFAULT 3.9,
  `postmeal_glucose_max` DECIMAL(5,2) DEFAULT 7.8,
  `target_weight` DECIMAL(6,2) DEFAULT NULL,
  `weight_gain_limit` DECIMAL(6,2) DEFAULT NULL,
  `emergency_contact` VARCHAR(50) DEFAULT NULL,
  `emergency_phone` VARCHAR(30) DEFAULT NULL,
  `hospital_name` VARCHAR(100) DEFAULT NULL,
  `doctor_name` VARCHAR(50) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_health_target_patient` (`patient_id`),
  KEY `idx_health_target_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Patienthealthtarget';

CREATE TABLE IF NOT EXISTS `medication_intake` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `reminder_id` BIGINT DEFAULT NULL,
  `medication_id` BIGINT DEFAULT NULL,
  `scheduled_at` DATETIME NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  `action_at` DATETIME DEFAULT NULL,
  `snooze_until` DATETIME DEFAULT NULL,
  `dosage` VARCHAR(100) DEFAULT NULL,
  `reason` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_intake_schedule` (`reminder_id`,`scheduled_at`),
  KEY `idx_intake_patient_schedule` (`patient_id`,`scheduled_at`,`status`),
  KEY `idx_intake_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='medicationruntask';

CREATE TABLE IF NOT EXISTS `health_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `event_date` DATE NOT NULL,
  `event_time` VARCHAR(5) DEFAULT NULL,
  `event_type` VARCHAR(30) NOT NULL,
  `title` VARCHAR(120) NOT NULL,
  `summary` VARCHAR(500) DEFAULT NULL,
  `source_type` VARCHAR(30) DEFAULT 'MANUAL',
  `source_id` BIGINT DEFAULT NULL,
  `status` VARCHAR(20) DEFAULT 'RECORDED',
  `remark` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_event_patient_date` (`patient_id`,`event_date`,`event_time`),
  KEY `idx_event_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Health Timelineevent';

CREATE TABLE IF NOT EXISTS `dialysis_schedule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `schedule_date` DATE NOT NULL,
  `schedule_time` VARCHAR(5) DEFAULT NULL,
  `status` VARCHAR(20) DEFAULT 'PLANNED',
  `completed_record_id` BIGINT DEFAULT NULL,
  `remark` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dialysis_schedule` (`patient_id`,`schedule_date`),
  KEY `idx_schedule_patient_date` (`patient_id`,`schedule_date`,`status`),
  KEY `idx_schedule_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Dialysis Schedule';

CREATE TABLE IF NOT EXISTS `care_member` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `relation_name` VARCHAR(60) DEFAULT NULL,
  `access_level` VARCHAR(20) NOT NULL DEFAULT 'WRITE',
  `visible_modules` VARCHAR(1000) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_care_member` (`patient_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Family Carecompletemember';

CREATE TABLE IF NOT EXISTS `care_invitation` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `invite_code` VARCHAR(64) NOT NULL,
  `expires_at` DATETIME NOT NULL,
  `accepted_by` BIGINT DEFAULT NULL,
  UNIQUE KEY `uk_care_invite` (`invite_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Family CareinvitePlease ';

CREATE TABLE IF NOT EXISTS `care_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `kind` VARCHAR(30) NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `status` VARCHAR(30) NOT NULL,
  `assigned_user_id` BIGINT DEFAULT NULL,
  `actor_id` BIGINT DEFAULT NULL,
  `actor_name` VARCHAR(100) DEFAULT NULL,
  `event_at` DATETIME DEFAULT NULL,
  `notify_at` DATETIME DEFAULT NULL,
  `notified_at` DATETIME DEFAULT NULL,
  `escalated_at` DATETIME DEFAULT NULL,
  `data_json` LONGTEXT,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_care_patient_kind` (`patient_id`,`kind`),
  KEY `idx_care_due` (`status`,`notify_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='careitem';

CREATE TABLE IF NOT EXISTS `medication_stock` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `medication_id` BIGINT NOT NULL,
  `quantity` DECIMAL(12,3) NOT NULL DEFAULT 0,
  `unit` VARCHAR(20) NOT NULL DEFAULT 'tablet',
  `warning_days` INT NOT NULL DEFAULT 7,
  `warning_quantity` DECIMAL(12,3) DEFAULT 0,
  `notified_at` DATETIME DEFAULT NULL,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_medication_stock` (`patient_id`,`medication_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MedicationInventory';

CREATE TABLE IF NOT EXISTS `medication_stock_movement` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `medication_id` BIGINT NOT NULL,
  `quantity` DECIMAL(12,3) NOT NULL,
  `reason` VARCHAR(200) DEFAULT NULL,
  `source_key` VARCHAR(100) DEFAULT NULL,
  `actor_id` BIGINT DEFAULT NULL,
  `actor_name` VARCHAR(100) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_stock_source` (`source_key`),
  KEY `idx_stock_medication` (`medication_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MedicationInventoryhistory';

CREATE TABLE IF NOT EXISTS `care_intake_action` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `intake_id` BIGINT NOT NULL,
  `actor_id` BIGINT NOT NULL,
  `actor_name` VARCHAR(100) DEFAULT NULL,
  `status` VARCHAR(30) DEFAULT NULL,
  `recorded_for` VARCHAR(20) DEFAULT NULL,
  `quantity` DECIMAL(12,3) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_action_intake` (`intake_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='medication intakeActionsaudit';

CREATE TABLE IF NOT EXISTS `notification_robot_config` (
  `channel_id` BIGINT PRIMARY KEY,
  `secret` VARCHAR(500) DEFAULT NULL,
  `keyword` VARCHAR(100) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NotificationBotextendconfiguration';

CREATE TABLE IF NOT EXISTS `notification_channel` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  channel_type VARCHAR(20) NOT NULL,
  channel_name VARCHAR(50) DEFAULT NULL,
  webhook_url VARCHAR(500) DEFAULT NULL,
  enabled TINYINT DEFAULT 1,
  last_test_at DATETIME DEFAULT NULL,
  last_test_result VARCHAR(255) DEFAULT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_channel_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User notification channels';

CREATE TABLE IF NOT EXISTS `health_analysis_automation` (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  task_name VARCHAR(100) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  frequency_type VARCHAR(20) NOT NULL DEFAULT 'WEEKLY',
  interval_days INT DEFAULT 1,
  day_of_week INT DEFAULT 1,
  day_of_month INT DEFAULT 1,
  run_time VARCHAR(5) NOT NULL DEFAULT '08:00',
  analysis_range_days INT NOT NULL DEFAULT 30,
  analysis_items VARCHAR(255) NOT NULL,
  notification_channel_ids VARCHAR(500),
  next_run_at DATETIME,
  last_run_at DATETIME,
  config_version BIGINT NOT NULL DEFAULT 0,
  active_run_id BIGINT,
  last_run_status VARCHAR(30),
  last_error VARCHAR(500),
  last_analysis_record_id BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_health_auto_due (enabled,next_run_at),
  KEY idx_health_auto_user (user_id),
  KEY idx_health_auto_patient (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automated health analysis tasks';

CREATE TABLE IF NOT EXISTS `health_analysis_run` (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  automation_id BIGINT NOT NULL,
  claim_token VARCHAR(64) NOT NULL,
  state VARCHAR(30) NOT NULL,
  scheduled TINYINT NOT NULL DEFAULT 0,
  scheduled_for DATETIME,
  settings_json LONGTEXT NOT NULL,
  claimed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  analysis_record_id BIGINT,
  last_error VARCHAR(500),
  notification_outcomes LONGTEXT,
  UNIQUE KEY uq_health_run_token (claim_token),
  KEY idx_health_run_automation (automation_id,id),
  KEY idx_health_run_recovery (state,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable health analysis operation journal';

CREATE TABLE IF NOT EXISTS `operation_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT DEFAULT NULL,
  `username` VARCHAR(100) DEFAULT NULL,
  `request_method` VARCHAR(10) NOT NULL,
  `request_path` VARCHAR(255) NOT NULL,
  `status_code` INT DEFAULT NULL,
  `duration_ms` BIGINT DEFAULT NULL,
  `client_ip` VARCHAR(64) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `action_type` VARCHAR(30) DEFAULT NULL,
  `target_type` VARCHAR(80) DEFAULT NULL,
  `target_id` VARCHAR(80) DEFAULT NULL,
  `detail_json` TEXT DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_user_time` (`user_id`,`created_at`),
  KEY `idx_audit_path_time` (`request_path`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Operation audit log';

CREATE TABLE IF NOT EXISTS `doctor_patient_assignment` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `doctor_user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `assigned_by` BIGINT DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `care_team_role` VARCHAR(40) NOT NULL DEFAULT 'ATTENDING',
  `assigned_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_doctor_patient` (`doctor_user_id`,`patient_id`),
  KEY `idx_doctor_assignment_patient` (`patient_id`,`status`),
  KEY `idx_doctor_assignment_doctor` (`doctor_user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生与患者医疗团队分配关系';

CREATE TABLE IF NOT EXISTS `doctor_clinical_note` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `doctor_user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `note_type` VARCHAR(40) NOT NULL DEFAULT 'FOLLOW_UP',
  `note_text` TEXT NOT NULL,
  `visibility` VARCHAR(20) NOT NULL DEFAULT 'CARE_TEAM',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_doctor_note_patient` (`patient_id`,`created_at`),
  KEY `idx_doctor_note_doctor` (`doctor_user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='带可见范围控制的医生临床笔记';

CREATE TABLE IF NOT EXISTS `doctor_care_plan` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `doctor_user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `title` VARCHAR(160) NOT NULL,
  `plan_type` VARCHAR(40) NOT NULL DEFAULT 'FOLLOW_UP',
  `instructions` TEXT NOT NULL,
  `target_date` DATE DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `workflow_version` TINYINT NOT NULL DEFAULT 0,
  `lifecycle` VARCHAR(20) DEFAULT NULL,
  `current_revision_id` BIGINT DEFAULT NULL,
  `draft_revision_id` BIGINT DEFAULT NULL,
  `legacy_source_id` BIGINT DEFAULT NULL,
  `lock_version` BIGINT NOT NULL DEFAULT 0,
  `closed_at` DATETIME(6) DEFAULT NULL,
  `cancelled_at` DATETIME(6) DEFAULT NULL,
  `cancel_reason` VARCHAR(1000) DEFAULT NULL,
  KEY `idx_doctor_plan_patient` (`patient_id`,`status`,`target_date`),
  KEY `idx_doctor_plan_doctor` (`doctor_user_id`,`status`),
  CONSTRAINT fk_doctor_plan_current_revision FOREIGN KEY (current_revision_id) REFERENCES care_plan_revision (id),
  CONSTRAINT fk_doctor_plan_draft_revision FOREIGN KEY (draft_revision_id) REFERENCES care_plan_revision (id),
  CONSTRAINT fk_doctor_plan_legacy_source FOREIGN KEY (legacy_source_id) REFERENCES doctor_care_plan (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生制定的患者照护计划';

CREATE TABLE IF NOT EXISTS `doctor_review_log` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `doctor_user_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `source_type` VARCHAR(30) NOT NULL,
  `source_id` BIGINT NOT NULL,
  `decision` VARCHAR(20) NOT NULL,
  `review_note` VARCHAR(1000) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_doctor_review_patient` (`patient_id`,`created_at`),
  KEY `idx_doctor_review_source` (`source_type`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变更的医生复核审计日志';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台品牌配置';

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
  CONSTRAINT `fk_care_plan_revision_plan` FOREIGN KEY (`plan_id`) REFERENCES `doctor_care_plan` (`id`)
  /*!80016 , CONSTRAINT `ck_care_plan_revision_no` CHECK (`revision_no` > 0) */
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
  CONSTRAINT `fk_care_plan_action_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`)
  /*!80016 , CONSTRAINT `ck_care_plan_action_ordinal` CHECK (`ordinal` BETWEEN 1 AND 50) */
  /*!80016 , CONSTRAINT `ck_care_plan_action_version` CHECK (`lock_version` >= 0) */
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
  CONSTRAINT `fk_care_plan_notification_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`)
  /*!80016 , CONSTRAINT `ck_care_plan_notification_attempts` CHECK (`attempt_count` BETWEEN 0 AND 3) */
  /*!80016 , CONSTRAINT `ck_care_plan_notification_dispatch` CHECK (CHAR_LENGTH(`dispatch_key`) > 0) */
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
  CONSTRAINT `fk_care_plan_command_plan` FOREIGN KEY (`plan_id`) REFERENCES `doctor_care_plan` (`id`)
  /*!80016 , CONSTRAINT `ck_care_plan_command_version` CHECK (`expected_version` >= 0) */
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Command deduplication; current authorization must be checked before replay';

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

SET FOREIGN_KEY_CHECKS = @init_foreign_key_checks;

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


-- 二、初始角色（菜单权限不代表患者数据授权，不分配任何账号）

INSERT INTO `sys_role` (`role_code`,`role_name`,`description`,`status`) VALUES
('admin','系统管理员','拥有全部权限的系统管理员',1),
('user','普通用户','平台普通用户',1),
('doctor','医生','负责临床复核和已分配患者管理',1),
('patient','患者','个人健康记录与自我管理',1),
('family','家属照护者','经授权的家庭照护与协作',1),
('specialty_dialysis','透析患者','患者档案的透析专病菜单范围',1),
('nurse','护理人员','分配患者的照护计划跟进；同时要求患者模块授权',1)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),description=VALUES(description),status=VALUES(status);

-- 三、导航菜单与操作权限

INSERT INTO `sys_menu` (`id`,`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`) VALUES
(1,0,'透析管理','dialysis','/dialysis','Histogram','dialysis:view',1,2,1),
(2,0,'医疗记录','clinical-record','/medical-record','FolderOpened','medical:view',1,3,1),
(3,0,'用药管理','medication','/medication','Goods','medication:view',1,4,1),
(5,0,'系统管理','system','','Setting','system:manage',1,7,1),
(6,5,'用户管理','system-user','/system/user','User','user:manage',1,1,1),
(7,5,'角色管理','system-role','/system/role','UserFilled','role:manage',1,2,1),
(8,30,'患者档案','patient-profile','/system/patient','Avatar','patient:manage',1,1,1),
(9,5,'菜单管理','system-menu','/system/menu','Menu','menu:manage',1,3,1),
(11,1,'透析记录','dialysis-record','/dialysis?tab=data','Document','dialysis:record:view',1,1,1),
(12,1,'趋势分析','dialysis-trend','/dialysis?tab=analysis','TrendCharts','dialysis:trend:view',1,2,1),
(13,1,'干体重管理','dialysis-dry-weight','/dry-weight','ScaleToOriginal','dry-weight:view',1,3,1),
(14,1,'AI 健康分析','dialysis-ai','/dialysis?tab=ai','Cpu','dialysis:ai:view',1,4,1),
(15,1,'透析排班','dialysis-schedule','/family-health?tab=schedule','Calendar','dialysis:schedule:view',1,5,1),
(16,2,'记录列表','clinical-record-list','/medical-record?tab=list','FolderOpened','medical:list:view',1,1,1),
(17,2,'上传报告','clinical-record-upload','/medical-record?tab=upload','Camera','medical:upload',1,2,1),
(18,2,'异常结果','clinical-abnormal','/medical-record?tab=abnormal','WarningFilled','medical:abnormal:view',1,3,1),
(19,2,'指标趋势','clinical-trend','/medical-record?tab=trend','DataLine','medical:trend:view',1,4,1),
(20,26,'并发症跟踪','health-complication','/health-analysis?tab=complication','Warning','complication:view',1,2,1),
(21,26,'健康告警','health-alert','/health-analysis?tab=alert','Bell','alert:view',1,3,1),
(22,29,'血压规律分析','analysis-bp-pattern','/health-analysis?tab=bp-pattern','TrendCharts','bp-pattern:view',1,1,1),
(23,26,'营养日记','health-nutrition','/health-analysis?tab=nutrition','Apple','nutrition-diary:view',1,4,1),
(24,29,'健康报告','analysis-health-report','/health-analysis?tab=health-report','DocumentChecked','health-report:view',1,2,1),
(25,29,'数据导出','analysis-data-export','/health-analysis?tab=data-export','Download','data-export:view',1,3,1),
(26,0,'健康监测','health-monitoring','','Odometer','health-monitoring:view',1,5,1),
(27,26,'血压与血糖','health-vitals','/bp-self-monitor','Odometer','bp-self-monitor:view',1,1,1),
(28,0,'健康总览','workspace','/monitoring','Monitor','monitoring:view',1,0,1),
(29,0,'分析与报告','analysis-report','','DataAnalysis','analysis-report:view',1,6,1),
(30,0,'患者中心','patient-center','','UserFilled','patient:view',1,1,1),
(31,30,'照护计划','patient-care','/family-health','Calendar','family-health:view',1,2,1),
(32,3,'药品列表','medication-catalog','/medication?tab=drugs','Box','medication:catalog:view',1,1,1),
(33,3,'用药记录','medication-log','/medication?tab=logs','Notebook','medication:log:view',1,2,1),
(34,3,'用药提醒','medication-reminder','/medication?tab=remind','Bell','medication:reminder:view',1,3,1),
(35,26,'营养评估','health-nutrition-assessment','/health-analysis?tab=nutrition-assessment','DataAnalysis','nutrition-assessment:view',1,5,1),
(36,30,'通知设置','notification-settings','/settings/notifications','Bell','notification:manage',1,3,1),
(37,5,'审计日志','system-audit','/system/audit','Document','audit:view',1,4,1),
(38,0,'临床工作台','clinical-workbench','/clinical-workbench','FirstAidKit','clinical-workbench:view',1,1,1),
(39,0,'医生工作台','doctor-workspace','/doctor-workspace','FirstAidKit','doctor-workspace:view',1,1,1),
(40,0,'照护历程','care-journey','/care-journey','FirstAidKit','care:journey:view',1,2,1),
(41,5,'平台品牌','platform-branding','/system/branding','Brush','branding:manage',1,5,1),
(42,0,'护理跟进队列','care-plan-nursing','/nurse-workspace','FirstAidKit','care-plan:nursing:view',1,2,1),
(43,8,'管理护理分配','care-plan-nurse-assignments',NULL,'User','care-plan:nurse-assignment:manage',2,90,1)
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),menu_name=VALUES(menu_name),menu_code=VALUES(menu_code),menu_path=VALUES(menu_path),menu_icon=VALUES(menu_icon),permission=VALUES(permission),menu_type=VALUES(menu_type),sort_order=VALUES(sort_order),status=VALUES(status);

-- 四、角色菜单范围；系统管理与护理分配仅授予管理员

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code IN ('dialysis','clinical-record','medication','system','system-user','system-role','patient-profile','system-menu','dialysis-record','dialysis-trend','dialysis-dry-weight','dialysis-ai','dialysis-schedule','clinical-record-list','clinical-record-upload','clinical-abnormal','clinical-trend','health-complication','health-alert','analysis-bp-pattern','health-nutrition','analysis-health-report','analysis-data-export','health-monitoring','health-vitals','workspace','analysis-report','patient-center','patient-care','medication-catalog','medication-log','medication-reminder','health-nutrition-assessment','notification-settings','system-audit','clinical-workbench','doctor-workspace','care-journey','platform-branding','care-plan-nurse-assignments') WHERE r.role_code='admin'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code IN ('dialysis','clinical-record','medication','patient-profile','dialysis-record','dialysis-trend','dialysis-dry-weight','dialysis-ai','dialysis-schedule','clinical-record-list','clinical-record-upload','clinical-abnormal','clinical-trend','health-complication','health-alert','analysis-bp-pattern','health-nutrition','analysis-health-report','analysis-data-export','health-monitoring','health-vitals','workspace','analysis-report','patient-center','patient-care','medication-catalog','medication-log','medication-reminder','health-nutrition-assessment','notification-settings','clinical-workbench','care-journey') WHERE r.role_code='user'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code IN ('dialysis','clinical-record','patient-profile','dialysis-record','dialysis-trend','dialysis-dry-weight','dialysis-ai','dialysis-schedule','clinical-record-list','clinical-abnormal','clinical-trend','health-complication','health-alert','analysis-bp-pattern','analysis-health-report','analysis-data-export','health-monitoring','health-vitals','workspace','analysis-report','patient-center','health-nutrition-assessment','notification-settings','clinical-workbench','doctor-workspace','care-journey') WHERE r.role_code='doctor'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code IN ('dialysis','clinical-record','medication','dialysis-record','dialysis-trend','dialysis-dry-weight','dialysis-ai','dialysis-schedule','clinical-record-list','clinical-record-upload','clinical-abnormal','clinical-trend','health-complication','health-alert','analysis-bp-pattern','health-nutrition','analysis-health-report','analysis-data-export','health-monitoring','health-vitals','workspace','analysis-report','patient-center','patient-care','medication-catalog','medication-log','medication-reminder','health-nutrition-assessment','notification-settings','care-journey') WHERE r.role_code='patient'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code IN ('dialysis','clinical-record','medication','dialysis-record','dialysis-trend','dialysis-schedule','clinical-record-list','clinical-record-upload','clinical-abnormal','clinical-trend','health-complication','health-alert','health-nutrition','analysis-health-report','analysis-data-export','health-monitoring','health-vitals','workspace','analysis-report','patient-center','patient-care','medication-catalog','medication-log','medication-reminder','notification-settings','care-journey') WHERE r.role_code='family'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code IN ('dialysis','dialysis-record','dialysis-trend','dialysis-dry-weight','dialysis-ai','dialysis-schedule') WHERE r.role_code='specialty_dialysis'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code IN ('care-plan-nursing') WHERE r.role_code='nurse'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

-- 五、健康指标、用药安全提示与平台品牌（不代替医生决策）

INSERT IGNORE INTO `health_indicator` (`item_code`, `item_name`, `aliases`, `unit`, `ref_range_male`, `category`, `sort_order`) VALUES
('FERRITIN', '铁蛋白', '铁蛋白,ferritin,serum ferritin', 'ng/mL', '27-375', 'IRON', 10),
('PTH', '甲状旁腺激素', '甲状旁腺激素,parathyroid hormone,intact PTH,iPTH', 'pg/mL', '15-65', 'BONE', 20),
('CA', '血钙', '血钙,钙,calcium,Ca', 'mmol/L', '2.1-2.6', 'BONE', 30),
('P', '血磷', '血磷,磷,phosphorus,P', 'mmol/L', '0.87-1.45', 'BONE', 40),
('CREA', '肌酐', '肌酐,creatinine,serum creatinine,Cr', 'μmol/L', '44-133', 'KIDNEY', 50),
('BUN', '尿素氮', '尿素氮,blood urea nitrogen,BUN', 'mmol/L', '2.9-8.2', 'KIDNEY', 60),
('K', '血钾', '血钾,钾,potassium,serum potassium,K', 'mmol/L', '3.5-5.5', 'BLOOD', 70),
('HB', '血红蛋白', '血红蛋白,hemoglobin,Hb,HGB', 'g/L', '120-160', 'BLOOD', 80);

INSERT IGNORE INTO `medication_safety_rule` (`ingredient_a`,`ingredient_b`,`severity`,`message`,`renal_note`) VALUES
('warfarin','ibuprofen','CRITICAL','华法林与布洛芬合用可显著增加出血风险，请核实开方方案。','除非医生明确要求，晚期肾病患者应避免常规使用 NSAIDs。'),
('warfarin','aspirin','WARNING','华法林与阿司匹林合用会增加出血风险，应有明确且已记录的适应证。',NULL),
('lisinopril','potassium','WARNING','ACE 抑制剂与钾补充剂合用可能升高血钾。','调整治疗前请复核血钾和肾功能。'),
('losartan','potassium','WARNING','ARB 与钾补充剂合用可能升高血钾。','调整治疗前请复核血钾和肾功能。'),
('spironolactone','potassium','CRITICAL','该组合可能导致严重高钾血症，请核实开方方案。','肾功能受损时需要格外谨慎。'),
('calcium carbonate','levothyroxine','INFO','钙剂可能降低左甲状腺素吸收；如医嘱要求，请错开服用时间。',NULL);

INSERT INTO `platform_branding` (`id`,`platform_name`,`organization_name`,`logo_data`,`page_background`,`ownership_text`) VALUES (1,'澄心健康','澄心健康','/logo.svg','#f5f7fb','© 2026 澄心健康 版权所有') ON DUPLICATE KEY UPDATE id=VALUES(id);

-- Exact canonical cp57 prerequisites, fresh empty database only.
DELIMITER $$
CREATE TRIGGER cp57_revision_bi BEFORE INSERT ON care_plan_revision
FOR EACH ROW BEGIN
  IF NEW.revision_no <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan revision_no must be positive';
  END IF;
END$$

CREATE TRIGGER cp57_revision_bu BEFORE UPDATE ON care_plan_revision
FOR EACH ROW BEGIN
  IF NEW.revision_no <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan revision_no must be positive';
  END IF;
END$$

CREATE TRIGGER cp57_action_bi BEFORE INSERT ON care_plan_action
FOR EACH ROW BEGIN
  IF NEW.ordinal NOT BETWEEN 1 AND 50 OR NEW.lock_version < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan ordinal/version outside allowed range';
  END IF;
END$$

CREATE TRIGGER cp57_action_bu BEFORE UPDATE ON care_plan_action
FOR EACH ROW BEGIN
  IF NEW.ordinal NOT BETWEEN 1 AND 50 OR NEW.lock_version < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan ordinal/version outside allowed range';
  END IF;
END$$

CREATE TRIGGER cp57_notification_bi BEFORE INSERT ON care_plan_notification
FOR EACH ROW BEGIN
  IF NEW.attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(NEW.dispatch_key) = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan notification attempts/dispatch invalid';
  END IF;
END$$

CREATE TRIGGER cp57_notification_bu BEFORE UPDATE ON care_plan_notification
FOR EACH ROW BEGIN
  IF NEW.attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(NEW.dispatch_key) = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan notification attempts/dispatch invalid';
  END IF;
END$$

CREATE TRIGGER cp57_command_bi BEFORE INSERT ON care_plan_command
FOR EACH ROW BEGIN
  IF NEW.expected_version < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan command version must be nonnegative';
  END IF;
END$$

CREATE TRIGGER cp57_command_bu BEFORE UPDATE ON care_plan_command
FOR EACH ROW BEGIN
  IF NEW.expected_version < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan command version must be nonnegative';
  END IF;
END$$
DELIMITER ;

-- Reviewed household storage, fresh empty database only. All feature flags remain off.
CREATE TABLE `emergency_assistance_command` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `command_key` VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `emergency_event_id` BIGINT NULL DEFAULT NULL,
  `expected_version` BIGINT NOT NULL,
  `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_eac_actor_key_uq` (`actor_id`,`command_key`) USING BTREE,
  KEY `hh_eac_emergency_event_id_ix` (`emergency_event_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `emergency_assistance_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `emergency_event_id` BIGINT NOT NULL,
  `aggregate_version` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `type` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_kind` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_id` BIGINT NULL DEFAULT NULL,
  `actor_relation` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `occurred_at` DATETIME(6) NOT NULL,
  `reason` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `evidence_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `evidence_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `hh_eae_target_ix` (`emergency_event_id`,`id`) USING BTREE,
  KEY `hh_eae_patient_ix` (`patient_id`,`id`) USING BTREE,
  KEY `hh_eae_actor_id_ix` (`actor_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `emergency_assistance_notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `emergency_event_id` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `recipient_kind` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `recipient_user_id` BIGINT NULL DEFAULT NULL,
  `channel_id` BIGINT NULL DEFAULT NULL,
  `template_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dispatch_key` VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `next_attempt_at` DATETIME(6) NULL DEFAULT NULL,
  `claimed_at` DATETIME(6) NULL DEFAULT NULL,
  `claim_token` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `last_result` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `delivered_at` DATETIME(6) NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_ean_dispatch_uq` (`dispatch_key`) USING BTREE,
  KEY `hh_ean_due_ix` (`status`,`next_attempt_at`,`id`) USING BTREE,
  KEY `hh_ean_recipient_ix` (`recipient_user_id`,`emergency_event_id`,`id`) USING BTREE,
  KEY `hh_ean_event_id_ix` (`event_id`) USING BTREE,
  KEY `hh_ean_patient_id_ix` (`patient_id`) USING BTREE,
  KEY `hh_ean_emergency_event_id_ix` (`emergency_event_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `emergency_assistance_recipient` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `emergency_event_id` BIGINT NOT NULL,
  `recipient_user_id` BIGINT NOT NULL,
  `invitation_event_id` BIGINT NOT NULL,
  `invited_by` BIGINT NOT NULL,
  `invited_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_ear_event_user_uq` (`emergency_event_id`,`recipient_user_id`) USING BTREE,
  KEY `hh_ear_recipient_user_id_ix` (`recipient_user_id`) USING BTREE,
  KEY `hh_ear_invitation_event_id_ix` (`invitation_event_id`) USING BTREE,
  KEY `hh_ear_invited_by_ix` (`invited_by`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `family_assistance` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `source_kind` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `care_item_id` BIGINT NULL DEFAULT NULL,
  `care_appointment_id` BIGINT NULL DEFAULT NULL,
  `request_kind` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_no` INT NOT NULL,
  `previous_assistance_id` BIGINT NULL DEFAULT NULL,
  `originator_id` BIGINT NOT NULL,
  `origin_evidence_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `origin_evidence_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `state` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REQUESTED',
  `lock_version` BIGINT NOT NULL DEFAULT 0,
  `cycle` INT NOT NULL DEFAULT 1,
  `assigned_user_id` BIGINT NULL DEFAULT NULL,
  `accepted_by` BIGINT NULL DEFAULT NULL,
  `accepted_at` DATETIME(6) NULL DEFAULT NULL,
  `handled_by` BIGINT NULL DEFAULT NULL,
  `handled_at` DATETIME(6) NULL DEFAULT NULL,
  `confirmed_at` DATETIME(6) NULL DEFAULT NULL,
  `deadline_at` DATETIME(6) NULL DEFAULT NULL,
  `source_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `reconfirmed_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `accepted_fingerprint` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `source_changed` TINYINT NOT NULL DEFAULT 0,
  `closed_at` DATETIME(6) NULL DEFAULT NULL,
  `close_reason` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_fa_item_request_uq` (`care_item_id`,`request_no`) USING BTREE,
  UNIQUE KEY `hh_fa_appointment_request_uq` (`care_appointment_id`,`request_no`) USING BTREE,
  UNIQUE KEY `hh_fa_previous_uq` (`previous_assistance_id`) USING BTREE,
  KEY `hh_fa_patient_state_ix` (`patient_id`,`state`,`id`) USING BTREE,
  KEY `hh_fa_due_ix` (`state`,`deadline_at`,`id`) USING BTREE,
  KEY `hh_fa_originator_id_ix` (`originator_id`) USING BTREE,
  KEY `hh_fa_assigned_user_id_ix` (`assigned_user_id`) USING BTREE,
  KEY `hh_fa_accepted_by_ix` (`accepted_by`) USING BTREE,
  KEY `hh_fa_handled_by_ix` (`handled_by`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `family_assistance_command` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `command_key` VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `assistance_id` BIGINT NULL DEFAULT NULL,
  `expected_version` BIGINT NOT NULL,
  `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_fac_actor_key_uq` (`actor_id`,`command_key`) USING BTREE,
  KEY `hh_fac_assistance_id_ix` (`assistance_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `family_assistance_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `assistance_id` BIGINT NOT NULL,
  `aggregate_version` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `type` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_kind` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_id` BIGINT NULL DEFAULT NULL,
  `actor_relation` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `occurred_at` DATETIME(6) NOT NULL,
  `reason` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `evidence_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `evidence_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `due_at` DATETIME(6) NULL DEFAULT NULL,
  `due_key` VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_fae_due_uq` (`due_key`) USING BTREE,
  KEY `hh_fae_target_ix` (`assistance_id`,`id`) USING BTREE,
  KEY `hh_fae_patient_ix` (`patient_id`,`id`) USING BTREE,
  KEY `hh_fae_actor_id_ix` (`actor_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `family_assistance_notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `assistance_id` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `recipient_kind` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `recipient_user_id` BIGINT NULL DEFAULT NULL,
  `channel_id` BIGINT NULL DEFAULT NULL,
  `template_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dispatch_key` VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `next_attempt_at` DATETIME(6) NULL DEFAULT NULL,
  `claimed_at` DATETIME(6) NULL DEFAULT NULL,
  `claim_token` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `last_result` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `delivered_at` DATETIME(6) NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_fan_dispatch_uq` (`dispatch_key`) USING BTREE,
  KEY `hh_fan_due_ix` (`status`,`next_attempt_at`,`id`) USING BTREE,
  KEY `hh_fan_recipient_ix` (`recipient_user_id`,`assistance_id`,`id`) USING BTREE,
  KEY `hh_fan_event_id_ix` (`event_id`) USING BTREE,
  KEY `hh_fan_patient_id_ix` (`patient_id`) USING BTREE,
  KEY `hh_fan_assistance_id_ix` (`assistance_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `medication_execution` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `medication_id` BIGINT NOT NULL,
  `provenance` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `lock_version` BIGINT NOT NULL DEFAULT 0,
  `current_reconciliation_id` BIGINT NULL DEFAULT NULL,
  `pending_reconciliation_id` BIGINT NULL DEFAULT NULL,
  `review_required` TINYINT NOT NULL DEFAULT 0,
  `schedule_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `generated_through` DATE NULL DEFAULT NULL,
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_me_patient_med_uq` (`patient_id`,`medication_id`) USING BTREE,
  KEY `hh_me_patient_queue_ix` (`patient_id`,`updated_at`,`id`) USING BTREE,
  KEY `hh_me_medication_id_ix` (`medication_id`) USING BTREE,
  KEY `hh_me_current_reconciliation_id_ix` (`current_reconciliation_id`) USING BTREE,
  KEY `hh_me_pending_reconciliation_id_ix` (`pending_reconciliation_id`) USING BTREE,
  KEY `hh_me_created_by_ix` (`created_by`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `medication_execution_command` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `command_key` VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `execution_id` BIGINT NULL DEFAULT NULL,
  `expected_version` BIGINT NOT NULL,
  `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_mec_actor_key_uq` (`actor_id`,`command_key`) USING BTREE,
  KEY `hh_mec_execution_id_ix` (`execution_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `medication_execution_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `execution_id` BIGINT NOT NULL,
  `aggregate_version` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `type` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_kind` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_id` BIGINT NULL DEFAULT NULL,
  `actor_relation` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `occurred_at` DATETIME(6) NOT NULL,
  `reason` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `evidence_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `evidence_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `due_at` DATETIME(6) NULL DEFAULT NULL,
  `due_key` VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `reconciliation_id` BIGINT NULL DEFAULT NULL,
  `revision_id` BIGINT NULL DEFAULT NULL,
  `intake_id` BIGINT NULL DEFAULT NULL,
  `action_id` BIGINT NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_mee_due_uq` (`due_key`) USING BTREE,
  KEY `hh_mee_target_ix` (`execution_id`,`id`) USING BTREE,
  KEY `hh_mee_patient_ix` (`patient_id`,`id`) USING BTREE,
  UNIQUE KEY `hh_mee_action_uq` (`action_id`) USING BTREE,
  KEY `hh_mee_actor_id_ix` (`actor_id`) USING BTREE,
  KEY `hh_mee_reconciliation_id_ix` (`reconciliation_id`) USING BTREE,
  KEY `hh_mee_revision_id_ix` (`revision_id`) USING BTREE,
  KEY `hh_mee_intake_id_ix` (`intake_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `medication_execution_notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `execution_id` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `recipient_kind` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `recipient_user_id` BIGINT NULL DEFAULT NULL,
  `channel_id` BIGINT NULL DEFAULT NULL,
  `template_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dispatch_key` VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `next_attempt_at` DATETIME(6) NULL DEFAULT NULL,
  `claimed_at` DATETIME(6) NULL DEFAULT NULL,
  `claim_token` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `last_result` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `delivered_at` DATETIME(6) NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  `revision_id` BIGINT NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_men_dispatch_uq` (`dispatch_key`) USING BTREE,
  KEY `hh_men_due_ix` (`status`,`next_attempt_at`,`id`) USING BTREE,
  KEY `hh_men_recipient_ix` (`recipient_user_id`,`execution_id`,`id`) USING BTREE,
  KEY `hh_men_event_id_ix` (`event_id`) USING BTREE,
  KEY `hh_men_patient_id_ix` (`patient_id`) USING BTREE,
  KEY `hh_men_execution_id_ix` (`execution_id`) USING BTREE,
  KEY `hh_men_revision_id_ix` (`revision_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `medication_reconciliation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `execution_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `proposal_no` INT NOT NULL,
  `state` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
  `current_revision_id` BIGINT NULL DEFAULT NULL,
  `selected_verifier_id` BIGINT NULL DEFAULT NULL,
  `verified_revision_id` BIGINT NULL DEFAULT NULL,
  `verified_by` BIGINT NULL DEFAULT NULL,
  `verified_at` DATETIME(6) NULL DEFAULT NULL,
  `scheduled_revision_id` BIGINT NULL DEFAULT NULL,
  `scheduled_by` BIGINT NULL DEFAULT NULL,
  `scheduled_at` DATETIME(6) NULL DEFAULT NULL,
  `replacement_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `replacement_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `applied_at` DATETIME(6) NULL DEFAULT NULL,
  `ended_at` DATETIME(6) NULL DEFAULT NULL,
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  `generated_through` DATE NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_mr_execution_no_uq` (`execution_id`,`proposal_no`) USING BTREE,
  KEY `hh_mr_patient_state_ix` (`patient_id`,`state`,`id`) USING BTREE,
  KEY `hh_mr_state_id_ix` (`state`,`id`) USING BTREE,
  KEY `hh_mr_current_revision_id_ix` (`current_revision_id`) USING BTREE,
  KEY `hh_mr_selected_verifier_id_ix` (`selected_verifier_id`) USING BTREE,
  KEY `hh_mr_verified_revision_id_ix` (`verified_revision_id`) USING BTREE,
  KEY `hh_mr_verified_by_ix` (`verified_by`) USING BTREE,
  KEY `hh_mr_scheduled_revision_id_ix` (`scheduled_revision_id`) USING BTREE,
  KEY `hh_mr_scheduled_by_ix` (`scheduled_by`) USING BTREE,
  KEY `hh_mr_created_by_ix` (`created_by`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `medication_reconciliation_revision` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `reconciliation_id` BIGINT NOT NULL,
  `execution_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `revision_no` INT NOT NULL,
  `source_kind` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `prescription_id` BIGINT NULL DEFAULT NULL,
  `prescription_item_id` BIGINT NULL DEFAULT NULL,
  `source_version` INT NULL DEFAULT NULL,
  `source_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `source_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `mapping_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `mapping_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `action` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `schedule_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `effective_local` DATETIME(6) NOT NULL,
  `effective_offset_seconds` INT NOT NULL,
  `effective_at` DATETIME(6) NOT NULL,
  `end_local` DATETIME(6) NULL DEFAULT NULL,
  `end_offset_seconds` INT NULL DEFAULT NULL,
  `end_at` DATETIME(6) NULL DEFAULT NULL,
  `revision_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `transcribed_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_mrr_proposal_no_uq` (`reconciliation_id`,`revision_no`) USING BTREE,
  KEY `hh_mrr_execution_id_ix` (`execution_id`) USING BTREE,
  KEY `hh_mrr_patient_id_ix` (`patient_id`) USING BTREE,
  KEY `hh_mrr_prescription_id_ix` (`prescription_id`) USING BTREE,
  KEY `hh_mrr_prescription_item_id_ix` (`prescription_item_id`) USING BTREE,
  KEY `hh_mrr_transcribed_by_ix` (`transcribed_by`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `stock_command` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `command_key` VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `stock_id` BIGINT NULL DEFAULT NULL,
  `expected_version` BIGINT NOT NULL,
  `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_sc_actor_key_uq` (`actor_id`,`command_key`) USING BTREE,
  KEY `hh_sc_stock_id_ix` (`stock_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `stock_count` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `stock_id` BIGINT NOT NULL,
  `work_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `medication_id` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `stock_version` BIGINT NOT NULL,
  `quantity` DECIMAL(12,3) NOT NULL,
  `unit` VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `counted_at` DATETIME(6) NOT NULL,
  `actor_id` BIGINT NOT NULL,
  `reason` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `prior_movements_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `prior_actions_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `reviewed_resolutions_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `evidence_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `hh_sc_stock_cycle_ix` (`stock_id`,`cycle`,`id`) USING BTREE,
  KEY `hh_sct_work_id_ix` (`work_id`) USING BTREE,
  KEY `hh_sct_patient_id_ix` (`patient_id`) USING BTREE,
  KEY `hh_sct_medication_id_ix` (`medication_id`) USING BTREE,
  KEY `hh_sct_actor_id_ix` (`actor_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `stock_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `stock_id` BIGINT NOT NULL,
  `aggregate_version` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `type` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_kind` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_id` BIGINT NULL DEFAULT NULL,
  `actor_relation` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `occurred_at` DATETIME(6) NOT NULL,
  `reason` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `evidence_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `evidence_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `due_at` DATETIME(6) NULL DEFAULT NULL,
  `due_key` VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `work_id` BIGINT NOT NULL,
  `intake_id` BIGINT NULL DEFAULT NULL,
  `action_id` BIGINT NULL DEFAULT NULL,
  `execution_event_id` BIGINT NULL DEFAULT NULL,
  `original_movement_id` BIGINT NULL DEFAULT NULL,
  `compensation_movement_id` BIGINT NULL DEFAULT NULL,
  `count_id` BIGINT NULL DEFAULT NULL,
  `prior_stock_event_id` BIGINT NULL DEFAULT NULL,
  `movement_id` BIGINT NULL DEFAULT NULL,
  `settlement_status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `settled_action_id` BIGINT NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_se_due_uq` (`due_key`) USING BTREE,
  KEY `hh_se_target_ix` (`stock_id`,`id`) USING BTREE,
  KEY `hh_se_patient_ix` (`patient_id`,`id`) USING BTREE,
  UNIQUE KEY `hh_se_settled_action_uq` (`settled_action_id`) USING BTREE,
  KEY `hh_se_intake_action_ix` (`intake_id`,`action_id`,`id`) USING BTREE,
  KEY `hh_se_actor_id_ix` (`actor_id`) USING BTREE,
  KEY `hh_se_work_id_ix` (`work_id`) USING BTREE,
  KEY `hh_se_action_id_ix` (`action_id`) USING BTREE,
  KEY `hh_se_execution_event_id_ix` (`execution_event_id`) USING BTREE,
  KEY `hh_se_original_movement_id_ix` (`original_movement_id`) USING BTREE,
  KEY `hh_se_compensation_movement_id_ix` (`compensation_movement_id`) USING BTREE,
  KEY `hh_se_count_id_ix` (`count_id`) USING BTREE,
  KEY `hh_se_prior_stock_event_id_ix` (`prior_stock_event_id`) USING BTREE,
  KEY `hh_se_movement_id_ix` (`movement_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `stock_intake_resolution` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `stock_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `medication_id` BIGINT NOT NULL,
  `intake_id` BIGINT NOT NULL,
  `current_action_id` BIGINT NOT NULL,
  `execution_event_id` BIGINT NOT NULL,
  `state` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNRECONCILED',
  `stock_event_id` BIGINT NOT NULL,
  `count_id` BIGINT NULL DEFAULT NULL,
  `lock_version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_sir_intake_uq` (`intake_id`) USING BTREE,
  KEY `hh_sir_stock_state_ix` (`stock_id`,`state`,`id`) USING BTREE,
  KEY `hh_sir_patient_id_ix` (`patient_id`) USING BTREE,
  KEY `hh_sir_medication_id_ix` (`medication_id`) USING BTREE,
  KEY `hh_sir_current_action_id_ix` (`current_action_id`) USING BTREE,
  KEY `hh_sir_execution_event_id_ix` (`execution_event_id`) USING BTREE,
  KEY `hh_sir_stock_event_id_ix` (`stock_event_id`) USING BTREE,
  KEY `hh_sir_count_id_ix` (`count_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `stock_notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `stock_id` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `recipient_kind` VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `recipient_user_id` BIGINT NULL DEFAULT NULL,
  `channel_id` BIGINT NULL DEFAULT NULL,
  `template_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dispatch_key` VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `next_attempt_at` DATETIME(6) NULL DEFAULT NULL,
  `claimed_at` DATETIME(6) NULL DEFAULT NULL,
  `claim_token` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `last_result` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `delivered_at` DATETIME(6) NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_sn_dispatch_uq` (`dispatch_key`) USING BTREE,
  KEY `hh_sn_due_ix` (`status`,`next_attempt_at`,`id`) USING BTREE,
  KEY `hh_sn_recipient_ix` (`recipient_user_id`,`stock_id`,`id`) USING BTREE,
  KEY `hh_sn_event_id_ix` (`event_id`) USING BTREE,
  KEY `hh_sn_patient_id_ix` (`patient_id`) USING BTREE,
  KEY `hh_sn_stock_id_ix` (`stock_id`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `stock_work` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `stock_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `medication_id` BIGINT NOT NULL,
  `cycle` INT NOT NULL,
  `previous_work_id` BIGINT NULL DEFAULT NULL,
  `state` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
  `assigned_user_id` BIGINT NULL DEFAULT NULL,
  `opening_quantity` DECIMAL(12,3) NOT NULL,
  `opening_unit` VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `warning_quantity` DECIMAL(12,3) NOT NULL,
  `warning_days` INT NOT NULL,
  `daily_use_quantity` DECIMAL(12,3) NULL DEFAULT NULL,
  `opening_evidence_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `opening_evidence_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `opened_at` DATETIME(6) NOT NULL,
  `closed_at` DATETIME(6) NULL DEFAULT NULL,
  `closing_event_id` BIGINT NULL DEFAULT NULL,
  `created_by` BIGINT NULL DEFAULT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `hh_sw_stock_cycle_uq` (`stock_id`,`cycle`) USING BTREE,
  UNIQUE KEY `hh_sw_previous_uq` (`previous_work_id`) USING BTREE,
  KEY `hh_sw_patient_state_ix` (`patient_id`,`state`,`id`) USING BTREE,
  KEY `hh_sw_medication_id_ix` (`medication_id`) USING BTREE,
  KEY `hh_sw_assigned_user_id_ix` (`assigned_user_id`) USING BTREE,
  KEY `hh_sw_closing_event_id_ix` (`closing_event_id`) USING BTREE,
  KEY `hh_sw_created_by_ix` (`created_by`) USING BTREE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE `care_appointment`
  ADD COLUMN `family_workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `family_assistance_id` BIGINT NULL DEFAULT NULL,
  ADD KEY `hh_nca_family_assistance_id_ix` (`family_assistance_id`) USING BTREE;

ALTER TABLE `care_intake_action`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `execution_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `execution_event_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `original_action_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `action_kind` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `actual_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `recorded_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `unit` VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  ADD COLUMN `amount_basis` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `correction_reason` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  ADD COLUMN `provenance_json` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  ADD UNIQUE KEY `hh_nia_event_uq` (`execution_event_id`) USING BTREE,
  ADD KEY `hh_nia_execution_id_ix` (`execution_id`) USING BTREE,
  ADD KEY `hh_nia_original_action_id_ix` (`original_action_id`) USING BTREE;

ALTER TABLE `care_item`
  ADD COLUMN `medication_workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `medication_execution_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `medication_reconciliation_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `family_workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `family_assistance_id` BIGINT NULL DEFAULT NULL,
  ADD KEY `hh_nci_medication_execution_id_ix` (`medication_execution_id`) USING BTREE,
  ADD KEY `hh_nci_medication_reconciliation_id_ix` (`medication_reconciliation_id`) USING BTREE,
  ADD KEY `hh_nci_family_assistance_id_ix` (`family_assistance_id`) USING BTREE;

ALTER TABLE `electronic_prescription`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0;

ALTER TABLE `electronic_prescription_item`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0;

ALTER TABLE `emergency_event`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `lock_version` BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN `workflow_state` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `response_status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `requester_user_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `current_lead_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `pending_successor_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `requested_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `accepted_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `last_progress_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `resolved_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `snapshot_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `outcome_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `outcome_reason` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  ADD KEY `hh_nee_patient_state_ix` (`patient_id`,`workflow_state`,`id`) USING BTREE,
  ADD KEY `hh_nee_requester_user_id_ix` (`requester_user_id`) USING BTREE,
  ADD KEY `hh_nee_current_lead_id_ix` (`current_lead_id`) USING BTREE,
  ADD KEY `hh_nee_pending_successor_id_ix` (`pending_successor_id`) USING BTREE;

ALTER TABLE `medication`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `execution_id` BIGINT NULL DEFAULT NULL,
  ADD UNIQUE KEY `hh_nm_execution_uq` (`execution_id`) USING BTREE;

ALTER TABLE `medication_intake`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `execution_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `reconciliation_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `provenance` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `planned_quantity` DECIMAL(12,3) NULL DEFAULT NULL,
  ADD COLUMN `planned_unit` VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  ADD COLUMN `schedule_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `scheduled_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `schedule_offset_seconds` INT NULL DEFAULT NULL,
  ADD COLUMN `actual_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `latest_action_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `follow_up_state` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `follow_up_kind` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `follow_up_cycle` INT NOT NULL DEFAULT 0,
  ADD COLUMN `follow_up_due_at` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `snooze_until_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD UNIQUE KEY `hh_nmi_utc_slot_uq` (`reminder_id`,`scheduled_at_utc`) USING BTREE,
  ADD KEY `hh_nmi_execution_due_ix` (`execution_id`,`follow_up_state`,`follow_up_due_at`,`id`) USING BTREE,
  ADD KEY `hh_nmi_patient_med_id_ix` (`patient_id`,`medication_id`,`id`) USING BTREE,
  ADD KEY `hh_nmi_reconciliation_id_ix` (`reconciliation_id`) USING BTREE,
  ADD KEY `hh_nmi_latest_action_id_ix` (`latest_action_id`) USING BTREE,
  ADD KEY `hh_nmi_execution_slot_ix` (`execution_id`,`scheduled_at_utc`,`id`) USING BTREE,
  ADD KEY `hh_nmi_follow_due_ix` (`workflow_version`,`follow_up_state`,`follow_up_due_at`,`id`) USING BTREE;

ALTER TABLE `medication_reminder`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `execution_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `reconciliation_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `order_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `planned_quantity` DECIMAL(12,3) NULL DEFAULT NULL,
  ADD COLUMN `planned_unit` VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  ADD COLUMN `schedule_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `effective_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD COLUMN `end_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD KEY `hh_nmr_execution_id_ix` (`execution_id`) USING BTREE,
  ADD KEY `hh_nmr_reconciliation_id_ix` (`reconciliation_id`) USING BTREE,
  ADD KEY `hh_nmr_order_id_ix` (`order_id`) USING BTREE;

ALTER TABLE `medication_stock`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `lock_version` BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN `current_work_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `adopted_at` DATETIME(6) NULL DEFAULT NULL,
  ADD UNIQUE KEY `hh_ns_current_work_uq` (`current_work_id`) USING BTREE;

ALTER TABLE `medication_stock_movement`
  ADD COLUMN `workflow_version` TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN `stock_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `intake_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `action_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `count_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `stock_event_id` BIGINT NULL DEFAULT NULL,
  ADD COLUMN `movement_kind` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN `unit` VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  ADD COLUMN `actual_at_utc` DATETIME(6) NULL DEFAULT NULL,
  ADD KEY `hh_nsm_stock_history_ix` (`stock_id`,`id`) USING BTREE,
  ADD UNIQUE KEY `hh_nsm_count_uq` (`count_id`) USING BTREE,
  ADD KEY `hh_nsm_intake_id_ix` (`intake_id`) USING BTREE,
  ADD KEY `hh_nsm_action_id_ix` (`action_id`) USING BTREE,
  ADD KEY `hh_nsm_stock_event_id_ix` (`stock_event_id`) USING BTREE,
  ADD KEY `hh_nsm_patient_med_history_ix` (`patient_id`,`medication_id`,`id`) USING BTREE;

ALTER TABLE `sys_role_menu`
  ADD KEY `hh_nrm_role_id_ix` (`role_id`,`id`) USING BTREE;

ALTER TABLE `sys_user_role`
  ADD KEY `hh_nur_role_user_ix` (`role_id`,`user_id`,`id`) USING BTREE;

ALTER TABLE `emergency_assistance_command`
  ADD CONSTRAINT `hh_eac_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_eac_emergency_event_id_fk` FOREIGN KEY (`emergency_event_id`) REFERENCES `emergency_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `emergency_assistance_event`
  ADD CONSTRAINT `hh_eae_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_eae_emergency_event_id_fk` FOREIGN KEY (`emergency_event_id`) REFERENCES `emergency_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_eae_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `emergency_assistance_notification`
  ADD CONSTRAINT `hh_ean_event_id_fk` FOREIGN KEY (`event_id`) REFERENCES `emergency_assistance_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_ean_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_ean_emergency_event_id_fk` FOREIGN KEY (`emergency_event_id`) REFERENCES `emergency_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_ean_recipient_user_id_fk` FOREIGN KEY (`recipient_user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `emergency_assistance_recipient`
  ADD CONSTRAINT `hh_ear_emergency_event_id_fk` FOREIGN KEY (`emergency_event_id`) REFERENCES `emergency_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_ear_recipient_user_id_fk` FOREIGN KEY (`recipient_user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_ear_invitation_event_id_fk` FOREIGN KEY (`invitation_event_id`) REFERENCES `emergency_assistance_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_ear_invited_by_fk` FOREIGN KEY (`invited_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `family_assistance`
  ADD CONSTRAINT `hh_fa_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fa_care_item_id_fk` FOREIGN KEY (`care_item_id`) REFERENCES `care_item` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fa_care_appointment_id_fk` FOREIGN KEY (`care_appointment_id`) REFERENCES `care_appointment` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fa_previous_assistance_id_fk` FOREIGN KEY (`previous_assistance_id`) REFERENCES `family_assistance` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fa_originator_id_fk` FOREIGN KEY (`originator_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fa_assigned_user_id_fk` FOREIGN KEY (`assigned_user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fa_accepted_by_fk` FOREIGN KEY (`accepted_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fa_handled_by_fk` FOREIGN KEY (`handled_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `family_assistance_command`
  ADD CONSTRAINT `hh_fac_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fac_assistance_id_fk` FOREIGN KEY (`assistance_id`) REFERENCES `family_assistance` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `family_assistance_event`
  ADD CONSTRAINT `hh_fae_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fae_assistance_id_fk` FOREIGN KEY (`assistance_id`) REFERENCES `family_assistance` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fae_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `family_assistance_notification`
  ADD CONSTRAINT `hh_fan_event_id_fk` FOREIGN KEY (`event_id`) REFERENCES `family_assistance_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fan_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fan_assistance_id_fk` FOREIGN KEY (`assistance_id`) REFERENCES `family_assistance` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_fan_recipient_user_id_fk` FOREIGN KEY (`recipient_user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_execution`
  ADD CONSTRAINT `hh_me_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_me_medication_id_fk` FOREIGN KEY (`medication_id`) REFERENCES `medication` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_me_current_reconciliation_id_fk` FOREIGN KEY (`current_reconciliation_id`) REFERENCES `medication_reconciliation` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_me_pending_reconciliation_id_fk` FOREIGN KEY (`pending_reconciliation_id`) REFERENCES `medication_reconciliation` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_me_created_by_fk` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_execution_command`
  ADD CONSTRAINT `hh_mec_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mec_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_execution_event`
  ADD CONSTRAINT `hh_mee_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mee_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mee_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mee_reconciliation_id_fk` FOREIGN KEY (`reconciliation_id`) REFERENCES `medication_reconciliation` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mee_revision_id_fk` FOREIGN KEY (`revision_id`) REFERENCES `medication_reconciliation_revision` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mee_intake_id_fk` FOREIGN KEY (`intake_id`) REFERENCES `medication_intake` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mee_action_id_fk` FOREIGN KEY (`action_id`) REFERENCES `care_intake_action` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_execution_notification`
  ADD CONSTRAINT `hh_men_event_id_fk` FOREIGN KEY (`event_id`) REFERENCES `medication_execution_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_men_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_men_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_men_recipient_user_id_fk` FOREIGN KEY (`recipient_user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_men_revision_id_fk` FOREIGN KEY (`revision_id`) REFERENCES `medication_reconciliation_revision` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_reconciliation`
  ADD CONSTRAINT `hh_mr_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mr_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mr_current_revision_id_fk` FOREIGN KEY (`current_revision_id`) REFERENCES `medication_reconciliation_revision` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mr_selected_verifier_id_fk` FOREIGN KEY (`selected_verifier_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mr_verified_revision_id_fk` FOREIGN KEY (`verified_revision_id`) REFERENCES `medication_reconciliation_revision` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mr_verified_by_fk` FOREIGN KEY (`verified_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mr_scheduled_revision_id_fk` FOREIGN KEY (`scheduled_revision_id`) REFERENCES `medication_reconciliation_revision` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mr_scheduled_by_fk` FOREIGN KEY (`scheduled_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mr_created_by_fk` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_reconciliation_revision`
  ADD CONSTRAINT `hh_mrr_reconciliation_id_fk` FOREIGN KEY (`reconciliation_id`) REFERENCES `medication_reconciliation` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mrr_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mrr_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mrr_prescription_id_fk` FOREIGN KEY (`prescription_id`) REFERENCES `electronic_prescription` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mrr_prescription_item_id_fk` FOREIGN KEY (`prescription_item_id`) REFERENCES `electronic_prescription_item` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_mrr_transcribed_by_fk` FOREIGN KEY (`transcribed_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `stock_command`
  ADD CONSTRAINT `hh_sc_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sc_stock_id_fk` FOREIGN KEY (`stock_id`) REFERENCES `medication_stock` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `stock_count`
  ADD CONSTRAINT `hh_sct_stock_id_fk` FOREIGN KEY (`stock_id`) REFERENCES `medication_stock` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sct_work_id_fk` FOREIGN KEY (`work_id`) REFERENCES `stock_work` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sct_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sct_medication_id_fk` FOREIGN KEY (`medication_id`) REFERENCES `medication` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sct_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `stock_event`
  ADD CONSTRAINT `hh_se_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_stock_id_fk` FOREIGN KEY (`stock_id`) REFERENCES `medication_stock` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_actor_id_fk` FOREIGN KEY (`actor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_work_id_fk` FOREIGN KEY (`work_id`) REFERENCES `stock_work` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_intake_id_fk` FOREIGN KEY (`intake_id`) REFERENCES `medication_intake` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_action_id_fk` FOREIGN KEY (`action_id`) REFERENCES `care_intake_action` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_execution_event_id_fk` FOREIGN KEY (`execution_event_id`) REFERENCES `medication_execution_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_original_movement_id_fk` FOREIGN KEY (`original_movement_id`) REFERENCES `medication_stock_movement` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_compensation_movement_id_fk` FOREIGN KEY (`compensation_movement_id`) REFERENCES `medication_stock_movement` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_count_id_fk` FOREIGN KEY (`count_id`) REFERENCES `stock_count` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_prior_stock_event_id_fk` FOREIGN KEY (`prior_stock_event_id`) REFERENCES `stock_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_movement_id_fk` FOREIGN KEY (`movement_id`) REFERENCES `medication_stock_movement` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_se_settled_action_id_fk` FOREIGN KEY (`settled_action_id`) REFERENCES `care_intake_action` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `stock_intake_resolution`
  ADD CONSTRAINT `hh_sir_stock_id_fk` FOREIGN KEY (`stock_id`) REFERENCES `medication_stock` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sir_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sir_medication_id_fk` FOREIGN KEY (`medication_id`) REFERENCES `medication` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sir_intake_id_fk` FOREIGN KEY (`intake_id`) REFERENCES `medication_intake` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sir_current_action_id_fk` FOREIGN KEY (`current_action_id`) REFERENCES `care_intake_action` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sir_execution_event_id_fk` FOREIGN KEY (`execution_event_id`) REFERENCES `medication_execution_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sir_stock_event_id_fk` FOREIGN KEY (`stock_event_id`) REFERENCES `stock_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sir_count_id_fk` FOREIGN KEY (`count_id`) REFERENCES `stock_count` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `stock_notification`
  ADD CONSTRAINT `hh_sn_event_id_fk` FOREIGN KEY (`event_id`) REFERENCES `stock_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sn_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sn_stock_id_fk` FOREIGN KEY (`stock_id`) REFERENCES `medication_stock` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sn_recipient_user_id_fk` FOREIGN KEY (`recipient_user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `stock_work`
  ADD CONSTRAINT `hh_sw_stock_id_fk` FOREIGN KEY (`stock_id`) REFERENCES `medication_stock` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sw_patient_id_fk` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sw_medication_id_fk` FOREIGN KEY (`medication_id`) REFERENCES `medication` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sw_previous_work_id_fk` FOREIGN KEY (`previous_work_id`) REFERENCES `stock_work` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sw_assigned_user_id_fk` FOREIGN KEY (`assigned_user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sw_closing_event_id_fk` FOREIGN KEY (`closing_event_id`) REFERENCES `stock_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_sw_created_by_fk` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `care_appointment`
  ADD CONSTRAINT `hh_nca_family_assistance_id_fk` FOREIGN KEY (`family_assistance_id`) REFERENCES `family_assistance` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `care_intake_action`
  ADD CONSTRAINT `hh_nia_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nia_execution_event_id_fk` FOREIGN KEY (`execution_event_id`) REFERENCES `medication_execution_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nia_original_action_id_fk` FOREIGN KEY (`original_action_id`) REFERENCES `care_intake_action` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `care_item`
  ADD CONSTRAINT `hh_nci_medication_execution_id_fk` FOREIGN KEY (`medication_execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nci_medication_reconciliation_id_fk` FOREIGN KEY (`medication_reconciliation_id`) REFERENCES `medication_reconciliation` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nci_family_assistance_id_fk` FOREIGN KEY (`family_assistance_id`) REFERENCES `family_assistance` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `emergency_event`
  ADD CONSTRAINT `hh_nee_requester_user_id_fk` FOREIGN KEY (`requester_user_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nee_current_lead_id_fk` FOREIGN KEY (`current_lead_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nee_pending_successor_id_fk` FOREIGN KEY (`pending_successor_id`) REFERENCES `sys_user` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication`
  ADD CONSTRAINT `hh_nm_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_intake`
  ADD CONSTRAINT `hh_nmi_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nmi_reconciliation_id_fk` FOREIGN KEY (`reconciliation_id`) REFERENCES `medication_reconciliation` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nmi_latest_action_id_fk` FOREIGN KEY (`latest_action_id`) REFERENCES `care_intake_action` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_reminder`
  ADD CONSTRAINT `hh_nmr_execution_id_fk` FOREIGN KEY (`execution_id`) REFERENCES `medication_execution` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nmr_reconciliation_id_fk` FOREIGN KEY (`reconciliation_id`) REFERENCES `medication_reconciliation` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nmr_order_id_fk` FOREIGN KEY (`order_id`) REFERENCES `care_item` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_stock`
  ADD CONSTRAINT `hh_ns_current_work_id_fk` FOREIGN KEY (`current_work_id`) REFERENCES `stock_work` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE `medication_stock_movement`
  ADD CONSTRAINT `hh_nsm_stock_id_fk` FOREIGN KEY (`stock_id`) REFERENCES `medication_stock` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nsm_intake_id_fk` FOREIGN KEY (`intake_id`) REFERENCES `medication_intake` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nsm_action_id_fk` FOREIGN KEY (`action_id`) REFERENCES `care_intake_action` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nsm_count_id_fk` FOREIGN KEY (`count_id`) REFERENCES `stock_count` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT `hh_nsm_stock_event_id_fk` FOREIGN KEY (`stock_event_id`) REFERENCES `stock_event` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;

SET @hh_saved_sql_mode=@@SESSION.sql_mode;

SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION';

SET NAMES utf8mb4 COLLATE utf8mb4_bin;

DELIMITER $$

CREATE TRIGGER hh_me_bi BEFORE INSERT ON medication_execution
FOR EACH ROW BEGIN
  IF ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND (NEW.medication_id <= 0 OR NEW.medication_id > 9007199254740991)))
    OR (NEW.lock_version < 0)
    OR ((NEW.current_reconciliation_id IS NOT NULL AND (NEW.current_reconciliation_id <= 0 OR NEW.current_reconciliation_id > 9007199254740991)))
    OR ((NEW.pending_reconciliation_id IS NOT NULL AND (NEW.pending_reconciliation_id <= 0 OR NEW.pending_reconciliation_id > 9007199254740991)))
    OR ((NEW.created_by IS NOT NULL AND (NEW.created_by <= 0 OR NEW.created_by > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication r WHERE r.id=NEW.medication_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.current_reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.current_reconciliation_id AND r.execution_id=NEW.id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.pending_reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.pending_reconciliation_id AND r.execution_id=NEW.id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.provenance IS NULL OR NEW.provenance NOT IN ('PERSONAL_UNVERIFIED','PROFESSIONAL')))
    OR (NEW.review_required NOT IN (0,1))
    OR ((NEW.schedule_zone IS NOT NULL AND CHAR_LENGTH(TRIM(NEW.schedule_zone))=0))
    OR ((NEW.current_reconciliation_id IS NOT NULL AND NEW.current_reconciliation_id=NEW.pending_reconciliation_id)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_execution';
  END IF;
END$$

CREATE TRIGGER hh_me_bu BEFORE UPDATE ON medication_execution
FOR EACH ROW BEGIN
  IF ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND (NEW.medication_id <= 0 OR NEW.medication_id > 9007199254740991)))
    OR (NEW.lock_version < 0)
    OR ((NEW.current_reconciliation_id IS NOT NULL AND (NEW.current_reconciliation_id <= 0 OR NEW.current_reconciliation_id > 9007199254740991)))
    OR ((NEW.pending_reconciliation_id IS NOT NULL AND (NEW.pending_reconciliation_id <= 0 OR NEW.pending_reconciliation_id > 9007199254740991)))
    OR ((NEW.created_by IS NOT NULL AND (NEW.created_by <= 0 OR NEW.created_by > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication r WHERE r.id=NEW.medication_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.current_reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.current_reconciliation_id AND r.execution_id=NEW.id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.pending_reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.pending_reconciliation_id AND r.execution_id=NEW.id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.provenance IS NULL OR NEW.provenance NOT IN ('PERSONAL_UNVERIFIED','PROFESSIONAL')))
    OR (NEW.review_required NOT IN (0,1))
    OR ((NEW.schedule_zone IS NOT NULL AND CHAR_LENGTH(TRIM(NEW.schedule_zone))=0))
    OR ((NEW.current_reconciliation_id IS NOT NULL AND NEW.current_reconciliation_id=NEW.pending_reconciliation_id))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.provenance <=> BINARY OLD.provenance) AND (BINARY NEW.created_by <=> BINARY OLD.created_by) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR ((NEW.lock_version<OLD.lock_version OR NEW.lock_version>OLD.lock_version+1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_execution';
  END IF;
END$$

CREATE TRIGGER hh_me_bd BEFORE DELETE ON medication_execution
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_mr_bi BEFORE INSERT ON medication_reconciliation
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.current_revision_id IS NOT NULL AND (NEW.current_revision_id <= 0 OR NEW.current_revision_id > 9007199254740991)))
    OR ((NEW.selected_verifier_id IS NOT NULL AND (NEW.selected_verifier_id <= 0 OR NEW.selected_verifier_id > 9007199254740991)))
    OR ((NEW.verified_revision_id IS NOT NULL AND (NEW.verified_revision_id <= 0 OR NEW.verified_revision_id > 9007199254740991)))
    OR ((NEW.verified_by IS NOT NULL AND (NEW.verified_by <= 0 OR NEW.verified_by > 9007199254740991)))
    OR ((NEW.scheduled_revision_id IS NOT NULL AND (NEW.scheduled_revision_id <= 0 OR NEW.scheduled_revision_id > 9007199254740991)))
    OR ((NEW.scheduled_by IS NOT NULL AND (NEW.scheduled_by <= 0 OR NEW.scheduled_by > 9007199254740991)))
    OR ((NEW.replacement_json IS NOT NULL AND NOT JSON_VALID(NEW.replacement_json)))
    OR ((NEW.replacement_hash IS NOT NULL AND NEW.replacement_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.created_by IS NOT NULL AND (NEW.created_by <= 0 OR NEW.created_by > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.current_revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.current_revision_id AND r.reconciliation_id=NEW.id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.verified_revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.verified_revision_id AND r.reconciliation_id=NEW.id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.scheduled_revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.scheduled_revision_id AND r.reconciliation_id=NEW.id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR (NEW.proposal_no <= 0)
    OR ((NEW.state IN ('READY','SCHEDULED','ACTIVE','SUPERSEDED','ENDED') AND (NEW.current_revision_id IS NULL OR NEW.verified_revision_id IS NULL OR NEW.verified_by IS NULL OR NEW.verified_at IS NULL)))
    OR ((NEW.state IN ('SCHEDULED','ACTIVE','SUPERSEDED','ENDED') AND (NEW.scheduled_revision_id IS NULL OR NEW.scheduled_by IS NULL OR NEW.scheduled_at IS NULL OR NEW.replacement_json IS NULL OR NEW.replacement_hash IS NULL)))
    OR ((NEW.state IN ('ACTIVE','SUPERSEDED','ENDED') AND NEW.applied_at IS NULL))
    OR ((NEW.state IN ('SUPERSEDED','ENDED') AND NEW.ended_at IS NULL))
    OR ((NEW.state IN ('SCHEDULED','ACTIVE','SUPERSEDED','ENDED') AND (NEW.scheduled_revision_id IS NULL OR NEW.verified_revision_id IS NULL OR NEW.current_revision_id IS NULL OR NEW.scheduled_revision_id<>NEW.verified_revision_id OR NEW.scheduled_revision_id<>NEW.current_revision_id)))
    OR ((NEW.state IN ('READY','SCHEDULED','ACTIVE','SUPERSEDED','ENDED') AND (NEW.verified_revision_id<>NEW.current_revision_id OR NEW.selected_verifier_id IS NULL OR NEW.verified_by<>NEW.selected_verifier_id)))
    OR ((NEW.state IS NULL OR NEW.state NOT IN ('DRAFT','READY','RETURNED','CANCELLED','SCHEDULED','ACTIVE','SUPERSEDED','ENDED','SOURCE_CHANGED','REPLACEMENT_CHANGED','TIME_UNRESOLVED')))
    OR ((NEW.generated_through IS NOT NULL AND (NEW.applied_at IS NULL OR NEW.state NOT IN ('ACTIVE','ENDED','SUPERSEDED') OR NEW.scheduled_revision_id IS NULL OR NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.scheduled_revision_id AND NEW.generated_through>=DATE(r.effective_local) AND (r.end_local IS NULL OR NEW.generated_through<=DATE(r.end_local)))))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_reconciliation';
  END IF;
END$$

CREATE TRIGGER hh_mr_bu BEFORE UPDATE ON medication_reconciliation
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.current_revision_id IS NOT NULL AND (NEW.current_revision_id <= 0 OR NEW.current_revision_id > 9007199254740991)))
    OR ((NEW.selected_verifier_id IS NOT NULL AND (NEW.selected_verifier_id <= 0 OR NEW.selected_verifier_id > 9007199254740991)))
    OR ((NEW.verified_revision_id IS NOT NULL AND (NEW.verified_revision_id <= 0 OR NEW.verified_revision_id > 9007199254740991)))
    OR ((NEW.verified_by IS NOT NULL AND (NEW.verified_by <= 0 OR NEW.verified_by > 9007199254740991)))
    OR ((NEW.scheduled_revision_id IS NOT NULL AND (NEW.scheduled_revision_id <= 0 OR NEW.scheduled_revision_id > 9007199254740991)))
    OR ((NEW.scheduled_by IS NOT NULL AND (NEW.scheduled_by <= 0 OR NEW.scheduled_by > 9007199254740991)))
    OR ((NEW.replacement_json IS NOT NULL AND NOT JSON_VALID(NEW.replacement_json)))
    OR ((NEW.replacement_hash IS NOT NULL AND NEW.replacement_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.created_by IS NOT NULL AND (NEW.created_by <= 0 OR NEW.created_by > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.current_revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.current_revision_id AND r.reconciliation_id=NEW.id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.verified_revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.verified_revision_id AND r.reconciliation_id=NEW.id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.scheduled_revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.scheduled_revision_id AND r.reconciliation_id=NEW.id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR (NEW.proposal_no <= 0)
    OR ((NEW.state IN ('READY','SCHEDULED','ACTIVE','SUPERSEDED','ENDED') AND (NEW.current_revision_id IS NULL OR NEW.verified_revision_id IS NULL OR NEW.verified_by IS NULL OR NEW.verified_at IS NULL)))
    OR ((NEW.state IN ('SCHEDULED','ACTIVE','SUPERSEDED','ENDED') AND (NEW.scheduled_revision_id IS NULL OR NEW.scheduled_by IS NULL OR NEW.scheduled_at IS NULL OR NEW.replacement_json IS NULL OR NEW.replacement_hash IS NULL)))
    OR ((NEW.state IN ('ACTIVE','SUPERSEDED','ENDED') AND NEW.applied_at IS NULL))
    OR ((NEW.state IN ('SUPERSEDED','ENDED') AND NEW.ended_at IS NULL))
    OR ((NEW.state IN ('SCHEDULED','ACTIVE','SUPERSEDED','ENDED') AND (NEW.scheduled_revision_id IS NULL OR NEW.verified_revision_id IS NULL OR NEW.current_revision_id IS NULL OR NEW.scheduled_revision_id<>NEW.verified_revision_id OR NEW.scheduled_revision_id<>NEW.current_revision_id)))
    OR ((NEW.state IN ('READY','SCHEDULED','ACTIVE','SUPERSEDED','ENDED') AND (NEW.verified_revision_id<>NEW.current_revision_id OR NEW.selected_verifier_id IS NULL OR NEW.verified_by<>NEW.selected_verifier_id)))
    OR ((NEW.state IS NULL OR NEW.state NOT IN ('DRAFT','READY','RETURNED','CANCELLED','SCHEDULED','ACTIVE','SUPERSEDED','ENDED','SOURCE_CHANGED','REPLACEMENT_CHANGED','TIME_UNRESOLVED')))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.execution_id <=> BINARY OLD.execution_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.proposal_no <=> BINARY OLD.proposal_no) AND (BINARY NEW.created_by <=> BINARY OLD.created_by) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR ((OLD.state='ACTIVE' AND NOT ((BINARY NEW.current_revision_id <=> BINARY OLD.current_revision_id) AND (BINARY NEW.selected_verifier_id <=> BINARY OLD.selected_verifier_id) AND (BINARY NEW.verified_revision_id <=> BINARY OLD.verified_revision_id) AND (BINARY NEW.verified_by <=> BINARY OLD.verified_by) AND (BINARY NEW.verified_at <=> BINARY OLD.verified_at) AND (BINARY NEW.scheduled_revision_id <=> BINARY OLD.scheduled_revision_id) AND (BINARY NEW.scheduled_by <=> BINARY OLD.scheduled_by) AND (BINARY NEW.scheduled_at <=> BINARY OLD.scheduled_at) AND (BINARY NEW.replacement_json <=> BINARY OLD.replacement_json) AND (BINARY NEW.replacement_hash <=> BINARY OLD.replacement_hash) AND (BINARY NEW.applied_at <=> BINARY OLD.applied_at))))
    OR ((OLD.state IN ('CANCELLED','SUPERSEDED','ENDED') AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.execution_id <=> BINARY OLD.execution_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.proposal_no <=> BINARY OLD.proposal_no) AND (BINARY NEW.state <=> BINARY OLD.state) AND (BINARY NEW.current_revision_id <=> BINARY OLD.current_revision_id) AND (BINARY NEW.selected_verifier_id <=> BINARY OLD.selected_verifier_id) AND (BINARY NEW.verified_revision_id <=> BINARY OLD.verified_revision_id) AND (BINARY NEW.verified_by <=> BINARY OLD.verified_by) AND (BINARY NEW.verified_at <=> BINARY OLD.verified_at) AND (BINARY NEW.scheduled_revision_id <=> BINARY OLD.scheduled_revision_id) AND (BINARY NEW.scheduled_by <=> BINARY OLD.scheduled_by) AND (BINARY NEW.scheduled_at <=> BINARY OLD.scheduled_at) AND (BINARY NEW.replacement_json <=> BINARY OLD.replacement_json) AND (BINARY NEW.replacement_hash <=> BINARY OLD.replacement_hash) AND (BINARY NEW.applied_at <=> BINARY OLD.applied_at) AND (BINARY NEW.ended_at <=> BINARY OLD.ended_at) AND (BINARY NEW.created_by <=> BINARY OLD.created_by) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.updated_at <=> BINARY OLD.updated_at))))
    OR (NOT (NEW.state=OLD.state OR (OLD.state IN ('DRAFT','RETURNED','SOURCE_CHANGED','REPLACEMENT_CHANGED','TIME_UNRESOLVED') AND NEW.state IN ('DRAFT','READY','RETURNED','CANCELLED','SOURCE_CHANGED')) OR (OLD.state='READY' AND NEW.state IN ('DRAFT','RETURNED','CANCELLED','SCHEDULED','SOURCE_CHANGED','REPLACEMENT_CHANGED','TIME_UNRESOLVED')) OR (OLD.state='SCHEDULED' AND NEW.state IN ('READY','ACTIVE','CANCELLED','SOURCE_CHANGED','REPLACEMENT_CHANGED','TIME_UNRESOLVED')) OR (OLD.state='ACTIVE' AND NEW.state IN ('SUPERSEDED','ENDED'))))
    OR ((NEW.generated_through IS NOT NULL AND (NEW.applied_at IS NULL OR NEW.state NOT IN ('ACTIVE','ENDED','SUPERSEDED') OR NEW.scheduled_revision_id IS NULL OR NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.scheduled_revision_id AND NEW.generated_through>=DATE(r.effective_local) AND (r.end_local IS NULL OR NEW.generated_through<=DATE(r.end_local))))))
    OR ((OLD.generated_through IS NOT NULL AND (NEW.generated_through IS NULL OR NEW.generated_through < OLD.generated_through))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_reconciliation';
  END IF;
END$$

CREATE TRIGGER hh_mr_bd BEFORE DELETE ON medication_reconciliation
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_mrr_bi BEFORE INSERT ON medication_reconciliation_revision
FOR EACH ROW BEGIN
  IF ((NEW.reconciliation_id IS NOT NULL AND (NEW.reconciliation_id <= 0 OR NEW.reconciliation_id > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.prescription_id IS NOT NULL AND (NEW.prescription_id <= 0 OR NEW.prescription_id > 9007199254740991)))
    OR ((NEW.prescription_item_id IS NOT NULL AND (NEW.prescription_item_id <= 0 OR NEW.prescription_item_id > 9007199254740991)))
    OR ((NEW.source_json IS NOT NULL AND NOT JSON_VALID(NEW.source_json)))
    OR ((NEW.source_hash IS NOT NULL AND NEW.source_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.mapping_json IS NOT NULL AND NOT JSON_VALID(NEW.mapping_json)))
    OR ((NEW.mapping_hash IS NOT NULL AND NEW.mapping_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.revision_hash IS NOT NULL AND NEW.revision_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.transcribed_by IS NOT NULL AND (NEW.transcribed_by <= 0 OR NEW.transcribed_by > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.reconciliation_id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.prescription_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM electronic_prescription r WHERE r.id=NEW.prescription_id AND r.patient_id=NEW.patient_id AND r.version_no=NEW.source_version)))
    OR ((NEW.prescription_item_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM electronic_prescription_item r WHERE r.id=NEW.prescription_item_id AND r.prescription_id=NEW.prescription_id)))
    OR (NEW.revision_no <= 0)
    OR ((NEW.source_kind IS NULL OR NEW.source_kind NOT IN ('PRESCRIPTION','MANUAL')))
    OR ((NEW.action IS NULL OR NEW.action NOT IN ('CHANGE','STOP')))
    OR ((NEW.source_kind='PRESCRIPTION' AND (NEW.prescription_id IS NULL OR NEW.prescription_item_id IS NULL OR NEW.source_version IS NULL OR NEW.source_version<=0)))
    OR ((NEW.source_kind='MANUAL' AND (NEW.prescription_id IS NOT NULL OR NEW.prescription_item_id IS NOT NULL OR NEW.source_version IS NOT NULL)))
    OR ((NEW.schedule_zone IS NULL OR CHAR_LENGTH(TRIM(NEW.schedule_zone))=0))
    OR (NEW.effective_offset_seconds NOT BETWEEN -64800 AND 64800)
    OR (NOT (NEW.effective_local <=> DATE_ADD(NEW.effective_at, INTERVAL NEW.effective_offset_seconds SECOND)))
    OR (((NEW.end_at IS NULL) <> (NEW.end_local IS NULL)))
    OR (((NEW.end_at IS NULL) <> (NEW.end_offset_seconds IS NULL)))
    OR ((NEW.end_at IS NOT NULL AND (NEW.end_at<=NEW.effective_at OR NEW.end_offset_seconds NOT BETWEEN -64800 AND 64800 OR NOT (NEW.end_local <=> DATE_ADD(NEW.end_at, INTERVAL NEW.end_offset_seconds SECOND))))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_reconciliation_revision';
  END IF;
END$$

CREATE TRIGGER hh_mrr_bu BEFORE UPDATE ON medication_reconciliation_revision
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_mrr_bd BEFORE DELETE ON medication_reconciliation_revision
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_sw_bi BEFORE INSERT ON stock_work
FOR EACH ROW BEGIN
  IF ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND (NEW.medication_id <= 0 OR NEW.medication_id > 9007199254740991)))
    OR ((NEW.previous_work_id IS NOT NULL AND (NEW.previous_work_id <= 0 OR NEW.previous_work_id > 9007199254740991)))
    OR ((NEW.assigned_user_id IS NOT NULL AND (NEW.assigned_user_id <= 0 OR NEW.assigned_user_id > 9007199254740991)))
    OR ((NEW.opening_evidence_json IS NOT NULL AND NOT JSON_VALID(NEW.opening_evidence_json)))
    OR ((NEW.opening_evidence_hash IS NOT NULL AND NEW.opening_evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.closing_event_id IS NOT NULL AND (NEW.closing_event_id <= 0 OR NEW.closing_event_id > 9007199254740991)))
    OR ((NEW.created_by IS NOT NULL AND (NEW.created_by <= 0 OR NEW.created_by > 9007199254740991)))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.previous_work_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_work r WHERE r.id=NEW.previous_work_id AND r.stock_id=NEW.stock_id AND r.cycle=NEW.cycle-1 AND r.state<>'OPEN')))
    OR ((NEW.closing_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.closing_event_id AND r.stock_id=NEW.stock_id AND r.work_id=NEW.id AND r.cycle=NEW.cycle)))
    OR (NEW.cycle <= 0)
    OR (((NEW.cycle=1) <> (NEW.previous_work_id IS NULL)))
    OR (NEW.warning_quantity < 0)
    OR (NEW.warning_days < 0)
    OR ((NEW.daily_use_quantity IS NOT NULL AND NEW.daily_use_quantity<=0))
    OR ((NEW.opening_unit IS NULL OR CHAR_LENGTH(TRIM(NEW.opening_unit))=0))
    OR ((NEW.state='OPEN' AND (NEW.closed_at IS NOT NULL OR NEW.closing_event_id IS NOT NULL)))
    OR ((NEW.state<>'OPEN' AND (NEW.closed_at IS NULL OR NEW.closing_event_id IS NULL)))
    OR ((NEW.state IS NULL OR NEW.state NOT IN ('OPEN','RESOLVED','NOT_APPLICABLE','CANCELLED_MISTAKE'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_work';
  END IF;
END$$

CREATE TRIGGER hh_sw_bu BEFORE UPDATE ON stock_work
FOR EACH ROW BEGIN
  IF ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND (NEW.medication_id <= 0 OR NEW.medication_id > 9007199254740991)))
    OR ((NEW.previous_work_id IS NOT NULL AND (NEW.previous_work_id <= 0 OR NEW.previous_work_id > 9007199254740991)))
    OR ((NEW.assigned_user_id IS NOT NULL AND (NEW.assigned_user_id <= 0 OR NEW.assigned_user_id > 9007199254740991)))
    OR ((NEW.opening_evidence_json IS NOT NULL AND NOT JSON_VALID(NEW.opening_evidence_json)))
    OR ((NEW.opening_evidence_hash IS NOT NULL AND NEW.opening_evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.closing_event_id IS NOT NULL AND (NEW.closing_event_id <= 0 OR NEW.closing_event_id > 9007199254740991)))
    OR ((NEW.created_by IS NOT NULL AND (NEW.created_by <= 0 OR NEW.created_by > 9007199254740991)))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.previous_work_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_work r WHERE r.id=NEW.previous_work_id AND r.stock_id=NEW.stock_id AND r.cycle=NEW.cycle-1 AND r.state<>'OPEN')))
    OR ((NEW.closing_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.closing_event_id AND r.stock_id=NEW.stock_id AND r.work_id=NEW.id AND r.cycle=NEW.cycle)))
    OR (NEW.cycle <= 0)
    OR (((NEW.cycle=1) <> (NEW.previous_work_id IS NULL)))
    OR (NEW.warning_quantity < 0)
    OR (NEW.warning_days < 0)
    OR ((NEW.daily_use_quantity IS NOT NULL AND NEW.daily_use_quantity<=0))
    OR ((NEW.opening_unit IS NULL OR CHAR_LENGTH(TRIM(NEW.opening_unit))=0))
    OR ((NEW.state='OPEN' AND (NEW.closed_at IS NOT NULL OR NEW.closing_event_id IS NOT NULL)))
    OR ((NEW.state<>'OPEN' AND (NEW.closed_at IS NULL OR NEW.closing_event_id IS NULL)))
    OR ((NEW.state IS NULL OR NEW.state NOT IN ('OPEN','RESOLVED','NOT_APPLICABLE','CANCELLED_MISTAKE')))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.stock_id <=> BINARY OLD.stock_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.cycle <=> BINARY OLD.cycle) AND (BINARY NEW.previous_work_id <=> BINARY OLD.previous_work_id) AND (BINARY NEW.opening_quantity <=> BINARY OLD.opening_quantity) AND (BINARY NEW.opening_unit <=> BINARY OLD.opening_unit) AND (BINARY NEW.warning_quantity <=> BINARY OLD.warning_quantity) AND (BINARY NEW.warning_days <=> BINARY OLD.warning_days) AND (BINARY NEW.daily_use_quantity <=> BINARY OLD.daily_use_quantity) AND (BINARY NEW.opening_evidence_json <=> BINARY OLD.opening_evidence_json) AND (BINARY NEW.opening_evidence_hash <=> BINARY OLD.opening_evidence_hash) AND (BINARY NEW.opened_at <=> BINARY OLD.opened_at) AND (BINARY NEW.created_by <=> BINARY OLD.created_by)))
    OR ((OLD.state<>'OPEN' AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.stock_id <=> BINARY OLD.stock_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.cycle <=> BINARY OLD.cycle) AND (BINARY NEW.previous_work_id <=> BINARY OLD.previous_work_id) AND (BINARY NEW.state <=> BINARY OLD.state) AND (BINARY NEW.assigned_user_id <=> BINARY OLD.assigned_user_id) AND (BINARY NEW.opening_quantity <=> BINARY OLD.opening_quantity) AND (BINARY NEW.opening_unit <=> BINARY OLD.opening_unit) AND (BINARY NEW.warning_quantity <=> BINARY OLD.warning_quantity) AND (BINARY NEW.warning_days <=> BINARY OLD.warning_days) AND (BINARY NEW.daily_use_quantity <=> BINARY OLD.daily_use_quantity) AND (BINARY NEW.opening_evidence_json <=> BINARY OLD.opening_evidence_json) AND (BINARY NEW.opening_evidence_hash <=> BINARY OLD.opening_evidence_hash) AND (BINARY NEW.opened_at <=> BINARY OLD.opened_at) AND (BINARY NEW.closed_at <=> BINARY OLD.closed_at) AND (BINARY NEW.closing_event_id <=> BINARY OLD.closing_event_id) AND (BINARY NEW.created_by <=> BINARY OLD.created_by) AND (BINARY NEW.updated_at <=> BINARY OLD.updated_at)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_work';
  END IF;
END$$

CREATE TRIGGER hh_sw_bd BEFORE DELETE ON stock_work
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_sct_bi BEFORE INSERT ON stock_count
FOR EACH ROW BEGIN
  IF ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.work_id IS NOT NULL AND (NEW.work_id <= 0 OR NEW.work_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND (NEW.medication_id <= 0 OR NEW.medication_id > 9007199254740991)))
    OR (NEW.stock_version < 0)
    OR ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.prior_movements_json IS NOT NULL AND NOT JSON_VALID(NEW.prior_movements_json)))
    OR ((NEW.prior_actions_json IS NOT NULL AND NOT JSON_VALID(NEW.prior_actions_json)))
    OR ((NEW.reviewed_resolutions_json IS NOT NULL AND NOT JSON_VALID(NEW.reviewed_resolutions_json)))
    OR ((NEW.evidence_hash IS NOT NULL AND NEW.evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.work_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_work r WHERE r.id=NEW.work_id AND r.stock_id=NEW.stock_id AND r.cycle=NEW.cycle AND r.patient_id=NEW.patient_id)))
    OR (NEW.cycle<=0)
    OR (NEW.quantity<0)
    OR ((NEW.unit IS NULL OR CHAR_LENGTH(TRIM(NEW.unit))=0))
    OR ((NEW.reason IS NULL OR CHAR_LENGTH(TRIM(NEW.reason))=0))
    OR (NEW.counted_at>NEW.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_count';
  END IF;
END$$

CREATE TRIGGER hh_sct_bu BEFORE UPDATE ON stock_count
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_sct_bd BEFORE DELETE ON stock_count
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_sir_bi BEFORE INSERT ON stock_intake_resolution
FOR EACH ROW BEGIN
  IF ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND (NEW.medication_id <= 0 OR NEW.medication_id > 9007199254740991)))
    OR ((NEW.intake_id IS NOT NULL AND (NEW.intake_id <= 0 OR NEW.intake_id > 9007199254740991)))
    OR ((NEW.current_action_id IS NOT NULL AND (NEW.current_action_id <= 0 OR NEW.current_action_id > 9007199254740991)))
    OR ((NEW.execution_event_id IS NOT NULL AND (NEW.execution_event_id <= 0 OR NEW.execution_event_id > 9007199254740991)))
    OR ((NEW.stock_event_id IS NOT NULL AND (NEW.stock_event_id <= 0 OR NEW.stock_event_id > 9007199254740991)))
    OR ((NEW.count_id IS NOT NULL AND (NEW.count_id <= 0 OR NEW.count_id > 9007199254740991)))
    OR (NEW.lock_version < 0)
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.intake_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_intake r WHERE r.id=NEW.intake_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.current_action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.current_action_id AND r.intake_id=NEW.intake_id AND r.patient_id=NEW.patient_id AND r.execution_event_id=NEW.execution_event_id)))
    OR ((NEW.execution_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution_event r WHERE r.id=NEW.execution_event_id AND r.action_id=NEW.current_action_id AND r.intake_id=NEW.intake_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.stock_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.stock_event_id AND r.stock_id=NEW.stock_id AND r.intake_id=NEW.intake_id AND r.action_id=NEW.current_action_id AND r.execution_event_id=NEW.execution_event_id AND ((NEW.state='UNRECONCILED' AND r.settlement_status='UNRECONCILED') OR (NEW.state='COUNT_INCLUDED' AND r.settlement_status='COUNT_INCLUDED' AND r.count_id=NEW.count_id) OR (NEW.state='APPLIED' AND r.settlement_status IN ('APPLIED','NO_DELTA'))))))
    OR ((NEW.count_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_count r WHERE r.id=NEW.count_id AND r.stock_id=NEW.stock_id)))
    OR ((NEW.state='COUNT_INCLUDED' AND NEW.count_id IS NULL))
    OR ((NEW.state IS NULL OR NEW.state NOT IN ('UNRECONCILED','APPLIED','COUNT_INCLUDED'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_intake_resolution';
  END IF;
END$$

CREATE TRIGGER hh_sir_bu BEFORE UPDATE ON stock_intake_resolution
FOR EACH ROW BEGIN
  IF ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.medication_id IS NOT NULL AND (NEW.medication_id <= 0 OR NEW.medication_id > 9007199254740991)))
    OR ((NEW.intake_id IS NOT NULL AND (NEW.intake_id <= 0 OR NEW.intake_id > 9007199254740991)))
    OR ((NEW.current_action_id IS NOT NULL AND (NEW.current_action_id <= 0 OR NEW.current_action_id > 9007199254740991)))
    OR ((NEW.execution_event_id IS NOT NULL AND (NEW.execution_event_id <= 0 OR NEW.execution_event_id > 9007199254740991)))
    OR ((NEW.stock_event_id IS NOT NULL AND (NEW.stock_event_id <= 0 OR NEW.stock_event_id > 9007199254740991)))
    OR ((NEW.count_id IS NOT NULL AND (NEW.count_id <= 0 OR NEW.count_id > 9007199254740991)))
    OR (NEW.lock_version < 0)
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.intake_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_intake r WHERE r.id=NEW.intake_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.current_action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.current_action_id AND r.intake_id=NEW.intake_id AND r.patient_id=NEW.patient_id AND r.execution_event_id=NEW.execution_event_id)))
    OR ((NEW.execution_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution_event r WHERE r.id=NEW.execution_event_id AND r.action_id=NEW.current_action_id AND r.intake_id=NEW.intake_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.stock_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.stock_event_id AND r.stock_id=NEW.stock_id AND r.intake_id=NEW.intake_id AND r.action_id=NEW.current_action_id AND r.execution_event_id=NEW.execution_event_id AND ((NEW.state='UNRECONCILED' AND r.settlement_status='UNRECONCILED') OR (NEW.state='COUNT_INCLUDED' AND r.settlement_status='COUNT_INCLUDED' AND r.count_id=NEW.count_id) OR (NEW.state='APPLIED' AND r.settlement_status IN ('APPLIED','NO_DELTA'))))))
    OR ((NEW.count_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_count r WHERE r.id=NEW.count_id AND r.stock_id=NEW.stock_id)))
    OR ((NEW.state='COUNT_INCLUDED' AND NEW.count_id IS NULL))
    OR ((NEW.state IS NULL OR NEW.state NOT IN ('UNRECONCILED','APPLIED','COUNT_INCLUDED')))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.stock_id <=> BINARY OLD.stock_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.intake_id <=> BINARY OLD.intake_id) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR ((NEW.lock_version<OLD.lock_version OR NEW.lock_version>OLD.lock_version+1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_intake_resolution';
  END IF;
END$$

CREATE TRIGGER hh_sir_bd BEFORE DELETE ON stock_intake_resolution
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_fa_bi BEFORE INSERT ON family_assistance
FOR EACH ROW BEGIN
  IF ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.care_item_id IS NOT NULL AND (NEW.care_item_id <= 0 OR NEW.care_item_id > 9007199254740991)))
    OR ((NEW.care_appointment_id IS NOT NULL AND (NEW.care_appointment_id <= 0 OR NEW.care_appointment_id > 9007199254740991)))
    OR ((NEW.previous_assistance_id IS NOT NULL AND (NEW.previous_assistance_id <= 0 OR NEW.previous_assistance_id > 9007199254740991)))
    OR ((NEW.originator_id IS NOT NULL AND (NEW.originator_id <= 0 OR NEW.originator_id > 9007199254740991)))
    OR ((NEW.origin_evidence_json IS NOT NULL AND NOT JSON_VALID(NEW.origin_evidence_json)))
    OR ((NEW.origin_evidence_hash IS NOT NULL AND NEW.origin_evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR (NEW.lock_version < 0)
    OR ((NEW.assigned_user_id IS NOT NULL AND (NEW.assigned_user_id <= 0 OR NEW.assigned_user_id > 9007199254740991)))
    OR ((NEW.accepted_by IS NOT NULL AND (NEW.accepted_by <= 0 OR NEW.accepted_by > 9007199254740991)))
    OR ((NEW.handled_by IS NOT NULL AND (NEW.handled_by <= 0 OR NEW.handled_by > 9007199254740991)))
    OR ((NEW.source_fingerprint IS NOT NULL AND NEW.source_fingerprint NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.reconfirmed_fingerprint IS NOT NULL AND NEW.reconfirmed_fingerprint NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.accepted_fingerprint IS NOT NULL AND NEW.accepted_fingerprint NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.care_item_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_item r WHERE r.id=NEW.care_item_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.care_appointment_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_appointment r WHERE r.id=NEW.care_appointment_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.previous_assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.previous_assistance_id AND r.patient_id=NEW.patient_id AND r.source_kind=NEW.source_kind AND (r.care_item_id <=> NEW.care_item_id) AND (r.care_appointment_id <=> NEW.care_appointment_id) AND r.request_no=NEW.request_no-1 AND r.state IN ('COMPLETED','CANCELLED','CLOSED_UNRESOLVED'))))
    OR (NEW.request_no<=0)
    OR (NEW.cycle<=0)
    OR (NEW.source_changed NOT IN (0,1))
    OR ((NEW.source_kind IS NULL OR NEW.source_kind NOT IN ('CARE_ITEM','CARE_APPOINTMENT')))
    OR ((NEW.request_kind IS NULL OR NEW.request_kind NOT IN ('SYMPTOM','QUESTION','HANDOVER','ACCOMPANIMENT')))
    OR (NOT ((NEW.source_kind='CARE_ITEM' AND NEW.care_item_id IS NOT NULL AND NEW.care_appointment_id IS NULL) OR (NEW.source_kind='CARE_APPOINTMENT' AND NEW.care_item_id IS NULL AND NEW.care_appointment_id IS NOT NULL)))
    OR ((NEW.source_kind='CARE_APPOINTMENT' AND NEW.request_kind<>'ACCOMPANIMENT'))
    OR (((NEW.request_no=1) <> (NEW.previous_assistance_id IS NULL)))
    OR ((NEW.request_kind='ACCOMPANIMENT' AND NEW.source_fingerprint IS NULL))
    OR ((NEW.state IN ('ACCEPTED','AWAITING_CONFIRMATION','COMPLETED') AND (NEW.accepted_by IS NULL OR NEW.accepted_at IS NULL)))
    OR ((NEW.state IN ('AWAITING_CONFIRMATION','COMPLETED') AND (NEW.handled_by IS NULL OR NEW.handled_at IS NULL)))
    OR ((NEW.state='COMPLETED' AND NEW.confirmed_at IS NULL))
    OR ((NEW.state IN ('CANCELLED','CLOSED_UNRESOLVED') AND (NEW.closed_at IS NULL OR NEW.close_reason IS NULL OR CHAR_LENGTH(TRIM(NEW.close_reason))=0)))
    OR ((NEW.state IS NULL OR NEW.state NOT IN ('REQUESTED','ACCEPTED','AWAITING_CONFIRMATION','COMPLETED','CANCELLED','CLOSED_UNRESOLVED'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid family_assistance';
  END IF;
END$$

CREATE TRIGGER hh_fa_bu BEFORE UPDATE ON family_assistance
FOR EACH ROW BEGIN
  IF ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.care_item_id IS NOT NULL AND (NEW.care_item_id <= 0 OR NEW.care_item_id > 9007199254740991)))
    OR ((NEW.care_appointment_id IS NOT NULL AND (NEW.care_appointment_id <= 0 OR NEW.care_appointment_id > 9007199254740991)))
    OR ((NEW.previous_assistance_id IS NOT NULL AND (NEW.previous_assistance_id <= 0 OR NEW.previous_assistance_id > 9007199254740991)))
    OR ((NEW.originator_id IS NOT NULL AND (NEW.originator_id <= 0 OR NEW.originator_id > 9007199254740991)))
    OR ((NEW.origin_evidence_json IS NOT NULL AND NOT JSON_VALID(NEW.origin_evidence_json)))
    OR ((NEW.origin_evidence_hash IS NOT NULL AND NEW.origin_evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR (NEW.lock_version < 0)
    OR ((NEW.assigned_user_id IS NOT NULL AND (NEW.assigned_user_id <= 0 OR NEW.assigned_user_id > 9007199254740991)))
    OR ((NEW.accepted_by IS NOT NULL AND (NEW.accepted_by <= 0 OR NEW.accepted_by > 9007199254740991)))
    OR ((NEW.handled_by IS NOT NULL AND (NEW.handled_by <= 0 OR NEW.handled_by > 9007199254740991)))
    OR ((NEW.source_fingerprint IS NOT NULL AND NEW.source_fingerprint NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.reconfirmed_fingerprint IS NOT NULL AND NEW.reconfirmed_fingerprint NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.accepted_fingerprint IS NOT NULL AND NEW.accepted_fingerprint NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.care_item_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_item r WHERE r.id=NEW.care_item_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.care_appointment_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_appointment r WHERE r.id=NEW.care_appointment_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.previous_assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.previous_assistance_id AND r.patient_id=NEW.patient_id AND r.source_kind=NEW.source_kind AND (r.care_item_id <=> NEW.care_item_id) AND (r.care_appointment_id <=> NEW.care_appointment_id) AND r.request_no=NEW.request_no-1 AND r.state IN ('COMPLETED','CANCELLED','CLOSED_UNRESOLVED'))))
    OR (NEW.request_no<=0)
    OR (NEW.cycle<=0)
    OR (NEW.source_changed NOT IN (0,1))
    OR ((NEW.source_kind IS NULL OR NEW.source_kind NOT IN ('CARE_ITEM','CARE_APPOINTMENT')))
    OR ((NEW.request_kind IS NULL OR NEW.request_kind NOT IN ('SYMPTOM','QUESTION','HANDOVER','ACCOMPANIMENT')))
    OR (NOT ((NEW.source_kind='CARE_ITEM' AND NEW.care_item_id IS NOT NULL AND NEW.care_appointment_id IS NULL) OR (NEW.source_kind='CARE_APPOINTMENT' AND NEW.care_item_id IS NULL AND NEW.care_appointment_id IS NOT NULL)))
    OR ((NEW.source_kind='CARE_APPOINTMENT' AND NEW.request_kind<>'ACCOMPANIMENT'))
    OR (((NEW.request_no=1) <> (NEW.previous_assistance_id IS NULL)))
    OR ((NEW.request_kind='ACCOMPANIMENT' AND NEW.source_fingerprint IS NULL))
    OR ((NEW.state IN ('ACCEPTED','AWAITING_CONFIRMATION','COMPLETED') AND (NEW.accepted_by IS NULL OR NEW.accepted_at IS NULL)))
    OR ((NEW.state IN ('AWAITING_CONFIRMATION','COMPLETED') AND (NEW.handled_by IS NULL OR NEW.handled_at IS NULL)))
    OR ((NEW.state='COMPLETED' AND NEW.confirmed_at IS NULL))
    OR ((NEW.state IN ('CANCELLED','CLOSED_UNRESOLVED') AND (NEW.closed_at IS NULL OR NEW.close_reason IS NULL OR CHAR_LENGTH(TRIM(NEW.close_reason))=0)))
    OR ((NEW.state IS NULL OR NEW.state NOT IN ('REQUESTED','ACCEPTED','AWAITING_CONFIRMATION','COMPLETED','CANCELLED','CLOSED_UNRESOLVED')))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.source_kind <=> BINARY OLD.source_kind) AND (BINARY NEW.care_item_id <=> BINARY OLD.care_item_id) AND (BINARY NEW.care_appointment_id <=> BINARY OLD.care_appointment_id) AND (BINARY NEW.request_kind <=> BINARY OLD.request_kind) AND (BINARY NEW.request_no <=> BINARY OLD.request_no) AND (BINARY NEW.previous_assistance_id <=> BINARY OLD.previous_assistance_id) AND (BINARY NEW.originator_id <=> BINARY OLD.originator_id) AND (BINARY NEW.origin_evidence_json <=> BINARY OLD.origin_evidence_json) AND (BINARY NEW.origin_evidence_hash <=> BINARY OLD.origin_evidence_hash) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR ((NEW.lock_version<OLD.lock_version OR NEW.lock_version>OLD.lock_version+1))
    OR (NOT ((NEW.cycle=OLD.cycle AND (NEW.state=OLD.state OR (OLD.state='REQUESTED' AND NEW.state IN ('ACCEPTED','CANCELLED','CLOSED_UNRESOLVED')) OR (OLD.state='ACCEPTED' AND NEW.state IN ('REQUESTED','AWAITING_CONFIRMATION','CANCELLED','CLOSED_UNRESOLVED')) OR (OLD.state='AWAITING_CONFIRMATION' AND NEW.state IN ('REQUESTED','ACCEPTED','COMPLETED','CANCELLED','CLOSED_UNRESOLVED')))) OR (OLD.state IN ('COMPLETED','CANCELLED','CLOSED_UNRESOLVED') AND NEW.state='REQUESTED' AND NEW.cycle=OLD.cycle+1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid family_assistance';
  END IF;
END$$

CREATE TRIGGER hh_fa_bd BEFORE DELETE ON family_assistance
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_ear_bi BEFORE INSERT ON emergency_assistance_recipient
FOR EACH ROW BEGIN
  IF ((NEW.emergency_event_id IS NOT NULL AND (NEW.emergency_event_id <= 0 OR NEW.emergency_event_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.invitation_event_id IS NOT NULL AND (NEW.invitation_event_id <= 0 OR NEW.invitation_event_id > 9007199254740991)))
    OR ((NEW.invited_by IS NOT NULL AND (NEW.invited_by <= 0 OR NEW.invited_by > 9007199254740991)))
    OR ((NEW.emergency_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM emergency_event r WHERE r.id=NEW.emergency_event_id AND r.requester_user_id<>NEW.recipient_user_id AND r.workflow_version=1)))
    OR ((NEW.invitation_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM emergency_assistance_event r WHERE r.id=NEW.invitation_event_id AND r.emergency_event_id=NEW.emergency_event_id AND r.actor_kind='HUMAN' AND r.actor_id=NEW.invited_by AND r.type IN ('CREATE_REQUEST','HANDOFF')))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid emergency_assistance_recipient';
  END IF;
END$$

CREATE TRIGGER hh_ear_bu BEFORE UPDATE ON emergency_assistance_recipient
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_ear_bd BEFORE DELETE ON emergency_assistance_recipient
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_mee_bi BEFORE INSERT ON medication_execution_event
FOR EACH ROW BEGIN
  IF ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR (NEW.aggregate_version < 0)
    OR ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.evidence_json IS NOT NULL AND NOT JSON_VALID(NEW.evidence_json)))
    OR ((NEW.evidence_hash IS NOT NULL AND NEW.evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.reconciliation_id IS NOT NULL AND (NEW.reconciliation_id <= 0 OR NEW.reconciliation_id > 9007199254740991)))
    OR ((NEW.revision_id IS NOT NULL AND (NEW.revision_id <= 0 OR NEW.revision_id > 9007199254740991)))
    OR ((NEW.intake_id IS NOT NULL AND (NEW.intake_id <= 0 OR NEW.intake_id > 9007199254740991)))
    OR ((NEW.action_id IS NOT NULL AND (NEW.action_id <= 0 OR NEW.action_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.type IS NULL OR NEW.type NOT IN ('CREATE_RECONCILIATION','NEW_RECONCILIATION','REVISE_RECONCILIATION','VERIFY_RECONCILIATION','RETURN_RECONCILIATION','CANCEL_RECONCILIATION','SOURCE_CHANGED','SCHEDULE_REPLACEMENT','CANCEL_SCHEDULE','BOUNDARY_APPLIED','INTERVAL_ENDED','TIME_UNRESOLVED','REPLACEMENT_INPUT_CHANGED','REPLACEMENT_CHANGED','RECORD_TAKEN','RECORD_SKIPPED','SNOOZE','CORRECT_INTAKE','RECORD_CONTACT','END_INTAKE_UNRESOLVED','REOPEN_INTAKE_FOLLOW_UP','DUE','PERSONAL_FACT_CREATED')))
    OR ((NEW.actor_kind IS NULL OR NEW.actor_kind NOT IN ('HUMAN','SYSTEM')))
    OR ((NEW.actor_relation IS NULL OR NEW.actor_relation NOT IN ('SELF','ASSISTED','OWNER','DOCTOR','NURSE','FAMILY','REQUESTER','ORIGINATOR','HANDLER','WRITER','SYSTEM','DUE')))
    OR ((NEW.actor_kind='HUMAN' AND (NEW.actor_id IS NULL OR NEW.actor_relation IN ('SYSTEM','DUE') OR CHAR_LENGTH(TRIM(NEW.actor_relation))=0)))
    OR ((NEW.actor_kind='SYSTEM' AND (NEW.actor_id IS NOT NULL OR NEW.actor_relation NOT IN ('SYSTEM','DUE'))))
    OR (((NEW.type='DUE') <> (NEW.due_key IS NOT NULL)))
    OR (((NEW.type='DUE') <> (NEW.due_at IS NOT NULL)))
    OR ((NEW.actor_kind='SYSTEM' AND NEW.type<>'DUE' AND NEW.actor_relation<>'SYSTEM'))
    OR ((NEW.type='DUE' AND (NEW.actor_kind<>'SYSTEM' OR NEW.actor_relation<>'DUE' OR CHAR_LENGTH(NEW.due_key)=0)))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.actor_kind='SYSTEM' AND NEW.type NOT IN ('DUE','BOUNDARY_APPLIED','INTERVAL_ENDED','TIME_UNRESOLVED') ))
    OR ((NEW.reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.reconciliation_id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.revision_id AND r.execution_id=NEW.execution_id AND r.reconciliation_id=NEW.reconciliation_id)))
    OR ((NEW.intake_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_intake r WHERE r.id=NEW.intake_id AND r.patient_id=NEW.patient_id AND r.execution_id=NEW.execution_id)))
    OR ((NEW.action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.action_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id AND r.execution_id=NEW.execution_id AND (r.execution_event_id IS NULL OR r.execution_event_id=NEW.id))))
    OR ((NEW.type='DUE' AND NEW.intake_id IS NULL))
    OR ((NEW.type='DUE' AND NOT (BINARY NEW.due_key <=> BINARY CONCAT('MEDICATION_EXECUTION:',NEW.execution_id,':',NEW.intake_id,':',NEW.cycle,':',DATE_FORMAT(NEW.due_at,'%Y-%m-%dT%H:%i:%s.%fZ')))))
    OR ((NEW.type IN ('BOUNDARY_APPLIED','INTERVAL_ENDED') AND (NEW.actor_kind<>'SYSTEM' OR NEW.actor_id IS NOT NULL OR NEW.actor_relation<>'SYSTEM'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_execution_event';
  END IF;
END$$

CREATE TRIGGER hh_mee_bu BEFORE UPDATE ON medication_execution_event
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_mee_bd BEFORE DELETE ON medication_execution_event
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_mec_bi BEFORE INSERT ON medication_execution_command
FOR EACH ROW BEGIN
  IF ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR (NEW.expected_version < 0)
    OR ((NEW.payload_hash IS NOT NULL AND NEW.payload_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.result_json IS NOT NULL AND NOT JSON_VALID(NEW.result_json)))
    OR (NEW.command_key NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR (NEW.result_json IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_execution_command';
  END IF;
END$$

CREATE TRIGGER hh_mec_bu BEFORE UPDATE ON medication_execution_command
FOR EACH ROW BEGIN
  IF ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR (NEW.expected_version < 0)
    OR ((NEW.payload_hash IS NOT NULL AND NEW.payload_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.result_json IS NOT NULL AND NOT JSON_VALID(NEW.result_json)))
    OR (NEW.command_key NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.actor_id <=> BINARY OLD.actor_id) AND (BINARY NEW.command_key <=> BINARY OLD.command_key) AND (BINARY NEW.expected_version <=> BINARY OLD.expected_version) AND (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR (OLD.result_json IS NOT NULL)
    OR (NEW.result_json IS NULL)
    OR (NEW.execution_id IS NULL)
    OR ((OLD.execution_id IS NOT NULL AND NOT (NEW.execution_id <=> OLD.execution_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_execution_command';
  END IF;
END$$

CREATE TRIGGER hh_mec_bd BEFORE DELETE ON medication_execution_command
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_men_bi BEFORE INSERT ON medication_execution_notification
FOR EACH ROW BEGIN
  IF ((NEW.event_id IS NOT NULL AND (NEW.event_id <= 0 OR NEW.event_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.channel_id IS NOT NULL AND (NEW.channel_id <= 0 OR NEW.channel_id > 9007199254740991)))
    OR ((NEW.revision_id IS NOT NULL AND (NEW.revision_id <= 0 OR NEW.revision_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.recipient_kind IS NULL OR NEW.recipient_kind NOT IN ('USER','NONE')))
    OR ((NEW.status IS NULL OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED')))
    OR (NEW.attempt_count NOT BETWEEN 0 AND 3)
    OR ((NEW.dispatch_key IS NULL OR CHAR_LENGTH(TRIM(NEW.dispatch_key))=0))
    OR ((NEW.template_key IS NULL OR CHAR_LENGTH(TRIM(NEW.template_key))=0))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution_event r WHERE r.id=NEW.event_id AND r.patient_id=NEW.patient_id AND r.execution_id=NEW.execution_id AND r.cycle=NEW.cycle)))
    OR ((NEW.recipient_kind='NONE' AND (NEW.recipient_user_id IS NOT NULL OR NEW.channel_id IS NOT NULL OR NEW.status<>'NO_CHANNEL' OR NEW.last_result<>'NO_RECIPIENT' OR NEW.last_result IS NULL OR NEW.attempt_count<>0 OR NEW.claim_token IS NOT NULL OR NEW.request_id IS NOT NULL OR NEW.next_attempt_at IS NOT NULL)))
    OR ((NEW.recipient_kind='USER' AND NEW.recipient_user_id IS NULL))
    OR ((NEW.status='CLAIMED' AND (NEW.claim_token IS NULL OR NEW.claimed_at IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status<>'CLAIMED' AND NEW.claim_token IS NOT NULL))
    OR ((NEW.status='DELIVERED' AND NEW.delivered_at IS NULL))
    OR ((NEW.status<>'DELIVERED' AND NEW.delivered_at IS NOT NULL))
    OR ((NEW.status IN ('QUEUED','CLAIMED') AND (NEW.recipient_user_id IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status='UNKNOWN' AND NEW.next_attempt_at IS NOT NULL))
    OR ((NEW.last_result IS NOT NULL AND NEW.last_result NOT IN ('ATTEMPT_STARTED','FAILED_RETRYABLE','FAILED_MANUAL_RETRY_REQUIRED','CLAIMED_UNATTEMPTED','MANUAL_RETRY','QUEUED','DELIVERED','NO_CHANNEL','NO_RECIPIENT','FAILED','UNKNOWN','SUPPRESSED')))
    OR ((NEW.revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.revision_id AND r.execution_id=NEW.execution_id)))
    OR ((NEW.request_id IS NOT NULL AND NEW.request_id NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|-?[1-9][0-9]*)$'))
    OR ((NEW.last_result='ATTEMPT_STARTED' AND (NEW.status<>'CLAIMED' OR NEW.request_id IS NULL OR NEW.attempt_count<1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_execution_notification';
  END IF;
END$$

CREATE TRIGGER hh_men_bu BEFORE UPDATE ON medication_execution_notification
FOR EACH ROW BEGIN
  IF ((NEW.event_id IS NOT NULL AND (NEW.event_id <= 0 OR NEW.event_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.channel_id IS NOT NULL AND (NEW.channel_id <= 0 OR NEW.channel_id > 9007199254740991)))
    OR ((NEW.revision_id IS NOT NULL AND (NEW.revision_id <= 0 OR NEW.revision_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.recipient_kind IS NULL OR NEW.recipient_kind NOT IN ('USER','NONE')))
    OR ((NEW.status IS NULL OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED')))
    OR (NEW.attempt_count NOT BETWEEN 0 AND 3)
    OR ((NEW.dispatch_key IS NULL OR CHAR_LENGTH(TRIM(NEW.dispatch_key))=0))
    OR ((NEW.template_key IS NULL OR CHAR_LENGTH(TRIM(NEW.template_key))=0))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution_event r WHERE r.id=NEW.event_id AND r.patient_id=NEW.patient_id AND r.execution_id=NEW.execution_id AND r.cycle=NEW.cycle)))
    OR ((NEW.recipient_kind='NONE' AND (NEW.recipient_user_id IS NOT NULL OR NEW.channel_id IS NOT NULL OR NEW.status<>'NO_CHANNEL' OR NEW.last_result<>'NO_RECIPIENT' OR NEW.last_result IS NULL OR NEW.attempt_count<>0 OR NEW.claim_token IS NOT NULL OR NEW.request_id IS NOT NULL OR NEW.next_attempt_at IS NOT NULL)))
    OR ((NEW.recipient_kind='USER' AND NEW.recipient_user_id IS NULL))
    OR ((NEW.status='CLAIMED' AND (NEW.claim_token IS NULL OR NEW.claimed_at IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status<>'CLAIMED' AND NEW.claim_token IS NOT NULL))
    OR ((NEW.status='DELIVERED' AND NEW.delivered_at IS NULL))
    OR ((NEW.status<>'DELIVERED' AND NEW.delivered_at IS NOT NULL))
    OR ((NEW.status IN ('QUEUED','CLAIMED') AND (NEW.recipient_user_id IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status='UNKNOWN' AND NEW.next_attempt_at IS NOT NULL))
    OR ((NEW.last_result IS NOT NULL AND NEW.last_result NOT IN ('ATTEMPT_STARTED','FAILED_RETRYABLE','FAILED_MANUAL_RETRY_REQUIRED','CLAIMED_UNATTEMPTED','MANUAL_RETRY','QUEUED','DELIVERED','NO_CHANNEL','NO_RECIPIENT','FAILED','UNKNOWN','SUPPRESSED')))
    OR ((NEW.revision_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.id=NEW.revision_id AND r.execution_id=NEW.execution_id)))
    OR ((NEW.request_id IS NOT NULL AND NEW.request_id NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|-?[1-9][0-9]*)$'))
    OR ((NEW.last_result='ATTEMPT_STARTED' AND (NEW.status<>'CLAIMED' OR NEW.request_id IS NULL OR NEW.attempt_count<1)))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.event_id <=> BINARY OLD.event_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.execution_id <=> BINARY OLD.execution_id) AND (BINARY NEW.cycle <=> BINARY OLD.cycle) AND (BINARY NEW.recipient_kind <=> BINARY OLD.recipient_kind) AND (BINARY NEW.recipient_user_id <=> BINARY OLD.recipient_user_id) AND (BINARY NEW.channel_id <=> BINARY OLD.channel_id) AND (BINARY NEW.template_key <=> BINARY OLD.template_key) AND (BINARY NEW.dispatch_key <=> BINARY OLD.dispatch_key) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.revision_id <=> BINARY OLD.revision_id)))
    OR (OLD.recipient_kind='NONE')
    OR ((NEW.attempt_count<OLD.attempt_count AND NOT (NEW.status='QUEUED' AND NEW.attempt_count=0 AND NOT (NEW.request_id <=> OLD.request_id) AND NEW.last_result='MANUAL_RETRY' AND OLD.status IN ('FAILED','UNKNOWN','SUPPRESSED','NO_CHANNEL'))))
    OR ((NEW.attempt_count>OLD.attempt_count+1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_execution_notification';
  END IF;
END$$

CREATE TRIGGER hh_men_bd BEFORE DELETE ON medication_execution_notification
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_se_bi BEFORE INSERT ON stock_event
FOR EACH ROW BEGIN
  IF ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR (NEW.aggregate_version < 0)
    OR ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.evidence_json IS NOT NULL AND NOT JSON_VALID(NEW.evidence_json)))
    OR ((NEW.evidence_hash IS NOT NULL AND NEW.evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.work_id IS NOT NULL AND (NEW.work_id <= 0 OR NEW.work_id > 9007199254740991)))
    OR ((NEW.intake_id IS NOT NULL AND (NEW.intake_id <= 0 OR NEW.intake_id > 9007199254740991)))
    OR ((NEW.action_id IS NOT NULL AND (NEW.action_id <= 0 OR NEW.action_id > 9007199254740991)))
    OR ((NEW.execution_event_id IS NOT NULL AND (NEW.execution_event_id <= 0 OR NEW.execution_event_id > 9007199254740991)))
    OR ((NEW.original_movement_id IS NOT NULL AND (NEW.original_movement_id <= 0 OR NEW.original_movement_id > 9007199254740991)))
    OR ((NEW.compensation_movement_id IS NOT NULL AND (NEW.compensation_movement_id <= 0 OR NEW.compensation_movement_id > 9007199254740991)))
    OR ((NEW.count_id IS NOT NULL AND (NEW.count_id <= 0 OR NEW.count_id > 9007199254740991)))
    OR ((NEW.prior_stock_event_id IS NOT NULL AND (NEW.prior_stock_event_id <= 0 OR NEW.prior_stock_event_id > 9007199254740991)))
    OR ((NEW.movement_id IS NOT NULL AND (NEW.movement_id <= 0 OR NEW.movement_id > 9007199254740991)))
    OR ((NEW.settled_action_id IS NOT NULL AND (NEW.settled_action_id <= 0 OR NEW.settled_action_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.type IS NULL OR NEW.type NOT IN ('ADOPT_STOCK','RECORD_REPLENISHMENT','RECORD_COUNT','RESOLVE_INTAKE','RECONCILE','CHANGE_RULE','CANCEL_MISTAKE','CONSUMPTION','CORRECTION','CYCLE_OPENED','DUE')))
    OR ((NEW.actor_kind IS NULL OR NEW.actor_kind NOT IN ('HUMAN','SYSTEM')))
    OR ((NEW.actor_relation IS NULL OR NEW.actor_relation NOT IN ('SELF','ASSISTED','OWNER','DOCTOR','NURSE','FAMILY','REQUESTER','ORIGINATOR','HANDLER','WRITER','SYSTEM','DUE')))
    OR ((NEW.actor_kind='HUMAN' AND (NEW.actor_id IS NULL OR NEW.actor_relation IN ('SYSTEM','DUE') OR CHAR_LENGTH(TRIM(NEW.actor_relation))=0)))
    OR ((NEW.actor_kind='SYSTEM' AND (NEW.actor_id IS NOT NULL OR NEW.actor_relation NOT IN ('SYSTEM','DUE'))))
    OR (((NEW.type='DUE') <> (NEW.due_key IS NOT NULL)))
    OR (((NEW.type='DUE') <> (NEW.due_at IS NOT NULL)))
    OR ((NEW.actor_kind='SYSTEM' AND NEW.type<>'DUE' AND NEW.actor_relation<>'SYSTEM'))
    OR ((NEW.type='DUE' AND (NEW.actor_kind<>'SYSTEM' OR NEW.actor_relation<>'DUE' OR CHAR_LENGTH(NEW.due_key)=0)))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.actor_kind='SYSTEM' AND NEW.type NOT IN ('DUE','CYCLE_OPENED') ))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id AND r.workflow_version=1)))
    OR ((NEW.work_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_work r WHERE r.id=NEW.work_id AND r.stock_id=NEW.stock_id AND r.cycle=NEW.cycle)))
    OR ((NEW.intake_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_intake r WHERE r.id=NEW.intake_id AND r.patient_id=NEW.patient_id AND r.medication_id=(SELECT s.medication_id FROM medication_stock s WHERE s.id=NEW.stock_id))))
    OR ((NEW.action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.action_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id)))
    OR ((NEW.execution_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution_event r WHERE r.id=NEW.execution_event_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id AND r.action_id=NEW.action_id)))
    OR ((NEW.original_movement_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock_movement r WHERE r.id=NEW.original_movement_id AND r.patient_id=NEW.patient_id AND r.medication_id=(SELECT s.medication_id FROM medication_stock s WHERE s.id=NEW.stock_id) AND BINARY r.source_key=BINARY CONCAT('INTAKE:',NEW.intake_id))))
    OR ((NEW.compensation_movement_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock_movement r WHERE r.id=NEW.compensation_movement_id AND r.stock_id=NEW.stock_id AND r.action_id=NEW.action_id AND r.movement_kind='CORRECTION')))
    OR ((NEW.movement_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock_movement r WHERE r.id=NEW.movement_id AND r.stock_id=NEW.stock_id AND (r.stock_event_id IS NULL OR r.stock_event_id=NEW.id))))
    OR ((NEW.count_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_count r WHERE r.id=NEW.count_id AND r.stock_id=NEW.stock_id)))
    OR ((NEW.prior_stock_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.prior_stock_event_id AND r.stock_id=NEW.stock_id AND r.intake_id=NEW.intake_id)))
    OR ((NEW.settlement_status IS NOT NULL AND NEW.settlement_status NOT IN ('UNRECONCILED','APPLIED','COUNT_INCLUDED','NO_DELTA')))
    OR ((NEW.settlement_status IS NOT NULL AND (NEW.action_id IS NULL OR NEW.intake_id IS NULL)))
    OR ((NEW.settlement_status='UNRECONCILED' AND (NEW.settled_action_id IS NOT NULL OR NEW.compensation_movement_id IS NOT NULL)))
    OR ((NEW.settlement_status IN ('APPLIED','COUNT_INCLUDED','NO_DELTA') AND (NEW.settled_action_id IS NULL OR NEW.settled_action_id<>NEW.action_id OR NEW.execution_event_id IS NULL)))
    OR ((NEW.settlement_status IS NULL AND NEW.settled_action_id IS NOT NULL))
    OR ((NEW.settlement_status='COUNT_INCLUDED' AND NEW.count_id IS NULL))
    OR ((NEW.settlement_status IN ('COUNT_INCLUDED','NO_DELTA') AND NEW.compensation_movement_id IS NOT NULL))
    OR ((NEW.type='DUE' AND NEW.work_id IS NULL))
    OR ((NEW.type='DUE' AND NOT (BINARY NEW.due_key <=> BINARY CONCAT('STOCK:',NEW.stock_id,':',NEW.work_id,':',NEW.cycle,':',DATE_FORMAT(NEW.due_at,'%Y-%m-%dT%H:%i:%s.%fZ'))))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_event';
  END IF;
END$$

CREATE TRIGGER hh_se_bu BEFORE UPDATE ON stock_event
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_se_bd BEFORE DELETE ON stock_event
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_sc_bi BEFORE INSERT ON stock_command
FOR EACH ROW BEGIN
  IF ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR (NEW.expected_version < 0)
    OR ((NEW.payload_hash IS NOT NULL AND NEW.payload_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.result_json IS NOT NULL AND NOT JSON_VALID(NEW.result_json)))
    OR (NEW.command_key NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR (NEW.result_json IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_command';
  END IF;
END$$

CREATE TRIGGER hh_sc_bu BEFORE UPDATE ON stock_command
FOR EACH ROW BEGIN
  IF ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR (NEW.expected_version < 0)
    OR ((NEW.payload_hash IS NOT NULL AND NEW.payload_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.result_json IS NOT NULL AND NOT JSON_VALID(NEW.result_json)))
    OR (NEW.command_key NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.actor_id <=> BINARY OLD.actor_id) AND (BINARY NEW.command_key <=> BINARY OLD.command_key) AND (BINARY NEW.expected_version <=> BINARY OLD.expected_version) AND (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR (OLD.result_json IS NOT NULL)
    OR (NEW.result_json IS NULL)
    OR (NEW.stock_id IS NULL)
    OR ((OLD.stock_id IS NOT NULL AND NOT (NEW.stock_id <=> OLD.stock_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_command';
  END IF;
END$$

CREATE TRIGGER hh_sc_bd BEFORE DELETE ON stock_command
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_sn_bi BEFORE INSERT ON stock_notification
FOR EACH ROW BEGIN
  IF ((NEW.event_id IS NOT NULL AND (NEW.event_id <= 0 OR NEW.event_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.channel_id IS NOT NULL AND (NEW.channel_id <= 0 OR NEW.channel_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.recipient_kind IS NULL OR NEW.recipient_kind NOT IN ('USER','NONE')))
    OR ((NEW.status IS NULL OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED')))
    OR (NEW.attempt_count NOT BETWEEN 0 AND 3)
    OR ((NEW.dispatch_key IS NULL OR CHAR_LENGTH(TRIM(NEW.dispatch_key))=0))
    OR ((NEW.template_key IS NULL OR CHAR_LENGTH(TRIM(NEW.template_key))=0))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.event_id AND r.patient_id=NEW.patient_id AND r.stock_id=NEW.stock_id AND r.cycle=NEW.cycle)))
    OR ((NEW.recipient_kind='NONE' AND (NEW.recipient_user_id IS NOT NULL OR NEW.channel_id IS NOT NULL OR NEW.status<>'NO_CHANNEL' OR NEW.last_result<>'NO_RECIPIENT' OR NEW.last_result IS NULL OR NEW.attempt_count<>0 OR NEW.claim_token IS NOT NULL OR NEW.request_id IS NOT NULL OR NEW.next_attempt_at IS NOT NULL)))
    OR ((NEW.recipient_kind='USER' AND NEW.recipient_user_id IS NULL))
    OR ((NEW.status='CLAIMED' AND (NEW.claim_token IS NULL OR NEW.claimed_at IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status<>'CLAIMED' AND NEW.claim_token IS NOT NULL))
    OR ((NEW.status='DELIVERED' AND NEW.delivered_at IS NULL))
    OR ((NEW.status<>'DELIVERED' AND NEW.delivered_at IS NOT NULL))
    OR ((NEW.status IN ('QUEUED','CLAIMED') AND (NEW.recipient_user_id IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status='UNKNOWN' AND NEW.next_attempt_at IS NOT NULL))
    OR ((NEW.last_result IS NOT NULL AND NEW.last_result NOT IN ('ATTEMPT_STARTED','FAILED_RETRYABLE','FAILED_MANUAL_RETRY_REQUIRED','CLAIMED_UNATTEMPTED','MANUAL_RETRY','QUEUED','DELIVERED','NO_CHANNEL','NO_RECIPIENT','FAILED','UNKNOWN','SUPPRESSED')))
    OR ((NEW.request_id IS NOT NULL AND NEW.request_id NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|-?[1-9][0-9]*)$'))
    OR ((NEW.last_result='ATTEMPT_STARTED' AND (NEW.status<>'CLAIMED' OR NEW.request_id IS NULL OR NEW.attempt_count<1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_notification';
  END IF;
END$$

CREATE TRIGGER hh_sn_bu BEFORE UPDATE ON stock_notification
FOR EACH ROW BEGIN
  IF ((NEW.event_id IS NOT NULL AND (NEW.event_id <= 0 OR NEW.event_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.channel_id IS NOT NULL AND (NEW.channel_id <= 0 OR NEW.channel_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.recipient_kind IS NULL OR NEW.recipient_kind NOT IN ('USER','NONE')))
    OR ((NEW.status IS NULL OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED')))
    OR (NEW.attempt_count NOT BETWEEN 0 AND 3)
    OR ((NEW.dispatch_key IS NULL OR CHAR_LENGTH(TRIM(NEW.dispatch_key))=0))
    OR ((NEW.template_key IS NULL OR CHAR_LENGTH(TRIM(NEW.template_key))=0))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.event_id AND r.patient_id=NEW.patient_id AND r.stock_id=NEW.stock_id AND r.cycle=NEW.cycle)))
    OR ((NEW.recipient_kind='NONE' AND (NEW.recipient_user_id IS NOT NULL OR NEW.channel_id IS NOT NULL OR NEW.status<>'NO_CHANNEL' OR NEW.last_result<>'NO_RECIPIENT' OR NEW.last_result IS NULL OR NEW.attempt_count<>0 OR NEW.claim_token IS NOT NULL OR NEW.request_id IS NOT NULL OR NEW.next_attempt_at IS NOT NULL)))
    OR ((NEW.recipient_kind='USER' AND NEW.recipient_user_id IS NULL))
    OR ((NEW.status='CLAIMED' AND (NEW.claim_token IS NULL OR NEW.claimed_at IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status<>'CLAIMED' AND NEW.claim_token IS NOT NULL))
    OR ((NEW.status='DELIVERED' AND NEW.delivered_at IS NULL))
    OR ((NEW.status<>'DELIVERED' AND NEW.delivered_at IS NOT NULL))
    OR ((NEW.status IN ('QUEUED','CLAIMED') AND (NEW.recipient_user_id IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status='UNKNOWN' AND NEW.next_attempt_at IS NOT NULL))
    OR ((NEW.last_result IS NOT NULL AND NEW.last_result NOT IN ('ATTEMPT_STARTED','FAILED_RETRYABLE','FAILED_MANUAL_RETRY_REQUIRED','CLAIMED_UNATTEMPTED','MANUAL_RETRY','QUEUED','DELIVERED','NO_CHANNEL','NO_RECIPIENT','FAILED','UNKNOWN','SUPPRESSED')))
    OR ((NEW.request_id IS NOT NULL AND NEW.request_id NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|-?[1-9][0-9]*)$'))
    OR ((NEW.last_result='ATTEMPT_STARTED' AND (NEW.status<>'CLAIMED' OR NEW.request_id IS NULL OR NEW.attempt_count<1)))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.event_id <=> BINARY OLD.event_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.stock_id <=> BINARY OLD.stock_id) AND (BINARY NEW.cycle <=> BINARY OLD.cycle) AND (BINARY NEW.recipient_kind <=> BINARY OLD.recipient_kind) AND (BINARY NEW.recipient_user_id <=> BINARY OLD.recipient_user_id) AND (BINARY NEW.channel_id <=> BINARY OLD.channel_id) AND (BINARY NEW.template_key <=> BINARY OLD.template_key) AND (BINARY NEW.dispatch_key <=> BINARY OLD.dispatch_key) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR (OLD.recipient_kind='NONE')
    OR ((NEW.attempt_count<OLD.attempt_count AND NOT (NEW.status='QUEUED' AND NEW.attempt_count=0 AND NOT (NEW.request_id <=> OLD.request_id) AND NEW.last_result='MANUAL_RETRY' AND OLD.status IN ('FAILED','UNKNOWN','SUPPRESSED','NO_CHANNEL'))))
    OR ((NEW.attempt_count>OLD.attempt_count+1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid stock_notification';
  END IF;
END$$

CREATE TRIGGER hh_sn_bd BEFORE DELETE ON stock_notification
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_fae_bi BEFORE INSERT ON family_assistance_event
FOR EACH ROW BEGIN
  IF ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.assistance_id IS NOT NULL AND (NEW.assistance_id <= 0 OR NEW.assistance_id > 9007199254740991)))
    OR (NEW.aggregate_version < 0)
    OR ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.evidence_json IS NOT NULL AND NOT JSON_VALID(NEW.evidence_json)))
    OR ((NEW.evidence_hash IS NOT NULL AND NEW.evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR (NEW.cycle<=0)
    OR ((NEW.type IS NULL OR NEW.type NOT IN ('CREATE_REQUEST','ASSIGN','ACCEPT','REJECT','HANDLE','CONFIRM','RETURN','HANDOFF','CANCEL','CLOSE_UNRESOLVED','REOPEN','RECONFIRM_APPOINTMENT','SOURCE_CHANGED','DUE')))
    OR ((NEW.actor_kind IS NULL OR NEW.actor_kind NOT IN ('HUMAN','SYSTEM')))
    OR ((NEW.actor_relation IS NULL OR NEW.actor_relation NOT IN ('SELF','ASSISTED','OWNER','DOCTOR','NURSE','FAMILY','REQUESTER','ORIGINATOR','HANDLER','WRITER','SYSTEM','DUE')))
    OR ((NEW.actor_kind='HUMAN' AND (NEW.actor_id IS NULL OR NEW.actor_relation IN ('SYSTEM','DUE') OR CHAR_LENGTH(TRIM(NEW.actor_relation))=0)))
    OR ((NEW.actor_kind='SYSTEM' AND (NEW.actor_id IS NOT NULL OR NEW.actor_relation NOT IN ('SYSTEM','DUE'))))
    OR (((NEW.type='DUE') <> (NEW.due_key IS NOT NULL)))
    OR (((NEW.type='DUE') <> (NEW.due_at IS NOT NULL)))
    OR ((NEW.actor_kind='SYSTEM' AND NEW.type<>'DUE' AND NEW.actor_relation<>'SYSTEM'))
    OR ((NEW.type='DUE' AND (NEW.actor_kind<>'SYSTEM' OR NEW.actor_relation<>'DUE' OR CHAR_LENGTH(NEW.due_key)=0)))
    OR ((NEW.assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.assistance_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.actor_kind='SYSTEM' AND NEW.type NOT IN ('DUE') ))
    OR ((NEW.type='DUE' AND NOT (BINARY NEW.due_key <=> BINARY CONCAT('FAMILY_ASSISTANCE:',NEW.assistance_id,':',NEW.cycle,':',DATE_FORMAT(NEW.due_at,'%Y-%m-%dT%H:%i:%s.%fZ'))))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid family_assistance_event';
  END IF;
END$$

CREATE TRIGGER hh_fae_bu BEFORE UPDATE ON family_assistance_event
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_fae_bd BEFORE DELETE ON family_assistance_event
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_fac_bi BEFORE INSERT ON family_assistance_command
FOR EACH ROW BEGIN
  IF ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.assistance_id IS NOT NULL AND (NEW.assistance_id <= 0 OR NEW.assistance_id > 9007199254740991)))
    OR (NEW.expected_version < 0)
    OR ((NEW.payload_hash IS NOT NULL AND NEW.payload_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.result_json IS NOT NULL AND NOT JSON_VALID(NEW.result_json)))
    OR (NEW.command_key NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR (NEW.result_json IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid family_assistance_command';
  END IF;
END$$

CREATE TRIGGER hh_fac_bu BEFORE UPDATE ON family_assistance_command
FOR EACH ROW BEGIN
  IF ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.assistance_id IS NOT NULL AND (NEW.assistance_id <= 0 OR NEW.assistance_id > 9007199254740991)))
    OR (NEW.expected_version < 0)
    OR ((NEW.payload_hash IS NOT NULL AND NEW.payload_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.result_json IS NOT NULL AND NOT JSON_VALID(NEW.result_json)))
    OR (NEW.command_key NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.actor_id <=> BINARY OLD.actor_id) AND (BINARY NEW.command_key <=> BINARY OLD.command_key) AND (BINARY NEW.expected_version <=> BINARY OLD.expected_version) AND (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR (OLD.result_json IS NOT NULL)
    OR (NEW.result_json IS NULL)
    OR (NEW.assistance_id IS NULL)
    OR ((OLD.assistance_id IS NOT NULL AND NOT (NEW.assistance_id <=> OLD.assistance_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid family_assistance_command';
  END IF;
END$$

CREATE TRIGGER hh_fac_bd BEFORE DELETE ON family_assistance_command
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_fan_bi BEFORE INSERT ON family_assistance_notification
FOR EACH ROW BEGIN
  IF ((NEW.event_id IS NOT NULL AND (NEW.event_id <= 0 OR NEW.event_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.assistance_id IS NOT NULL AND (NEW.assistance_id <= 0 OR NEW.assistance_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.channel_id IS NOT NULL AND (NEW.channel_id <= 0 OR NEW.channel_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.recipient_kind IS NULL OR NEW.recipient_kind NOT IN ('USER','NONE')))
    OR ((NEW.status IS NULL OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED')))
    OR (NEW.attempt_count NOT BETWEEN 0 AND 3)
    OR ((NEW.dispatch_key IS NULL OR CHAR_LENGTH(TRIM(NEW.dispatch_key))=0))
    OR ((NEW.template_key IS NULL OR CHAR_LENGTH(TRIM(NEW.template_key))=0))
    OR ((NEW.assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.assistance_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance_event r WHERE r.id=NEW.event_id AND r.patient_id=NEW.patient_id AND r.assistance_id=NEW.assistance_id AND r.cycle=NEW.cycle)))
    OR ((NEW.recipient_kind='NONE' AND (NEW.recipient_user_id IS NOT NULL OR NEW.channel_id IS NOT NULL OR NEW.status<>'NO_CHANNEL' OR NEW.last_result<>'NO_RECIPIENT' OR NEW.last_result IS NULL OR NEW.attempt_count<>0 OR NEW.claim_token IS NOT NULL OR NEW.request_id IS NOT NULL OR NEW.next_attempt_at IS NOT NULL)))
    OR ((NEW.recipient_kind='USER' AND NEW.recipient_user_id IS NULL))
    OR ((NEW.status='CLAIMED' AND (NEW.claim_token IS NULL OR NEW.claimed_at IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status<>'CLAIMED' AND NEW.claim_token IS NOT NULL))
    OR ((NEW.status='DELIVERED' AND NEW.delivered_at IS NULL))
    OR ((NEW.status<>'DELIVERED' AND NEW.delivered_at IS NOT NULL))
    OR ((NEW.status IN ('QUEUED','CLAIMED') AND (NEW.recipient_user_id IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status='UNKNOWN' AND NEW.next_attempt_at IS NOT NULL))
    OR ((NEW.last_result IS NOT NULL AND NEW.last_result NOT IN ('ATTEMPT_STARTED','FAILED_RETRYABLE','FAILED_MANUAL_RETRY_REQUIRED','CLAIMED_UNATTEMPTED','MANUAL_RETRY','QUEUED','DELIVERED','NO_CHANNEL','NO_RECIPIENT','FAILED','UNKNOWN','SUPPRESSED')))
    OR ((NEW.request_id IS NOT NULL AND NEW.request_id NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|-?[1-9][0-9]*)$'))
    OR ((NEW.last_result='ATTEMPT_STARTED' AND (NEW.status<>'CLAIMED' OR NEW.request_id IS NULL OR NEW.attempt_count<1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid family_assistance_notification';
  END IF;
END$$

CREATE TRIGGER hh_fan_bu BEFORE UPDATE ON family_assistance_notification
FOR EACH ROW BEGIN
  IF ((NEW.event_id IS NOT NULL AND (NEW.event_id <= 0 OR NEW.event_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.assistance_id IS NOT NULL AND (NEW.assistance_id <= 0 OR NEW.assistance_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.channel_id IS NOT NULL AND (NEW.channel_id <= 0 OR NEW.channel_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.recipient_kind IS NULL OR NEW.recipient_kind NOT IN ('USER','NONE')))
    OR ((NEW.status IS NULL OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED')))
    OR (NEW.attempt_count NOT BETWEEN 0 AND 3)
    OR ((NEW.dispatch_key IS NULL OR CHAR_LENGTH(TRIM(NEW.dispatch_key))=0))
    OR ((NEW.template_key IS NULL OR CHAR_LENGTH(TRIM(NEW.template_key))=0))
    OR ((NEW.assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.assistance_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance_event r WHERE r.id=NEW.event_id AND r.patient_id=NEW.patient_id AND r.assistance_id=NEW.assistance_id AND r.cycle=NEW.cycle)))
    OR ((NEW.recipient_kind='NONE' AND (NEW.recipient_user_id IS NOT NULL OR NEW.channel_id IS NOT NULL OR NEW.status<>'NO_CHANNEL' OR NEW.last_result<>'NO_RECIPIENT' OR NEW.last_result IS NULL OR NEW.attempt_count<>0 OR NEW.claim_token IS NOT NULL OR NEW.request_id IS NOT NULL OR NEW.next_attempt_at IS NOT NULL)))
    OR ((NEW.recipient_kind='USER' AND NEW.recipient_user_id IS NULL))
    OR ((NEW.status='CLAIMED' AND (NEW.claim_token IS NULL OR NEW.claimed_at IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status<>'CLAIMED' AND NEW.claim_token IS NOT NULL))
    OR ((NEW.status='DELIVERED' AND NEW.delivered_at IS NULL))
    OR ((NEW.status<>'DELIVERED' AND NEW.delivered_at IS NOT NULL))
    OR ((NEW.status IN ('QUEUED','CLAIMED') AND (NEW.recipient_user_id IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status='UNKNOWN' AND NEW.next_attempt_at IS NOT NULL))
    OR ((NEW.last_result IS NOT NULL AND NEW.last_result NOT IN ('ATTEMPT_STARTED','FAILED_RETRYABLE','FAILED_MANUAL_RETRY_REQUIRED','CLAIMED_UNATTEMPTED','MANUAL_RETRY','QUEUED','DELIVERED','NO_CHANNEL','NO_RECIPIENT','FAILED','UNKNOWN','SUPPRESSED')))
    OR ((NEW.request_id IS NOT NULL AND NEW.request_id NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|-?[1-9][0-9]*)$'))
    OR ((NEW.last_result='ATTEMPT_STARTED' AND (NEW.status<>'CLAIMED' OR NEW.request_id IS NULL OR NEW.attempt_count<1)))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.event_id <=> BINARY OLD.event_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.assistance_id <=> BINARY OLD.assistance_id) AND (BINARY NEW.cycle <=> BINARY OLD.cycle) AND (BINARY NEW.recipient_kind <=> BINARY OLD.recipient_kind) AND (BINARY NEW.recipient_user_id <=> BINARY OLD.recipient_user_id) AND (BINARY NEW.channel_id <=> BINARY OLD.channel_id) AND (BINARY NEW.template_key <=> BINARY OLD.template_key) AND (BINARY NEW.dispatch_key <=> BINARY OLD.dispatch_key) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR (OLD.recipient_kind='NONE')
    OR ((NEW.attempt_count<OLD.attempt_count AND NOT (NEW.status='QUEUED' AND NEW.attempt_count=0 AND NOT (NEW.request_id <=> OLD.request_id) AND NEW.last_result='MANUAL_RETRY' AND OLD.status IN ('FAILED','UNKNOWN','SUPPRESSED','NO_CHANNEL'))))
    OR ((NEW.attempt_count>OLD.attempt_count+1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid family_assistance_notification';
  END IF;
END$$

CREATE TRIGGER hh_fan_bd BEFORE DELETE ON family_assistance_notification
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_eae_bi BEFORE INSERT ON emergency_assistance_event
FOR EACH ROW BEGIN
  IF ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.emergency_event_id IS NOT NULL AND (NEW.emergency_event_id <= 0 OR NEW.emergency_event_id > 9007199254740991)))
    OR (NEW.aggregate_version < 0)
    OR ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.evidence_json IS NOT NULL AND NOT JSON_VALID(NEW.evidence_json)))
    OR ((NEW.evidence_hash IS NOT NULL AND NEW.evidence_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR (NEW.cycle<=0)
    OR ((NEW.type IS NULL OR NEW.type NOT IN ('CREATE_REQUEST','ACCEPT','PROGRESS','HANDOFF','RESOLVE','CANCEL_MISTAKE')))
    OR ((NEW.emergency_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM emergency_event r WHERE r.id=NEW.emergency_event_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.actor_kind IS NULL OR NEW.actor_kind NOT IN ('HUMAN')))
    OR ((NEW.actor_relation IS NULL OR NEW.actor_relation NOT IN ('SELF','ASSISTED','OWNER','DOCTOR','NURSE','FAMILY','REQUESTER','ORIGINATOR','HANDLER','WRITER')))
    OR (NEW.actor_id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid emergency_assistance_event';
  END IF;
END$$

CREATE TRIGGER hh_eae_bu BEFORE UPDATE ON emergency_assistance_event
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_eae_bd BEFORE DELETE ON emergency_assistance_event
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_eac_bi BEFORE INSERT ON emergency_assistance_command
FOR EACH ROW BEGIN
  IF ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.emergency_event_id IS NOT NULL AND (NEW.emergency_event_id <= 0 OR NEW.emergency_event_id > 9007199254740991)))
    OR (NEW.expected_version < 0)
    OR ((NEW.payload_hash IS NOT NULL AND NEW.payload_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.result_json IS NOT NULL AND NOT JSON_VALID(NEW.result_json)))
    OR (NEW.command_key NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR (NEW.result_json IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid emergency_assistance_command';
  END IF;
END$$

CREATE TRIGGER hh_eac_bu BEFORE UPDATE ON emergency_assistance_command
FOR EACH ROW BEGIN
  IF ((NEW.actor_id IS NOT NULL AND (NEW.actor_id <= 0 OR NEW.actor_id > 9007199254740991)))
    OR ((NEW.emergency_event_id IS NOT NULL AND (NEW.emergency_event_id <= 0 OR NEW.emergency_event_id > 9007199254740991)))
    OR (NEW.expected_version < 0)
    OR ((NEW.payload_hash IS NOT NULL AND NEW.payload_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR ((NEW.result_json IS NOT NULL AND NOT JSON_VALID(NEW.result_json)))
    OR (NEW.command_key NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.actor_id <=> BINARY OLD.actor_id) AND (BINARY NEW.command_key <=> BINARY OLD.command_key) AND (BINARY NEW.expected_version <=> BINARY OLD.expected_version) AND (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR (OLD.result_json IS NOT NULL)
    OR (NEW.result_json IS NULL)
    OR (NEW.emergency_event_id IS NULL)
    OR ((OLD.emergency_event_id IS NOT NULL AND NOT (NEW.emergency_event_id <=> OLD.emergency_event_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid emergency_assistance_command';
  END IF;
END$$

CREATE TRIGGER hh_eac_bd BEFORE DELETE ON emergency_assistance_command
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_ean_bi BEFORE INSERT ON emergency_assistance_notification
FOR EACH ROW BEGIN
  IF ((NEW.event_id IS NOT NULL AND (NEW.event_id <= 0 OR NEW.event_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.emergency_event_id IS NOT NULL AND (NEW.emergency_event_id <= 0 OR NEW.emergency_event_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.channel_id IS NOT NULL AND (NEW.channel_id <= 0 OR NEW.channel_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.recipient_kind IS NULL OR NEW.recipient_kind NOT IN ('USER','NONE')))
    OR ((NEW.status IS NULL OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED')))
    OR (NEW.attempt_count NOT BETWEEN 0 AND 3)
    OR ((NEW.dispatch_key IS NULL OR CHAR_LENGTH(TRIM(NEW.dispatch_key))=0))
    OR ((NEW.template_key IS NULL OR CHAR_LENGTH(TRIM(NEW.template_key))=0))
    OR ((NEW.emergency_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM emergency_event r WHERE r.id=NEW.emergency_event_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM emergency_assistance_event r WHERE r.id=NEW.event_id AND r.patient_id=NEW.patient_id AND r.emergency_event_id=NEW.emergency_event_id AND r.cycle=NEW.cycle)))
    OR ((NEW.recipient_kind='NONE' AND (NEW.recipient_user_id IS NOT NULL OR NEW.channel_id IS NOT NULL OR NEW.status<>'NO_CHANNEL' OR NEW.last_result<>'NO_RECIPIENT' OR NEW.last_result IS NULL OR NEW.attempt_count<>0 OR NEW.claim_token IS NOT NULL OR NEW.request_id IS NOT NULL OR NEW.next_attempt_at IS NOT NULL)))
    OR ((NEW.recipient_kind='USER' AND NEW.recipient_user_id IS NULL))
    OR ((NEW.status='CLAIMED' AND (NEW.claim_token IS NULL OR NEW.claimed_at IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status<>'CLAIMED' AND NEW.claim_token IS NOT NULL))
    OR ((NEW.status='DELIVERED' AND NEW.delivered_at IS NULL))
    OR ((NEW.status<>'DELIVERED' AND NEW.delivered_at IS NOT NULL))
    OR ((NEW.status IN ('QUEUED','CLAIMED') AND (NEW.recipient_user_id IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status='UNKNOWN' AND NEW.next_attempt_at IS NOT NULL))
    OR ((NEW.last_result IS NOT NULL AND NEW.last_result NOT IN ('ATTEMPT_STARTED','FAILED_RETRYABLE','FAILED_MANUAL_RETRY_REQUIRED','CLAIMED_UNATTEMPTED','MANUAL_RETRY','QUEUED','DELIVERED','NO_CHANNEL','NO_RECIPIENT','FAILED','UNKNOWN','SUPPRESSED')))
    OR ((NEW.request_id IS NOT NULL AND NEW.request_id NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|-?[1-9][0-9]*)$'))
    OR ((NEW.last_result='ATTEMPT_STARTED' AND (NEW.status<>'CLAIMED' OR NEW.request_id IS NULL OR NEW.attempt_count<1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid emergency_assistance_notification';
  END IF;
END$$

CREATE TRIGGER hh_ean_bu BEFORE UPDATE ON emergency_assistance_notification
FOR EACH ROW BEGIN
  IF ((NEW.event_id IS NOT NULL AND (NEW.event_id <= 0 OR NEW.event_id > 9007199254740991)))
    OR ((NEW.patient_id IS NOT NULL AND (NEW.patient_id <= 0 OR NEW.patient_id > 9007199254740991)))
    OR ((NEW.emergency_event_id IS NOT NULL AND (NEW.emergency_event_id <= 0 OR NEW.emergency_event_id > 9007199254740991)))
    OR ((NEW.recipient_user_id IS NOT NULL AND (NEW.recipient_user_id <= 0 OR NEW.recipient_user_id > 9007199254740991)))
    OR ((NEW.channel_id IS NOT NULL AND (NEW.channel_id <= 0 OR NEW.channel_id > 9007199254740991)))
    OR (NEW.cycle<=0)
    OR ((NEW.recipient_kind IS NULL OR NEW.recipient_kind NOT IN ('USER','NONE')))
    OR ((NEW.status IS NULL OR NEW.status NOT IN ('QUEUED','CLAIMED','DELIVERED','NO_CHANNEL','FAILED','UNKNOWN','SUPPRESSED')))
    OR (NEW.attempt_count NOT BETWEEN 0 AND 3)
    OR ((NEW.dispatch_key IS NULL OR CHAR_LENGTH(TRIM(NEW.dispatch_key))=0))
    OR ((NEW.template_key IS NULL OR CHAR_LENGTH(TRIM(NEW.template_key))=0))
    OR ((NEW.emergency_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM emergency_event r WHERE r.id=NEW.emergency_event_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM emergency_assistance_event r WHERE r.id=NEW.event_id AND r.patient_id=NEW.patient_id AND r.emergency_event_id=NEW.emergency_event_id AND r.cycle=NEW.cycle)))
    OR ((NEW.recipient_kind='NONE' AND (NEW.recipient_user_id IS NOT NULL OR NEW.channel_id IS NOT NULL OR NEW.status<>'NO_CHANNEL' OR NEW.last_result<>'NO_RECIPIENT' OR NEW.last_result IS NULL OR NEW.attempt_count<>0 OR NEW.claim_token IS NOT NULL OR NEW.request_id IS NOT NULL OR NEW.next_attempt_at IS NOT NULL)))
    OR ((NEW.recipient_kind='USER' AND NEW.recipient_user_id IS NULL))
    OR ((NEW.status='CLAIMED' AND (NEW.claim_token IS NULL OR NEW.claimed_at IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status<>'CLAIMED' AND NEW.claim_token IS NOT NULL))
    OR ((NEW.status='DELIVERED' AND NEW.delivered_at IS NULL))
    OR ((NEW.status<>'DELIVERED' AND NEW.delivered_at IS NOT NULL))
    OR ((NEW.status IN ('QUEUED','CLAIMED') AND (NEW.recipient_user_id IS NULL OR NEW.channel_id IS NULL)))
    OR ((NEW.status='UNKNOWN' AND NEW.next_attempt_at IS NOT NULL))
    OR ((NEW.last_result IS NOT NULL AND NEW.last_result NOT IN ('ATTEMPT_STARTED','FAILED_RETRYABLE','FAILED_MANUAL_RETRY_REQUIRED','CLAIMED_UNATTEMPTED','MANUAL_RETRY','QUEUED','DELIVERED','NO_CHANNEL','NO_RECIPIENT','FAILED','UNKNOWN','SUPPRESSED')))
    OR ((NEW.request_id IS NOT NULL AND NEW.request_id NOT REGEXP BINARY '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|-?[1-9][0-9]*)$'))
    OR ((NEW.last_result='ATTEMPT_STARTED' AND (NEW.status<>'CLAIMED' OR NEW.request_id IS NULL OR NEW.attempt_count<1)))
    OR (NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.event_id <=> BINARY OLD.event_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.emergency_event_id <=> BINARY OLD.emergency_event_id) AND (BINARY NEW.cycle <=> BINARY OLD.cycle) AND (BINARY NEW.recipient_kind <=> BINARY OLD.recipient_kind) AND (BINARY NEW.recipient_user_id <=> BINARY OLD.recipient_user_id) AND (BINARY NEW.channel_id <=> BINARY OLD.channel_id) AND (BINARY NEW.template_key <=> BINARY OLD.template_key) AND (BINARY NEW.dispatch_key <=> BINARY OLD.dispatch_key) AND (BINARY NEW.created_at <=> BINARY OLD.created_at)))
    OR (OLD.recipient_kind='NONE')
    OR ((NEW.attempt_count<OLD.attempt_count AND NOT (NEW.status='QUEUED' AND NEW.attempt_count=0 AND NOT (NEW.request_id <=> OLD.request_id) AND NEW.last_result='MANUAL_RETRY' AND OLD.status IN ('FAILED','UNKNOWN','SUPPRESSED','NO_CHANNEL'))))
    OR ((NEW.attempt_count>OLD.attempt_count+1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid emergency_assistance_notification';
  END IF;
END$$

CREATE TRIGGER hh_ean_bd BEFORE DELETE ON emergency_assistance_notification
FOR EACH ROW BEGIN
  IF (TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH immutable evidence';
  END IF;
END$$

CREATE TRIGGER hh_nm_bi BEFORE INSERT ON medication
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR ((NEW.workflow_version=1 AND NEW.execution_id IS NULL))
    OR ((NEW.workflow_version=0 AND NEW.execution_id IS NOT NULL))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.medication_id=NEW.id AND r.patient_id=NEW.patient_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication';
  END IF;
END$$

CREATE TRIGGER hh_nm_bu BEFORE UPDATE ON medication
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR ((NEW.workflow_version=1 AND NEW.execution_id IS NULL))
    OR ((NEW.workflow_version=0 AND NEW.execution_id IS NOT NULL))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.medication_id=NEW.id AND r.patient_id=NEW.patient_id)))
    OR ((OLD.workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.user_id <=> BINARY OLD.user_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.drug_name <=> BINARY OLD.drug_name) AND (BINARY NEW.generic_name <=> BINARY OLD.generic_name) AND (BINARY NEW.specification <=> BINARY OLD.specification) AND (BINARY NEW.unit <=> BINARY OLD.unit) AND (BINARY NEW.dosage_form <=> BINARY OLD.dosage_form) AND (BINARY NEW.manufacturer <=> BINARY OLD.manufacturer) AND (BINARY NEW.approval_number <=> BINARY OLD.approval_number) AND (BINARY NEW.category <=> BINARY OLD.category) AND (BINARY NEW.default_dosage <=> BINARY OLD.default_dosage) AND (BINARY NEW.remark <=> BINARY OLD.remark) AND (BINARY NEW.is_active <=> BINARY OLD.is_active) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.updated_at <=> BINARY OLD.updated_at) AND (BINARY NEW.workflow_version <=> BINARY OLD.workflow_version) AND (BINARY NEW.execution_id <=> BINARY OLD.execution_id)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication';
  END IF;
END$$

CREATE TRIGGER hh_nm_bd BEFORE DELETE ON medication
FOR EACH ROW BEGIN
  IF (OLD.workflow_version=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_np_bi BEFORE INSERT ON electronic_prescription
FOR EACH ROW BEGIN
  IF (NEW.workflow_version NOT IN (0,1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid electronic_prescription';
  END IF;
END$$

CREATE TRIGGER hh_np_bu BEFORE UPDATE ON electronic_prescription
FOR EACH ROW BEGIN
  IF (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR (((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.prescription_id=OLD.id)) AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.visit_id <=> BINARY OLD.visit_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.doctor_user_id <=> BINARY OLD.doctor_user_id) AND (BINARY NEW.version_no <=> BINARY OLD.version_no) AND (BINARY NEW.instructions <=> BINARY OLD.instructions) AND (BINARY NEW.published_at <=> BINARY OLD.published_at) AND (BINARY NEW.created_at <=> BINARY OLD.created_at))))
    OR (((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.prescription_id=OLD.id)) AND NOT ((NEW.status <=> OLD.status) OR (OLD.status='ACTIVE' AND NEW.status='SUPERSEDED')))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid electronic_prescription';
  END IF;
END$$

CREATE TRIGGER hh_np_bd BEFORE DELETE ON electronic_prescription
FOR EACH ROW BEGIN
  IF ((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.prescription_id=OLD.id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_npi_bi BEFORE INSERT ON electronic_prescription_item
FOR EACH ROW BEGIN
  IF (NEW.workflow_version NOT IN (0,1)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid electronic_prescription_item';
  END IF;
END$$

CREATE TRIGGER hh_npi_bu BEFORE UPDATE ON electronic_prescription_item
FOR EACH ROW BEGIN
  IF (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR (((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.prescription_item_id=OLD.id)) AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.prescription_id <=> BINARY OLD.prescription_id) AND (BINARY NEW.drug_name <=> BINARY OLD.drug_name) AND (BINARY NEW.dosage <=> BINARY OLD.dosage) AND (BINARY NEW.frequency <=> BINARY OLD.frequency) AND (BINARY NEW.administration_route <=> BINARY OLD.administration_route) AND (BINARY NEW.duration_days <=> BINARY OLD.duration_days) AND (BINARY NEW.remark <=> BINARY OLD.remark)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid electronic_prescription_item';
  END IF;
END$$

CREATE TRIGGER hh_npi_bd BEFORE DELETE ON electronic_prescription_item
FOR EACH ROW BEGIN
  IF ((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM medication_reconciliation_revision r WHERE r.prescription_item_id=OLD.id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_nci_bi BEFORE INSERT ON care_item
FOR EACH ROW BEGIN
  IF ((NEW.medication_execution_id IS NOT NULL AND (NEW.medication_execution_id <= 0 OR NEW.medication_execution_id > 9007199254740991)))
    OR ((NEW.medication_reconciliation_id IS NOT NULL AND (NEW.medication_reconciliation_id <= 0 OR NEW.medication_reconciliation_id > 9007199254740991)))
    OR ((NEW.family_assistance_id IS NOT NULL AND (NEW.family_assistance_id <= 0 OR NEW.family_assistance_id > 9007199254740991)))
    OR (NEW.medication_workflow_version NOT IN (0,1))
    OR (NEW.family_workflow_version NOT IN (0,1))
    OR ((NEW.medication_workflow_version=1 AND (NEW.kind<>'ORDER' OR NEW.medication_execution_id IS NULL OR NEW.medication_reconciliation_id IS NULL)))
    OR ((NEW.family_workflow_version=1 AND NEW.family_assistance_id IS NULL))
    OR ((NEW.medication_workflow_version=1 AND NEW.family_workflow_version=1))
    OR ((NEW.medication_execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.medication_execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.medication_reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.medication_reconciliation_id AND r.execution_id=NEW.medication_execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.family_assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.family_assistance_id AND r.patient_id=NEW.patient_id AND r.care_item_id=NEW.id AND r.care_appointment_id IS NULL))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid care_item';
  END IF;
END$$

CREATE TRIGGER hh_nci_bu BEFORE UPDATE ON care_item
FOR EACH ROW BEGIN
  IF ((NEW.medication_execution_id IS NOT NULL AND (NEW.medication_execution_id <= 0 OR NEW.medication_execution_id > 9007199254740991)))
    OR ((NEW.medication_reconciliation_id IS NOT NULL AND (NEW.medication_reconciliation_id <= 0 OR NEW.medication_reconciliation_id > 9007199254740991)))
    OR ((NEW.family_assistance_id IS NOT NULL AND (NEW.family_assistance_id <= 0 OR NEW.family_assistance_id > 9007199254740991)))
    OR (NEW.medication_workflow_version NOT IN (0,1))
    OR (NEW.family_workflow_version NOT IN (0,1))
    OR (NEW.medication_workflow_version<OLD.medication_workflow_version)
    OR (NEW.family_workflow_version<OLD.family_workflow_version)
    OR ((NEW.medication_workflow_version=1 AND (NEW.kind<>'ORDER' OR NEW.medication_execution_id IS NULL OR NEW.medication_reconciliation_id IS NULL)))
    OR ((NEW.family_workflow_version=1 AND NEW.family_assistance_id IS NULL))
    OR ((NEW.medication_workflow_version=1 AND NEW.family_workflow_version=1))
    OR ((NEW.medication_execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.medication_execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.medication_reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.medication_reconciliation_id AND r.execution_id=NEW.medication_execution_id AND r.patient_id=NEW.patient_id)))
    OR ((NEW.family_assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.family_assistance_id AND r.patient_id=NEW.patient_id AND r.care_item_id=NEW.id AND r.care_appointment_id IS NULL)))
    OR ((OLD.family_workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.user_id <=> BINARY OLD.user_id) AND (BINARY NEW.kind <=> BINARY OLD.kind) AND (BINARY NEW.title <=> BINARY OLD.title) AND (BINARY NEW.status <=> BINARY OLD.status) AND (BINARY NEW.assigned_user_id <=> BINARY OLD.assigned_user_id) AND (BINARY NEW.actor_id <=> BINARY OLD.actor_id) AND (BINARY NEW.actor_name <=> BINARY OLD.actor_name) AND (BINARY NEW.event_at <=> BINARY OLD.event_at) AND (BINARY NEW.notify_at <=> BINARY OLD.notify_at) AND (BINARY NEW.notified_at <=> BINARY OLD.notified_at) AND (BINARY NEW.escalated_at <=> BINARY OLD.escalated_at) AND (BINARY NEW.data_json <=> BINARY OLD.data_json) AND (BINARY NEW.created_at <=> BINARY OLD.created_at))))
    OR ((OLD.medication_workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.user_id <=> BINARY OLD.user_id) AND (BINARY NEW.kind <=> BINARY OLD.kind) AND (BINARY NEW.title <=> BINARY OLD.title) AND (BINARY NEW.assigned_user_id <=> BINARY OLD.assigned_user_id) AND (BINARY NEW.actor_id <=> BINARY OLD.actor_id) AND (BINARY NEW.actor_name <=> BINARY OLD.actor_name) AND (BINARY NEW.event_at <=> BINARY OLD.event_at) AND (BINARY NEW.notify_at <=> BINARY OLD.notify_at) AND (BINARY NEW.notified_at <=> BINARY OLD.notified_at) AND (BINARY NEW.escalated_at <=> BINARY OLD.escalated_at) AND (BINARY NEW.data_json <=> BINARY OLD.data_json) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.medication_execution_id <=> BINARY OLD.medication_execution_id) AND (BINARY NEW.medication_reconciliation_id <=> BINARY OLD.medication_reconciliation_id))))
    OR ((OLD.family_workflow_version=1 AND NOT (NEW.family_assistance_id <=> OLD.family_assistance_id) AND NOT EXISTS (SELECT 1 FROM family_assistance f WHERE f.id=NEW.family_assistance_id AND f.previous_assistance_id=OLD.family_assistance_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid care_item';
  END IF;
END$$

CREATE TRIGGER hh_nci_bd BEFORE DELETE ON care_item
FOR EACH ROW BEGIN
  IF (OLD.medication_workflow_version=1 OR OLD.family_workflow_version=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_nca_bi BEFORE INSERT ON care_appointment
FOR EACH ROW BEGIN
  IF ((NEW.family_assistance_id IS NOT NULL AND (NEW.family_assistance_id <= 0 OR NEW.family_assistance_id > 9007199254740991)))
    OR (NEW.family_workflow_version NOT IN (0,1))
    OR ((NEW.family_workflow_version=1 AND NEW.family_assistance_id IS NULL))
    OR ((NEW.family_assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.family_assistance_id AND r.patient_id=NEW.patient_id AND r.care_appointment_id=NEW.id AND r.care_item_id IS NULL))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid care_appointment';
  END IF;
END$$

CREATE TRIGGER hh_nca_bu BEFORE UPDATE ON care_appointment
FOR EACH ROW BEGIN
  IF ((NEW.family_assistance_id IS NOT NULL AND (NEW.family_assistance_id <= 0 OR NEW.family_assistance_id > 9007199254740991)))
    OR (NEW.family_workflow_version NOT IN (0,1))
    OR (NEW.family_workflow_version<OLD.family_workflow_version)
    OR ((NEW.family_workflow_version=1 AND NEW.family_assistance_id IS NULL))
    OR ((NEW.family_assistance_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM family_assistance r WHERE r.id=NEW.family_assistance_id AND r.patient_id=NEW.patient_id AND r.care_appointment_id=NEW.id AND r.care_item_id IS NULL)))
    OR ((OLD.family_workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.created_by <=> BINARY OLD.created_by) AND (BINARY NEW.created_at <=> BINARY OLD.created_at))))
    OR ((OLD.family_workflow_version=1 AND NOT (NEW.family_assistance_id <=> OLD.family_assistance_id) AND NOT EXISTS (SELECT 1 FROM family_assistance f WHERE f.id=NEW.family_assistance_id AND f.previous_assistance_id=OLD.family_assistance_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid care_appointment';
  END IF;
END$$

CREATE TRIGGER hh_nca_bd BEFORE DELETE ON care_appointment
FOR EACH ROW BEGIN
  IF (OLD.family_workflow_version=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_nmr_bi BEFORE INSERT ON medication_reminder
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.reconciliation_id IS NOT NULL AND (NEW.reconciliation_id <= 0 OR NEW.reconciliation_id > 9007199254740991)))
    OR ((NEW.order_id IS NOT NULL AND (NEW.order_id <= 0 OR NEW.order_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR ((NEW.workflow_version=1 AND (NEW.execution_id IS NULL OR NEW.reconciliation_id IS NULL OR NEW.order_id IS NULL OR NEW.planned_quantity IS NULL OR NEW.planned_quantity<=0 OR NEW.planned_unit IS NULL OR NEW.schedule_zone IS NULL OR NEW.effective_at_utc IS NULL)))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.reconciliation_id AND r.execution_id=NEW.execution_id)))
    OR ((NEW.order_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_item r WHERE r.id=NEW.order_id AND r.medication_execution_id=NEW.execution_id AND r.medication_reconciliation_id=NEW.reconciliation_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_reminder';
  END IF;
END$$

CREATE TRIGGER hh_nmr_bu BEFORE UPDATE ON medication_reminder
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.reconciliation_id IS NOT NULL AND (NEW.reconciliation_id <= 0 OR NEW.reconciliation_id > 9007199254740991)))
    OR ((NEW.order_id IS NOT NULL AND (NEW.order_id <= 0 OR NEW.order_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR ((NEW.workflow_version=1 AND (NEW.execution_id IS NULL OR NEW.reconciliation_id IS NULL OR NEW.order_id IS NULL OR NEW.planned_quantity IS NULL OR NEW.planned_quantity<=0 OR NEW.planned_unit IS NULL OR NEW.schedule_zone IS NULL OR NEW.effective_at_utc IS NULL)))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.reconciliation_id AND r.execution_id=NEW.execution_id)))
    OR ((NEW.order_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_item r WHERE r.id=NEW.order_id AND r.medication_execution_id=NEW.execution_id AND r.medication_reconciliation_id=NEW.reconciliation_id)))
    OR ((OLD.workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.user_id <=> BINARY OLD.user_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.remind_time <=> BINARY OLD.remind_time) AND (BINARY NEW.repeat_days <=> BINARY OLD.repeat_days) AND (BINARY NEW.dosage <=> BINARY OLD.dosage) AND (BINARY NEW.remark <=> BINARY OLD.remark) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.workflow_version <=> BINARY OLD.workflow_version) AND (BINARY NEW.execution_id <=> BINARY OLD.execution_id) AND (BINARY NEW.reconciliation_id <=> BINARY OLD.reconciliation_id) AND (BINARY NEW.order_id <=> BINARY OLD.order_id) AND (BINARY NEW.planned_quantity <=> BINARY OLD.planned_quantity) AND (BINARY NEW.planned_unit <=> BINARY OLD.planned_unit) AND (BINARY NEW.schedule_zone <=> BINARY OLD.schedule_zone) AND (BINARY NEW.effective_at_utc <=> BINARY OLD.effective_at_utc) AND (BINARY NEW.end_at_utc <=> BINARY OLD.end_at_utc)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_reminder';
  END IF;
END$$

CREATE TRIGGER hh_nmr_bd BEFORE DELETE ON medication_reminder
FOR EACH ROW BEGIN
  IF (OLD.workflow_version=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_nmi_bi BEFORE INSERT ON medication_intake
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.reconciliation_id IS NOT NULL AND (NEW.reconciliation_id <= 0 OR NEW.reconciliation_id > 9007199254740991)))
    OR ((NEW.latest_action_id IS NOT NULL AND (NEW.latest_action_id <= 0 OR NEW.latest_action_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR ((NEW.workflow_version=1 AND (NEW.execution_id IS NULL OR NEW.provenance IS NULL OR NEW.follow_up_state IS NULL OR NEW.follow_up_kind IS NULL OR NEW.follow_up_cycle<=0)))
    OR ((NEW.provenance IS NOT NULL AND NEW.provenance NOT IN ('VERIFIED_ORDER','PERSONAL_UNVERIFIED')))
    OR ((NEW.follow_up_state IS NOT NULL AND NEW.follow_up_state NOT IN ('OPEN','ENDED_UNRESOLVED','FACT_RECORDED','NOT_APPLICABLE')))
    OR ((NEW.follow_up_kind IS NOT NULL AND NEW.follow_up_kind NOT IN ('DOSE_REMINDER','FOLLOW_UP_ONLY')))
    OR ((NEW.provenance='PERSONAL_UNVERIFIED' AND NEW.reconciliation_id IS NOT NULL))
    OR ((NEW.provenance='VERIFIED_ORDER' AND (NEW.reconciliation_id IS NULL OR NEW.scheduled_at_utc IS NULL OR NEW.schedule_zone IS NULL OR NEW.schedule_offset_seconds IS NULL OR NEW.planned_quantity IS NULL OR NEW.planned_quantity<=0 OR NEW.planned_unit IS NULL)))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.reconciliation_id AND r.execution_id=NEW.execution_id)))
    OR ((NEW.latest_action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.latest_action_id AND r.intake_id=NEW.id AND r.patient_id=NEW.patient_id AND r.execution_id=NEW.execution_id)))
    OR ((NEW.provenance='VERIFIED_ORDER' AND (NEW.workflow_version<>1 OR NEW.reminder_id IS NULL OR NOT EXISTS (SELECT 1 FROM medication_reminder r WHERE r.id=NEW.reminder_id AND r.workflow_version=1 AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id AND r.execution_id=NEW.execution_id AND r.reconciliation_id=NEW.reconciliation_id AND r.planned_quantity=NEW.planned_quantity AND BINARY r.planned_unit=BINARY NEW.planned_unit AND BINARY r.schedule_zone=BINARY NEW.schedule_zone AND NEW.scheduled_at_utc>=r.effective_at_utc AND (r.end_at_utc IS NULL OR NEW.scheduled_at_utc<r.end_at_utc))))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_intake';
  END IF;
END$$

CREATE TRIGGER hh_nmi_bu BEFORE UPDATE ON medication_intake
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.reconciliation_id IS NOT NULL AND (NEW.reconciliation_id <= 0 OR NEW.reconciliation_id > 9007199254740991)))
    OR ((NEW.latest_action_id IS NOT NULL AND (NEW.latest_action_id <= 0 OR NEW.latest_action_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR ((NEW.workflow_version=1 AND (NEW.execution_id IS NULL OR NEW.provenance IS NULL OR NEW.follow_up_state IS NULL OR NEW.follow_up_kind IS NULL OR NEW.follow_up_cycle<=0)))
    OR ((NEW.provenance IS NOT NULL AND NEW.provenance NOT IN ('VERIFIED_ORDER','PERSONAL_UNVERIFIED')))
    OR ((NEW.follow_up_state IS NOT NULL AND NEW.follow_up_state NOT IN ('OPEN','ENDED_UNRESOLVED','FACT_RECORDED','NOT_APPLICABLE')))
    OR ((NEW.follow_up_kind IS NOT NULL AND NEW.follow_up_kind NOT IN ('DOSE_REMINDER','FOLLOW_UP_ONLY')))
    OR ((NEW.provenance='PERSONAL_UNVERIFIED' AND NEW.reconciliation_id IS NOT NULL))
    OR ((NEW.provenance='VERIFIED_ORDER' AND (NEW.reconciliation_id IS NULL OR NEW.scheduled_at_utc IS NULL OR NEW.schedule_zone IS NULL OR NEW.schedule_offset_seconds IS NULL OR NEW.planned_quantity IS NULL OR NEW.planned_quantity<=0 OR NEW.planned_unit IS NULL)))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.reconciliation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_reconciliation r WHERE r.id=NEW.reconciliation_id AND r.execution_id=NEW.execution_id)))
    OR ((NEW.latest_action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.latest_action_id AND r.intake_id=NEW.id AND r.patient_id=NEW.patient_id AND r.execution_id=NEW.execution_id)))
    OR ((OLD.workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.user_id <=> BINARY OLD.user_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.reminder_id <=> BINARY OLD.reminder_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.scheduled_at <=> BINARY OLD.scheduled_at) AND (BINARY NEW.dosage <=> BINARY OLD.dosage) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.workflow_version <=> BINARY OLD.workflow_version) AND (BINARY NEW.execution_id <=> BINARY OLD.execution_id) AND (BINARY NEW.reconciliation_id <=> BINARY OLD.reconciliation_id) AND (BINARY NEW.provenance <=> BINARY OLD.provenance) AND (BINARY NEW.planned_quantity <=> BINARY OLD.planned_quantity) AND (BINARY NEW.planned_unit <=> BINARY OLD.planned_unit) AND (BINARY NEW.schedule_zone <=> BINARY OLD.schedule_zone) AND (BINARY NEW.scheduled_at_utc <=> BINARY OLD.scheduled_at_utc) AND (BINARY NEW.schedule_offset_seconds <=> BINARY OLD.schedule_offset_seconds))))
    OR ((NEW.follow_up_cycle<OLD.follow_up_cycle OR NEW.follow_up_cycle>OLD.follow_up_cycle+1))
    OR ((NEW.provenance='VERIFIED_ORDER' AND (NEW.workflow_version<>1 OR NEW.reminder_id IS NULL OR NOT EXISTS (SELECT 1 FROM medication_reminder r WHERE r.id=NEW.reminder_id AND r.workflow_version=1 AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id AND r.execution_id=NEW.execution_id AND r.reconciliation_id=NEW.reconciliation_id AND r.planned_quantity=NEW.planned_quantity AND BINARY r.planned_unit=BINARY NEW.planned_unit AND BINARY r.schedule_zone=BINARY NEW.schedule_zone AND NEW.scheduled_at_utc>=r.effective_at_utc AND (r.end_at_utc IS NULL OR NEW.scheduled_at_utc<r.end_at_utc)))))
    OR ((OLD.workflow_version=0 AND NEW.workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.user_id <=> BINARY OLD.user_id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.reminder_id <=> BINARY OLD.reminder_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.scheduled_at <=> BINARY OLD.scheduled_at) AND (BINARY NEW.status <=> BINARY OLD.status) AND (BINARY NEW.action_at <=> BINARY OLD.action_at) AND (BINARY NEW.snooze_until <=> BINARY OLD.snooze_until) AND (BINARY NEW.dosage <=> BINARY OLD.dosage) AND (BINARY NEW.reason <=> BINARY OLD.reason) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.updated_at <=> BINARY OLD.updated_at)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_intake';
  END IF;
END$$

CREATE TRIGGER hh_nmi_bd BEFORE DELETE ON medication_intake
FOR EACH ROW BEGIN
  IF (OLD.workflow_version=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_nia_bi BEFORE INSERT ON care_intake_action
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.execution_event_id IS NOT NULL AND (NEW.execution_event_id <= 0 OR NEW.execution_event_id > 9007199254740991)))
    OR ((NEW.original_action_id IS NOT NULL AND (NEW.original_action_id <= 0 OR NEW.original_action_id > 9007199254740991)))
    OR ((NEW.provenance_json IS NOT NULL AND NOT JSON_VALID(NEW.provenance_json)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR ((NEW.workflow_version=1 AND (NEW.execution_id IS NULL OR NEW.recorded_at_utc IS NULL OR NEW.action_kind IS NULL OR NEW.amount_basis IS NULL OR NEW.provenance_json IS NULL OR NEW.status NOT IN ('TAKEN','SKIPPED') OR NEW.status IS NULL OR NEW.recorded_for NOT IN ('SELF','ASSISTED') OR NEW.recorded_for IS NULL)))
    OR ((NEW.action_kind IS NOT NULL AND NEW.action_kind NOT IN ('FACT','CORRECTION')))
    OR ((NEW.amount_basis IS NOT NULL AND NEW.amount_basis NOT IN ('ACTUAL','CONFIRMED_PLAN','UNKNOWN','NOT_APPLICABLE')))
    OR ((NEW.workflow_version=1 AND NEW.actual_at_utc IS NULL))
    OR ((NEW.workflow_version=1 AND NEW.actual_at_utc>NEW.recorded_at_utc))
    OR ((NEW.workflow_version=1 AND NEW.status='TAKEN' AND NEW.amount_basis IN ('ACTUAL','CONFIRMED_PLAN') AND (NEW.quantity IS NULL OR NEW.quantity<=0 OR NEW.unit IS NULL OR CHAR_LENGTH(TRIM(NEW.unit))=0)))
    OR ((NEW.action_kind='CORRECTION' AND (NEW.original_action_id IS NULL OR NEW.correction_reason IS NULL OR CHAR_LENGTH(TRIM(NEW.correction_reason))=0)))
    OR ((NEW.action_kind='FACT' AND NEW.original_action_id IS NOT NULL))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id AND r.medication_id=(SELECT i.medication_id FROM medication_intake i WHERE i.id=NEW.intake_id AND i.patient_id=NEW.patient_id))))
    OR ((NEW.original_action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.original_action_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id AND r.id<>NEW.id)))
    OR ((NEW.execution_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution_event r WHERE r.id=NEW.execution_event_id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id AND r.action_id=NEW.id)))
    OR ((EXISTS (SELECT 1 FROM medication_intake i JOIN medication_stock s ON s.patient_id=i.patient_id AND s.medication_id=i.medication_id WHERE i.id=NEW.intake_id AND s.workflow_version=1) AND NEW.workflow_version<>1))
    OR ((NEW.workflow_version=1 AND (NEW.actor_id IS NULL OR NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.id=NEW.actor_id)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid care_intake_action';
  END IF;
END$$

CREATE TRIGGER hh_nia_bu BEFORE UPDATE ON care_intake_action
FOR EACH ROW BEGIN
  IF ((NEW.execution_id IS NOT NULL AND (NEW.execution_id <= 0 OR NEW.execution_id > 9007199254740991)))
    OR ((NEW.execution_event_id IS NOT NULL AND (NEW.execution_event_id <= 0 OR NEW.execution_event_id > 9007199254740991)))
    OR ((NEW.original_action_id IS NOT NULL AND (NEW.original_action_id <= 0 OR NEW.original_action_id > 9007199254740991)))
    OR ((NEW.provenance_json IS NOT NULL AND NOT JSON_VALID(NEW.provenance_json)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR ((NEW.workflow_version=1 AND (NEW.execution_id IS NULL OR NEW.recorded_at_utc IS NULL OR NEW.action_kind IS NULL OR NEW.amount_basis IS NULL OR NEW.provenance_json IS NULL OR NEW.status NOT IN ('TAKEN','SKIPPED') OR NEW.status IS NULL OR NEW.recorded_for NOT IN ('SELF','ASSISTED') OR NEW.recorded_for IS NULL)))
    OR ((NEW.action_kind IS NOT NULL AND NEW.action_kind NOT IN ('FACT','CORRECTION')))
    OR ((NEW.amount_basis IS NOT NULL AND NEW.amount_basis NOT IN ('ACTUAL','CONFIRMED_PLAN','UNKNOWN','NOT_APPLICABLE')))
    OR ((NEW.workflow_version=1 AND NEW.actual_at_utc IS NULL))
    OR ((NEW.workflow_version=1 AND NEW.actual_at_utc>NEW.recorded_at_utc))
    OR ((NEW.workflow_version=1 AND NEW.status='TAKEN' AND NEW.amount_basis IN ('ACTUAL','CONFIRMED_PLAN') AND (NEW.quantity IS NULL OR NEW.quantity<=0 OR NEW.unit IS NULL OR CHAR_LENGTH(TRIM(NEW.unit))=0)))
    OR ((NEW.action_kind='CORRECTION' AND (NEW.original_action_id IS NULL OR NEW.correction_reason IS NULL OR CHAR_LENGTH(TRIM(NEW.correction_reason))=0)))
    OR ((NEW.action_kind='FACT' AND NEW.original_action_id IS NOT NULL))
    OR ((NEW.execution_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution r WHERE r.id=NEW.execution_id AND r.patient_id=NEW.patient_id AND r.medication_id=(SELECT i.medication_id FROM medication_intake i WHERE i.id=NEW.intake_id AND i.patient_id=NEW.patient_id))))
    OR ((NEW.original_action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.original_action_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id AND r.id<>NEW.id)))
    OR ((NEW.execution_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_execution_event r WHERE r.id=NEW.execution_event_id AND r.execution_id=NEW.execution_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id AND r.action_id=NEW.id)))
    OR (((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM care_intake_action a WHERE a.original_action_id=OLD.id) OR EXISTS (SELECT 1 FROM medication_execution_event e WHERE e.action_id=OLD.id) OR EXISTS (SELECT 1 FROM stock_event e WHERE e.action_id=OLD.id OR e.settled_action_id=OLD.id) OR EXISTS (SELECT 1 FROM medication_intake i JOIN medication_stock s ON s.patient_id=i.patient_id AND s.medication_id=i.medication_id WHERE i.id=OLD.intake_id AND s.workflow_version=1)) AND NOT (OLD.workflow_version=1 AND OLD.execution_event_id IS NULL AND NEW.execution_event_id IS NOT NULL AND (BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.intake_id <=> BINARY OLD.intake_id) AND (BINARY NEW.actor_id <=> BINARY OLD.actor_id) AND (BINARY NEW.actor_name <=> BINARY OLD.actor_name) AND (BINARY NEW.status <=> BINARY OLD.status) AND (BINARY NEW.recorded_for <=> BINARY OLD.recorded_for) AND (BINARY NEW.quantity <=> BINARY OLD.quantity) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.workflow_version <=> BINARY OLD.workflow_version) AND (BINARY NEW.execution_id <=> BINARY OLD.execution_id) AND (BINARY NEW.original_action_id <=> BINARY OLD.original_action_id) AND (BINARY NEW.action_kind <=> BINARY OLD.action_kind) AND (BINARY NEW.actual_at_utc <=> BINARY OLD.actual_at_utc) AND (BINARY NEW.recorded_at_utc <=> BINARY OLD.recorded_at_utc) AND (BINARY NEW.unit <=> BINARY OLD.unit) AND (BINARY NEW.amount_basis <=> BINARY OLD.amount_basis) AND (BINARY NEW.correction_reason <=> BINARY OLD.correction_reason) AND (BINARY NEW.provenance_json <=> BINARY OLD.provenance_json))))
    OR ((NEW.workflow_version=1 AND (NEW.actor_id IS NULL OR NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.id=NEW.actor_id)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid care_intake_action';
  END IF;
END$$

CREATE TRIGGER hh_nia_bd BEFORE DELETE ON care_intake_action
FOR EACH ROW BEGIN
  IF ((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM care_intake_action a WHERE a.original_action_id=OLD.id) OR EXISTS (SELECT 1 FROM medication_execution_event e WHERE e.action_id=OLD.id) OR EXISTS (SELECT 1 FROM stock_event e WHERE e.action_id=OLD.id OR e.settled_action_id=OLD.id) OR EXISTS (SELECT 1 FROM medication_intake i JOIN medication_stock s ON s.patient_id=i.patient_id AND s.medication_id=i.medication_id WHERE i.id=OLD.intake_id AND s.workflow_version=1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_ns_bi BEFORE INSERT ON medication_stock
FOR EACH ROW BEGIN
  IF (NEW.lock_version < 0)
    OR ((NEW.current_work_id IS NOT NULL AND (NEW.current_work_id <= 0 OR NEW.current_work_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR ((NEW.workflow_version=1 AND (NEW.adopted_at IS NULL OR NEW.current_work_id IS NULL)))
    OR ((NEW.current_work_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_work r WHERE r.id=NEW.current_work_id AND r.stock_id=NEW.id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_stock';
  END IF;
END$$

CREATE TRIGGER hh_ns_bu BEFORE UPDATE ON medication_stock
FOR EACH ROW BEGIN
  IF (NEW.lock_version < 0)
    OR ((NEW.current_work_id IS NOT NULL AND (NEW.current_work_id <= 0 OR NEW.current_work_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR ((NEW.workflow_version=1 AND (NEW.adopted_at IS NULL OR NEW.current_work_id IS NULL)))
    OR ((NEW.current_work_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_work r WHERE r.id=NEW.current_work_id AND r.stock_id=NEW.id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((OLD.workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.unit <=> BINARY OLD.unit) AND (BINARY NEW.adopted_at <=> BINARY OLD.adopted_at))))
    OR ((NEW.lock_version<OLD.lock_version OR NEW.lock_version>OLD.lock_version+1))
    OR ((OLD.workflow_version=1 AND NOT (NEW.current_work_id <=> OLD.current_work_id) AND NOT EXISTS (SELECT 1 FROM stock_work w WHERE w.id=NEW.current_work_id AND w.previous_work_id=OLD.current_work_id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_stock';
  END IF;
END$$

CREATE TRIGGER hh_ns_bd BEFORE DELETE ON medication_stock
FOR EACH ROW BEGIN
  IF (OLD.workflow_version=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_nsm_bi BEFORE INSERT ON medication_stock_movement
FOR EACH ROW BEGIN
  IF ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.intake_id IS NOT NULL AND (NEW.intake_id <= 0 OR NEW.intake_id > 9007199254740991)))
    OR ((NEW.action_id IS NOT NULL AND (NEW.action_id <= 0 OR NEW.action_id > 9007199254740991)))
    OR ((NEW.count_id IS NOT NULL AND (NEW.count_id <= 0 OR NEW.count_id > 9007199254740991)))
    OR ((NEW.stock_event_id IS NOT NULL AND (NEW.stock_event_id <= 0 OR NEW.stock_event_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR ((NEW.workflow_version=1 AND (NEW.stock_id IS NULL OR NEW.movement_kind IS NULL OR NEW.unit IS NULL OR CHAR_LENGTH(TRIM(NEW.unit))=0 OR NEW.actual_at_utc IS NULL OR NEW.source_key IS NULL)))
    OR ((NEW.movement_kind IS NOT NULL AND NEW.movement_kind NOT IN ('INTAKE','REPLENISHMENT','COUNT','CORRECTION')))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.intake_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_intake r WHERE r.id=NEW.intake_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.action_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id)))
    OR ((NEW.count_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_count r WHERE r.id=NEW.count_id AND r.patient_id=NEW.patient_id AND r.stock_id=NEW.stock_id)))
    OR ((NEW.stock_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.stock_event_id AND r.patient_id=NEW.patient_id AND r.stock_id=NEW.stock_id AND (r.original_movement_id=NEW.id OR r.compensation_movement_id=NEW.id OR r.movement_id=NEW.id))))
    OR ((NEW.movement_kind='INTAKE' AND (NEW.intake_id IS NULL OR NEW.action_id IS NULL OR NEW.quantity>=0 OR NOT (BINARY NEW.source_key <=> BINARY CONCAT('INTAKE:',NEW.intake_id)))))
    OR ((NEW.movement_kind='COUNT' AND (NEW.count_id IS NULL OR NOT (BINARY NEW.source_key <=> BINARY CONCAT('COUNT:',NEW.count_id)))))
    OR ((NEW.movement_kind='REPLENISHMENT' AND (NEW.quantity<=0 OR NEW.source_key NOT REGEXP BINARY '^REPLENISHMENT:[0-9a-f-]{36}$')))
    OR ((NEW.movement_kind='CORRECTION' AND (NEW.intake_id IS NULL OR NEW.action_id IS NULL OR NEW.source_key NOT REGEXP BINARY '^CORRECTION:[0-9a-f-]{36}$')))
    OR ((EXISTS (SELECT 1 FROM medication_stock s WHERE s.patient_id=NEW.patient_id AND s.medication_id=NEW.medication_id AND s.workflow_version=1) AND NEW.workflow_version<>1))
    OR ((NEW.workflow_version=1 AND (NEW.actor_id IS NULL OR NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.id=NEW.actor_id)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_stock_movement';
  END IF;
END$$

CREATE TRIGGER hh_nsm_bu BEFORE UPDATE ON medication_stock_movement
FOR EACH ROW BEGIN
  IF ((NEW.stock_id IS NOT NULL AND (NEW.stock_id <= 0 OR NEW.stock_id > 9007199254740991)))
    OR ((NEW.intake_id IS NOT NULL AND (NEW.intake_id <= 0 OR NEW.intake_id > 9007199254740991)))
    OR ((NEW.action_id IS NOT NULL AND (NEW.action_id <= 0 OR NEW.action_id > 9007199254740991)))
    OR ((NEW.count_id IS NOT NULL AND (NEW.count_id <= 0 OR NEW.count_id > 9007199254740991)))
    OR ((NEW.stock_event_id IS NOT NULL AND (NEW.stock_event_id <= 0 OR NEW.stock_event_id > 9007199254740991)))
    OR (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR ((NEW.workflow_version=1 AND (NEW.stock_id IS NULL OR NEW.movement_kind IS NULL OR NEW.unit IS NULL OR CHAR_LENGTH(TRIM(NEW.unit))=0 OR NEW.actual_at_utc IS NULL OR NEW.source_key IS NULL)))
    OR ((NEW.movement_kind IS NOT NULL AND NEW.movement_kind NOT IN ('INTAKE','REPLENISHMENT','COUNT','CORRECTION')))
    OR ((NEW.stock_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_stock r WHERE r.id=NEW.stock_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.intake_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM medication_intake r WHERE r.id=NEW.intake_id AND r.patient_id=NEW.patient_id AND r.medication_id=NEW.medication_id)))
    OR ((NEW.action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM care_intake_action r WHERE r.id=NEW.action_id AND r.patient_id=NEW.patient_id AND r.intake_id=NEW.intake_id)))
    OR ((NEW.count_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_count r WHERE r.id=NEW.count_id AND r.patient_id=NEW.patient_id AND r.stock_id=NEW.stock_id)))
    OR ((NEW.stock_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stock_event r WHERE r.id=NEW.stock_event_id AND r.patient_id=NEW.patient_id AND r.stock_id=NEW.stock_id AND (r.original_movement_id=NEW.id OR r.compensation_movement_id=NEW.id OR r.movement_id=NEW.id))))
    OR ((NEW.movement_kind='INTAKE' AND (NEW.intake_id IS NULL OR NEW.action_id IS NULL OR NEW.quantity>=0 OR NOT (BINARY NEW.source_key <=> BINARY CONCAT('INTAKE:',NEW.intake_id)))))
    OR ((NEW.movement_kind='COUNT' AND (NEW.count_id IS NULL OR NOT (BINARY NEW.source_key <=> BINARY CONCAT('COUNT:',NEW.count_id)))))
    OR ((NEW.movement_kind='REPLENISHMENT' AND (NEW.quantity<=0 OR NEW.source_key NOT REGEXP BINARY '^REPLENISHMENT:[0-9a-f-]{36}$')))
    OR ((NEW.movement_kind='CORRECTION' AND (NEW.intake_id IS NULL OR NEW.action_id IS NULL OR NEW.source_key NOT REGEXP BINARY '^CORRECTION:[0-9a-f-]{36}$')))
    OR (((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM medication_stock s WHERE s.patient_id=OLD.patient_id AND s.medication_id=OLD.medication_id AND s.workflow_version=1) OR EXISTS (SELECT 1 FROM stock_event e WHERE e.original_movement_id=OLD.id OR e.compensation_movement_id=OLD.id OR e.movement_id=OLD.id)) AND NOT (OLD.workflow_version=1 AND OLD.stock_event_id IS NULL AND NEW.stock_event_id IS NOT NULL AND (BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.medication_id <=> BINARY OLD.medication_id) AND (BINARY NEW.quantity <=> BINARY OLD.quantity) AND (BINARY NEW.reason <=> BINARY OLD.reason) AND (BINARY NEW.source_key <=> BINARY OLD.source_key) AND (BINARY NEW.actor_id <=> BINARY OLD.actor_id) AND (BINARY NEW.actor_name <=> BINARY OLD.actor_name) AND (BINARY NEW.created_at <=> BINARY OLD.created_at) AND (BINARY NEW.workflow_version <=> BINARY OLD.workflow_version) AND (BINARY NEW.stock_id <=> BINARY OLD.stock_id) AND (BINARY NEW.intake_id <=> BINARY OLD.intake_id) AND (BINARY NEW.action_id <=> BINARY OLD.action_id) AND (BINARY NEW.count_id <=> BINARY OLD.count_id) AND (BINARY NEW.movement_kind <=> BINARY OLD.movement_kind) AND (BINARY NEW.unit <=> BINARY OLD.unit) AND (BINARY NEW.actual_at_utc <=> BINARY OLD.actual_at_utc))))
    OR ((NEW.workflow_version=1 AND (NEW.actor_id IS NULL OR NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.id=NEW.actor_id)))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid medication_stock_movement';
  END IF;
END$$

CREATE TRIGGER hh_nsm_bd BEFORE DELETE ON medication_stock_movement
FOR EACH ROW BEGIN
  IF ((OLD.workflow_version=1 OR EXISTS (SELECT 1 FROM medication_stock s WHERE s.patient_id=OLD.patient_id AND s.medication_id=OLD.medication_id AND s.workflow_version=1) OR EXISTS (SELECT 1 FROM stock_event e WHERE e.original_movement_id=OLD.id OR e.compensation_movement_id=OLD.id OR e.movement_id=OLD.id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

CREATE TRIGGER hh_nee_bi BEFORE INSERT ON emergency_event
FOR EACH ROW BEGIN
  IF (NEW.lock_version < 0)
    OR ((NEW.requester_user_id IS NOT NULL AND (NEW.requester_user_id <= 0 OR NEW.requester_user_id > 9007199254740991)))
    OR ((NEW.current_lead_id IS NOT NULL AND (NEW.current_lead_id <= 0 OR NEW.current_lead_id > 9007199254740991)))
    OR ((NEW.pending_successor_id IS NOT NULL AND (NEW.pending_successor_id <= 0 OR NEW.pending_successor_id > 9007199254740991)))
    OR ((NEW.snapshot_hash IS NOT NULL AND NEW.snapshot_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR (NEW.workflow_version NOT IN (0,1))
    OR ((NEW.workflow_state IS NOT NULL AND NEW.workflow_state NOT IN ('REQUESTED','ACCEPTED','IN_PROGRESS','RESOLVED','CANCELLED_MISTAKE')))
    OR ((NEW.response_status IS NOT NULL AND NEW.response_status NOT IN ('UNANSWERED','ANSWERED')))
    OR ((NEW.workflow_version=1 AND (NEW.requester_user_id IS NULL OR NEW.requester_user_id<>NEW.triggered_by OR NEW.requested_at_utc IS NULL OR NEW.snapshot_hash IS NULL OR NEW.workflow_state IS NULL OR NEW.response_status IS NULL)))
    OR ((NEW.current_lead_id IS NOT NULL AND NEW.current_lead_id=NEW.requester_user_id))
    OR ((NEW.pending_successor_id IS NOT NULL AND NEW.pending_successor_id=NEW.requester_user_id))
    OR ((NEW.response_status='ANSWERED' AND NEW.accepted_at_utc IS NULL))
    OR ((NEW.workflow_state IN ('RESOLVED','CANCELLED_MISTAKE') AND (NEW.resolved_at_utc IS NULL OR NEW.outcome_code IS NULL OR NEW.outcome_reason IS NULL OR CHAR_LENGTH(TRIM(NEW.outcome_reason))=0)))
    OR ((NEW.current_lead_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_user r WHERE r.id=NEW.current_lead_id AND EXISTS (SELECT 1 FROM emergency_assistance_recipient er WHERE er.emergency_event_id=NEW.id AND er.recipient_user_id=r.id))))
    OR ((NEW.pending_successor_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_user r WHERE r.id=NEW.pending_successor_id AND EXISTS (SELECT 1 FROM emergency_assistance_recipient er WHERE er.emergency_event_id=NEW.id AND er.recipient_user_id=r.id))))
    OR ((NEW.workflow_state IN ('ACCEPTED','IN_PROGRESS') AND (NEW.response_status<>'ANSWERED' OR NEW.accepted_at_utc IS NULL OR NEW.current_lead_id IS NULL))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid emergency_event';
  END IF;
END$$

CREATE TRIGGER hh_nee_bu BEFORE UPDATE ON emergency_event
FOR EACH ROW BEGIN
  IF (NEW.lock_version < 0)
    OR ((NEW.requester_user_id IS NOT NULL AND (NEW.requester_user_id <= 0 OR NEW.requester_user_id > 9007199254740991)))
    OR ((NEW.current_lead_id IS NOT NULL AND (NEW.current_lead_id <= 0 OR NEW.current_lead_id > 9007199254740991)))
    OR ((NEW.pending_successor_id IS NOT NULL AND (NEW.pending_successor_id <= 0 OR NEW.pending_successor_id > 9007199254740991)))
    OR ((NEW.snapshot_hash IS NOT NULL AND NEW.snapshot_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'))
    OR (NEW.workflow_version NOT IN (0,1))
    OR (NEW.workflow_version<OLD.workflow_version)
    OR ((NEW.workflow_state IS NOT NULL AND NEW.workflow_state NOT IN ('REQUESTED','ACCEPTED','IN_PROGRESS','RESOLVED','CANCELLED_MISTAKE')))
    OR ((NEW.response_status IS NOT NULL AND NEW.response_status NOT IN ('UNANSWERED','ANSWERED')))
    OR ((NEW.workflow_version=1 AND (NEW.requester_user_id IS NULL OR NEW.requester_user_id<>NEW.triggered_by OR NEW.requested_at_utc IS NULL OR NEW.snapshot_hash IS NULL OR NEW.workflow_state IS NULL OR NEW.response_status IS NULL)))
    OR ((NEW.current_lead_id IS NOT NULL AND NEW.current_lead_id=NEW.requester_user_id))
    OR ((NEW.pending_successor_id IS NOT NULL AND NEW.pending_successor_id=NEW.requester_user_id))
    OR ((NEW.response_status='ANSWERED' AND NEW.accepted_at_utc IS NULL))
    OR ((NEW.workflow_state IN ('RESOLVED','CANCELLED_MISTAKE') AND (NEW.resolved_at_utc IS NULL OR NEW.outcome_code IS NULL OR NEW.outcome_reason IS NULL OR CHAR_LENGTH(TRIM(NEW.outcome_reason))=0)))
    OR ((NEW.current_lead_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_user r WHERE r.id=NEW.current_lead_id AND EXISTS (SELECT 1 FROM emergency_assistance_recipient er WHERE er.emergency_event_id=NEW.id AND er.recipient_user_id=r.id))))
    OR ((NEW.pending_successor_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_user r WHERE r.id=NEW.pending_successor_id AND EXISTS (SELECT 1 FROM emergency_assistance_recipient er WHERE er.emergency_event_id=NEW.id AND er.recipient_user_id=r.id))))
    OR ((OLD.workflow_version=1 AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.triggered_by <=> BINARY OLD.triggered_by) AND (BINARY NEW.latitude <=> BINARY OLD.latitude) AND (BINARY NEW.longitude <=> BINARY OLD.longitude) AND (BINARY NEW.location_text <=> BINARY OLD.location_text) AND (BINARY NEW.snapshot_json <=> BINARY OLD.snapshot_json) AND (BINARY NEW.notified_user_ids <=> BINARY OLD.notified_user_ids) AND (BINARY NEW.triggered_at <=> BINARY OLD.triggered_at) AND (BINARY NEW.requester_user_id <=> BINARY OLD.requester_user_id) AND (BINARY NEW.requested_at_utc <=> BINARY OLD.requested_at_utc) AND (BINARY NEW.snapshot_hash <=> BINARY OLD.snapshot_hash))))
    OR ((OLD.response_status='ANSWERED' AND NEW.response_status<>'ANSWERED'))
    OR ((OLD.workflow_state IN ('RESOLVED','CANCELLED_MISTAKE') AND NOT ((BINARY NEW.id <=> BINARY OLD.id) AND (BINARY NEW.patient_id <=> BINARY OLD.patient_id) AND (BINARY NEW.triggered_by <=> BINARY OLD.triggered_by) AND (BINARY NEW.latitude <=> BINARY OLD.latitude) AND (BINARY NEW.longitude <=> BINARY OLD.longitude) AND (BINARY NEW.location_text <=> BINARY OLD.location_text) AND (BINARY NEW.snapshot_json <=> BINARY OLD.snapshot_json) AND (BINARY NEW.status <=> BINARY OLD.status) AND (BINARY NEW.notified_user_ids <=> BINARY OLD.notified_user_ids) AND (BINARY NEW.triggered_at <=> BINARY OLD.triggered_at) AND (BINARY NEW.resolved_at <=> BINARY OLD.resolved_at) AND (BINARY NEW.workflow_version <=> BINARY OLD.workflow_version) AND (BINARY NEW.lock_version <=> BINARY OLD.lock_version) AND (BINARY NEW.workflow_state <=> BINARY OLD.workflow_state) AND (BINARY NEW.response_status <=> BINARY OLD.response_status) AND (BINARY NEW.requester_user_id <=> BINARY OLD.requester_user_id) AND (BINARY NEW.current_lead_id <=> BINARY OLD.current_lead_id) AND (BINARY NEW.pending_successor_id <=> BINARY OLD.pending_successor_id) AND (BINARY NEW.requested_at_utc <=> BINARY OLD.requested_at_utc) AND (BINARY NEW.accepted_at_utc <=> BINARY OLD.accepted_at_utc) AND (BINARY NEW.last_progress_at_utc <=> BINARY OLD.last_progress_at_utc) AND (BINARY NEW.resolved_at_utc <=> BINARY OLD.resolved_at_utc) AND (BINARY NEW.snapshot_hash <=> BINARY OLD.snapshot_hash) AND (BINARY NEW.outcome_code <=> BINARY OLD.outcome_code) AND (BINARY NEW.outcome_reason <=> BINARY OLD.outcome_reason))))
    OR ((NEW.lock_version<OLD.lock_version OR NEW.lock_version>OLD.lock_version+1))
    OR ((NEW.workflow_state IN ('ACCEPTED','IN_PROGRESS') AND (NEW.response_status<>'ANSWERED' OR NEW.accepted_at_utc IS NULL OR NEW.current_lead_id IS NULL)))
    OR ((OLD.accepted_at_utc IS NOT NULL AND NOT (NEW.accepted_at_utc <=> OLD.accepted_at_utc)))
    OR ((OLD.workflow_version=1 AND NOT (NEW.workflow_state=OLD.workflow_state OR (OLD.workflow_state='REQUESTED' AND NEW.workflow_state IN ('ACCEPTED','RESOLVED','CANCELLED_MISTAKE')) OR (OLD.workflow_state='ACCEPTED' AND NEW.workflow_state IN ('IN_PROGRESS','RESOLVED','CANCELLED_MISTAKE')) OR (OLD.workflow_state='IN_PROGRESS' AND NEW.workflow_state IN ('RESOLVED','CANCELLED_MISTAKE'))))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH invalid emergency_event';
  END IF;
END$$

CREATE TRIGGER hh_nee_bd BEFORE DELETE ON emergency_event
FOR EACH ROW BEGIN
  IF (OLD.workflow_version=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HH tracked native history cannot be deleted';
  END IF;
END$$

DELIMITER ;

SET SESSION sql_mode=@hh_saved_sql_mode;

-- VISIT_QUESTION_STORAGE_20261015
-- 就诊问题跟踪：仅新增三个独立表，不修改既有问题或家庭协作 B 对象。
-- 目标原生 MySQL 5.7.18；功能默认关闭，不执行历史回填，不自动启用。
-- 先停写、核对库名与权限、完成全库及附件备份，并在隔离库验证恢复。
-- 现有库按 01 只读预检 → 02 仅 ABSENT 时升级 → 03 只读后检；全新空库单独使用 init.sql。
-- DDL 隐式提交；任一步失败立即停止，禁止 --force、盲重跑、删除重建或用 init.sql 修补。
-- 执行连接须使用 utf8mb4/utf8mb4_bin，严格 SQL 模式须与 B 守卫一致；本文件不会修改连接或服务器设置。
-- 仅源码交付。原生数据库执行、驱动收发和并发验证仍为 NOT_RUN。
-- 以下仅为新库初始化追加段；不要在现有库执行。

CREATE TABLE visit_question_state (
  question_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  state VARCHAR(20) NOT NULL,
  lock_version BIGINT NOT NULL,
  current_title VARCHAR(200) NOT NULL,
  current_description MEDIUMTEXT NULL,
  current_recorded_answer MEDIUMTEXT NULL,
  current_follow_up MEDIUMTEXT NULL,
  ordinary_answer_origin VARCHAR(24) NULL,
  ordinary_answer_recorder_id BIGINT NULL,
  ordinary_answer_recorded_at DATETIME(6) NULL,
  legacy_appointment_id BIGINT NULL,
  source_observed_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_observed_at DATETIME(6) NOT NULL,
  observed_b_assistance_id BIGINT NULL,
  context_type VARCHAR(24) NULL,
  context_id BIGINT NULL,
  context_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  responsible_user_id BIGINT NULL,
  follow_up_due_date DATE NULL,
  current_answer_event_id BIGINT NULL,
  close_reason VARCHAR(40) NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (question_id),
  KEY vq_state_patient_ix (patient_id,question_id),
  KEY vq_state_status_ix (patient_id,state,question_id),
  KEY vq_state_context_ix (patient_id,context_type,context_id,question_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE visit_question_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  from_state VARCHAR(20) NULL,
  to_state VARCHAR(20) NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_kind VARCHAR(24) NOT NULL,
  answer_origin VARCHAR(24) NULL,
  reason_code VARCHAR(40) NULL,
  body_json MEDIUMTEXT NOT NULL,
  recorded_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY vq_event_version_uq (question_id,version),
  KEY vq_event_page_ix (patient_id,question_id,id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE visit_question_command (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_id BIGINT NOT NULL,
  command_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  question_id BIGINT NULL,
  expected_version BIGINT NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_json LONGTEXT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY vq_command_key_uq (actor_id,command_key),
  KEY vq_command_question_ix (question_id,id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DELIMITER $$

CREATE TRIGGER vq_state_bi BEFORE INSERT ON visit_question_state
FOR EACH ROW BEGIN
  IF COALESCE((NEW.question_id BETWEEN 1 AND 9007199254740991
      AND NEW.patient_id BETWEEN 1 AND 9007199254740991
      AND NEW.lock_version BETWEEN 1 AND 9007199254740991
      AND NEW.created_by BETWEEN 1 AND 9007199254740991
      AND BINARY NEW.state IN ('OPEN','UNRESOLVED','ANSWERED','FOLLOW_UP','CLOSED')
      AND CHAR_LENGTH(NEW.current_title) BETWEEN 1 AND 200
      AND OCTET_LENGTH(NEW.current_title)+COALESCE(OCTET_LENGTH(NEW.current_description),0)+COALESCE(OCTET_LENGTH(NEW.current_recorded_answer),0)+COALESCE(OCTET_LENGTH(NEW.current_follow_up),0)<=131072
      AND BINARY NEW.source_observed_hash REGEXP '^[0-9a-f]{64}$'
      AND EXISTS(SELECT 1 FROM care_item c WHERE c.id=NEW.question_id AND c.patient_id=NEW.patient_id AND BINARY c.kind='QUESTION')
      AND ((NEW.ordinary_answer_origin IS NULL AND NEW.ordinary_answer_recorder_id IS NULL AND NEW.ordinary_answer_recorded_at IS NULL AND NEW.current_recorded_answer IS NULL) OR (BINARY NEW.ordinary_answer_origin='LEGACY_UNVERIFIED' AND NEW.ordinary_answer_recorder_id IS NULL AND NEW.ordinary_answer_recorded_at IS NULL AND NEW.current_recorded_answer IS NOT NULL) OR (BINARY NEW.ordinary_answer_origin='USER_RECORDED' AND NEW.ordinary_answer_recorder_id BETWEEN 1 AND 9007199254740991 AND NEW.ordinary_answer_recorded_at IS NOT NULL AND CHAR_LENGTH(TRIM(NEW.current_recorded_answer))>0))
      AND ((NEW.context_type IS NULL AND NEW.context_id IS NULL AND NEW.context_fingerprint IS NULL) OR (BINARY NEW.context_type IN ('LEGACY_APPOINTMENT','CARE_APPOINTMENT','VISIT_RECORD') AND NEW.context_id BETWEEN 1 AND 9007199254740991 AND BINARY NEW.context_fingerprint REGEXP '^[0-9a-f]{64}$'))
      AND (NEW.legacy_appointment_id IS NULL OR EXISTS(SELECT 1 FROM care_item a WHERE a.id=NEW.legacy_appointment_id AND a.patient_id=NEW.patient_id AND BINARY a.kind='APPOINTMENT'))
      AND (NEW.responsible_user_id IS NULL OR NEW.responsible_user_id BETWEEN 1 AND 9007199254740991)
      AND (NEW.observed_b_assistance_id IS NULL OR EXISTS(SELECT 1 FROM family_assistance b WHERE b.id=NEW.observed_b_assistance_id AND b.patient_id=NEW.patient_id AND b.care_item_id=NEW.question_id AND b.care_appointment_id IS NULL))
      AND (NEW.current_answer_event_id IS NULL OR EXISTS(SELECT 1 FROM visit_question_event e WHERE e.id=NEW.current_answer_event_id AND e.question_id=NEW.question_id AND e.patient_id=NEW.patient_id AND e.version<=NEW.lock_version AND BINARY e.event_type='PUBLISH_ANSWER' AND BINARY e.answer_origin='CLINICIAN_AUTHORED'))
      AND ((BINARY NEW.state='CLOSED' AND BINARY NEW.close_reason IN ('ANSWER_SUFFICIENT','FOLLOW_UP_FINISHED','NO_LONGER_NEEDED','DUPLICATE','WITHDRAWN','OTHER')) OR (BINARY NEW.state<>'CLOSED' AND NEW.close_reason IS NULL))
      AND (BINARY NEW.state<>'FOLLOW_UP' OR (CHAR_LENGTH(TRIM(NEW.current_follow_up))>0 AND NEW.follow_up_due_date IS NOT NULL))
      AND NEW.updated_at>=NEW.created_at AND NEW.lock_version=1 AND NEW.current_answer_event_id IS NULL),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_state';
  END IF;
END$$

CREATE TRIGGER vq_state_bu BEFORE UPDATE ON visit_question_state
FOR EACH ROW BEGIN
  IF COALESCE((NEW.question_id BETWEEN 1 AND 9007199254740991
      AND NEW.patient_id BETWEEN 1 AND 9007199254740991
      AND NEW.lock_version BETWEEN 1 AND 9007199254740991
      AND NEW.created_by BETWEEN 1 AND 9007199254740991
      AND BINARY NEW.state IN ('OPEN','UNRESOLVED','ANSWERED','FOLLOW_UP','CLOSED')
      AND CHAR_LENGTH(NEW.current_title) BETWEEN 1 AND 200
      AND OCTET_LENGTH(NEW.current_title)+COALESCE(OCTET_LENGTH(NEW.current_description),0)+COALESCE(OCTET_LENGTH(NEW.current_recorded_answer),0)+COALESCE(OCTET_LENGTH(NEW.current_follow_up),0)<=131072
      AND BINARY NEW.source_observed_hash REGEXP '^[0-9a-f]{64}$'
      AND EXISTS(SELECT 1 FROM care_item c WHERE c.id=NEW.question_id AND c.patient_id=NEW.patient_id AND BINARY c.kind='QUESTION')
      AND ((NEW.ordinary_answer_origin IS NULL AND NEW.ordinary_answer_recorder_id IS NULL AND NEW.ordinary_answer_recorded_at IS NULL AND NEW.current_recorded_answer IS NULL) OR (BINARY NEW.ordinary_answer_origin='LEGACY_UNVERIFIED' AND NEW.ordinary_answer_recorder_id IS NULL AND NEW.ordinary_answer_recorded_at IS NULL AND NEW.current_recorded_answer IS NOT NULL) OR (BINARY NEW.ordinary_answer_origin='USER_RECORDED' AND NEW.ordinary_answer_recorder_id BETWEEN 1 AND 9007199254740991 AND NEW.ordinary_answer_recorded_at IS NOT NULL AND CHAR_LENGTH(TRIM(NEW.current_recorded_answer))>0))
      AND ((NEW.context_type IS NULL AND NEW.context_id IS NULL AND NEW.context_fingerprint IS NULL) OR (BINARY NEW.context_type IN ('LEGACY_APPOINTMENT','CARE_APPOINTMENT','VISIT_RECORD') AND NEW.context_id BETWEEN 1 AND 9007199254740991 AND BINARY NEW.context_fingerprint REGEXP '^[0-9a-f]{64}$'))
      AND (NEW.legacy_appointment_id IS NULL OR EXISTS(SELECT 1 FROM care_item a WHERE a.id=NEW.legacy_appointment_id AND a.patient_id=NEW.patient_id AND BINARY a.kind='APPOINTMENT'))
      AND (NEW.responsible_user_id IS NULL OR NEW.responsible_user_id BETWEEN 1 AND 9007199254740991)
      AND (NEW.observed_b_assistance_id IS NULL OR EXISTS(SELECT 1 FROM family_assistance b WHERE b.id=NEW.observed_b_assistance_id AND b.patient_id=NEW.patient_id AND b.care_item_id=NEW.question_id AND b.care_appointment_id IS NULL))
      AND (NEW.current_answer_event_id IS NULL OR EXISTS(SELECT 1 FROM visit_question_event e WHERE e.id=NEW.current_answer_event_id AND e.question_id=NEW.question_id AND e.patient_id=NEW.patient_id AND e.version<=NEW.lock_version AND BINARY e.event_type='PUBLISH_ANSWER' AND BINARY e.answer_origin='CLINICIAN_AUTHORED'))
      AND ((BINARY NEW.state='CLOSED' AND BINARY NEW.close_reason IN ('ANSWER_SUFFICIENT','FOLLOW_UP_FINISHED','NO_LONGER_NEEDED','DUPLICATE','WITHDRAWN','OTHER')) OR (BINARY NEW.state<>'CLOSED' AND NEW.close_reason IS NULL))
      AND (BINARY NEW.state<>'FOLLOW_UP' OR (CHAR_LENGTH(TRIM(NEW.current_follow_up))>0 AND NEW.follow_up_due_date IS NOT NULL))
      AND NEW.updated_at>=NEW.created_at
      AND (NEW.question_id <=> OLD.question_id) AND (NEW.patient_id <=> OLD.patient_id) AND (NEW.created_by <=> OLD.created_by) AND (NEW.created_at <=> OLD.created_at)
      AND ((NEW.lock_version=OLD.lock_version+1 AND OLD.lock_version<9007199254740991 AND EXISTS(SELECT 1 FROM visit_question_event e WHERE e.question_id=OLD.question_id AND e.patient_id=OLD.patient_id AND e.version=OLD.lock_version AND BINARY e.to_state=OLD.state))
        OR (NEW.lock_version=OLD.lock_version AND NOT (NEW.current_answer_event_id <=> OLD.current_answer_event_id) AND (NEW.question_id <=> OLD.question_id) AND (NEW.patient_id <=> OLD.patient_id) AND (BINARY NEW.state <=> BINARY OLD.state) AND (NEW.lock_version <=> OLD.lock_version) AND (BINARY NEW.current_title <=> BINARY OLD.current_title) AND (BINARY NEW.current_description <=> BINARY OLD.current_description) AND (BINARY NEW.current_recorded_answer <=> BINARY OLD.current_recorded_answer) AND (BINARY NEW.current_follow_up <=> BINARY OLD.current_follow_up) AND (BINARY NEW.ordinary_answer_origin <=> BINARY OLD.ordinary_answer_origin) AND (NEW.ordinary_answer_recorder_id <=> OLD.ordinary_answer_recorder_id) AND (NEW.ordinary_answer_recorded_at <=> OLD.ordinary_answer_recorded_at) AND (NEW.legacy_appointment_id <=> OLD.legacy_appointment_id) AND (BINARY NEW.source_observed_hash <=> BINARY OLD.source_observed_hash) AND (NEW.source_observed_at <=> OLD.source_observed_at) AND (NEW.observed_b_assistance_id <=> OLD.observed_b_assistance_id) AND (BINARY NEW.context_type <=> BINARY OLD.context_type) AND (NEW.context_id <=> OLD.context_id) AND (BINARY NEW.context_fingerprint <=> BINARY OLD.context_fingerprint) AND (NEW.responsible_user_id <=> OLD.responsible_user_id) AND (NEW.follow_up_due_date <=> OLD.follow_up_due_date) AND (BINARY NEW.close_reason <=> BINARY OLD.close_reason) AND (NEW.created_by <=> OLD.created_by) AND (NEW.created_at <=> OLD.created_at) AND (NEW.updated_at <=> OLD.updated_at) AND EXISTS(SELECT 1 FROM visit_question_event e WHERE e.id=NEW.current_answer_event_id AND e.question_id=NEW.question_id AND e.patient_id=NEW.patient_id AND e.version=NEW.lock_version AND BINARY e.to_state=NEW.state AND BINARY e.event_type='PUBLISH_ANSWER')))),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_state';
  END IF;
END$$

CREATE TRIGGER vq_state_bd BEFORE DELETE ON visit_question_state
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ history is immutable';
END$$

CREATE TRIGGER vq_event_bi BEFORE INSERT ON visit_question_event
FOR EACH ROW BEGIN
  IF JSON_VALID(NEW.body_json)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid event JSON';
  END IF;
  IF COALESCE((NEW.question_id BETWEEN 1 AND 9007199254740991
      AND NEW.patient_id BETWEEN 1 AND 9007199254740991
      AND NEW.version BETWEEN 1 AND 9007199254740991
      AND NEW.actor_id BETWEEN 1 AND 9007199254740991
      AND BINARY NEW.event_type IN ('CREATE','ADOPT','EDIT','RECORD_ANSWER','PUBLISH_ANSWER','MARK_UNRESOLVED','SET_FOLLOW_UP','CLOSE','REOPEN')
      AND BINARY NEW.to_state IN ('OPEN','UNRESOLVED','ANSWERED','FOLLOW_UP','CLOSED')
      AND BINARY NEW.actor_kind IN ('RECORD_OWNER','AUTHORIZED_FAMILY','AUTHORIZED_DOCTOR','AUTHORIZED_NURSE','OTHER_AUTHORIZED')
      AND EXISTS(SELECT 1 FROM visit_question_state s WHERE s.question_id=NEW.question_id AND s.patient_id=NEW.patient_id AND s.lock_version=NEW.version AND BINARY s.state=NEW.to_state)
      AND ((NEW.version=1 AND NEW.from_state IS NULL AND NOT EXISTS(SELECT 1 FROM visit_question_event e WHERE e.question_id=NEW.question_id)) OR (NEW.version>1 AND EXISTS(SELECT 1 FROM visit_question_event e WHERE e.question_id=NEW.question_id AND e.patient_id=NEW.patient_id AND e.version=NEW.version-1 AND BINARY e.to_state=NEW.from_state) AND NOT EXISTS(SELECT 1 FROM visit_question_event e WHERE e.question_id=NEW.question_id AND e.version>=NEW.version)))
      AND JSON_TYPE(NEW.body_json)='OBJECT'
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.schemaVersion'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.schemaVersion')=1
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary'))='OBJECT'
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.schemaVersion'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.ordinary.schemaVersion')=1
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.patientId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.ordinary.patientId')=NEW.patient_id
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.expectedVersion'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.ordinary.expectedVersion')=NEW.version-1
      AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.operation'))=NEW.event_type
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body'))='OBJECT'
      AND JSON_LENGTH(JSON_EXTRACT(NEW.body_json,'$.ordinary'))=6
      AND ((BINARY NEW.event_type='CREATE' AND NEW.version=1 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.questionId'))='NULL') OR (BINARY NEW.event_type<>'CREATE' AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.questionId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.ordinary.questionId')=NEW.question_id))
      AND OCTET_LENGTH(JSON_EXTRACT(NEW.body_json,'$.ordinary'))<=65536
      AND ((JSON_CONTAINS_PATH(NEW.body_json,'one','$.baseline')=0 AND JSON_LENGTH(NEW.body_json)=2 AND OCTET_LENGTH(NEW.body_json)<=65536) OR (NEW.version=1 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline'))='OBJECT' AND JSON_LENGTH(NEW.body_json)=3 AND OCTET_LENGTH(JSON_EXTRACT(NEW.body_json,'$.baseline'))<=917504 AND OCTET_LENGTH(NEW.body_json)<=1048576))
      AND ((BINARY NEW.event_type='PUBLISH_ANSWER' AND BINARY NEW.answer_origin='CLINICIAN_AUTHORED' AND BINARY NEW.actor_kind='AUTHORIZED_DOCTOR') OR (BINARY NEW.event_type='RECORD_ANSWER' AND BINARY NEW.answer_origin='USER_RECORDED') OR (BINARY NEW.event_type NOT IN ('PUBLISH_ANSWER','RECORD_ANSWER') AND (NEW.answer_origin IS NULL OR (NEW.version=1 AND BINARY NEW.answer_origin='LEGACY_UNVERIFIED'))))
      AND (BINARY NEW.event_type NOT IN ('PUBLISH_ANSWER','RECORD_ANSWER') OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.answer'))='STRING' AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.answer')))) BETWEEN 1 AND 4000))
      AND ((BINARY NEW.event_type='MARK_UNRESOLVED' AND BINARY NEW.reason_code IN ('NOT_ASKED','NOT_ANSWERED','MORE_INFORMATION_NEEDED','VISIT_CANCELLED','OTHER')) OR (BINARY NEW.event_type='CLOSE' AND BINARY NEW.reason_code IN ('ANSWER_SUFFICIENT','FOLLOW_UP_FINISHED','NO_LONGER_NEEDED','DUPLICATE','WITHDRAWN','OTHER')) OR (BINARY NEW.event_type='REOPEN' AND BINARY NEW.reason_code IN ('NEW_INFORMATION','ANSWER_UNCLEAR','FOLLOW_UP_NOT_DONE','CONTEXT_CHANGED','OTHER')) OR (BINARY NEW.event_type NOT IN ('MARK_UNRESOLVED','CLOSE','REOPEN') AND NEW.reason_code IS NULL))
      AND (NEW.reason_code IS NULL OR BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.reason'))=NEW.reason_code)
      AND (NEW.reason_code IS NULL OR BINARY NEW.reason_code<>'OTHER' OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.reasonNote'))='STRING' AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.reasonNote')))) BETWEEN 1 AND 1000))
      AND (NEW.reason_code IS NULL OR BINARY NEW.reason_code<>'FOLLOW_UP_FINISHED' OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.resultText'))='STRING' AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.resultText')))) BETWEEN 1 AND 4000) OR JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.resultReference'))='OBJECT')
      AND (NEW.version<>1 OR BINARY NEW.event_type='CREATE' OR JSON_CONTAINS_PATH(NEW.body_json,'one','$.baseline')=1)
      AND (JSON_CONTAINS_PATH(NEW.body_json,'one','$.baseline')=0 OR (JSON_LENGTH(JSON_EXTRACT(NEW.body_json,'$.baseline'))=16 AND JSON_EXTRACT(NEW.body_json,'$.baseline.id')=NEW.question_id AND JSON_EXTRACT(NEW.body_json,'$.baseline.patientId')=NEW.patient_id AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.kind'))='QUESTION' AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.id'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.id') BETWEEN 1 AND 9007199254740991 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.patientId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.patientId') BETWEEN 1 AND 9007199254740991 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.userId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.userId') BETWEEN 1 AND 9007199254740991 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.kind'))='STRING' AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.title'))='STRING' AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.status'))='STRING' AND (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId'))='NULL' OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId') BETWEEN 1 AND 9007199254740991)) AND (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorId'))='NULL' OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.actorId') BETWEEN 1 AND 9007199254740991)) AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorName')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.eventAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifyAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifiedAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.escalatedAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.dataJson')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.createdAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.updatedAt')) IN ('STRING','NULL') AND CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.id'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.id'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.patientId'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.patientId'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.userId'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.userId'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.kind'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.kind'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.title'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.title'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.status'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.status'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorId'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorId'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorName'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorName'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.eventAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.eventAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifyAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifyAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifiedAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifiedAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.escalatedAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.escalatedAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.dataJson'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.dataJson'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.createdAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.createdAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.updatedAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.updatedAt'))) END<=131072))),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_event';
  END IF;
END$$

CREATE TRIGGER vq_event_bu BEFORE UPDATE ON visit_question_event
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ history is immutable';
END$$

CREATE TRIGGER vq_event_bd BEFORE DELETE ON visit_question_event
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ history is immutable';
END$$

CREATE TRIGGER vq_command_bi BEFORE INSERT ON visit_question_command
FOR EACH ROW BEGIN
  IF COALESCE((NEW.actor_id BETWEEN 1 AND 9007199254740991
      AND (NEW.question_id IS NULL OR NEW.question_id BETWEEN 1 AND 9007199254740991)
      AND BINARY NEW.command_key REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      AND NEW.expected_version BETWEEN 0 AND 9007199254740990
      AND BINARY NEW.payload_hash REGEXP '^[0-9a-f]{64}$'
      AND (NEW.question_id IS NOT NULL OR NEW.expected_version=0) AND NEW.result_json IS NULL),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_command';
  END IF;
END$$

CREATE TRIGGER vq_command_bu BEFORE UPDATE ON visit_question_command
FOR EACH ROW BEGIN
  IF NEW.result_json IS NULL OR JSON_VALID(NEW.result_json)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ incomplete command receipt';
  END IF;
  IF COALESCE((NEW.actor_id BETWEEN 1 AND 9007199254740991
      AND (NEW.question_id IS NULL OR NEW.question_id BETWEEN 1 AND 9007199254740991)
      AND BINARY NEW.command_key REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      AND NEW.expected_version BETWEEN 0 AND 9007199254740990
      AND BINARY NEW.payload_hash REGEXP '^[0-9a-f]{64}$'
      AND (NEW.question_id IS NOT NULL OR NEW.expected_version=0)
      AND (NEW.id <=> OLD.id) AND (NEW.actor_id <=> OLD.actor_id) AND (BINARY NEW.command_key <=> BINARY OLD.command_key) AND (NEW.expected_version <=> OLD.expected_version) AND (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash) AND (NEW.created_at <=> OLD.created_at)
      AND OLD.result_json IS NULL AND NEW.result_json IS NOT NULL
      AND (OLD.question_id IS NULL OR NEW.question_id=OLD.question_id)
      AND NEW.question_id BETWEEN 1 AND 9007199254740991
      AND JSON_TYPE(NEW.result_json)='OBJECT' AND JSON_LENGTH(NEW.result_json)=4 AND OCTET_LENGTH(NEW.result_json)<=512
      AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.questionId'))='INTEGER' AND JSON_EXTRACT(NEW.result_json,'$.questionId') BETWEEN 1 AND 9007199254740991
      AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.version'))='INTEGER' AND JSON_EXTRACT(NEW.result_json,'$.version') BETWEEN 1 AND 9007199254740991
      AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.eventId'))='INTEGER' AND JSON_EXTRACT(NEW.result_json,'$.eventId') BETWEEN 1 AND 9007199254740991
      AND JSON_EXTRACT(NEW.result_json,'$.questionId')=NEW.question_id
      AND JSON_EXTRACT(NEW.result_json,'$.version')=NEW.expected_version+1
      AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.state'))='STRING' AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.state')) IN ('OPEN','UNRESOLVED','ANSWERED','FOLLOW_UP','CLOSED')
      AND EXISTS(SELECT 1 FROM visit_question_state s JOIN visit_question_event e ON e.question_id=s.question_id AND e.patient_id=s.patient_id AND e.version=s.lock_version AND BINARY e.to_state=s.state WHERE s.question_id=NEW.question_id AND e.id=JSON_EXTRACT(NEW.result_json,'$.eventId') AND s.lock_version=JSON_EXTRACT(NEW.result_json,'$.version') AND BINARY s.state=JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.state')) AND e.actor_id=NEW.actor_id)),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_command';
  END IF;
END$$

CREATE TRIGGER vq_command_bd BEFORE DELETE ON visit_question_command
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ history is immutable';
END$$

DELIMITER ;
