import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { localizeMenuName, localizePayload } from '../../cn/frontend/src/utils/serverText.js'
import { normalizeWorkspaceMenus as chineseMenus } from '../../cn/frontend/src/utils/workspaceNavigation.js'
import { getEnglishMenuLabel } from '../src/utils/workspaceNavigation.js'

const editionRoot = edition => new URL(edition === 'cn' ? '../../cn/frontend/' : '../', import.meta.url)
const read = (edition, file) => readFileSync(new URL(`src/${file}`, editionRoot(edition)), 'utf8')

// Exercise the actual selector label function before and after it delegates to the shared presenter.
for (const [edition, expected, missing] of [['en', 'ID', 'Patient name unavailable'], ['cn', '编号', '患者姓名暂不可用']]) {
  test(`${edition}: patient selector labels distinguish same names without hash notation`, async () => {
    const source = read(edition, 'views/MedicalRecordManager.vue')
    const code = source.match(/function patientLabel\(patient\) \{[^\n]+\}/)[0]
    const helperUrl = new URL('src/utils/patientPresentation.js', editionRoot(edition))
    const helper = await import(helperUrl.href)
    const label = new Function('formatPatientIdentity', `${code}; return patientLabel`)(helper.formatPatientIdentity)
    const a = { id: 2, patientName: 'Synthetic Patient' }, b = { id: 3, patientName: 'Synthetic Patient' }
    assert.equal(label(a), `Synthetic Patient · ${expected} 2`)
    assert.equal(label(b), `Synthetic Patient · ${expected} 3`)
    assert.equal(label({ id: null, name: '' }), `${missing} · ${expected} —`)
    const longName = 'Synthetic very long patient name '.repeat(20)
    assert.equal(label({ id: 27, name: longName }), `${longName} · ${expected} 27`)
    assert.deepEqual(a, { id: 2, patientName: 'Synthetic Patient' }, 'presentation must not mutate identity')
  })
}

test('Chinese stock Care Journey label is localized even with a legacy menu code', () => {
  const menu = { menuCode: 'doctor-workspace', menuPath: '/care-journey', menuName: 'Care Journey' }
  assert.equal(localizeMenuName(menu), '照护历程')
  const payload = localizePayload({ code: 200, data: { menus: [menu], user: { realName: 'Synthetic Care Journey' } } }, '/auth/info')
  assert.equal(payload.data.menus[0].menuName, '照护历程')
  assert.equal(payload.data.user.realName, 'Synthetic Care Journey')
  assert.equal(menu.menuName, 'Care Journey')
})

test('Chinese route navigation corrects raw backend stock labels and preserves unknown custom labels', () => {
  const items = chineseMenus([
    { path: '/care-journey', label: 'Care Journey' },
    { path: '/synthetic-custom', label: 'Synthetic Team Dashboard' }
  ], { menuPaths: ['/care-journey', '/synthetic-custom'] })
  assert.equal(items[0].label, '照护历程')
  assert.equal(items[1].label, 'Synthetic Team Dashboard')
  assert.equal(localizeMenuName({ menuCode: 'synthetic-custom', menuPath: '/synthetic-custom', menuName: 'Care Journey' }), 'Care Journey')
  assert.equal(localizeMenuName({ menuCode: 'care-journey', menuPath: '/care-journey', menuName: 'Synthetic Custom Title' }), 'Synthetic Custom Title')
  assert.equal(getEnglishMenuLabel('/care-journey', '照护历程', 'care-journey'), 'Care Journey')
  assert.equal(getEnglishMenuLabel('/synthetic-custom', 'Synthetic Team Dashboard', 'synthetic-custom'), 'Synthetic Team Dashboard')
})

// Compile and render the actual Vue components. Select/option stubs expose only
// Element Plus's public slot and event contract; labels and ID forwarding stay real.
import { createRequire } from 'node:module'
import { parse, compileScript, compileStyle } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'
import { createSSRApp, defineComponent, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
const require = createRequire(import.meta.url)
for (const [edition, idLabel] of [['en', 'ID'], ['cn', '编号']]) {
  const helper = await import(new URL('src/utils/patientPresentation.js', editionRoot(edition)))
  function loadComponent(file) {
    const source = read(edition, `components/${file}`)
    const { descriptor } = parse(source)
    const compiled = compileScript(descriptor, { id: 'patient-presentation', inlineTemplate: true })
    const code = transformSync(compiled.content, { format: 'cjs', loader: 'js' }).code
    const module = { exports: {} }
    new Function('require', 'module', 'exports', code)(id => {
      if (id.endsWith('patientPresentation.js')) return helper
      if (id.endsWith('.vue')) return { __esModule: true, default: loadComponent(id.split('/').pop()) }
      return require(id)
    }, module, module.exports)
    return module.exports.default
  }
  async function render(component, props) {
    const selectors = [], app = createSSRApp(component, props)
    app.component('ElSelect', defineComponent({ props: ['modelValue', 'popperClass'], emits: ['update:modelValue', 'change'], setup(props, { slots, emit, attrs }) {
      selectors.push({ emit, attrs })
      return () => h('section', { 'data-selected-id': props.modelValue, class: props.popperClass }, [
        h('div', { class: 'selected-label' }, slots.label?.({ label: 'All patients' })), h('ul', slots.default?.())
      ])
    } }))
    app.component('ElOption', defineComponent({ props: ['value', 'label'], setup(props, { slots }) {
      return () => h('li', { 'data-patient-id': props.value, 'data-identity': props.label }, slots.default?.() || props.label)
    } }))
    app.component('ElIcon', { setup(_, { slots }) { return () => h('span', slots.default?.()) } })
    return { html: await renderToString(app), selectors }
  }
  test(`${edition}: global selected name is primary and stable ID remains in visible context and options`, async () => {
    let selected = null
    const patients = [{ id: 2, patientName: 'Synthetic Patient' }, { id: 3, patientName: 'Synthetic Patient' }]
    const view = await render(loadComponent('PatientSwitcher.vue'), { modelValue: 2, patients, 'onUpdate:modelValue': value => { selected = value } })
    assert.match(view.html, new RegExp(`patient-switcher__record[^>]*>${idLabel} 2<`))
    const selectedLabel = view.html.match(/class="selected-label"[^>]*>(.*?)<\/div>/s)[1]
    assert.match(selectedLabel, /patient-selection__name[^>]*>Synthetic Patient</)
    assert.doesNotMatch(selectedLabel, /patient-selection__id/)
    assert.match(view.html, new RegExp(`data-identity="Synthetic Patient · ${idLabel} 3"`))
    assert.doesNotMatch(view.html, /Record #|档案 #/)
    view.selectors[0].emit('change', 3)
    assert.equal(selected, 3, 'selecting the second same-name option forwards the stable numeric ID')
  })
  test(`${edition}: clinical selector renders secondary IDs and forwards clear and locked state`, async () => {
    let selected = 2
    const view = await render(loadComponent('PatientSelect.vue'), { modelValue: 2, patients: [{ id: 2, name: 'Synthetic Long Name '.repeat(20) }], disabled: true, 'onUpdate:modelValue': value => { selected = value } })
    assert.match(view.html, new RegExp(`patient-selection__id[^>]*>${idLabel} 2<`))
    assert.equal(view.selectors[0].attrs.disabled, true)
    view.selectors[0].emit('update:modelValue', 3); assert.equal(selected, 3)
    view.selectors[0].emit('update:modelValue', ''); assert.equal(selected, '')
    const { descriptor } = parse(read(edition, 'components/PatientSelect.vue'))
    assert.deepEqual(compileStyle({ source: descriptor.styles[0].content, id: 'patient-presentation', scoped: true }).errors, [])
  })
}
