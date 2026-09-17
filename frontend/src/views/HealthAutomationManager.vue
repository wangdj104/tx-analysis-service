<template>
  <main class="automation-page">
    <el-alert v-if="!patientId" title="Select a family member in the top bar first." type="warning" :closable="false" show-icon/>
    <header><div><h2>AutomaticHealth Analytics</h2><p>to pointAutomaticsummarizehealthrecord, call AI analysis, SaveresultandthroughNotificationchannelsend. </p></div><el-button type="primary" :disabled="!patientId" @click="openCreate">AddAutomatictask</el-button></header>
    <section v-loading="loading" class="task-list">
      <el-empty v-if="!rows.length" description="stillnohas Automated Analysistask"/>
      <article v-for="row in rows" :key="row.id" class="task-card">
        <div class="task-main"><div><b>{{row.taskName}}</b><el-tag size="small" :type="row.enabled===1?'success':'info'">{{row.enabled===1?'runrowin':'already Disabled'}}</el-tag></div><p>{{cycleText(row)}} · most recent  {{row.analysisRangeDays}} days · {{itemsText(row.analysisItems)}}</p><small>down timesrun: {{row.nextRunAt||'—'}}　most recent result: {{statusText(row.lastRunStatus)}} {{row.lastRunAt||''}}</small><small v-if="row.lastError" class="error">{{row.lastError}}</small></div>
        <div class="task-actions"><el-button v-if="row.lastAnalysisContent" link @click="showResult(row)">Viewresult</el-button><el-button link :loading="runningId===row.id" @click="runNow(row)">immediatelyrun</el-button><el-button link type="primary" @click="edit(row)">Edit</el-button><el-popconfirm title="ConfirmDeletethisAutomatictask?" @confirm="remove(row)"><template #reference><el-button link type="danger">Delete</el-button></template></el-popconfirm></div>
      </article>
    </section>
    <el-dialog v-model="visible" :title="form.id?'EditAutomated Analysistask':'AddAutomated Analysistask'" width="min(680px,96vw)">
      <el-form :model="form" label-width="110px">
        <el-form-item label="taskName"><el-input v-model="form.taskName" maxlength="100"/></el-form-item>
        <el-form-item label="runWeek"><el-radio-group v-model="form.frequencyType"><el-radio-button value="DAILY">by days</el-radio-button><el-radio-button value="WEEKLY">by week</el-radio-button><el-radio-button value="MONTHLY">by Month</el-radio-button></el-radio-group></el-form-item>
        <el-form-item v-if="form.frequencyType==='DAILY'" label="intervaldayscount"><el-input-number v-model="form.intervalDays" :min="1" :max="30"/> days</el-form-item>
        <el-form-item v-if="form.frequencyType==='WEEKLY'" label="each week"><el-select v-model="form.dayOfWeek"><el-option v-for="(label,index) in weekdays" :key="index" :label="label" :value="index+1"/></el-select></el-form-item>
        <el-form-item v-if="form.frequencyType==='MONTHLY'" label="each Month"><el-input-number v-model="form.dayOfMonth" :min="1" :max="28"/> Day</el-form-item>
        <el-form-item label="Run Time"><el-time-picker v-model="form.runTime" value-format="HH:mm" format="HH:mm"/></el-form-item>
        <el-form-item label="analysisrange"><el-select v-model="form.analysisRangeDays"><el-option label="Last 7 Days" :value="7"/><el-option label="most recent 14days" :value="14"/><el-option label="Last 30 Days" :value="30"/><el-option label="most recent 90days" :value="90"/></el-select></el-form-item>
        <el-form-item label="Examinationcontent"><el-checkbox-group v-model="form.analysisItems"><el-checkbox v-for="item in itemOptions" :key="item.value" :value="item.value">{{item.label}}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="Notificationchannel"><el-select v-model="form.channelIds" multiple clearable placeholder="not selectthensendto Allalready Enabledchannel" style="width:100%"><el-option v-for="channel in channels" :key="channel.id" :label="channel.channelName||channel.channelType" :value="channel.id"/></el-select><small>stillnohas channel?Please first to “Notification Settings”AddDingTalk, WeCom or  Webhook. </small></el-form-item>
        <el-form-item label="Enabledtask"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">Cancel</el-button><el-button type="primary" :loading="saving" @click="submit">Save</el-button></template>
    </el-dialog>
    <el-dialog v-model="resultVisible" title="most recent onetimesAutomated Analysisresult" width="min(760px,96vw)"><pre class="analysis-result">{{resultContent}}</pre></el-dialog>
  </main>
</template>
<script setup>
import{onMounted,reactive,ref,watch}from'vue'
import{ElMessage}from'element-plus'
import{useCurrentPatient}from'@/composables/useCurrentPatient'
import{listNotificationChannels}from'@/api/notificationChannel'
import{deleteHealthAutomation,listHealthAutomations,runHealthAutomation,saveHealthAutomation}from'@/api/healthAutomation'
const{currentPatientId:patientId}=useCurrentPatient(),rows=ref([]),channels=ref([]),loading=ref(false),saving=ref(false),visible=ref(false),runningId=ref(null),resultVisible=ref(false),resultContent=ref('')
const weekdays=['weekone','weektwo','weekthree','weekfour','weekfive','weeksix','weekDay']
const itemOptions=[{value:'DIALYSIS',label:'Dialysis and Weight'},{value:'VITALS',label:'Blood Pressure & Glucose'},{value:'MEDICATION',label:'medicationrun'},{value:'NUTRITION',label:'Nutrition and fluid intake'},{value:'COMPLICATION',label:'complication and discomfort'}]
const form=reactive({id:null,taskName:'each weekHealth Analytics',frequencyType:'WEEKLY',intervalDays:1,dayOfWeek:1,dayOfMonth:1,runTime:'08:00',analysisRangeDays:30,analysisItems:itemOptions.map(x=>x.value),channelIds:[],enabled:1})
async function load(){loading.value=true;try{const[a,b]=await Promise.all([listHealthAutomations(),listNotificationChannels()]);rows.value=(a.data||[]).filter(x=>!patientId.value||Number(x.patientId)===Number(patientId.value));channels.value=(b.data||[]).filter(x=>x.enabled===1)}finally{loading.value=false}}
function reset(){Object.assign(form,{id:null,taskName:'each weekHealth Analytics',frequencyType:'WEEKLY',intervalDays:1,dayOfWeek:1,dayOfMonth:1,runTime:'08:00',analysisRangeDays:30,analysisItems:itemOptions.map(x=>x.value),channelIds:[],enabled:1})}
function openCreate(){reset();visible.value=true}
function edit(row){Object.assign(form,{...row,analysisItems:(row.analysisItems||'').split(',').filter(Boolean),channelIds:(row.notificationChannelIds||'').split(',').filter(Boolean).map(Number)});visible.value=true}
async function submit(){if(!form.taskName?.trim()){ElMessage.warning('Please fillwritetaskName');return}if(!form.analysisItems.length){ElMessage.warning('Please to fewselectoneitemExaminationcontent');return}saving.value=true;try{await saveHealthAutomation({...form,patientId:patientId.value,analysisItems:form.analysisItems.join(','),notificationChannelIds:form.channelIds.join(',')});ElMessage.success('AutomatictaskSaved successfully.');visible.value=false;await load()}finally{saving.value=false}}
async function runNow(row){runningId.value=row.id;try{await runHealthAutomation(row.id);ElMessage.success('analysiscomplete, resultSaved successfully.andtrysend');await load()}finally{runningId.value=null}}
async function remove(row){await deleteHealthAutomation(row.id);ElMessage.success('Deleted');await load()}
function showResult(row){resultContent.value=row.lastAnalysisContent||'Noneresult';resultVisible.value=true}
function cycleText(row){if(row.frequencyType==='DAILY')return`each  ${row.intervalDays} days ${row.runTime}`;if(row.frequencyType==='WEEKLY')return`each ${weekdays[(row.dayOfWeek||1)-1]} ${row.runTime}`;return`each Month ${row.dayOfMonth} Day ${row.runTime}`}
function itemsText(value){const set=(value||'').split(',');return itemOptions.filter(x=>set.includes(x.value)).map(x=>x.label).join(', ')}
function statusText(value){return({SUCCESS:'sendsuccessful',SUCCESS_NOTIFY_FAILED:'analysiscomplete, sendfailed',FAILED:'runfailed'}[value]||'stillnot run')}
watch(patientId,load);onMounted(load)
</script>
<style scoped>
.automation-page>header,.task-card{display:flex;align-items:center;justify-content:space-between;gap:18px}.automation-page h2{margin:0 0 6px}.automation-page p{margin:0;color:#64748b}.task-list{margin-top:18px}.task-card{padding:18px 20px;margin-bottom:12px;border:1px solid #dce7e4;border-radius:14px;background:#fff}.task-main{display:grid;gap:7px}.task-main b{margin-right:10px}.task-main small{color:#71817d}.task-main .error{color:#c2413b}.task-actions{white-space:nowrap}.el-checkbox-group{display:flex;flex-wrap:wrap;gap:4px 14px}.el-form-item small{display:block;color:#71817d;margin-top:6px}.analysis-result{white-space:pre-wrap;line-height:1.75;font:inherit;color:#233b35}@media(max-width:700px){.automation-page>header,.task-card{align-items:flex-start;flex-direction:column}.task-actions{width:100%}}
</style>
