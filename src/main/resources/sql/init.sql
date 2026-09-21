-- ============================================================
-- healthdatamanagementsystem - allamountinitializescriptthis
-- useroute: firsttimesdeploytimeonekeyCreatehas tablestructureandinitializedata
-- runmethod: mysql -u<user> -p<pass> <database> < init.sql
-- ============================================================

-- ============================================================
-- one, systemBasictable
-- ============================================================

-- 1. systemusertable
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

-- 2. systemRoletable
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

-- 3. systemMenu/Permissiontable
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

-- ============================================================
-- two, Patient Managementtable
-- ============================================================

-- 4. Patientinformationtable
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

-- ============================================================
-- three, Dialysis Managementmodule
-- ============================================================

-- 5. Dialysis Recordstable
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

-- 6. Dry WeightMonthlevelrecordtable
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

-- ============================================================
-- four, Medical Recordsmodule
-- ============================================================

-- 7. Medical Recordsmaintable
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

-- 8. Examination item detailstable
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

-- 9. imagingReportAttachmenttable
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

-- ============================================================
-- five, Medicationmanagementmodule
-- ============================================================

-- 10. Medicationinformationtable
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

-- 11. medicationrecordtable
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

-- ============================================================
-- six, AI analysismodule
-- ============================================================

-- 12. AI Dialysisanalysisrecordtable
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

-- ============================================================
-- seven, extendtable (Patientclinical, indicator dictionary, Medication Reminders, userslightlygood)
-- ============================================================

-- 13. Patientclinical detailsinformationtable
CREATE TABLE IF NOT EXISTS `patient_clinical` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `patient_id` BIGINT NOT NULL COMMENT 'related patient.id',
  `user_id` BIGINT NOT NULL COMMENT 'belongAccount',
  `dialysis_type` VARCHAR(20) DEFAULT NULL COMMENT 'HDperitoneal dialysis/PDperitoneal dialysis/CRRTetc.',
  `dialysis_start_date` DATE DEFAULT NULL COMMENT 'startDialysis Date',
  `vascular_access` VARCHAR(64) DEFAULT NULL COMMENT 'vascular access',
  `primary_diagnosis` VARCHAR(255) DEFAULT NULL COMMENT 'originalonset/primary diagnosis',
  `allergy_drugs` TEXT COMMENT 'Medicationallergy history(JSON or text)',
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

-- 14. healthlaboratory test dictionarytable
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

-- 15. Medication Remindersplantable
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

-- 16. userinterface preferencestable
CREATE TABLE IF NOT EXISTS `user_preference` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'primary keyID',
  `user_id` BIGINT NOT NULL COMMENT 'userID',
  `pref_key` VARCHAR(128) NOT NULL COMMENT 'for example  table:medical-record-list',
  `pref_value` TEXT NOT NULL COMMENT 'JSON',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated At',
  UNIQUE KEY `uk_user_key` (`user_id`, `pref_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='userinterface preferences';

-- ============================================================
-- eight, RBAC related table (dependency sys_user/sys_role/sys_menu)
-- ============================================================

-- 17. userRolerelated table
CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` BIGINT NOT NULL COMMENT 'userID',
  `role_id` BIGINT NOT NULL COMMENT 'RoleID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='userRolerelated table';

-- 18. RoleMenurelated table
CREATE TABLE IF NOT EXISTS `sys_role_menu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `role_id` BIGINT NOT NULL COMMENT 'RoleID',
  `menu_id` BIGINT NOT NULL COMMENT 'MenuID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RoleMenurelated table';

-- ============================================================
-- nine, healthmonitoring and Family Caretable
-- ============================================================

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
  UNIQUE KEY `uk_dialysis_schedule` (`patient_id`,`schedule_date`),
  KEY `idx_schedule_patient_date` (`patient_id`,`schedule_date`,`status`),
  KEY `idx_schedule_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Dialysis Schedule';

CREATE TABLE IF NOT EXISTS `care_member` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `patient_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `relation_name` VARCHAR(60) DEFAULT NULL,
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

-- ============================================================
-- 10. Baseline data (does not create a default account)
-- ============================================================

-- 10.1 System roles
INSERT INTO `sys_role` (`role_code`, `role_name`, `description`, `status`) VALUES
('admin', 'Administrator', 'System administrator with full permissions', 1),
('user', 'Standard User', 'Standard application user', 1)
ON DUPLICATE KEY UPDATE `role_code` = `role_code`;

-- 10.2 Navigation: top-level domains, task entries, and stable menu codes
INSERT INTO `sys_menu` (`id`,`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`) VALUES
(28,0,'Workspace','workspace','/monitoring','Monitor','monitoring:view',1,0,1),
(30,0,'Patient Center','patient-center','','UserFilled','patient:view',1,1,1),
(8,30,'Patient Profiles','patient-profile','/system/patient','Avatar','patient:manage',1,1,1),
(31,30,'Care Plan','patient-care','/family-health','Calendar','family-health:view',1,2,1),
(1,0,'Dialysis Management','dialysis','/dialysis','Histogram','dialysis:view',1,2,1),
(11,1,'Dialysis Records','dialysis-record','/dialysis?tab=data','Document','dialysis:record:view',1,1,1),
(12,1,'Trend Analysis','dialysis-trend','/dialysis?tab=analysis','TrendCharts','dialysis:trend:view',1,2,1),
(13,1,'Dry Weight Management','dialysis-dry-weight','/dry-weight','ScaleToOriginal','dry-weight:view',1,3,1),
(14,1,'AI Health Analytics','dialysis-ai','/dialysis?tab=ai','Cpu','dialysis:ai:view',1,4,1),
(15,1,'Dialysis Schedule','dialysis-schedule','/family-health?tab=schedule','Calendar','dialysis:schedule:view',1,5,1),
(2,0,'Medical Records','clinical-record','/medical-record','FolderOpened','medical:view',1,3,1),
(16,2,'Record List','clinical-record-list','/medical-record?tab=list','FolderOpened','medical:list:view',1,1,1),
(17,2,'Upload Report','clinical-record-upload','/medical-record?tab=upload','Camera','medical:upload',1,2,1),
(18,2,'Abnormal Results','clinical-abnormal','/medical-record?tab=abnormal','WarningFilled','medical:abnormal:view',1,3,1),
(19,2,'Result Trends','clinical-trend','/medical-record?tab=trend','DataLine','medical:trend:view',1,4,1),
(3,0,'Medication Management','medication','/medication','Goods','medication:view',1,4,1),
(32,3,'Medication List','medication-catalog','/medication?tab=drugs','Box','medication:catalog:view',1,1,1),
(33,3,'Medication Log','medication-log','/medication?tab=logs','Notebook','medication:log:view',1,2,1),
(34,3,'Medication Reminders','medication-reminder','/medication?tab=remind','Bell','medication:reminder:view',1,3,1),
(26,0,'Health Monitoring','health-monitoring','','Odometer','health-monitoring:view',1,5,1),
(27,26,'Blood Pressure & Glucose','health-vitals','/bp-self-monitor','Odometer','bp-self-monitor:view',1,1,1),
(20,26,'Complication Tracking','health-complication','/health-analysis?tab=complication','Warning','complication:view',1,2,1),
(21,26,'Health Alerts','health-alert','/health-analysis?tab=alert','Bell','alert:view',1,3,1),
(23,26,'Nutrition Diary','health-nutrition','/health-analysis?tab=nutrition','Apple','nutrition-diary:view',1,4,1),
(35,26,'Nutrition Assessment','health-nutrition-assessment','/health-analysis?tab=nutrition-assessment','DataAnalysis','nutrition-assessment:view',1,5,1),
(29,0,'Analytics & Reports','analysis-report','','DataAnalysis','analysis-report:view',1,6,1),
(22,29,'Blood Pressure Pattern Analysis','analysis-bp-pattern','/health-analysis?tab=bp-pattern','TrendCharts','bp-pattern:view',1,1,1),
(24,29,'Health Report','analysis-health-report','/health-analysis?tab=health-report','DocumentChecked','health-report:view',1,2,1),
(25,29,'Data Export','analysis-data-export','/health-analysis?tab=data-export','Download','data-export:view',1,3,1),
(5,0,'System Administration','system','','Setting','system:manage',1,7,1),
(6,5,'User Management','system-user','/system/user','User','user:manage',1,1,1),
(7,5,'Role Management','system-role','/system/role','UserFilled','role:manage',1,2,1),
(9,5,'Menu Management','system-menu','/system/menu','Menu','menu:manage',1,3,1)
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),menu_name=VALUES(menu_name),menu_code=VALUES(menu_code),menu_path=VALUES(menu_path),menu_icon=VALUES(menu_icon),permission=VALUES(permission),menu_type=VALUES(menu_type),sort_order=VALUES(sort_order),status=VALUES(status);
INSERT INTO `sys_menu` (`id`,`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
VALUES (36,30,'Notification Settings','notification-settings','/settings/notifications','Bell','notification:manage',1,3,1)
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),menu_name=VALUES(menu_name),menu_code=VALUES(menu_code),menu_path=VALUES(menu_path),menu_icon=VALUES(menu_icon),permission=VALUES(permission),sort_order=VALUES(sort_order),status=VALUES(status);
INSERT INTO `sys_menu` (`id`,`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
VALUES (38,0,'Clinical Workbench','clinical-workbench','/clinical-workbench','FirstAidKit','clinical-workbench:view',1,1,1)
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),menu_name=VALUES(menu_name),menu_code=VALUES(menu_code),menu_path=VALUES(menu_path),menu_icon=VALUES(menu_icon),permission=VALUES(permission),sort_order=VALUES(sort_order),status=VALUES(status);
-- 10.3 Grant every menu to the administrator role
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.role_code = 'admin'
ON DUPLICATE KEY UPDATE `role_id` = `role_id`;

-- 10.4 Grant business menus to the standard user role (excluding administration)
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.role_code = 'user' AND m.id IN (28,30,8,31,36,38,1,11,12,13,14,15,2,16,17,18,19,3,32,33,34,26,27,20,21,23,35,29,22,24,25)
ON DUPLICATE KEY UPDATE `role_id` = `role_id`;

-- 10.5 Common health indicator dictionary
INSERT IGNORE INTO `health_indicator` (`item_code`, `item_name`, `aliases`, `unit`, `ref_range_male`, `category`, `sort_order`) VALUES
('FERRITIN', 'Ferritin', 'ferritin,serum ferritin', 'ng/mL', '27-375', 'IRON', 10),
('PTH', 'Parathyroid Hormone', 'parathyroid hormone,intact PTH,iPTH', 'pg/mL', '15-65', 'BONE', 20),
('CA', 'serum calcium', 'calcium,Ca', 'mmol/L', '2.1-2.6', 'BONE', 30),
('P', 'serum phosphorus', 'phosphorus,P', 'mmol/L', '0.87-1.45', 'BONE', 40),
('CREA', 'Creatinine', 'creatinine,serum creatinine,Cr', 'μmol/L', '44-133', 'KIDNEY', 50),
('BUN', 'Blood Urea Nitrogen', 'blood urea nitrogen,BUN', 'mmol/L', '2.9-8.2', 'KIDNEY', 60),
('K', 'Potassium', 'potassium,serum potassium,K', 'mmol/L', '3.5-5.5', 'BLOOD', 70),
('HB', 'Hemoglobin', 'hemoglobin,Hb,HGB', 'g/L', '120-160', 'BLOOD', 80);

INSERT IGNORE INTO `medication_safety_rule` (`ingredient_a`,`ingredient_b`,`severity`,`message`,`renal_note`) VALUES
('warfarin','ibuprofen','CRITICAL','Warfarin with ibuprofen can substantially increase bleeding risk. Confirm the prescriber plan.','Avoid routine NSAID use in advanced kidney disease unless explicitly directed.'),
('warfarin','aspirin','WARNING','Warfarin with aspirin increases bleeding risk and should have a documented indication.',NULL),
('lisinopril','potassium','WARNING','ACE inhibitor plus potassium supplementation can increase serum potassium.','Review potassium and renal function before changing therapy.'),
('losartan','potassium','WARNING','ARB plus potassium supplementation can increase serum potassium.','Review potassium and renal function before changing therapy.'),
('spironolactone','potassium','CRITICAL','This combination can cause severe hyperkalaemia. Confirm the prescriber plan.','Extra caution is required when kidney function is impaired.'),
('calcium carbonate','levothyroxine','INFO','Calcium can reduce levothyroxine absorption; separate administration times when directed.',NULL);

CREATE TABLE IF NOT EXISTS notification_channel (id BIGINT NOT NULL AUTO_INCREMENT,user_id BIGINT NOT NULL,channel_type VARCHAR(20) NOT NULL,channel_name VARCHAR(50) DEFAULT NULL,webhook_url VARCHAR(500) DEFAULT NULL,enabled TINYINT DEFAULT 1,last_test_at DATETIME DEFAULT NULL,last_test_result VARCHAR(255) DEFAULT NULL,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,PRIMARY KEY (id),KEY idx_channel_user (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User notification channels';

CREATE TABLE IF NOT EXISTS health_analysis_automation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, patient_id BIGINT NOT NULL,
 task_name VARCHAR(100) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
 frequency_type VARCHAR(20) NOT NULL DEFAULT 'WEEKLY', interval_days INT DEFAULT 1,
 day_of_week INT DEFAULT 1, day_of_month INT DEFAULT 1, run_time VARCHAR(5) NOT NULL DEFAULT '08:00',
 analysis_range_days INT NOT NULL DEFAULT 30, analysis_items VARCHAR(255) NOT NULL,
 notification_channel_ids VARCHAR(500), next_run_at DATETIME, last_run_at DATETIME,
 last_run_status VARCHAR(30), last_error VARCHAR(500), last_analysis_record_id BIGINT,
 created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_health_auto_due (enabled,next_run_at), KEY idx_health_auto_user (user_id), KEY idx_health_auto_patient (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automated health analysis tasks';

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
  PRIMARY KEY (`id`), KEY `idx_audit_user_time` (`user_id`,`created_at`), KEY `idx_audit_path_time` (`request_path`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Operation audit log';

INSERT INTO `sys_menu` (`id`,`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
VALUES (37,5,'Audit Log','system-audit','/system/audit','Document','audit:view',1,4,1)
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),menu_name=VALUES(menu_name),menu_code=VALUES(menu_code),menu_path=VALUES(menu_path),menu_icon=VALUES(menu_icon),permission=VALUES(permission),sort_order=VALUES(sort_order),status=VALUES(status);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,37 FROM sys_role r WHERE r.role_code='admin'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
