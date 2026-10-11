-- 用途：补临床导入、告警事件和用药安全表及 6 条初始安全规则；当前 init.sql 已含这些定义。
-- 前提：基础表已存在；旧库缺相关表或初始规则时，人工核对后再执行。
-- 重复执行：已有表会跳过；INSERT IGNORE 仍会补回缺失的 6 条初始用药安全规则，已有同一药物对不覆盖。
-- 风险：删除过的初始规则可能按默认启用状态（enabled=1）恢复；不修复全部旧字段，DDL 隐式提交，先备份并演练恢复。

CREATE TABLE IF NOT EXISTS clinical_import_batch (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  source_type VARCHAR(20) NOT NULL,
  status VARCHAR(30) NOT NULL,
  item_count INT NOT NULL DEFAULT 0,
  error_count INT NOT NULL DEFAULT 0,
  summary VARCHAR(500) DEFAULT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_import_patient_time (patient_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='clinical import audit batch';

CREATE TABLE IF NOT EXISTS alert_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  alert_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  actor_id BIGINT DEFAULT NULL,
  actor_name VARCHAR(100) DEFAULT NULL,
  from_status VARCHAR(20) DEFAULT NULL,
  to_status VARCHAR(20) NOT NULL,
  note VARCHAR(500) DEFAULT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_alert_event_alert (alert_id,created_at),
  KEY idx_alert_event_patient (patient_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='alert status audit trail';

CREATE TABLE IF NOT EXISTS medication_safety_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ingredient_a VARCHAR(100) NOT NULL,
  ingredient_b VARCHAR(100) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  message VARCHAR(500) NOT NULL,
  renal_note VARCHAR(500) DEFAULT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_medication_safety_pair (ingredient_a,ingredient_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='curated medication safety screening rules';

-- Restore the same starter screening rules already shipped in init.sql.
-- These are review prompts, not prescribing decisions.
INSERT IGNORE INTO medication_safety_rule (ingredient_a,ingredient_b,severity,message,renal_note) VALUES
('warfarin','ibuprofen','CRITICAL','Warfarin with ibuprofen can substantially increase bleeding risk. Confirm the prescriber plan.','Avoid routine NSAID use in advanced kidney disease unless explicitly directed.'),
('warfarin','aspirin','WARNING','Warfarin with aspirin increases bleeding risk and should have a documented indication.',NULL),
('lisinopril','potassium','WARNING','ACE inhibitor plus potassium supplementation can increase serum potassium.','Review potassium and renal function before changing therapy.'),
('losartan','potassium','WARNING','ARB plus potassium supplementation can increase serum potassium.','Review potassium and renal function before changing therapy.'),
('spironolactone','potassium','CRITICAL','This combination can cause severe hyperkalaemia. Confirm the prescriber plan.','Extra caution is required when kidney function is impaired.'),
('calcium carbonate','levothyroxine','INFO','Calcium can reduce levothyroxine absorption; separate administration times when directed.',NULL);
