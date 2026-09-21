export const DEFAULT_WORKSPACE_TABS = {
  '/dialysis': 'data',
  '/medical-record': 'list',
  '/medication': 'drugs',
  '/health-analysis': 'complication',
  '/family-health': 'today'
};

/** navigation and routeguardtotaluse: emptyPermissiontableshowUnknown or Noneauthorize, not tableshowmanagementmember.  */
export function canAccessWorkspace(fullPath, menuPaths = [], roleCodes = []) {
  const path = fullPath.split(/[?#]/, 1)[0];
  if (path === '/monitoring' || roleCodes.includes('admin')) return true;
  if (menuPaths.includes(fullPath)) return true;
  if (!fullPath.includes('?')) {
    return menuPaths.includes(path) || menuPaths.some(item => item.startsWith(`${path}?`));
  }
  // todown pagetagoriginalthis can throughmodulewithinby buttonenter, URL samestepafter keepalreadyhas feature.
  if (menuPaths.includes(path)) {
    if (fullPath === `${path}?tab=${DEFAULT_WORKSPACE_TABS[path]}`) return true;
    if (['/medication?tab=upload', '/medication?tab=category', '/medical-record?tab=upload',
      '/family-health?tab=timeline', '/family-health?tab=schedule', '/family-health?tab=target',
      '/family-health?tab=summary'].includes(fullPath)) return true;
    if (fullPath === '/health-analysis?tab=automation') return true;
  }
  return false;
}

/** childpagePermissioncan onlyenteralready authorizechildpage, cannotuse the baremodulePathreturnfallto anotheroneDefaultfeature.  */
export function resolveWorkspaceEntry(fullPath, menuPaths = [], roleCodes = []) {
  if (!canAccessWorkspace(fullPath, menuPaths, roleCodes)) return '';
  const path = fullPath.split(/[?#]/, 1)[0];
  if (fullPath.includes('?') || !DEFAULT_WORKSPACE_TABS[path] || roleCodes.includes('admin') || menuPaths.includes(path)) return fullPath;
  return menuPaths.find(item => item.startsWith(`${path}?`)) || '';
}
