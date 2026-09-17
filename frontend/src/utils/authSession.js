/** Sign Instate and currentwillmessagehavehas  this slowstore.  */
export const AUTH_STORAGE_KEYS = ['token', 'userId', 'username', 'realName'];
const SESSION_CACHE_KEYS = ['userMenus', 'userMenuNames', 'userRoleCodes', 'permissionSession', 'currentPatientId'];
let sessionRevision = 0;

export function clearPermissionCache() {
  ['userMenus', 'userMenuNames', 'userRoleCodes', 'permissionSession'].forEach(key => localStorage.removeItem(key));
}

export function saveAuthSession(data = {}) {
  if (data.token && data.token !== localStorage.getItem('token')) clearAuthSession();
  if (data.token) localStorage.setItem('token', data.token);
  if (data.userId != null) localStorage.setItem('userId', String(data.userId));
  if (data.username) localStorage.setItem('username', data.username);
  if (data.realName != null) localStorage.setItem('realName', data.realName);
}

export function clearAuthSession() {
  sessionRevision++;
  [...AUTH_STORAGE_KEYS, ...SESSION_CACHE_KEYS].forEach(key => localStorage.removeItem(key));
  if (typeof window !== 'undefined') window.dispatchEvent(new Event('auth-session-cleared'));
}

/** token + this willmessagereplacetimes: exitoutputafter sign in againsameoneAccountalsocannotacceptlegacyresponseshould.  */
export function captureAuthSession() {
  return { token: localStorage.getItem('token'), revision: sessionRevision };
}

export function isAuthSessionCurrent(session) {
  return !!session?.token && session.token === localStorage.getItem('token') && session.revision === sessionRevision;
}

export function savePermissionCache({ menuPaths = [], menuNames = [], roleCodes = [] }) {
  localStorage.setItem('userMenus', JSON.stringify(menuPaths));
  localStorage.setItem('userMenuNames', JSON.stringify(menuNames));
  localStorage.setItem('userRoleCodes', JSON.stringify(roleCodes));
  localStorage.setItem('permissionSession', localStorage.getItem('token') || '');
}

export function readPermissionCache() {
  const token = localStorage.getItem('token');
  if (!token || localStorage.getItem('permissionSession') !== token) return null;
  try {
    const menuPaths = JSON.parse(localStorage.getItem('userMenus'));
    const menuNames = JSON.parse(localStorage.getItem('userMenuNames'));
    const roleCodes = JSON.parse(localStorage.getItem('userRoleCodes'));
    if (![menuPaths, menuNames, roleCodes].every(items => Array.isArray(items) && items.every(item => typeof item === 'string'))) return null;
    return { menuPaths, menuNames, roleCodes };
  } catch { return null; }
}

export function getAuthSessionKey() {
  const userId = localStorage.getItem('userId');
  const username = localStorage.getItem('username');
  const token = localStorage.getItem('token');
  if (!token) return 'guest';
  return userId || username || token.slice(-8);
}
