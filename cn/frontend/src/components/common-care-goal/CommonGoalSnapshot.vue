<template>
  <section v-if="goal" class="cg-snapshot">
    <h4>{{ goal.content.title }}</h4>
    <p>{{ t(goal.state) }} · {{ t('cycle') }} {{ goal.cycle }} · {{ t('version') }} {{ goal.version }}</p>
    <dl>
      <dt>{{ t('goalText') }}</dt><dd>{{ goal.content.goalText }}</dd>
      <dt>{{ t('progressCriterion') }}</dt><dd>{{ goal.content.progressCriterion }}</dd>
      <dt>{{ t('responsibility') }}</dt><dd>{{ goal.content.responsibility }}</dd>
      <dt>{{ t('reviewDate') }}</dt><dd>{{ goal.content.reviewDate || '—' }}</dd>
      <dt>{{ t('nominee') }}</dt><dd v-if="goal.content.nomineeId != null">{{ person(nominee) }} <small>{{ t('account') }}: {{ goal.content.nomineeId }}</small></dd><dd v-else>{{ t('unassigned') }}</dd>
      <template v-if="goal.acceptedBy != null"><dt>{{ t('acceptedBy') }}</dt><dd>{{ goal.acceptedBy }}</dd><dt>{{ t('acceptedAt') }}</dt><dd>{{ goal.acceptedAt }}</dd></template>
    </dl>
  </section>
</template>
<script setup>
import { goalText as t, goalPerson as person } from '@/utils/commonCareGoalCopy'
defineProps({ goal: Object, nominee: Object })
</script>
