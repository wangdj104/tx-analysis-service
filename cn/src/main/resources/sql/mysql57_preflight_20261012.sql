-- 用途：本次 MySQL 5.7.18 定向升级前的只读核对，保存输出作为升级后对比基线。
-- 前提：选择正确应用库并核对实际部署版本；此检查不是完整备份，也不代表已连接或验证生产库。
-- 重复执行：只查询结构与聚合数量，不修改角色、患者数据、表、索引、过程或服务设置。
-- 风险：预检输出不包含逐条患者身份；仍应在受控环境保存，发现缺表、重复或未知定义须停止升级。
-- 顺序：本文件 -> mysql57_schema_upgrade_20261012.sql -> mysql57_postcheck_20261012.sql。

SELECT DATABASE() AS selected_schema, VERSION() AS server_version,
       @@sql_mode AS sql_mode, @@explicit_defaults_for_timestamp AS explicit_defaults_for_timestamp;
SHOW VARIABLES WHERE Variable_name IN ('innodb_large_prefix','innodb_file_format','innodb_default_row_format','log_bin','log_bin_trust_function_creators');
-- 5.7 的宽索引要求 Barracuda、DYNAMIC/COMPRESSED、innodb_large_prefix=ON；不截短现有唯一键。
-- log_bin=ON 且 log_bin_trust_function_creators=OFF 时先请 DBA 审核触发器权限/策略；升级脚本不会放宽服务器安全设置。
SELECT table_name,engine,row_format,table_collation
FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name;
SELECT table_name,column_name,column_type,is_nullable,column_default,extra,character_set_name,collation_name
FROM information_schema.columns WHERE table_schema=DATABASE()
AND table_name IN ('doctor_care_plan','care_plan_revision','care_plan_action','care_plan_event','care_plan_evidence','care_nurse_assignment','care_plan_notification','care_plan_command','patient','medication_stock','medical_record_attachment')
ORDER BY table_name,ordinal_position;
SELECT table_name,index_name,non_unique,seq_in_index,column_name,sub_part,index_type
FROM information_schema.statistics WHERE table_schema=DATABASE()
AND table_name IN ('patient','medication_stock','alert_record','medication_intake','dialysis_schedule','health_event','doctor_care_plan','care_plan_revision','care_plan_action','care_plan_event','care_plan_evidence','care_nurse_assignment','care_plan_notification','care_plan_command')
ORDER BY table_name,index_name,seq_in_index;
SELECT table_name,constraint_name,column_name,referenced_table_name,referenced_column_name
FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND referenced_table_name IS NOT NULL
AND table_name IN ('doctor_care_plan','care_plan_revision','care_plan_action','care_plan_event','care_plan_evidence','care_nurse_assignment','care_plan_notification','care_plan_command')
ORDER BY table_name,constraint_name,ordinal_position;
SELECT trigger_name,event_object_table,event_manipulation,action_timing,action_statement
FROM information_schema.triggers WHERE trigger_schema=DATABASE()
AND event_object_table IN ('care_plan_revision','care_plan_action','care_plan_notification','care_plan_command')
ORDER BY trigger_name;
SELECT routine_name,routine_type,SHA2(routine_definition,256) AS definition_sha256
FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name='cp57_upgrade_preflight';

-- 所有阻断计数应为 0；本查询不自动删除、合并或重分配任何记录。
SELECT 'patient_null_owner_rows' AS check_name,COUNT(*) AS problem_count FROM patient WHERE user_id IS NULL;
SELECT 'patient_owner_name_duplicate_groups' AS check_name,COUNT(*) AS problem_count FROM (
 SELECT user_id,name FROM patient GROUP BY user_id,name HAVING COUNT(*)>1
) duplicate_groups;
SELECT 'stock_patient_medication_duplicate_groups' AS check_name,COUNT(*) AS problem_count FROM (
 SELECT patient_id,medication_id FROM medication_stock GROUP BY patient_id,medication_id HAVING COUNT(*)>1
) duplicate_groups;
SELECT 'stock_ownership_mismatch_rows' AS check_name,COUNT(*) AS problem_count
FROM medication_stock s LEFT JOIN medication m ON m.id=s.medication_id LEFT JOIN patient p ON p.id=s.patient_id
WHERE p.id IS NULL OR m.id IS NULL OR m.patient_id IS NULL OR m.patient_id<>s.patient_id;

-- 保存旧计划数量/状态分布基线；升级后的静态查询不能独立证明历史内容未变，完整证明须对比备份/基线。
SELECT status,COUNT(*) AS plan_count FROM doctor_care_plan GROUP BY status ORDER BY status;
SELECT COUNT(*) AS patient_rows FROM patient;
SELECT COUNT(*) AS medication_stock_rows FROM medication_stock;
SELECT COUNT(*) AS attachment_rows FROM medical_record_attachment;
-- 明确保留的历史差异：现有九张表字符集和 unit 默认值不属于本次修复；TIMESTAMP NULL 差异应结合服务器参数解释。
SELECT table_name,table_collation FROM information_schema.tables WHERE table_schema=DATABASE()
AND table_name IN ('health_indicator','medical_record','medical_record_attachment','medical_record_item','medication','medication_log','medication_reminder','patient_clinical','user_preference') ORDER BY table_name;
SELECT table_name,column_name,column_type,is_nullable,column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE()
AND ((table_name='medication_stock' AND column_name='unit') OR (table_name='ai_analysis_record' AND column_name IN ('created_at','updated_at')))
ORDER BY table_name,column_name;
-- 仅列出惯例角色/菜单，存在不等于已为账号分配护士身份或患者 CARE_PLAN 授权。
SELECT role_code,status FROM sys_role WHERE role_code IN ('nurse','admin','doctor','clinician');
SELECT menu_code,permission,status FROM sys_menu WHERE menu_code IN ('care-plan-nursing','care-plan-nurse-assignments');

-- 以下身份须先人工确认：启用 admin 恰好 1 个、patient-profile 父菜单恰好 1 个且 id=8，目标菜单不重复。
SELECT 'active_admin_roles' AS check_name,COUNT(*) AS observed_count FROM sys_role WHERE role_code='admin' AND status=1 AND deleted=0;
SELECT id,menu_code,permission,menu_path,menu_type,status FROM sys_menu
WHERE id=8 OR menu_code IN ('patient-profile','care-plan-nursing','care-plan-nurse-assignments') ORDER BY id;
SELECT role_code,COUNT(*) AS identity_count FROM sys_role WHERE role_code IN ('admin','nurse') GROUP BY role_code;
SELECT menu_code,COUNT(*) AS identity_count FROM sys_menu WHERE menu_code IN ('patient-profile','care-plan-nursing','care-plan-nurse-assignments') GROUP BY menu_code;
