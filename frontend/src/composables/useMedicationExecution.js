import * as Vue from 'vue';
import * as medicationApi from '@/api/medicationExecution';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
import { createHouseholdCommandSession } from '@/utils/householdCommandSession';
const copy = value => value == null ? value : JSON.parse(JSON.stringify(value));
const unwrap = response => { if (response?.code && response.code !== 200) throw Object.assign(new Error('REQUEST_REJECTED'), { status: response.code, data: response.data }); return response && Object.hasOwn(response, 'data') ? response.data : response; };
const status = error => Number(error?.response?.status ?? error?.status ?? error?.code) || 0;
/** One shared technical command session, with domain-specific drafts and current-authority projections. */
export function useMedicationExecution(patientRef, deps = {}) {
  const { reactive, unref, watch, getCurrentScope, onScopeDispose } = Vue;
  const api = deps.api || medicationApi, host = deps.events || globalThis.window;
  const patient = () => Number(unref(patientRef)) || null;
  const captureAuth = deps.captureAuth || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }));
  const isAuthCurrent = deps.isAuthCurrent || (auth => isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId'));
  const newKey = deps.newKey || (() => { if (globalThis.crypto.randomUUID) return globalThis.crypto.randomUUID(); const b = globalThis.crypto.getRandomValues(new Uint8Array(16)); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128; const h = [...b].map(n => n.toString(16).padStart(2, '0')).join(''); return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`; });
  const initial = () => ({ capabilities: null, items: [], nextCursor: null, sources: [], sourceCursor: null, detail: null, replacementPreview: null, intakes: [], intakeCursor: null, factIntake: null, personalMedication: null, events: [], eventCursor: null, deliveries: [], deliveryCursor: null, draft: null, operation: null, phase: 'idle', error: null, loading: false, dialog: false, reviewedWinner: null, deliveryPhase: 'idle' });
  const state = reactive(initial()); let session = create(), disposed = false, deliveryAttempt = null, loadingRequest = null; const controllers = new Set();
  function create() { return createHouseholdCommandSession({ patient, captureAuth, isAuthCurrent, events: host, newKey }); }
  function clear() { session.dispose(); for (const c of controllers) c.abort(); controllers.clear(); deliveryAttempt = null; loadingRequest = null; Object.assign(state, initial()); if (!disposed) session = create(); }
  function fail(error) { if ([401,403].includes(status(error))) { clear(); state.phase = 'forbidden'; state.error = 'ACCESS_DENIED'; } else state.error = error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'REQUEST_FAILED'; }
  async function read(lane, fetch, apply) { if (disposed || !patient()) return { phase: 'unavailable' }; const origin = session, context = origin.capture(lane), controller = new AbortController(); controllers.add(controller); const options = { expectedAuth: context.auth, signal: controller.signal, patientId: patient() }; try { const data = unwrap(await fetch(options)); if (origin !== session || !origin.owns(context) || controller.signal.aborted) return { phase: 'discarded' }; apply(data); return { phase: 'succeeded' }; } catch (error) { if (origin !== session || !origin.owns(context) || controller.signal.aborted) return { phase: 'discarded' }; fail(error); return { phase: state.phase === 'forbidden' ? 'forbidden' : 'unavailable' }; } finally { controllers.delete(controller); } }
  async function load() {
    const origin = session, request = {}; loadingRequest = request; state.loading = true;
    try {
      const result = await read('capabilities', options => api.capabilities(patient(), options), data => { state.capabilities = data; });
      if (origin === session && loadingRequest === request && result.phase === 'succeeded' && state.capabilities?.read) await loadList();
      return result;
    } finally {
      // View ownership may be discarded without replacing this request's busy-indicator ownership.
      if (origin === session && loadingRequest === request) { state.loading = false; loadingRequest = null; }
    }
  }
  function loadList(more = false) { const cursor = more ? state.nextCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('list', options => api.list({ patientId: patient(), limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { state.items = cursor ? [...state.items, ...page.items] : page.items; state.nextCursor = page.nextCursor; }); }
  function loadCatalogue(kind, more) {
    const medicines = kind === 'medications', key = medicines ? 'medications' : 'eligibleDoctors', cursorKey = medicines ? 'medicationCursor' : 'doctorCursor';
    if (!state.capabilities) return Promise.resolve({ phase: 'unavailable' });
    const cursor = more ? state.capabilities[cursorKey] : null;
    if (more && !cursor) return Promise.resolve({ phase: 'unavailable' });
    return read(kind, options => api[kind]({ patientId: patient(), limit: 30, ...(cursor ? { cursor } : {}) }, options), page => {
      if (!state.capabilities) return;
      state.capabilities[key] = cursor ? [...state.capabilities[key], ...page.items] : page.items;
      state.capabilities[cursorKey] = page.nextCursor;
    });
  }
  const loadMedications = (more = false) => loadCatalogue('medications', more);
  const loadClinicians = (more = false) => loadCatalogue('clinicians', more);
  function loadSources(more = false) { const cursor = more ? state.sourceCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('sources', options => api.sources({ patientId: patient(), limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { state.sources = cursor ? [...state.sources, ...page.items] : page.items; state.sourceCursor = page.nextCursor; }); }
  async function open(id, preserveDraft = false) { if (!Number.isSafeInteger(Number(id)) || Number(id) < 1) return { phase: 'unavailable' }; if (!preserveDraft) { session.close(); state.draft = null; state.dialog = false; } state.detail = null; state.replacementPreview = null; state.intakes = []; state.intakeCursor = null; if(!preserveDraft){state.factIntake = null;state.personalMedication = null;} state.events = []; state.deliveries = []; state.eventCursor = null; state.deliveryCursor = null; deliveryAttempt = null;
    return read('detail', options => api.detail(Number(id), options), data => { if (Number(data?.patientId) !== patient()) { clear(); state.error = 'PATIENT_MISMATCH'; return; } state.detail = data; }); }
  function loadReplacementPreview() { if (!state.detail || !state.detail.allowedCommands?.includes('SCHEDULE_REPLACEMENT')) return Promise.resolve({ phase: 'unavailable' }); const id=state.detail.executionId;state.replacementPreview=null;return read('replacement-preview', options=>api.replacementPreview(id,options), value=>{if(state.detail?.executionId===id && Number(value.executionVersion)===Number(state.detail.version))state.replacementPreview=value;}); }
  function loadIntakes(more=false){if(!state.detail)return Promise.resolve({phase:'unavailable'});const id=state.detail.executionId,cursor=more?state.intakeCursor:null;if(more&&!cursor)return Promise.resolve({phase:'unavailable'});return read('intakes',options=>api.intakes(id,{limit:30,...(cursor?{cursor}:{})},options),page=>{if(state.detail?.executionId!==id)return;state.intakes=cursor?[...state.intakes,...page.items]:page.items;state.intakeCursor=page.nextCursor;});}
  async function loadPersonalIntakes(medicationId,more=false){if(['unknown','submitting'].includes(state.phase))return {phase:'unavailable'};const cursor=more?state.intakeCursor:null;if(more&&!cursor)return {phase:'unavailable'};if(!more){session.close();state.detail=null;state.factIntake=null;state.intakes=[];state.personalMedication=Number(medicationId);}const origin=session;let winner=null;const result=await read('personal-intakes',options=>api.personalIntakes({patientId:patient(),medicationId:Number(medicationId),limit:30,...(cursor?{cursor}:{})},options),page=>{winner=page.executionId;state.intakes=cursor?[...state.intakes,...page.items]:page.items;state.intakeCursor=page.nextCursor;});if(origin===session&&result.phase==='succeeded'&&winner){await open(winner);if(origin===session)await loadIntakes();}return result;}
  function useFactWinner(){if(!state.factIntake||!state.reviewedWinner||state.detail?.executionId!==state.reviewedWinner)return false;const current=state.intakes.find(row=>row.intakeId===state.factIntake.intakeId);if(!current||!current.allowedCommands?.includes(state.operation)||state.operation==='CORRECT_INTAKE'&&current.latestActionId!==state.draft.originalActionId){state.error='ACTION_CHANGED';return false;}if(state.draft.countId&&(state.draft.countId!==current.currentCount?.id||state.draft.countVersion!==current.currentCount?.version)){state.error='COUNT_CHANGED';return false;}session.reset();state.factIntake=copy(current);delete state.draft.medicationId;state.personalMedication=null;state.phase='idle';state.error=null;state.dialog=true;return true;}
  function openFact(operation,row){if(!row.allowedCommands?.includes(operation))return false;const draft={intakeId:row.intakeId};if(!state.detail&&state.personalMedication)draft.medicationId=state.personalMedication;if(['RECORD_TAKEN','RECORD_SKIPPED','CORRECT_INTAKE'].includes(operation)){Object.assign(draft,{status:operation==='CORRECT_INTAKE'?'':operation==='RECORD_TAKEN'?'TAKEN':'SKIPPED',actualAt:'',attribution:'',amountBasis:operation==='RECORD_SKIPPED'?'NOT_APPLICABLE':''});if(operation==='CORRECT_INTAKE')draft.originalActionId=row.latestActionId;}if(['CORRECT_INTAKE','RECORD_CONTACT','END_INTAKE_UNRESOLVED','REOPEN_INTAKE_FOLLOW_UP'].includes(operation))draft.reason='';if(operation==='END_INTAKE_UNRESOLVED')draft.reasonCode='';if(!openDraft(operation,draft))return false;state.factIntake=copy(row);return true;}
  function loadEvents(more = false) { if (!state.detail) return Promise.resolve({ phase: 'unavailable' }); const id = state.detail.executionId, cursor = more ? state.eventCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('events', options => api.events(id, { limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { if (state.detail?.executionId !== id) return; state.events = cursor ? [...state.events, ...page.items] : page.items; state.eventCursor = page.nextCursor; }); }
  function loadDeliveries(more = false) { if (!state.detail) return Promise.resolve({ phase: 'unavailable' }); const id = state.detail.executionId, cursor = more ? state.deliveryCursor : null; if (more && !cursor) return Promise.resolve({ phase: 'unavailable' }); return read('deliveries', options => api.deliveryStatus(id, { limit: 30, ...(cursor ? { cursor } : {}) }, options), page => { if (state.detail?.executionId !== id) return; state.deliveries = cursor ? [...state.deliveries, ...page.items] : page.items; state.deliveryCursor = page.nextCursor; if (state.deliveryPhase === 'conflict' || state.deliveryPhase === 'invalid') state.deliveryPhase = 'idle'; }); }
  function openDraft(operation, draft = {}) { if (['unknown','submitting'].includes(state.phase) || !state.capabilities?.create && operation === 'CREATE_RECONCILIATION') return false; try { session.reset(); } catch { return false; } state.factIntake = null; state.operation = operation; state.draft = copy(draft); state.dialog = true; state.phase = 'idle'; state.error = null; return true; }
  async function outcome(origin, result) {
    if (origin !== session || disposed) return { phase: 'discarded' };
    if (result.phase === 'discarded') {
      // Private settlement may release busy/recoverability, but never adopts a closed view's result.
      if (result.settledPhase) { state.phase = result.settledPhase; state.error = result.errorCode || null; if (result.settledPhase === 'succeeded') origin.reset(); }
      return result;
    }
    state.phase = result.phase; state.error = result.errorCode || null;
    if (result.phase === 'forbidden') { clear(); state.phase = 'forbidden'; state.error = 'ACCESS_DENIED'; }
    else if (result.phase === 'succeeded') { state.draft = null; state.dialog = false; origin.reset(); if (result.result?.executionId) { await open(result.result.executionId); if(origin===session && api.intakes) await loadIntakes(); } if (origin === session) await loadList(); }
    return result;
  }
  const send = envelope => api.command(envelope, { expectedAuth: captureAuth() }).then(unwrap);
  async function submit() {
    if (!state.draft || !state.operation || ['submitting','unknown','invalid','conflict'].includes(state.phase)) return { phase: 'unavailable' };
    const origin = session, operation=!state.detail && state.factIntake && state.personalMedication?'PERSONAL_STOCK_FACT':state.operation, target = ['CREATE_RECONCILIATION','PERSONAL_STOCK_FACT'].includes(operation) ? null : state.detail?.executionId;
    if (target === undefined) return { phase: 'unavailable' };
    try { origin.begin(operation, target, { patientId: patient(), expectedVersion: target === null ? 0 : state.detail.version }, state.draft); state.phase = 'submitting'; return await outcome(origin, await origin.submit(send)); } catch { return { phase: origin === session ? 'unavailable' : 'discarded' }; }
  }
  async function retry() { const origin = session, previous = state.phase; try { state.phase = 'submitting'; return await outcome(origin, await origin.retryIdentical(send)); } catch { if (origin === session) state.phase = previous; return { phase: origin === session ? 'unavailable' : 'discarded' }; } }
  async function reconcile() { const origin = session; try { return await outcome(origin, await origin.reconcile(envelope => api.receipt(envelope.patientId, envelope.commandKey, { expectedAuth: captureAuth() }).then(unwrap))); } catch { return { phase: origin === session ? 'unavailable' : 'discarded' }; } }
  function editAfterError() { if (!['invalid','conflict'].includes(state.phase)) return false; if (state.error === 'EXECUTION_EXISTS' && state.draft) return false; session.reset(); state.phase = 'idle'; state.error = null; return true; }
  async function reviewWinner() {
    if (state.error !== 'EXECUTION_EXISTS' || !state.draft?.medicationId) return { phase: 'unavailable' };
    const origin = session, medicine = state.draft.medicationId; let winner = null;
    const selected = await read('selected-medication', options => api.medication(medicine, options), item => { winner = item.executionId; });
    if (origin !== session || selected.phase !== 'succeeded' || !winner) return { phase: selected.phase === 'discarded' ? 'discarded' : 'unavailable' };
    const result = await open(winner, true);
    if (origin === session && result.phase === 'succeeded' && state.detail) { state.reviewedWinner = winner; if(state.factIntake && api.intakes) await loadIntakes(); }
    return result;
  }
  function newReconciliation() { if (!state.reviewedWinner || state.detail?.executionId !== state.reviewedWinner || !state.detail.allowedCommands?.includes('NEW_RECONCILIATION')) return false; session.reset(); state.operation = 'NEW_RECONCILIATION'; state.phase = 'idle'; state.error = null; state.dialog = true; return true; }
  function close() { session.close(); state.dialog = false; state.draft = null; state.operation = null; state.detail = null; state.replacementPreview = null; state.intakes = []; state.intakeCursor = null; state.factIntake = null; state.personalMedication = null; state.events = []; state.deliveries = []; state.reviewedWinner = null; }
  async function retryDelivery(row, acknowledged) { if (!state.detail || !row?.canRetry || !state.deliveries.some(item => item.id === row.id && item.retryToken === row.retryToken)) return { phase: 'unavailable' }; if (row.requiresDuplicateRiskAcknowledgement && !acknowledged) return { phase: 'acknowledgement-required' }; if (state.deliveryPhase === 'conflict') return { phase: 'decision-required' }; if (state.deliveryPhase === 'submitting') return { phase: 'unavailable' }; if (deliveryAttempt && state.deliveryPhase === 'unknown' && deliveryAttempt.id !== row.id) return { phase: 'unavailable' }; const origin = session, context = origin.capture('delivery-retry'); if (!deliveryAttempt || state.deliveryPhase !== 'unknown') deliveryAttempt = Object.freeze({ id: row.id, body: Object.freeze({ retryToken: row.retryToken, duplicateRiskAcknowledged: !!acknowledged }) }); const attempt = deliveryAttempt; state.deliveryPhase = 'submitting'; try { await api.retryDelivery(attempt.id, attempt.body, { expectedAuth: context.auth }); if (origin !== session || !origin.owns(context)) return { phase: 'discarded' }; state.deliveryPhase = 'succeeded'; deliveryAttempt = null; await loadDeliveries(); return { phase: 'succeeded' }; } catch (error) { if (origin !== session || !origin.owns(context)) return { phase: 'discarded' }; if ([401,403].includes(status(error))) { fail(error); return { phase: 'forbidden' }; } state.deliveryPhase = status(error) === 409 ? 'conflict' : status(error) === 400 ? 'invalid' : 'unknown'; if (state.deliveryPhase !== 'unknown') deliveryAttempt = null; return { phase: state.deliveryPhase }; } }
  const onIdentity = () => clear(); for (const event of ['auth-session-cleared','auth-session-changed','storage']) host?.addEventListener?.(event, onIdentity);
  const stop = watch(patientRef, clear, { flush: 'sync' });
  function dispose() { disposed = true; stop(); clear(); for (const event of ['auth-session-cleared','auth-session-changed','storage']) host?.removeEventListener?.(event, onIdentity); }
  if (getCurrentScope()) onScopeDispose(dispose);
  return { state, invalidate: clear, load, loadList, loadMedications, loadClinicians, loadSources, open, loadReplacementPreview, loadIntakes, loadPersonalIntakes, useFactWinner, openFact, loadEvents, loadDeliveries, openDraft, submit, retry, reconcile, editAfterError, reviewWinner, newReconciliation, close, retryDelivery, dispose };
}
