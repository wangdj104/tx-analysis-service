<template>
 <main class="nurse-workspace aw"><h1>{{t('title')}}</h1><p>{{t('nursingHelp')}}</p>
  <label>{{t('patient')}}<select v-model="currentPatientId"><option :value="null">{{t('selectPatient')}}</option><option v-for="patient in patientList" :key="patient.id" :value="patient.id">{{patient.patientName||patient.name}} (#{{patient.id}})</option></select></label>
  <FollowUpList :patient-id="currentPatientId" />
  <section v-if="careEnabled&&isNurse"><h2>{{t('plans')}}</h2><nav class="aw-controls" :aria-label="t('plans')"><button type="button" data-testid="nurse-help" :aria-pressed="queue==='HELP'" @click="selectQueue('HELP')">{{t('FOLLOW_UP')}}</button><button type="button" data-testid="nurse-review" :aria-pressed="queue==='REVIEW'" @click="selectQueue('REVIEW')">{{t('WAITING_REVIEW')}}</button></nav><PlanTaskList :patient-id="null" mode="NURSE" :queue="queue" /></section>
 </main>
</template>
<script setup>
import {ref,onMounted,onUnmounted} from 'vue'
import PlanTaskList from '@/components/care-plan/PlanTaskList.vue'
import FollowUpList from '@/components/alert-workflow/FollowUpList.vue'
import {useCurrentPatient} from '@/composables/useCurrentPatient'
import {getCarePlanCapabilities} from '@/api/carePlan'
import {captureAuthSession,isAuthSessionCurrent} from '@/utils/authSession'
import {t} from '@/utils/alertWorkflowCopy'
import '@/components/alert-workflow/workflow.css'
const {currentPatientId,patientList}=useCurrentPatient(),queue=ref('HELP'),careEnabled=ref(false),isNurse=ref(false),controller=new AbortController()
onMounted(async()=>{let roles=[];try{roles=JSON.parse(localStorage.getItem('userRoleCodes')||'[]')}catch{}isNurse.value=roles.includes('nurse');const auth=captureAuthSession(),actor=localStorage.getItem('userId');try{const result=await getCarePlanCapabilities({expectedAuth:{...auth,actorId:actor},signal:controller.signal});if(!controller.signal.aborted&&isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId'))careEnabled.value=result.data?.enabled===true}catch{}})
onUnmounted(()=>controller.abort())
function selectQueue(value){if(queue.value===value)return;const event=new Event('care-plan-before-context-change',{cancelable:true});window.dispatchEvent(event);if(!event.defaultPrevented)queue.value=value}
</script>
<style scoped>.nurse-workspace{max-width:1200px;padding:24px;margin:auto}@media(max-width:600px){.nurse-workspace{padding:14px}}</style>
