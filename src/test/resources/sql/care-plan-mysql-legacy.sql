-- Synthetic records only, inserted BEFORE the collaboration migration.
INSERT INTO patient(id,name,user_id,remark,create_time,update_time)
VALUES(7001,'Synthetic MySQL Patient',7001,'Disposable acceptance fixture','2026-10-03 05:00:00','2026-10-03 05:00:00'),
      (7002,'Other Synthetic MySQL Patient',7002,'Disposable acceptance fixture','2026-10-03 05:00:00','2026-10-03 05:00:00');
INSERT INTO doctor_care_plan(id,doctor_user_id,patient_id,title,plan_type,instructions,target_date,status,created_at,updated_at)
VALUES(7100,7003,7001,'Synthetic legacy internal plan','FOLLOW_UP','Legacy full-field preservation: 引用 😀 and apostrophe '' remain unchanged','2026-11-02','ACTIVE','2026-09-20 12:34:56','2026-09-21 13:45:57');
