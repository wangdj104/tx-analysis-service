-- 用途：本次 MySQL 5.7.18 定向升级后的只读结构与完整性核对；与预检输出和完整备份比较。
-- 前提：mysql57_schema_upgrade_20261012.sql 无错误结束；继续停止写入直至维护负责人完成核验。
-- 重复执行：仅查询，不插入测试患者、不发通知、不更改或清理任何业务内容。
-- 风险：缺表/缺列将直接报错；仅看数量不能证明全部历史内容未变，不能替代完整备份恢复和应用隔离验收。
-- 顺序：mysql57_preflight_20261012.sql -> mysql57_schema_upgrade_20261012.sql -> 本文件。

SELECT DATABASE() AS selected_schema, VERSION() AS server_version;
-- 应有 7 张 InnoDB 表；字段、索引及外键完整定义仍须逐项核对，不能仅看名称或数量。
SELECT table_name,engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE()
AND table_name IN ('care_plan_revision','care_plan_action','care_plan_event','care_plan_evidence','care_nurse_assignment','care_plan_notification','care_plan_command') ORDER BY table_name;
SELECT column_name,column_type,is_nullable,column_default,datetime_precision FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='doctor_care_plan'
AND column_name IN ('workflow_version','lifecycle','current_revision_id','draft_revision_id','legacy_source_id','lock_version','closed_at','cancelled_at','cancel_reason') ORDER BY ordinal_position;
SELECT k.table_name,k.constraint_name,k.column_name,k.referenced_table_name,k.referenced_column_name,r.update_rule,r.delete_rule
FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r
ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name
WHERE k.constraint_schema=DATABASE() AND k.table_name IN ('doctor_care_plan','care_plan_revision','care_plan_action','care_plan_event','care_plan_evidence','care_nurse_assignment','care_plan_notification','care_plan_command')
ORDER BY k.table_name,k.constraint_name,k.ordinal_position;
-- doctor_care_plan 应有 current_revision/draft_revision/legacy_source 三条目标外键，全部 RESTRICT/NO ACTION，绝不级联删除历史。
SELECT table_name,index_name,MIN(non_unique) AS non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index) AS ordered_columns,SUM(sub_part IS NOT NULL) AS prefix_parts
FROM information_schema.statistics WHERE table_schema=DATABASE()
AND table_name IN ('patient','medication_stock','alert_record','medication_intake','dialysis_schedule','health_event','care_plan_revision','care_plan_action','care_plan_event','care_plan_evidence','care_nurse_assignment','care_plan_notification','care_plan_command')
GROUP BY table_name,index_name ORDER BY table_name,index_name;
-- 目标唯一范围 patient(user_id,name)、medication_stock(patient_id,medication_id)；不再有 patient.uk_name(name)。
-- 二级索引为 alert_record(patient_id,dedupe_key,status)、medication_intake(patient_id,scheduled_at,status)、
-- dialysis_schedule(patient_id,schedule_date,status)、health_event(patient_id,event_date,event_time)，均为完整列而非前缀。
SELECT column_type,is_nullable,column_default,character_set_name,collation_name,column_comment
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record_attachment' AND column_name='file_path';
-- file_path 应为可空 VARCHAR(500)，字符集、排序规则与注释应和预检/备份相同。
SELECT trigger_name,event_object_table,event_manipulation,action_timing,action_statement
FROM information_schema.triggers WHERE trigger_schema=DATABASE()
AND trigger_name IN ('cp57_revision_bi','cp57_revision_bu','cp57_action_bi','cp57_action_bu','cp57_notification_bi','cp57_notification_bu','cp57_command_bi','cp57_command_bu') ORDER BY trigger_name;
-- 应有以上 8 个完整校验正文的 BEFORE INSERT/UPDATE 触发器；5.7 CHECK 名称存在不表示实际执行。
SELECT COUNT(*) AS leftover_preflight_routines FROM information_schema.routines
WHERE routine_schema=DATABASE() AND routine_name='cp57_upgrade_preflight' AND routine_type='PROCEDURE';

-- 以下问题数量全部应为 0。
SELECT 'invalid_revision' AS check_name,COUNT(*) AS problem_count FROM care_plan_revision WHERE revision_no<=0
UNION ALL SELECT 'invalid_action',COUNT(*) FROM care_plan_action WHERE ordinal NOT BETWEEN 1 AND 50 OR lock_version<0
UNION ALL SELECT 'invalid_notification',COUNT(*) FROM care_plan_notification WHERE attempt_count NOT BETWEEN 0 AND 3 OR CHAR_LENGTH(dispatch_key)=0
UNION ALL SELECT 'invalid_command',COUNT(*) FROM care_plan_command WHERE expected_version<0
UNION ALL SELECT 'plan_current_revision_orphan',COUNT(*) FROM doctor_care_plan p LEFT JOIN care_plan_revision r ON r.id=p.current_revision_id WHERE p.current_revision_id IS NOT NULL AND r.id IS NULL
UNION ALL SELECT 'plan_draft_revision_orphan',COUNT(*) FROM doctor_care_plan p LEFT JOIN care_plan_revision r ON r.id=p.draft_revision_id WHERE p.draft_revision_id IS NOT NULL AND r.id IS NULL
UNION ALL SELECT 'plan_legacy_source_orphan',COUNT(*) FROM doctor_care_plan p LEFT JOIN doctor_care_plan old_plan ON old_plan.id=p.legacy_source_id WHERE p.legacy_source_id IS NOT NULL AND old_plan.id IS NULL;
SELECT 'patient_owner_name_duplicate_groups' AS check_name,COUNT(*) AS problem_count FROM (
 SELECT user_id,name FROM patient GROUP BY user_id,name HAVING COUNT(*)>1
) duplicate_groups;
SELECT 'stock_patient_medication_duplicate_groups' AS check_name,COUNT(*) AS problem_count FROM (
 SELECT patient_id,medication_id FROM medication_stock GROUP BY patient_id,medication_id HAVING COUNT(*)>1
) duplicate_groups;

-- 与预检状态分布/行数基线比较。刚补协作列的旧计划应 workflow_version=0、lifecycle=NULL；
-- 已经协作化的计划必须保留原值，不能把本查询作为批量重置许可。
SELECT status,COUNT(*) AS plan_count FROM doctor_care_plan GROUP BY status ORDER BY status;
SELECT workflow_version,lifecycle,COUNT(*) AS plan_count FROM doctor_care_plan GROUP BY workflow_version,lifecycle ORDER BY workflow_version,lifecycle;
SELECT COUNT(*) AS patient_rows FROM patient;
SELECT COUNT(*) AS medication_stock_rows FROM medication_stock;
SELECT COUNT(*) AS attachment_rows FROM medical_record_attachment;
SELECT role_code,status FROM sys_role WHERE role_code IN ('nurse','admin','doctor');
SELECT menu_code,permission,status FROM sys_menu WHERE menu_code IN ('care-plan-nursing','care-plan-nurse-assignments');

-- 应有 nurse→护理队列及 admin→护理分配菜单关系；这不表示任何用户角色或患者权限已授予。
SELECT r.role_code,m.menu_code,COUNT(*) AS binding_count
FROM sys_role_menu rm JOIN sys_role r ON r.id=rm.role_id JOIN sys_menu m ON m.id=rm.menu_id
WHERE (r.role_code='nurse' AND m.menu_code='care-plan-nursing')
   OR (r.role_code='admin' AND m.menu_code='care-plan-nurse-assignments')
GROUP BY r.role_code,m.menu_code ORDER BY r.role_code,m.menu_code;
