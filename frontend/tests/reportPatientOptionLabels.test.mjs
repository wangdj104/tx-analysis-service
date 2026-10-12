// Offline compatibility checks only. Native Playwright execution is a separate gate.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
const report = read('e2e/reportBrowser.mjs')
const races = read('e2e/careExecutionReportRaces.spec.mjs')
const names = { 9101: 'Synthetic Report Patient', 9102: 'Synthetic Empty Report Patient', 9103: 'Synthetic Limited Report Patient' }
const switcher = read('src/components/PatientSwitcher.vue')
const expression = switcher.match(/:label="(`[^"\n]+`)"/)?.[1]
assert.ok(expression, 'Read the actual PatientSwitcher option expression')
const expectedLabel = new Function('patient', `return ${expression}`)
const recordWord = switcher.includes(' · 档案 #') ? '档案' : 'Record'
function labelFunction(name) {
  const start = report.indexOf(`export function ${name}(`)
  assert.ok(start >= 0, `Shared exact ${name} must exist`)
  const end = report.indexOf('\n}', start) + 2
  return new Function('reportPatientNames', 'uiText', `${report.slice(start, end).replace('export ', '')};return ${name}`)(names, (en, cn) => recordWord === '档案' ? cn : en)
}
function evaluateCallback(marker, endMarker) {
  const start = races.indexOf(marker)
  assert.ok(start >= 0, `Native race callback must use full authorized option labels: ${marker}`)
  const end = races.indexOf(endMarker, start)
  assert.ok(end > start)
  return races.slice(start + 'await page.evaluate('.length, end)
}

test('report helper exact labels match actual PatientSwitcher name and authorized record ID', () => {
  const label = labelFunction('reportPatientOptionLabel')
  for (const [id, patientName] of Object.entries(names)) assert.equal(label(Number(id)), expectedLabel({ id: Number(id), patientName }))
  assert.notEqual(label(9101), label(9102))
  assert.match(report, /getByRole\('option',\s*\{\s*name: reportPatientOptionLabel\(id\), exact: true\s*\}\)/)
  assert.match(report, /toContainText\(reportPatientOptionLabel\(id\)\)/)
})
test('doctor report option matches the actual DoctorWorkspace name-plus-ID label exactly', () => {
  const label = labelFunction('doctorReportPatientOptionLabel')
  const doctor = read('src/views/DoctorWorkspace.vue')
  assert.ok(doctor.includes('label:`${p.name} · #${p.id}`'))
  assert.equal(label(9101), 'Synthetic Report Patient · #9101')
  assert.match(read('e2e/careExecutionReport.spec.mjs'), /name: doctorReportPatientOptionLabel\(ids.reportPatient\), exact: true/)
})
test('source report selection retains exact full identity and persisted patient-ID assertion', () => {
  const source = read('e2e/careExecutionReportSources.spec.mjs')
  assert.match(source, /name: reportPatientOptionLabel\(id\), exact: true/)
  assert.match(source, /toContainText\(reportPatientOptionLabel\(id\)\)/)
  assert.ok(source.includes("Number(localStorage.getItem('currentPatientId'))"))
  assert.ok(source.includes('.toBe(id)'))
})
test('pre-click native option lookup requires full identity, preserving click and metadata boundary', () => {
  const label = labelFunction('reportPatientOptionLabel')(9102), clicks = []
  const callback = evaluateCallback('await page.evaluate(emptyPatientLabel => {', '}, reportPatientOptionLabel(ids.emptyReportPatient))') + '}'
  const nodes = [
    { textContent: names[9102], click: () => clicks.push('bare-name') },
    { textContent: label, click: () => clicks.push(9102) },
    { textContent: label.replace('#9102', '#9199'), click: () => clicks.push('wrong-record') },
  ]
  const window = {}, document = { querySelector: () => ({ textContent: '#9101 schema 1' }), querySelectorAll: () => nodes }
  const URL = { createObjectURL: () => 'synthetic:blob' }
  new Function('window', 'document', 'URL', `return (${callback})`)(window, document, URL)(label)
  assert.equal(URL.createObjectURL({}), 'synthetic:blob'); assert.deepEqual(clicks, [9102])
  assert.deepEqual(window.__reportPatientBoundary, { metadata: '#9101 schema 1', optionFound: true })
})
test('coalesced native A→B→A clicks use exact ID labels and still assert the selected middle/final IDs', async () => {
  const label = labelFunction('reportPatientOptionLabel'), clicks = []; let selected = 9101
  const callback = evaluateCallback('await page.evaluate(async ({ patientALabel, patientBLabel }) => {', '}, { patientALabel: reportPatientOptionLabel(ids.reportPatient), patientBLabel: reportPatientOptionLabel(ids.emptyReportPatient) })') + '}'
  const options = [9101, 9102].flatMap(id => [
    { textContent: names[id], click: () => clicks.push('bare-name') },
    { textContent: label(id), click: () => { clicks.push(id); selected = id } },
  ])
  const document = { querySelectorAll: () => options }, localStorage = { getItem: () => String(selected) }
  const result = await new Function('document', 'localStorage', `return (${callback})`)(document, localStorage)({ patientALabel: label(9101), patientBLabel: label(9102) })
  assert.deepEqual(clicks, [9102, 9101]); assert.deepEqual(result, { middle: 9102, last: 9101 })
  assert.ok(races.includes('expect(accepted).toEqual({middle:ids.emptyReportPatient,last:ids.reportPatient})'))
  assert.ok(races.includes('expect(counter.count()).toBe(0)'))
})
