import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { ref, effectScope } from 'vue'

const source = name => fs.readFileSync(new URL(`../src/${name}`, import.meta.url), 'utf8')
const dataModule = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const vueUrl = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
let fixtureId = 0
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
async function fixture(t, transport = async () => ({ code: 200, data: { items: [], nextCursor: null } }), patientId = 1) {
  assert.ok(fs.existsSync(new URL('../src/api/carePlan.js', import.meta.url)), 'care-plan API module exists')
  assert.ok(fs.existsSync(new URL('../src/composables/useCarePlan.js', import.meta.url)), 'care-plan composable exists')
  const storage = new Map([['token', 'synthetic-token'], ['userId', '51']]), writes = []
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => { writes.push([key, value]); storage.set(key, String(value)) },
    removeItem: key => storage.delete(key), key: index => [...storage.keys()][index],
    get length() { return storage.size }
  }
  globalThis.window = new EventTarget()
  const calls = [], request = config => { calls.push(config); return transport(config) }
  const slot = `__carePlanTransport${++fixtureId}`
  globalThis[slot] = request
  const authUrl = new URL(`../src/utils/authSession.js?carePlan=${fixtureId}`, import.meta.url).href
  const apiUrl = dataModule(source('api/carePlan.js')
    .replace(/import request from ['"]@\/utils\/request['"];?/, `const request = globalThis.${slot}`)
    .replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`))
  const api = await import(apiUrl)
  const timeUrl = new URL('../src/utils/carePlanTime.js', import.meta.url).href
  const composableSource = source('composables/useCarePlan.js')
    .replace(/from ['"]vue['"]/g, `from '${vueUrl}'`)
    .replace(/from ['"]@\/api\/carePlan['"]/g, `from '${apiUrl}'`)
    .replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`)
    .replace(/from ['"]@\/utils\/carePlanTime['"]/g, `from '${timeUrl}'`)
  const { useCarePlan } = await import(dataModule(`${composableSource}\n// fixture ${fixtureId}`))
  const auth = await import(authUrl), scope = effectScope(), patient = ref(patientId)
  const client = scope.run(() => useCarePlan(patient))
  t.after(() => { scope.stop(); delete globalThis[slot] })
  return { ...client, patient, calls, api, auth, storage, writes }
}

// Changing a wrapper's path or verb must fail the exact request assertions below.
test('thin wrappers send every approved path, verb, params and unchanged body through request', async t => {
  const v = await fixture(t), body = { note: 'Synthetic note', commandKey: 'synthetic-key', expectedVersion: 4 }
  const cases = [
    ['getCarePlanCapabilities', [], '/care-plans/capabilities', 'get'],
    ['listPlans', [{ queue: 'TODAY', dueBefore: '2026-10-04T00:00:00Z' }], '/care-plans', 'get', { queue: 'TODAY', dueBefore: '2026-10-04T00:00:00Z' }],
    ['getPlan', [17], '/care-plans/17', 'get'],
    ['listPlanRevisions', [17, { cursor: 'safe-cursor', limit: 50 }], '/care-plans/17/revisions', 'get', { cursor: 'safe-cursor', limit: 50 }],
    ['getPlanRevision', [17, 23], '/care-plans/17/revisions/23', 'get'],
    ['createDraft', [body], '/care-plans', 'post', undefined, body],
    ['saveDraft', [17, 23, body], '/care-plans/17/revisions/23/save', 'post', undefined, body],
    ['publishPlan', [17, 23, body], '/care-plans/17/revisions/23/publish', 'post', undefined, body],
    ['revisePlan', [17, body], '/care-plans/17/revisions', 'post', undefined, body],
    ['transitionPlan', [17, 'CANCEL', body], '/care-plans/17/cancel', 'post', undefined, body],
    ['transitionPlan', [17, 'CLOSE', body], '/care-plans/17/close', 'post', undefined, body],
    ['submitReceipt', [31, body], '/care-plans/actions/31/receipts', 'post', undefined, body],
    ['requestHelp', [31, body], '/care-plans/actions/31/help', 'post', undefined, body],
    ['followUp', [31, body], '/care-plans/actions/31/follow-ups', 'post', undefined, body],
    ['reviewReceipt', [31, body], '/care-plans/actions/31/reviews', 'post', undefined, body],
    ['listPlanEvents', [17], '/care-plans/17/events', 'get'],
    ['listAssignees', [1], '/care-plans/assignees', 'get', { patientId: 1 }],
    ['listNurseAssignments', [1], '/care-nurse-assignments', 'get', { patientId: 1 }],
    ['assignNurse', [body], '/care-nurse-assignments', 'post', undefined, body],
    ['revokeNurseAssignment', [19], '/care-nurse-assignments/19/revoke', 'post']
  ]
  for (const [name, args, url, method, params, data] of cases) {
    await v.api[name](...args)
    const { expectedAuth, ...config } = v.calls.at(-1)
    assert.deepEqual(expectedAuth, { ...v.auth.captureAuthSession(), actorId: '51' }, name)
    assert.deepEqual(config, { url, method, ...(params === undefined ? {} : { params }), ...(data === undefined ? {} : { data }) }, name)
  }
  const count = v.calls.length
  assert.throws(() => v.api.transitionPlan(17, 'unexpected', body), /CANCEL|CLOSE/)
  assert.equal(v.calls.length, count)
})

test('lateReadCannotReplaceNewPatientOrEditor including A → B → A', async t => {
  const reads = [], v = await fixture(t, () => { const d = deferred(); reads.push(d); return d.promise })
  const older = v.open(17)
  v.patient.value = 2
  assert.equal(v.state.patientId, 2)
  v.patient.value = 1
  const newer = v.open(18, { draft: { note: 'Synthetic unsaved note' } })
  reads[0].resolve({ code: 200, data: { id: 17, patientId: 1, version: 1, title: 'Old' } })
  assert.equal((await older).status, 'stale')
  assert.equal(v.state.patientId, 1)
  assert.equal(v.state.plan, null)
  assert.equal(v.state.draft.note, 'Synthetic unsaved note')
  assert.equal(v.state.opening, true, 'old finally cannot release the new read')
  reads[1].resolve({ code: 200, data: { id: 18, patientId: 1, version: 2 } })
  await newer
  assert.equal(v.state.plan.id, 18)
  assert.equal(v.state.opening, false)
})

test('same-plan editor reopen rejects old response and does not reuse its command key', async t => {
  const pending = deferred(), v = await fixture(t, config => config.method === 'get' ? Promise.resolve({ data: { id: 17, version: 5 } }) : pending.promise)
  await v.open(17, { draft: { note: 'Synthetic first' } })
  const older = v.runCommand('requestHelp', { id: 31, expectedVersion: 5 })
  const firstKey = v.calls.at(-1).data.commandKey
  await v.open(17, { draft: { note: 'Synthetic replacement' } })
  pending.resolve({ data: { eventId: 33, version: 6 } })
  assert.equal((await older).status, 'stale')
  assert.equal(v.state.draft.note, 'Synthetic replacement')
  assert.equal(v.state.result, null)
  await v.runCommand('requestHelp', { id: 31, expectedVersion: 5 })
  assert.notEqual(v.calls.at(-1).data.commandKey, firstKey)
})

test('late command finally cannot unlock a replacement pending command', async t => {
  const saves = [], v = await fixture(t, () => { const d = deferred(); saves.push(d); return d.promise })
  await v.open(null, { draft: { note: 'Synthetic A' } })
  const older = v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
  v.patient.value = 2
  await v.open(null, { draft: { note: 'Synthetic B' } })
  const newer = v.runCommand('requestHelp', { id: 32, expectedVersion: 2 })
  saves[0].reject(new Error('Synthetic offline'))
  assert.equal((await older).status, 'stale')
  assert.equal(v.state.commandPhase, 'pending')
  assert.equal(v.state.draft.note, 'Synthetic B')
  saves[1].resolve({ data: { version: 3 } })
  await newer
  assert.equal(v.state.commandPhase, 'succeeded')
})

test('timeoutRetryReusesCommandKey, payload and version even when current input changes', async t => {
  let count = 0
  const v = await fixture(t, async () => { if (++count === 1) throw Object.assign(new Error('Synthetic timeout'), { code: 'ECONNABORTED' }); return { data: { version: 5 } } })
  await v.open(null, { draft: { note: 'Synthetic original', evidence: [{ sourceType: 'MEASUREMENT', sourceId: 9 }] } })
  const firstResult = await v.runCommand('submitReceipt', { id: 31, expectedVersion: 4 })
  assert.equal(firstResult.status, 'unknown')
  const first = structuredClone(v.calls[0].data)
  v.state.draft.note = 'Synthetic unsaved correction'
  v.state.draft.evidence[0].sourceId = 10
  assert.equal((await v.runCommand('submitReceipt', { id: 31, expectedVersion: 999 })).status, 'retry-required')
  assert.equal(v.calls.length, 1, 'UNKNOWN must not retry automatically')
  await v.runCommand('submitReceipt', { retry: true, id: 31, expectedVersion: 999, payload: { note: 'Changed payload' } })
  const retry = v.calls[1].data
  assert.equal(retry.commandKey, first.commandKey)
  assert.deepEqual(retry, first)
  assert.equal(v.state.draft.note, 'Synthetic unsaved correction')
  assert.equal(v.state.draft.evidence[0].sourceId, 10)
  assert.equal(v.writes.length, 0, 'clinical state remains memory only')
})

for (const error of [Object.assign(new Error('Synthetic HTTP conflict'), { response: { status: 409 } }), Object.assign(new Error('Synthetic envelope conflict'), { code: 409 })]) {
  test(`conflictPreservesInput via ${error.response ? 'HTTP' : 'body code'}`, async t => {
    const v = await fixture(t, async () => { throw error })
    await v.open(null, { draft: { note: 'Synthetic unsaved note' } })
    assert.equal((await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })).status, 'conflict')
    assert.equal(v.state.commandPhase, 'conflict')
    assert.equal(v.state.draft.note, 'Synthetic unsaved note')
    assert.equal(v.state.result, null)
  })
}

test('logoutClearsSensitiveState immediately and rejects an old successful command', async t => {
  const pending = deferred(), v = await fixture(t, () => pending.promise)
  await v.open(null, { draft: { note: 'Synthetic sensitive note' } })
  const saving = v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
  v.auth.clearAuthSession()
  assert.equal(v.state.draft, null)
  assert.deepEqual(v.state.items, [])
  assert.equal(v.state.plan, null)
  assert.equal(v.state.commandKey, null)
  pending.resolve({ data: { note: 'Synthetic old response', version: 2 } })
  assert.equal((await saving).status, 'stale')
  assert.equal(v.state.draft, null)
  assert.equal(v.state.result, null)
})

for (const change of ['token', 'userId', 'same-token-relogin', 'storage-event']) {
  test(`auth session change ${change} clears state and rejects old reads`, async t => {
    const pending = deferred(), v = await fixture(t, () => pending.promise)
    const reading = v.open(17, { draft: { note: 'Synthetic private note' } })
    if (change === 'same-token-relogin') { v.auth.clearAuthSession(); v.auth.saveAuthSession({ token: 'synthetic-token', userId: 51 }) }
    else {
      v.storage.set(change === 'userId' ? 'userId' : 'token', 'different-synthetic-account')
      if (change === 'storage-event') { const e = new Event('storage'); Object.assign(e, { key: 'token' }); window.dispatchEvent(e) }
    }
    pending.resolve({ data: { id: 17, title: 'Synthetic clinical response' } })
    assert.equal((await reading).status, 'stale')
    assert.equal(v.state.draft, null)
    assert.equal(v.state.plan, null)
  })
}

test('list uses selected patient and allows an authorized staff global queue with patient null', async t => {
  const v = await fixture(t)
  await v.load({ queue: 'HELP', patientId: 99 })
  assert.equal(v.calls[0].params.patientId, 1)
  v.patient.value = null
  await v.load({ queue: 'REVIEW' })
  assert.equal(v.calls[1].params.patientId, undefined)
  assert.equal(v.calls[1].params.queue, 'REVIEW')
})

test('TODAY pagination is bound to queue, patient and exclusive local day cutoff', async t => {
  const v = await fixture(t, async () => ({ data: { items: [{ id: 17 }], nextCursor: 'synthetic-next' } }))
  await v.load({ queue: 'TODAY', dueBefore: '2026-10-04T00:00:00Z' })
  assert.equal(v.calls[0].params.dueBefore, '2026-10-04T00:00:00Z')
  assert.equal(v.state.nextCursor, 'synthetic-next')
  const result = await v.load({ queue: 'TODAY', cursor: 'synthetic-next', dueBefore: '2026-10-05T00:00:00Z' })
  assert.equal(result.status, 'stale-cursor')
  assert.equal(v.calls.length, 1)
  assert.deepEqual(v.state.items, [])
  assert.equal(v.state.nextCursor, null)
})

test('old list results and finally cannot overwrite or release a replacement load', async t => {
  const reads = [], v = await fixture(t, () => { const d = deferred(); reads.push(d); return d.promise })
  const first = v.load({ queue: 'HELP' })
  v.patient.value = 2; v.patient.value = 1
  const second = v.load({ queue: 'REVIEW' })
  reads[0].resolve({ data: { items: [{ id: 17 }], nextCursor: 'old' } })
  assert.equal((await first).status, 'stale')
  assert.deepEqual(v.state.items, [])
  assert.equal(v.state.loading, true)
  reads[1].resolve({ data: { items: [{ id: 18 }], nextCursor: null } })
  await second
  assert.deepEqual(v.state.items, [{ id: 18 }])
  assert.equal(v.state.loading, false)
})

test('disposal rejects inflight reads and removes sensitive state', async t => {
  const pending = deferred(), v = await fixture(t, () => pending.promise)
  const reading = v.open(17, { revisionId: 23, draft: { note: 'Synthetic note' } })
  assert.equal(v.calls[0].url, '/care-plans/17/revisions/23')
  v.dispose()
  pending.resolve({ data: { id: 17 } })
  assert.equal((await reading).status, 'stale')
  assert.equal(v.state.draft, null)
})

for (const status of [401, 403, 404]) {
  test(`HTTP ${status} clears sensitive input, detail and command state`, async t => {
    const v = await fixture(t, async () => { throw Object.assign(new Error('Synthetic access lost'), { response: { status } }) })
    await v.open(null, { draft: { note: 'Synthetic sensitive note' } })
    await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
    assert.equal(v.state.draft, null)
    assert.equal(v.state.plan, null)
    assert.equal(v.state.result, null)
    assert.equal(v.state.commandKey, null)
  })
}

test('validation failure preserves editable input and a corrected command gets a new key', async t => {
  let count = 0
  const v = await fixture(t, async () => { if (++count === 1) throw Object.assign(new Error('Synthetic validation'), { code: 400 }); return { data: { version: 2 } } })
  await v.open(null, { draft: { note: 'Synthetic first' } })
  assert.equal((await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })).status, 'failed')
  const key = v.calls[0].data.commandKey
  assert.equal(v.state.draft.note, 'Synthetic first')
  v.state.draft.note = 'Synthetic correction'
  await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
  assert.notEqual(v.calls[1].data.commandKey, key)
  assert.equal(v.calls[1].data.note, 'Synthetic correction')
})

test('UUIDv4 command key works with getRandomValues when randomUUID is unavailable', async t => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  const realCrypto = globalThis.crypto
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues: values => realCrypto.getRandomValues(values) } })
  t.after(() => Object.defineProperty(globalThis, 'crypto', descriptor))
  const v = await fixture(t, async () => ({ data: { version: 1 } }))
  await v.open(null, { draft: { title: 'Synthetic draft' } })
  await v.runCommand('createDraft', { expectedVersion: 0 })
  assert.match(v.calls[0].data.commandKey, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})

test('editor input changed during pending command rejects its result while preserving input', async t => {
  const pending = deferred(), v = await fixture(t, () => pending.promise)
  await v.open(null, { draft: { note: 'Synthetic first' } })
  const saving = v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
  v.state.draft.note = 'Synthetic correction'
  pending.resolve({ data: { version: 2 } })
  assert.equal((await saving).status, 'stale')
  assert.equal(v.state.result, null)
  assert.equal(v.state.draft.note, 'Synthetic correction')
  assert.notEqual(v.state.commandPhase, 'pending')
})

for (const method of ['read', 'command']) {
  test(`late previous-account ${method} completion cannot clear a newly signed-in editor`, async t => {
    const pending = deferred(), v = await fixture(t, config => {
      if (config.url === '/care-plans/18') return Promise.resolve({ data: { id: 18, version: 2 } })
      return pending.promise
    })
    await v.open(null, { draft: { note: 'Synthetic old account' } })
    const older = method === 'read' ? v.open(17) : v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
    v.auth.clearAuthSession()
    v.auth.saveAuthSession({ token: 'new-synthetic-token', userId: 52 })
    await v.open(18, { draft: { note: 'Synthetic new account input' } })
    pending.resolve({ data: { id: 17, version: 2 } })
    assert.equal((await older).status, 'stale')
    assert.equal(v.state.draft?.note, 'Synthetic new account input')
    assert.equal(v.state.plan?.id, 18)
  })
}

test('a silent auth change cannot send an old editor payload as the new account', async t => {
  const v = await fixture(t)
  await v.open(null, { draft: { note: 'Synthetic previous account' } })
  const payload = structuredClone({ note: v.state.draft.note })
  v.storage.set('token', 'new-synthetic-token')
  const result = await v.runCommand('requestHelp', { id: 31, expectedVersion: 1, payload })
  assert.equal(result.status, 'editor-required')
  assert.equal(v.calls.length, 0)
  assert.equal(v.state.draft, null)
})

test('create command body binds patient to the selected context', async t => {
  const v = await fixture(t)
  await v.open(null, { draft: { patientId: 99, title: 'Synthetic selected patient draft' } })
  await v.runCommand('createDraft', { expectedVersion: 0 })
  assert.equal(v.calls[0].data.patientId, 1)
  assert.equal(v.state.draft.patientId, 99, 'input is copied, never silently rewritten')
})

test('fresh editor after UNKNOWN creates a new key and cannot replay the previous editor', async t => {
  const v = await fixture(t, async () => { throw new Error('Synthetic lost response') })
  await v.open(null, { draft: { note: 'Synthetic first editor' } })
  await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
  const oldKey = v.calls[0].data.commandKey
  await v.open(null, { draft: { note: 'Synthetic reopened editor' } })
  assert.equal((await v.runCommand('requestHelp', { retry: true, id: 31 })).status, 'retry-unavailable')
  await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
  assert.notEqual(v.calls[1].data.commandKey, oldKey)
  assert.equal(v.calls[1].data.note, 'Synthetic reopened editor')
})

test('UNKNOWN replay cannot be retargeted and transport mutation cannot change its frozen payload', async t => {
  let count = 0
  const v = await fixture(t, async config => {
    if (++count === 1) { config.data.note = 'Synthetic transport mutation'; throw new Error('Synthetic lost response') }
    return { data: { version: 2 } }
  })
  await v.open(null, { draft: { note: 'Synthetic original' } })
  await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
  assert.equal((await v.runCommand('requestHelp', { retry: true, id: 32 })).status, 'retry-required')
  assert.equal(v.calls.length, 1)
  await v.runCommand('requestHelp', { retry: true })
  assert.equal(v.calls[1].url, '/care-plans/actions/31/help')
  assert.equal(v.calls[1].data.note, 'Synthetic original')
  assert.equal(v.state.draft.note, 'Synthetic original')
})

test('duplicate pending command is suppressed and non-TODAY queues omit cutoff', async t => {
  const pending = deferred(), v = await fixture(t, config => config.method === 'get' ? Promise.resolve({ data: { items: [], nextCursor: null } }) : pending.promise)
  await v.load({ queue: 'REVIEW', dueBefore: '2026-10-04T00:00:00Z' })
  assert.equal(v.calls[0].params.dueBefore, undefined)
  await v.open(null, { draft: { note: 'Synthetic note' } })
  const first = v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
  assert.equal((await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })).status, 'busy')
  assert.equal(v.calls.filter(call => call.method === 'post').length, 1)
  pending.resolve({ data: { version: 2 } }); await first
})

test('local day rollover invalidates an in-flight TODAY result and its cursor', async t => {
  const OriginalDate = globalThis.Date
  let clock = Date.parse('2026-10-03T23:59:00Z')
  globalThis.Date = class extends OriginalDate {
    constructor(...args) { super(...(args.length ? args : [clock])) }
    static now() { return clock }
  }
  t.after(() => { globalThis.Date = OriginalDate })
  const pending = deferred(), v = await fixture(t, () => pending.promise)
  const reading = v.load({ queue: 'TODAY' })
  clock += 24 * 3600000
  pending.resolve({ data: { items: [{ id: 17 }], nextCursor: 'yesterday' } })
  assert.equal((await reading).status, 'stale')
  assert.deepEqual(v.state.items, [])
  assert.equal(v.state.nextCursor, null)
  assert.equal(v.state.loading, false)
})
