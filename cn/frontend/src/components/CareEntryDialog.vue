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
    <p class="entry-patient">当前患者: <strong>{{ patientName || '—' }}</strong> · 档案 ID: {{ patientId }}</p>
    <section v-if="discardVisible" class="draft-warning" role="alert">
      <p>有尚未提交的修改。继续编辑可保留当前患者的内容。放弃修改会清除本次草稿；已发送的保存请求可能已经完成。</p><el-button @click="keepEditing">继续编辑</el-button><el-button type="danger" @click="discardChanges">放弃修改</el-button>
    </section>
    <section v-if="Object.keys(errors).length" class="entry-errors" role="alert" aria-live="polite"><strong>请修正下列字段后保存</strong><ul><li v-for="(message, field) in errors" :key="field">{{ message }}</li></ul></section>
    <el-form label-position="top" :model="form" class="care-entry">
      <el-form-item label="标题" required :error="errors.title">
        <el-input v-model="form.title" ref="titleInput" :aria-invalid="!!errors.title" :aria-describedby="errors.title ? 'care-entry-error-title' : undefined" maxlength="200" placeholder="例如：肾内科复诊、头晕、需要咨询医生的问题" />
      <template #error="{ error }"><p id="care-entry-error-title" class="field-error">{{ error }}</p></template></el-form-item>

      <template v-if="form.kind === 'APPOINTMENT'">
        <div class="entry-grid">
          <el-form-item label="预约时间" required :error="errors.eventAt"><div class="care-required-control" data-care-required-field="eventAt" v-care-field-error="{ error: errors.eventAt, id: 'care-entry-error-eventAt' }"><el-date-picker v-model="form.eventAt" ref="timeInput" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></div><template #error="{ error }"><p id="care-entry-error-eventAt" class="field-error">{{ error }}</p></template></el-form-item>
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
          <el-form-item label="药品" required :error="errors.medicationId"><div class="care-required-control" data-care-required-field="medicationId" v-care-field-error="{ error: errors.medicationId, id: 'care-entry-error-medicationId' }"><el-select v-model="form.details.medicationId" ref="medicationInput" filterable><el-option v-for="m in medications" :key="m.id" :value="m.id" :label="m.drugName" /></el-select></div><template #error="{ error }"><p id="care-entry-error-medicationId" class="field-error">{{ error }}</p></template></el-form-item>
          <el-form-item label="变更类型"><el-radio-group v-model="form.details.action"><el-radio value="CHANGE">开始／调整</el-radio><el-radio value="STOP">停药</el-radio></el-radio-group></el-form-item>
          <el-form-item label="生效日期" required :error="errors.startDate"><div class="care-required-control" data-care-required-field="startDate" v-care-field-error="{ error: errors.startDate, id: 'care-entry-error-startDate' }"><el-date-picker v-model="form.details.startDate" ref="dateInput" value-format="YYYY-MM-DD" /></div><template #error="{ error }"><p id="care-entry-error-startDate" class="field-error">{{ error }}</p></template></el-form-item>
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
        <label class="clinical-confirmation"><input type="checkbox" v-model="form.details.confirmed" ref="confirmationInput" :aria-invalid="!!errors.confirmed" :aria-describedby="errors.confirmed ? 'care-entry-error-confirmed' : undefined" /> <span>我已根据医生处方核对药品、剂量、用药安排和生效日期。</span></label>
        <p v-if="errors.confirmed" id="care-entry-error-confirmed" class="field-error">{{ errors.confirmed }}</p>
      </template>

      <template v-if="form.kind === 'SYMPTOM'">
        <el-form-item label="发生时间" required :error="errors.eventAt"><div class="care-required-control" data-care-required-field="eventAt" v-care-field-error="{ error: errors.eventAt, id: 'care-entry-error-eventAt' }"><el-date-picker v-model="form.eventAt" ref="timeInput" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" /></div><template #error="{ error }"><p id="care-entry-error-eventAt" class="field-error">{{ error }}</p></template></el-form-item>
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
    </div>
    <template #footer><el-button @click="requestClose()">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
  </el-dialog>
</template>

<script setup>
import { nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { saveCareItem } from '@/api/care'
import { localDateKey } from '@/utils/familyHealth'
import { AUTH_STORAGE_KEYS } from '@/utils/authSession'

const props = defineProps({ modelValue: Boolean, kind: String, row: Object, patientId: [Number, String], patientName: String, members: { type: Array, default: () => [] }, medications: { type: Array, default: () => [] }, reports: { type: Array, default: () => [] }, appointments: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue', 'saved'])
const form = reactive({ details: {} })
const saving = ref(false)
const days = ref([])
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const titles = { APPOINTMENT: '预约／复诊', ORDER: '处方与用药方案', SYMPTOM: '记录症状', QUESTION: '咨询医生', HANDOVER: '照护交接' }

const errors = ref({}), attempted = ref(false), discardVisible = ref(false)
const titleInput = ref(null), timeInput = ref(null), medicationInput = ref(null), dateInput = ref(null), confirmationInput = ref(null)
let baseline = '', draftEpoch = 0, pendingClose = null
const snapshot = () => JSON.stringify({ form, days: days.value })
const isDirty = () => props.modelValue && (saving.value || snapshot() !== baseline)
function validationErrors() {
  const result = {}
  if (!String(form.title || '').trim()) result.title = '请填写标题'
  if (['APPOINTMENT', 'SYMPTOM'].includes(form.kind) && !form.eventAt) result.eventAt = '请选择日期和时间'
  if (form.kind === 'ORDER') {
    if (!form.details.medicationId) result.medicationId = '请选择药品'
    if (!form.details.startDate) result.startDate = '请选择生效日期'
    if (!form.details.confirmed) result.confirmed = "请确认用药方案与医生处方一致"
  }
  return result
}
watch(form, () => { if (attempted.value) errors.value = validationErrors() }, { deep: true, flush: 'sync' })
function clearSensitive() {
  draftEpoch++; saving.value = false; pendingClose = null; discardVisible.value = false; attempted.value = false; errors.value = {}
  Object.keys(form).forEach(key => delete form[key]); Object.assign(form, { title: '', details: {} }); days.value = []
  baseline = snapshot()
}
function requestClose(done) {
  if (isDirty()) { pendingClose = typeof done === 'function' ? done : null; discardVisible.value = true; return false }
  clearSensitive(); if (typeof done === 'function') done(); else emit('update:modelValue', false)
  return true
}
function onVisibilityChange(open) { if (!open) requestClose(); else emit('update:modelValue', true) }
function keepEditing() { pendingClose = null; discardVisible.value = false }
function discardChanges() { const close = pendingClose; clearSensitive(); if (close) close(); emit('update:modelValue', false) }
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
    window.addEventListener('storage', sessionStorageChanged)
    element.__careEntryCleanup = () => { removeGuard(); window.removeEventListener('care-plan-before-context-change', contextChange); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('auth-session-cleared', sessionCleared); window.removeEventListener('storage', sessionStorageChanged); clearSensitive() }
  },
  unmounted(element) { element.__careEntryCleanup?.() }
}
watch(() => props.patientId, sessionCleared, { flush: 'sync' })

watch(() => props.modelValue, open => {
  if (!open) { clearSensitive(); return }
  draftEpoch++; attempted.value = false; errors.value = {}; discardVisible.value = false; pendingClose = null
  Object.keys(form).forEach(k => delete form[k])
  Object.assign(form, { kind: props.kind, title: '', eventAt: null, notifyAt: null, assignedUserId: null, details: { startDate: localDateKey(), endDate: null, action: 'CHANGE', unit: '片', repeatDays: '1,2,3,4,5,6,7', doses: [{ time: '08:00', quantity: 1 }], attachments: [], confirmed: false, severity: 3, progress: 'ONGOING' } }, props.row ? JSON.parse(JSON.stringify(props.row)) : {})
  if (props.kind === 'ORDER') {
    delete form.id
    form.details.confirmed = false
  }
  days.value = String(form.details.repeatDays || '1,2,3,4,5,6,7').split(',')
  if (props.kind === 'SYMPTOM' && !form.eventAt) form.eventAt = localDateKey() + ' ' + new Date().toTimeString().slice(0, 8)
  baseline = snapshot()
}, { immediate: true, flush: 'sync' })

async function attach(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('每个附件不能超过 5 MB')
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
  if (saving.value || !props.modelValue || !props.patientId) return
  attempted.value = true; errors.value = validationErrors()
  const first = Object.keys(errors.value)[0]
  if (first) {
    await nextTick()
    const target = { title: titleInput, eventAt: timeInput, medicationId: medicationInput, startDate: dateInput, confirmed: confirmationInput }[first]?.value
    if (typeof target?.focus === 'function') target.focus(); else target?.$el?.querySelector('input,button')?.focus()
    return
  }
  saving.value = true
  const pid = props.patientId, epoch = draftEpoch, submitted = snapshot()
  const current = () => epoch === draftEpoch && pid === props.patientId && props.modelValue
  try {
    const data = JSON.parse(JSON.stringify(form)); data.patientId = pid
    if (data.kind === 'ORDER') data.details.repeatDays = days.value.join(',')
    await saveCareItem(data)
    if (!current() || snapshot() !== submitted) return
    clearSensitive(); emit('saved'); emit('update:modelValue', false)
    ElMessage.success('保存成功')
  } catch (error) {
    if (!current()) return
    if (Number(error?.response?.status || error?.code) === 403) sessionCleared()
    else ElMessage.error('未能保存，请保留输入并重试')
  } finally { if (epoch === draftEpoch) saving.value = false }
}

</script>

<style scoped>
.care-required-control{width:100%}

.entry-patient{overflow-wrap:anywhere;line-height:1.6}.draft-warning,.entry-errors{padding:12px;margin-bottom:16px;border:1px solid var(--warning);border-radius:8px;background:var(--warning-soft);overflow-wrap:anywhere}.draft-warning p{margin:0 0 12px}.field-error{margin:4px 0;color:var(--el-color-danger);font-size:inherit;line-height:1.5}.entry-errors ul{padding-left:20px}.care-entry :deep(.el-form-item__error){position:static;padding-top:4px}

.entry-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.care-entry :deep(.el-select),.care-entry :deep(.el-date-editor){width:100%}.dose-list{width:100%}.dose-row,.attachment-row{display:flex;gap:10px;align-items:center;margin-bottom:10px}.dose-row :deep(.el-date-editor){width:150px}.clinical-confirmation{display:flex;gap:8px;line-height:1.5;height:auto;align-items:flex-start;white-space:normal;padding:12px;border:1px solid var(--warning);border-radius:8px;background:var(--warning-soft)}.clinical-confirmation :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.attachment-row{margin-top:8px}@media(max-width:600px){.entry-grid{grid-template-columns:1fr}.dose-row{flex-wrap:wrap}.dose-row :deep(.el-date-editor){width:100%}}
</style>
