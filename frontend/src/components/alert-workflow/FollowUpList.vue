<template>
 <section class="aw aw-panel" aria-labelledby="follow-up-heading">
  <h2 id="follow-up-heading">{{t('title')}}</h2><p>{{t('privacy')}}</p>
  <p v-if="!patientId" role="status">{{t('selectPatient')}}</p>
  <template v-else>
   <div class="aw-controls"><label>{{t('state')}}<select v-model="queue" :disabled="state.loading" @change="flow.load({queue})"><option v-for="key in queues" :key="key" :value="key">{{t(key)}}</option></select></label><button type="button" @click="refresh" :disabled="state.loading">{{t('refresh')}}</button></div>
   <p v-if="state.capabilityPhase==='loading'||state.loading" role="status">{{t('loading')}}</p>
   <p v-else-if="state.capabilityPhase==='disabled'" role="status">{{t('featureOff')}}</p>
   <p v-else-if="state.capabilityPhase==='denied'" role="alert">{{t('denied')}}</p>
   <p v-else-if="state.capabilityPhase!=='enabled'" role="alert">{{t('capabilityUnknown')}}</p>
   <template v-else>
    <p v-if="state.error" role="alert">{{t(state.error)}}</p>
    <p v-if="!state.items.length&&!state.loading">{{t('empty')}}</p>
    <article v-for="item in state.items" :key="item.alertId">
     <h3>{{t(item.sourceType)}} #{{item.sourceId}} · {{t(item.metric)}}</h3><p>{{t(item.mode)}} · {{t(item.state)}} · {{t('patient')}} #{{item.patient.id}}</p>
     <p>{{t('due')}}: <time>{{item.dueAt?formatWorkflowTime(item.dueAt):t('noDeadline')}}</time></p><p v-if="item.blockers?.length">{{item.blockers.map(t).join(' · ')}}</p>
     <router-link class="aw-link" :to="`/alert-workflows/${item.alertId}?patientId=${item.patient.id}`">{{t('open')}} #{{item.alertId}}</router-link>
    </article>
    <button v-if="state.nextCursor" type="button" :disabled="state.loading" @click="flow.load({queue,cursor:state.nextCursor})">{{t('more')}}</button>
   </template>
  </template>
 </section>
</template>
<script setup>
import {formatWorkflowTime} from '@/utils/alertWorkflowTime'
import {ref,toRef,watch} from 'vue'
import {useAlertWorkflow} from '@/composables/useAlertWorkflow'
import {t} from '@/utils/alertWorkflowCopy'
import './workflow.css'
const props=defineProps({patientId:{type:Number,default:null}}),flow=useAlertWorkflow(toRef(props,'patientId')),state=flow.state,queue=ref('TODAY')
const queues=['TODAY','ALL','WAITING_ACCEPTANCE','ASSIGNED_TO_ME','WAITING_FEEDBACK','WAITING_REVIEW','BLOCKED']
async function refresh(){const result=await flow.loadCapabilities();if(result.status==='enabled')await flow.load({queue:queue.value})}
watch(()=>props.patientId,refresh,{immediate:true})
</script>
