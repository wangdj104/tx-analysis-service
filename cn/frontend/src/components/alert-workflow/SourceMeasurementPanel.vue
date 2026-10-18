<template>
 <section ref="panelElement" tabindex="-1" class="aw aw-panel aw-source-panel">
  <h3>{{t('source')}}</h3>
  <p v-if="state.sourcePhase==='succeeded'&&state.receipt" role="status">{{t('saved')}} · {{t(state.receipt.sourceType)}} #{{state.receipt.sourceId}}</p>
  <p v-if="state.sourceReadPhase==='loading'" role="status">{{t('loading')}}</p>
  <p v-if="state.sourceReadPhase==='unavailable'" class="aw-notice" role="alert">{{t(state.sourcePhase==='succeeded'?'sourceSavedReadUnavailable':'sourceUnavailable')}}</p><button v-if="state.sourceReadPhase==='unavailable'&&state.sourceLocator" type="button" @click="retryRead">{{t('retryRead')}}</button>
  <div v-if="state.receipt" class="aw-controls"><router-link v-for="item in state.receipt.followUps||[]" :key="item.alertId" class="aw-link" :to="`/alert-workflows/${item.alertId}?patientId=${state.patientId}`">{{t('open')}} #{{item.alertId}}</router-link></div>
  <template v-if="source">
   <p>{{t(source.sourceType)}} #{{source.id}} · {{t('sourceRevision')}} {{source.sourceRevision}} · {{t('sourceVersion')}} {{source.sourceVersion}} · {{t(source.sourceStatus)}}</p>
   <p v-if="source.sourceStatus==='INVALIDATED'" class="aw-notice">{{t('invalidSource')}}</p><SourceValues :source="source" />
   <h3>{{t('assessment')}}</h3><p v-if="!source.assessments?.length">{{t('unknown')}}</p>
   <article v-for="assessment in source.assessments||[]" :key="assessment.metric"><strong>{{t(assessment.metric)}} · {{t(assessment.status)}}</strong><p>{{t('policy')}}: {{assessment.policyKey}}</p><dl><template v-for="(value,key) in assessment.thresholds||{}" :key="key"><dt>{{t(key)}}</dt><dd>{{t('withheld')}}</dd></template></dl><p v-if="assessment.status==='UNCLASSIFIABLE'" class="aw-notice">{{t('confirmContext')}}</p><p v-if="assessment.status==='NORMAL'">{{t('normalNoClosure')}}</p><p class="aw-notice">{{t('comparisonUnitUnavailable')}}</p><p v-if="assessment.period">{{t('period')}}: {{assessment.period}}</p></article>
   <div class="aw-controls"><button type="button" @click="loadHistory">{{t('sourceHistory')}}</button><template v-if="state.capabilities?.canAdopt"><button type="button" :disabled="locked" @click="begin('correct')">{{t('correct')}}</button><button type="button" :disabled="locked" @click="begin('invalidate')">{{t('invalidate')}}</button><button type="button" :disabled="locked||source.sourceStatus==='INVALIDATED'" @click="begin('adopt')">{{t('manual')}}</button></template></div>
   <form v-if="operation" @submit.prevent="save" class="aw-panel">
    <h3>{{t(operation==='correct'?'correct':operation==='invalidate'?'invalidate':'manual')}}</h3><p>{{t(operation==='adopt'?'manualHelp':'correctionHelp')}}</p>
    <p v-if="message" role="alert">{{t(message)}}</p><p v-if="state.sourcePhase==='uncertain'" class="aw-notice">{{t('uncertain')}}</p><p v-if="state.sourcePhase==='conflict'" class="aw-notice">{{t('conflict')}}</p><p v-if="state.sourcePhase==='rejected'" class="aw-notice">{{t('rejected')}}</p>
    <fieldset :disabled="locked">
     <label v-if="operation==='adopt'">{{t('metric')}}<select v-model="form.metric" required><option v-for="assessment in source.assessments||[]" :key="assessment.metric" :value="assessment.metric">{{t(assessment.metric)}} · {{t(assessment.status)}}</option></select></label>
     <template v-if="operation==='correct'"><p class="aw-notice">{{t('correctionMetadataReadOnly')}}</p>
      <template v-if="source.sourceType==='HEALTH_MEASUREMENT'"><label>{{t('metric')}}<select v-model="form.metricType"><option v-for="metric in ['BP','GLUCOSE','SPO2','WEIGHT','HEART_RATE','TEMPERATURE','CUSTOM']" :key="metric" :value="metric">{{t(metric)}}</option></select></label><label>{{t('primary')}}<input v-model="form.valuePrimary" type="text" inputmode="decimal" step="any" required /></label><label v-if="form.metricType==='BP'">{{t('secondary')}}<input v-model="form.valueSecondary" type="text" inputmode="decimal" step="1" required /></label><template v-else><p>{{t('currentSecondary')}}: {{source.valueSecondary ?? t('unknown')}}</p><label>{{t('secondaryAction')}}<select v-model="form.secondaryAction" required><option value="" disabled>{{t('chooseSecondary')}}</option><option value="preserve">{{t('keepSecondary')}}</option><option value="set">{{t('changeSecondary')}}</option><option value="clear">{{t('clearSecondary')}}</option></select></label><label v-if="form.secondaryAction==='set'">{{t('secondary')}}<input v-model="form.valueSecondary" type="text" inputmode="decimal" step="any" required /></label></template><label>{{t('unit')}}<input v-model="form.unit" required /></label><label>{{t('measurementTime')}}<input v-model="form.measuredAt" type="datetime-local" step="0.000001" required /></label></template>
      <template v-else><p class="aw-notice">{{t('bpPreservationHelp')}}</p><label>{{t('measureType')}}<select v-model="form.measureType" @change="edited('measureType')"><option value="BP">{{t('BP')}}</option><option value="BG">{{t('BG')}}</option><option :value="form.measureType==='BOTH'?'BOTH':'BP_BG'">{{t('BP_BG')}}</option></select></label><div class="aw-grid"><label>{{t('recordDate')}}<input v-model="form.recordDate" @input="edited('recordDate')" type="date" required /></label><label>{{t('recordTime')}}<input v-model="form.recordTime" @input="edited('recordTime')" type="text" pattern="(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,6})?)?" placeholder="HH:mm:ss.ffffff" autocomplete="off" spellcheck="false" required /><small>{{t('recordTimeHelp')}}</small></label></div><template v-if="form.measureType!=='BG'"><label>{{t('systolic')}}<input v-model="form.systolicBp" @input="edited('systolicBp')" type="text" inputmode="decimal" step="1" required /></label><label>{{t('diastolic')}}<input v-model="form.diastolicBp" @input="edited('diastolicBp')" type="text" inputmode="decimal" step="1" required /></label></template><template v-if="form.measureType!=='BP'"><label>{{t('glucose')}}<input v-model="form.bloodGlucose" @input="edited('bloodGlucose')" type="text" inputmode="decimal" step="any" required /></label><label>{{t('unit')}}<select v-model="form.bgUnit" @change="edited('bgUnit')"><option v-if="!['mmol/L','mg/dL'].includes(form.bgUnit)" :value="form.bgUnit">{{form.bgUnit?.trim() ? form.bgUnit : t('preservedUnknown')}}</option><option value="mmol/L">mmol/L</option><option value="mg/dL">mg/dL</option></select></label><label>{{t('period')}}<input v-model="form.measurePeriod" @input="edited('measurePeriod')" /></label></template></template>
      <div v-if="clearedFields.length" class="aw-notice" role="status"><p>{{t('typeChangeClears')}}</p><ul><li v-for="field in clearedFields" :key="field">{{t(fieldLabels[field])}}</li></ul></div>
      <label>{{t('offset')}}<input v-model="form.offset" type="text" pattern="[+-][0-9]{2}:[0-9]{2}" placeholder="+08:00" required /></label><p>{{t('offsetHelp')}}</p>
     </template>
     <label>{{t('reason')}}<textarea v-model="form.reason" maxlength="1000" rows="3" required /></label>
    </fieldset>
    <div class="aw-controls"><button v-if="!['uncertain','conflict','rejected'].includes(state.sourcePhase)" type="submit" :disabled="locked">{{t('submit')}}</button><button v-if="state.sourcePhase==='uncertain'" type="button" @click="retry">{{t('retryOriginal')}}</button><button v-if="['conflict','rejected'].includes(state.sourcePhase)" type="button" @click="newDecision">{{t('newDecision')}}</button><button type="button" :disabled="locked" @click="operation=null">{{t('cancel')}}</button></div>
   </form>
   <template v-if="historyVisible"><h3>{{t('sourceHistory')}}</h3><article v-for="revision in state.sourceHistory" :key="revision.id"><p>{{t('sourceRevision')}} {{revision.sourceRevision}} · {{t(revision.sourceStatus)}}</p><p>{{t('recorder')}} #{{revision.recordedBy}} · {{t('occurred')}}: {{formatWorkflowTime(revision.occurredAt)}} · {{t('captured')}}: {{formatWorkflowTime(revision.recordedAt)}}</p><p>{{t('reason')}}: {{revision.correctionReason||'—'}}</p><SourceValues :source="revision.snapshot" /></article><p v-if="!state.sourceHistory.length">{{t('noResult')}}</p><button v-if="state.sourceCursor" type="button" @click="loadHistory(state.sourceCursor)">{{t('more')}}</button></template>
  </template>
 </section>
</template>
<script setup>
import {measurementFits,sameMeasurementDecimal} from '@/utils/measurementNumbers'
import {formatWorkflowTime} from '@/utils/alertWorkflowTime'
import {computed,reactive,ref,watch,nextTick} from 'vue'
import {measurementInstant} from '@/utils/alertWorkflowForms'
import {t} from '@/utils/alertWorkflowCopy'
import SourceValues from './SourceValues.vue'
import './workflow.css'
const props=defineProps({flow:{type:Object,required:true}}),emit=defineEmits(['changed']),state=props.flow.state,source=computed(()=>state.source),operation=ref(null),form=reactive({}),historyVisible=ref(false),message=ref(null),panelElement=ref(null)
const locked=computed(()=>['submitting','uncertain'].includes(state.sourcePhase))
const dirty=reactive({}),fieldLabels={systolicBp:'systolic',diastolicBp:'diastolic',bloodGlucose:'glucose',bgUnit:'unit',measurePeriod:'period'}
let ownerEpoch=0
const compatibleType=type=>type==='BOTH'?'BP_BG':type
const typeChanged=computed(()=>!!dirty.measureType&&compatibleType(form.measureType)!==compatibleType(source.value?.measureType))
const clearedFields=computed(()=>operation.value==='correct'&&source.value?.sourceType==='BP_SELF_MONITOR'&&typeChanged.value?(form.measureType==='BP'?['bloodGlucose','bgUnit','measurePeriod']:form.measureType==='BG'?['systolicBp','diastolicBp']:[]):[])
function edited(key){dirty[key]=true}
function resetForm(){Object.keys(form).forEach(key=>delete form[key]);Object.keys(dirty).forEach(key=>delete dirty[key])}
function hydrate(){resetForm();Object.assign(form,{...source.value,secondaryAction:'preserve',reason:'',offset:'',metric:source.value?.assessments?.[0]?.metric,measuredAt:source.value?.measuredAt?.replace(' ','T')})}
function begin(kind){if(locked.value||!state.capabilities?.canAdopt||!source.value)return;operation.value=kind;message.value=null;hydrate()}
// 会话失效由现有流程同步清空来源；本地代次阻止失效前的表单回调继续处理。
function captureOwner(){return {epoch:ownerEpoch,patient:state.patientId,type:state.sourceLocator?.type,id:state.sourceLocator?.id}}
function owns(owner){return owner&&owner.epoch===ownerEpoch&&owner.patient===state.patientId&&owner.type===state.sourceLocator?.type&&String(owner.id)===String(state.sourceLocator?.id)}
function readable(owner){return owns(owner)&&state.sourceReadPhase==='succeeded'&&source.value?.sourceType===owner.type&&String(source.value?.id)===String(owner.id)}
async function loadHistory(cursor=null){const owner=captureOwner();if(!readable(owner))return;historyVisible.value=true;await props.flow.loadSourceHistory(owner.type,owner.id,typeof cursor==='string'||typeof cursor==='number'?cursor:null)}
// 已提交的回执不重发；重试只读取当前所有者的原记录。
async function retryRead(){const owner=captureOwner();if(!owner.type||!owner.id||locked.value)return;const result=await props.flow.readSource(owner.type,owner.id);if(result.status==='succeeded'&&readable(owner)&&historyVisible.value)await loadHistory()}
function correction(){
 const s=source.value,number=value=>value!==null&&value!==undefined&&String(value).trim()!==''&&Number.isFinite(Number(value));
 // 旧值不变时沿原合同；新改数值必须能被现有列原样保存。
 const storage=(key,value,precision,scale)=>{if(value==null)return;if(sameMeasurementDecimal(value,s[key]))return;if(!measurementFits(value,precision,scale))throw new Error('INPUT_REQUIRED')};
 if(s.sourceType==='HEALTH_MEASUREMENT'){
  if(!number(form.valuePrimary))throw new Error('VALUE_REQUIRED');
  const body={metricType:form.metricType,valuePrimary:form.valuePrimary,unit:form.unit,measuredAt:form.measuredAt,sourceType:s.sourceKind,reason:form.reason,occurredAt:measurementInstant(form.measuredAt,form.offset)};
  // 次值属于原审计字段；不改就省略，由同次事务保留当前原值。
  if(form.metricType==='BP'){if(!number(form.valueSecondary))throw new Error('SECONDARY_REQUIRED');body.valueSecondary=form.valueSecondary}
  else if(form.secondaryAction==='set'){if(!number(form.valueSecondary))throw new Error('SECONDARY_REQUIRED');body.valueSecondary=form.valueSecondary}
  else if(form.secondaryAction==='clear')body.valueSecondary=null;
  else if(form.secondaryAction!=='preserve')throw new Error('SECONDARY_DECISION_REQUIRED');
  storage('valuePrimary',body.valuePrimary,12,3);if(Object.hasOwn(body,'valueSecondary'))storage('valueSecondary',body.valueSecondary,12,3);
  body.valuePrimary=Number(body.valuePrimary);if(body.valueSecondary!=null)body.valueSecondary=Number(body.valueSecondary);
  return body;
 }
 // 未触碰的控件沿用原始值，避免原生时间控件显示归一化丢失秒或微秒。
 const effective=key=>dirty[key]?form[key]:s[key];
 const body={reason:form.reason,occurredAt:measurementInstant(`${effective('recordDate')}T${effective('recordTime')}`,form.offset)};
 const type=typeChanged.value?form.measureType:s.measureType;
 if(typeChanged.value)body.measureType=form.measureType;
 const fields=['recordDate','recordTime',...(type==='BG'?[]:['systolicBp','diastolicBp']),...(type==='BP'?[]:['bloodGlucose','bgUnit','measurePeriod'])];
 for(const key of fields){
  if(!dirty[key])continue;
  const value=form[key];
  if(['systolicBp','diastolicBp','bloodGlucose'].includes(key)){
   if(sameMeasurementDecimal(value,s[key]))continue;
   if(!number(value))throw new Error('INPUT_REQUIRED');
   storage(key,value,key==='bloodGlucose'?5:10,key==='bloodGlucose'?2:0);
   if(key!=='bloodGlucose'&&(Number(value)>2147483647||Number(value)<-2147483648))throw new Error('INPUT_REQUIRED');
   body[key]=Number(value);
  }else if(value!==s[key])body[key]=value;
 }
 // 只有明确改变最终类型才清空相反指标，并在保存前列明影响。
 if(typeChanged.value){
  for(const key of type==='BP'?['systolicBp','diastolicBp']:type==='BG'?['bloodGlucose']:['systolicBp','diastolicBp','bloodGlucose'])if(!number(effective(key)))throw new Error('INPUT_REQUIRED');
  for(const key of clearedFields.value)body[key]=null;
 }

 return body;
}
watch(()=>form.metricType,(type,previous)=>{if(operation.value!=='correct'||!type||!previous||type===previous)return;form.valueSecondary=null;form.secondaryAction=type==='BP'?'set':''},{flush:'sync'})
async function execute(retry=false){
 message.value=null;const owner=captureOwner();
 try{
  const s=source.value;if(!s||!owns(owner))return;
  const result=operation.value==='adopt'?await props.flow.adoptSource({sourceType:s.sourceType,sourceId:s.id,metric:form.metric,manualFollowUp:true,reason:form.reason},{retry}):await props.flow.saveSource(s.sourceType,retry?{}:operation.value==='invalidate'?{reason:form.reason}:correction(),{id:s.id,operation:operation.value,retry});
  if(!owns(owner))return;
  if(result.status==='succeeded'){
   operation.value=null;emit('changed');
   // 父组件可能同步关闭或刷新；读取历史前重新核验来源仍然存在且属于当前所有者。
   if(historyVisible.value&&readable(owner))await loadHistory();
  }else if(!['uncertain','conflict','rejected','stale'].includes(result.status))message.value='inputInvalid';
 }catch{if(owns(owner))message.value='inputInvalid'}
}
const save=()=>execute(),retry=()=>execute(true)
async function newDecision(){
 const owner=captureOwner();if(!readable(owner)||locked.value)return;
 const result=await props.flow.readSource(owner.type,owner.id);
 if(result.status==='succeeded'&&readable(owner)&&props.flow.newDecision('source')){hydrate();message.value=null}
}
defineExpose({async beginOperation(kind){if(kind)begin(kind);await nextTick();panelElement.value?.focus({preventScroll:true});panelElement.value?.scrollIntoView({behavior:'auto',block:'start'})}})
watch(()=>`${state.sourceLocator?.type}:${state.sourceLocator?.id}`,()=>{ownerEpoch++;operation.value=null;historyVisible.value=false;message.value=null;resetForm()},{flush:'sync'})
watch(()=>state.patientId,()=>{ownerEpoch++;operation.value=null;historyVisible.value=false;message.value=null;resetForm()},{flush:'sync'})
</script>
