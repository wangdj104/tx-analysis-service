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
        <FollowUpList :patient-id="currentPatientId" />
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
              <p v-if="loading" role="status">正在加载告警信息…</p>
              <p v-else-if="readState === 'error'" role="alert">告警信息不可用，请切换筛选条件或重新选择患者后重试。</p>
              <el-table :empty-text="loading ? '正在加载告警信息…' : readState === 'error' ? '告警信息不可用，请切换筛选条件或重新选择患者后重试。' : undefined" :data="alertRecords" stripe class="app-data-table">
                <el-table-column prop="triggeredAt" label="触发时间" width="160" />
                <el-table-column prop="alertTitle" label="告警" min-width="240">
                  <template #default="{ row }">
                    <div>{{ row.alertTitle }}</div>
                    <section class="alert-source-facts"><b>已记录告警</b><p v-for="(line, index) in alertSourceLines(row)" :key="index">{{ line }}</p></section>
                  </template>
                </el-table-column>
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
                    <el-button v-if="row.status === 'PENDING'" link type="primary" size="small" :onClick="ownedRowCallback(handleAck, row.id)">确认</el-button>
                    <el-button v-if="row.status === 'CONFIRMED'" link type="success" size="small" :onClick="ownedRowCallback(showResolveDialog, row)">解决</el-button>
                    <el-popconfirm title="确认删除吗？" :onConfirm="ownedRowCallback(handleDeleteRecord, row.id)">
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
                    <el-switch :model-value="row.enabled === 1" :onChange="ownedRowCallback(handleToggleRule, row.id)" size="small" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="80" align="center">
                  <template #default="{ row }">
                    <el-popconfirm title="确认删除吗？" :onConfirm="ownedRowCallback(handleDeleteRule, row.id)">
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
        <el-button :onClick="dialogCallback(() => ruleDialogVisible = false, 'rule')">取消</el-button>
        <el-button type="primary" :onClick="dialogCallback(handleSaveRule, 'rule')">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveDialogVisible" title="解决告警" width="min(440px, 94vw)" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="处置措施">
          <el-input v-model="resolveNote" type="textarea" :rows="3" placeholder="必填：请记录处置措施和结果" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :onClick="dialogCallback(() => resolveDialogVisible = false, 'resolve')">取消</el-button>
        <el-button type="primary" :onClick="dialogCallback(handleResolve, 'resolve')">确认解决</el-button>
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

const LEVEL_MAP = { INFO: '提示', WARNING: '警告', CRITICAL: '危急' };
const STATUS_MAP = { PENDING: '待处理', CONFIRMED: '已确认', OBSERVING: '观察中', CONSULTED: '已问诊', RECHECKED: '已复查', RESOLVED: '已解决' };
const THRESHOLD_MAP = { ABOVE: '高于', BELOW: '低于', OUT_OF_RANGE: '超出范围' };

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
  if(item.sourceType!=='MEDICAL_RECORD_ITEM')return ['当前来源资格不可用']
  if(!keys.every(key=>Object.hasOwn(item,key)))return ['当前来源资格不可用']
  const {sourceStatus:status,sourceBindingStatus:binding,currentSourceStatus:current,sourceQualificationSemantics:semantics,sourceQualificationAt:at}=item
  if(status==='SOURCE_UNAVAILABLE' && binding===null && current==='SOURCE_UNAVAILABLE' && semantics===null && at===null)return ['当前来源资格不可用']
  const currentCopy={'CURRENT': '当前来源资格：有效', 'UNTRACKED': '当前来源：旧版处理', 'SOURCE_REVIEW_REQUIRED': '当前来源待核对', 'SOURCE_CHANGED': '当前来源已变更', 'SOURCE_INVALIDATED': '当前来源已失效', 'SOURCE_UNAVAILABLE': '当前来源资格不可用'}, bindingCopy={'CURRENT_CONTENT': '历史链接匹配当前内容', 'UNBOUND': '历史链接未绑定版本', 'CONTENT_CHANGED': '历史链接指向较早的来源版本', 'GENERATION_CHANGED': '历史链接指向较早的来源版本', 'MISSING_ANCHOR': '无法核实历史来源链接', 'SOURCE_MISSING': '无法核实历史来源链接', 'STORE_CONFLICT': '无法核实历史来源链接'}
  if(![status,binding,current].every(value=>typeof value==='string') || !Object.hasOwn(currentCopy,current) || !Object.hasOwn(bindingCopy,binding) || !['CURRENT','UNTRACKED','SOURCE_REVIEW_REQUIRED','SOURCE_CHANGED','SOURCE_INVALIDATED','SOURCE_UNAVAILABLE','UNKNOWN_NEEDS_RECHECK'].includes(status) || semantics!=='CURRENT_CHECK' || !validSourceInstant(at))return ['当前来源资格不可用']
  if(current==='SOURCE_UNAVAILABLE' && !['MISSING_ANCHOR','SOURCE_MISSING'].includes(binding))return ['当前来源资格不可用']
  const coherent=binding==='MISSING_ANCHOR' || binding==='SOURCE_MISSING' ? status==='SOURCE_UNAVAILABLE' && current==='SOURCE_UNAVAILABLE' : binding==='STORE_CONFLICT' ? status==='SOURCE_UNAVAILABLE' : binding==='UNBOUND' ? status===(current==='CURRENT'?'UNKNOWN_NEEDS_RECHECK':current) : binding==='GENERATION_CHANGED' ? status==='SOURCE_CHANGED' : binding==='CONTENT_CHANGED' ? status==='SOURCE_CHANGED' || status===current && !['CURRENT','UNTRACKED'].includes(current) : status===(['CURRENT','UNTRACKED'].includes(current)?'CURRENT':current)
  if(!coherent)return ['当前来源资格不可用']
  return [bindingCopy[binding],currentCopy[current],...(status==='UNKNOWN_NEEDS_RECHECK'?['历史链接需要重新核对']:[]),'当前来源核对时间：'+at]
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
    if (res.code === 200) { ElMessage.success('告警规则已保存。'); ruleDialogVisible.value = false; loadData(); }
    else ElMessage.error(res.msg || '保存失败');
  } catch (e) { if (ownsContext(owner) && dialogEpoch === ruleDialogEpoch) ElMessage.error('保存失败'); }
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
  if (res.code === 200) { ElMessage.success('删除成功'); loadData(); }
}

async function handleAck(id) {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  const res = await acknowledge(id);
  if (!ownsContext(owner)) return;
  if (res.code === 200) { ElMessage.success('告警已确认。'); loadData(); }
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
  if (!resolveNote.value.trim()) { ElMessage.warning('解决告警前请记录处置措施。'); return; }
  const res = await resolve(resolveId.value, resolveNote.value);
  if (!ownsContext(owner) || dialogEpoch !== resolveDialogEpoch) return;
  if (res.code === 200) { ElMessage.success('告警已解决。'); resolveDialogVisible.value = false; loadData(); }
}

async function handleDeleteRecord(id) {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  const res = await deleteRecord(id);
  if (!ownsContext(owner)) return;
  if (res.code === 200) { ElMessage.success('删除成功'); loadData(); }
}

async function handleCheck() {
  const owner = captureContext();
  if (!ownsContext(owner)) return;
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
  const res = await checkThresholds(currentPatientId.value);
  if (!ownsContext(owner)) return;
  if (res.code === 200) { ElMessage.success('告警扫描完成。'); loadData(); }
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
