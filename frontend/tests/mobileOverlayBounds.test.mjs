import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref } from 'vue'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { baseParse } from '@vue/compiler-dom'
import postcss from 'postcss'

const read = file => fs.readFileSync(new URL('../src/' + file, import.meta.url), 'utf8')
const guide = read('components/OnboardingGuide.vue')
const mobileRules = css => {
  const rules = []
  postcss.parse(css).walkRules(rule => {
    let parent = rule.parent
    while (parent && parent.type !== 'atrule') parent = parent.parent
    if (parent?.name === 'media' && /max-width:\s*(719|768)px/.test(parent.params)) rules.push(rule)
  })
  return rules
}
function declarations(rules, selector) {
  const values = new Map()
  for (const rule of rules.filter(rule => rule.selector === selector)) {
    rule.walkDecls(declaration => values.set(declaration.prop, declaration))
  }
  return values
}

// This executes the shipped computed style. The previous fixed 285px estimate
// yielded top=603px at 375x900, pushing a wrapped 320px card below the viewport.
test('mobile onboarding anchors its natural card height inside the viewport', () => {
  const block = guide.slice(guide.indexOf('const cardStyle'), guide.indexOf('const guideTitle'))
  for (const width of [375, 390, 719]) for (const height of [320, 600, 900]) {
    const viewport = reactive({ width, height }), placement = ref('right')
    const style = new Function('computed', 'viewport', 'targetBox', 'cardPlacement', block + ';return cardStyle')(computed, viewport, { top: 410, left: 10, width: 351, height: 105 }, placement).value
    assert.equal(style.top, 'auto', `${width}x${height}: mobile must not guess a content-dependent top`)
    assert.equal(style.bottom, '12px')
    assert.equal(style.left, '12px')
    assert.equal(Number.parseFloat(style.width), Math.min(370, width - 24))
  }
})

test('mobile guide scrolls only its description, keeping heading, close and navigation outside the scroll region', () => {
  const { descriptor } = parseSfc(guide)
  const ast = baseParse(descriptor.template.content)
  const visit = node => [node, ...(node.children || []).flatMap(visit)]
  const nodes = visit(ast), hasClass = (node, name) => node.props?.some(prop => prop.name === 'class' && prop.value?.content?.split(' ').includes(name))
  const card = nodes.find(node => hasClass(node, 'guide-card'))
  const content = card.children.find(node => hasClass(node, 'guide-content'))
  assert.ok(content, 'Descriptions need a bounded scroll region rather than clipping the whole card')
  assert.ok(card.children.some(node => node.tag === 'h2'))
  assert.ok(card.children.some(node => node.tag === 'footer'))
  assert.ok(card.children.some(node => hasClass(node, 'guide-close')))
  assert.equal(visit(content).some(node => ['footer', 'h2', 'button'].includes(node.tag)), false)
  const rules = mobileRules(descriptor.styles.map(style => style.content).join('\n'))
  const cardCss = declarations(rules, '.guide-card'), contentCss = declarations(rules, '.guide-content')
  assert.equal(cardCss.get('display')?.value, 'flex')
  assert.equal(cardCss.get('flex-direction')?.value, 'column')
  assert.equal(cardCss.get('max-height')?.value, 'calc(100dvh - 24px)')
  assert.equal(contentCss.get('min-height')?.value, '0')
  assert.equal(contentCss.get('overflow-y')?.value, 'auto')
  assert.equal(declarations(rules, '.guide-card footer').get('flex-shrink')?.value, '0')
})

test('mobile toast insets cancel Element Plus centering and bound the rendered width', () => {
  const rules = mobileRules(read('styles/mobile-optimization.css'))
  const toast = declarations(rules, '.el-message')
  assert.equal(toast.get('transform')?.value, 'none', 'left:16px plus translateX(-50%) clips half of a mobile toast')
  assert.equal(toast.get('transform')?.important, true, 'The library centered entry/exit selectors must not reintroduce horizontal translation')
  assert.equal(toast.get('left')?.value, '16px')
  assert.equal(toast.get('right')?.value, '16px')
  assert.equal(toast.get('width')?.value, 'calc(100vw - 32px)')
  assert.equal(toast.get('max-width')?.value, 'calc(100vw - 32px)')
  assert.equal(toast.get('min-width')?.value, '0')
  const content = declarations(rules, '.el-message__content')
  assert.equal(content.get('min-width')?.value, '0')
  assert.equal(content.get('overflow-wrap')?.value, 'anywhere')
})
