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
      { path: '/medical-record?tab=trend', label: '结果趋势', icon: 'DataLine' }
    ]
  },
  '/medication': {
    path: '/medication', label: '用药管理', icon: 'FirstAidKit',
    children: [
      { path: '/medication?tab=drugs', label: '用药清单', icon: 'Box' },
      { path: '/medication?tab=upload', label: '上传并识别', icon: 'Camera' },
      { path: '/medication?tab=logs', label: '服药记录', icon: 'Notebook' },
      { path: '/medication?tab=category', label: '药品分类', icon: 'Grid' },
      { path: '/medication?tab=remind', label: '服药提醒', icon: 'Bell' }
    ]
  },
  '/health-analysis': {
    path: '/health-analysis', label: '健康分析', icon: 'DataAnalysis',
    children: [
      { path: '/health-analysis?tab=complication', label: '并发症跟踪', icon: 'Warning' },
      { path: '/health-analysis?tab=alert', label: '健康预警', icon: 'Bell' },
      { path: '/health-analysis?tab=bp-pattern', label: '血压模式分析', icon: 'TrendCharts' },
      { path: '/health-analysis?tab=nutrition', label: '营养日记', icon: 'Apple' },
      { path: '/health-analysis?tab=nutrition-assessment', label: '营养评估', icon: 'DataAnalysis' },
      { path: '/health-analysis?tab=health-report', label: '健康报告', icon: 'DocumentChecked' },
      { path: '/health-analysis?tab=automation', label: '自动化分析', icon: 'Timer' },
      { path: '/health-analysis?tab=data-export', label: '数据导出', icon: 'Download' }
    ]
  },
  '/family-health': {
    path: '/family-health', label: '照护计划', icon: 'Calendar',
    children: [
      { path: '/family-health?tab=today', label: '今日任务', icon: 'Calendar' },
      { path: '/family-health?tab=timeline', label: '健康时间轴', icon: 'Clock' },
      { path: '/family-health?tab=schedule', label: '透析日程', icon: 'Date' },
      { path: '/family-health?tab=target', label: '个人目标', icon: 'Aim' },
      { path: '/family-health?tab=summary', label: '就诊小结', icon: 'DocumentChecked' }
    ]
  }
};

/** 保留后端分组与展示信息，补全已授权模块入口，并移除无权访问的链接。 */
export function normalizeWorkspaceMenus(items, { menuPaths = [], roleCodes = [] } = {}) {
  const canAccess = path => canAccessWorkspace(path, menuPaths, roleCodes);
  const nodesByPath = new Map();

  // 先收集全部后端入口，再补充默认入口；后端文案、图标和归属分组优先。
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

/** 菜单 API 不可用时，使用相同权限规则构建标准导航。 */
export function getFallbackWorkspaceMenus(menuPaths = [], roleCodes = []) {
  return normalizeWorkspaceMenus([
    { path: '/monitoring', label: '健康概览', icon: 'Monitor' },
    { path: '/clinical-workbench', label: '临床工作台', icon: 'FirstAidKit' },
    { path: '/doctor-workspace', label: '医生工作台', icon: 'FirstAidKit' },
    MODULE_MENUS['/care-journey'],
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
        ,{ path: '/system/branding', label: '平台品牌', icon: 'Brush' }
      ]
    }
  ], { menuPaths, roleCodes });
}
