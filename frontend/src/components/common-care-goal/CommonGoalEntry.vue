<template>
  <section class="cg-entry">
    <h4>{{ t(heading) }}</h4>
    <template v-if="entry">
      <p v-if="entry.feedbackSource">{{ t('feedbackSource') }}: {{ t(entry.feedbackSource) }}</p>
      <p v-if="entry.note">{{ entry.note }}</p><p v-if="entry.reason">{{ t('reason') }}: {{ entry.reason }}</p><p v-if="entry.result">{{ t('result') }}: {{ entry.result }}</p>
      <p>{{ t('recorder') }}: {{ entry.actorId }} · {{ t('recordedAt') }}: {{ entry.recordedAt }}</p>
      <p>{{ t('cycle') }} {{ entry.cycle }}<span v-if="entry.reportedDate"> · {{ t('reportedDate') }}: {{ entry.reportedDate }}</span></p>
    </template>
    <p v-else>{{ t(heading === 'progress' ? 'noProgress' : 'noFeedback') }}</p>
  </section>
</template>
<script setup>
import { goalText as t } from '@/utils/commonCareGoalCopy'
defineProps({ entry: Object, heading: { type: String, required: true } })
</script>
