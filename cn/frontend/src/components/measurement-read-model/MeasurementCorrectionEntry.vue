<template>
 <section class="measurement-read-model mr-panel" data-testid="measurement-correction-entry">
  <header class="mr-panel-heading">
   <div><h2 ref="heading" tabindex="-1">{{t('correctionTitle')}}</h2><p class="mr-help">{{t('correctionScope')}}</p></div>
   <button type="button" data-action="toggle-correction-entry" :aria-expanded="expanded" :disabled="sourceLocked" @click="toggle">{{t(expanded?'correctionClose':'correctionOpen')}}</button>
  </header>
  <template v-if="expanded">
   <p class="mr-help">{{t('correctionBaseline')}}</p>
   <p v-if="!patientId" role="status">{{t('choosePatient')}}</p>
   <template v-else>
    <p class="mr-patient"><strong>{{patientName || t('patient')}}</strong><small>{{t('patientId')}} #{{patientId}}</small></p>
    <div class="mr-controls"><label>{{t('source')}}<select v-model="sourceType" :disabled="sourceLocked" data-control="source"><option value="BP_SELF_MONITOR">{{t('BP_SELF_MONITOR')}}</option><option value="HEALTH_MEASUREMENT">{{t('HEALTH_MEASUREMENT')}}</option></select></label></div>
    <OriginalMeasurementPageStatus :state="state" :locked="sourceLocked" @refresh="refresh" @retry="pageAction('refresh',{keepPosition:true})" @next="pageAction('next')" @previous="pageAction('previous')" />
    <p class="mr-help">{{t('correctionNotes')}}</p>
    <div v-if="state.phase==='ready' && state.items.length" class="mr-table-wrap" tabindex="0" role="region" :aria-label="t('correctionTitle')">
     <table><caption>{{t('pageScope')}}</caption><thead><tr><th scope="col">{{t('source')}}</th><th scope="col">{{t('originalTime')}}</th><th scope="col">{{t('original')}}</th><th scope="col">{{t('historyStatus')}}</th><th scope="col">{{t('correctionReview')}}</th></tr></thead>
      <tbody><tr v-for="row in state.items" :key="`${sourceType}:${row.id}`" :data-source-id="row.id">
       <th scope="row">{{t(sourceType)}} #{{row.id}}</th>
       <td>{{isSelf?selfTime(row):recordedTime(row.measured_at)}}</td>
       <td><strong class="mr-value-name">{{isSelf?t(row.measureType || 'unknown'):journeyName(row)}}</strong>{{isSelf?selfValues(row):journeyValues(row)}}<small v-if="isSelf">{{t('period')}}: {{row.measurePeriod || t('periodUnknown')}}</small></td>
       <td>{{(isSelf?row.verificationStatus:row.status) || t('unknown')}}</td>
       <td><button type="button" data-action="review-correction-record" :disabled="sourceLocked" @click="openSource(row,$event)">{{t('correctionReview')}}</button></td>
      </tr></tbody>
     </table>
    </div>
   </template>
  </template>
  <MeasurementSourceDialog ref="sourceDialog" :patient-id="patientId" :locator="locator" :context-key="state.generation" :source-context="sourceType" :opener="opener" :fallback="heading" @close="locator=null" @changed="sourceChanged" @access-denied="model.deny()" @locked="sourceLocked=$event" />
 </section>
</template>
<script setup>
import { computed, inject, ref, shallowRef, toRef, watch } from 'vue';
import { useOriginalMeasurementPage } from '@/composables/useOriginalMeasurementPage';
import { originalText as t } from '@/utils/originalMeasurementCopy';
import { selfTime, selfValues, recordedTime, journeyValues, journeyName } from '@/utils/originalMeasurementPresentation';
import OriginalMeasurementPageStatus from './OriginalMeasurementPageStatus.vue';
import MeasurementSourceDialog from './MeasurementSourceDialog.vue';
import './measurement-read-model.css';
const props = defineProps({ patientId: { type: Number, default: null }, patientName: { type: String, default: '' }, active: { type: Boolean, default: true } });
const emit = defineEmits(['changed']);
const expanded = ref(false), sourceType = ref('BP_SELF_MONITOR');
const isSelf = computed(() => sourceType.value === 'BP_SELF_MONITOR');
const permissions = inject('userMenus', ref([]));
// 入口按需读取一个来源的当前页；不加日期过滤，保留缺少日期的原始资料。
const query = computed(() => ({ sourceType: sourceType.value, limit: 30 }));
const active = computed(() => props.active && expanded.value);
const model = useOriginalMeasurementPage(toRef(props, 'patientId'), query, active, { permissions });
const state = model.state;
const sourceDialog = ref(null), locator = shallowRef(null), opener = shallowRef(null), heading = ref(null), sourceLocked = ref(false);
watch(() => state.generation, () => { sourceDialog.value?.invalidate(); locator.value = null; }, { flush: 'sync' });
// 来源切走再切回也属于真实边界，不能把上一次关闭的焦点交给新来源。
watch(sourceType, value => sourceDialog.value?.invalidate({ sourceContext: value }), { flush: 'sync' });
function toggle() { if (!sourceLocked.value) expanded.value = !expanded.value; }
function pageAction(action, options) { if (!sourceLocked.value) return model[action](options); }
function refresh() { return pageAction('refresh'); }
function openSource(row, event) {
    if (sourceLocked.value || state.phase !== 'ready' || !active.value) return;
    opener.value = event?.currentTarget;
    locator.value = { sourceType: sourceType.value, sourceId: row.id };
}
// 原顺序不是快照；只有当前所有者的已落定变更才从第一页重读并通知概览。
function sourceChanged() { refresh(); emit('changed'); }
</script>
