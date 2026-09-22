import { canAccessWorkspace, resolveWorkspaceEntry } from './workspaceAccess.js';

const MODULE_MENUS = {
  '/care-journey': {
    path: '/care-journey', label: 'Care Journey', icon: 'Connection',
    children: [
      { path: '/care-journey?tab=measurements', label: 'All Measurements', icon: 'Odometer' },
      { path: '/care-journey?tab=appointments', label: 'Appointments & Visits', icon: 'Calendar' },
      { path: '/care-journey?tab=consultation', label: 'Remote Consultation', icon: 'VideoCamera' },
      { path: '/care-journey?tab=recovery', label: 'Inpatient & Recovery', icon: 'FirstAidKit' },
      { path: '/care-journey?tab=emergency', label: 'Emergency Card', icon: 'WarnTriangleFilled' },
      { path: '/care-journey?tab=specialty', label: 'Children & Maternity', icon: 'UserFilled' },
      { path: '/care-journey?tab=mental', label: 'Mental Health', icon: 'ChatDotRound' },
      { path: '/care-journey?tab=privacy', label: 'Privacy & Access', icon: 'Lock' },
      { path: '/care-journey?tab=operations', label: 'Care Operations', icon: 'DataAnalysis' }
    ]
  },
  '/dialysis': {
    path: '/dialysis', label: 'Dialysis Management', icon: 'Histogram',
    children: [
      { path: '/dialysis?tab=data', label: 'Dialysis Records', icon: 'DocumentChecked' },
      { path: '/dialysis?tab=analysis', label: 'Trend Analysis', icon: 'TrendCharts' },
      { path: '/dialysis?tab=ai', label: 'AI analysis', icon: 'Cpu' },
      { path: '/dry-weight', label: 'Dry Weight', icon: 'ScaleToOriginal' }
    ]
  },
  '/medical-record': {
    path: '/medical-record', label: 'Medical Records', icon: 'FolderOpened',
    children: [
      { path: '/medical-record?tab=list', label: 'Record List', icon: 'FolderOpened' },
      { path: '/medical-record?tab=upload', label: 'Upload Report', icon: 'Upload' },
      { path: '/medical-record?tab=abnormal', label: 'Abnormal Results', icon: 'WarningFilled' },
      { path: '/medical-record?tab=trend', label: 'Result Trends', icon: 'DataLine' }
    ]
  },
  '/medication': {
    path: '/medication', label: 'Medication Management', icon: 'FirstAidKit',
    children: [
      { path: '/medication?tab=drugs', label: 'Medication List', icon: 'Box' },
      { path: '/medication?tab=upload', label: 'Upload and Recognize', icon: 'Camera' },
      { path: '/medication?tab=logs', label: 'Medication Log', icon: 'Notebook' },
      { path: '/medication?tab=category', label: 'Browse Categories', icon: 'Grid' },
      { path: '/medication?tab=remind', label: 'Medication Reminders', icon: 'Bell' }
    ]
  },
  '/health-analysis': {
    path: '/health-analysis', label: 'Health Analytics', icon: 'DataAnalysis',
    children: [
      { path: '/health-analysis?tab=complication', label: 'Complication Tracking', icon: 'Warning' },
      { path: '/health-analysis?tab=alert', label: 'Health Alerts', icon: 'Bell' },
      { path: '/health-analysis?tab=bp-pattern', label: 'Blood Pressure Pattern Analysis', icon: 'TrendCharts' },
      { path: '/health-analysis?tab=nutrition', label: 'Nutrition Diary', icon: 'Apple' },
      { path: '/health-analysis?tab=nutrition-assessment', label: 'Nutrition Assessment', icon: 'DataAnalysis' },
      { path: '/health-analysis?tab=health-report', label: 'Health Report', icon: 'DocumentChecked' },
      { path: '/health-analysis?tab=automation', label: 'Automated Analysis', icon: 'Timer' },
      { path: '/health-analysis?tab=data-export', label: 'Data Export', icon: 'Download' }
    ]
  },
  '/family-health': {
    path: '/family-health', label: 'Care Plan', icon: 'Calendar',
    children: [
      { path: '/family-health?tab=today', label: "Today's Tasks", icon: 'Calendar' },
      { path: '/family-health?tab=timeline', label: 'Health Timeline', icon: 'Clock' },
      { path: '/family-health?tab=schedule', label: 'Dialysis Schedule', icon: 'Date' },
      { path: '/family-health?tab=target', label: 'Personal Goals', icon: 'Aim' },
      { path: '/family-health?tab=summary', label: 'Visit Summary', icon: 'DocumentChecked' }
    ]
  }
};

const ENGLISH_MENU_LABELS = {
  '/monitoring': 'Health Overview',
  '/clinical-workbench': 'Clinical Workbench',
  '/doctor-workspace': 'Doctor Workspace',
  '/care-journey': 'Care Journey',
  '/dialysis': 'Dialysis Management',
  '/dry-weight': 'Dry Weight',
  '/bp-self-monitor': 'Blood Pressure & Glucose',
  '/medical-record': 'Medical Records',
  '/medication': 'Medication Management',
  '/health-analysis': 'Health Analytics',
  '/family-health': 'Care Plan',
  '/system/patient': 'Patient Management',
  '/settings/notifications': 'Notification Settings',
  '/system/user': 'User Management',
  '/system/role': 'Role Management',
  '/system/menu': 'Menu Management',
  '/system/audit': 'Audit Log',
  '/system/branding': 'Platform Branding'
};

const ENGLISH_MENU_CODE_LABELS = {
  workspace: 'Health Overview',
  'patient-center': 'Patient Center',
  dialysis: 'Dialysis Management',
  'doctor-workspace': 'Care Journey',
  'clinical-record': 'Medical Records',
  medication: 'Medication Management',
  'health-monitoring': 'Health Monitoring',
  'analysis-report': 'Analytics & Reports',
  system: 'System Administration',
  'legacy-dashboard': 'Legacy Dashboard',
  'dialysis-record': 'Dialysis Records',
  'dialysis-trend': 'Trend Analysis',
  'dialysis-dry-weight': 'Dry Weight',
  'dialysis-ai': 'AI Analysis',
  'dialysis-schedule': 'Dialysis Schedule',
  'clinical-record-list': 'Record List',
  'clinical-record-upload': 'Upload Report',
  'clinical-abnormal': 'Abnormal Results',
  'clinical-trend': 'Result Trends',
  'medication-catalog': 'Medication List',
  'medication-log': 'Medication Log',
  'medication-reminder': 'Medication Reminders',
  'system-user': 'User Management',
  'system-role': 'Role Management',
  'system-menu': 'Menu Management',
  'system-audit': 'Audit Log',
  'platform-branding': 'Platform Branding',
  'health-vitals': 'Blood Pressure & Glucose',
  'health-complication': 'Complication Tracking',
  'health-alert': 'Health Alerts',
  'health-nutrition': 'Nutrition Diary',
  'health-nutrition-assessment': 'Nutrition Assessment',
  'analysis-bp-pattern': 'Blood Pressure Pattern Analysis',
  'analysis-health-report': 'Health Report',
  'analysis-data-export': 'Data Export',
  'clinical-share': 'Clinical Data Sharing',
  'patient-profile': 'Patient Management',
  'patient-care': 'Care Plan',
  'notification-settings': 'Notification Settings'
};

export function getEnglishMenuLabel(path, fallback = '', code = '') {
  const rawPath = String(path || '');
  const normalizedPath = rawPath.split('?')[0];
  const exactDefinition = Object.values(MODULE_MENUS)
    .flatMap(item => [item, ...(item.children || [])])
    .find(item => item.path === rawPath);
  return exactDefinition?.label
    || ENGLISH_MENU_LABELS[rawPath]
    || ENGLISH_MENU_CODE_LABELS[String(code || '').toLowerCase()]
    || (!rawPath.includes('?') ? ENGLISH_MENU_LABELS[normalizedPath] : '')
    || fallback;
}

/** keepafter endgroup and displayinformation, supplementcompletegetauthorize moduleentry, andRemovecannot access link.  */
export function normalizeWorkspaceMenus(items, { menuPaths = [], roleCodes = [] } = {}) {
  const canAccess = path => canAccessWorkspace(path, menuPaths, roleCodes);
  const nodesByPath = new Map();

  // firstcollectAllafter endentry, againsupplementDefaultentry, keepcertificateafter end textrecord, Icon and belonggroupexcellentfirst.
  function copyItems(sourceItems) {
    const result = [];
    for (const item of sourceItems || []) {
      const path = item.path || '';
      const existing = path && nodesByPath.get(path);
      if (existing) {
        existing.children.push(...copyItems(item.children));
        continue;
      }

      const node = { ...item, path, label: getEnglishMenuLabel(path, item.label, item.menuCode), children: [] };
      if (path) nodesByPath.set(path, node);
      node.children.push(...copyItems(item.children));
      result.push(node);
    }
    return result;
  }

  const menus = copyItems(items);
  for (const [path, definition] of Object.entries(MODULE_MENUS)) {
    const moduleMenu = nodesByPath.get(path);
    if (!moduleMenu) continue;
    for (const child of definition.children) {
      if (nodesByPath.has(child.path) || !canAccess(child.path)) continue;
      const node = { ...child, children: [] };
      moduleMenu.children.push(node);
      nodesByPath.set(child.path, node);
    }
  }

  function filterItems(sourceItems) {
    const result = [];
    for (const item of sourceItems) {
      item.children = filterItems(item.children);
      if (item.path && !canAccess(item.path)) item.path = '';
      item.entryPath = item.path ? resolveWorkspaceEntry(item.path, menuPaths, roleCodes) : item.children[0]?.entryPath || '';
      if (item.path || item.children.length) result.push(item);
    }
    return result;
  }

  return filterItems(menus);
}

/** MenuAPIcannot usetimeuserelativesamePermissionrulebuildstandardnavigation.  */
export function getFallbackWorkspaceMenus(menuPaths = [], roleCodes = []) {
  return normalizeWorkspaceMenus([
    { path: '/monitoring', label: 'Health Overview', icon: 'Monitor' },
    { path: '/clinical-workbench', label: 'Clinical Workbench', icon: 'FirstAidKit' },
    { path: '/doctor-workspace', label: 'Doctor Workspace', icon: 'FirstAidKit' },
    MODULE_MENUS['/care-journey'],
    MODULE_MENUS['/dialysis'],
    { path: '/bp-self-monitor', label: 'Blood Pressure & Glucose', icon: 'Odometer' },
    MODULE_MENUS['/medical-record'],
    MODULE_MENUS['/medication'],
    MODULE_MENUS['/health-analysis'],
    MODULE_MENUS['/family-health'],
    { path: '/system/patient', label: 'Patient Management', icon: 'UserFilled' },
    { path: '/settings/notifications', label: 'Notification Settings', icon: 'Bell' },
    {
      path: '', label: 'System Administration', icon: 'Setting',
      children: [
        { path: '/system/user', label: 'User Management', icon: 'User' },
        { path: '/system/role', label: 'Role Management', icon: 'Avatar' },
        { path: '/system/menu', label: 'Menu Management', icon: 'Menu' },
        { path: '/system/audit', label: 'Audit Log', icon: 'Document' }
        ,{ path: '/system/branding', label: 'Platform Branding', icon: 'Brush' }
      ]
    }
  ], { menuPaths, roleCodes });
}
