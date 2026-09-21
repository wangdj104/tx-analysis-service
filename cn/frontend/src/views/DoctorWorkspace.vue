<template>
  <main class="doctor-page">
    <header class="doctor-hero">
      <div><span class="eyebrow">临床工作区</span><h1>医生工作台</h1><p>集中查看风险、核验导入结果，并为已分配患者协调连续照护。</p></div>
      <el-button :loading="loading" @click="loadAll"><el-icon><Refresh /></el-icon>刷新</el-button>
    </header>

    <section class="metric-grid" aria-label="临床工作量概览">
      <article><span>已分配患者</span><strong>{{ summary.assignedPatients || 0 }}</strong><small>当前有效的医疗团队分配</small></article>
      <article><span>待复核</span><strong>{{ summary.pendingReviews || 0 }}</strong><small>导入记录与 AI 辅助分析</small></article>
      <article class="risk"><span>危急患者</span><strong>{{ summary.criticalPatients || 0 }}</strong><small>存在未关闭危急提醒的患者</small></article>
      <article><span>执行中计划</span><strong>{{ summary.activePlans || 0 }}</strong><small>由医生制定的照护计划</small></article>
    </section>

    <el-tabs v-model="tab" class="doctor-tabs">
      <el-tab-pane label="患者面板" name="patients">
        <section class="panel">
          <div class="panel-head"><div><h2>已分配患者</h2><p>这里只显示已分配到当前医疗团队的患者。</p></div></div>
          <el-table :data="patients" v-loading="loading" stripe empty-text="暂未分配患者">
            <el-table-column prop="name" label="患者" min-width="140"><template #default="{row}"><button class="patient-link" @click="selectPatient(row)">{{ row.name }}</button></template></el-table-column>
            <el-table-column prop="careTeamRole" label="团队角色" min-width="140" />
            <el-table-column prop="lastVitalAt" label="最近生命体征" min-width="170"><template #default="{row}">{{ row.lastVitalAt || '暂无读数' }}</template></el-table-column>
            <el-table-column prop="criticalAlerts" label="危急提醒" width="130"><template #default="{row}"><el-tag :type="Number(row.criticalAlerts)>0?'danger':'success'">{{ row.criticalAlerts || 0 }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="210"><template #default="{row}"><el-button link type="primary" @click="openNote(row)">添加笔记</el-button><el-button link @click="openPlan(row)">照护计划</el-button></template></el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
      <el-tab-pane :label="`复核队列（${reviews.length}）`" name="reviews">
        <section class="panel review-list">
          <article v-for="item in reviews" :key="item.sourceType+'-'+item.sourceId" class="review-card">
            <div><span class="eyebrow">{{ sourceLabel(item.sourceType) }}</span><h3>{{ item.patientName }} · {{ item.title }}</h3><p>{{ item.occurredAt || '日期未知' }}<template v-if="item.confidence"> · 可信度 {{ Math.round(Number(item.confidence)*100) }}%</template></p></div>
            <div class="review-actions"><el-button type="success" plain @click="review(item,'APPROVED')">通过</el-button><el-button type="danger" plain @click="review(item,'REJECTED')">驳回</el-button></div>
          </article>
          <el-empty v-if="!reviews.length" description="暂无等待复核的临床项目" />
        </section>
      </el-tab-pane>
      <el-tab-pane label="临床笔记" name="notes">
        <section class="panel"><PatientSelect v-model="selectedPatientId" :patients="patients" @change="loadPatientContext" /><div class="timeline">
          <article v-for="note in notes" :key="note.id"><span>{{ note.noteType }} · {{ note.createdAt }}</span><h3>{{ note.doctorName || '医疗团队医生' }}</h3><p>{{ note.noteText }}</p><el-tag size="small">{{ note.visibility === 'PATIENT' ? '患者可见' : '仅医疗团队可见' }}</el-tag></article>
          <el-empty v-if="selectedPatientId && !notes.length" description="该患者暂无临床笔记" />
        </div></section>
      </el-tab-pane>
      <el-tab-pane label="照护计划" name="plans">
        <section class="panel"><PatientSelect v-model="selectedPatientId" :patients="patients" @change="loadPatientContext" /><div class="plan-grid">
          <article v-for="plan in plans" :key="plan.id"><el-tag>{{ plan.status }}</el-tag><h3>{{ plan.title }}</h3><p>{{ plan.instructions }}</p><small>{{ plan.planType }} · 目标日期 {{ plan.targetDate || '持续执行' }}</small></article>
          <el-empty v-if="selectedPatientId && !plans.length" description="该患者暂无照护计划" />
        </div></section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="noteVisible" title="添加临床笔记" width="min(560px,94vw)">
      <el-form label-position="top"><el-form-item label="患者"><b>{{ activePatient?.name }}</b></el-form-item><el-form-item label="笔记类型"><el-select v-model="noteForm.noteType"><el-option label="随访" value="FOLLOW_UP"/><el-option label="评估" value="ASSESSMENT"/><el-option label="用药" value="MEDICATION"/></el-select></el-form-item><el-form-item label="可见范围"><el-radio-group v-model="noteForm.visibility"><el-radio value="CARE_TEAM">仅医疗团队</el-radio><el-radio value="PATIENT">患者可见</el-radio></el-radio-group></el-form-item><el-form-item label="临床笔记"><el-input v-model="noteForm.noteText" type="textarea" :rows="5" maxlength="4000" show-word-limit/></el-form-item></el-form>
      <template #footer><el-button @click="noteVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitNote">保存笔记</el-button></template>
    </el-dialog>
    <el-dialog v-model="planVisible" title="创建照护计划" width="min(600px,94vw)">
      <el-form label-position="top"><el-form-item label="患者"><b>{{ activePatient?.name }}</b></el-form-item><el-form-item label="计划标题"><el-input v-model="planForm.title" maxlength="160"/></el-form-item><el-form-item label="计划类型"><el-select v-model="planForm.planType"><el-option label="随访" value="FOLLOW_UP"/><el-option label="用药" value="MEDICATION"/><el-option label="透析" value="DIALYSIS"/><el-option label="营养" value="NUTRITION"/></el-select></el-form-item><el-form-item label="目标日期"><el-date-picker v-model="planForm.targetDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="执行说明"><el-input v-model="planForm.instructions" type="textarea" :rows="5" maxlength="4000" show-word-limit/></el-form-item></el-form>
      <template #footer><el-button @click="planVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitPlan">启用计划</el-button></template>
    </el-dialog>
  </main>
</template>

<script setup>
import { defineComponent, h, onMounted, reactive, ref, resolveComponent } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { getDoctorSummary, getDoctorNotes, getDoctorPlans, saveDoctorNote, saveDoctorPlan, completeDoctorReview } from '@/api/doctorWorkspace';

const PatientSelect = defineComponent({ props:{ modelValue:[Number,String], patients:{type:Array,default:()=>[]} }, emits:['update:modelValue','change'], setup(props,{emit}){return()=>h('div',{class:'patient-filter'},[h('span','患者'),h(resolveComponent('el-select'),{modelValue:props.modelValue,placeholder:'请选择患者','onUpdate:modelValue':v=>{emit('update:modelValue',v);emit('change',v)}},{default:()=>props.patients.map(p=>h(resolveComponent('el-option'),{key:p.id,label:p.name,value:p.id}))})])} });
const loading=ref(false),saving=ref(false),tab=ref('patients'),summary=ref({}),patients=ref([]),reviews=ref([]),notes=ref([]),plans=ref([]),selectedPatientId=ref(null),activePatient=ref(null),noteVisible=ref(false),planVisible=ref(false);
const noteForm=reactive({noteType:'FOLLOW_UP',visibility:'CARE_TEAM',noteText:''});
const planForm=reactive({title:'',planType:'FOLLOW_UP',targetDate:'',instructions:''});
async function loadAll(){loading.value=true;try{const res=await getDoctorSummary();summary.value=res.data||{};patients.value=summary.value.patients||[];reviews.value=summary.value.reviewQueue||[];if(!selectedPatientId.value&&patients.value.length)selectedPatientId.value=patients.value[0].id;if(selectedPatientId.value)await loadPatientContext()}finally{loading.value=false}}
async function loadPatientContext(){if(!selectedPatientId.value){notes.value=[];plans.value=[];return}const [n,p]=await Promise.all([getDoctorNotes(selectedPatientId.value),getDoctorPlans(selectedPatientId.value)]);notes.value=n.data||[];plans.value=p.data||[]}
function selectPatient(row){selectedPatientId.value=row.id;activePatient.value=row;tab.value='notes';loadPatientContext()}
function openNote(row){activePatient.value=row;selectedPatientId.value=row.id;Object.assign(noteForm,{noteType:'FOLLOW_UP',visibility:'CARE_TEAM',noteText:''});noteVisible.value=true}
function openPlan(row){activePatient.value=row;selectedPatientId.value=row.id;Object.assign(planForm,{title:'',planType:'FOLLOW_UP',targetDate:'',instructions:''});planVisible.value=true}
async function submitNote(){if(!noteForm.noteText.trim()){ElMessage.warning('请输入临床笔记。');return}saving.value=true;try{await saveDoctorNote({patientId:selectedPatientId.value,...noteForm});ElMessage.success('临床笔记已保存。');noteVisible.value=false;await loadPatientContext()}finally{saving.value=false}}
async function submitPlan(){if(!planForm.title.trim()||!planForm.instructions.trim()){ElMessage.warning('请输入计划标题和执行说明。');return}saving.value=true;try{await saveDoctorPlan({patientId:selectedPatientId.value,status:'ACTIVE',...planForm});ElMessage.success('照护计划已启用。');planVisible.value=false;await loadAll()}finally{saving.value=false}}
async function review(item,decision){const {value}=await ElMessageBox.prompt(decision==='APPROVED'?'可选的通过说明':'请输入驳回原因','临床复核',{confirmButtonText:decision==='APPROVED'?'通过':'驳回',cancelButtonText:'取消',inputType:'textarea'});await completeDoctorReview({sourceType:item.sourceType,sourceId:item.sourceId,decision,reviewNote:value});ElMessage.success('复核已完成。');await loadAll()}
function sourceLabel(type){return type==='MEDICAL_RECORD'?'导入的医疗记录':'AI 辅助分析'}
onMounted(loadAll);
</script>

<style scoped>
.doctor-page{max-width:1440px;margin:0 auto;padding:32px}.doctor-hero,.panel-head,.review-card,.review-actions,.patient-filter{display:flex;align-items:center;justify-content:space-between;gap:16px}.doctor-hero{padding:30px;border-radius:22px;background:linear-gradient(135deg,#eef8f5,#f8fbff);border:1px solid #dcece7}.eyebrow{color:#187566;text-transform:uppercase;font-size:12px;font-weight:800;letter-spacing:.08em}.doctor-hero h1{margin:7px 0;font-size:32px;color:#163b37}.doctor-hero p,.panel p,.review-card p{margin:0;color:#64748b}.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin:18px 0}.metric-grid article,.panel{background:#fff;border:1px solid #e2e8f0;border-radius:18px;padding:20px}.metric-grid span,.metric-grid small{display:block;color:#64748b}.metric-grid strong{display:block;font-size:30px;margin:8px 0;color:#174b45}.metric-grid .risk strong{color:#c2413b}.doctor-tabs{background:transparent}.panel{min-height:260px}.patient-link{border:0;background:none;color:#166d61;font:inherit;font-weight:700;cursor:pointer}.review-list{display:grid;gap:12px}.review-card{padding:18px;border:1px solid #e2e8f0;border-radius:14px}.review-card h3{margin:6px 0}.patient-filter{justify-content:flex-start;margin-bottom:18px}.patient-filter span{font-weight:700}.patient-filter :deep(.el-select){width:min(320px,100%)}.timeline,.plan-grid{display:grid;gap:12px}.timeline article,.plan-grid article{padding:18px;border-left:4px solid #2f8578;border-radius:12px;background:#f8fbfa}.timeline h3,.plan-grid h3{margin:6px 0}.timeline p,.plan-grid p{white-space:pre-wrap;color:#334155}.plan-grid{grid-template-columns:repeat(2,minmax(0,1fr))}@media(max-width:900px){.metric-grid{grid-template-columns:repeat(2,1fr)}.doctor-page{padding:18px 12px}.doctor-hero,.review-card{align-items:flex-start;flex-direction:column}.plan-grid{grid-template-columns:1fr}}@media(max-width:520px){.metric-grid{grid-template-columns:1fr}.review-actions{width:100%}.review-actions .el-button{flex:1}}
</style>
