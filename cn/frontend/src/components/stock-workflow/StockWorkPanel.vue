<template>
 <section class="stock-work-panel" aria-label="库存闭环">
  <header><h2>库存核对</h2><button type="button" :disabled="flow.state.loading" @click="refresh()">刷新</button></header>
  <p>患者记录： {{ patientId }}</p><p>记录实际收到的补充或实物盘点。数量变化不会自动结案。</p>
  <p v-if="flow.state.phase==='forbidden'" role="alert">当前用药访问权限已失效，请选择有权查看的记录。</p>
  <p v-else-if="flow.state.error" role="alert">{{ flow.state.error }}. 重新编辑前请刷新当前记录。</p>
  <p v-if="flow.state.capabilities && !flow.state.capabilities.enabled">库存闭环开关已关闭，已跟踪记录仍受保护。</p>
  <p v-if="flow.state.capabilities && flow.state.capabilities.schemaStatus!=='STRUCTURALLY_READY'" role="status">库存闭环存储尚未就绪，请稍后刷新，目前无法执行库存操作。</p>
  <ul><li v-for="item in flow.state.items" :key="item.stockId"><button type="button" @click="flow.open(item.stockId)">{{ item.drugName }} · {{ item.quantity }} {{ item.unit }} · {{ stateText(item.state) }}</button></li></ul>
  <button v-if="flow.state.nextCursor" type="button" @click="flow.loadList(true)">更多库存记录</button>
  <StockWorkDetail v-if="flow.state.detail" :flow="flow" @command="begin" @notification-review="$emit('notification-review',$event)" />
  <p v-if="['unknown','submitting'].includes(flow.state.phase)" role="status">{{ flow.state.phase==='unknown' ? '结果未知，请查询回执或原样重试同一请求。' : '正在保存库存核对…' }}</p>
  <button v-if="flow.state.phase==='unknown'" type="button" @click="flow.reconcile()">查询回执</button><button v-if="flow.state.phase==='unknown'" type="button" @click="flow.retry()">原样重试</button>
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
const stateText=s=>({UNTRACKED:'尚未跟踪',OPEN:'待核对',RESOLVED:'已明确核清',NOT_APPLICABLE:'规则已变更，不再适用',CANCELLED_MISTAKE:'误建已取消'})[s]||s;
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
