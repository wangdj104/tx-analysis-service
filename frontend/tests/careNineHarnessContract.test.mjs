import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'

const care = readFileSync(new URL('./careDraftDialogBacklog.browser.mjs', import.meta.url), 'utf8')
const clinical = readFileSync(new URL('./uiSeventeenClinical.browser.mjs', import.meta.url), 'utf8')
test('density baseline SFCs are exact frozen c8756525 snapshots rather than estimated old pixel heights', () => {
  const hashes = { en: 'a9f32af9e90a781388d82e8db8891c2e6c015fa11f5e8c7164cb92db5edd74d1', cn: '76f7667b8b054ee1af9353566e50d1978e8c338f553dd962a4d77fab3ac08e4c' }
  for (const [edition, expected] of Object.entries(hashes)) {
    const source = readFileSync(new URL(`./fixtures/care-density-baseline/${edition}.vue`, import.meta.url))
    assert.equal(createHash('sha256').update(source).digest('hex'), expected)
    assert.ok(care.includes(expected), 'Browser harness also fails closed on altered baseline source')
  }
  assert.match(care, /baselinePage\.locator\('\.plan-tasks'\)\.evaluate\(measureDensity\)/)
  assert.match(care, /baselineHeights: \[baseline\.planCardHeight\], currentHeights: \[current\.planCardHeight\]/)
  assert.match(care, /baselineSourceSha256: baselineSources\[result\.edition\]\.sha256/)
  assert.match(care, /sourceFiles\.push\(\.\.\.Object\.values\(baselineSources\)/)
})
test('five-width care and receipt measurements supplement, rather than remove, the375/1365 legacy matrix', () => {
  for (const source of [care, clinical]) assert.match(source, /\[320, 375, 390, 768, 1365\]/)
  assert.match(care, /legacyWidths = \[375, 1365\]/)
  assert.match(care, /legacyWidths\.includes\(viewport\) \? itemIds : \[1\]/)
  assert.match(clinical, /legacyViewports = \[375, 1365\]/)
  assert.match(clinical, /itemIds: receiptOnly \? \[5\] : itemIds/)
  assert.doesNotMatch(care, /nineItemIds: \[\]/)
  assert.doesNotMatch(clinical, /nineItemIds: \[\]/)
  assert.match(care, /result\.nineItemIds = \[5, 6\]/)
  assert.match(clinical, /result\.nineItemIds = \[9\]/)
})
test('receipt pending evidence uses the actual submit request and preserved bounded API/auth guards', () => {
  assert.match(clinical, /fixture\.holdReceipt = true[\s\S]+getByTestId\('submit-receipt'\)\.click\(\)[\s\S]+assertSubmittingState\(enabledSubmit, submittingSubmit\)/)
  assert.match(clinical, /fixture\.releaseReceipt\(\); fixture\.holdReceipt = false/)
  assert.match(clinical, /result\.nineMeasurements\[9\] = \{ \.\.\.receiptMeasurements, enabledSubmit, submittingSubmit, disabledAddReference \}/)
  assert.match(clinical, /report\.sourceHashes\['frontend\/tests\/careNineMeasurements\.mjs'\]/)
  assert.match(clinical, /fixture\.unexpected, \[\]/)
  assert.match(clinical, /receiptWrite\.body\.occurredAt, '2026-10-10T08:30:15\.123-07:00'/)
})
