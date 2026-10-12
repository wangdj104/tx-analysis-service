// QA-shell fidelity regression for run38189464350's clipped desktop glucose card.
// Source/CSS and geometry oracles are offline checks; browser evidence is separate.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
const read = file => readFileSync(new URL(file, import.meta.url), 'utf8')
const harness = read('./uiSeventeenMonitoring.browser.mjs')
const app = parse(read('../src/App.vue')).descriptor

function boundsOracle() {
  const start = harness.indexOf('function assertBpSummaryBounds(')
  assert.ok(start >= 0, 'Check actual BP card/container geometry instead of relying on document scrollWidth')
  const end = harness.indexOf('\nasync function capture', start)
  assert.ok(end > start)
  return new Function('assert', `${harness.slice(start, end)};return assertBpSummaryBounds`)(assert)
}
function fittingGeometry() {
  return {
    containers: ['page', 'main', 'inner', 'header', 'summary'].map(name => ({ name, left: 0, right: 1365, width: 1365, height: 100, boxSizing: 'border-box' })),
    cards: [0, 1, 2].map(index => ({ name: `card${index}`, left: 900 + index * 145, right: 1045 + index * 145, width: 145, height: 90, boxSizing: 'border-box', clippingAncestors: [{ name: 'main', left: 0, right: 1365 }] })),
  }
}

test('monitoring QA imports the actual App global reset before the same entry theme styles', () => {
  assert.equal(app.styles[0].scoped, undefined)
  assert.match(app.styles[0].content, /\*\s*\{\s*box-sizing:\s*border-box/)
  const reset = harness.indexOf("import '/@fs/${frontend}/src/App.vue?vue&type=style&index=0&lang.css';")
  assert.ok(reset >= 0, 'Reuse the actual unscoped App style without executing App authentication/navigation behavior')
  assert.ok(reset < harness.indexOf("import 'element-plus/dist/index.css';"), 'Match actual main.js App-before-theme CSS import order')
  assert.match(harness, /\.\.\.files, 'App\.vue'/, 'Tie the imported global style to evidence source hashes')
  assert.doesNotMatch(harness, /import App from|createApp\(App\)/)
})
test('actual BP card/container bounds reject desktop clipping even when document width fits', () => {
  const check = boundsOracle(), actual = fittingGeometry()
  // Missing App reset: page-inner width100% plus32px padding on both sides.
  actual.containers[2].right = 1429; actual.containers[2].width = 1429; actual.containers[2].boxSizing = 'content-box'
  actual.cards[2].right = 1397
  assert.throws(() => check(actual, 1365), /viewport|border-box/)
  check(fittingGeometry(), 1365)
})
test('in-viewport cards must also fit real clipping ancestors', () => {
  const check = boundsOracle(), geometry = fittingGeometry()
  geometry.cards[2].clippingAncestors.push({ name: 'clipped-header', left: 0, right: 1300 })
  assert.throws(() => check(geometry, 1365), /clipp/)
  assert.throws(() => check({ ...fittingGeometry(), cards: [] }, 1365), /three|3/)
})
test('every BP screenshot records and checks computed reset and card/container geometry', () => {
  assert.match(harness, /getComputedStyle\(element\)\.boxSizing/)
  assert.match(harness, /clippingAncestors/)
  assert.match(harness, /assertBpSummaryBounds\(geometry\.bpSummary, result\.viewport\)/)
  assert.match(harness, /\.bp-summary \.summary-item/)
  assert.match(harness, /geometry\.scrollWidth <= geometry\.innerWidth/)
  assert.doesNotMatch(harness, /overflow-x:\s*hidden|clip:\s*\{|waitForTimeout/)
})
