-- ============================================================
-- Clarity Health demo data
-- Use only with the Docker demo profile. Never load this file in production.
-- Sign-in account: demo / Demo@123456
-- All identities, contact details, and health records below are fictional.
-- ============================================================

SET NAMES utf8mb4;

INSERT INTO `sys_user`
(`id`,`username`,`password`,`real_name`,`phone`,`email`,`status`,`deleted`)
VALUES
(9001,'demo','$2a$10$QcNm2XJnLB2PmBV.CM.v2OwaapvzJ6jyUWbzhLgEq78C0Cej16D6S','Demo Administrator','18800000000','demo@example.invalid',1,0)
ON DUPLICATE KEY UPDATE `password`=VALUES(`password`),`real_name`=VALUES(`real_name`),`status`=1,`deleted`=0;

INSERT INTO `sys_user_role` (`user_id`,`role_id`)
SELECT 9001,id FROM `sys_role` WHERE `role_code`='admin'
ON DUPLICATE KEY UPDATE `role_id`=VALUES(`role_id`);

INSERT INTO `patient`
(`id`,`user_id`,`name`,`gender`,`birth_date`,`phone`,`medical_history`,`remark`,`status`,`deleted`)
VALUES
(9001,9001,'Emma Carter','FEMALE','1962-05-18','18800000001','Fictional history: hypertension and maintenance hemodialysis','Fictional record for product demonstration only',1,0)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`),`remark`=VALUES(`remark`),`status`=1,`deleted`=0;

INSERT INTO `patient_clinical`
(`id`,`patient_id`,`user_id`,`dialysis_type`,`dialysis_start_date`,`vascular_access`,`primary_diagnosis`,`target_dry_weight`,`fluid_limit_ml`,`remark`)
VALUES
(9001,9001,9001,'HD','2024-03-01','Left forearm arteriovenous fistula','Chronic kidney disease, stage 5',56.50,1200,'Fictional demo record; not for clinical decision-making')
ON DUPLICATE KEY UPDATE `target_dry_weight`=VALUES(`target_dry_weight`),`fluid_limit_ml`=VALUES(`fluid_limit_ml`);

INSERT INTO `patient_health_target`
(`id`,`user_id`,`patient_id`,`systolic_min`,`systolic_max`,`diastolic_min`,`diastolic_max`,`target_weight`,`weight_gain_limit`,`hospital_name`,`doctor_name`)
VALUES
(9001,9001,9001,100,140,60,90,56.50,2.50,'Riverside Medical Center','Dr. Daniel Lee')
ON DUPLICATE KEY UPDATE `target_weight`=VALUES(`target_weight`),`weight_gain_limit`=VALUES(`weight_gain_limit`);

INSERT INTO `dry_weight_monthly` (`id`,`year_month`,`dry_weight`,`user_id`,`patient_id`) VALUES
(9001,DATE_FORMAT(DATE_SUB(CURDATE(),INTERVAL 2 MONTH),'%Y-%m'),56.80,9001,9001),
(9002,DATE_FORMAT(DATE_SUB(CURDATE(),INTERVAL 1 MONTH),'%Y-%m'),56.60,9001,9001),
(9003,DATE_FORMAT(CURDATE(),'%Y-%m'),56.50,9001,9001)
ON DUPLICATE KEY UPDATE `dry_weight`=VALUES(`dry_weight`);

INSERT INTO `dialysis_record`
(`id`,`record_date`,`user_id`,`patient_id`,`last_off_weight`,`on_weight`,`off_weight`,`interval_days`,`weight_gain`,`uf_amount`,`systolic_bp`,`diastolic_bp`,`daily_weight_gain`,`dehydration_status`,`record_type`,`remark`)
VALUES
(9001,DATE_SUB(CURDATE(),INTERVAL 12 DAY),9001,9001,56.5,58.4,56.6,2,1.9,1.8,138,82,0.95,'MATCH','NORMAL','stable course'),
(9002,DATE_SUB(CURDATE(),INTERVAL 10 DAY),9001,9001,56.6,58.8,56.7,2,2.2,2.1,142,84,1.10,'MATCH','NORMAL','Mild fatigue, relieved after rest'),
(9003,DATE_SUB(CURDATE(),INTERVAL 7 DAY),9001,9001,56.7,59.2,56.8,3,2.5,2.4,146,86,0.83,'MATCH','NORMAL','Fluid intake was higher over the weekend'),
(9004,DATE_SUB(CURDATE(),INTERVAL 5 DAY),9001,9001,56.8,58.6,56.5,2,1.8,2.1,136,80,0.90,'MATCH','NORMAL','stable course'),
(9005,DATE_SUB(CURDATE(),INTERVAL 3 DAY),9001,9001,56.5,58.5,56.6,2,2.0,1.9,134,78,1.00,'MATCH','NORMAL','stable course'),
(9006,DATE_SUB(CURDATE(),INTERVAL 1 DAY),9001,9001,56.6,58.3,56.5,2,1.7,1.8,132,76,0.85,'MATCH','NORMAL','Feeling well')
ON DUPLICATE KEY UPDATE `on_weight`=VALUES(`on_weight`),`off_weight`=VALUES(`off_weight`),`remark`=VALUES(`remark`);

INSERT INTO `dialysis_schedule`
(`id`,`user_id`,`patient_id`,`schedule_date`,`schedule_time`,`status`,`remark`)
VALUES
(9001,9001,9001,DATE_ADD(CURDATE(),INTERVAL 1 DAY),'08:00','PLANNED','Bring the latest laboratory report'),
(9002,9001,9001,DATE_ADD(CURDATE(),INTERVAL 3 DAY),'08:00','PLANNED','Regular dialysis session')
ON DUPLICATE KEY UPDATE `schedule_time`=VALUES(`schedule_time`),`status`=VALUES(`status`),`remark`=VALUES(`remark`);

INSERT INTO `bp_self_monitor_record`
(`id`,`patient_id`,`user_id`,`record_date`,`record_time`,`measure_type`,`systolic_bp`,`diastolic_bp`,`remark`)
VALUES
(9001,9001,9001,DATE_SUB(CURDATE(),INTERVAL 4 DAY),'07:30','BP',145,86,'on waking'),
(9002,9001,9001,DATE_SUB(CURDATE(),INTERVAL 3 DAY),'20:10','BP',138,82,'Evening'),
(9003,9001,9001,DATE_SUB(CURDATE(),INTERVAL 2 DAY),'07:25','BP',136,80,'on waking'),
(9004,9001,9001,DATE_SUB(CURDATE(),INTERVAL 1 DAY),'20:20','BP',132,78,'Post-dialysis'),
(9005,9001,9001,CURDATE(),'07:35','BP',135,79,'on waking')
ON DUPLICATE KEY UPDATE `systolic_bp`=VALUES(`systolic_bp`),`diastolic_bp`=VALUES(`diastolic_bp`);

INSERT INTO `medication`
(`id`,`user_id`,`patient_id`,`drug_name`,`generic_name`,`specification`,`unit`,`dosage_form`,`category`,`default_dosage`,`remark`,`is_active`)
VALUES
(9001,9001,9001,'Amlodipine','Amlodipine','5 mg','tablet','TABLET','ANTIHYPERTENSIVE','Once daily','Fictional demo medication; follow the prescribed instructions',1),
(9002,9001,9001,'Sevelamer carbonate','Sevelamer carbonate','800 mg','tablet','TABLET','PHOSPHATE_BINDER','With meals','Fictional demo medication; follow the prescribed instructions',1),
(9003,9001,9001,'Renal multivitamin','Multivitamin supplement','1 tablet','tablet','TABLET','VITAMIN','Once daily','Fictional demo medication; follow the prescribed instructions',1)
ON DUPLICATE KEY UPDATE `drug_name`=VALUES(`drug_name`),`is_active`=1;

INSERT INTO `medication_reminder`
(`id`,`user_id`,`patient_id`,`medication_id`,`remind_time`,`repeat_days`,`dosage`,`enabled`,`remark`)
VALUES
(9001,9001,9001,9001,'08:00','1,2,3,4,5,6,7','1 tablet',1,'After breakfast'),
(9002,9001,9001,9002,'12:00','1,2,3,4,5,6,7','2 tablets',1,'With lunch'),
(9003,9001,9001,9003,'20:00','1,2,3,4,5,6,7','1 tablet',1,'After dinner')
ON DUPLICATE KEY UPDATE `remind_time`=VALUES(`remind_time`),`enabled`=1;

INSERT INTO `medication_intake`
(`id`,`user_id`,`patient_id`,`reminder_id`,`medication_id`,`scheduled_at`,`status`,`action_at`,`dosage`)
VALUES
(9001,9001,9001,9001,9001,TIMESTAMP(CURDATE(),'08:00:00'),'TAKEN',TIMESTAMP(CURDATE(),'08:06:00'),'1 tablet'),
(9002,9001,9001,9002,9002,TIMESTAMP(CURDATE(),'12:00:00'),'PENDING',NULL,'2 tablets'),
(9003,9001,9001,9003,9003,TIMESTAMP(CURDATE(),'20:00:00'),'PENDING',NULL,'1 tablet')
ON DUPLICATE KEY UPDATE `status`=VALUES(`status`),`action_at`=VALUES(`action_at`);

INSERT INTO `medication_stock`
(`id`,`patient_id`,`medication_id`,`quantity`,`unit`,`warning_days`,`warning_quantity`)
VALUES
(9001,9001,9001,18,'tablet',7,7),
(9002,9001,9002,42,'tablet',7,14),
(9003,9001,9003,6,'tablet',7,7)
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`),`warning_quantity`=VALUES(`warning_quantity`);

INSERT INTO `care_item`
(`id`,`patient_id`,`user_id`,`kind`,`title`,`status`,`assigned_user_id`,`event_at`,`notify_at`,`data_json`)
VALUES
(9001,9001,9001,'APPOINTMENT','Nephrology follow-up','OPEN',9001,DATE_ADD(NOW(),INTERVAL 5 DAY),DATE_ADD(NOW(),INTERVAL 4 DAY),JSON_OBJECT('hospital','Riverside Medical Center','department','Nephrology','doctor','Dr. Daniel Lee','preparation','Bring dialysis records and the latest laboratory report')),
(9002,9001,9001,'QUESTION','Discuss recent blood pressure changes','OPEN',9001,DATE_ADD(NOW(),INTERVAL 5 DAY),NULL,JSON_OBJECT('question','Pre-dialysis blood pressure is occasionally elevated. Should monitoring frequency change?')),
(9003,9001,9001,'SYMPTOM','Mild post-dialysis fatigue','OPEN',9001,DATE_SUB(NOW(),INTERVAL 1 DAY),NULL,JSON_OBJECT('severity',3,'progress','IMPROVING','note','Relieved after rest')),
(9004,9001,9001,'HANDOVER','Prepare follow-up materials','OPEN',9001,DATE_ADD(NOW(),INTERVAL 2 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY),JSON_OBJECT('note','Organize laboratory reports, medication list, and two weeks of blood pressure records'))
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`),`status`=VALUES(`status`),`event_at`=VALUES(`event_at`),`data_json`=VALUES(`data_json`);

INSERT INTO `medical_record`
(`id`,`patient_name`,`patient_id`,`user_id`,`record_date`,`record_type`,`hospital_name`,`dept_name`,`doctor_name`,`remark`)
VALUES
(9001,'Emma Carter',9001,9001,DATE_SUB(CURDATE(),INTERVAL 8 DAY),'BLOOD','Riverside Medical Center','Laboratory Medicine','Dr. Daniel Lee','Fictional laboratory data for demonstration')
ON DUPLICATE KEY UPDATE `record_date`=VALUES(`record_date`),`remark`=VALUES(`remark`);

INSERT INTO `medical_record_item`
(`id`,`record_id`,`item_name`,`item_code`,`result_value`,`unit`,`reference_range`,`is_abnormal`,`ai_confidence`)
VALUES
(9001,9001,'Hemoglobin','HB','108','g/L','120-160',-1,0.9800),
(9002,9001,'Potassium','K','4.8','mmol/L','3.5-5.5',0,0.9900),
(9003,9001,'Phosphorus','P','1.62','mmol/L','0.87-1.45',1,0.9700),
(9004,9001,'Calcium','CA','2.28','mmol/L','2.1-2.6',0,0.9900)
ON DUPLICATE KEY UPDATE `result_value`=VALUES(`result_value`),`is_abnormal`=VALUES(`is_abnormal`);

INSERT INTO `alert_record`
(`id`,`patient_id`,`user_id`,`alert_type`,`alert_level`,`alert_title`,`triggered_value`,`triggered_at`,`status`,`handling_note`)
VALUES
(9001,9001,9001,'INDICATOR','WARNING','Phosphorus above the reference range','1.62 mmol/L',DATE_SUB(NOW(),INTERVAL 8 DAY),'PENDING',NULL),
(9002,9001,9001,'INDICATOR','INFO','Hemoglobin below the reference range','108 g/L',DATE_SUB(NOW(),INTERVAL 8 DAY),'CONFIRMED','Added to the follow-up question list')
ON DUPLICATE KEY UPDATE `status`=VALUES(`status`),`handling_note`=VALUES(`handling_note`);

INSERT INTO `nutrition_diary`
(`id`,`patient_id`,`user_id`,`record_date`,`body_weight`,`appetite`,`meal_breakfast`,`meal_lunch`,`meal_dinner`,`fluid_intake`,`symptoms`,`remark`)
VALUES
(9001,9001,9001,DATE_SUB(CURDATE(),INTERVAL 2 DAY),56.9,'GOOD',1,1,1,1050,'','Regular diet'),
(9002,9001,9001,DATE_SUB(CURDATE(),INTERVAL 1 DAY),56.7,'NORMAL',1,1,1,1100,'Mild fatigue','Dialysis day'),
(9003,9001,9001,CURDATE(),56.8,'GOOD',1,1,0,720,'','Dinner not recorded yet')
ON DUPLICATE KEY UPDATE `body_weight`=VALUES(`body_weight`),`appetite`=VALUES(`appetite`),`fluid_intake`=VALUES(`fluid_intake`);

INSERT INTO `health_event`
(`id`,`user_id`,`patient_id`,`event_date`,`event_time`,`event_type`,`title`,`summary`,`source_type`,`source_id`,`status`)
VALUES
(9001,9001,9001,DATE_SUB(CURDATE(),INTERVAL 1 DAY),'11:30','DIALYSIS','Dialysis completed','Post-dialysis weight: 56.5 kg; session stable','DIALYSIS',9006,'RECORDED'),
(9002,9001,9001,CURDATE(),'08:06','MEDICATION','Morning medication taken','Amlodipine, 1 tablet','MEDICATION_INTAKE',9001,'RECORDED')
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`),`summary`=VALUES(`summary`);
