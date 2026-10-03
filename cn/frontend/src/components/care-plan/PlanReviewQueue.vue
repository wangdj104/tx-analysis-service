<template>
  <section class="plan-review-queue" aria-label="协作计划队列">
    <header><div><h2>协作计划队列</h2><p>执行记录待复核、帮助请求和逾期待执行行动分别列队。</p></div><button type="button" :disabled="state.loading || loading" @click="refresh">刷新队列</button></header>
    <label v-if="enabled">队列 <select v-model="queue"><option value="REVIEW">等待医生复核</option><option value="HELP">已请求帮助</option><option value="OVERDUE">逾期待执行行动</option><option value="TODAY">今日（浏览器本地日期）</option><option value="HISTORY">计划与历史（含私有草稿）</option></select></label>
    <p v-if="state.loading || loading" role="status">正在加载获授权的计划…</p><p v-if="message" role="status">{{ message }}</p>
    <template v-if="enabled"><article v-for="plan in state.items" :key="plan.id"><span>版本 {{ plan.revisionNo }} · {{ revisionLabel(plan.revisionStatus) }} · {{ lifecycleLabel(plan.lifecycle) }}</span><h3>{{ plan.title }}</h3><p>{{ plan.instructions }}</p><ul><li v-for="action in plan.actions || []" :key="action.id || action.ordinal">{{ action.instruction }} · {{ displayTime(action.dueAt) }} · {{ actionLabel(action.status) }}<template v-if="action.overdue"> · 当前逾期</template><template v-if="action.reviewWaitingSince"> · 等待复核始于 {{ displayTime(action.reviewWaitingSince) }}</template></li></ul><button type="button" :data-testid="`open-plan-${plan.id}`" @click="emit('open',plan.id)">打开计划与记录</button></article><p v-if="!state.loading && !state.items.length">此队列暂无获授权的计划。</p><button v-if="state.nextCursor" type="button" data-testid="more-plans" :disabled="state.loading" @click="more">加载更多</button></template>
    <p v-else-if="!loading">当前会话无法使用协作计划。</p>
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
async function initialize(){const g=++generation;controller?.abort();controller=new AbortController();client.reset();enabled.value=false;loading.value=true;message.value='';const auth=captureAuthSession(),actor=localStorage.getItem('userId');try{const response=await getCarePlanCapabilities({expectedAuth:{...auth,actorId:actor},signal:controller.signal});if(g!==generation||!isAuthSessionCurrent(auth)||actor!==localStorage.getItem('userId'))return;enabled.value=!!response.data?.enabled;if(enabled.value)await refresh()}catch{if(g===generation)message.value='当前会话无法加载此队列。'}finally{if(g===generation)loading.value=false}}
async function refresh(){if(!enabled.value)return;const result=await client.load({queue:queue.value});if(result.status==='succeeded')message.value='';else if(result.status!=='stale')message.value='队列不可用，或权限已变化。请在当前会话中刷新。'}
async function more(){const result=await client.load({queue:queue.value,cursor:state.nextCursor});if(result.status==='stale-cursor')await refresh();else if(!['succeeded','stale'].includes(result.status))message.value='下一页加载失败，请刷新队列。'}
function actionLabel(status){return ({OPEN:'待执行或待补充信息',NEEDS_HELP:'已请求帮助',SUBMITTED:'已提交，等待医生复核',CONFIRMED:'记录已复核',SUPERSEDED:'已被新版本替代',CANCELLED:'已取消',DRAFT:'私有草稿'})[status]||'未知状态'}
function lifecycleLabel(status){return ({DRAFT:'私有草稿',ACTIVE:'进行中',COMPLETED:'复核后已关闭',CANCELLED:'已取消'})[status]||'未知状态'}
function revisionLabel(status){return ({DRAFT:'私有草稿',PUBLISHED:'已发布版本'})[status]||'未知状态'}
function displayTime(value){if(!value)return '时间不可用';try{return formatPlanTime(value,'zh-CN')}catch{return '服务器时间无效'}}
watch(()=>props.patientId,initialize,{immediate:true,flush:'sync'})
watch(queue,refresh)
onUnmounted(()=>{generation++;controller?.abort()})
</script>
<style scoped>
.plan-review-queue{min-width:0;color:var(--ink-800)}header{display:flex;gap:12px;justify-content:space-between;flex-wrap:wrap}h2,h3{margin:8px 0}p,li{line-height:1.6;overflow-wrap:anywhere}article{padding:16px;margin:16px 0;border:1px solid var(--line);border-radius:12px;background:var(--surface-subtle)}label{display:grid;gap:6px;font-weight:600;margin:12px 0}select{width:min(420px,100%);min-height:44px;padding:10px;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:var(--ink-800);font:inherit}button{min-height:44px;max-width:100%;padding:10px 14px;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800);font:inherit;cursor:pointer}button:disabled{opacity:.5;cursor:default}button:focus-visible,select:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}@media(max-width:600px){header>div{min-width:0;width:100%}article{padding:12px}}
</style>
