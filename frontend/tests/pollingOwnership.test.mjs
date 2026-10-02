import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope, nextTick } from 'vue'

const flush = async () => { for (let n = 0; n < 8; n++) await nextTick() }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }

// Real production setup code and Vue reactivity, with only browser/API boundaries
// replaced. Manual timer ticks represent immediate triggers within the client's
// 30-second timeout; no real request, health data, notification or media is used.
function setup(t, kind) {
  const file = kind === 'monitoring' ? 'MonitoringCenter.vue' : 'FamilyHealthManager.vue'
  const source = fs.readFileSync(new URL(`../src/views/${file}`, import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?from\s*['"][^'"]+['"];?\s*$/gm, '')
  const mounted = [], unmounted = [], timers = new Map(), listeners = new Map(), calls = []
  const patientId = ref(1), scope = effectScope(), route = reactive({ path: '/family-health', query: { tab: 'today' } })
  let timerId = 0
  const window = {
    setInterval(fn, ms) { const id = ++timerId; timers.set(id, { fn, ms }); return id },
    clearInterval(id) { timers.delete(id) }
  }
  const document = { hidden: false, addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name) }
  function request(name, args) { const pending = deferred(); calls.push({ name, args, settled: false, ...pending }); return pending.promise }
  const api = new Proxy({}, { get: (_, name) => (...args) => request(name, args) })
  const dependencies = {
    computed, reactive, ref, watch, onMounted: fn => mounted.push(fn), onUnmounted: fn => unmounted.push(fn),
    useCurrentPatient: () => ({ currentPatientId: patientId }), window, document, api,
    useRoute: () => route, useRouter: () => ({ push() {} }), inject: (_, fallback) => fallback,
    readPermissionCache: () => ({ menuPaths: [], roleCodes: [] }), canAccessWorkspace: () => true,
    localDateKey: () => '2026-10-02', replaceTarget(target, value) { for (const key in target) delete target[key]; Object.assign(target, value || {}) },
    getMonitoringSnapshot: (...args) => request('getMonitoringSnapshot', args),
    checkThresholds: (...args) => request('checkThresholds', args), acknowledge: (...args) => request('acknowledge', args),
    resolve: (...args) => request('resolve', args), actionIntake: (...args) => request('actionIntake', args),
    ElMessage: { success() {}, warning() {}, error() {} }, ElMessageBox: {},
    use() {}, CanvasRenderer: {}, LineChart: {}, GridComponent: {}, LegendComponent: {}, MarkLineComponent: {}, TooltipComponent: {}
  }
  const names = kind === 'monitoring'
    ? ['snapshot', 'loading', 'days', 'loadedDays', 'loadError', 'autoRefresh', 'loadSnapshot', 'handleVisibility', 'resetTimer', 'acknowledgeAlert']
    : ['intakes', 'events', 'schedules', 'target', 'today', 'loading', 'saving', 'reload', 'refreshTasks', 'save', 'eventRange']
  const view = scope.run(() => new Function(...Object.keys(dependencies), source + '\nreturn {' + names.join(',') + '}')(...Object.values(dependencies)))
  const unmount = () => { unmounted.splice(0).forEach(fn => fn()); scope.stop() }
  t.after(unmount)
  return {
    ...view, patientId, calls, timers, listeners, document, unmount,
    start() { mounted.forEach(fn => fn()) },
    async tick() { for (const timer of [...timers.values()]) timer.fn(); await flush() },
    async finish(call, data = []) { assert.ok(!call.settled); call.settled = true; call.resolve({ code: 200, data }); await flush() },
    async fail(call) { assert.ok(!call.settled); call.settled = true; call.reject(new Error('Synthetic read failure')); await flush() },
    pending(name) { return calls.filter(call => !call.settled && (!name || call.name === name)) }
  }
}
async function ready(t, kind) {
  const view = setup(t, kind); view.start()
  for (const call of [...view.calls]) await view.finish(call, kind === 'monitoring' ? { marker: 'initial' } : [])
  return view
}
const finishReload = async (view, calls, id = 1) => {
  for (const call of calls) await view.finish(call, call.name === 'getIntakes' ? [{ id }] : [{ marker: id }])
}

test('monitoring automatic visibility and timer wakes share an in-flight read', async t => {
  const v = await ready(t, 'monitoring')
  v.handleVisibility(); v.handleVisibility(); await v.tick()
  assert.equal(v.pending().length, 1)
  await v.finish(v.pending()[0], { marker: 'fresh' })
  assert.equal(v.snapshot.value.marker, 'fresh')
  await v.tick(); assert.equal(v.pending().length, 1)
})

test('monitoring background wakes do not invalidate an initial manual read', async t => {
  const v = setup(t, 'monitoring'); v.start()
  v.handleVisibility(); await v.tick()
  assert.equal(v.calls.length, 1)
  await v.finish(v.calls[0], { marker: 'initial-result' })
  assert.equal(v.snapshot.value.marker, 'initial-result'); assert.equal(v.loading.value, false)
})

test('monitoring hidden and disabled automatic wakes remain inert', async t => {
  const v = await ready(t, 'monitoring'), before = v.calls.length
  v.document.hidden = true; v.handleVisibility(); await v.tick(); assert.equal(v.calls.length, before)
  v.document.hidden = false; v.autoRefresh.value = false; v.resetTimer(); v.handleVisibility(); await v.tick()
  assert.equal(v.calls.length, before)
  const manual = v.loadSnapshot(); assert.equal(v.calls.length, before + 1)
  await v.finish(v.calls.at(-1), { marker: 'manual' }); await manual
  assert.equal(v.snapshot.value.marker, 'manual')
})

for (const order of ['old-first', 'new-first']) test(`monitoring manual/range change supersedes automatic work: ${order}`, async t => {
  const v = await ready(t, 'monitoring'); v.handleVisibility(); const old = v.calls.at(-1)
  v.days.value = 30; const manual = v.loadSnapshot(), current = v.calls.at(-1)
  assert.notEqual(current, old); assert.deepEqual(current.args, [1, 30])
  if (order === 'old-first') {
    await v.finish(old, { marker: 'obsolete' }); assert.equal(v.loading.value, true)
    v.handleVisibility(); assert.equal(v.calls.at(-1), current)
    await v.finish(current, { marker: 'manual' })
  } else {
    await v.finish(current, { marker: 'manual' }); await v.finish(old, { marker: 'obsolete' })
  }
  await manual; assert.equal(v.snapshot.value.marker, 'manual'); assert.equal(v.loadedDays.value, 30); assert.equal(v.loading.value, false)
})

test('monitoring explicit same-range refresh is not swallowed by background work', async t => {
  const v = await ready(t, 'monitoring'); v.handleVisibility(); const old = v.calls.at(-1)
  const manual = v.loadSnapshot(), current = v.calls.at(-1)
  assert.notEqual(current, old); await v.finish(current, { marker: 'explicit' }); await manual
  await v.finish(old, { marker: 'old' }); assert.equal(v.snapshot.value.marker, 'explicit')
})

test('monitoring post-write silent refresh remains fresh even with an older read pending', async t => {
  const v = await ready(t, 'monitoring'); v.handleVisibility(); const old = v.calls.at(-1)
  const write = v.acknowledgeAlert({ id: 41 }), mutation = v.calls.at(-1)
  assert.equal(mutation.name, 'acknowledge'); assert.deepEqual(mutation.args, [41])
  await v.finish(mutation); const current = v.calls.at(-1)
  assert.equal(current.name, 'getMonitoringSnapshot'); assert.notEqual(current, old)
  await v.finish(current, { marker: 'after-write' }); await write
  await v.finish(old, { marker: 'before-write' }); assert.equal(v.snapshot.value.marker, 'after-write')
})

test('monitoring patient switch starts promptly and rejects the previous patient result', async t => {
  const v = await ready(t, 'monitoring'); v.handleVisibility(); const old = v.calls.at(-1)
  v.patientId.value = 2; await flush(); const current = v.calls.at(-1)
  assert.deepEqual(current.args, [2, 7]); v.handleVisibility(); assert.equal(v.calls.at(-1), current)
  await v.finish(old, { marker: 'patient-a' }); assert.equal(v.loading.value, true)
  await v.finish(current, { marker: 'patient-b' }); assert.equal(v.snapshot.value.marker, 'patient-b')
})

test('monitoring current automatic failure releases coalescing for a later retry', async t => {
  const v = await ready(t, 'monitoring'); v.handleVisibility(); const first = v.calls.at(-1)
  await v.fail(first); v.handleVisibility(); const retry = v.calls.at(-1)
  assert.notEqual(retry, first); await v.finish(retry, { marker: 'retry' }); assert.equal(v.snapshot.value.marker, 'retry')
})

test('monitoring obsolete finally cannot release a newer background read', async t => {
  const v = await ready(t, 'monitoring'); v.handleVisibility(); const old = v.calls.at(-1)
  const manual = v.loadSnapshot(); await v.finish(v.calls.at(-1), { marker: 'manual' }); await manual
  v.handleVisibility(); const current = v.calls.at(-1)
  await v.finish(old, { marker: 'obsolete' }); v.handleVisibility(); await v.tick()
  assert.equal(v.calls.at(-1), current); assert.equal(v.pending().length, 1)
  await v.finish(current, { marker: 'current' }); assert.equal(v.snapshot.value.marker, 'current')
})

test('monitoring clearing the patient invalidates work and releases the busy display', async t => {
  const v = await ready(t, 'monitoring'), request = v.loadSnapshot(), pending = v.calls.at(-1)
  v.patientId.value = null; await flush(); assert.equal(v.loading.value, false)
  const count = v.calls.length; v.handleVisibility(); await v.tick(); assert.equal(v.calls.length, count)
  await v.finish(pending, { marker: 'old-patient' }); await request; assert.equal(v.snapshot.value.marker, undefined)
})

test('monitoring current manual error retains range recovery and permits retry', async t => {
  const v = await ready(t, 'monitoring'); v.days.value = 90
  const failed = v.loadSnapshot(); await v.fail(v.calls.at(-1)); await failed
  assert.equal(v.days.value, 7); assert.match(v.loadError.value, /Synthetic read failure/); assert.equal(v.loading.value, false)
  const retry = v.loadSnapshot(); await v.finish(v.calls.at(-1), { marker: 'recovered' }); await retry
  assert.equal(v.loadError.value, ''); assert.equal(v.snapshot.value.marker, 'recovered')
})

for (const outcome of ['success', 'failure']) test(`monitoring unmount suppresses pending ${outcome} and future callbacks`, async t => {
  const v = await ready(t, 'monitoring'); v.days.value = 30
  const pending = v.loadSnapshot(), call = v.calls.at(-1), before = v.snapshot.value
  v.unmount(); const oldLoading = v.loading.value, oldError = v.loadError.value
  if (outcome === 'success') await v.finish(call, { marker: 'disposed' }); else await v.fail(call)
  await pending; assert.equal(v.snapshot.value, before); assert.equal(v.days.value, 30)
  assert.equal(v.loading.value, oldLoading); assert.equal(v.loadError.value, oldError)
  const count = v.calls.length; v.handleVisibility(); const future = v.loadSnapshot(); await v.tick()
  assert.equal(v.calls.length, count); await future; assert.equal(v.timers.size, 0); assert.equal(v.listeners.size, 0)
})

test('family background timer cannot overtake a full reload or invalidate its intake result', async t => {
  const v = await ready(t, 'family'), explicit = v.reload(), calls = [...v.pending()]
  await v.tick(); await v.tick(); assert.equal(v.pending('getIntakes').length, 1)
  await finishReload(v, calls, 11); await explicit
  assert.deepEqual(v.intakes.value.map(x => x.id), [11]); assert.equal(v.loading.value, false)
})

test('family repeated background ticks remain single-flight and recover after completion', async t => {
  const v = await ready(t, 'family'); await v.tick(); const first = v.calls.at(-1)
  await v.tick(); await v.tick(); assert.equal(v.pending('getIntakes').length, 1)
  await v.finish(first, [{ id: 12 }]); assert.deepEqual(v.intakes.value.map(x => x.id), [12])
  await v.tick(); assert.notEqual(v.calls.at(-1), first)
})

for (const order of ['old-first', 'new-first']) test(`family explicit full reload wins over an old task read: ${order}`, async t => {
  const v = await ready(t, 'family'); await v.tick(); const old = v.calls.at(-1)
  const reload = v.reload(), calls = v.pending().filter(call => call !== old)
  assert.equal(calls.length, 4)
  if (order === 'old-first') {
    await v.finish(old, [{ id: 13 }]); assert.deepEqual(v.intakes.value, []); assert.equal(v.loading.value, true)
    await v.tick(); assert.equal(v.pending('getIntakes').length, 1)
    await finishReload(v, calls, 14)
  } else {
    await finishReload(v, calls, 14); await v.finish(old, [{ id: 13 }])
  }
  await reload; assert.deepEqual(v.intakes.value.map(x => x.id), [14])
  assert.deepEqual(v.events.value, [{ marker: 14 }]); assert.deepEqual(v.schedules.value, [{ marker: 14 }])
})

test('family explicit event-range reload is not coalesced with an older full reload', async t => {
  const v = await ready(t, 'family'), first = v.reload(), old = [...v.pending()]
  v.eventRange.value = ['2026-01-01', '2026-10-02']; const second = v.reload(), current = v.pending().filter(call => !old.includes(call))
  assert.equal(current.length, 4); assert.deepEqual(current.find(call => call.name === 'getTimeline').args, [1, '2026-01-01', '2026-10-02'])
  await finishReload(v, old, 15); await first; assert.equal(v.loading.value, true)
  await finishReload(v, current, 16); await second; assert.deepEqual(v.intakes.value.map(x => x.id), [16])
})

test('family post-write full reload supersedes a pre-write task read', async t => {
  const v = await ready(t, 'family'); await v.tick(); const old = v.calls.at(-1), operation = deferred()
  const save = v.save(() => operation.promise)
  await v.tick(); assert.equal(v.pending('getIntakes').length, 1)
  operation.resolve(); await flush(); const after = v.pending().filter(call => call !== old)
  assert.equal(after.length, 4); await v.tick(); assert.equal(v.pending('getIntakes').length, 2)
  await finishReload(v, after, 17); await save; await v.finish(old, [{ id: 18 }])
  assert.deepEqual(v.intakes.value.map(x => x.id), [17]); assert.equal(v.saving.value, false)
})

test('family skipped save-time ticks do not invalidate an already pending task result', async t => {
  const v = await ready(t, 'family'); await v.tick(); const pending = v.calls.at(-1)
  v.saving.value = true; await v.tick(); v.saving.value = false
  await v.finish(pending, [{ id: 19 }]); assert.deepEqual(v.intakes.value.map(x => x.id), [19])
})

test('family task error releases the guard for retry', async t => {
  const v = await ready(t, 'family'); await v.tick(); const first = v.calls.at(-1)
  await v.fail(first); await v.tick(); const retry = v.calls.at(-1)
  assert.notEqual(retry, first); await v.finish(retry, [{ id: 20 }]); assert.deepEqual(v.intakes.value.map(x => x.id), [20])
})

test('family obsolete task finally cannot release a newer task read after full refresh', async t => {
  const v = await ready(t, 'family'); await v.tick(); const old = v.calls.at(-1)
  const reload = v.reload(); await finishReload(v, v.pending().filter(call => call !== old), 27); await reload
  await v.tick(); const current = v.calls.at(-1)
  await v.finish(old, [{ id: 28 }]); await v.tick(); assert.equal(v.calls.at(-1), current)
  assert.equal(v.pending('getIntakes').length, 1)
  await v.finish(current, [{ id: 29 }]); assert.deepEqual(v.intakes.value.map(x => x.id), [29])
})

test('family clearing the patient suppresses all background and explicit reads', async t => {
  const v = await ready(t, 'family'); await v.tick(); const old = v.calls.at(-1)
  v.patientId.value = null; await flush(); assert.equal(v.loading.value, false)
  const count = v.calls.length; await v.tick(); await v.reload(); assert.equal(v.calls.length, count)
  await v.finish(old, [{ id: 30 }]); assert.deepEqual(v.intakes.value, [])
})

test('family failed full reload releases background work and allows explicit retry', async t => {
  const v = await ready(t, 'family'), request = v.reload(), calls = [...v.pending()]
  const failure = assert.rejects(request, /Synthetic read failure/)
  await v.fail(calls[0]); await failure
  for (const call of calls.slice(1)) await v.finish(call)
  assert.equal(v.loading.value, false); await v.tick(); await v.finish(v.calls.at(-1), [{ id: 21 }])
  const retry = v.reload(); await finishReload(v, [...v.pending()], 22); await retry
  assert.deepEqual(v.intakes.value.map(x => x.id), [22])
})

test('family patient switch starts promptly and old read completion cannot release the new guard', async t => {
  const v = await ready(t, 'family'); await v.tick(); const old = v.calls.at(-1)
  v.patientId.value = 2; await flush(); const current = v.pending().filter(call => call !== old)
  assert.equal(current.length, 4); assert.ok(current.every(call => call.args[0] === 2))
  await v.finish(old, [{ id: 23 }]); await v.tick(); assert.equal(v.pending('getIntakes').length, 1)
  await finishReload(v, current, 24); assert.deepEqual(v.intakes.value.map(x => x.id), [24])
})

test('family unmount invalidates full and task results and blocks saved timer callbacks', async t => {
  const v = await ready(t, 'family'); await v.tick(); const old = v.calls.at(-1)
  const reload = v.reload(), current = v.pending().filter(call => call !== old), callback = [...v.timers.values()][0].fn
  v.unmount(); await v.finish(old, [{ id: 25 }]); await finishReload(v, current, 26); await reload
  assert.deepEqual(v.intakes.value, []); assert.deepEqual(v.events.value, []); assert.equal(v.timers.size, 0)
  const count = v.calls.length, queued = callback(), future = v.reload(); assert.equal(v.calls.length, count)
  await queued; await future
})
