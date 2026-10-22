<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div><h1>Health Alerts</h1><p class="subtitle">Automatic checks run every 10 minutes. Review evidence and document each clinical response.</p></div>
            </div>
            <div class="right">
              <el-button plain @click="handleCheck">Run scan now</el-button>
            </div>
          </div>
        </div>
        <FollowUpList :patient-id="currentPatientId" />
        <div class="content-panel">
          <el-tabs v-model="activeTab">
            <!-- alertrecord -->
            <el-tab-pane label="Alert records" name="records">
              <div class="toolbar">
                <el-radio-group v-model="statusFilter" @change="loadData" class="status-radio-group">
                  <el-radio-button value="">All</el-radio-button>
                  <el-radio-button value="PENDING">Pending</el-radio-button>
                  <el-radio-button value="CONFIRMED">Acknowledged</el-radio-button>
                  <el-radio-button value="RESOLVED">Resolved</el-radio-button>
                </el-radio-group>
              </div>
              <p v-if="loading" role="status">Loading alert information…</p>
              <p v-else-if="readState === 'error'" role="alert">Alert information is unavailable. Change the filter or reselect the patient to retry.</p>
              <el-table :empty-text="loading ? 'Loading alert information…' : readState === 'error' ? 'Alert information is unavailable. Change the filter or reselect the patient to retry.' : undefined" :data="alertRecords" stripe class="app-data-table">
                <el-table-column prop="triggeredAt" label="Triggered" width="160" />
                <el-table-column prop="alertTitle" label="Alert" min-width="240">
                  <template #default="{ row }">
                    <div>{{ row.alertTitle }}</div>
                    <section class="alert-source-facts"><b>Recorded alert</b><p v-for="(line, index) in alertSourceLines(row)" :key="index">{{ line }}</p></section>
                  </template>
                </el-table-column>
                <el-table-column prop="alertLevel" label="Severity" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="levelTagType(row.alertLevel)" size="small">{{ levelLabel(row.alertLevel) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="triggeredValue" label="Evidence" min-width="130" />
                <el-table-column prop="occurrenceCount" label="Occurrences" width="105" align="center" />
                <el-table-column prop="status" label="Status" width="105" align="center">
                  <template #default="{ row }">
                    <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="Actions" width="210" align="center" fixed="right">
                  <template #default="{ row }">
                    <el-button v-if="row.status === 'PENDING'" link type="primary" size="small" :onClick="ownedRowCallback(handleAck, row.id)">Acknowledge</el-button>
                    <el-button v-if="row.status === 'CONFIRMED'" link type="success" size="small" :onClick="ownedRowCallback(showResolveDialog, row)">Resolve</el-button>
                    <el-popconfirm title="Confirm deletion?" :onConfirm="ownedRowCallback(handleDeleteRecord, row.id)">
                      <template #reference><el-button link type="danger" size="small">Delete</el-button></template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- alert rule -->
            <el-tab-pane label="Alert rules" name="rules">
              <div class="toolbar">
                <el-button type="primary" @click="showRuleDialog"><el-icon><Plus /></el-icon>Add rule</el-button>
              </div>
              <el-table :data="rules" stripe class="app-data-table">
                <el-table-column prop="indicatorName" label="Indicator" width="160" />
                <el-table-column prop="thresholdType" label="Threshold type" width="130">
                  <template #default="{ row }">{{ thresholdTypeLabel(row.thresholdType) }}</template>
                </el-table-column>
                <el-table-column prop="thresholdValue" label="Threshold" width="110" />
                <el-table-column prop="alertLevel" label="Severity" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="levelTagType(row.alertLevel)" size="small">{{ levelLabel(row.alertLevel) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="enabled" label="Enabled" width="70" align="center">
                  <template #default="{ row }">
                    <el-switch :model-value="row.enabled === 1" :onChange="ownedRowCallback(handleToggleRule, row.id)" size="small" />
                  </template>
                </el-table-column>
                <el-table-column label="Actions" width="80" align="center">
                  <template #default="{ row }">
                    <el-popconfirm title="Confirm deletion?" :onConfirm="ownedRowCallback(handleDeleteRule, row.id)">
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

    <el-dialog v-model="ruleDialogVisible" title="Add alert rule" :width="isMobile ? '92%' : '500px'" destroy-on-close>
      <el-form :model="ruleForm" label-width="100px" ref="ruleFormRef">
        <el-form-item label="Indicator">
          <el-select v-model="ruleForm.indicatorCode" placeholder="Select an indicator" style="width: 100%" @change="onIndicatorChange">
            <el-option v-for="ind in indicators" :key="ind.itemCode" :label="ind.itemName" :value="ind.itemCode" />
          </el-select>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12">
            <el-form-item label="Threshold type">
              <el-select v-model="ruleForm.thresholdType" style="width: 100%">
                <el-option label="Above" value="ABOVE" />
                <el-option label="Below" value="BELOW" />
                <el-option label="Outside range" value="OUT_OF_RANGE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="Threshold value">
              <el-input v-model="ruleForm.thresholdValue" placeholder="For example 5.5 or 2.1-2.6" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="Severity">
          <el-select v-model="ruleForm.alertLevel" style="width: 100%">
            <el-option label="Information" value="INFO" />
            <el-option label="Warning" value="WARNING" />
            <el-option label="Critical" value="CRITICAL" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :onClick="dialogCallback(() => ruleDialogVisible = false, 'rule')">Cancel</el-button>
        <el-button type="primary" :onClick="dialogCallback(handleSaveRule, 'rule')">Save</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveDialogVisible" title="Resolve alert" width="min(440px, 94vw)" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="Action taken">
          <el-input v-model="resolveNote" type="textarea" :rows="3" placeholder="Required: document the action and outcome" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :onClick="dialogCallback(() => resolveDialogVisible = false, 'resolve')">Cancel</el-button>
        <el-button type="primary" :onClick="dialogCallback(handleResolve, 'resolve')">Confirm resolution</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import FollowUpList from '@/components/alert-workflow/FollowUpList.vue';
import { ref, reactive, inject, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache } from '@/utils/authSession';
import { ElMessage } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { listRules, saveRule, toggleRuleEnabled, deleteRule, listRecords, acknowledge, resolve, checkThresholds, getStats, deleteRecord } from '@/api/alert.js';
import { listIndicators } from '@/api/healthIndicator.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMobile } from '@/composables/useMobile';

const { currentPatientId } = useCurrentPatient();
const { isMobile } = useMobile();
const route = useRoute();
const permissionMenus = inject('userMenus', ref([]));
const loading = ref(false);
const readState = ref('idle');
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
const THRESHOLD_MAP = { ABOVE: 'Above', BELOW: 'Below', OUT_OF_RANGE: 'Outside range' };

function levelLabel(v) { return LEVEL_MAP[v] || v; }
function statusLabel(v) { return STATUS_MAP[v] || v; }
function thresholdTypeLabel(v) { return THRESHOLD_MAP[v] || v; }
function levelTagType(v) { return { INFO: 'info', WARNING: 'warning', CRITICAL: 'danger' }[v] || 'info'; }
function statusTagType(v) { return { PENDING: 'warning', CONFIRMED: 'primary', RESOLVED: 'success' }[v] || 'info'; }

let readEpoch = 0, contextEpoch = 0, disposed = false;
let ruleDialogEpoch = 0, resolveDialogEpoch = 0;
function captureContext() {
  return { epoch: contextEpoch, patient: currentPatientId.value, filter: statusFilter.value, route: route.fullPath,
    auth: captureAuthSession(), actor: localStorage.getItem('userId'),
    permissions: JSON.stringify(readPermissionCache()), menus: JSON.stringify(permissionMenus.value) };
}
function ownsContext(owner) {
  return !disposed && owner.epoch === contextEpoch && owner.patient === currentPatientId.value && owner.filter === statusFilter.value
    && owner.route === route.fullPath && isAuthSessionCurrent(owner.auth) && owner.actor === localStorage.getItem('userId')
    && owner.permissions === JSON.stringify(readPermissionCache()) && owner.menus === JSON.stringify(permissionMenus.value);
}
function clearRows() { alertRecords.value = []; rules.value = []; indicators.value = []; }
function retireContext() {
  contextEpoch++; readEpoch++; clearRows(); loading.value = false; readState.value = 'idle';
  ruleDialogVisible.value = false; resolveDialogVisible.value = false; resolveId.value = null; resolveNote.value = '';
  Object.assign(ruleForm, { patientId: null, indicatorCode: '', indicatorName: '', thresholdType: 'ABOVE', thresholdValue: '', alertLevel: 'WARNING' });
}
function storageChanged(event) {
  if (event.key == null || ['token','userId','username','realName','permissionSession','userMenus','userMenuNames','userRoleCodes'].includes(event.key)) retireContext();
}
function ownedRowCallback(action, id) {
  const owner = captureContext(), epoch = readEpoch;
  return () => { if (ownsContext(owner) && epoch === readEpoch) return action(id); };
}
function dialogCallback(action, kind) {
  const owner = captureContext(), epoch = kind === 'rule' ? ruleDialogEpoch : resolveDialogEpoch;
  return () => { if (ownsContext(owner) && epoch === (kind === 'rule' ? ruleDialogEpoch : resolveDialogEpoch)) return action(); };
}
function readRows(result) {
  if (result?.code !== 200 || !Array.isArray(result.data) || result.data.some(row => !row || typeof row !== 'object' || Array.isArray(row))) throw Object.assign(new Error('ALERT_READ_UNAVAILABLE'), { code: result?.code });
  return result.data;
}
async function loadData() {
  const epoch = ++readEpoch, owner = captureContext();
  clearRows(); loading.value = false; readState.value = 'idle';
  if (!owner.patient || !ownsContext(owner)) return;
  loading.value = true; readState.value = 'loading';
  const current = () => epoch === readEpoch && ownsContext(owner);
  try {
    if (!current()) return;
    const rRes = await listRecords(owner.patient, owner.filter);
    if (!current()) return;
    alertRecords.value = readRows(rRes);
    const ruRes = await listRules(owner.patient);
    if (!current()) return;
    rules.value = readRows(ruRes);
    const iRes = await listIndicators();
    if (!current()) return;
    indicators.value = readRows(iRes);
    readState.value = 'ready';
  } catch (error) {
    if (!current()) return;
    if ([401, 403].includes(Number(error?.response?.status || error?.status || error?.code))) retireContext();
    clearRows(); loading.value = false; readState.value = 'error';
  } finally {
    if (current()) loading.value = false;
  }
}

function validSourceInstant(value){
  if(typeof value!=='string')return false
  const m=value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/)
  if(!m)return false
  const [year,month,day,hour,minute,second]=m.slice(1).map(Number),leap=year%4===0 && (year%100!==0 || year%400===0),days=[31,leap?29:28,31,30,31,30,31,31,30,31,30,31]
  return month>=1 && month<=12 && day>=1 && day<=days[month-1] && hour<24 && minute<60 && second<60
}
function alertSourceLines(item){
  const keys=['sourceStatus','sourceBindingStatus','currentSourceStatus','sourceQualificationSemantics','sourceQualificationAt']
  if(item.alertType==='MEASUREMENT_FOLLOW_UP' || typeof item.sourceType==='string' && item.sourceType.length>0 && item.sourceType!=='MEDICAL_RECORD_ITEM')return []
  if(item.sourceType!=='MEDICAL_RECORD_ITEM')return ['Current source qualification unavailable']
  if(!keys.every(key=>Object.hasOwn(item,key)))return ['Current source qualification unavailable']
  const {sourceStatus:status,sourceBindingStatus:binding,currentSourceStatus:current,sourceQualificationSemantics:semantics,sourceQualificationAt:at}=item
  if(status==='SOURCE_UNAVAILABLE' && binding===null && current==='SOURCE_UNAVAILABLE' && semantics===null && at===null)return ['Current source qualification unavailable']
  const currentCopy={'CURRENT': 'Current source qualification: current', 'UNTRACKED': 'Current source: legacy handling', 'SOURCE_REVIEW_REQUIRED': 'Current source awaiting verification', 'SOURCE_CHANGED': 'Current source changed', 'SOURCE_INVALIDATED': 'Current source invalidated', 'SOURCE_UNAVAILABLE': 'Current source qualification unavailable'}, bindingCopy={'CURRENT_CONTENT': 'Historical link matches current content', 'UNBOUND': 'Historical link has no version binding', 'CONTENT_CHANGED': 'Historical link refers to an earlier source version', 'GENERATION_CHANGED': 'Historical link refers to an earlier source version', 'MISSING_ANCHOR': 'Historical source link could not be verified', 'SOURCE_MISSING': 'Historical source link could not be verified', 'STORE_CONFLICT': 'Historical source link could not be verified'}
  if(![status,binding,current].every(value=>typeof value==='string') || !Object.hasOwn(currentCopy,current) || !Object.hasOwn(bindingCopy,binding) || !['CURRENT','UNTRACKED','SOURCE_REVIEW_REQUIRED','SOURCE_CHANGED','SOURCE_INVALIDATED','SOURCE_UNAVAILABLE','UNKNOWN_NEEDS_RECHECK'].includes(status) || semantics!=='CURRENT_CHECK' || !validSourceInstant(at))return ['Current source qualification unavailable']
  if(current==='SOURCE_UNAVAILABLE' && !['MISSING_ANCHOR','SOURCE_MISSING'].includes(binding))return ['Current source qualification unavailable']
  const coherent=binding==='MISSING_ANCHOR' || binding==='SOURCE_MISSING' ? status==='SOURCE_UNAVAILABLE' && current==='SOURCE_UNAVAILABLE' : binding==='STORE_CONFLICT' ? status==='SOURCE_UNAVAILABLE' : binding==='UNBOUND' ? status===(current==='CURRENT'?'UNKNOWN_NEEDS_RECHECK':current) : binding==='GENERATION_CHANGED' ? status==='SOURCE_CHANGED' : binding==='CONTENT_CHANGED' ? status==='SOURCE_CHANGED' || status===current && !['CURRENT','UNTRACKED'].includes(current) : status===(['CURRENT','UNTRACKED'].includes(current)?'CURRENT':current)
  if(!coherent)return ['Current source qualification unavailable']
  return [bindingCopy[binding],currentCopy[current],...(status==='UNKNOWN_NEEDS_RECHECK'?['Historical link needs rechecking']:[]),'Current source checked at '+at]
}

function onIndicatorChange(code) {
  const ind = indicators.value.find(i => i.itemCode === code);
  if (ind) ruleForm.indicatorName = ind.itemName;
}

function showRuleDialog() {
  ruleDialogEpoch++;
  Object.assign(ruleForm, { patientId: currentPatientId.value, indicatorCode: '', indicatorName: '', thresholdType: 'ABOVE', thresholdValue: '', alertLevel: 'WARNING' });
  ruleDialogVisible.value = true;
}

async function handleSaveRule() {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  const dialogEpoch = ruleDialogEpoch;
  try {
    const res = await saveRule(ruleForm);
    if (!ownsContext(owner) || dialogEpoch !== ruleDialogEpoch) return;
    if (res.code === 200) { ElMessage.success('Alert rule saved.'); ruleDialogVisible.value = false; loadData(); }
    else ElMessage.error(res.msg || 'Failed to save');
  } catch (e) { if (ownsContext(owner) && dialogEpoch === ruleDialogEpoch) ElMessage.error('Failed to save'); }
}

async function handleToggleRule(id) {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  const res = await toggleRuleEnabled(id);
  if (!ownsContext(owner)) return;
  if (res.code === 200) loadData();
}

async function handleDeleteRule(id) {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  const res = await deleteRule(id);
  if (!ownsContext(owner)) return;
  if (res.code === 200) { ElMessage.success('Deleted successfully'); loadData(); }
}

async function handleAck(id) {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  const res = await acknowledge(id);
  if (!ownsContext(owner)) return;
  if (res.code === 200) { ElMessage.success('Alert acknowledged.'); loadData(); }
}

function showResolveDialog(row) {
  resolveDialogEpoch++;
  resolveId.value = row.id;
  resolveNote.value = '';
  resolveDialogVisible.value = true;
}

async function handleResolve() {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  const dialogEpoch = resolveDialogEpoch;
  if (!resolveNote.value.trim()) { ElMessage.warning('Document the action taken before resolving the alert.'); return; }
  const res = await resolve(resolveId.value, resolveNote.value);
  if (!ownsContext(owner) || dialogEpoch !== resolveDialogEpoch) return;
  if (res.code === 200) { ElMessage.success('Alert resolved.'); resolveDialogVisible.value = false; loadData(); }
}

async function handleDeleteRecord(id) {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  const res = await deleteRecord(id);
  if (!ownsContext(owner)) return;
  if (res.code === 200) { ElMessage.success('Deleted successfully'); loadData(); }
}

async function handleCheck() {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
  const res = await checkThresholds(currentPatientId.value);
  if (!ownsContext(owner)) return;
  if (res.code === 200) { ElMessage.success('Alert scan complete.'); loadData(); }
}

watch(currentPatientId, () => { retireContext(); loadData(); }, { flush: 'sync' });
watch(statusFilter, retireContext, { flush: 'sync' });
watch(() => route.fullPath, retireContext, { flush: 'sync' });
watch(permissionMenus, retireContext, { deep: true, flush: 'sync' });
watch(ruleDialogVisible, value => { if (!value) ruleDialogEpoch++; }, { flush: 'sync' });
watch(resolveDialogVisible, value => { if (!value) resolveDialogEpoch++; }, { flush: 'sync' });
onMounted(() => {
  window.addEventListener('auth-session-changed', retireContext);
  window.addEventListener('auth-session-cleared', retireContext);
  window.addEventListener('permissions-changed', retireContext);
  window.addEventListener('storage', storageChanged);
  loadData();
});
onUnmounted(() => {
  disposed = true; retireContext();
  window.removeEventListener('auth-session-changed', retireContext);
  window.removeEventListener('auth-session-cleared', retireContext);
  window.removeEventListener('permissions-changed', retireContext);
  window.removeEventListener('storage', storageChanged);
});
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>
.alert-source-facts { margin-top: 6px; color: var(--ink-500, #475569); font-size: 12px; overflow-wrap: anywhere; }
.alert-source-facts p { margin: 3px 0; }
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
