<template>
  <section class="vq" aria-labelledby="vq-heading">
    <header><h2 id="vq-heading">{{ t('title') }}</h2><p>{{ t('intro') }}</p><p>{{ t('patient') }}: {{ patientName || patientId || '—' }} <span v-if="patientName">#{{ patientId }}</span></p><p>{{ t('context') }}: {{ flow.state.selectedContext ? `${t(flow.state.selectedContext.type)} #${flow.state.selectedContext.id} · ${flow.state.selectedContext.date || ''} · ${t(flow.state.selectedContext.status)}` : t('none') }}</p></header>
    <p v-if="locator?.invalid" role="alert">{{ t('invalidLink') }}</p><p v-else-if="!patientId">{{ t('noPatient') }}</p>
    <section v-else-if="patientMismatch" role="alert" class="vq-notice"><p>{{ t('patientLinkMismatch') }}</p><button type="button" @click="returnToCurrentPatient">{{ t('returnToCurrentPatient') }}</button></section>
    <template v-else>
      <p role="status" aria-live="polite">{{ t(flow.state.availability) }}</p>
      <div class="vq-actions"><button type="button" @click="initialize">{{ t('refresh') }}</button><button v-if="flow.allowed('CREATE')" type="button" :disabled="flow.locked()" @click="flow.begin('CREATE')">{{ t('new') }}</button><button v-if="['READY','DISABLED','ABSENT'].includes(flow.state.availability)" type="button" :disabled="flow.locked()" @click="ordinary()">{{ t('manual') }}</button></div>
      <p v-if="flow.state.availability==='READY' && !flow.state.capabilities?.write">{{ t('readOnly') }}</p>
      <p v-if="flow.state.error" role="alert">{{ t(flow.state.error==='QUESTION_LIMIT'?'limit':flow.state.error==='QUESTION_CONTEXT_CHANGED'?'conflict':'unavailable') }}</p>
      <p v-if="flow.state.commandPhase==='saved'" role="status" aria-live="polite">{{ t('saved') }}</p>
      <details v-if="flow.state.availability==='READY'" class="vq-card"><summary>{{ t('context') }}</summary><p>{{ t('contextHelp') }}</p><label>{{ t('context') }}<select v-model="contextType" @change="flow.loadContexts(contextType)"><option v-for="type in contextTypes" :key="type" :value="type">{{ t(type) }}</option></select></label><button type="button" class="vq-context-action vq-context-refresh" @click="flow.loadContexts(contextType)">{{ t('refresh') }}</button><button type="button" class="vq-context-action" @click="selectVisit(null)">{{ t('none') }}</button><p v-if="flow.state.contextOptions.phase==='loading'">{{ t('loading') }}</p><p v-if="flow.state.contextOptions.phase==='unavailable'" role="alert">{{ t('unavailable') }}</p><p v-if="flow.state.contextOptions.fromDate">{{flow.state.contextOptions.fromDate}} / {{flow.state.contextOptions.toDate}}</p><article v-for="context in flow.state.contextOptions.items" :key="`${context.type}:${context.id}`" class="vq-card"><p>{{ t(context.type) }} #{{ context.id }} · {{ context.date }} · {{ t(context.status) }}</p><button type="button" @click="selectVisit(context.type,context.id)">{{ t('selectContext') }}</button></article><button v-if="flow.state.contextOptions.hasMore" type="button" @click="flow.loadContexts(contextType,true)">{{ t('more') }}</button></details>
      <p v-if="flow.state.contextPhase==='unavailable'" role="alert">{{ t('conflict') }}</p>
      <p v-if="flow.state.detailPhase==='loading'" role="status">{{ t('loading') }}</p><p v-if="flow.state.detailPhase==='unavailable'" role="alert">{{ t('unavailable') }}</p>
      <VisitQuestionDetail :flow="flow" @ordinary="ordinary" @close="flow.closeDetail" :can-handover="canHandover" @handover="$emit('handover',$event)" />
      <p>{{ t('legacyCaution') }}</p>
      <section v-for="lane in ['active','closed','legacy']" :key="lane" class="vq-section" :aria-label="t(lane)"><h3>{{ t(lane) }}</h3><template v-if="flow.state[lane].phase==='loaded'"><p v-if="!flow.state[lane].items.length">{{ t('empty') }}</p><article v-for="q in flow.state[lane].items" :key="q.questionId" class="vq-card"><h4>{{ q.title }}</h4><p class="vq-status">{{ q.managed ? t(q.state) : `${t('legacyMark')}: ${q.legacyMark}` }}</p><p>{{ q.description }}</p><p>{{ t('preview') }} · {{ t(q.answerOrigin || 'LEGACY_UNVERIFIED') }}</p><p v-if="q.recordedAnswer">{{ q.recordedAnswer }}</p><p v-if="q.answerRecorderId">{{ t('recorder') }}: {{ person(q.answerRecorderId) }} · {{ q.answerRecordedAt }}</p><p v-if="q.professionalAnswerAvailable">{{ t('professional') }}: {{ t('view') }}</p><p>{{ t('followUp') }}: {{ q.followUp || t('noNext') }}</p><p>{{ t('responsible') }}: {{ q.responsibleUserId == null ? t('unassigned') : person(q.responsibleUserId,true) }} · {{ t('dueDate') }}: {{ q.followUpDueDate || '—' }}</p><button type="button" :disabled="flow.locked()" @click="flow.open(q.questionId,!q.managed)">{{ t('view') }}</button></article><div class="vq-actions"><button v-if="flow.state[lane].hasMore" type="button" @click="flow.loadPage(lane,true)">{{ t('more') }}</button><button type="button" @click="flow.loadPage(lane)">{{ t('first') }}</button></div></template><p v-else role="status">{{ t(flow.state[lane].phase==='idle'?'unavailable':flow.state[lane].phase) }}</p></section>
    </template>
    <VisitQuestionActionDialog :flow="flow" />
    <section v-if="flow.state.navigationBlocked && !flow.state.dialog" role="alert"><p>{{ t('dirty') }}</p></section>
  </section>
</template>
<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useVisitQuestion } from '@/composables/useVisitQuestion'
import { contextTypes, parseQuestionRoute, questionLink, questionId } from '@/api/visitQuestion'
import { questionText as t } from '@/utils/visitQuestionCopy'
import VisitQuestionDetail from './VisitQuestionDetail.vue'
import VisitQuestionActionDialog from './VisitQuestionActionDialog.vue'
const props = defineProps({ patientId: [Number,String], patientName: String, active: { type: Boolean, default: true }, locator: Object, canHandover: Boolean })
const emit = defineEmits(['ordinary','handover'])
const contextType = ref('CARE_APPOINTMENT'), patient = computed(() => props.patientId), routeKey = computed(() => JSON.stringify(props.locator || null))
const flow = useVisitQuestion(patient,{contextKey:routeKey}), router = useRouter()
const currentPatientId = computed(() => questionId(props.patientId)), patientMismatch = computed(() => !!currentPatientId.value && !!props.locator?.patientId && Number(props.locator.patientId)!==currentPatientId.value)
let generation = 0
async function initialize() {
  if (!flow.protectNavigation()) return
  const run = ++generation
  if (!props.active || !props.patientId || props.locator?.invalid) return
  if (props.locator?.patientId && Number(props.locator.patientId)!==Number(props.patientId)) { flow.clear(); flow.state.error='QUESTION_IDENTITY_INVALID'; return }
  if (props.locator?.contextType) await flow.selectContext(props.locator.contextType,props.locator.contextId)
  else { await flow.load(); if (run!==generation || flow.state.availability!=='READY') return; if(props.locator?.questionId) await flow.open(props.locator.questionId) }
}
watch([patient,()=>props.active,routeKey],()=>{generation++;flow.clear();if(props.active)initialize()},{immediate:true,flush:'sync'})
function ordinary(row) { if(!flow.protectNavigation())return;emit('ordinary',row || null) }
function selectVisit(type, id) {
  const target=type ? questionLink(props.patientId,type,id) : `/care?patientId=${props.patientId}&tab=questions`
  if(JSON.stringify(parseQuestionRoute(target))===routeKey.value)return initialize()
  flow.clear();return router.replace(target)
}
function returnToCurrentPatient() { if (currentPatientId.value && patientMismatch.value) return selectVisit(null) }
function person(id,responsible=false) {return `${Number(id)===Number(flow.state.capabilities?.actorId)?t('me'):responsible?t('currentResponsible'):t('recorder')} (${t('account')} ${id})`}
const removeGuard = router.beforeEach((to) => {
  const locator = parseQuestionRoute(to.fullPath)
  // 身份或精确就诊切换立即清除，普通离开当前上下文才询问是否放弃草稿。
  if (locator && (locator.invalid || Number(locator.patientId)!==Number(props.patientId) || JSON.stringify(locator)!==routeKey.value)) { generation++;flow.clear();return true }
  return flow.protectNavigation()
})
function beforeUnload(event) {if(flow.dirty()){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{generation++;removeGuard();window.removeEventListener('beforeunload',beforeUnload)})
defineExpose({ flow, refresh:initialize, protectNavigation:flow.protectNavigation, open:flow.open })
</script>
<style>
/* 复用六种外观的语义颜色；状态同时使用文字，不借颜色暗示医疗结论。 */
.vq{color:var(--ink-950);min-width:0;max-width:100%;overflow-wrap:anywhere;line-height:1.65}.vq *{box-sizing:border-box}.vq h2,.vq h3,.vq h4,.vq h5{line-height:1.45}.vq h4,.vq h5{font-size:1rem}.vq p,.vq dd{white-space:pre-wrap;overflow-wrap:anywhere}.vq-card{border:1px solid var(--line);border-radius:12px;background:var(--paper);padding:16px;margin:14px 0;min-width:0}.vq-notice{border:1px solid var(--warning);background:var(--surface-subtle);padding:12px;border-radius:8px}.vq-status{font-weight:650;color:var(--info)}.vq button,.vq a,.vq summary{min-height:44px;display:inline-flex;align-items:center;justify-content:center;max-width:100%;white-space:normal;overflow-wrap:anywhere;line-height:1.5;padding:9px 14px;cursor:pointer}.vq button{border:1px solid var(--line);border-radius:8px;background:var(--paper);color:var(--ink-950);font:inherit}.vq :is(button,input,select,textarea):focus-visible{outline:3px solid var(--info);outline-offset:2px}.vq button:disabled{opacity:.6;cursor:default}.vq-actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}.vq label{display:flex;flex-direction:column;gap:6px;margin:12px 0}.vq input,.vq select,.vq textarea{font:inherit;color:var(--ink-950);background:var(--paper);border:1px solid var(--line);border-radius:6px;padding:10px;width:100%;min-height:44px;max-width:100%}.vq textarea{resize:vertical}.vq .vq-check{flex-direction:row;align-items:center;min-height:44px}.vq input[type=checkbox]{width:22px;height:22px;min-height:22px;flex-shrink:0}.vq fieldset{border:0;padding:0;margin:0;min-width:0}.vq .vq-error{color:var(--danger);margin-top:4px}.vq dl{display:grid;grid-template-columns:minmax(100px,1fr) minmax(0,3fr);gap:8px}.vq dd{margin:0;min-width:0}.vq pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word}.vq-section{margin-top:24px}.care-senior .vq{font-size:20px}.care-senior .vq :is(button,a,summary,input,select,textarea){min-height:48px;min-inline-size:48px;font-size:inherit}.care-senior .vq input[type=checkbox]{min-height:24px}.vq-form{max-width:100%}@media(max-width:600px){.vq-actions{display:grid;grid-template-columns:minmax(0,1fr)}.vq-actions button{width:100%}.vq dl{grid-template-columns:minmax(0,1fr)}.vq-card{padding:12px}.vq-form footer{display:grid;grid-template-columns:1fr}.vq-form label{min-width:0}}
</style>

<style scoped>
.vq-context-action {
  vertical-align: top;
  margin-block-end: 10px;
}
.vq-context-refresh {
  margin-inline-end: 10px;
  max-width: calc(100% - 10px);
}
</style>
