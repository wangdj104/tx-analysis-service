import request from '@/utils/request';
import { captureAuthSession } from '@/utils/authSession';
// 新原值入口独立于旧报告接口，也不借用可信读取的授权。
const get = (url, params, options = {}) => request({
    url, method: 'get', params, executionReport: true,
    expectedAuth: options.expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') },
    signal: options.signal,
    // 概览已有持久错误与重试入口，暂时读取失败不再重复弹出遮挡提示。
    ...(url === '/monitoring/snapshot-bounded' ? { monitoringInlineErrors: true } : {})
});
export const selfPage = (params, options) => get('/bp-self-monitor/page', params, options);
export const journeyPage = (params, options) => get('/care-journey/measurements/page', params, options);
export const selfDetail = (params, options) => get('/bp-self-monitor/detail', { ...params, includeImportFacts: true }, options);
export const monitoringSnapshotBounded = (params, options) => get('/monitoring/snapshot-bounded', params, options);
