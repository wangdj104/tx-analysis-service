<template>
 <fieldset class="handover-picker" :disabled="disabled">
  <legend>{{ t('choosePerson') }}</legend><p>{{ t('candidateHelp') }}</p>
  <label>{{ t('searchName') }}<input :value="flow.state.candidateSearch" maxlength="100" autocomplete="off" @input="search($event.target.value)"></label>
  <button type="button" @click="flow.loadHandoverCandidates(flow.state.operation,flow.state.candidateSearch)">{{ t('refreshPeople') }}</button>
  <p v-if="flow.state.candidatePhase==='loading'" role="status">{{ t('loadingPeople') }}</p>
  <p v-else-if="flow.state.candidatePhase==='unavailable'" role="status">{{ t(flow.state.error==='HANDOVER_CANDIDATES_OVERFLOW'?'narrowSearch':'peopleUnavailable') }}</p>
  <template v-else-if="flow.state.candidatePhase==='ready'">
   <label>{{ t('choosePerson') }}<select v-model="flow.state.draft.assignedUserId" :required="required"><option value="">{{ t(required?'chooseExplicitly':'leaveUnassigned') }}</option><option v-for="person in flow.state.candidates" :key="person.userId" :value="person.userId">{{ person.displayName || t('account') }} #{{ person.userId }}</option></select></label>
   <p v-if="!flow.state.candidates.length" role="status">{{ t('noPeople') }}</p>
  </template>
 </fieldset>
</template>
<script setup>
import { t } from '@/utils/familyAssistanceCopy';
const props=defineProps({ flow: { type:Object, required:true }, required:Boolean, disabled:Boolean });
// 搜索值由同一读取 lane 持有；每次新搜索立即清旧人名与选择，不保留组件私有副本。
function search(value) { props.flow.loadHandoverCandidates(props.flow.state.operation,[...value].slice(0,50).join('')); }
</script>
<style scoped>
.handover-picker{min-width:0;margin:0;padding:.7rem;border:1px solid var(--el-border-color);border-radius:8px;overflow-wrap:anywhere}label{display:grid;gap:.3rem;margin:.5rem 0}input,select,button{box-sizing:border-box;min-height:44px;min-width:44px;max-width:100%;width:100%;padding:.5rem;border:1px solid var(--el-border-color);border-radius:6px;background:var(--el-bg-color);color:inherit;font:inherit}button{cursor:pointer}input:focus-visible,select:focus-visible,button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.care-senior input,.care-senior select,.care-senior button{min-height:48px}
</style>
