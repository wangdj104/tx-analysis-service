-- 健康平台人工升级入口 01：只读分流；适用用户手工选择并核对的应用库（实际 txdata 结构未知）。
-- 版本：B 20261014，目标原生 MySQL 5.7.18；本文件只读元数据，不连接或代替用户验证生产库。
-- 预期：先看到正确 selected_schema/server_version，再依据实际对象进入下面唯一适用的阶段；表数仅作分流，不证明完整签名。
-- 停止条件：库名不符、非目标版本、任一 SQL 错误、未知/部分签名、阻断计数非零、备份不可恢复。禁止 --force。
-- 执行前停写，验证完整数据库与附件存储备份能在隔离库恢复，并保存历史数量/状态基线；限定家庭归档不是全库恢复工具。
-- MySQL5.7 DDL 非原子、隐式提交。任何阶段中断都停止；先核对已经提交的对象与备份，由操作者决定恢复，不能盲目重跑或删除对象。
-- 下列所有 01/03 只读脚本均需人工完整查看输出；表/列/索引/FK/触发器正文哈希与已发布定义必须一致，数量相同不是签名相同。
--
-- 既有库阶段 C（仅确实缺失 cp57，且旧 preflight/upgrade 的准入核对全部通过）：
-- 01 mysql57_preflight_20261012.sql → 02 mysql57_schema_upgrade_20261012.sql → 03 mysql57_postcheck_20261012.sql。
-- 若 cp57 已存在，仅读原 postcheck 对照定义/历史基线；任何部分/未知对象停止，不重复 02。保留三份原文件精确字节。
--
-- A_ABSENT 候选（A 的表、增量列、保留索引/FK、触发器、过程均完全不存在，不能只看一张表）：
-- 01 alert_workflow_preflight_20261013.sql → 02 alert_workflow_upgrade_20261013.sql → 03 alert_workflow_postcheck_20261013.sql。
-- 原 A preflight 是单次安装门禁；任何缺前提、保留名冲突或错误立即停止，不覆盖/修复未知历史。
-- A_PRESENT 候选：先只读 alert_workflow_postcheck_20261013.sql，并逐项对照已发布 A 的完整结构/触发器正文哈希。
-- 只有完整 A 签名及所有 blockers=0、历史基线一致时才进入 B；不要重复 A 的 02。部分 A 或无法核实签名必须停止。
--
-- 既有且已核实 A→B：
-- 01 household_workflow_preflight_20261014.sql → 02 household_workflow_upgrade_20261014.sql → 03 household_workflow_postcheck_20261014.sql。
-- 02 唯一准入：B 完全 ABSENT、prerequisite_errors=0、正确库名和 5.7.18；B 缺失时其增量 metadata/row_errors 可非零，不将 ABSENT 当 READY。
-- 03 预期 household_status=READY 且 prerequisite_errors=metadata_errors=row_errors=0；否则停止，保持四开关关闭。
-- B_PRESENT 候选：只读 B postcheck；仅 READY 且全部零错误视为已安装，无需再升级；INCOMPATIBLE/部分/未知签名均停止。
--
-- 独立 fresh 初始化：只对经核实的新建空库执行 init.sql，再只读 C/A/B 三个 postcheck；任何已有业务库禁止执行 init.sql。
-- 所有路径不激活开关、不推断排程时区、不改权限/服务器安全设置、不调用 provider。原生 MySQL5.7.18/实际恢复仍 NOT_RUN。

SELECT DATABASE() AS selected_schema, VERSION() AS server_version, @@sql_mode AS sql_mode;
SELECT CASE WHEN DATABASE() IS NULL THEN 'STOP_SELECT_AND_VERIFY_SCHEMA'
            WHEN SUBSTRING_INDEX(VERSION(),'-',1)<>'5.7.18' OR VERSION() LIKE '%MariaDB%' THEN 'STOP_UNSUPPORTED_VERSION'
            ELSE 'REVIEW_ACTUAL_SIGNATURES_BEFORE_ANY_02' END AS next_step;

-- 这里只列元数据，不输出患者正文、账号凭据或逐条业务记录。
SELECT table_name,engine,row_format,table_collation
FROM information_schema.tables WHERE BINARY table_schema=BINARY DATABASE() ORDER BY table_name;
SELECT table_name,column_name,column_type,is_nullable,column_default,extra,ordinal_position,collation_name
FROM information_schema.columns WHERE BINARY table_schema=BINARY DATABASE()
AND (table_name IN ('doctor_care_plan','care_plan_revision','care_plan_action','care_plan_event','care_plan_evidence','care_nurse_assignment','care_plan_notification','care_plan_command','alert_record','alert_event','alert_source_identity','alert_source_revision','alert_source_link','alert_evidence','alert_command','measurement_command','alert_notification') OR LEFT(table_name,11)='medication_' OR LEFT(table_name,6)='stock_' OR LEFT(table_name,17)='family_assistance' OR LEFT(table_name,20)='emergency_assistance')
ORDER BY table_name,ordinal_position;
SELECT trigger_name,event_object_table,action_timing,event_manipulation,sql_mode,SHA2(action_statement,256) AS body_sha256
FROM information_schema.triggers WHERE BINARY trigger_schema=BINARY DATABASE()
AND (LEFT(trigger_name,5) IN ('cp57_','aw57_') OR LEFT(trigger_name,3)='hh_') ORDER BY trigger_name;
SELECT routine_name,routine_type FROM information_schema.routines WHERE BINARY routine_schema=BINARY DATABASE()
AND (LEFT(routine_name,5) IN ('cp57_','aw57_') OR LEFT(routine_name,3)='hh_') ORDER BY routine_name;

-- presence 只是选择下一项只读检查，绝不是执行 DDL 的授权/签名证明。
SELECT CASE WHEN
 EXISTS(SELECT 1 FROM information_schema.tables WHERE BINARY table_schema=BINARY DATABASE() AND table_name IN ('alert_source_identity','alert_source_revision','alert_source_link','alert_evidence','alert_command','measurement_command','alert_notification'))
 OR EXISTS(SELECT 1 FROM information_schema.columns WHERE BINARY table_schema=BINARY DATABASE() AND ((table_name='alert_record' AND column_name IN ('workflow_version','workflow_state','workflow_mode','cycle_no','source_revision','metric_key','responsible_user_id','due_at','accepted_by','accepted_at','lock_version','current_handling_event_id','current_feedback_event_id','source_review_required','reassignment_required','classification_json','closed_by','closed_at','closure_reason')) OR (table_name='alert_event' AND column_name IN ('event_type','cycle_no','actor_role','actor_relation','entry_mode','occurred_at','recorded_at','payload_json'))))
 OR EXISTS(SELECT 1 FROM information_schema.triggers WHERE BINARY trigger_schema=BINARY DATABASE() AND LEFT(trigger_name,5)='aw57_')
 OR EXISTS(SELECT 1 FROM information_schema.routines WHERE BINARY routine_schema=BINARY DATABASE() AND LEFT(routine_name,5)='aw57_')
 OR EXISTS(SELECT 1 FROM information_schema.statistics WHERE BINARY table_schema=BINARY DATABASE() AND (LEFT(index_name,6)='uk_aw_' OR LEFT(index_name,7)='idx_aw_' OR LEFT(index_name,6)='fk_aw_'))
 OR EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE BINARY constraint_schema=BINARY DATABASE() AND (LEFT(constraint_name,6)='uk_aw_' OR LEFT(constraint_name,7)='idx_aw_' OR LEFT(constraint_name,6)='fk_aw_'))
 THEN 'A_PRESENT_VALIDATE_FULL_SIGNATURE_OR_STOP'
 ELSE 'A_ABSENT_CANDIDATE_RUN_ORIGINAL_PREFLIGHT_OR_STOP' END AS a_route;
-- HH_CANONICAL_PRESENCE_BEGIN
SELECT CASE WHEN (SELECT CASE WHEN (EXISTS(SELECT 1 FROM information_schema.tables WHERE BINARY table_schema=BINARY DATABASE() AND table_name IN ('emergency_assistance_command','emergency_assistance_event','emergency_assistance_notification','emergency_assistance_recipient','family_assistance','family_assistance_command','family_assistance_event','family_assistance_notification','medication_execution','medication_execution_command','medication_execution_event','medication_execution_notification','medication_reconciliation','medication_reconciliation_revision','stock_command','stock_count','stock_event','stock_intake_resolution','stock_notification','stock_work')) OR EXISTS(SELECT 1 FROM information_schema.columns WHERE BINARY table_schema=BINARY DATABASE() AND ((table_name='care_appointment' AND column_name IN ('family_assistance_id','family_workflow_version')) OR (table_name='care_intake_action' AND column_name IN ('action_kind','actual_at_utc','amount_basis','correction_reason','execution_event_id','execution_id','original_action_id','provenance_json','recorded_at_utc','unit','workflow_version')) OR (table_name='care_item' AND column_name IN ('family_assistance_id','family_workflow_version','medication_execution_id','medication_reconciliation_id','medication_workflow_version')) OR (table_name='electronic_prescription' AND column_name IN ('workflow_version')) OR (table_name='electronic_prescription_item' AND column_name IN ('workflow_version')) OR (table_name='emergency_event' AND column_name IN ('accepted_at_utc','current_lead_id','last_progress_at_utc','lock_version','outcome_code','outcome_reason','pending_successor_id','requested_at_utc','requester_user_id','resolved_at_utc','response_status','snapshot_hash','workflow_state','workflow_version')) OR (table_name='medication' AND column_name IN ('execution_id','workflow_version')) OR (table_name='medication_intake' AND column_name IN ('actual_at_utc','execution_id','follow_up_cycle','follow_up_due_at','follow_up_kind','follow_up_state','latest_action_id','planned_quantity','planned_unit','provenance','reconciliation_id','schedule_offset_seconds','schedule_zone','scheduled_at_utc','snooze_until_utc','workflow_version')) OR (table_name='medication_reminder' AND column_name IN ('effective_at_utc','end_at_utc','execution_id','order_id','planned_quantity','planned_unit','reconciliation_id','schedule_zone','workflow_version')) OR (table_name='medication_stock' AND column_name IN ('adopted_at','current_work_id','lock_version','workflow_version')) OR (table_name='medication_stock_movement' AND column_name IN ('action_id','actual_at_utc','count_id','intake_id','movement_kind','stock_event_id','stock_id','unit','workflow_version')))) OR EXISTS(SELECT 1 FROM information_schema.statistics WHERE BINARY table_schema=BINARY DATABASE() AND LEFT(index_name,3)='hh_') OR EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE BINARY constraint_schema=BINARY DATABASE() AND LEFT(constraint_name,3)='hh_') OR EXISTS(SELECT 1 FROM information_schema.triggers WHERE BINARY trigger_schema=BINARY DATABASE() AND LEFT(trigger_name,3)='hh_') OR EXISTS(SELECT 1 FROM information_schema.routines WHERE BINARY routine_schema=BINARY DATABASE() AND LEFT(routine_name,3)='hh_')) THEN 1 ELSE 0 END AS present)=1
 THEN 'B_PRESENT_READ_POSTCHECK_READY_ONLY_ELSE_STOP'
 ELSE 'B_ABSENT_CANDIDATE_READ_PREFLIGHT_PREREQUISITES_OR_STOP' END AS b_route;
-- HH_CANONICAL_PRESENCE_END
