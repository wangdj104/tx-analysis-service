import { reactive, unref, watch, getCurrentScope, onScopeDispose } from 'vue';
import { metricsForSource } from '@/utils/measurementReadModelPresentation';
import * as readApi from '@/api/originalMeasurements';
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
export function useOriginalMeasurementPage(patientRef, queryRef = {}, activeRef = false, deps = {}) {
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
        if (!sources.includes(input.sourceType)) throw new TypeError('INVALID_REQUEST');
        const self = input.sourceType === 'BP_SELF_MONITOR', filter = input.filter || null;
        if (filter && !(self ? ['BP','BG','BP_BG','BOTH'] : metricsForSource('HEALTH_MEASUREMENT')).includes(filter)) throw new TypeError('INVALID_REQUEST');
        const hasRange = input.from != null || input.to != null;
        if (hasRange && !validMeasurementRange(input.from, input.to)) throw new TypeError('INVALID_REQUEST');
        const limit = input.limit ?? 30;
        if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new TypeError('INVALID_REQUEST');
        return { patientId: patient(), limit, ...(filter ? { [self ? 'measureType' : 'metricType']: filter === 'BOTH' ? 'BP_BG' : filter } : {}), ...(hasRange ? { from: input.from, to: input.to } : {}) };
    }
    function validResponse(data, params, source) {
        if (!data || !Array.isArray(data.items) || data.items.length > params.limit) return false;
        const c = data.coverage;
        if (c?.scope !== 'CURRENT_PAGE' || c.consistency !== 'NON_SNAPSHOT' || c.sourceType !== source || c.from !== (params.from ?? null) || c.to !== (params.to ?? null) || c.limit !== params.limit || c.unknownDateExcluded !== (params.from != null)) return false;
        if (c.sort !== (source === 'BP_SELF_MONITOR' ? 'record_date DESC,record_time DESC,id DESC' : 'measured_at DESC,id DESC')) return false;
        if (data.nextCursor != null && (typeof data.nextCursor !== 'string' || !data.nextCursor.length || data.nextCursor.length > 2048)) return false;
        return data.items.every(item => positiveId(item.id) && Number(source === 'BP_SELF_MONITOR' ? item.patientId : item.patient_id) === params.patientId);
    }
    function failed(error) {
        const status = Number(error?.response?.status || error?.code) || 0;
        const code = error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'READ_UNAVAILABLE';
        state.error = code;
        state.phase = [401, 403].includes(status) ? 'denied' : status === 400 ? 'invalid' : status === 404 ? 'missing' : 'unavailable';
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
        const version = ++epoch, queryKey = JSON.stringify(query()), permissions = permissionKey(), source = query().sourceType;
        // 翻页先清空旧原值，不拼完整历史，也不把旧结果当成本次成功。
        Object.assign(state, { phase: 'loading', items: [], data: null, error: null, nextCursor: null, ...position, generation: state.generation + 1 });
        const owns = () => !disposed && active() && version === epoch && patient() === params.patientId && queryKey === JSON.stringify(query()) && currentAuth(auth) && permissions === permissionKey();
        const options = { expectedAuth: auth, signal: request.signal };
        try {
            const input = { ...params, ...(position.cursor != null ? { cursor: position.cursor } : {}) };
            const response = await api[source === 'BP_SELF_MONITOR' ? 'selfPage' : 'journeyPage'](input, options);
            if (!owns()) return { status: 'stale' };
            const data = unwrap(response);
            if (!validResponse(data, params, source)) throw new Error('INVALID_READ_RESPONSE');
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
    const stop = watch(() => [patient(), JSON.stringify(query()), active(), unref(deps.permissions), unref(deps.contextKey)], () => { clear(); if (active()) refresh(); }, { immediate: true, flush: 'sync' });
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
