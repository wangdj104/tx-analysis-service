// Component state, error presentation, and request-boundary contracts; no live APIs.
import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'
import * as Vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import axios from 'axios'
const {reactive,ref,watch,effectScope}=Vue
const source=readFileSync(new URL('../src/components/CareEntryDialog.vue',import.meta.url),'utf8')
test('live required-field errors persist until the corresponding input is corrected',t=>{
 const scope=effectScope(),form=reactive({kind:'ORDER',title:'',details:{}}),errors=ref({}),attempted=ref(true)
 const code=source.slice(source.indexOf('function validationErrors()'),source.indexOf('function clearSensitive()'))
 const validate=scope.run(()=>new Function('form','errors','attempted','watch',code+';return validationErrors')(form,errors,attempted,watch));t.after(()=>scope.stop());errors.value=validate()
 assert.deepEqual(Object.keys(errors.value),['title','medicationId','startDate','confirmed']);form.title='Synthetic order';assert.deepEqual(Object.keys(errors.value),['medicationId','startDate','confirmed']);form.details.medicationId=9;assert.deepEqual(Object.keys(errors.value),['startDate','confirmed']);form.details.startDate='2026-10-13';form.details.confirmed=true;assert.deepEqual(errors.value,{})
})
test('required-control association preserves other help and clears only its own error',()=>{
 const attrs=new Map([['aria-describedby','help-text']]),input={getAttribute:k=>attrs.get(k),setAttribute:(k,v)=>attrs.set(k,v),removeAttribute:k=>attrs.delete(k)},element={querySelector:()=>input}
 const code=source.slice(source.indexOf('function syncFieldError('),source.indexOf('const vCareFieldError'))
 const sync=new Function(code+';return syncFieldError')();sync(element,{value:{error:'Required',id:'care-entry-error-medicationId'}});assert.equal(attrs.get('aria-describedby'),'help-text care-entry-error-medicationId');assert.equal(attrs.get('aria-invalid'),'true');sync(element,{value:{error:'',id:'care-entry-error-medicationId'}});assert.equal(attrs.get('aria-describedby'),'help-text');assert.equal(attrs.has('aria-invalid'),false)
})

const require = createRequire(import.meta.url), base = new URL('../src/', import.meta.url)
function load(file, overrides = {}) {
  if (file in overrides) return overrides[file]
  if (file.endsWith('.css')) return {}
  let source = readFileSync(new URL(file, base), 'utf8')
  if (file.endsWith('.vue')) source = compileScript(parse(source).descriptor, { id: 'measurement-disabled', inlineTemplate: true }).content
  const module = { exports: {} }
  new Function('require', 'module', 'exports', transformSync(source, { format: 'cjs', loader: 'js' }).code)(id => {
    if (id in overrides) return overrides[id]
    if (id === 'vue') return Vue
    if (id.startsWith('@/')) return load(id.slice(2) + (path.extname(id) ? '' : '.js'), overrides)
    if (id.startsWith('.')) return load(path.posix.normalize(path.posix.join(path.posix.dirname(file), id)), overrides)
    return require(id)
  }, module, module.exports)
  return module.exports
}

const disabled = { code: 503, msg: 'Alert follow-up is not enabled.', data: { errorCode: 'FEATURE_DISABLED' } }
function fixture(t, { httpStatus = 200, payload = disabled } = {}) {
  const oldWindow = globalThis.window, oldStorage = globalThis.localStorage
  const messages = [], calls = [], host = new EventTarget()
  host.location = { pathname: '/monitoring', search: '', hash: '' }
  globalThis.window = host
  globalThis.localStorage = { getItem: key => key === 'userId' ? '7' : 'synthetic' }
  t.after(() => { globalThis.window = oldWindow; globalThis.localStorage = oldStorage })
  t.mock.method(console, 'error', () => {})
  const auth = { token: 'synthetic', actorId: '7', revision: 1 }
  let current = true
  const overrides = {
    'element-plus': { ElMessage: { error: message => messages.push(message) } },
    'utils/authSession.js': { captureAuthSession: () => auth, isAuthSessionCurrent: () => current, clearAuthSession() {}, readPermissionCache: () => ({}) },
    'utils/assistanceLogin.js': { captureAssistanceLoginRedirect: () => ({ protected: false }), ownsAssistanceLoginRedirect: () => true },
    'utils/requestContext.js': { captureRequestContext: () => ({}), isRequestContextCurrent: () => current, isProtectedAccessFailure: () => false },
    'utils/serverText.js': { localizePayload: value => value, localizeServerText: value => value }
  }
  const request = load('utils/request.js', overrides).default
  request.defaults.adapter = async config => {
    calls.push(config)
    const response = { status: httpStatus, statusText: 'Synthetic', headers: {}, data: payload, config }
    if (httpStatus >= 400) throw new axios.AxiosError('Synthetic HTTP failure', axios.AxiosError.ERR_BAD_RESPONSE, config, null, response)
    return response
  }
  overrides['utils/request.js'] = { __esModule: true, default: request }
  const api = load('api/measurementReadModel.js', overrides)
  return { request, api, messages, calls, overrides, auth, host, invalidate: () => { current = false } }
}

for (const httpStatus of [200, 503]) test(`measurement readers keep HTTP ${httpStatus} disabled responses inline while rejecting them`, async t => {
  const f = fixture(t, { httpStatus })
  for (const read of [() => f.api.trend({ patientId: 7 }), () => f.api.items({ patientId: 7 }), () => f.api.events(9, { patientId: 7 })]) {
    await assert.rejects(read, error => (error.data || error.response?.data?.data)?.errorCode === 'FEATURE_DISABLED')
  }
  assert.equal(f.calls.length, 3)
  assert.deepEqual(f.messages, [])
})

test('schema failures, access denials, unrelated endpoints and mutations still show their errors', async t => {
  const f = fixture(t)
  for (const config of [
    { url: '/alert-workflows/read-model/measurement-trend', method: 'get' },
    { url: '/alert-workflows/read-model/measurement-trend', method: 'post', measurementReadModel: true },
    { url: '/alert-workflows/9/close', method: 'get', measurementReadModel: true }
  ]) await assert.rejects(() => f.request(config))
  assert.equal(f.messages.length, 3)
  for (const [status, code] of [[503, 'SCHEMA_NOT_READY'], [403, 'ACCESS_DENIED'], [500, 'FEATURE_DISABLED']]) {
    f.request.defaults.adapter = async config => ({ status: 200, headers: {}, config, data: { code: status, data: { errorCode: code } } })
    await assert.rejects(() => f.api.trend({ patientId: 7 }))
  }
  assert.equal(f.messages.length, 6)
})

test('the actual reader retains a localized disabled state without stale data or a toast', async t => {
  const f = fixture(t), scope = Vue.effectScope()
  t.after(() => scope.stop())
  f.overrides['api/measurementReadModel.js'] = f.api
  const { useMeasurementReadModel } = load('composables/useMeasurementReadModel.js', f.overrides)
  const reader = scope.run(() => useMeasurementReadModel('trend', Vue.ref(7), Vue.ref({ sourceType: 'BP_SELF_MONITOR', metric: 'BP', from: '2026-10-18', to: '2026-10-24' }), true,
    { capture: () => f.auth, isCurrent: () => true, permissionKey: () => '', events: f.host }))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(reader.state.phase, 'disabled')
  assert.deepEqual(reader.state.items, [])
  assert.equal(reader.state.data, null)
  assert.deepEqual(f.messages, [])
})

test('a disabled response from a retired account context is still discarded', async t => {
  const f = fixture(t)
  f.request.defaults.adapter = async config => {
    f.invalidate()
    return { status: 200, headers: {}, config, data: disabled }
  }
  await assert.rejects(() => f.api.trend({ patientId: 7 }), error => axios.isCancel(error))
  assert.deepEqual(f.messages, [])
})

for (const component of ['TrustedMeasurementTrend', 'MeasurementItemHistory']) test(`${component} presents disabled as a localized status rather than an error alert`, async () => {
  const empty = Vue.defineComponent({ render: () => null })
  const overrides = {
    'echarts/core': { use() {} }, 'echarts/renderers': { CanvasRenderer: {} }, 'echarts/charts': { ScatterChart: {} },
    'echarts/components': { GridComponent: {}, LegendComponent: {}, TooltipComponent: {} },
    'components/HealthChart.vue': empty, 'components/measurement-read-model/MeasurementSourceDialog.vue': empty,
    'composables/useMeasurementReadModel.js': { useMeasurementReadModel: () => ({ state: Vue.reactive({ phase: 'disabled', generation: 0, items: [], previous: [], nextCursor: null }), refresh() {}, clear() {}, deny() {} }) }
  }
  const app = Vue.createSSRApp(load(`components/measurement-read-model/${component}.vue`, overrides).default, { patientId: 7 })
  app.component('RouterLink', { render: () => Vue.h('a') })
  const html = await renderToString(app), text = load('utils/measurementReadModelCopy.js').measurementText('disabled')
  assert.ok(html.includes(text))
  assert.match(html, /class="mr-notice" role="status"/)
  assert.doesNotMatch(html, /role="alert"/)
})
