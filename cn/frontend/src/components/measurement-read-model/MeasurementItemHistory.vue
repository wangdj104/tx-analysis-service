<template>
 <section class="measurement-read-model mr-panel" data-testid="measurement-item-history">
  <h2 ref="heading" tabindex="-1">{{t('itemTitle')}}</h2>
  <p v-if="!patientId" role="status">{{t('choosePatient')}}</p>
  <template v-else>
   <p class="mr-patient"><strong>{{patientName || t('patient')}}</strong><small>{{t('patientId')}} #{{patientId}}</small></p>
   <p class="mr-help">{{t('itemOrder')}}</p><p class="mr-help">{{t('summaryOnly')}}</p>
   <div class="mr-controls"><button type="button" :disabled="items.phase==='loading' || sourceLocked" @click="refreshItems">{{t('refresh')}}</button></div>
   <p v-if="blockedPhase" class="mr-notice mr-error" role="alert">{{t(blockedPhase)}}</p>
   <p v-if="items.phase==='loading'" role="status">{{t('loading')}}</p>
   <p v-else-if="!['ready','idle'].includes(items.phase)" class="mr-notice mr-error" role="alert">{{t(items.phase)}}</p>
   <template v-if="items.phase==='ready'">
    <div class="mr-notice"><strong>{{t('page')}} {{items.page}} · {{items.items.length}} {{t('items')}}</strong><p>{{t('nonSnapshot')}}</p><p>{{t(items.nextCursor!=null?'more':'last')}}</p></div>
    <p v-if="!items.items.length">{{t('emptyItems')}}</p>
    <ul class="mr-item-list"><li v-for="item in items.items" :key="item.alertId" class="mr-item" :aria-current="selected?.alertId===item.alertId">
     <h3>{{t('itemId')}} #{{item.alertId}} · {{t(item.metric)}}</h3>
     <p>{{t(item.state)}} · {{t(item.mode)}} · {{t('cycle')}} {{item.cycleNo}}</p>
     <p>{{t(item.sourceType)}} #{{item.sourceId}} · {{t('due')}}: {{item.dueAt?formatWorkflowTime(item.dueAt):t('noDeadline')}}</p>
     <div class="mr-controls"><button type="button" data-action="select-measurement-item" :disabled="sourceLocked" @click="selectItem(item,$event)">{{t('selectItem')}}</button><router-link class="mr-link" :to="followUpLink(item)">{{t('originalFollowUp')}}</router-link><button type="button" :disabled="sourceLocked" @click="openSource(item,$event)">{{t('sourceOpen')}}</button></div>
    </li></ul>
   </template>
   <div class="mr-controls"><button type="button" :disabled="items.phase==='loading' || sourceLocked || !items.previous.length" @click="itemModel.previous()">{{t('previous')}}</button><button type="button" :disabled="items.phase==='loading' || sourceLocked || items.nextCursor==null" @click="itemModel.next()">{{t('next')}}</button></div>
   <section v-if="selected" class="mr-item" data-testid="measurement-events">
    <h3 ref="eventHeading" tabindex="-1">{{t('eventTitle')}} · #{{selected.alertId}}</h3>
    <p class="mr-help">{{t('eventOrder')}}</p><p class="mr-help">{{t('summaryOnly')}}</p>
    <div class="mr-controls"><button type="button" :disabled="eventState.phase==='loading' || sourceLocked" @click="eventModel.refresh()">{{t('refresh')}}</button><button type="button" :disabled="sourceLocked" @click="clearSelection">{{t('clearSelection')}}</button></div>
    <p v-if="eventState.phase==='loading'" role="status">{{t('loading')}}</p><p v-else-if="!['ready','idle'].includes(eventState.phase)" class="mr-notice mr-error" role="alert">{{t(eventState.phase)}}</p>
    <template v-if="eventState.phase==='ready'">
     <div class="mr-notice"><strong>{{t('page')}} {{eventState.page}} · {{eventState.items.length}} {{t('items')}}</strong><p>{{t('nonSnapshot')}}</p><p>{{t(eventState.nextCursor!=null?'more':'last')}}</p></div>
     <p v-if="!eventState.items.length">{{t('emptyEvents')}}</p>
     <ol class="mr-event-list"><li v-for="event in eventState.items" :key="event.id"><strong>{{t(event.eventType)}} · {{t('cycle')}} {{event.cycleNo}} · #{{event.id}}</strong><p>{{t('transition')}}: {{event.fromStatus?t(event.fromStatus):'—'}} → {{t(event.toStatus)}}</p><p>{{t('occurred')}}: {{event.occurredAt?formatWorkflowTime(event.occurredAt):t('noTime')}}</p><p>{{t('recorded')}}: {{event.recordedAt?formatWorkflowTime(event.recordedAt):t('noTime')}}</p></li></ol>
    </template>
    <div class="mr-controls"><button type="button" :disabled="eventState.phase==='loading' || sourceLocked || !eventState.previous.length" @click="eventModel.previous()">{{t('previous')}}</button><button type="button" :disabled="eventState.phase==='loading' || sourceLocked || eventState.nextCursor==null" @click="eventModel.next()">{{t('next')}}</button><router-link class="mr-link" :to="followUpLink(selected)">{{t('originalFollowUp')}}</router-link><button type="button" :disabled="sourceLocked" @click="openSource(selected,$event)">{{t('sourceOpen')}}</button></div>
   </section>
   <p v-else-if="items.phase==='ready'">{{t('idle')}}</p>
  </template>
  <MeasurementSourceDialog ref="sourceDialog" :patient-id="patientId" :locator="locator" :context-key="`${items.generation}:${eventState.generation}`" :opener="opener" :fallback="heading" @close="locator=null" @changed="refreshItems" @access-denied="itemModel.deny()" @locked="sourceLocked=$event" />
 </section>
</template>
<script setup>
import { computed, inject, nextTick, ref, shallowRef, toRef, watch } from 'vue';
import { useMeasurementReadModel } from '@/composables/useMeasurementReadModel';
import { measurementText as t } from '@/utils/measurementReadModelCopy';
import { formatWorkflowTime } from '@/utils/alertWorkflowTime';
import MeasurementSourceDialog from './MeasurementSourceDialog.vue';
import './measurement-read-model.css';
const props = defineProps({ patientId: { type: Number, default: null }, patientName: { type: String, default: '' }, active: { type: Boolean, default: true } });
const emit = defineEmits(['locked']);
const sourceDialog = ref(null);
const selected = shallowRef(null), locator = shallowRef(null), opener = shallowRef(null), itemOpener = shallowRef(null), sourceLocked = ref(false), heading = ref(null), eventHeading = ref(null), blockedPhase = ref('');
watch(sourceLocked, value => emit('locked', value), { flush: 'sync' });
const permissions = inject('userMenus', ref([]));
const itemModel = useMeasurementReadModel('items', toRef(props, 'patientId'), {}, toRef(props, 'active'), { permissions });
const items = itemModel.state;
const eventQuery = computed(() => ({ alertId: selected.value?.alertId }));
const eventActive = computed(() => props.active && !!selected.value);
const eventModel = useMeasurementReadModel('events', toRef(props, 'patientId'), eventQuery, eventActive, { permissions, onFailure: phase => { if (phase === 'denied') { blockedPhase.value = phase; itemModel.clear(); } } });
const eventState = eventModel.state;
// 选择器换页或失权时，旧事项及其原始来源必须在同一轮同步清除。
watch(() => items.generation, () => { sourceDialog.value?.invalidate(); selected.value = null; locator.value = null; }, { flush: 'sync' });
watch(() => eventState.generation, () => { sourceDialog.value?.invalidate(); locator.value = null; }, { flush: 'sync' });
function followUpLink(item) { return `/alert-workflows/${item.alertId}?patientId=${props.patientId}`; }
async function selectItem(item, event) {
    if (sourceLocked.value || items.phase !== 'ready') return;
    itemOpener.value = event?.currentTarget;
    selected.value = { ...item };
    const current = selected.value;
    await nextTick();
    if (selected.value === current) eventHeading.value?.focus?.({ preventScroll: true });
}
async function clearSelection() {
    selected.value = null;
    locator.value = null;
    await nextTick();
    const target = itemOpener.value?.isConnected ? itemOpener.value : heading.value;
    target?.focus?.({ preventScroll: true });
}
function openSource(item, event) { if (sourceLocked.value) return; opener.value = event?.currentTarget; locator.value = { sourceType: item.sourceType, sourceId: item.sourceId }; }
function refreshItems() { blockedPhase.value = ''; itemModel.clear(); return itemModel.refresh(); }
defineExpose({ refresh: refreshItems, clear: itemModel.clear });
</script>
