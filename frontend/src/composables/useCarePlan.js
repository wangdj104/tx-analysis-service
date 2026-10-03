import { reactive, unref, watch, getCurrentScope, onScopeDispose } from 'vue'
import * as api from '@/api/carePlan'
import { AUTH_STORAGE_KEYS, captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'
import { localDayContext } from '@/utils/carePlanTime'

const queues = ['TODAY', 'REVIEW', 'HELP', 'OVERDUE', 'HISTORY']
const commands = ['createDraft', 'saveDraft', 'publishPlan', 'revisePlan', 'transitionPlan', 'submitReceipt', 'requestHelp', 'followUp', 'reviewReceipt']
const actionCommands = ['submitReceipt', 'requestHelp', 'followUp', 'reviewReceipt']
const copy = value => value == null ? value : JSON.parse(JSON.stringify(value))
const dataOf = response => response && Object.hasOwn(response, 'data') ? response.data : response
const statusOf = error => Number(error?.response?.status || error?.code) || 0
function positiveId(value) {
  if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError('An ID must be a positive safe integer.')
  return value
}
function freeze(value) {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value) }
  return value
}
function commandKey() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID()
  if (!globalThis.crypto?.getRandomValues) throw new Error('Secure random values are unavailable.')
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = [...bytes].map(byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** Patient ref may be null for staff inboxes. Sensitive drafts and command snapshots are memory-only. */
export function useCarePlan(patientIdRef) {
  const patient = () => unref(patientIdRef) == null ? null : positiveId(Number(unref(patientIdRef)))
  const state = reactive({ patientId: patient(), items: [], nextCursor: null, listContext: null,
    plan: null, editor: null, draft: null, loading: false, opening: false,
    commandPhase: 'idle', commandKey: null, error: null, result: null })
  let epoch = 0, editorEpoch = 0, listEpoch = 0, draftEpoch = 0, disposed = false, attempt = null
  let session = captureAuthSession(), account = localStorage.getItem('userId')
  const pendingRequests = new Map()
  function abortRequests(kind) {
    for (const [controller, requestKind] of pendingRequests) {
      if (!kind || requestKind === kind) { controller.abort(); pendingRequests.delete(controller) }
    }
  }
  function requestOptions(context, kind) {
    const controller = new AbortController()
    pendingRequests.set(controller, kind)
    return { expectedAuth: { ...context.auth, actorId: context.account }, signal: controller.signal, controller }
  }

  function clear(clearPatient = false) {
    abortRequests()
    epoch++; editorEpoch++; listEpoch++; attempt = null
    Object.assign(state, { patientId: clearPatient ? null : patient(), items: [], nextCursor: null, listContext: null,
      plan: null, editor: null, draft: null, loading: false, opening: false,
      commandPhase: 'idle', commandKey: null, error: null, result: null })
  }
  function reset() { clear() }
  function authChanged() {
    clear(true)
    session = captureAuthSession(); account = localStorage.getItem('userId')
  }
  function storageChanged(event) {
    if (event.key == null || AUTH_STORAGE_KEYS.includes(event.key)) authChanged()
  }
  function currentSession() {
    if (disposed) return null
    const now = captureAuthSession(), actor = localStorage.getItem('userId')
    if (!isAuthSessionCurrent(session) || account !== actor) {
      clear(true); session = now; account = actor
    }
    if (!now.token) return null
    state.patientId = patient()
    return now
  }
  function capture() {
    const auth = currentSession()
    return auth && { auth, account, epoch, editorEpoch, listEpoch, draftEpoch }
  }
  function owns(context, kind) {
    if (!context || disposed) return false
    if (!isAuthSessionCurrent(context.auth) || context.account !== localStorage.getItem('userId')) {
      // An obsolete account's result must not erase the replacement account's editor.
      if (!isAuthSessionCurrent(session) || account !== localStorage.getItem('userId')) authChanged()
      return false
    }
    return context.epoch === epoch && (kind !== 'editor' || context.editorEpoch === editorEpoch) && (kind !== 'list' || context.listEpoch === listEpoch)
  }
  function readError(error) {
    const status = statusOf(error)
    if ([401, 403, 404].includes(status)) {
      clear(status === 401)
      return { status: status === 401 ? 'unauthenticated' : status === 403 ? 'forbidden' : 'unavailable' }
    }
    state.error = error
    return { status: 'failed', error }
  }

  /** load({queue='TODAY',cursor=null,limit=50,dueBefore?}). A cursor is valid only in the current patient/day/queue context. */
  async function load({ queue = 'TODAY', cursor = null, limit = 50, dueBefore } = {}) {
    if (!queues.includes(queue)) throw new TypeError('Invalid care-plan queue.')
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new TypeError('The page size must be between 1 and 100.')
    if (!currentSession()) return { status: 'unauthenticated' }
    const day = localDayContext()
    const cutoff = queue === 'TODAY' ? dueBefore ?? day.dueBefore : null
    const key = JSON.stringify([state.patientId, account, queue, day.dayKey, day.timeZone, cutoff])
    if (cursor && (key !== state.listContext || cursor !== state.nextCursor)) {
      abortRequests('list'); listEpoch++; state.items = []; state.nextCursor = null; state.listContext = key; state.loading = false
      return { status: 'stale-cursor' }
    }
    abortRequests('list'); listEpoch++
    if (!cursor) { state.items = []; state.nextCursor = null }
    state.listContext = key; state.loading = true; state.error = null
    const context = capture(), options = requestOptions(context, 'list')
    const params = { queue, limit, ...(state.patientId == null ? {} : { patientId: state.patientId }), ...(cursor ? { cursor } : {}), ...(cutoff == null ? {} : { dueBefore: cutoff }) }
    try {
      const response = await api.listPlans(params, options)
      if (!owns(context, 'list')) return { status: 'stale' }
      // A response spanning local midnight cannot reintroduce a cursor for yesterday.
      const now = localDayContext()
      if (now.dayKey !== day.dayKey || now.timeZone !== day.timeZone) {
        listEpoch++; state.items = []; state.nextCursor = null; state.listContext = null; state.loading = false
        return { status: 'stale' }
      }
      const page = dataOf(response)
      state.items = cursor ? [...state.items, ...(page?.items || [])] : page?.items || []
      state.nextCursor = page?.nextCursor ?? null
      return { status: 'succeeded', data: page }
    } catch (error) {
      if (!owns(context, 'list')) return { status: 'stale' }
      return readError(error)
    } finally {
      pendingRequests.delete(options.controller)
      if (owns(context, 'list')) state.loading = false
    }
  }

  /** open(null,{draft}) starts a new editor; open(planId,{revisionId?,draft?}) explicitly loads a revision when requested. */
  async function open(planId = null, { revisionId = null, draft = null } = {}) {
    if (!currentSession()) return { status: 'unauthenticated' }
    if (planId != null) positiveId(planId)
    if (revisionId != null) { positiveId(revisionId); if (planId == null) throw new TypeError('A revision requires a plan ID.') }
    abortRequests('editor'); editorEpoch++; attempt = null
    Object.assign(state, { plan: null, editor: { planId, revisionId }, draft: copy(draft), opening: planId != null,
      commandPhase: 'idle', commandKey: null, error: null, result: null })
    const context = capture()
    if (planId == null) return { status: 'opened' }
    const options = requestOptions(context, 'editor')
    try {
      const response = await (revisionId == null ? api.getPlan(planId, options) : api.getPlanRevision(planId, revisionId, options))
      if (!owns(context, 'editor')) return { status: 'stale' }
      const plan = dataOf(response)
      state.plan = plan
      if (draft == null && context.draftEpoch === draftEpoch && state.draft == null) state.draft = copy(plan)
      return { status: 'succeeded', data: plan }
    } catch (error) {
      if (!owns(context, 'editor')) return { status: 'stale' }
      return readError(error)
    } finally {
      pendingRequests.delete(options.controller)
      if (owns(context, 'editor')) state.opening = false
    }
  }

  function newAttempt(name, options) {
    if (!commands.includes(name)) throw new TypeError('Invalid care-plan command.')
    const id = options.id ?? (actionCommands.includes(name) ? null : state.editor?.planId ?? state.plan?.id)
    const revisionId = options.revisionId ?? state.editor?.revisionId ?? state.plan?.draftRevisionId
    if (name !== 'createDraft') positiveId(id)
    if (['saveDraft', 'publishPlan'].includes(name)) positiveId(revisionId)
    if (name === 'transitionPlan' && !['CANCEL', 'CLOSE'].includes(options.action)) throw new TypeError('The action must be CANCEL or CLOSE.')
    const expectedVersion = options.expectedVersion ?? state.plan?.version ?? 0
    if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 0) throw new TypeError('The expected version must be a nonnegative safe integer.')
    const input = options.payload === undefined ? state.draft ?? {} : options.payload
    if (!input || typeof input !== 'object' || Array.isArray(input)) throw new TypeError('A command payload must be a JSON object.')
    const key = commandKey()
    return { name, id, revisionId, action: options.action, key,
      input: JSON.stringify(input), body: freeze({ ...copy(input), ...(name === 'createDraft' && state.patientId != null ? { patientId: state.patientId } : {}), commandKey: key, expectedVersion }), token: Symbol('command') }
  }
  function invoke(command, options) {
    const body = copy(command.body)
    if (command.name === 'createDraft') return api.createDraft(body, options)
    if (['saveDraft', 'publishPlan'].includes(command.name)) return api[command.name](command.id, command.revisionId, body, options)
    if (command.name === 'transitionPlan') return api.transitionPlan(command.id, command.action, body, options)
    return api[command.name](command.id, body, options)
  }

  /** runCommand(name,{id?,revisionId?,action?,payload=state.draft,expectedVersion?,retry=false}). UNKNOWN retries reuse the entire original command. */
  async function runCommand(name, options = {}) {
    if (!currentSession()) return { status: 'unauthenticated' }
    if (!state.editor) return { status: 'editor-required' }
    if (state.commandPhase === 'pending') return { status: 'busy' }
    if (state.commandPhase === 'unknown') {
      if (!options.retry || name !== attempt?.name ||
          (options.id != null && options.id !== attempt.id) ||
          (options.revisionId != null && options.revisionId !== attempt.revisionId) ||
          (options.action != null && options.action !== attempt.action)) return { status: 'retry-required' }
    } else {
      if (options.retry) return { status: 'retry-unavailable' }
      attempt = newAttempt(name, options)
    }
    const command = attempt, context = capture(), optionsForRequest = requestOptions(context, 'editor')
    state.commandPhase = 'pending'; state.commandKey = command.key; state.error = null; state.result = null
    try {
      const response = await invoke(command, optionsForRequest)
      if (!owns(context, 'editor') || attempt?.token !== command.token) return { status: 'stale' }
      if (context.draftEpoch !== draftEpoch) {
        attempt = null; state.commandPhase = 'idle'; state.commandKey = null
        return { status: 'stale' }
      }
      const result = dataOf(response)
      state.result = result; state.commandPhase = 'succeeded'; attempt = null
      return { status: 'succeeded', data: result, draftUnchanged: JSON.stringify(state.draft ?? {}) === command.input }
    } catch (error) {
      if (!owns(context, 'editor') || attempt?.token !== command.token) return { status: 'stale' }
      if (error.notDispatched) {
        attempt = null; state.commandPhase = 'failed'; state.error = null
        return { status: 'cancelled' }
      }
      const status = statusOf(error)
      if ([401, 403, 404].includes(status)) return readError(error)
      if (!status || status >= 500 || status === 408) {
        // The server may have committed. Only explicit replay of this frozen snapshot is safe.
        state.commandPhase = 'unknown'; state.error = error
        return { status: 'unknown', error }
      }
      attempt = null
      state.commandPhase = status === 409 ? 'conflict' : 'failed'
      if (context.draftEpoch !== draftEpoch) return { status: 'stale' }
      state.error = error
      return { status: state.commandPhase, error }
    } finally {
      pendingRequests.delete(optionsForRequest.controller)
      if (owns(context, 'editor') && attempt?.token === command.token && state.commandPhase === 'pending') state.commandPhase = 'idle'
    }
  }

  const stopPatient = watch(() => unref(patientIdRef), () => clear(), { flush: 'sync' })
  const stopDraft = watch(() => state.draft, () => { draftEpoch++ }, { deep: true, flush: 'sync' })
  if (typeof window !== 'undefined') {
    window.addEventListener('auth-session-cleared', authChanged)
    window.addEventListener('storage', storageChanged)
  }
  function dispose() {
    if (disposed) return
    clear(true); disposed = true; stopPatient(); stopDraft()
    if (typeof window !== 'undefined') {
      window.removeEventListener('auth-session-cleared', authChanged)
      window.removeEventListener('storage', storageChanged)
    }
  }
  if (getCurrentScope()) onScopeDispose(dispose)
  return { state, load, open, runCommand, reset, dispose }
}
