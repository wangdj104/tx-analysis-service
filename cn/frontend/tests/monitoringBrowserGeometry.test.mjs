// Offline layout/automation regressions. These do not replace real browser geometry.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const read = file => readFileSync(new URL(file, import.meta.url), 'utf8')
const source = read('../src/views/MonitoringCenter.vue')
const harness = read('../../../frontend/tests/uiSeventeenMonitoring.browser.mjs')

test('monitoring controls 100% width includes mobile padding and border', () => {
  const controls = source.match(/\.monitoring-controls\s*\{([^}]+)\}/)?.[1]
  assert.ok(controls)
  // Actual 375px CI geometry: 14px page gutters leave a 347px parent.
  const parentWidth = 375 - 14 * 2, mobilePadding = 9 * 2, border = 1 * 2
  const borderBox = /box-sizing:\s*border-box/.test(controls)
  const outerWidth = parentWidth + (borderBox ? 0 : mobilePadding + border)
  assert.equal(outerWidth, parentWidth, '100% controls must fit the patient page instead of overflowing to x381')
})

test('history scroll origin is captured at the real click boundary, not before actionability scrolling', async () => {
  const start = harness.indexOf('async function readEditClickOrigin(')
  assert.ok(start >= 0, 'Observe the real native click origin')
  const end = harness.indexOf('\nasync function editHistory', start)
  assert.ok(end > start)
  const callback = new Function(`${harness.slice(start, end)};return readEditClickOrigin`)()
  let scrollY = 1000, capture
  const element = { addEventListener(type, listener, options) { assert.equal(type, 'click'); assert.equal(options.capture, true); assert.equal(options.once, true); capture = listener }, getBoundingClientRect() { return { top: 440, bottom: 464 } } }
  const document = { documentElement: { scrollHeight: 2500 } }, window = { get scrollY() { return scrollY }, innerHeight: 900 }
  const originalWindow = globalThis.window, originalDocument = globalThis.document
  globalThis.window = window; globalThis.document = document
  try {
    const opener = { async evaluate(fn) { return fn(element) }, async click() { scrollY += 24; capture({ isTrusted: true }) } }
    const origin = await callback(opener)
    assert.equal(origin.scrollY, 1024, 'Native focus/Playwright click scrolling belongs to the original context')
    assert.equal(origin.rowTop, 440); assert.equal(origin.maxScrollY, 1600)
  } finally { globalThis.window = originalWindow; globalThis.document = originalDocument }
  assert.ok(harness.includes('const origin = await readEditClickOrigin(opener)'))
  assert.ok(harness.includes('Math.abs(restored.scrollY - origin.scrollY)'))
  assert.match(harness, /toBeLessThanOrEqual\(3\)/, 'Retain the original strict scroll tolerance')
})

test('monitoring screenshots retain full-page no-overflow checks and store failure diagnostics', () => {
  assert.match(harness, /fullPage: true/)
  assert.match(harness, /document\.documentElement\.scrollWidth/)
  assert.match(harness, /Screenshot must use the actual requested viewport width/)
  assert.doesNotMatch(harness, /overflow-x:\s*hidden|clip:\s*\{/)
})

test('monitoring browser uses built preview without a Vite dev-client or WebSocket exemption', () => {
  assert.match(harness, /import \{ build, preview \} from 'vite'/)
  assert.match(harness, /await preview\(harness\.config\)/)
  assert.match(harness, /routeWebSocket\('\*\*\/\*'/)
  assert.doesNotMatch(harness, /createServer|allow.*WebSocket|vite.*WebSocket.*allow/i)
})
