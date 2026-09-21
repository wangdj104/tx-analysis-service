<template>
  <el-dialog
    :model-value="modelValue"
    :title="titles[form.kind]"
    width="min(760px, 95vw)"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-form label-position="top" :model="form" class="care-entry">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="200" placeholder="例如：肾内科复诊、头晕、需要咨询医生的问题" />
      </el-form-item>

      <template v-if="form.kind === 'APPOINTMENT'">
        <div class="entry-grid">
          <el-form-item label="预约时间" required><el-date-picker v-model="form.eventAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
          <el-form-item label="提醒时间"><el-date-picker v-model="form.notifyAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="可选" /></el-form-item>
          <el-form-item label="医院"><el-input v-model="form.details.hospital" /></el-form-item>
          <el-form-item label="科室"><el-input v-model="form.details.department" /></el-form-item>
          <el-form-item label="医生"><el-input v-model="form.details.doctor" /></el-form-item>
          <el-form-item label="陪诊人／负责人"><el-select v-model="form.assignedUserId" clearable><el-option v-for="m in members" :key="m.userId" :value="m.userId" :label="m.name" /></el-select></el-form-item>
        </div>
        <el-form-item label="检查与准备事项"><el-input v-model="form.details.preparation" type="textarea" placeholder="需要携带的资料、空腹要求、报到地点及其他说明" /></el-form-item>
        <el-form-item label="关联报告"><el-select v-model="form.details.reportId" clearable filterable><el-option v-for="r in reports" :key="r.id" :value="r.id" :label="`${r.recordDate} ${r.hospitalName || ''} ${r.recordType || ''}`" /></el-select></el-form-item>
        <el-form-item label="下次复诊"><el-date-picker v-model="form.details.nextAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="可选；本次完成后可创建下次复诊" /></el-form-item>
      </template>

      <template v-if="form.kind === 'ORDER'">
        <el-alert title="请严格按照医生处方录入。保存后将创建新版本，并在生效日期同步用药提醒；旧版本会保留在历史记录中。" :closable="false" type="info" />
        <div class="entry-grid">
          <el-form-item label="药品" required><el-select v-model="form.details.medicationId" filterable><el-option v-for="m in medications" :key="m.id" :value="m.id" :label="m.drugName" /></el-select></el-form-item>
          <el-form-item label="变更类型"><el-radio-group v-model="form.details.action"><el-radio value="CHANGE">开始／调整</el-radio><el-radio value="STOP">停药</el-radio></el-radio-group></el-form-item>
          <el-form-item label="生效日期" required><el-date-picker v-model="form.details.startDate" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item v-if="form.details.action !== 'STOP'" label="结束日期"><el-date-picker v-model="form.details.endDate" clearable value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="开方医生"><el-input v-model="form.details.doctor" /></el-form-item>
          <el-form-item label="数量单位"><el-input v-model="form.details.unit" placeholder="片、粒、mL 等，请与库存单位保持一致" /></el-form-item>
        </div>
        <template v-if="form.details.action !== 'STOP'">
          <el-form-item label="用药日"><el-checkbox-group v-model="days"><el-checkbox v-for="(d, i) in weekdays" :key="i" :value="String(i + 1)">{{ d }}</el-checkbox></el-checkbox-group></el-form-item>
          <el-form-item label="用药时间与剂量" required>
            <div class="dose-list">
              <div v-for="(dose, i) in form.details.doses" :key="i" class="dose-row">
                <el-time-picker v-model="dose.time" value-format="HH:mm" format="HH:mm" />
                <el-input-number v-model="dose.quantity" :min="0.001" :precision="3" />
                <span>{{ form.details.unit }}</span>
                <el-button :disabled="form.details.doses.length === 1" @click="form.details.doses.splice(i, 1)">删除</el-button>
              </div>
              <el-button @click="form.details.doses.push({ time: '20:00', quantity: 1 })">添加一次</el-button>
            </div>
          </el-form-item>
        </template>
        <el-form-item label="处方说明／调整原因"><el-input v-model="form.details.instructions" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="处方图片或 PDF">
          <input type="file" accept="image/*,application/pdf" @change="attach" />
          <div v-for="(a, i) in form.details.attachments" :key="i" class="attachment-row"><a :href="a.dataUrl" :download="a.name">{{ a.name }}</a><el-button link type="danger" @click="form.details.attachments.splice(i, 1)">移除</el-button></div>
        </el-form-item>
        <el-checkbox v-model="form.details.confirmed" class="clinical-confirmation">我已根据医生处方核对药品、剂量、用药安排和生效日期。</el-checkbox>
      </template>

      <template v-if="form.kind === 'SYMPTOM'">
        <el-form-item label="发生时间" required><el-date-picker v-model="form.eventAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
        <el-form-item label="严重程度（0＝无不适，10＝最严重）"><el-slider v-model="form.details.severity" :min="0" :max="10" show-stops show-input /></el-form-item>
        <div class="entry-grid">
          <el-form-item label="持续时间"><el-input v-model="form.details.duration" placeholder="例如：约 20 分钟" /></el-form-item>
          <el-form-item label="当前状态"><el-select v-model="form.details.progress"><el-option label="仍在持续" value="ONGOING" /><el-option label="已有缓解" value="IMPROVED" /><el-option label="已经消失" value="RESOLVED" /></el-select></el-form-item>
        </div>
        <el-form-item label="发生时正在做什么"><el-input v-model="form.details.context" type="textarea" /></el-form-item>
        <el-form-item label="采取了什么措施，是否有效"><el-input v-model="form.details.response" type="textarea" /></el-form-item>
      </template>

      <template v-if="form.kind === 'QUESTION'">
        <el-form-item label="关联预约"><el-select v-model="form.details.appointmentId" clearable><el-option v-for="a in appointments" :key="a.id" :value="a.id" :label="`${a.eventAt || ''} ${a.title}`" /></el-select></el-form-item>
        <el-form-item label="问题详情"><el-input v-model="form.details.description" type="textarea" /></el-form-item>
        <el-form-item label="医生答复"><el-input v-model="form.details.answer" type="textarea" /></el-form-item>
        <el-form-item label="后续行动"><el-input v-model="form.details.followUp" type="textarea" /></el-form-item>
      </template>

      <template v-if="form.kind === 'HANDOVER'">
        <el-form-item label="交接内容"><el-input v-model="form.details.note" type="textarea" :rows="4" placeholder="例如：报告放在客厅抽屉，就诊时记得携带" /></el-form-item>
        <div class="entry-grid">
          <el-form-item label="交给谁"><el-select v-model="form.assignedUserId" clearable><el-option v-for="m in members" :key="m.userId" :value="m.userId" :label="m.name" /></el-select></el-form-item>
          <el-form-item label="完成时间"><el-date-picker v-model="form.eventAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
          <el-form-item label="提醒时间"><el-date-picker v-model="form.notifyAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></el-form-item>
        </div>
      </template>
    </el-form>
    <template #footer><el-button @click="$emit('update:modelValue', false)">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
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
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const titles = { APPOINTMENT: '预约／复诊', ORDER: '处方与用药方案', SYMPTOM: '记录症状', QUESTION: '咨询医生', HANDOVER: '照护交接' }

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
    ElMessage.warning('每个附件不能超过 5 MB')
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
    ElMessage.warning('请输入标题')
    return
  }
  if (['APPOINTMENT', 'SYMPTOM'].includes(form.kind) && !form.eventAt) {
    ElMessage.warning('请选择日期和时间')
    return
  }
  if (form.kind === 'ORDER' && !form.details.confirmed) {
    ElMessage.warning('请确认用药方案与医生处方一致')
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
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.entry-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.care-entry :deep(.el-select),.care-entry :deep(.el-date-editor){width:100%}.dose-list{width:100%}.dose-row,.attachment-row{display:flex;gap:10px;align-items:center;margin-bottom:10px}.dose-row :deep(.el-date-editor){width:150px}.clinical-confirmation{height:auto;align-items:flex-start;white-space:normal;padding:12px;border:1px solid #f59e0b;border-radius:8px;background:#fffbeb}.clinical-confirmation :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.attachment-row{margin-top:8px}@media(max-width:600px){.entry-grid{grid-template-columns:1fr}.dose-row{flex-wrap:wrap}.dose-row :deep(.el-date-editor){width:100%}}
</style>
