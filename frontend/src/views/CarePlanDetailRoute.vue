<template>
  <main class="plan-route"><router-link to="/care">Return to care</router-link><PlanDetail ref="detail" :plan-id="planId" @changed="changed"><template #action-controls="{action,plan,refresh}"><div class="route-controls"><button v-if="action.allowedActions?.includes('SUBMIT_RECEIPT') && action.allowedEntryModes?.length" type="button" :data-testid="`route-record-${action.id}`" :ref="node=>setReceiptOpener(action.id,'submitReceipt',node)" @click="open(action,plan,refresh,'submitReceipt')">Record execution</button><button v-if="action.allowedActions?.includes('REQUEST_HELP')" type="button" :ref="node=>setReceiptOpener(action.id,'requestHelp',node)" @click="open(action,plan,refresh,'requestHelp')">Ask for help</button><button v-if="isNurse && action.allowedActions?.includes('FOLLOW_UP')" type="button" :ref="node=>setReceiptOpener(action.id,'followUp',node)" @click="open(action,plan,refresh,'followUp')">Record follow-up</button></div></template></PlanDetail><ReceiptDialog v-if="selected" ref="receipt" :action="selected.action" :plan-version="selected.version" :command="selected.command" @submitted="submitted" @closed="closed" /></main>
</template>
<script setup>
import {computed,ref,watch,onMounted,onUnmounted,nextTick} from 'vue'
import {useRoute,onBeforeRouteLeave,onBeforeRouteUpdate} from 'vue-router'
import PlanDetail from '@/components/care-plan/PlanDetail.vue'
import ReceiptDialog from '@/components/care-plan/ReceiptDialog.vue'
import {captureAuthSession,isAuthSessionCurrent} from '@/utils/authSession'
const route=useRoute(),planId=computed(()=>Number(route.params.id)),selected=ref(null),receipt=ref(null),detail=ref(null)
let roles=[];try{roles=JSON.parse(localStorage.getItem('userRoleCodes')||'[]')}catch{}
const isNurse=roles.includes('nurse')
const receiptOpeners=new Map()
let receiptEpoch=0
function setReceiptOpener(id,command,node){const key=id+':'+command;if(node)receiptOpeners.set(key,node);else receiptOpeners.delete(key)}
function open(action,plan,refresh,command){if(selected.value)return;selected.value={action,version:plan.version,refresh,command,focusContext:{epoch:++receiptEpoch,planId:planId.value,auth:captureAuthSession(),actor:localStorage.getItem('userId')}}}
async function submitted(){await closed()}
async function closed(){
  const selection=selected.value
  if(!selection)return
  const key=selection.action.id+':'+selection.command,original=receiptOpeners.get(key),context=selection.focusContext
  selected.value=null
  await selection.refresh?.()
  await nextTick()
  if(selected.value||receiptEpoch!==context.epoch||planId.value!==context.planId||!isAuthSessionCurrent(context.auth)||localStorage.getItem('userId')!==context.actor)return
  const target=receiptOpeners.get(key),active=document.activeElement
  if(target?.isConnected&&!target.matches(':disabled')&&!target.closest('[hidden],[inert]')&&target.getClientRects().length&&window.getComputedStyle(target).visibility==='visible'&&(!active||active===document.body||active===original))target.focus()
}
function changed(){if(selected.value && !receipt.value?.guardLeave?.())return;selected.value=null}
const guard=()=>{const allowed=(detail.value?.requestLeave?.()??true) && (receipt.value?.guardLeave?.()??true);if(allowed)receiptEpoch++;return allowed}
onBeforeRouteLeave(guard);onBeforeRouteUpdate(guard)
// ReceiptDialog guards this event itself. A blocked detail owns the acknowledgment.
function contextChange(event){if(!event.defaultPrevented && detail.value?.requestLeave?.()===false){event.preventDefault();event.stopImmediatePropagation()}if(!event.defaultPrevented)receiptEpoch++}
onMounted(()=>window.addEventListener('care-plan-before-context-change',contextChange))
onUnmounted(()=>{receiptEpoch++;receiptOpeners.clear();window.removeEventListener('care-plan-before-context-change',contextChange)})
watch(planId,()=>{receiptEpoch++;selected.value=null},{flush:'sync'})
</script>
<style scoped>
.plan-route{box-sizing:border-box;max-width:1200px;min-width:0;margin:auto;padding:24px}.route-controls{display:flex;flex-wrap:wrap;gap:10px}button,a{display:inline-flex;align-items:center;box-sizing:border-box;min-height:44px;padding:10px 14px;max-width:100%;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800);font:inherit}button{cursor:pointer}button:focus-visible,a:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}@media(max-width:600px){.plan-route{padding:14px}.route-controls button{width:100%}}
</style>
