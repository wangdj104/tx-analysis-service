-- Read-only fail-closed preflight for the separate alert workflow installation.
-- Select the intended application schema; stop all writers, take/restore-test a full backup.
-- Stop on any error; never use mysql --force. Existing managed objects are NOT overwritten.
-- This single-use path rejects partial/prior installation and unknown names for operator review.
-- It does not inspect or print patient prose, enable features, grant access or send notifications.
SELECT DATABASE() AS selected_schema,VERSION() AS server_version,@@sql_mode AS sql_mode;
-- QUOTE() preserves the legacy note comment under the supported default escaping mode.
SET @aw57_ok=(FIND_IN_SET('NO_BACKSLASH_ESCAPES',@@sql_mode)=0);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS supported_escaping','SELECT * FROM information_schema.AW57_STOP_ESCAPING_MODE');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SHOW VARIABLES WHERE Variable_name IN ('log_bin','log_bin_trust_function_creators');
SET @aw57_ok=(@@GLOBAL.log_bin=0 OR @@GLOBAL.log_bin_trust_function_creators=1 OR EXISTS(SELECT 1 FROM information_schema.user_privileges WHERE privilege_type='SUPER' AND grantee=CONCAT(CHAR(39),SUBSTRING_INDEX(CURRENT_USER(),'@',1),CHAR(39),'@',CHAR(39),SUBSTRING_INDEX(CURRENT_USER(),'@',-1),CHAR(39))));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS trigger_privilege_review','SELECT * FROM information_schema.AW57_STOP_TRIGGER_PRIVILEGE_REVIEW');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(DATABASE() IS NOT NULL AND VERSION() NOT LIKE '%MariaDB%' AND (VERSION() LIKE '8.0.%' OR (VERSION() LIKE '5.7.%' AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=18)));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS supported_mysql','SELECT * FROM information_schema.AW57_STOP_SUPPORTED_MYSQL');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('patient','health_measurement','bp_self_monitor_record','alert_record','alert_event','doctor_care_plan','care_plan_action','care_plan_event','care_plan_evidence','care_access_grant','care_nurse_assignment') AND engine='InnoDB' AND table_type='BASE TABLE')=11);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS prerequisite_tables','SELECT * FROM information_schema.AW57_STOP_PREREQUISITE_TABLES');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('alert_source_identity','alert_source_revision','alert_source_link','alert_evidence','alert_command','measurement_command','alert_notification')));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS uninstalled_tables','SELECT * FROM information_schema.AW57_STOP_UNINSTALLED_TABLES');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='alert_record' AND column_name IN ('workflow_version','workflow_state','workflow_mode','cycle_no','source_revision','metric_key','responsible_user_id','due_at','accepted_by','accepted_at','lock_version','current_handling_event_id','current_feedback_event_id','source_review_required','reassignment_required','classification_json','closed_by','closed_at','closure_reason')) OR (table_name='alert_event' AND column_name IN ('event_type','cycle_no','actor_role','actor_relation','entry_mode','occurred_at','recorded_at','payload_json')))));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS uninstalled_columns','SELECT * FROM information_schema.AW57_STOP_UNINSTALLED_COLUMNS');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND (trigger_name LIKE 'aw57\_%' OR event_object_table IN ('alert_record','alert_event','alert_source_identity','alert_source_revision','alert_source_link','alert_evidence','alert_command','measurement_command','alert_notification'))) AND NOT EXISTS(SELECT 1 FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name LIKE 'aw57\_%'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS unknown_trigger_routine','SELECT * FROM information_schema.AW57_STOP_UNKNOWN_TRIGGER_ROUTINE');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name IN ('uk_aw_source_identity','idx_aw_source_patient','fk_aw_source_patient','uk_aw_source_revision','fk_aw_revision_identity','uk_aw_source_metric','idx_aw_link_alert','fk_aw_link_revision','fk_aw_link_alert','uk_aw_evidence_ref','fk_aw_evidence_event','uk_aw_command_actor_key','fk_aw_command_alert','uk_aw_measurement_actor_key','uk_aw_notification_dispatch','idx_aw_notification_due','fk_aw_notification_event','fk_aw_notification_patient','idx_aw_patient_queue','idx_aw_event_cycle')) AND NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name IN ('uk_aw_source_identity','idx_aw_source_patient','fk_aw_source_patient','uk_aw_source_revision','fk_aw_revision_identity','uk_aw_source_metric','idx_aw_link_alert','fk_aw_link_revision','fk_aw_link_alert','uk_aw_evidence_ref','fk_aw_evidence_event','uk_aw_command_actor_key','fk_aw_command_alert','uk_aw_measurement_actor_key','uk_aw_notification_dispatch','idx_aw_notification_due','fk_aw_notification_event','fk_aw_notification_patient','idx_aw_patient_queue','idx_aw_event_cycle')));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS reserved_name_collision','SELECT * FROM information_schema.AW57_STOP_RESERVED_NAME_COLLISION');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='patient' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS patient_id','SELECT * FROM information_schema.AW57_STOP_PATIENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_record' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_record_id','SELECT * FROM information_schema.AW57_STOP_ALERT_RECORD_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_record' AND column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_record_patient_id','SELECT * FROM information_schema.AW57_STOP_ALERT_RECORD_PATIENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='alert_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_event_alert_id','SELECT * FROM information_schema.AW57_STOP_ALERT_EVENT_ALERT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='patient_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_event_patient_id','SELECT * FROM information_schema.AW57_STOP_ALERT_EVENT_PATIENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='health_measurement' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS health_measurement_id','SELECT * FROM information_schema.AW57_STOP_HEALTH_MEASUREMENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='bp_self_monitor_record' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS bp_self_monitor_record_id','SELECT * FROM information_schema.AW57_STOP_BP_SELF_MONITOR_RECORD_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
-- Existing FK parents must have exact compatible signed identifiers and usable primary indexes.
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND is_nullable='NO'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_event_id','SELECT * FROM information_schema.AW57_STOP_ALERT_EVENT_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='PRIMARY' AND seq_in_index=1 AND column_name='id' AND sub_part IS NULL AND non_unique=0 AND index_type='BTREE') AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='patient' AND index_name='PRIMARY')=1);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS patient_primary_id','SELECT * FROM information_schema.AW57_STOP_PATIENT_PRIMARY_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_record' AND index_name='PRIMARY' AND seq_in_index=1 AND column_name='id' AND sub_part IS NULL AND non_unique=0 AND index_type='BTREE') AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_record' AND index_name='PRIMARY')=1);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_record_primary_id','SELECT * FROM information_schema.AW57_STOP_ALERT_RECORD_PRIMARY_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_event' AND index_name='PRIMARY' AND seq_in_index=1 AND column_name='id' AND sub_part IS NULL AND non_unique=0 AND index_type='BTREE') AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='alert_event' AND index_name='PRIMARY')=1);
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_event_primary_id','SELECT * FROM information_schema.AW57_STOP_ALERT_EVENT_PRIMARY_ID');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
-- The stable legacy-column discriminator is reserved; conflicting legacy rows require operator review.
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM alert_record WHERE alert_type='MEASUREMENT_FOLLOW_UP'));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS reserved_marker_rows','SELECT * FROM information_schema.AW57_STOP_RESERVED_MARKER_ROWS');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SET @aw57_ok=(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_event' AND column_name='note' AND data_type='varchar' AND is_nullable='YES' AND column_default IS NULL AND extra=''));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS note_compatible','SELECT * FROM information_schema.AW57_STOP_NOTE_COMPATIBLE');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;
SELECT COUNT(*) AS preserved_legacy_orphan_events FROM alert_event e LEFT JOIN alert_record a ON a.id=e.alert_id WHERE a.id IS NULL;
SET @aw57_ok=(NOT EXISTS(SELECT 1 FROM alert_record a LEFT JOIN patient p ON p.id=a.patient_id WHERE p.id IS NULL));
SET @aw57_sql=IF(@aw57_ok,'SELECT 1 AS alert_patient','SELECT * FROM information_schema.AW57_STOP_ALERT_PATIENT');
PREPARE aw57_stmt FROM @aw57_sql; EXECUTE aw57_stmt; DEALLOCATE PREPARE aw57_stmt;

-- Any existing revision table is a prior/partial installation, including one without action_versions_json.
-- Never substitute unknown historical qualification metadata with an empty object.
