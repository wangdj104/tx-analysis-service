import * as Vue from 'vue'
import * as policyApi from '@/api/archiveStockNotification'
import { captureAuthSession, isAuthSessionCurrent, AUTH_STORAGE_KEYS } from '@/utils/authSession'
import { createHouseholdCommandSession } from '@/utils/householdCommandSession'
import { stockPolicyId, policyEnvelope, validStockPolicyReceipt, validStockPolicyCurrent } from '@/utils/archiveStockNotification'
const unwrap = response => {
  if (response?.code && response.code !== 200) throw Object.assign(new Error('REQUEST_REJECTED'), { status: response.code, data: response.data })
  return response && Object.hasOwn(response, 'data') ? response.data : response
}
const status = error => Number(error?.response?.status ?? error?.status ?? error?.code) || 0
const newRequestKey = () => {
  if (globalThis.crypto.randomUUID) return globalThis.crypto.randomUUID()
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
  return [...bytes].map(n => n.toString(16).padStart(2, '0')).join('')
}
/** One explicit row review. Unknown writes retain the original immutable household command session. */
export function useArchiveStockNotification(patientRef, deps = {}) {
  const api = deps.api || policyApi, host = deps.events || globalThis.window
  // This caller filters harmless preferences for both its flow and the unchanged shared session.
  const storageListeners = new Map()
  const identityEvents = {
    addEventListener(type, listener) {
      if (type !== 'storage') { host?.addEventListener?.(type, listener); return }
      const filtered = event => { if (event.key == null || [...AUTH_STORAGE_KEYS, 'permissionSession'].includes(event.key)) listener(event) }
      storageListeners.set(listener, filtered); host?.addEventListener?.(type, filtered)
    },
    removeEventListener(type, listener) {
      const filtered = type === 'storage' ? storageListeners.get(listener) : listener
      if (filtered) host?.removeEventListener?.(type, filtered)
      if (type === 'storage') storageListeners.delete(listener)
    }
  }
  const patient = () => stockPolicyId(Vue.unref(patientRef))
  const captureAuth = deps.captureAuth || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }))
  const isAuthCurrent = deps.isAuthCurrent || (auth => isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId'))
  const initial = () => ({ dialog: false, patientId: null, target: null, viewId: 0, current: null, currentPhase: 'idle', phase: 'idle', receipt: null, acknowledged: false, error: null, reading: false })
  const state = Vue.reactive(initial()), controllers = new Set()
  let disposed = false, session = create(), commandAuth = null, viewRevision = 0
  function create() { return createHouseholdCommandSession({ patient, captureAuth, isAuthCurrent, events: identityEvents, newKey: deps.newKey || newRequestKey }) }
  function clear() {
    session.dispose(); for (const controller of controllers) controller.abort(); controllers.clear()
    commandAuth = null; Object.assign(state, initial()); if (!disposed) session = create()
  }
  const locked = () => ['submitting', 'unknown', 'checking'].includes(state.phase)
  function protectNavigation() { if (!locked()) return true; state.error = 'PENDING_ACTION'; return false }
  function ownsProjection(context) { return !!(state.dialog && context && context.patientId === patient() && context.patientId === state.patientId && context.stockId === state.target && context.viewId === state.viewId) }
  function forbidden() {
    const context = { patientId: state.patientId, stockId: state.target, viewId: state.viewId }
    if (ownsProjection(context)) deps.onForbidden?.(context)
    clear(); state.dialog = true; state.phase = 'forbidden'; state.error = 'ACCESS_DENIED'
  }
  async function refreshCurrent() {
    if (disposed || !state.target || !patient()) return { phase: 'unavailable' }
    const origin = session, stock = state.target, selectedPatient = patient(), context = origin.capture('current'), controller = new AbortController()
    controllers.add(controller); state.current = null; state.currentPhase = 'loading'; state.acknowledged = false
    const owns = () => origin === session && origin.owns(context) && state.target === stock && !controller.signal.aborted
    try {
      const value = unwrap(await api.current(stock, selectedPatient, { expectedAuth: context.auth, signal: controller.signal }))
      if (!owns()) return { phase: 'discarded' }
      if (!validStockPolicyCurrent(value, selectedPatient, stock)) throw Object.assign(new Error('INVALID_CURRENT'), { status: 503 })
      state.current = value; state.currentPhase = 'ready'; return { phase: 'succeeded' }
    } catch (error) {
      if (!owns()) return { phase: 'discarded' }
      if ([401,403].includes(status(error))) { forbidden(); return { phase: 'forbidden' } }
      state.current = null; state.currentPhase = 'unavailable'; return { phase: 'unavailable' }
    } finally { controllers.delete(controller) }
  }
  async function open(stock) {
    stock = stockPolicyId(stock)
    if (disposed || !stock || !patient() || !protectNavigation()) return false
    clear(); state.viewId = ++viewRevision; state.patientId = patient(); state.target = stock; state.dialog = true; await refreshCurrent(); return true
  }
  function close() { if (!protectNavigation()) return false; clear(); return true }
  function retireView() { if (!protectNavigation()) return false; clear(); return true }
  function can(operation) {
    const current = state.current
    return !!(!locked() && ['idle','succeeded'].includes(state.phase) && state.currentPhase === 'ready' && current?.marked && current.canWrite &&
      current.controlVersion < Number.MAX_SAFE_INTEGER && (operation === 'DISABLE' ? current.enabled : !current.enabled && current.currentFacts.reviewReady && state.acknowledged))
  }
  function checkedReceipt(value, envelope) {
    if (!validStockPolicyReceipt(value, envelope)) throw Object.assign(new Error('INVALID_RECEIPT'), { status: 503 })
    return value
  }
  const send = envelope => api.command(policyEnvelope(envelope), { expectedAuth: commandAuth }).then(unwrap).then(value => checkedReceipt(value, envelope))
  async function settle(origin, result) {
    if (origin !== session || disposed || result.phase === 'discarded') return { phase: 'discarded' }
    state.phase = result.phase; state.error = result.errorCode || null
    if (result.phase === 'forbidden') forbidden()
    else if (result.phase === 'succeeded') {
      state.receipt = result.result; origin.reset(); commandAuth = null; await refreshCurrent()
    } else if (['conflict','invalid'].includes(result.phase)) await refreshCurrent()
    return result
  }
  async function submit(operation) {
    if (!['ENABLE','DISABLE'].includes(operation) || !can(operation)) return { phase: 'unavailable' }
    const origin = session, current = state.current
    try {
      commandAuth = Object.freeze(captureAuth())
      origin.begin(operation, state.target, { patientId: patient(), expectedVersion: current.controlVersion }, operation === 'ENABLE' ? { currentFactsHash: current.currentFactsHash, reviewAcknowledged: true } : {})
      state.phase = 'submitting'; state.receipt = null; state.error = null
      return await settle(origin, await origin.submit(send))
    } catch { return { phase: origin === session ? 'unavailable' : 'discarded' } }
  }
  async function checkReceipt() {
    if (state.phase !== 'unknown' || state.reading) return { phase: 'unavailable' }
    const origin = session; state.reading = true
    try {
      return await settle(origin, await origin.reconcile(envelope => api.receipt(envelope.target, envelope.patientId, envelope.commandKey, { expectedAuth: commandAuth }).then(unwrap).then(value => checkedReceipt(value, envelope))))
    } finally { if (origin === session) state.reading = false }
  }
  async function retry() {
    if (state.phase !== 'unknown' || state.reading) return { phase: 'unavailable' }
    const origin = session; state.reading = true
    try {
      const read = await refreshCurrent()
      if (origin !== session || read.phase !== 'succeeded' || !state.current?.canWrite) return { phase: origin === session ? 'unavailable' : 'discarded' }
      state.phase = 'submitting'; return await settle(origin, await origin.retryIdentical(send))
    } finally { if (origin === session) state.reading = false }
  }
  async function reviewAgain() {
    if (!['conflict','invalid'].includes(state.phase)) return false
    session.reset(); commandAuth = null; state.phase = 'idle'; state.error = null; state.acknowledged = false
    await refreshCurrent(); return true
  }
  const onIdentity = () => clear()
  for (const event of ['auth-session-cleared','auth-session-changed','storage']) identityEvents.addEventListener(event, onIdentity)
  const stop = Vue.watch(patientRef, clear, { flush: 'sync' })
  function dispose() {
    if (disposed) return
    disposed = true; stop(); clear()
    for (const event of ['auth-session-cleared','auth-session-changed','storage']) identityEvents.removeEventListener(event, onIdentity)
  }
  if (Vue.getCurrentScope()) Vue.onScopeDispose(dispose)
  return { state, open, close, refreshCurrent, submit, checkReceipt, retry, reviewAgain, can, locked, protectNavigation, retireView, ownsProjection, clear, dispose }
}
