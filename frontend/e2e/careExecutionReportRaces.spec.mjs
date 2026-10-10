import { expect } from '@playwright/test'
import { test, signOut } from './carePlanSessions.mjs'
import { ids, appPath, login } from './helpers.mjs'
import { openReport, reportPath, panel, preview, reportBody, selectPatient, reportResponse, assertClearedReport, downloadCounter, screenshotReport, holdRealReportResponse } from './reportBrowser.mjs'

function safeConsole(page) {
  const violations = []
  const listener = message => {
    if (/Authorization|Bearer\s|XMLHttpRequest|\bconfig\s*:|synthetic-report|Synthetic original question|care-plan-e2e-/i.test(message.text())) violations.push(message.type())
  }
  page.on('console', listener)
  return () => { page.off('console', listener); expect(violations, 'Console contains no report body, auth data or request configuration').toEqual([]) }
}
async function waitForIdle(page) { if (await panel(page).count()) await expect(panel(page)).toHaveAttribute('aria-busy','false') }
async function holdCsvScan(page) {
  await page.evaluate(() => {
    const native = Blob.prototype.stream
    let release
    const gate = new Promise(resolve => { release = resolve })
    const control = { reached: false, release, restore: () => { release(); Blob.prototype.stream = native; delete window.__reportScanGate } }
    let held = false
    Blob.prototype.stream = function (...args) {
      const stream = native.apply(this,args)
      if (held || !/csv/i.test(this.type)) return stream
      held = true
      const getReader = stream.getReader.bind(stream)
      stream.getReader = function (...args) {
        const reader = getReader(...args), read = reader.read.bind(reader)
        let first = true
        reader.read = async (...args) => {
          const result = await read(...args) // Actual Blob bytes, unchanged.
          if (first) { first = false; control.reached = true; await gate }
          return result
        }
        return reader
      }
      return stream
    }
    window.__reportScanGate = control
  })
  return {
    reached: () => expect.poll(() => page.evaluate(() => !!window.__reportScanGate?.reached)).toBe(true),
    release: () => page.evaluate(() => window.__reportScanGate?.release()),
    dispose: () => page.evaluate(() => window.__reportScanGate?.restore()),
  }
}

// The debugger only pauses a wrapper immediately before the native Blob URL
// creation. Bytes, response, metadata, auth checks and anchor click stay real.
async function holdPreClick(page) {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Debugger.enable')
  let frame, disposed = false, resolvePause
  const paused = new Promise(resolve => { resolvePause = resolve })
  const onPause = event => { frame = event.callFrames[0].callFrameId; resolvePause(event) }
  cdp.on('Debugger.paused', onPause)
  await page.evaluate(() => {
    const native = URL.createObjectURL
    URL.createObjectURL = function (blob) { debugger; return native.call(URL,blob) }
    window.__restoreReportUrl = () => { URL.createObjectURL = native; delete window.__restoreReportUrl }
  })
  return {
    async reached() {
      const event = await paused
      frame = event.callFrames[0].callFrameId
      const result = await cdp.send('Debugger.evaluateOnCallFrame', { callFrameId: frame, expression: `JSON.stringify({metadata:document.querySelector('[data-testid="last-export"]')?.textContent||'',links:document.querySelectorAll('a[download^="care-execution-report-"]').length})`, returnByValue: true })
      expect(result.exceptionDetails).toBeUndefined()
      const metadata = JSON.parse(result.result.value)
      expect(metadata.metadata).toContain(`#${ids.reportPatient}`)
      expect(metadata.metadata).toContain('UTC')
      expect(metadata.metadata).toMatch(/schema.*1/)
      expect(metadata.links).toBe(0)
    },
    async release() { if (frame) { frame = null; await cdp.send('Debugger.resume') } },
    async dispose() {
      if (disposed) return
      disposed = true
      if (frame) { frame = null; await cdp.send('Debugger.resume') }
      await page.evaluate(() => window.__restoreReportUrl?.())
      cdp.off('Debugger.paused', onPause)
      await cdp.send('Debugger.disable'); await cdp.detach()
    },
  }
}

test('real report races: App patient A→B→A discards late preview and late CSV; repeat clicks stay single-flight', async ({ sessions }, testInfo) => {
  test.setTimeout(120000)
  const page = sessions.owner, counter = downloadCounter(page), checkConsole = safeConsole(page)
  try {
    await openReport(page); await selectPatient(page, ids.reportPatient)
    for (const endpoint of ['preview','export']) {
      await openReport(page)
      const hold = await holdRealReportResponse(page, endpoint)
      let requests = 0
      const count = request => { if (request.method() === 'POST' && new URL(request.url()).pathname === `/api/care-plans/reports/${endpoint}`) requests++ }
      page.on('request', count)
      try {
        const button = page.getByTestId(endpoint === 'preview' ? 'preview-report' : 'download-actions_csv')
        await button.click(); await hold.reached
        await expect(button).toBeDisabled()
        await button.evaluate(element => { element.click(); element.click() })
        expect(requests).toBe(1)
        await selectPatient(page, ids.emptyReportPatient)
        await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(reportPath(ids.emptyReportPatient))}`)
        await selectPatient(page, ids.reportPatient)
        await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(reportPath())}`)
        hold.release(); await hold.dispose(); await waitForIdle(page)
        await assertClearedReport(page)
        expect(counter.count()).toBe(0)
      } finally { page.off('request',count); await hold.dispose() }
    }
    const fresh = await preview(page); expect(fresh.patient.id).toBe(ids.reportPatient)
    await screenshotReport(page,testInfo,'patient-a-b-a-fresh-only')
  } finally { counter.dispose(); checkConsole() }
})

test('real report races: dates timezone language plan scope and unmount discard unchanged real late responses', async ({ sessions }, testInfo) => {
  test.setTimeout(150000)
  const page = sessions.owner, counter = downloadCounter(page)
  const changes = [
    ['date', async () => { const input = panel(page).locator('input[type=date]').first(); const date = new Date(`${await input.inputValue()}T12:00:00Z`); date.setUTCDate(date.getUTCDate()+1); await input.fill(date.toISOString().slice(0,10)) }],
    ['timezone', () => panel(page).locator('input[list]').fill('America/New_York')],
    ['language', () => panel(page).locator('select').selectOption('zh-CN')],
    ['plan', () => page.goto(appPath(reportPath(ids.reportPatient,ids.reportPlan)))],
    ['unmount', () => page.goto(appPath('/care'))],
  ]
  try {
    for (const endpoint of ['preview','export']) for (const [label,change] of changes) {
      await openReport(page)
      const hold = await holdRealReportResponse(page,endpoint)
      try {
        await page.getByTestId(endpoint === 'preview' ? 'preview-report' : 'download-events_csv').click()
        await hold.reached; await change(); hold.release(); await hold.dispose()
        await waitForIdle(page); await assertClearedReport(page)
        expect(counter.count(),`No stale ${endpoint} file after ${label}`).toBe(0)
      } finally { await hold.dispose() }
    }
    await screenshotReport(page,testInfo,'unmounted-late-responses-cleared')
  } finally { counter.dispose() }
})

test('real report history: exact plan scope survives Back and Forward without resurrecting preview or metadata', async ({ sessions }, testInfo) => {
  const page = sessions.owner
  await openReport(page); await preview(page)
  await reportBody(page).getByRole('link', { name: 'Open authorized plan details', exact: true }).first().click()
  await page.getByTestId('detail-execution-report').click()
  await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(reportPath(ids.reportPatient,ids.reportPlan))}`)
  const scoped = await preview(page)
  expect(scoped.scope).toEqual({ planId: ids.reportPlan, label: 'SINGLE_PLAN' })
  expect(scoped.questionsAvailability).toBe('NOT_INCLUDED_IN_PLAN_SCOPE')
  await page.goBack(); await expect(page.getByTestId('detail-execution-report')).toBeVisible()
  await page.goBack(); await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(reportPath())}`)
  await assertClearedReport(page)
  await page.goForward(); await expect(page.getByTestId('detail-execution-report')).toBeVisible()
  await page.goForward(); await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(reportPath(ids.reportPatient,ids.reportPlan))}`)
  await assertClearedReport(page)
  await screenshotReport(page,testInfo,'history-scope-without-stale-dom')
})

test('real report CSV scanning: accepted patient A→B→A cancels after HTTP delivery before metadata or file', async ({ sessions }, testInfo) => {
  const page = sessions.owner, counter = downloadCounter(page)
  await openReport(page); await selectPatient(page,ids.reportPatient)
  const scan = await holdCsvScan(page)
  try {
    const delivered = reportResponse(page,'export',{format:'actions_csv'})
    await page.getByTestId('download-actions_csv').click()
    expect((await delivered).status()).toBe(200)
    await scan.reached()
    await expect(page.getByTestId('last-export')).toHaveCount(0)
    await selectPatient(page,ids.emptyReportPatient); await selectPatient(page,ids.reportPatient)
    await scan.release(); await waitForIdle(page)
    await assertClearedReport(page); expect(counter.count()).toBe(0)
    await screenshotReport(page,testInfo,'csv-scan-patient-change')
  } finally { await scan.dispose(); counter.dispose() }
})

for (const boundary of ['response','csv-scan','pre-click']) for (const replacement of ['personal','outsider']) {
  test(`real report session: ${boundary} rejects same-context logout/login as ${replacement}`, async ({ sessions }, testInfo) => {
    test.setTimeout(120000)
    const page = sessions.owner, counter = downloadCounter(page), checkConsole = safeConsole(page)
    await openReport(page)
    // Open a genuine second tab before a debugger pause, never copy a token or
    // inject an invented session. Login uses exactly the shared UI fixture.
    const sibling = await sessions.sibling(page,`auth-${boundary}-${replacement}`)
    await sibling.goto(appPath('/monitoring'))
    let hold
    try {
      if (boundary === 'response') hold = await holdRealReportResponse(page,'export')
      else if (boundary === 'csv-scan') hold = await holdCsvScan(page)
      else hold = await holdPreClick(page)
      await page.getByTestId('download-actions_csv').click()
      if (boundary === 'response') await hold.reached
      else await hold.reached()
      await signOut(sibling); await login(sibling,replacement)
      await hold.release(); await hold.dispose(); await waitForIdle(page)
      await assertClearedReport(page)
      expect(counter.count()).toBe(0)
      await screenshotReport(page,testInfo,`${boundary}-${replacement}-session-replaced`)
    } finally { if(hold)await hold.dispose(); counter.dispose(); checkConsole() }
  })
}

test('real report session: same-tab logout/login and Back cannot restore prior clinical DOM', async ({ sessions }, testInfo) => {
  const page = sessions.owner
  await openReport(page); await preview(page)
  await signOut(page)
  await page.goBack(); await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath('/login')}`)
  await assertClearedReport(page)
  await login(page,'outsider')
  await page.goto(appPath(reportPath()))
  await expect(page.getByTestId('preview-report')).toBeVisible()
  const response=reportResponse(page)
  await page.getByTestId('preview-report').click(); expect((await response).status()).toBe(403)
  await assertClearedReport(page)
  await screenshotReport(page,testInfo,'same-tab-account-replacement')
})


test('real report pre-click patient boundary: metadata is visible before a real App selection invalidates the file', async ({ sessions }, testInfo) => {
  const page = sessions.owner, counter = downloadCounter(page)
  await openReport(page); await selectPatient(page, ids.reportPatient)
  // Materialize the actual Element Plus options through its keyboard control.
  // At this extremely short boundary, a native option click is synchronously
  // dispatched by instrumentation so the actual App selection handler runs.
  await page.locator('.patient-switcher').getByRole('combobox').press('ArrowDown')
  await expect(page.getByRole('option',{name:'Synthetic Empty Report Patient',exact:true})).toBeVisible()
  await page.keyboard.press('Escape')
  await page.evaluate(() => {
    const native = URL.createObjectURL
    window.__reportPatientBoundary = null
    URL.createObjectURL = function (blob) {
      const metadata = document.querySelector('[data-testid="last-export"]')
      const option = [...document.querySelectorAll('[role="option"]')].find(node => node.textContent.trim() === 'Synthetic Empty Report Patient')
      window.__reportPatientBoundary = { metadata: metadata?.textContent || '', optionFound: !!option }
      if (!option) throw new Error('Synthetic patient option must already exist')
      option.click() // Production Element Plus -> App switchPatient, no state injection.
      return native.call(URL,blob)
    }
    window.__restoreReportPatientBoundary = () => { URL.createObjectURL = native; delete window.__restoreReportPatientBoundary }
  })
  try {
    const delivered = reportResponse(page,'export',{format:'actions_csv'})
    await page.getByTestId('download-actions_csv').click()
    expect((await delivered).status()).toBe(200)
    await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(reportPath(ids.emptyReportPatient))}`)
    const boundary = await page.evaluate(() => window.__reportPatientBoundary)
    expect(boundary.optionFound).toBe(true)
    expect(boundary.metadata).toContain(`#${ids.reportPatient}`)
    expect(boundary.metadata).toMatch(/schema.*1/)
    await assertClearedReport(page);expect(counter.count()).toBe(0)
    await screenshotReport(page,testInfo,'pre-click-real-app-patient-change')
  } finally { await page.evaluate(() => window.__restoreReportPatientBoundary?.()); counter.dispose() }
})

test('real report coalesced App A→B→A retains the same panel but rejects its original late preview and CSV', async ({ sessions }, testInfo) => {
  test.setTimeout(120000)
  const page = sessions.owner, counter = downloadCounter(page)
  await openReport(page);await selectPatient(page,ids.reportPatient)
  try {
    for(const endpoint of ['preview','export']) {
      await openReport(page)
      // Report readiness is independent of the App's patient-name bootstrap.
      // A keypress on its initially disabled select cannot open the dropdown.
      const patientSelect = page.locator('.patient-switcher').getByRole('combobox')
      await expect(patientSelect).toBeEnabled()
      await patientSelect.focus();await expect(patientSelect).toBeFocused()
      await patientSelect.press('ArrowDown')
      await expect(patientSelect).toHaveAttribute('aria-expanded','true')
      await expect(page.getByRole('option',{name:'Synthetic Empty Report Patient',exact:true})).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(patientSelect).toHaveAttribute('aria-expanded','false')
      const original = await panel(page).elementHandle()
      const hold = await holdRealReportResponse(page,endpoint)
      try {
        await page.getByTestId(endpoint==='preview'?'preview-report':'download-actions_csv').click()
        await hold.reached
        const accepted = await page.evaluate(async () => {
          const options = [...document.querySelectorAll('[role="option"]')]
          const option = name => options.find(node=>node.textContent.trim()===name)
          const a=option('Synthetic Report Patient'),b=option('Synthetic Empty Report Patient')
          if(!a||!b)throw new Error('Synthetic App patient options missing')
          b.click()
          const middle=Number(localStorage.getItem('currentPatientId'))
          // Element Plus compares its controlled modelValue before emitting change.
          // One Vue flush microtask updates that value; router guard promises are
          // still pending. Both real clicks occur in this same browser task.
          await Promise.resolve()
          a.click()
          return {middle,last:Number(localStorage.getItem('currentPatientId'))}
        })
        expect(accepted).toEqual({middle:ids.emptyReportPatient,last:ids.reportPatient})
        await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(reportPath())}`)
        expect(await original.evaluate(element=>element===document.querySelector('.execution-report')),'Coalesced actual App selection retains the original panel').toBe(true)
        hold.release();await hold.dispose();await waitForIdle(page)
        expect(await original.evaluate(element=>element===document.querySelector('.execution-report'))).toBe(true)
        await assertClearedReport(page);expect(counter.count()).toBe(0)
      }finally{await hold.dispose();await original.dispose()}
    }
    await screenshotReport(page,testInfo,'coalesced-same-panel-a-b-a')
  }finally{counter.dispose()}
})
