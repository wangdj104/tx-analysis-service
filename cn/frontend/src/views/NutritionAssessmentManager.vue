<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>营养评估</h1>
                <p class="subtitle">评估透析患者营养状态，跟踪 SGA 评分和生化指标变化趋势。</p>
              </div>
            </div>
            <div class="right">
              <el-button type="primary" @click="showAddDialog">
                <el-icon><Plus /></el-icon>新增评估
              </el-button>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <!-- list -->
          <div class="toolbar">
            <div class="list-panel-title">
              <el-icon><FirstAidKit /></el-icon>
              <span>评估记录</span>
              <span v-if="records.length" class="list-count">共 {{ records.length }} 项</span>
            </div>
            <el-button @click="loadData" :loading="loading">
              <el-icon><Refresh /></el-icon>刷新
            </el-button>
          </div>

          <el-table :data="records" stripe class="app-data-table" v-loading="loading">
            <el-table-column prop="assessmentDate" label="评估日期" width="108" />
            <el-table-column label="营养状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.nutritionStatus)" size="small">{{ statusLabel(row.nutritionStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sgaGrade" label="SGA 等级" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="sgaTagType(row.sgaGrade)" size="small">{{ row.sgaGrade || '-' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sgaScore" label="SGA 评分" width="80" align="center" />
            <el-table-column prop="bmi" label="BMI" width="80" align="center" />
            <el-table-column prop="albumin" label="白蛋白" width="80">
              <template #default="{ row }">{{ row.albumin ? row.albumin + ' g/L' : '-' }}</template>
            </el-table-column>
            <el-table-column prop="preAlbumin" label="前白蛋白" width="90">
              <template #default="{ row }">{{ row.preAlbumin ? row.preAlbumin + ' mg/L' : '-' }}</template>
            </el-table-column>
            <el-table-column prop="supplementAdvice" label="补充建议" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showEditDialog(row)">编辑</el-button>
                <el-popconfirm title="确认删除吗？" @confirm="handleDelete(row.id)">
                  <template #reference><el-button link type="danger" size="small">删除</el-button></template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-main>

    <!-- Add/Editdialog -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑营养评估' : '新增营养评估'" :width="isMobile ? '92%' : '680px'" destroy-on-close>
      <el-form :model="form" label-width="110px" ref="formRef" :rules="rules">
        <el-form-item label="评估日期" prop="assessmentDate">
          <el-date-picker v-model="form.assessmentDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="8">
            <el-form-item label="体重（kg）">
              <el-input-number v-model="form.bodyWeight" :precision="1" :min="0" :max="300" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="身高（cm）">
              <el-input-number v-model="form.height" :precision="1" :min="0" :max="250" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="BMI">
              <el-input v-model="form.bmi" disabled placeholder="自动计算" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :xs="12" :sm="6">
            <el-form-item label="SGA 评分">
              <el-input-number v-model="form.sgaScore" :min="1" :max="7" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="白蛋白（g/L）">
              <el-input-number v-model="form.albumin" :precision="1" :min="0" :max="100" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="前白蛋白（mg/L）">
              <el-input-number v-model="form.preAlbumin" :precision="0" :min="0" :max="500" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="液体摄入（mL）">
              <el-input-number v-model="form.fluidIntake" :min="0" :max="5000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :xs="12" :sm="6">
            <el-form-item label="蛋白质摄入（g）">
              <el-input-number v-model="form.totalProteinIntake" :precision="1" :min="0" :max="200" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="热量摄入（kcal）">
              <el-input-number v-model="form.dailyCalorieIntake" :precision="0" :min="0" :max="5000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="钾摄入（mg）">
              <el-input-number v-model="form.dailyPotassiumIntake" :precision="0" :min="0" :max="5000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="6">
            <el-form-item label="磷摄入（mg）">
              <el-input-number v-model="form.dailyPhosphorusIntake" :precision="0" :min="0" :max="5000" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注信息" />
        </el-form-item>

        <!-- pre-calculateresult -->
        <div v-if="previewResult" class="preview-result">
          <p class="preview-title">预计算结果</p>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="8">
              <span class="preview-label">BMI: </span>
              <span class="preview-value">{{ previewResult.bmi || '-' }}</span>
            </el-col>
            <el-col :xs="24" :sm="8">
              <span class="preview-label">营养状态：</span>
              <el-tag :type="statusTagType(previewResult.nutritionStatus)" size="small">{{ statusLabel(previewResult.nutritionStatus) }}</el-tag>
            </el-col>
            <el-col :xs="24" :sm="8">
              <span class="preview-label">SGA 等级：</span>
              <el-tag :type="sgaTagType(previewResult.sgaGrade)" size="small">{{ previewResult.sgaGrade || '-' }}</el-tag>
            </el-col>
          </el-row>
          <p v-if="previewResult.supplementAdvice" class="preview-advice">{{ previewResult.supplementAdvice }}</p>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="info" @click="handlePreview" :loading="previewLoading">预计算</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
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
  assessmentDate: [{ required: true, message: '请选择评估日期', trigger: 'change' }]
};

const STATUS_MAP = { GOOD: '良好', AT_RISK: '有风险', DEFICIENT: '营养不良' };
const SGA_MAP = { A: '营养状况良好', B: '轻至中度营养不良', C: '重度营养不良' };

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
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
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
  } catch (e) { ElMessage.error('计算失败'); }
  finally { previewLoading.value = false; }
}

async function handleSave() {
  try { await formRef.value.validate(); } catch { return; }
  saving.value = true;
  try {
    form.patientId = currentPatientId.value;
    const res = await saveAssessment(form);
    if (res.code === 200) {
      ElMessage.success('保存成功');
      dialogVisible.value = false;
      loadData();
    } else { ElMessage.error(res.msg || '保存失败'); }
  } catch (e) { ElMessage.error('保存失败'); }
  finally { saving.value = false; }
}

async function handleDelete(id) {
  try {
    const res = await deleteAssessment(id);
    if (res.code === 200) { ElMessage.success('删除成功'); loadData(); }
    else { ElMessage.error(res.msg || '删除失败'); }
  } catch (e) { ElMessage.error('删除失败'); }
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
