<template>
  <section class="reminder-panel">
    <header class="reminder-head">
      <div><h2>Medication reminders</h2><p>Set dose times and repeat days for each patient to reduce missed or duplicate doses.</p></div>
      <div class="head-actions"><el-button @click="requestBrowserNotification">Enable browser notifications</el-button><el-button type="primary" :disabled="!patientId" @click="openCreate"><el-icon><Plus /></el-icon>Add reminder</el-button></div>
    </header>
    <el-alert v-if="!patientId" title="Select a patient before managing medication reminders." type="warning" :closable="false" show-icon />
    <el-table v-else :data="rows" v-loading="loading" empty-text="No medication reminders" stripe>
      <el-table-column label="Medication" min-width="150"><template #default="{ row }"><b>{{ row.medication?.drugName || medicationName(row.medicationId) }}</b><div class="muted">{{ row.dosage || 'Dose not provided' }}</div></template></el-table-column>
      <el-table-column prop="remindTime" label="Time" width="100" />
      <el-table-column label="Repeat days" min-width="190"><template #default="{ row }">{{ daysText(row.repeatDays) }}</template></el-table-column>
      <el-table-column prop="remark" label="Notes" min-width="160" show-overflow-tooltip />
      <el-table-column label="Enabled" width="90" align="center"><template #default="{ row }"><el-switch :model-value="row.enabled === 1" @change="toggle(row)" /></template></el-table-column>
      <el-table-column label="Actions" min-width="140" fixed="right"><template #default="{ row }"><div class="table-actions"><el-button link type="primary" @click="openEdit(row)">Edit</el-button><el-popconfirm title="Delete this medication reminder?" @confirm="remove(row)"><template #reference><el-button link type="danger">Delete</el-button></template></el-popconfirm></div></template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? 'Edit medication reminder' : 'Add medication reminder'" width="min(560px, 94vw)" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="Medication" prop="medicationId"><el-select v-model="form.medicationId" filterable style="width:100%"><el-option v-for="item in medications.filter(m => m.isActive === 1)" :key="item.id" :label="item.drugName" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="Reminder time" prop="remindTime"><el-time-picker v-model="form.remindTime" format="HH:mm" value-format="HH:mm" style="width:100%" /></el-form-item>
        <el-form-item label="Repeat days" prop="repeatDays"><el-checkbox-group v-model="form.repeatDays"><el-checkbox v-for="day in weekDays" :key="day.value" :value="day.value">{{ day.label }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="Dose"><el-input v-model="form.dosage" placeholder="For example: 1 tablet" /></el-form-item>
        <el-form-item label="Notes"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="255" /></el-form-item>
        <el-form-item label="Enabled"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">Cancel</el-button><el-button type="primary" :loading="saving" @click="submit">Save</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { deleteReminder, listReminders, saveReminder, toggleEnabled } from '@/api/medicationReminder'

const props = defineProps({ patientId: { type: [Number, String], default: null }, medications: { type: Array, default: () => [] } })
const rows = ref([]), loading = ref(false), saving = ref(false), visible = ref(false), formRef = ref(null)
const weekDays = [{ value: '1', label: 'Monday' }, { value: '2', label: 'Tuesday' }, { value: '3', label: 'Wednesday' }, { value: '4', label: 'Thursday' }, { value: '5', label: 'Friday' }, { value: '6', label: 'Saturday' }, { value: '7', label: 'Sunday' }]
const form = reactive({ id: null, medicationId: null, remindTime: '08:00', repeatDays: ['1', '2', '3', '4', '5', '6', '7'], dosage: '', remark: '', enabled: 1 })
const rules = { medicationId: [{ required: true, message: 'Select a medication', trigger: 'change' }], remindTime: [{ required: true, message: 'Select a reminder time', trigger: 'change' }], repeatDays: [{ type: 'array', required: true, min: 1, message: 'Select at least one day', trigger: 'change' }] }
let version = 0

async function load() {
  const id = props.patientId, v = ++version
  if (!id) { rows.value = []; return }
  loading.value = true
  try {
    const res = await listReminders(id)
    if (v === version && id === props.patientId) rows.value = res.data || []
  } finally {
    if (v === version) loading.value = false
  }
}
function reset() { Object.assign(form, { id: null, medicationId: null, remindTime: '08:00', repeatDays: ['1', '2', '3', '4', '5', '6', '7'], dosage: '', remark: '', enabled: 1 }) }
function openCreate() { reset(); visible.value = true }
function openEdit(row) { Object.assign(form, { id: row.id, medicationId: row.medicationId, remindTime: row.remindTime, repeatDays: (row.repeatDays || '').split(',').filter(Boolean), dosage: row.dosage || '', remark: row.remark || '', enabled: row.enabled ?? 1 }); visible.value = true }
async function submit() { await formRef.value.validate(); saving.value = true; try { await saveReminder({ ...form, patientId: Number(props.patientId), repeatDays: form.repeatDays.join(',') }); ElMessage.success('Reminder saved'); visible.value = false; await load() } finally { saving.value = false } }
async function toggle(row) { await toggleEnabled(row.id); await load() }
async function remove(row) { await deleteReminder(row.id); ElMessage.success('Reminder deleted'); await load() }
function medicationName(id) { return props.medications.find(x => x.id === id)?.drugName || 'Unknown medication' }
function daysText(value) { const values = (value || '').split(','); if (values.length === 7) return 'Every day'; return weekDays.filter(x => values.includes(x.value)).map(x => x.label).join(', ') || 'Not set' }
async function requestBrowserNotification() { if (!('Notification' in window)) { ElMessage.warning('This browser does not support notifications'); return } const permission = await Notification.requestPermission(); ElMessage[permission === 'granted' ? 'success' : 'warning'](permission === 'granted' ? 'Browser notifications enabled' : 'Browser notification permission was not granted') }
watch(() => props.patientId, () => { visible.value = false; rows.value = []; load() }, { immediate: true })
</script>

<style scoped>
.reminder-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px}.reminder-head h2{margin:0 0 6px}.reminder-head p,.muted{margin:0;color:#64748b;font-size:13px}.head-actions,.table-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.table-actions :deep(.el-button + .el-button){margin-left:0}@media(max-width:768px){.reminder-head{flex-direction:column}.head-actions{width:100%}.head-actions .el-button{flex:1;margin-left:0}}
</style>
