import { parseAssistanceRoute } from './workspaceAccess.js';

let navigationEpoch = 0;

export function assistanceLoginRoute(fullPath) {
  const target = parseAssistanceRoute(fullPath);
  return target ? `/login?assistancePatientId=${target.patientId}&assistanceRequestId=${target.assistanceId}` : '/login';
}

export function assistanceAfterLogin(fullPath) {
  if (typeof fullPath !== 'string' || fullPath.includes('#')) return null;
  const parts = fullPath.split('?');
  if (parts.length !== 2 || parts[0] !== '/login') return null;
  const query = new URLSearchParams(parts[1]), keys = [...query.keys()];
  if (keys.length !== 2 || new Set(keys).size !== 2 || !keys.includes('assistancePatientId') || !keys.includes('assistanceRequestId')) return null;
  const target = parseAssistanceRoute(`/care?patientId=${encodeURIComponent(query.get('assistancePatientId'))}&assistanceId=${encodeURIComponent(query.get('assistanceRequestId'))}`);
  return target ? `/care?patientId=${target.patientId}&assistanceId=${target.assistanceId}` : null;
}

// 只记页面代次和两个已校验的定位 ID，不保存正文、草稿或命令。
export function noteAssistanceNavigation() { navigationEpoch++; }

export function captureAssistanceLoginRedirect(fullPath, base = '/') {
  const prefix = base === '/cn/' ? '/cn' : '';
  const route = typeof fullPath === 'string' && (!prefix || fullPath.startsWith(`${prefix}/`)) ? fullPath.slice(prefix.length) : '';
  const target = parseAssistanceRoute(route), dedicatedLogin = !!assistanceAfterLogin(route);
  return { epoch: navigationEpoch, fullPath, base, protected: !!target || dedicatedLogin, dedicatedLogin,
    loginHref: target ? prefix + assistanceLoginRoute(route) : null };
}

export function ownsAssistanceLoginRedirect(context, fullPath) {
  const current = captureAssistanceLoginRedirect(fullPath, context?.base);
  if (!context?.protected && !current.protected) return true;
  return !!context && context.epoch === navigationEpoch && context.fullPath === fullPath;
}
