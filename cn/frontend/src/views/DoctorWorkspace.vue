<template>
  <main ref="workflowFallback" class="doctor-page" tabindex="-1">
    <header class="doctor-hero">
      <div><span class="eyebrow">临床工作区</span><h1>医生工作台</h1><p>集中查看风险、核验导入结果，并为已分配患者协调连续照护。</p></div>
      <el-button :loading="loading" @click="loadAll"><el-icon><Refresh /></el-icon>刷新</el-button>
    </header>

    <div class="care-inboxes">
    <section class="consultation-entry"><div><h2>问诊收件箱</h2><p>患者选择你发起问诊后，会直接出现在收件箱中，无需先分配到医疗团队。打开会话即可回复。</p></div><router-link to="/care-journey?tab=consultation" class="consultation-link">查看患者问诊 <span aria-hidden="true">→</span></router-link></section>
    <section class="consultation-entry"><div><h2>预约收件箱与排班</h2><p>查看预约给您的患者、取消或完成就诊，并维护可预约时段。无需患者先加入长期照护团队。</p></div><router-link to="/care-journey?tab=operations" class="consultation-link">管理门诊预约 <span aria-hidden="true">→</span></router-link></section>
    </div>

    <p v-if="summaryState === 'loading'" role="status">正在加载工作台…</p>
    <p v-else-if="summaryState === 'error'" role="alert">工作台信息不可用，请刷新后重试。</p>
    <p v-else-if="summaryState === 'idle'" role="status">请刷新以加载工作台信息。</p>
    <p v-if="contextState === 'loading'" role="status">正在加载患者笔记和计划…</p>
    <p v-else-if="contextState === 'error'" role="alert">患者笔记和计划不可用，请重新选择患者或刷新后重试。</p>
    <section class="metric-grid" aria-label="临床工作量概览">
      <article><span>已分配患者</span><strong>{{ metricValue('assignedPatients') }}</strong><small>当前有效的医疗团队分配</small></article>
      <article><span>待复核</span><strong>{{ metricValue('pendingReviews') }}</strong><small>导入记录与 AI 辅助分析</small></article>
      <article class="risk"><span>危急患者</span><strong>{{ metricValue('criticalPatients') }}</strong><small>存在未关闭危急提醒的患者</small></article>
      <article><span>执行中计划</span><strong>{{ metricValue('activePlans') }}</strong><small>由医生制定的照护计划</small></article>
    </section>

    <section class="panel"><PatientSelect v-model="selectedPatientId" :patients="patients" :disabled="collaborationEditing" @change="loadPatientContext" /><FollowUpList :patient-id="selectedPatientId" /></section>
    <ReconciliationPanel :patient-id="selectedPatientId" :navigation-key="tab" />
    <el-tabs v-model="tab" class="doctor-tabs">
      <el-tab-pane label="患者面板" name="patients">
        <section class="panel">
          <div class="panel-head"><div><h2>已分配患者</h2><p>这里只显示已分配到当前医疗团队的患者。</p></div></div>
          <el-table :data="patients" v-loading="loading" stripe :empty-text="summaryState === 'ready' ? '暂未分配患者' : '—'">
            <el-table-column prop="name" label="患者" min-width="140"><template #default="{row}"><button class="patient-link" @click="selectPatient(row)">{{ row.name }} · #{{ row.id }}</button></template></el-table-column>
            <el-table-column prop="careTeamRole" label="团队角色" min-width="140" />
            <el-table-column prop="lastVitalAt" label="最近生命体征" min-width="170"><template #default="{row}">{{row.lastVitalAt ? recordedTime(row.lastVitalAt) : '暂无读数'}}</template></el-table-column>
            <el-table-column prop="criticalAlerts" label="危急提醒" width="130"><template #default="{row}"><el-tag :type="Number(row.criticalAlerts)>0?'danger':'success'">{{ row.criticalAlerts || 0 }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="210"><template #default="{row}"><el-button link type="primary" @click="openNote(row)">添加笔记</el-button><el-button link @click="openPlan(row)">照护计划</el-button></template></el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
      <el-tab-pane :label="summaryState === 'ready' ? `已显示审核任务（${reviews.length}）` : '已显示审核任务'" name="reviews">
        <section class="panel review-list">
          <p class="review-subset">最多显示六条审核任务，保持服务返回的顺序。待复核总数为全部已选工作量。</p>
          <article v-for="(item,index) in reviews" :key="item.sourceType+'-'+item.sourceId+'-'+index" class="review-card">
            <div class="review-body"><span class="eyebrow">{{ sourceLabel(item.sourceType) }}</span><h3>{{ item.patientName }} · 档案 #{{ item.patientId }} · {{ item.title }}</h3><p>{{ item.occurredAt || '日期未知' }}<template v-if="item.confidence"> · 可信度 {{ Math.round(Number(item.confidence)*100) }}%</template></p>
              <section v-if="item.sourceType === 'MEDICAL_RECORD'" class="queue-qualification">
                <strong>{{ reviewCategory(item) }}</strong>
                <p>{{ reviewPresentation(item).sourceText }}</p>
                <p v-if="reviewPresentation(item).sourceActorText">{{ reviewPresentation(item).sourceActorText }}</p>
                <p v-if="reviewPresentation(item).doctorText">{{ reviewPresentation(item).doctorText }}</p>
                <p v-if="reviewPresentation(item).doctorActorText">{{ reviewPresentation(item).doctorActorText }}</p>
                <p class="queue-automatic" :data-tone="reviewPresentation(item).automaticTone">{{ reviewPresentation(item).automaticText }}</p>
                <p v-if="reviewPresentation(item).observationText">{{ reviewPresentation(item).observationText }}</p>
                <p v-if="item.verificationStatus != null">{{ reviewPresentation(item).legacyHeading }}: {{ verificationLabel(item.verificationStatus) }}</p>
                <p>来源核验与 DOCTOR 审核记录不代表诊断或临床适用性判断</p>
                <p v-if="item.reviewChannel === 'DOCTOR' && reviewPresentation(item).mode === 'PARTICIPANT' && !reviewPresentation(item).mismatch">此处仅展示独立通道审核</p>
                <p v-if="item.reviewChannel === 'LEGACY' && reviewPresentation(item).mode === 'UNAVAILABLE'" role="status">审核信息不可用。仍可查看来源；只有重新加载的预览确认可提交后，才能进行旧版审核。</p>
              </section>
            </div>
            <div class="review-actions"><el-button type="primary" plain @click="openReview(item)">{{ reviewAction(item) }}</el-button><el-button v-if="item.sourceType === 'MEDICAL_RECORD'" data-testid="doctor-review-workflow-launch" plain @click="openReviewWorkflow(item)">{{ workflowCopy.doctorEntry }}</el-button></div>
          </article>
          <el-empty v-if="summaryState === 'ready' && !reviews.length" description="本次视图未返回审核任务" />
        </section>
      </el-tab-pane>
      <el-tab-pane label="临床笔记" name="notes">
        <section class="panel"><PatientSelect v-model="selectedPatientId" :patients="patients" @change="loadPatientContext" /><div class="timeline">
          <article v-for="note in notes" :key="note.id"><span>{{ note.noteType }} · {{ note.createdAt }}</span><h3>{{ note.doctorName || '医疗团队医生' }}</h3><p>{{ note.noteText }}</p><el-tag size="small">{{ note.visibility === 'PATIENT' ? '患者可见' : '仅医疗团队可见' }}</el-tag></article>
          <el-empty v-if="contextState === 'ready' && selectedPatientId && !notes.length" description="该患者暂无临床笔记" />
        </div></section>
      </el-tab-pane>
      <el-tab-pane label="照护计划" name="plans">
        <section class="panel"><PatientSelect v-model="selectedPatientId" :patients="patients" :disabled="collaborationEditing" @change="loadPatientContext" /><router-link v-if="selectedPatientId" :to="`/care-plans/reports?patientId=${selectedPatientId}`" class="consultation-link" data-testid="doctor-execution-report">就诊准备 · 全部计划</router-link><CollaborationPlans :patient-id="selectedPatientId" :legacy-plans="plans" @editing="collaborationEditing=$event" /><h2>历史内部计划</h2><p>既有临床记录仍为内部记录，复制为协作草稿不会自动发布。</p><div class="plan-grid">
          <article v-for="plan in plans" :key="plan.id"><el-tag>历史内部计划 · {{ statusLabel(plan.status) }}</el-tag><h3>{{ plan.title }}</h3><p>{{ plan.instructions }}</p><small>{{ planTypeLabel(plan.planType) }} · 目标日期 {{ plan.targetDate || '持续执行' }}</small></article>
          <el-empty v-if="contextState === 'ready' && selectedPatientId && !plans.length" description="该患者暂无照护计划" />
        </div></section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="noteVisible" title="添加临床笔记" width="min(560px,94vw)">
      <el-form label-position="top"><el-form-item label="患者"><b>{{ activePatient?.name }} · 档案 #{{ activePatient?.id }}</b></el-form-item><el-form-item label="笔记类型"><el-select v-model="noteForm.noteType"><el-option label="随访" value="FOLLOW_UP"/><el-option label="评估" value="ASSESSMENT"/><el-option label="用药" value="MEDICATION"/></el-select></el-form-item><el-form-item label="可见范围"><el-radio-group v-model="noteForm.visibility"><el-radio value="CARE_TEAM">仅医疗团队</el-radio><el-radio value="PATIENT">患者可见</el-radio></el-radio-group></el-form-item><el-form-item label="临床笔记"><el-input v-model="noteForm.noteText" type="textarea" :rows="5" maxlength="4000" show-word-limit/></el-form-item></el-form>
      <template #footer><el-button @click="noteVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitNote">保存笔记</el-button></template>
    </el-dialog>
    <el-dialog v-model="planVisible" title="新建历史内部计划" width="min(600px,94vw)">
      <el-form label-position="top"><el-form-item label="患者"><b>{{ activePatient?.name }} · 档案 #{{ activePatient?.id }}</b></el-form-item><el-form-item label="计划标题"><el-input v-model="planForm.title" maxlength="160"/></el-form-item><el-form-item label="计划类型"><el-select v-model="planForm.planType"><el-option label="随访" value="FOLLOW_UP"/><el-option label="用药" value="MEDICATION"/><el-option label="透析" value="DIALYSIS"/><el-option label="营养" value="NUTRITION"/></el-select></el-form-item><el-form-item label="目标日期"><el-date-picker v-model="planForm.targetDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="执行说明"><el-input v-model="planForm.instructions" type="textarea" :rows="5" maxlength="4000" show-word-limit/></el-form-item></el-form>
      <template #footer><el-button @click="planVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitPlan">保存内部计划</el-button></template>
    </el-dialog>
    <MedicalReviewWorkflowDialog v-if="workflowTarget" :key="workflowTarget.epoch" :record-id="workflowTarget.recordId" :patient-id="workflowTarget.patientId" :patient-name="workflowTarget.patientName" initial-channel="DOCTOR" :current-patient-id="workflowGlobalPatient" :context-patient-id="selectedPatientId" :route-key="route.fullPath" :navigation-key="tab" :fallback-element="workflowFallback" @closed="workflowTarget.closed" @changed="workflowTarget.changed" @view-state="workflowTarget.viewState" />
    <AiAnalysisReviewDialog v-if="aiReviewTarget" :key="reviewTargetEpoch" :item="aiReviewTarget" @closed="aiReviewTarget=null" @reviewed="reviewCompleted" />
    <MedicalRecordReviewDialog v-if="medicalReviewTarget" :key="reviewTargetEpoch" :record-id="medicalReviewTarget.recordId" :patient-id="medicalReviewTarget.patientId" workspace @closed="medicalReviewTarget=null" @reviewed="reviewCompleted" />
  </main>
</template>

<script setup>
import {recordedTime} from '@/utils/originalMeasurementPresentation';
import ReconciliationPanel from '@/components/medication-execution/ReconciliationPanel.vue';
import FollowUpList from '@/components/alert-workflow/FollowUpList.vue';
import AiAnalysisReviewDialog from '@/components/care-plan/AiAnalysisReviewDialog.vue';
import MedicalReviewWorkflowDialog from '@/components/medical-record/MedicalReviewWorkflowDialog.vue';
import { medicalReviewWorkflowCopy as workflowCopy } from '@/components/medical-record/medicalReviewWorkflowCopy';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import MedicalRecordReviewDialog from '@/components/MedicalRecordReviewDialog.vue';
import { defineComponent, h, inject, onMounted, onUnmounted, reactive, ref, resolveComponent, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router';
import PlanEditor from '@/components/care-plan/PlanEditor.vue';
import PlanDetail from '@/components/care-plan/PlanDetail.vue';
import PlanReviewQueue from '@/components/care-plan/PlanReviewQueue.vue';
import { getCarePlanCapabilities } from '@/api/carePlan';
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache, AUTH_STORAGE_KEYS } from '@/utils/authSession';
import { medicalReviewPresentation } from '@/utils/medicalReviewQualification';
import { ElMessage, ElMessageBox } from 'element-plus';
import { getDoctorSummary, getDoctorNotes, getDoctorPlans, saveDoctorNote, saveDoctorPlan, completeDoctorReview } from '@/api/doctorWorkspace';

const CollaborationPlans = defineComponent({
  props: { patientId: { type: Number, default: null }, legacyPlans: { type: Array, default: () => [] } },
  emits: ['editing'],
  setup(props,{emit}) {
    const enabled=ref(false), editorVisible=ref(false), editorRef=ref(null), legacySource=ref(null), detailId=ref(null), detailRef=ref(null), refreshKey=ref(0), message=ref('');
    let epoch=0,controller=null;
    async function checkCapability(){const e=++epoch;controller?.abort();controller=new AbortController();enabled.value=false;editorVisible.value=false;detailId.value=null;legacySource.value=null;const auth=captureAuthSession(),actor=localStorage.getItem('userId');try{const result=await getCarePlanCapabilities({expectedAuth:{...auth,actorId:actor},signal:controller.signal});if(e===epoch&&isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId'))enabled.value=!!result.data?.enabled}catch{if(e===epoch)message.value='当前会话无法加载协作计划。'}}
    function requestLeave(){if(editorVisible.value && (!editorRef.value || !editorRef.value.requestLeave()))return false;return detailRef.value?.requestLeave?.()??true}
    function contextChange(event){if(!event.defaultPrevented && !requestLeave())event.preventDefault()}
    onBeforeRouteLeave(requestLeave);onBeforeRouteUpdate(requestLeave);
    onMounted(()=>window.addEventListener('care-plan-before-context-change',contextChange));
    function canChange(){return !editorVisible.value && (!detailRef.value || detailRef.value.requestLeave())}
    function openEditor(source=null){if(!enabled.value||!props.patientId||!canChange())return;detailId.value=null;legacySource.value=source;editorVisible.value=true}
    function openDetail(id){if(!canChange())return;detailId.value=id}
    function closeDetail(){if(canChange())detailId.value=null}
    function saved(){refreshKey.value++}
    function closed(){editorVisible.value=false;legacySource.value=null;refreshKey.value++}
    watch(()=>[editorVisible.value,detailId.value],()=>emit('editing',editorVisible.value||!!detailId.value),{flush:'sync'});
    watch(()=>props.patientId,checkCapability,{immediate:true,flush:'sync'});
    onUnmounted(()=>{epoch++;controller?.abort();window.removeEventListener('care-plan-before-context-change',contextChange)});
    return ()=>h('section',{class:'collaboration-plans'},enabled.value?[
      h('h2','协作计划'),h('p','私有草稿、已发布行动和复核队列使用独立的明确发布流程。'),
      props.patientId?h('button',{type:'button',disabled:editorVisible.value,'data-testid':'new-collaboration-draft',onClick:()=>openEditor()},'创建私有协作草稿'):h('p','请选择已分配患者以创建草稿。'),
      ...props.legacyPlans.map(plan=>h('button',{key:plan.id,type:'button',disabled:editorVisible.value||!props.patientId,'data-testid':`copy-legacy-${plan.id}`,onClick:()=>openEditor(plan)},`将“${plan.title}”复制为私有协作草稿`)),
      editorVisible.value?h(PlanEditor,{ref:editorRef,patientId:props.patientId,legacySource:legacySource.value,onSaved:saved,onClosed:closed}):null,
      !editorVisible.value?h(PlanReviewQueue,{key:refreshKey.value,patientId:props.patientId,onOpen:openDetail}):null,
      detailId.value?h('div',[h('button',{type:'button',onClick:closeDetail},'关闭计划详情'),h(PlanDetail,{ref:detailRef,planId:detailId.value,onChanged:saved})]):null
    ]:message.value?h('p',{role:'status'},message.value):[])
  }
});

const PatientSelect = defineComponent({ props:{ modelValue:[Number,String], disabled:Boolean, patients:{type:Array,default:()=>[]} }, emits:['update:modelValue','change'], setup(props,{emit}){return()=>h('div',{class:'patient-filter'},[h('span','患者'),h(resolveComponent('el-select'),{modelValue:props.modelValue,disabled:props.disabled,placeholder:'请选择患者','onUpdate:modelValue':v=>{emit('update:modelValue',v);emit('change',v)}},{default:()=>props.patients.map(p=>h(resolveComponent('el-option'),{key:p.id,label:`${p.name} · #${p.id}`,value:p.id}))})])} });
const loading=ref(false),saving=ref(false),tab=ref('patients'),summary=ref({}),patients=ref([]),reviews=ref([]),notes=ref([]),plans=ref([]),selectedPatientId=ref(null),activePatient=ref(null),noteVisible=ref(false),planVisible=ref(false),collaborationEditing=ref(false);
const noteForm=reactive({noteType:'FOLLOW_UP',visibility:'CARE_TEAM',noteText:''});
const planForm=reactive({title:'',planType:'FOLLOW_UP',targetDate:'',instructions:''});
const medicalReviewTarget=ref(null),aiReviewTarget=ref(null),reviewTargetEpoch=ref(0),reviewCompleted=ref(null);
const summaryState = ref('idle'), contextState = ref('idle');
const { currentPatientId: workflowGlobalPatient } = useCurrentPatient();
const workflowTarget = ref(null), workflowFallback = ref(null), workflowViewState = reactive({ locked: false, dirty: false });
let workflowEpoch = 0;
function retireReviewWorkflow() { workflowEpoch++; workflowTarget.value = null; Object.assign(workflowViewState, { locked: false, dirty: false }); }
function openReviewWorkflow(item) {
  if (!owns(summaryOwner) || summaryState.value !== 'ready' || !reviews.value.includes(item) || item?.sourceType !== 'MEDICAL_RECORD' || !safeId(item.sourceId) || !safeId(item.patientId)
      || workflowTarget.value || medicalReviewTarget.value || aiReviewTarget.value || noteVisible.value || planVisible.value || collaborationEditing.value) return;
  const epoch = ++workflowEpoch, owner = summaryOwner, localPatient = selectedPatientId.value, globalPatient = workflowGlobalPatient.value, selectedTab = tab.value; let refreshNeeded = false;
  const owned = () => epoch === workflowEpoch && workflowTarget.value?.epoch === epoch && owner === summaryOwner && owns(owner) && localPatient === selectedPatientId.value && globalPatient === workflowGlobalPatient.value && selectedTab === tab.value;
  workflowTarget.value = { epoch, recordId: Number(item.sourceId), patientId: Number(item.patientId), patientName: typeof item.patientName === 'string' ? item.patientName : '',
    changed: payload => { if (owned() && payload && Object.keys(payload).length === 3 && payload.recordId === Number(item.sourceId) && ['SOURCE', 'DOCTOR'].includes(payload.channel) && Number.isSafeInteger(payload.decisionVersion) && payload.decisionVersion > 0) refreshNeeded = true; },
    viewState: payload => { if (owned() && payload && Object.keys(payload).length === 2 && typeof payload.locked === 'boolean' && typeof payload.dirty === 'boolean') Object.assign(workflowViewState, payload); },
    closed: payload => { if (!owned() || !payload || Object.keys(payload).length !== 1 || typeof payload.retired !== 'boolean') return; const refresh = refreshNeeded && !payload.retired; refreshNeeded = false; retireReviewWorkflow(); if (refresh) void loadAll(); }
  };
}
watch([tab, workflowGlobalPatient], retireReviewWorkflow, { flush: 'sync' });
function workflowStorageChanged(event) { if (event.key === 'currentPatientId') retireReviewWorkflow(); }
onMounted(() => { window.addEventListener('current-patient-change', retireReviewWorkflow); window.addEventListener('storage', workflowStorageChanged); });
onUnmounted(() => { retireReviewWorkflow(); window.removeEventListener('current-patient-change', retireReviewWorkflow); window.removeEventListener('storage', workflowStorageChanged); });
const permissionMenus = inject('userMenus', ref([])), route = useRoute();
let contextEpoch=0, summaryEpoch=0, dialogEpoch=0, disposed=false, summaryOwner=null;
const permissionKey = () => JSON.stringify(readPermissionCache());
const menuKey = () => JSON.stringify(permissionMenus.value);
const captureOwner = () => ({ auth: captureAuthSession(), actorId: localStorage.getItem('userId'), permission: permissionKey(), menus: menuKey(), route: route.fullPath });
const owns = owner => !disposed && !!owner && isAuthSessionCurrent(owner.auth) && owner.actorId === localStorage.getItem('userId') && owner.permission === permissionKey() && owner.menus === menuKey() && owner.route === route.fullPath;
const objectRow = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const safeId = value => (typeof value === 'number' || typeof value === 'string' && /^[1-9]\d*$/.test(value)) && Number.isSafeInteger(Number(value)) && Number(value) > 0;
const validRows = rows => Array.isArray(rows) && rows.every(objectRow);
function retirePatientContext() {
  retireReviewWorkflow();
  contextEpoch++; dialogEpoch++; reviewTargetEpoch.value++; reviewCompleted.value=null; notes.value=[]; plans.value=[]; contextState.value='idle';
  medicalReviewTarget.value=null; aiReviewTarget.value=null; activePatient.value=null; noteVisible.value=false; planVisible.value=false;
  Object.assign(noteForm,{noteType:'FOLLOW_UP',visibility:'CARE_TEAM',noteText:''});
  Object.assign(planForm,{title:'',planType:'FOLLOW_UP',targetDate:'',instructions:''});
}
function retireWorkspace() {
  summaryEpoch++; summaryOwner=null; summary.value={}; patients.value=[]; reviews.value=[]; summaryState.value='idle'; loading.value=false;
  retirePatientContext(); selectedPatientId.value=null;
}
function metricValue(key) {
  const value=summary.value[key];
  return summaryState.value==='ready' && Number.isSafeInteger(value) && value>=0 ? value : summaryState.value==='loading' || summaryState.value==='idle' ? '—' : '不可用';
}
async function loadAll() {
  if(disposed)return;
  const epoch=++summaryEpoch;
  summary.value={}; patients.value=[]; reviews.value=[]; retirePatientContext();
  const owner=captureOwner(); summaryOwner=owner; summaryState.value='loading'; loading.value=true;
  if(!owns(owner)){retireWorkspace();return}
  // These existing wrappers do not forward auth/signal options. Ownership below
  // retires local state; it does not cancel transport or suppress global errors.
  try {
    const res=await getDoctorSummary();
    if(epoch!==summaryEpoch)return;
    if(!owns(owner)){retireWorkspace();return}
    const data=res?.data;
    if(res?.code!==200 || !objectRow(data) || !validRows(data.patients) || !data.patients.every(p=>safeId(p.id)) || !validRows(data.reviewQueue) || !data.reviewQueue.every(r=>['MEDICAL_RECORD','AI_ANALYSIS'].includes(r.sourceType) && safeId(r.sourceId) && safeId(r.patientId)))throw new Error('Invalid workspace response');
    summary.value=data; patients.value=data.patients; reviews.value=data.reviewQueue; summaryState.value='ready';
    if(!patients.value.some(p=>String(p.id)===String(selectedPatientId.value)))selectedPatientId.value=patients.value[0]?.id??null;
    await loadPatientContext();
  } catch {
    if(epoch===summaryEpoch && owns(owner)){summary.value={};patients.value=[];reviews.value=[];retirePatientContext();selectedPatientId.value=null;summaryState.value='error'}
  } finally {
    if(epoch===summaryEpoch){if(!owns(owner))retireWorkspace();else loading.value=false}
  }
}
async function loadPatientContext() {
  retirePatientContext();
  const epoch=contextEpoch, patientId=selectedPatientId.value, owner=captureOwner();
  if(!owns(summaryOwner)){retireWorkspace();return}
  if(!safeId(patientId) || summaryState.value!=='ready' || !patients.value.some(p=>String(p.id)===String(patientId)))return;
  if(!owns(owner))return;
  contextState.value='loading';
  try {
    const [n,p]=await Promise.all([getDoctorNotes(patientId),getDoctorPlans(patientId)]);
    if(epoch!==contextEpoch || patientId!==selectedPatientId.value)return;
    if(!owns(owner)){retireWorkspace();return}
    if(n?.code!==200 || p?.code!==200 || !validRows(n.data) || !validRows(p.data))throw new Error('Invalid patient context');
    notes.value=n.data; plans.value=p.data; contextState.value='ready';
  } catch {
    if(epoch===contextEpoch && patientId===selectedPatientId.value && owns(owner)){notes.value=[];plans.value=[];contextState.value='error'}
  } finally { if(epoch===contextEpoch && !owns(owner))retireWorkspace() }
}
function ownsPatientRow(row) { if(!owns(summaryOwner)){retireWorkspace();return false}return summaryState.value==='ready' && patients.value.includes(row) && safeId(row?.id) }
function reviewPresentation(item) {
  const value=medicalReviewPresentation(item.reviewSummary, 'zh');
  const known=value.mode==='NONE' || value.mode==='PARTICIPANT' || value.mode==='UNAVAILABLE';
  const consistent=item.reviewChannel==='LEGACY' ? value.mode==='NONE' || value.mode==='UNAVAILABLE' : item.reviewChannel==='DOCTOR' && value.mode==='PARTICIPANT' && ['DOCTOR_ONLY','BOTH'].includes(value.participation) && ['PENDING','APPROVED','REJECTED'].includes(item.reviewSummary?.doctor?.state);
  const mismatch=known && (item.reviewSummary?.qualificationSemantics!=='CURRENT_CHECK' || !consistent);
  if(mismatch)return {...value,sourceText:'当前审核信息与此工作量视图不一致。请重新加载来源以核实当前可用操作。',doctorText:'',sourceActorText:'',doctorActorText:'',automaticText:'无法核实自动分析输入资格',automaticTone:'info',mismatch:true,legacyEntry:false};
  return {...value,mismatch:false,legacyEntry:item.reviewChannel==='LEGACY' && value.mode==='NONE'};
}
function reviewCategory(item) { return item.reviewChannel==='LEGACY' ? '旧版审核任务' : item.reviewChannel==='DOCTOR' ? 'DOCTOR 通道复核任务' : '当前审核信息' }
function reviewAction(item) { return item.sourceType!=='MEDICAL_RECORD' || reviewPresentation(item).legacyEntry ? '查看并复核' : '查看来源' }
function verificationLabel(value) { return ({REVIEW_REQUIRED:'待复核',VERIFIED:'已核验',APPROVED:'已通过',REJECTED:'未通过'})[value] || '不可用' }
function selectPatient(row){if(!ownsPatientRow(row))return;if(collaborationEditing.value){ElMessage.warning('先关闭协作编辑器或计划详情，再切换患者。');return}selectedPatientId.value=row.id;activePatient.value=row;tab.value='notes';loadPatientContext()}
function openNote(row){if(!ownsPatientRow(row))return;if(collaborationEditing.value){ElMessage.warning('先关闭协作编辑器或计划详情，再切换患者。');return}selectedPatientId.value=row.id;loadPatientContext();dialogEpoch++;activePatient.value=row;Object.assign(noteForm,{noteType:'FOLLOW_UP',visibility:'CARE_TEAM',noteText:''});noteVisible.value=true}
function openPlan(row){if(!ownsPatientRow(row))return;if(collaborationEditing.value){ElMessage.warning('先关闭协作编辑器或计划详情，再切换患者。');return}selectedPatientId.value=row.id;loadPatientContext();dialogEpoch++;activePatient.value=row;Object.assign(planForm,{title:'',planType:'FOLLOW_UP',targetDate:'',instructions:''});planVisible.value=true}
async function submitNote(){if(saving.value)return;if(!noteForm.noteText.trim()){ElMessage.warning('请输入临床笔记。');return}const epoch=dialogEpoch,patientId=selectedPatientId.value;saving.value=true;try{await saveDoctorNote({patientId,...noteForm});if(epoch!==dialogEpoch||patientId!==selectedPatientId.value||!noteVisible.value)return;ElMessage.success('临床笔记已保存。');noteVisible.value=false;await loadPatientContext()}finally{saving.value=false}}
async function submitPlan(){if(saving.value)return;if(!planForm.title.trim()||!planForm.instructions.trim()){ElMessage.warning('请输入计划标题和执行说明。');return}const epoch=dialogEpoch,patientId=selectedPatientId.value;saving.value=true;try{await saveDoctorPlan({patientId,status:'ACTIVE',...planForm});if(epoch!==dialogEpoch||patientId!==selectedPatientId.value||!planVisible.value)return;ElMessage.success('内部照护计划已保存。');planVisible.value=false;await loadAll()}finally{saving.value=false}}
function openReview(item){
  aiReviewTarget.value=null;medicalReviewTarget.value=null;
  if(!owns(summaryOwner)){retireWorkspace();return}
  if(summaryState.value!=='ready' || !reviews.value.includes(item) || !safeId(item?.sourceId) || !safeId(item?.patientId))return;
  const epoch=++reviewTargetEpoch.value,owner=summaryOwner;
  reviewCompleted.value=()=>{if(epoch!==reviewTargetEpoch.value || owner!==summaryOwner || !owns(owner))return;return item.sourceType==='MEDICAL_RECORD'?reviewedMedicalRecord():reviewedAiAnalysis()};
  if(item.sourceType==='MEDICAL_RECORD'){medicalReviewTarget.value={recordId:Number(item.sourceId),patientId:item.patientId};return}
  if(item.sourceType==='AI_ANALYSIS')aiReviewTarget.value={...item};
}
async function reviewedAiAnalysis(){if(!aiReviewTarget.value || summaryState.value!=='ready' || !owns(summaryOwner))return;aiReviewTarget.value=null;await loadAll()}
async function reviewedMedicalRecord(){if(!medicalReviewTarget.value || summaryState.value!=='ready' || !owns(summaryOwner))return;medicalReviewTarget.value=null;await loadAll()}
watch(selectedPatientId,retirePatientContext,{flush:'sync'});
function sourceLabel(type){return type==='MEDICAL_RECORD'?'导入的医疗记录':'AI 辅助分析'}
function statusLabel(value){return {ACTIVE:'执行中',DRAFT:'草稿',COMPLETED:'已完成',CANCELLED:'已取消'}[value]||value}
function planTypeLabel(value){return {FOLLOW_UP:'随访计划',MEDICATION:'用药计划',DIALYSIS:'透析计划',NUTRITION:'营养计划'}[value]||value}
watch(permissionMenus,retireWorkspace,{deep:true,flush:'sync'});
watch(()=>route.fullPath,retireWorkspace,{flush:'sync'});
function storageChanged(event){if(event.key==null || [...AUTH_STORAGE_KEYS,'permissionSession','userMenus','userMenuNames','userRoleCodes'].includes(event.key))retireWorkspace()}
onMounted(()=>{window.addEventListener('auth-session-changed',retireWorkspace);window.addEventListener('auth-session-cleared',retireWorkspace);window.addEventListener('permissions-changed',retireWorkspace);window.addEventListener('storage',storageChanged);loadAll()});
onUnmounted(()=>{disposed=true;retireWorkspace();window.removeEventListener('auth-session-changed',retireWorkspace);window.removeEventListener('auth-session-cleared',retireWorkspace);window.removeEventListener('permissions-changed',retireWorkspace);window.removeEventListener('storage',storageChanged)});
</script>

<style scoped>
.review-body{min-width:0;flex:1;overflow-wrap:anywhere}.queue-qualification{margin-top:12px;padding:12px;border:1px solid var(--line);border-radius:10px;background:var(--surface-subtle)}.queue-qualification p{margin:6px 0;line-height:1.6}.queue-automatic{font-weight:600}.queue-automatic[data-tone="success"]{color:var(--care-800)}.review-actions{flex-shrink:0;max-width:100%}.review-actions :deep(.el-button){min-height:44px;height:auto;white-space:normal}.review-actions :deep(.el-button:focus-visible){outline:3px solid var(--care-600);outline-offset:3px}.review-subset{line-height:1.6}

.collaboration-plans{margin:20px 0;padding:16px 0;border-bottom:1px solid var(--line)}:deep(.collaboration-plans > button),:deep(.collaboration-plans > div > button){min-height:44px;max-width:100%;margin:8px 12px 8px 0;padding:10px 14px;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800);font:inherit;cursor:pointer;overflow-wrap:anywhere}:deep(.collaboration-plans button:focus-visible){outline:3px solid var(--care-600);outline-offset:3px}

.care-inboxes{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.care-inboxes .consultation-entry{min-width:0;flex-direction:column;align-items:stretch}.care-inboxes .consultation-link{justify-content:center}@media(max-width:850px){.care-inboxes{grid-template-columns:1fr}}
.consultation-entry{display:flex;align-items:center;justify-content:space-between;gap:20px;margin:18px 0;padding:20px;border:1px solid var(--care-100);border-radius:16px;background:var(--care-50)}.consultation-entry h2{font-size:18px;margin:0 0 6px;color:var(--care-950)}.consultation-entry p{margin:0;color:var(--ink-500);line-height:1.6}.consultation-link{display:inline-flex;align-items:center;gap:12px;flex-shrink:0;min-height:44px;padding:10px 16px;border-radius:10px;background:var(--care-600);color:var(--on-accent);text-decoration:none;font-weight:600}@media(max-width:600px){.consultation-entry{align-items:stretch;flex-direction:column}.consultation-link{justify-content:center}}
.doctor-page{max-width:1440px;margin:0 auto;padding:32px}.doctor-hero,.panel-head,.review-card,.review-actions,.patient-filter{display:flex;align-items:center;justify-content:space-between;gap:16px}.doctor-hero{padding:30px;border-radius:22px;background:linear-gradient(135deg,var(--care-50),var(--surface-subtle));border:1px solid var(--line)}.eyebrow{color:var(--care-700);text-transform:uppercase;font-size:12px;font-weight:800;letter-spacing:.08em}.doctor-hero h1{margin:7px 0;font-size:32px;color:var(--ink-800)}.doctor-hero p,.panel p,.review-card p{margin:0;color:var(--ink-500)}.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin:18px 0}.metric-grid article,.panel{background:var(--paper);border:1px solid var(--line);border-radius:18px;padding:20px}.metric-grid span,.metric-grid small{display:block;color:var(--ink-500)}.metric-grid strong{display:block;font-size:30px;margin:8px 0;color:var(--care-950)}.metric-grid .risk strong{color:var(--danger)}.doctor-tabs{background:transparent}.panel{min-height:260px}.patient-link{border:0;background:none;color:var(--care-600);font:inherit;font-weight:700;cursor:pointer}.review-list{display:grid;gap:12px}.review-card{padding:18px;border:1px solid var(--line);border-radius:14px}.review-card h3{margin:6px 0}.patient-filter{justify-content:flex-start;margin-bottom:18px}.patient-filter span{font-weight:700}.patient-filter :deep(.el-select){width:min(320px,100%)}.timeline,.plan-grid{display:grid;gap:12px}.timeline article,.plan-grid article{padding:18px;border-left:4px solid var(--care-600);border-radius:12px;background:var(--surface-subtle)}.timeline h3,.plan-grid h3{margin:6px 0}.timeline p,.plan-grid p{white-space:pre-wrap;color:var(--ink-800)}.plan-grid{grid-template-columns:repeat(2,minmax(0,1fr))}@media(max-width:900px){.metric-grid{grid-template-columns:repeat(2,1fr)}.doctor-page{padding:18px 12px}.doctor-hero,.review-card{align-items:flex-start;flex-direction:column}.plan-grid{grid-template-columns:1fr}}@media(max-width:520px){.metric-grid{grid-template-columns:1fr}.review-actions{width:100%}.review-actions .el-button{flex:1}}
</style>
