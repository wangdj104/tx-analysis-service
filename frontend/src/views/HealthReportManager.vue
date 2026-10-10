<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Health report export</h1>
                <p class="subtitle">Generate a comprehensive health report with dialysis trends, blood pressure analysis, and nutrition records</p>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <button class="execution-report-choice" type="button" :aria-expanded="showCareExecution" @click="showCareExecution=!showCareExecution">Care execution report · Current actions CSV / Period events CSV</button>
          <ExecutionReportPanel v-if="showCareExecution && currentPatientId" :patient-id="currentPatientId"/>
          <p v-else-if="showCareExecution" role="status">Select a patient first</p>
          <el-form v-if="!showCareExecution" :model="form" label-width="110px" ref="formRef" :rules="rules" :disabled="exporting || previewing">
            <el-row :gutter="16">
              <el-col :xs="24" :sm="8">
                <el-form-item label="Report type" prop="reportType">
                  <el-select v-model="form.reportType" style="width: 100%">
                    <el-option v-for="opt in reportTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item label="Export format" prop="format">
                  <el-select v-model="form.format" style="width: 100%">
                    <el-option label="HTML page" value="html" />
                    <el-option label="PDF file" value="pdf" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :xs="24" :sm="8">
                <el-form-item label="Time dimension" prop="timeType">
                  <el-select v-model="form.timeType" style="width: 100%">
                    <el-option label="Monthly" value="month" />
                    <el-option label="Weekly" value="week" />
                    <el-option label="Yearly" value="year" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item label="Time range" prop="timeValue">
                  <el-date-picker
                    v-model="form.timeValue"
                    :type="form.timeType === 'week' ? 'date' : form.timeType"
                    :value-format="form.timeType === 'year' ? 'YYYY' : form.timeType === 'month' ? 'YYYY-MM' : 'YYYY-MM-DD'"
                    placeholder="Select a time period"
                    style="width: 100%"
                    :clearable="false"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item>
              <el-button type="primary" @click="handleExport" :loading="exporting">
                <el-icon><Download /></el-icon>Generate and download report
              </el-button>
              <el-button @click="handlePreview" :loading="previewing">
                <el-icon><View /></el-icon>Preview online
              </el-button>
            </el-form-item>
          </el-form>

          <div v-if="!showCareExecution && previewHtml" style="margin-top: 16px; border: 1px solid var(--line); border-radius: 8px; overflow: hidden;">
            <div style="padding: 12px 16px; background: var(--surface-subtle); border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: 600; color: var(--ink-800);">Report preview</span>
              <el-button type="primary" size="small" @click="handleExport" :disabled="exporting || previewing">Download</el-button>
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
  const hasBpPattern = hasMenuName('Blood Pressure Pattern Analysis');
  const hasNutrition = hasMenuName('Nutrition Diary');
  const hasBpMonitor = hasMenuName('Blood GlucoseBlood Pressurerecord');

  if (hasDialysis || hasBpPattern || hasNutrition || hasBpMonitor) {
    options.push({ label: hasDialysis ? 'Comprehensive report' : 'Comprehensive report (excluding dialysis statistics)', value: hasDialysis ? 'summary' : 'summary_no_dialysis' });
  }
  if (hasBpPattern) options.push({ label: 'Blood pressure report', value: 'bp' });
  if (hasNutrition) options.push({ label: 'Nutrition diary report', value: 'nutrition' });
  if (hasBpMonitor) options.push({ label: 'Blood pressure and glucose report', value: 'bp_monitor' });
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
  reportType: [{ required: true, message: 'Select a report type', trigger: 'change' }],
  format: [{ required: true, message: 'Select an export format', trigger: 'change' }],
  timeType: [{ required: true, message: 'Select a time dimension', trigger: 'change' }],
  timeValue: [{ required: true, message: 'Select a time range', trigger: 'change' }]
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
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
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
      link.download = `Health Report_${snapshot.patientId}.${ext}`;
      link.click();
    } finally { window.URL.revokeObjectURL(url); }
    ElMessage.success(snapshot.format === 'pdf' ? 'PDF report downloaded' : 'HTML report downloaded');
  } catch (e) {
    if (isCurrentReport(epoch, snapshot)) ElMessage.error('Failed to generate report');
  } finally { if (isCurrentReport(epoch, snapshot)) exporting.value = false; }
}

async function handlePreview() {
  if (exporting.value || previewing.value || disposed) return;
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
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
    if (isCurrentReport(epoch, snapshot)) ElMessage.error('Failed to load preview');
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
      { type: 'value', name: 'Weight kg', scale: true },
      { type: 'value', name: 'weight gain kg', min: 0 }
    ],
    series: [
      { name: 'Pre-dialysis Weight', type: 'line', data: chart.onWeightList || [], smooth: true, itemStyle: { color: '#6366f1' } },
      { name: 'Post-dialysis Weight', type: 'line', data: chart.offWeightList || [], smooth: true, itemStyle: { color: '#10b981' } },
      { name: 'Dry Weight', type: 'line', data: chart.dryWeightList || [], lineStyle: { type: 'dashed', color: '#64748b' }, symbol: 'none' },
      { name: 'weight gain', type: 'bar', yAxisIndex: 1, data: chart.weightGainList || [], barMaxWidth: 18, itemStyle: { color: '#38bdf8', borderRadius: [4, 4, 0, 0] } },
      { name: '3% threshold', type: 'line', yAxisIndex: 1, data: chart.weight3pctList || [], lineStyle: { type: 'dashed', color: '#f59e0b' }, symbol: 'none' },
      { name: '5% threshold', type: 'line', yAxisIndex: 1, data: chart.weight5pctList || [], lineStyle: { type: 'dashed', color: '#ef4444' }, symbol: 'none' }
    ]
  };
}

function buildOnWeightOption(chart) {
  return lineOption(chart.dateList, [
    { name: 'Pre-dialysis Weight', type: 'line', data: chart.onWeightList || [], smooth: true, itemStyle: { color: '#6366f1' } },
    { name: 'Dry weight reference', type: 'line', data: chart.dryWeightList || [], lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' }
  ]);
}

function buildOffWeightOption(chart) {
  return lineOption(chart.dateList, [
    { name: 'Post-dialysis Weight', type: 'line', data: chart.offWeightList || [], smooth: true, itemStyle: { color: '#10b981' } },
    { name: 'Dry weight reference', type: 'line', data: chart.dryWeightList || [], lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' }
  ]);
}

function buildUfOption(chart) {
  return lineOption(chart.dateList, [
    { name: 'interdialytic weight gain', type: 'line', data: chart.weightGainList || [], smooth: true, itemStyle: { color: '#3b82f6' } },
    { name: 'ultrafiltration volume', type: 'line', data: chart.ufAmountList || [], smooth: true, itemStyle: { color: '#10b981' } },
    { name: '3% threshold', type: 'line', data: chart.weight3pctList || [], lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' },
    { name: '5% threshold', type: 'line', data: chart.weight5pctList || [], lineStyle: { type: 'dashed', color: '#F56C6C' }, symbol: 'none' }
  ]);
}

function buildDailyGainOption(chart) {
  return {
    ...lineOption(chart.dateList, [
      { name: 'Average daily weight gain', type: 'line', data: chart.dailyWeightGainList || [], smooth: true, areaStyle: { opacity: 0.12 }, itemStyle: { color: '#8b5cf6' } }
    ]),
    yAxis: { type: 'value', name: 'kg/day' }
  };
}

function buildBpOption(chart) {
  const dates = chart.dateList || [];
  return {
    ...lineOption(dates, [
      { name: 'systolic', type: 'line', data: chart.systolicBpList || [], smooth: true, itemStyle: { color: '#ef4444' } },
      { name: 'diastolic', type: 'line', data: chart.diastolicBpList || [], smooth: true, itemStyle: { color: '#3b82f6' } },
      { name: 'Target systolic pressure', type: 'line', data: dates.map(() => 130), lineStyle: { type: 'dashed', color: '#67C23A' }, symbol: 'none' },
      { name: 'Target diastolic pressure', type: 'line', data: dates.map(() => 80), lineStyle: { type: 'dashed', color: '#67C23A' }, symbol: 'none' }
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
        { value: stats.tooMuchCount || 0, name: 'Excessive ultrafiltration', itemStyle: { color: '#ef4444' } },
        { value: stats.insufficientCount || 0, name: 'Insufficient ultrafiltration', itemStyle: { color: '#f59e0b' } },
        { value: stats.matchCount || 0, name: 'Ultrafiltration on target', itemStyle: { color: '#10b981' } }
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
      { name: 'Average interdialytic weight gain', type: 'bar', data: monthlyStats.map(m => Number(m.avg_weight_gain || 0).toFixed(2)), itemStyle: { color: '#409EFF' } },
      { name: 'Average ultrafiltration volume', type: 'bar', data: monthlyStats.map(m => Number(m.avg_uf_amount || 0).toFixed(2)), itemStyle: { color: '#67C23A' } }
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
