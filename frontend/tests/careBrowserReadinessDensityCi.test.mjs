// Local regressions for care run 38204792928, not replacement browser evidence.
import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { babelParse, parse } from '@vue/compiler-sfc'
import postcss from 'postcss'

const source = readFileSync(new URL('./careDraftDialogBacklog.browser.mjs', import.meta.url), 'utf8')
const ast = babelParse(source, { sourceType: 'module' })
const themeCode = ast.program.body.filter(node =>
  node.type === 'FunctionDeclaration' && node.id.name === 'checkTheme' ||
  node.type === 'VariableDeclaration' && node.declarations.some(item => ['themeOracle', 'expectedPageColors'].includes(item.id?.name))
).map(node => source.slice(node.start, node.end)).join('\n')
const mint = Object.freeze({ theme: 'mint', body: 'rgb(234, 244, 239)', tokens: ['#ffffff', '#f0f7f3', '#edf7f1', '#176b59'] })

function themeHarness(frames) {
  let frame = frames[0], attempts = 0, waitOptions
  const root = Object.freeze({ get dataset() { return Object.freeze({ healthTheme: frame.theme }) } })
  const body = Object.freeze({})
  const checkTheme = new Function('assert', 'document', 'window', 'getComputedStyle', themeCode + '\nreturn checkTheme')(
    assert,
    Object.freeze({ documentElement: root, body }),
    Object.freeze({ innerWidth: 1365, innerHeight: 900, devicePixelRatio: 1 }),
    element => {
      return Object.freeze({
        backgroundColor: frame.body,
        color: 'rgb(32, 52, 44)',
        getPropertyValue(token) {
          assert.equal(element, root)
          return frame.tokens[['--paper', '--surface-subtle', '--care-50', '--care-700'].indexOf(token)]
        },
      })
    },
  )
  const page = {
    async evaluate(read) { return structuredClone(read()) },
    async waitForFunction(predicate, expected, options) {
      waitOptions = options
      for (const next of frames) {
        frame = next
        attempts++
        const value = predicate(expected)
        if (value) return
      }
      throw new Error('Timed out waiting for the exact computed theme')
    },
  }
  return { run: () => checkTheme(page, 'mint'), get attempts() { return attempts }, get waitOptions() { return waitOptions } }
}

test('care theme readiness waits past the observed platform body while retaining the exact mint oracle', async () => {
  const v = themeHarness([{ ...mint, body: 'rgb(245, 247, 251)' }, mint])
  const actual = await v.run()
  assert.equal(actual.body, mint.body)
  assert.deepEqual(actual.tokens, mint.tokens)
  assert.equal(v.attempts, 2)
  assert.equal(v.waitOptions.polling, 'raf', 'Wait on rendered frames rather than a fixed sleep')
  assert.equal(v.waitOptions.timeout, 10000, 'A persistent style defect must fail within the existing page budget')
})

test('care theme readiness requires both the real preset and every computed token before accepting a frame', async () => {
  const wrongTokenFrames = mint.tokens.map((_, index) => ({ ...mint, tokens: mint.tokens.map((token, i) => i === index ? '#000000' : token) }))
  const v = themeHarness([{ ...mint, theme: 'platform' }, ...wrongTokenFrames, mint])
  assert.equal((await v.run()).theme, 'mint')
  assert.equal(v.attempts, 6, 'Matching only the dataset or body must not satisfy readiness')
})

for (const [name, bad] of [
  ['body', { ...mint, body: 'rgb(245, 247, 251)' }],
  ['preset', { ...mint, theme: 'platform' }],
  ...mint.tokens.map((_, index) => [`token ${index}`, { ...mint, tokens: mint.tokens.map((token, i) => i === index ? '#000000' : token) }]),
]) test(`care theme readiness times out on a persistent wrong ${name}`, async () => {
  const v = themeHarness([bad, bad])
  await assert.rejects(v.run(), /Timed out waiting for the exact computed theme/)
  assert.equal(v.attempts, 2)
})

for (const edition of ['frontend', 'cn/frontend']) test(`${edition} task spacing compacts whitespace while retaining readable full-size controls`, () => {
  const component = readFileSync(new URL(`../../${edition}/src/components/care-plan/PlanTaskList.vue`, import.meta.url), 'utf8')
  const css = postcss.parse(parse(component).descriptor.styles[0].content)
  const declarations = selector => {
    const values = {}
    css.walkRules(rule => { if (rule.selectors.includes(selector)) rule.walkDecls(d => { values[d.prop] = d.value }) })
    return values
  }
  const margins = declarations('.action-group').margin.split(/\s+/).map(parseFloat)
  assert.ok(margins[0] > 0 && margins[0] <= 8, 'Only surplus action-group whitespace is compacted to clear the observed CN375 density miss')
  assert.equal(declarations('.action-group>summary')['min-height'], '44px')
  assert.equal(declarations('.action-event-history>summary')['min-height'], '44px')
  assert.equal(declarations('button')['min-height'], '44px')
  assert.equal(declarations('button').font, 'inherit')
  assert.equal(declarations('.action-card p')['line-height'], '1.5')
  assert.equal(declarations('.action-group>summary')['line-height'], '1.5')
  assert.equal(declarations('.plan-card h3')['font-size'], '1.125em')
})
