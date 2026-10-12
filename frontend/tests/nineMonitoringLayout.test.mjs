// Offline component/behavior regressions. Actual viewport geometry is verified by browser CI.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as Vue from 'vue'
import { renderToString } from 'vue/server-renderer'
import { parse, compileScript } from '@vue/compiler-sfc'
import { baseParse } from '@vue/compiler-dom'

const read = file => readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
const row = { id: 11, patientId: 1, recordDate: '2026-10-10', recordTime: '09:15:30', measureType: 'BP_BG', systolicBp: 144, diastolicBp: 83, bloodGlucose: 108, bgUnit: 'mg/dL', remark: 'Fictional record' }
const flush = async () => { for (let i = 0; i < 8; i++) await Vue.nextTick() }
function setup(t, file, extra = {}) {
  const source = read(file), { descriptor } = parse(source)
  const scope = Vue.effectScope(), unmounts = [], patient = Vue.ref(1), writes = []
  const bindings = {
    ...Object.fromEntries(Object.entries(Vue).filter(([key]) => /^[A-Za-z_$][\w$]*$/.test(key) && key !== 'default')), onMounted() {}, onUnmounted: fn => unmounts.push(fn),
    defineProps: () => Vue.reactive({ records: [row] }), use() {}, CanvasRenderer: {}, LineChart: {}, GridComponent: {}, LegendComponent: {}, MarkLineComponent: {}, TooltipComponent: {},
    useCurrentPatient: () => ({ currentPatientId: patient, currentPatientName: Vue.ref('Fictional patient') }),
    localDateKey: () => '2026-10-11', localStorage: { getItem: () => 'synthetic' },
    window: { removeEventListener() {}, setInterval: () => 1, clearInterval() {}, scrollY: 200, scrollTo() {}, matchMedia: () => ({ matches: true }) }, document: { removeEventListener() {}, hidden: false },
    setInterval: () => 1, clearInterval() {},
    listBpSelfMonitorRecords: async () => ({ code: 200, data: [row] }), updateBpSelfMonitorRecord: async data => { writes.push(data); return { code: 200 } },
    deleteBpSelfMonitorRecord: async id => { writes.push(id); return { code: 200 } },
    getMonitoringSnapshot: async () => ({ code: 200, data: { patient: { name: 'Fictional patient' }, vitalTrend: [{ systolic: 144, diastolic: 83, glucose: 108, glucoseUnit: 'mg/dL' }], activeAlerts: [] } }),
    readPermissionCache: () => ({ menuPaths: [], roleCodes: [] }), canAccessWorkspace: () => true, resolveWorkspaceEntry: p => p,
    ElMessage: { success() {}, error() {} }, ElMessageBox: {}, WarningFilled: {}, SuccessFilled: {},
    careMoments: '', careMomentsSmall: '', healthJournalSmall: '', medicationCareSmall: '', dialysisCareSmall: '', recordsCareSmall: '', ...extra
  }
  const names = Object.keys(compileScript(descriptor, { id: 'nine-layout' }).bindings)
  const code = descriptor.scriptSetup.content.replace(/^import[\s\S]*?from\s*['"][^'"]+['"];?\s*$/gm, '')
  const view = scope.run(() => new Function(...Object.keys(bindings), code + `\nreturn {${names.map(n => `${n}:typeof ${n}==='undefined'?undefined:${n}`).join(',')}}`)(...Object.values(bindings)))
  t.after(() => { unmounts.forEach(fn => fn()); scope.stop() })
  return { ...view, source, descriptor, patient, writes }
}
function findClass(node, name) {
  if (node.type === 1 && node.props.some(p => p.name === 'class' && p.value?.content.split(/\s+/).includes(name))) return node
  for (const child of node.children || []) { const found = findClass(child, name); if (found) return found }
}
async function fragment(view, className) {
  const node = findClass(baseParse(view.descriptor.template.content), className)
  assert.ok(node, `${className} must be available`)
  const template = node.loc.source
  const app = Vue.createSSRApp({ setup: () => view, render: Vue.compile(template) })
  app.config.warnHandler = () => {}
  for (const tag of new Set(template.match(/el-[a-z-]+/g))) app.component(tag, { template: tag === 'el-popconfirm' ? '<div><slot name="reference" /></div>' : '<div><slot /></div>' })
  return renderToString(app)
}

test('mobile history renders full date/time, type, raw readings and units in compact cards', async t => {
  const v = setup(t, 'views/BpSelfMonitorManager.vue'); await v.loadRecords()
  const html = await fragment(v, 'mobile-record-list')
  assert.match(html, /data-record-id="11"/); assert.match(html, /2026-10-10 09:15:30/)
  assert.match(html, /record-card-type/); assert.match(html, /144\/83\s*mmHg/); assert.match(html, /108\s*mg\/dL/)
  assert.match(v.source, /class="table-wrap records-desktop-table"/)
  assert.match(v.source, /handleEdit\(row, \$event, 'mobile'\)/)
  assert.match(v.source, /@confirm="handleDelete\(row\)"/)
})
test('mobile history never treats a recorded zero or missing counterpart as a missing reading', async t => {
  const v = setup(t, 'views/BpSelfMonitorManager.vue'); v.lastSuccessAt.value = '2026-10-11'; v.records.value = [{ ...row, systolicBp: 0, diastolicBp: null, bloodGlucose: 0 }]
  const html = await fragment(v, 'mobile-record-list')
  assert.match(html, /0\/—\s*mmHg/); assert.match(html, /0\s*mg\/dL/)
})
test('mobile edit saves and cancels to the matching mobile opener, even when desktop has the same record ID', async t => {
  const v = setup(t, 'views/BpSelfMonitorManager.vue'), focused = []
  const desktop = { isConnected: true, focus: () => focused.push('desktop') }
  const mobile = { isConnected: true, focus: () => focused.push('mobile') }
  const replacement = { isConnected: true, focus: () => focused.push('replacement') }
  v.formRef.value = { clearValidate() {}, validate: async () => true }
  v.setEditOpener(row.id, mobile, 'mobile'); v.setEditOpener(row.id, desktop)
  v.handleEdit(row, { currentTarget: mobile }, 'mobile'); await flush()
  assert.equal(v.editingId.value, row.id); assert.equal(v.form.bloodGlucose, 108); assert.equal(v.form.bgUnit, 'mg/dL')
  await v.cancelEdit(); assert.deepEqual(focused, ['mobile'])
  focused.length = 0; v.handleEdit(row, { currentTarget: mobile }, 'mobile'); v.setEditOpener(row.id, replacement, 'mobile')
  await v.handleSave(); await flush(); assert.deepEqual(focused, ['replacement']); assert.equal(v.writes[0].id, row.id); assert.equal(v.writes[0].bloodGlucose, 108)
})
test('reference labels are external to plots while original reference coordinates remain unchanged', async t => {
  const v = setup(t, 'components/VitalsTrendPanel.vue')
  assert.equal(v.bpOption.value.series[0].markLine.label.show, false)
  assert.deepEqual(v.bpOption.value.series[0].markLine.data.map(x => x.yAxis), [140])
  assert.equal(v.bgOption.value.series[0].markLine.label.show, false)
  assert.deepEqual(v.bgOption.value.series[0].markLine.data.map(x => x.yAxis), [6.1, 7.8])
  const html = await fragment(v, 'chart-grid')
  for (const value of ['140', '6.1', '7.8']) assert.match(html, new RegExp(`data-reference-value="${value.replace('.', '\\.')}"`))
  assert.match(html, /140 mmHg/); assert.match(html, /6\.1 mmol\/L/); assert.match(html, /7\.8 mmol\/L/)
  const monitoring = setup(t, 'views/MonitoringCenter.vue'); await flush()
  assert.equal(monitoring.chartOption.value.series[0].markLine.label.show, false)
  assert.deepEqual(monitoring.chartOption.value.series[0].markLine.data.map(x => x.yAxis), [140])
  assert.match(await fragment(monitoring, 'monitoring-reference-legend'), /140 mmHg/)
})
test('desktop trends, tasks and events share an independent left column beside the alert panel', () => {
  const source = read('views/MonitoringCenter.vue'), ast = baseParse(parse(source).descriptor.template.content)
  const grid = findClass(ast, 'monitoring-main-grid'), column = findClass(grid, 'monitoring-primary-column')
  assert.ok(column, 'An independent column prevents tall alerts stretching the trend and delaying tasks')
  for (const name of ['trend-panel', 'task-panel', 'event-panel']) assert.ok(findClass(column, name), name)
  assert.equal(findClass(column, 'alert-panel'), undefined)
  assert.ok(findClass(grid, 'alert-panel'))
  assert.match(source, /\.monitoring-main-grid\s*\{[^}]*align-items:\s*start/s)
})
test('mobile summary keeps a wrapping 2x2 layout and section anchors focus their visible target', t => {
  const focused = [], scrolled = [], target = { focus: options => focused.push(options), scrollIntoView: options => scrolled.push(options) }
  const v = setup(t, 'views/MonitoringCenter.vue', { document: { removeEventListener() {}, hidden: false, getElementById: id => id === 'missing' ? null : target } })
  assert.equal(typeof v.focusSection, 'function')
  for (const id of ['monitoring-alerts', 'monitoring-trends', 'monitoring-tasks']) {
    assert.match(v.source, new RegExp(`href="#${id}"`))
    assert.match(v.source, new RegExp(`id="${id}"[^>]*tabindex="-1"`))
    v.focusSection(id)
  }
  assert.equal(focused.length, 3); assert.deepEqual(focused[0], { preventScroll: true }); assert.equal(scrolled.length, 3)
  assert.deepEqual(scrolled[0], { behavior: 'auto', block: 'start' }); assert.doesNotThrow(() => v.focusSection('missing'))
  const mobile = v.source.slice(v.source.lastIndexOf('@media (max-width: 480px)'))
  assert.match(mobile, /\.monitor-metrics\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/s)
  assert.match(v.source, /\.monitor-section-heading\s*\{[^}]*scroll-margin-top:\s*88px/s)
  assert.match(v.source, /\.monitor-section-heading:focus\s*\{/)
})

for (const from of ['mobile', 'desktop']) for (const action of ['cancel', 'save']) test(`${from} edit ${action} returns to the visible same-record counterpart after responsive reflow`, async t => {
  const focused = [], scrolled = [], to = from === 'mobile' ? 'desktop' : 'mobile'
  let visible = from
  const opener = surface => ({ isConnected: true, getClientRects: () => visible === surface ? [{}] : [], focus: () => focused.push(surface), scrollIntoView: options => scrolled.push([surface, options]) })
  const old = opener(from), counterpart = opener(to), v = setup(t, 'views/BpSelfMonitorManager.vue')
  v.formRef.value = { clearValidate() {}, validate: async () => true }
  v.setEditOpener(row.id, old, from); v.setEditOpener(row.id, counterpart, to)
  v.handleEdit(row, { currentTarget: old }, from); visible = to
  if (action === 'cancel') await v.cancelEdit(); else await v.handleSave()
  await flush()
  assert.deepEqual(focused, [to]); assert.deepEqual(scrolled, [[to, { behavior: 'auto', block: 'nearest' }]])
  if (action === 'save') { assert.equal(v.writes[0].id, row.id); assert.equal(v.writes[0].bloodGlucose, 108) }
})
test('edit return skips CSS visibility-hidden controls and never focuses a different record', async t => {
  const focused = [], window = { removeEventListener() {}, scrollY: 200, scrollTo() {}, getComputedStyle: element => ({ visibility: element.visibility }) }
  const v = setup(t, 'views/BpSelfMonitorManager.vue', { window })
  const hidden = { isConnected: true, visibility: 'hidden', getClientRects: () => [{}], focus: () => focused.push('hidden') }
  const wrong = { isConnected: true, visibility: 'visible', getClientRects: () => [{}], focus: () => focused.push('wrong') }
  v.setEditOpener(row.id, hidden, 'mobile'); v.setEditOpener(999, wrong)
  v.handleEdit(row, { currentTarget: hidden }, 'mobile'); await v.cancelEdit()
  assert.deepEqual(focused, [])
})
for (const bottom of [148, 216]) test(`section navigation clears an actual ${bottom}px wrapped header`, t => {
  const margins = [], focused = []
  let top = 88
  const target = { style: { setProperty: (name, value) => { assert.equal(name, 'scroll-margin-top'); margins.push(parseFloat(value)) } }, getBoundingClientRect: () => ({ top }), scrollIntoView: () => { top = margins.at(-1) ?? 88 }, focus: options => focused.push(options) }
  const header = { getBoundingClientRect: () => ({ top: 0, bottom, height: bottom, width: 320 }) }
  const v = setup(t, 'views/MonitoringCenter.vue', { document: { removeEventListener() {}, hidden: false, getElementById: () => target, querySelector: selector => { assert.equal(selector, '.workspace-topbar'); return header } } })
  v.focusSection('monitoring-alerts')
  assert.ok(top >= bottom + 12, `Heading at ${top}px must sit below actual header bottom ${bottom}px`)
  assert.deepEqual(focused, [{ preventScroll: true }])
})
test('section navigation remeasures a header that reaches its sticky position during the scroll', t => {
  let scrolled = false, top = 0, margin = 88
  const target = { style: { setProperty: (_name, value) => { margin = parseFloat(value) } }, getBoundingClientRect: () => ({ top }), scrollIntoView: () => { scrolled = true; top = margin }, focus() {} }
  const header = { getBoundingClientRect: () => ({ top: scrolled ? 0 : -112, bottom: scrolled ? 160 : 48, height: 160, width: 320 }) }
  const v = setup(t, 'views/MonitoringCenter.vue', { document: { removeEventListener() {}, hidden: false, getElementById: () => target, querySelector: () => header } })
  v.focusSection('monitoring-trends')
  assert.ok(top >= 172)
})
