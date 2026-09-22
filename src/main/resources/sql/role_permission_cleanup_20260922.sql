-- Remove obsolete disease-label roles and the duplicate clinician role.
-- Business code uses the canonical roles: admin, doctor, patient, family, user.
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
