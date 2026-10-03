<template>
  <main class="nurse-workspace"><h1>Nursing follow-up</h1><p>Only currently assigned patients with valid CARE_PLAN authorization appear. Nursing follow-up does not publish clinical instructions or review execution records.</p>
    <template v-if="isNurse"><nav aria-label="Nursing queues"><button type="button" data-testid="nurse-help" :aria-pressed="queue==='HELP'" @click="selectQueue('HELP')">Help and follow-up</button><button type="button" data-testid="nurse-review" :aria-pressed="queue==='REVIEW'" @click="selectQueue('REVIEW')">Waiting for doctor review</button></nav><PlanTaskList :patient-id="null" mode="NURSE" :queue="queue" /></template><p v-else role="status">A valid nursing role is required. Administrators manage assignments separately.</p>
  </main>
</template>
<script setup>
import {ref} from 'vue'
import PlanTaskList from '@/components/care-plan/PlanTaskList.vue'
const queue=ref('HELP')
let roles=[];try{roles=JSON.parse(localStorage.getItem('userRoleCodes')||'[]')}catch{}
const isNurse=roles.includes('nurse')
function selectQueue(value){if(queue.value===value)return;const event=new Event('care-plan-before-context-change',{cancelable:true});window.dispatchEvent(event);if(!event.defaultPrevented)queue.value=value}
</script>
<style scoped>
.nurse-workspace{box-sizing:border-box;max-width:1200px;padding:24px;margin:auto;min-width:0;color:var(--ink-800)}p{line-height:1.6;overflow-wrap:anywhere}nav{display:flex;gap:12px;flex-wrap:wrap}button{min-height:44px;max-width:100%;padding:10px 14px;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800);font:inherit;cursor:pointer}button[aria-pressed=true]{background:var(--care-100)}button:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}@media(max-width:600px){.nurse-workspace{padding:14px}nav button{width:100%}}
</style>
