<template>
  <Teleport to="body">
    <div v-if="!closed && !state.retired" class="review-backdrop">
      <section ref="dialog" class="review-dialog" role="dialog" aria-modal="true" :aria-labelledby="titleId" tabindex="-1" data-medical-review-workflow @keydown="onKeydown">
        <header><div><h1 :id="titleId" ref="heading" tabindex="-1">{{ t.title }}</h1><p>{{ t.record }} #{{ recordId }} · {{ t.patient }}: {{ patientName || (patientId === null ? t.patientless : `#${patientId}`) }}</p></div><button type="button" data-action="close" @click="requestClose">{{ t.close }}</button></header>
        <p>{{ t.disclaimer }}</p>
        <div class="actions" :aria-label="t.title">
          <button type="button" data-action="source-channel" :aria-pressed="state.channel === 'SOURCE'" :disabled="flow.locked.value" @click="flow.selectChannel('SOURCE')">{{ t.source }}</button>
          <button type="button" data-action="doctor-channel" :aria-pressed="state.channel === 'DOCTOR'" :disabled="flow.locked.value || patientId === null" @click="flow.selectChannel('DOCTOR')">{{ t.doctor }}</button>
        </div>
        <section ref="statusArea" tabindex="-1" aria-live="polite" role="status">
          <p v-if="state.pendingSummary">{{ state.pendingSummary.channel }} · {{ t.operations[state.pendingSummary.operation] }} · {{ t.version }} {{ state.pendingSummary.contentVersion }} · {{ t.decision }} {{ state.pendingSummary.decisionVersion }}</p>
          <p v-if="state.phase === 'submitting'">{{ t.submitting }}</p><p v-if="state.phase === 'reconciling'">{{ t.reconciling }}</p>
          <template v-if="state.phase === 'unknown'"><p>{{ t.unknown }}</p><div class="actions"><button type="button" data-action="check-receipt" @click="flow.checkReceipt">{{ t.check }}</button><button type="button" data-action="retry-same" @click="flow.retrySameRequest">{{ t.retrySame }}</button></div></template>
          <section v-if="state.receipt" class="receipt" data-testid="review-receipt"><h2>{{ t.saved }}</h2><p>{{ state.receipt.channel }} · {{ t.operations[state.receipt.operation] }} · {{ t.states[state.receipt.state] }}</p><p>{{ t.version }} {{ state.receipt.contentVersion }} · {{ t.decision }} {{ state.receipt.decisionVersion }} · {{ t.cycle }} {{ state.receipt.cycle }}</p><p>{{ t.actors[state.receipt.actorKind] }} #{{ state.receipt.actorId }} · {{ state.receipt.appliedAt }} ({{ t.localTime }})</p><p v-if="state.currentError">{{ t.savedRefreshFailed }}</p></section>
          <p v-if="state.phase === 'conflict'">{{ t.conflict }}</p><p v-if="state.phase === 'rejected'">{{ t.rejected }}</p>
        </section>
        <p v-if="state.validation" role="alert">{{ message(state.validation) }}</p>
        <div class="actions"><button type="button" data-action="refresh-current" :disabled="flow.dirty.value || state.currentLoading" @click="flow.loadCurrent">{{ t.refresh }}</button></div>
        <p v-if="state.currentLoading" role="status">{{ t.loading }}</p><p v-if="state.currentError" role="alert">{{ message(state.currentError) }}</p>
        <section v-if="state.current" data-testid="review-current"><h2>{{ t.current }} · {{ t.version }} {{ state.current.sourceRecord.contentVersion }}</h2>
          <p>{{ presentation.sourceText }}</p><p>{{ presentation.sourceActorText }}</p><p>{{ presentation.doctorText }}</p><p>{{ presentation.doctorActorText }}</p><p>{{ presentation.automaticText }}</p><p>{{ presentation.observationText }}</p>
          <dl class="heads"><template v-for="channel in ['source', 'doctor']" :key="channel"><dt>{{ channel === 'source' ? t.source : t.doctor }}</dt><dd v-if="state.current.reviewSummary[channel]">{{ t.decision }} {{ state.current.reviewSummary[channel].decisionVersion }} · {{ t.cycle }} {{ state.current.reviewSummary[channel].cycle }}</dd><dd v-else>{{ t.noChannel }}</dd></template></dl>
          <MedicalRecordSnapshot :snapshot="snapshot" /><OriginalImportFacts :original-import="state.current.sourceRecord.originalImport" />
          <p>{{ t.nativeHelp }}</p><details><summary>{{ t.native }}</summary><pre>{{ exactText(state.current.sourceRecord.aiRawResult) }}</pre></details>
          <template v-if="['view', 'saved'].includes(state.phase)"><h3>{{ t.choose }}</h3><p v-if="!state.current.newCommandsEnabled">{{ t.disabled }}</p><div class="actions"><button v-for="operation in flow.allowedOperations.value" :key="operation" type="button" :data-action="`start-${operation}`" @click="flow.start(operation)">{{ t.operations[operation] }}</button></div><p v-if="!flow.allowedOperations.value.length">{{ t.noActions }}</p></template>
        </section>
        <section v-if="state.phase === 'editing'" ref="editArea" tabindex="-1"><h2>{{ state.channel }} · {{ t.operations[state.operation] }}</h2>
          <label v-if="state.operation === 'APPROVE'" class="check"><input type="checkbox" data-action="optional-note" :checked="state.noteEnabled" @change="flow.setNoteEnabled($event.target.checked)" />{{ t.optionalNote }}</label>
          <label v-if="state.operation !== 'REQUEST' && (state.operation !== 'APPROVE' || state.noteEnabled)" class="field">{{ state.operation === 'APPROVE' ? t.note : t.reason }}<textarea data-action="draft" rows="5" :value="state.draft" @input="flow.setText($event.target.value)" /><span>{{ t.count }}: {{ [...state.draft].length }} / 1000</span></label>
          <button type="button" data-action="confirm" @click="flow.confirm">{{ t.review }}</button>
        </section>
        <section v-if="state.phase === 'confirming' && state.confirmation" ref="confirmArea" tabindex="-1"><h2>{{ t.confirmTitle }}</h2><p>{{ state.confirmation.channel }} · {{ t.operations[state.confirmation.operation] }} · {{ t.version }} {{ state.confirmation.expectedContentVersion }} · {{ t.decision }} {{ state.confirmation.expectedDecisionVersion }}</p>
          <p v-for="channel in ['source', 'doctor']" :key="channel">{{ channel === 'source' ? t.source : t.doctor }}: {{ state.confirmation[channel] ? `${t.states[state.confirmation[channel].state]} · ${t.decision} ${state.confirmation[channel].decisionVersion}` : t.noChannel }}</p>
          <h3>{{ t.reviewedText }}</h3><pre data-testid="confirmed-review-text">{{ exactText(state.confirmation.text) }}</pre>
          <label class="check"><input type="checkbox" data-action="acknowledge" :checked="state.acknowledged" @change="flow.acknowledge($event.target.checked)" />{{ t.acknowledge }}</label>
          <div class="actions"><button type="button" data-action="back-edit" @click="flow.backToEdit">{{ t.back }}</button><button type="button" data-action="submit" :disabled="!state.acknowledged" @click="flow.submit">{{ t.submit }}</button></div>
        </section>
        <section v-if="state.current && !flow.locked.value"><div class="actions"><button type="button" data-action="history" :disabled="state.channel === 'DOCTOR' && patientId === null" @click="flow.loadHistory">{{ t.history }}</button></div>
          <p v-if="state.historyLoading" role="status">{{ t.loading }}</p><div v-if="state.historyError" role="alert"><p>{{ message(state.historyError) }}</p><button type="button" data-action="retry-history" @click="flow.retryHistory">{{ t.retryRead }}</button></div>
          <section v-if="state.history" data-testid="review-history"><h2>{{ t.historyTitle }}</h2><p>{{ t.highWater }}: {{ state.history.highWaterDecisionVersion }}</p><p v-if="!state.history.entries.length">{{ t.noHistory }}</p>
            <ol><li v-for="entry in state.history.entries" :key="entry.decisionVersion"><p>{{ t.decision }} {{ entry.decisionVersion }} · {{ t.version }} {{ entry.contentVersion }} · {{ t.cycle }} {{ entry.cycle }} · {{ t.operations[entry.operation] }} · {{ t.states[entry.state] }}</p><p>{{ t.actors[entry.actorKind] }} #{{ entry.actorId }} · {{ entry.occurredAt }} ({{ t.localTime }})</p><button v-if="flow.canReadText.value" type="button" data-action="detail" @click="flow.loadDetail(entry)">{{ t.textButton }}</button></li></ol>
            <p v-if="!flow.canReadText.value">{{ t.textDenied }}</p><div class="actions"><button type="button" data-action="previous-history" :disabled="!state.hasPrevious || state.historyLoading" @click="flow.previousHistory">{{ t.previous }}</button><button type="button" data-action="next-history" :disabled="!flow.canNext.value" @click="flow.nextHistory">{{ t.next }}</button><button type="button" data-action="first-history" @click="flow.loadHistory">{{ t.firstPage }}</button></div>
          </section>
          <p v-if="state.detailLoading" role="status">{{ t.loading }}</p><p v-if="state.detailError" role="alert">{{ message(state.detailError) }}</p><section v-if="state.detail" data-testid="review-detail"><h3>{{ t.textHeading }} · {{ t.decision }} {{ state.detail.event.decisionVersion }}</h3><pre>{{ exactText(state.detail.text?.value ?? null) }}</pre></section>
        </section>
        <section v-if="askLeave" class="leave-confirmation" role="alertdialog" :aria-label="t.leaveTitle"><h2>{{ t.leaveTitle }}</h2><p>{{ t.leaveHelp }}</p><div class="actions"><button type="button" data-action="keep" @click="cancelLeave">{{ t.keep }}</button><button type="button" data-action="leave" @click="confirmLeave">{{ t.leave }}</button></div></section>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, ref, inject, unref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache } from '@/utils/authSession'
import { medicalReviewPresentation } from '@/utils/medicalReviewQualification'
import { useMedicalReviewWorkflow } from '@/composables/useMedicalReviewWorkflow'
import { medicalReviewWorkflowCopy as t } from './medicalReviewWorkflowCopy'
import MedicalRecordSnapshot from './MedicalRecordSnapshot.vue'
import OriginalImportFacts from '@/components/OriginalImportFacts.vue'

const props = defineProps({ recordId: { type: Number, required: true }, patientId: { type: Number, default: null }, patientName: { type: String, default: '' }, initialChannel: { type: String, default: 'SOURCE' }, currentPatientId: { default: null }, contextPatientId: { default: null }, routeKey: { type: String, required: true }, navigationKey: { type: String, required: true }, fallbackElement: { default: null } })
const emit = defineEmits(['closed', 'changed', 'view-state'])
const dialog = ref(null), heading = ref(null), statusArea = ref(null), editArea = ref(null), confirmArea = ref(null), askLeave = ref(false), closed = ref(false)
const target = computed(() => ({ recordId: props.recordId, patientId: props.patientId }))
const context = computed(() => ({ globalPatient: props.currentPatientId, localPatient: props.contextPatientId, route: props.routeKey, navigation: props.navigationKey }))
const menus = inject('userMenus', null), focusContext = () => JSON.stringify([target.value, context.value, unref(menus)])
let opener = null, focusTicket = null, discardOrigin = null, routeResolve = null
function retired() { if (closed.value) return; closed.value = true; focusTicket = null; askLeave.value = false; if (routeResolve) { routeResolve(false); routeResolve = null } emit('closed', { retired: true }) }
const flow = useMedicalReviewWorkflow(target, context, { initialChannel: props.initialChannel, onChanged: payload => emit('changed', payload), onRetired: retired }), state = flow.state
const titleId = `medical-review-workflow-${props.recordId}`
const presentation = computed(() => medicalReviewPresentation(state.current?.reviewSummary, t.locale))
const snapshot = computed(() => { const r = state.current?.sourceRecord; return r ? { record: r, items: r.items, attachments: r.attachments } : null })
const exactText = text => text === null ? t.nullText : text === '' ? t.emptyText : /^[\u0009-\u000D\u001C-\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+$/u.test(text) ? `${t.whitespace}: «${text}»` : text
const message = code => t.errors[code] || t.errors.READ_UNAVAILABLE
function finishClose(leavingRoute = false) {
  if (closed.value || !flow.requireOwner()) return
  focusTicket = leavingRoute ? null : { ...captureAuthSession(), actorId: localStorage.getItem('userId'), permission: JSON.stringify(readPermissionCache()), context: focusContext() }
  closed.value = true; askLeave.value = false; const resolve = routeResolve; routeResolve = null; flow.discard(); emit('closed', { retired: false }); resolve?.(true)
}
function requestClose() { if (closed.value || !flow.requireOwner()) return; if (flow.dirty.value) { discardOrigin = document.activeElement; askLeave.value = true } else finishClose() }
function cancelLeave() { askLeave.value = false; const resolve = routeResolve; routeResolve = null; resolve?.(false) }
function confirmLeave() { finishClose(!!routeResolve) }
function visible(element) { return !!element?.isConnected && !element.disabled && element.getClientRects?.().length > 0 }
function focusables() { const scope = askLeave.value ? dialog.value?.querySelector('.leave-confirmation') : dialog.value; return [...(scope?.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),summary,a[href],[tabindex="0"]') || [])].filter(visible) }
function onKeydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); if (askLeave.value) cancelLeave(); else requestClose(); return }
  if (event.key !== 'Tab') return
  const elements = focusables(), first = elements[0], last = elements[elements.length - 1]
  if (!first) { event.preventDefault(); dialog.value?.focus(); return }
  if (event.shiftKey ? document.activeElement === first || !elements.includes(document.activeElement) : document.activeElement === last || !elements.includes(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus() }
}
function beforeUnload(event) { if (flow.dirty.value) { event.preventDefault(); event.returnValue = '' } }
function beforeRouteChange() { if (closed.value || state.retired) return true; if (!flow.requireOwner()) return true; if (!flow.dirty.value) { finishClose(true); return true } if (routeResolve) routeResolve(false); discardOrigin = document.activeElement; askLeave.value = true; return new Promise(resolve => { routeResolve = resolve }) }
onBeforeRouteLeave(beforeRouteChange)
onBeforeRouteUpdate(beforeRouteChange)
watch(() => [flow.locked.value, flow.dirty.value], ([locked, dirty]) => { if (!closed.value) emit('view-state', { locked, dirty }) }, { immediate: true, flush: 'sync' })
watch(() => state.phase, async phase => { const generation = state.generation; await nextTick(); if (closed.value || generation !== state.generation || !flow.requireOwner()) return; const destination = phase === 'editing' ? editArea.value : phase === 'confirming' ? confirmArea.value : ['saved', 'unknown', 'conflict', 'rejected'].includes(phase) ? statusArea.value : null; destination?.focus(); destination?.scrollIntoView({ block: 'nearest' }) })
watch(askLeave, async value => { const generation = state.generation; await nextTick(); if (closed.value || generation !== state.generation || !flow.requireOwner()) return; if (value) focusables()[0]?.focus(); else if (visible(discardOrigin)) discardOrigin.focus() })
onMounted(() => { opener = document.activeElement === document.body ? null : document.activeElement; if (!closed.value) heading.value?.focus(); window.addEventListener('beforeunload', beforeUnload) })
onUnmounted(() => { window.removeEventListener('beforeunload', beforeUnload); if (routeResolve) { routeResolve(false); routeResolve = null } if (focusTicket) nextTick(() => { if (!focusTicket || !isAuthSessionCurrent(focusTicket) || focusTicket.actorId !== localStorage.getItem('userId') || focusTicket.permission !== JSON.stringify(readPermissionCache()) || focusTicket.context !== focusContext() || document.querySelector('[data-medical-review-workflow]')) return; const destination = visible(opener) ? opener : props.fallbackElement; if (visible(destination)) destination.focus() }) })
</script>

<style scoped>
.review-backdrop { position: fixed; inset: 0; z-index: 3150; display: flex; align-items: flex-start; justify-content: center; overflow: auto; padding: max(12px, env(safe-area-inset-top)) 12px; box-sizing: border-box; background: rgb(0 0 0 / 45%); }
.review-dialog { width: min(960px, 100%); max-width: 100%; min-width: 0; padding: 20px; border-radius: 14px; border: 1px solid var(--line); background: var(--paper); color: var(--ink-800); line-height: 1.6; overflow-wrap: anywhere; box-sizing: border-box; }
header, .actions { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px; margin: 12px 0; } header > div { min-width: 0; }.actions { justify-content: flex-start; } h1 { font-size: 1.4rem; } h2 { font-size: 1.2rem; } h3 { font-size: 1.1rem; }
button, textarea, input { font: inherit; color: var(--ink-800); background: var(--paper); border: 1px solid var(--line); border-radius: 8px; box-sizing: border-box; } button { min-height: 44px; max-width: 100%; padding: 9px 14px; color: var(--care-800); border-color: var(--care-600); white-space: normal; cursor: pointer; } button:disabled { opacity: .6; cursor: not-allowed; } button:focus-visible, input:focus-visible, textarea:focus-visible, summary:focus-visible { outline: 3px solid var(--care-600); outline-offset: 2px; }
.field { display: grid; gap: 8px; margin: 12px 0; } textarea { width: 100%; min-height: 120px; resize: vertical; padding: 10px; }.check { display: flex; gap: 10px; align-items: flex-start; padding: 10px 0; min-height: 44px; }.check input { width: 20px; height: 20px; flex: 0 0 20px; margin: 3px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; padding: 10px; background: var(--surface-subtle); border: 1px solid var(--line); border-radius: 8px; }.receipt, .leave-confirmation { padding: 14px; border: 2px solid var(--care-600); border-radius: 10px; margin: 12px 0; }.heads { display: grid; grid-template-columns: auto 1fr; gap: 8px 16px; }.heads dd { margin: 0; } summary { min-height: 44px; cursor: pointer; padding: 8px 0; } li { margin: 12px 0; } :global(body.care-senior) button { min-height: 48px; }
@media (max-width: 600px) { .review-dialog { padding: 12px; }.heads { grid-template-columns: minmax(0, 1fr); }.actions { flex-direction: column; } }
</style>
