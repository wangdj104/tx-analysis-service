<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div><h1>健康告警</h1><p class="subtitle">系统每 10 分钟自动检查一次，请核对依据并记录每次临床处置。</p></div>
            </div>
            <div class="right">
              <el-button plain @click="handleCheck">立即扫描</el-button>
            </div>
          </div>
        </div>
        <div class="content-panel">
          <el-tabs v-model="activeTab">
            <!-- alertrecord -->
            <el-tab-pane label="告警记录" name="records">
              <div class="toolbar">
                <el-radio-group v-model="statusFilter" @change="loadData" class="status-radio-group">
                  <el-radio-button value="">全部</el-radio-button>
                  <el-radio-button value="PENDING">待确认</el-radio-button>
                  <el-radio-button value="CONFIRMED">已确认</el-radio-button>
                  <el-radio-button value="RESOLVED">已解决</el-radio-button>
                </el-radio-group>
              </div>
              <el-table :data="alertRecords" stripe class="app-data-table">
                <el-table-column prop="triggeredAt" label="触发时间" width="160" />
                <el-table-column prop="alertTitle" label="告警" min-width="180" show-overflow-tooltip />
                <el-table-column prop="alertLevel" label="严重程度" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="levelTagType(row.alertLevel)" size="small">{{ levelLabel(row.alertLevel) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="triggeredValue" label="依据" min-width="130" />
                <el-table-column prop="occurrenceCount" label="发生次数" width="105" align="center" />
                <el-table-column prop="status" label="状态" width="105" align="center">
                  <template #default="{ row }">
                    <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="210" align="center" fixed="right">
                  <template #default="{ row }">
                    <el-button v-if="row.status === 'PENDING'" link type="primary" size="small" @click="handleAck(row.id)">确认</el-button>
                    <el-button v-if="row.status === 'CONFIRMED'" link type="success" size="small" @click="showResolveDialog(row)">解决</el-button>
                    <el-popconfirm title="确认删除吗？" @confirm="handleDeleteRecord(row.id)">
                      <template #reference><el-button link type="danger" size="small">删除</el-button></template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- alert rule -->
            <el-tab-pane label="告警规则" name="rules">
              <div class="toolbar">
                <el-button type="primary" @click="showRuleDialog"><el-icon><Plus /></el-icon>新增规则</el-button>
              </div>
              <el-table :data="rules" stripe class="app-data-table">
                <el-table-column prop="indicatorName" label="指标" width="160" />
                <el-table-column prop="thresholdType" label="阈值类型" width="130">
                  <template #default="{ row }">{{ thresholdTypeLabel(row.thresholdType) }}</template>
                </el-table-column>
                <el-table-column prop="thresholdValue" label="阈值" width="110" />
                <el-table-column prop="alertLevel" label="严重程度" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="levelTagType(row.alertLevel)" size="small">{{ levelLabel(row.alertLevel) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="enabled" label="启用" width="70" align="center">
                  <template #default="{ row }">
                    <el-switch :model-value="row.enabled === 1" @change="handleToggleRule(row.id)" size="small" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="80" align="center">
                  <template #default="{ row }">
                    <el-popconfirm title="确认删除吗？" @confirm="handleDeleteRule(row.id)">
                      <template #reference><el-button link type="danger" size="small">删除</el-button></template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
    </el-main>

    <el-dialog v-model="ruleDialogVisible" title="新增告警规则" :width="isMobile ? '92%' : '500px'" destroy-on-close>
      <el-form :model="ruleForm" label-width="100px" ref="ruleFormRef">
        <el-form-item label="指标">
          <el-select v-model="ruleForm.indicatorCode" placeholder="选择指标" style="width: 100%" @change="onIndicatorChange">
            <el-option v-for="ind in indicators" :key="ind.itemCode" :label="ind.itemName" :value="ind.itemCode" />
          </el-select>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12">
            <el-form-item label="阈值类型">
              <el-select v-model="ruleForm.thresholdType" style="width: 100%">
                <el-option label="高于" value="ABOVE" />
                <el-option label="低于" value="BELOW" />
                <el-option label="超出范围" value="OUT_OF_RANGE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="阈值">
              <el-input v-model="ruleForm.thresholdValue" placeholder="例如 5.5 或 2.1-2.6" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="严重程度">
          <el-select v-model="ruleForm.alertLevel" style="width: 100%">
            <el-option label="提示" value="INFO" />
            <el-option label="警告" value="WARNING" />
            <el-option label="紧急" value="CRITICAL" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveRule">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveDialogVisible" title="解决告警" width="min(440px, 94vw)" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="处置措施">
          <el-input v-model="resolveNote" type="textarea" :rows="3" placeholder="必填：请记录处置措施和结果" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleResolve">确认解决</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { listRules, saveRule, toggleRuleEnabled, deleteRule, listRecords, acknowledge, resolve, checkThresholds, getStats, deleteRecord } from '@/api/alert.js';
import { listIndicators } from '@/api/healthIndicator.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMobile } from '@/composables/useMobile';

const { currentPatientId } = useCurrentPatient();
const { isMobile } = useMobile();
const activeTab = ref('records');
const alertRecords = ref([]);
const rules = ref([]);
const indicators = ref([]);
const statusFilter = ref('');
const ruleDialogVisible = ref(false);
const resolveDialogVisible = ref(false);
const resolveId = ref(null);
const resolveNote = ref('');
const ruleFormRef = ref(null);
const ruleForm = reactive({
  patientId: null, indicatorCode: '', indicatorName: '', thresholdType: 'ABOVE',
  thresholdValue: '', alertLevel: 'WARNING'
});

const LEVEL_MAP = { INFO: 'Information', WARNING: 'Warning', CRITICAL: 'Critical' };
const STATUS_MAP = { PENDING: 'Pending', CONFIRMED: 'Acknowledged', OBSERVING: 'Observing', CONSULTED: 'Consulted', RECHECKED: 'Rechecked', RESOLVED: 'Resolved' };
const THRESHOLD_MAP = { ABOVE: '高于', BELOW: '低于', OUT_OF_RANGE: '超出范围' };

function levelLabel(v) { return LEVEL_MAP[v] || v; }
function statusLabel(v) { return STATUS_MAP[v] || v; }
function thresholdTypeLabel(v) { return THRESHOLD_MAP[v] || v; }
function levelTagType(v) { return { INFO: 'info', WARNING: 'warning', CRITICAL: 'danger' }[v] || 'info'; }
function statusTagType(v) { return { PENDING: 'warning', CONFIRMED: 'primary', RESOLVED: 'success' }[v] || 'info'; }

async function loadData() {
  if (!currentPatientId.value) { alertRecords.value = []; rules.value = []; return; }
  try {
    const rRes = await listRecords(currentPatientId.value, statusFilter.value);
    if (rRes.code === 200) alertRecords.value = rRes.data || [];
    const ruRes = await listRules(currentPatientId.value);
    if (ruRes.code === 200) rules.value = ruRes.data || [];
    const iRes = await listIndicators();
    if (iRes.code === 200) indicators.value = iRes.data || [];
  } catch (e) { console.error(e); }
}

function onIndicatorChange(code) {
  const ind = indicators.value.find(i => i.itemCode === code);
  if (ind) ruleForm.indicatorName = ind.itemName;
}

function showRuleDialog() {
  Object.assign(ruleForm, { patientId: currentPatientId.value, indicatorCode: '', indicatorName: '', thresholdType: 'ABOVE', thresholdValue: '', alertLevel: 'WARNING' });
  ruleDialogVisible.value = true;
}

async function handleSaveRule() {
  try {
    const res = await saveRule(ruleForm);
    if (res.code === 200) { ElMessage.success('告警规则已保存。'); ruleDialogVisible.value = false; loadData(); }
    else ElMessage.error(res.msg || '保存失败');
  } catch (e) { ElMessage.error('保存失败'); }
}

async function handleToggleRule(id) {
  const res = await toggleRuleEnabled(id);
  if (res.code === 200) loadData();
}

async function handleDeleteRule(id) {
  const res = await deleteRule(id);
  if (res.code === 200) { ElMessage.success('删除成功'); loadData(); }
}

async function handleAck(id) {
  const res = await acknowledge(id);
  if (res.code === 200) { ElMessage.success('告警已确认。'); loadData(); }
}

function showResolveDialog(row) {
  resolveId.value = row.id;
  resolveNote.value = '';
  resolveDialogVisible.value = true;
}

async function handleResolve() {
  if (!resolveNote.value.trim()) { ElMessage.warning('解决告警前请记录处置措施。'); return; }
  const res = await resolve(resolveId.value, resolveNote.value);
  if (res.code === 200) { ElMessage.success('告警已解决。'); resolveDialogVisible.value = false; loadData(); }
}

async function handleDeleteRecord(id) {
  const res = await deleteRecord(id);
  if (res.code === 200) { ElMessage.success('删除成功'); loadData(); }
}

async function handleCheck() {
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
  const res = await checkThresholds(currentPatientId.value);
  if (res.code === 200) { ElMessage.success('告警扫描完成。'); loadData(); }
}

watch(currentPatientId, () => loadData());
onMounted(() => loadData());
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>
.status-radio-group {
  flex-wrap: wrap;
}
@media (max-width: 768px) {
  .status-radio-group :deep(.el-radio-button__inner) {
    padding: 8px 12px;
    font-size: 13px;
  }
}
</style>
