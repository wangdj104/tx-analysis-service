import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, nextTick, effectScope } from 'vue'

function deferred() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
function care(t, overrides = {}) {
  const patientId = ref(1), saved = [], scope = effectScope()
  const api = { getCareHome: async () => ({ data: [] }), getCareContext: async () => ({ data: {} }),
    quickVitals: async data => { saved.push(data) }, getStockHistory: async () => ({ data: [] }), createCareInvite: async () => ({ data: '' }), ...overrides }
  const bindings = { ref, reactive, computed, watch, onMounted() {}, onUnmounted() {},
    localStorage: { getItem() { return null }, setItem() {} }, document: { body: { classList: { toggle() {} } } },
    useCurrentPatient: () => ({ currentPatientId: patientId, setPatientList() {} }),
    ElMessage: { success() {}, warning() {}, error() {} }, api }
  const script = fs.readFileSync(new URL('../src/views/CareCenter.vue', import.meta.url), 'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const exposed = 'quick,quickVisible,submitQuick,invite,inviteCode,showStockHistory,stockHistory,historyVisible,profile,entryRow,selectedIntake'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {' + exposed + '}')(...Object.values(bindings)))
  t.after(() => scope.stop())
  return { ...view, patientId, saved }
}

test('changing patient immediately discards the previous health draft and patient dialogs', async t => {
  const view = care(t)
  Object.assign(view.quick, { systolicBp: 145, diastolicBp: 90, bloodGlucose: 6, remark: 'Patient A', measurePeriod: 'After Meal' })
  view.quickVisible.value = true
  view.historyVisible.value = true
  view.stockHistory.value = [{ patientId: 1 }]
  view.profile.escalationUserId = 21
  view.patientId.value = 2
  assert.equal(view.quick.systolicBp, undefined)
  assert.equal(view.quick.diastolicBp, undefined)
  assert.equal(view.quick.bloodGlucose, undefined)
  assert.equal(view.quick.remark, '')
  assert.equal(view.quick.measurePeriod, 'After Meal')
  assert.equal(view.quickVisible.value, false)
  assert.equal(view.historyVisible.value, false)
  assert.deepEqual(view.stockHistory.value, [])
  assert.equal(view.profile.escalationUserId, null)
  await nextTick()
})

for (const operation of ['invite', 'history']) {
  test(`${operation}: a late result cannot survive switching A to B and back to A`, async t => {
    const first = deferred()
    const view = care(t, operation === 'invite' ? { createCareInvite: () => first.promise } : { getStockHistory: () => first.promise })
    const request = operation === 'invite' ? view.invite() : view.showStockHistory({ medication_id: 10 })
    view.patientId.value = 2
    await nextTick()
    view.patientId.value = 1
    await nextTick()
    first.resolve({ data: operation === 'invite' ? 'OLD-CODE' : [{ patientId: 1 }] })
    await request
    assert.equal(view.inviteCode.value, '')
    assert.deepEqual(view.stockHistory.value, [])
    assert.equal(view.historyVisible.value, false)
  })
  test(`${operation}: an older same-patient request cannot overwrite the latest result`, async t => {
    const first = deferred(); let calls = 0
    const get = () => ++calls === 1 ? first.promise : Promise.resolve({ data: operation === 'invite' ? 'NEW-CODE' : [{ medicationId: 20 }] })
    const view = care(t, operation === 'invite' ? { createCareInvite: get } : { getStockHistory: get })
    const start = () => operation === 'invite' ? view.invite() : view.showStockHistory({ medication_id: calls ? 20 : 10 })
    const request = start(); await start()
    first.resolve({ data: operation === 'invite' ? 'OLD-CODE' : [{ medicationId: 10 }] }); await request
    if (operation === 'invite') assert.equal(view.inviteCode.value, 'NEW-CODE')
    else assert.deepEqual(view.stockHistory.value, [{ medicationId: 20 }])
  })
}

test('a completed save for patient A does not erase a new patient B draft', async t => {
  const first = deferred(), view = care(t, { quickVitals: () => first.promise })
  view.quick.systolicBp = 120; view.quick.diastolicBp = 80
  const saving = view.submitQuick()
  view.patientId.value = 2; await nextTick()
  view.quickVisible.value = true; view.quick.remark = 'Patient B draft'
  first.resolve({}); await saving
  assert.equal(view.quickVisible.value, true)
  assert.equal(view.quick.remark, 'Patient B draft')
})

for (const action of ['openNote', 'openPlan']) {
  test(`${action}: selecting a new patient clears the previous clinical notes and plans`, async () => {
    const pending = deferred()
    const script = fs.readFileSync(new URL('../src/views/DoctorWorkspace.vue', import.meta.url), 'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
    const bindings = { ref, reactive, defineComponent: value => value, onMounted() {},
      getDoctorNotes: () => pending.promise, getDoctorPlans: () => pending.promise }
    const view = new Function(...Object.keys(bindings), script + '\nreturn {selectedPatientId, notes, plans, openNote, openPlan, noteVisible, planVisible}')(...Object.values(bindings))
    view.selectedPatientId.value = 1
    view.notes.value = [{ patientId: 1, noteText: 'Private note A' }]
    view.plans.value = [{ patientId: 1, title: 'Private plan A' }]
    view[action]({ id: 2, name: 'Patient B' })
    assert.equal(view.selectedPatientId.value, 2)
    assert.deepEqual(view.notes.value, [])
    assert.deepEqual(view.plans.value, [])
    view.noteVisible.value = false; view.planVisible.value = false
    pending.resolve({ data: [{ patientId: 2 }] })
    await nextTick(); await nextTick()
    assert.equal(view.notes.value[0].patientId, 2)
    assert.equal(view.plans.value[0].patientId, 2)
  })
}
