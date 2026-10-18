<template>
 <section class="handover-summary" :aria-label="t('currentContent')">
  <p class="basis">{{ t('currentContent') }}</p>
  <p v-if="phase==='loading'" role="status">{{ t('loadingContent') }}</p>
  <p v-else-if="!source || !['AVAILABLE','NOT_CURRENT'].includes(source.contentStatus)" role="status">{{ t('contentUnavailable') }}</p>
  <p v-else-if="source.contentStatus==='NOT_CURRENT'" role="status">{{ t('historicalContent') }}</p>
  <template v-else><h3>{{ source.title || t('noTitle') }}</h3><p v-if="showNote" class="note">{{ source.note || t('noNote') }}</p></template>
  <template v-if="source?.responsibilityStatus">
   <p class="responsibility">{{ t(source.responsibilityStatus) }}</p>
   <dl><dt>{{ t('namedPerson') }}</dt><dd>{{ person(source.assigned) }}</dd><dt>{{ t('acceptedPerson') }}</dt><dd>{{ person(source.handler) }}</dd><dt>{{ t('originatorPerson') }}</dt><dd>{{ person(source.originator) }}</dd><dt>{{ t('deadlineUtc') }}</dt><dd>{{ source.deadlineAt || t('noDeadline') }}</dd></dl>
   <p v-if="source.assignedUserId && source.assignedCurrent===false" role="status">{{ t('assignedAccessLost') }}</p>
   <p v-if="source.acceptedBy && source.handlerCurrent===false" role="status">{{ t('acceptedAccessLost') }}</p>
   <p v-if="source.overdue" role="status">{{ t('overdue') }}</p>
  </template>
 </section>
</template>
<script setup>
import { t } from '@/utils/familyAssistanceCopy';
defineProps({ source: Object, phase: String, showNote: { type: Boolean, default: true } });
const person = value => value?.userId ? `${value.displayName || t('account')} #${value.userId}` : t('notRecorded');
</script>
<style scoped>
.handover-summary{min-width:0;overflow-wrap:anywhere}.basis{font-weight:600}.note{white-space:pre-wrap;line-height:1.65}.responsibility{font-weight:700}h3{font-size:1.15em;margin:.5rem 0}dl{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,2fr);gap:.4rem .7rem}dt{font-weight:600}dd{margin:0;min-width:0}@media(max-width:375px){dl{grid-template-columns:1fr}dd{margin-bottom:.4rem}}
</style>
