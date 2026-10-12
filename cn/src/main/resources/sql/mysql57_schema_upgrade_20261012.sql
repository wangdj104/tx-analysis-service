-- 用途：MySQL 5.7.18/8.0 定向升级协作结构与已确认的旧库索引、附件可空性；保留全部历史业务行。
-- 前提：先确认所选应用库并停止全部写入者，完整备份数据库、附件及配置并隔离恢复演练；基础、医生和照护平台结构已存在。
-- 重复执行：严格预检后补缺失对象，重建本文件保留的 8 个校验触发器，补回 nurse 角色、两个菜单与 nurse/admin 绑定。
-- 风险：DDL 隐式提交，整批不能回滚；禁止 mysql --force。预检失败须停下核查，绝不自动去重、删表或改写患者内容。
-- 风险：需应用 schema 内 CREATE ROUTINE、ALTER ROUTINE、EXECUTE、TRIGGER、SELECT、INSERT、UPDATE、CREATE、ALTER、INDEX、REFERENCES 权限。
-- 风险：cp57_upgrade_preflight 与 cp57_*_bi/bu 是本脚本保留名；失败可遗留过程，触发器定义冲突会先报错，绝不覆盖未知触发器。
-- 范围：不转换现有字符集、不改 unit 默认值、不删除额外历史表、不授予患者权限/账号角色、不自动发布旧计划。
-- 范围：完整包含 20261003 协作结构和相同菜单种子；本路径无需再执行旧协作脚本，也不重跑 init 或权限清理。
-- MySQL 5.7 的 CHECK 不执行：8 个 BEFORE INSERT/UPDATE 触发器提供同等范围校验，MySQL 8 保留原 CHECK。
-- 请使用支持 DELIMITER 的 mysql 客户端逐文件执行；本文件不由应用启动或 Compose 自动加载。

-- 只读依赖核验先于任何过程/业务表 DDL；不要把结构导出当作完整数据备份。
SELECT DATABASE() AS selected_schema, VERSION() AS server_version, @@sql_mode AS sql_mode;
-- 5.7.18 的现有较宽 utf8mb4 索引还需要 Barracuda、DYNAMIC/COMPRESSED 与 innodb_large_prefix=ON；8.0 已移除这些旧变量。
SHOW VARIABLES WHERE Variable_name IN ('innodb_large_prefix','innodb_file_format','innodb_default_row_format','log_bin','log_bin_trust_function_creators');
SELECT id,doctor_user_id,patient_id,title,plan_type,instructions,target_date,status,created_at,updated_at FROM doctor_care_plan WHERE 1=0;
SELECT id,user_id,name FROM patient WHERE 1=0;
SELECT id,patient_id FROM medication WHERE 1=0;
SELECT patient_id,medication_id,quantity,unit FROM medication_stock WHERE 1=0;
SELECT patient_id,dedupe_key,status FROM alert_record WHERE 1=0;
SELECT patient_id,scheduled_at,status FROM medication_intake WHERE 1=0;
SELECT patient_id,schedule_date,status FROM dialysis_schedule WHERE 1=0;
SELECT patient_id,event_date,event_time FROM health_event WHERE 1=0;
SELECT file_path,file_content,record_id FROM medical_record_attachment WHERE 1=0;
SELECT id,patient_id,grantee_user_id,visible_modules,status FROM care_access_grant WHERE 1=0;
SELECT id,role_code,role_name,description,status,deleted FROM sys_role WHERE 1=0;
SELECT id,parent_id,menu_name,menu_code,menu_path,menu_icon,permission,menu_type,sort_order,status FROM sys_menu WHERE 1=0;
SELECT role_id,menu_id FROM sys_role_menu WHERE 1=0;

-- 已存在的过程必须与本脚本正文哈希一致；未知/不可读定义通过不存在的只读系统表明确中止，先于 DROP PROCEDURE。
SET @cp57_owned_routine=NOT EXISTS(SELECT 1 FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name='cp57_upgrade_preflight' AND routine_type='PROCEDURE') OR EXISTS(SELECT 1 FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name='cp57_upgrade_preflight' AND routine_type='PROCEDURE' AND SHA2(ROUTINE_DEFINITION,256)='02864f30bbf542e5ce700f059d6d203ed561873608b1e3ccaca7be8c0b95d87b');
SET @ddl=IF(@cp57_owned_routine,'SELECT 1','SELECT * FROM information_schema.CP57_UNKNOWN_ROUTINE_STOP_REVIEW');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DELIMITER $$
DROP PROCEDURE IF EXISTS cp57_upgrade_preflight$$
CREATE PROCEDURE cp57_upgrade_preflight()
BEGIN
  IF DATABASE() IS NULL OR VERSION() NOT REGEXP '^(5[.]7[.]|8[.])' OR VERSION() LIKE '%MariaDB%' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Select an application schema on MySQL 5.7.18+ or MySQL 8';
  END IF;
  IF @@GLOBAL.log_bin=1 AND @@GLOBAL.log_bin_trust_function_creators=0 AND NOT EXISTS(SELECT 1 FROM information_schema.user_privileges WHERE privilege_type='SUPER' AND grantee=CONCAT(CHAR(39),SUBSTRING_INDEX(CURRENT_USER(),'@',1),CHAR(39),'@',CHAR(39),SUBSTRING_INDEX(CURRENT_USER(),'@',-1),CHAR(39))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='DBA review required: binary logging restricts trigger creation';
  END IF;
  IF VERSION() LIKE '5.7.%' AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)<18 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MySQL 5.7.18 or later is required';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('doctor_care_plan','patient','medication','medication_stock','alert_record','medication_intake','dialysis_schedule','health_event','medical_record_attachment','care_access_grant','sys_role','sys_menu','sys_role_menu') AND (engine<>'InnoDB' OR table_type<>'BASE TABLE')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected InnoDB prerequisite tables';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='patient' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO') OR NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected patient.id definition';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO') OR NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.id definition';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='patient' AND (column_name='user_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected patient.user_id definition';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='patient' AND (column_name='name' AND data_type='varchar' AND character_maximum_length=50 AND is_nullable='NO' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected patient.name definition';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medication_stock' AND (column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected medication_stock.patient_id definition';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medication_stock' AND (column_name='medication_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected medication_stock.medication_id definition';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record_attachment' AND column_name='file_path' AND data_type='varchar' AND character_maximum_length=500 AND column_default IS NULL AND extra='') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected attachment file_path definition';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_record' AND column_name='patient_id' AND data_type='bigint') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column alert_record.patient_id';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_record' AND column_name='dedupe_key' AND data_type='varchar' AND character_maximum_length=160) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column alert_record.dedupe_key';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_record' AND column_name='status' AND data_type='varchar' AND character_maximum_length=20) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column alert_record.status';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medication_intake' AND column_name='patient_id' AND data_type='bigint') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column medication_intake.patient_id';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medication_intake' AND column_name='scheduled_at' AND data_type='datetime') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column medication_intake.scheduled_at';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medication_intake' AND column_name='status' AND data_type='varchar' AND character_maximum_length=20) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column medication_intake.status';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dialysis_schedule' AND column_name='patient_id' AND data_type='bigint') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column dialysis_schedule.patient_id';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dialysis_schedule' AND column_name='schedule_date' AND data_type='date') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column dialysis_schedule.schedule_date';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dialysis_schedule' AND column_name='status' AND data_type='varchar' AND character_maximum_length=20) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column dialysis_schedule.status';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='health_event' AND column_name='patient_id' AND data_type='bigint') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column health_event.patient_id';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='health_event' AND column_name='event_date' AND data_type='date') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column health_event.event_date';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='health_event' AND column_name='event_time' AND data_type='varchar' AND character_maximum_length=5) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected index column health_event.event_time';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='workflow_version') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='workflow_version' AND data_type='tinyint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default='0' AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.workflow_version definition';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='lifecycle') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='lifecycle' AND data_type='varchar' AND character_maximum_length=20 AND is_nullable='YES' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.lifecycle definition';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='current_revision_id') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='current_revision_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.current_revision_id definition';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='draft_revision_id') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='draft_revision_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.draft_revision_id definition';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='legacy_source_id') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='legacy_source_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.legacy_source_id definition';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='lock_version') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='lock_version' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default='0' AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.lock_version definition';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='closed_at') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='closed_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.closed_at definition';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='cancelled_at') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='cancelled_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.cancelled_at definition';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='cancel_reason') AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND (column_name='cancel_reason' AND data_type='varchar' AND character_maximum_length=1000 AND is_nullable='YES' AND column_default IS NULL AND extra='')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected doctor_care_plan.cancel_reason definition';
  END IF;
  IF (SELECT COUNT(*) FROM sys_role WHERE role_code='admin' AND status=1 AND deleted=0)<>1 OR (SELECT COUNT(*) FROM sys_role WHERE role_code='admin')<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing or ambiguous active admin role; review menu bindings';
  END IF;
  IF (SELECT COUNT(*) FROM sys_role WHERE role_code='nurse')>1 OR EXISTS(SELECT 1 FROM sys_role WHERE role_code='nurse' AND (NOT(status<=>1) OR NOT(deleted<=>0))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected nurse role status; preserve and review';
  END IF;
  IF (SELECT COUNT(*) FROM sys_menu WHERE menu_code='patient-profile')<>1 OR NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=8 AND menu_code='patient-profile') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected patient-profile menu id 8; review parent identity';
  END IF;
  IF (SELECT COUNT(*) FROM sys_menu WHERE menu_code='care-plan-nursing')>1 OR EXISTS(SELECT 1 FROM sys_menu WHERE menu_code='care-plan-nursing' AND (NOT(parent_id<=>0) OR NOT(menu_path<=>'/nurse-workspace') OR NOT(permission<=>'care-plan:nursing:view') OR NOT(menu_type<=>1) OR NOT(status<=>1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care-plan-nursing definition; preserve and review';
  END IF;
  IF (SELECT COUNT(*) FROM sys_menu WHERE menu_code='care-plan-nurse-assignments')>1 OR EXISTS(SELECT 1 FROM sys_menu WHERE menu_code='care-plan-nurse-assignments' AND (NOT(parent_id<=>8) OR NOT(menu_path<=>NULL) OR NOT(permission<=>'care-plan:nurse-assignment:manage') OR NOT(menu_type<=>2) OR NOT(status<=>1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care-plan-nurse-assignments definition; preserve and review';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sys_role_menu' GROUP BY index_name HAVING COUNT(*)=2 AND GROUP_CONCAT(column_name ORDER BY seq_in_index)='role_id,menu_id' AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected unique role/menu bindings; review existing schema';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_revision') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_revision' AND engine='InnoDB' AND table_type='BASE TABLE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_revision storage';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_revision' AND (
      (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='auto_increment') OR
      (column_name='plan_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='revision_no' AND data_type='int' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='status' AND data_type='varchar' AND character_maximum_length=20 AND is_nullable='NO' AND column_default='DRAFT' AND extra='') OR
      (column_name='title' AND data_type='varchar' AND character_maximum_length=160 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='instructions' AND data_type='text' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='plan_type' AND data_type='varchar' AND character_maximum_length=40 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='draft_json' AND data_type='longtext' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='created_by' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='created_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='updated_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='published_by' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='published_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='')
    ))<>13 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_revision columns';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_revision' AND column_name NOT IN ('id','plan_id','revision_no','status','title','instructions','plan_type','draft_json','created_by','created_at','updated_at','published_by','published_at') AND is_nullable='NO' AND column_default IS NULL AND extra NOT LIKE '%auto_increment%') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected required column on care_plan_revision';
  END IF;
  IF NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_revision' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_revision.PRIMARY index';
  END IF;
  IF NOT (SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='plan_id,revision_no',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_revision' AND index_name='uk_care_plan_revision_no') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_revision.uk_care_plan_revision_no index';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_revision' AND k.constraint_name='fk_care_plan_revision_plan' AND k.column_name='plan_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='doctor_care_plan' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_revision' AND all_k.constraint_name='fk_care_plan_revision_plan')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_revision_plan foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_revision` c LEFT JOIN `doctor_care_plan` p ON c.`plan_id`=p.id WHERE c.`plan_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_action') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_action' AND engine='InnoDB' AND table_type='BASE TABLE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_action storage';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_action' AND (
      (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='auto_increment') OR
      (column_name='plan_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='revision_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='ordinal' AND data_type='int' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='instruction' AND data_type='text' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='due_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='assigned_user_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='status' AND data_type='varchar' AND character_maximum_length=20 AND is_nullable='NO' AND column_default='OPEN' AND extra='') OR
      (column_name='lock_version' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default='0' AND extra='') OR
      (column_name='first_submitted_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='latest_submitted_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='review_waiting_since' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='created_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='updated_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='')
    ))<>15 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_action columns';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_action' AND column_name NOT IN ('id','plan_id','revision_id','patient_id','ordinal','instruction','due_at','assigned_user_id','status','lock_version','first_submitted_at','latest_submitted_at','review_waiting_since','created_at','updated_at') AND is_nullable='NO' AND column_default IS NULL AND extra NOT LIKE '%auto_increment%') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected required column on care_plan_action';
  END IF;
  IF NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_action' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_action.PRIMARY index';
  END IF;
  IF NOT (SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='revision_id,ordinal',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_action' AND index_name='uk_care_plan_action_ordinal') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_action.uk_care_plan_action_ordinal index';
  END IF;
  IF NOT (SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,status,due_at',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_action' AND index_name='idx_care_plan_action_patient_queue') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_action.idx_care_plan_action_patient_queue index';
  END IF;
  IF NOT (SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='plan_id,status',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_action' AND index_name='idx_care_plan_action_plan') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_action.idx_care_plan_action_plan index';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_action' AND k.constraint_name='fk_care_plan_action_plan' AND k.column_name='plan_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='doctor_care_plan' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_action' AND all_k.constraint_name='fk_care_plan_action_plan')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_action_plan foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_action` c LEFT JOIN `doctor_care_plan` p ON c.`plan_id`=p.id WHERE c.`plan_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_action' AND k.constraint_name='fk_care_plan_action_revision' AND k.column_name='revision_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='care_plan_revision' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_action' AND all_k.constraint_name='fk_care_plan_action_revision')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_action_revision foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_action` c LEFT JOIN `care_plan_revision` p ON c.`revision_id`=p.id WHERE c.`revision_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_action' AND k.constraint_name='fk_care_plan_action_patient' AND k.column_name='patient_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='patient' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_action' AND all_k.constraint_name='fk_care_plan_action_patient')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_action_patient foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_action` c LEFT JOIN `patient` p ON c.`patient_id`=p.id WHERE c.`patient_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_event') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_event' AND engine='InnoDB' AND table_type='BASE TABLE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_event storage';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_event' AND (
      (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='auto_increment') OR
      (column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='plan_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='revision_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='action_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='actor_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='actor_name' AND data_type='varchar' AND character_maximum_length=100 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='actor_role' AND data_type='varchar' AND character_maximum_length=50 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='actor_relation' AND data_type='varchar' AND character_maximum_length=100 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='entry_mode' AND data_type='varchar' AND character_maximum_length=20 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='event_type' AND data_type='varchar' AND character_maximum_length=40 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='note' AND data_type='text' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='occurred_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='recorded_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='payload_json' AND data_type='longtext' AND is_nullable='NO' AND column_default IS NULL AND extra='')
    ))<>15 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_event columns';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_event' AND column_name NOT IN ('id','patient_id','plan_id','revision_id','action_id','actor_id','actor_name','actor_role','actor_relation','entry_mode','event_type','note','occurred_at','recorded_at','payload_json') AND is_nullable='NO' AND column_default IS NULL AND extra NOT LIKE '%auto_increment%') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected required column on care_plan_event';
  END IF;
  IF NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_event' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_event.PRIMARY index';
  END IF;
  IF NOT (SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='plan_id,id',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_event' AND index_name='idx_care_plan_event_plan') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_event.idx_care_plan_event_plan index';
  END IF;
  IF NOT (SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='action_id,id',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_event' AND index_name='idx_care_plan_event_action') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_event.idx_care_plan_event_action index';
  END IF;
  IF NOT (SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,recorded_at,id',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_event' AND index_name='idx_care_plan_event_patient_time') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_event.idx_care_plan_event_patient_time index';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_event' AND k.constraint_name='fk_care_plan_event_patient' AND k.column_name='patient_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='patient' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_event' AND all_k.constraint_name='fk_care_plan_event_patient')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_event_patient foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_event` c LEFT JOIN `patient` p ON c.`patient_id`=p.id WHERE c.`patient_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_event' AND k.constraint_name='fk_care_plan_event_plan' AND k.column_name='plan_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='doctor_care_plan' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_event' AND all_k.constraint_name='fk_care_plan_event_plan')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_event_plan foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_event` c LEFT JOIN `doctor_care_plan` p ON c.`plan_id`=p.id WHERE c.`plan_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_event' AND k.constraint_name='fk_care_plan_event_revision' AND k.column_name='revision_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='care_plan_revision' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_event' AND all_k.constraint_name='fk_care_plan_event_revision')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_event_revision foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_event` c LEFT JOIN `care_plan_revision` p ON c.`revision_id`=p.id WHERE c.`revision_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_event' AND k.constraint_name='fk_care_plan_event_action' AND k.column_name='action_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='care_plan_action' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_event' AND all_k.constraint_name='fk_care_plan_event_action')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_event_action foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_event` c LEFT JOIN `care_plan_action` p ON c.`action_id`=p.id WHERE c.`action_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_evidence') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_evidence' AND engine='InnoDB' AND table_type='BASE TABLE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_evidence storage';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_evidence' AND (
      (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='auto_increment') OR
      (column_name='event_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='source_type' AND data_type='varchar' AND character_maximum_length=30 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='source_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='')
    ))<>4 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_evidence columns';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_evidence' AND column_name NOT IN ('id','event_id','source_type','source_id') AND is_nullable='NO' AND column_default IS NULL AND extra NOT LIKE '%auto_increment%') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected required column on care_plan_evidence';
  END IF;
  IF NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_evidence' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_evidence.PRIMARY index';
  END IF;
  IF NOT (SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='event_id,source_type,source_id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_evidence' AND index_name='uk_care_plan_evidence_ref') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_evidence.uk_care_plan_evidence_ref index';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_evidence' AND k.constraint_name='fk_care_plan_evidence_event' AND k.column_name='event_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='care_plan_event' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_evidence' AND all_k.constraint_name='fk_care_plan_evidence_event')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_evidence_event foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_evidence` c LEFT JOIN `care_plan_event` p ON c.`event_id`=p.id WHERE c.`event_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_nurse_assignment') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_nurse_assignment' AND engine='InnoDB' AND table_type='BASE TABLE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_nurse_assignment storage';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_nurse_assignment' AND (
      (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='auto_increment') OR
      (column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='nurse_user_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='assigned_by' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='assigned_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='expires_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='revoked_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='status' AND data_type='varchar' AND character_maximum_length=20 AND is_nullable='NO' AND column_default='ACTIVE' AND extra='')
    ))<>8 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_nurse_assignment columns';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_nurse_assignment' AND column_name NOT IN ('id','patient_id','nurse_user_id','assigned_by','assigned_at','expires_at','revoked_at','status') AND is_nullable='NO' AND column_default IS NULL AND extra NOT LIKE '%auto_increment%') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected required column on care_nurse_assignment';
  END IF;
  IF NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_nurse_assignment' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_nurse_assignment.PRIMARY index';
  END IF;
  IF NOT (SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,nurse_user_id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_nurse_assignment' AND index_name='uk_care_nurse_patient_user') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_nurse_assignment.uk_care_nurse_patient_user index';
  END IF;
  IF NOT (SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='nurse_user_id,status,expires_at',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_nurse_assignment' AND index_name='idx_care_nurse_user_status') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_nurse_assignment.idx_care_nurse_user_status index';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_nurse_assignment' AND k.constraint_name='fk_care_nurse_patient' AND k.column_name='patient_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='patient' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_nurse_assignment' AND all_k.constraint_name='fk_care_nurse_patient')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_nurse_patient foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_nurse_assignment` c LEFT JOIN `patient` p ON c.`patient_id`=p.id WHERE c.`patient_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_notification') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_notification' AND engine='InnoDB' AND table_type='BASE TABLE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_notification storage';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_notification' AND (
      (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='auto_increment') OR
      (column_name='event_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='recipient_user_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='channel_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='dispatch_key' AND data_type='varchar' AND character_maximum_length=160 AND is_nullable='NO' AND column_default IS NULL AND extra='' AND character_set_name='ascii' AND collation_name='ascii_bin') OR
      (column_name='status' AND data_type='varchar' AND character_maximum_length=20 AND is_nullable='NO' AND column_default='QUEUED' AND extra='') OR
      (column_name='attempt_count' AND data_type='int' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default='0' AND extra='') OR
      (column_name='next_attempt_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='claimed_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='claim_token' AND data_type='varchar' AND character_maximum_length=64 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='request_id' AND data_type='varchar' AND character_maximum_length=64 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='last_result' AND data_type='varchar' AND character_maximum_length=1000 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='delivered_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='created_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='updated_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='')
    ))<>16 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_notification columns';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_notification' AND column_name NOT IN ('id','event_id','patient_id','recipient_user_id','channel_id','dispatch_key','status','attempt_count','next_attempt_at','claimed_at','claim_token','request_id','last_result','delivered_at','created_at','updated_at') AND is_nullable='NO' AND column_default IS NULL AND extra NOT LIKE '%auto_increment%') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected required column on care_plan_notification';
  END IF;
  IF NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_notification' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_notification.PRIMARY index';
  END IF;
  IF NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='dispatch_key',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_notification' AND index_name='uk_care_plan_notification_dispatch') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_notification.uk_care_plan_notification_dispatch index';
  END IF;
  IF NOT (SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='status,next_attempt_at,id',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_notification' AND index_name='idx_care_plan_notification_due') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_notification.idx_care_plan_notification_due index';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_notification' AND k.constraint_name='fk_care_plan_notification_event' AND k.column_name='event_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='care_plan_event' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_notification' AND all_k.constraint_name='fk_care_plan_notification_event')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_notification_event foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_notification` c LEFT JOIN `care_plan_event` p ON c.`event_id`=p.id WHERE c.`event_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_notification' AND k.constraint_name='fk_care_plan_notification_patient' AND k.column_name='patient_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='patient' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_notification' AND all_k.constraint_name='fk_care_plan_notification_patient')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_notification_patient foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_notification` c LEFT JOIN `patient` p ON c.`patient_id`=p.id WHERE c.`patient_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_command') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_command' AND engine='InnoDB' AND table_type='BASE TABLE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_command storage';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_command' AND (
      (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='auto_increment') OR
      (column_name='actor_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='command_key' AND data_type='varchar' AND character_maximum_length=36 AND is_nullable='NO' AND column_default IS NULL AND extra='' AND character_set_name='ascii' AND collation_name='ascii_bin') OR
      (column_name='plan_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='expected_version' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO' AND column_default IS NULL AND extra='') OR
      (column_name='payload_hash' AND data_type='varchar' AND character_maximum_length=64 AND is_nullable='NO' AND column_default IS NULL AND extra='' AND character_set_name='ascii' AND collation_name='ascii_bin') OR
      (column_name='result_json' AND data_type='longtext' AND is_nullable='YES' AND column_default IS NULL AND extra='') OR
      (column_name='created_at' AND data_type='datetime' AND datetime_precision=6 AND is_nullable='NO' AND column_default IS NULL AND extra='')
    ))<>8 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_command columns';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='care_plan_command' AND column_name NOT IN ('id','actor_id','command_key','plan_id','expected_version','payload_hash','result_json','created_at') AND is_nullable='NO' AND column_default IS NULL AND extra NOT LIKE '%auto_increment%') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected required column on care_plan_command';
  END IF;
  IF NOT (SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_command' AND index_name='PRIMARY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_command.PRIMARY index';
  END IF;
  IF NOT (SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='actor_id,command_key',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='care_plan_command' AND index_name='uk_care_plan_command_actor_key') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected care_plan_command.uk_care_plan_command_actor_key index';
  END IF;
  IF NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='care_plan_command' AND k.constraint_name='fk_care_plan_command_plan' AND k.column_name='plan_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='doctor_care_plan' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='care_plan_command' AND all_k.constraint_name='fk_care_plan_command_plan')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_care_plan_command_plan foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM `care_plan_command` c LEFT JOIN `doctor_care_plan` p ON c.`plan_id`=p.id WHERE c.`plan_id` IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing collaboration foreign-key orphan';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_revision') THEN
  IF EXISTS(SELECT 1 FROM care_plan_revision WHERE revision_no <= 0) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid existing collaboration rows';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_action') THEN
  IF EXISTS(SELECT 1 FROM care_plan_action WHERE ordinal NOT BETWEEN 1 AND 50 OR lock_version < 0) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid existing collaboration rows';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_notification') THEN
  IF EXISTS(SELECT 1 FROM care_plan_notification WHERE attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(dispatch_key) = 0) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid existing collaboration rows';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_command') THEN
  IF EXISTS(SELECT 1 FROM care_plan_command WHERE expected_version < 0) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid existing collaboration rows';
  END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='doctor_care_plan' AND constraint_name='fk_doctor_plan_current_revision') AND NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='doctor_care_plan' AND k.constraint_name='fk_doctor_plan_current_revision' AND k.column_name='current_revision_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='care_plan_revision' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='doctor_care_plan' AND all_k.constraint_name='fk_doctor_plan_current_revision')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_doctor_plan_current_revision foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='current_revision_id') THEN
    IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_revision') THEN
  IF EXISTS(SELECT 1 FROM doctor_care_plan c LEFT JOIN care_plan_revision p ON c.current_revision_id=p.id WHERE c.current_revision_id IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing doctor plan foreign-key orphan';
  END IF;
    ELSEIF EXISTS(SELECT 1 FROM doctor_care_plan WHERE current_revision_id IS NOT NULL) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing referenced revision table with existing plan links';
    END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='doctor_care_plan' AND constraint_name='fk_doctor_plan_draft_revision') AND NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='doctor_care_plan' AND k.constraint_name='fk_doctor_plan_draft_revision' AND k.column_name='draft_revision_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='care_plan_revision' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='doctor_care_plan' AND all_k.constraint_name='fk_doctor_plan_draft_revision')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_doctor_plan_draft_revision foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='draft_revision_id') THEN
    IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='care_plan_revision') THEN
  IF EXISTS(SELECT 1 FROM doctor_care_plan c LEFT JOIN care_plan_revision p ON c.draft_revision_id=p.id WHERE c.draft_revision_id IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing doctor plan foreign-key orphan';
  END IF;
    ELSEIF EXISTS(SELECT 1 FROM doctor_care_plan WHERE draft_revision_id IS NOT NULL) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing referenced revision table with existing plan links';
    END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='doctor_care_plan' AND constraint_name='fk_doctor_plan_legacy_source') AND NOT (SELECT COUNT(*)=1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name='doctor_care_plan' AND k.constraint_name='fk_doctor_plan_legacy_source' AND k.column_name='legacy_source_id' AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name='doctor_care_plan' AND k.referenced_column_name='id' AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION') AND (SELECT COUNT(*) FROM information_schema.key_column_usage all_k WHERE all_k.constraint_schema=DATABASE() AND all_k.table_name='doctor_care_plan' AND all_k.constraint_name='fk_doctor_plan_legacy_source')=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected fk_doctor_plan_legacy_source foreign key';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='doctor_care_plan' AND column_name='legacy_source_id') THEN
    IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='doctor_care_plan') THEN
  IF EXISTS(SELECT 1 FROM doctor_care_plan c LEFT JOIN doctor_care_plan p ON c.legacy_source_id=p.id WHERE c.legacy_source_id IS NOT NULL AND p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing doctor plan foreign-key orphan';
  END IF;
    ELSEIF EXISTS(SELECT 1 FROM doctor_care_plan WHERE legacy_source_id IS NOT NULL) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing referenced revision table with existing plan links';
    END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='uk_name') AND NOT ((SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='name',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='uk_name')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unrecognized legacy index patient.uk_name';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='uk_user_patient_name') AND NOT ((SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='user_id,name',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='uk_user_patient_name')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unrecognized legacy index patient.uk_user_patient_name';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_stock' AND index_name='uk_medication_stock') AND NOT ((SELECT COUNT(*)=1 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='medication_id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_stock' AND index_name='uk_medication_stock') OR (SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,medication_id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_stock' AND index_name='uk_medication_stock')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unrecognized legacy index medication_stock.uk_medication_stock';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_record' AND index_name='idx_alert_dedupe') AND NOT ((SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,dedupe_key,status',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_record' AND index_name='idx_alert_dedupe')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unrecognized legacy index alert_record.idx_alert_dedupe';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_intake' AND index_name='idx_intake_patient_schedule') AND NOT ((SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,scheduled_at,status',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_intake' AND index_name='idx_intake_patient_schedule')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unrecognized legacy index medication_intake.idx_intake_patient_schedule';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dialysis_schedule' AND index_name='idx_schedule_patient_date') AND NOT ((SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,schedule_date,status',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dialysis_schedule' AND index_name='idx_schedule_patient_date')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unrecognized legacy index dialysis_schedule.idx_schedule_patient_date';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='health_event' AND index_name='idx_event_patient_date') AND NOT ((SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,event_date',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='health_event' AND index_name='idx_event_patient_date') OR (SELECT COUNT(*)=3 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,event_date,event_time',0) AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='health_event' AND index_name='idx_event_patient_date')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unrecognized legacy index health_event.idx_event_patient_date';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name<>'uk_name' GROUP BY index_name HAVING MIN(non_unique)=0 AND COUNT(*)=1 AND GROUP_CONCAT(column_name)='name') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown global unique index on patient; review scope';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_stock' AND index_name<>'uk_medication_stock' GROUP BY index_name HAVING MIN(non_unique)=0 AND COUNT(*)=1 AND GROUP_CONCAT(column_name)='medication_id') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown global unique index on medication_stock; review scope';
  END IF;
  IF EXISTS(SELECT 1 FROM patient WHERE user_id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Patient ownership is NULL; resolve explicitly before upgrade';
  END IF;
  IF EXISTS(SELECT 1 FROM patient GROUP BY user_id,name HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Duplicate patient owner/name; retain all records and review';
  END IF;
  IF EXISTS(SELECT 1 FROM medication_stock GROUP BY patient_id,medication_id HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Duplicate stock patient/medication; retain all records and review';
  END IF;
  IF EXISTS(SELECT 1 FROM medication_stock s LEFT JOIN medication m ON m.id=s.medication_id LEFT JOIN patient p ON p.id=s.patient_id WHERE p.id IS NULL OR m.id IS NULL OR m.patient_id IS NULL OR m.patient_id<>s.patient_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Stock medication ownership mismatch; review before upgrade';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_revision_bi' AND (event_object_table<>'care_plan_revision' OR event_manipulation<>'INSERT' OR action_timing<>'BEFORE' OR BINARY ACTION_STATEMENT<>BINARY 'BEGIN
  IF NEW.revision_no <= 0 THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Care plan revision_no must be positive'';
  END IF;
END')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown trigger definition cp57_revision_bi; preserve and review';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_revision_bu' AND (event_object_table<>'care_plan_revision' OR event_manipulation<>'UPDATE' OR action_timing<>'BEFORE' OR BINARY ACTION_STATEMENT<>BINARY 'BEGIN
  IF NEW.revision_no <= 0 THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Care plan revision_no must be positive'';
  END IF;
END')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown trigger definition cp57_revision_bu; preserve and review';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_action_bi' AND (event_object_table<>'care_plan_action' OR event_manipulation<>'INSERT' OR action_timing<>'BEFORE' OR BINARY ACTION_STATEMENT<>BINARY 'BEGIN
  IF NEW.ordinal NOT BETWEEN 1 AND 50 OR NEW.lock_version < 0 THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Care plan ordinal/version outside allowed range'';
  END IF;
END')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown trigger definition cp57_action_bi; preserve and review';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_action_bu' AND (event_object_table<>'care_plan_action' OR event_manipulation<>'UPDATE' OR action_timing<>'BEFORE' OR BINARY ACTION_STATEMENT<>BINARY 'BEGIN
  IF NEW.ordinal NOT BETWEEN 1 AND 50 OR NEW.lock_version < 0 THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Care plan ordinal/version outside allowed range'';
  END IF;
END')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown trigger definition cp57_action_bu; preserve and review';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_notification_bi' AND (event_object_table<>'care_plan_notification' OR event_manipulation<>'INSERT' OR action_timing<>'BEFORE' OR BINARY ACTION_STATEMENT<>BINARY 'BEGIN
  IF NEW.attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(NEW.dispatch_key) = 0 THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Care plan notification attempts/dispatch invalid'';
  END IF;
END')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown trigger definition cp57_notification_bi; preserve and review';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_notification_bu' AND (event_object_table<>'care_plan_notification' OR event_manipulation<>'UPDATE' OR action_timing<>'BEFORE' OR BINARY ACTION_STATEMENT<>BINARY 'BEGIN
  IF NEW.attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(NEW.dispatch_key) = 0 THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Care plan notification attempts/dispatch invalid'';
  END IF;
END')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown trigger definition cp57_notification_bu; preserve and review';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_command_bi' AND (event_object_table<>'care_plan_command' OR event_manipulation<>'INSERT' OR action_timing<>'BEFORE' OR BINARY ACTION_STATEMENT<>BINARY 'BEGIN
  IF NEW.expected_version < 0 THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Care plan command version must be nonnegative'';
  END IF;
END')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown trigger definition cp57_command_bi; preserve and review';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='cp57_command_bu' AND (event_object_table<>'care_plan_command' OR event_manipulation<>'UPDATE' OR action_timing<>'BEFORE' OR BINARY ACTION_STATEMENT<>BINARY 'BEGIN
  IF NEW.expected_version < 0 THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Care plan command version must be nonnegative'';
  END IF;
END')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown trigger definition cp57_command_bu; preserve and review';
  END IF;
END$$
CALL cp57_upgrade_preflight()$$
DROP PROCEDURE cp57_upgrade_preflight$$
DELIMITER ;

-- 全部预检通过后才开始业务表 DDL；预检与执行之间须持续停止其他写入者。
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
SELECT 'nurse','护理人员','分配患者的照护计划跟进；同时要求患者模块授权',1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code='nurse');
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
SELECT 0,'护理跟进队列','care-plan-nursing','/nurse-workspace','FirstAidKit','care-plan:nursing:view',1,2,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_code='care-plan-nursing');
INSERT INTO `sys_menu` (`parent_id`,`menu_name`,`menu_code`,`menu_path`,`menu_icon`,`permission`,`menu_type`,`sort_order`,`status`)
SELECT 8,'管理护理分配','care-plan-nurse-assignments',NULL,'User','care-plan:nurse-assignment:manage',2,90,1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_code='care-plan-nurse-assignments');
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code='care-plan-nursing' WHERE r.role_code='nurse'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.menu_code='care-plan-nurse-assignments' WHERE r.role_code='admin'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

-- 仅修复已确认的旧唯一范围与四个二级索引；不缩短索引前缀，不删除任何行。
SET @cp57_patient_target=EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' GROUP BY index_name HAVING COUNT(*)=2 AND GROUP_CONCAT(column_name ORDER BY seq_in_index)='user_id,name' AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0);
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='uk_name'),IF(@cp57_patient_target,'ALTER TABLE patient DROP INDEX uk_name','ALTER TABLE patient DROP INDEX uk_name, ADD UNIQUE KEY uk_user_patient_name(user_id,name)'),IF(@cp57_patient_target,'SELECT 1','ALTER TABLE patient ADD UNIQUE KEY uk_user_patient_name(user_id,name)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=IF((SELECT COUNT(*)=2 AND COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,medication_id',0) AND MIN(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_stock' AND index_name='uk_medication_stock'),'SELECT 1',IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_stock' AND index_name='uk_medication_stock'),'ALTER TABLE medication_stock DROP INDEX uk_medication_stock, ADD UNIQUE KEY uk_medication_stock(patient_id,medication_id)','ALTER TABLE medication_stock ADD UNIQUE KEY uk_medication_stock(patient_id,medication_id)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_record' GROUP BY index_name HAVING COUNT(*)=3 AND GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,dedupe_key,status' AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0),'SELECT 1',IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_record' AND index_name='idx_alert_dedupe'),'ALTER TABLE alert_record DROP INDEX idx_alert_dedupe, ADD KEY idx_alert_dedupe(patient_id,dedupe_key,status)','ALTER TABLE alert_record ADD KEY idx_alert_dedupe(patient_id,dedupe_key,status)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_intake' GROUP BY index_name HAVING COUNT(*)=3 AND GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,scheduled_at,status' AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0),'SELECT 1',IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medication_intake' AND index_name='idx_intake_patient_schedule'),'ALTER TABLE medication_intake DROP INDEX idx_intake_patient_schedule, ADD KEY idx_intake_patient_schedule(patient_id,scheduled_at,status)','ALTER TABLE medication_intake ADD KEY idx_intake_patient_schedule(patient_id,scheduled_at,status)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dialysis_schedule' GROUP BY index_name HAVING COUNT(*)=3 AND GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,schedule_date,status' AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0),'SELECT 1',IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dialysis_schedule' AND index_name='idx_schedule_patient_date'),'ALTER TABLE dialysis_schedule DROP INDEX idx_schedule_patient_date, ADD KEY idx_schedule_patient_date(patient_id,schedule_date,status)','ALTER TABLE dialysis_schedule ADD KEY idx_schedule_patient_date(patient_id,schedule_date,status)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='health_event' GROUP BY index_name HAVING COUNT(*)=3 AND GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,event_date,event_time' AND MIN(non_unique)=1 AND SUM(sub_part IS NOT NULL)=0),'SELECT 1',IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='health_event' AND index_name='idx_event_patient_date'),'ALTER TABLE health_event DROP INDEX idx_event_patient_date, ADD KEY idx_event_patient_date(patient_id,event_date,event_time)','ALTER TABLE health_event ADD KEY idx_event_patient_date(patient_id,event_date,event_time)'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 放宽 file_path 可空，原有字符集、排序规则、注释与所有附件内容保持不变；预检已排除非 NULL 默认值和生成列。
SELECT CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_COMMENT,is_nullable INTO @cp57_charset,@cp57_collation,@cp57_comment,@cp57_nullable
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record_attachment' AND column_name='file_path';
SET @ddl=IF(@cp57_nullable='YES','SELECT 1',CONCAT('ALTER TABLE medical_record_attachment MODIFY COLUMN file_path VARCHAR(500) CHARACTER SET ',@cp57_charset,' COLLATE ',@cp57_collation,' NULL DEFAULT NULL COMMENT ',IF(FIND_IN_SET('NO_BACKSLASH_ESCAPES',@@SESSION.sql_mode),CONCAT(CHAR(39),REPLACE(@cp57_comment,CHAR(39),CONCAT(CHAR(39),CHAR(39))),CHAR(39)),QUOTE(@cp57_comment))));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 标准 5.7 无法 PREPARE CREATE TRIGGER；使用 mysql 客户端 DELIMITER，执行失败必须立即停止。
-- 同名触发器已在最前面校验完整正文、表、时机与事件。只替换本脚本的触发器，保留其他触发器。
DELIMITER $$
DROP TRIGGER IF EXISTS cp57_revision_bi$$
CREATE TRIGGER cp57_revision_bi BEFORE INSERT ON care_plan_revision
FOR EACH ROW BEGIN
  IF NEW.revision_no <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan revision_no must be positive';
  END IF;
END$$
DROP TRIGGER IF EXISTS cp57_revision_bu$$
CREATE TRIGGER cp57_revision_bu BEFORE UPDATE ON care_plan_revision
FOR EACH ROW BEGIN
  IF NEW.revision_no <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan revision_no must be positive';
  END IF;
END$$
DROP TRIGGER IF EXISTS cp57_action_bi$$
CREATE TRIGGER cp57_action_bi BEFORE INSERT ON care_plan_action
FOR EACH ROW BEGIN
  IF NEW.ordinal NOT BETWEEN 1 AND 50 OR NEW.lock_version < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan ordinal/version outside allowed range';
  END IF;
END$$
DROP TRIGGER IF EXISTS cp57_action_bu$$
CREATE TRIGGER cp57_action_bu BEFORE UPDATE ON care_plan_action
FOR EACH ROW BEGIN
  IF NEW.ordinal NOT BETWEEN 1 AND 50 OR NEW.lock_version < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan ordinal/version outside allowed range';
  END IF;
END$$
DROP TRIGGER IF EXISTS cp57_notification_bi$$
CREATE TRIGGER cp57_notification_bi BEFORE INSERT ON care_plan_notification
FOR EACH ROW BEGIN
  IF NEW.attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(NEW.dispatch_key) = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan notification attempts/dispatch invalid';
  END IF;
END$$
DROP TRIGGER IF EXISTS cp57_notification_bu$$
CREATE TRIGGER cp57_notification_bu BEFORE UPDATE ON care_plan_notification
FOR EACH ROW BEGIN
  IF NEW.attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(NEW.dispatch_key) = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan notification attempts/dispatch invalid';
  END IF;
END$$
DROP TRIGGER IF EXISTS cp57_command_bi$$
CREATE TRIGGER cp57_command_bi BEFORE INSERT ON care_plan_command
FOR EACH ROW BEGIN
  IF NEW.expected_version < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan command version must be nonnegative';
  END IF;
END$$
DROP TRIGGER IF EXISTS cp57_command_bu$$
CREATE TRIGGER cp57_command_bu BEFORE UPDATE ON care_plan_command
FOR EACH ROW BEGIN
  IF NEW.expected_version < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Care plan command version must be nonnegative';
  END IF;
END$$
DELIMITER ;

-- 成功后应为 8 个校验触发器；另核对三条计划外键、唯一范围、四个索引与附件可空性。
SELECT trigger_name,event_object_table,event_manipulation,action_timing FROM information_schema.triggers
WHERE trigger_schema=DATABASE() AND trigger_name IN ('cp57_revision_bi','cp57_revision_bu','cp57_action_bi','cp57_action_bu','cp57_notification_bi','cp57_notification_bu','cp57_command_bi','cp57_command_bu') ORDER BY trigger_name;
