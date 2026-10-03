<template>
  <section v-if="enabled" class="plan-tasks" data-testid="collaboration-tasks" aria-labelledby="plan-task-heading">
    <header><h2 id="plan-task-heading">Doctor plan tasks</h2><button type="button" data-testid="reload-tasks" :disabled="state.loading" @click="reload">Refresh plan tasks</button></header>
    <p v-if="state.loading" role="status">Loading authorized plan tasks…</p><p v-if="message" role="status">{{ message }}</p><p v-if="!state.loading && !state.items.length">No authorized tasks in this queue.</p>
    <article v-for="plan in state.items" :key="plan.id" class="plan-card"><header><h3>{{plan.title}}</h3><router-link :to="`/care-plans/${plan.id}`" :data-testid="`open-plan-${plan.id}`">Open plan and version history</router-link></header><p>Published version {{plan.revisionNo}} · {{plan.instructions}}</p>
      <article v-for="action in plan.actions || []" :key="action.id" class="action-card"><h4>{{action.ordinal}}. {{action.instruction}}</h4><p>{{statusLabel(action.status)}}<strong v-if="action.overdue"> · Currently overdue</strong></p><p>Responsible account #{{action.assignedUserId}} · Deadline {{displayTime(action.dueAt)}}</p><p v-if="action.firstSubmittedAt">Original submission {{displayTime(action.firstSubmittedAt)}} · {{originalTiming(action)}}</p><p v-if="action.latestSubmittedAt">Latest submission {{displayTime(action.latestSubmittedAt)}}</p><p v-if="action.reviewWaitingSince">Waiting for doctor review since {{displayTime(action.reviewWaitingSince)}}. This review wait is not patient delay.</p>
        <ul><li v-for="(e,index) in action.evidence || []" :key="index"><span v-if="e.restricted">A restricted linked record exists</span><a v-else-if="safeLink(e.detailLink)" :href="e.detailLink">{{e.title || 'Existing record'}}</a><span v-else>{{e.title || 'Existing record'}} #{{e.sourceId}}</span></li></ul>
        <div v-for="event in action.events || []" :key="event.id" class="event"><p>{{eventLabel(event.eventType)}} · {{event.actorName}} · {{roleLabel(event.actorRole)}}<template v-if="event.entryMode"> · {{event.entryMode==='ASSISTED'?'Assisted entry':'Account-owner self-entry declaration'}}</template></p><p v-if="event.note">{{event.note}}</p><p>{{displayTime(event.recordedAt)}}</p></div>
        <div class="controls"><button v-if="action.allowedActions?.includes('SUBMIT_RECEIPT') && action.allowedEntryModes?.length" type="button" :data-testid="`record-${action.id}`" :ref="node=>setReceiptOpener(action.id,'submitReceipt',node)" @click="openReceipt(plan,action,'submitReceipt')">{{mode==='PATIENT'?'Record execution':'Record assisted execution'}}</button><button v-if="action.allowedActions?.includes('REQUEST_HELP')" type="button" :data-testid="`help-${action.id}`" :ref="node=>setReceiptOpener(action.id,'requestHelp',node)" @click="openReceipt(plan,action,'requestHelp')">Ask for help</button><button v-if="mode==='NURSE' && action.allowedActions?.includes('FOLLOW_UP')" type="button" :data-testid="`followup-${action.id}`" :ref="node=>setReceiptOpener(action.id,'followUp',node)" @click="openReceipt(plan,action,'followUp')">Record follow-up</button></div>
      </article>
    </article>
    <button v-if="state.nextCursor" type="button" data-testid="more-tasks" :disabled="state.loading" @click="loadMore">More plan tasks</button>
    <ReceiptDialog v-if="selected" ref="receipt" :action="selected.action" :plan-version="selected.version" :command="selected.command" @submitted="receiptSubmitted" @closed="receiptClosed" />
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
let generation=0,controller=null,timer=null,receiptEpoch=0
const receiptOpeners=new Map()
function setReceiptOpener(id,command,node){const key=id+':'+command;if(node)receiptOpeners.set(key,node);else receiptOpeners.delete(key)}
const capture=()=>({generation,auth:captureAuthSession(),actor:localStorage.getItem('userId')})
const owns=c=>c.generation===generation&&isAuthSessionCurrent(c.auth)&&c.actor===localStorage.getItem('userId')
async function initialize(){receiptEpoch++;generation++;controller?.abort();controller=new AbortController();enabled.value=false;message.value='';selected.value=null;client.reset();if(props.patientId==null&&props.mode!=='NURSE')return;const c=capture();try{const result=await getCarePlanCapabilities({expectedAuth:{...c.auth,actorId:c.actor},signal:controller.signal});if(!owns(c)||result.data?.enabled!==true)return;enabled.value=true;await reload()}catch{if(owns(c)){client.reset();message.value='Plan collaboration is unavailable in this session.'}}}
async function reload(){if(!enabled.value)return;const result=await client.load({queue:props.queue});if(['forbidden','unauthenticated','unavailable'].includes(result.status)){selected.value=null;message.value='Plan access is unavailable or has been revoked.'}else if(result.status==='failed')message.value='Unable to load plan tasks. Try refreshing.';else if(result.status==='succeeded')message.value=''}
async function loadMore(){if(state.nextCursor){const result=await client.load({queue:props.queue,cursor:state.nextCursor});if(['forbidden','unauthenticated','unavailable'].includes(result.status))selected.value=null}}
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
watch(()=>props.queue,()=>{receiptEpoch++;selected.value=null;reload()},{flush:'sync'})
watch(()=>state.patientId,id=>{if(id==null&&props.patientId!=null){selected.value=null;enabled.value=false}},{flush:'sync'})
function focusReload(){reload()}
onMounted(()=>{timer=setInterval(reload,60000);window.addEventListener('focus',focusReload)})
onUnmounted(()=>{receiptEpoch++;receiptOpeners.clear();generation++;controller?.abort();clearInterval(timer);window.removeEventListener('focus',focusReload)})
</script>
<style scoped>
.plan-tasks{min-width:0;margin:18px 0;color:var(--ink-800)}header,.controls{display:flex;flex-wrap:wrap;align-items:center;gap:10px;justify-content:space-between}.plan-card,.action-card{min-width:0;border:1px solid var(--line);border-radius:12px;padding:16px;margin:14px 0}.action-card{background:var(--surface-subtle)}h2,h3,h4,p,li{overflow-wrap:anywhere}p,li{line-height:1.6}.event{border-left:3px solid var(--care-600);padding-left:12px}.controls{justify-content:flex-start}button,a{min-height:44px;box-sizing:border-box;padding:10px 14px;font:inherit;color:var(--care-800);border:1px solid var(--care-600);border-radius:8px;background:var(--paper)}a{display:inline-flex;align-items:center}button{cursor:pointer}button:disabled{opacity:.5}button:focus-visible,a:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}@media(max-width:600px){.plan-card,.action-card{padding:12px}.controls button{width:100%}header>a{width:100%;justify-content:center}}
</style>
