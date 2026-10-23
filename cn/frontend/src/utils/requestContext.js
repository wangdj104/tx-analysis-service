import { captureAuthSession } from './authSession';

let permissionRevision = 0;
let patientRevision = 0;
const globalRoutes = /^(?:\/auth(?:\/|$)|\/platform-branding(?:\/|$)|\/privacy\/access-context$|\/patient\/(?:names|specialty-menu-scope)$)/;

/** Keep the original context on a retry; a retry must never adopt another account or patient. */
export function captureRequestContext(config = {}) {
  return {
    auth: config.expectedAuth || captureAuthSession(),
    actorId: localStorage.getItem('userId'),
    patientId: localStorage.getItem('currentPatientId'),
    patientScoped: !globalRoutes.test(config.url || '') && !/\/capabilities$/.test(config.url || ''),
    permissionRevision,
    patientRevision
  };
}

export function isRequestContextCurrent(context) {
  if (!context) return true;
  const current = captureAuthSession();
  return context.auth.token === current.token && context.auth.revision === current.revision &&
    context.actorId === localStorage.getItem('userId') && context.permissionRevision === permissionRevision &&
    (!context.patientScoped || (context.patientRevision === patientRevision && context.patientId === localStorage.getItem('currentPatientId')));
}

export function invalidateRequestPermissions() {
  permissionRevision++;
  for (let index = localStorage.length - 1; index >= 0; index--) {
    const key = localStorage.key(index);
    if (key?.startsWith('offlineEmergencyCard:') || key?.startsWith('care-profile:')) localStorage.removeItem(key);
  }
}

export function isProtectedAccessFailure(config) {
  // Capability probes legitimately return denied without exposing a record.
  return !!config?.authSession?.token && !globalRoutes.test(config.url || '') && !/\/capabilities$/.test(config.url || '');
}

if (typeof window !== 'undefined') {
  window.addEventListener('permissions-changed', invalidateRequestPermissions);
  window.addEventListener('current-patient-change', () => { patientRevision++; });
  window.addEventListener('storage', event => {
    if (event.key === null || event.key === 'currentPatientId') patientRevision++;
  });
}
