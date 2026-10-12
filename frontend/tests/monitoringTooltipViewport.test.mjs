// Offline regression for CI run38189464350's actual146.5px tooltip at x229.
// Real hover/focus/leave geometry remains an independent browser gate.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { buildPopperOptions } from 'element-plus/es/components/popper/src/utils.mjs'
const read = file => readFileSync(new URL(file, import.meta.url), 'utf8')
const view = read('../src/views/MonitoringCenter.vue')
const harness = read('./uiSeventeenMonitoring.browser.mjs')
const tag = view.match(/<el-tooltip\b([^>]+)>/)?.[1]
assert.ok(tag)
function sourceFunction(name, next) {
  const start = harness.indexOf(`${name}(`)
  assert.ok(start >= 0, `${name} must observe actual rendered tooltip state`)
  const functionStart = harness.lastIndexOf('function ', start)
  const asyncStart = harness.slice(functionStart - 6, functionStart) === 'async ' ? functionStart - 6 : functionStart
  const end = harness.indexOf(next, start)
  assert.ok(end > start)
  return new Function('assert', `${harness.slice(asyncStart, end)};return ${name}`)(assert)
}

test('actual Element Plus options leave safe viewport padding for fractional tooltip positioning', () => {
  const expression = tag.match(/:popper-options="([^"]+)"/)?.[1]
  const popperOptions = expression ? new Function(`return (${expression})`)() : {}
  const options = buildPopperOptions({ placement: 'bottom', strategy: 'absolute', offset: 12, gpuAcceleration: true, popperOptions })
  const padding = options.modifiers.filter(row => row.name === 'preventOverflow').at(-1).options.padding
  const rightPadding = typeof padding === 'number' ? padding : padding.right
  // Popper rounds position at DPR1; zero padding rounds228.5 to229, ending375.5.
  const width = 146.5, viewport = 375, roundedLeft = Math.round(viewport - width - rightPadding)
  assert.ok(roundedLeft + width <= viewport, 'Visible tooltip must fit instead of extending to375.5px')
  assert.ok(rightPadding >= 1, 'Safe padding must survive fractional rounding')
  assert.ok(tag.includes('popper-class="monitoring-auto-refresh-tooltip"'))
})
test('monitoring tooltip remains available through native keyboard focus and hover', () => {
  const trigger = tag.match(/:trigger="([^"]+)"/)?.[1]
  assert.ok(trigger, 'Keyboard focus must display the same useful help')
  assert.deepEqual(new Function(`return (${trigger})`)(), ['hover', 'focus'])
  assert.match(view, /<el-switch[^>]+v-model="autoRefresh"/)
  assert.ok(harness.includes("getByRole('switch').focus()"))
  assert.ok(harness.includes("capture(page, result, 'monitoring-visible-tooltip', { preserveHover: true })"))
})
test('visible tooltip overflow still fails and strict375px assertions remain unchanged', () => {
  const check = sourceFunction('assertMonitoringTooltipBounds', '\nasync function verifyMonitoringTooltip')
  assert.throws(() => check({ left: 229, right: 375.5, width: 146.5, height: 32, scrollWidth: 376 }, 375), /viewport/)
  check({ left: 221, right: 367.5, width: 146.5, height: 32, scrollWidth: 375 }, 375)
  assert.match(harness, /geometry\.scrollWidth <= geometry\.innerWidth/)
  assert.match(harness, /assert\.equal\(bytes\.readUInt32BE\(16\), result\.viewport/)
  assert.doesNotMatch(harness, /overflow-x:\s*hidden|clip:\s*\{|waitForTimeout/)
})
test('layout settling waits for real leaving state, fonts and stable frames instead of deleting tooltip DOM', async () => {
  const settle = sourceFunction('waitForMonitoringLayout', '\nasync function capture')
  let frameCount = 0, fontWaited = false, checks = 0
  const globals = ['document', 'getComputedStyle', 'requestAnimationFrame'], originals = globals.map(key => globalThis[key])
  const tooltip = { className: 'el-tooltip el-fade-in-linear-leave-active', getBoundingClientRect: () => ({ left: 229, right: 375.5, top: 1, bottom: 33 }), getAnimations: () => frameCount < 2 ? [{ playState: 'running' }] : [] }
  globalThis.document = { fonts: { ready: Promise.resolve().then(() => { fontWaited = true }) }, documentElement: { scrollWidth: 375, scrollHeight: 900 }, querySelectorAll: () => frameCount < 2 ? [tooltip] : [] }
  globalThis.getComputedStyle = () => ({ display: 'block', visibility: 'visible' })
  globalThis.requestAnimationFrame = callback => { frameCount++; if (frameCount >= 2) tooltip.className = 'el-tooltip'; callback() }
  try {
    await settle({ async waitForFunction(callback) { while (checks++ < 5) { if (await callback()) return }; throw new Error('Layout never settled') } })
    assert.ok(fontWaited); assert.ok(frameCount >= 4); assert.ok(checks >= 2)
  } finally { globals.forEach((key, index) => { globalThis[key] = originals[index] }) }
  assert.ok(harness.includes('await waitForMonitoringLayout(page)'))
  assert.ok(harness.includes('await page.mouse.move(0, 0)'))
})
