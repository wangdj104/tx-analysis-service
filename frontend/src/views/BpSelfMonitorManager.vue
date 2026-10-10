<template>
  <el-container class="module-page bp-monitor-page">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header bp-hero">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Blood pressure and glucose records</h1>
                <p class="subtitle">Record daily blood pressure and glucose readings to track changes over time</p>
              </div>
            </div>
            <div class="bp-summary" aria-label="Record overview">
              <div class="summary-item">
                <span class="summary-label">Record count</span>
                <strong>{{ records.length }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">Latest blood pressure</span>
                <strong>{{ latestBpText }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">Latest blood glucose</span>
                <strong>{{ latestBgText }}</strong>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel entry-panel">
          <div class="panel-head">
            <div class="list-panel-title">
              <el-icon><Plus /></el-icon>
              <span>{{ editingId ? 'Edit record' : 'Add record' }}</span>
            </div>
            <el-radio-group v-model="form.measureType" :disabled="saving" class="measure-switch">
              <el-radio-button value="BP">Blood Pressure</el-radio-button>
              <el-radio-button value="BG">Blood Glucose</el-radio-button>
              <el-radio-button value="BP_BG">Blood pressure + blood glucose</el-radio-button>
            </el-radio-group>
          </div>

          <el-form :model="form" :disabled="saving" label-position="top" ref="formRef" :rules="formRules" class="entry-form">
            <div class="form-grid form-grid--meta">
              <el-form-item label="Record date" prop="recordDate">
                <el-date-picker v-model="form.recordDate" value-format="YYYY-MM-DD" :clearable="false" />
              </el-form-item>
              <el-form-item label="Record time" prop="recordTime">
                <el-time-picker v-model="form.recordTime" format="HH:mm" value-format="HH:mm" placeholder="Optional" />
              </el-form-item>
            </div>

            <div class="metric-grid" :class="{ 'metric-grid--single': !showBpFields || !showBgFields }">
              <section v-if="showBpFields" class="metric-section metric-section--bp">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>Blood Pressure</span>
                </div>
                <div class="field-grid field-grid--two">
                  <el-form-item label="Systolic Pressure" prop="systolicBp">
                    <el-input-number v-model="form.systolicBp" :min="50" :max="250" controls-position="right" />
                    <span class="unit-text">mmHg</span>
                  </el-form-item>
                  <el-form-item label="Diastolic Pressure" prop="diastolicBp">
                    <el-input-number v-model="form.diastolicBp" :min="30" :max="150" controls-position="right" />
                    <span class="unit-text">mmHg</span>
                  </el-form-item>
                </div>
              </section>

              <section v-if="showBgFields" class="metric-section metric-section--bg">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>Blood Glucose</span>
                </div>
                <div class="field-grid field-grid--two">
                  <el-form-item label="Blood glucose value" prop="bloodGlucose">
                    <el-input-number v-model="form.bloodGlucose" :min="0" :max="String(form.bgUnit).trim().toLowerCase() === 'mg/dl' ? 900 : 50" :precision="1" :step="0.1" controls-position="right" />
                  </el-form-item>
                  <el-form-item label="Unit">
                    <el-select v-model="form.bgUnit">
                      <el-option label="mmol/L" value="mmol/L" />
                      <el-option label="mg/dL" value="mg/dL" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="Measurement period">
                    <el-select v-model="form.measurePeriod">
                      <el-option label="Fasting" value="Fasting" />
                      <el-option label="Two hours after a meal" value="After Meal2h" />
                      <el-option label="Random" value="Random" />
                    </el-select>
                  </el-form-item>
                </div>
              </section>
            </div>

            <el-form-item label="Notes" class="remark-field">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" placeholder="Record the measurement context, meal timing, symptoms, or other observations" />
            </el-form-item>

            <div class="form-actions">
              <el-button type="primary" @click="handleSave" :loading="saving">
                <el-icon><Check /></el-icon>{{ editingId ? 'Update record' : 'Save record' }}
              </el-button>
              <el-button @click="resetForm">Reset</el-button>
            </div>
          </el-form>
        </div>

        <VitalsTrendPanel :records="records" />
        <div class="content-panel records-panel">
          <div class="toolbar records-toolbar">
            <div class="list-panel-title">
              <el-icon><TrendCharts /></el-icon>
              <span>Monitoring records</span>
              <span v-if="records.length" class="list-count">{{ records.length }} items</span>
            </div>
            <div class="records-tools">
              <el-radio-group v-model="filterType" class="filter-switch">
                <el-radio-button value="">All</el-radio-button>
                <el-radio-button value="BP">Blood Pressure</el-radio-button>
                <el-radio-button value="BG">Blood Glucose</el-radio-button>
              </el-radio-group>
              <el-button @click="loadRecords" :loading="loading">
                <el-icon><Refresh /></el-icon>Refresh
              </el-button>
            </div>
          </div>

          <div class="table-wrap">
            <el-table :data="records" stripe class="app-data-table app-data-table--list" v-loading="loading" empty-text="No records">
              <el-table-column prop="recordDate" label="Date" width="112" />
              <el-table-column prop="recordTime" label="Time" width="86">
                <template #default="{ row }">{{ row.recordTime || '-' }}</template>
              </el-table-column>
              <el-table-column prop="measureType" label="type" width="96" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="measureTagType(row.measureType)">{{ measureLabel(row.measureType) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Blood Pressure" width="120">
                <template #default="{ row }">
                  <span class="value-text">{{ row.systolicBp && row.diastolicBp ? row.systolicBp + '/' + row.diastolicBp : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="Blood Glucose" width="130">
                <template #default="{ row }">
                  <span class="value-text">{{ row.bloodGlucose ? row.bloodGlucose + ' ' + (row.bgUnit || 'mmol/L') : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="measurePeriod" label="period" width="100" align="center">
                <template #default="{ row }">{{ row.measurePeriod || '-' }}</template>
              </el-table-column>
              <el-table-column prop="remark" label="Notes" min-width="180" show-overflow-tooltip />
              <el-table-column label="Actions" width="120" align="center" fixed="right">
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button link type="primary" size="small" @click="handleEdit(row)" :disabled="saving">Edit</el-button>
                    <el-popconfirm title="Delete this record?" @confirm="handleDelete(row)">
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
      </div>
    </el-main>
  </el-container>
</template>

<script setup>
import { localDateKey } from '@/utils/familyHealth';
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
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
let patientEpoch = 0;
let editorEpoch = 0;
let listRequest = 0;
let saveRequest = 0;
let disposed = false;
const pendingDeletes = new Map();

function captureContext() {
  return { patientId: currentPatientId.value, patientEpoch, editorEpoch };
}

function isCurrentContext(context, includeEditor = true) {
  return !disposed && !!context.patientId && context.patientId === currentPatientId.value
    && context.patientEpoch === patientEpoch && (!includeEditor || context.editorEpoch === editorEpoch);
}

function invalidateEditor() {
  editorEpoch++;
  saveRequest++;
  saving.value = false;
}

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
  recordDate: [{ required: true, message: 'Select a date', trigger: 'change' }],
  measureType: [{ required: true, message: 'Select a measurement type', trigger: 'change' }],
  systolicBp: [{ validator: measurementValidator('bp', 50, 250, true), trigger: 'change' }],
  diastolicBp: [{ validator: measurementValidator('bp', 30, 150, true), trigger: 'change' }],
  bloodGlucose: [{ validator: measurementValidator('bg'), trigger: 'change' }]
};
function measurementValidator(kind, min, max, integer = false) {
  return (_rule, value, callback) => {
    if (kind === 'bp' ? !showBpFields.value : !showBgFields.value) return callback();
    const number = Number(value);
    const invalid = value == null || String(value).trim() === '' || !Number.isFinite(number)
      || (integer && !Number.isInteger(number))
      || (kind === 'bp' ? number < min || number > max
        : number <= 0 || !['mmol/l', 'mg/dl'].includes(String(form.bgUnit).trim().toLowerCase()) || number > (String(form.bgUnit).trim().toLowerCase() === 'mg/dl' ? 900 : 50));
    callback(invalid ? new Error('Enter a valid measurement for the selected type') : undefined);
  };
}


function measureLabel(type) {
  return type === 'BP' ? 'Blood Pressure' : type === 'BG' ? 'Blood Glucose' : 'Blood pressure + blood glucose';
}

function measureTagType(type) {
  return type === 'BP' ? 'primary' : type === 'BG' ? 'success' : 'warning';
}

function resetForm() {
  invalidateEditor();
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
  // Defaults above own the new draft; resetFields can restore another patient's cached values.
  formRef.value?.clearValidate?.();
}

async function loadRecords() {
  const request = ++listRequest;
  const context = captureContext();
  const type = filterType.value;
  const isCurrentList = () => request === listRequest && isCurrentContext(context, false) && type === filterType.value;
  if (!context.patientId || disposed) { records.value = []; loading.value = false; return; }
  loading.value = true;
  try {
    const res = await listBpSelfMonitorRecords(context.patientId, type || undefined);
    if (!isCurrentList()) return;
    if (res.code === 200) records.value = res.data || [];
  } catch (e) {
    if (isCurrentList()) ElMessage.error('Failed to load records');
  } finally { if (isCurrentList()) loading.value = false; }
}

async function handleSave() {
  if (saving.value || disposed) return;
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
  if (!formRef.value) return;
  const context = captureContext();
  const request = ++saveRequest;
  const isEdit = !!editingId.value;
  const data = { ...form, patientId: context.patientId, id: editingId.value || undefined };
  const isCurrentSave = () => request === saveRequest && isCurrentContext(context);
  saving.value = true;
  try {
    try { await formRef.value.validate(); } catch { return; }
    if (!isCurrentSave()) return;
    const res = isEdit ? await updateBpSelfMonitorRecord(data) : await saveBpSelfMonitorRecord(data);
    if (!isCurrentSave()) return;
    if (res.code === 200) {
      ElMessage.success(isEdit ? 'Updated successfully' : 'Saved successfully');
      resetForm();
      loadRecords();
    } else {
      ElMessage.error(res.msg || 'Operation failed');
    }
  } catch (e) {
    if (isCurrentSave()) ElMessage.error('Operation failed');
  } finally { if (isCurrentSave()) saving.value = false; }
}

function handleEdit(row) {
  if (saving.value || disposed || !currentPatientId.value || row.patientId !== currentPatientId.value) return;
  invalidateEditor();
  editingId.value = row.id;
  Object.assign(form, {
    recordDate: row.recordDate,
    recordTime: row.recordTime,
    measureType: row.measureType,
    systolicBp: row.systolicBp,
    diastolicBp: row.diastolicBp,
    bloodGlucose: row.bloodGlucose,
    bgUnit: String(row.bgUnit ?? '').trim() ? row.bgUnit : 'mmol/L',
    measurePeriod: row.measurePeriod || 'Fasting',
    remark: row.remark || ''
  });
}

async function handleDelete(row) {
  if (disposed || !currentPatientId.value || row.patientId !== currentPatientId.value || pendingDeletes.has(row.id)) return;
  const context = captureContext();
  const operation = {};
  pendingDeletes.set(row.id, operation);
  try {
    const res = await deleteBpSelfMonitorRecord(row.id);
    if (!isCurrentContext(context, false)) return;
    if (res.code === 200) {
      ElMessage.success('Deleted successfully');
      loadRecords();
    }
  } catch (e) {
    if (isCurrentContext(context, false)) ElMessage.error('Failed to delete');
  } finally { if (pendingDeletes.get(row.id) === operation) pendingDeletes.delete(row.id); }
}

watch(currentPatientId, () => {
  patientEpoch++;
  listRequest++;
  pendingDeletes.clear();
  records.value = [];
  loading.value = false;
  resetForm();
  loadRecords();
}, { flush: 'sync' });
watch(filterType, () => {
  listRequest++;
  records.value = [];
  loading.value = false;
  loadRecords();
}, { flush: 'sync' });

onMounted(() => { loadRecords(); });
onUnmounted(() => {
  disposed = true;
  patientEpoch++;
  listRequest++;
  pendingDeletes.clear();
  loading.value = false;
  invalidateEditor();
});
</script>

<style scoped src="@/styles/module-layout.css"></style>
<style scoped>
.bp-monitor-page {
  --bp-accent: var(--info);
  --bp-soft: var(--info-soft);
  --bg-accent: var(--success);
  --bg-soft: var(--success-soft);
}

.bp-monitor-page .page-inner {
  max-width: 1400px;
}

.bp-hero {
  background:
    linear-gradient(135deg, rgba(37, 99, 235, 0.08), rgba(5, 150, 105, 0.07)),
    var(--paper);
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
  border: 1px solid var(--line);
  border-radius: 10px;
  background: color-mix(in srgb, var(--paper) 76%, transparent);
}

.summary-label {
  display: block;
  margin-bottom: 6px;
  color: var(--ink-500);
  font-size: 12px;
  font-weight: 600;
}

.summary-item strong {
  color: var(--ink-950);
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
  border-bottom: 1px solid var(--surface-subtle);
  background: linear-gradient(180deg, var(--surface-subtle) 0%, var(--paper) 100%);
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
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--paper);
}

.metric-section--bp {
  background: linear-gradient(180deg, var(--bp-soft), var(--paper) 54%);
}

.metric-section--bg {
  background: linear-gradient(180deg, var(--bg-soft), var(--paper) 54%);
}

.metric-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  color: var(--ink-950);
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
  color: var(--ink-950);
  font-weight: 600;
}

.unit-text {
  margin-top: 4px;
  color: var(--ink-500);
  font-size: 12px;
}

:deep(.entry-form .el-form-item) {
  margin-bottom: 0;
}

:deep(.entry-form .el-form-item__label) {
  margin-bottom: 6px;
  color: var(--ink-700);
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
  box-shadow: 0 0 0 1px var(--line-strong) inset;
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
