<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>健康报告导出</h1>
                <p class="subtitle">生成综合健康数据报告，包括透析趋势、血压分析和营养日记。</p>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <button class="execution-report-choice" type="button" :aria-expanded="showCareExecution" @click="showCareExecution=!showCareExecution">照护执行报告 · 当前事项CSV / 期间事件CSV</button>
          <ExecutionReportPanel v-if="showCareExecution && currentPatientId" :patient-id="currentPatientId"/>
          <p v-else-if="showCareExecution" role="status">请先选择患者</p>
          <el-form v-if="!showCareExecution" :model="form" label-width="110px" ref="formRef" :rules="rules" :disabled="exporting || previewing">
            <el-row :gutter="16">
              <el-col :xs="24" :sm="8">
                <el-form-item label="报告类型" prop="reportType">
                  <el-select v-model="form.reportType" style="width: 100%">
                    <el-option v-for="opt in reportTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item label="导出格式" prop="format">
                  <el-select v-model="form.format" style="width: 100%">
                    <el-option label="HTML 网页" value="html" />
                    <el-option label="PDF 文件" value="pdf" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :xs="24" :sm="8">
                <el-form-item label="时间维度" prop="timeType">
                  <el-select v-model="form.timeType" style="width: 100%">
                    <el-option label="按月" value="month" />
                    <el-option label="按周" value="week" />
                    <el-option label="按年" value="year" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item label="时间范围" prop="timeValue">
                  <el-date-picker
                    v-model="form.timeValue"
                    :type="form.timeType === 'week' ? 'date' : form.timeType"
                    :value-format="form.timeType === 'year' ? 'YYYY' : form.timeType === 'month' ? 'YYYY-MM' : 'YYYY-MM-DD'"
                    placeholder="选择时间"
                    style="width: 100%"
                    :clearable="false"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item>
              <el-button type="primary" @click="handleExport" :loading="exporting">
                <el-icon><Download /></el-icon>生成并下载报告
              </el-button>
              <el-button @click="handlePreview" :loading="previewing">
                <el-icon><View /></el-icon>在线预览
              </el-button>
            </el-form-item>
          </el-form>

          <div v-if="!showCareExecution && previewHtml" style="margin-top: 16px; border: 1px solid var(--line); border-radius: 8px; overflow: hidden;">
            <div style="padding: 12px 16px; background: var(--surface-subtle); border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: 600; color: var(--ink-800);">报告预览</span>
              <el-button type="primary" size="small" @click="handleExport" :disabled="exporting || previewing">下载</el-button>
            </div>
            <iframe sandbox="" title="Report preview" :srcdoc="previewHtml" class="report-preview-frame"></iframe>
          </div>
        </div>
      </div>
    </el-main>
  </el-container>
</template>

<script setup>
import ExecutionReportPanel from '@/components/care-plan/ExecutionReportPanel.vue';
import { ref, reactive, onMounted, onUnmounted, computed, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Download, View } from '@element-plus/icons-vue';
import * as echarts from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart, BarChart, PieChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent, ToolboxComponent } from 'echarts/components';
import { generateReport } from '@/api/healthReport.js';
import { getChartData, getStats } from '@/api/dialysis.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMenuPermission } from '@/composables/useMenuPermission';

echarts.use([CanvasRenderer, LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, ToolboxComponent]);

const { currentPatientId } = useCurrentPatient();
const { hasMenu, hasMenuName } = useMenuPermission();
const showCareExecution = ref(false);
const exporting = ref(false);
const previewing = ref(false);
const previewHtml = ref('');
const formRef = ref(null);
let requestEpoch = 0;
let disposed = false;

function invalidateReport() {
  requestEpoch++;
  previewHtml.value = '';
  exporting.value = false;
  previewing.value = false;
}

function captureReport(format) {
  return {
    patientId: currentPatientId.value,
    reportType: form.reportType,
    format,
    timeType: form.timeType,
    timeValue: form.timeValue
  };
}

function isCurrentReport(epoch, payload) {
  return !disposed && epoch === requestEpoch && !!payload.patientId && payload.patientId === currentPatientId.value;
}

const reportTypeOptions = computed(() => {
  const options = [];
  const hasDialysis = hasMenu('/dialysis');
  const hasBpPattern = hasMenuName('血压模式分析');
  const hasNutrition = hasMenuName('营养日记');
  const hasBpMonitor = hasMenuName('血压血糖记录');

  if (hasDialysis || hasBpPattern || hasNutrition || hasBpMonitor) {
    options.push({ label: hasDialysis ? '综合报告' : '综合报告（不含透析统计）', value: hasDialysis ? 'summary' : 'summary_no_dialysis' });
  }
  if (hasBpPattern) options.push({ label: '血压报告', value: 'bp' });
  if (hasNutrition) options.push({ label: '营养日记报告', value: 'nutrition' });
  if (hasBpMonitor) options.push({ label: '血压血糖报告', value: 'bp_monitor' });
  return options;
});

const form = reactive({
  reportType: '',
  format: 'html',
  timeType: 'month',
  timeValue: ''
});

onMounted(() => {
  if (reportTypeOptions.value.length > 0) {
    form.reportType = reportTypeOptions.value[0].value;
  }
});

const rules = {
  reportType: [{ required: true, message: '请选择报告类型', trigger: 'change' }],
  format: [{ required: true, message: '请选择导出格式', trigger: 'change' }],
  timeType: [{ required: true, message: '请选择时间维度', trigger: 'change' }],
  timeValue: [{ required: true, message: '请选择时间范围', trigger: 'change' }]
};

watch(currentPatientId, invalidateReport, { flush: 'sync' });
watch(form, invalidateReport, { deep: true, flush: 'sync' });
onUnmounted(() => { disposed = true; invalidateReport(); });

const commonChartConfig = {
  animation: false,
  tooltip: { trigger: 'axis', confine: true },
  legend: { bottom: 0, type: 'plain' },
  grid: { left: '3%', right: '4%', bottom: '15%', containLabel: true },
  toolbox: { feature: { saveAsImage: {} } }
};

async function buildReportPayload(format, snapshot = captureReport(format), isCurrent = () => true) {
  const payload = { ...snapshot, format };
  if (payload.reportType === 'summary' && hasMenu('/dialysis')) {
    payload.trendChartImages = await generateTrendChartImages(payload, isCurrent);
  }
  return payload;
}

async function handleExport() {
  if (exporting.value || previewing.value || disposed) return;
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
  if (!formRef.value) return;
  const epoch = ++requestEpoch, snapshot = captureReport(form.format);
  exporting.value = true;
  try {
    try { await formRef.value.validate(); } catch { return; }
    if (!isCurrentReport(epoch, snapshot)) return;
    const payload = await buildReportPayload(snapshot.format, snapshot, () => isCurrentReport(epoch, snapshot));
    if (!isCurrentReport(epoch, snapshot)) return;
    const blob = await generateReport(payload);
    if (!isCurrentReport(epoch, snapshot)) return;
    const url = window.URL.createObjectURL(blob);
    try {
      const link = document.createElement('a');
      link.href = url;
      const ext = snapshot.format === 'pdf' ? 'pdf' : 'html';
      link.download = `健康报告_${snapshot.patientId}.${ext}`;
      link.click();
    } finally { window.URL.revokeObjectURL(url); }
    ElMessage.success(snapshot.format === 'pdf' ? 'PDF报告已下载' : 'HTML报告已下载');
  } catch (e) {
    if (isCurrentReport(epoch, snapshot)) ElMessage.error('报告生成失败');
  } finally { if (isCurrentReport(epoch, snapshot)) exporting.value = false; }
}

async function handlePreview() {
  if (exporting.value || previewing.value || disposed) return;
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
  if (!formRef.value) return;
  const epoch = ++requestEpoch, snapshot = captureReport('html');
  previewing.value = true;
  try {
    try { await formRef.value.validate(); } catch { return; }
    if (!isCurrentReport(epoch, snapshot)) return;
    const payload = await buildReportPayload('html', snapshot, () => isCurrentReport(epoch, snapshot));
    if (!isCurrentReport(epoch, snapshot)) return;
    const blob = await generateReport(payload);
    if (!isCurrentReport(epoch, snapshot)) return;
    const html = await blob.text();
    if (isCurrentReport(epoch, snapshot)) previewHtml.value = html;
  } catch (e) {
    if (isCurrentReport(epoch, snapshot)) ElMessage.error('预览加载失败');
  } finally { if (isCurrentReport(epoch, snapshot)) previewing.value = false; }
}

async function generateTrendChartImages(snapshot = captureReport(form.format), isCurrent = () => true) {
  const [statsRes, chartRes] = await Promise.all([
    getStats(snapshot.timeType, snapshot.timeValue, snapshot.patientId),
    getChartData(snapshot.timeType, snapshot.timeValue, snapshot.patientId)
  ]);
  if (!isCurrent()) return {};
  const stats = statsRes.code === 200 ? (statsRes.data || {}) : {};
  const chart = chartRes.code === 200 ? (chartRes.data || {}) : {};
  const dates = chart.dateList || [];
  if (!dates.length) return {};

  const charts = [
    ['weightOverview', buildWeightOverviewOption(chart), 1240, 360],
    ['onWeight', buildOnWeightOption(chart), 600, 320],
    ['offWeight', buildOffWeightOption(chart), 600, 320],
    ['uf', buildUfOption(chart), 600, 320],
    ['dailyGain', buildDailyGainOption(chart), 600, 320],
    ['bp', buildBpOption(chart), 600, 320],
    ['dehydration', buildDehydrationOption(stats), 600, 320]
  ];
  if ((stats.monthlyStats || []).length) {
    charts.push(['monthly', buildMonthlyOption(stats.monthlyStats), 1240, 360]);
  }

  const images = {};
  for (const [key, option, width, height] of charts) {
    images[key] = renderChartImage(option, width, height);
  }
  return images;
}

function renderChartImage(option, width, height) {
  const el = document.createElement('div');
  el.style.position = 'fixed';
  el.style.left = '-10000px';
  el.style.top = '-10000px';
  el.style.width = `${width}px`;
  el.style.height = `${height}px`;
  el.style.background = '#fff';
  document.body.appendChild(el);
  let chart;
  try {
    chart = echarts.init(el, null, { renderer: 'canvas', width, height });
    chart.setOption(option);
    return chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' });
  } finally {
    try { chart?.dispose(); } finally { document.body.removeChild(el); }
  }
}

function buildWeightOverviewOption(chart) {
  return {
    ...commonChartConfig,
    xAxis: { type: 'category', data: chart.dateList || [], axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: [
      { type: 'value', name: '体重 kg', scale: true },
      { type: 'value', name: '体重增长 kg', min: 0 }
    ],
    series: [
      { name: '透前体重', type: 'line', data: chart.onWeightList || [], smooth: true, itemStyle: { color: '#6366f1' } },
      { name: '透后体重', type: 'line', data: chart.offWeightList || [], smooth: true, itemStyle: { color: '#10b981' } },
      { name: '干体重', type: 'line', data: chart.dryWeightList || [], lineStyle: { type: 'dashed', color: '#64748b' }, symbol: 'none' },
      { name: '体重增长', type: 'bar', yAxisIndex: 1, data: chart.weightGainList || [], barMaxWidth: 18, itemStyle: { color: '#38bdf8', borderRadius: [4, 4, 0, 0] } },
      { name: '3% 阈值', type: 'line', yAxisIndex: 1, data: chart.weight3pctList || [], lineStyle: { type: 'dashed', color: '#f59e0b' }, symbol: 'none' },
      { name: '5% 阈值', type: 'line', yAxisIndex: 1, data: chart.weight5pctList || [], lineStyle: { type: 'dashed', color: '#ef4444' }, symbol: 'none' }
    ]
  };
}

function buildOnWeightOption(chart) {
  return lineOption(chart.dateList, [
    { name: '透前体重', type: 'line', data: chart.onWeightList || [], smooth: true, itemStyle: { color: '#6366f1' } },
    { name: '干体重参考', type: 'line', data: chart.dryWeightList || [], lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' }
  ]);
}

function buildOffWeightOption(chart) {
  return lineOption(chart.dateList, [
    { name: '透后体重', type: 'line', data: chart.offWeightList || [], smooth: true, itemStyle: { color: '#10b981' } },
    { name: '干体重参考', type: 'line', data: chart.dryWeightList || [], lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' }
  ]);
}

function buildUfOption(chart) {
  return lineOption(chart.dateList, [
    { name: '透析间期体重增长', type: 'line', data: chart.weightGainList || [], smooth: true, itemStyle: { color: '#3b82f6' } },
    { name: '超滤量', type: 'line', data: chart.ufAmountList || [], smooth: true, itemStyle: { color: '#10b981' } },
    { name: '3% 阈值', type: 'line', data: chart.weight3pctList || [], lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' },
    { name: '5% 阈值', type: 'line', data: chart.weight5pctList || [], lineStyle: { type: 'dashed', color: '#F56C6C' }, symbol: 'none' }
  ]);
}

function buildDailyGainOption(chart) {
  return {
    ...lineOption(chart.dateList, [
      { name: '日均体重增长', type: 'line', data: chart.dailyWeightGainList || [], smooth: true, areaStyle: { opacity: 0.12 }, itemStyle: { color: '#8b5cf6' } }
    ]),
    yAxis: { type: 'value', name: 'kg/days' }
  };
}

function buildBpOption(chart) {
  const dates = chart.dateList || [];
  return {
    ...lineOption(dates, [
      { name: '收缩压', type: 'line', data: chart.systolicBpList || [], smooth: true, itemStyle: { color: '#ef4444' } },
      { name: '舒张压', type: 'line', data: chart.diastolicBpList || [], smooth: true, itemStyle: { color: '#3b82f6' } },
      { name: '理想收缩压', type: 'line', data: dates.map(() => 130), lineStyle: { type: 'dashed', color: '#67C23A' }, symbol: 'none' },
      { name: '理想舒张压', type: 'line', data: dates.map(() => 80), lineStyle: { type: 'dashed', color: '#67C23A' }, symbol: 'none' }
    ]),
    yAxis: { type: 'value', min: 40 }
  };
}

function buildDehydrationOption(stats) {
  return {
    animation: false,
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, type: 'plain' },
    series: [{
      type: 'pie', radius: ['40%', '70%'], avoidLabelOverlap: false,
      itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}: {c} ({d}%)' },
      data: [
        { value: stats.tooMuchCount || 0, name: '超滤过量', itemStyle: { color: '#ef4444' } },
        { value: stats.insufficientCount || 0, name: '超滤不足', itemStyle: { color: '#f59e0b' } },
        { value: stats.matchCount || 0, name: '超滤达标', itemStyle: { color: '#10b981' } }
      ]
    }]
  };
}

function buildMonthlyOption(monthlyStats) {
  return {
    ...commonChartConfig,
    xAxis: { type: 'category', data: monthlyStats.map(m => m.month) },
    yAxis: { type: 'value' },
    series: [
      { name: '平均透析间期体重增长', type: 'bar', data: monthlyStats.map(m => Number(m.avg_weight_gain || 0).toFixed(2)), itemStyle: { color: '#409EFF' } },
      { name: '平均超滤量', type: 'bar', data: monthlyStats.map(m => Number(m.avg_uf_amount || 0).toFixed(2)), itemStyle: { color: '#67C23A' } }
    ]
  };
}

function lineOption(dates = [], series = []) {
  return {
    ...commonChartConfig,
    xAxis: { type: 'category', data: dates || [], axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: { type: 'value', scale: true },
    series
  };
}
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>
.report-preview-frame {
  width: 100%;
  height: 500px;
  border: none;
}
@media (max-width: 768px) {
  .report-preview-frame {
    height: 360px;
  }
}
</style>

<style scoped>.execution-report-choice{min-height:44px;padding:10px 16px;margin-bottom:16px;max-width:100%;font:inherit;white-space:normal}.execution-report-choice:focus-visible{outline:3px solid var(--care-600)}</style>
