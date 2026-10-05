<template>
  <main class="family-page" v-loading="loading">
    <header class="hero"><div><span class="hero-eyebrow">日常照护</span><h1>家庭健康工作区</h1><p>集中查看今日任务、已完成照护和需要关注的事项。</p></div><el-button @click="reload">刷新数据</el-button></header>
    <el-alert v-if="!patientId" title="请先在顶部选择家庭成员。" type="warning" :closable="false" show-icon />
    <template v-else>
      <el-alert v-if="['unavailable','denied'].includes(reloadState)" :title="reloadState==='denied'?'当前记录无访问权限。请检查授权后刷新。':'当前记录暂不可用。请刷新重试。'" type="warning" :closable="false" show-icon />
      <section v-if="reloadState==='ready'" class="today-grid">
        <article class="status-card"><span>今日用药</span><strong>{{ takenCount }}/{{ activeIntakes.length }}</strong><small>{{ missedCount ? `${missedCount} 次漏服` : '按今日计划服药并记录每次用药' }}</small></article>
        <article class="status-card"><span>透析排班</span><strong>{{ scheduleState==='ready' ? (todaySchedule ? statusText(todaySchedule.status) : '无日程') : scheduleStateText(scheduleState) }}</strong><small>{{ todaySchedule?.scheduleTime || '—' }}</small></article>
        <article class="status-card"><span>个人目标</span><strong>{{ target.id ? '已配置' : '未配置' }}</strong><small>个人目标可提高告警准确性</small></article>
      </section>
      <el-tabs v-model="tab" class="workspace">
        <el-tab-pane v-if="availableTabs.includes('today')" label="今日任务" name="today">
          <div class="toolbar"><el-button type="primary" @click="openEvent()">记录症状或事件</el-button><el-button @click="$router.push('/bp-self-monitor')">快速录入血压 / 血糖</el-button><el-button @click="$router.push('/medication?tab=remind')">用药提醒</el-button></div>
          <el-empty v-if="reloadState==='ready' && ['ready','disabled'].includes(scheduleState) && !intakes.length && !todaySchedule" description="今天没有需要关注的事项" />
          <div v-if="todaySchedule" class="task-row"><div><b>{{ todaySchedule.scheduleTime || '未设置时间' }} 透析</b><p>{{ todaySchedule.remark }}</p></div><el-tag>{{statusText(todaySchedule.status)}}</el-tag><div class="actions"><el-button @click="openSchedule(todaySchedule)">查看排班</el-button><el-button v-if="todaySchedule.status==='PLANNED'" :disabled="saving" @click="changeSchedule(todaySchedule,'COMPLETED')">标记完成</el-button></div></div>
          <div v-for="item in intakes" :key="item.id" class="task-row">
            <div><b>{{ item.scheduledAt?.slice(11,16) }} {{ item.drugName }}</b><p>{{ item.dosage || '遵医嘱服用' }}</p><small v-if="item.status==='SNOOZED'">已延后至 {{ item.snoozeUntil }}</small></div>
            <el-tag :type="tagType(item.status)">{{ intakeText(item.status) }}</el-tag>
            <div v-if="['PENDING','MISSED','SNOOZED'].includes(item.status)" class="actions"><el-button type="success" :disabled="saving" @click="doIntake(item,'TAKEN')">已服用</el-button><el-button :disabled="saving" @click="doIntake(item,'SNOOZED')">15 分钟后提醒</el-button><el-button type="danger" plain :disabled="saving" @click="doIntake(item,'SKIPPED')">跳过</el-button></div>
          </div>
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('timeline')" label="健康时间线" name="timeline">
          <div class="toolbar"><el-button type="primary" @click="openEvent()">新增健康事件</el-button><el-date-picker v-model="eventRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" @change="reload" /></div>
          <p>系统会自动汇总测量、透析和用药记录，最多展示最近 200 条事件；如需修改，请前往对应功能模块。</p>
          <el-timeline><el-timeline-item v-for="e in localizedEvents" :key="`${e.sourceType || 'MANUAL'}-${e.sourceId ?? e.id}`" :timestamp="timelineTimestamp(e)" placement="top"><el-card><b>{{e.title}}</b><p>{{e.summary || e.remark || '—'}}</p><el-tag size="small">{{eventType(e.eventType)}}</el-tag><router-link v-if="safePlanLink(e)" :to="safePlanLink(e)" class="care-plan-event-link">查看计划和受控历史</router-link><span v-if="!e.sourceType || e.sourceType==='MANUAL'"><el-button link type="primary" @click="openEvent(e)">编辑</el-button><el-popconfirm title="确认删除该事件吗？" @confirm="removeEvent(e)"><template #reference><el-button link type="danger" :disabled="saving">删除</el-button></template></el-popconfirm></span></el-card></el-timeline-item></el-timeline><el-empty v-if="reloadState==='ready' && !events.length" description="暂无健康事件" />
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('schedule')" label="透析排班" name="schedule">
          <el-alert v-if="scheduleState!=='ready'" :title="scheduleStateText(scheduleState)" type="warning" :closable="false" show-icon />
          <el-alert class="schedule-tip" title="排班仅来自手动添加的日期，或在临床工作台中明确确认的周期计划；你可以在这里编辑或取消。" type="info" :closable="false" show-icon />
          <div class="toolbar"><el-button type="primary" :disabled="scheduleState!=='ready'" @click="openSchedule()">新增排班</el-button></div>
          <el-table :data="schedules"><el-table-column prop="scheduleDate" label="日期" min-width="110"/><el-table-column prop="scheduleTime" label="时间"/><el-table-column label="状态"><template #default="{row}">{{statusText(row.status)}}<small v-if="row.completedRecordId">（已关联记录）</small></template></el-table-column><el-table-column prop="remark" label="备注"/><el-table-column label="操作" min-width="200"><template #default="{row}"><el-button link @click="openSchedule(row)">编辑</el-button><el-button v-if="row.status==='PLANNED'" link type="success" :disabled="saving" @click="changeSchedule(row,'COMPLETED')">完成</el-button><el-button v-if="row.status!=='CANCELLED'" link type="danger" :disabled="saving" @click="changeSchedule(row,'CANCELLED')">取消排班</el-button><el-button v-else link type="primary" :disabled="saving" @click="changeSchedule(row,'PLANNED')">恢复</el-button></template></el-table-column></el-table>
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('target')" label="个人目标" name="target">
          <el-form :model="target" label-width="130px" class="target-form"><el-divider>血压目标（mmHg）</el-divider><div class="form-grid"><el-form-item label="收缩压"><el-input-number v-model="target.systolicMin" :min="1"/> 至 <el-input-number v-model="target.systolicMax" :min="1"/></el-form-item><el-form-item label="舒张压"><el-input-number v-model="target.diastolicMin" :min="1"/> 至 <el-input-number v-model="target.diastolicMax" :min="1"/></el-form-item><el-form-item label="目标体重"><el-input-number v-model="target.targetWeight" :precision="2" :min="1"/> kg</el-form-item><el-form-item label="紧急联系人"><el-input v-model="target.emergencyContact"/></el-form-item><el-form-item label="联系电话"><el-input v-model="target.emergencyPhone"/></el-form-item><el-form-item label="医院"><el-input v-model="target.hospitalName"/></el-form-item><el-form-item label="主管医生"><el-input v-model="target.doctorName"/></el-form-item></div><el-button type="primary" :loading="saving" @click="submitTarget">保存目标</el-button></el-form>
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('summary')" label="就诊摘要" name="summary">
          <div class="toolbar"><el-button :loading="summaryLoading" @click="loadSummary">生成 / 刷新摘要</el-button><el-button :disabled="!summary" @click="printSummary">打印 / 保存 PDF</el-button></div>
          <div ref="summaryElement"><article v-if="summary" class="visit-summary">
            <h2>{{summary.patient?.name}} · 就诊摘要</h2><p>生成时间：{{summary.generatedAt}}</p><p>既往病史：{{summary.patient?.medicalHistory || '未填写'}}</p>
            <p>医院：{{summary.target?.hospitalName || '未填写'}}；主管医生：{{summary.target?.doctorName || '未填写'}}</p><p>紧急联系人：{{summary.target?.emergencyContact || summary.patient?.emergencyContact || '未填写'}} {{summary.target?.emergencyPhone || summary.patient?.emergencyPhone}}</p>
            <p>血压目标：{{summary.target?.systolicMin || '—'}}–{{summary.target?.systolicMax || '—'}} / {{summary.target?.diastolicMin || '—'}}–{{summary.target?.diastolicMax || '—'}} mmHg</p>
            <h3>当前用药</h3><table><thead><tr><th>药品</th><th>剂量说明</th></tr></thead><tbody><tr v-for="m in summary.medications" :key="m.id"><td>{{m.drugName}}</td><td>{{m.defaultDosage || '遵医嘱服用'}}</td></tr></tbody></table><p v-if="!summary.medications?.length">暂无用药记录</p>
            <h3>近期测量（最多 30 条）</h3><table><thead><tr><th>时间</th><th>血压（mmHg）</th><th>血糖</th></tr></thead><tbody><tr v-for="r in summary.recentMeasurements" :key="r.id"><td>{{r.recordDate}} {{r.recordTime}}</td><td>{{r.systolicBp ?? '—'}} / {{r.diastolicBp ?? '—'}}</td><td>{{r.bloodGlucose ?? '—'}} {{r.bgUnit}}</td></tr></tbody></table>
            <h3>未解决告警（最多 30 条）</h3><ul><li v-for="a in summary.unresolvedAlerts" :key="a.id">{{a.triggeredAt}} {{a.alertTitle}}：{{a.triggeredValue}} {{a.handlingNote}}</li></ul><p v-if="!summary.unresolvedAlerts?.length">暂无未解决告警</p>
            <h3>准备咨询医生的问题</h3><ul><li v-for="q in summary.questions||[]" :key="q.id"><b>{{q.title}}</b><p>{{q.details?.description}}</p><p v-if="q.details?.answer">记录的答复：{{q.details.answer}}</p><p v-if="q.details?.followUp">后续事项：{{q.details.followUp}}</p></li></ul>
            <h3>近期症状跟踪</h3><ul><li v-for="s in summary.careSymptoms||[]" :key="s.id">{{s.eventAt}} {{s.title}}，自评分 {{s.details?.severity}}/10，持续 {{s.details?.duration || '未填写'}}；{{s.details?.response}}</li></ul>
            <h3>近期健康事件（最多 30 条）</h3><ul><li v-for="e in localizedSummaryEvents" :key="`${e.sourceType}-${e.id}`">{{e.eventDate}} {{e.title}}：{{e.summary}}</li></ul><p>{{summary.disclaimer}}</p>
          </article><el-empty v-else description="生成摘要，为下次就诊准备近期记录"/>
          <ExecutionReportPanel ref="reportPanel" :patient-id="patientId"/></div>
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog v-model="eventVisible" :title="eventForm.id?'编辑健康事件':'记录健康事件'" width="min(520px, 94vw)"><el-form :model="eventForm" label-width="90px"><el-form-item label="日期" required><el-date-picker v-model="eventForm.eventDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="时间"><el-time-picker v-model="eventForm.eventTime" value-format="HH:mm" format="HH:mm"/></el-form-item><el-form-item label="类型"><el-select v-model="eventForm.eventType"><el-option label="症状" value="SYMPTOM"/><el-option label="就诊" value="VISIT"/><el-option label="备注" value="NOTE"/></el-select></el-form-item><el-form-item label="标题" required><el-input v-model="eventForm.title" maxlength="120"/></el-form-item><el-form-item label="说明"><el-input v-model="eventForm.summary" type="textarea" maxlength="500" show-word-limit/></el-form-item></el-form><template #footer><el-button @click="eventVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitEvent">保存</el-button></template></el-dialog>
    <el-dialog v-model="scheduleVisible" :title="scheduleForm.id?'编辑透析排班':'新增透析排班'" width="min(480px, 94vw)"><el-form :model="scheduleForm" label-width="80px"><el-form-item label="日期" required><el-date-picker v-model="scheduleForm.scheduleDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="时间"><el-time-picker v-model="scheduleForm.scheduleTime" value-format="HH:mm" format="HH:mm"/></el-form-item><el-form-item label="备注"><el-input v-model="scheduleForm.remark" maxlength="255"/></el-form-item></el-form><template #footer><el-button @click="scheduleVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitSchedule">保存</el-button></template></el-dialog>
  </main>
</template>
<script setup>
import {ref,reactive,computed,watch,onMounted,onUnmounted,inject,nextTick} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {useCurrentPatient} from '@/composables/useCurrentPatient'
import {useRoute,useRouter} from 'vue-router'
import ExecutionReportPanel from '@/components/care-plan/ExecutionReportPanel.vue'
import {captureAuthSession,isAuthSessionCurrent,AUTH_STORAGE_KEYS,readPermissionCache} from '@/utils/authSession'
import {canAccessWorkspace} from '@/utils/workspaceAccess'
import * as api from '@/api/familyHealth'
import {localDateKey, replaceTarget} from '@/utils/familyHealth'
import {formatPlanTime} from '@/utils/carePlanTime'
import {localizeHealthTimelineEntry} from '@/utils/timelineText'
const {currentPatientId:patientId}=useCurrentPatient()
const tab=ref('today'),intakes=ref([]),events=ref([]),schedules=ref([]),target=reactive({}),loading=ref(false),saving=ref(false)
const eventVisible=ref(false),scheduleVisible=ref(false),eventForm=reactive({}),scheduleForm=reactive({}),eventRange=ref(null)
const reportPanel=ref(null)
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
const localizedEvents=computed(()=>events.value.map(localizeHealthTimelineEntry))
const localizedSummaryEvents=computed(()=>(summary.value?.recentEvents||[]).map(localizeHealthTimelineEntry))
let requestVersion=0, taskVersion=0, summaryVersion=0, timer
let summaryController=null, printPopup=null
let disposed=false, taskPending=null
const reloadState=ref('idle'),scheduleState=ref('idle')
function clearReload(){
  requestVersion++;taskVersion++;taskPending=null
  intakes.value=[];events.value=[];schedules.value=[];replaceTarget(target,null)
  reloadState.value='idle';scheduleState.value='idle';loading.value=false
  eventVisible.value=false;scheduleVisible.value=false
}
function invalidateContext(){clearReload();invalidateSummary()}
const readFailure=error=>[401,403].includes(Number(error?.response?.status||error?.code))?'denied':'unavailable'
async function loadOptionalSchedules(id,owns){
  if(!availableTabs.value.includes('schedule'))return {state:'denied',data:[]}
  try{
    const result=await api.getSpecialtyMenuScope(id)
    if(!owns())return {state:'unavailable',data:[]}
    const scope=result.data
    if(Number(scope?.patientId)!==Number(id)||!Array.isArray(scope?.allowedPaths))return {state:'unavailable',data:[]}
    if(!scope.allowedPaths.includes('/family-health?tab=schedule'))return {state:'disabled',data:[]}
    const schedules=await api.getDialysisSchedules(id)
    return {state:'ready',data:schedules.data||[]}
  }catch(error){return {state:readFailure(error),data:[]}}
}
async function reload(){
  const id=patientId.value
  if(disposed || !id)return
  clearReload()
  const version=++requestVersion,intakeVersion=++taskVersion,auth=captureAuthSession()
  let active=true
  const owns=()=>active&&!disposed&&id===patientId.value&&version===requestVersion&&isAuthSessionCurrent(auth)
  loading.value=true;reloadState.value='loading';scheduleState.value='loading'
  try {
    const [a,b,c,d]=await Promise.all([api.getIntakes(id),api.getTimeline(id,eventRange.value?.[0],eventRange.value?.[1]),loadOptionalSchedules(id,owns),api.getHealthTarget(id)])
    if(!owns())return
    // Full reload and task-only refresh share ownership of the intake list.
    if(intakeVersion===taskVersion){intakes.value=a.data||[];today.value=localDateKey()}
    events.value=b.data||[];schedules.value=c.data;scheduleState.value=c.state;replaceTarget(target,d.data)
    reloadState.value='ready'
  }catch(error){
    if(owns()){intakes.value=[];events.value=[];schedules.value=[];replaceTarget(target,null);reloadState.value=readFailure(error);scheduleState.value='unavailable'}
  } finally {active=false;if(!disposed && version===requestVersion)loading.value=false}
}
watch(patientId,()=>{invalidateContext();reload()},{immediate:true,flush:'sync'})
async function refreshTasks(){
  const id=patientId.value
  // Skip redundant background reads before advancing result ownership.
  if(disposed || !id || saving.value || loading.value || (taskPending?.id===id && taskPending.version===taskVersion))return
  const pending={id,version:++taskVersion}
  taskPending=pending
  try{
    const a=await api.getIntakes(id)
    if(!disposed && id===patientId.value && pending.version===taskVersion){intakes.value=a.data||[];today.value=localDateKey()}
  }catch{/* request utility displays failures */}
  finally{if(taskPending===pending)taskPending=null}
}
onMounted(()=>{timer=window.setInterval(refreshTasks,30000)})
onUnmounted(()=>{disposed=true;taskPending=null;window.clearInterval(timer);requestVersion++;taskVersion++;invalidateSummary();window.removeEventListener('auth-session-cleared',invalidateContext);window.removeEventListener('storage',summaryStorageChanged)})
async function save(operation){if(saving.value)return;const id=patientId.value;if(!id)return;saving.value=true;try{await operation(id);ElMessage.success('保存成功。');if(id===patientId.value){summaryVersion++;summary.value=null;summaryLoading.value=false;await reload()}}finally{saving.value=false}}
async function doIntake(item,status){const id=patientId.value;let reason='';if(status==='SKIPPED'){const answer=await ElMessageBox.prompt('请简要说明跳过本次服药的原因。','跳过本次服药').catch(()=>null);if(!answer)return;reason=answer.value}if(id!==patientId.value)return;await save(()=>api.actionIntake(item.id,status,reason))}
function openEvent(row){Object.keys(eventForm).forEach(k=>delete eventForm[k]);Object.assign(eventForm,{id:null,eventDate:localDateKey(),eventTime:'',eventType:'SYMPTOM',title:'',summary:''},row||{});eventVisible.value=true}
async function submitEvent(){if(!eventForm.title?.trim() || !eventForm.eventDate){ElMessage.warning('请输入日期和标题。');return}const data={...eventForm};await save(async id=>{await api.saveEvent({...data,patientId:id,eventTime:data.eventTime||''});eventVisible.value=false})}
async function removeEvent(row){await save(()=>api.deleteEvent(row.id))}
function openSchedule(row){Object.keys(scheduleForm).forEach(k=>delete scheduleForm[k]);Object.assign(scheduleForm,{id:null,scheduleDate:localDateKey(),scheduleTime:'',remark:'',status:'PLANNED'},row||{});scheduleVisible.value=true}
async function submitSchedule(){if(!scheduleForm.scheduleDate){ElMessage.warning('请选择日期');return}const data={...scheduleForm};await save(async id=>{await api.saveDialysisSchedule({...data,patientId:id,scheduleTime:data.scheduleTime||''});scheduleVisible.value=false})}
async function changeSchedule(row,status){await save(id=>api.saveDialysisSchedule({...row,status,patientId:id}))}
async function submitTarget(){if(target.systolicMin>target.systolicMax || target.diastolicMin>target.diastolicMax){ElMessage.warning('目标最小值不能大于最大值。');return}const data={...target};delete data.id;await save(id=>api.saveHealthTarget({...data,patientId:id}))}
function invalidateSummary(){
  summaryVersion++;summaryController?.abort();summaryController=null
  summary.value=null;summaryLoading.value=false;reportPanel.value?.clear()
  closePrintPopup()
}
function closePrintPopup(){if(printPopup){try{printPopup.document.body?.replaceChildren();printPopup.close()}catch{}printPopup=null}}
// A replacement report must clear a prepared popup immediately. Do not clear the
// replacement client here: the intentional paired refresh advances this too.
watch(()=>reportPanel.value?.generation,closePrintPopup,{flush:'sync'})
function summaryStorageChanged(event){if(event.key==null||AUTH_STORAGE_KEYS.includes(event.key))invalidateContext()}
watch(()=>JSON.stringify(reportPanel.value?.context),invalidateSummary,{flush:'sync'})
window.addEventListener('auth-session-cleared',invalidateContext)
window.addEventListener('storage',summaryStorageChanged)
async function refreshVisit(forPrint=false){
  if(summaryLoading.value||!patientId.value||!reportPanel.value)return false
  invalidateSummary()
  const id=patientId.value,version=summaryVersion,auth=captureAuthSession(),actor=localStorage.getItem('userId'),panel=reportPanel.value
  const reportContext=JSON.stringify(panel.context),controller=new AbortController();summaryController=controller
  let reportGeneration
  const owns=()=>panel.generation===reportGeneration&&!disposed&&version===summaryVersion&&id===patientId.value&&isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId')&&panel===reportPanel.value&&reportContext===JSON.stringify(panel.context)
  summaryLoading.value=true
  try{
    const legacyRequest=api.getVisitSummary(id,{expectedAuth:{...auth,actorId:actor},signal:controller.signal})
    const projectionRequest=panel.refresh()
    reportGeneration=panel.generation
    const [old,projection]=await Promise.all([legacyRequest,projectionRequest])
    if(!owns()||old.data?.patient?.id!==id)return false
    // A freshly denied/disabled optional section is shown as unavailable, never as zero.
    const unavailable=projection.status==='failed'&&['ACCESS_DENIED','FEATURE_DISABLED'].includes(panel.state.error?.errorCode)
    if(projection.status!=='succeeded'&&!unavailable)return false
    if(!unavailable&&(panel.state.report?.patient?.id!==id||panel.state.report?.scope?.planId!=null))return false
    summary.value=old.data
    if(!owns())return false
    await nextTick()
    if(!owns())return false
    if(forPrint){
      const markup=summaryElement.value?.innerHTML
      if(!markup||!owns())return false
      const popup=window.open('','_blank')
      if(!popup){ElMessage.warning('请允许弹出窗口以打印摘要。');return false}
      printPopup=popup
      if(!owns()){invalidateSummary();return false}
      popup.document.write('<!doctype html><html><head><meta charset="UTF-8"><title>Visit Summary</title><style>body{font-family:Arial,"Microsoft YaHei",sans-serif;margin:24px;line-height:1.6;overflow-wrap:anywhere}.report-controls{display:none}table{width:100%;border-collapse:collapse}th,td{border:1px solid #ccc;text-align:left;padding:6px}thead{display:table-header-group}tr{break-inside:avoid}h2,h3,h4,h5{break-after:avoid}.original{white-space:pre-wrap}</style></head><body>'+markup+'</body></html>')
      popup.document.close();popup.focus()
      popup.setTimeout(()=>{if(!owns()||printPopup!==popup){try{popup.document.body?.replaceChildren();popup.close()}catch{}return}popup.print()},250)
    }
    return true
  }catch{if(owns()){summary.value=null;panel.clear();ElMessage.warning('无法生成当前已授权摘要，请重新尝试。')}return false}
  finally{if(version===summaryVersion){
    if(panel.generation!==reportGeneration){summary.value=null;summaryLoading.value=false;if(summaryController===controller)summaryController=null;closePrintPopup()}
    else if(!owns())invalidateSummary()
    else{summaryLoading.value=false;summaryController=null}
  }}
}
async function loadSummary(){return refreshVisit(false)}
async function printSummary(){return refreshVisit(true)}
const intakeText=s=>({PENDING:'待服用',TAKEN:'已服用',SNOOZED:'已延后',SKIPPED:'已跳过',MISSED:'已漏服',CANCELLED:'已取消'}[s]||s)
const tagType=s=>({TAKEN:'success',MISSED:'danger',SKIPPED:'info',SNOOZED:'warning',CANCELLED:'info'}[s]||'primary')
const scheduleStateText=s=>({loading:'加载中',disabled:'透析护理未启用',denied:'透析日程无访问权限',unavailable:'透析日程暂不可用',idle:'尚未加载'}[s]||'')
const statusText=s=>({PLANNED:'已计划',COMPLETED:'已完成',CANCELLED:'已取消'}[s]||s)
function timelineTimestamp(event){
  if(event.sourceType!=='CARE_PLAN_EVENT')return `${event.eventDate} ${event.eventTime||''}`
  try{return formatPlanTime(`${event.eventDate}T${event.eventTime||''}`,'zh-CN')}catch{return '照护计划事件时间无效或缺失'}
}
function safePlanLink(event){return event.sourceType==='CARE_PLAN_EVENT'&&Number.isSafeInteger(event.carePlanId)&&event.carePlanId>0?`/care-plans/${event.carePlanId}`:''}
const eventType=s=>({SYMPTOM:'症状',VISIT:'就诊',NOTE:'备注',MEASUREMENT:'测量',DIALYSIS:'透析',INTAKE:'服药',MEDICATION_LOG:'用药记录'}[s]||s)
</script>
<style scoped>
.visit-summary table{width:100%;border-collapse:collapse}.visit-summary th,.visit-summary td{border:1px solid var(--line);padding:8px;text-align:left}.visit-summary{overflow-x:auto}.task-row small{color:var(--ink-500)}</style>
<style scoped>
.care-plan-event-link{display:inline-flex;align-items:center;min-height:44px;margin:8px;padding:0 10px;color:var(--care-700)}.care-plan-event-link:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}
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
  background: var(--paper);
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
  color: var(--on-accent);
  border-color: transparent;
  background: linear-gradient(145deg, var(--care-800), var(--care-600));
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
  min-height: 44px;
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
