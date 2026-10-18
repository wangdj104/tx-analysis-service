<template>
  <section class="review-detail" data-testid="reconciliation-detail">
    <h3>{{detail.medicationName}} · {{t('version')}} {{detail.version}}</h3>
    <p>{{t(detail.reconciliation?.state || 'DRAFT')}} · {{t('manualStatus')}}</p>
    <p v-if="detail.reconciliation?.state==='READY'" class="notice">{{t('notApplied')}}</p>
    <p v-if="detail.reviewRequired" class="notice">{{t('reviewRequired')}}</p>
    <dl v-if="detail.revision"><dt>{{t('source')}}</dt><dd>{{t(detail.revision.source.kind==='MANUAL'?'manual':'prescription')}}<template v-if="detail.revision.source.version"> · {{t('sourceVersion')}} {{detail.revision.source.version}} · #{{detail.revision.source.prescriptionId}}/{{detail.revision.source.itemId}}</template><p v-if="detail.revision.source.provenance">{{detail.revision.source.provenance}}</p></dd><dt>{{t('revision')}}</dt><dd>{{detail.revision.revisionNo}} · #{{detail.revision.id}}</dd><dt>{{t('selectedDoctor')}}</dt><dd>{{doctorName || t('noDoctor')}}</dd><dt>{{t('action')}}</dt><dd>{{t(detail.revision.mapping.action==='STOP'?'stop':'change')}}</dd><template v-if="detail.revision.mapping.action==='CHANGE'"><dt>{{t('quantity')}}</dt><dd>{{detail.revision.mapping.quantity}} {{detail.revision.mapping.unit}}</dd><dt>{{t('times')}}</dt><dd>{{detail.revision.mapping.times.join(', ')}} · {{detail.revision.mapping.weekdays.map(n=>days[n-1]).join(', ')}}</dd></template><dt>{{t('zone')}}</dt><dd>{{detail.revision.mapping.scheduleZone}}</dd><dt>{{t('effectiveLocal')}}</dt><dd>{{detail.revision.mapping.effectiveLocal}} · {{detail.revision.mapping.effectiveOffsetSeconds}}</dd><dt>{{t('effectiveAt')}}</dt><dd>{{detail.revision.mapping.effectiveAt}}</dd><template v-if="detail.revision.mapping.endAt"><dt>{{t('endAt')}}</dt><dd>{{detail.revision.mapping.endAt}}</dd></template></dl>
    <p><strong>{{t('next')}}:</strong> {{t(detail.nextAction)}}</p>
    <ul v-if="detail.blockedReasons?.length"><li v-for="reason in detail.blockedReasons" :key="reason">{{t(reason)}}</li></ul>
    <div class="actions"><button v-for="operation in detail.allowedCommands || []" :key="operation" type="button" :disabled="disabled" @click="$emit('command',operation)">{{t(operation)}}</button></div>
  </section>
</template>
<script setup>
import {computed} from 'vue';
import {medicationText as t,medicationWeekdays as days} from '@/utils/medicationExecutionCopy';
const props=defineProps({detail:{type:Object,required:true},doctors:{type:Array,default:()=>[]},disabled:Boolean});defineEmits(['command']);
const doctorName=computed(()=>{const id=props.detail.reconciliation?.selectedVerifierId;return props.doctors.find(d=>d.userId===id)?.displayName||(id?`#${id}`:null);});
</script>
<style scoped>
.review-detail{border:1px solid var(--line);border-radius:12px;padding:16px;min-width:0;color:var(--ink-950);background:var(--paper)}dl{display:grid;grid-template-columns:minmax(100px,1fr) minmax(0,3fr);gap:8px}dt{color:var(--ink-500)}dd{margin:0;overflow-wrap:anywhere}p{overflow-wrap:anywhere}.notice{padding:12px;background:var(--surface-subtle)}.actions{display:flex;flex-wrap:wrap;gap:8px}button{min-height:44px;border:1px solid var(--line);border-radius:8px;padding:8px 12px;background:var(--paper);color:var(--ink-950);font:inherit;white-space:normal}button:focus-visible{outline:3px solid var(--care-600)}@media(max-width:480px){dl{grid-template-columns:1fr}dt{font-weight:600}.actions button{width:100%}}
</style>
