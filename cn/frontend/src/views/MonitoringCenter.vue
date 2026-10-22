<template>
  <main class="monitoring-page">
    <header class="monitoring-head">
      <div class="monitoring-title">
        <p class="monitoring-head__eyebrow"><span class="live-dot"></span>健康监测</p>
        <h1>{{ snapshot.patient?.name ? `${snapshot.patient.name}的健康状态` : '健康状态概览' }}</h1>
        <p>先查看待处理事项，再查看趋势和详细记录。</p>
      </div>
      <picture class="monitoring-head-art">
        <source media="(max-width: 900px)" :srcset="careMomentsSmall" />
        <img :src="careMoments" alt="" width="180" height="120" decoding="async" />
      </picture>
      <div class="monitoring-controls" aria-label="监测工具栏">
        <label class="control-field">
          <span>趋势范围</span>
          <el-select v-model="days" class="range-select" aria-label="趋势时间范围" @change="loadSnapshot()">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
        </label>
        <label class="control-field control-field--switch">
          <span>自动检查</span>
          <el-tooltip popper-class="monitoring-auto-refresh-tooltip" :trigger="['hover', 'focus']" :popper-options="{ modifiers: [{ name: 'preventOverflow', options: { padding: 8 } }] }" :content="autoRefresh ? '每 30 秒检查一次新记录' : '自动刷新已关闭'">
            <el-switch v-model="autoRefresh" inline-prompt active-text="开" inactive-text="关" @change="resetTimer" />
          </el-tooltip>
        </label>
        <el-button class="refresh-button" :loading="loading" @click="loadSnapshot()"><el-icon><Refresh /></el-icon>刷新数据</el-button>
      </div>
    </header>

    <el-alert v-if="!patientId" title="请先在顶部选择一名患者，再查看监测数据。" type="warning" :closable="false" show-icon />

    <!-- 更正入口不依赖概览读取成功，患者权限由原记录及更正接口分别核验。 -->
    <MeasurementCorrectionEntry :patient-id="patientId" :patient-name="currentPatientName" :key="route.fullPath" :onChanged="bindSnapshotChanged()" />

    <template v-if="patientId">
      <TrustedMeasurementTrend :patient-id="patientId" :patient-name="currentPatientName" :key="route.fullPath" :onChanged="bindSnapshotChanged()" />
      <div v-if="loadError" role="alert" class="read-state read-state--error">
        <p>{{ loadError }}</p>
        <p v-if="hasSnapshot" data-testid="monitoring-stale">显示上次成功结果，数据可能已过时。成功读取时间：{{ formatDateTime(lastSuccessAt) }}</p>
        <el-button data-testid="retry-monitoring" :loading="loading" @click="loadSnapshot()">重试</el-button>
      </div>
      <p v-if="loading" role="status" class="read-state">{{ hasSnapshot ? '正在刷新，当前显示上次成功结果' : '正在加载监测数据…' }}</p>
      <template v-if="hasSnapshot">
      <section class="status-hero" :class="`status-hero--${statusTone}`" v-loading="loading && !snapshot.generatedAt">
        <div class="status-orb"><el-icon :size="34"><component :is="statusIcon" /></el-icon></div>
        <div class="status-copy">
          <span class="status-label">{{ot('legacyOverview')}}</span>
          <h2>{{snapshot.overallStatus==='STABLE'?ot('legacyStable'):snapshot.statusLabel}}</h2>
          <p>{{ primarySuggestion }}</p>
        </div>
        <div class="status-side">
          <div class="status-meta">
            <span><i></i>{{ loadError ? '上次成功结果' : (autoRefresh ? '正在监测新记录' : '手动检查') }}</span>
            <small>{{ot('generated')}} {{ formatDateTime(snapshot.generatedAt) }}</small>

          </div>
          <div class="status-actions">
            <el-button type="primary" @click="$router.push('/bp-self-monitor')">录入健康数据</el-button>
            <el-button type="primary" plain @click="$router.push('/family-health')">查看今日计划</el-button>
          </div>
        </div>
      </section>

      <nav class="illustrated-shortcuts" aria-label="常用健康功能">
        <router-link v-for="item in illustratedShortcuts" :key="item.path" :to="item.path" class="illustrated-shortcut">
          <img :src="item.image" width="116" height="77" alt="" decoding="async" />
          <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
        </router-link>
      </nav>

      <p v-if="todayTasks.some(task => task.nextAction)" role="status" class="workflow-metric-limit">协作用药随访不计入旧完成率；记录事实、结束随访与临床安全是不同的结果。</p>
      <section class="monitor-metrics">
        <article class="monitor-metric">
          <span class="monitor-metric__icon monitor-metric__icon--alert"><el-icon><Bell /></el-icon></span>
          <div><strong>{{ metrics.activeAlertCount ?? 0 }}</strong><span>活动告警</span><small>{{ metrics.criticalAlertCount || 0 }} 项紧急</small></div>
        </article>
        <article class="monitor-metric">
          <span class="monitor-metric__icon"><el-icon><CircleCheck /></el-icon></span>
          <div><strong>{{ metrics.completedTaskCount ?? 0 }}/{{ metrics.todayTaskCount ?? 0 }}</strong><span>今日任务</span><small>完成率 {{ metrics.adherenceRate ?? 0 }}%</small></div>
        </article>
        <article class="monitor-metric">
          <span class="monitor-metric__icon"><el-icon><DataLine /></el-icon></span>
          <div><strong>{{ metrics.dataCompleteness ?? 0 }}%</strong><span>{{ot('categories')}}</span><small>{{ot('legacyRule')}}</small></div>
        </article>
        <article class="monitor-metric">
          <span class="monitor-metric__icon"><el-icon><Clock /></el-icon></span>
          <div><strong class="monitor-metric__time">{{formatDateTime(snapshot.generatedAt)}}</strong><span>{{ot('generated')}}</span></div>
        </article>
      </section>

      <nav class="monitoring-section-nav" aria-label="监测页面分区">
        <a href="#monitoring-alerts" @click.prevent="focusSection('monitoring-alerts')">告警</a>
        <a href="#monitoring-trends" @click.prevent="focusSection('monitoring-trends')">趋势</a>
        <a href="#monitoring-tasks" @click.prevent="focusSection('monitoring-tasks')">任务</a>
      </nav>

      <section class="signal-grid">
        <article v-for="signal in displaySignals" :key="signal.key" class="signal-card" :class="`signal-card--${signalTone(signal.original && signal.status==='NORMAL'?'UNASSESSED':signal.status)}`">
          <header><span>{{ signal.label }}</span><em><i></i>{{ signal.statusLabel }}</em></header>
          <p v-if="signal.original">{{ot('windowLatest')}} {{measurementContext.from}} – {{measurementContext.to}}</p><div class="signal-value">{{ signal.value }} <small>{{ signal.unit }}</small></div>
          <p v-if="signal.original && signal.key==='glucose'">{{signal.period || ot('periodUnknown')}}</p><p v-if="signal.original" class="mr-quality" data-testid="measurement-quality">{{signal.issues.join(' · ')}} {{ot('unassessed')}}</p><footer><span>{{signal.freshnessText}}</span><span>{{signal.original?signal.rawTime:shortDateTime(signal.updatedAt)}}</span></footer>
        </article>
      </section>

      <section class="monitoring-main-grid">
        <div class="monitoring-primary-column">
          <article class="monitor-panel trend-panel measurement-read-model">
            <h2 id="monitoring-trends" tabindex="-1" class="monitor-section-heading">{{ot('originalTitle')}}</h2><p>{{ot('rawChartHelp')}}</p>
            <button type="button" data-action="toggle-monitoring-original" :aria-expanded="rawOpen" @click="rawOpen=!rawOpen">{{ot(rawOpen?'close':'open')}}</button>
            <template v-if="rawOpen"><OriginalMeasurementPageStatus :state="raw.state" @refresh="raw.refresh()" @retry="raw.refresh({keepPosition:true})" @next="raw.next()" @previous="raw.previous()" />
            <div v-if="raw.state.phase==='ready' && raw.state.items.length" class="mr-table-wrap" tabindex="0" role="region" :aria-label="ot('originalTitle')"><table data-testid="monitoring-trend-data"><caption>{{ot('pageScope')}}</caption><thead><tr><th>{{ot('originalTime')}}</th><th>{{ot('BP')}}</th><th>{{ot('GLUCOSE')}}</th><th>{{ot('source')}}</th></tr></thead><tbody><tr v-for="row in raw.state.items" :key="row.id"><th>{{selfTime(row)}}</th><td>{{selfBp(row)}}</td><td>{{selfGlucose(row)}}</td><td>#{{row.id}} · {{row.measurePeriod || ot('periodUnknown')}}<p>{{originalIssues(row).join(' · ')}}</p></td></tr></tbody></table></div></template>
          </article>

          <article class="monitor-panel task-panel">
            <header class="panel-head panel-head--compact"><div><span class="panel-kicker">照护计划</span><h2 id="monitoring-tasks" tabindex="-1" class="monitor-section-heading">今日任务</h2></div><span class="panel-count">{{ todayTasks.length }} 项</span></header>
            <div v-if="todayTasks.length" class="task-list">
              <article v-for="task in todayTasks" :key="`${task.taskType}-${task.id}`" class="task-item" :class="{ 'task-item--done': isTaskDone(task), 'task-item--workflow': !!task.nextAction }">
                <span class="task-time">{{ timeOnly(task.scheduledAt) }}</span>
                <span class="task-icon"><el-icon><component :is="task.taskType === 'MEDICATION' ? 'FirstAidKit' : 'Calendar'" /></el-icon></span>
                <div :class="{ 'workflow-task-copy': !!task.nextAction }"><strong>{{ task.title }}</strong><p v-if="!task.nextAction">{{ task.dosage || task.description || taskTypeText(task.taskType) }}</p><span v-if="task.nextAction" role="status" class="workflow-task-truth">{{ medicationText(task.followUpState) }} · {{ medicationText(task.followUpKind) }} · {{ medicationText(task.status) }}</span></div>
                <el-tag v-if="!task.nextAction" :type="taskTagType(task.status)" effect="light">{{ taskStatusText(task.status) }}</el-tag>
                <router-link v-if="task.nextAction" class="workflow-task-link" :to="{ path: '/medication', query: { patientId, ...(task.executionId ? { executionId: task.executionId } : {}) } }">{{ medicationText('intakeTitle') }}</router-link>
                <el-button v-if="!task.nextAction && task.taskType === 'MEDICATION' && ['PENDING','MISSED','SNOOZED'].includes(task.status)" size="small" type="primary" @click="completeMedication(task)">标记已服</el-button>
                <el-button v-if="task.taskType === 'DIALYSIS'" size="small" @click="$router.push('/family-health')">管理排班</el-button>
              </article>
            </div>
            <div v-else class="simple-empty">今天没有需要关注的任务</div>
          </article>

          <article class="monitor-panel event-panel">
            <header class="panel-head panel-head--compact"><div><span class="panel-kicker">健康轨迹</span><h2>最近事件</h2></div><el-button text type="primary" @click="$router.push('/family-health')">记录事件</el-button></header>
            <div v-if="recentEvents.length" class="event-list">
              <article v-for="event in recentEvents.slice(0, 7)" :key="`${event.sourceType}-${event.id}`" class="event-item">
                <span class="event-dot"></span>
                <div><strong>{{ event.title || eventTypeText(event.type) }}</strong><p>{{ event.summary || '无补充说明' }}</p><small>{{ event.date }} {{ event.time || '' }} · {{ eventTypeText(event.type) }}</small></div>
              </article>
            </div>
            <div v-else class="simple-empty">暂无健康事件</div>
          </article>
        </div>

        <article class="monitor-panel alert-panel">
          <header class="panel-head panel-head--compact">
            <div><span class="panel-kicker">风险队列</span><h2 id="monitoring-alerts" tabindex="-1" class="monitor-section-heading">活动告警</h2></div>
            <el-button text type="primary" :loading="checking" @click="runCheck">重新检查</el-button>
          </header>
          <div class="alert-list-summary"><span data-testid="alert-count">显示 {{ shownAlerts.length }} / 已加载 {{ activeAlerts.length }} 项</span><router-link v-if="canViewAllAlerts" :to="allAlertsRoute" data-testid="view-all-alerts">查看全部告警</router-link></div>
          <p class="alert-source-note">仅在返回时显示当前医疗来源信息；已记录告警计数保持原义</p>
          <div v-if="activeAlerts.length" class="alert-list">
            <article v-for="alert in shownAlerts" :key="alert.id" class="alert-row" :class="`alert-row--${(alert.level || 'INFO').toLowerCase()}`">
              <span class="alert-level">{{ alertLevel(alert.level) }}</span>
              <div class="alert-row__body"><strong>{{ alert.title || '健康指标异常' }}</strong><p>{{ alert.value || '请查看详细记录' }}</p><small>{{ formatDateTime(alert.triggeredAt) }}</small><section class="alert-source-facts"><b>已记录告警</b><p v-for="(line, lineIndex) in alertSourceLines(alert)" :key="lineIndex">{{ line }}</p></section></div>
              <div class="alert-actions">
                <el-button v-if="alert.status === 'PENDING'" size="small" @click="acknowledgeAlert(alert)">确认</el-button>
                <el-button size="small" type="primary" plain @click="resolveAlert(alert)">处理</el-button>
              </div>
            </article>
          </div>
          <div v-else class="safe-empty"><el-icon :size="30"><CircleCheckFilled /></el-icon><strong>当前没有活动告警</strong><span>请持续记录，异常变化会显示在这里。</span></div>
        </article>
      </section>

      <section class="care-guidance">
        <header><el-icon><Opportunity /></el-icon><div><strong>{{ot('legacySuggestions')}}</strong><span>根据监测数据自动汇总，仅供健康管理参考。</span></div></header>
        <ol><li v-for="item in snapshot.careSuggestions || []" :key="item">{{ item }}</li></ol>
      </section>
      </template>
    </template>
  </main>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TrustedMeasurementTrend from '@/components/measurement-read-model/TrustedMeasurementTrend.vue'
import MeasurementCorrectionEntry from '@/components/measurement-read-model/MeasurementCorrectionEntry.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Bell, Calendar, CircleCheck, CircleCheckFilled, Clock, DataLine,
  FirstAidKit, Opportunity, Refresh, WarningFilled
} from '@element-plus/icons-vue'
import {monitoringSnapshotBounded} from '@/api/originalMeasurements'
import {useOriginalMeasurementPage} from '@/composables/useOriginalMeasurementPage'
import OriginalMeasurementPageStatus from '@/components/measurement-read-model/OriginalMeasurementPageStatus.vue'
import {originalText as ot} from '@/utils/originalMeasurementCopy'
import {selfTime,selfBp,selfGlucose,originalIssues,boundedMeasurementContext} from '@/utils/originalMeasurementPresentation'
import { localizeHealthTimelineEntry } from '@/utils/timelineText'
import { localizeServerText } from '@/utils/serverText'
import { acknowledge, checkThresholds, resolve } from '@/api/alert'
import { actionIntake } from '@/api/familyHealth'
import { medicationText } from '@/utils/medicationExecutionCopy'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import {readPermissionCache,captureAuthSession,isAuthSessionCurrent} from '@/utils/authSession'
import { canAccessWorkspace, resolveWorkspaceEntry } from '@/utils/workspaceAccess'
import careMoments from '@/assets/illustrations/care-moments.webp'
import careMomentsSmall from '@/assets/illustrations/care-moments-small.webp'
import healthJournalSmall from '@/assets/illustrations/health-journal-small.webp'
import medicationCareSmall from '@/assets/illustrations/medication-care-small.webp'
import dialysisCareSmall from '@/assets/illustrations/dialysis-care-small.webp'
import recordsCareSmall from '@/assets/illustrations/records-care-small.webp'


const route = useRoute()
const userMenus = inject('userMenus', ref([]))
const { currentPatientId, currentPatientName } = useCurrentPatient()
const patientId = computed(() => currentPatientId.value)
const snapshot = ref({ signals: [], activeAlerts: [], todayTasks: [], recentEvents: [], careSuggestions: [], metrics: {} })
const loading = ref(false)
const checking = ref(false)
const days = ref(7)
const loadError = ref('')
const accessDenied = ref(false)
const lastSuccessAt = ref('')
const hasSnapshot = computed(() => !!lastSuccessAt.value)
const rawOpen=ref(false);
const measurementContext=computed(()=>snapshot.value.measurementContext||null);
const raw=useOriginalMeasurementPage(patientId,computed(()=>({sourceType:'BP_SELF_MONITOR',from:measurementContext.value?.from,to:measurementContext.value?.to})),computed(()=>rawOpen.value&&!!measurementContext.value));
// 最新卡只取服务器窗口内各指标原记录，旧信号仅保留为带来源的历史规则提示。
const displaySignals=computed(()=>{
  if(!measurementContext.value)return [];
  const signals=snapshot.value.signals||[];
  const originals=['bloodPressure','glucose'].map(key=>{
    const legacy=signals.find(signal=>signal.key===key),metric=key==='bloodPressure'?'BP':'GLUCOSE';
    const row=measurementContext.value[metric==='BP'?'latestBloodPressure':'latestGlucose'];
    return {...legacy,key,label:legacy?.label||ot(metric),status:legacy?.status||'UNASSESSED',original:true,
      value:row?(metric==='BP'?selfBp(row):selfGlucose(row)):ot('noReading'),unit:'',updatedAt:null,
      rawTime:row?selfTime(row):ot('noTime'),period:row?.measurePeriod,freshnessText:ot('localTime'),issues:originalIssues(row,metric),
      statusLabel:legacy?ot('legacyRule')+' · '+(legacy.status==='NORMAL'?ot('legacyNormal'):legacy.statusLabel):ot('unassessed')};
  });
  return [...originals,...signals.filter(signal=>!['bloodPressure','glucose'].includes(signal.key))];
});
const measurementNeedsReview=computed(()=>displaySignals.value.some(signal=>signal.original&&signal.issues.length));

function focusSection(id) {
  const target = document.getElementById(id)
  if (!target) return
  const toolbar = document.querySelector?.('.workspace-topbar')
  const clearance = () => {
    const bounds = toolbar?.getBoundingClientRect()
    return bounds ? Math.max(0, bounds.bottom) + 16 : 88
  }
  target.style?.setProperty('scroll-margin-top', `${clearance()}px`)
  target.scrollIntoView({ behavior: 'auto', block: 'start' })
  // Measure again after scrolling: the real toolbar may have reached its sticky position.
  const requiredTop = clearance()
  if (target.getBoundingClientRect?.().top < requiredTop) {
    target.style?.setProperty('scroll-margin-top', `${requiredTop}px`)
    target.scrollIntoView({ behavior: 'auto', block: 'start' })
  }
  target.focus({ preventScroll: true })
}

const shownAlerts = computed(() => activeAlerts.value.slice(0, 8))
const allAlertsRoute = computed(() => ({ path: '/health-analysis', query: { tab: 'alert', patientId: patientId.value } }))
const canViewAllAlerts = computed(() => {
  const { menuPaths = [], roleCodes = [] } = readPermissionCache() || {}
  return canAccessWorkspace('/health-analysis?tab=alert', menuPaths, roleCodes)
})
const autoRefresh = ref(true)
let timer = null
let snapshotRequestEpoch = 0
let snapshotAccessEpoch = 0
let snapshotPending = null
let snapshotController=null
let snapshotDisposed = false

function captureReaderSession() {
  if (typeof localStorage === 'undefined') return null;
  return JSON.stringify({...captureAuthSession(),actor:localStorage.getItem('userId'),permissions:readPermissionCache()});
}
let readerSession = captureReaderSession();
let readerSessionEpoch = 0;
function ownsReaderSession(session = readerSession, epoch = readerSessionEpoch) {
  return epoch === readerSessionEpoch && session === captureReaderSession();
}
function clearProtectedSnapshot(message) {
  snapshotAccessEpoch++
  snapshotRequestEpoch++
  snapshotPending = null
  snapshotController?.abort();snapshotController=null;raw.clear()
  snapshot.value = { signals: [], activeAlerts: [], todayTasks: [], recentEvents: [], careSuggestions: [], metrics: {} }
  lastSuccessAt.value = ''
  loading.value = false
  checking.value = false
  accessDenied.value = true
  loadError.value = message
}
function readerSessionChanged(event) {
  if (snapshotDisposed || (event?.key != null && !['token','userId','username','realName','permissionSession','userMenus','userRoleCodes'].includes(event.key))) return
  readerSessionEpoch++
  rawOpen.value=false;raw.clear()
  clearProtectedSnapshot('登录会话已改变，请重新登录并选择患者。')
}
function ensureReaderSession() {
  if (ownsReaderSession()) return true
  readerSessionChanged()
  return false
}

function validSourceInstant(value){
  if(typeof value!=='string')return false
  const m=value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/)
  if(!m)return false
  const [year,month,day,hour,minute,second]=m.slice(1).map(Number),leap=year%4===0 && (year%100!==0 || year%400===0),days=[31,leap?29:28,31,30,31,30,31,31,30,31,30,31]
  return month>=1 && month<=12 && day>=1 && day<=days[month-1] && hour<24 && minute<60 && second<60
}
function alertSourceLines(item){
  const keys=['sourceStatus','sourceBindingStatus','currentSourceStatus','sourceQualificationSemantics','sourceQualificationAt']
  if(!item || typeof item!=='object' || Array.isArray(item))return ['当前来源资格不可用']
  if(!keys.some(key=>Object.hasOwn(item,key)))return []
  if(!keys.every(key=>Object.hasOwn(item,key)))return ['当前来源资格不可用']
  if(keys.every(key=>item[key]===null))return []
  const {sourceStatus:status,sourceBindingStatus:binding,currentSourceStatus:current,sourceQualificationSemantics:semantics,sourceQualificationAt:at}=item
  if(status==='SOURCE_UNAVAILABLE' && binding===null && current==='SOURCE_UNAVAILABLE' && semantics===null && at===null)return ['当前来源资格不可用']
  const currentCopy={'CURRENT': '当前来源资格：有效', 'UNTRACKED': '当前来源：旧版处理', 'SOURCE_REVIEW_REQUIRED': '当前来源待核对', 'SOURCE_CHANGED': '当前来源已变更', 'SOURCE_INVALIDATED': '当前来源已失效', 'SOURCE_UNAVAILABLE': '当前来源资格不可用'}, bindingCopy={'CURRENT_CONTENT': '历史链接匹配当前内容', 'UNBOUND': '历史链接未绑定版本', 'CONTENT_CHANGED': '历史链接指向较早的来源版本', 'GENERATION_CHANGED': '历史链接指向较早的来源版本', 'MISSING_ANCHOR': '无法核实历史来源链接', 'SOURCE_MISSING': '无法核实历史来源链接', 'STORE_CONFLICT': '无法核实历史来源链接'}
  if(![status,binding,current].every(value=>typeof value==='string') || !Object.hasOwn(currentCopy,current) || !Object.hasOwn(bindingCopy,binding) || !['CURRENT','UNTRACKED','SOURCE_REVIEW_REQUIRED','SOURCE_CHANGED','SOURCE_INVALIDATED','SOURCE_UNAVAILABLE','UNKNOWN_NEEDS_RECHECK'].includes(status) || semantics!=='CURRENT_CHECK' || !validSourceInstant(at))return ['当前来源资格不可用']
  if(current==='SOURCE_UNAVAILABLE' && !['MISSING_ANCHOR','SOURCE_MISSING'].includes(binding))return ['当前来源资格不可用']
  const coherent=binding==='MISSING_ANCHOR' || binding==='SOURCE_MISSING' ? status==='SOURCE_UNAVAILABLE' && current==='SOURCE_UNAVAILABLE' : binding==='STORE_CONFLICT' ? status==='SOURCE_UNAVAILABLE' : binding==='UNBOUND' ? status===(current==='CURRENT'?'UNKNOWN_NEEDS_RECHECK':current) : binding==='GENERATION_CHANGED' ? status==='SOURCE_CHANGED' : binding==='CONTENT_CHANGED' ? status==='SOURCE_CHANGED' || status===current && !['CURRENT','UNTRACKED'].includes(current) : status===(['CURRENT','UNTRACKED'].includes(current)?'CURRENT':current)
  if(!coherent)return ['当前来源资格不可用']
  return [bindingCopy[binding],currentCopy[current],...(status==='UNKNOWN_NEEDS_RECHECK'?['历史链接需要重新核对']:[]),'当前来源核对时间：'+at]
}
// Completion ownership is independent of whether the list has loaded successfully.
function captureSnapshotOwner() {
  return { patientId: patientId.value, session: readerSession, sessionEpoch: readerSessionEpoch,
    accessEpoch: snapshotAccessEpoch, route: route.fullPath, available: !accessDenied.value }
}
function ownsSnapshotContext(owner) {
  return !snapshotDisposed && owner.patientId === patientId.value
    && owner.accessEpoch === snapshotAccessEpoch && owner.route === route.fullPath
    && ownsReaderSession(owner.session, owner.sessionEpoch)
}
function ownsSnapshotOwner(owner) {
  return owner.available && !accessDenied.value && ownsSnapshotContext(owner)
}
function bindSnapshotChanged() {
  const owner = captureSnapshotOwner()
  return () => { if (ownsSnapshotContext(owner)) return loadSnapshot() }
}

const metrics = computed(() => snapshot.value.metrics || {})
const activeAlerts = computed(() => snapshot.value.activeAlerts || [])
const todayTasks = computed(() => (snapshot.value.todayTasks || []).map(task => task.taskType === 'DIALYSIS' && ['Dialysisschedule','Dialysis schedule','Dialysis Schedule'].includes(task.title) ? {...task, title:'透析排班'} : task))
const recentEvents = computed(() => (snapshot.value.recentEvents || []).map(event => {
  const sourceType = event.sourceType || (['MEASUREMENT','DIALYSIS','INTAKE','MEDICATION_LOG'].includes(event.type) ? event.type : '')
  const localized = localizeHealthTimelineEntry({...event, sourceType})
  return {...event, title: localized.title, summary: localized.summary}
}))
const primarySuggestion = computed(() => measurementNeedsReview.value ? ot('missingContext') : ot('unassessed'))
const statusTone = computed(() => ({ CRITICAL: 'critical', WARNING: 'warning', STABLE: 'empty', NO_DATA: 'empty' }[snapshot.value.overallStatus] || 'empty'))
const statusIcon = computed(() => ['CRITICAL','WARNING'].includes(snapshot.value.overallStatus)?WarningFilled:Clock)
const illustratedShortcuts = computed(() => {
  const { menuPaths = [], roleCodes = [] } = readPermissionCache() || {}
  return [
    { path: '/bp-self-monitor', label: '血压与血糖', description: '日常测量与记录', image: healthJournalSmall },
    { path: '/medication', label: '用药管理', description: '药品、提醒与用药记录', image: medicationCareSmall },
    { path: '/dialysis', label: '透析记录', description: '模板录入与趋势分析', image: dialysisCareSmall },
    { path: '/medical-record', label: '医疗记录', description: '报告与指标变化', image: recordsCareSmall }
  ].filter(item => canAccessWorkspace(item.path, menuPaths, roleCodes))
    .map(item => ({ ...item, path: resolveWorkspaceEntry(item.path, menuPaths, roleCodes) }))
})

async function loadSnapshot(silent = false) {
  const requestedPatientId = patientId.value
  const requestedDays = days.value
  if (snapshotDisposed || !requestedPatientId || !ensureReaderSession()) return
  const session = readerSession, sessionEpoch = readerSessionEpoch,auth={...captureAuthSession(),actorId:localStorage.getItem('userId')},permissions=JSON.stringify(readPermissionCache())
  const requestEpoch = ++snapshotRequestEpoch
  const pending = { patientId: requestedPatientId, days: requestedDays }
  snapshotPending = pending
  snapshotController?.abort();const controller=new AbortController();snapshotController=controller;
  snapshot.value={signals:[],activeAlerts:[],todayTasks:[],recentEvents:[],careSuggestions:[],metrics:{}};lastSuccessAt.value='';
  loading.value = true
  try {
    const res = await monitoringSnapshotBounded({patientId:requestedPatientId,days:requestedDays},{expectedAuth:auth,signal:controller.signal})
    if (snapshotDisposed || requestEpoch !== snapshotRequestEpoch || requestedPatientId !== patientId.value || !ownsReaderSession(session, sessionEpoch) || requestedDays!==days.value || !isAuthSessionCurrent(auth) || permissions!==JSON.stringify(readPermissionCache())) return
    if (res.code === 200) {
      if(!boundedMeasurementContext(res.data,requestedPatientId,requestedDays))throw new Error(ot('unavailable'));
      snapshot.value = localizeSnapshot(res.data)
      loadError.value = ''
      lastSuccessAt.value = new Date().toISOString()
      accessDenied.value = false
    } else {
      throw Object.assign(new Error(res.msg || '无法加载监测数据。'), { code: res.code })
    }
  } catch (error) {
    if (snapshotDisposed || requestEpoch !== snapshotRequestEpoch || requestedPatientId !== patientId.value || !ownsReaderSession(session, sessionEpoch) || requestedDays!==days.value || !isAuthSessionCurrent(auth) || permissions!==JSON.stringify(readPermissionCache())) return
    if ([401, 403].includes(Number(error.response?.status || error.status || error.code))) {
      clearProtectedSnapshot('监测访问不可用或已被撤销，请重新检查权限。')
      return
    }
    loadError.value = error.message || '无法加载监测数据。'
  } finally {
    if (snapshotPending === pending) snapshotPending = null
    if (!snapshotDisposed && requestEpoch === snapshotRequestEpoch) { loading.value = false;if(!ownsReaderSession(session,sessionEpoch)||!isAuthSessionCurrent(auth)||permissions!==JSON.stringify(readPermissionCache()))clearProtectedSnapshot(ot('denied')) }
  }
}

// Only automatic wakes share a pending read. Explicit and post-write refreshes
// must still dispatch so new patient/range choices and saved changes take priority.
function refreshSnapshotInBackground() {
  if (snapshotDisposed || accessDenied.value || document.hidden || !autoRefresh.value) return
  if (snapshotPending?.patientId === patientId.value && snapshotPending?.days === days.value) return
  return loadSnapshot(true)
}

function resetTimer() {
  if (timer) window.clearInterval(timer)
  timer = null
  if (autoRefresh.value && !snapshotDisposed) timer = window.setInterval(refreshSnapshotInBackground, 30000)
}

async function runCheck() {
  if (snapshotDisposed || !ensureReaderSession() || accessDenied.value || !patientId.value) return
  const owner = captureSnapshotOwner()
  checking.value = true
  try {
    await checkThresholds(patientId.value)
    if (!ownsSnapshotOwner(owner)) return
    await loadSnapshot(true)
    if (!ownsSnapshotOwner(owner)) return
    ElMessage.success('告警规则检查完成')
  } catch (error) {
    if (!ownsSnapshotOwner(owner)) return
    throw error
  } finally {
    if (ownsSnapshotOwner(owner)) checking.value = false
  }
}

async function acknowledgeAlert(alert) {
  if (snapshotDisposed || !ensureReaderSession() || accessDenied.value || !patientId.value) return
  const owner = captureSnapshotOwner()
  try {
    await acknowledge(alert.id)
  } catch (error) {
    if (!ownsSnapshotOwner(owner)) return
    throw error
  }
  if (!ownsSnapshotOwner(owner)) return
  ElMessage.success('告警已确认')
  await loadSnapshot(true)
}

async function resolveAlert(alert) {
  if (snapshotDisposed || !ensureReaderSession() || accessDenied.value || !patientId.value) return
  const context = { patientId: patientId.value, session: readerSession, sessionEpoch: readerSessionEpoch, accessEpoch: snapshotAccessEpoch, alertId: alert.id }
  const isCurrentResolution = () => !snapshotDisposed && !accessDenied.value && context.patientId === patientId.value
    && context.accessEpoch === snapshotAccessEpoch && ownsReaderSession(context.session, context.sessionEpoch)
    && activeAlerts.value.some(item => item.id === context.alertId && item.status !== 'RESOLVED')
  if (!isCurrentResolution()) return
  const result = await ElMessageBox.prompt('请描述已采取的措施、复测结果或后续计划。', '解决告警', {
    confirmButtonText: '标记已解决', cancelButtonText: '取消', inputType: 'textarea',
    inputPlaceholder: '例如：休息 10 分钟后复测 138/86，继续观察',
    inputValidator: value => value?.trim() ? true : '请输入处置说明'
  }).catch(() => null)
  if (!result || !isCurrentResolution()) return
  try {
    await resolve(context.alertId, result.value.trim())
  } catch (error) {
    if (!isCurrentResolution()) return
    throw error
  }
  if (!isCurrentResolution()) return
  ElMessage.success('告警已解决')
  await loadSnapshot(true)
}

async function completeMedication(task) {
  if (snapshotDisposed || !ensureReaderSession() || accessDenied.value || !patientId.value) return
  const owner = captureSnapshotOwner()
  try {
    await actionIntake(task.id, 'TAKEN', '')
  } catch (error) {
    if (!ownsSnapshotOwner(owner)) return
    throw error
  }
  if (!ownsSnapshotOwner(owner)) return
  ElMessage.success('服药记录已保存')
  await loadSnapshot(true)
}

function signalTone(status) { return ({ CRITICAL: 'critical', WARNING: 'warning', DELAYED: 'delayed', NORMAL: 'normal', PENDING: 'pending' }[status] || 'empty') }
function alertLevel(level) { return ({ CRITICAL: '紧急', WARNING: '警告', INFO: '提示' }[level] || '提示') }
function isTaskDone(task) { return !task.nextAction && (task.status === 'TAKEN' || task.status === 'COMPLETED') }
function taskStatusText(status) { return ({ PENDING: '待处理', TAKEN: '已服用', SNOOZED: '已延后', SKIPPED: '已跳过', MISSED: '已漏服', PLANNED: '已计划', COMPLETED: '已完成', CANCELLED: '已取消' }[status] || status || '待处理') }
function taskTagType(status) { return ({ TAKEN: 'success', COMPLETED: 'success', MISSED: 'danger', SKIPPED: 'info', SNOOZED: 'warning', CANCELLED: 'info' }[status] || 'warning') }
function taskTypeText(type) { if (type === 'MEDICATION_WORKFLOW') return medicationText('intakeTitle'); return type === 'DIALYSIS' ? '透析排班' : '用药计划' }
function eventTypeText(type) { return ({ SYMPTOM: '症状记录', VISIT: '就诊记录', NOTE: '健康备注', MEDICATION: '用药', DIALYSIS: '透析', MEASUREMENT: '测量', INTAKE: '服药打卡', MEDICATION_LOG: '用药记录' }[type] || '健康事件') }
function timeOnly(value) { return value ? String(value).slice(11, 16) : '—' }
function shortDateTime(value) { return value ? String(value).slice(5, 16).replace('T', ' ') : '—' }
function formatDateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '无' }
function formatRelative(value) {
  if (!value) return '暂无数据'
  const time = new Date(String(value).replace(' ', 'T')).getTime()
  if (Number.isNaN(time)) return formatDateTime(value)
  const minutes = Math.max(0, Math.floor((Date.now() - time) / 60000))
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (minutes < 1440) return `${Math.floor(minutes / 60)} 小时前`
  return `${Math.floor(minutes / 1440)} 天前`
}

function localizeSnapshot(data) {
  const hasWorkflowTasks = (data.todayTasks || []).some(item => item.nextAction)
  const statusLabels = { CRITICAL: '需要立即关注', WARNING: '存在待处理事项', NO_DATA: '等待健康数据', STABLE: '当前状态稳定' }
  const signalLabels = { bloodPressure: '血压监测', glucose: '血糖监测', dialysis: '透析监测', medication: '今日用药' }
  const signalStatus = { CRITICAL: '严重异常', WARNING: '需要关注', DELAYED: '数据延迟', PENDING: '进行中', NO_DATA: '暂无数据', NORMAL: '正常' }
  const signals = (data.signals || []).map(signal => ({
    ...signal,
    label: signalLabels[signal.key] || signal.label,
    statusLabel: signalStatus[signal.status] || signal.statusLabel,
    freshnessText: hasWorkflowTasks && signal.key === 'medication' ? '旧汇总仅统计独立旧任务；协作事实和随访请分别核对。' : signal.updatedAt ? formatRelative(signal.updatedAt) : (signal.key === 'medication' ? '今日暂无用药任务' : '等待首次记录'),
    unit: signal.key === 'dialysis' && signal.unit === 'kg weight gain' ? 'kg 增重' : localizeServerText(signal.unit)
  }))
  const suggestions = []
  if ((data.todayTasks || []).some(item => item.nextAction)) suggestions.push('协作用药随访不计入旧完成率；记录事实、结束随访与临床安全是不同的结果。')
  if (data.overallStatus === 'CRITICAL') suggestions.push('检测到严重异常，请立即复测；如伴随明显不适，请按医嘱联系医生或及时就医。')
  if (!signals.some(item => item.key === 'bloodPressure' && item.status !== 'NO_DATA')) suggestions.push('近期没有血压记录，建议在静息状态下完成一次测量。')
  if (!signals.some(item => item.key === 'glucose' && item.status !== 'NO_DATA')) suggestions.push('近期没有血糖记录；如照护计划有要求，请及时补充。')
  const pending = (data.todayTasks || []).filter(item => !item.nextAction && ['PENDING', 'MISSED', 'SNOOZED'].includes(item.status)).length
  if (pending) suggestions.push(`今日仍有 ${pending} 项用药任务待处理，请核对实际服药情况。`)
  if ((data.metrics?.dataCompleteness ?? 0) < 50) suggestions.push('当前监测数据覆盖不足，持续记录后趋势判断会更可靠。')
  if (!suggestions.length) suggestions.push('当前没有紧急事项，请继续按计划记录、用药和复诊。')
  return { ...data, statusLabel: statusLabels[data.overallStatus] || data.statusLabel, signals, careSuggestions: suggestions }
}


watch(userMenus, readerSessionChanged, { deep: true, flush: 'sync' })
watch(() => route.fullPath, () => clearProtectedSnapshot(''), { flush: 'sync' })
watch(days,()=>{clearProtectedSnapshot('');accessDenied.value=false},{flush:'sync'})
watch(patientId, () => {
  rawOpen.value=false;raw.clear();snapshotController?.abort();
  snapshotAccessEpoch++
  readerSession = captureReaderSession()
  readerSessionEpoch++
  snapshotRequestEpoch++
  snapshotPending = null
  loading.value = false
  checking.value = false
  snapshot.value = { signals: [], activeAlerts: [], todayTasks: [], recentEvents: [], careSuggestions: [], metrics: {} }
  loadError.value = ''
  lastSuccessAt.value = ''
  accessDenied.value = false
  loadSnapshot()
  resetTimer()
}, { immediate: true, flush: 'sync' })
onMounted(() => { resetTimer(); document.addEventListener('visibilitychange', handleVisibility); window.addEventListener('storage', readerSessionChanged); window.addEventListener('auth-session-cleared', readerSessionChanged);window.addEventListener('auth-session-changed',readerSessionChanged);window.addEventListener('permissions-changed',readerSessionChanged) })
onUnmounted(() => {
  snapshotDisposed = true
  snapshotRequestEpoch++
  snapshotPending = null
  if (timer) window.clearInterval(timer)
  document.removeEventListener('visibilitychange', handleVisibility)
  window.removeEventListener('storage', readerSessionChanged)
  window.removeEventListener('auth-session-cleared', readerSessionChanged);window.removeEventListener('auth-session-changed',readerSessionChanged);window.removeEventListener('permissions-changed',readerSessionChanged);snapshotController?.abort()
})
function handleVisibility() { return refreshSnapshotInBackground() }
</script>

<style scoped>
.alert-source-facts, .alert-source-note { color: var(--ink-700); font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.alert-source-facts { margin-top: 8px; }
.alert-source-facts p { margin: 3px 0; }
.workflow-task-link{display:inline-flex;align-items:center;min-height:44px;min-width:44px;padding:.5rem;overflow-wrap:anywhere}.workflow-task-link:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.workflow-task-truth,.workflow-metric-limit{overflow-wrap:anywhere}

.read-state { padding: 14px 18px; margin-bottom: 16px; border: 1px solid var(--line-strong); border-radius: 12px; background: var(--surface-subtle); color: var(--ink-800); overflow-wrap: anywhere; }
.read-state--error { border-color: var(--danger); }
.alert-list-summary, .trend-view-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 12px 0; }
.alert-list-summary { justify-content: space-between; }
.alert-list-summary a, .trend-view-controls button { min-height: 44px; display: inline-flex; align-items: center; padding: 8px 12px; color: var(--care-800); }
.trend-view-controls button { font: inherit; border: 1px solid var(--line-strong); border-radius: 8px; background: var(--paper); cursor: pointer; }
.trend-view-controls button[aria-pressed="true"] { background: var(--care-50); border-color: var(--care-700); }
.trend-view-controls button:focus-visible, .trend-data-wrap:focus-visible, .alert-list-summary a:focus-visible { outline: 3px solid var(--care-600); outline-offset: 3px; }
.trend-context { color: var(--ink-700); }
.trend-data-wrap { overflow-x: auto; }
.trend-data-wrap table { border-collapse: collapse; width: 100%; color: var(--ink-800); }
.trend-data-wrap th, .trend-data-wrap td { padding: 10px; border-bottom: 1px solid var(--line); text-align: start; }
.trend-data-wrap caption { text-align: start; padding: 8px 0; }

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
  background: var(--care-600);
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
  box-sizing: border-box;
  padding: 10px;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  flex: 0 0 auto;
  border: 1px solid var(--line, #dde8e5);
  border-radius: 14px;
  background: color-mix(in srgb, var(--paper) 82%, transparent);
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
  border: 1px solid var(--line-strong);
  border-radius: 22px;
  background:
    radial-gradient(circle at 84% 10%, rgb(75 157 144 / 14%), transparent 30%),
    linear-gradient(135deg, var(--care-50) 0%, var(--surface-subtle) 70%);
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
  border: 1px solid var(--care-100);
  border-radius: 22px;
  background: color-mix(in srgb, var(--paper) 70%, transparent);
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
  background: var(--care-600);
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
  border-color: var(--warning-soft);
  background: radial-gradient(circle at 84% 10%, rgb(222 174 72 / 13%), transparent 30%), linear-gradient(135deg, var(--warning-soft), var(--warning-soft) 70%);
}

.status-hero--warning::before {
  background: var(--warning);
}

.status-hero--warning .status-orb {
  color: var(--warning);
  border-color: var(--warning-soft);
  background: var(--warning-soft);
}

.status-hero--critical {
  border-color: var(--danger-soft);
  background: radial-gradient(circle at 84% 10%, rgb(216 92 85 / 12%), transparent 30%), linear-gradient(135deg, var(--danger-soft), var(--danger-soft) 70%);
}

.status-hero--critical::before {
  background: var(--danger);
}

.status-hero--critical .status-orb {
  color: var(--danger);
  border-color: var(--danger-soft);
  background: var(--danger-soft);
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
  background: var(--paper);
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}

.illustrated-shortcut:hover {
  transform: translateY(-2px);
  border-color: var(--line-strong);
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
  background: var(--paper);
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
  color: var(--warning);
  background: var(--warning-soft);
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
  background: var(--paper);
  box-shadow: 0 1px 2px rgb(16 45 42 / 3%);
}

.signal-card::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--info);
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
  background: var(--info);
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
  background: var(--success);
}

.signal-card--warning::before,
.signal-card--delayed::before,
.signal-card--warning em i,
.signal-card--delayed em i {
  background: var(--warning);
}

.signal-card--critical::before,
.signal-card--critical em i {
  background: var(--danger);
}

.signal-card--pending::before,
.signal-card--pending em i {
  background: var(--info);
}

.monitoring-main-grid,
.monitoring-lower-grid {
  display: grid;
  gap: 20px;
  margin-bottom: 20px;
}

.monitoring-main-grid {
  align-items: start;
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
  background: var(--paper);
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

.legend-systolic { background: var(--chart-systolic); }
.legend-diastolic { background: var(--chart-diastolic); }
.legend-glucose { background: var(--chart-glucose); }

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
  background: var(--surface-subtle);
  font-size: 10px;
  font-weight: 750;
}

.alert-row--critical .alert-level {
  color: var(--danger);
  background: var(--danger-soft);
}

.alert-row--warning .alert-level {
  color: var(--warning);
  background: var(--warning-soft);
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
  color: var(--success);
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

/* 跟踪任务的名称与真实随访状态共用纵向文本流，链接另起一行，不能挤窄名称或覆盖状态。 */
.task-item.task-item--workflow { grid-template-columns: 46px 36px minmax(0, 1fr); align-items: start; }
.workflow-task-copy { min-width: 0; overflow-wrap: anywhere; }
.workflow-task-copy strong { display: block; }
.workflow-task-truth { display: block; margin-top: 6px; line-height: 1.5; }
.task-item--workflow .workflow-task-link { grid-column: 3 / -1; justify-self: start; max-width: 100%; white-space: normal; }

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
  border: 2px solid var(--paper);
  border-radius: 50%;
  background: var(--care-600);
  box-shadow: 0 0 0 2px var(--care-100);
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
  border: 1px solid var(--line-strong);
  border-radius: 18px;
  background: linear-gradient(135deg, var(--care-50), var(--paper));
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

  .monitor-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

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
.monitoring-primary-column { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
.monitoring-section-nav { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 16px; }
.monitoring-section-nav a { display: inline-flex; align-items: center; justify-content: center; min-height: 44px; padding: 8px 16px; border: 1px solid var(--line-strong); border-radius: 9px; color: var(--care-800); background: var(--paper); text-decoration: none; font-weight: 650; }
.monitor-section-heading { scroll-margin-top: 88px; }
.monitor-section-heading:focus { outline: 3px solid var(--care-600); outline-offset: 5px; border-radius: 3px; }
.monitoring-section-nav a:focus-visible { outline: 3px solid var(--care-600); outline-offset: 3px; }
.monitoring-reference-legend { margin: 8px 0; color: var(--ink-700); font-size: 12px; line-height: 1.5; }
.monitoring-reference-legend span { display: inline-flex; align-items: baseline; gap: 7px; overflow-wrap: anywhere; }
.monitoring-reference-legend i { flex: 0 0 18px; border-top: 2px dashed var(--warning); }
@media (max-width: 768px) {
  .monitoring-page .monitor-metric { min-height: 96px; padding: 10px; gap: 8px; align-items: flex-start; }
  .monitoring-page .monitor-metric__icon { width: 24px; height: 24px; border-radius: 7px; }
  .monitoring-page .monitor-metric > div { grid-template-columns: minmax(0, 1fr); row-gap: 3px; }
  .monitoring-page .monitor-metric strong { font-size: 20px; line-height: 1.1; }
  .monitoring-page .monitor-metric span { line-height: 1.25; overflow-wrap: anywhere; }
  .monitoring-page .monitor-metric small { margin-top: 0; font-size: 11px; line-height: 1.3; overflow-wrap: anywhere; }
  .monitoring-page .monitor-metric__time { max-width: none; overflow: visible; white-space: normal; text-overflow: clip; font-size: 17px !important; overflow-wrap: anywhere; }
  .monitoring-section-nav a { flex: 1; padding-inline: 8px; }
}

</style>

<style scoped>
.monitoring-controls {
  --control-row-size: 44px;
}
body.care-senior .monitoring-controls {
  --control-row-size: 48px;
}
.monitoring-controls .range-select {
  --el-component-size: var(--control-row-size);
}
.monitoring-controls .range-select :deep(.el-select__wrapper),
.monitoring-controls .refresh-button {
  box-sizing: border-box;
  min-height: var(--control-row-size);
}
.monitoring-controls .refresh-button {
  height: auto;
}
</style>
