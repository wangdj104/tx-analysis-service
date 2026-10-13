// Observation-contract tests only; native visibility/persistence still needs CI.
import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('./careDraftDialogBacklog.browser.mjs', import.meta.url), 'utf8')
const code = source.slice(source.indexOf('async function requiredAcceptance('), source.indexOf('async function entryDraftAcceptance('))
function probe({ failVisibility = false } = {}) {
  const trace = [], visible = new Set(), fields = ['title', 'medicationId', 'startDate', 'confirmed']
  const finished = new Error('Persistence gate completed')
  const page = { locator(selector) {
    if (selector.startsWith('#care-entry-error-')) {
      const field = selector.slice('#care-entry-error-'.length)
      return { async waitFor(options) {
        assert.deepEqual(options, { state: 'visible', timeout: 10000 }); trace.push(`wait:${field}`)
        if (failVisibility && field === 'medicationId') throw new Error('Error never became visible')
        await Promise.resolve(); visible.add(field)
      }, async isVisible() { trace.push(`read:${field}`); return visible.has(field) } }
    }
    return { async waitFor() {}, async focus() { trace.push('focus:checkbox') } }
  } }
  const noOp = async () => {}, bindings = { assert, qaClick: noOp, dialog: noOp, checkPaper: noOp, setDate: noOp, button: noOp,
    titleControl: () => ({ async fill() { throw finished } }), requiredControl: () => ({}), associated: noOp, focused: noOp, waitOutput: noOp,
    async screenshot() {
      trace.push('capture')
      assert.ok(fields.every(field => visible.has(field)), 'capture must wait for every live required-field error to become visible')
      // Model transition restoration after an animations-disabled capture.
      // Error data/associations remain present; only rendered visibility lags.
      visible.delete('medicationId')
    } }
  const run = new Function(...Object.keys(bindings), code + ';return requiredAcceptance')(...Object.values(bindings))
  return { trace, finished, run: () => run(page, {}, {}) }
}
test('required-error capture and post-focus persistence wait on actual live visibility', async () => {
  const v = probe(); await assert.rejects(v.run(), error => error === v.finished)
  assert.deepEqual(v.trace, ['wait:title', 'wait:medicationId', 'wait:startDate', 'wait:confirmed', 'capture', 'focus:checkbox', 'wait:medicationId', 'read:medicationId'])
  assert.ok(source.includes("assert.equal(await page.locator('#care-entry-error-medicationId').isVisible(), true)"), 'the original persistence assertion must remain unchanged')
  assert.doesNotMatch(code, /waitForTimeout|setTimeout|sleep\(/)
})
test('a visibility timeout still fails acceptance before capture or any success claim', async () => {
  const v = probe({ failVisibility: true }); await assert.rejects(v.run(), /Error never became visible/)
  assert.deepEqual(v.trace, ['wait:title', 'wait:medicationId'])
})
