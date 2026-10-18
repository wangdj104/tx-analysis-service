import * as Vue from 'vue';
import * as assistanceApi from '@/api/familyAssistance';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
import { createHouseholdCommandSession } from '@/utils/householdCommandSession';
import { handoverId, typedHandoverSource, isOrdinaryHandover, sameHandoverContext, sameHandoverContent, handoverUtcDeadline } from '@/utils/familyHandover';
const copy = value => value == null ? value : JSON.parse(JSON.stringify(value));
const unwrap = response => { if (response?.code && response.code !== 200) throw Object.assign(new Error('REQUEST_REJECTED'), { status: response.code, data: response.data }); return response && Object.hasOwn(response, 'data') ? response.data : response; };
const status = error => Number(error?.response?.status ?? error?.status ?? error?.code) || 0;
/** One shared technical command session, with domain-specific drafts and current-authority projections. */
export function useFamilyAssistance(patientRef, deps = {}) {
  const { reactive, unref, watch, getCurrentScope, onScopeDispose } = Vue;
  const api = deps.api || assistanceApi, host = deps.events || globalThis.window;
  const patient = () => Number(unref(patientRef)) || null;
  const captureAuth = deps.captureAuth || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }));
  const isAuthCurrent = deps.isAuthCurrent || (auth => isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId'));
  const newKey = deps.newKey || (() => { if (globalThis.crypto.randomUUID) return globalThis.crypto.randomUUID(); const b = globalThis.crypto.getRandomValues(new Uint8Array(16)); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128; const h = [...b].map(n => n.toString(16).padStart(2, '0')).join(''); return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`; });
  const initial = () => ({ capabilities: null, items: [], nextCursor: null, sources: [], sourceCursor: null, detail: null, events: [], eventCursor: null, deliveries: [], deliveryCursor: null, draft: null, operation: null, phase: 'idle', error: null, loading: false, dialog: false, reviewedWinner: null, deliveryPhase: 'idle', handoverSource: null, sourcePreview: null, sourcePhase: 'idle', handoverContent: null, contentPhase: 'idle', candidates: [], candidateContext: null, candidateSearch: '', candidatePhase: 'idle', workItems: [], workCursor: null, workMore: false, workPhase: 'idle', handoverDraft: false, frozenSourceId: null, sourceSettlement: null, navigationBlocked: false });
  const state = reactive(initial()); let session = create(), disposed = false, deliveryAttempt = null, deliveryRequest = null, loadingRequest = null, selectedAssistance = null; const controllers = new Set(), laneControllers = new Map(); let pendingSource = null, viewEpoch = 0;
  function create() { return createHouseholdCommandSession({ patient, captureAuth, isAuthCurrent, events: host, newKey }); }
  function clear() { viewEpoch++; session.dispose(); for (const c of controllers) c.abort(); controllers.clear(); laneControllers.clear(); pendingSource = null; deliveryAttempt = null; deliveryRequest = null; selectedAssistance = null; loadingRequest = null; Object.assign(state, initial()); if (!disposed) session = create(); }
  function fail(error) { if ([401,403].includes(status(error))) { clear(); state.phase = 'forbidden'; state.error = 'ACCESS_DENIED'; } else state.error = error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'REQUEST_FAILED'; }
  function cancelLane(lane) { const controller = laneControllers.get(lane); if (controller) { controller.abort(); controllers.delete(controller); laneControllers.delete(lane); } }
  function clearCandidates() { cancelLane('handover-candidates'); state.candidates = []; state.candidateContext = null; state.candidateSearch = ''; state.candidatePhase = 'idle'; if (state.handoverDraft && state.draft) state.draft.assignedUserId = ''; }
  function clearEnrichment() { cancelLane('handover-content'); state.handoverContent = null; state.contentPhase = 'idle'; clearCandidates(); }
  function clearHandoverReads() { clearEnrichment(); cancelLane('handover-source'); cancelLane('handover-work'); state.handoverSource = null; state.sourcePreview = null; state.sourcePhase = 'idle'; state.workItems = []; state.workCursor = null; state.workMore = false; state.workPhase = 'idle'; }
  // 命令重置会退役旧读取；同步退出加载态，不能等一个可能永不返回的响应来恢复按钮。
  function resetSessionReads(origin = session) {
    origin.reset();
    for (const [lane, phase] of [['handover-source','sourcePhase'], ['handover-content','contentPhase'], ['handover-candidates','candidatePhase'], ['handover-work','workPhase']]) {
      cancelLane(lane);
      if (state[phase] === 'loading') state[phase] = 'idle';
    }
  }
  // 只读的本域视图标记；不会打开或重置原命令会话，也不改变未知结果的原键。
  function captureView() { return viewEpoch; }
  function ownsView(epoch) { return !disposed && epoch === viewEpoch; }
  function projectionChanged() { clearEnrichment(); state.sourcePreview = null; state.sourcePhase = 'unavailable'; state.contentPhase = 'unavailable'; state.candidatePhase = 'unavailable'; state.error = 'HANDOVER_PROJECTION_CHANGED'; return false; }
  async function read(lane, fetch, apply, unavailable = () => {}) {
    if (disposed || !patient()) return { phase: 'unavailable' };
    cancelLane(lane); const origin = session, context = origin.capture(lane), controller = new AbortController(); controllers.add(controller); laneControllers.set(lane, controller);
    const options = { expectedAuth: context.auth, signal: controller.signal, patientId: patient() };
    try {
      const data = unwrap(await fetch(options));
      if (origin !== session || !origin.owns(context) || controller.signal.aborted) return { phase: 'discarded' };
      return { phase: apply(data) === false ? 'unavailable' : 'succeeded' };
    } catch (error) {
      if (origin !== session || !origin.owns(context) || controller.signal.aborted) return { phase: 'discarded' };
      unavailable(); fail(error); return { phase: state.phase === 'forbidden' ? 'forbidden' : 'unavailable' };
    } finally { controllers.delete(controller); if (laneControllers.get(lane) === controller) laneControllers.delete(lane); }
  }
  async function load(assistanceId = null) {
    // A typed locator reads its fixed detail first. Broad list discovery is a separate user action.
    const target = assistanceId == null ? null : Number(assistanceId), detail = target ? open(target) : null;
    const origin = session, request = {}; loadingRequest = request; state.loading = true;
    try {
      if (detail) {
        const result = await detail;
        if (origin !== session || loadingRequest !== request || result.phase !== 'succeeded') return result;
        return await read('capabilities', options => api.capabilities(patient(), options), data => { state.capabilities = data; });
      }
      const result = await read('capabilities', options => api.capabilities(patient(), options), data => { state.capabilities = data; });
      if (origin === session && loadingRequest === request && result.phase === 'succeeded' && state.capabilities?.read && state.capabilities?.ordinary) await loadHandoverWork();
      return result;
    } finally {
      if (origin === session && loadingRequest === request) { state.loading = false; loadingRequest = null; }
    }
  }
  function loadList(more = false) { const cursor = more ? state.nextCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('list', options => api.list({ patientId: patient(), limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { state.items = cursor ? [...state.items, ...page.items] : page.items; state.nextCursor = page.nextCursor; }); }
  function resetDelivery() { deliveryRequest = null; deliveryAttempt = null; state.deliveryPhase = 'idle'; }
  async function open(id, preserveDraft = false) {
    id = Number(id); if (!Number.isSafeInteger(id) || id < 1) return { phase: 'unavailable' };
    if (['unknown','submitting'].includes(state.phase) && selectedAssistance !== id) return { phase: 'unavailable' };
    viewEpoch++;
    // Locator changes retire presentation callbacks; command recovery still belongs to the original session.
    if (selectedAssistance != null && selectedAssistance !== id) clear();
    selectedAssistance = id; clearEnrichment();
    // 打开新修订先撤掉旧工作页的人名；迟到的旧页不能补回已清除的责任事实。
    cancelLane('handover-work'); state.workItems = []; state.workCursor = null; state.workMore = false; state.workPhase = 'idle';
    if (!preserveDraft) { state.handoverSource = null; state.sourcePreview = null; state.sourcePhase = 'idle'; state.handoverDraft = false; }
    if (!preserveDraft) { session.close(); state.draft = null; state.dialog = false; if (['invalid','conflict'].includes(state.phase)) { state.phase = 'idle'; state.operation = null; state.error = null; state.reviewedWinner = null; } }
    state.detail = null; state.events = []; state.deliveries = []; state.eventCursor = null; state.deliveryCursor = null; resetDelivery();
    const result = await read('detail', options => api.detail(id, options), data => { if (Number(data?.patientId) !== patient()) { clear(); state.error = 'PATIENT_MISMATCH'; return; } state.detail = data; });
    if (result.phase === 'succeeded' && isOrdinaryHandover(state.detail)) await loadHandoverContent();
    return result;
  }
  function loadEvents(more = false) { if (!state.detail) return Promise.resolve({ phase: 'unavailable' }); const id = state.detail.assistanceId, cursor = more ? state.eventCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('events', options => api.events(id, { limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { if (state.detail?.assistanceId !== id) return; state.events = cursor ? [...state.events, ...page.items] : page.items; state.eventCursor = page.nextCursor; }); }
  function loadDeliveries(more = false) { if (!state.detail) return Promise.resolve({ phase: 'unavailable' }); const id = state.detail.assistanceId, cursor = more ? state.deliveryCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('deliveries', options => api.deliveryStatus(id, { limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { if (state.detail?.assistanceId !== id) return; state.deliveries = cursor ? [...state.deliveries, ...page.items] : page.items; state.deliveryCursor = page.nextCursor; if (state.deliveryPhase === 'conflict' || state.deliveryPhase === 'invalid') state.deliveryPhase = 'idle'; }); }
  function openDraft(operation, draft = {}) { if (['unknown','submitting','conflict','invalid'].includes(state.phase) || !(operation === 'CREATE' ? state.capabilities?.create : state.detail?.allowedCommands?.includes(operation))) return false; try { resetSessionReads(); } catch { return false; } viewEpoch++; state.handoverDraft = false; clearCandidates(); state.operation = operation; state.draft = copy(draft); state.dialog = true; state.phase = 'idle'; state.error = null; return true; }
  async function outcome(origin, result) {
    if (origin !== session || disposed) return { phase: 'discarded' };
    if (result.phase === 'discarded') {
      // Private settlement may release busy/recoverability, but never adopts a closed view's result.
      if (result.settledPhase) { state.phase = result.settledPhase; state.error = result.errorCode || null; if (result.settledPhase === 'succeeded') { if (pendingSource) state.sourceSettlement = { careItemId: pendingSource, assistanceId: null }; state.frozenSourceId = null; pendingSource = null; resetSessionReads(origin); } else if (['invalid','conflict'].includes(result.settledPhase)) { state.frozenSourceId = null; pendingSource = null; } }
      return result;
    }
    if (pendingSource && ['succeeded','invalid','conflict'].includes(result.phase)) { if (result.phase === 'succeeded') state.sourceSettlement = { careItemId: pendingSource, assistanceId: result.result?.assistanceId || null }; state.frozenSourceId = null; pendingSource = null; }
    state.phase = result.phase; state.error = result.errorCode || null;
    if (result.phase === 'forbidden') { clear(); state.phase = 'forbidden'; state.error = 'ACCESS_DENIED'; }
    else if (result.phase === 'succeeded') { state.draft = null; state.dialog = false; resetSessionReads(origin); if (result.result?.assistanceId) await open(result.result.assistanceId); if (origin === session && state.detail) { const index = state.items.findIndex(row => row.assistanceId === state.detail.assistanceId); if (index >= 0) state.items.splice(index,1,copy(state.detail)); } }
    return result;
  }
  const send = envelope => api.command(envelope, { expectedAuth: captureAuth() }).then(unwrap);
  async function submit(bodyOverride = null) {
    if (!state.draft || !state.operation || ['submitting','unknown','invalid','conflict'].includes(state.phase)) return { phase: 'unavailable' };
    const origin = session, target = state.operation === 'CREATE' ? null : state.detail?.assistanceId;
    if (!target && state.operation !== 'CREATE') return { phase: 'unavailable' };
    try { if (state.handoverDraft) bodyOverride = handoverBody(); origin.begin(state.operation, target, { patientId: patient(), expectedVersion: state.operation === 'CREATE' ? 0 : state.detail.version }, bodyOverride || state.draft); if (state.operation === 'CREATE' && state.handoverDraft) { pendingSource = state.handoverSource.careItemId; state.frozenSourceId = pendingSource; } state.reviewedWinner = null; state.phase = 'submitting'; return await outcome(origin, await origin.submit(send)); } catch { return { phase: origin === session ? 'unavailable' : 'discarded' }; }
  }
  async function retry() { const origin = session, previous = state.phase; try { state.phase = 'submitting'; return await outcome(origin, await origin.retryIdentical(send)); } catch { if (origin === session) state.phase = previous; return { phase: origin === session ? 'unavailable' : 'discarded' }; } }
  async function reconcile() { const origin = session; try { return await outcome(origin, await origin.reconcile(envelope => api.receipt(envelope.patientId, envelope.commandKey, { expectedAuth: captureAuth() }).then(unwrap))); } catch { return { phase: origin === session ? 'unavailable' : 'discarded' }; } }
  function editAfterError() { if (!['invalid','conflict'].includes(state.phase) || state.phase === 'conflict' && !state.reviewedWinner) return false; if (state.operation !== 'CREATE' && !state.detail?.allowedCommands?.includes(state.operation) || state.operation === 'CREATE' && state.handoverDraft && !['UNTRACKED','CURRENT_TERMINAL'].includes(state.sourcePreview?.sourceReadiness)) return false; resetSessionReads(); viewEpoch++; state.reviewedWinner = null; state.phase = 'idle'; state.error = null; return true; }
  function close() { viewEpoch++; session.close(); if (!['unknown','submitting'].includes(state.phase)) { state.phase = 'idle'; state.error = null; } clearHandoverReads(); state.handoverDraft = false; state.items = []; state.nextCursor = null; state.dialog = false; state.draft = null; state.operation = null; state.detail = null; state.events = []; state.deliveries = []; state.reviewedWinner = null; resetDelivery(); }
  async function openHandoverSource(value, previewOnly = false) {
    const source = typedHandoverSource(value);
    if (!source || ['unknown','submitting'].includes(state.phase)) return { phase: 'unavailable' };
    viewEpoch++; session.close(); clearHandoverReads(); state.detail = null; selectedAssistance = null; state.items = []; state.events = []; state.deliveries = []; state.draft = null; state.operation = null; state.dialog = false; state.handoverDraft = false; state.phase = 'idle'; resetDelivery();
    state.handoverSource = source; state.sourcePhase = 'loading'; state.error = null;
    const result = await read('handover-source', options => api.handoverSource(source.careItemId, options), data => {
      if (!isOrdinaryHandover(data) || data.patientId !== patient() || data.careItemId !== source.careItemId) { return projectionChanged(); }
      state.sourcePreview = data; state.sourcePhase = 'ready';
    }, () => { state.sourcePreview = null; state.sourcePhase = 'unavailable'; });
    if (result.phase !== 'succeeded' || state.handoverSource !== source || !state.sourcePreview) return result;
    if (!previewOnly && handoverId(state.sourcePreview.currentAssistanceId)) return load(state.sourcePreview.currentAssistanceId);
    return read('capabilities', options => api.capabilities(patient(), options), data => { state.capabilities = data; });
  }
  function loadHandoverContent() {
    const detail = state.detail; clearEnrichment();
    if (!isOrdinaryHandover(detail)) return Promise.resolve({ phase: 'unavailable' });
    state.contentPhase = 'loading';
    return read('handover-content', options => api.handoverContent(detail.assistanceId, options), data => {
      if (state.detail !== detail || !sameHandoverContext(detail, data) || data.contentStatus !== 'UNAVAILABLE' && !sameHandoverContent(detail, data)) { return projectionChanged(); }
      state.handoverContent = data; state.contentPhase = 'ready';
    }, () => { state.handoverContent = null; state.contentPhase = 'unavailable'; });
  }
  function loadHandoverWork(more = false) {
    const cursor = more ? state.workCursor : null;
    if (more && !cursor) return Promise.resolve({ phase: 'unavailable' });
    state.workItems = []; state.workMore = false; state.workCursor = null; state.workPhase = 'loading';
    return read('handover-work', options => api.handoverWork({ patientId: patient(), limit: 25, ...(cursor ? { cursor } : {}) }, options), page => {
      if (page?.patientId !== patient() || !Array.isArray(page.items) || page.items.length > 25 || page.items.some(row => !isOrdinaryHandover(row) || row.patientId !== patient() || !row.currentSource || !handoverId(row.assistanceId))) { state.workPhase = 'unavailable'; return false; }
      state.workItems = page.items; state.workCursor = page.nextCursor; state.workMore = page.more === true; state.workPhase = 'ready';
    }, () => { state.workItems = []; state.workPhase = 'unavailable'; });
  }
  function loadHandoverCandidates(operation = state.operation, search = '') {
    clearCandidates();
    const context = operation === 'CREATE' ? state.sourcePreview : state.detail;
    if (!['CREATE','ASSIGN','HANDOFF'].includes(operation) || !isOrdinaryHandover(context) || operation === 'CREATE' && context.contentStatus !== 'AVAILABLE' || typeof search !== 'string' || [...search].length > 50) return Promise.resolve({ phase: 'unavailable' });
    state.candidateSearch = search; state.candidatePhase = 'loading';
    const params = { patientId: patient(), operation, ...(operation === 'CREATE' ? { careItemId: context.careItemId } : { assistanceId: context.assistanceId }), ...(search.trim() ? { search } : {}) };
    return read('handover-candidates', options => api.handoverCandidates(params, options), data => {
      const current = operation === 'CREATE' ? state.sourcePreview : state.detail;
      if (current !== context || operation !== state.operation || !sameHandoverContext(context, data) || data.operation !== operation || !Array.isArray(data.items) || data.items.length > 40 || data.items.some(item => !handoverId(item.userId))) { return projectionChanged(); }
      state.candidates = data.items; state.candidateContext = data; state.candidatePhase = 'ready';
    }, () => { state.candidates = []; state.candidateContext = null; state.candidatePhase = 'unavailable'; });
  }
  async function beginHandoverCreate() {
    if (!state.handoverSource || state.sourcePreview?.contentStatus !== 'AVAILABLE' || !['UNTRACKED','CURRENT_TERMINAL'].includes(state.sourcePreview.sourceReadiness)) return false;
    if (!openDraft('CREATE', { assignedUserId: '', deadlineUtc: '' })) return false;
    state.handoverDraft = true; await loadHandoverCandidates('CREATE'); return true;
  }
  async function beginHandoverAction(operation, draft) {
    if (!isOrdinaryHandover(state.detail) || !openDraft(operation, draft)) return false;
    state.handoverDraft = true;
    if (['ASSIGN','HANDOFF'].includes(operation)) await loadHandoverCandidates(operation);
    return true;
  }
  function handoverBody() {
    const operation = state.operation, draft = state.draft;
    if (!draft) throw new Error('INVALID_REQUEST');
    const body = {};
    if (operation === 'CREATE') {
      if (!state.handoverSource || state.sourcePreview?.contentStatus !== 'AVAILABLE' || !['UNTRACKED','CURRENT_TERMINAL'].includes(state.sourcePreview.sourceReadiness)) throw new Error('HANDOVER_SOURCE_UNAVAILABLE');
      body.sourceKind = 'CARE_ITEM'; body.careItemId = state.handoverSource.careItemId;
      const deadline = handoverUtcDeadline(draft.deadlineUtc); if (deadline) body.deadlineAt = deadline;
    }
    if (['CREATE','ASSIGN','HANDOFF'].includes(operation)) {
      const context = operation === 'CREATE' ? state.sourcePreview : state.detail;
      const selected = draft.assignedUserId ? handoverId(draft.assignedUserId) : null;
      if (draft.assignedUserId && !selected) throw new Error('HANDOVER_PERSON_REQUIRED');
      // 明确暂不指派不依赖人选目录成功；正文预览和原 B 写入授权仍各自生效。
      if ((operation !== 'CREATE' || selected) && (state.candidatePhase !== 'ready' || !sameHandoverContext(context, state.candidateContext) || state.candidateContext.operation !== operation)) throw new Error('HANDOVER_CANDIDATES_UNAVAILABLE');
      if (selected && !state.candidates.some(row => row.userId === selected) || operation !== 'CREATE' && !selected) throw new Error('HANDOVER_PERSON_REQUIRED');
      if (selected) body.assignedUserId = selected;
    }
    if ('reason' in draft) body.reason = draft.reason;
    return body;
  }
  async function refreshConflict() {
    if (state.phase !== 'conflict') return false;
    const origin = session, operation = state.operation, draft = copy(state.draft), typed = state.handoverSource, handover = state.handoverDraft;
    let result;
    if (operation === 'CREATE' && typed) {
      state.sourcePreview = null; clearEnrichment(); state.sourcePhase = 'loading';
      result = await read('handover-source', options => api.handoverSource(typed.careItemId, options), data => {
        if (data.patientId !== patient() || data.careItemId !== typed.careItemId || !isOrdinaryHandover(data)) { return projectionChanged(); }
        state.sourcePreview = data; state.sourcePhase = 'ready';
      }, () => { state.sourcePhase = 'unavailable'; });
    } else if (state.detail) result = await open(state.detail.assistanceId, true);
    else if (operation === 'CREATE' && !handover) { result = await loadList(); if (result.phase === 'succeeded') state.reviewedWinner = { existingRequestsReviewed: true }; }
    if (origin !== session || state.phase !== 'conflict' || result?.phase !== 'succeeded') return false;
    state.draft = draft; state.operation = operation; state.handoverDraft = handover;
    state.reviewedWinner = operation === 'CREATE' ? (handover ? copy(state.sourcePreview) : state.reviewedWinner) : copy(state.detail); return !!state.reviewedWinner;
  }
  function protectNavigation() { state.navigationBlocked = ['unknown','submitting'].includes(state.phase); return !state.navigationBlocked; }
  async function retryDelivery(row, acknowledged) {
    if (!state.detail || !row?.canRetry || !state.deliveries.some(item => item.id === row.id && item.retryToken === row.retryToken)) return { phase: 'unavailable' };
    if (row.requiresDuplicateRiskAcknowledgement && !acknowledged) return { phase: 'acknowledgement-required' };
    if (state.deliveryPhase === 'conflict') return { phase: 'decision-required' };
    if (state.deliveryPhase === 'submitting') return { phase: 'unavailable' };
    if (deliveryAttempt && state.deliveryPhase === 'unknown' && deliveryAttempt.id !== row.id) return { phase: 'unavailable' };
    const origin = session, context = origin.capture('delivery-retry'), selected = selectedAssistance, request = {};
    if (!deliveryAttempt || state.deliveryPhase !== 'unknown') deliveryAttempt = Object.freeze({ id: row.id, body: Object.freeze({ retryToken: row.retryToken, duplicateRiskAcknowledged: !!acknowledged }) });
    const attempt = deliveryAttempt; deliveryRequest = request; state.deliveryPhase = 'submitting';
    const owns = () => origin === session && origin.owns(context) && deliveryRequest === request && selectedAssistance === selected;
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
  return { state, captureView, ownsView, invalidate: clear, load, loadList, open, loadEvents, loadDeliveries, openDraft, submit, retry, reconcile, editAfterError, close, retryDelivery, openHandoverSource, loadHandoverContent, loadHandoverWork, loadHandoverCandidates, beginHandoverCreate, beginHandoverAction, handoverBody, refreshConflict, protectNavigation, dispose };
}
