<template>
  <section class="trend-panel">
    <header class="trend-head"><div><h2>Vital sign trends</h2><p>View blood pressure and blood glucose changes together. Reference lines help identify unusual trends. </p></div><el-radio-group v-model="mode" size="small"><el-radio-button value="both">All</el-radio-button><el-radio-button value="bp">Blood Pressure</el-radio-button><el-radio-button value="bg">Blood Glucose</el-radio-button></el-radio-group></header>
    <div class="risk-strip"><span>Current consecutive abnormal blood pressure readings <b>{{ bpStreak.current }}</b> times</span><span>Longest consecutive abnormal blood pressure readings <b>{{ bpStreak.max }}</b> times</span><span>Total abnormal records <b>{{ abnormalCount }}</b> items</span></div>
    <div v-if="hasData" class="chart-grid" :class="{'single':mode!=='both'}">
      <article v-if="mode!=='bg'" class="chart-card"><h3>Blood pressure trend</h3><ul v-if="hasBpData" class="chart-reference-legend"><li data-reference-value="140"><i class="reference-line reference-line--upper"></i><span>Reference upper limit: 140 mmHg</span></li></ul><v-chart v-if="hasBpData" class="chart" :option="bpOption" autoresize /><el-empty v-else description="No blood-pressure records available for analysis" /></article>
      <article v-if="mode!=='bp'" class="chart-card"><h3>Blood glucose trend</h3><ul v-if="hasBgData" class="chart-reference-legend"><li data-reference-value="6.1"><i class="reference-line reference-line--upper"></i><span>Fasting reference: 6.1 mmol/L</span></li><li data-reference-value="7.8"><i class="reference-line reference-line--after"></i><span>After-meal reference: 7.8 mmol/L</span></li></ul><v-chart v-if="hasBgData" class="chart" :option="bgOption" autoresize /><el-empty v-else description="No blood-glucose records available for analysis" /></article>
    </div>
    <el-empty v-else :description="emptyDescription" />
  </section>
</template>

<script setup>
import { computed, ref } from 'vue';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components';
import VChart from '@/components/HealthChart.vue';
import { localMeasurementTime } from '@/utils/measurementReadModelPresentation';
use([CanvasRenderer,LineChart,GridComponent,LegendComponent,MarkLineComponent,TooltipComponent]);
const props=defineProps({records:{type:Array,default:()=>[]}});const mode=ref('both');
const sorted=computed(()=>[...props.records].sort((a,b)=>`${a.recordDate} ${a.recordTime||''}`.localeCompare(`${b.recordDate} ${b.recordTime||''}`)));
// Availability controls presentation only; keep source readings and clinical rules intact.
function positiveReading(value) {
  return ['number', 'string'].includes(typeof value) && String(value).trim() !== '' && Number.isFinite(Number(value)) && Number(value) > 0;
}
// 原始图仅按原字段验证完整时间和成对血压，不引入可信投影的权限或修订状态。
const validTime = row => localMeasurementTime(`${row.recordDate}T${row.recordTime}`);
const bpValue = (row, key) => validTime(row) && positiveReading(row.systolicBp) && positiveReading(row.diastolicBp) ? row[key] : null;
const bgValue = row => validTime(row) ? glucoseInMmol(row.bloodGlucose, row.bgUnit) : null;
const hasBpData = computed(() => sorted.value.some(row => bpValue(row, 'systolicBp') != null));
const hasBgData = computed(() => sorted.value.some(row => positiveReading(bgValue(row))));
const hasData = computed(() => mode.value === 'bp' ? hasBpData.value : mode.value === 'bg' ? hasBgData.value : hasBpData.value || hasBgData.value);
const emptyDescription = computed(() => ({ bp: 'No blood-pressure records available for analysis', bg: 'No blood-glucose records available for analysis', both: 'No vital-sign records available for analysis' })[mode.value]);
const labels=computed(()=>sorted.value.map(x=>`${String(x.recordDate || '').slice(5)}${x.recordTime?' '+x.recordTime:''}`));
const base = {
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0 },
  // containLabel reserves the measured date/time height; the remaining inset
  // keeps both native legend rows clear when their names wrap on narrow cards.
  grid: { left: 16, right: 16, top: 30, bottom: 56, containLabel: true },
  xAxis: {
    type: 'category', data: labels.value, boundaryGap: false,
    axisLabel: {
      formatter: value => value.replace(' ', '\n'),
      showMinLabel: true, showMaxLabel: true, hideOverlap: true,
      alignMinLabel: 'left', alignMaxLabel: 'right',
    },
  },
  yAxis: { type: 'value', scale: true },
};
const bpOption=computed(()=>({...base,xAxis:{...base.xAxis,data:labels.value},yAxis:{type:'value',name:'mmHg',min:40},series:[{name:'Systolic Pressure',type:'line',smooth:false,connectNulls:false,data:sorted.value.map(x=>bpValue(x,'systolicBp')),itemStyle:{color:'#ef4444'},markLine:{symbol:'none',label:{show:false,formatter:'Reference upper limit: 140'},data:[{yAxis:140,lineStyle:{type:'dashed',color:'#f59e0b'}}]}},{name:'Diastolic Pressure',type:'line',smooth:false,connectNulls:false,data:sorted.value.map(x=>bpValue(x,'diastolicBp')),itemStyle:{color:'#2563eb'}}]}));
const bgOption=computed(()=>({...base,xAxis:{...base.xAxis,data:labels.value},yAxis:{type:'value',name:'mmol/L',min:0},series:[{name:'Blood Glucose',type:'line',smooth:false,connectNulls:false,data:sorted.value.map(bgValue),itemStyle:{color:'#059669'},markLine:{symbol:'none',label:{show:false},data:[{name:'Fasting reference',yAxis:6.1,lineStyle:{type:'dashed',color:'#f59e0b'}},{name:'After-meal reference',yAxis:7.8,lineStyle:{type:'dashed',color:'#ef4444'}}]}}]}));
function bpAbnormal(x){return x.systolicBp&&(x.systolicBp>140||x.systolicBp<90||x.diastolicBp>90||x.diastolicBp<60)}
// Existing chart reference lines are in mmol/L. Keep source values and units unchanged.
function glucoseInMmol(value, unit) {
  if (!['number', 'string'].includes(typeof value) || String(value).trim() === '' || !Number.isFinite(Number(value))) return null;
  const normalizedUnit = String(unit ?? '').trim().toLowerCase();
  if (normalizedUnit === 'mg/dl') return Number((Number(value) / 18).toFixed(8));
  return normalizedUnit === 'mmol/l' ? Number(value) : null;
}
function bgAbnormal(x){const value=glucoseInMmol(x.bloodGlucose,x.bgUnit);if(value==null)return false;const postMeal=String(x.measurePeriod||'').includes('After Meal');return value<3.9||value>(postMeal?7.8:6.1)}
const abnormalCount=computed(()=>sorted.value.filter(x=>bpAbnormal(x)||bgAbnormal(x)).length);
const bpStreak=computed(()=>{let current=0,max=0;for(const row of sorted.value){if(!row.systolicBp)continue;if(bpAbnormal(row)){current++;max=Math.max(max,current)}else current=0}return{current,max}});
</script>

<style scoped>.trend-panel{margin-bottom:18px;padding:20px;border:1px solid var(--line-strong);border-radius:16px;background:var(--paper)}.trend-head{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.trend-head h2{margin:0 0 6px}.trend-head p{margin:0;color:var(--ink-500);font-size:13px}.risk-strip{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}.risk-strip span{padding:8px 12px;border-radius:999px;background:var(--surface-subtle);color:var(--ink-700);font-size:13px}.chart-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.chart-grid.single{grid-template-columns:1fr}.chart-card{border:1px solid var(--line);border-radius:14px;padding:14px}.chart-card h3{margin:0 0 8px;font-size:15px}.chart{height:320px}@media(max-width:900px){.chart-grid{grid-template-columns:1fr}.trend-head{flex-direction:column}}
.chart-card { min-width: 0; }
.chart-reference-legend { display: flex; flex-wrap: wrap; gap: 8px 16px; padding: 0; margin: 8px 0 12px; list-style: none; color: var(--ink-700); font-size: 12px; line-height: 1.5; }
.chart-reference-legend li { display: flex; align-items: baseline; gap: 7px; min-width: 0; }
.chart-reference-legend span { overflow-wrap: anywhere; }
.reference-line { flex: 0 0 18px; border-top: 2px dashed var(--warning); }
.reference-line--after { border-color: var(--danger); }
@media (max-width: 480px) { .trend-panel { padding: 14px; } .chart-card { padding: 10px; } }
</style>
