import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { reactive, ref } from 'vue'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function setup(overrides = {}) {
  const messages = [], savedNotes = [], savedPlans = []
  const bindings = {
    ref, reactive, defineComponent: value => value, onMounted() {},
    ElMessage: { success: text => messages.push(text), warning() {} },
    getDoctorSummary: async () => ({ data: { patients: [{ id: 1 }, { id: 2 }] } }),
    getDoctorNotes: async patientId => ({ data: [{ patientId }] }),
    getDoctorPlans: async patientId => ({ data: [{ patientId }] }),
    saveDoctorNote: async payload => { savedNotes.push(payload) },
    saveDoctorPlan: async payload => { savedPlans.push(payload) }, ...overrides
  }
  const script = fs.readFileSync(new URL('../src/views/DoctorWorkspace.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const exposed = 'openNote,openPlan,submitNote,submitPlan,noteVisible,planVisible,noteForm,planForm,saving,selectedPatientId,notes,plans'
  const view = new Function(...Object.keys(bindings), script + '\nreturn {' + exposed + '}')(...Object.values(bindings))
  return { ...view, messages, savedNotes, savedPlans }
}
const kinds = [
  { name: 'note', open: 'openNote', submit: 'submitNote', visible: 'noteVisible', form: 'noteForm', api: 'saveDoctorNote', values: { noteText: 'A note' } },
  { name: 'plan', open: 'openPlan', submit: 'submitPlan', visible: 'planVisible', form: 'planForm', api: 'saveDoctorPlan', values: { title: 'A title', instructions: 'A instructions' } }
]
for (const kind of kinds) {
  for (const nextPatient of [1, 2]) test(`${kind.name}: late save preserves reopened patient ${nextPatient} dialog and draft`, async () => {
    const pending = deferred(), payloads = []
    const view = setup({ [kind.api]: payload => { payloads.push(payload); return pending.promise } })
    view[kind.open]({ id: 1, name: 'Fictional A' })
    Object.assign(view[kind.form], kind.values)
    const request = view[kind.submit]()
    view[kind.visible].value = false
    view[kind.open]({ id: nextPatient, name: 'Fictional next patient' })
    const newDraft = Object.fromEntries(Object.keys(kind.values).map(key => [key, 'New draft']))
    Object.assign(view[kind.form], newDraft)
    pending.resolve({}); await request
    assert.equal(view[kind.visible].value, true)
    for (const [key, value] of Object.entries(newDraft)) assert.equal(view[kind.form][key], value)
    assert.equal(view.selectedPatientId.value, nextPatient)
    assert.equal(payloads[0].patientId, 1)
    for (const [key, value] of Object.entries(kind.values)) assert.equal(payloads[0][key], value)
    assert.equal(view.messages.length, 0, 'a replaced dialog must not show stale save feedback')
  })
  test(`${kind.name}: repeated save creates one record while pending`, async () => {
    const pending = deferred(), payloads = []
    const view = setup({ [kind.api]: payload => { payloads.push(payload); return pending.promise } })
    view[kind.open]({ id: 1, name: 'Fictional A' }); Object.assign(view[kind.form], kind.values)
    const first = view[kind.submit](), duplicate = view[kind.submit]()
    assert.equal(payloads.length, 1)
    pending.resolve({}); await Promise.all([first, duplicate])
    assert.equal(view[kind.visible].value, false)
    assert.equal(view.saving.value, false)
    assert.equal(view.messages.length, 1)
  })
  test(`${kind.name}: cancelled save does not refresh chart or report stale success`, async () => {
    const pending = deferred()
    let reads = 0
    const view = setup({ [kind.api]: () => pending.promise, getDoctorNotes: async () => { reads++; return { data: [] } } })
    view[kind.open]({ id: 1, name: 'Fictional A' }); Object.assign(view[kind.form], kind.values)
    const request = view[kind.submit]()
    view[kind.visible].value = false
    pending.resolve({}); await request
    assert.equal(reads, 1)
    assert.equal(view.messages.length, 0)
    assert.equal(view.saving.value, false)
  })
  test(`${kind.name}: failed save keeps the draft and allows a retry`, async () => {
    let requests = 0
    const view = setup({ [kind.api]: async () => { if (++requests === 1) throw new Error('offline') } })
    view[kind.open]({ id: 1, name: 'Fictional A' }); Object.assign(view[kind.form], kind.values)
    await assert.rejects(view[kind.submit](), /offline/)
    assert.equal(view[kind.visible].value, true)
    assert.equal(view.saving.value, false)
    for (const [key, value] of Object.entries(kind.values)) assert.equal(view[kind.form][key], value)
    await view[kind.submit]()
    assert.equal(requests, 2)
    assert.equal(view[kind.visible].value, false)
  })
}

for (const [previous, next] of [[kinds[0], kinds[1]], [kinds[1], kinds[0]]]) {
  test(`late ${previous.name} save does not refresh the newly opened ${next.name} dialog`, async () => {
    const pending = deferred(); let reads = 0
    const view = setup({ [previous.api]: () => pending.promise, getDoctorNotes: async () => { reads++; return { data: [] } } })
    view[previous.open]({ id: 1, name: 'Fictional A' }); Object.assign(view[previous.form], previous.values)
    const request = view[previous.submit]()
    view[previous.visible].value = false
    view[next.open]({ id: 2, name: 'Fictional B' }); Object.assign(view[next.form], next.values)
    pending.resolve({}); await request
    assert.equal(view[next.visible].value, true)
    assert.equal(view.selectedPatientId.value, 2)
    assert.equal(reads, 2)
    assert.equal(view.messages.length, 0)
  })
}
