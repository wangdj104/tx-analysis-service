<template>
  <div class="dashboard">
    <!-- top bar: welcome + Refresh -->
    <section class="dash-top">
      <div class="dash-top__left">
        <p class="dash-top__greet">{{ greetText }}</p>
        <h1 class="dash-top__title">firstpageoverview</h1>
        <p class="dash-top__sub">summarizeThis Monthhealthdata, QuickenterPrimary navigation</p>
      </div>
      <el-button type="primary" round :loading="loading" @click="loadSummary">
        <el-icon><Refresh /></el-icon>Refresh data
      </el-button>
    </section>

    <!-- indicatorcard -->
    <section class="health-hero" :class="`health-hero--${healthRiskTone}`" v-loading="loading">
      <div class="health-hero__main">
        <span class="health-hero__eyebrow">This MonthhealthStatus</span>
        <div class="health-hero__title-row">
          <h2>{{ summary.healthRiskLabel || 'etc.pendingdata' }}</h2>
          <span class="health-hero__badge">{{ healthRiskText }}</span>
        </div>
        <p class="health-hero__sub">{{ primaryHealthHint }}</p>
        <div class="health-hero__chips">
          <span v-for="item in healthHighlights" :key="item" class="health-chip">{{ item }}</span>
        </div>
      </div>
      <div class="health-hero__stats" :style="{ '--health-stat-count': healthStatCards.length || 1 }">
        <div v-for="item in healthStatCards" :key="item.label" class="health-stat">
          <span class="health-stat__value">{{ item.value }}</span>
          <span class="health-stat__label">{{ item.label }}</span>
        </div>
      </div>
    </section>

    <section
      class="metric-grid"
      v-loading="loading"
      :style="{ '--metric-count': visibleMetrics.length || 1 }"
    >
      <div
        v-for="card in visibleMetrics"
        :key="card.key"
        class="metric-card"
        :class="`metric-card--${card.tone}`"
      >
        <div class="metric-card__icon">
          <el-icon :size="22"><component :is="card.icon" /></el-icon>
        </div>
        <div class="metric-card__body">
          <div class="metric-card__val">
            {{ card.display }}
            <span v-if="card.unit" class="metric-card__unit">{{ card.unit }}</span>
          </div>
          <div class="metric-card__lbl">{{ card.label }}</div>
          <div class="metric-card__hint" :class="[card.hintClass, { 'is-empty': !card.hint }]">
            {{ card.hint || '' }}
          </div>
        </div>
      </div>
    </section>

    <!-- maincontentrange: charttable + sidebar -->
    <section class="dash-main" v-loading="loading">
      <article v-if="showTrendChart" class="dash-card dash-card--chart">
        <header class="dash-card__head">
          <div>
            <h2>recent  6 Monthdatatrend</h2>
            <p>by modulestatisticseach Monthentryamount</p>
          </div>
        </header>
        <div class="chart-shell">
          <v-chart
            v-if="trendHasData"
            class="chart-canvas"
            :option="trendChartOption"
            autoresize
          />
          <div v-else class="chart-placeholder">
            <el-icon :size="48" class="chart-placeholder__icon"><TrendCharts /></el-icon>
            <p>Nonetrenddata</p>
            <span>entryMedical Records or medicationrecordafter , will in thisdisplaytrend</span>
          </div>
        </div>
      </article>

      <aside class="dash-aside">
        <article v-if="hasMenu('/medical-record')" class="dash-card dash-card--table">
          <header class="dash-card__head">
            <div>
              <h2><el-icon><WarningFilled /></el-icon> most recent Abnormal Results</h2>
            </div>
            <router-link to="/medical-record" class="dash-card__link">Medical Records →</router-link>
          </header>
          <el-table
            :data="summary.recentAbnormalItems || []"
            size="small"
            class="app-data-table dash-table"
            :max-height="tableMaxHeight"
            empty-text="NoneAbnormal Results, keep it up"
          >
            <el-table-column prop="recordDate" label="Date" width="100" />
            <el-table-column prop="patientName" label="Patient" width="80" show-overflow-tooltip />
            <el-table-column prop="itemName" label="Examinationitem" min-width="100" show-overflow-tooltip />
            <el-table-column label="result" min-width="90">
              <template #default="{ row }">
                <span class="result-abnormal">{{ row.resultValue }}{{ row.unit ? ' ' + row.unit : '' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </article>

        <article v-if="hasMenu('/dialysis')" class="dash-card dash-card--table">
          <header class="dash-card__head">
            <div>
              <h2><el-icon><Document /></el-icon> most recent Dialysis</h2>
            </div>
            <router-link to="/dialysis" class="dash-card__link">ViewAll →</router-link>
          </header>
          <el-table
            :data="summary.recentDialysis || []"
            size="small"
            class="app-data-table dash-table"
            :max-height="280"
            empty-text="NoneDialysis Records"
          >
            <el-table-column prop="recordDate" label="Date" width="100" />
            <el-table-column prop="onWeight" label="pre-dialysis" width="72" />
            <el-table-column prop="offWeight" label="post-dialysis" width="72" />
            <el-table-column prop="dehydrationStatus" label="Status" min-width="88">
              <template #default="{ row }">
                <el-tag size="small" :type="dehydrationTagType(row.dehydrationStatus)">
                  {{ dehydrationText(row.dehydrationStatus) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </article>

      </aside>
    </section>

    <!-- bottomsectionDialysisdoublechart (has DialysisPermissionandmainrangenot placetime)  -->
    <section v-if="hasMenu('/dialysis') && showDialysisRow" class="dash-sub-charts" v-loading="loading">
      <article class="dash-card dash-card--mini-chart">
        <header class="dash-card__head"><h2>This Monthfluid removalStatus</h2></header>
        <v-chart class="chart-canvas chart-canvas--sm" :option="dehydrationChartOption" autoresize />
      </article>
      <article class="dash-card dash-card--mini-chart">
        <header class="dash-card__head"><h2>Dry Weightchange</h2></header>
        <v-chart
          v-if="dryWeightHasData"
          class="chart-canvas chart-canvas--sm"
          :option="dryWeightChartOption"
          autoresize
        />
        <div v-else class="chart-placeholder chart-placeholder--sm">
          <span>NoneDry Weightrecord</span>
        </div>
      </article>
    </section>

    <!-- shortcutentry: horizontaltowardcompact -->
    <section v-if="quickLinks.length" class="quick-bar">
      <span class="quick-bar__label">shortcutentry</span>
      <div class="quick-bar__links">
        <router-link
          v-for="item in quickLinks"
          :key="item.path"
          :to="item.path"
          class="quick-chip"
        >
          <el-icon :size="16"><component :is="item.icon" /></el-icon>
          {{ item.label }}
        </router-link>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, inject, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  User, Document, FirstAidKit, WarningFilled, Box, ScaleToOriginal, Refresh, TrendCharts
} from '@element-plus/icons-vue';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import { getDashboardSummary } from '@/api/dashboard';
import { useCurrentPatient } from '@/composables/useCurrentPatient';

use([
  CanvasRenderer, BarChart, LineChart, PieChart,
  GridComponent, TooltipComponent, LegendComponent
]);

const loading = ref(false);
const summary = ref({});
const userMenuPaths = ref([]);
const injectedMenus = inject('userMenus', null);

const { currentPatientId } = useCurrentPatient();

function syncMenus() {
  if (injectedMenus?.value?.length) {
    userMenuPaths.value = injectedMenus.value;
  } else {
    try {
      const raw = localStorage.getItem('userMenus');
      userMenuPaths.value = raw ? JSON.parse(raw) : [];
    } catch {
      userMenuPaths.value = [];
    }
  }
}

if (injectedMenus) {
  watch(injectedMenus, syncMenus, { immediate: true });
}

function hasMenu(path) {
  return userMenuPaths.value.includes(path);
}

const greetText = computed(() => {
  try {
    const raw = localStorage.getItem('userInfo');
    if (raw) {
      const u = JSON.parse(raw);
      const name = u.realName || u.username;
      if (name) return `yougood, ${name}`;
    }
  } catch { /* ignore */ }
  return 'yougood';
});

const showTrendChart = computed(() =>
  hasMenu('/dialysis') || hasMenu('/medical-record') || hasMenu('/medication')
);

const showDialysisRow = computed(() => hasMenu('/dialysis'));

const tableMaxHeight = computed(() => (hasMenu('/dialysis') ? 220 : 320));

const visibleMetrics = computed(() => {
  const s = summary.value;
  const list = [];
  if (hasMenu('/dialysis')) {
    list.push({
      key: 'matchRate',
      label: 'This MonthUltrafiltration on targetrate',
      display: s.dialysisMatchRateMonth != null ? Number(s.dialysisMatchRateMonth).toFixed(1) : '-',
      unit: s.dialysisMatchRateMonth != null ? '%' : '',
      icon: TrendCharts,
      tone: 'emerald',
      hint: s.latestDialysisStatus ? `most recent onetimes: ${s.latestDialysisStatus}` : null
    });
    list.push({
      key: 'over5',
      label: 'weight gain exceeds 5%',
      display: s.over5pctCountMonth ?? 0,
      icon: WarningFilled,
      tone: (s.over5pctCountMonth || 0) > 0 ? 'amber' : 'sky',
      hint: 'by This MonthDialysis Recordsstatistics'
    });
  }
  if (hasMenu('/system/patient')) {
    list.push({
      key: 'patient',
      label: 'in managePatient',
      display: s.patientCount ?? 0,
      icon: User,
      tone: 'indigo'
    });
  }
  if (hasMenu('/dialysis')) {
    list.push({
      key: 'dialysis',
      label: 'This MonthDialysis',
      display: s.dialysisCountMonth ?? 0,
      icon: Document,
      tone: 'violet'
    });
  }
  if (hasMenu('/medical-record')) {
    list.push({
      key: 'medical',
      label: 'This MonthMedical Records',
      display: s.medicalRecordCountMonth ?? 0,
      icon: FirstAidKit,
      tone: 'emerald'
    });
    list.push({
      key: 'abnormal',
      label: 'Abnormal Resultsitem',
      display: s.abnormalItemCount ?? 0,
      icon: WarningFilled,
      tone: 'amber'
    });
  }
  if (hasMenu('/medication')) {
    list.push({
      key: 'medication',
      label: 'This Monthmedicationrecord',
      display: s.medicationLogCountMonth ?? 0,
      icon: Box,
      tone: 'sky'
    });
  }
  if (hasMenu('/dialysis')) {
    const dw = s.currentDryWeight;
    list.push({
      key: 'dry',
      label: 'currentDry Weight',
      display: dw != null ? dw : '—',
      unit: dw != null ? 'kg' : '',
      icon: ScaleToOriginal,
      tone: 'rose',
      hint: s.dryWeightDelta != null ? `relativelyup Month ${formatDelta(s.dryWeightDelta)}` : null,
      hintClass: deltaClass.value
    });
  }
  return list;
});

const quickLinks = computed(() => {
  const all = [
    { path: '/dialysis', label: 'Dialysis Management', icon: Document },
    { path: '/medical-record', label: 'Medical Records', icon: FirstAidKit },
    { path: '/medication', label: 'Medicationmanagement', icon: Box },
    { path: '/dry-weight', label: 'Dry Weight', icon: ScaleToOriginal },
    { path: '/system/patient', label: 'Patient', icon: User }
  ];
  return all.filter(item => hasMenu(item.path));
});

const deltaClass = computed(() => {
  const d = summary.value.dryWeightDelta;
  if (d == null) return '';
  if (d > 0) return 'is-up';
  if (d < 0) return 'is-down';
  return '';
});

const chartPalette = ['#6366f1', '#10b981', '#f59e0b'];

function sumTrendValues(trend, field) {
  return (trend || []).reduce((acc, t) => acc + (Number(t[field]) || 0), 0);
}

const trendHasData = computed(() => {
  const trend = summary.value.monthlyTrend || [];
  if (hasMenu('/dialysis') && sumTrendValues(trend, 'dialysis') > 0) return true;
  if (hasMenu('/medical-record') && sumTrendValues(trend, 'medical') > 0) return true;
  if (hasMenu('/medication') && sumTrendValues(trend, 'medication') > 0) return true;
  return false;
});

const dryWeightHasData = computed(() => {
  const trend = summary.value.dryWeightTrend || [];
  return trend.some(t => t.weight != null);
});

const healthRiskTone = computed(() => {
  const level = summary.value.healthRiskLevel || 'LOW';
  if (level === 'HIGH') return 'high';
  if (level === 'MEDIUM') return 'medium';
  return 'low';
});

const healthRiskText = computed(() => {
  const map = { HIGH: 'highattention', MEDIUM: 'needobserve', LOW: 'stable' };
  return map[summary.value.healthRiskLevel] || 'stable';
});

const healthHighlights = computed(() => {
  const list = summary.value.healthHighlights || [];
  return list.length ? list.slice(0, 4) : ['Noneenoughenoughdata, firstkeepcontinuousrecord'];
});

const healthStatCards = computed(() => {
  const s = summary.value;
  if (hasMenu('/dialysis')) {
    return [
      { label: 'Ultrafiltration on targetrate', value: formatPercent(s.dialysisMatchRateMonth) },
      { label: 'averageweight gain', value: formatKg(s.avgWeightGainMonth) },
      { label: 'Blood PressureAbnormal', value: s.bpAbnormalCountMonth ?? 0 }
    ];
  }
  const cards = [];
  if (hasMenu('/medical-record')) {
    cards.push(
      { label: 'ExaminationAbnormal', value: s.abnormalItemCount ?? 0 },
      { label: 'This MonthExamination', value: s.medicalRecordCountMonth ?? 0 }
    );
  }
  if (hasMenu('/medication')) {
    cards.push({ label: 'This Monthmedication', value: s.medicationLogCountMonth ?? 0 });
  }
  if (!cards.length) {
    cards.push({ label: 'in managePatient', value: s.patientCount ?? 0 });
  }
  return cards.slice(0, 3);
});

const primaryHealthHint = computed(() => {
  if (!hasMenu('/dialysis')) {
    if (hasMenu('/medical-record') && (summary.value.abnormalItemCount || 0) > 0) {
      return 'Current Accountnot openthroughDialysis Management, firstpagewill excellentfirstsummarizeExaminationAbnormal and medicationrecord. ';
    }
    return 'Current Accountnot openthroughDialysis Management, entryMedical Records and medicationrecordafter , herewillsummarizehealthchange. ';
  }
  const latest = summary.value.latestDialysisStatus;
  if (latest && latest !== 'NoneDialysis Records') {
    return `most recent onetimesDialysis: ${latest}. excellentfirstattentionThis Monthweight gain, Blood Pressure and ExaminationAbnormalchange. `;
  }
  return 'entryDialysis and Examinationdataafter , herewillAutomaticsummarizeThis MonthkeyRisk. ';
});

const trendChartOption = computed(() => {
  const trend = summary.value.monthlyTrend || [];
  const months = trend.map(t => {
    const m = (t.month || '').replace(/^\d{4}-/, '');
    return m ? `${m}Month` : '';
  });
  const series = [];
  if (hasMenu('/dialysis')) {
    series.push({
      name: 'Dialysis',
      type: 'bar',
      barMaxWidth: 22,
      barGap: '30%',
      itemStyle: { borderRadius: [6, 6, 0, 0], color: chartPalette[0] },
      data: trend.map(t => t.dialysis ?? 0)
    });
  }
  if (hasMenu('/medical-record')) {
    series.push({
      name: 'Medical Records',
      type: 'bar',
      barMaxWidth: 22,
      itemStyle: { borderRadius: [6, 6, 0, 0], color: chartPalette[1] },
      data: trend.map(t => t.medical ?? 0)
    });
  }
  if (hasMenu('/medication')) {
    series.push({
      name: 'medicationrecord',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 7,
      yAxisIndex: 0,
      lineStyle: { width: 2.5, color: chartPalette[2] },
      itemStyle: { color: chartPalette[2] },
      data: trend.map(t => t.medication ?? 0)
    });
  }
  const maxVal = Math.max(
    1,
    ...series.flatMap(s => (s.data || []).map(v => Number(v) || 0))
  );
  return {
    color: chartPalette,
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15, 23, 42, 0.92)',
      borderWidth: 0,
      textStyle: { color: '#f8fafc', fontSize: 12 }
    },
    legend: {
      bottom: 4,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: { color: '#64748b', fontSize: 12 }
    },
    grid: { left: 44, right: 20, top: 20, bottom: 52 },
    xAxis: {
      type: 'category',
      data: months,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: maxVal <= 1 ? 4 : undefined,
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f1f5f9', type: 'dashed' } },
      axisLabel: { color: '#94a3b8', fontSize: 11 }
    },
    series
  };
});

const dehydrationChartOption = computed(() => {
  const s = summary.value.dehydrationStats || {};
  const raw = [
    { value: s.tooMuch ?? 0, name: 'Excessive ultrafiltration' },
    { value: s.insufficient ?? 0, name: 'Insufficient ultrafiltration' },
    { value: s.match ?? 0, name: 'Ultrafiltration on target' }
  ];
  const total = raw.reduce((a, b) => a + b.value, 0);
  const data = total > 0 ? raw.filter(d => d.value > 0) : [{ value: 1, name: 'Nonerecord', itemStyle: { color: '#e2e8f0' } }];
  return {
    color: ['#f59e0b', '#ef4444', '#22c55e'],
    tooltip: { trigger: 'item', formatter: total > 0 ? '{b}: {c} times ({d}%)' : 'This MonthNoneDialysis Records' },
    legend: { bottom: 0, textStyle: { color: '#64748b', fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['46%', '72%'],
      center: ['50%', '44%'],
      itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
      label: { fontSize: 11 },
      data
    }]
  };
});

const dryWeightChartOption = computed(() => {
  const trend = summary.value.dryWeightTrend || [];
  return {
    color: ['#6366f1'],
    tooltip: { trigger: 'axis' },
    grid: { left: 44, right: 16, top: 16, bottom: 28 },
    xAxis: {
      type: 'category',
      data: trend.map(t => (t.month || '').replace(/^\d{4}-/, '')),
      axisLine: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      scale: true,
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#94a3b8', fontSize: 10, formatter: '{value}' }
    },
    series: [{
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(99, 102, 241, 0.28)' },
            { offset: 1, color: 'rgba(99, 102, 241, 0.02)' }
          ]
        }
      },
      lineStyle: { width: 2.5 },
      data: trend.map(t => t.weight)
    }]
  };
});

function formatPercent(val) {
  if (val == null || val === '') return '-';
  const n = Number(val);
  return Number.isNaN(n) ? '-' : `${n.toFixed(1)}%`;
}

function formatKg(val) {
  if (val == null || val === '') return '-';
  const n = Number(val);
  return Number.isNaN(n) ? '-' : `${n.toFixed(2)}kg`;
}

function formatDelta(val) {
  if (val == null) return '—';
  const prefix = val > 0 ? '+' : '';
  return `${prefix}${val} kg`;
}

function dehydrationText(status) {
  const map = { TOO_MUCH: 'excessive', INSUFFICIENT: 'not enough', MATCH: 'match' };
  return map[status] || status || '—';
}

function dehydrationTagType(status) {
  const map = { TOO_MUCH: 'warning', INSUFFICIENT: 'danger', MATCH: 'success' };
  return map[status] || 'info';
}

async function loadSummary() {
  loading.value = true;
  try {
    const res = await getDashboardSummary(currentPatientId.value);
    if (res.code === 200) {
      summary.value = res.data || {};
    } else {
      ElMessage.error(res.msg || 'Failed to load');
    }
  } catch (e) {
    ElMessage.error('Failed to load: ' + e.message);
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  syncMenus();
  loadSummary();
});
</script>

<style scoped>
.dashboard {
  max-width: var(--app-content-max, 1340px);
  margin: 0 auto;
  padding: 20px 20px 36px;
  min-height: calc(100vh - 64px);
}

/* —— top bar —— */
.dash-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
  padding: 22px 26px;
  border-radius: var(--app-radius-xl, 14px);
  background: var(--app-brand-gradient-135, linear-gradient(135deg, #4f6af6 0%, #7c3aed 100%));
  color: #fff;
  box-shadow: 0 10px 32px rgb(79 106 246 / 22%);
}

.dash-top__greet {
  font-size: 13px;
  opacity: 0.88;
  margin-bottom: 4px;
}

.dash-top__title {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.02em;
  margin: 0 0 6px;
}

.dash-top__sub {
  font-size: 13px;
  opacity: 0.85;
  margin: 0;
}

/* —— indicatorcard: by actualquantityaveragepointwholerow, avoidright sidekeepwhite —— */
.health-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  align-items: stretch;
  margin-bottom: 18px;
  padding: 20px 22px;
  border-radius: var(--app-radius-xl, 14px);
  border: 1px solid var(--app-border, #e8ecf4);
  background: #fff;
  box-shadow: var(--app-shadow-panel);
  position: relative;
  overflow: hidden;
}

.health-hero::before {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 4px;
  background: #22c55e;
}

.health-hero--medium::before {
  background: #f59e0b;
}

.health-hero--high::before {
  background: #ef4444;
}

.health-hero__main {
  min-width: 0;
}

.health-hero__eyebrow {
  display: inline-flex;
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 700;
  color: #64748b;
}

.health-hero__title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.health-hero__title-row h2 {
  margin: 0;
  font-size: 24px;
  line-height: 1.2;
  color: #0f172a;
}

.health-hero__badge {
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  color: #15803d;
  background: #dcfce7;
}

.health-hero--medium .health-hero__badge {
  color: #b45309;
  background: #fef3c7;
}

.health-hero--high .health-hero__badge {
  color: #b91c1c;
  background: #fee2e2;
}

.health-hero__sub {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 13px;
}

.health-hero__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.health-chip {
  padding: 6px 10px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  color: #475569;
  font-size: 12px;
}

.health-hero__stats {
  display: grid;
  grid-template-columns: repeat(var(--health-stat-count, 3), 112px);
  gap: 10px;
}

.health-stat {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 92px;
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  text-align: center;
}

.health-stat__value {
  font-size: 22px;
  font-weight: 800;
  color: #0f172a;
  font-variant-numeric: tabular-nums;
}

.health-stat__label {
  margin-top: 5px;
  font-size: 12px;
  color: #64748b;
}

.metric-grid {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(var(--metric-count, 4), minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 20px;
}

.metric-card {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  padding: 18px 16px;
  background: var(--app-surface, #fff);
  border-radius: var(--app-radius-lg, 12px);
  border: 1px solid var(--app-border, #e8ecf4);
  box-shadow: var(--app-shadow-panel);
  transition: transform 0.22s cubic-bezier(0.25, 0.46, 0.45, 0.94), box-shadow 0.22s ease;
  cursor: default;
  animation: card-enter 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94) both;
}

.metric-card:nth-child(1) { animation-delay: 0.04s; }
.metric-card:nth-child(2) { animation-delay: 0.08s; }
.metric-card:nth-child(3) { animation-delay: 0.12s; }
.metric-card:nth-child(4) { animation-delay: 0.16s; }
.metric-card:nth-child(5) { animation-delay: 0.20s; }
.metric-card:nth-child(6) { animation-delay: 0.24s; }

@keyframes card-enter {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--app-shadow-lifted);
}

.metric-card__icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.metric-card--indigo .metric-card__icon { background: #eef2ff; color: #4f46e5; }
.metric-card--violet .metric-card__icon { background: #f3e8ff; color: #7c3aed; }
.metric-card--emerald .metric-card__icon { background: #ecfdf5; color: #059669; }
.metric-card--amber .metric-card__icon { background: #fffbeb; color: #d97706; }
.metric-card--sky .metric-card__icon { background: #f0f9ff; color: #0284c7; }
.metric-card--rose .metric-card__icon { background: #fff1f2; color: #e11d48; }

.metric-card__val {
  font-size: 26px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}

.metric-card__unit {
  font-size: 14px;
  font-weight: 500;
  color: #64748b;
  margin-left: 2px;
}

.metric-card__lbl {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.metric-card__hint {
  font-size: 11px;
  margin-top: 6px;
  color: #94a3b8;
}

.metric-card__hint.is-up { color: #dc2626; }
.metric-card__hint.is-down { color: #16a34a; }
.metric-card__hint.is-empty { visibility: hidden; }

/* —— maingridgrid: leftchartrighttable —— */
.dash-main {
  display: grid;
  grid-template-columns: 1fr 380px;
  gap: 16px;
  align-items: start;
  margin-bottom: 16px;
}

.dash-card {
  background: var(--app-surface, #fff);
  border-radius: var(--app-radius-lg, 12px);
  border: 1px solid var(--app-border, #e8ecf4);
  box-shadow: var(--app-shadow-panel);
  overflow: hidden;
  animation: card-enter 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94) both;
}

.dash-main .dash-card:nth-child(1) { animation-delay: 0.08s; }
.dash-main .dash-card:nth-child(2) { animation-delay: 0.14s; }

.dash-sub-charts .dash-card:nth-child(1) { animation-delay: 0.10s; }
.dash-sub-charts .dash-card:nth-child(2) { animation-delay: 0.16s; }

.dash-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 0;
}

.dash-card__head h2 {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  margin: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.dash-card__head p {
  font-size: 12px;
  color: #94a3b8;
  margin: 4px 0 0;
}

.dash-card__link {
  font-size: 13px;
  color: #6366f1;
  text-decoration: none;
  white-space: nowrap;
  padding-top: 2px;
}

.dash-card__link:hover {
  color: #4f46e5;
}

.dash-aside {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dash-card--chart {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.dash-card--chart .chart-shell {
  padding: 8px 12px 16px;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.chart-canvas {
  width: 100%;
  height: 300px;
}

.chart-canvas--sm {
  height: 220px;
  padding: 0 8px 12px;
}

.chart-placeholder {
  flex: 1;
  min-height: 240px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  text-align: center;
  padding: 24px;
}

.chart-placeholder--sm {
  height: 220px;
  font-size: 13px;
}

.chart-placeholder__icon {
  color: #cbd5e1;
  margin-bottom: 12px;
}

.chart-placeholder p {
  font-size: 15px;
  font-weight: 500;
  color: #64748b;
  margin: 0 0 6px;
}

.chart-placeholder span {
  font-size: 12px;
  max-width: 260px;
  line-height: 1.5;
}

.dash-card--table {
  padding-bottom: 12px;
}

.dash-table {
  margin: 12px 14px 0;
  width: calc(100% - 28px);
}

.result-abnormal {
  color: #d97706;
  font-weight: 500;
}

/* Dialysissecondarychartrow */
.dash-sub-charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

.dash-card--mini-chart .dash-card__head {
  padding-bottom: 4px;
}

/* —— shortcutentry —— */
.quick-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 14px 18px;
  background: var(--app-surface, #fff);
  border-radius: var(--app-radius-lg, 12px);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-xs);
  animation: card-enter 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94) 0.18s both;
}

.quick-bar__label {
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
  flex-shrink: 0;
}

.quick-bar__links {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.quick-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 999px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  color: #475569;
  font-size: 13px;
  text-decoration: none;
  transition: all 0.2s cubic-bezier(0.25, 0.46, 0.45, 0.94);
}

.quick-chip:hover {
  background: #eef2ff;
  border-color: #c7d2fe;
  color: #4f46e5;
}

/* —— responseshouldstyle —— */
@media (max-width: 1024px) {
  .dash-main {
    grid-template-columns: 1fr;
  }

  .dash-aside {
    display: grid;
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .dashboard {
    padding: 12px 12px 28px;
  }

  .dash-top {
    flex-direction: column;
    align-items: stretch;
    text-align: center;
  }

  .health-hero {
    grid-template-columns: 1fr;
    padding: 18px 16px;
  }

  .health-hero__stats {
    grid-template-columns: 1fr;
  }

  .dash-sub-charts {
    grid-template-columns: 1fr;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 480px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }

  .quick-bar {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
