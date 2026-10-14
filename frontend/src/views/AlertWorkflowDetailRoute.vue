<template><main class="aw aw-route"><router-link class="aw-link" to="/care">{{t('back')}}</router-link><p v-if="blocked" role="alert">{{t('pendingLeave')}}</p><FollowUpDetail v-if="patientId&&alertId" ref="detail" :patient-id="patientId" :alert-id="alertId" :selected-patient-id="selectedPatientId" /><p v-else>{{t('selectPatient')}}</p></main></template>
<script setup>
import {computed,ref,onMounted,onUnmounted} from 'vue'
import {useRoute,onBeforeRouteLeave,onBeforeRouteUpdate} from 'vue-router'
import {useCurrentPatient} from '@/composables/useCurrentPatient'
import FollowUpDetail from '@/components/alert-workflow/FollowUpDetail.vue'
import {t} from '@/utils/alertWorkflowCopy'
const route=useRoute(),{currentPatientId:selectedPatientId}=useCurrentPatient(),detail=ref(null),blocked=ref(false),alertId=computed(()=>Number(route.params.id))
const validId=value=>Number.isSafeInteger(Number(value))&&Number(value)>0
// Deep links have their own authorized context; they never mutate global patient selection.
const patientId=computed(()=>route.query.patientId===undefined?selectedPatientId.value:validId(route.query.patientId)?Number(route.query.patientId):null)
function guard(){const allowed=detail.value?.guardLeave()!==false;blocked.value=!allowed;return allowed}
function contextChange(event){if(!guard()){event.preventDefault();event.stopImmediatePropagation()}}
onBeforeRouteLeave(guard);onBeforeRouteUpdate(guard)
function beforeUnload(event){if(!guard()){event.preventDefault();event.returnValue=''}}
onMounted(()=>{window.addEventListener('care-plan-before-context-change',contextChange);window.addEventListener('beforeunload',beforeUnload)})
onUnmounted(()=>{window.removeEventListener('care-plan-before-context-change',contextChange);window.removeEventListener('beforeunload',beforeUnload)})
</script>
<style scoped>.aw-route{max-width:1100px;min-width:0;margin:auto;padding:24px}@media(max-width:600px){.aw-route{padding:14px}}</style>
