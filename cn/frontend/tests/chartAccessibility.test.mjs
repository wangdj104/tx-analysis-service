import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import * as Vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-dom'
import postcss from 'postcss'
import { use } from 'echarts/core'
import { SVGRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, DataZoomComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { adaptChartOption } from '../src/utils/appearanceChart.js'

// vue-echarts installs its stylesheet at module load. Only that DOM boundary is
// adapted; the component, its watchers and ECharts SVG/SSR model below are real.
globalThis.document = { head: { appendChild: node => node }, createElement: () => ({}) }
const { default: VChart } = await import('vue-echarts')
delete globalThis.document
use([SVGRenderer, LineChart, GridComponent, LegendComponent, DataZoomComponent, MarkLineComponent, TooltipComponent])
const helperUrl = new URL('../src/composables/useReducedMotion.js', import.meta.url)
const motionModule = fs.existsSync(helperUrl) ? await import(helperUrl) : {}
const read = path => fs.readFileSync(new URL('../src/' + path, import.meta.url), 'utf8')
const tick = async () => { await Vue.nextTick(); await new Promise(resolve => setImmediate(resolve)) }

function trend(t, records) {
  const source = read('components/VitalsTrendPanel.vue')
  const code = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*?from\s+['"][^'"]+['"];?\r?$/gm, '')
  const props = Vue.reactive({ records }), scope = Vue.effectScope()
  const deps = { ...Vue, defineProps: () => props, use() {}, CanvasRenderer: {}, LineChart: {}, GridComponent: {}, LegendComponent: {}, MarkLineComponent: {}, TooltipComponent: {} }
  delete deps.default
  delete deps['module.exports']
  const view = scope.run(() => new Function(...Object.keys(deps), code + '\nreturn {mode,hasData,bpOption,bgOption,bpStreak,abnormalCount' + (code.includes('const hasBpData') ? ',hasBpData,hasBgData,emptyDescription' : '') + '}')(...Object.values(deps)))
  t.after(() => scope.stop())
  const template = source.slice(source.indexOf('<template>') + 10, source.indexOf('</template>'))
  const render = new Function('Vue', compile(template, { mode: 'function', prefixIdentifiers: true }).code)({ ...Vue, resolveComponent: name => ({ name }) })
  const nodes = () => {
    const result = []
    const walk = node => {
      if (!node || typeof node !== 'object') return
      if (Array.isArray(node)) return node.forEach(walk)
      result.push(node)
      if (Array.isArray(node.children)) node.children.forEach(walk)
      else if (node.children && typeof node.children === 'object') for (const slot of Object.values(node.children)) if (typeof slot === 'function') walk(slot())
    }
    walk(render(Vue.proxyRefs(view), [])); return result
  }
  return { ...view, props, nodes }
}
const row = values => ({ recordDate: '2026-10-02', recordTime: '08:00', ...values })
const bp = row({ systolicBp: 120, diastolicBp: 80, bloodGlucose: null })
const bg = row({ systolicBp: null, diastolicBp: null, bloodGlucose: 108, bgUnit: 'mg/dL' })

for (const [kind, records] of [['bg', [bp]], ['bp', [bg]]]) {
  test(`selected ${kind} mode announces its own empty state without a reference-only chart`, t => {
    const view = trend(t, records); view.mode.value = kind
    assert.equal(view.hasData.value, false)
    assert.equal(view.nodes().filter(node => node.type?.name === 'v-chart').length, 0)
    const empty = view.nodes().find(node => node.type?.name === 'el-empty')
    assert.match(empty.props.description, kind === 'bp' ? /blood.pressure|血压/i : /blood.glucose|血糖/i)
  })
}
for (const records of [[bp], [bg]]) {
  test(`All mode keeps the available ${records[0].bloodGlucose ? 'glucose' : 'pressure'} trend and explains its missing companion`, t => {
    const view = trend(t, records)
    assert.equal(view.nodes().filter(node => node.type?.name === 'v-chart').length, 1)
    assert.equal(view.nodes().filter(node => node.type?.name === 'el-empty').length, 1)
  })
}

test('availability ignores nonpositive, malformed and unsupported measurements without editing clinical data', t => {
  const invalid = [null, undefined, '', ' ', 0, '0', -1, Infinity, 'invalid', true, {}, []]
  const records = invalid.map(value => row({ systolicBp: value, diastolicBp: value, bloodGlucose: value }))
  records.push(row({ bloodGlucose: 6, bgUnit: 'mg/L' }))
  const before = structuredClone(records), view = trend(t, records)
  for (const mode of ['both', 'bp', 'bg']) { view.mode.value = mode; assert.equal(view.hasData.value, false, mode) }
  assert.deepEqual(records, before)
})

test('availability reacts to replacements and retains readings, conversions, references and abnormality counts', t => {
  const records = [bp, bg, row({ systolicBp: 145, diastolicBp: 95, bloodGlucose: 7, bgUnit: 'mmol/L', measurePeriod: 'Fasting' })]
  const before = structuredClone(records), view = trend(t, records)
  assert.equal(view.nodes().filter(node => node.type?.name === 'v-chart').length, 2)
  assert.deepEqual(view.bpOption.value.series.map(series => series.data), [[120, null, 145], [80, null, 95]])
  assert.deepEqual(view.bgOption.value.series[0].data, [null, 6, 7])
  assert.equal(view.bpOption.value.series[0].markLine.data[0].yAxis, 140)
  assert.deepEqual(view.bgOption.value.series[0].markLine.data.map(mark => mark.yAxis), [6.1, 7.8])
  assert.equal(view.abnormalCount.value, 1)
  assert.deepEqual(view.bpStreak.value, { current: 1, max: 1 })
  assert.deepEqual(records, before)
  view.mode.value = 'bg'; view.props.records = [bp]; assert.equal(view.hasData.value, false)
  view.props.records = [bg]; assert.equal(view.hasData.value, true)
  view.props.records = [row({ diastolicBp: 80 })]; view.mode.value = 'bp'; assert.equal(view.hasData.value, true)
})

function monitoring() {
  const source = read('views/MonitoringCenter.vue')
  const expression = source.match(/const chartOption = computed\(\(\) => \{[\s\S]*?\n\}\)/)[0]
  return new Function('computed', 'snapshot', 'glucoseInMmol', expression + ';return chartOption.value')(Vue.computed, Vue.ref({ vitalTrend: [{ date: '2026-10-02', time: '08:00', systolic: 120, diastolic: 80, glucose: 5.5, glucoseUnit: 'mmol/L' }] }), value => value)
}
function tokens(theme) {
  const result = {}
  postcss.parse(read('styles/health-appearance.css')).walkRules(rule => {
    if (rule.parent.type === 'atrule' || !rule.selectors.some(selector => selector === ':root' || selector === `:root[data-health-theme="${theme}"]`)) return
    for (const declaration of rule.nodes) if (declaration.type === 'decl') result[declaration.prop] = declaration.value
  })
  return result
}
function rgb(color) { return [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16)) }
function contrast(a, b) {
  const light = color => rgb(color).map(value => value / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0)
  const values = [light(a), light(b)].sort((a, b) => b - a)
  return (values[0] + .05) / (values[1] + .05)
}
for (const theme of ['platform', 'white', 'blue', 'mint', 'sand', 'dark']) {
  test(`${theme} monitoring strokes, point markers and legend tokens agree at 3:1 contrast`, () => {
    const source = monitoring(), before = source.series.map(series => series.lineStyle.color)
    const result = adaptChartOption(source, theme), palette = tokens(theme)
    for (const [index, key] of ['systolic', 'diastolic', 'glucose'].entries()) {
      const series = result.series[index], color = series.lineStyle.color
      assert.ok(contrast(color, palette['--paper']) >= 3, `${color} against ${palette['--paper']}`)
      assert.equal(series.itemStyle.color, color)
      assert.equal(palette[`--chart-${key}`], color)
      assert.strictEqual(series.data, source.series[index].data)
    }
    const [r, g, b] = rgb(result.series[2].lineStyle.color)
    assert.ok(r > g && g > b, 'The glucose line remains amber')
    assert.deepEqual(source.series.map(series => series.lineStyle.color), before)
    assert.equal(result.series[0].markLine.data[0].yAxis, 140)
  })
}

function chartComponent() {
  const { descriptor } = parse(read('components/HealthChart.vue'))
  let code = compileScript(descriptor, { id: 'chart-accessibility-test', inlineTemplate: true }).content
  const deps = { appearanceTheme: Vue.ref('white'), adaptChartOption, ...motionModule, VChart }
  code = code.replace(/^import\s+\{([^}]+)\}\s+from\s+['"]vue['"];?$/gm, (_, names) => {
    names.split(',').forEach(name => { const [key, alias = key] = name.trim().split(/\s+as\s+/); deps[alias] = Vue[key] }); return ''
  }).replace(/^import.*$/gm, '').replace('export default', 'return')
  return new Function(...Object.keys(deps), code)(...Object.values(deps))
}
function mountChart(t, source, { reduced = false, legacy = false, matchMedia = true } = {}) {
  const listeners = new Set(), media = { matches: reduced }
  if (legacy) { media.addListener = fn => listeners.add(fn); media.removeListener = fn => listeners.delete(fn) }
  else { media.addEventListener = (name, fn) => { assert.equal(name, 'change'); listeners.add(fn) }; media.removeEventListener = (name, fn) => listeners.delete(fn) }
  const oldWindow = globalThis.window
  globalThis.window = matchMedia ? { matchMedia: query => { assert.equal(query, '(prefers-reduced-motion: reduce)'); return media } } : {}
  const host = tag => ({ tag, children: [], style: {}, setAttribute() {}, getAttribute() {}, removeAttribute() {} })
  const renderer = Vue.createRenderer({
    createElement: host, createText: text => ({ text }), createComment: text => ({ text }),
    setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
    insert(node, parent) { parent.children.push(node); node.parent = parent }, remove(node) { node.parent.children = node.parent.children.filter(child => child !== node) },
    parentNode: node => node.parent, nextSibling: () => null, patchProp(node, key, previous, value) { node[key] = value }
  })
  const option = Vue.shallowRef(source), exposed = Vue.shallowRef(), component = chartComponent()
  const app = renderer.createApp({ render: () => Vue.h(component, { ref: exposed, option: option.value, 'init-options': { renderer: 'svg', ssr: true, width: 700, height: 300 } }) })
  app.mount(host('root'))
  let stopped = false
  const unmount = () => { if (!stopped) { stopped = true; app.unmount() }; if (oldWindow === undefined) delete globalThis.window; else globalThis.window = oldWindow }
  t.after(unmount)
  return { option, exposed, listeners, unmount, async setReduced(value) { media.matches = value; for (const fn of listeners) fn({ matches: value }); await tick() } }
}
function interactiveOption() {
  return { animationDuration: 350, legend: {}, dataZoom: [{ type: 'inside', start: 0, end: 100 }], xAxis: { type: 'category', data: ['a', 'b', 'c', 'd'] }, yAxis: { type: 'value' }, series: [
    { id: 'default', name: 'Default', type: 'line', data: [1, 2, 3, 4] },
    { id: 'disabled', name: 'Disabled', type: 'line', animation: false, data: [2, 3, 4, 5] },
    { id: 'enabled', name: 'Enabled', type: 'line', animation: true, data: [3, 4, 5, 6] }
  ] }
}
const animation = chart => chart.getModel().getSeries().map(series => series.isAnimationEnabled())

test('initial reduced motion disables actual ECharts animation, including explicit series opt-ins', async t => {
  const source = interactiveOption(), view = mountChart(t, source, { reduced: true }); await tick()
  assert.deepEqual(animation(view.exposed.value.chart), [false, false, false])
  assert.equal(view.exposed.value.getOption().animation, false)
  assert.equal(source.animation, undefined)
  assert.equal(source.series[2].animation, true)
})

test('live motion toggles restore actual animation and preserve the same instance, zoom and legend state', async t => {
  const view = mountChart(t, interactiveOption()); await tick()
  const chart = view.exposed.value.chart
  assert.deepEqual(animation(chart), [true, false, true])
  chart.dispatchAction({ type: 'dataZoom', start: 25, end: 75 })
  chart.dispatchAction({ type: 'legendUnSelect', name: 'Default' })
  // ECharts adjusts its automatic dataZoom throttle with animation. Assert the
  // user's range rather than the renderer's internal scheduling default.
  const zoomState = () => chart.getOption().dataZoom.map(({ start, end, startValue, endValue }) => ({ start, end, startValue, endValue }))
  const zoom = zoomState(), selected = structuredClone(chart.getOption().legend[0].selected)
  for (const reduced of [true, false, true, false]) {
    await view.setReduced(reduced)
    assert.strictEqual(view.exposed.value.chart, chart)
    assert.deepEqual(animation(chart), reduced ? [false, false, false] : [true, false, true])
    assert.deepEqual(zoomState(), zoom)
    assert.deepEqual(chart.getOption().legend[0].selected, selected)
    assert.equal(chart.getOption().animationDuration, 350)
  }
})

test('source replacements while reduced honor the preference then restore the latest explicit opt-outs', async t => {
  const view = mountChart(t, interactiveOption(), { reduced: true }); await tick()
  const source = interactiveOption(); source.animation = false; source.series = [source.series[0], source.series[2]]; source.series[0].data = [9, 8, 7, 6]
  view.option.value = source; await tick()
  const chart = view.exposed.value.chart
  assert.deepEqual(animation(chart), [false, false])
  assert.deepEqual(chart.getOption().series[0].data, [9, 8, 7, 6])
  await view.setReduced(false)
  assert.equal(chart.getOption().animation, false)
  assert.deepEqual(animation(chart), [false, true])
  assert.equal(chart.getOption().series.length, 2, 'Source replacement must still remove obsolete series')
})

for (const legacy of [false, true]) {
  test(`${legacy ? 'legacy' : 'modern'} preference listener updates and is removed at unmount`, async t => {
    const view = mountChart(t, interactiveOption(), { legacy }); await tick()
    assert.equal(view.listeners.size, 1)
    await view.setReduced(true); assert.deepEqual(animation(view.exposed.value.chart), [false, false, false])
    view.unmount(); assert.equal(view.listeners.size, 0)
  })
}

test('missing matchMedia keeps ordinary chart behavior without a listener', async t => {
  const view = mountChart(t, interactiveOption(), { matchMedia: false }); await tick()
  assert.deepEqual(animation(view.exposed.value.chart), [true, false, true])
  assert.equal(view.listeners.size, 0)
})

test('imperative animation opt-outs survive a preference cycle', async t => {
  const view = mountChart(t, interactiveOption()); await tick()
  view.exposed.value.setOption({ animation: false, series: [{ id: 'enabled', animation: false }] })
  await view.setReduced(true)
  await view.setReduced(false)
  assert.equal(view.exposed.value.getOption().animation, false)
  assert.deepEqual(animation(view.exposed.value.chart), [false, false, false])
})

test('imperative updates while reduced keep new series quiet and restore their authored opt-outs', async t => {
  const view = mountChart(t, interactiveOption(), { reduced: true }); await tick()
  view.exposed.value.setOption({ animation: false, series: [
    { id: 'enabled', animation: false },
    { id: 'added', name: 'Added', type: 'line', animation: true, data: [2, 4, 6, 8] }
  ] })
  assert.deepEqual(animation(view.exposed.value.chart), [false, false, false, false])
  await view.setReduced(false)
  assert.equal(view.exposed.value.getOption().animation, false)
  assert.deepEqual(animation(view.exposed.value.chart), [false, false, false, true])
})

for (const updateOptions of [{ notMerge: true }, { replaceMerge: ['series'] }]) {
  test(`imperative ${JSON.stringify(updateOptions)} removes old series and retains only replacement motion settings`, async t => {
    const view = mountChart(t, interactiveOption(), { reduced: true }); await tick()
    view.exposed.value.setOption({ ...interactiveOption(), animation: false, series: [{ id: 'replacement', name: 'Replacement', type: 'line', animation: true, data: [4, 3, 2, 1] }] }, updateOptions)
    assert.deepEqual(animation(view.exposed.value.chart), [false])
    await view.setReduced(false)
    assert.deepEqual(animation(view.exposed.value.chart), [true])
    assert.deepEqual(view.exposed.value.chart.getModel().getSeries().map(series => series.id), ['replacement'])
  })
}

test('imperative named and positional updates preserve separate unnamed animation opt-outs', async t => {
  const source = interactiveOption(); source.series.forEach(series => { delete series.id }); source.series[0].animation = false
  const view = mountChart(t, source, { reduced: true }); await tick()
  view.exposed.value.setOption({ series: [{ name: 'Enabled', animation: false }, { animation: true }] })
  await view.setReduced(false)
  assert.deepEqual(animation(view.exposed.value.chart), [true, false, false])
})

test('duplicate series names keep their individual animation opt-outs after an imperative data update', async t => {
  const source = interactiveOption(); source.series = source.series.slice(0, 2); source.series.forEach(series => { delete series.id; series.name = 'Same' })
  const view = mountChart(t, source, { reduced: true }); await tick()
  view.exposed.value.setOption({ series: [{ data: [4, 3, 2, 1] }] })
  await view.setReduced(false)
  assert.deepEqual(animation(view.exposed.value.chart), [true, false])
})

test('renaming an unnamed-id series while reduced retains its existing animation opt-out', async t => {
  const source = interactiveOption(); source.series = [{ name: 'Original', type: 'line', animation: false, data: [1, 2, 3, 4] }]
  const view = mountChart(t, source, { reduced: true }); await tick()
  view.exposed.value.setOption({ series: [{ name: 'Renamed', data: [4, 3, 2, 1] }] })
  await view.setReduced(false)
  assert.deepEqual(animation(view.exposed.value.chart), [false])
  assert.equal(view.exposed.value.getOption().series[0].name, 'Renamed')
})

for (const initiallyReduced of [true, false]) for (const [retained, index, enabled] of [['disabled', 1, false], ['enabled', 2, true]]) {
  test(`partial replaceMerge retains sparse index ${index} and its opt-out with initial reduced=${initiallyReduced}`, async t => {
    const view = mountChart(t, interactiveOption(), { reduced: initiallyReduced }); await tick()
    const chart = view.exposed.value.chart
    assert.doesNotThrow(() => view.exposed.value.setOption({ series: [{ id: retained, data: [9, 8, 7, 6] }] }, { replaceMerge: ['series'] }))
    const check = reduced => {
      assert.strictEqual(view.exposed.value.chart, chart)
      const series = chart.getModel().getSeries()
      assert.equal(series.length, 1)
      assert.equal(series[0].id, retained)
      assert.equal(series[0].componentIndex, index, 'A metadata hole must not shift the surviving component')
      assert.deepEqual(chart.getOption().series.map(item => item?.id ?? null), [...Array(index).fill(null), retained])
      assert.deepEqual(chart.getOption().series[index].data, [9, 8, 7, 6])
      assert.deepEqual(animation(chart), [reduced ? false : enabled])
    }
    check(initiallyReduced)
    for (const reduced of [true, false, true, false]) { await view.setReduced(reduced); check(reduced) }
  })
}

test('public clear resets animation metadata before reused IDs receive fresh data', async t => {
  const view = mountChart(t, interactiveOption(), { reduced: true }); await tick()
  view.exposed.value.clear()
  const fresh = interactiveOption(); delete fresh.series[1].animation
  view.exposed.value.setOption(fresh)
  await view.setReduced(false)
  assert.deepEqual(animation(view.exposed.value.chart), [true, true, true])
})

test('a cleared chart stays empty across motion toggles without reconstructing removed series', async t => {
  const view = mountChart(t, interactiveOption(), { reduced: true }); await tick()
  view.exposed.value.clear()
  const chart = view.exposed.value.chart, updates = [], setOption = chart.setOption.bind(chart)
  chart.setOption = (option, ...args) => { updates.push(option); return setOption(option, ...args) }
  for (const reduced of [false, true, false]) {
    await view.setReduced(reduced)
    assert.equal(view.exposed.value.chart.getModel().getSeries().length, 0)
  }
  assert.ok(updates.every(option => !option.series?.some(Boolean)), 'No preference patch may reinsert cleared series metadata')
})

test('replaceMerge using the actual generated series ID retains an explicit opt-out', async t => {
  const source = interactiveOption(); source.series = [{ name: 'Same', type: 'line', animation: false, data: [1, 2, 3, 4] }]
  const view = mountChart(t, source, { reduced: true }); await tick()
  const series = view.exposed.value.chart.getModel().getSeries()[0]
  view.exposed.value.setOption({ series: [{ id: series.id, data: [4, 3, 2, 1] }] }, { replaceMerge: ['series'] })
  await view.setReduced(false)
  assert.deepEqual(animation(view.exposed.value.chart), [false])
  assert.equal(view.exposed.value.chart.getModel().getSeries()[0].id, series.id)
})
