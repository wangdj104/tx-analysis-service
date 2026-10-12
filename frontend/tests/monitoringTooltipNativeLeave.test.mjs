// Offline native-input sequence regression for CI run38192419226.
// Real DOM tooltip behavior remains a separate browser acceptance gate.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const harness = readFileSync(new URL('./uiSeventeenMonitoring.browser.mjs', import.meta.url), 'utf8')

function leaveSequence(expect, settle) {
  const start = harness.indexOf('async function leaveMonitoringTooltip(')
  assert.ok(start >= 0, 'A proven native blur/leave sequence must replace unverified Refresh focus')
  const end = harness.indexOf('\nasync function stopMonitoringTimer', start)
  assert.ok(end > start)
  return new Function('expect', 'waitForMonitoringLayout', 'monitoringTooltipInputTrace', `${harness.slice(start, end)};return leaveMonitoringTooltip`)(expect, settle, () => {})
}
function fakePage(events, { disabled = false, tabFails = false, forbidKeyboard = false, forbidFocus = false, forbidPointer = false, active: initialActive = 'body' } = {}) {
  let active = initialActive
  const input = { async focus() { assert.equal(forbidKeyboard || forbidFocus, false, 'Dismissal must never add an extra input focus'); events.push('focus-switch'); active = 'switch' } }
  const refresh = { name: 'refresh', disabled }
  const neutral = { async hover() { assert.equal(forbidPointer, false, 'Keyboard dismissal must not add a pointer leave'); events.push('hover-neutral') } }
  const tooltip = { name: 'tooltip' }
  return {
    page: {
      async evaluate(callback, operation) { return ['focus-unchanged', 'pointer-neutral'].includes(operation) ? true : undefined }, // The real observer callback is tested below.
      locator(selector) { return selector.includes('.el-switch') ? { getByRole(role) { assert.equal(role, 'switch'); return input } } : selector === '.refresh-button' ? refresh : tooltip },
      getByTestId(id) { assert.equal(id, 'fixture-patient'); return neutral },
      keyboard: { async press(key) { assert.equal(forbidKeyboard, false, 'Pure hover must never add a keyboard blur'); assert.equal(key, 'Tab'); events.push('native-Tab'); if (!tabFails) active = 'refresh' } },
    },
    expect: target => ({
      toBe(expected) { assert.equal(target, expected) },
      async toBeEnabled() { events.push('assert-enabled'); assert.equal(target.disabled, false, 'Native blur destination must be enabled') },
      async toBeFocused() { const name = target === input ? 'switch' : target.name; events.push(`assert-focus-${name}`); assert.equal(active, name, 'Do not silently accept failed focus transfer') },
      async toBeHidden() { events.push('assert-tooltip-hidden'); assert.equal(target, tooltip); assert.notEqual(active, 'switch'); assert.ok(forbidPointer || events.includes('hover-neutral')) },
    }),
  }
}
test('actual harness proves native switch focus, Tab blur, neutral hover and normal hide in order', async () => {
  const events = [], fixture = fakePage(events)
  await leaveSequence(fixture.expect, async () => events.push('settled'))(fixture.page, { mode: 'reset' })
  assert.deepEqual(events, ['assert-enabled', 'focus-switch', 'assert-focus-switch', 'native-Tab', 'assert-focus-refresh', 'hover-neutral', 'assert-tooltip-hidden', 'settled'])
})
test('native leave sequence fails for disabled destination or failed Tab instead of hiding DOM', async () => {
  for (const options of [{ disabled: true }, { tabFails: true }]) {
    const events = [], fixture = fakePage(events, options)
    await assert.rejects(() => leaveSequence(fixture.expect, async () => {})(fixture.page, { mode: 'reset' }), /enabled|focus transfer/)
    assert.ok(!events.includes('assert-tooltip-hidden'))
  }
  assert.doesNotMatch(harness, /dispatchEvent\([^)]*(?:blur|mouseleave)|\.remove\(\)|tooltip.*style\.display\s*=|waitForTimeout/)
})
test('initial timer stop and both visible-tooltip checks share the proven native leave path', () => {
  const stop = harness.slice(harness.indexOf('async function stopMonitoringTimer'), harness.indexOf('\nasync function filter'))
  const verify = harness.slice(harness.indexOf('async function verifyMonitoringTooltip'), harness.indexOf('\nasync function monitoringCase'))
  assert.match(stop, /await leaveMonitoringTooltip\(page, \{ mode: 'reset' \}\)/)
  assert.match(verify, /await leaveMonitoringTooltip\(page, \{ mode \}\)/)
  assert.match(verify, /assertMonitoringTooltipBounds\(geometry, result\.viewport\)/)
  assert.match(verify, /monitoring-visible-tooltip.*preserveHover: true/)
  assert.match(verify, /monitoring-keyboard-tooltip.*preserveHover: true/)
})
test('pure-hover dismissal cannot add a focus or Tab cycle to make a broken pointer leave pass', async () => {
  const events = [], fixture = fakePage(events, { forbidKeyboard: true, active: 'refresh' })
  await leaveSequence(fixture.expect, async () => events.push('settled'))(fixture.page, { mode: 'hover' })
  assert.deepEqual(events, ['hover-neutral', 'assert-tooltip-hidden', 'settled'])
})
test('keyboard-only dismissal Tabs from the proven focused input without another focus or pointer cycle', async () => {
  const events = [], fixture = fakePage(events, { forbidFocus: true, forbidPointer: true, active: 'switch' })
  await leaveSequence(fixture.expect, async () => events.push('settled'))(fixture.page, { mode: 'focus' })
  assert.deepEqual(events, ['assert-focus-switch', 'assert-enabled', 'native-Tab', 'assert-focus-refresh', 'assert-tooltip-hidden', 'settled'])
})
test('read-only diagnostics record native events and DOM states, including failure cleanup', () => {
  const start = harness.indexOf('function monitoringTooltipInputTrace(')
  assert.ok(start >= 0, 'Record actual event and activeElement evidence instead of inventing an EN-only cause')
  const end = harness.indexOf('\nasync function leaveMonitoringTooltip', start)
  const trace = new Function(`${harness.slice(start, end)};return monitoringTooltipInputTrace`)()
  const listeners = new Map(), input = { tagName: 'INPUT', className: 'el-switch__input', getAttribute: () => 'switch' }
  const refresh = { tagName: 'BUTTON', className: 'refresh-button', disabled: false, matches: () => false, getAttribute: () => null }
  const trigger = { contains: element => element === input, matches: () => true }
  const tooltip = { matches: () => false, getAttribute: () => 'false' }
  const originals = [globalThis.document, globalThis.window, globalThis.getComputedStyle]
  globalThis.window = {}
  globalThis.document = { activeElement: input, querySelector: selector => selector === '.refresh-button' ? refresh : selector === '.monitoring-auto-refresh-tooltip' ? tooltip : trigger, addEventListener: (name, listener) => listeners.set(name, listener), removeEventListener: name => listeners.delete(name) }
  globalThis.getComputedStyle = () => ({ display: 'block', visibility: 'visible' })
  try {
    trace('start')
    listeners.get('blur')({ type: 'blur', isTrusted: true, timeStamp: 123, target: { ...input, closest: () => trigger }, relatedTarget: refresh })
    document.activeElement = refresh; trace('refresh-focused'); trace('finish')
    const result = window.__uiSeventeenMonitoringTooltipLeaves[0]
    assert.equal(result.states[0].triggerFocused, true)
    assert.equal(result.states[1].refreshFocused, true); assert.equal(result.states[1].refreshEnabled, true)
    assert.equal(result.states[1].triggerHovered, true)
    assert.equal(result.events[0].type, 'blur'); assert.equal(result.events[0].trusted, true)
    assert.equal(listeners.size, 0)
  } finally { [globalThis.document, globalThis.window, globalThis.getComputedStyle] = originals }
  assert.match(harness, /finally\s*\{\s*await page\.evaluate\(monitoringTooltipInputTrace, 'finish'\)/)
  assert.match(harness, /tooltipLeaveTraces: window\.__uiSeventeenMonitoringTooltipLeaves/)
})
