<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Blood Pressure Pattern Analysis</h1>
                <p class="subtitle">analysisBlood Pressurevariability, orthostatic hypotensionBlood PressureandWeightgrowth andBlood Pressure relatedproperty</p>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <div class="toolbar" style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px;">
            <div>
              <el-button type="primary" @click="handleAnalyze" :loading="analyzing">
                <el-icon><DataAnalysis /></el-icon>runanalysis
              </el-button>
              <el-button @click="loadData">
                <el-icon><Refresh /></el-icon>Refresh
              </el-button>
            </div>
            <TimeScopeFilter
              v-model:timeType="timeType"
              v-model:timeValue="timeValue"
              :stack="isMobile"
              @dimension-change="onDimensionChange"
              @date-change="onDateChange"
            />
          </div>

          <!-- latestanalysisresultoverview -->
          <div v-if="currentAnalysis" class="analysis-overview">
            <el-row :gutter="16">
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="Average systolic pressure" :value="currentAnalysis.avgSystolic || 0" suffix="mmHg" />
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="Average diastolic pressure" :value="currentAnalysis.avgDiastolic || 0" suffix="mmHg" />
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="Systolic standard deviation" :value="currentAnalysis.stdDeviation || 0">
                  <template #suffix>
                    <span v-if="currentAnalysis.stdDeviation > 15" style="color: var(--danger); font-size: 12px;"> (slightlylarge)</span>
                  </template>
                </el-statistic>
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="orthostatic hypotensionBlood Pressure" :value="currentAnalysis.orthostaticCount || 0" suffix="times" />
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="lowBlood Pressure(<90)" :value="currentAnalysis.lowBpCount || 0" suffix="times" />
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="Weight-Blood Pressurerelated" :value="currentAnalysis.correlationWeightGainBp || 0" />
              </el-col>
            </el-row>
            <el-alert v-if="currentAnalysis.analysisSummary" :title="currentAnalysis.analysisSummary" type="info" :closable="false" style="margin-top: 12px;" />
          </div>

          <!-- NonedataNotice -->
          <el-empty v-if="!currentAnalysis && !historyList.length" description="No analysis data. Select a time range and run the analysis." />

          <!-- Analysis history -->
          <div v-if="historyList.length" class="list-panel-head" style="margin: -20px -24px 12px; padding: 12px 16px;">
            <div class="list-panel-title">
              <el-icon><TrendCharts /></el-icon>
              <span>Analysis history</span>
              <span class="list-count">{{ historyList.length }} items</span>
            </div>
          </div>
          <el-table v-if="historyList.length" :data="historyList" stripe class="app-data-table">
            <el-table-column prop="Analysis date" label="Analysis date" width="108" />
            <el-table-column label="Time dimension" width="80">
              <template #default="{ row }">{{ timeTypeLabel(row.timeType) }}</template>
            </el-table-column>
            <el-table-column prop="timeValue" label="Week" width="100" />
            <el-table-column label="Average blood pressure" width="130">
              <template #default="{ row }">{{ row.avgSystolic || '-' }}/{{ row.avgDiastolic || '-' }} mmHg</template>
            </el-table-column>
            <el-table-column label="variability(σ)" width="100">
              <template #default="{ row }">
                <span :style="row.stdDeviation > 15 ? 'color: #f56c6c; font-weight: 600;' : ''">{{ row.stdDeviation || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="Abnormal readings" width="130">
              <template #default="{ row }">
                <el-tag v-if="row.lowBpCount > 0" type="warning" size="small">diastolic{{ row.lowBpCount }}times</el-tag>
                <el-tag v-if="row.highBpCount > 0" type="danger" size="small" style="margin-left: 4px;">systolic{{ row.highBpCount }}times</el-tag>
                <el-tag v-if="row.orthostaticCount > 0" type="info" size="small" style="margin-left: 4px;">property{{ row.orthostaticCount }}times</el-tag>
                <span v-if="!row.lowBpCount && !row.highBpCount && !row.orthostaticCount" style="color: var(--success);">Normal</span>
              </template>
            </el-table-column>
            <el-table-column prop="analysisSummary" label="analysissummary" min-width="200" show-overflow-tooltip />
            <el-table-column label="Actions" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showDetail(row)">Details</el-button>
                <el-popconfirm title="Confirm deletion?" @confirm="handleDelete(row.id)">
                  <template #reference><el-button link type="danger" size="small">Delete</el-button></template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-main>

    <!-- Detailsdialog -->
    <el-dialog v-model="detailVisible" title="Blood Pressure Pattern AnalysisDetails" :width="isMobile ? '94%' : '640px'" destroy-on-close>
      <template v-if="detailRecord">
        <el-descriptions :column="isMobile ? 1 : 2" border size="default">
          <el-descriptions-item label="Analysis date">{{ detailRecord.analysisDate }}</el-descriptions-item>
          <el-descriptions-item label="Time dimension">{{ timeTypeLabel(detailRecord.timeType) }}</el-descriptions-item>
          <el-descriptions-item label="Time range">{{ detailRecord.timeValue }}</el-descriptions-item>
          <el-descriptions-item label="Average systolic pressure">{{ detailRecord.avgSystolic }} mmHg</el-descriptions-item>
          <el-descriptions-item label="Average diastolic pressure">{{ detailRecord.avgDiastolic }} mmHg</el-descriptions-item>
          <el-descriptions-item label="Systolic range">{{ detailRecord.minSystolic }}-{{ detailRecord.maxSystolic }} mmHg</el-descriptions-item>
          <el-descriptions-item label="Systolic standard deviation">
            <span :style="detailRecord.stdDeviation > 15 ? 'color: #f56c6c; font-weight: 600;' : ''">
              {{ detailRecord.stdDeviation }}
            </span>
            <span v-if="detailRecord.stdDeviation > 15" style="color: var(--danger); font-size: 12px;"> variabilityrelativelylarge</span>
          </el-descriptions-item>
          <el-descriptions-item label="Average ultrafiltration volume">{{ detailRecord.avgUfAmount }} kg</el-descriptions-item>
          <el-descriptions-item label="Orthostatic hypotension count">
            <el-tag :type="detailRecord.orthostaticCount > 0 ? 'warning' : 'success'" size="small">{{ detailRecord.orthostaticCount }} times</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Low blood pressure count(<90)">
            <el-tag :type="detailRecord.lowBpCount > 0 ? 'warning' : 'success'" size="small">{{ detailRecord.lowBpCount }} times</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="High blood pressure count(>140)">
            <el-tag :type="detailRecord.highBpCount > 0 ? 'danger' : 'success'" size="small">{{ detailRecord.highBpCount }} times</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Weight-gain and blood-pressure correlation">
            <span :style="correlationStyle(detailRecord.correlationWeightGainBp)">
              {{ detailRecord.correlationWeightGainBp }}
            </span>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="detailRecord.analysisSummary" style="margin-top: 16px; padding: 12px 16px; background: var(--surface-subtle); border-radius: 8px; border: 1px solid var(--line);">
          <p style="font-size: 13px; font-weight: 600; color: var(--ink-800); margin-bottom: 8px;">analysissummary</p>
          <p style="font-size: 13px; color: var(--ink-500); line-height: 1.6;">{{ detailRecord.analysisSummary }}</p>
        </div>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { DataAnalysis, TrendCharts, Refresh } from '@element-plus/icons-vue';
import { analyzeBpPattern, listBpPatterns, deleteBpPattern } from '@/api/bpPattern.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMobile } from '@/composables/useMobile';
import TimeScopeFilter from '@/components/TimeScopeFilter.vue';

const { currentPatientId } = useCurrentPatient();
const { isMobile } = useMobile();
const timeType = ref('month');
const timeValue = ref('');
const analyzing = ref(false);
const currentAnalysis = ref(null);
const historyList = ref([]);
const detailVisible = ref(false);
const detailRecord = ref(null);
let patientEpoch = 0;
let analysisEpoch = 0;
let listRequest = 0;
let analysisRequest = 0;
let disposed = false;
const pendingDeletes = new Map();

function captureContext() {
  return { patientId: currentPatientId.value, patientEpoch, analysisEpoch, timeType: timeType.value, timeValue: timeValue.value };
}

function isCurrentContext(context, includeAnalysis = false) {
  return !disposed && !!context.patientId && context.patientId === currentPatientId.value
    && context.patientEpoch === patientEpoch && (!includeAnalysis || context.analysisEpoch === analysisEpoch);
}

function invalidateAnalysis() {
  analysisEpoch++;
  analysisRequest++;
  analyzing.value = false;
}

function clearDetail() {
  detailVisible.value = false;
  detailRecord.value = null;
}

const TIME_TYPE_MAP = { month: 'by Month', week: 'by week', year: 'by Year' };
function timeTypeLabel(v) { return TIME_TYPE_MAP[v] || v; }

function correlationStyle(val) {
  if (!val) return '';
  const abs = Math.abs(val);
  if (abs >= 0.7) return 'color: #f56c6c; font-weight: 600;';
  if (abs >= 0.4) return 'color: #e6a23c; font-weight: 600;';
  return 'color: #67c23a;';
}

function onDimensionChange() { loadData(); }
function onDateChange() { loadData(); }

async function loadData() {
  const request = ++listRequest;
  const context = captureContext();
  const isCurrentList = () => request === listRequest && isCurrentContext(context);
  if (!context.patientId || disposed) { historyList.value = []; currentAnalysis.value = null; return; }
  try {
    const res = await listBpPatterns(context.patientId);
    if (!isCurrentList()) return;
    if (res.code === 200) {
      historyList.value = res.data || [];
      currentAnalysis.value = historyList.value.length > 0 ? historyList.value[0] : null;
    }
  } catch (e) { if (isCurrentList()) console.error(e); }
}

async function handleAnalyze() {
  if (analyzing.value || disposed) return;
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
  if (!timeValue.value) { ElMessage.warning('Select a time range'); return; }
  const context = captureContext();
  const request = ++analysisRequest;
  const isCurrentAnalysis = () => request === analysisRequest && isCurrentContext(context, true);
  analyzing.value = true;
  try {
    const res = await analyzeBpPattern(context.patientId, context.timeType, context.timeValue);
    if (!isCurrentAnalysis()) return;
    if (res.code === 200) { ElMessage.success('analysiscomplete'); loadData(); }
    else ElMessage.error(res.msg || 'analysisfailed');
  } catch (e) { if (isCurrentAnalysis()) ElMessage.error('analysisfailed'); }
  finally { if (isCurrentAnalysis()) analyzing.value = false; }
}

function showDetail(row) {
  if (disposed || !currentPatientId.value || row.patientId !== currentPatientId.value) return;
  detailRecord.value = row;
  detailVisible.value = true;
}

async function handleDelete(id) {
  const row = historyList.value.find(item => item.id === id);
  if (disposed || !currentPatientId.value || !row || row.patientId !== currentPatientId.value || pendingDeletes.has(id)) return;
  const context = captureContext();
  const operation = {};
  pendingDeletes.set(id, operation);
  try {
    const res = await deleteBpPattern(id);
    if (!isCurrentContext(context)) return;
    if (res.code === 200) { ElMessage.success('Deleted successfully'); loadData(); }
    else ElMessage.error(res.msg || 'Failed to delete');
  } catch (e) { if (isCurrentContext(context)) ElMessage.error('Failed to delete'); }
  finally { if (pendingDeletes.get(id) === operation) pendingDeletes.delete(id); }
}

watch(currentPatientId, () => {
  patientEpoch++;
  listRequest++;
  pendingDeletes.clear();
  historyList.value = [];
  currentAnalysis.value = null;
  clearDetail();
  invalidateAnalysis();
  loadData();
}, { flush: 'sync' });
watch([timeType, timeValue], invalidateAnalysis, { flush: 'sync' });
watch(detailVisible, visible => { if (!visible) detailRecord.value = null; }, { flush: 'sync' });
onMounted(() => loadData());
onUnmounted(() => {
  disposed = true;
  patientEpoch++;
  listRequest++;
  pendingDeletes.clear();
  clearDetail();
  invalidateAnalysis();
});
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>
.analysis-overview {
  margin-bottom: 20px;
}
.analysis-overview :deep(.el-statistic__content) {
  font-size: 20px;
}
@media (max-width: 768px) {
  .analysis-overview .el-col {
    margin-bottom: 12px;
  }
  .analysis-overview :deep(.el-statistic__content) {
    font-size: 18px;
  }
}
</style>
