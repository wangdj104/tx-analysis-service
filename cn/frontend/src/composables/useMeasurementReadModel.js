import { reactive, unref, watch, getCurrentScope, onScopeDispose } from 'vue';
import { isLegalMeasurement } from '@/utils/measurementReadModelPresentation';
import * as readApi from '@/api/measurementReadModel';
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache } from '@/utils/authSession';

const sources = ['BP_SELF_MONITOR', 'HEALTH_MEASUREMENT'];
const positiveId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0;
const unwrap = response => {
    if (response?.code && response.code !== 200) throw Object.assign(new Error('READ_FAILED'), { code: response.code, data: response.data });
    return response?.data ?? response;
};
function validDay(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '') || Number(value.slice(0, 4)) < 1000) return false;
    const date = new Date(`${value}T00:00:00Z`);
    return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
export function validMeasurementRange(from, to) {
    if (!validDay(from) || !validDay(to) || to === '9999-12-31') return false;
    const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000;
    return days >= 0 && days < 90;
}

/** 只保存当前页与有限游标；日期校验的UTC计算不用于解释原测量时区。 */
export function useMeasurementReadModel(kind, patientRef, queryRef = {}, activeRef = true, deps = {}) {
    if (!['trend', 'items', 'events'].includes(kind)) throw new TypeError('INVALID_READ_MODEL');
    const api = deps.api || readApi, host = deps.events || globalThis.window;
    const capture = deps.capture || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }));
    const currentAuth = deps.isCurrent || (auth => isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId'));
    const permissionKey = deps.permissionKey || (() => JSON.stringify(readPermissionCache()));
    const patient = () => Number(unref(patientRef)) || null, query = () => unref(queryRef) || {}, active = () => !!unref(activeRef);
    const initial = () => ({ phase: 'idle', items: [], data: null, error: null, nextCursor: null, cursor: null, previous: [], page: 1 });
    const state = reactive({ ...initial(), generation: 0 });
    let epoch = 0, controller = null, disposed = false;
    function clear() {
        epoch++;
        controller?.abort();
        controller = null;
        Object.assign(state, initial(), { generation: state.generation + 1 });
    }
    function parameters() {
        const input = query();
        if (!positiveId(patient())) return null;
        if (kind === 'trend') {
            if (!isLegalMeasurement(input.sourceType, input.metric) || !validMeasurementRange(input.from, input.to)) throw new TypeError('INVALID_REQUEST');
            return { patientId: patient(), sourceType: input.sourceType, metric: input.metric, from: input.from, to: input.to, limit: 50 };
        }
        if (kind === 'events' && !positiveId(input.alertId)) return null;
        return { patientId: patient(), limit: 30 };
    }
    function validResponse(data, params, alertId) {
        if (!data || Number(data.patientId) !== params.patientId || !Array.isArray(data.items) || data.items.length > 100 || data.items.length > params.limit) return false;
        if (data.coverage?.scope !== 'CURRENT_PAGE' || data.coverage?.consistency !== 'NON_SNAPSHOT' || (data.nextCursor != null && typeof data.nextCursor !== 'string')) return false;
        if (kind === 'trend') return ['sourceType', 'metric', 'from', 'to'].every(key => data[key] === params[key]) && data.items.every(item => item.sourceType === params.sourceType && positiveId(item.sourceId) && Array.isArray(item.qualityFlags));
        if (data.summaryOnly !== true) return false;
        if (kind === 'events') return Number(data.alertId) === alertId && sources.includes(data.sourceType) && positiveId(data.sourceId) && data.items.every(item => positiveId(item.id));
        return data.items.every(item => positiveId(item.alertId) && positiveId(item.sourceId) && isLegalMeasurement(item.sourceType, item.metric));
    }
    function failed(error) {
        const status = Number(error?.response?.status || error?.code) || 0;
        const code = error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'READ_UNAVAILABLE';
        state.error = code;
        state.phase = [401, 403].includes(status) ? 'denied' : code === 'FEATURE_DISABLED' ? 'disabled' : status === 400 ? 'invalid' : status === 404 ? 'missing' : 'unavailable';
        deps.onFailure?.(state.phase);
        return { status: state.phase };
    }
    function deny() { clear(); return failed({ code: 403, data: { errorCode: 'ACCESS_DENIED' } }); }
    async function read(position) {
        if (disposed || !active()) return { status: 'inactive' };
        let params;
        try { params = parameters(); } catch (error) { clear(); return failed({ code: 400, data: { errorCode: 'INVALID_REQUEST' } }); }
        if (!params) { clear(); return { status: 'idle' }; }
        const auth = capture();
        if (!auth?.token) { clear(); return failed({ code: 401, data: { errorCode: 'AUTHENTICATION_REQUIRED' } }); }
        controller?.abort();
        const request = new AbortController();
        controller = request;
        const version = ++epoch, queryKey = JSON.stringify(query()), permissions = permissionKey(), alertId = Number(query().alertId);
        // 开始任何新读页先清空图表、表格与旧来源所有权，不把上次结果当本次成功。
        Object.assign(state, { phase: 'loading', items: [], data: null, error: null, nextCursor: null, ...position, generation: state.generation + 1 });
        const owns = () => !disposed && active() && version === epoch && patient() === params.patientId && queryKey === JSON.stringify(query()) && currentAuth(auth) && permissions === permissionKey();
        const options = { expectedAuth: auth, signal: request.signal };
        try {
            const input = { ...params, ...(position.cursor != null ? { cursor: position.cursor } : {}) };
            const response = kind === 'events' ? await api.events(alertId, input, options) : await api[kind](input, options);
            if (!owns()) return { status: 'stale' };
            const data = unwrap(response);
            if (!validResponse(data, params, alertId)) throw new Error('INVALID_READ_RESPONSE');
            state.data = data;
            state.items = data.items;
            state.nextCursor = data.nextCursor ?? null;
            state.phase = 'ready';
            return { status: 'succeeded' };
        } catch (error) {
            return owns() ? failed(error) : { status: 'stale' };
        } finally {
            if (controller === request) controller = null;
            // 无事件的权限/会话变化也不能留下已失去所有权的加载状态或内容。
            if (!disposed && version === epoch && !owns()) clear();
        }
    }
    function refresh({ keepPosition = false } = {}) {
        return read(keepPosition ? { cursor: state.cursor, previous: [...state.previous], page: state.page } : { cursor: null, previous: [], page: 1 });
    }
    function next() {
        if (state.phase !== 'ready' || state.nextCursor == null) return Promise.resolve({ status: 'not-available' });
        return read({ cursor: state.nextCursor, previous: [...state.previous, state.cursor].slice(-10), page: state.page + 1 });
    }
    function previous() {
        if (state.phase === 'loading' || !state.previous.length) return Promise.resolve({ status: 'not-available' });
        const history = [...state.previous], cursor = history.pop();
        return read({ cursor, previous: history, page: Math.max(1, state.page - 1) });
    }
    const stop = watch(() => [patient(), JSON.stringify(query()), active(), unref(deps.permissions)], () => { clear(); if (active()) refresh(); }, { immediate: true, flush: 'sync' });
    function authChanged() {
        clear();
        // 资料修订先同步清除；路由若被重建，旧实例的微任务不会再发请求。
        queueMicrotask(() => { if (!disposed && active() && capture()?.token) refresh(); });
    }
    function storageChanged(event) {
        if (event.key == null || ['token', 'userId', 'username', 'realName', 'permissionSession', 'userMenus', 'userRoleCodes'].includes(event.key)) authChanged();
    }
    host?.addEventListener('auth-session-cleared', authChanged);
    host?.addEventListener('auth-session-changed', authChanged);
    host?.addEventListener('storage', storageChanged);
    function dispose() {
        if (disposed) return;
        disposed = true;
        clear();
        stop();
        host?.removeEventListener('auth-session-cleared', authChanged);
        host?.removeEventListener('auth-session-changed', authChanged);
        host?.removeEventListener('storage', storageChanged);
    }
    if (getCurrentScope()) onScopeDispose(dispose);
    return { state, refresh, next, previous, clear, deny, dispose };
}
