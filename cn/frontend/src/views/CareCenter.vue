<template>
  <section class="care-center" aria-labelledby="care-heading" v-loading="loading">
    <header class="care-header">
      <div class="care-header__copy">
        <span class="eyebrow">家庭照护</span>
        <h1 id="care-heading">{{ mode === 'PATIENT' ? '照顾好今天的自己' : '携手照护家人' }}</h1>
        <p class="care-context"><span>当前档案</span><strong>{{ selectedPatientName || '请在顶部选择患者' }}</strong></p>
      </div>
      <picture class="care-header-art"><source media="(max-width:800px)" :srcset="careMomentsSmall"/><img :src="careMoments" alt="" width="120" height="80" decoding="async"/></picture>
    </header>
    <div class="care-controls">
      <el-radio-group v-model="mode" aria-label="照护角色"><el-radio-button value="PATIENT">我是患者</el-radio-button><el-radio-button value="FAMILY">我是照护者</el-radio-button></el-radio-group>
      <div class="care-controls__utilities">
        <el-switch v-model="senior" active-text="大字模式" aria-label="大字模式"/>
        <details class="care-options">
          <summary>照护选项</summary>
          <div class="care-toolbar">
            <el-button @click="joinVisible=true">加入共享照护</el-button>
            <el-button @click="tab='backup'">备份与恢复</el-button>
            <el-button :loading="loading" @click="reload">刷新</el-button>
          </div>
        </details>
      </div>
    </div>
    <section v-if="mode==='FAMILY'" class="family-cards" aria-label="家庭成员">
      <button v-for="p in home" :key="p.patient.id" type="button" :class="{selected:p.patient.id===patientId}" :aria-pressed="p.patient.id===patientId" @click="patientId=p.patient.id">
        <span class="family-card__heading"><strong>{{p.patient.name}}</strong><span v-if="p.patient.id===patientId" class="family-card__selected">当前</span></span>
        <span>{{pendingCount(p)}} 项需要关注</span><small>{{nextAppointment(p)}}</small>
      </button>
    </section>
    <el-alert v-if="!patientId" title="请选择家庭成员，或使用邀请码加入共享照护。" :closable="false" type="info"/>
    <el-tabs v-model="tab" class="care-tabs">
      <el-tab-pane label="今日" name="today" :disabled="!patientId">
        <section class="quick-actions" aria-label="记录健康"><el-button class="quick-actions__primary" type="primary" @click="quickVisible=true"><span>记录健康<small>血压 / 血糖</small></span></el-button><el-button @click="open('SYMPTOM')">记录症状</el-button><el-button @click="open('QUESTION')">添加就诊问题</el-button><el-button v-if="mode==='FAMILY'" @click="open('HANDOVER')">添加家庭交接</el-button></section>
        <div class="care-columns"><section class="care-card"><div class="care-card__heading"><h2>今日用药</h2><span class="care-count">{{pendingMedicationCount}} 项待办</span></div><el-empty v-if="!activeIntakes.length" description="今天没有用药任务"/><article v-for="i in activeIntakes" :key="i.id" class="care-task"><div><strong>{{i.scheduledAt?.slice(11,16)}} {{i.drugName}}</strong><p>{{i.dosage}}</p><small v-if="lastActor(i.id)">{{lastActor(i.id)}}</small><small v-if="i.status==='SNOOZED'">将在 {{i.snoozeUntil}} 再次提醒</small></div><el-tag>{{statusText(i.status)}}</el-tag><div v-if="['PENDING','MISSED','SNOOZED'].includes(i.status)" class="task-actions"><el-button type="success" :disabled="busy" @click="openIntake(i)">{{mode==='FAMILY'?'代记已服':'我已服药'}}</el-button><el-button :disabled="busy" @click="intakeAction(i,'SNOOZED')">15 分钟后提醒</el-button><el-button :disabled="busy" @click="skipIntake(i)">跳过</el-button></div></article></section>
          <section class="care-card"><div class="care-card__heading"><h2>预约与照护任务</h2><span class="care-count">{{dueTasks.length}} 项待办</span></div><el-empty v-if="!dueTasks.length" description="近期没有待处理事项"/><article v-for="item in dueTasks" :key="item.id" class="care-task"><div><strong>{{item.title}}</strong><p>{{item.eventAt || '未设置时间'}}</p><small>负责人：{{memberName(item.assignedUserId)}} · 记录人：{{item.actorName||'家庭成员'}}</small><p>{{item.details?.note || item.details?.preparation}}</p></div><div class="task-actions"><el-button :disabled="busy" type="primary" @click="action(item,'DONE')">完成</el-button><el-button @click="open(item.kind,item)">查看</el-button></div></article></section></div>
        <el-alert v-for="s in lowStocks" :key="s.id" :title="s.drugName + ' 剩余 ' + s.quantity + ' ' + s.unit + (s.estimatedDays != null ? '（约 ' + s.estimatedDays + ' 天），请安排补充。' : '，请检查库存。')" type="warning" :closable="false"/>
      </el-tab-pane>
      <el-tab-pane label="预约" name="appointments" :disabled="!patientId">
        <div class="section-head"><h2>预约、复诊与陪诊</h2><el-button type="primary" @click="open('APPOINTMENT')">新增预约</el-button></div>
        <el-calendar v-model="calendarDate"><template #date-cell="{data}"><div class="calendar-cell"><span>{{data.day.slice(8)}}</span><button v-for="a in appointments.filter(x=>x.eventAt?.startsWith(data.day)&&x.status!=='CANCELLED')" :key="a.id" @click.stop="open('APPOINTMENT',a)">{{a.title}}</button></div></template></el-calendar>
        <el-table :data="appointments"><el-table-column label="时间" prop="eventAt" min-width="165"/><el-table-column label="预约事项" prop="title" min-width="150"/><el-table-column label="医院 / 科室" min-width="150"><template #default="{row}">{{row.details.hospital}} {{row.details.department}}</template></el-table-column><el-table-column label="陪诊人"><template #default="{row}">{{memberName(row.assignedUserId)}}</template></el-table-column><el-table-column label="状态"><template #default="{row}">{{statusText(row.status)}}</template></el-table-column><el-table-column label="操作" min-width="190"><template #default="{row}"><el-button link @click="open('APPOINTMENT',row)">编辑 / 关联报告</el-button><el-button v-if="row.status==='OPEN'" link type="success" :disabled="busy" @click="action(row,'DONE')">完成</el-button><el-button v-if="row.status!=='CANCELLED'" link :disabled="busy" @click="action(row,'CANCELLED')">取消</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="用药医嘱" name="orders" :disabled="!patientId">
        <div class="section-head"><div><h2>遵循当前医嘱并保留每次变更</h2><p>请先添加药品，再录入医生用药指示和生效日期。</p></div><div><el-button @click="$router.push('/medication')">药品列表</el-button><el-button type="primary" @click="open('ORDER')">新增医嘱</el-button></div></div>
        <el-empty v-if="!orders.length" description="暂无医嘱版本"/>
        <article v-for="order in orders" :key="order.id" class="care-card order-card"><div class="section-head"><h3>{{order.title}}</h3><el-tag>{{statusText(order.status)}}</el-tag></div><p>{{drugName(order.details.medicationId)}} · {{order.details.startDate}} 起{{order.details.endDate?'，至 '+order.details.endDate:''}} · 医生：{{order.details.doctor||'未填写'}}</p><p v-if="order.details.action!=='STOP'">{{order.details.doses?.map(d=>d.time+' '+d.quantity+order.details.unit).join('; ')}} · {{daysText(order.details.repeatDays)}}</p><p v-else>该药品已停用。</p><p>{{order.details.instructions}}</p><div class="attachment-links"><a v-for="(a,i) in order.details.attachments||[]" :key="i" :href="a.dataUrl" :download="a.name">{{a.name}}</a></div><small>记录人：{{order.actorName}} · 版本 #{{order.id}}</small><div class="task-actions"><el-button @click="changeOrder(order,'CHANGE')">新增调整版本</el-button><el-button v-if="order.status==='ACTIVE'" @click="changeOrder(order,'STOP')">记录停药医嘱</el-button><el-button v-if="order.status==='SCHEDULED'" :disabled="busy" @click="action(order,'CANCELLED')">取消计划版本</el-button></div></article>
      </el-tab-pane>
      <el-tab-pane label="药品库存" name="stock" :disabled="!patientId">
        <div class="section-head"><h2>剩余药品</h2><el-button type="primary" @click="openStock()">设置或修正库存</el-button></div><p>单位需与医嘱一致。服药打卡会扣减医嘱中的数值剂量；确认时也可录入临时剂量。</p>
        <el-table :data="context.stocks||[]"><el-table-column prop="drugName" label="药品" min-width="150"/><el-table-column label="剩余数量"><template #default="{row}">{{row.quantity}} {{row.unit}} <el-tag v-if="row.low" type="warning">库存不足</el-tag></template></el-table-column><el-table-column label="预计可用天数"><template #default="{row}">{{row.estimatedDays==null?'医嘱无数值剂量':row.estimatedDays+' 天'}}</template></el-table-column><el-table-column label="操作" min-width="180"><template #default="{row}"><el-button link @click="purchase(row)">补充库存</el-button><el-button link @click="openStock(row)">修正</el-button><el-button link @click="showStockHistory(row)">历史</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="症状" name="symptoms" :disabled="!patientId">
        <div class="section-head"><h2>跟踪症状出现时间与变化</h2><el-button type="primary" @click="open('SYMPTOM')">记录症状</el-button></div><el-select v-model="symptomFilter" clearable placeholder="选择症状查看历史"><el-option v-for="name in [...new Set(symptoms.map(s=>s.title))]" :key="name" :value="name"/></el-select>
        <article v-for="s in filteredSymptoms" :key="s.id" class="care-card"><div class="section-head"><h3>{{s.title}}</h3><span>{{s.eventAt}} · {{s.actorName}}</span></div><el-progress :percentage="Number(s.details.severity)*10" :format="()=>s.details.severity+'/10'"/><p>持续时间：{{s.details.duration||'未填写'}}；{{progressText(s.details.progress)}}；{{symptomChange(s)}}</p><p>{{s.details.context}}</p><p>{{s.details.response}}</p><el-button link @click="open('SYMPTOM',s)">编辑</el-button><el-button link @click="open('SYMPTOM',{...s,id:null,eventAt:null})">再次记录</el-button><el-popconfirm title="确认删除这条症状记录吗？" @confirm="removeItem(s)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm></article><el-empty v-if="!filteredSymptoms.length" description="暂无症状记录"/>
      </el-tab-pane>
      <el-tab-pane label="就诊问题" name="questions" :disabled="!patientId">
        <div class="section-head"><h2>就诊前准备问题，就诊后记录答案</h2><div><el-button @click="$router.push('/family-health')">就诊摘要与打印</el-button><el-button type="primary" @click="open('QUESTION')">新增问题</el-button></div></div>
        <article v-for="q in questions" :key="q.id" class="care-card"><div class="section-head"><h3>{{q.title}}</h3><el-tag>{{statusText(q.status)}}</el-tag></div><p>{{q.details.description}}</p><p v-if="q.details.appointmentId">关联预约：{{appointments.find(a=>a.id===q.details.appointmentId)?.title||'预约已删除'}}</p><p>医生答复：{{q.details.answer||'尚未询问'}}</p><p>后续事项：{{q.details.followUp||'无'}}</p><el-button @click="open('QUESTION',q)">编辑问题 / 答案</el-button><el-button v-if="q.status==='OPEN'" :disabled="busy" @click="answer(q)">标记已答复</el-button><el-button v-if="q.details.followUp" @click="open('HANDOVER',{title:q.details.followUp,details:{note:q.details.answer},kind:'HANDOVER'})">创建照护任务</el-button><el-popconfirm title="确认删除这个问题吗？" @confirm="removeItem(q)"><template #reference><el-button link>删除</el-button></template></el-popconfirm></article><el-empty v-if="!questions.length" description="想到问题时可随时记录在这里"/>
      </el-tab-pane>
      <el-tab-pane label="家庭协作" name="family" :disabled="!patientId">
        <div class="section-head"><h2>共享照护成员</h2><el-button v-if="canManage" type="primary" @click="invite">生成邀请码</el-button></div><p v-if="inviteCode" class="invite-code">邀请码（7 天内有效且仅可使用一次）：<strong>{{inviteCode}}</strong><el-button @click="copyInvite">复制</el-button></p>
        <el-table :data="context.members||[]"><el-table-column prop="name" label="姓名"/><el-table-column prop="username" label="账号"/><el-table-column prop="relationName" label="与患者关系"/><el-table-column label="操作"><template #default="{row}"><el-popconfirm v-if="canManage&&row.userId!==context.patient?.userId" title="确认将该账号移出共享照护吗？" @confirm="removeMember(row)"><template #reference><el-button link type="danger">移除</el-button></template></el-popconfirm></template></el-table-column></el-table>
        <div class="section-head"><h2>照护交接</h2><el-button @click="open('HANDOVER')">新增交接</el-button></div><article v-for="h in handovers" :key="h.id" class="care-card"><strong>{{h.title}}</strong><p>{{h.details.note}}</p><small>{{h.actorName}} 指派给 {{memberName(h.assignedUserId)}} · {{h.eventAt||'未设置截止时间'}} · {{statusText(h.status)}}</small><div class="task-actions"><el-button @click="open('HANDOVER',h)">编辑</el-button><el-button v-if="h.status==='OPEN'" :disabled="busy" @click="action(h,'DONE')">完成</el-button></div></article>
        <h2>近期动态</h2><el-table :data="activities.slice(0,50)"><el-table-column prop="eventAt" label="时间"/><el-table-column prop="actorName" label="记录人"/><el-table-column prop="title" label="操作"/></el-table>
      </el-tab-pane>
      <el-tab-pane label="照护设置" name="settings" :disabled="!patientId">
        <el-form label-position="top" style="max-width:650px"><h2>{{context.patient?.name}}的照护设置</h2><el-form-item label="逾期任务通知照护者"><el-select v-model="profile.escalationUserId" clearable placeholder="不发送额外跟进通知"><el-option v-for="m in context.members||[]" :key="m.userId" :value="m.userId" :label="m.name"/></el-select></el-form-item><el-form-item label="逾期多少分钟后通知照护者"><el-input-number v-model="profile.escalationMinutes" :min="15" :step="15"/></el-form-item><el-button type="primary" :loading="busy" @click="saveProfile">保存设置</el-button><el-button @click="$router.push('/settings/notifications')">通知渠道</el-button></el-form>
      </el-tab-pane>
      <el-tab-pane label="备份与恢复" name="backup"><FamilyBackupPanel @restored="reload"/></el-tab-pane>
    </el-tabs>
    <CareEntryDialog v-model="entryVisible" :kind="entryKind" :row="entryRow" :patient-id="patientId" :members="context.members" :medications="context.medications" :reports="context.reports" :appointments="appointments" @saved="reload"/>
    <el-dialog v-model="quickVisible" title="记录血压 / 血糖" width="min(460px,94vw)"><el-form label-position="top"><el-form-item label="收缩压 / 舒张压（mmHg）"><el-input-number v-model="quick.systolicBp" :min="1"/> / <el-input-number v-model="quick.diastolicBp" :min="1"/></el-form-item><el-form-item label="血糖（mmol/L，可选）"><el-input-number v-model="quick.bloodGlucose" :min="0.1" :precision="1"/></el-form-item><el-form-item label="测量时段"><el-select v-model="quick.measurePeriod"><el-option label="空腹" value="Fasting"/><el-option label="餐后" value="After Meal"/><el-option label="随机" value="Random"/></el-select></el-form-item><el-form-item label="备注"><el-input v-model="quick.remark"/></el-form-item></el-form><template #footer><el-button :loading="busy" type="primary" @click="submitQuick">保存读数</el-button></template></el-dialog>
    <el-dialog v-model="intakeVisible" :title="mode==='FAMILY'?'为家庭成员记录服药':'确认本次服药'" width="min(460px,94vw)"><p>{{selectedIntake?.drugName}} · {{selectedIntake?.dosage}}</p><el-form label-position="top"><el-form-item label="实际用量（可选，使用库存单位）"><el-input-number v-model="intakeQuantity" :min="0.001" :precision="3"/></el-form-item><p>留空时，仅在医嘱剂量单位与库存单位一致时自动扣减库存。</p></el-form><template #footer><el-button :loading="busy" type="success" @click="confirmIntake">确认已服</el-button></template></el-dialog>
    <el-dialog v-model="stockVisible" title="设置或修正库存" width="min(480px,94vw)"><el-form label-position="top"><el-form-item label="药品"><el-select v-model="stockForm.medicationId" :disabled="!!stockForm.id"><el-option v-for="m in context.medications||[]" :key="m.id" :value="m.id" :label="m.drugName"/></el-select></el-form-item><el-form-item label="实际剩余数量"><el-input-number v-model="stockForm.quantity" :min="0" :precision="3"/></el-form-item><el-form-item label="单位（需与医嘱一致）"><el-input v-model="stockForm.unit"/></el-form-item><el-form-item label="剩余天数低于此值时提醒"><el-input-number v-model="stockForm.warningDays" :min="0"/></el-form-item><el-form-item label="或数量低于此值时提醒"><el-input-number v-model="stockForm.warningQuantity" :min="0"/></el-form-item></el-form><template #footer><el-button :loading="busy" type="primary" @click="submitStock">保存</el-button></template></el-dialog>
    <el-dialog v-model="historyVisible" title="库存历史" width="min(800px,94vw)"><el-table :data="stockHistory"><el-table-column prop="created_at" label="时间"/><el-table-column prop="quantity" label="数量变化"/><el-table-column prop="reason" label="原因"/><el-table-column prop="actor_name" label="记录人"/></el-table></el-dialog>
    <el-dialog v-model="joinVisible" title="加入共享照护" width="min(460px,94vw)"><p>请向该家庭成员记录的所有者索取邀请码。</p><el-input v-model="joinCode" placeholder="粘贴邀请码"/><el-input v-model="joinRelation" placeholder="你与患者的关系，例如：女儿" style="margin-top:14px"/><template #footer><el-button type="primary" :loading="busy" @click="join">加入</el-button></template></el-dialog>
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
function readCarePreference(key, fallback = '') {
  try { return localStorage.getItem(key) || fallback } catch { return fallback }
}
const mode=ref(readCarePreference('care-mode','PATIENT')),senior=ref(readCarePreference('care-senior')==='true'),tab=ref('today'),home=ref([]),context=ref({}),loading=ref(false),busy=ref(false)
const entryVisible=ref(false),entryKind=ref('APPOINTMENT'),entryRow=ref(null),calendarDate=ref(new Date()),symptomFilter=ref('')
const quickVisible=ref(false),quick=reactive({systolicBp:undefined,diastolicBp:undefined,bloodGlucose:undefined,measurePeriod:readCarePreference('care-measure-period','Fasting'),remark:''})
const intakeVisible=ref(false),selectedIntake=ref(null),intakeQuantity=ref(undefined),stockVisible=ref(false),stockForm=reactive({}),historyVisible=ref(false),stockHistory=ref([])
const joinVisible=ref(false),joinCode=ref(''),joinRelation=ref('家庭照护者'),inviteCode=ref(''),profile=reactive({escalationUserId:null,escalationMinutes:60})
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
watch(mode,v=>{try{localStorage.setItem('care-mode',v)}catch{/* Keep the live selection when storage is unavailable. */}})
watch(senior,v=>{
  document.body.classList.toggle('care-senior',v)
  try{localStorage.setItem('care-senior',v)}catch{/* Large text still applies for this visit. */}
},{immediate:true})
let generation=0, patientEpoch=0, historyRequest=0, inviteRequest=0, timer
let editorOperation=null, disposed=false
const editorVersions={quick:0,stock:0}
function invalidateEditor(kind) {
  editorVersions[kind]++
  if(editorOperation?.kind===kind){editorOperation=null;busy.value=false}
}
watch(quickVisible,()=>invalidateEditor('quick'),{flush:'sync'})
watch(stockVisible,()=>invalidateEditor('stock'),{flush:'sync'})
async function reload(){const v=++generation;loading.value=true;try{const r=await api.getCareHome();if(v!==generation)return;home.value=r.data||[];setPatientList(home.value.map(x=>({id:x.patient.id,patientName:x.patient.name})));if(!patientId.value&&home.value.length){patientId.value=home.value[0].patient.id;return}if(patientId.value){const id=patientId.value,c=await api.getCareContext(id);if(v!==generation||id!==patientId.value)return;context.value=c.data||{};const p=items.value.find(x=>x.kind==='PROFILE');Object.assign(profile,{escalationUserId:null,escalationMinutes:60},p?.details||{});}}finally{if(v===generation)loading.value=false}}
function resetQuick() {
  Object.assign(quick, { systolicBp: undefined, diastolicBp: undefined, bloodGlucose: undefined, remark: '' })
}
watch(patientId, () => {
  generation++; patientEpoch++; historyRequest++; inviteRequest++
  invalidateEditor('quick'); invalidateEditor('stock')
  context.value = {}; entryRow.value = null; selectedIntake.value = null
  entryVisible.value = false; quickVisible.value = false; intakeVisible.value = false; stockVisible.value = false
  historyVisible.value = false; stockHistory.value = []; inviteCode.value = ''; symptomFilter.value = ''
  Object.assign(profile, { escalationUserId: null, escalationMinutes: 60 })
  resetQuick()
  reload()
}, { flush: 'sync' })
onMounted(()=>{reload();timer=setInterval(()=>{if(!busy.value&&!entryVisible.value&&!stockVisible.value&&!quickVisible.value&&!intakeVisible.value&&tab.value==='today')reload()},60000)})
onUnmounted(()=>{disposed=true;generation++;patientEpoch++;historyRequest++;inviteRequest++;invalidateEditor('quick');invalidateEditor('stock');clearInterval(timer)})
async function perform(operation){if(busy.value)return;busy.value=true;try{await operation();ElMessage.success('保存成功。');await reload()}finally{busy.value=false}}
function open(kind,row){if(!patientId.value)return;entryKind.value=kind;entryRow.value=row||null;entryVisible.value=true}
function changeOrder(row,action){const data=JSON.parse(JSON.stringify(row));delete data.id;data.details.action=action;data.details.startDate=localDateKey();data.details.endDate=null;data.title=`${drugName(data.details.medicationId)}${action==='STOP'?'停药':'用药调整'}`;open('ORDER',data)}
async function action(row,status){await perform(()=>api.careAction(row.id,status))}
async function removeItem(row){await perform(()=>api.deleteCareItem(row.id))}
async function answer(row){const r=await ElMessageBox.prompt('记录医生答复','就诊答复',{inputType:'textarea',inputValue:row.details.answer||''}).catch(()=>null);if(r)await perform(()=>api.careAction(row.id,'ANSWERED',r.value))}
async function saveEditor(kind, form, save, finish) {
  if(busy.value||!patientId.value||disposed)return
  const id=patientId.value,epoch=patientEpoch,editor=editorVersions[kind]
  const data={...form,patientId:id},draft=JSON.stringify(form),operation={kind}
  const current=()=>!disposed&&editorOperation===operation&&epoch===patientEpoch&&id===patientId.value&&editor===editorVersions[kind]
  editorOperation=operation;busy.value=true
  try{
    await save(data)
    if(!current()||JSON.stringify(form)!==draft)return
    finish(data)
    ElMessage.success('保存成功。')
    await reload()
  }catch(error){if(current()&&JSON.stringify(form)===draft)throw error}
  finally{if(editorOperation===operation){editorOperation=null;busy.value=false}}
}
async function submitQuick() {
  await saveEditor('quick',quick,data=>api.quickVitals(data),data=>{
    try{localStorage.setItem('care-measure-period',data.measurePeriod)}catch{/* The reading is saved even when this preference cannot be remembered. */}
    resetQuick()
    quickVisible.value=false
  })
}
function openIntake(i){selectedIntake.value=i;intakeQuantity.value=undefined;intakeVisible.value=true}
async function confirmIntake(){await intakeAction(selectedIntake.value,'TAKEN','',intakeQuantity.value);intakeVisible.value=false}
async function intakeAction(i,status,reason='',quantity){await perform(()=>api.recordIntake(i.id,status,reason,quantity,mode.value==='FAMILY'?'FAMILY':'SELF'))}
async function skipIntake(i){const r=await ElMessageBox.prompt('请输入跳过原因','跳过本次服药').catch(()=>null);if(r)await intakeAction(i,'SKIPPED',r.value)}
function openStock(row){invalidateEditor('stock');Object.assign(stockForm,{id:null,medicationId:null,quantity:0,unit:'片',warningDays:7,warningQuantity:0},row?{id:row.id,medicationId:row.medication_id,quantity:Number(row.quantity),unit:row.unit,warningDays:row.warning_days,warningQuantity:Number(row.warning_quantity)}:{});stockVisible.value=true}
async function submitStock(){await saveEditor('stock',stockForm,data=>api.saveStock(data),()=>{stockVisible.value=false})}
async function purchase(row){const pid=patientId.value,r=await ElMessageBox.prompt(`要补充多少${row.unit}？`,row.drugName,{inputPattern:/^\d+(\.\d{1,3})?$/,inputErrorMessage:'请输入有效数值'}).catch(()=>null);if(r&&pid===patientId.value)await perform(()=>api.purchaseStock({patientId:pid,medicationId:row.medication_id,quantity:Number(r.value),reason:'补充库存'}))}
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
async function copyInvite(){try{await navigator.clipboard.writeText(inviteCode.value);ElMessage.success('邀请码已复制。')}catch{ElMessage.info('请手动选择并复制邀请码。')}}
async function join(){await perform(async()=>{const r=await api.joinCare(joinCode.value.trim(),joinRelation.value);joinVisible.value=false;patientId.value=r.data;window.dispatchEvent(new Event('care-patients-changed'))})}
async function removeMember(row){await perform(()=>api.removeCareMember(patientId.value,row.userId))}
async function saveProfile(){await perform(()=>api.saveCareItem({patientId:patientId.value,kind:'PROFILE',title:'照护设置',details:{...profile}}))}
function memberName(id){return context.value.members?.find(m=>m.userId===id)?.name||'未指定'}
function drugName(id){return context.value.medications?.find(m=>m.id===id)?.drugName||'药品'}
function lastActor(id){const a=context.value.actions?.find(x=>x.intake_id===id);return a?`${a.actor_name||'家庭成员'}${a.recorded_for==='FAMILY'?'为患者记录':'记录'}：${statusText(a.status)}`:''}
function pendingCount(p){return(p.intakes||[]).filter(i=>['PENDING','MISSED','SNOOZED'].includes(i.status)).length+(p.items||[]).filter(i=>['APPOINTMENT','HANDOVER'].includes(i.kind)&&i.status==='OPEN').length}
function nextAppointment(p){const a=(p.items||[]).filter(i=>i.kind==='APPOINTMENT'&&i.status==='OPEN').sort((a,b)=>(a.eventAt||'').localeCompare(b.eventAt||''))[0];return a?`${a.eventAt?.slice(0,16)} ${a.title}`:'暂无后续预约'}
function symptomChange(s){const previous=symptoms.value.filter(x=>x.title===s.title&&x.id!==s.id&&(x.eventAt||'')<(s.eventAt||'')).sort((a,b)=>(b.eventAt||'').localeCompare(a.eventAt||''))[0];if(!previous)return'首次记录';const diff=Number(s.details.severity)-Number(previous.details.severity);return diff===0?'与上次评分相同':`比上次${diff>0?'高':'低'} ${Math.abs(diff)} 分`}
const progressText=s=>({ONGOING:'症状持续',IMPROVED:'已有改善',RESOLVED:'已缓解'}[s]||'未填写')
const statusText=s=>({OPEN:'待处理',DONE:'已完成',CANCELLED:'已取消',ACTIVE:'生效中',SCHEDULED:'已计划',STOPPED:'已停用',ANSWERED:'已答复',RESOLVED:'已解决',PENDING:'待服用',MISSED:'已漏服',SNOOZED:'已稍后提醒',TAKEN:'已服用',SKIPPED:'已跳过'}[s]||s)
const daysText=s=>String(s||'').split(',').map(x=>['','周一','周二','周三','周四','周五','周六','周日'][Number(x)]).join('、')
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
