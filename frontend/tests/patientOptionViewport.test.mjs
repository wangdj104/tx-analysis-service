import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileStyle } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'
import postcss from 'postcss'

const nodes = node => [node, ...(node.children || []).flatMap(nodes)]
const attr = (node, name) => node.props?.find(prop => prop.name === name)?.value?.content
const source = edition => readFileSync(new URL(`${edition === 'en' ? '../' : '../../cn/frontend/'}src/components/PatientSwitcher.vue`, import.meta.url), 'utf8')
function component(edition) {
  const { descriptor } = parse(source(edition))
  const compiled = compileStyle({ source: descriptor.styles.map(style => style.content).join('\n'), id: 'data-v-patient-test', scoped: true })
  assert.deepEqual(compiled.errors, [])
  return { tree: nodes(parseTemplate(descriptor.template.content)), css: postcss.parse(compiled.code) }
}
function declarations(css, selector) {
  const values = {}
  css.walkRules(rule => { if (rule.selector === selector) rule.walkDecls(decl => { values[decl.prop] = decl.value }) })
  return values
}

// These are compiled-style/template contracts, not substitutes for the real
// browser matrix. Removing the scoped viewport cap or restoring nowrap must
// fail here, while the browser still checks actual text and clipping geometry.
for (const edition of ['en', 'cn']) {
  test(`${edition} teleported patient options have a viewport-bounded dropdown`, () => {
    const { tree, css } = component(edition)
    const select = tree.find(node => node.tag === 'el-select')
    assert.equal(attr(select, 'popper-class'), 'patient-switcher-popper')
    const popup = declarations(css, '.el-popper.patient-switcher-popper')
    assert.equal(popup.width, 'min(38rem, calc(100vw - 24px))', 'long labels cannot determine an unbounded intrinsic popup width')
    const dropdown = declarations(css, '.el-select-dropdown.patient-switcher-popper')
    assert.equal(dropdown.width, '100%')
    assert.equal(dropdown['min-width'], '0', 'the teleported dropdown can shrink within its constrained popup')
    const minimums = []
    css.walkRules(rule => { if (rule.selector === '.el-select-dropdown.patient-switcher-popper') rule.walkDecls('min-width', decl => minimums.push(decl)) })
    assert.equal(minimums.length, 1)
    assert.equal(minimums[0].important, true, 'the width reset must beat Element Plus inline min-width')
  })

  test(`${edition} long names wrap in growing rows while record IDs remain unbroken`, () => {
    const { tree, css } = component(edition)
    const options = tree.filter(node => node.tag === 'el-option')
    assert.ok(options.every(option => attr(option, 'class') === 'patient-switcher-option'))
    const row = declarations(css, '.patient-switcher-option[data-v-patient-test]')
    assert.equal(row.height, 'auto')
    assert.equal(row['min-height'], '44px')
    assert.equal(row['white-space'], 'normal')
    const name = declarations(css, '.patient-option__name[data-v-patient-test]')
    assert.equal(name['white-space'], 'normal')
    assert.equal(name['overflow-wrap'], 'anywhere')
    assert.notEqual(name.overflow, 'hidden', 'the full name remains available without hover')
    const record = declarations(css, '.patient-option__record[data-v-patient-test]')
    assert.equal(record['flex-shrink'], '0')
    assert.equal(record['white-space'], 'nowrap')
  })
}

test('shell browser saves patient geometry before strict assertions and checks the full name', () => {
  const browser = readFileSync(new URL('./uiSeventeenShell.browser.mjs', import.meta.url), 'utf8')
  const start = browser.indexOf('const optionIdentities = []')
  const end = browser.indexOf("await capture('patient-identities')", start)
  const checks = browser.slice(start, end)
  assert.ok(checks.indexOf('result.patientOptionBounds = []') >= 0)
  assert.ok(checks.indexOf('result.patientOptionBounds.push(') < checks.indexOf('assertReadableBox(identity,'))
  assert.match(checks, /assertReadableBox\(name, cell.viewport,/)
  assert.match(checks, /assertReadableBox\(identity, cell.viewport,/)
  assert.match(checks, /assert\.equal\(name.text, patientDisplayName/)
  assert.match(checks, /assert\.ok\(dropdown.left >= -1 && dropdown.right <= cell.viewport \+ 1/)
  assert.match(checks, /await fit\(\)/)
})
