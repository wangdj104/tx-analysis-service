import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

// Exercise the compiled production templates with actual Vue Router links and
// history base resolution. HTTP and unrelated presentation are the only seams.
const source = file => fs.readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
const moduleUrl = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const vueUrl = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
const routerUrl = new URL('../node_modules/vue-router/dist/vue-router.mjs', import.meta.url).href
const appBase = source('main.js').match(/createWebHistory\((?:['"]([^'"]+)['"])?\)/)?.[1] || '/'
let fixtureId = 0

async function flush() {
  for (let index = 0; index < 20; index++) { await Promise.resolve(); await nextTick() }
}
function nodes(root) { return [root, ...(root.children || []).flatMap(nodes)] }
function textOf(root) { return (root.text || '') + (root.children || []).map(textOf).join(' ') }
function componentInstance(vnode, name) {
  if (!vnode) return
  if (vnode.component) {
    if (vnode.component.type.__name === name) return vnode.component
    const found = componentInstance(vnode.component.subTree, name)
    if (found) return found
  }
  for (const child of Array.isArray(vnode.children) ? vnode.children : []) {
    const found = componentInstance(child, name)
    if (found) return found
  }
}

async function fixture(t, name, { timeline = [], enabled = true } = {}) {
  const id = ++fixtureId, slot = `__baseNavigationTransport${id}`, calls = []
  const storage = new Map([
    ['token', 'synthetic-token'], ['userId', '51'], ['userRoleCodes', '["nurse"]'],
    ['userMenus', '["/family-health"]'], ['userMenuNames', '[]'], ['permissionSession', 'synthetic-token']
  ])
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key),
    key: index => [...storage.keys()][index], get length() { return storage.size }
  }
  globalThis.window = new EventTarget()
  window.setInterval = setInterval; window.clearInterval = clearInterval
  globalThis.document = { activeElement: null }
  globalThis.Document = class {}; globalThis.ShadowRoot = class {}
  globalThis[slot] = async config => {
    calls.push(config)
    if (config.url.endsWith('/capabilities')) return { data: { enabled } }
    const plan = { id: 17, patientId: 1, title: 'Synthetic plan', revisionId: 23, revisionNo: 1, version: 4, lifecycle: 'ACTIVE', instructions: 'Synthetic instructions', actions: [], allowedActions: [] }
    if (config.url === '/care-plans') return { data: { items: [plan], nextCursor: null } }
    if (config.url === '/care-plans/17') return { data: plan }
    if (config.url.endsWith('/revisions')) return { data: { items: [], nextCursor: null } }
    if (config.url === '/family-health/timeline') return { data: timeline }
    if (config.url === '/family-health/target') return { data: {} }
    return { data: [] }
  }
  const authUrl = new URL(`../src/utils/authSession.js?base-navigation=${id}`, import.meta.url).href
  const timeUrl = new URL('../src/utils/carePlanTime.js', import.meta.url).href
  const api = file => moduleUrl(source(file)
    .replace(/import request from ['"]@\/utils\/request['"];?/g, `const request = (...args) => globalThis.${slot}(...args)`)
    .replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`))
  const careApiUrl = api('api/carePlan.js'), familyApiUrl = api('api/familyHealth.js'), doctorApiUrl = api('api/doctorWorkspace.js')
  const composableUrl = moduleUrl(source('composables/useCarePlan.js')
    .replace(/from ['"]vue['"]/g, `from '${vueUrl}'`)
    .replace(/from ['"]@\/api\/carePlan['"]/g, `from '${careApiUrl}'`)
    .replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`)
    .replace(/from ['"]@\/utils\/carePlanTime['"]/g, `from '${timeUrl}'`))
  const patientUrl = moduleUrl(`import { ref } from '${vueUrl}'; export function useCurrentPatient() { return { currentPatientId: ref(1) } } // ${id}`)
  const elementPlusUrl = moduleUrl('export const ElMessage = { success() {}, warning() {} }; export const ElMessageBox = { prompt: async () => null }')
  const compiled = {}
  async function compile(componentName) {
    if (compiled[componentName]) return compiled[componentName]
    const file = componentName.includes('/') ? `${componentName}.vue` : `components/care-plan/${componentName}.vue`
    const scope = `base-navigation-${id}-${componentName}`, { descriptor } = parse(source(file), { filename: file })
    const script = compileScript(descriptor, { id: scope })
    const template = compileTemplate({ source: descriptor.template.content, filename: file, id: scope, compilerOptions: { bindingMetadata: script.bindings, hoistStatic: false } })
    assert.deepEqual(template.errors, [], `${componentName} compiles`)
    let code = script.content.replace('export default', 'const __component =') + '\n' + template.code.replace('export function render', 'function render') + '\n__component.render = render; export default __component;'
    code = code.replace(/from ['"]vue['"]/g, `from '${vueUrl}'`)
      .replace(/from ['"]vue-router['"]/g, `from '${routerUrl}'`)
      .replace(/from ['"]@\/api\/carePlan['"]/g, `from '${careApiUrl}'`)
      .replace(/from ['"]@\/api\/familyHealth['"]/g, `from '${familyApiUrl}'`)
      .replace(/from ['"]@\/api\/doctorWorkspace['"]/g, `from '${doctorApiUrl}'`)
      .replace(/from ['"]@\/composables\/useCarePlan['"]/g, `from '${composableUrl}'`)
      .replace(/from ['"]@\/composables\/useCurrentPatient['"]/g, `from '${patientUrl}'`)
      .replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`)
      .replace(/from ['"]@\/utils\/carePlanTime['"]/g, `from '${timeUrl}'`)
      .replace(/from ['"]element-plus['"]/g, `from '${elementPlusUrl}'`)
      .replace(/from ['"]@\/utils\/(workspaceAccess|familyHealth|timelineText)['"]/g, (_, utility) => `from '${new URL(`../src/utils/${utility}.js`, import.meta.url).href}'`)
    for (const match of code.matchAll(/from ['"](?:\.\/|@\/components\/care-plan\/)(\w+)\.vue['"]/g)) code = code.replace(match[0], `from '${await compile(match[1])}'`)
    return compiled[componentName] = moduleUrl(code)
  }
  const node = (tag, text = '') => ({ tag, text, children: [], props: {}, parent: null, getAttribute(key) { return this.props[key] } })
  const renderer = createRenderer({
    createElement: tag => node(tag), createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (target, text) => { target.text = text }, setElementText: (target, text) => { target.text = text; target.children = [] },
    parentNode: target => target.parent, nextSibling: target => target.parent?.children[target.parent.children.indexOf(target) + 1] || null,
    patchProp: (target, key, previous, value) => { target.props[key] = value },
    insert(target, parent, anchor) {
      if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1)
      target.parent = parent
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, target)
    },
    remove(target) { if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1) }
  })
  const component = (await import(await compile(name))).default
  const isDetail = name === 'views/CarePlanDetailRoute'
  const initial = isDetail ? '/care-plans/17' : name === 'PlanTaskList' ? '/care' : '/family-health?tab=timeline'
  const router = createRouter({ history: createMemoryHistory(appBase), routes: [
    { path: '/care', component: isDetail ? { render: () => h('p', { 'data-testid': 'care-destination' }, 'Care route') } : { render: () => h(component, { patientId: 1 }) } },
    { path: '/family-health', component },
    { path: '/care-plans/:id(\\d+)', component: isDetail ? component : { render: () => h('p', { 'data-testid': 'plan-destination' }, 'Authorized plan route') } }
  ] })
  const root = node('root'), app = renderer.createApp({ render: () => h(RouterView) })
  for (const tag of new Set(source('views/FamilyHealthManager.vue').match(/el-[a-z-]+/g))) {
    app.component(tag, { setup(props, { slots }) { return () => h('div', {}, tag === 'el-table-column' ? [] : slots.default?.()) } })
  }
  app.directive('loading', {}); app.use(router)
  await router.push(initial); await router.isReady(); app.mount(root); await flush()
  t.after(() => { app.unmount(); delete globalThis[slot] })
  return { root, router, calls, initial, vm: componentInstance(root._vnode, name.split('/').at(-1)).setupState }
}

async function click(link) {
  const event = { button: 0, defaultPrevented: false, currentTarget: link, preventDefault() { this.defaultPrevented = true } }
  await link.props.onClick?.(event); await flush()
  return event
}
const validEvent = { id: 99, sourceId: 99, sourceType: 'CARE_PLAN_EVENT', carePlanId: 17, title: 'Synthetic timeline event', eventDate: '2026-10-03', eventTime: '10:00:00Z' }

test(`plan task link stays under ${appBase} and uses router navigation and guards`, async t => {
  const view = await fixture(t, 'PlanTaskList')
  const link = nodes(view.root).find(node => node.props['data-testid'] === 'open-plan-17')
  assert.ok(link, 'authorized task renders a plan link')
  assert.equal(link.tag, 'a')
  assert.equal(link.props.href, `${appBase}care-plans/17`)
  let attempts = 0
  const removeGuard = view.router.beforeEach(() => { attempts++; return false })
  assert.equal((await click(link)).defaultPrevented, true)
  assert.equal(view.router.currentRoute.value.fullPath, view.initial)
  assert.equal(attempts, 1, 'the displayed link invokes the registered router guard')
  removeGuard(); await click(link)
  assert.equal(view.router.currentRoute.value.path, '/care-plans/17')
  assert.ok(nodes(view.root).some(node => node.props['data-testid'] === 'plan-destination'))
  assert.ok(view.calls.some(call => call.url === '/care-plans'), 'HTTP collection path remains edition independent')
})

test(`timeline link resolves its authorized carePlanId under ${appBase}`, async t => {
  const view = await fixture(t, 'views/FamilyHealthManager', { timeline: [validEvent] })
  const links = nodes(view.root).filter(node => node.tag === 'a' && node.props.class === 'care-plan-event-link')
  assert.equal(links.length, 1)
  assert.equal(links[0].props.href, `${appBase}care-plans/17`, 'carePlanId, not event sourceId, locates the plan')
  assert.equal((await click(links[0])).defaultPrevented, true)
  assert.equal(view.router.currentRoute.value.path, '/care-plans/17')
})

test('timeline rejects absent, foreign-type, unsafe and non-positive care-plan locators', async t => {
  const invalid = [
    { sourceType: 'CARE_PLAN_EVENT', sourceId: 17 },
    ...['17', '//evil.test', 0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, NaN, Infinity].map(carePlanId => ({ sourceType: 'CARE_PLAN_EVENT', carePlanId })),
    { sourceType: 'MEDICAL_RECORD', sourceId: 17, carePlanId: 17 }
  ].map((event, index) => ({ id: index + 1, title: 'Synthetic invalid locator', eventDate: '2026-10-03', eventTime: '10:00:00Z', ...event }))
  const view = await fixture(t, 'views/FamilyHealthManager', { timeline: [validEvent, ...invalid] })
  assert.equal(view.vm.safePlanLink(validEvent), '/care-plans/17', 'logical routes are resolved by Vue Router')
  for (const event of invalid) assert.equal(view.vm.safePlanLink(event), '')
  assert.equal(nodes(view.root).filter(node => node.tag === 'a' && node.props.class === 'care-plan-event-link').length, 1)
})

test('feature-disabled task queues expose no plan navigation', async t => {
  const view = await fixture(t, 'PlanTaskList', { enabled: false })
  assert.equal(nodes(view.root).some(node => node.props['data-testid'] === 'open-plan-17'), false)
  assert.equal(view.calls.some(call => call.url === '/care-plans'), false)
})

test(`return-to-care link stays under ${appBase} and participates in router guards`, async t => {
  const view = await fixture(t, 'views/CarePlanDetailRoute')
  const link = nodes(view.root).find(node => node.tag === 'a' && /Return to care|返回照护/.test(textOf(node)))
  assert.ok(link, 'detail renders its return link')
  assert.equal(link.props.href, `${appBase}care`)
  let attempts = 0
  const removeGuard = view.router.beforeEach(() => { attempts++; return false })
  assert.equal((await click(link)).defaultPrevented, true)
  assert.equal(view.router.currentRoute.value.path, '/care-plans/17')
  assert.equal(attempts, 1)
  removeGuard(); await click(link)
  assert.equal(view.router.currentRoute.value.path, '/care')
  assert.ok(nodes(view.root).some(node => node.props['data-testid'] === 'care-destination'))
})
