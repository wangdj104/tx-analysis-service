<template>
 <el-dialog :model-value="flow.state.dialog" title="Stock evidence" width="min(520px,94vw)" :close-on-click-modal="false" @close="flow.close()">
  <form v-if="draft" @submit.prevent="submit"><p>Patient {{ flow.state.detail?.patientId }} · Stock {{ flow.state.detail?.stockId }}</p>
   <label v-if="'assignedUserId' in draft">Current medication writer ID<input v-model="draft.assignedUserId" inputmode="numeric" required/></label>
   <label v-if="'quantity' in draft">Actual quantity<input v-model="draft.quantity" inputmode="decimal" required pattern="(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,3})?"/></label>
   <label v-if="'unit' in draft">Exact ledger unit<input :value="draft.unit" readonly/></label>
   <label v-if="'dailyUseQuantity' in draft">Known daily use, or leave blank for unknown<input v-model="draft.dailyUseQuantity" inputmode="decimal"/></label>
   <label v-if="'actualAt' in draft">Actually received at (UTC, for example 2026-10-14T12:00:00Z)<input v-model="draft.actualAt" required/></label>
   <label v-if="'countedAt' in draft">Physically counted at (UTC)<input v-model="draft.countedAt" required/></label>
   <template v-if="'warningQuantity' in draft"><label>Quantity warning threshold<input v-model="draft.warningQuantity" inputmode="decimal" required/></label><label>Days warning threshold<input v-model="draft.warningDays" type="number" min="0" max="36500" required/></label></template>
   <fieldset v-if="'countChoice' in draft"><legend>Was this actual fact included in the current physical count?</legend><label><input v-model="draft.countChoice" type="radio" value="ACTUAL_AMOUNT" required/>Actual amount was not included; apply its ledger effect</label><label><input v-model="draft.countChoice" type="radio" value="INCLUDED_IN_COUNT" required :disabled="!draft.countId"/>Included in the displayed current count; apply zero original effect</label><p>Nothing is selected for you. Unknown quantity or historical unit stays unresolved.</p></fieldset>
   <label>Evidence / reason<textarea v-model="draft.reason" maxlength="1000" required/></label>
   <p v-if="['invalid','conflict'].includes(flow.state.phase)" role="alert">{{ flow.state.error }}. Refresh the current stock and review the evidence, then start a new command.</p>
   <button type="submit" :disabled="['submitting','unknown','invalid','conflict'].includes(flow.state.phase)">Save evidence</button><button type="button" @click="flow.close()">Close</button>
  </form>
 </el-dialog>
</template>
<script setup>
import {computed} from 'vue';const props=defineProps({flow:{type:Object,required:true}});const draft=computed(()=>props.flow.state.draft);
// countChoice: '' is intentional. No count-inclusion default is safe.
function submit(){const d=draft.value;if(!d)return;if('assignedUserId' in d)d.assignedUserId=Number(d.assignedUserId);if('warningDays' in d)d.warningDays=Number(d.warningDays);if('dailyUseQuantity' in d&&!d.dailyUseQuantity)delete d.dailyUseQuantity;if('countChoice' in d&&d.countChoice==='ACTUAL_AMOUNT'&&!d.countId){delete d.countId;delete d.countVersion;}props.flow.submit();}
</script>
<style scoped>label{display:block;min-height:44px;margin:.75rem 0}input:not([type=radio]),textarea{box-sizing:border-box;width:100%;min-height:44px;color:var(--el-text-color-primary);background:var(--el-bg-color);border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);padding:.5rem}button{min-height:44px;min-width:44px;padding:.6rem;margin:.25rem}input[type=radio]{min-height:24px;min-width:24px;vertical-align:middle}fieldset{border:1px solid var(--el-border-color)}textarea{min-height:88px}</style>
