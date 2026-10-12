<template>
  <section class="plan-tasks" data-testid="collaboration-tasks" aria-labelledby="plan-task-heading">
    <header><h2 id="plan-task-heading">Doctor plan tasks</h2><button v-if="enabled" type="button" data-testid="reload-tasks" :disabled="state.loading" @click="reload">Refresh plan tasks</button></header>
    <div v-if="!enabled" data-testid="plan-capability-state" :role="capabilityStatus === 'error' || capabilityStatus === 'forbidden' ? 'alert' : 'status'">
      <p>{{ capabilityLoading ? 'Checking plan access…' : message }}</p>
      <button v-if="!capabilityLoading && capabilityStatus !== 'no-patient'" type="button" data-testid="retry-plan-capability" @click="initialize">Retry access check</button>
    </div>
    <template v-if="enabled">
    <router-link v-if="patientId" :to="`/care-plans/reports?patientId=${patientId}`" data-testid="patient-execution-report">Visit preparation · all plans</router-link>

    <p v-if="state.loading" role="status">Loading authorized plan tasks…</p>
    <div v-if="taskError" role="alert"><p>{{ taskError }}</p><p v-if="taskLastSuccessAt" data-testid="plan-tasks-stale">Showing the last successful results. Data may be stale. Last successful read: {{ taskLastSuccessAt }}</p><button type="button" data-testid="retry-plan-tasks" :disabled="state.loading" @click="reload">Retry plan tasks</button></div>
    <p v-else-if="state.loading && taskLastSuccessAt" role="status">Refreshing; last successful read: {{ taskLastSuccessAt }}</p>
    <p v-if="queueLoaded && !state.loading && !taskError && !taskItems.length">No authorized tasks in this queue.</p>
    <article v-for="plan in state.loading ? [] : taskItems" :key="plan.id" class="plan-card"><header><h3>{{plan.title}}</h3><router-link :to="`/care-plans/${plan.id}`" :data-testid="`open-plan-${plan.id}`">Open plan and version history</router-link></header><p>Published version {{plan.revisionNo}} · {{plan.instructions}}</p>
      <router-link :to="`/care-plans/reports?patientId=${plan.patientId || patientId}&planId=${plan.id}`" :data-testid="`plan-execution-report-${plan.id}`">Execution report for this plan</router-link>
      <details v-for="action in orderedActions(plan)" :key="action.id" :open="!isHistoryAction(action)" class="action-group" :data-testid="`plan-action-${action.id}`"><summary><span v-if="isHistoryAction(action)">Reviewed and ended history · </span>{{ action.ordinal }}. {{ action.instruction }} · {{ statusLabel(action.status) }}</summary><article class="action-card"><h4>{{action.ordinal}}. {{action.instruction}}</h4><p>{{statusLabel(action.status)}}<strong v-if="action.overdue"> · Currently overdue</strong></p><p>Responsible account #{{action.assignedUserId}} · Deadline {{displayTime(action.dueAt)}}</p><p v-if="action.firstSubmittedAt">Original submission {{displayTime(action.firstSubmittedAt)}} · {{originalTiming(action)}}</p><p v-if="action.latestSubmittedAt">Latest submission {{displayTime(action.latestSubmittedAt)}}</p><p v-if="action.reviewWaitingSince">Waiting for doctor review since {{displayTime(action.reviewWaitingSince)}}. This review wait is not patient delay.</p>
        <ul><li v-for="(e,index) in action.evidence || []" :key="index"><span v-if="e.restricted">A restricted linked record exists</span><a v-else-if="safeLink(e.detailLink)" :href="e.detailLink">{{e.title || 'Existing record'}}</a><span v-else>{{e.title || 'Existing record'}} #{{e.sourceId}}</span></li></ul>
        <details v-if="action.events?.length" class="action-event-history"><summary>View activity history ({{ action.events.length }} events)</summary>
        <div v-for="event in action.events || []" :key="event.id" class="event"><p>{{eventLabel(event.eventType)}} · {{event.actorName}} · {{roleLabel(event.actorRole)}}<template v-if="event.entryMode"> · {{event.entryMode==='ASSISTED'?'Assisted entry':'Account-owner self-entry declaration'}}</template></p><p v-if="event.note">{{event.note}}</p><p>{{displayTime(event.recordedAt)}}</p></div>
        </details>
        <div class="controls"><button v-if="action.allowedActions?.includes('SUBMIT_RECEIPT') && action.allowedEntryModes?.length" type="button" :data-testid="`record-${action.id}`" :ref="node=>setReceiptOpener(action.id,'submitReceipt',node)" @click="openReceipt(plan,action,'submitReceipt')">{{mode==='PATIENT'?'Record execution':'Record assisted execution'}}</button><button v-if="action.allowedActions?.includes('REQUEST_HELP')" type="button" :data-testid="`help-${action.id}`" :ref="node=>setReceiptOpener(action.id,'requestHelp',node)" @click="openReceipt(plan,action,'requestHelp')">Ask for help</button><button v-if="mode==='NURSE' && action.allowedActions?.includes('FOLLOW_UP')" type="button" :data-testid="`followup-${action.id}`" :ref="node=>setReceiptOpener(action.id,'followUp',node)" @click="openReceipt(plan,action,'followUp')">Record follow-up</button></div>
      </article></details>
    </article>
    <button v-if="state.nextCursor && !taskError" type="button" data-testid="more-tasks" :disabled="state.loading" @click="loadMore">More plan tasks</button>
    <ReceiptDialog v-if="selected" ref="receipt" :action="selected.action" :plan-version="selected.version" :command="selected.command" @submitted="receiptSubmitted" @closed="receiptClosed" />
    </template>
  </section>
</template>
<script setup>
import {computed,ref,watch,onMounted,onUnmounted,nextTick} from 'vue'
import {onBeforeRouteLeave,onBeforeRouteUpdate} from 'vue-router'
import {useCarePlan} from '@/composables/useCarePlan'
import {getCarePlanCapabilities} from '@/api/carePlan'
import {captureAuthSession,isAuthSessionCurrent} from '@/utils/authSession'
import {formatPlanTime} from '@/utils/carePlanTime'
import ReceiptDialog from './ReceiptDialog.vue'
const props=defineProps({patientId:{type:Number,default:null},mode:{type:String,default:'PATIENT'},queue:{type:String,default:'TODAY'}})
const client=useCarePlan(computed(()=>props.patientId)),{state}=client,enabled=ref(false),message=ref(''),selected=ref(null),receipt=ref(null)
const capabilityLoading=ref(false),capabilityStatus=ref('idle'),taskItems=ref([]),taskLastSuccessAt=ref(''),taskError=ref(''),queueLoaded=ref(false)
let taskReadEpoch=0,sessionOwner=null,taskListContext=null
function clearTaskPresentation(){taskReadEpoch++;taskItems.value=[];taskLastSuccessAt.value='';taskError.value='';queueLoaded.value=false;taskListContext=null}
function isHistoryAction(action){return props.queue==='TODAY'&&['CONFIRMED','SUPERSEDED','CANCELLED'].includes(action.status)&&!action.allowedActions?.length}
function orderedActions(plan){const rank=action=>isHistoryAction(action)?2:action.status==='SUBMITTED'?1:0;return [...(plan.actions||[])].sort((a,b)=>rank(a)-rank(b))}
let generation=0,controller=null,timer=null,receiptEpoch=0
const receiptOpeners=new Map()
function setReceiptOpener(id,command,node){const key=id+':'+command;if(node)receiptOpeners.set(key,node);else receiptOpeners.delete(key)}
const capture=()=>({generation,auth:captureAuthSession(),actor:localStorage.getItem('userId')})
const owns=c=>c.generation===generation&&isAuthSessionCurrent(c.auth)&&c.actor===localStorage.getItem('userId')
async function initialize(){
  receiptEpoch++;generation++;controller?.abort();controller=new AbortController();enabled.value=false;message.value='';selected.value=null;clearTaskPresentation();client.reset();
  if(props.patientId==null&&props.mode!=='NURSE'){capabilityLoading.value=false;capabilityStatus.value='no-patient';message.value='Select a patient to view plan tasks.';return}
  const c=capture();sessionOwner=c;capabilityLoading.value=true;capabilityStatus.value='loading'
  try{
    const result=await getCarePlanCapabilities({expectedAuth:{...c.auth,actorId:c.actor},signal:controller.signal})
    if(!owns(c))return
    if(result.code!=null&&result.code!==200)throw Object.assign(new Error('Capability request failed'),{status:result.code})
    if(result.data?.enabled!==true){capabilityStatus.value='disabled';message.value='Plan collaboration is not enabled in this session.';return}
    capabilityStatus.value='enabled';enabled.value=true;await reload()
  }catch(error){
    if(!owns(c))return
    clearTaskPresentation();client.reset();enabled.value=false
    const status=error.response?.status||error.status||error.code
    capabilityStatus.value=status===403?'forbidden':status===401?'unauthenticated':'error'
    message.value=status===403?'You do not have access to plan collaboration.':status===401?'Sign in again to check plan access.':'Plan access could not be checked. Try again.'
  }finally{if(owns(c))capabilityLoading.value=false}
}
function revokeTaskAccess(status){
  enabled.value=false;selected.value=null;clearTaskPresentation();capabilityLoading.value=false;capabilityStatus.value=status
  message.value='Plan access is unavailable or has been revoked. Recheck access.'
}
async function loadTaskPage(cursor=null){
  if(!enabled.value)return
  if(sessionOwner&&!owns(sessionOwner)){revokeTaskAccess('unauthenticated');return}
  const c={...capture(),queue:props.queue,read:++taskReadEpoch}
  const result=await client.load({queue:c.queue,...(cursor?{cursor}: {})})
  if(!owns(c)||c.read!==taskReadEpoch||c.queue!==props.queue)return
  if(['forbidden','unauthenticated','unavailable'].includes(result.status)){revokeTaskAccess(result.status)}
  else if(result.status==='failed'){if(taskListContext!==state.listContext){clearTaskPresentation()}taskError.value='Plan tasks could not be loaded. Try again.'}
  else if(result.status==='succeeded'){taskListContext=state.listContext;taskItems.value=[...state.items];taskLastSuccessAt.value=new Date().toISOString();taskError.value='';message.value='';queueLoaded.value=true}
  else if(result.status==='stale-cursor'){clearTaskPresentation();taskError.value='The task range changed. Reload the queue.'}
}
async function reload(){return loadTaskPage()}
async function loadMore(){if(state.nextCursor)return loadTaskPage(state.nextCursor)}
function openReceipt(plan,action,command){if(selected.value||!action.allowedActions?.includes(({submitReceipt:'SUBMIT_RECEIPT',requestHelp:'REQUEST_HELP',followUp:'FOLLOW_UP'})[command]))return;selected.value={action,version:plan.version,command,focusContext:{...capture(),epoch:++receiptEpoch,patientId:props.patientId,queue:props.queue}}}
async function receiptClosed(){
  const selection=selected.value
  if(!selection)return
  const key=selection.action.id+':'+selection.command,original=receiptOpeners.get(key),context=selection.focusContext
  selected.value=null
  await reload()
  await nextTick()
  if(selected.value||receiptEpoch!==context.epoch||!owns(context)||props.patientId!==context.patientId||props.queue!==context.queue)return
  const target=receiptOpeners.get(key),active=document.activeElement
  if(target?.isConnected&&!target.matches(':disabled')&&!target.closest('[hidden],[inert]')&&target.getClientRects().length&&window.getComputedStyle(target).visibility==='visible'&&(!active||active===document.body||active===original))target.focus()
}
async function receiptSubmitted(){await receiptClosed()}
function displayTime(value){if(!value)return 'Time unavailable';try{return formatPlanTime(value,'en')}catch{return 'Invalid server time'}}
function instantMicros(value){const fraction=value.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/)?.[1]||'';return BigInt(Date.parse(value))*1000n+BigInt(fraction.padEnd(6,'0').slice(3,6))}
function originalTiming(action){try{return instantMicros(action.firstSubmittedAt)<=instantMicros(action.dueAt)?'Originally submitted by the deadline':'Originally submitted after the deadline'}catch{return 'Original submission timing unavailable'}}
function safeLink(link){return typeof link==='string'&&link.startsWith('/')&&!link.startsWith('//')&&!/[\u0000-\u0020\u007f\\]/.test(link)}
function statusLabel(status){return ({OPEN:'Awaiting execution or more information',NEEDS_HELP:'Help requested',SUBMITTED:'Submitted, awaiting doctor review',CONFIRMED:'Record reviewed',SUPERSEDED:'Replaced by a new version',CANCELLED:'Cancelled'})[status]||'Unavailable status'}
function roleLabel(role){return ({doctor:'Doctor',nurse:'Nurse',admin:'Administrator',patient:'Patient account',owner:'Record owner',family:'Family caregiver',guardian:'Guardian'})[String(role||'').toLowerCase()]||'Recording account'}
function eventLabel(type){return ({RECEIPT_SUBMITTED:'Execution record submitted',HELP_REQUESTED:'Help requested',FOLLOW_UP_RECORDED:'Care-team follow-up',RECEIPT_CONFIRMED:'Doctor reviewed the record',RECEIPT_RETURNED:'More information requested'})[type]||type}
const guard=()=>{const allowed=receipt.value?.guardLeave?.()??true;if(allowed)receiptEpoch++;return allowed}
onBeforeRouteLeave(guard);onBeforeRouteUpdate(guard)
watch(()=>[props.patientId,props.mode],initialize,{immediate:true,flush:'sync'})
watch(()=>props.queue,()=>{receiptEpoch++;selected.value=null;clearTaskPresentation();reload()},{flush:'sync'})
watch(()=>state.patientId,id=>{if(id==null&&props.patientId!=null&&enabled.value)revokeTaskAccess('unauthenticated')},{flush:'sync'})
function sessionChanged(){if(sessionOwner&&!owns(sessionOwner)){receiptEpoch++;generation++;controller?.abort();revokeTaskAccess('unauthenticated')}}
function focusReload(){reload()}
onMounted(()=>{timer=setInterval(reload,60000);window.addEventListener('focus',focusReload);window.addEventListener('auth-session-cleared',sessionChanged);window.addEventListener('storage',sessionChanged)})
onUnmounted(()=>{receiptEpoch++;receiptOpeners.clear();generation++;controller?.abort();clearInterval(timer);window.removeEventListener('focus',focusReload);window.removeEventListener('auth-session-cleared',sessionChanged);window.removeEventListener('storage',sessionChanged)})
</script>
<style scoped>
.action-group>summary,.action-event-history>summary{min-height:44px;line-height:1.6;cursor:pointer;overflow-wrap:anywhere;font-weight:600}.action-group>summary:focus-visible,.action-event-history>summary:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}.action-group{margin:14px 0;border:1px solid var(--line);border-radius:10px;padding:10px}.action-group .action-card{margin:8px 0}.plan-tasks{min-width:0;margin:18px 0;color:var(--ink-800)}header,.controls{display:flex;flex-wrap:wrap;align-items:center;gap:10px;justify-content:space-between}.plan-card,.action-card{min-width:0;border:1px solid var(--line);border-radius:12px;padding:16px;margin:14px 0}.action-card{background:var(--surface-subtle)}h2,h3,h4,p,li{overflow-wrap:anywhere}p,li{line-height:1.6}.event{border-left:3px solid var(--care-600);padding-left:12px}.controls{justify-content:flex-start}button,a{min-height:44px;box-sizing:border-box;padding:10px 14px;font:inherit;color:var(--care-800);border:1px solid var(--care-600);border-radius:8px;background:var(--paper)}a{display:inline-flex;align-items:center}button{cursor:pointer}button:disabled{opacity:.5}button:focus-visible,a:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}@media(max-width:600px){.plan-card,.action-card{padding:12px}.controls button{width:100%}header>a{width:100%;justify-content:center}}
</style>
