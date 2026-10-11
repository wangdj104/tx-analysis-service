-- 用途：补患者专病角色表与角色/菜单关系，不推断或创建患者的专病分配。
-- 前提：基础 patient、sys_role、sys_menu 等表已存在；运行账号仅授权应用库所需权限。
-- 重复执行：当前 MySQL/MariaDB 每次应用启动都会执行，手工跑过也不会关闭；本版本没有关闭开关。
-- 风险：仍会写入角色及菜单关系，DDL 隐式提交；发布前完整备份并演练恢复，需 CREATE/SELECT/INSERT/UPDATE，外键还可能需 REFERENCES。

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
('specialty_dialysis','透析患者','患者档案的透析专病菜单范围',1);

UPDATE `sys_role`
SET `role_name` = IF(`role_name` = 'Dialysis Patient', '透析患者', `role_name`),
    `description` = IF(`description` = 'Patient-specific dialysis menu scope', '患者档案的透析专病菜单范围', `description`)
WHERE `role_code` = 'specialty_dialysis'
  AND (`role_name` = 'Dialysis Patient' OR `description` = 'Patient-specific dialysis menu scope');

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.id IN (1,11,12,13,14,15) WHERE r.role_code='specialty_dialysis'
ON DUPLICATE KEY UPDATE role_id=role_id;

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.id IN (1,11,12,13,14,15) WHERE r.role_code='doctor'
ON DUPLICATE KEY UPDATE role_id=role_id;
