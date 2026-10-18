<template>
  <section v-if="detail" class="cg-card" aria-labelledby="cg-detail-heading">
    <header class="cg-row"><h3 id="cg-detail-heading">{{ detail.goal.content.title }}</h3><button type="button" :disabled="flow.locked()" @click="flow.closeDetail">{{ t('back') }}</button></header>
    <p class="cg-notice" role="status">{{ t(detail.responsibilityStatus) }}</p>
    <p>{{ t('creator') }}: {{ person(detail.creator) }} <small>{{ t('account') }}: {{ detail.goal.creatorId }}</small></p>
    <CommonGoalSnapshot :goal="detail.goal" :nominee="detail.nominee" />
    <p v-if="detail.recordOwner">{{ t('owner') }} · {{ t('ownerHelp') }}</p>
    <CommonGoalEntry :entry="detail.latestProgress" heading="progress" />
    <CommonGoalEntry :entry="detail.latestFeedback" heading="feedback" />
    <p>{{ t('feedbackHelp') }}</p><p v-if="detail.goal.state==='ACTIVE'">{{ t('completionHelp') }}</p>
    <CommonGoalEntry v-if="detail.closure" :entry="detail.closure" heading="closure" />
    <div class="cg-actions"><button v-for="operation in actions" :key="operation" type="button" :disabled="flow.locked()" @click="flow.begin(operation)">{{ t(operation) }}</button></div>
    <section aria-labelledby="cg-history-heading">
      <h3 id="cg-history-heading">{{ t('history') }}</h3><p>{{ t('historyHelp') }}</p>
      <div class="cg-actions"><button type="button" :disabled="flow.locked() || history.phase==='loading'" @click="flow.loadHistory()">{{ t('first') }}</button><button v-if="history.nextCursor" type="button" :disabled="flow.locked() || history.phase==='loading'" @click="flow.loadHistory(true)">{{ t('more') }}</button></div>
      <p v-if="history.phase==='loading'" role="status">{{ t('loading') }}</p><p v-if="history.phase==='unavailable'" role="alert">{{ t('unavailable') }}</p><p v-if="history.phase==='loaded' && !history.items.length">{{ t('noEvents') }}</p>
      <ol class="cg-list"><li v-for="row in history.items" :key="row.sequence" class="cg-card"><p>{{ t(row.operation) }} · {{ t('cycle') }} {{ row.cycle }} · {{ t('version') }} {{ row.sequence }}</p><p>{{ row.actorDisplayName || t('unnamed') }} <small>{{ t('account') }}: {{ row.actorId }}</small></p><p>{{ t('recordedAt') }}: {{ row.recordedAt }}</p><button type="button" :disabled="flow.locked()" @click="flow.loadEvent(row.sequence)">{{ t('event') }}</button></li></ol>
      <p v-if="flow.state.eventPhase==='loading'" role="status">{{ t('loading') }}</p><p v-if="flow.state.eventPhase==='unavailable'" role="alert">{{ t('unavailable') }}</p>
      <article v-if="flow.state.event" class="cg-card">
        <h4>{{ t(flow.state.event.operation) }} · {{ t('version') }} {{ flow.state.event.sequence }}</h4>
        <CommonGoalEntry :entry="flow.state.event" heading="history" />
        <h4>{{ t('before') }}</h4><CommonGoalSnapshot v-if="flow.state.event.before" :goal="flow.state.event.before" /><p v-else>{{ t('noBefore') }}</p>
        <h4>{{ t('after') }}</h4><CommonGoalSnapshot :goal="flow.state.event.after" />
      </article>
    </section>
  </section>
</template>
<script setup>
import { computed } from 'vue'
import { goalOperations } from '@/utils/commonCareGoal'
import { goalText as t, goalPerson as person } from '@/utils/commonCareGoalCopy'
import CommonGoalSnapshot from './CommonGoalSnapshot.vue'
import CommonGoalEntry from './CommonGoalEntry.vue'
const props = defineProps({ flow: { type: Object, required: true } })
const detail = computed(() => props.flow.state.detail), history = computed(() => props.flow.state.history)
const actions = computed(() => goalOperations.filter(operation => operation !== 'CREATE' && props.flow.allowed(operation)))
</script>
