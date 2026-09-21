<template>
  <section class="reminder-panel">
    <header class="reminder-head">
      <div><h2>用药提醒</h2><p>为患者设置用药时间和重复日期，减少漏服或重复服药。</p></div>
      <div class="head-actions"><el-button @click="requestBrowserNotification">启用浏览器通知</el-button><el-button type="primary" :disabled="!patientId" @click="openCreate"><el-icon><Plus /></el-icon>添加提醒</el-button></div>
    </header>
    <el-alert v-if="!patientId" title="请先选择患者，再管理用药提醒。" type="warning" :closable="false" show-icon />
    <el-table v-else :data="rows" v-loading="loading" empty-text="暂无用药提醒" stripe>
      <el-table-column label="药品" min-width="150"><template #default="{ row }"><b>{{ row.medication?.drugName || medicationName(row.medicationId) }}</b><div class="muted">{{ row.dosage || '未填写剂量' }}</div></template></el-table-column>
      <el-table-column prop="remindTime" label="时间" width="100" />
      <el-table-column label="重复日期" min-width="190"><template #default="{ row }">{{ daysText(row.repeatDays) }}</template></el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="已启用" width="90" align="center"><template #default="{ row }"><el-switch :model-value="row.enabled === 1" @change="toggle(row)" /></template></el-table-column>
      <el-table-column label="操作" min-width="140" fixed="right"><template #default="{ row }"><div class="table-actions"><el-button link type="primary" @click="openEdit(row)">编辑</el-button><el-popconfirm title="确认删除这条用药提醒吗？" @confirm="remove(row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm></div></template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑用药提醒' : '添加用药提醒'" width="min(560px, 94vw)" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="药品" prop="medicationId"><el-select v-model="form.medicationId" filterable style="width:100%"><el-option v-for="item in medications.filter(m => m.isActive === 1)" :key="item.id" :label="item.drugName" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="提醒时间" prop="remindTime"><el-time-picker v-model="form.remindTime" format="HH:mm" value-format="HH:mm" style="width:100%" /></el-form-item>
        <el-form-item label="重复日期" prop="repeatDays"><el-checkbox-group v-model="form.repeatDays"><el-checkbox v-for="day in weekDays" :key="day.value" :value="day.value">{{ day.label }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="剂量"><el-input v-model="form.dosage" placeholder="例如：每次 1 片" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="255" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
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
const weekDays = [{ value: '1', label: '周一' }, { value: '2', label: '周二' }, { value: '3', label: '周三' }, { value: '4', label: '周四' }, { value: '5', label: '周五' }, { value: '6', label: '周六' }, { value: '7', label: '周日' }]
const form = reactive({ id: null, medicationId: null, remindTime: '08:00', repeatDays: ['1', '2', '3', '4', '5', '6', '7'], dosage: '', remark: '', enabled: 1 })
const rules = { medicationId: [{ required: true, message: '请选择药品', trigger: 'change' }], remindTime: [{ required: true, message: '请选择提醒时间', trigger: 'change' }], repeatDays: [{ type: 'array', required: true, min: 1, message: '请至少选择一天', trigger: 'change' }] }
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
async function submit() { await formRef.value.validate(); saving.value = true; try { await saveReminder({ ...form, patientId: Number(props.patientId), repeatDays: form.repeatDays.join(',') }); ElMessage.success('提醒已保存'); visible.value = false; await load() } finally { saving.value = false } }
async function toggle(row) { await toggleEnabled(row.id); await load() }
async function remove(row) { await deleteReminder(row.id); ElMessage.success('提醒已删除'); await load() }
function medicationName(id) { return props.medications.find(x => x.id === id)?.drugName || '未知药品' }
function daysText(value) { const values = (value || '').split(','); if (values.length === 7) return '每天'; return weekDays.filter(x => values.includes(x.value)).map(x => x.label).join('、') || '未设置' }
async function requestBrowserNotification() { if (!('Notification' in window)) { ElMessage.warning('当前浏览器不支持通知'); return } const permission = await Notification.requestPermission(); ElMessage[permission === 'granted' ? 'success' : 'warning'](permission === 'granted' ? '浏览器通知已启用' : '未获得浏览器通知权限') }
watch(() => props.patientId, () => { visible.value = false; rows.value = []; load() }, { immediate: true })
</script>

<style scoped>
.reminder-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px}.reminder-head h2{margin:0 0 6px}.reminder-head p,.muted{margin:0;color:#64748b;font-size:13px}.head-actions,.table-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.table-actions :deep(.el-button + .el-button){margin-left:0}@media(max-width:768px){.reminder-head{flex-direction:column}.head-actions{width:100%}.head-actions .el-button{flex:1;margin-left:0}}
</style>
