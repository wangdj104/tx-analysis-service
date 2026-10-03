<template>
  <main class="nurse-workspace"><h1>护理跟进</h1><p>仅显示当前有效分配且具有有效 CARE_PLAN 授权的患者。护理跟进不提供发布临床指导或复核执行记录的操作。</p>
    <template v-if="isNurse"><nav aria-label="护理队列"><button type="button" data-testid="nurse-help" :aria-pressed="queue==='HELP'" @click="selectQueue('HELP')">协助与跟进</button><button type="button" data-testid="nurse-review" :aria-pressed="queue==='REVIEW'" @click="selectQueue('REVIEW')">等待医生复核</button></nav><PlanTaskList :patient-id="null" mode="NURSE" :queue="queue" /></template><p v-else role="status">须具有有效护理角色；管理员在独立入口管理分配。</p>
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
