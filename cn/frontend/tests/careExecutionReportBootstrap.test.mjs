import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRenderer, h, ref, watch, nextTick } from 'vue'
import { createRouter, createMemoryHistory, useRoute } from 'vue-router'
import * as workspaceAccess from '../src/utils/workspaceAccess.js'

const readSource = file => fs.readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const node = tag => ({ tag, parent: null, children: [], attributes: {}, textContent: '', listeners: {},
  setAttribute(key, value) { this.attributes[key] = value },
  append(...values) { this.children.push(...values) },
  replaceChildren(...values) { this.children = values },
  addEventListener(event, callback, options) { this.listeners[event] = { callback, once: options?.once } },
  click() { const handler = this.listeners.click; if (handler?.once) delete this.listeners.click; handler?.callback() },
})
const content = value => [value.textContent, ...value.children.map(content)].join(' ')
const findNode = (value, predicate) => predicate(value) ? value : value.children.map(child => findNode(child, predicate)).find(Boolean)
const renderer = createRenderer({
  createElement: node, createText: node, createComment: node, setText() {}, setElementText() {}, patchProp() {},
  parentNode: value => value.parent, nextSibling: () => null,
  insert(value, parent) { value.parent = parent; parent.children.push(value) },
  remove(value) { if (value.parent) value.parent.children.splice(value.parent.children.indexOf(value), 1) },
})

function startup(t, initial, { authenticated = true, delayed = false } = {}) {
  const main = readSource('main.js'), appSource = readSource('App.vue')
  const guard = main.slice(main.indexOf('router.beforeEach('), main.indexOf('app.use(router)'))
  const bootstrap = main.slice(main.indexOf('app.use(router)'), main.indexOf("if ('serviceWorker'"))
  const refresh = appSource.slice(appSource.indexOf('let specialtyEpoch = 0;'), appSource.indexOf('const menuLoadError'))
  const watcher = appSource.match(/watch\(\[currentPatientId, \(\) => route\.fullPath\], refreshSpecialtyScope, \{ immediate: true, flush: 'sync' \}\);/)[0]
  const storage = new Map([['userRoleCodes', '["family"]'], ['userMenus', '[]']])
  if (authenticated) storage.set('token', 'synthetic-startup-token')
  const localStorage = { getItem: key => storage.get(key) ?? null }
  const root = node('root'), gate = deferred(), calls = [], mountedAt = [], logs = [], routeErrors = []
  const component = { render: () => h('div') }
  const initialPath = initial.split('?')[0]
  const paths = ['/', '/login', '/monitoring', '/care', '/care-plans/reports', '/care-journey', '/medical-record']
  const router = createRouter({
    history: createMemoryHistory(import.meta.url.includes('/cn/frontend/') ? '/cn/' : '/'),
    routes: paths.map(path => ({ path, meta: { hideNav: path === '/login' }, component: delayed && path === initialPath ? () => gate.promise : component })),
  })
  // Do not install a test-only router error listener: it would hide the router's
  // default raw-error logging when the initial lazy import fails.
  t.mock.method(console, 'error', (...args) => logs.push(args))
  t.mock.method(console, 'warn', (...args) => logs.push(args))
  const loadPatientSpecialtyScope = async patientId => { calls.push({ patientId, routeAtRead: router.currentRoute.value.fullPath }); return {} }
  const deps = { router, localStorage, ...workspaceAccess, loadPatientSpecialtyScope, knownSpecialtyPath: () => false, specialtyPathAllowed: () => true }
  // Execute the current production main guard and current App refresh/watch code.
  // Unrelated page rendering and HTTP are boundaries; the Vue watcher, router,
  // initial lazy-route resolution and main bootstrap ordering remain real.
  new Function(...Object.keys(deps), guard)(...Object.values(deps))
  const vueApp = renderer.createApp({ setup() {
    const appDeps = { ...deps, route: useRoute(), currentPatientId: ref(9102), specialtyScope: ref(null), watch }
    new Function(...Object.keys(appDeps), refresh + '\n' + watcher)(...Object.values(appDeps))
    return () => h('div')
  } })
  let navigation
  const app = {
    use(plugin, ...options) {
      vueApp.use(plugin, ...options)
      if (plugin === router) {
        // Browser router.install starts this initial navigation itself. Memory
        // history needs the same push explicitly, still before main can mount.
        navigation = router.push(initial)
        navigation.catch(() => {})
      }
      return app
    },
    component(...args) { vueApp.component(...args); return app },
    mount(selector) { assert.equal(selector, '#app'); mountedAt.push(router.currentRoute.value.fullPath); return vueApp.mount(root) },
  }
  let reloads = 0
  const document = { getElementById: id => { assert.equal(id, 'app'); return root }, createElement: node }
  const window = { location: { reload: () => { reloads++ } } }
  const bootstrapDeps = { document, window, app, router, ElementPlusIconsVue: {}, ElementPlus: { install() {} }, en: {}, zhCn: {}, console: { error: (...args) => logs.push(args) } }
  new Function(...Object.keys(bootstrapDeps), bootstrap)(...Object.values(bootstrapDeps))
  t.after(async () => {
    gate.resolve(component)
    await navigation.catch(() => {})
    await flush()
    if (mountedAt.length) vueApp.unmount()
  })
  return { root, reloads: () => reloads, router, calls, mountedAt, logs, routeErrors, release: () => gate.resolve(component), fail: () => gate.reject(new Error('Synthetic route-loader private diagnostic')), settle: async () => { await navigation.catch(() => {}); await flush() } }
}

for (const initial of [
  '/care-plans/reports?patientId=9101',
  '/care-journey?tab=measurements&patientId=9101&measurementId=19101',
  '/medical-record?tab=list&patientId=9101&recordId=19102',
]) {
  test(`actual main cold bootstrap waits for ${initial.split('?')[0]} before the App patient watcher can read`, async t => {
    const view = startup(t, initial, { delayed: true })
    await flush()
    assert.deepEqual(view.mountedAt, [], 'An unresolved initial route must not mount App at START_LOCATION')
    assert.ok(findNode(view.root, value => value.attributes?.role === 'status'), 'Deferred route has visible loading status')
    assert.match(content(view.root), /Loading application|正在加载应用/)
    assert.deepEqual(view.calls, [], 'No broad patient bootstrap is allowed while the exact initial locator is unresolved')
    view.release()
    await view.settle()
    assert.deepEqual(view.mountedAt, [initial])
    assert.deepEqual(view.calls, [], 'The stored patient 9102 cannot bootstrap a full profile for route-owned patient 9101')
    assert.deepEqual(view.logs, [])
    await view.router.push('/care')
    await flush()
    assert.ok(view.calls.length > 0, 'Ordinary navigation retains its existing specialty bootstrap')
    await view.router.push(initial)
    await flush()
    assert.deepEqual(view.mountedAt, [initial], 'Later navigation never mounts a second application')
  })
}

for (const initial of ['/login', '/care-plans/reports?patientId=9101', '/care-journey?tab=measurements&patientId=9101&measurementId=19101', '/medical-record?tab=list&patientId=9101&recordId=19102']) {
  test(`actual main bootstrap mounts signed-out ${initial.split('?')[0]} once only after login readiness`, async t => {
    const view = startup(t, initial, { authenticated: false })
    await view.settle()
    assert.deepEqual(view.mountedAt, ['/login'])
    assert.deepEqual(view.calls, [])
    assert.deepEqual(view.logs, [])
  })
}

for (const initial of [
  '/care-plans/reports?patientId=9101',
  '/care-journey?tab=measurements&patientId=9101&measurementId=19101',
  '/medical-record?tab=list&patientId=9101&recordId=19102',
]) test(`actual main exposes safe retry after rejected ${initial.split('?')[0]} and recovers on reload`, async t => {
  const view = startup(t, initial, { delayed: true })
  await flush(); view.fail(); await view.settle()
  assert.deepEqual(view.mountedAt, [])
  assert.deepEqual(view.calls, [])
  assert.deepEqual(view.logs, [['Application route initialization failed.']])
  assert.ok(findNode(view.root, value => value.attributes?.role === 'alert'), 'Rejected startup must leave a visible nonclinical failure state')
  assert.match(content(view.root), /Unable to load application|应用加载失败/)
  assert.doesNotMatch(content(view.root), /private diagnostic|9101|9102|Synthetic|token/)
  const retry = findNode(view.root, value => value.tag === 'button')
  assert.ok(retry, 'Failure must offer an accessible retry without mounting App')
  assert.match(retry.textContent, /Reload application|重新加载应用/)
  assert.equal(view.reloads(), 0)
  retry.click(); retry.click()
  assert.equal(view.reloads(), 1, 'Repeated user clicks request only one safe reload')
  assert.deepEqual(view.calls, [])
  // A native reload re-evaluates main and lazy imports. Exercise that new lifetime
  // with the same real route, guard and App watcher after the transport recovers.
  const recovered = startup(t, initial, { delayed: true })
  await flush(); assert.deepEqual(recovered.mountedAt, []); recovered.release(); await recovered.settle()
  assert.deepEqual(recovered.mountedAt, [initial]); assert.deepEqual(recovered.calls, [])
  assert.equal(findNode(recovered.root, value => value.attributes?.role === 'alert'), undefined)
  await recovered.router.push('/care'); await flush(); assert.ok(recovered.calls.length > 0)
  await recovered.router.push(initial); await flush(); assert.deepEqual(recovered.mountedAt, [initial])
})
