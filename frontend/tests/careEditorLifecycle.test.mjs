import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, nextTick, effectScope } from 'vue'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function setup(t, name, overrides = {}) {
  const patient = ref(1), route = reactive({ query: { tab: 'appointments' } }), scope = effectScope()
  const messages = [], reads = [], saves = [], unmount = [], storage = new Map()
  const api = new Proxy(overrides, { get(target, key) {
    return target[key] || (async (...args) => {
      if (String(key).startsWith('save') || key === 'quickVitals') saves.push([key, ...args])
      else reads.push([key, ...args])
      return { data: key === 'getCareContext' ? {} : [] }
    })
  } })
  const bindings = { ref, reactive, computed, watch, onMounted() {}, onUnmounted: fn => unmount.push(fn),
    useRoute: () => route, useRouter: () => ({ replace: value => { route.query = value.query } }),
    useCurrentPatient: () => ({ currentPatientId: patient, currentPatientName: ref('Synthetic patient'), setPatientList() {} }),
    ElMessage: new Proxy({}, { get: (_, kind) => message => messages.push([kind, message]) }),
    ElMessageBox: { confirm: async () => {}, prompt: async () => ({ value: 'Synthetic response' }) },
    localStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    document: { body: { classList: { toggle() {} } } }, window: { scrollTo() {} }, navigator: {}, api,
    localDateKey: () => '2026-10-02' }
  const locale = import.meta.url.includes('/cn/frontend/') ? 'cn/frontend' : 'frontend'
  const sourcePath = process.env.CARE_EDITOR_SOURCE_ROOT
    ? `${process.env.CARE_EDITOR_SOURCE_ROOT}/${locale}/src/views/${name}.vue`
    : new URL(`../src/views/${name}.vue`, import.meta.url)
  const source = fs.readFileSync(sourcePath, 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const exposed = name === 'CareCenter'
    ? 'quick,quickVisible,submitQuick,stockForm,stockVisible,openStock,submitStock,busy,tab'
    : 'prescription,savePrescription,appointment,bookAppointment,editAppointment,plan,planText,createPlan,rehab,rehabText,checkinRehab,busy,tab'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + `\nreturn {${exposed}}`)(...Object.values(bindings)))
  function leave() { unmount.forEach(fn => fn()); scope.stop() }
  t.after(leave)
  return { ...view, patient, messages, reads, saves, storage, leave }
}
const journeyFlows = [
  { name: 'prescription', method: 'savePrescription', api: 'savePrescription',
    draft: (v, note) => Object.assign(v.prescription, { drugName: note, dosage: 'Synthetic unchanged dose', frequency: 'Synthetic frequency' }),
    value: v => v.prescription.drugName },
  { name: 'appointment', method: 'bookAppointment', api: 'saveAppointment',
    draft: (v, note) => Object.assign(v.appointment, { doctorUserId: 42, startAt: '2026-10-03T10:00:00', endAt: '2026-10-03T10:30:00', reason: note }),
    value: v => v.appointment.reason },
  { name: 'plan', method: 'createPlan', api: 'saveTreatmentPlan',
    draft: (v, note) => { v.plan.title = note; v.planText.value = note }, value: v => v.planText.value },
  { name: 'rehab', method: 'checkinRehab', api: 'saveRehabCheckin',
    draft: (v, note) => { v.rehabText.value = note }, value: v => v.rehabText.value }
]
for (const flow of journeyFlows) {
  for (const change of ['patient', 'patient-round-trip', 'edited-draft', 'tab', 'leave']) {
    test(`journey ${flow.name}: ${change} invalidates old completion without resetting a replacement draft`, async t => {
      const pending = deferred(), payloads = []
      const view = setup(t, 'CareJourneyManager', { [flow.api]: body => { payloads.push(body); return pending.promise } })
      flow.draft(view, 'Synthetic original draft')
      const saving = view[flow.method]()
      if (change.startsWith('patient')) { view.patient.value = 2; if (change === 'patient-round-trip') view.patient.value = 1; await nextTick() }
      else if (change === 'tab') { view.tab.value = 'recovery'; await nextTick() }
      else if (change === 'leave') view.leave()
      flow.draft(view, 'Synthetic replacement draft')
      const reads = view.reads.length
      pending.resolve({}); await saving
      assert.equal(flow.value(view), 'Synthetic replacement draft')
      assert.equal(view.messages.length, 0)
      assert.equal(view.reads.length, reads)
      assert.equal(payloads.length, 1)
      assert.equal(payloads[0].patientId, 1)
      assert.equal(JSON.stringify(payloads[0]).includes('Synthetic replacement draft'), false)
      assert.equal(view.busy.value, false)
    })
  }
  for (const change of ['patient-round-trip', 'tab-round-trip', 'leave']) {
    test(`journey ${flow.name}: ${change} rejects completion even when the replacement has identical text`, async t => {
      const pending = deferred()
      const view = setup(t, 'CareJourneyManager', { [flow.api]: () => pending.promise })
      flow.draft(view, 'Synthetic identical draft'); const saving = view[flow.method]()
      if (change === 'patient-round-trip') { view.patient.value = 2; view.patient.value = 1 }
      else if (change === 'tab-round-trip') { const tab = view.tab.value; view.tab.value = 'recovery'; view.tab.value = tab }
      else view.leave()
      await nextTick(); flow.draft(view, 'Synthetic identical draft')
      const reads = view.reads.length
      pending.resolve({}); await saving
      assert.equal(flow.value(view), 'Synthetic identical draft')
      assert.equal(view.messages.length, 0)
      assert.equal(view.reads.length, reads)
      assert.equal(view.busy.value, false)
    })
  }
  test(`journey ${flow.name}: same-tick duplicate save has one request and owned success resets the draft`, async t => {
    const pending = deferred(); let requests = 0
    const view = setup(t, 'CareJourneyManager', { [flow.api]: () => { requests++; return pending.promise } })
    flow.draft(view, 'Synthetic original draft')
    const first = view[flow.method](), duplicate = view[flow.method]()
    assert.equal(requests, 1)
    assert.equal(view.busy.value, true)
    pending.resolve({}); await Promise.all([first, duplicate])
    assert.equal(flow.value(view), '')
    assert.equal(view.messages.length, 1)
    assert.equal(view.busy.value, false)
  })
  for (const outcome of ['resolve', 'reject']) {
    test(`journey ${flow.name}: old ${outcome} cannot release the replacement patient's pending save`, async t => {
      const first = deferred(), second = deferred(); let requests = 0
      const view = setup(t, 'CareJourneyManager', { [flow.api]: () => ++requests === 1 ? first.promise : second.promise })
      flow.draft(view, 'Synthetic A draft'); const older = view[flow.method]()
      view.patient.value = 2; await nextTick()
      flow.draft(view, 'Synthetic B draft'); const newer = view[flow.method]()
      assert.equal(requests, 2, 'the replacement patient must not stay locked by the old save')
      const reads = view.reads.length
      if (outcome === 'reject') first.reject(Object.assign(new Error('Synthetic stale validation'), { validation: true }))
      else first.resolve({})
      await older
      assert.equal(view.busy.value, true)
      assert.equal(flow.value(view), 'Synthetic B draft')
      assert.equal(view.messages.length, 0)
      assert.equal(view.reads.length, reads)
      second.resolve({}); await newer
      assert.equal(view.busy.value, false)
      assert.equal(view.messages.length, 1)
    })
  }
  test(`journey ${flow.name}: a stale error cannot warn for an edited draft`, async t => {
    const pending = deferred()
    const view = setup(t, 'CareJourneyManager', { [flow.api]: () => pending.promise })
    flow.draft(view, 'Synthetic original draft'); const saving = view[flow.method]()
    flow.draft(view, 'Synthetic correction')
    pending.reject(Object.assign(new Error('Synthetic obsolete validation'), { validation: true }))
    await saving
    assert.equal(view.messages.length, 0)
    assert.equal(flow.value(view), 'Synthetic correction')
    assert.equal(view.busy.value, false)
  })
  test(`journey ${flow.name}: an owned save failure preserves inputs and allows explicit retry`, async t => {
    let requests = 0
    const view = setup(t, 'CareJourneyManager', { [flow.api]: async () => { if (++requests === 1) throw new Error('Synthetic offline') } })
    flow.draft(view, 'Synthetic retry draft')
    await view[flow.method]()
    assert.equal(view.busy.value, false)
    assert.equal(flow.value(view), 'Synthetic retry draft')
    assert.equal(view.messages.length, 0)
    await view[flow.method]()
    assert.equal(requests, 2)
    assert.equal(flow.value(view), '')
    assert.equal(view.messages.length, 1)
  })
}
const centerFlows = [
  { name: 'quick', method: 'submitQuick', api: 'quickVitals', visible: 'quickVisible',
    draft: (v, note) => { v.quickVisible.value = true; v.quick.remark = note; v.quick.systolicBp = 120; v.quick.diastolicBp = 80 },
    edit: (v, note) => { v.quick.remark = note }, value: v => v.quick.remark },
  { name: 'stock', method: 'submitStock', api: 'saveStock', visible: 'stockVisible',
    draft: (v, note) => { v.openStock(); v.stockForm.medicationId = 10; v.stockForm.unit = note },
    edit: (v, note) => { v.stockForm.unit = note }, value: v => v.stockForm.unit }
]
for (const flow of centerFlows) {
  for (const change of ['patient', 'patient-round-trip', 'reopen', 'edited-draft', 'leave']) {
    test(`center ${flow.name}: ${change} invalidates old completion and preserves replacement editor`, async t => {
      const pending = deferred(), payloads = []
      const view = setup(t, 'CareCenter', { [flow.api]: body => { payloads.push(body); return pending.promise } })
      flow.draft(view, 'Synthetic original draft'); const saving = view[flow.method]()
      if (change.startsWith('patient')) { view.patient.value = 2; if (change === 'patient-round-trip') view.patient.value = 1; await nextTick() }
      else if (change === 'reopen') view[flow.visible].value = false
      else if (change === 'leave') view.leave()
      if (change === 'edited-draft') flow.edit(view, 'Synthetic replacement draft')
      else flow.draft(view, 'Synthetic replacement draft')
      const reads = view.reads.length
      pending.resolve({}); await saving
      assert.equal(view[flow.visible].value, true)
      assert.equal(flow.value(view), 'Synthetic replacement draft')
      assert.equal(view.messages.length, 0)
      assert.equal(view.reads.length, reads)
      assert.equal(payloads.length, 1)
      assert.equal(payloads[0].patientId, 1)
      assert.equal(JSON.stringify(payloads[0]).includes('Synthetic replacement draft'), false)
      assert.equal(view.storage.has('care-measure-period'), false)
      assert.equal(view.busy.value, false)
    })
  }
  for (const change of ['patient-round-trip', 'reopen', 'leave']) {
    test(`center ${flow.name}: ${change} rejects completion for an identical replacement draft`, async t => {
      const pending = deferred()
      const view = setup(t, 'CareCenter', { [flow.api]: () => pending.promise })
      flow.draft(view, 'Synthetic identical draft'); const saving = view[flow.method]()
      if (change === 'patient-round-trip') { view.patient.value = 2; view.patient.value = 1 }
      else if (change === 'reopen') view[flow.visible].value = false
      else view.leave()
      await nextTick(); flow.draft(view, 'Synthetic identical draft')
      const reads = view.reads.length
      pending.resolve({}); await saving
      assert.equal(view[flow.visible].value, true)
      assert.equal(flow.value(view), 'Synthetic identical draft')
      assert.equal(view.messages.length, 0)
      assert.equal(view.reads.length, reads)
      assert.equal(view.busy.value, false)
    })
  }
  test(`center ${flow.name}: duplicate save is single-flight and owned success closes the editor`, async t => {
    const pending = deferred(); let requests = 0
    const view = setup(t, 'CareCenter', { [flow.api]: () => { requests++; return pending.promise } })
    flow.draft(view, 'Synthetic original draft')
    const first = view[flow.method](), duplicate = view[flow.method]()
    assert.equal(requests, 1)
    assert.equal(view.busy.value, true)
    pending.resolve({}); await Promise.all([first, duplicate])
    assert.equal(view[flow.visible].value, false)
    assert.equal(view.messages.length, 1)
    assert.equal(view.busy.value, false)
    if (flow.name === 'quick') { assert.equal(view.quick.remark, ''); assert.equal(view.storage.get('care-measure-period'), 'Fasting') }
  })
  for (const outcome of ['resolve', 'reject']) {
    test(`center ${flow.name}: old ${outcome} cannot release the replacement editor's save lock`, async t => {
      const first = deferred(), second = deferred(); let requests = 0
      const view = setup(t, 'CareCenter', { [flow.api]: () => ++requests === 1 ? first.promise : second.promise })
      flow.draft(view, 'Synthetic original draft'); const older = view[flow.method]()
      view[flow.visible].value = false; flow.draft(view, 'Synthetic replacement draft')
      const newer = view[flow.method]()
      assert.equal(requests, 2, 'a reopened editor must not stay locked by the old save')
      const reads = view.reads.length
      if (outcome === 'reject') first.reject(new Error('Synthetic obsolete failure'))
      else first.resolve({})
      await older
      assert.equal(view.busy.value, true)
      assert.equal(flow.value(view), 'Synthetic replacement draft')
      assert.equal(view.messages.length, 0)
      assert.equal(view.reads.length, reads)
      second.resolve({}); await newer
      assert.equal(view.busy.value, false)
      assert.equal(view.messages.length, 1)
    })
  }
  test(`center ${flow.name}: an obsolete error is suppressed for an edited draft`, async t => {
    const pending = deferred()
    const view = setup(t, 'CareCenter', { [flow.api]: () => pending.promise })
    flow.draft(view, 'Synthetic original draft'); const saving = view[flow.method]()
    flow.edit(view, 'Synthetic correction')
    pending.reject(new Error('Synthetic obsolete failure'))
    await assert.doesNotReject(saving)
    assert.equal(view.messages.length, 0)
    assert.equal(flow.value(view), 'Synthetic correction')
    assert.equal(view.busy.value, false)
  })
  test(`center ${flow.name}: current failure preserves draft and explicit retry works`, async t => {
    let requests = 0
    const view = setup(t, 'CareCenter', { [flow.api]: async () => { if (++requests === 1) throw new Error('Synthetic offline') } })
    flow.draft(view, 'Synthetic retry draft')
    await assert.rejects(view[flow.method](), /Synthetic offline/)
    assert.equal(view.busy.value, false)
    assert.equal(view[flow.visible].value, true)
    assert.equal(flow.value(view), 'Synthetic retry draft')
    assert.equal(view.messages.length, 0)
    await view[flow.method]()
    assert.equal(requests, 2)
    assert.equal(view[flow.visible].value, false)
  })
}

test('journey appointment: reselecting the same row is a new editor even with identical inputs', async t => {
  const pending = deferred()
  const view = setup(t, 'CareJourneyManager', { saveAppointment: () => pending.promise })
  const row = { id: 81, status: 'BOOKED', doctor_user_id: 42, schedule_id: 7, consultation_mode: 'IN_PERSON', start_at: '2026-10-03 10:00:00', end_at: '2026-10-03 10:30:00', recurrence_days: 0, reason: 'Synthetic same appointment' }
  view.editAppointment(row)
  const saving = view.bookAppointment()
  view.editAppointment(row)
  const reads = view.reads.length
  pending.resolve({}); await saving
  assert.equal(view.appointment.id, 81)
  assert.equal(view.appointment.reason, 'Synthetic same appointment')
  assert.equal(view.messages.length, 0)
  assert.equal(view.reads.length, reads)
  assert.equal(view.busy.value, false)
})
