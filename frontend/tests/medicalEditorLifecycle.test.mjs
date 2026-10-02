import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import * as Vue from 'vue'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'
import ElementPlus, { ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { useHandlers } from 'element-plus/es/components/upload/src/use-handlers.mjs'
import { useDialog } from 'element-plus/es/components/dialog/src/use-dialog.mjs'

const ok = data => ({ code: 200, data })
const item = () => ({ id: 11, itemName: 'Synthetic item', resultValue: '42', unit: 'synthetic', referenceRange: 'Synthetic range', isAbnormal: 0 })
const attachment = () => ({ id: 12, fileName: 'synthetic-existing.png', fileType: 'IMAGE', fileSize: 42, fileContent: 'c3ludGhldGlj' })
const record = (id, extra = {}) => ({ id, patientId: 1, patientName: 'Synthetic patient A', recordDate: '2026-10-02', recordType: 'OTHER', hospitalName: 'Synthetic hospital', doctorName: 'Synthetic clinician', remark: `Synthetic record ${id}`, items: [item()], attachments: [attachment()], ...extra })
const photo = (uid = 1) => ({ uid, name: `synthetic-${uid}.png`, raw: { uid, name: `synthetic-${uid}.png`, type: 'image/png', size: 42 } })
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const tick = async () => { await Vue.nextTick(); await Vue.nextTick() }

// Execute the real component with Vue reactivity, synthetic API responses and
// controlled FileReaders. No production API or real patient data is used.
function setup(t, overrides = {}) {
  const source = fs.readFileSync(new URL('../src/views/MedicalRecordManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const patientId = Vue.ref(1), requests = [], messages = [], readers = [], callbacks = [], scope = Vue.effectScope()
  const api = {
    listRecords: async () => { requests.push({ method: 'list' }); return ok([]) }, getAllItemNames: async () => ok([]),
    getRecord: async id => ok(record(id)), updateRecord: async (...payload) => { requests.push({ method: 'update', payload }); return ok() }, ...overrides.api
  }
  const bindings = {
    ref: Vue.ref, reactive: Vue.reactive, computed: Vue.computed, watch: Vue.watch, inject: (_key, fallback) => fallback,
    onMounted() {}, onUnmounted: callback => callbacks.push(callback), use() {}, CanvasRenderer: {}, EchartsLineChart: {}, GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, TitleComponent: {},
    useCurrentPatient: () => ({ currentPatientId: patientId }), useTableColumns: () => ({}), useMobile: () => ({ isMobile: Vue.ref(false) }), useRoute: () => ({ query: {}, path: '/medical-record' }), useRouter: () => ({ push: async () => {} }),
    localDateKey: () => '2026-10-02', readPermissionCache: () => ({}), canAccessWorkspace: () => true, dedupeRecognizedItems: value => value,
    isImageFile: file => file.type?.startsWith('image/'), compressImageFile: async file => file, formatFileSize: String,
    ElMessage: Object.fromEntries(['success', 'warning', 'error', 'info'].map(level => [level, value => messages.push({ level, value })])),
    window: { removeEventListener() {} }, URL: { revokeObjectURL() {} },
    FileReader: class { readAsDataURL(file) { readers.push({ file, finish: () => this.onload({ target: { result: `data:image/png;base64,${file.name}` } }), fail: () => this.onerror(new Error('Synthetic file failure')) }) } }, api
  }
  const names = 'patientList,editDialogVisible,editForm,editItems,editAttachments,editAttachmentUploadRef,openEditDialog,handleEditAttachmentChange,removeEditAttachment,addEditItem,removeEditItem,saveEditRecord,getImageSrc,getPreviewSrcList'
  const optional = ['editLoading', 'editReady', 'editSaving', 'editLocked', 'editFilesProcessing', 'editSession', 'editAttachmentChange', 'editDialogKey', 'editDialogBeforeClose', 'editDialogModelChange']
  const view = scope.run(() => new Function(...Object.keys(bindings), script + `\nreturn {${names},${optional.map(name => `...(typeof ${name} === 'undefined' ? {} : {${name}})`).join(',')}}`)(...Object.values(bindings)))
  view.patientList.value = [{ id: 1, patientName: 'Synthetic patient A' }, { id: 2, patientName: 'Synthetic patient B' }]
  const unmount = () => { callbacks.splice(0).forEach(callback => callback()); scope.stop() }
  t.after(unmount)
  const template = source.slice(source.search(/<el-dialog (?:v-model|:model-value)="editDialogVisible"/), source.lastIndexOf('</el-dialog>') + '</el-dialog>'.length)
  const { code } = compile(template, { mode: 'function', prefixIdentifiers: true })
  function nodes() {
    const render = new Function('Vue', code)({ ...Vue, resolveComponent: name => ({ name }), withDirectives: node => node })
    const result = []
    const walk = node => {
      if (!node || typeof node !== 'object') return
      if (Array.isArray(node)) return node.forEach(walk)
      result.push(node)
      if (Array.isArray(node.children)) node.children.forEach(walk)
      else if (node.children && typeof node.children === 'object') for (const slot of Object.values(node.children)) if (typeof slot === 'function') walk(slot({ row: view.editItems.value[0] || item(), $index: 0 }))
    }
    walk(render(Vue.proxyRefs(view), []))
    return result
  }
  const controls = () => {
    const all = nodes(), find = name => all.find(node => node.type?.name === name)
    return { all, dialog: find('el-dialog').props, form: find('el-form').props, upload: find('el-upload').props, save: all.find(node => node.props?.onClick === view.saveEditRecord).props }
  }
  async function renderedControls() {
    const slotsOnly = name => Vue.defineComponent({ name, setup: (_props, { slots }) => () => Vue.h('div', [slots.default?.(), slots.footer?.()]) })
    const column = Vue.defineComponent({ setup: (_props, { slots }) => () => Vue.h('div', slots.default?.({ row: view.editItems.value[0] || item(), $index: 0 })) })
    const wrappers = { 'el-dialog': slotsOnly('TestDialog'), 'el-table': slotsOnly('TestTable'), 'el-table-column': column }
    const render = new Function('Vue', code)({ ...Vue, resolveComponent: name => wrappers[name] || Vue.resolveComponent(name) })
    const app = Vue.createSSRApp({ setup: () => view, render })
    app.use(ElementPlus); app.component('Upload', Upload)
    // Dialog teleport and table geometry are irrelevant to disabled control
    // semantics. Every form/input/select/date-picker/button/upload is real.
    app.provide(ID_INJECTION_KEY, { prefix: 100, current: 0 }); app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
    return renderToString(app)
  }
  return { ...view, patientId, requests, messages, readers, unmount, controls, renderedControls }
}
const writes = view => view.requests.filter(request => request.method === 'update')
const refreshes = view => view.requests.filter(request => request.method === 'list')

// Preserve the installed dialog's real timing: header/Escape call beforeClose,
// model false is otherwise emitted only afterLeave. Use production prop bindings.
async function dialogHarness(t, v) {
  const dialogs = []
  const renderer = Vue.createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const Probe = Vue.defineComponent({
    props: ['modelValue', 'beforeClose'], emits: ['update:modelValue', 'close', 'closed'],
    setup(props) {
      const dialog = useDialog(Vue.reactive({ get modelValue() { return props.modelValue }, get beforeClose() { return props.beforeClose }, destroyOnClose: true, closeOnPressEscape: true, lockScroll: false }), Vue.ref())
      dialogs.push(dialog)
      return () => null
    }
  })
  const app = renderer.createApp({ setup: () => () => Vue.h(Probe, v.controls().dialog) })
  app.provide(ID_INJECTION_KEY, { prefix: 101, current: 0 }); app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
  app.mount({}); t.after(() => app.unmount()); await tick()
  return dialogs
}

for (const closeBy of ['header', 'Escape']) {
  for (const phase of ['read', 'attachment', 'save']) {
    for (const outcome of ['success', 'failure', 'rejection']) {
      if (phase === 'attachment' && outcome === 'failure') continue
      test(`real dialog ${closeBy} close-start makes pending ${phase} ${outcome} inert before afterLeave`, async t => {
        const pending = deferred(), v = setup(t, { api: phase === 'read' ? { getRecord: () => pending.promise } : phase === 'save' ? { updateRecord: () => pending.promise } : {} })
        let work = v.openEditDialog({ id: 10 })
        if (phase !== 'read') await work
        if (phase === 'save') work = v.saveEditRecord()
        if (phase === 'attachment') work = v.handleEditAttachmentChange(photo())
        const dialogs = await dialogHarness(t, v), dialog = dialogs.at(-1)
        assert.equal(dialog.visible.value, true)
        if (closeBy === 'header') dialog.handleClose(); else dialog.onCloseRequested()
        dialog.beforeLeave()
        const before = { ...v.editForm }, attachments = [...v.editAttachments.value]
        if (phase === 'attachment') { if (outcome === 'success') v.readers[0].finish(); else v.readers[0].fail() }
        else if (outcome === 'rejection') pending.reject(new Error('Synthetic close-window error'))
        else pending.resolve(outcome === 'success' ? ok(phase === 'read' ? record(10) : undefined) : { code: 500, message: 'Synthetic close-window failure' })
        await work
        assert.equal(v.editDialogVisible.value, false, 'dismissal invalidates synchronously, before afterLeave')
        assert.deepEqual({ ...v.editForm }, before); assert.deepEqual(v.editAttachments.value, attachments)
        assert.deepEqual(v.messages, []); assert.equal(refreshes(v).length, 0)
        assert.equal(v.editReady?.value, false); assert.equal(v.editSaving?.value, false)
        dialog.afterLeave(); await tick(); assert.equal(v.editDialogVisible.value, false)
      })
    }
  }
}
for (const closeBy of ['header', 'Escape', 'Cancel']) {
  test(`old ${closeBy} afterLeave cannot close a replacement before or after its dialog mounts`, async t => {
    const pending = deferred(); let calls = 0
    const v = setup(t, { api: { getRecord: id => ++calls === 1 ? Promise.resolve(ok(record(id))) : pending.promise } })
    await v.openEditDialog({ id: 10 }); const dialogs = await dialogHarness(t, v), previous = dialogs.at(-1)
    if (closeBy === 'Cancel') {
      const cancel = v.controls().all.find(node => node.type?.name === 'el-button' && node.children?.default?.().some(child => /^(Cancel|取消)$/.test(String(child.children).trim())))
      assert.ok(cancel, 'compiled footer Cancel is present')
      cancel.props.onClick() // The real compiled footer Cancel callback.
    }
    else if (closeBy === 'header') previous.handleClose()
    else previous.onCloseRequested()
    previous.beforeLeave()
    const opening = v.openEditDialog({ id: 20 })
    previous.afterLeave() // Parent update can arrive before Vue patches the new keyed instance.
    assert.equal(v.editDialogVisible.value, true)
    pending.resolve(ok(record(20))); await opening; await tick()
    assert.equal(dialogs.length, 2, 'replacement gets its own dialog lifetime')
    assert.equal(dialogs.at(-1).visible.value, true)
    previous.afterLeave(); assert.equal(v.editDialogVisible.value, true)
    assert.equal(v.editForm.id, 20); assert.equal(v.editReady.value, true)
  })
}

for (const sameRow of [false, true]) {
  test(`latest edit read wins over an earlier ${sameRow ? 'same-record' : 'other-record'} open`, async t => {
    const first = deferred(), second = deferred(); let calls = 0
    const v = setup(t, { api: { getRecord: () => ++calls === 1 ? first.promise : second.promise } })
    const oldOpen = v.openEditDialog({ id: 10 }), newOpen = v.openEditDialog({ id: sameRow ? 10 : 20 })
    second.resolve(ok(record(sameRow ? 10 : 20))); await newOpen; v.editForm.remark = 'Replacement draft'
    first.resolve(ok(record(10))); await oldOpen
    assert.equal(v.editForm.remark, 'Replacement draft'); assert.equal(v.editForm.id, sameRow ? 10 : 20)
  })
}
for (const interruptedBy of ['cancel', 'unmount']) {
  test(`${interruptedBy} during a read cannot reopen or populate the editor`, async t => {
    const pending = deferred(), v = setup(t, { api: { getRecord: () => pending.promise } })
    const opening = v.openEditDialog({ id: 10 })
    assert.equal(v.editDialogVisible.value, true, 'loading dialog can be cancelled')
    if (interruptedBy === 'cancel') v.editDialogVisible.value = false; else v.unmount()
    const before = { ...v.editForm }
    pending.resolve(ok(record(10))); await opening
    assert.deepEqual({ ...v.editForm }, before); assert.deepEqual(v.messages, [])
    if (interruptedBy === 'cancel') assert.equal(v.editDialogVisible.value, false)
  })
}
for (const outcome of ['success', 'failure', 'rejection']) {
  test(`old read ${outcome} cannot clear replacement loading or show stale feedback`, async t => {
    const first = deferred(), second = deferred(); let calls = 0
    const v = setup(t, { api: { getRecord: () => ++calls === 1 ? first.promise : second.promise } })
    const one = v.openEditDialog({ id: 10 }), two = v.openEditDialog({ id: 20 })
    if (outcome === 'rejection') first.reject(new Error('Synthetic old failure')); else first.resolve(outcome === 'success' ? ok(record(10)) : { code: 500 })
    await one
    assert.equal(v.editLoading?.value, true); assert.equal(v.editReady?.value, false); assert.deepEqual(v.messages, [])
    await v.saveEditRecord(); assert.equal(writes(v).length, 0)
    second.resolve(ok(record(20))); await two
    assert.equal(v.editLoading.value, false); assert.equal(v.editReady.value, true)
  })
}
for (const outcome of ['failure', 'rejection', 'mismatched-record']) {
  test(`read ${outcome} cannot save an incomplete draft and permits deliberate reopen`, async t => {
    let calls = 0
    const v = setup(t, { api: { getRecord: async id => {
      if (++calls > 1) return ok(record(id))
      if (outcome === 'rejection') throw new Error('Synthetic read failure')
      return outcome === 'failure' ? { code: 500 } : ok(record(999))
    } } })
    await v.openEditDialog({ id: 10 }); await v.saveEditRecord()
    assert.equal(writes(v).length, 0); assert.equal(v.editReady?.value, false); assert.equal(v.messages.filter(m => m.level === 'error').length, 1)
    await v.openEditDialog({ id: 10 }); await v.saveEditRecord(); assert.equal(writes(v).length, 1)
  })
}

test('opening a replacement clears old form, items and attachments while it loads', async t => {
  const pending = deferred(); let calls = 0
  const v = setup(t, { api: { getRecord: id => ++calls === 1 ? Promise.resolve(ok(record(id))) : pending.promise } })
  await v.openEditDialog({ id: 10 }); const opening = v.openEditDialog({ id: 20 })
  assert.equal(v.editForm.id, null); assert.deepEqual(v.editItems.value, []); assert.deepEqual(v.editAttachments.value, [])
  pending.resolve(ok(record(20))); await opening
})

for (const interruptedBy of ['same-record', 'other-record', 'cancel', 'unmount']) {
  for (const failure of [false, true]) test(`late attachment ${failure ? 'failure' : 'success'} is inert after ${interruptedBy}`, async t => {
    const v = setup(t); await v.openEditDialog({ id: 10 })
    const adding = v.handleEditAttachmentChange(photo())
    if (interruptedBy === 'unmount') v.unmount()
    else { v.editDialogVisible.value = false; if (interruptedBy !== 'cancel') await v.openEditDialog({ id: interruptedBy === 'same-record' ? 10 : 20 }) }
    const before = [...v.editAttachments.value]
    if (failure) v.readers[0].fail(); else v.readers[0].finish()
    await adding
    assert.deepEqual(v.editAttachments.value, before); assert.deepEqual(v.messages, [])
  })
}

test('a queued callback from an old Element Plus upload cannot attach into a replacement editor', async t => {
  let v
  const uploaders = []
  const renderer = Vue.createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const UploadProbe = Vue.defineComponent({
    props: ['onChange'],
    setup(props) {
      const uploader = useHandlers(Vue.reactive({ fileList: [], listType: 'text', onChange: (...args) => props.onChange(...args), onRemove() {} }), Vue.ref())
      uploaders.push(uploader)
      return () => null
    }
  })
  const app = renderer.createApp({ setup() {
    v = setup(t)
    return () => {
      if (!v.editDialogVisible.value) return null
      const props = v.controls().upload
      return Vue.h(UploadProbe, { key: props.key, onChange: props['on-change'] })
    }
  } })
  app.mount({}); t.after(() => app.unmount())
  await v.openEditDialog({ id: 10 }); await tick()
  // The actual handler queues onChange until nextTick. The production key must
  // replace this widget, or Vue updates its props to the newer session callback.
  const originalUploader = uploaders.at(-1)
  originalUploader.handleStart(photo().raw)
  await v.openEditDialog({ id: 20 }); await tick()
  for (const reader of v.readers) reader.finish()
  await tick()
  assert.equal(uploaders.length, 2, 'each editor session must own its uploader')
  assert.equal(v.readers.length, 0); assert.deepEqual(v.editAttachments.value.map(a => a.fileName), ['synthetic-existing.png']); assert.deepEqual(v.messages, [])
  // A later orphan-widget callback must still retain its old session identity,
  // even after the replacement is fully loaded and editable.
  originalUploader.handleStart(photo(99).raw); await tick()
  assert.equal(v.readers.length, 0)
  uploaders.at(-1).handleStart(photo(2).raw); await tick()
  assert.equal(v.readers.length, 1, 'the current uploader remains usable')
  v.readers[0].finish(); await tick()
  assert.deepEqual(v.editAttachments.value.map(a => a.fileName), ['synthetic-existing.png', 'synthetic-2.png'])
})

test('save waits for all attachment conversions, then snapshots their results', async t => {
  const v = setup(t); await v.openEditDialog({ id: 10 })
  const first = v.handleEditAttachmentChange(photo(1)), second = v.handleEditAttachmentChange(photo(2))
  await v.saveEditRecord(); assert.equal(writes(v).length, 0); assert.equal(v.controls().save.disabled, true)
  v.readers[0].finish(); await first; await v.saveEditRecord(); assert.equal(writes(v).length, 0)
  v.readers[1].finish(); await second; await v.saveEditRecord()
  assert.equal(writes(v).length, 1); assert.deepEqual(writes(v)[0].payload[2].map(a => a.fileName), ['synthetic-existing.png', 'synthetic-1.png', 'synthetic-2.png'])
})

test('attachment failure releases only its own session preparation state and permits retry', async t => {
  const v = setup(t); await v.openEditDialog({ id: 10 }); const first = v.handleEditAttachmentChange(photo())
  v.readers[0].fail(); await first
  assert.equal(v.editFilesProcessing?.value, false); assert.equal(v.messages.filter(m => m.level === 'error').length, 1)
  const second = v.handleEditAttachmentChange(photo(2)); v.readers[1].finish(); await second
  await v.saveEditRecord(); assert.equal(writes(v)[0].payload[2].length, 2)
})

test('old attachment finally cannot clear replacement preparation state', async t => {
  const v = setup(t); await v.openEditDialog({ id: 10 }); const old = v.handleEditAttachmentChange(photo())
  await v.openEditDialog({ id: 20 }); const current = v.handleEditAttachmentChange(photo(2))
  v.readers[0].finish(); await old; assert.equal(v.editFilesProcessing?.value, true)
  await v.saveEditRecord(); assert.equal(writes(v).length, 0)
  v.readers[1].finish(); await current; assert.equal(v.editFilesProcessing.value, false)
})

test('duplicate saves dispatch one update and freeze item/attachment mutation handlers', async t => {
  const pending = deferred(), payloads = [], v = setup(t, { api: { updateRecord: (...data) => { payloads.push(data); return pending.promise } } })
  await v.openEditDialog({ id: 10 }); const first = v.saveEditRecord(), second = v.saveEditRecord()
  v.addEditItem(); v.removeEditItem(0); v.removeEditAttachment(0); const adding = v.handleEditAttachmentChange(photo())
  assert.equal(payloads.length, 1); assert.equal(v.editItems.value.length, 1); assert.equal(v.editAttachments.value.length, 1); assert.equal(v.readers.length, 0)
  pending.resolve(ok()); await Promise.all([first, second, adding]); assert.equal(v.editDialogVisible.value, false)
})

test('save payload is detached from later edits and preserves the deliberately selected patient', async t => {
  const pending = deferred(), payloads = [], v = setup(t, { api: { updateRecord: (...data) => { payloads.push(data); return pending.promise } } })
  await v.openEditDialog({ id: 10 }); v.editForm.patientId = 2
  const saving = v.saveEditRecord()
  v.editForm.id = 20; v.editForm.patientId = 1; v.editForm.remark = 'Later mutation'; v.editItems.value[0].resultValue = '99'; v.editAttachments.value[0].fileName = 'Later mutation'
  assert.equal(payloads[0][0].id, 10); assert.equal(payloads[0][0].patientId, 2); assert.equal(payloads[0][0].remark, 'Synthetic record 10')
  assert.equal(payloads[0][1][0].resultValue, '42'); assert.equal(payloads[0][2][0].fileName, 'synthetic-existing.png')
  pending.resolve(ok()); await saving
})
for (const replacement of ['same-record', 'other-record', 'cancel', 'unmount']) {
  for (const outcome of ['success', 'failure', 'rejection']) test(`old save ${outcome} is inert after ${replacement}`, async t => {
    const pending = deferred(), v = setup(t, { api: { updateRecord: () => pending.promise } })
    await v.openEditDialog({ id: 10 }); const saving = v.saveEditRecord()
    if (replacement === 'unmount') v.unmount()
    else { v.editDialogVisible.value = false; if (replacement !== 'cancel') await v.openEditDialog({ id: replacement === 'same-record' ? 10 : 20 }) }
    v.editForm.remark = 'Keep replacement'; const before = [...v.editItems.value]
    if (outcome === 'rejection') pending.reject(new Error('Synthetic old failure')); else pending.resolve({ code: outcome === 'success' ? 200 : 500 })
    await saving
    assert.equal(v.editForm.remark, 'Keep replacement'); assert.deepEqual(v.editItems.value, before); assert.deepEqual(v.messages, []); assert.equal(refreshes(v).length, 0)
    if (replacement.endsWith('record')) assert.equal(v.editDialogVisible.value, true)
  })
}

test('old save finally cannot unlock a newer in-flight save', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const v = setup(t, { api: { updateRecord: () => ++calls === 1 ? first.promise : second.promise } })
  await v.openEditDialog({ id: 10 }); const old = v.saveEditRecord(); await v.openEditDialog({ id: 20 }); const current = v.saveEditRecord()
  first.reject(new Error('Synthetic old error')); await old
  assert.equal(v.editSaving?.value, true); assert.deepEqual(v.messages, []); await v.saveEditRecord(); assert.equal(calls, 2)
  second.resolve(ok()); await current
})
for (const outcome of ['failure', 'rejection']) {
  test(`current save ${outcome} preserves the draft and permits retry`, async t => {
    let calls = 0
    const v = setup(t, { api: { updateRecord: async () => { if (++calls === 1) { if (outcome === 'rejection') throw new Error('Synthetic failure'); return { code: 500 } } return ok() } } })
    await v.openEditDialog({ id: 10 }); await v.saveEditRecord()
    assert.equal(v.editDialogVisible.value, true); assert.equal(v.editForm.remark, 'Synthetic record 10'); assert.equal(v.editSaving?.value, false); assert.equal(v.messages.filter(m => m.level === 'error').length, 1)
    await v.saveEditRecord(); assert.equal(calls, 2); assert.equal(v.editDialogVisible.value, false); assert.equal(refreshes(v).length, 1)
  })
}

test('global patient changes do not rewrite a deliberate editor patient selection', async t => {
  const v = setup(t); await v.openEditDialog({ id: 10 }); v.editForm.patientId = 2
  v.patientId.value = 2; v.patientId.value = 1; await tick()
  assert.equal(v.editDialogVisible.value, true); assert.equal(v.editForm.patientId, 2)
  await v.saveEditRecord(); assert.equal(writes(v)[0].payload[0].patientId, 2)
})

test('real Element Plus controls lock during loading and saving, while Cancel and replacement editing remain available', async t => {
  const load = deferred(), save = deferred(); let calls = 0
  const v = setup(t, { api: { getRecord: id => ++calls === 1 ? load.promise : Promise.resolve(ok(record(id))), updateRecord: () => save.promise } })
  async function check(disabled) {
    const html = await v.renderedControls()
    const inputs = [...html.matchAll(/<(?:input|textarea)\b[^>]*>/g)].map(match => match[0])
    assert.ok(inputs.length >= 11, 'real form and item inputs and file picker render')
    for (const input of inputs) assert.equal(/\sdisabled(?:\s|=|>)/.test(input), disabled, input)
    const buttons = [...html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g)]
    assert.ok(buttons.length >= 5)
    for (const button of buttons) {
      const cancel = /Cancel|取消/.test(button[2])
      assert.equal(/\sdisabled(?:\s|=|>)/.test(button[1]), cancel ? false : disabled, button[0])
    }
  }
  const opening = v.openEditDialog({ id: 10 }); await check(true)
  load.resolve(ok(record(10))); await opening; await check(false)
  const saving = v.saveEditRecord(); await check(true)
  v.editDialogVisible.value = false; await v.openEditDialog({ id: 20 }); await check(false)
  save.resolve(ok()); await saving; await check(false)
})
