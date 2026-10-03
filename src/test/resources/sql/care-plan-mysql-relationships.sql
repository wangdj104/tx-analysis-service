-- DB-level synthetic relationship fixture, not API lifecycle/E2E evidence.
INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,instructions,workflow_version,lifecycle,legacy_source_id,lock_version,created_at,updated_at)
VALUES(7200,7003,7001,'Synthetic published snapshot','Synthetic instructions',1,'ACTIVE',7100,2,'2026-10-03 05:00:00','2026-10-03 05:00:00'),
      (8000,7003,7001,'Synthetic SQL concurrency plan','SQL harness only',1,'DRAFT',NULL,0,'2026-10-03 05:00:00','2026-10-03 05:00:00');
INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at,published_by,published_at)
VALUES(7201,7200,1,'PUBLISHED','Synthetic published snapshot','Synthetic instructions','FOLLOW_UP','[{"ordinal":1,"instruction":"Synthetic action","evidence":[{"sourceType":"MEASUREMENT","sourceId":7601}]}]',7003,'2026-10-03 05:00:00.123456','2026-10-03 05:01:00.234567',7003,'2026-10-03 05:01:00.234567'),
      (7202,7200,2,'DRAFT','Synthetic next snapshot','Synthetic next instructions','FOLLOW_UP','[]',7003,'2026-10-03 05:02:00.345678','2026-10-03 05:02:00.345678',NULL,NULL);
UPDATE doctor_care_plan SET current_revision_id=7201,draft_revision_id=7202 WHERE id=7200;
INSERT INTO care_plan_action(id,plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,status,lock_version,first_submitted_at,latest_submitted_at,review_waiting_since,created_at,updated_at)
VALUES(7301,7200,7201,7001,1,'Synthetic one-time action 😀','2026-11-01 05:30:00.123456',7001,'SUBMITTED',2,'2026-10-03 05:03:00.456789','2026-10-03 05:04:00.567890','2026-10-03 05:04:00.567890','2026-10-03 05:01:00.234567','2026-10-03 05:04:00.567890'),
      (7302,7200,7201,7001,2,'Synthetic second action','2026-11-01 06:30:00.123456',7002,'CONFIRMED',3,'2026-10-03 05:03:00.456789','2026-10-03 05:03:00.456789',NULL,'2026-10-03 05:01:00.234567','2026-10-03 05:05:00.678901');
INSERT INTO care_plan_event(id,patient_id,plan_id,revision_id,action_id,actor_id,actor_name,actor_role,actor_relation,entry_mode,event_type,note,occurred_at,recorded_at,payload_json)
VALUES(7401,7001,7200,7201,NULL,7003,'Synthetic Doctor','doctor',NULL,NULL,'PUBLISHED',NULL,NULL,'2026-10-03 05:01:00.234567','{"revisionNo":1}'),
      (7402,7001,7200,7201,7301,7002,'Synthetic Family','family','Synthetic relationship','ASSISTED','SUBMITTED','Synthetic receipt with Unicode 中文 😀','2026-10-03 05:03:00.456789','2026-10-03 05:04:00.567890','{"actionStatus":"SUBMITTED"}'),
      (7403,7001,7200,7201,7301,7004,'Synthetic Nurse Snapshot','nurse',NULL,NULL,'FOLLOW_UP','Synthetic audit survives actor deletion',NULL,'2026-10-03 05:05:00.678901','{"kind":"CONTACTED"}');
INSERT INTO health_measurement(id,patient_id,metric_type,value_primary,unit,measured_at,recorded_by)
VALUES(7601,7001,'WEIGHT',60.25,'kg','2026-10-03 05:03:00',7001);
INSERT INTO medical_record(id,patient_id,user_id,record_type,patient_name,record_date)
VALUES(7602,7001,7001,'OTHER','Synthetic evidence source','2026-10-03');
INSERT INTO care_plan_evidence(id,event_id,source_type,source_id)
VALUES(7501,7402,'MEASUREMENT',7601),(7502,7402,'MEDICAL_RECORD',7602);
INSERT INTO care_nurse_assignment(id,patient_id,nurse_user_id,assigned_by,assigned_at,expires_at,revoked_at,status)
VALUES(7701,7001,7004,7005,'2026-10-03 05:00:00.123456','2026-11-03 05:00:00.123456',NULL,'ACTIVE');
INSERT INTO care_plan_notification(id,event_id,patient_id,recipient_user_id,channel_id,dispatch_key,status,attempt_count,next_attempt_at,claimed_at,claim_token,request_id,last_result,delivered_at,created_at,updated_at)
VALUES(7801,7401,7001,7001,NULL,'7401:7001:NO_CHANNEL','NO_CHANNEL',0,NULL,NULL,NULL,NULL,'Synthetic no-channel result',NULL,'2026-10-03 05:01:00.234567','2026-10-03 05:01:00.234567'),
      (7802,7402,7001,7002,NULL,'7402:7002:SYNTHETIC','UNKNOWN',1,NULL,'2026-10-03 05:04:01.567890','synthetic-claim','synthetic-request','Synthetic uncertain transport, no real sending',NULL,'2026-10-03 05:04:00.567890','2026-10-03 05:04:01.567890');
INSERT INTO care_plan_command(id,actor_id,command_key,plan_id,expected_version,payload_hash,result_json,created_at)
VALUES(7901,7003,'00000000-0000-0000-0000-000000007901',7200,1,'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef','{"planId":7200,"eventId":7401,"version":2}','2026-10-03 05:01:00.234567');
