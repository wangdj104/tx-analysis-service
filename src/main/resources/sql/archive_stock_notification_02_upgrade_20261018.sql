-- 中文操作说明：恢复库存提醒隔离，两张独立侧表；MySQL 5.7.18（可带供应商后缀，禁止MariaDB）。
-- 必须先SQL后代码：暂停恢复、暂停并排空旧库存通知工作进程 → 单独01预检 → 批准后02升级 → 单独03后检READY → 全部工作进程部署新版 → 恢复运行。
-- 结构缺失/部分/不符/不可读时新版暂停全部库存通知和含库存恢复；其他提醒不因本模块结构缺失暂停。
-- 只保护新版以后新恢复的库存；无法可靠识别此前历史恢复库存。不回填、不批量改库存、不自动启用。
-- 已有库只运行增量01/02/03；空库另用新init入口。旧SQL不重跑，不可用init升级已有库；root/cn同一库只选一套。
-- FULLY_INSTALLED跳过02；PARTIAL_OR_UNKNOWN或错误保持暂停，不能--force、盲重跑或自动补齐。
-- DDL隐式提交，可能等待元数据锁；不保证原子回滚、在线无锁或耗时。先验证完整数据库备份可恢复和权限/空间。
-- 已存在恢复标记后禁止回退旧应用，旧代码忽略OFF。数据库恢复必须连同两侧表；合法物理删除源库存时级联结束标记/回执寿命。
-- 本交付仅SQL源文；真实MySQL5.7语法/触发器/级联/锁行为、真实通知及部署均未验证。
-- 操作者明确核对目标库和连接，使用交互密码，禁止将凭据写入脚本：mysql --batch --default-character-set=utf8mb4 --host=HOST --port=PORT --user=USER --password --database=DB < SCRIPT.sql
-- 仅EXACT_OLD新增本模块，不改旧业务行或旧DDL。若已READY必须跳过本文件。
SET @asn57_absent=(DATABASE() IS NOT NULL AND (VERSION()='5.7.18' OR VERSION() LIKE '5.7.18-%') AND LOWER(VERSION()) NOT LIKE '%mariadb%' AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@SESSION.sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@SESSION.sql_mode)>0) AND @@SESSION.foreign_key_checks=1 AND @@SESSION.unique_checks=1 AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME='medication_stock' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB')=1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME='medication_stock' AND BINARY COLUMN_NAME='id' AND LOWER(COLUMN_TYPE)='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT IS NULL AND LOWER(EXTRA)='auto_increment')=1 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME='medication_stock' AND BINARY INDEX_NAME='PRIMARY')=1 AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY TABLE_NAME='medication_stock' AND BINARY INDEX_NAME='PRIMARY' AND NON_UNIQUE=0 AND SEQ_IN_INDEX=1 AND BINARY COLUMN_NAME='id' AND SUB_PART IS NULL AND INDEX_TYPE='BTREE' AND COLLATION='A')=1 AND (SELECT CASE WHEN DATABASE() IS NULL THEN NULL ELSE (SELECT COUNT(*) FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY LEFT(TABLE_NAME,27)=BINARY 'archive_stock_notification_')+(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE BINARY TRIGGER_SCHEMA=BINARY DATABASE() AND (BINARY EVENT_OBJECT_TABLE IN ('archive_stock_notification_state','archive_stock_notification_command') OR BINARY LEFT(TRIGGER_NAME,6)=BINARY 'asn57_'))+(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE BINARY ROUTINE_SCHEMA=BINARY DATABASE() AND BINARY LEFT(ROUTINE_NAME,6)=BINARY 'asn57_')+(SELECT COUNT(*) FROM information_schema.EVENTS WHERE BINARY EVENT_SCHEMA=BINARY DATABASE() AND BINARY LEFT(EVENT_NAME,6)=BINARY 'asn57_')+(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE() AND BINARY LEFT(INDEX_NAME,6)=BINARY 'asn57_')+(SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE BINARY CONSTRAINT_SCHEMA=BINARY DATABASE() AND BINARY LEFT(CONSTRAINT_NAME,6)=BINARY 'asn57_') END AS present)=0);
SET @asn57_guard_sql=IF(@asn57_absent,'SELECT 1 AS precondition_passed','SELECT * FROM information_schema.ASN57_STOP_EXACT_OLD');
PREPARE asn57_guard FROM @asn57_guard_sql;
EXECUTE asn57_guard;
DEALLOCATE PREPARE asn57_guard;

SET NAMES utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE `archive_stock_notification_state` (
  `stock_id` BIGINT NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 0,
  `control_version` BIGINT NOT NULL DEFAULT 0,
  `enabled_after_stock_version` BIGINT NOT NULL DEFAULT 0,
  `restored_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`stock_id`),
  CONSTRAINT `asn57_state_stock` FOREIGN KEY (`stock_id`) REFERENCES `medication_stock` (`id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE `archive_stock_notification_command` (
  `actor_id` BIGINT NOT NULL,
  `command_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `stock_id` BIGINT NOT NULL,
  `expected_version` BIGINT NOT NULL,
  `operation` VARCHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `payload_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result_json` VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`actor_id`,`command_key`),
  KEY `asn57_command_stock` (`stock_id`),
  CONSTRAINT `asn57_command_state` FOREIGN KEY (`stock_id`) REFERENCES `archive_stock_notification_state` (`stock_id`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

DELIMITER $$
CREATE TRIGGER `asn57_state_insert` BEFORE INSERT ON `archive_stock_notification_state` FOR EACH ROW
BEGIN
  IF COALESCE((NOT (NEW.stock_id BETWEEN 1 AND 9007199254740991 AND NEW.enabled IN (0,1) AND NEW.control_version BETWEEN 0 AND 9007199254740991 AND NEW.enabled_after_stock_version BETWEEN 0 AND 9007199254740991 AND NEW.restored_at IS NOT NULL AND NEW.updated_at IS NOT NULL AND NEW.updated_at>=NEW.restored_at) OR NEW.enabled<>0 OR NEW.control_version<>0 OR NEW.enabled_after_stock_version<>0 OR NOT (NEW.updated_at <=> NEW.restored_at)),TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ASN57_STATE_INITIAL';
  END IF;
END$$

CREATE TRIGGER `asn57_state_update` BEFORE UPDATE ON `archive_stock_notification_state` FOR EACH ROW
BEGIN
  IF COALESCE((NOT (NEW.stock_id BETWEEN 1 AND 9007199254740991 AND NEW.enabled IN (0,1) AND NEW.control_version BETWEEN 0 AND 9007199254740991 AND NEW.enabled_after_stock_version BETWEEN 0 AND 9007199254740991 AND NEW.restored_at IS NOT NULL AND NEW.updated_at IS NOT NULL AND NEW.updated_at>=NEW.restored_at) OR NOT (NEW.stock_id <=> OLD.stock_id) OR NOT (NEW.restored_at <=> OLD.restored_at) OR OLD.control_version>=9007199254740991 OR NEW.control_version<>OLD.control_version+1 OR NEW.enabled=OLD.enabled OR NEW.updated_at<OLD.updated_at OR NEW.enabled_after_stock_version<OLD.enabled_after_stock_version OR (NEW.enabled=0 AND NEW.enabled_after_stock_version<>OLD.enabled_after_stock_version)),TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ASN57_STATE_TRANSITION';
  END IF;
END$$

CREATE TRIGGER `asn57_state_delete` BEFORE DELETE ON `archive_stock_notification_state` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ASN57_STATE_DIRECT_DELETE';
END$$

CREATE TRIGGER `asn57_command_insert` BEFORE INSERT ON `archive_stock_notification_command` FOR EACH ROW
BEGIN
  IF COALESCE((NOT (NEW.actor_id BETWEEN 1 AND 9007199254740991 AND NEW.stock_id BETWEEN 1 AND 9007199254740991 AND NEW.expected_version BETWEEN 0 AND 9007199254740990 AND OCTET_LENGTH(NEW.command_key) BETWEEN 16 AND 64 AND BINARY NEW.command_key REGEXP '^[A-Za-z0-9][A-Za-z0-9_-]{15,63}$' AND BINARY NEW.operation IN ('ENABLE','DISABLE') AND OCTET_LENGTH(NEW.payload_hash)=64 AND BINARY NEW.payload_hash REGEXP '^[0-9a-f]{64}$' AND NEW.created_at IS NOT NULL) OR NEW.result_json IS NOT NULL OR NOT EXISTS(SELECT 1 FROM archive_stock_notification_state s WHERE s.stock_id=NEW.stock_id AND s.control_version=NEW.expected_version AND s.enabled=IF(BINARY NEW.operation='ENABLE',0,1))),TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ASN57_COMMAND_INITIAL';
  END IF;
END$$

CREATE TRIGGER `asn57_command_update` BEFORE UPDATE ON `archive_stock_notification_command` FOR EACH ROW
BEGIN
  IF COALESCE((OLD.result_json IS NOT NULL OR NEW.result_json IS NULL OR NOT (BINARY NEW.actor_id <=> BINARY OLD.actor_id) OR NOT (BINARY NEW.command_key <=> BINARY OLD.command_key) OR NOT (BINARY NEW.stock_id <=> BINARY OLD.stock_id) OR NOT (BINARY NEW.expected_version <=> BINARY OLD.expected_version) OR NOT (BINARY NEW.operation <=> BINARY OLD.operation) OR NOT (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash) OR NOT (BINARY NEW.created_at <=> BINARY OLD.created_at)),TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ASN57_COMMAND_IMMUTABLE';
  END IF;
  IF COALESCE((NOT (JSON_VALID(NEW.result_json))),TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ASN57_RECEIPT_JSON';
  END IF;
  IF COALESCE((NOT (JSON_VALID(NEW.result_json) AND OCTET_LENGTH(NEW.result_json)<=512 AND JSON_TYPE(NEW.result_json)='OBJECT' AND JSON_LENGTH(NEW.result_json)=5 AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.stockId'))='INTEGER' AND JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.stockId'))=NEW.stock_id AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.controlVersion'))='INTEGER' AND JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.controlVersion'))=NEW.expected_version+1 AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.operation'))='STRING' AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.operation'))=BINARY NEW.operation AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.enabled'))='BOOLEAN' AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.enabled'))=IF(BINARY NEW.operation='ENABLE','true','false') AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.recordedAt'))='STRING' AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.recordedAt'))=BINARY DATE_FORMAT(NEW.created_at,'%Y-%m-%dT%H:%i:%s.%fZ')) OR NOT EXISTS(SELECT 1 FROM archive_stock_notification_state s WHERE s.stock_id=NEW.stock_id AND s.control_version=NEW.expected_version+1 AND s.enabled=IF(BINARY NEW.operation='ENABLE',1,0) AND s.updated_at=NEW.created_at)),TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ASN57_RECEIPT_SHAPE';
  END IF;
END$$

CREATE TRIGGER `asn57_command_delete` BEFORE DELETE ON `archive_stock_notification_command` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ASN57_COMMAND_DIRECT_DELETE';
END$$

DELIMITER ;

-- 立即停止应用部署，另行运行03确认完整结构和数据。
