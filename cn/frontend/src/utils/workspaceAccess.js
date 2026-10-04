// These routes permit an authenticated attempt; the source/report API owns authorization.
function positiveId(value) { return /^[1-9]\d*$/.test(value || '') && Number.isSafeInteger(Number(value)) ? Number(value) : null }
function exactQuery(fullPath, path, required, optional = []) {
  if (typeof fullPath !== 'string' || fullPath.includes('#') || fullPath.split('?')[0] !== path || fullPath.split('?').length !== 2) return null
  const query = new URLSearchParams(fullPath.split('?')[1]), keys = [...query.keys()]
  return new Set(keys).size === keys.length && required.every(key => keys.includes(key)) && keys.every(key => [...required, ...optional].includes(key)) ? query : null
}
export function parseReportRoute(fullPath) {
  const query = exactQuery(fullPath, '/care-plans/reports', ['patientId'], ['planId'])
  if (!query) return null
  const patientId = positiveId(query.get('patientId')), planId = query.has('planId') ? positiveId(query.get('planId')) : null
  return patientId && (!query.has('planId') || planId) ? { patientId, planId } : null
}
export function parseEvidenceRoute(fullPath) {
  for (const [path, tab, key, sourceType] of [['/care-journey', 'measurements', 'measurementId', 'MEASUREMENT'], ['/medical-record', 'list', 'recordId', 'MEDICAL_RECORD']]) {
    const query = exactQuery(fullPath, path, ['tab', 'patientId', key])
    if (!query || query.get('tab') !== tab) continue
    const patientId = positiveId(query.get('patientId')), sourceId = positiveId(query.get(key))
    if (patientId && sourceId) return { patientId, sourceId, sourceType }
  }
  return null
}
export function isNarrowCareRoute(fullPath) { return !!(parseReportRoute(fullPath) || parseEvidenceRoute(fullPath)) }
export function isLocatorAttempt(fullPath) {
  const path = fullPath.split(/[?#]/, 1)[0], query = new URLSearchParams(fullPath.split('?')[1] || '')
  return path === '/care-plans/reports' || (['/care-journey', '/medical-record'].includes(path) && ['measurementId', 'recordId'].some(key => query.has(key)))
}
export const DEFAULT_WORKSPACE_TABS = {
  '/dialysis': 'data',
  '/medical-record': 'list',
  '/medication': 'drugs',
  '/health-analysis': 'complication',
  '/family-health': 'today'
};

/** navigation and routeguardtotaluse: emptyPermissiontableshowUnknown or Noneauthorize, not tableshowmanagementmember.  */
export function canAccessWorkspace(fullPath, menuPaths = [], roleCodes = []) {
  if (isLocatorAttempt(fullPath)) return isNarrowCareRoute(fullPath);
  const path = fullPath.split(/[?#]/, 1)[0];
  if (path === '/doctor-workspace' && !roleCodes.includes('doctor') && !roleCodes.includes('admin')) return false;
  if (path === '/nurse-workspace') return roleCodes.includes('nurse') && fullPath === path;
  if (/^\/care-plans\/[1-9]\d*$/.test(path)) return fullPath === path;
  // The authenticated care entry hosts independently authorized plan tasks.
  // Patient/module access stays with the API; this does not grant another tab.
  if (fullPath === '/care') return true;
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
