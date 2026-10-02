import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope, nextTick } from 'vue'
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function setup(t, overrides = {}) {
  const source = fs.readFileSync(new URL('../src/views/PatientManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const scope = effectScope(), messages = [], saves = [], unmount = []
  const bindings = { ref, reactive, computed, watch, onMounted() {}, onUnmounted: callback => unmount.push(callback), useTableColumns: () => ({}),
    ElMessage: Object.fromEntries(['error', 'success', 'warning'].map(level => [level, message => messages.push({ level, message })])),
    getClinicalByPatient: async patientId => ({ code: 200, data: { id: patientId * 10, patientId, remark: `Patient ${patientId}` } }),
    saveClinical: async payload => { saves.push(payload); return { code: 200 } }, console: { error() {} }, ...overrides }
  const view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {clinicalForm,clinicalDialogVisible,clinicalPatientId,showClinicalDialog,handleSaveClinical}')(...Object.values(bindings)))
  t.after(() => scope.stop())
  return { ...view, messages, saves, source, unmount: () => unmount.forEach(callback => callback()) }
}
test('clinical dialog opens while loading and cannot save an unloaded blank profile', async t => {
  const pending = deferred(), view = setup(t, { getClinicalByPatient: () => pending.promise })
  const work = view.showClinicalDialog({ id: 1 })
  assert.equal(view.clinicalDialogVisible.value, true)
  await view.handleSaveClinical(); assert.equal(view.saves.length, 0)
  pending.resolve({ code: 200, data: null }); await work
  await view.handleSaveClinical(); assert.equal(view.saves.length, 1)
})
for (const reverse of [false, true]) {
  test(`clinical dialog ignores older patient response in ${reverse ? 'reverse' : 'normal'} completion order`, async t => {
    const first = deferred(), second = deferred()
    const view = setup(t, { getClinicalByPatient: id => id === 1 ? first.promise : second.promise })
    const a = view.showClinicalDialog({ id: 1 }), b = view.showClinicalDialog({ id: 2 })
    if (reverse) { second.resolve({ code: 200, data: { id: 20, patientId: 2, remark: 'B' } }); await b }
    first.resolve({ code: 200, data: { id: 10, patientId: 1, remark: 'A' } }); await a
    if (!reverse) { second.resolve({ code: 200, data: { id: 20, patientId: 2, remark: 'B' } }); await b }
    assert.equal(view.clinicalPatientId.value, 2)
    assert.equal(view.clinicalForm.patientId, 2)
    assert.equal(view.clinicalForm.remark, 'B')
    await view.handleSaveClinical(); assert.equal(view.saves[0].patientId, 2)
  })
}
test('closing while clinical information loads cannot reopen the dialog', async t => {
  const pending = deferred(), view = setup(t, { getClinicalByPatient: () => pending.promise })
  const work = view.showClinicalDialog({ id: 1 }); view.clinicalDialogVisible.value = false
  pending.resolve({ code: 200, data: { patientId: 1, remark: 'Late' } }); await work
  assert.equal(view.clinicalDialogVisible.value, false)
  assert.notEqual(view.clinicalForm.remark, 'Late')
})
test('a failed clinical load cannot be saved as an empty overwrite', async t => {
  const view = setup(t, { getClinicalByPatient: async () => { throw new Error('offline') } })
  await view.showClinicalDialog({ id: 1 }); await view.handleSaveClinical()
  assert.equal(view.saves.length, 0)
  assert.equal(view.messages.filter(message => message.level === 'error').length, 1)
})
test('clinical saves are single-flight and preserve newer patient dialogs', async t => {
  const pending = deferred(), payloads = [], view = setup(t, { saveClinical: payload => { payloads.push(payload); return pending.promise } })
  await view.showClinicalDialog({ id: 1 }); const first = view.handleSaveClinical(); const second = view.handleSaveClinical()
  assert.equal(payloads.length, 1)
  view.clinicalDialogVisible.value = false; await view.showClinicalDialog({ id: 2 }); view.clinicalForm.remark = 'New B draft'
  pending.resolve({ code: 200 }); await Promise.all([first, second])
  assert.equal(payloads[0].patientId, 1)
  assert.equal(payloads[0].remark, 'Patient 1')
  assert.equal(view.clinicalDialogVisible.value, true)
  assert.equal(view.clinicalForm.remark, 'New B draft')
})
test('unmount prevents a pending clinical read from restoring state', async t => {
  const pending = deferred(), view = setup(t, { getClinicalByPatient: () => pending.promise })
  const work = view.showClinicalDialog({ id: 1 }); view.unmount()
  pending.resolve({ code: 200, data: { patientId: 1, remark: 'Late' } }); await work
  assert.notEqual(view.clinicalForm.remark, 'Late')
})
test('clinical load refuses a mismatched patient payload', async t => {
  const view = setup(t, { getClinicalByPatient: async () => ({ code: 200, data: { patientId: 99, remark: 'Wrong patient' } }) })
  await view.showClinicalDialog({ id: 1 }); await view.handleSaveClinical()
  assert.equal(view.saves.length, 0)
  assert.notEqual(view.clinicalForm.remark, 'Wrong patient')
})
