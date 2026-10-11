-- 用途：先迁移旧 clinician/疾病标签账号绑定，再删除旧角色与关联并缩减菜单权限。
-- 前提：基础及医生工作台角色已建立；人工核对实际账号、菜单与角色绑定后执行。
-- 重复执行：已经删除的行不会恢复，重复执行仍会按条件清理权限；不是纯加表升级。
-- 风险：涉及真实权限删除，禁止合并进启动迁移；先完整备份及恢复演练，并逐项核对清理结果。

START TRANSACTION;

-- Preserve clinical access before removing the duplicate clinician role.
INSERT IGNORE INTO sys_user_role(user_id, role_id)
SELECT ur.user_id, canonical.id
FROM sys_user_role ur
JOIN sys_role obsolete ON obsolete.id = ur.role_id AND LOWER(obsolete.role_code) = 'clinician'
JOIN sys_role canonical ON canonical.role_code = 'doctor';

-- Preserve basic patient access for accounts whose only classification was a disease-label role.
INSERT IGNORE INTO sys_user_role(user_id, role_id)
SELECT DISTINCT ur.user_id, canonical.id
FROM sys_user_role ur
JOIN sys_role obsolete ON obsolete.id = ur.role_id AND UPPER(obsolete.role_code) IN ('ESRD-7','DM-2','HBP')
JOIN sys_role canonical ON canonical.role_code = 'patient'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_user_role existing
  JOIN sys_role existing_role ON existing_role.id = existing.role_id
  WHERE existing.user_id = ur.user_id
    AND existing_role.role_code IN ('admin','doctor','patient','family','user')
);

-- A non-admin role can never receive platform administration menus.
DELETE rm
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE r.role_code <> 'admin'
  AND (
    m.menu_code IN ('system','system-user','system-role','system-menu','system-audit','platform-branding')
    OR m.permission IN ('user:manage','role:manage','menu:manage','audit:view','branding:manage')
    OR m.menu_path IN ('/system/user','/system/role','/system/menu','/system/audit','/system/branding')
  );

DELETE rm
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
WHERE UPPER(r.role_code) IN ('ESRD-7','DM-2','HBP') OR LOWER(r.role_code) = 'clinician';

DELETE ur
FROM sys_user_role ur
JOIN sys_role r ON r.id = ur.role_id
WHERE UPPER(r.role_code) IN ('ESRD-7','DM-2','HBP') OR LOWER(r.role_code) = 'clinician';

DELETE FROM sys_role
WHERE UPPER(role_code) IN ('ESRD-7','DM-2','HBP') OR LOWER(role_code) = 'clinician';

COMMIT;
