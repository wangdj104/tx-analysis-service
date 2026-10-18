<template>
  <component :is="embedded ? 'section' : 'el-dialog'" :model-value="!!dialog" :title="t(dialog?.operation)" width="min(720px, 96vw)" :close-on-click-modal="false" :before-close="requestClose" destroy-on-close @opened="focusFirst" @closed="restoreFocus" @update:model-value="value => { if (!value) requestClose() }">
    <form v-if="dialog" ref="formElement" class="vq vq-form" @submit.prevent="submit">
      <p>{{ t('patient') }}: {{ flow.state.patientId }}</p>
      <p>{{ t('context') }}: {{ contextLabel }}</p>
      <section v-if="ordinaryEditor && target" class="vq-notice"><p>{{ t('singleOrdinaryAction') }}</p><template v-if="dialog.operation==='RECORD_ANSWER'"><p>{{ t('titleField') }}: {{ target.title }}</p><p>{{ t('description') }}: {{ target.description }}</p></template><p v-else>{{ t('recordedAnswer') }}: {{ target.recordedAnswer || t('noAnswer') }}</p><p>{{ t('followUp') }}: {{ target.followUp || t('noNext') }}</p><p>{{ t('ordinaryReadOnly') }}</p></section>
      <p role="status" aria-live="polite" class="vq-notice">{{ saveMessage }}</p>
      <section v-if="flow.state.navigationBlocked" role="alert" class="vq-notice">
        <p>{{ flow.locked() ? t('uncertain') : t('dirty') }}</p>
        <button type="button" @click="flow.state.navigationBlocked=false">{{ t('keepEditing') }}</button>
        <button v-if="!flow.locked()" type="button" @click="flow.closeDialog(true)">{{ t('discard') }}</button>
      </section>
      <template v-if="dialog.operation==='ADOPT'">
        <p class="vq-notice">{{ t('adoption') }}</p>
        <label class="vq-check"><input v-model="draft.adoptionAccepted" type="checkbox" :aria-invalid="!!errors.adoptionAccepted" aria-describedby="vq-error-adoptionAccepted" :disabled="locked" />{{ t('acknowledge') }}</label>
        <p v-if="errors.adoptionAccepted" id="vq-error-adoptionAccepted" class="vq-error">{{ t(errors.adoptionAccepted) }}</p>
      </template>
      <fieldset :disabled="locked">
        <template v-if="['CREATE','ADOPT','EDIT'].includes(dialog.operation)">
          <label>{{ t('titleField') }} (200)<input v-model="draft.title" :aria-invalid="!!errors.title" aria-describedby="vq-error-title" /></label><p v-if="errors.title" id="vq-error-title" class="vq-error">{{ t(errors.title) }}</p>
          <label>{{ t('description') }} (4000)<textarea v-model="draft.description" rows="4" :aria-invalid="!!errors.description" aria-describedby="vq-error-description" /></label><p v-if="errors.description" id="vq-error-description" class="vq-error">{{ t(errors.description) }}</p>
          <label v-if="ordinaryEditor && dialog.operation==='EDIT'">{{ t('LEGACY_APPOINTMENT') }}<select v-model="draft.legacyAppointmentId"><option :value="null">{{ t('clearContext') }}</option><option v-if="target?.legacyAppointmentId && !appointments.some(a=>Number(a.id)===Number(target.legacyAppointmentId))" :value="target.legacyAppointmentId">{{ t('keepContext') }} #{{ target.legacyAppointmentId }}</option><option v-for="appointment in appointments" :key="appointment.id" :value="appointment.id">{{ appointment.eventAt || '' }} {{ appointment.title }}</option></select></label>
          <label>{{ t('context') }}<select v-model="draft.contextMode" :aria-invalid="!!errors.contextMode" aria-describedby="vq-error-contextMode"><option value="keep">{{ t('keepContext') }}</option><option value="none">{{ t('clearContext') }}</option><option v-if="flow.state.selectedContext" value="selected">{{ t('useContext') }}</option><option v-if="flow.state.contextCandidate" value="confirm">{{ t('confirmContext') }}</option></select></label><p v-if="errors.contextMode" id="vq-error-contextMode" class="vq-error">{{ t(errors.contextMode) }}</p>
          <button v-if="target?.contextType" type="button" @click="flow.inspectLinkedContext">{{ t('inspectContext') }}</button>
          <p v-if="target && flow.state.contextCandidate">{{ t(flow.state.contextCandidate.type) }} · {{ flow.state.contextCandidate.date }} · {{ t(flow.state.contextCandidate.status) }} · #{{ flow.state.contextCandidate.id }}</p>
        </template>
        <template v-if="dialog.operation.endsWith('ANSWER')">
          <p>{{ t(dialog.operation==='PUBLISH_ANSWER'?'CLINICIAN_AUTHORED':'USER_RECORDED') }}</p>
          <label>{{ t('recordedAnswer') }} (4000)<textarea v-model="draft.answer" rows="6" :aria-invalid="!!errors.answer" aria-describedby="vq-error-answer" /></label><p v-if="errors.answer" id="vq-error-answer" class="vq-error">{{ t(errors.answer) }}</p>
          <template v-if="dialog.operation==='RECORD_ANSWER'"><label>{{ t('reportedClinician') }}<input v-model="draft.reportedClinician" maxlength="200" :aria-invalid="!!errors.reportedClinician" /></label><label>{{ t('reportedDate') }}<input v-model="draft.reportedDate" type="date" :aria-invalid="!!errors.reportedDate" aria-describedby="vq-error-reportedDate" /></label><p v-if="errors.reportedDate" id="vq-error-reportedDate" class="vq-error">{{ t(errors.reportedDate) }}</p></template>
          <label v-if="flow.state.detail?.state==='FOLLOW_UP'" class="vq-check"><input v-model="draft.keepFollowUp" type="checkbox" />{{ t('keepFollowUp') }}</label>
        </template>
        <template v-if="dialog.operation==='SET_FOLLOW_UP'">
          <label>{{ t('followUp') }} (2000)<textarea v-model="draft.followUp" rows="4" :aria-invalid="!!errors.followUp" aria-describedby="vq-error-followUp" /></label><p v-if="errors.followUp" id="vq-error-followUp" class="vq-error">{{ t(errors.followUp) }}</p>
          <label>{{ t('dueDate') }}<input v-model="draft.dueDate" type="date" :aria-invalid="!!errors.dueDate" aria-describedby="vq-error-dueDate" /></label><p v-if="errors.dueDate" id="vq-error-dueDate" class="vq-error">{{ t(errors.dueDate) }}</p>
          <p>{{ t('responsible') }}: {{ responsibleLabel }}</p>
          <details><summary>{{ t('changeOwner') }}</summary><p>{{ t('ownerHelp') }}</p><label>{{ t('responsible') }}<select v-model="draft.ownerMode"><option value="keep">{{ t('keepOwner') }}</option><option value="me">{{ t('me') }}</option><option value="unassigned">{{ t('unassigned') }}</option><option value="account">{{ t('knownAccount') }}</option></select></label><label v-if="draft.ownerMode==='account'">{{ t('account') }}<input v-model="draft.responsibleInput" inputmode="numeric" :aria-invalid="!!errors.responsibleInput" aria-describedby="vq-error-responsibleInput" /></label><p v-if="errors.responsibleInput" id="vq-error-responsibleInput" class="vq-error">{{ t(errors.responsibleInput) }}</p></details>
        </template>
        <label v-if="questionReasons[dialog.operation]">{{ t('reason') }}<select v-model="draft.reason" :aria-invalid="!!errors.reason" aria-describedby="vq-error-reason"><option value="">{{ t('reason') }}</option><option v-for="reason in questionReasons[dialog.operation]" :key="reason" :value="reason">{{ t(reason) }}</option></select></label><p v-if="errors.reason" id="vq-error-reason" class="vq-error">{{ t(errors.reason) }}</p>
        <template v-if="questionReasons[dialog.operation] || dialog.operation==='SET_FOLLOW_UP' && draft.ownerMode!=='keep'"><label>{{ t('reasonNote') }} (1000)<textarea v-model="draft.reasonNote" rows="3" :aria-invalid="!!errors.reasonNote" aria-describedby="vq-error-reasonNote" /></label><p v-if="errors.reasonNote" id="vq-error-reasonNote" class="vq-error">{{ t(errors.reasonNote) }}</p></template>
        <template v-if="dialog.operation==='CLOSE'">
          <p>{{ t('notClinical') }}</p>
          <template v-if="draft.reason==='FOLLOW_UP_FINISHED'"><p>{{ t('resultHelp') }}</p><label class="vq-check"><input v-model="draft.useReference" type="checkbox" />{{ t('reference') }}</label>
            <template v-if="draft.useReference"><label>{{ t('referenceType') }}<select v-model="draft.referenceType" :aria-invalid="!!errors.referenceType"><option v-for="type in ['VISIT_RECORD','CARE_APPOINTMENT','LEGACY_APPOINTMENT','QUESTION_EVENT']" :key="type" :value="type">{{ t(type) }}</option></select></label><label>{{ t('referenceId') }}<input v-model="draft.referenceId" inputmode="numeric" :aria-invalid="!!errors.referenceId" aria-describedby="vq-error-referenceId" /></label><p v-if="errors.referenceId" id="vq-error-referenceId" class="vq-error">{{ t(errors.referenceId) }}</p></template>
            <template v-else><label>{{ t('resultText') }} (4000)<textarea v-model="draft.resultText" rows="4" :aria-invalid="!!errors.resultText" aria-describedby="vq-error-resultText" /></label><p v-if="errors.resultText" id="vq-error-resultText" class="vq-error">{{ t(errors.resultText) }}</p></template>
          </template>
        </template>
        <label v-if="dialog.operation==='REOPEN'">{{ t('reopenState') }}<select v-model="draft.state"><option value="OPEN">{{ t('OPEN') }}</option><option value="UNRESOLVED">{{ t('UNRESOLVED') }}</option></select></label>
      </fieldset>
      <section v-if="['conflict','rejected'].includes(flow.state.commandPhase)" class="vq-notice" role="alert"><p>{{ t('reviewHelp') }}</p><button type="button" @click="flow.refreshDecision">{{ t('refreshDecision') }}</button><button v-if="flow.state.decisionReady" type="button" @click="flow.useDraft">{{ t('useDraft') }}</button><article v-if="flow.state.reviewReady && flow.state.detail"><h3>{{ flow.state.detail.title }}</h3><p>{{ flow.state.detail.description }}</p><p>{{ t(flow.state.detail.state) }} · v{{ flow.state.detail.version }}</p><p>{{ flow.state.detail.recordedAnswer }}</p></article></section>
      <section v-if="['uncertain','checking'].includes(flow.state.commandPhase)" class="vq-notice"><button type="button" :disabled="flow.state.commandPhase==='checking'" @click="flow.reconcile">{{ t('checkReceipt') }}</button><button type="button" :disabled="flow.state.commandPhase!=='uncertain'" @click="flow.retry">{{ t('retry') }}</button><p>{{ t('retryHelp') }}</p><button type="button" :disabled="flow.state.commandPhase==='checking'" @click="flow.refreshDecision">{{ t('refreshDecision') }}</button><article v-if="flow.state.reviewReady && flow.state.detail"><h3>{{ flow.state.detail.title }}</h3><p>{{ flow.state.detail.description }}</p><p>{{ t(flow.state.detail.state) }} · v{{ flow.state.detail.version }}</p><p>{{ flow.state.detail.recordedAnswer }}</p><p>{{ t('unknownReadOnly') }}</p></article><p v-if="!flow.state.retryAllowed">{{ t('retryBlocked') }}</p></section>
      <footer class="vq-actions"><button type="button" @click="requestClose()">{{ t('cancel') }}</button><button type="submit" :disabled="locked || ['conflict','rejected'].includes(flow.state.commandPhase)">{{ t('save') }}</button></footer>
    </form>
  </component>
</template>
<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { questionText as t } from '@/utils/visitQuestionCopy'
import { questionReasons } from '@/composables/useVisitQuestion'
const props = defineProps({ flow: { type: Object, required: true }, embedded: Boolean, ordinaryEditor: Boolean, appointments: { type: Array, default: () => [] } })
const dialog = computed(() => props.flow.state.dialog), draft = computed(() => dialog.value?.draft || {}), errors = computed(() => props.flow.state.errors), locked = computed(() => props.flow.locked())
const formElement = ref(null)
let opener = null, openedPatient = null
watch(dialog, (value, old) => { if (value && !old) { opener = globalThis.document?.activeElement; openedPatient = props.flow.state.patientId } }, { flush: 'sync' })
const responsibleLabel = computed(() => { const id = props.flow.state.detail?.responsibleUserId; return id == null ? t('unassigned') : `${Number(id) === Number(props.flow.state.capabilities?.actorId) ? t('me') : t('currentResponsible')} (${t('account')} ${id})` })
const target = computed(() => dialog.value?.questionId === props.flow.state.detail?.questionId ? props.flow.state.detail : null)
const effectiveContext = computed(() => {
  const action = dialog.value
  if (!action) return null
  if (['CREATE', 'ADOPT', 'EDIT'].includes(action.operation)) {
    if (action.draft.contextMode === 'none') return null
    if (action.draft.contextMode === 'selected') return props.flow.state.selectedContext
    if (action.draft.contextMode === 'confirm') return props.flow.state.contextCandidate
  }
  // 未知结果下的只读刷新不能改写提交前显示的目标关联。
  if (Object.prototype.hasOwnProperty.call(action, 'targetContext')) return action.targetContext
  return target.value?.contextType ? { type: target.value.contextType, id: target.value.contextId } : null
})
const contextLabel = computed(() => { const context = effectiveContext.value; return context ? `${t(context.type)} #${context.id}${context.date ? ` · ${context.date}` : ''}` : t('none') })
const saveMessage = computed(() => { const phase = props.flow.state.commandPhase; if (props.flow.state.error === 'QUESTION_LIMIT') return t('limit'); return t(({ saving: 'saving', checking: 'loading', uncertain: 'uncertain', conflict: 'conflict', rejected: 'rejected', saved: 'saved', denied: 'denied' })[phase] || 'oldNote') })
function requestClose(done) { if (props.flow.closeDialog() && typeof done === 'function') done() }
watch(dialog, async (value, old) => { if (props.embedded) { await nextTick(); if (value) focusFirst(); else if (old) restoreFocus() } }, { flush: 'post' })
function focusFirst() { formElement.value?.querySelector('input,textarea,select,button')?.focus() }
function restoreFocus() { if (openedPatient === props.flow.state.patientId && opener?.isConnected) opener.focus(); opener = null }
async function submit() { const result = await props.flow.submit(); if (result.status === 'invalid') { await nextTick(); formElement.value?.querySelector('[aria-invalid="true"]')?.focus() } }
</script>
