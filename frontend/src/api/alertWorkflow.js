import request from '@/utils/request';
import { captureAuthSession } from '@/utils/authSession';
const root = '/alert-workflows';
const call = (config, options = {}) => request({ ...config, expectedAuth: options.expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, signal: options.signal, alertWorkflow: true });
export const capabilities = (patientId, options) => call({ url: `${root}/capabilities`, params: patientId ? { patientId } : {} }, options);
export const list = (params, options) => call({ url: root, params }, options);
export const detail = (id, options) => call({ url: `${root}/${id}` }, options);
export const events = (id, params, options) => call({ url: `${root}/${id}/events`, params }, options);
export const assignees = (patientId, mode, options) => call({ url: `${root}/assignees`, params: { patientId, mode } }, options);
export const adopt = (data, options) => call({ url: `${root}/adopt`, method: 'post', data }, options);
export function command(id, action, data, options) {
    if (!['ASSIGN', 'ACCEPT', 'REJECT_ASSIGNMENT', 'ESCALATE', 'FOLLOW_UP', 'SUBMIT', 'FEEDBACK', 'RETURN', 'REVIEW_SOURCE_CHANGE', 'RESOLVE_REFERENCE_CHANGE', 'CLOSE', 'CANCEL', 'REOPEN'].includes(action))
        throw new TypeError('INVALID_ACTION');
    return call({ url: `${root}/${id}/${action.toLowerCase().replaceAll('_', '-')}`, method: 'post', data }, options);
}
const sourcePath = (type, id) => {
    if (!['HEALTH_MEASUREMENT', 'BP_SELF_MONITOR'].includes(type) || !Number.isSafeInteger(Number(id)) || Number(id) < 1)
        throw new TypeError('INVALID_SOURCE');
    return `${root}/sources/${type}/${id}`;
};
export const source = (type, id, patientId, options) => call({ url: sourcePath(type, id), params: { patientId } }, options);
export const sourceHistory = (type, id, params, options) => call({ url: `${sourcePath(type, id)}/revisions`, params }, options);
export function sourceSave(type, data, options) {
    if (!['HEALTH_MEASUREMENT', 'BP_SELF_MONITOR'].includes(type))
        throw new TypeError('INVALID_SOURCE');
    return call({ url: type === 'HEALTH_MEASUREMENT' ? '/care-journey/measurements' : '/bp-self-monitor/save', method: 'post', data }, options);
}
export function sourceCorrect(type, id, data, options) {
    sourcePath(type, id);
    return call({ url: type === 'HEALTH_MEASUREMENT' ? `/care-journey/measurements/${id}` : '/bp-self-monitor/update', method: 'put', data: type === 'BP_SELF_MONITOR' ? { ...data, id } : data }, options);
}
export const sourceInvalidate = (type, id, data, options) => call({ url: `${sourcePath(type, id)}/invalidate`, method: 'post', data }, options);
export const deliveryStatus = (id, params, options) => call({ url: `${root}/${id}/delivery-status`, params }, options);
export const deliveryRetry = (id, data, options) => call({ url: `${root}/notifications/${id}/retry`, method: 'post', data }, options);
export const nurseCandidates = options => call({ url: `${root}/nurse-assignments/candidates` }, options);
export const nurseAssignments = (patientId, options) => call({ url: `${root}/nurse-assignments`, params: { patientId } }, options);
export const assignNurse = (data, options) => call({ url: `${root}/nurse-assignments`, method: 'post', data }, options);
export const revokeNurse = (id, options) => call({ url: `${root}/nurse-assignments/${id}/revoke`, method: 'post', data: {} }, options);
