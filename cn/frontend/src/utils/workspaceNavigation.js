import { canAccessWorkspace, resolveWorkspaceEntry } from './workspaceAccess.js';

const MODULE_MENUS = {
  '/care-journey': {
    path: '/care-journey', label: '健康照护全流程', icon: 'Connection',
    children: [
      { path: '/care-journey?tab=measurements', label: '全部健康指标', icon: 'Odometer' },
      { path: '/care-journey?tab=appointments', label: '预约与复诊', icon: 'Calendar' },
      { path: '/care-journey?tab=consultation', label: '远程问诊', icon: 'VideoCamera' },
      { path: '/care-journey?tab=recovery', label: '住院与康复', icon: 'FirstAidKit' },
      { path: '/care-journey?tab=emergency', label: '急救信息卡', icon: 'WarnTriangleFilled' },
      { path: '/care-journey?tab=specialty', label: '儿童与孕产', icon: 'UserFilled' },
      { path: '/care-journey?tab=mental', label: '心理健康', icon: 'ChatDotRound' },
      { path: '/care-journey?tab=privacy', label: '隐私与授权', icon: 'Lock' },
      { path: '/care-journey?tab=operations', label: '照护运营', icon: 'DataAnalysis' }
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

      const node = { ...item, path, children: [] };
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
        ,{ path: '/system/branding', label: '平台品牌', icon: 'Brush' }
      ]
    }
  ], { menuPaths, roleCodes });
}
