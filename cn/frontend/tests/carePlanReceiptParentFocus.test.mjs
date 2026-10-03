import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, reactive, markRaw } from 'vue'

const vueUrl = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
const source = path => fs.readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')
const moduleUrl = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const walk = node => [node, ...(node.children || []).flatMap(walk)]
const ancestors = node => node ? [node, ...ancestors(node.parent)] : []
const find = (root, id) => walk(root).find(node => node.props['data-testid'] === id)
const has = (node, key) => node.props[key] != null && node.props[key] !== false
const disabled = node => ['button', 'input', 'select', 'textarea', 'fieldset'].includes(node.tag) && ancestors(node).some(parent => has(parent, 'disabled'))
async function flush() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick() } }
function deferred() { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
let sequence = 0
const action = { id: 31, planId: 17, patientId: 1, ordinal: 1, assignedUserId: 51, instruction: 'Synthetic action', status: 'OPEN', dueAt: '2026-10-04T12:00:00Z', evidence: [], events: [], allowedActions: ['SUBMIT_RECEIPT', 'REQUEST_HELP', 'FOLLOW_UP'], allowedEntryModes: ['ASSISTED'] }
const plan = { id: 17, patientId: 1, title: 'Synthetic plan', instructions: 'Synthetic instructions', revisionNo: 1, revisionId: 23, currentRevisionId: 23, revisionStatus: 'PUBLISHED', version: 4, lifecycle: 'ACTIVE', actions: [action], allowedActions: [] }
const copy = value => JSON.parse(JSON.stringify(value))

async function mountedParent(t, name) {
  const id = ++sequence, slot = `__receiptParentFocus${id}`, calls = [], pending = deferred()
  let deferredRead = false, reads = 0, postTransport = async () => ({ data: { planId: 17, version: 5 } })
  const route = reactive({ params: { id: '17' } }), guards = []
  const storage = new Map([['token', 'synthetic-token'], ['userId', '51'], ['userRoleCodes', '["nurse"]']])
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)), removeItem: key => storage.delete(key), key: index => [...storage.keys()][index], get length() { return storage.size } }
  globalThis.window = new EventTarget()
  globalThis.Document = class Document {}
  globalThis.ShadowRoot = class ShadowRoot {}
  const body = { tag: 'body' }, previousDocument = globalThis.document
  globalThis.document = { body, activeElement: body }
  globalThis[slot] = { route, guards, async request(config) {
    calls.push(config)
    if (config.url.endsWith('/capabilities')) return { data: { enabled: true } }
    if (config.url.endsWith('/revisions')) return { data: { items: [], nextCursor: null } }
    if (config.method === 'post') return postTransport(config)
    const value = config.url === '/care-plans' ? { items: [copy(plan)], nextCursor: null } : copy(plan)
    reads++
    if (deferredRead && reads === 2) return pending.promise
    return { data: value }
  } }
  const authUrl = new URL(`../src/utils/authSession.js?receipt-parent=${id}`, import.meta.url).href
  const apiUrl = moduleUrl(source('api/carePlan.js').replace(/import request from ['"]@\/utils\/request['"]/g, `const request=config=>globalThis.${slot}.request(config)`).replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`))
  const timeUrl = new URL('../src/utils/carePlanTime.js', import.meta.url).href
  const composableUrl = moduleUrl(source('composables/useCarePlan.js').replace(/from ['"]vue['"]/g, `from '${vueUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g, `from '${apiUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g, `from '${timeUrl}'`))
  const routerUrl = moduleUrl(`export const useRoute=()=>globalThis.${slot}.route; export const onBeforeRouteLeave=guard=>globalThis.${slot}.guards.push(guard); export const onBeforeRouteUpdate=guard=>globalThis.${slot}.guards.push(guard);`)
  const urls = {}
  async function compile(componentName) {
    if (urls[componentName]) return urls[componentName]
    const filename = componentName === 'CarePlanDetailRoute' ? 'views/CarePlanDetailRoute.vue' : `components/care-plan/${componentName}.vue`
    const { descriptor } = parse(source(filename), { filename }), script = compileScript(descriptor, { id: `${id}-${componentName}` })
    const template = compileTemplate({ source: descriptor.template.content, filename, id: `${id}-${componentName}`, compilerOptions: { bindingMetadata: script.bindings, hoistStatic: false } })
    assert.deepEqual(template.errors, [])
    let code = script.content.replace('export default', 'const __component=') + '\n' + template.code.replace('export function render', 'function render') + '\n__component.render=render; export default __component;'
    code = code.replace(/from ['"]vue['"]/g, `from '${vueUrl}'`).replace(/from ['"]vue-router['"]/g, `from '${routerUrl}'`).replace(/from ['"]@\/api\/carePlan['"]/g, `from '${apiUrl}'`).replace(/from ['"]@\/composables\/useCarePlan['"]/g, `from '${composableUrl}'`).replace(/from ['"]@\/utils\/authSession['"]/g, `from '${authUrl}'`).replace(/from ['"]@\/utils\/carePlanTime['"]/g, `from '${timeUrl}'`)
    for (const match of code.matchAll(/from ['"](?:@\/components\/care-plan\/|\.\/)(\w+)\.vue['"]/g)) code = code.replace(match[0], `from '${await compile(match[1])}'`)
    return urls[componentName] = moduleUrl(code)
  }
  function node(tag, text = '') {
    const element = { tag, tagName: tag.toUpperCase(), text, children: [], parent: null, props: {}, style: {}, addEventListener() {}, removeEventListener() {}, getRootNode() { return {} }, getAttribute(key) { return this.props[key] }, setAttribute(key, value) { this.props[key] = value }, removeAttribute(key) { delete this.props[key] }, get options() { return this.children }, get isConnected() { return ancestors(this).some(parent => parent.tag === 'root') }, get tabIndex() { return has(this, 'tabindex') ? Number(this.props.tabindex) : ['button', 'input', 'select', 'textarea', 'a'].includes(tag) ? 0 : -1 }, contains(other) { return walk(this).includes(other) }, getClientRects() { return this.isConnected && !ancestors(this).some(parent => has(parent, 'hidden') || parent.style.display === 'none') ? [{}] : [] }, focus() { if (this.isConnected && !disabled(this) && this.getClientRects().length) document.activeElement = this }, showModal() { this.open = true; walk(this).find(child => child.tabIndex >= 0 && !disabled(child))?.focus() }, close() { this.open = false } }
    element.matches = selector => selector.split(',').some(part => { part = part.trim(); if (part === ':disabled') return disabled(element); const attr = part.match(/^\[([^\]]+)\]$/); if (attr) return has(element, attr[1]); const match = part.match(/^(\w+)(?:\[([^\]]+)\])?$/); return !!match && tag === match[1] && (!match[2] || has(element, match[2])) })
    element.closest = selector => ancestors(element).find(parent => parent.matches(selector)) || null
    element.querySelectorAll = selector => walk(element).slice(1).filter(child => child.matches(selector))
    return markRaw(element)
  }
  window.getComputedStyle = () => ({ visibility: 'visible' })
  const renderer = createRenderer({ createElement: tag => node(tag), createText: text => node('#text', text), createComment: text => node('#comment', text), setText: (element, text) => { element.text = text }, setElementText: (element, text) => { element.text = text; element.children = [] }, parentNode: element => element.parent, nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] || null, patchProp: (element, key, old, value) => { element.props[key] = value; if (key === 'value') element.value = value }, insert: (element, parent, anchor) => { if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1); element.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(index < 0 ? parent.children.length : index, 0, element) }, remove: element => { if (element.contains(document.activeElement)) document.activeElement = body; element.parent?.children.splice(element.parent.children.indexOf(element), 1); element.parent = null } })
  const root = node('root'), outside = node('button'), component = (await import(await compile(name))).default, app = renderer.createApp({ render() { return null } })
  outside.parent = node('root')
  app.component('router-link', { props: ['to'], setup(props, { slots }) { return () => h('a', { href: props.to }, slots.default?.()) } })
  let props = name === 'PlanTaskList' ? { patientId: 1, mode: 'NURSE' } : {}
  const render = () => { const vnode = h(component, props); vnode.appContext = app._context; renderer.render(vnode, root) }
  render(); await flush()
  t.after(() => { renderer.render(null, root); delete globalThis[slot]; globalThis.document = previousDocument })
  const openerId = name === 'PlanTaskList' ? 'record-31' : 'route-record-31'
  return { root, body, outside, route, guards, calls, storage, get vm() { return root._vnode.component.setupState }, beginDeferredRead() { deferredRead = true }, postTransport(handler) { postTransport = handler }, async resolveRead(value = plan) { pending.resolve({ data: name === 'PlanTaskList' ? { items: [copy(value)], nextCursor: null } : copy(value) }); await flush() }, async open(id = openerId) { const opener = find(root, id); assert.ok(opener); opener.focus(); opener.props.onClick({ currentTarget: opener }); await flush(); return opener }, async dismiss(kind = 'cancel') { const dialog = walk(root).find(element => element.tag === 'dialog'); assert.ok(dialog?.open); if (kind === 'cancel') dialog.props.onCancel(new Event('cancel', { cancelable: true })); else find(root, 'close-receipt').props.onClick(); await flush() }, async submit() { find(root, 'receipt-note').props['onUpdate:modelValue']('Synthetic submitted note'); walk(root).find(node => node.tag === 'input').props['onUpdate:modelValue'](new Date(Date.now() - 60000).toISOString()); await find(root, 'submit-receipt').props.onClick(new Event('click', { cancelable: true })); await flush() }, async update(value) { props = { ...props, ...value }; render(); await flush() }, openerId }
}

for (const parent of ['CarePlanDetailRoute', 'PlanTaskList']) {
  for (const dismissal of ['cancel', 'close']) test(`${parent}: ${dismissal} refresh replaces the trigger then restores focus to the surviving authorized control`, async t => {
    const view = await mountedParent(t, parent), original = await view.open()
    view.beginDeferredRead(); await view.dismiss(dismissal)
    assert.equal(original.isConnected, false, 'refresh really removes the original DOM node')
    assert.equal(walk(view.root).some(node => node.tag === 'dialog'), false)
    await view.resolveRead()
    const current = find(view.root, view.openerId)
    assert.ok(current?.isConnected)
    assert.notEqual(current, original)
    assert.equal(document.activeElement === current, true, 'focus belongs to the replacement button after server refresh')
    assert.equal(view.calls.filter(call => call.method === 'post').length, 0)
    assert.equal(view.calls.filter(call => call.url === (parent === 'PlanTaskList' ? '/care-plans' : '/care-plans/17')).length, 2, 'dismissal retains permission refresh')
  })
  test(`${parent}: refreshed permission removal never focuses a detached or unavailable trigger`, async t => {
    const view = await mountedParent(t, parent), original = await view.open()
    view.beginDeferredRead(); await view.dismiss()
    await view.resolveRead({ ...plan, actions: [{ ...action, allowedActions: [] }] })
    assert.equal(find(view.root, view.openerId), undefined)
    assert.equal(document.activeElement === original, false)
    assert.equal(document.activeElement, view.body)
  })
  test(`${parent}: accepted route leave prevents an outstanding dismissal from refocusing the old route`, async t => {
    const view = await mountedParent(t, parent)
    await view.open(); view.beginDeferredRead(); await view.dismiss()
    for (const guard of view.guards) assert.equal(guard(), true)
    await view.resolveRead()
    assert.equal(document.activeElement === find(view.root, view.openerId), false)
  })
  test(`${parent}: user focus moved during refresh is preserved`, async t => {
    const view = await mountedParent(t, parent)
    await view.open(); view.beginDeferredRead(); await view.dismiss()
    const other = view.outside
    assert.ok(other?.isConnected); other.focus()
    await view.resolveRead()
    assert.equal(document.activeElement === other, true)
  })
  test(`${parent}: submitted then closed performs one refresh and restores a surviving trigger`, async t => {
    const view = await mountedParent(t, parent)
    await view.open(); await view.submit()
    assert.equal(walk(view.root).some(node => node.tag === 'dialog'), false)
    assert.equal(view.calls.filter(call => call.url === (parent === 'PlanTaskList' ? '/care-plans' : '/care-plans/17')).length, 2)
    assert.equal(view.calls.filter(call => call.method === 'post').length, 1)
    assert.equal(document.activeElement === find(view.root, view.openerId), true)
  })
  test(`${parent}: context change during refresh prevents focus on the replacement context`, async t => {
    const view = await mountedParent(t, parent)
    await view.open(); view.beginDeferredRead(); await view.dismiss()
    if (parent === 'PlanTaskList') await view.update({ patientId: 2 })
    else { view.route.params.id = '18'; await flush() }
    await view.resolveRead()
    assert.ok(find(view.root, view.openerId)?.isConnected)
    assert.equal(document.activeElement === find(view.root, view.openerId), false)
  })
  test(`${parent}: auth change during refresh prevents stale focus restoration`, async t => {
    const view = await mountedParent(t, parent)
    await view.open(); view.beginDeferredRead(); await view.dismiss()
    view.storage.set('token', 'synthetic-replacement-token')
    await view.resolveRead()
    assert.equal(document.activeElement === find(view.root, view.openerId), false)
  })
  if (parent === 'CarePlanDetailRoute') test('CarePlanDetailRoute: accepted patient context event cancels outstanding focus restoration', async t => {
    const view = await mountedParent(t, parent)
    await view.open(); view.beginDeferredRead(); await view.dismiss()
    const event = new Event('care-plan-before-context-change', { cancelable: true })
    window.dispatchEvent(event)
    assert.equal(event.defaultPrevented, false)
    await view.resolveRead()
    assert.equal(document.activeElement === find(view.root, view.openerId), false)
  })
  test(`${parent}: pending dismissal retains the dialog and acknowledgment controls`, async t => {
    const view = await mountedParent(t, parent), pending = deferred()
    view.postTransport(() => pending.promise)
    await view.open(); const submission = view.submit(); await flush()
    await view.dismiss()
    assert.ok(walk(view.root).find(node => node.tag === 'dialog')?.open)
    assert.ok(find(view.root, 'confirm-abandon'))
    assert.ok(find(view.root, 'confirm-abandon').props.disabled)
    assert.equal(view.calls.filter(call => call.url === (parent === 'PlanTaskList' ? '/care-plans' : '/care-plans/17')).length, 1)
    pending.resolve({ data: { planId: 17, version: 5 } }); await submission
  })
}
