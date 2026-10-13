// Real App.vue + router + Element Plus, with synthetic API responses only.
// Run: UI_SEVENTEEN_EVIDENCE_DIR=/absolute/output node frontend/tests/uiSeventeenShell.browser.mjs
// Compile without launching a browser: append --compile-only. This is NOT visual evidence.
// Removing patient IDs, selected-index navigation/IME guards, the nurse step,
// summary deep link, export invalidation, or dated filenames must fail a case.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, writeFile, rename, mkdtemp, rm, readdir } from 'node:fs/promises'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { chromium, expect } from '@playwright/test'
import { build, preview } from 'vite'
import vue from '@vitejs/plugin-vue'
import { assertReadableBox, assertDisjointBoxes, assertTouchTarget, measureReadableElements } from './uiNineGeometry.mjs'
import { assertNineVisualMeasurement } from '../../scripts/ui-nine-measurements.mjs'

const repo = fileURLToPath(new URL('../../', import.meta.url))
const evidence = resolve(process.env.UI_SEVENTEEN_EVIDENCE_DIR || join(repo, 'frontend/test-results/ui-seventeen'))
const compileOnly = process.argv.includes('--compile-only')
assert.ok(process.argv.slice(2).every(arg => arg === '--compile-only'), 'Unknown argument; only --compile-only is supported')
const themes = ['platform', 'white', 'blue', 'mint', 'sand', 'dark']
const itemIds = [3, 9, 12, 14, 16, 17]
// Independent render oracle from the approved six presets. Merely changing
// data-health-theme without applying its CSS must not satisfy the matrix.
const themeOracle = {
  platform: ['#ffffff', '#f4f7f6', '#f0f7f3', '#176b59'],
  white: ['#ffffff', '#f5f7fa', '#f0f7f3', '#176b59'],
  blue: ['#ffffff', '#f1f6fb', '#edf4fb', '#225b91'],
  mint: ['#ffffff', '#f0f7f3', '#edf7f1', '#176b59'],
  sand: ['#fffdf9', '#f8f2e9', '#f8f0e3', '#7a5429'],
  dark: ['#18262e', '#203139', '#203e37', '#7cd3b5'],
}
const matrix = ['en', 'cn'].flatMap(edition => [320, 375, 390, 768, 1365].flatMap(viewport => themes.map(theme => ({ edition, viewport, theme }))))
const runId = new Date().toISOString().replace(/[^0-9A-Za-z]/g, '-')
const sha = value => createHash('sha256').update(value).digest('hex')
const manifest = {
  commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim(),
  mode: compileOnly ? 'compile-only' : 'browser', runId, startedAt: new Date().toISOString(),
  syntheticOnly: true, results: [], plannedCells: matrix.length, sourceHashes: {},
  limitations: ['Synthetic transport verifies UI behavior, not backend authorization or clinical correctness.'],
}
await mkdir(evidence, { recursive: true })
async function persist() {
  const target = join(evidence, 'shell-results.json')
  await writeFile(`${target}.tmp`, JSON.stringify(manifest, null, 2))
  await rename(`${target}.tmp`, target)
}
await persist() // Replace any old success report before doing work.

const ownedFiles = [
  'App.vue', 'components/PatientSwitcher.vue', 'components/OnboardingGuide.vue',
  'components/AppearanceSelector.vue', 'views/DoctorWorkspace.vue', 'views/CareCenter.vue', 'views/FamilyHealthManager.vue',
  'views/DataExportManager.vue', 'views/HealthReportManager.vue', 'views/MedicalRecordManager.vue', 'views/MonitoringCenter.vue',
]
async function compileSources() {
  const compiled = []
  for (const edition of ['en', 'cn']) {
    const root = join(repo, edition === 'en' ? 'frontend' : 'cn/frontend')
    for (const file of ownedFiles) {
      const filename = join(root, 'src', file), source = await readFile(filename, 'utf8')
      manifest.sourceHashes[relative(repo, filename)] = sha(source)
      const { descriptor, errors } = parse(source, { filename })
      assert.deepEqual(errors, [], `${edition}/${file}: SFC parsing`)
      const id = `qa-${sha(filename).slice(0, 8)}`
      const script = compileScript(descriptor, { id })
      const template = compileTemplate({ source: descriptor.template.content, filename, id, compilerOptions: { bindingMetadata: script.bindings } })
      assert.deepEqual(template.errors, [], `${edition}/${file}: template compilation`)
      compiled.push(relative(repo, filename))
    }
    for (const file of ['utils/exportPresentation.js', 'utils/healthAppearance.js', 'styles/health-appearance.css', 'styles/mobile-optimization.css']) {
      const filename = join(root, 'src', file)
      manifest.sourceHashes[relative(repo, filename)] = sha(await readFile(filename))
    }
  }
  for (const filename of [fileURLToPath(import.meta.url), fileURLToPath(new URL('./uiNineGeometry.mjs', import.meta.url)), fileURLToPath(new URL('../../scripts/ui-nine-measurements.mjs', import.meta.url))]) manifest.sourceHashes[relative(repo, filename)] = sha(await readFile(filename))
  manifest.compiledFiles = compiled
}

const patientDisplayName = 'Synthetic Same Name Alexandra Catherine Montgomery-Sutherland'
const patients = [{ id: 99011, patientName: patientDisplayName }, { id: 99012, patientName: patientDisplayName }]
function menusFor(edition) {
  const rows = [
    [1, 0, 'Family Care', '家庭照护', '/care', 'care'],
    [2, 0, 'Health Overview', '健康概览', '/monitoring', 'monitoring'],
    [3, 0, 'Care Plan', '照护计划', '/family-health', 'family-health'],
    [31, 3, 'Visit Summary', '就诊摘要', '/family-health?tab=summary', 'care-summary'],
    [4, 0, 'Health Analytics', '健康分析', '/health-analysis', 'health-analysis'],
    [41, 4, 'Nutrition Diary', '营养日记', '/health-analysis?tab=nutrition', 'health-nutrition'],
    [42, 4, 'Data Export', '数据导出', '/health-analysis?tab=data-export', 'analysis-data-export'],
    [43, 4, 'Health Report', '健康报告', '/health-analysis?tab=health-report', 'analysis-health-report'],
    [5, 0, 'Nursing Follow-up', '护理随访', '/nurse-workspace', 'nurse-workspace'],
    [7, 0, 'Doctor Workspace', '医生工作台', '/doctor-workspace', 'doctor-workspace'],
    [6, 0, 'Medical Records', '医疗记录', '/medical-record', 'clinical-record'],
    [61, 6, 'Result Trends', '指标趋势', '/medical-record?tab=trend', 'clinical-trend'],
    [62, 6, 'Record List', '记录列表', '/medical-record?tab=list', 'clinical-record-list'],
  ]
  return rows.map(([id, parentId, en, cn, menuPath, menuCode]) => ({ id, parentId, menuName: edition === 'en' ? en : cn, menuPath, menuCode }))
}

async function serve(edition, compileOnly = false) {
  const root = join(repo, edition === 'en' ? 'frontend' : 'cn/frontend')
  const output = await mkdtemp(join(repo, 'frontend/.shell-preview-'))
  const config = {
    configFile: false, root, base: edition === 'en' ? '/' : '/cn/', logLevel: 'error',
    plugins: [vue()],
    resolve: { alias: { '@': join(root, 'src') }, dedupe: ['vue', 'vue-router', 'element-plus'] },
    build: { outDir: output, emptyOutDir: true, minify: false },
    preview: { host: '127.0.0.1', port: 0, strictPort: false },
  }
  let server
  const close = async () => {
    if (server) await new Promise((done, reject) => server.httpServer.close(error => error ? reject(error) : done()))
    await rm(output, { recursive: true, force: true })
  }
  try {
    // Preview the compiled real app: no Vite development client, HMR socket,
    // proxy, or upstream service is configured. Every API remains intercepted.
    await build(config)
    async function assertNoDevClient(directory) {
      for (const entry of await readdir(directory, { withFileTypes: true })) {
        const path = join(directory, entry.name)
        if (entry.isDirectory()) await assertNoDevClient(path)
        else if (/\.(html|js)$/.test(entry.name)) assert.doesNotMatch(await readFile(path, 'utf8'), /\/@vite\/client|vite\/dist\/client\/client\.mjs/, 'Compiled preview must contain no Vite development client')
      }
    }
    await assertNoDevClient(output)
    if (compileOnly) return { server: { close } }
    server = await preview(config)
    const address = server.httpServer.address()
    assert.ok(address && typeof address === 'object', 'Preview must listen on a local TCP port')
    return { server: { close }, origin: `http://127.0.0.1:${address.port}`, prefix: edition === 'en' ? '' : '/cn' }
  } catch (error) { await close(); throw error }
}

async function openCase(browser, host, cell, result) {
  const menus = menusFor(cell.edition)
  const context = await browser.newContext({ viewport: { width: cell.viewport, height: 900 },
    timezoneId: 'UTC', locale: cell.edition === 'en' ? 'en-US' : 'zh-CN',
    reducedMotion: 'reduce', serviceWorkers: 'block', acceptDownloads: true })
  await context.addInitScript(({ menus }) => {
    // Initialize once per fresh test context. Reload must preserve real patient
    // changes and guide progress, rather than silently resetting their state.
    if (sessionStorage.getItem('synthetic-shell-initialized')) return
    const values = {
      token: 'synthetic-local-qa-token', userId: '99001', username: 'synthetic-nurse', realName: 'Synthetic Nurse',
      currentPatientId: '99011', userMenus: JSON.stringify(menus.map(x => x.menuPath)),
      userMenuNames: JSON.stringify(menus.map(x => x.menuName)), userRoleCodes: '["nurse"]',
      permissionSession: 'synthetic-local-qa-token', 'care-onboarding-prompt:99001:nurse': 'seen',
      'chengxin-health-appearance-v1': '{"version":1,"theme":"platform"}',
    }
    for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value)
    sessionStorage.setItem('synthetic-shell-initialized', 'yes')
  }, { menus })
  const state = { role: 'nurse', exportMode: 'success', capabilities: true, pendingExports: [], apiCalls: [], downloads: [], errors: [], violations: [] }
  const csv = patientId => `Date,Synthetic record\n2026-10-01,Synthetic export ${patientId}\n`
  const json = (route, data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status, data }) })
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method()
    if (url.origin !== host.origin) {
      state.violations.push(`Blocked external request: ${method} ${url.origin}${url.pathname}`)
      return route.abort('blockedbyclient')
    }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    let payload = null
    try { payload = request.postDataJSON() } catch { /* GET has no JSON body. */ }
    state.apiCalls.push({ method, path: url.pathname, query: Object.fromEntries(url.searchParams), payload })
    const patientId = Number(url.searchParams.get('patientId') || payload?.patientId || 99011)
    if (!patients.some(patient => patient.id === patientId)) {
      state.violations.push(`Unexpected synthetic patient: ${patientId}`)
      return json(route, null, 403)
    }
    if (method === 'GET') {
      const data = {
        '/api/auth/info': { user: { id: 99001, username: 'synthetic-nurse', realName: 'Synthetic Nurse' }, roles: [{ roleCode: state.role, roleName: state.role }], menus },
        '/api/platform-branding/public': { platformName: 'Synthetic Health QA', organizationName: 'Synthetic fixtures only', logo: '/logo.svg' },
        '/api/patient/names': patients,
        '/api/patient/specialty-menu-scope': { patientId, restrictedPaths: [], allowedPaths: menus.map(x => x.menuPath) },
        '/api/care-plans/capabilities': { enabled: state.capabilities },
        '/api/doctor-workspace/summary': { patients: [], reviewQueue: [], assignedPatients: 0, pendingReviews: 0 },
        '/api/care-plans': { items: [], nextCursor: null },
        '/api/care/home': patients.map(patient => ({ patient: { id: patient.id, name: patient.patientName }, intakes: [] })),
        '/api/care/context': { patient: { id: patientId, name: patientDisplayName, userId: 99001 }, medications: [], members: [], reports: [], stocks: [], intakes: [], items: [] },
        '/api/family-health/intakes': [], '/api/family-health/timeline': [], '/api/family-health/target': {},
        '/api/medical-record/list': [], '/api/medical-record/items': ['Synthetic laboratory item'],
        '/api/monitoring/snapshot': { patient: { id: patientId, name: patientDisplayName }, generatedAt: '2026-10-11T08:00:00Z', lastDataAt: '2026-10-11T07:30:00Z', overallStatus: 'STABLE', statusLabel: 'Synthetic stable status', signals: [], metrics: { activeAlertCount: 9, todayTaskCount: 0 },
          activeAlerts: Array.from({ length: 9 }, (_, index) => ({ id: 99040 + index, patientId, title: `Synthetic alert ${index + 1}`, level: 'INFO', value: 'Synthetic source evidence only', triggeredAt: '2026-10-11T07:30:00Z', status: 'PENDING' })), todayTasks: [], recentEvents: [], careSuggestions: [],
          vitalTrend: [{ date: '2026-10-10', time: '09:15', systolic: 144, diastolic: 83, glucose: 108, glucoseUnit: 'mg/dL', abnormal: true }, { date: '2026-10-11', time: '07:30', systolic: 120, diastolic: 80, glucose: 5.2, glucoseUnit: 'mmol/L', abnormal: false }] },
        // HealthAnalysisManager mounts its default complication pane before
        // onMounted selects the requested export/report tab. Keep those
        // transient reads explicit; never permit a generic API fallback.
        '/api/complication/list': [], '/api/complication/stats': null, '/api/dialysis/list': [],
      }
      if (Object.hasOwn(data, url.pathname)) return json(route, data[url.pathname])
    }
    if (method === 'POST' && url.pathname === '/api/data-export/csv') {
      if (state.exportMode === 'delay') { state.pendingExports.push({ route, patientId }); return }
      if (state.exportMode === 'fail') return json(route, { message: 'Synthetic export failure' }, 500)
      return route.fulfill({ status: 200, contentType: 'text/csv', body: state.exportMode === 'empty' ? 'Date,Synthetic record\n' : csv(patientId) })
    }
    if (method === 'POST' && url.pathname === '/api/health-report/generate') {
      if (payload?.format !== 'html') {
        state.violations.push('Only the synthetic HTML report is enabled by this fixture')
        return json(route, null, 599)
      }
      return route.fulfill({ status: 200, contentType: 'text/html', body: `<!doctype html><html><body><h1>Synthetic report ${patientId}</h1></body></html>` })
    }
    state.violations.push(`Unhandled API request: ${method} ${url.pathname}`)
    return json(route, { message: 'Unhandled synthetic request; never forwarded' }, 599)
  })
  // Compiled preview has no dev client. Any socket is unexpected and closed
  // without connecting to an upstream service; external attempts stay fatal.
  await context.routeWebSocket('**/*', socket => {
    const url = new URL(socket.url())
    if (url.host !== new URL(host.origin).host) state.violations.push(`Blocked external WebSocket: ${url.origin}${url.pathname}`)
    else state.violations.push(`Unexpected local WebSocket: ${url.pathname}`)
    socket.close()
  })
  const page = await context.newPage()
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => state.errors.push(error.message))
  page.on('download', download => state.downloads.push(download.suggestedFilename()))
  const t = (en, cn) => cell.edition === 'en' ? en : cn
  const go = async path => { await page.goto(host.origin + host.prefix + path); await expect(page.locator('.patient-switcher')).toBeVisible() }
  async function capture(name, { fullPage = true } = {}) {
    const file = join('shell', runId, `${cell.edition}-${cell.viewport}-${cell.theme}-${name}.png`)
    const destination = join(evidence, file)
    await mkdir(dirname(destination), { recursive: true })
    await page.screenshot({ path: destination, fullPage, animations: 'disabled' })
    const digest = sha(await readFile(destination))
    result.screenshots.push(file); result.screenshotHashes[file] = digest
  }
  async function fit() {
    const dimensions = await page.evaluate(() => ({ viewport: innerWidth, height: innerHeight, scroll: document.documentElement.scrollWidth }))
    assert.equal(dimensions.viewport, cell.viewport, 'The actual browser viewport matches this matrix cell')
    assert.ok(dimensions.scroll <= dimensions.viewport + 1, `Page overflows: ${JSON.stringify(dimensions)}`)
    result.renderedViewport = dimensions
  }
  async function guideBounds(name) {
    // Visibility alone accepts a button clipped below the viewport. Measure
    // without scrolling/clicking it into view, and hit-test enabled controls.
    let snapshot
    await expect.poll(async () => {
      snapshot = await page.locator('.guide-card').evaluate(card => {
        const rect = node => {
          const box = node.getBoundingClientRect()
          return { left: box.left, top: box.top, right: box.right, bottom: box.bottom, width: box.width, height: box.height }
        }
        const within = box => box.width > 0 && box.height > 0 && box.left >= 0 && box.top >= 0 && box.right <= innerWidth && box.bottom <= innerHeight
        const describe = node => {
          if (!node) return null
          const style = getComputedStyle(node)
          return { tag: node.tagName, id: node.getAttribute('id'), classes: node.getAttribute('class'),
            role: node.getAttribute('role'), label: node.getAttribute('aria-label'), testId: node.getAttribute('data-testid'),
            ...rect(node), position: style.position, zIndex: style.zIndex, pointerEvents: style.pointerEvents,
            opacity: style.opacity, transform: style.transform, isolation: style.isolation }
        }
        const ancestors = node => {
          const chain = []
          for (let parent = node?.parentElement; parent; parent = parent.parentElement) chain.push(describe(parent))
          return chain
        }
        const controls = [...card.querySelectorAll('.guide-close, footer button')].map(node => {
          const box = rect(node), hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
          return { label: node.getAttribute('aria-label'), disabled: node.disabled, ...box, inViewport: within(box), unobscured: Boolean(hit && node.contains(hit)),
            hitElement: describe(hit), hitAncestors: ancestors(hit) }
        })
        const parts = [card, card.querySelector('h2'), card.querySelector('footer')].map(node => {
          const box = rect(node)
          return { tag: node.tagName, ...box, inViewport: within(box) }
        })
        const content = card.querySelector('.guide-content')
        return { viewport: { width: innerWidth, height: innerHeight }, parts, controls,
          layer: describe(card.closest('.guide-layer')), highlightedTargets: [...document.querySelectorAll('.guide-target')].map(describe),
          content: content ? { ...rect(content), scrollHeight: content.scrollHeight, clientHeight: content.clientHeight } : null }
      })
      ;(result.guideBounds ||= {})[name] = snapshot
      return snapshot.viewport.width === cell.viewport && snapshot.controls.length === 3 &&
        snapshot.parts.every(part => part.inViewport) && snapshot.controls.every(control => control.inViewport && (control.disabled || control.unobscured))
    }, { message: `${name}: guide, heading, footer and navigation must be inside the viewport with usable controls` }).toBe(true)
  }
  async function toastBounds(id) {
    const measurements = result.patientSwitchToastBounds ||= []
    const measurement = { patientId: id }; measurements.push(measurement)
    await expect(page.locator('.patient-switch-toast:visible')).toHaveCount(1)
    await expect(page.locator('.patient-switch-toast:visible')).toContainText(t('Switched to', '已切换到'))
    await expect(page.locator('.patient-switch-toast:visible')).toContainText(patientDisplayName)
    await expect(page.locator('.patient-switch-toast:visible')).toContainText(`#${id}`)
    let snapshot
    await expect.poll(async () => {
      snapshot = await page.locator('.el-message:visible').evaluateAll(nodes => ({
        viewport: { width: innerWidth, height: innerHeight },
        patientContext: (() => { const box = document.querySelector('.patient-switcher').getBoundingClientRect(); return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height } })(),
        topbar: (() => { const box = document.querySelector('.workspace-topbar').getBoundingClientRect(); return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height } })(),
        messages: nodes.map(node => {
          const box = node.getBoundingClientRect(), content = node.querySelector('.el-message__content')
          const textBox = content?.getBoundingClientRect()
          return { patientSwitch: node.classList.contains('patient-switch-toast'), text: node.textContent, left: box.left, right: box.right, top: box.top, bottom: box.bottom,
            opacity: Number(getComputedStyle(node).opacity), transform: getComputedStyle(node).transform,
            contentLeft: textBox?.left, contentRight: textBox?.right,
            contentScrollWidth: content?.scrollWidth, contentClientWidth: content?.clientWidth }
        }),
      }))
      Object.assign(measurement, snapshot)
      return snapshot.viewport.width === cell.viewport && snapshot.messages.length > 0 && snapshot.messages.every(message =>
        message.opacity === 1 && message.left >= 0 && message.right <= snapshot.viewport.width &&
        message.top >= 0 && message.bottom <= snapshot.viewport.height && message.contentLeft >= message.left &&
        message.contentRight <= message.right && message.contentScrollWidth <= message.contentClientWidth + 1)
    }, { message: 'Actual patient-switch notifications and their full text must fit the viewport' }).toBe(true)
    const patientMessages = snapshot.messages.filter(message => message.patientSwitch)
    assert.equal(patientMessages.length, 1, 'Repeated patient switches keep exactly one current patient notification')
    for (const message of patientMessages) {
      assertDisjointBoxes(message, snapshot.patientContext, 'Patient toast and selected identity')
      assertDisjointBoxes(message, snapshot.topbar, 'Patient toast and fixed header')
      assert.ok(message.top >= snapshot.topbar.bottom + 11, 'Patient toast follows the post-render toolbar with its intended 12px gap')
      assert.match(message.text, new RegExp(`#${id}\\b`))
      assert.doesNotMatch(message.text, new RegExp(`#${id === 99011 ? 99012 : 99011}\\b`), 'No stale contradictory record identity remains')
    }
    const [identity] = await page.locator('.patient-switcher__record').evaluateAll(measureReadableElements)
    assertReadableBox(identity, cell.viewport, 'Persistent current patient record ID')
    assert.equal(identity.text, `#${id}`)
    measurement.identity = identity
  }
  async function choose(select, name) {
    await select.click()
    await page.getByRole('option', { name, exact: true }).click()
  }
  async function patient(id) {
    await choose(page.locator('.patient-switcher .el-select'), `${patientDisplayName} · ${t('Record', '档案')} #${id}`)
    await expect.poll(() => page.evaluate(() => localStorage.getItem('currentPatientId'))).toBe(String(id))
    await expect(page.locator('.patient-switcher')).toContainText(`#${id}`)
    await toastBounds(id)
  }
  async function date(scope, value = '2026-10') {
    const input = page.locator('.el-date-editor input').first()
    await input.fill(value); await input.press('Enter'); await scope.click()
    await expect(scope).toContainText('2026-10-01'); await expect(scope).toContainText('2026-10-31')
  }
  async function noExtraDownload(count) {
    // A negative event assertion has a bounded observation window after the
    // fulfilled response and UI outcome, not an immediate zero-count check.
    await page.waitForTimeout(250)
    assert.equal(state.downloads.length, count, 'No stale, failed, or empty download may start')
  }
  return { page, context, state, t, go, capture, fit, guideBounds, choose, patient, toastBounds, date, noExtraDownload, csv }
}

function acceptNine(result, item, measurement) {
  assertNineVisualMeasurement('shell', item, measurement, result.viewport)
  ;(result.nineMeasurements ||= {})[item] = measurement
  result.nineItemIds = [...new Set([...(result.nineItemIds || []), item])].sort((a, b) => a - b)
}
async function formLabelGeometry(page, cell, surface) {
  const labels = page.locator('.report-export-form .el-form-item__label:visible')
  assert.ok(await labels.count() >= 4, `${surface} exercises at least four actual form labels`)
  const measured = []
  for (const label of await labels.all()) {
    await label.scrollIntoViewIfNeeded()
    const [box] = await label.evaluateAll(measureReadableElements)
    assertReadableBox(box, cell.viewport, `${surface}: ${box.text}`)
    const layout = await label.evaluate(element => {
      const item = element.closest('.el-form-item'), content = item.querySelector('.el-form-item__content'), box = content.getBoundingClientRect()
      const pseudo = getComputedStyle(element, '::before'), style = getComputedStyle(element)
      return { content: { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height }, required: item.classList.contains('is-required'), marker: pseudo.content, markerWidth: parseFloat(pseudo.width) || 0, markerMargin: parseFloat(pseudo.marginRight) || 0, lineHeight: parseFloat(style.lineHeight), labelWidth: style.width, labelWhiteSpace: style.whiteSpace }
    })
    assert.ok(layout.content.width > 0 && layout.content.left >= -1 && layout.content.right <= cell.viewport + 1, `${surface} control fits the actual viewport`)
    if (cell.viewport <= 768) assert.ok(box.bottom <= layout.content.top + 1, `${surface} mobile labels are above their controls`)
    else assert.ok(box.right <= layout.content.left + 1, `${surface} desktop labels have their own adaptive column`)
    const lineTops = [...new Set(box.textBoxes.map(text => Math.round(text.top)))]
    assert.equal(lineTops.length, 1, `${surface} label text is a complete unbroken line`)
    if (layout.required) {
      assert.match(layout.marker, /\*/, `${surface} required marker is still present`)
      assert.ok(box.textBoxes[0].top < box.top + layout.lineHeight, `${surface} required marker cannot occupy a separate first line`)
      assert.ok(box.width >= box.textBoxes[0].right - box.textBoxes[0].left + layout.markerWidth + layout.markerMargin - 1, `${surface} label reserves actual width for marker and text`)
    }
    measured.push({ ...box, ...layout })
  }
  assert.ok(measured.some(label => label.required), `${surface} measures actual required labels`)
  return { surface, layout: cell.viewport <= 768 ? 'labels-above-controls' : 'adaptive-label-columns', labels: measured }
}
async function reportActionGeometry(page, cell) {
  const mode = page.locator('.execution-report-choice'), generate = page.locator('.report-export-actions .el-button--primary')
  await expect(mode).toHaveClass(/el-button/); await expect(mode).not.toHaveClass(/el-button--primary/)
  await expect(generate).toHaveCount(1); await expect(generate).toBeEnabled()
  const [secondary] = await mode.evaluateAll(measureReadableElements), [primary] = await generate.evaluateAll(measureReadableElements)
  for (const [name, box] of [['Secondary report mode', secondary], ['Generate report', primary]]) { assertReadableBox(box, cell.viewport, name); assertTouchTarget(box, name) }
  assert.notEqual(primary.background, secondary.background, 'Generate is visually primary and report mode uses a distinct secondary treatment')
  // Native keyboard activation proves the secondary control has kept its action.
  await mode.focus(); await expect(mode).toBeFocused(); await page.keyboard.press('Enter')
  await expect(mode).toHaveAttribute('aria-expanded', 'true'); await expect(page.locator('.report-export-form')).toHaveCount(0)
  await mode.focus(); await page.keyboard.press('Enter')
  await expect(mode).toHaveAttribute('aria-expanded', 'false'); await expect(generate).toBeVisible()
  return { secondary, primary, activation: ['keyboard-expand', 'keyboard-collapse'] }
}

async function exerciseCase(s, cell, result) {
  const { page, state, t, go, capture, fit, guideBounds, choose, patient, toastBounds, date, noExtraDownload } = s
  const checked = name => result.assertions.push(name)
  await go('/health-analysis?tab=data-export')
  await expect(page.locator('.export-scope')).toBeVisible()
  await page.locator('.appearance-selector select').selectOption(cell.theme)
  await expect(page.locator('html')).toHaveAttribute('data-health-theme', cell.theme)
  await expect(page.locator('.appearance-selector select')).toHaveValue(cell.theme)
  result.themeTokens = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement)
    return Object.fromEntries(['--paper', '--surface-subtle', '--care-50', '--care-700'].map(name => [name, style.getPropertyValue(name).trim().toLowerCase()]))
  })
  assert.deepEqual(Object.values(result.themeTokens), themeOracle[cell.theme], 'Computed colors must equal the requested real theme, not just its dataset label')
  const hex = themeOracle[cell.theme][2].slice(1)
  const expectedScopeBackground = `rgb(${[0, 2, 4].map(start => parseInt(hex.slice(start, start + 2), 16)).join(', ')})`
  await expect.poll(() => page.locator('.export-scope').evaluate(node => getComputedStyle(node).backgroundColor)).toBe(expectedScopeBackground)
  result.renderedScopeStyle = await page.locator('.export-scope').evaluate(node => ({ backgroundColor: getComputedStyle(node).backgroundColor, color: getComputedStyle(node).color }))
  assert.equal(result.renderedScopeStyle.backgroundColor, expectedScopeBackground, 'The real scope panel uses the selected theme in computed rendered styles')
  result.styleSignature = sha(JSON.stringify({ tokens: result.themeTokens, rendered: result.renderedScopeStyle }))
  await fit()
  checked('The real theme selector applies the requested theme and stylesheet tokens')
  const exportLabels = await formLabelGeometry(page, cell, 'data-export')
  await capture('nine-export-labels')

  await page.locator('.patient-switcher .el-select').click()
  const optionIdentities = []
  result.patientOptionBounds = []
  for (const id of [99011, 99012]) {
    const option = page.getByRole('option', { name: `${patientDisplayName} · ${t('Record', '档案')} #${id}`, exact: true })
    await expect(option).toBeVisible()
    const measurement = { id }
    result.patientOptionBounds.push(measurement)
    // Wait only for the real popup's placement/transition, never for acceptable
    // bounds. Save every last observation before an assertion can fail.
    await expect.poll(async () => {
      const [identity] = await option.locator('.patient-option__record').evaluateAll(measureReadableElements)
      const [name] = await option.locator('.patient-option__name').evaluateAll(measureReadableElements)
      const dropdown = await option.evaluate(element => {
        const popup = element.closest('.el-popper'), box = popup.getBoundingClientRect()
        return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height,
          placement: popup.getAttribute('data-popper-placement'), opacity: Number(getComputedStyle(popup).opacity),
          animating: popup.getAnimations().some(animation => animation.playState === 'running'),
          scrollWidth: document.documentElement.scrollWidth, viewport: innerWidth }
      })
      Object.assign(measurement, { identity, name, dropdown })
      return Boolean(dropdown.placement) && dropdown.opacity === 1 && !dropdown.animating
    }, { message: 'Patient dropdown has finished its real opening layout' }).toBe(true)
    const { identity, name, dropdown } = measurement
    assert.ok(dropdown.left >= -1 && dropdown.right <= cell.viewport + 1, 'The whole patient dropdown stays inside the viewport')
    assertReadableBox(identity, cell.viewport, `Long-name patient option #${id}`)
    assertReadableBox(name, cell.viewport, `Full patient name for #${id}`)
    assert.equal(name.text, patientDisplayName, 'The full long name remains readable without hover')
    assert.ok(identity.text.endsWith(`#${id}`)); optionIdentities.push({ id, ...identity })
  }
  await fit()
  await capture('patient-identities')
  await page.keyboard.press('Escape')
  await choose(page.locator('.patient-switcher .el-select'), t('All patients', '全部患者'))
  await expect(page.locator('.patient-switcher__record')).toHaveCount(0)
  await expect(page.locator('.patient-switch-toast:visible')).toContainText(t('Showing all patients.', '正在显示全部患者。'))
  const allPatientsHeader = await page.locator('.workspace-topbar').evaluate(element => { const box = element.getBoundingClientRect(); return { top: box.top, bottom: box.bottom, height: box.height, width: box.width } })
  await patient(99012)
  result.patientSwitchAllContext = { allPatientsHeader, selected: result.patientSwitchToastBounds.at(-1) }
  await capture('patient-switch-toast', { fullPage: false }); await patient(99011)
  // Two actual selections within one notification lifetime must not leave a stale identity.
  await choose(page.locator('.patient-switcher .el-select'), `${patientDisplayName} · ${t('Record', '档案')} #99012`)
  await choose(page.locator('.patient-switcher .el-select'), `${patientDisplayName} · ${t('Record', '档案')} #99011`)
  await toastBounds(99011)
  result.patientRapidSwitch = result.patientSwitchToastBounds.at(-1)
  await expect(page.locator('.export-scope')).toContainText(patientDisplayName)
  await expect(page.locator('.export-scope')).toContainText('#99011')
  checked('Duplicate-name authorized patients are disambiguated by record ID, selection stays in scope, and actual patient-switch toasts and text fit the viewport')

  const opener = page.locator('[data-guide="feature-search"]:visible')
  await expect(opener).toHaveCount(1)
  await opener.click()
  const search = page.locator('.workspace-search-dialog input'), links = page.locator('#feature-search-results > a')
  await expect(search).toBeFocused()
  await expect(page.locator('.patient-switch-toast:visible')).toHaveCount(0)
  const searchActions = await links.evaluateAll(nodes => nodes.slice(0, 3).map(node => {
    const box = node.getBoundingClientRect(), points = [[box.left + 4, box.top + 4], [box.right - 4, box.bottom - 4]]
    return { label: node.textContent, top: box.top, bottom: box.bottom, unobscured: points.every(([x, y]) => node.contains(document.elementFromPoint(x, y))) }
  }))
  result.searchUpperActionBounds = searchActions
  assert.ok(searchActions.length === 3 && searchActions.every(action => action.unobscured), 'Upper search results must stay clickable after a patient switch')
  const count = await links.count()
  assert.ok(count > 2, 'Synthetic permission menu provides multiple real search results')
  await expect(page.locator('.workspace-search-dialog [role="status"]')).toContainText(String(count))
  await search.press('ArrowUp')
  await expect(page.locator('.is-search-active')).toHaveAttribute('id', `feature-result-${count - 1}`)
  await search.press('ArrowDown'); await search.press('ArrowDown')
  await expect(page.locator('.is-search-active')).toHaveAttribute('id', 'feature-result-1')
  const beforeIme = page.url()
  await search.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true })
  await search.dispatchEvent('keydown', { key: 'ArrowDown', code: 'ArrowDown', isComposing: true })
  await expect(page.locator('.is-search-active')).toHaveAttribute('id', 'feature-result-1')
  await expect(page.locator('.workspace-search-dialog')).toBeVisible()
  assert.equal(page.url(), beforeIme, 'IME confirmation must not navigate')
  await search.fill('SyntheticNoSuchFeature')
  await expect(links).toHaveCount(0); await expect(page.locator('.workspace-search-dialog [role="status"]')).toContainText('0')
  await search.press('Enter'); assert.equal(page.url(), beforeIme)
  await search.fill(''); await expect(page.locator('.is-search-active')).toHaveAttribute('id', 'feature-result-0')
  await search.press('Tab'); await expect(page.locator('#feature-result-0')).toBeFocused()
  await page.keyboard.press('Shift+Tab'); await expect(search).toBeFocused()
  const hrefs = await links.evaluateAll(nodes => nodes.map(node => node.getAttribute('href')))
  const exportIndex = hrefs.findIndex(href => href.endsWith('/health-analysis?tab=data-export'))
  assert.ok(exportIndex > 0, 'Selected-Enter target must differ from the first result')
  for (let index = 0; index < exportIndex; index++) await search.press('ArrowDown')
  await expect(page.locator('.is-search-active')).toHaveAttribute('id', `feature-result-${exportIndex}`)
  await fit(); await capture('keyboard-search')
  await search.press('Enter')
  await expect(page.locator('.workspace-search-dialog')).toBeHidden()
  assert.equal(new URL(page.url()).searchParams.get('tab'), 'data-export')
  await opener.click(); await expect(search).toBeFocused(); await search.press('Escape')
  await expect(page.locator('.workspace-search-dialog')).toBeHidden(); await expect(opener).toBeFocused()
  checked('Search count, Up/Down wrapping, selected Enter, query reset, IME, Tab, and Escape focus work through DOM controls')

  async function startGuide() {
    if (cell.viewport <= 768) await page.getByRole('button', { name: t('Open navigation menu', '打开导航菜单'), exact: true }).click()
    await page.getByRole('button', { name: t('Start guided tour', '开始操作指引'), exact: true }).click()
    await expect(page.locator('.guide-card')).toBeVisible()
  }
  await startGuide()
  await expect(page.locator('.guide-card h2')).toHaveText(t('Review nursing follow-up tasks', '查看护理随访任务'))
  await expect.poll(() => new URL(page.url()).pathname).toBe(`${cell.edition === 'cn' ? '/cn' : ''}/nurse-workspace`)
  await expect(page.locator('[data-testid="collaboration-tasks"].guide-target')).toBeVisible()
  await guideBounds('nurse-guide')
  const refreshTasks = page.getByTestId('reload-tasks')
  await expect(refreshTasks).toBeEnabled()
  await expect.poll(async () => {
    result.nurseGuideActionBounds = await refreshTasks.evaluate(node => {
      const box = node.getBoundingClientRect(), card = document.querySelector('.guide-card').getBoundingClientRect()
      const points = [[box.left + 4, box.top + 4], [box.right - 4, box.top + 4], [box.left + 4, box.bottom - 4], [box.right - 4, box.bottom - 4]]
      return { top: box.top, bottom: box.bottom, left: box.left, right: box.right, cardTop: card.top, cardBottom: card.bottom,
        inViewport: box.top >= 0 && box.bottom <= innerHeight && box.left >= 0 && box.right <= innerWidth,
        unobscured: points.every(([x, y]) => node.contains(document.elementFromPoint(x, y))),
        clearOfCard: box.bottom + 8 <= card.top || box.top >= card.bottom + 8 }
    })
    return result.nurseGuideActionBounds.inViewport && result.nurseGuideActionBounds.unobscured && result.nurseGuideActionBounds.clearOfCard
  }, { message: 'The nursing Refresh plan tasks action must remain visible and hit-testable while the guide is open' }).toBe(true)
  await capture('nurse-guide', { fullPage: false })
  if (cell.viewport < 720) {
    const returnedScroll = await page.evaluate(() => { window.scrollTo({ top: 0, behavior: 'instant' }); return scrollY })
    await expect.poll(async () => {
      result.nurseGuideAfterScroll = await refreshTasks.evaluate(node => {
        const box = node.getBoundingClientRect(), card = document.querySelector('.guide-card').getBoundingClientRect()
        const points = [[box.left + 4, box.top + 4], [box.right - 4, box.top + 4], [box.left + 4, box.bottom - 4], [box.right - 4, box.bottom - 4]]
        return { scrollY, top: box.top, bottom: box.bottom, cardTop: card.top, cardBottom: card.bottom,
          clearOfCard: box.bottom + 8 <= card.top || box.top >= card.bottom + 8,
          unobscured: points.every(([x, y]) => node.contains(document.elementFromPoint(x, y))) }
      })
      return result.nurseGuideAfterScroll.clearOfCard && result.nurseGuideAfterScroll.unobscured
    }, { message: 'Ordinary scroll-back must keep the Refresh action clear without moving the page again' }).toBe(true)
    assert.equal(result.nurseGuideAfterScroll.scrollY, returnedScroll, 'The passive guide cannot undo the user’s scroll position')
    await guideBounds('nurse-guide-after-scroll'); await capture('nurse-guide-after-scroll', { fullPage: false })
  }
  await refreshTasks.click(); await expect(refreshTasks).toBeEnabled()
  await page.getByRole('button', { name: t('Confirm step and continue', '确认本步骤并继续'), exact: true }).click()
  const guidedSearch = page.locator('[data-guide="feature-search"].guide-target')
  await expect(guidedSearch).toHaveCount(1); await expect(guidedSearch).toBeVisible()
  assert.equal(await guidedSearch.evaluate(node => getComputedStyle(node).visibility), 'visible')
  const target = await guidedSearch.boundingBox()
  assert.ok(target && target.x >= 0 && target.x + target.width <= cell.viewport + 1 && target.y >= 0 && target.y + target.height <= 901, 'Guide highlights a visible in-viewport search control')
  await guideBounds('visible-search-guide'); await capture('visible-search-guide', { fullPage: false })
  await page.getByRole('button', { name: t('Pause guide', '暂停指引'), exact: true }).click()
  state.capabilities = false
  await page.evaluate(() => localStorage.removeItem('care-onboarding:99001:nurse'))
  await go('/nurse-workspace'); await startGuide()
  // The actual nursing task region remains present when the capability is
  // disabled; do not invent an absent target from a disabled service state.
  await expect(page.locator('[data-testid="collaboration-tasks"].guide-target')).toBeVisible()
  await expect(page.locator('.guide-card [role="status"]')).toHaveCount(0)
  await guideBounds('disabled-nurse-guide-target'); await capture('disabled-nurse-guide-target', { fullPage: false })
  await page.getByRole('button', { name: t('Pause guide', '暂停指引'), exact: true }).click()
  state.capabilities = true
  // Exercise the real missing-target branch using an empty doctor review queue.
  // Unlike the disabled nurse region, this page genuinely has no review button.
  state.role = 'doctor'
  await page.evaluate(() => {
    localStorage.setItem('userRoleCodes', '["doctor"]')
    localStorage.setItem('care-onboarding-prompt:99001:doctor', 'seen')
    localStorage.removeItem('care-onboarding:99001:doctor')
  })
  await go('/doctor-workspace'); await expect(page.locator('.doctor-page')).toBeVisible()
  await expect(page.locator('.review-actions .el-button')).toHaveCount(0)
  await startGuide()
  await expect(page.locator('.guide-card [role="status"]')).toContainText(t('No actionable target', '当前没有可操作的目标'))
  await expect(page.locator('.guide-target')).toHaveCount(0)
  await expect(page.locator('.guide-anchor')).toHaveCount(0)
  await guideBounds('empty-doctor-guide-target'); await capture('empty-doctor-guide-target', { fullPage: false })
  await page.getByRole('button', { name: t('Pause guide', '暂停指引'), exact: true }).click()
  state.role = 'nurse'
  await page.evaluate(() => localStorage.setItem('userRoleCodes', '["nurse"]'))
  checked('Guide card, heading, close and footer controls fit the viewport and enabled controls are unobscured. Nurse guide uses nursing scope, highlights the actual responsive search control, retains the disabled nursing region, and explains the genuinely absent empty doctor review target')

  await go('/care'); await patient(99012)
  const questions = page.getByRole('tab', { name: t('Questions', '就诊问题'), exact: true })
  await expect(questions).toBeEnabled(); await questions.click()
  await page.getByRole('button', { name: t('Prepare visit summary', '准备就诊摘要'), exact: true }).click()
  await expect.poll(() => new URL(page.url()).pathname + new URL(page.url()).search).toBe(`${cell.edition === 'cn' ? '/cn' : ''}/family-health?tab=summary`)
  await expect(page.getByRole('tab', { name: t('Visit Summary', '就诊摘要'), exact: true })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('.patient-switcher')).toContainText('#99012')
  await fit(); await capture('summary-deep-link')
  checked('Prepare summary lands on the summary tab while retaining the current patient')

  await go('/health-analysis?tab=data-export')
  const scope = page.locator('.export-scope'), form = scope.locator('..')
  await choose(form.locator('.el-select').first(), t('Nutrition Diary', '营养日记'))
  await date(scope)
  await expect(scope).toContainText('#99012'); await expect(scope).toContainText(t('Nutrition Diary', '营养日记'))
  await expect(scope).toContainText(t('does not anonymize health data', '不等同于匿名数据'))
  const privacy = form.locator('.export-privacy')
  await expect(privacy.getByRole('checkbox')).toBeChecked()
  await privacy.locator('.el-checkbox__label').click()
  await expect(privacy.getByRole('checkbox')).not.toBeChecked()
  const privacyBox = await privacy.locator('.el-checkbox__label').evaluate(node => ({ right: node.getBoundingClientRect().right, width: innerWidth, scrollWidth: node.scrollWidth, clientWidth: node.clientWidth }))
  assert.ok(privacyBox.right <= cell.viewport + 1 && privacyBox.scrollWidth <= privacyBox.clientWidth + 1, 'Privacy wording is visible and wraps within the narrow form')
  await expect(scope).toContainText(t('Masking is off', '未选择脱敏'))
  await fit(); await capture('export-scope')
  const exportButton = form.getByRole('button', { name: t('Export CSV', '导出 CSV'), exact: true })
  const feedback = page.locator('.export-feedback'), initialDownloads = state.downloads.length
  state.exportMode = 'empty'; await exportButton.click()
  await expect(feedback).toContainText(t('No records', '没有记录')); await noExtraDownload(initialDownloads)
  await capture('export-empty')
  state.exportMode = 'fail'; await exportButton.click()
  await expect(feedback).toContainText(t('Export failed', '导出失败'))
  await expect(page.locator('.el-date-editor input').first()).toHaveValue('2026-10')
  await noExtraDownload(initialDownloads); await capture('export-failed')
  state.exportMode = 'success'
  const downloading = page.waitForEvent('download'); await exportButton.click(); const downloaded = await downloading
  assert.equal(downloaded.suggestedFilename(), 'nutrition_2026-10-01_2026-10-31.csv')
  assert.match(await readFile(await downloaded.path(), 'utf8'), /Synthetic export 99012/)
  const sent = state.apiCalls.filter(call => call.path === '/api/data-export/csv').at(-1).payload
  assert.deepEqual(sent, { patientId: 99012, dataType: 'nutrition', timeType: 'month', timeValue: '2026-10', masked: false })
  await expect(feedback).toContainText(t('Data exported', '数据已导出'))
  state.exportMode = 'delay'; const beforeStale = state.downloads.length
  await exportButton.click(); await expect.poll(() => state.pendingExports.length).toBe(1)
  await patient(99011); await expect(scope).toContainText('#99011')
  const pending = state.pendingExports.shift()
  await pending.route.fulfill({ status: 200, contentType: 'text/csv', body: s.csv(pending.patientId) })
  await noExtraDownload(beforeStale)
  await expect(feedback).toBeHidden(); await capture('export-changed-patient')
  checked('Export repeats identity/type/dates/privacy, preserves failed input, distinguishes empty data, uses safe filenames, and suppresses a stale patient response')

  await go('/health-analysis?tab=health-report')
  const reportScope = page.locator('.report-export-scope'), reportForm = reportScope.locator('..')
  await choose(reportForm.locator('.el-select').first(), t('Nutrition diary report', '营养日记报告'))
  await date(reportScope)
  await expect(reportScope).toContainText('#99011'); await expect(reportScope).toContainText(t('not anonymized', '不是匿名数据'))
  const reportLabels = await formLabelGeometry(page, cell, 'health-report')
  acceptNine(result, 3, { export: exportLabels, report: reportLabels })
  const reportActions = await reportActionGeometry(page, cell)
  acceptNine(result, 9, reportActions)
  await capture('nine-report-labels-actions')
  const reportDownload = page.waitForEvent('download')
  await reportForm.getByRole('button', { name: t('Generate and download report', '生成并下载报告'), exact: true }).click()
  const report = await reportDownload
  assert.equal(report.suggestedFilename(), 'nutrition_2026-10-01_2026-10-31.html')
  assert.match(await readFile(await report.path(), 'utf8'), /Synthetic report 99011/)
  await fit(); await capture('report-scope')
  checked('HTML report scope and filename use captured patient-independent date/type labels')

  if (cell.viewport <= 768) {
    await go('/monitoring')
    await expect(page.locator('.alert-row')).toHaveCount(8)
    const autoRefresh = page.locator('.control-field--switch .el-switch')
    if (await autoRefresh.getByRole('switch').isChecked()) await autoRefresh.click()
    const anchors = []
    for (const id of ['monitoring-alerts', 'monitoring-trends', 'monitoring-tasks']) {
      const link = page.locator(`.monitoring-section-nav a[href="#${id}"]`), heading = page.locator(`#${id}`)
      for (const action of ['click', 'keyboard']) {
        if (action === 'click') await link.click()
        else { await link.focus(); await expect(link).toBeFocused(); await page.keyboard.press('Enter') }
        await expect(heading).toBeFocused()
        const snapshot = await heading.evaluate(element => {
          const rect = node => { const box = node.getBoundingClientRect(); return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height } }
          const target = rect(element), header = rect(document.querySelector('.workspace-topbar')), hit = document.elementFromPoint(target.left + Math.min(40, target.width / 2), target.top + target.height / 2)
          return { viewport: innerWidth, target, header, focused: document.activeElement === element, unobscured: Boolean(hit && element.contains(hit)), scrollY }
        })
        assert.equal(snapshot.viewport, cell.viewport)
        assert.ok(snapshot.focused && snapshot.unobscured, 'The real app region receives unobscured keyboard focus')
        assert.ok(snapshot.target.top >= snapshot.header.bottom && snapshot.target.bottom <= 900, 'Monitoring anchor clears the actual wrapped sticky toolbar')
        anchors.push({ id, action, ...snapshot })
      }
    }
    result.monitoringShellAnchors = anchors
    await fit(); await capture('nine-monitoring-real-header-anchors', { fullPage: false })
    checked('Monitoring click and keyboard anchors clear the actual responsive app toolbar, including long duplicate patient context')
  }
  await go('/medical-record?tab=trend')
  await expect(page.getByRole('button', { name: t('View trend', '查询趋势'), exact: true })).toBeVisible()
  await expect(page.locator('.trend-query-state')).toContainText(t('Not queried yet', '尚未查询'))
  assert.doesNotMatch(await page.locator('#workspace-content').innerText(), /querytrend/i)
  await fit(); await capture('trend-terminology')
  checked('English trend terminology is readable and the Chinese edition keeps its localized action')
  assert.deepEqual(state.errors, [], 'No uncaught browser error is allowed')
  assert.deepEqual(state.violations, [], 'Every API call must match an explicit synthetic fixture; every external request is forbidden')
  acceptNine(result, 4, { duplicateName: patientDisplayName, options: optionIdentities, switches: result.patientSwitchToastBounds, allPatientsTransition: result.patientSwitchAllContext, rapidSwitchFinal: result.patientRapidSwitch })
  result.apiCalls = state.apiCalls; result.downloads = state.downloads
}

let browser
const hosts = new Map()
try {
  await compileSources()
  if (compileOnly) {
    for (const edition of ['en', 'cn']) { const host = await serve(edition, true); await host.server.close() }
    manifest.note = 'Compilation only: no browser launched, no screenshot captured, no visual or interaction assertion executed. results is intentionally empty.'
    console.log(`Compiled ${manifest.compiledFiles.length} real Vue SFCs; browser verification NOT RUN`)
  } else {
    browser = await chromium.launch({ ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}), headless: true })
    for (const edition of ['en', 'cn']) hosts.set(edition, await serve(edition))
    for (const cell of matrix) {
      const result = { id: `shell-${cell.edition}-${cell.viewport}-${cell.theme}`, itemIds, ...cell, status: 'failed', assertions: [], screenshots: [], screenshotHashes: {} }
      manifest.results.push(result)
      let session
      try {
        session = await openCase(browser, hosts.get(cell.edition), cell, result)
        await exerciseCase(session, cell, result)
        assert.ok(result.screenshots.length >= 10, 'A passing case must preserve the rendered states it asserted')
        result.status = 'passed'
      } catch (error) {
        result.error = error.stack || String(error)
        if (session) {
          result.browserErrors = session.state.errors; result.networkViolations = session.state.violations
          try { await session.capture('failure') } catch (captureError) { result.screenshotError = String(captureError) }
        }
        process.exitCode = 1
      } finally {
        await session?.context.close(); await persist()
      }
      console.log(`${result.id}: ${result.status}`)
    }
  }
} catch (error) {
  manifest.error = error.stack || String(error); process.exitCode = 1
  if (!compileOnly) {
    for (const cell of matrix) if (!manifest.results.some(result => result.edition === cell.edition && result.viewport === cell.viewport && result.theme === cell.theme)) {
      manifest.results.push({ id: `shell-${cell.edition}-${cell.viewport}-${cell.theme}`, itemIds, ...cell, status: 'failed', screenshots: [], screenshotHashes: {}, error: 'Browser setup or compilation failed; case was not executed' })
    }
  }
  console.error(manifest.error)
} finally {
  await browser?.close()
  for (const { server } of hosts.values()) await server.close()
  manifest.finishedAt = new Date().toISOString()
  await persist()
}
