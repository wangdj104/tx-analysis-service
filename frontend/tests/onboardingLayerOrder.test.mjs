import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import postcss from 'postcss'

// Regression for the 320px shell failure: a highlighted nursing section can
// reach behind the Pause button. A child card's z-index cannot escape the
// guide layer's stacking context, even when the button itself fits on screen.
// Lowering that containing layer beneath the highlighted target fails here;
// the browser matrix separately verifies the actual native hit target.
for (const edition of ['en', 'cn']) {
  const path = edition === 'en' ? '../src/' : '../../cn/frontend/src/'
  const source = readFileSync(new URL(`${path}components/OnboardingGuide.vue`, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const rules = postcss.parse(descriptor.styles.map(style => style.content).join('\n'))
  const declaration = (selector, property) => {
    let value
    rules.walkRules(rule => {
      if (rule.selector === selector) rule.walkDecls(property, item => { value = item.value })
    })
    return value
  }

  test(`${edition}: guide controls stack above the highlighted target's whole region`, () => {
    const layer = Number(declaration('.guide-layer', 'z-index'))
    const target = Number(declaration('.guide-target', 'z-index'))
    assert.ok(Number.isFinite(layer) && Number.isFinite(target), 'Both stacking levels must be explicit')
    assert.ok(layer > target, `The guide layer (${layer}) must paint above the highlighted target (${target})`)
    assert.equal(declaration('.guide-layer', 'position'), 'fixed')
  })

  test(`${edition}: raising the guide does not block the interactive target outside its card`, () => {
    assert.equal(declaration('.guide-layer', 'pointer-events'), 'none')
    assert.equal(declaration('.guide-card', 'pointer-events'), 'auto')
    assert.notEqual(declaration('.guide-target', 'pointer-events'), 'none')
  })
}
