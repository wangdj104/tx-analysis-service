<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div><h1>real-timealert</h1><p class="subtitle">configurationalert rule, monitoringAbnormal Results, andtimedrypre-Risk</p></div>
            </div>
            <div class="right">
              <el-button type="warning" @click="handleCheck">ManualExaminationalert</el-button>
            </div>
          </div>
        </div>
        <div class="content-panel">
          <el-tabs v-model="activeTab">
            <!-- alertrecord -->
            <el-tab-pane label="alertrecord" name="records">
              <div class="toolbar">
                <el-radio-group v-model="statusFilter" @change="loadData" class="status-radio-group">
                  <el-radio-button value="">All</el-radio-button>
                  <el-radio-button value="PENDING">pendingConfirm</el-radio-button>
                  <el-radio-button value="CONFIRMED">already Confirm</el-radio-button>
                  <el-radio-button value="RESOLVED">resolved</el-radio-button>
                </el-radio-group>
              </div>
              <el-table :data="alertRecords" stripe class="app-data-table">
                <el-table-column prop="triggeredAt" label="triggerTime" width="160" />
                <el-table-column prop="alertTitle" label="alerttitle" min-width="180" show-overflow-tooltip />
                <el-table-column prop="alertLevel" label="level" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag :type="levelTagType(row.alertLevel)" size="small">{{ levelLabel(row.alertLevel) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="triggeredValue" label="triggervalue" width="100" />
                <el-table-column prop="status" label="Status" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="Actions" width="180" align="center" fixed="right">
                  <template #default="{ row }">
                    <el-button v-if="row.status === 'PENDING'" link type="primary" size="small" @click="handleAck(row.id)">Confirm</el-button>
                    <el-button v-if="row.status === 'CONFIRMED'" link type="success" size="small" @click="showResolveDialog(row)">resolve</el-button>
                    <el-popconfirm title="Confirm deletion?" @confirm="handleDeleteRecord(row.id)">
                      <template #reference><el-button link type="danger" size="small">Delete</el-button></template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- alert rule -->
            <el-tab-pane label="alert rule" name="rules">
              <div class="toolbar">
                <el-button type="primary" @click="showRuleDialog"><el-icon><Plus /></el-icon>Addrule</el-button>
              </div>
              <el-table :data="rules" stripe class="app-data-table">
                <el-table-column prop="indicatorName" label="monitoringindicator" width="140" />
                <el-table-column prop="thresholdType" label="thresholdtype" width="120">
                  <template #default="{ row }">{{ thresholdTypeLabel(row.thresholdType) }}</template>
                </el-table-column>
                <el-table-column prop="thresholdValue" label="threshold" width="100" />
                <el-table-column prop="alertLevel" label="alertlevel" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag :type="levelTagType(row.alertLevel)" size="small">{{ levelLabel(row.alertLevel) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="enabled" label="Enabled" width="70" align="center">
                  <template #default="{ row }">
                    <el-switch :model-value="row.enabled === 1" @change="handleToggleRule(row.id)" size="small" />
                  </template>
                </el-table-column>
                <el-table-column label="Actions" width="80" align="center">
                  <template #default="{ row }">
                    <el-popconfirm title="Confirm deletion?" @confirm="handleDeleteRule(row.id)">
                      <template #reference><el-button link type="danger" size="small">Delete</el-button></template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
    </el-main>

    <!-- Addruledialog -->
    <el-dialog v-model="ruleDialogVisible" title="Addalert rule" :width="isMobile ? '92%' : '500px'" destroy-on-close>
      <el-form :model="ruleForm" label-width="100px" ref="ruleFormRef">
        <el-form-item label="monitoringindicator">
          <el-select v-model="ruleForm.indicatorCode" placeholder="selectindicator" style="width: 100%" @change="onIndicatorChange">
            <el-option v-for="ind in indicators" :key="ind.itemCode" :label="ind.itemName" :value="ind.itemCode" />
          </el-select>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12">
            <el-form-item label="thresholdtype">
              <el-select v-model="ruleForm.thresholdType" style="width: 100%">
                <el-option label="highinthreshold" value="ABOVE" />
                <el-option label="belowthreshold" value="BELOW" />
                <el-option label="exceedrange" value="OUT_OF_RANGE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="thresholdvalue">
              <el-input v-model="ruleForm.thresholdValue" placeholder="for example  5.5  or  2.1-2.6" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="alertlevel">
          <el-select v-model="ruleForm.alertLevel" style="width: 100%">
            <el-option label="Notice" value="INFO" />
            <el-option label="warning" value="WARNING" />
            <el-option label="Severe" value="CRITICAL" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="handleSaveRule">Save</el-button>
      </template>
    </el-dialog>

    <!-- resolvealertdialog -->
    <el-dialog v-model="resolveDialogVisible" title="resolvealert" width="400px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="placesetrecord">
          <el-input v-model="resolveNote" type="textarea" :rows="3" placeholder="fillwriteplacesetmeasures and result" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="handleResolve">Confirmresolve</el-button>
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

const LEVEL_MAP = { INFO: 'Notice', WARNING: 'warning', CRITICAL: 'Severe' };
const STATUS_MAP = { PENDING: 'pendingConfirm', CONFIRMED: 'already Confirm', RESOLVED: 'resolved' };
const THRESHOLD_MAP = { ABOVE: 'highinthreshold', BELOW: 'belowthreshold', OUT_OF_RANGE: 'exceedrange' };

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
    if (res.code === 200) { ElMessage.success('ruleSaved successfully'); ruleDialogVisible.value = false; loadData(); }
    else ElMessage.error(res.msg || 'Failed to save');
  } catch (e) { ElMessage.error('Failed to save'); }
}

async function handleToggleRule(id) {
  const res = await toggleRuleEnabled(id);
  if (res.code === 200) loadData();
}

async function handleDeleteRule(id) {
  const res = await deleteRule(id);
  if (res.code === 200) { ElMessage.success('Deleted successfully'); loadData(); }
}

async function handleAck(id) {
  const res = await acknowledge(id);
  if (res.code === 200) { ElMessage.success('already Confirm'); loadData(); }
}

function showResolveDialog(row) {
  resolveId.value = row.id;
  resolveNote.value = '';
  resolveDialogVisible.value = true;
}

async function handleResolve() {
  const res = await resolve(resolveId.value, resolveNote.value);
  if (res.code === 200) { ElMessage.success('resolved'); resolveDialogVisible.value = false; loadData(); }
}

async function handleDeleteRecord(id) {
  const res = await deleteRecord(id);
  if (res.code === 200) { ElMessage.success('Deleted successfully'); loadData(); }
}

async function handleCheck() {
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
  const res = await checkThresholds(currentPatientId.value);
  if (res.code === 200) { ElMessage.success('alertExaminationcomplete'); loadData(); }
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
