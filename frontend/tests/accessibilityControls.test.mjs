import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import * as Vue from 'vue'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'
import ElementPlus, { ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } from 'element-plus'
import * as icons from '@element-plus/icons-vue'

const read = file => fs.readFileSync(new URL('../src/components/' + file, import.meta.url), 'utf8')
const template = source => source.slice(source.indexOf('<template>') + 10, source.lastIndexOf('</template>'))
const compiled = (source, runtime = Vue) => new Function('Vue', compile(template(source), { mode: 'function', prefixIdentifiers: true }).code)(runtime)
const tick = async () => { await Vue.nextTick(); await new Promise(resolve => setImmediate(resolve)) }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

// Exercise the shipped template with actual Element Plus buttons and popovers.
// Removing the accessible label must fail even when the settings icon remains.
test('column visibility trigger has a name in icon-only table headers', async () => {
  for (const iconOnly of [true, false]) {
    const app = Vue.createSSRApp({ render: compiled(read('ColumnSetting.vue')), setup: () => ({ iconOnly, model: [], columns: [] }) })
    app.use(ElementPlus)
    app.component('Setting', icons.Setting)
    app.provide(ID_INJECTION_KEY, { prefix: 17, current: 0 })
    app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
    const html = await renderToString(app)
    const button = html.match(/<button\b[^>]*>[\s\S]*?<\/button>/)[0]
    const text = button.replace(/<!--[\s\S]*?-->/g, '').replace(/<[^>]+>/g, '').trim()
    const label = button.match(/aria-label="([^"]+)"/)?.[1]
    assert.ok(label || text, 'The settings trigger must announce its purpose, not just an unnamed button')
    if (iconOnly) assert.match(label, /column|列/i)
    else assert.ok(!label || label.includes(text), 'An accessible name must retain the visible button label')
  }
})

// Production script and Vue reactivity, with only DOM, navigation and clocks
// adapted. No backend, browser, real patient data, or duplicate guide logic.
function guide(t, { navigate } = {}) {
  const source = read('OnboardingGuide.vue')
  const code = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/import[\s\S]*?from\s*['"][^'"]+['"];?/g, '')
  const listeners = new Map(), timers = new Map(), unmounts = [], focused = [], storage = new Map()
  let timerId = 0, visibleModal = false
  const document = {
    body: {}, activeElement: null,
    addEventListener(type, listener) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(listener) },
    removeEventListener(type, listener) { listeners.get(type)?.delete(listener) },
    querySelector(selector) {
      if (selector === '#workspace-content' || selector === '.workspace-content') return main
      if (selector === '.workspace-guide') return opener
      if (/dialog/.test(selector)) return visibleModal ? modal : null
      return target
    },
    querySelectorAll(selector) { return /dialog/.test(selector) ? visibleModal ? [modal] : [] : [opener] }
  }
  const node = name => Vue.markRaw({
    name, isConnected: true, disabled: false, hidden: false,
    getClientRects() { return this.hidden ? [] : [{}] },
    closest(selector) { return this.hidden && /hidden|inert/.test(selector) ? this : null },
    focus() { document.activeElement = this; focused.push(this.name) }
  })
  const opener = node('opener'), main = node('main'), title = node('title'), modal = node('modal')
  const highlights = [], classes = new Set(), target = Object.assign(node('target'), {
    classList: { add: value => { highlights.push(value); classes.add(value) }, remove: value => classes.delete(value) },
    scrollIntoView() {}, getBoundingClientRect: () => ({ top: 80, left: 80, width: 200, height: 40 })
  })
  const layer = { contains: element => element === title, closest() { return null } }
  const route = Vue.ref({ path: '/bp-self-monitor' })
  document.activeElement = opener
  const bindings = {
    ...Vue, ...icons, defineProps: () => ({ accountId: 'synthetic-account', roleCodes: ['patient'] }), defineExpose() {},
    useRouter: () => ({ currentRoute: route, push: async path => { await navigate?.(path); route.value.path = path } }),
    document, window: { innerWidth: 1200, innerHeight: 800, addEventListener: document.addEventListener, removeEventListener: document.removeEventListener },
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    setTimeout(callback) { const id = ++timerId; timers.set(id, callback); return id }, clearTimeout: id => timers.delete(id),
    onBeforeUnmount: callback => unmounts.push(callback)
  }
  delete bindings.default
  delete bindings['module.exports']
  const names = 'start,begin,pause,previous,confirmStep,active,welcomeVisible,stepIndex,steps,current,anchorStyle,cardStyle,cardPlacement'
  const scope = Vue.effectScope()
  const view = scope.run(() => new Function(...Object.keys(bindings), code + `\nreturn {${names}, guideTitle: typeof guideTitle === 'undefined' ? null : guideTitle, guideLayer: typeof guideLayer === 'undefined' ? null : guideLayer, focusAfterWelcome: typeof focusAfterWelcome === 'undefined' ? null : focusAfterWelcome}`)(...Object.values(bindings)))
  if (view.guideTitle) view.guideTitle.value = title
  if (view.guideLayer) view.guideLayer.value = layer
  const render = compiled(source, { ...Vue, resolveComponent: name => ({ name }) })
  function nodes() {
    const result = []
    const walk = node => {
      if (!node || typeof node !== 'object') return
      if (Array.isArray(node)) return node.forEach(walk)
      result.push(node)
      if (Array.isArray(node.children)) node.children.forEach(walk)
      else if (node.children && typeof node.children === 'object') for (const slot of Object.values(node.children)) if (typeof slot === 'function') walk(slot())
    }
    walk(render(Vue.proxyRefs(view), []))
    return result
  }
  function flushTimers() { for (const [id, callback] of [...timers]) { timers.delete(id); callback() } }
  function key(key, extras = {}) {
    const event = { key, defaultPrevented: false, preventDefault() { this.defaultPrevented = true }, ...extras }
    for (const listener of listeners.get('keydown') || []) listener(event)
    return event
  }
  function unmount() { unmounts.splice(0).forEach(callback => callback()); scope.stop() }
  t.after(unmount)
  return { ...view, nodes, focused, document, opener, main, title, target, classes, highlights, timers, route, listeners, flushTimers, key, unmount, modal: value => { visibleModal = value } }
}

test('interactive guide is non-modal and its heading is programmatically focusable', async t => {
  const view = guide(t); view.start(); await tick()
  const nodes = view.nodes(), dialog = nodes.find(node => node.props?.class === 'guide-layer'), heading = nodes.find(node => node.type === 'h2')
  assert.equal(dialog.props['aria-modal'], 'false')
  assert.equal(heading.props.tabindex, '-1')
  assert.equal(dialog.props['aria-labelledby'], heading.props.id)
  assert.equal(heading.ref.r, 'guideTitle')
})

test('start announces the guide; Escape pauses it and restores the connected opener', async t => {
  const view = guide(t); view.start(); await tick(); view.flushTimers()
  assert.equal(view.document.activeElement, view.title)
  view.target.focus(); view.key('Tab')
  assert.equal(view.document.activeElement, view.target, 'The interactive target must remain reachable without a guide focus trap')
  assert.equal(view.key('Escape').defaultPrevented, true)
  assert.equal(view.active.value, false)
  assert.equal(view.document.activeElement, view.opener)
  assert.equal(view.listeners.get('keydown')?.size || 0, 0)
  assert.equal(view.listeners.get('resize')?.size || 0, 0)
  assert.equal(view.classes.has('guide-target'), false)
})

test('Escape belongs to an open modal or an already-handled control before the guide', async t => {
  const view = guide(t); view.start(); await tick()
  view.key('Escape', { defaultPrevented: true }); assert.equal(view.active.value, true)
  view.modal(true); const event = view.key('Escape'); assert.equal(view.active.value, true); assert.equal(event.defaultPrevented, false)
  view.modal(false); view.key('Escape'); assert.equal(view.active.value, false)
})

test('step navigation refocuses the new heading and finishing restores the original opener', async t => {
  const view = guide(t); view.start(); await tick(); view.flushTimers()
  view.target.focus(); view.confirmStep(); await tick(); view.flushTimers()
  assert.equal(view.document.activeElement, view.title)
  view.previous(); await tick(); view.flushTimers(); assert.equal(view.stepIndex.value, 0)
  view.start(); await tick(); view.flushTimers()
  view.stepIndex.value = view.steps.value.length - 1; view.confirmStep()
  assert.equal(view.active.value, false)
  assert.equal(view.document.activeElement, view.opener)
  assert.equal(view.listeners.get('keydown')?.size || 0, 0)
  assert.equal(view.classes.has('guide-target'), false)
})

for (const unavailable of ['isConnected', 'disabled', 'hidden']) {
  test(`pause avoids a ${unavailable} opener and focuses the main region`, async t => {
    const view = guide(t); view.start(); await tick()
    view.opener[unavailable] = unavailable !== 'isConnected'
    view.pause()
    assert.equal(view.document.activeElement, view.main)
  })
}

test('pausing before nextTick prevents late focus and highlight installation', async t => {
  const view = guide(t); view.start(); view.pause(); const focused = [...view.focused]
  await tick(); view.flushTimers()
  assert.equal(view.active.value, false)
  assert.deepEqual(view.focused, focused)
  assert.equal(view.classes.has('guide-target'), false)
  assert.equal(view.timers.size, 0)
})

test('old pending navigation cannot focus or highlight after pause or replace a restarted step', async t => {
  const pending = deferred(), view = guide(t, { navigate: () => pending.promise })
  view.route.value.path = '/care'; view.start(); view.pause()
  view.stepIndex.value = 1; view.route.value.path = '/bp-self-monitor'; view.start()
  await tick(); view.flushTimers(); const focused = [...view.focused], highlights = [...view.highlights]
  pending.resolve(); await tick(); view.flushTimers()
  assert.equal(view.active.value, true)
  assert.deepEqual(view.highlights, highlights)
  assert.deepEqual(view.focused, focused)
  assert.equal(view.timers.size, 0)
})

test('unmount removes listeners and pending highlights without stealing focus', async t => {
  const view = guide(t); view.start(); view.target.focus(); const focused = [...view.focused]
  view.unmount(); await tick(); view.flushTimers()
  assert.deepEqual(view.focused, focused)
  assert.equal(view.classes.has('guide-target'), false)
  assert.equal(view.listeners.get('keydown')?.size || 0, 0)
  assert.equal(view.listeners.get('resize')?.size || 0, 0)
})

test('welcome close autofocus announces only the still-active guide', async t => {
  const view = guide(t); view.begin(); await tick()
  const welcome = view.nodes().find(node => node.type?.name === 'el-dialog')
  assert.equal(typeof welcome.props.onCloseAutoFocus, 'function')
  view.opener.focus(); welcome.props.onCloseAutoFocus(); await tick()
  assert.equal(view.document.activeElement, view.title)
  welcome.props.onCloseAutoFocus(); view.pause(); const focused = [...view.focused]; await tick()
  assert.deepEqual(view.focused, focused)
})

test('a body focus origin returns to the meaningful main region', async t => {
  const view = guide(t)
  view.document.body = { ...view.opener, name: 'body' }
  view.document.activeElement = view.document.body
  view.start(); await tick(); view.pause()
  assert.equal(view.document.activeElement, view.main)
})


test('fast welcome begin then pause never restores focus to the leaving Start button', async t => {
  const view = guide(t)
  const welcomeStart = Vue.markRaw({ ...view.opener, name: 'welcome-start' })
  view.document.activeElement = welcomeStart
  view.begin(); await tick()
  assert.equal(welcomeStart.isConnected, true)
  assert.equal(welcomeStart.getClientRects().length, 1, 'The 300 ms leaving dialog still has layout')
  view.pause()
  assert.equal(view.document.activeElement, view.main)
  welcomeStart.hidden = true
  view.nodes().find(node => node.type?.name === 'el-dialog').props.onCloseAutoFocus()
  await tick()
  assert.equal(view.document.activeElement, view.main, 'Late welcome autofocus must not reselect hidden content')
})

test('an old welcome close callback cannot steal focus from a restarted guide target', async t => {
  const view = guide(t)
  view.begin(); await tick()
  const closingWelcome = view.nodes().find(node => node.type?.name === 'el-dialog').props.onCloseAutoFocus
  view.pause(); view.start(); await tick(); view.target.focus()
  closingWelcome(); await tick()
  assert.equal(view.document.activeElement, view.target)
  assert.equal(view.active.value, true)
})
