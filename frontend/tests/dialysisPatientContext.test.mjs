import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope, nextTick } from 'vue'
import * as Vue from 'vue'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'
import ElementPlus, { ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } from 'element-plus'

function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function setup(t, overrides = {}) {
  const source = fs.readFileSync(new URL('../src/views/DialysisManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*? from ['"][^'"]+['"];?\s*$/gm, '')
  const patientId = ref(1), route = reactive({ query: {} }), saves = [], messages = [], unmount = [], downloads = [], scope = effectScope()
  const ok = async () => ({ code: 200, data: [] })
  const bindings = {
    ref, reactive, computed, watch, onMounted() {}, onUnmounted: fn => unmount.push(fn),
    useCurrentPatient: () => ({ currentPatientId: patientId }), useRoute: () => route, useRouter: () => ({}), useTableColumns: () => ({}),
    use() {}, ...Object.fromEntries(['CanvasRenderer', 'EchartsLineChart', 'EchartsBarChart', 'EchartsPieChart', 'GridComponent', 'TooltipComponent', 'LegendComponent', 'TitleComponent', 'ToolboxComponent', 'DataZoomComponent'].map(name => [name, {}])),
    ElMessage: Object.fromEntries(['success', 'warning', 'error', 'info'].map(level => [level, message => messages.push({ level, message })])),
    window: { removeEventListener() {} }, document: { createElement: () => ({ setAttribute(key, value) { this[key] = value }, click() { downloads.push(this) } }), body: { appendChild() {}, removeChild() {} } }, URL: { createObjectURL: () => 'blob:synthetic', revokeObjectURL() {} }, listRecords: ok, getStats: async () => ({ code: 200, data: {} }), getChartData: async () => ({ code: 200, data: {} }),
    getPatientNames: ok, listHistory: ok, analyzeDialysis: async () => ({ code: 200, data: { rawText: 'Synthetic analysis' } }),
    saveRecord: async data => { saves.push(data); return { code: 200 } }, updateRecord: async data => { saves.push(data); return { code: 200 } },
    deleteRecord: ok, deleteAnalysis: ok, saveAnalysis: async data => { saves.push(data); return { code: 200 } }, ...overrides,
  }
  const exposed = 'patientList,formPreview,rules,isMobile,listFilterOverride,activeMenu,records,loading,chartLoading,aiLoading,aiHistory,showAiHistory,aiResult,currentAiResult,formSaving,aiSaving,statsHero,chartDates,chartOnWeight,form,formRef,dialogVisible,isEdit,currentTimeType,currentTimeValue,loadData,loadAllRecords,loadStats,loadAiAnalysis,loadAiHistory,handleSaveAnalysis,handleAdd,handleEdit,goToDataEntry,handleDelete,handleDeleteAnalysis,submitForm,onTimeChange,viewHistoryItem,handleExportData'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + `\nreturn {${exposed}}`)(...Object.values(bindings)))
  t.after(() => scope.stop())
  return { ...view, source, patientId, route, saves, messages, downloads, leave: () => unmount.forEach(fn => fn()) }
}
function draft(view, remark = 'Synthetic draft') {
  view.handleAdd(); Object.assign(view.form, { recordDate: '2026-10-02', onWeight: 60, offWeight: 59, remark })
  view.formRef.value = { validate: async () => true }
}

// Each case executes the actual SFC script. Removing its ownership guard must fail.
test('patient switching immediately discards the open editor and all prior patient output', async t => {
  const pending = deferred(), view = setup(t, { listRecords: () => pending.promise })
  view.handleEdit({ id: 11, patientId: 1, recordDate: '2026-10-01', remark: 'Synthetic A' })
  view.records.value = [{ patientId: 1 }]; view.aiResult.value = 'Synthetic A'; view.currentAiResult.value = { rawText: 'Synthetic A' }
  view.aiHistory.value = [{ patientId: 1 }]; view.statsHero.value = { totalCount: 1 }; view.chartDates.value = ['2026-10-01']
  view.patientId.value = 2
  assert.equal(view.dialogVisible.value, false); assert.equal(view.form.id, null); assert.equal(view.form.remark, '')
  assert.equal(view.form.patientId, 2); assert.deepEqual(view.records.value, []); assert.equal(view.aiResult.value, '')
  assert.equal(view.currentAiResult.value, null); assert.deepEqual(view.aiHistory.value, []); assert.equal(view.statsHero.value, null); assert.deepEqual(view.chartDates.value, [])
  pending.resolve({ code: 200, data: [] }); await nextTick()
})
test('analysis from A cannot be submitted for B', async t => {
  const view = setup(t)
  await view.loadAiAnalysis(); view.patientId.value = 2; await view.handleSaveAnalysis()
  assert.equal(view.saves.length, 0)
})
test('old analysis cannot be relabelled to a new time period', async t => {
  const view = setup(t); view.currentTimeValue.value = '2026-09'
  await view.loadAiAnalysis(); view.currentTimeValue.value = '2026-10'; await view.handleSaveAnalysis()
  assert.equal(view.saves.length, 0); assert.equal(view.currentAiResult.value, null)
})
for (const method of ['loadData', 'loadAllRecords', 'loadStats', 'loadAiAnalysis', 'loadAiHistory']) {
  const dependency = { loadData: 'listRecords', loadAllRecords: 'listRecords', loadStats: 'getChartData', loadAiAnalysis: 'analyzeDialysis', loadAiHistory: 'listHistory' }[method]
  const value = name => method === 'loadStats' ? { dateList: [name] } : method === 'loadAiAnalysis' ? { rawText: name } : [{ marker: name }]
  const read = view => method === 'loadStats' ? view.chartDates.value[0] : method === 'loadAiAnalysis' ? view.aiResult.value : (method === 'loadAiHistory' ? view.aiHistory : view.records).value[0]?.marker
  test(`${method}: older same-patient responses cannot replace the latest data`, async t => {
    const first = deferred(); let calls = 0
    const view = setup(t, { [dependency]: () => ++calls === 1 ? first.promise : Promise.resolve({ code: 200, data: value('New') }) })
    const old = view[method](); await view[method](); first.resolve({ code: 200, data: value('Old') }); await old
    assert.equal(read(view), 'New')
  })
  for (const change of ['patient-round-trip', 'leave']) {
    test(`${method}: ${change} invalidates a pending response`, async t => {
      const first = deferred(); let calls = 0
      const empty = method === 'loadStats' ? {} : method === 'loadAiAnalysis' ? { rawText: '' } : []
      const view = setup(t, { [dependency]: () => ++calls === 1 ? first.promise : Promise.resolve({ code: 200, data: empty }) })
      const old = view[method]()
      if (change === 'leave') view.leave(); else { view.patientId.value = 2; view.patientId.value = 1 }
      first.resolve({ code: 200, data: value('Obsolete') }); await old; await nextTick()
      assert.notEqual(read(view), 'Obsolete')
    })
  }
}
for (const [method, dependency, loading] of [['loadData', 'listRecords', 'loading'], ['loadStats', 'getChartData', 'chartLoading'], ['loadAiAnalysis', 'analyzeDialysis', 'aiLoading']]) {
  test(`${method}: an old failure cannot clear a new loading indicator or display an obsolete error`, async t => {
    const first = deferred(), second = deferred(); let calls = 0
    const view = setup(t, { [dependency]: () => ++calls === 1 ? first.promise : second.promise })
    const old = view[method](), newer = view[method]()
    first.reject(new Error('Old failure')); await old
    assert.equal(view[loading].value, true); assert.equal(view.messages.length, 0)
    second.resolve({ code: 200, data: method === 'loadStats' ? {} : method === 'loadAiAnalysis' ? { rawText: 'New' } : [] }); await newer
    assert.equal(view[loading].value, false)
  })
}
test('clearing the selected patient makes no unscoped reads', async t => {
  const calls = [], record = (...args) => { calls.push(args); return Promise.resolve({ code: 200, data: [] }) }
  const view = setup(t, { listRecords: record, getStats: record, getChartData: record, analyzeDialysis: record, listHistory: record })
  view.patientId.value = null; await nextTick()
  await Promise.all([view.loadData(), view.loadAllRecords(), view.loadStats(), view.loadAiAnalysis(), view.loadAiHistory()])
  assert.deepEqual(calls, [])
})
test('filtered and all-history records share a latest-request boundary', async t => {
  const first = deferred(); let calls = 0
  const view = setup(t, { listRecords: () => ++calls === 1 ? first.promise : Promise.resolve({ code: 200, data: [{ marker: 'All history' }] }) })
  const old = view.loadData(); await view.loadAllRecords(); first.resolve({ code: 200, data: [{ marker: 'Filtered old' }] }); await old
  assert.equal(view.records.value[0].marker, 'All history')
})
for (const change of ['patient', 'patient-round-trip', 'close-reopen', 'leave', 'corrected-draft']) {
  test(`validation interrupted by ${change} cannot transmit an obsolete edit`, async t => {
    const validation = deferred(), view = setup(t); draft(view); view.formRef.value = { validate: () => validation.promise }
    const pending = view.submitForm()
    if (change.startsWith('patient')) { view.patientId.value = 2; if (change === 'patient-round-trip') view.patientId.value = 1 }
    else if (change === 'close-reopen') { view.dialogVisible.value = false; draft(view, 'New draft') }
    else if (change === 'leave') view.leave()
    else view.form.remark = 'Corrected draft'
    validation.resolve(true); await pending
    assert.equal(view.saves.length, 0)
  })
}
test('same-tick save clicks submit one detached form snapshot', async t => {
  const validation = deferred(), view = setup(t); draft(view); view.formRef.value = { validate: () => validation.promise }
  const first = view.submitForm(), duplicate = view.submitForm(); validation.resolve(true); await Promise.all([first, duplicate])
  assert.equal(view.saves.length, 1); assert.equal(view.saves[0].patientId, 1); assert.equal(view.saves[0].remark, 'Synthetic draft')
})
for (const samePatient of [false, true]) {
  test(`late save cannot close a newer ${samePatient ? 'same-patient' : 'patient B'} editor`, async t => {
    const first = deferred(), started = deferred(), view = setup(t, { saveRecord: () => { started.resolve(); return first.promise } }); draft(view)
    const pending = view.submitForm(); await started.promise; view.dialogVisible.value = false
    if (!samePatient) view.patientId.value = 2
    draft(view, 'New draft'); first.resolve({ code: 200 }); await pending
    assert.equal(view.dialogVisible.value, true); assert.equal(view.form.remark, 'New draft'); assert.equal(view.messages.length, 0)
  })
}
test('failed save preserves the draft and permits retry', async t => {
  let calls = 0; const view = setup(t, { saveRecord: async () => { if (++calls === 1) throw new Error('offline'); return { code: 200 } } }); draft(view)
  await view.submitForm(); assert.equal(view.dialogVisible.value, true); assert.equal(view.form.remark, 'Synthetic draft')
  await view.submitForm(); assert.equal(calls, 2); assert.equal(view.dialogVisible.value, false)
})
test('changing tabs discards pending AI output', async t => {
  const first = deferred(), view = setup(t, { analyzeDialysis: () => first.promise }); view.activeMenu.value = 'ai'; view.route.query.tab = 'ai'; await nextTick()
  const pending = view.loadAiAnalysis(); view.route.query.tab = 'data'; await nextTick()
  first.resolve({ code: 200, data: { rawText: 'Old hidden analysis' } }); await pending
  assert.equal(view.aiResult.value, ''); assert.equal(view.currentAiResult.value, null)
})

test('CSV export explicitly requests only the originating selected patient', async t => {
  const calls = [], view = setup(t, { listRecords: async (...args) => { calls.push(args); return { code: 200, data: [{ patientId: 1, recordDate: '2026-10-02' }] } } })
  view.currentTimeValue.value = '2026-10'; await view.handleExportData()
  assert.equal(calls.length, 1); assert.deepEqual(calls[0], ['month', '2026-10', 1])
  assert.equal(view.downloads.length, 1); assert.match(view.downloads[0].download, /1.*2026-10/)
})
for (const change of ['patient', 'period', 'view', 'leave']) {
  test(`CSV export interrupted by ${change} does not download stale content`, async t => {
    const first = deferred(), view = setup(t, { listRecords: () => first.promise })
    const pending = view.handleExportData()
    if (change === 'patient') view.patientId.value = 2
    else if (change === 'period') view.currentTimeValue.value = '2026-09'
    else if (change === 'view') { view.route.query.tab = 'ai'; await nextTick() }
    else view.leave()
    first.resolve({ code: 200, data: [{ patientId: 1, recordDate: '2026-10-02' }] }); await pending
    assert.equal(view.downloads.length, 0)
  })
}

for (const [action, dependency] of [['handleDelete', 'deleteRecord'], ['handleDeleteAnalysis', 'deleteAnalysis']]) {
  test(`${action}: a late deletion cannot refresh or announce success in the new patient context`, async t => {
    const first = deferred(), reads = [], view = setup(t, { [dependency]: () => first.promise, listRecords: async (...args) => { reads.push(args); return { code: 200, data: [] } }, listHistory: async (...args) => { reads.push(args); return { code: 200, data: [] } } })
    const pending = view[action](41); view.patientId.value = 2; await nextTick(); const before = reads.length
    first.resolve({ code: 200 }); await pending
    assert.equal(reads.length, before); assert.equal(view.messages.length, 0)
  })
  test(`${action}: same-row duplicate clicks share one operation and failure permits retry`, async t => {
    const first = deferred(); let calls = 0
    const view = setup(t, { [dependency]: () => { calls++; return calls === 1 ? first.promise : Promise.resolve({ code: 200 }) } })
    const pending = view[action](41), duplicate = view[action](41); assert.equal(calls, 1)
    first.reject(new Error('offline')); await Promise.all([pending, duplicate]); await view[action](41); assert.equal(calls, 2)
  })
}
test('an old editor save cannot release a newer editor save lock', async t => {
  const first = deferred(), second = deferred(), firstStarted = deferred(), secondStarted = deferred(); let calls = 0
  const view = setup(t, { saveRecord: () => { if (++calls === 1) { firstStarted.resolve(); return first.promise }; secondStarted.resolve(); return second.promise } }); draft(view)
  const old = view.submitForm(); await firstStarted.promise; view.dialogVisible.value = false; draft(view, 'New draft')
  const newer = view.submitForm(); await secondStarted.promise; assert.equal(calls, 2); first.resolve({ code: 200 }); await old
  assert.equal(view.formSaving.value, true); assert.equal(view.dialogVisible.value, true)
  second.resolve({ code: 200 }); await newer; assert.equal(view.formSaving.value, false)
})
test('old analysis save completion cannot unlock the new patient analysis save', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const view = setup(t, { saveAnalysis: () => ++calls === 1 ? first.promise : second.promise })
  await view.loadAiAnalysis(); const old = view.handleSaveAnalysis(); view.patientId.value = 2
  await view.loadAiAnalysis(); const newer = view.handleSaveAnalysis(); first.resolve({ code: 200 }); await old
  assert.equal(view.aiSaving.value, true); assert.equal(view.messages.length, 0)
  second.resolve({ code: 200 }); await newer; assert.equal(view.aiSaving.value, false)
})
test('analysis save keeps all originating clinical values unchanged and permits failure retry', async t => {
  const analysis = { rawText: 'Synthetic reviewed text', periodLabel: 'Synthetic period', dwAdjustNeeded: false, dwAdjustAmount: -0.25, dwTargetWeight: 59, dwAdjustReason: 'Synthetic reason', weightControlEval: 'Good', dehydrationEval: 'Fair', bpControlEval: 'Good', mainRisk: 'Synthetic risk', dietAdvice: 'Synthetic diet', fluidAdvice: 'Synthetic fluid', exerciseAdvice: 'Synthetic exercise', medicationAdvice: 'Synthetic unchanged instructions', followUpAdvice: 'Synthetic review', totalCount: 2, avgOnWeight: 60, avgOffWeight: 59, avgWeightGain: 1, avgUfAmount: 1, dehydrationMatchRate: 50 }
  const saved = []; let calls = 0
  const view = setup(t, { analyzeDialysis: async () => ({ code: 200, data: analysis }), saveAnalysis: async data => { saved.push(data); if (++calls === 1) throw new Error('offline'); return { code: 200 } } })
  view.currentTimeValue.value = '2026-10'; await view.loadAiAnalysis(); await view.handleSaveAnalysis(); assert.equal(view.aiSaving.value, false)
  await view.handleSaveAnalysis(); assert.equal(calls, 2)
  const { rawText, ...values } = analysis
  assert.deepEqual(saved[1], { ...values, analysisContent: rawText, patientId: 1, timeType: 'month', timeValue: '2026-10' })
})

// Compile the shipped editor form and render real Element Plus controls: an explicit
// false disabled prop can override the parent form, so script-only assertions miss it.
async function renderEditorPatientInput(view) {
  const editorForm = view.source.match(/<el-form :model="form"[\s\S]*?<\/el-form>/)?.[0]
  assert.ok(editorForm, 'The shipped editor form must be exercised')
  const render = new Function('Vue', compile(editorForm, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  const app = Vue.createSSRApp({ render, setup: () => view })
  app.use(ElementPlus)
  app.provide(ID_INJECTION_KEY, { prefix: 21, current: 0 })
  app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
  const html = await renderToString(app)
  const input = html.match(/<input\b[^>]*class="[^"]*el-select__input[^"]*"[^>]*>/)?.[0]
  assert.ok(input, 'The real filterable patient input must be rendered')
  return input
}
for (const phase of ['validation', 'transmission']) {
  test(`compiled editor keeps the Add patient picker disabled during ${phase}`, async t => {
    const pending = deferred(), started = deferred(), view = setup(t, phase === 'transmission' ? { saveRecord: () => { started.resolve(); return pending.promise } } : {})
    draft(view)
    if (phase === 'validation') view.formRef.value = { validate: () => pending.promise }
    const work = view.submitForm()
    if (phase === 'transmission') await started.promise
    assert.equal(view.formSaving.value, true)
    assert.match(await renderEditorPatientInput(view), /\sdisabled(?:\s|=|>)/)
    pending.resolve(phase === 'validation' ? true : { code: 200 }); await work
  })
}
test('compiled editor retains the enabled Add picker and locked Edit patient outside saves', async t => {
  const view = setup(t); draft(view)
  assert.doesNotMatch(await renderEditorPatientInput(view), /\sdisabled(?:\s|=|>)/)
  view.handleEdit({ id: 41, patientId: 1, recordDate: '2026-10-01' })
  assert.match(await renderEditorPatientInput(view), /\sdisabled(?:\s|=|>)/)
})
for (const action of ['save', 'delete']) {
  for (const chooseScope of ['before mutation', 'during mutation']) {
    test(`${action} refresh keeps All history selected ${chooseScope}`, async t => {
      const saved = deferred(), started = deferred(), calls = []
      const view = setup(t, {
        [action === 'save' ? 'updateRecord' : 'deleteRecord']: () => { started.resolve(); return saved.promise },
        listRecords: async (...args) => { calls.push(args); return { code: 200, data: [{ id: 41, patientId: 1, recordDate: '2026-09-01', marker: args[0] === null ? 'all' : 'filtered' }] } },
      })
      view.currentTimeValue.value = '2026-10'
      if (chooseScope === 'before mutation') await view.loadAllRecords()
      let work
      if (action === 'save') { view.handleEdit({ id: 41, patientId: 1, recordDate: '2026-09-01', remark: 'Synthetic edit' }); view.formRef.value = { validate: async () => true }; work = view.submitForm() }
      else work = view.handleDelete(41)
      await started.promise
      if (chooseScope === 'during mutation') await view.loadAllRecords()
      saved.resolve({ code: 200 }); await work; await nextTick()
      assert.equal(view.records.value[0].marker, 'all')
      assert.equal(view.listFilterOverride.value, 'Allhistory')
      assert.deepEqual(calls.at(-1), [null, '', 1])
    })
  }
}

test('Go enter data reloads patient records after an empty analysis cleared the list', async t => {
  const calls = [], row = { id: 41, patientId: 1, recordDate: '2026-10-01', recordType: 'INCOMPLETE' }
  const view = setup(t, { listRecords: async (...args) => { calls.push(args); return { code: 200, data: [row] } } })
  view.currentTimeValue.value = '2026-10'; await view.loadData()
  view.route.query.tab = 'analysis'; await view.loadStats()
  assert.deepEqual(view.records.value, [])
  assert.equal(view.statsHero.value, null)
  await view.goToDataEntry()
  assert.equal(view.activeMenu.value, 'data'); assert.deepEqual(view.records.value.map(record => record.id), [41])
  assert.deepEqual(calls, [['month', '2026-10', 1], ['month', '2026-10', 1]])
})
test('Go enter data invalidates an older pending analysis response', async t => {
  const pending = deferred(), view = setup(t, { getChartData: () => pending.promise })
  view.activeMenu.value = 'analysis'; const work = view.loadStats()
  await view.goToDataEntry()
  pending.resolve({ code: 200, data: { dateList: ['Obsolete analysis'] } }); await work
  assert.equal(view.activeMenu.value, 'data'); assert.deepEqual(view.chartDates.value, [])
})
