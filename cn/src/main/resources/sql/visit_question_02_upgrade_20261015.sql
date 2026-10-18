-- 就诊问题跟踪：仅新增三个独立表，不修改既有问题或家庭协作 B 对象。
-- 目标原生 MySQL 5.7.18；功能默认关闭，不执行历史回填，不自动启用。
-- 先停写、核对库名与权限、完成全库及附件备份，并在隔离库验证恢复。
-- 现有库按 01 只读预检 → 02 仅 ABSENT 时升级 → 03 只读后检；全新空库单独使用 init.sql。
-- DDL 隐式提交；任一步失败立即停止，禁止 --force、盲重跑、删除重建或用 init.sql 修补。
-- 执行连接须使用 utf8mb4/utf8mb4_bin，严格 SQL 模式须与 B 守卫一致；本文件不会修改连接或服务器设置。
-- 仅源码交付。原生数据库执行、驱动收发和并发验证仍为 NOT_RUN。
-- 只有同一停写窗口的 01=ABSENT、B=READY、所有前提通过，才允许执行本文件。

CREATE TABLE visit_question_state (
  question_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  state VARCHAR(20) NOT NULL,
  lock_version BIGINT NOT NULL,
  current_title VARCHAR(200) NOT NULL,
  current_description MEDIUMTEXT NULL,
  current_recorded_answer MEDIUMTEXT NULL,
  current_follow_up MEDIUMTEXT NULL,
  ordinary_answer_origin VARCHAR(24) NULL,
  ordinary_answer_recorder_id BIGINT NULL,
  ordinary_answer_recorded_at DATETIME(6) NULL,
  legacy_appointment_id BIGINT NULL,
  source_observed_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_observed_at DATETIME(6) NOT NULL,
  observed_b_assistance_id BIGINT NULL,
  context_type VARCHAR(24) NULL,
  context_id BIGINT NULL,
  context_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  responsible_user_id BIGINT NULL,
  follow_up_due_date DATE NULL,
  current_answer_event_id BIGINT NULL,
  close_reason VARCHAR(40) NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (question_id),
  KEY vq_state_patient_ix (patient_id,question_id),
  KEY vq_state_status_ix (patient_id,state,question_id),
  KEY vq_state_context_ix (patient_id,context_type,context_id,question_id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE visit_question_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  from_state VARCHAR(20) NULL,
  to_state VARCHAR(20) NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_kind VARCHAR(24) NOT NULL,
  answer_origin VARCHAR(24) NULL,
  reason_code VARCHAR(40) NULL,
  body_json MEDIUMTEXT NOT NULL,
  recorded_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY vq_event_version_uq (question_id,version),
  KEY vq_event_page_ix (patient_id,question_id,id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE visit_question_command (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_id BIGINT NOT NULL,
  command_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  question_id BIGINT NULL,
  expected_version BIGINT NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_json LONGTEXT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY vq_command_key_uq (actor_id,command_key),
  KEY vq_command_question_ix (question_id,id)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DELIMITER $$

CREATE TRIGGER vq_state_bi BEFORE INSERT ON visit_question_state
FOR EACH ROW BEGIN
  IF COALESCE((NEW.question_id BETWEEN 1 AND 9007199254740991
      AND NEW.patient_id BETWEEN 1 AND 9007199254740991
      AND NEW.lock_version BETWEEN 1 AND 9007199254740991
      AND NEW.created_by BETWEEN 1 AND 9007199254740991
      AND BINARY NEW.state IN ('OPEN','UNRESOLVED','ANSWERED','FOLLOW_UP','CLOSED')
      AND CHAR_LENGTH(NEW.current_title) BETWEEN 1 AND 200
      AND OCTET_LENGTH(NEW.current_title)+COALESCE(OCTET_LENGTH(NEW.current_description),0)+COALESCE(OCTET_LENGTH(NEW.current_recorded_answer),0)+COALESCE(OCTET_LENGTH(NEW.current_follow_up),0)<=131072
      AND BINARY NEW.source_observed_hash REGEXP '^[0-9a-f]{64}$'
      AND EXISTS(SELECT 1 FROM care_item c WHERE c.id=NEW.question_id AND c.patient_id=NEW.patient_id AND BINARY c.kind='QUESTION')
      AND ((NEW.ordinary_answer_origin IS NULL AND NEW.ordinary_answer_recorder_id IS NULL AND NEW.ordinary_answer_recorded_at IS NULL AND NEW.current_recorded_answer IS NULL) OR (BINARY NEW.ordinary_answer_origin='LEGACY_UNVERIFIED' AND NEW.ordinary_answer_recorder_id IS NULL AND NEW.ordinary_answer_recorded_at IS NULL AND NEW.current_recorded_answer IS NOT NULL) OR (BINARY NEW.ordinary_answer_origin='USER_RECORDED' AND NEW.ordinary_answer_recorder_id BETWEEN 1 AND 9007199254740991 AND NEW.ordinary_answer_recorded_at IS NOT NULL AND CHAR_LENGTH(TRIM(NEW.current_recorded_answer))>0))
      AND ((NEW.context_type IS NULL AND NEW.context_id IS NULL AND NEW.context_fingerprint IS NULL) OR (BINARY NEW.context_type IN ('LEGACY_APPOINTMENT','CARE_APPOINTMENT','VISIT_RECORD') AND NEW.context_id BETWEEN 1 AND 9007199254740991 AND BINARY NEW.context_fingerprint REGEXP '^[0-9a-f]{64}$'))
      AND (NEW.legacy_appointment_id IS NULL OR EXISTS(SELECT 1 FROM care_item a WHERE a.id=NEW.legacy_appointment_id AND a.patient_id=NEW.patient_id AND BINARY a.kind='APPOINTMENT'))
      AND (NEW.responsible_user_id IS NULL OR NEW.responsible_user_id BETWEEN 1 AND 9007199254740991)
      AND (NEW.observed_b_assistance_id IS NULL OR EXISTS(SELECT 1 FROM family_assistance b WHERE b.id=NEW.observed_b_assistance_id AND b.patient_id=NEW.patient_id AND b.care_item_id=NEW.question_id AND b.care_appointment_id IS NULL))
      AND (NEW.current_answer_event_id IS NULL OR EXISTS(SELECT 1 FROM visit_question_event e WHERE e.id=NEW.current_answer_event_id AND e.question_id=NEW.question_id AND e.patient_id=NEW.patient_id AND e.version<=NEW.lock_version AND BINARY e.event_type='PUBLISH_ANSWER' AND BINARY e.answer_origin='CLINICIAN_AUTHORED'))
      AND ((BINARY NEW.state='CLOSED' AND BINARY NEW.close_reason IN ('ANSWER_SUFFICIENT','FOLLOW_UP_FINISHED','NO_LONGER_NEEDED','DUPLICATE','WITHDRAWN','OTHER')) OR (BINARY NEW.state<>'CLOSED' AND NEW.close_reason IS NULL))
      AND (BINARY NEW.state<>'FOLLOW_UP' OR (CHAR_LENGTH(TRIM(NEW.current_follow_up))>0 AND NEW.follow_up_due_date IS NOT NULL))
      AND NEW.updated_at>=NEW.created_at AND NEW.lock_version=1 AND NEW.current_answer_event_id IS NULL),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_state';
  END IF;
END$$

CREATE TRIGGER vq_state_bu BEFORE UPDATE ON visit_question_state
FOR EACH ROW BEGIN
  IF COALESCE((NEW.question_id BETWEEN 1 AND 9007199254740991
      AND NEW.patient_id BETWEEN 1 AND 9007199254740991
      AND NEW.lock_version BETWEEN 1 AND 9007199254740991
      AND NEW.created_by BETWEEN 1 AND 9007199254740991
      AND BINARY NEW.state IN ('OPEN','UNRESOLVED','ANSWERED','FOLLOW_UP','CLOSED')
      AND CHAR_LENGTH(NEW.current_title) BETWEEN 1 AND 200
      AND OCTET_LENGTH(NEW.current_title)+COALESCE(OCTET_LENGTH(NEW.current_description),0)+COALESCE(OCTET_LENGTH(NEW.current_recorded_answer),0)+COALESCE(OCTET_LENGTH(NEW.current_follow_up),0)<=131072
      AND BINARY NEW.source_observed_hash REGEXP '^[0-9a-f]{64}$'
      AND EXISTS(SELECT 1 FROM care_item c WHERE c.id=NEW.question_id AND c.patient_id=NEW.patient_id AND BINARY c.kind='QUESTION')
      AND ((NEW.ordinary_answer_origin IS NULL AND NEW.ordinary_answer_recorder_id IS NULL AND NEW.ordinary_answer_recorded_at IS NULL AND NEW.current_recorded_answer IS NULL) OR (BINARY NEW.ordinary_answer_origin='LEGACY_UNVERIFIED' AND NEW.ordinary_answer_recorder_id IS NULL AND NEW.ordinary_answer_recorded_at IS NULL AND NEW.current_recorded_answer IS NOT NULL) OR (BINARY NEW.ordinary_answer_origin='USER_RECORDED' AND NEW.ordinary_answer_recorder_id BETWEEN 1 AND 9007199254740991 AND NEW.ordinary_answer_recorded_at IS NOT NULL AND CHAR_LENGTH(TRIM(NEW.current_recorded_answer))>0))
      AND ((NEW.context_type IS NULL AND NEW.context_id IS NULL AND NEW.context_fingerprint IS NULL) OR (BINARY NEW.context_type IN ('LEGACY_APPOINTMENT','CARE_APPOINTMENT','VISIT_RECORD') AND NEW.context_id BETWEEN 1 AND 9007199254740991 AND BINARY NEW.context_fingerprint REGEXP '^[0-9a-f]{64}$'))
      AND (NEW.legacy_appointment_id IS NULL OR EXISTS(SELECT 1 FROM care_item a WHERE a.id=NEW.legacy_appointment_id AND a.patient_id=NEW.patient_id AND BINARY a.kind='APPOINTMENT'))
      AND (NEW.responsible_user_id IS NULL OR NEW.responsible_user_id BETWEEN 1 AND 9007199254740991)
      AND (NEW.observed_b_assistance_id IS NULL OR EXISTS(SELECT 1 FROM family_assistance b WHERE b.id=NEW.observed_b_assistance_id AND b.patient_id=NEW.patient_id AND b.care_item_id=NEW.question_id AND b.care_appointment_id IS NULL))
      AND (NEW.current_answer_event_id IS NULL OR EXISTS(SELECT 1 FROM visit_question_event e WHERE e.id=NEW.current_answer_event_id AND e.question_id=NEW.question_id AND e.patient_id=NEW.patient_id AND e.version<=NEW.lock_version AND BINARY e.event_type='PUBLISH_ANSWER' AND BINARY e.answer_origin='CLINICIAN_AUTHORED'))
      AND ((BINARY NEW.state='CLOSED' AND BINARY NEW.close_reason IN ('ANSWER_SUFFICIENT','FOLLOW_UP_FINISHED','NO_LONGER_NEEDED','DUPLICATE','WITHDRAWN','OTHER')) OR (BINARY NEW.state<>'CLOSED' AND NEW.close_reason IS NULL))
      AND (BINARY NEW.state<>'FOLLOW_UP' OR (CHAR_LENGTH(TRIM(NEW.current_follow_up))>0 AND NEW.follow_up_due_date IS NOT NULL))
      AND NEW.updated_at>=NEW.created_at
      AND (NEW.question_id <=> OLD.question_id) AND (NEW.patient_id <=> OLD.patient_id) AND (NEW.created_by <=> OLD.created_by) AND (NEW.created_at <=> OLD.created_at)
      AND ((NEW.lock_version=OLD.lock_version+1 AND OLD.lock_version<9007199254740991 AND EXISTS(SELECT 1 FROM visit_question_event e WHERE e.question_id=OLD.question_id AND e.patient_id=OLD.patient_id AND e.version=OLD.lock_version AND BINARY e.to_state=OLD.state))
        OR (NEW.lock_version=OLD.lock_version AND NOT (NEW.current_answer_event_id <=> OLD.current_answer_event_id) AND (NEW.question_id <=> OLD.question_id) AND (NEW.patient_id <=> OLD.patient_id) AND (BINARY NEW.state <=> BINARY OLD.state) AND (NEW.lock_version <=> OLD.lock_version) AND (BINARY NEW.current_title <=> BINARY OLD.current_title) AND (BINARY NEW.current_description <=> BINARY OLD.current_description) AND (BINARY NEW.current_recorded_answer <=> BINARY OLD.current_recorded_answer) AND (BINARY NEW.current_follow_up <=> BINARY OLD.current_follow_up) AND (BINARY NEW.ordinary_answer_origin <=> BINARY OLD.ordinary_answer_origin) AND (NEW.ordinary_answer_recorder_id <=> OLD.ordinary_answer_recorder_id) AND (NEW.ordinary_answer_recorded_at <=> OLD.ordinary_answer_recorded_at) AND (NEW.legacy_appointment_id <=> OLD.legacy_appointment_id) AND (BINARY NEW.source_observed_hash <=> BINARY OLD.source_observed_hash) AND (NEW.source_observed_at <=> OLD.source_observed_at) AND (NEW.observed_b_assistance_id <=> OLD.observed_b_assistance_id) AND (BINARY NEW.context_type <=> BINARY OLD.context_type) AND (NEW.context_id <=> OLD.context_id) AND (BINARY NEW.context_fingerprint <=> BINARY OLD.context_fingerprint) AND (NEW.responsible_user_id <=> OLD.responsible_user_id) AND (NEW.follow_up_due_date <=> OLD.follow_up_due_date) AND (BINARY NEW.close_reason <=> BINARY OLD.close_reason) AND (NEW.created_by <=> OLD.created_by) AND (NEW.created_at <=> OLD.created_at) AND (NEW.updated_at <=> OLD.updated_at) AND EXISTS(SELECT 1 FROM visit_question_event e WHERE e.id=NEW.current_answer_event_id AND e.question_id=NEW.question_id AND e.patient_id=NEW.patient_id AND e.version=NEW.lock_version AND BINARY e.to_state=NEW.state AND BINARY e.event_type='PUBLISH_ANSWER')))),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_state';
  END IF;
END$$

CREATE TRIGGER vq_state_bd BEFORE DELETE ON visit_question_state
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ history is immutable';
END$$

CREATE TRIGGER vq_event_bi BEFORE INSERT ON visit_question_event
FOR EACH ROW BEGIN
  IF JSON_VALID(NEW.body_json)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid event JSON';
  END IF;
  IF COALESCE((NEW.question_id BETWEEN 1 AND 9007199254740991
      AND NEW.patient_id BETWEEN 1 AND 9007199254740991
      AND NEW.version BETWEEN 1 AND 9007199254740991
      AND NEW.actor_id BETWEEN 1 AND 9007199254740991
      AND BINARY NEW.event_type IN ('CREATE','ADOPT','EDIT','RECORD_ANSWER','PUBLISH_ANSWER','MARK_UNRESOLVED','SET_FOLLOW_UP','CLOSE','REOPEN')
      AND BINARY NEW.to_state IN ('OPEN','UNRESOLVED','ANSWERED','FOLLOW_UP','CLOSED')
      AND BINARY NEW.actor_kind IN ('RECORD_OWNER','AUTHORIZED_FAMILY','AUTHORIZED_DOCTOR','AUTHORIZED_NURSE','OTHER_AUTHORIZED')
      AND EXISTS(SELECT 1 FROM visit_question_state s WHERE s.question_id=NEW.question_id AND s.patient_id=NEW.patient_id AND s.lock_version=NEW.version AND BINARY s.state=NEW.to_state)
      AND ((NEW.version=1 AND NEW.from_state IS NULL AND NOT EXISTS(SELECT 1 FROM visit_question_event e WHERE e.question_id=NEW.question_id)) OR (NEW.version>1 AND EXISTS(SELECT 1 FROM visit_question_event e WHERE e.question_id=NEW.question_id AND e.patient_id=NEW.patient_id AND e.version=NEW.version-1 AND BINARY e.to_state=NEW.from_state) AND NOT EXISTS(SELECT 1 FROM visit_question_event e WHERE e.question_id=NEW.question_id AND e.version>=NEW.version)))
      AND JSON_TYPE(NEW.body_json)='OBJECT'
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.schemaVersion'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.schemaVersion')=1
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary'))='OBJECT'
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.schemaVersion'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.ordinary.schemaVersion')=1
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.patientId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.ordinary.patientId')=NEW.patient_id
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.expectedVersion'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.ordinary.expectedVersion')=NEW.version-1
      AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.operation'))=NEW.event_type
      AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body'))='OBJECT'
      AND JSON_LENGTH(JSON_EXTRACT(NEW.body_json,'$.ordinary'))=6
      AND ((BINARY NEW.event_type='CREATE' AND NEW.version=1 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.questionId'))='NULL') OR (BINARY NEW.event_type<>'CREATE' AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.questionId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.ordinary.questionId')=NEW.question_id))
      AND OCTET_LENGTH(JSON_EXTRACT(NEW.body_json,'$.ordinary'))<=65536
      AND ((JSON_CONTAINS_PATH(NEW.body_json,'one','$.baseline')=0 AND JSON_LENGTH(NEW.body_json)=2 AND OCTET_LENGTH(NEW.body_json)<=65536) OR (NEW.version=1 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline'))='OBJECT' AND JSON_LENGTH(NEW.body_json)=3 AND OCTET_LENGTH(JSON_EXTRACT(NEW.body_json,'$.baseline'))<=917504 AND OCTET_LENGTH(NEW.body_json)<=1048576))
      AND ((BINARY NEW.event_type='PUBLISH_ANSWER' AND BINARY NEW.answer_origin='CLINICIAN_AUTHORED' AND BINARY NEW.actor_kind='AUTHORIZED_DOCTOR') OR (BINARY NEW.event_type='RECORD_ANSWER' AND BINARY NEW.answer_origin='USER_RECORDED') OR (BINARY NEW.event_type NOT IN ('PUBLISH_ANSWER','RECORD_ANSWER') AND (NEW.answer_origin IS NULL OR (NEW.version=1 AND BINARY NEW.answer_origin='LEGACY_UNVERIFIED'))))
      AND (BINARY NEW.event_type NOT IN ('PUBLISH_ANSWER','RECORD_ANSWER') OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.answer'))='STRING' AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.answer')))) BETWEEN 1 AND 4000))
      AND ((BINARY NEW.event_type='MARK_UNRESOLVED' AND BINARY NEW.reason_code IN ('NOT_ASKED','NOT_ANSWERED','MORE_INFORMATION_NEEDED','VISIT_CANCELLED','OTHER')) OR (BINARY NEW.event_type='CLOSE' AND BINARY NEW.reason_code IN ('ANSWER_SUFFICIENT','FOLLOW_UP_FINISHED','NO_LONGER_NEEDED','DUPLICATE','WITHDRAWN','OTHER')) OR (BINARY NEW.event_type='REOPEN' AND BINARY NEW.reason_code IN ('NEW_INFORMATION','ANSWER_UNCLEAR','FOLLOW_UP_NOT_DONE','CONTEXT_CHANGED','OTHER')) OR (BINARY NEW.event_type NOT IN ('MARK_UNRESOLVED','CLOSE','REOPEN') AND NEW.reason_code IS NULL))
      AND (NEW.reason_code IS NULL OR BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.reason'))=NEW.reason_code)
      AND (NEW.reason_code IS NULL OR BINARY NEW.reason_code<>'OTHER' OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.reasonNote'))='STRING' AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.reasonNote')))) BETWEEN 1 AND 1000))
      AND (NEW.reason_code IS NULL OR BINARY NEW.reason_code<>'FOLLOW_UP_FINISHED' OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.resultText'))='STRING' AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.resultText')))) BETWEEN 1 AND 4000) OR JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.ordinary.body.resultReference'))='OBJECT')
      AND (NEW.version<>1 OR BINARY NEW.event_type='CREATE' OR JSON_CONTAINS_PATH(NEW.body_json,'one','$.baseline')=1)
      AND (JSON_CONTAINS_PATH(NEW.body_json,'one','$.baseline')=0 OR (JSON_LENGTH(JSON_EXTRACT(NEW.body_json,'$.baseline'))=16 AND JSON_EXTRACT(NEW.body_json,'$.baseline.id')=NEW.question_id AND JSON_EXTRACT(NEW.body_json,'$.baseline.patientId')=NEW.patient_id AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.kind'))='QUESTION' AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.id'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.id') BETWEEN 1 AND 9007199254740991 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.patientId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.patientId') BETWEEN 1 AND 9007199254740991 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.userId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.userId') BETWEEN 1 AND 9007199254740991 AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.kind'))='STRING' AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.title'))='STRING' AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.status'))='STRING' AND (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId'))='NULL' OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId') BETWEEN 1 AND 9007199254740991)) AND (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorId'))='NULL' OR (JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorId'))='INTEGER' AND JSON_EXTRACT(NEW.body_json,'$.baseline.actorId') BETWEEN 1 AND 9007199254740991)) AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorName')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.eventAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifyAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifiedAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.escalatedAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.dataJson')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.createdAt')) IN ('STRING','NULL') AND JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.updatedAt')) IN ('STRING','NULL') AND CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.id'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.id'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.patientId'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.patientId'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.userId'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.userId'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.kind'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.kind'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.title'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.title'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.status'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.status'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.assignedUserId'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorId'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorId'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorName'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.actorName'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.eventAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.eventAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifyAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifyAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifiedAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.notifiedAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.escalatedAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.escalatedAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.dataJson'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.dataJson'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.createdAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.createdAt'))) END+CASE WHEN JSON_TYPE(JSON_EXTRACT(NEW.body_json,'$.baseline.updatedAt'))='NULL' THEN 0 ELSE OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(NEW.body_json,'$.baseline.updatedAt'))) END<=131072))),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_event';
  END IF;
END$$

CREATE TRIGGER vq_event_bu BEFORE UPDATE ON visit_question_event
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ history is immutable';
END$$

CREATE TRIGGER vq_event_bd BEFORE DELETE ON visit_question_event
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ history is immutable';
END$$

CREATE TRIGGER vq_command_bi BEFORE INSERT ON visit_question_command
FOR EACH ROW BEGIN
  IF COALESCE((NEW.actor_id BETWEEN 1 AND 9007199254740991
      AND (NEW.question_id IS NULL OR NEW.question_id BETWEEN 1 AND 9007199254740991)
      AND BINARY NEW.command_key REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      AND NEW.expected_version BETWEEN 0 AND 9007199254740990
      AND BINARY NEW.payload_hash REGEXP '^[0-9a-f]{64}$'
      AND (NEW.question_id IS NOT NULL OR NEW.expected_version=0) AND NEW.result_json IS NULL),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_command';
  END IF;
END$$

CREATE TRIGGER vq_command_bu BEFORE UPDATE ON visit_question_command
FOR EACH ROW BEGIN
  IF NEW.result_json IS NULL OR JSON_VALID(NEW.result_json)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ incomplete command receipt';
  END IF;
  IF COALESCE((NEW.actor_id BETWEEN 1 AND 9007199254740991
      AND (NEW.question_id IS NULL OR NEW.question_id BETWEEN 1 AND 9007199254740991)
      AND BINARY NEW.command_key REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      AND NEW.expected_version BETWEEN 0 AND 9007199254740990
      AND BINARY NEW.payload_hash REGEXP '^[0-9a-f]{64}$'
      AND (NEW.question_id IS NOT NULL OR NEW.expected_version=0)
      AND (NEW.id <=> OLD.id) AND (NEW.actor_id <=> OLD.actor_id) AND (BINARY NEW.command_key <=> BINARY OLD.command_key) AND (NEW.expected_version <=> OLD.expected_version) AND (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash) AND (NEW.created_at <=> OLD.created_at)
      AND OLD.result_json IS NULL AND NEW.result_json IS NOT NULL
      AND (OLD.question_id IS NULL OR NEW.question_id=OLD.question_id)
      AND NEW.question_id BETWEEN 1 AND 9007199254740991
      AND JSON_TYPE(NEW.result_json)='OBJECT' AND JSON_LENGTH(NEW.result_json)=4 AND OCTET_LENGTH(NEW.result_json)<=512
      AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.questionId'))='INTEGER' AND JSON_EXTRACT(NEW.result_json,'$.questionId') BETWEEN 1 AND 9007199254740991
      AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.version'))='INTEGER' AND JSON_EXTRACT(NEW.result_json,'$.version') BETWEEN 1 AND 9007199254740991
      AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.eventId'))='INTEGER' AND JSON_EXTRACT(NEW.result_json,'$.eventId') BETWEEN 1 AND 9007199254740991
      AND JSON_EXTRACT(NEW.result_json,'$.questionId')=NEW.question_id
      AND JSON_EXTRACT(NEW.result_json,'$.version')=NEW.expected_version+1
      AND JSON_TYPE(JSON_EXTRACT(NEW.result_json,'$.state'))='STRING' AND BINARY JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.state')) IN ('OPEN','UNRESOLVED','ANSWERED','FOLLOW_UP','CLOSED')
      AND EXISTS(SELECT 1 FROM visit_question_state s JOIN visit_question_event e ON e.question_id=s.question_id AND e.patient_id=s.patient_id AND e.version=s.lock_version AND BINARY e.to_state=s.state WHERE s.question_id=NEW.question_id AND e.id=JSON_EXTRACT(NEW.result_json,'$.eventId') AND s.lock_version=JSON_EXTRACT(NEW.result_json,'$.version') AND BINARY s.state=JSON_UNQUOTE(JSON_EXTRACT(NEW.result_json,'$.state')) AND e.actor_id=NEW.actor_id)),0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ invalid visit_question_command';
  END IF;
END$$

CREATE TRIGGER vq_command_bd BEFORE DELETE ON visit_question_command
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VQ history is immutable';
END$$

DELIMITER ;
