import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as Vue from 'vue'
import { parse } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-dom'
import postcss from 'postcss'
import { fixture, findNode, textOf, flush } from './helpers/carePlanDoctorHarness.mjs'

const source = path => fs.readFileSync(new URL('../src/' + path, import.meta.url), 'utf8')
const walk = node => [node, ...(node.children || []).flatMap(walk)]
const action = { id: 31, ordinal: 1, patientId: 1, planId: 17, instruction: 'Synthetic daily action', assignedUserId: 51, dueAt: '2026-10-04T12:00:00Z', status: 'OPEN', overdue: true, allowedActions: ['SUBMIT_RECEIPT', 'REQUEST_HELP'], allowedEntryModes: ['ASSISTED'], evidence: [{ title: 'Synthetic reference', sourceId: 9, detailLink: '/medical-record/9' }], events: [{ id: 81, eventType: 'HELP_REQUESTED', note: 'Synthetic activity note', actorName: 'Synthetic caregiver', actorRole: 'family', recordedAt: '2026-10-03T12:00:00Z' }] }
const plan = { id: 17, patientId: 1, version: 4, revisionNo: 1, title: 'Synthetic plan', instructions: 'Keep the care details', actions: [action, { ...action, id: 32, instruction: 'Synthetic reviewed action', status: 'CONFIRMED', allowedActions: [] }] }
const transport = async config => ({ data: config.url.endsWith('/capabilities') ? { enabled: true } : config.url === '/care-plans' ? { items: [plan] } : plan })
const styles = file => postcss.parse(parse(source(file)).descriptor.styles[0].content)
const declarations = (file, selector) => { const result = {}; styles(file).walkRules(rule => { if (rule.selectors.includes(selector)) rule.walkDecls(d => { result[d.prop] = d.value }) }); return result }

test('today action renders its instruction and status only once while preserving detail and history', async t => {
  globalThis.document = { activeElement: null }
  const v = await fixture(t, 'PlanTaskList', { patientId: 1 }, transport)
  const task = findNode(v.root, 'plan-action-31')
  const text = textOf(task)
  assert.equal(text.split(action.instruction).length - 1, 1, 'instruction is not repeated in the open body')
  assert.equal(text.split(v.vm.statusLabel('OPEN')).length - 1, 1, 'status is not repeated in the open body')
  assert.equal(task.props.open, true)
  assert.equal(findNode(v.root, 'plan-action-32').props.open, false)
  assert.match(text, /#51/)
  assert.match(text, /Synthetic reference/)
  assert.match(text, /Synthetic activity note/)
  const history = walk(task).find(n => n.props?.class === 'action-event-history')
  assert.ok(history)
  assert.ok(!history.props.open, 'activity history starts folded')
  assert.ok(findNode(task, 'record-31'))
  assert.ok(findNode(task, 'help-31'))
})

test('action body adds no third border and controls keep readable, full-size targets', () => {
  const file = 'components/care-plan/PlanTaskList.vue'
  assert.equal(declarations(file, '.action-card').border, '0')
  assert.equal(declarations(file, '.action-card').padding, '0')
  assert.equal(declarations(file, 'button')['min-height'], '44px')
  assert.equal(declarations(file, '.action-group>summary')['min-height'], '44px')
})

test('real medication template gives each existing pending status distinct text and a hidden SVG cue', () => {
  const sfc = parse(source('views/CareCenter.vue')).descriptor
  const template = sfc.template.content.match(/<article v-for="i in pendingIntakes"[\s\S]*?<\/article>/)[0]
  const render = new Function('Vue', compile(template, { mode: 'function', isCustomElement: tag => tag.startsWith('el-') }).code)(Vue)
  const statusText = new Function(sfc.scriptSetup.content.match(/^const statusText=.*$/m)[0] + '; return statusText')()
  const statuses = ['PENDING', 'MISSED', 'SNOOZED']
  const root = render({ pendingIntakes: statuses.map((status, id) => ({ id, status, drugName: 'Synthetic dose', dosage: 'Synthetic amount' })), lastActor: () => '', statusText, mode: 'PATIENT', busy: false, openIntake() {}, intakeAction() {}, skipIntake() {} }, [])
  const nodes = n => n && typeof n === 'object' ? [n, ...(Array.isArray(n.children) ? n.children.flatMap(nodes) : [])] : []
  const rendered = nodes(root), badges = rendered.filter(n => n.props?.['data-medication-status'])
  assert.deepEqual(badges.map(n => n.props['data-medication-status']), statuses)
  const icons = []
  for (const badge of badges) {
    const status = badge.props['data-medication-status']
    const icon = nodes(badge).find(n => n.type === 'svg')
    assert.ok(icon, status + ' has an icon')
    assert.equal(icon.props['aria-hidden'], 'true')
    assert.ok(nodes(badge).some(n => n.children === statusText(status)), status + ' retains its unchanged status text')
    icons.push(JSON.stringify(nodes(icon).map(n => [n.type, n.props])))
  }
  assert.equal(new Set(icons).size, 3, 'statuses are recognizable without color')
})

function contrast(a, b) {
  const luminance = hex => { const rgb = hex.slice(1).match(/../g).map(value => parseInt(value, 16) / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4); return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722 }
  const values = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (values[0] + .05) / (values[1] + .05)
}
test('medication state colors use semantic theme pairs with at least 4.5:1 text contrast in all six themes', () => {
  const themeCss = postcss.parse(source('styles/health-appearance.css'))
  for (const theme of ['platform', 'white', 'blue', 'mint', 'sand', 'dark']) {
    const tokens = {}
    themeCss.walkRules(rule => { if (rule.parent.type === 'root' && [':root', `:root[data-health-theme="${theme}"]`].includes(rule.selector)) rule.walkDecls(d => { tokens[d.prop] = d.value }) })
    for (const [status, semantic] of [['PENDING', 'info'], ['MISSED', 'danger'], ['SNOOZED', 'warning']]) {
      const rule = declarations('views/CareCenter.vue', `.medication-status[data-medication-status="${status}"]`)
      assert.equal(rule.color, `var(--${semantic})`)
      assert.equal(rule.background, `var(--${semantic}-soft)`)
      assert.ok(contrast(tokens[`--${semantic}`], tokens[`--${semantic}-soft`]) >= 4.5, `${theme}/${status} text contrast`)
    }
  }
})

test('receipt has one primary submit, explicit busy feedback and reference-scoped light removal', async t => {
  globalThis.document = { activeElement: null }
  const v = await fixture(t, 'ReceiptDialog', { action, planVersion: 4 }, transport)
  v.vm.form.evidence.push({ sourceType: 'MEASUREMENT', sourceId: 9 }, { sourceType: 'MEDICAL_RECORD', sourceId: 10 })
  await flush()
  const submit = findNode(v.root, 'submit-receipt')
  assert.equal(submit.props.class, 'receipt-submit')
  assert.equal(walk(v.root).filter(n => n.props?.class === 'receipt-submit').length, 1)
  assert.equal(findNode(v.root, 'close-receipt').props.class, 'receipt-close')
  const rows = walk(v.root).filter(n => n.props?.class === 'evidence-row')
  for (const row of rows) {
    const remove = walk(row).find(n => n.tag === 'button')
    const label = walk(row).find(n => n.tag === 'p')
    assert.equal(remove.props.class, 'remove-reference')
    assert.equal(remove.props['aria-describedby'], label.props.id)
    assert.ok(label.props.id)
  }
  walk(rows[0]).find(n => n.tag === 'button').props.onClick()
  await flush()
  assert.deepEqual(v.vm.form.evidence, [{ sourceType: 'MEDICAL_RECORD', sourceId: 10 }])
  v.vm.state.commandPhase = 'pending'
  await flush()
  assert.equal(submit.props.disabled, true)
  assert.equal(submit.props['aria-busy'], true)
  assert.match(textOf(submit), /Submitting|正在提交/)
})

test('receipt primary, secondary, remove and disabled states have distinct scoped styling', () => {
  const file = 'components/care-plan/ReceiptDialog.vue'
  assert.equal(declarations(file, '.receipt-submit').background, 'var(--care-700)')
  assert.equal(declarations(file, '.receipt-submit').color, 'var(--on-accent)')
  assert.equal(declarations(file, '.remove-reference').background, 'transparent')
  assert.equal(declarations(file, '.remove-reference')['border-color'], 'transparent')
  assert.equal(declarations(file, 'button:disabled').opacity, '1')
  assert.equal(declarations(file, 'button')['min-height'], '44px')
})
