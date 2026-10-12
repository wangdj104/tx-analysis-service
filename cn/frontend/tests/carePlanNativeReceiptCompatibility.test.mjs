// E2E source/native-control fixture compatibility only; no browser run is implied.
import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { getLocalTimeOffsets, toOffsetIso } from '../src/utils/carePlanTime.js'

const source = readFileSync(new URL('../e2e/carePlanCollaboration.spec.mjs', import.meta.url), 'utf8')
const receipt = readFileSync(new URL('../src/components/care-plan/ReceiptDialog.vue', import.meta.url), 'utf8')
const isCn = import.meta.url.includes('/cn/frontend/')
const clockLabel = receipt.match(/<label>([^<]*)<input[^>]*data-testid="receipt-execution-time"/)[1]
const inputPaths = source.match(/await (?:dialog\.getByLabel\(labels\.occurred, \{ exact: true \}\)\.fill\(occurredAt\)|fillReceiptExecutionTime\(dialog, occurredAt\))/g) || []

function nativeReceipt(timeZone) {
  const clock = { type: 'datetime-local', value: '',
    async evaluate(callback, instant) { return callback({ type: this.type }, instant) },
    async fill(value) {
      assert.match(value, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$/, 'Native datetime-local rejects an ISO UTC/offset suffix')
      this.value = value
    } }
  const offset = { value: null, async selectOption(value) {
    assert.ok(getLocalTimeOffsets(clock.value, { timeZone }).includes(Number(value)), 'Select only an actual offset option for this clock')
    this.value = value
  } }
  const dialog = {
    getByTestId(id) {
      assert.ok(receipt.includes(`data-testid="${id}"`), 'Control exists in the actual ReceiptDialog')
      return id === 'receipt-execution-time' ? clock : id === 'receipt-time-offset' ? offset : assert.fail(`Unexpected control ${id}`)
    },
    getByLabel(label) { assert.equal(label, clockLabel, 'Legacy receipt label no longer identifies the native time control'); return clock }
  }
  const expect = value => ({
    async toHaveAttribute(name, expected) { assert.equal(value[name], expected) },
    async toHaveValue(expected) { assert.equal(value.value, expected) },
    toBe(expected) { assert.equal(value, expected) }
  })
  return { dialog, expect, payload: () => toOffsetIso(clock.value, Number(offset.value), { timeZone }) }
}

for (const [pathIndex, description] of ['shared receipt submission', '390px keyboard receipt submission'].entries()) {
  test(`${description} uses the real native controls without changing the persisted UTC instant`, async () => {
    assert.equal(inputPaths.length, 2, 'Both existing UI receipt input paths remain covered')
    const helper = source.match(/async function fillReceiptExecutionTime\(dialog, occurredAt\) \{[\s\S]*?\n\}/)?.[0] || ''
    const previousZone = process.env.TZ
    try {
      for (const [timeZone, occurredAt, expected] of [
        ['Asia/Shanghai', '2026-12-31T23:59:59.987Z', '2027-01-01T07:59:59.987+08:00'],
        ['Asia/Shanghai', '2026-10-11T00:00:00.001Z', '2026-10-11T08:00:00.001+08:00'],
        ['America/New_York', '2026-11-01T05:30:00.123Z', '2026-11-01T01:30:00.123-04:00'],
        ['America/New_York', '2026-11-01T06:30:00.123Z', '2026-11-01T01:30:00.123-05:00']
      ]) {
        // This Node fixture uses the same standard Date/Intl browser-local clock semantics.
        process.env.TZ = timeZone
        const fixture = nativeReceipt(timeZone)
        const labels = { occurred: source.match(/occurred: text\('([^']+)', '([^']+)'\)/)?.[isCn ? 2 : 1] }
        const run = new Function('expect', 'toOffsetIso', 'labels', `${helper};return async (dialog,occurredAt)=>{${inputPaths[pathIndex]}}`)(fixture.expect, toOffsetIso, labels)
        await run(fixture.dialog, occurredAt)
        assert.equal(fixture.payload(), expected)
        assert.equal(Date.parse(fixture.payload()), Date.parse(occurredAt), 'Milliseconds and UTC date rollover are preserved')
      }
    } finally {
      if (previousZone == null) delete process.env.TZ; else process.env.TZ = previousZone
    }
  })
}

test('exact authorized patient options retain name and record ID in both selector contexts', () => {
  const patientNames = { 11: 'Synthetic Same Name', 12: 'Synthetic Same Name' }
  const text = (en, cn) => isCn ? cn : en
  const switcher = readFileSync(new URL('../src/components/PatientSwitcher.vue', import.meta.url), 'utf8')
  const doctor = readFileSync(new URL('../src/views/DoctorWorkspace.vue', import.meta.url), 'utf8')
  const switcherExpression = switcher.match(/:label="(`[^`]*`)"/)[1]
  const doctorExpression = doctor.match(/label:(`\$\{p\.name\}[^`]*`)/)[1]
  for (const [name, actualExpression, param] of [['patientSwitcherLabel', switcherExpression, 'patient'], ['doctorPatientLabel', doctorExpression, 'p']]) {
    const declaration = source.match(new RegExp(`const ${name} =[^\\n]+`))?.[0]
    const actual = new Function(param, `return ${actualExpression}`)
    const selectName = declaration ? new Function('patientNames', 'text', `${declaration};return ${name}`)(patientNames, text) : id => patientNames[id]
    for (const id of [11, 12]) {
      const patient = { id, name: patientNames[id], patientName: patientNames[id] }
      assert.equal(selectName(id), actual(patient), 'Exact E2E option name must match the authorized production name+ID label')
    }
    assert.notEqual(selectName(11), selectName(12), 'Same-name authorized patients remain distinguishable')
  }
  assert.doesNotMatch(source, /selectElementOption\([^\n]*, patientNames\[/, 'No exact selector may use the obsolete name-only label')
  assert.match(source, /getByRole\('option', \{ name, exact: typeof name === 'string' \}\)/, 'Keep exact option matching')
})
