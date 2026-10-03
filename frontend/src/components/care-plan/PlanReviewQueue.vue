<template>
  <section class="plan-review-queue" aria-label="Collaboration plan queue">
    <header><div><h2>Collaboration plan queue</h2><p>Execution records awaiting review, requests for help and overdue open actions are kept separate.</p></div><button type="button" :disabled="state.loading || loading" @click="refresh">Refresh queue</button></header>
    <label v-if="enabled">Queue <select v-model="queue"><option value="REVIEW">Waiting for doctor review</option><option value="HELP">Help requested</option><option value="OVERDUE">Overdue open actions</option><option value="TODAY">Today, browser-local day</option><option value="HISTORY">Plans and history, including private drafts</option></select></label>
    <p v-if="state.loading || loading" role="status">Loading authorized plans…</p><p v-if="message" role="status">{{ message }}</p>
    <template v-if="enabled"><article v-for="plan in state.items" :key="plan.id"><span>Version {{ plan.revisionNo }} · {{ revisionLabel(plan.revisionStatus) }} · {{ lifecycleLabel(plan.lifecycle) }}</span><h3>{{ plan.title }}</h3><p>{{ plan.instructions }}</p><ul><li v-for="action in plan.actions || []" :key="action.id || action.ordinal">{{ action.instruction }} · {{ displayTime(action.dueAt) }} · {{ actionLabel(action.status) }}<template v-if="action.overdue"> · Currently overdue</template><template v-if="action.reviewWaitingSince"> · Waiting for review since {{ displayTime(action.reviewWaitingSince) }}</template></li></ul><button type="button" :data-testid="`open-plan-${plan.id}`" @click="emit('open',plan.id)">Open plan and records</button></article><p v-if="!state.loading && !state.items.length">No authorized plans in this queue.</p><button v-if="state.nextCursor" type="button" data-testid="more-plans" :disabled="state.loading" @click="more">Load more</button></template>
    <p v-else-if="!loading">Collaboration plans are unavailable in this session.</p>
  </section>
</template>
<script setup>
import { computed, ref, watch, onUnmounted } from 'vue'
import { useCarePlan } from '@/composables/useCarePlan'
import { getCarePlanCapabilities } from '@/api/carePlan'
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'
import { formatPlanTime } from '@/utils/carePlanTime'
const props=defineProps({patientId:{type:Number,default:null}}),emit=defineEmits(['open'])
const client=useCarePlan(computed(()=>props.patientId)),{state}=client,enabled=ref(false),loading=ref(false),message=ref(''),queue=ref('REVIEW')
let generation=0,controller=null
async function initialize(){const g=++generation;controller?.abort();controller=new AbortController();client.reset();enabled.value=false;loading.value=true;message.value='';const auth=captureAuthSession(),actor=localStorage.getItem('userId');try{const response=await getCarePlanCapabilities({expectedAuth:{...auth,actorId:actor},signal:controller.signal});if(g!==generation||!isAuthSessionCurrent(auth)||actor!==localStorage.getItem('userId'))return;enabled.value=!!response.data?.enabled;if(enabled.value)await refresh()}catch{if(g===generation)message.value='Unable to load this queue in the current session.'}finally{if(g===generation)loading.value=false}}
async function refresh(){if(!enabled.value)return;const result=await client.load({queue:queue.value});if(result.status==='succeeded')message.value='';else if(result.status!=='stale')message.value='The queue is unavailable or access has changed. Refresh in your current session.'}
async function more(){const result=await client.load({queue:queue.value,cursor:state.nextCursor});if(result.status==='stale-cursor')await refresh();else if(!['succeeded','stale'].includes(result.status))message.value='The next page could not be loaded. Refresh the queue.'}
function actionLabel(status){return ({OPEN:'Awaiting execution or more information',NEEDS_HELP:'Help requested',SUBMITTED:'Submitted, awaiting doctor review',CONFIRMED:'Record reviewed',SUPERSEDED:'Replaced by a new version',CANCELLED:'Cancelled',DRAFT:'Private draft'})[status]||'Unknown state'}
function lifecycleLabel(status){return ({DRAFT:'Private draft',ACTIVE:'Active',COMPLETED:'Closed after review',CANCELLED:'Cancelled'})[status]||'Unknown state'}
function revisionLabel(status){return ({DRAFT:'Private draft',PUBLISHED:'Published version'})[status]||'Unknown state'}
function displayTime(value){if(!value)return 'Time unavailable';try{return formatPlanTime(value,'en')}catch{return 'Invalid server time'}}
watch(()=>props.patientId,initialize,{immediate:true,flush:'sync'})
watch(queue,refresh)
onUnmounted(()=>{generation++;controller?.abort()})
</script>
<style scoped>
.plan-review-queue{min-width:0;color:var(--ink-800)}header{display:flex;gap:12px;justify-content:space-between;flex-wrap:wrap}h2,h3{margin:8px 0}p,li{line-height:1.6;overflow-wrap:anywhere}article{padding:16px;margin:16px 0;border:1px solid var(--line);border-radius:12px;background:var(--surface-subtle)}label{display:grid;gap:6px;font-weight:600;margin:12px 0}select{width:min(420px,100%);min-height:44px;padding:10px;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:var(--ink-800);font:inherit}button{min-height:44px;max-width:100%;padding:10px 14px;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800);font:inherit;cursor:pointer}button:disabled{opacity:.5;cursor:default}button:focus-visible,select:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}@media(max-width:600px){header>div{min-width:0;width:100%}article{padding:12px}}
</style>
