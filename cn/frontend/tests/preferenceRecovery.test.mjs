import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { ref, reactive, computed, watch, nextTick, effectScope } from 'vue'
import { useTableColumns } from '../src/composables/useTableColumns.js'

const columns = [{ key: 'month' }, { key: 'value' }, { key: 'updatedAt', default: false }]
function table(t, raw, { readError = false, writeError = false } = {}) {
  const storage = new Map(raw == null ? [] : [['table-cols:synthetic', raw]])
  const previous = globalThis.localStorage
  globalThis.localStorage = {
    getItem: key => { if (readError) throw new Error('Synthetic read denied'); return storage.get(key) ?? null },
    setItem: (key, value) => { if (writeError) throw new Error('Synthetic quota'); storage.set(key, value) }
  }
  const scope = effectScope(), view = scope.run(() => useTableColumns('synthetic', columns))
  t.after(() => { scope.stop(); globalThis.localStorage = previous })
  return { ...view, storage }
}
// Returning the filtered array directly instead of checking it would hide every data column.
for (const raw of ['["retired-column"]', '[null,42,{}]']) {
  test(`stale column preference ${raw} recovers the defaults`, t => {
    assert.deepEqual(table(t, raw).visibleKeys.value, ['month', 'value'])
  })
}
for (const [raw, expected] of [[null, ['month', 'value']], ['{bad', ['month', 'value']], ['{}', ['month', 'value']], ['[]', ['month', 'value']], ['["updatedAt"]', ['updatedAt']], ['["retired-column","updatedAt"]', ['updatedAt']]]) {
  test(`column preference ${raw} preserves its established fallback or valid choice`, t => {
    assert.deepEqual(table(t, raw).visibleKeys.value, expected)
  })
}
test('explicit live empty columns stay empty until reset; reset persists defaults', async t => {
  const view = table(t, null)
  view.visibleKeys.value = []; await nextTick()
  assert.deepEqual(view.visibleKeys.value, [])
  assert.equal(view.storage.get('table-cols:synthetic'), '[]')
  view.resetColumns(); await nextTick()
  assert.deepEqual(JSON.parse(view.storage.get('table-cols:synthetic')), ['month', 'value'])
})
test('unavailable column storage preserves working in-memory selection', async t => {
  const view = table(t, null, { readError: true, writeError: true })
  assert.deepEqual(view.visibleKeys.value, ['month', 'value'])
  view.visibleKeys.value = ['updatedAt']; await nextTick()
  assert.equal(view.isVisible('updatedAt'), true)
})

function care(t, { saved = {}, readKey, failWrites = false, patientId = null, api = {} } = {}) {
  const source = fs.readFileSync(new URL('../src/views/CareCenter.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const classes = new Set(), storage = new Map(Object.entries(saved)), scope = effectScope()
  const control = { failWrites }, messages = []
  const deps = { ref, reactive, computed, watch, onMounted() {}, onUnmounted() {},
    useCurrentPatient: () => ({ currentPatientId: ref(patientId), setPatientList() {} }),
    localStorage: { getItem: key => { if (key === readKey || readKey === '*') throw new Error('Synthetic read denied'); return storage.get(key) ?? null }, setItem: (key, value) => { if (control.failWrites) throw new Error('Synthetic quota'); storage.set(key, String(value)) } },
    document: { body: { classList: { toggle: (name, active) => active ? classes.add(name) : classes.delete(name) } } },
    api, localDateKey: () => '2026-10-02', ElMessage: { success: value => messages.push(value) }, ElMessageBox: {}
  }
  t.after(() => scope.stop())
  const view = scope.run(() => new Function(...Object.keys(deps), script + '\nreturn { mode, senior, quick, quickVisible, submitQuick, busy }')(...Object.values(deps)))
  return { ...view, classes, storage, control, messages }
}
// A preference storage failure must not throw out of setup or prevent applying the live font size.
for (const readKey of ['care-mode', 'care-senior', 'care-measure-period', '*']) {
  test(`care preference read failure for ${readKey} falls back without aborting setup`, t => {
    const view = care(t, { readKey })
    assert.equal(view.mode.value, 'PATIENT'); assert.equal(view.senior.value, false)
    assert.equal(view.quick.measurePeriod, 'Fasting')
  })
}
test('large-text initialization applies the saved choice even when writes fail', t => {
  const view = care(t, { saved: { 'care-senior': 'true' }, failWrites: true })
  assert.equal(view.senior.value, true); assert.equal(view.classes.has('care-senior'), true)
})
test('large-text and role switches still work when storage becomes unavailable', async t => {
  const view = care(t); view.control.failWrites = true
  view.senior.value = true; view.mode.value = 'FAMILY'; await nextTick()
  assert.equal(view.classes.has('care-senior'), true); assert.equal(view.mode.value, 'FAMILY')
  view.senior.value = false; await nextTick(); assert.equal(view.classes.has('care-senior'), false)
})
test('normal large-text toggles persist, and invalid saved booleans remain false', async t => {
  const view = care(t, { saved: { 'care-senior': 'invalid' } })
  assert.equal(view.senior.value, false)
  view.senior.value = true; await nextTick()
  assert.equal(view.storage.get('care-senior'), 'true'); assert.equal(view.classes.has('care-senior'), true)
})
test('app font initialization catches only its preference read and falls back', () => {
  const source = fs.readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const script = source.split('const appPatientList = ref([]);')[1].split("provide('userMenus'")[0]
  assert.ok(script.includes('care-senior'))
  for (const [saved, expected] of [['true', true], ['invalid', false], [null, false], ['throw', false]]) {
    const calls = []
    const document = { body: { classList: { toggle: (...args) => calls.push(args) } } }
    const localStorage = { getItem: key => { assert.equal(key, 'care-senior'); if (saved === 'throw') throw new Error('Synthetic read denied'); return saved } }
    assert.doesNotThrow(() => new Function('document', 'localStorage', script)(document, localStorage))
    assert.deepEqual(calls, [['care-senior', expected]])
  }
})

// A failed convenience-preference write after server success must not leave a retryable saved draft.
test('successful quick save finishes once even when remembering the measurement period fails', async t => {
  let finish, saves = 0, homeReads = 0, contextReads = 0, payload
  const pending = new Promise(resolve => { finish = resolve })
  const view = care(t, { patientId: 1, failWrites: true, api: {
    quickVitals: data => { saves++; payload = data; return pending },
    getCareHome: async () => { homeReads++; return { data: [] } },
    getCareContext: async () => { contextReads++; return { data: {} } }
  } })
  view.quickVisible.value = true
  Object.assign(view.quick, { systolicBp: 120, diastolicBp: 80, measurePeriod: 'After Meal', remark: 'Synthetic only' })
  const first = view.submitQuick(), duplicate = view.submitQuick()
  assert.equal(saves, 1); finish({}); await Promise.all([first, duplicate])
  assert.deepEqual(payload, { patientId: 1, systolicBp: 120, diastolicBp: 80, bloodGlucose: undefined, measurePeriod: 'After Meal', remark: 'Synthetic only' })
  assert.equal(view.quickVisible.value, false); assert.equal(view.quick.systolicBp, undefined)
  assert.equal(view.quick.remark, ''); assert.equal(view.quick.measurePeriod, 'After Meal')
  assert.equal(view.messages.length, 1); assert.equal(homeReads, 1); assert.equal(contextReads, 1)
  assert.equal(view.busy.value, false); assert.equal(saves, 1)
})
