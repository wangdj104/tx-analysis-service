<template>
  <el-container class="module-page bp-monitor-page">
    <el-main class="main-content">
      <div class="page-inner">
        <el-alert v-if="timelineLocator && timelineLocator.patientId!==currentPatientId" type="warning" :closable="false" :title="`请选择患者档案 #${timelineLocator.patientId} 以查看这条原始测量。`" />
        <div class="page-header bp-hero">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>血压血糖记录</h1>
                <p class="subtitle">记录日常血压、血糖自我监测数据，跟踪变化趋势。</p>
              </div>
            </div>
            <div v-if="rawOpen && raw.state.phase==='ready'" class="bp-summary" aria-label="记录概览">
              <div class="summary-item">
                <span class="summary-label">{{ot('pageCount')}}</span>
                <strong>{{ records.length }}</strong>
              </div>
              <div class="summary-item">
                <span class="summary-label">{{ot('pageLatestBp')}}</span>
                <strong>{{ latestBpText }}</strong>
                <small>{{ latestBpAt }}</small>
              </div>
              <div class="summary-item">
                <span class="summary-label">{{ot('pageLatestGlucose')}}</span>
                <strong>{{ latestBgText }}</strong>
                <small>{{ latestBgAt }}</small>
              </div>
            </div>
          </div>
        </div>

        <TrustedMeasurementTrend ref="trustedTrend" :patient-id="currentPatientId" :patient-name="currentPatientName" @changed="refreshRecords" />
        <details class="measurement-legacy-entry" @toggle="legacyFollowUpsVisible=$event.target.open"><summary>{{measurementText('legacyFollowUps')}}</summary><FollowUpList v-if="legacyFollowUpsVisible" :patient-id="currentPatientId" /></details>
        <MeasurementWorkflowStatus :flow="measurementFlow" source-type="BP_SELF_MONITOR" @saved="measurementReplaySaved" />
        <SourceMeasurementPanel v-if="measurementFlow.state.source||measurementFlow.state.receipt||measurementFlow.state.sourceReadPhase!=='idle'" ref="sourcePanel" :flow="measurementFlow" @changed="refreshRecords" />
        <div class="measurement-read-model"><button type="button" data-action="toggle-original" :disabled="measurementLocked" :aria-expanded="rawOpen" @click="rawOpen=!rawOpen">{{ot(rawOpen?'close':'open')}}</button></div>
        <div class="content-panel entry-panel">
          <div class="panel-head">
            <div class="list-panel-title">
              <el-icon><Plus /></el-icon>
              <h2 ref="editorHeading" tabindex="-1" class="editor-heading">{{ editingId ? editRecordLabel : '新增记录' }}</h2>
            </div>
            <el-radio-group v-model="form.measureType" @update:model-value="markEditDirty('measureType')" :disabled="saving || accessDenied || measurementLocked || ['loading','unavailable'].includes(detailPhase)" class="measure-switch">
              <el-radio-button value="BP">血压</el-radio-button>
              <el-radio-button value="BG">血糖</el-radio-button>
              <el-radio-button :value="form.measureType==='BOTH'?'BOTH':'BP_BG'">血压和血糖</el-radio-button>
            </el-radio-group>
          </div>

          <p class="submission-patient">正在为 {{ currentPatientName || (currentPatientId ? '当前患者档案' : '请选择患者') }} · 患者档案 #{{ currentPatientId || '—' }}</p>
          <el-form :model="form" :disabled="saving || accessDenied || measurementLocked || ['loading','unavailable'].includes(detailPhase)" label-position="top" ref="formRef" :rules="formRules" class="entry-form">
            <div class="form-grid form-grid--meta">
              <el-form-item label="记录日期" prop="recordDate">
                <el-date-picker v-model="form.recordDate" @update:model-value="markEditDirty('recordDate')" value-format="YYYY-MM-DD" :clearable="false" />
              </el-form-item>
              <el-form-item label="记录时间" prop="recordTime">
                <el-input v-if="editingId" v-model="form.recordTime" @update:model-value="markEditDirty('recordTime')" :placeholder="awText('preservedUnknown')" />
                <el-time-picker v-else v-model="form.recordTime" @update:model-value="markEditDirty('recordTime')" format="HH:mm" value-format="HH:mm" placeholder="选填" />
              </el-form-item>
            </div>

            <div class="metric-grid" :class="{ 'metric-grid--single': !showBpFields || !showBgFields }">
              <section v-if="showBpFields" class="metric-section metric-section--bp">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>血压</span>
                </div>
                <div class="field-grid field-grid--two">
                  <el-form-item label="收缩压" prop="systolicBp">
                    <el-input v-model="form.systolicBp" @update:model-value="markEditDirty('systolicBp')"  type="text" inputmode="decimal" />
                    <span class="unit-text">mmHg</span>
                  </el-form-item>
                  <el-form-item label="舒张压" prop="diastolicBp">
                    <el-input v-model="form.diastolicBp" @update:model-value="markEditDirty('diastolicBp')"  type="text" inputmode="decimal" />
                    <span class="unit-text">mmHg</span>
                  </el-form-item>
                </div>
              </section>

              <section v-if="showBgFields" class="metric-section metric-section--bg">
                <div class="metric-title">
                  <span class="metric-dot"></span>
                  <span>血糖</span>
                </div>
                <div class="field-grid field-grid--two">
                  <el-form-item label="血糖值" prop="bloodGlucose">
                    <el-input v-model="form.bloodGlucose" @update:model-value="markEditDirty('bloodGlucose')"  type="text" inputmode="decimal" />
                  </el-form-item>
                  <el-form-item label="单位">
                    <el-select v-model="form.bgUnit" @update:model-value="markEditDirty('bgUnit')" :placeholder="awText(editingId ? 'preservedUnknown' : 'chooseUnit')">
                      <el-option v-if="editingId && form.bgUnit != null && form.bgUnit !== '' && !['mmol/L','mg/dL'].includes(form.bgUnit)" :value="form.bgUnit" :label="form.bgUnit?.trim() ? form.bgUnit : awText('preservedUnknown')" />
                      <el-option label="mmol/L" value="mmol/L" />
                      <el-option label="mg/dL" value="mg/dL" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="测量时段">
                    <el-select v-model="form.measurePeriod" @update:model-value="markEditDirty('measurePeriod')" :placeholder="awText(editingId ? 'preservedUnknown' : 'choosePeriod')">
                      <el-option v-if="editingId && form.measurePeriod != null && form.measurePeriod !== '' && !['Fasting','After Meal2h','Random'].includes(form.measurePeriod)" :value="form.measurePeriod" :label="form.measurePeriod?.trim() ? form.measurePeriod : awText('preservedUnknown')" />
                      <el-option label="空腹" value="Fasting" />
                      <el-option label="餐后 2 小时" value="After Meal2h" />
                      <el-option label="随机" value="Random" />
                    </el-select>
                  </el-form-item>
                </div>
              </section>
            </div>

            <el-form-item label="备注" class="remark-field">
              <el-input v-model="form.remark" @update:model-value="markEditDirty('remark')" type="textarea" :rows="2" maxlength="500" placeholder="可记录测量场景、餐前餐后及身体感受等。" />
            </el-form-item>

            <el-form-item v-if="measurementFlow.state.capabilityPhase==='enabled'" :label="awText('offset')"><el-input v-model="measurementOffset" placeholder="+08:00" /><p>{{awText('offsetHelp')}}</p></el-form-item>
            <div class="form-actions">
              <el-button type="primary" @click="handleSave" :loading="saving" :disabled="accessDenied || measurementLocked || ['loading','unavailable'].includes(detailPhase) || !['enabled','disabled'].includes(measurementFlow.state.capabilityPhase) || (measurementFlow.state.capabilityPhase==='enabled'&&!measurementFlow.state.capabilities?.canAdopt)">
                <el-icon><Check /></el-icon>{{ editingId ? '更新记录' : '保存记录' }}
              </el-button>
              <el-button v-if="editingId" @click="cancelEdit" :disabled="saving || accessDenied || measurementLocked || detailPhase==='loading'">取消编辑</el-button>
              <el-button v-else @click="resetForm" :disabled="saving || accessDenied || measurementLocked || detailPhase==='loading'">重置</el-button>
            </div>
          </el-form>
        </div>

        <section v-if="rawOpen" ref="rawHeading" tabindex="-1" class="measurement-read-model" data-testid="self-original-records">
        <h2>{{ot('originalTitle')}}</h2><p>{{awText('legacyTrend')}}</p>
        <OriginalMeasurementPageStatus :state="raw.state" :locked="measurementLocked" @refresh="refreshRecords" @retry="raw.refresh({keepPosition:true})" @next="raw.next()" @previous="raw.previous()" />
        <div v-if="detailPhase!=='idle'">
        <h3 v-if="!detailIntent?.edit" ref="notesHeading" tabindex="-1" class="editor-heading">{{ot('notes')}}</h3>
        <p v-if="detailPhase==='loading'" role="status">{{ot('detailLoading')}}</p>
        <div v-if="detailPhase==='unavailable'" role="alert"><p>{{ot('detailError')}}</p><button type="button" @click="retryDetail">{{ot('retry')}}</button></div>
        <div v-if="originalDetail && detailPhase==='ready'" class="mr-notice" data-testid="original-detail"><OriginalImportFacts :original-import="originalDetail.originalImport" /><h3>当前记录</h3><p>{{selfBp(originalDetail)}} · {{selfGlucose(originalDetail)}}</p><p>{{selfTime(originalDetail)}} · #{{originalDetail.id}}</p><p v-if="Object.hasOwn(originalDetail,'remark')" style="white-space:pre-wrap">{{originalDetail.remark ?? ot('noNotes')}}</p><p v-else>{{ot('detailUnloaded')}}</p><button v-if="!editingId" type="button" @click="closeNotesDetail">{{ot('sourceClose')}}</button></div>
        </div>
        <div class="content-panel records-panel">
          <div class="toolbar records-toolbar">
            <div class="list-panel-title">
              <el-icon><TrendCharts /></el-icon>
              <span>监测记录</span>
              <span v-if="records.length" class="list-count">共 {{ records.length }} 条</span>
            </div>
            <div class="records-tools">
              <el-radio-group v-model="filterType" :disabled="measurementLocked || saving" class="filter-switch">
                <el-radio-button value="">全部</el-radio-button>
                <el-radio-button value="BP">血压</el-radio-button>
                <el-radio-button value="BG">血糖</el-radio-button>
              </el-radio-group>
              <el-button @click="refreshRecords" :loading="loading" :disabled="measurementLocked || saving">
                <el-icon><Refresh /></el-icon>刷新
              </el-button>
            </div>
          </div>

          <div v-if="lastSuccessAt" class="mobile-record-list">
            <p v-if="!records.length" class="record-list-empty">暂无记录</p>
            <article v-for="row in records" :key="row.id" class="mobile-record-card" :data-record-id="row.id">
              <div class="record-card-meta">
                <time>{{selfTime(row)}}</time><span>#{{row.id}}</span>
                <abbr class="record-card-type" :title="measureLabel(row.measureType)" :aria-label="measureLabel(row.measureType)">{{ row.measureType === 'BP' ? '血压' : row.measureType === 'BG' ? '血糖' : '血压 + 血糖' }}</abbr>
              </div>
              <div class="record-card-readings">
                <span v-if="row.systolicBp != null || row.diastolicBp != null" class="record-card-bp">{{ row.systolicBp ?? '—' }}/{{ row.diastolicBp ?? '—' }} mmHg</span>
                <span v-if="row.bloodGlucose != null" class="record-card-glucose">{{selfGlucose(row)}}</span>
                <span v-if="row.systolicBp == null && row.diastolicBp == null && row.bloodGlucose == null">—</span>
              </div>
              <p>{{row.measurePeriod || ot('periodUnknown')}}</p><p>{{ot('unassessed')}}</p><p v-if="originalIssues(row).length">{{originalIssues(row).join(' · ')}}</p>
              <button type="button" :disabled="measurementLocked||saving" @click="openDetail(row,false,$event)">{{ot('notes')}}</button>
              <div class="record-card-actions">
                <el-button link type="primary" :ref="node => setEditOpener(row.id, node, 'mobile')" @click="handleEdit(row, $event, 'mobile')" :disabled="saving || accessDenied || measurementLocked || detailPhase==='loading'">{{measurementFlow.state.capabilityPhase==='enabled'?awText('sourceOpen'):'编辑'}}</el-button>
                <el-button v-if="measurementFlow.state.capabilityPhase==='enabled'&&measurementFlow.state.capabilities?.canAdopt" link type="danger" :disabled="measurementLocked" @click="inspectSource(row,'invalidate')">{{awText('invalidate')}}</el-button><el-popconfirm v-if="measurementFlow.state.capabilityPhase==='disabled'" title="确认删除这条记录？" @confirm="handleDelete(row)">
                  <template #reference><el-button link type="danger">删除</el-button></template>
                </el-popconfirm>
              </div>
            </article>
          </div>

          <div v-if="lastSuccessAt" class="table-wrap records-desktop-table">
            <el-table :data="records" stripe class="app-data-table app-data-table--list" v-loading="loading" empty-text="暂无记录">
              <el-table-column :label="ot('originalTime')" min-width="210"><template #default="{row}">{{selfTime(row)}}</template></el-table-column>
              <el-table-column prop="measureType" label="类型" width="96" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="measureTagType(row.measureType)">{{ measureLabel(row.measureType) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="血压" width="120">
                <template #default="{ row }">
                  <span class="value-text">{{selfBp(row)}}</span>
                </template>
              </el-table-column>
              <el-table-column label="血糖" width="130">
                <template #default="{ row }">
                  <span class="value-text">{{selfGlucose(row)}}</span>
                </template>
              </el-table-column>
              <el-table-column prop="measurePeriod" label="时段" width="100" align="center">
                <template #default="{ row }">{{ periodLabel(row.measurePeriod) }}</template>
              </el-table-column>
              <el-table-column :label="ot('rawVerification')" min-width="210"><template #default="{row}">#{{row.id}} · {{row.verificationStatus || ot('unknown')}}<p>{{originalIssues(row).join(' · ')}}</p></template></el-table-column>
              <el-table-column :label="ot('notes')" min-width="180"><template #default="{row}"><button type="button" :disabled="measurementLocked||saving" @click="openDetail(row,false,$event)">{{ot('notes')}}</button></template></el-table-column>
              <el-table-column label="操作" width="120" align="center" fixed="right">
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button link type="primary" size="small" :ref="node => setEditOpener(row.id, node)" @click="handleEdit(row, $event)" :disabled="saving || accessDenied || measurementLocked || detailPhase==='loading'">{{measurementFlow.state.capabilityPhase==='enabled'?awText('sourceOpen'):'编辑'}}</el-button>
                    <el-button v-if="measurementFlow.state.capabilityPhase==='enabled'&&measurementFlow.state.capabilities?.canAdopt" link type="danger" :disabled="measurementLocked" @click="inspectSource(row,'invalidate')">{{awText('invalidate')}}</el-button><el-popconfirm v-if="measurementFlow.state.capabilityPhase==='disabled'" title="确认删除这条记录吗？" @confirm="handleDelete(row)">
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
        </section>
      </div>
    </el-main>
  </el-container>
</template>

<script setup>
import {measurementFits,sameMeasurementDecimal} from '@/utils/measurementNumbers';
import TrustedMeasurementTrend from '@/components/measurement-read-model/TrustedMeasurementTrend.vue';
import {measurementText} from '@/utils/measurementReadModelCopy';
import {useAlertWorkflowNavigation} from '@/composables/useAlertWorkflowNavigation'
import {nextTick} from 'vue';
import FollowUpList from '@/components/alert-workflow/FollowUpList.vue';
import SourceMeasurementPanel from '@/components/alert-workflow/SourceMeasurementPanel.vue';
import MeasurementWorkflowStatus from '@/components/alert-workflow/MeasurementWorkflowStatus.vue';
import {useAlertWorkflow} from '@/composables/useAlertWorkflow';
import {measurementInstant,localOffset,selfMonitorInput} from '@/utils/alertWorkflowForms';
import {t as awText} from '@/utils/alertWorkflowCopy';
import { localDateKey } from '@/utils/familyHealth';
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Check, Plus, Refresh, TrendCharts } from '@element-plus/icons-vue';
import { updateBpSelfMonitorRecord, deleteBpSelfMonitorRecord } from '@/api/bpSelfMonitor.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import OriginalMeasurementPageStatus from '@/components/measurement-read-model/OriginalMeasurementPageStatus.vue';
import {useOriginalMeasurementPage} from '@/composables/useOriginalMeasurementPage';
import {selfDetail} from '@/api/originalMeasurements';
import OriginalImportFacts from '@/components/OriginalImportFacts.vue';
import {originalText as ot} from '@/utils/originalMeasurementCopy';
import {selfTime,selfBp,selfGlucose,originalIssues,originalEditPatch,originalEditableFields,validOriginalDetail} from '@/utils/originalMeasurementPresentation';
import {captureAuthSession,isAuthSessionCurrent,readPermissionCache} from '@/utils/authSession';

import {useRoute} from 'vue-router';
import {parseOriginalMeasurementRoute} from '@/utils/workspaceAccess';
const sourceRoute=useRoute();
const timelineLocator=computed(()=>parseOriginalMeasurementRoute(sourceRoute.fullPath));

const { currentPatientId, currentPatientName } = useCurrentPatient();
const trustedTrend=ref(null),legacyFollowUpsVisible=ref(false);
const measurementFlow=useAlertWorkflow(currentPatientId),measurementOffset=ref(localOffset()),sourcePanel=ref(null);
useAlertWorkflowNavigation(measurementFlow)
// 已知来源写入成功后重新读取第一页，不沿用旧游标或自动重放写入。
watch(()=>measurementFlow.state.sourcePhase,phase=>{if(phase==='succeeded')trustedTrend.value?.refresh()},{flush:'sync'});
const measurementLocked=computed(()=>['submitting','uncertain'].includes(measurementFlow.state.sourcePhase));
watch(currentPatientId,()=>{measurementOffset.value=localOffset();measurementFlow.loadCapabilities()},{immediate:true});
async function measurementReplaySaved(){if(measurementFlow.state.sourceOperation==='save')resetForm();await refreshRecords()}
async function inspectSource(row,operation){
  if(measurementLocked.value)return;
  const owner={patientId:currentPatientId.value,generation:raw.state.generation};originalSourceOwner=owner;
  const result=await measurementFlow.readSource('BP_SELF_MONITOR',row.id);
  if(result.status==='succeeded'){await nextTick();if(originalSourceOwner===owner && owner.patientId===currentPatientId.value && owner.generation===raw.state.generation)sourcePanel.value?.beginOperation(operation)}
}

const loading = ref(false);
const saving = ref(false);
const records = ref([]);
const accessDenied = ref(false);
const lastSuccessAt = ref('');
const latestBpRecord = computed(() => newestSummaryRecord(item => item.systolicBp != null || item.diastolicBp != null));
const latestBgRecord = computed(() => newestSummaryRecord(item => item.bloodGlucose != null));
function newestSummaryRecord(predicate) { return records.value.find(predicate); }
function readingTime(row) { return row ? selfTime(row) : ot('noReading'); }
const latestBpAt = computed(() => readingTime(latestBpRecord.value));
const latestBgAt = computed(() => readingTime(latestBgRecord.value));
const filterType = ref('');
const rawOpen=ref(false),rawHeading=ref(null),notesHeading=ref(null),originalDetail=ref(null),detailPhase=ref('idle'),editDirty=reactive({});
let detailEpoch=0,detailController=null,detailIntent=null,originalSourceOwner=null;
const editingId = ref(null);
const formRef = ref(null);
const editorHeading = ref(null);
const editRecordLabel = ref('');
const editOpeners = new Map();
let editOrigin = null;
function setEditOpener(id, node, surface = 'desktop') {
  const key = surface === 'mobile' ? `mobile:${id}` : id;
  if (node) editOpeners.set(key, node.$el || node);
  else editOpeners.delete(key);
}
function focusEditor(context) {
  Promise.resolve().then(() => {
    if (!isCurrentContext(context) || !editingId.value) return;
    const heading = editorHeading.value;
    if (!heading?.isConnected) return;
    const behavior = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth';
    heading.scrollIntoView?.({ behavior, block: 'start' });
    heading.focus?.({ preventScroll: true });
  });
}
function eligibleFocusTarget(node) {
  if (!node?.isConnected || node.matches?.(':disabled') || node.closest?.('[hidden],[inert]') || node.getClientRects?.().length === 0) return false;
  const visibility = typeof window === 'undefined' ? '' : window.getComputedStyle?.(node).visibility;
  return visibility !== 'hidden' && visibility !== 'collapse';
}
async function restoreEditOrigin(origin, context, notes = null) {
  await (notes ? nextTick() : Promise.resolve());
  if (!origin || !isCurrentContext(context) || editingId.value || (notes && !notes.owns())) return;
  const preferred = notes ? origin.opener : editOpeners.get(origin.key ?? origin.id);
  const candidates = notes ? [origin.opener] : [preferred, origin.opener, editOpeners.get(origin.id), editOpeners.get(`mobile:${origin.id}`)];
  const target = candidates.find(eligibleFocusTarget);
  if (!target) {
    if (!notes || eligibleFocusTarget(rawHeading.value)) rawHeading.value?.focus?.();
    return;
  }
  if (typeof window !== 'undefined') window.scrollTo?.({ top: origin.scrollY, behavior: 'auto' });
  // A responsive reflow can hide the original surface and move its same-record counterpart.
  if (target !== preferred && target !== origin.opener) {
    target.scrollIntoView?.({ behavior: 'auto', block: 'nearest' });
    // Native edge alignment can round fractional layout coordinates. Keep the
    // responsive counterpart comfortably inside the viewport before focusing it.
    const rect = target.getBoundingClientRect?.();
    if (rect && typeof window !== 'undefined' && Number.isFinite(window.innerHeight)) {
      const inset = 8;
      const offset = rect.top < inset ? Math.floor(rect.top - inset)
        : rect.bottom > window.innerHeight - inset ? Math.ceil(rect.bottom - window.innerHeight + inset) : 0;
      if (offset) window.scrollTo?.({ top: window.scrollY + offset, behavior: 'auto' });
    }
  }
  target.focus?.({ preventScroll: true });
}
async function cancelEdit() {
  if (saving.value) return;
  const origin = editOrigin;
  resetForm();
  await restoreEditOrigin(origin, captureContext());
}
let patientEpoch = 0;
let editorEpoch = 0;
let saveRequest = 0;
let disposed = false;
const pendingDeletes = new Map();

function captureReaderSession() { return JSON.stringify({...captureAuthSession(),actor:localStorage.getItem('userId'),permissions:readPermissionCache()}); }
let readerSession = captureReaderSession();
let readerSessionEpoch = 0;
function ownsReaderSession(session = readerSession, epoch = readerSessionEpoch) {
  return epoch === readerSessionEpoch && session === captureReaderSession();
}
function readerSessionChanged(event) {
  if (disposed || (event?.key != null && !['token','userId','username','realName','permissionSession','userMenus','userRoleCodes'].includes(event.key))) return;
  readerSessionEpoch++;
  rawOpen.value=false;raw.clear();clearDetail();
  clearProtectedRecords();
}
function ensureReaderSession() {
  if (ownsReaderSession()) return true;
  readerSessionChanged();
  return false;
}

function captureContext() {
  return { patientId: currentPatientId.value, patientEpoch, editorEpoch, session: readerSession, sessionEpoch: readerSessionEpoch, detailPending: ['loading','unavailable'].includes(detailPhase.value) };
}

function isCurrentContext(context, includeEditor = true) {
  return !disposed && !!context.patientId && context.patientId === currentPatientId.value
    && context.patientEpoch === patientEpoch && ownsReaderSession(context.session, context.sessionEpoch) && (!includeEditor || context.editorEpoch === editorEpoch);
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
  bgUnit: '',
  measurePeriod: '',
  remark: ''
});

const showBpFields = computed(() => ['BP','BP_BG','BOTH'].includes(form.measureType));
const showBgFields = computed(() => ['BG','BP_BG','BOTH'].includes(form.measureType));
const latestBpText = computed(() => {
  const row = latestBpRecord.value;
  return row ? selfBp(row) : ot('noReading');
});
const latestBgText = computed(() => {
  const row = latestBgRecord.value;
  return row ? selfGlucose(row) : ot('noReading');
});

const formRules = {
  recordDate: [{ required: true, message: '请选择日期', trigger: 'change' }],
  measureType: [{ required: true, message: '请选择类型', trigger: 'change' }],
  systolicBp: [{ validator: measurementValidator('bp', 50, 250, true), trigger: 'change' }],
  diastolicBp: [{ validator: measurementValidator('bp', 30, 150, true), trigger: 'change' }],
  bloodGlucose: [{ validator: measurementValidator('bg'), trigger: 'change' }]
};
function measurementValidator(kind, min, max, integer = false) {
  return (_rule, value, callback) => {
    if (kind === 'bp' ? !showBpFields.value : !showBgFields.value) return callback();
    const number = Number(value), editing = !!editingId.value, unit = String(form.bgUnit ?? '').trim().toLowerCase();
    // 旧编辑沿原服务的有效输入范围，不按未知单位夹值，也不强制补单位。
    const lower = editing && kind === 'bp' ? (min === 50 ? 40 : 20) : min;
    const upper = editing && kind === 'bp' ? (max === 250 ? 300 : 200) : max;
    const invalid = value == null || String(value).trim() === '' || !Number.isFinite(number)
      || (integer && !measurementFits(value,10,0))
      || (kind === 'bg' && (!editing || !sameMeasurementDecimal(value,originalDetail.value?.bloodGlucose)) && !measurementFits(value,5,2))
      || (kind === 'bp' ? number < lower || number > upper
        : number <= 0 || (editing ? !!unit && !['mmol/l', 'mg/dl'].includes(unit) : !['mmol/l', 'mg/dl'].includes(unit) || number > (unit === 'mg/dl' ? 900 : 50)));
    callback(invalid ? new Error('请输入所选类型所需的有效测量值') : undefined);
  };
}


function measureLabel(type) {
  return type === 'BP' ? '血压' : type === 'BG' ? '血糖' : '血压和血糖';
}

function periodLabel(period) {
  return ({ Fasting: '空腹', 'After Meal2h': '餐后 2 小时', Random: '随机' })[period] || period || ot('periodUnknown');
}

function measureTagType(type) {
  return type === 'BP' ? 'primary' : type === 'BG' ? 'success' : 'warning';
}

// 仅已知开关模式参与原值读所有权；可信接口暂不可用不等于原权限被拒绝。
const originalMode=ref(['enabled','disabled'].includes(measurementFlow.state.capabilityPhase)?measurementFlow.state.capabilityPhase:null);
const raw=useOriginalMeasurementPage(currentPatientId,computed(()=>({sourceType:'BP_SELF_MONITOR',filter:filterType.value||null})),rawOpen,{contextKey:originalMode});
watch(()=>raw.state.generation,()=>{
  originalSourceOwner=null;clearDetail();if(editingId.value)resetForm();
  // 在途定位也属于旧页；尚未确认的写命令仍保留原重试包。
  if(!measurementLocked.value && (measurementFlow.state.source || measurementFlow.state.receipt || measurementFlow.state.sourceLocator || measurementFlow.state.sourceReadPhase==='loading')){measurementFlow.reset();measurementFlow.loadCapabilities()}
},{flush:'sync'});
watch(()=>[raw.state.phase,raw.state.items],()=>{
  records.value=raw.state.items;loading.value=raw.state.phase==='loading';lastSuccessAt.value=raw.state.phase==='ready'?new Date().toISOString():'';
  if(raw.state.phase==='denied'){accessDenied.value=true;resetForm(true)}else if(raw.state.phase==='ready')accessDenied.value=false;
},{flush:'sync'});
watch(()=>measurementFlow.state.capabilityPhase,(phase,old)=>{
  if(['enabled','disabled'].includes(phase))originalMode.value=phase;
  if(phase!==old){clearDetail();if(editingId.value)resetForm()}
  if(phase==='denied'&&originalSourceOwner?.patientId===currentPatientId.value&&originalSourceOwner.generation===raw.state.generation)raw.deny();
},{flush:'sync'});

function resetForm(force=false) {
  if(measurementLocked.value && force!==true)return;
  clearDetail();
  invalidateEditor();
  editingId.value = null;
  editRecordLabel.value = '';
  editOrigin = null;
  form.recordDate = localDateKey();
  form.recordTime = '';
  form.measureType = 'BP';
  form.systolicBp = null;
  form.diastolicBp = null;
  form.bloodGlucose = null;
  form.bgUnit = '';
  form.measurePeriod = '';
  form.remark = '';
  // Defaults above own the new draft; resetFields can restore another patient's cached values.
  formRef.value?.clearValidate?.();
}

function isDeniedRead(error) {
  return [401, 403].includes(Number(error.response?.status || error.status || error.code));
}
function clearProtectedRecords() {
  accessDenied.value = true;
  patientEpoch++;
  pendingDeletes.clear();
  records.value = [];
  lastSuccessAt.value = '';
  loading.value = false;
  editOpeners.clear();
  resetForm(true);
}
async function loadRecords() { return raw.refresh(); }
async function refreshRecords() { if(measurementLocked.value)return {status:'locked'};return raw.refresh(); }

async function handleSave() {
  if (saving.value || measurementLocked.value || disposed || !ensureReaderSession() || accessDenied.value) return;
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
  if (!formRef.value) return;
  const context = captureContext();
  if(context.detailPending)return;
  const request = ++saveRequest;
  const isEdit = !!editingId.value;
  if (isEdit && (detailPhase.value!=='ready' || originalDetail.value?.id!==editingId.value)) return;
  const data = { ...(isEdit ? originalEditPatch(originalDetail.value,form,editDirty) : form), patientId: context.patientId, id: editingId.value || undefined };
  const isCurrentSave = () => request === saveRequest && isCurrentContext(context);
  saving.value = true;
  try {
    try { await formRef.value.validate(); } catch { return; }
    if (!isCurrentSave()) return;
    for(const key of ['systolicBp','diastolicBp','bloodGlucose'])if(Object.hasOwn(data,key)&&data[key]!=null&&String(data[key]).trim()!==''){if(isEdit&&sameMeasurementDecimal(data[key],originalDetail.value[key]))delete data[key];else data[key]=Number(data[key]);}
    let res;
    if(isEdit){if(measurementFlow.state.capabilityPhase!=='disabled'){ElMessage.warning(awText('sourceOpen'));return}res=await updateBpSelfMonitorRecord(data)}
    else {delete data.id;Object.assign(data,selfMonitorInput(data,true));if(measurementFlow.state.capabilityPhase==='enabled'){data.occurredAt=measurementInstant(`${data.recordDate}T${data.recordTime}`,measurementOffset.value)}const result=await measurementFlow.saveSource('BP_SELF_MONITOR',data);if(result.status!=='succeeded')return;res={code:200,data:result.data}}
    if (!isCurrentSave()) return;
    if (res.code === 200) {
      ElMessage.success(isEdit ? '更新成功' : '保存成功');
      const origin = editOrigin;
      resetForm();
      const focusContext = captureContext();
      await refreshRecords();
      await restoreEditOrigin(origin, focusContext);
    } else {
      ElMessage.error(res.msg || '操作失败');
    }
  } catch (e) {
    if (isCurrentSave()) ElMessage.error('操作失败');
  } finally { if (isCurrentSave()) saving.value = false; }
}

async function handleEdit(row,event,surface='desktop') {
  if(measurementFlow.state.capabilityPhase==='enabled'){await inspectSource(row,null);return}
  if(measurementFlow.state.capabilityPhase!=='disabled'){ElMessage.warning(awText('capabilityUnknown'));return}
  await openDetail(row,true,event,surface);
}
function markEditDirty(key) { if(editingId.value && detailPhase.value==='ready') editDirty[key]=true; }
function clearDetail() {
  detailEpoch++;detailController?.abort();detailController=null;detailIntent=null;originalDetail.value=null;detailPhase.value='idle';
  for(const key of Object.keys(editDirty))delete editDirty[key];
}
async function openDetail(row,edit=false,event,surface='desktop',notesOrigin=null) {
  if(saving.value||measurementLocked.value||disposed||accessDenied.value||!ensureReaderSession()||Number(row.patientId)!==currentPatientId.value)return;
  if(edit || editingId.value)resetForm();
  clearDetail();
  const key=surface==='mobile'?`mobile:${row.id}`:row.id;
  // Native currentTarget may be cleared after this handler yields. Retry keeps this origin.
  const origin=edit?null:(notesOrigin||{id:row.id,key,opener:event?.currentTarget,scrollY:window.scrollY});
  detailIntent={row:{id:row.id,patientId:row.patientId},edit,event,surface,origin};
  const intent=detailIntent,version=++detailEpoch,context=captureContext(),auth={...captureAuthSession(),actorId:localStorage.getItem('userId')},permission=JSON.stringify(readPermissionCache()),mode=measurementFlow.state.capabilityPhase;
  const sourceOwner=originalSourceOwner,sourceLocator=measurementFlow.state.sourceLocator;
  const owns=()=>version===detailEpoch&&detailIntent===intent&&(edit||(sourceOwner===originalSourceOwner&&sourceLocator===measurementFlow.state.sourceLocator))&&isCurrentContext(context)&&isAuthSessionCurrent(auth)&&auth.actorId===localStorage.getItem('userId')&&permission===JSON.stringify(readPermissionCache())&&mode===measurementFlow.state.capabilityPhase&&rawOpen.value;
  intent.owns=owns;
  detailController=new AbortController();detailPhase.value='loading';
  try {
    if(!edit){
      await nextTick();
      if(!owns())return;
      const heading=notesHeading.value;
      if(eligibleFocusTarget(heading)){
        const toolbarBounds=document.querySelector?.('.workspace-topbar')?.getBoundingClientRect();
        const clearance=toolbarBounds?Math.max(0,toolbarBounds.bottom)+16:88;
        heading.style?.setProperty('scroll-margin-top',`${clearance}px`);
        const behavior=typeof window!=='undefined'&&window.matchMedia?.('(prefers-reduced-motion: reduce)').matches?'auto':'smooth';
        heading.scrollIntoView?.({behavior,block:'start'});
        heading.focus?.({preventScroll:true});
      }
    }
    const response=await selfDetail({patientId:context.patientId,recordId:row.id},{expectedAuth:auth,signal:detailController.signal});
    if(!owns())return;
    const item=response?.data;
    if(response?.code && response.code!==200)throw Object.assign(new Error('DETAIL_FAILED'),{code:response.code});
    if(!validOriginalDetail(item,row.id,context.patientId))throw new Error('INVALID_DETAIL');
    originalDetail.value={...item};detailPhase.value='ready';
    if(edit){
      for(const field of originalEditableFields)form[field]=item[field];
      editingId.value=item.id;editOrigin={id:item.id,key,opener:event?.currentTarget||editOpeners.get(key),scrollY:window.scrollY};
      editRecordLabel.value=ot('sourceOpen')+' · '+selfTime(item);focusEditor(captureContext());
    }
  }catch(error){if(!owns())return;if(isDeniedRead(error)){clearProtectedRecords();raw.deny()}else{originalDetail.value=null;detailPhase.value='unavailable';detailIntent=intent}}
  finally{if(version===detailEpoch&&!owns())clearDetail()}
}
function retryDetail(){const intent=detailIntent;if(intent)return openDetail(intent.row,intent.edit,intent.event,intent.surface,intent.origin)}
async function closeNotesDetail(){
  const intent=detailIntent,canReturn=intent&&!intent.edit&&intent.owns();
  const context=captureContext(),sourceOwner=originalSourceOwner,sourceLocator=measurementFlow.state.sourceLocator,mode=measurementFlow.state.capabilityPhase;
  clearDetail();
  if(!canReturn)return;
  const version=detailEpoch;
  await restoreEditOrigin(intent.origin,context,{owns:()=>version===detailEpoch&&!detailIntent&&rawOpen.value&&mode===measurementFlow.state.capabilityPhase&&sourceOwner===originalSourceOwner&&sourceLocator===measurementFlow.state.sourceLocator});
}

async function handleDelete(row) {
  if(measurementFlow.state.capabilityPhase==='enabled'){await inspectSource(row,'invalidate');return}
  if(measurementFlow.state.capabilityPhase!=='disabled'){ElMessage.warning(awText('capabilityUnknown'));return}
  if (disposed || !ensureReaderSession() || accessDenied.value || !currentPatientId.value || row.patientId !== currentPatientId.value || pendingDeletes.has(row.id)) return;
  const context = captureContext();
  const operation = {};
  pendingDeletes.set(row.id, operation);
  try {
    const res = await deleteBpSelfMonitorRecord(row.id);
    if (!isCurrentContext(context, false)) return;
    if (res.code === 200) {
      ElMessage.success('删除成功');
      refreshRecords();
    }
  } catch (e) {
    if (isCurrentContext(context, false)) ElMessage.error('删除失败');
  } finally { if (pendingDeletes.get(row.id) === operation) pendingDeletes.delete(row.id); }
}

watch(currentPatientId, () => {
  rawOpen.value=false;raw.clear();clearDetail();
  readerSession = captureReaderSession();
  readerSessionEpoch++;
  patientEpoch++;
  pendingDeletes.clear();
  accessDenied.value = false;
  lastSuccessAt.value = '';
  records.value = [];
  editOpeners.clear();
  loading.value = false;
  resetForm();
  refreshRecords();
}, { flush: 'sync' });

onMounted(() => {
  if (typeof window !== 'undefined') { window.addEventListener?.('storage', readerSessionChanged); window.addEventListener?.('auth-session-cleared', readerSessionChanged); window.addEventListener?.('auth-session-changed',readerSessionChanged); }
  refreshRecords();
});
onUnmounted(() => {
  if (typeof window !== 'undefined') { window.removeEventListener?.('storage', readerSessionChanged); window.removeEventListener?.('auth-session-cleared', readerSessionChanged); window.removeEventListener?.('auth-session-changed',readerSessionChanged); clearDetail(); }
  disposed = true;
  editOpeners.clear();
  editOrigin = null;
  patientEpoch++;
  pendingDeletes.clear();
  loading.value = false;
  invalidateEditor();
});
let openedTimelineLocator='';
watch([()=>sourceRoute.fullPath,currentPatientId,()=>originalMode.value,()=>raw.state.phase],async()=>{
  const locator=timelineLocator.value;
  if(!locator||locator.patientId!==currentPatientId.value||disposed)return;
  if(!rawOpen.value){rawOpen.value=true;return;}
  if(!['enabled','disabled'].includes(originalMode.value)||raw.state.phase!=='ready')return;
  const key=`${locator.patientId}:${locator.recordId}:${readerSessionEpoch}`;
  if(openedTimelineLocator===key)return;
  openedTimelineLocator=key;
  await openDetail({id:locator.recordId,patientId:locator.patientId});
},{immediate:true,flush:'post'});
</script>

<style scoped src="@/styles/module-layout.css"></style>
<style scoped>
.editor-heading { margin: 0; font-size: inherit; font-weight: 700; overflow-wrap: anywhere; }
.editor-heading:focus-visible { outline: 3px solid var(--care-600); outline-offset: 4px; }
.submission-patient { margin: 12px 20px 0; color: var(--ink-700); overflow-wrap: anywhere; }

 .summary-item small { display: block; margin-top: 8px; font-size: 12px; color: var(--ink-700); overflow-wrap: anywhere; }
.summary-context { color: var(--ink-700); }
.read-state, .trend-data { margin: 16px 0; padding: 16px; border: 1px solid var(--line-strong); border-radius: 12px; background: var(--paper); color: var(--ink-800); overflow-wrap: anywhere; }
.trend-data summary { min-height: 44px; cursor: pointer; font-weight: 700; }
.trend-data summary:focus-visible, .trend-data-wrap:focus-visible { outline: 3px solid var(--care-600); outline-offset: 4px; }
.trend-data-wrap { overflow-x: auto; }
.trend-data table { width: 100%; border-collapse: collapse; }
.trend-data th, .trend-data td { text-align: start; padding: 10px; border-bottom: 1px solid var(--line); }
.trend-data caption { text-align: start; padding: 8px 0; }
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
/* The desktop table remains available at wide widths; mobile cards keep the source reading in view. */
.mobile-record-list { display: none; }
@media (max-width: 768px) {
  .records-desktop-table { display: none; }
  .mobile-record-list { display: grid; gap: 10px; padding: 12px; }
  .mobile-record-card { min-width: 0; padding: 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--paper); }
  .record-card-meta { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 4px 8px; color: var(--ink-700); font-size: 12px; line-height: 1.5; }
  .record-card-meta time { font-variant-numeric: tabular-nums; }
  .record-card-type { color: var(--ink-800); text-decoration: none; font-weight: 700; }
  .record-card-readings { display: flex; flex-wrap: wrap; gap: 6px 12px; margin-top: 7px; color: var(--ink-950); font-size: 14px; font-weight: 700; line-height: 1.5; }
  .record-card-readings > span { white-space: nowrap; }
  .record-card-details { margin-top: 6px; color: var(--ink-700); font-size: 12px; overflow-wrap: anywhere; }
  .record-card-details summary { min-height: 32px; display: list-item; align-content: center; cursor: pointer; }
  .record-card-details p { margin: 6px 0; }
  .record-card-actions { display: flex; justify-content: flex-end; gap: 18px; margin-top: 4px; }
  .record-card-actions :deep(.el-button) { min-width: 44px; min-height: 44px; margin-left: 0; }
  .record-card-details summary:focus-visible { outline: 3px solid var(--care-600); outline-offset: 2px; }
  .record-list-empty { color: var(--ink-700); text-align: center; }
}

</style>

<style scoped>.measure-switch :deep(.el-radio-button__inner){min-height:44px;white-space:normal;line-height:1.4;overflow-wrap:anywhere}.entry-form :deep(.el-input__wrapper),.entry-form :deep(.el-select__wrapper),.entry-form :deep(.el-button){min-height:44px}.entry-form :deep(.el-input-number){max-width:100%;min-height:44px}.entry-form :deep(.el-form-item__label){height:auto;white-space:normal;overflow-wrap:anywhere}</style>

<style scoped>
.records-tools {
  --control-row-size: 44px;
  min-width: 0;
}
body.care-senior .records-tools {
  --control-row-size: 48px;
}
.records-tools .filter-switch {
  align-items: stretch;
  max-width: 100%;
  min-width: 0;
}
.records-tools .filter-switch :deep(.el-radio-button) {
  display: inline-flex;
  flex: 1 1 auto;
  flex-direction: row;
  gap: 0;
}
.records-tools .filter-switch :deep(.el-radio-button__inner) {
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  width: 100%;
  min-height: var(--control-row-size);
  white-space: normal;
  line-height: 1.4;
}
.records-tools > .el-button {
  min-height: var(--control-row-size);
  height: auto;
  line-height: 1.2;
}
body.care-senior .records-tools .filter-switch :deep(.el-radio-button__inner) {
  font-size: 18px;
}
</style>
