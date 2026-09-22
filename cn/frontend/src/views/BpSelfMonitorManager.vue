<template>
  <el-container class="module-page bp-monitor-page">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header bp-hero">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>血压血糖记录</h1>
                <p class="subtitle">记录日常血压、血糖自我监测数据，跟踪变化趋势。</p>
              </div>
            </div>
            <div class="bp-summary" aria-label="记录概览">
              <div class="summary-item">
                <span class="summary-label">记录数量</span>
                <strong>{{ records.length }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">最近血压</span>
                <strong>{{ latestBpText }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">最近血糖</span>
                <strong>{{ latestBgText }}</strong>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel entry-panel">
          <div class="panel-head">
            <div class="list-panel-title">
              <el-icon><Plus /></el-icon>
              <span>{{ editingId ? '编辑记录' : '新增记录' }}</span>
            </div>
            <el-radio-group v-model="form.measureType" class="measure-switch">
              <el-radio-button value="BP">血压</el-radio-button>
              <el-radio-button value="BG">血糖</el-radio-button>
              <el-radio-button value="BP_BG">血压和血糖</el-radio-button>
            </el-radio-group>
          </div>

          <el-form :model="form" label-position="top" ref="formRef" :rules="formRules" class="entry-form">
            <div class="form-grid form-grid--meta">
              <el-form-item label="记录日期" prop="recordDate">
                <el-date-picker v-model="form.recordDate" value-format="YYYY-MM-DD" :clearable="false" />
              </el-form-item>
              <el-form-item label="记录时间" prop="recordTime">
                <el-time-picker v-model="form.recordTime" format="HH:mm" value-format="HH:mm" placeholder="选填" />
              </el-form-item>
            </div>

            <div class="metric-grid" :class="{ 'metric-grid--single': !showBpFields || !showBgFields }">
              <section v-if="showBpFields" class="metric-section metric-section--bp">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>血压</span>
                </div>
                <div class="field-grid field-grid--two">
                  <el-form-item label="收缩压" prop="systolicBp">
                    <el-input-number v-model="form.systolicBp" :min="50" :max="250" controls-position="right" />
                    <span class="unit-text">mmHg</span>
                  </el-form-item>
                  <el-form-item label="舒张压" prop="diastolicBp">
                    <el-input-number v-model="form.diastolicBp" :min="30" :max="150" controls-position="right" />
                    <span class="unit-text">mmHg</span>
                  </el-form-item>
                </div>
              </section>

              <section v-if="showBgFields" class="metric-section metric-section--bg">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>血糖</span>
                </div>
                <div class="field-grid field-grid--two">
                  <el-form-item label="血糖值" prop="bloodGlucose">
                    <el-input-number v-model="form.bloodGlucose" :min="0" :max="50" :precision="1" :step="0.1" controls-position="right" />
                  </el-form-item>
                  <el-form-item label="单位">
                    <el-select v-model="form.bgUnit">
                      <el-option label="mmol/L" value="mmol/L" />
                      <el-option label="mg/dL" value="mg/dL" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="测量时段">
                    <el-select v-model="form.measurePeriod">
                      <el-option label="空腹" value="Fasting" />
                      <el-option label="餐后 2 小时" value="After Meal2h" />
                      <el-option label="随机" value="Random" />
                    </el-select>
                  </el-form-item>
                </div>
              </section>
            </div>

            <el-form-item label="备注" class="remark-field">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" placeholder="可记录测量场景、餐前餐后及身体感受等。" />
            </el-form-item>

            <div class="form-actions">
              <el-button type="primary" @click="handleSave" :loading="saving">
                <el-icon><Check /></el-icon>{{ editingId ? '更新记录' : '保存记录' }}
              </el-button>
              <el-button @click="resetForm">重置</el-button>
            </div>
          </el-form>
        </div>

        <VitalsTrendPanel :records="records" />
        <div class="content-panel records-panel">
          <div class="toolbar records-toolbar">
            <div class="list-panel-title">
              <el-icon><TrendCharts /></el-icon>
              <span>监测记录</span>
              <span v-if="records.length" class="list-count">共 {{ records.length }} 条</span>
            </div>
            <div class="records-tools">
              <el-radio-group v-model="filterType" @change="loadRecords" class="filter-switch">
                <el-radio-button value="">全部</el-radio-button>
                <el-radio-button value="BP">血压</el-radio-button>
                <el-radio-button value="BG">血糖</el-radio-button>
              </el-radio-group>
              <el-button @click="loadRecords" :loading="loading">
                <el-icon><Refresh /></el-icon>刷新
              </el-button>
            </div>
          </div>

          <div class="table-wrap">
            <el-table :data="records" stripe class="app-data-table app-data-table--list" v-loading="loading" empty-text="暂无记录">
              <el-table-column prop="recordDate" label="日期" width="112" />
              <el-table-column prop="recordTime" label="时间" width="86">
                <template #default="{ row }">{{ row.recordTime || '-' }}</template>
              </el-table-column>
              <el-table-column prop="measureType" label="类型" width="96" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="measureTagType(row.measureType)">{{ measureLabel(row.measureType) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="血压" width="120">
                <template #default="{ row }">
                  <span class="value-text">{{ row.systolicBp && row.diastolicBp ? row.systolicBp + '/' + row.diastolicBp : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="血糖" width="130">
                <template #default="{ row }">
                  <span class="value-text">{{ row.bloodGlucose ? row.bloodGlucose + ' ' + (row.bgUnit || 'mmol/L') : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="measurePeriod" label="时段" width="100" align="center">
                <template #default="{ row }">{{ periodLabel(row.measurePeriod) }}</template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
              <el-table-column label="操作" width="120" align="center" fixed="right">
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button link type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
                    <el-popconfirm title="确认删除这条记录吗？" @confirm="handleDelete(row)">
                      <template #reference>
                        <el-button link type="danger" size="small">删除</el-button>
                      </template>
                    </el-popconfirm>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </div>
    </el-main>
  </el-container>
</template>

<script setup>
import { localDateKey } from '@/utils/familyHealth';
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Check, Plus, Refresh, TrendCharts } from '@element-plus/icons-vue';
import { listBpSelfMonitorRecords, saveBpSelfMonitorRecord, updateBpSelfMonitorRecord, deleteBpSelfMonitorRecord } from '@/api/bpSelfMonitor.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import VitalsTrendPanel from '@/components/VitalsTrendPanel.vue';

const { currentPatientId } = useCurrentPatient();
const loading = ref(false);
const saving = ref(false);
const records = ref([]);
const filterType = ref('');
const editingId = ref(null);
const formRef = ref(null);

const form = reactive({
  recordDate: localDateKey(),
  recordTime: '',
  measureType: 'BP',
  systolicBp: null,
  diastolicBp: null,
  bloodGlucose: null,
  bgUnit: 'mmol/L',
  measurePeriod: 'Fasting',
  remark: ''
});

const showBpFields = computed(() => form.measureType === 'BP' || form.measureType === 'BP_BG');
const showBgFields = computed(() => form.measureType === 'BG' || form.measureType === 'BP_BG');
const latestBpText = computed(() => {
  const row = records.value.find(item => item.systolicBp && item.diastolicBp);
  return row ? `${row.systolicBp}/${row.diastolicBp}` : '-';
});
const latestBgText = computed(() => {
  const row = records.value.find(item => item.bloodGlucose);
  return row ? `${row.bloodGlucose} ${row.bgUnit || 'mmol/L'}` : '-';
});

const formRules = {
  recordDate: [{ required: true, message: '请选择日期', trigger: 'change' }],
  measureType: [{ required: true, message: '请选择类型', trigger: 'change' }]
};

function measureLabel(type) {
  return type === 'BP' ? '血压' : type === 'BG' ? '血糖' : '血压和血糖';
}

function periodLabel(period) {
  return ({ Fasting: '空腹', 'After Meal2h': '餐后 2 小时', Random: '随机' })[period] || period || '-';
}

function measureTagType(type) {
  return type === 'BP' ? 'primary' : type === 'BG' ? 'success' : 'warning';
}

function resetForm() {
  editingId.value = null;
  form.recordDate = localDateKey();
  form.recordTime = '';
  form.measureType = 'BP';
  form.systolicBp = null;
  form.diastolicBp = null;
  form.bloodGlucose = null;
  form.bgUnit = 'mmol/L';
  form.measurePeriod = 'Fasting';
  form.remark = '';
  formRef.value?.resetFields();
}

async function loadRecords() {
  if (!currentPatientId.value) {
    records.value = [];
    return;
  }
  loading.value = true;
  try {
    const res = await listBpSelfMonitorRecords(currentPatientId.value, filterType.value || undefined);
    if (res.code === 200) {
      records.value = res.data || [];
    }
  } catch (e) {
    ElMessage.error('加载记录失败');
  } finally { loading.value = false; }
}

async function handleSave() {
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
  try { await formRef.value.validate(); } catch { return; }
  saving.value = true;
  try {
    const data = {
      patientId: currentPatientId.value,
      ...form,
      id: editingId.value || undefined
    };
    const res = editingId.value
      ? await updateBpSelfMonitorRecord(data)
      : await saveBpSelfMonitorRecord(data);
    if (res.code === 200) {
      ElMessage.success(editingId.value ? '更新成功' : '保存成功');
      resetForm();
      await loadRecords();
    } else {
      ElMessage.error(res.msg || '操作失败');
    }
  } catch (e) {
    ElMessage.error('操作失败');
  } finally { saving.value = false; }
}

function handleEdit(row) {
  editingId.value = row.id;
  Object.assign(form, {
    recordDate: row.recordDate,
    recordTime: row.recordTime,
    measureType: row.measureType,
    systolicBp: row.systolicBp,
    diastolicBp: row.diastolicBp,
    bloodGlucose: row.bloodGlucose,
    bgUnit: row.bgUnit || 'mmol/L',
    measurePeriod: row.measurePeriod || 'Fasting',
    remark: row.remark || ''
  });
}

async function handleDelete(row) {
  try {
    const res = await deleteBpSelfMonitorRecord(row.id);
    if (res.code === 200) {
      ElMessage.success('删除成功');
      await loadRecords();
    }
  } catch (e) {
    ElMessage.error('删除失败');
  }
}

watch(currentPatientId, () => {
  resetForm();
  loadRecords();
});

onMounted(() => { loadRecords(); });
</script>

<style scoped src="@/styles/module-layout.css"></style>
<style scoped>
.bp-monitor-page {
  --bp-accent: #2563eb;
  --bp-soft: #eff6ff;
  --bg-accent: #059669;
  --bg-soft: #ecfdf5;
}

.bp-monitor-page .page-inner {
  max-width: 1400px;
}

.bp-hero {
  background:
    linear-gradient(135deg, rgba(37, 99, 235, 0.08), rgba(5, 150, 105, 0.07)),
    #ffffff;
}

.bp-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(108px, 1fr));
  gap: 10px;
  width: min(460px, 100%);
}

.summary-item {
  min-height: 62px;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.76);
}

.summary-label {
  display: block;
  margin-bottom: 6px;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}

.summary-item strong {
  color: #0f172a;
  font-size: 18px;
  line-height: 1.2;
}

.entry-panel,
.records-panel {
  padding: 0;
  overflow: hidden;
}

.panel-head,
.records-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid #eef2f7;
  background: linear-gradient(180deg, #fbfdff 0%, #ffffff 100%);
}

.entry-form {
  padding: 18px 20px 20px;
}

.form-grid,
.field-grid,
.metric-grid {
  display: grid;
  gap: 14px;
}

.form-grid--meta {
  grid-template-columns: repeat(2, minmax(180px, 1fr));
  max-width: 640px;
  margin-bottom: 14px;
}

.metric-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metric-grid--single {
  grid-template-columns: minmax(0, 1fr);
}

.metric-section {
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
}

.metric-section--bp {
  background: linear-gradient(180deg, var(--bp-soft), #fff 54%);
}

.metric-section--bg {
  background: linear-gradient(180deg, var(--bg-soft), #fff 54%);
}

.metric-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  color: #1e293b;
  font-size: 14px;
  font-weight: 700;
}

.metric-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--bp-accent);
}

.metric-section--bg .metric-dot {
  background: var(--bg-accent);
}

.field-grid--two {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.remark-field {
  margin-top: 14px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 2px;
}

.records-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.value-text {
  color: #0f172a;
  font-weight: 600;
}

.unit-text {
  margin-top: 4px;
  color: #94a3b8;
  font-size: 12px;
}

:deep(.entry-form .el-form-item) {
  margin-bottom: 0;
}

:deep(.entry-form .el-form-item__label) {
  margin-bottom: 6px;
  color: #475569;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.3;
}

:deep(.entry-form .el-input),
:deep(.entry-form .el-input-number),
:deep(.entry-form .el-select),
:deep(.entry-form .el-date-editor),
:deep(.entry-form .el-textarea) {
  width: 100%;
}

:deep(.entry-form .el-input__wrapper),
:deep(.entry-form .el-textarea__inner) {
  box-shadow: 0 0 0 1px #dbe3ef inset;
}

:deep(.measure-switch .el-radio-button__inner),
:deep(.filter-switch .el-radio-button__inner) {
  min-width: 72px;
}

@media (max-width: 980px) {
  .bp-summary,
  .metric-grid,
  .form-grid--meta {
    grid-template-columns: 1fr;
    width: 100%;
    max-width: none;
  }

  .field-grid--two {
    grid-template-columns: 1fr;
  }

  .panel-head,
  .records-toolbar,
  .form-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .measure-switch,
  .filter-switch,
  .records-tools {
    width: 100%;
  }

  :deep(.measure-switch .el-radio-button),
  :deep(.filter-switch .el-radio-button) {
    flex: 1;
  }

  :deep(.measure-switch .el-radio-button__inner),
  :deep(.filter-switch .el-radio-button__inner) {
    width: 100%;
  }
}
</style>
