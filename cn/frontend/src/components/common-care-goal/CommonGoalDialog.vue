<template>
  <dialog ref="dialogElement" class="cg cg-dialog" aria-labelledby="cg-dialog-heading" aria-modal="true" @cancel.prevent="requestClose" @keydown="trapFocus">
    <form v-if="dialog" autocomplete="off" @submit.prevent="submit">
      <header class="cg-row"><h2 id="cg-dialog-heading">{{ t(dialog.operation) }}</h2><button type="button" @click="requestClose">{{ t('close') }}</button></header>
      <p>{{ patientName || t('patient') }} <small>{{ t('recordId') }}: {{ flow.state.patientId }}</small></p>
      <p>{{ t('ownerHelp') }}</p>
      <CommonGoalSnapshot v-if="dialog.baseline" :goal="dialog.baseline.goal" :nominee="dialog.baseline.nominee" />
      <p v-if="['REVISE','REOPEN'].includes(dialog.operation)" class="cg-notice">{{ t('newCycle') }}</p>
      <section v-if="dialog.operation==='COMPLETE'" class="cg-notice"><CommonGoalEntry :entry="dialog.baseline.latestProgress" heading="progress" /><CommonGoalEntry :entry="dialog.baseline.latestFeedback" heading="feedback" /><p>{{ t('completionHelp') }}</p></section>
      <p v-if="phaseMessage" role="status" aria-live="polite" class="cg-notice">{{ t(phaseMessage) }}</p>
      <p v-if="flow.state.error && flow.state.error!==flow.state.candidateError" role="alert">{{ t(flow.state.error) }}</p>
      <section v-if="flow.state.navigationBlocked" class="cg-notice" role="alert">
        <p>{{ t(flow.locked() ? 'lockedLeave' : 'dirty') }}</p><div class="cg-actions"><button type="button" @click="flow.state.navigationBlocked=false">{{ t('keepEditing') }}</button><button v-if="!flow.locked()" type="button" @click="flow.closeDialog(true)">{{ t('discard') }}</button></div>
      </section>
      <fieldset :disabled="flow.locked() || ['conflict','invalid'].includes(flow.state.phase)">
        <template v-if="['CREATE','REVISE'].includes(dialog.operation)">
          <label v-for="field in contentFields" :key="field.key" :for="`cg-${field.key}`">{{ t(field.label || field.key) }} ({{ field.max }})
            <input v-if="field.key==='title'" :id="`cg-${field.key}`" v-model="draft[field.key]" :aria-invalid="!!errors[field.key]" :aria-describedby="`cg-error-${field.key}`" />
            <textarea v-else :id="`cg-${field.key}`" v-model="draft[field.key]" rows="3" :aria-invalid="!!errors[field.key]" :aria-describedby="`cg-error-${field.key}`" />
            <span v-if="errors[field.key]" :id="`cg-error-${field.key}`" class="cg-error">{{ t(errors[field.key]) }}</span>
          </label>
          <label for="cg-reviewDate">{{ t('reviewDate') }}<input id="cg-reviewDate" v-model="draft.reviewDate" type="date" :aria-invalid="!!errors.reviewDate" aria-describedby="cg-error-reviewDate" /><span v-if="errors.reviewDate" id="cg-error-reviewDate" class="cg-error">{{ t(errors.reviewDate) }}</span></label>
          <p>{{ t('nominationHelp') }}</p><p v-if="otherNomineeLocked">{{ t('keepOther') }}</p>
          <label for="cg-nominationMode">{{ t('nominee') }}<select id="cg-nominationMode" v-model="draft.nominationMode" :aria-invalid="!!errors.nominationMode" aria-describedby="cg-error-nominationMode">
            <option v-if="dialog.operation==='REVISE'" value="keep">{{ t('keep') }}</option><template v-if="!otherNomineeLocked"><option value="unassigned">{{ t('unassigned') }}</option><option value="me">{{ t('me') }}</option><option v-if="flow.state.capabilities?.canChooseNominee" value="choose">{{ t('choose') }}</option></template>
          </select><span v-if="errors.nominationMode" id="cg-error-nominationMode" class="cg-error">{{ t(errors.nominationMode) }}</span></label>
          <section v-if="draft.nominationMode==='choose' && flow.state.capabilities?.canChooseNominee" class="cg-card">
            <div class="cg-search"><label for="cg-name">{{ t('searchName') }} (50)<input id="cg-name" v-model="candidateName" maxlength="100" /></label><button type="button" :disabled="flow.state.candidates.phase==='loading'" @click="searchCandidates">{{ t('search') }}</button></div>
            <p v-if="flow.state.candidates.phase==='loading'" role="status">{{ t('loading') }}</p>
            <p v-else-if="flow.state.candidates.phase==='unavailable'" role="alert">{{ t(flow.state.candidateError==='COMMON_GOAL_CANDIDATES_OVERFLOW'?'candidateOverflow':'candidateUnavailable') }}</p>
            <p v-else-if="flow.state.candidates.phase==='loaded' && !flow.state.candidates.items.length">{{ t('noCandidates') }}</p>
            <label for="cg-selectedNominee">{{ t('chooseNominee') }}<select id="cg-selectedNominee" v-model="draft.selectedNomineeId"><option :value="null">{{ t('chooseNominee') }}</option><option v-for="candidate in flow.state.candidates.items" :key="candidate.userId" :value="candidate.userId">{{ person(candidate) }} · {{ t('account') }} {{ candidate.userId }}</option></select></label>
          </section>
        </template>
        <label v-if="reasonRequired" for="cg-reason">{{ t('reason') }} (1000)<textarea id="cg-reason" v-model="draft.reason" rows="3" :aria-invalid="!!errors.reason" aria-describedby="cg-error-reason" /><span v-if="errors.reason" id="cg-error-reason" class="cg-error">{{ t(errors.reason) }}</span></label>
        <template v-if="['ADD_PROGRESS','ADD_FEEDBACK'].includes(dialog.operation)">
          <label v-if="dialog.operation==='ADD_FEEDBACK'" for="cg-feedbackSource">{{ t('feedbackSource') }}<select id="cg-feedbackSource" v-model="draft.feedbackSource" :aria-invalid="!!errors.feedbackSource" aria-describedby="cg-error-feedbackSource"><option value="">{{ t('chooseSource') }}</option><option v-for="source in feedbackSources" :key="source" :value="source">{{ t(source) }}</option></select><span v-if="errors.feedbackSource" id="cg-error-feedbackSource" class="cg-error">{{ t(errors.feedbackSource) }}</span></label>
          <p v-if="dialog.operation==='ADD_FEEDBACK'">{{ t('feedbackHelp') }}</p>
          <label for="cg-note">{{ t('note') }} (1000)<textarea id="cg-note" v-model="draft.note" rows="4" :aria-invalid="!!errors.note" aria-describedby="cg-error-note" /><span v-if="errors.note" id="cg-error-note" class="cg-error">{{ t(errors.note) }}</span></label>
          <label for="cg-reportedDate">{{ t('reportedDate') }}<input id="cg-reportedDate" v-model="draft.reportedDate" type="date" :aria-invalid="!!errors.reportedDate" aria-describedby="cg-error-reportedDate" /><span v-if="errors.reportedDate" id="cg-error-reportedDate" class="cg-error">{{ t(errors.reportedDate) }}</span></label><p>{{ t('reportedHelp') }}</p>
        </template>
        <label v-if="dialog.operation==='COMPLETE'" for="cg-result">{{ t('result') }} (1000)<textarea id="cg-result" v-model="draft.result" rows="4" :aria-invalid="!!errors.result" aria-describedby="cg-error-result" /><span v-if="errors.result" id="cg-error-result" class="cg-error">{{ t(errors.result) }}</span></label>
        <label v-if="['ACCEPT','COMPLETE'].includes(dialog.operation)" class="cg-check" for="cg-confirmed"><input id="cg-confirmed" v-model="draft.confirmed" type="checkbox" :aria-invalid="!!errors.confirmed" aria-describedby="cg-error-confirmed" />{{ t(dialog.operation==='ACCEPT'?'acceptConfirm':'completeConfirm') }}</label><p v-if="errors.confirmed" id="cg-error-confirmed" class="cg-error">{{ t(errors.confirmed) }}</p>
      </fieldset>
      <section v-if="['conflict','invalid'].includes(flow.state.phase)" class="cg-notice">
        <p>{{ t('decisionHelp') }}</p><button type="button" @click="flow.refreshDecision">{{ t('review') }}</button>
        <template v-if="flow.state.reviewReady"><h3>{{ t('compare') }}</h3><CommonGoalSnapshot v-if="flow.state.detail" :goal="flow.state.detail.goal" :nominee="flow.state.detail.nominee" /><button v-if="flow.state.decisionReady" type="button" @click="flow.newDecision">{{ t('decide') }}</button></template>
      </section>
      <section v-if="['unknown','checking'].includes(flow.state.phase)" class="cg-notice"><p>{{ t('retryHelp') }}</p><div class="cg-actions"><button type="button" :disabled="flow.state.phase==='checking'" @click="flow.reconcile">{{ t('checkReceipt') }}</button><button type="button" :disabled="flow.state.phase==='checking'" @click="flow.retry">{{ t('retry') }}</button></div></section>
      <footer class="cg-actions"><button type="button" @click="requestClose">{{ t('close') }}</button><button type="submit" :disabled="flow.locked() || ['conflict','invalid'].includes(flow.state.phase)">{{ t('save') }}</button></footer>
    </form>
  </dialog>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { feedbackSources } from '@/utils/commonCareGoal'
import { goalText as t, goalPerson as person } from '@/utils/commonCareGoalCopy'
import CommonGoalSnapshot from './CommonGoalSnapshot.vue'
import CommonGoalEntry from './CommonGoalEntry.vue'
const props = defineProps({ flow: { type: Object, required: true }, patientName: String, focusFallback: Function })
const dialogElement = ref(null), candidateName = ref('')
const dialog = computed(() => props.flow.state.dialog), draft = computed(() => dialog.value?.draft || {}), errors = computed(() => props.flow.state.errors)
const reasonRequired = computed(() => ['REVISE','DECLINE','STEP_BACK','CANCEL','REOPEN'].includes(dialog.value?.operation))
const contentFields = [{key:'title',label:'titleField',max:120},{key:'goalText',max:1000},{key:'progressCriterion',max:500},{key:'responsibility',max:500}]
const phaseMessage = computed(() => ({submitting:'saving',checking:'checking',unknown:'unknown',conflict:'conflict',invalid:'invalid'})[props.flow.state.phase])
const otherNomineeLocked = computed(() => !props.flow.state.capabilities?.recordOwner && dialog.value?.baseline?.goal.content.nomineeId != null && dialog.value.baseline.goal.content.nomineeId !== Number(localStorage.getItem('userId')))
let opener = null, focusOwner = null, alive = true, focusGeneration = 0
function requestClose() { props.flow.closeDialog() }
function focusFirst() { dialogElement.value?.querySelector('input:not(:disabled),textarea:not(:disabled),select:not(:disabled),button:not(:disabled)')?.focus() }
watch(dialog, async (value, previous) => {
  const generation = ++focusGeneration
  if (value && !previous) { opener = globalThis.document?.activeElement; focusOwner = props.flow.captureFocus(); candidateName.value = '' }
  await nextTick()
  if (!alive || generation !== focusGeneration) return
  if (value) { if (!dialogElement.value?.open) dialogElement.value?.showModal?.(); focusFirst() }
  else {
    dialogElement.value?.close?.(); candidateName.value = ''
    // 只恢复本次同患者/同身份的焦点，路由卸载或授权变化不能抢走新页面焦点。
    if (previous && props.flow.ownsFocus(focusOwner)) {
      if (opener?.isConnected && !opener.matches?.(':disabled')) opener.focus()
      else props.focusFallback?.()
    }
    opener = null; focusOwner = null
  }
}, { flush: 'post' })
watch(() => draft.value.nominationMode, mode => { if (mode === 'choose') searchCandidates() })
function searchCandidates() { draft.value.selectedNomineeId = null; return props.flow.loadCandidates(candidateName.value) }
async function submit() {
  const result = await props.flow.submit()
  if (result.phase === 'invalid') { await nextTick(); dialogElement.value?.querySelector('[aria-invalid="true"]')?.focus() }
}
function trapFocus(event) {
  if (event.key !== 'Tab' || !dialogElement.value?.open) return
  const root = dialogElement.value
  const controls = [...root.querySelectorAll('button,input,select,textarea,a[href],[tabindex]')].filter(node => node.tabIndex >= 0 && !node.matches(':disabled') && !node.closest('[hidden],[inert]') && node.getClientRects().length > 0)
  const first = controls[0], last = controls[controls.length - 1], active = globalThis.document?.activeElement
  if (!controls.length) { event.preventDefault(); root.focus(); return }
  if (!controls.includes(active) || (event.shiftKey ? active === first : active === last)) { event.preventDefault(); (event.shiftKey ? last : first).focus() }
}
onBeforeUnmount(() => { alive = false; focusGeneration++; opener = null; focusOwner = null; dialogElement.value?.close?.() })
</script>
