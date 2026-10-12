<template>
  <dialog ref="dialog" class="receipt-dialog" aria-labelledby="receipt-heading" @cancel.prevent="requestClose" @keydown="containFocus">
    <h2 id="receipt-heading">{{ commandLabel }}</h2>
    <template v-if="state.editor && visibleAction">
      <p class="receipt-patient">Patient: {{ patientName || 'Patient name unavailable' }} · Record #{{ patientId }}</p><p>{{ visibleAction.instruction }}</p><p>Recording account: {{ actorName }} (#{{ actorId }}). Account identity does not verify patient identity.</p>
      <p v-if="command==='submitReceipt'">Assisted entry is the default, including when this account manages another family member.</p>
      <form @submit.prevent="submit">
        <label>Explanation ({{ command==='submitReceipt' ? '1–2000' : '1–1000' }} characters)<textarea v-model="form.note" rows="4" data-testid="receipt-note" :disabled="busy" /></label>
        <template v-if="command==='submitReceipt'">
          <label>Actual execution date and time<input v-model="executionClock" type="datetime-local" step="0.001" data-testid="receipt-execution-time" :disabled="busy" /></label>
          <label>Timezone and UTC offset ({{ executionZone }})<select v-model.number="executionOffset" data-testid="receipt-time-offset" :disabled="busy || !executionOffsets.length"><option v-if="executionOffsets.length !== 1" :value="null">Select an explicit offset</option><option v-for="offset in executionOffsets" :key="offset" :value="offset">{{ offsetLabel(offset) }}</option></select></label>
          <p v-if="executionTimeError" role="alert">{{ executionTimeError }}</p><p v-else>{{ displayTime(form.occurredAt) }}</p>
          <label>Entry declaration<select v-model="form.entryMode" :disabled="busy"><option v-if="entryModes.includes('ASSISTED')" value="ASSISTED">Assisted entry</option><option v-if="entryModes.includes('SELF')" value="SELF">I explicitly declare account-owner self-entry</option></select></label>
          <p v-if="form.entryMode==='SELF'">This is your declaration, not patient identity verification.</p>
          <fieldset :disabled="busy"><legend>Existing record references only (maximum 5; no uploads)</legend>
            <label>Reference type<select v-model="referenceType" aria-label="Reference type"><option value="MEASUREMENT">Measurement</option><option value="MEDICAL_RECORD">Medical record</option></select></label>
            <button type="button" :disabled="referencesLoading" @click="loadReferences">{{ referencesLoading ? 'Loading records…' : 'Browse authorized records' }}</button>
            <p v-if="referencesError" role="alert">{{ referencesError }}</p><p v-else-if="referencesQueried && !referencesLoading && !referenceOptions.length">No accessible records of this type for this patient.</p>
            <label v-if="referenceOptions.length">Select an existing record<select v-model.number="selectedReferenceId" data-testid="receipt-existing-record"><option :value="null">Select an existing record</option><option v-for="record in referenceOptions" :key="record.id" :value="record.id" :disabled="form.evidence.some(e => e.sourceType === referenceType && e.sourceId === record.id)">{{ record.label }}</option></select></label>
            <button type="button" data-testid="add-existing-reference" :disabled="form.evidence.length>=5 || !selectedReferenceId || referencesLoading" @click="addReference">Add existing reference</button>
            <div v-for="(e,index) in form.evidence" :key="e.sourceType+':'+e.sourceId" class="evidence-row"><p>{{ referenceLabels[e.sourceType+':'+e.sourceId] || referenceFallback(e) }}</p><button type="button" @click="form.evidence.splice(index,1)">Remove reference</button></div>
            <p>The server verifies patient ownership and current permission. A reference does not grant access.</p>
          </fieldset>
        </template>
        <label v-if="command==='followUp'">Follow-up type<select v-model="form.kind" :disabled="busy"><option value="CONTACTED">Contacted</option><option value="AWAITING_INFORMATION">Waiting for more information</option><option value="DOCTOR_NOTIFIED">Doctor notified</option></select></label>
        <p v-if="!canSubmit" role="status">Read-only or unavailable action. Current permission is required.</p>
        <div class="controls"><button v-if="canSubmit" type="submit" data-testid="submit-receipt" :disabled="busy || unresolved || needsReload" @click.prevent="submit">Submit record</button><button type="button" data-testid="close-receipt" @click="requestClose">Close</button><button v-if="needsReload" type="button" data-testid="reload-receipt" :disabled="busy" @click="reloadCurrent">Reload server state</button></div>
      </form>
    </template>
    <p v-if="message" role="status">{{ message }}</p>
    <section v-if="unresolved" class="notice" role="alert"><p>The result is unknown. The original submission may already exist. Retry sends the original frozen command and original input, even if you edited this form.</p><button type="button" data-testid="retry-original" :disabled="busy" @click="retryOriginal">Retry original submission</button></section>
    <section v-if="abandonIntent" class="notice" role="alert"><p>Closing does not cancel a server submission. You may lose its retry key. Acknowledge and reload server state before a fresh command.</p><button type="button" data-testid="confirm-abandon" :disabled="busy" @click="confirmAbandon">Acknowledge and reload</button><button type="button" data-testid="keep-editing" @click="abandonIntent=false">Keep editing</button></section>
    <button v-if="!state.editor" type="button" data-testid="close-receipt" @click="emit('closed')">Close</button>
  </dialog>
</template>
<script setup>
import {computed,ref,reactive,watch,onMounted,onUnmounted,nextTick} from 'vue'
import {useCarePlan} from '@/composables/useCarePlan'
import {formatPlanTime,getLocalTimeOffsets,toOffsetIso} from '@/utils/carePlanTime'
import request from '@/utils/request'
import {captureAuthSession,isAuthSessionCurrent} from '@/utils/authSession'
const props=defineProps({action:{type:Object,required:true},planVersion:{type:Number,required:true},command:{type:String,default:'submitReceipt'}}),emit=defineEmits(['submitted','closed'])
const patientId=computed(()=>props.action?.patientId??null),client=useCarePlan(patientId),{state}=client,dialog=ref(null),message=ref(''),abandonIntent=ref(false),needsReload=ref(false),visibleAction=ref(null),actorName=ref(''),actorId=ref('')
const form=reactive({note:'',occurredAt:'',entryMode:'ASSISTED',evidence:[],kind:'CONTACTED'})
const executionClock=ref(''),executionOffset=ref(null),executionZone=Intl.DateTimeFormat().resolvedOptions().timeZone,executionTimeError=ref(''),patientName=ref('')
const referenceType=ref('MEASUREMENT'),referenceOptions=ref([]),referenceLabels=reactive({}),selectedReferenceId=ref(null),referencesLoading=ref(false),referencesError=ref(''),referencesQueried=ref(false)
let referenceEpoch=0,referenceController=null,identityController=null
const executionOffsets=computed(()=>{try{return getLocalTimeOffsets(executionClock.value,{timeZone:executionZone})}catch{return []}})
function offsetLabel(value){const absolute=Math.abs(value);return `UTC${value<0?'-':'+'}${String(Math.floor(absolute/60)).padStart(2,'0')}:${String(absolute%60).padStart(2,'0')}`}
watch(executionClock,()=>{const offsets=executionOffsets.value;executionOffset.value=offsets.length===1?offsets[0]:null},{flush:'sync'})
watch(()=>[executionClock.value,executionOffset.value],()=>{try{form.occurredAt=toOffsetIso(executionClock.value,executionOffset.value,{timeZone:executionZone});executionTimeError.value=''}catch{form.occurredAt='';executionTimeError.value='Choose a valid local time and explicit offset. Repeated daylight-saving times need an offset; skipped times cannot be used.'}},{flush:'sync'})
function setExecutionInstant(date){const pad=value=>String(value).padStart(2,'0');executionClock.value=`${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${String(date.getMilliseconds()).padStart(3,'0')}`;executionOffset.value=-date.getTimezoneOffset()}
function resetReferences(){referenceEpoch++;referenceController?.abort();referenceOptions.value=[];selectedReferenceId.value=null;referencesLoading.value=false;referencesQueried.value=false;referencesError.value=''}
watch(referenceType,resetReferences,{flush:'sync'})
function referenceFallback(e){return `${e.sourceType==='MEASUREMENT'?'Measurement':'Medical record'} #${e.sourceId}`}
function referenceLabel(record,type){
  const metricLabels={BP:'Blood pressure',GLUCOSE:'Blood glucose',SPO2:'Oxygen saturation',WEIGHT:'Weight',HEART_RATE:'Heart rate',TEMPERATURE:'Temperature',CUSTOM:'Measurement'}
  const recordLabels={BLOOD:'Blood test',URINE:'Urinalysis',LIVER:'Liver function',KIDNEY:'Kidney function',BONE:'Bone metabolism',IRON:'Iron metabolism',IMAGE:'Imaging report',OTHER:'Other'}
  const date=type==='MEASUREMENT'?record.measured_at||record.measuredAt:record.recordDate
  const title=type==='MEASUREMENT'?record.metric_name||record.metricName||metricLabels[record.metric_type||record.metricType]||record.metric_type||record.metricType:recordLabels[record.recordType]||record.recordType
  const value=type==='MEASUREMENT'?`${record.value_primary ?? record.valuePrimary ?? ''}${record.value_secondary != null || record.valueSecondary != null ? '/'+(record.value_secondary ?? record.valueSecondary) : ''} ${record.unit||''}`:record.hospitalName||record.remark||''
  return `${date||'Date unavailable'} · ${title||'Existing record'} · ${value} · #${record.id}`
}
async function loadPatientIdentity(){
  const c=capture(),id=patientId.value;identityController?.abort();identityController=new AbortController()
  patientName.value=props.action.patientName||''
  try{const result=await request({url:'/patient/names',method:'get',expectedAuth:{...c.auth,actorId:c.actor},signal:identityController.signal,executionReport:true});if(!owns(c))return;const patient=Array.isArray(result.data)?result.data.find(p=>String(p.id)===String(id)):null;if(patient)patientName.value=patient.patientName||patient.name||patientName.value}catch{}
}
async function loadReferences(){
  if(!state.editor||!visibleAction.value||referencesLoading.value)return
  resetReferences();const turn=referenceEpoch,c=capture(),id=patientId.value,type=referenceType.value
  referenceController=new AbortController();referencesLoading.value=true;referencesQueried.value=true
  try{
    const result=await request({url:type==='MEASUREMENT'?'/care-journey/measurements':'/medical-record/list',method:'get',params:{patientId:id},expectedAuth:{...c.auth,actorId:c.actor},signal:referenceController.signal,executionReport:true})
    if(turn!==referenceEpoch||!owns(c))return
    if(result.code!=null&&result.code!==200)throw Object.assign(new Error('Restricted source'),{code:result.code})
    referenceOptions.value=(Array.isArray(result.data)?result.data:[]).filter(record=>Number.isSafeInteger(Number(record.id))&&Number(record.id)>0&&String(record.patientId??record.patient_id)===String(id)).map(record=>({id:Number(record.id),label:referenceLabel(record,type)}))
  }catch(failure){
    if(turn!==referenceEpoch||!owns(c))return
    referenceOptions.value=[];referencesError.value='Existing records could not be loaded or access is restricted. Try browsing again.'
    if([401,403,404].includes(Number(failure?.response?.status||failure?.code))){form.evidence=[];Object.keys(referenceLabels).forEach(key=>delete referenceLabels[key])}
  }finally{if(turn===referenceEpoch&&owns(c))referencesLoading.value=false}
}
function addReference(){const id=selectedReferenceId.value,record=referenceOptions.value.find(r=>r.id===id),type=referenceType.value;if(!record||form.evidence.length>=5||form.evidence.some(e=>e.sourceType===type&&e.sourceId===id))return;form.evidence.push({sourceType:type,sourceId:id});referenceLabels[type+':'+id]=record.label;selectedReferenceId.value=null}

let generation=0,opener=null,currentVersion=null
const names={submitReceipt:'SUBMIT_RECEIPT',requestHelp:'REQUEST_HELP',followUp:'FOLLOW_UP'}
const commandLabel=computed(()=>({submitReceipt:'Record execution',requestHelp:'Ask the care team for help',followUp:'Record nursing follow-up'})[props.command]||'Unavailable action')
const busy=computed(()=>state.commandPhase==='pending'||state.opening),unresolved=computed(()=>state.commandPhase==='unknown')
const entryModes=computed(()=>visibleAction.value?.allowedEntryModes||[])
const canSubmit=computed(()=>!!state.editor&&visibleAction.value?.allowedActions?.includes(names[props.command])&&(props.command!=='submitReceipt'||entryModes.value.includes(form.entryMode)))
const payload=()=>props.command==='submitReceipt'?{note:form.note.trim(),occurredAt:form.occurredAt,entryMode:form.entryMode,evidence:JSON.parse(JSON.stringify(form.evidence))}:props.command==='followUp'?{note:form.note.trim(),kind:form.kind}:{note:form.note.trim()}
const owns=c=>c.generation===generation&&isAuthSessionCurrent(c.auth)&&c.actor===localStorage.getItem('userId')
const capture=()=>({generation,auth:captureAuthSession(),actor:localStorage.getItem('userId')})
function displayTime(value){try{return formatPlanTime(value,'en')}catch{return 'Enter a valid ISO time with an explicit offset.'}}
function clearForm(){identityController?.abort();resetReferences();patientName.value='';executionClock.value='';executionOffset.value=null;Object.keys(referenceLabels).forEach(key=>delete referenceLabels[key]);Object.assign(form,{note:'',occurredAt:'',entryMode:'ASSISTED',evidence:[],kind:'CONTACTED'});visibleAction.value=null;actorName.value='';actorId.value='';abandonIntent.value=false;needsReload.value=false}
async function initialize(){generation++;client.reset();clearForm();message.value='';visibleAction.value=props.action;currentVersion=props.planVersion;actorId.value=localStorage.getItem('userId')||'';actorName.value=localStorage.getItem('realName')||localStorage.getItem('username')||'Current account';setExecutionInstant(new Date());await client.open(null,{draft:payload()});if(state.editor)loadPatientIdentity()}
watch(()=>[props.action?.id,props.action?.patientId,props.planVersion,props.command],initialize,{immediate:true,flush:'sync'})
watch(form,()=>{if(state.editor)state.draft=payload()},{deep:true,flush:'sync'})
watch(()=>state.editor,editor=>{if(!editor){clearForm()}},{flush:'sync'})
function validate(){const count=Array.from(form.note.trim()).length,max=props.command==='submitReceipt'?2000:1000;if(count<1||count>max)return false;if(!Number.isSafeInteger(currentVersion)||currentVersion<0)return false;if(props.command==='followUp')return ['CONTACTED','AWAITING_INFORMATION','DOCTOR_NOTIFIED'].includes(form.kind);if(props.command!=='submitReceipt')return props.command==='requestHelp';try{formatPlanTime(form.occurredAt,'en')}catch{return false}const fraction=form.occurredAt.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/)?.[1]||'';if(BigInt(Date.parse(form.occurredAt))*1000n+BigInt(fraction.padEnd(6,'0').slice(3,6))>BigInt(Date.now())*1000n)return false;const refs=form.evidence;if(refs.length>5)return false;const keys=new Set();for(const e of refs){if(!['MEASUREMENT','MEDICAL_RECORD'].includes(e.sourceType)||!Number.isSafeInteger(e.sourceId)||e.sourceId<=0)return false;const key=e.sourceType+':'+e.sourceId;if(keys.has(key))return false;keys.add(key)}return entryModes.value.includes(form.entryMode)}
function feedback(result){message.value=({unknown:'The server result is unknown. Retry the original submission or acknowledge a server reload.',conflict:'The plan changed. Your input is kept. Reload before submitting again.',forbidden:'Access has been revoked. Clinical content was cleared.',unauthenticated:'Sign in again to continue.',unavailable:'This action is unavailable.',cancelled:'The request was not sent.',stale:'Context or input changed. Reload server state before another command.',failed:'The server rejected this input. Correct it and try again.'})[result.status]||'Unable to submit this record.';if(['conflict','stale'].includes(result.status))needsReload.value=true}
async function submit(){if(!canSubmit.value||busy.value||unresolved.value||needsReload.value)return;if(!validate()){message.value='Check explanation length, entry declaration, explicit-offset past execution time, and up to 5 unique existing record references.';return}const c=capture();state.draft=payload();const result=await client.runCommand(props.command,{id:visibleAction.value.id,expectedVersion:currentVersion,payload:state.draft});if(owns(c))await settle(result)}
async function settle(result){if(result.status!=='succeeded'){feedback(result);return}if(result.draftUnchanged===false){message.value='The original submission succeeded. Your newer input is kept.';needsReload.value=true;await reloadCurrent();return}message.value='Record submitted. Execution receipts await doctor review.';emit('submitted',result.data);emit('closed')}
async function retryOriginal(){if(!unresolved.value||busy.value)return;const c=capture(),result=await client.runCommand(props.command,{retry:true});if(owns(c))await settle(result)}
async function reloadCurrent(){const c=capture(),input=payload(),id=visibleAction.value?.planId??props.action.planId;needsReload.value=true;if(!id){message.value='Reload the plan before creating another record.';return false}const result=await client.open(id,{draft:input});if(!owns(c))return false;if(result.status!=='succeeded'){feedback(result);return false}const action=state.plan?.actions?.find(a=>a.id===props.action.id);visibleAction.value=action||null;currentVersion=state.plan.version;needsReload.value=false;if(!action||!action.allowedActions?.includes(names[props.command]))message.value='Server state reloaded. This action is now read-only or unavailable.';else message.value='Server state reloaded. Your local input is kept.';return true}
function requestClose(){if(unresolved.value||busy.value){abandonIntent.value=true;return}emit('closed')}
async function confirmAbandon(){if(busy.value)return;abandonIntent.value=false;await reloadCurrent();emit('closed')}
function guardLeave(){if(!unresolved.value&&!busy.value)return true;return window.confirm('The submission may already exist. Leaving loses its retry key and does not cancel the server command. Reload server state before submitting again. Leave anyway?')}
function contextChange(event){if(!guardLeave())event.preventDefault()}
function beforeUnload(event){if(unresolved.value||busy.value){event.preventDefault();event.returnValue=''}}
function containFocus(event){
  if(event.key!=='Tab'||event.defaultPrevented||event.altKey||event.ctrlKey||event.metaKey)return
  const root=dialog.value
  if(!root?.open)return
  // Recompute after every state change: retry/leave controls and disabled fieldsets
  // change the available boundary while a command is pending or unresolved.
  const controls=Array.from(root.querySelectorAll('button,input,select,textarea,a[href],[tabindex]')).filter(node=>node.tabIndex>=0&&!node.matches(':disabled')&&!node.closest('[hidden],[inert]')&&node.getClientRects().length>0&&window.getComputedStyle(node).visibility==='visible')
  const first=controls[0],last=controls[controls.length-1],active=document.activeElement
  if(!controls.length){event.preventDefault();root.focus();return}
  if(!controls.includes(active)||(event.shiftKey?active===first:active===last)){
    event.preventDefault()
    ;(event.shiftKey?last:first).focus()
  }
}
onMounted(async()=>{opener=document.activeElement;await nextTick();dialog.value?.showModal?.();window.addEventListener('beforeunload',beforeUnload);window.addEventListener('care-plan-before-context-change',contextChange)})
onUnmounted(()=>{generation++;identityController?.abort();resetReferences();window.removeEventListener('beforeunload',beforeUnload);window.removeEventListener('care-plan-before-context-change',contextChange);dialog.value?.close?.();opener?.focus?.()})
defineExpose({guardLeave,requestClose})
</script>
<style scoped>
.receipt-dialog{box-sizing:border-box;width:min(640px,94vw);max-height:90dvh;overflow:auto;border:1px solid var(--line);border-radius:14px;padding:20px;background:var(--paper);color:var(--ink-800)}.receipt-dialog::backdrop{background:#10282c88}h2,p{overflow-wrap:anywhere}p{line-height:1.6}label{display:grid;gap:6px;margin:14px 0}textarea,input,select{width:100%;min-width:0;box-sizing:border-box;min-height:44px;padding:10px;border:1px solid var(--line);border-radius:8px;font:inherit;background:var(--paper);color:inherit}button{min-height:44px;max-width:100%;padding:10px 14px;cursor:pointer;font:inherit;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800)}button:disabled{opacity:.5;cursor:default}button:focus-visible,textarea:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}.controls{display:flex;gap:10px;flex-wrap:wrap}.notice{padding:12px;margin:12px 0;border:1px solid var(--line);background:var(--care-50)}fieldset{min-width:0;margin:12px 0;border:1px solid var(--line)}.evidence-row{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:10px 0}.evidence-row{align-items:center}.evidence-row p{margin:0;overflow-wrap:anywhere}@media(max-width:600px){.receipt-dialog{padding:14px}.controls button{width:100%}.evidence-row{grid-template-columns:1fr}}
</style>
