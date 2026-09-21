<template>
  <el-container class="module-page nutrition-diary-page">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header nutrition-hero">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>营养日记</h1>
                <p class="subtitle">记录每日饮食和身体感受，跟踪营养健康变化。</p>
              </div>
            </div>
            <div class="nutrition-summary" aria-label="记录概览">
              <div class="summary-item">
                <span class="summary-label">记录数量</span>
                <strong>{{ records.length }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">最近体重</span>
                <strong>{{ latestWeightText }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">最近食欲</span>
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
              <span>{{ editingId ? '编辑记录' : '新增记录' }}</span>
            </div>
          </div>

          <el-form :model="form" label-position="top" ref="formRef" :rules="formRules" class="entry-form">
            <div class="form-grid form-grid--meta">
              <el-form-item label="记录日期" prop="recordDate">
                <el-date-picker v-model="form.recordDate" value-format="YYYY-MM-DD" :clearable="false" />
              </el-form-item>
              <el-form-item label="体重（kg）">
                <el-input-number v-model="form.bodyWeight" :precision="1" :min="20" :max="300" controls-position="right" />
              </el-form-item>
            </div>

            <div class="metric-grid">
              <section class="metric-section metric-section--appetite">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>食欲</span>
                </div>
                <div class="field-grid field-grid--three">
                  <el-radio-group v-model="form.appetite" class="appetite-radio">
                    <el-radio-button value="GOOD">良好</el-radio-button>
                    <el-radio-button value="NORMAL">一般</el-radio-button>
                    <el-radio-button value="POOR">较差</el-radio-button>
                  </el-radio-group>
                </div>
              </section>

              <section class="metric-section metric-section--meals">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>三餐情况</span>
                </div>
                <div class="field-grid field-grid--four">
                  <el-checkbox v-model="form.mealBreakfast" label="早餐" />
                  <el-checkbox v-model="form.mealLunch" label="午餐" />
                  <el-checkbox v-model="form.mealDinner" label="晚餐" />
                  <el-checkbox v-model="form.mealSnack" label="加餐" />
                </div>
              </section>
            </div>

            <div class="form-grid form-grid--meta" style="max-width: 640px;">
              <el-form-item label="液体摄入（ml）">
                <el-input-number v-model="form.fluidIntake" :min="0" :max="5000" :step="100" controls-position="right" />
              </el-form-item>
            </div>

            <section class="symptoms-section">
              <div class="metric-title">
                <span class="metric-dot metric-dot--symptom"></span>
                <span>症状标签</span>
                <span class="text-muted" style="font-size: 12px; font-weight: 400;">（可多选）</span>
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

            <el-form-item label="备注" class="remark-field">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" placeholder="可记录饮食详情和身体感受等。" />
            </el-form-item>

            <div class="form-actions">
              <el-button type="primary" @click="handleSave" :loading="saving">
                <el-icon><Check /></el-icon>{{ editingId ? '更新记录' : '保存记录' }}
              </el-button>
              <el-button @click="resetForm">重置</el-button>
            </div>
          </el-form>
        </div>

        <!-- recordlist -->
        <div class="content-panel records-panel">
          <div class="toolbar records-toolbar">
            <div class="list-panel-title">
              <el-icon><TrendCharts /></el-icon>
              <span>每日记录</span>
              <span v-if="records.length" class="list-count">共 {{ records.length }} 条</span>
            </div>
            <div class="records-tools">
              <el-button @click="loadRecords" :loading="loading">
                <el-icon><Refresh /></el-icon>刷新
              </el-button>
            </div>
          </div>

          <div class="table-wrap">
            <el-table :data="records" stripe class="app-data-table app-data-table--list" v-loading="loading" empty-text="暂无每日记录">
              <el-table-column prop="recordDate" label="日期" width="112" />
              <el-table-column label="体重" width="90">
                <template #default="{ row }">
                  <span class="value-text">{{ row.bodyWeight ? row.bodyWeight + ' kg' : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="食欲" width="90" align="center">
                <template #default="{ row }">
                  <el-tag :type="appetiteTagType(row.appetite)" size="small">{{ appetiteLabel(row.appetite) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="三餐" width="140">
                <template #default="{ row }">
                  <span class="meal-text">{{ mealsText(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="液体摄入" width="100">
                <template #default="{ row }">{{ row.fluidIntake ? row.fluidIntake + ' ml' : '-' }}</template>
              </el-table-column>
              <el-table-column label="症状" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">
                  <template v-if="row.symptoms">
                    <el-tag v-for="s in row.symptoms.split(',')" :key="s" size="small" type="warning" style="margin: 2px;">{{ s }}</el-tag>
                  </template>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
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

const SYMPTOM_OPTIONS = ['乏力', '水肿', '恶心', '瘙痒', '失眠', '食欲差', '腹胀', '肌肉痉挛'];

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
  recordDate: [{ required: true, message: '请选择日期', trigger: 'change' }]
};

const latestWeightText = computed(() => {
  const row = records.value.find(r => r.bodyWeight);
  return row ? `${row.bodyWeight} kg` : '-';
});

const latestAppetiteText = computed(() => {
  const row = records.value.find(r => r.appetite);
  return row ? appetiteLabel(row.appetite) : '-';
});

const APPETITE_MAP = { GOOD: '良好', NORMAL: '一般', POOR: '较差' };
function appetiteLabel(v) { return APPETITE_MAP[v] || v || '-'; }
function appetiteTagType(v) {
  return { GOOD: 'success', NORMAL: 'info', POOR: 'danger' }[v] || 'info';
}

function mealsText(row) {
  const parts = [];
  if (row.mealBreakfast) parts.push('早');
  if (row.mealLunch) parts.push('中');
  if (row.mealDinner) parts.push('晚');
  if (row.mealSnack) parts.push('加餐');
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
    ElMessage.error('加载记录失败');
  } finally { loading.value = false; }
}

async function handleSave() {
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
  try { await formRef.value.validate(); } catch { return; }
  saving.value = true;
  try {
    const data = { patientId: currentPatientId.value, ...form, id: editingId.value || undefined };
    const res = editingId.value ? await updateDiary(data) : await saveDiary(data);
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
