import { reportPhase } from './reportOutcomePhase.mjs'
import { expect } from '@playwright/test'
import { test } from './carePlanSessions.mjs'
import { ids, appPath, api, grant, assignNurse, assertNoOverflow } from './helpers.mjs'
import { inspectReportDownload, assertReportDeniedResponse } from './reportDownloads.mjs'
import { panel, reportBody, formats, originalPhrase, originalAttack, questionSentinel, uiText, selectPatient, setReportOptions, openReport, preview, prepareReadRole, reportResponse, readReportInput, postReportResponse, assertClearedReport, downloadCounter, screenshotReport, observeDownloadMetadata, revokeReportGrants } from './reportBrowser.mjs'

import { fixtureReportExpectation, assertDownloadClickMetadata } from '../../../scripts/care-report-fixture-oracle.mjs'

async function uiDownload(page, testInfo, format, outputLanguage, expected, scenario) {
  const plan = new URL(page.url()).searchParams.get('planId')
  const selected = await readReportInput(page, { patientId: expected.patientId, planId: plan == null ? null : Number(plan) })
  expected = { ...expected, ...selected }
  const restore = await observeDownloadMetadata(page)
  try {
    await reportPhase('download-click')
    const response = reportResponse(page, 'export', { format, language: outputLanguage })
    const downloaded = page.waitForEvent('download')
    const button = page.getByTestId(`download-${format}`)
    await button.focus(); await expect(button).toBeFocused(); await button.press('Enter')
    const result = await inspectReportDownload({ download: await downloaded, response: await response, format, language: outputLanguage, expected, testInfo, scenario })
    await reportPhase('pre-click-metadata',async()=>{
      const observation = await page.evaluate(() => window.__reportClickObservation)
      assertDownloadClickMetadata(observation, { request: selected, format, filename: result.filename, rowCount: result.csvRowCount })
    })
    await expect(button).toBeEnabled()
    return result
  } finally { await restore() }
}

for (const role of ['personal', 'family', 'doctor', 'nurse']) for (const mobile of [false, true]) {
  test(`real report: ${role} ${mobile ? '390px' : 'desktop'} uses both languages and all four download buttons`, async ({ sessions }, testInfo) => {
    test.setTimeout(240000)
    const page = await prepareReadRole(sessions, role, { mobile })
    // The first entry is an actual role-appropriate plan/task link, never a test-only UI.
    if (role === 'nurse') {
      await page.goto(appPath('/nurse-workspace'))
      await page.getByTestId(`plan-execution-report-${ids.reportPlan}`).click()
    } else if (role === 'doctor') {
      await page.goto(appPath('/doctor-workspace'))
      await page.getByRole('tab', { name: uiText('Care plans','照护计划'), exact: true }).click()
      const select = page.locator('.doctor-tabs .patient-filter:visible').getByRole('combobox')
      await select.press('ArrowDown')
      await page.getByRole('option', { name: 'Synthetic Report Patient', exact: true }).click()
      await page.keyboard.press('Escape')
      await page.getByTestId('doctor-execution-report').click()
    } else {
      await page.goto(appPath('/care'))
      await selectPatient(page, ids.reportPatient)
      await page.getByTestId('patient-execution-report').click()
    }
    // Plan-level nursing entry remains explicitly single-plan; use the real App
    // patient selection to move to all plans without expanding clinical menus.
    if (role === 'nurse') await openReport(page)
    const unexpectedLegacy = []
    const listener = request => { if (/\/api\/(family-health\/visit-summary|health-report|data-export)/.test(new URL(request.url()).pathname)) unexpectedLegacy.push('legacy-read') }
    page.on('request', listener)
    try {
      for (const outputLanguage of ['en', 'zh-CN']) {
        await setReportOptions(page, outputLanguage)
        const report = await preview(page)
        expect(report.patient.id).toBe(ids.reportPatient)
        expect(report.reportSchemaVersion).toBe(1)
        expect(report.metadata).toMatchObject({ language: outputLanguage, timeZone: 'UTC' })
        expect(report.scope).toEqual({ planId: null, label: 'ALL_PLANS' })
        expect(report.currentSummary).toMatchObject({ total: 4, open: 1, needsHelp: 1, submitted: 1, confirmed: 1, overdue: 2, needsSupplement: 1 })
        expect(new Set(report.currentActions.map(row => row.actionId)).size).toBe(4)
        expect(report.periodEvents.every(row => Date.parse(row.recordedAt) >= Date.parse(report.metadata.rangeStartAt) && Date.parse(row.recordedAt) < Date.parse(report.metadata.rangeEndExclusiveAt))).toBe(true)
        expect(report.periodEvents.some(row => row.actionId === null)).toBe(false) // publication is 50 days old
        expect(report.activitySummary).toMatchObject({ eventCount: 7, distinctActionCount: 3 })
        expect(report.currentAttention.map(row => row.actionId)).toEqual([19101,19102,19103])
        expect(report.currentActions.find(row => row.actionId === 19103).latestReceipt).toMatchObject({ actorId: ids.family, actorRole: 'FAMILY', entryMode: 'ASSISTED' })
        expect(report.currentActions.find(row => row.actionId === 19104).latestReceipt).toMatchObject({ actorId: ids.nurse, actorRole: 'NURSE', entryMode: 'ASSISTED' })
        expect(report.currentActions.find(row => row.actionId === 19104).latestReview).toMatchObject({ actorId: ids.doctor, actorRole: 'DOCTOR', eventType: 'RECEIPT_CONFIRMED' })
        expect(report.currentActions.find(row => row.actionId === 19102).latestFollowUp).toMatchObject({ actorId: ids.nurse, actorRole: 'NURSE', followUpKind: 'CONTACTED' })
        expect(report.currentActions[0].instructions).toBe(originalPhrase)
        await expect(reportBody(page)).toContainText(originalPhrase)
        await expect(reportBody(page)).toContainText(originalAttack)
        await expect(reportBody(page).locator('script')).toHaveCount(0)
        await expect(reportBody(page)).not.toContainText('保存成功')
        const waiting = report.currentActions.find(row => row.actionId === 19103)
        expect(waiting.status).toBe('SUBMITTED'); expect(waiting.overdue).toBe(false)
        expect(waiting.reviewWaitingSince).toBe(waiting.latestReceipt.recordedAt)
        expect(Date.parse(waiting.reviewWaitingSince)).toBeLessThan(Date.parse(waiting.dueAt))
        expect(Date.parse(waiting.dueAt)).toBeLessThan(Date.parse(report.metadata.currentAsOf))
        await expect(reportBody(page)).toContainText('Synthetic old difficulty stays visible')
        await expect(reportBody(page)).toContainText('Synthetic old administrative contact')
        expect(report.periodEvents.some(row => row.note === 'Synthetic old difficulty stays visible')).toBe(false)
        const narrow = ['family','nurse'].includes(role)
        expect(report.questionsAvailability).toBe(narrow ? 'NOT_AUTHORIZED' : 'AVAILABLE')
        expect(report.questions.length).toBe(narrow ? 0 : 4)
        if (narrow) {
          expect(report.currentActions[0].evidence).toEqual([{ restricted: true }, { restricted: true }])
          await expect(reportBody(page)).not.toContainText(questionSentinel)
          await expect(reportBody(page).locator('a[href*="measurementId="],a[href*="recordId="]')).toHaveCount(0)
        } else {
          await expect(reportBody(page)).toContainText(questionSentinel)
          expect(report.questions.map(row=>row.status)).toEqual(['OPEN','ANSWERED','CANCELLED','LEGACY_UNKNOWN'])
          expect(report.questions.every(row=>row.timeBasis==='LEGACY_UNZONED')).toBe(true)
        }
        const jump = page.getByTestId('jump-action-19103')
        await jump.focus(); await jump.press('Enter')
        await expect(page.locator('#report-action-19103')).toBeFocused()
        await assertNoOverflow(page)
        for (const control of await panel(page).locator('form button,form input,form select').all()) {
          const box = await control.boundingBox(); expect(box.height).toBeGreaterThanOrEqual(44)
        }
        await screenshotReport(page, testInfo, `${role}-${mobile ? '390' : 'desktop'}-${outputLanguage}-preview`)
        for (const format of formats) {
          const expected = fixtureReportExpectation({ format, language: outputLanguage, narrow, patientId: ids.reportPatient })
          await uiDownload(page, testInfo, format, outputLanguage, expected, `${role === 'personal' ? 'owner' : role}-${mobile ? 'mobile' : 'desktop'}`)
        }
      }
      expect(unexpectedLegacy).toEqual([])
    } finally { page.off('request', listener) }
  })
}

test('real report: empty and independently limited CSVs disclose zero-row metadata before the click in both languages', async ({ sessions }, testInfo) => {
  test.setTimeout(150000)
  const page = sessions.owner
  for (const limited of [false, true]) for (const outputLanguage of ['en','zh-CN']) {
    const patientId = limited ? ids.limitedReportPatient : ids.emptyReportPatient
    await openReport(page, { patientId, language: outputLanguage })
    if (limited) {
      const response = reportResponse(page)
      await reportPhase('preview')
      await page.getByTestId('preview-report').click()
      await assertReportDeniedResponse(await response, { status: 422, errorCode: 'REPORT_LIMIT_EXCEEDED', limitKind: 'QUESTIONS', limit: 200 })
      await expect(panel(page).getByRole('alert')).toContainText('QUESTIONS')
      await expect(reportBody(page)).toHaveCount(0)
    } else {
      const data = await preview(page)
      expect(data.currentSummary.total).toBe(0); expect(data.activitySummary.eventCount).toBe(0)
      await expect(reportBody(page)).toContainText(outputLanguage === 'en' ? 'No current active-plan actions' : '无当前有效计划事项')
    }
    for (const format of ['actions_csv','events_csv']) {
      await expect(page.getByTestId(`download-${format}`)).toBeEnabled()
      await uiDownload(page, testInfo, format, outputLanguage, fixtureReportExpectation({ format, language: outputLanguage, patientId }), `${limited ? 'limited' : 'empty'}-${format === 'actions_csv' ? 'actions' : 'events'}`)
    }
    await assertNoOverflow(page)
    await screenshotReport(page, testInfo, `${limited ? 'limited' : 'empty'}-${outputLanguage}`)
  }
})

test('real report: JSON and every export reject admin, outsider, cross-patient and revoked grants without clinical bytes', async ({ sessions }, testInfo) => {
  test.setTimeout(150000)
  const owner = sessions.owner, admin = sessions.admin, outsider = await sessions.open('outsider')
  await openReport(owner)
  const body = await readReportInput(owner)
  for (const [actor, request] of [[admin, body], [outsider, body], [owner, { ...body, patientId: ids.outsideReportPatient }], [owner, { ...body, patientId: ids.emptyReportPatient, planId: ids.reportPlan }]]) {
    await assertReportDeniedResponse(await postReportResponse(actor, request))
    for (const format of formats) await assertReportDeniedResponse(await postReportResponse(actor, { ...request, format }, 'export'))
  }
  for (const page of [admin, outsider]) {
    await openReport(page)
    const counter = downloadCounter(page)
    try {
      const response = reportResponse(page)
      await reportPhase('preview')
      await page.getByTestId('preview-report').click()
      await assertReportDeniedResponse(await response)
      for (const format of formats) {
        const denied = reportResponse(page, 'export', { format })
        await page.getByTestId(`download-${format}`).click()
        await assertReportDeniedResponse(await denied)
        await expect(panel(page).getByRole('alert')).toBeVisible()
        await assertClearedReport(page)
      }
      expect(counter.count()).toBe(0)
    } finally { counter.dispose() }
  }
  for (const role of ['family','nurse']) {
    const page = await prepareReadRole(sessions, role)
    await openReport(page)
    await preview(page)
    const downloads = downloadCounter(page)
    try {
      if (role === 'family') await revokeReportGrants(owner)
      else for (const assignment of await api(admin, `/care-nurse-assignments?patientId=${ids.reportPatient}`)) {
        if (assignment.nurseUserId === ids.nurse && assignment.status === 'ACTIVE') await api(admin, `/care-nurse-assignments/${assignment.id}/revoke`, { method: 'POST' })
      }
      for (const format of formats) {
        await reportPhase('download-click')
    const response = reportResponse(page, 'export', { format })
        await page.getByTestId(`download-${format}`).click()
        await assertReportDeniedResponse(await response)
        await expect(panel(page).getByRole('alert')).toBeVisible()
        await assertClearedReport(page)
      }
      expect(downloads.count()).toBe(0)
      await screenshotReport(page, testInfo, `${role}-real-revocation-denied`)
    } finally { downloads.dispose() }
  }
})

test('real report: optional module and question revocation is reflected by the next freshly authorized report', async ({ sessions }, testInfo) => {
  const owner = sessions.owner
  await grant(owner, 'family', ids.reportPatient, { accessLevel: 'READ', visibleModules: 'CARE_PLAN' })
  await grant(owner, 'family', ids.reportPatient, { accessLevel: 'READ', granteeRole: 'GUARDIAN', visibleModules: '' })
  const family = await sessions.open('family')
  await openReport(family)
  const full = await preview(family)
  expect(full.questionsAvailability).toBe('AVAILABLE')
  expect(full.currentActions[0].evidence.every(row => row.restricted === false)).toBe(true)
  const optional = (await api(owner, `/care-journey/access-grants?patientId=${ids.reportPatient}`)).find(row => Number(row.grantee_user_id) === ids.family && row.grantee_role === 'GUARDIAN' && row.status === 'ACTIVE')
  await api(owner, `/care-journey/access-grants/${optional.id}`, { method: 'DELETE' })
  const narrow = await preview(family)
  expect(narrow.questionsAvailability).toBe('NOT_AUTHORIZED')
  expect(narrow.questions).toEqual([])
  expect(narrow.currentActions[0].evidence).toEqual([{ restricted: true }, { restricted: true }])
  await expect(reportBody(family)).not.toContainText(questionSentinel)
  await uiDownload(family, testInfo, 'html', 'en', fixtureReportExpectation({ format: 'html', language: 'en', patientId: ids.reportPatient, narrow: true }))
})
