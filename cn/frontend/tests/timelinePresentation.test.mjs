import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { localizeHealthTimelineEntry } from '../src/utils/timelineText.js'

test('Chinese health timeline localizes every system-generated event type', () => {
  const cases = [
    [{ sourceType: 'CONSULTATION', title: 'Remote consultation', summary: '头痛两天' }, '远程问诊', '头痛两天'],
    [{ sourceType: 'VISIT', title: 'Visit summary', summary: '已记录复诊方案' }, '就诊小结', '已记录复诊方案'],
    [{ sourceType: 'MEASUREMENT', title: 'Blood Pressure & Glucoserecord', summary: 'Blood Pressure 130/80 mmHg; Blood Glucose 6.1 mmol/L' }, '血压与血糖记录', '血压 130/80 mmHg；血糖 6.1 mmol/L'],
    [{ sourceType: 'DIALYSIS', title: 'Dialysis Records', summary: 'Pre-dialysis Weight 61.83 kg; Post-dialysis Weight 59.75 kg' }, '透析记录', '透析前体重 61.83 kg；透析后体重 59.75 kg'],
    [{ sourceType: 'INTAKE', title: 'Taken: Aspirin', summary: '1 tablet' }, '已服用：Aspirin', '1 tablet'],
    [{ sourceType: 'MEDICATION_LOG', title: 'medicationrecord: Aspirin', summary: '1 tablet' }, '用药记录：Aspirin', '1 tablet']
  ]
  for (const [entry, title, summary] of cases) {
    const localized = localizeHealthTimelineEntry(entry)
    assert.equal(localized.title, title)
    assert.equal(localized.summary, summary)
  }
})

test('Chinese health timeline preserves patient-authored entries verbatim', () => {
  const entry = { sourceType: 'MANUAL', title: 'Remote consultation', summary: 'Blood Pressure is how I named this note' }
  assert.deepEqual(localizeHealthTimelineEntry(entry), entry)
})

test('sidebar footer uses one compact horizontal utility bar in both locales and mobile drawer', () => {
  for (const appUrl of [new URL('../src/App.vue', import.meta.url), new URL('../../../frontend/src/App.vue', import.meta.url)]) {
    const source = fs.readFileSync(appUrl, 'utf8')
    assert.match(source, /workspace-utility-panel workspace-utility-panel--compact/g)
    assert.match(source, /workspace-account workspace-utility-identity/g)
    assert.match(source, /workspace-guide workspace-utility-guide/g)
    assert.doesNotMatch(source, /workspace-guide workspace-utility-action/)
  }
})
