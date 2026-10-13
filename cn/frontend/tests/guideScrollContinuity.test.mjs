import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import * as Vue from 'vue'
import { parse } from '@vue/compiler-sfc'

const read = edition => readFileSync(new URL(`${edition === 'cn' ? '../' : '../../../frontend/'}src/components/OnboardingGuide.vue`, import.meta.url), 'utf8')
async function guide(t, edition, height = 900, { clampInitialScroll = false, initialTop = 507, naturalCardHeight = 364 } = {}) {
  const script = parse(read(edition)).descriptor.scriptSetup.content.replace(/^import.*$/gm, ''), listeners = new Map(), timers = new Map(), unmounts = [], calls = [], observers = []
  const geometry = { top: initialTop, bottom: initialTop + 44, height: 44, left: 14, width: 146 }, viewport = { innerWidth: 320, innerHeight: height }, style = new Map([['padding-bottom', '76px']])
  let view, id = 0
  const listen = (type, callback, options) => { if (!listeners.has(type)) listeners.set(type, new Map()); listeners.get(type).set(callback, options) }
  const unlisten = (type, callback) => listeners.get(type)?.delete(callback)
  const emit = type => { for (const callback of listeners.get(type)?.keys() || []) callback({ type, target: document }) }
  const node = extra => Vue.markRaw({ isConnected: true, disabled: false, getClientRects: () => [{}], closest: () => null, focus() { document.activeElement = this }, ...extra })
  const action = node({ getBoundingClientRect: () => ({ ...geometry }) })
  const target = node({ getBoundingClientRect: () => ({ ...geometry }), querySelector: () => action, scrollIntoView() {}, classList: { add() {}, remove() {} } })
  const title = node({}), opener = node({}), main = node({ style: { getPropertyValue: key => style.get(key) || '', getPropertyPriority: () => '', setProperty: (key, value) => style.set(key, value), removeProperty: key => style.delete(key) } })
  const document = { activeElement: opener, body: {}, documentElement: {}, addEventListener: listen, removeEventListener: unlisten,
    querySelector: selector => selector === '#workspace-content' ? main : selector === '.workspace-topbar' ? { getBoundingClientRect: () => ({ bottom: 133 }) } : target,
    querySelectorAll: selector => /dialog/.test(selector) ? [] : [target] }
  const card = node({ contains: () => false, getBoundingClientRect() {
    const height = Math.min(naturalCardHeight, viewport.innerHeight / 2, view?.mobileCardMaxHeight?.value ?? Infinity), top = view?.mobileGuideAtTop.value ? 12 : viewport.innerHeight - height - 12
    return { height, top, bottom: top + height }
  }, querySelector: () => ({ scrollHeight: naturalCardHeight, clientHeight: card.getBoundingClientRect().height }) })
  const bindings = { ...Vue, Guide: {}, Menu: {}, Search: {}, User: {}, FirstAidKit: {}, Odometer: {}, House: {}, Setting: {}, Switch: {},
    defineProps: () => ({ accountId: 'synthetic', roleCodes: ['nurse'] }), defineExpose() {}, useReducedMotion: () => Vue.ref(true),
    useRouter: () => ({ currentRoute: Vue.ref({ path: '/nurse-workspace' }), push: async () => {} }), document,
    window: { ...viewport, addEventListener: listen, removeEventListener: unlisten, scrollBy(options) { calls.push(options); if (!clampInitialScroll) { geometry.top -= options.top; geometry.bottom -= options.top } } },
    getComputedStyle: () => ({ paddingBottom: '76px' }), localStorage: { getItem: key => key.includes('prompt') ? 'seen' : null, setItem() {} },
    setTimeout: callback => { const key = ++id; timers.set(key, callback); return key }, clearTimeout: key => timers.delete(key), onBeforeUnmount: callback => unmounts.push(callback),
    ResizeObserver: class { constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this) } observe() {} disconnect() { this.disconnected = true } } }
  delete bindings.default; delete bindings['module.exports']
  const scope = Vue.effectScope()
  view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {start,pause,previous,confirmStep,positionGuide,active,guideCard,guideTitle,mobileGuideAtTop,mobileCardMaxHeight:typeof mobileCardMaxHeight==="undefined"?null:mobileCardMaxHeight}')(...Object.values(bindings)))
  view.guideCard.value = card; view.guideTitle.value = title
  const flush = async () => { await Vue.nextTick(); while (timers.size) { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()) } await Vue.nextTick() }
  t.after(() => { unmounts.forEach(callback => callback()); scope.stop() })
  view.start(); await flush()
  const clear = () => { const rect = card.getBoundingClientRect(); return geometry.bottom + 12 <= rect.top || geometry.top >= rect.bottom + 12 }
  return { view, card, geometry, calls, listeners, style, observers, clear, flush,
    manualScroll(delta) { geometry.top -= delta; geometry.bottom -= delta; emit('scroll') },
    resize(nextHeight) { viewport.innerHeight = nextHeight; bindings.window.innerHeight = nextHeight; emit('resize') },
    unmount: () => unmounts.forEach(callback => callback()) }
}
for (const edition of ['en', 'cn']) {
  test(`${edition}: initial clamped scroll with no event still clears the actual CI Refresh bounds`, async t => {
    // CI run38350944017 failed before scroll-back: requested initial scrolling
    // left Refresh506.90625-550.90625 and the card523.875-888 unchanged.
    // The body had a popup scroll lock; this adapter models the observed
    // no-movement/no-event outcome, without claiming its exact browser cause.
    const v = await guide(t, edition, 900, { clampInitialScroll: true, initialTop: 506.90625, naturalCardHeight: 364.125 })
    assert.equal(v.calls.length, 1, 'a clamped initial attempt must not be retried as a scroll trap')
    assert.equal(v.geometry.top, 506.90625); assert.equal(v.geometry.bottom, 550.90625)
    assert.ok(v.clear(), 'initial positioning must adapt the card even when scrollBy produced no movement or event')
    assert.equal(v.view.mobileGuideAtTop.value, true)
  })
  test(`${edition}: scroll-back keeps Refresh clear without moving the user's page`, async t => {
    const v = await guide(t, edition), initialCalls = v.calls.length
    assert.equal(v.geometry.top, 468); assert.ok(v.clear())
    v.manualScroll(-39)
    assert.equal(v.geometry.top, 507, 'manual page position must be preserved')
    assert.ok(v.clear(), 'the fixed card must move clear of the returned Refresh button')
    assert.equal(v.calls.length, initialCalls, 'scroll positioning must not fight the user with another scrollBy')
    assert.ok([...v.listeners.get('scroll').values()].every(options => options?.passive === true))
  })
  test(`${edition}: repeated scroll and resize preserve separation in short mobile viewports`, async t => {
    const v = await guide(t, edition), initialCalls = v.calls.length
    for (const height of [900, 450, 320]) {
      v.resize(height)
      for (const top of [12, height / 2 - 22, height - 56, 507]) {
        v.manualScroll(v.geometry.top - top)
        assert.equal(v.geometry.top, top); assert.ok(v.clear(), `${height}px / target ${top}px: card needs a clear side or a smaller band`)
        const box = v.card.getBoundingClientRect(); assert.ok(box.top >= 12 && box.bottom <= height - 12)
      }
    }
    assert.equal(v.calls.length, initialCalls, 'neither scroll nor resize should force an additional page scroll')
  })
  test(`${edition}: next, previous, pause and unmount own only one scroll listener and clean it up`, async t => {
    const v = await guide(t, edition)
    assert.equal(v.listeners.get('scroll')?.size, 1)
    v.view.confirmStep(); await v.flush(); v.view.previous(); await v.flush()
    assert.equal(v.listeners.get('scroll')?.size, 1)
    v.view.pause(); assert.equal(v.listeners.get('scroll')?.size, 0); assert.equal(v.style.get('padding-bottom'), '76px')
    assert.ok(v.observers.every(observer => observer.disconnected))
    v.view.start(); await v.flush(); assert.equal(v.listeners.get('scroll')?.size, 1)
    v.unmount(); assert.equal(v.listeners.get('scroll')?.size, 0); assert.equal(v.listeners.get('resize')?.size, 0)
    assert.equal(v.style.get('padding-bottom'), '76px')
  })
}
