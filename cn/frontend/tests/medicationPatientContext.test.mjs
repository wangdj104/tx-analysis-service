import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope, nextTick } from 'vue'

function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function setup(t, overrides = {}) {
  const source = fs.readFileSync(new URL('../src/views/MedicationManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const patientId = ref(1), saves = [], messages = [], unmount = [], scope = effectScope()
  const api = {
    listMedications: async () => ({ code: 200, data: [] }), listActiveMedications: async () => ({ code: 200, data: [] }), listLogs: async () => ({ code: 200, data: [] }),
    uploadAndRecognize: async () => ({ code: 200, data: { drugs: [{ drugName: 'Synthetic A', defaultDosage: 'unchanged test dose' }] } }),
    saveMedication: async data => { saves.push(data); return { code: 200 } }, updateMedication: async data => { saves.push(data); return { code: 200 } },
    saveLog: async data => { saves.push(data); return { code: 200 } }, updateLog: async data => { saves.push(data); return { code: 200 } }, ...overrides.api,
  }
  const bindings = { ref, reactive, computed, watch, onMounted() {}, onUnmounted: callback => unmount.push(callback),
    useCurrentPatient: () => ({ currentPatientId: patientId }), useRoute: () => ({ query: {} }), useTableColumns: () => ({}), localDateTimeKey: () => '2026-10-02T01:30',
    getPatientNames: async () => ({ code: 200, data: [] }), saveMedicationsBatch: async data => { saves.push(data); return { code: 200 } },
    ElMessage: Object.fromEntries(['success', 'warning', 'error', 'info'].map(level => [level, value => messages.push({ level, value })])), ElMessageBox: { confirm: async () => {} },
    window: { removeEventListener() {} }, ...overrides, api,
  }
  const exposed = 'recognizeSaving,drugSaving,logSaving,uploadForm,selectedFiles,recognizedDrugs,recognizeResult,recognizeLoading,recognizeWarning,uploadRef,handleFileChange,startRecognize,saveRecognizedDrug,activeMenu,handleMenuSelect,drugs,activeDrugs,logs,loadDrugs,loadActiveDrugs,loadLogs,logFilter,editingDrug,editingLog,drugDialogVisible,logDialogVisible,showDrugDialog,showLogDialog,saveDrug,saveLog'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {' + exposed + ', remove: typeof handleFileRemove === "function" ? handleFileRemove : () => {}}')(...Object.values(bindings)))
  t.after(() => scope.stop())
  return { ...view, patientId, saves, messages, unmount, source }
}
const file = uid => ({ uid, name: `synthetic-${uid}.png`, raw: { name: `synthetic-${uid}.png`, type: 'image/png', size: 10 } })
const recognized = name => ({ code: 200, data: { drugs: [{ drugName: name, defaultDosage: 'unchanged test dose' }] } })

// These execute the real SFC script: replacing guards with unconditional writes must fail.
test('visible removal and duplicate callbacks keep the submitted upload queue in sync', t => {
  const view = setup(t), first = file(1)
  view.handleFileChange(first); view.handleFileChange(first)
  assert.equal(view.selectedFiles.value.length, 1)
  view.remove(first)
  assert.equal(view.selectedFiles.value.length, 0)
  assert.match(view.source, /:on-remove="handleFileRemove"/)
})
test('a queued Element Plus callback cannot restore a cleared patient file', t => {
  const view = setup(t), first = file(1)
  view.patientId.value = 2
  view.handleFileChange(first, [])
  assert.equal(view.selectedFiles.value.length, 0)
})
test('manual file callbacks obey the ten-file limit', t => {
  const view = setup(t)
  for (let uid = 1; uid <= 11; uid++) view.handleFileChange(file(uid))
  assert.equal(view.selectedFiles.value.length, 10)
})
test('global patient switching immediately clears upload results, lists and open patient dialogs', async t => {
  const view = setup(t)
  view.handleFileChange(file(1)); await view.startRecognize()
  view.drugs.value = [{ drugName: 'Synthetic A' }]; view.activeDrugs.value = [{ id: 4 }]; view.logs.value = [{ id: 5 }]
  view.showDrugDialog({ id: 3, patientId: 1, drugName: 'Synthetic A' }); view.showLogDialog({ id: 4, patientId: 1, medicationId: 3, dosage: 'Synthetic A dose' })
  view.patientId.value = 2
  assert.equal(view.uploadForm.patientId, 2)
  assert.equal(view.recognizeResult.value, false)
  assert.equal(view.recognizedDrugs.value.length, 0)
  assert.equal(view.selectedFiles.value.length, 0)
  assert.equal(view.drugs.value.length, 0); assert.equal(view.activeDrugs.value.length, 0); assert.equal(view.logs.value.length, 0)
  assert.equal(view.drugDialogVisible.value, false); assert.equal(view.logDialogVisible.value, false)
  assert.equal(view.editingDrug.drugName, ''); assert.equal(view.editingLog.dosage, '')
})
for (const source of ['global', 'upload']) {
  test(`${source} patient A to B to A invalidates pending recognition even in one tick`, async t => {
    const pending = deferred(), view = setup(t, { api: { uploadAndRecognize: () => pending.promise } })
    view.handleFileChange(file(1)); const work = view.startRecognize()
    if (source === 'global') { view.patientId.value = 2; view.patientId.value = 1 }
    else { view.uploadForm.patientId = 2; view.uploadForm.patientId = 1 }
    pending.resolve(recognized('Synthetic A')); await work
    assert.equal(view.recognizeResult.value, false); assert.equal(view.recognizedDrugs.value.length, 0); assert.equal(view.selectedFiles.value.length, 0)
  })
}
test('removing a file invalidates pending recognition and its save action', async t => {
  const pending = deferred(), view = setup(t, { api: { uploadAndRecognize: () => pending.promise } }), first = file(1)
  view.handleFileChange(first); const work = view.startRecognize(); view.remove(first)
  pending.resolve(recognized('Removed')); await work; await view.saveRecognizedDrug()
  assert.equal(view.recognizeResult.value, false); assert.equal(view.saves.length, 0)
})
test('a stale same-patient OCR response cannot replace newer results', async t => {
  const pending = deferred(); let calls = 0
  const view = setup(t, { api: { uploadAndRecognize: () => ++calls === 1 ? pending.promise : Promise.resolve(recognized('New')) } })
  view.handleFileChange(file(1)); const old = view.startRecognize(); await view.startRecognize()
  pending.resolve(recognized('Old')); await old
  assert.equal(view.recognizedDrugs.value[0].drugName, 'New')
})
test('an obsolete OCR error cannot clear the new loading indicator or show an old error', async t => {
  const old = deferred(), latest = deferred(); let calls = 0
  const view = setup(t, { api: { uploadAndRecognize: () => ++calls === 1 ? old.promise : latest.promise } })
  view.handleFileChange(file(1)); const first = view.startRecognize(); const second = view.startRecognize()
  old.reject(new Error('old failure')); await first
  assert.equal(view.recognizeLoading.value, true); assert.equal(view.messages.length, 0)
  latest.resolve(recognized('New')); await second
})
test('recognized save uses the upload patient and a detached clinical-value snapshot', async t => {
  const pending = deferred(), payloads = [], view = setup(t, { api: { saveMedication: payload => { payloads.push(payload); return pending.promise } } })
  view.uploadForm.patientId = 2; view.handleFileChange(file(1)); await view.startRecognize()
  const work = view.saveRecognizedDrug(); view.recognizedDrugs.value[0].defaultDosage = 'later edit'
  assert.equal(payloads[0].patientId, 2); assert.equal(payloads[0].defaultDosage, 'unchanged test dose')
  pending.resolve({ code: 200 }); await work
})
test('repeated recognized saves submit once and file additions cannot unlock the pending save', async t => {
  const pending = deferred(); let calls = 0
  const view = setup(t, { api: { saveMedication: () => { calls++; return pending.promise } } })
  view.handleFileChange(file(1)); await view.startRecognize()
  const first = view.saveRecognizedDrug(); view.handleFileChange(file(2)); const second = view.saveRecognizedDrug()
  assert.equal(calls, 1); assert.equal(view.selectedFiles.value.length, 1); assert.equal(view.recognizeSaving.value, true)
  pending.resolve({ code: 200 }); await Promise.all([first, second])
  assert.match(view.source, /:loading="recognizeSaving"/)
})
test('an old recognized save cannot clear the replacement patient draft', async t => {
  const pending = deferred(), view = setup(t, { api: { saveMedication: () => pending.promise } })
  view.handleFileChange(file(1)); await view.startRecognize(); const work = view.saveRecognizedDrug()
  view.uploadForm.patientId = 2; view.handleFileChange(file(2)); await view.startRecognize()
  assert.equal(view.recognizeSaving.value, false)
  pending.resolve({ code: 200 }); await work
  assert.equal(view.recognizedDrugs.value[0]?.drugName, 'Synthetic A'); assert.equal(view.recognizeResult.value, true)
  assert.equal(view.selectedFiles.value[0]?.name, 'synthetic-2.png')
  assert.equal(view.messages.filter(message => message.level === 'success').length, 0)
})
test('a stale result flag cannot save an unrecognized draft', async t => {
  const view = setup(t); view.recognizedDrugs.value = [{ drugName: 'stale' }]; await view.saveRecognizedDrug()
  assert.equal(view.saves.length, 0)
})
test('unmount invalidates recognition and all list responses', async t => {
  const pending = deferred(), view = setup(t, { api: { uploadAndRecognize: () => pending.promise, listMedications: () => pending.promise, listActiveMedications: () => pending.promise, listLogs: () => pending.promise } })
  view.handleFileChange(file(1)); const work = [view.startRecognize(), view.loadDrugs(), view.loadActiveDrugs(), view.loadLogs()]
  for (const close of view.unmount) close()
  pending.resolve(recognized('Late')); await Promise.all(work)
  assert.equal(view.recognizeResult.value, false); assert.deepEqual(view.drugs.value, []); assert.deepEqual(view.activeDrugs.value, []); assert.deepEqual(view.logs.value, [])
})
for (const tab of ['logs', 'remind']) {
  test(`${tab} tab entry and patient switch reload medication choices for the current patient`, async t => {
    const calls = [], view = setup(t, { api: { listMedications: async id => { calls.push(['drugs', id]); return { code: 200, data: [] } }, listActiveMedications: async id => { calls.push(['active', id]); return { code: 200, data: [] } } } })
    view.handleMenuSelect(tab); await nextTick()
    assert.ok(calls.some(([kind, id]) => kind === 'drugs' && id === 1)); assert.ok(calls.some(([kind, id]) => kind === 'active' && id === 1))
    calls.length = 0; view.patientId.value = 2; await nextTick()
    assert.ok(calls.some(([kind, id]) => kind === 'drugs' && id === 2)); assert.ok(calls.some(([kind, id]) => kind === 'active' && id === 2))
  })
}
for (const [method, apiMethod, state] of [['loadDrugs', 'listMedications', 'drugs'], ['loadActiveDrugs', 'listActiveMedications', 'activeDrugs'], ['loadLogs', 'listLogs', 'logs']]) {
  test(`${method} discards older requests for the same patient`, async t => {
    const pending = deferred(); let calls = 0
    const view = setup(t, { api: { [apiMethod]: () => ++calls === 1 ? pending.promise : Promise.resolve({ code: 200, data: [{ id: 'new' }] }) } })
    const work = view[method](); await view[method](); pending.resolve({ code: 200, data: [{ id: 'old' }] }); await work
    assert.equal(view[state].value[0].id, 'new')
  })
  test(`${method} ignores late failures after a patient switch`, async t => {
    const pending = deferred(); let calls = 0
    const view = setup(t, { api: { [apiMethod]: () => ++calls === 1 ? pending.promise : Promise.resolve({ code: 200, data: [] }) } })
    const work = view[method](); view.patientId.value = 2; pending.reject(new Error('old patient')); await work; await nextTick()
    assert.equal(view.messages.length, 0)
  })
}
test('changing the log patient filter immediately clears prior records and invalidates reads', async t => {
  const pending = deferred(), view = setup(t, { api: { listLogs: () => pending.promise } })
  view.logs.value = [{ id: 'old' }]; const work = view.loadLogs(); view.logFilter.patientId = 2
  assert.deepEqual(view.logs.value, [])
  pending.resolve({ code: 200, data: [{ id: 'old' }] }); await work; assert.deepEqual(view.logs.value, [])
})
test('log dialog patient selection clears the old drug choice and reloads that patient options', async t => {
  const calls = [], view = setup(t, { api: { listActiveMedications: async id => { calls.push(id); return { code: 200, data: [{ id: id * 10 }] } } } })
  view.showLogDialog(); await nextTick(); view.editingLog.medicationId = 10; view.editingLog.patientId = 2
  assert.equal(view.editingLog.medicationId, null); assert.deepEqual(view.activeDrugs.value, [])
  await nextTick(); assert.equal(calls.at(-1), 2); assert.equal(view.activeDrugs.value[0].id, 20)
})
for (const [show, save, apiMethod, form, visible, field] of [['showDrugDialog', 'saveDrug', 'saveMedication', 'editingDrug', 'drugDialogVisible', 'drugName'], ['showLogDialog', 'saveLog', 'saveLog', 'editingLog', 'logDialogVisible', 'dosage']]) {
  test(`${save} snapshots the submission and preserves a replacement patient dialog`, async t => {
    const pending = deferred(), sent = [], view = setup(t, { api: { [apiMethod]: payload => { sent.push(payload); return pending.promise } } })
    view[show](); view[form][field] = 'Original'; const work = view[save]()
    const saving = save === 'saveDrug' ? 'drugSaving' : 'logSaving'
    assert.equal(view[saving].value, true)
    view.patientId.value = 2; view[show](); view[form][field] = 'New'
    assert.equal(view[saving].value, false)
    assert.equal(sent[0].patientId, 1); assert.equal(sent[0][field], 'Original')
    pending.resolve({ code: 200 }); await work
    assert.equal(view[visible].value, true); assert.equal(view[form][field], 'New'); assert.equal(view.messages.length, 0)
  })
}

test('changing the patient within a pending log dialog preserves the replacement draft', async t => {
  const pending = deferred(), view = setup(t, { api: { saveLog: () => pending.promise } })
  view.showLogDialog(); view.editingLog.dosage = 'Original'; const work = view.saveLog()
  view.editingLog.patientId = 2; view.editingLog.dosage = 'New'
  pending.resolve({ code: 200 }); await work
  assert.equal(view.logDialogVisible.value, true); assert.equal(view.editingLog.dosage, 'New')
  assert.equal(view.messages.length, 0)
})

test('an old recognized save cannot release the replacement draft save lock', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const view = setup(t, { api: { saveMedication: () => ++calls === 1 ? first.promise : second.promise } })
  view.handleFileChange(file(1)); await view.startRecognize(); const old = view.saveRecognizedDrug()
  view.uploadForm.patientId = 2; view.handleFileChange(file(2)); await view.startRecognize(); const latest = view.saveRecognizedDrug()
  first.resolve({ code: 200 }); await old; await view.saveRecognizedDrug()
  assert.equal(calls, 2); second.resolve({ code: 200 }); await latest
})

test('recognized batch save preserves the selected patient and clinical values', async t => {
  const view = setup(t, { api: { uploadAndRecognize: async () => ({ code: 200, data: { drugs: [{ drugName: 'Synthetic A', defaultDosage: 'first' }, { drugName: 'Synthetic B', defaultDosage: 'second' }] } }) } })
  view.uploadForm.patientId = 2; view.handleFileChange(file(1)); await view.startRecognize(); await view.saveRecognizedDrug()
  assert.deepEqual(view.saves[0].map(drug => [drug.patientId, drug.defaultDosage]), [[2, 'first'], [2, 'second']])
  assert.equal(view.recognizeResult.value, false); assert.equal(view.selectedFiles.value.length, 0)
})

test('a failed recognized save keeps the draft and releases its lock for retry', async t => {
  let calls = 0
  const view = setup(t, { api: { saveMedication: async () => { if (++calls === 1) throw new Error('synthetic failure'); return { code: 200 } } } })
  view.handleFileChange(file(1)); await view.startRecognize(); await view.saveRecognizedDrug()
  assert.equal(view.recognizeResult.value, true); assert.equal(view.recognizedDrugs.value[0].drugName, 'Synthetic A')
  await view.saveRecognizedDrug(); assert.equal(calls, 2); assert.equal(view.recognizeResult.value, false)
})

test('each originating clinical editor is disabled only while its own save is pending', t => {
  const view = setup(t)
  for (const [model, saving] of [['currentRecognizeDrug', 'recognizeSaving'], ['editingDrug', 'drugSaving'], ['editingLog', 'logSaving']]) {
    assert.match(view.source, new RegExp(`<el-form[^>]*:model="${model}"[^>]*:disabled="${saving}"`))
  }
})

test('pending OCR and repeated recognition cannot replace a draft while it is being saved', async t => {
  const ocr = deferred(), saved = deferred(); let calls = 0
  const view = setup(t, { api: { uploadAndRecognize: () => ++calls === 1 ? ocr.promise : Promise.resolve(recognized('Current')), saveMedication: () => saved.promise } })
  view.handleFileChange(file(1)); const stale = view.startRecognize(); await view.startRecognize(); const work = view.saveRecognizedDrug()
  await view.startRecognize(); assert.equal(calls, 2)
  ocr.resolve(recognized('Old')); await stale
  assert.equal(view.recognizedDrugs.value[0].drugName, 'Current')
  saved.resolve({ code: 200 }); await work
})
