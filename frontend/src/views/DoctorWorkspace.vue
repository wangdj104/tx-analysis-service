<template>
  <main class="doctor-page">
    <header class="doctor-hero">
      <div><span class="eyebrow">Clinical workspace</span><h1>Doctor Workspace</h1><p>Review risk, verify imported results, and coordinate care for assigned patients.</p></div>
      <el-button :loading="loading" @click="loadAll"><el-icon><Refresh /></el-icon>Refresh</el-button>
    </header>

    <div class="care-inboxes">
    <section class="consultation-entry"><div><h2>Consultation inbox</h2><p>Patients who select you for a consultation appear here, even before a care-team assignment. Open their conversation to reply.</p></div><router-link to="/care-journey?tab=consultation" class="consultation-link">Open consultations <span aria-hidden="true">→</span></router-link></section>
    <section class="consultation-entry"><div><h2>Appointments and availability</h2><p>See patients booked with you, cancel or complete visits, and manage availability. A long-term care-team assignment is not required.</p></div><router-link to="/care-journey?tab=operations" class="consultation-link">Manage appointments <span aria-hidden="true">→</span></router-link></section>
    </div>

    <section class="metric-grid" aria-label="Clinical workload summary">
      <article><span>Assigned patients</span><strong>{{ summary.assignedPatients || 0 }}</strong><small>Active care-team assignments</small></article>
      <article><span>Pending reviews</span><strong>{{ summary.pendingReviews || 0 }}</strong><small>Imported records and AI analyses</small></article>
      <article class="risk"><span>Critical patients</span><strong>{{ summary.criticalPatients || 0 }}</strong><small>Patients with an open critical alert</small></article>
      <article><span>Active plans</span><strong>{{ summary.activePlans || 0 }}</strong><small>Clinician-authored care plans</small></article>
    </section>

    <el-tabs v-model="tab" class="doctor-tabs">
      <el-tab-pane label="Patient panel" name="patients">
        <section class="panel">
          <div class="panel-head"><div><h2>Assigned patients</h2><p>Only patients assigned to your care team are shown.</p></div></div>
          <el-table :data="patients" v-loading="loading" stripe empty-text="No patients have been assigned">
            <el-table-column prop="name" label="Patient" min-width="140"><template #default="{row}"><button class="patient-link" @click="selectPatient(row)">{{ row.name }}</button></template></el-table-column>
            <el-table-column prop="careTeamRole" label="Care-team role" min-width="140" />
            <el-table-column prop="lastVitalAt" label="Latest vital sign" min-width="170"><template #default="{row}">{{ row.lastVitalAt || 'No reading' }}</template></el-table-column>
            <el-table-column prop="criticalAlerts" label="Critical alerts" width="130"><template #default="{row}"><el-tag :type="Number(row.criticalAlerts)>0?'danger':'success'">{{ row.criticalAlerts || 0 }}</el-tag></template></el-table-column>
            <el-table-column label="Actions" width="210"><template #default="{row}"><el-button link type="primary" @click="openNote(row)">Add note</el-button><el-button link @click="openPlan(row)">Care plan</el-button></template></el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
      <el-tab-pane :label="`Review queue (${reviews.length})`" name="reviews">
        <section class="panel review-list">
          <article v-for="item in reviews" :key="item.sourceType+'-'+item.sourceId" class="review-card">
            <div><span class="eyebrow">{{ sourceLabel(item.sourceType) }}</span><h3>{{ item.patientName }} · {{ item.title }}</h3><p>{{ item.occurredAt || 'Date unavailable' }}<template v-if="item.confidence"> · Confidence {{ Math.round(Number(item.confidence)*100) }}%</template></p></div>
            <div class="review-actions"><el-button type="success" plain @click="review(item,'APPROVED')">Approve</el-button><el-button type="danger" plain @click="review(item,'REJECTED')">Reject</el-button></div>
          </article>
          <el-empty v-if="!reviews.length" description="No clinical items are waiting for review" />
        </section>
      </el-tab-pane>
      <el-tab-pane label="Clinical notes" name="notes">
        <section class="panel"><PatientSelect v-model="selectedPatientId" :patients="patients" @change="loadPatientContext" /><div class="timeline">
          <article v-for="note in notes" :key="note.id"><span>{{ note.noteType }} · {{ note.createdAt }}</span><h3>{{ note.doctorName || 'Care-team clinician' }}</h3><p>{{ note.noteText }}</p><el-tag size="small">{{ note.visibility === 'PATIENT' ? 'Visible to patient' : 'Care team only' }}</el-tag></article>
          <el-empty v-if="selectedPatientId && !notes.length" description="No clinical notes for this patient" />
        </div></section>
      </el-tab-pane>
      <el-tab-pane label="Care plans" name="plans">
        <section class="panel"><PatientSelect v-model="selectedPatientId" :patients="patients" @change="loadPatientContext" /><div class="plan-grid">
          <article v-for="plan in plans" :key="plan.id"><el-tag>{{ plan.status }}</el-tag><h3>{{ plan.title }}</h3><p>{{ plan.instructions }}</p><small>{{ plan.planType }} · Target {{ plan.targetDate || 'ongoing' }}</small></article>
          <el-empty v-if="selectedPatientId && !plans.length" description="No care plans for this patient" />
        </div></section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="noteVisible" title="Add clinical note" width="min(560px,94vw)">
      <el-form label-position="top"><el-form-item label="Patient"><b>{{ activePatient?.name }}</b></el-form-item><el-form-item label="Note type"><el-select v-model="noteForm.noteType"><el-option label="Follow-up" value="FOLLOW_UP"/><el-option label="Assessment" value="ASSESSMENT"/><el-option label="Medication" value="MEDICATION"/></el-select></el-form-item><el-form-item label="Visibility"><el-radio-group v-model="noteForm.visibility"><el-radio value="CARE_TEAM">Care team only</el-radio><el-radio value="PATIENT">Visible to patient</el-radio></el-radio-group></el-form-item><el-form-item label="Clinical note"><el-input v-model="noteForm.noteText" type="textarea" :rows="5" maxlength="4000" show-word-limit/></el-form-item></el-form>
      <template #footer><el-button @click="noteVisible=false">Cancel</el-button><el-button type="primary" :loading="saving" @click="submitNote">Save note</el-button></template>
    </el-dialog>
    <el-dialog v-model="planVisible" title="Create care plan" width="min(600px,94vw)">
      <el-form label-position="top"><el-form-item label="Patient"><b>{{ activePatient?.name }}</b></el-form-item><el-form-item label="Plan title"><el-input v-model="planForm.title" maxlength="160"/></el-form-item><el-form-item label="Plan type"><el-select v-model="planForm.planType"><el-option label="Follow-up" value="FOLLOW_UP"/><el-option label="Medication" value="MEDICATION"/><el-option label="Dialysis" value="DIALYSIS"/><el-option label="Nutrition" value="NUTRITION"/></el-select></el-form-item><el-form-item label="Target date"><el-date-picker v-model="planForm.targetDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="Instructions"><el-input v-model="planForm.instructions" type="textarea" :rows="5" maxlength="4000" show-word-limit/></el-form-item></el-form>
      <template #footer><el-button @click="planVisible=false">Cancel</el-button><el-button type="primary" :loading="saving" @click="submitPlan">Activate plan</el-button></template>
    </el-dialog>
  </main>
</template>

<script setup>
import { defineComponent, h, onMounted, reactive, ref, resolveComponent } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { getDoctorSummary, getDoctorNotes, getDoctorPlans, saveDoctorNote, saveDoctorPlan, completeDoctorReview } from '@/api/doctorWorkspace';

const PatientSelect = defineComponent({ props:{ modelValue:[Number,String], patients:{type:Array,default:()=>[]} }, emits:['update:modelValue','change'], setup(props,{emit}){return()=>h('div',{class:'patient-filter'},[h('span','Patient'),h(resolveComponent('el-select'),{modelValue:props.modelValue,placeholder:'Select a patient','onUpdate:modelValue':v=>{emit('update:modelValue',v);emit('change',v)}},{default:()=>props.patients.map(p=>h(resolveComponent('el-option'),{key:p.id,label:p.name,value:p.id}))})])} });
const loading=ref(false),saving=ref(false),tab=ref('patients'),summary=ref({}),patients=ref([]),reviews=ref([]),notes=ref([]),plans=ref([]),selectedPatientId=ref(null),activePatient=ref(null),noteVisible=ref(false),planVisible=ref(false);
const noteForm=reactive({noteType:'FOLLOW_UP',visibility:'CARE_TEAM',noteText:''});
const planForm=reactive({title:'',planType:'FOLLOW_UP',targetDate:'',instructions:''});
let contextEpoch=0, summaryEpoch=0;
async function loadAll(){const epoch=++summaryEpoch;loading.value=true;try{const res=await getDoctorSummary();if(epoch!==summaryEpoch)return;summary.value=res.data||{};patients.value=summary.value.patients||[];reviews.value=summary.value.reviewQueue||[];if(!patients.value.some(p=>p.id===selectedPatientId.value))selectedPatientId.value=patients.value[0]?.id||null;await loadPatientContext()}finally{if(epoch===summaryEpoch)loading.value=false}}
async function loadPatientContext(){const epoch=++contextEpoch;const patientId=selectedPatientId.value;notes.value=[];plans.value=[];if(!patientId)return;const [n,p]=await Promise.all([getDoctorNotes(patientId),getDoctorPlans(patientId)]);if(epoch!==contextEpoch||patientId!==selectedPatientId.value)return;notes.value=n.data||[];plans.value=p.data||[]}
function selectPatient(row){selectedPatientId.value=row.id;activePatient.value=row;tab.value='notes';loadPatientContext()}
function openNote(row){activePatient.value=row;selectedPatientId.value=row.id;Object.assign(noteForm,{noteType:'FOLLOW_UP',visibility:'CARE_TEAM',noteText:''});noteVisible.value=true}
function openPlan(row){activePatient.value=row;selectedPatientId.value=row.id;Object.assign(planForm,{title:'',planType:'FOLLOW_UP',targetDate:'',instructions:''});planVisible.value=true}
async function submitNote(){if(!noteForm.noteText.trim()){ElMessage.warning('Enter a clinical note.');return}saving.value=true;try{await saveDoctorNote({patientId:selectedPatientId.value,...noteForm});ElMessage.success('Clinical note saved.');noteVisible.value=false;await loadPatientContext()}finally{saving.value=false}}
async function submitPlan(){if(!planForm.title.trim()||!planForm.instructions.trim()){ElMessage.warning('Enter a title and instructions.');return}saving.value=true;try{await saveDoctorPlan({patientId:selectedPatientId.value,status:'ACTIVE',...planForm});ElMessage.success('Care plan activated.');planVisible.value=false;await loadAll()}finally{saving.value=false}}
async function review(item,decision){const {value}=await ElMessageBox.prompt(decision==='APPROVED'?'Optional approval note':'Reason for rejection','Clinical review',{confirmButtonText:decision==='APPROVED'?'Approve':'Reject',cancelButtonText:'Cancel',inputType:'textarea'});await completeDoctorReview({sourceType:item.sourceType,sourceId:item.sourceId,decision,reviewNote:value});ElMessage.success('Review completed.');await loadAll()}
function sourceLabel(type){return type==='MEDICAL_RECORD'?'Imported medical record':'AI-assisted analysis'}
onMounted(loadAll);
</script>

<style scoped>
.care-inboxes{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.care-inboxes .consultation-entry{min-width:0;flex-direction:column;align-items:stretch}.care-inboxes .consultation-link{justify-content:center}@media(max-width:850px){.care-inboxes{grid-template-columns:1fr}}
.consultation-entry{display:flex;align-items:center;justify-content:space-between;gap:20px;margin:18px 0;padding:20px;border:1px solid #cfe4dc;border-radius:16px;background:#f2faf6}.consultation-entry h2{font-size:18px;margin:0 0 6px;color:#174b45}.consultation-entry p{margin:0;color:#64748b;line-height:1.6}.consultation-link{display:inline-flex;align-items:center;gap:12px;flex-shrink:0;min-height:44px;padding:10px 16px;border-radius:10px;background:#166d61;color:white;text-decoration:none;font-weight:600}@media(max-width:600px){.consultation-entry{align-items:stretch;flex-direction:column}.consultation-link{justify-content:center}}
.doctor-page{max-width:1440px;margin:0 auto;padding:32px}.doctor-hero,.panel-head,.review-card,.review-actions,.patient-filter{display:flex;align-items:center;justify-content:space-between;gap:16px}.doctor-hero{padding:30px;border-radius:22px;background:linear-gradient(135deg,#eef8f5,#f8fbff);border:1px solid #dcece7}.eyebrow{color:#187566;text-transform:uppercase;font-size:12px;font-weight:800;letter-spacing:.08em}.doctor-hero h1{margin:7px 0;font-size:32px;color:#163b37}.doctor-hero p,.panel p,.review-card p{margin:0;color:#64748b}.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin:18px 0}.metric-grid article,.panel{background:#fff;border:1px solid #e2e8f0;border-radius:18px;padding:20px}.metric-grid span,.metric-grid small{display:block;color:#64748b}.metric-grid strong{display:block;font-size:30px;margin:8px 0;color:#174b45}.metric-grid .risk strong{color:#c2413b}.doctor-tabs{background:transparent}.panel{min-height:260px}.patient-link{border:0;background:none;color:#166d61;font:inherit;font-weight:700;cursor:pointer}.review-list{display:grid;gap:12px}.review-card{padding:18px;border:1px solid #e2e8f0;border-radius:14px}.review-card h3{margin:6px 0}.patient-filter{justify-content:flex-start;margin-bottom:18px}.patient-filter span{font-weight:700}.patient-filter :deep(.el-select){width:min(320px,100%)}.timeline,.plan-grid{display:grid;gap:12px}.timeline article,.plan-grid article{padding:18px;border-left:4px solid #2f8578;border-radius:12px;background:#f8fbfa}.timeline h3,.plan-grid h3{margin:6px 0}.timeline p,.plan-grid p{white-space:pre-wrap;color:#334155}.plan-grid{grid-template-columns:repeat(2,minmax(0,1fr))}@media(max-width:900px){.metric-grid{grid-template-columns:repeat(2,1fr)}.doctor-page{padding:18px 12px}.doctor-hero,.review-card{align-items:flex-start;flex-direction:column}.plan-grid{grid-template-columns:1fr}}@media(max-width:520px){.metric-grid{grid-template-columns:1fr}.review-actions{width:100%}.review-actions .el-button{flex:1}}
</style>
