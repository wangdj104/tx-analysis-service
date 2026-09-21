<template>
  <main class="family-page" v-loading="loading">
    <header class="hero"><div><span class="hero-eyebrow">Daily care</span><h1>Family Health Workspace</h1><p>See today's tasks, completed care, and items that need attention in one place.</p></div><el-button @click="reload">Refresh data</el-button></header>
    <el-alert v-if="!patientId" title="Select a family member in the top bar first." type="warning" :closable="false" show-icon />
    <template v-else>
      <section class="today-grid">
        <article class="status-card"><span>Today's medications</span><strong>{{ takenCount }}/{{ activeIntakes.length }}</strong><small>{{ missedCount ? `${missedCount} missed` : 'Follow today’s plan and record each dose' }}</small></article>
        <article class="status-card"><span>Dialysis schedule</span><strong>{{ todaySchedule ? statusText(todaySchedule.status) : 'No schedule' }}</strong><small>{{ todaySchedule?.scheduleTime || '—' }}</small></article>
        <article class="status-card"><span>Personal targets</span><strong>{{ target.id ? 'Configured' : 'Not configured' }}</strong><small>Personal targets improve alert accuracy</small></article>
      </section>
      <el-tabs v-model="tab" class="workspace">
        <el-tab-pane v-if="availableTabs.includes('today')" label="Today's Tasks" name="today">
          <div class="toolbar"><el-button type="primary" @click="openEvent()">Record symptom or event</el-button><el-button @click="$router.push('/bp-self-monitor')">Quick blood pressure / glucose entry</el-button><el-button @click="$router.push('/medication?tab=remind')">Medication reminders</el-button></div>
          <el-empty v-if="!intakes.length && !todaySchedule" description="Nothing needs attention today" />
          <div v-if="todaySchedule" class="task-row"><div><b>{{ todaySchedule.scheduleTime || 'Time not set' }} Dialysis session</b><p>{{ todaySchedule.remark }}</p></div><el-tag>{{statusText(todaySchedule.status)}}</el-tag><div class="actions"><el-button @click="openSchedule(todaySchedule)">View schedule</el-button><el-button v-if="todaySchedule.status==='PLANNED'" :disabled="saving" @click="changeSchedule(todaySchedule,'COMPLETED')">Mark complete</el-button></div></div>
          <div v-for="item in intakes" :key="item.id" class="task-row">
            <div><b>{{ item.scheduledAt?.slice(11,16) }} {{ item.drugName }}</b><p>{{ item.dosage || 'As prescribed' }}</p><small v-if="item.status==='SNOOZED'">Snoozed until {{ item.snoozeUntil }}</small></div>
            <el-tag :type="tagType(item.status)">{{ intakeText(item.status) }}</el-tag>
            <div v-if="['PENDING','MISSED','SNOOZED'].includes(item.status)" class="actions"><el-button type="success" :disabled="saving" @click="doIntake(item,'TAKEN')">Taken</el-button><el-button :disabled="saving" @click="doIntake(item,'SNOOZED')">Remind in 15 minutes</el-button><el-button type="danger" plain :disabled="saving" @click="doIntake(item,'SKIPPED')">Skip</el-button></div>
          </div>
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('timeline')" label="Health Timeline" name="timeline">
          <div class="toolbar"><el-button type="primary" @click="openEvent()">Add health event</el-button><el-date-picker v-model="eventRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="Start Date" end-placeholder="End Date" @change="reload" /></div>
          <p>Measurements, dialysis sessions, and medication records are combined automatically. Up to 200 recent events are shown; edit source records in their original module.</p>
          <el-timeline><el-timeline-item v-for="e in events" :key="`${e.sourceType || 'MANUAL'}-${e.id}`" :timestamp="`${e.eventDate} ${e.eventTime||''}`" placement="top"><el-card><b>{{e.title}}</b><p>{{e.summary || e.remark || '—'}}</p><el-tag size="small">{{eventType(e.eventType)}}</el-tag><span v-if="!e.sourceType || e.sourceType==='MANUAL'"><el-button link type="primary" @click="openEvent(e)">Edit</el-button><el-popconfirm title="Delete this event?" @confirm="removeEvent(e)"><template #reference><el-button link type="danger" :disabled="saving">Delete</el-button></template></el-popconfirm></span></el-card></el-timeline-item></el-timeline><el-empty v-if="!events.length" description="No health events" />
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('schedule')" label="Dialysis Schedule" name="schedule">
          <el-alert class="schedule-tip" title="Schedules are created only from dates you add or a recurring plan you explicitly confirm in the Clinical Workbench. Entries can be edited or cancelled here." type="info" :closable="false" show-icon />
          <div class="toolbar"><el-button type="primary" @click="openSchedule()">Add schedule</el-button></div>
          <el-table :data="schedules"><el-table-column prop="scheduleDate" label="Date" min-width="110"/><el-table-column prop="scheduleTime" label="Time"/><el-table-column label="Status"><template #default="{row}">{{statusText(row.status)}}<small v-if="row.completedRecordId"> (linked to a record) </small></template></el-table-column><el-table-column prop="remark" label="Notes"/><el-table-column label="Actions" min-width="200"><template #default="{row}"><el-button link @click="openSchedule(row)">Edit</el-button><el-button v-if="row.status==='PLANNED'" link type="success" :disabled="saving" @click="changeSchedule(row,'COMPLETED')">Complete</el-button><el-button v-if="row.status!=='CANCELLED'" link type="danger" :disabled="saving" @click="changeSchedule(row,'CANCELLED')">Cancel schedule</el-button><el-button v-else link type="primary" :disabled="saving" @click="changeSchedule(row,'PLANNED')">Restore</el-button></template></el-table-column></el-table>
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('target')" label="Personal Goals" name="target">
          <el-form :model="target" label-width="130px" class="target-form"><el-divider>Blood pressure target (mmHg)</el-divider><div class="form-grid"><el-form-item label="Systolic pressure"><el-input-number v-model="target.systolicMin" :min="1"/> to <el-input-number v-model="target.systolicMax" :min="1"/></el-form-item><el-form-item label="Diastolic pressure"><el-input-number v-model="target.diastolicMin" :min="1"/> to <el-input-number v-model="target.diastolicMax" :min="1"/></el-form-item><el-form-item label="Target weight"><el-input-number v-model="target.targetWeight" :precision="2" :min="1"/> kg</el-form-item><el-form-item label="Emergency contact"><el-input v-model="target.emergencyContact"/></el-form-item><el-form-item label="Phone number"><el-input v-model="target.emergencyPhone"/></el-form-item><el-form-item label="Hospital"><el-input v-model="target.hospitalName"/></el-form-item><el-form-item label="Primary clinician"><el-input v-model="target.doctorName"/></el-form-item></div><el-button type="primary" :loading="saving" @click="submitTarget">Save goals</el-button></el-form>
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('summary')" label="Visit Summary" name="summary">
          <div class="toolbar"><el-button :loading="summaryLoading" @click="loadSummary">Generate / refresh summary</el-button><el-button :disabled="!summary" @click="printSummary">Print / save PDF</el-button></div>
          <article v-if="summary" ref="summaryElement" class="visit-summary">
            <h2>{{summary.patient?.name}} · Visit Summary</h2><p>Generated: {{summary.generatedAt}}</p><p>Medical history: {{summary.patient?.medicalHistory || 'Not provided'}}</p>
            <p>Hospital: {{summary.target?.hospitalName || 'Not provided'}}; Primary clinician: {{summary.target?.doctorName || 'Not provided'}}</p><p>Emergency contact: {{summary.target?.emergencyContact || summary.patient?.emergencyContact || 'Not provided'}} {{summary.target?.emergencyPhone || summary.patient?.emergencyPhone}}</p>
            <p>Blood pressure target: {{summary.target?.systolicMin || '—'}}–{{summary.target?.systolicMax || '—'}} / {{summary.target?.diastolicMin || '—'}}–{{summary.target?.diastolicMax || '—'}} mmHg</p>
            <h3>Current medications</h3><table><thead><tr><th>Medication</th><th>Dose instructions</th></tr></thead><tbody><tr v-for="m in summary.medications" :key="m.id"><td>{{m.drugName}}</td><td>{{m.defaultDosage || 'as prescribed'}}</td></tr></tbody></table><p v-if="!summary.medications?.length">No medication records</p>
            <h3>Recent measurements (up to 30)</h3><table><thead><tr><th>Time</th><th>Blood pressure (mmHg)</th><th>Blood glucose</th></tr></thead><tbody><tr v-for="r in summary.recentMeasurements" :key="r.id"><td>{{r.recordDate}} {{r.recordTime}}</td><td>{{r.systolicBp ?? '—'}} / {{r.diastolicBp ?? '—'}}</td><td>{{r.bloodGlucose ?? '—'}} {{r.bgUnit}}</td></tr></tbody></table>
            <h3>Unresolved alerts (up to 30)</h3><ul><li v-for="a in summary.unresolvedAlerts" :key="a.id">{{a.triggeredAt}} {{a.alertTitle}}: {{a.triggeredValue}} {{a.handlingNote}}</li></ul><p v-if="!summary.unresolvedAlerts?.length">No unresolved alerts</p>
            <h3>Questions for the clinician</h3><ul><li v-for="q in summary.questions||[]" :key="q.id"><b>{{q.title}}</b><p>{{q.details?.description}}</p><p v-if="q.details?.answer">Clinician's answer: {{q.details.answer}}</p><p v-if="q.details?.followUp">Follow-up: {{q.details.followUp}}</p></li></ul>
            <h3>Recent symptom tracking</h3><ul><li v-for="s in summary.careSymptoms||[]" :key="s.id">{{s.eventAt}} {{s.title}}, self-rated {{s.details?.severity}}/10, duration {{s.details?.duration || 'Not provided'}}; {{s.details?.response}}</li></ul>
            <h3>Recent health events (up to 30)</h3><ul><li v-for="e in summary.recentEvents" :key="`${e.sourceType}-${e.id}`">{{e.eventDate}} {{e.title}}: {{e.summary}}</li></ul><p>{{summary.disclaimer}}</p>
          </article><el-empty v-else description="Generate a summary to prepare recent records for the next appointment"/>
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog v-model="eventVisible" :title="eventForm.id?'Edit health event':'Record health event'" width="min(520px, 94vw)"><el-form :model="eventForm" label-width="90px"><el-form-item label="Date" required><el-date-picker v-model="eventForm.eventDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="Time"><el-time-picker v-model="eventForm.eventTime" value-format="HH:mm" format="HH:mm"/></el-form-item><el-form-item label="Type"><el-select v-model="eventForm.eventType"><el-option label="Symptom" value="SYMPTOM"/><el-option label="Visit" value="VISIT"/><el-option label="Note" value="NOTE"/></el-select></el-form-item><el-form-item label="Title" required><el-input v-model="eventForm.title" maxlength="120"/></el-form-item><el-form-item label="Description"><el-input v-model="eventForm.summary" type="textarea" maxlength="500" show-word-limit/></el-form-item></el-form><template #footer><el-button @click="eventVisible=false">Cancel</el-button><el-button type="primary" :loading="saving" @click="submitEvent">Save</el-button></template></el-dialog>
    <el-dialog v-model="scheduleVisible" :title="scheduleForm.id?'Edit dialysis schedule':'Add dialysis schedule'" width="min(480px, 94vw)"><el-form :model="scheduleForm" label-width="80px"><el-form-item label="Date" required><el-date-picker v-model="scheduleForm.scheduleDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="Time"><el-time-picker v-model="scheduleForm.scheduleTime" value-format="HH:mm" format="HH:mm"/></el-form-item><el-form-item label="Notes"><el-input v-model="scheduleForm.remark" maxlength="255"/></el-form-item></el-form><template #footer><el-button @click="scheduleVisible=false">Cancel</el-button><el-button type="primary" :loading="saving" @click="submitSchedule">Save</el-button></template></el-dialog>
  </main>
</template>
<script setup>
import {ref,reactive,computed,watch,onMounted,onUnmounted,inject} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {useCurrentPatient} from '@/composables/useCurrentPatient'
import {useRoute,useRouter} from 'vue-router'
import {readPermissionCache} from '@/utils/authSession'
import {canAccessWorkspace} from '@/utils/workspaceAccess'
import * as api from '@/api/familyHealth'
import {localDateKey, replaceTarget} from '@/utils/familyHealth'
const {currentPatientId:patientId}=useCurrentPatient()
const tab=ref('today'),intakes=ref([]),events=ref([]),schedules=ref([]),target=reactive({}),loading=ref(false),saving=ref(false)
const eventVisible=ref(false),scheduleVisible=ref(false),eventForm=reactive({}),scheduleForm=reactive({}),eventRange=ref(null)
const summary=ref(null),summaryElement=ref(null),summaryLoading=ref(false),today=ref(localDateKey())
const route=useRoute()
const router=useRouter()
const permissionMenus=inject('userMenus',ref([]))
const tabNames=['today','timeline','schedule','target','summary']
const availableTabs=computed(()=>{
  permissionMenus.value
  const permissions=readPermissionCache()||{}
  return tabNames.filter(name=>canAccessWorkspace(`/family-health?tab=${name}`,permissions.menuPaths,permissions.roleCodes))
})
watch([()=>route.query.tab,availableTabs],([value,allowed])=>{
  const next=allowed.includes(value)?value:allowed[0]
  if(next)tab.value=next
},{immediate:true})
watch(tab,value=>{
  if(!availableTabs.value.includes(value) || route.query.tab===value)return
  router.push({path:route.path,query:{...route.query,tab:value}})
})
const activeIntakes=computed(()=>intakes.value.filter(x=>x.status!=='CANCELLED'))
const takenCount=computed(()=>activeIntakes.value.filter(x=>x.status==='TAKEN').length),missedCount=computed(()=>activeIntakes.value.filter(x=>x.status==='MISSED').length)
const todaySchedule=computed(()=>schedules.value.find(x=>x.scheduleDate===today.value))
let requestVersion=0, taskVersion=0, summaryVersion=0, timer
async function reload(){
  taskVersion++
  const id=patientId.value,version=++requestVersion
  if(!id)return
  loading.value=true
  try {
    const [a,b,c,d]=await Promise.all([api.getIntakes(id),api.getTimeline(id,eventRange.value?.[0],eventRange.value?.[1]),api.getDialysisSchedules(id),api.getHealthTarget(id)])
    if(id!==patientId.value || version!==requestVersion)return
    intakes.value=a.data||[];events.value=b.data||[];schedules.value=c.data||[];replaceTarget(target,d.data);today.value=localDateKey()
  } finally {if(version===requestVersion)loading.value=false}
}
watch(patientId,()=>{requestVersion++;taskVersion++;summaryVersion++;intakes.value=[];events.value=[];schedules.value=[];summary.value=null;summaryLoading.value=false;eventVisible.value=false;scheduleVisible.value=false;replaceTarget(target,null);loading.value=false;reload()},{immediate:true})
async function refreshTasks(){const id=patientId.value,v=++taskVersion;if(!id || saving.value)return;try{const a=await api.getIntakes(id);if(id===patientId.value && v===taskVersion){intakes.value=a.data||[];today.value=localDateKey()}}catch{/* request utility displays failures */}}
onMounted(()=>{timer=window.setInterval(refreshTasks,30000)})
onUnmounted(()=>{window.clearInterval(timer);requestVersion++;taskVersion++;summaryVersion++})
async function save(operation){if(saving.value)return;const id=patientId.value;if(!id)return;saving.value=true;try{await operation(id);ElMessage.success('Saved successfully.');if(id===patientId.value){summaryVersion++;summary.value=null;summaryLoading.value=false;await reload()}}finally{saving.value=false}}
async function doIntake(item,status){const id=patientId.value;let reason='';if(status==='SKIPPED'){const answer=await ElMessageBox.prompt('Briefly explain why this dose was skipped.','Skip this medication dose').catch(()=>null);if(!answer)return;reason=answer.value}if(id!==patientId.value)return;await save(()=>api.actionIntake(item.id,status,reason))}
function openEvent(row){Object.keys(eventForm).forEach(k=>delete eventForm[k]);Object.assign(eventForm,{id:null,eventDate:localDateKey(),eventTime:'',eventType:'SYMPTOM',title:'',summary:''},row||{});eventVisible.value=true}
async function submitEvent(){if(!eventForm.title?.trim() || !eventForm.eventDate){ElMessage.warning('Enter a date and title.');return}const data={...eventForm};await save(async id=>{await api.saveEvent({...data,patientId:id,eventTime:data.eventTime||''});eventVisible.value=false})}
async function removeEvent(row){await save(()=>api.deleteEvent(row.id))}
function openSchedule(row){Object.keys(scheduleForm).forEach(k=>delete scheduleForm[k]);Object.assign(scheduleForm,{id:null,scheduleDate:localDateKey(),scheduleTime:'',remark:'',status:'PLANNED'},row||{});scheduleVisible.value=true}
async function submitSchedule(){if(!scheduleForm.scheduleDate){ElMessage.warning('Select a date');return}const data={...scheduleForm};await save(async id=>{await api.saveDialysisSchedule({...data,patientId:id,scheduleTime:data.scheduleTime||''});scheduleVisible.value=false})}
async function changeSchedule(row,status){await save(id=>api.saveDialysisSchedule({...row,status,patientId:id}))}
async function submitTarget(){if(target.systolicMin>target.systolicMax || target.diastolicMin>target.diastolicMax){ElMessage.warning('A target minimum cannot exceed its maximum.');return}const data={...target};delete data.id;await save(id=>api.saveHealthTarget({...data,patientId:id}))}
async function loadSummary(){const id=patientId.value,v=++summaryVersion;if(!id)return;summaryLoading.value=true;try{const r=await api.getVisitSummary(id);if(id===patientId.value && v===summaryVersion)summary.value=r.data}finally{if(v===summaryVersion)summaryLoading.value=false}}
function printSummary(){if(!summaryElement.value)return;const popup=window.open('','_blank');if(!popup){ElMessage.warning('Allow pop-ups to print the summary.');return}popup.document.write('<!doctype html><html><head><meta charset="UTF-8"><title>Visit Summary</title><style>body{font-family:Arial,"Microsoft YaHei",sans-serif;margin:24px;line-height:1.6}table{width:100%;border-collapse:collapse}th,td{border:1px solid #ccc;text-align:left;padding:6px}thead{display:table-header-group}tr{break-inside:avoid}h3{break-after:avoid}</style></head><body>'+summaryElement.value.innerHTML+'</body></html>');popup.document.close();popup.focus();popup.setTimeout(()=>popup.print(),250)}
const intakeText=s=>({PENDING:'Due',TAKEN:'Taken',SNOOZED:'Snoozed',SKIPPED:'Skipped',MISSED:'Missed',CANCELLED:'Cancelled'}[s]||s)
const tagType=s=>({TAKEN:'success',MISSED:'danger',SKIPPED:'info',SNOOZED:'warning',CANCELLED:'info'}[s]||'primary')
const statusText=s=>({PLANNED:'Planned',COMPLETED:'Completed',CANCELLED:'Cancelled'}[s]||s)
const eventType=s=>({SYMPTOM:'Symptom',VISIT:'Visit',NOTE:'Note',MEASUREMENT:'Measurement',DIALYSIS:'Dialysis',INTAKE:'Medication intake',MEDICATION_LOG:'Medication record'}[s]||s)
</script>
<style scoped>.visit-summary table{width:100%;border-collapse:collapse}.visit-summary th,.visit-summary td{border:1px solid #dde8e5;padding:8px;text-align:left}.visit-summary{overflow-x:auto}.task-row small{color:#70847f}</style>
<style scoped>
.schedule-tip {
  margin-bottom: 16px;
}

.family-page {
  max-width: 1360px;
  margin: 0 auto;
  padding: 32px 34px 48px;
}

.hero,
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.hero {
  margin-bottom: 22px;
}

.hero-eyebrow {
  display: block;
  margin-bottom: 8px;
  color: var(--care-700, #267266);
  font-size: 12px;
  font-weight: 750;
  letter-spacing: .08em;
}

.hero h1 {
  margin: 0;
  color: var(--ink-950, #172421);
  font-size: 30px;
  font-weight: 770;
  letter-spacing: -.04em;
}

.hero p,
.task-row p {
  margin: 7px 0 0;
  color: var(--ink-500, #70847f);
  line-height: 1.55;
}

.hero-refresh {
  min-width: 102px;
}

.today-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.status-card,
.workspace {
  border: 1px solid var(--line, #dde8e5);
  border-radius: 17px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(16 45 42 / 3%), 0 8px 26px rgb(16 45 42 / 4%);
}

.status-card {
  min-height: 126px;
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
}

.status-card > span {
  color: var(--ink-500, #70847f);
  font-size: 12px;
  font-weight: 700;
}

.status-card strong {
  color: var(--ink-950, #172421);
  font-size: 29px;
  font-weight: 760;
  letter-spacing: -.035em;
}

.status-card small {
  color: var(--ink-500, #70847f);
  font-size: 12px;
}

.status-card:first-child {
  color: #fff;
  border-color: transparent;
  background: linear-gradient(145deg, #1c5a52, #31877a);
  box-shadow: 0 16px 32px rgb(28 90 82 / 16%);
}

.status-card:first-child > span,
.status-card:first-child small,
.status-card:first-child strong {
  color: inherit;
}

.status-card:first-child > span,
.status-card:first-child small {
  opacity: .76;
}

.workspace {
  margin-top: 20px;
  padding: 18px 22px 24px;
}

.workspace :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.toolbar {
  justify-content: flex-start;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.toolbar :deep(.el-button) {
  margin-left: 0;
}

.task-row {
  min-height: 74px;
  padding: 14px 6px;
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--line-soft, #edf2f1);
}

.task-row:last-child {
  border-bottom: 0;
}

.task-row b {
  color: var(--ink-950, #172421);
  font-size: 14px;
}

.task-row p {
  margin-top: 4px;
  font-size: 12px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.actions :deep(.el-button) {
  min-width: 70px;
  margin-left: 0;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 24px;
}

.target-form {
  max-width: 980px;
}

.target-form :deep(.el-form-item__content) {
  gap: 8px;
}

.target-form :deep(.el-input),
.target-form :deep(.el-input-number) {
  max-width: 220px;
}

.workspace :deep(.el-timeline) {
  padding: 8px 0 0 8px;
}

.workspace :deep(.el-timeline .el-card__body) {
  padding: 16px 18px;
}

.workspace :deep(.el-timeline p) {
  margin: 8px 0;
  color: var(--ink-700, #405651);
}

@media (max-width: 900px) {
  .today-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .status-card:last-child {
    grid-column: 1 / -1;
  }

  .task-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }
}

@media (max-width: 768px) {
  .family-page {
    padding: 19px 14px 28px;
  }

  .hero {
    align-items: flex-start;
  }

  .hero h1 {
    font-size: 25px;
  }

  .hero-refresh {
    min-width: auto;
  }

  .today-grid,
  .form-grid {
    grid-template-columns: 1fr;
  }

  .status-card:last-child {
    grid-column: auto;
  }

  .workspace {
    padding: 12px 14px 18px;
  }

  .workspace :deep(.el-tabs__nav-wrap) {
    overflow-x: auto;
  }

  .toolbar {
    display: grid;
    grid-template-columns: 1fr;
  }

  .toolbar :deep(.el-button) {
    width: 100%;
  }

  .task-row {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: start;
    padding: 15px 2px;
  }

  .actions {
    width: 100%;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .actions :deep(.el-button) {
    width: 100%;
    min-width: 0;
  }

  .target-form :deep(.el-form-item) {
    display: block;
  }

  .target-form :deep(.el-form-item__label) {
    display: block;
    width: auto !important;
    margin-bottom: 7px;
    text-align: left;
  }

  .target-form :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }

  .target-form :deep(.el-input),
  .target-form :deep(.el-input-number) {
    max-width: 100%;
  }
}

@media (max-width: 480px) {
  .hero {
    flex-direction: column;
  }

  .hero-refresh {
    width: 100%;
  }

  .task-row {
    grid-template-columns: 1fr;
  }

  .task-row > :deep(.el-tag) {
    justify-self: start;
  }

  .actions {
    grid-column: 1;
  }
}
</style>
