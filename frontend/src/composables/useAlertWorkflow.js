import * as Vue from 'vue';
import * as workflowApi from '@/api/alertWorkflow';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
const copy = value => value == null ? value : JSON.parse(JSON.stringify(value));
const unwrap = response => {
    if (response?.code && response.code !== 200)
        throw Object.assign(new Error('REQUEST_REJECTED'), { code: response.code, data: response.data });
    return response?.data ?? response;
};
const status = error => Number(error?.response?.status || error?.code) || 0;
const errorCode = error => error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'REQUEST_FAILED';
const freeze = value => {
    if (value && typeof value === 'object') {
        Object.values(value).forEach(freeze);
        Object.freeze(value);
    }
    return value;
};
export function workflowKey() {
    if (globalThis.crypto?.randomUUID)
        return globalThis.crypto.randomUUID();
    const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const h = [...bytes].map(n => n.toString(16).padStart(2, '0')).join('');
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
/** Protected drafts and replay envelopes exist in memory only, within one patient/auth epoch. */
export function useAlertWorkflow(patientRef, deps = {}) {
    const { reactive, unref, watch, getCurrentScope, onScopeDispose } = Vue;
    const api = deps.api || workflowApi, host = deps.events || globalThis.window;
    const captureAuth = deps.capture || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }));
    const authCurrent = deps.isCurrent || (c => isAuthSessionCurrent(c) && c.actorId === localStorage.getItem('userId'));
    const key = deps.newKey || workflowKey, patient = () => Number(unref(patientRef)) || null;
    const initial = () => ({ patientId: patient(), navigationBlocked: false, capabilityPhase: 'unknown', capabilities: null, items: [], queue: 'TODAY', nextCursor: null, loading: false, detail: null, opening: false, events: [], eventCursor: null, deliveries: [], deliveryCursor: null, assignees: [], dialog: null, commandPhase: 'idle', commandKey: null, error: null, result: null, source: null, sourceLocator: null, sourceReadPhase: 'idle', receipt: null, sourcePhase: 'idle', sourceOperation: null, sourceKey: null, sourceHistory: [], sourceCursor: null, deliveryPhase: 'idle', deliveryAttemptId: null, deliveryDecisionReady: false });
    const state = reactive(initial());
    let epoch = 0, disposed = false, session = captureAuth(), attempt = null, sourceAttempt = null, deliveryAttempt = null, dialogEpoch = 0;
    const lanes = new Map(), controllers = new Map();
    const tick = lane => {
        controllers.get(lane)?.abort();
        controllers.delete(lane);
        lanes.set(lane, (lanes.get(lane) || 0) + 1);
    };
    function clear() {
        epoch++;
        dialogEpoch++;
        for (const c of controllers.values())
            c.abort();
        controllers.clear();
        lanes.clear();
        attempt = null;
        sourceAttempt = null;
        deliveryAttempt = null;
        Object.assign(state, initial());
    }
    function authChanged() {
        clear();
        session = captureAuth();
    }
    function sync() {
        if (disposed)
            return false;
        if (!authCurrent(session)) {
            authChanged();
        }
        return !!session.token;
    }
    function context(lane, { advance = true } = {}) {
        if (!sync() || !patient())
            return null;
        if (advance)
            tick(lane);
        const controller = new AbortController();
        controllers.set(lane, controller);
        return { epoch, lane, version: lanes.get(lane), dialogEpoch, auth: captureAuth(), patient: patient(), controller, options: { expectedAuth: captureAuth(), signal: controller.signal } };
    }
    function owns(c, dialog = false) {
        if (!c || disposed)
            return false;
        if (!authCurrent(c.auth)) {
            if (!authCurrent(session))
                authChanged();
            return false;
        }
        return c.epoch === epoch && c.patient === patient() && c.version === lanes.get(c.lane) && (!dialog || c.dialogEpoch === dialogEpoch);
    }
    function denied(error) {
        const n = status(error);
        if (n === 401 || n === 403) {
            clear();
            state.commandPhase = 'denied';
            state.sourcePhase = 'denied';
            state.capabilityPhase = 'denied';
            state.error = n === 401 ? 'SESSION_CHANGED' : 'ACCESS_DENIED';
            return true;
        }
        return false;
    }
    function fail(error) {
        if (denied(error))
            return { status: 'denied' };
        state.error = errorCode(error);
        return { status: 'unavailable' };
    }
    async function loadCapabilities() {
        const c = context('capability');
        if (!c)
            return { status: 'unauthenticated' };
        state.capabilityPhase = 'loading';
        state.capabilities = null;
        state.error = null;
        try {
            const data = unwrap(await api.capabilities(c.patient, c.options));
            if (!owns(c))
                return { status: 'stale' };
            state.capabilities = data;
            state.capabilityPhase = data?.enabled === true ? 'enabled' : data?.enabled === false ? 'disabled' : 'unavailable';
            return { status: state.capabilityPhase };
        }
        catch (e) {
            if (!owns(c))
                return { status: 'stale' };
            if (!denied(e)) {
                state.capabilityPhase = 'unavailable';
                state.error = errorCode(e);
            }
            return { status: state.capabilityPhase };
        }
    }
    async function load({ queue = state.queue, cursor = null } = {}) {
        if (cursor && (queue !== state.queue || cursor !== state.nextCursor))
            return { status: 'stale-cursor' };
        const c = context('list');
        if (!c)
            return { status: 'unauthenticated' };
        if (!cursor) {
            state.items = [];
            state.nextCursor = null;
        }
        state.queue = queue;
        state.loading = true;
        try {
            const data = unwrap(await api.list({ patientId: c.patient, queue, limit: 30, ...(cursor ? { cursor } : {}) }, c.options));
            if (!owns(c))
                return { status: 'stale' };
            state.items = cursor ? [...state.items, ...data.items] : data.items;
            state.nextCursor = data.nextCursor ?? null;
            return { status: 'succeeded' };
        }
        catch (e) {
            return owns(c) ? fail(e) : { status: 'stale' };
        }
        finally {
            if (owns(c))
                state.loading = false;
        }
    }
    async function open(id) {
        if ([state.commandPhase, state.sourcePhase, state.deliveryPhase].some(phase => ['submitting', 'uncertain'].includes(phase)))
            return { status: 'reconciliation-required' };
        dialogEpoch++;
        attempt = null;
        deliveryAttempt = null;
        for (const lane of ['detail', 'history', 'delivery', 'assignees', 'source-read', 'source-history'])
            tick(lane);
        Object.assign(state, { source: null, sourceLocator: null, sourceHistory: [], sourceCursor: null, sourceReadPhase: 'idle', receipt: null, detail: null, events: [], eventCursor: null, deliveries: [], deliveryCursor: null, dialog: null, commandPhase: 'idle', commandKey: null, deliveryPhase: 'idle', deliveryAttemptId: null, deliveryDecisionReady: false });
        return refreshDetail(id);
    }
    async function refreshDetail(id = state.detail?.alertId) {
        if (!id)
            return { status: 'missing-detail' };
        const c = context('detail');
        if (!c)
            return { status: 'unauthenticated' };
        state.opening = true;
        try {
            const data = unwrap(await api.detail(id, c.options));
            if (!owns(c))
                return { status: 'stale' };
            if (Number(data.patient?.id) !== c.patient) {
                clear();
                state.error = 'PATIENT_MISMATCH';
                return { status: 'denied' };
            }
            state.detail = data;
            return { status: 'succeeded', data };
        }
        catch (e) {
            return owns(c) ? fail(e) : { status: 'stale' };
        }
        finally {
            if (owns(c))
                state.opening = false;
        }
    }
    async function paged(kind, cursor = null) {
        const id = state.detail?.alertId;
        if (!id)
            return { status: 'missing-detail' };
        const history = kind === 'events', lane = history ? 'history' : 'delivery', items = history ? 'events' : 'deliveries', next = history ? 'eventCursor' : 'deliveryCursor';
        if (cursor && cursor !== state[next])
            return { status: 'stale-cursor' };
        const c = context(lane);
        if (!c)
            return { status: 'unauthenticated' };
        if (!cursor) {
            state[items] = [];
            state[next] = null;
            if (!history)
                state.deliveryDecisionReady = false;
        }
        try {
            const data = unwrap(await api[history ? 'events' : 'deliveryStatus'](id, { limit: 30, ...(cursor ? { cursor } : {}) }, c.options));
            if (!owns(c) || state.detail?.alertId !== id)
                return { status: 'stale' };
            state[items] = cursor ? [...state[items], ...data.items] : data.items;
            state[next] = data.nextCursor ?? null;
            if (!history && state.deliveryPhase === 'conflict')
                state.deliveryDecisionReady = true;
            return { status: 'succeeded' };
        }
        catch (e) {
            return owns(c) ? fail(e) : { status: 'stale' };
        }
    }
    function openDialog(action, draft = {}) {
        if (!state.detail?.allowedActions?.includes(action) || ['submitting', 'uncertain'].includes(state.commandPhase))
            return false;
        dialogEpoch++;
        tick('assignees');
        attempt = null;
        state.assignees = [];
        state.dialog = { action, draft: copy(draft) };
        state.commandPhase = 'idle';
        state.commandKey = null;
        state.error = null;
        return true;
    }
    function closeDialog() {
        if (['submitting', 'uncertain'].includes(state.commandPhase))
            return false;
        dialogEpoch++;
        tick('assignees');
        state.dialog = null;
        state.assignees = [];
        attempt = null;
        state.commandPhase = 'idle';
        state.commandKey = null;
        return true;
    }
    async function loadAssignees(mode = state.detail?.mode) {
        const action = state.dialog?.action;
        if (!['ASSIGN', 'ESCALATE'].includes(action) || !state.detail?.allowedActions?.includes(action) || state.capabilityPhase !== 'enabled' || mode !== (action === 'ESCALATE' ? 'CLINICAL' : state.detail.mode))
            return { status: 'not-required' };
        const c = context('assignees');
        if (!c)
            return { status: 'unauthenticated' };
        state.assignees = [];
        try {
            const data = unwrap(await api.assignees(c.patient, mode, c.options));
            if (!owns(c, true))
                return { status: 'stale' };
            state.assignees = data;
            if (!data.some(person => Number(person.userId) === Number(state.dialog.draft.assigneeId)))
                state.dialog.draft.assigneeId = null;
            return { status: 'succeeded' };
        }
        catch (e) {
            return owns(c, true) ? fail(e) : { status: 'stale' };
        }
    }
    function newDecision(kind = 'command') {
        if (kind === 'delivery') {
            if (state.deliveryPhase !== 'conflict' || !state.deliveryDecisionReady)
                return false;
            deliveryAttempt = null;
            state.deliveryPhase = 'idle';
            state.deliveryAttemptId = null;
            return true;
        }
        if (kind === 'source') {
            if (!['conflict', 'rejected'].includes(state.sourcePhase))
                return false;
            sourceAttempt = null;
            state.sourcePhase = 'idle';
            state.sourceKey = null;
            return true;
        }
        if (!['conflict', 'rejected'].includes(state.commandPhase))
            return false;
        attempt = null;
        state.commandPhase = 'idle';
        state.commandKey = null;
        return true;
    }
    async function submit({ retry = false, body } = {}) {
        if (state.commandPhase === 'submitting')
            return { status: 'busy' };
        if (state.commandPhase === 'uncertain' && !retry)
            return { status: 'retry-required' };
        if (['conflict', 'rejected'].includes(state.commandPhase))
            return { status: 'decision-required' };
        if (!state.dialog || !state.detail)
            return { status: 'missing-detail' };
        if (!retry && (state.capabilityPhase !== 'enabled' || state.opening))
            return { status: 'capability-required' };
        if (!retry && !state.detail.allowedActions?.includes(state.dialog.action))
            return { status: 'denied' };
        if (retry && !attempt)
            return { status: 'retry-unavailable' };
        const c = context('command');
        if (!c)
            return { status: 'unauthenticated' };
        if (!retry) {
            const version = state.detail.version;
            if (!Number.isSafeInteger(version) || version < 0)
                return { status: 'invalid-version' };
            attempt = freeze({ id: state.detail.alertId, action: state.dialog.action, envelope: { commandKey: key(), expectedVersion: version, body: copy(body ?? state.dialog.draft) } });
        }
        const current = attempt;
        state.commandPhase = 'submitting';
        state.commandKey = current.envelope.commandKey;
        state.error = null;
        try {
            const data = unwrap(await api.command(current.id, current.action, copy(current.envelope), c.options));
            if (!owns(c, true) || attempt !== current)
                return { status: 'stale' };
            state.commandPhase = 'succeeded';
            state.result = data;
            attempt = null;
            return { status: 'succeeded', data };
        }
        catch (e) {
            if (!owns(c, true) || attempt !== current)
                return { status: 'stale' };
            if (denied(e))
                return { status: 'denied' };
            state.error = errorCode(e);
            state.commandPhase = uncertain(e) ? 'uncertain' : status(e) === 409 ? 'conflict' : 'rejected';
            return { status: state.commandPhase };
        }
    }
    function uncertain(e) {
        return !e.notDispatched && (!status(e) || status(e) >= 500 || status(e) === 408 || errorCode(e) === 'FEATURE_DISABLED' || errorCode(e) === 'WORKFLOW_SCHEMA_UNAVAILABLE');
    }
    async function readSource(type, id) {
        tick('source-history');
        state.sourceHistory = [];
        state.sourceCursor = null;
        const c = context('source-read');
        if (!c)
            return { status: 'unauthenticated' };
        // A matching save/replay read retains its receipt; selecting another source never does.
        if (state.receipt && (state.receipt.sourceType !== type || Number(state.receipt.sourceId) !== Number(id))) {
            state.receipt = null;
            if (state.sourcePhase === 'succeeded') {
                state.sourcePhase = 'idle';
                state.sourceKey = null;
                state.sourceOperation = null;
            }
        }
        state.sourceLocator = { type, id };
        state.source = null;
        state.sourceReadPhase = 'loading';
        try {
            const data = unwrap(await api.source(type, id, c.patient, c.options));
            if (!owns(c))
                return { status: 'stale' };
            state.source = data;
            state.sourceReadPhase = 'succeeded';
            return { status: 'succeeded', data };
        }
        catch (e) {
            if (!owns(c))
                return { status: 'stale' };
            if (!denied(e))
                state.sourceReadPhase = 'unavailable';
            return { status: 'unavailable' };
        }
    }
    async function loadSourceHistory(type, id, afterRevision = null) {
        if (afterRevision && afterRevision !== state.sourceCursor)
            return { status: 'stale-cursor' };
        const c = context('source-history');
        if (!c)
            return { status: 'unauthenticated' };
        if (!afterRevision) {
            state.sourceHistory = [];
            state.sourceCursor = null;
        }
        try {
            const data = unwrap(await api.sourceHistory(type, id, { patientId: c.patient, limit: 30, ...(afterRevision ? { afterRevision } : {}) }, c.options));
            if (!owns(c))
                return { status: 'stale' };
            state.sourceHistory = afterRevision ? [...state.sourceHistory, ...data.items] : data.items;
            state.sourceCursor = data.nextAfterRevision ?? null;
            return { status: 'succeeded' };
        }
        catch (e) {
            return owns(c) ? fail(e) : { status: 'stale' };
        }
    }
    async function saveSource(type, input, { id = null, operation = 'save', retry = false } = {}) {
        if (retry && sourceAttempt?.operation === 'adopt')
            return adoptSource({}, { retry: true });
        if (state.sourcePhase === 'submitting')
            return { status: 'busy' };
        if (state.sourcePhase === 'uncertain' && !retry)
            return { status: 'retry-required' };
        if (['conflict', 'rejected'].includes(state.sourcePhase))
            return { status: 'decision-required' };
        if (retry && !sourceAttempt)
            return { status: 'retry-unavailable' };
        if (!retry && !['enabled', 'disabled'].includes(state.capabilityPhase))
            return { status: 'capability-required' };
        if (!retry && state.capabilityPhase === 'enabled' && !state.capabilities?.canAdopt)
            return { status: 'denied' };
        if (!retry && operation !== 'save' && state.capabilityPhase !== 'enabled')
            return { status: 'capability-required' };
        if (!retry && operation !== 'save' && (state.source?.sourceType !== type || Number(state.source?.id) !== Number(id)))
            return { status: 'source-required' };
        const c = context('source-command');
        if (!c)
            return { status: 'unauthenticated' };
        if (!retry) {
            const enabled = state.capabilityPhase === 'enabled', data = { ...copy(input), patientId: c.patient };
            if (enabled) {
                data.commandKey = key();
                data.expectedVersion = operation === 'save' ? 0 : state.source?.sourceVersion;
                if (!Number.isSafeInteger(data.expectedVersion) || data.expectedVersion < 0)
                    return { status: 'source-required' };
            }
            sourceAttempt = freeze({ type, id, operation, enabled, data });
            state.sourceOperation = operation;
            state.receipt = null;
            if (operation === 'save') {
                tick('source-read');
                tick('source-history');
                state.source = null;
                state.sourceLocator = null;
                state.sourceReadPhase = 'idle';
                state.sourceHistory = [];
                state.sourceCursor = null;
            }
        }
        const current = sourceAttempt;
        state.sourcePhase = 'submitting';
        state.sourceKey = current.data.commandKey ?? null;
        state.error = null;
        try {
            const method = current.operation === 'save' ? 'sourceSave' : current.operation === 'invalidate' ? 'sourceInvalidate' : 'sourceCorrect';
            const data = unwrap(await (current.operation === 'save' ? api[method](current.type, copy(current.data), c.options) : api[method](current.type, current.id, copy(current.data), c.options)));
            if (!owns(c) || sourceAttempt !== current)
                return { status: 'stale' };
            state.sourcePhase = 'succeeded';
            state.receipt = current.enabled ? data : null;
            sourceAttempt = null;
            if (current.enabled)
                await readSource(data.sourceType, data.sourceId);
            if (!owns(c))
                return { status: 'stale' };
            return { status: 'succeeded', data };
        }
        catch (e) {
            if (!owns(c) || sourceAttempt !== current)
                return { status: 'stale' };
            if (denied(e))
                return { status: 'denied' };
            state.error = errorCode(e);
            state.sourcePhase = uncertain(e) ? 'uncertain' : status(e) === 409 ? 'conflict' : 'rejected';
            return { status: state.sourcePhase };
        }
    }
    async function adoptSource(body, { retry = false } = {}) {
        if (state.sourcePhase === 'submitting')
            return { status: 'busy' };
        if (state.sourcePhase === 'uncertain' && !retry)
            return { status: 'retry-required' };
        if (['conflict', 'rejected'].includes(state.sourcePhase))
            return { status: 'decision-required' };
        if (!retry && (state.capabilityPhase !== 'enabled' || !state.capabilities?.canAdopt))
            return { status: 'denied' };
        if (retry && sourceAttempt?.operation !== 'adopt')
            return { status: 'retry-unavailable' };
        const c = context('source-command');
        if (!c)
            return { status: 'unauthenticated' };
        if (!retry)
            sourceAttempt = freeze({ operation: 'adopt', data: { commandKey: key(), expectedVersion: 0, body: { ...copy(body), patientId: c.patient } } });
        const current = sourceAttempt;
        state.sourceOperation = 'adopt';
        state.sourcePhase = 'submitting';
        state.sourceKey = current.data.commandKey;
        try {
            const data = unwrap(await api.adopt(copy(current.data), c.options));
            if (!owns(c) || sourceAttempt !== current)
                return { status: 'stale' };
            state.sourcePhase = 'succeeded';
            state.receipt = { sourceType: current.data.body.sourceType, sourceId: current.data.body.sourceId, followUps: [data] };
            sourceAttempt = null;
            return { status: 'succeeded', data };
        }
        catch (e) {
            if (!owns(c) || sourceAttempt !== current)
                return { status: 'stale' };
            if (denied(e))
                return { status: 'denied' };
            state.error = errorCode(e);
            state.sourcePhase = uncertain(e) ? 'uncertain' : status(e) === 409 ? 'conflict' : 'rejected';
            return { status: state.sourcePhase };
        }
    }
    async function retryDelivery(row, acknowledged, { retry = false } = {}) {
        if (state.deliveryPhase === 'submitting')
            return { status: 'busy' };
        if (state.deliveryPhase === 'uncertain' && !retry)
            return { status: 'retry-required' };
        if (state.deliveryPhase === 'conflict')
            return { status: 'decision-required' };
        if (retry && !deliveryAttempt)
            return { status: 'retry-unavailable' };
        if (!retry) {
            if (!row?.canRetry || !state.deliveries.some(x => x.id === row.id && x.retryToken === row.retryToken))
                return { status: 'denied' };
            if (row.requiresDuplicateRiskAcknowledgement && !acknowledged)
                return { status: 'acknowledgement-required' };
        }
        const c = context('delivery-command');
        if (!c)
            return { status: 'unauthenticated' };
        if (!retry)
            deliveryAttempt = freeze({ id: row.id, body: { retryToken: row.retryToken, duplicateRiskAcknowledged: !!acknowledged } });
        const current = deliveryAttempt;
        state.deliveryPhase = 'submitting';
        state.deliveryDecisionReady = false;
        state.deliveryAttemptId = current.id;
        try {
            const data = unwrap(await api.deliveryRetry(current.id, copy(current.body), c.options));
            if (!owns(c) || deliveryAttempt !== current)
                return { status: 'stale' };
            state.deliveryPhase = 'succeeded';
            deliveryAttempt = null;
            await paged('delivery');
            return { status: 'succeeded', data };
        }
        catch (e) {
            if (!owns(c) || deliveryAttempt !== current)
                return { status: 'stale' };
            if (denied(e))
                return { status: 'denied' };
            state.error = errorCode(e);
            state.deliveryPhase = uncertain(e) ? 'uncertain' : status(e) === 409 ? 'conflict' : 'rejected';
            if (state.deliveryPhase === 'conflict')
                await paged('delivery');
            return { status: state.deliveryPhase };
        }
    }
    const stop = watch(() => unref(patientRef), clear, { flush: 'sync' }), storage = e => {
        if (e.key == null || ['token', 'userId', 'username', 'realName'].includes(e.key))
            authChanged();
    };
    host?.addEventListener('auth-session-cleared', authChanged);
    host?.addEventListener('storage', storage);
    function dispose() {
        if (disposed)
            return;
        clear();
        disposed = true;
        stop();
        host?.removeEventListener('auth-session-cleared', authChanged);
        host?.removeEventListener('storage', storage);
    }
    if (getCurrentScope())
        onScopeDispose(dispose);
    return { state, loadCapabilities, load, open, refreshDetail, loadEvents: cursor => paged('events', cursor), loadDeliveries: cursor => paged('delivery', cursor), openDialog, closeDialog, loadAssignees, submit, newDecision, readSource, loadSourceHistory, saveSource, adoptSource, retryDelivery, reset: clear, dispose };
}
