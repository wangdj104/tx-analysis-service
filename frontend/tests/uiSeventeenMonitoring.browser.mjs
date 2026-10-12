// Real EN/CN SFCs, real DOM interactions, and fictional transport only.
// UI_SEVENTEEN_EVIDENCE_DIR=/absolute/output node frontend/tests/uiSeventeenMonitoring.browser.mjs
// --compile-only builds both harnesses WITHOUT opening sockets or launching a browser.
// Compile evidence cannot satisfy browser acceptance. Browser mode requires official
// Playwright Chromium (default) or an explicitly configured CHROMIUM_PATH.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdtemp, mkdir, readFile, writeFile, rename, rm } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { join, relative, resolve } from 'node:path'
import { build, preview } from 'vite'
import vue from '@vitejs/plugin-vue'
import { chromium, expect } from '@playwright/test'

const repo = fileURLToPath(new URL('../../', import.meta.url))
const dependencyRoot = join(repo, 'frontend')
const evidence = resolve(process.env.UI_SEVENTEEN_EVIDENCE_DIR || join(repo, 'frontend/test-results/ui-seventeen'))
const compileOnly = process.argv.includes('--compile-only')
assert.ok(process.argv.slice(2).every(arg => arg === '--compile-only'), 'Only --compile-only is supported')
const themes = ['platform', 'white', 'blue', 'mint', 'sand', 'dark']
const itemIds = [2, 4, 8, 10, 13, 17]
const cells = ['en', 'cn'].flatMap(edition => [1365, 375].flatMap(viewport => themes.map(theme => ({ edition, viewport, theme }))))
const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim()
assert.match(commit, /^[a-f0-9]{40}$/)
const runId = new Date().toISOString().replace(/[^A-Za-z0-9]/g, '-')
const report = {
  commit, mode: compileOnly ? 'compile-only' : 'browser', runId,
  startedAt: new Date().toISOString(), syntheticOnly: true, results: [], sourceHashes: {},
  plannedCells: cells.map(cell => ({ ...cell, itemIds })),
  browserAssertions: 'never-run',
  limitations: ['Synthetic API fixtures verify UI behavior, not server authorization or clinical accuracy.', 'Alert denominator is loaded snapshot entries; the existing backend cap is 100.'],
}
await mkdir(evidence, { recursive: true })
async function persist() {
  const target = join(evidence, 'monitoring-results.json')
  await writeFile(`${target}.tmp`, JSON.stringify(report, null, 2))
  await rename(`${target}.tmp`, target)
}
await persist() // Never leave a prior successful run masquerading as this run.

const files = ['views/MonitoringCenter.vue', 'views/BpSelfMonitorManager.vue', 'views/NutritionDiaryManager.vue', 'components/care-plan/PlanTaskList.vue']
for (const edition of ['en', 'cn']) {
  for (const file of [...files, 'App.vue', 'utils/healthAppearance.js', 'styles/health-appearance.css']) {
    const filename = join(repo, edition === 'en' ? 'frontend' : 'cn/frontend', 'src', file)
    report.sourceHashes[relative(repo, filename)] = createHash('sha256').update(await readFile(filename)).digest('hex')
  }
}
report.sourceHashes[relative(repo, fileURLToPath(import.meta.url))] = createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex')

async function makeHarness(edition) {
  const frontend = join(repo, edition === 'en' ? 'frontend' : 'cn/frontend')
  const directory = await mkdtemp(join(dependencyRoot, '.ui-seventeen-monitoring-'))
  await writeFile(join(directory, 'index.html'), `<!doctype html><html lang="${edition === 'en' ? 'en' : 'zh-CN'}"><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="app"></div><script type="module" src="/main.js"></script></body></html>`)
  await writeFile(join(directory, 'main.js'), `
    import {createApp,h} from 'vue';
    import {createRouter,createMemoryHistory,RouterView,useRoute} from 'vue-router';
    import ElementPlus from 'element-plus';
    import locale from 'element-plus/es/locale/lang/${edition === 'en' ? 'en' : 'zh-cn'}';
    import * as icons from '@element-plus/icons-vue';
    // Reuse the actual App global reset in main.js order without running App behavior.
    import '/@fs/${frontend}/src/App.vue?vue&type=style&index=0&lang.css';
    import 'element-plus/dist/index.css';
    import '/@fs/${frontend}/src/styles/app-theme.css';
    import '/@fs/${frontend}/src/styles/mobile-optimization.css';
    import '/@fs/${frontend}/src/styles/health-redesign.css';
    import '/@fs/${frontend}/src/styles/care-accessibility.css';
    import '/@fs/${frontend}/src/styles/health-appearance.css';
    import Monitoring from '/@fs/${frontend}/src/views/MonitoringCenter.vue';
    import BP from '/@fs/${frontend}/src/views/BpSelfMonitorManager.vue';
    import Nutrition from '/@fs/${frontend}/src/views/NutritionDiaryManager.vue';
    import Alerts from '/@fs/${frontend}/src/views/AlertManager.vue';
    import PlanTaskList from '/@fs/${frontend}/src/components/care-plan/PlanTaskList.vue';
    import {useCurrentPatient} from '/@fs/${frontend}/src/composables/useCurrentPatient.js';
    import {initializeAppearance} from '/@fs/${frontend}/src/utils/healthAppearance.js';
    initializeAppearance();
    const scope=useCurrentPatient();
    scope.setPatientList([{id:1,patientName:'Fictional patient A'},{id:2,patientName:'Fictional patient B'}]);
    const Analysis={setup(){const route=useRoute();return()=>h(route.query.tab==='alert'?Alerts:Nutrition)}};
    const Nurse={setup(){return()=>h('main',{class:'fixture-nurse'},[h(PlanTaskList,{patientId:null,mode:'NURSE',queue:'TODAY'})])}};
    const router=createRouter({history:createMemoryHistory(),routes:[
      {path:'/monitoring',component:Monitoring},{path:'/bp-self-monitor',component:BP},
      {path:'/health-analysis',component:Analysis},{path:'/nurse-workspace',component:Nurse},
      {path:'/:pathMatch(.*)*',component:{render:()=>h('p','Synthetic route destination')}}]});
    const initial={monitoring:'/monitoring',bp:'/bp-self-monitor',nutrition:'/health-analysis?tab=nutrition',nurse:'/nurse-workspace'}[new URLSearchParams(location.search).get('surface')]||'/monitoring';
    const Host={setup(){return()=>h('div',{class:'care-workspace'},[
      h('header',{class:'fixture-toolbar'},[
        h('label',['Synthetic patient ',h('select',{'data-testid':'fixture-patient',value:scope.currentPatientId.value,onChange:event=>scope.currentPatientId.value=Number(event.target.value)},[
          h('option',{value:1},'Fictional patient A · #1'),h('option',{value:2},'Fictional patient B · #2')])]),
        h('output',{'data-testid':'fixture-route'},router.currentRoute.value.fullPath)]),h(RouterView)])}};
    router.push(initial).then(()=>{const app=createApp(Host);app.use(router);app.use(ElementPlus,{locale});for(const [name,icon]of Object.entries(icons))app.component(name,icon);app.mount('#app')});
  `)
  const config = {
    configFile: false, root: directory, plugins: [vue(), {
      name: 'monitoring-qa-no-api-upstream', configurePreviewServer(server) {
        server.middlewares.use((req, res, next) => {
          if (!new URL(req.url, 'http://localhost').pathname.startsWith('/api/')) return next()
          res.statusCode = 599; res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify({ code: 599, msg: 'Synthetic API interception missing; upstream is disabled' }))
        })
      },
    }],
    resolve: { alias: { '@': join(frontend, 'src') }, dedupe: ['vue', 'vue-router', 'element-plus'] },
    preview: { host: '127.0.0.1', port: 0 },
    build: { outDir: join(directory, 'dist'), reportCompressedSize: false }, logLevel: 'error',
  }
  return { directory, config }
}

const paths = { monitoring: '/api/monitoring/snapshot', bp: '/api/bp-self-monitor/list', nutrition: '/api/nutrition-diary/list', capabilities: '/api/care-plans/capabilities', tasks: '/api/care-plans' }
const patientName = id => `Fictional patient ${Number(id) === 2 ? 'B' : 'A'}`
const dateAt = n => `2026-09-${String(30 - n).padStart(2, '0')}`
function bpRows(patientId) {
  return [
    { id: 101, patientId, recordDate: '2026-10-11', recordTime: '07:30', measureType: 'BG', systolicBp: null, diastolicBp: null, bloodGlucose: 108, bgUnit: 'mg/dL', measurePeriod: 'Fasting', remark: 'Fictional latest glucose' },
    { id: 102, patientId, recordDate: '2026-10-10', recordTime: '09:15', measureType: 'BP', systolicBp: 144, diastolicBp: 83, bloodGlucose: null, bgUnit: 'mmol/L', remark: 'Fictional latest blood pressure' },
    { id: 103, patientId, recordDate: '2026-10-09', recordTime: '20:00', measureType: 'BP_BG', systolicBp: 120, diastolicBp: 80, bloodGlucose: 5.1, bgUnit: 'mmol/L', measurePeriod: 'Random', remark: 'Fictional combined reading' },
    ...Array.from({ length: 18 }, (_, n) => ({ id: 200 + n, patientId, recordDate: dateAt(n), recordTime: '08:00', measureType: 'BP', systolicBp: 120, diastolicBp: 80, bloodGlucose: null, bgUnit: 'mmol/L', remark: `Fictional BP history ${n}` })),
  ]
}
function nutritionRows(patientId) {
  return Array.from({ length: 18 }, (_, n) => ({ id: 300 + n, patientId, recordDate: dateAt(n), bodyWeight: 70, appetite: 'GOOD', mealBreakfast: true, mealLunch: true, mealDinner: true, mealSnack: false, fluidIntake: 1000, symptoms: '', remark: `Fictional nutrition history ${n}` }))
}
function alerts(patientId) {
  return Array.from({ length: 9 }, (_, n) => ({ id: 400 + n, patientId, type: 'BP', title: `Fictional alert ${n + 1}`, alertTitle: `Fictional alert ${n + 1}`, level: 'INFO', alertLevel: 'INFO', value: 'Fictional source evidence', triggeredValue: 'Fictional source evidence', triggeredAt: '2026-10-11T07:30:00Z', status: 'PENDING', occurrenceCount: 1 }))
}
function snapshot(patientId, days, empty = false) {
  const vitalTrend = [
    { date: '2026-10-10', time: '09:15', systolic: 144, diastolic: 83, glucose: 108, glucoseUnit: 'mg/dL', abnormal: true },
    { date: '2026-10-11', time: '07:30', systolic: 120, diastolic: 80, glucose: 5.2, glucoseUnit: 'mmol/L', abnormal: false },
  ]
  if (days >= 30) vitalTrend.unshift({ date: '2026-09-20', time: '08:00', systolic: 118, diastolic: 75, glucose: 5, glucoseUnit: 'mmol/L', abnormal: false })
  return { patient: { id: patientId, name: patientName(patientId) }, generatedAt: '2026-10-11T08:00:00Z', lastDataAt: '2026-10-11T07:30:00Z', overallStatus: 'STABLE', signals: [], metrics: { activeAlertCount: empty ? 0 : 9, todayTaskCount: 0 }, activeAlerts: empty ? [] : alerts(patientId), todayTasks: [], recentEvents: [], careSuggestions: [], vitalTrend: empty ? [] : vitalTrend }
}
function plans() {
  const action = (id, status, allowedActions) => ({ id, ordinal: id - 500, status, instruction: `Fictional action ${id}`, assignedUserId: 71, dueAt: '2026-10-12T18:00:00+00:00', allowedActions, allowedEntryModes: [], evidence: [], events: [] })
  return [{ id: 50, patientId: 1, title: 'Fictional authorized nurse plan', instructions: 'Fictional source instruction', revisionNo: 1, version: 1, actions: [action(503, 'CONFIRMED', []), action(502, 'SUBMITTED', []), action(501, 'OPEN', ['FOLLOW_UP']), action(504, 'CONFIRMED', ['FOLLOW_UP'])] }]
}
function fixture() {
  const state = { requests: [], unexpected: [], blockedNetwork: [], overrides: new Map(), gates: [], bp: new Map([[1, bpRows(1)], [2, bpRows(2)]]), nutrition: new Map([[1, nutritionRows(1)], [2, nutritionRows(2)]]) }
  state.override = (path, response) => state.overrides.set(path, response)
  state.clear = path => state.overrides.delete(path)
  state.hold = (path, predicate = () => true) => {
    let release
    const gate = { predicate, waiting: false, promise: new Promise(done => { release = done }), release: () => release() }
    state.gates.push(gate); state.override(path, { gate }); return gate
  }
  state.count = path => state.requests.filter(request => request.path === path).length
  state.release = () => state.gates.forEach(gate => gate.release())
  state.response = request => {
    const { path, query = {}, method, body, authorization } = request
    const reject = reason => { state.unexpected.push(`${method} ${path}: ${reason}`); return null }
    if (authorization !== 'Bearer fictional-browser-fixture-only') return reject('Missing or incorrect fictional bearer authorization')
    const reads = [paths.monitoring, paths.bp, paths.nutrition, paths.capabilities, paths.tasks, '/api/alert/records', '/api/alert/rules', '/api/health-indicator/list']
    if (reads.includes(path) && method !== 'GET') return reject('Read method must be GET')
    const scopedReads = [paths.monitoring, paths.bp, paths.nutrition, '/api/alert/records', '/api/alert/rules']
    if (scopedReads.includes(path) && !['1', '2'].includes(String(query.patientId))) return reject('Missing or malformed fictional patient scope')
    const patientId = Number(query.patientId)
    if (path === paths.monitoring) {
      if (!['7', '30', '90'].includes(String(query.days))) return reject('Missing or malformed trend range')
      return snapshot(patientId, Number(query.days))
    }
    if (path === paths.bp) {
      if (query.measureType != null && !['', 'BP', 'BG', 'BP_BG'].includes(query.measureType)) return reject('Unknown reading type')
      return (state.bp.get(patientId) || []).filter(row => !query.measureType || row.measureType === query.measureType)
    }
    if (path === paths.nutrition) return state.nutrition.get(patientId) || []
    if (path === paths.capabilities) return { enabled: true }
    if (path === paths.tasks) {
      if (query.patientId != null || query.queue !== 'TODAY' || !Number.isInteger(Number(query.limit)) || Number(query.limit) < 1 || Number(query.limit) > 100 || !query.dueBefore || !Number.isFinite(Date.parse(query.dueBefore))) return reject('Malformed nurse queue scope')
      return { items: plans(), nextCursor: null }
    }
    if (path === '/api/alert/records') return alerts(patientId)
    if (['/api/alert/rules', '/api/health-indicator/list'].includes(path)) return []
    if (['/api/bp-self-monitor/update', '/api/nutrition-diary/update'].includes(path)) {
      if (method !== 'PUT') return reject('Update method must be PUT')
      if (!body || ![1, 2].includes(body.patientId) || !Number.isSafeInteger(body.id)) return reject('Missing or malformed mutation ownership')
      const rows = path.includes('bp-self-monitor') ? state.bp : state.nutrition
      const records = rows.get(body.patientId) || [], index = records.findIndex(row => row.id === body.id && row.patientId === body.patientId)
      if (index < 0) return reject('Update does not belong to the exact fictional patient and record')
      records[index] = { ...records[index], ...body }; return records[index]
    }
    return reject('No explicitly authorized synthetic fixture')
  }
  return state
}
async function intercept(context, origin, state) {
  await context.routeWebSocket('**/*', socket => {
    state.blockedNetwork.push('websocket:' + socket.url()); socket.close()
  })
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url())
    if (url.origin !== origin) { state.blockedNetwork.push(url.origin + url.pathname); await route.abort('blockedbyclient'); return }
    if (!url.pathname.startsWith('/api/')) { await route.continue(); return }
    let body
    try { body = request.postDataJSON() } catch { body = null }
    const entry = { path: url.pathname, method: request.method(), query: Object.fromEntries(url.searchParams), body, authorization: request.headers()['authorization'] }
    state.requests.push(entry)
    const override = state.overrides.get(entry.path)
    // Capture data before waiting: a delayed old response must remain old.
    const previousUnexpected = state.unexpected.length, defaultData = state.response(entry)
    const invalid = state.unexpected.length !== previousUnexpected
    const data = structuredClone(invalid ? null : override && Object.hasOwn(override, 'data') ? override.data : defaultData)
    const httpStatus = invalid ? 599 : override?.httpStatus || 200
    if (override?.gate && override.gate.predicate(entry)) { override.gate.waiting = true; await override.gate.promise }
    try { await route.fulfill({ status: httpStatus, contentType: 'application/json', body: JSON.stringify({ code: httpStatus, msg: httpStatus === 200 ? 'Synthetic success' : 'Synthetic read unavailable', data }) }) }
    catch (error) { if (!context.pages().every(page => page.isClosed()) && !request.failure()) throw error }
  })
}
function verifyFixtureContracts() {
  const invalid = [
    { method: 'POST', path: paths.monitoring, query: { patientId: '1', days: '7' } },
    { method: 'DELETE', path: paths.bp, query: { patientId: '1' } },
    { method: 'POST', path: paths.capabilities, query: {} },
    ...[paths.monitoring, paths.bp, paths.nutrition, '/api/alert/records', '/api/alert/rules'].flatMap(path => [undefined, '0', 'abc', '3'].map(patientId => ({ method: 'GET', path, query: { patientId, days: '7' } }))),
    { method: 'GET', path: paths.monitoring, query: { patientId: '1' } },
    { method: 'GET', path: paths.monitoring, query: { patientId: '1', days: '999' } },
    { method: 'GET', path: paths.bp, query: { patientId: '1', measureType: 'INVALID' } },
    { method: 'GET', path: paths.tasks, query: {} },
    { method: 'POST', path: '/api/bp-self-monitor/update', query: {}, body: { id: 102, patientId: 1 } },
    { method: 'PUT', path: '/api/bp-self-monitor/update', query: {}, body: { id: 102, patientId: 3 } },
    { method: 'PUT', path: '/api/nutrition-diary/update', query: {}, body: { id: 102, patientId: 1 } },
    { method: 'PUT', path: '/api/nutrition-diary/update', query: {}, body: null },
  ]
  for (const request of invalid) {
    const state = fixture(); state.override(request.path, { data: [] })
    assert.equal(state.response({ ...request, authorization: 'Bearer fictional-browser-fixture-only' }), null); assert.equal(state.unexpected.length, 1)
  }
  for (const authorization of [undefined, 'Bearer incorrect']) {
    const state = fixture()
    assert.equal(state.response({ method: 'GET', path: paths.bp, query: { patientId: '1' }, authorization }), null)
    assert.equal(state.unexpected.length, 1)
  }
  const state = fixture()
  assert.equal(state.response({ authorization: 'Bearer fictional-browser-fixture-only', method: 'GET', path: paths.bp, query: { patientId: '1' } }).length, 21)
  assert.equal(state.response({ authorization: 'Bearer fictional-browser-fixture-only', method: 'GET', path: paths.nutrition, query: { patientId: '2' } }).length, 18)
  assert.equal(state.response({ authorization: 'Bearer fictional-browser-fixture-only', method: 'GET', path: paths.monitoring, query: { patientId: '2', days: '30' } }).vitalTrend.length, 3)
  assert.deepEqual(state.unexpected, [])
  return { type: 'offline-fixture-contract', rejectedInvalidRequests: invalid.length + 2, verified: true }
}
report.fixtureContracts = verifyFixtureContracts()

const copy = {
  en: { tooltipOff: 'Automatic refresh is off', data: 'Data', refresh: 'Refresh', edit: 'Edit', cancel: 'Cancel editing', update: 'Update record', bp: 'Blood Pressure', bg: 'Blood Glucose', all: 'All', thirty: 'Last 30 days', marker: 'Abnormal', emptyMonitoring: 'No vital-sign records in this time range', emptyBp: 'No records', emptyTasks: 'No authorized tasks in this queue.', disabled: 'Plan collaboration is not enabled in this session.', forbidden: 'You do not have access to plan collaboration.', capabilityError: 'Plan access could not be checked. Try again.', loadingMonitoring: 'Loading monitoring data', loadingBp: 'Loading records', loadingTasks: 'Loading authorized plan tasks', latestBp: 'Latest blood pressure', latestBg: 'Latest blood glucose', dated: 'Edit record from' },
  cn: { tooltipOff: '自动刷新已关闭', data: '数据', refresh: '刷新', edit: '编辑', cancel: '取消编辑', update: '更新记录', bp: '血压', bg: '血糖', all: '全部', thirty: '近 30 天', marker: '异常', emptyMonitoring: '当前时间范围内暂无生命体征记录', emptyBp: '暂无记录', emptyTasks: '当前队列没有已授权的事项。', disabled: '当前会话未启用计划协作。', forbidden: '没有访问计划协作的权限。', capabilityError: '无法检查计划访问权限，请重试。', loadingMonitoring: '正在加载监测数据', loadingBp: '正在加载记录', loadingTasks: '正在加载已授权的计划事项', latestBp: '最近血压', latestBg: '最近血糖', dated: '编辑' },
}
const expectedThemes = {
  platform: ['#f5f7fb', '#ffffff', '#f4f7f6'], white: ['#f5f7fb', '#ffffff', '#f5f7fa'],
  blue: ['#eaf1f8', '#ffffff', '#f1f6fb'], mint: ['#eaf4ef', '#ffffff', '#f0f7f3'],
  sand: ['#f7f0e5', '#fffdf9', '#f8f2e9'], dark: ['#101a21', '#18262e', '#203139'],
}
const rgb = hex => `rgb(${[1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16)).join(', ')})`
async function verifyTheme(page, cell, result, surface) {
  const signature = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement), body = getComputedStyle(document.body)
    const panel = document.querySelector('.monitor-panel,.entry-panel,.plan-card,.plan-tasks')
    return { theme: document.documentElement.dataset.healthTheme, innerWidth, page: root.getPropertyValue('--page').trim(), paper: root.getPropertyValue('--paper').trim(), subtle: root.getPropertyValue('--surface-subtle').trim(), bodyBackground: body.backgroundColor, panelBackground: panel ? getComputedStyle(panel).backgroundColor : null, colorScheme: root.colorScheme, reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches }
  })
  assert.equal(signature.theme, cell.theme); assert.equal(signature.innerWidth, cell.viewport)
  assert.deepEqual([signature.page, signature.paper, signature.subtle], expectedThemes[cell.theme])
  assert.equal(signature.bodyBackground, rgb(expectedThemes[cell.theme][0]))
  if (surface !== 'nurse') assert.equal(signature.panelBackground, rgb(expectedThemes[cell.theme][1]))
  assert.equal(signature.reducedMotion, true)
  result.styleSignatures ||= {}; result.styleSignatures[surface] = signature
}
// Wait for actual font/layout and Element Plus transition completion. Stable
// visible overflow still fails below; this never removes or hides application DOM.
async function waitForMonitoringLayout(page) {
  await page.waitForFunction(async () => {
    await (document.fonts?.ready || Promise.resolve())
    const snapshot = () => {
      const visible = [...document.querySelectorAll('.el-popper.el-tooltip')].filter(element => {
        const style = getComputedStyle(element)
        return style.display !== 'none' && style.visibility !== 'hidden'
      })
      return {
        moving: visible.some(element => /(?:enter|leave)-(?:active|from|to)/.test(element.className) || element.getAnimations().some(animation => animation.playState === 'running' || animation.pending)),
        rectangles: visible.map(element => { const rect = element.getBoundingClientRect(); return [rect.left, rect.right, rect.top, rect.bottom] }),
        width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight,
      }
    }
    const before = snapshot()
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    const after = snapshot()
    return !before.moving && !after.moving && JSON.stringify(before) === JSON.stringify(after)
  }, null, { timeout: 15000 })
}
function assertBpSummaryBounds(geometry, viewport) {
  assert.equal(geometry.containers.length, 5, 'All BP summary containers must be rendered')
  assert.equal(geometry.cards.length, 3, 'All three BP summary cards must be rendered')
  for (const element of [...geometry.containers, ...geometry.cards]) {
    assert.ok(element.width > 0 && element.height > 0, `${element.name} must actually be rendered`)
    assert.equal(element.boxSizing, 'border-box', `${element.name} must use the actual App border-box reset`)
    assert.ok(element.left >= 0 && element.right <= viewport, `${element.name} must fit the viewport`)
  }
  for (const card of geometry.cards) {
    for (const ancestor of card.clippingAncestors) {
      assert.ok(card.left >= ancestor.left && card.right <= ancestor.right, `${card.name} must not be clipped by ${ancestor.name}`)
    }
  }
}
async function capture(page, result, suffix, { preserveHover = false } = {}) {
  if (!preserveHover) await page.mouse.move(0, 0)
  await waitForMonitoringLayout(page)
  const path = `monitoring/${commit}/${runId}/${result.edition}-${result.viewport}-${result.theme}-${suffix}.png`
  const geometry = await page.evaluate(() => {
    const describe = element => { const rect = element.getBoundingClientRect(); return { tag: element.tagName, className: typeof element.className === 'string' ? element.className : '', left: rect.left, right: rect.right, width: rect.width, height: rect.height, boxSizing: getComputedStyle(element).boxSizing } }
    const bp = document.querySelector('.bp-monitor-page')
    const bpSummary = bp ? {
      containers: ['.bp-monitor-page', '.main-content', '.page-inner', '.bp-hero', '.bp-summary'].map(selector => { const element = selector === '.bp-monitor-page' ? bp : bp.querySelector(selector); return { name: selector, ...describe(element) } }),
      cards: [...bp.querySelectorAll('.bp-summary .summary-item')].map(element => {
        const clippingAncestors = []
        for (let parent = element.parentElement; parent; parent = parent.parentElement) {
          if (['hidden', 'clip', 'auto', 'scroll'].includes(getComputedStyle(parent).overflowX)) {
            const rect = parent.getBoundingClientRect(), left = rect.left + parent.clientLeft
            clippingAncestors.push({ name: parent.className || parent.tagName, left, right: left + parent.clientWidth })
          }
        }
        return { name: element.textContent.trim(), ...describe(element), clippingAncestors }
      }),
    } : null
    return { innerWidth, scrollWidth: document.documentElement.scrollWidth, bodyScrollWidth: document.body.scrollWidth, scrollY, scrollHeight: document.documentElement.scrollHeight, bpSummary, tooltipLeaveTraces: window.__uiSeventeenMonitoringTooltipLeaves || [], controls: [...document.querySelectorAll('.monitoring-controls')].map(describe), outsideViewport: [...document.querySelectorAll('.fixture-toolbar,.monitoring-head,.monitoring-controls,.monitor-panel,.el-popper,.bp-monitor-page .page-inner,.bp-summary .summary-item')].map(describe).filter(rect => rect.width && (rect.left < 0 || rect.right > innerWidth)) }
  })
  result.geometry ||= []; result.geometry.push({ suffix, ...geometry })
  await mkdir(join(evidence, 'monitoring', commit, runId), { recursive: true })
  await page.screenshot({ path: join(evidence, path), fullPage: true })
  const bytes = await readFile(join(evidence, path))
  result.screenshotHashes ||= {}; result.screenshotHashes[path] = createHash('sha256').update(bytes).digest('hex')
  result.screenshots.push(path)
  assert.equal(geometry.innerWidth, result.viewport, 'Geometry must use the actual requested viewport')
  assert.ok(geometry.scrollWidth <= geometry.innerWidth, 'Document content must not overflow the viewport')
  assert.equal(bytes.readUInt32BE(16), result.viewport, 'Screenshot must use the actual requested viewport width')
  assert.ok(bytes.readUInt32BE(20) >= 900, 'Screenshot must retain full-page height at least the viewport height')
  if (geometry.bpSummary) assertBpSummaryBounds(geometry.bpSummary, result.viewport)
}
async function waitGate(gate) { await expect.poll(() => gate.waiting).toBe(true) }
async function open(page, origin, surface) { await page.goto(`${origin}/?surface=${surface}`); await page.getByTestId('fixture-patient').waitFor() }
// Observation only: no dispatched events, focus changes, styles or tooltip state.
function monitoringTooltipInputTrace(operation) {
  const eventNames = ['focus', 'blur', 'mouseenter', 'mouseleave']
  if (operation === 'start') {
    const trace = { events: [], states: [], initialFocus: document.activeElement }
    const describe = element => element ? { tag: element.tagName, className: element.className, role: element.getAttribute('role') } : null
    trace.state = step => {
      const trigger = document.querySelector('.control-field--switch .el-switch'), refresh = document.querySelector('.refresh-button'), tooltip = document.querySelector('.monitoring-auto-refresh-tooltip')
      const style = tooltip && getComputedStyle(tooltip)
      trace.states.push({ step, activeElement: describe(document.activeElement), triggerFocused: !!trigger?.contains(document.activeElement), triggerHovered: !!trigger?.matches(':hover'), refreshFocused: document.activeElement === refresh, refreshEnabled: !!refresh && !refresh.disabled, tooltipHovered: !!tooltip?.matches(':hover'), tooltip: tooltip ? { ariaHidden: tooltip.getAttribute('aria-hidden'), display: style.display, visibility: style.visibility } : null })
      if (trace.states.length > 8) trace.states.shift()
    }
    trace.listener = event => {
      if (!event.target?.closest?.('.control-field--switch,.monitoring-auto-refresh-tooltip,.refresh-button')) return
      trace.events.push({ type: event.type, trusted: event.isTrusted, timestamp: event.timeStamp, target: describe(event.target), relatedTarget: describe(event.relatedTarget) })
      if (trace.events.length > 32) trace.events.shift()
    }
    window.__uiSeventeenMonitoringTooltipTrace = trace
    for (const name of eventNames) document.addEventListener(name, trace.listener, true)
    trace.state('before')
    return
  }
  const trace = window.__uiSeventeenMonitoringTooltipTrace
  if (!trace) return
  trace.state(operation)
  if (operation === 'focus-unchanged') return document.activeElement === trace.initialFocus
  if (operation === 'pointer-neutral') return !trace.states.at(-1).triggerHovered && !trace.states.at(-1).tooltipHovered
  if (operation === 'finish') {
    for (const name of eventNames) document.removeEventListener(name, trace.listener, true)
    window.__uiSeventeenMonitoringTooltipLeaves ||= []
    window.__uiSeventeenMonitoringTooltipLeaves.push({ events: trace.events, states: trace.states, focusUnchanged: document.activeElement === trace.initialFocus })
    if (window.__uiSeventeenMonitoringTooltipLeaves.length > 8) window.__uiSeventeenMonitoringTooltipLeaves.shift()
    delete window.__uiSeventeenMonitoringTooltipTrace
  }
}
async function leaveMonitoringTooltip(page, { mode = 'hover' } = {}) {
  const input = page.locator('.control-field--switch .el-switch').getByRole('switch')
  const refresh = page.locator('.refresh-button')
  // Establish a genuine focus/blur path instead of assuming a prior wrapper
  // click focused its native input or an unchecked focus() call moved it away.
  await page.evaluate(monitoringTooltipInputTrace, 'start')
  try {
    if (mode === 'reset') {
      // Only initial timer cleanup may establish a fresh keyboard state.
      await expect(refresh).toBeEnabled()
      await input.focus(); await expect(input).toBeFocused()
      await page.evaluate(monitoringTooltipInputTrace, 'switch-focused')
    } else if (mode === 'focus') {
      await expect(input).toBeFocused(); await expect(refresh).toBeEnabled()
      expect(await page.evaluate(monitoringTooltipInputTrace, 'pointer-neutral')).toBe(true)
    }
    if (mode !== 'hover') {
      await page.keyboard.press('Tab'); await expect(refresh).toBeFocused()
      await page.evaluate(monitoringTooltipInputTrace, 'refresh-focused')
    }
    if (mode !== 'focus') {
      // Pure hover leave never adds focus/Tab; keyboard leave never moves the pointer.
      await page.getByTestId('fixture-patient').hover()
      await page.evaluate(monitoringTooltipInputTrace, 'neutral-hover')
    }
    if (mode === 'hover') expect(await page.evaluate(monitoringTooltipInputTrace, 'focus-unchanged')).toBe(true)
    await expect(page.locator('.monitoring-auto-refresh-tooltip')).toBeHidden()
    await waitForMonitoringLayout(page)
  } finally {
    await page.evaluate(monitoringTooltipInputTrace, 'finish')
  }
}
async function stopMonitoringTimer(page) {
  const control = page.locator('.control-field--switch .el-switch')
  if (await control.getAttribute('aria-checked') === 'true' || await control.locator('input').isChecked()) await control.click()
  await leaveMonitoringTooltip(page, { mode: 'reset' })
}
async function filter(page, label) { await page.locator('.filter-switch .el-radio-button').filter({ hasText: new RegExp(`^${label}$`) }).click() }

function assertMonitoringTooltipBounds(geometry, viewport) {
  assert.ok(geometry.width > 0 && geometry.height > 0, 'Tooltip must actually be rendered')
  assert.ok(geometry.left >= 0 && geometry.right <= viewport, 'Visible tooltip must stay inside the viewport')
  assert.ok(geometry.scrollWidth <= viewport, 'Visible tooltip must not overflow the viewport')
}
async function verifyMonitoringTooltip(page, result, words) {
  const control = page.locator('.control-field--switch .el-switch')
  const tooltip = page.locator('.monitoring-auto-refresh-tooltip')
  for (const mode of ['hover', 'focus']) {
    if (mode === 'hover') { await expect(page.locator('.refresh-button')).toBeFocused(); await control.hover() }
    else { await control.getByRole('switch').focus(); await expect(control.getByRole('switch')).toBeFocused() }
    await expect(tooltip).toBeVisible(); await expect(tooltip).toContainText(words.tooltipOff)
    await waitForMonitoringLayout(page)
    const geometry = await tooltip.evaluate(element => { const rect = element.getBoundingClientRect(); return { left: rect.left, right: rect.right, width: rect.width, height: rect.height, scrollWidth: document.documentElement.scrollWidth } })
    result.tooltipGeometry ||= []; result.tooltipGeometry.push({ mode, ...geometry })
    assertMonitoringTooltipBounds(geometry, result.viewport)
    if (mode === 'hover') await capture(page, result, 'monitoring-visible-tooltip', { preserveHover: true })
    else await capture(page, result, 'monitoring-keyboard-tooltip', { preserveHover: true })
    await leaveMonitoringTooltip(page, { mode })
  }
  result.checks.push('Monitoring help is visible through native hover and keyboard focus, fits the exact viewport, and finishes its real leave transition before unrelated screenshots')
}

async function monitoringCase(page, origin, state, cell, result, words) {
  const gate = state.hold(paths.monitoring)
  await open(page, origin, 'monitoring'); await waitGate(gate)
  await expect(page.locator('.monitoring-page [role=status]').filter({ hasText: words.loadingMonitoring })).toBeVisible()
  await expect(page.getByTestId('monitoring-trend-data')).toHaveCount(0)
  await expect(page.getByText(words.emptyMonitoring, { exact: true })).toHaveCount(0)
  state.clear(paths.monitoring); gate.release()
  await expect(page.locator('.alert-panel .alert-row')).toHaveCount(8)
  await stopMonitoringTimer(page)
  await verifyTheme(page, cell, result, 'monitoring')
  await verifyMonitoringTooltip(page, result, words)
  await expect(page.getByTestId('alert-count')).toContainText(/8\s*\/.*9/)
  if (cell.edition === 'en') await expect(page.getByTestId('alert-count')).toHaveText('Showing 8 / 9 loaded alerts')
  const allAlerts = page.getByTestId('view-all-alerts'), href = new URL(await allAlerts.getAttribute('href'), origin)
  assert.equal(href.pathname, '/health-analysis'); assert.equal(href.searchParams.get('tab'), 'alert'); assert.equal(href.searchParams.get('patientId'), '1')
  const data = page.locator('.trend-view-controls').getByRole('button', { name: words.data, exact: true })
  await data.focus(); await page.keyboard.press('Enter')
  const table = page.getByTestId('monitoring-trend-data')
  await expect(table.locator('tbody tr')).toHaveCount(2)
  await expect(table).toContainText('108 mg/dL'); await expect(table).toContainText('5.2 mmol/L'); await expect(table).toContainText(words.marker)
  await expect(table.locator('tbody tr').first()).toContainText('2026-10-10 09:15')
  await page.locator('.range-select').click(); await page.getByRole('option', { name: words.thirty, exact: true }).click()
  await expect(table.locator('tbody tr')).toHaveCount(3)
  await expect(page.locator('.trend-context')).toContainText(/#1.*30/)
  assert.equal(state.requests.filter(request => request.path === paths.monitoring).at(-1).query.days, '30')
  await capture(page, result, 'monitoring-trend-alerts')
  state.override(paths.monitoring, { httpStatus: 500 })
  await page.locator('.refresh-button').click()
  await expect(page.getByTestId('monitoring-stale')).toContainText(/\d{4}-\d{2}-\d{2}/)
  await expect(table.locator('tbody tr')).toHaveCount(3)
  await expect(page.getByTestId('retry-monitoring')).toBeEnabled()
  await capture(page, result, 'monitoring-stale-retry')
  state.clear(paths.monitoring); await page.getByTestId('retry-monitoring').click()
  await expect(page.getByTestId('monitoring-stale')).toHaveCount(0)
  await allAlerts.click()
  await expect(page.getByTestId('fixture-route')).toHaveText('/health-analysis?tab=alert&patientId=1')
  await expect(page.locator('.el-table__row').filter({ hasText: 'Fictional alert 9' })).toBeVisible()
  assert.equal(state.requests.filter(request => request.path === '/api/alert/records').at(-1).query.patientId, '1')
  await capture(page, result, 'all-alerts-ninth-record')
  // A different patient's in-flight load must not expose the previous patient.
  await open(page, origin, 'monitoring'); await expect(page.locator('.alert-row')).toHaveCount(8); await stopMonitoringTimer(page)
  const switchGate = state.hold(paths.monitoring, request => request.query.patientId === '2')
  await page.getByTestId('fixture-patient').selectOption('2'); await waitGate(switchGate)
  await expect(page.locator('.alert-row')).toHaveCount(0)
  await expect(page.locator('.monitoring-page')).not.toContainText("Fictional patient A's")
  state.clear(paths.monitoring); switchGate.release()
  await expect(page.locator('.monitoring-page h1')).toContainText('Fictional patient B')
  await expect(page.locator('.trend-context')).toContainText('#2')
  state.override(paths.monitoring, { data: snapshot(2, 7, true) }); await page.locator('.refresh-button').click()
  await expect(page.getByText(words.emptyMonitoring, { exact: true })).toBeVisible()
  await expect(page.getByTestId('alert-count')).toContainText(/0\s*\/.*0/)
  await capture(page, result, 'monitoring-empty')
  state.override(paths.monitoring, { data: snapshot(2, 7) }); await page.locator('.refresh-button').click()
  await expect(page.locator('.alert-row')).toHaveCount(8)
  state.override(paths.monitoring, { httpStatus: 403 }); await page.locator('.refresh-button').click()
  await expect(page.getByTestId('retry-monitoring')).toBeVisible()
  await expect(page.locator('.alert-row')).toHaveCount(0)
  await expect(page.getByTestId('view-all-alerts')).toHaveCount(0)
  await expect(page.getByTestId('monitoring-stale')).toHaveCount(0)
  await expect(page.getByTestId('monitoring-trend-data')).toHaveCount(0)
  state.clear(paths.monitoring); await page.getByTestId('retry-monitoring').click()
  await expect(page.locator('.monitoring-page h1')).toContainText('Fictional patient B')
  await expect(page.locator('.alert-row')).toHaveCount(8)
  result.checks.push('Monitoring explicit 403 revocation purges protected alerts, navigation/actions and trend data; retry stays in current patient context')
  result.checks.push('Monitoring loading, stale/read timestamp, retry, successful empty, patient isolation, raw units/time range, shown/loaded count, and full authorized alert route')
}
// Click actionability/native focus may scroll after scrollIntoViewIfNeeded.
// Observe the trusted capture-phase event immediately before the real Vue handler;
// never derive the return-position oracle from the earlier automation position.
async function readEditClickOrigin(opener) {
  await opener.evaluate(element => {
    delete element.__uiSeventeenEditClickOrigin
    element.addEventListener('click', event => {
      const rect = element.getBoundingClientRect()
      element.__uiSeventeenEditClickOrigin = { scrollY: window.scrollY, rowTop: rect.top, rowBottom: rect.bottom, scrollHeight: document.documentElement.scrollHeight, maxScrollY: Math.max(0, document.documentElement.scrollHeight - window.innerHeight), trusted: event.isTrusted }
    }, { capture: true, once: true })
  })
  await opener.click()
  return opener.evaluate(element => element.__uiSeventeenEditClickOrigin)
}
async function editHistory(page, state, result, words, surface) {
  const note = surface === 'bp' ? 'Fictional BP history 17' : 'Fictional nutrition history 17'
  const recordId = surface === 'bp' ? 217 : 317, path = surface === 'bp' ? '/api/bp-self-monitor/update' : '/api/nutrition-diary/update'
  const row = page.locator('.records-panel .el-table__row').filter({ hasText: note })
  const opener = row.getByRole('button', { name: words.edit, exact: true })
  for (const action of ['cancel', 'save']) {
    await opener.scrollIntoViewIfNeeded()
    const beforeClickY = await page.evaluate(() => window.scrollY)
    const origin = await readEditClickOrigin(opener)
    assert.equal(origin?.trusted, true, 'History edit is opened through a trusted native click')
    assert.ok(origin.scrollY > 100, 'History origin must be meaningfully below the editor')
    const observation = { surface, action, beforeClickY, origin }
    result.editOrigins ||= []; result.editOrigins.push(observation)
    const heading = page.locator('.editor-heading')
    await expect(heading).toBeFocused(); await expect(heading).toContainText(words.dated); await expect(heading).toContainText(dateAt(17))
    if (surface === 'bp') await expect(heading).toContainText('08:00')
    const scroll = await page.evaluate(() => window.__uiSeventeenMonitoringEditorScrolls.at(-1))
    assert.deepEqual(scroll, { behavior: 'auto', block: 'start' }, 'Reduced motion uses immediate editor scrolling')
    const bounds = await heading.boundingBox(); assert.ok(bounds.y >= -2 && bounds.y < 500, 'Dated editor heading is scrolled into view')
    await expect(page.locator('.submission-patient')).toContainText(/Fictional patient A.*#1/)
    if (action === 'cancel') {
      await capture(page, result, `${surface}-dated-editor`)
      await page.locator('.form-actions').getByRole('button', { name: words.cancel, exact: true }).click()
    } else {
      await page.locator('.remark-field textarea').fill(`${note} updated by fictional browser acceptance`)
      await page.locator('.form-actions').getByRole('button', { name: words.update, exact: true }).click()
    }
    await expect(opener).toBeFocused()
    await expect.poll(async () => {
      const restored = await opener.evaluate(element => { const rect = element.getBoundingClientRect(); return { scrollY: window.scrollY, rowTop: rect.top, rowBottom: rect.bottom, scrollHeight: document.documentElement.scrollHeight, focused: document.activeElement === element } })
      observation.restored = restored
      return Math.abs(restored.scrollY - origin.scrollY)
    }).toBeLessThanOrEqual(3)
    await expect(page.locator('.form-actions').getByRole('button', { name: words.cancel, exact: true })).toHaveCount(0)
    if (action === 'save') {
      const mutation = state.requests.filter(request => request.path === path).at(-1)
      assert.equal(mutation.method, 'PUT'); assert.equal(mutation.body.patientId, 1); assert.equal(mutation.body.id, recordId)
      await expect(row).toContainText('updated by fictional browser acceptance')
    }
  }
  await capture(page, result, `${surface}-origin-restored`)
  result.checks.push(`${surface}: real history edit, dated heading focus/scroll, explicit cancel, save/cancel original row focus/scroll, reduced motion and exact mutation ownership`)
}
async function bpCase(page, origin, state, cell, result, words) {
  // Change the list filter before the initial summary supplier completes.
  const gate = state.hold(paths.bp, request => !request.query.measureType)
  await open(page, origin, 'bp'); await waitGate(gate)
  await expect(page.locator('.bp-monitor-page [role=status]').filter({ hasText: words.loadingBp })).toBeVisible()
  await expect(page.locator('.records-panel .table-wrap')).toHaveCount(0)
  await filter(page, words.bp)
  await expect(page.locator('.records-panel .el-table__row')).toHaveCount(19)
  gate.release(); state.clear(paths.bp)
  const summary = page.locator('.bp-summary .summary-item')
  await expect(summary.nth(1)).toContainText('144/83 mmHg'); await expect(summary.nth(1)).toContainText('2026-10-10 09:15')
  await expect(summary.nth(2)).toContainText('108 mg/dL'); await expect(summary.nth(2)).toContainText('2026-10-11 07:30')
  if (cell.edition === 'en') { await expect(summary.nth(0)).toContainText('Record count in list'); await expect(summary.nth(1)).toContainText(words.latestBp); await expect(summary.nth(2)).toContainText(words.latestBg) }
  const before = [await summary.nth(1).textContent(), await summary.nth(2).textContent()]
  await filter(page, words.bg); await expect(page.locator('.records-panel .el-table__row')).toHaveCount(1)
  assert.deepEqual([await summary.nth(1).textContent(), await summary.nth(2).textContent()], before)
  const raw = page.getByTestId('bp-trend-data'), disclosure = raw.locator('summary')
  await disclosure.focus(); await page.keyboard.press('Enter'); await expect(raw).toHaveAttribute('open', '')
  await expect(raw.locator('tbody tr')).toHaveCount(1); await expect(raw).toContainText('108 mg/dL'); await expect(raw).toContainText('#1')
  await filter(page, words.all); await expect(page.locator('.records-panel .el-table__row')).toHaveCount(21)
  await expect(raw.locator('tbody tr')).toHaveCount(21)
  await expect(raw.locator('tbody tr').filter({ hasText: '2026-10-10 09:15' })).toContainText(words.marker)
  await expect(raw.locator('tbody tr').filter({ hasText: '2026-10-09 20:00' })).toContainText('5.1 mmol/L')
  await verifyTheme(page, cell, result, 'bp'); await capture(page, result, 'bp-independent-summary-trend')
  state.override(paths.bp, { httpStatus: 500 })
  await page.locator('.records-tools').getByRole('button', { name: words.refresh, exact: true }).click()
  await expect(page.getByTestId('bp-stale')).toContainText(/\d{4}-\d{2}-\d{2}T/)
  await expect(page.locator('.records-panel .el-table__row')).toHaveCount(21)
  await capture(page, result, 'bp-stale-retry')
  state.clear(paths.bp); await page.getByTestId('retry-bp-records').click(); await expect(page.getByTestId('bp-stale')).toHaveCount(0)
  // Close the accessible table before testing history restoration, so chart/list
  // positions are realistic and the same native disclosure remains available.
  await disclosure.click()
  await editHistory(page, state, result, words, 'bp')
  state.override(paths.bp, { data: [] }); await page.locator('.records-tools').getByRole('button', { name: words.refresh, exact: true }).click()
  await expect(page.locator('.records-panel .el-table__empty-block')).toContainText(words.emptyBp)
  await expect(summary.nth(1).locator('strong')).toHaveText('-'); await expect(summary.nth(2).locator('strong')).toHaveText('-')
  state.clear(paths.bp); await page.locator('.records-tools').getByRole('button', { name: words.refresh, exact: true }).click()
  await expect(page.locator('.records-panel .el-table__row')).toHaveCount(21)
  await page.locator('.records-panel .el-table__row').filter({ hasText: 'Fictional BP history 17' }).getByRole('button', { name: words.edit, exact: true }).click()
  await expect(page.locator('.editor-heading')).toBeFocused()
  state.override(paths.bp, { httpStatus: 403 }); await page.locator('.records-tools').getByRole('button', { name: words.refresh, exact: true }).click()
  await expect(page.getByTestId('retry-bp-records')).toBeVisible()
  await expect(page.locator('.records-panel .table-wrap')).toHaveCount(0)
  await expect(page.getByTestId('bp-trend-data')).toHaveCount(0)
  await expect(page.getByTestId('bp-stale')).toHaveCount(0)
  await expect(page.locator('.editor-heading')).not.toContainText(dateAt(17))
  await expect(page.locator('.remark-field textarea')).toHaveValue('')
  await expect(page.locator('.form-actions button').first()).toBeDisabled()
  state.clear(paths.bp)
  result.checks.push('BP explicit 403 clears protected history, trend data, stale fallback and dated editor; submission is disabled until rechecked')
  result.checks.push('BP initial filter/summary request race, independent latest timestamps, loading/stale/retry/empty, raw current-list scope, original units and existing abnormal marker')
}
async function nutritionCase(page, origin, state, cell, result, words) {
  await open(page, origin, 'nutrition')
  await expect(page.locator('.records-panel .el-table__row')).toHaveCount(18)
  await verifyTheme(page, cell, result, 'nutrition')
  await editHistory(page, state, result, words, 'nutrition')
}
async function nurseCase(page, origin, state, cell, result, words) {
  // Disabled/error/forbidden capability states must remain readable OUTSIDE
  // enabled content, and must never fetch the authorized queue.
  for (const [kind, response, text] of [
    ['disabled', { data: { enabled: false } }, words.disabled],
    ['error', { httpStatus: 500 }, words.capabilityError],
    ['forbidden', { httpStatus: 403 }, words.forbidden],
  ]) {
    const capabilityGate = kind === 'disabled' ? state.hold(paths.capabilities) : null
    state.override(paths.capabilities, { ...response, ...(capabilityGate ? { gate: capabilityGate } : {}) })
    const count = state.count(paths.tasks)
    await open(page, origin, 'nurse')
    if (capabilityGate) {
      await waitGate(capabilityGate)
      await expect(page.getByTestId('plan-capability-state')).toContainText(cell.edition === 'en' ? 'Checking plan access' : '正在检查计划访问权限')
      await expect(page.getByTestId('retry-plan-capability')).toHaveCount(0)
      assert.equal(state.count(paths.tasks), count)
      capabilityGate.release()
    }
    await expect(page.getByTestId('plan-capability-state')).toContainText(text)
    await expect(page.getByTestId('retry-plan-capability')).toBeVisible()
    await expect(page.getByTestId('reload-tasks')).toHaveCount(0)
    // Flush browser tasks, then use the persistent visible state as synchronization.
    await page.evaluate(() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done))))
    assert.equal(state.count(paths.tasks), count, `${kind} capability must not dispatch queue request`)
    if (kind === 'forbidden') await capture(page, result, 'nurse-forbidden-capability')
  }
  state.clear(paths.capabilities)
  const gate = state.hold(paths.tasks)
  await page.getByTestId('retry-plan-capability').click(); await waitGate(gate)
  await expect(page.getByTestId('collaboration-tasks').getByRole('status').filter({ hasText: words.loadingTasks })).toBeVisible()
  await expect(page.getByText(words.emptyTasks, { exact: true })).toHaveCount(0)
  state.clear(paths.tasks); gate.release()
  await expect(page.locator('.plan-card h3')).toHaveText('Fictional authorized nurse plan')
  await verifyTheme(page, cell, result, 'nurse')
  await expect(page.getByTestId('plan-action-501')).toHaveAttribute('open', '')
  await expect(page.getByTestId('plan-action-502')).toHaveAttribute('open', '')
  await expect(page.getByTestId('plan-action-504')).toHaveAttribute('open', '')
  await expect(page.getByTestId('plan-action-503')).not.toHaveAttribute('open', '')
  await expect(page.getByTestId('followup-504')).toBeVisible()
  assert.deepEqual(await page.locator('.action-group').evaluateAll(nodes => nodes.map(node => node.dataset.testid)), ['plan-action-501', 'plan-action-504', 'plan-action-502', 'plan-action-503'])
  state.override(paths.tasks, { httpStatus: 500 }); await page.getByTestId('reload-tasks').click()
  await expect(page.getByTestId('plan-tasks-stale')).toContainText(/\d{4}-\d{2}-\d{2}T/)
  await expect(page.locator('.plan-card h3')).toHaveText('Fictional authorized nurse plan')
  await capture(page, result, 'nurse-stale-retry-history')
  state.override(paths.tasks, { httpStatus: 403 }); await page.getByTestId('retry-plan-tasks').click()
  await expect(page.getByTestId('plan-capability-state')).toBeVisible()
  await expect(page.locator('.plan-card')).toHaveCount(0)
  await expect(page.getByTestId('followup-504')).toHaveCount(0)
  await expect(page.getByTestId('plan-tasks-stale')).toHaveCount(0)
  state.override(paths.tasks, { data: { items: [], nextCursor: null } })
  await page.getByTestId('retry-plan-capability').click()
  await expect(page.getByText(words.emptyTasks, { exact: true })).toBeVisible()
  await expect(page.getByTestId('plan-tasks-stale')).toHaveCount(0)
  await capture(page, result, 'nurse-successful-empty')
  state.clear(paths.tasks)
  result.checks.push('Nurse visible disabled/error/forbidden capability and zero unauthorized queue requests, authorized loading/stale timestamp/retry/empty, pending-first actionable and submitted records visible')
}

let browser
try {
  for (const edition of ['en', 'cn']) {
    const harness = await makeHarness(edition)
    let server
    try {
      await build(harness.config)
      report.compiledEditions ||= []; report.compiledEditions.push(edition)
      if (compileOnly) {
        await persist(); console.log(`${edition}: actual monitoring/BP/nutrition/PlanTaskList harness bundle compiled; browser assertions NEVER RUN`)
        continue
      }
      // No socket or browser operation is reachable in compile-only mode.
      browser ||= await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}), timeout: 30000 })
      server = await preview(harness.config)
      const address = server.httpServer.address()
      assert.ok(address && typeof address === 'object')
      const origin = `http://127.0.0.1:${address.port}`
      for (const cell of cells.filter(cell => cell.edition === edition)) {
        const result = { ...cell, itemIds, status: 'failed', screenshots: [], checks: [] }
        const state = fixture(), context = await browser.newContext({ viewport: { width: cell.viewport, height: 900 }, reducedMotion: 'reduce', timezoneId: 'UTC', serviceWorkers: 'block' })
        const errors = []
        let page
        try {
          await context.addInitScript(({ theme }) => {
            const originalScrollIntoView = Element.prototype.scrollIntoView
            window.__uiSeventeenMonitoringEditorScrolls = []
            Element.prototype.scrollIntoView = function(options) {
              if (this.matches('.editor-heading')) window.__uiSeventeenMonitoringEditorScrolls.push(options)
              return originalScrollIntoView.call(this, options)
            }
            const token = 'fictional-browser-fixture-only'
            for (const [key, value] of Object.entries({ token, userId: '71', username: 'fictional-nurse', realName: 'Fictional Nurse', currentPatientId: '1', permissionSession: token, userMenus: JSON.stringify(['/monitoring', '/bp-self-monitor', '/health-analysis?tab=alert', '/health-analysis?tab=nutrition', '/nurse-workspace']), userMenuNames: JSON.stringify(['Monitoring', 'Readings', 'Alerts', 'Nutrition', 'Nurse']), userRoleCodes: JSON.stringify(['nurse']), 'chengxin-health-appearance-v1': JSON.stringify({ version: 1, theme }) })) localStorage.setItem(key, value)
          }, { theme: cell.theme })
          await intercept(context, origin, state)
          page = await context.newPage(); page.setDefaultTimeout(15000); page.setDefaultNavigationTimeout(30000)
          page.on('pageerror', error => errors.push(error.message))
          const words = copy[cell.edition]
          await monitoringCase(page, origin, state, cell, result, words)
          await bpCase(page, origin, state, cell, result, words)
          await nutritionCase(page, origin, state, cell, result, words)
          await nurseCase(page, origin, state, cell, result, words)
          assert.deepEqual(errors, [], 'No uncaught browser errors')
          assert.deepEqual(state.unexpected, [], 'All API requests must be explicitly synthetic fixtures')
          assert.deepEqual(state.blockedNetwork, [], 'No external network requests')
          result.status = 'passed'
        } catch (error) {
          result.error = { message: error.message, stack: error.stack }
          if (page && !page.isClosed()) await capture(page, result, 'FAILED').catch(() => {})
        } finally {
          state.release(); result.apiReads = state.requests.filter(request => request.method === 'GET').map(({ path, query }) => ({ path, query }))
          result.unexpectedApi = state.unexpected; result.blockedNetwork = state.blockedNetwork; result.browserErrors = errors
          await context.close(); report.results.push(result); report.browserAssertions = 'executed'; await persist()
        }
        console.log(`${edition} ${cell.viewport}px ${cell.theme}: ${result.status}`)
      }
    } finally { if (server) await new Promise((resolve, reject) => server.httpServer.close(error => error ? reject(error) : resolve())); await rm(harness.directory, { recursive: true, force: true }) }
  }
  if (!compileOnly) { assert.equal(report.results.length, 24); assert.ok(report.results.every(result => result.status === 'passed'), 'One or more real-browser acceptance cells failed') }
} catch (error) {
  report.error = { message: error.message, stack: error.stack }; process.exitCode = 1
  console.error(error.message)
} finally {
  await browser?.close(); report.finishedAt = new Date().toISOString(); await persist()
}
