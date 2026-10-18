<template>
  <section class="cg cg-panel" aria-labelledby="cg-heading">
    <header><h2 id="cg-heading" ref="heading" tabindex="-1">{{ t('title') }}</h2><p>{{ t('intro') }}</p><p>{{ patientName || t('patient') }} <small v-if="patientId">{{ t('recordId') }}: {{ patientId }}</small></p></header>
    <p v-if="!patientId" role="status">{{ t('noPatient') }}</p>
    <section v-else-if="locator?.invalid || mismatch" class="cg-notice" role="alert"><p>{{ t(locator?.invalid?'invalidLink':'mismatch') }}</p><button type="button" @click="recoverCurrentPatient">{{ t('recover') }}</button></section>
    <template v-else>
      <p role="status" aria-live="polite">{{ t(flow.state.availability) }}</p>
      <p v-if="flow.state.capabilities?.readable && flow.state.capabilities.enabled && !flow.state.capabilities.canWrite">{{ t('readOnly') }}</p>
      <p v-if="flow.state.error && !flow.state.dialog" role="alert">{{ t(flow.state.error) }}</p>
      <div class="cg-actions"><button type="button" :disabled="flow.locked() || flow.state.availability==='loading'" @click="initialize">{{ t('refresh') }}</button><button v-if="flow.allowed('CREATE')" type="button" :disabled="flow.locked()" @click="flow.begin('CREATE')">{{ t('new') }}</button></div>
      <section v-if="flow.state.receipt" class="cg-notice" role="status" aria-live="polite"><h3>{{ t('receipt') }}</h3><p>{{ t('saved') }} {{ t(flow.state.receipt.operation) }} · {{ t('version') }} {{ flow.state.receipt.version }} · {{ t('cycle') }} {{ flow.state.receipt.cycle }} · {{ t(flow.state.receipt.state) }}</p><p v-if="flow.state.detailPhase==='unavailable'">{{ t('savedReadFailure') }}</p></section>
      <p v-if="flow.state.detailPhase==='loading'" role="status">{{ t('loading') }}</p><div v-if="flow.state.detailPhase==='unavailable'" class="cg-notice"><p>{{ t('unavailable') }}</p><button type="button" :disabled="flow.locked()" @click="flow.refreshCurrent">{{ t('refreshCurrent') }}</button></div>
      <CommonGoalDetail :flow="flow" />
      <section v-if="flow.state.capabilities?.readable" :aria-label="t('title')">
        <div class="cg-search"><label for="cg-state">{{ t('filter') }}<select id="cg-state" :value="flow.state.filter" :disabled="flow.locked()" @change="changeFilter"><option v-for="state in ['OPEN','PROPOSED','ACTIVE','COMPLETED','CANCELLED']" :key="state" :value="state">{{ t(state) }}</option></select></label><button type="button" :disabled="flow.locked() || flow.state.list.phase==='loading'" @click="flow.loadList()">{{ t('first') }}</button></div>
        <p v-if="flow.state.list.phase==='loading'" role="status">{{ t('loading') }}</p><p v-else-if="flow.state.list.phase==='unavailable'" role="alert">{{ t('unavailable') }}</p><p v-else-if="flow.state.list.phase==='loaded' && !flow.state.list.items.length">{{ t('empty') }}</p>
        <ul class="cg-list"><li v-for="row in flow.state.list.items" :key="row.goalId" class="cg-card"><h3>{{ row.title }}</h3><p>{{ t(row.state) }} · {{ t(row.responsibilityStatus) }}</p><p>{{ t('nominee') }}: {{ row.nominee ? person(row.nominee) : t('unassigned') }} <small v-if="row.nominee">{{ t('account') }}: {{ row.nominee.userId }}</small></p><p>{{ t('cycle') }} {{ row.cycle }} · {{ t('reviewDate') }}: {{ row.reviewDate || '—' }}</p><button type="button" :disabled="flow.locked()" @click="flow.open(row.goalId)">{{ t('view') }}</button></li></ul>
        <button v-if="flow.state.list.nextCursor" type="button" :disabled="flow.locked() || flow.state.list.phase==='loading'" @click="flow.loadList(true)">{{ t('more') }}</button>
      </section>
    </template>
    <CommonGoalDialog :flow="flow" :patient-name="patientName" :focus-fallback="focusHeading" />
  </section>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useCommonCareGoal } from '@/composables/useCommonCareGoal'
import { goalId } from '@/utils/commonCareGoal'
import { goalText as t, goalPerson as person } from '@/utils/commonCareGoalCopy'
import CommonGoalDetail from './CommonGoalDetail.vue'
import CommonGoalDialog from './CommonGoalDialog.vue'
import './commonCareGoal.css'
const props = defineProps({ patientId: [Number,String], patientName: String, active: { type: Boolean, default: true }, locator: Object })
const router = useRouter(), patient = computed(() => props.patientId), heading = ref(null)
const flow = useCommonCareGoal(patient)
const mismatch = computed(() => !!props.locator?.patientId && props.locator.patientId !== goalId(props.patientId))
let alive = true
function initialize() {
  if (!props.active || !goalId(props.patientId) || props.locator?.invalid || mismatch.value || !flow.protectNavigation()) return
  if (flow.state.receipt && flow.state.targetId) return flow.refreshCurrent()
  return flow.load(props.locator?.goalId || null)
}
watch([patient, () => props.active, () => JSON.stringify(props.locator || null)], () => {
  flow.clear()
  if (props.active) initialize()
}, { immediate: true, flush: 'sync' })
function changeFilter(event) { flow.loadList(false, event.target.value) }
function recoverCurrentPatient() {
  if (!flow.protectNavigation() || !goalId(props.patientId)) return
  return router.replace({ path: '/care', query: { tab: 'goals', patientId: String(props.patientId) } })
}
function focusHeading() { if (alive && props.active && heading.value?.isConnected) heading.value.focus() }
// 普通导航/患者选择先保留脏草稿；真正身份变化立即清空，绝不跨上下文搬运草稿。
const removeGuard = router.beforeEach(() => !props.active || flow.protectNavigation())
function beforeContext(event) { if (props.active && !flow.protectNavigation()) event.preventDefault() }
function beforeUnload(event) { if (props.active && (flow.dirty() || flow.locked())) { event.preventDefault(); event.returnValue = '' } }
async function authChanged() { await nextTick(); if (alive && props.active && localStorage.getItem('token')) initialize() }
window.addEventListener('care-plan-before-context-change', beforeContext)
window.addEventListener('beforeunload', beforeUnload)
window.addEventListener('auth-session-changed', authChanged)
onBeforeUnmount(() => {
  alive = false; removeGuard(); flow.dispose()
  window.removeEventListener('care-plan-before-context-change', beforeContext)
  window.removeEventListener('beforeunload', beforeUnload)
  window.removeEventListener('auth-session-changed', authChanged)
})
defineExpose({ flow, refresh: initialize, protectNavigation: flow.protectNavigation })
</script>
