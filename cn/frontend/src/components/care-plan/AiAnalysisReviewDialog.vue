<template>
  <el-dialog :model-value="true" title="查看并复核 AI 分析" width="min(900px,96vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @close="close">
    <section class="ai-review" aria-label="原始 AI 分析复核">
      <p v-if="loading" role="status">正在加载已授权的原始分析…</p>
      <div v-if="error" role="alert"><p>{{ error }}</p><button type="button" :disabled="loading || saving" @click="load">重新加载原始分析</button></div>
      <template v-if="preview">
        <h2>{{ item.patientName || '患者姓名暂不可用' }} · 档案 #{{ preview.patientId }}</h2>
        <p>AI 辅助分析 · 来源记录 #{{ preview.id }} · {{ preview.periodLabel || preview.timeValue || '时段暂不可用' }}</p>
        <p>模型输出与置信度不保证临床可靠性，请查看原始内容和可用依据后再决定。</p>
        <h3>完整原始分析</h3><pre data-testid="ai-original-analysis">{{ preview.analysisContent }}</pre>
        <h3>原始依据、建议与来源</h3>
        <p>以下为分析保存时的原始字段。此历史接口未提供原始测量记录或模型详情。</p>
        <dl><template v-for="field in originalFields" :key="field.key"><dt>{{ field.label }}</dt><dd><pre>{{ field.value }}</pre></dd></template></dl>
        <label>复核说明<textarea v-model="note" rows="3" maxlength="4000" :disabled="saving" /></label>
        <label class="acknowledgement"><input v-model="acknowledged" type="checkbox" :disabled="saving || needsReload" />我已查看此原始分析、患者及可用依据</label>
      </template>
      <div class="review-controls">
        <button v-if="preview" type="button" data-testid="approve-ai-review" :disabled="!canSubmit" @click="submit('APPROVED')">通过分析</button>
        <button v-if="preview" type="button" data-testid="reject-ai-review" :disabled="!canSubmit" @click="submit('REJECTED')">驳回分析</button>
        <button type="button" :disabled="saving" @click="close">关闭</button>
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
const fieldLabels = { id:'来源记录编号',patientId:'患者档案编号',userId:'保存账号编号',timeType:'时段类型',timeValue:'时段值',periodLabel:'原始时段标题',createdAt:'创建时间',updatedAt:'更新时间',reviewStatus:'复核状态',reviewedBy:'复核账号编号',reviewedAt:'复核时间',remark:'原始备注',dwAdjustNeeded:'是否建议调整干体重',dwAdjustAmount:'建议调整量（kg）',dwTargetWeight:'建议目标体重（kg）',dwAdjustReason:'调整依据',weightControlEval:'体重控制评估',dehydrationEval:'脱水评估',bpControlEval:'血压控制评估',mainRisk:'主要风险',dietAdvice:'饮食建议',fluidAdvice:'液体摄入建议',exerciseAdvice:'运动建议',medicationAdvice:'用药建议',followUpAdvice:'随访建议',totalCount:'来源记录数量',avgOnWeight:'平均透析前体重（kg）',avgOffWeight:'平均透析后体重（kg）',avgWeightGain:'平均体重增长（kg）',avgUfAmount:'平均超滤量（kg）',dehydrationMatchRate:'超滤目标匹配率（%）',complicationRiskAssessment:'并发症风险评估',medicationAdviceDetails:'详细用药建议',vitalSignTrendSummary:'生命体征趋势摘要' }
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
    if((result.code != null && result.code!==200) || !source || typeof source.analysisContent!=='string' || !source.analysisContent.trim())throw new Error('原始分析不可用')
    preview.value=source
    if(source.reviewStatus!=='REVIEW_REQUIRED'){needsReload.value=true;error.value='此分析已不再等待复核，请刷新队列。'}
  } catch { if(owns(turn,id,patient)){preview.value=null;error.value='原始分析不存在、受限或暂不可用，请重新加载后复核。'} }
  finally { if(turn===epoch)loading.value=false }
}
async function submit(decision) {
  if(!currentSession()){invalidate();return}
  if(!canSubmit.value || !['APPROVED','REJECTED'].includes(decision))return
  const turn=epoch,id=Number(props.item.sourceId),patient=Number(props.item.patientId),auth=session
  saving.value=true
  try {
    const result=await request({url:'/doctor-workspace/reviews',method:'post',data:{sourceType:'AI_ANALYSIS',sourceId:id,decision,reviewNote:note.value},expectedAuth:auth,signal:controller.signal,executionReport:true})
    if(result.code!=null&&result.code!==200)throw Object.assign(new Error('复核被拒绝'),{code:result.code})
    if(owns(turn,id,patient)){acknowledged.value=false;needsReload.value=true;emit('reviewed',id)}
  } catch(failure) {
    if(!owns(turn,id,patient))return
    acknowledged.value=false;needsReload.value=true
    if([401,403,404].includes(Number(failure?.response?.status || failure?.code)))preview.value=null
    error.value='无法确认复核结果，请重新加载原始来源与队列后再作决定。'
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
