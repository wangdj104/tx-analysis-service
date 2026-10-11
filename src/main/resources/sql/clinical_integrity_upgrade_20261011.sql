-- 用途：人工升级临床内容版本、审核备注、来源去重键及健康分析持久运行记录。
-- 前提：先备份完整数据库和附件，在隔离库恢复演练；先执行医生工作台、照护平台及所需旧库修复脚本，health_analysis_automation 必须已具备下列旧字段；停止应用和其他写入者。
-- 重复执行：只补缺列/表，核对并修复两个命名唯一键；保留所有历史正文、审核与任务设置，不重写数据。
-- 风险：仅维护者线下执行，不接入启动/Compose 自动迁移；DDL 隐式提交，不能整批回滚；发现重复来源身份会 SIGNAL 并在任何临床表/索引变更前停止，绝不删除记录。
-- 风险：需要当前 schema 的 CREATE ROUTINE、ALTER ROUTINE、EXECUTE 及表结构权限；临时过程 clinical_integrity_abort_20261011 只供本脚本使用，勿使用该保留名称保存其他过程。
-- 风险：失败可能遗留临时过程；修正原因后原脚本会删除并重建该过程，成功结束时删除它。不要用 mysql --force 忽略错误继续执行。

-- 首先用只读语句核验依赖，缺表/旧字段时在任何 DDL 前失败。
SELECT id,user_id,patient_id,task_name,enabled,frequency_type,interval_days,day_of_week,day_of_month,run_time,analysis_range_days,analysis_items,notification_channel_ids,next_run_at,last_run_at,last_run_status,last_error,last_analysis_record_id,created_at,updated_at FROM health_analysis_automation WHERE 1=0;
SELECT patient_id,source_type,source_external_id,verified_by,verified_at FROM medical_record WHERE 1=0;
SELECT patient_id,source_type,source_external_id FROM bp_self_monitor_record WHERE 1=0;

-- SIGNAL 不支持 PREPARE；用单语句临时过程和条件 CALL，兼容标准 MySQL 客户端与原生测试执行器，无需 DELIMITER 改写。
DROP PROCEDURE IF EXISTS clinical_integrity_abort_20261011;
CREATE PROCEDURE clinical_integrity_abort_20261011() SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Clinical integrity blocked: duplicate patient/source/external ID; inspect both import tables, retain all rows';
SET @clinical_integrity_duplicates=(SELECT COUNT(*) FROM (SELECT patient_id,source_type,source_external_id FROM bp_self_monitor_record WHERE patient_id IS NOT NULL AND source_type IS NOT NULL AND source_external_id IS NOT NULL GROUP BY patient_id,source_type,source_external_id HAVING COUNT(*)>1) AS duplicate_bp)+(SELECT COUNT(*) FROM (SELECT patient_id,source_type,source_external_id FROM medical_record WHERE patient_id IS NOT NULL AND source_type IS NOT NULL AND source_external_id IS NOT NULL GROUP BY patient_id,source_type,source_external_id HAVING COUNT(*)>1) AS duplicate_medical);
SET @clinical_integrity_check=IF(@clinical_integrity_duplicates>0,'CALL clinical_integrity_abort_20261011()','SELECT 1');
PREPARE clinical_integrity_preflight FROM @clinical_integrity_check;
EXECUTE clinical_integrity_preflight;
DEALLOCATE PREPARE clinical_integrity_preflight;

-- 唯一键采用完整来源命名空间；已有同名旧键原子替换，不删除、合并或重标记临床记录。
SET @clinical_integrity_bp_key=(SELECT IF(COUNT(*)=3 AND MIN(non_unique)=0 AND GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,source_type,source_external_id' AND SUM(sub_part IS NOT NULL)=0,1,0) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='bp_self_monitor_record' AND index_name='uk_bp_external');
SET @ddl=IF(@clinical_integrity_bp_key=1,'SELECT 1',IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='bp_self_monitor_record' AND index_name='uk_bp_external'),'ALTER TABLE bp_self_monitor_record DROP INDEX uk_bp_external, ADD UNIQUE KEY uk_bp_external(patient_id,source_type,source_external_id)','ALTER TABLE bp_self_monitor_record ADD UNIQUE KEY uk_bp_external(patient_id,source_type,source_external_id)'));
PREPARE clinical_integrity_ddl FROM @ddl; EXECUTE clinical_integrity_ddl; DEALLOCATE PREPARE clinical_integrity_ddl;
SET @clinical_integrity_record_key=(SELECT IF(COUNT(*)=3 AND MIN(non_unique)=0 AND GROUP_CONCAT(column_name ORDER BY seq_in_index)='patient_id,source_type,source_external_id' AND SUM(sub_part IS NOT NULL)=0,1,0) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medical_record' AND index_name='uk_record_external');
SET @ddl=IF(@clinical_integrity_record_key=1,'SELECT 1',IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='medical_record' AND index_name='uk_record_external'),'ALTER TABLE medical_record DROP INDEX uk_record_external, ADD UNIQUE KEY uk_record_external(patient_id,source_type,source_external_id)','ALTER TABLE medical_record ADD UNIQUE KEY uk_record_external(patient_id,source_type,source_external_id)'));
PREPARE clinical_integrity_ddl FROM @ddl; EXECUTE clinical_integrity_ddl; DEALLOCATE PREPARE clinical_integrity_ddl;

SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='content_version'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN content_version BIGINT NOT NULL DEFAULT 1 AFTER verified_at');
PREPARE clinical_integrity_ddl FROM @ddl; EXECUTE clinical_integrity_ddl; DEALLOCATE PREPARE clinical_integrity_ddl;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_record' AND column_name='verification_note'),'SELECT 1','ALTER TABLE medical_record ADD COLUMN verification_note TEXT DEFAULT NULL AFTER content_version');
PREPARE clinical_integrity_ddl FROM @ddl; EXECUTE clinical_integrity_ddl; DEALLOCATE PREPARE clinical_integrity_ddl;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='health_analysis_automation' AND column_name='config_version'),'SELECT 1','ALTER TABLE health_analysis_automation ADD COLUMN config_version BIGINT NOT NULL DEFAULT 0 AFTER last_analysis_record_id');
PREPARE clinical_integrity_ddl FROM @ddl; EXECUTE clinical_integrity_ddl; DEALLOCATE PREPARE clinical_integrity_ddl;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='health_analysis_automation' AND column_name='active_run_id'),'SELECT 1','ALTER TABLE health_analysis_automation ADD COLUMN active_run_id BIGINT DEFAULT NULL AFTER config_version');
PREPARE clinical_integrity_ddl FROM @ddl; EXECUTE clinical_integrity_ddl; DEALLOCATE PREPARE clinical_integrity_ddl;

-- 保留被删除任务的运行审计，不添加 automation 外键；运行 token 和阶段由服务使用数据库时钟与短事务认领。
CREATE TABLE IF NOT EXISTS health_analysis_run (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, automation_id BIGINT NOT NULL,
 claim_token VARCHAR(64) NOT NULL, state VARCHAR(30) NOT NULL,
 scheduled TINYINT NOT NULL DEFAULT 0, scheduled_for DATETIME,
 settings_json LONGTEXT NOT NULL, claimed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 analysis_record_id BIGINT, last_error VARCHAR(500), notification_outcomes LONGTEXT,
 UNIQUE KEY uq_health_run_token (claim_token),
 KEY idx_health_run_automation (automation_id,id), KEY idx_health_run_recovery (state,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable health analysis operation journal';
DROP PROCEDURE clinical_integrity_abort_20261011;
