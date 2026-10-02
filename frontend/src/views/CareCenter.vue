<template>
  <section class="care-center" aria-labelledby="care-heading" v-loading="loading">
    <header class="care-header">
      <div class="care-header__copy">
        <span class="eyebrow">FAMILY CARE</span>
        <h1 id="care-heading">{{ mode === 'PATIENT' ? 'Your care, today' : 'Care together, today' }}</h1>
        <p class="care-context"><span>Current record</span><strong>{{ selectedPatientName || 'Select a patient above' }}</strong></p>
      </div>
      <picture class="care-header-art"><source media="(max-width:800px)" :srcset="careMomentsSmall"/><img :src="careMoments" alt="" width="120" height="80" decoding="async"/></picture>
    </header>
    <div class="care-controls">
      <el-radio-group v-model="mode" aria-label="Care role"><el-radio-button value="PATIENT">My care</el-radio-button><el-radio-button value="FAMILY">Family care</el-radio-button></el-radio-group>
      <div class="care-controls__utilities">
        <el-switch v-model="senior" active-text="Large text" aria-label="Large text"/>
        <details class="care-options">
          <summary>Care options</summary>
          <div class="care-toolbar">
            <el-button @click="joinVisible=true">Join shared care</el-button>
            <el-button @click="tab='backup'">Backup & restore</el-button>
            <el-button :loading="loading" @click="reload">Refresh</el-button>
          </div>
        </details>
      </div>
    </div>
    <section v-if="mode==='FAMILY'" class="family-cards" aria-label="Family members">
      <button v-for="p in home" :key="p.patient.id" type="button" :class="{selected:p.patient.id===patientId}" :aria-pressed="p.patient.id===patientId" @click="patientId=p.patient.id">
        <span class="family-card__heading"><strong>{{p.patient.name}}</strong><span v-if="p.patient.id===patientId" class="family-card__selected">Selected</span></span>
        <span>{{pendingCount(p)}} items need attention</span><small>{{nextAppointment(p)}}</small>
      </button>
    </section>
    <el-alert v-if="!patientId" title="Select a family member, or join shared care with an invitation code." :closable="false" type="info"/>
    <el-tabs v-model="tab" class="care-tabs">
      <el-tab-pane label="Today" name="today" :disabled="!patientId">
        <section class="quick-actions" aria-label="Record health"><el-button class="quick-actions__primary" type="primary" @click="quickVisible=true"><span>Record health<small>Blood pressure or glucose</small></span></el-button><el-button @click="open('SYMPTOM')">Record a symptom</el-button><el-button @click="open('QUESTION')">Add a visit question</el-button><el-button v-if="mode==='FAMILY'" @click="open('HANDOVER')">Leave a family handover</el-button></section>
        <div class="care-columns"><section class="care-card"><div class="care-card__heading"><h2>Today's medications</h2><span class="care-count">{{pendingMedicationCount}} pending</span></div><el-empty v-if="!activeIntakes.length" description="No medication tasks today"/><article v-for="i in activeIntakes" :key="i.id" class="care-task"><div><strong>{{i.scheduledAt?.slice(11,16)}} {{i.drugName}}</strong><p>{{i.dosage}}</p><small v-if="lastActor(i.id)">{{lastActor(i.id)}}</small><small v-if="i.status==='SNOOZED'">Remind again at {{i.snoozeUntil}}</small></div><el-tag>{{statusText(i.status)}}</el-tag><div v-if="['PENDING','MISSED','SNOOZED'].includes(i.status)" class="task-actions"><el-button type="success" :disabled="busy" @click="openIntake(i)">{{mode==='FAMILY'?'Record as taken':'I took this dose'}}</el-button><el-button :disabled="busy" @click="intakeAction(i,'SNOOZED')">Remind in 15 minutes</el-button><el-button :disabled="busy" @click="skipIntake(i)">Skip</el-button></div></article></section>
          <section class="care-card"><div class="care-card__heading"><h2>Appointments & care tasks</h2><span class="care-count">{{dueTasks.length}} open</span></div><el-empty v-if="!dueTasks.length" description="Nothing needs attention soon"/><article v-for="item in dueTasks" :key="item.id" class="care-task"><div><strong>{{item.title}}</strong><p>{{item.eventAt || 'Time not set'}}</p><small>Assigned to: {{memberName(item.assignedUserId)}} · Recorded by: {{item.actorName||'Family member'}}</small><p>{{item.details?.note || item.details?.preparation}}</p></div><div class="task-actions"><el-button :disabled="busy" type="primary" @click="action(item,'DONE')">Complete</el-button><el-button @click="open(item.kind,item)">View</el-button></div></article></section></div>
        <el-alert v-for="s in lowStocks" :key="s.id" :title="s.drugName + ' has ' + s.quantity + ' ' + s.unit + ' remaining' + (s.estimatedDays != null ? ' (about ' + s.estimatedDays + ' days). Please plan a refill.' : '. Please check the supply.')" type="warning" :closable="false"/>
      </el-tab-pane>
      <el-tab-pane label="Appointments" name="appointments" :disabled="!patientId">
        <div class="section-head"><h2>Appointments, follow-ups, and companions</h2><el-button type="primary" @click="open('APPOINTMENT')">Add appointment</el-button></div>
        <el-calendar v-model="calendarDate"><template #date-cell="{data}"><div class="calendar-cell"><span>{{data.day.slice(8)}}</span><button v-for="a in appointments.filter(x=>x.eventAt?.startsWith(data.day)&&x.status!=='CANCELLED')" :key="a.id" @click.stop="open('APPOINTMENT',a)">{{a.title}}</button></div></template></el-calendar>
        <el-table :data="appointments"><el-table-column label="Time" prop="eventAt" min-width="165"/><el-table-column label="Appointment" prop="title" min-width="150"/><el-table-column label="Hospital / Department" min-width="150"><template #default="{row}">{{row.details.hospital}} {{row.details.department}}</template></el-table-column><el-table-column label="Companion"><template #default="{row}">{{memberName(row.assignedUserId)}}</template></el-table-column><el-table-column label="Status"><template #default="{row}">{{statusText(row.status)}}</template></el-table-column><el-table-column label="Actions" min-width="190"><template #default="{row}"><el-button link @click="open('APPOINTMENT',row)">Edit / link report</el-button><el-button v-if="row.status==='OPEN'" link type="success" :disabled="busy" @click="action(row,'DONE')">Complete</el-button><el-button v-if="row.status!=='CANCELLED'" link :disabled="busy" @click="action(row,'CANCELLED')">Cancel</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="Prescriptions" name="orders" :disabled="!patientId">
        <div class="section-head"><div><h2>Follow the current prescription and preserve every change</h2><p>Add the medication first, then enter the clinician's directions and effective date.</p></div><div><el-button @click="$router.push('/medication')">Medication list</el-button><el-button type="primary" @click="open('ORDER')">Add prescription</el-button></div></div>
        <el-empty v-if="!orders.length" description="No prescription versions yet"/>
        <article v-for="order in orders" :key="order.id" class="care-card order-card"><div class="section-head"><h3>{{order.title}}</h3><el-tag>{{statusText(order.status)}}</el-tag></div><p>{{drugName(order.details.medicationId)}} · Starts {{order.details.startDate}}{{order.details.endDate?', ends '+order.details.endDate:''}} · Clinician: {{order.details.doctor||'Not provided'}}</p><p v-if="order.details.action!=='STOP'">{{order.details.doses?.map(d=>d.time+' '+d.quantity+order.details.unit).join('; ')}} · {{daysText(order.details.repeatDays)}}</p><p v-else>This medication was stopped.</p><p>{{order.details.instructions}}</p><div class="attachment-links"><a v-for="(a,i) in order.details.attachments||[]" :key="i" :href="a.dataUrl" :download="a.name">{{a.name}}</a></div><small>Recorded by {{order.actorName}} · Version #{{order.id}}</small><div class="task-actions"><el-button @click="changeOrder(order,'CHANGE')">Add revised version</el-button><el-button v-if="order.status==='ACTIVE'" @click="changeOrder(order,'STOP')">Record stop order</el-button><el-button v-if="order.status==='SCHEDULED'" :disabled="busy" @click="action(order,'CANCELLED')">Cancel scheduled version</el-button></div></article>
      </el-tab-pane>
      <el-tab-pane label="Medication stock" name="stock" :disabled="!patientId">
        <div class="section-head"><h2>Remaining medication</h2><el-button type="primary" @click="openStock()">Set or correct stock</el-button></div><p>Use the same unit as the prescription. Check-ins deduct the prescribed numeric dose; temporary doses can be entered when confirmed.</p>
        <el-table :data="context.stocks||[]"><el-table-column prop="drugName" label="Medication" min-width="150"/><el-table-column label="Remaining"><template #default="{row}">{{row.quantity}} {{row.unit}} <el-tag v-if="row.low" type="warning">Low stock</el-tag></template></el-table-column><el-table-column label="Estimated days"><template #default="{row}">{{row.estimatedDays==null?'No numeric regimen':row.estimatedDays+' days'}}</template></el-table-column><el-table-column label="Actions" min-width="180"><template #default="{row}"><el-button link @click="purchase(row)">Restock</el-button><el-button link @click="openStock(row)">Correct</el-button><el-button link @click="showStockHistory(row)">History</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="Symptoms" name="symptoms" :disabled="!patientId">
        <div class="section-head"><h2>Track when symptoms started and how they change</h2><el-button type="primary" @click="open('SYMPTOM')">Record symptom</el-button></div><el-select v-model="symptomFilter" clearable placeholder="Select a symptom to view its history"><el-option v-for="name in [...new Set(symptoms.map(s=>s.title))]" :key="name" :value="name"/></el-select>
        <article v-for="s in filteredSymptoms" :key="s.id" class="care-card"><div class="section-head"><h3>{{s.title}}</h3><span>{{s.eventAt}} · {{s.actorName}}</span></div><el-progress :percentage="Number(s.details.severity)*10" :format="()=>s.details.severity+'/10'"/><p>Duration: {{s.details.duration||'Not provided'}}; {{progressText(s.details.progress)}}; {{symptomChange(s)}}</p><p>{{s.details.context}}</p><p>{{s.details.response}}</p><el-button link @click="open('SYMPTOM',s)">Edit</el-button><el-button link @click="open('SYMPTOM',{...s,id:null,eventAt:null})">Record again</el-button><el-popconfirm title="Delete this symptom entry?" @confirm="removeItem(s)"><template #reference><el-button link type="danger">Delete</el-button></template></el-popconfirm></article><el-empty v-if="!filteredSymptoms.length" description="No symptom entries"/>
      </el-tab-pane>
      <el-tab-pane label="Questions" name="questions" :disabled="!patientId">
        <div class="section-head"><h2>Prepare questions before a visit and record answers afterward</h2><div><el-button @click="$router.push('/family-health')">Visit summary & print</el-button><el-button type="primary" @click="open('QUESTION')">Add question</el-button></div></div>
        <article v-for="q in questions" :key="q.id" class="care-card"><div class="section-head"><h3>{{q.title}}</h3><el-tag>{{statusText(q.status)}}</el-tag></div><p>{{q.details.description}}</p><p v-if="q.details.appointmentId">Appointment: {{appointments.find(a=>a.id===q.details.appointmentId)?.title||'Appointment deleted'}}</p><p>Clinician's answer: {{q.details.answer||'Not asked yet'}}</p><p>Follow-up: {{q.details.followUp||'None'}}</p><el-button @click="open('QUESTION',q)">Edit question / answer</el-button><el-button v-if="q.status==='OPEN'" :disabled="busy" @click="answer(q)">Mark answered</el-button><el-button v-if="q.details.followUp" @click="open('HANDOVER',{title:q.details.followUp,details:{note:q.details.answer},kind:'HANDOVER'})">Create care task</el-button><el-popconfirm title="Delete this question?" @confirm="removeItem(q)"><template #reference><el-button link>Delete</el-button></template></el-popconfirm></article><el-empty v-if="!questions.length" description="Keep questions here whenever they come to mind"/>
      </el-tab-pane>
      <el-tab-pane label="Family collaboration" name="family" :disabled="!patientId">
        <div class="section-head"><h2>Shared caregivers</h2><el-button v-if="canManage" type="primary" @click="invite">Generate invitation code</el-button></div><p v-if="inviteCode" class="invite-code">Invitation code (valid for 7 days and one use): <strong>{{inviteCode}}</strong><el-button @click="copyInvite">Copy</el-button></p>
        <el-table :data="context.members||[]"><el-table-column prop="name" label="Name"/><el-table-column prop="username" label="Account"/><el-table-column prop="relationName" label="Relationship"/><el-table-column label="Actions"><template #default="{row}"><el-popconfirm v-if="canManage&&row.userId!==context.patient?.userId" title="Remove this account from shared care?" @confirm="removeMember(row)"><template #reference><el-button link type="danger">Remove</el-button></template></el-popconfirm></template></el-table-column></el-table>
        <div class="section-head"><h2>Care handovers</h2><el-button @click="open('HANDOVER')">Add handover</el-button></div><article v-for="h in handovers" :key="h.id" class="care-card"><strong>{{h.title}}</strong><p>{{h.details.note}}</p><small>{{h.actorName}} assigned this to {{memberName(h.assignedUserId)}} · {{h.eventAt||'No due time'}} · {{statusText(h.status)}}</small><div class="task-actions"><el-button @click="open('HANDOVER',h)">Edit</el-button><el-button v-if="h.status==='OPEN'" :disabled="busy" @click="action(h,'DONE')">Complete</el-button></div></article>
        <h2>Recent activity</h2><el-table :data="activities.slice(0,50)"><el-table-column prop="eventAt" label="Time"/><el-table-column prop="actorName" label="Recorded by"/><el-table-column prop="title" label="Action"/></el-table>
      </el-tab-pane>
      <el-tab-pane label="Care settings" name="settings" :disabled="!patientId">
        <el-form label-position="top" style="max-width:650px"><h2>{{context.patient?.name}}'s care settings</h2><el-form-item label="Notify a caregiver about overdue tasks"><el-select v-model="profile.escalationUserId" clearable placeholder="No additional follow-up notification"><el-option v-for="m in context.members||[]" :key="m.userId" :value="m.userId" :label="m.name"/></el-select></el-form-item><el-form-item label="Minutes overdue before caregiver follow-up"><el-input-number v-model="profile.escalationMinutes" :min="15" :step="15"/></el-form-item><el-button type="primary" :loading="busy" @click="saveProfile">Save settings</el-button><el-button @click="$router.push('/settings/notifications')">Notification channels</el-button></el-form>
      </el-tab-pane>
      <el-tab-pane label="Backup & restore" name="backup"><FamilyBackupPanel @restored="reload"/></el-tab-pane>
    </el-tabs>
    <CareEntryDialog v-model="entryVisible" :kind="entryKind" :row="entryRow" :patient-id="patientId" :members="context.members" :medications="context.medications" :reports="context.reports" :appointments="appointments" @saved="reload"/>
    <el-dialog v-model="quickVisible" title="Record blood pressure / glucose" width="min(460px,94vw)"><el-form label-position="top"><el-form-item label="Systolic / diastolic (mmHg)"><el-input-number v-model="quick.systolicBp" :min="1"/> / <el-input-number v-model="quick.diastolicBp" :min="1"/></el-form-item><el-form-item label="Blood glucose (mmol/L, optional)"><el-input-number v-model="quick.bloodGlucose" :min="0.1" :precision="1"/></el-form-item><el-form-item label="Measurement period"><el-select v-model="quick.measurePeriod"><el-option label="Fasting" value="Fasting"/><el-option label="After meal" value="After Meal"/><el-option label="Random" value="Random"/></el-select></el-form-item><el-form-item label="Notes"><el-input v-model="quick.remark"/></el-form-item></el-form><template #footer><el-button :loading="busy" type="primary" @click="submitQuick">Save reading</el-button></template></el-dialog>
    <el-dialog v-model="intakeVisible" :title="mode==='FAMILY'?'Record a dose for a family member':'Confirm this dose'" width="min(460px,94vw)"><p>{{selectedIntake?.drugName}} · {{selectedIntake?.dosage}}</p><el-form label-position="top"><el-form-item label="Actual quantity (optional, in the inventory unit)"><el-input-number v-model="intakeQuantity" :min="0.001" :precision="3"/></el-form-item><p>If left blank, stock is deducted only when the prescribed dose and inventory use the same unit.</p></el-form><template #footer><el-button :loading="busy" type="success" @click="confirmIntake">Confirm taken</el-button></template></el-dialog>
    <el-dialog v-model="stockVisible" title="Set or correct inventory" width="min(480px,94vw)"><el-form label-position="top"><el-form-item label="Medication"><el-select v-model="stockForm.medicationId" :disabled="!!stockForm.id"><el-option v-for="m in context.medications||[]" :key="m.id" :value="m.id" :label="m.drugName"/></el-select></el-form-item><el-form-item label="Actual quantity remaining"><el-input-number v-model="stockForm.quantity" :min="0" :precision="3"/></el-form-item><el-form-item label="Unit (must match the prescription)"><el-input v-model="stockForm.unit"/></el-form-item><el-form-item label="Warn when this many days remain"><el-input-number v-model="stockForm.warningDays" :min="0"/></el-form-item><el-form-item label="Or warn below this quantity"><el-input-number v-model="stockForm.warningQuantity" :min="0"/></el-form-item></el-form><template #footer><el-button :loading="busy" type="primary" @click="submitStock">Save</el-button></template></el-dialog>
    <el-dialog v-model="historyVisible" title="Inventory history" width="min(800px,94vw)"><el-table :data="stockHistory"><el-table-column prop="created_at" label="Time"/><el-table-column prop="quantity" label="Quantity change"/><el-table-column prop="reason" label="Reason"/><el-table-column prop="actor_name" label="Recorded by"/></el-table></el-dialog>
    <el-dialog v-model="joinVisible" title="Join shared care" width="min(460px,94vw)"><p>Ask the owner of this family member's record for an invitation code.</p><el-input v-model="joinCode" placeholder="Paste invitation code"/><el-input v-model="joinRelation" placeholder="Your relationship to the patient, for example: Daughter" style="margin-top:14px"/><template #footer><el-button type="primary" :loading="busy" @click="join">Join</el-button></template></el-dialog>
  </section>
</template>
<script setup>
import {ref,reactive,computed,watch,onMounted,onUnmounted} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {useCurrentPatient} from '@/composables/useCurrentPatient'
import * as api from '@/api/care'
import CareEntryDialog from '@/components/CareEntryDialog.vue'
import FamilyBackupPanel from '@/components/FamilyBackupPanel.vue'
import {localDateKey} from '@/utils/familyHealth'
import careMoments from '@/assets/illustrations/care-moments.webp'
import careMomentsSmall from '@/assets/illustrations/care-moments-small.webp'
const {currentPatientId:patientId,setPatientList}=useCurrentPatient()
const mode=ref(localStorage.getItem('care-mode')||'PATIENT'),senior=ref(localStorage.getItem('care-senior')==='true'),tab=ref('today'),home=ref([]),context=ref({}),loading=ref(false),busy=ref(false)
const entryVisible=ref(false),entryKind=ref('APPOINTMENT'),entryRow=ref(null),calendarDate=ref(new Date()),symptomFilter=ref('')
const quickVisible=ref(false),quick=reactive({systolicBp:undefined,diastolicBp:undefined,bloodGlucose:undefined,measurePeriod:localStorage.getItem('care-measure-period')||'Fasting',remark:''})
const intakeVisible=ref(false),selectedIntake=ref(null),intakeQuantity=ref(undefined),stockVisible=ref(false),stockForm=reactive({}),historyVisible=ref(false),stockHistory=ref([])
const joinVisible=ref(false),joinCode=ref(''),joinRelation=ref('family caregiver'),inviteCode=ref(''),profile=reactive({escalationUserId:null,escalationMinutes:60})
const selectedPatientName=computed(()=>{
  if(!patientId.value)return ''
  return home.value.find(p=>p.patient.id===patientId.value)?.patient.name
    || (context.value.patient?.id===patientId.value ? context.value.patient.name : '') || ''
})
const pendingMedicationCount=computed(()=>(context.value.intakes||[]).filter(i=>['PENDING','MISSED','SNOOZED'].includes(i.status)).length)
const items=computed(()=>context.value.items||[]),byKind=kind=>computed(()=>items.value.filter(i=>i.kind===kind))
const appointments=byKind('APPOINTMENT'),orders=byKind('ORDER'),symptoms=byKind('SYMPTOM'),questions=byKind('QUESTION'),handovers=byKind('HANDOVER'),activities=byKind('ACTIVITY')
const activeIntakes=computed(()=>(context.value.intakes||[]).filter(i=>i.status!=='CANCELLED')),lowStocks=computed(()=>(context.value.stocks||[]).filter(s=>s.low))
const filteredSymptoms=computed(()=>symptoms.value.filter(s=>!symptomFilter.value||s.title===symptomFilter.value))
const dueTasks=computed(()=>items.value.filter(i=>['APPOINTMENT','HANDOVER'].includes(i.kind)&&i.status==='OPEN'&&(!i.eventAt||new Date(i.eventAt.replace(' ','T')).getTime()<Date.now()+7*86400000)))
const canManage=computed(()=>Number(localStorage.getItem('userId'))===context.value.patient?.userId||JSON.parse(localStorage.getItem('userRoleCodes')||'[]').includes('admin'))
watch(mode,v=>localStorage.setItem('care-mode',v));watch(senior,v=>{localStorage.setItem('care-senior',v);document.body.classList.toggle('care-senior',v)},{immediate:true})
let generation=0, patientEpoch=0, historyRequest=0, inviteRequest=0, timer
async function reload(){const v=++generation;loading.value=true;try{const r=await api.getCareHome();if(v!==generation)return;home.value=r.data||[];setPatientList(home.value.map(x=>({id:x.patient.id,patientName:x.patient.name})));if(!patientId.value&&home.value.length){patientId.value=home.value[0].patient.id;return}if(patientId.value){const id=patientId.value,c=await api.getCareContext(id);if(v!==generation||id!==patientId.value)return;context.value=c.data||{};const p=items.value.find(x=>x.kind==='PROFILE');Object.assign(profile,{escalationUserId:null,escalationMinutes:60},p?.details||{});}}finally{if(v===generation)loading.value=false}}
function resetQuick() {
  Object.assign(quick, { systolicBp: undefined, diastolicBp: undefined, bloodGlucose: undefined, remark: '' })
}
watch(patientId, () => {
  generation++; patientEpoch++; historyRequest++; inviteRequest++
  context.value = {}; entryRow.value = null; selectedIntake.value = null
  entryVisible.value = false; quickVisible.value = false; intakeVisible.value = false; stockVisible.value = false
  historyVisible.value = false; stockHistory.value = []; inviteCode.value = ''; symptomFilter.value = ''
  Object.assign(profile, { escalationUserId: null, escalationMinutes: 60 })
  resetQuick()
  reload()
}, { flush: 'sync' })
onMounted(()=>{reload();timer=setInterval(()=>{if(!busy.value&&!entryVisible.value&&!stockVisible.value&&!quickVisible.value&&!intakeVisible.value&&tab.value==='today')reload()},60000)})
onUnmounted(()=>{generation++;patientEpoch++;historyRequest++;inviteRequest++;clearInterval(timer)})
async function perform(operation){if(busy.value)return;busy.value=true;try{await operation();ElMessage.success('Saved successfully.');await reload()}finally{busy.value=false}}
function open(kind,row){if(!patientId.value)return;entryKind.value=kind;entryRow.value=row||null;entryVisible.value=true}
function changeOrder(row,action){const data=JSON.parse(JSON.stringify(row));delete data.id;data.details.action=action;data.details.startDate=localDateKey();data.details.endDate=null;data.title=`${drugName(data.details.medicationId)}${action==='STOP'?'stop medication':'medication adjustment'}`;open('ORDER',data)}
async function action(row,status){await perform(()=>api.careAction(row.id,status))}
async function removeItem(row){await perform(()=>api.deleteCareItem(row.id))}
async function answer(row){const r=await ElMessageBox.prompt("Record the clinician's answer",'Visit answer',{inputType:'textarea',inputValue:row.details.answer||''}).catch(()=>null);if(r)await perform(()=>api.careAction(row.id,'ANSWERED',r.value))}
async function submitQuick() {
  if (!patientId.value) return
  const epoch = patientEpoch, data = { ...quick, patientId: patientId.value }
  await perform(async () => {
    await api.quickVitals(data)
    if (epoch !== patientEpoch) return
    quickVisible.value = false
    localStorage.setItem('care-measure-period', data.measurePeriod)
    resetQuick()
  })
}
function openIntake(i){selectedIntake.value=i;intakeQuantity.value=undefined;intakeVisible.value=true}
async function confirmIntake(){await intakeAction(selectedIntake.value,'TAKEN','',intakeQuantity.value);intakeVisible.value=false}
async function intakeAction(i,status,reason='',quantity){await perform(()=>api.recordIntake(i.id,status,reason,quantity,mode.value==='FAMILY'?'FAMILY':'SELF'))}
async function skipIntake(i){const r=await ElMessageBox.prompt('Reason for skipping','Skip this dose').catch(()=>null);if(r)await intakeAction(i,'SKIPPED',r.value)}
function openStock(row){Object.assign(stockForm,{id:null,medicationId:null,quantity:0,unit:'tablet',warningDays:7,warningQuantity:0},row?{id:row.id,medicationId:row.medication_id,quantity:Number(row.quantity),unit:row.unit,warningDays:row.warning_days,warningQuantity:Number(row.warning_quantity)}:{});stockVisible.value=true}
async function submitStock(){await perform(async()=>{await api.saveStock({...stockForm,patientId:patientId.value});stockVisible.value=false})}
async function purchase(row){const pid=patientId.value,r=await ElMessageBox.prompt(`How many ${row.unit} should be added?`,row.drugName,{inputPattern:/^\d+(\.\d{1,3})?$/,inputErrorMessage:'Enter a valid number'}).catch(()=>null);if(r&&pid===patientId.value)await perform(()=>api.purchaseStock({patientId:pid,medicationId:row.medication_id,quantity:Number(r.value),reason:'Restock'}))}
async function showStockHistory(row) {
  if (!patientId.value) return
  const epoch = patientEpoch, request = ++historyRequest
  const result = await api.getStockHistory(patientId.value, row.medication_id)
  if (epoch !== patientEpoch || request !== historyRequest) return
  stockHistory.value = result.data || []
  historyVisible.value = true
}
async function invite() {
  if (!patientId.value) return
  const epoch = patientEpoch, request = ++inviteRequest
  const result = await api.createCareInvite(patientId.value)
  if (epoch !== patientEpoch || request !== inviteRequest) return
  inviteCode.value = result.data
}
async function copyInvite(){try{await navigator.clipboard.writeText(inviteCode.value);ElMessage.success('Invitation code copied.')}catch{ElMessage.info('Select and copy the invitation code manually.')}}
async function join(){await perform(async()=>{const r=await api.joinCare(joinCode.value.trim(),joinRelation.value);joinVisible.value=false;patientId.value=r.data;window.dispatchEvent(new Event('care-patients-changed'))})}
async function removeMember(row){await perform(()=>api.removeCareMember(patientId.value,row.userId))}
async function saveProfile(){await perform(()=>api.saveCareItem({patientId:patientId.value,kind:'PROFILE',title:'Care settings',details:{...profile}}))}
function memberName(id){return context.value.members?.find(m=>m.userId===id)?.name||'not specified'}
function drugName(id){return context.value.medications?.find(m=>m.id===id)?.drugName||'Medication'}
function lastActor(id){const a=context.value.actions?.find(x=>x.intake_id===id);return a?`${a.actor_name||'Family member'} ${a.recorded_for==='FAMILY'?'recorded for the patient':'recorded'}: ${statusText(a.status)}`:''}
function pendingCount(p){return(p.intakes||[]).filter(i=>['PENDING','MISSED','SNOOZED'].includes(i.status)).length+(p.items||[]).filter(i=>['APPOINTMENT','HANDOVER'].includes(i.kind)&&i.status==='OPEN').length}
function nextAppointment(p){const a=(p.items||[]).filter(i=>i.kind==='APPOINTMENT'&&i.status==='OPEN').sort((a,b)=>(a.eventAt||'').localeCompare(b.eventAt||''))[0];return a?`${a.eventAt?.slice(0,16)} ${a.title}`:'No upcoming appointment'}
function symptomChange(s){const previous=symptoms.value.filter(x=>x.title===s.title&&x.id!==s.id&&(x.eventAt||'')<(s.eventAt||'')).sort((a,b)=>(b.eventAt||'').localeCompare(a.eventAt||''))[0];if(!previous)return'First entry';const diff=Number(s.details.severity)-Number(previous.details.severity);return diff===0?'Same score as the previous entry':`${Math.abs(diff)} point${Math.abs(diff)===1?'':'s'} ${diff>0?'higher':'lower'} than the previous entry`}
const progressText=s=>({ONGOING:'Symptoms continue',IMPROVED:'Improved',RESOLVED:'Resolved'}[s]||'Not provided')
const statusText=s=>({OPEN:'Open',DONE:'Completed',CANCELLED:'Cancelled',ACTIVE:'Active',SCHEDULED:'Scheduled',STOPPED:'Stopped',ANSWERED:'Answered',RESOLVED:'Resolved',PENDING:'Due',MISSED:'Missed',SNOOZED:'Snoozed',TAKEN:'Taken',SKIPPED:'Skipped'}[s]||s)
const daysText=s=>String(s||'').split(',').map(x=>['','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'][Number(x)]).join(', ')
</script>
<style scoped>
.care-center { max-width: 1400px; margin: auto; padding: 24px 28px 32px; }
.care-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.care-header__copy { min-width: 0; }
.eyebrow { color: var(--care-700); font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.care-header h1 { margin: 5px 0 8px; font-size: 28px; line-height: 1.25; letter-spacing: -.025em; }
.care-context { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; margin: 0; color: var(--ink-500); font-size: 14px; }
.care-context strong { color: var(--ink-950); font-size: 16px; overflow-wrap: anywhere; }
.care-header-art { width: 120px; height: 80px; flex: 0 0 auto; }
.care-header-art img { display: block; width: 100%; height: 100%; object-fit: contain; }
.care-controls, .care-controls__utilities { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; }
.care-controls { justify-content: space-between; margin-bottom: 20px; }
.care-controls :deep(.el-radio-button__inner) { min-height: 42px; display: inline-flex; align-items: center; padding: 10px 18px; font-size: 14px; }
.care-options { position: relative; }
.care-options summary { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 0 10px; color: var(--care-700); font-size: 14px; font-weight: 600; cursor: pointer; border-radius: 8px; list-style: none; }
.care-options summary::-webkit-details-marker { display: none; }
.care-options summary::after { content: '+'; font-size: 18px; }
.care-options[open] summary::after { content: '−'; }
.care-options summary:hover { background: var(--care-50); }
.care-options summary:focus-visible { outline: 2px solid var(--care-700); outline-offset: 2px; }
.care-toolbar { position: absolute; right: 0; top: calc(100% + 4px); z-index: 20; width: max-content; max-width: min(300px, 85vw); display: grid; gap: 8px; padding: 12px; background: var(--paper); border: 1px solid var(--line-strong); border-radius: 12px; box-shadow: 0 8px 24px #20342c14; }
.care-toolbar :deep(.el-button) { margin: 0; }
.family-cards { display: grid; grid-template-columns: repeat(auto-fit,minmax(220px,1fr)); gap: 12px; margin: 0 0 20px; }
.family-cards button { min-width: 0; display: flex; flex-direction: column; gap: 8px; padding: 16px; text-align: left; font: inherit; color: var(--ink-700); background: var(--paper); border: 1px solid var(--line-strong); border-radius: 12px; }
.family-cards button.selected { border-color: var(--care-700); box-shadow: inset 0 0 0 1px var(--care-700); background: var(--care-50); }
.family-card__heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; width: 100%; }
.family-cards strong { color: var(--ink-950); font-size: 18px; overflow-wrap: anywhere; }
.family-card__selected { margin-left: auto; font-size: 12px; color: var(--care-700); font-weight: 700; }
.care-center small { color: var(--ink-500); font-size: 14px; line-height: 1.55; }
.care-tabs { min-width: 0; background: var(--paper); border: 1px solid var(--line); border-radius: 16px; padding: 0 20px 20px; }
.care-tabs :deep(.el-tabs__header) { margin-bottom: 0; }
.care-tabs :deep(.el-tabs__item) { height: 54px; padding-inline: 16px; font-size: 15px; }
.care-tabs :deep(.el-tabs__nav-next), .care-tabs :deep(.el-tabs__nav-prev) { line-height: 54px; }
.care-tabs :deep(.el-tabs__content) { padding-top: 20px; }
.quick-actions { display: flex; flex-wrap: wrap; gap: 10px; margin: 0 0 20px; }
.quick-actions :deep(.el-button) { flex: 1; min-width: 150px; min-height: 56px; height: auto; margin: 0; padding: 12px; white-space: normal; line-height: 1.4; }
.quick-actions :deep(.el-button > span) { white-space: normal; }
.quick-actions__primary small { display: block; color: inherit; font-size: 13px; font-weight: 400; }
.care-columns { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 16px; align-items: start; }
.care-card { min-width: 0; padding: 18px; border: 1px solid var(--line); border-radius: 12px; margin-bottom: 16px; background: var(--paper); }
.care-card__heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.care-card h2, .care-card h3 { margin: 0; font-size: 18px; line-height: 1.4; }
.care-count { padding: 4px 8px; border-radius: 6px; background: var(--care-50); color: var(--care-700); font-size: 13px; font-weight: 600; white-space: nowrap; }
.care-card p { margin: 6px 0; line-height: 1.65; }
.care-task { display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-start; padding: 16px 0; border-bottom: 1px solid var(--line-soft); }
.care-task:last-child { padding-bottom: 0; border-bottom: 0; }
.care-task > div:first-child { flex: 1; min-width: min(180px,100%); }
.care-task strong { font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
.care-task small { display: block; }
.task-actions, .attachment-links { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; }
.care-task > .task-actions { flex: 0 0 100%; margin-top: 0; }
.task-actions :deep(.el-button) { min-height: 44px; height: auto; margin: 0; padding: 10px 12px; white-space: normal; }
.task-actions :deep(.el-button > span) { white-space: normal; }
.section-head { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 20px; }
.calendar-cell button { display: block; border: 0; background: var(--care-50); color: var(--care-700); padding: 3px; text-align: left; width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; border-radius: 4px; margin-top: 4px; }
.invite-code { padding: 16px; background: var(--care-50); overflow-wrap: anywhere; }
.attachment-links { margin-block: 14px; }
.order-card small { display: block; }
@media (max-width: 1100px) { .care-columns { grid-template-columns: 1fr; } }
@media (max-width: 768px) {
  .care-center { padding: 18px 14px 24px; }
  .care-header { margin-bottom: 14px; gap: 10px; }
  .care-header h1 { font-size: 25px; }
  .care-header-art { width: 84px; height: 64px; }
  .care-controls { gap: 10px; margin-bottom: 16px; }
  .care-controls__utilities { gap: 12px; }
  .care-controls :deep(.el-radio-button__inner) { padding-inline: 14px; }
  .family-cards { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; }
  .family-cards button { padding: 12px; }
  .family-card__selected { margin-left: 0; }
  .care-tabs { padding: 0 12px 12px; }
  .care-tabs :deep(.el-tabs__item) { height: 48px; }
  .care-tabs :deep(.el-tabs__nav-next), .care-tabs :deep(.el-tabs__nav-prev) { line-height: 48px; }
  .care-tabs :deep(.el-tabs__content) { padding-top: 14px; }
  .quick-actions { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px; margin-bottom: 16px; }
  .quick-actions :deep(.el-button) { min-width: 0; width: 100%; min-height: 48px; }
  .quick-actions__primary { grid-column: 1 / -1; }
  .care-card { padding: 14px; }
  .care-card h2 { font-size: 17px; }
  .section-head { align-items: flex-start; flex-direction: column; }
  .care-tabs :deep(.el-calendar__body) { padding: 0; }
  .care-tabs :deep(.el-calendar-day) { height: 85px; padding: 4px; }
}
@media (max-width: 480px) {
  .care-header-art { display: none; }
  .care-controls__utilities { width: 100%; justify-content: space-between; }
  .family-cards { grid-template-columns: 1fr; }
}
</style>
