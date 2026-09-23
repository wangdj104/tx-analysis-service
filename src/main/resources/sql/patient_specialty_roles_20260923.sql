-- Apply once to an existing database after a backup. Patient assignments are intentionally not inferred.
CREATE TABLE IF NOT EXISTS `patient_specialty_role` (
  `patient_id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  `assigned_by` BIGINT NOT NULL,
  `assigned_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`patient_id`,`role_id`),
  KEY `idx_specialty_role` (`role_id`),
  CONSTRAINT `fk_patient_specialty_patient` FOREIGN KEY (`patient_id`) REFERENCES `patient` (`id`),
  CONSTRAINT `fk_patient_specialty_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Patient-specific specialty roles';

INSERT IGNORE INTO `sys_role` (`role_code`,`role_name`,`description`,`status`) VALUES
('specialty_dialysis','Dialysis Patient','Patient-specific dialysis menu scope',1);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.id IN (1,11,12,13,14,15) WHERE r.role_code='specialty_dialysis'
ON DUPLICATE KEY UPDATE role_id=role_id;

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.id IN (1,11,12,13,14,15) WHERE r.role_code='doctor'
ON DUPLICATE KEY UPDATE role_id=role_id;
