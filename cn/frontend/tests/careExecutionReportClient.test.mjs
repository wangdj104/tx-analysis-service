import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { ref, effectScope, watch } from 'vue'

const moduleUrl = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const base = new URL('../', import.meta.url)
const source = file => fs.readFileSync(new URL(`src/${file}`, base), 'utf8')
const body = { patientId: 1, planId: null, fromDate: null, toDate: null, timeZone: 'UTC', language: 'en' }
const report = note => ({ reportSchemaVersion: 1, patient: { id: 1, displayName: 'Synthetic person' }, currentActions: [{ instruction: note }], completeness: 'COMPLETE' })
const json = data => new Blob([JSON.stringify(data)], { type: 'application/json' })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const tick = () => new Promise(resolve => setImmediate(resolve))
let sequence = 0

// Reuses carePlanDispatch's production-axios / data-module fixture pattern. Only the
// adapter is replaced unless nonCancelable explicitly models a request already delivered.
async function fixture(t, { transport, nonCancelable = false } = {}) {
  for (const file of ['api/careExecutionReport.js', 'composables/useCareExecutionReport.js']) {
    assert.ok(fs.existsSync(new URL(`src/${file}`, base)), `${file} implements the execution report client`)
  }
  const id = ++sequence, slot = `__executionReport${id}`
  const storage = new Map([['token', 'synthetic-A'], ['userId', '51']]), writes = []
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => { writes.push([key, value]); storage.set(key, String(value)) },
    removeItem: key => storage.delete(key), key: index => [...storage.keys()][index], get length() { return storage.size }
  }
  globalThis.window = new EventTarget()
  window.location = { pathname: '/care', href: 'http://localhost/care' }
  const sent = [], messages = [], clicks = [], activeUrls = new Set(), revoked = [], consoleCalls = []
  const originalConsole = console.error, originalDocument = globalThis.document
  console.error = (...args) => consoleCalls.push(args)
  let onCreateUrl, onAppend, clickThrows = false
  window.URL = { createObjectURL() { const url = `blob:synthetic-${id}-${revoked.length}`; activeUrls.add(url); onCreateUrl?.(); return url }, revokeObjectURL(url) { activeUrls.delete(url); revoked.push(url) } }
  globalThis.document = { body: { appendChild() { onAppend?.() } }, createElement: () => ({ style: {}, click() { if (clickThrows) throw new Error('Synthetic private note'); clicks.push({ href: this.href, fileName: this.download }) }, remove() {} }) }
  globalThis[slot] = { messages }
  const authUrl = new URL(`src/utils/authSession.js?executionReport=${id}`, base).href
  const requestUrl = moduleUrl(source('utils/request.js')
    .replace("from 'axios'", `from '${new URL('node_modules/axios/index.js', base).href}'`)
    .replace("import { ElMessage } from 'element-plus';", `const ElMessage = { error: message => globalThis.${slot}.messages.push(message) };`)
    .replace("from '@/utils/authSession'", `from '${authUrl}'`)
    .replace("from '@/utils/serverText'", `from '${new URL('src/utils/serverText.js', base).href}'`))
  const { default: request } = await import(requestUrl)
  request.defaults.adapter = async config => {
    sent.push(config)
    if (transport) return transport(config)
    return { data: config.responseType === 'blob' ? new Blob(['Synthetic file'], { type: 'text/csv' }) : { code: 200, data: report('Synthetic private note') }, status: 200, headers: {}, config }
  }
  globalThis[slot].request = config => { sent.push(config); return transport(config) }
  let apiCode = source('api/careExecutionReport.js').replace("from '@/utils/authSession'", `from '${authUrl}'`)
    .replace("from '@/utils/careExecutionReport'", `from '${new URL('src/utils/careExecutionReport.js', base).href}'`)
  apiCode = nonCancelable ? apiCode.replace(/import request from ['"]@\/utils\/request['"];?/, `const request = globalThis.${slot}.request`) : apiCode.replace("from '@/utils/request'", `from '${requestUrl}'`)
  const apiUrl = moduleUrl(apiCode), api = await import(apiUrl), auth = await import(authUrl)
  const vueUrl = new URL('node_modules/vue/dist/vue.runtime.esm-bundler.js', base).href
  const { useCareExecutionReport } = await import(moduleUrl(source('composables/useCareExecutionReport.js')
    .replace("from 'vue'", `from '${vueUrl}'`)
    .replace("from '@/api/careExecutionReport'", `from '${apiUrl}'`)
    .replace("from '@/utils/authSession'", `from '${authUrl}'`)
    .replace("from '@/utils/careExecutionReport'", `from '${new URL('src/utils/careExecutionReport.js', base).href}'`)))
  const scope = effectScope(), context = ref({ ...body }), client = scope.run(() => useCareExecutionReport(context))
  t.after(() => { scope.stop(); console.error = originalConsole; globalThis.document = originalDocument; delete globalThis[slot] })
  function changeAuth(kind) {
    if (kind === 'silent-token') storage.set('token', 'synthetic-B')
    else if (kind === 'silent-actor') storage.set('userId', '52')
    else if (kind === 'storage') { storage.set('token', 'synthetic-B'); const event = new Event('storage'); event.key = 'token'; window.dispatchEvent(event) }
    else { auth.clearAuthSession(); if (kind !== 'logout') auth.saveAuthSession({ token: kind === 'same-token' ? 'synthetic-A' : 'synthetic-B', userId: kind === 'same-token' ? 51 : 52 }) }
  }
  return { ...client, api, auth, request, context, sent, messages, clicks, activeUrls, revoked, consoleCalls, writes, changeAuth,
    onCreate: fn => { onCreateUrl = fn }, onAppend: fn => { onAppend = fn }, throwOnClick: () => { clickThrows = true } }
}

test('exact POST bodies omit absent optional fields and preserve per-report 45s timeout', async t => {
  const v = await fixture(t), expectedAuth = { ...v.auth.captureAuthSession(), actorId: '51' }, signal = new AbortController().signal
  const result = await v.api.previewExecutionReport(body, { expectedAuth, signal })
  assert.equal(result.code, 200)
  await v.api.exportExecutionReport({ ...body, planId: 7, fromDate: '2026-09-01', toDate: '2026-09-30', format: 'actions_csv' }, { expectedAuth, signal })
  assert.equal(v.request.defaults.timeout, 30000)
  for (const config of v.sent) { assert.equal(config.timeout, 45000); assert.deepEqual(config.expectedAuth, expectedAuth); assert.equal(config.signal, signal); assert.equal(config.params, undefined); assert.equal(config.method, 'post') }
  assert.equal(v.sent[0].url, '/care-plans/reports/preview')
  assert.deepEqual(JSON.parse(v.sent[0].data), { patientId: 1, timeZone: 'UTC', language: 'en' })
  assert.equal(v.sent[1].url, '/care-plans/reports/export')
  assert.equal(v.sent[1].returnExportResponse, true)
  assert.deepEqual(JSON.parse(v.sent[1].data), { patientId: 1, planId: 7, fromDate: '2026-09-01', toDate: '2026-09-30', timeZone: 'UTC', language: 'en', format: 'actions_csv' })
  assert.deepEqual(v.writes, [], 'report body is never persisted')
})

test('new binary opt-in exposes only two headers; legacy callers still receive their Blob', async t => {
  const blob = new Blob(['Synthetic csv'], { type: 'text/csv' })
  const v = await fixture(t, { transport: async config => ({ data: blob, status: 200, config, headers: { 'content-disposition': 'attachment; filename="care-execution-report-en-20261004T173000Z-actions.csv"', 'content-type': 'text/csv', authorization: 'Synthetic secret' } }) })
  const result = await v.request({ url: '/synthetic', responseType: 'blob', returnExportResponse: true })
  assert.deepEqual(result, { blob, headers: { 'content-disposition': 'attachment; filename="care-execution-report-en-20261004T173000Z-actions.csv"', 'content-type': 'text/csv' } })
  assert.equal(await v.request({ url: '/legacy', responseType: 'blob' }), blob)
})

test('lateAResponseCannotReplaceNewA and stale finally cannot release a newer request', async t => {
  const pending = [], v = await fixture(t, { nonCancelable: true, transport: () => { const d = deferred(); pending.push(d); return d.promise } })
  const old = v.preview(); v.context.value.patientId = 2; v.context.value.patientId = 1; const current = v.preview()
  pending[0].resolve({ code: 200, data: report('Old synthetic A') })
  assert.equal((await old).status, 'stale'); assert.equal(v.state.report, null); assert.equal(v.state.phase, 'previewing')
  pending[1].resolve({ code: 200, data: report('New synthetic A') }); await current
  assert.equal(v.state.report.currentActions[0].instruction, 'New synthetic A')
})

for (const [field, value] of [['patientId', 2], ['planId', 5], ['fromDate', '2026-09-01'], ['toDate', '2026-09-30'], ['timeZone', 'Asia/Shanghai'], ['language', 'zh-CN'], ['format', 'pdf']]) {
  test(`formatAndContextChangesInvalidatePreview: ${field}`, async t => {
    const v = await fixture(t); await v.preview(); assert.ok(v.state.report)
    v.context.value[field] = value
    assert.equal(v.state.report, null); assert.equal(v.state.error, null); assert.equal(v.state.lastExport, null); assert.equal(v.state.phase, 'idle')
  })
}

for (const kind of ['logout', 'logout-login', 'same-token', 'silent-token', 'silent-actor', 'storage']) {
  test(`oldAccountBlobNeverDownloads: ${kind}`, async t => {
    const d = deferred(), v = await fixture(t, { nonCancelable: true, transport: () => d.promise })
    const pending = v.download('pdf'); v.changeAuth(kind)
    d.resolve({ blob: new Blob(['Synthetic private note'], { type: 'application/pdf' }), headers: {} })
    assert.equal((await pending).status, 'stale'); assert.deepEqual(v.clicks, []); assert.equal(v.activeUrls.size, 0); assert.equal(v.state.report, null); assert.equal(v.state.error, null)
  })
  test(`expectedAuth blocks ${kind} before adapter dispatch`, async t => {
    const v = await fixture(t), pending = v.preview(); v.changeAuth(kind)
    assert.equal((await pending).status, 'stale'); assert.equal(v.sent.length, 0); assert.deepEqual(v.messages, [])
  })
}

test('expectedAuth is rechecked after request transforms, immediately before dispatch', async t => {
  const v = await fixture(t)
  v.request.interceptors.request.use(config => { config.transformRequest = [function(data) { v.changeAuth('silent-actor'); return data }, ...config.transformRequest]; return config })
  assert.equal((await v.download('pdf')).status, 'stale'); assert.equal(v.sent.length, 0)
})

test('direct API captures authentication at invocation instead of recapturing a changed account', async t => {
  const v = await fixture(t), pending = v.api.exportExecutionReport({ ...body, format: 'pdf' }).catch(error => error)
  v.changeAuth('logout-login')
  assert.equal((await pending).code, 'ERR_CANCELED'); assert.equal(v.sent.length, 0)
})

test('duplicate clicks and preview/export overlap issue only one request', async t => {
  const d = deferred(), v = await fixture(t, { nonCancelable: true, transport: () => d.promise })
  const first = v.download('pdf'), second = v.download('pdf'), preview = v.preview()
  assert.equal(v.sent.length, 1); assert.equal((await second).status, 'stale'); assert.equal((await preview).status, 'stale')
  d.resolve({ blob: new Blob(['Synthetic file']), headers: {} }); assert.equal((await first).status, 'downloaded'); assert.equal(v.clicks.length, 1); assert.equal(v.activeUrls.size, 0)
})

for (const action of ['clear', 'dispose']) {
  test(`${action} aborts pending work silently and never accepts a late response`, async t => {
    const d = deferred(), v = await fixture(t, { nonCancelable: true, transport: () => d.promise })
    const pending = v.preview(); const signal = v.sent[0].signal; v[action]()
    assert.equal(signal.aborted, true)
    d.resolve({ code: 200, data: report('Synthetic private note') })
    assert.equal((await pending).status, 'stale'); assert.equal(v.state.report, null); assert.equal(v.activeUrls.size, 0); assert.deepEqual(v.messages, [])
  })
}

for (const boundary of ['create', 'append', 'click-fails', 'success']) {
  test(`all object URLs revoked and context checked at DOM boundary: ${boundary}`, async t => {
    const v = await fixture(t)
    if (boundary === 'create') v.onCreate(() => v.clear())
    if (boundary === 'append') v.onAppend(() => v.changeAuth('silent-actor'))
    if (boundary === 'click-fails') v.throwOnClick()
    const outcome = await v.download('actions_csv')
    assert.equal(v.activeUrls.size, 0); assert.ok(v.revoked.length > 0)
    assert.equal(v.clicks.length, boundary === 'success' ? 1 : 0)
    assert.equal(outcome.status, boundary === 'success' ? 'downloaded' : boundary === 'click-fails' ? 'failed' : 'stale')
  })
}

for (const status of [400, 403, 404, 409, 422]) {
  test(`blobErrorKeepsLimitMetadata: HTTP ${status} keeps only safe metadata`, async t => {
    const errorCode = { 400: 'INVALID_REQUEST', 403: 'ACCESS_DENIED', 404: 'FEATURE_DISABLED', 409: 'REPORT_ACCESS_CHANGED', 422: 'REPORT_LIMIT_EXCEEDED' }[status]
    let failing = false
    const v = await fixture(t, { transport: async config => {
      if (!failing) return { data: { code: 200, data: report('Synthetic private note') }, status: 200, headers: {}, config }
      throw Object.assign(new Error('Synthetic private note'), { config, response: { status, headers: { 'content-type': 'application/json' }, data: json({ code: status, msg: 'Synthetic private note', data: { errorCode, ...(status === 422 ? { limitKind: 'PERIOD_EVENTS', limit: 5000 } : {}), privateNote: 'Synthetic private note' } }) } })
    } })
    await v.preview(); failing = true
    assert.equal((await v.download('pdf')).status, 'failed'); assert.equal(v.state.report, null)
    assert.deepEqual(v.state.error, { status, errorCode, ...(status === 422 ? { limitKind: 'PERIOD_EVENTS', limit: 5000 } : {}) })
    assert.equal(JSON.stringify(v.state).includes('Synthetic private note'), false); assert.equal(v.activeUrls.size, 0)
    assert.equal(JSON.stringify(v.consoleCalls).includes('Synthetic private note'), false)
  })
}

test('JSON Result error on successful binary HTTP response retains safe limit metadata', async t => {
  const v = await fixture(t, { transport: async config => ({ config, status: 200, headers: { 'content-type': 'application/json' }, data: json({ code: 422, msg: 'Limit', data: { errorCode: 'REPORT_LIMIT_EXCEEDED', limitKind: 'QUESTIONS', limit: 200 } }) }) })
  assert.equal((await v.download('pdf')).status, 'failed')
  assert.deepEqual(v.state.error, { status: 422, errorCode: 'REPORT_LIMIT_EXCEEDED', limitKind: 'QUESTIONS', limit: 200 })
})

test('header-typed JSON error Blob is parsed even when Blob.type is empty', async t => {
  const v = await fixture(t, { transport: async config => { throw Object.assign(new Error('Request failed'), { config, response: { status: 409, headers: { 'content-type': 'application/json' }, data: new Blob([JSON.stringify({ code: 409, data: { errorCode: 'REPORT_ACCESS_CHANGED' } })]) } }) } })
  await v.download('pdf'); assert.equal(v.state.error.errorCode, 'REPORT_ACCESS_CHANGED')
})

test('a failed full preview does not disable an independent smaller CSV download', async t => {
  const v = await fixture(t, { transport: async config => {
    if (config.url.endsWith('/preview')) throw Object.assign(new Error('Limit'), { config, response: { status: 422, data: { code: 422, data: { errorCode: 'REPORT_LIMIT_EXCEEDED', limitKind: 'QUESTIONS', limit: 200 } } } })
    return { config, status: 200, headers: {}, data: new Blob(['Synthetic csv'], { type: 'text/csv' }) }
  } })
  assert.equal((await v.preview()).status, 'failed'); assert.equal((await v.download('actions_csv')).status, 'downloaded'); assert.equal(v.clicks.length, 1)
})

test('Abort is silent and raw request/error/config/header data never reaches console', async t => {
  const v = await fixture(t), secret = 'Synthetic private note'
  await v.request.interceptors.request.handlers[0].rejected(Object.assign(new Error(secret), { config: { headers: { Authorization: secret }, data: secret }, response: { status: 422, data: { msg: secret, data: { errorCode: secret } } } })).catch(() => {})
  await v.request.interceptors.response.handlers[0].rejected(Object.assign(new Error(secret), { config: { headers: { Authorization: secret }, data: secret }, response: { status: 500, data: { msg: secret } } })).catch(() => {})
  assert.ok(v.consoleCalls.length >= 2); assert.equal(JSON.stringify(v.consoleCalls).includes(secret), false)
  const d = deferred(); v.request.defaults.adapter = () => d.promise
  const pending = v.preview(); await tick(); v.clear(); d.resolve({ config: v.sent.at(-1) || {}, status: 200, headers: {}, data: { code: 200 } }); await pending
  assert.equal(v.state.error, null)
})

for (const language of ['en', 'zh-CN']) {
  test(`safe filename accepts matching quoted/RFC5987 server name and rejects mismatched or private names: ${language}`, async t => {
    const valid = `care-execution-report-${language}-20261004T173000Z-actions.csv`
    let disposition
    const v = await fixture(t, { transport: async config => ({ data: new Blob(['Synthetic csv']), status: 200, config, headers: { 'content-disposition': disposition } }) })
    for (const header of [`attachment; filename="${valid}"`, `attachment; filename*=UTF-8''${encodeURIComponent(valid)}`]) {
      disposition = header; assert.equal((await v.api.exportExecutionReport({ ...body, language, format: 'actions_csv' })).fileName, valid)
    }
    for (const header of [undefined, 'attachment; filename="Synthetic private note.csv"', `attachment; filename="../${valid}"`, `attachment; filename*=UTF-8''${valid}%0A`, `attachment; filename="${valid.replace('-actions.csv', '-events.csv')}"`, `attachment; filename="${valid.replace(language, language === 'en' ? 'zh-CN' : 'en')}"`, `attachment; filename*=UTF-8''%2F${valid}`, `attachment; filename*=UTF-8''%ZZ`, `attachment; filename="${valid}"; filename="evil.csv"`]) {
      disposition = header; assert.equal((await v.api.exportExecutionReport({ ...body, language, format: 'actions_csv' })).fileName, 'care-execution-report-download.csv', String(header))
    }
  })
}

test('report clinical free text remains unchanged even when it matches localization dictionary entries', async t => {
  const content = { ...report('Saved successfully'), questions: [{ title: 'No data', description: 'Normal', answer: 'Success' }] }
  const v = await fixture(t, { transport: async config => ({ data: { code: 200, data: content }, status: 200, headers: {}, config }) })
  await v.preview(); assert.deepEqual(JSON.parse(JSON.stringify(v.state.report)), content)
})

for (const operation of ['preview', 'download']) {
  test(`already-delivered JSON Blob conversion rejects old ${operation} response without stale messages`, async t => {
    const reading = deferred(), entered = deferred()
    const content = json({ code: 409, msg: 'Synthetic private note', data: { errorCode: 'REPORT_ACCESS_CHANGED' } })
    content.text = () => { entered.resolve(); return reading.promise }
    const v = await fixture(t, { transport: async config => { throw Object.assign(new Error('Failure'), { config, response: { status: 409, headers: {}, data: content } }) } })
    const pending = operation === 'preview' ? v.preview() : v.download('pdf')
    await entered.promise
    v.context.value.patientId = 2
    reading.resolve(JSON.stringify({ code: 409, msg: 'Synthetic private note', data: { errorCode: 'REPORT_ACCESS_CHANGED' } }))
    assert.equal((await pending).status, 'stale'); assert.equal(v.state.error, null); assert.equal(v.state.report, null); assert.deepEqual(v.messages, [])
  })
}

test('already-delivered fulfilled error Blob conversion is silent after logout', async t => {
  const reading = deferred(), entered = deferred(), content = json({})
  content.text = () => { entered.resolve(); return reading.promise }
  const v = await fixture(t, { transport: async config => ({ config, status: 200, headers: {}, data: content }) })
  const pending = v.download('pdf'); await entered.promise; v.changeAuth('logout')
  reading.resolve(JSON.stringify({ code: 422, msg: 'Synthetic private note', data: { errorCode: 'REPORT_LIMIT_EXCEEDED', limitKind: 'QUESTIONS', limit: 200 } }))
  assert.equal((await pending).status, 'stale'); assert.deepEqual(v.messages, []); assert.equal(v.state.error, null)
})

test('same-context AbortError remains silent and retry can proceed', async t => {
  let canceled = true
  const v = await fixture(t, { nonCancelable: true, transport: () => canceled ? Promise.reject(Object.assign(new Error('Synthetic private note'), { name: 'AbortError' })) : Promise.resolve({ code: 200, data: report('New synthetic report') }) })
  assert.equal((await v.preview()).status, 'stale'); assert.equal(v.state.phase, 'idle'); assert.equal(v.state.error, null)
  canceled = false; assert.equal((await v.preview()).status, 'succeeded')
})

for (const format of [undefined, '', 'exe', 'PREVIEW']) {
  test(`invalid download format cannot accidentally issue a preview: ${String(format)}`, async t => {
    const v = await fixture(t)
    assert.equal((await v.download(format)).status, 'failed'); assert.equal(v.sent.length, 0); assert.equal(v.state.report, null); assert.deepEqual(v.clicks, [])
  })
}

for (const format of ['html', 'pdf', 'actions_csv', 'events_csv']) {
  test(`every supported format downloads fresh bytes and clears the earlier preview: ${format}`, async t => {
    const v = await fixture(t)
    await v.preview(); assert.ok(v.state.report)
    assert.equal((await v.download(format)).status, 'downloaded')
    assert.equal(v.state.report, null); assert.equal(v.sent.length, 2); assert.equal(JSON.parse(v.sent[1].data).format, format)
    assert.equal(v.clicks.length, 1); assert.equal(v.activeUrls.size, 0)
  })
}

test('auth notification clears ready report and sanitized error immediately', async t => {
  const v = await fixture(t); await v.preview(); assert.ok(v.state.report)
  v.changeAuth('logout-login'); assert.equal(v.state.report, null); assert.equal(v.state.error, null)
  v.state.error = { status: 422, errorCode: 'REPORT_LIMIT_EXCEEDED' }
  v.changeAuth('storage'); assert.equal(v.state.error, null)
})

test('header-only CSV publishes fresh non-private export metadata before browser download', async t => {
  const csv = new Blob(['\uFEFF"Header one","Header two"\r\n'], { type: 'text/csv;charset=UTF-8' })
  const v = await fixture(t, { transport: async config => ({ data: csv, status: 200, config, headers: { 'content-disposition': 'attachment; filename="care-execution-report-en-20261004T173000Z-actions.csv"' } }) })
  Object.assign(v.context.value, { fromDate: '2026-09-01', toDate: '2026-09-30', planId: 7 })
  const observed = [], stop = watch(() => v.state.lastExport, value => { if (value) observed.push({ clicks: v.clicks.length, rows: value.rowCount }) }, { flush: 'post' }); t.after(stop)
  assert.equal((await v.download('actions_csv')).status, 'downloaded')
  assert.deepEqual(observed, [{ clicks: 0, rows: 0 }])
  assert.deepEqual(JSON.parse(JSON.stringify(v.state.lastExport)), { format: 'actions_csv', reportSchemaVersion: 1, scope: { patientId: 1, planId: 7, label: 'SINGLE_PLAN' }, fromDate: '2026-09-01', toDate: '2026-09-30', rangeKnown: true, timeZone: 'UTC', language: 'en', generatedAt: '2026-10-04T17:30:00Z', rowCount: 0, isEmpty: true })
  v.clear(); assert.equal(v.state.lastExport, null)
})

test('invalid filename and omitted dates leave unknown metadata instead of guessing server times', async t => {
  const v = await fixture(t)
  await v.download('actions_csv')
  assert.equal(v.state.lastExport.generatedAt, null); assert.equal(v.state.lastExport.rangeKnown, false)
  assert.equal(v.state.lastExport.fromDate, null); assert.equal(v.state.lastExport.toDate, null)
})

for (const transition of ['context', 'auth']) {
  test(`${transition} change during CSV scanning drops bytes and export metadata`, async t => {
    const gate = deferred(), entered = deferred(), csv = 'header\r\n"Synthetic private note"\r\n', blob = new Blob([csv])
    blob.stream = () => new ReadableStream({ async pull(controller) { entered.resolve(); await gate.promise; controller.enqueue(new TextEncoder().encode(csv)); controller.close() } })
    const v = await fixture(t, { nonCancelable: true, transport: async () => ({ blob, headers: {} }) })
    const pending = v.download('events_csv'); await Promise.race([entered.promise, pending.then(() => assert.fail('CSV must be scanned before download'))])
    if (transition === 'context') v.context.value.patientId = 2; else v.changeAuth('silent-actor')
    gate.resolve(); assert.equal((await pending).status, 'stale'); assert.equal(v.state.lastExport, null); assert.deepEqual(v.clicks, []); assert.equal(v.activeUrls.size, 0)
  })
  test(`${transition} change after export metadata publication prevents actual click`, async t => {
    const v = await fixture(t), stop = watch(() => v.state.lastExport, value => {
      if (!value) return
      if (transition === 'context') v.context.value.patientId = 2; else v.changeAuth('silent-actor')
    }, { flush: 'post' }); t.after(stop)
    assert.equal((await v.download('actions_csv')).status, 'stale'); assert.equal(v.state.lastExport, null); assert.deepEqual(v.clicks, []); assert.equal(v.activeUrls.size, 0)
  })
}

// Vue sync subscribers run inside the assignment, so publishing state is an ownership boundary.
test('preview publication cannot overwrite the phase of a reentrant replacement request', async t => {
  const reads = [], v = await fixture(t, { nonCancelable: true, transport: () => { const d = deferred(); reads.push(d); return d.promise } })
  let replacement
  const stop = watch(() => v.state.report, value => {
    if (!value || replacement) return
    v.context.value.patientId = 2
    replacement = v.preview()
  }, { flush: 'sync' }); t.after(stop)
  const original = v.preview(); reads[0].resolve({ code: 200, data: report('Synthetic old report') })
  assert.equal((await original).status, 'stale')
  assert.equal(reads.length, 2); assert.equal(v.state.phase, 'previewing'); assert.equal(v.state.report, null); assert.equal(v.state.error, null)
  stop(); reads[1].resolve({ code: 200, data: report('Synthetic replacement report') })
  assert.equal((await replacement).status, 'succeeded'); assert.equal(v.state.report.currentActions[0].instruction, 'Synthetic replacement report')
})

for (const boundary of ['clearing phase', 'error publication', 'error phase']) {
  test(`failure ${boundary} cannot overwrite a reentrant replacement request`, async t => {
    const reads = [], v = await fixture(t, { nonCancelable: true, transport: () => { const d = deferred(); reads.push(d); return d.promise } })
    let replacement, triggered = false
    const stop = watch(() => boundary === 'error publication' ? v.state.error : v.state.phase, value => {
      const match = boundary === 'clearing phase' ? value === 'idle' : boundary === 'error phase' ? value === 'error' : value != null
      if (!match || triggered) return
      triggered = true; v.context.value.patientId = 2; replacement = v.preview()
    }, { flush: 'sync' }); t.after(stop)
    const original = v.preview()
    reads[0].reject({ response: { status: 403, data: { code: 403, data: { errorCode: 'ACCESS_DENIED' } } } })
    assert.equal((await original).status, 'stale')
    assert.equal(reads.length, 2); assert.equal(v.state.phase, 'previewing'); assert.equal(v.state.report, null); assert.equal(v.state.error, null)
    stop(); reads[1].resolve({ code: 200, data: report('Synthetic replacement report') })
    assert.equal((await replacement).status, 'succeeded')
  })
}

test('ready phase publication cannot report success after a synchronous context change', async t => {
  const v = await fixture(t)
  const stop = watch(() => v.state.phase, value => { if (value === 'ready') v.context.value.patientId = 2 }, { flush: 'sync' }); t.after(stop)
  assert.equal((await v.preview()).status, 'stale'); assert.equal(v.state.phase, 'idle'); assert.equal(v.state.report, null)
})
