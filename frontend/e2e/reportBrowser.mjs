import { reportPhase } from './reportOutcomePhase.mjs'
import { expect } from '@playwright/test'
import { ids, appPath, api, assignNurse, grant, language } from './helpers.mjs'

export const reportPatientNames = Object.freeze({ [ids.reportPatient]: 'Synthetic Report Patient', [ids.emptyReportPatient]: 'Synthetic Empty Report Patient', [ids.limitedReportPatient]: 'Synthetic Limited Report Patient' })
export const reportPath = (patientId = ids.reportPatient, planId) => `/care-plans/reports?patientId=${patientId}${planId ? `&planId=${planId}` : ''}`
export const panel = page => page.locator('.execution-report')
export const reportBody = page => page.getByTestId('execution-report-body')
export const formats = ['html', 'pdf', 'actions_csv', 'events_csv']
export const originalPhrase = 'Saved successfully'
export const originalAttack = 'Synthetic 护理原文 <script>synthetic-report</script>'
export const questionSentinel = 'Synthetic question outside activity period'
export const formulaCell = "'\t=SUM(1,2)\r\nSynthetic \"quoted\", text"
export const uiText = (en, zh) => language === 'cn' ? zh : en

export async function selectPatient(page, id) {
  const control = page.locator('.patient-switcher').getByRole('combobox')
  await expect(control).toBeEnabled()
  await control.press('ArrowDown')
  await page.getByRole('option', { name: reportPatientNames[id], exact: true }).click()
  await page.keyboard.press('Escape')
  await expect(page.locator('.patient-switcher')).toContainText(reportPatientNames[id])
}
export async function setReportOptions(page, language = 'en', timeZone = 'UTC') {
  await expect(panel(page)).toBeVisible()
  await panel(page).locator('input[list="report-timezones"]').fill(timeZone)
  await panel(page).locator('select').selectOption(language)
  // Reset uses the real IANA calendar implementation and fixes both explicit dates.
  await panel(page).locator('form > button').first().click()
  await expect(page.getByTestId('preview-report')).toBeEnabled()
}
export async function openReport(page, { patientId = ids.reportPatient, planId, language = 'en' } = {}) {
  await page.goto(appPath(reportPath(patientId, planId)))
  await setReportOptions(page, language)
}
export function reportResponse(page, endpoint = 'preview', body = {}) {
  return page.waitForResponse(response => {
    const request = response.request()
    if (request.method() !== 'POST' || new URL(response.url()).pathname !== `/api/care-plans/reports/${endpoint}`) return false
    const actual = request.postDataJSON()
    return Object.entries(body).every(([key, value]) => actual[key] === value)
  })
}
export async function preview(page) {
  await reportPhase('preview')
  const responsePromise = reportResponse(page)
  await page.getByTestId('preview-report').focus()
  await expect(page.getByTestId('preview-report')).toBeFocused()
  await page.getByTestId('preview-report').press('Enter')
  const response = await responsePromise
  expect(response.status()).toBe(200)
  const result = await response.json()
  expect(result.code).toBe(200)
  await expect(reportBody(page)).toBeVisible()
  expect(result.data.completeness).toBe('COMPLETE')
  return result.data
}
export async function prepareReadRole(sessions, role, { mobile = false } = {}) {
  if (role === 'family') await grant(sessions.owner, role, ids.reportPatient, { accessLevel: 'READ' })
  if (role === 'nurse') {
    await assignNurse(sessions.admin, ids.reportPatient)
    await grant(sessions.owner, role, ids.reportPatient, { accessLevel: 'READ' })
  }
  return role === 'personal' && !mobile ? sessions.owner : sessions.open(role, { mobile })
}
export async function revokeReportGrants(owner, role = 'family') {
  for (const row of await api(owner, `/care-journey/access-grants?patientId=${ids.reportPatient}`)) {
    if (Number(row.grantee_user_id) === ids[role] && row.status === 'ACTIVE') await api(owner, `/care-journey/access-grants/${row.id}`, { method: 'DELETE' })
  }
}
export async function readReportInput(page, extra = {}) {
  const values = await panel(page).evaluate(element => ({
    fromDate: element.querySelectorAll('input[type=date]')[0].value,
    toDate: element.querySelectorAll('input[type=date]')[1].value,
    timeZone: element.querySelector('input[list]').value,
    language: element.querySelector('select').value,
  }))
  return { patientId: ids.reportPatient, ...values, ...extra }
}
export async function postReportResponse(page, body, endpoint = 'preview') {
  const response = reportResponse(page, endpoint, body)
  await page.evaluate(async ({ body, endpoint }) => {
    const response = await fetch(`/api/care-plans/reports/${endpoint}`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${localStorage.getItem('token')}` }, body: JSON.stringify(body) })
    // Finish delivery in the page, without returning/logging body or credentials.
    await response.arrayBuffer()
  }, { body, endpoint })
  return response
}
export async function assertClearedReport(page) {
  await expect(reportBody(page)).toHaveCount(0)
  await expect(page.getByTestId('last-export')).toHaveCount(0)
  await expect(page.getByText(originalAttack, { exact: true })).toHaveCount(0)
}
export function downloadCounter(page) {
  let count = 0
  const listener = () => count++
  page.on('download', listener)
  return { count: () => count, dispose: () => page.off('download', listener) }
}
export async function screenshotReport(page, testInfo, label) {
  await page.screenshot({ path: testInfo.outputPath(`${language}-report-${label}.png`), fullPage: true })
}

// Timing only: the real Spring response is obtained and forwarded unchanged.
// Caller must release in finally, including when navigation aborts the old fetch.
export async function holdRealReportResponse(page, endpoint = 'preview', predicate = () => true) {
  let release, reached, settled, error, used = false, disposed = false
  const gate = new Promise(resolve => { release = resolve })
  const captured = new Promise(resolve => { reached = resolve })
  const complete = new Promise(resolve => { settled = resolve })
  const pattern = `**/api/care-plans/reports/${endpoint}`
  const handler = async route => {
    if (used || route.request().method() !== 'POST' || !predicate(route.request().postDataJSON())) return route.continue()
    used = true
    try {
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      reached()
      await gate
      await route.fulfill({ response })
    } catch (failure) { error = failure; reached() } finally { settled() }
  }
  await page.route(pattern, handler)
  return { reached: captured, release, async dispose() { if (disposed) return; disposed = true; release(); if (used) await complete; await page.unroute(pattern, handler); if (error) throw error } }
}

export async function observeDownloadMetadata(page) {
  await page.evaluate(() => {
    const native = HTMLAnchorElement.prototype.click
    window.__reportClickObservation = null
    HTMLAnchorElement.prototype.click = function (...args) {
      if (this.download.startsWith('care-execution-report-')) {
        const node = document.querySelector('[data-testid="last-export"]')
        window.__reportClickObservation = { text: (node?.textContent || '').slice(0,8193), paragraphs: [...(node?.querySelectorAll('p') || [])].slice(0,9).map(value => value.textContent.slice(0,1025)), connected: !!node?.isConnected, fileName: this.download }
      }
      return native.apply(this, args)
    }
    window.__restoreReportClick = () => { HTMLAnchorElement.prototype.click = native; delete window.__restoreReportClick; delete window.__reportClickObservation }
  })
  return () => page.evaluate(() => window.__restoreReportClick?.())
}
