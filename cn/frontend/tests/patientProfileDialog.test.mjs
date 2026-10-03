import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import * as Vue from 'vue'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'
import { useFormDisabled } from 'element-plus/es/components/form/src/hooks/use-form-common-props.mjs'
import { formContextKey } from 'element-plus/es/components/form/src/constants.mjs'

const ok = data => ({ code: 200, data })
const catalog = [{ id: 101, roleName: 'Synthetic specialty' }, { id: 102, roleName: 'Synthetic alternative' }]
const patient = (id, extra = {}) => ({ id, name: `Synthetic patient ${id}`, phone: '00000000000', idCard: 'SYNTHETIC00000001', address: 'Synthetic address only', ...extra })
const tick = () => new Promise(resolve => setImmediate(resolve))
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

// Real Vue reactivity and the production script/template; inert Element Plus and
// API adapters keep every fixture synthetic and never contact a backend.
function setup(t, overrides = {}) {
  const source = fs.readFileSync(new URL('../src/views/PatientManager.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const template = source.slice(source.indexOf('<el-dialog v-model="dialogVisible"'), source.lastIndexOf('</el-dialog>') + '</el-dialog>'.length)
  const { code } = compile(template, { mode: 'function', prefixIdentifiers: true })
  const render = new Function('Vue', code)({ ...Vue, resolveComponent: name => ({ name }), withDirectives: node => node })
  const scope = Vue.effectScope(), messages = [], requests = [], events = [], mounted = [], unmounted = []
  const storage = new Map([['token','synthetic-profile-token'],['userId','8'],['userRoleCodes','[]']])
  const bindings = {
    captureAuthSession: () => ({ token: storage.get('token') || null, revision: 0 }),
    isAuthSessionCurrent: session => !!session?.token && session.token === (storage.get('token') || null) && session.revision === 0,
    getCarePlanCapabilities: async () => ({ data: { enabled: false } }),
    localStorage: { getItem: key => storage.get(key) || null },
    ref: Vue.ref, reactive: Vue.reactive, computed: Vue.computed, watch: Vue.watch, onMounted: callback => mounted.push(callback), onUnmounted: callback => unmounted.push(callback),
    useTableColumns: () => ({}), localizeSpecialtyRole: role => ({ ...role }),
    ElMessage: Object.fromEntries(['success', 'error', 'warning'].map(level => [level, message => messages.push({ level, message })])),
    getSpecialtyRoles: async () => ok(catalog), getPatientSpecialtyRoles: async () => ok([101]),
    getPatientList: async () => { requests.push({ method: 'list' }); return ok([]) },
    savePatient: async payload => { requests.push({ method: 'create', payload }); return ok('Synthetic success') },
    updatePatient: async payload => { requests.push({ method: 'update', payload }); return ok('Synthetic success') },
    deletePatient: async () => { throw new Error('Deletion is outside this test') },
    getClinicalByPatient: async () => { throw new Error('Clinical workflow is outside this test') },
    saveClinical: async () => { throw new Error('Clinical workflow is outside this test') },
    window: { dispatchEvent: event => events.push(event.type) }, Event, console: { error() {} }, ...overrides
  }
  const names = 'patients,displayPatients,showSensitive,form,formRef,rules,dialogVisible,isEdit,specialtyRoles,specialtyLoading,specialtyReady,showAddDialog,showEditDialog,handleSave,loadSpecialtyRoles'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + `\nreturn {${names}, ...(typeof profileSaving === 'undefined' ? {} : {profileSaving})}`)(...Object.values(bindings)))
  view.formRef.value = { validate: async () => true }
  const unmount = () => { unmounted.splice(0).forEach(callback => callback()); scope.stop() }
  t.after(unmount)
  function controls() {
    const nodes = []
    const walk = node => {
      if (!node || typeof node !== 'object') return
      if (Array.isArray(node)) return node.forEach(walk)
      nodes.push(node)
      if (Array.isArray(node.children)) node.children.forEach(walk)
      else if (node.children && typeof node.children === 'object') {
        for (const slot of Object.values(node.children)) if (typeof slot === 'function') walk(slot())
      }
    }
    walk(render(Vue.proxyRefs(view), []))
    return {
      form: nodes.find(node => node.type?.name === 'el-form').props,
      save: nodes.find(node => node.type?.name === 'el-button' && node.props?.onClick === view.handleSave).props,
      specialties: nodes.find(node => node.type?.name === 'el-select' && node.props?.multiple === '').props
    }
  }
  return { ...view, messages, requests, events, controls, mount: () => mounted.forEach(callback => callback()), unmount }
}
// Use the installed Element Plus hook used by ElSelect: an explicit false child
// prop can override the parent form's disabled state.
async function specialtyDisabled(controls) {
  let disabled
  const Probe = Vue.defineComponent({
    props: { disabled: { type: Boolean, default: undefined } },
    setup() { disabled = useFormDisabled().value; return () => Vue.h('span') }
  })
  await renderToString(Vue.createSSRApp({
    setup() {
      Vue.provide(formContextKey, Vue.reactive({ disabled: controls.form.disabled }))
      return () => Vue.h(Probe, { disabled: controls.specialties.disabled })
    }
  }))
  return disabled
}
const writes = view => view.requests.filter(request => ['create', 'update'].includes(request.method))
async function readyAdd(view) {
  await view.loadSpecialtyRoles()
  view.showAddDialog()
  await tick()
  view.form.name = 'Synthetic Add A'
  assert.equal(view.specialtyReady.value, true)
}

test('Add saves a general patient once, refreshes the list and announces the specialty change', async t => {
  const view = setup(t)
  await readyAdd(view)
  await view.handleSave()
  assert.equal(writes(view).length, 1)
  assert.equal(writes(view)[0].method, 'create')
  assert.equal(writes(view)[0].payload.id, null)
  assert.deepEqual(writes(view)[0].payload.specialtyRoleIds, [])
  assert.equal(view.dialogVisible.value, false)
  assert.equal(view.requests.filter(request => request.method === 'list').length, 1)
  assert.deepEqual(view.events, ['patient-specialty-changed'])
})

test('Edit preserves the unmasked source and the patient actual specialty selection', async t => {
  const view = setup(t), original = patient(202, { emergencyPhone: '00000000001', medicalHistory: 'Synthetic history' })
  view.patients.value = [original]
  const masked = view.displayPatients.value[0]
  assert.notEqual(masked.phone, original.phone)
  await view.showEditDialog(masked)
  assert.equal(view.form.phone, original.phone)
  assert.equal(view.form.idCard, original.idCard)
  assert.equal(view.form.address, original.address)
  assert.deepEqual(view.form.specialtyRoleIds, [101])
  view.form.specialtyRoleIds = [102]
  await view.handleSave()
  const request = writes(view)[0]
  assert.equal(request.method, 'update')
  assert.equal(request.payload.id, 202)
  assert.equal(request.payload.phone, original.phone)
  assert.deepEqual(request.payload.specialtyRoleIds, [102])
})

test('cancelled Add validation cannot submit the replacement Edit while its selections load', async t => {
  const validation = deferred(), selected = deferred()
  const view = setup(t, { getPatientSpecialtyRoles: () => selected.promise })
  await readyAdd(view)
  view.formRef.value = { validate: () => validation.promise }
  const saving = view.handleSave()
  view.dialogVisible.value = false
  const editing = view.showEditDialog(patient(202))
  validation.resolve(true); await saving
  assert.equal(writes(view).length, 0)
  assert.equal(view.dialogVisible.value, true)
  assert.equal(view.specialtyReady.value, false)
  selected.resolve(ok([101])); await editing
})

test('repeated Save locks before validation and dispatches only one create request', async t => {
  const validation = deferred(), response = deferred(), payloads = []
  let validations = 0
  const view = setup(t, { savePatient: payload => { payloads.push(payload); return response.promise } })
  await readyAdd(view)
  view.formRef.value = { validate: () => { validations++; return validation.promise } }
  const first = view.handleSave(), second = view.handleSave()
  assert.equal(validations, 1)
  validation.resolve(true); await tick()
  await view.handleSave()
  assert.equal(payloads.length, 1)
  response.resolve(ok('Synthetic success')); await Promise.all([first, second])
  assert.equal(view.dialogVisible.value, false)
})

test('the originating form freezes through validation and save, while a replacement draft is editable', async t => {
  const validation = deferred(), response = deferred()
  const view = setup(t, { savePatient: () => response.promise })
  await readyAdd(view)
  view.formRef.value = { validate: () => validation.promise }
  const saving = view.handleSave()
  assert.equal(view.controls().form.disabled, true)
  assert.equal(await specialtyDisabled(view.controls()), true, 'specialty selection freezes during validation')
  assert.equal(Boolean(view.controls().save.disabled || view.controls().save.loading), true)
  validation.resolve(true); await tick()
  assert.equal(view.controls().form.disabled, true)
  assert.equal(await specialtyDisabled(view.controls()), true, 'specialty selection freezes during the request')
  view.dialogVisible.value = false; view.showAddDialog()
  assert.equal(Boolean(view.controls().form.disabled), false)
  assert.equal(await specialtyDisabled(view.controls()), false, 'replacement specialty selection remains editable')
  response.resolve(ok('Synthetic success')); await saving
})

test('save captures its operation and a detached payload before awaiting validation', async t => {
  const validation = deferred(), response = deferred(), payloads = []
  const view = setup(t, { savePatient: payload => { payloads.push(payload); return response.promise } })
  await readyAdd(view)
  view.form.specialtyRoleIds = [101]
  view.formRef.value = { validate: () => validation.promise }
  const saving = view.handleSave()
  // Even a programmatic mutation must not change the in-flight request snapshot.
  view.form.name = 'Later programmatic value'; view.form.specialtyRoleIds.push(102)
  validation.resolve(true); await tick()
  assert.equal(payloads[0].name, 'Synthetic Add A')
  assert.deepEqual(payloads[0].specialtyRoleIds, [101])
  view.form.name = 'Another later value'; view.form.specialtyRoleIds.splice(0)
  assert.equal(payloads[0].name, 'Synthetic Add A')
  assert.deepEqual(payloads[0].specialtyRoleIds, [101])
  response.resolve(ok('Synthetic success')); await saving
})

test('same-patient close and reopen rejects older selections after the newer user choice', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const view = setup(t, { getPatientSpecialtyRoles: () => ++calls === 1 ? first.promise : second.promise })
  const oldOpen = view.showEditDialog(patient(202)); view.dialogVisible.value = false
  const newOpen = view.showEditDialog(patient(202))
  second.resolve(ok([102])); await newOpen
  view.form.specialtyRoleIds = [101, 102]
  first.resolve(ok([101])); await oldOpen
  assert.deepEqual(view.form.specialtyRoleIds, [101, 102])
  assert.equal(view.specialtyReady.value, true)
})

for (const failure of [false, true]) {
  test(`stale selection ${failure ? 'failure' : 'completion'} cannot clear a newer loading state or show feedback`, async t => {
    const first = deferred(), second = deferred(); let calls = 0
    const view = setup(t, { getPatientSpecialtyRoles: () => ++calls === 1 ? first.promise : second.promise })
    const oldOpen = view.showEditDialog(patient(201)); view.dialogVisible.value = false
    const newOpen = view.showEditDialog(patient(202))
    if (failure) first.reject(new Error('Synthetic old failure')); else first.resolve(ok([101]))
    await oldOpen
    assert.equal(view.specialtyLoading.value, true)
    assert.equal(view.specialtyReady.value, false)
    assert.equal(view.messages.length, 0)
    second.resolve(ok([102])); await newOpen
    assert.deepEqual(view.form.specialtyRoleIds, [102])
  })
}

test('mounted catalog preload cannot enable Edit Save before the selected roles arrive', async t => {
  const preloaded = deferred(), editingRoles = deferred(), selected = deferred(); let calls = 0
  const view = setup(t, { getSpecialtyRoles: () => ++calls === 1 ? preloaded.promise : editingRoles.promise, getPatientSpecialtyRoles: () => selected.promise })
  view.mount()
  const editing = view.showEditDialog(patient(202))
  preloaded.resolve(ok(catalog)); await tick()
  assert.equal(view.specialtyReady.value, false)
  assert.equal(view.specialtyLoading.value, true)
  await view.handleSave(); assert.equal(writes(view).length, 0)
  editingRoles.resolve(ok(catalog)); await tick()
  assert.equal(view.specialtyReady.value, false)
  assert.equal(view.controls().save.disabled, true)
  assert.equal(view.controls().specialties.disabled, true)
  selected.resolve(ok([102])); await editing
  assert.equal(view.specialtyReady.value, true)
  assert.deepEqual(view.form.specialtyRoleIds, [102])
  await view.handleSave(); assert.deepEqual(writes(view)[0].payload.specialtyRoleIds, [102])
})

for (const failure of [false, true]) {
  test(`stale catalog ${failure ? 'failure' : 'success'} cannot change a replacement draft readiness or choices`, async t => {
    const oldCatalog = deferred(), newCatalog = deferred(); let calls = 0
    const view = setup(t, { getSpecialtyRoles: () => ++calls === 1 ? oldCatalog.promise : newCatalog.promise })
    view.showAddDialog(); view.dialogVisible.value = false
    const editing = view.showEditDialog(patient(202))
    newCatalog.resolve(ok([catalog[1]])); await editing
    view.form.specialtyRoleIds = [102]
    if (failure) oldCatalog.reject(new Error('Synthetic old failure')); else oldCatalog.resolve(ok([catalog[0]]))
    await tick()
    assert.deepEqual(view.specialtyRoles.value.map(role => role.id), [102])
    assert.deepEqual(view.form.specialtyRoleIds, [102])
    assert.equal(view.specialtyReady.value, true)
    assert.equal(view.messages.length, 0)
  })
}

for (const api of ['getSpecialtyRoles', 'getPatientSpecialtyRoles']) {
  test(`${api} failure blocks an empty overwrite and allows a deliberate reopen retry`, async t => {
    let calls = 0
    const view = setup(t, { [api]: async () => { if (++calls === 1) throw new Error('Synthetic offline'); return ok(api === 'getSpecialtyRoles' ? catalog : [102]) } })
    await view.showEditDialog(patient(202)); await view.handleSave()
    assert.equal(writes(view).length, 0)
    assert.equal(view.specialtyReady.value, false)
    assert.equal(view.messages.filter(message => message.level === 'error').length, 1)
    view.dialogVisible.value = false
    await view.showEditDialog(patient(202)); await view.handleSave()
    assert.equal(writes(view).length, 1)
    assert.deepEqual(writes(view)[0].payload.specialtyRoleIds, api === 'getSpecialtyRoles' ? [101] : [102])
  })
}

for (const replacement of ['add', 'same-patient', 'other-patient']) {
  test(`old save success preserves the newer ${replacement} draft without stale feedback or refresh`, async t => {
    const response = deferred()
    const view = setup(t, { updatePatient: () => response.promise })
    await view.showEditDialog(patient(201))
    const saving = view.handleSave(); await tick()
    view.dialogVisible.value = false
    if (replacement === 'add') view.showAddDialog()
    else await view.showEditDialog(patient(replacement === 'same-patient' ? 201 : 202))
    view.form.name = 'Synthetic new draft'; view.form.specialtyRoleIds = [102]
    response.resolve(ok('Synthetic old success')); await saving
    assert.equal(view.dialogVisible.value, true)
    assert.equal(view.form.name, 'Synthetic new draft')
    assert.deepEqual(view.form.specialtyRoleIds, [102])
    assert.equal(view.messages.length, 0)
    assert.equal(view.requests.filter(request => request.method === 'list').length, 0)
    assert.deepEqual(view.events, [])
  })
}

test('an old save failure does not unlock or report an error over a newer in-flight save', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const view = setup(t, { savePatient: () => ++calls === 1 ? first.promise : second.promise })
  await readyAdd(view)
  const oldSave = view.handleSave(); await tick()
  view.dialogVisible.value = false; view.showAddDialog(); view.form.name = 'Synthetic B'
  const newSave = view.handleSave(); await tick()
  assert.equal(calls, 2)
  first.reject(new Error('Synthetic old failure')); await oldSave
  assert.equal(view.controls().form.disabled, true)
  assert.equal(view.messages.length, 0)
  await view.handleSave(); assert.equal(calls, 2)
  second.resolve(ok('Synthetic new success')); await newSave
  assert.equal(view.dialogVisible.value, false)
})

for (const outcome of ['validation-reject', 'validation-false', 'save-reject', 'save-failure']) {
  test(`${outcome} retains the draft and permits a deliberate retry`, async t => {
    let validations = 0, saves = 0
    const view = setup(t, { savePatient: async () => {
      saves++
      if (saves === 1 && outcome === 'save-reject') throw new Error('Synthetic save failed')
      if (saves === 1 && outcome === 'save-failure') return { code: 500, msg: 'Synthetic save failed' }
      return ok('Synthetic success')
    } })
    await readyAdd(view)
    view.formRef.value = { validate: async () => {
      validations++
      if (validations === 1 && outcome === 'validation-reject') throw { name: 'Synthetic validation error' }
      return !(validations === 1 && outcome === 'validation-false')
    } }
    await view.handleSave()
    assert.equal(view.dialogVisible.value, true)
    assert.equal(view.form.name, 'Synthetic Add A')
    assert.equal(Boolean(view.controls().form.disabled), false)
    if (outcome.startsWith('validation')) assert.equal(saves, 0)
    else assert.equal(view.messages.filter(message => message.level === 'error').length, 1)
    await view.handleSave()
    assert.equal(view.dialogVisible.value, false)
    assert.equal(saves, outcome.startsWith('validation') ? 1 : 2)
  })
}

for (const phase of ['validation', 'selected-roles', 'catalog', 'save']) {
  test(`unmount invalidates a pending ${phase} operation`, async t => {
    const pending = deferred()
    const view = setup(t, {
      ...(phase === 'selected-roles' ? { getPatientSpecialtyRoles: () => pending.promise } : {}),
      ...(phase === 'catalog' ? { getSpecialtyRoles: () => pending.promise } : {}),
      ...(phase === 'save' ? { savePatient: () => pending.promise } : {})
    })
    let work
    if (phase === 'selected-roles') work = view.showEditDialog(patient(202))
    else if (phase === 'catalog') work = view.loadSpecialtyRoles()
    else {
      await readyAdd(view)
      if (phase === 'validation') view.formRef.value = { validate: () => pending.promise }
      work = view.handleSave(); await tick()
    }
    view.unmount()
    pending.resolve(phase === 'validation' ? true : ok(phase === 'catalog' ? catalog : phase === 'selected-roles' ? [102] : 'Synthetic success'))
    await work
    assert.equal(writes(view).length, 0)
    assert.equal(view.messages.length, 0)
    assert.deepEqual(view.events, [])
    assert.equal(view.requests.filter(request => request.method === 'list').length, 0)
    if (phase === 'selected-roles') assert.deepEqual(view.form.specialtyRoleIds, [])
    if (phase === 'catalog') assert.deepEqual(view.specialtyRoles.value, [])
  })
}
