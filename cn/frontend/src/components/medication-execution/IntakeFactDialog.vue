<template>
 <form class="intake-fact" @submit.prevent="submit" :aria-label="t(operation)">
  <h3>{{t(operation)}}</h3><p>{{t('factHelp')}}</p><fieldset :disabled="disabled">
   <template v-if="isFact">
    <label v-if="operation==='CORRECT_INTAKE'">{{t('actualStatus')}}<select v-model="draft.status" required><option value="">{{t('select')}}</option><option value="TAKEN">{{t('TAKEN')}}</option><option value="SKIPPED">{{t('SKIPPED')}}</option></select></label>
    <label>{{t('actualUtc')}}<input v-model="draft.actualAt" type="text" required placeholder="YYYY-MM-DDTHH:mm:ssZ" autocomplete="off"/></label>
    <label>{{t('attribution')}}<select v-model="draft.attribution" required><option value="">{{t('select')}}</option><option v-if="intake.canSelf" value="SELF">{{t('SELF')}}</option><option value="ASSISTED">{{t('ASSISTED')}}</option></select></label>
    <template v-if="draft.status!=='SKIPPED'">
     <label>{{t('amountEvidence')}}<select v-model="draft.amountBasis" required><option value="">{{t('select')}}</option><option value="ACTUAL">{{t('ACTUAL')}}</option><option value="UNKNOWN">{{t('UNKNOWN_AMOUNT')}}</option><option v-if="intake.provenance==='VERIFIED_ORDER' && intake.plannedQuantity && intake.plannedUnit" value="CONFIRMED_PLAN">{{t('CONFIRMED_PLAN')}} · {{intake.plannedQuantity}} {{intake.plannedUnit}}</option></select></label>
     <div v-if="draft.amountBasis==='ACTUAL'" class="amount-row"><label>{{t('quantity')}}<input v-model="draft.quantity" inputmode="decimal" required autocomplete="off"/></label><label>{{t('unit')}}<input v-model="draft.unit" required maxlength="20" autocomplete="off"/></label></div>
     <p v-if="draft.amountBasis==='UNKNOWN'">{{t('unknownStock')}}</p>
    </template>
    <template v-if="operation==='CORRECT_INTAKE'">
     <IntakeFactReview :fact="intake.latestFact"/><p>{{t('originalAction')}} #{{draft.originalActionId}}</p><label>{{t('historicalUnit')}}<input v-model="draft.historicalUnit" maxlength="20" autocomplete="off"/></label><p>{{t('historicalUnitHelp')}}</p>
     <template v-if="intake.currentCount"><p>{{t('currentCount')}} #{{intake.currentCount.id}} · {{intake.currentCount.countedAt}}</p><label>{{t('countChoice')}}<select v-model="draft.countChoice"><option :value="undefined">{{t('select')}}</option><option value="ACTUAL_AMOUNT">{{t('ACTUAL_AMOUNT')}}</option><option value="INCLUDED_IN_COUNT">{{t('INCLUDED_IN_COUNT')}}</option></select></label></template>
    </template>
   </template>
   <label v-if="operation==='END_INTAKE_UNRESOLVED'">{{t('endReason')}}<select v-model="draft.reasonCode" required><option value="">{{t('select')}}</option><option v-for="code in ['NO_RESPONSE','CONTACT_FAILED','OUTCOME_UNKNOWN']" :key="code" :value="code">{{t(code)}}</option></select></label>
   <label v-if="needsReason">{{t('reason')}}<textarea v-model="draft.reason" required maxlength="1000" rows="3"/></label>
   <p v-if="operation==='REOPEN_INTAKE_FOLLOW_UP'">{{t('reopenHelp')}}</p><p v-if="operation==='END_INTAKE_UNRESOLVED'">{{t('endHelp')}}</p>
   <button type="submit">{{t('recordFact')}}</button><button type="button" @click="$emit('close')">{{t('close')}}</button>
  </fieldset>
 </form>
</template>
<script setup>
import {computed} from 'vue';import IntakeFactReview from '@/components/medication-execution/IntakeFactReview.vue';import {medicationText as t} from '@/utils/medicationExecutionCopy';
const props=defineProps({draft:{type:Object,required:true},operation:{type:String,required:true},intake:{type:Object,default:()=>({})},disabled:Boolean});const emit=defineEmits(['submit','close']);
const isFact=computed(()=>['RECORD_TAKEN','RECORD_SKIPPED','CORRECT_INTAKE'].includes(props.operation));const needsReason=computed(()=>['CORRECT_INTAKE','RECORD_CONTACT','END_INTAKE_UNRESOLVED','REOPEN_INTAKE_FOLLOW_UP'].includes(props.operation));
function submit(){const body={...props.draft};for(const key of Object.keys(body))if(body[key]===undefined||body[key]==='')delete body[key];if(isFact.value){if(body.status==='SKIPPED'){body.amountBasis='NOT_APPLICABLE';delete body.quantity;delete body.unit;}else if(body.amountBasis!=='ACTUAL'){delete body.quantity;delete body.unit;}}if(body.countChoice&&props.intake.currentCount){body.countId=props.intake.currentCount.id;body.countVersion=props.intake.currentCount.version;}else{delete body.countId;delete body.countVersion;}emit('submit',body);}
</script>
<style scoped>.intake-fact{padding:16px;border:1px solid var(--line);border-radius:12px;background:var(--paper);color:var(--ink-950);min-width:0;overflow-wrap:anywhere}.intake-fact fieldset{border:0;padding:0;min-width:0}.intake-fact label{display:flex;flex-direction:column;gap:5px;margin:12px 0}.intake-fact input,.intake-fact select,.intake-fact textarea,.intake-fact button{font:inherit;min-height:44px;max-width:100%;padding:8px 12px;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:var(--ink-950);box-sizing:border-box}.intake-fact button{white-space:normal;margin:4px}.intake-fact :focus-visible{outline:3px solid var(--care-600);outline-offset:2px}.amount-row{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr));gap:12px}@media(max-width:600px){.intake-fact{padding:12px}.intake-fact button{width:100%;margin:4px 0}}</style>
