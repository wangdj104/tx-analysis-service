import * as Vue from 'vue'
import * as goalApi from '@/api/commonCareGoal'
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'
import { createHouseholdCommandSession } from '@/utils/householdCommandSession'
import { goalId, validateGoalDraft, goalBody, validGoalReceipt, completionReady } from '@/utils/commonCareGoal'

const copy = value => value == null ? value : JSON.parse(JSON.stringify(value))
const status = error => Number(error?.response?.status ?? error?.status ?? error?.code) || 0
const errorCode = error => error?.response?.data?.data?.errorCode || error?.data?.errorCode || error?.message || 'REQUEST_FAILED'
const unwrap = response => {
  if (response?.code && response.code !== 200) throw Object.assign(new Error('REQUEST_FAILED'), { status: response.code, data: response.data })
  return response && Object.hasOwn(response, 'data') ? response.data : response
}
const page = () => ({ items: [], nextCursor: null, phase: 'idle' })

export function useCommonCareGoal(patientRef, deps = {}) {
  const { reactive, unref, watch, getCurrentScope, onScopeDispose } = Vue
  const api = deps.api || goalApi, host = deps.events || globalThis.window
  const patient = () => goalId(unref(patientRef))
  const captureAuth = deps.captureAuth || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }))
  const isAuthCurrent = deps.isAuthCurrent || (auth => isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId'))
  const newKey = deps.newKey || (() => globalThis.crypto.randomUUID())
  const initial = () => ({ patientId: patient(), availability: patient() ? 'idle' : 'noPatient', capabilities: null,
    list: page(), filter: 'OPEN', detail: null, targetId: null, detailPhase: 'idle', history: page(), event: null, eventPhase: 'idle',
    candidates: page(), candidateError: null, dialog: null, errors: {}, phase: 'idle', pending: null, receipt: null,
    error: null, decisionReady: false, reviewReady: false, navigationBlocked: false })
  const state = reactive(initial()), controllers = new Set()
  let disposed = false, viewEpoch = 0, session = create(), errorLane = null
  function create() { return createHouseholdCommandSession({ patient, captureAuth, isAuthCurrent, events: host, newKey }) }
  function retireReads() {
    // 只在已有 reset/close 使读票据失效后调用；同步结束旧加载，不等旧回调，也不改已载入事实或写入锁。
    for (const controller of controllers) controller.abort()
    controllers.clear()
    if (state.availability === 'loading') state.availability = patient() ? 'idle' : 'noPatient'
    for (const surface of [state.list, state.history, state.candidates]) {
      if (surface.phase === 'loading') surface.phase = 'idle'
    }
    for (const key of ['detailPhase', 'eventPhase']) {
      if (state[key] === 'loading') state[key] = 'idle'
    }
  }
  function clear() {
    viewEpoch++; errorLane = null; session.dispose(); retireReads()
    Object.assign(state, initial())
    if (!disposed) session = create()
  }
  function setError(value, lane = null) { state.error = value; errorLane = value == null ? null : lane }
  function deny() { clear(); state.availability = 'denied'; state.phase = 'forbidden'; setError('ACCESS_DENIED') }
  function fail(error, lane) {
    if ([401, 403].includes(status(error))) { deny(); return 'forbidden' }
    setError(errorCode(error), lane); return 'unavailable'
  }
  function owns(origin, context) {
    if (origin !== session || disposed) return false
    if (!isAuthCurrent(context.auth)) { clear(); return false }
    return origin.owns(context)
  }
  async function read(lane, fetch, apply) {
    if (disposed || !patient() || !captureAuth().token) return { phase: 'unavailable' }
    const origin = session, context = origin.capture(lane), controller = new AbortController()
    controllers.add(controller)
    try {
      const data = unwrap(await fetch({ expectedAuth: context.auth, signal: controller.signal }))
      if (!owns(origin, context) || controller.signal.aborted) return { phase: 'discarded' }
      apply(data)
      // owns 已排除旧响应；成功读取只退役本通道错误，不能抹掉其它读取或命令的新错误。
      if (errorLane === lane) setError(null)
      return { phase: 'loaded' }
    } catch (error) {
      if (!owns(origin, context) || controller.signal.aborted) return { phase: 'discarded' }
      return { phase: fail(error, lane) }
    } finally { controllers.delete(controller) }
  }
  async function loadCapabilities() {
    state.capabilities = null; state.availability = patient() ? 'loading' : 'noPatient'
    const result = await read('capabilities', options => api.capabilities(patient(), options), data => {
      if (goalId(data?.patientId) !== patient() || !['READY', 'ABSENT', 'INCOMPATIBLE'].includes(data.schemaStatus)) throw new Error('COMMON_GOAL_WIRE_INVALID')
      state.capabilities = data
      state.availability = data.schemaStatus !== 'READY' ? data.schemaStatus : !data.readable ? 'denied' : data.enabled ? 'ready' : 'disabled'
      if (!data.canChooseNominee) { state.candidates = page(); state.candidateError = null }
    })
    if (result.phase === 'unavailable') state.availability = patient() ? 'unavailable' : 'noPatient'
    return result
  }
  function readable() { return !!state.capabilities?.readable && state.capabilities.schemaStatus === 'READY' }
  async function load(target = null) {
    if (!protectNavigation()) return { phase: 'blocked' }
    const origin = session, result = await loadCapabilities()
    if (origin !== session || result.phase !== 'loaded') return result
    if (!readable()) { state.list = page(); state.detail = null; state.history = page(); state.event = null; return result }
    return target ? open(target) : loadList()
  }
  async function loadList(more = false, filter = state.filter) {
    if (!readable() || !protectNavigation() || !['OPEN','PROPOSED','ACTIVE','COMPLETED','CANCELLED'].includes(filter)) return { phase: 'blocked' }
    const cursor = more && filter === state.filter ? state.list.nextCursor : null
    if (more && !cursor) return { phase: 'blocked' }
    const previous = cursor ? state.list.items : []
    state.filter = filter; state.list = { ...page(), phase: 'loading' }
    const result = await read('list', options => api.list(patient(), { state: filter, limit: 20, ...(cursor ? { cursor } : {}) }, options), data => {
      if (!Array.isArray(data?.items) || data.items.length > 40 || data.items.some(item => !goalId(item.goalId))) throw new Error('COMMON_GOAL_WIRE_INVALID')
      state.list = { items: [...previous, ...data.items], nextCursor: data.nextCursor, phase: 'loaded' }
    })
    if (result.phase === 'unavailable') state.list.phase = 'unavailable'
    return result
  }
  function validCurrent(data, target) {
    const goal = data?.goal
    return goalId(goal?.patientId) === patient() && goalId(goal?.goalId) === target && goalId(goal?.version) && goalId(goal?.cycle) &&
      goal.content && ['title','goalText','progressCriterion','responsibility'].every(key => typeof goal.content[key] === 'string') &&
      ['PROPOSED','ACTIVE','COMPLETED','CANCELLED'].includes(goal.state) && Array.isArray(data.allowedActions)
  }
  async function readCurrent(target) {
    state.detail = null; state.detailPhase = 'loading'
    const result = await read('current', options => api.current(patient(), target, options), data => {
      if (!validCurrent(data, target)) throw new Error('COMMON_GOAL_WIRE_INVALID')
      state.detail = data; state.detailPhase = 'loaded'
    })
    if (result.phase === 'unavailable') state.detailPhase = 'unavailable'
    return result
  }
  async function open(target) {
    target = goalId(target)
    if (!target || !readable() || !protectNavigation()) return { phase: 'blocked' }
    session.close(); retireReads(); viewEpoch++; state.targetId = target; state.dialog = null; state.history = page(); state.event = null; state.eventPhase = 'idle'
    state.receipt = null; setError(null); state.phase = 'idle'
    return readCurrent(target)
  }
  function refreshCurrent() {
    if (!state.targetId || locked() || dirty()) return Promise.resolve({ phase: 'blocked' })
    return readCurrent(state.targetId)
  }
  function closeDetail() {
    if (!protectNavigation()) return false
    session.close(); retireReads(); viewEpoch++; state.targetId = null; state.detail = null; state.detailPhase = 'idle'; state.history = page(); state.event = null; state.eventPhase = 'idle'
    return true
  }
  async function loadHistory(more = false) {
    if (!state.detail || !protectNavigation()) return { phase: 'blocked' }
    const target = state.detail.goal.goalId, cursor = more ? state.history.nextCursor : null, previous = cursor ? state.history.items : []
    if (more && !cursor) return { phase: 'blocked' }
    state.history = { ...page(), phase: 'loading' }; state.event = null; state.eventPhase = 'idle'
    const result = await read('history', options => api.history(patient(), target, { limit: 20, ...(cursor ? { cursor } : {}) }, options), data => {
      if (!Array.isArray(data?.items) || data.items.length > 40 || data.items.some(item => item.patientId !== patient() || item.goalId !== target || !goalId(item.sequence))) throw new Error('COMMON_GOAL_WIRE_INVALID')
      if (state.targetId === target) state.history = { items: [...previous,...data.items], nextCursor: data.nextCursor, phase: 'loaded' }
    })
    if (result.phase === 'unavailable') state.history.phase = 'unavailable'
    return result
  }
  async function loadEvent(sequence) {
    if (!state.detail || !goalId(sequence) || !protectNavigation()) return { phase: 'blocked' }
    const target = state.detail.goal.goalId
    state.event = null; state.eventPhase = 'loading'
    const result = await read('event', options => api.historyDetail(patient(), target, sequence, options), data => {
      if (data?.patientId !== patient() || data?.goalId !== target || data.sequence !== sequence || !validCurrent({ goal: data.after, allowedActions: [] }, target)) throw new Error('COMMON_GOAL_WIRE_INVALID')
      if (state.targetId === target) { state.event = data; state.eventPhase = 'loaded' }
    })
    if (result.phase === 'unavailable') state.eventPhase = 'unavailable'
    return result
  }
  async function loadCandidates(name = '') {
    if (!state.capabilities?.canChooseNominee || locked()) return { phase: 'blocked' }
    state.candidates = { ...page(), phase: 'loading' }; state.candidateError = null
    const result = await read('candidates', options => api.candidates(patient(), name, options), data => {
      if (!Array.isArray(data?.items) || data.items.length > 40 || data.items.some(item => !goalId(item.userId))) throw new Error('COMMON_GOAL_WIRE_INVALID')
      state.candidates = { items: data.items, nextCursor: null, phase: 'loaded' }
    })
    if (result.phase === 'unavailable') {
      state.candidates.phase = 'unavailable'; state.candidateError = state.error
    }
    return result
  }
  function locked() { return ['submitting','checking','unknown'].includes(state.phase) }
  function dirty() { return !!state.dialog && (locked() || JSON.stringify(state.dialog.draft) !== state.dialog.original) }
  function protectNavigation() {
    if (!dirty() && !locked()) return true
    state.navigationBlocked = true; return false
  }
  function allowed(operation, detail = state.detail) {
    if (!captureAuth().token || !state.capabilities?.canWrite || !readable() || state.availability !== 'ready') return false
    if (operation === 'CREATE') return state.capabilities.canCreate === true
    return !!detail?.allowedActions?.includes(operation) && (operation !== 'COMPLETE' || completionReady(detail))
  }
  function begin(operation) {
    if (locked() || !protectNavigation() || !allowed(operation)) return false
    try { session.reset(); retireReads() } catch { return false }
    const baseline = operation === 'CREATE' ? null : copy(state.detail), content = baseline?.goal.content
    const draft = { title: content?.title || '', goalText: content?.goalText || '', progressCriterion: content?.progressCriterion || '', responsibility: content?.responsibility || '',
      reviewDate: content?.reviewDate || '', nominationMode: operation === 'REVISE' ? 'keep' : 'unassigned', selectedNomineeId: null,
      reason: '', note: '', reportedDate: '', feedbackSource: '', result: '', confirmed: false }
    state.dialog = { operation, target: baseline?.goal.goalId ?? null, version: baseline?.goal.version ?? 0, baseline, draft, original: JSON.stringify(draft) }
    state.phase = 'idle'; setError(null); state.errors = {}; state.pending = null; state.receipt = null; state.candidates = page()
    state.decisionReady = false; state.reviewReady = false; state.navigationBlocked = false
    return true
  }
  function closeDialog(discard = false) {
    if (locked() || (dirty() && !discard)) { state.navigationBlocked = true; return false }
    try { session.reset(); retireReads() } catch { return false }
    state.dialog = null; state.errors = {}; state.pending = null; state.candidates = page(); state.candidateError = null
    state.phase = 'idle'; state.navigationBlocked = false; state.decisionReady = false; state.reviewReady = false
    return true
  }
  async function settle(origin, result) {
    if (origin !== session || disposed || result.phase === 'discarded') return { phase: 'discarded' }
    if (result.phase === 'forbidden') { deny(); return result }
    state.phase = result.phase; setError(result.errorCode || null)
    if (result.phase !== 'succeeded') return result
    // 成功回执先独立保留。后续只读失败不能重新发送已经确认成功的写入。
    state.receipt = copy(result.result); state.phase = 'saved'; state.pending = null; state.dialog = null; state.candidates = page(); state.errors = {}; state.navigationBlocked = false
    state.list = page(); state.history = page(); state.event = null; state.eventPhase = 'idle'
    state.targetId = result.result.goalId; origin.reset(); retireReads()
    await readCurrent(state.targetId)
    return origin === session ? { phase: 'saved' } : { phase: 'discarded' }
  }
  function sender(origin, retrying = false) {
    const auth = captureAuth()
    return async envelope => {
      try {
        const receipt = unwrap(await api.command(envelope, { expectedAuth: auth }))
        if (!validGoalReceipt(receipt, envelope)) throw new Error('COMMON_GOAL_RECEIPT_INVALID')
        return receipt
      } catch (error) {
        // 未知原请求的失败重试仍不能证明原写入未提交，必须继续保留原键/正文。
        if ((retrying || ['COMMON_GOAL_RECEIPT_UNKNOWN','COMMON_GOAL_INTEGRITY'].includes(errorCode(error))) && ![401,403].includes(status(error))) throw Object.assign(new Error('COMMON_GOAL_OUTCOME_UNKNOWN'), { data: { errorCode: errorCode(error) } })
        throw error
      }
    }
  }
  async function submit() {
    if (!state.dialog || locked() || ['conflict','invalid'].includes(state.phase)) return { phase: 'blocked' }
    if (!allowed(state.dialog.operation)) return { phase: 'blocked' }
    const dialog = state.dialog
    state.errors = validateGoalDraft(dialog.operation, dialog.draft)
    let body
    try { body = goalBody(dialog.operation, dialog.draft, { actorId: captureAuth().actorId, owner: state.capabilities.recordOwner, baseline: dialog.baseline, candidates: state.candidates.items }) }
    catch { state.errors.nominationMode = 'chooseNominee' }
    if (Object.keys(state.errors).length) return { phase: 'invalid' }
    const origin = session
    try {
      state.pending = origin.begin(dialog.operation, dialog.target, { patientId: patient(), expectedVersion: dialog.version }, body)
      state.phase = 'submitting'; setError(null)
      return await settle(origin, await origin.submit(sender(origin)))
    } catch { return { phase: origin === session ? 'blocked' : 'discarded' } }
  }
  async function reconcile() {
    if (state.phase !== 'unknown') return { phase: 'blocked' }
    const origin = session
    state.phase = 'checking'
    try {
      const result = await origin.reconcile(async envelope => {
        const data = unwrap(await api.receipt(envelope.patientId, envelope.commandKey, { expectedAuth: captureAuth() }))
        if (data?.status === 'UNKNOWN' && data.receipt == null && data.safeToUseNewKey === false) return null
        if (data?.status !== 'COMMITTED' || data.safeToUseNewKey !== false || !validGoalReceipt(data.receipt, envelope)) throw new Error('COMMON_GOAL_RECEIPT_INVALID')
        return data.receipt
      })
      return settle(origin, result)
    } catch { if (origin === session) state.phase = 'unknown'; return { phase: origin === session ? 'unknown' : 'discarded' } }
  }
  async function retry() {
    if (state.phase !== 'unknown') return { phase: 'blocked' }
    const origin = session, result = await reconcile()
    if (origin !== session || result.phase !== 'unknown') return result
    state.phase = 'checking'
    const fresh = await loadCapabilities()
    if (origin !== session) return { phase: 'discarded' }
    if (fresh.phase !== 'loaded' || !state.capabilities?.canWrite) { state.phase = 'unknown'; return { phase: 'unknown' } }
    // 这里不能重验旧状态/责任关系：已成功的拒绝、退出、完成应当能按原键重放。
    state.phase = 'submitting'
    try { return await settle(origin, await origin.retryIdentical(sender(origin, true))) }
    catch { if (origin === session) state.phase = 'unknown'; return { phase: origin === session ? 'unknown' : 'discarded' } }
  }
  async function refreshDecision() {
    if (!state.dialog || !['conflict','invalid'].includes(state.phase)) return { phase: 'blocked' }
    const origin = session, dialog = state.dialog
    state.decisionReady = false; state.reviewReady = false
    const caps = await loadCapabilities()
    if (origin !== session || caps.phase !== 'loaded') return caps
    const detail = dialog.target ? await readCurrent(dialog.target) : { phase: 'loaded' }
    if (origin !== session || detail.phase !== 'loaded') return detail
    state.reviewReady = true; state.decisionReady = allowed(dialog.operation)
    return { phase: 'loaded' }
  }
  function newDecision() {
    if (!state.decisionReady || !state.dialog || locked() || !allowed(state.dialog.operation)) return false
    session.reset(); retireReads()
    const dialog = state.dialog
    dialog.baseline = dialog.target ? copy(state.detail) : null; dialog.version = dialog.baseline?.goal.version || 0
    state.phase = 'idle'; state.pending = null; setError(null); state.decisionReady = false; state.reviewReady = false; state.candidates = page()
    return true
  }
  function captureFocus() { return { patientId: patient(), auth: captureAuth(), epoch: viewEpoch } }
  function ownsFocus(ticket) { return !disposed && ticket?.epoch === viewEpoch && ticket.patientId === patient() && isAuthCurrent(ticket.auth) }
  function identityChanged() { clear() }
  const identityEvents = ['auth-session-cleared', 'auth-session-changed', 'storage']
  for (const event of identityEvents) host?.addEventListener?.(event, identityChanged)
  const stop = watch(() => unref(patientRef), clear, { flush: 'sync' })
  function dispose() { if (disposed) return; disposed = true; stop(); clear(); for (const event of identityEvents) host?.removeEventListener?.(event, identityChanged) }
  if (getCurrentScope()) onScopeDispose(dispose)
  return { state, clear, load, loadCapabilities, loadList, open, refreshCurrent, closeDetail, loadHistory, loadEvent, loadCandidates,
    allowed, begin, dirty, locked, protectNavigation, closeDialog, submit, reconcile, retry, refreshDecision, newDecision, captureFocus, ownsFocus, dispose }
}
