import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as helpers from '../e2e/helpers.mjs'

// DOM boundary fixtures exercise the actual E2E predicate; genuine Vue layout,
// styles, browser hit-testing and screenshots are verified by native browser CI.
function fixture() {
  const style = () => ({ opacity: '1', visibility: 'visible', display: 'block', transform: 'none' })
  const rect = () => ({ left: 30, top: 50, right: 160, bottom: 75, width: 130, height: 25 })
  const node = (name, parentElement = null) => ({ name, parentElement, isConnected: true, style: style(), animations: [], rect: rect(),
    getAnimations() { return this.animations }, getBoundingClientRect() { return this.rect }, contains(value) { return value === this || value?.parentElement === this },
  })
  const html = node('html'), body = node('body', html), module = node('module', body), overlay = node('overlay', module), root = node('dialog', overlay), panel = node('panel', root)
  overlay.classList = { contains: value => overlay.classes.includes(value) }; overlay.classes = []
  const title = node('title', panel), source = node('source', panel), close = node('close', panel)
  source.textContent = 'Synthetic exact report medical source'
  root.querySelector = selector => ({ '.el-dialog': panel, '.el-dialog__title': title, '.el-dialog__headerbtn': close })[selector]
  root.querySelectorAll = () => [source]
  root.closest = () => overlay
  let hits = [title, source, close], nextHit = 0
  const document = { defaultView: { innerWidth: 390, innerHeight: 900, getComputedStyle: el => el.style }, documentElement: html,
    elementFromPoint: () => hits[nextHit++ % hits.length] }
  for (const item of [html, body, module, overlay, root, panel, title, source, close]) item.ownerDocument = document
  return { root, panel, overlay, module, title, source, close, document, setHits(values) { hits = values; nextHit = 0 } }
}
function readiness(v) {
  assert.equal(typeof helpers.readMedicalDialogReadiness, 'function', 'The native fixture needs a real settled-dialog readiness predicate')
  return helpers.readMedicalDialogReadiness(v.root, 'Synthetic exact report medical source')
}

test('fully opaque settled medical title, exact source and close targets are ready', () => assert.equal(readiness(fixture()), 'ready'))
for (const name of ['root', 'overlay', 'module']) test(`partial ${name} opacity cannot authorize a medical screenshot`, () => {
  const v = fixture(); v[name].style.opacity = '0.2'; assert.equal(readiness(v), 'not-opaque')
})
for (const name of ['root', 'overlay', 'module']) test(`an unfinished ${name} animation cannot authorize a medical screenshot`, () => {
  const v = fixture(); v[name].animations = [{ playState: 'running', pending: false }]; assert.equal(readiness(v), 'animating')
})
test('a paused ancestor animation is not a completed opening transition', () => {
  const v = fixture(); v.module.animations = [{ playState: 'paused', pending: false }]; assert.equal(readiness(v), 'animating')
})
test('pending animation scheduling cannot authorize a medical screenshot', () => {
  const v = fixture(); v.root.animations = [{ playState: 'finished', pending: true }]; assert.equal(readiness(v), 'animating')
})
test('remaining Element Plus transition classes are not an opened dialog', () => {
  const v = fixture(); v.overlay.classes = ['dialog-fade-enter-active']; assert.equal(readiness(v), 'transitioning')
})
test('a translated dialog overlay is not a settled opening state', () => {
  const v = fixture(); v.root.style.transform = 'matrix(1, 0, 0, 1, 0, -12)'; assert.equal(readiness(v), 'moving')
})
for (const name of ['title', 'source', 'close']) test(`an obscured medical ${name} fails the genuine hit-target contract`, () => {
  const v = fixture(), hits = [v.title, v.source, v.close]; hits[['title', 'source', 'close'].indexOf(name)] = v.module; v.setHits(hits); assert.equal(readiness(v), 'obscured')
})
test('the exact source cell must be visible inside the current viewport', () => {
  const v = fixture(); v.source.rect = { left: 30, top: 880, right: 160, bottom: 905, width: 130, height: 25 }; assert.equal(readiness(v), 'offscreen')
})
test('a missing exact source cell cannot authorize a medical screenshot', () => {
  const v = fixture(); v.source.textContent = 'Synthetic outside medical source'; assert.equal(readiness(v), 'missing-target')
})
test('hidden dialog ancestry is not visually ready', () => {
  const v = fixture(); v.overlay.style.visibility = 'hidden'; assert.equal(readiness(v), 'hidden')
})
test('completed animations permit a fully opaque unobscured dialog', () => {
  const v = fixture(); v.module.animations = [{ playState: 'finished', pending: false }]; assert.equal(readiness(v), 'ready')
})
test('medical source acceptance waits for genuine visual readiness before captures and dismissal', () => {
  const spec = fs.readFileSync(new URL('../e2e/careExecutionReportSources.spec.mjs', import.meta.url), 'utf8')
  assert.match(spec, /await assertMedicalDialogReady\(medicalDialog\(page\), 'Synthetic exact report medical source'\)/)
  const close = spec.slice(spec.indexOf('async function closeMedicalDialog'), spec.indexOf('async function assertUnavailable'))
  assert.match(close, /assertMedicalDialogReady[\s\S]*keyboard\.press\('Escape'\)/)
  assert.equal((spec.match(/source\.kind}-\$\{mobile \? '390' : 'desktop'\}-exact-source\.png/g) || []).length, 1)
})

for (const name of ['title', 'source', 'close']) test(`partial medical ${name} opacity is not readable even when the panel is opaque`, () => {
  const v = fixture(); v[name].style.opacity = '0.2'; assert.equal(readiness(v), 'not-opaque')
})
