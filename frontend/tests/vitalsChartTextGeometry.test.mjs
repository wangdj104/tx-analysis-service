import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as Vue from 'vue'
import { parse } from '@vue/compiler-sfc'
import { init, use } from 'echarts/core'
import { SVGRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { adaptChartOption } from '../src/utils/appearanceChart.js'
import { measureVitalsChartText, assertVitalsChartText } from '../../scripts/ui-chart-text-geometry.mjs'

use([SVGRenderer, LineChart, GridComponent, LegendComponent, MarkLineComponent, TooltipComponent])
const records = Array.from({ length: 21 }, (_, index) => ({
  recordDate: `2026-10-${String(index + 1).padStart(2, '0')}`, recordTime: index % 2 ? '08:00' : '09:15:30',
  systolicBp: 120 + index, diastolicBp: 80, bloodGlucose: index % 2 ? 108 : 5.1, bgUnit: index % 2 ? 'mg/dL' : 'mmol/L',
}))
function trend(t, edition, rows = records) {
  const file = edition === 'en' ? '../src/components/VitalsTrendPanel.vue' : '../../cn/frontend/src/components/VitalsTrendPanel.vue'
  const source = readFileSync(new URL(file, import.meta.url), 'utf8')
  const code = parse(source).descriptor.scriptSetup.content.replace(/^import[\s\S]*?from\s*['"][^'"]+['"];?\s*$/gm, '')
  const props = Vue.reactive({ records: rows }), scope = Vue.effectScope()
  const dependencies = { computed: Vue.computed, ref: Vue.ref, defineProps: () => props, use() {}, CanvasRenderer: {}, LineChart: {}, GridComponent: {}, LegendComponent: {}, MarkLineComponent: {}, TooltipComponent: {} }
  const result = scope.run(() => new Function(...Object.keys(dependencies), `${code};return { bpOption, bgOption }`)(...Object.values(dependencies)))
  t.after(() => scope.stop())
  return { ...result, props }
}
function render(t, option, width, theme = 'platform') {
  const chart = init(null, null, { renderer: 'svg', ssr: true, width, height: 320 })
  chart.setOption({ ...adaptChartOption(option, theme), animation: false })
  // ECharts' real renderer and layout run without a browser or listening socket.
  chart.renderToSVGString()
  t.after(() => chart.dispose())
  return chart
}

// Reverting grid label containment or endpoint alignment must fail on real
// renderer bounds; changing only an option's spelling cannot satisfy this test.
for (const edition of ['en', 'cn']) for (const theme of ['platform', 'white', 'blue', 'mint', 'sand', 'dark']) for (const width of [214, 236, 269, 620]) {
  test(`${edition}/${theme} at ${width}px: complete timestamps and series legends fit without colliding`, t => {
    const view = trend(t, edition)
    for (const kind of ['bpOption', 'bgOption']) {
      const chart = render(t, view[kind].value, width, theme)
      assertVitalsChartText(measureVitalsChartText(chart))
    }
  })
}
for (const edition of ['en', 'cn']) test(`${edition}: layout keeps data, references, units and native legend selection`, t => {
  const before = structuredClone(records), view = trend(t, edition)
  const bp = render(t, view.bpOption.value, 214), bg = render(t, view.bgOption.value, 214)
  assert.deepEqual(bp.getOption().series.map(series => series.data), [records.map(row => row.systolicBp), records.map(row => row.diastolicBp)])
  assert.deepEqual(bg.getOption().series[0].data, records.map(row => row.bgUnit === 'mg/dL' ? 6 : 5.1))
  assert.deepEqual(bp.getOption().series[0].markLine.data.map(mark => mark.yAxis), [140])
  assert.deepEqual(bg.getOption().series[0].markLine.data.map(mark => mark.yAxis), [6.1, 7.8])
  assert.equal(bp.getOption().yAxis[0].name, 'mmHg'); assert.equal(bg.getOption().yAxis[0].name, 'mmol/L')
  const name = bp.getOption().series[0].name
  bp.dispatchAction({ type: 'legendToggleSelect', name }); assert.equal(bp.getOption().legend[0].selected[name], false)
  bp.dispatchAction({ type: 'legendToggleSelect', name }); assert.equal(bp.getOption().legend[0].selected[name], true)
  assert.deepEqual(records, before)
})
for (const edition of ['en', 'cn']) test(`${edition}: one reading and date-only readings still have visible complete labels`, t => {
  for (const rows of [[records[0]], records.map(row => ({ ...row, recordTime: '' }))]) {
    const view = trend(t, edition, rows)
    for (const kind of ['bpOption', 'bgOption']) assertVitalsChartText(measureVitalsChartText(render(t, view[kind].value, 214)))
  }
})

for (const target of ['axis', 'legend']) test(`rejects ${target} labels whose actual visible glyph spans are truncated`, t => {
  const view = trend(t, 'en'), option = view.bpOption.value
  const truncated = target === 'axis'
    ? { ...option, xAxis: { ...option.xAxis, axisLabel: { ...option.xAxis.axisLabel, width: 12, overflow: 'truncate' } } }
    : { ...option, legend: { ...option.legend, textStyle: { width: 22, overflow: 'truncate' } } }
  const chart = render(t, truncated, 214)
  const actualSpans = chart.getZr().storage.getDisplayList().filter(item => item.type === 'tspan').map(item => item.style.text)
  assert.ok(actualSpans.some(text => text.includes('...')), 'The real renderer must actually truncate the test labels')
  assert.throws(() => assertVitalsChartText(measureVitalsChartText(chart)), /full (endpoint timestamp|series legend) is missing/)
})

test('browser observer uses the edition-scoped ECharts registry and verifies the bundled renderer identity', () => {
  const harness = readFileSync(new URL('./uiSeventeenMonitoring.browser.mjs', import.meta.url), 'utf8')
  assert.match(harness, /const editionEchartsCore = await realpath\(join\(frontend, 'node_modules\/echarts\/core\.js'\)\)/)
  assert.match(harness, /import \{getInstanceByDom\} from '\/@fs\/\$\{editionEchartsCore\}'/)
  assert.match(harness, /assert\.equal\(observerCore, rendererCore/)
  assert.match(harness, /report\.chartModuleIdentity\[edition\]/)
})

test('collision guard rejects overlapping actual text spans even when every label is complete', t => {
  const measurement = measureVitalsChartText(render(t, trend(t, 'en').bpOption.value, 214))
  const target = measurement.legend[0], tick = measurement.ticks[0]
  const dx = tick.left - target.left, dy = tick.top - target.top
  for (const box of [target, ...target.spans]) { box.left += dx; box.right += dx; box.top += dy; box.bottom += dy }
  assert.throws(() => assertVitalsChartText(measurement), /overlapping text/)
})
