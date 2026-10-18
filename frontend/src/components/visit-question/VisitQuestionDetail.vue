<template>
  <section v-if="d" class="vq-detail vq-card" aria-labelledby="vq-detail-title">
    <header><h3 id="vq-detail-title">{{ d.title }}</h3><p>{{ t('patient') }} #{{ d.patientId }} · {{ t('titleField') }} #{{ d.questionId }}</p></header>
    <p class="vq-status">{{ d.managed ? t(d.state) : `${t('legacyMark')}: ${d.legacyMark}` }}</p>
    <p>{{ d.description }}</p>
    <p>{{ t('context') }}: {{ d.contextType ? `${t(d.contextType)} #${d.contextId}` : t('none') }}</p>
    <p v-if="d.legacyAppointmentId">{{ t('LEGACY_APPOINTMENT') }} #{{ d.legacyAppointmentId }}</p>
    <button v-if="d.contextType" type="button" @click="flow.inspectLinkedContext">{{ t('inspectContext') }}</button>
    <p v-if="flow.state.contextCandidate">{{ t(flow.state.contextCandidate.type) }} #{{ flow.state.contextCandidate.id }} · {{ flow.state.contextCandidate.date }} · {{ t(flow.state.contextCandidate.status) }}</p>
    <section><h4>{{ t('recordedAnswer') }}</h4><p>{{ d.recordedAnswer || t('noAnswer') }}</p><p>{{ t(d.answerOrigin || 'LEGACY_UNVERIFIED') }}</p><p v-if="d.answerRecorderId">{{ t('recorder') }}: {{ person(d.answerRecorderId) }} · {{ t('recordedAt') }}: {{ d.answerRecordedAt }}</p><p v-else-if="d.recordedAnswer">{{ t('unknownRecorder') }}</p></section>
    <section v-if="d.professionalAnswer"><h4>{{ t('professional') }}</h4><p>{{ d.professionalAnswer.ordinary?.body?.answer }}</p><p>{{ t('CLINICIAN_AUTHORED') }}</p><p>{{ t('recorder') }}: {{ person(d.professionalAnswer.actorId) }} · {{ d.professionalAnswer.recordedAt }} · #{{ d.professionalAnswer.eventId }}</p></section>
    <dl><dt>{{ t('followUp') }}</dt><dd>{{ d.followUp || t('noNext') }}</dd><dt>{{ t('responsible') }}</dt><dd>{{ d.responsibleUserId == null ? t('unassigned') : person(d.responsibleUserId, true) }}</dd><dt>{{ t('dueDate') }}</dt><dd>{{ d.followUpDueDate || '—' }}</dd></dl>
    <p v-if="d.closeReason">{{ t('reason') }}: {{ t(d.closeReason) }}</p><p v-if="d.state==='CLOSED'">{{ t('notClinical') }}</p>
    <p v-if="d.managed && d.legacyMark">{{ t('legacyMark') }}: {{ d.legacyMark }}</p>
    <section v-if="d.familyAssistanceId" class="vq-notice"><p>{{ t('bSeparate') }}</p><router-link :to="`/care?patientId=${d.patientId}&assistanceId=${d.familyAssistanceId}`">{{ t('bLink') }}</router-link></section>
    <div class="vq-actions">
      <template v-if="d.managed"><button v-if="flow.allowed('EDIT') || flow.allowed('RECORD_ANSWER')" type="button" :disabled="flow.locked()" @click="$emit('ordinary', d)">{{ t('manual') }}</button><button v-for="action in d.allowedCommands||[]" v-show="flow.allowed(action)" :key="action" type="button" :disabled="flow.locked()" @click="flow.begin(action)">{{ t(action) }}</button><button v-if="flow.allowed('CLOSE')" type="button" :disabled="flow.locked()" @click="flow.begin('CLOSE',{reason:'WITHDRAWN'})">{{ t('withdraw') }}</button></template>
      <template v-else><button v-if="flow.allowed('ADOPT')" type="button" @click="flow.begin('ADOPT')">{{ t('ADOPT') }}</button><button v-if="d.legacyEditable" type="button" @click="$emit('ordinary', d)">{{ t('manual') }}</button></template>
      <button v-if="canHandover && d.followUp" type="button" :disabled="flow.locked()" @click="$emit('handover',d)">{{ t('handover') }}</button><button v-if="d.managed" type="button" @click="flow.loadEvents()">{{ t('history') }}</button><button type="button" @click="$emit('close')">{{ t('close') }}</button>
    </div>
    <section v-if="flow.state.eventPhase!=='idle'" aria-live="polite"><h4>{{ t('history') }}</h4><p v-if="flow.state.eventPhase!=='loaded'">{{ t(flow.state.eventPhase) }}</p><p v-else-if="!flow.state.events.length">{{ t('empty') }}</p>
      <article v-for="event in flow.state.events" :key="event.eventId" class="vq-card"><h5>{{ t(event.type) }} · v{{ event.version }}</h5><p>{{ t(event.fromState || 'OPEN') }} → {{ t(event.toState) }}</p><p>{{ t('recorder') }}: {{ person(event.actorId) }} · {{ event.recordedAt }}</p><p v-if="event.answerOrigin">{{ t(event.answerOrigin) }}</p><p v-if="event.reason">{{ t('reason') }}: {{ t(event.reason) }}</p><template v-for="field in ['title','description','answer','reportedClinician','reportedDate','followUp','dueDate','reasonNote','resultText']" :key="field"><p v-if="event.ordinary?.body?.[field]"><strong>{{ t(field==='answer'?'recordedAnswer':field==='title'?'titleField':field) }}:</strong> {{ event.ordinary.body[field] }}</p></template><p v-if="event.ordinary?.body?.resultReference">{{ t('reference') }}: {{ t(event.ordinary.body.resultReference.type) }} #{{ event.ordinary.body.resultReference.id }}</p><p v-if="Object.prototype.hasOwnProperty.call(event.ordinary?.body||{},'responsibleUserId')">{{ t('responsible') }}: {{ event.ordinary.body.responsibleUserId == null ? t('unassigned') : person(event.ordinary.body.responsibleUserId,true) }}</p><p v-if="event.ordinary?.body?.context">{{ t('context') }}: {{ t(event.ordinary.body.context.type) }} #{{ event.ordinary.body.context.id }}</p><button v-if="event.baselineAvailable" type="button" @click="flow.loadBaseline(event.eventId)">{{ t('baseline') }}</button></article>
      <div class="vq-actions"><button v-if="flow.state.eventMore" type="button" @click="flow.loadEvents(true)">{{ t('more') }}</button><button type="button" @click="flow.loadEvents(false)">{{ t('first') }}</button></div>
    </section>
    <section v-if="flow.state.baseline"><h4>{{ t('baseline') }}</h4><p>{{ t('baselineHelp') }}</p><dl v-for="(value, field) in flow.state.baseline.body?.baseline || {}" :key="field"><dt>{{ field }}</dt><dd><pre>{{ value == null ? '—' : value }}</pre></dd></dl></section>
  </section>
</template>
<script setup>
import { computed } from 'vue'
import { questionText as t } from '@/utils/visitQuestionCopy'
const props = defineProps({ flow: { type: Object, required: true }, canHandover: Boolean })
defineEmits(['ordinary','close','handover'])
const d = computed(() => props.flow.state.detail)
function person(id, responsible = false) { return `${Number(id) === Number(props.flow.state.capabilities?.actorId) ? t('me') : responsible ? t('currentResponsible') : t('recorder')} (${t('account')} ${id})` }
</script>
