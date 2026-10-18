import * as Vue from 'vue';
import * as stockApi from '@/api/stockWorkflow';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
import { createHouseholdCommandSession } from '@/utils/householdCommandSession';
const copy = value => value == null ? value : JSON.parse(JSON.stringify(value));
const unwrap = response => { if (response?.code && response.code !== 200) throw Object.assign(new Error('REQUEST_REJECTED'), { status: response.code, data: response.data }); return response && Object.hasOwn(response, 'data') ? response.data : response; };
const status = error => Number(error?.response?.status ?? error?.status ?? error?.code) || 0;
/** One shared technical command session, with domain-specific drafts and current-authority projections. */
export function useStockWorkflow(patientRef, deps = {}) {
  const { reactive, unref, watch, getCurrentScope, onScopeDispose } = Vue;
  const api = deps.api || stockApi, host = deps.events || globalThis.window;
  const patient = () => Number(unref(patientRef)) || null;
  const captureAuth = deps.captureAuth || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }));
  const isAuthCurrent = deps.isAuthCurrent || (auth => isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId'));
  const newKey = deps.newKey || (() => { if (globalThis.crypto.randomUUID) return globalThis.crypto.randomUUID(); const b = globalThis.crypto.getRandomValues(new Uint8Array(16)); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128; const h = [...b].map(n => n.toString(16).padStart(2, '0')).join(''); return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`; });
  const initial = () => ({ capabilities: null, items: [], nextCursor: null, sources: [], sourceCursor: null, detail: null, events: [], eventCursor: null, deliveries: [], deliveryCursor: null, draft: null, operation: null, phase: 'idle', error: null, loading: false, dialog: false, reviewedWinner: null, deliveryPhase: 'idle' });
  const state = reactive(initial()); let session = create(), disposed = false, deliveryAttempt = null, deliveryRequest = null, loadingRequest = null, selectedStock = null; const controllers = new Set();
  function create() { return createHouseholdCommandSession({ patient, captureAuth, isAuthCurrent, events: host, newKey }); }
  function clear() { session.dispose(); for (const c of controllers) c.abort(); controllers.clear(); deliveryAttempt = null; deliveryRequest = null; selectedStock = null; loadingRequest = null; Object.assign(state, initial()); if (!disposed) session = create(); }
  function fail(error) { if ([401,403].includes(status(error))) { clear(); state.phase = 'forbidden'; state.error = 'ACCESS_DENIED'; } else state.error = error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'REQUEST_FAILED'; }
  async function read(lane, fetch, apply) { if (disposed || !patient()) return { phase: 'unavailable' }; const origin = session, context = origin.capture(lane), controller = new AbortController(); controllers.add(controller); const options = { expectedAuth: context.auth, signal: controller.signal, patientId: patient() }; try { const data = unwrap(await fetch(options)); if (origin !== session || !origin.owns(context) || controller.signal.aborted) return { phase: 'discarded' }; apply(data); return { phase: 'succeeded' }; } catch (error) { if (origin !== session || !origin.owns(context) || controller.signal.aborted) return { phase: 'discarded' }; fail(error); return { phase: state.phase === 'forbidden' ? 'forbidden' : 'unavailable' }; } finally { controllers.delete(controller); } }
  async function load(stockId = null) {
    // A typed locator reads its fixed detail first. Broad list discovery is a separate user action.
    const target = stockId == null ? null : Number(stockId), detail = target ? open(target) : null;
    const origin = session, request = {}; loadingRequest = request; state.loading = true;
    try {
      if (detail) {
        const result = await detail;
        if (origin !== session || loadingRequest !== request || result.phase !== 'succeeded') return result;
        return await read('capabilities', options => api.capabilities(patient(), options), data => { state.capabilities = data; });
      }
      const result = await read('capabilities', options => api.capabilities(patient(), options), data => { state.capabilities = data; });
      if (origin === session && loadingRequest === request && result.phase === 'succeeded' && state.capabilities?.read) await loadList();
      return result;
    } finally {
      if (origin === session && loadingRequest === request) { state.loading = false; loadingRequest = null; }
    }
  }
  function loadList(more = false) { const cursor = more ? state.nextCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('list', options => api.list({ patientId: patient(), limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { state.items = cursor ? [...state.items, ...page.items] : page.items; state.nextCursor = page.nextCursor; }); }
  function resetDelivery() { deliveryRequest = null; deliveryAttempt = null; state.deliveryPhase = 'idle'; }
  async function open(id, preserveDraft = false) {
    id = Number(id); if (!Number.isSafeInteger(id) || id < 1) return { phase: 'unavailable' };
    // Locator changes are navigation ownership changes. Deliberate close/reopen of the same locator is not.
    if (selectedStock != null && selectedStock !== id) clear();
    selectedStock = id;
    if (!preserveDraft) { session.close(); state.draft = null; state.dialog = false; }
    state.detail = null; state.events = []; state.deliveries = []; state.eventCursor = null; state.deliveryCursor = null; resetDelivery();
    return read('detail', options => api.detail(id, options), data => { if (Number(data?.patientId) !== patient()) { clear(); state.error = 'PATIENT_MISMATCH'; return; } state.detail = data; });
  }
  function loadEvents(more = false) { if (!state.detail) return Promise.resolve({ phase: 'unavailable' }); const id = state.detail.stockId, cursor = more ? state.eventCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('events', options => api.events(id, { limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { if (state.detail?.stockId !== id) return; state.events = cursor ? [...state.events, ...page.items] : page.items; state.eventCursor = page.nextCursor; }); }
  function loadDeliveries(more = false) { if (!state.detail) return Promise.resolve({ phase: 'unavailable' }); const id = state.detail.stockId, cursor = more ? state.deliveryCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('deliveries', options => api.deliveryStatus(id, { limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { if (state.detail?.stockId !== id) return; state.deliveries = cursor ? [...state.deliveries, ...page.items] : page.items; state.deliveryCursor = page.nextCursor; if (state.deliveryPhase === 'conflict' || state.deliveryPhase === 'invalid') state.deliveryPhase = 'idle'; }); }
  function openDraft(operation, draft = {}) { if (['unknown','submitting'].includes(state.phase) || !state.detail?.allowedCommands?.includes(operation)) return false; try { session.reset(); } catch { return false; } state.operation = operation; state.draft = copy(draft); state.dialog = true; state.phase = 'idle'; state.error = null; return true; }
  async function outcome(origin, result) {
    if (origin !== session || disposed) return { phase: 'discarded' };
    if (result.phase === 'discarded') {
      // Private settlement may release busy/recoverability, but never adopts a closed view's result.
      if (result.settledPhase) { state.phase = result.settledPhase; state.error = result.errorCode || null; if (result.settledPhase === 'succeeded') origin.reset(); }
      return result;
    }
    state.phase = result.phase; state.error = result.errorCode || null;
    if (result.phase === 'forbidden') { clear(); state.phase = 'forbidden'; state.error = 'ACCESS_DENIED'; }
    else if (result.phase === 'succeeded') { state.draft = null; state.dialog = false; origin.reset(); if (result.result?.stockId) await open(result.result.stockId); if (origin === session) await loadList(); }
    return result;
  }
  const send = envelope => api.command(envelope, { expectedAuth: captureAuth() }).then(unwrap);
  async function submit() {
    if (!state.draft || !state.operation || ['submitting','unknown','invalid','conflict'].includes(state.phase)) return { phase: 'unavailable' };
    const origin = session, target = state.detail?.stockId;
    if (!target) return { phase: 'unavailable' };
    try { origin.begin(state.operation, target, { patientId: patient(), expectedVersion: state.detail.version }, state.draft); state.phase = 'submitting'; return await outcome(origin, await origin.submit(send)); } catch { return { phase: origin === session ? 'unavailable' : 'discarded' }; }
  }
  async function retry() { const origin = session, previous = state.phase; try { state.phase = 'submitting'; return await outcome(origin, await origin.retryIdentical(send)); } catch { if (origin === session) state.phase = previous; return { phase: origin === session ? 'unavailable' : 'discarded' }; } }
  async function reconcile() { const origin = session; try { return await outcome(origin, await origin.reconcile(envelope => api.receipt(envelope.patientId, envelope.commandKey, { expectedAuth: captureAuth() }).then(unwrap))); } catch { return { phase: origin === session ? 'unavailable' : 'discarded' }; } }
  function editAfterError() { if (!['invalid','conflict'].includes(state.phase)) return false; if (state.error === 'STOCK_ADOPTED' && state.draft) return false; session.reset(); state.phase = 'idle'; state.error = null; return true; }
  function close() { session.close(); state.dialog = false; state.draft = null; state.operation = null; state.detail = null; state.events = []; state.deliveries = []; state.reviewedWinner = null; resetDelivery(); }
  async function retryDelivery(row, acknowledged) {
    if (!state.detail || !row?.canRetry || !state.deliveries.some(item => item.id === row.id && item.retryToken === row.retryToken)) return { phase: 'unavailable' };
    if (row.requiresDuplicateRiskAcknowledgement && !acknowledged) return { phase: 'acknowledgement-required' };
    if (state.deliveryPhase === 'conflict') return { phase: 'decision-required' };
    if (state.deliveryPhase === 'submitting') return { phase: 'unavailable' };
    if (deliveryAttempt && state.deliveryPhase === 'unknown' && deliveryAttempt.id !== row.id) return { phase: 'unavailable' };
    const origin = session, context = origin.capture('delivery-retry'), stock = selectedStock, request = {};
    if (!deliveryAttempt || state.deliveryPhase !== 'unknown') deliveryAttempt = Object.freeze({ id: row.id, body: Object.freeze({ retryToken: row.retryToken, duplicateRiskAcknowledged: !!acknowledged }) });
    const attempt = deliveryAttempt; deliveryRequest = request; state.deliveryPhase = 'submitting';
    const owns = () => origin === session && origin.owns(context) && deliveryRequest === request && selectedStock === stock;
    try {
      await api.retryDelivery(attempt.id, attempt.body, { expectedAuth: context.auth });
      if (!owns()) return { phase: 'discarded' };
      state.deliveryPhase = 'succeeded'; deliveryAttempt = null; await loadDeliveries(); return { phase: 'succeeded' };
    } catch (error) {
      if (!owns()) return { phase: 'discarded' };
      if ([401,403].includes(status(error))) { fail(error); return { phase: 'forbidden' }; }
      state.deliveryPhase = status(error) === 409 ? 'conflict' : status(error) === 400 ? 'invalid' : 'unknown';
      if (state.deliveryPhase !== 'unknown') deliveryAttempt = null;
      return { phase: state.deliveryPhase };
    } finally {
      if (deliveryRequest === request) { deliveryRequest = null; if (state.deliveryPhase === 'submitting') state.deliveryPhase = 'idle'; }
    }
  }
  const onIdentity = () => clear(); for (const event of ['auth-session-cleared','auth-session-changed','storage']) host?.addEventListener?.(event, onIdentity);
  const stop = watch(patientRef, clear, { flush: 'sync' });
  function dispose() { disposed = true; stop(); clear(); for (const event of ['auth-session-cleared','auth-session-changed','storage']) host?.removeEventListener?.(event, onIdentity); }
  if (getCurrentScope()) onScopeDispose(dispose);
  return { state, invalidate: clear, load, loadList, open, loadEvents, loadDeliveries, openDraft, submit, retry, reconcile, editAfterError, close, retryDelivery, dispose };
}
