<template>
  <section v-if="enabled && isAdmin" class="nurse-assignments" aria-labelledby="assignment-heading">
    <h2 id="assignment-heading">护理团队分配</h2><p>分配仅建立照护团队关系。CARE_PLAN 访问仍须患者或记录所有者独立授权；分配不会授予临床访问权限。</p>
    <p v-if="message" role="status">{{message}}</p><p v-if="loading" role="status">正在加载分配记录…</p>
    <form @submit.prevent="assign"><label>有效护理账号编号<input v-model.number="form.nurseUserId" type="number" min="1" step="1" :disabled="busy" required /></label><p>服务器会核实此账号已启用且具有护理角色。</p><label>分配到期时间，须含明确 UTC 偏移（可选）<input v-model="form.expiresAt" type="text" placeholder="2026-11-03T14:30:00+08:00" :disabled="busy" /></label><p v-if="form.expiresAt">{{displayTime(form.expiresAt)}}</p><div class="controls"><button type="submit" data-testid="assign-nurse" :disabled="busy || !patientId" @click.prevent="assign">分配护理人员</button><button type="button" data-testid="reload-assignments" :disabled="busy" @click="load">刷新分配</button></div></form>
    <article v-for="assignment in assignments" :key="assignment.id"><h3>{{assignment.nurseName}} (#{{assignment.nurseUserId}})</h3><p>{{assignment.status==='ACTIVE'?'有效分配':assignment.status==='REVOKED'?'已撤销':'状态不可用'}} · 分配人账号 #{{assignment.assignedBy}} · 开始 {{displayTime(assignment.assignedAt)}} · 到期 {{assignment.expiresAt?displayTime(assignment.expiresAt):'无到期时间'}}</p><button v-if="assignment.status==='ACTIVE'" type="button" :data-testid="`revoke-assignment-${assignment.id}`" :disabled="busy" @click="revoke(assignment)">撤销分配</button></article>
  </section>
</template>
<script setup>
import {ref,reactive,watch,onMounted,onUnmounted} from 'vue'
import {getCarePlanCapabilities,listNurseAssignments,assignNurse,revokeNurseAssignment} from '@/api/carePlan'
import {captureAuthSession,isAuthSessionCurrent,AUTH_STORAGE_KEYS} from '@/utils/authSession'
import {formatPlanTime} from '@/utils/carePlanTime'
const props=defineProps({patientId:{type:Number,required:true}}),enabled=ref(false),isAdmin=ref(false),loading=ref(false),busy=ref(false),assignments=ref([]),message=ref(''),form=reactive({nurseUserId:null,expiresAt:''})
let generation=0,controller=null
const capture=()=>({generation,auth:captureAuthSession(),actor:localStorage.getItem('userId')})
const owns=c=>c.generation===generation&&isAuthSessionCurrent(c.auth)&&c.actor===localStorage.getItem('userId')
const options=c=>({expectedAuth:{...c.auth,actorId:c.actor},signal:controller.signal})
function clear(){generation++;controller?.abort();controller=new AbortController();enabled.value=false;assignments.value=[];busy.value=false;loading.value=false;Object.assign(form,{nurseUserId:null,expiresAt:''});message.value=''}
async function initialize(){clear();try{isAdmin.value=JSON.parse(localStorage.getItem('userRoleCodes')||'[]').includes('admin')}catch{isAdmin.value=false}if(!isAdmin.value||!props.patientId)return;const c=capture();try{const result=await getCarePlanCapabilities(options(c));if(!owns(c)||result.data?.enabled!==true)return;enabled.value=true;await load()}catch{if(owns(c))clear()}}
async function load(){if(!enabled.value||!isAdmin.value)return;const c=capture();loading.value=true;assignments.value=[];try{const result=await listNurseAssignments(props.patientId,options(c));if(owns(c))assignments.value=result.data||[]}catch(error){if(owns(c)){if([401,403,404].includes(Number(error?.response?.status||error?.code)))clear();else assignments.value=[];message.value='当前会话无法使用分配记录。'}}finally{if(owns(c))loading.value=false}}
function displayTime(value){if(!value)return '时间不可用';try{return formatPlanTime(value,'zh-CN')}catch{return '须填写有效且带明确偏移的 ISO 时间。'}}
async function assign(){if(busy.value||!enabled.value||!isAdmin.value||!props.patientId)return;if(!Number.isSafeInteger(form.nurseUserId)||form.nurseUserId<=0){message.value='请输入有效护理账号编号。';return}const expiry=form.expiresAt.trim();if(expiry){try{formatPlanTime(expiry,'zh-CN');if(Date.parse(expiry)<=Date.now())throw new Error('past')}catch{message.value='分配到期时间须为未来的 ISO 时间，且包含明确偏移。';return}}const c=capture();busy.value=true;try{await assignNurse({patientId:props.patientId,nurseUserId:form.nurseUserId,...(expiry?{expiresAt:expiry}:{})},options(c));if(!owns(c))return;message.value='护理人员已分配；患者或记录所有者仍须独立授权 CARE_PLAN 访问。';Object.assign(form,{nurseUserId:null,expiresAt:''});await load()}catch(error){if(owns(c)){if([401,403,404].includes(Number(error?.response?.status||error?.code)))clear();else message.value='分配结果不确定或已被拒绝；请刷新分配记录后重试。'}}finally{if(owns(c))busy.value=false}}
async function revoke(assignment){if(busy.value||!enabled.value||!isAdmin.value||!assignments.value.some(a=>a.id===assignment.id))return;const c=capture();busy.value=true;try{await revokeNurseAssignment(assignment.id,options(c));if(owns(c)){message.value='分配已撤销；患者授权仍作为独立记录保留。';await load()}}catch(error){if(owns(c)){if([401,403,404].includes(Number(error?.response?.status||error?.code)))clear();else assignments.value=[];message.value='无法确定撤销结果，请刷新分配记录。'}}finally{if(owns(c))busy.value=false}}
function authCleared(){clear();isAdmin.value=false}
function storageChanged(event){if(event.key==null||AUTH_STORAGE_KEYS.includes(event.key)||event.key==='userRoleCodes')authCleared()}
watch(()=>props.patientId,initialize,{immediate:true,flush:'sync'})
onMounted(()=>{window.addEventListener('auth-session-cleared',authCleared);window.addEventListener('storage',storageChanged)})
onUnmounted(()=>{clear();window.removeEventListener('auth-session-cleared',authCleared);window.removeEventListener('storage',storageChanged)})
</script>
<style scoped>
.nurse-assignments{min-width:0;padding:16px;color:var(--ink-800)}p,h2,h3{overflow-wrap:anywhere;line-height:1.6}label{display:grid;gap:6px;margin:12px 0}input{box-sizing:border-box;width:100%;min-width:0;min-height:44px;padding:10px;border:1px solid var(--line);border-radius:8px;font:inherit}button{min-height:44px;max-width:100%;padding:10px 14px;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800);font:inherit;cursor:pointer}.controls{display:flex;flex-wrap:wrap;gap:10px}article{margin-top:16px;padding:12px;border:1px solid var(--line);border-radius:10px}button:disabled{opacity:.5}button:focus-visible,input:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}@media(max-width:600px){.nurse-assignments{padding:8px}.controls button{width:100%}}
</style>
