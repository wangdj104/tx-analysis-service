import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { baseParse, compile } from '@vue/compiler-dom'
import * as Vue from 'vue'
import { renderToString } from '@vue/server-renderer'

const source = fs.readFileSync(new URL('../src/views/MedicalRecordManager.vue', import.meta.url), 'utf8')
const cn = source.includes('title="医疗记录详情"')
const labels = cn ? ['检测值', '单位', '参考范围', '状态'] : ['Measured value', 'Unit', 'Reference range', 'Status']
function cardTemplate() {
  const ast = baseParse(parse(source).descriptor.template.content)
  let found
  function visit(node) {
    if (node.type === 1 && node.props.some(prop => prop.type === 6 && prop.name === 'class' && prop.value?.content.split(/\s+/).includes('medical-result-cards'))) found = node
    for (const child of node.children || []) visit(child)
  }
  visit(ast)
  assert.ok(found, 'The medical detail must expose a labelled mobile result-card layout')
  return found.loc.source
}
function actualFunction(name) {
  const match = source.match(new RegExp(`function ${name}\\(status\\) \\{[\\s\\S]*?\\n\\}`))
  assert.ok(match)
  return new Function(`return (${match[0]})`)()
}
async function render(items) {
  const code = compile(cardTemplate(), { mode: 'function', prefixIdentifiers: true }).code
  const render = new Function('Vue', code)(Vue)
  return renderToString(Vue.createSSRApp({ render, data: () => ({ currentRecord: items === null ? null : { items }, getAbnormalText: actualFunction('getAbnormalText'), getAbnormalType: actualFunction('getAbnormalType') }) }))
}

test('phone result cards keep every field labelled, preserve zero and escape original text', async () => {
  const html = await render([{ id: 1, itemName: '<Synthetic test>', resultValue: 0, unit: 'mmol/L', referenceRange: '0–5', isAbnormal: 1 }])
  assert.match(html, /&lt;Synthetic test&gt;/)
  assert.doesNotMatch(html, /<Synthetic test>/)
  for (const label of labels) assert.ok(html.includes(`<dt>${label}</dt>`), label)
  for (const value of ['<dd>0</dd>', 'mmol/L', '0–5', 'high']) assert.ok(html.includes(value), value)
})

test('all existing results and original abnormal labels remain available without new interpretation', async () => {
  const html = await render([0, -1, 1].map((isAbnormal, i) => ({ itemName: `Synthetic ${i}`, resultValue: `${i}`, unit: 'U/L', referenceRange: '0–10', isAbnormal })))
  assert.equal((html.match(/class="medical-result-card"/g) || []).length, 3)
  for (const value of [cn ? '正常' : 'Normal', 'low', 'high']) assert.ok(html.includes(value))
})

test('absent and empty source records show an explicit empty state without stale results', async () => {
  for (const items of [null, undefined, []]) {
    const html = await render(items)
    assert.ok(html.includes(cn ? '暂无检验项目' : 'No examination items'))
    assert.equal((html.match(/class="medical-result-card"/g) || []).length, 0)
  }
  const html = await render([{ itemName: 'Synthetic missing fields', resultValue: null, unit: '', referenceRange: undefined, isAbnormal: 0 }])
  assert.equal((html.match(/<dd>—<\/dd>/g) || []).length, 3)
})

test('responsive switching is confined to detail results and preserves the desktop table', () => {
  cardTemplate()
  assert.match(source, /:data="currentRecord\?\.items \|\| \[\]" class="app-data-table medical-result-table"/)
  assert.match(source, /\.medical-result-cards\s*\{\s*display:\s*none;/)
  const mobile = source.match(/@media\s*\(max-width:\s*600px\)\s*\{([\s\S]*?)\n\}/)?.[1]
  assert.ok(mobile, 'A narrow-screen media rule is required')
  assert.match(mobile, /\.medical-result-table\s*\{\s*display:\s*none;/)
  assert.match(mobile, /\.medical-result-cards\s*\{\s*display:\s*grid;/)
  assert.match(source, /\.medical-result-card[\s\S]*?overflow-wrap:\s*anywhere;/)
})


test('mobile results preserve the saved large-text accessibility setting', () => {
  const css = fs.readFileSync(new URL('../src/styles/care-accessibility.css', import.meta.url), 'utf8')
  assert.match(css, /body\.care-senior \.medical-result-card :is\(h3, dt, dd\)\s*\{[^}]*font-size:\s*18px/)
  assert.match(css, /body\.care-senior \.medical-result-status\s*\{[^}]*font-size:\s*17px/)
  assert.match(css, /body\.care-senior \.medical-result-empty\s*\{[^}]*font-size:\s*18px/)
})


test('card text and status cues use supported appearance tokens', () => {
  assert.doesNotMatch(source, /var\(--ink-900\)/)
  for (const status of ['success', 'warning', 'danger']) {
    assert.match(source, new RegExp(`\\.medical-result-status--${status}\\s*\\{[^}]*color: var\\(--${status}\\);[^}]*background: var\\(--${status}-soft\\)`))
  }
})
