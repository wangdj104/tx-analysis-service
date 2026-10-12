// Layout fixtures verify the real capture guards; actual pixels run only in CI.
import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'

const source = readFileSync(new URL('./uiSeventeenClinical.browser.mjs', import.meta.url), 'utf8')
function helper(name, dependencies = {}) {
  const code = source.match(new RegExp(`(?:async )?function ${name}\\([^\\n]*\\) \\{[\\s\\S]*?\\n\\}`))?.[0]
  assert.ok(code, `Missing real clinical capture helper: ${name}`)
  return new Function('assert', ...Object.keys(dependencies), `${code};return ${name}`)(assert, ...Object.values(dependencies))
}
const rectangle = (left, top, width, height) => ({ left, top, right: left + width, bottom: top + height, width, height })
function layoutFixture({ mobile = false, fragment = false } = {}) {
  const width = mobile ? 375 : 1365, height = 900
  const scroller = {
    tagName: 'DIV', className: mobile ? 'el-dialog' : 'el-overlay-dialog',
    clientLeft: 0, clientTop: 0, clientWidth: width - 40, clientHeight: mobile ? 765 : 900,
    scrollHeight: 2600, scrollWidth: width - 40, scrollTop: 0, scrollLeft: 0, parentElement: null,
    getBoundingClientRect() { return rectangle(20, mobile ? 20 : 0, this.clientWidth, this.clientHeight) },
    scrollBy({ top, behavior }) { assert.equal(behavior, 'instant'); this.scrollTop = Math.max(0, Math.min(this.scrollHeight - this.clientHeight, this.scrollTop + top)) }
  }
  const root = mobile ? scroller : {
    tagName: 'DIV', className: 'el-dialog', clientLeft: 0, clientTop: 0, clientWidth: 900,
    clientHeight: 2500, scrollHeight: 2500, scrollWidth: 900, scrollTop: 0, scrollLeft: 0, parentElement: scroller,
    getBoundingClientRect() { return rectangle(232, 135 - scroller.scrollTop, 900, 2500) }
  }
  const node = {
    tagName: fragment ? 'PRE' : 'LABEL', className: 'capture-target', parentElement: root,
    textContent: 'Synthetic original start. Saved source paragraph 6. Synthetic original end.',
    getBoundingClientRect() { return rectangle(mobile ? 45 : 270, (fragment ? 800 : 2000) - scroller.scrollTop, mobile ? 285 : 800, fragment ? 1400 : 80) }
  }
  const textNode = { textContent: node.textContent }
  root.querySelectorAll = selector => selector === '.capture-target' ? [node] : []
  const document = {
    createTreeWalker() { let returned = false; return { nextNode() { if (returned) return null; returned = true; return textNode } } },
    createRange() { return { setStart() {}, setEnd() {}, getBoundingClientRect() { return rectangle(mobile ? 45 : 270, 1300 - scroller.scrollTop, mobile ? 285 : 300, 44) } } }
  }
  const getComputedStyle = element => ({ overflowX: element === scroller ? 'hidden' : 'visible', overflowY: element === scroller ? 'auto' : 'visible', visibility: 'visible', display: 'block', opacity: '1' })
  const measure = helper('readDialogCaptureGeometry', { innerWidth: width, innerHeight: height, getComputedStyle, document, NodeFilter: { SHOW_TEXT: 4 } })
  return { root, scroller, node, measure, width }
}

for (const mobile of [false, true]) test(`${mobile ? '375px native dialog' : '1365px overlay'}: capture scrolls real ancestor and records visible target geometry`, async () => {
  const fixture = layoutFixture({ mobile }), events = []
  const guard = helper('assertVisibleCaptureTargets')
  const capture = helper('captureDialogSnapshot', { readDialogCaptureGeometry: fixture.measure, assertVisibleCaptureTargets: guard })
  const dialog = { async evaluate(callback, options) { events.push(options.scroll ? 'scroll' : 'measure'); return callback(fixture.root, options) } }
  const page = { async screenshot(options) { events.push('capture'); assert.equal(options.fullPage, false); assert.equal(options.path, 'actual-viewport.png') } }
  const geometry = await capture(page, dialog, [{ selector: '.capture-target', label: 'acknowledgement' }], 'actual-viewport.png')
  assert.deepEqual(events, ['scroll', 'measure', 'capture'])
  assert.ok(fixture.scroller.scrollTop > 0)
  assert.equal(geometry.viewport.width, fixture.width)
  assert.ok(geometry.targets[0].scrollports.some(port => port.scrollTop > 0))
  guard(geometry)
})

test('an oversized original body uses a real text range without changing its DOM', () => {
  const fixture = layoutFixture({ mobile: true, fragment: true }), original = fixture.node.textContent
  const geometry = fixture.measure(fixture.root, { targets: [{ selector: '.capture-target', label: 'original body', text: 'Saved source paragraph 6' }], scroll: true })
  helper('assertVisibleCaptureTargets')(geometry)
  assert.equal(fixture.node.textContent, original)
  assert.ok(geometry.targets[0].elementBox.height > geometry.viewport.height)
  assert.equal(geometry.targets[0].box.height, 44)
  assert.equal(geometry.targets[0].text, 'Saved source paragraph 6')
})

test('capture rejects a clipped, hidden, missing or zero-area target before writing a PNG', async () => {
  const guard = helper('assertVisibleCaptureTargets'), capture = helper('captureDialogSnapshot', { readDialogCaptureGeometry() {}, assertVisibleCaptureTargets: guard })
  const clip = rectangle(20, 20, 335, 765)
  for (const target of [
    { box: rectangle(30, 780, 100, 50), rendered: true },
    { box: rectangle(0, 100, 100, 50), rendered: true },
    { box: rectangle(30, 100, 100, 50), rendered: false },
    { box: rectangle(30, 100, 0, 50), rendered: true }
  ]) {
    let captures = 0
    const geometry = { viewport: { width: 375, height: 900 }, targets: [{ label: 'required control', clip, ...target }] }
    await assert.rejects(() => capture({ async screenshot() { captures++ } }, { async evaluate() { return geometry } }, [{}], 'must-not-exist.png'))
    assert.equal(captures, 0)
  }
  assert.throws(() => guard({ viewport: { width: 375, height: 900 }, targets: [] }), /target/i)
})

test('each AI region, the native receipt picker, all five references and footer have guarded viewport evidence', () => {
  for (const name of ['ai-original-analysis-start', 'ai-original-analysis', 'ai-original-analysis-end', 'ai-saved-evidence', 'ai-acknowledgement-gate', 'ai-ready-decision', 'receipt-friendly-controls', 'receipt-record-picker', 'receipt-references-1-2', 'receipt-references-3-4', 'receipt-reference-5', 'receipt-footer']) {
    assert.match(source, new RegExp(`snapshot\\('${name}', \\{ dialog:`), `${name} must use a visible-dialog target guard`)
  }
  assert.match(source, /result\.screenshotGeometry\[path\] = geometry/)
  assert.match(source, /new Set\(aiCapturePaths\.map\(path => result\.screenshotHashes\[path\]\)\)\.size/)
  assert.match(source, /Full original analysis, saved evidence\/provenance/)
  assert.match(source, /assert\.deepEqual\(fixture\.writes\.at\(-1\)\.body/)
})

test('visible toast bounds reject the actual negative-left and right-overflow mobile regressions', async () => {
  const bounds = helper('assertVisibleToastBounds')
  for (const box of [rectangle(-100, 16, 343, 50), rectangle(30, 16, 360, 50)]) {
    const page = { locator() { return { async evaluateAll() { return [{ box, width: 375, text: 'Synthetic query failure' }] } } } }
    await assert.rejects(() => bounds(page, 375), /viewport/)
  }
  const page = { locator() { return { async evaluateAll() { return [{ box: rectangle(16, 16, 343, 50), width: 375, text: 'Synthetic query failure' }] } } } }
  assert.equal((await bounds(page, 375)).length, 1)
  assert.match(source, /snapshot\('lab-error-toast', \{ toasts: true \}\)/)
  assert.match(source, /snapshot\('lab-empty-toast', \{ toasts: true \}\)/)
})

test('clinical fixture imports and hashes the actual App global reset without mounting App or copying its CSS', () => {
  assert.match(source, /src\('App\.vue\?vue&type=style&index=0&lang\.css'\)/)
  assert.match(source, /'App\.vue',/)
  assert.doesNotMatch(source, /import App from|h\(App[,) ]|createApp\(App\)/)
  assert.doesNotMatch(source, /box-sizing:\s*border-box|body\s*\{\s*margin:\s*0/)
  assert.ok(source.indexOf("src('App.vue?vue&type=style&index=0&lang.css')") < source.indexOf("import 'element-plus/dist/index.css'"), 'Match the actual main.js cascade order')
  for (const edition of ['en', 'cn']) {
    const app = readFileSync(new URL(`${edition === 'en' ? '../' : '../../cn/frontend/'}src/App.vue`, import.meta.url), 'utf8')
    const { descriptor, errors } = parse(app)
    assert.deepEqual(errors, [])
    assert.equal(descriptor.styles[0].scoped, undefined)
    for (const rule of [/box-sizing:\s*border-box/, /body\s*\{\s*margin:\s*0/, /select\s*\{\s*font:\s*inherit/]) assert.match(descriptor.styles[0].content, rule)
  }
})
