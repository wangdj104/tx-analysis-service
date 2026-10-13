import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { compile, createSSRApp, h, nextTick, ref, watch } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'

const read = file => fs.readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
const nodes = node => [node, ...(node.children || []).flatMap(nodes)]
const attr = (node, name) => node.props?.find(prop => prop.name === name)?.value?.content
const directive = (node, name) => node.props?.find(prop => prop.type === 7 && prop.arg?.content === name)?.exp?.content

// These template/style contracts supplement real-browser geometry; they do not claim pixel QA.
for (const file of ['DataExportManager.vue', 'HealthReportManager.vue']) {
  test(`${file} reserves content-sized label space and uses full-width narrow-screen fields`, () => {
    const source = read(`views/${file}`), { descriptor } = parseSfc(source)
    const tree = nodes(parseTemplate(descriptor.template.content))
    const form = tree.find(node => node.tag === 'el-form')
    assert.equal(attr(form, 'label-width'), 'auto', 'Time dimension must not be forced into 110px')
    assert.equal(attr(form, 'class'), 'report-export-form')
    for (const col of tree.filter(node => node.tag === 'el-col')) {
      assert.equal(directive(col, 'md'), '12', 'two sufficiently wide fields at desktop sizes')
      assert.equal(directive(col, 'sm'), undefined, 'tablet fields retain full width')
    }
    const css = descriptor.styles.map(style => style.content).join('\n')
    assert.match(css, /\.report-export-form\s+:deep\(\.el-form-item__label\)\s*\{[^}]*white-space:\s*nowrap/s)
    assert.match(css, /@media\s*\(max-width:\s*768px\)\s*\{[\s\S]*?\.report-export-form\s+:deep\(\.el-form-item\)\s*\{[^}]*display:\s*block/s)
    assert.match(css, /@media\s*\(max-width:\s*768px\)[\s\S]*?\.el-form-item__content\)\s*\{[^}]*margin-left:\s*0\s*!important/s)
    assert.doesNotMatch(css, /font-size:\s*(?:[0-9]|1[012])px/)
  })

  test(`${file} uses a secondary report-mode control and preserves primary export actions`, () => {
    const source = read(`views/${file}`), { descriptor } = parseSfc(source)
    const tree = nodes(parseTemplate(descriptor.template.content))
    const mode = tree.find(node => attr(node, 'class') === 'execution-report-choice')
    assert.equal(mode?.tag, 'el-button', 'reuse the app secondary button styling')
    assert.notEqual(attr(mode, 'type'), 'primary')
    const actionGroup = tree.find(node => attr(node, 'class') === 'report-export-actions')
    assert.ok(actionGroup)
    assert.ok(nodes(actionGroup).some(node => node.tag === 'el-button' && attr(node, 'type') === 'primary'))
    const css = descriptor.styles.map(style => style.content).join('\n')
    assert.match(css, /\.execution-report-choice\s*\{[^}]*min-height:\s*44px/s)
    assert.match(css, /\.report-export-actions\s+:deep\(\.el-button\)\s*\{[^}]*min-height:\s*44px/s)
  })
}

async function renderSwitcher(patients, modelValue) {
  const { descriptor } = parseSfc(read('components/PatientSwitcher.vue'))
  const component = { props: ['patients', 'modelValue', 'allowAll', 'inputId'], render: compile(descriptor.template.content) }
  const app = createSSRApp({ render: () => h(component, { patients, modelValue, allowAll: true, inputId: 'test-patient' }) })
  for (const name of ['ElIcon', 'User', 'ElSelect', 'ElOption']) app.component(name, { render() { return h('span', this.$slots.default?.()) } })
  return renderToString(app)
}

test('selected patient ID remains a separate visible node even with a very long name', async () => {
  const html = await renderSwitcher([{ id: 9102, patientName: 'Synthetic very long repeated patient name '.repeat(8) }], 9102)
  assert.match(html, /class="patient-switcher__record"[^>]*>\s*#9102\s*</)
  assert.match(html, /aria-label="(?:Record|档案) #9102"/)
  const css = parseSfc(read('components/PatientSwitcher.vue')).descriptor.styles.map(style => style.content).join('\n')
  assert.match(css, /\.patient-switcher__record\s*\{[^}]*flex-shrink:\s*0[^}]*white-space:\s*nowrap/s)
})

test('all-patients and unavailable-patient states do not show a stale patient ID', async () => {
  assert.doesNotMatch(await renderSwitcher([{ id: 9101, name: 'Synthetic' }], 0), /class="patient-switcher__record"/)
  assert.doesNotMatch(await renderSwitcher([], 9101), /class="patient-switcher__record"/)
})

function setupSwitch(initialPatientId = 9101) {
  const source = read('App.vue'), start = source.indexOf('// switchPatient'), end = source.indexOf('// Sign Out', start)
  const code = source.slice(start, end)
  const currentPatientId = ref(initialPatientId), calls = [], events = new EventTarget(), toolbar = { bottom: 132.4 }
  const appPatientList = ref([{ id: 9101, patientName: 'Synthetic Same' }, { id: 9102, patientName: 'Synthetic Same' }])
  const patientList = ref([{ id: 9103, patientName: 'Synthetic Family Patient' }])
  const message = type => options => {
    const call = { type, options, closed: false }; calls.push(call)
    return { close() { call.closed = true } }
  }
  const deps = { ref, nextTick, searchVisible: ref(false), currentPatientId, appPatientList, patientList, window: events, Event,
    document: { querySelector: () => ({ getBoundingClientRect: () => toolbar }) },
    ElMessage: { success: message('success'), info: message('info') },
    route: { path: '/monitoring', fullPath: '/monitoring' }, router: { replace() {} },
    parseReportRoute: () => false, isNarrowCareRoute: () => false, routerViewKey: ref('test'), getAuthSessionKey: () => 'test' }
  const v = new Function(...Object.keys(deps), `${code}; return { switchPatient, closePatientSwitchToast: typeof closePatientSwitchToast === 'function' ? closePatientSwitchToast : () => {} }`)(...Object.values(deps))
  return { ...v, ...deps, calls, toolbar }
}

test('patient switch notice identifies the selected record and starts below the actual toolbar', async () => {
  const v = setupSwitch(); v.switchPatient(9102); await nextTick()
  assert.match(v.calls[0].options.message || '', /Synthetic Same.*(?:Record|档案) #9102/)
  assert.equal(v.calls[0].options.customClass, 'patient-switch-toast')
  assert.ok(v.calls[0].options.offset >= 144.4)
})

test('successive switches close only the previous patient notice and use the current patient list', async () => {
  const v = setupSwitch(); v.switchPatient(9102); await nextTick(); v.switchPatient(0); await nextTick()
  assert.equal(v.calls[0].closed, true, 'the old selected-patient toast must disappear')
  assert.equal(v.calls[1].closed, false)
  assert.equal(v.calls[1].type, 'info')
  v.route.path = '/care'; v.switchPatient(9103); await nextTick()
  assert.equal(v.calls[1].closed, true)
  assert.match(v.calls[2].options.message, /Synthetic Family Patient.*(?:Record|档案) #9103/)
  v.closePatientSwitchToast(); assert.equal(v.calls[2].closed, true)
})

test('same-patient selection and a blocked context change preserve current identity and notice', async () => {
  const v = setupSwitch(); v.switchPatient(9102); await nextTick(); v.switchPatient(9102)
  v.window.addEventListener('care-plan-before-context-change', event => event.preventDefault())
  v.switchPatient(9101)
  assert.equal(v.currentPatientId.value, 9102)
  assert.equal(v.calls.length, 1)
  assert.equal(v.calls[0].closed, false)
})

test('obsolete patient notices are hidden during dismissal and cleaned up with the session', () => {
  const source = read('App.vue')
  assert.match(source, /function resetNavState\(\)\s*\{\s*closePatientSwitchToast\(\)/)
  assert.match(source, /onUnmounted\(\(\) =>\s*\{\s*closePatientSwitchToast\(\)/)
  assert.match(source, /\.patient-switch-toast\.el-message-fade-leave-active\s*\{[^}]*display:\s*none/s)
})

test('expanded long-name options keep a separate nonshrinking record ID and their full labels', async () => {
  const patientName = 'Synthetic Same Name Alexandra Catherine Montgomery-Sutherland'
  const html = await renderSwitcher([{ id: 9101, patientName }, { id: 9102, patientName }], 9101)
  assert.match(html, /class="patient-option__record"[^>]*>[^<]*#9101\s*</)
  assert.match(html, /class="patient-option__record"[^>]*>[^<]*#9102\s*</)
  assert.ok(html.includes(patientName))
  const source = read('components/PatientSwitcher.vue'), { descriptor } = parseSfc(source)
  const option = nodes(parseTemplate(descriptor.template.content)).find(node => node.tag === 'el-option')
  const label = new Function('patient', `return ${directive(option, 'label')}`)
  assert.match(label({ id: 9102, patientName }), /^Synthetic Same Name Alexandra Catherine Montgomery-Sutherland · (?:Record|档案) #9102$/)
  const css = descriptor.styles.map(style => style.content).join('\n')
  assert.match(css, /\.patient-option__record\s*\{[^}]*flex-shrink:\s*0[^}]*white-space:\s*nowrap/s)
})


test('all-patients to selected measures the toolbar after the new ID has rendered', async () => {
  const v = setupSwitch(null), selectionBoundaries = []
  const stop = watch(v.currentPatientId, () => { v.toolbar.bottom = 220.4 }, { flush: 'post' })
  v.window.addEventListener('care-report-selection-changed', () => selectionBoundaries.push(v.currentPatientId.value))
  v.switchPatient(9102)
  assert.deepEqual(selectionBoundaries, [9102], 'report invalidation stays synchronous')
  await nextTick(); stop()
  assert.equal(v.calls.length, 1)
  assert.ok(v.calls[0].options.offset >= 232.4, 'notice uses the newly wrapped toolbar bounds')
})

test('same-tick patient switches create only the newest notice after rendering', async () => {
  const v = setupSwitch()
  v.switchPatient(9102); v.switchPatient(0); v.switchPatient(9101)
  await nextTick()
  assert.equal(v.calls.length, 1, 'no stale intermediate notices are created')
  assert.match(v.calls[0].options.message, /#9101/)
})

test('session cleanup invalidates a patient notice waiting for the render flush', async () => {
  const v = setupSwitch()
  v.switchPatient(9102)
  v.closePatientSwitchToast()
  await nextTick()
  assert.equal(v.calls.length, 0, 'session cleanup must cancel pending toast creation')
})
