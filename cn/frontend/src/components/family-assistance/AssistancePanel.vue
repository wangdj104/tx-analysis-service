<template>
 <section class="assistance-panel" :aria-label="t('title')">
  <header><h2 tabindex="-1" ref="heading">{{t('title')}}</h2><button type="button" :disabled="flow.state.loading" @click="refresh">{{t('refresh')}}</button></header>
  <p>{{t('patient')}}: {{patientId}}</p><p>{{t('intro')}}</p>
  <p v-if="flow.state.phase==='forbidden'" role="alert">{{t('forbidden')}}</p><p v-else-if="flow.state.error" role="alert">{{t(flow.state.error==='HANDOVER_PROJECTION_CHANGED'?'projectionChanged':'error')}}</p>
  <p v-if="flow.state.capabilities && !flow.state.capabilities.enabled">{{t('disabled')}}</p><p v-if="flow.state.capabilities && flow.state.capabilities.schemaStatus!=='STRUCTURALLY_READY'" role="status">{{t('notReady')}}</p>
  <p v-if="flow.state.navigationBlocked" role="status">{{t('finishUnknown')}}</p>
  <p v-if="!sourceContext && !assistanceId">{{t('sourceDiscoveryLimit')}}</p>
  <section v-if="flow.state.handoverSource && !flow.state.detail">
   <SourceSummary :source="flow.state.sourcePreview" :phase="flow.state.sourcePhase"/>
   <p>{{t('lockedSource')}} #{{flow.state.handoverSource.careItemId}}</p>
   <button v-if="sourceCanCreate" type="button" :disabled="busy || !!flow.state.dialog" @click="flow.beginHandoverCreate()">{{t('arrangeHandover')}}</button>
   <button v-if="flow.state.sourcePreview?.currentAssistanceId" type="button" :disabled="busy" @click="selectHandover(flow.state.sourcePreview.currentAssistanceId)">{{t('openCurrent')}}</button>
  </section>
  <AssistanceDetail v-if="flow.state.detail" :flow="flow" @command="begin" @successor="successor" @select-handover="selectHandover" />
  <p v-if="busy" role="status">{{t(flow.state.phase==='unknown'?'unknown':'saving')}}</p><button v-if="flow.state.phase==='unknown'" type="button" @click="flow.reconcile">{{t('receipt')}}</button><button v-if="flow.state.phase==='unknown'" type="button" @click="flow.retry">{{t('identical')}}</button>
  <AssistanceDialog :flow="flow" />
  <WorkList v-if="flow.state.capabilities?.ordinary" :flow="flow" @select-handover="selectHandover"/>
  <details class="existing-requests"><summary>{{t('existingHistory')}}</summary><p>{{t('existingHelp')}}</p><button type="button" @click="flow.loadList()">{{t('loadExisting')}}</button>
   <ul><li v-for="row in flow.state.items" :key="row.assistanceId"><button type="button" :disabled="busy" @click="isOrdinaryHandover(row) ? selectHandover(row.assistanceId) : flow.open(row.assistanceId)">{{t(row.sourceKind)}} #{{row.assistanceId}} · {{t(row.state)}}</button></li></ul><button v-if="flow.state.nextCursor" type="button" @click="flow.loadList(true)">{{t('more')}}</button>
   <button v-if="flow.state.capabilities?.create" type="button" :disabled="busy" @click="createOther">{{t('otherRequest')}}</button>
  </details>
 </section>
</template>
<script setup>
import { toRef, watch, onBeforeUnmount, onMounted, computed, ref, nextTick } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { useFamilyAssistance } from '@/composables/useFamilyAssistance';
import { handoverId, isOrdinaryHandover } from '@/utils/familyHandover';
import { t } from '@/utils/familyAssistanceCopy';
import AssistanceDetail from './AssistanceDetail.vue';
import AssistanceDialog from './AssistanceDialog.vue';
import SourceSummary from './SourceSummary.vue';
import WorkList from './WorkList.vue';
const props=defineProps({patientId:[Number,String],assistanceId:[Number,String],sourceContext:Object,sourcePreviewOnly:{type:Boolean,default:false},sourceKind:{type:String,default:'CARE_ITEM'},active:{type:Boolean,default:true}});
const emit=defineEmits(['source-freeze','source-created','select-handover','source-successor']);
const flow=useFamilyAssistance(toRef(props,'patientId')),heading=ref(null);
const busy=computed(()=>['unknown','submitting'].includes(flow.state.phase));
const sourceCanCreate=computed(()=>flow.state.capabilities?.create && flow.state.sourcePreview?.contentStatus==='AVAILABLE' && ['UNTRACKED','CURRENT_TERMINAL'].includes(flow.state.sourcePreview.sourceReadiness));
// 普通交接先选定规范地址，再由地址读取详情，不能让正文与刷新目标分离。
function selectHandover(value){const assistanceId=handoverId(value),patientId=handoverId(props.patientId);if(assistanceId&&patientId&&props.active&&flow.protectNavigation())emit('select-handover',{patientId,assistanceId});}
function createOther(){flow.openDraft('CREATE',{sourceKind:props.sourceKind,sourceId:'',assignedUserId:'',deadlineUtc:''});}
function begin(operation){const d=flow.state.detail;if(!d)return;const draft={};if(['ASSIGN','HANDOFF'].includes(operation))draft.assignedUserId='';if(['REJECT','HANDLE','RETURN','HANDOFF','CANCEL','CLOSE_UNRESOLVED','REOPEN'].includes(operation))draft.reason='';if(operation==='RECONFIRM_APPOINTMENT')draft.fingerprint=d.source.fingerprint;return isOrdinaryHandover(d)?flow.beginHandoverAction(operation,draft):flow.openDraft(operation,draft);}
function successor(){const d=flow.state.detail;if(d&&props.active&&flow.protectNavigation())emit('source-successor',{assistanceId:d.assistanceId,careItemId:d.careItemId});}
function refresh(){if(flow.state.phase==='conflict')return flow.refreshConflict();if(flow.state.handoverSource&&!flow.state.detail)return flow.openHandoverSource(flow.state.handoverSource,true);return flow.load(flow.state.detail?.assistanceId || (props.assistanceId?Number(props.assistanceId):null));}
function protectNavigation(){return flow.protectNavigation();}
function beforeUnload(event){if(busy.value){event.preventDefault();event.returnValue='';}}
function contextChange(event){if(!event.defaultPrevented&&!protectNavigation()){event.preventDefault();event.stopImmediatePropagation();}}
onBeforeRouteLeave(protectNavigation);onBeforeRouteUpdate(protectNavigation);
watch(()=>[props.patientId,props.assistanceId,props.sourceContext,props.sourcePreviewOnly,props.active],()=>{flow.invalidate();if(props.active&&props.patientId){if(props.sourceContext)flow.openHandoverSource(props.sourceContext,props.sourcePreviewOnly);else refresh();}},{immediate:true});
watch(()=>flow.state.frozenSourceId,id=>emit('source-freeze',id),{flush:'sync'});
watch(()=>flow.state.sourceSettlement,value=>{if(value)emit('source-created',value);});
watch(()=>[flow.state.detail?.assistanceId,flow.state.handoverSource?.careItemId],async()=>{await nextTick();heading.value?.focus();});
onMounted(()=>{window.addEventListener('beforeunload',beforeUnload);window.addEventListener('care-plan-before-context-change',contextChange);});
onBeforeUnmount(()=>{window.removeEventListener('beforeunload',beforeUnload);window.removeEventListener('care-plan-before-context-change',contextChange);flow.dispose();});
defineExpose({flow,protectNavigation});
</script>
<style scoped>
.assistance-panel{min-width:0;padding:1rem;border:1px solid var(--el-border-color);border-radius:12px;background:var(--el-bg-color);color:var(--el-text-color-primary);overflow-wrap:anywhere}header{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:.5rem}button,summary{box-sizing:border-box;min-height:44px;min-width:44px;max-width:100%;padding:.5rem .8rem;color:inherit;background:var(--el-fill-color-light);border:1px solid var(--el-border-color);border-radius:8px;cursor:pointer;font:inherit;white-space:normal;overflow-wrap:anywhere}button:disabled{opacity:.55;cursor:wait}button:focus-visible,summary:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}li{margin:.4rem 0}.existing-requests{margin-top:1rem}.care-senior button,.care-senior summary{min-height:48px}@media(max-width:600px){header,button{width:100%}.assistance-panel{padding:.65rem}}
</style>
