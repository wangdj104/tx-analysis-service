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
  if (path === '/care-journey' && new URLSearchParams(fullPath.split('?')[1] || '').get('tab') === 'operations' && !roleCodes.includes('doctor')) return false;
  if (menuPaths.includes(fullPath)) return true;
  // A conversation ID identifies a record, not another permission or workspace tab.
  // Keep all other query permissions exact; the API still verifies participation.
  if (path === '/care-journey' && !fullPath.includes('#')) {
    const query = new URLSearchParams(fullPath.split('?')[1] || '');
    const keys = [...query.keys()];
    const knownTabs = ['measurements', 'appointments', 'consultation', 'recovery', 'emergency', 'specialty', 'mental', 'privacy', 'operations'];
    if (menuPaths.includes(path) && keys.length === 1 && knownTabs.includes(query.get('tab'))) return true;
    if (query.get('tab') === 'consultation' && keys.length === 2 &&
        new Set(keys).size === 2 && keys.includes('consultationId') &&
        /^[1-9]\d*$/.test(query.get('consultationId') || '') &&
        (menuPaths.includes(path) || menuPaths.includes('/care-journey?tab=consultation'))) return true;
  }
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
