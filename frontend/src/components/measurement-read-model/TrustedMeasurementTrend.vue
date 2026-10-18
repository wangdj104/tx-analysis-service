<template>
 <section class="measurement-read-model mr-panel" data-testid="trusted-measurement-trend">
  <h2 ref="heading" tabindex="-1">{{t('title')}}</h2>
  <p class="mr-help">{{t('subtitle')}}</p>
  <p v-if="!patientId" role="status">{{t('choosePatient')}}</p>
  <template v-else>
   <p class="mr-patient"><strong>{{patientName || t('patient')}}</strong><small>{{t('patientId')}} #{{patientId}}</small></p>
   <div class="mr-controls">
    <label>{{t('source')}}<select v-model="sourceType" :disabled="sourceLocked" data-control="source"><option value="BP_SELF_MONITOR">{{t('BP_SELF_MONITOR')}}</option><option value="HEALTH_MEASUREMENT">{{t('HEALTH_MEASUREMENT')}}</option></select></label>
    <label>{{t('metric')}}<select v-model="metric" :disabled="sourceLocked" data-control="metric"><option v-for="key in availableMetrics" :key="key" :value="key">{{t(key)}}</option></select></label>
    <label>{{t('from')}}<input v-model="from" type="date" :disabled="sourceLocked" data-control="from" /></label>
    <label>{{t('to')}}<input v-model="to" type="date" :disabled="sourceLocked" data-control="to" /></label>
    <button type="button" :disabled="busy || sourceLocked" @click="model.refresh()">{{t('refresh')}}</button>
   </div>
   <p class="mr-help">{{t('rangeHelp')}}</p>
   <p v-if="busy" role="status">{{t('loading')}}</p>
   <div v-else-if="state.phase!=='ready' && state.phase!=='idle'" class="mr-notice mr-error" role="alert"><p>{{t(state.phase)}}</p><button type="button" :disabled="sourceLocked" @click="model.refresh({keepPosition:true})">{{t('retry')}}</button></div>
   <template v-if="state.phase==='ready'">
    <div class="mr-notice" role="status"><strong>{{t('page')}} {{state.page}} · {{state.items.length}} {{t('rows')}}</strong><p>{{t('nonSnapshot')}}</p><p>{{t('dateCoverage')}}</p><p>{{t(state.nextCursor!=null?'more':'last')}}</p></div>
    <p class="mr-help">{{t('timeBasis')}}</p>
    <p v-if="!state.items.length">{{t('empty')}}</p>
    <template v-else>
     <p>{{reviewCount}} {{t('qualityCount')}}</p>
     <p class="mr-help">{{t(metric==='CUSTOM'?'customFacts':'chartHelp')}}</p>
     <HealthChart v-if="plotRows.length" class="mr-chart" :option="chartOption" autoresize :key="state.generation" />
     <p v-else-if="metric!=='CUSTOM'">{{t('noComparison')}}</p>
     <p class="mr-help">{{t('localValues')}}</p>
     <div v-for="group in rowGroups" :key="group.key" class="mr-table-wrap" tabindex="0" role="region" :aria-label="t(group.key)">
      <table v-if="group.rows.length"><caption>{{t(group.key)}}</caption><thead><tr><th scope="col">{{t('originalTime')}}</th><th scope="col">{{t('original')}}</th><th scope="col">{{t('comparison')}}</th><th scope="col">{{t('quality')}}</th><th scope="col">{{t('sourceIdentity')}}</th></tr></thead>
       <tbody><tr v-for="item in group.rows" :key="`${item.sourceType}:${item.sourceId}`" :data-source-id="item.sourceId">
        <th scope="row">{{originalMeasurementTime(item)}}<small v-if="!localMeasurementTime(item.plotTime)">{{t('unknownTime')}}</small></th>
        <td><strong v-if="metric==='CUSTOM'" class="mr-value-name">{{measurementName(item,metric)}}</strong><span v-if="metric==='CUSTOM'">{{t('primary')}}: </span>{{measurementValue(item.rawValue,metric)}} <strong>{{measurementUnit(item.rawUnit)}}</strong><small v-if="metric==='CUSTOM'">{{t('secondary')}}: {{measurementValue(item.rawSecondaryValue,metric)}}</small><small>{{t(item.unitBasis==='SCHEMA_DEFINED'?'schemaUnit':'recordedUnit')}}</small><small v-if="item.measurePeriod">{{t('period')}}: {{item.measurePeriod}}</small></td>
        <td><span v-if="metric==='CUSTOM'">{{t('NO_STANDARD_COMPARISON_POLICY')}}</span><template v-else-if="item.comparisonValue!=null && item.comparisonUnit">{{measurementValue(item.comparisonValue,metric)}} {{item.comparisonUnit}}<small v-if="!canPlotMeasurement(item,metric)">{{t('numericOnly')}}</small></template><span v-else>{{t('unavailableComparison')}}</span></td>
        <td><strong class="mr-quality" :class="{'mr-quality--review':!canPlotMeasurement(item,metric)}">{{canPlotMeasurement(item,metric)?t(item.status):t('UNCLASSIFIABLE')}}</strong><span>{{measurementQuality(item)}}</span><small>{{t('policy')}}: {{item.policyKey || t('unknown')}}</small></td>
        <td>{{t(item.sourceType)}} #{{item.sourceId}}<small>{{t('sourceRevision')}} {{item.sourceRevision ?? '—'}} · {{t('sourceVersion')}} {{item.sourceVersion ?? '—'}} · {{t(item.sourceStatus)}}</small><small v-if="item.sourceKind">{{item.sourceKind}}</small><button type="button" :disabled="sourceLocked" @click="openSource(item,$event)">{{t('sourceOpen')}}</button></td>
       </tr></tbody>
      </table>
     </div>
    </template>
   </template>
   <div class="mr-controls"><button type="button" :disabled="busy || sourceLocked || !state.previous.length" @click="model.previous()">{{t('previous')}}</button><button type="button" :disabled="busy || sourceLocked || state.nextCursor==null" @click="model.next()">{{t('next')}}</button><router-link class="mr-link" :to="sourceType==='BP_SELF_MONITOR'?'/bp-self-monitor':'/care-journey'">{{t('rawRecords')}}</router-link></div>
  </template>
  <MeasurementSourceDialog ref="sourceDialog" :patient-id="patientId" :locator="locator" :context-key="state.generation" :source-context="sourceType" :opener="opener" :fallback="heading" @close="locator=null" @changed="sourceChanged" @access-denied="model.deny()" @locked="sourceLocked=$event" />
 </section>
</template>
<script setup>
import { computed, inject, ref, shallowRef, toRef, watch } from 'vue';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { ScatterChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import HealthChart from '@/components/HealthChart.vue';
import MeasurementSourceDialog from './MeasurementSourceDialog.vue';
import { useMeasurementReadModel } from '@/composables/useMeasurementReadModel';
import { measurementText as t } from '@/utils/measurementReadModelCopy';
import { canPlotMeasurement, localMeasurementTime, measurementValue, originalMeasurementTime, measurementQuality, sevenDayRange, measurementMetrics, metricsForSource, measurementUnit, measurementName } from '@/utils/measurementReadModelPresentation';
import './measurement-read-model.css';
use([CanvasRenderer, ScatterChart, GridComponent, LegendComponent, TooltipComponent]);
const props = defineProps({ patientId: { type: Number, default: null }, patientName: { type: String, default: '' }, active: { type: Boolean, default: true }, initialSourceType: { type: String, default: 'BP_SELF_MONITOR' } });
const emit = defineEmits(['changed']);
const range = sevenDayRange(), from = ref(range.from), to = ref(range.to), sourceType = ref(metricsForSource(props.initialSourceType).length ? props.initialSourceType : 'BP_SELF_MONITOR'), selectedMetric = ref('BP');
const availableMetrics = computed(() => metricsForSource(sourceType.value));
// 来源和指标作为一个有效读取范围，切换来源时不发送中间态的非法组合。
const metric = computed({ get: () => availableMetrics.value.includes(selectedMetric.value) ? selectedMetric.value : 'BP', set: value => { selectedMetric.value = value; } });
const query = computed(() => ({ sourceType: sourceType.value, metric: metric.value, from: from.value, to: to.value }));
const permissions = inject('userMenus', ref([]));
const model = useMeasurementReadModel('trend', toRef(props, 'patientId'), query, toRef(props, 'active'), { permissions });
const state = model.state, busy = computed(() => state.phase === 'loading');
const sourceDialog = ref(null);
const locator = shallowRef(null), opener = shallowRef(null), sourceLocked = ref(false), heading = ref(null);
watch(() => state.generation, () => { sourceDialog.value?.invalidate(); locator.value = null; }, { flush: 'sync' });
// 来源边界同步通知对话框，避免批量渲染合并同栈切走再切回的变化。
watch(sourceType, value => sourceDialog.value?.invalidate({ sourceContext: value }), { flush: 'sync' });
const plotRows = computed(() => state.items.filter(item => canPlotMeasurement(item, metric.value)));
const reviewCount = computed(() => state.items.length - plotRows.value.length);
const rowGroups = computed(() => [{ key: 'original', rows: state.items.filter(item => localMeasurementTime(item.plotTime)) }, { key: 'uncertainTime', rows: state.items.filter(item => !localMeasurementTime(item.plotTime)) }]);
const chartOption = computed(() => {
    const bp = metric.value === 'BP', rows = plotRows.value;
    const series = (key, name) => ({ name, type: 'scatter', symbolSize: 9, data: rows.map(item => Number(bp ? item.comparisonValue[key] : item.comparisonValue)) });
    return { animation: false, grid: { left: 18, right: 18, top: 35, bottom: 55, containLabel: true }, legend: { bottom: 0 }, tooltip: { trigger: 'axis', renderMode: 'richText' },
        xAxis: { type: 'category', data: rows.map(item => `${item.plotTime.replace('T', ' ')} · #${item.sourceId}`), axisLabel: { hideOverlap: true } },
        yAxis: { type: 'value', scale: true, name: measurementMetrics[metric.value].comparisonUnit }, series: bp ? [series('systolic', t('systolic')), series('diastolic', t('diastolic'))] : [series(null, t(metric.value))] };
});
function openSource(item, event) { if (sourceLocked.value || state.phase !== 'ready') return; opener.value = event?.currentTarget; locator.value = { sourceType: item.sourceType, sourceId: item.sourceId }; }
function refresh() { model.clear(); return model.refresh(); }
function sourceChanged() { refresh(); emit('changed'); }
defineExpose({ refresh, clear: model.clear });
</script>
