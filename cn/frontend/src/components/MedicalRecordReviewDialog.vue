<template>
  <el-dialog :model-value="true" title="查看原始病历并审核" width="min(900px,96vw)" :close-on-click-modal="false" @close="emit('closed')">
    <section class="medical-review" aria-label="原始病历审核">
      <p v-if="loading" role="status">正在读取原始病历…</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <template v-if="preview">
        <h2>{{ preview.patientName }} · {{ preview.recordDate }}</h2>
        <dl class="medical-review-context">
          <dt>内容版本</dt><dd>{{ preview.contentVersion }}</dd>
          <dt>记录类型</dt><dd>{{ preview.recordType || '—' }}</dd>
          <dt>医院 / 科室</dt><dd>{{ preview.hospitalName || '—' }} / {{ preview.deptName || '—' }}</dd>
          <dt>医生</dt><dd>{{ preview.doctorName || '—' }}</dd>
          <dt>数据来源 / 外部编号</dt><dd>{{ preview.sourceType }} / {{ preview.sourceExternalId || '—' }}</dd>
          <dt>置信度</dt><dd>{{ preview.confidenceScore ?? '—' }}</dd>
        </dl>
        <h3>原始备注</h3><pre>{{ preview.remark || '—' }}</pre>
        <h3>原始识别数据</h3><pre>{{ preview.aiRawResult || '—' }}</pre>
        <h3>检查项目</h3>
        <div class="medical-review-table"><table><thead><tr><th>项目</th><th>结果</th><th>单位</th><th>参考范围</th><th>异常标记</th><th>置信度</th></tr></thead><tbody>
          <tr v-for="(item,index) in preview.items || []" :key="item.id || index"><td>{{ item.itemName }}<small v-if="item.itemCode"> ({{ item.itemCode }})</small></td><td>{{ item.resultValue }}</td><td>{{ item.unit }}</td><td>{{ item.referenceRange }}</td><td>{{ abnormalLabel(item.isAbnormal) }}</td><td>{{ item.aiConfidence ?? '—' }}</td></tr>
        </tbody></table></div>
        <h3>附件信息</h3>
        <ul v-if="preview.attachments?.length"><li v-for="file in preview.attachments" :key="file.id">{{ file.fileName }} · {{ file.fileType }} · {{ file.fileSize ?? '—' }} 字节</li></ul>
        <p v-else>暂无附件</p>
        <p class="medical-review-help">此预览仅列出附件信息。确认前请在病历中查看相关原始附件。</p>
        <section v-if="preview.verificationNote || preview.verifiedAt"><h3>上次审核</h3><p>{{ preview.verifiedAt }}<span v-if="preview.verifiedBy"> · #{{ preview.verifiedBy }}</span></p><pre>{{ preview.verificationNote || '—' }}</pre></section>
        <label class="medical-review-note">审核备注<textarea v-model="note" rows="3" :disabled="saving"></textarea></label>
        <label class="medical-review-confirm"><input v-model="acknowledged" type="checkbox" :disabled="saving || conflict">我已核对当前版本的原始病历及相关附件</label>
      </template>
      <div class="medical-review-actions">
        <button type="button" data-testid="reload-medical-preview" :disabled="loading || saving" @click="load">重新读取原文</button>
        <button type="button" data-testid="confirm-medical-approve" :disabled="!canSubmit" @click="submit(true)">通过此版本</button>
        <button type="button" data-testid="confirm-medical-reject" :disabled="!canSubmit" @click="submit(false)">驳回此版本</button>
        <button type="button" @click="emit('closed')">关闭</button>
      </div>
    </section>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { getMedicalReviewPreview, submitMedicalReview } from '@/api/medicalReview'
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'

const props = defineProps({ recordId: { type: Number, required: true }, patientId: { type: [Number, String], required: true }, workspace: Boolean })
const emit = defineEmits(['closed', 'reviewed'])
const preview = ref(null), note = ref(''), acknowledged = ref(false), loading = ref(false), saving = ref(false), conflict = ref(false), error = ref('')
let epoch = 0, controller = null, session = null
const currentSession = () => !!session && isAuthSessionCurrent(session) && session.actorId === localStorage.getItem('userId')
const canSubmit = computed(() => !!preview.value && acknowledged.value && !loading.value && !saving.value && !conflict.value)
function abnormalLabel(value) { return ({ 1: '偏高', '-1': '偏低', 0: '正常' })[value] || '—' }
function invalidate() { epoch++; controller?.abort(); preview.value = null; acknowledged.value = false; loading.value = false; saving.value = false; session = null }
function current(turn, record, patient) { return turn === epoch && record === props.recordId && String(patient) === String(props.patientId) && currentSession() }
async function load() {
  invalidate(); const turn = epoch, record = props.recordId, patient = props.patientId
  session = { ...captureAuthSession(), actorId: localStorage.getItem('userId') }; controller = new AbortController()
  if (!currentSession()) return
  loading.value = true; conflict.value = false; error.value = ''; note.value = ''
  try {
    const result = await getMedicalReviewPreview(record, { expectedAuth: session, signal: controller.signal })
    if (!current(turn, record, patient)) return
    const value = result.data
    if (value?.id !== record || String(value?.patientId) !== String(patient) || !Number.isSafeInteger(value?.contentVersion) || value.contentVersion <= 0) throw new Error('Invalid review snapshot')
    preview.value = value
  } catch {
    if (current(turn, record, patient)) { preview.value = null; error.value = '原始病历不可用或访问权限已变更，请重新读取后审核。' }
  } finally { if (turn === epoch) loading.value = false }
}
async function submit(approved) {
  if (!currentSession()) { invalidate(); return }
  if (!canSubmit.value) return
  const turn = epoch, record = props.recordId, patient = props.patientId
  // 提交绑定预览时看到的版本；失败后不得悄悄取新版并重发审核。
  const body = { approved, note: note.value, expectedContentVersion: preview.value.contentVersion }
  saving.value = true
  try {
    await submitMedicalReview(record, body, props.workspace, { expectedAuth: session, signal: controller.signal })
    if (current(turn, record, patient)) { acknowledged.value = false; conflict.value = true; emit('reviewed', record) }
  } catch (failure) {
    if (!current(turn, record, patient)) return
    acknowledged.value = false; conflict.value = true
    const status = Number(failure?.response?.status || failure?.code)
    if (status === 401 || status === 403) preview.value = null
    error.value = status === 409 ? '病历内容已变更，请重新读取并核对新版本后再审核。' : '无法确认审核结果，请重新读取原文后再作决定。'
  } finally { if (turn === epoch) saving.value = false }
}
watch(() => [props.recordId, props.patientId], load, { immediate: true, flush: 'sync' })
onMounted(() => window.addEventListener('auth-session-cleared', invalidate))
onUnmounted(() => { invalidate(); window.removeEventListener('auth-session-cleared', invalidate) })
</script>

<style scoped>
.medical-review{color:var(--ink-800);line-height:1.6}.medical-review h2,.medical-review h3{color:var(--ink-800);margin:18px 0 8px}.medical-review pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;background:var(--surface-subtle);padding:12px;border-radius:8px}.medical-review-context{display:grid;grid-template-columns:minmax(120px,1fr) 3fr;gap:8px}.medical-review-context dt{font-weight:600}.medical-review-context dd{margin:0;overflow-wrap:anywhere}.medical-review-table{max-width:100%;overflow-x:auto}.medical-review table{width:100%;border-collapse:collapse}.medical-review th,.medical-review td{text-align:left;padding:8px;border-bottom:1px solid var(--line);white-space:pre-wrap}.medical-review-help{color:var(--ink-500)}.medical-review-note{display:grid;gap:6px}.medical-review-note textarea{font:inherit;padding:10px;border:1px solid var(--line);border-radius:8px;color:var(--ink-800);background:var(--paper)}.medical-review-confirm{display:flex;gap:10px;margin:18px 0;align-items:flex-start}.medical-review-confirm input{margin-top:6px;min-width:18px;min-height:18px}.medical-review-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:18px}.medical-review-actions button{min-height:44px;padding:8px 14px;font:inherit;border:1px solid var(--care-600);border-radius:8px;color:var(--care-800);background:var(--paper);cursor:pointer}.medical-review-actions button:disabled{opacity:.5;cursor:not-allowed}.medical-review-actions button:focus-visible{outline:3px solid var(--care-600);outline-offset:2px}@media(max-width:600px){.medical-review-context{grid-template-columns:1fr}.medical-review-context dd{margin-bottom:6px}.medical-review-actions button{flex:1 1 42%}}
</style>
