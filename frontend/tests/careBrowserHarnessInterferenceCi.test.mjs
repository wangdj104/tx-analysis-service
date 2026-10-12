// Harness-only regressions: do not launch a browser or replace product assertions.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { babelParse } from '@vue/compiler-sfc'

const source = readFileSync(new URL('./careDraftDialogBacklog.browser.mjs', import.meta.url), 'utf8')
const ast = babelParse(source, { sourceType: 'module' })
const nodes = value => !value || typeof value !== 'object' ? [] : [value, ...Object.values(value).flatMap(child => Array.isArray(child) ? child.flatMap(nodes) : nodes(child))]
function declaration(name) {
  const found = ast.program.body.find(node => node.type === 'FunctionDeclaration' && node.id.name === name)
  assert.ok(found, `actual harness function ${name}`)
  return source.slice(found.start, found.end)
}
function loadQaClick() { return new Function(declaration('qaClick') + ';return qaClick')() }
function fakeTools() {
  const details = { open: false }
  const element = { hidden: false, inert: false, querySelector: () => details }
  const tools = {
    evaluate: async fn => fn(element),
    locator: selector => selector === 'details' ? { getAttribute: async () => details.open ? '' : null } : { click: async () => { details.open = true } },
  }
  return { element, tools, details }
}

test('care browser uses a compiled Vite preview before DOM cases, never a dev-client server', () => {
  const vite = ast.program.body.find(node => node.type === 'ImportDeclaration' && node.source.value === 'vite')
  const names = vite.specifiers.map(node => node.imported.name)
  assert.ok(names.includes('preview'), 'Serve the compiled bundle with supported Vite preview, eliminating injected HMR clients')
  assert.equal(names.includes('createServer'), false, 'No development server may inject a port-zero WebSocket client')
  const calls = nodes(ast).filter(node => node.type === 'AwaitExpression' && node.argument.type === 'CallExpression')
  const compile = calls.find(node => node.argument.callee.name === 'build')
  const serve = calls.find(node => node.argument.callee.name === 'preview')
  assert.ok(compile && serve && compile.end < serve.start, 'Browser and compile-only modes must use the same built artifact')
})

test('synthetic care controls start hidden and inert in the real fixture render', () => {
  const main = nodes(ast).find(node => node.type === 'VariableDeclarator' && node.id?.name === 'main' && node.init?.type === 'TemplateLiteral').init
  const generated = main.quasis.map(node => node.value.cooked).join('QA_PLACEHOLDER')
  const aside = nodes(babelParse(generated, { sourceType: 'module' })).find(node => node.type === 'CallExpression' && node.callee.name === 'h' && node.arguments[0]?.value === 'aside')
  assert.ok(aside, 'test-only aside remains a DOM fixture')
  for (const key of ['hidden', 'inert']) assert.equal(aside.arguments[1].properties.find(node => node.key.name === key)?.value.value, true, `${key} prevents interception outside QA actions`)
})

for (const reject of [false, true]) test(`QA action ${reject ? 'failure' : 'success'} restores hidden/inert controls before product clicks`, async () => {
  const { element, tools, details } = fakeTools()
  let clicks = 0
  const page = { locator: selector => selector === '[data-qa-tools]' ? tools : { click: async options => {
    assert.equal(options, undefined, 'No force click or DOM event bypass')
    assert.equal(element.hidden, false); assert.equal(element.inert, false)
    clicks++
    if (reject) throw new Error('Synthetic click failure')
  } } }
  if (reject) await assert.rejects(loadQaClick()(page, 'nav-entry'), /Synthetic click failure/)
  else await loadQaClick()(page, 'nav-entry')
  assert.equal(clicks, 1, 'the actual DOM action is attempted once')
  assert.equal(element.hidden, true, 'aside cannot intercept a subsequent mobile Save')
  assert.equal(element.inert, true, 'test controls cannot take production focus')
  assert.equal(details.open, false)
})

test('care WebSocket guard still closes every socket and flags genuinely foreign endpoints', async () => {
  const declarations = ast.program.body.filter(node => node.type === 'VariableDeclaration' && node.declarations.some(item => ['themeOracle', 'expectedPageColors'].includes(item.id?.name)))
  const code = declarations.map(node => source.slice(node.start, node.end)).join('\n') + '\n' + declaration('checkTheme') + '\n' + declaration('newPage')
  const newPage = new Function('assert', code + ';return newPage')(assert)
  let guard
  const signature = { theme: 'platform', body: 'rgb(245, 247, 251)', tokens: ['#ffffff', '#f4f7f6', '#f0f7f3', '#176b59'], innerWidth: 375, innerHeight: 900 }
  const page = { setDefaultTimeout() {}, on() {}, async addInitScript() {}, async goto() {}, locator: () => ({ async waitFor() {} }), async evaluate() { return signature } }
  const context = { async newPage() { return page }, async route() {}, async routeWebSocket(pattern, handler) { guard = handler }, async close() {} }
  const session = await newPage({ async newContext(options) { assert.equal(options.serviceWorkers, 'block'); return context } }, 'http://127.0.0.1:43123', { viewport: 375, theme: 'platform' })
  for (const endpoint of ['ws://127.0.0.1:43123/', 'ws://outside.invalid:443/socket', 'ws://127.0.0.1:0/']) {
    let closed = 0
    await guard({ url: () => endpoint, close: () => { closed++ }, connectToServer: () => assert.fail('No socket may connect upstream') })
    assert.equal(closed, 1)
  }
  assert.deepEqual(session.errors, ['Blocked external WebSocket: ws://outside.invalid:443/socket', 'Blocked external WebSocket: ws://127.0.0.1:0/'], 'Preview removes dev stub creation, not the external-socket policy')
})

test('compiled care artifacts reject injected dev-client modules and HTML', () => {
  const check = new Function('assert', declaration('assertCompiledNoDevClient') + ';return assertCompiledNoDevClient')(assert)
  const chunk = { type: 'chunk', fileName: 'assets/app.js', modules: { '/current-checkout/src/main.js': {} } }
  const html = { type: 'asset', fileName: 'index.html', source: '<script type="module" src="/assets/app.js"></script>' }
  check({ output: [chunk, html] })
  assert.throws(() => check({ output: [{ ...chunk, modules: { '/dependencies/vite/dist/client/client.mjs': {} } }] }), /dev client/)
  assert.throws(() => check({ output: [chunk, { ...html, source: '<script type="module" src="/@vite/client"></script>' }] }), /must not inject HMR/)
})
