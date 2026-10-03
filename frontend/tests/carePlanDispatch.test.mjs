import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'

const moduleUrl = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
let sequence = 0
async function fixture(t) {
  const id = ++sequence, base = new URL('../', import.meta.url)
  const source = file => fs.readFileSync(new URL(`src/${file}`, base), 'utf8')
  const storage = new Map([['token', 'synthetic-A'], ['userId', '51']])
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key), key: index => [...storage.keys()][index], get length() { return storage.size }
  }
  globalThis.window = new EventTarget()
  window.location = { pathname: '/care', href: '/care' }
  const messages = [], slot = `__carePlanMessages${id}`
  globalThis[slot] = messages
  const authUrl = new URL(`src/utils/authSession.js?dispatch=${id}`, base).href
  const requestUrl = moduleUrl(source('utils/request.js')
    .replace("from 'axios'", `from '${new URL('node_modules/axios/index.js', base).href}'`)
    .replace("import { ElMessage } from 'element-plus';", `const ElMessage = { error: message => globalThis.${slot}.push(message) };`)
    .replace("from '@/utils/authSession'", `from '${authUrl}'`)
    .replace("from '@/utils/serverText'", `from '${new URL('src/utils/serverText.js', base).href}'`))
  const { default: request } = await import(requestUrl)
  const sent = []
  // This is the only replaced network boundary. All production axios interceptors/transforms execute.
  request.defaults.adapter = async config => {
    sent.push({ url: config.url, method: config.method, authorization: config.headers.Authorization,
      body: config.data == null ? null : JSON.parse(config.data), config })
    return { data: { code: 200, data: { version: 2, items: [], nextCursor: null } }, status: 200, statusText: 'OK', headers: {}, config }
  }
  const apiUrl = moduleUrl(source('api/carePlan.js')
    .replace("from '@/utils/request'", `from '${requestUrl}'`)
    .replace("from '@/utils/authSession'", `from '${authUrl}'`))
  const api = await import(apiUrl), auth = await import(authUrl)
  const vueUrl = new URL('node_modules/vue/dist/vue.runtime.esm-bundler.js', base).href
  const timeUrl = new URL('src/utils/carePlanTime.js', base).href
  const composableUrl = moduleUrl(source('composables/useCarePlan.js')
    .replace("from 'vue'", `from '${vueUrl}'`)
    .replace("from '@/api/carePlan'", `from '${apiUrl}'`)
    .replace("from '@/utils/authSession'", `from '${authUrl}'`)
    .replace("from '@/utils/carePlanTime'", `from '${timeUrl}'`))
  const { useCarePlan } = await import(composableUrl), { ref } = await import(vueUrl)
  const patient = ref(1), client = useCarePlan(patient)
  t.after(() => { client.dispose(); delete globalThis[slot] })
  function changeAuth(kind) {
    if (kind === 'silent-token') storage.set('token', 'synthetic-B')
    else if (kind === 'silent-actor') storage.set('userId', '52')
    else { auth.clearAuthSession(); auth.saveAuthSession({ token: kind === 'same-token' ? 'synthetic-A' : 'synthetic-B', userId: kind === 'same-token' ? 51 : 52 }) }
    // No read: synchronously establish a replacement editor in the new session.
    return client.open(null, { draft: { note: 'Synthetic replacement B editor' } })
  }
  return { ...client, api, auth, request, storage, sent, messages, patient, changeAuth }
}

for (const kind of ['logout-login', 'same-token', 'silent-token', 'silent-actor']) {
  test(`real axios: ${kind} before interceptor dispatches zero commands and preserves replacement editor`, async t => {
    const v = await fixture(t)
    await v.open(null, { draft: { note: 'Synthetic account A clinical payload' } })
    const pending = v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
    await v.changeAuth(kind)
    const outcome = await pending
    assert.equal(v.sent.length, 0, 'no old payload reaches any adapter with replacement credentials')
    assert.equal(outcome.status, 'stale')
    assert.equal(v.state.draft?.note, 'Synthetic replacement B editor')
    assert.notEqual(v.state.editor, null)
    assert.deepEqual(v.messages, [])
  })
  test(`real axios: ${kind} after interceptor but before adapter dispatches zero commands`, async t => {
    const v = await fixture(t)
    await v.open(null, { draft: { note: 'Synthetic account A clinical payload' } })
    let interceptedToken
    v.request.interceptors.request.use(config => {
      const transforms = Array.isArray(config.transformRequest) ? config.transformRequest : [config.transformRequest]
      config.transformRequest = [function (data) {
        interceptedToken = config.authSession.token
        v.changeAuth(kind)
        return data
      }, ...transforms]
      return config
    })
    const outcome = await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })
    assert.equal(interceptedToken, 'synthetic-A', 'auth changes only after the production interceptor ran')
    assert.equal(v.sent.length, 0, 'adapter boundary must recheck after interceptor/transforms')
    assert.equal(outcome.status, 'stale')
    assert.equal(v.state.draft?.note, 'Synthetic replacement B editor')
    assert.deepEqual(v.messages, [])
  })
}

for (const operation of ['load', 'detail', 'revision']) {
  test(`real axios: stale ${operation} read is blocked before adapter dispatch`, async t => {
    const v = await fixture(t)
    const pending = operation === 'load' ? v.load({ queue: 'HELP' }) : v.open(17, operation === 'revision' ? { revisionId: 23 } : {})
    await v.changeAuth('logout-login')
    assert.equal((await pending).status, 'stale')
    assert.equal(v.sent.length, 0)
    assert.equal(v.state.draft?.note, 'Synthetic replacement B editor')
  })
}

const directCalls = [
  ['getCarePlanCapabilities', []], ['listPlans', [{ patientId: 1, queue: 'HELP' }]], ['getPlan', [17]],
  ['listPlanRevisions', [17, { limit: 50 }]], ['getPlanRevision', [17, 23]],
  ['createDraft', [{ patientId: 1, title: 'Synthetic clinical title' }]], ['saveDraft', [17, 23, { note: 'Synthetic draft' }]],
  ['publishPlan', [17, 23, { expectedVersion: 1 }]], ['revisePlan', [17, { expectedVersion: 1 }]],
  ['transitionPlan', [17, 'CANCEL', { reason: 'Synthetic reason' }]], ['submitReceipt', [31, { note: 'Synthetic receipt' }]],
  ['requestHelp', [31, { note: 'Synthetic help' }]], ['followUp', [31, { note: 'Synthetic follow up' }]],
  ['reviewReceipt', [31, { decision: 'CONFIRM' }]], ['listPlanEvents', [17]], ['listAssignees', [1]],
  ['listNurseAssignments', [1]], ['assignNurse', [{ patientId: 1, nurseUserId: 2 }]], ['revokeNurseAssignment', [19]]
]
for (const [name, args] of directCalls) {
  test(`direct ${name} captures auth synchronously and cannot dispatch as a replacement account`, async t => {
    const v = await fixture(t)
    const pending = v.api[name](...args).catch(error => error)
    await v.changeAuth('logout-login')
    const outcome = await pending
    assert.equal(v.sent.length, 0, name)
    assert.equal(outcome?.code, 'ERR_CANCELED')
    assert.deepEqual(v.messages, [])
  })
}

test('care-plan current-session requests still dispatch the exact body with the captured credential', async t => {
  const v = await fixture(t)
  await v.open(null, { draft: { note: 'Synthetic current clinical payload' } })
  assert.equal((await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })).status, 'succeeded')
  assert.equal(v.sent.length, 1)
  assert.equal(v.sent[0].authorization, 'Bearer synthetic-A')
  assert.equal(v.sent[0].body.note, 'Synthetic current clinical payload')
  assert.equal(v.sent[0].body.expectedVersion, 1)
})

test('legacy request without expected-auth option retains then-current interceptor auth behavior', async t => {
  const v = await fixture(t)
  const pending = v.request({ url: '/synthetic-legacy', method: 'post', data: { note: 'Synthetic legacy body' } })
  await v.changeAuth('logout-login')
  assert.equal((await pending).code, 200)
  assert.equal(v.sent.length, 1)
  assert.equal(v.sent[0].authorization, 'Bearer synthetic-B')
  assert.equal(v.sent[0].body.note, 'Synthetic legacy body')
})

for (const change of ['patient', 'editor', 'dispose']) {
  test(`real axios: ${change} reset after transforms cancels old dispatch before adapter`, async t => {
    const v = await fixture(t)
    await v.open(null, { draft: { note: 'Synthetic old editor' } })
    v.request.interceptors.request.use(config => {
      const transforms = Array.isArray(config.transformRequest) ? config.transformRequest : [config.transformRequest]
      config.transformRequest = [function (data) {
        if (change === 'dispose') v.dispose()
        else { if (change === 'patient') v.patient.value = 2; v.open(null, { draft: { note: 'Synthetic replacement editor' } }) }
        return data
      }, ...transforms]
      return config
    })
    assert.equal((await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })).status, 'stale')
    assert.equal(v.sent.length, 0)
    assert.equal(v.state.draft?.note, change === 'dispose' ? undefined : 'Synthetic replacement editor')
    assert.deepEqual(v.messages, [])
  })
}

for (const operation of ['load', 'detail', 'revision']) {
  test(`real axios: ${operation} auth change after interceptor is blocked at adapter`, async t => {
    const v = await fixture(t)
    v.request.interceptors.request.use(config => {
      const transforms = Array.isArray(config.transformRequest) ? config.transformRequest : [config.transformRequest]
      config.transformRequest = [function (data) { v.changeAuth('silent-actor'); return data }, ...transforms]
      return config
    })
    const pending = operation === 'load' ? v.load({ queue: 'HELP' }) : v.open(17, operation === 'revision' ? { revisionId: 23 } : {})
    assert.equal((await pending).status, 'stale')
    assert.equal(v.sent.length, 0)
    assert.equal(v.state.draft?.note, 'Synthetic replacement B editor')
    assert.deepEqual(v.messages, [])
  })
}

test('adapter guard rejects changed Authorization even while captured session remains current', async t => {
  const v = await fixture(t)
  await v.open(null, { draft: { note: 'Synthetic current input' } })
  v.request.interceptors.request.use(config => {
    const transforms = Array.isArray(config.transformRequest) ? config.transformRequest : [config.transformRequest]
    config.transformRequest = [function (data, headers) { headers.set('Authorization', 'Bearer synthetic-unexpected'); return data }, ...transforms]
    return config
  })
  assert.equal((await v.runCommand('requestHelp', { id: 31, expectedVersion: 1 })).status, 'cancelled')
  assert.equal(v.sent.length, 0)
  assert.equal(v.state.commandPhase, 'failed')
  assert.equal(v.state.draft.note, 'Synthetic current input')
  assert.deepEqual(v.messages, [])
})

test('an explicitly supplied old expectedAuth snapshot cannot be recaptured as the new actor', async t => {
  const v = await fixture(t), expectedAuth = { ...v.auth.captureAuthSession(), actorId: '51' }
  await v.changeAuth('logout-login')
  const outcome = await v.api.requestHelp(31, { note: 'Synthetic old captured input' }, { expectedAuth }).catch(error => error)
  assert.equal(outcome?.code, 'ERR_CANCELED')
  assert.equal(outcome?.notDispatched, true)
  assert.equal(v.sent.length, 0)
  assert.equal(v.state.draft.note, 'Synthetic replacement B editor')
})
