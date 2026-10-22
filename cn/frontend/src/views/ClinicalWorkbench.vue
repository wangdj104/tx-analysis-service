<template>
  <main class="clinical-workbench" v-loading="loading">
    <header class="workbench-hero">
      <div>
        <span class="eyebrow">临床安全与协同</span>
        <h1>临床工作台</h1>
        <p>集中处理健康告警、数据核验、用药安全、透析质量、急救交接和受控数据导入。</p>
      </div>
      <el-button :disabled="!patientId" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
    </header>

    <el-alert v-if="!patientId" title="请先在顶部选择家庭成员，再打开临床工作台。" type="warning" :closable="false" show-icon />
    <p v-else-if="overviewState !== 'ready'" role="status" class="workbench-state">{{ overviewState === 'error' ? '工作台信息不可用，请刷新后重试。' : loading ? '正在加载工作台…' : '请刷新以加载工作台信息。' }}</p>
    <template v-else>
      <section class="metric-grid" aria-label="工作台概览">
        <article><span>待关注事项</span><strong>{{ countValue(overview.attention?.total) }}</strong><small>{{ countValue(overview.attention?.critical) }} 项紧急</small></article>
        <article><span>数据问题</span><strong>{{ countValue(overview.dataQuality?.total) }}</strong><small>已知待审核条目：{{ countValue(overview.dataQuality?.pendingReview) }}</small></article>
        <article><span>用药风险</span><strong>{{ overview.medicationSafety?.findings?.length || 0 }}</strong><small>{{ adherenceText }}</small></article>
        <article><span>透析次数</span><strong>{{ countValue(overview.dialysisQuality?.sessionCount) }}</strong><small>近 {{ overview.dialysisQuality?.rangeDays || 90 }} 天</small></article>
      </section>

      <AutomationRunStatus :patient-id="patientId" :refresh-key="overview" @acknowledged="overviewAcknowledged" />

      <section class="workbench-panel">
        <el-tabs v-model="activeTab" class="workbench-tabs">
          <el-tab-pane label="关注中心" name="attention">
            <div class="section-heading"><div><h2>关注中心</h2><p>按严重程度排序，并展示依据、负责人、响应时限和建议操作。</p></div></div>
            <p class="qualification-note">已记录告警数量保留历史严重程度。仅在已提供信息时展示当前医疗来源资格。</p>
            <div v-if="overview.attention?.items?.length" class="issue-list">
              <article v-for="(item, index) in overview.attention.items" :key="`${item.type}-${item.sourceId}-${index}`" class="issue-row">
                <div class="issue-marker" :class="`is-${(item.severity || 'info').toLowerCase()}`"></div>
                <div class="issue-copy"><div><el-tag size="small" :type="tagType(item.severity)">{{ severityText(item.severity) }}</el-tag><b>{{ item.title }}</b></div><p>{{ item.evidence || '相关依据可在源记录中查看' }}</p><small><span v-if="item.dueAt">到期 {{formatDate(item.dueAt)}}</span><span v-if="Number.isFinite(item.slaMinutes)"> · SLA {{formatSla(item.slaMinutes)}}</span><span v-if="item.ownerId"> · 负责人 #{{ item.ownerId }}</span></small><section v-if="item.type === 'ALERT'" class="alert-source-facts"><b>已记录告警</b><p v-for="(line, lineIndex) in alertSourceLines(item)" :key="lineIndex">{{ line }}</p></section></div>
                <div class="row-actions"><span>{{ item.recommendedAction }}</span><el-button type="primary" plain @click="openPath(item.path)">查看来源</el-button></div>
              </article>
            </div>
            <el-empty v-else description="当前没有需要关注的事项。" />
          </el-tab-pane>

          <el-tab-pane label="数据质量" name="quality">
            <div class="section-heading"><div><h2>数据质量中心</h2><p>来源资格、DOCTOR 复核和 AI 草稿审核是不同事实。待审核数量按条目统计，不代表不同记录数或医疗许可。</p></div></div>
            <p v-if="reviewInformationIncomplete" role="status" class="qualification-note">当前审核信息不完整</p>
            <div v-if="overview.dataQuality?.issues?.length" class="issue-list">
              <article v-for="(item, index) in overview.dataQuality.issues" :key="`${item.type}-${item.sourceId}-${index}`" class="issue-row">
                <div class="issue-marker" :class="`is-${(item.severity || 'info').toLowerCase()}`"></div>
                <div class="issue-copy"><div><el-tag size="small" :type="tagType(item.severity)">{{ qualityType(item.type) }}</el-tag><b>{{ item.title }}</b></div><p>{{ item.detail }}</p><small v-if="item.confidence != null">置信度 {{ Math.round(Number(item.confidence) * 100) }}%</small><section v-if="isReviewIssue(item)" class="review-qualification" data-review-quality>
                  <p>{{ reviewPresentation(item).sourceText }}</p><p v-if="reviewPresentation(item).doctorText">{{ reviewPresentation(item).doctorText }}</p>
                  <p v-if="reviewPresentation(item).sourceActorText">{{ reviewPresentation(item).sourceActorText }}</p><p v-if="reviewPresentation(item).doctorActorText">{{ reviewPresentation(item).doctorActorText }}</p>
                  <p :data-tone="reviewPresentation(item).automaticTone">{{ reviewPresentation(item).automaticText }}</p><p v-if="reviewPresentation(item).observationText">{{ reviewPresentation(item).observationText }}</p>
                  <p v-if="item.reviewChannel === 'LEGACY' && reviewPresentation(item).mode === 'UNAVAILABLE'">审核信息不可用。仍可查看来源；只有重新加载的预览确认可提交后，才能进行旧版审核。</p>
                </section></div>
                <div v-if="item.type === 'REVIEW'" class="button-cluster"><template v-if="reviewPresentation(item).legacyEntry"><el-button type="success" plain @click="reviewRecord(item, true)">核验通过</el-button><el-button type="danger" plain @click="reviewRecord(item, false)">驳回</el-button></template><el-button v-else plain @click="reviewRecord(item)">查看来源</el-button></div>
                <div v-else-if="item.type === 'AI_DRAFT'" class="button-cluster"><el-button type="success" plain @click="reviewDraft(item, true, false)">批准</el-button><el-button type="primary" plain @click="reviewDraft(item, true, true)">批准并通知</el-button><el-button type="danger" plain @click="reviewDraft(item, false, false)">驳回</el-button></div>
                <el-button v-else-if="item.type !== 'REVIEW_AVAILABILITY' && safeId(item.sourceId)" plain @click="openQualitySource(item)">审核来源</el-button>
              </article>
            </div>
            <el-empty v-else description="本次视图未返回问题" />
          </el-tab-pane>

          <el-tab-pane label="用药安全" name="medication">
            <div class="section-heading"><div><h2>用药安全</h2><p>检查药物过敏、潜在重复用药、已知相互作用、肾功能注意事项、库存与依从性。</p></div><el-tag type="info">仅供辅助决策</el-tag></div>
            <el-alert :title="overview.medicationSafety?.disclaimer" type="warning" :closable="false" show-icon />
            <div class="quality-metrics">
              <article><span>30 天服药依从率</span><strong>{{ adherenceText }}</strong></article>
              <article><span>已服用</span><strong>{{ overview.medicationSafety?.taken || 0 }}</strong></article>
              <article><span>漏服 / 跳过</span><strong>{{ overview.medicationSafety?.missedOrSkipped || 0 }}</strong></article>
              <article><span>已记录过敏药物</span><strong class="small-value">{{ overview.medicationSafety?.allergies || '暂无记录' }}</strong></article>
            </div>
            <div v-if="overview.medicationSafety?.findings?.length" class="finding-grid">
              <article v-for="(finding, index) in overview.medicationSafety.findings" :key="index" class="finding-card" :class="`is-${finding.severity?.toLowerCase()}`"><div><el-tag :type="tagType(finding.severity)" size="small">{{ findingType(finding.type) }}</el-tag><b>{{ finding.title }}</b></div><p>{{ finding.evidence }}</p><small>{{ finding.action }}</small></article>
            </div>
            <el-empty v-else description="根据现有数据，暂未发现用药安全风险。" />
          </el-tab-pane>

          <el-tab-pane label="透析质量" name="dialysis">
            <div class="section-heading"><div><h2>透析质量看板</h2><p>汇总透析间期体重增长、超滤率、透析充分性、血管通路记录和已确认排班。</p></div><el-select v-model="dialysisDays" style="width:130px" @change="load"><el-option label="近 30 天" :value="30"/><el-option label="近 90 天" :value="90"/><el-option label="近 180 天" :value="180"/></el-select></div>
            <div class="quality-metrics">
              <article><span>平均 IDWG</span><strong>{{ metric(overview.dialysisQuality?.avgIdwgPercent, '%') }}</strong></article>
              <article><span>平均 UFR</span><strong>{{ metric(overview.dialysisQuality?.avgUfr, ' mL/kg/h') }}</strong></article>
              <article><span>平均 Kt/V</span><strong>{{ metric(overview.dialysisQuality?.avgKtv) }}</strong></article>
              <article><span>平均 URR</span><strong>{{ metric(overview.dialysisQuality?.avgUrr, '%') }}</strong></article>
            </div>
            <el-alert :title="overview.dialysisQuality?.disclaimer" type="info" :closable="false" show-icon />
            <div v-if="overview.dialysisQuality?.flags?.length" class="finding-grid compact">
              <article v-for="flag in overview.dialysisQuality.flags" :key="flag.metric" class="finding-card"><div><el-tag :type="tagType(flag.severity)" size="small">{{ flag.metric }}</el-tag><b>{{ flag.message }}</b></div></article>
            </div>
            <div class="schedule-builder">
              <div><h3>周期性排班生成器</h3><p>系统不会静默创建排班，请先预览日期，再明确确认。</p></div>
              <el-form label-position="top" class="schedule-form">
                <el-form-item label="星期"><el-checkbox-group v-model="schedule.weekdays"><el-checkbox-button v-for="day in weekdayOptions" :key="day.value" :value="day.value">{{ day.label }}</el-checkbox-button></el-checkbox-group></el-form-item>
                <el-form-item label="时间"><el-time-picker v-model="schedule.time" value-format="HH:mm" format="HH:mm" /></el-form-item>
                <el-form-item label="开始日期"><el-date-picker v-model="schedule.from" value-format="YYYY-MM-DD" /></el-form-item>
                <el-form-item label="结束日期"><el-date-picker v-model="schedule.to" value-format="YYYY-MM-DD" /></el-form-item>
              </el-form>
              <div class="button-cluster"><el-button @click="previewSchedule">预览日期</el-button><el-button type="primary" :disabled="!schedulePreview.length" @click="confirmSchedule">确认并创建</el-button></div>
              <div v-if="schedulePreview.length" class="schedule-preview"><el-tag v-for="row in schedulePreview" :key="row.scheduleDate" type="info">{{ row.scheduleDate }} {{ row.scheduleTime || '' }}</el-tag></div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="急救卡" name="emergency">
            <div class="section-heading no-print"><div><h2>急救与医生交接卡</h2><p>简洁、可打印的患者摘要。分享前请再次核对身份、过敏和用药信息。</p></div><el-button type="primary" @click="printCard"><el-icon><Printer /></el-icon>打印卡片</el-button></div>
            <article class="emergency-card" id="emergency-card">
              <header><div><span>{{ platformBranding.platformName }} · 急救摘要</span><h2>{{ overview.emergencyCard?.patient?.name || currentPatientName }}</h2><p>生成时间：{{ formatDate(overview.emergencyCard?.generatedAt) }}</p></div><el-tag type="danger" effect="dark">使用前请核验</el-tag></header>
              <div class="emergency-grid">
                <section><h3>身份与联系方式</h3><dl><dt>出生日期</dt><dd>{{ overview.emergencyCard?.patient?.birthDate || '—' }}</dd><dt>联系电话</dt><dd>{{ overview.emergencyCard?.patient?.phone || '—' }}</dd><dt>紧急联系人</dt><dd>{{ emergencyContact }}</dd></dl></section>
                <section><h3>肾脏照护</h3><dl><dt>透析类型</dt><dd>{{ overview.emergencyCard?.clinical?.dialysisType || '—' }}</dd><dt>血管通路</dt><dd>{{ overview.emergencyCard?.clinical?.vascularAccess || '—' }}</dd><dt>目标干体重</dt><dd>{{ metric(overview.emergencyCard?.clinical?.targetDryWeight, ' kg') }}</dd><dt>液体限制</dt><dd>{{ overview.emergencyCard?.clinical?.fluidLimitMl ? `${overview.emergencyCard.clinical.fluidLimitMl} mL/天` : '—' }}</dd></dl></section>
                <section class="critical-section"><h3>过敏与诊断</h3><dl><dt>药物过敏</dt><dd>{{ overview.emergencyCard?.clinical?.allergyDrugs || '暂无记录' }}</dd><dt>主要诊断</dt><dd>{{ overview.emergencyCard?.clinical?.primaryDiagnosis || overview.emergencyCard?.patient?.medicalHistory || '—' }}</dd></dl></section>
                <section><h3>照护团队</h3><dl><dt>医院</dt><dd>{{ overview.emergencyCard?.target?.hospitalName || '—' }}</dd><dt>医生</dt><dd>{{ overview.emergencyCard?.target?.doctorName || '—' }}</dd></dl></section>
              </div>
              <section><h3>当前用药</h3><div class="medication-chips"><span v-for="med in overview.emergencyCard?.medications || []" :key="med.id">{{ med.drugName }} {{ med.defaultDosage || '' }}</span><i v-if="!overview.emergencyCard?.medications?.length">暂无记录</i></div></section>
              <section><h3>未处理告警</h3><ul><li v-for="(alert, index) in overview.emergencyCard?.unresolvedAlerts || []" :key="`${alert.id}-${index}`">{{ severityText(alert.alertLevel) }} · {{ alert.alertTitle }} · {{ alert.triggeredValue }}<div class="alert-source-facts"><b>已记录告警</b><p v-for="(line, lineIndex) in alertSourceLines(alert, true)" :key="lineIndex">{{ line }}</p></div></li><li v-if="!overview.emergencyCard?.unresolvedAlerts?.length">平台内没有未处理告警。</li></ul></section>
              <footer>{{ overview.emergencyCard?.disclaimer }}</footer>
            </article>
          </el-tab-pane>

          <el-tab-pane label="FHIR / 设备导入" name="import">
            <div class="section-heading"><div><h2>受控临床数据导入</h2><p>FHIR 观察结果和设备读数会先解析为预览，提交时必须明确确认，导入值将保持“待审核”状态。</p></div></div>
            <el-form label-position="top" class="import-form">
              <el-form-item label="数据来源"><el-radio-group v-model="importMode" @change="loadImportSample"><el-radio-button value="FHIR">FHIR 数据包</el-radio-button><el-radio-button value="DEVICE">设备读数</el-radio-button></el-radio-group></el-form-item>
              <el-form-item label="JSON 数据"><el-input v-model="importText" type="textarea" :rows="13" spellcheck="false" /></el-form-item>
              <div class="button-cluster"><el-button :loading="importBusy" :disabled="importBusy || submitting" @click="previewImport">校验并预览</el-button><el-button type="primary" :disabled="importBusy || !importPreview?.itemCount || !lastImportContext" @click="commitImport">确认导入</el-button></div>
            </el-form>
            <p class="import-notice">中止请求不代表回滚。若提交后中断，请先核验记录再重试；没有稳定来源编号的数据可能重复导入。</p>
            <p v-if="importPreview" class="import-counts" role="status">有效 {{ importPreview.itemCount || 0 }} 项；已拒绝 {{ importPreview.rejectedCount || 0 }} 项。仅导入有效项，请修正拒绝项后重新预览。</p>
            <el-alert v-for="(warning, index) in importWarnings" :key="index" :title="warning" type="warning" :closable="false" show-icon />
            <article v-for="(row, index) in importRejectedRows" :key="index" class="import-rejected-row" data-testid="import-rejected-row">
              <p><strong>{{ importFactsText('rejectedRow') }} {{ row.sourceType }} #{{ row.rowIndex }}</strong> · {{ row.message }}</p>
              <ul><li v-for="(problem, fieldIndex) in (row.fields || []).slice(0, 8)" :key="fieldIndex">{{ importProblemText(problem) }}</li></ul>
            </article>
            <el-alert v-if="importError" :title="importError" type="error" :closable="false" show-icon />
            <el-alert v-if="importResult" :title="`已创建 ${importResult.created || 0} 条，跳过 ${importResult.skipped || 0} 条，失败 ${importResult.errors || 0} 条。`" :type="importResult.errors > 0 ? 'warning' : 'success'" :closable="false" show-icon />
            <el-alert v-for="(message, index) in importResult?.messages || []" :key="index" :title="message" type="warning" :closable="false" show-icon />
            <el-table v-if="importPreview?.items?.length" :data="importPreview.items" class="import-preview-table">
              <el-table-column prop="kind" label="类型" width="90"/><el-table-column prop="date" label="日期" width="120"/><el-table-column prop="display" label="观察指标" min-width="160"><template #default="{row}">{{ row.display || row.measureType }}</template></el-table-column><el-table-column label="数值" min-width="160"><template #default="{row}">{{ importValue(row) }}</template></el-table-column><el-table-column prop="externalId" label="来源 ID" min-width="160" show-overflow-tooltip/>
              <el-table-column :label="importFactsText('previewHeading')" min-width="180"><template #default="{ $index }"><button type="button" class="import-facts-toggle" data-action="review-import-facts" :disabled="importBusy" :aria-expanded="expandedImportIndex === $index" aria-controls="import-original-facts" @click="expandedImportIndex = expandedImportIndex === $index ? null : $index">{{ importFactsText(expandedImportIndex === $index ? 'hideFacts' : 'reviewFacts') }} {{ $index + 1 }}</button></template></el-table-column>
            </el-table>
            <section v-if="expandedImportRow" id="import-original-facts" class="import-facts-preview" :aria-label="importFactsText('previewHeading')">
              <p><strong>{{ importFactsText('previewRow') }} {{ expandedImportIndex + 1 }}</strong></p>
              <OriginalImportFacts :original-import="expandedImportRow.originalImport" preview />
            </section>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>
    <MedicalRecordReviewDialog v-if="medicalReviewTarget" :key="medicalReviewEpoch" :record-id="medicalReviewTarget.recordId" :patient-id="medicalReviewTarget.patientId" @closed="medicalReviewClosed" @reviewed="medicalReviewCompleted" />
  </main>
</template>

<script setup>
import MedicalRecordReviewDialog from '@/components/MedicalRecordReviewDialog.vue'
import OriginalImportFacts from '@/components/OriginalImportFacts.vue'
import { originalFactsText as importFactsText, importProblemText } from '@/utils/originalImportFacts'
import { computed, inject, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache, AUTH_STORAGE_KEYS } from '@/utils/authSession'
import { medicalReviewPresentation } from '@/utils/medicalReviewQualification'
import { platformBranding } from '@/utils/platformBranding'
import AutomationRunStatus from '@/components/AutomationRunStatus.vue'
import { getClinicalWorkbenchOverview, getDialysisQuality, reviewAnalysisDraft, generateDialysisSchedule, previewClinicalImport, commitClinicalImport } from '@/api/clinicalWorkbench'

const router = useRouter(), route = useRoute()
const { currentPatientId: patientId, currentPatientName } = useCurrentPatient()
const loading = ref(false), activeTab = ref('attention'), overview = ref({}), dialysisDays = ref(90)
const schedulePreview = ref([]), importMode = ref('FHIR'), importText = ref(''), importPreview = ref(null), lastImportContext = ref(null)
const importBusy = ref(false), importResult = ref(null), importError = ref('')
const expandedImportIndex = ref(null)
const expandedImportRow = computed(() => expandedImportIndex.value === null ? null : importPreview.value?.items?.[expandedImportIndex.value])
const importRejectedRows = computed(() => Array.isArray(importPreview.value?.rejectedRows) ? importPreview.value.rejectedRows.slice(0, 1000) : [])
const importWarnings = computed(() => {
  const rejectedMessages = new Set(importRejectedRows.value.map(row => row.message))
  return (importPreview.value?.warnings || []).filter(message => !rejectedMessages.has(message))
})
watch(importPreview, preview => { expandedImportIndex.value = preview?.items?.length ? 0 : null }, { flush: 'sync' })
let importAttempt = null, importDisposed = false
const medicalReviewTarget = ref(null), medicalReviewEpoch = ref(0), medicalReviewClosed = ref(null), medicalReviewCompleted = ref(null)
const submitting = ref(false)
let loadEpoch = 0, importEpoch = 0, scheduleEpoch = 0
let scheduleSnapshot = ''
const weekdayOptions = [{value:1,label:'周一'},{value:2,label:'周二'},{value:3,label:'周三'},{value:4,label:'周四'},{value:5,label:'周五'},{value:6,label:'周六'},{value:7,label:'周日'}]
const futureDate = days => { const date = new Date(); date.setDate(date.getDate() + days); return [date.getFullYear(), String(date.getMonth()+1).padStart(2,'0'), String(date.getDate()).padStart(2,'0')].join('-') }
const schedule = reactive({ weekdays: [], time: '', from: futureDate(0), to: futureDate(60) })
const fhirSample = { resourceType:'Bundle', type:'collection', entry:[{ resource:{ resourceType:'Observation', id:'bp-example-1', status:'final', code:{coding:[{system:'http://loinc.org',code:'85354-9',display:'血压组合'}]}, effectiveDateTime:new Date().toISOString(), component:[{code:{coding:[{code:'8480-6'}]},valueQuantity:{value:128,unit:'mmHg'}},{code:{coding:[{code:'8462-4'}]},valueQuantity:{value:78,unit:'mmHg'}}] } }] }
const deviceSample = [{ sourceExternalId:'home-device-example-1', observedAt:new Date().toISOString(), systolic:128, diastolic:78, glucose:5.6, unit:'mmol/L' }]

const adherenceText = computed(() => overview.value.medicationSafety?.adherenceRate == null ? '暂无已完成任务' : `${overview.value.medicationSafety.adherenceRate}%`)
const emergencyContact = computed(() => {
  const patient = overview.value.emergencyCard?.patient || {}, target = overview.value.emergencyCard?.target || {}
  const name = target.emergencyContact || patient.emergencyContact || '—', phone = target.emergencyPhone || patient.emergencyPhone || ''
  return `${name}${phone ? ` · ${phone}` : ''}`
})

const overviewState = ref('idle'), overviewAcknowledged = ref(null), permissionMenus = inject('userMenus', ref([]))
let overviewDisposed = false, overviewOwner = null
const objectRow = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const validRows = value => Array.isArray(value) && value.every(objectRow)
const safeId = value => (typeof value === 'number' || typeof value === 'string' && /^[1-9]\d*$/.test(value)) && Number.isSafeInteger(Number(value)) && Number(value) > 0
const permissionKey = () => JSON.stringify(readPermissionCache())
const menuKey = () => JSON.stringify(permissionMenus.value)
const captureOverviewOwner = () => ({ auth: captureAuthSession(), actorId: localStorage.getItem('userId'), permission: permissionKey(), menus: menuKey(), patientId: patientId.value, days: dialysisDays.value, route: route.fullPath })
const ownsOverview = owner => !overviewDisposed && !!owner && isAuthSessionCurrent(owner.auth) && owner.actorId === localStorage.getItem('userId') && owner.permission === permissionKey() && owner.menus === menuKey() && owner.patientId === patientId.value && owner.days === dialysisDays.value && owner.route === route.fullPath
function retireMedicalReview() { medicalReviewEpoch.value++; medicalReviewTarget.value=null; medicalReviewClosed.value=null; medicalReviewCompleted.value=null }
function retireOverview() { overviewAcknowledged.value=null; loadEpoch++; overviewOwner=null; overview.value={}; overviewState.value='idle'; loading.value=false; retireMedicalReview() }
function currentOverview() { if(!ownsOverview(overviewOwner)){retireOverview();return false}return overviewState.value==='ready' }
function validOverview(data, dialysis) {
  return objectRow(data) && objectRow(data.attention) && validRows(data.attention.items) && data.attention.items.every(row=>typeof row.type==='string') && objectRow(data.dataQuality) && validRows(data.dataQuality.issues) && data.dataQuality.issues.every(row=>typeof row.type==='string') && objectRow(data.medicationSafety) && validRows(data.medicationSafety.findings) && validRows(data.medicationSafety.medications) && objectRow(data.emergencyCard) && validRows(data.emergencyCard.medications) && validRows(data.emergencyCard.unresolvedAlerts) && objectRow(dialysis) && validRows(dialysis.sessions) && validRows(dialysis.flags)
}
async function load(){
  if(overviewDisposed)return
  retireOverview()
  const epoch=loadEpoch, owner=captureOverviewOwner()
  if(!safeId(owner.patientId))return
  overviewOwner=owner; overviewState.value='loading'; loading.value=true
  if(!ownsOverview(owner)){retireOverview();return}
  // Existing overview/dialysis wrappers do not forward auth/signal options.
  // These guards retire local state; they cannot cancel transport or global toasts.
  try {
    const [res,dialysis]=await Promise.all([getClinicalWorkbenchOverview(owner.patientId),getDialysisQuality(owner.patientId,owner.days)])
    if(epoch!==loadEpoch)return
    if(!ownsOverview(owner)){retireOverview();return}
    if(res?.code!==200 || dialysis?.code!==200 || !validOverview(res.data,dialysis.data))throw new Error('Invalid workbench response')
    overview.value={...res.data,dialysisQuality:dialysis.data}; overviewState.value='ready'; hydrateSchedule()
    overviewAcknowledged.value=()=>{if(owner===overviewOwner && ownsOverview(owner) && overviewState.value==='ready')return load()}
  } catch { if(epoch===loadEpoch){if(!ownsOverview(owner))retireOverview();else{overview.value={};retireMedicalReview();overviewState.value='error'}} }
  finally { if(epoch===loadEpoch){if(!ownsOverview(owner))retireOverview();else loading.value=false} }
}
function countValue(value){return Number.isSafeInteger(value) && value>=0 ? value : '—'}
const reviewInformationIncomplete = computed(()=>overview.value.dataQuality?.issues?.some(row=>row.type==='REVIEW_AVAILABILITY')===true)
function isReviewIssue(item){return item.sourceType==='medical-record' && ['REVIEW','SOURCE_REVIEW','DOCTOR_REVIEW','DOCTOR_REVIEW_OUTCOME'].includes(item.type)}
function reviewPresentation(item){
  const value=medicalReviewPresentation(item.reviewSummary, 'zh')
  const summary=item.reviewSummary, known=['NONE','PARTICIPANT','UNAVAILABLE'].includes(value.mode)
  const consistent=item.type==='REVIEW' ? item.reviewChannel==='LEGACY' && ['NONE','UNAVAILABLE'].includes(value.mode) : item.type==='SOURCE_REVIEW' ? item.reviewChannel==='SOURCE' && value.mode==='PARTICIPANT' && value.automaticInputEligible===false : item.reviewChannel==='DOCTOR' && value.mode==='PARTICIPANT' && ['DOCTOR_ONLY','BOTH'].includes(value.participation) && (item.type==='DOCTOR_REVIEW' ? summary?.doctor?.state==='PENDING' : ['APPROVED','REJECTED'].includes(summary?.doctor?.state))
  if(known && (summary?.qualificationSemantics!=='CURRENT_CHECK' || !consistent))return {...value,sourceText:'当前审核信息与本工作视图不匹配。请重新加载来源以核实当前资格。',doctorText:'',sourceActorText:'',doctorActorText:'',automaticText:'无法核实自动分析输入资格',automaticTone:'info',legacyEntry:false}
  return {...value,legacyEntry:item.type==='REVIEW' && item.reviewChannel==='LEGACY' && value.mode==='NONE'}
}
function validSourceInstant(value){
  if(typeof value!=='string')return false
  const m=value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/)
  if(!m)return false
  const [year,month,day,hour,minute,second]=m.slice(1).map(Number),leap=year%4===0 && (year%100!==0 || year%400===0),days=[31,leap?29:28,31,30,31,30,31,31,30,31,30,31]
  return month>=1 && month<=12 && day>=1 && day<=days[month-1] && hour<24 && minute<60 && second<60
}
function alertSourceLines(item, emergency=false){
  const keys=['sourceStatus','sourceBindingStatus','currentSourceStatus','sourceQualificationSemantics','sourceQualificationAt']
  const present=keys.some(key=>Object.hasOwn(item,key)),medical=emergency && item.sourceType==='MEDICAL_RECORD_ITEM' && item.alertType!=='MEASUREMENT_FOLLOW_UP'
  if(emergency && !medical)return []
  if(!present)return medical?['当前来源资格不可用']:[]
  if(!keys.every(key=>Object.hasOwn(item,key)))return ['当前来源资格不可用']
  const {sourceStatus:status,sourceBindingStatus:binding,currentSourceStatus:current,sourceQualificationSemantics:semantics,sourceQualificationAt:at}=item
  if(status==='SOURCE_UNAVAILABLE' && binding===null && current==='SOURCE_UNAVAILABLE' && semantics===null && at===null)return ['当前来源资格不可用']
  const currentCopy={'CURRENT': '当前来源资格：有效', 'UNTRACKED': '当前来源：旧版处理', 'SOURCE_REVIEW_REQUIRED': '当前来源待核对', 'SOURCE_CHANGED': '当前来源已变更', 'SOURCE_INVALIDATED': '当前来源已失效', 'SOURCE_UNAVAILABLE': '当前来源资格不可用'}, bindingCopy={'CURRENT_CONTENT': '历史链接匹配当前内容', 'UNBOUND': '历史链接未绑定版本', 'CONTENT_CHANGED': '历史链接指向较早的来源版本', 'GENERATION_CHANGED': '历史链接指向较早的来源版本', 'MISSING_ANCHOR': '无法核实历史来源链接', 'SOURCE_MISSING': '无法核实历史来源链接', 'STORE_CONFLICT': '无法核实历史来源链接'}
  if(!Object.hasOwn(currentCopy,current) || !Object.hasOwn(bindingCopy,binding) || !['CURRENT','UNTRACKED','SOURCE_REVIEW_REQUIRED','SOURCE_CHANGED','SOURCE_INVALIDATED','SOURCE_UNAVAILABLE','UNKNOWN_NEEDS_RECHECK'].includes(status) || semantics!=='CURRENT_CHECK' || !validSourceInstant(at))return ['当前来源资格不可用']
  const coherent=binding==='MISSING_ANCHOR' || binding==='SOURCE_MISSING' ? status==='SOURCE_UNAVAILABLE' && current==='SOURCE_UNAVAILABLE' : binding==='STORE_CONFLICT' ? status==='SOURCE_UNAVAILABLE' : binding==='UNBOUND' ? status===(current==='CURRENT'?'UNKNOWN_NEEDS_RECHECK':current) : binding==='GENERATION_CHANGED' ? status==='SOURCE_CHANGED' : binding==='CONTENT_CHANGED' ? status==='SOURCE_CHANGED' || status===current && !['CURRENT','UNTRACKED'].includes(current) : status===(['CURRENT','UNTRACKED'].includes(current)?'CURRENT':current)
  if(!coherent)return ['当前来源资格不可用']
  return [bindingCopy[binding],currentCopy[current],...(status==='UNKNOWN_NEEDS_RECHECK'?['历史链接需要重新核对']:[]),'当前来源核对时间：'+at]
}
function hydrateSchedule(){
  const quality=overview.value.dialysisQuality||{}
  if(!schedule.weekdays.length && quality.scheduleWeekdays) schedule.weekdays=String(quality.scheduleWeekdays).split(',').map(Number)
  if(!schedule.time && quality.scheduleTime) schedule.time=quality.scheduleTime
}
function tagType(severity){ return ({CRITICAL:'danger',WARNING:'warning',INFO:'info'}[severity]||'info') }
function severityText(severity){ return ({CRITICAL:'紧急',WARNING:'警告',INFO:'提示',HIGH:'高',MEDIUM:'中',LOW:'低'}[severity]||severity) }
function qualityType(type){ return ({REVIEW:'旧版审核',SOURCE_REVIEW:'来源核对',DOCTOR_REVIEW:'DOCTOR 通道待复核',DOCTOR_REVIEW_OUTCOME:'DOCTOR 通道复核结果',REVIEW_AVAILABILITY:'审核信息不可用',LOW_CONFIDENCE:'置信度较低',MISSING_CONTEXT:'缺少上下文',DUPLICATE:'可能重复',DIALYSIS_COMPLETENESS:'透析数据不完整',AI_DRAFT:'AI 草稿'}[type]||type) }
function findingType(type){ return ({ALLERGY:'过敏风险',DUPLICATE:'重复用药',INTERACTION:'药物相互作用',RENAL:'肾功能用药提示',STOCK:'库存不足',ADHERENCE:'依从性提示'}[type]||type) }
function formatDate(value){ if(!value)return '未设置'; const date=new Date(value); return Number.isNaN(date.getTime())?String(value):date.toLocaleString('zh-CN') }
function formatSla(minutes){ return minutes<60?`${minutes} 分钟`:minutes<1440?`${minutes/60} 小时`:`${minutes/1440} 天` }
function metric(value,suffix=''){ return value==null?'—':`${value}${suffix}` }
function openPath(path){ if(currentOverview() && path && overview.value.attention.items.some(item=>item.path===path))router.push(path) }
function openQualitySource(item){ if(!currentOverview() || !overview.value.dataQuality.issues.includes(item) || item.type==='REVIEW_AVAILABILITY' || !safeId(item.sourceId))return; router.push(item.sourceType==='dialysis'?'/dialysis':'/medical-record') }
function reviewRecord(item){
  retireMedicalReview()
  if(!currentOverview() || !overview.value.dataQuality.issues.includes(item) || item.type!=='REVIEW' || item.sourceType!=='medical-record' || !safeId(item.sourceId) || !safeId(patientId.value))return
  const epoch=medicalReviewEpoch.value, owner=overviewOwner
  medicalReviewClosed.value=()=>{if(epoch===medicalReviewEpoch.value)retireMedicalReview()}
  medicalReviewCompleted.value=()=>{if(epoch===medicalReviewEpoch.value && owner===overviewOwner && ownsOverview(owner))return reviewedMedicalRecord()}
  medicalReviewTarget.value={recordId:Number(item.sourceId),patientId:patientId.value}
}
async function reviewedMedicalRecord(){ if(!medicalReviewTarget.value || !currentOverview())return; retireMedicalReview(); await load() }
watch(patientId, () => { medicalReviewTarget.value = null }, { flush: 'sync' })
async function reviewDraft(item,approved,notify){
  const readOwner=overviewOwner
  const verb=approved?(notify?'批准并发送审核后的内容':'批准此草稿'):'驳回此草稿'
  await ElMessageBox.confirm(`确认要${verb}吗？`, 'AI 分析审核', {type:notify?'warning':'info'})
  await reviewAnalysisDraft(item.sourceId,approved,notify); if(readOwner!==overviewOwner || !ownsOverview(readOwner))return; ElMessage.success(approved?'草稿已批准。':'草稿已驳回。'); await load()
}
function scheduleBody(confirmed=false){ return {patientId:patientId.value,weekdays:schedule.weekdays.join(','),time:schedule.time||null,from:schedule.from,to:schedule.to,confirmed} }
async function previewSchedule(){
  if(!patientId.value || submitting.value)return
  const body=scheduleBody(false), snapshot=JSON.stringify(body), epoch=++scheduleEpoch
  schedulePreview.value=[]; scheduleSnapshot=''
  const res=await generateDialysisSchedule(body)
  if(epoch!==scheduleEpoch || snapshot!==JSON.stringify(scheduleBody(false)))return
  schedulePreview.value=res.data?.preview||[]; scheduleSnapshot=snapshot
  if(!schedulePreview.value.length)ElMessage.warning('没有符合当前计划的日期。')
}
async function confirmSchedule(){
  if(submitting.value || !schedulePreview.value.length || scheduleSnapshot!==JSON.stringify(scheduleBody(false)))return
  const snapshot=scheduleSnapshot, body={...scheduleBody(false),confirmed:true}, readOwner=overviewOwner
  submitting.value=true
  try {
  await ElMessageBox.confirm(`确认创建 ${schedulePreview.value.length} 个排班日期吗？已有日期将被跳过。`, '确认透析计划', {type:'warning'})
  if(snapshot!==scheduleSnapshot || snapshot!==JSON.stringify(scheduleBody(false)))return
const res=await generateDialysisSchedule(body); if(readOwner!==overviewOwner || !ownsOverview(readOwner))return; ElMessage.success(`已创建 ${res.data?.created||0} 条，跳过 ${res.data?.skipped||0} 条。`); if(snapshot===scheduleSnapshot){schedulePreview.value=[]; scheduleSnapshot=''; await load()}
  } catch(error) { if(error!=='cancel' && error!=='close')console.error(error) }
  finally { submitting.value=false }
}
function printCard(){ if(currentOverview())window.print() }
function loadImportSample(){ importText.value=JSON.stringify(importMode.value==='FHIR'?fhirSample:deviceSample,null,2) }
function buildImportRequest(selectedPatient, mode, rawText, confirmed=false) {
  if (!Number.isSafeInteger(selectedPatient) || selectedPatient <= 0 || !['FHIR', 'DEVICE'].includes(mode) || typeof rawText !== 'string') throw new Error('IMPORT_INPUT_INVALID')
  const value = JSON.parse(rawText)
  const object = value !== null && typeof value === 'object' && !Array.isArray(value)
  if (mode === 'FHIR' ? !object : !(Array.isArray(value) || (object && Array.isArray(value.readings)))) throw new Error('IMPORT_INPUT_INVALID')
  // 解析仅用于语法与外层形状检查；数值始终取原文，内层包装不能覆盖患者或确认信息。
  const body = `{"patientId":${JSON.stringify(selectedPatient)},"confirmed":${confirmed === true},"${mode === 'FHIR' ? 'bundle' : 'readings'}":${rawText}}`
  if (new TextEncoder().encode(body).length > 1048576) throw new Error('IMPORT_TOO_LARGE')
  return body
}
function captureImportContext() {
  return Object.freeze({ patientId: patientId.value, mode: importMode.value, rawText: importText.value, epoch: importEpoch,
    auth: Object.freeze({ ...captureAuthSession(), actorId: localStorage.getItem('userId') }) })
}
function isImportContextCurrent(context) {
  return !importDisposed && context?.epoch === importEpoch && context.patientId === patientId.value && context.mode === importMode.value &&
    context.rawText === importText.value && isAuthSessionCurrent(context.auth) && context.auth.actorId === localStorage.getItem('userId')
}
function ownsImport(attempt) {
  return attempt === importAttempt && !attempt.controller.signal.aborted && isImportContextCurrent(attempt.context)
}
function beginImport(context) {
  // 新尝试先中止旧请求；中止只忽略过期响应，并不表示服务端已回滚。
  importAttempt?.controller.abort()
  const attempt = { context, controller: new AbortController() }
  importAttempt = attempt
  importBusy.value = true
  importError.value = ''
  return attempt
}
function invalidateImport() {
  ++importEpoch
  importAttempt?.controller.abort()
  importAttempt = null
  importBusy.value = false
  importPreview.value = null
  lastImportContext.value = null
  importResult.value = null
  importError.value = ''
}
function importStorageChanged(event) {
  if (event.key === null || ['token', 'userId', 'username', 'realName', 'permissionSession', 'userMenus', 'userMenuNames', 'userRoleCodes', 'currentPatientId'].includes(event.key)) invalidateImport()
}
function showImportInputError(error) {
  importError.value = error?.message === 'IMPORT_TOO_LARGE' ? '导入请求超过 1 MiB，请拆分后重试。' : 'JSON 数据或外层结构无效，请提供 FHIR 对象、设备数组或单层 readings 数组包装。'
  ElMessage.error(importError.value)
}
async function previewImport() {
  if (importDisposed || !patientId.value || submitting.value || importBusy.value) return
  const context = captureImportContext()
  if (!isImportContextCurrent(context)) return
  const attempt = beginImport(context)
  importPreview.value = null
  lastImportContext.value = null
  importResult.value = null
  let body
  try { body = buildImportRequest(context.patientId, context.mode, context.rawText) }
  catch (error) { if (ownsImport(attempt)) { showImportInputError(error); importBusy.value = false } return }
  try {
    if (!ownsImport(attempt)) return
    const res = await previewClinicalImport(body, { signal: attempt.controller.signal, expectedAuth: context.auth })
    if (!ownsImport(attempt)) return
    importPreview.value = res.data
    lastImportContext.value = context
    if (!res.data?.itemCount) ElMessage.warning('没有可导入的有效数据项，请修正后重新预览。')
  } catch {
    // 当前请求错误由共享拦截器显示；过期响应不得再次提示或记录请求内容。
  } finally { if (ownsImport(attempt)) importBusy.value = false }
}
async function commitImport() {
  const context = lastImportContext.value
  if (importBusy.value || submitting.value || !context || !importPreview.value?.itemCount || !isImportContextCurrent(context)) return
  const readContext = captureOverviewOwner(), readEpoch = loadEpoch
  const valid = importPreview.value.itemCount, rejected = importPreview.value.rejectedCount || 0
  const attempt = beginImport(context)
  let dispatched = false
  try {
    await ElMessageBox.confirm(`确认仅导入 ${valid} 项有效数据并标记为待审核？${rejected} 项已拒绝的数据不会导入。`, '确认临床数据导入', { type: 'warning' })
    if (!ownsImport(attempt) || context !== lastImportContext.value) return
    const body = buildImportRequest(context.patientId, context.mode, context.rawText, true)
    if (!ownsImport(attempt)) return
    // 已派发的提交不自动重试；后续必须重新预览并由用户作出新决定。
    dispatched = true
    lastImportContext.value = null
    const res = await commitClinicalImport(body, { signal: attempt.controller.signal, expectedAuth: context.auth })
    if (!ownsImport(attempt)) return
    if (readEpoch !== loadEpoch || !ownsOverview(readContext)) return
    importResult.value = res.data
    importPreview.value = null
    const summary = `已创建 ${res.data?.created || 0} 条，跳过 ${res.data?.skipped || 0} 条，失败 ${res.data?.errors || 0} 条。`
    if (res.data?.errors > 0) ElMessage.warning(summary)
    else ElMessage.success(summary)
    if (ownsImport(attempt)) await load()
  } catch (error) {
    if (!ownsImport(attempt) || error === 'cancel' || error === 'close') return
    if (dispatched && (readEpoch !== loadEpoch || !ownsOverview(readContext))) return
    if (dispatched) importError.value = '尚未确认导入结果，部分数据可能已保存。再次导入前请先核验记录；中止请求不代表回滚。'
    else showImportInputError(error)
  } finally {
    // 旧请求的 finally 不能清除新请求的忙碌状态。
    if (ownsImport(attempt)) importBusy.value = false
  }
}
function importValue(row){ if(row.measureType==='BP'||row.measureType==='BOTH')return `${row.systolic}/${row.diastolic} mmHg${row.glucose!=null?` · ${row.glucose} ${row.unit}`:''}`; if(row.measureType==='BG')return `${row.glucose} ${row.unit}`; return `${row.value??'—'} ${row.unit||''}` }

watch([patientId, importText, importMode], invalidateImport, { flush: 'sync' })
watch(()=>JSON.stringify(scheduleBody()),()=>{ ++scheduleEpoch; schedulePreview.value=[]; scheduleSnapshot='' },{flush:'sync'})
watch(patientId,()=>{ overview.value={}; schedulePreview.value=[];  schedule.weekdays=[]; schedule.time=''; load() },{flush:'sync'})
window.addEventListener('auth-session-changed', invalidateImport)
window.addEventListener('auth-session-cleared', invalidateImport)
window.addEventListener('storage', importStorageChanged)
watch(permissionMenus, retireOverview, { deep: true, flush: 'sync' })
watch(dialysisDays, retireOverview, { flush: 'sync' })
watch(()=>route.fullPath, retireOverview, { flush: 'sync' })
function overviewStorageChanged(event){if(event.key===null || [...AUTH_STORAGE_KEYS,'permissionSession','userMenus','userMenuNames','userRoleCodes','currentPatientId'].includes(event.key))retireOverview()}
window.addEventListener('auth-session-changed', retireOverview)
window.addEventListener('auth-session-cleared', retireOverview)
window.addEventListener('permissions-changed', retireOverview)
window.addEventListener('storage', overviewStorageChanged)
onBeforeUnmount(() => {
  overviewDisposed = true
  retireOverview()
  window.removeEventListener('auth-session-changed', retireOverview)
  window.removeEventListener('auth-session-cleared', retireOverview)
  window.removeEventListener('permissions-changed', retireOverview)
  window.removeEventListener('storage', overviewStorageChanged)
  importDisposed = true
  invalidateImport()
  window.removeEventListener('auth-session-changed', invalidateImport)
  window.removeEventListener('auth-session-cleared', invalidateImport)
  window.removeEventListener('storage', importStorageChanged)
})
onMounted(()=>{ loadImportSample(); load() })
</script>

<style scoped>
.review-qualification,.alert-source-facts{margin-top:10px;min-width:0;overflow-wrap:anywhere;line-height:1.6}.review-qualification{padding:10px;border:1px solid var(--line);border-radius:8px}.review-qualification p,.alert-source-facts p{margin:5px 0}.review-qualification [data-tone="success"]{color:var(--care-800)}.qualification-note,.workbench-state{line-height:1.6;overflow-wrap:anywhere}.button-cluster :deep(.el-button){min-height:44px;height:auto;white-space:normal}.alert-source-facts{color:var(--ink-700)}

.import-facts-toggle{min-height:44px;max-width:100%;padding:8px 12px;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800);font:inherit;line-height:1.5;white-space:normal;overflow-wrap:anywhere;cursor:pointer}.import-facts-toggle:disabled{opacity:.55;cursor:not-allowed}.import-facts-toggle:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}.import-facts-preview,.import-rejected-row{min-width:0;overflow-wrap:anywhere;line-height:1.6}.import-facts-preview>p{margin:16px 0 0}.import-rejected-row{margin:12px 0;padding:12px;border:1px solid var(--line);border-radius:8px;background:var(--surface-subtle);color:var(--ink-800)}.import-rejected-row p{margin:0}.import-rejected-row ul{padding-inline-start:24px;margin:8px 0 0}

.import-form :deep(.el-button),.import-form :deep(.el-radio-button__inner){min-height:44px}.import-form :deep(.el-radio-button__inner){display:inline-flex;align-items:center}.import-notice,.import-counts{line-height:1.6;overflow-wrap:anywhere}.import-notice{color:var(--ink-500);font-size:12px}
.clinical-workbench{max-width:1440px;margin:0 auto;padding:28px 32px 48px;color:var(--ink-950)}.workbench-hero,.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.workbench-hero{margin-bottom:20px}.workbench-hero h1{margin:6px 0;font-size:30px;letter-spacing:-.04em}.workbench-hero p,.section-heading p,.schedule-builder p{margin:0;color:var(--ink-500);line-height:1.6}.eyebrow{font-size:11px;font-weight:700;letter-spacing:.15em;color:var(--care-700)}.metric-grid,.quality-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:18px}.metric-grid article,.quality-metrics article{padding:18px 20px;border:1px solid var(--line);border-radius:14px;background:var(--paper)}.metric-grid span,.quality-metrics span{display:block;color:var(--ink-500);font-size:12px}.metric-grid strong,.quality-metrics strong{display:block;margin:8px 0 3px;font-size:28px}.metric-grid small{color:var(--ink-500)}.workbench-panel{padding:4px 22px 24px;border:1px solid var(--line);border-radius:16px;background:var(--paper)}.section-heading{margin:12px 0 18px}.section-heading h2{margin:0 0 5px;font-size:20px}.issue-list{display:grid;gap:10px}.issue-row{display:grid;grid-template-columns:4px minmax(0,1fr) minmax(180px,auto);gap:14px;align-items:center;padding:15px 16px;border:1px solid var(--line);border-radius:12px;background:var(--surface-subtle)}.issue-marker{align-self:stretch;border-radius:3px;background:var(--info)}.issue-marker.is-critical{background:var(--danger)}.issue-marker.is-warning{background:var(--warning)}.issue-copy>div,.finding-card>div{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.issue-copy b,.finding-card b{font-size:14px}.issue-copy p,.finding-card p{margin:7px 0 4px;color:var(--ink-700)}.issue-copy small,.finding-card small{color:var(--ink-500)}.row-actions{display:flex;align-items:center;justify-content:flex-end;gap:12px;max-width:390px}.row-actions span{font-size:12px;color:var(--ink-700);text-align:right}.button-cluster{display:flex;flex-wrap:wrap;gap:8px}.button-cluster :deep(.el-button + .el-button){margin-left:0}.quality-metrics{margin:18px 0}.quality-metrics .small-value{font-size:16px;line-height:1.3;overflow-wrap:anywhere}.finding-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:16px}.finding-grid.compact{grid-template-columns:repeat(3,minmax(0,1fr))}.finding-card{padding:15px;border:1px solid var(--line);border-radius:12px;background:var(--surface-subtle)}.finding-card.is-critical{border-color:var(--danger-soft);background:var(--danger-soft)}.finding-card.is-warning{border-color:var(--warning-soft);background:var(--warning-soft)}.schedule-builder{margin-top:20px;padding:20px;border-radius:14px;background:var(--surface-subtle);border:1px solid var(--line)}.schedule-builder h3{margin:0 0 5px}.schedule-form{display:grid;grid-template-columns:minmax(300px,2fr) repeat(3,minmax(130px,1fr));gap:12px;margin-top:18px}.schedule-form :deep(.el-form-item){margin-bottom:4px}.schedule-form :deep(.el-date-editor),.schedule-form :deep(.el-time-picker){width:100%}.schedule-preview{display:flex;flex-wrap:wrap;gap:7px;margin-top:14px;max-height:130px;overflow:auto}.emergency-card{padding:26px;border:2px solid var(--care-800);border-radius:16px;background:var(--paper)}.emergency-card>header{display:flex;justify-content:space-between;gap:18px;padding-bottom:18px;border-bottom:1px solid var(--line)}.emergency-card header span{font-size:10px;letter-spacing:.13em}.emergency-card h2{margin:5px 0}.emergency-card header p{margin:0;color:var(--ink-700)}.emergency-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin:16px 0}.emergency-card section{padding:15px;border:1px solid var(--line);border-radius:10px}.emergency-card h3{margin:0 0 12px;font-size:13px;text-transform:uppercase;letter-spacing:.06em}.emergency-card dl{display:grid;grid-template-columns:130px 1fr;gap:7px;margin:0}.emergency-card dt{color:var(--ink-500)}.emergency-card dd{margin:0;font-weight:600}.critical-section{border-color:var(--danger-soft)!important;background:var(--danger-soft)}.medication-chips{display:flex;gap:8px;flex-wrap:wrap}.medication-chips span{padding:6px 10px;border-radius:999px;background:var(--care-50)}.emergency-card footer{margin-top:15px;font-size:11px;color:var(--ink-500)}.import-form{max-width:900px}.import-form :deep(textarea){font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;line-height:1.55}.import-preview-table{margin-top:18px}.workbench-tabs :deep(.el-tabs__header){position:sticky;top:76px;z-index:4;background:var(--paper);padding-top:8px}
@media(max-width:900px){.metric-grid,.quality-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.schedule-form{grid-template-columns:repeat(2,minmax(0,1fr))}.finding-grid.compact{grid-template-columns:1fr}.issue-row{grid-template-columns:4px 1fr}.issue-row>.row-actions,.issue-row>.button-cluster,.issue-row>.el-button{grid-column:2;justify-self:start}.row-actions{max-width:none;justify-content:flex-start;flex-wrap:wrap}.row-actions span{text-align:left}.emergency-grid{grid-template-columns:1fr}}
@media(max-width:600px){.clinical-workbench{padding:20px 14px 32px}.workbench-hero,.section-heading{flex-direction:column}.metric-grid,.quality-metrics,.finding-grid,.schedule-form{grid-template-columns:1fr}.workbench-panel{padding:2px 14px 18px}.issue-row{padding:13px 10px}.button-cluster{width:100%}.button-cluster :deep(.el-button){flex:1;margin:0;min-width:min(140px,100%)}.emergency-card{padding:16px}.emergency-card>header{flex-direction:column}.emergency-card dl{grid-template-columns:1fr}.emergency-card dd{margin-bottom:6px}.workbench-tabs :deep(.el-tabs__header){top:64px}.workbench-tabs :deep(.el-tabs__nav-wrap){overflow-x:auto}.workbench-tabs :deep(.el-tabs__nav){float:none}.workbench-tabs :deep(.el-tabs__item){padding:0 12px;white-space:nowrap}}
@media print{.clinical-workbench>*:not(.workbench-panel),.workbench-tabs :deep(.el-tabs__header),.workbench-tabs :deep(.el-tab-pane:not([aria-hidden="false"])),.no-print{display:none!important}.clinical-workbench,.workbench-panel{padding:0;border:0}.emergency-card{border:1px solid #222;box-shadow:none}}
</style>
