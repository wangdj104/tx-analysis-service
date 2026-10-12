import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, nextTick, effectScope } from 'vue'
import { parse } from '@vue/compiler-sfc'
import { AUTH_STORAGE_KEYS } from '../src/utils/authSession.js'
const read = name => fs.readFileSync(new URL('../src/' + name, import.meta.url), 'utf8')
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function entry(t, kind = 'ORDER', save = async () => {}) {
  const props = reactive({ modelValue: false, kind, row: null, patientId: 11, patientName: 'Synthetic patient', medications: [] })
  const mounted = [], unmounted = [], guards = [], emissions = [], requests = [], focus = [], scope = effectScope(), window = new EventTarget()
  const deps = { AUTH_STORAGE_KEYS, computed, reactive, ref, watch, nextTick, defineProps: () => props, defineEmits: () => (...args) => emissions.push(args),
    onMounted: fn => mounted.push(fn), onUnmounted: fn => unmounted.push(fn), onBeforeUnmount: fn => unmounted.push(fn),
    onBeforeRouteLeave: fn => guards.push(fn), onBeforeRouteUpdate: fn => guards.push(fn),
    localDateKey: () => '2026-10-11', ElMessage: { warning() {}, success() {}, error() {} }, window,
    saveCareItem: data => { requests.push(data); return save(data) } }
  const script = parse(read('components/CareEntryDialog.vue')).descriptor.scriptSetup.content.replace(/^import.*$/gm, '')
  const view = scope.run(() => new Function(...Object.keys(deps), script + '\nreturn {form,days,saving,submit,attach,lifecycle:typeof vCareEntryLifecycle==="undefined"?null:vCareEntryLifecycle,errors:typeof errors==="undefined"?null:errors,requestClose:typeof requestClose==="undefined"?null:requestClose,discardChanges:typeof discardChanges==="undefined"?null:discardChanges,keepEditing:typeof keepEditing==="undefined"?null:keepEditing,discardVisible:typeof discardVisible==="undefined"?null:discardVisible,titleInput:typeof titleInput==="undefined"?null:titleInput,timeInput:typeof timeInput==="undefined"?null:timeInput}')(...Object.values(deps)))
  mounted.forEach(fn => fn()); props.modelValue = true
  const element = {}; view.lifecycle?.mounted(element, { instance: { $: { appContext: { config: { globalProperties: { $router: { beforeEach: fn => { guards.push(fn); return () => {} } } } } } } } }); unmounted.push(() => view.lifecycle?.unmounted(element))
  t.after(() => { unmounted.forEach(fn => fn()); scope.stop() })
  return { ...view, props, emissions, requests, guards, window, focus }
}
function room(id, extra = {}) { return { id, patient_id: 11, patient_name: 'Synthetic patient', status: 'OPEN', can_message: true, can_close: true, mode: 'TEXT', messages: [], participants: [], ...extra } }
function consultation(t, overrides = {}, confirm = async () => {}) {
  const props = reactive({ patientId: 11, enabled: true, isDoctor: false, locale: import.meta.url.includes('/cn/') ? 'zh' : 'en' }), route = reactive({ path: '/care-journey', query: { tab: 'consultation' } })
  const mounted = [], unmounted = [], guards = [], window = Object.assign(new EventTarget(), { isSecureContext: false, setTimeout() {}, setInterval() {} }), scope = effectScope()
  const api = { listConsultations: async () => ({ data: [] }), listClinicians: async () => ({ data: [] }), listConsultationInvitees: async () => ({ data: [] }), listConsultationRecordOptions: async () => ({ data: [] }), getConsultation: async id => ({ data: room(id) }), ...overrides }
  const deps = { AUTH_STORAGE_KEYS, computed, reactive, ref, watch, nextTick, defineProps: () => props, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounted.push(fn),
    useRoute: () => route, useRouter: () => ({ replace: async target => { route.query = target.query }, beforeEach: fn => { guards.push(fn); return () => {} } }),
    localStorage: { getItem: () => '1' }, navigator: {}, window, api, ElMessage: { success() {}, error() {}, warning() {} }, ElMessageBox: { confirm, prompt: async () => ({ value: '' }) } }
  const script = parse(read('components/ConsultationWorkspace.vue')).descriptor.scriptSetup.content.replace(/^import.*$/gm, '').replace(/import\.meta\.env\.VITE_WEBRTC_ICE_SERVERS/g, "''")
  const view = scope.run(() => new Function(...Object.keys(deps), script + '\nreturn {draft,form,activeConsultation,activateConsultation,openConsultation,refreshActiveConsultation,resetContext,recordOptions,openingId,canSend}')(...Object.values(deps)))
  mounted.forEach(fn => fn())
  t.after(() => { unmounted.forEach(fn => fn()); scope.stop() })
  return { ...view, props, route, guards, window }
}
test('care entry required medication and effective date block an invalid prescription request', async t => {
  const view = entry(t); await nextTick()
  view.form.title = 'Synthetic prescription'; view.form.details.confirmed = true; view.form.details.startDate = null
  await view.submit()
  assert.equal(view.requests.length, 0)
  assert.ok(view.errors.value.medicationId)
  assert.ok(view.errors.value.startDate)
  view.form.details.medicationId = 17; await nextTick()
  assert.equal(view.errors.value.medicationId, undefined)
  assert.ok(view.errors.value.startDate)
})
test('care entry persistent title and time errors focus the first issue and remain until corrected', async t => {
  const view = entry(t, 'APPOINTMENT'); await nextTick()
  assert.ok(view.titleInput, 'title has a focus target')
  view.titleInput.value = { focus: () => view.focus.push('title') }; view.timeInput.value = { focus: () => view.focus.push('eventAt') }
  await view.submit(); await nextTick()
  assert.deepEqual(view.focus, ['title']); assert.ok(view.errors.value.title); assert.ok(view.errors.value.eventAt)
  view.form.title = 'Synthetic visit'; await nextTick(); assert.equal(view.errors.value.title, undefined); assert.ok(view.errors.value.eventAt)
  await view.submit(); await nextTick(); assert.deepEqual(view.focus, ['title', 'eventAt']); assert.equal(view.requests.length, 0)
})
test('care entry close keeps a dirty draft until explicit discard', async t => {
  const view = entry(t, 'QUESTION'); await nextTick(); view.form.title = 'Synthetic unsaved question'
  assert.equal(typeof view.requestClose, 'function')
  view.requestClose(); assert.equal(view.discardVisible.value, true); assert.equal(view.emissions.length, 0)
  view.keepEditing(); assert.equal(view.form.title, 'Synthetic unsaved question'); assert.equal(view.discardVisible.value, false)
  view.requestClose(); view.discardChanges(); assert.ok(view.emissions.some(item => item[0] === 'update:modelValue' && item[1] === false)); assert.equal(view.form.title, '')
})
test('care entry patient-context event and route guards retain dirty input; auth loss clears it immediately', async t => {
  const view = entry(t, 'QUESTION'); await nextTick(); view.form.title = 'Synthetic private question'
  const change = new Event('care-plan-before-context-change', { cancelable: true }); view.window.dispatchEvent(change)
  assert.equal(change.defaultPrevented, true); assert.ok(view.guards.length >= 1); assert.equal(await view.guards[0](), false)
  assert.equal(view.form.title, 'Synthetic private question')
  view.window.dispatchEvent(new Event('auth-session-cleared')); assert.equal(view.form.title, ''); assert.equal(view.discardVisible.value, false)
})
test('care entry cannot restore attachments or announce stale success after patient changes', async t => {
  const pending = deferred(), view = entry(t, 'QUESTION', () => pending.promise); await nextTick(); view.form.title = 'Synthetic first patient'
  const saving = view.submit(); view.props.patientId = 22; await nextTick(); pending.resolve({}); await saving
  assert.equal(view.form.title, ''); assert.equal(view.emissions.some(item => item[0] === 'saved'), false)
})
test('consultation keep-editing cancels chat switch without losing the source draft', async t => {
  const view = consultation(t, {}, async () => { throw 'cancel' }); await view.activateConsultation(room(1)); view.draft.content = 'Synthetic unsent message'
  await view.openConsultation(2)
  assert.equal(view.activeConsultation.value.id, 1); assert.equal(view.draft.content, 'Synthetic unsent message')
})
test('failed target consultation load preserves source room and unsent draft after discard choice', async t => {
  const view = consultation(t, { getConsultation: async id => { if (Number(id) === 2) throw new Error('Synthetic offline'); return { data: room(id) } } })
  await view.activateConsultation(room(1)); view.draft.content = 'Synthetic retained message'; await view.openConsultation(2)
  assert.equal(view.activeConsultation.value?.id, 1); assert.equal(view.draft.content, 'Synthetic retained message'); assert.equal(view.openingId.value, null)
})
test('consultation route and patient event protect drafts while logout and revoked permissions clear them', async t => {
  const view = consultation(t, {}, async () => { throw 'cancel' }); await view.activateConsultation(room(1)); view.draft.content = 'Synthetic private draft'
  assert.ok(view.guards.length); assert.equal(await view.guards[0]({ path: '/other', query: {} }, view.route), false)
  const change = new Event('care-plan-before-context-change', { cancelable: true }); view.window.dispatchEvent(change); assert.equal(change.defaultPrevented, true)
  view.window.dispatchEvent(new Event('auth-session-cleared')); assert.equal(view.draft.content, ''); assert.equal(view.activeConsultation.value, null)
  await view.activateConsultation(room(1)); view.draft.content = 'Synthetic revoked draft'; view.props.enabled = false; await nextTick(); assert.equal(view.draft.content, '')
})
test('consultation losing message access during refresh clears the unsent clinical text', async t => {
  const view = consultation(t, { getConsultation: async () => ({ data: room(1, { can_message: false }) }) }); await view.activateConsultation(room(1)); view.draft.content = 'Synthetic no longer authorized'
  await view.refreshActiveConsultation(); assert.equal(view.draft.content, ''); assert.equal(view.canSend.value, false)
})
test('Today places recording before plan tasks and folds history without changing stored statuses', () => {
  const { template, scriptSetup } = parse(read('views/CareCenter.vue')).descriptor
  const today = template.content.match(/<el-tab-pane[^>]+name="today"[\s\S]*?<\/el-tab-pane>/)[0]
  assert.ok(today.indexOf('class="quick-actions"') < today.indexOf('<PlanTaskList'))
  assert.match(today, /<details[^>]+class="care-history"/); assert.doesNotMatch(today, /<details[^>]+class="care-history"[^>]+open/)
  assert.match(scriptSetup.content, /pendingIntakes/); assert.match(scriptSetup.content, /completedIntakes/)
  assert.match(today, /statusText\(i.status\)/)
})
test('care summary shortcut targets the permitted summary tab and both save dialogs show patient identity', () => {
  const care = read('views/CareCenter.vue'), entrySource = read('components/CareEntryDialog.vue')
  assert.match(care, /\/family-health\?tab=summary/)
  assert.match(care, /:patient-name="selectedPatientName"/)
  const quick = care.match(/<el-dialog v-model="quickVisible"[\s\S]*?<\/el-dialog>/)[0]
  assert.match(quick, /selectedPatientName/); assert.match(quick, /patientId/); assert.match(entrySource, /patientName/)
})
test('required care entry controls have persistent accessible error associations', () => {
  const text = read('components/CareEntryDialog.vue')
  for (const field of ['title', 'eventAt', 'medicationId', 'startDate', 'confirmed']) {
    assert.match(text, new RegExp('care-entry-error-' + field)); assert.match(text, new RegExp('errors\\.' + field))
  }
  assert.match(text, /<input[^>]*type="checkbox"[^>]*v-model="form.details.confirmed"/);
  assert.match(text, /aria-invalid/); assert.match(text, /aria-describedby/); assert.match(text, /:before-close="requestClose"/)
})

test('Family selection cards and dose and stock saves distinguish authorized patient records', () => {
  const care = read('views/CareCenter.vue')
  const cards = care.match(/<section v-if="mode==='FAMILY'"[\s\S]*?<\/section>/)[0]
  assert.match(cards, /p.patient.name/); assert.match(cards, /Record ID|档案 ID/); assert.match(cards, /\{\{\s*p.patient.id\s*\}\}/)
  for (const name of ['intakeVisible', 'stockVisible']) {
    const dialog = care.match(new RegExp('<el-dialog v-model="' + name + '"[\\s\\S]*?<\\/el-dialog>'))[0]
    assert.match(dialog, /selectedPatientName/); assert.match(dialog, /Record ID|档案 ID/); assert.match(dialog, /\{\{\s*patientId\s*\}\}/)
  }
})

for (const key of ['token','userId',null]) test(`care entry ${key ?? 'clear'} auth storage boundary invalidates a pending save`, async t => {
 const pending=deferred(),view=entry(t,'QUESTION',()=>pending.promise);await nextTick();view.form.title='Synthetic prior-account question'
 const saving=view.submit();view.window.dispatchEvent(Object.assign(new Event('storage'),{key}));pending.resolve({});await saving
 assert.equal(view.form.title,'');assert.equal(view.emissions.some(item=>item[0]==='saved'),false);assert.equal(view.saving.value,false)
})
test('care entry storage boundary invalidates a pending attachment conversion', async t => {
 const previous=globalThis.FileReader;let reader
 globalThis.FileReader=class {constructor(){reader=this}readAsDataURL(){}}
 t.after(()=>{globalThis.FileReader=previous})
 const view=entry(t,'ORDER');await nextTick();const converting=view.attach({target:{files:[{name:'synthetic.png',size:10}],value:'synthetic'}})
 view.window.dispatchEvent(Object.assign(new Event('storage'),{key:'token'}));reader.result='data:image/png;base64,U3ludGhldGlj';reader.onload();await converting
 assert.equal(view.form.details.attachments,undefined);assert.equal(view.form.title,'')
})
test('care entry unrelated storage does not discard valid drafts', async t => {
 const view=entry(t,'QUESTION');await nextTick();view.form.title='Synthetic current draft'
 view.window.dispatchEvent(Object.assign(new Event('storage'),{key:'care-senior'}));assert.equal(view.form.title,'Synthetic current draft')
})
