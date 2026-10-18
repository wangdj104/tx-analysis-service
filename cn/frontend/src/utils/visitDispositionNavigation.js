const positive = value => typeof value === 'string' && /^[1-9][0-9]*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : null;
/** 路由只携带定位信息，不授予患者或行动权限；缺一项就不猜当前患者。 */
export function visitLocator(query) {
  if (!Object.hasOwn(query, 'visitId')) return { attempted: false, value: null };
  const patientId = positive(query.patientId), sourceId = positive(query.visitId);
  return { attempted: true, value: patientId && sourceId ? { patientId, sourceId } : null };
}
export function visitActionLocator(query) {
  const fields = ['patientId', 'revisionId', 'actionId', 'visitId'];
  if (!fields.some(key => Object.hasOwn(query, key))) return { attempted: false, value: null };
  const values = fields.map(key => positive(query[key]));
  return { attempted: true, value: values.every(Boolean) ? Object.fromEntries(fields.map((key, index) => [key, values[index]])) : null };
}
export function matchesVisitAction(plan, locator) {
  return !!plan && !!locator && plan.patientId === locator.patientId && plan.revisionId === locator.revisionId && plan.currentRevisionId === locator.revisionId && plan.revisionStatus === 'PUBLISHED' && plan.actions?.some(action => action.id === locator.actionId);
}

/** 主路由的窄入口白名单：只允许完整定位，不顺带打开其他工作区或患者列表。 */
export function parseVisitNavigationRoute(fullPath) {
  if (typeof fullPath !== 'string' || fullPath.includes('#') || fullPath.split('?').length !== 2) return null;
  const [path, raw] = fullPath.split('?'), params = new URLSearchParams(raw), keys = [...params.keys()];
  if (new Set(keys).size !== keys.length) return null;
  const query = Object.fromEntries(params);
  if (path === '/care-journey' && keys.length === 3 && keys.every(key => ['tab', 'patientId', 'visitId'].includes(key)) && query.tab === 'appointments') return visitLocator(query).value;
  if (/^\/care-plans\/[1-9][0-9]*$/.test(path) && positive(path.split('/').pop()) && keys.length === 4 && keys.every(key => ['patientId', 'revisionId', 'actionId', 'visitId'].includes(key))) return visitActionLocator(query).value;
  return null;
}
export function isVisitNavigationAttempt(fullPath) {
  if (typeof fullPath !== 'string') return false;
  const path=fullPath.split(/[?#]/,1)[0], query=new URLSearchParams(fullPath.split('?')[1] || '');
  return (path === '/care-journey' || /^\/care-plans\/[1-9][0-9]*$/.test(path)) && ['visitId','actionId','revisionId',...(path.startsWith('/care-plans/')?['patientId']:[])].some(key=>query.has(key));
}
