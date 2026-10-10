import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { baseParse } from '@vue/compiler-dom'
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
  const exposed = 'pageTitle,pageSubtitle,getCategoryName,deleteDrug,deleteLog,handlePaste,DRUG_COLUMN_DEFS,LOG_COLUMN_DEFS,recognizeSaving,drugSaving,logSaving,uploadForm,selectedFiles,recognizedDrugs,recognizeResult,recognizeLoading,recognizeWarning,uploadRef,handleFileChange,startRecognize,saveRecognizedDrug,activeMenu,handleMenuSelect,drugs,activeDrugs,logs,loadDrugs,loadActiveDrugs,loadLogs,logFilter,editingDrug,editingLog,drugDialogVisible,logDialogVisible,showDrugDialog,showLogDialog,saveDrug,saveLog'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {' + exposed + ', remove: typeof handleFileRemove === "function" ? handleFileRemove : () => {}}')(...Object.values(bindings)))
  t.after(() => scope.stop())
  return { ...view, patientId, saves, messages, unmount, source }
}
const file = uid => ({ uid, name: `synthetic-${uid}.png`, raw: { name: `synthetic-${uid}.png`, type: 'image/png', size: 10 } })
const recognized = name => ({ code: 200, data: { drugs: [{ drugName: name, defaultDosage: 'unchanged test dose' }] } })


test('medication page titles and descriptions are readable English', t => {
  const view = setup(t)
  for (const [tab, title, description] of [
    ['drugs', 'Medication catalog', 'Manage medication details for use in medication records.'],
    ['logs', 'Medication records', 'Record each dose and review its reported effects.'],
    ['category', 'Browse Categories', 'Browse medications grouped by category.']
  ]) {
    view.activeMenu.value = tab
    assert.equal(view.pageTitle.value, title)
    assert.equal(view.pageSubtitle.value, description)
  }
  assert.equal(view.getCategoryName('VD'), 'Active vitamin D')
  assert.equal(view.DRUG_COLUMN_DEFS.find(x => x.key === 'defaultDosage').label, 'Default dose')
  assert.equal(view.LOG_COLUMN_DEFS.find(x => x.key === 'administrationTime').label, 'Administration time')
  assert.equal(view.LOG_COLUMN_DEFS.find(x => x.key === 'effectEvaluation').label, 'Effect')
})
test('medication deletion prompts use complete English sentences', async t => {
  const prompts = [], view = setup(t, { ElMessageBox: { confirm: async text => { prompts.push(text); throw 'cancel' } } })
  await view.deleteDrug({ id: 1 }); await view.deleteLog({ id: 2 })
  assert.deepEqual(prompts, ['Delete this medication?', 'Delete this medication record?'])
})
test('medication recognition without an upload reports a readable warning', async t => {
  const view = setup(t)
  await view.startRecognize()
  assert.deepEqual(view.messages, [{ level: 'warning', value: 'Upload at least one medication file' }])
})
test('medication recognition timeout shows a readable recovery message', async t => {
  const view = setup(t, { api: { uploadAndRecognize: async () => { throw new Error('timeout') } } })
  view.handleFileChange(file(1)); await view.startRecognize()
  assert.deepEqual(view.messages, [{ level: 'error', value: 'Recognition timed out. Try a smaller file or retry on a faster connection.' }])
})
test('empty recognized medication list shows a readable save warning', async t => {
  const view = setup(t)
  view.handleFileChange(file(1)); await view.startRecognize(); view.recognizedDrugs.value = []
  await view.saveRecognizedDrug()
  assert.equal(view.messages.at(-1).value, 'No medications to save')
})
test('paste limit warning stays readable', t => {
  const view = setup(t); view.activeMenu.value = 'upload'
  for (let i = 0; i < 10; i++) view.handleFileChange(file(i))
  view.handlePaste({ clipboardData: { items: [{ type: 'image/png', getAsFile: () => file(11).raw }] } })
  assert.equal(view.messages.at(-1).value, 'You can upload up to 10 images')
})
test('medication UI labels are readable without changing clinical fields or protocol values', t => {
  const view = setup(t), ast = baseParse(parse(view.source).descriptor.template.content), copy = []
  function visit(node) {
    if (node.type === 2 && node.content.trim()) copy.push(node.content.trim())
    for (const prop of node.props || []) if (prop.type === 6 && ['label', 'placeholder', 'title'].includes(prop.name) && prop.value?.content) copy.push(prop.value.content)
    for (const child of node.children || []) visit(child)
  }
  visit(ast)
  for (const label of ['Upload files', 'Click to upload', 'Default dose', 'Medication category', 'Administration time', 'Effect', 'Enabled', 'Select a medication', 'Administration route', 'Prescribing clinician', 'Describe any adverse reaction', 'For example: 10 mg × 28 tablets', 'For example: 1 tablet once daily', 'For example: 1 tablet']) assert.ok(copy.includes(label), label)
  assert.doesNotMatch(copy.join('\n'), /Uploadfile|DefaultDose|Medicationcategory|administrationTime|validresult|YesNoEnabled|SelectMedication|administrationroute|prescribeClinician|positivein|each times1tablet|times1times|canneedneed|vitamin DD/)
  assert.match(view.source, /editingDrug\.id \? 'Edit medication' : 'Add medication'/)
  assert.match(view.source, /editingLog\.id \? 'Edit medication record' : 'Add medication record'/)
  view.showDrugDialog({ id: 3, drugName: '原始 Drug', defaultDosage: 'exact dose', category: 'VD' })
  assert.equal(view.editingDrug.drugName, '原始 Drug'); assert.equal(view.editingDrug.defaultDosage, 'exact dose'); assert.equal(view.editingDrug.category, 'VD')
})
test('recognized medication save button uses readable singular and plural labels', t => {
  const view = setup(t), ast = baseParse(parse(view.source).descriptor.template.content)
  let expression
  function visit(node) {
    if (node.type === 5 && node.content.content.includes('recognizedDrugs.length > 1')) expression = node.content.content
    for (const child of node.children || []) visit(child)
  }
  visit(ast)
  assert.ok(expression)
  const label = new Function('recognizedDrugs', `return (${expression})`)
  assert.equal(label([{}]), 'Save to medication catalog')
  assert.equal(label([{}, {}]), 'Save 2 medications to catalog')
})
