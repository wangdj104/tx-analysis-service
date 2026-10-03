<template>
  <dialog ref="dialog" class="receipt-dialog" aria-labelledby="receipt-heading" @cancel.prevent="requestClose">
    <h2 id="receipt-heading">{{ commandLabel }}</h2>
    <template v-if="state.editor && visibleAction">
      <p>{{ visibleAction.instruction }}</p><p>Recording account: {{ actorName }} (#{{ actorId }}). Account identity does not verify patient identity.</p>
      <p v-if="command==='submitReceipt'">Assisted entry is the default, including when this account manages another family member.</p>
      <form @submit.prevent="submit">
        <label>Explanation ({{ command==='submitReceipt' ? '1–2000' : '1–1000' }} characters)<textarea v-model="form.note" rows="4" data-testid="receipt-note" :disabled="busy" /></label>
        <template v-if="command==='submitReceipt'">
          <label>Actual execution time, with explicit UTC offset<input v-model="form.occurredAt" type="text" placeholder="2026-10-03T14:30:00+08:00" :disabled="busy" /></label><p>{{ displayTime(form.occurredAt) }}</p>
          <label>Entry declaration<select v-model="form.entryMode" :disabled="busy"><option v-if="entryModes.includes('ASSISTED')" value="ASSISTED">Assisted entry</option><option v-if="entryModes.includes('SELF')" value="SELF">I explicitly declare account-owner self-entry</option></select></label>
          <p v-if="form.entryMode==='SELF'">This is your declaration, not patient identity verification.</p>
          <fieldset :disabled="busy"><legend>Existing record references only (maximum 5; no uploads)</legend><div v-for="(e,index) in form.evidence" :key="index" class="evidence-row"><select v-model="e.sourceType" aria-label="Reference type"><option value="MEASUREMENT">Measurement</option><option value="MEDICAL_RECORD">Medical record</option></select><input v-model.number="e.sourceId" type="number" min="1" step="1" aria-label="Existing record ID" /><button type="button" @click="form.evidence.splice(index,1)">Remove reference</button></div><button type="button" :disabled="form.evidence.length>=5" @click="form.evidence.push({sourceType:'MEASUREMENT',sourceId:null})">Add existing reference</button><p>The server verifies patient ownership and current permission. A reference does not grant access.</p></fieldset>
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
import {formatPlanTime} from '@/utils/carePlanTime'
import {captureAuthSession,isAuthSessionCurrent} from '@/utils/authSession'
const props=defineProps({action:{type:Object,required:true},planVersion:{type:Number,required:true},command:{type:String,default:'submitReceipt'}}),emit=defineEmits(['submitted','closed'])
const patientId=computed(()=>props.action?.patientId??null),client=useCarePlan(patientId),{state}=client,dialog=ref(null),message=ref(''),abandonIntent=ref(false),needsReload=ref(false),visibleAction=ref(null),actorName=ref(''),actorId=ref('')
const form=reactive({note:'',occurredAt:'',entryMode:'ASSISTED',evidence:[],kind:'CONTACTED'})
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
function clearForm(){Object.assign(form,{note:'',occurredAt:'',entryMode:'ASSISTED',evidence:[],kind:'CONTACTED'});visibleAction.value=null;actorName.value='';actorId.value='';abandonIntent.value=false;needsReload.value=false}
async function initialize(){generation++;client.reset();clearForm();message.value='';visibleAction.value=props.action;currentVersion=props.planVersion;actorId.value=localStorage.getItem('userId')||'';actorName.value=localStorage.getItem('realName')||localStorage.getItem('username')||'Current account';Object.assign(form,{occurredAt:new Date().toISOString()});await client.open(null,{draft:payload()})}
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
onMounted(async()=>{opener=document.activeElement;await nextTick();dialog.value?.showModal?.();window.addEventListener('beforeunload',beforeUnload);window.addEventListener('care-plan-before-context-change',contextChange)})
onUnmounted(()=>{generation++;window.removeEventListener('beforeunload',beforeUnload);window.removeEventListener('care-plan-before-context-change',contextChange);dialog.value?.close?.();opener?.focus?.()})
defineExpose({guardLeave,requestClose})
</script>
<style scoped>
.receipt-dialog{box-sizing:border-box;width:min(640px,94vw);max-height:90dvh;overflow:auto;border:1px solid var(--line);border-radius:14px;padding:20px;background:var(--paper);color:var(--ink-800)}.receipt-dialog::backdrop{background:#10282c88}h2,p{overflow-wrap:anywhere}p{line-height:1.6}label{display:grid;gap:6px;margin:14px 0}textarea,input,select{width:100%;min-width:0;box-sizing:border-box;min-height:44px;padding:10px;border:1px solid var(--line);border-radius:8px;font:inherit;background:var(--paper);color:inherit}button{min-height:44px;max-width:100%;padding:10px 14px;cursor:pointer;font:inherit;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800)}button:disabled{opacity:.5;cursor:default}button:focus-visible,textarea:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}.controls{display:flex;gap:10px;flex-wrap:wrap}.notice{padding:12px;margin:12px 0;border:1px solid var(--line);background:var(--care-50)}fieldset{min-width:0;margin:12px 0;border:1px solid var(--line)}.evidence-row{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:10px 0}.evidence-row button{grid-column:1/-1}@media(max-width:600px){.receipt-dialog{padding:14px}.controls button{width:100%}.evidence-row{grid-template-columns:1fr}}
</style>
