import request from '@/utils/request';
import { captureAuthSession } from '@/utils/authSession';
const root = '/alert-workflows/read-model';
const get = (path, params, options = {}) => request({
    url: `${root}/${path}`, method: 'get', params,
    expectedAuth: options.expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') },
    signal: options.signal, alertWorkflow: true, measurementReadModel: true
});
export const trend = (params, options) => get('measurement-trend', params, options);
export const items = (params, options) => get('measurement-items', params, options);
export const events = (alertId, params, options) => {
    if (!Number.isSafeInteger(Number(alertId)) || Number(alertId) < 1) throw new TypeError('INVALID_REQUEST');
    return get(`measurement-items/${alertId}/events`, params, options);
};
