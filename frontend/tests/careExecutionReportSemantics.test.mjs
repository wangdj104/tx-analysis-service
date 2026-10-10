import test from 'node:test'
import assert from 'node:assert/strict'
import { reportLabel, defaultReportDates } from '../src/utils/careExecutionReport.js'
import { mountedReport, flush, syntheticReport } from './helpers/executionReportIntegration.mjs'

const lifecycleLabels = { ACTIVE: ['Active', '有效'], COMPLETED: ['Closed', '已关闭'], CANCELLED: ['Cancelled', '已取消'], UNKNOWN_LIFECYCLE: ['Unrecognized', '未识别'] }
for (const [language, index] of [['en', 0], ['zh-CN', 1]]) {
  test(`known OWNER provenance has the exact ${language} label`, () => {
    assert.equal(reportLabel('actorRole', 'OWNER', language), ['Record owner', '记录所有者'][index])
    assert.equal(reportLabel('actorRole', 'UNKNOWN_ROLE', language), ['Unrecognized', '未识别'][index])
  })
  test(`persisted lifecycle labels preserve the distinction from clinical outcomes in ${language}`, () => {
    for (const [code, labels] of Object.entries(lifecycleLabels)) assert.equal(reportLabel('lifecycle', code, language), labels[index])
  })
  test(`compiled panel shows OWNER receipt and period provenance with stable codes in ${language}`, async t => {
    const v = await mountedReport(t, { preview: body => {
      const report = syntheticReport(body)
      const event = { eventId: 19, eventType: 'RECEIPT_SUBMITTED', actorId: 7, actorName: 'Synthetic record owner', actorRole: 'OWNER', actorRelation: 'SELF', entryMode: 'SELF', note: '原始声明', recordedAt: '2026-10-04T12:00:00Z', evidence: [] }
      report.currentActions[0].latestReceipt = event
      report.periodEvents = [{ ...event, planId: 3, revisionId: 4, revisionNo: 2, actionId: 5, planTitle: 'Synthetic plan', planLifecycleAtGeneration: 'ACTIVE', revisionIsCurrentAtGeneration: true }]
      return { data: report }
    } })
    v.panelSetup.form.language = language
    assert.equal((await v.panel.refresh()).status, 'succeeded'); await flush()
    const label = ['Record owner', '记录所有者'][index]
    assert.equal(v.html.split(`${label} (OWNER)`).length - 1, 2)
    assert.equal(v.html.split(`${label} (OWNER) · ${['Relation: Self', '关系: 本人'][index]} (SELF) · ${['Self-reported', '本人自报'][index]}`).length - 1, 2)
    assert.ok(v.html.includes('原始声明'))
    assert.equal(v.panel.state.report.currentActions[0].latestReceipt.actorRole, 'OWNER')
    assert.equal(v.panel.state.report.currentActions[0].latestReceipt.actorRelation, 'SELF')
    assert.equal(v.panel.state.report.periodEvents[0].actorRelation, 'SELF')
  })
  test(`compiled panel localizes real lifecycle markers and keeps every code in ${language}`, async t => {
    const v = await mountedReport(t, { preview: body => {
      const report = syntheticReport(body)
      report.periodEvents = Object.keys(lifecycleLabels).map((code, i) => ({ eventId: 30 + i, eventType: code === 'COMPLETED' ? 'PLAN_CLOSED' : 'PLAN_PUBLISHED', planId: 3, revisionId: 4, revisionNo: 2, planTitle: 'Synthetic plan', planLifecycleAtGeneration: code, revisionIsCurrentAtGeneration: false, actorId: 9, actorName: 'Synthetic doctor', actorRole: 'DOCTOR', recordedAt: '2026-10-04T12:00:00Z', evidence: [] }))
      return { data: report }
    } })
    v.panelSetup.form.language = language
    assert.equal((await v.panel.refresh()).status, 'succeeded'); await flush()
    for (const [code, labels] of Object.entries(lifecycleLabels)) assert.ok(v.html.includes(`${labels[index]} (${code})`), code)
    assert.equal(v.panel.state.report.periodEvents[1].eventType, 'PLAN_CLOSED')
    assert.equal(v.panel.state.report.periodEvents[1].planLifecycleAtGeneration, 'COMPLETED')
  })
}

for (const timeZone of ['UTC', 'Asia/Shanghai', 'Japan', 'GMT', 'EST5EDT']) {
  test(`actual date helper accepts browser-supported named zone ${timeZone}`, () => {
    // The browser's real Intl must support the input; no timezone behavior is mocked.
    const now = new Date('2026-10-01T18:00:00Z')
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now).map(part => [part.type, part.value]))
    assert.equal(defaultReportDates(timeZone, now).toDate, `${parts.year}-${parts.month}-${parts.day}`)
  })
  test(`compiled panel enables generation and sends the selected named zone ${timeZone}`, async t => {
    const v = await mountedReport(t)
    Object.assign(v.panelSetup.form, { timeZone, fromDate: '2026-10-01', toDate: '2026-10-01' }); await flush()
    assert.equal(Boolean(v.panelSetup.valid), true)
    assert.equal((await v.panel.refresh()).status, 'succeeded'); await flush()
    assert.equal(v.calls.find(c => c.url === '/care-plans/reports/preview').data.timeZone, timeZone)
  })
}
for (const timeZone of ['CST', 'EST', 'PST', 'MST', 'HST', 'CET', 'EET', 'MET', 'WET', '+08:00', 'UTC+08:00', 'GMT+08:00', '-0400', 'Not/AZone', ' UTC', '', null]) {
  test(`actual helper and compiled panel reject ambiguous, offset or invalid zone ${timeZone}`, async t => {
    assert.throws(() => defaultReportDates(timeZone), RangeError)
    const v = await mountedReport(t)
    Object.assign(v.panelSetup.form, { timeZone, fromDate: '2026-10-01', toDate: '2026-10-01' }); await flush()
    assert.equal(Boolean(v.panelSetup.valid), false)
    assert.equal((await v.panel.refresh()).status, 'stale')
    assert.equal(v.calls.some(c => c.url === '/care-plans/reports/preview'), false)
  })
}

const emptyClaim = /Nothing needs attention today|今天没有需要关注的事项/
function emptyFamilyRead(scheduleState) {
  return async config => {
    if (config.url === '/patient/specialty-menu-scope') {
      if (scheduleState === 'denied') throw { code: 403 }
      if (scheduleState === 'unavailable') throw new Error('Synthetic optional scope failure')
      return { data: { patientId: config.params.patientId, allowedPaths: scheduleState === 'disabled' ? [] : ['/family-health?tab=schedule'] } }
    }
    return { data: config.url === '/family-health/target' ? {} : [] }
  }
}
for (const scheduleState of ['denied', 'unavailable', 'ready', 'disabled']) {
  test(`compiled Today empty claim requires a known optional schedule result: ${scheduleState}`, async t => {
    const v = await mountedReport(t, { family: true, initialRoute: '/family-health?tab=today', familyRead: emptyFamilyRead(scheduleState) })
    await flush()
    assert.equal(v.family.reloadState, 'ready')
    assert.equal(v.family.scheduleState, scheduleState)
    assert.equal(emptyClaim.test(v.html), ['ready', 'disabled'].includes(scheduleState))
    if (['denied', 'unavailable'].includes(scheduleState)) assert.ok(v.html.includes(v.family.scheduleStateText(scheduleState)))
    if (scheduleState === 'disabled') assert.equal(v.calls.some(c => c.url === '/family-health/dialysis-schedules'), false)
  })
}
test('compiled Today known-empty claim appears only after denied optional reads recover', async t => {
  let state = 'denied'
  const v = await mountedReport(t, { family: true, initialRoute: '/family-health?tab=today', familyRead: config => emptyFamilyRead(state)(config) })
  assert.equal(emptyClaim.test(v.html), false)
  state = 'ready'; await v.family.reload(); await flush()
  assert.equal(v.family.scheduleState, 'ready'); assert.equal(emptyClaim.test(v.html), true)
  assert.deepEqual(v.family.intakes, []); assert.deepEqual(v.family.schedules, [])
})
