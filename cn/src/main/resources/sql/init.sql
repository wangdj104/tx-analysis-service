-- 完整系统初始化：MySQL 5.7 / 8.0，使用前先选择一个新建空数据库。
-- 本文件包含全部表、索引、外键及基础角色、菜单、字典和品牌配置；只需执行本文件。
-- 不创建默认账号、患者、患者授权或演示业务数据。演示数据另见 demo/sql/demo-data.sql。
-- 仅用于空库初始化；CREATE TABLE IF NOT EXISTS 不会升级已有表，禁止作为旧库升级脚本使用。
-- 重复执行保留业务数据并补回缺失种子；更新初始角色、菜单的名称与配置，额外绑定保留。
-- 建表期间临时关闭并恢复外键检查，以定义计划与版本的循环引用；种子写入时外键检查已恢复。
-- DATETIME(6) 协作字段由应用显式写入/读取 UTC；MySQL 8.0.16+ 强制 CHECK，5.7 由应用校验。
-- DDL 隐式提交；发生错误请停止并检查已创建结构，不要使用 mysql --force。

SET NAMES utf8mb4;

SET @init_foreign_key_checks = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

-- 一、系统结构（79 张表）

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
  KEY `idx_alert_user_patient` (`user_id`,`patient_id`),
  KEY `idx_alert_patient_status_time` (`patient_id`,`status`,`triggered_at`),
  KEY `idx_alert_triggered_at` (`triggered_at`),
  KEY `idx_alert_dedupe` (`patient_id`,`dedupe_key`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='alertrecord';

CREATE TABLE IF NOT EXISTS `alert_event` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `alert_id` BIGINT NOT NULL,
  `patient_id` BIGINT NOT NULL,
  `actor_id` BIGINT DEFAULT NULL,
  `actor_name` VARCHAR(100) DEFAULT NULL,
  `from_status` VARCHAR(20) DEFAULT NULL,
  `to_status` VARCHAR(20) NOT NULL,
  `note` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_alert_event_alert` (`alert_id`,`created_at`),
  KEY `idx_alert_event_patient` (`patient_id`,`created_at`)
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

SET FOREIGN_KEY_CHECKS = @init_foreign_key_checks;

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
(40,0,'照护旅程','care-journey','/care-journey','FirstAidKit','care:journey:view',1,2,1),
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
