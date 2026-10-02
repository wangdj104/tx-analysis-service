import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import * as Vue from 'vue'
import { localDateKey } from '../src/utils/familyHealth.js'
const { ElForm, ElFormItem, ElTableColumn, ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } = createRequire(import.meta.url)('element-plus')
const { renderToString } = createRequire(import.meta.url)('vue/server-renderer')
const { createRenderer, createSSRApp, h, ref, reactive, computed, watch, onMounted, onUnmounted, nextTick, compile, getCurrentInstance } = Vue
const base = new URL('../src/views/', import.meta.url)
const element = type => ({ type, children: [], props: {}, parent: null })
const renderer = createRenderer({
  patchProp(el, key, previous, next) { el.props[key] = next }, createElement: element,
  createText: text => ({ ...element('#text'), text }), createComment: text => ({ ...element('#comment'), text }),
  setText(el, text) { el.text = text }, setElementText(el, text) { el.text = text; el.children = [] },
  parentNode: el => el.parent, nextSibling(el) { return el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null },
  insert(el, parent, anchor) {
    if (el.parent) { const children = el.parent.children; children.splice(children.indexOf(el), 1) }
    el.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index === -1) parent.children.push(el); else parent.children.splice(index, 0, el)
  },
  remove(el) { if (el.parent) { const children = el.parent.children; children.splice(children.indexOf(el), 1); el.parent = null } },
  querySelector: () => null, setScopeId() {}, insertStaticContent() { throw Error('Unexpected static content') }
})
const Leaf = { setup(_props, { slots }) { return () => h('leaf', slots.default?.()) } }
function setTimezone(t, timezone) {
  const previous = process.env.TZ
  process.env.TZ = timezone
  t.after(() => { if (previous === undefined) delete process.env.TZ; else process.env.TZ = previous })
}
function clock(instant) {
  let now = new Date(instant).valueOf()
  return { Date: class extends Date { constructor(...args) { super(...(args.length ? args : [now])) } static now() { return now } }, set(instant) { now = new Date(instant).valueOf() } }
}
async function mountDiary(t, time) {
  const patientId = ref(1), writes = [], messages = []; let state
  const source = fs.readFileSync(new URL('NutritionDiaryManager.vue', base), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const template = source.slice(source.indexOf('<el-form '), source.indexOf('</el-form>') + '</el-form>'.length)
  const deps = {
    ref, reactive, computed, watch, onMounted, onUnmounted, Date: time.Date,
    localDateKey: () => localDateKey(new time.Date()), useCurrentPatient: () => ({ currentPatientId: patientId }),
    ElMessage: Object.fromEntries(['success', 'warning', 'error'].map(kind => [kind, text => messages.push({ kind, text })])),
    listDiaries: async () => ({ code: 200, data: [] }),
    saveDiary: async data => { writes.push(structuredClone(data)); return { code: 200 } },
    updateDiary: async data => { writes.push(structuredClone(data)); return { code: 200 } }, deleteDiary: async () => ({ code: 200 })
  }
  const app = renderer.createApp({
    setup() {
      state = new Function(...Object.keys(deps), script + ';return {form,formRef,formRules,saving,editingId,selectedSymptoms,SYMPTOM_OPTIONS,toggleSymptom,handleEdit,handleSave,resetForm}')(...Object.values(deps))
      return state
    }, render: compile(template)
  })
  app.component('el-form', ElForm); app.component('el-form-item', ElFormItem)
  for (const name of ['el-icon', 'Check', 'el-radio-group', 'el-radio-button', 'el-date-picker', 'el-input-number', 'el-checkbox', 'el-check-tag', 'el-input', 'el-button']) app.component(name, Leaf)
  app.provide(ID_INJECTION_KEY, { prefix: 123, current: 0 }); app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
  app.mount(element('root')); t.after(() => app.unmount()); await nextTick()
  assert.equal(state.formRef.value.getField('recordDate').fieldValue, state.form.recordDate)
  return { ...state, patientId, writes, messages }
}
async function renderColumn(columnTemplate, row) {
  const columns = ref([])
  const store = {
    states: { columns, treeData: ref({}) }, scheduleLayout() {},
    commit(action, column) { if (action === 'insertColumn') columns.value.push(column) }
  }
  // Mount Element Plus's real column, including its prop-to-property binding and
  // default renderer. Only the table's registration/layout owner is simulated.
  const app = renderer.createApp({
    setup() {
      const instance = getCurrentInstance()
      instance.tableId = 'synthetic-table'
      instance.store = store
    }, render: compile(`<div ref="hiddenColumns">${columnTemplate}</div>`)
  })
  app.component('el-table-column', ElTableColumn)
  app.mount(element('root')); await nextTick()
  try {
    assert.equal(columns.value.length, 1)
    const column = columns.value[0]
    const html = await renderToString(createSSRApp({ render: () => column.renderCell({ row, column, store, cellIndex: 0, $index: 0 }) }))
    return { text: html.replace(/<[^>]*>/g, '').trim(), property: column.property }
  } finally { app.unmount() }
}
async function renderCell(component, property, value) {
  const source = fs.readFileSync(new URL(`${component}.vue`, base), 'utf8')
  const column = [...source.matchAll(/<el-table-column\b[^>]*(?<!\/)>(?:(?!<el-table-column)[\s\S])*?<\/el-table-column>/g)].map(match => match[0]).find(text => text.includes(`row.${property}`))
  assert.ok(column, `${property} production column must exist`)
  return (await renderColumn(column, { [property]: value })).text
}
for (const [timezone, instant, expected] of [
  ['Asia/Shanghai', '2026-10-02T00:30:00+08:00', '2026-10-02'],
  ['America/Los_Angeles', '2026-10-01T23:30:00-07:00', '2026-10-01'],
  ['Asia/Shanghai', '2027-01-01T00:01:00+08:00', '2027-01-01'],
  ['America/Los_Angeles', '2026-12-31T23:59:00-08:00', '2026-12-31'],
  ['Asia/Shanghai', '2028-02-29T00:01:00+08:00', '2028-02-29'],
  ['America/Los_Angeles', '2026-03-08T03:01:00-07:00', '2026-03-08'],
  ['UTC', '2026-10-02T12:00:00Z', '2026-10-02']
]) test(`diary initial day: ${timezone} ${instant}`, async t => {
  setTimezone(t, timezone)
  const v = await mountDiary(t, clock(instant))
  assert.equal(v.form.recordDate, expected)
})
for (const interruption of ['manual reset', 'patient change', 'successful save']) test(`actual Element Plus: ${interruption} uses the current day after midnight`, async t => {
  setTimezone(t, 'UTC')
  const time = clock('2026-10-01T23:59:00Z'), v = await mountDiary(t, time)
  v.handleEdit({ id: 10, patientId: 1, recordDate: '2026-09-30', bodyWeight: 65.5, appetite: 'GOOD', fluidIntake: 0, symptoms: 'Synthetic symptom', remark: 'Synthetic draft' })
  time.set('2026-10-02T00:01:00Z')
  if (interruption === 'patient change') v.patientId.value = 2
  else if (interruption === 'successful save') await v.handleSave()
  else v.resetForm()
  const immediate = v.form.recordDate
  await nextTick()
  assert.equal(immediate, '2026-10-02', 'manual defaults must not be overwritten by the mounted form snapshot')
  assert.equal(v.form.recordDate, '2026-10-02')
  assert.equal(v.editingId.value, null)
  assert.equal(v.form.fluidIntake, null)
  assert.equal(v.form.remark, '')
  assert.deepEqual(v.selectedSymptoms.value, [])
  if (interruption === 'successful save') {
    assert.equal(v.writes.length, 1)
    assert.equal(v.writes[0].recordDate, '2026-09-30')
    assert.equal(v.writes[0].fluidIntake, 0)
  }
})
test(`actual Element Plus: reset clears required-date errors without restoring mounted day`, async t => {
  setTimezone(t, 'Asia/Shanghai')
  const time = clock('2026-10-01T12:00:00+08:00'), v = await mountDiary(t, time)
  v.form.recordDate = ''
  await v.handleSave()
  assert.equal(v.formRef.value.getField('recordDate').validateState, 'error')
  assert.deepEqual(v.writes, [])
  time.set('2026-10-02T12:00:00+08:00')
  v.resetForm()
  assert.equal(v.formRef.value.getField('recordDate').validateState, '')
  assert.equal(v.form.recordDate, '2026-10-02')
  await nextTick()
  assert.equal(v.formRef.value.getField('recordDate').validateState, '')
  await v.handleSave()
  assert.equal(v.writes.length, 1)
  assert.equal(v.writes[0].recordDate, '2026-10-02')
})
test(`repeated resets and patient round trips keep today's clean draft`, async t => {
  setTimezone(t, 'America/Los_Angeles')
  const time = clock('2026-10-01T12:00:00-07:00'), v = await mountDiary(t, time)
  for (const [day, patient] of [['02', 2], ['03', 1], ['04', 2]]) {
    time.set(`2026-10-${day}T23:30:00-07:00`)
    v.handleEdit({ id: 10, patientId: v.patientId.value, recordDate: '2026-09-30', bodyWeight: 65.5, appetite: 'GOOD', fluidIntake: 1000, symptoms: 'Synthetic symptom', remark: 'Synthetic draft' })
    v.patientId.value = patient
    v.resetForm(); v.resetForm()
    assert.equal(v.form.recordDate, `2026-10-${day}`)
    assert.equal(v.form.bodyWeight, null)
    assert.equal(v.form.fluidIntake, null)
    assert.equal(v.form.appetite, '')
    assert.equal(v.form.remark, '')
    assert.equal(v.editingId.value, null)
    assert.deepEqual(v.selectedSymptoms.value, [])
    await nextTick()
    assert.equal(v.form.recordDate, `2026-10-${day}`)
  }
})
test(`actual Element Plus date column reads analysisDate from the analysis row`, async () => {
  const source = fs.readFileSync(new URL('BpPatternManager.vue', base), 'utf8')
  const column = source.match(/<el-table-column\b[^>]*prop="(?:analysisDate|Analysis date)"[^>]*\/>/)[0]
  const rendered = await renderColumn(column, { analysisDate: '2026-09-30' })
  assert.equal(rendered.text, '2026-09-30')
  assert.equal(rendered.property, 'analysisDate')
})
for (const [component, property, suffix] of [['NutritionDiaryManager', 'fluidIntake', ' ml'], ['BpPatternManager', 'stdDeviation', '']]) {
  for (const [value, expected] of [[0, `0${suffix}`], [12.5, `12.5${suffix}`], [5000, `5000${suffix}`], ['0', `0${suffix}`], [null, '-'], [undefined, '-'], ['', '-'], [false, '-'], [NaN, '-']]) test(`${property} ${JSON.stringify(value)} preserves numeric display`, async () => {
    assert.equal(await renderCell(component, property, value), expected)
  })
}
