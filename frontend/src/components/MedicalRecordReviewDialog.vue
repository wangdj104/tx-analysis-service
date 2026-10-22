<template>
  <el-dialog :model-value="true" title="Review the source medical record" width="min(900px,96vw)" :close-on-click-modal="false" :before-close="beforeClose" @close="close">
    <section class="medical-review" aria-label="Source medical-record review">
      <p v-if="loading" role="status">Loading the source medical record…</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <template v-if="preview">
        <section class="medical-review-qualification" data-testid="medical-review-qualification" aria-label="Current separate-channel review">
          <p>{{ reviewPresentation.sourceText }}</p>
          <p v-if="reviewPresentation.sourceActorText" class="medical-review-help">{{ reviewPresentation.sourceActorText }}</p>
          <p v-if="reviewPresentation.doctorText">{{ reviewPresentation.doctorText }}</p>
          <p v-if="reviewPresentation.doctorActorText" class="medical-review-help">{{ reviewPresentation.doctorActorText }}</p>
          <p class="medical-review-automatic" :data-tone="reviewPresentation.automaticTone">{{ reviewPresentation.automaticText }}</p>
          <p v-if="reviewPresentation.observationText" class="medical-review-help">{{ reviewPresentation.observationText }}</p>
          <p class="medical-review-help">Source verification and a recorded DOCTOR review are not a diagnosis or a judgment of clinical fitness</p>
          <p v-if="reviewPresentation.mode === 'PARTICIPANT'" role="status">This record uses separate review channels. This legacy form is read-only</p>
        </section>
        <OriginalImportFacts :original-import="preview.originalImport" />
        <h3>Current record</h3>
        <h2>{{ reviewPatientName }}</h2>
        <p>{{ correctionCopy.patientId }} {{ preview.patientId ?? correctionCopy.null }} · {{ correctionCopy.recordId }} {{ preview.id }} · {{ preview.recordDate }}</p>
        <p>{{ correctionCopy.validity }}: {{ correctionCopy.validities[preview.validity] || correctionCopy.validities.UNKNOWN }} · {{ reviewPresentation.mode === 'PARTICIPANT' ? reviewPresentation.legacyHeading : correctionCopy.review }}: {{ correctionCopy.reviews[preview.verificationStatus] || correctionCopy.null }}</p>
        <p v-if="preview.sourceStatus">{{ correctionCopy.sourceStatuses[preview.sourceStatus] || correctionCopy.sourceStatuses.UNAVAILABLE }}</p>
        <p v-if="reviewPresentation.mode === 'NONE'">{{ correctionCopy.verifiedHelp }}</p>
        <dl class="medical-review-context">
          <dt>Content version</dt><dd>{{ preview.contentVersion }}</dd>
          <dt>Record type</dt><dd>{{ preview.recordType || '—' }}</dd>
          <dt>Hospital / department</dt><dd>{{ preview.hospitalName || '—' }} / {{ preview.deptName || '—' }}</dd>
          <dt>Clinician</dt><dd>{{ preview.doctorName || '—' }}</dd>
          <dt>Source / external ID</dt><dd>{{ preview.sourceType }} / {{ preview.sourceExternalId || '—' }}</dd>
          <dt>Confidence</dt><dd>{{ preview.confidenceScore ?? '—' }}</dd>
        </dl>
        <h3>Original notes</h3><pre>{{ preview.remark || '—' }}</pre>
        <h3>Original recognized data</h3><pre>{{ preview.aiRawResult || '—' }}</pre>
        <h3>Test items</h3>
        <div class="medical-review-table"><table><thead><tr><th>Item</th><th>Result</th><th>Unit</th><th>Reference range</th><th>Abnormal flag</th><th>Confidence</th></tr></thead><tbody>
          <tr v-for="(item,index) in preview.items || []" :key="item.id || index"><td>{{ item.itemName }}<small v-if="item.itemCode"> ({{ item.itemCode }})</small></td><td>{{ item.resultValue }}</td><td>{{ item.unit }}</td><td>{{ item.referenceRange }}</td><td>{{ abnormalLabel(item.isAbnormal) }}</td><td>{{ item.aiConfidence ?? '—' }}</td></tr>
        </tbody></table></div>
        <h3>Attachment metadata</h3>
        <ul v-if="preview.attachments?.length"><li v-for="file in preview.attachments" :key="file.id">{{ file.fileName }} · {{ file.fileType }} · {{ file.fileSize ?? '—' }} bytes</li></ul>
        <p v-else>No attachments</p>
        <p class="medical-review-help">This preview lists attachment metadata. Check the relevant original attachments in the medical record before confirming.</p>
        <section v-if="preview.verificationNote || preview.verifiedAt"><h3>{{ reviewPresentation.legacyHeading }}</h3><p>{{ preview.verifiedAt }}<span v-if="preview.verifiedBy"> · #{{ preview.verifiedBy }}</span></p><pre>{{ preview.verificationNote || '—' }}</pre></section>
        <label class="medical-review-note">Review note<textarea ref="noteInput" v-model="note" rows="3" :disabled="!legacyEditable || saving || conflict" :aria-invalid="reasonError ? 'true' : undefined" :aria-describedby="'medical-review-note-help-' + recordId + (reasonError ? ' medical-review-note-error-' + recordId : '')"></textarea></label>
        <p :id="`medical-review-note-help-${recordId}`" class="medical-review-help">A reason is required to reject this version (up to 1,000 characters). Approval notes are optional.</p>
        <p v-if="reasonError" :id="`medical-review-note-error-${recordId}`" role="alert">{{ reasonError }}</p>
        <label class="medical-review-confirm"><input v-model="acknowledged" type="checkbox" :disabled="!legacyEditable || saving || conflict">I have checked this version of the source record and its relevant attachments</label>
      </template>
      <div class="medical-review-actions">
        <button type="button" data-testid="reload-medical-preview" :disabled="loading || saving" @click="load">Reload source</button>
        <button type="button" data-testid="confirm-medical-approve" :disabled="!canSubmit || preview?.validity === 'INVALIDATED'" @click="submit(true)">Approve this version</button>
        <button type="button" data-testid="confirm-medical-reject" :disabled="!canSubmit" @click="submit(false)">Reject this version</button>
        <button type="button" @click="close">Close</button>
      </div>
    </section>
  </el-dialog>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { getMedicalReviewPreview, submitMedicalReview } from '@/api/medicalReview'
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache, AUTH_STORAGE_KEYS } from '@/utils/authSession'
import OriginalImportFacts from '@/components/OriginalImportFacts.vue'
import { medicalRecordCopy as correctionCopy } from '@/components/medical-record/medicalRecordCorrectionCopy'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import { useRoute } from 'vue-router'
import { medicalReviewPresentation } from '@/utils/medicalReviewQualification'

const props = defineProps({ recordId: { type: Number, required: true }, patientId: { type: [Number, String], required: true }, workspace: Boolean })
const emit = defineEmits(['closed', 'reviewed'])
const { currentPatient, currentPatientId } = useCurrentPatient()
const route = useRoute()
const permissionMenus = inject('userMenus', ref([]))
const preview = ref(null), note = ref(''), acknowledged = ref(false), loading = ref(false), saving = ref(false), conflict = ref(false), error = ref('')
const noteInput = ref(null), reasonError = ref('')
const reviewPatientName = computed(() => (String(currentPatient.value?.id) === String(props.patientId) ? currentPatient.value?.patientName || currentPatient.value?.name : '') || preview.value?.patientName || correctionCopy.patientProfile)
const reviewPresentation = computed(() => medicalReviewPresentation(preview.value?.reviewSummary, 'en'))
const legacyEditable = computed(() => !!preview.value && reviewPresentation.value.mode === 'NONE')
let epoch = 0, controller = null, session = null, permission = null, closing = false, disposed = false
const currentSession = () => !closing && !disposed && !!session && !controller?.signal.aborted && isAuthSessionCurrent(session) && session.actorId === localStorage.getItem('userId') && permission === JSON.stringify(readPermissionCache()) && session.contextPatient === currentPatientId.value && session.contextRoute === route.fullPath
const canSubmit = computed(() => legacyEditable.value && acknowledged.value && !loading.value && !saving.value && !conflict.value)
function abnormalLabel(value) { return ({ 1: 'High', '-1': 'Low', 0: 'Normal' })[value] || '—' }
function invalidate() { epoch++; controller?.abort(); controller = null; permission = null; preview.value = null; note.value = ''; reasonError.value = ''; error.value = ''; conflict.value = false; acknowledged.value = false; loading.value = false; saving.value = false; session = null }
function close() { if (closing) return; closing = true; invalidate(); emit('closed') }
function beforeClose(done) { close(); done() }
function current(turn, record, patient) { return turn === epoch && record === props.recordId && String(patient) === String(props.patientId) && currentSession() }
function validPreviewRows(rows) { return rows == null || Array.isArray(rows) && rows.every(row => row !== null && typeof row === 'object' && !Array.isArray(row)) }
async function load() {
  if (closing || disposed) return
  invalidate(); const turn = epoch, record = props.recordId, patient = props.patientId
  session = { ...captureAuthSession(), actorId: localStorage.getItem('userId'), contextPatient: currentPatientId.value, contextRoute: route.fullPath }; permission = JSON.stringify(readPermissionCache()); controller = new AbortController()
  if (!currentSession()) return
  loading.value = true; conflict.value = false; error.value = ''; note.value = ''
  try {
    const result = await getMedicalReviewPreview(record, { expectedAuth: session, signal: controller.signal })
    if (!current(turn, record, patient)) return
    if (result?.code !== 200) throw new Error('Invalid review response')
    const value = result.data
    if (value?.id !== record || String(value?.patientId) !== String(patient) || !Number.isSafeInteger(value?.contentVersion) || value.contentVersion <= 0) throw new Error('Invalid review snapshot')
    if (!validPreviewRows(value.items) || !validPreviewRows(value.attachments)) throw new Error('Invalid review collections')
    preview.value = value
  } catch {
    if (current(turn, record, patient)) { preview.value = null; error.value = 'The source record is unavailable or access changed. Reload it before reviewing.' }
  } finally { if (turn === epoch) loading.value = false }
}
async function submit(approved) {
  if (!currentSession()) { invalidate(); return }
  if (typeof approved !== 'boolean' || !canSubmit.value || approved && preview.value?.validity === 'INVALIDATED') return
  // 退回才要求理由；校验失败保留草稿和已核对状态，不截断或改写临床文字。
  reasonError.value = ''
  if (!approved) {
    if (/^[\s\u001c-\u001f\u0085]*$/u.test(note.value)) reasonError.value = 'Enter a reason before rejecting this version. A blank note is not enough.'
    else if ([...note.value].length > 1000) reasonError.value = 'The rejection reason is over 1,000 characters. Shorten it before submitting.'
    if (reasonError.value) { noteInput.value?.focus(); return }
  }
  const turn = epoch, record = props.recordId, patient = props.patientId
  // Submit the displayed version. A failed review must never silently fetch a new version and retry.
  if (!currentSession() || !legacyEditable.value) { invalidate(); return }
  const body = { approved, note: note.value, expectedContentVersion: preview.value.contentVersion }
  saving.value = true
  try {
    const result = await submitMedicalReview(record, body, props.workspace, { expectedAuth: session, signal: controller.signal })
    if (result?.code !== 200) throw Object.assign(new Error('Unconfirmed review result'), { code: result?.code })
    if (current(turn, record, patient)) { invalidate(); emit('reviewed', record) }
  } catch (failure) {
    if (!current(turn, record, patient)) return
    const status = Number(failure?.response?.status || failure?.code)
    invalidate(); conflict.value = true
    if (status === 401 || status === 403) {
      // 失权时连同草稿清空；新的读取必须重新建立会话，旧回调不能恢复预览。
      error.value = "Access changed. The source preview and review-note draft were cleared. Reload before reviewing."; return
    }
    error.value = status === 409 ? 'The record changed. Reload and check the new version before reviewing.' : 'The review outcome is uncertain. Reload the source before deciding again.'
  } finally { if (turn === epoch) saving.value = false }
}
watch(note, () => { reasonError.value = '' })
watch(permissionMenus, invalidate, { deep: true, flush: 'sync' })
watch(() => [currentPatientId.value, route.fullPath], invalidate, { flush: 'sync' })
watch(() => [props.recordId, props.patientId], load, { immediate: true, flush: 'sync' })
// 会话或权限改变时，原始事实与所属预览一起清空；旧响应不能重新填回。
function storageChanged(event) { if (event.key == null || [...AUTH_STORAGE_KEYS, 'permissionSession', 'userMenus', 'userMenuNames', 'userRoleCodes'].includes(event.key)) invalidate() }
onMounted(() => { window.addEventListener('auth-session-cleared', invalidate); window.addEventListener('auth-session-changed', invalidate); window.addEventListener('storage', storageChanged); window.addEventListener('permissions-changed', invalidate) })
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener('auth-session-cleared', invalidate); window.removeEventListener('auth-session-changed', invalidate); window.removeEventListener('storage', storageChanged); window.removeEventListener('permissions-changed', invalidate) })
</script>

<style scoped>
.medical-review-qualification{padding:12px;border:1px solid var(--line);border-radius:8px;background:var(--surface-subtle);overflow-wrap:anywhere}.medical-review-qualification p{margin:6px 0}.medical-review-automatic{font-weight:600}.medical-review-automatic[data-tone="success"]{color:var(--care-800)}
.medical-review{color:var(--ink-800);line-height:1.6}.medical-review h2,.medical-review h3{color:var(--ink-800);margin:18px 0 8px}.medical-review pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;background:var(--surface-subtle);padding:12px;border-radius:8px}.medical-review-context{display:grid;grid-template-columns:minmax(120px,1fr) 3fr;gap:8px}.medical-review-context dt{font-weight:600}.medical-review-context dd{margin:0;overflow-wrap:anywhere}.medical-review-table{max-width:100%;overflow-x:auto}.medical-review table{width:100%;border-collapse:collapse}.medical-review th,.medical-review td{text-align:left;padding:8px;border-bottom:1px solid var(--line);white-space:pre-wrap}.medical-review-help{color:var(--ink-500)}.medical-review-note{display:grid;gap:6px}.medical-review-note textarea{font:inherit;padding:10px;border:1px solid var(--line);border-radius:8px;color:var(--ink-800);background:var(--paper)}.medical-review-confirm{display:flex;gap:10px;margin:18px 0;align-items:flex-start}.medical-review-confirm input{margin-top:6px;min-width:18px;min-height:18px}.medical-review-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:18px}.medical-review-actions button{min-height:44px;padding:8px 14px;font:inherit;border:1px solid var(--care-600);border-radius:8px;color:var(--care-800);background:var(--paper);cursor:pointer}.medical-review-actions button:disabled{opacity:.5;cursor:not-allowed}.medical-review-actions button:focus-visible{outline:3px solid var(--care-600);outline-offset:2px}@media(max-width:600px){.medical-review-context{grid-template-columns:1fr}.medical-review-context dd{margin-bottom:6px}.medical-review-actions button{flex:1 1 42%}}
</style>
