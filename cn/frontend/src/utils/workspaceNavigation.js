import { canAccessWorkspace, resolveWorkspaceEntry } from './workspaceAccess.js';

const MODULE_MENUS = {
  '/dialysis': {
    path: '/dialysis', label: '透析管理', icon: 'Histogram',
    children: [
      { path: '/dialysis?tab=data', label: '透析记录', icon: 'DocumentChecked' },
      { path: '/dialysis?tab=analysis', label: '趋势分析', icon: 'TrendCharts' },
      { path: '/dialysis?tab=ai', label: 'AI 分析', icon: 'Cpu' },
      { path: '/dry-weight', label: '干体重管理', icon: 'ScaleToOriginal' }
    ]
  },
  '/medical-record': {
    path: '/medical-record', label: '医疗记录', icon: 'FolderOpened',
    children: [
      { path: '/medical-record?tab=list', label: '记录列表', icon: 'FolderOpened' },
      { path: '/medical-record?tab=upload', label: '上传报告', icon: 'Upload' },
      { path: '/medical-record?tab=abnormal', label: '异常结果', icon: 'WarningFilled' },
      { path: '/medical-record?tab=trend', label: '指标趋势', icon: 'DataLine' }
    ]
  },
  '/medication': {
    path: '/medication', label: '用药管理', icon: 'FirstAidKit',
    children: [
      { path: '/medication?tab=drugs', label: '药品列表', icon: 'Box' },
      { path: '/medication?tab=upload', label: '上传识别', icon: 'Camera' },
      { path: '/medication?tab=logs', label: '用药记录', icon: 'Notebook' },
      { path: '/medication?tab=category', label: '分类浏览', icon: 'Grid' },
      { path: '/medication?tab=remind', label: '用药提醒', icon: 'Bell' }
    ]
  },
  '/health-analysis': {
    path: '/health-analysis', label: '健康分析', icon: 'DataAnalysis',
    children: [
      { path: '/health-analysis?tab=complication', label: '并发症跟踪', icon: 'Warning' },
      { path: '/health-analysis?tab=alert', label: '健康告警', icon: 'Bell' },
      { path: '/health-analysis?tab=bp-pattern', label: '血压规律分析', icon: 'TrendCharts' },
      { path: '/health-analysis?tab=nutrition', label: '营养日记', icon: 'Apple' },
      { path: '/health-analysis?tab=nutrition-assessment', label: '营养评估', icon: 'DataAnalysis' },
      { path: '/health-analysis?tab=health-report', label: '健康报告', icon: 'DocumentChecked' },
      { path: '/health-analysis?tab=automation', label: '自动分析', icon: 'Timer' },
      { path: '/health-analysis?tab=data-export', label: '数据导出', icon: 'Download' }
    ]
  },
  '/family-health': {
    path: '/family-health', label: '照护计划', icon: 'Calendar',
    children: [
      { path: '/family-health?tab=today', label: '今日任务', icon: 'Calendar' },
      { path: '/family-health?tab=timeline', label: '健康时间线', icon: 'Clock' },
      { path: '/family-health?tab=schedule', label: '透析排班', icon: 'Date' },
      { path: '/family-health?tab=target', label: '个人目标', icon: 'Aim' },
      { path: '/family-health?tab=summary', label: '就诊摘要', icon: 'DocumentChecked' }
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
    { path: '/monitoring', label: '健康总览', icon: 'Monitor' },
    { path: '/clinical-workbench', label: '临床工作台', icon: 'FirstAidKit' },
    { path: '/doctor-workspace', label: '医生工作台', icon: 'FirstAidKit' },
    MODULE_MENUS['/dialysis'],
    { path: '/bp-self-monitor', label: '血压与血糖', icon: 'Odometer' },
    MODULE_MENUS['/medical-record'],
    MODULE_MENUS['/medication'],
    MODULE_MENUS['/health-analysis'],
    MODULE_MENUS['/family-health'],
    { path: '/system/patient', label: '患者管理', icon: 'UserFilled' },
    { path: '/settings/notifications', label: '通知设置', icon: 'Bell' },
    {
      path: '', label: '系统管理', icon: 'Setting',
      children: [
        { path: '/system/user', label: '用户管理', icon: 'User' },
        { path: '/system/role', label: '角色管理', icon: 'Avatar' },
        { path: '/system/menu', label: '菜单管理', icon: 'Menu' },
        { path: '/system/audit', label: '审计日志', icon: 'Document' }
      ]
    }
  ], { menuPaths, roleCodes });
}
