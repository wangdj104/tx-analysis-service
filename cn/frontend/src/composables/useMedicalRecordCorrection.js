import { computed, reactive, unref, watch, onScopeDispose } from 'vue'
import * as correctionApi from '@/api/medicalRecordCorrection'
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache, AUTH_STORAGE_KEYS } from '@/utils/authSession'
import { createHouseholdCommandSession } from '@/utils/householdCommandSession'

export const HEADER_FIELDS = Object.freeze({ recordDate: 10, hospitalName: 100, deptName: 100, doctorName: 50, remark: 500 })
export const ITEM_FIELDS = Object.freeze({ itemName: 200, itemCode: 50, resultValue: 500, unit: 50, referenceRange: 200 })
const copy = value => JSON.parse(JSON.stringify(value))
const positive = value => Number.isSafeInteger(value) && value > 0
const fail = code => { throw new Error(code) }
const length = value => [...value].length
const samePatient = (left, right) => (left ?? null) === (right ?? null)
const unwrap = response => {
  if (response?.code && response.code !== 200) throw Object.assign(new Error('REQUEST_FAILED'), { code: response.code, data: response.data })
  return response?.data ?? response
}
function exactField(value, maximum, field) {
  if (value !== null && (typeof value !== 'string' || length(value) > maximum)) fail('FIELD_LIMIT')
  if (field === 'recordDate' && value !== null) {
    if (!/^[1-9]\d{3}-\d{2}-\d{2}$/.test(value)) fail('INVALID_DATE')
    const date = new Date(`${value}T00:00:00Z`)
    if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value) fail('INVALID_DATE')
  }
}
export function createCorrectionDraft(snapshot) {
  return {
    header: Object.fromEntries(Object.keys(HEADER_FIELDS).map(field => [field, snapshot.record[field] ?? null])),
    items: snapshot.items.map(item => ({ id: item.id, ...Object.fromEntries(Object.keys(ITEM_FIELDS).map(field => [field, item[field] ?? null])), flagChoice: 'KEEP', confirmNameCodePair: false }))
  }
}

/** 只发送真正改动的字段；空串、空格、null 和数字原文各自保留，绝不自动纠正单位或标记。 */
export function buildCorrectionPatch(snapshot, draft, { requirePair = true } = {}) {
  if (!snapshot?.record || !draft?.header || !Array.isArray(draft.items)) fail('INVALID_DRAFT')
  const headerPatch = {}, itemPatches = [], differences = [], flagResets = []
  for (const [field, maximum] of Object.entries(HEADER_FIELDS)) {
    const before = snapshot.record[field] ?? null, after = draft.header[field]
    if (before === after) continue
    exactField(after, maximum, field); headerPatch[field] = after
    differences.push({ field, before, after, itemId: null })
  }
  const seen = new Set()
  for (const proposed of draft.items) {
    const original = snapshot.items.find(item => item.id === proposed.id)
    if (!original || seen.has(proposed.id)) fail('INVALID_DRAFT')
    seen.add(proposed.id)
    const fields = {}
    for (const [field, maximum] of Object.entries(ITEM_FIELDS)) {
      const before = original[field] ?? null, after = proposed[field]
      if (before === after) continue
      exactField(after, maximum, field); fields[field] = after
      differences.push({ field, before, after, itemId: proposed.id })
    }
    const contextChanged = Object.keys(fields).length > 0
    const pairChanged = 'itemName' in fields || 'itemCode' in fields
    if (pairChanged && requirePair && proposed.confirmNameCodePair !== true) fail('PAIR_CONFIRMATION')
    const flagValues = { UNKNOWN: null, LOW: -1, NORMAL: 0, HIGH: 1 }
    if (proposed.flagChoice !== 'KEEP') {
      if (!(proposed.flagChoice in flagValues)) fail('INVALID_DRAFT')
      const after = flagValues[proposed.flagChoice]
      if (contextChanged || original.isAbnormal !== after) {
        fields.reportedFlag = { mode: after === null ? 'UNKNOWN' : 'SOURCE_REPORTED', value: after }
        differences.push({ field: 'reportedFlag', before: original.isAbnormal ?? null, after, itemId: proposed.id })
      }
    } else if (contextChanged) flagResets.push(proposed.id)
    if (Object.keys(fields).length) itemPatches.push({ itemId: proposed.id, fields, ...(pairChanged ? { confirmNameCodePair: true } : {}) })
  }
  return { ...(Object.keys(headerPatch).length ? { headerPatch } : {}), ...(itemPatches.length ? { itemPatches } : {}), differences, flagResets }
}

export function useMedicalRecordCorrection(targetRef, contextRef, dependencies = {}) {
  const api = dependencies.api || correctionApi, events = dependencies.events || globalThis.window
  const capture = dependencies.capture || (() => ({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }))
  const isCurrent = dependencies.isCurrent || (auth => isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId'))
  const permissionKey = dependencies.permissionKey || (() => JSON.stringify(readPermissionCache()))
  const target = () => unref(targetRef) || {}, context = () => unref(contextRef) || {}
  const contextKey = () => JSON.stringify([target(), context()])
  const initial = () => ({ capabilities: null, current: null, history: null, revision: null, historyCursor: null, previousCursors: [], revisionSequence: null,
    loading: false, currentLoading: false, historyLoading: false, revisionLoading: false,
    accessError: '', currentError: '', historyError: '', revisionError: '', commandError: '',
    draft: null, draftBase: null, reason: '', operation: null, preview: null, phase: 'idle', stage: 'view', receipt: null, generation: 0 })
  const state = reactive(initial())
  let disposed = false, owner = null, commands = null, pendingBody = null
  const controllers = new Map()
  function authSnapshot() { return { ...capture(), context: contextKey(), permission: permissionKey() } }
  function ownsAuth(auth) { return !!auth && isCurrent(auth) && auth.context === contextKey() && auth.permission === permissionKey() }
  function makeCommands() {
    return createHouseholdCommandSession({ patient: () => target().patientId, captureAuth: authSnapshot, isAuthCurrent: ownsAuth,
      newKey: dependencies.newKey || (() => crypto.randomUUID()) })
  }
  function clear(error = '') {
    const generation = state.generation + 1
    for (const controller of controllers.values()) controller.abort()
    controllers.clear(); commands?.dispose(); commands = null; pendingBody = null; owner = null
    Object.assign(state, initial(), { accessError: error, generation })
  }
  function resetCommandAndCancelReads() {
    // 先由原命令会话拒绝结果未知或正在发送的重置；拒绝时不能动读取与冻结请求。
    commands.reset()
    // 重置也会作废读 ticket，必须同时取消实际在途读取，避免界面永远停在加载中。
    for (const [lane, controller] of controllers) {
      controller.abort()
      state[lane === 'capabilities' ? 'loading' : `${lane}Loading`] = false
      if (lane === 'current' && !state.current) state.currentError = 'READ_UNAVAILABLE'
      if (lane === 'capabilities' && !state.capabilities) state.accessError = 'READ_UNAVAILABLE'
    }
    controllers.clear()
  }
  function requireOwner() {
    if (disposed || !ownsAuth(owner)) { if (!disposed) clear('CONTEXT_CHANGED'); return false }
    return true
  }
  function readError(error) {
    const status = Number(error?.response?.status ?? error?.status ?? error?.code)
    if ([401, 403].includes(status)) { clear('ACCESS_DENIED'); return null }
    const code = error?.response?.data?.data?.errorCode || error?.data?.errorCode || ''
    return code === 'MEDICAL_RECORD_SCHEMA_NOT_READY' ? 'SCHEMA_UNAVAILABLE' : code === 'MEDICAL_RECORD_INTEGRITY' ? 'INTEGRITY_UNAVAILABLE' : code === 'MEDICAL_RECORD_LIMIT' ? 'RECORD_LIMIT' : 'READ_UNAVAILABLE'
  }
  function startRead(lane) {
    if (!requireOwner()) return null
    controllers.get(lane)?.abort()
    const controller = new AbortController(); controllers.set(lane, controller)
    const ticket = commands.capture(lane), scope = { ...target() }, generation = state.generation
    return { scope, generation, options: { expectedAuth: ticket.auth, signal: controller.signal },
      owns: () => !disposed && commands?.owns(ticket) && ownsAuth(owner),
      finish: () => { if (controllers.get(lane) === controller) controllers.delete(lane); if (!disposed && generation === state.generation && !ownsAuth(owner)) clear('CONTEXT_CHANGED') } }
  }
  function validTarget(value, scope) { return value?.recordId === scope.recordId && samePatient(value.patientId, scope.patientId) }
  function validCapabilities(value, scope) {
    return validTarget(value, scope) && ['canCorrect', 'canInvalidate', 'canLegacyEdit', 'canLegacyDelete', 'managed'].every(key => typeof value[key] === 'boolean')
  }
  async function open() {
    if (disposed || ['unknown', 'submitting'].includes(state.phase)) return false
    clear()
    if (context().focused || !positive(target().recordId) || (target().patientId != null && !positive(target().patientId))) { state.accessError = 'ACCESS_DENIED'; return false }
    owner = authSnapshot(); if (!ownsAuth(owner)) { clear('ACCESS_DENIED'); return false }
    commands = makeCommands()
    const read = startRead('capabilities'); if (!read) return false
    state.loading = true
    try {
      const value = unwrap(await api.capabilities(read.scope.recordId, read.scope.patientId, read.options))
      if (!read.owns()) return false
      if (!validCapabilities(value, read.scope)) fail('INVALID_RESPONSE')
      state.capabilities = value
      await loadCurrent()
      return read.owns()
    } catch (error) { if (read.owns()) state.accessError = readError(error) || state.accessError; return false }
    finally { if (read.owns()) state.loading = false; read.finish() }
  }
  async function loadCurrent() {
    const read = startRead('current'); if (!read) return false
    state.currentLoading = true; state.currentError = ''; state.current = null
    try {
      const value = unwrap(await api.current(read.scope.recordId, read.scope.patientId, read.options))
      if (!read.owns()) return false
      if (!validCapabilities(value, read.scope) || !positive(value.contentVersion) || value.current?.record?.id !== read.scope.recordId ||
          !samePatient(value.current.record.patientId, read.scope.patientId) || value.current.record.contentVersion !== value.contentVersion ||
          !Array.isArray(value.current.items) || value.current.items.length > 100 || !Array.isArray(value.current.attachments) || value.current.attachments.length > 100) fail('INVALID_RESPONSE')
      state.current = value
      state.capabilities = Object.fromEntries(['recordId', 'patientId', 'managed', 'canCorrect', 'canInvalidate', 'canLegacyEdit', 'canLegacyDelete'].map(key => [key, value[key]]))
      return true
    } catch (error) {
      if (read.owns()) {
        state.currentError = readError(error) || ''
        // 已明确发现存储或历史不一致时，旧能力提示失效；普通网络失败和快照限额不等同于失权。
        if (['SCHEMA_UNAVAILABLE', 'INTEGRITY_UNAVAILABLE'].includes(state.currentError) && state.capabilities) {
          state.capabilities = { ...state.capabilities, canCorrect: false, canInvalidate: false, canLegacyEdit: false, canLegacyDelete: false }
        }
      }
      return false
    }
    finally { if (read.owns()) state.currentLoading = false; read.finish() }
  }
  async function loadHistory(cursor = null, previous = []) {
    const read = startRead('history'); if (!read) return false
    state.history = null; state.revision = null; state.revisionSequence = null
    controllers.get('revision')?.abort(); controllers.delete('revision'); commands.capture('revision')
    state.revisionLoading = false
    state.historyCursor = cursor; state.previousCursors = previous; state.historyLoading = true; state.historyError = ''; state.revisionError = ''
    try {
      const value = unwrap(await api.history(read.scope.recordId, read.scope.patientId, { ...read.options, size: 20, cursor }))
      if (!read.owns()) return false
      if (!validTarget(value, read.scope) || !Array.isArray(value.entries) || value.entries.length > 20 || (value.nextCursor != null && typeof value.nextCursor !== 'string')) fail('INVALID_RESPONSE')
      state.history = value; return true
    } catch (error) { if (read.owns()) state.historyError = readError(error) || ''; return false }
    finally { if (read.owns()) state.historyLoading = false; read.finish() }
  }
  const retryHistory = () => loadHistory(state.historyCursor, [...state.previousCursors])
  const nextHistory = () => state.history?.nextCursor != null && loadHistory(state.history.nextCursor, [...state.previousCursors, state.historyCursor].slice(-50))
  function previousHistory() { const previous = [...state.previousCursors]; if (!previous.length) return; return loadHistory(previous.pop(), previous) }
  async function loadRevision(sequence) {
    if (!Number.isSafeInteger(sequence) || sequence < 0) return false
    const read = startRead('revision'); if (!read) return false
    state.revision = null; state.revisionSequence = sequence; state.revisionError = ''; state.revisionLoading = true
    try {
      const value = unwrap(await api.revision(read.scope.recordId, read.scope.patientId, sequence, read.options))
      if (!read.owns()) return false
      if (value?.historySequence !== sequence || value.snapshot?.record?.id !== read.scope.recordId || !samePatient(value.snapshot.record.patientId, read.scope.patientId)) fail('INVALID_RESPONSE')
      state.revision = value; return true
    } catch (error) { if (read.owns()) state.revisionError = readError(error) || ''; return false }
    finally { if (read.owns()) state.revisionLoading = false; read.finish() }
  }
  const locked = computed(() => ['unknown', 'submitting'].includes(state.phase))
  const canClose = computed(() => !locked.value)
  const dirty = computed(() => state.stage !== 'view' && (!!state.reason || !!state.draft))
  function start(operation) {
    if (!requireOwner() || locked.value || !state.current || state.currentLoading || state.phase === 'conflict') return false
    if (operation === 'CORRECT' ? !state.current.canCorrect : operation !== 'INVALIDATE' || !state.current.canInvalidate) return false
    resetCommandAndCancelReads(); state.receipt = null; state.commandError = ''; state.phase = 'idle'; state.stage = 'edit'; state.operation = operation
    state.draftBase = copy(state.current.current); state.draft = createCorrectionDraft(state.draftBase); state.reason = ''; state.preview = null
    return true
  }
  function preview() {
    if (!requireOwner() || locked.value || state.phase === 'conflict' || !state.current || !state.operation) return false
    try {
      if (typeof state.reason !== 'string' || !state.reason.trim() || length(state.reason) > 1000) fail('REASON_REQUIRED')
      const patch = state.operation === 'CORRECT' ? buildCorrectionPatch(state.draftBase, state.draft) : { differences: [], flagResets: [] }
      if (state.operation === 'CORRECT' && !patch.differences.length) fail('NO_CHANGES')
      state.preview = patch; state.commandError = ''; state.stage = 'confirm'; return true
    } catch (error) { state.commandError = error.message; return false }
  }
  function editAgain() { if (requireOwner() && !locked.value && state.phase !== 'conflict') { state.stage = 'edit'; state.preview = null } }
  function discard() {
    if (!requireOwner() || locked.value) return false
    resetCommandAndCancelReads(); state.stage = 'view'; state.phase = 'idle'; state.operation = null; state.draft = null; state.draftBase = null; state.reason = ''; state.preview = null; state.commandError = ''; return true
  }
  async function applyResult(result, generation) {
    if (generation !== state.generation || !requireOwner() || result.phase === 'discarded') return
    if (result.phase === 'forbidden') { clear('ACCESS_DENIED'); return }
    state.phase = result.phase
    if (result.phase === 'succeeded') {
      state.receipt = result.result; state.phase = 'saved'; state.stage = 'view'; state.draft = null; state.draftBase = null; state.reason = ''; state.preview = null; state.operation = null; state.commandError = ''
      state.history = null; state.revision = null; state.historyLoading = false; state.revisionLoading = false
      for (const lane of ['history', 'revision']) { controllers.get(lane)?.abort(); commands.capture(lane) }
      // 回执一旦确认即保留；后续读取失败只允许重读，不能再次执行保存。
      await loadCurrent()
    } else if (result.phase === 'conflict') {
      state.commandError = 'VERSION_CONFLICT'; state.stage = 'conflict'
      await loadCurrent()
    } else if (result.phase === 'invalid') {
      state.commandError = 'COMMAND_REJECTED'; state.stage = 'edit'; resetCommandAndCancelReads()
    } else state.commandError = 'UNKNOWN_OUTCOME'
  }
  async function sendPending(retry) {
    const generation = state.generation, options = { expectedAuth: owner }
    const send = async () => {
      const value = unwrap(await api.command(pendingBody, options))
      if (!value || value.recordId !== pendingBody.recordId || value.contentVersion !== pendingBody.expectedContentVersion + 1 ||
          !Number.isSafeInteger(value.historySequence) || value.historySequence < 1 || value.operation !== pendingBody.operation || !['ACTIVE', 'INVALIDATED'].includes(value.validity)) fail('INVALID_RECEIPT')
      return Object.fromEntries(['recordId', 'contentVersion', 'historySequence', 'validity', 'operation'].map(key => [key, value[key]]))
    }
    state.phase = 'submitting'; state.commandError = ''
    const result = await (retry ? commands.retryIdentical(send) : commands.submit(send))
    await applyResult(result, generation)
  }
  async function submit() {
    if (!requireOwner() || locked.value || state.stage !== 'confirm' || !state.current || !preview()) return false
    if (state.operation === 'CORRECT' ? !state.current.canCorrect : !state.current.canInvalidate) { state.commandError = 'ACTION_UNAVAILABLE'; return false }
    if (state.current.contentVersion !== state.draftBase.record.contentVersion) { state.phase = 'conflict'; state.stage = 'conflict'; return false }
    const { headerPatch, itemPatches } = state.preview
    const body = { reason: state.reason, ...(headerPatch ? { headerPatch } : {}), ...(itemPatches ? { itemPatches } : {}) }
    const command = commands.begin(state.operation, target().recordId, { expectedVersion: state.current.contentVersion, patientId: target().patientId }, body)
    pendingBody = Object.freeze({ commandKey: command.commandKey, recordId: command.target, patientId: command.patientId,
      expectedContentVersion: command.expectedVersion, operation: command.operation, ...command.body })
    if (new TextEncoder().encode(JSON.stringify(pendingBody)).length > 65536) { resetCommandAndCancelReads(); pendingBody = null; state.commandError = 'COMMAND_LIMIT'; return false }
    await sendPending(false); return true
  }
  async function retryCommand() { if (!requireOwner() || state.phase !== 'unknown') return false; await sendPending(true); return true }
  function reconsider() {
    if (!requireOwner() || state.phase !== 'conflict' || !state.current || state.currentLoading) return false
    if (state.operation === 'CORRECT' ? !state.current.canCorrect : !state.current.canInvalidate) { state.commandError = 'ACTION_UNAVAILABLE'; return false }
    try {
      const intended = state.operation === 'CORRECT' ? buildCorrectionPatch(state.draftBase, state.draft) : {}
      const next = createCorrectionDraft(state.current.current)
      Object.assign(next.header, intended.headerPatch || {})
      for (const patch of intended.itemPatches || []) {
        const item = next.items.find(value => value.id === patch.itemId); if (!item) fail('ITEM_CHANGED')
        for (const [field, value] of Object.entries(patch.fields)) {
          if (field === 'reportedFlag') item.flagChoice = value.mode === 'UNKNOWN' ? 'UNKNOWN' : ({ '-1': 'LOW', 0: 'NORMAL', 1: 'HIGH' })[value.value]
          else item[field] = value
        }
        // 新版本必须重新核对名称/代码组合，不能沿用上一次确认。
        item.confirmNameCodePair = false
      }
      resetCommandAndCancelReads(); pendingBody = null; state.draftBase = copy(state.current.current); state.draft = next; state.preview = null; state.phase = 'idle'; state.stage = 'edit'; state.commandError = ''; return true
    } catch (error) { state.commandError = error.message; return false }
  }
  function authChanged() { clear('CONTEXT_CHANGED') }
  function storageChanged(event) { if (event.key == null || [...AUTH_STORAGE_KEYS, 'permissionSession', 'userMenus', 'userMenuNames', 'userRoleCodes', 'currentPatientId'].includes(event.key)) authChanged() }
  function checkContext() { if (owner && !ownsAuth(owner)) clear('CONTEXT_CHANGED') }
  const eventNames = ['auth-session-cleared', 'auth-session-changed', 'permissions-changed', 'current-patient-change']
  for (const event of eventNames) events?.addEventListener?.(event, authChanged)
  events?.addEventListener?.('storage', storageChanged); events?.addEventListener?.('focus', checkContext)
  const timer = events?.setInterval?.(checkContext, 250)
  const stop = watch(() => contextKey(), () => { clear(); open() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { disposed = true; stop(); clear(); for (const event of eventNames) events?.removeEventListener?.(event, authChanged); events?.removeEventListener?.('storage', storageChanged); events?.removeEventListener?.('focus', checkContext); if (timer != null) events.clearInterval(timer) })
  return { state, locked, canClose, dirty, open, loadCurrent, loadHistory, retryHistory, nextHistory, previousHistory, loadRevision, start, preview, editAgain, discard, submit, retryCommand, reconsider, invalidate: authChanged, requireOwner }
}
