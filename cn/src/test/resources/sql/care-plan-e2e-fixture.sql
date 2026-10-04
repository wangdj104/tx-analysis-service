-- Only synthetic, disposable browser acceptance identities. No implicit grants.
-- The guarded test launcher replaces placeholder passwords with a generated BCrypt hash.
INSERT INTO sys_user(id,username,password,real_name,status) VALUES
 (9001,'care-plan-e2e-personal','not-a-login-password','Synthetic Personal',1),
 (9002,'care-plan-e2e-family','not-a-login-password','Synthetic Family',1),
 (9003,'care-plan-e2e-doctor','not-a-login-password','Synthetic Doctor',1),
 (9004,'care-plan-e2e-nurse','not-a-login-password','Synthetic Nurse',1),
 (9005,'care-plan-e2e-admin','not-a-login-password','Synthetic Administrator',1),
 (9006,'care-plan-e2e-outsider','not-a-login-password','Synthetic Outsider',1);
INSERT INTO sys_user_role(user_id,role_id) SELECT 9001,id FROM sys_role WHERE role_code='patient';
INSERT INTO sys_user_role(user_id,role_id) SELECT 9002,id FROM sys_role WHERE role_code='family';
INSERT INTO sys_user_role(user_id,role_id) SELECT 9003,id FROM sys_role WHERE role_code='doctor';
INSERT INTO sys_user_role(user_id,role_id) SELECT 9004,id FROM sys_role WHERE role_code='nurse';
INSERT INTO sys_user_role(user_id,role_id) SELECT 9005,id FROM sys_role WHERE role_code='admin';
INSERT INTO sys_user_role(user_id,role_id) SELECT 9006,id FROM sys_role WHERE role_code='patient';
INSERT INTO patient(id,name,user_id,remark) VALUES
 (9001,'Synthetic Patient A',9001,'Disposable browser fixture'),
 (9002,'Synthetic Patient B',9001,'Disposable browser fixture, same account manages another member'),
 (9003,'Synthetic Patient C',9006,'Disposable cross-patient denial fixture');
INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by,status)
 VALUES(9003,9001,9005,'ACTIVE'),(9003,9002,9005,'ACTIVE');
INSERT INTO health_measurement(id,patient_id,metric_type,value_primary,unit,measured_at,recorded_by)
 VALUES(9001,9001,'WEIGHT',60.25,'kg',CURRENT_TIMESTAMP,9001),(9003,9003,'WEIGHT',62.25,'kg',CURRENT_TIMESTAMP,9006);
INSERT INTO medical_record(id,patient_id,user_id,record_type,patient_name,record_date)
 VALUES(9001,9001,9001,'OTHER','Synthetic existing evidence',CURRENT_DATE);

-- Independent report-only fixtures. All relative instants use this database UTC
-- baseline; no old fixed-clock regression, primary patient or six login IDs change.
SET @report_now = UTC_TIMESTAMP(6);
INSERT INTO patient(id,name,user_id,medical_history,remark) VALUES
 (9101,'Synthetic Report Patient',9001,'Synthetic legacy history before refresh','Disposable report-only fixture'),
 (9102,'Synthetic Empty Report Patient',9001,NULL,'Disposable zero-row report fixture'),
 (9103,'Synthetic Limited Report Patient',9001,NULL,'Disposable independent CSV / question-limit fixture'),
 (9104,'Synthetic Outside Report Patient',9006,NULL,'Disposable cross-owner report/source denial fixture');
INSERT INTO doctor_patient_assignment(doctor_user_id,patient_id,assigned_by,status)
 VALUES(9003,9101,9005,'ACTIVE'),(9003,9102,9005,'ACTIVE'),(9003,9103,9005,'ACTIVE');
INSERT INTO health_measurement(id,patient_id,metric_type,value_primary,unit,measured_at,recorded_by)
 VALUES(19101,9101,'WEIGHT',51.91,'kg',DATE_SUB(@report_now,INTERVAL 1100 DAY),9001),
       (19104,9104,'WEIGHT',84.19,'kg',@report_now,9006);
-- More than the ordinary 1000-row maximum lie ahead of the exact linked source.
INSERT INTO health_measurement(id,patient_id,metric_type,value_primary,unit,measured_at,recorded_by)
 SELECT 30000+n,9101,'WEIGHT',60.01,'kg',DATE_SUB(@report_now,INTERVAL n HOUR),9001 FROM
 (SELECT a.n+10*b.n+100*c.n+1000*d.n AS n FROM
  (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1) d) numbers WHERE n<1001;
INSERT INTO medical_record(id,patient_id,user_id,record_type,patient_name,record_date,remark)
 VALUES(19102,9101,9001,'OTHER','Synthetic exact report medical source',DATE(@report_now),'Synthetic exact medical marker 19102'),
       (19104,9104,9006,'OTHER','Synthetic outside medical source',DATE(@report_now),'Synthetic cross-owner private marker');
INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,plan_type,instructions,status,workflow_version,lifecycle,current_revision_id,lock_version,created_at,updated_at)
 VALUES(19101,9003,9101,'Synthetic report published plan','FOLLOW_UP','Saved successfully','ACTIVE',1,'ACTIVE',NULL,0,@report_now,@report_now);
INSERT INTO care_plan_revision(id,plan_id,revision_no,status,title,instructions,plan_type,draft_json,created_by,created_at,updated_at,published_by,published_at)
 VALUES(19101,19101,1,'PUBLISHED','Synthetic report published plan','Saved successfully','FOLLOW_UP','{"actions":[{"ordinal":1,"evidence":[{"sourceType":"MEASUREMENT","sourceId":19101},{"sourceType":"MEDICAL_RECORD","sourceId":19102}]},{"ordinal":2,"evidence":[]},{"ordinal":3,"evidence":[]},{"ordinal":4,"evidence":[]}]}',9003,DATE_SUB(@report_now,INTERVAL 50 DAY),DATE_SUB(@report_now,INTERVAL 50 DAY),9003,DATE_SUB(@report_now,INTERVAL 50 DAY));
UPDATE doctor_care_plan SET current_revision_id=19101 WHERE id=19101;
INSERT INTO care_plan_action(id,plan_id,revision_id,patient_id,ordinal,instruction,due_at,assigned_user_id,status,first_submitted_at,latest_submitted_at,review_waiting_since,created_at,updated_at) VALUES
 (19101,19101,19101,9101,1,'Synthetic 护理原文 <script>synthetic-report</script>',DATE_SUB(@report_now,INTERVAL 2 DAY),9001,'OPEN',NULL,NULL,NULL,DATE_SUB(@report_now,INTERVAL 50 DAY),@report_now),
 (19102,19101,19101,9101,2,'Synthetic difficulty outside selected activity dates',DATE_SUB(@report_now,INTERVAL 1 DAY),9001,'NEEDS_HELP',NULL,NULL,NULL,DATE_SUB(@report_now,INTERVAL 50 DAY),@report_now),
 (19103,19101,19101,9101,3,'Synthetic submitted before deadline, waiting for review',DATE_SUB(@report_now,INTERVAL 2 DAY),9001,'SUBMITTED',DATE_SUB(@report_now,INTERVAL 4 DAY),DATE_SUB(@report_now,INTERVAL 3 DAY),DATE_SUB(@report_now,INTERVAL 3 DAY),DATE_SUB(@report_now,INTERVAL 50 DAY),@report_now),
 (19104,19101,19101,9101,4,'Synthetic doctor reviewed action',DATE_SUB(@report_now,INTERVAL 2 DAY),9001,'CONFIRMED',DATE_SUB(@report_now,INTERVAL 4 DAY),DATE_SUB(@report_now,INTERVAL 4 DAY),NULL,DATE_SUB(@report_now,INTERVAL 50 DAY),@report_now);
INSERT INTO care_plan_event(id,patient_id,plan_id,revision_id,action_id,actor_id,actor_name,actor_role,actor_relation,entry_mode,event_type,note,occurred_at,recorded_at,payload_json) VALUES
 (19101,9101,19101,19101,NULL,9003,'Synthetic Doctor','DOCTOR','ASSIGNED_DOCTOR',NULL,'PLAN_PUBLISHED','Synthetic publication',NULL,DATE_SUB(@report_now,INTERVAL 50 DAY),'{}'),
 (19102,9101,19101,19101,19102,9002,'Synthetic historical Family','FAMILY','AUTHORIZED_FAMILY','ASSISTED','HELP_REQUESTED','Synthetic old difficulty stays visible',DATE_SUB(@report_now,INTERVAL 41 DAY),DATE_SUB(@report_now,INTERVAL 40 DAY),'{"actionStatus":"NEEDS_HELP"}'),
 (19103,9101,19101,19101,19102,9004,'Synthetic historical Nurse','NURSE','ASSIGNED_NURSE',NULL,'FOLLOW_UP_RECORDED','Synthetic old administrative contact',NULL,DATE_SUB(@report_now,INTERVAL 39 DAY),'{"kind":"CONTACTED"}'),
 (19104,9101,19101,19101,19101,9001,'Synthetic Personal','OWNER','SELF','SELF','RECEIPT_SUBMITTED','Synthetic initial receipt',DATE_SUB(@report_now,INTERVAL 6 DAY),DATE_SUB(@report_now,INTERVAL 5 DAY),'{"actionStatus":"SUBMITTED"}'),
 (19105,9101,19101,19101,19101,9003,'Synthetic Doctor','DOCTOR','ASSIGNED_DOCTOR',NULL,'RECEIPT_RETURNED','Synthetic supplement required',NULL,DATE_SUB(@report_now,INTERVAL 4 DAY),'{"actionStatus":"OPEN"}'),
 (19106,9101,19101,19101,19103,9001,'Synthetic Personal','OWNER','SELF','SELF','RECEIPT_SUBMITTED','Synthetic first submitted before deadline',DATE_SUB(@report_now,INTERVAL 5 DAY),DATE_SUB(@report_now,INTERVAL 4 DAY),'{"actionStatus":"SUBMITTED"}'),
 (19107,9101,19101,19101,19103,9003,'Synthetic Doctor','DOCTOR','ASSIGNED_DOCTOR',NULL,'RECEIPT_RETURNED','Synthetic clarify then resubmit',NULL,DATE_SUB(DATE_SUB(@report_now,INTERVAL 3 DAY),INTERVAL 1 HOUR),'{"actionStatus":"OPEN"}'),
 (19108,9101,19101,19101,19103,9002,'Synthetic historical Family','FAMILY','AUTHORIZED_FAMILY','ASSISTED','RECEIPT_SUBMITTED',CONCAT(CHAR(9),'=SUM(1,2)',CHAR(13),CHAR(10),'Synthetic "quoted", text'),DATE_SUB(@report_now,INTERVAL 4 DAY),DATE_SUB(@report_now,INTERVAL 3 DAY),'{"actionStatus":"SUBMITTED"}'),
 (19109,9101,19101,19101,19104,9004,'Synthetic historical Nurse','NURSE','ASSIGNED_NURSE','ASSISTED','RECEIPT_SUBMITTED','Synthetic nurse assisted receipt',DATE_SUB(@report_now,INTERVAL 5 DAY),DATE_SUB(@report_now,INTERVAL 4 DAY),'{"actionStatus":"SUBMITTED"}'),
 (19110,9101,19101,19101,19104,9003,'Synthetic Doctor','DOCTOR','ASSIGNED_DOCTOR',NULL,'RECEIPT_CONFIRMED','Synthetic doctor review is not efficacy proof',NULL,DATE_SUB(@report_now,INTERVAL 3 DAY),'{"actionStatus":"CONFIRMED"}');
INSERT INTO care_plan_evidence(event_id,source_type,source_id) VALUES(19104,'MEASUREMENT',19101),(19104,'MEDICAL_RECORD',19102);
INSERT INTO care_item(id,patient_id,user_id,kind,title,status,actor_id,actor_name,event_at,data_json,created_at,updated_at) VALUES
 (19101,9101,9001,'QUESTION','Synthetic question outside activity period','OPEN',9001,'Synthetic Personal',DATE_SUB(@report_now,INTERVAL 80 DAY),'{"description":"Synthetic original question description"}',DATE_SUB(@report_now,INTERVAL 80 DAY),DATE_SUB(@report_now,INTERVAL 70 DAY)),
 (19102,9101,9001,'QUESTION','Synthetic answered question','ANSWERED',9002,'Synthetic historical Family',DATE_SUB(@report_now,INTERVAL 80 DAY),'{"answer":"Synthetic recorded answer, not an independent doctor verification","followUp":"Synthetic question follow-up"}',DATE_SUB(@report_now,INTERVAL 80 DAY),DATE_SUB(@report_now,INTERVAL 70 DAY)),
 (19103,9101,9001,'QUESTION','Synthetic cancelled question','CANCELLED',9001,'Synthetic Personal',NULL,'{}',@report_now,@report_now),
 (19104,9101,9001,'QUESTION','Synthetic unknown question status','LEGACY_UNKNOWN',9001,'Synthetic Personal',NULL,'{}',@report_now,@report_now);
INSERT INTO care_item(id,patient_id,user_id,kind,title,status,actor_id,actor_name,data_json,created_at,updated_at)
 SELECT 40000+n,9103,9001,'QUESTION',CONCAT('Synthetic capacity question ',n),'OPEN',9001,'Synthetic Personal','{}',@report_now,@report_now FROM
 (SELECT a.n+10*b.n+100*c.n AS n FROM
  (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2) c) numbers WHERE n<201;
