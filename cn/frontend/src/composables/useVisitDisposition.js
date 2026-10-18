import * as Vue from 'vue';
import * as appointmentApi from '@/api/visitFollowUp';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
import { createHouseholdCommandSession } from '@/utils/householdCommandSession';
const copy = value => value == null ? value : JSON.parse(JSON.stringify(value));
const unwrap = response => { if (response?.code && response.code !== 200) throw Object.assign(new Error('REQUEST_REJECTED'), { status: response.code, data: response.data }); return response && Object.hasOwn(response, 'data') ? response.data : response; };
const status = error => Number(error?.response?.status ?? error?.status ?? error?.code) || 0;
const validId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0;
/** 就诊命令使用独立会话：未知创建只重放原包，不能按列表猜测一次新建已失败。 */
export function useVisitDisposition(patientRef, deps = {}) {
  const { reactive, unref, watch, getCurrentScope, onScopeDispose } = Vue;
  const api = deps.api || appointmentApi, host = deps.events || globalThis.window;
  const patient = () => Number(typeof patientRef === 'function' ? patientRef() : unref(patientRef)) || null;
  const captureAuth = deps.captureAuth || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }));
  const isAuthCurrent = deps.isAuthCurrent || (value => isAuthSessionCurrent(value) && value.actorId === localStorage.getItem('userId'));
  const newKey = deps.newKey || (() => globalThis.crypto.randomUUID());
  const initial = () => ({ capabilities: null, capabilityPhase: 'idle', candidatePhase: 'idle', originPhase: 'idle', eventPhase: 'idle', detail: null, items: [], nextCursor: null, events: [], eventCursor: null, candidates: [], candidateCursor: null, target: null, operation: null, draft: null, submitted: null, receipt: null, phase: 'idle', readPhase: 'idle', error: null, compared: false });
  const state = reactive(initial()), controllers = new Map();
  let disposed = false, selected = null, commandAuth = null, session = create();
  function create() { return createHouseholdCommandSession({ patient, captureAuth, isAuthCurrent, events: host, newKey }); }
  function clear() { session.dispose(); for (const c of controllers.values()) c.abort(); controllers.clear(); selected = null; commandAuth = null; Object.assign(state, initial()); if (!disposed) session = create(); }
  function fail(error) { if ([401, 403].includes(status(error))) { clear(); state.phase = 'forbidden'; state.readPhase = 'unavailable'; } state.error = error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'REQUEST_FAILED'; }
  async function read(lane, fetch, apply) {
    if (disposed || !patient()) return { phase: 'unavailable' };
    controllers.get(lane)?.abort(); const origin = session, context = origin.capture(lane), controller = new AbortController(); controllers.set(lane, controller);
    try {
      const value = unwrap(await fetch({ expectedAuth: context.auth, signal: controller.signal }));
      if (origin !== session || !origin.owns(context) || controller.signal.aborted) return { phase: 'discarded' };
      apply(value); return { phase: 'succeeded' };
    } catch (error) {
      if (origin !== session || !origin.owns(context) || controller.signal.aborted) return { phase: 'discarded' };
      fail(error); return { phase: 'unavailable' };
    } finally { if (controllers.get(lane) === controller) controllers.delete(lane); }
  }
  async function loadCapabilities() { state.capabilityPhase='loading'; const result=await read('capabilities', options => api.capabilities(patient(), options), data => { state.capabilities = data; state.capabilityPhase='ready'; if (selected === 'VISIT_RECORD:new' && state.detail) state.detail.allowedCommands = data.allowedCommands?.includes('CREATE_VISIT') ? ['CREATE_VISIT'] : []; }); if(result.phase==='unavailable'){state.capabilities=null;state.capabilityPhase='unavailable';} return result; }
  function loadPage(next = false) {
    const cursor = next ? state.nextCursor : null; if (next && !cursor) return Promise.resolve({ phase: 'unavailable' });
    state.items = []; state.readPhase = 'loading';
    return read('list', options => api.visits({ patientId: patient(), limit: 25, ...(cursor ? { cursor } : {}) }, options), data => {
      if (!Array.isArray(data.items) || data.items.length > 25 || data.items.some(item => Number(item.patientId) !== patient() || item.sourceKind !== 'VISIT_RECORD' || !validId(item.sourceId))) throw Error('PATIENT_MISMATCH');
      state.items = data.items; state.nextCursor = data.nextCursor; state.readPhase = 'ready';
    }).then(result => { if (result.phase === 'unavailable') state.readPhase = 'unavailable'; return result; });
  }
  async function open(kind, id, preserve = false) {
    id = Number(id); if (!validId(id) || kind !== 'VISIT_RECORD') return { phase: 'unavailable' };
    const key = `${kind}:${id}`;
    if ((locked() || state.draft) && selected !== key) return { phase: 'unavailable' };
    if (selected !== key) { session.close(); state.detail = null; state.events = []; state.target = null; state.draft = null; state.submitted = null; state.receipt = null; state.operation = null; state.phase = 'idle'; }
    selected = key; state.readPhase = 'loading';
    const result = await read('source', options => api.source(patient(), kind, id, options), data => {
      if (Number(data.patientId) !== patient() || data.sourceKind !== kind || Number(data.sourceId) !== id) throw Error('PATIENT_MISMATCH');
      state.detail = data; state.readPhase = 'ready'; if (!preserve && !locked()) state.error = null;
    });
    if (result.phase === 'unavailable') { state.detail = null; state.readPhase = 'unavailable'; } return result;
  }
  function begin(operation, body) {
    if (locked() || ['conflict', 'invalid'].includes(state.phase) || !state.detail?.allowedCommands?.includes(operation)) return false;
    try { session.reset(); } catch { return false; }
    state.operation = operation; state.draft = copy(body); state.submitted = null; state.receipt = null; state.target = null; state.phase = 'idle'; state.error = null; state.compared = false; return true;
  }
  // 未知结果只重发原键、原版本和原正文；读取最新来源不等于确认原提交失败。
  const envelope = value => ({ commandKey: value.commandKey, patientId: value.patientId, sourceKind: value.target.sourceKind, sourceId: value.target.sourceId, operation: value.operation, expectedVersion: value.expectedVersion, expectedSourceFingerprint: value.target.sourceFingerprint, body: value.body });
  // HTTP成功不是命令回执证明；错来源/版本或畸形回执仍保留原包，按未知结果恢复。
  function receipt(value, result) {
    const positive = id => typeof id === 'number' && Number.isSafeInteger(id) && id > 0;
    if (!result || typeof result !== 'object' || Array.isArray(result) ||
        !positive(result.workflowId) || !positive(result.eventId) || !positive(result.sourceId) || !positive(result.version) ||
        result.sourceKind !== 'VISIT_RECORD' || (value.operation !== 'CREATE_VISIT' && result.sourceId !== value.target.sourceId) ||
        result.operation !== value.operation || result.version !== value.expectedVersion + 1 ||
        Object.hasOwn(result, 'successorKind') || Object.hasOwn(result, 'successorId')) throw Error('RECEIPT_UNVERIFIED');
    return result;
  }
  const send = value => api.command(envelope(value), { expectedAuth: commandAuth }).then(unwrap).then(result => receipt(value, result));
  async function settle(origin, result) {
    if (origin !== session || disposed) return { phase: 'discarded' };
    if (result.phase === 'discarded') return result;
    state.phase = result.phase; state.error = result.errorCode || null;
    if (result.phase === 'forbidden') { clear(); state.phase = 'forbidden'; state.readPhase = 'unavailable'; state.error = 'unavailable'; }
    if (result.phase === 'succeeded') {
      // 已确认保存的回执独立于后续读取；503不能把成功改成未知，也不能生成新命令。
      state.receipt = copy(result.result); state.draft = null; state.operation = null; state.target = null; origin.reset();
      if (result.result.operation === 'CREATE_VISIT') selected = `VISIT_RECORD:${result.result.sourceId}`;
      await reloadSelected();
      if (origin !== session || disposed) return { phase: 'discarded' };
    }
    return result;
  }
  async function submit(body = state.draft) {
    if (!state.detail || !state.draft || locked() || ['invalid', 'conflict'].includes(state.phase) || !state.detail.allowedCommands?.includes(state.operation)) return { phase: 'unavailable' };
    const origin = session, detail = state.detail;
    try {
      commandAuth = captureAuth(); const value = origin.begin(state.operation, { sourceKind: detail.sourceKind, sourceId: detail.sourceId, sourceFingerprint: detail.sourceFingerprint }, { patientId: patient(), expectedVersion: detail.version }, body);
      state.submitted = copy(envelope(value)); state.compared = false; state.phase = 'submitting'; return await settle(origin, await origin.submit(send));
    } catch { if (origin === session && state.phase !== 'submitting') state.error = 'INVALID_REQUEST'; return { phase: origin === session ? 'unavailable' : 'discarded' }; }
  }
  async function retry() { const origin = session; if (state.phase !== 'unknown') return { phase: 'unavailable' }; state.phase = 'submitting'; try { return await settle(origin, await origin.retryIdentical(send)); } catch { if (origin === session) state.phase = 'unknown'; return { phase: 'unavailable' }; } }
  // 来源正文不可读时仍保留原typed身份；只有同账号/患者会话可以重读，401/403会清空全部。
  function reloadSelected() { if (!selected) return Promise.resolve({ phase: 'unavailable' }); if (selected === 'VISIT_RECORD:new') return loadPage(false).then(result => result.phase === 'succeeded' ? loadCapabilities() : result); const [kind, id] = selected.split(':'); return open(kind, id, true); }
  function beginCreate(body) {
    if (locked() || state.draft || !state.capabilities?.allowedCommands?.includes('CREATE_VISIT')) return false;
    session.reset(); selected = 'VISIT_RECORD:new'; state.detail = { patientId: patient(), sourceKind: 'VISIT_RECORD', sourceId: null, version: 0, sourceFingerprint: null, allowedCommands: ['CREATE_VISIT'] };
    return begin('CREATE_VISIT', body);
  }
  async function compare() { if (!['conflict', 'invalid', 'unknown'].includes(state.phase)) return { phase: 'unavailable' }; const origin = session; state.compared = false; const result = await reloadSelected(); if (origin === session && result.phase === 'succeeded') state.compared = true; return result; }
  function editAfterComparison() { if (!['invalid', 'conflict'].includes(state.phase) || state.phase === 'conflict' && !state.compared || !state.detail?.allowedCommands?.includes(state.operation)) return false; session.reset(); state.phase = 'idle'; state.submitted = null; state.compared = false; state.error = null; state.target = null; return true; }
  function loadCandidates(next = false) {
    const cursor = next ? state.candidateCursor : null; if (next && !cursor || locked()) return Promise.resolve({ phase: 'unavailable' }); state.candidates = []; state.candidatePhase='loading';
    return read('candidates', options => api.visitActions({ patientId: patient(), limit: 25, ...(cursor ? { cursor } : {}) }, options), data => { if (!Array.isArray(data.items) || data.items.length > 25) throw Error('INVALID_ACTION'); state.candidates = data.items; state.candidateCursor = data.nextCursor; state.candidatePhase='ready'; }).then(result=>{if(result.phase==='unavailable')state.candidatePhase='unavailable';return result;});
  }
  function selectOrigin(kind, id) {
    if (locked() || !['CARE_ITEM_APPOINTMENT', 'CARE_APPOINTMENT'].includes(kind) || !validId(id)) return Promise.resolve({ phase: 'unavailable' }); state.target = null; state.originPhase='loading';
    return read('origin', options => api.source(patient(), kind, Number(id), options), data => { if (data.patientId !== patient() || data.sourceKind !== kind || data.sourceId !== Number(id) || !data.managed) throw Error('ORIGIN_CONFIRM_REQUIRED'); state.target = data; state.originPhase='ready'; }).then(result=>{if(result.phase==='unavailable')state.originPhase='unavailable';return result;});
  }
  function loadEvents(next = false) { const detail = state.detail, cursor = next ? state.eventCursor : null; if (!detail?.sourceId || next && !cursor) return Promise.resolve({ phase: 'unavailable' }); state.events=[];state.eventPhase='loading';return read('events', options => api.events(patient(), detail.sourceKind, detail.sourceId, { limit: 25, ...(cursor ? { cursor } : {}) }, options), data => { state.events = data.items; state.eventCursor = data.nextCursor; state.eventPhase='ready'; }).then(result=>{if(result.phase==='unavailable')state.eventPhase='unavailable';return result;}); }
  function cancelDraft() { if (locked()) return false; session.reset(); state.draft = null; state.operation = null; state.submitted = null; state.phase = 'idle'; state.compared = false; state.target = null; if (selected === 'VISIT_RECORD:new') { selected = null; state.detail = null; } return true; }
  function locked() { return ['unknown', 'submitting'].includes(state.phase); }
  const listeners = ['auth-session-cleared', 'auth-session-changed', 'storage', 'patient-changed']; for (const name of listeners) host?.addEventListener?.(name, clear);
  const stop = watch(patient, clear, { flush: 'sync' });
  function dispose() { disposed = true; stop?.(); clear(); for (const name of listeners) host?.removeEventListener?.(name, clear); }
  if (getCurrentScope()) onScopeDispose(dispose);
  return { state, loadCapabilities, loadPage, open, reloadSelected, hasSelection: () => !!selected, begin, beginCreate, submit, retry, compare, editAfterComparison, loadCandidates, selectOrigin, loadEvents, cancelDraft, locked, clear, dispose };
}
