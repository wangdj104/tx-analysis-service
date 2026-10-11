-- 用途：补用户偏好表，供后台通知读取接收人的界面语言；当前 init.sql 已包含该表。
-- 前提：基础表已存在；旧库缺少 user_preference 时人工执行。
-- 重复执行：只创建缺失表，不填用户偏好、不修复已有表的错误定义。
-- 风险：DDL 隐式提交；先完整备份及恢复演练，后台语言取值仍由应用负责。

CREATE TABLE IF NOT EXISTS `user_preference` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `pref_key` VARCHAR(128) NOT NULL,
  `pref_value` TEXT NOT NULL,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_user_key` (`user_id`, `pref_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户界面与通知偏好';
