<template>
 <section class="stock-work-panel" aria-label="Stock workflow">
  <header><h2>Stock review</h2><button type="button" :disabled="flow.state.loading" @click="refresh()">Refresh</button></header>
  <p>Patient record: {{ patientId }}</p><p>Record what was received or physically counted. Quantity changes do not close a review.</p>
  <p v-if="flow.state.phase==='forbidden'" role="alert">Medication access is no longer available. Select an authorized record.</p>
  <p v-else-if="flow.state.error" role="alert">{{ flow.state.error }}. Refresh the current record before editing again.</p>
  <p v-if="flow.state.capabilities && !flow.state.capabilities.enabled">Stock workflow is disabled. Tracked records remain protected.</p>
  <p v-if="flow.state.capabilities && flow.state.capabilities.schemaStatus!=='STRUCTURALLY_READY'" role="status">Stock workflow storage is not ready. Refresh later; no stock command is available.</p>
  <ul><li v-for="item in flow.state.items" :key="item.stockId"><button type="button" @click="flow.open(item.stockId)">{{ item.drugName }} · {{ item.quantity }} {{ item.unit }} · {{ stateText(item.state) }}</button></li></ul>
  <button v-if="flow.state.nextCursor" type="button" @click="flow.loadList(true)">More stock records</button>
  <StockWorkDetail v-if="flow.state.detail" :flow="flow" @command="begin" @notification-review="$emit('notification-review',$event)" />
  <p v-if="['unknown','submitting'].includes(flow.state.phase)" role="status">{{ flow.state.phase==='unknown' ? 'The result is unknown. Check the receipt or retry the identical request.' : 'Saving this stock review…' }}</p>
  <button v-if="flow.state.phase==='unknown'" type="button" @click="flow.reconcile()">Check receipt</button><button v-if="flow.state.phase==='unknown'" type="button" @click="flow.retry()">Retry identical request</button>
  <StockWorkDialog :flow="flow" />
 </section>
</template>
<script setup>
import { toRef, watch, onBeforeUnmount } from 'vue';
import { useStockWorkflow } from '@/composables/useStockWorkflow';
import StockWorkDetail from './StockWorkDetail.vue';import StockWorkDialog from './StockWorkDialog.vue';
const props=defineProps({patientId:[Number,String],stockId:[Number,String],active:{type:Boolean,default:true}});
defineEmits(['notification-review']);
const flow=useStockWorkflow(toRef(props,'patientId'));
const stateText=s=>({UNTRACKED:'Not yet tracked',OPEN:'Review open',RESOLVED:'Explicitly reconciled',NOT_APPLICABLE:'Rule changed; no longer applicable',CANCELLED_MISTAKE:'Cancelled as mistaken'})[s]||s;
function begin(operation,extra={}){const d=flow.state.detail;if(!d)return;const drafts={ADOPT_STOCK:{assignedUserId:flow.state.capabilities?.actorId||'',dailyUseQuantity:'',unit:d.unit,reason:''},RECORD_COUNT:{quantity:'',unit:d.unit,countedAt:'',reason:''},RECORD_REPLENISHMENT:{quantity:'',unit:d.unit,actualAt:'',reason:''},CHANGE_RULE:{assignedUserId:d.responsibleCurrent?d.assignedUserId:flow.state.capabilities?.actorId||'',warningQuantity:d.warningQuantity,warningDays:d.warningDays,dailyUseQuantity:d.dailyUseQuantity||'',unit:d.unit,reason:''},RECONCILE:{reason:''},CANCEL_MISTAKE:{reason:''},RESOLVE_INTAKE:{intakeId:extra.intake_id,actionId:extra.action_id,countChoice:'',countId:d.currentCount?.id,countVersion:d.currentCount?.stock_version,reason:''}};flow.openDraft(operation,drafts[operation]);}
function refresh(){return flow.load(props.stockId ? Number(props.stockId) : null);}
watch(()=>[props.patientId,props.stockId,props.active],()=>{flow.invalidate();if(props.active&&props.patientId)refresh();},{immediate:true});
function updateReminderPolicy({stockId,policy}){if(flow.state.detail?.stockId===stockId)flow.state.detail.reminderPolicy=policy;for(const row of flow.state.items)if(row.stockId===stockId)row.reminderPolicy=policy;}
defineExpose({updateReminderPolicy});
onBeforeUnmount(()=>flow.dispose());
</script>
<style scoped>
.stock-work-panel{padding:1rem;border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);background:var(--el-bg-color);color:var(--el-text-color-primary)}header{display:flex;justify-content:space-between;gap:1rem;flex-wrap:wrap}ul{list-style:none;padding:0}button{min-height:44px;min-width:44px;padding:.6rem;margin:.25rem;border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);color:inherit;background:var(--el-fill-color-light);cursor:pointer}li button{width:100%;text-align:left}button:disabled{opacity:.6;cursor:default}
</style>
