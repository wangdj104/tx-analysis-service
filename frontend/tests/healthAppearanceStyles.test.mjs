import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import postcss from 'postcss'

const read = name => { const path = new URL('../src/styles/' + name, import.meta.url); return fs.existsSync(path) ? fs.readFileSync(path, 'utf8') : '' }
const css = ['app-theme.css', 'health-redesign.css', 'health-appearance.css'].map(read).join('\n')
function tokens(theme, print = false) {
  const properties = { '--platform-page-bg': '#d9e4ed' }
  postcss.parse(css).walkRules(rule => {
    if (rule.parent.type === 'atrule' && (!print || rule.parent.name !== 'media' || rule.parent.params !== 'print')) return
    if (!rule.selectors.some(selector => selector === ':root' || selector === ':root[data-health-theme]' || selector === `:root[data-health-theme="${theme}"]`)) return
    for (const declaration of rule.nodes) if (declaration.type === 'decl') properties[declaration.prop] = declaration.value
  })
  const resolve = (value, depth = 0) => {
    assert.ok(depth < 15, 'CSS variables must not have cycles')
    return value?.replace(/var\((--[\w-]+)(?:,\s*([^()]+))?\)/g, (_, name, fallback) => resolve(properties[name] ?? fallback, depth + 1))
  }
  return Object.fromEntries(Object.entries(properties).map(([key, value]) => [key, resolve(value)]))
}
function rgb(hex) {
  assert.match(hex || '', /^#[\da-f]{3}(?:[\da-f]{3})?$/i, `Expected a resolved color, received ${hex}`)
  const value = hex.length === 4 ? [...hex.slice(1)].map(c => c + c).join('') : hex.slice(1)
  return [0, 2, 4].map(index => parseInt(value.slice(index, index + 2), 16))
}
function luminance(hex) { return rgb(hex).map(x => { const s = x / 255; return s <= .04045 ? s / 12.92 : ((s + .055) / 1.055) ** 2.4 }).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0) }
function contrast(a, b) { const values = [luminance(a), luminance(b)].sort((x, y) => y - x); return (values[0] + .05) / (values[1] + .05) }

// Catches an omitted theme mapping, low-contrast secondary text or controls, and
// status colors accidentally tied to an aesthetic accent instead of clinical meaning.
for (const theme of ['platform', 'white', 'blue', 'mint', 'sand', 'dark']) {
  test(`${theme} keeps text, controls and clinical statuses readable`, () => {
    const values = tokens(theme)
    for (const background of ['--paper', '--surface-subtle', '--page']) for (const foreground of ['--ink-950', '--ink-700', '--ink-500', '--ink-400']) {
      assert.ok(contrast(values[foreground], values[background]) >= 4.5, `${theme} ${foreground} on ${background} must meet WCAG AA`)
    }
    assert.ok(contrast(values['--control-border'], values['--paper']) >= 3, `${theme} controls must be distinguishable`)
    assert.ok(contrast(values['--care-700'], values['--on-accent']) >= 4.5, `${theme} primary button text must meet WCAG AA`)
    for (const status of ['danger', 'warning', 'success']) assert.ok(contrast(values[`--${status}`], values[`--${status}-soft`]) >= 4.5, `${theme} ${status} labels must meet WCAG AA`)
    const [redR, redG, redB] = rgb(values['--danger']); assert.ok(redR > redG && redR > redB)
    const [greenR, greenG, greenB] = rgb(values['--success']); assert.ok(greenG > greenR && greenG > greenB)
    const [amberR, amberG, amberB] = rgb(values['--warning']); assert.ok(amberR > amberG && amberG > amberB)
  })
}

test('dark theme dims page, panels and native controls together', () => {
  const values = tokens('dark')
  assert.ok(luminance(values['--page']) < .025)
  assert.ok(luminance(values['--paper']) < .04)
  assert.equal(values['--el-bg-color'], values['--paper'])
  assert.equal(values['--el-fill-color-blank'], values['--paper'])
  assert.equal(values['color-scheme'], 'dark')
})

test('platform background remains effective only for the platform choice', () => {
  assert.equal(tokens('platform')['--page'], '#d9e4ed')
  for (const theme of ['white', 'blue', 'mint', 'sand', 'dark']) assert.notEqual(tokens(theme)['--page'], '#d9e4ed')
})

// Exercise the actual panel declarations: a late-loading scoped white panel
// must not override the dark palette while its inherited text turns white.
for (const [file, selector] of [
  ['views/CareCenter.vue', '.care-tabs'], ['views/CareCenter.vue', '.care-card'],
  ['views/MonitoringCenter.vue', '.monitor-panel'], ['views/MonitoringCenter.vue', '.signal-card'],
  ['views/ClinicalWorkbench.vue', '.workbench-panel'], ['views/ClinicalWorkbench.vue', '.emergency-card'],
  ['components/VitalsTrendPanel.vue', '.trend-panel']
]) {
  test(`${file} ${selector} respects a dark surface after lazy loading`, () => {
    const source = fs.readFileSync(new URL('../src/' + file, import.meta.url), 'utf8')
    const style = source.match(/<style[^>]*>([\s\S]*?)<\/style>/)[1]
    let background
    postcss.parse(style).walkRules(rule => {
      if (rule.parent.type === 'atrule' || !rule.selectors.includes(selector)) return
      for (const declaration of rule.nodes) if (['background', 'background-color'].includes(declaration.prop)) background = declaration.value
    })
    const values = tokens('dark')
    background = background?.replace(/var\((--[\w-]+)(?:,[^()]*)?\)/g, (_, key) => values[key])
    assert.ok(luminance(background) < .04, `${selector} must not leave a light panel in dark mode`)
    assert.ok(contrast(values['--ink-950'], background) >= 4.5)
  })
}

function declaration(file, selector, property) {
  let value
  const source = fs.readFileSync(new URL('../src/' + file, import.meta.url), 'utf8')
  const cssText = file.endsWith('.vue') ? source.match(/<style[^>]*>([\s\S]*?)<\/style>/)[1] : source
  postcss.parse(cssText).walkRules(rule => {
    if (rule.parent.type === 'atrule' || !rule.selectors.includes(selector)) return
    for (const item of rule.nodes) if (item.prop === property) value = item.value
  })
  return value
}
function resolveColor(value, theme) {
  return value?.replace(/var\((--[\w-]+)(?:,[^()]*)?\)/g, (_, key) => tokens(theme)[key])
}

test('platform page headings have a readable surface even with near-black branding', () => {
  for (const selector of [':root[data-health-theme="platform"] .care-center .care-header', ':root[data-health-theme="platform"] .monitoring-page .monitoring-head', ':root[data-health-theme="platform"] .clinical-workbench .workbench-hero', ':root[data-health-theme="platform"] .care-workspace .page-header']) {
    const background = resolveColor(declaration('styles/health-appearance.css', selector, 'background'), 'platform')
    assert.ok(contrast(tokens('platform')['--ink-950'], background) >= 4.5, 'An opaque title surface must insulate text from an arbitrary near-black platform page')
  }
})

test('monitoring normal indicators remain green in every appearance', () => {
  const background = declaration('views/MonitoringCenter.vue', '.signal-card--normal::before', 'background')
  for (const theme of ['white', 'blue', 'mint', 'sand', 'dark']) {
    const [r,g,b] = rgb(resolveColor(background, theme))
    assert.ok(g > r && g > b, `Normal status must remain green in ${theme}`)
  }
})

test('monitoring legend swatches match actual chart series when the accent changes', async () => {
  const { computed, ref } = await import('vue')
  const { adaptChartOption } = await import('../src/utils/appearanceChart.js')
  const script = fs.readFileSync(new URL('../src/views/MonitoringCenter.vue', import.meta.url), 'utf8')
  const expression = script.match(/const chartOption = computed\(\(\) => \{[\s\S]*?\n\}\)/)[0]
  const chart = new Function('computed', 'snapshot', 'glucoseInMmol', expression + '; return chartOption.value')(computed, ref({vitalTrend: []}), value => value)
  for (const theme of ['white','blue','mint','sand','dark']) {
    const series = adaptChartOption(chart, theme).series
    for (const [index, key] of ['systolic','diastolic','glucose'].entries()) {
      assert.equal(resolveColor(declaration('views/MonitoringCenter.vue', '.legend-' + key, 'background'), theme), series[index].lineStyle.color)
    }
  }
})

test('print summary is self-contained without app CSS variables', () => {
  const source = fs.readFileSync(new URL('../src/views/FamilyHealthManager.vue', import.meta.url), 'utf8')
  const printFunction = source.match(/function printSummary\(\)\{[^\n]+/)[0]
  let html = ''
  const popup = { document: { write(value) { html = value }, close() {} }, focus() {}, setTimeout() {} }
  new Function('window', 'summaryElement', printFunction + '; printSummary()')({ open: () => popup }, { value: { innerHTML: '<table><tr><td>Example</td></tr></table>' } })
  let border
  postcss.parse(html.match(/<style>([\s\S]*?)<\/style>/)[1]).walkRules(rule => {
    if (rule.selectors.includes('td')) border = rule.nodes.find(item => item.prop === 'border')?.value
  })
  const standaloneColor = border?.match(/#[\da-f]{3}(?:[\da-f]{3})?\b/i)?.[0]
  assert.ok(standaloneColor && contrast(standaloneColor, '#fff') > 1.5, 'Printed table borders need their own visible color')
})

test('solid semantic tags keep readable text in light and dark themes', () => {
  for (const theme of ['white', 'blue', 'sand', 'dark']) for (const status of ['success', 'warning', 'danger', 'info']) {
    const foreground = declaration('styles/health-appearance.css', `.el-tag.el-tag--dark.el-tag--${status}`, '--el-tag-text-color')
    assert.ok(contrast(resolveColor(foreground, theme), tokens(theme)['--' + status]) >= 4.5, `${theme} ${status} solid tag must stay readable`)
  }
})


test('printing a dark emergency card keeps chips and warning sections readable', () => {
  const values = tokens('dark', true)
  for (const background of ['--paper', '--care-50', '--danger-soft', '--warning-soft']) {
    assert.ok(luminance(values[background]) > .8, `Printed ${background} should use a light surface`)
    for (const foreground of ['--ink-950', '--ink-800', '--ink-700', '--ink-500', '--ink-400']) assert.ok(contrast(values[foreground], values[background]) >= 4.5)
  }
  assert.ok(contrast(values['--danger'], values['--danger-soft']) >= 4.5)
})

test('onboarding fine print and close control meet AA against the guide card', () => {
  for (const theme of ['platform', 'white', 'blue', 'mint', 'sand', 'dark']) {
    const background = resolveColor(declaration('components/OnboardingGuide.vue', '.guide-card', 'background'), theme)
    for (const selector of ['.guide-instruction small', '.guide-close']) {
      let foreground = resolveColor(declaration('components/OnboardingGuide.vue', selector, 'color'), theme)
      const mix = foreground.match(/^color-mix\(in srgb,\s*(#[\da-f]+)\s+(\d+)%,\s*transparent\)$/i)
      if (mix) {
        const alpha = Number(mix[2]) / 100, backdrop = rgb(background)
        foreground = '#' + rgb(mix[1]).map((value, index) => Math.round(value * alpha + backdrop[index] * (1 - alpha)).toString(16).padStart(2, '0')).join('')
      }
      assert.ok(contrast(foreground, background) >= 4.5, `${theme} ${selector} must remain readable`)
    }
  }
})
