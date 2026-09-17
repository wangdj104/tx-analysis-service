<template>
  <el-container class="module-page dialysis-manager dry-weight-page">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>{{ pageTitle }}</h1>
                <p class="subtitle">{{ pageSubtitle }}</p>
              </div>
            </div>
          </div>
          <el-tabs v-model="activeMenu" class="dw-sub-tabs">
            <el-tab-pane label="Monthly list" name="list" />
            <el-tab-pane label="trendoverview" name="trend" />
            <el-tab-pane label="changerecord" name="history" />
            <el-tab-pane label="useinstructions" name="guide" />
          </el-tabs>
        </div>

        <div class="content-panel" v-loading="dryWeightLoading">
          <!-- trendoverview -->
          <div v-show="activeMenu === 'trend'" class="dw-trend-panel">
            <div v-if="dryWeightList.length === 0" class="dw-empty-wrap">
              <el-empty description="No data, Please first AddMonthly Dry Weight" />
            </div>
            <template v-else>
              <div class="dw-stat-grid">
                <div class="dw-stat-card">
                  <span class="dw-stat-label">currentreference</span>
                  <span class="dw-stat-value">{{ trendStats.latest }}<small>kg</small></span>
                  <span class="dw-stat-meta">{{ trendStats.latestMonth }}</span>
                </div>
                <div class="dw-stat-card" :class="trendStats.deltaClass">
                  <span class="dw-stat-label">relativelyup Monthchange</span>
                  <span class="dw-stat-value">{{ trendStats.deltaText }}</span>
                  <span class="dw-stat-meta">ringcompared with</span>
                </div>
                <div class="dw-stat-card">
                  <span class="dw-stat-label">historyhighest</span>
                  <span class="dw-stat-value">{{ trendStats.max }}<small>kg</small></span>
                </div>
                <div class="dw-stat-card">
                  <span class="dw-stat-label">historylowest</span>
                  <span class="dw-stat-value">{{ trendStats.min }}<small>kg</small></span>
                </div>
              </div>
              <div class="dw-trend-table-wrap">
                <p class="dw-section-title">by MonthOrder</p>
                <div class="table-wrap table-wrap--compact">
                  <el-table
                    :data="sortedByMonth"
                    class="app-data-table app-data-table--compact"
                    stripe
                    size="small"
                    table-layout="fixed"
                    style="width: 420px"
                  >
                    <el-table-column prop="yearMonth" label="Month" width="140" />
                    <el-table-column label="Dry Weight (kg)" width="140" align="right">
                      <template #default="{ row }">
                        <span class="num-cell emphasis">{{ formatNum(row.dryWeight) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="relativelyup Month" width="140" align="right">
                      <template #default="{ row }">
                        <span :class="monthDeltaClass(row)">{{ monthDeltaText(row) }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>
            </template>
          </div>

          <!-- useinstructions -->
          <div v-show="activeMenu === 'guide'" class="dw-guide-panel">
            <ul class="dw-guide-list">
              <li><strong>Monthly list: </strong>maintaineach MonthDry Weightreference value, Dialysis RecordscalculatetimewillAutomaticguideusecorrespondingMonthdata. </li>
              <li><strong>trendoverview: </strong>ViewDry Weightchangeimagelevel and historyhighlowpoint, assistassessmentfluid removaltargetYesNoreasonable. </li>
              <li><strong>changerecord: </strong>by most recent Updated AtViewmaintainrecord, convenientinverifywhowhattimeadjustpastreference value. </li>
              <li><strong>recommendation: </strong>each MonthDialysisfillpointpropertyassessmentafter updateonetimes; adjustimagelevelrecommendationsingletimesnot exceed 0.5 kg, andobserve 2～4 weekWeight and Blood Pressurechange. </li>
            </ul>
          </div>

          <!-- Monthly list / changerecord -->
          <template v-if="activeMenu === 'list' || activeMenu === 'history'">
            <div class="toolbar">
              <el-button type="primary" @click="handleAddDryWeight">
                <el-icon><Plus /></el-icon>AddMonthly Dry Weight
              </el-button>
              <el-button @click="loadDryWeights">
                <el-icon><Refresh /></el-icon>Refresh
              </el-button>
            </div>

            <div class="list-panel">
              <div class="list-panel-head">
                <div class="list-panel-title">
                  <el-icon><ScaleToOriginal /></el-icon>
                  <span>{{ activeMenu === 'history' ? 'changerecord' : 'Dry WeightMonthly reference' }}</span>
                  <span v-if="tableRows.length" class="list-count">{{ tableRows.length }} items</span>
                </div>
              </div>

              <div v-if="isMobile" class="record-cards dw-cards">
                <article v-for="(row, index) in tableRows" :key="rowKey(row, index)" class="record-card dw-card">
                  <div class="record-card-head">
                    <div class="date-block">
                      <span class="date">{{ row.yearMonth }}</span>
                      <span class="date-week">{{ activeMenu === 'history' ? 'most recent update' : 'Monthly reference' }}</span>
                    </div>
                    <span class="dw-weight-badge">{{ formatNum(row.dryWeight) }} <small>kg</small></span>
                  </div>
                  <p class="dw-updated">updatein {{ formatDateTime(row.updatedAt) }}</p>
                  <div class="record-actions">
                    <el-button type="primary" plain size="small" @click="handleEditDryWeight(row)">Edit</el-button>
                    <el-popconfirm title="Confirm deletion?" @confirm="handleDeleteDryWeight(row.id)">
                      <template #reference>
                        <el-button type="danger" plain size="small">Delete</el-button>
                      </template>
                    </el-popconfirm>
                  </div>
                </article>
                <el-empty v-if="!dryWeightLoading && tableRows.length === 0" description="NoneDry Weightrecord" />
              </div>

              <div v-else class="table-wrap">
                <el-table
                  :data="tableRows"
                  class="app-data-table app-data-table--balanced"
                  stripe
                  table-layout="fixed"
                  style="width: 720px"
                  :empty-text="'NoneDry Weightrecord'"
                  :row-key="rowKey"
                >
                  <el-table-column v-if="dwColVisible('yearMonth')" prop="yearMonth" label="Month" width="148">
                    <template #default="{ row }">
                      <div class="cell-month">
                        <el-icon><Calendar /></el-icon>
                        <span>{{ row.yearMonth }}</span>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="dwColVisible('dryWeight')" label="Dry Weight" width="120" align="right">
                    <template #default="{ row }">
                      <span class="num-cell emphasis">{{ formatNum(row.dryWeight) }} <small class="unit">kg</small></span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="dwColVisible('updatedAt')" label="Updated At" width="168">
                    <template #default="{ row }">
                      <span class="cell-time">{{ formatDateTime(row.updatedAt) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="Actions" width="148" align="center" class-name="col-actions" fixed="right">
                    <template #header>
                      <TableActionHeader v-model="dwVisibleCols" :columns="DW_COLUMN_DEFS" @reset="resetDwColumns" />
                    </template>
                    <template #default="{ row }">
                      <div class="table-actions">
                        <el-button link type="primary" size="small" @click="handleEditDryWeight(row)">Edit</el-button>
                        <el-popconfirm title="Confirm deletion?" @confirm="handleDeleteDryWeight(row.id)">
                          <template #reference>
                            <el-button link type="danger" size="small">Delete</el-button>
                          </template>
                        </el-popconfirm>
                      </div>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </template>
        </div>
      </div>
    </el-main>

    <el-dialog v-model="dryWeightDialogVisible" :title="dryWeightDialogTitle" :width="isMobile ? '92%' : '480px'" destroy-on-close>
      <el-form :model="dryWeightForm" :label-width="isMobile ? 'auto' : '120px'" :label-position="isMobile ? 'top' : 'right'" :rules="dryWeightRules" ref="dryWeightFormRef">
        <el-form-item label="Month" prop="yearMonth">
          <el-date-picker
            v-model="dryWeightForm.yearMonth"
            type="month"
            value-format="YYYY-MM"
            placeholder="selectMonth"
            style="width:100%"
          />
        </el-form-item>
        <el-form-item label="Dry Weight(kg)" prop="dryWeight">
          <el-input-number v-model="dryWeightForm.dryWeight" :precision="2" :step="0.1" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dryWeightDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="submitDryWeightForm">Save</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { listDryWeights, saveDryWeight, deleteDryWeight } from '@/api/dryWeight.js';
import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';
import { useCurrentPatient } from '@/composables/useCurrentPatient';

const DW_COLUMN_DEFS = [
  { key: 'yearMonth', label: 'Month' },
  { key: 'dryWeight', label: 'Dry Weight' },
  { key: 'updatedAt', label: 'Updated At', default: false }
];
const { visibleKeys: dwVisibleCols, isVisible: dwColVisible, resetColumns: resetDwColumns } =
  useTableColumns('dry-weight-list', DW_COLUMN_DEFS);

const activeMenu = ref('list');
const dryWeightLoading = ref(false);
const dryWeightList = ref([]);
const dryWeightDialogVisible = ref(false);
const dryWeightDialogTitle = ref('AddDry Weight');
const dryWeightFormRef = ref(null);
const dryWeightForm = reactive({
  id: null,
  yearMonth: '',
  dryWeight: null
});
const dryWeightRules = {
  yearMonth: [{ required: true, message: 'SelectMonth', trigger: 'change' }],
  dryWeight: [{ required: true, message: 'Enter Dry Weight', trigger: 'blur' }]
};

const isMobile = ref(false);
const { currentPatientId } = useCurrentPatient();

watch(currentPatientId, () => {
  loadDryWeights();
});

const pageTitle = computed(() => {
  const map = {
    list: 'Dry Weight Management',
    trend: 'trendoverview',
    history: 'changerecord',
    guide: 'useinstructions'
  };
  return map[activeMenu.value] || 'Dry Weight Management';
});

const pageSubtitle = computed(() => {
  const map = {
    list: 'managementeach MonthDry Weightreference value, Automaticshoulduseto Dialysis Records',
    trend: 'ViewDry Weightchangetrend and historyhighlowpoint',
    history: 'by most recent Updated AtViewmaintainrecord',
    guide: 'Dry WeightModule featuresinstructions and maintainrecommendation'
  };
  return map[activeMenu.value] || '';
});

const sortedByMonth = computed(() =>
  [...dryWeightList.value].sort((a, b) => (b.yearMonth || '').localeCompare(a.yearMonth || ''))
);

const tableRows = computed(() => {
  if (activeMenu.value === 'history') {
    return [...dryWeightList.value].sort((a, b) => {
      const ta = new Date(a.updatedAt || 0).getTime();
      const tb = new Date(b.updatedAt || 0).getTime();
      return tb - ta;
    });
  }
  return sortedByMonth.value;
});

const trendStats = computed(() => {
  const rows = sortedByMonth.value;
  if (!rows.length) {
    return { latest: '-', latestMonth: '-', deltaText: '-', deltaClass: '', max: '-', min: '-' };
  }
  const weights = rows.map(r => Number(r.dryWeight)).filter(n => !isNaN(n));
  const latest = weights[0];
  const prev = weights[1];
  let deltaText = '-';
  let deltaClass = '';
  if (prev != null && latest != null) {
    const d = latest - prev;
    deltaText = (d >= 0 ? '+' : '') + d.toFixed(2) + ' kg';
    deltaClass = d > 0 ? 'is-up' : d < 0 ? 'is-down' : 'is-flat';
  }
  return {
    latest: latest != null ? latest.toFixed(2) : '-',
    latestMonth: rows[0].yearMonth,
    deltaText,
    deltaClass,
    max: weights.length ? Math.max(...weights).toFixed(2) : '-',
    min: weights.length ? Math.min(...weights).toFixed(2) : '-'
  };
});

function monthDeltaText(row) {
  const rows = sortedByMonth.value;
  const idx = rows.findIndex(r => r.id === row.id || r.yearMonth === row.yearMonth);
  if (idx < 0 || idx >= rows.length - 1) return '-';
  const cur = Number(row.dryWeight);
  const prev = Number(rows[idx + 1].dryWeight);
  if (isNaN(cur) || isNaN(prev)) return '-';
  const d = cur - prev;
  return (d >= 0 ? '+' : '') + d.toFixed(2);
}

function monthDeltaClass(row) {
  const rows = sortedByMonth.value;
  const idx = rows.findIndex(r => r.id === row.id || r.yearMonth === row.yearMonth);
  if (idx < 0 || idx >= rows.length - 1) return 'text-muted';
  const cur = Number(row.dryWeight);
  const prev = Number(rows[idx + 1].dryWeight);
  if (isNaN(cur) || isNaN(prev)) return 'text-muted';
  const d = cur - prev;
  if (d > 0) return 'delta-up';
  if (d < 0) return 'delta-down';
  return 'text-muted';
}

function rowKey(row, index) {
  return row?.id ?? row?.yearMonth ?? `row-${index}`;
}

function formatNum(v) {
  if (v == null || v === '') return '-';
  const n = Number(v);
  if (isNaN(n)) return '-';
  return n.toFixed(2);
}

function formatDateTime(d) {
  if (!d) return '-';
  const date = new Date(d);
  if (isNaN(date.getTime())) return d;
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const h = String(date.getHours()).padStart(2, '0');
  const min = String(date.getMinutes()).padStart(2, '0');
  return `${y}-${m}-${day} ${h}:${min}`;
}

function checkMobile() {
  isMobile.value = window.innerWidth <= 768;
}

async function loadDryWeights() {
  dryWeightLoading.value = true;
  try {
    if (!currentPatientId.value) {
      dryWeightList.value = [];
      return;
    }
    const res = await listDryWeights(currentPatientId.value);
    if (res.code === 200) {
      dryWeightList.value = res.data || [];
    }
  } finally {
    dryWeightLoading.value = false;
  }
}

function handleAddDryWeight() {
  dryWeightDialogTitle.value = 'AddDry Weight';
  Object.assign(dryWeightForm, {
    id: null,
    yearMonth: '',
    dryWeight: null
  });
  dryWeightDialogVisible.value = true;
}

function handleEditDryWeight(row) {
  dryWeightDialogTitle.value = 'EditDry Weight';
  Object.assign(dryWeightForm, {
    id: row.id,
    yearMonth: row.yearMonth,
    dryWeight: row.dryWeight
  });
  dryWeightDialogVisible.value = true;
}

async function handleDeleteDryWeight(id) {
  const res = await deleteDryWeight(id);
  if (res.code === 200) {
    ElMessage.success('Deleted successfully');
    loadDryWeights();
  } else {
    ElMessage.error(res.msg || 'Failed to delete');
  }
}

async function submitDryWeightForm() {
  const valid = await dryWeightFormRef.value.validate().catch(() => false);
  if (!valid) return;

  if (!currentPatientId.value) {
    ElMessage.warning('Select a patient first.');
    return;
  }
  const res = await saveDryWeight({ ...dryWeightForm, patientId: currentPatientId.value });
  if (res.code === 200) {
    ElMessage.success('Saved successfully');
    dryWeightDialogVisible.value = false;
    loadDryWeights();
  } else {
    ElMessage.error(res.msg || 'Operation failed');
  }
}

onMounted(() => {
  checkMobile();
  window.addEventListener('resize', checkMobile);
  loadDryWeights();
});

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile);
});
</script>

<style scoped src="@/styles/module-layout.css"></style>
<style scoped src="./dialysis-manager.css"></style>
<style scoped>
.dry-weight-page .dw-sub-tabs {
  margin-top: 8px;
}

.dry-weight-page .dw-sub-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.dry-weight-page .dw-stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 14px;
  margin-bottom: 20px;
}

.dry-weight-page .dw-stat-card {
  padding: 16px 18px;
  border-radius: 12px;
  background: linear-gradient(145deg, #f8fafc 0%, #fff 100%);
  border: 1px solid #e2e8f0;
  transition: transform 0.2s cubic-bezier(0.25, 0.46, 0.45, 0.94), box-shadow 0.2s ease;
  animation: card-enter 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94) both;
}

.dry-weight-page .dw-stat-card:nth-child(1) { animation-delay: 0.05s; }
.dry-weight-page .dw-stat-card:nth-child(2) { animation-delay: 0.1s; }
.dry-weight-page .dw-stat-card:nth-child(3) { animation-delay: 0.15s; }
.dry-weight-page .dw-stat-card:nth-child(4) { animation-delay: 0.2s; }

.dry-weight-page .dw-stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

@keyframes card-enter {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.dry-weight-page .dw-stat-card.is-up {
  border-color: #fecaca;
  background: linear-gradient(145deg, #fff5f5 0%, #fff 100%);
}

.dry-weight-page .dw-stat-card.is-down {
  border-color: #bbf7d0;
  background: linear-gradient(145deg, #f0fdf4 0%, #fff 100%);
}

.dry-weight-page .dw-stat-label {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 6px;
}

.dry-weight-page .dw-stat-value {
  font-size: 1.5rem;
  font-weight: 700;
  color: #1e293b;
}

.dry-weight-page .dw-stat-value small {
  font-size: 14px;
  font-weight: 500;
  color: #94a3b8;
  margin-left: 2px;
}

.dry-weight-page .dw-stat-meta {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
}

.dry-weight-page .dw-section-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}

.dry-weight-page .dw-empty-wrap {
  padding: 24px 0;
  animation: panel-enter 0.3s ease both;
}

.dry-weight-page .dw-guide-list {
  margin: 0;
  padding-left: 1.2em;
  color: #475569;
  font-size: 14px;
  line-height: 1.85;
}

.dry-weight-page .dw-guide-list li {
  margin-bottom: 10px;
}

.dry-weight-page .delta-up {
  color: #dc2626;
  font-weight: 600;
}

.dry-weight-page .delta-down {
  color: #059669;
  font-weight: 600;
}

.dry-weight-page .text-muted {
  color: #94a3b8;
}

/* limittablegridwidthlevel, avoidcolumn widthpasthoursappearlargetabletnot collaborateadjustemptywhite */
.dry-weight-page .table-wrap {
  display: flex;
  justify-content: center;
}

.dry-weight-page .dw-trend-table-wrap .table-wrap--compact {
  display: flex;
  justify-content: center;
}

/* mobileoptimize */
@media (max-width: 768px) {
  .dry-weight-page .dw-stat-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }

  .dry-weight-page .dw-stat-card {
    padding: 14px 16px;
  }

  .dry-weight-page .dw-stat-value {
    font-size: 1.3rem;
  }

  .dry-weight-page .dw-stat-value small {
    font-size: 13px;
  }

  .dry-weight-page .dw-section-title {
    font-size: 12px;
  }

  .dry-weight-page .dw-guide-list {
    font-size: 13px;
    line-height: 1.75;
  }

  .dry-weight-page .dw-guide-list li {
    margin-bottom: 8px;
  }

  /* cardtabletoptimize */
  .dry-weight-page .dw-cards {
    gap: 10px;
  }

  .dry-weight-page .dw-card .record-card-head {
    flex-direction: column;
    gap: 8px;
  }

  .dry-weight-page .dw-weight-badge {
    font-size: 1.1rem;
  }

  .dry-weight-page .dw-updated {
    font-size: 11px;
  }

  .dry-weight-page .record-actions {
    flex-direction: column;
    gap: 6px;
  }

  .dry-weight-page .record-actions .el-button {
    width: 100%;
  }

  /* trendtablegridoptimize */
  .dry-weight-page .dw-trend-table-wrap .table-wrap--compact {
    justify-content: center;
    overflow-x: auto;
  }

  .dry-weight-page .dw-trend-table-wrap .el-table {
    min-width: 320px;
  }
}

@media (max-width: 375px) {
  .dry-weight-page .dw-stat-grid {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .dry-weight-page .dw-stat-card {
    padding: 12px 14px;
  }

  .dry-weight-page .dw-stat-value {
    font-size: 1.2rem;
  }

  .dry-weight-page .dw-stat-label {
    font-size: 11px;
  }

  .dry-weight-page .dw-stat-meta {
    font-size: 11px;
  }
}
</style>
