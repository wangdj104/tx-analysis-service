<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>并发症跟踪</h1>
                <p class="subtitle">记录透析并发症事件，跟踪发生趋势和严重程度。</p>
              </div>
            </div>
            <div class="right">
              <el-button type="primary" @click="showAddDialog">
                <el-icon><Plus /></el-icon>新增记录
              </el-button>
            </div>
          </div>
        </div>

        <!-- statisticsoverview -->
        <div v-if="stats" class="content-panel stats-overview">
          <el-row :gutter="16">
            <el-col :xs="12" :sm="6" :md="6">
              <el-statistic title="记录总数" :value="stats.totalCount || 0" suffix="条" />
            </el-col>
            <el-col :xs="12" :sm="6" :md="6">
              <el-statistic title="轻度" :value="mildCount" suffix="次">
                <template #suffix><span class="stat-suffix mild">次</span></template>
              </el-statistic>
            </el-col>
            <el-col :xs="12" :sm="6" :md="6">
              <el-statistic title="中度" :value="moderateCount">
                <template #suffix><span class="stat-suffix moderate">次</span></template>
              </el-statistic>
            </el-col>
            <el-col :xs="12" :sm="6" :md="6">
              <el-statistic title="重度" :value="severeCount">
                <template #suffix><span class="stat-suffix severe">次</span></template>
              </el-statistic>
            </el-col>
          </el-row>

          <!-- typedistribution -->
          <div v-if="stats.typeCounts && stats.typeCounts.length" class="stats-distribution">
            <span class="distribution-label">类型分布</span>
            <div class="stats-tag-wrap">
              <el-tag v-for="tc in stats.typeCounts" :key="tc.type" :type="typeTagType(tc.type)" size="small">
                {{ typeLabel(tc.type) }} · {{ tc.count }}
              </el-tag>
            </div>
          </div>
        </div>

        <!-- recordlist -->
        <div class="content-panel">
          <div class="toolbar">
            <div class="list-panel-title">
              <el-icon><Warning /></el-icon>
              <span>并发症记录</span>
              <span v-if="records.length" class="list-count">共 {{ records.length }} 条</span>
            </div>
            <el-button @click="loadData" :loading="loading">
              <el-icon><Refresh /></el-icon>刷新
            </el-button>
          </div>

          <div class="table-wrap">
            <el-table :data="records" stripe class="app-data-table app-data-table--list" v-loading="loading" empty-text="暂无并发症记录">
              <el-table-column prop="occurrenceDate" label="发生日期" width="108" />
              <el-table-column prop="complicationType" label="类型" width="120">
                <template #default="{ row }">
                  <el-tag :type="typeTagType(row.complicationType)" size="small">{{ typeLabel(row.complicationType) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="severity" label="严重程度" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="severityTagType(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="关联透析摘要" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <template v-if="row.relatedDialysisId && row.dialysisWeightGain != null">
                    <el-tag type="info" size="small">
                      增重 {{ row.dialysisWeightGain }} kg｜血压 {{ row.dialysisSystolicBp ?? '-' }}/{{ row.dialysisDiastolicBp ?? '-' }}
                    </el-tag>
                  </template>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
              <el-table-column prop="treatmentMeasures" label="处理措施" min-width="140" show-overflow-tooltip />
              <el-table-column prop="outcome" label="转归" min-width="100" show-overflow-tooltip />
              <el-table-column label="操作" width="120" align="center" fixed="right">
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button link type="primary" size="small" @click="showEditDialog(row)">编辑</el-button>
                    <el-popconfirm title="确认删除吗？" @confirm="handleDelete(row.id)">
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

    <!-- Add/Editcomplicationrecord -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑并发症记录' : '新增并发症记录'" :width="isMobile ? '94%' : '600px'" destroy-on-close>
      <el-form :model="form" label-width="110px" ref="formRef" :rules="rules">
        <el-form-item label="关联透析记录">
          <el-select v-model="form.relatedDialysisId" filterable clearable placeholder="选择透析记录（选填）" style="width: 100%" @change="onDialysisSelect">
            <el-option v-for="d in dialysisOptions" :key="d.id" :label="`${d.recordDate}｜增重 ${d.weightGain ?? '-'} kg｜血压 ${d.systolicBp ?? '-'}/${d.diastolicBp ?? '-'}`" :value="d.id" />
          </el-select>
        </el-form-item>
        <!-- relatedDialysisdataPreview -->
        <div v-if="linkedDialysisData" class="linked-dialysis-preview">
          <div class="linked-preview-title">关联透析数据：</div>
          <div class="linked-preview-grid">
            <div><span class="muted">透析前</span> <strong>{{ linkedDialysisData.onWeight ?? '-' }}</strong> kg</div>
            <div><span class="muted">透析后</span> <strong>{{ linkedDialysisData.offWeight ?? '-' }}</strong> kg</div>
            <div><span class="muted">增重</span> <strong class="accent">{{ linkedDialysisData.weightGain ?? '-' }}</strong> kg</div>
            <div><span class="muted">超滤量</span> <strong>{{ linkedDialysisData.ufAmount ?? '-' }}</strong> kg</div>
            <div><span class="muted">血压</span> <strong>{{ linkedDialysisData.systolicBp ?? '-' }}/{{ linkedDialysisData.diastolicBp ?? '-' }}</strong></div>
            <div><span class="muted">脱水状态</span> <el-tag :type="dehydrationTagType(linkedDialysisData.dehydrationStatus)" size="small">{{ dehydrationLabel(linkedDialysisData.dehydrationStatus) }}</el-tag></div>
          </div>
        </div>
        <el-form-item label="发生日期" prop="occurrenceDate">
          <el-date-picker v-model="form.occurrenceDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12">
            <el-form-item label="并发症类型" prop="complicationType">
              <el-select v-model="form.complicationType" placeholder="请选择" style="width: 100%">
                <el-option label="感染" value="INFECTION" />
                <el-option label="透析中低血压" value="HYPOTENSION" />
                <el-option label="贫血" value="ANEMIA" />
                <el-option label="骨病" value="BONE_DISEASE" />
                <el-option label="心血管事件" value="CARDIOVASCULAR" />
                <el-option label="血管通路问题" value="VASCULAR_ACCESS_ISSUE" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="严重程度" prop="severity">
              <el-select v-model="form.severity" placeholder="请选择" style="width: 100%">
                <el-option label="轻度" value="MILD" />
                <el-option label="中度" value="MODERATE" />
                <el-option label="重度" value="SEVERE" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="请描述并发症详情" />
        </el-form-item>
        <el-form-item label="处理措施">
          <el-input v-model="form.treatmentMeasures" type="textarea" :rows="2" placeholder="记录已采取的处理措施" />
        </el-form-item>
        <el-form-item label="转归／结果">
          <el-input v-model="form.outcome" placeholder="例如：好转、稳定、需要住院" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注信息" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Plus, Refresh, Warning } from '@element-plus/icons-vue';
import { listRecords, saveRecord, updateRecord, deleteRecord, getStats } from '@/api/complication.js';
import { listRecords as listDialysisRecords } from '@/api/dialysis.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMobile } from '@/composables/useMobile';

const { currentPatientId } = useCurrentPatient();
const { isMobile } = useMobile();
const records = ref([]);
const stats = ref(null);
const loading = ref(false);
const dialogVisible = ref(false);
const isEdit = ref(false);
const formRef = ref(null);
const dialysisOptions = ref([]);
const linkedDialysisData = ref(null);

const form = reactive({
  id: null, patientId: null, relatedDialysisId: null, complicationType: '', severity: '',
  occurrenceDate: '', description: '', treatmentMeasures: '', outcome: '', remark: ''
});

const rules = {
  occurrenceDate: [{ required: true, message: '请选择发生日期', trigger: 'change' }],
  complicationType: [{ required: true, message: '请选择并发症类型', trigger: 'change' }],
  severity: [{ required: true, message: '请选择严重程度', trigger: 'change' }]
};

const TYPE_MAP = {
  INFECTION: '感染', HYPOTENSION: '透析中低血压', ANEMIA: '贫血',
  BONE_DISEASE: '骨病', CARDIOVASCULAR: '心血管事件',
  VASCULAR_ACCESS_ISSUE: '血管通路问题', OTHER: '其他'
};
const SEVERITY_MAP = { MILD: '轻度', MODERATE: '中度', SEVERE: '重度' };

function typeLabel(v) { return TYPE_MAP[v] || v; }
function severityLabel(v) { return SEVERITY_MAP[v] || v; }
function typeTagType(v) {
  const m = { INFECTION: 'danger', HYPOTENSION: 'warning', CARDIOVASCULAR: 'danger', VASCULAR_ACCESS_ISSUE: 'warning' };
  return m[v] || 'info';
}
function severityTagType(v) {
  const m = { MILD: 'success', MODERATE: 'warning', SEVERE: 'danger' };
  return m[v] || 'info';
}
function dehydrationLabel(v) {
  const m = { TOO_MUCH: '过多', INSUFFICIENT: '不足', MATCH: '匹配' };
  return m[v] || v || '-';
}
function dehydrationTagType(v) {
  const m = { TOO_MUCH: 'warning', INSUFFICIENT: 'danger', MATCH: 'success' };
  return m[v] || 'info';
}

// Severelevelstatistics (from  stats.severityCounts extract)
const mildCount = computed(() => {
  const item = stats.value?.severityCounts?.find(s => s.severity === 'MILD');
  return item?.count || 0;
});
const moderateCount = computed(() => {
  const item = stats.value?.severityCounts?.find(s => s.severity === 'MODERATE');
  return item?.count || 0;
});
const severeCount = computed(() => {
  const item = stats.value?.severityCounts?.find(s => s.severity === 'SEVERE');
  return item?.count || 0;
});

function onDialysisSelect(dialysisId) {
  if (!dialysisId) {
    linkedDialysisData.value = null;
    return;
  }
  const d = dialysisOptions.value.find(item => item.id === dialysisId);
  if (d) {
    linkedDialysisData.value = d;
    // AutomaticpopulateoccurDate (onlywhen be emptytime)
    if (!form.occurrenceDate) {
      form.occurrenceDate = d.recordDate;
    }
  }
}

async function loadData() {
  if (!currentPatientId.value) { records.value = []; stats.value = null; dialysisOptions.value = []; return; }
  loading.value = true;
  try {
    const res = await listRecords(currentPatientId.value);
    if (res.code === 200) records.value = res.data || [];
    const sRes = await getStats(currentPatientId.value);
    if (sRes.code === 200) stats.value = sRes.data;
    // loadDialysis Recordslistprovideselectdeviceuse
    const dRes = await listDialysisRecords(null, null, currentPatientId.value);
    if (dRes.code === 200) dialysisOptions.value = (dRes.data || []).sort((a, b) => (b.recordDate || '').localeCompare(a.recordDate || ''));
  } catch (e) { console.error(e); }
  finally { loading.value = false; }
}

function showAddDialog() {
  isEdit.value = false;
  Object.assign(form, {
    id: null, patientId: currentPatientId.value, relatedDialysisId: null, complicationType: '', severity: '',
    occurrenceDate: '', description: '', treatmentMeasures: '', outcome: '', remark: ''
  });
  linkedDialysisData.value = null;
  dialogVisible.value = true;
}

function showEditDialog(row) {
  isEdit.value = true;
  Object.assign(form, { ...row });
  // ifhas relatedDialysis Records, from row temporarytimefieldbuildPreviewdata
  if (row.relatedDialysisId && row.dialysisWeightGain != null) {
    linkedDialysisData.value = {
      onWeight: row.dialysisOnWeight,
      offWeight: row.dialysisOffWeight,
      weightGain: row.dialysisWeightGain,
      ufAmount: row.dialysisUfAmount,
      systolicBp: row.dialysisSystolicBp,
      diastolicBp: row.dialysisDiastolicBp,
      dehydrationStatus: row.dialysisDehydrationStatus
    };
  } else {
    linkedDialysisData.value = null;
  }
  dialogVisible.value = true;
}

async function handleSave() {
  try {
    await formRef.value.validate();
    form.patientId = currentPatientId.value;
    const res = isEdit.value ? await updateRecord(form) : await saveRecord(form);
    if (res.code === 200) {
      ElMessage.success(res.data || '保存成功');
      dialogVisible.value = false;
      loadData();
    } else { ElMessage.error(res.msg || '保存失败'); }
  } catch (e) { console.error(e); }
}

async function handleDelete(id) {
  const res = await deleteRecord(id);
  if (res.code === 200) { ElMessage.success('删除成功'); loadData(); }
  else { ElMessage.error(res.msg || '删除失败'); }
}

watch(currentPatientId, () => { loadData(); });
onMounted(() => { loadData(); });
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>
/* statisticsoverview */
.stats-overview {
  padding: 18px 24px;
}
.stats-overview :deep(.el-statistic__head) {
  font-size: 12px;
  color: #64748b;
  font-weight: 600;
}
.stats-overview :deep(.el-statistic__content) {
  font-size: 15px;
  color: #1e293b;
  font-weight: 600;
}
.stat-suffix {
  font-size: 13px;
  color: #94a3b8;
  margin-left: 2px;
}
.stat-suffix.mild { color: #67c23a; }
.stat-suffix.moderate { color: #e6a23c; }
.stat-suffix.severe { color: #f56c6c; }

/* typedistribution */
.stats-distribution {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #e2e8f0;
}
.distribution-label {
  font-size: 13px;
  font-weight: 600;
  color: #475569;
  flex-shrink: 0;
}
.stats-tag-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* relatedDialysisPreview */
.linked-dialysis-preview {
  margin: -8px 0 16px 0;
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}
.linked-preview-title {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}
.linked-preview-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  font-size: 13px;
}
.linked-preview-grid .muted { color: #94a3b8; }
.linked-preview-grid .accent { color: #4f6af6; }

@media (max-width: 768px) {
  .stats-overview :deep(.el-statistic__content) {
    font-size: 14px;
  }
  .stats-overview .el-col {
    margin-bottom: 12px;
  }
  .stats-distribution {
    flex-direction: column;
    align-items: flex-start;
  }
  .linked-dialysis-preview {
    margin-left: 0;
  }
  .linked-preview-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
