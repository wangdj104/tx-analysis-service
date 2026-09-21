<template>
  <main class="family-page" v-loading="loading">
    <header class="hero"><div><span class="hero-eyebrow">日常照护</span><h1>家庭健康工作区</h1><p>集中查看今日任务、已完成照护和需要关注的事项。</p></div><el-button @click="reload">刷新数据</el-button></header>
    <el-alert v-if="!patientId" title="请先在顶部选择家庭成员。" type="warning" :closable="false" show-icon />
    <template v-else>
      <section class="today-grid">
        <article class="status-card"><span>今日用药</span><strong>{{ takenCount }}/{{ activeIntakes.length }}</strong><small>{{ missedCount ? `${missedCount} 次漏服` : '按今日计划服药并记录每次用药' }}</small></article>
        <article class="status-card"><span>透析排班</span><strong>{{ todaySchedule ? statusText(todaySchedule.status) : '暂无排班' }}</strong><small>{{ todaySchedule?.scheduleTime || '—' }}</small></article>
        <article class="status-card"><span>个人目标</span><strong>{{ target.id ? '已配置' : '未配置' }}</strong><small>个人目标可提高告警准确性</small></article>
      </section>
      <el-tabs v-model="tab" class="workspace">
        <el-tab-pane v-if="availableTabs.includes('today')" label="今日任务" name="today">
          <div class="toolbar"><el-button type="primary" @click="openEvent()">记录症状或事件</el-button><el-button @click="$router.push('/bp-self-monitor')">快速录入血压 / 血糖</el-button><el-button @click="$router.push('/medication?tab=remind')">用药提醒</el-button></div>
          <el-empty v-if="!intakes.length && !todaySchedule" description="今天没有需要关注的事项" />
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
          <el-timeline><el-timeline-item v-for="e in events" :key="`${e.sourceType || 'MANUAL'}-${e.id}`" :timestamp="`${e.eventDate} ${e.eventTime||''}`" placement="top"><el-card><b>{{e.title}}</b><p>{{e.summary || e.remark || '—'}}</p><el-tag size="small">{{eventType(e.eventType)}}</el-tag><span v-if="!e.sourceType || e.sourceType==='MANUAL'"><el-button link type="primary" @click="openEvent(e)">编辑</el-button><el-popconfirm title="确认删除该事件吗？" @confirm="removeEvent(e)"><template #reference><el-button link type="danger" :disabled="saving">删除</el-button></template></el-popconfirm></span></el-card></el-timeline-item></el-timeline><el-empty v-if="!events.length" description="暂无健康事件" />
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('schedule')" label="透析排班" name="schedule">
          <el-alert class="schedule-tip" title="排班仅来自手动添加的日期，或在临床工作台中明确确认的周期计划；你可以在这里编辑或取消。" type="info" :closable="false" show-icon />
          <div class="toolbar"><el-button type="primary" @click="openSchedule()">新增排班</el-button></div>
          <el-table :data="schedules"><el-table-column prop="scheduleDate" label="日期" min-width="110"/><el-table-column prop="scheduleTime" label="时间"/><el-table-column label="状态"><template #default="{row}">{{statusText(row.status)}}<small v-if="row.completedRecordId">（已关联记录）</small></template></el-table-column><el-table-column prop="remark" label="备注"/><el-table-column label="操作" min-width="200"><template #default="{row}"><el-button link @click="openSchedule(row)">编辑</el-button><el-button v-if="row.status==='PLANNED'" link type="success" :disabled="saving" @click="changeSchedule(row,'COMPLETED')">完成</el-button><el-button v-if="row.status!=='CANCELLED'" link type="danger" :disabled="saving" @click="changeSchedule(row,'CANCELLED')">取消排班</el-button><el-button v-else link type="primary" :disabled="saving" @click="changeSchedule(row,'PLANNED')">恢复</el-button></template></el-table-column></el-table>
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('target')" label="个人目标" name="target">
          <el-form :model="target" label-width="130px" class="target-form"><el-divider>血压目标（mmHg）</el-divider><div class="form-grid"><el-form-item label="收缩压"><el-input-number v-model="target.systolicMin" :min="1"/> 至 <el-input-number v-model="target.systolicMax" :min="1"/></el-form-item><el-form-item label="舒张压"><el-input-number v-model="target.diastolicMin" :min="1"/> 至 <el-input-number v-model="target.diastolicMax" :min="1"/></el-form-item><el-form-item label="目标体重"><el-input-number v-model="target.targetWeight" :precision="2" :min="1"/> kg</el-form-item><el-form-item label="紧急联系人"><el-input v-model="target.emergencyContact"/></el-form-item><el-form-item label="联系电话"><el-input v-model="target.emergencyPhone"/></el-form-item><el-form-item label="医院"><el-input v-model="target.hospitalName"/></el-form-item><el-form-item label="主管医生"><el-input v-model="target.doctorName"/></el-form-item></div><el-button type="primary" :loading="saving" @click="submitTarget">保存目标</el-button></el-form>
        </el-tab-pane>
        <el-tab-pane v-if="availableTabs.includes('summary')" label="就诊摘要" name="summary">
          <div class="toolbar"><el-button :loading="summaryLoading" @click="loadSummary">生成 / 刷新摘要</el-button><el-button :disabled="!summary" @click="printSummary">打印 / 保存 PDF</el-button></div>
          <article v-if="summary" ref="summaryElement" class="visit-summary">
            <h2>{{summary.patient?.name}} · 就诊摘要</h2><p>生成时间：{{summary.generatedAt}}</p><p>既往病史：{{summary.patient?.medicalHistory || '未填写'}}</p>
            <p>医院：{{summary.target?.hospitalName || '未填写'}}；主管医生：{{summary.target?.doctorName || '未填写'}}</p><p>紧急联系人：{{summary.target?.emergencyContact || summary.patient?.emergencyContact || '未填写'}} {{summary.target?.emergencyPhone || summary.patient?.emergencyPhone}}</p>
            <p>血压目标：{{summary.target?.systolicMin || '—'}}–{{summary.target?.systolicMax || '—'}} / {{summary.target?.diastolicMin || '—'}}–{{summary.target?.diastolicMax || '—'}} mmHg</p>
            <h3>当前用药</h3><table><thead><tr><th>药品</th><th>剂量说明</th></tr></thead><tbody><tr v-for="m in summary.medications" :key="m.id"><td>{{m.drugName}}</td><td>{{m.defaultDosage || '遵医嘱服用'}}</td></tr></tbody></table><p v-if="!summary.medications?.length">暂无用药记录</p>
            <h3>近期测量（最多 30 条）</h3><table><thead><tr><th>时间</th><th>血压（mmHg）</th><th>血糖</th></tr></thead><tbody><tr v-for="r in summary.recentMeasurements" :key="r.id"><td>{{r.recordDate}} {{r.recordTime}}</td><td>{{r.systolicBp ?? '—'}} / {{r.diastolicBp ?? '—'}}</td><td>{{r.bloodGlucose ?? '—'}} {{r.bgUnit}}</td></tr></tbody></table>
            <h3>未解决告警（最多 30 条）</h3><ul><li v-for="a in summary.unresolvedAlerts" :key="a.id">{{a.triggeredAt}} {{a.alertTitle}}：{{a.triggeredValue}} {{a.handlingNote}}</li></ul><p v-if="!summary.unresolvedAlerts?.length">暂无未解决告警</p>
            <h3>准备咨询医生的问题</h3><ul><li v-for="q in summary.questions||[]" :key="q.id"><b>{{q.title}}</b><p>{{q.details?.description}}</p><p v-if="q.details?.answer">医生答复：{{q.details.answer}}</p><p v-if="q.details?.followUp">后续事项：{{q.details.followUp}}</p></li></ul>
            <h3>近期症状跟踪</h3><ul><li v-for="s in summary.careSymptoms||[]" :key="s.id">{{s.eventAt}} {{s.title}}，自评分 {{s.details?.severity}}/10，持续 {{s.details?.duration || '未填写'}}；{{s.details?.response}}</li></ul>
            <h3>近期健康事件（最多 30 条）</h3><ul><li v-for="e in summary.recentEvents" :key="`${e.sourceType}-${e.id}`">{{e.eventDate}} {{e.title}}：{{e.summary}}</li></ul><p>{{summary.disclaimer}}</p>
          </article><el-empty v-else description="生成摘要，为下次就诊准备近期记录"/>
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog v-model="eventVisible" :title="eventForm.id?'编辑健康事件':'记录健康事件'" width="min(520px, 94vw)"><el-form :model="eventForm" label-width="90px"><el-form-item label="日期" required><el-date-picker v-model="eventForm.eventDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="时间"><el-time-picker v-model="eventForm.eventTime" value-format="HH:mm" format="HH:mm"/></el-form-item><el-form-item label="类型"><el-select v-model="eventForm.eventType"><el-option label="症状" value="SYMPTOM"/><el-option label="就诊" value="VISIT"/><el-option label="备注" value="NOTE"/></el-select></el-form-item><el-form-item label="标题" required><el-input v-model="eventForm.title" maxlength="120"/></el-form-item><el-form-item label="说明"><el-input v-model="eventForm.summary" type="textarea" maxlength="500" show-word-limit/></el-form-item></el-form><template #footer><el-button @click="eventVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitEvent">保存</el-button></template></el-dialog>
    <el-dialog v-model="scheduleVisible" :title="scheduleForm.id?'编辑透析排班':'新增透析排班'" width="min(480px, 94vw)"><el-form :model="scheduleForm" label-width="80px"><el-form-item label="日期" required><el-date-picker v-model="scheduleForm.scheduleDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="时间"><el-time-picker v-model="scheduleForm.scheduleTime" value-format="HH:mm" format="HH:mm"/></el-form-item><el-form-item label="备注"><el-input v-model="scheduleForm.remark" maxlength="255"/></el-form-item></el-form><template #footer><el-button @click="scheduleVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitSchedule">保存</el-button></template></el-dialog>
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
async function save(operation){if(saving.value)return;const id=patientId.value;if(!id)return;saving.value=true;try{await operation(id);ElMessage.success('保存成功。');if(id===patientId.value){summaryVersion++;summary.value=null;summaryLoading.value=false;await reload()}}finally{saving.value=false}}
async function doIntake(item,status){const id=patientId.value;let reason='';if(status==='SKIPPED'){const answer=await ElMessageBox.prompt('请简要说明跳过本次服药的原因。','跳过本次服药').catch(()=>null);if(!answer)return;reason=answer.value}if(id!==patientId.value)return;await save(()=>api.actionIntake(item.id,status,reason))}
function openEvent(row){Object.keys(eventForm).forEach(k=>delete eventForm[k]);Object.assign(eventForm,{id:null,eventDate:localDateKey(),eventTime:'',eventType:'SYMPTOM',title:'',summary:''},row||{});eventVisible.value=true}
async function submitEvent(){if(!eventForm.title?.trim() || !eventForm.eventDate){ElMessage.warning('请输入日期和标题。');return}const data={...eventForm};await save(async id=>{await api.saveEvent({...data,patientId:id,eventTime:data.eventTime||''});eventVisible.value=false})}
async function removeEvent(row){await save(()=>api.deleteEvent(row.id))}
function openSchedule(row){Object.keys(scheduleForm).forEach(k=>delete scheduleForm[k]);Object.assign(scheduleForm,{id:null,scheduleDate:localDateKey(),scheduleTime:'',remark:'',status:'PLANNED'},row||{});scheduleVisible.value=true}
async function submitSchedule(){if(!scheduleForm.scheduleDate){ElMessage.warning('请选择日期');return}const data={...scheduleForm};await save(async id=>{await api.saveDialysisSchedule({...data,patientId:id,scheduleTime:data.scheduleTime||''});scheduleVisible.value=false})}
async function changeSchedule(row,status){await save(id=>api.saveDialysisSchedule({...row,status,patientId:id}))}
async function submitTarget(){if(target.systolicMin>target.systolicMax || target.diastolicMin>target.diastolicMax){ElMessage.warning('目标最小值不能大于最大值。');return}const data={...target};delete data.id;await save(id=>api.saveHealthTarget({...data,patientId:id}))}
async function loadSummary(){const id=patientId.value,v=++summaryVersion;if(!id)return;summaryLoading.value=true;try{const r=await api.getVisitSummary(id);if(id===patientId.value && v===summaryVersion)summary.value=r.data}finally{if(v===summaryVersion)summaryLoading.value=false}}
function printSummary(){if(!summaryElement.value)return;const popup=window.open('','_blank');if(!popup){ElMessage.warning('请允许弹出窗口以打印摘要。');return}popup.document.write('<!doctype html><html><head><meta charset="UTF-8"><title>就诊摘要</title><style>body{font-family:Arial,"Microsoft YaHei",sans-serif;margin:24px;line-height:1.6}table{width:100%;border-collapse:collapse}th,td{border:1px solid #ccc;text-align:left;padding:6px}thead{display:table-header-group}tr{break-inside:avoid}h3{break-after:avoid}</style></head><body>'+summaryElement.value.innerHTML+'</body></html>');popup.document.close();popup.focus();popup.setTimeout(()=>popup.print(),250)}
const intakeText=s=>({PENDING:'待服用',TAKEN:'已服用',SNOOZED:'已延后',SKIPPED:'已跳过',MISSED:'已漏服',CANCELLED:'已取消'}[s]||s)
const tagType=s=>({TAKEN:'success',MISSED:'danger',SKIPPED:'info',SNOOZED:'warning',CANCELLED:'info'}[s]||'primary')
const statusText=s=>({PLANNED:'已计划',COMPLETED:'已完成',CANCELLED:'已取消'}[s]||s)
const eventType=s=>({SYMPTOM:'症状',VISIT:'就诊',NOTE:'备注',MEASUREMENT:'测量',DIALYSIS:'透析',INTAKE:'服药',MEDICATION_LOG:'用药记录'}[s]||s)
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
