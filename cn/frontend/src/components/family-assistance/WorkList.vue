<template>
 <section class="handover-work" :aria-label="t('workTitle')">
  <header><h3>{{ t('workTitle') }}</h3><button type="button" :disabled="flow.state.workPhase==='loading'" @click="flow.loadHandoverWork()">{{ t('firstPage') }}</button></header>
  <p>{{ t('pageHelp') }}</p><p v-if="flow.state.workPhase==='loading'" role="status">{{ t('loadingContent') }}</p><p v-else-if="flow.state.workPhase==='unavailable'" role="status">{{ t('workUnavailable') }}</p><p v-else-if="flow.state.workPhase==='ready' && !flow.state.workItems.length">{{ t('noWork') }}</p>
  <ul><li v-for="row in flow.state.workItems" :key="row.assistanceId"><SourceSummary :source="row" :show-note="false"/><button type="button" :disabled="busy" @click="$emit('select-handover',row.assistanceId)">{{ t('openCurrent') }} #{{ row.assistanceId }}</button></li></ul>
  <p v-if="flow.state.workPhase==='ready'">{{ t('pageRows') }} {{ flow.state.workItems.length }}</p><button v-if="flow.state.workMore" type="button" :disabled="flow.state.workPhase==='loading'" @click="flow.loadHandoverWork(true)">{{ t('morePage') }}</button>
 </section>
</template>
<script setup>
import { computed } from 'vue';
import { t } from '@/utils/familyAssistanceCopy';
import SourceSummary from './SourceSummary.vue';
const props=defineProps({flow:{type:Object,required:true}});
defineEmits(['select-handover']);
const busy=computed(()=>['unknown','submitting'].includes(props.flow.state.phase));
</script>
<style scoped>
.handover-work{min-width:0;overflow-wrap:anywhere}header{display:flex;flex-wrap:wrap;gap:.5rem;align-items:center;justify-content:space-between}ul{list-style:none;padding:0}li{padding:.8rem 0;border-bottom:1px solid var(--el-border-color)}button{min-height:44px;min-width:44px;max-width:100%;padding:.5rem .8rem;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-fill-color-light);color:inherit;font:inherit;white-space:normal;overflow-wrap:anywhere;cursor:pointer}button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.care-senior button{min-height:48px}@media(max-width:600px){button{width:100%}}
</style>
