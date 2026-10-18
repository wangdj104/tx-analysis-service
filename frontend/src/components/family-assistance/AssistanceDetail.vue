<template>
 <article class="assistance-detail">
  <SourceSummary v-if="ordinary" :source="flow.state.handoverContent" :phase="flow.state.contentPhase"/><button v-if="ordinary" type="button" @click="flow.loadHandoverContent()">{{t('refreshContent')}}</button>
  <h3 v-if="ordinary">{{t(d.state)}}</h3>
  <p v-if="ordinary && flow.state.handoverContent?.contentStatus!=='AVAILABLE'">{{t('requestIdentity')}} #{{d.assistanceId}} · {{t(d.sourceKind)}} #{{d.source.id}}</p>
  <p v-if="ordinary && !flow.state.handoverContent?.responsibilityStatus">{{t('namedPerson')}} {{d.assignedUserId ? '#'+d.assignedUserId : t('notRecorded')}} · {{t('acceptedPerson')}} {{d.acceptedBy ? '#'+d.acceptedBy : t('notRecorded')}} · {{t('originatorPerson')}} #{{d.originatorId}}</p>
  <template v-if="!ordinary">
  <h3>{{t(d.sourceKind)}} #{{d.source.id}} · {{t(d.state)}}</h3><p>{{t('cycle')}} {{d.cycle}} · {{t('originator')}} {{d.originatorId}} · {{t('handler')}} {{d.assignedUserId || t('noHandler')}}</p>
  <p>{{t('time')}}: {{d.source.time || '—'}} · {{t('status')}}: {{t(d.source.status)}}</p><p>{{t('immutable')}}</p>
  </template>
  <p v-if="d.currentSource===false" role="status">{{t('superseded')}}<button v-if="d.currentAssistanceId && d.currentAssistanceId!==d.assistanceId" type="button" :disabled="ordinary && busy" @click="ordinary ? $emit('select-handover',d.currentAssistanceId) : flow.open(d.currentAssistanceId)">{{t('openCurrent')}}</button></p>
  <p>{{t('deadlineUtc')}}: {{d.deadlineAt || t('noDeadline')}}</p>
  <p v-if="d.state==='AWAITING_CONFIRMATION'" role="status">{{t('awaiting')}}</p><p v-if="d.overdue" role="status">{{t('overdue')}}</p><p v-if="d.sourceChanged" role="alert">{{t('sourceChanged')}}</p><p v-if="!ordinary && d.assignedUserId && !d.handlerCurrent && d.acceptedBy" role="alert">{{t('accessLost')}}</p>
  <template v-if="ordinary && !flow.state.handoverContent?.responsibilityStatus">
   <p v-if="d.assignedUserId && d.blockedReasons?.includes('HANDLER_ACCESS_LOST')" role="alert">{{t('assignedAccessLost')}}</p>
   <p v-if="d.acceptedBy && d.handlerCurrent===false" role="alert">{{t('acceptedAccessLost')}}</p>
  </template>
  <p v-if="d.acceptedAt">{{t('accepted')}} {{d.acceptedAt}}</p><p>{{d.handledAt ? t('handled')+' '+d.handledAt : t('noEvidence')}}</p><p v-if="d.closeReason">{{t('endReason')}}: {{d.closeReason}}</p>
  <button v-if="ordinary && d.currentSource && ['COMPLETED','CANCELLED','CLOSED_UNRESOLVED'].includes(d.state) && flow.state.capabilities?.create" type="button" :disabled="busy" @click="$emit('successor')">{{t('newSuccessor')}}</button>
  <div class="commands"><button v-for="op in d.allowedCommands" :key="op" type="button" :disabled="busy" @click="$emit('command',op)">{{t(op)}}</button></div>
  <component :is="ordinary ? 'details' : 'div'" :class="{'technical-details':ordinary}">
   <summary v-if="ordinary">{{t('technicalDetails')}}</summary>
   <template v-if="ordinary">
  <h3>{{t(d.sourceKind)}} #{{d.source.id}} · {{t(d.state)}}</h3><p>{{t('cycle')}} {{d.cycle}} · {{t('originator')}} {{d.originatorId}} · {{t('handler')}} {{d.assignedUserId || t('noHandler')}}</p>
  <p>{{t('time')}}: {{d.source.time || '—'}} · {{t('status')}}: {{t(d.source.status)}}</p><p>{{t('immutable')}}</p>
   </template>
  <button type="button" @click="flow.loadEvents()">{{t('history')}}</button><ol><li v-for="e in flow.state.events" :key="e.id">{{t('cycle')}} {{e.cycle}} · {{t(e.type)}} · {{e.occurred_at}}<p v-if="e.reason">{{e.reason}}</p></li></ol><button v-if="flow.state.eventCursor" type="button" @click="flow.loadEvents(true)">{{t('moreEvents')}}</button>
  <button type="button" @click="flow.loadDeliveries()">{{t('deliveries')}}</button><ul><li v-for="row in flow.state.deliveries" :key="row.id">#{{row.id}} · {{t(row.status)}}<p v-if="row.blockedReason" role="status">{{t(row.blockedReason)}}</p><label v-if="row.requiresDuplicateRiskAcknowledgement"><input v-model="ack[row.id]" type="checkbox">{{t('duplicate')}}</label><button v-if="row.canRetry" type="button" :disabled="flow.state.deliveryPhase==='submitting' || (row.requiresDuplicateRiskAcknowledgement && !ack[row.id])" @click="flow.retryDelivery(row,!!ack[row.id])">{{t('retryDelivery')}}</button></li></ul><p v-if="!ordinary && flow.state.deliveryPhase==='unknown'" role="status">{{t('retryUnknown')}}</p><button v-if="flow.state.deliveryCursor" type="button" @click="flow.loadDeliveries(true)">{{t('moreDeliveries')}}</button>
  </component>
  <p v-if="ordinary && flow.state.deliveryPhase==='unknown'" role="status">{{t('retryUnknown')}}</p>
  <button type="button" @click="flow.close">{{t('close')}}</button>
 </article>
</template>
<script setup>
import { computed, reactive, watch } from 'vue';
import SourceSummary from './SourceSummary.vue';
import { isOrdinaryHandover } from '@/utils/familyHandover';
import { t } from '@/utils/familyAssistanceCopy';
const props=defineProps({flow:{type:Object,required:true}});
defineEmits(['command','successor','select-handover']);
const d=computed(()=>props.flow.state.detail),busy=computed(()=>['unknown','submitting'].includes(props.flow.state.phase)),ack=reactive({}),ordinary=computed(()=>isOrdinaryHandover(d.value));
watch(()=>[d.value?.assistanceId,d.value?.version,d.value?.cycle,props.flow.state.deliveries],()=>{Object.keys(ack).forEach(key=>delete ack[key]);},{flush:'sync'});
</script>
<style scoped>
.assistance-detail{margin-top:1rem;border-top:1px solid var(--el-border-color);padding-top:.7rem;overflow-wrap:anywhere}.technical-details{margin-top:.75rem}summary{cursor:pointer}.commands{display:flex;flex-wrap:wrap;gap:.5rem}button,label,summary{font:inherit;max-width:100%;white-space:normal;overflow-wrap:anywhere;min-height:44px;min-width:44px;margin:.25rem 0;padding:.5rem;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-fill-color-light);color:inherit}label{display:flex;align-items:center;gap:.5rem}li{margin:.4rem 0}button:focus-visible,summary:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.care-senior button,.care-senior label,.care-senior summary{min-height:48px}@media(max-width:600px){button{width:100%}}
</style>
