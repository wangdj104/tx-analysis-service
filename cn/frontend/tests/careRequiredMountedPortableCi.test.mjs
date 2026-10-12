// A checkout dependency must be loaded from the checkout being tested, even if
// an older developer tree happens to remain readable on the same machine.
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { babelParse } from '@vue/compiler-sfc'

const edition = import.meta.url.includes('/cn/') ? 'cn/frontend' : 'frontend'
const testsDirectory = fileURLToPath(new URL('./', import.meta.url))
function testSources(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const filename = path.join(directory, entry.name)
    return entry.isDirectory() ? testSources(filename) : /\.(?:[cm]?js|ts)$/.test(entry.name) ? [filename] : []
  })
}
function fixedCheckoutPaths(source) {
  const ast = babelParse(source, { sourceType: 'unambiguous', plugins: ['typescript'] })
  const violations = []
  function visit(node) {
    if (!node || typeof node !== 'object') return
    if (node.type === 'StringLiteral' || node.type === 'TemplateElement') {
      let value = node.type === 'StringLiteral' ? node.value : node.value.cooked ?? node.value.raw
      try { value = decodeURIComponent(value) } catch { /* Non-URL strings remain readable. */ }
      if (/(?:^|[\s"'`])(?:file:(?:\/\/(?:localhost)?)?)?\/(?:workspace|tmp)(?:\/|$)/.test(value)) violations.push({ line: node.loc?.start.line, value })
    }
    for (const child of Object.values(node)) {
      if (Array.isArray(child)) child.forEach(visit)
      else if (child && typeof child === 'object') visit(child)
    }
  }
  visit(ast)
  return violations
}

test(`${edition}: every frontend test harness avoids fixed workspace/temp module paths`, () => {
  const violations = testSources(testsDirectory).flatMap(filename => fixedCheckoutPaths(fs.readFileSync(filename, 'utf8')).map(value => ({ file: path.relative(testsDirectory, filename), ...value })))
  assert.deepEqual(violations, [], 'Use a current-checkout resolver such as new URL(..., import.meta.url), never a developer workspace/temp path')
})

test(`${edition}: portability guard catches direct, dynamic and generated imports for both fixed roots`, () => {
  for (const root of ['workspace', 'tmp']) {
    const absolute = '/' + root + '/unrelated-checkout/src/dependency.js'
    for (const source of [
      `import dependency from ${JSON.stringify(absolute)}`,
      `await import(${JSON.stringify('file://' + absolute)})`,
      'const compiled = `from \'' + 'file://' + absolute + '\'`',
    ]) assert.equal(fixedCheckoutPaths(source).length, 1, 'A fixed path is detected regardless of how the harness imports it')
  }
  for (const source of [
    "const dependency = new URL('../src/utils/authSession.js', import.meta.url).href; await import(dependency)",
    "import x from '../tmp/dependency.js'; import y from './workspace/dependency.js'; import z from '../workspace/dependency.js'",
    "const endpoint = '/api/workspace/summary'",
    "await import('https://example.test/tmp/demo.js')",
    "await import('https://example.invalid/workspace/dependency.js')",
  ]) assert.deepEqual(fixedCheckoutPaths(source), [], 'Relative checkout and non-filesystem URL resolution remain valid')
})

test(`${edition}: relocated mounted real component loads authSession from its own checkout`, t => {
  // A space in the checkout name also exercises URL encoding. No production
  // component logic, test assertions or auth exports are replaced in the copy.
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'care-required-ci checkout-'))
  t.after(() => fs.rmSync(temporary, { recursive: true, force: true }))
  const checkout = path.join(temporary, edition)
  for (const directory of ['tests', 'src/components', 'src/utils']) fs.mkdirSync(path.join(checkout, directory), { recursive: true })
  fs.writeFileSync(path.join(checkout, 'package.json'), '{"type":"module"}\n')
  fs.symlinkSync(fs.realpathSync(new URL('../node_modules', import.meta.url)), path.join(checkout, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir')
  fs.copyFileSync(new URL('../src/components/CareEntryDialog.vue', import.meta.url), path.join(checkout, 'src/components/CareEntryDialog.vue'))
  const auth = fs.readFileSync(new URL('../src/utils/authSession.js', import.meta.url), 'utf8')
  fs.writeFileSync(path.join(checkout, 'src/utils/authSession.js'), auth + '\nglobalThis.__carePortableAuthModuleUrl = import.meta.url;\n')
  const mounted = fs.readFileSync(new URL('./careRequiredMountedBacklog.test.mjs', import.meta.url), 'utf8')
  const probe = `\ntest('actual mounted fixture must import authSession from this checkout', () => {\n  assert.equal(globalThis.__carePortableAuthModuleUrl, new URL('../src/utils/authSession.js', import.meta.url).href)\n})\n`
  const filename = path.join(checkout, 'tests/careRequiredMountedBacklog.test.mjs')
  fs.writeFileSync(filename, mounted + probe)
  // The child is a fresh test runner, not the parent's IPC test worker.
  const env = { ...process.env }; delete env.NODE_TEST_CONTEXT
  const result = spawnSync(process.execPath, ['--test', '--test-reporter=tap', filename], { cwd: checkout, env, encoding: 'utf8', timeout: 30000 })
  assert.ifError(result.error)
  assert.equal(result.status, 0, `Relocated mounted component must load this checkout's real dependency:\n${result.stdout}\n${result.stderr}`)
  assert.match(result.stdout, /^# pass 2$/m, 'The original mounted behavior assertion and dependency-origin probe both ran')
  assert.match(result.stdout, /^# skipped 0$/m, 'No real component check may be skipped')
})
