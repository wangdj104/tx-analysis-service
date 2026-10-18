<template>
  <section v-if="patientId" class="schedule-preview" :aria-label="t('schedulePreview')">
    <h2>{{ t('schedulePreview') }}</h2><p>{{ t('schedulePreviewScope') }}</p>
    <p v-if="flow.state.previewPhase==='loading'" role="status">{{ t('loading') }}</p>
    <p v-else-if="flow.state.previewPhase==='unavailable'" role="alert">{{ t('REQUEST_FAILED') }}</p>
    <button type="button" :disabled="!canLeave()" @click="refresh">{{ t('refresh') }}</button>
    <ul v-if="flow.state.preview"><li v-for="lane in flow.state.preview.lanes" :key="lane.sourceKind">
      <h3>{{ t(lane.sourceKind) }}</h3>
      <p v-if="lane.status==='EMPTY'">{{ t('scheduleLaneEmpty') }}</p>
      <p v-else-if="lane.status==='UNAVAILABLE'" role="status">{{ t('scheduleLaneUnavailable') }}</p>
      <template v-else><p>{{ lane.item.startWall?.replace('T',' ') }}<template v-if="lane.item.endWall"> – {{ lane.item.endWall.replace('T',' ') }}</template></p><p>{{ t(lane.item.nativeStatus) }}</p><button type="button" :disabled="!canLeave()" @click="open(lane.item,$event)">{{ t('open') }} #{{ lane.item.sourceId }}</button></template>
    </li></ul>
    <template v-if="inline && selected"><button type="button" :disabled="!canLeave()" @click="close">{{ t('closePreview') }}</button><AppointmentFollowUpPanel ref="panel" :patient-id="patientId" :source-kind="selected.sourceKind" :source-id="selected.sourceId" @changed="flow.loadPreview" /></template>
  </section>
</template>
<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { useVisitFollowUp } from '@/composables/useVisitFollowUp';
import { appointmentText as t } from '@/utils/visitFollowUpCopy';
import AppointmentFollowUpPanel from './AppointmentFollowUpPanel.vue';
const props=defineProps({patientId:[Number,String],inline:{type:Boolean,default:true}}),emit=defineEmits(['open']);
const flow=useVisitFollowUp(computed(()=>props.patientId)),selected=ref(null),panel=ref(null);let returnFocus=null,epoch=0;
function canLeave(){return panel.value?.protectNavigation()!==false;}
async function refresh(){if(!canLeave())return;await flow.loadPreview();}
async function open(row,event){if(!canLeave()||Number(row.patientId)!==Number(props.patientId))return;returnFocus=event?.currentTarget;if(!props.inline){emit('open',row);return;}selected.value={sourceKind:row.sourceKind,sourceId:row.sourceId};await nextTick();}
async function close(){if(!canLeave())return;selected.value=null;await nextTick();if(returnFocus?.isConnected)returnFocus.focus();}
function retire(){selected.value=null;returnFocus=null;}
// 身份退休立即清掉原typed来源；新患者只触发自己的有界读取，不为全部首页卡拉详情。
watch(()=>props.patientId,async()=>{const ticket=++epoch;retire();await nextTick();if(ticket===epoch&&props.patientId)await flow.loadPreview();},{immediate:true,flush:'sync'});
for(const name of ['auth-session-cleared','auth-session-changed','storage'])window.addEventListener(name,retire);
onUnmounted(()=>{epoch++;for(const name of ['auth-session-cleared','auth-session-changed','storage'])window.removeEventListener(name,retire);});
defineExpose({protectNavigation:canLeave,refresh});
</script>
<style scoped>
.schedule-preview{min-width:0;margin-block:1rem;padding:1rem;border:1px solid #cbd5e1;border-radius:10px;overflow-wrap:anywhere}.schedule-preview ul{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr));gap:1rem;list-style:none;padding:0}.schedule-preview li{padding:.75rem;border:1px solid #d5dde5;border-radius:8px}.schedule-preview button{font:inherit;min-height:44px;padding:.6rem .85rem;max-width:100%;white-space:normal;border:1px solid #728497;border-radius:7px;background:#f8fafc;color:#17344b}.schedule-preview button:focus-visible{outline:3px solid #1767ad;outline-offset:3px}.schedule-preview button:disabled{opacity:.6}
</style>
