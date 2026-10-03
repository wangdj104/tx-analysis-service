<template>
  <dialog ref="dialog" class="receipt-dialog" aria-labelledby="receipt-heading" @cancel.prevent="requestClose">
    <h2 id="receipt-heading">{{ commandLabel }}</h2>
    <template v-if="state.editor && visibleAction">
      <p>{{ visibleAction.instruction }}</p><p>录入账号：{{ actorName }} (#{{ actorId }}). 账号身份不等于患者身份认证。</p>
      <p v-if="command==='submitReceipt'">默认代为录入，包括使用本账号管理其他家庭成员的情况。</p>
      <form @submit.prevent="submit">
        <label>说明（{{ command==='submitReceipt' ? '1–2000' : '1–1000' }} 字）<textarea v-model="form.note" rows="4" data-testid="receipt-note" :disabled="busy" /></label>
        <template v-if="command==='submitReceipt'">
          <label>实际执行时间，须含明确的 UTC 偏移<input v-model="form.occurredAt" type="text" placeholder="2026-10-03T14:30:00+08:00" :disabled="busy" /></label><p>{{ displayTime(form.occurredAt) }}</p>
          <label>录入声明<select v-model="form.entryMode" :disabled="busy"><option v-if="entryModes.includes('ASSISTED')" value="ASSISTED">代为录入</option><option v-if="entryModes.includes('SELF')" value="SELF">我明确声明由账号所有者本人录入</option></select></label>
          <p v-if="form.entryMode==='SELF'">这是您的录入声明，并非患者身份认证。</p>
          <fieldset :disabled="busy"><legend>仅可引用既有记录（最多 5 条；不可上传）</legend><div v-for="(e,index) in form.evidence" :key="index" class="evidence-row"><select v-model="e.sourceType" aria-label="引用类型"><option value="MEASUREMENT">健康测量</option><option value="MEDICAL_RECORD">医疗记录</option></select><input v-model.number="e.sourceId" type="number" min="1" step="1" aria-label="既有记录编号" /><button type="button" @click="form.evidence.splice(index,1)">移除引用</button></div><button type="button" :disabled="form.evidence.length>=5" @click="form.evidence.push({sourceType:'MEASUREMENT',sourceId:null})">添加既有引用</button><p>服务器会验证患者归属和当前权限；引用不会授予访问权限。</p></fieldset>
        </template>
        <label v-if="command==='followUp'">跟进类型<select v-model="form.kind" :disabled="busy"><option value="CONTACTED">已联系</option><option value="AWAITING_INFORMATION">等待补充信息</option><option value="DOCTOR_NOTIFIED">已通知医生</option></select></label>
        <p v-if="!canSubmit" role="status">此操作只读或不可用，需要有效的当前权限。</p>
        <div class="controls"><button v-if="canSubmit" type="submit" data-testid="submit-receipt" :disabled="busy || unresolved || needsReload" @click.prevent="submit">提交记录</button><button type="button" data-testid="close-receipt" @click="requestClose">关闭</button><button v-if="needsReload" type="button" data-testid="reload-receipt" :disabled="busy" @click="reloadCurrent">重新加载服务器状态</button></div>
      </form>
    </template>
    <p v-if="message" role="status">{{ message }}</p>
    <section v-if="unresolved" class="notice" role="alert"><p>结果不明，原提交可能已存在。重试会发送原始冻结命令和原始输入，即使您已修改此表单。</p><button type="button" data-testid="retry-original" :disabled="busy" @click="retryOriginal">重试原始提交</button></section>
    <section v-if="abandonIntent" class="notice" role="alert"><p>关闭不会取消服务器提交，可能丢失重试键。发起新命令前，请确认并重新加载服务器状态。</p><button type="button" data-testid="confirm-abandon" :disabled="busy" @click="confirmAbandon">确认并重新加载</button><button type="button" data-testid="keep-editing" @click="abandonIntent=false">继续编辑</button></section>
    <button v-if="!state.editor" type="button" data-testid="close-receipt" @click="emit('closed')">关闭</button>
  </dialog>
</template>
<script setup>
import {computed,ref,reactive,watch,onMounted,onUnmounted,nextTick} from 'vue'
import {useCarePlan} from '@/composables/useCarePlan'
import {formatPlanTime} from '@/utils/carePlanTime'
import {captureAuthSession,isAuthSessionCurrent} from '@/utils/authSession'
const props=defineProps({action:{type:Object,required:true},planVersion:{type:Number,required:true},command:{type:String,default:'submitReceipt'}}),emit=defineEmits(['submitted','closed'])
const patientId=computed(()=>props.action?.patientId??null),client=useCarePlan(patientId),{state}=client,dialog=ref(null),message=ref(''),abandonIntent=ref(false),needsReload=ref(false),visibleAction=ref(null),actorName=ref(''),actorId=ref('')
const form=reactive({note:'',occurredAt:'',entryMode:'ASSISTED',evidence:[],kind:'CONTACTED'})
let generation=0,opener=null,currentVersion=null
const names={submitReceipt:'SUBMIT_RECEIPT',requestHelp:'REQUEST_HELP',followUp:'FOLLOW_UP'}
const commandLabel=computed(()=>({submitReceipt:'记录执行情况',requestHelp:'向照护团队请求协助',followUp:'记录护理跟进'})[props.command]||'操作不可用')
const busy=computed(()=>state.commandPhase==='pending'||state.opening),unresolved=computed(()=>state.commandPhase==='unknown')
const entryModes=computed(()=>visibleAction.value?.allowedEntryModes||[])
const canSubmit=computed(()=>!!state.editor&&visibleAction.value?.allowedActions?.includes(names[props.command])&&(props.command!=='submitReceipt'||entryModes.value.includes(form.entryMode)))
const payload=()=>props.command==='submitReceipt'?{note:form.note.trim(),occurredAt:form.occurredAt,entryMode:form.entryMode,evidence:JSON.parse(JSON.stringify(form.evidence))}:props.command==='followUp'?{note:form.note.trim(),kind:form.kind}:{note:form.note.trim()}
const owns=c=>c.generation===generation&&isAuthSessionCurrent(c.auth)&&c.actor===localStorage.getItem('userId')
const capture=()=>({generation,auth:captureAuthSession(),actor:localStorage.getItem('userId')})
function displayTime(value){try{return formatPlanTime(value,'zh-CN')}catch{return '请输入有效且带明确偏移的 ISO 时间。'}}
function clearForm(){Object.assign(form,{note:'',occurredAt:'',entryMode:'ASSISTED',evidence:[],kind:'CONTACTED'});visibleAction.value=null;actorName.value='';actorId.value='';abandonIntent.value=false;needsReload.value=false}
async function initialize(){generation++;client.reset();clearForm();message.value='';visibleAction.value=props.action;currentVersion=props.planVersion;actorId.value=localStorage.getItem('userId')||'';actorName.value=localStorage.getItem('realName')||localStorage.getItem('username')||'当前账号';Object.assign(form,{occurredAt:new Date().toISOString()});await client.open(null,{draft:payload()})}
watch(()=>[props.action?.id,props.action?.patientId,props.planVersion,props.command],initialize,{immediate:true,flush:'sync'})
watch(form,()=>{if(state.editor)state.draft=payload()},{deep:true,flush:'sync'})
watch(()=>state.editor,editor=>{if(!editor){clearForm()}},{flush:'sync'})
function validate(){const count=Array.from(form.note.trim()).length,max=props.command==='submitReceipt'?2000:1000;if(count<1||count>max)return false;if(!Number.isSafeInteger(currentVersion)||currentVersion<0)return false;if(props.command==='followUp')return ['CONTACTED','AWAITING_INFORMATION','DOCTOR_NOTIFIED'].includes(form.kind);if(props.command!=='submitReceipt')return props.command==='requestHelp';try{formatPlanTime(form.occurredAt,'zh-CN')}catch{return false}const fraction=form.occurredAt.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/)?.[1]||'';if(BigInt(Date.parse(form.occurredAt))*1000n+BigInt(fraction.padEnd(6,'0').slice(3,6))>BigInt(Date.now())*1000n)return false;const refs=form.evidence;if(refs.length>5)return false;const keys=new Set();for(const e of refs){if(!['MEASUREMENT','MEDICAL_RECORD'].includes(e.sourceType)||!Number.isSafeInteger(e.sourceId)||e.sourceId<=0)return false;const key=e.sourceType+':'+e.sourceId;if(keys.has(key))return false;keys.add(key)}return entryModes.value.includes(form.entryMode)}
function feedback(result){message.value=({unknown:'服务器结果不明，请重试原始提交或确认重新加载服务器。',conflict:'计划已变化，输入已保留；再次提交前请重新加载。',forbidden:'权限已撤销，临床内容已清除。',unauthenticated:'请重新登录以继续。',unavailable:'此操作不可用。',cancelled:'请求未发送。',stale:'上下文或输入已变化；发起其他命令前请重新加载服务器状态。',failed:'服务器已拒绝此输入，请修改后重试。'})[result.status]||'无法提交此记录。';if(['conflict','stale'].includes(result.status))needsReload.value=true}
async function submit(){if(!canSubmit.value||busy.value||unresolved.value||needsReload.value)return;if(!validate()){message.value='请检查说明长度、录入声明、带明确偏移且不晚于现在的执行时间，以及最多 5 条不重复的既有记录引用。';return}const c=capture();state.draft=payload();const result=await client.runCommand(props.command,{id:visibleAction.value.id,expectedVersion:currentVersion,payload:state.draft});if(owns(c))await settle(result)}
async function settle(result){if(result.status!=='succeeded'){feedback(result);return}if(result.draftUnchanged===false){message.value='原始提交已成功，您更新的输入已保留。';needsReload.value=true;await reloadCurrent();return}message.value='记录已提交，执行回执等待医生复核。';emit('submitted',result.data);emit('closed')}
async function retryOriginal(){if(!unresolved.value||busy.value)return;const c=capture(),result=await client.runCommand(props.command,{retry:true});if(owns(c))await settle(result)}
async function reloadCurrent(){const c=capture(),input=payload(),id=visibleAction.value?.planId??props.action.planId;needsReload.value=true;if(!id){message.value='创建其他记录前请重新加载计划。';return false}const result=await client.open(id,{draft:input});if(!owns(c))return false;if(result.status!=='succeeded'){feedback(result);return false}const action=state.plan?.actions?.find(a=>a.id===props.action.id);visibleAction.value=action||null;currentVersion=state.plan.version;needsReload.value=false;if(!action||!action.allowedActions?.includes(names[props.command]))message.value='服务器状态已重新加载；此操作现在只读或不可用。';else message.value='服务器状态已重新加载，本地输入已保留。';return true}
function requestClose(){if(unresolved.value||busy.value){abandonIntent.value=true;return}emit('closed')}
async function confirmAbandon(){if(busy.value)return;abandonIntent.value=false;await reloadCurrent();emit('closed')}
function guardLeave(){if(!unresolved.value&&!busy.value)return true;return window.confirm('提交可能已存在。离开会丢失重试键，且不会取消服务器命令；再次提交前请重新加载服务器状态。仍要离开吗？')}
function contextChange(event){if(!guardLeave())event.preventDefault()}
function beforeUnload(event){if(unresolved.value||busy.value){event.preventDefault();event.returnValue=''}}
onMounted(async()=>{opener=document.activeElement;await nextTick();dialog.value?.showModal?.();window.addEventListener('beforeunload',beforeUnload);window.addEventListener('care-plan-before-context-change',contextChange)})
onUnmounted(()=>{generation++;window.removeEventListener('beforeunload',beforeUnload);window.removeEventListener('care-plan-before-context-change',contextChange);dialog.value?.close?.();opener?.focus?.()})
defineExpose({guardLeave,requestClose})
</script>
<style scoped>
.receipt-dialog{box-sizing:border-box;width:min(640px,94vw);max-height:90dvh;overflow:auto;border:1px solid var(--line);border-radius:14px;padding:20px;background:var(--paper);color:var(--ink-800)}.receipt-dialog::backdrop{background:#10282c88}h2,p{overflow-wrap:anywhere}p{line-height:1.6}label{display:grid;gap:6px;margin:14px 0}textarea,input,select{width:100%;min-width:0;box-sizing:border-box;min-height:44px;padding:10px;border:1px solid var(--line);border-radius:8px;font:inherit;background:var(--paper);color:inherit}button{min-height:44px;max-width:100%;padding:10px 14px;cursor:pointer;font:inherit;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800)}button:disabled{opacity:.5;cursor:default}button:focus-visible,textarea:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}.controls{display:flex;gap:10px;flex-wrap:wrap}.notice{padding:12px;margin:12px 0;border:1px solid var(--line);background:var(--care-50)}fieldset{min-width:0;margin:12px 0;border:1px solid var(--line)}.evidence-row{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:10px 0}.evidence-row button{grid-column:1/-1}@media(max-width:600px){.receipt-dialog{padding:14px}.controls button{width:100%}.evidence-row{grid-template-columns:1fr}}
</style>
