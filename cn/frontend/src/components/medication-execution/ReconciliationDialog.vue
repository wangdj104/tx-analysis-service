<template>
  <section class="reconciliation-editor" role="dialog" :aria-label="t('manualStatus')" data-testid="reconciliation-dialog">
    <h3>{{ t(draft.source ? 'manualStatus' : operation) }}</h3>
    <p v-if="draft.source">{{ t('sourceHelp') }}</p>
    <form @submit.prevent="$emit('submit')">
      <fieldset :disabled="disabled">
        <template v-if="draft.source">
          <div class="review-fields">
            <label>{{ t('selectedDoctor') }}<select v-model="draft.selectedVerifierId"><option :value="null">{{ t('noDoctor') }}</option><option v-for="doctor in doctors" :key="doctor.userId" :value="doctor.userId">{{ doctor.displayName }}</option></select></label>
            <label>{{ t('source') }}<select :value="draft.source.kind" @change="changeSource($event.target.value)"><option value="MANUAL">{{ t('manual') }}</option><option value="PRESCRIPTION">{{ t('prescription') }}</option></select></label>
            <label v-if="draft.source.kind==='MANUAL'" class="wide">{{ t('provenance') }}<textarea v-model="draft.source.provenance" required maxlength="1000" rows="3"/></label>
            <label v-else class="wide">{{ t('sourceItem') }}<select :value="draft.source.itemId || ''" required @change="selectSource($event.target.value)"><option value="" disabled>{{ t('select') }}</option><option v-for="source in sources" :key="source.itemId" :value="source.itemId" :disabled="source.status!=='ACTIVE'">{{ t('version') }} {{source.version}} · {{source.drugName}} · {{source.dosage}} · {{source.frequency}}</option></select></label>
            <label>{{ t('action') }}<select :value="draft.mapping.action" @change="changeAction($event.target.value)"><option value="CHANGE">{{t('change')}}</option><option value="STOP">{{t('stop')}}</option></select></label>
            <template v-if="draft.mapping.action==='CHANGE'">
              <label>{{ t('quantity') }}<input v-model="draft.mapping.quantity" inputmode="decimal" required pattern="(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,3})?" autocomplete="off" /></label>
              <label>{{ t('unit') }}<input v-model="draft.mapping.unit" required maxlength="20" autocomplete="off"/></label>
              <label>{{ t('times') }}<input v-model="times" required placeholder="08:00,20:00" autocomplete="off"/></label>
              <fieldset class="weekday-group wide"><legend>{{ t('weekdays') }}</legend><label v-for="(day,index) in days" :key="index"><input v-model="draft.mapping.weekdays" type="checkbox" :value="index+1"/>{{day}}</label></fieldset>
            </template>
            <label>{{ t('zone') }}<input v-model="draft.mapping.scheduleZone" required placeholder="Asia/Shanghai" autocomplete="off"/></label>
            <label>{{ t('effectiveLocal') }}<input v-model="draft.mapping.effectiveLocal" type="datetime-local" step="1" required/></label>
            <label>{{ t('effectiveOffset') }}<input v-model.number="draft.mapping.effectiveOffsetSeconds" type="number" min="-64800" max="64800" step="1" required/></label>
            <label>{{ t('effectiveAt') }}<input v-model="draft.mapping.effectiveAt" required placeholder="2026-10-15T00:00:00Z" autocomplete="off"/></label>
            <label class="check wide"><input type="checkbox" :checked="hasEnd" @change="toggleEnd($event.target.checked)"/>{{ t('end') }}</label>
            <template v-if="hasEnd"><label>{{ t('endLocal') }}<input v-model="draft.mapping.endLocal" type="datetime-local" step="1" required/></label><label>{{ t('endOffset') }}<input v-model.number="draft.mapping.endOffsetSeconds" type="number" min="-64800" max="64800" step="1" required/></label><label>{{ t('endAt') }}<input v-model="draft.mapping.endAt" required placeholder="2026-10-16T00:00:00Z"/></label></template>
          </div>
          <p v-if="!draft.selectedVerifierId" class="review-notice">{{ t('noDoctorHelp') }}</p>
        </template>
        <label v-else-if="operation!=='VERIFY_RECONCILIATION'">{{t('reason')}}<textarea v-model="draft.reason" rows="3" required maxlength="1000" :placeholder="t('reasonHelp')"/></label>
        <p v-else>{{t('notApplied')}}</p>
        <button type="submit">{{ t(draft.source?'save':operation) }}</button>
      </fieldset>
    </form>
    <button type="button" @click="$emit('close')">{{t('close')}}</button>
  </section>
</template>
<script setup>
import {computed} from 'vue';
import {medicationText as t,medicationWeekdays as days} from '@/utils/medicationExecutionCopy';
const props=defineProps({draft:{type:Object,required:true},operation:{type:String,required:true},doctors:{type:Array,default:()=>[]},sources:{type:Array,default:()=>[]},disabled:Boolean});
defineEmits(['submit','close']);
const times=computed({get:()=>props.draft.mapping?.times?.join(',')||'',set:value=>{props.draft.mapping.times=value.split(',').map(v=>v.trim()).filter(Boolean);}});
const hasEnd=computed(()=>Object.hasOwn(props.draft.mapping||{},'endAt'));
function changeSource(kind){props.draft.source=kind==='MANUAL'?{kind,provenance:''}:{kind,prescriptionId:null,itemId:null,version:null};}
function selectSource(value){const item=props.sources.find(source=>source.itemId===Number(value));if(item)props.draft.source={kind:'PRESCRIPTION',prescriptionId:item.prescriptionId,itemId:item.itemId,version:item.version};}
function changeAction(action){props.draft.mapping.action=action;if(action==='STOP')for(const key of ['quantity','unit','times','weekdays'])delete props.draft.mapping[key];else Object.assign(props.draft.mapping,{quantity:'',unit:'',times:[],weekdays:[]});}
function toggleEnd(enabled){if(enabled)Object.assign(props.draft.mapping,{endLocal:'',endOffsetSeconds:'',endAt:''});else for(const key of ['endLocal','endOffsetSeconds','endAt'])delete props.draft.mapping[key];}
</script>
<style scoped>
.reconciliation-editor{border:1px solid var(--line);padding:16px;border-radius:12px;background:var(--paper);color:var(--ink-950);min-width:0}.review-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr));gap:14px}.wide{grid-column:1/-1}fieldset{border:0;padding:0;margin:0;min-width:0}label{display:flex;flex-direction:column;gap:6px;min-width:0}input,select,textarea,button{font:inherit;min-height:44px;max-width:100%;box-sizing:border-box;border:1px solid var(--line);border-radius:7px;padding:8px;background:var(--paper);color:var(--ink-950)}input,select,textarea{width:100%}textarea{resize:vertical}.weekday-group{display:flex;flex-wrap:wrap;gap:8px}.weekday-group label,.check{display:flex;flex-direction:row;align-items:center;min-height:44px}.weekday-group input,.check input{width:22px;min-height:22px}button{margin:12px 8px 0 0;white-space:normal;cursor:pointer}button:disabled{opacity:.6;cursor:default}.review-notice{background:var(--surface-subtle);padding:12px;overflow-wrap:anywhere}:focus-visible{outline:3px solid var(--care-600);outline-offset:2px}
</style>
