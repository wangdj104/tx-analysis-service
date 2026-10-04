<template>
  <section v-if="enabled" class="plan-tasks" data-testid="collaboration-tasks" aria-labelledby="plan-task-heading">
    <router-link v-if="patientId" :to="`/care-plans/reports?patientId=${patientId}`" data-testid="patient-execution-report">就诊准备 · 全部计划</router-link>
    <header><h2 id="plan-task-heading">医生计划事项</h2><button type="button" data-testid="reload-tasks" :disabled="state.loading" @click="reload">刷新计划事项</button></header>
    <p v-if="state.loading" role="status">正在加载已授权的计划事项…</p><p v-if="message" role="status">{{ message }}</p><p v-if="!state.loading && !state.items.length">当前队列没有已授权的事项。</p>
    <article v-for="plan in state.items" :key="plan.id" class="plan-card"><header><h3>{{plan.title}}</h3><router-link :to="`/care-plans/${plan.id}`" :data-testid="`open-plan-${plan.id}`">查看计划和版本历史</router-link></header><p>已发布版本 {{plan.revisionNo}} · {{plan.instructions}}</p>
      <router-link :to="`/care-plans/reports?patientId=${plan.patientId || patientId}&planId=${plan.id}`" :data-testid="`plan-execution-report-${plan.id}`">此计划执行报告</router-link>
      <article v-for="action in plan.actions || []" :key="action.id" class="action-card"><h4>{{action.ordinal}}. {{action.instruction}}</h4><p>{{statusLabel(action.status)}}<strong v-if="action.overdue"> · 当前逾期</strong></p><p>负责账号 #{{action.assignedUserId}} · 截止时间 {{displayTime(action.dueAt)}}</p><p v-if="action.firstSubmittedAt">原始提交 {{displayTime(action.firstSubmittedAt)}} · {{originalTiming(action)}}</p><p v-if="action.latestSubmittedAt">最近提交 {{displayTime(action.latestSubmittedAt)}}</p><p v-if="action.reviewWaitingSince">自此时间等待医生复核：{{displayTime(action.reviewWaitingSince)}}. 等待医生复核不计作患者延误。</p>
        <ul><li v-for="(e,index) in action.evidence || []" :key="index"><span v-if="e.restricted">存在受限的关联记录</span><a v-else-if="safeLink(e.detailLink)" :href="e.detailLink">{{e.title || '既有记录'}}</a><span v-else>{{e.title || '既有记录'}} #{{e.sourceId}}</span></li></ul>
        <div v-for="event in action.events || []" :key="event.id" class="event"><p>{{eventLabel(event.eventType)}} · {{event.actorName}} · {{roleLabel(event.actorRole)}}<template v-if="event.entryMode"> · {{event.entryMode==='ASSISTED'?'代为录入':'账号所有者自行录入声明'}}</template></p><p v-if="event.note">{{event.note}}</p><p>{{displayTime(event.recordedAt)}}</p></div>
        <div class="controls"><button v-if="action.allowedActions?.includes('SUBMIT_RECEIPT') && action.allowedEntryModes?.length" type="button" :data-testid="`record-${action.id}`" :ref="node=>setReceiptOpener(action.id,'submitReceipt',node)" @click="openReceipt(plan,action,'submitReceipt')">{{mode==='PATIENT'?'记录执行情况':'代为记录执行情况'}}</button><button v-if="action.allowedActions?.includes('REQUEST_HELP')" type="button" :data-testid="`help-${action.id}`" :ref="node=>setReceiptOpener(action.id,'requestHelp',node)" @click="openReceipt(plan,action,'requestHelp')">请求协助</button><button v-if="mode==='NURSE' && action.allowedActions?.includes('FOLLOW_UP')" type="button" :data-testid="`followup-${action.id}`" :ref="node=>setReceiptOpener(action.id,'followUp',node)" @click="openReceipt(plan,action,'followUp')">记录跟进</button></div>
      </article>
    </article>
    <button v-if="state.nextCursor" type="button" data-testid="more-tasks" :disabled="state.loading" @click="loadMore">更多计划事项</button>
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
async function initialize(){receiptEpoch++;generation++;controller?.abort();controller=new AbortController();enabled.value=false;message.value='';selected.value=null;client.reset();if(props.patientId==null&&props.mode!=='NURSE')return;const c=capture();try{const result=await getCarePlanCapabilities({expectedAuth:{...c.auth,actorId:c.actor},signal:controller.signal});if(!owns(c)||result.data?.enabled!==true)return;enabled.value=true;await reload()}catch{if(owns(c)){client.reset();message.value='当前会话无法使用计划协作。'}}}
async function reload(){if(!enabled.value)return;const result=await client.load({queue:props.queue});if(['forbidden','unauthenticated','unavailable'].includes(result.status)){selected.value=null;message.value='计划访问不可用或已被撤销。'}else if(result.status==='failed')message.value='无法加载计划事项，请刷新重试。';else if(result.status==='succeeded')message.value=''}
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
function displayTime(value){if(!value)return '时间不可用';try{return formatPlanTime(value,'zh-CN')}catch{return '服务器时间无效'}}
function instantMicros(value){const fraction=value.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/)?.[1]||'';return BigInt(Date.parse(value))*1000n+BigInt(fraction.padEnd(6,'0').slice(3,6))}
function originalTiming(action){try{return instantMicros(action.firstSubmittedAt)<=instantMicros(action.dueAt)?'原始提交按时':'原始提交晚于截止时间'}catch{return '原始提交时效不可用'}}
function safeLink(link){return typeof link==='string'&&link.startsWith('/')&&!link.startsWith('//')&&!/[\u0000-\u0020\u007f\\]/.test(link)}
function statusLabel(status){return ({OPEN:'待执行或补充信息',NEEDS_HELP:'已请求协助',SUBMITTED:'已提交，等待医生复核',CONFIRMED:'记录已复核',SUPERSEDED:'已被新版本替代',CANCELLED:'已取消'})[status]||'状态不可用'}
function roleLabel(role){return ({doctor:'医生',nurse:'护理人员',admin:'管理员',patient:'患者账号',owner:'记录所有者',family:'家庭照护者',guardian:'监护人'})[String(role||'').toLowerCase()]||'录入账号'}
function eventLabel(type){return ({RECEIPT_SUBMITTED:'执行记录已提交',HELP_REQUESTED:'已请求协助',FOLLOW_UP_RECORDED:'照护团队跟进',RECEIPT_CONFIRMED:'医生已复核记录',RECEIPT_RETURNED:'需要补充信息'})[type]||type}
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
