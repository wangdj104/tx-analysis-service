import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, nextTick, effectScope } from 'vue'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function consultation(id, extra = {}) {
  return { id, patient_id: id * 10, patient_name: 'Patient ' + id, status: 'OPEN', mode: 'TEXT', can_message: true, can_close: true, messages: [], participants: [], ...extra }
}
function setup(t, overrides = {}, propOverrides = {}) {
  const props = reactive({ patientId: 10, isDoctor: false, enabled: true, locale: 'en', ...propOverrides })
  const route = reactive({ query: { tab: 'consultation' } }), scheduled = []
  const api = {
    listConsultationInbox: async () => ({ data: [] }), listConsultations: async () => ({ data: [] }), listClinicians: async () => ({ data: [] }), listConsultationInvitees: async () => ({ data: [] }),
    getConsultation: async id => ({ data: consultation(id) }), listConsultationRecordOptions: async () => ({ data: [] }),
    sendConsultationMessage: async () => ({ data: {} }), closeConsultation: async id => ({ data: consultation(id, { status: 'CLOSED', can_message: false, can_close: false }) }),
    ...overrides
  }
  const source = fs.readFileSync(new URL('../src/components/ConsultationWorkspace.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace(/import\.meta\.env\.VITE_WEBRTC_ICE_SERVERS/g, "''")
  const deps = {
    computed, nextTick, reactive, ref, watch, defineProps: () => props, onMounted() {}, onBeforeUnmount() {},
    useRoute: () => route, useRouter: () => ({ replace: async target => { route.query = target.query } }),
    localStorage: { getItem: () => '1' }, window: { isSecureContext: false, setTimeout: fn => { scheduled.push(fn); return scheduled.length }, setInterval() {} },
    navigator: {}, ElMessage: { success() {}, warning() {}, error() {} }, ElMessageBox: { prompt: async () => ({ value: '' }), confirm: async () => {} }, api
  }
  const exposed = ['consultations','activeConsultation','draft','recordOptions','canMessage','canSend','canClose','loadConsultations','openConsultation','activateConsultation','refreshActiveConsultation','sendMessage','finishConsultation','resetContext','initialize','sending','listError','openingId','onComposerKeydown']
  const scope = effectScope()
  const view = scope.run(() => new Function(...Object.keys(deps), source + '\nreturn {' + exposed.join(',') + '}')(...Object.values(deps)))
  t.after(() => { view.resetContext(); scope.stop() })
  return { ...view, props, route, scheduled, api }
}

test('doctor inbox loads without any global patient selection', async t => {
  let calls = 0
  const view = setup(t, { listConsultationInbox: async () => { calls++; return { data: [consultation(1)] } }, listConsultations: async () => { throw new Error('must not query global patient') } }, { isDoctor: true, patientId: null })
  await view.loadConsultations()
  assert.equal(calls, 1)
  assert.equal(view.consultations.value[0].patient_name, 'Patient 1')
  assert.equal(view.props.patientId, null)
  await view.openConsultation(1)
  assert.equal(view.activeConsultation.value.id, 1)
  assert.equal(view.props.patientId, null)
})

test('late list responses cannot restore another patient records', async t => {
  const pending = deferred()
  const view = setup(t, { listConsultations: async id => id === 10 ? pending.promise : { data: [consultation(2)] } })
  const first = view.loadConsultations()
  view.props.patientId = 20
  await nextTick()
  await view.loadConsultations()
  pending.resolve({ data: [consultation(1)] })
  await first
  assert.deepEqual(view.consultations.value.map(row => row.id), [2])
})

test('patient switch clears private draft, active room and attachments before loading', async t => {
  const view = setup(t)
  await view.activateConsultation(consultation(1))
  view.draft.content = 'private message'
  view.recordOptions.value = [{ id: 501 }]
  view.props.patientId = 20
  await nextTick()
  assert.equal(view.activeConsultation.value, null)
  assert.equal(view.draft.content, '')
  assert.deepEqual(view.recordOptions.value, [])
})

test('switching conversations ignores a late response from the first room', async t => {
  const pending = deferred()
  const view = setup(t, { getConsultation: async id => id === 1 ? pending.promise : { data: consultation(id) } })
  const first = view.openConsultation(1)
  await view.openConsultation(2)
  pending.resolve({ data: consultation(1) })
  await first
  assert.equal(view.activeConsultation.value.id, 2)
  assert.equal(view.route.query.consultationId, '2')
})

test('blank, read-only, archived and unknown attachments cannot be sent', async t => {
  const sent = []
  const view = setup(t, { sendConsultationMessage: async (...args) => { sent.push(args); return {} } })
  await view.activateConsultation(consultation(1))
  view.draft.content = '  \n  '
  assert.equal(view.canSend.value, false)
  await view.sendMessage()
  view.draft.content = 'report'
  view.draft.messageType = 'FILE'
  view.draft.attachmentRecordId = 9
  assert.equal(view.canSend.value, false)
  view.recordOptions.value = [{ id: 9 }]
  assert.equal(view.canSend.value, true)
  view.activeConsultation.value.can_message = false
  assert.equal(view.canSend.value, false)
  view.activeConsultation.value.can_message = true
  view.activeConsultation.value.status = 'CLOSED'
  assert.equal(view.canSend.value, false)
  await view.sendMessage()
  assert.equal(sent.length, 0)
})

test('double send is blocked and stale send cannot clear a different conversation draft', async t => {
  const pending = deferred(), calls = []
  const view = setup(t, { sendConsultationMessage: async (id, payload) => { calls.push({ id, payload }); return pending.promise } })
  await view.activateConsultation(consultation(1))
  view.draft.content = 'first patient'
  const first = view.sendMessage()
  await view.sendMessage()
  assert.equal(calls.length, 1)
  await view.activateConsultation(consultation(2))
  assert.equal(view.sending.value, false)
  view.draft.content = 'second patient'
  pending.resolve({})
  await first
  assert.equal(view.draft.content, 'second patient')
  assert.equal(view.activeConsultation.value.id, 2)
})

test('late OPEN poll cannot overwrite an archived consultation', async t => {
  const pending = deferred()
  const view = setup(t, { getConsultation: async () => pending.promise })
  await view.activateConsultation(consultation(1))
  const polling = view.refreshActiveConsultation()
  await view.finishConsultation()
  assert.equal(view.activeConsultation.value.status, 'CLOSED')
  pending.resolve({ data: consultation(1) })
  await polling
  assert.equal(view.activeConsultation.value.status, 'CLOSED')
  assert.equal(view.canSend.value, false)
})

test('polling failure pauses polling instead of repeatedly generating requests', async t => {
  const view = setup(t, { listConsultations: async () => { throw new Error('offline') } })
  await view.loadConsultations()
  assert.equal(view.listError.value, true)
  assert.equal(view.scheduled.length, 0)
})

test('Enter preserves multiline input; Ctrl+Enter sends unless an IME composition is active', async t => {
  let calls = 0, prevented = 0
  const view = setup(t, { sendConsultationMessage: async () => { calls++; return {} } })
  await view.activateConsultation(consultation(1))
  view.draft.content = 'multiline text'
  view.onComposerKeydown({ key: 'Enter', preventDefault: () => prevented++ })
  view.onComposerKeydown({ key: 'Enter', ctrlKey: true, isComposing: true, preventDefault: () => prevented++ })
  assert.equal(calls, 0)
  view.onComposerKeydown({ key: 'Enter', ctrlKey: true, isComposing: false, preventDefault: () => prevented++ })
  assert.equal(calls, 1)
  assert.equal(prevented, 1)
  await nextTick()
})
