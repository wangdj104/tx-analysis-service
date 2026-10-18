<template>
  <Teleport to="body">
    <div class="medical-correction-backdrop">
      <section ref="dialog" class="medical-correction-dialog" data-medical-correction-dialog role="dialog" aria-modal="true" :aria-labelledby="titleId" tabindex="-1" @keydown="onKeydown">
        <header class="correction-heading">
          <div><h1 :id="titleId" ref="heading" tabindex="-1">{{ t.title }}</h1><h2>{{ patientName || (patientId == null ? t.unlinkedPatient : t.patientProfile) }}</h2><p class="secondary-id"><span v-if="patientId != null">{{ t.patientId }} {{ patientId }} · </span>{{ t.recordId }} {{ recordId }}</p></div>
          <button type="button" data-action="close" :disabled="flow.locked.value" @click="requestClose">{{ t.close }}</button>
        </header>
        <div ref="statusArea" class="correction-status" tabindex="-1" aria-live="polite">
          <p v-if="state.loading" role="status">{{ t.loading }}</p>
          <div v-if="state.accessError" role="alert"><p>{{ message(state.accessError) }}</p><button type="button" data-action="retry-access" :disabled="state.loading" @click="flow.open">{{ t.retryAccess }}</button></div>
          <p v-if="state.phase === 'submitting'" role="status">{{ t.submitting }}</p>
          <div v-if="state.phase === 'unknown'" role="alert"><p>{{ t.unknown }}</p><button type="button" data-action="retry-command" @click="flow.retryCommand">{{ t.retryCommand }}</button></div>
          <div v-if="state.receipt" class="saved-receipt" role="status"><strong>{{ t.saved }}</strong><p>{{ t.version }} {{ state.receipt.contentVersion }} · {{ t.historySequence }} {{ state.receipt.historySequence }} · {{ t.validities[state.receipt.validity] }}</p><p>{{ t.savedHelp }}</p></div>
        </div>
        <template v-if="state.capabilities">
          <nav v-if="state.stage === 'view'" class="correction-actions" :aria-label="t.title">
            <button type="button" data-action="current" :aria-pressed="tab === 'current'" @click="tab = 'current'">{{ t.current }}</button>
            <button type="button" data-action="history" :aria-pressed="tab === 'history'" @click="showHistory">{{ t.history }}</button>
          </nav>
          <section v-if="tab === 'current' || state.stage !== 'view'" class="current-section">
            <p v-if="state.currentLoading" role="status">{{ t.currentLoading }}</p>
            <div v-if="state.currentError" role="alert"><p>{{ message(state.currentError) }}</p><button type="button" data-action="retry-current" :disabled="state.currentLoading || flow.locked.value" @click="flow.loadCurrent">{{ t.retryCurrent }}</button></div>
            <div v-if="state.current" class="current-status">
              <p><strong>{{ t.version }} {{ state.current.contentVersion }}</strong> · {{ t.validity }}: {{ t.validities[state.current.validity] || t.null }} · {{ t.review }}: {{ t.reviews[state.current.current.record.verificationStatus] || t.null }}</p>
              <p>{{ t.verifiedHelp }}</p>
              <p v-if="state.current.validity === 'INVALIDATED'" class="important-note">{{ t.invalidatedHelp }}</p>
            </div>
            <template v-if="state.stage === 'view'">
              <div class="correction-actions">
                <button v-if="state.current?.canCorrect" type="button" data-action="correct" :disabled="state.currentLoading" @click="start('CORRECT')">{{ t.correct }}</button>
                <button v-if="state.current?.canInvalidate" type="button" data-action="invalidate" :disabled="state.currentLoading" @click="start('INVALIDATE')">{{ t.invalidate }}</button>
                <button v-if="state.capabilities.canLegacyEdit" type="button" data-action="legacy-edit" @click="leaveFor('legacy-edit')">{{ t.legacyEdit }}</button>
                <button v-if="state.capabilities.canLegacyDelete" type="button" data-action="legacy-delete" @click="leaveFor('legacy-delete')">{{ t.legacyDelete }}</button>
                <button type="button" data-action="original" @click="leaveFor('original')">{{ t.original }}</button>
              </div>
              <p v-if="state.capabilities.canLegacyEdit || state.capabilities.canLegacyDelete">{{ t.legacyHelp }}</p>
              <p v-else-if="!state.current?.canCorrect && !state.current?.canInvalidate">{{ t.readOnly }}</p>
              <p>{{ t.originalHelp }}</p>
              <MedicalRecordSnapshot v-if="state.current" :snapshot="state.current.current" />
            </template>
            <template v-else>
              <div v-if="state.phase === 'conflict'" ref="conflictArea" class="important-note" role="alert" tabindex="-1">
                <p>{{ t.conflict }}</p><p>{{ t.draftVersion }} {{ state.draftBase?.record.contentVersion }}</p>
                <details v-if="state.current"><summary>{{ t.current }}</summary><MedicalRecordSnapshot :snapshot="state.current.current" /></details>
                <button type="button" data-action="reconsider" :disabled="!state.current || state.currentLoading" @click="flow.reconsider">{{ t.reconsider }}</button>
              </div>
              <p v-if="state.commandError && state.phase !== 'unknown' && state.phase !== 'conflict'" role="alert">{{ message(state.commandError) }}</p>
              <p v-if="state.operation === 'INVALIDATE'" class="important-note">{{ t.invalidationHelp }}</p>
              <form v-if="state.stage === 'edit'" @submit.prevent="flow.preview">
                <fieldset :disabled="flow.locked.value"><legend ref="editHeading" tabindex="-1">{{ t.operations[state.operation] }}</legend>
                  <template v-if="state.operation === 'CORRECT'">
                    <p>{{ t.originalHelp }}</p><p>{{ t.exactHelp }}</p><h3>{{ t.headerFields }}</h3>
                    <div class="correction-field-grid">
                      <div v-for="(maximum, field) in HEADER_FIELDS" :key="field" class="correction-field">
                        <label :for="`header-${field}`">{{ t.fields[field] }} <small>({{ maximum }})</small></label>
                        <textarea v-if="field === 'remark'" :id="`header-${field}`" :name="`header-${field}`" :value="state.draft.header[field] ?? ''" :disabled="state.draft.header[field] === null" rows="3" @input="state.draft.header[field] = $event.target.value"></textarea>
                        <input v-else :id="`header-${field}`" :name="`header-${field}`" type="text" :value="state.draft.header[field] ?? ''" :disabled="state.draft.header[field] === null" @input="state.draft.header[field] = $event.target.value">
                        <label class="check-label"><input type="checkbox" :checked="state.draft.header[field] === null" @change="state.draft.header[field] = $event.target.checked ? null : ''">{{ t.null }}</label>
                      </div>
                    </div>
                    <h3>{{ t.items }}</h3><p>{{ t.flagHelp }}</p><p v-if="!state.draft.items.length">{{ t.noItems }}</p>
                    <section v-for="item in state.draft.items" :key="item.id" class="editable-item">
                      <h4>{{ t.itemId }} {{ item.id }}</h4>
                      <div class="correction-field-grid">
                        <div v-for="(maximum, field) in ITEM_FIELDS" :key="field" class="correction-field">
                          <label :for="`item-${item.id}-${field}`">{{ t.fields[field] }} <small>({{ maximum }})</small></label>
                          <input :id="`item-${item.id}-${field}`" :name="`item-${item.id}-${field}`" type="text" :value="item[field] ?? ''" :disabled="item[field] === null" @input="item[field] = $event.target.value">
                          <label class="check-label"><input type="checkbox" :checked="item[field] === null" @change="item[field] = $event.target.checked ? null : ''">{{ t.null }}</label>
                        </div>
                      </div>
                      <div v-if="pairChanged(item)" class="pair-confirmation"><p>{{ t.pairHelp }}</p><p>{{ t.fields.itemName }}: <span class="exact-value">{{ exact(item.itemName) }}</span> · {{ t.fields.itemCode }}: <span class="exact-value">{{ exact(item.itemCode) }}</span></p><label class="check-label"><input :checked="item.confirmNameCodePair" @change="item.confirmNameCodePair = $event.target.checked" type="checkbox" :name="`pair-${item.id}`">{{ t.pair }}</label></div>
                      <label class="correction-field">{{ t.flag }}<select :value="item.flagChoice" @change="item.flagChoice = $event.target.value" :name="`flag-${item.id}`"><option value="KEEP">{{ t.keepFlag }}</option><option value="UNKNOWN">{{ t.unknownFlag }}</option><option value="LOW">{{ t.lowFlag }}</option><option value="NORMAL">{{ t.normalFlag }}</option><option value="HIGH">{{ t.highFlag }}</option></select></label>
                    </section>
                  </template>
                  <label class="correction-field" for="correction-reason">{{ t.reason }}<textarea id="correction-reason" name="reason" :value="state.reason" rows="4" required aria-describedby="correction-reason-help" @input="state.reason = $event.target.value"></textarea></label><p id="correction-reason-help">{{ t.reasonHelp }}</p>
                  <div class="correction-actions"><button type="submit" data-action="preview" @click.prevent="flow.preview">{{ t.preview }}</button><button type="button" data-action="discard-edit" @click="askDiscard = 'edit'">{{ t.close }}</button></div>
                </fieldset>
              </form>
              <section v-else-if="state.preview" class="difference-preview">
                <h3 ref="decisionHeading" tabindex="-1">{{ t.confirmTitle }}</h3>
                <p v-if="state.operation === 'INVALIDATE'">{{ t.noChange }}</p>
                <dl v-for="(change, index) in state.preview.differences" :key="index" class="difference-row">
                  <dt>{{ change.itemId == null ? t.headerFields : `${t.itemId} ${change.itemId}` }} · {{ t.fields[change.field] }}</dt>
                  <dd><strong>{{ t.before }}</strong><pre>{{ change.field === 'reportedFlag' ? flag(change.before) : exact(change.before) }}</pre><strong>{{ t.after }}</strong><pre>{{ change.field === 'reportedFlag' ? flag(change.after) : exact(change.after) }}</pre></dd>
                </dl>
                <p v-if="state.preview.flagResets.length" class="important-note">{{ t.flagReset }} {{ state.preview.flagResets.join(', ') }}</p>
                <h4>{{ t.reason }}</h4><pre>{{ state.reason }}</pre>
                <div v-if="state.phase !== 'conflict'" class="correction-actions"><button type="button" data-action="edit-again" :disabled="flow.locked.value" @click="flow.editAgain">{{ t.editAgain }}</button><button type="button" data-action="submit" :disabled="flow.locked.value || !state.current || state.currentLoading" @click="flow.submit">{{ t.submit }}</button></div>
              </section>
            </template>
          </section>
          <section v-else class="history-section">
            <h3>{{ t.history }}</h3><p>{{ t.historyHelp }}</p><p>{{ t.pageOnly }}</p>
            <p v-if="state.historyLoading" role="status">{{ t.historyLoading }}</p>
            <div v-if="state.historyError" role="alert"><p>{{ message(state.historyError) }}</p><button type="button" data-action="retry-history" @click="flow.retryHistory">{{ t.retryHistory }}</button></div>
            <template v-if="state.history">
              <p v-if="!state.history.managed">{{ t.untracked }}</p><p v-else>{{ t.earliest }} {{ state.history.earliestCapturedContentVersion }}</p>
              <p v-if="state.history.managed && !state.history.entries.length">{{ t.emptyHistory }}</p>
              <ol class="history-entries"><li v-for="entry in state.history.entries" :key="entry.historySequence"><p>{{ t.operations[entry.operation] || t.null }} · {{ t.version }} {{ entry.afterVersion }} · {{ t.validities[entry.validity] || t.null }}</p><p>{{ t.capturedAt }} {{ entry.capturedAt }} · {{ t.capturedBy }} {{ entry.capturedBy }}</p><button type="button" :data-action="`revision-${entry.historySequence}`" @click="flow.loadRevision(entry.historySequence)">{{ t.openRevision }} {{ entry.afterVersion }}</button></li></ol>
              <div class="correction-actions"><button type="button" data-action="previous-history" :disabled="!state.previousCursors.length || state.historyLoading" @click="flow.previousHistory">{{ t.previous }}</button><button type="button" data-action="next-history" :disabled="state.history.nextCursor == null || state.historyLoading" @click="flow.nextHistory">{{ t.next }}</button></div>
            </template>
            <section class="revision-detail" aria-live="polite">
              <p v-if="state.revisionLoading" role="status">{{ t.revisionLoading }}</p>
              <div v-if="state.revisionError" role="alert"><p>{{ message(state.revisionError) }}</p><button type="button" data-action="retry-revision" @click="flow.loadRevision(state.revisionSequence)">{{ t.retryRevision }}</button></div>
              <template v-if="state.revision"><h3>{{ t.revisionTitle }} · {{ state.revision.afterVersion }}</h3><p>{{ t.capturedAt }} {{ state.revision.capturedAt }} · {{ t.capturedBy }} {{ state.revision.capturedBy }}</p><p>{{ t.notOriginalAuthor }}</p><h4>{{ t.reason }}</h4><pre>{{ state.revision.reason ?? t.baselineReason }}</pre><MedicalRecordSnapshot :snapshot="state.revision.snapshot" /></template>
            </section>
          </section>
        </template>
        <section v-if="askDiscard" class="discard-confirmation" role="alertdialog" :aria-label="t.discardTitle">
          <h3>{{ t.discardTitle }}</h3><p>{{ t.discardHelp }}</p><div class="correction-actions"><button type="button" data-action="keep-editing" @click="askDiscard = ''">{{ t.keepEditing }}</button><button type="button" data-action="discard-close" @click="confirmDiscard">{{ t.discardClose }}</button></div>
        </section>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache } from '@/utils/authSession'
import { useMedicalRecordCorrection, HEADER_FIELDS, ITEM_FIELDS } from '@/composables/useMedicalRecordCorrection'
import { medicalRecordCopy as t } from './medicalRecordCorrectionCopy'
import MedicalRecordSnapshot from './MedicalRecordSnapshot.vue'

const props = defineProps({ recordId: { type: Number, required: true }, patientId: { type: Number, default: null }, patientName: { type: String, default: '' }, currentPatientId: { type: Number, default: null }, routeKey: { type: String, required: true }, fallbackElement: { default: null } })
const emit = defineEmits(['closed', 'changed', 'legacy-edit', 'legacy-delete', 'original', 'view-state'])
const target = computed(() => ({ recordId: props.recordId, patientId: props.patientId }))
const context = computed(() => ({ patientId: props.currentPatientId, route: props.routeKey }))
const flow = useMedicalRecordCorrection(target, context), state = flow.state
// 列表筛选只能关闭普通只读结果，不能丢掉草稿或结果未知的同一请求。
watch(() => [flow.locked.value, flow.dirty.value, state.current?.validity ?? state.receipt?.validity ?? null], ([locked, dirty, validity]) => emit('view-state', { locked, dirty, validity }), { immediate: true, flush: 'sync' })
const dialog = ref(null), heading = ref(null), statusArea = ref(null), tab = ref('current'), askDiscard = ref('')
const titleId = `medical-correction-${props.recordId}`
const editHeading = ref(null), decisionHeading = ref(null), conflictArea = ref(null)
let opener = null, restoreFocus = false, closed = false, focusTicket = null, discardOrigin = null
const focusContext = () => JSON.stringify([props.recordId, props.patientId, props.currentPatientId, props.routeKey])
const exact = value => value == null ? t.null : value === '' ? t.empty : typeof value === 'string' && !value.trim() ? `${t.whitespace} (${[...value].length}) «${value}»` : String(value)
const flag = value => ({ '-1': t.lowFlag, 0: t.normalFlag, 1: t.highFlag })[value] || t.unknownFlag
const message = code => t.errors[code] || t.errors.READ_UNAVAILABLE
function pairChanged(item) { const original = state.draftBase?.items.find(value => value.id === item.id); return original && (item.itemName !== original.itemName || item.itemCode !== original.itemCode) }
function start(operation) { tab.value = 'current'; flow.start(operation) }
function showHistory() { if (!flow.requireOwner() || flow.locked.value) return; tab.value = 'history'; if (!state.history && !state.historyLoading) flow.loadHistory() }
function finishClose() { if (closed || !flow.canClose.value) return; closed = true; restoreFocus = flow.requireOwner(); focusTicket = restoreFocus ? { ...captureAuthSession(), actorId: localStorage.getItem('userId'), permission: JSON.stringify(readPermissionCache()), context: focusContext() } : null; emit('closed') }
function requestClose() { if (!flow.canClose.value) return; if (flow.dirty.value) { discardOrigin = document.activeElement; askDiscard.value = 'close' } else finishClose() }
function confirmDiscard() { const action = askDiscard.value; if (!flow.discard()) return; askDiscard.value = ''; if (action === 'close') finishClose() }
function leaveFor(action) { if (!flow.requireOwner() || flow.locked.value || flow.dirty.value) return; restoreFocus = false; closed = true; emit(action, { recordId: props.recordId, patientId: props.patientId }) }
function visible(element) { return !!element?.isConnected && !element.disabled && element.getClientRects?.().length > 0 }
function focusables() { const scope = askDiscard.value ? dialog.value?.querySelector('.discard-confirmation') : dialog.value; return [...(scope?.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),summary,a[href],[tabindex="0"]') || [])].filter(visible) }
function onKeydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); if (askDiscard.value) askDiscard.value = ''; else requestClose(); return }
  if (event.key !== 'Tab') return
  const elements = focusables(), first = elements[0], last = elements[elements.length - 1]
  if (!first) { event.preventDefault(); dialog.value?.focus(); return }
  if (event.shiftKey ? document.activeElement === first || !elements.includes(document.activeElement) : document.activeElement === last || !elements.includes(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus() }
}
function beforeUnload(event) { if (flow.locked.value || flow.dirty.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(() => { if (flow.locked.value) return false; if (flow.dirty.value) { askDiscard.value = 'close'; return false } return true })
onMounted(() => { opener = document.activeElement === document.body ? null : document.activeElement; heading.value?.focus(); window.addEventListener('beforeunload', beforeUnload) })
watch(() => state.phase, async phase => {
  const generation = state.generation
  if (phase === 'saved') emit('changed', state.receipt)
  if (!['unknown', 'saved', 'conflict'].includes(phase)) return
  await nextTick()
  if (!closed && generation === state.generation && flow.requireOwner()) { const destination = phase === 'conflict' ? conflictArea.value : statusArea.value; destination?.focus(); destination?.scrollIntoView({ block: 'nearest' }) }
})
watch(askDiscard, async value => {
  const generation = state.generation
  await nextTick()
  if (closed || generation !== state.generation) return
  if (value) focusables()[0]?.focus()
  else if (visible(discardOrigin)) discardOrigin.focus()
})
watch(() => state.stage, async stage => {
  const generation = state.generation
  await nextTick()
  if (closed || generation !== state.generation || !flow.requireOwner()) return
  const destination = stage === 'edit' ? editHeading.value : stage === 'confirm' ? decisionHeading.value : null
  destination?.focus(); destination?.scrollIntoView({ block: 'nearest' })
})
watch(focusContext, () => { opener = null; restoreFocus = false; focusTicket = null }, { flush: 'sync' })
watch(() => state.generation, () => { askDiscard.value = ''; tab.value = 'current'; restoreFocus = false })
onUnmounted(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  // 只回到仍可见的原入口；患者/来源或会话改变以及新弹窗已出现时，旧回调不得抢焦点。
  if (restoreFocus) nextTick(() => { if (!focusTicket || !isAuthSessionCurrent(focusTicket) || focusTicket.actorId !== localStorage.getItem('userId') || focusTicket.permission !== JSON.stringify(readPermissionCache()) || focusTicket.context !== focusContext() || document.querySelector('[data-medical-correction-dialog]')) return; const destination = visible(opener) ? opener : props.fallbackElement; if (visible(destination)) destination.focus() })
})
</script>

<style scoped>
.medical-correction-backdrop { position: fixed; inset: 0; z-index: 3100; display: flex; justify-content: center; align-items: flex-start; padding: max(12px, env(safe-area-inset-top)) 12px max(12px, env(safe-area-inset-bottom)); background: rgb(0 0 0 / 45%); overflow: auto; box-sizing: border-box; }
.medical-correction-dialog { color: var(--ink-800); background: var(--paper); border: 1px solid var(--line); border-radius: 14px; width: min(960px, 100%); max-width: 100%; padding: 20px; box-sizing: border-box; min-width: 0; line-height: 1.6; overflow-wrap: anywhere; }
.correction-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.correction-heading > div { min-width: 0; }
.correction-heading h1 { font-size: 1.4em; margin: 0 0 8px; }
.correction-heading h2 { font-size: 1.2em; margin: 0; }
.secondary-id { color: var(--ink-600); margin-top: 4px; }
.correction-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: stretch; margin: 14px 0; }
button, input, textarea, select { font: inherit; color: var(--ink-800); background: var(--paper); border: 1px solid var(--line-strong, var(--line)); border-radius: 8px; box-sizing: border-box; }
button { min-height: 44px; padding: 9px 15px; cursor: pointer; white-space: normal; overflow-wrap: anywhere; max-width: 100%; border-color: var(--care-600); color: var(--care-800); }
button:disabled, fieldset:disabled { opacity: .65; cursor: not-allowed; }
button:focus-visible, input:focus-visible, textarea:focus-visible, select:focus-visible { outline: 3px solid var(--care-600); outline-offset: 2px; }
input:not([type="checkbox"]), textarea, select { width: 100%; min-height: 44px; padding: 9px; }
textarea { resize: vertical; }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
legend { font-size: 1.2em; font-weight: 700; margin: 12px 0; }
.correction-field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; gap: 16px; }
.correction-field { display: grid; align-content: start; gap: 8px; min-width: 0; }
.correction-field-grid > .correction-field { grid-template-rows: subgrid; grid-row: span 3; }
.correction-field > label:first-child { min-height: 1.6em; font-weight: 600; }
.check-label { display: flex; align-items: flex-start; gap: 9px; min-height: 44px; padding: 6px 0; box-sizing: border-box; }
.check-label input { flex: 0 0 20px; width: 20px; height: 20px; margin: 3px 0 0; }
.editable-item, .important-note, .saved-receipt, .discard-confirmation { border: 1px solid var(--line); border-radius: 10px; padding: 12px; margin: 14px 0; background: var(--surface-subtle); }
.editable-item h4 { margin: 0 0 12px; }
.important-note { border-left: 4px solid var(--care-600); }
pre, .exact-value { font: inherit; white-space: pre-wrap; overflow-wrap: anywhere; }
pre { background: var(--surface-subtle); padding: 10px; border: 1px solid var(--line); border-radius: 6px; }
.difference-row { border-bottom: 1px solid var(--line); padding: 10px 0; }
.difference-row dt { font-weight: 700; }
.difference-row dd { margin: 8px 0; }
.history-entries { padding-left: 24px; }
.history-entries li { padding: 12px 0; border-bottom: 1px solid var(--line); }
:global(body.care-senior) .medical-correction-dialog { font-size: 20px; }
:global(body.care-senior) .medical-correction-dialog :is(button, input:not([type="checkbox"]), textarea, select, .check-label) { min-height: 48px; }
@media (max-width: 600px) { .medical-correction-dialog { padding: 14px; } .correction-field-grid { grid-template-columns: minmax(0, 1fr); } .correction-actions button { flex: 1 1 100%; } .correction-heading { flex-wrap: wrap; } .correction-heading > button { margin-left: auto; } }
</style>
