// Offline behavior tests exercise the real SFC; actual geometry remains a browser-CI requirement.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as Vue from 'vue'

const row = { id: 217, patientId: 1, recordDate: '2026-09-13', recordTime: '08:00', measureType: 'BP', systolicBp: 120, diastolicBp: 80, remark: 'Fictional BP history 17' }
function setup(t, edition, { responsive = true, documentTop = 7812.03125 } = {}) {
  const source = readFileSync(new URL(`../../${edition === 'cn' ? 'cn/' : ''}frontend/src/views/BpSelfMonitorManager.vue`, import.meta.url), 'utf8')
  const code = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*?from\s*['"][^'"]+['"];?\s*$/gm, '')
  const scope = Vue.effectScope(), unmounts = [], writes = [], scrolls = [], focuses = [], patient = Vue.ref(1)
  let mobileVisible = false
  const window = {
    innerHeight: 900, scrollY: 3831, getComputedStyle: () => ({ visibility: 'visible' }),
    scrollTo: options => { scrolls.push(options); window.scrollY = Math.round(options.top) },
    matchMedia: () => ({ matches: true }), removeEventListener() {},
  }
  const document = { activeElement: null }
  const opener = surface => ({
    isConnected: true, getClientRects: () => (surface === 'mobile') === mobileVisible ? [{}] : [],
    focus: options => { document.activeElement = surface; focuses.push({ surface, options }) },
    getBoundingClientRect: () => ({ top: documentTop - window.scrollY, bottom: documentTop + 44 - window.scrollY, height: 44 }),
    scrollIntoView: () => {
      const top = documentTop - window.scrollY, bottom = top + 44
      // A native viewport scroll can round an otherwise fractional edge alignment.
      if (bottom > window.innerHeight) window.scrollY = Math.round(documentTop + 44 - window.innerHeight)
      else if (top < 0) window.scrollY = Math.round(documentTop)
    },
  })
  const desktop = opener('desktop'), mobile = opener('mobile')
  const bindings = {
    ...Object.fromEntries(Object.entries(Vue).filter(([key]) => /^[A-Za-z_$][\w$]*$/.test(key) && key !== 'default')),
    onMounted() {}, onUnmounted: fn => unmounts.push(fn),
    useCurrentPatient: () => ({ currentPatientId: patient, currentPatientName: Vue.ref('Fictional patient') }),
    localDateKey: () => '2026-10-11', localStorage: { getItem: () => 'fictional' }, window, document,
    listBpSelfMonitorRecords: async () => ({ code: 200, data: [row] }),
    updateBpSelfMonitorRecord: async body => { writes.push(body); return { code: 200 } },
    ElMessage: { success() {}, error() {} },
  }
  const view = scope.run(() => new Function(...Object.keys(bindings), `${code}\nreturn { formRef, handleEdit, handleSave, cancelEdit, setEditOpener };`)(...Object.values(bindings)))
  t.after(() => { unmounts.forEach(fn => fn()); scope.stop() })
  view.formRef.value = { clearValidate() {}, validate: async () => true }
  view.setEditOpener(217, desktop); view.setEditOpener(217, mobile, 'mobile')
  view.handleEdit(row, { currentTarget: desktop }); mobileVisible = responsive
  return { ...view, window, document, writes, scrolls, focuses, target: responsive ? mobile : desktop }
}

for (const edition of ['en', 'cn']) for (const action of ['cancel', 'save']) {
  for (const [edge, documentTop] of [['bottom', 7812.03125], ['top', 301.96875]]) {
    test(`${edition} responsive ${action} leaves the same-record control fully inside the ${edge} edge after fractional native scroll`, async t => {
      const v = setup(t, edition, { documentTop })
      await (action === 'cancel' ? v.cancelEdit() : v.handleSave())
      const rect = v.target.getBoundingClientRect()
      assert.ok(rect.top >= 8 && rect.bottom <= v.window.innerHeight - 8, `returned control must have breathing room: ${JSON.stringify(rect)}`)
      assert.deepEqual(v.focuses, [{ surface: 'mobile', options: { preventScroll: true } }])
      if (action === 'save') { assert.equal(v.writes[0].id, 217); assert.equal(v.writes[0].patientId, 1) }
    })
  }
  test(`${edition} same-surface ${action} preserves the exact original scroll offset`, async t => {
    const v = setup(t, edition, { responsive: false })
    await (action === 'cancel' ? v.cancelEdit() : v.handleSave())
    assert.equal(v.window.scrollY, 3831)
    assert.deepEqual(v.focuses, [{ surface: 'desktop', options: { preventScroll: true } }])
  })
  test(`${edition} responsive ${action} does not move an already comfortably visible counterpart`, async t => {
    const v = setup(t, edition, { documentTop: 4100 })
    await (action === 'cancel' ? v.cancelEdit() : v.handleSave())
    assert.equal(v.window.scrollY, 3831)
    assert.deepEqual(v.focuses, [{ surface: 'mobile', options: { preventScroll: true } }])
  })
}

const browserSource = readFileSync(new URL('./uiSeventeenMonitoring.browser.mjs', import.meta.url), 'utf8')
const browserFunction = browserSource.slice(browserSource.indexOf('async function responsiveBpEditReturn('), browserSource.indexOf('\nasync function bpCase('))
for (const [failure, overrides] of [
  ['fractional bottom clipping', { top: 856.03125, bottom: 900.03125 }],
  ['fractional top clipping', { top: -0.03125, bottom: 43.96875 }],
  ['occlusion', { unobscured: false }],
  ['lost native focus', { focused: false }],
]) test(`responsive acceptance preserves ${failure} evidence and still rejects it`, async () => {
  const result = {}, returned = { viewport: 375, left: 195, right: 241, top: 400, bottom: 444, width: 46, height: 44, focused: true, unobscured: true, scrollY: 6956, ...overrides }
  const target = { scrollIntoViewIfNeeded() {}, evaluate: async () => returned }
  const page = { locator: () => ({ getByRole: () => ({ click() {} }) }), setViewportSize() {} }
  const expect = () => ({ toBeFocused() {}, toBeVisible() {} })
  const bindings = { assert, expect, bpRecordRows: () => ({ filter: () => ({ getByRole: () => target }) }), readEditClickOrigin: async () => ({ trusted: true, scrollY: 3831 }), waitForMonitoringLayout: async () => {}, capture: async () => {} }
  const run = new Function(...Object.keys(bindings), `${browserFunction}\nreturn responsiveBpEditReturn;`)(...Object.values(bindings))
  await assert.rejects(run(page, { requests: [] }, { viewport: 1365 }, result, { edit: 'Edit', cancel: 'Cancel' }), /Responsive edit return focuses the visible same-record counterpart/)
  assert.equal(result.responsiveEditReturns?.length, 1, 'The failed attempt must survive for the CI outcome artifact')
  assert.deepEqual(result.responsiveEditReturns[0].returned, returned)
  assert.equal(result.responsiveEditReturns[0].recordId, 217)
})

for (const moving of ['focused rectangle', 'scroll offset']) test(`bounded layout readiness observes changing ${moving} with unchanged document size`, async () => {
  let frames = 0, checks = 0
  const position = () => Math.min(frames, 3)
  const document = {
    fonts: { ready: Promise.resolve() }, documentElement: { scrollWidth: 375, scrollHeight: 7924 }, querySelectorAll: () => [],
    defaultView: { scrollX: 0, get scrollY() { return moving === 'scroll offset' ? position() : 0 } },
    activeElement: { getBoundingClientRect: () => ({ left: 195, right: 241, top: moving === 'focused rectangle' ? position() : 0, bottom: 44 }) },
  }
  const source = browserSource.slice(browserSource.indexOf('async function waitForMonitoringLayout('), browserSource.indexOf('\nfunction assertBpSummaryBounds('))
  const settle = new Function('document', 'requestAnimationFrame', `${source}\nreturn waitForMonitoringLayout;`)(document, callback => { frames++; callback() })
  await settle({ async waitForFunction(callback, arg, options) {
    assert.equal(options.timeout, 15000)
    while (checks++ < 5) if (await callback()) return
    throw new Error('Layout never settled')
  } })
  assert.equal(checks, 3, 'Stable document height alone cannot certify the focused control is settled')
})
