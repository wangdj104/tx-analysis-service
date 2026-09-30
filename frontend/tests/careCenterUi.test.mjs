import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope, nextTick } from 'vue'
import { parse, compileStyle } from '@vue/compiler-sfc'
import postcss from 'postcss'

const source = file => fs.readFileSync(new URL('../src/' + file, import.meta.url), 'utf8')
function careView(t) {
  const script = parse(source('views/CareCenter.vue')).descriptor.scriptSetup.content.replace(/^import.*$/gm, '')
  const patientId = ref(11)
  const scope = effectScope()
  const bindings = { ref, reactive, computed, watch, onMounted() {}, onUnmounted() {},
    localStorage: { getItem() { return null }, setItem() {} },
    document: { body: { classList: { toggle() {} } } },
    useCurrentPatient: () => ({ currentPatientId: patientId, setPatientList() {} }),
    api: { getCareHome: async () => ({ data: [] }), getCareContext: async () => ({ data: {} }) }
  }
  const view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {home, context, selectedPatientName, pendingMedicationCount, entryVisible, quickVisible}')(...Object.values(bindings)))
  t.after(() => scope.stop())
  return { ...view, patientId }
}

test('care home uses one workspace patient dropdown and exposes family-card selection', () => {
  const template = parse(source('views/CareCenter.vue')).descriptor.template.content
  assert.doesNotMatch(template, /<el-select\b[^>]*v-model="patientId"/)
  assert.match(template, /:aria-pressed="p.patient.id===patientId"/)
  assert.match(template, /selectedPatientName/)
  assert.match(source('App.vue'), /:allow-all="route.path !== '\/care'"/)
  assert.match(source('components/PatientSwitcher.vue'), /v-if="patients.length && allowAll"/)
})

test('current care identity follows the selected patient, never stale context', async t => {
  const view = careView(t)
  view.home.value = [{ patient: { id: 11, name: 'Example A' } }, { patient: { id: 22, name: 'Example B' } }]
  view.context.value = { patient: { id: 11, name: 'Example A' } }
  assert.equal(view.selectedPatientName.value, 'Example A')
  view.quickVisible.value = true
  view.patientId.value = 22
  assert.equal(view.selectedPatientName.value, 'Example B')
  await nextTick()
  assert.equal(view.quickVisible.value, false)
  view.patientId.value = null
  assert.equal(view.selectedPatientName.value, '')
})

test('medication attention count excludes completed, skipped and cancelled doses', t => {
  const view = careView(t)
  view.context.value = { intakes: ['PENDING', 'MISSED', 'SNOOZED', 'TAKEN', 'SKIPPED', 'CANCELLED'].map((status, id) => ({ id, status })) }
  assert.equal(view.pendingMedicationCount.value, 3)
  view.context.value.intakes[0].status = 'TAKEN'
  assert.equal(view.pendingMedicationCount.value, 2)
  view.context.value = {}
  assert.equal(view.pendingMedicationCount.value, 0)
})

test('mobile navigation exposes the current page and care home is a named section', () => {
  const app = parse(source('App.vue')).descriptor.template.content
  const bottomNav = app.match(/<nav v-if="isMobile"[\s\S]*?<\/nav>/)[0]
  assert.match(bottomNav, /:aria-current="isItemActive\(item\) \? 'page' : undefined"/)
  assert.match(parse(source('views/CareCenter.vue')).descriptor.template.content, /<section class="care-center" aria-labelledby="care-heading"/)
})

// Compare the real compiled scoped rules with global senior overrides. Global
// accessibility CSS loads first, so a tie would lose after CareCenter lazy-loads.
function specificity(selector) {
  const withoutAttributes = selector.replace(/\[[^\]]+\]/g, '')
  return [(selector.match(/#[\w-]+/g)||[]).length,
    (selector.match(/\.[\w-]+|\[[^\]]+\]/g)||[]).length,
    (withoutAttributes.match(/(?:^|[\s>+~])([a-z][\w-]*)/gi)||[]).length]
}
function weight(selector) { const [ids, classes, elements] = specificity(selector); return ids*10000+classes*100+elements }
test('large-text overrides beat lazy-loaded scoped font sizes', () => {
  const descriptor = parse(source('views/CareCenter.vue')).descriptor
  const scoped = compileStyle({ source: descriptor.styles[0].content, id: 'data-v-care', scoped: true }).code
  const rules = css => { const out=[]; postcss.parse(css).walkRules(rule => {
    const size=rule.nodes.find(node=>node.prop==='font-size')?.value
    if(size) for(const selector of rule.selectors) out.push({selector,size,weight:weight(selector)})
  }); return out }
  const regular=rules(scoped), senior=rules(source('styles/care-accessibility.css'))
  for(const [target,min] of [['.el-tabs__item',18],['small',17],['strong',20],['.el-radio-button__inner',18],['summary',18],['.care-count',17]]) {
    const endsAtTarget = rule => rule.selector.replace(/\[data-v-care\]/g,'').endsWith(target)
    const normal=regular.filter(endsAtTarget)
    const overrides=senior.filter(rule=>rule.selector.includes('.care-senior')&&endsAtTarget(rule)&&parseFloat(rule.size)>=min)
    assert.ok(overrides.length, 'Missing large-text font for '+target)
    assert.ok(Math.max(...overrides.map(rule=>rule.weight))>Math.max(...normal.map(rule=>rule.weight)), 'Large-text font loses the cascade for '+target)
  }
})

test('large text keeps the existing tab enlargement outside the care home', () => {
  let fontSize
  postcss.parse(source('styles/care-accessibility.css')).walkRules(rule => {
    if (rule.selectors.includes('body.care-senior .el-tabs__item')) fontSize = rule.nodes.find(node => node.prop === 'font-size')?.value
  })
  assert.equal(fontSize, '18px')
})
