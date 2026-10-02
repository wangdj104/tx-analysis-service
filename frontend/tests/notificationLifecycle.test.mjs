import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { effectScope, ref, watch, nextTick } from 'vue'
import { localDateKey } from '../src/utils/familyHealth.js'

const tokenA = 'synthetic-account-A-token', tokenB = 'synthetic-account-B-token'
const accountA = 'synthetic-account-A', accountB = 'synthetic-account-B'
const today = '2026-10-02', due = `${today} 08:00:00`
const task = (id = 41, extra = {}) => ({ id, status: 'PENDING', scheduledAt: due, drugName: 'Synthetic item', dosage: 'Synthetic prescribed dose', ...extra })
const key = item => `${item.id}:${item.snoozeUntil || item.scheduledAt}`
const storageKey = (account = accountA, id = 1) => `medication-notifications:${account}:${id}`
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const settle = async () => { await nextTick(); await nextTick() }

// Execute the production composable and auth-session helpers with real Vue
// watchers. Only browser surfaces and API results are synthetic; no request,
// real credential, browser notification, or permission prompt is used.
function setup(t, options = {}) {
  const scope = effectScope(), mounted = [], unmounted = [], intervals = [], cleared = [], notices = [], reads = [], writes = [], attempts = []
  const values = new Map([['token', tokenA], ['username', accountA]])
  const patientId = ref(1), patientName = ref('Synthetic patient A')
  const failures = { readHistory: false, writeHistory: false, readAll: false }
  let now = new Date(`${today}T12:00:00`).getTime(), focusCount = 0, permissionRequests = 0
  class Clock extends Date {
    constructor(...args) { super(...(args.length ? args : [now])) }
    static now() { return now }
  }
  const storage = {
    get length() { return values.size }, key: index => [...values.keys()][index] ?? null,
    getItem(name) { if (failures.readAll || (failures.readHistory && name.startsWith('medication-notifications:'))) throw new Error('Synthetic storage read failure'); return values.get(name) ?? null },
    setItem(name, value) { if (name.startsWith('medication-notifications:')) { if (failures.writeHistory) throw new Error('Synthetic storage quota failure'); writes.push({ name, value }) } values.set(name, String(value)) },
    removeItem: name => values.delete(name)
  }
  class SyntheticNotification {
    static permission = options.permission || 'granted'
    static requestPermission() { permissionRequests++; throw new Error('Permission must never be requested by the poller') }
    constructor(title, notificationOptions) {
      attempts.push({ title, options: notificationOptions })
      options.beforeNotification?.(attempts.length)
      if (options.failNotification?.(attempts.length)) throw new Error('Synthetic notification constructor failure')
      this.title = title; this.options = notificationOptions; this.closed = false; notices.push(this)
    }
    close() { this.closed = true }
  }
  const events = new EventTarget()
  const window = {
    Notification: SyntheticNotification, dispatchEvent: event => events.dispatchEvent(event),
    setInterval(callback, delay) { intervals.push({ callback, delay }); return intervals.length },
    clearInterval: id => cleared.push(id), focus() { focusCount++ }
  }
  const authSource = fs.readFileSync(new URL('../src/utils/authSession.js', import.meta.url), 'utf8').replace(/^export /gm, '')
  const sessions = new Function('localStorage', 'window', authSource + '\nreturn { captureAuthSession, isAuthSessionCurrent, saveAuthSession, clearAuthSession }')(storage, window)
  const source = fs.readFileSync(new URL('../src/composables/useMedicationNotifications.js', import.meta.url), 'utf8').replace(/^import.*$/gm, '').replace('export function', 'function')
  const bindings = {
    watch, onMounted: callback => mounted.push(callback), onUnmounted: callback => unmounted.push(callback),
    useCurrentPatient: () => ({ currentPatientId: patientId, currentPatientName: patientName }),
    getIntakes: async id => { reads.push(id); return options.getIntakes ? options.getIntakes(id, reads.length) : { data: [task()] } },
    localStorage: storage, window, Notification: SyntheticNotification, Date: Clock,
    localDateKey: () => localDateKey(new Clock()), ...sessions
  }
  scope.run(() => new Function(...Object.keys(bindings), source + '\nuseMedicationNotifications()')(...Object.values(bindings)))
  const unmount = () => { unmounted.splice(0).forEach(callback => callback()); scope.stop() }
  t.after(unmount)
  return {
    values, failures, patientId, patientName, intervals, cleared, notices, reads, writes, attempts, sessions, window, Notification: SyntheticNotification,
    start() { mounted.forEach(callback => callback()) }, poll: () => intervals[0].callback(), unmount,
    setDay: day => { now = new Date(`${day}T12:00:00`).getTime() },
    get focusCount() { return focusCount }, get permissionRequests() { return permissionRequests }
  }
}

for (const change of ['token-and-name', 'username', 'logout', 'same-token-login', 'account-round-trip', 'patient', 'patient-round-trip', 'name', 'name-round-trip', 'unmount']) {
  test(`pending reminder result is discarded after ${change}`, async t => {
    const pending = deferred(), view = setup(t, { getIntakes: () => pending.promise })
    view.start()
    if (change === 'token-and-name') { view.values.set('token', tokenB); view.values.set('username', accountB); view.patientName.value = 'Synthetic patient B' }
    if (change === 'username') view.values.set('username', accountB)
    if (change === 'logout') view.sessions.clearAuthSession()
    if (change === 'same-token-login') { view.sessions.clearAuthSession(); view.sessions.saveAuthSession({ token: tokenA, username: accountA }) }
    if (change === 'account-round-trip') { view.sessions.saveAuthSession({ token: tokenB, username: accountB }); view.sessions.saveAuthSession({ token: tokenA, username: accountA }) }
    if (change.startsWith('patient')) { view.patientId.value = 2; if (change.endsWith('round-trip')) view.patientId.value = 1 }
    if (change.startsWith('name')) { view.patientName.value = 'Synthetic renamed patient'; if (change.endsWith('round-trip')) view.patientName.value = 'Synthetic patient A' }
    if (change === 'unmount') view.unmount()
    pending.resolve({ data: [task()] }); await settle()
    assert.equal(view.notices.length, 0, 'an interrupted request must not emit its old tasks')
    assert.equal(view.writes.length, 0, 'discarded tasks must not be marked delivered')
  })
}

test('a fresh poll after an interrupted request uses the current patient and session', async t => {
  const pending = deferred(), view = setup(t, { getIntakes: (_id, count) => count === 1 ? pending.promise : { data: [task(42, { drugName: 'Synthetic B item' })] } })
  view.start(); view.sessions.saveAuthSession({ token: tokenB, username: accountB }); view.patientId.value = 2; view.patientName.value = 'Synthetic patient B'
  pending.resolve({ data: [task()] }); await settle(); await view.poll()
  assert.deepEqual(view.reads, [1, 2]); assert.equal(view.notices.length, 1)
  assert.match(view.notices[0].title, /Synthetic patient B/)
  assert.match(view.notices[0].options.body, /Synthetic B item/)
  assert.equal(view.values.has(storageKey(accountA)), false)
  assert.deepEqual(JSON.parse(view.values.get(storageKey(accountB, 2))).sent, [key(task(42))])
})

test('polling stays single-flight, uses the normal interval, and stops after unmount', async t => {
  const pending = deferred(), view = setup(t, { getIntakes: (_id, count) => count === 1 ? pending.promise : { data: [task(), task()] } })
  view.start(); assert.equal(view.intervals[0].delay, 30000)
  await Promise.all([view.poll(), view.poll()]); assert.deepEqual(view.reads, [1])
  pending.resolve({ data: [task(), task()] }); await settle(); await view.poll()
  assert.equal(view.notices.length, 1)
  view.unmount(); await view.poll()
  assert.deepEqual(view.reads, [1, 1]); assert.deepEqual(view.cleared, [1])
})

for (const permission of ['default', 'denied']) {
  test(`permission ${permission} never fetches or requests browser permission`, async t => {
    const view = setup(t, { permission }); view.start(); await settle(); await view.poll()
    assert.deepEqual(view.reads, []); assert.equal(view.notices.length, 0); assert.equal(view.permissionRequests, 0)
  })
  test(`permission becoming ${permission} while pending discards delivery and permits later granted retry`, async t => {
    const pending = deferred(), view = setup(t, { getIntakes: () => pending.promise })
    view.start(); view.Notification.permission = permission; pending.resolve({ data: [task()] }); await settle()
    assert.equal(view.notices.length, 0); assert.equal(view.writes.length, 0)
    view.Notification.permission = 'granted'; await view.poll(); assert.equal(view.notices.length, 1)
  })
}

test('permission is rechecked before each emission in a response batch', async t => {
  let view
  view = setup(t, { getIntakes: () => ({ data: [task(), task(42)] }), beforeNotification: count => { if (count === 1) view.Notification.permission = 'denied' } })
  view.start(); await settle(); assert.equal(view.notices.length, 1)
  view.Notification.permission = 'granted'; await view.poll(); assert.equal(view.notices.length, 2)
})

test('valid due statuses, snooze precedence, exact due time, and dose text remain unchanged', async t => {
  const rows = [
    task(), task(42, { status: 'MISSED' }), task(43, { status: 'SNOOZED', scheduledAt: '2099-01-01 08:00:00', snoozeUntil: due }),
    task(44, { status: 'TAKEN' }), task(45, { status: 'SKIPPED' }), task(46, { scheduledAt: '2099-01-01 08:00:00' }),
    task(47, { status: 'SNOOZED', snoozeUntil: '2099-01-01 08:00:00' }), task(48, { scheduledAt: 'invalid' }),
    task(49, { scheduledAt: `${today} 12:00:00` }), task(50, { scheduledAt: null })
  ]
  const view = setup(t, { getIntakes: () => ({ data: rows }) }); view.start(); await settle(); await view.poll()
  assert.deepEqual(view.notices.map(notice => notice.options.tag), ['medication-41', 'medication-42', 'medication-43', 'medication-49'])
  for (const notice of view.notices) { assert.equal(notice.options.body, 'Synthetic item · Synthetic prescribed dose'); assert.equal(notice.options.requireInteraction, true); assert.match(notice.title, /^Synthetic patient A · /) }
  view.notices[0].onclick(); assert.equal(view.focusCount, 1); assert.equal(view.notices[0].closed, true)
})

for (const history of ['null', '{broken', 'true', '[]', '"invalid history"', JSON.stringify({ date: today, sent: {} }), JSON.stringify({ date: today, sent: 7 }), JSON.stringify({ date: today, sent: key(task()) })]) {
  test(`malformed persisted history ${history} does not suppress delivery or break dedupe`, async t => {
    const view = setup(t); view.values.set(storageKey(), history); view.start(); await settle(); await view.poll()
    assert.equal(view.notices.length, 1)
    assert.deepEqual(JSON.parse(view.values.get(storageKey())), { date: today, sent: [key(task())] })
  })
}

test('valid stored delivery keys are retained while malformed sent entries are removed', async t => {
  const view = setup(t, { getIntakes: () => ({ data: [task(), task(42)] }) })
  view.values.set(storageKey(), JSON.stringify({ date: today, sent: [null, key(task()), 42, {}, key(task())] }))
  view.start(); await settle(); await view.poll()
  assert.deepEqual(view.notices.map(notice => notice.options.tag), ['medication-42'])
  assert.deepEqual(JSON.parse(view.values.get(storageKey())).sent, [key(task()), key(task(42))])
})

for (const failure of ['readHistory', 'writeHistory', 'both']) {
  test(`${failure} failure keeps delivered reminders deduped and persists them after recovery`, async t => {
    const view = setup(t)
    view.failures.readHistory = failure !== 'writeHistory'; view.failures.writeHistory = failure !== 'readHistory'
    view.start(); await settle(); await view.poll(); await view.poll()
    assert.equal(view.notices.length, 1, 'successful delivery must survive unavailable persistence')
    view.failures.readHistory = false; view.failures.writeHistory = false; await view.poll()
    assert.equal(view.notices.length, 1)
    assert.deepEqual(JSON.parse(view.values.get(storageKey())).sent, [key(task())])
  })
}

test('a later history read failure retains already loaded successful-delivery keys', async t => {
  const view = setup(t); view.values.set(storageKey(), JSON.stringify({ date: today, sent: [key(task())] }))
  view.start(); await settle(); view.failures.readHistory = true; await view.poll()
  assert.equal(view.notices.length, 0)
})

test('auth-storage read failures are contained and the next available poll retries', async t => {
  const view = setup(t); view.start(); await settle(); view.failures.readAll = true
  await assert.doesNotReject(view.poll())
  view.failures.readAll = false; await view.poll(); assert.equal(view.notices.length, 1)
})

test('memory dedupe is scoped to account, patient and current day when storage is unavailable', async t => {
  const view = setup(t); view.failures.readHistory = true; view.failures.writeHistory = true
  view.start(); await settle(); await view.poll(); assert.equal(view.notices.length, 1)
  view.patientId.value = 2; await view.poll(); assert.equal(view.notices.length, 2)
  view.patientId.value = 1; await view.poll(); assert.equal(view.notices.length, 2)
  view.sessions.saveAuthSession({ token: tokenB, username: accountB }); await view.poll(); assert.equal(view.notices.length, 3)
  view.sessions.saveAuthSession({ token: tokenA, username: accountA }); await view.poll(); assert.equal(view.notices.length, 3)
  view.setDay('2026-10-03'); await view.poll(); await view.poll(); assert.equal(view.notices.length, 4)
  // Revisiting a previous day with unavailable persistence proves old-day memory
  // was discarded rather than retained indefinitely across date changes.
  view.setDay(today); await view.poll(); assert.equal(view.notices.length, 5)
})

test('constructor failure retries the failed delivery without repeating the earlier successful delivery', async t => {
  const view = setup(t, { getIntakes: () => ({ data: [task(), task(42)] }), failNotification: count => count === 2 })
  view.failures.writeHistory = true; view.start(); await settle(); assert.equal(view.notices.length, 1)
  await view.poll(); assert.deepEqual(view.notices.map(notice => notice.options.tag), ['medication-41', 'medication-42'])
  view.failures.writeHistory = false; await view.poll(); assert.equal(view.notices.length, 2)
  assert.deepEqual(JSON.parse(view.values.get(storageKey())).sent, [key(task()), key(task(42))])
})

test('API failure leaves delivery retryable and releases the poll lock', async t => {
  const view = setup(t, { getIntakes: (_id, count) => { if (count === 1) throw new Error('Synthetic API failure'); return { data: [task()] } } })
  view.start(); await settle(); assert.equal(view.notices.length, 0); assert.equal(view.writes.length, 0)
  await view.poll(); assert.equal(view.notices.length, 1); assert.deepEqual(view.reads, [1, 1])
})

for (const missing of ['token', 'patient', 'notification-support']) {
  test(`missing ${missing} stays idle and starts normally once available`, async t => {
    const view = setup(t)
    if (missing === 'token') view.values.delete('token')
    if (missing === 'patient') view.patientId.value = null
    if (missing === 'notification-support') delete view.window.Notification
    view.start(); await settle(); assert.equal(view.reads.length, 0); assert.equal(view.notices.length, 0)
    view.values.set('token', tokenA); view.patientId.value = 1; view.window.Notification = view.Notification
    await view.poll(); assert.equal(view.notices.length, 1)
  })
}

test('history from another day does not suppress a due reminder today', async t => {
  const view = setup(t); view.values.set(storageKey(), JSON.stringify({ date: '2026-10-01', sent: [key(task())] }))
  view.start(); await settle(); assert.equal(view.notices.length, 1)
  assert.deepEqual(JSON.parse(view.values.get(storageKey())), { date: today, sent: [key(task())] })
})

test('a changed due timestamp gets its own delivery key while duplicate timestamps stay deduped', async t => {
  const changed = task(41, { status: 'SNOOZED', snoozeUntil: `${today} 09:00:00` })
  const view = setup(t, { getIntakes: (_id, count) => ({ data: [count === 1 ? task() : changed] }) })
  view.start(); await settle(); await view.poll(); await view.poll()
  assert.equal(view.notices.length, 2)
  assert.deepEqual(JSON.parse(view.values.get(storageKey())).sent, [key(task()), key(changed)])
})

test('missing username retains the existing token-suffix history namespace', async t => {
  const view = setup(t); view.values.delete('username'); view.start(); await settle(); await view.poll()
  assert.equal(view.notices.length, 1)
  assert.deepEqual(JSON.parse(view.values.get(storageKey(tokenA.slice(-20)))).sent, [key(task())])
})

test('localized fallback patient, medication and dose text is unchanged', async t => {
  const view = setup(t, { getIntakes: () => ({ data: [task(41, { drugName: '', dosage: '' })] }) })
  view.patientName.value = ''; view.start(); await settle()
  const chinese = import.meta.url.includes('/cn/frontend/')
  assert.equal(view.notices[0].title, chinese ? '家庭成员 · 用药提醒' : 'Family Member · Medication Reminders')
  assert.equal(view.notices[0].options.body, chinese ? '服药任务 · 遵医嘱服用' : 'medication intake · As prescribed')
})

test('pending result remains retryable after auth storage becomes unreadable', async t => {
  const pending = deferred(), view = setup(t, { getIntakes: () => pending.promise })
  view.start(); view.failures.readAll = true; pending.resolve({ data: [task()] }); await settle()
  assert.equal(view.notices.length, 0); assert.equal(view.writes.length, 0)
  view.failures.readAll = false; await view.poll(); assert.equal(view.notices.length, 1)
})
