<template>
 <section v-if="flow.state.dialog && flow.state.draft" class="assistance-dialog" role="dialog" aria-modal="false" :aria-label="t(flow.state.operation)">
  <h3 tabindex="-1" ref="heading">{{t(flow.state.operation)}}</h3><form @submit.prevent="submit">
   <template v-if="flow.state.operation==='CREATE'">
    <p v-if="flow.state.handoverDraft">{{t('lockedSource')}} #{{flow.state.handoverSource?.careItemId}}</p>
    <template v-else><p>{{t('otherRequestHelp')}}</p><label>{{t('sourceKind')}}<select v-model="flow.state.draft.sourceKind" :disabled="busy"><option value="CARE_ITEM">{{t('CARE_ITEM')}}</option><option value="CARE_APPOINTMENT">{{t('CARE_APPOINTMENT')}}</option></select></label><label>{{t('sourceId')}}<input v-model="flow.state.draft.sourceId" inputmode="numeric" required :disabled="busy"></label></template>
    <label>{{t('deadlineUtc')}}<input v-model="flow.state.draft.deadlineUtc" type="datetime-local" step="60" :disabled="busy"></label><p>{{t('utcHelp')}}</p><p>{{t('deadlineWillBe')}} {{deadline || t('noDeadline')}}</p>
   </template>
   <PersonPicker v-if="flow.state.handoverDraft && 'assignedUserId' in flow.state.draft" :flow="flow" :required="flow.state.operation!=='CREATE'" :disabled="busy || locked"/>
   <label v-else-if="'assignedUserId' in flow.state.draft">{{t('handler')}}<input v-model="flow.state.draft.assignedUserId" inputmode="numeric" :required="flow.state.operation!=='CREATE'" :disabled="busy || locked"></label>
   <label v-if="'reason' in flow.state.draft">{{t('reason')}}<textarea v-model="flow.state.draft.reason" maxlength="1000" required :disabled="busy || locked" /></label><p v-if="flow.state.operation==='CLOSE_UNRESOLVED'">{{t('adminReason')}}</p>
   <p v-if="flow.state.operation==='RECONFIRM_APPOINTMENT'">{{t('fingerprint')}}: {{flow.state.draft.fingerprint}}<br>{{flow.state.detail?.source.time}} · {{t(flow.state.detail?.source.status)}}</p>
   <p v-if="flow.state.phase==='conflict'" role="alert">{{t('conflictDecision')}}</p>
   <button v-if="flow.state.phase==='conflict'" type="button" @click="flow.refreshConflict()">{{t('reviewWinner')}}</button>
   <button v-if="flow.state.phase==='invalid' || flow.state.phase==='conflict' && flow.state.reviewedWinner" type="button" @click="edit">{{t('editCurrent')}}</button>
   <button type="submit" :disabled="busy || locked">{{t('save')}}</button><button type="button" @click="flow.close">{{t('close')}}</button>
  </form>
 </section>
</template>
<script setup>
import { computed, ref, watch, nextTick } from 'vue';
import { handoverUtcDeadline } from '@/utils/familyHandover';
import { t } from '@/utils/familyAssistanceCopy';
import PersonPicker from './PersonPicker.vue';
const props=defineProps({flow:{type:Object,required:true}}),heading=ref(null);
const busy=computed(()=>['unknown','submitting'].includes(props.flow.state.phase));
const locked=computed(()=>['invalid','conflict'].includes(props.flow.state.phase));
const deadline=computed(()=>{try{return handoverUtcDeadline(props.flow.state.draft?.deadlineUtc);}catch{return t('invalidDeadline');}});
function positive(v){const n=Number(v);if(!/^[1-9][0-9]*$/.test(String(v))||!Number.isSafeInteger(n))throw new TypeError('INVALID_ID');return n;}
async function submit(){const flow=props.flow,d=flow.state.draft;try{let body={...d};if(flow.state.handoverDraft)body=flow.handoverBody();else if(flow.state.operation==='CREATE'){body={sourceKind:d.sourceKind,[d.sourceKind==='CARE_ITEM'?'careItemId':'careAppointmentId']:positive(d.sourceId)};if(d.assignedUserId)body.assignedUserId=positive(d.assignedUserId);const utc=handoverUtcDeadline(d.deadlineUtc);if(utc)body.deadlineAt=utc;}else if('assignedUserId' in body)body.assignedUserId=positive(body.assignedUserId);await flow.submit(body);}catch{flow.state.error='INVALID_REQUEST';}}
async function edit(){const flow=props.flow;if(!flow.editAfterError())return;if(flow.state.handoverDraft && ['CREATE','ASSIGN','HANDOFF'].includes(flow.state.operation))await flow.loadHandoverCandidates();}
watch(()=>props.flow.state.dialog,async visible=>{if(visible){await nextTick();heading.value?.focus();}});
</script>
<style scoped>
.assistance-dialog{min-width:0;margin:1rem 0;padding:1rem;border:2px solid var(--el-border-color);border-radius:12px;background:var(--el-bg-color);overflow-wrap:anywhere}form{display:grid;gap:.6rem;min-width:0}label{display:grid;gap:.3rem;min-height:44px;min-width:0}input,select,textarea,button{box-sizing:border-box;min-height:44px;min-width:44px;max-width:100%;width:100%;padding:.5rem;border:1px solid var(--el-border-color);border-radius:6px;background:var(--el-bg-color);color:var(--el-text-color-primary);font:inherit}textarea{resize:vertical}button{cursor:pointer;white-space:normal;overflow-wrap:anywhere}button:disabled{opacity:.55;cursor:wait}input:focus-visible,select:focus-visible,textarea:focus-visible,button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.care-senior input,.care-senior select,.care-senior textarea,.care-senior button{min-height:48px}@media(max-width:375px){.assistance-dialog{padding:.65rem}}
</style>
