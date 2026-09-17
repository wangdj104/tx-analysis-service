<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Nutrition Assessment</h1>
                <p class="subtitle">assessmentDialysisPatientNutritionStatus, trackSGAscore and biochemistryindicatorchangetrend</p>
              </div>
            </div>
            <div class="right">
              <el-button type="primary" @click="showAddDialog">
                <el-icon><Plus /></el-icon>Addassessment
              </el-button>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <!-- list -->
          <div class="toolbar">
            <div class="list-panel-title">
              <el-icon><FirstAidKit /></el-icon>
              <span>assessmentrecord</span>
              <span v-if="records.length" class="list-count">{{ records.length }} items</span>
            </div>
            <el-button @click="loadData" :loading="loading">
              <el-icon><Refresh /></el-icon>Refresh
            </el-button>
          </div>

          <el-table :data="records" stripe class="app-data-table" v-loading="loading">
            <el-table-column prop="assessmentDate" label="assessmentDate" width="108" />
            <el-table-column label="NutritionStatus" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.nutritionStatus)" size="small">{{ statusLabel(row.nutritionStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sgaGrade" label="SGAgrade" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="sgaTagType(row.sgaGrade)" size="small">{{ row.sgaGrade || '-' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sgaScore" label="SGAscore" width="80" align="center" />
            <el-table-column prop="bmi" label="BMI" width="80" align="center" />
            <el-table-column prop="albumin" label="albumin" width="80">
              <template #default="{ row }">{{ row.albumin ? row.albumin + ' g/L' : '-' }}</template>
            </el-table-column>
            <el-table-column prop="preAlbumin" label="before albumin" width="90">
              <template #default="{ row }">{{ row.preAlbumin ? row.preAlbumin + ' mg/L' : '-' }}</template>
            </el-table-column>
            <el-table-column prop="supplementAdvice" label="add detailsrecommendation" min-width="200" show-overflow-tooltip />
            <el-table-column label="Actions" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showEditDialog(row)">Edit</el-button>
                <el-popconfirm title="Confirm deletion?" @confirm="handleDelete(row.id)">
                  <template #reference><el-button link type="danger" size="small">Delete</el-button></template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-main>

    <!-- Add/Editdialog -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? 'EditNutrition Assessment' : 'AddNutrition Assessment'" :width="isMobile ? '92%' : '680px'" destroy-on-close>
      <el-form :model="form" label-width="110px" ref="formRef" :rules="rules">
        <el-form-item label="assessmentDate" prop="assessmentDate">
          <el-date-picker v-model="form.assessmentDate" type="date" placeholder="selectDate" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="8">
            <el-form-item label="Weight(kg)">
              <el-input-number v-model="form.bodyWeight" :precision="1" :min="0" :max="300" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="Height(cm)">
              <el-input-number v-model="form.height" :precision="1" :min="0" :max="250" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="BMI">
              <el-input v-model="form.bmi" disabled placeholder="Automaticcalculate" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :xs="12" :sm="6">
            <el-form-item label="SGAscore">
              <el-input-number v-model="form.sgaScore" :min="1" :max="7" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="albumin(g/L)">
              <el-input-number v-model="form.albumin" :precision="1" :min="0" :max="100" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="before albumin(mg/L)">
              <el-input-number v-model="form.preAlbumin" :precision="0" :min="0" :max="500" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="fluidintake(ml)">
              <el-input-number v-model="form.fluidIntake" :min="0" :max="5000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :xs="12" :sm="6">
            <el-form-item label="Proteinintake(g)">
              <el-input-number v-model="form.totalProteinIntake" :precision="1" :min="0" :max="200" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="Caloriesintake(kcal)">
              <el-input-number v-model="form.dailyCalorieIntake" :precision="0" :min="0" :max="5000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="potassiumintake(mg)">
              <el-input-number v-model="form.dailyPotassiumIntake" :precision="0" :min="0" :max="5000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="phosphorusintake(mg)">
              <el-input-number v-model="form.dailyPhosphorusIntake" :precision="0" :min="0" :max="5000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="Notes">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="Notesinformation" />
        </el-form-item>

        <!-- pre-calculateresult -->
        <div v-if="previewResult" class="preview-result">
          <p class="preview-title">pre-calculateresult</p>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="8">
              <span class="preview-label">BMI: </span>
              <span class="preview-value">{{ previewResult.bmi || '-' }}</span>
            </el-col>
            <el-col :xs="24" :sm="8">
              <span class="preview-label">NutritionStatus: </span>
              <el-tag :type="statusTagType(previewResult.nutritionStatus)" size="small">{{ statusLabel(previewResult.nutritionStatus) }}</el-tag>
            </el-col>
            <el-col :xs="24" :sm="8">
              <span class="preview-label">SGAgrade: </span>
              <el-tag :type="sgaTagType(previewResult.sgaGrade)" size="small">{{ previewResult.sgaGrade || '-' }}</el-tag>
            </el-col>
          </el-row>
          <p v-if="previewResult.supplementAdvice" class="preview-advice">{{ previewResult.supplementAdvice }}</p>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">Cancel</el-button>
        <el-button type="info" @click="handlePreview" :loading="previewLoading">pre-calculate</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">Save</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Plus, Refresh, FirstAidKit } from '@element-plus/icons-vue';
import { listAssessments, saveAssessment, calculateNutrition, deleteAssessment } from '@/api/nutrition.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMobile } from '@/composables/useMobile';

const { currentPatientId } = useCurrentPatient();
const { isMobile } = useMobile();
const records = ref([]);
const loading = ref(false);
const dialogVisible = ref(false);
const isEdit = ref(false);
const saving = ref(false);
const previewLoading = ref(false);
const previewResult = ref(null);
const formRef = ref(null);

const form = reactive({
  id: null, patientId: null, assessmentDate: '',
  bodyWeight: null, height: null, bmi: null,
  sgaScore: null, sgaGrade: null,
  albumin: null, preAlbumin: null,
  totalProteinIntake: null, dailyCalorieIntake: null,
  dailyPotassiumIntake: null, dailyPhosphorusIntake: null,
  fluidIntake: null,
  nutritionStatus: null, supplementAdvice: null, remark: ''
});

const rules = {
  assessmentDate: [{ required: true, message: 'SelectassessmentDate', trigger: 'change' }]
};

const STATUS_MAP = { GOOD: 'Good', AT_RISK: 'has Risk', DEFICIENT: 'deficient' };
const SGA_MAP = { A: 'Good', B: 'mildModerateNutritionadverse', C: 'severeNutritionadverse' };

function statusLabel(v) { return STATUS_MAP[v] || v; }
function statusTagType(v) { return { GOOD: 'success', AT_RISK: 'warning', DEFICIENT: 'danger' }[v] || 'info'; }
function sgaTagType(v) { return { A: 'success', B: 'warning', C: 'danger' }[v] || 'info'; }

async function loadData() {
  if (!currentPatientId.value) { records.value = []; return; }
  loading.value = true;
  try {
    const res = await listAssessments(currentPatientId.value);
    if (res.code === 200) records.value = res.data || [];
  } catch (e) { console.error(e); }
  finally { loading.value = false; }
}

function showAddDialog() {
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
  isEdit.value = false;
  previewResult.value = null;
  Object.assign(form, {
    id: null, patientId: currentPatientId.value, assessmentDate: '',
    bodyWeight: null, height: null, bmi: null,
    sgaScore: null, sgaGrade: null,
    albumin: null, preAlbumin: null,
    totalProteinIntake: null, dailyCalorieIntake: null,
    dailyPotassiumIntake: null, dailyPhosphorusIntake: null,
    fluidIntake: null,
    nutritionStatus: null, supplementAdvice: null, remark: ''
  });
  dialogVisible.value = true;
}

function showEditDialog(row) {
  isEdit.value = true;
  previewResult.value = null;
  Object.assign(form, { ...row });
  dialogVisible.value = true;
}

async function handlePreview() {
  previewLoading.value = true;
  try {
    const res = await calculateNutrition({ ...form, patientId: currentPatientId.value });
    if (res.code === 200) {
      previewResult.value = res.data;
      form.bmi = res.data.bmi;
      form.nutritionStatus = res.data.nutritionStatus;
      form.sgaGrade = res.data.sgaGrade;
      form.supplementAdvice = res.data.supplementAdvice;
    }
  } catch (e) { ElMessage.error('Calculation failed'); }
  finally { previewLoading.value = false; }
}

async function handleSave() {
  try { await formRef.value.validate(); } catch { return; }
  saving.value = true;
  try {
    form.patientId = currentPatientId.value;
    const res = await saveAssessment(form);
    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      dialogVisible.value = false;
      loadData();
    } else { ElMessage.error(res.msg || 'Failed to save'); }
  } catch (e) { ElMessage.error('Failed to save'); }
  finally { saving.value = false; }
}

async function handleDelete(id) {
  try {
    const res = await deleteAssessment(id);
    if (res.code === 200) { ElMessage.success('Deleted successfully'); loadData(); }
    else { ElMessage.error(res.msg || 'Failed to delete'); }
  } catch (e) { ElMessage.error('Failed to delete'); }
}

watch(currentPatientId, () => loadData());
onMounted(() => loadData());
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>
.preview-result {
  padding: 12px 16px;
  background: #f8fafc;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  margin-top: 8px;
}
.preview-title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 8px;
}
.preview-label {
  font-size: 13px;
  color: #64748b;
}
.preview-value {
  font-weight: 600;
}
.preview-advice {
  font-size: 13px;
  color: #64748b;
  margin-top: 8px;
}
@media (max-width: 768px) {
  .preview-result .el-col {
    margin-bottom: 8px;
  }
}
</style>
