import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'

const harness = readFileSync(new URL('./uiSeventeenShell.browser.mjs', import.meta.url), 'utf8')
const measurement = harness.match(/snapshot = await page\.locator\('\.guide-card'\)\.evaluate\((card => \{[\s\S]*?\n      \})\)/)?.[1]
assert.ok(measurement, 'Execute the exact read-only guide measurement used by the browser matrix')

// This tests diagnostic serialization only. The actual browser remains the
// authority for painting order and elementFromPoint; never substitute this
// adapter's hit result for browser acceptance.
function inspect(hitKind) {
  const box = { left: 251, right: 295, top: 535, bottom: 579, width: 44, height: 44 }
  const node = (tagName, attributes = {}, style = {}) => ({
    tagName, parentElement: null, disabled: false,
    getBoundingClientRect: () => box,
    getAttribute: name => attributes[name] ?? null,
    contains(other) { return this === other },
    style: { position: 'static', zIndex: 'auto', pointerEvents: 'auto', opacity: '1', transform: 'none', isolation: 'auto', ...style },
  })
  const body = node('BODY'), target = node('SECTION', { class: 'plan-tasks guide-target', 'data-testid': 'collaboration-tasks' }, { position: 'relative', zIndex: '4801' })
  const occluder = node('P', { role: 'status' })
  target.parentElement = body; occluder.parentElement = target
  const close = node('BUTTON', { class: 'guide-close', 'aria-label': 'Pause guide' })
  const previous = node('BUTTON', { 'aria-label': 'Previous step' }), next = node('BUTTON', { 'aria-label': 'Confirm step and continue' })
  previous.disabled = true
  const card = node('SECTION', { class: 'guide-card' }), heading = node('H2'), footer = node('FOOTER')
  const layer = node('DIV', { class: 'guide-layer' }, { position: 'fixed', zIndex: '4800', pointerEvents: 'none' })
  card.parentElement = layer; layer.parentElement = body
  close.parentElement = card; previous.parentElement = footer; next.parentElement = footer; footer.parentElement = card
  card.querySelectorAll = () => [close, previous, next]
  card.querySelector = selector => ({ h2: heading, footer })[selector] || null
  card.closest = () => layer
  const document = {
    elementFromPoint: () => hitKind === 'control' ? close : hitKind === 'missing' ? null : occluder,
    querySelectorAll: () => [target],
  }
  return new Function('document', 'getComputedStyle', 'innerWidth', 'innerHeight', `return (${measurement})`)(document, element => element.style, 320, 900)(card)
}

test('guide diagnostics record the native hit element and its highlighted ancestor without accepting occlusion', () => {
  const snapshot = inspect('occluder'), close = snapshot.controls[0]
  assert.equal(close.inViewport, true)
  assert.equal(close.unobscured, false)
  assert.equal(close.hitElement?.tag, 'P')
  assert.equal(close.hitElement?.role, 'status')
  assert.equal(close.hitAncestors?.[0]?.testId, 'collaboration-tasks')
  assert.equal(close.hitAncestors?.[0]?.zIndex, '4801')
  assert.equal(snapshot.layer?.zIndex, '4800')
})

test('guide diagnostics retain the same native hit-test result for a control or absent hit', () => {
  const own = inspect('control').controls[0], missing = inspect('missing').controls[0]
  assert.equal(own.unobscured, true)
  assert.equal(own.hitElement?.label, 'Pause guide')
  assert.equal(missing.unobscured, false)
  assert.equal(missing.hitElement, null)
  assert.deepEqual(missing.hitAncestors, [])
})
