import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { ref, reactive, computed, watch, effectScope, nextTick, compile, createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

const flush = async () => { for (let i = 0; i < 8; i++) await nextTick() }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const row = (patientId = 1, id = 11) => ({ id, patientId, recordDate: '2026-10-10', recordTime: '09:15', measureType: 'BP_BG', systolicBp: 144, diastolicBp: 83, bloodGlucose: 108, bgUnit: 'mg/dL', remark: 'Fictional test record' })
function setup(t, name, extra = {}) {
  const file = name === 'PlanTaskList' ? 'components/care-plan/PlanTaskList.vue' : `views/${name}.vue`
  const source = fs.readFileSync(process.env.MONITORING_TEST_SOURCE_ROOT ? `${process.env.MONITORING_TEST_SOURCE_ROOT}/${file}` : new URL(`../src/${file}`, import.meta.url), 'utf8')
  const code = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*?from\s*['"][^'"]+['"];?\s*$/gm, '')
  const patient = ref(1), scope = effectScope(), unmounted = [], mounted = [], reads = [], scrolls = [], writes = [], messages = [], listeners = new Map(), storage = new Map([['token', 'synthetic'], ['userId', '51']])
  const props = reactive({ patientId: 1, mode: 'NURSE', queue: 'TODAY' })
  const state = reactive({ patientId: 1, items: [], nextCursor: null, loading: false })
  const client = { state, reset() { state.patientId = props.patientId; state.items = []; state.nextCursor = null; state.loading = false }, async load() { state.loading = true; state.items = []; await Promise.resolve(); state.loading = false; return { status: 'succeeded' } } }
  const document = { hidden: false, activeElement: null, body: {}, addEventListener() {}, removeEventListener() {} }
  const window = { setInterval: () => 1, clearInterval() {}, addEventListener: (name, callback) => listeners.set(name, callback), removeEventListener: name => listeners.delete(name), matchMedia: () => ({ matches: true }), scrollTo: options => scrolls.push(options), scrollY: 780, getComputedStyle: () => ({ visibility: 'visible' }) }
  const bindings = {
    ref, reactive, computed, watch, nextTick, onMounted: fn => mounted.push(fn), onUnmounted: fn => unmounted.push(fn),
    onBeforeRouteLeave() {}, onBeforeRouteUpdate() {}, defineProps: () => props,
    useCurrentPatient: () => ({ currentPatientId: patient, currentPatientName: ref('Fictional patient') }),
    useCarePlan: () => client, getCarePlanCapabilities: async () => ({ data: { enabled: true } }),
    captureAuthSession: () => ({ token: 'synthetic' }), isAuthSessionCurrent: () => true,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)), removeItem: key => storage.delete(key), clear: () => storage.clear() }, document, window, setInterval: () => 1, clearInterval() {},
    localDateKey: () => '2026-10-11', formatPlanTime: value => value,
    listBpSelfMonitorRecords: async (...args) => { reads.push(args); return { code: 200, data: [row(args[0])] } },
    listDiaries: async id => ({ code: 200, data: [row(id)] }), saveDiary: async () => ({ code: 200 }), updateDiary: async () => ({ code: 200 }),
    saveBpSelfMonitorRecord: async data => { writes.push(data); return { code: 200 } }, updateBpSelfMonitorRecord: async data => { writes.push(data); return { code: 200 } },
    deleteBpSelfMonitorRecord: async id => { writes.push(id); return { code: 200 } }, checkThresholds: async id => { writes.push(id); return { code: 200 } }, acknowledge: async id => { writes.push(id); return { code: 200 } },
    getMonitoringSnapshot: async () => ({ code: 200, data: { patient: { name: 'Fictional patient' }, vitalTrend: [], activeAlerts: [] } }),
    ElMessage: { error: text => messages.push(['error', text]), success: text => messages.push(['success', text]), warning: text => messages.push(['warning', text]) }, ElMessageBox: {},
    readPermissionCache: () => ({ menuPaths: ['/health-analysis'], roleCodes: ['nurse'] }), canAccessWorkspace: () => true, resolveWorkspaceEntry: p => p,
    careMoments: 'synthetic-art', careMomentsSmall: 'synthetic-art', healthJournalSmall: 'synthetic-art', medicationCareSmall: 'synthetic-art', dialysisCareSmall: 'synthetic-art', recordsCareSmall: 'synthetic-art',
    WarningFilled: { template: '<span />' }, SuccessFilled: { template: '<span />' },
    use() {}, CanvasRenderer: {}, LineChart: {}, GridComponent: {}, LegendComponent: {}, MarkLineComponent: {}, TooltipComponent: {},
    ...extra
  }
  const names = ['resolveAlert','accessDenied','runCheck','acknowledgeAlert','handleDelete','loadLatestRecords','currentPatientId','currentPatientName','todayTasks','recentEvents','patientId','metrics','primarySuggestion','statusTone','statusIcon','hasTrendData','latestUpdateText','illustratedShortcuts','formatDateTime','formatRelative','signalTone','shortDateTime','alertLevel','isTaskDone','timeOnly','taskTagType','taskStatusText','taskTypeText','eventTypeText','autoRefresh','checking','formRules','showBpFields','showBgFields','measureLabel','measureTagType','selectedSymptoms','SYMPTOM_OPTIONS','appetiteLabel','appetiteTagType','mealsText','saving','displayTime','originalTiming','safeLink','statusLabel','roleLabel','eventLabel','selected','careMoments','careMomentsSmall','snapshot','loadSnapshot','loadError','loading','lastSuccessAt','hasSnapshot','days','loadedDays','activeAlerts','shownAlerts','allAlertsRoute','canViewAllAlerts','trendRows','trendView','chartOption','state','enabled','message','capabilityLoading','capabilityStatus','initialize','reload','loadMore','orderedActions','isHistoryAction','taskItems','taskLastSuccessAt','taskError','queueLoaded','records','loadRecords','refreshRecords','summaryRecords','summaryError','summaryLoading','summarySuccessAt','latestBpText','latestBgText','latestBpAt','latestBgAt','latestBpRecord','latestBgRecord','filterType','form','formRef','editingId','handleEdit','handleSave','resetForm','cancelEdit','editorHeading','editRecordLabel','setEditOpener','trendRecords','trendRecordAbnormal']
  const view = scope.run(() => new Function(...Object.keys(bindings), code + '\nreturn {' + names.map(n => `${n}:typeof ${n} !== "undefined" ? ${n} : undefined`).join(',') + '}')(...Object.values(bindings)))
  if (view.formRef) view.formRef.value = { validate: async () => true, clearValidate() {} }
  t.after(() => { unmounted.forEach(fn => fn()); scope.stop() })
  return { ...view, ...(name === 'PlanTaskList' ? { patientId: computed(() => props.patientId), mode: computed(() => props.mode), queue: computed(() => props.queue) } : {}), patient, props, client, document, window, reads, scrolls, writes, messages, source, storage, listeners, start: () => mounted.forEach(fn => fn()) }
}
async function html(v) {
  const template = v.source.match(/<template>([\s\S]*?)<\/template>\s*<script setup>/)[1]
  const app = createSSRApp({ setup: () => v, render: compile(template) })
  app.config.warnHandler = () => {}
  app.directive('loading', {})
  for (const tag of new Set(template.match(/(?:el-[a-z-]+|router-link|v-chart|VitalsTrendPanel|ReceiptDialog)/g))) app.component(tag, { props: ['to'], template: tag === 'router-link' ? '<a :href="typeof to === \'string\' ? to : to?.path"><slot /></a>' : tag === 'el-table-column' ? '<div></div>' : '<div><slot /></div>' })
  return renderToString(app)
}

test('monitoring: initial failure is visible with retry and never claims empty queues', async t => {
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: async () => { throw new Error('Fictional offline') } }); await flush()
  const text = await html(v)
  assert.match(text, /Fictional offline/)
  assert.match(text, /data-testid="retry-monitoring"/)
  assert.doesNotMatch(text, /No active alerts|无活动告警|暂无活动告警|No tasks need attention today|今日暂无待办事项/)
})
test('monitoring: automatic failure labels retained results with last successful time, recovery clears it', async t => {
  let fail = false
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: async () => { if (fail) throw new Error('Fictional offline'); return { code: 200, data: { marker: 'same-patient', activeAlerts: [] } } } }); await flush()
  const stamp = v.lastSuccessAt?.value
  assert.ok(stamp)
  fail = true; await v.loadSnapshot(true)
  assert.equal(v.snapshot.value.marker, 'same-patient'); assert.match(v.loadError.value, /offline/)
  assert.match(await html(v), /data-testid="monitoring-stale"/)
  assert.equal(v.lastSuccessAt.value, stamp)
  fail = false; await v.loadSnapshot(true); assert.equal(v.loadError.value, '')
})
test('monitoring: non-success response is a read failure', async t => {
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: async () => ({ code: 500, msg: 'Fictional server failure' }) }); await flush()
  assert.ok(v.loadError.value); assert.equal(v.hasSnapshot?.value, false)
})
test('monitoring: patient switch synchronously clears data and timestamps before stale result', async t => {
  const pending = deferred(); let calls = 0
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: () => ++calls === 1 ? Promise.resolve({ code: 200, data: { marker: 'A' } }) : pending.promise }); await flush()
  v.patient.value = 2
  assert.equal(v.snapshot.value.marker, undefined); assert.equal(v.lastSuccessAt?.value, '')
  pending.resolve({ code: 200, data: {} }); await flush()
})
for (const count of [0, 1, 8, 9]) test(`monitoring: ${count} alerts expose exact shown/total and patient-preserving full route`, async t => {
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: async () => ({ code: 200, data: { activeAlerts: Array.from({ length: count }, (_, id) => ({ id })) } }) }); await flush()
  assert.equal(v.shownAlerts?.value.length, Math.min(count, 8))
  assert.deepEqual(v.allAlertsRoute?.value, { path: '/health-analysis', query: { tab: 'alert', patientId: 1 } })
  assert.match(await html(v), /data-testid="alert-count"/)
})
test('monitoring: data view renders source values, units, date/time and existing abnormal mark', async t => {
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: async () => ({ code: 200, data: { vitalTrend: [{ date: '2026-10-10', time: '09:15', systolic: 144, diastolic: 83, glucose: 108, glucoseUnit: 'mg/dL', abnormal: true }] } }) }); await flush()
  if (v.trendView) v.trendView.value = 'data'
  const text = await html(v)
  assert.match(text, /<table/); assert.match(text, /2026-10-10/); assert.match(text, /108/); assert.match(text, /mg\/dL/); assert.match(text, /Abnormal|异常/)
})

test('BP: latest summaries are sorted across all records and unaffected by the list filter', async t => {
  const readings = [{ ...row(), recordDate: '2026-10-09' }, { ...row(1, 12), systolicBp: null, diastolicBp: null, bloodGlucose: 117, recordDate: '2026-10-11', recordTime: '07:30' }]
  const v = setup(t, 'BpSelfMonitorManager', { listBpSelfMonitorRecords: async (id, type) => ({ code: 200, data: type === 'BP' ? [readings[0]] : readings }) })
  await v.loadRecords(); const bp = v.latestBpText.value, bg = v.latestBgText.value
  assert.match(v.latestBpAt?.value || '', /2026-10-09.*09:15/); assert.match(v.latestBgAt?.value || '', /2026-10-11.*07:30/)
  v.filterType.value = 'BP'; await flush()
  assert.equal(v.latestBpText.value, bp); assert.equal(v.latestBgText.value, bg); assert.match(bg, /117/)
})
test('BP: read error is persistent, old data is labeled, and successful empty is distinct', async t => {
  let phase = 'success'
  const v = setup(t, 'BpSelfMonitorManager', { listBpSelfMonitorRecords: async () => { if (phase === 'failure') throw new Error('Fictional offline'); return { code: 200, data: phase === 'empty' ? [] : [row()] } } })
  await v.loadRecords(); assert.ok(v.lastSuccessAt?.value)
  phase = 'failure'; await v.loadRecords(); assert.equal(v.records.value.length, 1); assert.ok(v.loadError?.value)
  assert.match(await html(v), /data-testid="bp-stale"/)
  phase = 'empty'; await v.loadRecords(); assert.equal(v.records.value.length, 0); assert.equal(v.loadError.value, '')
})
test('BP: accessible trend data retains raw units and existing chart abnormal semantics', async t => {
  const v = setup(t, 'BpSelfMonitorManager'); await v.loadRecords()
  assert.equal(v.trendRecords?.value.length, 1)
  assert.equal(v.trendRecordAbnormal?.(row()), true)
  const text = await html(v)
  assert.match(text, /data-testid="bp-trend-data"/); assert.match(text, /mg\/dL/)
})
for (const name of ['BpSelfMonitorManager', 'NutritionDiaryManager']) {
  test(`${name}: history edit moves and focuses dated editor then cancel restores original row`, async t => {
    const v = setup(t, name), focus = [], heading = { isConnected: true, scrollIntoView: x => focus.push(['heading-scroll', x]), focus: x => focus.push(['heading-focus', x]) }, opener = { isConnected: true, focus: x => focus.push(['row-focus', x]), scrollIntoView: x => focus.push(['row-scroll', x]) }
    assert.ok(v.editorHeading); v.editorHeading.value = heading
    v.handleEdit(row(), { currentTarget: opener }); await flush()
    assert.match(v.editRecordLabel.value, /2026-10-10/)
    assert.deepEqual(focus[0], ['heading-scroll', { behavior: 'auto', block: 'start' }]); assert.equal(focus[1][0], 'heading-focus')
    await v.cancelEdit(); await flush()
    assert.equal(v.editingId.value, null); assert.ok(focus.some(x => x[0] === 'row-focus'))
  })
  test(`${name}: saving edit restores the current row; switch while focus is queued cannot focus A`, async t => {
    const v = setup(t, name), focuses = [], original = { isConnected: true, focus: () => focuses.push('A') }, replacement = { isConnected: true, focus: () => focuses.push('updated'), scrollIntoView() {} }
    assert.equal(typeof v.setEditOpener, 'function')
    v.setEditOpener(11, original); v.handleEdit(row(), { currentTarget: original }); await flush()
    v.setEditOpener(11, replacement); await v.handleSave(); await flush(); assert.ok(focuses.includes('updated'))
    focuses.length = 0; v.handleEdit(row(), { currentTarget: original }); v.patient.value = 2; await flush(); assert.deepEqual(focuses, [])
  })
}
for (const outcome of ['error', 'disabled', 'forbidden']) test(`nurse: ${outcome} capability is visible outside enabled block and never reads queue`, async t => {
  let reads = 0
  const client = { state: reactive({ patientId: 1, items: [], nextCursor: null, loading: false }), reset() { this.state.items = [] }, async load() { reads++; return { status: 'succeeded' } } }
  const v = setup(t, 'PlanTaskList', { useCarePlan: () => client, getCarePlanCapabilities: async () => { if (outcome === 'error') throw new Error('Fictional offline'); if (outcome === 'forbidden') throw Object.assign(new Error('Forbidden'), { status: 403 }); return { data: { enabled: false } } } }); await flush()
  assert.equal(reads, 0); assert.equal(v.enabled.value, false)
  assert.ok(v.capabilityStatus?.value); assert.match(await html(v), /data-testid="plan-capability-state"/)
  assert.doesNotMatch(await html(v), /No authorized tasks|没有已授权的事项/)
})
test('nurse: queue failure retains only same-context data, stamps it, and recovery removes error', async t => {
  const state = reactive({ patientId: 1, items: [], nextCursor: null, loading: false }); let phase = 'success'
  const client = { state, reset() { state.patientId = 1; state.items = []; state.loading = false }, async load() { state.items = []; if (phase === 'failure') return { status: 'failed' }; state.items = [{ id: 7, title: 'Fictional task' }]; return { status: 'succeeded' } } }
  const v = setup(t, 'PlanTaskList', { useCarePlan: () => client }); await flush()
  assert.ok(v.taskLastSuccessAt?.value)
  phase = 'failure'; await v.reload(); assert.equal(v.taskItems?.value.length, 1); assert.ok(v.taskError?.value)
  assert.match(await html(v), /data-testid="plan-tasks-stale"/)
  phase = 'success'; await v.reload(); assert.equal(v.taskError.value, '')
})
test('nurse: permission failure clears stale tasks and prevents periodic unauthorized queue reads', async t => {
  const state = reactive({ patientId: 1, items: [], nextCursor: null, loading: false }); let phase = 'success', calls = 0
  const client = { state, reset() { state.items = [] }, async load() { calls++; if (phase === 'forbidden') { state.items = []; return { status: 'forbidden' } } state.items = [{ id: 7 }]; return { status: 'succeeded' } } }
  const v = setup(t, 'PlanTaskList', { useCarePlan: () => client }); await flush(); phase = 'forbidden'; await v.reload()
  assert.equal(v.taskItems?.value.length, 0); assert.equal(v.enabled.value, false)
  const count = calls; await v.reload(); assert.equal(calls, count)
})
test('plan Today: pending actions precede terminal history without hiding submitted or allowed actions', async t => {
  const v = setup(t, 'PlanTaskList'); await flush()
  assert.equal(typeof v.orderedActions, 'function'); assert.equal(typeof v.isHistoryAction, 'function')
  const confirmed = { id: 1, status: 'CONFIRMED', allowedActions: [] }, submitted = { id: 2, status: 'SUBMITTED' }, open = { id: 3, status: 'OPEN' }, actionableConfirmed = { id: 4, status: 'CONFIRMED', allowedActions: ['FOLLOW_UP'] }
  assert.deepEqual(v.orderedActions({ actions: [confirmed, submitted, open] }).map(x => x.id), [3, 2, 1])
  assert.equal(v.isHistoryAction(confirmed), true); assert.equal(v.isHistoryAction(submitted), false); assert.equal(v.isHistoryAction(actionableConfirmed), false)
  v.props.queue = 'HISTORY'; assert.equal(v.isHistoryAction(confirmed), false)
})
test('nurse: a changed local-day/list context cannot reuse a failed refresh cache', async t => {
  const state = reactive({ patientId: 1, items: [], nextCursor: null, loading: false, listContext: 'day-A' }); let phase = 'success'
  const client = { state, reset() { state.items = [] }, async load() { if (phase === 'failure') { state.listContext = 'day-B'; state.items = []; return { status: 'failed' } } state.items = [{ id: 7 }]; return { status: 'succeeded' } } }
  const v = setup(t, 'PlanTaskList', { useCarePlan: () => client }); await flush(); phase = 'failure'; await v.reload()
  assert.deepEqual(v.taskItems.value, []); assert.equal(v.taskLastSuccessAt.value, '')
})
test('monitoring: full alert route is hidden when the existing permission check rejects that tab', async t => {
  const v = setup(t, 'MonitoringCenter', { canAccessWorkspace: path => path !== '/health-analysis?tab=alert' }); await flush()
  assert.equal(v.canViewAllAlerts.value, false); assert.doesNotMatch(await html(v), /data-testid="view-all-alerts"/)
})
test('BP: first read failure never renders successful-empty chart or list; retry can produce empty data', async t => {
  let fail = true
  const v = setup(t, 'BpSelfMonitorManager', { listBpSelfMonitorRecords: async () => { if (fail) throw new Error('Fictional offline'); return { code: 200, data: [] } } })
  await v.loadRecords(); assert.ok(v.loadError.value); assert.equal(v.lastSuccessAt.value, '')
  const text = await html(v); assert.doesNotMatch(text, /bp-trend-data|class="table-wrap"/); assert.match(text, /retry-bp-records/)
  fail = false; await v.loadRecords(); assert.ok(v.lastSuccessAt.value); assert.equal(v.loadError.value, '')
})
test('nurse: disabled capability section retains its accessible heading', async t => {
  const v = setup(t, 'PlanTaskList', { getCarePlanCapabilities: async () => ({ data: { enabled: false } }) }); await flush()
  const text = await html(v); assert.match(text, /aria-labelledby="plan-task-heading"/); assert.match(text, /id="plan-task-heading"/)
})
for (const surface of ['MonitoringCenter', 'BpSelfMonitorManager']) for (const outcome of ['http', 'application']) test(`${surface}: ${outcome} 403 purges protected data and actions, while explicit retry can recover`, async t => {
  let denied = false
  const read = async () => { if (denied) { if (outcome === 'http') throw Object.assign(new Error('Forbidden'), { response: { status: 403 } }); return { code: 403, msg: 'Forbidden' } } return { code: 200, data: surface === 'MonitoringCenter' ? { patient: { name: 'Fictional restricted patient' }, activeAlerts: [{ id: 123, title: 'Fictional restricted alert' }] } : [row()] } }
  const v = setup(t, surface, surface === 'MonitoringCenter' ? { getMonitoringSnapshot: read } : { listBpSelfMonitorRecords: read })
  if (surface === 'MonitoringCenter') await flush(); else { await v.loadRecords(); v.handleEdit(row()); await flush() }
  denied = true
  if (surface === 'MonitoringCenter') await v.loadSnapshot(); else await v.refreshRecords()
  assert.equal(v.accessDenied?.value, true); assert.equal(v.lastSuccessAt.value, '')
  if (surface === 'MonitoringCenter') { assert.equal(v.snapshot.value.activeAlerts?.length || 0, 0); await v.runCheck(); await v.acknowledgeAlert({ id: 123 }) }
  else { assert.equal(v.records.value.length, 0); assert.equal(v.summaryRecords.value.length, 0); assert.equal(v.summarySuccessAt.value, ''); assert.equal(v.editingId.value, null); assert.equal(v.form.remark, ''); await v.handleSave(); v.handleEdit(row()); await v.handleDelete(row()) }
  assert.deepEqual(v.writes, [])
  const text = await html(v); assert.doesNotMatch(text, /Fictional restricted alert|data-testid="(?:monitoring-stale|bp-stale)"/)
  denied = false
  if (surface === 'MonitoringCenter') await v.loadSnapshot(); else await v.refreshRecords()
  assert.equal(v.accessDenied.value, false); assert.ok(v.lastSuccessAt.value)
})
test('BP: filter switched during first all-record read cannot discard its independent latest summaries', async t => {
  const first = deferred(); let n = 0
  const v = setup(t, 'BpSelfMonitorManager', { listBpSelfMonitorRecords: async () => ++n === 1 ? first.promise : { code: 200, data: [{ ...row(), bloodGlucose: null }] } })
  const initial = v.refreshRecords(); v.filterType.value = 'BP'; await flush()
  first.resolve({ code: 200, data: [row()] }); await initial; await flush()
  assert.match(v.latestBpText.value, /144/); assert.match(v.latestBgText.value, /108/)
  assert.equal(v.records.value[0].bloodGlucose, null)
})
test('BP: a 403 list result invalidates an overlapping authorized summary before it can repopulate the screen', async t => {
  const pending = deferred(); let phase = 'success'
  const v = setup(t, 'BpSelfMonitorManager', { listBpSelfMonitorRecords: async (id, type) => phase === 'success' ? { code: 200, data: [row()] } : type ? Promise.reject(Object.assign(new Error('Forbidden'), { response: { status: 403 } })) : pending.promise })
  await v.loadRecords(); v.filterType.value = 'BP'; await flush(); phase = 'deny'
  const refresh = v.refreshRecords(); await flush(); pending.resolve({ code: 200, data: [row()] }); await refresh
  assert.equal(v.records.value.length, 0); assert.equal(v.summaryRecords.value.length, 0); assert.equal(v.accessDenied?.value, true)
})
for (const surface of ['MonitoringCenter', 'BpSelfMonitorManager']) for (const boundary of ['token', 'userId', 'clear']) test(`${surface}: ${boundary} cross-tab boundary clears same-patient data and rejects late old-account result`, async t => {
  const pending = deferred(); let calls = 0
  const data = () => surface === 'MonitoringCenter' ? { activeAlerts: [{ id: 123, title: 'Fictional prior-account alert' }] } : [row()]
  const read = async () => ++calls === 1 ? { code: 200, data: data() } : pending.promise
  const v = setup(t, surface, surface === 'MonitoringCenter' ? { getMonitoringSnapshot: read } : { listBpSelfMonitorRecords: read }); v.start(); await flush()
  if (surface === 'BpSelfMonitorManager') { v.handleEdit(row()); await flush() }
  const oldRead = surface === 'MonitoringCenter' ? v.loadSnapshot() : v.loadRecords()
  if (boundary === 'token') v.storage.delete('token'); else if (boundary === 'userId') v.storage.set('userId', '52'); else v.storage.clear()
  assert.equal(typeof v.listeners.get('storage'), 'function')
  v.listeners.get('storage')({ key: boundary === 'clear' ? null : boundary })
  assert.equal(v.patient.value, 1)
  assert.equal(v.lastSuccessAt.value, '')
  if (surface === 'MonitoringCenter') assert.equal(v.snapshot.value.activeAlerts?.length || 0, 0)
  else { assert.deepEqual(v.records.value, []); assert.deepEqual(v.summaryRecords.value, []); assert.equal(v.editingId.value, null); assert.equal(v.form.remark, '') }
  pending.resolve({ code: 200, data: data() }); await oldRead; await flush()
  if (surface === 'MonitoringCenter') assert.equal(v.snapshot.value.activeAlerts?.length || 0, 0)
  else assert.deepEqual(v.records.value, [])
  assert.equal(v.lastSuccessAt.value, '')
})
for (const surface of ['MonitoringCenter', 'BpSelfMonitorManager']) test(`${surface}: unrelated cross-tab storage change preserves live clinical content`, async t => {
  const v = setup(t, surface); v.start(); await flush()
  const stamp = v.lastSuccessAt.value
  assert.equal(typeof v.listeners.get('storage'), 'function'); v.listeners.get('storage')({ key: 'theme' }); await flush()
  assert.equal(v.lastSuccessAt.value, stamp)
  if (surface === 'BpSelfMonitorManager') assert.equal(v.records.value.length, 1)
  else assert.equal(v.accessDenied.value, false)
})
for (const boundary of ['403', '403-recovered', 'patient-roundtrip', 'account-roundtrip', 'alert-removed']) test(`monitoring: ${boundary} while resolution prompt is open cannot send a stale note`, async t => {
  const prompt = deferred(), writes = []; let denied = false, present = true
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: async () => { if (denied) throw Object.assign(new Error('Forbidden'), { response: { status: 403 } }); return { code: 200, data: { activeAlerts: present ? [{ id: 123 }] : [] } } }, ElMessageBox: { prompt: () => prompt.promise }, resolve: async (...args) => writes.push(args) }); v.start(); await flush()
  const pending = v.resolveAlert({ id: 123 })
  if (boundary.startsWith('403')) { denied = true; await v.loadSnapshot(); if (boundary.endsWith('recovered')) { denied = false; await v.loadSnapshot() } }
  else if (boundary === 'patient-roundtrip') { v.patient.value = 2; await flush(); v.patient.value = 1; await flush() }
  else if (boundary === 'account-roundtrip') { v.storage.set('userId', '52'); v.listeners.get('storage')({ key: 'userId' }); v.storage.set('userId', '51'); v.listeners.get('storage')({ key: 'userId' }); await v.loadSnapshot() }
  else { present = false; await v.loadSnapshot() }
  prompt.resolve({ value: 'Fictional prior-scope note' }); await pending
  assert.deepEqual(writes, []); assert.deepEqual(v.messages, [])
})
test('monitoring: same-scope refresh allows current alert resolution and freezes the original alert ID', async t => {
  const prompt = deferred(), writes = []
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: async () => ({ code: 200, data: { activeAlerts: [{ id: 123 }] } }), ElMessageBox: { prompt: () => prompt.promise }, resolve: async (...args) => writes.push(args) }); await flush()
  const target = { id: 123 }, pending = v.resolveAlert(target); target.id = 999; await v.loadSnapshot()
  prompt.resolve({ value: ' Fictional current-scope note ' }); await pending
  assert.deepEqual(writes, [[123, 'Fictional current-scope note']]); assert.equal(v.messages.length, 1)
})
test('monitoring: resolution completion after patient replacement has no success or refresh side effects', async t => {
  const operation = deferred(); let reads = 0
  const v = setup(t, 'MonitoringCenter', { getMonitoringSnapshot: async () => { reads++; return { code: 200, data: { activeAlerts: [{ id: 123 }] } } }, ElMessageBox: { prompt: async () => ({ value: 'Fictional current note' }) }, resolve: () => operation.promise }); await flush()
  const pending = v.resolveAlert({ id: 123 }); await flush(); v.patient.value = 2; await flush(); const before = reads
  operation.resolve({ code: 200 }); await pending
  assert.deepEqual(v.messages, []); assert.equal(reads, before)
})
