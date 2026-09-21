<template>
  <el-dialog
    :model-value="modelValue"
    :title="titles[form.kind]"
    width="min(760px, 95vw)"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-form label-position="top" :model="form" class="care-entry">
      <el-form-item label="Title" required>
        <el-input v-model="form.title" maxlength="200" placeholder="For example: nephrology follow-up, dizziness, question for clinician" />
      </el-form-item>

      <template v-if="form.kind === 'APPOINTMENT'">
        <div class="entry-grid">
          <el-form-item label="Appointment time" required><el-date-picker v-model="form.eventAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
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
          <el-form-item label="Medication" required><el-select v-model="form.details.medicationId" filterable><el-option v-for="m in medications" :key="m.id" :value="m.id" :label="m.drugName" /></el-select></el-form-item>
          <el-form-item label="Action"><el-radio-group v-model="form.details.action"><el-radio value="CHANGE">Start / change</el-radio><el-radio value="STOP">Stop</el-radio></el-radio-group></el-form-item>
          <el-form-item label="Effective date" required><el-date-picker v-model="form.details.startDate" value-format="YYYY-MM-DD" /></el-form-item>
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
        <el-checkbox v-model="form.details.confirmed" class="clinical-confirmation">I have checked the medication, dose, schedule, and effective date against the clinician's prescription.</el-checkbox>
      </template>

      <template v-if="form.kind === 'SYMPTOM'">
        <el-form-item label="Time of onset" required><el-date-picker v-model="form.eventAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
        <el-form-item label="Severity (0 = none, 10 = worst)"><el-slider v-model="form.details.severity" :min="0" :max="10" show-stops show-input /></el-form-item>
        <div class="entry-grid">
          <el-form-item label="Duration"><el-input v-model="form.details.duration" placeholder="For example: about 20 minutes" /></el-form-item>
          <el-form-item label="Current status"><el-select v-model="form.details.progress"><el-option label="Ongoing" value="ONGOING" /><el-option label="Improved" value="IMPROVED" /><el-option label="Resolved" value="RESOLVED" /></el-select></el-form-item>
        </div>
        <el-form-item label="What was happening when it started"><el-input v-model="form.details.context" type="textarea" /></el-form-item>
        <el-form-item label="What was tried and whether it helped"><el-input v-model="form.details.response" type="textarea" /></el-form-item>
      </template>

      <template v-if="form.kind === 'QUESTION'">
        <el-form-item label="Appointment"><el-select v-model="form.details.appointmentId" clearable><el-option v-for="a in appointments" :key="a.id" :value="a.id" :label="`${a.eventAt || ''} ${a.title}`" /></el-select></el-form-item>
        <el-form-item label="Question details"><el-input v-model="form.details.description" type="textarea" /></el-form-item>
        <el-form-item label="Clinician's answer"><el-input v-model="form.details.answer" type="textarea" /></el-form-item>
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
    <template #footer><el-button @click="$emit('update:modelValue', false)">Cancel</el-button><el-button type="primary" :loading="saving" @click="submit">Save</el-button></template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { saveCareItem } from '@/api/care'
import { localDateKey } from '@/utils/familyHealth'

const props = defineProps({ modelValue: Boolean, kind: String, row: Object, patientId: [Number, String], members: { type: Array, default: () => [] }, medications: { type: Array, default: () => [] }, reports: { type: Array, default: () => [] }, appointments: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue', 'saved'])
const form = reactive({ details: {} })
const saving = ref(false)
const days = ref([])
const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
const titles = { APPOINTMENT: 'Appointment / follow-up', ORDER: 'Prescription & medication plan', SYMPTOM: 'Record symptom', QUESTION: 'Question for clinician', HANDOVER: 'Care handover' }

watch(() => props.modelValue, open => {
  if (!open) return
  Object.keys(form).forEach(k => delete form[k])
  Object.assign(form, { kind: props.kind, title: '', eventAt: null, notifyAt: null, assignedUserId: null, details: { startDate: localDateKey(), endDate: null, action: 'CHANGE', unit: 'tablet', repeatDays: '1,2,3,4,5,6,7', doses: [{ time: '08:00', quantity: 1 }], attachments: [], confirmed: false, severity: 3, progress: 'ONGOING' } }, props.row ? JSON.parse(JSON.stringify(props.row)) : {})
  if (props.kind === 'ORDER') {
    delete form.id
    form.details.confirmed = false
  }
  days.value = String(form.details.repeatDays || '1,2,3,4,5,6,7').split(',')
  if (props.kind === 'SYMPTOM' && !form.eventAt) form.eventAt = localDateKey() + ' ' + new Date().toTimeString().slice(0, 8)
})

async function attach(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('Each attachment must be 5 MB or smaller')
    return
  }
  const dataUrl = await new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
  form.details.attachments ||= []
  form.details.attachments.push({ name: file.name, dataUrl })
  e.target.value = ''
}

async function submit() {
  if (!form.title.trim()) {
    ElMessage.warning('Enter a title')
    return
  }
  if (['APPOINTMENT', 'SYMPTOM'].includes(form.kind) && !form.eventAt) {
    ElMessage.warning('Select a date and time')
    return
  }
  if (form.kind === 'ORDER' && !form.details.confirmed) {
    ElMessage.warning('Confirm that the medication plan matches the clinician\'s prescription')
    return
  }
  saving.value = true
  const pid = props.patientId
  try {
    const data = JSON.parse(JSON.stringify(form))
    data.patientId = pid
    if (data.kind === 'ORDER') data.details.repeatDays = days.value.join(',')
    await saveCareItem(data)
    if (pid === props.patientId) {
      emit('saved')
      emit('update:modelValue', false)
    }
    ElMessage.success('Saved successfully')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.entry-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.care-entry :deep(.el-select),.care-entry :deep(.el-date-editor){width:100%}.dose-list{width:100%}.dose-row,.attachment-row{display:flex;gap:10px;align-items:center;margin-bottom:10px}.dose-row :deep(.el-date-editor){width:150px}.clinical-confirmation{height:auto;align-items:flex-start;white-space:normal;padding:12px;border:1px solid #f59e0b;border-radius:8px;background:#fffbeb}.clinical-confirmation :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.attachment-row{margin-top:8px}@media(max-width:600px){.entry-grid{grid-template-columns:1fr}.dose-row{flex-wrap:wrap}.dose-row :deep(.el-date-editor){width:100%}}
</style>
