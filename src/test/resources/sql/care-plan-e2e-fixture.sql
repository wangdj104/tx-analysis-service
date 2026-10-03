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
