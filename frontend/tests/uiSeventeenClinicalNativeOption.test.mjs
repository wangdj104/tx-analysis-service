// Static/native-control fixtures only; the actual browser matrix runs separately in CI.
import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('./uiSeventeenClinical.browser.mjs', import.meta.url), 'utf8')

function assertionFixture(edition, count, { optionDisabled = true, allowDuplicate = false } = {}) {
  const receipt = readFileSync(new URL(`${edition === 'en' ? '../' : '../../cn/frontend/'}src/components/care-plan/ReceiptDialog.vue`, import.meta.url), 'utf8')
  const form = { evidence: Array.from({ length: count }, (_, i) => ({ sourceType: 'MEASUREMENT', sourceId: 701 + i })) }
  const referenceOptions = { value: Array.from({ length: 6 }, (_, i) => ({ id: 701 + i, label: `Synthetic original record #${701 + i}` })) }
  const referenceType = { value: 'MEASUREMENT' }, selectedReferenceId = { value: null }, referenceLabels = {}
  const guard = receipt.match(/function addReference\(\)\{[^\n]+/)[0]
  const addReference = new Function('form', 'referenceOptions', 'referenceType', 'selectedReferenceId', 'referenceLabels', `${guard};return addReference`)(form, referenceOptions, referenceType, selectedReferenceId, referenceLabels)
  const rows = { get count() { return form.evidence.length }, async allTextContents() { return form.evidence.map(e => `${e.sourceType} #${e.sourceId}`) } }
  const select = { value: '', dispatchEvent(event) { assert.equal(event.type, 'change'); selectedReferenceId.value = Number(this.value) } }
  const picker = {
    locator(selector) { assert.equal(selector, 'option[value="701"]'); return { disabled: optionDisabled } },
    async evaluate(callback, id) { return callback(select, id) }
  }
  const rendered = {
    locator(selector) { assert.ok(['.evidence-row', '.evidence-row p'].includes(selector)); return rows },
    getByTestId(id) {
      assert.equal(id, 'add-existing-reference')
      return { async evaluate(callback) { return callback({ click() {
        if (allowDuplicate) form.evidence.push({ sourceType: 'MEASUREMENT', sourceId: selectedReferenceId.value })
        else addReference()
      } }) } }
    }
  }
  const expect = actual => ({
    async toHaveJSProperty(key, expected) { assert.equal(actual[key], expected, 'Native OPTION disabled property must remain true') },
    async toHaveCount(expected) { assert.equal(actual.count, expected, 'Duplicate must never alter rendered reference count') }
  })
  return { rendered, picker, expect, form }
}

function nativeAssertion(expect) {
  const code = source.match(/async function assertNativeReferenceDeduplication\(receipt, picker, id, expectedCount\) \{[\s\S]*?\n\}/)?.[0]
  assert.ok(code, 'Clinical browser harness must assert native OPTION.disabled and exercise the duplicate guard')
  return new Function('expect', 'assert', `${code};return assertNativeReferenceDeduplication`)(expect, assert)
}

test('native OPTION semantics replace the matcher that retargets to enabled SELECT', () => {
  assert.doesNotMatch(source, /expect\(picker\.locator\(`option[^\n]+\.toBeDisabled\(\)/, 'Do not apply Playwright disabled actionability matching to native OPTION')
})

for (const edition of ['en', 'cn']) {
  test(`${edition}: browser assertion preserves real receipt deduplication before the five-item cap`, async () => {
    for (const count of [1, 2, 3, 4]) {
      const fixture = assertionFixture(edition, count)
      await nativeAssertion(fixture.expect)(fixture.rendered, fixture.picker, '701', count)
      assert.equal(fixture.form.evidence.filter(e => e.sourceId === 701).length, 1)
    }
  })
  test(`${edition}: browser gate still rejects an enabled duplicate option or a duplicate-add regression`, async () => {
    const enabled = assertionFixture(edition, 1, { optionDisabled: false })
    await assert.rejects(() => nativeAssertion(enabled.expect)(enabled.rendered, enabled.picker, '701', 1), /disabled property/)
    const duplicate = assertionFixture(edition, 1, { allowDuplicate: true })
    await assert.rejects(() => nativeAssertion(duplicate.expect)(duplicate.rendered, duplicate.picker, '701', 1), /reference count/)
  })
}

test('clinical runtime serves compiled output without weakening the strict WebSocket/API gate', () => {
  assert.match(source, /server = await preview\(/, 'Production preview avoids injected Vite development WebSockets')
  assert.doesNotMatch(source, /server = await createServer\(/)
  assert.match(source, /context\.routeWebSocket\('\*\*\/\*', socket => \{ fixture\.unexpected\.push/)
  assert.match(source, /assert\.deepEqual\(fixture\.unexpected, \[\]/)
  assert.match(source, /new Set\(receiptWrite\.body\.evidence\.map/)
})
