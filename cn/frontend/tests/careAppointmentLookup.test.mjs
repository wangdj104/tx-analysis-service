import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope } from 'vue'
import { parse } from '@vue/compiler-sfc'

const source = fs.readFileSync(new URL('../src/views/CareCenter.vue', import.meta.url), 'utf8')
const descriptor = parse(source).descriptor
const paragraph = descriptor.template.content.match(/<p v-if="q\.details\.appointmentId">[^<]*?\{\{([\s\S]*?)\}\}<\/p>/)
assert.ok(paragraph, 'Keep the existing truthy appointment-link visibility condition')
const lookup = new Function('appointments', 'appointmentById', 'q', `return ${paragraph[1]}`)
const deletedLabel = import.meta.url.includes('/cn/frontend/') ? '预约已删除' : 'Appointment deleted'

function setup(t) {
  const scope = effectScope()
  const bindings = { ref, reactive, computed, watch, onMounted() {}, onUnmounted() {},
    localStorage: { getItem() { return null }, setItem() {} },
    document: { body: { classList: { toggle() {} } } },
    useCurrentPatient: () => ({ currentPatientId: ref(1), setPatientList() {} }) }
  const script = descriptor.scriptSetup.content.replace(/^import.*$/gm, '')
  const view = scope.run(() => new Function(...Object.keys(bindings), script + `
    return { context, appointments, questions,
      appointmentById: typeof appointmentById === 'undefined' ? null : appointmentById }
  `)(...Object.values(bindings)))
  t.after(() => scope.stop())
  const label = q => lookup(view.appointments.value, view.appointmentById?.value, q)
  const labels = computed(() => view.questions.value.filter(q => q.details.appointmentId).map(label))
  return { ...view, label, labels }
}
const appointment = (id, title = `Synthetic appointment ${String(id)}`) => ({ kind: 'APPOINTMENT', id, title })
const question = appointmentId => ({ kind: 'QUESTION', details: { appointmentId } })

// Removing the computed index or restoring the per-question search must fail
// these operation-count tests; no elapsed-time threshold depends on host load.
for (const n of [100, 1000]) {
  test(`appointment labels read each ID at most once for ${n} questions, then reuse the index`, t => {
    const view = setup(t)
    let idReads = 0
    view.context.value = { items: [
      ...Array.from({ length: n }, (_, i) => ({ kind: 'APPOINTMENT', get id() { idReads++; return i + 1 }, title: `Synthetic ${i + 1}` })),
      ...Array.from({ length: n }, (_, i) => question(i + 1))
    ] }
    const labels = view.questions.value.map(view.label)
    assert.equal(labels.length, n)
    assert.equal(labels[n - 1], `Synthetic ${n}`)
    assert.ok(idReads <= n, `Expected at most ${n} appointment ID reads, got ${idReads}`)
    idReads = 0
    assert.deepEqual(view.questions.value.map(view.label), labels)
    assert.equal(idReads, 0, 'Unchanged label reads must not rescan appointment IDs')
  })
}

test('the computed index keeps the first strict match, including falsy IDs, without a NaN match', t => {
  const view = setup(t)
  const objectId = {}, symbolId = Symbol('Synthetic ID')
  const ids = [undefined, null, false, 0, -0, '', '0', 1, '1', 1n, NaN, objectId, symbolId]
  view.context.value = { items: ids.flatMap((id, i) => [appointment(id, `First ${i}`), appointment(id, `Later ${i}`)]) }
  assert.ok(view.appointmentById, 'The computed appointment lookup index is missing')
  const index = view.appointmentById.value
  for (const row of view.appointments.value) {
    const expected = view.appointments.value.find(a => a.id === row.id)
    assert.equal(index.get(row.id), expected, `Index must preserve strict first-match semantics for ${String(row.id)}`)
  }
  assert.equal(index.get(NaN), undefined, 'Map SameValueZero must not introduce a NaN match')
  assert.equal(index.get({}), undefined, 'Object IDs must retain identity semantics')
  assert.equal(index.get(Symbol('Synthetic ID')), undefined, 'Symbols must retain identity semantics')
  assert.equal(view.appointments.value.length, ids.length * 2, 'Indexing must not deduplicate the source data')
})

test('production label expression preserves strict IDs, missing links, and title fallback', t => {
  const view = setup(t)
  const objectId = {}, symbolId = Symbol('Synthetic ID')
  const ids = [undefined, null, false, true, 0, -0, '', '0', 1, '1', 1n, NaN, objectId, symbolId]
  view.context.value = { items: ids.flatMap((id, i) => [appointment(id, `First ${i}`), appointment(id, `Later ${i}`)]) }
  for (const row of view.appointments.value) {
    const q = question(row.id)
    const expected = view.appointments.value.find(a => a.id === q.details.appointmentId)?.title || deletedLabel
    assert.equal(view.label(q), expected)
  }
  for (const id of [999, '999', {}, Symbol('Synthetic ID')]) assert.equal(view.label(question(id)), deletedLabel)
  assert.equal(view.label({ details: {} }), 'First 0', 'An absent ID retains the original undefined-ID lookup')
  view.context.value = { items: [appointment(1, ''), appointment(1, 'Do not use the later title')] }
  assert.equal(view.label(question(1)), deletedLabel)
  for (const title of [undefined, null, false, 0, NaN]) {
    view.context.value.items[0].title = title
    assert.equal(view.label(question(1)), deletedLabel)
  }
})

test('falsy question links stay hidden while truthy string links remain visible', t => {
  const view = setup(t)
  view.context.value = { items: [appointment('0', 'String zero'), appointment('1', 'String one'),
    ...[undefined, null, false, 0, -0, '', NaN, '0', '1'].map(question)] }
  assert.deepEqual(view.labels.value, ['String zero', 'String one'])
})

test('labels follow context replacement, item-array replacement, and empty data', t => {
  const view = setup(t)
  const q = question(1)
  assert.equal(view.label(q), deletedLabel)
  view.context.value = { items: [appointment(1, 'First collection'), q] }
  assert.deepEqual(view.labels.value, ['First collection'])
  view.context.value = { items: [appointment(1, 'Replacement context'), q] }
  assert.deepEqual(view.labels.value, ['Replacement context'])
  view.context.value.items = [appointment(1, 'Replacement array'), q]
  assert.deepEqual(view.labels.value, ['Replacement array'])
  view.context.value = {}
  assert.equal(view.label(q), deletedLabel)
})

test('push, insertion, removal, and reordering preserve the first duplicate winner', t => {
  const view = setup(t)
  const q = question(1)
  view.context.value = { items: [q] }
  assert.equal(view.label(q), deletedLabel)
  view.context.value.items.push(appointment(1, 'Original'), appointment(1, 'Later'))
  assert.equal(view.label(q), 'Original')
  view.context.value.items.unshift(appointment(1, 'Inserted first'))
  assert.equal(view.label(q), 'Inserted first')
  view.context.value.items.shift()
  assert.equal(view.label(q), 'Original')
  view.context.value.items.reverse()
  assert.equal(view.label(q), 'Later')
  view.context.value.items.splice(0, 1)
  assert.equal(view.label(q), 'Original')
})

test('ID mutation invalidates the index and preserves first-match collision semantics', t => {
  const view = setup(t)
  view.context.value = { items: [appointment(1, 'Earlier'), appointment(2, 'Later'), question(2)] }
  assert.deepEqual(view.labels.value, ['Later'])
  view.context.value.items[0].id = 2
  assert.deepEqual(view.labels.value, ['Earlier'])
  assert.equal(view.label(question(1)), deletedLabel)
  view.context.value.items[0].id = NaN
  assert.deepEqual(view.labels.value, ['Later'])
  assert.equal(view.label(question(NaN)), deletedLabel)
})

test('title changes update reactive label consumers without rebuilding the ID index', t => {
  const view = setup(t)
  view.context.value = { items: [appointment(1, 'Before'), question(1)] }
  assert.deepEqual(view.labels.value, ['Before'])
  assert.ok(view.appointmentById, 'The computed appointment lookup index is missing')
  const index = view.appointmentById.value
  view.context.value.items[0].title = 'After'
  assert.deepEqual(view.labels.value, ['After'])
  assert.equal(view.appointmentById.value, index)
  view.context.value.items[0].title = ''
  assert.deepEqual(view.labels.value, [deletedLabel])
  assert.equal(view.appointmentById.value, index)
})

test('changing question links or appointment kind updates reactive labels', t => {
  const view = setup(t)
  view.context.value = { items: [appointment(1, 'One'), appointment(2, 'Two'), question(1)] }
  assert.deepEqual(view.labels.value, ['One'])
  view.context.value.items[2].details.appointmentId = 2
  assert.deepEqual(view.labels.value, ['Two'])
  view.context.value.items[1].kind = 'HANDOVER'
  assert.deepEqual(view.labels.value, [deletedLabel])
  view.context.value.items[1].kind = 'APPOINTMENT'
  assert.deepEqual(view.labels.value, ['Two'])
})

test('unchanged reads and unrelated details do not rebuild the computed index', t => {
  const view = setup(t)
  view.context.value = { items: [appointment(1, 'One'), question(1)] }
  assert.ok(view.appointmentById, 'The computed appointment lookup index is missing')
  const index = view.appointmentById.value
  for (let i = 0; i < 20; i++) assert.equal(view.appointmentById.value, index)
  view.context.value.items[1].details.description = 'Synthetic edited question'
  view.context.value.items[0].status = 'DONE'
  assert.equal(view.appointmentById.value, index)
  assert.deepEqual(view.labels.value, ['One'])
})
