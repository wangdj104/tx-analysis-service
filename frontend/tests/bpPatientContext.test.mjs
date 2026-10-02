import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import { reactive, ref, computed, watch, effectScope, nextTick, compile, createSSRApp, createRenderer, h, onMounted, onUnmounted } from 'vue'
import { renderToString } from 'vue/server-renderer'
import ElementPlus, { ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } from 'element-plus'
import { Check, Plus, Refresh, TrendCharts } from '@element-plus/icons-vue'

const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const record = (patientId = 1, id = patientId * 10) => ({ id, patientId, recordDate: '2026-10-02', measureType: 'BP', systolicBp: 120, diastolicBp: 80, remark: 'Synthetic draft' })
function setup(t, component = 'monitor', overrides = {}) {
  const patientId = ref(1), messages = [], writes = [], reads = [], deletions = [], errors = [], unmountCallbacks = [], scope = effectScope()
  const name = component === 'monitor' ? 'BpSelfMonitorManager' : 'BpPatternManager'
  const bindings = {
    ref, reactive, computed, watch, onMounted() {}, onUnmounted: fn => unmountCallbacks.push(fn),
    useCurrentPatient: () => ({ currentPatientId: patientId }), useMobile: () => ({ isMobile: ref(false) }), localDateKey: () => '2026-10-02',
    ElMessage: Object.fromEntries(['warning', 'error', 'success'].map(kind => [kind, text => messages.push({ kind, text })])),
    console: { error: value => errors.push(value) },
    listBpSelfMonitorRecords: async (...args) => { reads.push(args); return { code: 200, data: [record(args[0])] } },
    listBpPatterns: async (...args) => { reads.push(args); return { code: 200, data: [record(args[0])] } },
    saveBpSelfMonitorRecord: async data => { writes.push({ operation: 'save', data }); return { code: 200 } },
    updateBpSelfMonitorRecord: async data => { writes.push({ operation: 'update', data }); return { code: 200 } },
    analyzeBpPattern: async (...args) => { writes.push(args); return { code: 200 } },
    deleteBpSelfMonitorRecord: async id => { deletions.push(id); return { code: 200 } },
    deleteBpPattern: async id => { deletions.push(id); return { code: 200 } }, ...overrides
  }
  const source = fs.readFileSync(new URL(`../src/views/${name}.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const exposed = component === 'monitor'
    ? 'records,loading,saving,form,formRef,editingId,filterType,loadRecords,handleSave,handleEdit,resetForm,handleDelete,latestBpText,latestBgText,formRules,showBpFields,showBgFields,measureTagType,measureLabel'
    : 'historyList,currentAnalysis,detailVisible,detailRecord,timeType,timeValue,analyzing,loadData,handleAnalyze,showDetail,handleDelete,onDimensionChange,onDateChange'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {' + exposed + (component === 'monitor' ? ', periodLabel: typeof periodLabel === "function" ? periodLabel : undefined' : '') + '}')(...Object.values(bindings)))
  if (component === 'monitor') view.formRef.value = { validate: async () => true, resetFields() {} }
  else view.timeValue.value = '2026-10'
  const unmount = () => { unmountCallbacks.forEach(fn => fn()); scope.stop() }
  t.after(unmount)
  return { ...view, patientId, messages, writes, reads, deletions, errors, unmount, source }
}
const settle = async () => { await nextTick(); await nextTick() }

for (const component of ['monitor', 'pattern']) {
  const listKey = component === 'monitor' ? 'listBpSelfMonitorRecords' : 'listBpPatterns'
  const load = view => component === 'monitor' ? view.loadRecords() : view.loadData()
  const rows = view => component === 'monitor' ? view.records : view.historyList
  test(`${component}: switching patients clears displayed rows synchronously`, async t => {
    const pending = deferred(), v = setup(t, component, { [listKey]: () => pending.promise })
    rows(v).value = [record()]
    if (component === 'pattern') { v.currentAnalysis.value = record(); v.showDetail(record()) }
    v.patientId.value = 2
    assert.deepEqual(rows(v).value, [])
    if (component === 'pattern') { assert.equal(v.currentAnalysis.value, null); assert.equal(v.detailVisible.value, false); assert.equal(v.detailRecord.value, null) }
    pending.resolve({ code: 200, data: [] }); await settle()
  })
  test(`${component}: late A response cannot replace B rows`, async t => {
    const old = deferred(), v = setup(t, component, { [listKey]: async id => id === 1 ? old.promise : { code: 200, data: [record(2)] } })
    const pending = load(v); v.patientId.value = 2; await settle()
    assert.deepEqual(rows(v).value, [record(2)])
    old.resolve({ code: 200, data: [record()] }); await pending
    assert.deepEqual(rows(v).value, [record(2)])
    if (component === 'pattern') assert.equal(v.currentAnalysis.value.patientId, 2)
  })
  for (const jump of ['clear', 'A-B-A', 'unmount']) {
    test(`${component}: old list result is invalid after ${jump}`, async t => {
      const old = deferred(); let calls = 0
      const v = setup(t, component, { [listKey]: () => ++calls === 1 ? old.promise : Promise.resolve({ code: 200, data: [] }) })
      const pending = load(v)
      if (jump === 'clear') v.patientId.value = null
      else if (jump === 'A-B-A') { v.patientId.value = 2; v.patientId.value = 1 }
      else v.unmount()
      old.resolve({ code: 200, data: [record()] }); await pending; await settle()
      assert.deepEqual(rows(v).value, [])
    })
  }
  for (const outcome of ['success', 'failure', 'rejection']) {
    test(`${component}: latest same-patient refresh owns completion (${outcome})`, async t => {
      const first = deferred(), second = deferred(); let calls = 0
      const v = setup(t, component, { [listKey]: () => ++calls === 1 ? first.promise : second.promise })
      const a = load(v), b = load(v)
      if (outcome === 'rejection') first.reject(new Error('synthetic stale list'))
      else first.resolve({ code: outcome === 'success' ? 200 : 500, data: [record()], msg: 'obsolete' })
      await a
      assert.deepEqual(rows(v).value, [])
      assert.deepEqual(v.messages, []); assert.deepEqual(v.errors, [])
      if (component === 'monitor') assert.equal(v.loading.value, true)
      second.resolve({ code: 200, data: [record(1, 11)] }); await b
      assert.deepEqual(rows(v).value, [record(1, 11)])
      if (component === 'monitor') assert.equal(v.loading.value, false)
    })
  }
}

test('monitor: filter changes clear stale rows immediately and bind the list request', async t => {
  const old = deferred(), newer = deferred(), calls = []
  const v = setup(t, 'monitor', { listBpSelfMonitorRecords: (...args) => { calls.push(args); return calls.length === 1 ? old.promise : newer.promise } })
  v.records.value = [record()]; const pending = v.loadRecords()
  v.filterType.value = 'BG'
  assert.deepEqual(v.records.value, [])
  await settle()
  assert.deepEqual(calls, [[1, undefined], [1, 'BG']])
  old.resolve({ code: 200, data: [record()] }); await pending
  assert.equal(v.loading.value, true)
  newer.resolve({ code: 200, data: [] }); await settle(); assert.equal(v.loading.value, false)
})

for (const jump of ['patient', 'A-B-A', 'reset', 'unmount']) {
  test(`monitor: ${jump} invalidates pending validation before dispatch`, async t => {
    const validation = deferred(), v = setup(t)
    v.formRef.value.validate = () => validation.promise
    const pending = v.handleSave()
    if (jump === 'patient') v.patientId.value = 2
    else if (jump === 'A-B-A') { v.patientId.value = 2; v.patientId.value = 1 }
    else if (jump === 'reset') v.resetForm()
    else v.unmount()
    v.form.remark = 'replacement'
    validation.resolve(true); await pending
    assert.equal(v.writes.length, 0); assert.equal(v.form.remark, 'replacement'); assert.equal(v.saving.value, false)
  })
}
for (const jump of ['patient', 'reset', 'unmount']) {
  for (const outcome of ['success', 'failure', 'rejection']) {
    test(`monitor: ${outcome} save completion is inert after ${jump}`, async t => {
      const operation = deferred(), v = setup(t, 'monitor', { saveBpSelfMonitorRecord: () => operation.promise })
      const pending = v.handleSave(); await settle()
      if (jump === 'patient') v.patientId.value = 2
      else if (jump === 'reset') v.resetForm()
      else v.unmount()
      await settle(); const readCount = v.reads.length
      v.form.remark = 'replacement'
      if (outcome === 'rejection') operation.reject(new Error('synthetic obsolete save'))
      else operation.resolve({ code: outcome === 'success' ? 200 : 500, msg: 'obsolete save' })
      await pending
      assert.equal(v.form.remark, 'replacement'); assert.deepEqual(v.messages, []); assert.equal(v.reads.length, readCount)
    })
  }
}
test('monitor: save captures immutable patient, operation, record, and fields before validation', async t => {
  const validation = deferred(), v = setup(t)
  v.handleEdit(record()); v.formRef.value.validate = () => validation.promise
  const pending = v.handleSave(); v.form.remark = 'later model change'; v.editingId.value = null
  validation.resolve(true); await pending
  assert.equal(v.writes.length, 1)
  assert.equal(v.writes[0].operation, 'update'); assert.equal(v.writes[0].data.patientId, 1); assert.equal(v.writes[0].data.id, 10); assert.equal(v.writes[0].data.remark, 'Synthetic draft')
})
test('monitor: repeated save clicks share one validation and one API write', async t => {
  const validation = deferred(), v = setup(t); let validations = 0
  v.formRef.value.validate = () => { validations++; return validation.promise }
  const a = v.handleSave(), b = v.handleSave()
  assert.equal(v.saving.value, true)
  validation.resolve(true); await Promise.all([a, b])
  assert.equal(validations, 1); assert.equal(v.writes.length, 1)
})
test('monitor: the previous save cannot release a newer patient save lock', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const v = setup(t, 'monitor', { saveBpSelfMonitorRecord: () => ++calls === 1 ? first.promise : second.promise })
  const a = v.handleSave(); await settle(); v.patientId.value = 2
  const b = v.handleSave(); await settle()
  first.resolve({ code: 200 }); await a; assert.equal(v.saving.value, true)
  second.resolve({ code: 500 }); await b; assert.equal(v.saving.value, false)
})
for (const failure of ['validation', 'response', 'rejection']) {
  test(`monitor: ${failure} failure preserves the draft and permits retry`, async t => {
    let calls = 0
    const v = setup(t, 'monitor', { saveBpSelfMonitorRecord: async () => { calls++; if (calls === 1 && failure === 'response') return { code: 500 }; if (calls === 1 && failure === 'rejection') throw new Error('synthetic failure'); return { code: 200 } } })
    v.form.remark = 'retain me'
    if (failure === 'validation') v.formRef.value.validate = async () => { throw new Error('validation') }
    await v.handleSave(); assert.equal(v.form.remark, 'retain me'); assert.equal(v.saving.value, false)
    v.formRef.value.validate = async () => true; await v.handleSave(); assert.equal(v.form.remark, ''); assert.equal(v.saving.value, false)
  })
}
for (const rowPatient of [undefined, 2]) {
  test(`monitor: edit rejects a row without current patient ownership (${rowPatient})`, t => {
    const v = setup(t); v.form.remark = 'keep'; v.handleEdit({ ...record(), patientId: rowPatient })
    assert.equal(v.editingId.value, null); assert.equal(v.form.remark, 'keep')
  })
}
test('monitor: editing is locked while save validation is pending', async t => {
  const validation = deferred(), v = setup(t); v.formRef.value.validate = () => validation.promise
  v.form.remark = 'first draft'; const pending = v.handleSave(); v.handleEdit(record(1, 11))
  assert.equal(v.editingId.value, null); assert.equal(v.form.remark, 'first draft')
  validation.reject(new Error('validation')); await pending
})

async function renderMonitor(view) {
  const template = view.source.match(/<template>([\s\S]*?)<\/template>\s*<script setup>/)[1]
  const app = createSSRApp({ setup: () => view, render: compile(template) })
  app.use(ElementPlus)
  for (const [name, component] of Object.entries({ Check, Plus, Refresh, TrendCharts })) app.component(name, component)
  app.component('VitalsTrendPanel', { render: () => null })
  app.provide(ID_INJECTION_KEY, { prefix: 100, current: 0 }); app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
  const html = await renderToString(app)
  const entry = html.match(/<div class="content-panel entry-panel">([\s\S]*?)<\/form>/)?.[1]
  assert.ok(entry)
  return [...entry.matchAll(/<(?:input|textarea|button)\b[^>]*>/g)].map(match => match[0])
}
for (const measureType of ['BP', 'BG', 'BP_BG']) test(`monitor: actual rendered ${measureType} controls lock throughout validation and recover after failure`, async t => {
  const validation = deferred(), v = setup(t); v.formRef.value.validate = () => validation.promise
  v.form.measureType = measureType
  const before = await renderMonitor(v); assert.ok(before.length > 5)
  for (const control of before) assert.equal(/\sdisabled(?:\s|=|>)/.test(control), false, control)
  const pending = v.handleSave()
  for (const control of await renderMonitor(v)) assert.equal(/\sdisabled(?:\s|=|>)/.test(control), true, control)
  validation.reject(new Error('validation')); await pending
  for (const control of await renderMonitor(v)) assert.equal(/\sdisabled(?:\s|=|>)/.test(control), false, control)
})

for (const rowPatient of [undefined, 2]) {
  test(`pattern: detail rejects a row without current patient ownership (${rowPatient})`, t => {
    const v = setup(t, 'pattern'); v.showDetail({ ...record(), patientId: rowPatient })
    assert.equal(v.detailVisible.value, false); assert.equal(v.detailRecord.value, null)
  })
}
test('pattern: closing detail clears its retained record immediately', t => {
  const v = setup(t, 'pattern'); v.showDetail(record()); assert.equal(v.detailRecord.value.id, 10)
  v.detailVisible.value = false; assert.equal(v.detailRecord.value, null)
})
test('pattern: repeated analyze clicks only dispatch once', async t => {
  const operation = deferred(); let calls = 0
  const v = setup(t, 'pattern', { analyzeBpPattern: () => { calls++; return operation.promise } })
  const a = v.handleAnalyze(), b = v.handleAnalyze(); assert.equal(calls, 1)
  operation.resolve({ code: 200 }); await Promise.all([a, b]); assert.equal(v.analyzing.value, false)
})
for (const jump of ['patient', 'clear', 'A-B-A', 'period', 'period-round-trip', 'unmount']) {
  for (const outcome of ['success', 'failure', 'rejection']) {
    test(`pattern: ${outcome} analysis completion is inert after ${jump}`, async t => {
      const operation = deferred(), v = setup(t, 'pattern', { analyzeBpPattern: () => operation.promise })
      const pending = v.handleAnalyze()
      if (jump === 'patient') v.patientId.value = 2
      else if (jump === 'clear') v.patientId.value = null
      else if (jump === 'A-B-A') { v.patientId.value = 2; v.patientId.value = 1 }
      else if (jump === 'period') v.timeType.value = 'year'
      else if (jump === 'period-round-trip') { v.timeValue.value = '2026-09'; v.timeValue.value = '2026-10' }
      else v.unmount()
      await settle(); const reads = v.reads.length
      assert.equal(v.analyzing.value, false)
      if (outcome === 'rejection') operation.reject(new Error('obsolete analysis'))
      else operation.resolve({ code: outcome === 'success' ? 200 : 500, msg: 'obsolete analysis' })
      await pending
      assert.deepEqual(v.messages, []); assert.equal(v.reads.length, reads)
    })
  }
}
test('pattern: old analysis cannot release a newer request lock', async t => {
  const old = deferred(), current = deferred(); let calls = 0
  const v = setup(t, 'pattern', { analyzeBpPattern: () => ++calls === 1 ? old.promise : current.promise })
  const a = v.handleAnalyze(); v.timeValue.value = '2026-09'; const b = v.handleAnalyze()
  old.resolve({ code: 200 }); await a; assert.equal(v.analyzing.value, true)
  current.resolve({ code: 500 }); await b; assert.equal(v.analyzing.value, false)
})
test('pattern: active analysis failure preserves its context and allows retry', async t => {
  let calls = 0
  const v = setup(t, 'pattern', { analyzeBpPattern: async () => ({ code: ++calls === 1 ? 500 : 200 }) })
  await v.handleAnalyze(); assert.equal(v.analyzing.value, false); assert.equal(v.timeValue.value, '2026-10')
  await v.handleAnalyze(); assert.equal(calls, 2); assert.equal(v.messages.length, 2); assert.equal(v.reads.length, 1)
})

for (const component of ['monitor', 'pattern']) {
  const key = component === 'monitor' ? 'deleteBpSelfMonitorRecord' : 'deleteBpPattern'
  const prime = view => { if (component === 'monitor') view.records.value = [record()]; else view.historyList.value = [record()] }
  const remove = view => view.handleDelete(component === 'monitor' ? record() : 10)
  test(`${component}: repeated deletion is single flight per record`, async t => {
    const operation = deferred(); let calls = 0
    const v = setup(t, component, { [key]: () => { calls++; return operation.promise } }); prime(v)
    const a = remove(v), b = remove(v); assert.equal(calls, 1)
    operation.resolve({ code: 200 }); await Promise.all([a, b]); assert.equal(v.messages.length, 1)
  })
  for (const outcome of ['success', 'failure', 'rejection']) {
    test(`${component}: obsolete delete ${outcome} cannot message or reload another patient`, async t => {
      const operation = deferred(), v = setup(t, component, { [key]: () => operation.promise }); prime(v)
      const pending = remove(v); v.patientId.value = 2; await settle(); const reads = v.reads.length
      if (outcome === 'rejection') operation.reject(new Error('obsolete deletion'))
      else operation.resolve({ code: outcome === 'success' ? 200 : 500, msg: 'obsolete deletion' })
      await pending; assert.deepEqual(v.messages, []); assert.equal(v.reads.length, reads)
    })
  }
  test(`${component}: stale deletion callback cannot dispatch after patient selection changes`, async t => {
    const v = setup(t, component); prime(v); v.patientId.value = 2
    await remove(v); assert.deepEqual(v.deletions, [])
  })
  test(`${component}: failed deletion permits deliberate retry`, async t => {
    let calls = 0
    const v = setup(t, component, { [key]: async () => ({ code: ++calls === 1 ? 500 : 200 }) }); prime(v)
    await remove(v); await remove(v); assert.equal(calls, 2)
  })
}


for (const component of ['monitor', 'pattern']) {
  test(`${component}: no write or detail action dispatches after unmount`, async t => {
    const v = setup(t, component)
    v.unmount()
    if (component === 'monitor') { v.handleEdit(record()); await v.handleSave(); await v.handleDelete(record()) }
    else { v.historyList.value = [record()]; v.showDetail(record()); await v.handleAnalyze(); await v.handleDelete(10) }
    assert.deepEqual(v.writes, []); assert.deepEqual(v.deletions, []); assert.deepEqual(v.messages, [])
    if (component === 'pattern') assert.equal(v.detailVisible.value, false)
  })
  const deleteKey = component === 'monitor' ? 'deleteBpSelfMonitorRecord' : 'deleteBpPattern'
  const prime = view => { if (component === 'monitor') view.records.value = [record()]; else view.historyList.value = [record()] }
  const remove = view => view.handleDelete(component === 'monitor' ? record() : 10)
  for (const jump of ['A-B-A', 'unmount']) {
    test(`${component}: a pending delete is inert after ${jump}`, async t => {
      const operation = deferred(), v = setup(t, component, { [deleteKey]: () => operation.promise })
      prime(v); const pending = remove(v)
      if (jump === 'A-B-A') { v.patientId.value = 2; v.patientId.value = 1 } else v.unmount()
      await settle(); const readCount = v.reads.length
      operation.resolve({ code: 200 }); await pending
      assert.deepEqual(v.messages, []); assert.equal(v.reads.length, readCount)
    })
  }
  test(`${component}: an old delete cannot unlock a replacement delete after a patient round trip`, async t => {
    const first = deferred(), second = deferred(); let calls = 0
    const v = setup(t, component, { [deleteKey]: () => ++calls === 1 ? first.promise : second.promise })
    prime(v); const a = remove(v); v.patientId.value = 2; v.patientId.value = 1; await settle(); prime(v)
    const b = remove(v); assert.equal(calls, 2)
    first.resolve({ code: 200 }); await a; await remove(v); assert.equal(calls, 2)
    second.resolve({ code: 500 }); await b
  })
}
test('monitor: a row lacking patient identity cannot dispatch a deletion', async t => {
  const v = setup(t)
  await v.handleDelete({ ...record(), patientId: undefined })
  assert.deepEqual(v.deletions, [])
})
test('pattern: analysis parameters retain the originating patient and selected period', async t => {
  const operation = deferred(), parameters = []
  const v = setup(t, 'pattern', { analyzeBpPattern: (...args) => { parameters.push(args); return operation.promise } })
  const pending = v.handleAnalyze(); v.patientId.value = 2; v.timeType.value = 'year'; v.timeValue.value = '2025'
  assert.deepEqual(parameters, [[1, 'month', '2026-10']])
  operation.resolve({ code: 200 }); await pending
})


// Keep the real form and form-item lifecycle: v-if fields cache their initial model
// values when mounted. A resetFields no-op cannot cover patient isolation here.
// The installed CJS form bundle handles async-validator's CJS default correctly
// in Node; Vite performs this interop for the browser's ES build.
const { ElForm, ElFormItem, ID_INJECTION_KEY: MOUNT_ID_KEY, ZINDEX_INJECTION_KEY: MOUNT_ZINDEX_KEY } = createRequire(import.meta.url)('element-plus')
const mountedElement = type => ({ type, children: [], props: {}, parent: null })
const mountedRenderer = createRenderer({
  patchProp(element, key, _previous, next) { element.props[key] = next },
  createElement: mountedElement,
  createText: text => ({ ...mountedElement('#text'), text }),
  createComment: text => ({ ...mountedElement('#comment'), text }),
  setText(element, text) { element.text = text },
  setElementText(element, text) { element.text = text; element.children = [] },
  parentNode: element => element.parent,
  nextSibling(element) { return element.parent?.children[element.parent.children.indexOf(element) + 1] ?? null },
  insert(element, parent, anchor) {
    if (element.parent) { const children = element.parent.children; children.splice(children.indexOf(element), 1) }
    element.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index === -1) parent.children.push(element)
    else parent.children.splice(index, 0, element)
  },
  remove(element) {
    if (element.parent) { const children = element.parent.children; children.splice(children.indexOf(element), 1); element.parent = null }
  },
  querySelector: () => null, setScopeId() {},
  insertStaticContent() { throw new Error('Unexpected static content in form test') }
})
const FormLeafStub = { setup(_props, { slots }) { return () => h('form-leaf', slots.default?.()) } }
async function mountMonitor(t) {
  const patientId = ref(1), writes = [], messages = []
  let view
  const source = fs.readFileSync(new URL('../src/views/BpSelfMonitorManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const template = source.slice(source.indexOf('<div class="content-panel entry-panel">'), source.indexOf('</el-form>') + '</el-form>'.length) + '</div>'
  const bindings = {
    ref, reactive, computed, watch, onMounted, onUnmounted,
    localDateKey: () => '2026-10-02', useCurrentPatient: () => ({ currentPatientId: patientId }),
    ElMessage: Object.fromEntries(['success', 'warning', 'error'].map(kind => [kind, text => messages.push({ kind, text })])),
    listBpSelfMonitorRecords: async () => ({ code: 200, data: [] }),
    saveBpSelfMonitorRecord: async data => { writes.push(structuredClone(data)); return { code: 200 } },
    updateBpSelfMonitorRecord: async data => { writes.push(structuredClone(data)); return { code: 200 } },
    deleteBpSelfMonitorRecord: async () => ({ code: 200 })
  }
  const app = mountedRenderer.createApp({
    setup() {
      view = new Function(...Object.keys(bindings), script + ';return {form,formRef,formRules,saving,editingId,showBpFields,showBgFields,handleEdit,handleSave,resetForm}')(...Object.values(bindings))
      return view
    },
    render: compile(template)
  })
  app.component('el-form', ElForm); app.component('el-form-item', ElFormItem)
  for (const name of ['el-icon', 'Plus', 'Check', 'el-radio-group', 'el-radio-button', 'el-date-picker', 'el-time-picker', 'el-input-number', 'el-select', 'el-option', 'el-input', 'el-button']) app.component(name, FormLeafStub)
  app.provide(MOUNT_ID_KEY, { prefix: 123, current: 0 }); app.provide(MOUNT_ZINDEX_KEY, { current: 0 })
  app.mount(mountedElement('root')); t.after(() => app.unmount()); await nextTick()
  return { ...view, patientId, writes, messages }
}
function assertEmptyMeasurements(v) {
  assert.equal(v.form.systolicBp, null)
  assert.equal(v.form.diastolicBp, null)
  assert.equal(v.form.bloodGlucose, null)
}
async function mountEditedMeasurements(v, measureType) {
  // BP fields must be removed first so their edit values are cached on remount.
  if (measureType === 'BP') { v.form.measureType = 'BG'; await nextTick() }
  v.handleEdit({ ...record(), measureType, systolicBp: 125, diastolicBp: 81, bloodGlucose: 6, bgUnit: 'mmol/L' })
  await nextTick()
  const field = measureType === 'BP' ? 'systolicBp' : 'bloodGlucose'
  assert.equal(v.formRef.value.getField(field).fieldValue, measureType === 'BP' ? 125 : 6)
}
for (const measureType of ['BP', 'BG', 'BP_BG']) {
  for (const interruption of ['patient change', 'manual reset']) {
    test(`mounted Element Plus: ${measureType} ${interruption} cannot restore cached measurements`, async t => {
      const v = await mountMonitor(t)
      await mountEditedMeasurements(v, measureType)
      if (interruption === 'patient change') v.patientId.value = 2
      else v.resetForm()
      assertEmptyMeasurements(v)
      assert.equal(v.editingId.value, null)
      await nextTick(); assertEmptyMeasurements(v)
      v.form.measureType = measureType; await nextTick()
      await v.handleSave()
      assert.deepEqual(v.writes, [], 'missing new measurements must fail the actual form validators')
      assert.equal(v.saving.value, false)
    })
  }
  test(`mounted Element Plus: successful ${measureType} save cannot seed a new record`, async t => {
    const v = await mountMonitor(t)
    await mountEditedMeasurements(v, measureType)
    await v.handleSave(); await nextTick()
    assert.equal(v.writes.length, 1); assert.equal(v.writes[0].patientId, 1); assert.equal(v.writes[0].id, 10)
    assertEmptyMeasurements(v)
    v.form.measureType = measureType; await nextTick(); await v.handleSave()
    assert.equal(v.writes.length, 1, 'the new draft must require fresh measurements')
  })
}
test('mounted Element Plus: repeated resets and BP/BG toggles never resurrect previous readings', async t => {
  const v = await mountMonitor(t)
  await mountEditedMeasurements(v, 'BG')
  for (const measureType of ['BG', 'BP', 'BP_BG', 'BG', 'BP']) {
    v.form.measureType = measureType
    Object.assign(v.form, { systolicBp: 125, diastolicBp: 81, bloodGlucose: 6 })
    await nextTick()
    v.resetForm(); v.resetForm()
    assertEmptyMeasurements(v); await nextTick(); assertEmptyMeasurements(v)
    v.form.measureType = measureType; await nextTick(); await v.handleSave()
    assert.deepEqual(v.writes, [])
  }
})
test('mounted Element Plus: reset clears validation errors without restoring field values', async t => {
  const v = await mountMonitor(t)
  await v.handleSave()
  assert.equal(v.formRef.value.getField('systolicBp').validateState, 'error')
  v.resetForm()
  assertEmptyMeasurements(v)
  assert.equal(v.formRef.value.getField('systolicBp').validateState, '')
  assert.equal(v.formRef.value.getField('diastolicBp').validateState, '')
  await v.handleSave(); assert.deepEqual(v.writes, [])
})
