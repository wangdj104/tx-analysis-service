// Regression checks for run 38204792928. These exercise observation/acceptance
// helpers only and are never substituted for actual browser matrix evidence.
import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { babelParse } from '@vue/compiler-sfc'
import * as measurements from './careNineMeasurements.mjs'

const source = readFileSync(new URL('./uiSeventeenClinical.browser.mjs', import.meta.url), 'utf8')
const ast = babelParse(source, { sourceType: 'module' })
const box = Object.freeze({ width: 140, height: 44, visible: true })
const control = (testId, role, background) => ({ testId, role, background, bounds: box, color: 'rgb(20, 87, 67)', borderColor: 'rgba(0, 0, 0, 0)', textDecorationLine: 'underline', disabled: false, hovered: false, text: testId, describedBy: '' })
const baseline = () => ({ surface: 'rgb(255, 255, 255)', hoverToken: '#eaf1ed', controls: [control('submit-receipt', 'primary', 'rgb(23, 107, 89)'), control('close-receipt', 'secondary', 'rgb(255, 255, 255)'), { ...control('', 'remove', 'rgba(0, 0, 0, 0)'), describedBy: 'reference-701' }], referenceControls: [{ referenceId: 'reference-701', referenceText: 'Synthetic reference', describedBy: 'reference-701', sameRow: true, bounds: box }] })

function observationHarness({ hovered = false, animation = null, disabled = false, busy = 'false' } = {}) {
  const reads = []
  const button = Object.freeze({
    matches: selector => selector === ':hover' ? hovered : selector === ':disabled' ? disabled : selector === '.remove-reference',
    getAnimations: () => animation ? [Object.freeze(animation)] : [],
    getAttribute: () => busy,
  })
  const dialog = Object.freeze({ querySelectorAll: () => [button], querySelector: () => button })
  return { dialog, reads, getComputedStyle: node => {
    assert.equal(node, button)
    return Object.freeze({ get backgroundColor() { reads.push('style-flush'); return 'rgb(23, 107, 89)' } })
  } }
}

function readiness(harness, phase) {
  assert.equal(typeof measurements.receiptStylesReady, 'function', 'The browser must use a read-only readiness probe')
  const fn = new Function('getComputedStyle', `return (${measurements.receiptStylesReady.toString()})`)(harness.getComputedStyle)
  return fn({ dialog: harness.dialog, phase })
}

test('receipt neutral readiness waits for native hover release and actual transitions to finish', () => {
  assert.equal(readiness(observationHarness({ hovered: true }), 'neutral'), false)
  for (const animation of [{ playState: 'running', pending: false }, { playState: 'finished', pending: true }]) assert.equal(readiness(observationHarness({ animation }), 'neutral'), false)
  const settled = observationHarness({ animation: { playState: 'finished', pending: false } })
  assert.equal(readiness(settled, 'neutral'), true)
  assert.ok(settled.reads.includes('style-flush'), 'Force a read of current style before observing transition state')
})

test('receipt hover and pending readiness require the actual native interaction state', () => {
  assert.equal(readiness(observationHarness(), 'remove-hover'), false)
  assert.equal(readiness(observationHarness({ hovered: true }), 'remove-hover'), true)
  assert.equal(readiness(observationHarness({ disabled: true }), 'submitting'), false)
  assert.equal(readiness(observationHarness({ busy: 'true' }), 'submitting'), false)
  assert.equal(readiness(observationHarness({ busy: 'true', disabled: true, animation: { playState: 'running' } }), 'submitting'), false)
  assert.equal(readiness(observationHarness({ busy: 'true', disabled: true }), 'submitting'), true)
})

test('hover uses a distinct lightweight theme treatment without weakening the neutral one-primary gate', () => {
  assert.equal(typeof measurements.assertReceiptHover, 'function', 'Native hover has its own acceptance gate')
  const neutral = baseline(), hovered = structuredClone(neutral)
  Object.assign(hovered.controls[2], { hovered: true, background: 'rgb(234, 241, 237)' })
  measurements.assertReceiptHierarchy(neutral)
  assert.throws(() => measurements.assertReceiptHierarchy(hovered), /Exactly one action/, 'A hover snapshot is not accepted as a neutral baseline')
  measurements.assertReceiptHover(neutral, hovered)
  for (const change of [{ background: neutral.controls[0].background }, { borderColor: neutral.controls[0].background }, { textDecorationLine: 'none' }, { describedBy: '' }, { hovered: false }, { bounds: { ...box, height: 43 } }]) {
    const bad = structuredClone(hovered); Object.assign(bad.controls[2], change)
    assert.throws(() => measurements.assertReceiptHover(neutral, bad))
  }
  const other = structuredClone(hovered); other.controls[1].background = neutral.controls[0].background
  assert.throws(() => measurements.assertReceiptHover(neutral, other), /unchanged/)
})

function loadWait() {
  const node = ast.program.body.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'waitForReceiptStyles')
  assert.ok(node, 'A bounded rendered-frame wait replaces immediate computed-style sampling')
  return new Function('receiptStylesReady', 'measureReceiptControls', source.slice(node.start, node.end) + ';return waitForReceiptStyles')(measurements.receiptStylesReady, measurements.measureReceiptControls)
}

for (const fail of [false, true]) test(`receipt readiness ${fail ? 'timeout' : 'success'} retains real observations for diagnostics`, async () => {
  const steps = [], handle = {}, diagnostics = {}, observed = { controls: [{ background: 'rgb(23, 107, 89)' }] }
  const receipt = { async elementHandle() { return handle }, async evaluate(fn) { assert.equal(fn, measurements.measureReceiptControls); steps.push('observe'); return structuredClone(observed) } }
  const page = { mouse: { async move(x, y) { assert.deepEqual([x, y], [0, 0]); steps.push('native-pointer') } }, async waitForFunction(fn, args, options) {
    assert.equal(fn, measurements.receiptStylesReady)
    assert.equal(args.dialog, handle); assert.equal(args.phase, 'neutral')
    assert.deepEqual(options, { polling: 'raf', timeout: 15000 })
    assert.deepEqual(diagnostics.neutral.before, observed, 'Before-wait evidence exists even if readiness times out')
    steps.push('wait')
    if (fail) throw new Error('Persistent transition or interaction state')
  } }
  const wait = loadWait()
  if (fail) await assert.rejects(wait(page, receipt, 'neutral', diagnostics), /Persistent transition/)
  else assert.deepEqual(await wait(page, receipt, 'neutral', diagnostics), observed)
  assert.deepEqual(diagnostics.neutral.after, observed)
  assert.deepEqual(steps, ['native-pointer', 'observe', 'wait', 'observe'])
})
