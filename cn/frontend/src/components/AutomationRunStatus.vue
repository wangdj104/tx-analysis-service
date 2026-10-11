<template>
  <section v-if="rows.length" aria-label="自动任务运行状态" class="automation-run-status">
    <h3>自动任务运行状态</h3>
    <article v-for="row in rows" :key="row.id"><b>{{ row.taskName }}</b><span>{{ stateText(row.lastRunStatus) }}</span><p v-if="row.lastError">{{ row.lastError }}</p><el-button v-if="isUncertain(row)" type="warning" :disabled="busyId != null" :loading="busyId === row.id" @click="acknowledge(row)">确认中断运行</el-button></article>
  </section>
</template>
<script setup>
import { ref, watch } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { listHealthAutomations, runHealthAutomation } from '@/api/healthAutomation'
const props = defineProps({ patientId: [String, Number], refreshKey: Object })
const emit = defineEmits(['acknowledged'])
const rows = ref([]), busyId = ref(null)
let version = 0
function isUncertain(row) { return ['UNKNOWN', 'NOTIFY_UNKNOWN'].includes(row.lastRunStatus) }
function stateText(state) { return ({CLAIMED:'运行已认领',GENERATING:'正在生成草稿',DRAFT_SAVED:'草稿已保存，通知待发送',NOTIFYING:'正在发送草稿通知',UNKNOWN:'AI 结果不确定，需要确认',NOTIFY_UNKNOWN:'草稿已保存，通知结果需要确认'})[state] || state }
async function load() {
  const requestVersion = ++version, selected = props.patientId
  rows.value = []
  if (!selected) return
  try {
    const response = await listHealthAutomations()
    if (requestVersion !== version || selected !== props.patientId) return
    rows.value = (response.data || []).filter(row => Number(row.patientId) === Number(selected) && ['CLAIMED','GENERATING','DRAFT_SAVED','NOTIFYING','UNKNOWN','NOTIFY_UNKNOWN'].includes(row.lastRunStatus))
  } catch {}
}
async function acknowledge(row) {
  if (busyId.value != null || !isUncertain(row) || Number(row.patientId) !== Number(props.patientId)) return
  const selected = props.patientId, observedId = row.uncertainRunId, observedState = row.uncertainRunState
  if (observedId == null || observedState !== row.lastRunStatus) { await load(); return }
  busyId.value = row.id
  try {
    await ElMessageBox.confirm(observedState === 'NOTIFY_UNKNOWN' ? '草稿已保存，但通知结果不确定。确认后保留原稿并解除阻塞，不会再次生成或发送通知。继续吗？' : '上次 AI 请求可能已完成，但未保存草稿。确认后将发起一次新请求，可能重复处理。继续吗？', '确认中断运行', { type: 'warning' })
    if (selected !== props.patientId) return
    await runHealthAutomation(row.id, true, observedId, observedState)
    if (selected !== props.patientId) return
    ElMessage.success(observedState === 'NOTIFY_UNKNOWN' ? '已保留原草稿，未重新发送通知。' : '新草稿已生成并进入待审核队列。')
    emit('acknowledged')
    await load()
  } catch { if (selected === props.patientId) await load() }
  finally { busyId.value = null }
}
watch(() => [props.patientId, props.refreshKey], load, { immediate: true })
defineExpose({ refresh: load })
</script>
<style scoped>
.automation-run-status{padding:18px;border:1px solid var(--line);border-radius:14px;margin-bottom:18px}.automation-run-status h3{margin:0 0 12px}.automation-run-status article{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-top:8px}.automation-run-status p{flex-basis:100%;margin:0;color:var(--danger)}
</style>
