import { captureAuthSession, isAuthSessionCurrent } from '../src/utils/authSession.js'
import { useFocusedCareSource } from '../src/composables/useFocusedCareSource.js'
import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { ref, reactive, computed, watch, nextTick, effectScope } from 'vue'
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const ok = data => ({ code: 200, data })
function setup(t, api = {}, initialRoute = {}) {
  const source = fs.readFileSync(new URL('../src/views/MedicalRecordManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const currentPatientId = ref(1), scope = effectScope(), unmounts = [], errors = [], infos = [], warnings = []
  const route = reactive({ query: {}, path: '/medical-record', ...initialRoute }), mounted=[]
  const deps = { captureAuthSession, isAuthSessionCurrent, useFocusedCareSource, ref, reactive, computed, watch, inject: (_key, fallback) => fallback,
    onMounted:fn=>mounted.push(fn), onUnmounted: fn => unmounts.push(fn), use() {}, CanvasRenderer: {}, EchartsLineChart: {}, GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, TitleComponent: {},
    useCurrentPatient: () => ({ currentPatientId }), useTableColumns: () => ({}), useMobile: () => ({ isMobile: ref(false) }), useRoute: () => route, useRouter: () => ({ push: async () => {} }),
    localDateKey: () => '2026-10-02', readPermissionCache: () => ({}), canAccessWorkspace: () => true, dedupeRecognizedItems: value => value,
    isImageFile: () => false, compressImageFile: async value => value, formatFileSize: String,
    ElMessage: { error: value => errors.push(value), info: value => infos.push(value), warning: value => warnings.push(value) }, window: { addEventListener() {}, removeEventListener() {} }, URL: { revokeObjectURL() {} },
    getPatientNames: async () => ok([]),
    api: { listRecords: async () => ok([]), getAllItemNames: async () => ok([]), ...api }
  }
  const view = scope.run(() => new Function(...Object.keys(deps), script + '\nreturn { filterForm, trendForm, trendData, trendLoading, loadTrend, allItemNames, loadItemNames, currentRecord, detailDialogVisible, viewDetail, sourceFocus, focusedMode, records, loadRecords }')(...Object.values(deps)))
  const leave = () => { unmounts.splice(0).forEach(fn => fn()); scope.stop() }
  t.after(leave)
  return { ...view, currentPatientId, errors, infos, warnings, leave, route, start(){mounted.forEach(fn=>fn())} }
}

// The local trend patient and indicator own a query independently of the global patient selector.
for (const field of ['itemName', 'patientId']) {
  test(`trend ${field} changes immediately invalidate pending results and visible data`, async t => {
    const pending = deferred(), view = setup(t, { getItemTrend: () => pending.promise })
    view.trendForm.itemName = 'Synthetic A'; view.trendData.value = [{ marker: 'existing' }]
    const loading = view.loadTrend()
    view.trendForm[field] = field === 'itemName' ? 'Synthetic B' : 2
    assert.deepEqual(view.trendData.value, []); assert.equal(view.trendLoading.value, false)
    pending.resolve(ok([{ marker: 'old' }])); await loading
    assert.deepEqual(view.trendData.value, [])
  })
  for (const outcome of ['success', 'empty', 'error']) {
    test(`trend ${field} same-tick A-B-A ignores old ${outcome}`, async t => {
      const pending = deferred(), view = setup(t, { getItemTrend: () => pending.promise })
      view.trendForm.itemName = 'Synthetic A'; const original = view.trendForm[field]
      const loading = view.loadTrend()
      view.trendForm[field] = field === 'itemName' ? 'Synthetic B' : 2; view.trendForm[field] = original
      if (outcome === 'error') pending.reject(new Error('Synthetic obsolete failure'))
      else pending.resolve(ok(outcome === 'empty' ? [] : [{ marker: 'old' }]))
      await loading
      assert.deepEqual(view.trendData.value, []); assert.deepEqual(view.errors, []); assert.deepEqual(view.infos, [])
      assert.equal(view.trendLoading.value, false)
    })
  }
}
test('latest trend request alone owns data and loading for an unchanged selector', async t => {
  const a = deferred(), b = deferred(); let count = 0
  const view = setup(t, { getItemTrend: () => ++count === 1 ? a.promise : b.promise })
  view.trendForm.itemName = 'Synthetic A'; const first = view.loadTrend(), second = view.loadTrend()
  a.resolve(ok([{ marker: 'old' }])); await first
  assert.deepEqual(view.trendData.value, []); assert.equal(view.trendLoading.value, true)
  b.resolve(ok([{ marker: 'new' }])); await second
  assert.deepEqual(view.trendData.value, [{ marker: 'new' }]); assert.equal(view.trendLoading.value, false)
})
for (const outcome of ['success', 'error']) {
  test(`unmounted trend ignores late ${outcome}`, async t => {
    const pending = deferred(), view = setup(t, { getItemTrend: () => pending.promise })
    view.trendForm.itemName = 'Synthetic A'; const loading = view.loadTrend(); view.leave()
    if (outcome === 'error') pending.reject(new Error('Synthetic late failure')); else pending.resolve(ok([{ marker: 'late' }]))
    await loading
    assert.deepEqual(view.trendData.value, []); assert.deepEqual(view.errors, []); assert.equal(view.trendLoading.value, false)
  })
}
test('global patient changes do not rebind local trend or independent record filters', async t => {
  const pending = deferred(), view = setup(t, { getItemTrend: () => pending.promise })
  view.trendForm.itemName = 'Synthetic A'; view.filterForm.patientId = null
  const loading = view.loadTrend(); view.currentPatientId.value = 2; await nextTick()
  assert.equal(view.trendForm.patientId, 1); assert.equal(view.filterForm.patientId, null)
  pending.resolve(ok([{ marker: 'locally selected patient 1' }])); await loading
  assert.deepEqual(view.trendData.value, [{ marker: 'locally selected patient 1' }])
})
test('current trend errors and empty outcomes still produce their messages', async t => {
  const view = setup(t, { getItemTrend: async () => { throw new Error('Synthetic current failure') } })
  view.trendForm.itemName = 'Synthetic A'; await view.loadTrend()
  assert.equal(view.errors.length, 1); assert.equal(view.trendLoading.value, false)
  const empty = setup(t, { getItemTrend: async () => ok([]) })
  empty.trendForm.itemName = 'Synthetic A'; await empty.loadTrend()
  assert.equal(empty.infos.length, 1); assert.equal(empty.trendLoading.value, false)
})
test('global patient switch clears prior item names and ignores the old response', async t => {
  const a = deferred(), b = deferred(), calls = []
  const view = setup(t, { getAllItemNames: id => { calls.push(id); return id === 1 ? a.promise : b.promise } })
  view.allItemNames.value = ['Synthetic old visible item']; const first = view.loadItemNames()
  view.currentPatientId.value = 2; assert.deepEqual(view.allItemNames.value, [])
  b.resolve(ok(['Synthetic B item'])); await nextTick(); await Promise.resolve()
  assert.ok(view.allItemNames.value.includes('Synthetic B item'))
  a.resolve(ok(['Synthetic A item'])); await first
  assert.equal(view.allItemNames.value.includes('Synthetic A item'), false)
  assert.ok(view.allItemNames.value.includes('Synthetic B item')); assert.deepEqual(calls, [1, 2])
})
for (const outcome of ['success', 'error']) {
  test(`item-name A-B-A ignores original ${outcome} despite matching current patient`, async t => {
    const a = deferred(), b = deferred(), c = deferred(); let count = 0
    const view = setup(t, { getAllItemNames: () => [a, b, c][count++].promise })
    const first = view.loadItemNames(); view.currentPatientId.value = 2; view.currentPatientId.value = 1
    c.resolve(ok(['Synthetic newest A item'])); await nextTick(); await Promise.resolve()
    if (outcome === 'error') a.reject(new Error('Synthetic old item failure')); else a.resolve(ok(['Synthetic stale A item']))
    await first; b.resolve(ok(['Synthetic stale B item'])); await nextTick(); await Promise.resolve()
    assert.ok(view.allItemNames.value.includes('Synthetic newest A item'))
    assert.equal(view.allItemNames.value.includes('Synthetic stale A item'), false)
    assert.equal(view.allItemNames.value.includes('Synthetic stale B item'), false); assert.deepEqual(view.warnings, [])
  })
}
for (const outcome of ['success', 'error']) {
  test(`unmounted item-name request ignores late ${outcome}`, async t => {
    const pending = deferred(), view = setup(t, { getAllItemNames: () => pending.promise })
    const loading = view.loadItemNames(); view.leave()
    if (outcome === 'error') pending.reject(new Error('Synthetic late item failure')); else pending.resolve(ok(['Synthetic late item']))
    await loading
    assert.deepEqual(view.allItemNames.value, []); assert.deepEqual(view.warnings, [])
  })
}
test('current item names retain defaults, deduplicate stored names, and remain sorted', async t => {
  const view = setup(t, { getAllItemNames: async () => ok(['Synthetic Z', 'Synthetic A', 'Synthetic Z']) })
  await view.loadItemNames()
  assert.equal(view.allItemNames.value.filter(name => name === 'Synthetic Z').length, 1)
  assert.ok(view.allItemNames.value.includes('PTH'))
  assert.deepEqual(view.allItemNames.value, [...view.allItemNames.value].sort((a, b) => a.localeCompare(b, 'zh')))
})

function sourceSession(){const storage=new Map([['token','source-session'],['userId','7']]);globalThis.localStorage={getItem:key=>storage.get(key)};globalThis.window=new EventTarget()}
test('actual medical page cold locator loads only exact detail, never broad items, list or names',async t=>{
 sourceSession();const calls=[];const view=setup(t,{getRecord:async(...args)=>{calls.push(['detail',...args]);return ok({id:17,patientId:2,items:[],attachments:[]})},listRecords:async()=>{calls.push(['list']);return ok([])},getAllItemNames:async()=>{calls.push(['items']);return ok([])}},{fullPath:'/medical-record?tab=list&patientId=2&recordId=17',query:{tab:'list',patientId:'2',recordId:'17'}})
 view.start();await nextTick();await nextTick();assert.equal(view.currentRecord.value.patientId,2);assert.deepEqual(calls.map(c=>c[0]),['detail']);assert.equal(calls[0][1],17);assert.equal(calls[0][2],2)
})
test('an ordinary detail response cannot overwrite a new focused source after warm navigation',async t=>{
 sourceSession();const pending=deferred();const view=setup(t,{getRecord:async(id,patient)=>patient?ok({id,patientId:patient}):pending.promise});const old=view.viewDetail({id:99});view.route.fullPath='/medical-record?tab=list&patientId=2&recordId=17';await nextTick();await nextTick();assert.equal(view.currentRecord.value.id,17);pending.resolve(ok({id:99,patientId:1}));await old;assert.equal(view.currentRecord.value.id,17)
})
test('leaving focused medical mode restores ordinary independent filters and bootstrap reads',async t=>{
 sourceSession();const calls=[];const view=setup(t,{getRecord:async()=>ok({id:17,patientId:2}),listRecords:async params=>{calls.push(['list',params]);return ok([])},getAllItemNames:async id=>{calls.push(['items',id]);return ok([])}},{fullPath:'/medical-record?tab=list&patientId=2&recordId=17',query:{tab:'list',patientId:'2',recordId:'17'}});await nextTick();view.filterForm.patientId=5;view.route.fullPath='/medical-record?tab=list';await nextTick();assert.equal(view.filterForm.patientId,5);assert.ok(calls.some(c=>c[0]==='list'&&c[1].patientId===5));assert.ok(calls.some(c=>c[0]==='items'))
})

test('leaving a focused medical page for another workspace never starts a broad fallback read',async t=>{
 sourceSession();const calls=[];const view=setup(t,{getRecord:async()=>ok({id:17,patientId:2}),listRecords:async()=>{calls.push('list');return ok([])},getAllItemNames:async()=>{calls.push('items');return ok([])}},{fullPath:'/medical-record?tab=list&patientId=2&recordId=17',query:{tab:'list',patientId:'2',recordId:'17'}});await nextTick();view.route.fullPath='/care';await nextTick();assert.deepEqual(calls,[])
})
