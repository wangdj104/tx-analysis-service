<template>
  <main class="automation-page">
    <el-alert v-if="!patientId" title="请先在顶部选择家庭成员。" type="warning" :closable="false" show-icon/>
    <header><div><h2>自动健康分析</h2><p>定时任务会生成待审核草稿；在人工批准前，通知中不会包含临床建议。</p></div><el-button type="primary" :disabled="!patientId" @click="openCreate">新增自动任务</el-button></header>
    <section v-loading="loading" class="task-list">
      <el-empty v-if="!rows.length" description="暂无自动分析任务"/>
      <article v-for="row in rows" :key="row.id" class="task-card">
        <div class="task-main"><div><b>{{row.taskName}}</b><el-tag size="small" :type="row.enabled===1?'success':'info'">{{row.enabled===1?'启用':'停用'}}</el-tag></div><p>{{cycleText(row)}} · 分析近 {{row.analysisRangeDays}} 天 · {{itemsText(row.analysisItems)}}</p><small>下次运行：{{row.nextRunAt||'—'}} · 最近状态：{{statusText(row.lastRunStatus)}} {{row.lastRunAt||''}}</small><small v-if="row.lastError" class="error">{{row.lastError}}</small></div>
        <div class="task-actions"><el-button v-if="row.lastAnalysisContent" link @click="showResult(row)">查看草稿</el-button><el-button link :loading="runningId===row.id" @click="runNow(row)">立即运行</el-button><el-button link type="primary" @click="edit(row)">编辑</el-button><el-popconfirm title="确认删除该自动任务吗？" @confirm="remove(row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm></div>
      </article>
    </section>
    <el-dialog v-model="visible" :title="form.id?'编辑自动任务':'新增自动任务'" width="min(680px,96vw)">
      <el-form :model="form" label-width="110px">
        <el-form-item label="任务名称"><el-input v-model="form.taskName" maxlength="100"/></el-form-item>
        <el-form-item label="运行频率"><el-radio-group v-model="form.frequencyType"><el-radio-button value="DAILY">每天</el-radio-button><el-radio-button value="WEEKLY">每周</el-radio-button><el-radio-button value="MONTHLY">每月</el-radio-button></el-radio-group></el-form-item>
        <el-form-item v-if="form.frequencyType==='DAILY'" label="间隔"><el-input-number v-model="form.intervalDays" :min="1" :max="30"/> 天</el-form-item>
        <el-form-item v-if="form.frequencyType==='WEEKLY'" label="星期"><el-select v-model="form.dayOfWeek"><el-option v-for="(label,index) in weekdays" :key="index" :label="label" :value="index+1"/></el-select></el-form-item>
        <el-form-item v-if="form.frequencyType==='MONTHLY'" label="每月日期"><el-input-number v-model="form.dayOfMonth" :min="1" :max="28"/></el-form-item>
        <el-form-item label="运行时间"><el-time-picker v-model="form.runTime" value-format="HH:mm" format="HH:mm"/></el-form-item>
        <el-form-item label="分析范围"><el-select v-model="form.analysisRangeDays"><el-option label="近 7 天" :value="7"/><el-option label="近 14 天" :value="14"/><el-option label="近 30 天" :value="30"/><el-option label="近 90 天" :value="90"/></el-select></el-form-item>
        <el-form-item label="分析主题"><el-checkbox-group v-model="form.analysisItems"><el-checkbox v-for="item in itemOptions" :key="item.value" :value="item.value">{{item.label}}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="通知渠道"><el-select v-model="form.channelIds" multiple clearable placeholder="留空则使用全部已启用渠道" style="width:100%"><el-option v-for="channel in channels" :key="channel.id" :label="channel.channelName||channel.channelType" :value="channel.id"/></el-select><small>渠道只会收到草稿已生成通知；审核后的内容仅在人工明确批准后发送。</small></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="resultVisible" title="最新分析草稿" width="min(760px,96vw)"><el-alert title="此草稿尚未通过临床审核，分享前请在临床工作台完成审核。" type="warning" :closable="false" show-icon/><pre class="analysis-result">{{resultContent}}</pre></el-dialog>
  </main>
</template>
<script setup>
import{onMounted,reactive,ref,watch}from'vue'
import{ElMessage}from'element-plus'
import{useCurrentPatient}from'@/composables/useCurrentPatient'
import{listNotificationChannels}from'@/api/notificationChannel'
import{deleteHealthAutomation,listHealthAutomations,runHealthAutomation,saveHealthAutomation}from'@/api/healthAutomation'
const{currentPatientId:patientId}=useCurrentPatient(),rows=ref([]),channels=ref([]),loading=ref(false),saving=ref(false),visible=ref(false),runningId=ref(null),resultVisible=ref(false),resultContent=ref('')
const weekdays=['周一','周二','周三','周四','周五','周六','周日']
const itemOptions=[{value:'DIALYSIS',label:'透析与体重'},{value:'VITALS',label:'血压与血糖'},{value:'MEDICATION',label:'用药依从性'},{value:'NUTRITION',label:'营养与液体摄入'},{value:'COMPLICATION',label:'并发症与症状'}]
const form=reactive({id:null,taskName:'每周健康回顾',frequencyType:'WEEKLY',intervalDays:1,dayOfWeek:1,dayOfMonth:1,runTime:'08:00',analysisRangeDays:30,analysisItems:itemOptions.map(x=>x.value),channelIds:[],enabled:1})
async function load(){loading.value=true;try{const[a,b]=await Promise.all([listHealthAutomations(),listNotificationChannels()]);rows.value=(a.data||[]).filter(x=>!patientId.value||Number(x.patientId)===Number(patientId.value));channels.value=(b.data||[]).filter(x=>x.enabled===1)}finally{loading.value=false}}
function reset(){Object.assign(form,{id:null,taskName:'每周健康回顾',frequencyType:'WEEKLY',intervalDays:1,dayOfWeek:1,dayOfMonth:1,runTime:'08:00',analysisRangeDays:30,analysisItems:itemOptions.map(x=>x.value),channelIds:[],enabled:1})}
function openCreate(){reset();visible.value=true}
function edit(row){Object.assign(form,{...row,analysisItems:(row.analysisItems||'').split(',').filter(Boolean),channelIds:(row.notificationChannelIds||'').split(',').filter(Boolean).map(Number)});visible.value=true}
async function submit(){if(!form.taskName?.trim()){ElMessage.warning('请输入任务名称。');return}if(!form.analysisItems.length){ElMessage.warning('请至少选择一个分析主题。');return}saving.value=true;try{await saveHealthAutomation({...form,patientId:patientId.value,analysisItems:form.analysisItems.join(','),notificationChannelIds:form.channelIds.join(',')});ElMessage.success('自动任务已保存。');visible.value=false;await load()}finally{saving.value=false}}
async function runNow(row){runningId.value=row.id;try{await runHealthAutomation(row.id);ElMessage.success('草稿已生成并进入待审核队列。');await load()}finally{runningId.value=null}}
async function remove(row){await deleteHealthAutomation(row.id);ElMessage.success('已删除');await load()}
function showResult(row){resultContent.value=row.lastAnalysisContent||'暂无结果';resultVisible.value=true}
function cycleText(row){if(row.frequencyType==='DAILY')return`每 ${row.intervalDays} 天 ${row.runTime}`;if(row.frequencyType==='WEEKLY')return`每${weekdays[(row.dayOfWeek||1)-1]} ${row.runTime}`;return`每月 ${row.dayOfMonth} 日 ${row.runTime}`}
function itemsText(value){const set=(value||'').split(',');return itemOptions.filter(x=>set.includes(x.value)).map(x=>x.label).join(', ')}
function statusText(value){return({DRAFT_READY:'草稿待审核',DRAFT_READY_NOTIFY_FAILED:'草稿待审核，通知发送失败',SUCCESS:'已完成',SUCCESS_NOTIFY_FAILED:'已完成，通知发送失败',FAILED:'运行失败'}[value]||'尚未运行')}
watch(patientId,load);onMounted(load)
</script>
<style scoped>
.automation-page>header,.task-card{display:flex;align-items:center;justify-content:space-between;gap:18px}.automation-page h2{margin:0 0 6px}.automation-page p{margin:0;color:#64748b}.task-list{margin-top:18px}.task-card{padding:18px 20px;margin-bottom:12px;border:1px solid #dce7e4;border-radius:14px;background:#fff}.task-main{display:grid;gap:7px;min-width:0}.task-main b{margin-right:10px}.task-main small{color:#71817d}.task-main .error{color:#c2413b}.task-actions{display:flex;align-items:center;justify-content:flex-end;flex-wrap:wrap;gap:4px 10px}.task-actions .el-button+.el-button{margin-left:0}.el-checkbox-group{display:flex;flex-wrap:wrap;gap:4px 14px}.el-form-item small{display:block;color:#71817d;margin-top:6px}.analysis-result{white-space:pre-wrap;line-height:1.75;font:inherit;color:#233b35}@media(max-width:700px){.automation-page>header,.task-card{align-items:flex-start;flex-direction:column}.task-actions{width:100%;justify-content:flex-start}}
</style>
