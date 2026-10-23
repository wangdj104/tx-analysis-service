<template>
  <section class="specialty-plans" :aria-label="text.title">
    <header><div><h2>{{ text.title }}</h2><p>{{ text.intro }}</p></div><button type="button" :aria-expanded="expanded" @click="toggle">{{ expanded ? text.hide : text.open }}</button></header>
    <template v-if="expanded">
      <p>{{ text.workflow }}</p><p>{{ text.privacy }}</p>
      <p v-if="!patientId" role="status">{{ text.patient }}</p>
      <template v-else>
        <button v-if="isDoctor && !editing" type="button" @click="editing=true">{{ text.create }}</button>
        <PlanEditor v-if="editing" ref="editor" :patient-id="patientId" :initial-plan-type="planType" @saved="refreshKey++" @closed="closed" />
        <template v-else>
          <PlanReviewQueue v-if="isDoctor" :key="refreshKey" :patient-id="patientId" :plan-type="planType" @open="openPlan" />
          <template v-else><label>{{ text.queue }} <select v-model="queue"><option value="TODAY">{{ text.today }}</option><option value="HELP">{{ text.help }}</option><option value="OVERDUE">{{ text.overdue }}</option><option value="REVIEW">{{ text.review }}</option><option value="HISTORY">{{ text.history }}</option></select></label><PlanTaskList ref="tasks" :key="refreshKey" :patient-id="patientId" :plan-type="planType" :queue="queue" :mode="isNurse ? 'NURSE' : 'PATIENT'" /></template>
        </template>
      </template>
    </template>
  </section>
</template>
<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { AUTH_STORAGE_KEYS, captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'
import PlanEditor from './PlanEditor.vue'
import PlanReviewQueue from './PlanReviewQueue.vue'
import PlanTaskList from './PlanTaskList.vue'
const props=defineProps({patientId:{type:Number,default:null},planType:{type:String,required:true}})
const text={title:'Specialty care plans',intro:'Use the existing care plan to coordinate this specialty.',open:'Open plans',hide:'Hide plans',workflow:'The doctor publishes a plan with responsible people, deadlines and a stage review action. Record execution or use “Ask for help” to explain missed work or an abnormal observation. The doctor reviews each record, can return it for more information, and publishes a new version for manual adjustments. Close the stage only after all records have been reviewed.',privacy:'Published instructions and notes are shared with the currently authorized care plan participants. Choose what to share, especially for mental health. Original specialty records keep their own visibility settings. Reminders do not count as execution.',patient:'Select a patient to continue.',create:'Create a private specialty plan',queue:'Plan queue',today:'Today',help:'Missed work or help requested',overdue:'Overdue open actions',review:'Awaiting doctor review',history:'Plans and history'}
const router=useRouter(),expanded=ref(false),editing=ref(false),editor=ref(null),tasks=ref(null),refreshKey=ref(0),queue=ref('TODAY'),roles=ref([])
let session=captureAuthSession(),actor=localStorage.getItem('userId')
function readRoles(){try{const value=JSON.parse(localStorage.getItem('userRoleCodes')||'[]');roles.value=Array.isArray(value)?value:[]}catch{roles.value=[]}}
readRoles()
const isDoctor=computed(()=>roles.value.includes('doctor')),isNurse=computed(()=>roles.value.includes('nurse'))
function guard(){return (editor.value?.requestLeave?.()??true)&&(tasks.value?.requestLeave?.()??true)}
function toggle(){if(!guard())return;expanded.value=!expanded.value;editing.value=false}
function closed(){editing.value=false;refreshKey.value++}
function openPlan(id){if(Number.isSafeInteger(id)&&id>0)router.push(`/care-plans/${id}`)}
function clear(){expanded.value=false;editing.value=false;refreshKey.value++;queue.value='TODAY'}
function contextChange(event){if(!event.defaultPrevented&&!guard()){event.preventDefault();event.stopImmediatePropagation()}}
function sessionChanged(event){if(event?.type==='storage'&&event.key!==null&&!AUTH_STORAGE_KEYS.includes(event.key))return;if(!isAuthSessionCurrent(session)||actor!==localStorage.getItem('userId')){clear();session=captureAuthSession();actor=localStorage.getItem('userId');readRoles()}}
watch(()=>[props.patientId,props.planType],clear,{flush:'sync'})
onBeforeRouteLeave(guard);onBeforeRouteUpdate(guard)
onMounted(()=>{window.addEventListener('care-plan-before-context-change',contextChange);window.addEventListener('auth-session-cleared',sessionChanged);window.addEventListener('storage',sessionChanged)})
onUnmounted(()=>{window.removeEventListener('care-plan-before-context-change',contextChange);window.removeEventListener('auth-session-cleared',sessionChanged);window.removeEventListener('storage',sessionChanged)})
</script>
<style scoped>
.specialty-plans{min-width:0;margin:18px 0;padding:16px;border:1px solid var(--line);border-radius:12px;background:var(--paper);color:var(--ink-800)}header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;flex-wrap:wrap}h2{font-size:1.2rem;margin:0}p{line-height:1.6;overflow-wrap:anywhere}button,select{font:inherit;min-height:44px;max-width:100%;padding:10px 14px;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:var(--ink-800)}button{cursor:pointer}button:focus-visible,select:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}label{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:12px 0}@media(max-width:600px){.specialty-plans{padding:12px}}
</style>
