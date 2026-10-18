import * as Vue from 'vue'
import * as questionApi from '@/api/visitQuestion'
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'

const copy = value => value == null ? value : JSON.parse(JSON.stringify(value))
const unwrap = response => {
  if (response?.code && response.code !== 200) throw Object.assign(new Error('QUESTION_REQUEST_REJECTED'), { code: response.code, data: response.data })
  return response && Object.prototype.hasOwnProperty.call(response, 'data') ? response.data : response
}
const status = error => Number(error?.response?.status || error?.code) || 0
const code = error => error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'REQUEST_FAILED'
const id = value => /^[1-9][0-9]*$/.test(String(value)) && Number.isSafeInteger(Number(value)) ? Number(value) : null
const freeze = value => { if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value) } return value }
const dateValid = value => { if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '') || Number(value.slice(0, 4)) < 1000) return false; const date = new Date(`${value}T00:00:00Z`); return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value }
export const questionReasons = {
  MARK_UNRESOLVED: ['NOT_ASKED', 'NOT_ANSWERED', 'MORE_INFORMATION_NEEDED', 'VISIT_CANCELLED', 'OTHER'],
  CLOSE: ['ANSWER_SUFFICIENT', 'FOLLOW_UP_FINISHED', 'NO_LONGER_NEEDED', 'DUPLICATE', 'WITHDRAWN', 'OTHER'],
  REOPEN: ['NEW_INFORMATION', 'ANSWER_UNCLEAR', 'FOLLOW_UP_NOT_DONE', 'CONTEXT_CHANGED', 'OTHER']
}
export function validateQuestionDraft(operation, draft, detail) {
  const errors = {}, require = key => { if (!String(draft[key] || '').trim()) errors[key] = 'required' }
  const bounded = (key, max, old) => { if (draft[key] !== old && [...String(draft[key] || '')].length > max) errors[key] = 'tooLong' }
  if (['CREATE', 'ADOPT', 'EDIT'].includes(operation)) {
    require('title'); bounded('title', 200, operation === 'CREATE' ? undefined : detail?.title); bounded('description', 4000, operation === 'CREATE' ? undefined : detail?.description)
    if (operation === 'ADOPT' && !draft.adoptionAccepted) errors.adoptionAccepted = 'required'
  }
  if (operation.endsWith('ANSWER')) { require('answer'); bounded('answer', 4000); bounded('reportedClinician', 200); if (draft.reportedDate && !dateValid(draft.reportedDate)) errors.reportedDate = 'invalidDate' }
  if (operation === 'SET_FOLLOW_UP') {
    require('followUp'); bounded('followUp', 2000)
    if (!dateValid(draft.dueDate)) errors.dueDate = 'invalidDate'
    if (draft.ownerMode !== 'keep') { require('reasonNote'); if (!draft.reasonNote?.trim()) errors.reasonNote = 'reassignReason' }
    if (draft.ownerMode === 'account' && !id(draft.responsibleInput)) errors.responsibleInput = 'invalidId'
  }
  if (questionReasons[operation]) {
    if (!questionReasons[operation].includes(draft.reason)) errors.reason = 'required'
    if (draft.reason === 'OTHER') require('reasonNote')
  }
  bounded('reasonNote', 1000); bounded('resultText', 4000)
  if (operation === 'CLOSE' && draft.reason === 'FOLLOW_UP_FINISHED') {
    if (draft.useReference) { if (!['QUESTION_EVENT', 'LEGACY_APPOINTMENT', 'CARE_APPOINTMENT', 'VISIT_RECORD'].includes(draft.referenceType)) errors.referenceType = 'required'; if (!id(draft.referenceId)) errors.referenceId = 'invalidId' }
    else require('resultText')
  }
  if (operation === 'REOPEN' && !['OPEN', 'UNRESOLVED'].includes(draft.state)) errors.state = 'required'
  return errors
}

/** 所有正文、草稿和待核对命令只保存在当前授权会话内，切换身份或患者立即清除。 */
export function useVisitQuestion(patientRef, deps = {}) {
  const { reactive, unref, watch, onScopeDispose, getCurrentScope } = Vue
  const api = deps.api || questionApi, host = deps.events || globalThis.window
  const capture = deps.capture || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }))
  const isCurrent = deps.isCurrent || (s => isAuthSessionCurrent(s) && s.actorId === localStorage.getItem('userId'))
  const newKey = deps.newKey || (() => globalThis.crypto.randomUUID())
  const patient = () => id(unref(patientRef)), page = () => ({ items: [], nextCursor: null, hasMore: false, phase: 'idle' })
  const initial = () => ({ patientId: patient(), capabilities: null, availability: 'idle', active: page(), closed: page(), legacy: page(), detail: null, detailPhase: 'idle', events: [], eventCursor: null, eventMore: false, eventPhase: 'idle', baseline: null, selectedContext: null, contextCandidate: null, contextOptions: page(), contextType: 'CARE_APPOINTMENT', contextPhase: 'idle', dialog: null, commandPhase: 'idle', pending: null, receipt: null, error: null, errors: {}, decisionReady: false, reviewReady: false, retryAllowed: false, navigationBlocked: false })
  const state = reactive(initial())
  let epoch = 0, detailEpoch = 0, disposed = false, session = capture(), attempt = null
  const versions = new Map(), controllers = new Map()
  function clear() {
    epoch++; detailEpoch++
    controllers.forEach(c => c.abort()); controllers.clear(); versions.clear(); attempt = null
    Object.assign(state, initial())
  }
  function authChanged() { clear(); session = capture() }
  function sync() { if (disposed) return false; if (!isCurrent(session)) authChanged(); return !!session?.token && !!patient() }
  function request(lane) {
    if (!sync()) return null
    controllers.get(lane)?.abort(); versions.set(lane, (versions.get(lane) || 0) + 1)
    const controller = new AbortController(), auth = capture()
    controllers.set(lane, controller)
    return { epoch, detailEpoch, lane, version: versions.get(lane), patient: patient(), auth, options: { signal: controller.signal, expectedAuth: auth } }
  }
  function current(c, detail = false) {
    if (!c || disposed) return false
    if (!isCurrent(c.auth)) { if (!isCurrent(session)) authChanged(); return false }
    return c.epoch === epoch && c.patient === patient() && c.version === versions.get(c.lane) && !c.options.signal.aborted && (!detail || c.detailEpoch === detailEpoch)
  }
  function failure(error, c) {
    if (!current(c)) return 'stale'
    if ([401, 403].includes(status(error))) { clear(); state.availability = 'denied'; state.commandPhase = 'denied'; return 'denied' }
    state.error = code(error)
    return 'unavailable'
  }
  async function loadCapabilities() {
    const c = request('capabilities'); if (!c) return { status: 'stale' }
    state.availability = 'loading'; state.capabilities = null
    try {
      const data = unwrap(await api.capabilities(c.patient, c.options)); if (!current(c)) return { status: 'stale' }
      if (id(data?.patientId) !== c.patient || id(data?.actorId) !== id(c.auth.actorId)) throw new Error('QUESTION_IDENTITY_INVALID')
      state.capabilities = data; state.availability = ['READY', 'DISABLED', 'ABSENT', 'INCOMPATIBLE'].includes(data.availability) ? data.availability : 'INCOMPATIBLE'
      return { status: 'loaded' }
    } catch (error) { const result = failure(error, c); if (result === 'unavailable') state.availability = 'unavailable'; return { status: result } }
  }
  async function loadPage(lane, next = false) {
    if (!['active', 'closed', 'legacy'].includes(lane)) return { status: 'invalid' }
    const c = request(`page:${lane}`); if (!c) return { status: 'stale' }
    const old = state[lane], filters = { limit: 25 }
    if (next && old.nextCursor) filters.cursor = old.nextCursor
    if (lane !== 'legacy') {
      if (state.availability !== 'READY') return { status: 'unavailable' }
      filters.state = lane === 'closed' ? 'CLOSED' : 'ACTIVE'
      if (state.selectedContext) Object.assign(filters, { contextType: state.selectedContext.type, contextId: state.selectedContext.id })
    } else if (!['READY', 'DISABLED', 'ABSENT'].includes(state.availability)) return { status: 'unavailable' }
    state[lane] = { ...page(), phase: 'loading' }
    try {
      const data = unwrap(await (lane === 'legacy' ? api.legacy : api.list)(c.patient, filters, c.options)); if (!current(c)) return { status: 'stale' }
      if (!Array.isArray(data?.items) || data.items.some(row => id(row.patientId) !== c.patient)) throw new Error('QUESTION_IDENTITY_INVALID')
      state[lane] = { items: data.items, nextCursor: data.nextCursor, hasMore: !!data.hasMore, phase: 'loaded' }; return { status: 'loaded' }
    } catch (error) { const result = failure(error, c); if (result === 'unavailable') state[lane].phase = result; return { status: result } }
  }
  async function load() { const c = await loadCapabilities(); if (c.status !== 'loaded') return c; await Promise.all(['active', 'closed', 'legacy'].map(lane => loadPage(lane))); return { status: 'loaded' } }
  function locked() { return ['saving', 'checking', 'uncertain'].includes(state.commandPhase) }
  function dirty() { return !!state.dialog && (JSON.stringify(state.dialog.draft) !== state.dialog.original || locked()) }
  function protectNavigation() { if (!dirty()) return true; state.navigationBlocked = true; return false }
  function closeDialog(discard = false) {
    if (locked()) { state.navigationBlocked = true; return false }
    if (dirty() && !discard) { state.navigationBlocked = true; return false }
    state.dialog = null; state.errors = {}; state.navigationBlocked = false; state.commandPhase = 'idle'; state.pending = null; attempt = null; return true
  }
  async function open(questionId, legacy = false) {
    if (!id(questionId) || !protectNavigation()) return { status: 'blocked' }
    detailEpoch++; state.detail = null; state.events = []; state.baseline = null; state.dialog = null; state.contextCandidate = null; state.receipt = null; state.error = null
    const c = request('detail'); if (!c) return { status: 'stale' }
    state.detailPhase = 'loading'
    try {
      const data = unwrap(await (legacy ? api.legacyDetail : api.detail)(c.patient, id(questionId), c.options)); if (!current(c, true)) return { status: 'stale' }
      if (id(data?.patientId) !== c.patient || id(data?.questionId) !== id(questionId)) throw new Error('QUESTION_IDENTITY_INVALID')
      state.detail = data; state.detailPhase = 'loaded'; return { status: 'loaded' }
    } catch (error) { const result = failure(error, c); if (result === 'unavailable') state.detailPhase = result; return { status: result } }
  }
  function closeDetail() {
    if (!protectNavigation()) return false
    detailEpoch++
    for(const lane of ['detail','events','baseline','linkedContext','decision']) { controllers.get(lane)?.abort(); controllers.delete(lane); versions.set(lane,(versions.get(lane)||0)+1) }
    state.detail=null; state.events=[]; state.baseline=null; state.contextCandidate=null; state.detailPhase='idle'; state.eventPhase='idle'; state.eventCursor=null; state.eventMore=false
    return true
  }
  async function loadEvents(next = false) {
    if (!state.detail?.managed) return { status: 'invalid' }
    const c = request('events'), question = state.detail.questionId; if (!c) return { status: 'stale' }
    const filters = { limit: 25 }; if (next && state.eventCursor) filters.cursor = state.eventCursor
    state.eventPhase = 'loading'; state.events = []; state.baseline = null
    try { const data = unwrap(await api.events(c.patient, question, filters, c.options)); if (!current(c, true)) return { status: 'stale' }; state.events = data.items; state.eventCursor = data.nextCursor; state.eventMore = !!data.hasMore; state.eventPhase = 'loaded'; return { status: 'loaded' } }
    catch (error) { const result = failure(error, c); if (result === 'unavailable') state.eventPhase = result; return { status: result } }
  }
  async function loadBaseline(eventId) {
    if (!state.detail?.managed || !id(eventId)) return { status: 'invalid' }
    const c = request('baseline'), question = state.detail.questionId; if (!c) return { status: 'stale' }
    state.baseline = null
    try { const data = unwrap(await api.event(c.patient, question, id(eventId), c.options)); if (!current(c, true)) return { status: 'stale' }; if (id(data?.patientId) !== c.patient || id(data?.questionId) !== question || id(data?.eventId) !== id(eventId)) throw new Error('QUESTION_IDENTITY_INVALID'); state.baseline = data; return { status: 'loaded' } }
    catch (error) { return { status: failure(error, c) } }
  }
  async function loadContexts(type, next = false) {
    if (!questionApi.contextTypes.includes(type) || state.availability !== 'READY') return { status: 'invalid' }
    const c = request('contexts'); if (!c) return { status: 'stale' }
    const filters = { limit: 25 }; if (next && type === state.contextType && state.contextOptions.nextCursor) filters.cursor = state.contextOptions.nextCursor
    state.contextType = type; state.contextOptions = { ...page(), phase: 'loading' }
    try { const data = unwrap(await api.contexts(c.patient, type, filters, c.options)); if (!current(c)) return { status: 'stale' }; state.contextOptions = { ...data, phase: 'loaded' }; return { status: 'loaded' } }
    catch (error) { const result = failure(error, c); if (result === 'unavailable') state.contextOptions.phase = result; return { status: result } }
  }
  async function selectContext(type, contextId) {
    // 切换就诊是敏感上下文切换，不把旧草稿带入新就诊。
    clear(); session = capture()
    const selectedEpoch = epoch; const loaded = await loadCapabilities(); if (selectedEpoch !== epoch || loaded.status !== 'loaded') return { status: 'stale' }; if (state.availability !== 'READY') return { status: 'unavailable' }
    if (type == null) { await Promise.all(['active', 'closed', 'legacy'].map(lane => loadPage(lane))); return { status: 'loaded' } }
    const c = request('context'); if (!c || !questionApi.contextTypes.includes(type) || !id(contextId)) return { status: 'invalid' }
    state.contextPhase = 'loading'
    try {
      const data = unwrap(await api.context(c.patient, type, id(contextId), c.options)); if (!current(c)) return { status: 'stale' }
      if (data?.type !== type || id(data?.id) !== id(contextId) || !/^[0-9a-f]{64}$/.test(data?.fingerprint || '')) throw new Error('QUESTION_CONTEXT_CHANGED')
      state.selectedContext = data; state.contextPhase = 'loaded'; await Promise.all(['active', 'closed', 'legacy'].map(lane => loadPage(lane))); return { status: 'loaded' }
    } catch (error) { const result = failure(error, c); if (result === 'unavailable') state.contextPhase = result; return { status: result } }
  }
  async function inspectLinkedContext() {
    const d = state.detail; if (!d?.contextType) return { status: 'invalid' }
    const c = request('linkedContext'); if (!c) return { status: 'stale' }
    state.contextCandidate = null
    try { const data = unwrap(await api.context(c.patient, d.contextType, d.contextId, c.options)); if (!current(c, true)) return { status: 'stale' }; if (data.type !== d.contextType || id(data.id) !== id(d.contextId)) throw new Error('QUESTION_CONTEXT_CHANGED'); state.contextCandidate = data; return { status: 'loaded' } }
    catch (error) { return { status: failure(error, c) } }
  }
  function allowed(operation, d = state.detail) {
    if (!sync() || state.availability !== 'READY' || !state.capabilities?.write) return false
    if (operation === 'CREATE') return true
    if (operation === 'ADOPT') return !!d && !d.managed
    return !!d?.managed && d.allowedCommands?.includes(operation) && (operation !== 'PUBLISH_ANSWER' || state.capabilities.publish === true)
  }
  function begin(operation, overrides = {}) {
    if (locked() || !allowed(operation) || !protectNavigation()) return false
    const d = operation === 'CREATE' ? null : state.detail
    const draft = { title: d?.title || '', description: d?.description || '', answer: '', reportedClinician: '', reportedDate: '', keepFollowUp: false, followUp: d?.followUp || '', dueDate: d?.followUpDueDate || '', ownerMode: 'keep', responsibleInput: '', reason: '', reasonNote: '', resultText: '', useReference: false, referenceType: 'VISIT_RECORD', referenceId: '', state: 'OPEN', contextMode: state.selectedContext && operation !== 'EDIT' ? 'selected' : 'keep', adoptionAccepted: false, legacyAppointmentId: d?.legacyAppointmentId ?? null, ...overrides }
    state.dialog = { operation, questionId: d?.questionId || null, version: d?.version || 0, targetContext: d?.contextType ? { type: d.contextType, id: d.contextId } : null, draft, original: JSON.stringify(draft) }
    state.commandPhase = 'idle'; state.error = null; state.errors = {}; state.decisionReady = false; state.reviewReady = false; state.pending = null; state.receipt = null; attempt = null; return true
  }
  function bodyFor(dialog) {
    const { operation: op, draft: d } = dialog, body = {}, old = state.detail
    if (['CREATE', 'ADOPT', 'EDIT'].includes(op)) {
      for (const field of ['title', 'description']) if (op === 'CREATE' || d[field] !== old?.[field]) body[field] = d[field]
      if (op === 'EDIT' && d.legacyAppointmentId !== (old?.legacyAppointmentId ?? null)) body.legacyAppointmentId = d.legacyAppointmentId == null || d.legacyAppointmentId === '' ? null : id(d.legacyAppointmentId)
      if (d.contextMode === 'none') body.context = null
      if (['selected', 'confirm'].includes(d.contextMode)) { const context = d.contextMode === 'confirm' ? state.contextCandidate : state.selectedContext; if (!context) { state.errors.contextMode = 'selectRequired'; return null } body.context = { type: context.type, id: context.id, fingerprint: context.fingerprint } }
    }
    if (op.endsWith('ANSWER')) { body.answer = d.answer; if (old?.state === 'FOLLOW_UP') body.keepFollowUp = !!d.keepFollowUp; if (op === 'RECORD_ANSWER') { if (d.reportedClinician) body.reportedClinician = d.reportedClinician; if (d.reportedDate) body.reportedDate = d.reportedDate } }
    if (op === 'SET_FOLLOW_UP') {
      body.followUp = d.followUp; body.dueDate = d.dueDate
      if (d.ownerMode !== 'keep') { body.responsibleUserId = d.ownerMode === 'me' ? id(state.capabilities.actorId) : d.ownerMode === 'unassigned' ? null : id(d.responsibleInput); body.reasonNote = d.reasonNote }
    }
    if (questionReasons[op]) { body.reason = d.reason; if (d.reasonNote) body.reasonNote = d.reasonNote }
    if (op === 'CLOSE' && d.reason === 'FOLLOW_UP_FINISHED') { if (d.useReference) body.resultReference = { type: d.referenceType, id: id(d.referenceId) }; else body.resultText = d.resultText }
    if (op === 'REOPEN') body.state = d.state
    return body
  }
  async function preflight(c, frozen) {
    const caps = unwrap(await api.capabilities(c.patient, c.options)); if (!current(c, true)) return 'stale'
    if (id(caps?.actorId) !== id(c.auth.actorId) || id(caps?.patientId) !== c.patient) return 'denied'
    state.capabilities = caps; state.availability = caps.availability
    if (caps.availability !== 'READY' || !caps.write || frozen.operation === 'PUBLISH_ANSWER' && !caps.publish) return 'blocked'
    if (frozen.questionId != null) {
      const d = unwrap(await (frozen.operation === 'ADOPT' ? api.legacyDetail : api.detail)(c.patient, frozen.questionId, c.options)); if (!current(c, true)) return 'stale'
      if (id(d?.patientId) !== c.patient || id(d?.questionId) !== frozen.questionId) return 'denied'
      if (Number(d.version || 0) !== frozen.expectedVersion || !allowed(frozen.operation, d)) return 'conflict'
    }
    if (frozen.body.context) { const x = frozen.body.context; const actual = unwrap(await api.context(c.patient, x.type, x.id, c.options)); if (!current(c, true)) return 'stale'; if (actual?.fingerprint !== x.fingerprint) return 'context-changed' }
    return 'ready'
  }
  function validReceipt(value, frozen) { return value && id(value.questionId) && (frozen.questionId == null || id(value.questionId) === frozen.questionId) && id(value.eventId) && value.version === frozen.expectedVersion + 1 && ['OPEN', 'UNRESOLVED', 'ANSWERED', 'FOLLOW_UP', 'CLOSED'].includes(value.state) }
  async function complete(receipt, c) {
    if (!current(c, true)) return { status: 'stale' }
    state.receipt = receipt; state.commandPhase = 'saved'; state.pending = null; attempt = null; state.dialog = null; state.navigationBlocked = false; state.errors = {}
    const keep = receipt; await open(receipt.questionId); if (current(c)) { state.receipt = keep; state.commandPhase = 'saved'; await Promise.all(['active', 'closed', 'legacy'].map(lane => loadPage(lane))) }
    return { status: 'saved' }
  }
  async function submit() {
    if (locked()) return { status: state.commandPhase === 'uncertain' ? 'reconciliation-required' : 'busy' }
    if (!state.dialog || ['conflict', 'rejected'].includes(state.commandPhase)) return { status: 'decision-required' }
    if (!allowed(state.dialog.operation)) return { status: 'denied' }
    state.errors = validateQuestionDraft(state.dialog.operation, state.dialog.draft, state.detail)
    if (Object.keys(state.errors).length) return { status: 'invalid' }
    const body = bodyFor(state.dialog); if (!body) return { status: 'invalid' }
    const envelope = freeze(copy({ schemaVersion: 1, patientId: patient(), questionId: state.dialog.questionId, expectedVersion: state.dialog.version, operation: state.dialog.operation, body }))
    const c = request('command'); if (!c) return { status: 'stale' }
    attempt = { envelope, key: newKey(), context: c }; state.pending = freeze({ envelope, key: attempt.key }); state.commandPhase = 'saving'; state.error = null
    let sent = false
    try {
      const ready = await preflight(c, envelope); if (!current(c, true)) return { status: 'stale' }
      if (ready !== 'ready') { if (ready === 'denied') { clear(); state.availability = 'denied' } else { state.commandPhase = 'conflict'; state.error = ready === 'context-changed' ? 'QUESTION_CONTEXT_CHANGED' : 'VERSION_CONFLICT' } return { status: ready } }
      sent = true
      const result = unwrap(await api.command(envelope, attempt.key, c.options)); if (!current(c, true)) return { status: 'stale' }
      if (!validReceipt(result, envelope)) { state.commandPhase = 'uncertain'; state.error = 'QUESTION_RECEIPT_INVALID'; return { status: 'uncertain' } }
      return await complete(result, c)
    } catch (error) {
      const failed = failure(error, c); if (failed !== 'unavailable') return { status: failed }
      const definitive = !error?.questionOutcomeUnknown && status(error) >= 400 && status(error) < 500 && ![408, 429].includes(status(error))
      state.commandPhase = sent && !error?.notDispatched && !definitive ? 'uncertain' : status(error) === 409 ? 'conflict' : 'rejected'
      return { status: state.commandPhase }
    }
  }
  async function reconcile() {
    if (!attempt || !['uncertain', 'checking'].includes(state.commandPhase)) return { status: 'invalid' }
    const pending = attempt, c = request('receipt'); if (!c) return { status: 'stale' }
    state.commandPhase = 'checking'; state.retryAllowed = false
    try {
      const result = unwrap(await api.receipt(c.patient, pending.key, c.options)); if (!current(c, true) || attempt !== pending) return { status: 'stale' }
      if (result != null) { if (!validReceipt(result, pending.envelope)) throw new Error('QUESTION_RECEIPT_INVALID'); return await complete(result, c) }
      state.commandPhase = 'uncertain'
      const ready = await preflight(c, pending.envelope); if (!current(c, true)) return { status: 'stale' }
      if (ready === 'denied') { clear(); state.availability = 'denied'; return { status: 'denied' } }
      state.retryAllowed = ready === 'ready'; return { status: 'absent' }
    } catch (error) { const failed = failure(error, c); if (failed === 'unavailable') state.commandPhase = 'uncertain'; return { status: failed } }
  }
  async function retry() {
    if (state.commandPhase !== 'uncertain' || !attempt) return { status: 'blocked' }
    const checked = await reconcile(); if (checked.status !== 'absent' || !state.retryAllowed || !attempt) return checked
    const pending = attempt, c = request('command'); if (!c) return { status: 'stale' }
    state.commandPhase = 'saving'; state.retryAllowed = false
    try {
      const result = unwrap(await api.command(pending.envelope, pending.key, c.options)); if (!current(c, true) || attempt !== pending) return { status: 'stale' }
      if (!validReceipt(result, pending.envelope)) throw new Error('QUESTION_RECEIPT_INVALID')
      return await complete(result, c)
    } catch (error) {
      const failed = failure(error, c); if (failed !== 'unavailable') return { status: failed }
      // 同键成功会先返回原回执；只有应用阶段的明确版本冲突能证明此键没有提交。
      // 其他 409、缺回执或网络失败仍是未知结果，不能换键重发。
      state.commandPhase = !error?.questionOutcomeUnknown && status(error) === 409 && code(error) === 'VERSION_CONFLICT' ? 'conflict' : 'uncertain'
      state.decisionReady = false; state.reviewReady = false
      return { status: state.commandPhase }
    }
  }
  async function refreshDecision() {
    if (!state.dialog || !['conflict', 'rejected', 'uncertain'].includes(state.commandPhase)) return { status: 'blocked' }
    const c = request('decision'), dialog = state.dialog; if (!c) return { status: 'stale' }
    state.decisionReady = false; state.reviewReady = false
    try {
      const caps = unwrap(await api.capabilities(c.patient, c.options)); if (!current(c, true)) return { status: 'stale' }
      if (id(caps?.patientId) !== c.patient || id(caps?.actorId) !== id(c.auth.actorId)) throw Object.assign(new Error('DENIED'), { code: 403 })
      state.capabilities = caps; state.availability = caps.availability
      if (dialog.questionId) { const d = unwrap(await (dialog.operation === 'ADOPT' ? api.legacyDetail : api.detail)(c.patient, dialog.questionId, c.options)); if (!current(c, true)) return { status: 'stale' }; if (id(d?.patientId) !== c.patient || id(d?.questionId) !== dialog.questionId) throw Object.assign(new Error('DENIED'), { code: 403 }); state.detail = d }
      state.reviewReady = true; state.decisionReady = state.commandPhase !== 'uncertain' && allowed(dialog.operation); return { status: 'loaded' }
    } catch (error) { return { status: failure(error, c) } }
  }
  function useDraft() { if (!state.decisionReady || !state.dialog || !allowed(state.dialog.operation) || locked()) return false; const target = state.dialog.questionId === state.detail?.questionId ? state.detail : null; state.dialog.version = target?.version || 0; state.dialog.targetContext = target?.contextType ? { type: target.contextType, id: target.contextId } : null; state.pending = null; attempt = null; state.commandPhase = 'idle'; state.decisionReady = false; state.error = null; return true }
  function storageChanged(event) { if (event.key == null || ['token', 'userId', 'username', 'realName', 'permissionSession', 'currentPatientId'].includes(event.key)) authChanged() }
  host?.addEventListener('auth-session-changed', authChanged); host?.addEventListener('auth-session-cleared', authChanged); host?.addEventListener('storage', storageChanged)
  watch(() => unref(patientRef), authChanged, { flush: 'sync' })
  if (deps.contextKey) watch(() => unref(deps.contextKey), authChanged, { flush: 'sync' })
  function dispose() { clear(); disposed = true; host?.removeEventListener('auth-session-changed', authChanged); host?.removeEventListener('auth-session-cleared', authChanged); host?.removeEventListener('storage', storageChanged) }
  if (getCurrentScope()) onScopeDispose(dispose)
  return { state, loadCapabilities, loadPage, load, open, closeDetail, loadEvents, loadBaseline, loadContexts, selectContext, inspectLinkedContext, allowed, begin, dirty, locked, protectNavigation, closeDialog, submit, reconcile, retry, refreshDecision, useDraft, clear, dispose }
}
