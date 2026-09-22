<template>
  <section class="backup-panel">
    <h2>家庭记录备份与恢复</h2>
    <p>仅导出有完整档案访问权限的患者资料，包含支持的家庭档案、透析与指标记录、用药、库存、医疗报告和照护事项；附件仅包含已存入数据库的内容。</p>
    <el-alert title="这不是全平台备份：不包含问诊与聊天、照护全流程记录、医生工作台、数据授权和系统账号。灾备请另行备份数据库与附件文件。" type="warning" :closable="false" />
    <div class="backup-actions">
      <el-button type="primary" :loading="busy" @click="download">下载家庭记录备份</el-button>
      <label class="file-label">选择备份文件 <input type="file" accept=".zip" :disabled="busy" @change="preview" /></label>
    </div>
    <el-alert title="恢复会创建独立副本，不覆盖当前数据。用药提醒与自动分析默认关闭；通知渠道需重新配置，照护成员需重新邀请。" type="info" :closable="false" />
    <section v-if="inspection" class="backup-preview">
      <h3>备份预览</h3>
      <p>创建时间：{{ inspection.createdAt }}</p>
      <p>家庭成员：{{ inspection.patients?.map(p => p.name).join('、') }}</p>
      <el-table :data="Object.entries(inspection.counts || {}).map(([table, count]) => ({ table, count }))"><el-table-column prop="table" label="记录类型" /><el-table-column prop="count" label="数量" /></el-table>
      <el-alert v-for="(w, i) in inspection.warnings || []" :key="i" :title="w" type="warning" :closable="false" />
      <el-checkbox v-model="confirmed" class="restore-confirmation">我已了解备份将恢复为独立副本。</el-checkbox>
      <el-button type="primary" :disabled="!confirmed" :loading="busy" @click="restore">恢复为副本</el-button>
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
    ElMessage.success('备份已下载')
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
