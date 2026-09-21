<template>
  <el-container class="module-page nutrition-diary-page">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header nutrition-hero">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Nutrition Diary</h1>
                <p class="subtitle">recordeach Daydietcondition and bodyphysical feeling, trackNutritionhealthchange</p>
              </div>
            </div>
            <div class="nutrition-summary" aria-label="recordoverview">
              <div class="summary-item">
                <span class="summary-label">recordcount</span>
                <strong>{{ records.length }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">most recent Weight</span>
                <strong>{{ latestWeightText }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">most recent appetite</span>
                <strong>{{ latestAppetiteText }}</strong>
              </div>
            </div>
          </div>
        </div>

        <!-- entrypanel -->
        <div class="content-panel entry-panel">
          <div class="panel-head">
            <div class="list-panel-title">
              <el-icon><EditPen /></el-icon>
              <span>{{ editingId ? 'Edit record' : 'Add record' }}</span>
            </div>
          </div>

          <el-form :model="form" label-position="top" ref="formRef" :rules="formRules" class="entry-form">
            <div class="form-grid form-grid--meta">
              <el-form-item label="recordDate" prop="recordDate">
                <el-date-picker v-model="form.recordDate" value-format="YYYY-MM-DD" :clearable="false" />
              </el-form-item>
              <el-form-item label="Weight(kg)">
                <el-input-number v-model="form.bodyWeight" :precision="1" :min="20" :max="300" controls-position="right" />
              </el-form-item>
            </div>

            <div class="metric-grid">
              <section class="metric-section metric-section--appetite">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>appetite</span>
                </div>
                <div class="field-grid field-grid--three">
                  <el-radio-group v-model="form.appetite" class="appetite-radio">
                    <el-radio-button value="GOOD">Good</el-radio-button>
                    <el-radio-button value="NORMAL">Fair</el-radio-button>
                    <el-radio-button value="POOR">difference</el-radio-button>
                  </el-radio-group>
                </div>
              </section>

              <section class="metric-section metric-section--meals">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>threemealcondition</span>
                </div>
                <div class="field-grid field-grid--four">
                  <el-checkbox v-model="form.mealBreakfast" label="Breakfast" />
                  <el-checkbox v-model="form.mealLunch" label="Lunch" />
                  <el-checkbox v-model="form.mealDinner" label="Dinner" />
                  <el-checkbox v-model="form.mealSnack" label="Snack" />
                </div>
              </section>
            </div>

            <div class="form-grid form-grid--meta" style="max-width: 640px;">
              <el-form-item label="Fluid Intake(ml)">
                <el-input-number v-model="form.fluidIntake" :min="0" :max="5000" :step="100" controls-position="right" />
              </el-form-item>
            </div>

            <section class="symptoms-section">
              <div class="metric-title">
                <span class="metric-dot metric-dot--symptom"></span>
                <span>symptomlabel</span>
                <span class="text-muted" style="font-size: 12px; font-weight: 400;"> (can multipleselect) </span>
              </div>
              <div class="symptom-tags">
                <el-check-tag
                  v-for="s in SYMPTOM_OPTIONS"
                  :key="s"
                  :checked="selectedSymptoms.includes(s)"
                  @change="toggleSymptom(s)"
                >{{ s }}</el-check-tag>
              </div>
            </section>

            <el-form-item label="Notes" class="remark-field">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" placeholder="can recorddietDetails, bodyphysical feelingetc." />
            </el-form-item>

            <div class="form-actions">
              <el-button type="primary" @click="handleSave" :loading="saving">
                <el-icon><Check /></el-icon>{{ editingId ? 'Update record' : 'Save record' }}
              </el-button>
              <el-button @click="resetForm">Reset</el-button>
            </div>
          </el-form>
        </div>

        <!-- recordlist -->
        <div class="content-panel records-panel">
          <div class="toolbar records-toolbar">
            <div class="list-panel-title">
              <el-icon><TrendCharts /></el-icon>
              <span>Dayrecordrecord</span>
              <span v-if="records.length" class="list-count">{{ records.length }} items</span>
            </div>
            <div class="records-tools">
              <el-button @click="loadRecords" :loading="loading">
                <el-icon><Refresh /></el-icon>Refresh
              </el-button>
            </div>
          </div>

          <div class="table-wrap">
            <el-table :data="records" stripe class="app-data-table app-data-table--list" v-loading="loading" empty-text="NoneDayrecordrecord">
              <el-table-column prop="recordDate" label="Date" width="112" />
              <el-table-column label="Weight" width="90">
                <template #default="{ row }">
                  <span class="value-text">{{ row.bodyWeight ? row.bodyWeight + ' kg' : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="appetite" width="90" align="center">
                <template #default="{ row }">
                  <el-tag :type="appetiteTagType(row.appetite)" size="small">{{ appetiteLabel(row.appetite) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="threemeal" width="140">
                <template #default="{ row }">
                  <span class="meal-text">{{ mealsText(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="Fluid Intake" width="100">
                <template #default="{ row }">{{ row.fluidIntake ? row.fluidIntake + ' ml' : '-' }}</template>
              </el-table-column>
              <el-table-column label="symptom" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">
                  <template v-if="row.symptoms">
                    <el-tag v-for="s in row.symptoms.split(',')" :key="s" size="small" type="warning" style="margin: 2px;">{{ s }}</el-tag>
                  </template>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="Notes" min-width="160" show-overflow-tooltip />
              <el-table-column label="Actions" width="120" align="center" fixed="right">
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button link type="primary" size="small" @click="handleEdit(row)">Edit</el-button>
                    <el-popconfirm title="Confirm deletionthisitemsrecord?" @confirm="handleDelete(row)">
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
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Check, Refresh, EditPen, TrendCharts } from '@element-plus/icons-vue';
import { listDiaries, saveDiary, updateDiary, deleteDiary } from '@/api/nutritionDiary.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';

const { currentPatientId } = useCurrentPatient();
const loading = ref(false);
const saving = ref(false);
const records = ref([]);
const editingId = ref(null);
const formRef = ref(null);
const selectedSymptoms = ref([]);

const SYMPTOM_OPTIONS = ['fatigue', 'edema', 'nausea', 'itching', 'insomnia', 'appetitedifference', 'bloating', 'muscle cramps'];

const form = reactive({
  recordDate: new Date().toISOString().slice(0, 10),
  bodyWeight: null,
  appetite: '',
  mealBreakfast: false,
  mealLunch: false,
  mealDinner: false,
  mealSnack: false,
  fluidIntake: null,
  symptoms: '',
  remark: ''
});

const formRules = {
  recordDate: [{ required: true, message: 'Select a date', trigger: 'change' }]
};

const latestWeightText = computed(() => {
  const row = records.value.find(r => r.bodyWeight);
  return row ? `${row.bodyWeight} kg` : '-';
});

const latestAppetiteText = computed(() => {
  const row = records.value.find(r => r.appetite);
  return row ? appetiteLabel(row.appetite) : '-';
});

const APPETITE_MAP = { GOOD: 'Good', NORMAL: 'Fair', POOR: 'difference' };
function appetiteLabel(v) { return APPETITE_MAP[v] || v || '-'; }
function appetiteTagType(v) {
  return { GOOD: 'success', NORMAL: 'info', POOR: 'danger' }[v] || 'info';
}

function mealsText(row) {
  const parts = [];
  if (row.mealBreakfast) parts.push('morning');
  if (row.mealLunch) parts.push('noon');
  if (row.mealDinner) parts.push('evening');
  if (row.mealSnack) parts.push('add');
  return parts.length ? parts.join(' ') : '-';
}

function toggleSymptom(name) {
  const idx = selectedSymptoms.value.indexOf(name);
  if (idx >= 0) {
    selectedSymptoms.value.splice(idx, 1);
  } else {
    selectedSymptoms.value.push(name);
  }
  form.symptoms = selectedSymptoms.value.join(',');
}

function resetForm() {
  editingId.value = null;
  form.recordDate = new Date().toISOString().slice(0, 10);
  form.bodyWeight = null;
  form.appetite = '';
  form.mealBreakfast = false;
  form.mealLunch = false;
  form.mealDinner = false;
  form.mealSnack = false;
  form.fluidIntake = null;
  form.symptoms = '';
  form.remark = '';
  selectedSymptoms.value = [];
  formRef.value?.resetFields();
}

async function loadRecords() {
  if (!currentPatientId.value) { records.value = []; return; }
  loading.value = true;
  try {
    const res = await listDiaries(currentPatientId.value);
    if (res.code === 200) records.value = res.data || [];
  } catch (e) {
    ElMessage.error('Failed to load records');
  } finally { loading.value = false; }
}

async function handleSave() {
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
  try { await formRef.value.validate(); } catch { return; }
  saving.value = true;
  try {
    const data = { patientId: currentPatientId.value, ...form, id: editingId.value || undefined };
    const res = editingId.value ? await updateDiary(data) : await saveDiary(data);
    if (res.code === 200) {
      ElMessage.success(editingId.value ? 'Updated successfully' : 'Saved successfully');
      resetForm();
      await loadRecords();
    } else {
      ElMessage.error(res.msg || 'Operation failed');
    }
  } catch (e) {
    ElMessage.error('Operation failed');
  } finally { saving.value = false; }
}

function handleEdit(row) {
  editingId.value = row.id;
  Object.assign(form, {
    recordDate: row.recordDate,
    bodyWeight: row.bodyWeight,
    appetite: row.appetite || '',
    mealBreakfast: !!row.mealBreakfast,
    mealLunch: !!row.mealLunch,
    mealDinner: !!row.mealDinner,
    mealSnack: !!row.mealSnack,
    fluidIntake: row.fluidIntake,
    symptoms: row.symptoms || '',
    remark: row.remark || ''
  });
  selectedSymptoms.value = row.symptoms ? row.symptoms.split(',') : [];
}

async function handleDelete(row) {
  try {
    const res = await deleteDiary(row.id);
    if (res.code === 200) {
      ElMessage.success('Deleted successfully');
      await loadRecords();
    }
  } catch (e) {
    ElMessage.error('Failed to delete');
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
.nutrition-diary-page {
  --diary-accent: #059669;
  --diary-soft: #ecfdf5;
  --meal-accent: #2563eb;
  --meal-soft: #eff6ff;
}

.nutrition-diary-page .page-inner {
  max-width: 1400px;
}

.nutrition-hero {
  background:
    linear-gradient(135deg, rgba(5, 150, 105, 0.08), rgba(37, 99, 235, 0.06)),
    #ffffff;
}

.nutrition-summary {
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

.metric-section {
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
}

.metric-section--appetite {
  background: linear-gradient(180deg, var(--diary-soft), #fff 54%);
}

.metric-section--meals {
  background: linear-gradient(180deg, var(--meal-soft), #fff 54%);
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
  background: var(--diary-accent);
}

.metric-dot--symptom {
  background: #d97706;
}

.metric-section--meals .metric-dot {
  background: var(--meal-accent);
}

.field-grid--three {
  grid-template-columns: repeat(3, 1fr);
}

.field-grid--four {
  grid-template-columns: repeat(4, 1fr);
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
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
}

.value-text {
  color: #0f172a;
  font-weight: 600;
}

.meal-text {
  color: #334155;
  font-weight: 500;
  font-size: 13px;
}

/* symptomlabelrange */
.symptoms-section {
  margin-top: 14px;
}

.symptom-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.symptom-tags :deep(.el-check-tag) {
  border-radius: 8px;
  padding: 6px 14px;
  font-size: 13px;
}

/* tablesingledeeplevelstyle */
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

@media (max-width: 980px) {
  .nutrition-summary,
  .metric-grid,
  .form-grid--meta {
    grid-template-columns: 1fr;
    width: 100%;
    max-width: none;
  }

  .field-grid--three {
    grid-template-columns: 1fr;
  }

  .panel-head,
  .records-toolbar,
  .form-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .records-tools {
    width: 100%;
  }
}

@media (max-width: 768px) {
  .summary-item strong {
    font-size: 16px;
  }
}
</style>
