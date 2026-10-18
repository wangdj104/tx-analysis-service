-- 中文操作说明：导入时原始事实的独立 SQL 交付说明（执行前请完整阅读）。
-- 适用版本：MySQL 5.7.18；本组脚本不据此宣称兼容其他版本或 MariaDB。
-- 目标数据库：操作者必须确认连接环境和实际健康数据库名，用 --database=实际库名明确选择。
-- 核对 01/03 输出的 selected_schema 与预定目标一致；不得根据表数量猜测库名或已部署状态。
-- 版本选择：同一数据库只选 root 或 cn 中与部署对应的一套脚本，不要把两套都执行。
-- 既有库步骤：停写并备份后，先单独执行只读 01，再依据 import_facts_state 决定下一步。
-- 01 预期输出：EXACT_OLD 才可经单独批准执行 02，随后单独执行只读 03；
-- FULLY_INSTALLED 表示跳过 02，直接执行 03；PARTIAL_OR_UNKNOWN 表示停止并核对差异。
-- 03 预期输出：import_facts_state 应为 FULLY_INSTALLED；否则停止，不得盲目重跑写脚本。
-- 新库路径：fresh init 仅用于已确认的空库，与既有库升级路径二选一；不能在 02 后追加执行。
-- 执行约束：每个文件使用独立、遇错即停的批处理调用；禁止 --force 或继续忽略错误的包装器。
-- 连接参数请按批准的实际环境填写；沿用下方 --password 交互输入密码，不在脚本内保存凭据。
-- 备份与维护：停止所有写入，取得备份并确认恢复点和恢复方法；检查权限、同表触发器、磁盘和表大小。
-- DDL 会隐式提交，可能重建表或等待元数据锁；不承诺在线、零停机、事务回滚或固定耗时。
-- 停止条件：库名/版本不符、结构未知或不完整、前置条件失败、任何报错/非零退出均须立即停止。
-- 恢复提示：保持停写并人工核对部分变更，选择审核后的备份恢复或向前修复；未经核验不要恢复写流量。
-- 不得把时间列缩回 10 位，或删除已保留的原始事实/其字段来模拟回滚，避免截断或丢失数据。
-- 验证状态：原生 MySQL 5.7.18 执行尚未验证；源码/本地检查不能替代操作者的原生验证，部署启用前必须核验。
-- 本文件用途：只读检查既有库，不执行 DDL/DML；查看分类、目标库/版本/字符集和结构明细。
-- 本文件完成后：先人工确认分类与目标库，再选择 02 或 03；分类不能替代对实际环境的确认。
-- 以下保留原有英文说明和完整 SQL 正文。
-- Immutable import facts source contract for MySQL 5.7.18. Native execution remains user-owned and unverified.
-- Existing database sequence: 01 read-only precheck -> separately approved 02 upgrade -> 03 read-only postcheck.
-- Stop writers; obtain a backup; inspect privileges, same-table triggers, table sizes, disk and metadata-lock risk.
-- Run each file in a separate stop-on-error batch invocation; never use --force or a continue-on-error wrapper:
-- mysql --no-defaults --batch --default-character-set=utf8mb4 --host=HOST --port=PORT --user=USER --password --database=DB < SCRIPT.sql
-- Use approved connection values and interactive password entry; no credentials are stored here.
-- EXACT_OLD is the only upgrade input. FULLY_INSTALLED means skip 02 entirely. PARTIAL_OR_UNKNOWN means stop.
-- DDL implicitly commits and may rebuild/wait on metadata locks. No online/atomic rollback or duration promise.
-- Never shrink time to 10 or drop retained facts as rollback. Choose a reviewed restore/forward-fix with writers stopped.
-- SELECT-only. Full metadata visibility is required; the user must inspect any additional same-table triggers.
SELECT CASE WHEN (
  DATABASE() IS NOT NULL
  AND (VERSION()='5.7.18' OR VERSION() LIKE '5.7.18-%') AND LOWER(VERSION()) NOT LIKE '%mariadb%'
  AND @@character_set_client='utf8mb4' AND @@character_set_connection='utf8mb4' AND @@character_set_results='utf8mb4'
  AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record')) = 2
  AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record' ) AND ENGINE='InnoDB' AND TABLE_COLLATION LIKE 'utf8mb4\_%') = 2
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record')) = 38
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND (
    (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='auto_increment')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='patient_id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='user_id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='record_date' AND LOWER(COLUMN_TYPE)='date' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='record_time' AND LOWER(COLUMN_TYPE)='varchar(10)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='measure_type' AND LOWER(COLUMN_TYPE)='varchar(10)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='systolic_bp' AND LOWER(COLUMN_TYPE)='int(11)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='diastolic_bp' AND LOWER(COLUMN_TYPE)='int(11)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='blood_glucose' AND LOWER(COLUMN_TYPE)='decimal(5,2)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='bg_unit' AND LOWER(COLUMN_TYPE)='varchar(10)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'mmol/L' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='measure_period' AND LOWER(COLUMN_TYPE)='varchar(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='source_type' AND LOWER(COLUMN_TYPE)='varchar(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'MANUAL' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='source_external_id' AND LOWER(COLUMN_TYPE)='varchar(120)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='verification_status' AND LOWER(COLUMN_TYPE)='varchar(30)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'VERIFIED' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='remark' AND LOWER(COLUMN_TYPE)='varchar(500)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='created_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'CURRENT_TIMESTAMP' AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='updated_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'CURRENT_TIMESTAMP' AND LOWER(EXTRA)='on update current_timestamp')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='auto_increment')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='patient_name' AND LOWER(COLUMN_TYPE)='varchar(50)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='patient_id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='user_id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='record_date' AND LOWER(COLUMN_TYPE)='date' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='record_type' AND LOWER(COLUMN_TYPE)='varchar(30)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='hospital_name' AND LOWER(COLUMN_TYPE)='varchar(100)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='dept_name' AND LOWER(COLUMN_TYPE)='varchar(100)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='doctor_name' AND LOWER(COLUMN_TYPE)='varchar(50)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='ai_raw_result' AND LOWER(COLUMN_TYPE)='text' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='source_type' AND LOWER(COLUMN_TYPE)='varchar(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'MANUAL' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='source_external_id' AND LOWER(COLUMN_TYPE)='varchar(120)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='verification_status' AND LOWER(COLUMN_TYPE)='varchar(30)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'VERIFIED' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='confidence_score' AND LOWER(COLUMN_TYPE)='decimal(5,4)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='verified_by' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='verified_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='content_version' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> '1' AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='verification_note' AND LOWER(COLUMN_TYPE)='text' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='remark' AND LOWER(COLUMN_TYPE)='varchar(500)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='created_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'CURRENT_TIMESTAMP' AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='updated_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'CURRENT_TIMESTAMP' AND LOWER(EXTRA)='on update current_timestamp')
  )) = 38
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record')) = 20
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND SUB_PART IS NULL AND INDEX_TYPE='BTREE' AND COLLATION='A' AND (
    (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_patient_date' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_patient_date' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=2 AND COLUMN_NAME='record_date')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_patient_date' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=3 AND COLUMN_NAME='record_time')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_user' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='user_id')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_measure_type' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='measure_type')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='uk_bp_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='uk_bp_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=2 AND COLUMN_NAME='source_type')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='uk_bp_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=3 AND COLUMN_NAME='source_external_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_patient' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_name')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_date' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='record_date')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_type' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='record_type')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_user_id' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='user_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_patient_id' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_review' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_review' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=2 AND COLUMN_NAME='verification_status')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='uk_record_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='uk_record_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=2 AND COLUMN_NAME='source_type')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='uk_record_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=3 AND COLUMN_NAME='source_external_id')
  )) = 20
  AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND (EVENT_OBJECT_TABLE IN ('bp_self_monitor_record','medical_record') OR TRIGGER_NAME IN ('if57_bp_insert_facts','if57_bp_update_facts','if57_medical_insert_facts','if57_medical_update_facts'))) = 0
) THEN 'EXACT_OLD' WHEN (
  DATABASE() IS NOT NULL
  AND (VERSION()='5.7.18' OR VERSION() LIKE '5.7.18-%') AND LOWER(VERSION()) NOT LIKE '%mariadb%'
  AND @@character_set_client='utf8mb4' AND @@character_set_connection='utf8mb4' AND @@character_set_results='utf8mb4'
  AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record')) = 2
  AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record' ) AND ENGINE='InnoDB' AND TABLE_COLLATION LIKE 'utf8mb4\_%') = 2
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record')) = 40
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND (
    (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='auto_increment')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='patient_id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='user_id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='record_date' AND LOWER(COLUMN_TYPE)='date' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='record_time' AND LOWER(COLUMN_TYPE)='varchar(15)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='measure_type' AND LOWER(COLUMN_TYPE)='varchar(10)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='systolic_bp' AND LOWER(COLUMN_TYPE)='int(11)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='diastolic_bp' AND LOWER(COLUMN_TYPE)='int(11)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='blood_glucose' AND LOWER(COLUMN_TYPE)='decimal(5,2)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='bg_unit' AND LOWER(COLUMN_TYPE)='varchar(10)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'mmol/L' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='measure_period' AND LOWER(COLUMN_TYPE)='varchar(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='source_type' AND LOWER(COLUMN_TYPE)='varchar(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'MANUAL' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='source_external_id' AND LOWER(COLUMN_TYPE)='varchar(120)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='verification_status' AND LOWER(COLUMN_TYPE)='varchar(30)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'VERIFIED' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='remark' AND LOWER(COLUMN_TYPE)='varchar(500)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='created_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'CURRENT_TIMESTAMP' AND LOWER(EXTRA)='')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='updated_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'CURRENT_TIMESTAMP' AND LOWER(EXTRA)='on update current_timestamp')
    OR (TABLE_NAME='bp_self_monitor_record' AND COLUMN_NAME='import_facts_json' AND LOWER(COLUMN_TYPE)='text' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='auto_increment')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='patient_name' AND LOWER(COLUMN_TYPE)='varchar(50)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='patient_id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='user_id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='record_date' AND LOWER(COLUMN_TYPE)='date' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='record_type' AND LOWER(COLUMN_TYPE)='varchar(30)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='hospital_name' AND LOWER(COLUMN_TYPE)='varchar(100)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='dept_name' AND LOWER(COLUMN_TYPE)='varchar(100)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='doctor_name' AND LOWER(COLUMN_TYPE)='varchar(50)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='ai_raw_result' AND LOWER(COLUMN_TYPE)='text' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='source_type' AND LOWER(COLUMN_TYPE)='varchar(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'MANUAL' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='source_external_id' AND LOWER(COLUMN_TYPE)='varchar(120)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='verification_status' AND LOWER(COLUMN_TYPE)='varchar(30)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'VERIFIED' AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='confidence_score' AND LOWER(COLUMN_TYPE)='decimal(5,4)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='verified_by' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='verified_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='content_version' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT <=> '1' AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='verification_note' AND LOWER(COLUMN_TYPE)='text' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='remark' AND LOWER(COLUMN_TYPE)='varchar(500)' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='created_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'CURRENT_TIMESTAMP' AND LOWER(EXTRA)='')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='updated_at' AND LOWER(COLUMN_TYPE)='datetime' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> 'CURRENT_TIMESTAMP' AND LOWER(EXTRA)='on update current_timestamp')
    OR (TABLE_NAME='medical_record' AND COLUMN_NAME='import_facts_json' AND LOWER(COLUMN_TYPE)='text' AND IS_NULLABLE='YES' AND COLUMN_DEFAULT <=> NULL AND LOWER(EXTRA)='' AND CHARACTER_SET_NAME='utf8mb4')
  )) = 40
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record')) = 20
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND SUB_PART IS NULL AND INDEX_TYPE='BTREE' AND COLLATION='A' AND (
    (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_patient_date' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_patient_date' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=2 AND COLUMN_NAME='record_date')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_patient_date' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=3 AND COLUMN_NAME='record_time')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_user' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='user_id')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='idx_bp_measure_type' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='measure_type')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='uk_bp_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='uk_bp_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=2 AND COLUMN_NAME='source_type')
    OR (TABLE_NAME='bp_self_monitor_record' AND INDEX_NAME='uk_bp_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=3 AND COLUMN_NAME='source_external_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_patient' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_name')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_date' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='record_date')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_type' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='record_type')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_user_id' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='user_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_patient_id' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_review' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='idx_record_review' AND NON_UNIQUE=1 AND SEQ_IN_INDEX=2 AND COLUMN_NAME='verification_status')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='uk_record_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND COLUMN_NAME='patient_id')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='uk_record_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=2 AND COLUMN_NAME='source_type')
    OR (TABLE_NAME='medical_record' AND INDEX_NAME='uk_record_external' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=3 AND COLUMN_NAME='source_external_id')
  )) = 20
  AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME IN ('if57_bp_insert_facts','if57_bp_update_facts','if57_medical_insert_facts','if57_medical_update_facts')) = 4
  AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND (
    (TRIGGER_NAME='if57_bp_insert_facts' AND EVENT_OBJECT_TABLE='bp_self_monitor_record' AND EVENT_MANIPULATION='INSERT' AND ACTION_TIMING='BEFORE' AND ACTION_ORIENTATION='ROW' AND ACTION_CONDITION IS NULL AND CHARACTER_SET_CLIENT='utf8mb4' AND COLLATION_CONNECTION LIKE 'utf8mb4\_%' AND DATABASE_COLLATION LIKE 'utf8mb4\_%' AND BINARY ACTION_STATEMENT=BINARY 'BEGIN
  IF NEW.import_facts_json IS NOT NULL THEN
    IF OCTET_LENGTH(NEW.import_facts_json) > 4096 OR JSON_VALID(NEW.import_facts_json) <> 1 THEN
      SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''IF57_INVALID_IMPORT_FACTS'';
    END IF;
    IF JSON_TYPE(NEW.import_facts_json) <> ''OBJECT''
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.version'')) <=> ''INTEGER'')
      OR NOT (JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, ''$.version'')) <=> ''1'')
      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, ''$.format'')) <=> BINARY NEW.source_type)
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.timestamps'')) <=> ''OBJECT'')
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.basis'')) <=> ''OBJECT'')
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.readings'')) <=> ''ARRAY'')
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.selectedTimestamp'')) <=> ''STRING'')
      OR NOT (JSON_CONTAINS_PATH(NEW.import_facts_json, ''all'', ''$.version'', ''$.format'', ''$.kind'', ''$.timestamps'', ''$.selectedTimestamp'', ''$.basis'', ''$.readings'') <=> 1)
      OR NEW.source_external_id IS NULL OR CHAR_LENGTH(TRIM(NEW.source_external_id)) = 0
      OR NOT (BINARY NEW.source_type <=> BINARY ''FHIR'') AND NOT (BINARY NEW.source_type <=> BINARY ''DEVICE'')
      OR NOT (JSON_LENGTH(NEW.import_facts_json) <=> IF(BINARY NEW.source_type = BINARY ''DEVICE'', 8, 7))
      OR (BINARY NEW.source_type = BINARY ''DEVICE'' AND NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.deviceUnit'')) <=> ''OBJECT''))
      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, ''$.kind'')) <=> BINARY NEW.measure_type)
      OR NOT (BINARY NEW.measure_type <=> BINARY ''BP'') AND NOT (BINARY NEW.measure_type <=> BINARY ''BG'') AND NOT (BINARY NEW.measure_type <=> BINARY ''BP_BG'')
      OR NOT (JSON_LENGTH(JSON_EXTRACT(NEW.import_facts_json, ''$.readings'')) <=> IF(BINARY NEW.measure_type = BINARY ''BP'', 2, IF(BINARY NEW.measure_type = BINARY ''BG'', 1, 3))) THEN
      SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''IF57_INVALID_IMPORT_FACTS'';
    END IF;
  END IF;
END')
    OR (TRIGGER_NAME='if57_bp_update_facts' AND EVENT_OBJECT_TABLE='bp_self_monitor_record' AND EVENT_MANIPULATION='UPDATE' AND ACTION_TIMING='BEFORE' AND ACTION_ORIENTATION='ROW' AND ACTION_CONDITION IS NULL AND CHARACTER_SET_CLIENT='utf8mb4' AND COLLATION_CONNECTION LIKE 'utf8mb4\_%' AND DATABASE_COLLATION LIKE 'utf8mb4\_%' AND BINARY ACTION_STATEMENT=BINARY 'BEGIN
  IF NOT ((BINARY NEW.import_facts_json <=> BINARY OLD.import_facts_json)
      AND (OCTET_LENGTH(NEW.import_facts_json) <=> OCTET_LENGTH(OLD.import_facts_json))) THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''IF57_ORIGINAL_BINDING_IMMUTABLE'';
  END IF;
  IF OLD.import_facts_json IS NOT NULL AND (
      NOT (NEW.id <=> OLD.id) OR NOT (NEW.patient_id <=> OLD.patient_id) OR NOT (NEW.user_id <=> OLD.user_id)
      OR NOT ((BINARY NEW.source_type <=> BINARY OLD.source_type) AND (OCTET_LENGTH(NEW.source_type) <=> OCTET_LENGTH(OLD.source_type)))
      OR NOT ((BINARY NEW.source_external_id <=> BINARY OLD.source_external_id) AND (OCTET_LENGTH(NEW.source_external_id) <=> OCTET_LENGTH(OLD.source_external_id)))) THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''IF57_ORIGINAL_BINDING_IMMUTABLE'';
  END IF;
END')
    OR (TRIGGER_NAME='if57_medical_insert_facts' AND EVENT_OBJECT_TABLE='medical_record' AND EVENT_MANIPULATION='INSERT' AND ACTION_TIMING='BEFORE' AND ACTION_ORIENTATION='ROW' AND ACTION_CONDITION IS NULL AND CHARACTER_SET_CLIENT='utf8mb4' AND COLLATION_CONNECTION LIKE 'utf8mb4\_%' AND DATABASE_COLLATION LIKE 'utf8mb4\_%' AND BINARY ACTION_STATEMENT=BINARY 'BEGIN
  IF NEW.import_facts_json IS NOT NULL THEN
    IF OCTET_LENGTH(NEW.import_facts_json) > 4096 OR JSON_VALID(NEW.import_facts_json) <> 1 THEN
      SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''IF57_INVALID_IMPORT_FACTS'';
    END IF;
    IF JSON_TYPE(NEW.import_facts_json) <> ''OBJECT''
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.version'')) <=> ''INTEGER'')
      OR NOT (JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, ''$.version'')) <=> ''1'')
      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, ''$.format'')) <=> BINARY NEW.source_type)
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.timestamps'')) <=> ''OBJECT'')
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.basis'')) <=> ''OBJECT'')
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.readings'')) <=> ''ARRAY'')
      OR NOT (JSON_TYPE(JSON_EXTRACT(NEW.import_facts_json, ''$.selectedTimestamp'')) <=> ''STRING'')
      OR NOT (JSON_CONTAINS_PATH(NEW.import_facts_json, ''all'', ''$.version'', ''$.format'', ''$.kind'', ''$.timestamps'', ''$.selectedTimestamp'', ''$.basis'', ''$.readings'') <=> 1)
      OR NEW.source_external_id IS NULL OR CHAR_LENGTH(TRIM(NEW.source_external_id)) = 0
      OR NOT (BINARY NEW.source_type <=> BINARY ''FHIR'')
      OR NOT (JSON_LENGTH(NEW.import_facts_json) <=> 7)
      OR NOT (BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.import_facts_json, ''$.kind'')) <=> BINARY ''LAB'')
      OR NOT (JSON_LENGTH(JSON_EXTRACT(NEW.import_facts_json, ''$.readings'')) <=> 1) THEN
      SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''IF57_INVALID_IMPORT_FACTS'';
    END IF;
  END IF;
END')
    OR (TRIGGER_NAME='if57_medical_update_facts' AND EVENT_OBJECT_TABLE='medical_record' AND EVENT_MANIPULATION='UPDATE' AND ACTION_TIMING='BEFORE' AND ACTION_ORIENTATION='ROW' AND ACTION_CONDITION IS NULL AND CHARACTER_SET_CLIENT='utf8mb4' AND COLLATION_CONNECTION LIKE 'utf8mb4\_%' AND DATABASE_COLLATION LIKE 'utf8mb4\_%' AND BINARY ACTION_STATEMENT=BINARY 'BEGIN
  IF NOT ((BINARY NEW.import_facts_json <=> BINARY OLD.import_facts_json)
      AND (OCTET_LENGTH(NEW.import_facts_json) <=> OCTET_LENGTH(OLD.import_facts_json))) THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''IF57_ORIGINAL_BINDING_IMMUTABLE'';
  END IF;
  IF OLD.import_facts_json IS NOT NULL AND (
      NOT (NEW.id <=> OLD.id) OR NOT (NEW.patient_id <=> OLD.patient_id) OR NOT (NEW.user_id <=> OLD.user_id)
      OR NOT ((BINARY NEW.source_type <=> BINARY OLD.source_type) AND (OCTET_LENGTH(NEW.source_type) <=> OCTET_LENGTH(OLD.source_type)))
      OR NOT ((BINARY NEW.source_external_id <=> BINARY OLD.source_external_id) AND (OCTET_LENGTH(NEW.source_external_id) <=> OCTET_LENGTH(OLD.source_external_id)))) THEN
    SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''IF57_ORIGINAL_BINDING_IMMUTABLE'';
  END IF;
END')
  )) = 4
) THEN 'FULLY_INSTALLED' ELSE 'PARTIAL_OR_UNKNOWN' END AS import_facts_state;

SELECT DATABASE() AS selected_schema, VERSION() AS server_version, @@character_set_client AS client_charset, @@character_set_connection AS connection_charset, @@character_set_results AS results_charset;
SELECT TABLE_NAME,ENGINE,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record') LIMIT 3;
SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,CHARACTER_SET_NAME,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record') ORDER BY TABLE_NAME,ORDINAL_POSITION LIMIT 64;
SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,INDEX_TYPE,COLLATION FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('bp_self_monitor_record','medical_record') ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX LIMIT 40;
SELECT TRIGGER_NAME,EVENT_OBJECT_TABLE,EVENT_MANIPULATION,ACTION_TIMING,ACTION_STATEMENT,CHARACTER_SET_CLIENT,COLLATION_CONNECTION,DATABASE_COLLATION FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND (EVENT_OBJECT_TABLE IN ('bp_self_monitor_record','medical_record') OR TRIGGER_NAME IN ('if57_bp_insert_facts','if57_bp_update_facts','if57_medical_insert_facts','if57_medical_update_facts')) ORDER BY TRIGGER_NAME LIMIT 32;
