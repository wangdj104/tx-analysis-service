import { captureAuthSession, isAuthSessionCurrent } from '../src/utils/authSession.js'
import { useFocusedCareSource } from '../src/composables/useFocusedCareSource.js'
import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope, nextTick, createRenderer } from 'vue'
import { useHandlers } from 'element-plus/es/components/upload/src/use-handlers.mjs'

function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function setup(t, overrides = {}) {
  const source = fs.readFileSync(new URL('../src/views/MedicalRecordManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const patientId = ref(1), saves = [], messages = [], scope = effectScope(), readers = [], urls = [], revoked = [], unmount = [], pickers = []
  const api = { listRecords: async () => ({ code: 200, data: [] }), getAllItemNames: async () => ({ code: 200, data: [] }), uploadAndRecognize: async () => ({ code: 200, data: { items: [{ itemName: 'Synthetic A', resultValue: '42' }] } }), saveRecord: async (...data) => { saves.push(data); return { code: 200 } }, saveRecordsBatch: async (...data) => { saves.push(data); return { code: 200 } }, ...overrides.api }
  const bindings = { captureAuthSession, isAuthSessionCurrent, useFocusedCareSource, ref, reactive, computed, watch, inject: (_key, fallback) => fallback, onMounted() {}, onUnmounted(callback) { unmount.push(callback) }, use() {},
    CanvasRenderer: {}, EchartsLineChart: {}, GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, TitleComponent: {},
    useCurrentPatient: () => ({ currentPatientId: patientId }), useTableColumns: () => ({}), useMobile: () => ({ isMobile: ref(false) }), useRoute: () => ({ query: {}, path: '/medical-record' }), useRouter: () => ({ push: async () => {} }),
    localDateKey: () => '2026-10-02', readPermissionCache: () => ({}), canAccessWorkspace: () => true, dedupeRecognizedItems: value => value,
    isImageFile: file => file.type?.startsWith('image/'), compressImageFile: async file => file, formatFileSize: value => String(value),
    ElMessage: Object.fromEntries(['success', 'warning', 'error', 'info'].map(level => [level, value => messages.push({ level, value })])),
    document: { createElement() { const input = { click() {} }; pickers.push(input); return input } },
    window: { removeEventListener() {} }, URL: { createObjectURL(file) { const url = `blob:${urls.length}`; urls.push({ file, url }); return url }, revokeObjectURL(url) { revoked.push(url) } },
    FileReader: class { readAsDataURL(file) { readers.push({ file, finish: () => this.onload({ target: { result: `data:image/png;base64,${file.name}` } }) }) } },
    api, ...overrides, }
  bindings.api = api
  const exposed = 'uploadFiles,selectedFiles,uploadForm,patientList,recognizedRecords,recognizeResult,recognizeLoading,archiveForm,archiveItems,archiveSaving,uploadDialogVisible,handleFileChange,startRecognize,saveArchiveRecord,saveRecognizedRecord,resetUploadState,getFilePreviewUrl,triggerCameraUpload,triggerAlbumUpload,uploadRef'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {' + exposed + ',remove: typeof handleFileRemove === "function" ? handleFileRemove : () => {}}')(...Object.values(bindings)))
  t.after(() => scope.stop())
  view.patientList.value = [{ id: 1, patientName: 'Synthetic A' }, { id: 2, patientName: 'Synthetic B' }]
  return { ...view, patientId, saves, readers, messages, source, urls, revoked, unmount, pickers }
}
const pdf = (uid, name = `report-${uid}.pdf`) => ({ uid, name, raw: { name, size: 42, type: 'application/pdf' } })
const image = uid => ({ uid, name: `image-${uid}.png`, raw: { name: `image-${uid}.png`, size: 100, type: 'image/png' } })

// Removing an item from the actual upload widget must remove the bytes sent to APIs.
test('both medical upload widgets connect visible removal to the submitted queue', async t => {
  const view = setup(t), file = pdf(1)
  await view.handleFileChange(file)
  view.remove(file)
  assert.equal(view.selectedFiles.value.length, 0)
  assert.equal((view.source.match(/:on-remove="handleFileRemove"/g) || []).length, 2)
})
test('removing a mobile photo during compression prevents it returning to the queue', async t => {
  const pending = deferred(), view = setup(t, { compressImageFile: () => pending.promise }), file = image(1)
  const work = view.handleFileChange(file)
  view.remove(file)
  pending.resolve(file.raw); await work
  assert.equal(view.selectedFiles.value.length, 0)
})
test('concurrent mobile compression retains the user-selected file order', async t => {
  const first = deferred(), view = setup(t, { compressImageFile: file => file.name === 'image-1.png' ? first.promise : Promise.resolve(file) })
  const a = image(1), b = image(2), work = view.handleFileChange(a)
  await view.handleFileChange(b); first.resolve(a.raw); await work
  assert.deepEqual(view.selectedFiles.value.map(file => file.name), ['image-1.png', 'image-2.png'])
})
test('changing patient A to B and back discards stale OCR and patient-bound files', async t => {
  const pending = deferred(), view = setup(t, { api: { uploadAndRecognize: () => pending.promise } })
  await view.handleFileChange(pdf(1)); const work = view.startRecognize()
  view.uploadForm.patientId = 2; await nextTick(); view.uploadForm.patientId = 1; await nextTick()
  pending.resolve({ code: 200, data: { items: [{ itemName: 'Synthetic A', resultValue: '42' }] } }); await work
  assert.equal(view.recognizeResult.value, false)
  assert.equal(view.recognizedRecords.value.length, 0)
  assert.equal(view.selectedFiles.value.length, 0)
})
test('global patient switching clears the old upload draft and ignores pending compression', async t => {
  const pending = deferred(), view = setup(t, { compressImageFile: () => pending.promise }), file = image(1)
  view.archiveForm.remark = 'Synthetic A draft'; const work = view.handleFileChange(file)
  view.patientId.value = 2; await nextTick(); pending.resolve(file.raw); await work
  assert.equal(view.uploadForm.patientId, 2)
  assert.equal(view.archiveForm.remark, '')
  assert.equal(view.selectedFiles.value.length, 0)
})
test('removing a report invalidates an in-flight recognition response', async t => {
  const pending = deferred(), view = setup(t, { api: { uploadAndRecognize: () => pending.promise } }), file = pdf(1)
  await view.handleFileChange(file); const work = view.startRecognize(); view.remove(file)
  pending.resolve({ code: 200, data: { items: [{ itemName: 'Removed report', resultValue: '42' }] } }); await work
  assert.equal(view.recognizeResult.value, false)
})
test('recognized save cancels before transmission when patient changes during attachment conversion', async t => {
  const view = setup(t)
  await view.handleFileChange(image(1)); await view.startRecognize(); const work = view.saveRecognizedRecord()
  view.uploadForm.patientId = 2; await nextTick(); view.readers[0].finish(); await work
  assert.equal(view.saves.length, 0)
})
test('archive save snapshots items before attachment conversion', async t => {
  const view = setup(t)
  await view.handleFileChange(image(1)); view.archiveItems.value = [{ itemName: 'Original', resultValue: '42' }]
  const work = view.saveArchiveRecord(); view.archiveItems.value[0].resultValue = '99'; view.readers[0].finish(); await work
  assert.equal(view.saves[0][1][0].resultValue, '42')
})
test('an old archive save cannot clear a new patient draft when it finishes', async t => {
  const pending = deferred(), view = setup(t, { api: { saveRecord: () => pending.promise } })
  await view.handleFileChange(pdf(1)); const work = view.saveArchiveRecord(); await nextTick()
  view.uploadForm.patientId = 2; await nextTick(); await view.handleFileChange(pdf(2)); view.archiveForm.remark = 'Synthetic B new draft'
  pending.resolve({ code: 200 }); await work
  assert.equal(view.uploadForm.patientId, 2)
  assert.equal(view.archiveForm.remark, 'Synthetic B new draft')
  assert.deepEqual(view.selectedFiles.value.map(file => file.name), ['report-2.pdf'])
})
test('repeated recognized save clicks create only one request', async t => {
  const pending = deferred(); let calls = 0
  const view = setup(t, { api: { saveRecord: () => { calls++; return pending.promise } } })
  await view.handleFileChange(pdf(1)); await view.startRecognize()
  const first = view.saveRecognizedRecord(), second = view.saveRecognizedRecord(); await nextTick()
  assert.equal(calls, 1); pending.resolve({ code: 200 }); await Promise.all([first, second])
})
test('save without a recognized draft is rejected', async t => {
  const view = setup(t); await view.saveRecognizedRecord(); assert.equal(view.saves.length, 0)
})
test('save waits for mobile photo preparation instead of omitting pending files', async t => {
  const pending = deferred(), view = setup(t, { compressImageFile: () => pending.promise }), photo = image(2)
  await view.handleFileChange(pdf(1)); const compression = view.handleFileChange(photo)
  await view.saveArchiveRecord(); assert.equal(view.saves.length, 0)
  pending.resolve(photo.raw); await compression
})

test('manual camera and paste additions enforce the same ten-file queue limit', async t => {
  const view = setup(t)
  for (let id = 1; id <= 11; id++) await view.handleFileChange(pdf(id))
  assert.equal(view.selectedFiles.value.length, 10)
})
test('upload preparation has a visible accessible status in both entry points', t => {
  const view = setup(t)
  assert.equal((view.source.match(/v-if="filesProcessing"[^>]*role="status"/g) || []).length, 2)
})
test('repeated preview renders reuse one object URL and removal releases it', async t => {
  const view = setup(t), file = image(1)
  await view.handleFileChange(file)
  const prepared = view.selectedFiles.value[0]
  const first = view.getFilePreviewUrl(prepared), second = view.getFilePreviewUrl(prepared)
  assert.equal(first, second)
  assert.equal(view.urls.length, 1)
  view.remove(file)
  assert.deepEqual(view.revoked, [first])
})
test('reset and unmount release previews and invalidate unfinished photos', async t => {
  const view = setup(t), file = image(1)
  await view.handleFileChange(file)
  const first = view.getFilePreviewUrl(view.selectedFiles.value[0])
  view.resetUploadState(); assert.deepEqual(view.revoked, [first])
  await view.handleFileChange(image(2))
  const second = view.getFilePreviewUrl(view.selectedFiles.value[0])
  for (const close of view.unmount) close()
  assert.deepEqual(view.revoked, [first, second])
})
test('a file-add event during a pending save cannot unlock duplicate submission', async t => {
  const pending = deferred(); let calls = 0
  const view = setup(t, { api: { saveRecord: () => { calls++; return pending.promise } } })
  await view.handleFileChange(pdf(1)); const work = view.saveArchiveRecord(); await nextTick()
  await view.handleFileChange(pdf(2)); const repeated = view.saveArchiveRecord(); await nextTick()
  assert.equal(calls, 1)
  assert.deepEqual(view.selectedFiles.value.map(file => file.name), ['report-1.pdf'])
  pending.resolve({ code: 200 }); await Promise.all([work, repeated])
})
test('leaving the view before attachment conversion finishes prevents a late save', async t => {
  const view = setup(t)
  await view.handleFileChange(image(1)); const work = view.saveArchiveRecord()
  for (const close of view.unmount) close()
  view.readers[0].finish(); await work
  assert.equal(view.saves.length, 0)
})
test('a delayed upload widget callback is ignored after its live queue was cleared', async t => {
  const view = setup(t), file = pdf(1)
  view.resetUploadState(2)
  await view.handleFileChange(file, [])
  assert.equal(view.selectedFiles.value.length, 0)
})

for (const action of ['triggerCameraUpload', 'triggerAlbumUpload']) {
  test(`${action}: an old native picker cannot add a report to a new patient`, async t => {
    const view = setup(t), added = []
    view.uploadRef.value = { clearFiles() {}, handleStart: file => added.push(file) }
    view[action]('page')
    view.uploadForm.patientId = 2
    view.pickers[0].onchange({ target: { files: [pdf(1).raw] } })
    assert.equal(added.length, 0)
  })
}

function widget(t, overrides = {}) {
  let view, uploader
  const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() {
    view = setup(t, overrides)
    const props = reactive({ fileList: view.uploadFiles.value, listType: 'text', onChange: view.handleFileChange, onRemove: view.remove })
    watch(view.uploadFiles, files => { props.fileList = files }, { flush: 'sync' })
    uploader = useHandlers(props, ref())
    view.uploadRef.value = uploader
    return () => null
  } })
  app.mount({}); t.after(() => app.unmount())
  return { view, uploader }
}
test('real ElementPlus queued change callbacks cannot cross a patient reset', async t => {
  const { view, uploader } = widget(t)
  uploader.handleStart({ ...pdf(991).raw, uid: 991 })
  view.patientId.value = 2
  await nextTick(); await nextTick()
  assert.equal(view.uploadForm.patientId, 2)
  assert.deepEqual(view.selectedFiles.value, [])
})
test('real ElementPlus multi-selection and removal stay in sync with API files', async t => {
  const { view, uploader } = widget(t)
  uploader.handleStart({ ...pdf(1).raw, uid: 1 }); uploader.handleStart({ ...pdf(2).raw, uid: 2 })
  await nextTick(); await nextTick()
  assert.deepEqual(view.selectedFiles.value.map(file => file.name), ['report-1.pdf', 'report-2.pdf'])
  await uploader.handleRemove(uploader.uploadFiles.value[0]); await nextTick()
  assert.deepEqual(view.selectedFiles.value.map(file => file.name), ['report-2.pdf'])
  await view.saveArchiveRecord()
  assert.deepEqual(view.saves[0][2].map(file => file.fileName), ['report-2.pdf'])
})
test('real ElementPlus removal before its change callback never queues removed bytes', async t => {
  const { view, uploader } = widget(t)
  uploader.handleStart({ ...pdf(1).raw, uid: 1 })
  await uploader.handleRemove(uploader.uploadFiles.value[0]); await nextTick(); await nextTick()
  assert.deepEqual(view.selectedFiles.value, [])
})
test('the page recognition editor locks every editable control while its snapshot saves', t => {
  const view = setup(t)
  const editor = view.source.split('<div v-if="recognizeResult" class="recognize-result">')[1].split('<!-- Result Trends -->')[0]
  assert.match(editor, /<el-form[^>]*:disabled="uploadSaving"/)
  for (const tag of editor.matchAll(/<el-(?:input|select|button)(?:\s[^>]*?)?>/g)) {
    assert.match(tag[0], /:disabled="uploadSaving"/, tag[0])
  }
})
