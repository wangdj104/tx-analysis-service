import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { ref, reactive, computed, watch, nextTick, effectScope } from 'vue'
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const ok = data => ({ code: 200, data })
function setup(t, listRecords) {
  const source = fs.readFileSync(new URL('../src/views/MedicalRecordManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const currentPatientId = ref(1), scope = effectScope(), unmounts = [], errors = []
  const deps = { ref, reactive, computed, watch, inject: (_key, fallback) => fallback,
    onMounted() {}, onUnmounted: fn => unmounts.push(fn), use() {}, CanvasRenderer: {}, EchartsLineChart: {}, GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, TitleComponent: {},
    useCurrentPatient: () => ({ currentPatientId }), useTableColumns: () => ({}), useMobile: () => ({ isMobile: ref(false) }), useRoute: () => ({ query: {}, path: '/medical-record' }), useRouter: () => ({ push: async () => {} }),
    localDateKey: () => '2026-10-02', readPermissionCache: () => ({}), canAccessWorkspace: () => true, dedupeRecognizedItems: value => value,
    isImageFile: () => false, compressImageFile: async value => value, formatFileSize: String,
    ElMessage: { error: value => errors.push(value), info() {}, warning() {} }, window: { removeEventListener() {} }, URL: { revokeObjectURL() {} },
    api: { listRecords, getAllItemNames: async () => ok([]) }
  }
  const view = scope.run(() => new Function(...Object.keys(deps), script + '\nreturn { filterForm, records, displayRecords, loading, loadRecords }')(...Object.values(deps)))
  const leave = () => { unmounts.splice(0).forEach(fn => fn()); scope.stop() }
  t.after(leave)
  return { ...view, currentPatientId, errors, leave }
}
// Without request identity, the older response overwrites the latest query's table.
test('newest medical-list query owns the visible rows', async t => {
  const a = deferred(), b = deferred(); let count = 0
  const view = setup(t, () => ++count === 1 ? a.promise : b.promise)
  view.filterForm.recordType = 'BLOOD'; const first = view.loadRecords()
  view.filterForm.recordType = 'URINE'; const second = view.loadRecords()
  b.resolve(ok([{ id: 2, recordType: 'URINE' }])); await second
  a.resolve(ok([{ id: 1, recordType: 'BLOOD' }])); await first
  assert.deepEqual(view.displayRecords.value, [{ id: 2, recordType: 'URINE' }])
})
test('an older request cannot clear the newest request loading flag', async t => {
  const a = deferred(), b = deferred(); let count = 0
  const view = setup(t, () => ++count === 1 ? a.promise : b.promise)
  const first = view.loadRecords(), second = view.loadRecords()
  a.resolve(ok([])); await first; assert.equal(view.loading.value, true)
  b.resolve(ok([])); await second; assert.equal(view.loading.value, false)
})
test('obsolete errors do not show a failure message for the current query', async t => {
  const a = deferred(), b = deferred(); let count = 0
  const view = setup(t, () => ++count === 1 ? a.promise : b.promise)
  const first = view.loadRecords(), second = view.loadRecords()
  b.resolve(ok([{ id: 2 }])); await second
  a.reject(new Error('Old synthetic failure')); await first
  assert.deepEqual(view.errors, []); assert.deepEqual(view.records.value, [{ id: 2 }])
})
test('request parameters are a stable snapshot and changed filters reject pending results', async t => {
  const a = deferred(); let sent
  const view = setup(t, params => { sent = params; return a.promise })
  view.filterForm.recordType = 'BLOOD'; const pending = view.loadRecords()
  view.filterForm.recordType = 'URINE'
  assert.notEqual(sent, view.filterForm); assert.equal(sent.recordType, 'BLOOD')
  a.resolve(ok([{ id: 1, recordType: 'BLOOD' }])); await pending
  assert.deepEqual(view.records.value, []); assert.equal(view.loading.value, false)
})
test('unmount prevents late rows and messages from updating the departed view', async t => {
  const pending = deferred(), view = setup(t, () => pending.promise)
  const loading = view.loadRecords(); view.leave()
  pending.resolve(ok([{ id: 1 }])); await loading
  assert.deepEqual(view.records.value, []); assert.deepEqual(view.errors, [])
})
test('all-patient list filters remain independent from global patient selection', async t => {
  const params = [], view = setup(t, async value => { params.push(value); return ok([{ id: 7 }]) })
  view.filterForm.patientId = null; view.currentPatientId.value = 2; await nextTick()
  assert.equal(view.filterForm.patientId, null); assert.equal(params.length, 0)
  await view.loadRecords(); assert.equal(params[0].patientId, null)
  assert.deepEqual(view.records.value, [{ id: 7 }])
})
test('a current request error reports and finishes loading', async t => {
  const view = setup(t, async () => { throw new Error('Synthetic current failure') })
  await view.loadRecords(); assert.equal(view.errors.length, 1); assert.equal(view.loading.value, false)
})
