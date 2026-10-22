import { reactive, readonly, computed, watch, unref, inject, onMounted, onUnmounted } from 'vue'
import * as api from '@/api/medicalReviewWorkflow'
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache, AUTH_STORAGE_KEYS } from '@/utils/authSession'
import { medicalReviewPresentation } from '@/utils/medicalReviewQualification'
import { presentOriginalFacts } from '@/utils/originalImportFacts'

const CHANNELS = ['SOURCE', 'DOCTOR'], OPERATIONS = ['APPROVE', 'REJECT', 'REQUEST', 'RESUBMIT']
const HEAD = ['state', 'contentVersion', 'decisionVersion', 'cycle', 'operation', 'actorId', 'actorKind', 'occurredAt']
const EVENT = ['channel', 'operation', 'beforeContentVersion', 'contentVersion', 'decisionVersion', 'cycle', 'state', 'actorId', 'actorKind', 'occurredAt']
const RECEIPT = ['recordId', 'channel', 'operation', 'contentVersion', 'decisionVersion', 'cycle', 'state', 'actorId', 'actorKind', 'appliedAt']
const SUMMARY = ['availability', 'participation', 'automaticInputEligible', 'sourceStatus', 'source', 'doctor', 'qualificationSemantics', 'checkedAt']
const SOURCE = ['id', 'patientId', 'userId', 'patientName', 'recordDate', 'recordType', 'hospitalName', 'deptName', 'doctorName', 'remark', 'aiRawResult', 'sourceType', 'sourceExternalId', 'confidenceScore', 'verificationStatus', 'verifiedBy', 'verifiedAt', 'verificationNote', 'createdAt', 'updatedAt', 'contentVersion', 'managed', 'validity', 'sourceStatus', 'items', 'attachments', 'originalImport']
const ITEM = ['id', 'recordId', 'itemName', 'itemCode', 'resultValue', 'unit', 'referenceRange', 'isAbnormal', 'aiConfidence', 'createdAt']
const ATTACHMENT = ['id', 'recordId', 'fileName', 'fileType', 'fileSize', 'createdAt']
const key = channel => channel === 'SOURCE' ? 'source' : 'doctor'
const positive = value => Number.isSafeInteger(value) && value > 0
const nonnegative = value => Number.isSafeInteger(value) && value >= 0
const nullableId = value => value === null || positive(value)
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value))
const exact = (value, fields) => object(value) && Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
const assert = value => { if (!value) throw new Error('INVALID_RESPONSE') }
const copy = value => value === null || typeof value !== 'object' ? value : Array.isArray(value) ? Object.freeze(value.map(copy)) : Object.freeze(Object.fromEntries(Object.keys(value).map(k => [k, copy(value[k])])))
const attempt = fn => { try { return fn() } catch { return null } }
const cursor = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,320}$/.test(value)
const fingerprint = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const nullableNumber = value => value === null || typeof value === 'number' && Number.isFinite(value)
const unicode = value => typeof value === 'string' && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?:^|[^\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value)
const nullableString = value => value === null || unicode(value)
const blank = value => /^[\u0009-\u000D\u001C-\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$/u.test(value)
const bytes = value => new TextEncoder().encode(value).length
function date(value) {
  const m = typeof value === 'string' && value.match(/^([1-9]\d{3})-(\d{2})-(\d{2})$/)
  if (!m) return false
  const y = +m[1], mo = +m[2], d = +m[3], days = [31, y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return mo >= 1 && mo <= 12 && d >= 1 && d <= days[mo - 1]
}
function wall(value) {
  const m = typeof value === 'string' && value.match(/^([1-9]\d{3}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})\.\d{6}$/)
  return !!m && date(m[1]) && +m[2] < 24 && +m[3] < 60 && +m[4] < 60
}
function sourceTime(value) {
  const m = typeof value === 'string' && value.match(/^([1-9]\d{3}-\d{2}-\d{2}) (\d{2}):(\d{2}):(\d{2})$/)
  return !!m && date(m[1]) && +m[2] < 24 && +m[3] < 60 && +m[4] < 60
}
export function validateReviewText(operation, text) {
  if (operation === 'REQUEST') return text === null
  if (operation === 'APPROVE' && text === null) return true
  return ['APPROVE', 'REJECT', 'RESUBMIT'].includes(operation) && unicode(text) && [...text].length <= 1000 && bytes(text) <= 4000 && (operation === 'APPROVE' || !blank(text))
}
function fact(value, max) {
  assert(object(value))
  if (['MISSING', 'NULL'].includes(value.state)) assert(exact(value, ['state']))
  else assert(exact(value, ['state', 'supplied']) && value.state === 'STRING' && unicode(value.supplied) && value.supplied.length <= max)
}
function decimalIdentity(text) {
  const m = text.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '').match(/^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[Ee]([+-]?\d+))?$/)
  assert(m)
  const fraction = m[3] ?? m[4] ?? '', exponent = BigInt(m[5] || '0')
  assert(exponent >= -2147483647n && exponent <= 2147483647n)
  let digits = ((m[2] || '') + fraction).replace(/^0+/, '')
  if (!digits) return '0'
  const tail = digits.match(/0*$/)[0].length; digits = digits.slice(0, digits.length - tail)
  return `${m[1] === '-' ? '-' : ''}${digits}:${exponent - BigInt(fraction.length) + BigInt(tail)}`
}
function original(value, sourceType) {
  if (exact(value, ['status']) && value.status === 'NOT_RETAINED') return
  assert(exact(value, ['status', 'facts']) && value.status === 'RETAINED')
  const f = value.facts
  assert(exact(f, ['version', 'format', 'kind', 'timestamps', 'selectedTimestamp', 'basis', 'readings']) && f.version === 1 && f.format === 'FHIR' && sourceType === 'FHIR' && f.kind === 'LAB' && bytes(JSON.stringify(f)) <= 4096)
  const times = ['effectiveDateTime', 'issued', 'effectiveInstant']
  assert(exact(f.timestamps, times) && times.includes(f.selectedTimestamp))
  times.forEach(name => fact(f.timestamps[name], 32))
  assert(f.timestamps[f.selectedTimestamp].state === 'STRING' && times.find(name => f.timestamps[name].state === 'STRING') === f.selectedTimestamp)
  let selectedBasis = null
  for (const name of times) {
    if (f.timestamps[name].state !== 'STRING') continue
    const m = f.timestamps[name].supplied.match(/^([1-9]\d{3}-\d{2}-\d{2})(?:T(\d{2}:\d{2}(?::\d{2})?)(?:\.(\d{1,6}))?(Z|[+-]\d{2}:\d{2})?)?$/)
    assert(m && date(m[1]) && (!m[2] || +m[2].slice(0, 2) < 24 && +m[2].slice(3, 5) < 60 && (m[2].length === 5 || +m[2].slice(6, 8) < 60)) && (!m[3] || m[2].length === 8))
    const offset = m[4] ?? null
    if (offset && offset !== 'Z') assert(offset !== '-00:00' && +offset.slice(1, 3) <= 14 && +offset.slice(4) < 60 && (+offset.slice(1, 3) !== 14 || +offset.slice(4) === 0))
    if (name === f.selectedTimestamp) selectedBasis = { localDate: m[1], localTime: m[2] ? m[2] + (m[3] ? '.' + m[3] : '') : null, precision: !m[2] ? 'DATE' : m[3] ? 'FRACTION' : m[2].length === 5 ? 'MINUTE' : 'SECOND', fractionDigits: m[3]?.length ?? 0, offsetState: offset === null ? 'NOT_SUPPLIED' : 'EXPLICIT', offset }
  }
  const b = f.basis
  assert(exact(b, ['localDate', 'localTime', 'precision', 'fractionDigits', 'offsetState', 'offset']) && date(b.localDate) && nullableString(b.localTime) && ['DATE', 'MINUTE', 'SECOND', 'FRACTION'].includes(b.precision) && nonnegative(b.fractionDigits) && b.fractionDigits <= 6)
  assert(Object.keys(selectedBasis).every(field => selectedBasis[field] === b[field]))
  assert(b.offsetState === 'NOT_SUPPLIED' ? b.offset === null : b.offsetState === 'EXPLICIT' && typeof b.offset === 'string' && /^(Z|[+-]\d{2}:\d{2})$/.test(b.offset))
  assert(Array.isArray(f.readings) && f.readings.length === 1)
  const r = f.readings[0]
  assert(exact(r, ['metric', 'field', 'value', 'unit', 'code', 'display', 'displayField']) && r.metric === 'LAB_SCALAR' && ['FHIR_QUANTITY_VALUE', 'FHIR_VALUE_STRING'].includes(r.field) && ['FIRST_CODING_DISPLAY', 'CODE_TEXT', 'NOT_SUPPLIED'].includes(r.displayField))
  fact(r.unit, 50); fact(r.code, 50); fact(r.display, 200)
  assert(r.displayField === 'NOT_SUPPLIED' ? r.display.state === 'MISSING' : r.display.state === 'STRING' && r.display.supplied.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '') !== '')
  const v = r.value
  assert(object(v) && (r.field === 'FHIR_VALUE_STRING' ? v.type === 'TEXT' && r.unit.state === 'MISSING' : ['JSON_NUMBER', 'NUMERIC_STRING'].includes(v.type)))
  if (v.type === 'TEXT') assert(exact(v, ['type', 'supplied']) && unicode(v.supplied) && v.supplied.length <= 500)
  else { assert(exact(v, v.type === 'JSON_NUMBER' ? ['type', 'exact'] : ['type', 'exact', 'supplied']) && ['JSON_NUMBER', 'NUMERIC_STRING'].includes(v.type) && unicode(v.exact) && v.exact.length <= 256 && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?$/.test(v.exact)); if (v.type === 'NUMERIC_STRING') assert(unicode(v.supplied) && v.supplied.length <= 256 && decimalIdentity(v.supplied) === decimalIdentity(v.exact)); else decimalIdentity(v.exact) }
  assert(presentOriginalFacts(value).state === 'retained')
}
function sourceRecord(r, target) {
  assert(exact(r, SOURCE) && r.id === target.recordId && r.patientId === target.patientId && positive(r.contentVersion) && nullableId(r.userId) && nullableId(r.verifiedBy))
  for (const field of ['patientName', 'recordType', 'hospitalName', 'deptName', 'doctorName', 'remark', 'aiRawResult', 'sourceType', 'sourceExternalId', 'verificationStatus', 'verificationNote']) assert(nullableString(r[field]))
  assert((r.recordDate === null || date(r.recordDate)) && nullableNumber(r.confidenceScore) && typeof r.managed === 'boolean' && ['ACTIVE', 'INVALIDATED', 'UNTRACKED'].includes(r.validity))
  for (const field of ['verifiedAt', 'createdAt', 'updatedAt']) assert(r[field] === null || sourceTime(r[field]))
  for (const [field, fields] of [['items', ITEM], ['attachments', ATTACHMENT]]) {
    assert(Array.isArray(r[field]) && r[field].length <= 100)
    const ids = new Set()
    for (const row of r[field]) {
      assert(exact(row, fields) && positive(row.id) && !ids.has(row.id) && row.recordId === r.id && (row.createdAt === null || sourceTime(row.createdAt))); ids.add(row.id)
      if (field === 'items') { for (const f of ['itemName', 'itemCode', 'resultValue', 'unit', 'referenceRange']) assert(nullableString(row[f])); assert((row.isAbnormal === null || [-1, 0, 1].includes(row.isAbnormal)) && nullableNumber(row.aiConfidence)) }
      else assert(nullableString(row.fileName) && nullableString(row.fileType) && (row.fileSize === null || nonnegative(row.fileSize)))
    }
  }
  original(r.originalImport, r.sourceType)
}
function success(response) { assert(object(response) && response.code === 200 && object(response.data)); return response.data }
export function validateCurrent(response, target) {
  return attempt(() => {
    assert(positive(target.recordId) && nullableId(target.patientId))
    const d = success(response)
    assert(exact(d, ['contractVersion', 'recordId', 'patientId', 'sourceRecord', 'reviewSummary', 'newCommandsEnabled', 'preparation', 'channels']) && d.contractVersion === 1 && d.recordId === target.recordId && d.patientId === target.patientId)
    sourceRecord(d.sourceRecord, target)
    const p = d.preparation, s = d.reviewSummary
    assert(exact(p, ['expectedContentVersion', 'contentFingerprint', 'initialLegacyFingerprint', 'lifecycleFingerprint']) && p.expectedContentVersion === d.sourceRecord.contentVersion && fingerprint(p.contentFingerprint) && fingerprint(p.lifecycleFingerprint))
    assert(exact(s, SUMMARY) && s.availability === 'AVAILABLE' && s.qualificationSemantics === 'CURRENT_CHECK' && ['NONE', 'PARTICIPANT'].includes(medicalReviewPresentation(s).mode) && s.sourceStatus === d.sourceRecord.sourceStatus)
    assert(s.source === null ? fingerprint(p.initialLegacyFingerprint) : p.initialLegacyFingerprint === null)
    assert(typeof d.newCommandsEnabled === 'boolean' && exact(d.channels, ['source', 'doctor']))
    for (const channel of CHANNELS) {
      const head = s[key(channel)], c = d.channels[key(channel)]
      assert(exact(c, ['expectedDecisionVersion', 'allowedOperations', 'canReadText', 'historyCursor']) && nonnegative(c.expectedDecisionVersion) && typeof c.canReadText === 'boolean')
      if (head === null) assert(c.expectedDecisionVersion === 0 && c.historyCursor === null)
      else assert(exact(head, HEAD) && head.contentVersion === d.sourceRecord.contentVersion && head.contentVersion === p.expectedContentVersion && head.decisionVersion === c.expectedDecisionVersion && cursor(c.historyCursor))
      let choices = head === null ? channel === 'SOURCE' ? ['APPROVE', 'REJECT'] : ['REQUEST'] : head.state === 'PENDING' ? ['APPROVE', 'REJECT'] : ['RESUBMIT']
      if (!d.newCommandsEnabled || c.expectedDecisionVersion === Number.MAX_SAFE_INTEGER || choices[0] === 'RESUBMIT' && head.cycle === Number.MAX_SAFE_INTEGER) choices = []
      if (d.sourceRecord.validity === 'INVALIDATED') choices = choices.filter(op => op === 'REJECT')
      assert(Array.isArray(c.allowedOperations) && c.allowedOperations.every((op, i, all) => choices.includes(op) && all.indexOf(op) === i && (i === 0 || choices.indexOf(all[i - 1]) < choices.indexOf(op))))
      assert(c.allowedOperations.length === 0 || c.canReadText)
      if (channel === 'DOCTOR' && c.allowedOperations.some(op => ['REQUEST', 'RESUBMIT'].includes(op))) assert(d.channels.source.canReadText)
      if (channel === 'DOCTOR' && target.patientId === null) assert(head === null && !c.canReadText && c.allowedOperations.length === 0)
    }
    return copy(d)
  })
}
function event(e, channel) {
  assert(exact(e, EVENT) && e.channel === channel && [e.contentVersion, e.decisionVersion, e.cycle, e.actorId].every(positive) && e.cycle <= e.decisionVersion && wall(e.occurredAt) && ['OWNER', 'DELEGATE', 'ADMIN', 'ASSIGNED_DOCTOR'].includes(e.actorKind))
  const op = e.operation, decision = ['APPROVE', 'REJECT'].includes(op)
  assert(e.state === ({ APPROVE: 'APPROVED', REJECT: 'REJECTED', REQUEST: 'PENDING', RESUBMIT: 'PENDING', CONTENT_CHANGED: 'SUPERSEDED' })[op])
  assert(e.decisionVersion === 1 ? e.beforeContentVersion === null : positive(e.beforeContentVersion) && e.contentVersion === e.beforeContentVersion + (op === 'CONTENT_CHANGED' ? 1 : 0))
  if (op === 'CONTENT_CHANGED') assert(e.decisionVersion > 1)
  assert(e.decisionVersion !== 1 || e.cycle === 1)
  if (op === 'REQUEST') assert(channel === 'DOCTOR' && e.decisionVersion === 1 && e.cycle === 1)
  if (op === 'RESUBMIT') assert(e.decisionVersion > 1 && e.cycle > 1)
  if (channel === 'DOCTOR' && decision) assert(e.decisionVersion > 1 && ['ADMIN', 'ASSIGNED_DOCTOR'].includes(e.actorKind))
  if (channel === 'SOURCE') assert(op !== 'REQUEST')
}
export function validateHistory(response, target, channel, selector = null) {
  return attempt(() => {
    const d = success(response)
    assert(CHANNELS.includes(channel) && exact(d, ['contractVersion', 'recordId', 'patientId', 'channel', 'highWaterDecisionVersion', 'entries', 'pageCursor', 'nextCursor']) && d.contractVersion === 1 && d.recordId === target.recordId && d.patientId === target.patientId && d.channel === channel && nonnegative(d.highWaterDecisionVersion) && Array.isArray(d.entries) && d.entries.length <= 20)
    const h = d.highWaterDecisionVersion
    if (h === 0) assert(selector === null && d.entries.length === 0 && d.pageCursor === null && d.nextCursor === null)
    else {
      assert(d.entries.length > 0 && cursor(d.pageCursor) && (d.nextCursor === null || cursor(d.nextCursor)))
      if (selector !== null) assert(d.pageCursor === selector.cursor && h === selector.highWater && positive(selector.expectedUpper))
      d.entries.forEach((e, i) => {
        event(e, channel); assert(e.decisionVersion <= h && e.decisionVersion === (selector === null ? h : selector.expectedUpper) - i)
        if (i > 0) {
          const newer = d.entries[i - 1]
          assert(newer.beforeContentVersion === e.contentVersion)
          if (newer.operation === 'CONTENT_CHANGED') assert(newer.contentVersion === e.contentVersion + 1 && newer.cycle === e.cycle)
          else if (newer.operation === 'RESUBMIT') assert(['APPROVED', 'REJECTED', 'SUPERSEDED'].includes(e.state) && newer.contentVersion === e.contentVersion && newer.cycle === e.cycle + 1)
          else assert(['APPROVE', 'REJECT'].includes(newer.operation) && e.state === 'PENDING' && newer.contentVersion === e.contentVersion && newer.cycle === e.cycle)
        }
      })
      const last = d.entries[d.entries.length - 1].decisionVersion
      assert((d.nextCursor === null) === (last === 1))
      if (d.nextCursor !== null) assert(d.entries.length === 20 && last > 1 && d.nextCursor !== d.pageCursor)
    }
    return copy(d)
  })
}
export function validateDetail(response, target, channel, selected) {
  return attempt(() => {
    const d = success(response)
    assert(exact(d, ['contractVersion', 'recordId', 'patientId', 'channel', 'event', 'text']) && d.contractVersion === 1 && d.recordId === target.recordId && d.patientId === target.patientId && d.channel === channel)
    event(d.event, channel); assert(EVENT.every(f => d.event[f] === selected[f]))
    const op = selected.operation
    if (['REQUEST', 'CONTENT_CHANGED'].includes(op)) assert(d.text === null)
    else if (op === 'APPROVE' && d.text === null) { /* Explicit absence, not an empty note. */ }
    else assert(exact(d.text, ['type', 'value']) && d.text.type === ({ APPROVE: 'REVIEW_NOTE', REJECT: 'REJECTION_REASON', RESUBMIT: 'RESUBMISSION_REASON' })[op] && validateReviewText(op, d.text.value))
    return copy(d)
  })
}
export function validateReceipt(response, frozen, reconciliation = false) {
  return attempt(() => {
    const d = success(response)
    assert(exact(d, reconciliation ? ['contractVersion', 'status', 'receipt'] : ['contractVersion', 'receipt']) && d.contractVersion === 1)
    if (reconciliation) { assert(['FOUND', 'UNKNOWN'].includes(d.status)); if (d.status === 'UNKNOWN') { assert(d.receipt === null); return Object.freeze({ status: 'UNKNOWN', receipt: null }) } }
    const r = d.receipt
    assert(CHANNELS.includes(r?.channel))
    assert(exact(r, RECEIPT) && [r.recordId, r.contentVersion, r.decisionVersion, r.cycle, r.actorId].every(positive) && r.recordId === frozen.recordId && r.channel === frozen.channel && r.operation === frozen.operation && r.actorId === frozen.actorId && r.contentVersion === frozen.expectedContentVersion && r.decisionVersion === frozen.expectedDecisionVersion + 1 && r.cycle === (frozen.baseCycle || 1) + (r.operation === 'RESUBMIT' ? 1 : 0) && wall(r.appliedAt))
    assert(OPERATIONS.includes(r.operation) && r.state === ({ APPROVE: 'APPROVED', REJECT: 'REJECTED', REQUEST: 'PENDING', RESUBMIT: 'PENDING' })[r.operation] && ['OWNER', 'DELEGATE', 'ADMIN', 'ASSIGNED_DOCTOR'].includes(r.actorKind))
    if (r.channel === 'DOCTOR' && ['APPROVE', 'REJECT'].includes(r.operation)) assert(['ADMIN', 'ASSIGNED_DOCTOR'].includes(r.actorKind) && r.decisionVersion > 1)
    if (r.operation === 'REQUEST') assert(r.channel === 'DOCTOR' && r.decisionVersion === 1 && r.cycle === 1)
    return Object.freeze({ status: 'FOUND', receipt: copy(r) })
  })
}
export function buildReviewBody(base, commandKey) {
  return attempt(() => {
    assert(CHANNELS.includes(base.channel) && OPERATIONS.includes(base.operation) && validateReviewText(base.operation, base.text) && typeof commandKey === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(commandKey))
    assert(positive(base.expectedContentVersion) && nonnegative(base.expectedDecisionVersion) && fingerprint(base.contentFingerprint) && fingerprint(base.lifecycleFingerprint))
    const initial = base.channel === 'SOURCE' && base.expectedDecisionVersion === 0 ? base.initialLegacyFingerprint : null
    assert(initial === null ? !(base.channel === 'SOURCE' && base.expectedDecisionVersion === 0) : fingerprint(initial))
    const body = JSON.stringify({ operation: base.operation, commandKey, expectedContentVersion: base.expectedContentVersion, expectedDecisionVersion: base.expectedDecisionVersion,
      contentFingerprint: base.contentFingerprint, initialLegacyFingerprint: initial, lifecycleFingerprint: base.lifecycleFingerprint, acknowledged: true, text: base.text })
    assert(bytes(body) <= 16384); return body
  })
}

const CONFLICTS = new Set(['MEDICAL_REVIEW_CONTENT_CONFLICT', 'MEDICAL_REVIEW_DECISION_CONFLICT', 'MEDICAL_REVIEW_LEGACY_CONFLICT', 'MEDICAL_REVIEW_LIFECYCLE_CONFLICT', 'MEDICAL_REVIEW_TRANSITION_CONFLICT', 'MEDICAL_REVIEW_DISABLED', 'MEDICAL_RECORD_INVALIDATED', 'MEDICAL_REVIEW_VERSION_EXHAUSTED', 'MEDICAL_REVIEW_CYCLE_EXHAUSTED'])
const PREPARATION_FAILURES = new Map([['CURRENT_RESULT_UNAVAILABLE', 409], ['MEDICAL_REVIEW_INTEGRITY', 409], ['MEDICAL_RECORD_INTEGRITY', 409], ['MEDICAL_REVIEW_SCHEMA_NOT_READY', 503], ['MEDICAL_RECORD_SCHEMA_NOT_READY', 503]])
const BOUNDARY = new Map([[400, 'MEDICAL_REVIEW_INVALID_REQUEST'], [413, 'MEDICAL_REVIEW_LIMIT'], [405, 'MEDICAL_REVIEW_METHOD_NOT_ALLOWED'], [415, 'MEDICAL_REVIEW_UNSUPPORTED_MEDIA_TYPE']])
function failure(error) {
  const status = Number(error?.response?.status ?? error?.code), code = error?.response?.data?.data?.errorCode ?? error?.data?.errorCode
  return { status: Number.isInteger(status) ? status : 0, code: typeof code === 'string' ? code : '' }
}
function responseFailure(response) { return { code: response?.code, data: response?.data } }

// All protected values are per-dialog. The serialized replay envelope never enters reactive state.
export function useMedicalReviewWorkflow(target, context, options = {}) {
  const menus = inject('userMenus', null)
  const state = reactive({ generation: 0, retired: false, phase: 'view', channel: options.initialChannel === 'DOCTOR' ? 'DOCTOR' : 'SOURCE', current: null, currentLoading: false, currentError: '',
    history: null, historyLoading: false, historyError: '', hasPrevious: false, historyDepth: 0, detail: null, detailLoading: false, detailError: '', selectedVersion: null,
    operation: null, draft: '', noteEnabled: false, confirmation: null, acknowledged: false, validation: '', receipt: null, pendingSummary: null })
  let owner = null, disposed = false, base = null, confirmed = null, confirmationRevision = 0, envelope = null, frozen = null, uncertainty = false, commandController = null, commandTicket = 0
  let selectors = [], pageSelector = null, historyRetry = null
  const lanes = Object.fromEntries(['current', 'history', 'detail'].map(name => [name, { serial: 0, controller: null }]))
  const value = input => typeof input === 'function' ? input() : unref(input)
  const contextKey = () => JSON.stringify([value(target), value(context), unref(menus)])
  function capture() {
    const auth = captureAuthSession(), actor = localStorage.getItem('userId'), binding = value(target)
    if (!auth.token || typeof actor !== 'string' || !/^[1-9]\d*$/.test(actor) || !positive(Number(actor)) || !positive(binding?.recordId) || !nullableId(binding?.patientId)) return null
    return { auth: { ...auth, actorId: actor }, actorId: Number(actor), target: { recordId: binding.recordId, patientId: binding.patientId }, context: contextKey(), permission: JSON.stringify(readPermissionCache()) }
  }
  function matches() { return !!owner && !disposed && !state.retired && isAuthSessionCurrent(owner.auth) && owner.auth.actorId === localStorage.getItem('userId') && owner.permission === JSON.stringify(readPermissionCache()) && owner.context === contextKey() }
  function abort(name) { const lane = lanes[name]; lane.serial++; lane.controller?.abort(); lane.controller = null; state[`${name}Loading`] = false }
  function clearConfirmation() { confirmed = null; confirmationRevision++; state.confirmation = null; state.acknowledged = false }
  function clearDraft() { clearConfirmation(); base = null; state.operation = null; state.draft = ''; state.noteEnabled = false; state.validation = '' }
  function clearText() { abort('detail'); state.detail = null; state.detailError = ''; state.selectedVersion = null }
  function clearHistory() { abort('history'); clearText(); state.history = null; state.historyError = ''; selectors = []; pageSelector = null; historyRetry = null; state.hasPrevious = false; state.historyDepth = 0 }
  function invalidatePreparation(error) {
    abort('current'); clearHistory(); clearDraft(); state.current = null; state.currentError = error; state.phase = state.receipt ? 'saved' : 'view'
  }
  function retire(reason = 'CONTEXT_CHANGED') {
    if (state.retired) return
    state.generation++; state.retired = true; commandTicket++; commandController?.abort(); commandController = null
    abort('current'); clearHistory(); clearDraft(); state.current = null; state.currentError = ''; state.receipt = null; state.pendingSummary = null; envelope = null; frozen = null; uncertainty = false; state.phase = 'view'; owner = null
    options.onRetired?.(reason)
  }
  function requireOwner() { if (!matches()) { retire(); return false } return true }
  const locked = computed(() => ['submitting', 'unknown', 'reconciling'].includes(state.phase))
  const dirty = computed(() => locked.value || ['editing', 'confirming'].includes(state.phase))
  const allowedOperations = computed(() => state.current && !locked.value && !state.retired ? state.current.channels[key(state.channel)].allowedOperations : [])
  const canReadText = computed(() => !!state.current?.channels[key(state.channel)].canReadText && !locked.value && !state.retired)
  const canNext = computed(() => !!state.history?.nextCursor && !state.historyLoading && !locked.value)
  function beginLane(name) { abort(name); const lane = lanes[name]; lane.controller = new AbortController(); state[`${name}Loading`] = true; state[`${name}Error`] = ''; return { serial: lane.serial, epoch: state.generation, controller: lane.controller } }
  function ownLane(name, ticket) { return ticket.epoch === state.generation && ticket.serial === lanes[name].serial && !ticket.controller.signal.aborted && requireOwner() }
  function readError(error, lane) {
    const { status, code } = failure(error)
    if (status === 401 || status === 403) { retire('ACCESS_CHANGED'); return }
    if (status === 409 && code === 'MEDICAL_REVIEW_HISTORY_CHANGED') { retire('HISTORY_CHANGED'); return }
    if (PREPARATION_FAILURES.get(code) === status) { invalidatePreparation('READ_UNAVAILABLE'); state[`${lane}Error`] = 'READ_UNAVAILABLE'; return }
    state[`${lane}Error`] = lane === 'detail' && code === 'MEDICAL_REVIEW_EVENT_UNAVAILABLE' ? 'EVENT_UNAVAILABLE' : status === 404 ? 'WORKFLOW_UNAVAILABLE' : 'READ_UNAVAILABLE'
  }
  async function loadCurrent() {
    if (!requireOwner() || locked.value) return false
    clearDraft(); clearHistory(); state.current = null
    if (!state.receipt) state.phase = 'view'
    const ticket = beginLane('current'), target = owner.target
    try {
      const response = await api.current(target.recordId, target.patientId, { expectedAuth: owner.auth, signal: ticket.controller.signal })
      if (!ownLane('current', ticket)) return false
      if (response?.code !== 200) { readError(responseFailure(response), 'current'); return false }
      const current = validateCurrent(response, target)
      if (!current) { invalidatePreparation('INVALID_RESPONSE'); return false }
      state.current = current; return true
    } catch (error) { if (ownLane('current', ticket)) readError(error, 'current'); return false }
    finally { if (ownLane('current', ticket)) state.currentLoading = false }
  }
  function selectChannel(channel) {
    if (!requireOwner() || locked.value || !CHANNELS.includes(channel) || channel === 'DOCTOR' && owner.target.patientId === null) return false
    clearDraft(); clearHistory(); state.channel = channel; state.phase = state.receipt ? 'saved' : 'view'; return true
  }
  async function readPage(selection, nextStack) {
    if (!requireOwner() || locked.value || !state.current || state.channel === 'DOCTOR' && owner.target.patientId === null) return false
    clearText(); state.history = null; historyRetry = { selection, nextStack }; const ticket = beginLane('history'), channel = state.channel, binding = owner.target
    try {
      const response = await api.history(binding.recordId, binding.patientId, channel, selection?.cursor ?? null, { expectedAuth: owner.auth, signal: ticket.controller.signal })
      if (!ownLane('history', ticket) || channel !== state.channel) return false
      if (response?.code !== 200) { readError(responseFailure(response), 'history'); return false }
      const page = validateHistory(response, binding, channel, selection)
      if (!page) { invalidatePreparation('INVALID_RESPONSE'); state.historyError = 'INVALID_RESPONSE'; return false }
      pageSelector = page.highWaterDecisionVersion === 0 ? null : { cursor: page.pageCursor, expectedUpper: page.entries[0].decisionVersion, highWater: page.highWaterDecisionVersion }
      selectors = nextStack; historyRetry = null; state.hasPrevious = selectors.length > 0; state.historyDepth = selectors.length; state.history = page; return true
    } catch (error) { if (ownLane('history', ticket)) readError(error, 'history'); return false }
    finally { if (ownLane('history', ticket)) state.historyLoading = false }
  }
  function loadHistory() { if (!requireOwner() || locked.value) return false; clearHistory(); return readPage(null, []) }
  function nextHistory() {
    if (!requireOwner() || !canNext.value || !pageSelector) return false
    const page = state.history, selection = { cursor: page.nextCursor, expectedUpper: page.entries[page.entries.length - 1].decisionVersion - 1, highWater: page.highWaterDecisionVersion }
    return readPage(selection, [...selectors, pageSelector].slice(-50))
  }
  function previousHistory() { if (!requireOwner() || locked.value || state.historyLoading || !selectors.length) return false; return readPage(selectors[selectors.length - 1], selectors.slice(0, -1)) }
  function retryHistory() { return historyRetry ? readPage(historyRetry.selection, historyRetry.nextStack) : loadHistory() }
  async function loadDetail(entry) {
    if (!requireOwner() || !canReadText.value || !state.history?.entries.includes(entry) || !state.history.pageCursor) return false
    const selected = copy(entry), channel = state.channel, page = state.history.pageCursor, binding = owner.target
    clearText(); state.selectedVersion = selected.decisionVersion; const ticket = beginLane('detail')
    try {
      const response = await api.detail(binding.recordId, binding.patientId, channel, selected.decisionVersion, page, { expectedAuth: owner.auth, signal: ticket.controller.signal })
      if (!ownLane('detail', ticket) || channel !== state.channel || state.history?.pageCursor !== page || state.selectedVersion !== selected.decisionVersion) return false
      if (response?.code !== 200) { readError(responseFailure(response), 'detail'); return false }
      const detail = validateDetail(response, binding, channel, selected)
      if (!detail) { invalidatePreparation('INVALID_RESPONSE'); state.detailError = 'INVALID_RESPONSE'; return false }
      state.detail = detail; return true
    } catch (error) { if (ownLane('detail', ticket)) readError(error, 'detail'); return false }
    finally { if (ownLane('detail', ticket)) state.detailLoading = false }
  }
  function start(operation) {
    if (!requireOwner() || locked.value || !allowedOperations.value.includes(operation) || state.currentLoading) return false
    clearDraft(); clearText(); state.receipt = null
    const current = state.current, head = current.reviewSummary[key(state.channel)]
    base = Object.freeze({ current, channel: state.channel, operation, actorId: owner.actorId, recordId: owner.target.recordId, baseCycle: head?.cycle ?? 0, expectedDecisionVersion: current.channels[key(state.channel)].expectedDecisionVersion, ...current.preparation })
    state.operation = operation; state.phase = 'editing'; return true
  }
  function editable() { return requireOwner() && !locked.value && !!base && ['editing', 'confirming'].includes(state.phase) }
  function setText(text) { if (!editable() || typeof text !== 'string') return false; clearConfirmation(); state.draft = text; state.phase = 'editing'; state.validation = ''; return true }
  function setNoteEnabled(enabled) { if (!editable() || base.operation !== 'APPROVE' || typeof enabled !== 'boolean') return false; clearConfirmation(); state.noteEnabled = enabled; state.phase = 'editing'; state.validation = ''; return true }
  const draftText = () => base?.operation === 'REQUEST' || base?.operation === 'APPROVE' && !state.noteEnabled ? null : state.draft
  function confirm() {
    if (!editable() || base.current !== state.current || state.phase !== 'editing') return false
    const text = draftText()
    if (!validateReviewText(base.operation, text)) { state.validation = 'INVALID_TEXT'; return false }
    clearConfirmation(); confirmed = Object.freeze({ ...base, text, noteEnabled: state.noteEnabled, revision: confirmationRevision, epoch: state.generation })
    state.confirmation = copy({ channel: base.channel, operation: base.operation, expectedContentVersion: base.expectedContentVersion, expectedDecisionVersion: base.expectedDecisionVersion, text, noteEnabled: state.noteEnabled, source: base.current.reviewSummary.source, doctor: base.current.reviewSummary.doctor })
    state.phase = 'confirming'; return true
  }
  function backToEdit() { if (!editable()) return false; clearConfirmation(); state.phase = 'editing'; return true }
  function acknowledge(checked) { if (!requireOwner() || state.phase !== 'confirming' || !confirmed || typeof checked !== 'boolean') return false; state.acknowledged = checked; return true }
  function uncertain() { uncertainty = true; state.phase = 'unknown'; state.validation = 'RESULT_UNKNOWN' }
  function commandError(error, reconciliation) {
    const { status, code } = failure(error)
    if (status === 401 || status === 403) { retire('ACCESS_CHANGED'); return }
    if (reconciliation || uncertainty) { uncertain(); return }
    if (status === 409 && CONFLICTS.has(code)) { state.phase = 'conflict'; state.validation = 'CONFLICT'; envelope = null; frozen = null; state.pendingSummary = null; state.current = null; return }
    if (BOUNDARY.get(status) === code) { state.phase = 'rejected'; state.validation = 'REJECTED'; envelope = null; frozen = null; state.pendingSummary = null; state.current = null; return }
    uncertain()
  }
  async function transmit(reconciliation = false) {
    if (!requireOwner() || !envelope || !frozen || commandController) return false
    const body = envelope, receiptBinding = frozen, epoch = state.generation, ticket = ++commandTicket
    commandController = new AbortController(); const controller = commandController, auth = owner.auth
    state.phase = reconciliation ? 'reconciling' : 'submitting'; state.validation = ''
    const owns = () => ticket === commandTicket && epoch === state.generation && !controller.signal.aborted && requireOwner()
    let saved = false
    try {
      const response = await (reconciliation ? api.receipt : api.command)(receiptBinding.recordId, receiptBinding.channel, body, { expectedAuth: auth, signal: controller.signal })
      if (!owns()) return false
      if (response?.code !== 200) { commandError(responseFailure(response), reconciliation); return false }
      const result = validateReceipt(response, receiptBinding, reconciliation)
      if (!result || result.status === 'UNKNOWN') { uncertain(); return false }
      envelope = null; frozen = null; state.pendingSummary = null; uncertainty = false; clearDraft(); state.current = null; state.receipt = result.receipt; state.phase = 'saved'; saved = true
      options.onChanged?.(Object.freeze({ recordId: result.receipt.recordId, channel: result.receipt.channel, decisionVersion: result.receipt.decisionVersion }))
      return true
    } catch (error) { if (owns()) commandError(error, reconciliation); return false }
    finally {
      if (owns()) { commandController = null; if (saved) void loadCurrent() }
    }
  }
  function submit() {
    if (!requireOwner() || locked.value || !confirmed || state.phase !== 'confirming' || !state.acknowledged || confirmed.revision !== confirmationRevision || confirmed.epoch !== state.generation || confirmed.current !== state.current || confirmed.text !== draftText() || confirmed.noteEnabled !== state.noteEnabled) return false
    let commandKey
    try { commandKey = globalThis.crypto?.randomUUID?.() } catch { /* Crypto failure must not fall back to a weak key. */ }
    const body = buildReviewBody(confirmed, commandKey)
    if (!body) { state.validation = 'PREPARATION_UNAVAILABLE'; return false }
    frozen = Object.freeze({ recordId: confirmed.recordId, actorId: confirmed.actorId, channel: confirmed.channel, operation: confirmed.operation, expectedContentVersion: confirmed.expectedContentVersion, expectedDecisionVersion: confirmed.expectedDecisionVersion, baseCycle: confirmed.baseCycle })
    state.pendingSummary = copy({ channel: frozen.channel, operation: frozen.operation, contentVersion: frozen.expectedContentVersion, decisionVersion: frozen.expectedDecisionVersion });
    envelope = body; uncertainty = false; abort('current'); clearHistory(); clearDraft(); state.current = null
    return transmit(false)
  }
  function checkReceipt() { if (state.phase !== 'unknown') return false; return transmit(true) }
  function retrySameRequest() { if (state.phase !== 'unknown') return false; return transmit(false) }
  function discard() { retire('DISMISSED'); return true }
  function checkContext() { if (!state.retired) requireOwner() }
  function storage(event) { if (event.key === null || [...AUTH_STORAGE_KEYS, 'userMenus', 'userMenuNames', 'userRoleCodes', 'permissionSession', 'currentPatientId'].includes(event.key)) retire() }
  const events = ['auth-session-cleared', 'auth-session-changed', 'permissions-changed', 'current-patient-change']
  const forced = () => retire(), hide = () => retire('PAGE_HIDDEN'), show = event => { if (event.persisted) retire('PAGE_HIDDEN') }
  let timer = null
  watch(contextKey, checkContext, { flush: 'sync' })
  onMounted(() => {
    owner = capture()
    if (!owner) { retire(); return }
    events.forEach(event => window.addEventListener(event, forced)); window.addEventListener('storage', storage); window.addEventListener('focus', checkContext); window.addEventListener('pagehide', hide); window.addEventListener('pageshow', show)
    timer = setInterval(checkContext, 250); void loadCurrent()
  })
  onUnmounted(() => {
    events.forEach(event => window.removeEventListener(event, forced)); window.removeEventListener('storage', storage); window.removeEventListener('focus', checkContext); window.removeEventListener('pagehide', hide); window.removeEventListener('pageshow', show)
    if (timer !== null) clearInterval(timer); retire('DISPOSED'); disposed = true
  })
  return { state: readonly(state), locked, dirty, allowedOperations, canReadText, canNext, requireOwner, loadCurrent, selectChannel, loadHistory, nextHistory, previousHistory, retryHistory, loadDetail,
    start, setText, setNoteEnabled, confirm, backToEdit, acknowledge, submit, checkReceipt, retrySameRequest, discard, retire }
}
