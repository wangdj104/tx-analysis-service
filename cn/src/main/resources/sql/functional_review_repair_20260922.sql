-- Additive repair for existing installations; no business records are changed.
-- Definitions match init.sql. Safe to run more than once.
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
