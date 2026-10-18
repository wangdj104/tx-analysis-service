<template>
  <el-dialog
    :model-value="modelValue"
    :title="titles[form.kind]"
    width="min(760px, 95vw)"
    destroy-on-close
    :before-close="requestClose"
    @update:model-value="onVisibilityChange"
  >
    <div v-care-entry-lifecycle>
    <p v-if="questionSaveUnknown" role="alert">Save outcome not confirmed. Do not submit again. Review the earlier question list to check whether it was saved; absence does not prove failure.</p>
    <p v-if="saving" role="status" aria-live="polite">Saving…</p>
    <p class="entry-patient">Current patient: <strong>{{ patientName || '—' }}</strong> · Record ID: {{ patientId }}</p>
    <section v-if="discardVisible" class="draft-warning" role="alert">
      <p>You have unsaved changes. Keep editing retains them for this patient. Discard clears this draft; a save already sent may still complete.</p>
      <div class="draft-warning-actions">
        <el-button @click="keepEditing">Keep editing</el-button>
        <el-button v-if="!managedQuestion || !managedFlow.locked()" type="danger" @click="discardChanges">Discard changes</el-button>
      </div>
    </section>
    <AppointmentFollowUpPanel v-if="managedAppointment" ref="appointmentPanel" :patient-id="patientId" source-kind="CARE_ITEM_APPOINTMENT" :source-id="row.id" :active="modelValue" @changed="emit('saved')" />
    <section v-if="managedQuestion" class="vq">
      <p role="status" aria-live="polite">{{ qt(managedFlow.state.availability) }}</p>
      <p v-if="managedFlow.locked()" role="alert">{{ qt('uncertain') }}</p>
      <template v-if="!managedFlow.state.dialog">
        <p>{{ qt('singleOrdinaryAction') }}</p>
        <article v-if="managedFlow.state.detail"><h3>{{ managedFlow.state.detail.title }}</h3><p>{{ managedFlow.state.detail.description }}</p><p>{{ qt(managedFlow.state.detail.state) }}</p><p>{{ qt('recordedAnswer') }}: {{ managedFlow.state.detail.recordedAnswer || qt('noAnswer') }}</p><p>{{ qt('followUp') }}: {{ managedFlow.state.detail.followUp || qt('noNext') }}</p></article>
        <div class="vq-actions"><button v-for="operation in ['EDIT','RECORD_ANSWER']" v-show="managedFlow.allowed(operation)" :key="operation" type="button" @click="beginOrdinaryAction(operation)">{{ qt(operation) }}</button><button type="button" @click="loadManagedQuestion">{{ qt('refreshDecision') }}</button></div><p>{{ qt('ordinaryReadOnly') }}</p>
      </template>
      <VisitQuestionActionDialog :flow="managedFlow" embedded ordinary-editor :appointments="appointments" />
    </section>
    <section v-if="Object.keys(errors).length" class="entry-errors" role="alert" aria-live="polite"><strong>Correct these fields before saving</strong><ul><li v-for="(message, field) in errors" :key="field">{{ message }}</li></ul></section>
    <el-form v-if="!managedQuestion && !managedAppointment" label-position="top" :model="form" class="care-entry" :disabled="saving || questionSaveUnknown">
      <el-form-item label="Title" required :error="errors.title">
        <el-input v-model="form.title" ref="titleInput" :aria-invalid="!!errors.title" :aria-describedby="errors.title ? 'care-entry-error-title' : undefined" maxlength="200" placeholder="For example: nephrology follow-up, dizziness, question for clinician" />
      <template #error="{ error }"><p id="care-entry-error-title" class="field-error">{{ error }}</p></template></el-form-item>

      <template v-if="form.kind === 'APPOINTMENT'">
        <div class="entry-grid">
          <el-form-item label="Appointment time" required :error="errors.eventAt"><div class="care-required-control" data-care-required-field="eventAt" v-care-field-error="{ error: errors.eventAt, id: 'care-entry-error-eventAt' }"><el-date-picker v-model="form.eventAt" ref="timeInput" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></div><template #error="{ error }"><p id="care-entry-error-eventAt" class="field-error">{{ error }}</p></template></el-form-item>
          <el-form-item label="Reminder time"><el-date-picker v-model="form.notifyAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="Optional" /></el-form-item>
          <el-form-item label="Hospital"><el-input v-model="form.details.hospital" /></el-form-item>
          <el-form-item label="Department"><el-input v-model="form.details.department" /></el-form-item>
          <el-form-item label="Clinician"><el-input v-model="form.details.doctor" /></el-form-item>
          <el-form-item label="Companion / owner"><el-select v-model="form.assignedUserId" clearable><el-option v-for="m in members" :key="m.userId" :value="m.userId" :label="m.name" /></el-select></el-form-item>
        </div>
        <el-form-item label="Tests and preparation"><el-input v-model="form.details.preparation" type="textarea" placeholder="What to bring, fasting requirements, where to check in, and other instructions" /></el-form-item>
        <el-form-item label="Related report"><el-select v-model="form.details.reportId" clearable filterable><el-option v-for="r in reports" :key="r.id" :value="r.id" :label="`${r.recordDate} ${r.hospitalName || ''} ${r.recordType || ''}`" /></el-select></el-form-item>
        <el-form-item label="Next follow-up"><el-date-picker v-model="form.details.nextAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="Optional; a follow-up can be created after completion" /></el-form-item>
      </template>

      <template v-if="form.kind === 'ORDER'">
        <el-alert title="Enter this exactly as prescribed. Saving creates a new version and synchronizes reminders on the effective date; prior versions remain in history." :closable="false" type="info" />
        <div class="entry-grid">
          <el-form-item label="Medication" required :error="errors.medicationId"><div class="care-required-control" data-care-required-field="medicationId" v-care-field-error="{ error: errors.medicationId, id: 'care-entry-error-medicationId' }"><el-select v-model="form.details.medicationId" ref="medicationInput" filterable><el-option v-for="m in medications" :key="m.id" :value="m.id" :label="m.drugName" /></el-select></div><template #error="{ error }"><p id="care-entry-error-medicationId" class="field-error">{{ error }}</p></template></el-form-item>
          <el-form-item label="Action"><el-radio-group v-model="form.details.action"><el-radio value="CHANGE">Start / change</el-radio><el-radio value="STOP">Stop</el-radio></el-radio-group></el-form-item>
          <el-form-item label="Effective date" required :error="errors.startDate"><div class="care-required-control" data-care-required-field="startDate" v-care-field-error="{ error: errors.startDate, id: 'care-entry-error-startDate' }"><el-date-picker v-model="form.details.startDate" ref="dateInput" value-format="YYYY-MM-DD" /></div><template #error="{ error }"><p id="care-entry-error-startDate" class="field-error">{{ error }}</p></template></el-form-item>
          <el-form-item v-if="form.details.action !== 'STOP'" label="End date"><el-date-picker v-model="form.details.endDate" clearable value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="Prescribing clinician"><el-input v-model="form.details.doctor" /></el-form-item>
          <el-form-item label="Quantity unit"><el-input v-model="form.details.unit" placeholder="Tablet, capsule, mL — match the inventory unit" /></el-form-item>
        </div>
        <template v-if="form.details.action !== 'STOP'">
          <el-form-item label="Days"><el-checkbox-group v-model="days"><el-checkbox v-for="(d, i) in weekdays" :key="i" :value="String(i + 1)">{{ d }}</el-checkbox></el-checkbox-group></el-form-item>
          <el-form-item label="Dose times and quantities" required>
            <div class="dose-list">
              <div v-for="(dose, i) in form.details.doses" :key="i" class="dose-row">
                <el-time-picker v-model="dose.time" value-format="HH:mm" format="HH:mm" />
                <el-input-number v-model="dose.quantity" :min="0.001" :precision="3" />
                <span>{{ form.details.unit }}</span>
                <el-button :disabled="form.details.doses.length === 1" @click="form.details.doses.splice(i, 1)">Delete</el-button>
              </div>
              <el-button @click="form.details.doses.push({ time: '20:00', quantity: 1 })">Add dose</el-button>
            </div>
          </el-form-item>
        </template>
        <el-form-item label="Prescription instructions / reason for change"><el-input v-model="form.details.instructions" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="Prescription image or PDF">
          <input type="file" accept="image/*,application/pdf" @change="attach" />
          <div v-for="(a, i) in form.details.attachments" :key="i" class="attachment-row"><a :href="a.dataUrl" :download="a.name">{{ a.name }}</a><el-button link type="danger" @click="form.details.attachments.splice(i, 1)">Remove</el-button></div>
        </el-form-item>
        <label class="clinical-confirmation"><input type="checkbox" v-model="form.details.confirmed" ref="confirmationInput" :aria-invalid="!!errors.confirmed" :aria-describedby="errors.confirmed ? 'care-entry-error-confirmed' : undefined" /> <span>I have checked the medication, dose, schedule, and effective date against the clinician's prescription.</span></label>
        <p v-if="errors.confirmed" id="care-entry-error-confirmed" class="field-error">{{ errors.confirmed }}</p>
      </template>

      <template v-if="form.kind === 'SYMPTOM'">
        <el-form-item label="Time of onset" required :error="errors.eventAt"><div class="care-required-control" data-care-required-field="eventAt" v-care-field-error="{ error: errors.eventAt, id: 'care-entry-error-eventAt' }"><el-date-picker v-model="form.eventAt" ref="timeInput" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></div><template #error="{ error }"><p id="care-entry-error-eventAt" class="field-error">{{ error }}</p></template></el-form-item>
        <el-form-item label="Severity (0 = none, 10 = worst)"><el-slider v-model="form.details.severity" :min="0" :max="10" show-stops show-input /></el-form-item>
        <div class="entry-grid">
          <el-form-item label="Duration"><el-input v-model="form.details.duration" placeholder="For example: about 20 minutes" /></el-form-item>
          <el-form-item label="Current status"><el-select v-model="form.details.progress"><el-option label="Ongoing" value="ONGOING" /><el-option label="Improved" value="IMPROVED" /><el-option label="Resolved" value="RESOLVED" /></el-select></el-form-item>
        </div>
        <el-form-item label="What was happening when it started"><el-input v-model="form.details.context" type="textarea" /></el-form-item>
        <el-form-item label="What was tried and whether it helped"><el-input v-model="form.details.response" type="textarea" /></el-form-item>
      </template>

      <template v-if="form.kind === 'QUESTION'">
        <p>An ordinary entry records what was reported; it is not a clinician-authored publication.</p>
        <el-form-item label="Appointment"><el-select v-model="form.details.appointmentId" clearable><el-option v-for="a in appointments" :key="a.id" :value="a.id" :label="`${a.eventAt || ''} ${a.title}`" /></el-select></el-form-item>
        <el-form-item label="Question details"><el-input v-model="form.details.description" type="textarea" /></el-form-item>
        <el-form-item label="Recorded answer"><el-input v-model="form.details.answer" type="textarea" /></el-form-item>
        <el-form-item label="Follow-up action"><el-input v-model="form.details.followUp" type="textarea" /></el-form-item>
      </template>

      <template v-if="form.kind === 'HANDOVER'">
        <el-form-item label="Handover details"><el-input v-model="form.details.note" type="textarea" :rows="4" placeholder="For example: reports are in the living-room drawer; remember to bring them to the appointment" /></el-form-item>
        <div class="entry-grid">
          <el-form-item label="Assigned to"><el-select v-model="form.assignedUserId" clearable><el-option v-for="m in members" :key="m.userId" :value="m.userId" :label="m.name" /></el-select></el-form-item>
          <el-form-item label="Due time"><el-date-picker v-model="form.eventAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
          <el-form-item label="Reminder time"><el-date-picker v-model="form.notifyAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
        </div>
      </template>
    </el-form>
    </div>
    <template #footer><el-button v-if="!managedQuestion || !managedFlow.state.dialog" @click="requestClose()">Cancel</el-button><el-button v-if="!managedQuestion && !managedAppointment" type="primary" :loading="saving" :disabled="questionSaveUnknown" @click="submit">Save</el-button></template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { saveCareItem } from '@/api/care'
import * as questionApi from '@/api/visitQuestion'
import { useVisitQuestion } from '@/composables/useVisitQuestion'
import { questionText as qt } from '@/utils/visitQuestionCopy'
import AppointmentFollowUpPanel from '@/components/visit-follow-up/AppointmentFollowUpPanel.vue'
import VisitQuestionActionDialog from '@/components/visit-question/VisitQuestionActionDialog.vue'
import { localDateKey } from '@/utils/familyHealth'
import { AUTH_STORAGE_KEYS, captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'

const props = defineProps({ modelValue: Boolean, kind: String, row: Object, patientId: [Number, String], patientName: String, members: { type: Array, default: () => [] }, medications: { type: Array, default: () => [] }, reports: { type: Array, default: () => [] }, appointments: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue', 'saved'])
const form = reactive({ details: {} })
const appointmentPanel = ref(null)
const managedAppointment = computed(() => props.kind === 'APPOINTMENT' && props.row?.visitFollowUpManaged === true)
const managedQuestion = computed(() => props.kind === 'QUESTION' && Number(props.row?.questionProjection?.questionVersion) > 0)
const managedFlow = useVisitQuestion(computed(() => props.patientId), { api: { ...questionApi, command: questionApi.ordinaryCommand } })
watch(() => managedFlow.state.commandPhase, phase => { if (managedQuestion.value && phase === 'saved') { clearSensitive(); emit('saved'); emit('update:modelValue', false) } })
watch(() => managedFlow.state.availability, phase => { if (managedQuestion.value && phase === 'denied') sessionCleared() }, { flush: 'sync' })
async function loadManagedQuestion() {
  const epoch = draftEpoch, row = props.row
  const loaded = await managedFlow.loadCapabilities()
  if (epoch !== draftEpoch || !props.modelValue || row !== props.row || loaded.status !== 'loaded' || managedFlow.state.availability !== 'READY') return
  await managedFlow.open(row.id)
}
function beginOrdinaryAction(operation) { managedFlow.begin(operation) }
const saving = ref(false)
const days = ref([])
const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
const titles = { APPOINTMENT: 'Appointment / follow-up', ORDER: 'Prescription & medication plan', SYMPTOM: 'Record symptom', QUESTION: 'Question for clinician', HANDOVER: 'Care handover' }

const errors = ref({}), attempted = ref(false), discardVisible = ref(false)
const titleInput = ref(null), timeInput = ref(null), medicationInput = ref(null), dateInput = ref(null), confirmationInput = ref(null)
let saveController = null
const questionSaveUnknown = ref(false)
let baseline = '', draftEpoch = 0, pendingClose = null
const snapshot = () => JSON.stringify({ form, days: days.value })
const isDirty = () => props.modelValue && (managedAppointment.value ? appointmentPanel.value?.dirty() || appointmentPanel.value?.locked() : managedQuestion.value ? managedFlow.dirty() : saving.value || snapshot() !== baseline)
function validationErrors() {
  const result = {}
  if (!String(form.title || '').trim()) result.title = 'Enter a title'
  if (['APPOINTMENT', 'SYMPTOM'].includes(form.kind) && !form.eventAt) result.eventAt = 'Select a date and time'
  if (form.kind === 'ORDER') {
    if (!form.details.medicationId) result.medicationId = 'Select a medication'
    if (!form.details.startDate) result.startDate = 'Select an effective date'
    if (!form.details.confirmed) result.confirmed = "Confirm that the medication plan matches the clinician’s prescription"
  }
  return result
}
watch(form, () => { if (attempted.value) errors.value = validationErrors() }, { deep: true, flush: 'sync' })
function clearSensitive() {
  draftEpoch++; managedFlow.clear(); saveController?.abort(); saveController=null; questionSaveUnknown.value=false; saving.value = false; pendingClose = null; discardVisible.value = false; attempted.value = false; errors.value = {}
  Object.keys(form).forEach(key => delete form[key]); Object.assign(form, { title: '', details: {} }); days.value = []
  baseline = snapshot()
}
function requestClose(done) {
  if (managedAppointment.value && appointmentPanel.value?.locked()) return false
  if (isDirty()) { pendingClose = typeof done === 'function' ? done : null; discardVisible.value = true; return false }
  clearSensitive(); if (typeof done === 'function') done(); else emit('update:modelValue', false)
  return true
}
function onVisibilityChange(open) { if (!open) requestClose(); else emit('update:modelValue', true) }
function keepEditing() { pendingClose = null; discardVisible.value = false }
function discardChanges() { if (managedAppointment.value && appointmentPanel.value?.locked()) return; if (managedQuestion.value && managedFlow.locked()) return; const close = pendingClose; clearSensitive(); if (close) close(); emit('update:modelValue', false) }
function protectNavigation() { if (!isDirty()) return true; pendingClose = null; discardVisible.value = true; return false }
function contextChange(event) { if (!event.defaultPrevented && !protectNavigation()) event.preventDefault() }
function beforeUnload(event) { if (isDirty()) { event.preventDefault(); event.returnValue = '' } }
function sessionCleared() { clearSensitive(); emit('update:modelValue', false) }
function sessionStorageChanged(event) { if (event.key == null || AUTH_STORAGE_KEYS.includes(event.key)) sessionCleared() }
// Element Plus date/select attributes land on wrapper elements. Associate the
// persistent error with the actual focusable control while preserving other help.
function syncFieldError(element, binding) {
  const input = element.querySelector('input:not([type="hidden"]), textarea, [role="combobox"]')
  if (!input) return
  const { error, id } = binding.value
  const describedBy = String(input.getAttribute('aria-describedby') || '').split(/\s+/).filter(value => value && value !== id)
  if (error) describedBy.push(id)
  if (describedBy.length) input.setAttribute('aria-describedby', describedBy.join(' ')); else input.removeAttribute('aria-describedby')
  if (error) input.setAttribute('aria-invalid', 'true'); else input.removeAttribute('aria-invalid')
}
const vCareFieldError = {
  mounted(element, binding) {
    const state = { binding, active: true, observer: null }
    element.__careFieldError = state; syncFieldError(element, binding)
    if (typeof MutationObserver !== 'undefined') {
      state.observer = new MutationObserver(() => { if (state.active) syncFieldError(element, state.binding) })
      state.observer.observe(element, { childList: true, subtree: true })
    }
  },
  updated(element, binding) { element.__careFieldError.binding = binding; syncFieldError(element, binding) },
  unmounted(element, binding) {
    const state = element.__careFieldError; if (state) { state.active = false; state.observer?.disconnect() }
    syncFieldError(element, { value: { ...binding.value, error: null } })
  }
}
// Scope protection to the mounted dialog body, including Escape and outside dismissal.
const vCareEntryLifecycle = {
  mounted(element, binding) {
    const removeGuard = binding.instance.$.appContext.config.globalProperties.$router.beforeEach(protectNavigation)
    window.addEventListener('care-plan-before-context-change', contextChange)
    window.addEventListener('beforeunload', beforeUnload)
    window.addEventListener('auth-session-cleared', sessionCleared)
    window.addEventListener('auth-session-changed', sessionCleared)
    window.addEventListener('storage', sessionStorageChanged)
    element.__careEntryCleanup = () => { removeGuard(); window.removeEventListener('care-plan-before-context-change', contextChange); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('auth-session-cleared', sessionCleared); window.removeEventListener('auth-session-changed', sessionCleared); window.removeEventListener('storage', sessionStorageChanged); clearSensitive() }
  },
  unmounted(element) { element.__careEntryCleanup?.() }
}
watch(() => props.patientId, sessionCleared, { flush: 'sync' })

watch([() => props.modelValue, () => props.row], ([open]) => {
  if (!open) { clearSensitive(); return }
  draftEpoch++; attempted.value = false; errors.value = {}; discardVisible.value = false; pendingClose = null
  Object.keys(form).forEach(k => delete form[k])
  Object.assign(form, { kind: props.kind, title: '', eventAt: null, notifyAt: null, assignedUserId: null, details: { startDate: localDateKey(), endDate: null, action: 'CHANGE', unit: 'tablet', repeatDays: '1,2,3,4,5,6,7', doses: [{ time: '08:00', quantity: 1 }], attachments: [], confirmed: false, severity: 3, progress: 'ONGOING' } }, !managedQuestion.value && props.row ? JSON.parse(JSON.stringify(props.row)) : {})
  if (props.kind === 'ORDER') {
    delete form.id
    form.details.confirmed = false
  }
  days.value = String(form.details.repeatDays || '1,2,3,4,5,6,7').split(',')
  if (props.kind === 'SYMPTOM' && !form.eventAt) form.eventAt = localDateKey() + ' ' + new Date().toTimeString().slice(0, 8)
  baseline = snapshot()
  if (managedQuestion.value) { managedFlow.clear(); loadManagedQuestion() }
}, { immediate: true, flush: 'sync' })

async function attach(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('Each attachment must be 5 MB or smaller')
    return
  }
  const epoch = draftEpoch, pid = props.patientId
  const dataUrl = await new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
  if (epoch !== draftEpoch || pid !== props.patientId || !props.modelValue) return
  form.details.attachments ||= []
  form.details.attachments.push({ name: file.name, dataUrl })
  e.target.value = ''
}

async function submit() {
  if (managedQuestion.value || managedAppointment.value || saving.value || questionSaveUnknown.value || !props.modelValue || !props.patientId) return
  attempted.value = true; errors.value = validationErrors()
  const first = Object.keys(errors.value)[0]
  if (first) {
    await nextTick()
    const target = { title: titleInput, eventAt: timeInput, medicationId: medicationInput, startDate: dateInput, confirmed: confirmationInput }[first]?.value
    if (typeof target?.focus === 'function') target.focus(); else target?.$el?.querySelector('input,button')?.focus()
    return
  }
  saving.value = true
  const pid = props.patientId, epoch = draftEpoch, submitted = snapshot(), auth = {...captureAuthSession(),actorId:localStorage.getItem('userId')}
  saveController=new AbortController()
  const current = () => epoch === draftEpoch && pid === props.patientId && props.modelValue && isAuthSessionCurrent(auth) && auth.actorId===localStorage.getItem('userId')
  try {
    const data = JSON.parse(JSON.stringify(form)); data.patientId = pid
    if (data.kind === 'ORDER') data.details.repeatDays = days.value.join(',')
    if(data.kind==='QUESTION'){
      // 普通入口只发送普通文字，不能把旧表单默认字段或专业署名写回问题。
      const details=data.details
      data.details=Object.fromEntries(['description','answer','followUp'].filter(key=>details[key]!=null).map(key=>[key,details[key]]))
      // 遗漏表示保留，明确清空必须传 null，不能被普通字段过滤器丢掉。
      if(Object.prototype.hasOwnProperty.call(details,'appointmentId')) data.details.appointmentId=details.appointmentId==null || details.appointmentId==='' ? null : details.appointmentId
    }
    await saveCareItem(data,{expectedAuth:auth,signal:saveController.signal,...(data.kind==='QUESTION'?{visitQuestion:true}:{})})
    if (!current() || snapshot() !== submitted) return
    clearSensitive(); emit('saved'); emit('update:modelValue', false)
    ElMessage.success('Saved successfully')
  } catch (error) {
    if (!current()) return
    if ([401,403].includes(Number(error?.response?.status || error?.code))) sessionCleared()
    else if(form.kind==='QUESTION' && !error?.notDispatched && (!Number(error?.response?.status || error?.code) || Number(error?.response?.status || error?.code)>=500)){questionSaveUnknown.value=true}
    else ElMessage.error('Unable to save. Your input is kept; please retry.')
  } finally { if (epoch === draftEpoch) saving.value = false }
}

</script>

<style scoped>
.care-required-control{width:100%}

.entry-patient{overflow-wrap:anywhere;line-height:1.6}.draft-warning,.entry-errors{padding:12px;margin-bottom:16px;border:1px solid var(--warning);border-radius:8px;background:var(--warning-soft);overflow-wrap:anywhere}.draft-warning p{margin:0 0 12px}.draft-warning-actions{display:flex;flex-wrap:wrap;gap:10px}.draft-warning-actions :deep(.el-button){margin:0;min-height:44px;max-width:100%;height:auto;white-space:normal;line-height:1.4}.field-error{margin:4px 0;color:var(--el-color-danger);font-size:inherit;line-height:1.5}.entry-errors ul{padding-left:20px}.care-entry :deep(.el-form-item__error){position:static;padding-top:4px}

.entry-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.care-entry :deep(.el-select),.care-entry :deep(.el-date-editor){width:100%}.dose-list{width:100%}.dose-row,.attachment-row{display:flex;gap:10px;align-items:center;margin-bottom:10px}.dose-row :deep(.el-date-editor){width:150px}.clinical-confirmation{display:flex;gap:8px;line-height:1.5;height:auto;align-items:flex-start;white-space:normal;padding:12px;border:1px solid var(--warning);border-radius:8px;background:var(--warning-soft)}.clinical-confirmation :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.attachment-row{margin-top:8px}@media(max-width:600px){.entry-grid{grid-template-columns:1fr}.dose-row{flex-wrap:wrap}.dose-row :deep(.el-date-editor){width:100%}}
</style>
