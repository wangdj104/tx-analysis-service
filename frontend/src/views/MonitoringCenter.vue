<template>
  <main class="monitoring-page">
    <header class="monitoring-head">
      <div class="monitoring-title">
        <p class="monitoring-head__eyebrow"><span class="live-dot"></span>healthmonitoring</p>
        <h1>{{ snapshot.patient?.name ? `${snapshot.patient.name} healthStatus` : 'healthStatusoverview' }}</h1>
        <p>firstviewneedneedprocess item, againViewtrend and Detailedrecord. </p>
      </div>
      <picture class="monitoring-head-art">
        <source media="(max-width: 900px)" :srcset="careMomentsSmall" />
        <img :src="careMoments" alt="" width="180" height="120" decoding="async" />
      </picture>
      <div class="monitoring-controls" aria-label="monitoringworktoolcolumn">
        <label class="control-field">
          <span>trendrange</span>
          <el-select v-model="days" class="range-select" aria-label="trendTime range" @change="loadSnapshot()">
            <el-option label="recent  7 days" :value="7" />
            <el-option label="recent  30 days" :value="30" />
            <el-option label="recent  90 days" :value="90" />
          </el-select>
        </label>
        <label class="control-field control-field--switch">
          <span>Run checks</span>
          <el-tooltip :content="autoRefresh ? 'Check for new records every 30 seconds' : 'Automatic refresh is off'">
            <el-switch v-model="autoRefresh" inline-prompt active-text="open" inactive-text="close" @change="resetTimer" />
          </el-tooltip>
        </label>
        <el-button class="refresh-button" :loading="loading" @click="loadSnapshot()"><el-icon><Refresh /></el-icon>Refresh data</el-button>
      </div>
    </header>

    <el-alert v-if="!patientId" title="Please first in sidebardown sideselectonePatient, againViewmonitoringdata. " type="warning" :closable="false" show-icon />

    <template v-else>
      <section class="status-hero" :class="`status-hero--${statusTone}`" v-loading="loading && !snapshot.generatedAt">
        <div class="status-orb"><el-icon :size="34"><component :is="statusIcon" /></el-icon></div>
        <div class="status-copy">
          <span class="status-label">currentdataStatus</span>
          <h2>{{ snapshot.statusLabel || 'positivein summarizehealthrecord' }}</h2>
          <p>{{ primarySuggestion }}</p>
        </div>
        <div class="status-side">
          <div class="status-meta">
            <span><i></i>{{ autoRefresh ? 'Watching for new records' : 'Manual checks' }}</span>
            <small>Statusupdate {{ formatDateTime(snapshot.generatedAt) }}</small>
            <small>most recent record {{ formatRelative(snapshot.lastDataAt) }}</small>
          </div>
          <div class="status-actions">
            <el-button type="primary" @click="$router.push('/bp-self-monitor')">entryhealthdata</el-button>
            <el-button type="primary" plain @click="$router.push('/family-health')">Viewtoday Dayplan</el-button>
          </div>
        </div>
      </section>

      <nav class="illustrated-shortcuts" aria-label="commonhealthfeature">
        <router-link v-for="item in illustratedShortcuts" :key="item.path" :to="item.path" class="illustrated-shortcut">
          <img :src="item.image" width="116" height="77" alt="" decoding="async" />
          <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
        </router-link>
      </nav>

      <section class="monitor-metrics">
        <article class="monitor-metric">
          <span class="monitor-metric__icon monitor-metric__icon--alert"><el-icon><Bell /></el-icon></span>
          <div><strong>{{ metrics.activeAlertCount ?? 0 }}</strong><span>activityalert</span><small>{{ metrics.criticalAlertCount || 0 }} itemsSevere</small></div>
        </article>
        <article class="monitor-metric">
          <span class="monitor-metric__icon"><el-icon><CircleCheck /></el-icon></span>
          <div><strong>{{ metrics.completedTaskCount ?? 0 }}/{{ metrics.todayTaskCount ?? 0 }}</strong><span>today Daytask</span><small>completerate {{ metrics.adherenceRate ?? 0 }}%</small></div>
        </article>
        <article class="monitor-metric">
          <span class="monitor-metric__icon"><el-icon><DataLine /></el-icon></span>
          <div><strong>{{ metrics.dataCompleteness ?? 0 }}%</strong><span>datacover</span><small>by monitoringsourcestatistics</small></div>
        </article>
        <article class="monitor-metric">
          <span class="monitor-metric__icon"><el-icon><Clock /></el-icon></span>
          <div><strong class="monitor-metric__time">{{ latestUpdateText }}</strong><span>most recent update</span><small>{{ formatDateTime(snapshot.lastDataAt) }}</small></div>
        </article>
      </section>

      <section class="signal-grid">
        <article v-for="signal in snapshot.signals || []" :key="signal.key" class="signal-card" :class="`signal-card--${signalTone(signal.status)}`">
          <header><span>{{ signal.label }}</span><em><i></i>{{ signal.statusLabel }}</em></header>
          <div class="signal-value">{{ signal.value }} <small>{{ signal.unit }}</small></div>
          <footer><span>{{ signal.freshnessText }}</span><span>{{ shortDateTime(signal.updatedAt) }}</span></footer>
        </article>
      </section>

      <section class="monitoring-main-grid">
        <article class="monitor-panel trend-panel">
          <header class="panel-head">
            <div><span class="panel-kicker">trendmonitoring</span><h2>vital signschange</h2><p>Abnormaldatapointwillusediamondmark. </p></div>
            <div class="chart-legend"><span><i class="legend-systolic"></i>Systolic Pressure</span><span><i class="legend-diastolic"></i>Diastolic Pressure</span><span><i class="legend-glucose"></i>Blood Glucose</span></div>
          </header>
          <v-chart v-if="hasTrendData" class="monitor-chart" :option="chartOption" autoresize />
          <el-empty v-else description="currentTime rangenohas vital signsrecord">
            <el-button type="primary" @click="$router.push('/bp-self-monitor')">gorecordBlood Pressure & Glucose</el-button>
          </el-empty>
        </article>

        <article class="monitor-panel alert-panel">
          <header class="panel-head panel-head--compact">
            <div><span class="panel-kicker">Riskteamcolumn</span><h2>activityalert</h2></div>
            <el-button text type="primary" :loading="checking" @click="runCheck">againExamination</el-button>
          </header>
          <div v-if="activeAlerts.length" class="alert-list">
            <article v-for="alert in activeAlerts.slice(0, 8)" :key="alert.id" class="alert-row" :class="`alert-row--${(alert.level || 'INFO').toLowerCase()}`">
              <span class="alert-level">{{ alertLevel(alert.level) }}</span>
              <div class="alert-row__body"><strong>{{ alert.title || 'healthindicatorAbnormal' }}</strong><p>{{ alert.value || 'Please ViewDetailedrecord' }}</p><small>{{ formatDateTime(alert.triggeredAt) }}</small></div>
              <div class="alert-actions">
                <el-button v-if="alert.status === 'PENDING'" size="small" @click="acknowledgeAlert(alert)">Confirm</el-button>
                <el-button size="small" type="primary" plain @click="resolveAlert(alert)">process</el-button>
              </div>
            </article>
          </div>
          <div v-else class="safe-empty"><el-icon :size="30"><CircleCheckFilled /></el-icon><strong>currentnohas activityalert</strong><span>keep it uprecord, Abnormalchangewillappearin here. </span></div>
        </article>
      </section>

      <section class="monitoring-lower-grid">
        <article class="monitor-panel task-panel">
          <header class="panel-head panel-head--compact"><div><span class="panel-kicker">Care Plan</span><h2>today Daytask</h2></div><span class="panel-count">{{ todayTasks.length }} item</span></header>
          <div v-if="todayTasks.length" class="task-list">
            <article v-for="task in todayTasks" :key="`${task.taskType}-${task.id}`" class="task-item" :class="{ 'task-item--done': isTaskDone(task) }">
              <span class="task-time">{{ timeOnly(task.scheduledAt) }}</span>
              <span class="task-icon"><el-icon><component :is="task.taskType === 'MEDICATION' ? 'FirstAidKit' : 'Calendar'" /></el-icon></span>
              <div><strong>{{ task.title }}</strong><p>{{ task.dosage || task.description || taskTypeText(task.taskType) }}</p></div>
              <el-tag :type="taskTagType(task.status)" effect="light">{{ taskStatusText(task.status) }}</el-tag>
              <el-button v-if="task.taskType === 'MEDICATION' && ['PENDING','MISSED','SNOOZED'].includes(task.status)" size="small" type="primary" @click="completeMedication(task)">marktaken</el-button>
              <el-button v-if="task.taskType === 'DIALYSIS'" size="small" @click="$router.push('/family-health')">managementschedule</el-button>
            </article>
          </div>
          <div v-else class="simple-empty">Nothing needs attention todaytask</div>
        </article>

        <article class="monitor-panel event-panel">
          <header class="panel-head panel-head--compact"><div><span class="panel-kicker">healthtrajectory</span><h2>most recent event</h2></div><el-button text type="primary" @click="$router.push('/family-health')">recordevent</el-button></header>
          <div v-if="recentEvents.length" class="event-list">
            <article v-for="event in recentEvents.slice(0, 7)" :key="`${event.sourceType}-${event.id}`" class="event-item">
              <span class="event-dot"></span>
              <div><strong>{{ event.title || eventTypeText(event.type) }}</strong><p>{{ event.summary || 'No additional details' }}</p><small>{{ event.date }} {{ event.time || '' }} · {{ eventTypeText(event.type) }}</small></div>
            </article>
          </div>
          <div v-else class="simple-empty">No health events</div>
        </article>
      </section>

      <section class="care-guidance">
        <header><el-icon><Opportunity /></el-icon><div><strong>currentcarerecommendation</strong><span>based onmonitoringdataAutomaticwholemanage, onlyasfor Health Managementreference. </span></div></header>
        <ol><li v-for="item in snapshot.careSuggestions || []" :key="item">{{ item }}</li></ol>
      </section>
    </template>
  </main>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Bell, Calendar, CircleCheck, CircleCheckFilled, Clock, DataLine,
  FirstAidKit, Opportunity, Refresh, SuccessFilled, WarningFilled
} from '@element-plus/icons-vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { getMonitoringSnapshot } from '@/api/monitoring'
import { acknowledge, checkThresholds, resolve } from '@/api/alert'
import { actionIntake } from '@/api/familyHealth'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import { readPermissionCache } from '@/utils/authSession'
import { canAccessWorkspace, resolveWorkspaceEntry } from '@/utils/workspaceAccess'
import careMoments from '@/assets/illustrations/care-moments.webp'
import careMomentsSmall from '@/assets/illustrations/care-moments-small.webp'
import healthJournalSmall from '@/assets/illustrations/health-journal-small.webp'
import medicationCareSmall from '@/assets/illustrations/medication-care-small.webp'
import dialysisCareSmall from '@/assets/illustrations/dialysis-care-small.webp'
import recordsCareSmall from '@/assets/illustrations/records-care-small.webp'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent])

const { currentPatientId } = useCurrentPatient()
const patientId = computed(() => currentPatientId.value)
const snapshot = ref({ signals: [], activeAlerts: [], todayTasks: [], recentEvents: [], careSuggestions: [], metrics: {}, vitalTrend: [] })
const loading = ref(false)
const checking = ref(false)
const days = ref(7)
const loadedDays = ref(7)
const loadError = ref('')
const autoRefresh = ref(true)
let timer = null
let snapshotRequestEpoch = 0

const metrics = computed(() => snapshot.value.metrics || {})
const activeAlerts = computed(() => snapshot.value.activeAlerts || [])
const todayTasks = computed(() => snapshot.value.todayTasks || [])
const recentEvents = computed(() => snapshot.value.recentEvents || [])
const primarySuggestion = computed(() => snapshot.value.careSuggestions?.[0] || 'durationrecordafter , systemwillprovidemorecan rely on trenddetermine. ')
const statusTone = computed(() => ({ CRITICAL: 'critical', WARNING: 'warning', STABLE: 'stable', NO_DATA: 'empty' }[snapshot.value.overallStatus] || 'empty'))
const statusIcon = computed(() => snapshot.value.overallStatus === 'STABLE' ? SuccessFilled : WarningFilled)
const hasTrendData = computed(() => (snapshot.value.vitalTrend || []).some(p => p.systolic != null || p.diastolic != null || p.glucose != null))
const latestUpdateText = computed(() => formatRelative(snapshot.value.lastDataAt))
const illustratedShortcuts = computed(() => {
  const { menuPaths = [], roleCodes = [] } = readPermissionCache() || {}
  return [
    { path: '/bp-self-monitor', label: 'Blood Pressure & Glucose', description: 'Daily measurements and records', image: healthJournalSmall },
    { path: '/medication', label: 'Medication Management', description: 'Medication, Reminder and Medication Log', image: medicationCareSmall },
    { path: '/dialysis', label: 'Dialysis Records', description: 'templateentry and Trend Analysis', image: dialysisCareSmall },
    { path: '/medical-record', label: 'Medical Records', description: 'Report and indicatorchange', image: recordsCareSmall }
  ].filter(item => canAccessWorkspace(item.path, menuPaths, roleCodes))
    .map(item => ({ ...item, path: resolveWorkspaceEntry(item.path, menuPaths, roleCodes) }))
})

const chartOption = computed(() => {
  const rows = snapshot.value.vitalTrend || []
  const labels = rows.map(p => `${String(p.date || '').slice(5)} ${p.time || ''}`.trim())
  const symbol = p => p.abnormal ? 'diamond' : 'circle'
  return {
    animationDuration: 350,
    tooltip: {
      trigger: 'axis', renderMode: 'richText', backgroundColor: 'rgba(12,47,45,.94)', borderWidth: 0,
      textStyle: { color: '#fff' },
      formatter(params = []) {
        const items = Array.isArray(params) ? params : [params]
        const row = rows[items[0]?.dataIndex]
        const lines = [items[0]?.axisValueLabel || 'measurementrecord', ...items.map(item => `${item.seriesName}: ${item.value ?? '—'}`)]
        if (row?.abnormal) lines.push('this timepointstorein Abnormalmark, can cancomeselfOtherindicator, Please combineoriginalrecordView. ')
        return lines.join('\n')
      }
    },
    grid: { left: 48, right: 50, top: 28, bottom: 52 },
    xAxis: { type: 'category', data: labels, boundaryGap: false, axisLine: { lineStyle: { color: '#dbe7e4' } }, axisTick: { show: false }, axisLabel: { color: '#708783', hideOverlap: true } },
    yAxis: [
      { type: 'value', name: 'mmHg', min: 40, axisLabel: { color: '#708783' }, splitLine: { lineStyle: { color: '#edf3f1' } } },
      { type: 'value', name: 'mmol/L', min: 0, axisLabel: { color: '#708783' }, splitLine: { show: false } }
    ],
    series: [
      { name: 'Systolic Pressure', type: 'line', smooth: .25, connectNulls: true, data: rows.map(p => ({ value: p.systolic, symbol: symbol(p) })), symbolSize: 7, lineStyle: { width: 2.5, color: '#236b63' }, itemStyle: { color: '#236b63' }, markLine: { silent: true, symbol: 'none', lineStyle: { color: '#d6a642', type: 'dashed' }, label: { color: '#8a6110', formatter: 'reference 140' }, data: [{ yAxis: 140 }] } },
      { name: 'Diastolic Pressure', type: 'line', smooth: .25, connectNulls: true, data: rows.map(p => ({ value: p.diastolic, symbol: symbol(p) })), symbolSize: 7, lineStyle: { width: 2.2, color: '#589b91' }, itemStyle: { color: '#589b91' } },
      { name: 'Blood Glucose', type: 'line', yAxisIndex: 1, smooth: .25, connectNulls: true, data: rows.map(p => ({ value: p.glucose, symbol: symbol(p) })), symbolSize: 7, lineStyle: { width: 2.2, color: '#d59c2f' }, itemStyle: { color: '#d59c2f' } }
    ]
  }
})

async function loadSnapshot(silent = false) {
  const requestedPatientId = patientId.value
  const requestedDays = days.value
  const requestEpoch = ++snapshotRequestEpoch
  if (!requestedPatientId) return
  if (!silent) loading.value = true
  try {
    const res = await getMonitoringSnapshot(requestedPatientId, requestedDays)
    if (requestEpoch !== snapshotRequestEpoch || requestedPatientId !== patientId.value) return
    if (res.code === 200) {
      snapshot.value = res.data || {}
      loadedDays.value = requestedDays
      loadError.value = ''
    }
  } catch (error) {
    if (requestEpoch !== snapshotRequestEpoch || requestedPatientId !== patientId.value) return
    if (!silent) {
      days.value = loadedDays.value
      loadError.value = error.message || 'monitoringdataFailed to load'
      ElMessage.error(loadError.value)
    }
  } finally {
    if (requestEpoch === snapshotRequestEpoch) loading.value = false
  }
}

function resetTimer() {
  if (timer) window.clearInterval(timer)
  timer = null
  if (autoRefresh.value) timer = window.setInterval(() => {
    if (!document.hidden) loadSnapshot(true)
  }, 30000)
}

async function runCheck() {
  checking.value = true
  try {
    await checkThresholds(patientId.value)
    await loadSnapshot(true)
    ElMessage.success('alert ruleExaminationcomplete')
  } finally {
    checking.value = false
  }
}

async function acknowledgeAlert(alert) {
  await acknowledge(alert.id)
  ElMessage.success('Alert confirmed')
  await loadSnapshot(true)
}

async function resolveAlert(alert) {
  const result = await ElMessageBox.prompt('Describe the action taken, repeat measurement, or follow-up plan.', 'Resolve alert', {
    confirmButtonText: 'Mark as resolved', cancelButtonText: 'Cancel', inputType: 'textarea',
    inputPlaceholder: 'for example: rest 10 minutesafter remeasure 138/86, continueobserve',
    inputValidator: value => value?.trim() ? true : 'Enter a resolution note'
  }).catch(() => null)
  if (!result) return
  await resolve(alert.id, result.value.trim())
  ElMessage.success('Alert resolved')
  await loadSnapshot(true)
}

async function completeMedication(task) {
  await actionIntake(task.id, 'TAKEN', '')
  ElMessage.success('Medication intake recorded')
  await loadSnapshot(true)
}

function signalTone(status) { return ({ CRITICAL: 'critical', WARNING: 'warning', DELAYED: 'delayed', NORMAL: 'normal', PENDING: 'pending' }[status] || 'empty') }
function alertLevel(level) { return ({ CRITICAL: 'Severe', WARNING: 'warning', INFO: 'Notice' }[level] || 'Notice') }
function isTaskDone(task) { return task.status === 'TAKEN' || task.status === 'COMPLETED' }
function taskStatusText(status) { return ({ PENDING: 'Pending', TAKEN: 'Taken', SNOOZED: 'Snoozed', SKIPPED: 'Skipped', MISSED: 'Missed', PLANNED: 'Planned', COMPLETED: 'Completed', CANCELLED: 'Cancelled' }[status] || status || 'Pending') }
function taskTagType(status) { return ({ TAKEN: 'success', COMPLETED: 'success', MISSED: 'danger', SKIPPED: 'info', SNOOZED: 'warning', CANCELLED: 'info' }[status] || 'warning') }
function taskTypeText(type) { return type === 'DIALYSIS' ? 'Dialysis Schedule' : 'medicationplan' }
function eventTypeText(type) { return ({ SYMPTOM: 'symptomrecord', VISIT: 'thenvisitrecord', NOTE: 'healthNotes', MEDICATION: 'medication', DIALYSIS: 'Dialysis', MEASUREMENT: 'measurement', INTAKE: 'medication intakecheck-in', MEDICATION_LOG: 'medicationrecord' }[type] || 'healthevent') }
function timeOnly(value) { return value ? String(value).slice(11, 16) : '—' }
function shortDateTime(value) { return value ? String(value).slice(5, 16).replace('T', ' ') : '—' }
function formatDateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : 'None' }
function formatRelative(value) {
  if (!value) return 'No data'
  const time = new Date(String(value).replace(' ', 'T')).getTime()
  if (Number.isNaN(time)) return formatDateTime(value)
  const minutes = Math.max(0, Math.floor((Date.now() - time) / 60000))
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} minutesbefore `
  if (minutes < 1440) return `${Math.floor(minutes / 60)} hoursbefore `
  return `${Math.floor(minutes / 1440)} daysbefore `
}

watch(patientId, () => {
  snapshotRequestEpoch++
  snapshot.value = { signals: [], activeAlerts: [], todayTasks: [], recentEvents: [], careSuggestions: [], metrics: {}, vitalTrend: [] }
  loadedDays.value = days.value
  loadError.value = ''
  loadSnapshot()
  resetTimer()
}, { immediate: true })
onMounted(() => { resetTimer(); document.addEventListener('visibilitychange', handleVisibility) })
onUnmounted(() => { if (timer) window.clearInterval(timer); document.removeEventListener('visibilitychange', handleVisibility) })
function handleVisibility() { if (!document.hidden && autoRefresh.value) loadSnapshot(true) }
</script>

<style scoped>
.monitoring-page {
  max-width: 1480px;
  margin: 0 auto;
  padding: 32px 34px 48px;
  color: var(--ink-950, #172421);
}

.monitoring-head {
  margin-bottom: 22px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
}

.monitoring-title {
  min-width: 0;
}

.monitoring-head-art {
  width: 180px;
  height: 120px;
  flex: 0 0 auto;
}

.monitoring-head-art img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.monitoring-head__eyebrow {
  margin: 0 0 8px;
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--care-700, #267266);
  font-size: 12px;
  font-weight: 750;
  letter-spacing: .08em;
}

.live-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #42a491;
  box-shadow: 0 0 0 5px rgb(66 164 145 / 13%);
}

.monitoring-head h1 {
  margin: 0;
  color: var(--ink-950, #172421);
  font-size: clamp(26px, 2.4vw, 34px);
  font-weight: 770;
  line-height: 1.16;
  letter-spacing: -.04em;
}

.monitoring-head p:last-child {
  margin: 9px 0 0;
  color: var(--ink-500, #70847f);
  font-size: 14px;
  line-height: 1.6;
}

.monitoring-controls {
  padding: 10px;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  flex: 0 0 auto;
  border: 1px solid var(--line, #dde8e5);
  border-radius: 14px;
  background: rgb(255 255 255 / 82%);
  box-shadow: 0 5px 20px rgb(16 45 42 / 4%);
}

.control-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.control-field > span {
  padding-left: 2px;
  color: var(--ink-500, #70847f);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .04em;
}

.control-field--switch {
  min-width: 72px;
  align-items: center;
}

.range-select {
  width: 112px;
}

.refresh-button {
  min-width: 104px;
}

.status-hero {
  position: relative;
  min-height: 168px;
  padding: 28px 30px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) minmax(240px, auto);
  align-items: center;
  gap: 22px;
  overflow: hidden;
  border: 1px solid #cce2dd;
  border-radius: 22px;
  background:
    radial-gradient(circle at 84% 10%, rgb(75 157 144 / 14%), transparent 30%),
    linear-gradient(135deg, #edf8f5 0%, #fbfdfc 70%);
  box-shadow: 0 16px 38px rgb(30 91 83 / 7%);
}

.status-hero::before {
  content: "";
  position: absolute;
  inset: 22px auto 22px 0;
  width: 4px;
  border-radius: 0 6px 6px 0;
  background: var(--care-600, #31877a);
}

.status-orb {
  width: 72px;
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--care-700, #267266);
  border: 1px solid #cae3dd;
  border-radius: 22px;
  background: rgb(255 255 255 / 70%);
  box-shadow: 0 10px 25px rgb(28 90 82 / 8%);
}

.status-copy {
  min-width: 0;
}

.status-label {
  color: var(--care-700, #267266);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: .1em;
}

.status-copy h2 {
  margin: 7px 0 8px;
  color: var(--ink-950, #172421);
  font-size: clamp(24px, 2.2vw, 31px);
  font-weight: 770;
  letter-spacing: -.035em;
}

.status-copy p {
  max-width: 680px;
  margin: 0;
  color: var(--ink-700, #405651);
  font-size: 14px;
  line-height: 1.65;
}

.status-side {
  min-width: 240px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 14px;
}

.status-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  color: var(--ink-500, #70847f);
}

.status-meta > span {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--care-700, #267266);
  font-size: 12px;
  font-weight: 700;
}

.status-meta i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #42a491;
  box-shadow: 0 0 0 4px rgb(66 164 145 / 12%);
}

.status-meta small {
  font-size: 11px;
}

.status-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.status-hero--warning {
  border-color: #ead8aa;
  background: radial-gradient(circle at 84% 10%, rgb(222 174 72 / 13%), transparent 30%), linear-gradient(135deg, #fff8e7, #fffdfa 70%);
}

.status-hero--warning::before {
  background: #d3a038;
}

.status-hero--warning .status-orb {
  color: #94600d;
  border-color: #ead8aa;
  background: #fff8e8;
}

.status-hero--critical {
  border-color: #edc8c4;
  background: radial-gradient(circle at 84% 10%, rgb(216 92 85 / 12%), transparent 30%), linear-gradient(135deg, #fff0ef, #fffafa 70%);
}

.status-hero--critical::before {
  background: #d85c55;
}

.status-hero--critical .status-orb {
  color: #b4453f;
  border-color: #edc8c4;
  background: #fff2f1;
}

.status-hero--empty {
  border-style: dashed;
  box-shadow: none;
}

.illustrated-shortcuts {
  margin: 18px 0 14px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.illustrated-shortcut {
  min-width: 0;
  min-height: 112px;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--ink-950, #172421);
  text-decoration: none;
  border: 1px solid var(--line, #dde8e5);
  border-radius: 17px;
  background: #fff;
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}

.illustrated-shortcut:hover {
  transform: translateY(-2px);
  border-color: #a9cdc5;
  box-shadow: 0 10px 26px rgb(16 45 42 / 7%);
}

.illustrated-shortcut img {
  width: 104px;
  height: 70px;
  flex: 0 0 auto;
  object-fit: contain;
}

.illustrated-shortcut span { min-width: 0; }
.illustrated-shortcut strong { display: block; font-size: 14px; }
.illustrated-shortcut small { display: block; margin-top: 6px; color: var(--ink-500, #70847f); font-size: 11px; line-height: 1.45; }

.monitor-metrics,
.signal-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.monitor-metrics {
  margin: 18px 0 14px;
}

.monitor-metric {
  min-width: 0;
  min-height: 104px;
  padding: 17px 18px;
  display: flex;
  align-items: center;
  gap: 14px;
  border: 1px solid var(--line, #dde8e5);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(16 45 42 / 3%);
}

.monitor-metric__icon {
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  color: var(--care-700, #267266);
  border-radius: 13px;
  background: var(--care-50, #eef8f5);
}

.monitor-metric__icon--alert {
  color: #a15b22;
  background: #fff4e5;
}

.monitor-metric > div {
  min-width: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: baseline;
  column-gap: 8px;
}

.monitor-metric strong {
  color: var(--ink-950, #172421);
  font-size: 25px;
  line-height: 1.1;
  letter-spacing: -.03em;
}

.monitor-metric__time {
  max-width: 118px;
  overflow: hidden;
  font-size: 18px !important;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.monitor-metric span {
  color: var(--ink-700, #405651);
  font-size: 12px;
  font-weight: 650;
}

.monitor-metric small {
  grid-column: 1 / -1;
  margin-top: 6px;
  color: var(--ink-400, #8fa09c);
  font-size: 11px;
}

.signal-grid {
  margin-bottom: 20px;
}

.signal-card {
  position: relative;
  min-width: 0;
  padding: 18px 19px;
  overflow: hidden;
  border: 1px solid var(--line, #dde8e5);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(16 45 42 / 3%);
}

.signal-card::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: #9bacaa;
}

.signal-card header,
.signal-card footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.signal-card header > span {
  color: var(--ink-700, #405651);
  font-size: 13px;
  font-weight: 700;
}

.signal-card em {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-500, #70847f);
  font-size: 11px;
  font-style: normal;
  font-weight: 650;
}

.signal-card em i {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #9bacaa;
}

.signal-value {
  margin: 17px 0 15px;
  overflow: hidden;
  color: var(--ink-950, #172421);
  font-size: 28px;
  font-weight: 760;
  letter-spacing: -.035em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.signal-value small {
  color: var(--ink-500, #70847f);
  font-size: 12px;
  font-weight: 550;
}

.signal-card footer {
  color: var(--ink-400, #8fa09c);
  font-size: 11px;
}

.signal-card--normal::before,
.signal-card--normal em i {
  background: #3d9b8c;
}

.signal-card--warning::before,
.signal-card--delayed::before,
.signal-card--warning em i,
.signal-card--delayed em i {
  background: #d3a038;
}

.signal-card--critical::before,
.signal-card--critical em i {
  background: #d85c55;
}

.signal-card--pending::before,
.signal-card--pending em i {
  background: #668ba4;
}

.monitoring-main-grid,
.monitoring-lower-grid {
  display: grid;
  gap: 20px;
  margin-bottom: 20px;
}

.monitoring-main-grid {
  grid-template-columns: minmax(0, 1.5fr) minmax(350px, .82fr);
}

.monitoring-lower-grid {
  grid-template-columns: minmax(0, 1.12fr) minmax(330px, .88fr);
}

.monitor-panel {
  min-width: 0;
  padding: 22px 23px;
  overflow: visible;
  border: 1px solid var(--line, #dde8e5);
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(16 45 42 / 3%), 0 9px 28px rgb(16 45 42 / 4%);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.panel-head--compact {
  align-items: center;
}

.panel-kicker {
  display: block;
  margin-bottom: 5px;
  color: var(--care-600, #31877a);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: .11em;
}

.panel-head h2 {
  margin: 0;
  color: var(--ink-950, #172421);
  font-size: 18px;
  font-weight: 740;
}

.panel-head p {
  margin: 6px 0 0;
  color: var(--ink-400, #8fa09c);
  font-size: 12px;
}

.panel-count {
  padding: 5px 10px;
  color: var(--care-800, #1c5a52);
  border-radius: 999px;
  background: var(--care-50, #eef8f5);
  font-size: 11px;
  font-weight: 700;
}

.chart-legend {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
  color: var(--ink-500, #70847f);
  font-size: 11px;
}

.chart-legend span {
  display: flex;
  align-items: center;
  gap: 6px;
}

.chart-legend i {
  width: 16px;
  height: 3px;
  border-radius: 2px;
}

.legend-systolic { background: #267266; }
.legend-diastolic { background: #68a49a; }
.legend-glucose { background: #d39a31; }

.monitor-chart {
  width: 100%;
  height: 350px;
  margin-top: 10px;
}

.alert-list {
  margin-top: 12px;
}

.alert-row {
  padding: 14px 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  gap: 8px 10px;
  border-bottom: 1px solid var(--line-soft, #edf2f1);
}

.alert-row:last-child {
  border-bottom: 0;
}

.alert-level {
  padding: 5px 8px;
  color: var(--ink-700, #405651);
  border-radius: 7px;
  background: #edf3f1;
  font-size: 10px;
  font-weight: 750;
}

.alert-row--critical .alert-level {
  color: #a73f39;
  background: #fde8e6;
}

.alert-row--warning .alert-level {
  color: #8a6110;
  background: #fff2d2;
}

.alert-row__body {
  min-width: 0;
}

.alert-row__body strong {
  display: block;
  overflow: hidden;
  color: var(--ink-950, #172421);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alert-row__body p {
  margin: 5px 0;
  color: var(--ink-700, #405651);
  font-size: 12px;
  line-height: 1.5;
}

.alert-row__body small {
  color: var(--ink-400, #8fa09c);
  font-size: 10px;
}

.alert-actions {
  grid-column: 2;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.alert-actions :deep(.el-button) {
  min-width: 70px;
  margin-left: 0;
}

.safe-empty,
.simple-empty {
  min-height: 190px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--ink-500, #70847f);
  text-align: center;
}

.safe-empty .el-icon {
  margin-bottom: 11px;
  color: #3d9b8c;
}

.safe-empty strong {
  margin-bottom: 7px;
  color: var(--ink-700, #405651);
  font-size: 14px;
}

.safe-empty span,
.simple-empty {
  font-size: 12px;
}

.task-list,
.event-list {
  margin-top: 12px;
}

.task-item {
  min-height: 70px;
  padding: 13px 0;
  display: grid;
  grid-template-columns: 46px 36px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--line-soft, #edf2f1);
}

.task-item:last-child {
  border-bottom: 0;
}

.task-item--done {
  opacity: .62;
}

.task-time {
  color: var(--ink-700, #405651);
  font-size: 12px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.task-icon {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--care-700, #267266);
  border-radius: 10px;
  background: var(--care-50, #eef8f5);
}

.task-item strong {
  color: var(--ink-950, #172421);
  font-size: 13px;
}

.task-item p {
  margin: 4px 0 0;
  color: var(--ink-400, #8fa09c);
  font-size: 11px;
}

.task-item :deep(.el-button) {
  margin-left: 0;
}

.event-item {
  position: relative;
  padding: 9px 0 14px;
  display: grid;
  grid-template-columns: 15px minmax(0, 1fr);
  gap: 10px;
}

.event-item::before {
  content: "";
  position: absolute;
  left: 5px;
  top: 20px;
  bottom: -3px;
  width: 1px;
  background: var(--line, #dde8e5);
}

.event-item:last-child::before {
  display: none;
}

.event-dot {
  z-index: 1;
  width: 10px;
  height: 10px;
  margin-top: 4px;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #3d9b8c;
  box-shadow: 0 0 0 2px #cfe5df;
}

.event-item strong {
  color: var(--ink-950, #172421);
  font-size: 12px;
}

.event-item p {
  margin: 4px 0;
  color: var(--ink-700, #405651);
  font-size: 11px;
  line-height: 1.55;
}

.event-item small {
  color: var(--ink-400, #8fa09c);
  font-size: 10px;
}

.care-guidance {
  padding: 20px 23px;
  border: 1px solid #cfe4de;
  border-radius: 18px;
  background: linear-gradient(135deg, #edf8f5, #fff);
}

.care-guidance header {
  display: flex;
  align-items: center;
  gap: 11px;
  color: var(--care-700, #267266);
}

.care-guidance header > .el-icon {
  font-size: 24px;
}

.care-guidance header div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.care-guidance header strong {
  color: var(--care-900, #163f3a);
  font-size: 14px;
}

.care-guidance header span {
  color: var(--ink-500, #70847f);
  font-size: 11px;
}

.care-guidance ol {
  margin: 15px 0 0;
  padding-left: 22px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 28px;
  color: var(--ink-700, #405651);
  font-size: 13px;
  line-height: 1.65;
}

@media (max-width: 1220px) {
  .monitoring-head-art { width: 130px; height: 88px; }

  .illustrated-shortcuts { grid-template-columns: repeat(2, minmax(0, 1fr)); }

  .monitor-metrics,
  .signal-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .monitoring-main-grid,
  .monitoring-lower-grid {
    grid-template-columns: 1fr;
  }

  .alert-panel {
    order: -1;
  }
}

@media (max-width: 900px) {
  .monitoring-head-art { display: none; }

  .monitoring-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .monitoring-controls {
    width: 100%;
  }

  .status-hero {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .status-side {
    grid-column: 1 / -1;
    width: 100%;
    padding-top: 16px;
    flex-direction: row;
    align-items: flex-end;
    justify-content: space-between;
    border-top: 1px solid rgb(38 114 102 / 12%);
  }

  .status-meta {
    align-items: flex-start;
  }
}

@media (max-width: 768px) {
  .monitoring-page {
    padding: 19px 14px 28px;
  }

  .monitoring-head {
    gap: 17px;
    margin-bottom: 18px;
  }

  .monitoring-head h1 {
    font-size: 25px;
  }

  .monitoring-controls {
    padding: 9px;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: end;
  }

  .range-select {
    width: 100%;
  }

  .control-field--switch {
    min-width: 82px;
  }

  .refresh-button {
    grid-column: 1 / -1;
    width: 100%;
  }

  .status-hero {
    min-height: auto;
    padding: 21px 18px;
    gap: 16px;
    border-radius: 18px;
  }

  .status-orb {
    width: 54px;
    height: 54px;
    border-radius: 16px;
  }

  .status-copy h2 {
    font-size: 22px;
  }

  .status-side {
    flex-direction: column;
    align-items: stretch;
  }

  .status-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .status-actions :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }

  .monitor-metrics,
  .signal-grid {
    gap: 10px;
  }

  .monitor-metric {
    min-height: 92px;
    padding: 14px;
  }

  .signal-card {
    padding: 16px;
  }

  .monitor-panel {
    padding: 18px 16px;
    border-radius: 16px;
  }

  .panel-head {
    flex-direction: column;
  }

  .panel-head--compact {
    flex-direction: row;
    align-items: center;
  }

  .chart-legend {
    justify-content: flex-start;
  }

  .monitor-chart {
    height: 290px;
  }

  .task-item {
    grid-template-columns: 40px 34px minmax(0, 1fr) auto;
  }

  .task-item > :deep(.el-button) {
    grid-column: 3 / -1;
    width: 100%;
  }

  .care-guidance ol {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 480px) {
  .illustrated-shortcuts { grid-template-columns: 1fr; }

  .monitor-metrics,
  .signal-grid {
    grid-template-columns: 1fr;
  }

  .monitor-metric {
    min-height: 82px;
  }

  .status-hero {
    grid-template-columns: 1fr;
  }

  .status-orb {
    width: 48px;
    height: 48px;
  }

  .status-actions {
    grid-template-columns: 1fr;
  }

  .alert-row {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .alert-actions {
    grid-column: 1 / -1;
    justify-content: stretch;
  }

  .alert-actions :deep(.el-button) {
    flex: 1;
  }

  .task-item {
    grid-template-columns: 38px 32px minmax(0, 1fr);
  }

  .task-item > :deep(.el-tag),
  .task-item > :deep(.el-button) {
    grid-column: 3;
    justify-self: stretch;
  }
}
</style>
