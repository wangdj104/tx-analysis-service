<template>
  <section class="backup-panel">
    <h2>Family record backup and restore</h2>
    <p>Export supported family profiles, dialysis and vital records, medications, inventory, medical reports and care items for patients whose full records you can access. Attachments are included only when their content is stored in the database.</p>
    <el-alert title="This is not a full platform backup. Consultations and messages, care-journey records, doctor workspaces, access grants and system accounts are excluded. Maintain separate database and file backups for disaster recovery." type="warning" :closable="false" />
    <div class="backup-actions">
      <el-button type="primary" :loading="busy" @click="download">Download family records</el-button>
      <label class="file-label">Choose backup file <input type="file" accept=".zip" :disabled="busy" @change="preview" /></label>
    </div>
    <el-alert title="Restore creates independent copies without overwriting current data. Medication reminders and automated analysis are disabled; notification channels and caregiver invitations must be configured again." type="info" :closable="false" />
    <section v-if="inspection" class="backup-preview">
      <h3>Backup preview</h3>
      <p>Created: {{ inspection.createdAt }}</p>
      <p>Family members: {{ inspection.patients?.map(p => p.name).join(', ') }}</p>
      <el-table :data="Object.entries(inspection.counts || {}).map(([table, count]) => ({ table, count }))"><el-table-column prop="table" label="Record type" /><el-table-column prop="count" label="Count" /></el-table>
      <el-alert v-for="(w, i) in inspection.warnings || []" :key="i" :title="w" type="warning" :closable="false" />
      <el-checkbox v-model="confirmed" class="restore-confirmation">I understand that this will restore the backup as independent copies.</el-checkbox>
      <el-button type="primary" :disabled="!confirmed" :loading="busy" @click="restore">Restore as copies</el-button>
    </section>
    <el-alert v-if="result" :title="result" type="success" :closable="false" />
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadBackup, previewBackup, restoreBackup } from '@/api/care'

const emit = defineEmits(['restored'])
const busy = ref(false)
const inspection = ref(null)
const confirmed = ref(false)
const result = ref('')
let selected

async function download() {
  busy.value = true
  try {
    const blob = await downloadBackup()
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
  selected = e.target.files?.[0]
  inspection.value = null
  confirmed.value = false
  result.value = ''
  if (!selected) return
  busy.value = true
  try {
    inspection.value = (await previewBackup(selected)).data
  } finally {
    busy.value = false
    e.target.value = ''
  }
}

async function restore() {
  if (!selected || !confirmed.value) return
  busy.value = true
  try {
    const r = await restoreBackup(selected)
    result.value = r.data.message
    inspection.value = null
    confirmed.value = false
    selected = null
    emit('restored')
    window.dispatchEvent(new Event('care-patients-changed'))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.backup-actions{display:flex;flex-wrap:wrap;gap:16px;align-items:center;margin:20px 0}.backup-preview{max-width:700px;margin-top:24px}.restore-confirmation{display:flex;height:auto;margin:20px 0;white-space:normal}.restore-confirmation :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.backup-panel p{line-height:1.7}.file-label{display:flex;gap:10px;flex-wrap:wrap;align-items:center}@media(max-width:600px){.backup-actions>*{width:100%}.file-label input{max-width:100%}}
</style>
