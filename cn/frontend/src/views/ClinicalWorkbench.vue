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
    <template v-else>
      <section class="metric-grid" aria-label="工作台概览">
        <article><span>待关注事项</span><strong>{{ overview.attention?.total || 0 }}</strong><small>{{ overview.attention?.critical || 0 }} 项紧急</small></article>
        <article><span>数据问题</span><strong>{{ overview.dataQuality?.total || 0 }}</strong><small>{{ overview.dataQuality?.pendingReview || 0 }} 项待审核</small></article>
        <article><span>用药风险</span><strong>{{ overview.medicationSafety?.findings?.length || 0 }}</strong><small>{{ adherenceText }}</small></article>
        <article><span>透析次数</span><strong>{{ overview.dialysisQuality?.sessionCount || 0 }}</strong><small>近 {{ overview.dialysisQuality?.rangeDays || 90 }} 天</small></article>
      </section>

      <section class="workbench-panel">
        <el-tabs v-model="activeTab" class="workbench-tabs">
          <el-tab-pane label="关注中心" name="attention">
            <div class="section-heading"><div><h2>关注中心</h2><p>按严重程度排序，并展示依据、负责人、响应时限和建议操作。</p></div></div>
            <div v-if="overview.attention?.items?.length" class="issue-list">
              <article v-for="item in overview.attention.items" :key="`${item.type}-${item.sourceId}`" class="issue-row">
                <div class="issue-marker" :class="`is-${(item.severity || 'info').toLowerCase()}`"></div>
                <div class="issue-copy"><div><el-tag size="small" :type="tagType(item.severity)">{{ severityText(item.severity) }}</el-tag><b>{{ item.title }}</b></div><p>{{ item.evidence || '相关依据可在源记录中查看' }}</p><small>截止 {{ formatDate(item.dueAt) }} · 响应时限 {{ formatSla(item.slaMinutes) }}<span v-if="item.ownerId"> · 负责人 #{{ item.ownerId }}</span></small></div>
                <div class="row-actions"><span>{{ item.recommendedAction }}</span><el-button type="primary" plain @click="openPath(item.path)">查看来源</el-button></div>
              </article>
            </div>
            <el-empty v-else description="当前没有需要关注的事项。" />
          </el-tab-pane>

          <el-tab-pane label="数据质量" name="quality">
            <div class="section-heading"><div><h2>数据质量中心</h2><p>导入数据和 AI 生成数据在人工核验前均保持待确认状态。</p></div></div>
            <div v-if="overview.dataQuality?.issues?.length" class="issue-list">
              <article v-for="item in overview.dataQuality.issues" :key="`${item.type}-${item.sourceId}`" class="issue-row">
                <div class="issue-marker" :class="`is-${(item.severity || 'info').toLowerCase()}`"></div>
                <div class="issue-copy"><div><el-tag size="small" :type="tagType(item.severity)">{{ qualityType(item.type) }}</el-tag><b>{{ item.title }}</b></div><p>{{ item.detail }}</p><small v-if="item.confidence != null">置信度 {{ Math.round(Number(item.confidence) * 100) }}%</small></div>
                <div v-if="item.type === 'REVIEW'" class="button-cluster"><el-button type="success" plain @click="reviewRecord(item, true)">核验通过</el-button><el-button type="danger" plain @click="reviewRecord(item, false)">驳回</el-button></div>
                <div v-else-if="item.type === 'AI_DRAFT'" class="button-cluster"><el-button type="success" plain @click="reviewDraft(item, true, false)">批准</el-button><el-button type="primary" plain @click="reviewDraft(item, true, true)">批准并通知</el-button><el-button type="danger" plain @click="reviewDraft(item, false, false)">驳回</el-button></div>
                <el-button v-else plain @click="openQualitySource(item)">审核来源</el-button>
              </article>
            </div>
            <el-empty v-else description="未发现数据质量问题。" />
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
              <section><h3>未处理告警</h3><ul><li v-for="alert in overview.emergencyCard?.unresolvedAlerts || []" :key="alert.id">{{ severityText(alert.alertLevel) }} · {{ alert.alertTitle }} · {{ alert.triggeredValue }}</li><li v-if="!overview.emergencyCard?.unresolvedAlerts?.length">平台内没有未处理告警。</li></ul></section>
              <footer>{{ overview.emergencyCard?.disclaimer }}</footer>
            </article>
          </el-tab-pane>

          <el-tab-pane label="FHIR / 设备导入" name="import">
            <div class="section-heading"><div><h2>受控临床数据导入</h2><p>FHIR 观察结果和设备读数会先解析为预览，提交时必须明确确认，导入值将保持“待审核”状态。</p></div></div>
            <el-form label-position="top" class="import-form">
              <el-form-item label="数据来源"><el-radio-group v-model="importMode" @change="loadImportSample"><el-radio-button value="FHIR">FHIR 数据包</el-radio-button><el-radio-button value="DEVICE">设备读数</el-radio-button></el-radio-group></el-form-item>
              <el-form-item label="JSON 数据"><el-input v-model="importText" type="textarea" :rows="13" spellcheck="false" /></el-form-item>
              <div class="button-cluster"><el-button @click="previewImport">校验并预览</el-button><el-button type="primary" :disabled="!importPreview?.itemCount" @click="commitImport">确认导入</el-button></div>
            </el-form>
            <el-alert v-for="warning in importPreview?.warnings || []" :key="warning" :title="warning" type="warning" :closable="false" show-icon />
            <el-table v-if="importPreview?.items?.length" :data="importPreview.items" class="import-preview-table">
              <el-table-column prop="kind" label="类型" width="90"/><el-table-column prop="date" label="日期" width="120"/><el-table-column prop="display" label="观察指标" min-width="160"><template #default="{row}">{{ row.display || row.measureType }}</template></el-table-column><el-table-column label="数值" min-width="160"><template #default="{row}">{{ importValue(row) }}</template></el-table-column><el-table-column prop="externalId" label="来源 ID" min-width="160" show-overflow-tooltip/>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import { platformBranding } from '@/utils/platformBranding'
import { getClinicalWorkbenchOverview, getDialysisQuality, reviewMedicalRecord, reviewAnalysisDraft, generateDialysisSchedule, previewClinicalImport, commitClinicalImport } from '@/api/clinicalWorkbench'

const router = useRouter()
const { currentPatientId: patientId, currentPatientName } = useCurrentPatient()
const loading = ref(false), activeTab = ref('attention'), overview = ref({}), dialysisDays = ref(90)
const schedulePreview = ref([]), importMode = ref('FHIR'), importText = ref(''), importPreview = ref(null), lastImportBody = ref(null)
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

async function load(){
  const epoch=++loadEpoch, selectedPatient=patientId.value
  if(!selectedPatient){ overview.value={}; loading.value=false; return }
  loading.value=true
  try { const [res,dialysis]=await Promise.all([getClinicalWorkbenchOverview(selectedPatient),getDialysisQuality(selectedPatient,dialysisDays.value)]); if(epoch!==loadEpoch || selectedPatient!==patientId.value)return; overview.value=res.data||{}; overview.value.dialysisQuality=dialysis.data||{}; hydrateSchedule() }
  catch { if(epoch===loadEpoch)overview.value={} }
  finally { if(epoch===loadEpoch)loading.value=false }
}
function hydrateSchedule(){
  const quality=overview.value.dialysisQuality||{}
  if(!schedule.weekdays.length && quality.scheduleWeekdays) schedule.weekdays=String(quality.scheduleWeekdays).split(',').map(Number)
  if(!schedule.time && quality.scheduleTime) schedule.time=quality.scheduleTime
}
function tagType(severity){ return ({CRITICAL:'danger',WARNING:'warning',INFO:'info'}[severity]||'info') }
function severityText(severity){ return ({CRITICAL:'紧急',WARNING:'警告',INFO:'提示',HIGH:'高',MEDIUM:'中',LOW:'低'}[severity]||severity) }
function qualityType(type){ return ({REVIEW:'待审核',LOW_CONFIDENCE:'置信度较低',MISSING_CONTEXT:'缺少上下文',DUPLICATE:'可能重复',DIALYSIS_COMPLETENESS:'透析数据不完整',AI_DRAFT:'AI 草稿'}[type]||type) }
function findingType(type){ return ({ALLERGY:'过敏风险',DUPLICATE:'重复用药',INTERACTION:'药物相互作用',RENAL:'肾功能用药提示',STOCK:'库存不足',ADHERENCE:'依从性提示'}[type]||type) }
function formatDate(value){ if(!value)return '未设置'; const date=new Date(value); return Number.isNaN(date.getTime())?String(value):date.toLocaleString('zh-CN') }
function formatSla(minutes){ return minutes<60?`${minutes} 分钟`:minutes<1440?`${minutes/60} 小时`:`${minutes/1440} 天` }
function metric(value,suffix=''){ return value==null?'—':`${value}${suffix}` }
function openPath(path){ if(path)router.push(path) }
function openQualitySource(item){ router.push(item.sourceType==='dialysis'?'/dialysis':'/medical-record') }
async function reviewRecord(item,approved){
  const action=approved?'核验通过':'驳回'
  await ElMessageBox.confirm(`确认要将这条医疗记录${action}吗？`, '需要人工审核', {type:approved?'success':'warning'})
  await reviewMedicalRecord(item.sourceId,approved,`在临床工作台${action}`); ElMessage.success(`记录已${action}。`); await load()
}
async function reviewDraft(item,approved,notify){
  const verb=approved?(notify?'批准并发送审核后的内容':'批准此草稿'):'驳回此草稿'
  await ElMessageBox.confirm(`确认要${verb}吗？`, 'AI 分析审核', {type:notify?'warning':'info'})
  await reviewAnalysisDraft(item.sourceId,approved,notify); ElMessage.success(approved?'草稿已批准。':'草稿已驳回。'); await load()
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
  const snapshot=scheduleSnapshot, body={...scheduleBody(false),confirmed:true}
  submitting.value=true
  try {
  await ElMessageBox.confirm(`确认创建 ${schedulePreview.value.length} 个排班日期吗？已有日期将被跳过。`, '确认透析计划', {type:'warning'})
  if(snapshot!==scheduleSnapshot || snapshot!==JSON.stringify(scheduleBody(false)))return
const res=await generateDialysisSchedule(body); ElMessage.success(`已创建 ${res.data?.created||0} 条，跳过 ${res.data?.skipped||0} 条。`); if(snapshot===scheduleSnapshot){schedulePreview.value=[]; scheduleSnapshot=''; await load()}
  } catch(error) { if(error!=='cancel' && error!=='close')console.error(error) }
  finally { submitting.value=false }
}
function printCard(){ window.print() }
function loadImportSample(){ importPreview.value=null; lastImportBody.value=null; importText.value=JSON.stringify(importMode.value==='FHIR'?fhirSample:deviceSample,null,2) }
function importBody(){ const value=JSON.parse(importText.value); return importMode.value==='FHIR'?{patientId:patientId.value,bundle:value}:{patientId:patientId.value,readings:Array.isArray(value)?value:value.readings} }
async function previewImport(){
  if(!patientId.value || submitting.value)return
  const epoch=++importEpoch, selectedPatient=patientId.value
  importPreview.value=null; lastImportBody.value=null
  try{ const body=importBody(); const res=await previewClinicalImport(body); if(epoch!==importEpoch || selectedPatient!==patientId.value)return; importPreview.value=res.data; lastImportBody.value=body; if(!res.data?.itemCount)ElMessage.warning('未找到支持导入的数据项。') }catch(error){ if(error instanceof SyntaxError)ElMessage.error('JSON 数据格式无效。'); else console.error(error) }
}
async function commitImport(){
  if(submitting.value || !lastImportBody.value || !importPreview.value?.itemCount)return
  const body=lastImportBody.value, epoch=importEpoch
  submitting.value=true
  try {
  await ElMessageBox.confirm(`确认导入 ${importPreview.value.itemCount} 项数据，并将其标记为待审核吗？`, '确认临床数据导入', {type:'warning'})
  if(epoch!==importEpoch || body!==lastImportBody.value || body.patientId!==patientId.value)return
const res=await commitClinicalImport(body); ElMessage.success(`已创建 ${res.data?.created||0} 条，跳过 ${res.data?.skipped||0} 条，失败 ${res.data?.errors||0} 条。`); if(epoch===importEpoch){importPreview.value=null; lastImportBody.value=null; await load()}
  } catch(error) { if(error!=='cancel' && error!=='close')console.error(error) }
  finally { submitting.value=false }
}
function importValue(row){ if(row.measureType==='BP'||row.measureType==='BOTH')return `${row.systolic}/${row.diastolic} mmHg${row.glucose!=null?` · ${row.glucose} ${row.unit}`:''}`; if(row.measureType==='BG')return `${row.glucose} ${row.unit}`; return `${row.value??'—'} ${row.unit||''}` }

watch([patientId, importText, importMode],()=>{ ++importEpoch; importPreview.value=null; lastImportBody.value=null },{flush:'sync'})
watch(()=>JSON.stringify(scheduleBody()),()=>{ ++scheduleEpoch; schedulePreview.value=[]; scheduleSnapshot='' },{flush:'sync'})
watch(patientId,()=>{ overview.value={}; schedulePreview.value=[]; importPreview.value=null; lastImportBody.value=null; schedule.weekdays=[]; schedule.time=''; load() },{flush:'sync'})
onMounted(()=>{ loadImportSample(); load() })
</script>

<style scoped>
.clinical-workbench{max-width:1440px;margin:0 auto;padding:28px 32px 48px;color:#213c35}.workbench-hero,.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.workbench-hero{margin-bottom:20px}.workbench-hero h1{margin:6px 0;font-size:30px;letter-spacing:-.04em}.workbench-hero p,.section-heading p,.schedule-builder p{margin:0;color:#647b75;line-height:1.6}.eyebrow{font-size:11px;font-weight:700;letter-spacing:.15em;color:#2d786a}.metric-grid,.quality-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:18px}.metric-grid article,.quality-metrics article{padding:18px 20px;border:1px solid #dfe9e6;border-radius:14px;background:#fff}.metric-grid span,.quality-metrics span{display:block;color:#6a7f79;font-size:12px}.metric-grid strong,.quality-metrics strong{display:block;margin:8px 0 3px;font-size:28px}.metric-grid small{color:#81928e}.workbench-panel{padding:4px 22px 24px;border:1px solid #dfe9e6;border-radius:16px;background:#fff}.section-heading{margin:12px 0 18px}.section-heading h2{margin:0 0 5px;font-size:20px}.issue-list{display:grid;gap:10px}.issue-row{display:grid;grid-template-columns:4px minmax(0,1fr) minmax(180px,auto);gap:14px;align-items:center;padding:15px 16px;border:1px solid #e5ecea;border-radius:12px;background:#fbfcfc}.issue-marker{align-self:stretch;border-radius:3px;background:#8aa7a0}.issue-marker.is-critical{background:#c44d49}.issue-marker.is-warning{background:#d69a32}.issue-copy>div,.finding-card>div{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.issue-copy b,.finding-card b{font-size:14px}.issue-copy p,.finding-card p{margin:7px 0 4px;color:#506760}.issue-copy small,.finding-card small{color:#7d908b}.row-actions{display:flex;align-items:center;justify-content:flex-end;gap:12px;max-width:390px}.row-actions span{font-size:12px;color:#60736e;text-align:right}.button-cluster{display:flex;flex-wrap:wrap;gap:8px}.button-cluster :deep(.el-button + .el-button){margin-left:0}.quality-metrics{margin:18px 0}.quality-metrics .small-value{font-size:16px;line-height:1.3;overflow-wrap:anywhere}.finding-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:16px}.finding-grid.compact{grid-template-columns:repeat(3,minmax(0,1fr))}.finding-card{padding:15px;border:1px solid #e4ece9;border-radius:12px;background:#fbfcfc}.finding-card.is-critical{border-color:#efc6c2;background:#fff8f7}.finding-card.is-warning{border-color:#f0ddba;background:#fffaf1}.schedule-builder{margin-top:20px;padding:20px;border-radius:14px;background:#f5f9f7;border:1px solid #dce9e5}.schedule-builder h3{margin:0 0 5px}.schedule-form{display:grid;grid-template-columns:minmax(300px,2fr) repeat(3,minmax(130px,1fr));gap:12px;margin-top:18px}.schedule-form :deep(.el-form-item){margin-bottom:4px}.schedule-form :deep(.el-date-editor),.schedule-form :deep(.el-time-picker){width:100%}.schedule-preview{display:flex;flex-wrap:wrap;gap:7px;margin-top:14px;max-height:130px;overflow:auto}.emergency-card{padding:26px;border:2px solid #254f45;border-radius:16px;background:#fff}.emergency-card>header{display:flex;justify-content:space-between;gap:18px;padding-bottom:18px;border-bottom:1px solid #dce7e4}.emergency-card header span{font-size:10px;letter-spacing:.13em}.emergency-card h2{margin:5px 0}.emergency-card header p{margin:0;color:#60736e}.emergency-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin:16px 0}.emergency-card section{padding:15px;border:1px solid #dfe8e5;border-radius:10px}.emergency-card h3{margin:0 0 12px;font-size:13px;text-transform:uppercase;letter-spacing:.06em}.emergency-card dl{display:grid;grid-template-columns:130px 1fr;gap:7px;margin:0}.emergency-card dt{color:#6e817c}.emergency-card dd{margin:0;font-weight:600}.critical-section{border-color:#e9c2bd!important;background:#fff8f7}.medication-chips{display:flex;gap:8px;flex-wrap:wrap}.medication-chips span{padding:6px 10px;border-radius:999px;background:#edf5f2}.emergency-card footer{margin-top:15px;font-size:11px;color:#6a7f79}.import-form{max-width:900px}.import-form :deep(textarea){font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;line-height:1.55}.import-preview-table{margin-top:18px}.workbench-tabs :deep(.el-tabs__header){position:sticky;top:76px;z-index:4;background:#fff;padding-top:8px}
@media(max-width:900px){.metric-grid,.quality-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.schedule-form{grid-template-columns:repeat(2,minmax(0,1fr))}.finding-grid.compact{grid-template-columns:1fr}.issue-row{grid-template-columns:4px 1fr}.issue-row>.row-actions,.issue-row>.button-cluster,.issue-row>.el-button{grid-column:2;justify-self:start}.row-actions{max-width:none;justify-content:flex-start;flex-wrap:wrap}.row-actions span{text-align:left}.emergency-grid{grid-template-columns:1fr}}
@media(max-width:600px){.clinical-workbench{padding:20px 14px 32px}.workbench-hero,.section-heading{flex-direction:column}.metric-grid,.quality-metrics,.finding-grid,.schedule-form{grid-template-columns:1fr}.workbench-panel{padding:2px 14px 18px}.issue-row{padding:13px 10px}.button-cluster{width:100%}.button-cluster :deep(.el-button){flex:1;margin:0;min-width:min(140px,100%)}.emergency-card{padding:16px}.emergency-card>header{flex-direction:column}.emergency-card dl{grid-template-columns:1fr}.emergency-card dd{margin-bottom:6px}.workbench-tabs :deep(.el-tabs__header){top:64px}.workbench-tabs :deep(.el-tabs__nav-wrap){overflow-x:auto}.workbench-tabs :deep(.el-tabs__nav){float:none}.workbench-tabs :deep(.el-tabs__item){padding:0 12px;white-space:nowrap}}
@media print{.clinical-workbench>*:not(.workbench-panel),.workbench-tabs :deep(.el-tabs__header),.workbench-tabs :deep(.el-tab-pane:not([aria-hidden="false"])),.no-print{display:none!important}.clinical-workbench,.workbench-panel{padding:0;border:0}.emergency-card{border:1px solid #222;box-shadow:none}}
</style>
