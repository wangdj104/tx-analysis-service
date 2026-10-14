<template>
 <section class="aw aw-panel" v-if="enabled">
  <h2>{{t('nurseGovernance')}}</h2><p>{{t('assignmentHelp')}}</p><p v-if="message" role="status">{{t(message)}}</p>
  <form v-if="canAdmin" @submit.prevent="assign"><label>{{t('chooseNurse')}}<select v-model.number="form.nurseUserId" :disabled="busy" required><option :value="null">{{t('chooseNurse')}}</option><option v-for="nurse in candidates" :key="nurse.userId" :value="nurse.userId">{{nurse.displayName}} (#{{nurse.userId}})</option></select></label><label>{{t('assignmentExpiry')}}<input v-model="form.expiresAt" type="text" :disabled="busy" placeholder="2026-12-01T14:30:00+08:00" /></label><button type="submit" :disabled="busy||uncertain||!patientId">{{t('assignNurse')}}</button></form>
  <article v-if="receipt"><h3>{{t('receipt')}}</h3><p>{{t('assignmentId')}} #{{receipt.id}} · {{receipt.nurseName}} (#{{receipt.nurseUserId}}) · {{t(receipt.status)}}</p><p>{{formatWorkflowTime(receipt.assignedAt)}} · {{formatWorkflowTime(receipt.expiresAt)}}</p></article>
  <form v-if="canAdmin" @submit.prevent="revoke"><label>{{t('assignmentId')}}<input v-model.number="revokeId" type="number" min="1" step="1" :disabled="busy" required /></label><button type="submit" :disabled="busy">{{t('revokeKnown')}}</button></form>
  <template v-if="historyAllowed"><button type="button" :disabled="busy" @click="loadHistory">{{t('loadHistory')}}</button><article v-for="assignment in history" :key="assignment.id"><p>#{{assignment.id}} · {{assignment.nurseName}} (#{{assignment.nurseUserId}}) · {{t(assignment.status)}}</p><p>{{formatWorkflowTime(assignment.assignedAt)}} · {{formatWorkflowTime(assignment.expiresAt)}}</p></article></template>
 </section>
</template>
<script setup>
import {formatWorkflowTime} from '@/utils/alertWorkflowTime'
import {ref,reactive,watch,onMounted,onUnmounted} from 'vue'
import * as api from '@/api/alertWorkflow'
import {captureAuthSession,isAuthSessionCurrent,AUTH_STORAGE_KEYS} from '@/utils/authSession'
import {t} from '@/utils/alertWorkflowCopy'
import './workflow.css'
const props=defineProps({patientId:{type:Number,required:true},historyAllowed:{type:Boolean,default:false}}),enabled=ref(false),canAdmin=ref(false),busy=ref(false),uncertain=ref(false),candidates=ref([]),history=ref([]),receipt=ref(null),revokeId=ref(null),message=ref(null),form=reactive({nurseUserId:null,expiresAt:''})
let epoch=0,controller=null
function clear(){epoch++;controller?.abort();controller=new AbortController();enabled.value=false;canAdmin.value=false;busy.value=false;uncertain.value=false;candidates.value=[];history.value=[];receipt.value=null;revokeId.value=null;message.value=null;Object.assign(form,{nurseUserId:null,expiresAt:''})}
const capture=()=>({epoch,patientId:props.patientId,auth:captureAuthSession(),actorId:localStorage.getItem('userId')})
const owns=c=>c.epoch===epoch&&c.patientId===props.patientId&&isAuthSessionCurrent(c.auth)&&c.actorId===localStorage.getItem('userId')
const options=c=>({expectedAuth:{...c.auth,actorId:c.actorId},signal:controller.signal})
function failed(error,c){if(!owns(c))return;if([401,403].includes(Number(error?.response?.status||error?.code))){clear();message.value='denied'}else message.value='assignmentUncertain'}
async function initialize(){clear();if(!props.patientId)return;const c=capture();try{const result=await api.capabilities(null,options(c));if(!owns(c)||result.data?.enabled!==true)return;enabled.value=true;let roles=[];try{roles=JSON.parse(localStorage.getItem('userRoleCodes')||'[]')}catch{}if(roles.includes('admin')){const response=await api.nurseCandidates(options(c));if(owns(c)){candidates.value=response.data||[];canAdmin.value=true}}}catch(e){failed(e,c)}}
async function assign(){if(busy.value||uncertain.value||!canAdmin.value)return;const c=capture(),expiry=form.expiresAt.trim();if(!candidates.value.some(n=>n.userId===form.nurseUserId)||(expiry&&(!/(Z|[+-]\d{2}:\d{2})$/.test(expiry)||!Number.isFinite(Date.parse(expiry))||Date.parse(expiry)<=Date.now()))){message.value='inputInvalid';return}busy.value=true;try{const result=await api.assignNurse({patientId:props.patientId,nurseUserId:form.nurseUserId,...(expiry?{expiresAt:expiry}:{})},options(c));if(owns(c)){receipt.value=result.data;revokeId.value=result.data.id;message.value='assignmentHelp'}}catch(e){if(owns(c))uncertain.value=true;failed(e,c)}finally{if(owns(c))busy.value=false}}
async function revoke(){if(busy.value||!canAdmin.value||!Number.isSafeInteger(revokeId.value)||revokeId.value<=0)return;const c=capture(),id=revokeId.value;busy.value=true;try{await api.revokeNurse(id,options(c));if(owns(c)){if(receipt.value?.id===id)receipt.value={...receipt.value,status:'REVOKED'};message.value='succeeded'}}catch(e){failed(e,c)}finally{if(owns(c))busy.value=false}}
// Admin status never authorizes this read. The parent must pass current owner/doctor capability.
async function loadHistory(){if(!props.historyAllowed||!enabled.value)return;const c=capture();history.value=[];try{const result=await api.nurseAssignments(c.patientId,options(c));if(owns(c)&&props.historyAllowed)history.value=result.data||[]}catch(e){failed(e,c)}}
watch(()=>[props.patientId,props.historyAllowed],initialize,{immediate:true,flush:'sync'})
const storage=e=>{if(e.key==null||AUTH_STORAGE_KEYS.includes(e.key)||e.key==='userRoleCodes')clear()}
onMounted(()=>{window.addEventListener('auth-session-cleared',clear);window.addEventListener('storage',storage)})
onUnmounted(()=>{clear();window.removeEventListener('auth-session-cleared',clear);window.removeEventListener('storage',storage)})
</script>
