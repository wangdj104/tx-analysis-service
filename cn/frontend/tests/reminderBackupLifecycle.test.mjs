import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { effectScope, reactive, ref, watch, nextTick } from 'vue'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function component(t, name, bindings, exposed) {
  const scope = effectScope(), unmount = []
  const source = fs.readFileSync(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const dependencies = { ref, reactive, watch, onUnmounted: callback => unmount.push(callback), ...bindings }
  const view = scope.run(() => new Function(...Object.keys(dependencies), script + `\nreturn {${exposed}}`)(...Object.values(dependencies)))
  t.after(() => scope.stop())
  return { ...view, source, leave: () => unmount.forEach(callback => callback()) }
}
function reminder(t, overrides = {}) {
  const props = reactive({ patientId: 1, medications: [] }), saves = [], messages = [], reads = [], toggles = [], deletes = []
  const view = component(t, 'MedicationReminderPanel', {
    defineProps: () => props,
    ElMessage: { success: value => messages.push(value) },
    listReminders: async id => { reads.push(id); return { data: [] } },
    saveReminder: async payload => { saves.push(payload) },
    toggleEnabled: async id => { toggles.push(id) },
    deleteReminder: async id => { deletes.push(id) }, ...overrides
  }, 'rows,loading,saving,visible,formRef,form,openCreate,openEdit,submit,toggle,remove,load')
  view.formRef.value = { validate: async () => true }
  return { ...view, props, saves, messages, reads, toggles, deletes }
}
function draft(view, remark = 'Synthetic first draft') {
  view.openCreate()
  Object.assign(view.form, { medicationId: 11, dosage: 'Synthetic dose', remark, repeatDays: ['1', '3'] })
}

test('reminder same-tick save clicks acquire one lock before validation', async t => {
  const validation = deferred(), view = reminder(t)
  draft(view); view.formRef.value = { validate: () => validation.promise }
  const first = view.submit(), second = view.submit()
  assert.equal(view.saving.value, true)
  validation.resolve(true); await Promise.all([first, second])
  assert.equal(view.saves.length, 1)
})
for (const [field, change] of [
  ['medicationId', form => { form.medicationId = 22 }],
  ['dosage', form => { form.dosage = 'Synthetic corrected dose' }],
  ['repeatDays', form => { form.repeatDays.push('5') }],
  ['remindTime', form => { form.remindTime = '09:00' }],
  ['remark', form => { form.remark = 'Synthetic corrected note' }],
  ['enabled', form => { form.enabled = 0 }]
]) {
  test(`reminder ${field} change during validation preserves the correction for a fresh save`, async t => {
    const validation = deferred(), view = reminder(t)
    draft(view); view.formRef.value = { validate: () => validation.promise }
    const work = view.submit(); change(view.form)
    const corrected = { ...view.form, repeatDays: view.form.repeatDays.join(','), patientId: 1 }
    validation.resolve(true); await work
    assert.equal(view.saves.length, 0, 'an old snapshot must not silently replace corrected inputs')
    assert.equal(view.visible.value, true)
    assert.equal(view.saving.value, false)
    assert.equal(view.messages.length, 0)
    view.formRef.value = { validate: async () => true }
    await view.submit()
    assert.deepEqual(view.saves, [corrected])
    assert.equal(view.visible.value, false)
  })
}
test('reminder form inputs stay locked throughout validation and transmission', async t => {
  const validation = deferred(), pending = deferred(), view = reminder(t, { saveReminder: () => pending.promise })
  assert.match(view.source, /<el-form\b[^>]*:disabled="saving"/)
  draft(view); view.formRef.value = { validate: () => validation.promise }
  const work = view.submit(); assert.equal(view.saving.value, true)
  validation.resolve(true); await nextTick(); assert.equal(view.saving.value, true)
  pending.resolve({}); await work; assert.equal(view.saving.value, false)
})
test('reminder late save cannot close a programmatically corrected draft', async t => {
  const pending = deferred(), view = reminder(t, { saveReminder: () => pending.promise })
  draft(view); const work = view.submit(); await nextTick()
  view.form.dosage = 'Synthetic corrected dose'
  const reads = view.reads.length
  pending.resolve({}); await work
  assert.equal(view.visible.value, true)
  assert.equal(view.form.dosage, 'Synthetic corrected dose')
  assert.equal(view.saving.value, false)
  assert.equal(view.messages.length, 0)
  assert.equal(view.reads.length, reads)
})
test('reminder closing a locked form makes the reopened draft immediately editable', async t => {
  const pending = deferred(), view = reminder(t, { saveReminder: () => pending.promise })
  draft(view); const work = view.submit(); await nextTick()
  assert.equal(view.saving.value, true)
  view.visible.value = false; draft(view, 'Synthetic editable new draft')
  assert.equal(view.saving.value, false)
  pending.resolve({}); await work
  assert.equal(view.saving.value, false)
  assert.equal(view.visible.value, true)
  assert.equal(view.form.remark, 'Synthetic editable new draft')
})
for (const change of ['patient', 'patient-round-trip', 'dialog', 'close', 'leave']) {
  test(`reminder ${change} interruption during validation prevents transmission`, async t => {
    const validation = deferred(), view = reminder(t)
    draft(view); view.formRef.value = { validate: () => validation.promise }
    const work = view.submit()
    if (change.startsWith('patient')) { view.props.patientId = 2; if (change === 'patient-round-trip') view.props.patientId = 1 }
    else if (change === 'leave') view.leave()
    else { view.visible.value = false; if (change === 'dialog') draft(view, 'New draft') }
    validation.resolve(true); await work
    assert.equal(view.saves.length, 0)
    assert.equal(view.saving.value, false)
  })
}
for (const samePatient of [true, false]) {
  test(`reminder late save preserves the newer ${samePatient ? 'same-patient' : 'other-patient'} dialog`, async t => {
    const pending = deferred(), payloads = [], view = reminder(t, { saveReminder: payload => { payloads.push(payload); return pending.promise } })
    draft(view); const work = view.submit(); await nextTick()
    view.visible.value = false
    if (!samePatient) { view.props.patientId = 2; await nextTick() }
    draft(view, 'Synthetic newer draft'); const reads = view.reads.length
    pending.resolve({}); await work
    assert.equal(view.visible.value, true)
    assert.equal(view.form.remark, 'Synthetic newer draft')
    assert.equal(view.messages.length, 0)
    assert.equal(view.reads.length, reads)
    assert.equal(payloads[0].patientId, 1)
  })
}
test('reminder old save cannot release the newer dialog save lock', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const view = reminder(t, { saveReminder: () => ++calls === 1 ? first.promise : second.promise })
  draft(view); const older = view.submit(); await nextTick()
  view.visible.value = false; draft(view, 'New draft'); const newer = view.submit(); await nextTick()
  assert.equal(calls, 2)
  first.resolve({}); await older
  assert.equal(view.saving.value, true)
  second.resolve({}); await newer
  assert.equal(view.saving.value, false)
})
for (const failsAt of ['validation', 'save']) {
  test(`reminder ${failsAt} failure preserves the draft and permits explicit retry`, async t => {
    let calls = 0
    const view = reminder(t, failsAt === 'save' ? { saveReminder: async () => { if (++calls === 1) throw new Error('offline') } } : {})
    draft(view)
    if (failsAt === 'validation') view.formRef.value = { validate: async () => { if (++calls === 1) throw new Error('invalid'); return true } }
    await assert.rejects(view.submit(), failsAt === 'save' ? /offline/ : /invalid/)
    assert.equal(view.saving.value, false)
    assert.equal(view.visible.value, true)
    assert.equal(view.form.remark, 'Synthetic first draft')
    await view.submit()
    assert.equal(calls, 2)
    assert.equal(view.visible.value, false)
  })
}
for (const action of ['toggle', 'remove']) {
  test(`reminder ${action} blocks duplicate row actions and ignores stale patient completion`, async t => {
    const pending = deferred(); let calls = 0
    const view = reminder(t, { [action === 'toggle' ? 'toggleEnabled' : 'deleteReminder']: () => { calls++; return pending.promise } })
    const row = { id: 31 }, first = view[action](row), duplicate = view[action](row)
    assert.equal(calls, 1)
    view.props.patientId = 2; await nextTick(); view.props.patientId = 1; await nextTick()
    const reads = view.reads.length
    pending.resolve({}); await Promise.all([first, duplicate])
    assert.equal(view.reads.length, reads)
    assert.equal(view.messages.length, 0)
  })
  test(`reminder ${action} failure permits retry`, async t => {
    let calls = 0
    const view = reminder(t, { [action === 'toggle' ? 'toggleEnabled' : 'deleteReminder']: async () => { if (++calls === 1) throw new Error('offline') } })
    await assert.rejects(view[action]({ id: 31 }), /offline/)
    await view[action]({ id: 31 })
    assert.equal(calls, 2)
  })
}
test('reminder row lock survives switching away and back until the action settles', async t => {
  const pending = deferred(); let calls = 0
  const view = reminder(t, { toggleEnabled: () => { calls++; return pending.promise } })
  const first = view.toggle({ id: 31 })
  view.props.patientId = 2; view.props.patientId = 1
  const duplicate = view.toggle({ id: 31 })
  assert.equal(calls, 1)
  pending.resolve({}); await Promise.all([first, duplicate])
})
test('reminder toggle and delete share the row lock', async t => {
  const pending = deferred(), view = reminder(t, { toggleEnabled: () => pending.promise })
  const first = view.toggle({ id: 31 }); await view.remove({ id: 31 })
  assert.deepEqual(view.deletes, [])
  pending.resolve({}); await first
})
test('clearing reminder patient also clears a pending list loading state', async t => {
  const pending = deferred(), view = reminder(t, { listReminders: () => pending.promise })
  view.props.patientId = null
  await nextTick(); pending.resolve({ data: [{ id: 31 }] }); await nextTick()
  assert.equal(view.loading.value, false)
  assert.deepEqual(view.rows.value, [])
})

const fakeFile = name => ({ name, size: 42, type: 'application/zip' })
const selection = file => ({ target: { files: file ? [file] : [], value: 'synthetic.zip' } })
const previewData = { createdAt: '2026-10-02', patients: [{ name: 'Synthetic patient' }], counts: { patient: 1 }, warnings: [] }
function backup(t, overrides = {}) {
  const restores = [], previews = [], downloads = [], messages = [], events = [], emitted = [], links = []
  const view = component(t, 'FamilyBackupPanel', {
    defineEmits: () => (...args) => emitted.push(args),
    ElMessage: { success: value => messages.push(value) },
    previewBackup: async file => { previews.push(file); return { data: previewData } },
    restoreBackup: async file => { restores.push(file); return { data: { message: 'Synthetic restore complete' } } },
    downloadBackup: async () => { downloads.push(true); return new Blob(['synthetic']) },
    URL: { createObjectURL: () => 'blob:synthetic', revokeObjectURL() {} },
    document: { createElement: () => ({ click() { links.push(this.href) } }) },
    setTimeout: callback => callback(), window: { dispatchEvent: event => events.push(event.type) }, ...overrides
  }, 'busy,inspection,confirmed,result,download,preview,restore')
  return { ...view, restores, previews, downloads, messages, events, emitted, links }
}
async function ready(view, name = 'synthetic-backup.zip') {
  const file = fakeFile(name); await view.preview(selection(file)); view.confirmed.value = true; return file
}
test('backup repeated restore clicks create one set of copies and one refresh', async t => {
  const pending = deferred(), files = [], view = backup(t, { restoreBackup: file => { files.push(file); return pending.promise } })
  const file = await ready(view), first = view.restore(), duplicate = view.restore()
  assert.equal(files.length, 1); assert.equal(files[0], file)
  pending.resolve({ data: { message: 'Synthetic restore complete' } }); await Promise.all([first, duplicate])
  assert.deepEqual(view.emitted, [['restored']]); assert.deepEqual(view.events, ['care-patients-changed'])
  await view.restore(); assert.equal(files.length, 1)
})
test('backup restore requires a completed preview for the selected file', async t => {
  const pending = deferred(), view = backup(t, { previewBackup: () => pending.promise })
  const work = view.preview(selection(fakeFile('synthetic-backup.zip')))
  view.confirmed.value = true; await view.restore()
  assert.equal(view.restores.length, 0)
  pending.resolve({ data: previewData }); await work
})
test('backup failed preview cannot authorize restore through an old confirmation', async t => {
  const view = backup(t, { previewBackup: async () => { throw new Error('invalid archive') } })
  await assert.rejects(view.preview(selection(fakeFile('invalid.zip'))), /invalid archive/)
  view.confirmed.value = true; await view.restore()
  assert.equal(view.restores.length, 0)
})
test('backup empty preview cannot authorize restore', async t => {
  const view = backup(t, { previewBackup: async () => ({ data: null }) })
  await ready(view); await view.restore()
  assert.equal(view.restores.length, 0)
})
test('backup repeated download clicks start one download', async t => {
  const pending = deferred(); let calls = 0
  const view = backup(t, { downloadBackup: () => { calls++; return pending.promise } })
  const first = view.download(), duplicate = view.download()
  assert.equal(calls, 1)
  pending.resolve(new Blob(['synthetic'])); await Promise.all([first, duplicate])
  assert.deepEqual(view.links, ['blob:synthetic'])
})
test('backup stale file-selection event during preview cannot replace the file being inspected', async t => {
  const pending = deferred(), files = [], view = backup(t, { previewBackup: file => { files.push(file); return pending.promise } })
  const original = fakeFile('synthetic-original.zip'), first = view.preview(selection(original))
  const duplicate = view.preview(selection(fakeFile('synthetic-new.zip')))
  assert.equal(files.length, 1)
  pending.resolve({ data: previewData }); await Promise.all([first, duplicate])
  view.confirmed.value = true; await view.restore()
  assert.equal(view.restores[0], original)
})
test('backup busy download cannot unlock or replace a pending restore', async t => {
  const pending = deferred(), view = backup(t, { restoreBackup: () => pending.promise })
  await ready(view); const first = view.restore()
  await view.download(); await view.preview(selection(fakeFile('synthetic-new.zip')))
  assert.equal(view.downloads.length, 0); assert.equal(view.previews.length, 1); assert.equal(view.busy.value, true)
  pending.resolve({ data: { message: 'Synthetic restore complete' } }); await first
})
for (const action of ['preview', 'download', 'restore']) {
  test(`backup ${action} failure permits explicit retry`, async t => {
    let calls = 0
    const response = action === 'download' ? new Blob(['synthetic']) : { data: action === 'preview' ? previewData : { message: 'Synthetic restore complete' } }
    const view = backup(t, { [`${action}Backup`]: async () => { if (++calls === 1) throw new Error('offline'); return response } })
    if (action === 'restore') await ready(view)
    const invoke = () => view[action](selection(fakeFile('synthetic-backup.zip')))
    await assert.rejects(invoke(), /offline/); assert.equal(view.busy.value, false)
    await invoke(); assert.equal(calls, 2)
  })
  test(`backup late ${action} completion after leaving does not affect the page`, async t => {
    const pending = deferred(), view = backup(t, { [`${action}Backup`]: () => pending.promise })
    if (action === 'restore') await ready(view)
    const work = view[action](selection(fakeFile('synthetic-backup.zip'))); view.leave()
    pending.resolve(action === 'download' ? new Blob(['synthetic']) : { data: action === 'preview' ? previewData : { message: 'Synthetic restore complete' } }); await work
    assert.equal(view.inspection.value, null); assert.equal(view.result.value, '')
    assert.deepEqual(view.links, []); assert.deepEqual(view.emitted, []); assert.deepEqual(view.events, [])
  })
}
