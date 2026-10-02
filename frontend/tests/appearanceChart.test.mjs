import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import * as Vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'

import { adaptChartOption } from '../src/utils/appearanceChart.js'

function contrast(color, background) {
  const luminance = hex => {
    const full = hex.length === 4 ? '#' + [...hex.slice(1)].map(c => c + c).join('') : hex
    const rgb = [1, 3, 5].map(index => parseInt(full.slice(index, index + 2), 16) / 255)
      .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
    return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722
  }
  const a = luminance(color), b = luminance(background)
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05)
}
function fixture() {
  const formatter = params => `Reading: ${params.value}`
  return {
    color: ['#ef4444', '#236b63', '#d6a642'],
    title: { text: 'Vital signs', subtext: 'Fictional test data', textStyle: { color: '#303133' } },
    tooltip: { renderMode: 'richText', formatter, textStyle: { color: '#fff' }, backgroundColor: '#fff' },
    legend: [{ bottom: 0, textStyle: { color: '#94a3b8' } }],
    grid: { left: 48, right: 50, top: 28, bottom: 52 },
    xAxis: { type: 'category', data: ['10-01'], axisLabel: { color: '#94a3b8', formatter } },
    yAxis: [{ name: 'mmHg', type: 'value', min: 40, max: 200 }, { name: 'mmol/L', type: 'value', min: 0 }],
    series: [{ name: 'Blood Pressure', type: 'line', data: [{ value: 141, symbol: 'diamond' }, null],
      itemStyle: { color: '#236b63' }, lineStyle: { color: '#236b63', width: 2.5 },
      label: { show: true, formatter },
      markLine: { symbol: 'none', silent: true, label: { color: '#8a6110', formatter: 'Reference 140' },
        lineStyle: { type: 'dashed', color: '#d6a642' }, data: [{ yAxis: 140 }, [{ coord: [0, 90] }, { coord: [1, 90] }]] }
    }]
  }
}

// Catches adapters that mutate source clinical data, reconstruct via JSON, or alter thresholds.
test('chart adaptation preserves readings, thresholds, formatting functions and source options', () => {
  const source = fixture(), original = structuredClone({ ...source, tooltip: undefined, xAxis: undefined, series: undefined })
  const data = source.series[0].data, marks = source.series[0].markLine.data
  const result = adaptChartOption(source, 'dark')
  assert.notEqual(result, source)
  assert.strictEqual(result.series[0].data, data)
  assert.deepEqual(result.series[0].markLine.data.map(item => Array.isArray(item) ? item.map(point => point.coord) : item.yAxis), [140, [[0, 90], [1, 90]]])
  assert.deepEqual(source.series[0].markLine.data, marks)
  assert.equal(source.series[0].itemStyle.color, '#236b63')
  assert.equal(result.yAxis[0].min, 40)
  assert.equal(result.yAxis[0].max, 200)
  assert.equal(result.yAxis[1].min, 0)
  assert.strictEqual(result.tooltip.formatter, source.tooltip.formatter)
  assert.strictEqual(result.series[0].label.formatter, source.series[0].label.formatter)
  assert.strictEqual(result.xAxis.axisLabel.formatter, source.xAxis.axisLabel.formatter)
  assert.equal(result.tooltip.renderMode, 'richText')
  assert.deepEqual(result.grid.left, 48)
  assert.deepEqual({ ...source, tooltip: undefined, xAxis: undefined, series: undefined }, original)
})

// Catches forgotten default axes, legend arrays, chart labels or reference labels on dark panels.
test('dark chart text and grid are readable on the dark card surface', () => {
  const result = adaptChartOption(fixture(), 'dark'), surface = '#18262e'
  for (const color of [result.textStyle?.color, result.title.textStyle.color, result.title.subtextStyle?.color,
    result.legend[0].textStyle.color, result.xAxis.axisLabel.color, result.yAxis[0].nameTextStyle?.color,
    result.yAxis[1].axisLabel?.color, result.series[0].label.color, result.series[0].markLine.label.color]) {
    assert.ok(color && contrast(color, surface) >= 4.5, `unreadable text ${color}`)
  }
  assert.ok(contrast(result.tooltip.textStyle.color, result.tooltip.backgroundColor) >= 4.5)
  assert.ok(result.yAxis[0].splitLine?.lineStyle?.color)
  assert.notEqual(result.yAxis[0].splitLine.lineStyle.color, '#f1f5f9')
})

// Catches changing clinical hue identity or needlessly brightening colors that already have contrast.
test('dark mode only brightens low-contrast semantic colors within the same hue', () => {
  const result = adaptChartOption(fixture(), 'dark')
  assert.equal(result.color[0], '#ef4444')
  assert.equal(result.color[2], '#d6a642')
  assert.notEqual(result.series[0].itemStyle.color, '#236b63')
  assert.equal(result.series[0].lineStyle.color, result.series[0].itemStyle.color)
  assert.ok(contrast(result.series[0].itemStyle.color, '#18262e') >= 3)
  const rgb = color => [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16))
  const [r, g, b] = rgb(result.series[0].itemStyle.color)
  assert.ok(g > b && b > r, 'clinical teal remains teal')
  assert.equal(result.series[0].markLine.lineStyle.type, 'dashed')
  assert.equal(result.series[0].markLine.lineStyle.color, '#d6a642')
})

// Catches light themes inheriting stale bright text or dark-adjusted clinical line colors.
test('every light theme restores semantic series colors with readable labels', () => {
  const source = fixture()
  adaptChartOption(source, 'dark')
  for (const theme of ['platform', 'white', 'blue', 'mint', 'sand']) {
    const result = adaptChartOption(source, theme)
    assert.equal(result.series[0].itemStyle.color, '#236b63', theme)
    assert.deepEqual(result.color, ['#ef4444', '#236b63', '#d6a642'], theme)
    assert.ok(contrast(result.xAxis.axisLabel.color, '#ffffff') >= 4.5, theme)
    assert.ok(contrast(result.series[0].label.color, '#ffffff') >= 4.5, theme)
  }
})

// Catches a blanket recursive recolor corrupting gradient stops, point metadata or formatter callbacks.
test('adaptation leaves gradient fills and function-valued semantic colors intact', () => {
  const gradient = { type: 'linear', colorStops: [{ offset: 0, color: '#236b63' }, { offset: 1, color: '#ffffff' }] }
  const color = params => params.value > 140 ? '#ef4444' : '#10b981'
  const option = { series: [{ type: 'line', data: [{ value: 141, extra: { color: 'clinical metadata' } }], itemStyle: { color }, areaStyle: { color: gradient } }] }
  const result = adaptChartOption(option, 'dark')
  assert.strictEqual(result.series[0].itemStyle.color, color)
  assert.strictEqual(result.series[0].areaStyle.color, gradient)
  assert.strictEqual(result.series[0].data, option.series[0].data)
  assert.ok(contrast(result.series[0].label?.color || '#000000', '#18262e') >= 4.5)
})

// Catches rich-text labels inheriting dark ink while keeping formatter control and safe render mode.
test('rich labels and per-reference labels adapt without changing render mode or clinical coordinates', () => {
  const option = fixture()
  option.series[0].label.rich = { reading: { color: '#303133', fontWeight: 700 } }
  option.series[0].markLine.data[0].label = { color: '#8a6110', formatter: '{b}' }
  const result = adaptChartOption(option, 'dark')
  assert.ok(contrast(result.series[0].label.rich.reading.color, '#18262e') >= 4.5)
  assert.equal(result.series[0].label.rich.reading.fontWeight, 700)
  assert.ok(contrast(result.series[0].markLine.data[0].label.color, '#18262e') >= 4.5)
  assert.equal(result.series[0].markLine.data[0].yAxis, 140)
  assert.equal(result.series[0].markLine.data[0].label.formatter, '{b}')
  assert.equal(option.series[0].markLine.data[0].label.color, '#8a6110')
  assert.equal(result.tooltip.renderMode, 'richText')
})

function chartWrapper() {
  const filename = new URL('../src/components/HealthChart.vue', import.meta.url)
  assert.ok(fs.existsSync(filename), 'HealthChart wrapper exists')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'))
  let script = compileScript(descriptor, { id: 'health-chart-test', inlineTemplate: true }).content
  const deps = { appearanceTheme: Vue.ref('white'), adaptChartOption, VChart: { name: 'ChartBoundary' } }
  script = script.replace(/^import\s+\{([^}]+)\}\s+from\s+['"]vue['"];?$/gm, (_, names) => {
    names.split(',').forEach(name => { const [key, alias = key] = name.trim().split(/\s+as\s+/); deps[alias] = Vue[key] })
    return ''
  }).replace(/^import.*$/gm, '').replace('export default', 'return')
  const component = new Function(...Object.keys(deps), script)(...Object.values(deps))
  return { component, appearanceTheme: deps.appearanceTheme }
}

// Catches a setup-time theme snapshot or wrapper that drops event/autoresize/class props.
test('wrapper re-derives rendered chart options when the appearance ref changes', () => {
  const { component, appearanceTheme } = chartWrapper()
  const source = fixture(), props = Vue.reactive({ option: source }), exposed = {}
  const render = component.setup(props, { expose: value => Object.assign(exposed, value) })
  const click = () => {}, attrs = { class: 'monitor-chart', autoresize: { throttle: 80 }, onClick: click, 'aria-label': 'Trend' }
  const light = render({ $attrs: attrs })
  appearanceTheme.value = 'dark'
  const dark = render({ $attrs: attrs })
  assert.notEqual(dark.props.option.xAxis.axisLabel.color, light.props.option.xAxis.axisLabel.color)
  assert.equal(dark.props.onClick, click)
  assert.deepEqual(dark.props.autoresize, { throttle: 80 })
  assert.equal(dark.props.class, 'monitor-chart')
  assert.equal(dark.props['aria-label'], 'Trend')
  assert.equal(dark.type, light.type)
  assert.equal(dark.key, light.key, 'switching does not key/remount the chart')
  appearanceTheme.value = 'white'
  const restored = render({ $attrs: attrs })
  assert.deepEqual(restored.props.option, light.props.option)
  assert.equal(source.series[0].itemStyle.color, '#236b63')
  assert.equal(typeof exposed.getDataURL, 'function')
  assert.equal(typeof exposed.resize, 'function')
})
