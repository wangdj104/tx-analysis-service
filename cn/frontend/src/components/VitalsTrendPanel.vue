<template>
  <section class="trend-panel">
    <header class="trend-head"><div><h2>生命体征趋势</h2><p>集中观察血压和血糖变化，参考线仅用于辅助识别异常趋势。</p></div><el-radio-group v-model="mode" size="small"><el-radio-button value="both">全部</el-radio-button><el-radio-button value="bp">血压</el-radio-button><el-radio-button value="bg">血糖</el-radio-button></el-radio-group></header>
    <div class="risk-strip"><span>当前连续血压异常 <b>{{ bpStreak.current }}</b> 次</span><span>最长连续血压异常 <b>{{ bpStreak.max }}</b> 次</span><span>异常记录共 <b>{{ abnormalCount }}</b> 条</span></div>
    <div v-if="hasData" class="chart-grid" :class="{'single':mode!=='both'}">
      <article v-if="mode!=='bg'" class="chart-card"><h3>血压趋势</h3><v-chart class="chart" :option="bpOption" autoresize /></article>
      <article v-if="mode!=='bp'" class="chart-card"><h3>血糖趋势</h3><v-chart class="chart" :option="bgOption" autoresize /></article>
    </div>
  <el-empty v-else description="暂无可用于分析的生命体征记录" />
  </section>
</template>

<script setup>
import { computed, ref } from 'vue';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
use([CanvasRenderer,LineChart,GridComponent,LegendComponent,MarkLineComponent,TooltipComponent]);
const props=defineProps({records:{type:Array,default:()=>[]}});const mode=ref('both');
const sorted=computed(()=>[...props.records].sort((a,b)=>`${a.recordDate} ${a.recordTime||''}`.localeCompare(`${b.recordDate} ${b.recordTime||''}`)));
const hasData=computed(()=>sorted.value.some(x=>x.systolicBp||x.bloodGlucose));
const labels=computed(()=>sorted.value.map(x=>`${x.recordDate.slice(5)}${x.recordTime?' '+x.recordTime:''}`));
const base={tooltip:{trigger:'axis'},legend:{bottom:0},grid:{left:46,right:20,top:30,bottom:54},xAxis:{type:'category',data:labels.value,boundaryGap:false},yAxis:{type:'value',scale:true}};
const bpOption=computed(()=>({...base,xAxis:{...base.xAxis,data:labels.value},yAxis:{type:'value',name:'mmHg',min:40},series:[{name:'收缩压',type:'line',smooth:true,connectNulls:true,data:sorted.value.map(x=>x.systolicBp),itemStyle:{color:'#ef4444'},markLine:{symbol:'none',label:{formatter:'参考上限 140'},data:[{yAxis:140,lineStyle:{type:'dashed',color:'#f59e0b'}}]}},{name:'舒张压',type:'line',smooth:true,connectNulls:true,data:sorted.value.map(x=>x.diastolicBp),itemStyle:{color:'#2563eb'}}]}));
const bgOption=computed(()=>({...base,xAxis:{...base.xAxis,data:labels.value},yAxis:{type:'value',name:'mmol/L',min:0},series:[{name:'血糖',type:'line',smooth:true,connectNulls:true,data:sorted.value.map(x=>x.bloodGlucose),itemStyle:{color:'#059669'},markLine:{symbol:'none',data:[{name:'空腹参考值',yAxis:6.1,lineStyle:{type:'dashed',color:'#f59e0b'}},{name:'餐后参考值',yAxis:7.8,lineStyle:{type:'dashed',color:'#ef4444'}}]}}]}));
function bpAbnormal(x){return x.systolicBp&&(x.systolicBp>140||x.systolicBp<90||x.diastolicBp>90||x.diastolicBp<60)}
function bgAbnormal(x){if(!x.bloodGlucose)return false;return x.measurePeriod==='After Meal2h'?x.bloodGlucose>7.8:x.bloodGlucose>6.1||x.bloodGlucose<3.9}
const abnormalCount=computed(()=>sorted.value.filter(x=>bpAbnormal(x)||bgAbnormal(x)).length);
const bpStreak=computed(()=>{let current=0,max=0;for(const row of sorted.value){if(!row.systolicBp)continue;if(bpAbnormal(row)){current++;max=Math.max(max,current)}else current=0}return{current,max}});
</script>

<style scoped>.trend-panel{margin-bottom:18px;padding:20px;border:1px solid #dbeafe;border-radius:16px;background:#fff}.trend-head{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.trend-head h2{margin:0 0 6px}.trend-head p{margin:0;color:#64748b;font-size:13px}.risk-strip{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}.risk-strip span{padding:8px 12px;border-radius:999px;background:#f8fafc;color:#475569;font-size:13px}.chart-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.chart-grid.single{grid-template-columns:1fr}.chart-card{border:1px solid #e2e8f0;border-radius:14px;padding:14px}.chart-card h3{margin:0 0 8px;font-size:15px}.chart{height:320px}@media(max-width:900px){.chart-grid{grid-template-columns:1fr}.trend-head{flex-direction:column}}</style>
