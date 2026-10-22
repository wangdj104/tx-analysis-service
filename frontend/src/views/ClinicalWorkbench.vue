<template>
  <main class="clinical-workbench" v-loading="loading">
    <header class="workbench-hero">
      <div>
        <span class="eyebrow">CLINICAL SAFETY & COORDINATION</span>
        <h1>Clinical Workbench</h1>
        <p>One place for actionable alerts, data verification, medication safety, dialysis quality, emergency handoff, and controlled imports.</p>
      </div>
      <el-button :disabled="!patientId" @click="load"><el-icon><Refresh /></el-icon>Refresh</el-button>
    </header>

    <el-alert v-if="!patientId" title="Select a family member in the top bar to open the workbench." type="warning" :closable="false" show-icon />
    <p v-else-if="overviewState !== 'ready'" role="status" class="workbench-state">{{ overviewState === 'error' ? 'Workbench information is unavailable. Refresh to try again.' : loading ? 'Loading workbench…' : 'Refresh to load workbench information.' }}</p>
    <template v-else>
      <section class="metric-grid" aria-label="Workbench summary">
        <article><span>Needs attention</span><strong>{{ countValue(overview.attention?.total) }}</strong><small>{{ countValue(overview.attention?.critical) }} critical</small></article>
        <article><span>Data issues</span><strong>{{ countValue(overview.dataQuality?.total) }}</strong><small>Known pending review entries: {{ countValue(overview.dataQuality?.pendingReview) }}</small></article>
        <article><span>Medication findings</span><strong>{{ overview.medicationSafety?.findings?.length || 0 }}</strong><small>{{ adherenceText }}</small></article>
        <article><span>Dialysis sessions</span><strong>{{ countValue(overview.dialysisQuality?.sessionCount) }}</strong><small>Last {{ overview.dialysisQuality?.rangeDays || 90 }} days</small></article>
      </section>

      <AutomationRunStatus :patient-id="patientId" :refresh-key="overview" @acknowledged="overviewAcknowledged" />

      <section class="workbench-panel">
        <el-tabs v-model="activeTab" class="workbench-tabs">
          <el-tab-pane label="Attention Center" name="attention">
            <div class="section-heading"><div><h2>Attention Center</h2><p>Prioritized by severity, with evidence, owner, service level, and a next action.</p></div></div>
            <p class="qualification-note">Recorded alert counts retain historical severity. Current medical-source qualification is shown only where supplied.</p>
            <div v-if="overview.attention?.items?.length" class="issue-list">
              <article v-for="(item, index) in overview.attention.items" :key="`${item.type}-${item.sourceId}-${index}`" class="issue-row">
                <div class="issue-marker" :class="`is-${(item.severity || 'info').toLowerCase()}`"></div>
                <div class="issue-copy"><div><el-tag size="small" :type="tagType(item.severity)">{{ item.severity }}</el-tag><b>{{ item.title }}</b></div><p>{{ item.evidence || 'Evidence available in the source record' }}</p><small><span v-if="item.dueAt">Due {{formatDate(item.dueAt)}}</span><span v-if="Number.isFinite(item.slaMinutes)"> · SLA {{formatSla(item.slaMinutes)}}</span><span v-if="item.ownerId"> · owner #{{ item.ownerId }}</span></small><section v-if="item.type === 'ALERT'" class="alert-source-facts"><b>Recorded alert</b><p v-for="(line, lineIndex) in alertSourceLines(item)" :key="lineIndex">{{ line }}</p></section></div>
                <div class="row-actions"><span>{{ item.recommendedAction }}</span><el-button type="primary" plain @click="openPath(item.path)">Open source</el-button></div>
              </article>
            </div>
            <el-empty v-else description="No active items need attention." />
          </el-tab-pane>

          <el-tab-pane label="Data Quality" name="quality">
            <div class="section-heading"><div><h2>Data Quality Center</h2><p>Source qualification, DOCTOR review, and AI draft review are separate facts. Pending counts are entries, not distinct records or medical clearance.</p></div></div>
            <p v-if="reviewInformationIncomplete" role="status" class="qualification-note">Current review information is incomplete</p>
            <div v-if="overview.dataQuality?.issues?.length" class="issue-list">
              <article v-for="(item, index) in overview.dataQuality.issues" :key="`${item.type}-${item.sourceId}-${index}`" class="issue-row">
                <div class="issue-marker" :class="`is-${(item.severity || 'info').toLowerCase()}`"></div>
                <div class="issue-copy"><div><el-tag size="small" :type="tagType(item.severity)">{{ qualityType(item.type) }}</el-tag><b>{{ item.title }}</b></div><p>{{ item.detail }}</p><small v-if="item.confidence != null">Confidence {{ Math.round(Number(item.confidence) * 100) }}%</small><section v-if="isReviewIssue(item)" class="review-qualification" data-review-quality>
                  <p>{{ reviewPresentation(item).sourceText }}</p><p v-if="reviewPresentation(item).doctorText">{{ reviewPresentation(item).doctorText }}</p>
                  <p v-if="reviewPresentation(item).sourceActorText">{{ reviewPresentation(item).sourceActorText }}</p><p v-if="reviewPresentation(item).doctorActorText">{{ reviewPresentation(item).doctorActorText }}</p>
                  <p :data-tone="reviewPresentation(item).automaticTone">{{ reviewPresentation(item).automaticText }}</p><p v-if="reviewPresentation(item).observationText">{{ reviewPresentation(item).observationText }}</p>
                  <p v-if="item.reviewChannel === 'LEGACY' && reviewPresentation(item).mode === 'UNAVAILABLE'">Review information is unavailable. You can view the source; legacy submission remains disabled unless a fresh preview establishes eligibility.</p>
                </section></div>
                <div v-if="item.type === 'REVIEW'" class="button-cluster"><template v-if="reviewPresentation(item).legacyEntry"><el-button type="success" plain @click="reviewRecord(item, true)">Verify</el-button><el-button type="danger" plain @click="reviewRecord(item, false)">Reject</el-button></template><el-button v-else plain @click="reviewRecord(item)">View source</el-button></div>
                <div v-else-if="item.type === 'AI_DRAFT'" class="button-cluster"><el-button type="success" plain @click="reviewDraft(item, true, false)">Approve</el-button><el-button type="primary" plain @click="reviewDraft(item, true, true)">Approve & notify</el-button><el-button type="danger" plain @click="reviewDraft(item, false, false)">Reject</el-button></div>
                <el-button v-else-if="item.type !== 'REVIEW_AVAILABILITY' && safeId(item.sourceId)" plain @click="openQualitySource(item)">Review source</el-button>
              </article>
            </div>
            <el-empty v-else description="No issues returned for this view" />
          </el-tab-pane>

          <el-tab-pane label="Medication Safety" name="medication">
            <div class="section-heading"><div><h2>Medication Safety</h2><p>Checks allergies, possible duplicate therapy, curated interactions, renal cautions, stock and adherence.</p></div><el-tag type="info">Decision support only</el-tag></div>
            <el-alert :title="overview.medicationSafety?.disclaimer" type="warning" :closable="false" show-icon />
            <div class="quality-metrics">
              <article><span>30-day adherence</span><strong>{{ adherenceText }}</strong></article>
              <article><span>Taken</span><strong>{{ overview.medicationSafety?.taken || 0 }}</strong></article>
              <article><span>Missed / skipped</span><strong>{{ overview.medicationSafety?.missedOrSkipped || 0 }}</strong></article>
              <article><span>Recorded allergies</span><strong class="small-value">{{ overview.medicationSafety?.allergies || 'None recorded' }}</strong></article>
            </div>
            <div v-if="overview.medicationSafety?.findings?.length" class="finding-grid">
              <article v-for="(finding, index) in overview.medicationSafety.findings" :key="index" class="finding-card" :class="`is-${finding.severity?.toLowerCase()}`"><div><el-tag :type="tagType(finding.severity)" size="small">{{ finding.type }}</el-tag><b>{{ finding.title }}</b></div><p>{{ finding.evidence }}</p><small>{{ finding.action }}</small></article>
            </div>
            <el-empty v-else description="No medication safety finding detected from the available data." />
          </el-tab-pane>

          <el-tab-pane label="Dialysis Quality" name="dialysis">
            <div class="section-heading"><div><h2>Dialysis Quality Cockpit</h2><p>IDWG, ultrafiltration rate, adequacy, vascular-access notes, and a confirmed schedule plan.</p></div><el-select v-model="dialysisDays" style="width:130px" @change="load"><el-option label="30 days" :value="30"/><el-option label="90 days" :value="90"/><el-option label="180 days" :value="180"/></el-select></div>
            <div class="quality-metrics">
              <article><span>Average IDWG</span><strong>{{ metric(overview.dialysisQuality?.avgIdwgPercent, '%') }}</strong></article>
              <article><span>Average UFR</span><strong>{{ metric(overview.dialysisQuality?.avgUfr, ' mL/kg/h') }}</strong></article>
              <article><span>Average Kt/V</span><strong>{{ metric(overview.dialysisQuality?.avgKtv) }}</strong></article>
              <article><span>Average URR</span><strong>{{ metric(overview.dialysisQuality?.avgUrr, '%') }}</strong></article>
            </div>
            <el-alert :title="overview.dialysisQuality?.disclaimer" type="info" :closable="false" show-icon />
            <div v-if="overview.dialysisQuality?.flags?.length" class="finding-grid compact">
              <article v-for="flag in overview.dialysisQuality.flags" :key="flag.metric" class="finding-card"><div><el-tag :type="tagType(flag.severity)" size="small">{{ flag.metric }}</el-tag><b>{{ flag.message }}</b></div></article>
            </div>
            <div class="schedule-builder">
              <div><h3>Recurring schedule generator</h3><p>Nothing is generated silently. Preview the dates, then confirm them.</p></div>
              <el-form label-position="top" class="schedule-form">
                <el-form-item label="Weekdays"><el-checkbox-group v-model="schedule.weekdays"><el-checkbox-button v-for="day in weekdayOptions" :key="day.value" :value="day.value">{{ day.label }}</el-checkbox-button></el-checkbox-group></el-form-item>
                <el-form-item label="Time"><el-time-picker v-model="schedule.time" value-format="HH:mm" format="HH:mm" /></el-form-item>
                <el-form-item label="From"><el-date-picker v-model="schedule.from" value-format="YYYY-MM-DD" /></el-form-item>
                <el-form-item label="To"><el-date-picker v-model="schedule.to" value-format="YYYY-MM-DD" /></el-form-item>
              </el-form>
              <div class="button-cluster"><el-button @click="previewSchedule">Preview dates</el-button><el-button type="primary" :disabled="!schedulePreview.length" @click="confirmSchedule">Confirm & create</el-button></div>
              <div v-if="schedulePreview.length" class="schedule-preview"><el-tag v-for="row in schedulePreview" :key="row.scheduleDate" type="info">{{ row.scheduleDate }} {{ row.scheduleTime || '' }}</el-tag></div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="Emergency Card" name="emergency">
            <div class="section-heading no-print"><div><h2>Emergency & Clinician Handoff Card</h2><p>A concise, printable snapshot. Recheck identity, allergies, and medication details before sharing.</p></div><el-button type="primary" @click="printCard"><el-icon><Printer /></el-icon>Print card</el-button></div>
            <article class="emergency-card" id="emergency-card">
              <header><div><span>{{ platformBranding.platformName.toUpperCase() }} · EMERGENCY SUMMARY</span><h2>{{ overview.emergencyCard?.patient?.name || currentPatientName }}</h2><p>Generated {{ formatDate(overview.emergencyCard?.generatedAt) }}</p></div><el-tag type="danger" effect="dark">Verify before use</el-tag></header>
              <div class="emergency-grid">
                <section><h3>Identity & contact</h3><dl><dt>Date of birth</dt><dd>{{ overview.emergencyCard?.patient?.birthDate || '—' }}</dd><dt>Phone</dt><dd>{{ overview.emergencyCard?.patient?.phone || '—' }}</dd><dt>Emergency contact</dt><dd>{{ emergencyContact }}</dd></dl></section>
                <section><h3>Renal care</h3><dl><dt>Dialysis type</dt><dd>{{ overview.emergencyCard?.clinical?.dialysisType || '—' }}</dd><dt>Vascular access</dt><dd>{{ overview.emergencyCard?.clinical?.vascularAccess || '—' }}</dd><dt>Target dry weight</dt><dd>{{ metric(overview.emergencyCard?.clinical?.targetDryWeight, ' kg') }}</dd><dt>Fluid limit</dt><dd>{{ overview.emergencyCard?.clinical?.fluidLimitMl ? `${overview.emergencyCard.clinical.fluidLimitMl} mL/day` : '—' }}</dd></dl></section>
                <section class="critical-section"><h3>Allergies & diagnoses</h3><dl><dt>Drug allergies</dt><dd>{{ overview.emergencyCard?.clinical?.allergyDrugs || 'None recorded' }}</dd><dt>Primary diagnosis</dt><dd>{{ overview.emergencyCard?.clinical?.primaryDiagnosis || overview.emergencyCard?.patient?.medicalHistory || '—' }}</dd></dl></section>
                <section><h3>Care team</h3><dl><dt>Hospital</dt><dd>{{ overview.emergencyCard?.target?.hospitalName || '—' }}</dd><dt>Clinician</dt><dd>{{ overview.emergencyCard?.target?.doctorName || '—' }}</dd></dl></section>
              </div>
              <section><h3>Active medications</h3><div class="medication-chips"><span v-for="med in overview.emergencyCard?.medications || []" :key="med.id">{{ med.drugName }} {{ med.defaultDosage || '' }}</span><i v-if="!overview.emergencyCard?.medications?.length">None recorded</i></div></section>
              <section><h3>Unresolved alerts</h3><ul><li v-for="(alert, index) in overview.emergencyCard?.unresolvedAlerts || []" :key="`${alert.id}-${index}`">{{ alert.alertLevel }} · {{ alert.alertTitle }} · {{ alert.triggeredValue }}<div class="alert-source-facts"><b>Recorded alert</b><p v-for="(line, lineIndex) in alertSourceLines(alert, true)" :key="lineIndex">{{ line }}</p></div></li><li v-if="!overview.emergencyCard?.unresolvedAlerts?.length">No unresolved alert in the platform.</li></ul></section>
              <footer>{{ overview.emergencyCard?.disclaimer }}</footer>
            </article>
          </el-tab-pane>

          <el-tab-pane label="FHIR / Device Import" name="import">
            <div class="section-heading"><div><h2>Controlled Clinical Import</h2><p>FHIR Observations and device readings are parsed into a preview. Committing always requires explicit confirmation, and imported values remain “review required”.</p></div></div>
            <el-form label-position="top" class="import-form">
              <el-form-item label="Source"><el-radio-group v-model="importMode" @change="loadImportSample"><el-radio-button value="FHIR">FHIR Bundle</el-radio-button><el-radio-button value="DEVICE">Device readings</el-radio-button></el-radio-group></el-form-item>
              <el-form-item label="JSON payload"><el-input v-model="importText" type="textarea" :rows="13" spellcheck="false" /></el-form-item>
              <div class="button-cluster"><el-button :loading="importBusy" :disabled="importBusy || submitting" @click="previewImport">Validate & preview</el-button><el-button type="primary" :disabled="importBusy || !importPreview?.itemCount || !lastImportContext" @click="commitImport">Confirm import</el-button></div>
            </el-form>
            <p class="import-notice">Cancellation does not undo an import. If a submitted import is interrupted, check records before retrying; records without stable source IDs may be duplicated.</p>
            <p v-if="importPreview" class="import-counts" role="status">Valid: {{ importPreview.itemCount || 0 }}; rejected: {{ importPreview.rejectedCount || 0 }}. Only valid items will be imported. Fix rejected rows and preview again.</p>
            <el-alert v-for="(warning, index) in importWarnings" :key="index" :title="warning" type="warning" :closable="false" show-icon />
            <article v-for="(row, index) in importRejectedRows" :key="index" class="import-rejected-row" data-testid="import-rejected-row">
              <p><strong>{{ importFactsText('rejectedRow') }} {{ row.sourceType }} #{{ row.rowIndex }}</strong> · {{ row.message }}</p>
              <ul><li v-for="(problem, fieldIndex) in (row.fields || []).slice(0, 8)" :key="fieldIndex">{{ importProblemText(problem) }}</li></ul>
            </article>
            <el-alert v-if="importError" :title="importError" type="error" :closable="false" show-icon />
            <el-alert v-if="importResult" :title="`Created ${importResult.created || 0}; skipped ${importResult.skipped || 0}; errors ${importResult.errors || 0}.`" :type="importResult.errors > 0 ? 'warning' : 'success'" :closable="false" show-icon />
            <el-alert v-for="(message, index) in importResult?.messages || []" :key="index" :title="message" type="warning" :closable="false" show-icon />
            <el-table v-if="importPreview?.items?.length" :data="importPreview.items" class="import-preview-table">
              <el-table-column prop="kind" label="Type" width="90"/><el-table-column prop="date" label="Date" width="120"/><el-table-column prop="display" label="Observation" min-width="160"><template #default="{row}">{{ row.display || row.measureType }}</template></el-table-column><el-table-column label="Value" min-width="160"><template #default="{row}">{{ importValue(row) }}</template></el-table-column><el-table-column prop="externalId" label="Source ID" min-width="160" show-overflow-tooltip/>
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
const weekdayOptions = [{value:1,label:'Mon'},{value:2,label:'Tue'},{value:3,label:'Wed'},{value:4,label:'Thu'},{value:5,label:'Fri'},{value:6,label:'Sat'},{value:7,label:'Sun'}]
const futureDate = days => { const date = new Date(); date.setDate(date.getDate() + days); return [date.getFullYear(), String(date.getMonth()+1).padStart(2,'0'), String(date.getDate()).padStart(2,'0')].join('-') }
const schedule = reactive({ weekdays: [], time: '', from: futureDate(0), to: futureDate(60) })
const fhirSample = { resourceType:'Bundle', type:'collection', entry:[{ resource:{ resourceType:'Observation', id:'bp-example-1', status:'final', code:{coding:[{system:'http://loinc.org',code:'85354-9',display:'Blood pressure panel'}]}, effectiveDateTime:new Date().toISOString(), component:[{code:{coding:[{code:'8480-6'}]},valueQuantity:{value:128,unit:'mmHg'}},{code:{coding:[{code:'8462-4'}]},valueQuantity:{value:78,unit:'mmHg'}}] } }] }
const deviceSample = [{ sourceExternalId:'home-device-example-1', observedAt:new Date().toISOString(), systolic:128, diastolic:78, glucose:5.6, unit:'mmol/L' }]

const adherenceText = computed(() => overview.value.medicationSafety?.adherenceRate == null ? 'No completed tasks' : `${overview.value.medicationSafety.adherenceRate}%`)
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
  const value=medicalReviewPresentation(item.reviewSummary, 'en')
  const summary=item.reviewSummary, known=['NONE','PARTICIPANT','UNAVAILABLE'].includes(value.mode)
  const consistent=item.type==='REVIEW' ? item.reviewChannel==='LEGACY' && ['NONE','UNAVAILABLE'].includes(value.mode) : item.type==='SOURCE_REVIEW' ? item.reviewChannel==='SOURCE' && value.mode==='PARTICIPANT' && value.automaticInputEligible===false : item.reviewChannel==='DOCTOR' && value.mode==='PARTICIPANT' && ['DOCTOR_ONLY','BOTH'].includes(value.participation) && (item.type==='DOCTOR_REVIEW' ? summary?.doctor?.state==='PENDING' : ['APPROVED','REJECTED'].includes(summary?.doctor?.state))
  if(known && (summary?.qualificationSemantics!=='CURRENT_CHECK' || !consistent))return {...value,sourceText:'Current review information does not match this workload view. Reload the source to check current eligibility.',doctorText:'',sourceActorText:'',doctorActorText:'',automaticText:'Automatic-analysis qualification could not be verified',automaticTone:'info',legacyEntry:false}
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
  if(!present)return medical?['Current source qualification unavailable']:[]
  if(!keys.every(key=>Object.hasOwn(item,key)))return ['Current source qualification unavailable']
  const {sourceStatus:status,sourceBindingStatus:binding,currentSourceStatus:current,sourceQualificationSemantics:semantics,sourceQualificationAt:at}=item
  if(status==='SOURCE_UNAVAILABLE' && binding===null && current==='SOURCE_UNAVAILABLE' && semantics===null && at===null)return ['Current source qualification unavailable']
  const currentCopy={'CURRENT': 'Current source qualification: current', 'UNTRACKED': 'Current source: legacy handling', 'SOURCE_REVIEW_REQUIRED': 'Current source awaiting verification', 'SOURCE_CHANGED': 'Current source changed', 'SOURCE_INVALIDATED': 'Current source invalidated', 'SOURCE_UNAVAILABLE': 'Current source qualification unavailable'}, bindingCopy={'CURRENT_CONTENT': 'Historical link matches current content', 'UNBOUND': 'Historical link has no version binding', 'CONTENT_CHANGED': 'Historical link refers to an earlier source version', 'GENERATION_CHANGED': 'Historical link refers to an earlier source version', 'MISSING_ANCHOR': 'Historical source link could not be verified', 'SOURCE_MISSING': 'Historical source link could not be verified', 'STORE_CONFLICT': 'Historical source link could not be verified'}
  if(!Object.hasOwn(currentCopy,current) || !Object.hasOwn(bindingCopy,binding) || !['CURRENT','UNTRACKED','SOURCE_REVIEW_REQUIRED','SOURCE_CHANGED','SOURCE_INVALIDATED','SOURCE_UNAVAILABLE','UNKNOWN_NEEDS_RECHECK'].includes(status) || semantics!=='CURRENT_CHECK' || !validSourceInstant(at))return ['Current source qualification unavailable']
  const coherent=binding==='MISSING_ANCHOR' || binding==='SOURCE_MISSING' ? status==='SOURCE_UNAVAILABLE' && current==='SOURCE_UNAVAILABLE' : binding==='STORE_CONFLICT' ? status==='SOURCE_UNAVAILABLE' : binding==='UNBOUND' ? status===(current==='CURRENT'?'UNKNOWN_NEEDS_RECHECK':current) : binding==='GENERATION_CHANGED' ? status==='SOURCE_CHANGED' : binding==='CONTENT_CHANGED' ? status==='SOURCE_CHANGED' || status===current && !['CURRENT','UNTRACKED'].includes(current) : status===(['CURRENT','UNTRACKED'].includes(current)?'CURRENT':current)
  if(!coherent)return ['Current source qualification unavailable']
  return [bindingCopy[binding],currentCopy[current],...(status==='UNKNOWN_NEEDS_RECHECK'?['Historical link needs rechecking']:[]),'Current source checked at '+at]
}
function hydrateSchedule(){
  const quality=overview.value.dialysisQuality||{}
  if(!schedule.weekdays.length && quality.scheduleWeekdays) schedule.weekdays=String(quality.scheduleWeekdays).split(',').map(Number)
  if(!schedule.time && quality.scheduleTime) schedule.time=quality.scheduleTime
}
function tagType(severity){ return ({CRITICAL:'danger',WARNING:'warning',INFO:'info'}[severity]||'info') }
function qualityType(type){ return ({REVIEW:'Legacy review',SOURCE_REVIEW:'SOURCE review',DOCTOR_REVIEW:'DOCTOR review pending',DOCTOR_REVIEW_OUTCOME:'DOCTOR review outcome',REVIEW_AVAILABILITY:'Review information unavailable',LOW_CONFIDENCE:'Low confidence',MISSING_CONTEXT:'Missing context',DUPLICATE:'Possible duplicate',DIALYSIS_COMPLETENESS:'Incomplete dialysis data',AI_DRAFT:'AI draft'}[type]||type) }
function formatDate(value){ if(!value)return 'not set'; const date=new Date(value); return Number.isNaN(date.getTime())?String(value):date.toLocaleString('en-US') }
function formatSla(minutes){ return minutes<60?`${minutes} min`:minutes<1440?`${minutes/60} h`:`${minutes/1440} d` }
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
  const verb=approved?(notify?'approve and send the reviewed text':'approve this draft'):'reject this draft'
  await ElMessageBox.confirm(`Confirm you want to ${verb}.`, 'AI analysis review', {type:notify?'warning':'info'})
  await reviewAnalysisDraft(item.sourceId,approved,notify); if(readOwner!==overviewOwner || !ownsOverview(readOwner))return; ElMessage.success(approved?'Draft approved.':'Draft rejected.'); await load()
}
function scheduleBody(confirmed=false){ return {patientId:patientId.value,weekdays:schedule.weekdays.join(','),time:schedule.time||null,from:schedule.from,to:schedule.to,confirmed} }
async function previewSchedule(){
  if(!patientId.value || submitting.value)return
  const body=scheduleBody(false), snapshot=JSON.stringify(body), epoch=++scheduleEpoch
  schedulePreview.value=[]; scheduleSnapshot=''
  const res=await generateDialysisSchedule(body)
  if(epoch!==scheduleEpoch || snapshot!==JSON.stringify(scheduleBody(false)))return
  schedulePreview.value=res.data?.preview||[]; scheduleSnapshot=snapshot
  if(!schedulePreview.value.length)ElMessage.warning('No dates match the selected plan.')
}
async function confirmSchedule(){
  if(submitting.value || !schedulePreview.value.length || scheduleSnapshot!==JSON.stringify(scheduleBody(false)))return
  const snapshot=scheduleSnapshot, body={...scheduleBody(false),confirmed:true}, readOwner=overviewOwner
  submitting.value=true
  try {
  await ElMessageBox.confirm(`Create ${schedulePreview.value.length} schedule dates? Existing dates will be skipped.`, 'Confirm dialysis plan', {type:'warning'})
  if(snapshot!==scheduleSnapshot || snapshot!==JSON.stringify(scheduleBody(false)))return
const res=await generateDialysisSchedule(body); if(readOwner!==overviewOwner || !ownsOverview(readOwner))return; ElMessage.success(`Created ${res.data?.created||0}; skipped ${res.data?.skipped||0}.`); if(snapshot===scheduleSnapshot){schedulePreview.value=[]; scheduleSnapshot=''; await load()}
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
  importError.value = error?.message === 'IMPORT_TOO_LARGE' ? 'The import request exceeds 1 MiB. Split it into smaller imports.' : 'Invalid JSON or outer structure. Provide a FHIR object, device array, or one readings array wrapper.'
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
    if (!res.data?.itemCount) ElMessage.warning('No valid item is available to import. Fix the input and preview again.')
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
    await ElMessageBox.confirm(`Import only ${valid} valid items as review-required data? ${rejected} rejected items will not be imported.`, 'Confirm clinical import', { type: 'warning' })
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
    const summary = `Created ${res.data?.created || 0}; skipped ${res.data?.skipped || 0}; errors ${res.data?.errors || 0}.`
    if (res.data?.errors > 0) ElMessage.warning(summary)
    else ElMessage.success(summary)
    if (ownsImport(attempt)) await load()
  } catch (error) {
    if (!ownsImport(attempt) || error === 'cancel' || error === 'close') return
    if (dispatched && (readEpoch !== loadEpoch || !ownsOverview(readContext))) return
    if (dispatched) importError.value = 'The import result is not confirmed. Some rows may have been saved. Check records before importing again; cancellation does not undo an import.'
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
