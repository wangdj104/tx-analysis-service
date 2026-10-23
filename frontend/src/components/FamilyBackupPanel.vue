<template>
  <section class="backup-panel">
    <h2>Family record backup and restore</h2>
    <p>Download eligible v1 record subsets for patients whose full records you can access.</p>
    <el-alert title="v1 is a limited record export, not a complete database, attachment or history backup. Listed categories do not mean complete history coverage; keep separate database and file backups." type="warning" :closable="false" />
    <details class="backup-coverage">
      <summary>See v1 coverage and exclusions</summary>
      <p><strong>Supported subsets, when eligible</strong></p>
      <ul>
        <li>Profiles, clinical profiles and individual targets; dialysis records and schedules, dry-weight, blood-pressure records and analysis, nutrition and complications.</li>
        <li>Eligible independent medications, legacy reminders, intakes, stock, movements and actions.</li>
        <li>Unmanaged medical records and items, including attachment content stored in the database.</li>
        <li>Supported AI analysis and automation settings, legacy alert rules and eligible alerts; ordinary events and care items; ordinary question-text copies. Question text may be current projected text or an older preserved native snapshot, without professional answer history.</li>
      </ul>
      <p><strong>Excluded</strong></p>
      <ul>
        <li>Consultations and chat, care-journey records, doctor workspaces, shared goals, collaborative plans, nurse assignments, authorizations, accounts, notification channels, outbox and commands.</li>
        <li>Collaborative medication, stock-reconciliation, family-handover, emergency and measurement-follow-up histories, including their owned snapshots, revisions, actions, receipts, events and evidence.</li>
        <li>Managed medical records, items and attachments, correction and reference history, and alerts dependent on omitted items.</li>
        <li>Managed calendar appointments, clinician appointments, visit records, attendance facts, dispositions and successor bindings and history; activity copies and visit-summary event copies.</li>
        <li>Professional question answers, state, ownership, commands and history, plus modern visit links.</li>
        <li>Attachment contents stored outside the database.</li>
      </ul>
    </details>
    <el-alert title="Newly restored stock reminders are OFF and require separate per-item review before enabling. Historical stock restored before this release is not automatically covered." type="warning" :closable="false" />
    <div class="backup-actions">
      <el-button type="primary" :loading="busy" @click="download">Download family records</el-button>
      <label class="file-label">Choose backup file <input type="file" accept=".zip" :disabled="busy" @change="preview" /></label>
    </div>
    <el-alert title="Restore creates independent copies without overwriting current data. Medication reminders and automated analysis are disabled; notification channels and caregiver invitations must be configured again." type="info" :closable="false" />
    <section v-if="inspection" class="backup-preview">
      <p>Threshold alert rules also restore disabled. Historical actors are not treated as verified identities.</p>
      <h3>Backup preview</h3>
      <p>Created: {{ inspection.createdAt }}</p>
      <p>Family members: {{ inspection.patients?.map(p => p.name).join(', ') }}</p>
      <p class="backup-coverage-limit"><strong>Current v1 coverage limit:</strong> Only eligible supported record subsets can be restored. Complete database, attachment storage and managed or collaborative history cannot be restored from this file. Review the v1 coverage and exclusions above.</p>
      <p>Counts show rows of accepted record types in this file; they do not prove complete database, attachment or history coverage.</p>
      <el-table :data="Object.entries(inspection.counts || {}).map(([table, count]) => ({ table, count }))"><el-table-column prop="table" label="Record type" /><el-table-column prop="count" label="Count" /></el-table>
      <h4 v-if="(inspection.warnings || []).length">Preview notices</h4>
      <el-alert v-for="(w, i) in inspection.warnings || []" :key="i" :title="w" type="warning" :closable="false" />
      <el-checkbox v-model="confirmed" :disabled="busy" class="restore-confirmation">I understand that this will restore the backup as independent copies.</el-checkbox>
      <el-button type="primary" :disabled="!confirmed || busy" :loading="busy" @click="restore">Restore as copies</el-button>
    </section>
    <el-alert v-if="result" :title="result" type="success" :closable="false" />
    <el-alert v-if="restoreUncertain" title="The restore result is unconfirmed. Check whether patient copies were created before selecting the file again; do not repeatedly retry this restore." type="warning" :closable="false" />
  </section>
</template>

<script setup>
import { onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadBackup, previewBackup, restoreBackup } from '@/api/care'

const emit = defineEmits(['restored'])
const busy = ref(false)
const inspection = ref(null)
const confirmed = ref(false)
const result = ref('')
const restoreUncertain = ref(false)
let selected = null, inspectedFile = null, disposed = false

async function download() {
  if (busy.value || disposed) return
  busy.value = true
  try {
    const blob = await downloadBackup()
    if (disposed) return
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `family-health-backup-${new Date().toISOString().slice(0, 10)}.zip`
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success('Backup downloaded')
  } finally {
    busy.value = false
  }
}

async function preview(e) {
  if (busy.value || disposed) return
  const input = e.target, file = input.files?.[0]
  selected = file
  inspectedFile = null
  inspection.value = null
  confirmed.value = false
  result.value = ''
  restoreUncertain.value = false
  if (!file) return
  busy.value = true
  try {
    const data = (await previewBackup(file)).data
    if (disposed || selected !== file || !data || typeof data !== 'object' || Array.isArray(data)) return
    inspection.value = data
    inspectedFile = file
  } finally {
    busy.value = false
    input.value = ''
  }
}

async function restore() {
  if (busy.value || disposed || !selected || !inspection.value || inspectedFile !== selected || !confirmed.value) return
  const file = selected
  busy.value = true
  try {
    const r = await restoreBackup(file)
    if (disposed || selected !== file || inspectedFile !== file) return
    result.value = [r.data.message, r.data.stockNotificationMessage].filter(Boolean).join(' ')
    inspection.value = null
    confirmed.value = false
    selected = null
    inspectedFile = null
    emit('restored')
    window.dispatchEvent(new Event('care-patients-changed'))
  } catch (error) {
    if (!disposed && error.code !== 'ERR_CANCELED') {
      restoreUncertain.value = true
      inspection.value = null
      selected = null
      inspectedFile = null
      confirmed.value = false
      window.dispatchEvent(new Event('care-patients-changed'))
    }
  } finally {
    busy.value = false
  }
}
onUnmounted(() => {
  disposed = true
  selected = null
  inspectedFile = null
  inspection.value = null
  confirmed.value = false
  busy.value = false
})
</script>

<style scoped>
.backup-coverage{margin:16px 0}.backup-coverage summary{cursor:pointer;font-weight:600}.backup-coverage ul{padding-left:24px}.backup-coverage li{line-height:1.7;margin:8px 0}.backup-coverage-limit{margin:16px 0}.backup-actions{display:flex;flex-wrap:wrap;gap:16px;align-items:center;margin:20px 0}.backup-preview{max-width:700px;margin-top:24px}.restore-confirmation{display:flex;height:auto;margin:20px 0;white-space:normal}.restore-confirmation :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.backup-panel p{line-height:1.7}.file-label{display:flex;gap:10px;flex-wrap:wrap;align-items:center}@media(max-width:600px){.backup-actions>*{width:100%}.file-label input{max-width:100%}}
</style>
