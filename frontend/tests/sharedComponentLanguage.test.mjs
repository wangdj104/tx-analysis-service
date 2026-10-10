import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope } from 'vue'
import { parse } from '@vue/compiler-sfc'
import { baseParse } from '@vue/compiler-dom'
const read = name => fs.readFileSync(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8')
function setup(name, deps, expose) {
  const script = parse(read(name)).descriptor.scriptSetup.content.replace(/^import.*$/gm, '')
  return new Function(...Object.keys(deps), script + '\nreturn {' + expose + '}')(...Object.values(deps))
}
function copy(name) {
  const values = []
  function walk(node) {
    if (node.type === 2 && node.content.trim()) values.push(node.content.trim())
    for (const prop of node.props || []) if (prop.type === 6 && ['aria-label', 'label', 'placeholder', 'title', 'description'].includes(prop.name) && prop.value?.content) values.push(prop.value.content)
    for (const child of node.children || []) walk(child)
  }
  walk(baseParse(parse(read(name)).descriptor.template.content))
  return values
}
for (const [name, expected] of [
  ['ColumnSetting', ['Column visibility', 'Choose columns to display', 'Restore defaults']],
  ['TimeScopeFilter', ['Time grouping']],
  ['WorkspaceNav', ['Main navigation']],
  ['DialysisModuleSidebar', ['Data entry']],
  ['VitalsTrendPanel', ['Vital sign trends', 'View blood pressure and blood glucose changes together. Reference lines help identify unusual trends.', 'Current consecutive abnormal blood pressure readings', 'Longest consecutive abnormal blood pressure readings', 'Total abnormal records', 'Blood pressure trend', 'Blood glucose trend']]
]) test(`${name} visible and accessibility copy uses readable English`, () => {
  const actual = copy(name)
  for (const label of expected) assert.ok(actual.includes(label), label)
})
test('time filter labels and placeholders use readable English for each period', () => {
  const props = reactive({ timeType: 'month' }), view = setup('TimeScopeFilter', { computed, defineProps: () => props, defineEmits: () => () => {}, MonthIcon: {}, Timer: {}, Histogram: {} }, 'dimensions,pickerLabel,pickerPlaceholder')
  assert.deepEqual(view.dimensions.map(x => x.label), ['By month', 'By week', 'By year'])
  for (const [type, label, placeholder] of [['month', 'Month', 'Select a month'], ['week', 'Week start', 'Select a date'], ['year', 'Year', 'Select a year']]) {
    props.timeType = type
    assert.equal(view.pickerLabel.value, label); assert.equal(view.pickerPlaceholder.value, placeholder)
  }
})
test('vital trend chart reference labels use readable English without changing reference values', () => {
  const view = setup('VitalsTrendPanel', { computed, ref, defineProps: () => ({ records: [] }), use() {}, CanvasRenderer: {}, LineChart: {}, GridComponent: {}, LegendComponent: {}, MarkLineComponent: {}, TooltipComponent: {} }, 'bpOption,bgOption')
  assert.equal(view.bpOption.value.series[0].markLine.label.formatter, 'Reference upper limit: 140')
  assert.deepEqual(view.bgOption.value.series[0].markLine.data.map(x => [x.name, x.yAxis]), [['Fasting reference', 6.1], ['After-meal reference', 7.8]])
})
