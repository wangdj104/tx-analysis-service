<template>
  <el-dialog :model-value="true" title="View and review AI analysis" width="min(900px,96vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @close="close">
    <section class="ai-review" aria-label="Original AI analysis review">
      <p v-if="loading" role="status">Loading the authorized original analysis…</p>
      <div v-if="error" role="alert"><p>{{ error }}</p><button type="button" :disabled="loading || saving" @click="load">Reload original analysis</button></div>
      <template v-if="preview">
        <h2>{{ item.patientName || 'Patient name unavailable' }} · Record #{{ preview.patientId }}</h2>
        <p>AI-assisted analysis · Source record #{{ preview.id }} · {{ preview.periodLabel || preview.timeValue || 'Period unavailable' }}</p>
        <p>Model output and confidence are not a guarantee of clinical reliability. Check the original content and available evidence before deciding.</p>
        <h3>Full original analysis</h3><pre data-testid="ai-original-analysis">{{ preview.analysisContent }}</pre>
        <h3>Original evidence, recommendations and provenance</h3>
        <p>These are the fields saved with this analysis. Raw source measurements and model details are not supplied by this history endpoint.</p>
        <dl><template v-for="field in originalFields" :key="field.key"><dt>{{ field.label }}</dt><dd><pre>{{ field.value }}</pre></dd></template></dl>
        <label>Review note<textarea v-model="note" rows="3" maxlength="4000" :disabled="saving" /></label>
        <label class="acknowledgement"><input v-model="acknowledged" type="checkbox" :disabled="saving || needsReload" />I have checked this original analysis, patient and available evidence</label>
      </template>
      <div class="review-controls">
        <button v-if="preview" type="button" data-testid="approve-ai-review" :disabled="!canSubmit" @click="submit('APPROVED')">Approve analysis</button>
        <button v-if="preview" type="button" data-testid="reject-ai-review" :disabled="!canSubmit" @click="submit('REJECTED')">Reject analysis</button>
        <button type="button" :disabled="saving" @click="close">Close</button>
      </div>
    </section>
  </el-dialog>
</template>
<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import request from '@/utils/request'
import { AUTH_STORAGE_KEYS, captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'
const props = defineProps({ item: { type: Object, required: true } }), emit = defineEmits(['closed', 'reviewed'])
const preview = ref(null), note = ref(''), acknowledged = ref(false), loading = ref(false), saving = ref(false), error = ref(''), needsReload = ref(false)
let epoch = 0, controller = null, session = null
const currentSession = () => !!session && isAuthSessionCurrent(session) && session.actorId === localStorage.getItem('userId')
const canSubmit = computed(() => !!preview.value && acknowledged.value && !loading.value && !saving.value && !needsReload.value && preview.value.reviewStatus === 'REVIEW_REQUIRED')
const fieldLabels = { id:'Source record ID',patientId:'Patient record ID',userId:'Saved by account ID',timeType:'Period type',timeValue:'Period value',periodLabel:'Original period title',createdAt:'Created at',updatedAt:'Updated at',reviewStatus:'Review status',reviewedBy:'Reviewed by account ID',reviewedAt:'Reviewed at',remark:'Original notes',dwAdjustNeeded:'Dry-weight adjustment needed',dwAdjustAmount:'Suggested adjustment (kg)',dwTargetWeight:'Suggested target weight (kg)',dwAdjustReason:'Adjustment rationale',weightControlEval:'Weight control assessment',dehydrationEval:'Fluid removal assessment',bpControlEval:'Blood pressure control assessment',mainRisk:'Main risk',dietAdvice:'Diet advice',fluidAdvice:'Fluid intake advice',exerciseAdvice:'Exercise advice',medicationAdvice:'Medication advice',followUpAdvice:'Follow-up advice',totalCount:'Source record count',avgOnWeight:'Average pre-dialysis weight (kg)',avgOffWeight:'Average post-dialysis weight (kg)',avgWeightGain:'Average weight gain (kg)',avgUfAmount:'Average ultrafiltration volume (kg)',dehydrationMatchRate:'Ultrafiltration target rate (%)',complicationRiskAssessment:'Complication risk assessment',medicationAdviceDetails:'Detailed medication advice',vitalSignTrendSummary:'Vital sign trend summary' }
const originalFields = computed(() => Object.entries(preview.value || {}).filter(([key]) => key !== 'analysisContent').map(([key,value]) => ({ key,label:fieldLabels[key] || key,value:value == null ? '—' : typeof value === 'object' ? JSON.stringify(value,null,2) : String(value) })))
function invalidate() { epoch++; controller?.abort(); preview.value=null; acknowledged.value=false; note.value=''; loading.value=false; saving.value=false; session=null }
const owns = (turn,id,patient) => turn === epoch && String(id) === String(props.item.sourceId) && String(patient) === String(props.item.patientId) && currentSession()
async function load() {
  invalidate(); error.value=''; needsReload.value=false
  const turn=epoch,id=Number(props.item.sourceId),patient=Number(props.item.patientId)
  session={...captureAuthSession(),actorId:localStorage.getItem('userId')}; controller=new AbortController()
  if(!currentSession() || props.item.sourceType !== 'AI_ANALYSIS' || !Number.isSafeInteger(id) || id<=0 || !Number.isSafeInteger(patient) || patient<=0)return
  loading.value=true
  try {
    const result=await request({url:'/ai/history',method:'get',params:{patientId:patient},expectedAuth:session,signal:controller.signal,executionReport:true})
    if(!owns(turn,id,patient))return
    const source=Array.isArray(result.data) ? result.data.find(r=>Number(r.id)===id && Number(r.patientId)===patient) : null
    if((result.code != null && result.code!==200) || !source || typeof source.analysisContent!=='string' || !source.analysisContent.trim())throw new Error('Original analysis unavailable')
    preview.value=source
    if(source.reviewStatus!=='REVIEW_REQUIRED'){needsReload.value=true;error.value='This analysis is no longer awaiting review. Refresh the queue.'}
  } catch { if(owns(turn,id,patient)){preview.value=null;error.value='The original analysis is missing, restricted or unavailable. Reload before reviewing.'} }
  finally { if(turn===epoch)loading.value=false }
}
async function submit(decision) {
  if(!currentSession()){invalidate();return}
  if(!canSubmit.value || !['APPROVED','REJECTED'].includes(decision))return
  const turn=epoch,id=Number(props.item.sourceId),patient=Number(props.item.patientId),auth=session
  saving.value=true
  try {
    const result=await request({url:'/doctor-workspace/reviews',method:'post',data:{sourceType:'AI_ANALYSIS',sourceId:id,decision,reviewNote:note.value},expectedAuth:auth,signal:controller.signal,executionReport:true})
    if(result.code!=null&&result.code!==200)throw Object.assign(new Error('Review rejected'),{code:result.code})
    if(owns(turn,id,patient)){acknowledged.value=false;needsReload.value=true;emit('reviewed',id)}
  } catch(failure) {
    if(!owns(turn,id,patient))return
    acknowledged.value=false;needsReload.value=true
    if([401,403,404].includes(Number(failure?.response?.status || failure?.code)))preview.value=null
    error.value='The review outcome could not be confirmed. Reload the original source and queue before deciding again.'
  } finally { if(turn===epoch)saving.value=false }
}
function close(){if(saving.value)return;invalidate();emit('closed')}
watch(()=>[props.item.sourceType,props.item.sourceId,props.item.patientId],load,{immediate:true,flush:'sync'})
function authStorageChanged(event) {
  if ((event.key == null || AUTH_STORAGE_KEYS.includes(event.key)) && session && !currentSession()) invalidate()
}
onMounted(()=>{window.addEventListener('auth-session-cleared',invalidate);window.addEventListener('storage',authStorageChanged)})
onUnmounted(()=>{invalidate();window.removeEventListener('auth-session-cleared',invalidate);window.removeEventListener('storage',authStorageChanged)})
</script>
<style scoped>
.ai-review{color:var(--ink-800);line-height:1.6}.ai-review h2,.ai-review h3{overflow-wrap:anywhere}.ai-review pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;margin:0;padding:12px;background:var(--surface-subtle);border-radius:8px}.ai-review dl{display:grid;grid-template-columns:minmax(140px,1fr) 3fr;gap:8px}.ai-review dt{font-weight:600;overflow-wrap:anywhere}.ai-review dd{margin:0;min-width:0}.ai-review label{display:grid;gap:6px;margin:16px 0}.ai-review textarea{box-sizing:border-box;width:100%;padding:10px;font:inherit;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:inherit}.ai-review .acknowledgement{display:flex;align-items:flex-start;gap:10px}.acknowledgement input{margin-top:6px;min-width:18px;min-height:18px}.review-controls{display:flex;flex-wrap:wrap;gap:10px}.ai-review button{min-height:44px;padding:10px 14px;font:inherit;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800);cursor:pointer}.ai-review button:disabled{opacity:.5;cursor:default}.ai-review button:focus-visible,.ai-review input:focus-visible,.ai-review textarea:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}@media(max-width:600px){.ai-review dl{grid-template-columns:1fr}.ai-review dd{margin-bottom:8px}.review-controls button{flex:1 1 45%}}
</style>
