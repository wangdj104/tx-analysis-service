/**
 * Actual browser acceptance for the frozen UI, using fictional data only.
 * Run: UI_SEVENTEEN_EVIDENCE_DIR=/absolute/evidence node tests/uiSeventeenClinical.browser.mjs
 * Offline/sandbox compile check: add --compile-only (never launches/listens).
 * Requires the repository's pinned @playwright/test and an installed Chromium;
 * CHROMIUM_PATH can select an already-authorized executable. No installs here.
 * The harness imports real edition SFCs, APIs, auth guards, chart and theme CSS.
 * Only HTTP responses and fixture-host navigation are synthetic. No SFC stubs,
 * exposed setup-state mutation, production writes, real patients or credentials.
 * Item 10 (BP/nutrition history-edit focus) is covered by the monitoring harness.
 */
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium, expect } from '@playwright/test'
import { build, createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

const repo = fileURLToPath(new URL('../../', import.meta.url))
const dependencyRoot = join(repo, 'frontend')
const evidenceDir = resolve(process.env.UI_SEVENTEEN_EVIDENCE_DIR || join(dependencyRoot, 'test-results/ui-seventeen'))
const compileOnly = process.argv.includes('--compile-only')
const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim()
const itemIds = [3, 5, 11, 15, 17]
const themes = ['platform', 'white', 'blue', 'mint', 'sand', 'dark']
const themeOracle = {
  platform: ['#ffffff', '#f4f7f6', '#f0f7f3', '#176b59'],
  white: ['#ffffff', '#f5f7fa', '#f0f7f3', '#176b59'],
  blue: ['#ffffff', '#f1f6fb', '#edf4fb', '#225b91'],
  mint: ['#ffffff', '#f0f7f3', '#edf7f1', '#176b59'],
  sand: ['#fffdf9', '#f8f2e9', '#f8f0e3', '#7a5429'],
  dark: ['#18262e', '#203139', '#203e37', '#7cd3b5']
}
const viewports = [1365, 375]
const harnessSha256 = createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex')
const report = { commit, mode: compileOnly ? 'compile-only' : 'browser', harnessSha256,
  syntheticDataOnly: true, startedAt: new Date().toISOString(), results: [], compilation: [],
  coverage: { itemIds, editions: ['en', 'cn'], viewports, themes,
    excluded: [{ itemId: 10, reason: 'BP/nutrition history-edit focus belongs to the monitoring browser harness' }] } }
report.sourceHashes = {}
for (const prefix of ['frontend', 'cn/frontend']) for (const file of [
  'components/care-plan/AiAnalysisReviewDialog.vue', 'components/care-plan/ReceiptDialog.vue',
  'views/MedicalRecordManager.vue', 'components/MedicalRecordReviewDialog.vue',
  'utils/healthAppearance.js', 'styles/health-appearance.css'
]) report.sourceHashes[`${prefix}/src/${file}`] = createHash('sha256').update(readFileSync(join(repo, prefix, 'src', file))).digest('hex')
mkdirSync(evidenceDir, { recursive: true })
const writeReport = () => writeFileSync(join(evidenceDir, 'clinical-results.json'), JSON.stringify(report, null, 2) + '\n')
assert.ok(process.argv.slice(2).every(arg => arg === '--compile-only'), 'Only --compile-only is supported')

function fixtureData(edition) {
  const patientName = edition === 'en' ? 'Synthetic patient' : '演示同名患者'
  const analysisContent = 'SYNTHETIC ORIGINAL START\nOriginal clinical prose 原始记录 preserved verbatim.\n'
    + Array.from({ length: 12 }, (_, i) => `Saved source paragraph ${i + 1}: fictional evidence only.`).join('\n')
    + '\n<img src=x onerror="window.clinicalInjected=true">\nSYNTHETIC ORIGINAL END'
  const analysis = { id: 51, patientId: 11, userId: 71, reviewStatus: 'REVIEW_REQUIRED',
    timeType: 'MONTH', timeValue: '2026-10', periodLabel: 'SYNTHETIC ORIGINAL PERIOD',
    analysisContent, createdAt: '2026-10-10T10:00:00Z', updatedAt: '2026-10-10T11:00:00Z',
    remark: 'SYNTHETIC ORIGINAL NOTE', totalCount: 6, avgOnWeight: 70.1,
    vitalSignTrendSummary: { source: 'SYNTHETIC AGGREGATE EVIDENCE', originalUnit: 'mmHg' },
    medicationAdviceDetails: [{ text: 'SYNTHETIC SAVED RECOMMENDATION' }],
    dwAdjustReason: 'SYNTHETIC SAVED RATIONALE', confidenceScore: 0.91 }
  const record = { id: 901, patientId: 11, patientName, recordDate: '2026-10-10', recordType: 'BLOOD',
    contentVersion: 7, hospitalName: 'Synthetic hospital 原始医院', deptName: 'Synthetic department',
    doctorName: 'Synthetic clinician', sourceType: 'IMPORT', sourceExternalId: 'SYNTHETIC-EXTERNAL-901',
    confidenceScore: 0.88, verificationStatus: 'REVIEW_REQUIRED',
    remark: 'SYNTHETIC ORIGINAL RECORD NOTE', aiRawResult: '{"original":"SYNTHETIC RECOGNIZED SOURCE"}',
    items: [{ id: 201, itemName: 'Synthetic hemoglobin 原始项目', itemCode: 'SYNTHETIC-HB',
      resultValue: '101', unit: 'g/L', referenceRange: 'Synthetic source range', isAbnormal: 1, aiConfidence: 0.87 }],
    attachments: [{ id: 301, fileName: 'synthetic-original.pdf', fileType: 'PDF', fileSize: 1234 }] }
  const measurements = Array.from({ length: 6 }, (_, i) => ({ id: 701 + i, patient_id: 11,
    measured_at: `2026-10-10 0${i + 1}:00:00`, metric_type: 'BP',
    value_primary: 120 + i, value_secondary: 80, unit: 'mmHg' }))
  measurements.push({ id: 799, patient_id: 12, measured_at: '2026-10-10', metric_type: 'BP',
    value_primary: 999, value_secondary: 999, unit: 'SYNTHETIC OTHER PATIENT' })
  return { patientName, analysisContent, analysis, record, measurements,
    patients: [{ id: 11, patientName }, { id: 12, patientName }],
    records: [record, { ...record, id: 902, recordDate: '2026-10-09' },
      { ...record, id: 999, patientId: 12, hospitalName: 'SYNTHETIC OTHER PATIENT' }],
    trends: [
      { ...record.items[0], recordDate: '2026-10-09', hospitalName: record.hospitalName },
      { ...record.items[0], id: 202, resultValue: '103', recordDate: '2026-10-10', hospitalName: record.hospitalName }
    ] }
}

function mainSource(frontend, edition, data) {
  const src = path => JSON.stringify(`/@fs/${join(frontend, 'src', path)}`)
  return `
    import {createApp,h,ref} from 'vue';
    import {createRouter,createMemoryHistory,RouterView} from 'vue-router';
    import ElementPlus from 'element-plus';
    import locale from 'element-plus/es/locale/lang/${edition === 'en' ? 'en' : 'zh-cn'}';
    import * as icons from '@element-plus/icons-vue';
    import 'element-plus/dist/index.css';
    ${['app-theme', 'mobile-optimization', 'health-redesign', 'care-accessibility', 'health-appearance'].map(name => `import ${src(`styles/${name}.css`)};`).join('\n')}
    import AiReview from ${src('components/care-plan/AiAnalysisReviewDialog.vue')};
    import Receipt from ${src('components/care-plan/ReceiptDialog.vue')};
    import MedicalReview from ${src('components/MedicalRecordReviewDialog.vue')};
    import MedicalManager from ${src('views/MedicalRecordManager.vue')};
    import {initializeAppearance,APPEARANCE_THEME_IDS} from ${src('utils/healthAppearance.js')};
    import {useCurrentPatient} from ${src('composables/useCurrentPatient.js')};
    import i18n from ${src('i18n/index.js')};
    initializeAppearance();useCurrentPatient().setPatientList(${JSON.stringify(data.patients)});
    window.__clinicalQa={events:[],themes:APPEARANCE_THEME_IDS};
    function host(Component,props,testId){return{setup(){const visible=ref(false);
      return()=>h('section',{class:'synthetic-dialog-host'},[
        h('p',${JSON.stringify(edition === 'en' ? 'Synthetic browser acceptance fixture' : '虚构数据浏览器验收场景')}),
        h('button',{'data-testid':testId,type:'button',onClick:()=>visible.value=true},${JSON.stringify(edition === 'en' ? 'Open authorized fixture' : '打开已授权演示场景')}),
        visible.value?h(Component,{...props,onClosed:()=>{visible.value=false;window.__clinicalQa.events.push('closed')},
          onReviewed:()=>{visible.value=false;window.__clinicalQa.events.push('reviewed')},
          onSubmitted:()=>window.__clinicalQa.events.push('submitted')}):null])}}}
    const routes=[
      {path:'/ai',component:host(AiReview,{item:{sourceType:'AI_ANALYSIS',sourceId:51,patientId:11,patientName:${JSON.stringify(data.patientName)}}},'open-ai')},
      {path:'/receipt',component:host(Receipt,{action:{id:61,planId:41,patientId:11,patientName:${JSON.stringify(data.patientName)},instruction:'SYNTHETIC EXISTING CARE INSTRUCTION',allowedActions:['SUBMIT_RECEIPT'],allowedEntryModes:['ASSISTED']},planVersion:2},'open-receipt')},
      {path:'/medical-review',component:host(MedicalReview,{recordId:901,patientId:11,workspace:true},'open-medical-review')},
      {path:'/medical-record',component:MedicalManager},
      {path:'/blank',component:{render:()=>h('p','Synthetic blank fixture')}}
    ];
    const router=createRouter({history:createMemoryHistory(),routes});
    const screen=new URL(location.href).searchParams.get('screen')||'ai';
    const target=screen==='trend'?'/medical-record?tab=trend':screen==='upload'?'/medical-record?tab=upload':'/'+screen;
    router.push(target).then(()=>{const app=createApp({render:()=>h('div',{class:'care-workspace'},[h(RouterView)])});
      app.use(router);app.use(ElementPlus,{locale});app.use(i18n);
      for(const [name,icon]of Object.entries(icons))app.component(name,icon);
      app.mount('#app');window.__clinicalQa.ready=true;});
  `
}

async function startHarness(edition, compile) {
  const frontend = join(repo, edition === 'en' ? 'frontend' : 'cn/frontend')
  const harness = mkdtempSync(join(dependencyRoot, '.ui-seventeen-clinical-'))
  const data = fixtureData(edition)
  let server
  try {
    writeFileSync(join(harness, 'index.html'), `<!doctype html><html lang="${edition === 'en' ? 'en' : 'zh-CN'}"><meta name="viewport" content="width=device-width,initial-scale=1"><body><div id="app"></div><script type="module" src="/main.js"></script></body></html>`)
    writeFileSync(join(harness, 'main.js'), mainSource(frontend, edition, data))
    const config = { configFile: false, root: harness, plugins: [vue()],
      resolve: { alias: { '@': join(frontend, 'src') }, dedupe: ['vue', 'vue-router', 'element-plus'] },
      server: { host: '127.0.0.1', port: 0, hmr: false, fs: { allow: [repo] } }, logLevel: 'error' }
    if (compile) {
      await build({ ...config, build: { outDir: join(harness, 'dist'), reportCompressedSize: false, chunkSizeWarningLimit: 5000 } })
      return { data, close: async () => rmSync(harness, { recursive: true, force: true }) }
    }
    server = await createServer(config)
    await server.listen()
    return { data, base: `http://127.0.0.1:${server.httpServer.address().port}`,
      close: async () => { await server.close(); rmSync(harness, { recursive: true, force: true }) } }
  } catch (error) {
    await server?.close(); rmSync(harness, { recursive: true, force: true }); throw error
  }
}

async function attachApiFixtures(context, data, base) {
  const fixture = { reads: [], writes: [], unexpected: [], trend: 'empty', denyReferences: false,
    historyMissing: false, holdDecision: false, releaseDecision: null, decisionsSettled: 0, holdTrend: false, releaseTrend: null }
  await context.routeWebSocket('**/*', socket => { fixture.unexpected.push({ reason: 'Blocked WebSocket', url: socket.url() }); socket.close() })
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.slice(4)
    if (url.origin !== base) { fixture.unexpected.push({ reason: 'Blocked external request', origin: url.origin }); return route.abort('blockedbyclient') }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const method = request.method(), body = ['POST', 'PUT', 'PATCH'].includes(method) ? request.postDataJSON() : null
    const event = { path, method, query: Object.fromEntries(url.searchParams), body }
    if (method === 'GET') fixture.reads.push(event); else fixture.writes.push(event)
    const json = (value, status = 200, code = 200) => route.fulfill({ status,
      contentType: 'application/json', body: JSON.stringify({ code, data: value, msg: 'Synthetic fixture response' }) })
    if (!request.headers().authorization?.startsWith('Bearer synthetic-')) {
      fixture.unexpected.push({ ...event, reason: 'Missing fictional authorization' }); return json(null, 403, 403)
    }
    if (path === '/patient/names' && method === 'GET') return json(data.patients)
    if (path === '/ai/history' && method === 'GET') {
      if (url.searchParams.get('patientId') !== '11') fixture.unexpected.push(event)
      return json(fixture.historyMissing ? [] : [data.analysis, { ...data.analysis, id: 52, patientId: 12, analysisContent: 'SYNTHETIC OTHER PATIENT' }])
    }
    if (path === '/doctor-workspace/reviews' && method === 'POST') {
      if (fixture.holdDecision) await new Promise(resolve => { fixture.releaseDecision = resolve })
      await json({ id: body.sourceId, reviewStatus: body.decision }); fixture.decisionsSettled++; return
    }
    if (path === '/medical-record/901/review-preview' && method === 'GET') return json(data.record)
    if (path === '/medical-record/items' && method === 'GET') return json([data.record.items[0].itemName])
    if (path === '/medical-record/trend' && method === 'GET') {
      if (url.searchParams.get('patientId') !== '11' || url.searchParams.get('itemName') !== data.record.items[0].itemName) fixture.unexpected.push(event)
      const state = fixture.trend
      if (fixture.holdTrend) await new Promise(resolve => { fixture.releaseTrend = resolve })
      return state === 'error' ? json(null, 503, 503) : state === 'forbidden' ? json(null, 403, 403)
        : state === 'application-forbidden' ? json(null, 200, 403) : json(state === 'empty' ? [] : data.trends)
    }
    if (['/medical-record/list', '/care-journey/measurements'].includes(path) && method === 'GET') {
      if (url.searchParams.get('patientId') !== '11') fixture.unexpected.push(event)
      if (fixture.denyReferences) return json(null, 403, 403)
      return json(path === '/medical-record/list' ? data.records : data.measurements)
    }
    if (path === '/care-plans/actions/61/receipts' && method === 'POST') return json({ id: 801, status: 'REVIEW_REQUIRED' })
    fixture.unexpected.push(event)
    return json(null, 404, 404)
  })
  return fixture
}

async function verifyCell(browser, harness, edition, viewport, theme) {
  const result = { itemIds, edition, viewport, theme, status: 'failed', screenshots: [], screenshotHashes: {}, assertions: [] }
  const context = await browser.newContext({ viewport: { width: viewport, height: 900 },
    timezoneId: 'America/Los_Angeles', locale: edition === 'en' ? 'en-US' : 'zh-CN', reducedMotion: 'reduce' })
  const { data, base } = harness
  await context.addInitScript(({ theme }) => {
    localStorage.setItem('token', 'synthetic-clinical-only'); localStorage.setItem('userId', '71')
    localStorage.setItem('realName', 'Synthetic reviewer'); localStorage.setItem('currentPatientId', '11')
    localStorage.setItem('permissionSession', 'synthetic-clinical-only')
    localStorage.setItem('userMenus', JSON.stringify(['/medical-record', '/medical-record?tab=upload']))
    localStorage.setItem('userMenuNames', '[]'); localStorage.setItem('userRoleCodes', '["doctor"]')
    localStorage.setItem('chengxin-health-appearance-v1', JSON.stringify({ version: 1, theme }))
  }, { theme })
  const fixture = await attachApiFixtures(context, data, base), page = await context.newPage(), errors = []
  page.on('pageerror', error => errors.push(error.message))
  page.setDefaultTimeout(15000)
  const t = (en, cn) => edition === 'en' ? en : cn
  const recordId = t('Record #11', '档案 #11')
  const visit = async screen => {
    await page.goto(`${base}/?screen=${screen}`)
    await page.waitForFunction(() => window.__clinicalQa?.ready)
    assert.deepEqual(await page.evaluate(() => [...window.__clinicalQa.themes]), themes)
    await expect(page.locator('html')).toHaveAttribute('data-health-theme', theme)
    const rendered = await page.evaluate(() => {
      const style = getComputedStyle(document.documentElement)
      return { viewport: innerWidth, theme: document.documentElement.dataset.healthTheme,
        signature: ['--paper', '--surface-subtle', '--care-50', '--care-700'].map(key => style.getPropertyValue(key).trim()),
        bodyBackground: getComputedStyle(document.body).backgroundColor, colorScheme: style.colorScheme }
    })
    assert.equal(rendered.viewport, viewport, 'Actual innerWidth matches the matrix cell')
    assert.deepEqual(rendered.signature, themeOracle[theme], 'Actual computed CSS signature matches this theme, not merely its metadata label')
    assert.equal(rendered.colorScheme, theme === 'dark' ? 'dark' : 'light')
    result.renderedTheme = rendered
  }
  const snapshot = async name => {
    const path = `${edition}-${viewport}-${theme}-clinical-${name}.png`
    await page.screenshot({ path: join(evidenceDir, path), fullPage: true })
    result.screenshots.push(path)
    result.screenshotHashes[path] = createHash('sha256').update(readFileSync(join(evidenceDir, path))).digest('hex')
  }
  const checked = text => result.assertions.push(text)
  const noWrites = count => assert.equal(fixture.writes.length, count, 'Opening, reading and closing must not write')
  const responsive = async selector => {
    const dimensions = await page.locator(selector).evaluate(node => {
      const box = node.getBoundingClientRect()
      return { left: box.left, right: box.right, width: innerWidth,
        pageWidth: document.documentElement.scrollWidth, paper: getComputedStyle(document.documentElement).getPropertyValue('--paper').trim() }
    })
    assert.ok(dimensions.left >= -1 && dimensions.right <= dimensions.width + 1, `${selector} fits the ${viewport}px viewport`)
    assert.equal(dimensions.width, viewport, 'Responsive assertions use the actual requested innerWidth')
    assert.ok(dimensions.pageWidth <= dimensions.width + 2, 'No document-level horizontal overflow')
    assert.ok(dimensions.paper, 'Actual theme surface token is present')
    const surfaceSelector = selector === '.medical-record-manager' ? '.medical-record-manager .content-panel' : selector
    const surface = await page.locator(surfaceSelector).evaluate(node => ({ background: getComputedStyle(node).backgroundColor, color: getComputedStyle(node).color }))
    const hex = themeOracle[theme][0].slice(1), rgb = `rgb(${[0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16)).join(', ')})`
    assert.equal(surface.background, rgb, 'The actual rendered dialog/panel uses the selected theme surface')
    result.renderedSurfaces ??= []; result.renderedSurfaces.push({ selector: surfaceSelector, ...surface })
  }
  try {
    // Original, escaped source and every saved provenance/evidence field precede decisions.
    await visit('ai'); await page.getByTestId('open-ai').click()
    await expect(page.getByTestId('ai-original-analysis')).toHaveText(data.analysisContent)
    const ai = page.locator('.ai-review')
    await expect(ai.locator('h2')).toContainText(`${data.patientName} · ${recordId}`)
    for (const sentinel of ['SYNTHETIC AGGREGATE EVIDENCE', 'SYNTHETIC SAVED RECOMMENDATION', 'SYNTHETIC SAVED RATIONALE', 'SYNTHETIC ORIGINAL PERIOD', 'SYNTHETIC ORIGINAL NOTE']) await expect(ai).toContainText(sentinel)
    assert.equal(await ai.locator('img,script,[onerror]').count(), 0, 'Original HTML-like prose stays inert text')
    assert.equal(await page.evaluate(() => !!window.clinicalInjected), false)
    assert.equal(await ai.evaluate(node => {
      const source = node.querySelector('[data-testid="ai-original-analysis"]'), evidence = node.querySelector('dl'), approve = node.querySelector('[data-testid="approve-ai-review"]')
      return !!(source.compareDocumentPosition(approve) & Node.DOCUMENT_POSITION_FOLLOWING) && !!(evidence.compareDocumentPosition(approve) & Node.DOCUMENT_POSITION_FOLLOWING)
    }), true)
    await expect(page.getByTestId('approve-ai-review')).toBeDisabled(); await expect(page.getByTestId('reject-ai-review')).toBeDisabled()
    await expect(ai).toContainText(t('not a guarantee of clinical reliability', '不保证临床可靠性'))
    await responsive('.el-dialog'); await snapshot('ai-original-analysis')
    await ai.locator('dl dd').filter({ hasText: 'SYNTHETIC AGGREGATE EVIDENCE' }).scrollIntoViewIfNeeded()
    await snapshot('ai-saved-evidence')
    await page.getByTestId('approve-ai-review').scrollIntoViewIfNeeded(); await snapshot('ai-acknowledgement-gate'); noWrites(0)
    await ai.getByRole('button', { name: t('Close', '关闭'), exact: true }).click(); await expect(ai).toHaveCount(0); noWrites(0)
    checked('Full original analysis, saved evidence/provenance, authorized name+ID, inert source text, acknowledgement gate and read-only open/close')
    for (const decision of ['APPROVED', 'REJECTED']) {
      await page.getByTestId('open-ai').click(); await expect(page.getByTestId('ai-original-analysis')).toHaveText(data.analysisContent)
      await ai.locator('input[type="checkbox"]').check(); await ai.locator('textarea').fill('Synthetic explicit review note')
      await page.getByTestId(decision === 'APPROVED' ? 'approve-ai-review' : 'reject-ai-review').click()
      await expect(ai).toHaveCount(0)
      assert.deepEqual(fixture.writes.at(-1).body, { sourceType: 'AI_ANALYSIS', sourceId: 51, decision, reviewNote: 'Synthetic explicit review note' })
    }
    checked('Approve and reject each submit exactly the displayed authorized source after explicit acknowledgement')
    fixture.historyMissing = true
    await page.getByTestId('open-ai').click(); await expect(ai.getByRole('alert')).toBeVisible()
    await expect(page.getByTestId('approve-ai-review')).toHaveCount(0); await expect(page.getByTestId('ai-original-analysis')).toHaveCount(0)
    await ai.getByRole('button', { name: t('Close', '关闭'), exact: true }).click(); noWrites(2); fixture.historyMissing = false
    checked('Missing original source cannot expose decision buttons')

    // A genuine same-origin sibling-tab storage event releases an obsolete pending decision.
    await page.getByTestId('open-ai').click(); await expect(page.getByTestId('ai-original-analysis')).toBeVisible()
    await ai.locator('input[type="checkbox"]').check(); await ai.locator('textarea').fill('Synthetic account-scoped draft')
    fixture.holdDecision = true
    await page.getByTestId('approve-ai-review').click(); await expect.poll(() => !!fixture.releaseDecision).toBe(true)
    const sibling = await context.newPage(); await sibling.goto(`${base}/?screen=blank`)
    await sibling.evaluate(() => localStorage.setItem('token', 'synthetic-replacement-account'))
    await expect(page.getByTestId('ai-original-analysis')).toHaveCount(0)
    await expect(ai.locator('textarea')).toHaveCount(0)
    await expect(ai.getByRole('button', { name: t('Close', '关闭'), exact: true })).toBeEnabled()
    await ai.getByRole('button', { name: t('Close', '关闭'), exact: true }).click()
    const reviewedBefore = await page.evaluate(() => window.__clinicalQa.events.filter(e => e === 'reviewed').length)
    fixture.releaseDecision(); fixture.holdDecision = false
    await expect.poll(() => fixture.decisionsSettled).toBe(3)
    await expect(ai).toHaveCount(0)
    assert.equal(await page.evaluate(() => window.__clinicalQa.events.filter(e => e === 'reviewed').length), reviewedBefore)
    await sibling.evaluate(() => localStorage.setItem('token', 'synthetic-clinical-only')); await sibling.close()
    checked('Actual cross-tab account change clears protected source/note and pending busy state; closing remains possible')

    // Real imported-record preview, original values/attachment metadata, no implicit decision.
    await visit('medical-review'); await page.getByTestId('open-medical-review').click()
    const medical = page.locator('.medical-review')
    await expect(medical.locator('h2')).toContainText(`${data.patientName} · ${recordId}`)
    for (const text of ['SYNTHETIC ORIGINAL RECORD NOTE', 'SYNTHETIC RECOGNIZED SOURCE', 'SYNTHETIC-EXTERNAL-901', 'synthetic-original.pdf', '101', 'g/L']) await expect(medical).toContainText(text)
    await expect(medical.locator('.medical-review-context dd').first()).toHaveText('7')
    await expect(page.getByTestId('confirm-medical-approve')).toBeDisabled(); await expect(page.getByTestId('confirm-medical-reject')).toBeDisabled()
    await responsive('.el-dialog'); await snapshot('medical-original-review')
    const beforeClose = fixture.writes.length
    await medical.getByRole('button', { name: t('Close', '关闭'), exact: true }).click(); await expect(medical).toHaveCount(0); noWrites(beforeClose)
    checked('Actual medical-review dialog shows source version, original values, attachment metadata and patient identity without writing')

    // Native friendly controls, existing scoped records, unchanged explicit-offset payload.
    await visit('receipt'); const opener = page.getByTestId('open-receipt'); await opener.click()
    const receipt = page.locator('dialog.receipt-dialog')
    await expect(receipt).toHaveAttribute('open', '')
    await expect(receipt.locator('.receipt-patient')).toContainText(`${data.patientName} · ${recordId}`)
    await page.getByTestId('close-receipt').focus(); await page.keyboard.press('Tab'); await expect(page.getByTestId('receipt-note')).toBeFocused()
    await page.keyboard.press('Shift+Tab'); await expect(page.getByTestId('close-receipt')).toBeFocused()
    await page.keyboard.press('Escape'); await expect(receipt).toHaveCount(0); await expect(opener).toBeFocused(); await opener.click()
    const clock = page.getByTestId('receipt-execution-time')
    await expect(clock).toHaveAttribute('type', 'datetime-local')
    const offset = page.getByTestId('receipt-time-offset')
    await clock.fill('2026-11-01T01:30')
    await expect(offset.locator('option[value="-420"]')).toHaveCount(1)
    await expect(offset.locator('option[value="-480"]')).toHaveCount(1)
    await expect(receipt.locator('form > [role="alert"]')).toHaveCount(1)
    await offset.selectOption('-420'); await expect(receipt.locator('form > [role="alert"]')).toHaveCount(0)
    await offset.selectOption('-480'); await expect(receipt.locator('form > [role="alert"]')).toHaveCount(0)
    await snapshot('receipt-dst-explicit-offset')
    await clock.fill('2026-03-08T02:30')
    await expect(offset).toBeDisabled()
    await expect(offset.locator('option[value="-420"],option[value="-480"]')).toHaveCount(0)
    await expect(receipt.locator('form > [role="alert"]')).toHaveCount(1)
    await snapshot('receipt-dst-skipped-time')
    checked('Repeated daylight-saving local time requires either explicit UTC offset; skipped local time is rejected in the actual controls')
    await clock.fill('2026-10-10T08:30:15.123'); await expect(page.getByTestId('receipt-time-offset')).toHaveValue('-420')
    await page.getByTestId('receipt-note').fill('Synthetic receipt: completed existing instruction')
    await receipt.getByRole('button', { name: t('Browse authorized records', '查看已授权记录'), exact: true }).click()
    const picker = page.getByTestId('receipt-existing-record')
    await expect(picker.locator('option[value="701"]')).toContainText(t('Blood pressure', '血压'))
    await expect(picker.locator('option[value="701"]')).toContainText('2026-10-10')
    await expect(picker.locator('option[value="701"]')).toContainText('120/80 mmHg')
    await expect(picker.locator('option[value="799"]')).toHaveCount(0)
    for (const id of ['701', '702', '703', '704']) {
      await picker.selectOption(id); await page.getByTestId('add-existing-reference').click()
      await expect(picker.locator(`option[value="${id}"]`)).toBeDisabled()
    }
    await receipt.getByRole('combobox', { name: t('Reference type', '引用类型'), exact: true }).selectOption('MEDICAL_RECORD')
    await receipt.getByRole('button', { name: t('Browse authorized records', '查看已授权记录'), exact: true }).click()
    await expect(picker.locator('option[value="901"]')).toContainText(t('Blood test', '血液检验'))
    await expect(picker.locator('option[value="901"]')).toContainText(data.record.hospitalName)
    await expect(picker.locator('option[value="999"]')).toHaveCount(0)
    await picker.selectOption('901'); await page.getByTestId('add-existing-reference').click()
    await expect(receipt.locator('.evidence-row')).toHaveCount(5)
    await picker.selectOption('902'); await expect(page.getByTestId('add-existing-reference')).toBeDisabled()
    await responsive('dialog.receipt-dialog'); await snapshot('receipt-friendly-controls')
    await page.getByTestId('submit-receipt').click(); await expect(receipt).toHaveCount(0)
    const receiptWrite = fixture.writes.at(-1)
    assert.equal(receiptWrite.path, '/care-plans/actions/61/receipts')
    assert.equal(receiptWrite.body.occurredAt, '2026-10-10T08:30:15.123-07:00')
    assert.equal(new Date(receiptWrite.body.occurredAt).toISOString(), '2026-10-10T15:30:15.123Z')
    assert.equal(receiptWrite.body.expectedVersion, 2); assert.equal(receiptWrite.body.entryMode, 'ASSISTED')
    assert.equal(receiptWrite.body.evidence.length, 5)
    assert.equal(new Set(receiptWrite.body.evidence.map(e => `${e.sourceType}:${e.sourceId}`)).size, 5)
    await expect(opener).toBeFocused()
    checked('Native date/time generates unchanged explicit-offset instant; recognizable authorized measurement/medical-record options; wrong patient excluded; unique five cap and opener focus restored')
    await opener.click(); await receipt.getByRole('button', { name: t('Browse authorized records', '查看已授权记录'), exact: true }).click()
    await picker.selectOption('701'); await page.getByTestId('add-existing-reference').click()
    fixture.denyReferences = true
    await receipt.getByRole('button', { name: t('Browse authorized records', '查看已授权记录'), exact: true }).click()
    await expect(receipt.getByRole('alert')).toBeVisible(); await expect(receipt.locator('.evidence-row')).toHaveCount(0)
    await expect(picker).toHaveCount(0); fixture.denyReferences = false
    const beforeReceiptClose = fixture.writes.length
    await page.getByTestId('close-receipt').click(); await expect(receipt).toHaveCount(0); await expect(opener).toBeFocused(); noWrites(beforeReceiptClose)
    checked('Revoked record access clears selectable and selected protected references; closing does not write')

    // Actual manager state machine and a same-name authorized selector, never setup-state edits.
    await visit('trend'); const trend = page.locator('.trend-panel'), state = trend.locator('.trend-query-state')
    await expect(state).toContainText(t('Not queried yet', '尚未查询'))
    await expect(trend.locator('.el-select').first()).toContainText(`${data.patientName} · ${recordId}`)
    await trend.locator('.el-select').first().click()
    for (const id of [11, 12]) await expect(page.getByRole('option', { name: `${data.patientName} · ${t('Record', '档案')} #${id}`, exact: true })).toBeVisible()
    await page.getByRole('option', { name: `${data.patientName} · ${recordId}`, exact: true }).click()
    await trend.locator('.el-select').nth(1).click()
    await page.getByRole('option', { name: data.record.items[0].itemName, exact: true }).click()
    const query = trend.getByRole('button', { name: t('View trend', '查询趋势'), exact: true })
    await expect(query).toBeVisible(); assert.equal(await trend.getByText('querytrend', { exact: true }).count(), 0)
    fixture.holdTrend = true; await query.click(); await expect.poll(() => !!fixture.releaseTrend).toBe(true)
    await expect(state.getByRole('status')).toBeVisible(); await snapshot('lab-loading')
    fixture.releaseTrend(); fixture.holdTrend = false
    await expect(state).toContainText(t('No historical records', '暂无历史记录'))
    await expect(state).toContainText(data.record.items[0].itemName); await expect(state).toContainText(`${data.patientName} · ${recordId}`)
    await expect(trend.locator('.trend-chart-wrap')).toHaveCount(0); await snapshot('lab-empty')
    fixture.trend = 'error'; await query.click(); await expect(state.getByRole('alert')).toBeVisible()
    await expect(page.getByTestId('retry-trend')).toBeVisible(); await snapshot('lab-error-retry')
    fixture.trend = 'loaded'; await page.getByTestId('retry-trend').click()
    await expect(state.getByRole('alert')).toHaveCount(0)
    await expect(state).toContainText(t('Displayed result:', '当前结果对应：'))
    await expect(trend.locator('.trend-chart-wrap canvas')).toBeVisible()
    for (const row of data.trends) {
      const rendered = trend.locator('.el-table__body tr').filter({ hasText: row.recordDate })
      await expect(rendered).toHaveCount(1); await expect(rendered).toContainText(row.resultValue); await expect(rendered).toContainText(row.unit)
    }
    await responsive('.medical-record-manager'); await snapshot('lab-populated')
    fixture.trend = 'error'; await query.click()
    await expect(state).toContainText(t('last successful result; refresh failed', '上次成功结果，刷新失败'))
    await expect(trend.locator('.trend-chart-wrap')).toBeVisible()
    fixture.trend = 'forbidden'; await page.getByTestId('retry-trend').click()
    await expect(state.getByRole('alert')).toBeVisible(); await expect(trend.locator('.trend-chart-wrap')).toHaveCount(0)
    await expect(state).not.toContainText(t('Displayed result:', '当前结果对应：'))
    fixture.trend = 'loaded'; await page.getByTestId('retry-trend').click(); await expect(trend.locator('.trend-chart-wrap')).toBeVisible()
    fixture.trend = 'application-forbidden'; await query.click(); await expect(trend.locator('.trend-chart-wrap')).toHaveCount(0)
    checked('Lab idle/loading/empty/error/retry/populated states and exact query context; transient failure labels retained result; HTTP and application 403 purge protected chart')

    await visit('upload')
    await expect(page.locator('.upload-panel .submission-patient')).toContainText(`${data.patientName} · ${recordId}`)
    await page.locator('.upload-panel .el-select').nth(1).click()
    await expect(page.getByRole('option', { name: t('Blood test', '血液检查'), exact: true })).toBeVisible()
    await expect(page.getByRole('option', { name: t('Urinalysis', '尿液检查'), exact: true })).toBeVisible()
    await page.keyboard.press('Escape')
    checked('Actual submission identity and edition-specific recognizable report terminology')
    assert.deepEqual(fixture.unexpected, [], 'Every API read is from the bounded synthetic fixture contract')
    assert.deepEqual(errors, [], 'No uncaught browser runtime errors')
    result.status = 'passed'
  } catch (error) {
    result.error = { name: error.name, message: error.message }
    try { await snapshot('failure') } catch (screenshotError) { result.screenshotError = screenshotError.message }
  } finally {
    fixture.releaseDecision?.(); fixture.releaseTrend?.()
    result.api = { reads: fixture.reads, writes: fixture.writes, unexpected: fixture.unexpected }
    result.pageErrors = errors
    await context.close()
  }
  return result
}

let browser
try {
  if (process.env.UI_SEVENTEEN_EXPECTED_COMMIT) assert.equal(commit, process.env.UI_SEVENTEEN_EXPECTED_COMMIT, 'Acceptance must run against the requested full source SHA')
  assert.equal(execFileSync('git', ['status', '--porcelain', '--', 'frontend/src', 'cn/frontend/src'], { cwd: repo, encoding: 'utf8' }).trim(), '', 'Production source must remain frozen')
  if (!compileOnly) browser = await chromium.launch({ headless: true,
    ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) })
  for (const edition of ['en', 'cn']) {
    const harness = await startHarness(edition, compileOnly)
    try {
      if (compileOnly) {
        report.compilation.push({ edition, status: 'compiled', actualComponents: ['AiAnalysisReviewDialog', 'ReceiptDialog', 'MedicalRecordManager', 'MedicalRecordReviewDialog'] })
        console.log(`${edition}: actual clinical SFC/API/theme harness compiled; browser assertions NOT EXECUTED`)
      } else for (const viewport of viewports) for (const theme of themes) {
        const result = await verifyCell(browser, harness, edition, viewport, theme)
        report.results.push(result); writeReport()
        console.log(`${edition} ${viewport}px ${theme}: ${result.status}`)
      }
    } finally { await harness.close() }
  }
  if (!compileOnly && report.results.some(result => result.status !== 'passed')) process.exitCode = 1
} catch (error) {
  report.executionError = { name: error.name, message: error.message }
  process.exitCode = 1
} finally {
  await browser?.close()
  report.finishedAt = new Date().toISOString()
  report.execution = compileOnly ? 'not-executed' : report.results.length ? 'executed' : 'not-executed'
  writeReport()
  console.log(`SHA-bound evidence: ${relative(repo, join(evidenceDir, 'clinical-results.json'))}`)
}
