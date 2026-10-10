import { expect } from '@playwright/test'
import { test, showIdentity, signOut } from './carePlanSessions.mjs'
import { ids, language, appPath, login, api, grant, assertNoOverflow, assertMedicalDialogReady } from './helpers.mjs'

// Actual built Vue, Spring authorization and disposable MySQL fixtures. The only
// transport failure below is one explicitly named menu-bootstrap failure; source
// and report requests are never replaced with successful synthetic responses.
const cn = language === 'cn'
const text = (en, zh) => cn ? zh : en
const patient = 9101, otherPatient = 9102, crossPatient = 9104, missingSource = 91999999
const reportPath = `/care-plans/reports?patientId=${patient}`
const sourceKinds = [
  {
    kind: 'measurement', type: 'MEASUREMENT', module: 'MEASUREMENTS', id: 19101,
    heading: text('Linked measurement', '关联测量记录'),
    path: (patientId = patient, sourceId = 19101) => `/care-journey?tab=measurements&patientId=${patientId}&measurementId=${sourceId}`,
    apiPath: (patientId = patient, sourceId = 19101) => `/care-journey/measurements?patientId=${patientId}&measurementId=${sourceId}`,
  },
  {
    kind: 'medical', type: 'MEDICAL_RECORD', module: 'MEDICAL', id: 19102,
    heading: text('Linked medical record', '关联病历记录'),
    path: (patientId = patient, sourceId = 19102) => `/medical-record?tab=list&patientId=${patientId}&recordId=${sourceId}`,
    apiPath: (patientId = patient, sourceId = 19102) => `/medical-record/${sourceId}?patientId=${patientId}`,
  },
]
const recheckLabel = text('Recheck source', '重新检查来源')
const unavailableLabel = text('This linked record is restricted, unavailable, or its context changed.', '关联记录受限、不可用或上下文已变化。')
const medicalDialog = page => page.getByRole('dialog', { name: text('Medical record details', '医疗记录详情'), exact: true })
const focused = page => page.locator('.focused-source')
const measurementRows = page => focused(page).locator('.el-table__body tr')
const patientNames = { [patient]: 'Synthetic Report Patient', [otherPatient]: 'Synthetic Empty Report Patient' }

function sameApiPath(url, path) {
  const actual = new URL(url), expected = new URL(`/api${path}`, 'http://fixture.invalid')
  const sorted = value => [...value.searchParams].sort(([ak, av], [bk, bv]) => ak.localeCompare(bk) || av.localeCompare(bv))
  return actual.pathname === expected.pathname && JSON.stringify(sorted(actual)) === JSON.stringify(sorted(expected))
}

function readAudit(page) {
  // Store route metadata only, never response bodies or authentication material.
  const paths = []
  const listener = request => {
    const url = new URL(request.url())
    if (request.method() === 'GET' && url.pathname.startsWith('/api/')) paths.push(url.pathname + url.search)
  }
  page.on('request', listener)
  const sourceReads = () => paths.filter(path => /^\/api\/(?:care-journey\/measurements(?:[/?]|$)|medical-record(?:[/?]|$))/.test(path))
  return {
    stop: () => page.off('request', listener),
    sourceReads,
    assertNarrow() {
      expect(paths.filter(path => /^\/api\/patient(?:[/?]|$)/.test(path) && !path.startsWith('/api/patient/names')), 'A report locator must not bootstrap the full patient or specialty scope').toEqual([])
      for (const path of sourceReads()) {
        const url = new URL(path, 'http://fixture.invalid')
        if (url.pathname === '/api/care-journey/measurements') {
          expect([...url.searchParams.keys()].sort(), 'No capped measurement-list fallback').toEqual(['measurementId', 'patientId'])
        } else {
          expect(url.pathname, 'No broad medical list, item-name or trend bootstrap').toMatch(/^\/api\/medical-record\/[1-9]\d*$/)
          expect([...url.searchParams.keys()], 'Medical detail must assert its patient').toEqual(['patientId'])
        }
      }
    },
  }
}

async function selectPatient(page, id) {
  const switcher = page.locator('.patient-switcher')
  const control = switcher.getByRole('combobox')
  await expect(control).toBeEnabled()
  await control.press('ArrowDown')
  await expect(control).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('option', { name: patientNames[id], exact: true }).click()
  await page.keyboard.press('Escape')
  await expect(switcher).toContainText(patientNames[id])
  await expect.poll(() => page.evaluate(() => Number(localStorage.getItem('currentPatientId')))).toBe(id)
}

async function narrowGrant(owner, source, modules = `CARE_PLAN,${source.module}`) {
  const result = await grant(owner, 'family', patient, { accessLevel: 'READ', visibleModules: modules })
  expect(result).toMatchObject({ patient_id: patient, grantee_user_id: ids.family, access_level: 'READ', visible_modules: modules, status: 'ACTIVE' })
  const active = (await api(owner, `/care-journey/access-grants?patientId=${patient}`))
    .filter(row => Number(row.grantee_user_id) === ids.family && row.status === 'ACTIVE')
  expect(active).toHaveLength(1)
  expect(active[0].visible_modules).toBe(modules)
  expect(active[0].access_level).toBe('READ')
  return result
}

async function preview(page) {
  await expect(page.getByTestId('preview-report')).toBeEnabled()
  const response = page.waitForResponse(response => response.request().method() === 'POST' && sameApiPath(response.url(), '/care-plans/reports/preview'))
  await page.getByTestId('preview-report').click()
  const delivered = await response
  expect(delivered.status()).toBe(200)
  const json = await delivered.json()
  expect(json.code).toBe(200)
  expect(json.data).toMatchObject({ patient: { id: patient }, completeness: 'COMPLETE', questionsAvailability: 'NOT_AUTHORIZED' })
  await expect(page.getByTestId('execution-report-body')).toBeVisible()
  return json.data
}

async function observeSource(page, source, perform, { patientId = patient, sourceId = source.id, denied = false } = {}) {
  const path = source.apiPath(patientId, sourceId)
  const response = page.waitForResponse(response => response.request().method() === 'GET' && sameApiPath(response.url(), path))
  await perform()
  const delivered = await response
  // These existing source APIs use Result business errors with HTTP 200.
  expect(delivered.status()).toBe(200)
  const json = await delivered.json()
  expect(json.code).toBe(denied ? 403 : 200)
  if (denied) {
    expect(json.data).toBeNull()
    expect(Object.keys(json).sort()).toEqual(['code', 'data', 'msg', 'timestamp'])
    await assertUnavailable(page)
    return null
  }
  const record = source.kind === 'measurement' ? json.data[0] : json.data
  if (source.kind === 'measurement') expect(json.data).toHaveLength(1)
  expect(Number(record.id)).toBe(sourceId)
  expect(Number(record.patient_id ?? record.patientId)).toBe(patientId)
  await assertSourceVisible(page, source)
  return record
}

async function assertSourceVisible(page, source) {
  await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(source.path())}`)
  await expect(focused(page).getByRole('heading', { name: source.heading, exact: true })).toBeVisible()
  await expect(focused(page)).toContainText(`#${patient} · #${source.id}`)
  await expect(focused(page).getByRole('alert')).toHaveCount(0)
  if (source.kind === 'measurement') {
    await expect(measurementRows(page)).toHaveCount(1)
    await expect(measurementRows(page)).toContainText('51.91 kg')
    await expect(focused(page)).not.toContainText('60.01 kg')
    await expect(focused(page)).not.toContainText('84.19 kg')
  } else {
    await expect(medicalDialog(page)).toBeVisible()
    await expect(medicalDialog(page)).toContainText('Synthetic exact report medical source')
    await expect(medicalDialog(page)).not.toContainText('Synthetic outside medical source')
    await assertMedicalDialogReady(medicalDialog(page), 'Synthetic exact report medical source')
    const resultCards = medicalDialog(page).locator('.medical-result-cards')
    const resultTable = medicalDialog(page).locator('.medical-result-table')
    if (page.viewportSize().width <= 600) {
      await expect(resultTable).toBeHidden()
      await expect(resultCards).toBeVisible()
      await expect(resultCards.getByRole('heading', { name: 'Synthetic mobile examination', exact: true })).toBeVisible()
      for (const label of [text('Measured value', '检测值'), text('Unit', '单位'), text('Reference range', '参考范围'), text('Status', '状态')]) {
        await expect(resultCards.locator('dt').filter({ hasText: label })).toBeVisible()
      }
      await expect(resultCards.locator('dd').filter({ hasText: /^0$/ })).toBeVisible()
      await expect(resultCards).toContainText('mmol/L')
      await expect(resultCards).toContainText('0–5')
      expect(await resultCards.evaluate(root => [...root.querySelectorAll('h3, dt, dd')].every(node => {
        const box = node.getBoundingClientRect()
        return box.width > 0 && box.left >= 0 && box.right <= window.innerWidth + 1 && node.scrollWidth <= node.clientWidth + 1
      })), 'Every mobile result label and value fits without horizontal clipping').toBe(true)
      const previousSeniorMode = await page.evaluate(() => {
        const previous = document.body.classList.contains('care-senior')
        document.body.classList.add('care-senior')
        return previous
      })
      try {
        expect(await resultCards.evaluate(root => [...root.querySelectorAll('h3, dt, dd')].every(node =>
          parseFloat(getComputedStyle(node).fontSize) >= 18 && node.scrollWidth <= node.clientWidth + 1
        )), 'Saved large-text styling also enlarges mobile result labels and values').toBe(true)
      } finally {
        await page.evaluate(previous => document.body.classList.toggle('care-senior', previous), previousSeniorMode)
      }
    } else {
      await expect(resultCards).toBeHidden()
      await expect(resultTable).toBeVisible()
      await expect(resultTable).toContainText('Synthetic mobile examination')
      await expect(resultTable).toContainText('mmol/L')
    }
  }
}

async function closeMedicalDialog(page, source) {
  if (source.kind === 'medical') {
    await assertMedicalDialogReady(medicalDialog(page), 'Synthetic exact report medical source')
    await page.keyboard.press('Escape')
    await expect(medicalDialog(page)).toBeHidden()
    await expect(page.locator('.el-overlay:visible')).toHaveCount(0)
  }
}

async function assertUnavailable(page) {
  await expect(focused(page).getByRole('alert')).toHaveText(unavailableLabel)
  await expect(measurementRows(page)).toHaveCount(0)
  await expect(medicalDialog(page)).toBeHidden()
  await expect(page.getByText('Synthetic exact report medical source', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Synthetic outside medical source', { exact: true })).toHaveCount(0)
  await expect(focused(page).getByRole('button', { name: text('Open record details', '打开记录详情'), exact: true })).toHaveCount(0)
}

async function signOutAndAssertClear(page, mobile = false) {
  await signOut(page, mobile)
  await expect(focused(page)).toHaveCount(0)
  await expect(page.getByText('Synthetic exact report medical source', { exact: true })).toHaveCount(0)
}

for (const source of sourceKinds) {
  for (const mobile of [false, true]) {
    test(`report exact ${source.kind} source: ${mobile ? '390px' : 'desktop'} READ-only warm link, cold refresh and Back/Forward with stale patient`, async ({ sessions }, testInfo) => {
      test.setTimeout(120000)
      await narrowGrant(sessions.owner, source)
      await grant(sessions.owner, 'family', otherPatient, { accessLevel: 'READ', visibleModules: 'CARE_PLAN' })
      const family = await sessions.open('family', { mobile })
      await selectPatient(family, otherPatient)
      if (source.kind === 'measurement') {
        const capped = await api(family, `/care-journey/measurements?patientId=${patient}`)
        expect(capped).toHaveLength(1000)
        expect(capped.some(row => Number(row.id) === source.id), 'The linked fixture is genuinely older than the ordinary list cap').toBe(false)
      }
      const audit = readAudit(family)
      try {
        await family.goto(appPath(reportPath))
        const report = await preview(family)
        const evidence = report.currentActions.find(action => action.actionId === 19101).evidence
        expect(evidence).toHaveLength(2)
        expect(evidence.filter(item => item.restricted)).toEqual([{ restricted: true }])
        expect(evidence.find(item => !item.restricted)).toMatchObject({ restricted: false, sourceType: source.type, sourceId: source.id, detailPath: source.path() })
        const link = family.getByTestId('execution-report-body').locator(`a[href="${appPath(source.path())}"]`).first()
        await expect(link).toBeVisible()
        await link.focus()
        await expect(link).toBeFocused()
        const row = await observeSource(family, source, () => link.press('Enter'))
        if (source.kind === 'measurement') expect(Number(row.value_primary)).toBe(51.91)
        else expect(row.remark).toBe('Synthetic exact medical marker 19102')
        await assertNoOverflow(family)
        await family.screenshot({ path: testInfo.outputPath(`${language}-${source.kind}-${mobile ? '390' : 'desktop'}-exact-source.png`), fullPage: true })
        await closeMedicalDialog(family, source)
        expect(await family.evaluate(() => Number(localStorage.getItem('currentPatientId')))).toBe(otherPatient)

        // A real full document refresh executes App's auth/menu bootstrap again.
        const menuResponse = family.waitForResponse(response => response.request().method() === 'GET' && sameApiPath(response.url(), '/auth/info'))
        await observeSource(family, source, () => family.reload())
        expect((await menuResponse).status()).toBe(200)
        await closeMedicalDialog(family, source)
        await showIdentity(family, 'family', mobile)
        expect(await family.evaluate(() => Number(localStorage.getItem('currentPatientId')))).toBe(otherPatient)

        await family.goBack()
        await expect(family).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(reportPath)}`)
        await expect(focused(family)).toHaveCount(0)
        await observeSource(family, source, () => family.goForward())
        await closeMedicalDialog(family, source)
        expect(audit.sourceReads()).toHaveLength(3)
        audit.assertNarrow()
      } finally { audit.stop() }
    })
  }

  test(`report exact ${source.kind} source denies CARE_PLAN-only, missing, cross-patient and revoked IDs without a list fallback`, async ({ sessions }, testInfo) => {
    test.setTimeout(120000)
    await narrowGrant(sessions.owner, source, 'CARE_PLAN')
    const family = await sessions.open('family')
    const audit = readAudit(family)
    try {
      await family.goto(appPath(reportPath))
      const report = await preview(family)
      expect(report.currentActions.find(action => action.actionId === 19101).evidence).toEqual([{ restricted: true }, { restricted: true }])
      const body = family.getByTestId('execution-report-body')
      await expect(body.locator('a[href*="measurementId"], a[href*="recordId"]')).toHaveCount(0)
      await expect(body.getByText(cn ? '存在受限或不可用的关联记录' : 'A restricted or unavailable linked record exists', { exact: true }).first()).toBeVisible()
      await observeSource(family, source, () => family.goto(appPath(source.path())), { denied: true })

      const access = await narrowGrant(sessions.owner, source)
      await observeSource(family, source, () => focused(family).getByRole('button', { name: recheckLabel, exact: true }).click())
      await closeMedicalDialog(family, source)
      for (const [patientId, sourceId] of [[patient, missingSource], [patient, 19104], [crossPatient, source.id]]) {
        await observeSource(family, source, () => family.goto(appPath(source.path(patientId, sourceId))), { patientId, sourceId, denied: true })
      }
      await observeSource(family, source, () => family.goto(appPath(source.path())))
      await closeMedicalDialog(family, source)
      await api(sessions.owner, `/care-journey/access-grants/${access.id}`, { method: 'DELETE' })
      // Restore only CARE_PLAN: revoked source permission must remain independent.
      await narrowGrant(sessions.owner, source, 'CARE_PLAN')
      await observeSource(family, source, () => focused(family).getByRole('button', { name: recheckLabel, exact: true }).click(), { denied: true })
      expect(audit.sourceReads()).toHaveLength(7)
      audit.assertNarrow()
      await family.screenshot({ path: testInfo.outputPath(`${language}-${source.kind}-revoked-source-cleared.png`), fullPage: true })
    } finally { audit.stop() }
  })

  test(`report exact ${source.kind} locator rejects duplicate and extra keys in browser and API`, async ({ sessions }) => {
    await narrowGrant(sessions.owner, source)
    const family = await sessions.open('family')
    const sourceKey = source.kind === 'measurement' ? 'measurementId' : 'recordId'
    for (const suffix of [`&${sourceKey}=${source.id}`, `&patientId=${patient}`, '&extra=1']) {
      const audit = readAudit(family)
      try {
        await family.goto(appPath(source.path() + suffix))
        await expect(family).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath('/monitoring')}`)
        await expect(focused(family)).toHaveCount(0)
        expect(audit.sourceReads(), 'Malformed locators must not unlock any source workspace').toEqual([])
      } finally { audit.stop() }
    }
    const apiSuffixes = source.kind === 'measurement'
      ? [`&measurementId=${source.id}`, `&patientId=${patient}`, '&metricType=WEIGHT', '&extra=1']
      : [`&patientId=${patient}`, `&recordId=${source.id}`, '&extra=1']
    for (const suffix of apiSuffixes) {
      const rejected = await api(family, source.apiPath() + suffix, { allowedCodes: [400] })
      expect(rejected).toEqual({ status: 200, code: 400, data: null })
    }
  })

  test(`report exact ${source.kind} menu-refresh resilience keeps a narrow locator after one menu transport failure`, async ({ sessions }) => {
    await narrowGrant(sessions.owner, source)
    const family = await sessions.open('family')
    let failures = 0
    const failMenuOnce = async route => { failures++; await route.abort('failed') }
    await family.route('**/api/auth/info', failMenuOnce, { times: 1 })
    const audit = readAudit(family)
    try {
      await observeSource(family, source, () => family.goto(appPath(source.path())))
      await closeMedicalDialog(family, source)
      expect(failures).toBe(1)
      const retry = family.getByRole('button', { name: text('Reload feature menu', '重新加载功能菜单'), exact: true })
      await expect(retry).toBeVisible()
      const menuResponse = family.waitForResponse(response => response.request().method() === 'GET' && sameApiPath(response.url(), '/auth/info'))
      await retry.click()
      const delivered = await menuResponse
      expect(delivered.status()).toBe(200)
      expect((await delivered.json()).code).toBe(200)
      await expect(retry).toBeHidden()
      await expect(family).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(source.path())}`)
      await expect(focused(family).getByRole('alert')).toHaveCount(0)
      if (source.kind === 'measurement') await expect(measurementRows(family)).toContainText('51.91 kg')
      else {
        await focused(family).getByRole('button', { name: text('Open record details', '打开记录详情'), exact: true }).click()
        await expect(medicalDialog(family)).toContainText('Synthetic exact report medical source')
      }
      expect(audit.sourceReads()).toHaveLength(1)
      audit.assertNarrow()
    } finally {
      audit.stop()
      await family.unroute('**/api/auth/info', failMenuOnce)
    }
  })

  test(`report exact ${source.kind} 390px patient and account A→B→A clears old source until a fresh authorized read`, async ({ sessions }, testInfo) => {
    test.setTimeout(150000)
    await narrowGrant(sessions.owner, source)
    await grant(sessions.owner, 'family', otherPatient, { accessLevel: 'READ', visibleModules: 'CARE_PLAN' })
    const family = await sessions.open('family', { mobile: true })
    await selectPatient(family, patient)
    const audit = readAudit(family)
    try {
      await observeSource(family, source, () => family.goto(appPath(source.path())))
      await closeMedicalDialog(family, source)
      await selectPatient(family, otherPatient)
      await assertUnavailable(family)
      await selectPatient(family, patient)
      await assertUnavailable(family)
      await expect(family).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(source.path())}`)
      expect(audit.sourceReads()).toHaveLength(1)
      await observeSource(family, source, () => focused(family).getByRole('button', { name: recheckLabel, exact: true }).click())
      await closeMedicalDialog(family, source)
      expect(audit.sourceReads()).toHaveLength(2)
      audit.assertNarrow()
    } finally { audit.stop() }

    await signOutAndAssertClear(family, true)
    await family.goBack()
    await expect(family).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath('/login')}`)
    await expect(focused(family)).toHaveCount(0)
    await login(family, 'outsider')
    expect(await family.evaluate(() => Number(localStorage.getItem('userId')))).toBe(ids.outsider)
    const outsiderAudit = readAudit(family)
    try {
      await observeSource(family, source, () => family.goto(appPath(source.path())), { denied: true })
      outsiderAudit.assertNarrow()
    } finally { outsiderAudit.stop() }
    await signOutAndAssertClear(family, true)
    await login(family, 'family')
    expect(await family.evaluate(() => Number(localStorage.getItem('userId')))).toBe(ids.family)
    await expect(focused(family)).toHaveCount(0)
    const restoredAudit = readAudit(family)
    try {
      await observeSource(family, source, () => family.goto(appPath(source.path())))
      restoredAudit.assertNarrow()
      expect(restoredAudit.sourceReads()).toHaveLength(1)
      await assertNoOverflow(family)
      await family.screenshot({ path: testInfo.outputPath(`${language}-${source.kind}-390-account-reauthorized.png`), fullPage: true })
    } finally { restoredAudit.stop() }
  })
}
