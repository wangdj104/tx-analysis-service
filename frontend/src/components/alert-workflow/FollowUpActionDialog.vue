<template>
 <el-dialog class="aw-dialog" :model-value="!!state.dialog" :title="t(action)" width="min(680px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!locked" :before-close="close" destroy-on-close @opened="focusForm">
  <div v-if="state.dialog&&detail" class="aw">
   <p>{{t('patient')}} #{{detail.patient.id}} · #{{detail.alertId}} · {{t(detail.mode)}} · {{t('version')}} {{detail.version}}</p>
   <p v-if="phaseText" class="aw-notice" role="status">{{phaseText}}</p><p v-if="validation" id="aw-action-error" role="alert">{{t('inputInvalid')}}</p><p v-if="state.error" role="alert">{{t(state.error)}}</p>
   <form :aria-describedby="validation?'aw-action-error':undefined" ref="formElement" @submit.prevent="submit" class="aw-action-form">
    <div class="aw-dialog-body"><fieldset :disabled="locked||state.commandPhase==='succeeded'">
     <template v-if="action==='ACCEPT'"><p>{{t('acceptHelp')}}</p><p>{{t('assignee')}}: {{assigneeName}} (#{{detail.responsibleUserId}})</p><p>{{t('due')}}: {{detail.dueAt||t('noDeadline')}}</p></template>
     <template v-if="['ASSIGN','ESCALATE'].includes(action)">
      <p v-if="action==='ESCALATE'">{{t('escalateHelp')}} {{t('escalationReset')}}</p><label>{{t('assignee')}}<select v-model.number="form.assigneeId" :required="action==='ASSIGN'"><option :value="null">{{t('noAssignee')}}</option><option v-for="person in state.assignees" :key="person.userId" :value="person.userId">{{person.displayName}} (#{{person.userId}}) · {{t(person.role)}}</option></select></label>
      <p v-if="!state.assignees.length" role="status">{{t('missingCandidates')}}</p>
      <template v-if="action==='ASSIGN'?detail.permissions?.canSetDeadline:state.capabilities?.canSetClinicalDeadline"><label>{{t('deadlineMode')}}<select v-model="form.deadlineMode"><option v-if="action==='ASSIGN'" value="preserve">{{t('preserve')}}</option><option value="clear">{{t('clearDeadline')}}</option><option value="set">{{t('setDeadline')}}</option></select></label><label v-if="form.deadlineMode==='set'">{{t('due')}}<input v-model="form.dueAt" type="text" placeholder="2026-10-20T14:30:00+08:00" required /></label></template>
     </template>
     <template v-if="['FOLLOW_UP','SUBMIT','FEEDBACK'].includes(action)">
      <label>{{t('note')}}<textarea v-model="form.note" rows="4" maxlength="2000" required /></label>
      <div class="aw-grid"><label>{{t('wallTime')}}<input v-model="form.wall" type="datetime-local" step="1" required /></label><label>{{t('offset')}}<input v-model="form.offset" type="text" pattern="[+-][0-9]{2}:[0-9]{2}" placeholder="+08:00" required /></label></div><p>{{t('offsetHelp')}}</p>
     </template>
     <template v-if="action==='SUBMIT'">
      <h3>{{t('evidence')}}</h3><p>{{t('referenceHelp')}}</p>
      <div v-for="(reference,index) in form.evidence" :key="index" class="aw-panel"><label>{{t('sourceType')}}<select v-model="reference.sourceType"><option v-for="kind in evidenceTypes" :key="kind" :value="kind">{{t(kind)}}</option></select></label><label>{{t('sourceId')}}<input v-model.number="reference.sourceId" type="number" min="1" step="1" required /></label><button type="button" @click="form.evidence.splice(index,1)">{{t('remove')}}</button></div>
      <button type="button" :disabled="form.evidence.length>=5" @click="form.evidence.push({sourceType:'BP_SELF_MONITOR',sourceId:null})">{{t('addReference')}}</button>
      <label class="aw-check"><input v-model="form.hasContact" type="checkbox" />{{t('contact')}}</label><template v-if="form.hasContact"><p>{{t('contactHelp')}}</p><label>{{t('relationship')}}<input v-model="form.relationship" maxlength="100" required /></label><label>{{t('contactOutcome')}}<textarea v-model="form.contactOutcome" maxlength="2000" required /></label></template>
     </template>
     <template v-if="action==='FEEDBACK'"><label>{{t('entryMode')}}<select v-model="form.entryMode"><option v-if="detail.permissions?.canDeclareSelf" value="SELF">{{t('SELF')}}</option><option value="ASSISTED">{{t('ASSISTED')}}</option></select></label><p>{{t('assistedHelp')}}</p><label>{{t('outcome')}}<select v-model="form.outcome"><option value="HANDLED">{{t('HANDLED')}}</option><option value="DIFFICULTY">{{t('DIFFICULTY')}}</option></select></label></template>
     <label v-if="action==='RETURN'">{{t('returnTarget')}}<select v-model="form.targetState"><option value="IN_PROGRESS">{{t('IN_PROGRESS')}}</option><option value="WAITING_FEEDBACK">{{t('WAITING_FEEDBACK')}}</option></select></label>
     <p v-if="action==='REVIEW_SOURCE_CHANGE'">{{t('sourceRevision')}}: {{detail.source?.sourceRevision}} · {{t('correctionHelp')}}</p>
     <template v-if="action==='RESOLVE_REFERENCE_CHANGE'"><label>{{t('evidence')}}<select v-model.number="form.evidenceId" required><option :value="null">{{t('resolution')}}</option><option v-for="reference in detail.evidence?.filter(item=>item.canResolve)" :key="reference.id" :value="reference.id">#{{reference.id}} · {{reference.restricted?t('restricted'):`${t(reference.sourceType)} #${reference.sourceId}`}}</option></select></label><label>{{t('resolution')}}<select v-model="form.resolution"><option value="WITHDRAW">{{t('WITHDRAW')}}</option><option value="REPLACE">{{t('REPLACE')}}</option></select></label><template v-if="form.resolution==='REPLACE'"><p>{{t('referenceHelp')}}</p><label>{{t('sourceType')}}<select v-model="form.replacementType"><option v-for="kind in evidenceTypes" :key="kind" :value="kind">{{t(kind)}}</option></select></label><label>{{t('sourceId')}}<input v-model.number="form.replacementId" type="number" min="1" step="1" required /></label></template></template>
     <p v-if="action==='CLOSE'" class="aw-notice">{{t(detail.mode==='CLINICAL'?'clinicalClose':'personalClose')}}</p>
     <label v-if="needsReason">{{t('reason')}}<textarea v-model="form.reason" rows="3" maxlength="1000" required /></label>
    </fieldset></div>
    <div class="aw-dialog-actions">
     <button v-if="!['uncertain','conflict','rejected','succeeded'].includes(state.commandPhase)" type="submit" :disabled="locked">{{t(locked?'saving':'submit')}}</button>
     <button v-if="state.commandPhase==='uncertain'" type="button" @click="retry">{{t('retryOriginal')}}</button>
     <button v-if="['conflict','rejected'].includes(state.commandPhase)" type="button" @click="newDecision">{{t('newDecision')}}</button>
     <button type="button" :disabled="locked" @click="close()">{{t('closeDialog')}}</button>
    </div>
   </form>
  </div>
 </el-dialog>
</template>
<script setup>
import {computed,ref,nextTick} from 'vue'
import {actionBody,responsibleDisplayName} from '@/utils/alertWorkflowForms'
import {t} from '@/utils/alertWorkflowCopy'
const props=defineProps({flow:{type:Object,required:true}}),emit=defineEmits(['changed','closed']),state=props.flow.state,formElement=ref(null),validation=ref(false)
const action=computed(()=>state.dialog?.action),detail=computed(()=>state.detail),form=computed(()=>state.dialog?.draft),locked=computed(()=>['submitting','uncertain'].includes(state.commandPhase))
const needsReason=computed(()=>!['ACCEPT','FOLLOW_UP','SUBMIT'].includes(action.value)&&(action.value!=='FEEDBACK'||form.value?.outcome==='DIFFICULTY'))
const phaseText=computed(()=>({submitting:t('saving'),uncertain:t('uncertain'),conflict:t('conflict'),rejected:t('rejected'),succeeded:t('succeeded')})[state.commandPhase])
const evidenceTypes=['HEALTH_MEASUREMENT','BP_SELF_MONITOR','MEDICAL_RECORD','CARE_PLAN_ACTION']
const assigneeName=computed(()=>responsibleDisplayName(detail.value,state.assignees,state.events)||t('unknown'))
async function submit(){validation.value=false;try{const body=actionBody(action.value,form.value,detail.value,state.capabilities);const result=await props.flow.submit({body});if(result.status==='succeeded')emit('changed')}catch{validation.value=true}}
async function retry(){const result=await props.flow.submit({retry:true});if(result.status==='succeeded')emit('changed')}
async function newDecision(){const cap=await props.flow.loadCapabilities();if(cap.status!=='enabled')return;const result=await props.flow.refreshDetail();if(result.status==='succeeded'){props.flow.newDecision();if(['ASSIGN','ESCALATE'].includes(action.value))await props.flow.loadAssignees(action.value==='ESCALATE'?'CLINICAL':detail.value.mode)}}
function close(done){if(!props.flow.closeDialog())return;emit('closed');if(typeof done==='function')done()}
async function focusForm(){await nextTick();formElement.value?.querySelector('input:not(:disabled),select:not(:disabled),textarea:not(:disabled),button:not(:disabled)')?.focus()}
</script>
