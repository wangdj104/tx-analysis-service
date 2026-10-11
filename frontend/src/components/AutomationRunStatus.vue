<template>
  <section v-if="rows.length" aria-label="Automation run status" class="automation-run-status">
    <h3>Automation run status</h3>
    <article v-for="row in rows" :key="row.id"><b>{{ row.taskName }}</b><span>{{ stateText(row.lastRunStatus) }}</span><p v-if="row.lastError">{{ row.lastError }}</p><el-button v-if="isUncertain(row)" type="warning" :disabled="busyId != null" :loading="busyId === row.id" @click="acknowledge(row)">Confirm interrupted run</el-button></article>
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
function stateText(state) { return ({CLAIMED:'Run claimed',GENERATING:'Generating draft',DRAFT_SAVED:'Draft saved; notice pending',NOTIFYING:'Sending draft notice',UNKNOWN:'AI result uncertain; confirmation required',NOTIFY_UNKNOWN:'Draft saved; notification needs confirmation'})[state] || state }
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
    await ElMessageBox.confirm(observedState === 'NOTIFY_UNKNOWN' ? 'The draft is saved, but notification delivery is uncertain. Confirmation keeps the existing draft and clears the block without generating or sending again. Continue?' : 'The AI request may have completed without a saved draft. Confirmation starts a new request and may repeat processing. Continue?', 'Confirm interrupted run', { type: 'warning' })
    if (selected !== props.patientId) return
    await runHealthAutomation(row.id, true, observedId, observedState)
    if (selected !== props.patientId) return
    ElMessage.success(observedState === 'NOTIFY_UNKNOWN' ? 'Existing draft kept; notifications were not sent again.' : 'New draft created for review.')
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
