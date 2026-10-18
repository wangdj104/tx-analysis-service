<template>
  <section class="backup-panel">
    <h2>家庭记录备份与恢复</h2>
    <p>仅导出有完整档案访问权限的患者的 v1 合格记录子集。</p>
    <el-alert title="v1 仅导出限定记录子集，并非完整数据库、附件或历史备份。列出的类别不代表包含完整历史；灾备请另行备份数据库和附件文件。" type="warning" :closable="false" />
    <details class="backup-coverage">
      <summary>查看 v1 支持范围与排除项</summary>
      <p><strong>符合条件时支持的记录子集</strong></p>
      <ul>
        <li>患者档案、临床资料和个人目标；透析记录与排程、干体重、血压记录与分析、营养和并发症。</li>
        <li>符合条件的独立用药、旧提醒、服药记录、库存、变动和行动。</li>
        <li>未纳管病历及检查项，包括已存入数据库的附件内容。</li>
        <li>支持的 AI 分析与自动分析设置、旧告警规则及符合条件的告警；普通事件、照护事项和普通问题文字副本。问题文字可能是当前投影文字，也可能是保留的较旧原生快照，不包含专业答复历史。</li>
      </ul>
      <p><strong>不包含的内容</strong></p>
      <ul>
        <li>问诊与聊天、照护全流程、医生工作台、共同目标、协作计划、护理分配、授权、账号、通知渠道、发件箱和命令。</li>
        <li>协作用药、库存核对、家庭交接、紧急响应和测量随访历史，以及其归属的快照、版本、行动、回执、事件和证据。</li>
        <li>已纳管病历、检查项和附件、更正与引用历史，以及依赖已省略检查项的告警。</li>
        <li>已纳管日历预约、医生预约、就诊记录、到诊事实、诊后决定、后继历史和活动，包括就诊摘要副本。</li>
        <li>专业问题答复、状态、所有权、命令和历史，以及现代就诊关联。</li>
        <li>存放在数据库以外的附件内容。</li>
      </ul>
    </details>
    <el-alert title="本次新恢复副本的库存提醒默认关闭，需逐项核对后单独启用。此版本之前已恢复的历史库存不会被自动纳入此保护。" type="warning" :closable="false" />
    <div class="backup-actions">
      <el-button type="primary" :loading="busy" @click="download">下载家庭记录备份</el-button>
      <label class="file-label">选择备份文件 <input type="file" accept=".zip" :disabled="busy" @change="preview" /></label>
    </div>
    <el-alert title="恢复会创建独立副本，不覆盖当前数据。用药提醒与自动分析默认关闭；通知渠道需重新配置，照护成员需重新邀请。" type="info" :closable="false" />
    <section v-if="inspection" class="backup-preview">
      <h3>备份预览</h3>
      <p>创建时间：{{ inspection.createdAt }}</p>
      <p>家庭成员：{{ inspection.patients?.map(p => p.name).join('、') }}</p>
      <p class="backup-coverage-limit"><strong>当前 v1 固定范围限制：</strong>仅可恢复符合条件的受支持记录子集。此文件不能恢复完整数据库、附件存储或纳管与协作历史。请查看上方 v1 支持范围与排除项。</p>
      <p>数量仅表示此文件中可接受记录类型的行数，不能证明完整数据库、附件或历史均已覆盖。</p>
      <el-table :data="Object.entries(inspection.counts || {}).map(([table, count]) => ({ table, count }))"><el-table-column prop="table" label="记录类型" /><el-table-column prop="count" label="数量" /></el-table>
      <h4 v-if="(inspection.warnings || []).length">预览提示</h4>
      <el-alert v-for="(w, i) in inspection.warnings || []" :key="i" :title="w" type="warning" :closable="false" />
      <el-checkbox v-model="confirmed" :disabled="busy" class="restore-confirmation">我已了解备份将恢复为独立副本。</el-checkbox>
      <el-button type="primary" :disabled="!confirmed || busy" :loading="busy" @click="restore">恢复为副本</el-button>
    </section>
    <el-alert v-if="result" :title="result" type="success" :closable="false" />
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
    ElMessage.success('备份已下载')
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
